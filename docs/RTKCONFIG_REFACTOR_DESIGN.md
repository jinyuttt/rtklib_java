# RtkConfig 组合模式重构设计

> 版本：v1.0  
> 日期：2026-10-26  
> 前置：JOINT_RTK_DESIGN.md v1.0（RtkState record 方案）  
> 状态：定稿  
> 执行时机：joint 模块 Phase 1 验证通过后（先验证联合 RTK 有效性，再重构配置）

---

## 1. 动机

### 1.1 现状

`RtkConfig` 是一个 **~120 字段的扁平类**，所有定位模式（SPP/RTK/PPP/PPP-AR/PPP-RTK）的 Java 扩展开关混在一起：

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
| **命名空间污染** | SPP 用户看到 `pppArRatioWl`，PPP 用户看到 `sppEkfVarPos`，无法区分哪些开关属于当前模式 |
| **配置归属不清** | 新增优化项时，开发者不确定该放哪个前缀（`spp`/`ppp`/`rtk`/无前缀），导致 `enableIflcPairNegotiation` 这类 RTK 专用开关无前缀 |
| **PresetManager 膨胀** | 场景切换时需逐字段设值，120 个字段中 ~80% 与当前模式无关但仍需遍历 |
| **joint 模块无法精简** | 联合 RTK 只需 CommonOpts + RtkOpts，但被迫依赖整个 RtkConfig |
| **序列化/日志噪音** | 调试 SPP 时日志输出 120 个字段，其中 90 个是 PPP 默认值 |

### 1.3 目标

1. **命名空间隔离**：每个模式只看到自己的开关 + 共用开关
2. **模式归属明确**：新增开关有明确的归属子配置
3. **向后兼容**：现有代码 `rtk.rtkConfig.enableIggiii` → `rtk.rtkConfig.rtk.enableIggiii`，迁移路径清晰
4. **joint 模块精简**：RtkState record 只传 CommonOpts + RtkOpts
5. **PresetManager 简化**：场景切换只操作相关子配置

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

**63 个字段**

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
| spp- sppInnovDownweightRscale | double | 10.0 | SppProcessor |
| sppRobustKPos | double | 1.345 | SppProcessor |
| sppRobustKVel | double | 5.0 | SppProcessor |
| sppRobustMaxIter | int | 15 | SppProcessor |
| sppRobustPosOutlier | double | 100.0 | SppProcessor |
| sppRobustVelOutlier | double | 20.0 | SppProcessor |
| sppRobustMinW | double | 0.0001 | SppProcessor |
| sppZeroVelSpeedThresh | double | 0.5 | SppProcessor |
| sppZeroVelMinEpochs | int | 1 | SppProcessor |

**34 个字段**

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
| CommonOpts | 9 | 7.5% |
| RtkOpts | 63 | 52.5% |
| SppOpts | 34 | 28.3% |
| PppOpts | 25 | 20.8% |
| PppArOpts | 21 | 17.5% |
| PppRtkOpts | 6 | 5.0% |
| AdaptiveOpts | 16 | 13.3% |
| **合计** | **174** | — |

> 注：合计 > 120 是因为部分字段可归入多个子配置（如 enableSsrTropDirect 同时被 PppCore 和 PppRtkCore 使用），实际归属按主要消费者确定。

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

在子配置内部，去掉模式前缀（已在命名空间内0中）：

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

### 3.4 向后兼容层（过渡期）

提供临时兼容访问方法，标记 @Deprecated：

```java
public class RtkConfig implements Serializable {
    // ... 子配置 ...

    @Deprecated public boolean getEnableAdaptiveQ() { return rtk.enableAdaptiveQ; }
    @Deprecated public void setEnableAdaptiveQ(boolean v) { rtk.enableAdaptiveQ = v; }
    @Deprecated public boolean getEnablePppAR() { return pppAr.enableAR; }
    @Deprecated public void setEnablePppAR(boolean v) { pppAr.enableAR = v; }
    // ... 其余 120 个兼容方法 ...
}
```

过渡期（1 个版本）内，旧代码编译通过但产生 deprecation 警告，逐步迁移。

---

## 4. RtkState record 精简

组合模式完成后，`RtkState` record 可以只传需要的子配置：

### 4.1 迁移前（基于扁平 RtkConfig）

```java
public record RtkState(PrcOpt opt, Ssat[] ssat, RtkConfig rtkConfig, double[] snrMedian) {}
```

### 4.2 迁移后（基于组合 RtkConfig）

```java
public record RtkState(PrcOpt opt, Ssat[] ssat, CommonOpts common, RtkOpts rtk, double[] snrMedian) {}
```

joint 模块不需要 PppOpts/PppArOpts/PppRtkOpts/SppOpts——它只做 RTK。

### 4.3 Rtk.toState() 更新

```java
public RtkState toState() {
    return new RtkState(opt, ssat, rtkConfig.common, rtkConfig.rtk, snrMedian);
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
| 测试文件 | ~25 | 同主代码迁移 |

**总改动量**：~101 个 rtkConfig 引用点 + RtkConfig 拆分 + 测试

### 6.2 不受影响的文件

- rtklib-adjust：不引用 rtkConfig（0 处）
- rtklib-stream：不引用 rtkConfig（0 处）
- PrcOpt：不变（C 原版结构，与 RtkConfig 正交）
- 数据结构（Rtk, Ssat, Obsd 等）：rtkConfig 字段类型仍是 RtkConfig，不变

---

## 7. 实施路线

### Phase 0：准备工作（1d）

1. 创建 7 个子配置类（CommonOpts, RtkOpts, SppOpts, PppOpts, PppArOpts, PppRtkOpts, AdaptiveOpts）
2. RtkConfig 中新增 7 个 `public final` 子配置字段
3. 保留原扁平字段，标记 `@Deprecated`
4. 编译通过，所有测试通过（零功能变更）

### Phase 1：迁移调用点（2d）

1. 逐文件将 `rtkConfig.enableIggiii` → `rtkConfig.rtk.enableIggiii`
2. IDE 批量替换 + 手动验证（每个替换点需确认归属正确）
3. 每迁移完一个模块，跑对应测试
4. 迁移完成后删除 `@Deprecated` 兼容方法

### Phase 2：字段重命名（1d）

1. 子配置内部去掉模式前缀：`enableSppEkf` → `enableEkf`
2. IDE Rename Refactor（安全重构，自动更新所有引用）
3. 删除 RtkConfig 中的原扁平字段

### Phase 3：RtkState record 精简（0.5d）

1. `RtkState(PrcOpt, Ssat[], RtkConfig, double[])` → `RtkState(PrcOpt, Ssat[], CommonOpts, RtkOpts, double[])`
2. 更新 `Rtk.toState()` 和 joint 模块

### Phase 4：验证（0.5d）

1. 全量测试通过
2. 序列化兼容性验证（旧 RtkConfig 反序列化）
3. PresetManager 场景切换验证

**总工期**：~5d

---

## 8. 风险与缓解

| 风险 | 概率 | 影响 | 缓解 |
|------|------|------|------|
| 迁移时归属判断错误（如 enableSsrTropDirect 同时被 PPP 和 PPP-RTK 使用） | 中 | 编译错误 | 归属按**主要消费者**确定，跨模块使用的字段归入更通用的子配置（enableSsrTropDirect → PppOpts） |
| 序列化不兼容（旧版本序列化的 RtkConfig 无法反序列化） | 高 | 已有结果文件无法读取 | serialVersionUID 递增；提供 RtkConfigMigration 工具类做旧格式转换 |
| 字段重命名导致外部配置文件（JSON/YAML）不兼容 | 低 | 用户配置失效 | 配置文件读取层加兼容映射；或推迟重命名到下一个大版本 |
| AdaptiveOpts 的 managedParams/lockedParams 引用字段路径变更 | 中 | 场景切换失效 | PresetManager 中的参数路径同步更新（如 `"enableIggiii"` → `"rtk.enableIggiii"`） |

---

## 9. 与 joint 模块的依赖关系

| 顺序 | 任务 | 依赖 |
|------|------|------|
| **1** | RtkConfig 组合模式重构 | 无前置 |
| **2** | RtkState record（精简版） | 依赖 1（需要 CommonOpts + RtkOpts） |
| **3** | core 方法可见性修改 | 依赖 2（zdres 签名需要 RtkState） |
| **4** | joint 模块实现 | 依赖 3（需要调用 core 方法） |

**结论**：先重构 RtkConfig，再实现 joint 模块，路径最优。