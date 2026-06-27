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