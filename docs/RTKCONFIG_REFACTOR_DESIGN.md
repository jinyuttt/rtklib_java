# RtkConfig 组合模式重构设计

> 版本：v1.1  
> 日期：2026-10-26（v1.1 修订 2026-10-28）  
> 前置：无（joint 模块已实现并验证，FIX=91.5%）  
> 状态：已完成（2026-10-28）
>
> v1.1 修订：动机改写（主痛点是 core 内模式归属不可见，非 joint 解耦）；字段清单经脚本
> 对账修正为 178 个；RtkState 签名按现状（7 参）重写；删除 @Deprecated 兼容层方案，
> 改为按组原子迁移；删除 §9（joint 已存在，与现状矛盾）；影响范围补充 rtklib-joint。

---

## 1. 动机

### 1.1 现状

`RtkConfig` 是一个 **178 字段的扁平类**（2026-10-28 脚本实测），所有定位模式（SPP/RTK/PPP/PPP-AR/PPP-RTK）的 Java 扩展开关混在一起：

```
RtkConfig (487 行)
├── RTK 优化：enableAdaptiveQ, enableIggiii, enableSnrMedian, enableCascadeAR, ...
├── SPP 优化：enableSppEkf, enableSppRobust, sppEkfVarPos, sppRobustKPos, ...
├── PPP 优化：enableGpt3Vmf3, enableIers2010, enableIsbIfcbIfb, ...
├── PPP-AR：enablePppAR, pppArRatioWl, fcbFile, updFile, ...
├── PPP-RTK：enablePppRtk, pppRtkArRatio, ssrMaxAge, ...
├── 自适应场景：enableAdaptiveScene, adaptiveWindowEpochs, ...
└── 共用：enableParRefReselect, parElMask, atmFrozenNsThresh, ...
```

### 1.2 问题

| 问题 | 表现 |
|------|------|
| **模式归属不可见（主痛点）** | 做 SPP 时分不清 178 个字段里哪些对当前模式有效，只能翻文档；想要"做 SPP 就用 SppConfig、做 RTK 就用 RtkConfig"式的参数归类 |
| **命名空间污染** | SPP 用户看到 `pppArRatioWl`，PPP 用户看到 `sppEkfVarPos`，无法区分哪些开关属于当前模式 |
| **配置归属不清** | 新增优化项时，开发者不确定该放哪个前缀（`spp`/`ppp`/`rtk`/无前缀），导致 `enableIflcPairNegotiation` 这类 RTK 专用开关无前缀 |
| **PresetManager 膨胀** | 场景切换时需逐字段设值，178 个字段中 ~80% 与当前模式无关但仍需遍历 |
| **joint 模块无法精简（附带）** | 联合 RTK 只需 CommonOpts + RtkOpts，但被迫依赖整个 RtkConfig（joint 已实现，本项为附带收益而非主动机） |
| **序列化/日志噪音** | 调试 SPP 时日志输出 178 个字段，其中 140+ 个是其他模式的默认值 |

### 1.3 目标

1. **命名空间隔离**：每个模式只看到自己的开关 + 共用开关（`rtkConfig.spp.enableEkf`、`rtkConfig.rtk.enableIggiii`）
2. **模式归属明确**：新增开关有明确的归属子配置
3. **调用点迁移**：现有代码 `rtk.rtkConfig.enableIggiii` → `rtk.rtkConfig.rtk.enableIggiii`，编译器驱动迁移
4. **PresetManager 简化**：场景切换只操作相关子配置
5. **joint 模块精简（附带）**：RtkState 可只传 CommonOpts + RtkOpts

---

## 2. 字段归属分析

### 2.1 共用（CommonOpts）— 所有模式都用

| 字段 | 类型 | 默认值 | 使用者 |
|------|------|--------|--------|
| enableParRefReselect | boolean | false | RtkCore |
| parElMask | double | 15.0 | RtkCore |
| parMaxConsecutiveReselect | int | 3 | RtkCore |
| atmFrozenNsThresh | int | 7 | RtkCore |
| enableIflcPairNegotiation | boolean | false | RtkCore |
| enableParamTypeNoise | boolean | false | RtkCore |
| noiseZtdRw | double | 1e-4 | RtkCore |
| noiseClkWhite | double | 1e2 | RtkCore |
| noiseIonoRw | double | 1e-4 | RtkCore |

**9 个字段**

### 2.2 RTK 专用（RtkOpts）— 双差相对定位

| 字段 | 类型 | 默认值 | 使用者 |
|------|------|--------|--------|
| enableAdaptiveQ | boolean | false | RtkCore |
| adaptiveQNsRef | double | 8.0 | RtkCore |
| adaptiveQPdopRef | double | 3.0 | RtkCore |
| adaptiveQScaleMinZeroVel | double | 0.1 | RtkCore |
| adaptiveQScaleMax | double | 2.0 | RtkCore |
| adaptiveQScaleMinMoving | double | 0.5 | RtkCore |
| zeroVelSpeedThresh | double | 0.5 | RtkCore |
| zeroVelPosDiffThresh | double | 0.05 | RtkCore |
| zeroVelConsecutiveEpochs | int | 3 | RtkCore |
| zeroVelStdThresh | double | 0.2 | RtkCore |
| adaptiveQTraceThresh | double | 1e6 | RtkCore |
| adaptiveQWinSize | int | 50 | RtkCore |
| adaptiveQStaticThresh | double | 0.001 | RtkCore |
| adaptiveQDynamicThresh | double | 0.05 | RtkCore |
| adaptiveQScaleMinStatic | double | 0.01 | RtkCore |
| adaptiveQScaleMaxDynamic | double | 5.0 | RtkCore |
| enableIggiii | boolean | false | RtkCore, RtkOptimizations |
| iggiiiK0 | double | 3.0 | RtkOptimizations |
| iggiiiK1 | double | 6.0 | RtkOptimizations |
| iggiiiMinW | double | 0.5 | RtkOptimizations |
| iggiiiLowElMask | double | ~0.175 | RtkOptimizations |
| iggiiiLowElNormThresh | double | 2.5 | RtkOptimizations |
| iggiiiLowElW | double | 0.5 | RtkOptimizations |
| iggiiiMultiFreqW | double | 0.5 | RtkOptimizations |
| iggiiiLowElExtraIterMask | double | ~0.262 | RtkOptimizations |
| enableSnrMedian | boolean | false | RtkCore |
| snrMedianMinEl | double | ~0.175 | RtkCore |
| snrMedianMinLockTime | double | 10.0 | RtkCore |
| snrMedianWindowSize | int | 20 | RtkCore |
| snrMedianKCode | double | 2.0 | RtkCore |
| snrMedianKPhase | double | 0.5 | RtkCore |
| snrMedianMinSnr | double | 25.0 | RtkCore |
| snrMedianInvalidVar | double | 1e6 | RtkCore |
| snrMedianMinSatsForFallback | int | 3 | RtkCore |
| snrMedianFallbackCodeRef | double | 35.0 | RtkCore |
| snrMedianFallbackPhaseRef | double | 40.0 | RtkCore |
| snrMedianAbsMin | double | 20.0 | RtkCore |
| enableIonoTropGradient | boolean | false | RtkCore |
| gradientIonoInitVar | double | 1e-4 | RtkCore |
| gradientIonoPrn | double | 1e-3 | RtkCore |
| enableAmbAnchor | boolean | false | RtkCore |
| ambAnchorMinFixCount | int | 100 | RtkCore |
| ambAnchorVar | double | 1e-9 | RtkCore |
| enableCascadeAR | boolean | false | RtkCore |
| cascadeArRatioEwl | double | 1.5 | RtkCore |
| cascadeArRatioWl | double | 2.0 | RtkCore |
| cascadeArRatioNl | double | 3.0 | RtkCore |
| cascadeArBootstrapping | boolean | false | RtkCore |
| enableResidualEdit | boolean | false | RtkCore |
| residEditJumpThresh | double | 4.0 | RtkCore |
| residEditMinArcLen | int | 10 | RtkCore |
| residEditPcConsistThresh | double | 3.0 | RtkCore |
| enablePartialAR | boolean | false | RtkCore |
| partialArMinRatio | double | 2.0 | RtkCore |
| partialArMinSats | int | 4 | RtkCore |
| partialArMinBootstrapping | double | 0.99 | RtkCore |
| partialArMaxSubsetTries | int | 10 | RtkCore |
| enableBootstrapping | boolean | false | RtkCore |
| bootstrappingMinSuccess | double | 0.99 | RtkCore |
| bootstrappingWithRatio | boolean | true | RtkCore |
| enableSegmentSigmaFilter | boolean | false | RtkProcessor |
| segmentSigmaThreshold | double | 5.0 | RtkProcessor |
| segmentSigmaMinLen | int | 5 | RtkProcessor |
| enableBdsCodeBias | boolean | false | RtkCore |
| bdsCodeBiasElThresh | double | 30.0 | RtkCore |
| bdsCodeBiasForceOn | boolean | false | RtkCore |

**66 个字段**（v1.0 误记为 63）

### 2.3 SPP 专用（SppOpts）

| 字段 | 类型 | 默认值 | 使用者 |
|------|------|--------|--------|
| enableSppEkf | boolean | false | SppProcessor |
| enableSppRobust | boolean | false | SppProcessor |
| enableSppZeroVel | boolean | false | SppProcessor |
| enableSppDopplerSnr | boolean | false | SppProcessor |
| enableSppInnovChisq | boolean | true | SppProcessor |
| sppEkfMinSat | int | 0 | SppProcessor |
| sppEkfLowSatThresh | int | 7 | SppProcessor |
| sppEkfVarPos | double | 2500 | SppProcessor |
| sppEkfVarVel | double | 100 | SppProcessor |
| sppEkfVarAcc | double | 100 | SppProcessor |
| sppEkfClkProcessNoise | double | 10000 | SppProcessor |
| sppEkfAccPrn1 | double | 10.0 | SppProcessor |
| sppEkfAccPrn2 | double | 10.0 | SppProcessor |
| sppEkfVelResidScale | double | 1.0 | SppProcessor |
| sppEkfVarResetThresh | double | 2500 | SppProcessor |
| sppEkfDtGap | double | 10.0 | SppProcessor |
| sppEkfVarCap | double | 625 | SppProcessor |
| sppEkfDivergedRecoverEpochs | int | 5 | SppProcessor |
| sppEkfDivergedRscaleBase | double | 100.0 | SppProcessor |
| sppEkfDivergedRscaleMax | double | 1000.0 | SppProcessor |
| sppEkfDivergedMaxEpochs | double | 20 | SppProcessor |
| sppEkfDivergedQscale | double | 1.0 | SppProcessor |
| sppInnovOutlierThresh | double | 5.0 | SppProcessor |
| sppInnovDownweightThresh | double | 3.0 | SppProcessor |
| sppInnovOutlierThreshLowSat | double | 7.0 | SppProcessor |
| sppInnovDownweightThreshLowSat | double | 5.0 | SppProcessor |
| sppInnovDownweightRscale | double | 10.0 | SppProcessor |
| sppRobustKPos | double | 1.345 | SppProcessor |
| sppRobustKVel | double | 5.0 | SppProcessor |
| sppRobustMaxIter | int | 15 | SppProcessor |
| sppRobustPosOutlier | double | 100.0 | SppProcessor |
| sppRobustVelOutlier | double | 20.0 | SppProcessor |
| sppRobustMinW | double | 0.0001 | SppProcessor |
| sppZeroVelSpeedThresh | double | 0.5 | SppProcessor |
| sppZeroVelMinEpochs | int | 1 | SppProcessor |

**35 个字段**（v1.0 误记为 34）

### 2.4 PPP 专用（PppOpts）

| 字段 | 类型 | 默认值 | 使用者 |
|------|------|--------|--------|
| enableGpt3Vmf3 | boolean | false | PppCore, PppProcessor |
| gpt3GridFile | String | "" | PppProcessor |
| vmf3OpFile | String | "" | PppProcessor |
| useGpt3Grid | boolean | false | PppProcessor |
| enableIers2010 | boolean | false | PppCore, PppProcessor |
| enableAt1S2 | boolean | false | PppProcessor |
| ionexFile | String | "" | PppProcessor |
| enableIsbIfcbIfb | boolean | false | PppCore, PppProcessor |
| enablePppIsbClockInit | boolean | false | PppCore |
| estimateIsb | boolean | true | PppCore |
| estimateIfcb | boolean | true | PppCore |
| estimateIfb | boolean | true | PppCore |
| isbPrn | double | 0.01 | PppCore |
| ifcbPrn | double | 0.01 | PppCore |
| ifbPrn | double | 0.001 | PppCore |
| enableIonoEstRobust | boolean | false | PppCore |
| enableOsb | boolean | false | PppCore, PppProcessor |
| osbFile | String | "" | PppProcessor |
| dcbFile | String | "" | PppProcessor |
| enableSsrTropDirect | boolean | false | PppCore, PppRtkCore |
| ssrMaxAge | double | 60.0 | PppProcessor |
| ssrIonoMode | int | 1 | PppProcessor |
| enableCompactSsr | boolean | false | PppProcessor |
| compactSsrGridFile | String | "" | PppProcessor |
| cssrDecoder | CompactSsrDecoder | null | PppCore |

**25 个字段**

### 2.5 PPP-AR 专用（PppArOpts）

| 字段 | 类型 | 默认值 | 使用者 |
|------|------|--------|--------|
| enablePppAR | boolean | false | PppProcessor, PppCore |
| pppArRatioWl | double | 2.0 | PppArEngine |
| pppArRatioNl | double | 3.0 | PppArEngine |
| pppArWlMaxdev | double | 0.20 | PppArEngine |
| pppArNlMaxdev | double | 0.15 | PppArEngine |
| pppArNlMaxsig | double | 0.15 | PppArEngine |
| pppArNlMinsav | int | 4 | PppArEngine |
| fcbFile | String | "" | PppProcessor |
| updFile | String | "" | PppProcessor |
| enablePppArFixHold | boolean | false | PppProcessor |
| pppArFixHoldVar | double | 1e-6 | PppAmbFix |
| pppArFixHoldMinEp | int | 50 | PppAmbFix |
| enablePppPartialAR | boolean | false | PppProcessor |
| pppPartialArMinRatio | double | 2.0 | PppProcessor |
| pppPartialArMinSats | int | 4 | PppProcessor |
| pppPartialArMaxTries | int | 10 | PppProcessor |
| enablePppArDeltaGate | boolean | false | PppAmbFix |
| pppArDeltaMax | double | 0.5 | PppAmbFix |
| enableBds3PppAR | boolean | false | PppProcessor |
| enableMultiFreqAR | boolean | false | PppProcessor |
| pppArRatioEwl | double | 1.5 | PppArEngine |

**21 个字段**

### 2.6 PPP-RTK 专用（PppRtkOpts）

| 字段 | 类型 | 默认值 | 使用者 |
|------|------|--------|--------|
| enablePppRtk | boolean | false | PppProcessor |
| enablePppRtkAR | boolean | false | PppProcessor |
| pppRtkArRatio | double | 3.0 | PppRtkAmbFix |
| enablePppRtkFixHold | boolean | false | PppRtkAmbFix |
| pppRtkFixHoldMinEpoch | int | 10 | PppRtkAmbFix |
| pppRtkFixHoldVar | double | 1e-4 | PppRtkAmbFix |

**6 个字段**

### 2.7 自适应场景（AdaptiveOpts）

| 字段 | 类型 | 默认值 | 使用者 |
|------|------|--------|--------|
| enableAdaptiveScene | boolean | false | AdaptiveController |
| adaptiveWindowEpochs | int | 0 | AdaptiveController |
| adaptiveCooldownEpochs | int | 0 | AdaptiveController |
| adaptiveNsatOpenToCanyon | int | 8 | AdaptiveController |
| adaptiveSnrOpenToCanyon | double | 30.0 | AdaptiveController |
| adaptiveNsatCanyonToOpen | int | 14 | AdaptiveController |
| adaptiveSnrCanyonToOpen | double | 34.0 | AdaptiveController |
| adaptiveManagedParams | Set\<String\> | null | AdaptiveController |
| adaptiveLockedParams | Set\<String\> | null | AdaptiveController |
| adaptiveAuditEnabled | boolean | true | AdaptiveAuditLogger |
| adaptiveAuditDir | String | "" | AdaptiveAuditLogger |
| adaptiveAuditMaxSizeKB | int | 5120 | AdaptiveAuditLogger |
| adaptiveAuditMaxFiles | int | 3 | AdaptiveAuditLogger |
| adaptiveVerbose | boolean | true | AdaptiveController |
| adaptiveTraceAll | boolean | false | AdaptiveController |
| adaptiveListener | AdaptiveListener | null | AdaptiveController |

**16 个字段**

### 2.8 汇总

| 子配置 | 字段数 | 占比 |
|--------|--------|------|
| CommonOpts | 9 | 5.1% |
| RtkOpts | 66 | 37.1% |
| SppOpts | 35 | 19.7% |
| PppOpts | 25 | 14.0% |
| PppArOpts | 21 | 11.8% |
| PppRtkOpts | 6 | 3.4% |
| AdaptiveOpts | 16 | 9.0% |
| **合计** | **178** | 100% |

> 2026-10-28 脚本对账：RtkConfig 实测 178 个 public 字段，上述表格合计恰好 178，
> 为**完整无重复划分**（跨模块字段如 enableSsrTropDirect 只归入主要消费者 PppOpts 一次）。
> v1.0 的汇总数字（174）与"~120 字段"说法有误，已更正。

---

## 3. 重构方案

### 3.1 新结构

```java
public class RtkConfig implements Serializable {
    private static final long serialVersionUID = 2L;  // 版本变更

    public final CommonOpts common = new CommonOpts();
    public final RtkOpts    rtk    = new RtkOpts();
    public final SppOpts    spp    = new SppOpts();
    public final PppOpts    ppp    = new PppOpts();
    public final PppArOpts  pppAr  = new PppArOpts();
    public final PppRtkOpts pppRtk = new PppRtkOpts();
    public final AdaptiveOpts adaptive = new AdaptiveOpts();
}
```

每个子配置是独立的 Serializable 类：

```java
public class CommonOpts implements Serializable {
    private static final long serialVersionUID = 1L;
    public boolean enableParRefReselect = false;
    public double parElMask = 15.0;
    public int parMaxConsecutiveReselect = 3;
    public int atmFrozenNsThresh = 7;
    public boolean enableIflcPairNegotiation = false;
    public boolean enableParamTypeNoise = false;
    public double noiseZtdRw = 1e-4;
    public double noiseClkWhite = 1e2;
    public double noiseIonoRw = 1e-4;
}

public class RtkOpts implements Serializable {
    private static final long serialVersionUID = 1L;
    public boolean enableAdaptiveQ = false;
    public double adaptiveQNsRef = 8.0;
    // ... 其余 63 个字段，去掉 rtk 前缀（已在命名空间内）
    public boolean enableIggiii = false;
    public double iggiiiK0 = 3.0;
    // ...
    public boolean enableCascadeAR = false;
    // ...
}

public class SppOpts implements Serializable {
    private static final long serialVersionUID = 1L;
    public boolean enableEkf = false;       // 原 enableSppEkf
    public boolean enableRobust = false;    // 原 enableSppRobust
    public boolean enableZeroVel = false;   // 原 enableSppZeroVel
    // ...
}
```

### 3.2 字段重命名规则

在子配置内部，去掉模式前缀（已在命名空间内）：

| 原名 | 新位置 | 新名 |
|------|--------|------|
| enableSppEkf | SppOpts | enableEkf |
| sppEkfVarPos | SppOpts | ekfVarPos |
| enablePppAR | PppArOpts | enableAR |
| pppArRatioWl | PppArOpts | arRatioWl |
| enablePppRtkAR | PppRtkOpts | enableAR |
| pppRtkArRatio | PppRtkOpts | arRatio |
| adaptiveWindowEpochs | AdaptiveOpts | windowEpochs |

**例外**：CommonOpts 中的字段保持原名（无前缀可去）。

### 3.3 调用点迁移

#### 迁移前

```java
// RtkCore.java
if (rtk.rtkConfig.enableAdaptiveQ && rtk.qScale != 1.0) { ... }
if (rtk.rtkConfig.enableCascadeAR && nfAr >= 2) { ... }

// PppProcessor.java
if (rtk.rtkConfig.enablePppAR) { ... }

// SppProcessor.java
if (rtkConfig.enableSppEkf) { ... }
```

#### 迁移后

```java
// RtkCore.java
if (rtk.rtkConfig.rtk.enableAdaptiveQ && rtk.qScale != 1.0) { ... }
if (rtk.rtkConfig.rtk.enableCascadeAR && nfAr >= 2) { ... }

// PppProcessor.java
if (rtk.rtkConfig.pppAr.enableAR) { ... }

// SppProcessor.java
if (rtkConfig.spp.enableEkf) { ... }
```

### 3.4 迁移策略：按组原子迁移（无兼容层）

**v1.1 更正**：v1.0 的"@Deprecated 扁平字段 + getter/setter 兼容层过渡一个版本"方案已删除。
原因：现有代码全是 **public 字段直访**（`rtk.rtkConfig.enableAdaptiveQ`），Java 字段无法委托，
扁平字段与子配置字段并存会产生**两份独立存储**——未迁移的写入方写扁平字段、已迁移的读取方
读子配置字段，编译不报错、静默丢配置（测试里 `config.enableIggiii = true` 会悄悄失效）。

改为**按子配置分组、组内原子迁移**，每组一个提交完成"建类 → 迁移调用点 → 删扁平字段"：

1. 编译器驱动：删掉某组扁平字段后，`mvn compile` 报错的每个点就是迁移点，清零即完成
2. 组间无依赖，每步编译绿色、测试通过，无共存窗口
3. 迁移顺序（由小到大、由独立到核心）：
   AdaptiveOpts(16) → SppOpts(35) → PppRtkOpts(6) → PppArOpts(21) → PppOpts(25) → RtkOpts+CommonOpts(66+9)

---

## 4. RtkState record 精简（附带收益，非本重构的前置）

**v1.1 更正**：v1.0 假设的 4 参签名已过时。RtkState 现状（Rtk.java:185）为 **7 参**，
且 joint 模块已实现并消费该 record（JointCore/JointDdRes/JointRtk）：

### 4.1 现状（2026-10-28）

```java
// Rtk.java
public RtkState toState() {
    return new RtkState(opt, ssat, rtkConfig, snrMedian, sol, x, tt);
}

// JointRtk.java（safeX 变体）
return new RtkState(opt, ssat, rtkConfig, snrMedian, sol, safeX, 0.0);
```

### 4.2 重构完成后（可选的后续精简）

```java
public record RtkState(PrcOpt opt, Ssat[] ssat, CommonOpts common, RtkOpts rtk,
                       double[] snrMedian, Solution sol, double[] x, double tt) {}
```

joint 模块不需要 PppOpts/PppArOpts/PppRtkOpts/SppOpts——它只做 RTK。
**注意**：此精简不是本重构的必要部分，可单独评估；若做，需同步修改
JointCore（2 处 toState 调用）、JointDdRes、JointRtk。

### 4.3 Rtk.toState() 更新（若做 4.2 精简）

```java
public RtkState toState() {
    return new RtkState(opt, ssat, rtkConfig.common, rtkConfig.rtk, snrMedian, sol, x, tt);
}
```

---

## 5. PresetManager 简化

### 5.1 迁移前

```java
// 场景切换时需设 ~40 个字段
config.enableIggiii = true;
config.iggiiiK0 = 3.0;
config.iggiiiK1 = 6.0;
// ... 还有 37 行 ...
config.enablePppAR = false;  // PPP-AR 开关在 RTK 场景下也要显式关闭
```

### 5.2 迁移后

```java
// 场景切换只操作相关子配置
config.rtk.enableIggiii = true;
config.rtk.iggiiiK0 = 3.0;
config.rtk.iggiiiK1 = 6.0;
// ... 只有 20 行，全是 rtk 子配置 ...

// PPP-AR 子配置完全不受影响，无需显式关闭
```

---

## 6. 影响范围

### 6.1 需修改的文件

| 文件 | rtkConfig 引用数 | 改动类型 |
|------|-----------------|---------|
| RtkCore.java | 21 | `rtkConfig.xxx` → `rtkConfig.rtk.xxx` / `rtkConfig.common.xxx` |
| PppProcessor.java | 34 | `rtkConfig.xxx` → `rtkConfig.ppp.xxx` / `rtkConfig.pppAr.xxx` |
| PppCore.java | 10 | `rtkConfig.xxx` → `rtkConfig.ppp.xxx` |
| PppRtkCore.java | 4 | `rtkConfig.xxx` → `rtkConfig.pppRtk.xxx` / `rtkConfig.ppp.xxx` |
| SppProcessor.java | 2 | `rtkConfig.xxx` → `rtkConfig.spp.xxx` |
| RtkProcessor.java | 2 | `rtkConfig.xxx` → `rtkConfig.rtk.xxx` |
| PostPosProcessor.java | 2 | `rtkConfig.xxx` → `rtkConfig.rtk.xxx` |
| AdaptiveController.java | ~15 | `rtkConfig.xxx` → `rtkConfig.adaptive.xxx` |
| AdaptiveOverride.java | ~10 | 多子配置 |
| PresetManager.java | ~10 | 多子配置 |
| RtkOptimizations*.java | ~20 | `rtkConfig.xxx` → `rtkConfig.rtk.xxx` |
| PppAmbFix*.java | ~10 | `rtkConfig.xxx` → `rtkConfig.pppAr.xxx` |
| PppArEngine.java | ~5 | `rtkConfig.xxx` → `rtkConfig.pppAr.xxx` |
| RtkConfig.java | — | 拆分为 7 个类 |
| Rtk.java | — | rtkConfig 字段类型不变 |
| **rtklib-joint**（JointCore/JointDdRes/JointRtk） | ~5 | v1.1 补充：消费 RtkState（内嵌 RtkConfig）；若做 §4.2 精简需同步改签名 |
| 测试文件 | ~25 | 同主代码迁移 |

**总改动量**：~101 个 rtkConfig 引用点 + RtkConfig 拆分 + 测试

### 6.2 不受影响的文件

- rtklib-adjust：不引用 rtkConfig（0 处）
- rtklib-stream：不引用 rtkConfig（0 处）
- PrcOpt：不变（C 原版结构，与 RtkConfig 正交）
- 数据结构（Rtk, Ssat, Obsd 等）：rtkConfig 字段类型仍是 RtkConfig，不变

---

## 7. 实施路线（v1.1：按组原子迁移，编译器驱动）

**原则**：每组一个提交，组内完成"建子配置类 → 迁移全部调用点 → 删扁平字段"，
`mvn compile` 报错清零即完成；不留扁平字段与子配置并存的窗口（见 §3.4）。

### 步骤 1：AdaptiveOpts（16 字段，试点）

- 涉及：AdaptiveController(~15) + AdaptiveOverride(~10) + PresetManager(~10) + 测试
- 注意：`adaptiveManagedParams`/`adaptiveLockedParams` 中的参数字符串路径同步改为
  `"rtk.enableIggiii"` 形式（路径解析逻辑在 AdaptiveController）
- 验证：`mvn test -pl rtklib-core -Dtest=Adaptive*Test`

### 步骤 2：SppOpts（35 字段）

- 涉及：SppProcessor(2) + 测试（SPP 字段大头在测试/配置侧）
- 字段重命名同步做（去 `spp` 前缀，`enableSppEkf` → `enableEkf`）
- 验证：`mvn test -pl rtklib-core -Dtest=*Spp*Test`

### 步骤 3：PppRtkOpts（6 字段）

- 涉及：PppProcessor + PppRtkCore(4) + PppRtkAmbFix
- 验证：PPP-RTK 相关测试

### 步骤 4：PppArOpts（21 字段）

- 涉及：PppProcessor + PppAmbFix*(~10) + PppArEngine(~5)
- 验证：PPP-AR 相关测试

### 步骤 5：PppOpts（25 字段）

- 涉及：PppProcessor(34) + PppCore(10)
- 注意 `enableSsrTropDirect` 归 PppOpts 后，PppRtkCore 改引用 `rtkConfig.ppp.*`
- 验证：PPP 相关测试

### 步骤 6：RtkOpts + CommonOpts（66+9 字段，最大的一组放最后）

- 涉及：RtkCore(21) + RtkOptimizations*(~20) + RtkProcessor(2) + PostPosProcessor(2)
- 验证：RTK 优化测试组 + RTK 端到端测试（含 FIX 率回归，对齐 FIX-ALG-044/045 基准）

### 步骤 7（可选）：RtkState 精简

- 按 §4.2/§4.3，同步修改 joint 模块 3 个文件
- 验证：joint 双基线测试（FIX=91.5% 基准回归）

**全程**：每步 `mvn compile` 零报错 + 相关测试全绿后才进入下一步；全部完成后 `mvn test` 全量回归。

---

## 8. 风险与缓解

| 风险 | 概率 | 影响 | 缓解 |
|------|------|------|------|
| 迁移时归属判断错误（如 enableSsrTropDirect 同时被 PPP 和 PPP-RTK 使用） | 中 | 编译错误 | 归属按**主要消费者**确定，跨模块使用的字段归入更通用的子配置（enableSsrTropDirect → PppOpts） |
| 迁移中间态双写分歧（扁平字段与子配置字段并存，读旧写新或反之） | 中 | 配置静默丢失 | 按组原子迁移：每组一个提交内"建类 → 迁移调用点 → 删扁平字段"，不保留 @Deprecated 兼容层（论证见 §3.4） |
| 序列化不兼容（旧版本序列化的 RtkConfig 无法反序列化） | 高 | 已有结果文件无法读取 | 字段移入子配置后旧格式必然失效，不做兼容层；变更 serialVersionUID 使其显式失败而非静默错读，并在升级说明中注明 |
| 外部配置文件（JSON/YAML）扁平键路径变化 | 低 | 用户配置失效 | 本重构不改字段名、只改归属；配置读取层加扁平键 → 子配置路径映射，或在文档中提供对照表 |
| AdaptiveOpts 的 managedParams/lockedParams 引用字段路径变更 | 中 | 场景切换失效 | PresetManager 中的参数路径同步更新（如 `"enableIggiii"` → `"rtk.enableIggiii"`） |