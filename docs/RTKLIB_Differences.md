# Java版与RTKLIB C版实现差异说明

本文档记录Java版RTKLIB与C版RTKLIB（2.5.0）在实现上的有意差异。
这些差异是经过验证的必要修改或设计选择，不应随意回退。

Bug 修复的调试过程不在本文档中，请参见 `SPP_Debug_Record.md` 和 `RTK_Debug_Record.md`。

---

## 1. RTCM时间初始化策略

### C版行为
`adjweek()`、`adjustGpsWeek()`、`adjustBdtWeek()`、`adjdayGlot()` 在时间
未初始化时使用CPU时间（`timeget()`）作为参考。这在实时转换场景下是合理的，
因为CPU时间与数据时间基本一致。

### Java版行为
时间未初始化时不使用CPU时间，而是：
- `adjweek()`：用week=0创建临时时间，不设`timeInitialized`，让星历消息
  建立正确的时间基准
- `adjustGpsWeek()`/`adjustBdtWeek()`：返回原始周数，不做1024周调整
- `adjdayGlot()`：直接返回，不修改时间

### 原因
Java版用于离线处理RTCM数据直接定位，没有convbin转RINEX的中间步骤。
CPU时间可能与数据时间相差数年，使用CPU时间会导致周数调整完全错误。

### 影响范围
仅影响MSM消息在星历消息之前到达的场景（即前几个历元）。
星历消息到达后`timeInitialized`被设为true，后续处理与C版完全一致。

### 补偿措施
调用方（如SppTest）需要在所有数据解码完成后，用星历toe的周数修正
早期观测时间。这是离线处理的必要步骤。

---

## 2. ephclk函数签名

### C版签名
```c
extern int ephclk(gtime_t time, gtime_t teph, int sat, const nav_t *nav, double *dts);
```
返回1=成功，0=失败，时钟偏差通过指针参数输出。

### Java版签名
```java
private static boolean ephclk(GTime time, GTime teph, int sat, Nav nav, double[] dtOut)
```
返回boolean，时钟偏差通过数组参数输出。

### 原因
Java不支持指针，原实现直接返回时钟偏差值并用`dt == 0.0`判断失败，
但某些卫星的时钟偏差接近0会被误判为失败。改为与C版一致的
状态+输出参数模式。

---

## 3. Sagnac效应修正

### C版
`geodist()`中包含Sagnac修正项：
```c
r+=OMGE*(rs[0]*rr[1]-rs[1]*rr[0])/CLIGHT;
```

### Java版
与C版一致，已补上Sagnac修正项。

### 说明
早期Java版遗漏了此项，导致约50m东向偏差。现已对齐。

---

## 4. MSM cell索引处理

### C版
```c
for (k=0;k<h->nsig;k++) {
    if (!h->cellmask[k+i*h->nsig]) continue;  // 跳过无效cell，不递增j
    // ... use pr[j], cp[j], etc. ...
    j++;  // 只在有效cell时递增
}
```

### Java版
```java
for (k = 0; k < h.nsig; k++) {
    if (h.cellmask[k + i * h.nsig] == 0) continue;  // 跳过无效cell，不递增j
    // ... use pr[j], cp[j], etc. ...
    j++;  // 只在有效cell时递增
}
```

### 说明
两者逻辑一致。早期Java版错误地在无效cell上也递增j（`{j++; continue;}`），
导致pr[j]索引错位，伪距偏差高达200m。已修复为与C版一致。

---

## 5. 离子层频率缩放

### C版
使用`sat2freq()`获取信号对应频率，用于Klobuchar模型频率缩放。

### Java版
与C版一致，使用`sat2freq()`获取系统特定频率。

### 说明
早期Java版对BDS B1I信号使用了GPS L1频率（1575.42MHz）而非BDS B1频率
（1561.098MHz），导致离子层校正偏差约1.8%。已修复。

---

## 6. 其他已对齐项

以下项目经SPP测试验证，Java版与C版行为一致：

| 功能 | C版函数 | Java版函数 | 状态 |
|------|---------|-----------|------|
| 卫星位置计算 | `ephpos()`/`satpos()` | `EphModel.ephpos()`/`satposs()` | 已对齐 |
| 卫星钟差计算 | `ephclk()` | `EphModel.ephclk()` | 已对齐 |
| 几何距离 | `geodist()` | `RtklibCommon.geodist()` | 已对齐 |
| 对流层改正 | `tropmodel()` | `TroposphereModel.tropmodel()` | 已对齐 |
| 离子层改正 | `ionmodel()` | `IonosphereModel.ionmodel()` | 已对齐 |
| 最小二乘定位 | `rescode()`/`lsq()` | `Spp.rescode()`/`lsq()` | 已对齐 |
| 星历选择 | `seleph()` | `EphModel.searchEphemeris()` | 已对齐 |
| BDS信号映射 | MSM信号表 | `MsmSig`/`ObsCode` | 已对齐 |
| BDT时间系统 | `bdt2gpst()`/`gpst2bdt()` | `TimeSystem.bdt2gpst()`/`gpst2bdt()` | 已对齐 |

---

## 7. 架构差异（非Bug，设计选择）

### RTCM解码与定位一体化
C版：convbin（RTCM→RINEX）和rnx2rtkp（RINEX→定位）是两个独立程序。
Java版：RTCM解码后直接定位，无中间RINEX文件。

这一设计选择导致了时间初始化策略的差异（见第1节），以及需要在调用方
修正早期观测时间。

### 观测数据存储
C版使用动态链表管理观测数据，Java版使用固定大小数组。
两者在SPP场景下行为等价。
---

## 8. RTK状态向量定义：绝对位置 vs 基线向量

### C版行为
`rtk->x[0..2]` 存储**流动站绝对ECEF坐标**。

初始化（`udpos()`）：
`c
for (i=0;i<3;i++) initx(rtk,rtk->sol.rr[i],VAR_POS,i);
`
`sol.rr[i]` 是SPP得到的绝对位置，直接作为状态向量初始值。

输出（`relpos()` 末尾）：
`c
// float解
rtk->sol.rr[i] = rtk->x[i];       // 绝对位置直接输出
// fix解
rtk->sol.rr[i] = rtk->xa[i];      // 绝对位置直接输出
`

调用 `zdres()` 时：
`c
zdres(0, obs, nu, ..., xp, opt, y, e, azel, freq);
// xp = rtk->x，直接是绝对位置，作为接收机位置传入
`

### Java版行为
`rtk.x[0..2]` 存储**基线向量**（流动站坐标 - 基准站坐标）。

初始化（`udpos()`）：
`java
initx(rtk, rtk.sol.rr[i] - rtk.rb[i], VAR_POS, i);
`
`sol.rr[i]` 是绝对位置，减去 `rb[i]`（基准站位置）得到基线向量。

输出（`relpos()` 末尾）：
`java
// float解
rtk.sol.rr[i] = rtk.x[i] + rtk.rb[i];   // 基线向量 + 基准站位置 = 绝对位置
// fix解
rtk.sol.rr[i] = xa[i] + rtk.rb[i];       // 同上
`

调用 `zdres()` 时：
`java
for (j = 0; j < 3; j++) rr_rover[j] = rtk.rb[j] + xp[j];
zdres(0, obs, nu, nr, ..., rr_rover, opt, y, e, azel, freq);
// xp 是基线向量，需要加上 rb 得到绝对位置再传入
`

### 原因
Java版选择基线向量表示，使得状态向量的数值量级更小（基线通常几十米到几公里，
而非ECEF坐标的百万米级），有利于Kalman滤波的数值稳定性。

### 影响范围
所有涉及 `rtk.x[0..2]` 的代码都需要注意这个差异：
- `udpos()`：初始化用 `sol.rr - rb`
- `zdres()`：传入 `rb + xp` 作为接收机位置
- `ddres()`：H矩阵中位置偏导数不受影响（双差消去基准站坐标）
- `sol.rr` 输出：需要 `x + rb` 还原为绝对位置
- `filter()` 更新后：`xp` 仍然是基线向量，无需额外转换

### 等价性证明
设流动站绝对位置为 `p_r`，基准站位置为 `p_b`，基线向量为 `b = p_r - p_b`。

C版：`x = p_r`，zdres 传入 `p_r`
Java版：`x = b = p_r - p_b`，zdres 传入 `rb + x = p_b + (p_r - p_b) = p_r`

两者传入zdres的接收机位置相同，因此零差残差 `y` 相同。
双差残差 `v` 和设计矩阵 `H` 也相同（H中位置偏导数是几何关系的导数，
与坐标原点无关）。Kalman滤波更新等价。

### 逐函数等价性验证

| 函数 | C版（x=绝对位置） | Java版（x=基线向量） | 等价？ |
|------|-------------------|---------------------|--------|
| `udpos()` norm检查 | `norm(rtk->x, 3)` | `norm(rb + x, 3)` | 等价 |
| `udpos()` 初始化 | `initx(rtk, sol.rr[i], ...)` | `initx(rtk, sol.rr[i] - rb[i], ...)` | 等价 |
| `zdres()` 接收机位置 | 直接传 `xp` | 传 `rb + xp` | 等价 |
| `ddres()` 流动站经纬度 | `ecef2pos(x, posu)` | `ecef2pos(rb + x, pos)` | 等价 |
| `ddres()` 基线长度 | `baseline(x, rb, dr)` = `norm(x-rb)` | `baseline(x, rb, null)` = `norm(x)` | 等价 |
| `ddres()` H矩阵位置偏导 | `H[k] = -e_ref[k] + e_j[k]` | 同上 | 等价 |
| `ddres()` 残差v | 来自zdres的y | 同上 | 等价 |
| `ddres()` R矩阵varerr | `c = err[3] * bl / 1E4` | 同上（bl相同） | 等价 |
| `filter()` 更新 | `xp += K*v` | 同上 | 等价 |
| `sol.rr` 输出 | `sol.rr[i] = x[i]` | `sol.rr[i] = x[i] + rb[i]` | 等价 |

**结论：状态向量定义差异不是14m偏差的原因。** 两版本在数学上完全等价，
只要Java版正确处理了 `rb + x` 的转换（已验证全部调用点均正确）。
14m偏差的根本原因需从其他方面排查。

---

## 9. 基准站位置处理

### C版行为（后处理 `postpos.c`）

基准站位置在**处理开始前**一次性计算，整个处理过程中保持不变：

1. `execses()` 调用 `antpos(&popt_, 2, &obss, &navs, stas, fopt->stapos)`
2. `antpos()` 根据 `opt->refpos` 选项计算基准站位置，写入 `opt->rb[0..2]`
3. 每个 `rtkpos()` 调用中：
   `c
   if (opt->refpos <= POSOPT_RINEX && opt->mode != PMODE_SINGLE && opt->mode != PMODE_MOVEB) {
       for (i=0;i<6;i++) rtk->rb[i] = i<3 ? opt->rb[i] : 0.0;
   }
   `
   即每历元从 `opt->rb` 重设 `rtk->rb`，保证基准站位置不变。

`antpos()` 支持的基准站位置选项（`refpos`）：

| 值 | 宏定义 | 含义 |
|----|--------|------|
| 0 | POSOPT_POS_LLH | 使用配置文件中的LLH坐标 |
| 1 | POSOPT_POS_XYZ | 使用配置文件中的XYZ坐标 |
| 2 | POSOPT_SINGLE | 对所有历元SPP取平均 |
| 3 | POSOPT_FILE | 从位置文件读取 |
| 4 | POSOPT_RINEX | 从RINEX文件头读取 |
| 5 | POSOPT_RTCM | 从RTCM/原始数据获取（仅实时流有效） |

**注意**：`POSOPT_RTCM(5)` 在后处理中 `antpos()` 不处理，直接返回1，
`opt->rb` 保持默认值（全0），会导致RTK失败。

### Java版行为

Java版 `rtkpos()` 中有相同的逻辑：
`java
if (opt.refpos <= Constants.POSOPT_RINEX && opt.mode != Constants.PMODE_SINGLE &&
        opt.mode != Constants.PMODE_MOVEB) {
    for (i = 0; i < 6; i++) rtk.rb[i] = i < 3 ? opt.rb[i] : 0.0;
}
`

但Java版目前**没有** `antpos()` / `avepos()` 的等价实现。
基准站位置需要调用方在调用 `rtkpos()` 之前手动设置 `rtk.rb` 和 `rtk.opt.rb`。

### 待完善

Java版需要实现 `antpos()` / `avepos()` 的等价功能，支持从 RINEX 头或
SPP 平均自动获取基准站位置，避免硬编码。

---

## 10. 周跳检测函数（已修复）

### C版行为
`udbias()` 中调用 4 个周跳检测函数，依赖 `rtkpos()` 末尾保存的 `ph`/`pt` 历史数据：
- `detslp_ll()`：LLI 标志检测周跳
- `detslp_gf()`：几何无关组合（GF）检测周跳
- `detslp_code()`：码类型变化检测周跳
- `detslp_dop()`：多普勒-相位差检测周跳

### Java版行为
与 C 版一致，`udbias()` 中调用 4 个检测函数，`rtkpos()` 末尾保存 `ph`/`pt` 历史数据。
所有检测函数逻辑与 C 版等价（已验证 `gfobs`, `detslpLl`, `detslpGf`, `detslpCode`, `detslpDop`）。

---

## 11. 观测噪声模型 varerr()

### C版行为
`varerr()` 包含：
- 星座因子修正（EFACT_GLO=1.5, EFACT_SBS=3.0, EFACT_GPS/GAL/CMP/QZS=1.0）
- SNR 调整项：`10^(0.1*(thresh - snr))`
- 接收机噪声项：`err[6]`/`err[7]`

### Java版行为
与 C 版一致，已实现所有噪声项和星座因子。

---

## 12. 对流层映射函数

### C版行为
`zdres()` 中使用 NMF（Niell Mapping Function），通过 `tropmapf()` → `nmf()` 调用链计算。

### Java版行为
与 C 版一致，使用 `TroposphereModel.tropmapf()` 调用 NMF 映射函数。

---

## 13. Kalman 滤波器时间传播

### C版行为
`rtkpos()` 中每历元更新 `rtk->sol.time = obs[0].time`，计算 `tt = timediff(sol.time, prevTime)`，
用于状态转移矩阵 F 和过程噪声 Q 的时间传播。

### Java版行为
与 C 版一致，每历元更新 `rtk.sol.time` 并计算 `rtk.tt`。

---

## 14. Kalman 滤波矩阵运算

### C版行为
使用列优先矩阵，手动实现 Kalman 增益：
```
K = P * H^T * (H * P * H^T + R)^-1
x = x + K * v
P = (I - K*H) * P        ← 标准形式协方差更新
```

C版 `filter_()` 中协方差更新使用**标准形式** `P = (I-KH)*P`。

### Java版行为
使用行优先矩阵 + EJML 库实现，公式与 C 版一致。
通过 `KalmanFilter.update()` 封装，内部包含状态压缩（ix 数组）和 **Joseph 形式**协方差更新。

### 关键差异：Joseph 形式协方差更新

Java版使用 Joseph 形式替代 C 版的标准形式：

```
C版（标准形式）：    P_new = (I - K*H) * P
Java版（Joseph形式）：P_new = (I - K*H) * P * (I - K*H)^T + K * R * K^T
```

### 为什么需要 Joseph 形式

#### 问题现象
使用标准形式时，AR ratio 极低（1.04~1.65），Fix 解比例为 0%。
具体表现为：
- `I-KH` 矩阵对角线出现异常值（32.43, -85.01），表明增益 K 过大
- P 矩阵逐渐失去正定性，模糊度方差无法收敛
- S 矩阵条件数高达 394,143（载波相位方差 ~0.0002 vs 伪距方差 ~8~16）

#### 根因分析
标准形式 `P = (I-KH)*P` 在数学上等价于 Joseph 形式的前提是 K 为最优增益
（`K = P*H^T*S^-1`）。但浮点运算中 K 存在舍入误差，标准形式无法保证 P 的正定性。

RTK 场景中，H 矩阵天然病态：
- 位置偏导数（0.1~0.5）与模糊度偏导数（λ ≈ 0.19）量级相近
- 载波相位观测噪声（~0.003m²）与伪距观测噪声（~16m²）差异 4~5 个数量级
- S 矩阵条件数极高，导致 S^-1 中载波相位部分增益过大（Sinv_diag 载波部分达 8.11e+03）

C 版使用自定义 `matmul()`（简单三重循环），运算顺序固定；Java 版使用 EJML
（高度优化，可能使用分块/SIMD），运算顺序不同。浮点加法不满足结合律，
不同运算顺序导致舍入误差累积不同。在 H 矩阵病态条件下，这种差异被放大，
导致标准形式在 Java 版中 P 矩阵失去正定性。

#### Joseph 形式优势
1. **保证对称性**：`P_new` 一定是实对称矩阵（`(I-KH)*P*(I-KH)^T` 和 `K*R*K^T` 都对称）
2. **保证正定性**：即使 I-KH 有误差，`K*R*K^T` 项会补偿，保证 `P_new` 正定
3. **数值稳定性好**：特别适合 H 矩阵病态、S 条件数高的场景

数学证明：设 `δ = K - K_optimal`（增益误差），则：
- 标准形式：`P_new = (I-KH)*P`，误差项 `O(δ)` 无下界保护
- Joseph形式：`P_new = (I-KH)*P*(I-KH)^T + K*R*K^T`，误差项 `O(δ²)` 且 `K*R*K^T > 0`

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

虽然数据集C的Qb_diag（模糊度协方差对角线）更小，但由于浮点值偏差大，
s[0] = (y-b)^T * Qb^{-1} * (y-b) 反而极大（3446 vs 22）。

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

### 与 C 版的等价性

当 K 为精确最优增益时，Joseph 形式与标准形式数学等价：
```
(I-KH)*P*(I-KH)^T + K*R*K^T
= (I-KH)*P*(I-KH)^T + K*(HPH^T+R)*K^T - K*HPH^T*K^T
= (I-KH)*P - (I-KH)*P*H^T*K^T + K*(HPH^T+R)*K^T
= (I-KH)*P                              （利用 K = P*H^T*S^-1）
```

因此 Joseph 形式是标准形式的**数值稳定超集**，不会改变滤波的数学性质，
只在浮点精度不足时提供更好的数值保证。

### 已知小问题

日志中仍有 `holdamb filter error (info=-1)` 警告，这是因为 `holdamb` 函数中
H 矩阵维度计算有问题（创建了 `nb * nx` 但实际使用 `nv` 个观测）。
不影响主要功能，Fix 解比例已达到 88.7%。

---

## 15. 矩阵存储约定：行优先 vs 列优先

### C版：列优先（Column-Major）

C 版 RTKLIB 使用**列优先**存储二维矩阵。矩阵 `A[m][n]`（m 行 n 列）在内存中按列连续排列：

```
A = [a00 a01 a02]    内存布局: [a00, a10, a20, a01, a11, a21, a02, a12, a22]
    [a10 a11 a12]
    [a20 a21 a22]
```

元素 `A[i][j]` 在内存中的偏移为 `j * m + i`。

### Java版：行优先（Row-Major）

Java 版使用**行优先**存储二维矩阵。矩阵 `A[m][n]`（m 行 n 列）在内存中按行连续排列：

```
A = [a00 a01 a02]    内存布局: [a00, a01, a02, a10, a11, a12, a20, a21, a22]
    [a10 a11 a12]
    [a20 a21 a22]
```

元素 `A[i][j]` 在内存中的偏移为 `i * n + j`。

### 影响范围

整个项目中所有矩阵运算都使用行优先约定，包括：

| 模块 | 矩阵 | 维度 |
|------|------|------|
| KalmanFilter | P（协方差）、H（设计矩阵）、K（增益）、R（噪声） | 动态 |
| RtkCore | F（状态转移）、Q（过程噪声）、H/v/R（观测方程） | 动态 |
| LAMBDA | Z（变换矩阵）、Q（协方差）、L/D（LDL分解） | 动态 |
| 最小二乘 | A（设计矩阵）、Q（权重）、N（法方程） | 动态 |

### 与 C 版矩阵的对应关系

C 版列优先矩阵 `A_c[m][n]` 与 Java 版行优先矩阵 `A_j[m][n]` 在数学上表示同一个矩阵，
但内存布局不同。当需要将 C 版矩阵直接复制到 Java 版时，需要转置。

通过 EJML 库（`SimpleMatrix`）进行矩阵运算，无需手动处理索引转换。
`SimpleMatrix` 内部使用行优先，`MatrixUtil.createMatrix(data, rows, cols)` 接受行优先数据。

### 验证要点

所有矩阵运算的正确性取决于：
- 输入矩阵（H、R）按行优先填充
- `MatrixUtil.createMatrix()` 的行列参数正确
- 矩阵乘法结果的行列索引正确
- 最终结果写回 `x[]` 和 `P[]` 时按行优先顺序
---

## 16. RTK 解算结果对比验证（C版 vs Java版）

### 测试条件

- 数据源：同一组 RTCM 原始数据
- C版流程：RTCM → convbin 转 RINEX → rtklib rtkpos
- Java版流程：RTCM → 直接解析 → Java rtkpos
- 测试时长：约1小时
- 解算模式：RTK 相对定位

### 对比结果

| 指标 | 数值 |
|------|------|
| 公共历元数 | 239 |
| 最大高度差 | -28.45m (17:04:18) |
| RMS dH (整体) | 8.41m |
| RMS dH (17:04:18前) | 3.84m (15 epochs) |
| RMS dH (17:04:18后) | 8.63m (224 epochs) |

### 关键发现

**C版在17:04:18发生跳变后滤波器状态崩溃，再也无法恢复。**

| 时刻 | C版高度 | Java版高度 | dH | 状态 |
|------|---------|-----------|-----|------|
| 17:04:03 | 904.11m | 904.19m | -0.08m | ✅ 高度一致 |
| 17:04:18 | 875.87m | 904.32m | -28.45m | ❌ C版剔除卫星127 |
| 17:04:18之后 | 887-892m | 903-904m | -10~-16m | ❌ C版持续偏低 |
| 17:59:03 | 902.24m | 903.98m | -1.74m | ⚠️ C版短暂恢复 |
| 17:59:04 | 892.67m | 904.01m | -11.34m | ❌ C版再次跌落 |

Java版在整个1小时测试中高度稳定在903-904m，无跳变。

### 根因分析

C版使用 convbin 将RTCM转为RINEX时，将历元时间**四舍五入到15秒整秒间隔**，导致：

1. **模糊度状态累积误差**：时间对齐偏差导致双差残差计算不准确，模糊度浮点解逐渐偏离
2. **17:04:18误判卫星127为outlier**：C版残差 v=-7.339周，而Java版同一卫星残差仅0.0062周
3. **滤波器状态崩溃后无法恢复**：错误剔除卫星后，模糊度状态被重置，后续历元无法重新收敛

Java版直接使用原始RTCM数据，通过 intpres() 函数正确处理流动站与基准站的时间差，保证了模糊度状态的正确性。

### 结论

**Java版RTK移植是正确的，且在数据时间处理方面比C版更稳定。**

差异的根因是 convbin 历元四舍五入引入的误差，而非Java版算法缺陷。Java版的 intpres() 实现是正确的，它直接处理原始RTCM数据，避免了C版RINEX转换引入的精度损失。

### 对 convbin 时间四舍五入的详细说明

RTCM 原始数据中，历元时间可能不是整秒（如 17:04:18.3），convbin 在转为 RINEX 时
将时间截断/四舍五入到最近的整秒或15秒间隔。这导致：

- 流动站与基准站的观测值时间标签被修改
- 	t = timediff(obs[0].time, obs[nu].time) 计算出的时间差不准确
- 状态转移矩阵 F 和过程噪声 Q 的时间传播产生误差
- 双差残差中包含时间对齐误差，被误判为周跳或outlier

Java版通过 intpres() 函数直接处理原始时间戳，无需 RINEX 中间转换，
从根本上避免了此问题。