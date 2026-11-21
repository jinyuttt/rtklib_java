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
- E 向已到浮点物理极限；U 向剩余 4.8cm 是当前主要优化空间，已实测可归因的方向只有 GPT3 真网格（ΔU -1.3%~-1.9%），其余 U 向候选见 §1.3

### 1.3 浮点优化项清单（体系内可任意组合 A/B）

**数据口径**：下表收益均取自 `PppIndividualOptTest` 的香港 CORS 双站实测（HKWS / HKSL，RINEX 3.x 静态观测 + GBM 精密星历 SP3+CLK，全天 2880 历元（`maxEpochs=0` 全历元运行，默认 120 为 1 小时快跑）、skip=30，基线 = IFLC + Saastamoinen + GMF + IERS1996 潮汐(tidecorr=7) + 动态 PPP，FIX-ALG-029 修复后重测）。Δ 为相对基线变化，**正值为恶化**。原始表格见 [PPP_Extra_Optimizations.md §7.4](PPP_Extra_Optimizations.md)。**旧版表中无数据集标注的百分比区间已全部作废**——单站/单数据集结论不具备跨站可迁移性，下表以双站实测为准。

| 优化项 | 开关 | 收益方向（实测 HKWS / HKSL） | 场景注意 | 优先级 |
|--------|------|---------------------------|----------|--------|
| 非组合 PPP（EST） | `opt.ionoopt = IONOOPT_EST` | HKWS：ΔN +43.8% / ΔE +117.6% / ΔU +139.7%（2879/2880 存活）；HKSL：**0/2880 完全无解**。**两站均未复现改善，站间不可迁移** | 状态量暴增（每卫星电离层），短弧段/低仰角星多时收敛慢；卫星-only OSB 对接收机码偏差无效。**本行未开 `enableIonoEstRobust`**，与 §8 中 ALGO + robust 标志 + DCB 的 2880/2880 属不同配置，不可互推 | **暂缓（待查）** |
| ISB/IFCB/IFB | `enableIsbIfcbIfb` | 存活率 2880/2880（可复现）；HKWS：ΔN -28.1% / ΔE +57.6% / **ΔU +521.4%**；HKSL：ΔN +89.2% / ΔE +68.8% / **ΔU +207.4%**。**仅存活率提升稳定，N/E/U 改善站间不一致** | **单 GPS 场景 ISB 不可观**，估计不可观参数反引噪声；IFLC 下与 IFB 耦合致 U 向严重恶化 | **待查（不阻塞定版）** |
| GPT3+VMF3 | `enableGpt3Vmf3` | fallback（无网格）：ΔN -40.6% / -41.7%，ΔE +83.5% / -51.8%（站间相反），ΔU +55.3% / +47.6%；**真网格**（`useGpt3Grid=true` + `gpt3_5.grd`）：ΔN -0.3% / +4.3%、ΔE +0.1% / -0.9%（≈基线），**ΔU -1.9% / -1.3%** | 必须配 `useGpt3Grid=true` + TU Wien 官方 5°×5° 网格文件；旧记「U 向恶化 +144~192%」为 vmf3 分母缺失 + ZHD 单位错配所致，已由 FIX-ALG-029 闭环 | 第二（网格就绪后） |
| IERS2010 | `enableIers2010`（+ `enableAt1S2`） | 仅 `enableIers2010`：Δ 全为 0.0%（S1/S2 大气潮项已**内含**于该开关，位移 mm 量级，被基线噪声淹没）；`enableIers2010 + enableAt1S2`：ΔU **-29.9% / -54.6%**，ΔN -49.3% / +36.7%、ΔE +62.7% / +35.9%（站间不一致） | **归因警告**：`enableAt1S2` 在代码上只是把解算路由到 `PppCoreEx` 扩展路径（`PppBiasModel.extraDim()` 不增状态、不加任何改正项），故该 ΔU 只能来自解算路径差异，**不得当作「S1/S2 大气潮 U 收益」**，须与已知问题「PppCoreEx 浮点退化」一并复核；另缺 Hardisp 频率依赖 Love 数，当前实现非完整 IERS2010 | 待查（勿据此宣称 U 向收益） |
| OSB 偏差改正 | `enableOsb` | 毫米~厘米级（观测值特异性偏差） | **PPP-AR 前置**：OSB 同时是 FCB 整数性来源 | 条件开启 |

### 1.4 限制

- E 向 2mm 已到极限，浮点侧继续优化空间集中在 U 向；**目前唯一可归因且跨站同向有效的 U 向项是 GPT3 真网格（ΔU -1.3%~-1.9%）**——`enableAt1S2` 组合下的 ΔU -30%~-55% 实为 `PppCoreEx` 路径差异，不能计入大气潮收益（见 §1.3 归因警告）
- ISB/IFCB/IFB 与 IFLC 存在耦合（U 向 +207%~+521% 严重恶化），仅有「存活率提升至 100%」可稳定复现；**不可用「改用非组合 EST」作为规避手段**——EST 在 HKWS 同样恶化（ΔU +139.7%）、在 HKSL 直接 0/2880 无解
- 浮点优化项的收益**站间不可迁移**：ISB/IFCB/IFB 的 N 向（-28.1% vs +89.2%）、GPT3 fallback 的 E 向（+83.5% vs -51.8%）在两站完全反向，EST 在 HKSL 直接无解——均禁止用单站结论外推；**跨站同向且可归因的仅 GPT3 真网格一项（U -1.3%~-1.9%）**
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
| Fix-and-Hold | `cfg.pppRtk.enableFixHold` | 锁定固定解 |

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

✅ 推荐开启 / ☑ 条件开启（前提满足） / ⚪ 无收益不推荐 / ⛔ 有害避免 / ⚠️ 待查（实测存疑，暂不默认开启）

### 浮点 PPP 体系

| 开关 | 开阔静态 CORS | 城市动态 | 沿海站 | 单 GPS | 多系统多频 |
|------|:---:|:---:|:---:|:---:|:---:|
| 非组合 EST | ⚠️ | ⚠️ | ⚠️（HKWS ΔU +139.7%） | ⚠️ | ⚠️（HKSL 0/2880 无解） |
| GPT3+VMF3（含网格） | ✅ | ☑ | ☑ | ✅ | ✅ |
| IERS2010 | ☑ | ⚪ | ⚠️（`enableIers2010` 单独 0%；`+enableAt1S2` 的 ΔU -30%~-55% 实为 PppCoreEx 路径差异） | ☑ | ☑ |
| ISB/IFCB/IFB | ⚪ | ⚪ | ⚪（ΔU +207%~+521%） | **⛔ 不可观** | ⚠️（仅存活率提升可复现） |
| OSB | ☑ | ☑ | ☑ | ☑ | ✅（AR 前置） |

> 降级依据：EST / ISB 两行整体降为「⚠️ 待查」，IERS2010 行的沿海站格降级——HKWS/HKSL 双站全天实测（§1.3、[PPP_Extra_Optimizations.md §7.4](PPP_Extra_Optimizations.md)）未复现任何可归因的 N/E/U 改善，原矩阵依据的无数据集标注结论已作废。IERS2010 单独开启实测无害但无收益（Δ=0.0%），故非沿海格保留「条件开启」。待 U 向劣化根因（含 `PppCoreEx` 路径差异）定位后再恢复评级。

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
| enablePppRtkAR / cfg.pppRtk.enableFixHold | ✅（产品链路验证后） | 不可测 |

---

## 6. 场景 Preset（配置组合蓝图）

> 以下为**配置组合建议**，不涉及新算法；每项收益以实测验收为准，定版冻结阶段仅组合既有开关。**EST 目前为「待查」项（§1.3 / §5）**：LANDSLIDE / OPEN_SKY 中的 EST 仅作候选，启用前须先按 HKWS/HKSL 口径复核，未复核前默认按 IFLC 执行。

| Preset | 体系 | 配置组合 | 目标 | 应用 |
|--------|------|----------|------|------|
| LANDSLIDE | 浮点 + PPP-AR | 非组合 EST（待复核）+ GPT3（网格）+ IERS2010 + `enablePppAR` + `enablePppPartialAR` + `enablePppArFixHold` | E/N < 1cm, U < 2cm | 滑坡/大坝监测 |
| OPEN_SKY | 浮点 | 非组合 EST（待复核）+ GPT3（有网格）+ IERS2010 | E/N < 2cm, U < 5cm | 常规测绘/CORS |
| URBAN_CANYON | 浮点（抗差） | IFLC + 放宽 maxinno + 关闭 GPT3（网格未就绪时 U 向风险） | 不断星、坐标不飞 | 城市动态 |

**验收前检查清单（D 类前置）**：LANDSLIDE 开 AR 前确认 `fcbFile` 加载、`nav.fcbWl != null`、ionoopt 与产品频点一致。

---

## 7. 与既有文档的关系

| 文档 | 职责 |
|------|------|
| 本文档 | 体系认知：三体系划分、判定、验收指标、测试纪律、场景矩阵 |
| [PPP_Extra_Optimizations.md](PPP_Extra_Optimizations.md) | 实现细节：各模块原理、代码侵入点、单元测试清单、逐项对照原始数据 |
| [ALGORITHM_FIX_LOG.md](ALGORITHM_FIX_LOG.md) | 算法修复记录：FIX-ALG-001~029（按编号索引） |
| [PPP_Test_Plan.md](archive/PPP_Test_Plan.md) | 测试计划（已归档） |

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