# PPP 优化项逐项对照测试计划

> 版本：v1.0  
> 日期：2026-10-15  
> 基于：rtklib-java v2.3.0 / PPP_Extra_Optimizations v3.0  

---

## 1. 测试目标

对PPP 14个优化模块进行**逐项对照测试**，量化每个优化项的独立边际收益：

- **基准配置**：标准PPP（IFLC + Saastamoinen+GMF + IERS1996 + 无偏差模型 + 浮点解）
- **逐项对照**：基准 + 单个优化项开启，对比精度/收敛/固定率变化
- **输出指标**：3D RMS、N/E/U分量RMS、收敛时间（至<1dm）、固定率（PPP-AR）

---

## 2. 测试数据集

### 2.1 数据可用性盘点

| 数据类型 | 文件路径 | 日期 | 系统 | 状态 |
|---------|---------|------|------|------|
| RINEX观测(wtzr) | `test-data/rinex/ppp_wtzr_20180606.18o` | 2018-06-06 | G+R | ✅ 可用 |
| RINEX观测(wtza) | `test-data/rinex/ppp_wtza_20180606.18o` | 2018-06-06 | G+R | ✅ 可用 |
| RINEX观测(hkws) | `test-data/rinex/ppp_hkws_20180606.18o` | 2018-06-06 | G+R+E+C+J+S | ✅ 可用 |
| RINEX观测(hksl) | `test-data/rinex/ppp_hksl_20180606.18o` | 2018-06-06 | G+R+E+C+J+S | ✅ 可用 |
| 广播星历 | `test-data/nav/ppp_20180606.18p` | 2018-06-06 | G+R+E+C+J+S | ✅ 可用 |
| 精密轨道(GFZ) | `test-data/product/gbm20043.sp3` | 2018-06-06 | G+R+E+C | ✅ 可用 |
| 精密钟差(GFZ) | `test-data/product/gbm20043.clk` | 2018-06-06 | G+R+E+C | ✅ 可用 |
| 电离层格网(IGS) | `test-data/product/igsg1570.18i` | 2018-06-06 | G | ✅ 可用 |
| 天线改正 | `test-data/product/test.atx` | - | G+R+E+C+J | ✅ 可用 |
| BSX偏差(CAS) | `test-data/product/cas0mgxrap_2023305.bsx` | 2023-11-01 | G+R+E+C+J | ⚠️ 日期不匹配 |
| OSB偏差(CODE) | `test-data/product/cod_osb_2021265.bia` | 2021-09-22 | G+R+E+C+J | ⚠️ 日期不匹配 |
| FCB/UPD | `test-data/product/upd/upd_wl_*/upd_ewl_*` | 2023-11-01 | G+C+E | ⚠️ 日期不匹配 |
| 参考解(wtzr) | `test-data/reference/ppp_wtzr_20180606.pos` | 2018-06-06 | G+R | ✅ 可用 |
| 参考解(wtza) | `test-data/reference/ppp_wtza_20180606.pos` | 2018-06-06 | G+R | ✅ 可用 |
| GPT3 5°×5°网格 | `gpt3_5deg.dat` | - | - | ❌ 缺失 |
| VMF3 OP文件 | `VMF3_*.H00` | - | - | ❌ 缺失 |

### 2.2 测试站点信息

| 站点 | 类型 | 系统 | 采样率 | 场景 | 参考解 |
|------|------|------|--------|------|--------|
| WTZR | IGS CORS（德国） | G+R | 30s | 开阔静态 | ppp_wtzr_20180606.pos ✅ |
| WTZA | IGS CORS（德国） | G+R | 30s | 开阔静态 | ppp_wtza_20180606.pos ✅ |
| HKWS | HK CORS（香港） | G+R+E+C+J+S | 30s | 开阔静态 | ❌ 无参考 |
| HKSL | HK CORS（香港） | G+R+E+C+J+S | 30s | 开阔静态 | ❌ 无参考 |

### 2.3 数据集选择

**主测试集**：WTZR + WTZA（G+R双系统，有参考解，日期匹配精密产品）

**辅助测试集**：HKWS + HKSL（多系统G+R+E+C+J+S，验证多系统偏差模型，无参考解用RINEX头坐标代替）

**日期匹配说明**：
- 2018-06-06 观测数据 ↔ gbm20043.sp3/clk（GFZ，2018-06-06）✅ 完全匹配
- BSX/BIA/UPD产品日期不匹配（2021/2023），仅用于PPP-AR/偏差模型**功能验证**，不用于精度评估

---

## 3. 优化项清单与测试可行性

| # | 优化项 | 开关 | 数据依赖 | 测试可行性 | 说明 |
|---|--------|------|---------|-----------|------|
| 1 | GPT3+VMF3对流层(经验系数) | `enableGpt3Vmf3` | 无 | ✅ 可测 | 简化经验系数fallback，无需网格文件 |
| 2 | GPT3 5°×5°网格插值 | `useGpt3Grid` | gpt3_5deg.dat | ❌ 缺数据 | 需下载GPT3网格文件 |
| 3 | VMF3 OP站点特异性 | 自动 | VMF3_*.H00 | ❌ 缺数据 | 需下载VMF3 OP文件 |
| 4 | IERS2010潮汐 | `enableIers2010` | 无 | ✅ 可测 | 纯模型计算，无外部数据 |
| 5 | S1/S2大气潮 | `enableAt1S2` | 无 | ✅ 可测 | 纯模型计算 |
| 6 | ISB/IFCB/IFB偏差模型 | `enableIsbIfcbIfb` | 无(估计) | ✅ 可测 | 参数估计，无需外部产品 |
| 7 | OSB偏差改正 | `enableOsb` | BSX/BIA | ⚠️ 日期不匹配 | 功能可验证，精度评估受限 |
| 8 | PPP-AR(WL+NL) | `enablePppAR` | FCB/UPD | ⚠️ 日期不匹配 | 功能可验证，精度评估受限 |
| 9 | Fix-and-Hold | `enablePppArFixHold` | FCB/UPD | ⚠️ 日期不匹配 | 依赖PPP-AR |
| 10 | Partial AR | `enablePppPartialAR` | FCB/UPD | ⚠️ 日期不匹配 | 依赖PPP-AR |
| 11 | BDS-3 PPP-AR | `enableBds3PppAR` | FCB/UPD+BDS | ⚠️ 日期不匹配 | 需BDS观测数据 |
| 12 | PPP-RTK | `enablePppRtk` | SSR流 | ❌ 无SSR数据 | 需实时SSR流或SSR文件 |
| 13 | PPP-RTK AR | `enablePppRtkAR` | SSR流 | ❌ 无SSR数据 | 依赖PPP-RTK |
| 14 | 非组合PPP | `ionoopt=EST/SSR` | 无(EST)/SSR | ⚠️ 部分可测 | EST模式可测，SSR模式缺数据 |

### 可行性分类

- **✅ 完全可测（6项）**：GPT3+VMF3经验系数、IERS2010、S1/S2大气潮、ISB/IFCB/IFB、非组合PPP(EST)
- **⚠️ 功能可验证（5项）**：OSB、PPP-AR、Fix-and-Hold、Partial AR、BDS-3 PPP-AR（偏差产品日期不匹配）
- **❌ 缺数据（3项）**：GPT3网格、VMF3 OP、PPP-RTK/PPP-RTK AR

---

## 4. 测试配置矩阵

### 4.1 基准配置

```java
PrcOpt opt = PppProcessor.createDefaultOpt();
opt.mode = Constants.PMODE_PPP_KINEMA;
opt.nf = 2;
opt.elmin = 10.0 * Constants.D2R;
opt.tropopt = Constants.TROPOPT_EST;
opt.ionoopt = Constants.IONOOPT_IFLC;
opt.sateph = Constants.EPHOPT_PREC;  // 精密星历
opt.tidecorr = 7;  // 固体潮+海潮负荷

RtkConfig cfg = new RtkConfig();  // 全部默认关闭
```

### 4.2 逐项对照配置

| 测试ID | 配置名 | 基准+开启开关 | 预期影响 |
|--------|--------|-------------|---------|
| T01 | baseline | 基准 | — |
| T02 | gpt3vmf3 | `enableGpt3Vmf3=true` | 先验ZTD改善→收敛加速 |
| T03 | iers2010 | `enableIers2010=true` | 潮汐改正→mm级位置偏移 |
| T04 | at1s2 | `enableIers2010=true` + `enableAt1S2=true` | 大气潮→mm级位置偏移 |
| T05 | isbIfcbIfb | `enableIsbIfcbIfb=true` | 多系统偏差→E方向改善 |
| T06 | osb | `enableOsb=true` + BSX产品 | 观测值特异性偏差改正 |
| T07 | pppAr | `enablePppAR=true` + FCB产品 | 浮点→固定解→cm级精度 |
| T08 | pppArFixHold | T07 + `enablePppArFixHold=true` | 固定连续性提升 |
| T09 | pppPartialAR | T07 + `enablePppPartialAR=true` | 弱条件下固定率提升 |
| T10 | bds3PppAR | `enableBds3PppAR=true` + BDS FCB | BDS-3固定解 |
| T11 | uncombinedPpp | `ionoopt=IONOOPT_EST` | 电离层估计→非组合模式 |

---

## 5. 测试方法

### 5.1 静态PPP精度测试

对WTZR/WTZA站执行24小时静态PPP，与参考解对比：

```
误差 = PPP解 - 参考解（ECEF→NEU转换）
RMS_N = sqrt(mean(dN^2))
RMS_E = sqrt(mean(dE^2))
RMS_U = sqrt(mean(dU^2))
RMS_3D = sqrt(RMS_N^2 + RMS_E^2 + RMS_U^2)
```

**收敛判定**：连续20个历元误差<1dm时认为收敛，记录收敛时间。

### 5.2 动态PPP精度测试

对WTZR/WTZA站执行动态PPP（PMODE_PPP_KINEMA），与参考解对比：

- 跳过前30分钟（收敛期）
- 统计收敛后RMS
- 统计成功率（有效历元/总历元）

### 5.3 逐项对照流程

```
for each station in [WTZR, WTZA, HKWS, HKSL]:
    for each testConfig in [T01..T11]:
        result = runPPP(station, testConfig)
        compute RMS, convergence, fixRate
    compute delta vs baseline (T01)
```

### 5.4 参考坐标来源

| 站点 | 参考来源 | 说明 |
|------|---------|------|
| WTZR | ppp_wtzr_20180606.pos | 已有PPP参考解 |
| WTZA | ppp_wtza_20180606.pos | 已有PPP参考解 |
| HKWS | RINEX头APPROX POSITION | 无参考解，用近似坐标 |
| HKSL | RINEX头APPROX POSITION | 无参考解，用近似坐标 |

---

## 6. 预期结果范围

基于PPP_Extra_Optimizations.md §7.3已有实测数据：

| 优化项 | 预期N方向影响 | 预期E方向影响 | 预期U方向影响 | 置信度 |
|--------|-------------|-------------|-------------|--------|
| GPT3+VMF3 | -0.06~-0.22m | ±0.19m | -0.10~+0.03m | 高（已有3站实测） |
| IERS2010 | 包含在GPT3+VMF3结果中 | 同左 | 同左 | 中（需分离测试） |
| ISB/IFCB/IFB | 待测 | 待测（预期E方向改善） | 待测 | 低（无先验数据） |
| PPP-AR | 待测 | 待测 | 待测 | 低（依赖FCB产品匹配） |

---

## 7. 缺失数据获取方案

### 7.1 GPT3 5°×5°网格文件

- **来源**：Vienna University of Technology, GGIM Atmosphere
- **URL**：`https://vmf.geo.tuwien.ac.at/troposphere/GPT3/gpt3_5deg.dat`
- **大小**：~5MB
- **用途**：GPT3网格插值，替代简化经验系数，精度提升~毫米级
- **优先级**：中（经验系数fallback已可测试基本功能）

### 7.2 VMF3 OP文件

- **来源**：Vienna University of Technology
- **URL**：`https://vmf.geo.tuwien.ac.at/troposphere/VMF3/OP/`
- **格式**：VMF3_YYYYMMDD.HHH（每小时一个文件）
- **用途**：站点特异性映射函数系数（ah/aw/bH/bW）
- **优先级**：低（GPT3经验系数+VMF3 b系数模型已可测试）

### 7.3 匹配日期的FCB/OSB产品

- **来源**：CODE/WUM/CAS MGEX
- **日期**：2018-06-06（匹配观测数据）
- **URL**：`https://cddis.nasa.gov/archive/gnss/products/mgex/` 或 IGS CDDIS
- **格式**：.BIA (Bias-SINEX) 或 .BSX
- **用途**：PPP-AR精度评估
- **优先级**：高（PPP-AR核心依赖）

### 7.4 SSR数据

- **来源**：IGS SSR NTRIP广播流 或 历史SSR文件
- **用途**：PPP-RTK测试
- **优先级**：低（PPP-RTK为实时应用，离线测试非必须）

---

## 8. 测试代码设计

### 8.1 测试类结构

```
rtklib-test/src/test/java/org/rtklib/java/test/
├── PppIndividualOptTest.java      ← 新增：逐项对照测试
└── PppAccuracyTest.java           ← 已有：精度验证测试
```

### 8.2 PppIndividualOptTest 核心方法

```java
/**
 * PPP逐项对照测试
 * @param station  站点名（wtzr/wtza/hkws/hksl）
 * @param optName  优化项名称
 * @param cfgCustom  自定义RtkConfig配置回调
 */
static void runIndividualOptComparison(
    String station, String optName, Consumer<RtkConfig> cfgCustom) {

    // 1. 加载数据
    String obsFile = getObsFile(station);
    String navFile = NAV_FILE;  // ppp_20180606.18p
    String sp3File = SP3_FILE;  // gbm20043.sp3
    String clkFile = CLK_FILE;  // gbm20043.clk

    // 2. 运行基准PPP
    RtkConfig baselineCfg = new RtkConfig();
    PppResult baseline = runPpp(obsFile, navFile, sp3File, clkFile, baselineCfg);

    // 3. 运行优化PPP
    RtkConfig optCfg = new RtkConfig();
    cfgCustom.accept(optCfg);
    PppResult optimized = runPpp(obsFile, navFile, sp3File, clkFile, optCfg);

    // 4. 计算RMS
    double[] refPos = getReferencePos(station);
    double[] baselineRms = computeRms(baseline, refPos);
    double[] optRms = computeRms(optimized, refPos);

    // 5. 输出对照结果
    double delta2d = (optRms[2d] - baselineRms[2d]) / baselineRms[2d] * 100;
    System.out.printf("%s | %s | baseline 2D=%.2f | opt 2D=%.2f | Δ2D=%.1f%%%n",
        station, optName, baselineRms[2d], optRms[2d], delta2d);
}
```

### 8.3 测试方法清单

| 方法 | 站点 | 优化项 |
|------|------|--------|
| `testBaselineWtzr` | WTZR | 基准 |
| `testGpt3Vmf3Wtzr` | WTZR | GPT3+VMF3 |
| `testIers2010Wtzr` | WTZR | IERS2010 |
| `testAt1s2Wtzr` | WTZR | S1/S2大气潮 |
| `testIsbIfcbIfbWtzr` | WTZR | ISB/IFCB/IFB |
| `testOsbWtzr` | WTZR | OSB偏差 |
| `testPppArWtzr` | WTZR | PPP-AR |
| `testPppArFixHoldWtzr` | WTZR | Fix-and-Hold |
| `testPartialARWtzr` | WTZR | Partial AR |
| `testUncombinedPppWtzr` | WTZR | 非组合PPP |
| `testBaselineWtza` | WTZA | 基准 |
| `testGpt3Vmf3Wtza` | WTZA | GPT3+VMF3 |
| ... | ... | ... |
| `testBaselineHkws` | HKWS | 基准（多系统） |
| `testIsbIfcbIfbHkws` | HKWS | ISB/IFCB/IFB（多系统重点） |
| `testBaselineHksl` | HKSL | 基准（多系统） |
| `testIsbIfcbIfbHksl` | HKSL | ISB/IFCB/IFB（多系统重点） |

---

## 9. 现有测试覆盖评估

### 9.1 当前测试覆盖

| 测试类 | 类型 | 覆盖优化项 | 数据 | 状态 |
|--------|------|-----------|------|------|
| PppOptimizationsTest | 单元测试 | GPT3+VMF3/IERS2010/ISB/IFCB/IFB/PPP-AR（开关+返回值） | 无（纯逻辑） | ✅ 完整 |
| PppArFixVerificationTest | 单元测试 | Fix-and-Hold守卫/Partial AR转换 | 无（构造输入） | ✅ 完整 |
| RtkPppIntegrationTest | 集成测试 | GPT3+VMF3/IERS2010/ISB/IFCB/IFB/全部 | RTCM流 | ⚠️ 数据路径未配置 |
| PppFeaturesCoverageTest | 集成测试 | GPT3+VMF3/BDS-PPP | RTCM流 | ⚠️ 数据路径未配置 |
| PppProductIntegrationTest | 对比测试 | GPT3+VMF3+IERS2010 | RTCM流(3站) | ✅ 有实测结果 |
| PppAccuracyTest | 精度测试 | GPT3+VMF3/FCB/DCB/BLQ | RTCM流 | ⚠️ 依赖外部数据 |

### 9.2 覆盖缺口

| 优化项 | 单元测试 | 集成测试 | 逐项对照实测 | 缺口 |
|--------|---------|---------|-------------|------|
| GPT3+VMF3(经验系数) | ✅ | ✅ | ✅(3站) | 无 |
| GPT3网格插值 | ✅ | ❌ | ❌ | 需GPT3网格文件 |
| VMF3 OP | ✅ | ❌ | ❌ | 需VMF3 OP文件 |
| IERS2010 | ✅ | ✅ | ⚠️(与GPT3混合) | 需分离测试 |
| S1/S2大气潮 | ✅ | ❌ | ❌ | **需逐项对照** |
| ISB/IFCB/IFB | ✅ | ✅ | ❌ | **需逐项对照** |
| OSB | ✅ | ❌ | ❌ | **需逐项对照+匹配产品** |
| PPP-AR | ✅ | ❌ | ❌ | **需逐项对照+匹配FCB** |
| Fix-and-Hold | ✅ | ❌ | ❌ | **需逐项对照** |
| Partial AR | ✅ | ❌ | ❌ | **需逐项对照** |
| BDS-3 PPP-AR | ✅ | ❌ | ❌ | 需BDS观测+FCB |
| PPP-RTK | ✅ | ❌ | ❌ | 需SSR数据 |
| PPP-RTK AR | ✅ | ❌ | ❌ | 需SSR数据 |
| 非组合PPP | ✅ | ❌ | ❌ | **需逐项对照** |

**关键缺口**：S1/S2大气潮、ISB/IFCB/IFB、OSB、PPP-AR系列、非组合PPP均缺少逐项对照实测。

---

## 10. 执行优先级

| 优先级 | 优化项 | 原因 |
|--------|--------|------|
| P0 | GPT3+VMF3(经验系数) | 数据齐全，已有部分结果，需分离IERS2010 |
| P0 | IERS2010 | 数据齐全，需与GPT3+VMF3分离测试 |
| P0 | S1/S2大气潮 | 数据齐全，纯模型，零依赖 |
| P1 | ISB/IFCB/IFB | 数据齐全，多系统关键优化 |
| P1 | 非组合PPP(EST) | 数据齐全>齐全，电离层估计 |
| P2 | PPP-AR + Fix-and-Hold + Partial AR | 需匹配日期FCB产品 |
| P2 | OSB | 需匹配日期BSX/BIA产品 |
| P3 | GPT3网格 + VMF3 OP | 需下载外部文件 |
| P3 | BDS-3 PPP-AR | 需BDS观测数据 |
| P4 | PPP-RTK + PPP-RTK AR | 需SSR实时流 |

---

## 11. 测试结果记录模板

### 9.1 逐项对照结果（待填充）

**测试条件**：WTZR站，2018-06-06，PMODE_PPP_KINEMA，精密星历gbm20043

| 优化项 | N RMS(m) | E RMS(m) | U RMS(m) | 3D RMS(m) | ΔN(%) | ΔE(%) | ΔU(%) | 收敛(min) | 固定率(%) |
|--------|----------|----------|----------|-----------|-------|-------|-------|----------|----------|
| baseline | — | — | — | — | — | — | — | — | — |
| GPT3+VMF3 | — | — | — | — | — | — | — | — | — |
| IERS2010 | — | — | — | — | — | — | — | — | — |
| S1/S2大气* | — | — | — | — | — | — | — | — | — |
| ISB/IFCB/IFB | — | — | — | — | — | — | — | — | — |
| OSB | — | — | — | — | — | — | — | — | — |
| PPP-AR | — | — | — | — | — | — | — | — | — |
| Fix-and-Hold | — | — | — | — | — | — | — | — | — |
| Partial AR | — | — | — | — | — | — | — | — | — |
| 非组合PPP | — | — | — | — | — | — | — | — | — |

*S1/S2需IERS2010同时开启

### 9.2 多系统对照结果（待填充）

**测试条件**：HKWS站，2018-06-06，G+R+E+C+J+S，ISB/IFCB/IFB重点

| 配置 | N RMS(m) | E RMS(m) | U RMS(m) | 3D RMS(m) | ΔE(%) |
|------|----------A----------|----------|-----------|-------|
| baseline(G+R) | — | — | — | — | — |
| baseline(多系统) | — | — | — | — | — |
| +ISB/IFCB/IFB | — | — | — | — | — |

---

## 12. 与SPP测试文档的对照

| 维度 | SPP测试 | PPP测试 |
|------|---------|---------|
| 数据集数量 | 7组（4 MobileGNSS + 2 PPC + 1 UrbanNav） | 4组（4 CORS站） |
| 运动模式 | 动态为主 | 静态（CORS站） |
| 基准配置 | EKF+抗差 | 标准PPP(IFLC+Saas+GMF+IERS1996) |
| 逐项对照项 | 3项（零速/多普勒SNR/新息检验） | 11项（见§4.2） |
| 精度指标 | 2D RMS + 成功率 | 3D RMS + N/E/U + 收敛时间 + 固定率 |
| 参考位置 | RINEX头/NMEA | PPP参考解/RINEX头 |
| 数据完整性 | ✅ 全部可用 | ⚠️ 部分产品日期不匹配 |

---

## 附录A：文件路径汇总

```
# RINEX观测
test-data/rinex/ppp_wtzr_20180606.18o
test-data/rinex/ppp_wtza_20180606.18o
test-data/rinex/ppp_hkws_20180606.18o
test-data/rinex/ppp_hksl_20180606.18o

# 广播星历
test-data/nav/ppp_20180606.18p

# 精密产品
test-data/product/gbm20043.sp3    # GFZ精密轨道
test-data/product/gbm20043.clk    # GFZ精密钟差
test-data/product/igsg1570.18i    # IGS电离层格网
test-data/product/test.atx        # 天线改正

# 偏差产品（日期不匹配）
test-data/product/cas0mgxrap_2023305.bsx   # CAS BSX (2023-11-01)
test-data/product/cod_osb_2021265.bia      # CODE OSB (2021-09-22)
test-data/product/upd/upd_wl_2023305_G     # WL FCB GPS (2023-11-01)
test-data/product/upd/upd_wl_2023305_C     # WL FCB BDS (2023-11-01)
test-data/product/upd/upd_wl_2023305_E     # WL FCB GAL (2023-11-01)
test-data/product/upd/upd_ewl_2023305_G    # EWL FCB GPS (:2023-11-01)
test-data/product/upd/upd_ewl_2023305_C    # EWL FCB BDS (2023-11-01)
test-data/product/upd/upd_ewl_2023305_E    # EWL FCB GAL (2023-11-01)

# 参考解
test-data/reference/ppp_wtzr_20180606.pos
test-data/reference/ppp_wtza_20180606.pos
```

## 附录B：下载命令

```powershell
# GPT3 5°×5°网格文件（~5MB）
wget https://vmf.geo.tuwien.ac.at/troposphere/GPT3/gpt3_5deg.dat -O test-data/product/gpt3_5deg.dat

# VMF3 OP文件（2018-06-06，UTC 0~23时）
wget https://vmf.geo.tuwien.ac.at/troposphere/VMF3/OP/2018/VMF3_20180606.H00 -O test-data/product/vmf3/VMF3_20180606.H00
# ... H00~H23

# CODE MGEX OSB产品（2018-06-06，DOY=157）
wget https://cddis.nasa.gov/archive/gnss/products/mgex/2018/157/COD0MGXFIN_201815700.BSX -O test-data/product/cod0mgxfin_2018157.b*sx

# WHU FCB产品（2018-06-06）
wget https://cddis.nasa.gov/archive/gnss/products/mgex/2018/157/WUM0MGXRAP_20181570000_01D_01D_OSB.BIA -O test-data/product/wum0mgxrap_2018157.bia
```