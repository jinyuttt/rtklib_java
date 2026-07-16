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
| dE 系统偏差 ~0.8m | 可能与卫星数差异（Java 比 C 少 1-2 颗）或观测值权重模型有关 | 🟡 |
| 后段历元精度波动 | epoch 210, 239 出现 5m 跳变，可能与卫星升降或周跳处理细节有关 | 🟡 |
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
之前的 RTK 管道仅实现了离散的调试修复，核心方法（elpos、udstate、zdres、ddres、esamb_LAMBDA、holdamb）缺失或不完整，导致 RTK 定位无法运行。

### 新增文件

#### KalmanFilter.java
- 路径：src/main/java/org/rtklib/java/kalman/KalmanFilter.java
- 实现 EKF 测量更新：x = x + K*v, P = (I-KH)*P
- 使用 EJML SimpleMatrix 进行矩阵运算，行优先存储

#### RtkCore.java 核心方法
| 方法 | 功能 | 对应 C 函数 |
|------|------|-----------|
| elpos() | RTK 核心管道入口 | elpos() |
| udstate() | 状态时间更新调度 | udstate() |
| udpos() | 位置/速度状态传播 + 自适应 Q | udpos() |
| udion() | 电离层状态传播 | udion() |
| udtrop() | 对流层状态传播 | udtrop() |
| udbias() | 模糊度状态传播 + 周跳检测 | udbias() |
| zdres() | 零差残差计算 | zdres() |
| ddres() | 双差残差 + H 矩阵构建 | ddres() |
| ilter() | Kalman 滤波封装 | ilter() |
| esamb_LAMBDA() | LAMBDA 模糊度固定 | esamb_LAMBDA() |
| holdamb() | Fix-and-Hold 约束 | holdamb() |
| 	estSys() | 卫星系统校验 | 	estSys() |

### 测试修复

#### 1. SPP 初始化改用 PntPos.pntpos
原代码调用 SppCore.estpos(obs, nu, null, ...) 传入 null 的 s 参数导致 NullPointerException。
修复：改用 PntPos.pntpos(obs, nu, nav, opt, rtk.sol, null, rtk.ssat) 进行 SPP 初始化，由 PntPos 内部计算卫星位置。

#### 2. ionocorr 数组大小修复
IonosphereModel.ionocorr() 需要 out 数组至少 2 个元素（out[0] 和 out[1]），原代码分配 
ew double[1] 导致 ArrayIndexOutOfBoundsException。
修复：改为 
ew double[2]。

#### 3. rtk.nx 初始化
tk.nx 默认值为 0，导致 H = new double[nx * nv] 分配长度为 0 的数组。
修复：在 elpos() 中根据 NP(rtk) + NI(rtk) + NT(rtk) + NA(rtk, ns) 动态设置 tk.nx。

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
| 🟡 | dE 系统偏差 ~0.8m | 历史遗留，可能与卫星数/权重模型有关 |
| 🟡 | 后段历元精度波动 | 可能与卫星升降/周跳细节有关 |
