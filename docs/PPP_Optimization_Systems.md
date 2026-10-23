# PPP 优化体系指南

> 版本：v1.0
> 日期：2026-10-21
> 定位：**体系认知与使用纪律**——使用任何 PPP 优化项之前，先回答一个问题："你开的是哪个体系的优化项"。体系决定验收指标、前置条件和测试纪律。
> 配套文档：各优化项的实现原理、代码侵入点、单元测试见 [PPP_Extra_Optimizations.md](PPP_Extra_Optimizations.md)；算法修复记录见 [ALGORITHM_FIX_LOG.md](ALGORITHM_FIX_LOG.md)。

---

## 0. 体系总览（必读）

### 0.1 三体系关系

```
                ┌────────────────────────────────────┐
                │        浮点 PPP 体系（地基）          │
                │   PppCore.pppos()                   │
                │   PppCoreEx.ppos()（扩展开关路由）    │
                └─────────────────┬──────────────────┘
                                  │ 浮点解收敛（bias 状态干净）
                                  │
                ┌─────────────────┴──────────────────┐
                │        整数性恢复来源不同             │
                │                                    │
     ┌──────────▼──────────┐              ┌──────────▼──────────┐
     │     PPP-AR 体系      │              │    PPP-RTK 体系      │
     │  FCB/OSB 产品恢复    │              │  SSR 改正压制误差    │
     │  模糊度整数性        │              │  后 LAMBDA 固定      │
     │  PppCoreEx AR 分支   │              │  PppRtkCore.ppprtk() │
     └─────────────────────┘              └─────────────────────┘
```

三个体系**共用同一个浮点滤波器基座**（pppos 状态估计：位置/钟/ZTD/模糊度 bias/偏差参数），差别只在"增强层"：

| 体系 | 增强来源 | 整数性恢复方式 | 代码入口 |
|------|----------|----------------|----------|
| 浮点 PPP | 环境模型 + 偏差建模 | 无（不固定） | `PppCore.pppos()` / `PppCoreEx.ppos()` |
| PPP-AR | FCB/OSB 外部产品 | 产品恢复整数性 → WL+NL LAMBDA | `PppCoreEx.ppos()` 收敛后 AR 分支 |
| PPP-RTK | RTCM SSR 实时流 | SSR 改正压掉轨道/钟误差 → LAMBDA | `PppRtkCore.ppprtk()`（独立处理器） |

### 0.2 体系判定表（配置落到哪个体系）

| 配置特征 | 落入体系 | 路由 |
|----------|----------|------|
| `enablePppRtk = true`（有 SSR 流） | **PPP-RTK** | `PppRtkCore.ppprtk()`，互斥分支，不经 PppCoreEx |
| 任意 AR 开关（`enablePppAR` / `enablePppPartialAR` / `enablePppArFixHold` / `enableBds3PppAR` / `enableMultiFreqAR`）或偏差开关开启 | **PPP-AR** | `PppCoreEx.ppos()`，浮点收敛后进入 AR 分支 |
| 仅 `enableGpt3Vmf3` / `enableIers2010` 开启 | **浮点**（模型替换，不触发扩展路由） | 仍走 `PppCore.pppos()`，侵入点在 PppCore 内部 |
| 仅 `useGpt3Grid` / `enableAt1S2` / `enableIsbIfcbIfb` / `enableOsb` 开启 | **浮点**（状态/模型扩展） | `PppCoreEx.ppos()` |
| 全部开关关闭 | **浮点**（标准 RTKLIB 2.5.0 对齐基线） | `PppCore.pppos()` |

> 注意：`enableGpt3Vmf3` / `enableIers2010` 与 `useGpt3Grid` 的路由行为不同——前者是 PppCore 内部模型替换，后者触发 PppCoreEx 扩展路径。体系判定以"是否引入估计状态/固定逻辑"为准，不以"是否为优化项"为准。

### 0.3 核心原则

1. **浮点优化项是三个体系共用的地基**。GPT3+VMF3、IERS2010、ISB/IFCB/IFB、非组合（EST）、OSB 作用于观测模型层与状态估计层，在 AR/RTK 介入之前就改变浮点状态质量，理论上与 AR/RTK 开关正交可叠加。
2. **AR/RTK 专属优化项只在各自体系内组合**。两套专属开关（Full AR / Partial AR / Fix-and-Hold 三件套）机制同构但整数性来源不同，验收指标与浮点完全不同，A/B 表格互相独立。
3. **隔离的对象是"对照变量与指标口径"，不是"开关永远不能同开"**。AR 全开下单变量 A/B 浮点优化项（A 组 = AR 全开 + GPT3 开，B 组 = AR 全开 + GPT3 关）是合法且必要的实验；禁止的是对角线双变量比较（A 组开浮点项关 AR vs B 组关浮点项开 AR——两个变量同时变，无法归因）。
4. **固定失败必须干净回退浮点**。固定结果只写 `rtk.xa/Pa`，reject 时不碰 `rtk.x/P` 滤波器状态——浮点状态始终是兜底。

---

## 1. 浮点 PPP 体系（地基）

### 1.1 入口与验收标准

| 项目 | 内容 |
|------|------|
| 入口 | `PppCore.pppos()`（基线）/ `PppCoreEx.ppos()`（状态扩展项开启） |
| 验收指标 | 24h 静态精度（E/N/U RMSE）、收敛曲线、存活率（成功历元/总历元） |
| 对照真值 | 必须使用**观测历元当日的独立解**（UNR/NGL 同日解、IGS20 框架）——参考坐标未外推板块运动会被误判为解算系统差（FIX-ALG-019 教训：北美板块 E 向 -1.65cm/yr × 13.8yr ≈ -0.23m） |

### 1.2 当前实测状态（ALGO 站，PppValidationTest 4/4 通过）

- 基准：IFLC + Saastamoinen + IERS1996（tidecorr=7），GPS+GLO
- 收敛后精度：**E = 2mm，N = 1.4cm，U = 4.8cm**（IGS20 框架，UNR 同日基准）
- E 向已到浮点物理极限；U 向剩余 4.8cm 是当前主要优化空间（GPT3+VMF3 / IERS2010 方向）

### 1.3 浮点优化项清单（体系内可任意组合 A/B）

| 优化项 | 开关 | 收益方向（实测 HKWS/HKSL） | 场景注意 | 优先级 |
|--------|------|---------------------------|----------|--------|
| 非组合 PPP（EST） | `opt.ionoopt = IONOOPT_EST` | N -30~40%、E -50~70%、U -7~22%，**三向全改善，当前最有效单项** | 状态量暴增（每卫星电离层），短弧段/低仰角星多时收敛慢 | 第一 |
| ISB/IFCB/IFB | `enableIsbIfcbIfb` | E -57~75%（消除系统间偏差），存活率提升至 100% | **单 GPS 场景 ISB 不可观**，估计不可观参数反引噪声；N/U 在 IFLC 下有耦合恶化 | 第二（多系统必开） |
| GPT3+VMF3 | `enableGpt3Vmf3` | N -20~34%、E -33~60% | **U 向曾恶化 +144~192%**（疑似缺 GPT3 网格文件致先验 ZTD 异常）——必须配 `useGpt3Grid=true` + 网格文件加载后再验 | 第三（网格就绪后） |
| IERS2010 | `enableIers2010` | 当前实现实测 0%（缺 Hardisp 频率依赖 Love数，S1/S2 量级被噪声淹没） | 沿海站依赖 OTL 模型质量 | 第三（沿海站预期 U 向 1~3cm） |
| OSB 偏差改正 | `enableOsb` | 毫米~厘米级（观测值特异性偏差） | **PPP-AR 前置**：OSB 同时是 FCB 整数性来源 | 条件开启 |

### 1.4 限制

- E 向 2mm 已到极限，浮点侧继续优化空间集中在 U 向（GPT3 网格就绪 + IERS2010 沿海场景）
- ISB/IFCB/IFB 与 IFLC 存在耦合（N/U 恶化），需配合 AR 或改用非组合验证
- "开启了不一定更好"——各开关场景适用性见第 5 章矩阵

---

## 2. PPP-AR 体系（独立分支）

### 2.1 入口与验收标准

| 项目 | 内容 |
|------|------|
| 入口 | `PppCoreEx.ppos()` 浮点收敛后 AR 分支（WL+NL → Partial → BDS-3 → Fix-and-Hold 依次尝试） |
| 验收指标 | **TTFF（首次固定时间）+ 固定率 + 假固定率**——不是 24h 精度 |
| 假固定率 | 固定解对照真值的**逐历元误差分布（p50/p90/p99）+ 固定解 RMSE**。仅看 TTFF + 固定率会被"自洽假 Fix"完整骗过（浮点陷入错误整数邻域时 ratio 小、固定"成功"且自洽）——Tokyo run1 实证：基线 H.RMSE 9.5m 假 Fix 簇，整数域 z 统计门限无法拦截 |

### 2.2 路由架构（两条处理路径）

系统存在两条 PPP 处理路径，AR 分发逻辑不同：

```
路径A: PppProcessor.processEpoch()
         ├─ enablePppRtk  → PppRtkCore.ppprtkos()    (PPP-RTK)
         ├─ AR/偏差开关   → PppCoreEx.ppos()          ✅ AR 已路由
         └─ 其他          → PppCore.pppos()            (基础浮点)

路径B: RinexPppProcessor → PostPosProcessor → RtkCore.rtkpos()
         ├─ enablePppRtk  → (无，PPP-RTK不经此路径)
         ├─ AR/偏差开关   → PppCoreEx.ppos()          ✅ 已修复(2026-10-21)
         └─ 其他          → PppCore.pppos()            (基础浮点)
```

| 路径 | 处理器 | AR 分发 | 使用者 | 数据来源 |
|------|--------|---------|--------|----------|
| A | `PppProcessor` | ✅ 原有 | `PppArDiag` / `PppBdsPipeline` / `PppAccuracyTest` | 本地RTCM + IGS产品 |
| B | `RinexPppProcessor` → `RtkCore` | ✅ 已修复 | `PppArValidationTest` | IGS公共数据(ALGO) |

**修复记录**：`RtkCore.rtkpos()` 原硬编码 `PppCore.pppos()`，2026-10-21 增加 `cfg` 判断分支，当 AR/偏差开关开启时路由到 `PppCoreEx.ppos()`。

### 2.3 前置条件（缺一不可）

1. **浮点解已收敛**：2D < 10cm，连续多历元稳定（浮点质量直接决定固定率）
2. **FCB/OSB 产品已加载**：`nav.fcbWl`（FcbReader 传统 FCB）或 `nav.fcbFromOsb = true`（OSB 转换）；`cfg.fcbFile` 配置路径。**产品缺失时开 AR = 空转或把连续值硬当整数固定**
3. **ionoopt 与 FCB 产品频点一致**（IFLC 或非组合）

### 2.3 PPP-AR 专属优化项（只在 AR 体系内组合 A/B）

| 优化项 | 开关 | 作用 | A/B 建议 |
|--------|------|------|----------|
| Full AR (WL+NL) | `enablePppAR` | 基础固定（ratio: WL 2.0 / NL 3.0） | AR 组基线 |
| Partial AR | `enablePppPartialAR` | 全固定失败时固定子集（minSats=4, maxTries=10） | 对比固定率；**统计口径分 SOLQ 等级**——部分固定会掩盖全固定不达标的信号 |
| Fix-and-Hold | `enablePppArFixHold` | 锁定已固定模糊度（方差收紧 1e-6） | 对比固定连续性；**MinEp=50 守卫必须保留** |
| BDS-3 AR | `enableBds3PppAR` | B1C/B2a 专用（PRN 19-46） | 条件开启（有 BDS-3 数据） |
| 多频 AR | `enableMultiFreqAR` | EWL→WL→NL 级联（`pppAmbFixEwlWlNl()`，已实现） | 多频数据场景 |

### 2.4 限制

- 固定失败必须干净回退浮点，不污染 `rtk.x/P`（实现已保证：固定写 `xa/Pa`，reject 不提交）
- Fix-and-Hold 在多路径/城市环境下有**假 Fix 锁死风险**（holdamb 伪观测将错误固定锁定数秒以上）——开阔天空场景才建议开启
- 与浮点优化项的 A/B 纯律见第 4 章

### 2.5 当前测试状态（2026-10-21，ALGO 站，路径B）

**路由修复已验证**：`RtkCore.rtkpos()` → `PppCoreEx.ppos()` 分发生效，AR 代码路径已进入。

**AR 执行诊断**：

| 阶段 | epoch | 现象 | 原因 |
|------|-------|------|------|
| WL方差过大 | 1~10 | `skipVar=14, nbWl=0` | 浮点未收敛，模糊度方差 ~3m² |
| WL方差收敛 | 50 | `skipVar=0, nbWl=14` | ✅ 方差降至 0.04m²，WL可用 |
| WL ratio=1.0 | 50+ | `WL ratio too low: 1.00 < 2.0` | **LAMBDA 两个候选解等价，ratio 恒为1.0** |
| NL | — | 未进入 | WL 未通过，NL 不触发 |

**浮点解质量对比**（同一数据，ALGO 站 2880 历元）：

| 处理器 | E RMS | N RMS | U RMS | 2D RMS | 说明 |
|--------|-------|-------|-------|--------|------|
| `PppCore.pppos()` | 0.0245m | 0.0065m | 0.0440m | 0.0254m | 基础浮点，精度正常 |
| `PppCoreEx.ppos()` | 0.4240m | 1.3108m | 0.6935m | 1.3776m | **精度暴跌 50x** |

**待排查问题**：
1. **PppCoreEx 浮点解质量严重退化**：E/N/U 精度比 PppCore 差 50 倍，AR 无法在劣质浮点解上固定
2. **WL ratio 恒为 1.0**：即使方差收敛后 LAMBDA 搜索也无法区分最优/次优候选，疑似 WL 模糊度协方差结构问题或 OSB 偏差改正未正确施加到浮点滤波
3. **根因推测**：`PppCoreEx.ppos()` 的 `pppResEx()` 观测方程/状态转移与 `PppCore.ppos()` 的 `pppres()` 存在差异，需逐项对比

---

## 3. PPP-RTK 体系（独立分支）

### 3.1 入口与验收标准

| 项目 | 内容 |
|------|------|
| 入口 | `PppRtkCore.ppprtk()`（独立处理器，`enablePppRtk` 路由，不经过 PppCoreEx） |
| 验收指标 | **收敛速度**（30s < 10cm，2min < 5cm）+ SSR 解码正确性——不是 24h 精度 |

### 3.2 前置条件（缺一不可）

1. SSR 数据流已接入（RTCM MT1057-1068 / MT1240-1270，或 CLAS 紧凑 SSR）
2. Rtcm.java SSR 消息解码验证通过
3. `SsrCorrector` 四项改正（轨道/钟差/码偏差/相位偏差）日志中改正量非零——**全零 = 产品未生效，AR 开关全是空转**
4. 若用 SSR 电离层（非组合模式）：dispBias 或 IONEX 网格已加载

### 3.3 PPP-RTK 专属优化项

| 优化项 | 开关 | 作用 |
|--------|------|------|
| SSR 改正 | `enablePppRtk` | 总闸：轨道/钟差/高频钟差/码偏差/相位偏差改正 |
| PPP-RTK AR | `enablePppRtkAR` | LAMBDA + ratio test（`pppRtkArRatio`，默认 3.0） |
| Fix-and-Hold | `enablePppRtkFixHold` | 锁定固定解 |

### 3.4 限制

- **静态 RINEX + SP3/CLK 环境下无法测试**（无 SSR 流）——现有 PppValidationTest/GreatPvt 数据集均不适用
- 只开 `enablePppRtk` 不开 AR 开关 = SSR 增强的浮点 PPP（PPP-RTK 内部也有浮点态/固定态两阶）
- 与 PPP-AR 的 TTFF 不可直接对比：SSR 已压掉轨道/钟误差，浮点收敛快得多，固定触发更早——对比的是产品路线（FCB vs SSR），不是开关

---

## 4. 依赖关系与测试纪律

### 4.1 依赖树

```
浮点优化项（GPT3 / IERS2010 / ISB / 非组合 / OSB）
    ↓ 浮点解收敛（2D < 10cm）
PPP-AR 专属优化项（Full AR / Partial AR / Fix-and-Hold / BDS-3 / 多频）
    ↓ 验收：TTFF + 固定率 + 假固定率（p50/p90/p99 对照真值）

浮点优化项（同上）
    ↓ 浮点解收敛
PPP-RTK 专属优化项（SSR 改正 → AR → Fix-and-Hold）
    ↓ 验收：30s < 10cm、2min < 5cm
```

### 4.2 测试纪律三条

1. **浮点优化项在浮点状态下做 A/B**——不混入 AR/RTK 开关（当前 PppValidationTest 3.x 系列即此模式）
2. **AR/RTK 优化项做 A/B 时，浮点优化项锁死在已验证的最优配置**——只动 AR 专属开关这一个变量
3. **跨体系对比时浮点配置必须一致**——否则分不清是浮点优化的功劳还是 AR/RTK 的功劳

补充：AR 全开下单变量测试浮点优化项是合法实验（隔离的是变量与指标口径，不是开关共存）；预期效果看 AR 指标（固定更早、固定率更高），不是浮点 RMSE。

### 4.3 开关风险分类（开了不一定好）

| 分类 | 开关 | 说明 |
|------|------|------|
| A 通用开启 | `enableIers2010`、`enableGpt3Vmf3` | 纯模型替换，不增参数不改滤波结构；GPT3 前提是网格文件加载 |
| B 开启无害 | `enablePppPartialAR`、`enableSnrMedian` | 兜底性质，但部分固定会掩盖全固定率信号 |
| C 场景有害 | `enablePppArFixHold`（多路径假 Fix 锁死）、`enableIsbIfcbIfb`（单系统不可观）、非组合 EST（短弧段收敛慢）、`enableBdsCodeBias`/`enableBds3PppAR`（非 BDS 无意义） | 必须确认场景前提 |
| D 前提缺失 | AR 系全部开关（FCB/OSB 未加载）、`enablePppRtk` 系（SSR 流未接入） | 空转或乱固定，不是"有害"而是"无源" |

---

## 5. 场景优化矩阵（按体系）

✅ 推荐开启 / ☑ 条件开启（前提满足） / ⚪ 无收益不推荐 / ⛔ 有害避免

### 浮点 PPP 体系

| 开关 | 开阔静态 CORS | 城市动态 | 沿海站 | 单 GPS | 多系统多频 |
|------|:---:|:---:|:---:|:---:|:---:|
| 非组合 EST | ☑ | ⚪（短弧段收敛慢） | ☑ | ☑ | ✅ |
| GPT3+VMF3（含网格） | ✅ | ☑ | ☑ | ✅ | ✅ |
| IERS2010 | ☑ | ⚪ | ✅（预期 U 1~3cm） | ☑ | ☑ |
| ISB/IFCB/IFB | ⚪ | ⚪ | ⚪ | **⛔ 不可观** | ✅ |
| OSB | ☑ | ☑ | ☑ | ☑ | ✅（AR 前置） |

### PPP-AR 体系（浮点收敛 + FCB/OSB 就绪为全局前提）

| 开关 | 开阔静态 | 城市动态 | 多频数据 |
|------|:---:|:---:|:---:|
| enablePppAR | ✅ | ☑（固定率低） | ✅ |
| enablePppPartialAR | ✅ | ✅ | ✅ |
| enablePppArFixHold | ☑（MinEp 守卫保留） | **⛔ 假 Fix 锁死** | ☑ |
| enableBds3PppAR | ☑（有 BDS-3） | ⚪ | ☑ |
| enableMultiFreqAR | ☑ | ⚪ | ✅ |

### PPP-RTK 体系（SSR 流为全局前提）

| 开关 | 实时 SSR 流 | 静态 RINEX 事后 |
|------|:---:|:---:|
| enablePppRtk | ✅ | **不可测**（无流） |
| enablePppRtkAR / enablePppRtkFixHold | ✅（产品链路验证后） | 不可测 |

---

## 6. 场景 Preset（配置组合蓝图）

> 以下为**配置组合建议**，不涉及新算法；每项收益以实测验收为准，定版冻结阶段仅组合既有开关。

| Preset | 体系 | 配置组合 | 目标 | 应用 |
|--------|------|----------|------|------|
| LANDSLIDE | 浮点 + PPP-AR | 非组合 EST + GPT3（网格）+ IERS2010 + `enablePppAR` + `enablePppPartialAR` + `enablePppArFixHold` | E/N < 1cm, U < 2cm | 滑坡/大坝监测 |
| OPEN_SKY | 浮点 | 非组合 EST + GPT3（有网格）+ IERS2010 | E/N < 2cm, U < 5cm | 常规测绘/CORS |
| URBAN_CANYON | 浮点（抗差） | IFLC + 放宽 maxinno + 关闭 GPT3（网格未就绪时 U 向风险） | 不断星、坐标不飞 | 城市动态 |

**验收前检查清单（D 类前置）**：LANDSLIDE 开 AR 前确认 `fcbFile` 加载、`nav.fcbWl != null`、ionoopt 与产品频点一致。

---

## 7. 与既有文档的关系

| 文档 | 职责 |
|------|------|
| 本文档 | 体系认知：三体系划分、判定、验收指标、测试纪律、场景矩阵 |
| [PPP_Extra_Optimizations.md](PPP_Extra_Optimizations.md) | 实现细节：各模块原理、代码侵入点、单元测试清单、逐项对照原始数据 |
| [ALGORITHM_FIX_LOG.md](ALGORITHM_FIX_LOG.md) | 算法修复记录：FIX-ALG-001~029（按编号索引） |
| [PPP_Test_Plan.md](PPP_Test_Plan.md) | 测试计划 |

## 8. 非组合 PPP（EST）死锁修复与实测（FIX-ALG-020，2026-10-21）

### 8.1 根因（详见 ALGORITHM_FIX_LOG.md FIX-ALG-020）

首历元电离层初值（双频伪距差）与 MW 偏差初值含未建模码偏差，泄漏项
`-mapf·Δdcb/(γ-1)` 使相位 prefit 残差（L1 约 +17.8 m）超 maxinno[0]=5.0 被全剔；
码行不含 bias/iono 偏导，两状态协方差永不收缩 → 永久 SOLQ_NONE。

### 8.2 两个修复项

| 项 | 性质 | 配置 | 实测（ALGO 2880 历元） |
|---|---|---|---|
| `DcbReader` + `setDcbPath()` | C 对齐移植（preceph.c readdcb） | `WUM0MGXRAP_20240600000_01D_01D_OSB.BIA`（2024 DOY 060，test-data/gnss-validation/product/bia/） | EST 0→4/2880；EST+MultiSys 0→0/2880 |
| `RtkConfig.enableIonoEstRobust` | JAVA-ONLY 兜底（默认 false） | EST 配置全开 | IFLC→EST 0→401/2880（E 5.6/N 3.7/U 3.2 cm）；EST+MultiSys 0→509/2880（E 4.8/N 1.5/U 5.2 cm） |

### 8.3 结论

1. **仅含卫星 OSB 的 DCB 产品救不了 EST 首历元**——接收机码偏差无产品、随 mapf 变化
   不可被常数偏差吸收，是泄漏主项。修正此前"C 靠自动加载 DCB 规避死锁"的推断
   （未经 C 版实测验证，C 版 headless EST 行为待补参考结果）。
2. `enableIonoEstRobust` 与 FIX-ALG-021 超时重置链修复（udbias 对齐 C 无条件重置 +
   udiono 对齐 C 无条件重初始化）先后落地后，EST 6 配置 401~509→**2880/2880（100%）**，
   EST+DCB 4→1537、EST+MultiSys+DCB 0→1760，IFLC 基线 2679→2880。此前"剩余失败与
   被剔卫星 outc>GAP_RESION 电离层重置链有关，属 EST 体系固有困难"的推测**已实锤为
   两处移植偏差并修复**（udbias 被 vsat 条件阻塞、udiono 被多余 outc 条件阻塞，
   详见 `ALGORITHM_FIX_LOG.md` FIX-ALG-021），EST 体系内卫星级死亡循环解除。
3. 测试纪律：EST 属浮点基座优化项，A/B 仅在浮点状态下进行（PppValidationTest 3.x），
   不与 AR/RTK 开关混测。
