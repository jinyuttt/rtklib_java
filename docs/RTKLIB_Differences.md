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

## 3. 架构差异（非Bug，设计选择）

### RTCM解码与定位一体化
C版：convbin（RTCM→RINEX）和rnx2rtkp（RINEX→定位）是两个独立程序。
Java版：RTCM解码后直接定位，无中间RINEX文件。

这一设计选择导致了时间初始化策略的差异（见第1节），以及需要在调用方
修正早期观测时间。

### 观测数据存储
C版使用动态链表管理观测数据，Java版使用固定大小数组。
两者在SPP场景下行为等价。

### SPP状态向量维度
C版使用固定 `NX=4+4`（或 `4+5`），始终估计所有系统间钟差偏移。
Java版动态计算 `NX`，仅估计启用系统的钟差（更高效）。
`dtr` 数组已改为固定索引（`dtr[1]`=GLO, `dtr[2]`=GAL, `dtr[3]`=BDS, `dtr[4]`=IRN），
与C版和PPP `udclk_ppp` 一致。未启用系统的 `dtr[i]=0`。
---

## 4. RTK状态向量定义：绝对位置 vs 基线向量

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

---

## 5. 基准站位置处理

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
```java
if (opt.refposmode != Constants.REFPOS_RTCM && opt.mode != Constants.PMODE_SINGLE &&
        opt.mode != Constants.PMODE_MOVEB) {
    for (i = 0; i < 6; i++) rtk.rb[i] = i < 3 ? opt.rb[i] : 0.0;
}
```

MOVEB 模式下 `RtkCore.rtkpos()` 已实现 SPP 平均获取基准站位置（与C版 `avepos()` 等价）。

`RinexRtkProcessor` 和 `RinexSppProcessor` 在处理 RINEX 文件时，自动从
`APPROX POSITION XYZ` 头部读取基准站位置（仅当 `rtk.rb` 为0时生效，
手动设置 `setBasePosition()` 优先级更高）。

### 待完善

Java版目前**没有** `antpos()` 的完整等价实现。缺失部分：
- `POSOPT_SINGLE`：对非MOVEB模式的Static/Kinematic，缺少SPP取平均自动获取基准站位置
- `POSOPT_FILE`：缺少从位置文件读取基准站位置

---

## 6. Kalman 滤波矩阵运算

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

标准形式 `P = (I-KH)*P` 在数学上等价于 Joseph 形式的前提是 K 为最优增益
（`K = P*H^T*S^-1`）。但浮点运算中 K 存在舍入误差，标准形式无法保证 P 的正定性。

RTK 场景中，H 矩阵天然病态：
- 位置偏导数（0.1~0.5）与模糊度偏导数（λ ≈ 0.19）量级相近
- 载波相位观测噪声（~0.003m²）与伪距观测噪声（~16m²）差异 4~5 个数量级
- S 矩阵条件数极高，导致 S^-1 中载波相位部分增益过大

C 版使用自定义 `matmul()`（简单三重循环），运算顺序固定；Java 版使用 EJML
（高度优化，可能使用分块/SIMD），运算顺序不同。浮点加法不满足结合律，
不同运算顺序导致舍入误差累积不同。在 H 矩阵病态条件下，这种差异被放大，
导致标准形式在 Java 版中 P 矩阵失去正定性。

Joseph 形式优势：
1. **保证对称性**：`P_new` 一定是实对称矩阵
2. **保证正定性**：即使 I-KH 有误差，`K*R*K^T` 项会补偿，保证 `P_new` 正定
3. **数值稳定性好**：特别适合 H 矩阵病态、S 条件数高的场景
4. **误差量级更小**：标准形式误差项 `O(δ)`，Joseph 形式误差项 `O(δ²)`

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

日志中偶有 `holdamb filter error (info=-1)` 警告，这是 EJML 矩阵求逆失败导致。
C 版同样使用 `nb*nx` 分配 H 矩阵、实际使用 `nv*nx`（`nv ≤ nb`），LAPACK 求逆失败时
同样返回错误码。两者行为一致，不影响主要功能，Fix 解比例已达到 88.7%。

> 测试数据集详情（数据集A/B/C对比、C版验证方法等）见 `RTK_Debug_Record.md`。

---

## 7. 矩阵存储约定：行优先 vs 列优先

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

## 8. RTK优化项（Java版独有） (2026-07-16)

以下三项优化是Java版独有的，C版RTKLIB无对应功能。所有优化通过`RtkConfig`独立开关控制，默认关闭。

### 8.1 滑动窗自适应Q矩阵（enableAdaptiveQ）

| 差异项 | 说明 |
|--------|------|
| C版 | 固定过程噪声，`Q = prn[3]² * |tt|`，不区分运动状态 |
| Java版 | 环形滑动窗（50历元）计算位置增量RMS，Sigmoid映射到缩放因子α∈[0.01, 5.0]，`Q *= α²` |
| 影响 | 静态时噪声压制（α→0.01），动态时快速响应（α→5.0），滑坡监测场景精度提升明显 |
| 新增字段 | `Rtk.xOld[3]`, `Rtk.posWin[100]`, `Rtk.winIdx`, `Rtk.winCnt` |
| 新增配置 | `adaptiveQWinSize`, `adaptiveQStaticThresh`, `adaptiveQDynamicThresh`, `adaptiveQScaleMinStatic`, `adaptiveQScaleMaxDynamic` |

### 8.2 模糊度子集锚固（enableAmbAnchor）

| 差异项 | 说明 |
|--------|------|
| C版 | Fix-and-Hold全部使用`varholdamb`协方差，LAMBDA失败时可能重置所有模糊度 |
| Java版 | 连续固定≥100历元的模糊度标记为"锚固"，协方差压制到1e-9（数学上等价于已知常数），LAMBDA搜索跳过已锚固子集 |
| 影响 | 解决频繁跳变问题，短时遮挡下基线解维持在毫米级精度 |
| 新增字段 | `Rtk.ambAnchored[MAXSAT*NF]`, `Rtk.ambAnchorCount[MAXSAT*NF]` |
| 新增配置 | `enableAmbAnchor`, `ambAnchorMinFixCount`, `ambAnchorVar` |

### 8.3 大气参数自适应冻结（atmFrozenNsThresh）

| 差异项 | 说明 |
|--------|------|
| C版 | 无论卫星数多少，每历元都更新电离层/对流层过程噪声 |
| Java版 | ns < 7时跳过`udion()`和`udtrop()`的过程噪声更新，冻结大气参数状态 |
| 影响 | 防止少星时法方程病态导致虚假坐标跳变 |
| 新增配置 | `atmFrozenNsThresh` (默认7) |

### 8.4 IGGIII 抗差估计（enableIggiii）

| 差异项 | 说明 |
|--------|------|
| C版 | 无对应功能，使用标准最小二乘 |
| Java版 | 基于IGGIII等价权函数，根据标准化残差动态降权：正常段(≤K0)权重1.0，可疑段(K0~K1)降权，淘汰段(>K1)权重趋零 |
| 影响 | 抑制粗差对Kalman滤波的影响，减少异常观测导致的模糊度重置和坐标跳变 |
| 新增配置 | `iggiiiK0`(1.5), `iggiiiK1`(3.0), `iggiiiMinW`(1e-4), `iggiiiLowElMask`, `iggiiiLowElNormThresh`, `iggiiiLowElW`, `iggiiiMultiFreqW` |

#### 核心算法

```
1. 计算 H*P*H' 对角线 diag[]（预测残差方差）
2. 对每个观测 i:
   sigma = sqrt(R[i,i])
   predVar = max(diag[i], 1e-30)
   innovation = |v[i]| / sqrt(predVar + sigma²)   // 标准化残差
3. IGGIII 等价权:
   innovation ≤ K0(1.5)  → w = 1.0       (正常段，保留全部信息)
   K0 < innovation ≤ K1(3.0) → w = K0/innovation (可疑段，降权)
   innovation > K1(3.0) → w = minW(1e-4)  (淘汰段，几乎零权)
4. 低高度角卫星额外惩罚:
   el < lowElMask 且 innovation > lowElNormThresh → w = min(w, lowElW)
5. 多频一致性惩罚:
   同一卫星任一频点被降权 → 该星所有频点权重不超过 multiFreqW
6. 修改观测噪声: R[i,j] /= w[i]  (等效于对角加权)
```

#### 实现位置
- `RtkOptimizations.applyIggiii()`：完整实现
- `RtkOptimizations.computeHPHtDiagNative()`：计算 H*P*H' 对角线
- `RtkCore.relpos()`：在 ddres 之后、filter 之前调用

### 8.5 SNR 中值参考星选择（enableSnrMedian）

| 差异项 | 说明 |
|--------|------|
| C版 | 参考星仅按高度角选择，不考虑信号质量 |
| Java版 | 计算各频点SNR中值，用于改进参考星选择和观测权重 |
| 影响 | 避免低SNR卫星被选为参考星，改善双差观测质量 |
| 新增配置 | `snrMedianMinEl`, `snrMedianMinLockTime`, `snrMedianMinSatsForFallback`, `snrMedianFallbackPhaseRef`, `snrMedianAbsMin` |

#### 核心算法

```
1. 对每个频点 f:
   - 筛选有效卫星: el > minEl, lockTime > minLockTime, SNR > absMin
   - 有效卫星数 ≥ minSatsForFallback → 取SNR中值
   - 否则 → 使用 fallbackPhaseRef 作为默认中值
2. rtk.snrMedian[f] 存储结果
3. 后续参考星选择可参考 snrMedian 值
```

#### 实现位置
- `RtkOptimizations.computeSnrMedian()`：完整实现
- `RtkCore.relpos()`：在 udstate 之前调用

---

## 9. Bug修复记录 (2026-07-18)

以下6个Bug中，5个是优化过程中引入的，1个是原始移植遗漏。阶段6（07-16）RTK核心管道重构时
使用了 `NA(rtk, ns)` 动态索引 + `naOff + i*nf + f` 相对索引方案，该方案内部自洽，
阶段6测试通过（Q匹配率100%）。阶段7（07-16/17）添加优化时引入了C版风格的
`buildParIndex()`/`ddidxFallback()`（需要 `rtk.na` + MAXSAT遍历），与阶段6的
动态索引方案不兼容，导致Bug。修复方案是统一回C版的固定索引方案。

| Bug# | 问题 | 来源 | 引入阶段 |
|------|------|------|----------|
| 9.1 | NI() 返回动态值 | 优化引入：添加 ionoGradient 时修改 | 阶段7.4 |
| 9.2 | NT() 返回 1/3 | 优化引入：添加 TROPOPT_ESTG 时错误理解 | 阶段7.4 |
| 9.3 | 缺少状态向量初始化 | 原始移植遗漏 | 阶段6 |
| 9.4 | udstate 在 xp/Pp 复制之后执行 | 优化引入：重构时执行顺序错误 | 阶段6 |
| 9.5 | 状态向量大小随 ns 动态变化 | 优化引入：NA(rtk,ns) 替代 NB(opt) | 阶段6 |
| 9.6 | 模糊度索引用相对位置 | 优化引入：naOff+i*nf+f 替代 IB() | 阶段6 |
| 9.6a | rtk.na 未设置 | 优化引入：buildParIndex需要但未设置 | 阶段7 |

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

### 9.7 新增辅助函数

为配合上述修复，新增以下与C版对应的辅助函数：

| 函数 | 定义 | 说明 |
|------|------|------|
| `NB(rtk)` | `MAXSAT * nf` (DGPS模式=0) | 模糊度状态数（固定大小） |
| `NL(rtk)` | `glomodear==AUTOCAL ? NFREQGLO : 0` | GLONASS IC bias数 |
| `NR(rtk)` | `NP + NI + NT + NL` | 非模糊度状态总数 |
| `IT(r, opt)` | `NP + NI + (nt/2)*r` | 对流层参数索引（r=0流动站, r=1基准站） |
| `IL(f, opt)` | `NP + NI + NT + f` | GLONASS IC bias索引 |
| `IB(sat, f, opt)` | `NR + MAXSAT*f + (sat-1)` | 模糊度状态索引（按卫星号） |

### 9.8 新增常量

| 常量 | 值 | 说明 |
|------|-----|------|
| `VAR_POS` | 900.0 (30²) | 初始位置方差 (m²) |
| `VAR_POS_FIX` | 1E-8 | 固定解位置方差 (m²) |
| `VAR_VEL` | 100.0 (10²) | 初始速度方差 ((m/s)²) |
| `VAR_ACC` | 100.0 (10²) | 初始加速度方差 ((m/s²)²) |

### 9.9 修改文件清单

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

## 10. 额外优化详细说明 (2026-07-18)

以下补充第8节中未完整记录的优化细节。

### 10.1 参考星重选优化（enableParRefReselect）

| 差异项 | 说明 |
|--------|------|
| C版 | `ddidx()` 每历元独立选择参考星，不考虑历元间一致性 |
| Java版 | `buildParIndex()` 记录上一历元参考星，优先沿用；连续重选超过阈值时排除不稳定卫星并回退 |
| 影响 | 减少参考星频繁切换导致的模糊度重置，提高Fix解稳定性 |
| 新增配置 | `enableParRefReselect`, `parElMask`, `parMaxConsecutiveReselect` |
| 新增字段 | `Rtk.parPrevRefSat[NFREQ]`, `Rtk.parExcludedSats[]`, `Rtk.parExcludedSatCount`, `Rtk.parConsecutiveReselectCount` |

#### 核心算法

```
1. 对每个频点 f:
   a. 优先沿用上一历元参考星 parPrevRefSat[f]
   b. 若上一参考星不可用，选择最高高度角的合格卫星
   c. 标记参考星 fix[f] = 2，非参考合格卫星 fix[f] = 2，不合格卫星 fix[f] = 1
2. 连续重选计数:
   - 参考星与上一历元不同 → consecutiveReselectCount++
   - 参考星与上一历元相同 → consecutiveReselectCount = 0
3. 排除机制:
   - consecutiveReselectCount > maxConsecutiveReselect → 清空排除列表，回退到标准 ddidxFallback()
```

#### 实现位置
- `RtkOptimizations.buildParIndex()`：完整实现
- `RtkOptimizations.ddidxFallback()`：标准C版逻辑的Java实现
- `RtkCore.ddidx()`：根据 `enableParRefReselect` 开关选择调用

### 10.2 电离层梯度扩展（enableIonoTropGradient / ionoGradient）

| 差异项 | 说明 |
|--------|------|
| C版 | `IONOOPT_EST` 模式下每颗卫星估计1个电离层延迟参数 |
| Java版 | 梯度模式下每颗卫星估计3个参数：总延迟 + 北向梯度 + 东向梯度 |
| 影响 | 长基线（>20km）场景下电离层空间相关性建模更精确 |
| 新增配置 | `enableIonoTropGradient`, `gradientIonoInitVar`, `gradientIonoPrn` |
| 新增字段 | `PrcOpt.ionoGradient` (boolean) |

#### 核心算法

```
1. NI(opt) 扩展:
   - ionoGradient = false → NI = MAXSAT (每星1个参数)
   - ionoGradient = true  → NI = MAXSAT * 3 (每星3个参数)

2. II(sat, opt) 索引扩展:
   - ionoGradient = false → II(sat) = NP + (sat-1)
   - ionoGradient = true  → II(sat) = NP + (sat-1)*3
     - II(sat)+0: 总电离层延迟
     - II(sat)+1: 北向梯度 Gn
     - II(sat)+2: 东向梯度 Ge

3. ddres() 中双差残差修正:
   v -= scale * cotEl * cos(az) * Gn   (北向梯度贡献)
   v -= scale * cotEl * sin(az) * Ge   (东向梯度贡献)
   H 矩阵相应偏导数填充

4. udion() 过程噪声:
   - ionoGradient = true 时，梯度参数使用 gradientIonoPrn 作为过程噪声
```

#### 当前状态
`enableIonoTropGradient` 配置项已定义，但尚未在 `RtkProcessor` 中连接到 `PrcOpt.ionoGradient`。
需要在使用时手动设置 `opt.ionoGradient = true`，或添加配置连接逻辑。

### 10.3 自适应Q矩阵：零速检测详细说明

第8.1节描述了滑动窗方案，此处补充零速检测的完整逻辑。

#### 零速检测算法

```
1. 速度检测:
   - dynamics 模式下: speed = ||x[3..5]|| (状态向量中的速度分量)
   - speed ≥ zeroVelSpeedThresh(0.5 m/s) → 非零速，返回 false

2. 位置差分检测:
   - posDiff = ||curPos - prevPosForZeroVel||
   - posDiff ≥ zeroVelPosDiffThresh(0.05 m) → 非零速，返回 false

3. 连续历元计数:
   - 通过以上两项检测 → consecutiveZeroVelEpochs++
   - 未通过 → consecutiveZeroVelEpochs = 0
   - consecutiveZeroVelEpochs ≥ zeroVelConsecutiveEpochs(3) → 判定为零速

4. 零速对Q缩放的影响:
   - 零速时: scaleMin = adaptiveQScaleMinZeroVel(0.1) — 更强压制
   - 非零速时: scaleMin = adaptiveQScaleMinMoving(0.5) — 允许更大变化
```

#### 新增字段

| 字段 | 说明 |
|------|------|
| `Rtk.prevPosForZeroVel[3]` | 上一历元绝对位置（用于位置差分） |
| `Rtk.consecutiveZeroVelEpochs` | 连续零速历元计数 |

### 10.4 NX_RTK 常量更新说明

#### 修改原因

修复Bug后，状态向量大小从动态变为固定：
- 旧: `NX_RTK = 337`（仅覆盖 NSAT* 各系统卫星数之和，不够容纳 MAXSAT 布局）
- 新: `NX_RTK = 9 + MAXSAT*3 + 6 + NFREQGLO + MAXSAT*3 = 1385`

#### 各项含义

```
NX_RTK = NP_max + NI_max + NT_max + NL_max + NB_max
       = 9       + 684      + 6      + 2       + 684
       = 1385

其中:
- NP_max = 9 (dynamics模式: 3位置 + 3速度 + 3加速度)
- NI_max = MAXSAT * 3 = 228 * 3 = 684 (ionoGradient模式: 每星3个电离层参数)
- NT_max = 6 (TROPOPT_ESTG模式: 流动站3 + 基准站3)
- NL_max = NFREQGLO = 2 (GLONASS IC bias)
- NB_max = MAXSAT * 3 = 684 (3频点模糊度)
```

#### 位置调整

`NX_RTK` 原定义在 `MAXSAT` 之前（第229行），导致编译时 `MAXSAT` 未定义。
已将 `NX_RTK` 移至 `MAXSAT` 定义之后。

### 10.5 rtk.na 字段设置

#### C版行为
```c
// rtkinit() 中一次性设置
rtk->na = opt->mode <= PMODE_FIXED ? NR(opt) : pppnx(opt);
```

#### Java版修复前
`rtk.na` 在 `RtkProcessor` 初始化时设为 0，`relpos()` 中未更新。
`buildParIndex()` 和 `ddidxFallback()` 中使用 `rtk.na` 作为模糊度区域起始索引，
值为 0 导致索引完全错误。

#### Java版修复后
```java
// relpos() 中每历元设置
rtk.na = NR(rtk);
```

与C版 `NR(opt)` 等价，确保 `buildParIndex()` 和 `ddidxFallback()` 中
`k = na` 正确指向模糊度区域起始位置。