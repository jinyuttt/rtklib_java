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

## 8. RTK额外优化项（Java版独有）

Java版包含7项C版RTKLIB没有的额外优化，通过`RtkConfig`独立开关控制，默认全部关闭。

**详细文档见 [RTK_Extra_Optimizations.md](RTK_Extra_Optimizations.md)**，包含每项优化的：
- 问题背景与C版对比
- 核心算法与数学原理
- 配置参数与新增字段
- 实现位置与代码引用
- 优化项之间的依赖关系与调用顺序

### 优化项概览

| # | 名称 | 开关 | 一句话说明 |
|---|------|------|-----------|
| 1 | 滑动窗自适应Q矩阵 | `enableAdaptiveQ` | 位置增量RMS映射Q缩放因子，静态压制/动态放大 |
| 2 | 模糊度子集锚固 | `enableAmbAnchor` | 长期固定模糊度跳过LAMBDA搜索，防止跳变 |
| 3 | 大气参数自适应冻结 🔭 | `atmFrozenNsThresh` | 少星时冻结电离层/对流层参数（长基线） |
| 4 | IGGIII抗差估计 | `enableIggiii` | 标准化残差三段降权，抑制粗差 |
| 5 | SNR中值参考星选择 | `enableSnrMedian` | SNR中值辅助参考星选择 |
| 6 | PAR参考星重选 | `enableParRefReselect` | ratio不足时排除差星重选参考星 |
| 7 | 电离层/对流层梯度 🔭 | `enableIonoTropGradient` | 每星VTEC+Gn+Ge三参数（长基线） |

🔭 = 长基线优化项（>10km），短基线无效果

---

## 9. Bug修复记录

已移至 [RTK_Debug_Record.md](RTK_Debug_Record.md) "阶段7：索引体系Bug修复 (2026-07-18/19)" 及 "阶段10：观测值质量控制修复 (2026-07-19)" 章节。

## 10. 额外优化详细说明

已移至 [RTK_Extra_Optimizations.md](RTK_Extra_Optimizations.md)，包含每项优化的完整算法、配置参数和实现位置。

## 11. 观测值质量控制差异（2026-07-19 修复）

C版RTKLIB在 `ddres()` 和 `udbias()` 中有完整的观测值质量控制机制，
Java版原始移植时遗漏了以下关键逻辑：

| 功能 | C版 | Java版(修复前) | Java版(修复后) |
|------|-----|---------------|---------------|
| ddres() 残差剔除 | `maxinno` 阈值检查，超限则 `vsat=0, rejc++, continue` | 无 | ✅ 已添加 |
| ddres() 阈值调整 | 模糊度刚初始化时 `threshadj=10` | 无 | ✅ 已添加 |
| udbias() rejc重置 | `rejc>=2` 或周跳时重置模糊度 | 无 | ✅ 已添加 |
| udbias() lock设置 | 重置时 `lock=-minlock` | 无 | ✅ 已添加 |
| udbias() icbias | 非GLONASS卫星重置时清零 | 无 | ✅ 已添加 |
| valpos() 日志 | 大残差输出 `errmsg` | 无输出 | ✅ 已添加 rejc 计数 |
| valpos() 剔除 | 始终返回1 | 始终返回true | ✅ 超半数异常返回false |