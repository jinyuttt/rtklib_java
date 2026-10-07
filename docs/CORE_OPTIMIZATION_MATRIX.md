# rtklib-core 优化功能矩阵

> **版本**：v1.0  
> **日期**：2026-10-07  
> **目标模块**：`rtklib-core`  
> **编码规则**：代码真实默认值（非文档描述值），以 `RtkConfig.java` 实际字段值为准  

---

## 0. 定位模式速查

| 模式 | 常量 | dynamics | 状态维度 | 典型场景 |
|------|------|----------|----------|----------|
| **SPP** | `PMODE_SINGLE` | 0 | 3~4 (pos+clk) | 单点定位 |
| **DGPS** | `PMODE_DGPS` | 0 | 3~4 | 伪距差分 |
| **RTK Kinematic** | `PMODE_KINEMA` | 1 | ~60~200+ | 动态RTK |
| **RTK Static** | `PMODE_STATIC` | 0 | ~60~200+ | 静态RTK（滑坡/形变监测） |
| **RTK Moving-Base** | `PMODE_MOVEB` | 1 | ~60~200+ | 移动基准站 |
| **RTK Fixed** | `PMODE_FIXED` | 0 | ~60~200+ | 已知坐标约束 |
| **PPP Kinematic** | `PMODE_PPP_KINEMA` | 1 | ~6~200+ | 动态PPP |
| **PPP Static** | `PMODE_PPP_STATIC` | 0 | ~6~200+ | 静态PPP |
| **PPP Fixed** | `PMODE_PPP_FIXED` | 0 | ~6~200+ | 已知坐标PPP |
| **PPP-RTK Kinematic** | `PMODE_PPPRTK_KINEMA` | 1 | ~6~200+ | SSR增强动态PPP-RTK |
| **PPP-RTK Static** | `PMODE_PPPRTK_STATIC` | 0 | ~6~200+ | SSR增强静态PPP-RTK |

> **短基线**：< 10 km（电离层/对流层双差消除，不估计大气参数）  
> **中长基线**：10~100 km（估计ZTD/电离层）  
> **长基线**：> 100 km（需梯度参数、精细大气建模）  

---

## 1. 优化功能总矩阵

### 1.1 RTK 扩展优化

| # | 优化项 | 开关 | 默认 | SPP | DGPS | RTK<br>Static<br>🔭短基线 | RTK<br>Static<br>🔭长基线 | RTK<br>Kinematic<br>🔭短基线 | RTK<br>Kinematic<br>🔭长基线 | 原理导向 |
|---|--------|------|------|-----|------|------|------|------|------|----------|
| R1 | 滑动窗自适应Q矩阵 | `enableAdaptiveQ` | false | — | — | ⚠️ | ⚠️ | ✅ | ✅ | **动态专用**：运动/零速检测→Q缩放。Static下qScale对收敛模糊度无实质影响 `【实测: Static FIX率 81.0%→81.0%】` |
| R2 | 模糊度子集锚固 | `enableAmbAnchor` | false | — | — | ✅ | ✅ | ✅ | ✅ | **通用**：连续FIX≥50历元→跳过LAMBDA，锚固模糊度不因少数卫星信号恶化而重置 |
| R3 | 大气参数自适应冻结 | `atmFrozenNsThresh` | 7 | — | — | — | ✅ | — | ✅ | **长基线专用**：卫星数<阈值→冻结ion/trop过程噪声，防止少星时参数漂移 |
| R4 | IGGIII抗差估计 | `enableIggiii` | false | — | — | ✅ | ✅ | ✅ | ✅ | **通用**：标准化残差异常→降权(三段式IGGIII)，抑制多路径/粗差 `【K0=3.0σ, K1=6.0σ, MinW=0.5】(代码真实默认值)` |
| R5 | SNR中值参考星选择 | `enableSnrMedian` | false | — | — | ✅ | ✅ | ✅ | ✅ | **通用**：各频点SNR中值替代固定参考值→弱信号降权；参考星评分=高度角+SNR接近度 |
| R6 | PAR参考星重选 | `enableParRefReselect` | false | — | — | ✅ | ✅ | ✅ | ✅ | **通用（AR增强）**：Ratio不足时排除贡献差卫星+重选参考星 `【⚠️ 仅LAMBDA失败后触发】` |
| R7 | 电离层/对流层梯度 | `enableIonoTropGradient` | false | — | — | — | ✅ | — | ✅ | **长基线专用**：每星VTEC+Gn+Ge三参数，建模电离层空间不均匀性 `【C版2.5.0已有，Java统一RtkConfig管理】` |
| R8 | 逐级模糊度固定 | `enableCascadeAR` | false | — | — | ✅ | ✅ | ✅ | ✅ | **多频（≥2频）通用**：EWL(λ≈0.86m)→WL(λ≈0.48m)→NL(λ≈0.11m)逐级约束，EWL/WL成功率>95% |
| R9 | 精细化残差编辑 | `enableResidualEdit` | false | — | — | ✅ | ✅ | ✅ | ✅ | **通用（AR基础）**：残差跳变检测+伪距-载波一致性+弧段完整性三重周跳检测 |
| R10 | 部分模糊度固定 | `enablePartialAR` | false | — | — | ✅ | ✅ | ✅ | ✅ | **通用（AR增强）**：全局Ratio失败→逐步剔除方差大模糊度→子集固定 |
| R11 | Bootstrapping联合判据 | `enableBootstrapping` | false | — | — | ✅ | ✅ | ✅ | ✅ | **通用（AR增强）**：Ratio通过后补验模糊度联合取整成功率≥0.99，防Ratio假阳性 |
| R12 | BDS卫星码偏差改正 | `enableBdsCodeBias` | false | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | **含BDS时通用**：Wanninger模型改正GEO/IGSO/MEO码偏差 `【GEO固定-0.58m(B1I)】` |
| R13 | 参数类型级过程噪声 | `enableParamTypeNoise` | false | — | — | ⚠️ | ✅ | ⚠️ | ✅ | **长/中基线**：ZTD随机游走(1e-4 m/√s)、钟差白噪声(1e2 m)、电离层随机游走(1e-3 m/√s)，替代统一prn[]模型 |

> **图例**：✅ = 适用且推荐  |  ⚠️ = 可开但效果弱/无实质收益  |  — = 不适用（模式不支持或无意义）

### 1.2 PPP 扩展优化

| # | 优化项 | 开关 | 默认 | PPP<br>Static | PPP<br>Kinematic | PPP-RTK<br>Static | PPP-RTK<br>Kinematic | 原理导向 |
|---|--------|------|------|------|------|------|------|----------|
| P1 | GPT3+VMF3对流层 | `enableGpt3Vmf3` | false | ✅ | ✅ | ✅ | ✅ | **通用**：GPT3经验先验ZHD/VMF3映射函数替代Saastamoinen+GMF `【实测24h静态: dN±0.14~0.18m, 1h动态: N方向-0.06~-0.22m趋势】` |
| P2 | GPT3网格插值 | `useGpt3Grid` | false | ✅ | ✅ | ✅ | ✅ | **通用**：5°×5°网格插值精度高于简化经验系数 |
| P3 | IERS2010潮汐 | `enableIers2010` | false | ✅ | ✅ | ✅ | ✅ | **通用（静态效果更显著）**：固体潮(dehanttideinel)、极潮(9mm/9mm/-33mm)、大气潮(S1/S2) `【mm级改正，动态下噪声淹没】` |
| P4 | ISB/IFCB/IFB偏差 | `enableIsbIfcbIfb` | false | ✅ | ✅ | ✅ | ✅ | **多系统通用**：估系统间/频间偏差参数，显著改善多GNSS融合PPP收敛 `【ISB 60m²初方差，IFCB 30m²】` |
| P5 | OSB偏差改正 | `enableOsb` | false | ✅ | ✅ | ✅ | ✅ | **通用（PPP-AR前提）**：观测值特异性偏差注入观测方程 |
| P6 | PPP-AR WL+NL固定 | `enablePppAR` | false | ✅ | ✅ | ✅ | ✅ | **通用**：WL(λ≈86cm)→NL(λ≈11cm)两步固定，浮点解→固定解 `【需FCB/OSB产品】` |
| P7 | PPP-AR Fix-and-Hold | `enablePppArFixHold` | false | ✅ | ✅ | ✅ | ✅ | **通用（AR增强）**：连续FIX≥50历元→方差收紧至1e-6，防回浮点 |
| P8 | PPP Partial AR | `enablePppPartialAR` | false | ✅ | ✅ | ✅ | ✅ | **通用（AR增强）**：全局Ratio失败→最少4星子集→最多10次尝试 |
| P9 | BDS-3 PPP-AR | `enableBds3PppAR` | false | ✅ | ✅ | ✅ | ✅ | **BDS-3专用**：PRN 19~46 MEO/IGSO卫星的B1C/B2a固定 |
| P10 | 多频PPP-AR | `enableMultiFreqAR` | false | ✅ | ✅ | ✅ | ✅ | **≥3频通用**：EWL→WL→NL三级联固定 |
| P11 | PPP-RTK处理器 | `enablePppRtk` | false | — | — | ✅ | ✅ | **PPP-RTK专用**：接收SSR改正（轨道/钟差/码偏差/相位偏差），替代标准PPP |
| P12 | PPP-RTK AR | `enablePppRtkAR` | false | — | — | ✅ | ✅ | **PPP-RTK专用**：LAMBDA+ratio test+Fix-and-Hold |
| P13 | PPP-RTK Fix-and-Hold | `enablePppRtkFixHold` | false | — | — | ✅ | ✅ | **PPP-RTK专用**：FIX后收紧方差至1e-4 |
| P14 | 紧凑SSR解码 | `enableCompactSsr` | false | — | — | ✅ | ✅ | **QZSS CLAS专用**：L6专有格式解码，MT1-12消息类型+网格插值 |

> **图例**：✅ = 适用且推荐  |  ⚠️ = 可开但效果弱  |  — = 不适用

### 1.3 处理阶段特性（非RtkConfig开关）

| # | 特性 | 控制方式 | SPP | DGPS | RTK<br>Static | RTK<br>Kinematic | PPP<br>Static | PPP<br>Kinematic | 原理导向 |
|---|------|----------|-----|------|------|------|------|------|----------|
| T1 | 非组合PPP | `ionoopt=EST/SSR` | — | — | — | — | ✅ | ✅ | **PPP通用**：逐星估计电离层，替代IF组合 `【收敛慢于IF，需电离层约束】` |
| T2 | 前向-后向平滑 | `Smoother/CombinedFilter` | — | — | ✅ | — | — | — | **静态后处理专用**：Kalman平滑器 `【离线，双向滤波加权融合】` |
| T3 | 基站稳定性监测 | `BaseStationMonitor` | — | — | ✅ | ✅ | ✅ | ✅ | **监控工具**：SPP窗口差分检测基站位移 `【检测阈值10m/突变+5m/漂移】` |
| T4 | 滑坡监测专用配置 | `LandslideMonitorTest` 用例 | — | — | ✅ | — | — | — | **静态长基线示例**：自适应Q+锚固+大气冻结组合 `【BDS三频+ARMODE_FIXHOLD】` |
| T5 | SSR电离层 | `ionoopt=SSR/IONOOPT_TEC` | — | — | — | — | ✅ | ✅ | **PPP通用**：外部IONEX/SSR产品提供电离层先验→收敛加速 |
| T6 | S1/S2大气潮 | `enableAt1S2` | false | — | — | — | — | ✅ | ✅ | **PPP通用**：IERS2010扩展，S1/S2大气潮改正 `【mm级，需enableIers2010=true】` |
| T7 | MW组合DCB校正 | 自动（mwmeas内） | false | — | — | — | — | ✅ | ✅ | **PPP-AR基础**：从nav.cbias读码偏差改正P1/P2→提升WL整数性 |
| T8 | DCB文件独立读取 | `DcbReader` | — | — | — | — | — | ✅ | ✅ | **PPP工具**：支持CODE .DCB + IGS .BIA/.BSX格式 |

---

## 2. 静态场景验证汇总

### 2.1 有实测或等价性验证的优化项

| 优化项 | 基线类型 | 数据 | 验证方式 | 结论 |
|--------|----------|------|----------|------|
| **R1 自适应Q** | 短基线 | RTCM流 1h | RTK Static FIX率对比 | `【等价性】` Static下FIX率 81.0%→81.0%，qScale缩放对已收敛模糊度无实质影响。**Static不推荐开启** |
| **R1 自适应Q** | 长基线 | 滑坡监测数据 | LandslideMonitorTest | `【集成验证】` 自适应Q+锚固+大气冻结组合通过测试，零速压制有效 |
| **P1 GPT3+VMF3** | — | 3站×24h静态 | 基础版 vs 优化版±差 | `【实测】` dN ±0.14~0.18m, dE ±0.03~0.23m, dU 0.26~0.40m。**24h静态分米级差异**（GPT3+VMF3贡献有限，需配合AR验证） |
| **P1 GPT3+VMF3** | — | 3站×1h动态 | 基础版 vs 优化版STD | `【实测】` N方向-0.06~-0.22m正向趋势，E/U无显著恶化。**1h浮点解噪声量级内，不足以证明精度显著提升** |
| **P3 IERS2010** | — | 同上 | 同组对比 | `【原理等价】` dehanttideinel+IERS极潮公式为国际标准实现，**数学等价于GAMIT/Bernese等权威软件**，mm级差异不可由定位残差区分 |

### 2.2 原理上有明确预期但缺实测数据的优化项

| 优化项 | 基线类型 | 预期效果 | 待验证 |
|--------|----------|----------|--------|
| **R2 模糊度锚固** | 短/长 | 静态长弧段下FIX连续性↑（保护数百历元已固定的模糊度不被重置） | 静态2h+弧段下FIX连续性对比 |
| **R3 大气冻结** | 🔭长基线 | 少星时（城市峡谷）防ZTD/ion漂移→坐标稳定性↑ | 模拟卫星降级场景对比 |
| **R8 逐级AR** | 短/长 | ≥2频时EWL/WL固定成功率>95%，NL搜索空间缩小→整体FIX率↑ | 多频数据FIX率对比 |
| **R9 残差编辑** | 短/长 | 伪距-载波一致性检测→减少错误弧段→AR成功率↑ | 含多路径/周跳场景对比 |
| **R10 部分AR** | 短/长 | 全局Ratio失败→子集固定→固定率↑（至少4星） | 弱信号场景固定率对比 |
| **R11 Bootstrapping** | 短/长 | Ratio假阳性→Bootstrapping拒绝→假固定率↓ | 低Ratio边界场景对比 |
| **R13 参数类型噪声** | 🔭长基线 | ZTD/ion过程噪声按物理特性建模→长基线精度↑ | 长基线静态对比 |
| **P6 PPP-AR** | — | WL+NL固定→cm级（浮点dm~m级） | 静态24h收敛时间+FIX率 |
| **P4 ISB/IFCB/IFB** | — | 多系统融合PPP收敛显著加速 | 多系统vs单系统收敛对比 |

---

## 3. 动态场景优化分类

### 3.1 专为动态场景设计

| 优化项 | 动态设计意图 | 适用条件 | 局限性 |
|--------|-------------|----------|--------|
| **R1 自适应Q** | 运动/静止自动切换Q缩放：静止压制噪声(0.01x)→静态精度↑，运动放大噪声(5x)→跟踪响应↑ `【零速检测：速度<0.5m/s、位置差分<0.05m、连续3历元】` | dynamics=1，有速度状态 | Static下qScale对已收敛模糊度无实质影响；需合理的静态/动态阈值配置；协方差发散(>1e6)时自动旁路 |
| **P11 PPP-RTK** | 接收SSR实时流(轨道+钟差+码偏差+相位偏差)→替代精密星历→cm级快速收敛 | 必须有SSR数据源(RTCM/CLAS)；maxAge=60s内有效 | 无SSR→回退标准PPP；SSR中断→收敛恶化 |

### 3.2 通用优化（静态+动态均有效）

| 优化项 | 对动态的额外价值 | 适用条件 | 局限性 |
|--------|-----------------|----------|--------|
| **R4 IGGIII** | **动态下更关键**：城市峡谷NLOS粗差频发，抗差估计直接抑制异常观测权重 | 有标准化残差计算基础 | K0/K1阈值需适配环境（代码默认K0=3.0σ偏宽松）；多频一致性惩罚在单频下无效果 |
| **R5 SNR中值** | 动态下信号质量波动大，SNR自适应参考更合理 | 各频点≥3颗有效卫星计算中值 | 卫星数<3回退固定参考SNR(40dB-Hz)；高度角+SNR综合评分 |
| **R6 PAR重选** | Ratio不足时触发，动态下参考星信号易波动 | LAMBDA失败后触发；最多连续3次重选 | 静态下极少触发（参考星稳定）；重选有额外计算开销 |
| **R9 残差编辑** | 动态下周跳风险更高（遮挡/多路径），三重检测覆盖缓变周跳 | 有ddres后验残差可用 | 残差跳变阈值4.0σ可配置；伪距-载波一致性在电离层活跃时需放宽 |
| **R12 BDS码偏差** | 动态下BDS GEO/IGSO高度角变化快，Wanninger模型随el线性插值改正 | 含有BDS卫星；B1I/B2I频点 | GEO固定-0.58m仅对B1I;高度角>30°可不改正(bdsCodeBiasElThresh=30) |

### 3.3 需特殊条件才能用于动态

| 优化项 | 动态前提 | 说明 |
|--------|---------|------|
| **R7 离子梯度** | 长基线 + 动态 | 动态下电离层穿刺点快速变化，梯度参数需足够卫星数约束，少星时梯度估计不可靠→配合R3大气冻结 |
| **R8 逐级AR** | ≥2频 | 动态下模糊度初始化周期短，EWL/WL在数历元内即可固定，NL需10~30历元收敛 |
| **P6 PPP-AR** | 需FCB/OSB+连续弧段 | 动态下弧段短（遮挡频繁），WL/NL固定窗口可能不足→配合P7 Fix-Hold保护已固定模糊度 |

---

## 4. 推荐配置组合

### 4.1 静态短基线 RTK（形变/滑坡监测）

```
RtkConfig: enableAdaptiveQ + enableAmbAnchor + atmFrozenNsThresh=5
PrcOpt:    PMODE_STATIC, dynamics=0, ARMODE_FIXHOLD, IONOOPT_IFLC
特点:      零速压制Q→高精度，锚固保护稳定模糊度，少星冻结防漂移
实测:      LandslideMonitorTest通过
```

### 4.2 静态长基线 RTK

```
RtkConfig: enableAdaptiveQ + enableAmbAnchor + enableIonoTropGradient
           + enableParamTypeNoise + atmFrozenNsThresh=7
PrcOpt:    PMODE_STATIC, IONOOPT_EST, TROPOPT_EST/ESTG
特点:      梯度建模电离层空间不均匀性，参数类型噪声精细建模
注:        更依赖实际调试，无完整长基线验证数据
```

### 4.3 动态短基线 RTK（车载/无人机）

```
RtkConfig: enableAdaptiveQ + enableIggiii + enableSnrMedian 
           + enableCascadeAR + enablePartialAR + enableBootstrapping
PrcOpt:    PMODE_KINEMA, dynamics=1, ARMODE_FIXHOLD
特点:      自适应Q区分运动/静止，IGGIII抗NLOS，逐级+部分AR最大化固定率
```

### 4.4 静态PPP（科研/基准站）

```
RtkConfig: enableGpt3Vmf3 + enableIers2010 + enableIsbIfcbIfb 
           + enablePppAR + enablePppArFixHold + enablePppPartialAR
PrcOpt:    PMODE_PPP_STATIC
特点:      精密对流层/潮汐模型+多系统偏差+PPP-AR固定解
```

### 4.5 PPP-RTK（实时精密定位）

```
RtkConfig: enablePppRtk + enablePppRtkAR + enablePppRtkFixHold
PrcOpt:    PMODE_PPPRTK_KINEMA/STATIC
前提:      SSR数据源（RTCM SSR / QZSS CLAS）
```

---

## 5. 优化项间协同关系

```
┌─────────────────────────────────────────────────────────┐
│                    基础观测层                            │
│  R9 残差编辑 ──→ 干净弧段 ──→ R5 SNR中值 ──→ 合理权重   │
│  R12 BDS码偏差 ──→ 无偏伪距 ──→ T7 MW DCB ──→ WL整数性  │
├─────────────────────────────────────────────────────────┤
│                    滤波鲁棒层                            │
│  R4 IGGIII + R1 自适应Q ──→ 静态低噪/动态高响应          │
│  R3 大气冻结 + R13 参数噪声 ──→ 防少星漂移（长基线↕）    │
├─────────────────────────────────────────────────────────┤
│                    AR增强层                              │
│  R9 残差编辑（前提）──→ R8 逐级AR ──→ R11 Bootstrapping  │
│             └──→ R10 部分AR（Ratio失败补救）             │
│             └──→ R6 PAR重选（参考星波动补救）            │
│                        └──→ R2 锚固（长期固定保护）       │
├─────────────────────────────────────────────────────────┤
│                    精密模型层（PPP↕）                     │
│  P1 GPT3+VMF3 + P3 IERS2010 ──→ 精密先验                 │
│  P4 ISB + P5 OSB ──→ 多系统融合                          │
│  P6 WL+NL AR ──→ P7 Fix-Hold ──→ P8 Partial AR          │
│                     └──→ P9 BDS-3 / P10 多频             │
└─────────────────────────────────────────────────────────┘
```

---

## 6. 代码默认值与文档偏差表

| 优化项 | 参数 | 文档描述值 | 代码真实值 | 影响 |
|--------|------|-----------|-----------|------|
| R4 IGGIII | `iggiiiK0` | 1.5 | **3.0** | 代码更宽松，可疑段起始阈值翻倍 |
| R4 IGGIII | `iggiiiK1` | 3.0 | **6.0** | 代码更宽松，淘汰段阈值翻倍 |
| R4 IGGIII | `iggiiiMinW` | 1e-4 | **0.5** | 代码淘汰段权重远更高（0.5 vs 几乎零权） |
| R4 IGGIII | `iggiiiLowElW` | 0.01 | **0.5** | 代码低高度角惩罚极弱 |
| R4 IGGIII | `iggiiiMultiFreqW` | 0.01 | **0.5** | 代码多频一致性惩罚极弱 |

> **结论**：IGGIII代码默认值处于"极保守"模式（几乎不降权），生产环境应调整为文档描述值以求实际抗差效果。

---

## 7. 已知局限与未验证项

| 项目 | 状态 | 说明 |
|------|------|------|
| 动态RTK对比 | ❌ 未验证 | 所有现有测试使用 `PMODE_KINEMA` 配置但数据本身可能是静态采集，无真实动态数据 |
| 长基线验证 | ❌ 未验证 | R7梯度/CascadeAR/参数噪声在短基线上效果等价于关闭 |
| 城市峡谷场景 | ❌ 未验证 | 无NLOS/多路径密集场景数据，IGGIII/SwitchVariable实际效果未知 |
| PPP-AR完整验证 | ⚠️ 部分 | 有算法级单元测试，集成测试受数据可用性限制 |
| 多频(≥3频)验证 | ⚠️ 部分 | R8逐级AR/R10多频AR有测试但依赖多频数据 |
| 组合优化交互 | ⚠️ 部分 | R1+R4组合已验证，全量组合(R1-R13全开)未系统性评估 |