# Java版与RTKLIB C版实现差异说明

本文档记录Java版RTKLIB与C版RTKLIB（2.5.0）在实现上的有意差异。
这些差异是经过SPP测试验证的必要修改，不应随意回退。

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

### 测试中的处理

`RtkRinexCompareTest` 中使用硬编码的C版本SPP平均坐标：
`java
double[] cRefBasePos = new double[]{-493099.3671, 5551412.6391, 3092558.0152};
System.arraycopy(cRefBasePos, 0, rtk.rb, 0, 3);
System.arraycopy(cRefBasePos, 0, rtk.opt.rb, 0, 3);
`

这个值来自C版本 `rnx2rtkp` 输出文件头中的 `ref pos` 行。
C版本使用 `POSOPT_SINGLE`（SPP平均所有历元）计算得到。

### 待完善

Java版需要实现 `antpos()` / `avepos()` 的等价功能，支持从RINEX头或
SPP平均自动获取基准站位置，避免硬编码。

---

## 10. 对流层映射函数差异（待修复）

### C版行为
`zdres()` 中使用 NMF（Niell Mapping Function）：
`c
zhd = tropmodel(obs[0].time, pos, zazel, 0.0);     // 天顶静力学延迟
mapfh = tropmapf(obs[i].time, pos, azel+i*2, NULL);  // NMF映射函数
r += mapfh * zhd;
`

`tropmapf()` -> `nmf()` 调用链包含：
- 纬度内插 `interpc()`：按15度间隔对NMF系数表做线性内插
- 季节修正：`y = (time2doy(time) - 28.0) / 365.25 + (lat < 0 ? 0.5 : 0)`
- 连分式映射 `mapf()`：`(1+a/(1+b/(1+c))) / (sinel+a/(sinel+b/(sinel+c)))`
- 高度修正 `dm`：`(1/sin(el) - mapf(el, aht)) * hgt / 1000`

### Java版行为
`zdres()` 中使用简化映射函数：
`java
double zhd = TroposphereModel.saastamoinen(pos, zazel, 0.0, 293.15);
double mapfh = 1.0 / (Math.sin(el) + 1E-10);   // 简化近似
r += mapfh * zhd;
`

### 数值差异
以 zhd 约 2.3m 为例：

| 高度角 | 1/sin(el) | NMF(近似) | 映射函数差 | 延迟误差 |
|--------|-----------|-----------|-----------|---------|
| 10度 | 5.76 | ~5.5 | ~0.26 | ~0.6m |
| 15度 | 3.86 | ~3.7 | ~0.16 | ~0.37m |
| 20度 | 2.92 | ~2.8 | ~0.12 | ~0.28m |
| 30度 | 2.00 | ~1.95 | ~0.05 | ~0.12m |

### 修复方案
1. `TimeSystem.java`：添加 `time2doy()` 函数
2. `TroposphereModel.java`：添加 `interpc()`、`mapfNmf()`、`nmf()`、`tropmapf()`
3. `RtkCore.java`：将 `1.0 / (Math.sin(el) + 1E-10)` 替换为 `TroposphereModel.tropmapf()`

### 当前状态
NMF函数已实现，但实测映射函数差异（0.01~0.06）不足以解释14m偏差。
14m偏差的根本原因仍在排查中。
---
---

## 14. rtk.sol.time未更新导致Kalman滤波器冻结（已修复）

### 问题描述

C版 rtkpos.c 第2372行：当P[0]!=0时（非首次历元），需要更新 rtk->sol.time 为当前观测时间：
`c
} else rtk->sol.time=obs[0].time;
`

Java版缺失此 else 分支，导致 rtk.sol.time 只在首次SPP时设置，之后永远不变。

### 影响链

1. rtk.sol.time 不更新 -> rtk.tt = timediff(rtk.sol.time, prevTime) 永远为 0
2. tt=0 -> udpos() 中状态转移矩阵 F 的速度项 F[i*(i+3)] = tt = 0，位置不传播
3. tt=0 -> 过程噪声 Q = prn^2 * |tt| = 0，协方差矩阵 P 不增长
4. tt=0 -> udbias() 中模糊度过程噪声也为 0
5. 结果：Kalman滤波器完全冻结，每个历元都是原地踏步

### 证据

Java输出文件中240行数据全部是同一个时间戳（第1个历元的时间），而C版每个历元时间都不同。

### 修复

在 RtkCore.java 中添加 else 分支：
`java
} else {
    rtk.sol.time = obs[0].time;
}
`

这很可能是14m偏差的根本原因。Kalman滤波器冻结后，模糊度无法收敛，定位结果停留在SPP精度水平。


## 15. varerr\(\) 缺少 SNR 和接收机噪声项（已修复）

C版 rtkpos.c:L401-L446 的 varerr\(\) 包含两个 Java 版缺失的噪声项。

注意：默认配置下 err[6]=0.0 和 err[7]=0.0，因此此 Bug 在默认配置下不影响结果。
但如果用户设置了这些参数，Java 版将忽略 SNR 和接收机噪声。

### 修复

- 修改 varerr\(\) 签名，添加 snr_rover, snr_base, obs 参数
- 添加 SNR 调整项和接收机标准差项
- 更新 ddres\(\) 中的调用点，传入 rtk.ssat[].snrRover/snrBase 和 obs

## 16. varerr\(\) 星座因子硬编码且 IRNSS 错误（已修复）

Java 版 varerr\(\) 的星座因子使用硬编码值，且 IRNSS 使用 1.0 而非 C 版的 1.5。
同时缺少 SYS_GPS 和 SYS_SBS 分支。

### 修复

使用 Constants.EFACT_* 常量替代硬编码值，添加所有星座分支。

## 17. 对流层映射函数

Java 版已实现 NMF 映射函数，通过 TroposphereModel.tropmapf 调用。
与 C 版 rtkpos.c 中的 tropmapf 等价。此差异已不存在。