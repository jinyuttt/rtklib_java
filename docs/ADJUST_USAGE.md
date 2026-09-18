# rtklib-adjust 模块使用说明

## 概述

单历元GNSS多基线间接平差模块。基于高斯-马尔可夫最小二乘模型，对同一历元多条独立RTK基线进行融合平差，输出流动站P01最优坐标及精度评定。

## 依赖

- rtklib-core（RTK解算核心）
- EJML 0.41（Apache 2.0，矩阵运算）

## 快速使用

```java
// 1. 从rtklib-core获取各基线SolData（RTK解算结果）
SolData solA = ...; // 基线A的解算结果
SolData solB = ...; // 基线B的解算结果
SolData solC = ...; // 基线C的解算结果

// 2. 构建BaselineEntry
BaselineEpoch.BaselineEntry entryA = new BaselineEpoch.BaselineEntry(
    "A", solA,
    CovAssembler.extractBaselineDxyz(solA),       // ECEF基线增量
    CovAssembler.extractCovariance3x3(solA),       // 基线协方差
    CovAssembler.extractCovariance3x3(solA)        // Rover位置协方差
);

// 3. 构建BaselineEpoch（自动剔除非FIX基线）
BaselineEpoch epoch = new BaselineEpoch(
    "2026-01-01 10:00:00",
    new BaselineEpoch.BaselineEntry[]{entryA, entryB, entryC}
);

// 4. 执行平差
AdjustResult result = GnssBaselineAdjust.adjust(epoch);

// 5. 获取结果
if (result.success) {
    double p01X = result.p01Xyz[0];  // 平差后P01 ECEF X (m)
    double p01Y = result.p01Xyz[1];  // 平差后P01 ECEF Y (m)
    double p01Z = result.p01Xyz[2];  // 平差后P01 ECEF Z (m)
    double sigma0 = result.sigma0;    // 单位权中误差
    double[] baardaT = result.baardaT; // Baarda数据探测T统计量
}
```

## 输出说明

| 字段 | 含义 | 单位 |
|------|------|------|
| `p01Xyz` | 平差后P01 ECEF坐标 | m |
| `dx` | 参数解（即P01坐标估计） | m |
| `Dx` | 参数协方差矩阵 3×3 | m² |
| `v` | 残差向量 | m |
| `sigma0` | 单位权中误差 | 无量纲 |
| `dof` | 自由度 df=3k-3 | - |
| `baardaT` | Baarda数据探测T统计量 | - |
| `Qv` | 残差协因数矩阵 | m² |

## 特殊情况

- **k=1**（单基线）：df=0，无法精度评定，sigma0=NaN，不计算Baarda
- **k=0**（无有效基线）：返回失败结果
- **非FIX基线**：构建BaselineEpoch时自动剔除

---

## 基站异常诊断

### 概述

当多基线平差σ₀持续超限时，说明基线间存在系统性不一致，最常见原因是基站坐标错误。`BaseStationDiagnoser` 可自动检测异常基站并给出建议坐标。

### 诊断策略

| 基线数 | 定位异常基站方法 | 给建议坐标方法 |
|--------|----------------|---------------|
| k=1 | 无法诊断 | 无法诊断 |
| k=2 | SPP定位各基站，偏差最大的为异常 | 以SPP偏差小的为参考，静态解算异常基站 |
| k≥3 | 残差分析（残差最大的基线为异常） | 以残差小的基线为参考，静态解算异常基站 |

### 快速使用

```java
// 1. 构建数据源（支持RTCM文件、RINEX文件、内存RTCM）
GnssDataSource baseA = new RtcmFileDataSource("A", "baseA_0.rtcm3", "baseA_1.rtcm3");
GnssDataSource baseB = new RtcmFileDataSource("B", "baseB_0.rtcm3", "baseB_1.rtcm3");
GnssDataSource rover  = new RtcmFileDataSource("R1", "rover_0.rtcm3", "rover_1.rtcm3");

// 2. 配置
AdjustDiagnosisConfig config = AdjustDiagnosisConfig.builder()
    .enable(true)
    .sigma0Threshold(3.0)              // σ₀超过3.0触发诊断
    .diagnoseWindow(100)               // 滑动窗口100个历元
    .diagnoseRatio(0.8)                // 窗口内80%历元超限才触发
    .useSppForDirection(true)          // 2基线时用SPP判断方向
    .useStaticForCorrection(true)      // 静态解算给建议坐标
    .minStaticDataHours(2.0)           // 静态解算最少2小时数据
    .cacheDiagnosis(true)              // 缓存结果，避免重复计算
    .build();

// 3. 创建诊断器
BaseStationDiagnoser diagnoser = new BaseStationDiagnoser(config);
diagnoser.addBaseStation(baseA);
diagnoser.addBaseStation(baseB);
diagnoser.setRover(rover);

// 4. 设置回调（诊断结果只推送一次）
diagnoser.setHandler(diagnosis -> {
    System.out.println(diagnosis);
    // 可在此推送消息、记录日志、修正坐标等
});

// 5. 执行诊断
PrcOpt rtkOpt = RtkProcessor.createDefaultOpt();
List<BaseStationDiagnosis> results = diagnoser.diagnose(rtkOpt);
```

### 实时场景（逐历元feed）

```java
// 内存数据源
RtcmMemoryDataSource baseA = new RtcmMemoryDataSource("A");
RtcmMemoryDataSource baseB = new RtcmMemoryDataSource("B");
RtcmMemoryDataSource rover  = new RtcmMemoryDataSource("R1");

// 实时收到RTCM数据时
baseA.feed(rtcmBytesA);
baseB.feed(rtcmBytesB);
rover.feed(rtcmBytesRover);

// 每个历元平差后feed给诊断器
AdjustResult result = GnssBaselineAdjust.adjust(epoch);
diagnoser.feedAdjustResult(result);  // 内部累积σ₀统计，超限时自动触发深度诊断
```

### RINEX文件场景

```java
GnssDataSource baseA = new RinexFileDataSource("A", "baseA.obs", "baseA.nav");
GnssDataSource rover  = new RinexFileDataSource("R1", "rover.obs", "rover.nav");
```

### 缓存管理

```java
// 调用方修正基站坐标后，清除缓存，后续可重新验证
diagnoser.clearDiagnosis("A");

// 清除所有缓存
diagnoser.clearAllDiagnosis();

// 查询缓存
boolean diagnosed = diagnoser.isDiagnosed("A");
BaseStationDiagnosis cached = diagnoser.getCachedDiagnosis("A");
```

### 诊断结果说明

| 字段 | 含义 |
|------|------|
| `baseId` | 基站标识 |
| `anomalyLevel` | 异常等级：NORMAL/WARNING/ERROR/CRITICAL/UNKNOWN |
| `rtcm1005Xyz` | RTCM 1005中的基站ECEF坐标 |
| `suggestedXyz` | 建议的ECEF坐标（静态解算结果） |
| `suggestedLlh` | 建议的LLH坐标 |
| `deviationNeu` | RTCM1005与建议坐标的NEU偏差(m) |
| `deviation3d` | 3D偏差(m) |
| `diagnosisMethod` | 诊断方法描述 |
| `meanSigma0` | 触发诊断时的平均σ₀ |
| `staticFixRate` | 静态解算FIX率 |
| `staticStdEcef` | 静态解算坐标标准差(m) |
| `suggestion` | 建议文本 |

### 异常等级判定

| 3D偏差 | 等级 | 说明 |
|--------|------|------|
| <0.5m | NORMAL | 基站坐标正常 |
| 0.5~2m | WARNING | 轻微偏差，建议修正 |
| 2~5m | ERROR | 明显异常，强烈建议修正 |
| >5m | CRITICAL | 严重异常，必须修正 |

### σ₀阈值说明

σ₀的理论临界值（α=0.05）：

| 基线数k | 自由度df | σ₀临界值 |
|---------|---------|----------|
| 2 | 3 | 1.61 |
| 3 | 6 | 1.45 |
| 4 | 9 | 1.37 |

默认阈值3.0是工程保守值，避免对协方差模型近似误差误报警。实际中σ₀>10通常表示基线间明显不一致，σ₀>100表示基站坐标严重错误。

### 数据源接口

`GnssDataSource` 统一抽象了三种数据格式：

| 实现类 | 适用场景 | 说明 |
|--------|---------|------|
| `RtcmFileDataSource` | 事后RTCM文件 | 支持多文件（多小时数据） |
| `RinexFileDataSource` | 事后RINEX文件 | 需obs+nav文件 |
| `RtcmMemoryDataSource` | 实时RTCM流 | 逐帧feed，累积数据 |

接口方法：

```java
public interface GnssDataSource {
    String getId();                                    // 数据源标识
    double[] getAntennaPosition();                     // RTCM 1005基站坐标(ECEF)
    List<SolData> sppPositioning(PrcOpt sppOpt);       // SPP定位
    List<SolData> rtkPositioning(GnssDataSource rover, PrcOpt rtkOpt);  // RTK基线解算
    List<SolData> staticPositioning(GnssDataSource refBase, PrcOpt staticOpt); // 静态解算
    boolean hasData();                                 // 是否有数据
    double estimatedDataHours();                       // 估计数据时长(小时)
}
```