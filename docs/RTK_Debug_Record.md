# RTK调试记录

## 测试环境
- 数据源：RTCM3 MSM4流（BDS-only，13颗卫星）
- 对比基准：RTKLIB C版（2.5.0）rnx2rtkp RTK定位
- 历元数：212
- 基准站坐标：硬编码 C 版 SPP 平均值（待实现 avepos）
- 验证标准：Java版与C版定位结果差异

---

## 当前状态

### 最终定位精度（多系统短基线，Joseph形式修复后）

| 指标 | 修复前（标准形式） | 修复后（Joseph形式） | 改善幅度 |
|------|-------------------|---------------------|----------|
| Fix解比例 | 0% | **88.7%** (86/97) | ↑ 88.7% |
| AR ratio | 1.04~1.65 | **42~384** | ↑ 230倍 |
| 位置方差 | 0.03~0.04 | **0.00015~0.00017** | ↓ 200倍 |

### 仍存在的差异

| 问题 | 描述 | 优先级 |
|------|------|--------|
| dE 系统偏差 ~0.8m | 可能与卫星数差异（Java 比 C 少 1-2 颗）或观测值权重模型有关；ddres()残差剔除修复后待验证 | 🟡 |
| 后段历元精度波动 | epoch 210, 239 出现 5m 跳变；udbias() rejc重置逻辑修复后待验证 | 🟡 |
| Java 卫星数少 1-2 颗 | 可能影响可用观测值数量和几何结构 | 🟢 |

---

## Bug #1：周跳检测函数未被调用（最关键）

### 文件
`src/main/java/org/rtklib/java/rtkpos/RtkCore.java` — `udbias()` 方法

### 问题
C 版 `udbias()` 中调用了 4 个周跳检测函数，Java 版一个都没调用：

| C 版函数 | 功能 | Java 版状态 |
|----------|------|-------------|
| `detslp_ll()` | LLI 标志检测周跳 | ❌ 未调用 |
| `detslp_gf()` | 几何无关组合检测周跳 | ❌ 未调用 |
| `detslp_code()` | 码类型变化检测周跳 | ❌ 未调用 |
| `detslp_dop()` | 多普勒-相位差检测周跳 | ❌ 未调用 |

Java 版有 `CycleDetect` 类（`src/main/java/org/rtklib/java/cycle/CycleDetect.java`）实现了部分功能，
但 `RtkCore.udbias()` 中没有调用它。

### C 版参考代码（rtkpos.c）
```c
static void udbias(rtk_t *rtk, double tt, const obsd_t *obs, const int *sat,
                   const int *iu, const int *ir, int ns, const nav_t *nav) {
    // 清除 slip 标志
    for (i=0;i<ns;i++) for (k=0;k<nf;k++) rtk->ssat[sat[i]-1].slip[k]&=0xFC;

    // 周跳检测
    detslp_dop(rtk,obs,iu,ns,1,nav);   // rover: 多普勒-相位差
    detslp_dop(rtk,obs,ir,ns,2,nav);   // base:  多普勒-相位差
    for (i=0;i<ns;i++) {
        detslp_code(rtk, obs, iu[i], 1);  // rover: 码类型变化
        detslp_code(rtk, obs, ir[i], 2);  // base:  码类型变化
        detslp_ll(rtk,obs,iu[i],1);       // rover: LLI标志
        detslp_ll(rtk,obs,ir[i],2);       // base:  LLI标志
        detslp_gf(rtk,obs,iu[i],ir[i],nav); // GF组合
    }
    // ... 后续处理 ...
}
```

### Java 版当前代码（RtkCore.java）
```java
private static void udbias(Rtk rtk, double tt, Obsd[] obs, int[] sat, int[] iu, int[] ir,
                           int ns, Nav nav, int nf) {
    PrcOpt opt = rtk.opt;
    for (int i = 0; i < ns; i++) {
        for (int k = 0; k < nf; k++) {
            rtk.ssat[sat[i] - 1].slip[k] &= 0xFC;  // 只清除，不检测
        }
    }
    // 缺失：detslp_dop, detslp_code, detslp_ll, detslp_gf

    for (int k = 0; k < nf; k++) {
        // ... outlier/slip 重置逻辑（依赖 slip 标志，但 slip 标志从未被更新）...
    }
}
```

### 影响链
```
周跳漏检 → 模糊度状态偏差累积 → 残差偏大
→ 超过 outlier 阈值 → 被标记为 outlier
→ 触发模糊度重置 → 重新收敛 → 精度下降
→ 再次触发 outlier → 恶性循环
```

### 修复方案
在 `udbias()` 中插入 5 个新方法（`detslp_dop`, `detslp_code`, `detslp_ll`, `detslp_gf`, `gfobs`），
调用位置在 `slip[k] &= 0xFC` 之后、`for (int k = 0; k < nf; k++)` 之前。

### 预期效果
- Outlier 检测次数：479 → 接近 224（~50%减少）
- 模糊度重置次数：222 → 接近 158（~30%减少）
- 异常历元(>20m)：16.5% → 预期 5-8%
- 整体 3D RMS：23.6m → 预期 8-12m

### Bug #1a：缺少 ph/pt 保存逻辑（🔴→✅ 已修复）

**文件**: `src/main/java/org/rtklib/java/rtkpos/RtkCore.java` — `rtkpos()` 末尾

**问题**：C 版在 `rtkpos()` 末尾保存每个卫星的相位观测值和观测时间到 `ssat`，Java 版完全缺失。

**修复**：在 `rtkpos()` 末尾（`slipc++` 循环之前）添加了 ph/pt 保存逻辑，与 C 版一致。

---

### Bug #1b：detslpDop 中 tt 使用 Math.abs() 错误（🟡→✅ 已修复）

**文件**: `src/main/java/org/rtklib/java/rtkpos/RtkCore.java` — `detslpDop()` 方法

**问题**：`dt = Math.abs(timediff(...))` 丢失了时间差符号，导致相位差 `dph` 符号反转。

**修复**：改为 `dt = TimeSystem.timediff(...)` 保留原始符号，判断条件改为 `Math.abs(dt) < DTTOL`。

---

### Bug #1c：detslpLl 中 slip 位操作（🟡→✅ 已验证）

**文件**: `src/main/java/org/rtklib/java/rtkpos/RtkCore.java` — `detslpLl()` 方法

**验证结果**：LLI bit 操作、slip 保存、half 标志逻辑与 C 版一致，无需额外修改。

---

### Bug #1d：函数签名 nf 参数（🟢→✅ 已对齐）

**文件**: `src/main/java/org/rtklib/java/rtkpos/RtkCore.java` — `detslp_*` 方法

**验证结果**：当前用 `rtk.opt.nf`，C 版用 `NF(&rtk->opt)`，功能等价。

---

### 修复后验证结果

所有 5 个检测函数与 C 版对比：

| 函数 | Java 版 vs C 版 | 状态 |
|------|----------------|------|
| `gfobs()` | 逻辑完全一致 | ✅ |
| `detslpLl()` | LLI bit 操作、slip 保存、half 标志一致 | ✅ |
| `detslpGf()` | GF 跳变检测、slip 标记一致 | ✅ |
| `detslpCode()` | 码类型变化检测一致 | ✅ |
| `detslpDop()` | 已修复 tt 符号问题，逻辑一致 | ✅ |
| `udbias()` 调用顺序 | 与 C 版完全一致 | ✅ |
| `half` 标志更新 | 逻辑等价 | ✅ |

---

## Bug #2：rtk.sol.time 未更新导致 Kalman 滤波器冻结（已修复）

### 文件
`src/main/java/org/rtklib/java/rtkpos/RtkCore.java` — `rtkpos()` 方法

### 问题
C 版 rtkpos.c 中 P[0]!=0 时更新 `rtk->sol.time = obs[0].time`，Java 版缺失此 else 分支。

### 影响链
1. `rtk.sol.time` 不更新 → `rtk.tt = timediff(rtk.sol.time, prevTime)` 永远为 0
2. `tt=0` → `udpos()` 中状态转移矩阵 F 的速度项 `F[i*(i+3)] = tt = 0`，位置不传播
3. `tt=0` → 过程噪声 `Q = prn^2 * |tt| = 0`，协方差矩阵 P 不增长
4. `tt=0` → `udbias()` 中模糊度过程噪声也为 0
5. 结果：Kalman 滤波器完全冻结

### 修复
```java
} else {
    rtk.sol.time = obs[0].time;
}
```

---

## Bug #3：varerr() 观测噪声模型不完整（已修复）

### 文件
`src/main/java/org/rtklib/java/rtkpos/RtkCore.java` — `varerr()` 方法

### 问题 3a：缺少 SNR 调整和接收机噪声项
C 版 varerr() 包含 SNR 调整项 `10^(0.1*(thresh - snr))` 和接收机标准差项 `err[6]/err[7]`，Java 版缺失。

### 问题 3b：星座因子硬编码且 IRNSS 错误
Java 版使用硬编码值，IRNSS 用 1.0 而非 C 版的 1.5，缺少 GPS 和 SBS 分支。

### 修复
- 添加 SNR 调整项和接收机标准差项
- 使用 `Constants.EFACT_*` 常量替代硬编码
- 添加所有星座分支

### 注意
默认配置下 `err[6]=0.0` 和 `err[7]=0.0`，SNR/接收机噪声项不影响结果。
但星座因子差异会影响所有情况。

---

## Bug #4：对流层映射函数简化（已修复）

### 文件
`src/main/java/org/rtklib/java/rtkpos/RtkCore.java` — `zdres()` 方法

### 问题
Java 版使用简化映射函数 `1/sin(el)` 替代 C 版的 NMF（Niell Mapping Function）。

### 数值差异

| 高度角 | 1/sin(el) | NMF | 差异 |
|--------|-----------|-----|------|
| 10° | 5.76 | ~5.5 | ~0.26 |
| 15° | 3.86 | ~3.7 | ~0.16 |
| 20° | 2.92 | ~2.8 | ~0.12 |
| 30° | 2.00 | ~1.95 | ~0.05 |

### 修复
使用 `TroposphereModel.tropmapf()` 替代 `1/sin(el)`。

### 实测影响
映射函数差异（0.01~0.06）不足以单独解释 14m 偏差，但会与其他因素累积。

---

## Bug #5：LAMBDA输出索引错误（行优先/列优先混淆）（✅ 已修复）

### 文件
`src/main/java/org/rtklib/java/rtkpos/RtkCore.java` — `relpos()` 中 LAMBDA 调用后读取 `b[]`

### 问题
`org.rtklib.java.ambiguity.Lambda` 输出固定解 `F[n*m]` 使用**行优先**存储（`F[i*m+j]`），
但调用方用**列优先**方式 `b[i]` 读取，导致交错取到最优/次优解。

### C版 vs Java版存储对比

C版 `lambda.c` search() 输出（列优先）：
```c
for (i=0;i<n;i++) zn[i+nn*n]=z[i];   // nn=0:最优解列, nn=1:次优解列
```
- `b[0..n-1]` = 最优解（所有模糊度）
- `b[n..2n-1]` = 次优解

C版调用方 `rtkpos.c`：
```c
bias[i]=b[i];   // b[i] 直接取到第i个模糊度的最优解 ✅
```

Java版 `Lambda.java` search() 输出（行优先）：
```java
for (i = 0; i < n; i++) zn[i * m + nn] = z[i];   // m=2
```
- `b[0]` = amb0最优, `b[1]` = amb0次优
- `b[2]` = amb1最优, `b[3]` = amb1次优
- `b[i*2]` = 第i个模糊度最优解, `b[i*2+1]` = 第i个模糊度次优解

Java版调用方 `RtkCore.java`（修复前）：
```java
ddBias[i] = b[i];      // ❌ 交错取到最优/次优解
```

### 错误示例（n=5, m=2）

| 索引 | `b[i]` 取到的值 | 应该取的值 | 结果 |
|------|-----------------|-----------|------|
| i=0 | amb0最优 ✅ | amb0最优 | 正确 |
| i=1 | amb0次优 ❌ | amb1最优 | 可能差几周 |
| i=2 | amb1最优 ❌ | amb2最优 | 错位1个模糊度 |
| i=3 | amb1次优 ❌ | amb3最优 | 错位+次优 |
| i=4 | amb2最优 ❌ | amb4最优 | 错位2个模糊度 |

### 影响链
```
b[i]交错取最优/次优解 → 模糊度固定到错误整数
→ 整周模糊度偏差数周 → 位置偏差数十米
→ Fix解偏差约24m
```

### 修复
```java
// 修复前
ddBias[i] = b[i];
y_dd[i] -= b[i];

// 修复后
ddBias[i] = b[i * 2];      // 行优先 F[i*m+0]，取第i个模糊度的最优解
y_dd[i] -= b[i * 2];
```

### 备注
另一个Java版 `org.gogpsproject.positioning.Lambda` 使用列优先存储（与C版一致），
其调用方用 `b[i]` 取值是正确的，不存在此Bug。

---

## Bug #6：Kalman 协方差更新数值不稳定（✅ 已修复，采用 Joseph 形式）

### 文件
`src/main/java/org/rtklib/java/kalman/KalmanFilter.java` — `update()` 方法

### 问题
C版 `filter_()` 使用标准形式 `P = (I-KH)*P` 更新协方差。Java版使用EJML库，
运算顺序与C版自定义 `matmul()` 不同，在H矩阵病态条件下标准形式导致P矩阵
失去正定性，AR ratio 极低（1.04~1.65），Fix 解比例为 0%。

### 修复方案
采用 Joseph 形式替代标准形式：
```
C版（标准形式）：    P_new = (I - K*H) * P
Java版（Joseph形式）：P_new = (I - K*H) * P * (I - K*H)^T + K * R * K^T
```

当 K 为精确最优增益时，Joseph 形式与标准形式数学等价，是标准形式的数值稳定超集。

### 测试验证

#### 数据集A：多系统短基线（基线~200m，GPS+BDS）

| 指标 | 标准形式（修复前） | Joseph形式（修复后） | 提升 |
|------|-------------------|---------------------|------|
| AR ratio | 1.04~1.65 | 42~384 | ↑ 230倍 |
| Fix解比例 | 0% | 88.7% (86/97) | ↑ 88.7% |
| LAMBDA s[0] | 31~244 | 21~22 | ↓ 残差更小更稳定 |
| LAMBDA s[1] | 32~253 | 4572~4944 | ↑ 次优解残差大幅增加 |
| 位置方差 | 0.03~0.04 | 0.00015~0.00017 | ↓ 200倍 |

ratio 收敛过程：42 → 86 → 212 → 384 → 稳定在 200+

#### 数据集B：非配对数据 — 无效，不可作为测试依据

| 指标 | 标准形式（修复前） | Joseph形式（修复后） | 说明 |
|------|-------------------|---------------------|------|
| AR ratio | 1.04~1.65 | 1.04~6.10 | 有改善但不够 |
| Fix解比例 | 0% | 8% (4/50) | 少量Fix |

**⚠️ 此数据无效，不可作为测试依据：**
- Rover 和 Base **不是配对的基站/测站**
- 两个站点位于完全不同的地理位置（相距数百公里）
- Rover 和 Base 历元数差异大，采样率/时间不同步
- 持续出现-16~-20周的双差残差，表明数据质量极差
- 此数据的测试结果仅反映"非配对数据"的失败情况，与 Joseph 形式无关

#### 数据集C：单系统BDS短基线（基线~420m，仅BDS）— 正确配对但数据质量差

| 指标 | Joseph形式（Java版） | C版（rnx2rtkp EX 2.5.0） |
|------|---------------------|--------------------------|
| AR ratio | 1.05~1.16 | **0.0**（全部为Float） |
| Fix解比例 | 0% (0/239) | **0%** (0/240) |
| 解类型 | 全Float (Q=2) | 全Float (Q=2) |
| 卫星数 | 7~10颗 | 7~10颗 |
| 频点数 | 2 (B1I/B2I) | 2 (L1+L2) |
| 基线长度 | ~420m | ~420m |

**此数据C版同样无法Fix，说明是数据质量问题而非Java版bug。**

##### 根因分析：模糊度浮点解精度差

| 指标 | 数据集C（无法Fix） | 数据集A（Fix=88.7%） |
|------|---------------------|---------------------|
| 模糊度浮点值偏差 | **0.5~2.5周** | **0.01~0.02周** |
| 双差残差平均 | **19.2周** | **8.6周** |
| 双差残差最大 | **61.6周** | **18.0周** |
| LAMBDA s[0] | **3446~4662** | **21~22** |
| 模糊度方差（后期） | 15.8~17.3（卡住） | 100.0（稳定） |
| Qb_diag | 0.0005~0.02 | 0.00007~0.001 |

数据集C的模糊度浮点值远离整数（偏差1~2.5周），导致LAMBDA搜索空间中
多个整数候选的残差接近（s[0]≈s[1]），ratio≈1.0。

可能原因：多路径效应严重、电离层/对流层误差大、观测噪声大。

##### C版验证方法

使用 RTKLIB EX 2.5.0 的 `rnx2rtkp.exe` 命令行工具：

```bash
# 1. RTCM3转RINEX（含导航文件）
convbin.exe -r rtcm3 -n rover.nav -o rover.obs rover.rtcm3
convbin.exe -r rtcm3 -n base.nav -o base.obs base.rtcm3

# 2. C版RTK定位（Kinematic模式，BDS，2频点，ratio阈值3.0）
rnx2rtkp.exe -p 2 -f 2 -v 3.0 -sys C -o c_result.pos rover.obs base.obs rover.nav base.nav
```

C版结果：全部历元Q=2（Float），ratio=0.0，与Java版结论一致。

### 代码实现

`KalmanFilter.java` 第 207~216 行：

```java
SimpleMatrix Ic = MatrixUtil.identity(k);
SimpleMatrix KHc = MatrixUtil.multiply(K, HcMat);
SimpleMatrix I_KH = MatrixUtil.subtract(Ic, KHc);

// Joseph形式协方差更新: P_new = (I-KH)*P*(I-KH)' + K*R*K'
SimpleMatrix I_KH_T = MatrixUtil.transpose(I_KH);
SimpleMatrix P_temp = MatrixUtil.multiply(I_KH, PcMat);
SimpleMatrix P_new = MatrixUtil.multiply(P_temp, I_KH_T);

SimpleMatrix KR = MatrixUtil.multiply(K, RMat);
SimpleMatrix KRKt = MatrixUtil.multiply(KR, MatrixUtil.transpose(K));
P_new = MatrixUtil.add(P_new, KRKt);
```

### 状态
✅ 已修复

---

## 待排查问题

### 🟡 P1：dE 系统偏差 ~0.8m

Java 版 RTK 定位在东向存在约 0.8m 的系统偏差，可能与卫星数差异（Java 比 C 少 1-2 颗）
或观测值权重模型有关。

### 🟡 P2：后段历元精度波动

epoch 210, 239 出现 5m 跳变，可能与卫星升降或周跳处理细节有关。

### 🟢 P3：基准站位置自动获取

RINEX 头 `APPROX POSITION XYZ` 已实现自动读取（`RinexParser`），MOVEB 模式已有 SPP 平均。
缺失：`POSOPT_SINGLE`（非MOVEB模式SPP取平均）和 `POSOPT_FILE`（位置文件读取）。

---

## 修复优先级汇总

| 优先级 | 问题 | 文件 | 状态 | 影响 |
|--------|------|------|------|------|
| 🔴→✅ | 周跳检测：缺少 ph/pt 保存 | RtkCore.rtkpos() | ✅ 已修复 | detslp 读取永远为 0 |
| 🔴→✅ | 周跳检测：4 个函数未调用 | RtkCore.udbias() | ✅ 已修复 | 恶性循环根源 |
| 🟡→✅ | 周跳检测：detslpDop Math.abs() | RtkCore.detslpDop() | ✅ 已修复 | 符号丢失 |
| 🟡→✅ | 周跳检测：detslpLl slip 覆盖 | RtkCore.detslpLl() | ✅ 已验证 | 逻辑一致 |
| 🟢→✅ | 周跳检测：nf 参数 | RtkCore.detslp_*() | ✅ 已对齐 | 功能等价 |
| ✅ | rtk.sol.time 未更新 | RtkCore.rtkpos() | ✅ 已修复 | Kalman 冻结 |
| ✅ | varerr() 不完整 | RtkCore.varerr() | ✅ 已修复 | 噪声模型 |
| ✅ | 对流层映射函数 | RtkCore.zdres() | ✅ 已修复 | 对流层延迟 |
| 🔴→✅ | LAMBDA输出索引错误 | RtkCore.relpos() | ✅ 已修复 | Fix解偏差24m |
| 🔴→✅ | Kalman 协方差更新 | KalmanFilter.java | ✅ 已修复(Joseph形式) | 数值稳定性 |
| 🟡 | dE 系统偏差 ~0.8m | 观测值权重/卫星数 | ⚠️ 待排查 | 精度 |
| 🟡 | 后段历元精度波动 | 卫星升降/周跳细节 | ⚠️ 待排查 | 稳定性 |
| 🟢 | 基准站位置自动获取 | 待实现 | ⚠️ 待实现 | 自动化 |

---

## 调试过程

### 阶段1：14m 偏差初步排查
初始 Java 版与 C 版 RTK 定位偏差达 14m，逐步排查：
1. 状态向量定义差异 → 验证等价（不是原因）
2. 基准站坐标差异（5m）→ 硬编码 C 版坐标
3. 对流层映射函数差异 → 修复但效果有限（0.01~0.06 差异）

### 阶段2：时间同步问题发现
发现 Java 输出文件中 240 行全是同一时间戳，而 C 版每历元时间不同。
定位到 `rtk.sol.time` 未更新 → `rtk.tt=0` → Kalman 滤波器冻结。

### 阶段3：观测噪声模型修复
对比 C 版 varerr() 发现：
- 缺少 SNR 调整项
- 星座因子硬编码且 IRNSS 错误
- 修复后观测噪声模型与 C 版一致

### 阶段4：异常定位行为分析
修复时间同步和噪声模型后，偏差仍较大。统计发现：
- Outlier 检测 479 次（C 版 224 次，2.1x）
- 模糊度重置 222 次（C 版 158 次，1.4x）
- C14/C27/C17 频繁被重置（33/31/28 次）

### 阶段5：周跳检测缺失定位
对比 C 版 udbias() 发现 4 个周跳检测函数全部缺失。
这是 Outlier 频发和模糊度反复重置的根本原因。
---

## 阶段6：RTK 核心管道重构与测试修复 (2026-07-16)

### 背景
之前的 RTK 管道仅实现了离散的调试修复，核心方法（elpos、udstate、zdres、ddres、esamb_LAMBDA、holdamb）缺失或不完整，导致 RTK 定位无法运行。

### 新增文件

#### KalmanFilter.java
- 路径：src/main/java/org/rtklib/java/kalman/KalmanFilter.java
- 实现 EKF 测量更新 (Joseph形式)：x = x + K*v, P = (I-KH)*P*(I-KH)^T + K*R*K^T
- 使用 EJML SimpleMatrix 进行矩阵运算，行优先存储

#### RtkCore.java 核心方法
| 方法 | 功能 | 对应 C 函数 |
|------|------|-----------|
| elpos() | RTK 核心管道入口 | elpos() |
| udstate() | 状态时间更新调度 | udstate() |
| udpos() | 位置/速度状态传播 + 自适应 Q | udpos() |
| udion() | 电离层状态传播 | udion() |
| udtrop() | 对流层状态传播 | udtrop() |
| udbias() | 模糊度状态传播 + 周跳检测 | udbias() |
| zdres() | 零差残差计算 | zdres() |
| ddres() | 双差残差 + H 矩阵构建 | ddres() |
| ilter() | Kalman 滤波封装 | ilter() |
| esamb_LAMBDA() | LAMBDA 模糊度固定 | esamb_LAMBDA() |
| holdamb() | Fix-and-Hold 约束 | holdamb() |
| 	estSys() | 卫星系统校验 | 	estSys() |

### 测试修复

#### 1. SPP 初始化改用 PntPos.pntpos
原代码调用 SppCore.estpos(obs, nu, null, ...) 传入 null 的 s 参数导致 NullPointerException。
修复：改用 PntPos.pntpos(obs, nu, nav, opt, rtk.sol, null, rtk.ssat) 进行 SPP 初始化，由 PntPos 内部计算卫星位置。

#### 2. ionocorr 数组大小修复
IonosphereModel.ionocorr() 需要 out 数组至少 2 个元素（out[0] 和 out[1]），原代码分配 
ew double[1] 导致 ArrayIndexOutOfBoundsException。
修复：改为 
ew double[2]。

#### 3. rtk.nx 初始化
tk.nx 默认值为 0，导致 H = new double[nx * nv] 分配长度为 0 的数组。
修复：在 elpos() 中根据 NP(rtk) + NI(rtk) + NT(rtk) + NA(rtk, ns) 动态设置 tk.nx。

#### 4. H 矩阵分配大小
ddres 内部使用 flg.length (= 
s * nf * 2) 作为迭代上限，但 H 矩阵按 zdres 返回值 
v 分配，可能小于实际需要。
修复：H//R 改为按 
s * nf * 2 分配。

### 测试结果

| 指标 | 结果 |
|------|------|
| 测试命令 | mvn test |
| 测试用例 | RtkRinexCompareTest.testRtkCompareWithRtklib |
| 对比历元数 | 240 |
| Q 匹配率 | 100% (240/240) |
| 解类型匹配率 | 100% |
| 测试状态 | ✅ BUILD SUCCESS |

### 已知问题

| 优先级 | 问题 | 说明 |
|--------|------|------|
| 🟡 | 基线向量为 0 | RTK 管道输出基线向量全为 0，需要进一步调试收敛性 |
| 🟡 | 位置偏差大 | 平均 3D 偏差约 6373999m，输出的是流动站绝对坐标而非基线向量 |
| 🟡 | dE 系统偏差 ~0.8m | 历史遗留，可能与卫星数/权重模型有关；ddres()残差剔除修复后待验证 |
| 🟡 | 后段历元精度波动 | 可能与卫星升降/周跳细节有关；udbias() rejc重置逻辑修复后待验证 |
## 阶段7：三项RTK优化实现 (2026-07-16)

### 背景
基于滑坡监测场景需求，实现了三项核心优化，所有优化通过`RtkConfig`独立开关控制，默认关闭，保持向后兼容。

### 7.1 滑动窗自适应Q矩阵（enableAdaptiveQ）

#### 设计目标
传统Q矩阵使用固定过程噪声，无法区分静态/蠕变/滑动状态。静态时Q应极小以压制观测噪声，动态时需增大Q以避免滞后。

#### 新增字段
| 文件 | 字段 | 类型 | 说明 |
|------|------|------|------|
| Rtk.java | `xOld[3]` | double[] | 上一历元绝对ECEF位置 |
| Rtk.java | `posWin[100]` | double[] | 位置增量环形滑动窗缓冲区 |
| Rtk.java | `winIdx` | int | 滑动窗当前写入位置 |
| Rtk.java | `winCnt` | int | 滑动窗有效元素计数 |

#### 新增配置
| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `adaptiveQWinSize` | 50 | 滑动窗大小（历元） |
| `adaptiveQStaticThresh` | 0.001 m | 静态阈值 |
| `adaptiveQDynamicThresh` | 0.05 m | 动态阈值 |
| `adaptiveQScaleMinStatic` | 0.01 | 静态最小缩放因子 |
| `adaptiveQScaleMaxDynamic` | 5.0 | 动态最大缩放因子 |

#### 核心算法
```
1. 计算当前历元绝对位置 curPos = x[0:2] + rb[0:2]
2. 若 xOld 非零，计算位置增量 posInc = ||curPos - xOld||
3. 存入环形滑动窗 posWin[winIdx]，winIdx = (winIdx+1) % winSize
4. 更新 xOld = curPos
5. 计算滑动窗内位置增量的 RMS：σ_pos = sqrt(∑(x-μ)²/validCount)
6. Sigmoid 映射：
   - σ_pos ≤ 0.001m → α = 0.01（静态，极度信任模型）
   - σ_pos ≥ 0.05m  → α = 5.0（动态，快速响应）
   - 中间值 → S型过渡：α = 0.01 + sigmoid(t) * 4.99
   - sigmoid(t) = 1/(1 + exp(-10*(t-0.5)))
7. 最终缩放 = α × nsFactor × pdopFactor × clamp(min, max)
8. udpos() 中 qh/qv *= qScale²
```

#### 实现位置
- `RtkOptimizations.computeQScale()`：完整重写，原简化版（nsFactor×pdopFactor）替换为滑动窗方案
- `RtkCore.udpos()`：已有 `qScale` 乘法逻辑，无需修改

### 7.2 模糊度子集锚固（enableAmbAnchor）

#### 设计目标
标准Fix-and-Hold在LAMBDA失败时重置所有模糊度。对于滑坡监测，老卫星几何关系稳定，模糊度已收敛，不应轻易丢弃。锚固机制将长期固定（≥100历元）的模糊度协方差压制到1e-9，数学上等价于"已知常数"。

#### 新增字段
| 文件 | 字段 | 类型 | 说明 |
|------|------|------|------|
| Rtk.java | `ambAnchored[MAXSAT*NF]` | boolean[] | 模糊度锚固标记 |
| Rtk.java | `ambAnchorCount[MAXSAT*NF]` | int[] | 连续固定历元计数 |

#### 新增配置
| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `enableAmbAnchor` | false | 锚固开关 |
| `ambAnchorMinFixCount` | 100 | 锚固所需连续固定历元数 |
| `ambAnchorVar` | 1e-9 | 锚固后的协方差值 |

#### 核心算法

**holdamb() 修改**：
```
1. SOLQ_FIX 时，对所有 fix[f]>0 的模糊度 ambAnchorCount[globalIdx]++
2. 连续固定 ≥100 历元 → ambAnchored[globalIdx] = true
3. 已锚固的模糊度，holdamb中 Rh 使用 ambAnchorVar(1e-9) 而非 varholdamb
4. 非 SOLQ_FIX 时，仅重置未固定卫星的 ambAnchorCount，不清空锚固标记
```

**resamb_LAMBDA() 修改**：
```
1. 分离已锚固和未锚固的模糊度（freeMap[] / anchoredMap[]）
2. 若全部锚固（freeCount==0）→ 直接返回 SOLQ_FIX（ratio=999.9）
3. 仅对未锚固子集提取 a/Qa 子矩阵
4. 对子集执行 LAMBDA 搜索
5. 固定成功后，已锚固值保持原值，未锚固值用 LAMBDA 结果
```

#### 实现位置
- `RtkCore.holdamb()`：锚固计数、协方差压制、失败不清空
- `RtkCore.resamb_LAMBDA()`：子集分离、子矩阵提取、子集LAMBDA

### 7.3 大气参数自适应冻结（atmFrozenNsThresh）

#### 设计目标
卫星数少（<7颗）时，强行估计大气参数会导致法方程病态，滤波器将大气误差"吸收"进坐标分量，造成虚假位移。冻结机制在少星时跳过电离层/对流层状态更新。

#### 新增配置
| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `atmFrozenNsThresh` | 7 | 卫星数阈值，0=禁用 |

#### 核心算法
```
udion(): if (ns < atmFrozenNsThresh) return;  // 冻结电离层过程噪声更新
udtrop(): if (ns < atmFrozenNsThresh) return;  // 冻结对流层过程噪声更新
```

#### 实现位置
- `RtkCore.udion()`：第277行，return前检查
- `RtkCore.udtrop()`：第297行，return前检查

### 7.4 PAR重选、TROPOPT_ESTG、IONOOPT_EST、电离层梯度 (2026-07-17)

#### 设计目标
四项优化通过配置控制，默认关闭，不影响已调试功能。扩展RTK观测模型，支持电离层/对流层参数估计及梯度项，提升复杂环境下的定位精度。

#### 新增配置
| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `enableParRefReselect` | false | PAR参考星动态重选 |
| `ionoGradient` | false | 电离层梯度项启用 |
| `ionoopt` | IONOOPT_OFF | 电离层估计模式（EST=估计） |
| `tropopt` | TROPOPT_SAAS | 对流层估计模式（ESTG=含梯度） |

#### 核心实现

**4.1 PAR重选（ddidx）**：

```
RtkCore.ddidx():
  if (enableParRefReselect) → buildParIndex()  // 动态选择参考星
  else → ddidxFallback()  // 默认逻辑，按高度角选最高星
```
- 动态参考星选择：基于高度角、信号质量、锁相状态
- 回退保护：`parMaxConsecutiveReselect` 超过限制时清空排除列表

**4.2 电离层状态索引（II）**：

```
RtkCore.II(sat, opt):
  if (ionoGradient) → np + (sat-1)*3   // 3参数/卫星：iono + 梯度x + 梯度y
  else → np + (sat-1)                   // 1参数/卫星：仅iono
```

**4.3 双差残差中的电离层项（ddres）**：

```
if (ionoopt == IONOOPT_EST):
  imI = ionmapf(posI, azelI)  // 电离层映射函数（站I）
  imJ = ionmapf(posJ, azelJ)  // 电离层映射函数（站J）
  H[ionoI] += imI; H[ionoJ] -= imJ  // 设计矩阵
  v -= imI*xi + imJ*xj  // 残差修正

  if (ionoGradient):
    // 梯度项：cot(El) * cos(Az) / sin(Az)
    H[ionoI+1] += cotElI * cosAzI; H[ionoJ+1] -= cotElJ * cosAzJ
    H[ionoI+2] += cotElI * sinAzI; H[ionoJ+2] -= cotElJ * sinAzJ
```

**4.4 双差残差中的对流层项（ddres + prectrop）**：

```
if (tropopt == TROPOPT_EST || tropopt == TROPOPT_ESTG):
  prectrop(rtk, rr, azel, i, dtdx, nx)  // 计算对流层延迟对状态向量的偏导
  H[k] += dtdxI[k] - dtdxJ[k]  // 设计矩阵累加

prectrop() 内部：
  TROPOPT_EST:  dtdx[IT] = mw  // 湿延迟映射函数
  TROPOPT_ESTG: dtdx[IT] = mw; dtdx[IT+1] = mw*cotEl*cosAz; dtdx[IT+2] = mw*cotEl*sinAz
```

**4.5 测量残差计算**：

```
zdres(): 签名修改为 zdres(..., double[] y)
  // 无几何距离组合残差存储在 y 中

ddres():
  v[nv] = y[i] - y[refI]  // 双差残差 = 非差残差差分
  H[nv*nx + k] = -e[k]  // 修复：(e[k] - e[k]) → e[k]
```

#### 实现位置
- `RtkCore.ddidx()`：PAR重选入口，配置开关
- `RtkCore.II()`：电离层状态索引计算
- `RtkCore.prectrop()`：对流层梯度偏导计算
- `RtkCore.zdres()`：签名修改，传递y数组
- `RtkCore.ddres()`：电离层/对流层梯度项、残差计算、H矩阵修复
- `RtkOptimizations.buildParIndex()`：动态参考星选择
- `RtkOptimizations.ddidxFallback()`：默认回退逻辑

### 7.5 SingularMatrixException 安全修复 (2026-07-17)

#### 问题分析

**错误链路**：
```
RtkCore.relpos() → RtkOptimizations.computeQScale() → dops() → invert() → SingularMatrixException
```

**根因**：`dops()` 中构建的 4×4 Q 矩阵可能奇异（卫星扎堆、共面、几何退化），即使 n≥4。C版 `matinv()` 调用 LAPACK `dgetrf_`，奇异时返回 0，流程继续。Java版 EJML `invert()` 直接抛异常。

**额外问题**：即使修复 dops() 不抛异常，`computeQScale()` 中 `pdop=dop[1]=0` 时，`pdopFactor = ref/1.0 = 2.0`，几何退化反而放大Q缩放，逻辑反转。

#### 修复方案：三层防护

| 层 | 文件 | 修改 | 作用 |
|----|------|------|------|
| 底层工具 | `MatrixUtil.java` | 新增 `invertSafe()` | 通用安全求逆，返回 `Optional<SimpleMatrix>` |
| 源头修复 | `RtklibCommon.dops()` | `invert()` → `invertSafe()` | 矩阵奇异时静默返回，DOP=0，对齐C版 |
| 调用方兜底 | `RtkOptimizations.computeQScale()` | `dop[1]==0` 检查 | 几何退化时 `pdopFactor=1.0`，保守策略 |

**invertSafe() 实现**：
```java
public static Optional<SimpleMatrix> invertSafe(SimpleMatrix A) {
    try {
        return Optional.of(A.invert());
    } catch (SingularMatrixException e) {
        return Optional.empty();
    }
}
```

**dops() 修改**：
```java
// 修复前
SimpleMatrix QInv = MatrixUtil.invert(QMat);  // 可能抛异常

// 修复后
Optional<SimpleMatrix> qInvOpt = MatrixUtil.invertSafe(QMat);
if (!qInvOpt.isPresent()) return;  // DOP全为0，与C版一致
```

**computeQScale() 修改**：
```java
// 修复前
double pdop = dop[1];
double pdopFactor = Math.min(ref / Math.max(pdop, 1.0), 2.0);
// → pdop=0 时 pdopFactor = ref/1.0 = 2.0（逻辑反转！）

// 修复后
double pdopFactor = 1.0;
if (dop[1] > 0) {
    pdopFactor = Math.min(ref / dop[1], 2.0);
}
// → pdop=0 时 pdopFactor=1.0（不做缩放，保守策略）
```

#### 实现位置
- `MatrixUtil.invertSafe()`：通用工具方法
- `RtklibCommon.dops()`：求逆失败静默返回
- `RtkOptimizations.computeQScale()`：DOP=0 保守处理

### 7.6 修改文件清单

| 文件 | 修改类型 | 说明 |
|------|---------|------|
| `config/RtkConfig.java` | 新增7个字段 | 3项优化的全部配置 |
| `data/Rtk.java` | 新增6个字段 | xOld、posWin、winIdx、winCnt、ambAnchored、ambAnchorCount |
| `rtkpos/RtkOptimizations.java` | 重写computeQScale | 滑动窗+RMS+sigmoid+pdopFactor安全处理 |
| `rtkpos/RtkCore.java` | 修改4个方法 | udion/udtrop/resamb_LAMBDA/holdamb |
| `rtkpos/RtkCore.java` | 新增4个方法 | ddidx/II/prectrop/zdres签名修改 |
| `common/MatrixUtil.java` | 新增1个方法 | invertSafe |
| `common/RtklibCommon.java` | 修改dops | invert→invertSafe |

### 7.7 测试结果

| 指标 | 结果 |
|------|------|
| 测试命令 | `mvn test` |
| 编译 | ✅ BUILD SUCCESS |
| 测试用例 | RtkRinexCompareTest |
| Tests run | 1, Failures: 0, Errors: 0 |
| Q 匹配率 | 100% (240/240) |
| 解类型匹配率 | 100% |
| 优化状态 | 默认关闭，向后兼容，按需开启 |

---

## 阶段7：索引体系Bug修复 (2026-07-18/19)

共发现10个Bug，其中6个是优化过程中引入的，4个是原始移植遗漏。
优化引入的Bug根因：阶段6（07-16）RTK核心管道重构时使用了 `NA(rtk, ns)` 动态索引 +
`naOff + i*nf + f` 相对索引方案，该方案内部自洽，阶段6测试通过（Q匹配率100%）。
阶段7（07-16/17）添加优化时引入了C版风格的 `buildParIndex()`/`ddidxFallback()`
（需要 `rtk.na` + MAXSAT遍历），与阶段6的动态索引方案不兼容，导致Bug。
修复方案是统一回C版的固定索引方案。

原始移植遗漏的Bug（9.3/9.8/9.9/9.10）在当前默认配置下不影响结果，
但在启用对应功能（dynamics/TROPOPT_EST/IONOOPT_EST）时会暴露。

| Bug# | 问题 | 来源 | 引入阶段 |
|------|------|------|----------|
| 9.1 | NI() 返回动态值 | 优化引入：添加 ionoGradient 时修改 | 阶段7.4 |
| 9.2 | NT() 返回 1/3 | 优化引入：添加 TROPOPT_ESTG 时错误理解 | 阶段7.4 |
| 9.3 | 缺少状态向量初始化 | 原始移植遗漏 | 阶段6 |
| 9.4 | udstate 在 xp/Pp 复制之后执行 | 优化引入：重构时执行顺序错误 | 阶段6 |
| 9.5 | 状态向量大小随 ns 动态变化 | 优化引入：NA(rtk,ns) 替代 NB(opt) | 阶段6 |
| 9.6 | 模糊度索引用相对位置 | 优化引入：naOff+i*nf+f 替代 IB() | 阶段6 |
| 9.6a | rtk.na 未设置 | 优化引入：buildParIndex需要但未设置 | 阶段7 |
| 9.7 | NP() dynamics时返回6而非9 | 优化引入：遗漏加速度3个状态 | 阶段6 |
| 9.8 | ssat.lock 未更新 | 原始移植遗漏：lock始终为0，缺少延迟机制 | - |
| 9.9 | udtrop 缺少基准站参数更新 | 原始移植遗漏：只更新流动站 | - |
| 9.10 | udion 缺少初始化和重置逻辑 | 原始移植遗漏：缺少initx和GAP_RESION重置 | - |

### 9.1 NI() 返回动态值而非 MAXSAT（优化引入）

| 项目 | 说明 |
|------|------|
| C版 | `#define NI(opt) ((opt)->ionoopt!=IONOOPT_EST?0:MAXSAT)` — 固定返回 MAXSAT(228) |
| Java版(修复前) | 遍历 `rtk.x[]` 数非零元素，返回动态值 |
| 引入原因 | 阶段7.4添加 `ionoGradient` 支持时，将NI()改为动态计数以适配每星3参数模式，但破坏了基本模式的固定大小语义 |
| 影响 | NI 决定状态向量大小，动态值导致 nx 随历元变化，后续所有索引（NT、NA、IB等）全部错乱 |
| 修复 | 改为 `ionoopt==IONOOPT_EST ? (ionoGradient ? MAXSAT*3 : MAXSAT) : 0` |

### 9.2 NT() 返回 1/3 而非 2/6（优化引入）

| 项目 | 说明 |
|------|------|
| C版 | `#define NT(opt) ((opt)->tropopt<TROPOPT_EST?0:((opt)->tropopt<TROPOPT_ESTG?2:6))` |
| Java版(修复前) | `tropopt==TROPOPT_EST → 1; tropopt==TROPOPT_ESTG → 3` |
| 引入原因 | 阶段7.4添加 `TROPOPT_ESTG` 支持时，错误理解为每站1/3个参数，实际C版是每站1/3个参数×2站=2/6 |
| 影响 | 对流层参数数错误：C版2/6对应流动站+基准站各1/3个参数，Java版只算了流动站 |
| 修复 | 改为 `tropopt<EST → 0; tropopt<ESTG → 2; else → 6` |

### 9.3 缺少状态向量初始化（原始移植遗漏）

| 项目 | 说明 |
|------|------|
| C版 | `udpos()` 中 `norm(rtk->x,3) <= RE_WGS84/2` 时用 SPP 结果初始化 `x[0..2]` |
| Java版(修复前) | `udpos()` 完全缺失此初始化逻辑，x[0..2] 始终为 0 |
| 影响 | 首历元位置状态未初始化，Kalman滤波从零开始收敛，导致初始历元定位偏差巨大 |
| 修复 | 在 `udpos()` 开头添加完整初始化逻辑：PMODE_FIXED、norm检查、STATIC/KINEMATIC模式、方差过大重置 |

### 9.4 udstate 在 xp/Pp 复制之后执行（优化引入）

| 项目 | 说明 |
|------|------|
| C版 | 先 `udstate()`（时间更新），再 `matcpy(xp, rtk->x)` / `matcpy(Pp, rtk->P)` |
| Java版(修复前) | 先 `System.arraycopy(rtk.x→xp)`，再 `udstate()` |
| 影响 | 过程噪声写入 rtk.x/P，但 xp/Pp 是旧副本，Kalman滤波测量更新使用的是不含过程噪声的状态 |
| 修复 | 将 `udstate()` 调用移到 `arraycopy` 之前 |

### 9.5 状态向量大小随 ns 动态变化（优化引入）

| 项目 | 说明 |
|------|------|
| C版 | `nx = NR(opt) + NB(opt)`，其中 `NB(opt) = MAXSAT * NF(opt)` — 固定大小 |
| Java版(修复前) | `rtk.nx = NP + NI + NT + NA(rtk, ns)`，其中 `NA = ns * nf` — 随 ns 变化 |
| 引入原因 | 阶段6重构时用 `NA(rtk, ns)` 替代C版 `NB(opt) = MAXSAT*NF`，意图节省内存。阶段6内部自洽但与阶段7的 `buildParIndex()`（需要MAXSAT遍历）不兼容 |
| 影响 | 不同历元卫星数不同时 nx 变化，导致状态向量大小不一致，模糊度状态在历元间漂移 |
| 修复 | 新增 `NB(rtk) = MAXSAT * nf`，`rtk.nx = NR(rtk) + NB(rtk)` |

### 9.6 模糊度索引用相对位置而非卫星号（优化引入）

| 项目 | 说明 |
|------|------|
| C版 | `IB(sat, f, opt) = NR(opt) + MAXSAT*f + (sat-1)` — 按卫星号索引，固定位置 |
| Java版(修复前) | `naOff + i * nf + f` — 按相对位置 i 索引，随 sat[] 排列变化 |
| 引入原因 | 阶段6重构时用 `naOff + i*nf + f` 替代C版 `IB()`，与 `NA(rtk, ns)` 动态方案配套。阶段7的 `buildParIndex()`/`ddidxFallback()` 使用C版风格的 `rtk.na + MAXSAT*f + (sat-1)` 遍历，两套索引体系冲突 |
| 影响 | 同一卫星在不同历元的模糊度索引不同，导致状态无法正确继承；ddres 与 udbias 索引不一致 |
| 修复 | 新增 `IB(sat, f, opt)` 函数，所有模糊度索引统一使用卫星号计算 |

### 9.6a rtk.na 未设置（优化引入）

| 项目 | 说明 |
|------|------|
| C版 | `rtk->na = NR(opt)` 在 `rtkinit()` 中设置，`ddidx()` 中 `na = rtk->na` 作为模糊度区域起始索引 |
| Java版(修复前) | `rtk.na` 在 `RtkProcessor` 初始化时设为 0，`relpos()` 中未更新 |
| 引入原因 | 阶段7的 `buildParIndex()`/`ddidxFallback()` 从C版移植，使用 `rtk.na` + MAXSAT遍历模糊度。但阶段6的动态方案不需要 `rtk.na`，从未设置 |
| 影响 | `buildParIndex()` 中 `k = na = 0`，模糊度区域起始索引错误，参考星选择和双差索引完全错乱 |
| 修复 | 在 `relpos()` 中添加 `rtk.na = NR(rtk)` |

### 9.7 NP() dynamics 时返回 6 而非 9（优化引入）

| 项目 | 说明 |
|------|------|
| C版 | `#define NP(opt) ((opt)->dynamics==0?3:9)` — dynamics时9（3位置+3速度+3加速度） |
| Java版(修复前) | `NP(rtk) = (rtk.opt.dynamics!=0)?6:3` — dynamics时6，缺少3个加速度状态 |
| 引入原因 | 阶段6重构时遗漏加速度3个状态，导致所有索引函数（II/IT/IL/IB）在dynamics模式下偏移3 |
| 影响 | 当前默认配置 dynamics=0（Static模式），NP=3，不受影响。启用dynamics模式后所有索引错位 |
| 修复 | 改为 `(rtk.opt.dynamics==0)?3:9`，同步修复 II/IT/IL/IB 中的 np 计算 |

### 9.8 ssat.lock 未更新（原始移植遗漏）

| 项目 | 说明 |
|------|------|
| C版 | `lock[f]` 初始为0，首次使用设为 `-minlock`，每历元 `lock[f]++`，`lock>=0` 时才允许模糊度固定 |
| Java版 | `lock[f]` 始终为0（从未更新），`lock>=0` 条件始终满足 |
| 影响 | 缺少新卫星模糊度固定的延迟保护机制，可能导致锁定不够稳定时就尝试固定 |
| 修复 | 待补充：在 `relpos()` 中添加 `lock[f]++`，在 `udbias()` 中添加首次使用时设为 `-minlock` |

### 9.9 udtrop 缺少基准站参数更新（原始移植遗漏 → ✅ 已修复）

| 项目 | 说明 |
|------|------|
| C版 | `udtrop()` 循环 `i=0..1`（流动站+基准站），每站初始化和过程噪声 |
| Java版(修复前) | `udtrop()` 只更新流动站（idx = NP+NI），缺少基准站（idx = NP+NI+NT/2） |
| 影响 | 当前默认配置 `tropopt=SAAS`（不估计对流层），NT=0，不影响。启用 TROPOPT_EST/ESTG 时基准站对流层参数不会更新 |
| 修复 | ✅ 已修复：`udtrop()` 已包含 `for (int i = 0; i < 2; i++)` 循环，i=0流动站、i=1基准站，使用 `IT(i, opt)` 计算索引 |

### 9.10 udion 缺少初始化和重置逻辑（原始移植遗漏 → ✅ 已修复）

| 项目 | 说明 |
|------|------|
| C版 | `udion()` 包含：1) GAP_RESION重置长时间中断卫星；2) initx初始化新卫星；3) 过程噪声 |
| Java版(修复前) | `udion()` 只有过程噪声更新，缺少初始化和重置 |
| 影响 | 当前默认配置 `ionoopt!=IONOOPT_EST`，NI=0，不影响。启用电离层估计时新卫星不会被初始化 |
| 修复 | ✅ 已修复：1) GAP_RESION重置已实现；2) VTEC初始化已实现；3) 梯度参数Gn/Ge初始化和过程噪声已实现，使用 `gradientIonoInitVar`(1e-4) 和 `gradientIonoPrn`(1e-3) 配置参数 |

### 9.11 新增辅助函数

为配合上述修复，新增以下与C版对应的辅助函数：

| 函数 | 定义 | 说明 |
|------|------|------|
| `NB(rtk)` | `MAXSAT * nf` (DGPS模式=0) | 模糊度状态数（固定大小） |
| `NL(rtk)` | `glomodear==AUTOCAL ? NFREQGLO : 0` | GLONASS IC bias数 |
| `NR(rtk)` | `NP + NI + NT + NL` | 非模糊度状态总数 |
| `IT(r, opt)` | `NP + NI + (nt/2)*r` | 对流层参数索引（r=0流动站, r=1基准站） |
| `IL(f, opt)` | `NP + NI + NT + f` | GLONASS IC bias索引 |
| `IB(sat, f, opt)` | `NR + MAXSAT*f + (sat-1)` | 模糊度状态索引（按卫星号） |

### 9.12 新增常量

| 常量 | 值 | 说明 |
|------|-----|------|
| `VAR_POS` | 900.0 (30²) | 初始位置方差 (m²) |
| `VAR_POS_FIX` | 1E-8 | 固定解位置方差 (m²) |
| `VAR_VEL` | 100.0 (10²) | 初始速度方差 ((m/s)²) |
| `VAR_ACC` | 100.0 (10²) | 初始加速度方差 ((m/s²)²) |

### 9.13 修改文件清单

| 文件 | 修改类型 | 说明 |
|------|---------|------|
| `RtkCore.java` | 修复 NI() | 返回 MAXSAT 而非动态计数 |
| `RtkCore.java` | 修复 NT() | 返回 2/6 而非 1/3 |
| `RtkCore.java` | 修复 udpos() | 添加完整初始化逻辑（C版对齐） |
| `RtkCore.java` | 修复 relpos() | udstate 移到 arraycopy 之前 |
| `RtkCore.java` | 修复 rtk.nx | 使用 NR+NB 固定大小 |
| `RtkCore.java` | 修复 udbias() | 模糊度索引改用 IB() |
| `RtkCore.java` | 修复 ddres() | 模糊度索引改用 IB() |
| `RtkCore.java` | 修复 resamb_LAMBDA() | 模糊度索引改用 IB() |
| `RtkCore.java` | 修复 holdamb() | 模糊度索引改用 IB() |
| `RtkCore.java` | 新增 7 个函数 | NB, NL, NR, IT, IL, IB, initx |
| `RtkCore.java` | 新增 rtk.na 设置 | relpos() 中设置 rtk.na = NR(rtk) |
| `Constants.java` | 新增 4 个常量 | VAR_POS, VAR_POS_FIX, VAR_VEL, VAR_ACC |
| `Constants.java` | 更新 NX_RTK | 移至 MAXSAT 定义之后，值 = 9 + MAXSAT*3 + 6 + NFREQGLO + MAXSAT*3 = 1385 |

---

## 阶段8：固定解验证管道修复 (2026-07-19)

### 问题

zdres() 和 ddres() 在固定解验证时始终使用 rtk.x（浮点解状态向量），
而非 xa（固定解状态向量），导致固定解残差计算错误。

### 修复

1. zdres() 和 ddres() 添加 xState 参数，替代内部对 rtk.x 的引用
2. relpos() 中 Kalman 迭代时传入 xp（浮点解），固定解验证时传入 xa（固定解）
3. ddres() 中电离层梯度计算也使用 xState 替代 rtk.x

### ssat.vsat 类型修复

`ssat.vsat[f]` 是 int 类型，在条件判断中直接使用 `&&` 导致编译错误。
修复：`rtk.ssat[sat[i] - 1].vsat[f] != 0 && ...`

---

## 阶段9：锚固优化修复与额外优化文档化 (2026-07-19)

### 锚固优化修复

resamb_LAMBDA() 和 holdamb() 在C风格重构后锚固逻辑完全丢失。
修复：在 resamb_LAMBDA() 中添加锚固/自由组分离，在 holdamb() 中添加锚固计数管理。

### 电离层梯度开关同步

RtkConfig.enableIonoTropGradient 是死开关，从未同步到 PrcOpt.ionoGradient。
修复：在 relpos() 入口处同步。

### 文档整理

- 创建 [RTK_Extra_Optimizations.md](RTK_Extra_Optimizations.md)：记录7项Java版独有优化的完整算法、配置参数和实现位置
- RTKLIB_Differences.md §9/§10 移至本调试记录和 RTK_Extra_Optimizations.md

---

## 阶段10：观测值质量控制修复 (2026-07-19)

### 10.1 valpos() 空循环（🟡 诊断工具，非质量控制）

| 项目 | 说明 |
|------|------|
| C版 | `valpos()` 遍历后验残差，超过阈值的输出 `errmsg` 日志，**始终返回1** |
| Java版(修复前) | `valpos()` 遍历后验残差，超过阈值的只 `continue`，**无日志，始终返回true** |
| 结论 | `valpos()` 是**后端诊断工具**，不是质量控制。C版也始终返回1，不会因后验残差丢弃历元 |

**C版的设计意图**：`valpos()` 是事后检查——Kalman滤波已经完成，观测已被吸收。
此时再剔除观测没有意义。真正的质量控制在前端 `ddres()` 的 `maxinno` 检查中。

### 10.2 ddres() 缺少残差剔除逻辑（🔴 高优先级，C版原始移植遗漏）

| 项目 | 说明 |
|------|------|
| C版 | `ddres()` 中检查 `fabs(v[nv]) > opt->maxinno[code] * threshadj`，超过则 `vsat=0, rejc++, continue` |
| Java版(修复前) | `ddres()` 中无任何残差剔除，所有观测无条件进入Kalman滤波 |
| 影响 | **这是真正的质量控制缺失**。粗差观测直接进入滤波，可能导致模糊度重置和坐标跳变 |

C版 `ddres()` 中的关键逻辑（前端剔除）：
```c
/* if residual too large, flag as outlier */
if (fabs(v[nv]) > opt->maxinno[code] * threshadj) {
    rtk->ssat[sat[j]-1].vsat[frq] = 0;
    rtk->ssat[sat[j]-1].rejc[frq]++;
    errmsg(rtk, "outlier rejected (sat=%3d-%3d %s%d v=%.3f)\n",
            sat[i], sat[j], code?"P":"L", frq+1, v[nv]);
    continue;   // ← 不进入Kalman滤波
}
```

其中 `threshadj` 在模糊度刚初始化时放大10倍，避免误剔除。

### 10.3 udbias() 缺少 rejc 周跳/粗差重置逻辑（🔴 C版原始移植遗漏）

| 项目 | 说明 |
|------|------|
| C版 | `udbias()` 中检查 `rejc >= 2` 或有周跳时重置模糊度：`x[j]=0, rejc=0, lock=-minlock` |
| Java版(修复前) | `udbias()` 中无 rejc 检查，粗差卫星的模糊度不会被重置 |
| 影响 | 持续粗差卫星的模糊度无法被自动重置，影响后续历元 |

### 10.4 C版质量控制链完整梳理

```
前端剔除（ddres）:  观测 → maxinno检查 → 通过/剔除（vsat=0, rejc++）
                                                    ↓ 通过
                                              Kalman filter
                                                    ↓
后端诊断（valpos）: 后验残差 → 4σ检查 → 输出日志（始终通过）
                                                    ↓
模糊度管理（udbias）: rejc≥2 或周跳 → 重置模糊度（x=0, rejc=0, lock=-minlock）
```

`valpos()` 不参与质量控制链，它只是事后记录。

### 10.5 修复内容

1. **ddres()**: 添加 `maxinno` 残差检查，超过阈值则 `vsat=0, rejc++, continue`（前端剔除）
2. **udbias()**: 添加 `rejc >= 2` 或周跳时的模糊度重置逻辑
3. **valpos()**: 保持与C版一致——始终返回true（后端诊断，不做剔除）

### 10.6 与问题2/3的关联

| 问题 | 可能原因 | 本次修复的影响 |
|------|---------|---------------|
| dE 系统偏差 ~0.8m | 粗差观测未被前端剔除，持续污染滤波 | ddres() maxinno 剔除后可能改善 |
| 后段历元精度波动 | 粗差/周跳卫星模糊度未被重置 | udbias() rejc 重置逻辑可自动恢复 |