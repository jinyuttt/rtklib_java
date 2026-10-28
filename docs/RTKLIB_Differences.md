# Java版与RTKLIB C版实现差异说明

本文档记录Java版RTKLIB与C版RTKLIB在实现上的有意差异。
这些差异是经过验证的必要修改或设计选择，不应随意回退。

**C 参考源码基准（2026-10-13 声明）**：本仓库 `RTKLIB-2.5.0/` 目录 = **rtklibexplorer demo5 分支**
（`rtklib.h`: `VER_RTKLIB "EX"` + `PATCH_LEVEL "2.5.0"`，基于官方 2.4.3 分支增强）。
IFLC（`IONOOPT_IFLC`）、ddres 的 `threshadj` 等为 demo5 特有特性（官方 2.4.3 无）；
`rinex.c set_index()`/`rtkcmn.c code2idx()` 的槽位机制与官方 2.4.3 一致。
本文档所称"C版"均指该基准，勿与官方 T.Takasu 2.4.3 混淆对照。

Bug 修复的调试过程不在本文档中，请参见 `SPP_Debug_Record.md` 和 `RTK_Debug_Record.md`。

**收录判据**：本文档只写"当前仍然存在的差异"。修复后已与 C 版一致的内容不写入本文档
（无论取证过程多么有价值），一律记入 [ALGORITHM_FIX_LOG.md](ALGORITHM_FIX_LOG.md)；
已对齐的小节直接删除、不保留存根，仅在本节说明中注明编号作废。

---

## 1. RTCM时间初始化策略

### C版行为
`adjweek()`、`adjustGpsWeek()`、`adjustBdtWeek()`、`adjdayGlot()` 在时间
未初始化时使用`timeget()`作为参考周数。`timeget()`的返回值取决于启动模式
和参数，**不同参数会产生不同的时间解析结果**：

| 启动模式 | `timeget()` 返回 | 代码位置 | 说明 |
|---------|------------------|---------|------|
| 文件回放+timetag | 文件timetag中的时间 | `stream.c:658` | `timeset(gpst2utc(file->time))` |
| 文件回放无timetag | 系统当前时间 | — | `timeget()`直接返回系统时间 |
| 实时流 | 系统时间→首解后修正 | `rtksvr.c:656` | `timeset(gpst2utc(timeadd(svr->rtk.sol.time,tt)))` |
| 后处理(postpos) | 系统当前时间 | — | 不调用`timeset()` |

### C版离线处理的已知问题
C版`adjweek()`的致命缺陷：**只调整TOW的±半周（302400秒），但week直接
取自`timeget()`初始化的值，不随TOW调整**。当`timeget()`返回的时间与数据
时间不在同一GPS周时，所有观测时间被整体偏移整数周。

验证实例（本地BDS双频RTCM3数据）：
- 数据真实时间：GPS_week=2419, TOW=39630s → 2026/05/17 11:00:30 UTC
- C版`timeget()`=2026-09-16 → GPS_week=2436（文件timetag时间）
- C版`adjweek()`：TOW=39630s不变（在±半周内），week=2436被锚定
- C版输出：**2026/09/13 11:00:30 UTC**（偏移17周=119天）❌
- Java版输出：**2026/05/17 11:00:30 UTC** ✅

### Java版行为
时间未初始化时不使用CPU时间或文件时间，而是：
- `adjweek()`：用week=0创建临时时间，不设`timeInitialized`，让星历消息
  建立正确的时间基准
- `adjustGpsWeek()`/`adjustBdtWeek()`：返回原始周数，不做1024周调整
- `adjdayGlot()`：直接返回，不修改时间

### 原因
Java版用于离线处理RTCM数据直接定位，没有convbin转RINEX的中间步骤。
`timeget()`返回的时间（无论是系统时间还是文件时间）可能与数据真实观测
时间相差数月甚至数年，使用它做周数调整会导致时间完全错误。

### 影响范围
仅影响MSM消息在星历消息之前到达的场景（即前几个历元）。
星历消息到达后`timeInitialized`被设为true，后续处理与C版完全一致。

### 补偿措施
调用方（如`RtkProcessor`、`RtcmCallbackDecoder`）在所有数据解码完成后，
用星历toe的周数修正早期观测时间（`correctObsWeek()`/`flushPendingEpochs()`）。
这是离线处理的必要步骤。

### 验证（2026-10-11）
`testGpsWeekRolloverAnalysis()` 单元测试完整复现了C版的错误行为：
- C版`adjweek()`用文件timetag时间(2026-09-16)初始化→GPS_week=2436
- 数据TOW=39630s在±半周内不调整→week=2436被锚定
- 输出2026/09/13 11:00:30 UTC（偏移119天）❌
- Java版输出2026/05/17 11:00:30 UTC ✅

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

## 4. RTCM3 MSM obsflag放宽检查（relaxSyncFlag）——兼容习惯性错误

### C版行为
RTCM3 MSM消息中 Multiple Message Indicator (MMI/sync) 标志：
- sync=0：本消息是当前历元最后一条（或唯一一条），obsflag=1，输出历元
- sync=1：当前历元还有后续消息，obsflag=0，累积观测不输出

C版严格按标准实现：`rtcm->obsflag=!sync; return sync?0:1;`
上层仅当 obsflag==1 时提取观测数据，sync=1 的观测被丢弃。

### 标准定义
RTCM 10403.x 标准明确定义：
- MMI=0：本消息为当前历元的最后一条消息
- MMI=1：当前历元还有后续消息待接收

### 现实兼容性问题
多种接收机/NTRIP源对MMI处理不一致，属于**习惯性错误**：
- BNC（德国联邦制图和测地局NTRIP客户端）源码注释明确指出：
  > "Since the Multiple Message Indicator is inconsistently handled by various receivers"
- RTKLIB Issue #104：NTRIP源发送的RTCM 1004以sync=1结尾，导致GPS数据被丢弃
- RTKLIB Issue #265：2016/2017闰秒导致GLONASS历元时间误判，连锁触发sync标志异常
- 实测数据 `rtcm3_gmsd_20121014.rtcm3` 中GPS 1077和GLONASS 1087消息均为
  sync=1且无后续消息，属于非标准行为

### Java版行为
在 `Rtcm` 类增加 `relaxSyncFlag` 配置项（默认 `false`，保持与C版一致的标准行为）。

**修复在解码层完成**，而非上层绕过检查。解码函数中：
```java
// 原来（与C版一致）：
this.obsflag = sync != 0 ? 0 : 1;

// 改为：
this.obsflag = (sync != 0 && !this.relaxSyncFlag) ? 0 : 1;
```
当 `relaxSyncFlag=true` 时，即使sync=1，obsflag也会被置为1，
上层代码（RtkProcessor、PppProcessor、BaseStationMonitor等）继续检查
`obsflag==1`，但该条件在relax模式下因解码层的修改而自然满足。

涉及修改的解码函数：decodeType1001-1004（legacy）、decodeType1012、
decodeMsm0/4/5/6/7（MSM），共12处obsflag设置。

### 设计原因
1. obsflag的语义是"历元观测是否就绪可用"（业务信号），不是sync原始值记录，
   因此修复应在解码层完成，让obsflag始终是可靠的就绪信号
2. 上层代码无需感知relaxSyncFlag的存在，自动生效，不会遗漏
3. 标准行为在遇到非标准数据源时会完全丢弃观测，导致无法定位
4. C版无此配置选项，属于Java版增强

### 注意事项
- `relaxSyncFlag=true` 仅适用于单消息历元（I=1）sync=1的非标准场景
- 如果数据源真的是多消息历元（I>1），开启此选项会导致第一条消息就被当作
  完整历元输出，后续消息丢失。这是用户配置责任，代码无法自动判断
- RINEX文件处理不受影响（RINEX观测已按历元组织，无sync标志）

### 使用建议
- 标准NTRIP源（如IGS站点）：保持默认 `relaxSyncFlag=false`
- 非标准或未知NTRIP源：设置 `relaxSyncFlag=true`
- 测试旧数据：设置 `relaxSyncFlag=true`

---

## 5. RTK状态向量定义：绝对位置 vs 基线向量

### C版行为
`rtk->x[0..2]` 存储**流动站绝对ECEF坐标**。

初始化（`udpos()`）：
```c
for (i=0;i<3;i++) initx(rtk,rtk->sol.rr[i],VAR_POS,i);
```
`sol.rr[i]` 是SPP得到的绝对位置，直接作为状态向量初始值。

输出（`relpos()` 末尾）：
```c
// float解
rtk->sol.rr[i] = rtk->x[i];       // 绝对位置直接输出
// fix解
rtk->sol.rr[i] = rtk->xa[i];      // 绝对位置直接输出
```

调用 `zdres()` 时：
```c
zdres(0, obs, nu, ..., xp, opt, y, e, azel, freq);
// xp = rtk->x，直接是绝对位置，作为接收机位置传入
```

### Java版行为
`rtk.x[0..2]` 存储**基线向量**（流动站坐标 - 基准站坐标）。

初始化（`udpos()`）：
```java
initx(rtk, rtk.sol.rr[i] - rtk.rb[i], VAR_POS, i);
```
`sol.rr[i]` 是绝对位置，减去 `rb[i]`（基准站位置）得到基线向量。

输出（`relpos()` 末尾）：
```java
// float解
rtk.sol.rr[i] = rtk.x[i] + rtk.rb[i];   // 基线向量 + 基准站位置 = 绝对位置
// fix解
rtk.sol.rr[i] = xa[i] + rtk.rb[i];       // 同上
```

调用 `zdres()` 时：
```java
for (j = 0; j < 3; j++) rr_rover[j] = rtk.rb[j] + xp[j];
zdres(0, obs, nu, nr, ..., rr_rover, opt, y, e, azel, freq);
// xp 是基线向量，需要加上 rb 得到绝对位置再传入
```

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

## 6. 基准站位置处理

### C版行为（后处理 `postpos.c`）

基准站位置在**处理开始前**一次性计算，整个处理过程中保持不变：

1. `execses()` 调用 `antpos(&popt_, 2, &obss, &navs, stas, fopt->stapos)`
2. `antpos()` 根据 `opt->refpos` 选项计算基准站位置，写入 `opt->rb[0..2]`
3. 每个 `rtkpos()` 调用中：
   ```c
   if (opt->refpos <= POSOPT_RINEX && opt->mode != PMODE_SINGLE && opt->mode != PMODE_MOVEB) {
       for (i=0;i<6;i++) rtk->rb[i] = i<3 ? opt->rb[i] : 0.0;
   }
   ```
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
if (opt.refpos != Constants.POSOPT_RTCM && opt.mode != Constants.PMODE_SINGLE &&
        opt.mode != Constants.PMODE_MOVEB) {
    for (i = 0; i < 6; i++) rtk.rb[i] = i < 3 ? opt.rb[i] : 0.0;
}
```

MOVEB 模式下 `RtkCore.rtkpos()` 已实现 SPP 平均获取基准站位置（与C版 `avepos()` 等价）。

`RinexRtkProcessor` 和 `RinexSppProcessor` 在处理 RINEX 文件时，自动从
`APPROX POSITION XYZ` 头部读取基准站位置（仅当 `rtk.rb` 为0时生效，
手动设置 `setBasePosition()` 优先级更高）。

`antpos()` 的分支状态：`POSOPT_SINGLE` 已按 C 版 `rtksvr` 的增量平均逻辑实现
（`RtkProcessor.averageBasePos()`，受 `maxaveep`/`initrst` 控制，非差异项）；
`POSOPT_FILE` 不实现，见 §15.7。

---

## 7. Kalman 滤波矩阵运算

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

## 8. 矩阵存储约定：行优先 vs 列优先

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

## 9. RTK额外优化项（Java版独有）

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

Bug 修复过程见 [RTK_Debug_Record.md](RTK_Debug_Record.md)、[SPP_Debug_Record.md](SPP_Debug_Record.md)、
[ALGORITHM_FIX_LOG.md](ALGORITHM_FIX_LOG.md)；优化项算法细节见 [RTK_Extra_Optimizations.md](RTK_Extra_Optimizations.md)。

> 编号说明：原 §10（Bug修复记录）、§11（额外优化详细说明）为空存根，§12（观测值质量控制）
> 已对齐无差异，三者已删除；§14（PPP关键改正项）、§15.6（SBAS）同属已对齐内容，一并删除。
> 后续编号保持不变以维持其他文档的引用。

---

## 13. RTCM MSM 多信号管理机制差异

### 13.1 槽位容量：NFREQ / NEXOBS

| 项 | C 版 | Java 版 |
|----|------|---------|
| `NFREQ` | 3（[rtklib.h](file:///D:/rtklib/rtklib_java/RTKLIB-2.5.0/src/rtklib.h#L154-L154)） | 6（[Constants.java](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/constants/Constants.java#L186-L186)） |
| `NEXOBS` | 0（[rtklib.h](file:///D:/rtklib/rtklib_java/RTKLIB-2.5.0/src/rtklib.h#L159-L159)） | 2（[Constants.java](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/constants/Constants.java#L192-L192)） |
| 槽位总数/卫星 | 3 | 8 |

C 版无扩展槽：MSM 中未占据主槽的候选信号在 `sigindex()` 里得到 `idx=-1`，存储时被跳过
（即丢弃）。Java 版保留 2 个扩展槽 `NFREQ..NFREQ+NEXOBS-1`，超出部分同样丢弃。

`Obsd` 的 L/P/D/LLI/SNR/code 均按 `NFREQ+NEXOBS` 定长分配（[Obsd.java](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/data/Obsd.java)），
因此 Java 版每颗卫星的观测记录更大（内存量化见技术参考第 11 章）。

### 13.2 码优先级覆盖缺失：`sigindex()` / `setIndex()` 均无 `opt`

C 版两条槽位分配路径都带 `const char *opt`：
- MSM：`sigindex(sys, code, n, opt, idx)`（[rtcm3.c](file:///D:/rtklib/rtklib_java/RTKLIB-2.5.0/src/rtcm3.c#L1957-L1991)）
- RINEX：`set_index(ver, sys, opt, tobs, ind)`（[rinex.c](file:///D:/rtklib/rtklib_java/RTKLIB-2.5.0/src/rinex.c#L966-L1038)）

`opt` 在两处各有两个用途：`getcodepri(sys,code,opt)` 解析 `-CL`/`-GL` 等**码优先级覆盖**；
RINEX 路径额外解析 `-CL%2s=%lf` 形式的**相位偏移**（写入 `ind->shift[]`）。

Java 版两条路径均不带 `opt`，且各自独立、并非共用：
[ObsCode.sigindex(sys, code, n, idx)](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/common/ObsCode.java#L354-L399)（MSM）、
[RinexParser.setIndex(si)](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rinex/RinexParser.java#L440-L479)（RINEX）。
两者都固定查 `CODEPRIS` 默认表（`getcodepri(sys, code)`），
因此**码优先级覆盖与相位偏移选项均不支持**。

除 `opt` 外，分配语义逐条对齐：主槽只放该频率索引的最高优先级码
（`pri_h[]`/`index[]` 裁决，落选者标 `ex[]`），扩展槽按 `NFREQ+nex` 依次分配，
扩展槽用尽则 `idx=-1`。

另一处小差异：Java `sigindex()` 对 `idx[i]<0`（`code2idx` 未识别该码）有 `continue` 保护，
C 版没有——C 会把未知码标 `ex[]` 并占用一个 NEXOBS 槽（可能挤掉合法码），Java 直接留 `-1`。
MSM 信号 id 均为已知值，实际数据不触发。

影响面：仅当调用方显式给出 `-CL`/`-GL` 或相位偏移选项时才与 C 产生槽位差异，
默认配置下两版分配结果一致。

### 13.3 `promoteExtSig()`：Java 版独有的后处理

实现：[Rtcm.promoteExtSig()](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtcm/Rtcm.java#L2377-L2402)
（`private static`，全仓库仅一个调用点），
在 `saveMsmObs()` 存完一颗卫星后调用（[Rtcm.java](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtcm/Rtcm.java#L2362-L2363)）。

**触发场景**：`sigindex()` 对同一频率索引只留最高优先级的码在主槽，落选码进扩展槽
（§13.2）。若该卫星在本历元**没有**高优先级码的观测（`cellmask` 为 0），主槽就空着，
而落选码恰好有观测、落在扩展槽——即"主槽空、扩展槽有同频码"。C 版因 `NEXOBS=0`
根本不存在扩展槽，落选码直接 `idx=-1` 丢弃，因此不会出现该状态。

**逻辑**：遍历主槽 `f=0..NFREQ-1`，若该槽无有效观测（`code[f]==0`，或 `L[f]==0 && P[f]==0`），
就在扩展槽中找 `code[ex]!=0`、`code2idx` 频率索引等于 `f`、且 `L[ex]`/`P[ex]` 非全零的槽，
把 L/P/D/LLI/SNR/code 整体搬到主槽、清空扩展槽（每个主槽只搬第一个匹配，随后 `break`）。
`Lstd`/`Pstd` 不搬——MSM 路径两版都不写这两个字段，无影响。

C 版 `save_msm_obs()` 在 `j++` 循环结束后直接返回，无任何后处理
（[rtcm3.c](file:///D:/rtklib/rtklib_java/RTKLIB-2.5.0/src/rtcm3.c#L2073-L2103)）。

该函数只做**同频率索引**内的搬运，不改写槽位语义（slot f 恒为频率索引 f），
因此不会引入 §16 所述的双站槽位不一致风险。

**内部不一致**：仅 MSM 路径做提升，RINEX 路径（[RinexParser](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rinex/RinexParser.java#L538-L545)）
按 `sigPos` 直接写入、不做提升，与 C 一致。即 Java 的 RTCM 输入与 RINEX 输入在同一场景下
观测集合可能不同，这是两格式间唯一的槽位行为差异。

### 13.4 `save_msm_obs()` 存储差异

存储循环本身一致：`cellmask` 为 0 的单元 `continue` 且不递增 `j`；仅当 `sat!=0 &&
index>=0 && idx[k]>=0` 才写入；`idx[k]==-1`（无槽位）的信号跳过。
差异只在循环结束后：Java 版对每颗卫星追加一次 `promoteExtSig()`（§13.3），C 版没有这一步。

另有一处遗留调试代码：同一位置对 BDS 卫星按 `INFO` 级别逐星打印 `[BDS-OBS] prn=… code=[…]`
（[Rtcm.java](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtcm/Rtcm.java#L2364-L2372)），
C 版无对应输出。量产数据下会产生大量日志，属待清理项。

### 13.5 对定位结果的影响

- 解算只读前 `nf` 个主槽（`PrcOpt.nf` 默认 2，SPP 默认 3），两版前 `min(NFREQ, …)` 个槽位的
  码型选择语义相同（§13.2），默认配置下进入解算的观测集合一致。
- `promoteExtSig` 仅在"主槽缺观测、扩展槽有同频备用码"时生效，属兜底路径，
  正常数据下不改变解算输入。
- 性能/内存量化与逐行说明见
  [RTKLIB_JAVA_TECHNICAL_REFERENCE.md](file:///D:/rtklib/rtklib_java/docs/RTKLIB_JAVA_TECHNICAL_REFERENCE.md) 第 11 章。

---

## 15. 功能边界：Java版未实现功能清单（2026-10-28 更新）

### 15.1 数据流与网络协议

| C版功能 | C版源码 | Java版状态 | 说明 |
|---------|---------|------------|------|
| 串口通信 (STR_SERIAL) | stream.c | ✅ rtklib-stream | `SerialPort`，基于 jSerialComm，跨平台（Win/Lin/Mac） |
| NTRIP客户端 (STR_NTRIPCLI) | stream.c | ✅ rtklib-stream | `NtripClient`：NTRIP 2.0 + SSL/TLS + 自动重连 + Sourcetable 查询；仅 GET 下行，无"向外部 Caster 推送"的上行（无 STR_NTRIPSVR 等价物），`write()` 只用于向 Caster 发 GGA |
| NTRIP服务端/Caster (STR_NTRIPSVR + STR_NTRIPCAS) | stream.c | ✅ rtklib-stream（Java 扩展） | `NtripCaster`：Sourcetable + 挂载点订阅 + Basic 鉴权 + 客户端 GGA 上行；改正数据由应用层 `publish(mountpoint, data)` 注入。C 版无 Caster 实现 |
| TCP服务端/客户端 (STR_TCPSVR/STR_TCPCLI) | stream.c | ❌ 未实现 | `NtripClient` 内部直接用 Socket，无通用 TCP 流抽象 |
| UDP服务端/客户端 | stream.c | ❌ 未实现 | — |
| 内存缓冲区 (STR_MEMBUF) | stream.c | ❌ 未实现 | 实时缓存由 Processor 的 EpochCache 承担（§15.9），不在流层 |
| FTP/HTTP下载 (STR_FTP/STR_HTTP) | stream.c | ⚠️ 不在流层 | rtklib-product 的 `ProductDownloader` 提供 FTP/FTPS/HTTPS 产品下载，非通用流接口 |
| 流服务 (streamsvr.c) | streamsvr.c | ❌ 未实现 | 数据流管理、格式转换服务 |
| RTK服务 (rtksvr.c) | rtksvr.c | ❌ 未实现 | 实时服务由应用层组合 Processor + NtripClient/NtripCaster 自行搭建 |

**Java版数据输入方式**：rtklib-stream 提供 NTRIP 客户端、NTRIP Caster、GGA 解析（`NmeaGgaParser`）和串口，
通过 `StreamListener` 回调取数后调用 `feed()` 注入核心算法；也支持本地文件（RTCM/RINEX）和 byte[] 直接输入。

### 15.2 接收机原始协议

| C版接收机 | C版源码 | Java版状态 | 说明 |
|-----------|---------|------------|------|
| NovAtel OEM4/6/7 | rcv/novatel.c | ❌ 未实现 | 仅定义了STRFMT_OEM4常量 |
| u-blox | rcv/ublox.c | ❌ 未实现 | 仅定义了STRFMT_UBX常量 |
| SwiftNav Piksi | rcv/swiftnav.c | ❌ 未实现 | 仅定义了STRFMT_SBP常量 |
| Hemisphere | rcv/crescent.c | ❌ 未实现 | 仅定义了STRFMT_CRES常量 |
| Septentrio | rcv/septentrio.c | ❌ 未实现 | 仅定义了STRFMT_STQ常量 |
| Javad | rcv/javad.c | ❌ 未实现 | 仅定义了STRFMT_JAVAD常量 |
| NVS | rcv/nvs.c | ❌ 未实现 | 仅定义了STRFMT_NVS常量 |
| BINEX | rcv/binex.c | ❌ 未实现 | 仅定义了STRFMT_BINEX常量 |
| Trimble RT17 | rcv/rt17.c | ❌ 未实现 | 仅定义了STRFMT_RT17常量 |
| Tersus | rcv/tersus.c | ❌ 未实现 | C版有，Java版无常量 |
| Unicore | rcv/unicore.c | ❌ 未实现 | 仅定义了STRFMT_UNICORE常量 |
| SkyTraq | rcv/skytraq.c | ❌ 未实现 | C版有，Java版无常量 |
| ComNav | rcv/comnav.c | ❌ 未实现 | C版有，Java版无常量 |

**Java版数据格式**：仅支持 **RTCM3**（完整解码）和 **RINEX 3.x**（读写）。
接收机原始协议需先用convbin转为RTCM3/RINEX后使用。

### 15.3 解算输出格式

| C版格式 | C版函数 | Java版状态 | 说明 |
|---------|---------|------------|------|
| LLH/XYZ/ENU | outsol() | ✅ 已实现 | 由 `SolOpt.posf`（SOLF_LLH/XYZ/ENU）选择 |
| NMEA 0183 | outnmea_rmc/gga/gsa/gsv() | ❌ 未实现 | 仅有常量 `SOLF_NMEA`；rtklib-stream 的 `NmeaGga`/`NmeaGgaParser` 只做 GGA 上行**解析**，不含 NMEA 输出编码 |
| Solution Status | outsols() | ❌ 未实现 | 仅有常量 `SOLF_STAT` |
| GSI F1/F2 | outsols() | ❌ 未实现 | 仅有常量 `SOLF_GSIF` |

### 15.4 坐标转换与格式

| C版功能 | C版源码 | Java版状态 | 说明 |
|---------|---------|------------|------|
| GPX输出 | convgpx.c | ❌ 未实现 | Google Earth格式 |
| KML输出 | convkml.c | ❌ 未实现 | Google Earth格式 |
| 大地水准面模型 | geoid.c | ❌ 未实现 | EGM96等大地水准面改正 |
| 基准转换 | datum.c | ❌ 未实现 | ITRF间坐标转换 |
| TLE星历 | tle.c | ✅ 已实现 | `TleParser`+`Sgp4Propagator`，仅用于可见性预测（TEME坐标系，不实现帧转换） |
| GIS功能 | gis.c | ❌ 未实现 | Shapefile等 |
| IONEX电离层 | ionex.c | ✅ 已实现 | `IonexReader`，IGS IONEX v1.0/v2.0读取+双线性插值 |
| 下载功能 | download.c | ✅ 已实现 | `ProductDownloader`（rtklib-product模块），FTP/FTPS/HTTPS多协议多镜像 |

已实现的 TLE/IONEX/download 为 Java 版自带能力，与 C 同名模块不完全等价
（如 TLE 仅用于可见性预测）；GPX/KML/大地水准面/基准转换/GIS 为明确的未实现项。

### 15.5 定位模式（未完整对齐项）

| C版模式 | 常量 | Java版状态 | 说明 |
|---------|------|------------|------|
| DGPS差分 | PMODE_DGPS | ⚠️ 部分 | RTK框架内，modear=OFF时等效 |
| Moving-Base | PMODE_MOVEB | ⚠️ 部分 | 常量已定义，核心逻辑未完整验证 |

其余定位模式（SPP/Kinematic/Static/Static-Start/Fixed/PPP-Kinematic/PPP-Static/PPP-Fixed）均已完整实现，详见 [RTKLIB_JAVA_TECHNICAL_REFERENCE.md](RTKLIB_JAVA_TECHNICAL_REFERENCE.md) 第18章。

### 15.7 算法细节未对齐项

| C版功能 | C版源码 | Java版状态 | 说明 |
|---------|---------|------------|------|
| POSOPT_FILE | `antpos()` | — 不实现 | 有意取舍。用户读取位置文件后自行赋 `opt.rb` 即可，无需内置 |
| Static Start 长延迟回退 | `rtkpos()` 中 `fabs(tt)>300` | ❌ 未实现 | 卫星数据中断超 5 分钟时，C 版把 `opt.mode` 退回 `PMODE_STATIC_START` 并重置位置/速度/加速度方差，等待重新收敛；Java 无此回退（也未保存 `initial_mode`），长中断后继续用旧模式 |

RINEX 观测槽位分配已对齐 C `set_index`（FIX-ALG-004），但同一批 PPC 数据集 Fix 合计
4250 vs C 3784（+12%），差异落在 AR 决策层，尚未逐项定位；`ar_poly_coeffs` 多项式门限
本身已按 C 移植（`RtkCore.AR_POLY_COEFFS`）。

已对齐项（SPP实时均值平滑、SSR相位偏差改正、合并速度smoother、udtrop()冻结）详见 [RTKLIB_JAVA_TECHNICAL_REFERENCE.md](RTKLIB_JAVA_TECHNICAL_REFERENCE.md) 第18章。

### 15.8 功能边界总结

**定位**：GNSS 定位算法引擎（SPP/RTK/PPP/PPP-AR/PPP-RTK + 网络 RTK 解算），
不含 C 版的 GUI、接收机原始协议、NMEA/GPX/KML 等输出格式与通用流服务框架。

**已实现**：
- RTCM3 解码（MSM4/5/6、多系统星历、SSR）
- RINEX 3.x 读写、IONEX 读取、TLE/SGP4（可见性预测）
- SPP/RTK/PPP 解算（正向/反向/双向滤波）、LAMBDA、PPP-AR
- 天线 PCV/DCB/海潮/固体潮/极潮改正
- 实时流：NTRIP 客户端、NTRIP Caster（Java 扩展）、串口、GGA 解析、双向缓存（§15.9）
- 模块扩展：rtklib-networkvrs（网络 RTK）、rtklib-joint（联合 RTK）、rtklib-adjust（网平差）、rtklib-otl（海潮）

**明确不做**（项目定位，非缺口）：
- TCP/UDP 通用流、streamsvr/rtksvr 服务框架
- 接收机原始协议（NovAtel/u-blox/…），需 convbin 转 RTCM3/RINEX
- NMEA/GSI/GPX/KML 输出、大地水准面/基准转换
- 实时流管理 → 由应用层组合 Processor 与 stream 模块自行搭建

### 15.9 实时流双向缓存系统（Java版新增，C版无对应）

C版RTKLIB的反向滤波仅支持事后批处理（读完文件→正向→反向→合并→输出），
Java版新增实时流场景下的双向缓存能力。

**适用范围**：

| 场景 | SPP | RTK | PPP |
|------|:---:|:---:|:---:|
| 实时正向 | ✅ | ✅ | ✅ |
| 批处理双向（process方法） | ❌ | ✅ | ✅ |
| 实时双向（缓存触发） | ❌ | ✅ | ❌ |
| 事后双向（soltype配置） | — | ✅ | ✅ |

SPP为绝对定位，每历元独立求解，双向无意义。RTK/PPP批处理双向通过`cacheMaxEpochs>0`触发（正向成功历元>10时自动执行反向→合并）。RTK实时双向通过缓存满触发；RTK/PPP事后双向通过`PostPosProcessor`+`soltype`配置（与C版对齐）。

**设计要点**：
- `PrcOpt.cacheMaxEpochs`：0=纯正向(默认)，>0=缓存满触发反向的批次大小
- `feed(sourceId, data)`：带数据源标识的投喂接口，sourceId由调用方定义（站ID/设备序列号/NTRIP mountpoint等）
- 缓存满自动触发：反向处理→CombinedFilter合并→通过handler.onResult()输出→清空缓存
- 两种缓存实现：InMemoryEpochCache（环形缓冲区）、ExternalEpochCache（对接Redis/DB/文件）
- 向后兼容：feed(data)等价于feed(null, data)，cacheMaxEpochs=0时行为与原版一致

**与C版对比**：

| 特性 | C版 | Java版 |
|------|-----|--------|
| 反向滤波 | 仅事后（文件边界） | 事后+实时（缓存边界） |
| 批次边界 | 文件长度 | cacheMaxEpochs |
| 数据源标识 | 无（文件名隐含） | sourceId（调用方注入） |
| 缓存方式 | 无（全量内存） | 内存环形缓冲/外部接口 |
| 触发方式 | 自动（文件读完） | 缓存满自动/手动reprocess() |

### 15.10 solstatic 静态单解输出（Java版扩展至实时流）

C版RTKLIB的 `solstatic` 仅在 `PostPosProcessor`（事后批处理）中实现：
`sopt.solstatic=1` + `mode=PMODE_STATIC/PMODE_PPP_STATIC` 时，所有历元参与滤波但只输出1个最优解。
C版在 `procpos()`（单方向）和 `combres()`（双向合并）两个函数中分别独立实现 solstatic 逻辑，
合并时使用更高的 FIX 优先级（`pri[]={7,1,2,3,4,5,1,6}` vs 单方向 `{6,...}`）。

Java版将此能力扩展到实时流处理器，并新增窗口模式：

| 特性 | C版（PostPosProcessor） | Java版（RtkProcessor/PppProcessor） |
|------|------------------------|--------------------------------------|
| solstatic 支持 | ✅ 事后批处理 | ✅ 实时流+批处理 |
| 输出时机 | 文件处理完 | finish()时 或 窗口满时 |
| 窗口模式 | ❌ 无 | ✅ `solStaticWindow>0` 时每N历元输出1个 |
| 滤波中断 | 不适用（批处理） | ❌ 窗口输出后不重置滤波器 |
| bestSol选择 | pri[]优先级+最早时间 | 同C版，SOL_PRIO/COMBINED_SOL_PRIO |
| 双向合并+solstatic | ✅ combres()内独立实现 | ✅ processBatchCombined()内独立实现 |
| PPP实时流 | ❌ 不存在 | ✅ PppProcessor支持solstatic |

**新增字段**：
- `SolOpt.solStaticWindow`：0=finish时输出（与C版一致），>0=每N个历元输出1个bestSol

**新增构造函数**：
- `RtkProcessor(PrcOpt, SolOpt, PosHandler, OutputStream)`
- `PppProcessor(PrcOpt, SolOpt, PosHandler, OutputStream)`

**向后兼容**：原有构造函数委托到新构造函数（`SolOpt=null`），`solstatic=false`，行为不变。

---

## 16. 观测槽位语义与逐星 IFLC 频率对协商

### 16.1 槽位语义：恒等于频率索引，不压缩不重排

C 版 `rinex.c set_index()`（L966-1038，RINEX 路径）与 `rtcm3.c sigindex()`（MSM 路径）：
slot 0..NFREQ-1 只放该频率索引的**最高优先级**观测码（`code2idx`+`getcodepri` 裁决），
其余进扩展槽 NFREQ+i；**从不按数据空缺重排槽位**。某站缺失某频率时，下游靠
`cp==0.0||pr==0.0||freq<=0.0` 检查**整星跳过**。slot f 的含义恒为"频率索引 f"，
这是双站 IFLC/DD 配对的共同约定。

BDS 频率映射（`code2idx`/`code2freq_BDS`，Java 与 C 一致）：

| 频率字符 | 频段 | slot |
|---------|------|------|
| '2' | B1I 1561.098 | 0 |
| '7' | B2 1207.14（BDS-2 B2I 与 BDS-3 B2b 同频段） | 1 |
| '5' | B2a 1176.45 | 2 |
| '6' | B3I 1268.52 | 3 |
| '1' | B1C 1575.42 | 4 |
| '8' | B2ab | 5 |

**已移除的反例**：Java 曾实现 `compactObsFreq()`，把有数据的槽位前移压缩，试图救回缺第二频率
的 BDS-3 卫星。结果流动站 C5P 从 slot2 挪进 slot1、基准站 slot1 仍是 C7D → 同槽不同频 →
IFLC SD `cp2` 混合 B2a/B2b → bias≈1e6 m → udbias 毒化全部模糊度 → Fix 率 0%。该调用已移除
（方法本体保留供测试引用）。修复与数据见 FIX-ALG-002。

> 编号说明：原 §16.2/§16.3 已并入本节；§16.4~§16.6 编号保持不变，以维持其他文档的引用。
> 原 §16.7（satid2no 空格填充）、§16.8（RINEX 3 槽位分配）均已对齐 C（FIX-ALG-003/004），
> 内容删除，编号不再使用；其中残余的 Fix 数差异记入 §15.7。

### 16.4 为什么 C 不"动态找该站实际有的第二频率"

1. **跨站一致性**：DD 要求两站对 slot f 的含义有共同约定。固定映射在构造上保证一致；
   任何"按本站数据重排/改选"的逻辑（compaction 或动态选对）让约定退化为"按各自数据决定"，
   两站独立执行时必然分叉——compaction 的 1e6 m bias 即实证。
2. **模糊度记账**：udbias 的 IFLC bias 公式（λ、γ 频率比）按"每系统一个固定频率对"设计；
   `IB(sat,f,opt)` 的 slot 寻址体系无法表达"同一 slot 承载不同频率对的模糊度"。
   要支持需重设计状态索引，这是连 demo5 都没有的扩展。

### 16.5 修复结果与后续方向

移除调用后，IFLC bias 从 1e6 m 垃圾值回到仅真实周跳的 130-170 m，120 m 位置尖峰消失，
ddres 零虚假拒绝。若要让 B1I+B2a 卫星参与 IFLC，正确路径不是压缩槽位而是显式按频率选对，
已作为 §16.6 实现。

### 16.6 逐卫星 IFLC 频率对协商（v2.3.0 JAVA-ONLY 扩展，默认关闭）

§16.4 指出 C 版"每系统一个固定频率对"的两个约束（跨站一致性、模糊度记账）。
本扩展在**保留槽位语义**（槽位 = `code2idx` 固定频段映射，不压缩不重排）的前提下，
把"每系统一对"放宽为"每卫星一对"，由**两站公共频段协商 + sticky 保持**同时满足两个约束：
两站共用同一协商结果（跨站一致），配对切换按周跳重置模糊度（记账正确）。

**开关**：`RtkConfig.enableIflcPairNegotiation`（默认 `false`）。关闭时全部路径 no-op，
与 RTKLIB C（demo5 EX 2.5.0）逐位一致；仅 `IONOOPT_IFLC` + RTK 双站模式（`mode > PMODE_DGPS` 且 `nr > 0`）生效。

**协商算法**（`RtkCore.negotiateIflcPairs`，每历元 `rtkpos()` 中 `zdres(base)` 之前调用）：

- 对两站公共卫星（obs 按 (接收端， 卫星号) 排序，两指针归并遍历）执行单星协商；
- **优先级表**（槽位对，首槽位恒为 0 以保持 ddres `code[frq=0]` 频率检查语义）：
  GPS `{0,1}>{0,2}`；GLO `{0,1}`；GAL nf≥3 `{0,2}>{0,1}`（对齐 `seliflc` 语义）、nf=2 `{0,1}>{0,2}`；
  BDS `B1I+B2{0,1} > B1I+B3I{0,3} > B1I+B2a{0,2}`；QZS `{0,1}>{0,2}`；IRN `{0,1}`；
- **sticky**：上一配对仍"结构可用"（两站该槽位 code 非 0，即频段被跟踪）则保持不变，
  不因更高优先级对出现而切换，避免数据级空洞（L/P=0 单历元缺失）导致配对来回抖动；
- **失效重选**：按优先级表从两站公共频段中重选；**无公共对**则 `iflcSlot` 清零，
  回退固定槽位 `{0, seliflc()}`（该星因数据缺失自然被 IFLC 跳过）；
- **切换/消失按周跳处理**：置 `Ssat.iflcPairSwitch`，`udbias()` 消费后强制重置该星
  模糊度并清 lock，随后清除标志。注意不能复用 `slip` 位域传递——`udbias()` 开头
  `slip &= 0xFC` 会清掉 bit0（本端口 `LLI_SLIP=0x01`），故用独立布尔标志。

**存储**：`Ssat.iflcSlot[2]`（协商槽位对，`{0,0}`=未协商）+ `Ssat.iflcPairSwitch`（切换标志）。
按槽位对而非 code 枚举存储：槽位=频段（`code2idx` 固定映射保证），同频段不同码变体
（如 C7D/C7I）等价可用，与 C 版按槽位配对行为一致。

**读取点改造**（4 处，未协商/回退时全部走原固定槽位路径）：

| 读取点 | 固定槽位（C 对齐） | 协商模式 |
|--------|-------------------|----------|
| `zdres()` IFLC 分支 | `{0, seliflc(nf,sys)}` | `iflcSlots()` 协商槽位对 |
| `udbias()` IFLC bias | `slip[f2]`、cp/pr 按固定对 | 协商对 + `iflcPairSwitch` 置 `LLI_SLIP` |
| `detslpGf()` | `gfobs(obs,i,j,f)` | `gfobsPair(obs,i,j,s1,s2)`，slip 写 `slip[0]|slip[s1]|slip[s2]` |
| `detslpLl()`/`detslpCode()` | 遍历 `f < nf` | maxF 扩展到协商槽位上界 |

`varerr()` 无需改动：C 版 IFLC 处理为固定 `var *= SQR(3.0)`（rtkpos.c L444），无波长项，
对任意频率对通用。B1I+B2a 噪声因子 ≈2.66、B1I+B2 ≈2.90，×9 假设偏保守（方差略大），可接受。

**验证**：

- 单元测试 `IflcPairNegotiationTest`（8 项）：公共对协商、sticky 保持、失效重选+切换标志、
  无公共对清零回退、开关关 no-op、DGPS/nr=0 no-op、归并遍历仅公共卫星、GAL nf 优先级；
- no-op 回归（开关关闭）：`RinexRtkProcessorTest` 8 run/0 fail/4 skip 与基线一致；
  `testPpcTokyoRun1` H.RMSE=11.629 m / V.RMSE=15.949 m / Float=1825 历元逐位一致；
- 端到端（开关开启，PPC-Dataset Tokyo run1）：BDS-3 卫星以 B1I+B2a 协商对参与 RTK，
  平均可用卫星数增加，无 bias 爆炸（详见 `UrbanCanyonRtkDiagTest#testPpcTokyoRun1IflcNegotiation`）。

## 17. PPP 精密模式 ISB 钟差初始化——enablePppIsbClockInit（v2.3.1 JAVA-ONLY 扩展，默认关闭）

### 17.1 C 版行为（RTKLIB-2.5.0/src/ppp.c L612-631 udclk_ppp）

```c
for (i=0;i<NSYS;i++) {
    if (rtk->opt.sateph==EPHOPT_PREC) {
        /* time of prec ephemeris is based gpst */
        /* neglect receiver inter-system bias  */
        dtr=rtk->sol.dtr[0];
    }
    else {
        dtr=i==0?rtk->sol.dtr[0]:rtk->sol.dtr[0]+rtk->sol.dtr[i];
    }
    initx(rtk,CLIGHT*dtr,VAR_CLK,IC(i,&rtk->opt));
}
```

C 为双分支设计：精密星历模式忽略接收机系统间偏差（全部系统用 `dtr[0]` 初始化），
广播星历模式用各系统 ISB（`dtr[0]+dtr[i]`，`sol.dtr[i]` 由 SPP 输出，见 pntpos.c
L452-459：dtr[1]=GLO-GPS、dtr[2]=GAL-GPS 等）。

### 17.2 C 版行为的实际后果（有意偏离的动机）

C 的精密分支假设"精密钟差统一 GPST 尺、接收机 ISB 可忽略"，但实测 GLO-GPS 接收机
ISB 达 μs 级（数百米）。`initx` 每历元重置钟差（白噪声模型）导致初值偏差无法收敛：
GLO/GAL 残差（约 -750/-800 m）持续超 maxinno 被剔除——**C 版精密模式 PPP 实际上仅
GPS 参与解算**，多系统能量被静默丢弃。

### 17.3 Java 版行为

- 默认（`enablePppIsbClockInit=false`）：恢复 C 双分支逐值语义（精密模式忽略 ISB）。
- 开启（`=true`）：精密模式也采用 ISB 初始化（广播分支语义），GLO 钟差初值偏差从
  约 790 m 降至约 49 m，GLO 观测参与解算（nv 28→42，残差 -750 m→约 50 m）。

### 17.4 适用限制

Galileo 的 ISB 依赖 SPP 联合解算；当广播星历缺失该系统（如 BRDC 无 GAL）时
`dtr[i]≈0`，开关对该系统无效，需从其他途径获得先验（超出本扩展范围）。
相关修复记录：ALGORITHM_FIX_LOG.md FIX-ALG-010（SPP 多系统恢复，C 对齐）与
FIX-ALG-011（udclk_ppp 双分支 + 开关）。

## 18. EST 模式首历元死锁与 DCB 产品加载

### 18.1 C 版行为（固有缺陷）

非组合 PPP（`IONOOPT_EST`）首历元，各卫星电离层状态由双频伪距差初始化
（`udiono()`），相位偏差状态由 `MW` 组合初始化（`udbias()`）。此时相位 prefit 残差含
未建模的接收机/卫星码偏差（`dcb` 项），典型量级 L1 +17.8 m、L2 -7 m，远超
`maxinno[0]=5.0` 门限（`prcopt_t.maxinno={5,30}`）——相位行全部被 prefit 剔除，仅码行
进入 EKF。码行观测方程不含 bias 状态（H 为 0），bias/电离层状态协方差永不收缩、
状态值永不更新，下一历元相位残差依旧超限 → **永久死锁，全历元 SOLQ_NONE**。

C 2.5.0 通过 `postpos.c` L1092-1108 规避：处理前先把 `navs.cbias` 全置 -1（FIXME 注释
自认 quirk），扫描输入文件找 `.BIA/.BSX`（`readdcb()`），失败再用 `fopt->dcb` 指定文件。
加载码偏差产品后相位 prefit 残差降至门限内，死锁不触发。若两种来源都没有，C 版同样
死锁（且 `corr_meas` 还会应用 -1 m 的残留哨兵值——哨兵未被清理的二次缺陷）。

### 18.2 Java 版行为

- `DcbReader.readdcb(file, nav, stas)` 完整移植 C `preceph.c readdcb()`
  （L514-544）：入口清零 `nav.cbias`（对齐 L523-525），按扩展名分发
  `.BIA/.BSX → readbiaf`（OSB/DSB Bias-SINEX，L449-502）、`.DCB → readdcbf`
  （P1-C1/P2-C2 传统格式，L378-417），值统一 ×1E-9×CLIGHT（ns→m）。
  GAL 频率映射对齐 C：L1→freq0、L5→freq1（E5b 记录 C 版同样丢弃）。
  码偏差经 `corr_meas`/`prange` 应用（`SppCore.code2biasIx`，对齐 C code2bias_ix）。
  RINEX 路径经 `PostPosProcessor.setDcbPath()`/`RinexPppProcessor.setDcbPath()` 接线，
  与 C postpos 的 `fopt->dcb` 等价（C 版扫描输入文件目录的分支 Java 不做，显式传路径）。
- 与 C 的差异（有意）：`readdcb` 失败（无文件/无记录）时 Java 侧 `cbias` 保持 0
  （无偏移），不复制 C 的 -1 哨兵残留缺陷。
- `RtkConfig.enableIonoEstRobust`（**JAVA-ONLY**，默认 false）：无 DCB 产品时的兜底
  开关。开启后 EST 模式对本历元刚 `initx` 的 bias/ion 状态（协方差恰为
  `VAR_BIAS`/`VAR_IONO`，即尚未被任何 KF 更新吸收，覆盖首历元与周跳后重置两种情形）
  跳过相位行的 prefit maxinno 剔除，让 EKF 一次性吸收常数偏差；状态被估计一历元后
  协方差偏离初值，自动恢复正常剔除。仅放宽相位行（code==0），码行剔除与 C 一致。
  开关关闭时判定条件恒 false，与 C 行为逐值一致。

### 18.3 验证

见 ALGORITHM_FIX_LOG.md FIX-ALG-020。正确解法是配置 DCB 产品（开关关闭）；
`enableIonoEstRobust` 仅作无产品的退化兜底，其假设（首历元偏差可被 EKF 吸收）
在长观测弧、多卫星场景成立，单星/短弧场景慎用。

### 18.4 仍存在的差异

超时重置链（`udbias()` 的 bias 重置、`udiono()` 的重初始化）已对齐 C，见 FIX-ALG-021，
不再列为差异。余下两项：

- C 的 `clk_jump`（日边界钟跳，`posopt[5]`+`time2gpst`）无 Java 对应机制，需日边界
  处理链；日边界数据存在已知差异。
- MW/GF 周跳检测的历史清理为 JAVA-ONLY 扩展，C 无对应实现。