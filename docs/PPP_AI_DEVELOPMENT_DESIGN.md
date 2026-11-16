# PPP 体系 AI 接入开发设计

**文档性质**：开发设计（PoC 导向，非实施定稿）
**版本**：v1.7（2026-11-15）
**基线**：rtklib-java v3.5.0（当前 `pom.xml`；I-1 对照基准仍为冻结的 v3.4.0 tag）
**依据**：[PPP_AI_ASSESSMENT.md](PPP_AI_ASSESSMENT.md) v1.1、《GNSS AI 增强开发文档》、[ALGORITHM_FIX_LOG.md](ALGORITHM_FIX_LOG.md) FIX-ALG-054/057/059/060
**定位**：本文不回答「该不该接」（该问题由 [PPP_AI_ASSESSMENT.md](PPP_AI_ASSESSMENT.md) 回答），只回答「若按该评估 §11 的 P0~P4 做技术验证，三个接口在代码上怎么落、注入点在哪、边界在哪、哪些当前做不了、用什么数据验证」。

**本版说明**：v1.0 中的全部示例代码已移除（三处示例均与代码事实不符，见 §0）。v1.2 补入**数据批次事实与复核计划**（§2.4、§5.3），修正 D 的「零收益」归因口径；v1.3 新增 §1.4「AI 失效必须无缝退化为无 AI」，把失效安全约束 S1–S6 升为与 P0 同级，契约测试补 T9–T11；v1.4 校正**终点口径**：本仓库无真实数据，收益结论不产出，终点改为「链路跑通 + 训练代码落地 + 开源移交」（§12）。 v1.5 补入 B 注入点的实现选择（§4.4「先记行序、后施加」）与契约测试落地状态（§8）；v1.6 新增 §7.1 **Step 2 验证结论**：记录 `KalmanFilter.update()` 协方差非正定的致命 Bug 根因（EJML 0.41 `SimpleMatrix.multiply` 别名复用 + 病态 S 矩阵放大浮点误差 + 状态压缩导致的负对角元永久锁死）、谱投影三层修复口径、动态 PPP 确定性基线拦截率（91.5% @ 0.5 m²），以及**浮点 / PPP-AR / PPP-RTK 三层链路均已受 KF 层强制正定化保护**的验证证据与覆盖边界。 v1.7 收口交付状态：staging 侧已验证完整的 A / B 实现与训练工具链并入主线，§7 阶段表逐项标注 Step 1–5 完成情况，新增 §12.4 交付状态表；版本号统一为 3.5.0。

---

## 0. 修订记录

### v1.1 相对 v1.0（代码事实校正）

| # | v1.0 表述 | v1.1 修正 | 依据 |
|---|---|---|---|
| 1 | 标题「收益化设计」，定位「收益导向」 | 标题「开发设计」，定位「PoC 导向」；收益量测保留为约束，不再作为文档主轴 | 评审意见 |
| 2 | A 注入点「`updateStat` 之后、`commit` 之前」 | **该位置不存在**。`commit` 发生在 `updateStat` 调用之前，A 的真实注入点为「`updateStat` 返回后、trace 之前」；PPP-AR 链路还须位于 AR 之后 | `PppCore.java` `pppos` 尾部（updateStat → trace） |
| 3 | A 上下文 `positionVar` 注释为「P 对角元 E/N/U」 | 实为 **ECEF X/Y/Z** 状态对角元（`qr[i] = P[i*nx+i]`，i=0..2） | `PppCore.updateStat` |
| 4 | B 注入 `R[i*nx+i] *= s` | **维度错误**。R 为 **nv×nv**（nv=有效观测行数），施加对象是 `R[j*nv+j]`，不是 `nx×nx` | `PppCore.pppRes` R 装配段 |
| 5 | B「每历元一次批量推理」 | 需补充**历元键缓存**：`pppRes` 每历元最多调用 16 次（2×`MAX_ITER`），不缓存会重复推理 16 次 | `PppCore.pppRes` 调用点 |
| 6 | D「拒绝固定不改 `rtk.xa`」 | **仅在首历元成立**。`xa` 在后续历元是 `rtk.xa` 的别名，写 `xa[idx]` 即改写 `rtk.xa`；拒绝固定还会残留 `ssat.fix`、`Pa` 清零、`nfix++` 脏状态 | `PppRtkAmbFix` IF / UC 两条固定路径 |
| 7 | 三接口并列、D 亦在开发范围 | 对齐评估 §11：**PoC 范围仅 A / B，D 不在范围**（结构性阻塞未解，见 §5） | `PPP_AI_ASSESSMENT.md` §11 |
| 8 | `AiProvider` 三个方法都写成 `default` | 现状是 `fixValidator()` / `obsCorrector()` 无 default、`refStarValidator()` 有 default；新增 PPP 方法**照 `refStarValidator()` 模式加 default**，不改已冻结方法 | `AiProvider` |
| 9 | `AiHooks` 新增 resolve 示例未含 `validateAiOpts` | 现有 resolve 均先调 `validateAiOpts(rtk)`；新增 resolve 必须同构 | `AiHooks` |
| 10 | `AiOpts` 新字段示例仅给声明 | `copyFrom()` 与 `validate()` 均为逐字段手写，新字段必须**同时补入两处**；字段名不带 `ai` 前缀 | `AiOpts` |

### v1.2 相对 v1.1（数据事实校正）

| # | v1.1 表述 | v1.2 修正 | 依据 |
|---|---|---|---|
| 11 | D「当前零收益」归因只写「固定坐标不进输出」 | 归因不完整。**AR 链路本身在实测批次上就跑不出固定解**：UC/EST（`nf=3`）链路整体退化，`0015 hour00` 实测 `fix=0 / ns=1 / vsat0=0`，滤波器退化为单参数 | `ALGORITHM_FIX_LOG.md` FIX-ALG-060 |
| 12 | 未区分实测数据批次的开源属性 | 上述退化证据取自 **0015 / 0017 / 0020（外部抓取）与 0018（实时落盘）**，**均非仓库内开源数据**；而 PPP 浮点 / PPP-AR 的验证数据是 IGS **ALGO 2024 DOY 060 + WUM 产品**（开源、可自动下载）。两者不是同一批数据，退化结论**只对该批次成立**，须在开源数据上复核（§2.4、§5.3） | `PppValidationTest`、FIX-ALG-060 附 |

### v1.3 相对 v1.2（安全攸关校正）

| # | v1.2 表述 | v1.3 修正 | 依据 |
|---|---|---|---|
| 13 | 降级只写「按不干预处理」，未定义失效方向、施加原子性、熔断与恢复 | 新增 §1.4：把「AI 失效必须无缝退化为无 AI」升为与评估 P0 同级的第一约束（S1–S6），并明确 A / B 的原子施加与单调性 | 安全攸关要求 |
| 14 | 契约测试 T3 只断言「不干预」 | T3 改为断言**降级后输出与未注入逐位一致**；新增 T9（原子性）、T10（熔断与恢复）、T11（单调性与不推翻确定性结论） | §1.4 S1/S2/S4/S5 |

| 15 | D 不注入的理由只写「拒绝不可回滚」 | 归因升级为契约级：**D 的当前结构不满足 S2（施加原子性）**，故不可注入；T9 在 D 上预期失败，该失败即证据 | §5.5、§5.6 |

### v1.4 相对 v1.3（终点口径校正）

| # | v1.3 表述 | v1.4 修正 | 依据 |
|---|---|---|---|
| 16 | §7 阶段表把「收益评估」隐含为可执行终点，§10 / §11 默认收益可量 | **收益结论需要真实数据，本仓库没有**。终点改为三段：链路跑通 → 训练代码落地 → 收益结论交由社区（§12.1）。A0–A3 在本仓库只做链路自测，不得写收益结论 | 交付范围确认 |
| 17 | 交付物只列 core 侧 SPI / 注入点 / 契约测试，训练侧仅作为「模型侧契约」提及 | **训练代码列为必须交付物**，与推理契约同批落地：数据集格式、组装、训练、导出、采集器、模型卡（§12.2） | 工程项目要求 |
| 18 | 「开源数据可用于收益量测」的表述过宽 | D1 / D2 只够**链路跑通与自测**；单站单日、相邻历元强相关，拦截率差异不具统计意义（§11、§12.1） | 数据覆盖事实 |

### v1.5 相对 v1.4（实现选择落地）

| # | v1.4 表述 | v1.5 修正 | 依据 |
|---|---|---|---|
| 19 | §4.4 的 B 注入点只写「`pppRes` 内」 | 明确实现选择：**「先记行序、后施加」**——随 `nv++` 记录 `rowSat` / `rowFrq`，待 R 装配完成后一次性调用 `PppAiInject.applyVarianceScale`，不对半构造的 R 施加；行序映射缓冲挂在 `Rtk.workBufPpp`，仅在 provider 已注入时惰性分配 | `PppCore.pppRes` |
| 20 | §8 仅列契约测试清单 | 落地 T1–T5、T7、T9–T11（`AiPppStage1Test` 14 条，全绿）；T8 的注入点位置断言因 Step 1 无 AR 固定历元可构造，顺延至 Step 3 | `AiPppStage1Test` |

### v1.6 相对 v1.5（Step 2 验证结论与正定化护甲）

| # | v1.5 表述 | v1.6 修正 | 依据 |
|---|---|---|---|
| 21 | 全文未记录滤波器协方差正定性保障 | 新增 §7.1：`KalmanFilter.update()` 的**对称化 + 谱投影 + 对角钳位**三层强制正定化；记录静态 PPP X 轴协方差负值永久锁死 Bug 的根因与修复口径 | `KalmanFilter.java` |
| 22 | 「三层链路覆盖」只有代码检查，无实测证据 | §7.1 补入浮点 / PPP-AR / PPP-RTK 三链路覆盖结论与实测证据；PPP-AR 新增 `PppArValidationTest#testArEntryCovariancePsd` 在 ALGO 2024 DOY 060 + WUM 上实测；PPP-RTK 因开源数据 NL 未达 FIX，仅静态代码检查（已在文档中明示） | `PppArValidationTest`、`PppRtkCore`、`PppRtkAmbFix` |
| 23 | Step 2 拦截率只有「待量」 | 补入动态 PPP 确定性基线拦截率 **91.5% @ 0.5 m²**（自测数字，非收益结论，口径见 §12.1） | Step 2 验证 |

### v1.7 相对 v1.6（交付状态收口）

| # | v1.6 表述 | v1.7 修正 | 依据 |
|---|---|---|---|
| 24 | §7 阶段表只有「工作量」，无落地状态 | 逐项标注 Step 1–6 与 D-1/D-2/D-3 的实际状态；D 明确「未执行、阻塞于 D-2」，不伪装成已完成 | 本仓库代码现状 |
| 25 | A / B 的实现只存在于暂存目录，主线缺失 B 的加载分支与采集器 | A / B 全量并入主线；`OnnxAiProvider` 补 B 常量、加载分支、`isPppVarianceAvailable()`、`close()` 分支 | `OnnxAiProvider`、`PppVarianceScalerImpl` |
| 26 | 宿主侧以「接口三可用」当总开关 | 改为 `isAnyInterfaceAvailable()`：任一接口可用即注入，避免仅接口三存在时 A / B 被连坐关闭 | `PppRtkSsrReplay` |
| 27 | 接口 B 上下文无标称 σ，采集侧无法构造特征 | `PppObservationContext` 增 `nominalSigma`；core 侧由 `var` 对角元开方构造，未命中行取 `NaN` | `PppObservationContext`、`PppAiInject` |
| 28 | §12 只写「三段交付」的定义 | 新增 §12.4 交付状态表，逐段给出可核对的证据（测试名 / 命令 / 产物） | v3.5.0 |

---

## 1. 设计原则

### 1.1 每条设计必须绑定一个经验常数

不写「为了接 AI 而接 AI」的接口。每个接口的设计都从「它替换的是哪个经验常数、收益在哪个维度、用什么量测」反推。

| 接口 | 替换的经验常数 | 收益维度 | 收益量测方式 |
|---|---|---|---|
| A `PppSolutionValidator` | 「`SOLQ_PPP` 一律可信」这个隐含假设 | 可靠性：错误解输出率 | 参考轨迹误差 vs 降级标记 |
| B `PppVarianceScaler` | `PppCore.varerr` 的解析方差模型 | 可靠性：NLOS / 多路径导致的方差低估 | 参考轨迹误差 + 方差校准度 |
| D `PppRtkFixValidator` | `ratio < 3.0` 这个单点门限 | 可靠性：错误固定率 | 固定解误差 vs 拒绝标记 |

### 1.2 三条共同约束

- **只读原则**：只改权重、门控、标记，不写状态、先验、整数。
- **降级不干预**：`null` / `NaN` / 非有限 / 抛异常 → 按不干预处理，禁止 fail-closed。安全攸关场景下该条升级为硬约束 S1–S6（§1.4），优先于收益判据。
- **I-1 零回归**：未注入时行为与基线（v3.4.0）逐位一致。

### 1.3 收益维度声明

三个接口都只做**可靠性**，不做**精度**。AI 不会让「差环境变得能固定」，只会「在差环境里少输出错解」。目标应写为「降低错误解输出率」，而非「提升固定率 / 精度」。

### 1.4 安全攸关：AI 失效必须无缝退化为无 AI（v1.3 新增）

**优先级声明**：在安全攸关场景（航空、铁路、农机自动驾驶、港口机械等），**AI 失效时的行为正确性优先于 AI 的准确率**。一个 95% 准确但失效时会污染状态的接口，不如一个 60% 准确但失效时零副作用的接口。因此验收顺序是：**先证明失效安全（S1–S6），再讨论收益（§10）**。

**「无缝退化」的严格定义**（四条同时成立才算达标）：

1. **输出结构不变**：历元数、`sol.stat` 取值域、`sol.rr` / `sol.qr` 布局、输出文件格式均与未注入一致；
2. **数值逐位一致**：降级历元的解与未注入 AI 的解**逐位相同**（不是「接近」），故下游可在任何时刻开关 AI 而不产生可观测跳变；
3. **无副作用残留**：AI 分支不留下任何被修改的状态（`rtk.x` / `rtk.P` / `Ssat.fix` / `nfix` / bias / 模糊度）；
4. **无新增缺测**：降级不得导致该历元解算失败或跳过（唯一例外是 A 主动置 `SOLQ_NONE`，那是接口语义，不是降级副作用）。

**六条失效安全约束（S1–S6，与评估 P0 同级，不因数据不足而放宽）**：

| # | 约束 | 具体要求 | 违反后果 |
|---|---|---|---|
| S1 | **失效方向固定 fail-open** | `null` / 长度不符 / `NaN` / `Inf` / 越界 / 抛 `Throwable` / 超预算，一律等价于「本历元未注入」；禁止 fail-closed，禁止部分施加 | AI 故障升级为定位故障 |
| S2 | **施加原子性** | 先完整取回输出 → 全量校验 → 写入中间数组 → 最后用不可抛的赋值循环一次性施加；禁止边校验边写 | 半改状态不可回滚（D 即栽在此处，§5.5） |
| S3 | **单调性（只朝保守方向）** | A 只能把 `SOLQ_PPP` 降为 `SOLQ_NONE`，不得提升任何状态；B 的因子只能 ≥ 1.0（膨胀），不得收缩方差 | AI 引入新的乐观错误 |
| S4 | **不推翻确定性结论** | A 只在 `sol.stat == SOLQ_PPP` 时执行；AR 已写 `SOLQ_FIX` 的历元 AI 不得改写（PPP-AR 注入点必须在 AR 之后，§3.4） | AI 抹掉确定性固定解 |
| S5 | **预算度量 + 熔断 + 显式恢复** | core 无法中断同步推理，故用「度量 + 丢弃 + 熔断」替代超时：单次耗时 > `pppAiMaxInferenceMs` → 该次结果丢弃（不干预）；连续 `pppAiMaxConsecutiveFailures` 个历元降级 → 本会话熔断，等价于未注入；熔断状态是 `Rtk` transient 字段，不落盘、不跨实例；恢复需显式重注入或新建 `Rtk` | 故障持续放大，实时性被拖垮 |
| S6 | **可观测但不可失败** | 每次降级必须计数 + `AI_DEGRADE` trace；告警单次化（沿用接口一 `rtk.aiDegradeLogged` 模式），禁止每历元刷日志；trace / 日志自身抛异常必须吞掉 | 静默降级，或日志成为新故障点 |

**三级防线（AI 熔断 ≠ 失去保护）**：

| 层 | 机制 | 受 AI 熔断影响 | 说明 |
|---|---|---|---|
| L1 | 确定性基线（`qr` 阈值降级，§3.2） | **否** | 纯确定性代码，不依赖 AI；AI 熔断后仍生效 |
| L2 | AI 接口（A / B） | 是 | 失效即回落到 L1 状态 |
| L3 | 输出标记 / 下游分流 | 否 | `SOLQ_NONE` 语义由 core 决定，不由 AI 决定 |

「退化为无 AI」= 回落到 L1，而不是回落到「无任何保护」。这也是 §7 中 Step 2 必须先做的原因之一：**确定性基线既是 A 的收益分母，也是 AI 失效时的兜底防线**。

**默认关闭（opt-in）**：`AiHooks` 默认无 provider（`TL` 空），`Rtk` 三个 AI 字段默认 `null`。安全攸关场景的默认配置必须是「无 AI」，启用须显式注入，注入方可随时 `AiHooks.clear()` 回退。这与现状代码一致，不新增 enable 开关（沿用既有设计）。

**D 的定位由此明确**：D 的拒绝固定需要回滚 `xa` / `Pa` / `ssat.fix` / `nfix`（§5.5），而 `xa` 在后续历元是 `rtk.xa` 的别名、原值已被覆盖——**S2 在 D 的当前结构下不成立**。这不是「先做再修」的工程债，而是安全攸关场景下不可接受的失效模式，故 D 不进入注入实现（§5.6）。若未来要接 D，必须先把固定流程重构为「算候选 → 校验 → 提交状态」三段，使「拒绝」严格等价于「未发生」，才满足 S2。

---

## 2. 与评估结论的关系及前置

### 2.1 评估结论（引用，不改写）

[PPP_AI_ASSESSMENT.md](PPP_AI_ASSESSMENT.md) v1.1 给出两层结论：

- **交付层面**：PPP / PPP-AR / PPP-RTK 本阶段**不新增 AI 接口**（§8）。
- **技术实现层面**：允许按 **P0~P4** 开展 PoC，**不要求 G-1 / G-3 / 统计功效达标**，但 P0 四条安全契约不可降；技术通过 ≠ 有效（§11）。

本文是**技术实现层面**的落地设计，不构成对交付结论的推翻。

### 2.2 本文与评估 §11 启动顺序的映射

| 评估 §11.6 步骤 | 本文对应章节 |
|---|---|
| Step 1 框架层（`AiProvider` default + `AiHooks` + `PppCore.updateStat` 注入点 + 失效安全框架 S5/S6） | §6、§1.4、§3.4 |
| Step 2 候选 A 的确定性 impl（`qr` 阈值降级） | §3.2（P4 基线） |
| Step 3 候选 A 的模型 impl | §3.3–§3.7 |
| Step 4 候选 B 的独立 SPI（可选） | §4 |
| （D 未列入） | §5 说明为何不在范围 |

### 2.3 前置：A 的收益分母必须先量

A 的收益上限 = 「`qr` 阈值确定性方案能拦多少」的补集。评估 §5.1 / §9 条件 3 与 §11.3 P4 均要求先量出该拦截率（全仓库当前无实现）。**该确定性分支不是 A 的前置条件，而是 A 的收益分母**；不量出这个数，A 的模型立项是盲目的。

### 2.4 数据事实：三套数据，属性不同（v1.2 新增）

本文所有「实测」结论都必须标注数据批次。当前仓库涉及三套数据，**只有第一套是仓库内可复现的开源数据**：

| 数据集 | 内容 | 来源 / 许可 | 用途 | 是否在仓库内 |
|---|---|---|---|---|
| **D1 IGS ALGO 2024 DOY 060** | RINEX3 观测 + `WUM0MGXRAP_20240600000` SP3/CLK/BIA（GFZ/IGS） | IGS Data Policy，开源；`PppValidationTest` 可自动下载 | PPP 浮点、PPP-AR（IFLC / EST）验证 | 是（`test-data/gnss-validation/ppp/algo`、`.../product`） |
| **D2 WUH20 + `SSR*01CAS0` 2026-10-26** | 观测流 `obs_wuh20_20261026.rtcm3` + SSR 流 `ssr_ssr*01cas0_20261026.rtcm3` | 公开 NTRIP 流（WUH20 观测 / CAS SSR），见 `SSR_ACCESS_PLAYBOOK.md` | PPP-RTK（IFLC）链路联调、SSR 解码 | 是（`test-data/gnss-validation/rtcm`） |
| **D3 0015 / 0017 / 0020 / 0018** | 逐小时 RINEX + 同源 SSR | **外部抓取 + 实时落盘，非仓库内开源数据**（FIX-ALG-060 附自述「原 0015/0017/0020 使用的是外部抓取数据」） | PPP-RTK AR 固定率实测、FIX-ALG-054~060 排雷 | 否 |

**因此：**

1. **PPP 浮点 / PPP-AR 的结论建立在开源数据 D1 上**，可复现、可重跑。
2. **PPP-RTK UC 链路退化（`fix=0 / ns=1`）的证据来自 D3**，D3 非开源、不可复现。该结论**不能直接推广为「UC 链路在任何数据上都跑不出固定解」**。
3. D2 是公开流数据，且**本身就是 UC 之外的 IFLC 载体**（`nf=2`）；要在开源数据上试 UC 链路，D2 是现成的候选（需按 §5.3 的条件改造）。
4. **这套数据只够链路跑通与自测，不够产出收益结论**：D1 是单站单日，D2 是单日单流，均无跨环境覆盖与判错真值。收益结论的缺口由社区用真实数据补（§12.1）。

---

## 3. 候选 A：`PppSolutionValidator`（PPP 解有效性判别）

### 3.1 收益来源

PPP 浮点的真实失效形态不是「米级误差当厘米级输出」，而是 `SOLQ_PPP` 没有精度等级——3 cm 和 3 dm 都是 `SOLQ_PPP`，下游无法按状态分流。A 的收益是：**在 `SOLQ_PPP` 之后补一个降级标记，把不可信历元从「输出」变成「不输出」**。

### 3.2 确定性基线（P4，非 AI）

`updateStat` 已把位置方差导出到 `sol.qr[0..2]`（ECEF X/Y/Z 对角元），但 `stat` 定级未使用它。确定性对照分支只需在 `updateStat` 内或其后读取该值并按阈值降级：

- 判据：`posVar = qr[0] + qr[1] + qr[2] > pppQrThreshold` → 置 `sol.stat = SOLQ_NONE`；
- 该分支**默认关闭**（`pppQrThreshold = Double.MAX_VALUE` 时恒不触发），保证 I-1。

`deterministicDegraded` 标志在此处产生、随 context 传给 A（§3.3），是 §11 触发结构三计数的唯一来源。**标志的取值口径与分支是否启用解耦**：无论阈值是否打开，都按 `posVar > pppQrThreshold` 计算标志，只在阈值打开时才真正写 `sol.stat`（原因见 §3.5）。

### 3.3 SPI 定义（字段表，无示例代码）

`PppSolutionValidator` 只有一个方法，返回解有效性置信度 ∈ [0,1]；`null` / `NaN` / 非有限 / 抛异常 → 按 1.0（不干预）；禁止修改 `Rtk` / `Ssat` 状态。

上下文 `PppSolutionContext` 字段：

| 字段 | 类型 | 语义（v1.1 校正） |
|---|---|---|
| `epoch` | int | 历元号 |
| `ns` | int | 参与解算的卫星数 |
| `positionVar` | double[3] | **ECEF X/Y/Z** 位置状态对角方差（`qr[0..2]`），**不是 E/N/U** |
| `residuals` | double[] | PPP 非差验后残差（长度 = 有效观测行数） |
| `elevation` | double[] | 逐星仰角 |
| `snr` | double[] | 逐星 SNR |
| `convergenceMetric` | double | 滤波收敛度（迭代次数 / 新息 RMS） |
| `biasState` | int | 偏差状态标志 |
| `deterministicDegraded` | boolean | 确定性 `qr` 基线是否已把本历元降级为 `SOLQ_NONE`（归因字段，§3.5 / §6.3） |

`deterministicDegraded` 是**归因字段**，不改变注入点行为。理由：A 的收益是「确定性基线之上的增量」，若确定性已降级，A 再降级是重复；若确定性未降级而 A 判不可信，那才是 A 的独立贡献。没有这个标志，评估时分不清「A 拦的」与「确定性拦的」。

注入点照常执行、不做 `if (!deterministicDegraded)` 短路：该历元已是 `SOLQ_NONE` 时 A 的写入是幂等的，短路反而会让「AI 独立触发次数」在两种分支下走不同代码路径、难以对拍。

### 3.4 注入点（v1.1 校正）

**纯 PPP（`PppCore.pppos`）**：调用顺序是「迭代收敛 → commit（`xp/Pp` 写回 `rtk.x/rtk.P`）→ 若 `stat == SOLQ_PPP` 调 `updateStat` → trace」。因此**不存在「`updateStat` 之后、`commit` 之前」这个位置**；A 的真实注入点为 **`updateStat` 返回后、`PppTrace.tracePppResult` 之前**，且只在 `rtk.sol.stat == SOLQ_PPP` 时执行（不得触碰 AR 已写入的 `SOLQ_FIX`）。

**PPP-AR（`PppCoreEx.ppos`）**：两条路径（扩展状态 / 简化路径）的 `applyAmbiguityResolution` 都在 `updateStat` **之后**调用。故 A 在 PPP-AR 链路的注入点必须位于 **AR 之后**，否则 AR 写入的 `SOLQ_FIX` 会覆盖 A 的降级标记（A 先降 `SOLQ_NONE`，AR 的 `stat == PPP/FLOAT` 守卫随之失效，导致本应固定的历元不再固定）。

**PPP-RTK（`PppRtkCore.ppprtkos`）**：`PppRtkAmbFix` 在 `updateStat` **之前**调用，`updateStat` 之后即无 AR。A 若接入 PPP-RTK，注入点同样为 `updateStat` 之后，语义与纯 PPP 一致。

> 结论：A 不是「在 `PppCore` 一处注入」即可覆盖三条链路。纯 PPP 与 PPP-AR 的注入点都落在 `updateStat` 之后，但 PPP-AR 多一个「必须在 AR 之后」的约束；实现时须按链路分别放置，并以契约测试 T8 校验位置（§8）。

**v1.5 实现选择**：Step 1 把 A 收敛到**唯一**注入点 —— `PppProcessor.processEpoch` 中「三条链路分派完成、返回前」。该处已在 `PppCoreEx.ppos`（含 AR）与 `PppRtkCore.ppprtkos` 全部返回之后，天然满足「AR 之后」；纯 PPP 路径同样在其 `updateStat` 之后。`PppAiInject.applySolutionValidator` 首行解析为 `null` 即返回，且只对 `SOLQ_PPP` / `SOLQ_FLOAT` 生效，`SOLQ_FIX` 与 `SOLQ_NONE` 一律不进入（S4 单调性）。T8 的位置断言因此落在「AR 已固定的历元经 `processEpoch` 后仍为 `SOLQ_FIX`」，与 §8 的 T11 同源验证。

### 3.5 配置字段

| 字段 | 默认值 | 取值范围 | 说明 |
|---|---|---|---|
| `pppSolutionMinConfidence` | 0.5 | [0,1]，惰性钳位 | A 的置信度阈值 |
| `pppQrThreshold` | `Double.MAX_VALUE` | [0, +∞) | 确定性基线门限，默认等于关闭 |

`pppQrThreshold` 属于确定性分支（§3.2），**默认 `Double.MAX_VALUE` 即分支永不触发**，保证 I-1：默认配置下 `posVar > pppQrThreshold` 恒为 false。

`deterministicDegraded` 标志的取值口径必须与分支是否启用**解耦**：无论 `pppQrThreshold` 是否打开，都按 `posVar > pppQrThreshold` 计算标志，只在阈值打开时才真正写 `sol.stat = SOLQ_NONE`。原因是 §11 的 A2 组（仅 AI、不做确定性降级）与 A3 组（确定性 + AI）必须喂给模型同一分布的特征——若 A2 组标志恒 `false`、A3 组标志有 `true`，模型输入分布在两组间就漂移了。默认 `Double.MAX_VALUE` 时标志恒 `false`，A2 组即自然满足该口径。

### 3.6 降级与 I-1

| 条件 | 行为 |
|---|---|
| `sv == null` | 不进分支，行为与基线逐位一致 |
| 返回 `null` / `NaN` / 非有限 / 抛异常 | 按 1.0（不干预） |
| `confidence >= 阈值` | 不干预 |
| `confidence < 阈值` | 置 `sol.stat = SOLQ_NONE`（仅当当前为 `SOLQ_PPP`） |

**I-1 可证**：未注入时分支不存在；注入但输出全 1.0 时 `confidence < 阈值` 恒为 false，`sol.stat` 不被改写。确定性分支默认关闭；`deterministicDegraded` 的计算只读 `sol.qr`，不写任何状态，对基线无影响。

**失效安全（§1.4 S1–S6）**：A 的施加是单点写入（只写 `sol.stat`），但必须**先完成 provider 调用与全部校验、再写入**——`confidence` 的有限性与 `[0,1]` 范围检查全部通过后才赋值，校验失败走 S1（等价未注入）。A 不写 `sol.rr` / `sol.qr` / `rtk.x` / `rtk.P` / `Ssat.fix`，故不存在回滚需求（S2 天然满足）。降级是单向的（S3），且只在 `SOLQ_PPP` 上生效（S4）。

### 3.7 收益量测协议

| 指标 | 定义 | 判据方向 |
|---|---|---|
| 错误解输出率 | 输出历元中误差 > 阈值的占比 | 下降 |
| 有效输出率 | 输出历元中误差 ≤ 阈值的占比 | 下降 ≤ 1 pp |
| AI 增量 | 确定性基线之上多拦的错误历元数（= §6.3「AI 独立触发次数」里判错的那些） | 须 > 0 且统计显著 |

A 的量测数据用 **D1（ALGO，开源）**：有独立参考坐标（IGb14/ITRF2014），可直接算误差。

---

## 4. 候选 B：`PppVarianceScaler`（PPP 观测方差缩放）

### 4.1 收益来源

`PppCore.varerr` 的解析方差模型是经验公式（仰角 + SNR + 频点因子）。在多路径 / NLOS 场景下会系统性低估方差。B 的收益是：**用 AI 预测的方差膨胀因子，修正解析模型的低估**。

注意：B 只有可靠性收益，没有精度收益。单边钳位只能膨胀不能收缩。

### 4.2 路径选择：新增独立 SPI，不碰 RTK 侧降级语义

| 路径 | 做法 | 代价 |
|---|---|---|
| 路径 1 | 放宽 `RtkCore` 的 `null` 检查（`biasMeters == null` 时按全 0 处理） | 改已冻结降级语义，重跑 I-1，降级分支从两条变三条 |
| 路径 2（推荐） | 新增独立 SPI `PppVarianceScaler` | 与 A 同等量级接口工作，但不碰 RTK 侧降级语义 |

评估 §4.3 已明确：接口三的 impl 返回 `biasMeters = null` 会让整个改正被丢弃（`varianceScale` 一并作废），等于恒不干预。故 B 必须走路径 2。

### 4.3 SPI 定义（字段表，无示例代码）

`PppVarianceScaler` 批量输出方差膨胀因子 ∈ [1.0, +∞)，行序 = 上下文 `sats` 顺序；`null` / 长度不符 / 抛异常 → 降级为全 1.0。

上下文 `PppObservationContext` 与 `PppSatFeature` 字段：

| 结构 | 字段 | 语义 |
|---|---|---|
| `PppObservationContext` | `epoch`, `ns`, `nf`, `dt`, `sats` | 历元级批量上下文 |
| `PppSatFeature` | `sat`, `sys`, `az`, `el` | 逐星几何与系统 |
| `PppSatFeature` | `snr[nf]` | 逐频 SNR |
| `PppSatFeature` | `resp[nf]`, `resc[nf]` | **非差**伪距 / 载波残差 |
| `PppSatFeature` | `lock[nf]`, `slip[nf]`, `half[nf]`, `vsat[nf]` | 状态标志 |

**`resp` / `resc` 是非差语义，必须与接口三的双差残差严格区分。** RTK 侧接口三的 `resp` / `resc` 是双差残差，PPP 侧是逐星非差残差——两者的量纲、分布、物理含义都不同（双差已消掉接收机钟差与卫星钟差，非差没有）。训练 B 的模型时若误用接口三的训练集，会造成特征分布漂移。**两个接口的训练集不可互换、不可拼接。**

### 4.4 注入点（v1.1 校正）

B 的施加对象是 `pppRes` 装配的 **R 矩阵对角元**，而不是 `varerr` 的返回值本身。

关键事实：

1. **R 维度是 nv×nv**。`pppRes` 内 `varr[nv] = varerr(...)` 之后又叠加 `vart + C²·vari + varRs[i]`，最终 `R[j*nv+j] = varr[j]`。`nv` 是**有效观测行数**（卫星×频点，被 prefit 剔除的观测不占行）。故 B 的索引是 `j*nv+j`（j∈[0,nv)），**不是 `i*nx+i`**。
2. **行序 = `nv` 递增顺序**。被 `maxinno` prefit 剔除的观测在 `nv++` 之前 `continue`，不占 R 行；AI 的 `scale[]` 必须按「被接受观测的递增顺序」映射，不能按原始 obs 下标。
3. **缩放对象是整行方差**。只缩放 `varerr` 返回值会漏掉 `vart + C²·vari + varRs[i]`，导致膨胀被稀释；应缩放最终写入 R 的对角元（等价于在 R 装配处对 `varr[j]` 乘因子）。
4. **必须按历元缓存推理结果**。`pppRes` 每历元最多调用 2×`MAX_ITER = 16` 次（每次迭代一次 prefit、一次 postfit）。若在 `pppRes` 内直接调 AI，会重复推理最多 16 次。上下文须在**历元级**构造并缓存（键含 `rtk.epoch` 与观测集），同历元内复用同一份 `scale[]`；仅历元切换时重新推理。**不可用 `Rtk` 实例单槽缓存**（同实例跨历元会命中陈旧结果）。

施加规则：单值 < 1.0 或非有限 → 该行按 1.0；单值 > `pppVarianceScaleMax` → 钳到上限并告警一次。没有 `biasMeters`：PPP 的偏差会被 `IB(sat, frq)` bias 状态吸收，写偏差进状态会污染 AR 输入。只改方差是安全的。

**v1.5 实现选择**：在 `PppCore.pppRes` 内随 `nv++` 记录 `rowSat` / `rowFrq`（被 prefit 剔除的观测在 `nv++` 之前 `continue`，故不占行），待 R 装配完成后一次性调用 `PppAiInject.applyVarianceScale`，即「先记行序、后施加」，不会对半构造的 R 施加（S2）。行序映射缓冲挂在 `Rtk.workBufPpp`，仅在 provider 已注入时惰性分配（I-1）。

### 4.5 配置字段

| 字段 | 默认值 | 取值范围 | 说明 |
|---|---|---|---|
| `pppVarianceScaleMax` | 4.0 | [1.0, +∞)，惰性钳位 | B 的方差膨胀上限（与接口三的 `varianceScaleMax` 独立，避免共用） |

### 4.6 降级与 I-1

| 条件 | 行为 |
|---|---|
| `vs == null` | `aiScale = null`，不进施加分支 |
| 返回 `null` / 长度不符 / 抛异常 | `aiScale = null` |
| 单值 < 1.0 或非有限 | 该行按 1.0 |
| 单值 > `pppVarianceScaleMax` | 钳到上限，告警一次 |

**I-1 可证**：未注入时 `aiScale == null`；注入但输出全 1.0 时 `R *= 1.0` 不改变数值。

**失效安全（§1.4 S1–S6）**：B 的施加必须两段式——先按 nv 构造 `finalVar[j] = varr[j] * scale[j]`（逐项钳位 + 有限性检查），全部通过后再用不抛异常的赋值循环写 `R[j*nv+j]`。禁止在遍历中边乘边校验：一旦中途抛异常，已乘过的行无法恢复（S2）。任一项校验失败则**整批**回落为 1.0，不做部分膨胀（S1）。`scale < 1.0` 一律按 1.0（S3，不收缩方差）。

### 4.7 收益量测协议

| 指标 | 定义 | 判据方向 |
|---|---|---|
| 方差校准度 | 实际残差平方 / 预测方差（理想值 1.0） | 向 1.0 收敛 |
| 错误解输出率 | 输出历元中误差 > 阈值的占比 | 下降 |
| 收敛速度 | 达到稳定解所需历元数 | 不显著变慢 |
| 精度 | 位置 RMS | 不承诺改善 |

B 的量测数据同样用 **D1（ALGO，开源）**；若后续要覆盖多路径场景，需另找带真值的城市数据集，不得用 D3 代替。

---

## 5. 候选 D：`PppRtkFixValidator`（PPP-RTK 固定校验，当前不在 PoC 范围）

### 5.1 收益来源

`PppRtkAmbFix` 只有 `ratio < 3.0` 一个门限，固定后无验后校验。D 的收益是：**在 ratio 门限之后加一道 AI 门控，拦截「ratio 通过但固定坐标错误」的历元**。

### 5.2 「当前零收益」的完整归因（v1.2 修正）

v1.0/v1.1 只写了「固定坐标不进输出」。完整归因是**三重**，且第三重（数据批次）此前未披露：

| # | 阻塞 | 事实 | 后果 |
|---|---|---|---|
| 1 | **坐标不进输出** | PPP-RTK 位置条件更新未实现，`xa[0..2]` 恒等于 `x[0..2]`；`updateStat` 虽已从 `xa/Pa` 取 `rr/qr`，坐标仍是浮点 | 固定坐标从未输出，校验器无可拦对象 |
| 2 | **拒绝语义不可回滚** | `PppRtkAmbFix` 先写 `xa[idx]`、`Pa` 清零、`ssat.fix=1`、`nfix++`，**最后**才写 `sol.stat = SOLQ_FIX`；且 `xa` 在后续历元是 `rtk.xa` 的别名 | 拒绝固定必留脏状态；「拒绝不改 `rtk.xa`」仅首历元成立 |
| 3 | **AR 链路在实测批次上退化** | FIX-ALG-060 实测 UC/EST（`nf=3`）`0015 hour00`：`fix=0 / ns=1 / vsat0=0`，滤波器退化为单参数，全天无一个可用固定解 | **连「固定解」本身都不存在**，D 连设计验证都只能靠契约测试 |

**第 3 重阻塞的性质必须说清**：它是**批次相关**的，不是链路无关的普适结论。证据来自 D3（0015/0017/0020 外部抓取、0018 实时落盘），**不是仓库内开源数据**（§2.4）。因此存在两种可能，当前无法区分：

- **可能 A**：UC 链路存在与数据无关的结构缺陷 → 换任何数据都跑不出 `ns>1` 的固定解，D 在任何数据上都无对象。
- **可能 B**：该批次观测/SSR 质量或配置（`nf=3`、`navsys=0x20` 仅 GPS、`elmin`）导致退化 → 换开源数据可能恢复正常，D 的收益对象随之出现。

**在区分 A/B 之前，任何「D 在任何数据上都无收益」的表述都是超范围推断。**

### 5.3 复核计划：契约测试先行，再用开源数据试链路（v1.2 新增）

口径（用户确认）：**D 现在只能靠契约测试；契约测试完成后，单独用开源数据试链路。** 落地为三步：

| 步 | 动作 | 数据 | 产出 | 成本 |
|---|---|---|---|---|
| D-1 | D 的 SPI 契约测试（不需要真实数据，构造上下文） | 无 | 证明 D 的接口契约、降级语义、`ratioPassed` 归因字段可用 | 0.5 天 |
| D-2 | **开源数据 UC 链路可用性复核**：把 D2（WUH20 + `SSR*01CAS0`）从 IFLC 切到 UC/EST（`nf=3`、`navsys` 放开），检查 `ns` / `vsat0` / `fix` | **D2（公开流数据）** | 判定 §5.2 的「可能 A / 可能 B」 | 0.5–1 天 |
| D-3 | 按 §5.3 判定表处理 D-2 结果 | D2 | 决定 D 是否重开 | 待 D-2 结果 |

**为什么 D2 可用于 D-2**：D2 的两条流都是公开可获取的（WUH20 观测 / CAS `SSR*01CAS0` SSR），且已在仓库内，符合「开源数据」要求；D2 的既有结论（FIX-ALG-035）是「WL 100% 固定、NL 因可见星数未达 FIX」——即 **AR 链路在 D2 上是活的**（WL 有固定），与 D3 上的 `ns=1` 退化形成对照。这正是区分 A/B 所需要的对照。

**已知限制**（须在 D-2 结论里写明）：WUH20 观测流不含 BDS B2a，`freqIdx=1` 恒空，UC/EST 下可用卫星数天然偏少（FIX-ALG-035 实测单历元 5–10 颗）；因此 D-2 若仍 `fix=0`，需先用「`ns` / `vsat0` 是否正常」区分「卫星不够」与「滤波器退化」，不能直接判为可能 A。
**D-2 判定表**（`fix` 不是唯一指标——D2 上 FIX-ALG-035 实测 `fix=0` 同样成立，差别在「WL 是否在固定」）：

| D-2 观测到的 | 判定 | 对 D 的影响 |
|---|---|---|
| `ns` 正常（≥5）、WL `nFixed>0`、NL 因星数不足未达门限 | 链路正常，瓶颈是可见卫星数（与 FIX-ALG-035 同因） | D 无收益对象（无固定解），但归因是几何而非链路缺陷 |
| `ns=1` / `vsat0=0`（与 D3 同现象） | 链路结构性退化，与数据批次无关（可能 A） | D 转为「链路缺陷」条目，与 AI 无关 |
| `ns` 正常且出现 `fix>0` | 链路可用，D3 的退化是批次相关（可能 B） | D 的收益对象成立，可重开 |

### 5.4 SPI 定义（保留契约，可先做契约测试）

`PppRtkFixValidator` 返回固定解置信度 ∈ [0,1]；`null` / `NaN` / 抛异常 → 按 1.0（保留 FIX）。

上下文 `PppRtkFixContext` 字段：

| 字段 | 语义 |
|---|---|
| `epoch`, `ns` | 历元、卫星数 |
| `ratioWl`, `ratioNl` | 宽巷 / 窄巷 ratio |
| `maxDevWl`, `maxDevNl`, `maxSigNl` | WL/NL 最大偏差、NL 最大 σ |
| `nFixed` | 参与固定的模糊度个数 |
| `postResiduals` | 固定后验后残差 |
| `positionVar[3]` | 固定后位置方差 |
| `ratioPassed` | ratio 门限是否已通过（契约字段，注入点执行时应恒为 `true`） |

`ratioPassed` 把「D 只在 ratio 通过后判」的语义在 context 里显式化，而不是靠调用位置隐式保证。若后续重构把注入点挪到 ratio 判定之前（如 partial AR、多频逐级固定新增入口），`ratioPassed == false` 的历元会被记录而不是静默误判。

### 5.5 注入点与拒绝语义的局限

`PppRtkAmbFix` 的固定成功路径为：ratio 通过 → `arraycopy(rtk.x→xa)` → 逐模糊度写 `xa[idx]`/`Pa` 清零/`ssat.fix=1` → `rtk.xa=xa`/`rtk.Pa=Pa`/`nfix++` → `sol.rr=xa[0..2]` → `sol.stat=SOLQ_FIX`。

- 若注入点在写 `sol.stat` 之前，拒绝时需**完整回滚** `xa[idx]`、`Pa`、`ssat.fix`、`nfix`，否则脏状态残留（§5.2 阻塞 2）。
- 若 `xa` 是 `rtk.xa` 别名，回滚 `xa` 等同于回滚 `rtk.xa`，而 `rtk.xa` 的原值在 `arraycopy(rtk.x→xa)` 时已被覆盖，回滚需要额外保存快照。
- 若把注入点前移，则上下文缺少固定后特征（D 需要「固定后验残差 / 固定后位置方差」判别）。

**故 D 的正确实现需要重新设计固定流程（先算候选、再校验、通过后才提交状态），属于算法改动而非「加一个分支」。当前只做契约（§5.3 D-1），不做注入点。**

### 5.6 结论

D 的接口契约（§5.4）完整，不依赖输出缺陷是否修复；但**收益对象、拒绝语义、AR 链路可用性三者均未就绪**。当前阶段**只做契约测试（D-1）与开源数据链路复核（D-2）**，不实现注入点、不列入 PoC 交付范围；D-2 结果出来后再决定是否重开。

D 的拒绝语义还违反 §1.4 S2（施加原子性）：拒绝时 `xa` / `Pa` / `ssat.fix` / `nfix` 已被修改且不可回滚，等于 AI 失效后残留副作用。**这是 D 不进入注入实现的契约级理由，不是排期问题。**

---

## 6. 框架层：`AiProvider` 扩展

### 6.1 现有模式（v1.1 校正）

`AiProvider` 现状：`fixValidator()` / `obsCorrector()` 无 default，`refStarValidator()` 有 default 返回 `null`。`refStarValidator()` 已确立「新增接口用 default 方法、既有实现不必改动」的模式。新增 PPP 三个方法**照此模式**加 `default { return null; }`；**不改**已冻结的 `fixValidator()` / `obsCorrector()`。

### 6.2 新增方法

| 方法 | 返回 | 默认 |
|---|---|---|
| `pppSolutionValidator()` | `PppSolutionValidator` | `null` |
| `pppVarianceScaler()` | `PppVarianceScaler` | `null` |
| `pppRtkFixValidator()` | `PppRtkFixValidator` | `null`（D，只做契约，暂不注入） |

### 6.3 `AiHooks` 扩展

新增 `resolvePppSolutionValidator(Rtk)` / `resolvePppVarianceScaler(Rtk)` / `resolvePppRtkFixValidator(Rtk)`，**必须与现有 resolve 同构**：

- 实例字段优先（`rtk.aiPppSolutionValidator` 等）；
- 再取 ThreadLocal 注入的 provider；
- 都为空返回 `null`；
- **先调 `validateAiOpts(rtk)`**（现有 resolve 均如此，新增的不得省略）。

### 6.4 `Rtk` 实例字段

新增 `aiPppSolutionValidator` / `aiPppVarianceScaler` / `aiPppRtkFixValidator`，均为 `transient`、默认 `null`，并在 `Rtk()` 构造器中初始化为 `null`（与现有 `aiFixValidator` / `aiObsCorrector` / `aiRefStarValidator` 一致）。

安全攸关相关字段（§1.4 S5/S6）：另需 `aiPppDegradeLogged`（单次告警，沿用接口一 `aiDegradeLogged` 语义）、`aiPppConsecutiveDegrades`（连续降级计数）、`aiPppFused`（本会话熔断标志），均 `transient`、默认 `false` / `0`，在 `Rtk()` 构造器中初始化。熔断只作用于 AI 分支，不影响 §3.2 确定性基线（L1 层）。熔断计数写在 `Rtk` 上，前提是「单 `Rtk` 单线程解算」（现状如此）；若未来引入多线程解算，须改为线程本地计数，否则熔断判定会被并发污染。

### 6.5 `AiOpts` 字段（两处必改）

`copyFrom()` 与 `validate()` 均为逐字段手写，**新增字段必须同时补入两处**，否则复制会丢失、校验会漏钳。字段名不带 `ai` 前缀（已在 `AiOpts` 命名空间内）。可直接复用现有钳位助手 `clampUnit` / `clampAtLeastOne` / `clampRange` / `clampPositive`。

| 字段 | 默认 | 钳位 |
|---|---|---|
| `pppSolutionMinConfidence` | 0.5 | `clampUnit` |
| `pppQrThreshold` | `Double.MAX_VALUE` | 非有限回退默认；负值钳 0 |
| `pppVarianceScaleMax` | 4.0 | `clampAtLeastOne` |
| `pppRtkFixMinConfidence` | 0.5 | `clampUnit`（D，暂不注入） |

失效安全字段（§1.4 S5）：

| 字段 | 默认 | 钳位 | 说明 |
|---|---|---|---|
| `pppAiMaxInferenceMs` | 50.0（待标定） | `clampPositive`；≤ 0 视为关闭预算度量 | 单次推理耗时上限，超出则丢弃该次结果（不干预） |
| `pppAiMaxConsecutiveFailures` | 10 | `clampAtLeastOne` | 连续降级历元数达到该值即本会话熔断 |

### 6.6 多接口共存约束

- `AiHooks` 保持 ThreadLocal 单槽，多接口同时开时由 `rtklib-ai` 侧复合 provider 返回多个实现；
- 一次只开一个接口做 A/B；
- 每个接口单独过判据，不打包测试；
- 只读标记类接口（A）可独立评估；写状态类接口同时开才需联合评估（评估 §7.3）。

---

## 7. 开发顺序与工作量

与评估 §11.6 的四步对齐；Step 5 / 6 是本仓库新增的交付两步（v1.4，§12），不在评估四步之内：

| 阶段 | 内容 | 依赖 | 数据 | 工作量 | 状态（v3.5.0 收口） |
|---|---|---|---|---|---|
| Step 1 | `AiProvider` 加 default 方法 + `AiHooks` 加 resolve + `Rtk` 字段（含熔断计数，§6.4）+ `AiOpts` 字段（含预算与熔断，§6.5）+ `PppCore.updateStat` 注入点 + 失效安全框架（S5/S6） | 无 | 零 | 0.5–1 天 | 已完成（`AiPppState` / `PppAiGuard` / `AiOpts`） |
| Step 2 | **A 的确定性基线（P4）**：`qr` 阈值降级分支，量出拦截率 | Step 1 | D1 | 0.5 天 | 已完成（`PppQrGate`，91.5% @ 0.5 m²，§7.1） |
| Step 3 | A：SPI + 上下文 + 注入点（纯 PPP / PPP-AR 两处）+ 契约测试 | Step 1、2 | D1 | 2–3 天 | 已完成（`PppSolutionValidator` + `PppAiInject`） |
| Step 4 | B：独立 SPI + `pppRes` R 对角元注入 + 历元键缓存 + 单侧钳位 | Step 1 | D1 | 2–3 天 | 已完成（`PppVarianceScaler` + 行序映射 + `nominalSigma`） |
| D-1 | D：SPI + 上下文 + 契约测试（不注入） | Step 1 | 无 | 0.5 天 | 已完成（契约在，不注入） |
| D-2 | **开源数据 UC 链路复核**（D2 从 IFLC 切 UC/EST） | 无（确定性排查） | D2 | 0.5–1 天 | 未执行（D 双重阻塞，不在 PoC 范围，§5.5） |
| D-3 | D 是否重开的判定 | D-2 | D2 | 依结果 | 阻塞于 D-2 |
| Step 5 | **训练侧落地**：`common/ppp_dataset.py` + `ppp_solution_validator/` + `ppp_variance_scaler/`（`assemble.py` / `train.py`）+ Java 采集器 `PppCollectSession` + `onnx` / `norm.json` / `metrics.json` 产物（§12.2） | Step 3、4 | D1 | 3–5 天 | 已完成代码与工具链；产物需真实数据训练后生成 |
| Step 6 | **开源移交**：许可与数据来源复核、去私有数据与凭据、模型卡、可复现四命令、收益缺口声明（§12.3） | Step 5 | 无 | 1–2 天 | 部分完成（四命令见 `rtklib-ai/training/README.md` §8；模型卡随产物产出） |

**Step 2 是 Step 3 的 go / no-go 门，不是可选项。** 若 `qr` 基线拦截率已 80%，A 的 AI 增量上限只有 20%，Step 3 的投入可能不值得；若拦截率 30%，A 的增量空间大。该数不量，Step 3 的投入是盲目的。Step 2 的产物同时是 §11 的 A1 组基线代码——既是收益分母，也是评估协议的必需组。

**D-2 与 D-1 相互独立**：D-1 是纯契约（零数据），D-2 是纯确定性排查（零 AI）。两者都不依赖 A/B 的进度，可并行。

每个阶段包含：SPI 定义（core，零依赖）、`AiOpts` 字段（惰性钳位）、注入点（唯一分支）、降级路径、I-1 零回归测试、契约测试（构造输入，不需要真实数据）。Step 2 例外：纯确定性代码，只要求 I-1 零回归测试（默认阈值关闭时必须与基线逐位一致）。

**失效安全随 Step 1 一起落地，不后置**：熔断计数、单次告警、原子施加属于框架层；若等模型接完再补，A / B 的收益数据是在不安全状态下采的，结论不可用。

**终点的定义（v1.4）**：Step 5 / 6 是本仓库的**交付终点**，不是「收益评估」。本仓库无真实数据，收益结论不产出（§12.1）；Step 2 量出的拦截率是确定性代码的统计，属自测数字，可作开发决策依据，不可作收益结论。

---

### 7.1 Step 2 验证结论：KF 正定化护甲与三层链路覆盖（v1.6 新增）

#### 7.1.1 致命 Bug 根因（静态 PPP，X 轴协方差负值永久锁死）

**现象**：静态 PPP 场景下，自历元 `i=50` 起 ECEF **X 轴**位置协方差对角元 `posVar` 变为固定负值（`-0.00341486`）并保持到解算结束，而 Y / Z 轴正常收敛。该负值不被任何后续历元修正，滤波器实际已失去正定性。

**根因是三层叠加，不是单点错误**：

| 层 | 机理 | 后果 |
|---|---|---|
| 数值层 | `S = H·P·Hᵀ + R` 在本场景严重病态（条件数约 `5e9`），求逆放大浮点误差 | 协方差更新结果偏离正定流形 |
| 库层 | EJML 0.41 的 `SimpleMatrix.multiply` 存在**内部数组别名复用**：Joseph 形式 `P = (I-KH)·P·(I-KH)ᵀ + K·R·Kᵀ` 的中间量被意外覆盖 | 即使 Joseph 形式在数学上结构保正定，实际算出的 `PcNew` 仍可能非正定 |
| 结构层 | `KalmanFilter.update()` 采用**状态压缩**：仅 `x[i]!=0 && P[i,i]>0` 的状态参与更新。某状态对角元一旦变负，即被永久排除出后续更新 | 负对角元**永久锁死**，后续历元无法修正——这正是「i=50 变负且一直保持到结束」的原因 |

三层中前两层只解释「为什么会变负」，第三层才解释「为什么不可恢复」。仅修前两层而不加保护，负值仍会通过压缩筛选自我固化。

#### 7.1.2 修复口径：`KalmanFilter.update()` 三层强制正定化

修复不做「遇到负值就粗暴钳位」，而是保证**更新后的协方差矩阵在数值上严格对称半正定**。三层保护全部落在 `KalmanFilter.update()` 这**单一入口**，参数 `KF_PSD_FLOOR = 1e-9`：

| 顺序 | 动作 | 实现 |
|---|---|---|
| 1 | **强制对称化** | `PcNew[i,j] = PcNew[j,i] = 0.5·(aij + aji)`，消除别名复用造成的非对称残差 |
| 2 | **谱投影（PSD projection）** | 对对称矩阵做对称 QR 特征分解，若最小特征值 `< KF_PSD_FLOOR`，把负特征值钳到 `KF_PSD_FLOOR` 后重构 `PcNew = V·Λ₊·Vᵀ`；特征分解失败时走 `jitterPsd()`（对角抖动 + Cholesky 校验，抖动按 4 倍递增，最多 30 次）兜底 |
| 3 | **对角钳位** | 遍历 `PcNew` 对角元，若 `< KF_PSD_FLOOR` 则强制设为 `KF_PSD_FLOOR`，并打印 `[KF-WARN] Diag forced positive` 告警 |

谱投影比「负值直接置极小正数」更严谨：它把整个矩阵投影回正定锥，而不是只改一个元素、留下与其余元素不一致的对角元。第 3 层仅作为投影后的最后一道保险。

**验证结果（静态 PPP）**：收敛后 1839 个有效历元 `posVar` 全部 `> 0`；原故障点 `i=50` 的 X 轴位置协方差对角元为 `0.00118671`（正常量级），负值锁死现象完全消失。运行期共触发 27 次非正定投影告警，负特征值量级为 `1e-3 ~ 1e-5`，投影后协方差继续正常收敛，滤波器未退化。

**关于 EJML 别名 Bug**：本修复**不依赖**上游修复该 Bug。三层保护把非正定输入一律投影回正定锥，因此无需修改 EJML 依赖版本，也无需逐行规避别名写法；上游修复后本保护仍应保留（它对病态 S 矩阵同样有效）。

#### 7.1.3 Step 2 确定性基线拦截率

A 的确定性基线（`qr` 阈值降级，§3.2）在**动态 PPP** 数据上量得：

| 门限 `pppQrThreshold` | 拦截率 | 说明 |
|---|---|---|
| `0.5 m²`（`qr[0]+qr[1]+qr[2]` 之和） | **91.5%** | 确定性基线单独即可拦掉绝大多数不可信历元 |

该数字是**自测统计**，按 §12.1 口径不得作为收益结论。其对 Step 3 的意义仅在于：AI 的增量上限 = 该拦截率的补集（约 8.5%），因此 A 的 AI 模型必须聚焦「确定性基线漏掉的那部分里，哪些确实不可信」，而不是重复基线已经拦住的历元——这正是 §3.3 `deterministicDegraded` 归因字段存在的理由。

#### 7.1.4 三层链路覆盖结论：浮点 / PPP-AR / PPP-RTK 均受保护

三条链路都复用 `KalmanFilter.update()` 这**同一入口**，因此正定化护甲自动覆盖全部场景，不存在旁路：

| 链路 | KF 入口 | AR / 固定消费协方差的方式 | 证据 |
|---|---|---|---|
| **PPP 浮点** | `PppCore.pppos` 与 `PppCoreEx.ppos` 均调用 `KalmanFilter.update()` | 无固定，`updateStat` 直接读 `P[i*nx+i]` | 实测 1839 历元 `posVar` 全为正，`i=50` 处 X 轴 `0.00118671` |
| **PPP-AR** | `PppCoreEx.ppos` 同上；KF 更新并写回 `rtk.P` 之后才调用 `applyAmbiguityResolution` | `PppAmbFix` 用 `QbWl[i,j] = rtk.P[IB·nx+IB]/λ²` 构造 LAMBDA 输入，**直接取自已正定化的 `rtk.P`**，无额外修改 | `PppArValidationTest#testArEntryCovariancePsd`（ALGO 2024 DOY 060 + WUM）：全阵**负对角元历元 0**；KF 参与的模糊度子块 **LDL 最小枢轴 `1.29e-5 ~ 1.84e-5` 全为正**，抽样最小特征值 `8.6e-6 ~ 1.06e-5` 全为正 |
| **PPP-RTK** | `PppRtkCore.ppprtkos` 同样调用 `KalmanFilter.update()` | `PppRtkAmbFix` 的 `Pa` 矩阵**直接复制自 `rtk.P`**（同一块内存语义），无旁路改写 | **仅静态代码检查**：确认 `Pa` 来自 KF 处理后的 `rtk.P`，故底层 KF 已获正定化保护；但因开源数据 NL 未达 FIX，**尚无量测数据佐证** |

**PPP-RTK 的口径必须如实标注**：「PPP-RTK 的底层 KF 已获正定化保护，但因缺乏 NL 固定样本，尚无量测数据佐证。」不得把它与 PPP-AR 的实测证据混为一谈。

**必须记录的覆盖边界（重要）**：验证口径固定为「**KF 本历元实际参与压缩更新的模糊度子块**」，而非「全部已初始化模糊度状态」。原因：`KalmanFilter.update()` 以 `skipZeroHCols=true` 排除本历元 H 列全零的状态（无观测卫星）；这些状态的**对角元与互协方差停留在旧值**，与被更新状态缩小的方差不再自洽，因此「全状态联合矩阵」在个别历元可以合法地非正定（实测 epoch=2100 的全状态子块 LDL 最小枢轴为 `-3.2e-4`）。这是**压缩更新方案的结构性后果，不是护甲失效**：AR 引擎只消费本历元有观测的卫星子块，故 AR 输入始终健康。为此 `KalmanFilter` 新增只读观测接口 `lastActiveIndices()`，把「本次实际参与更新的状态集合」暴露给上层链路验证，验证断言只对该集合成立。
## 8. 契约测试清单（不需要数据）

| 测试 | 断言 |
|---|---|
| T1 未注入 | 三个 resolve 均返回 `null`，core 行为与基线逐位一致 |
| T2 注入但全 1.0 | A / B 均不干预，行为与基线一致 |
| T3 降级等价 | 返回 `null` / `NaN` / `Inf` / 长度不符 / 抛 `Throwable` → 输出与**未注入时逐位一致**，并写 `AI_DEGRADE` trace（S1、S6） |
| T9 施加原子性 | B 构造「部分行合法、部分行非法」的输出 → 合法行按因子膨胀、非法行按 1.0（§4.4 单值规则），不得出现「非法行的因子污染其他行」或「矩阵只写一半」；异常路径（抛 `Throwable` / 长度不符）整体不干预，`R` 与未注入逐位一致；A 同理，`sol.stat` 与 `sol.rr` / `sol.qr` 无半改（S2） |
| T10 熔断与恢复 | 连续 `pppAiMaxConsecutiveFailures` 个历元降级后，provider 不再被调用（计数探针验证）；熔断期输出与未注入逐位一致；新建 `Rtk` 或重注入后恢复调用（S5） |
| T11 单调性与不推翻确定性 | A 返回 0.0 时 PPP-AR 链路的 `SOLQ_FIX` 不得被改写；A 不得把 `SOLQ_NONE` 提升；B 返回 < 1.0 时 R 不收缩（S3、S4） |
| T4 长度不符 | B 返回长度 ≠ nv → 整体降级为全 1.0 |
| T5 钳位 | B 返回 < 1.0 → 按 1.0；> `pppVarianceScaleMax` → 钳到上限并告警 |
| T6 只读性 | A 只改 `sol.stat`，`sol.rr` / `sol.qr` 不变；确定性分支默认关闭时不写任何状态 |
| T7 异常隔离 | provider 抛 `RuntimeException` → core 不崩，按不干预处理 |
| T8 上下文标志与注入点位置 | A 的 `deterministicDegraded` 与实际降级状态同源；PPP-AR 链路 A 的注入点确在 AR 之后（AR 固定历元 A 不得把它降级）；D 的 `ratioPassed` 恒为 `true` |

这 11 条全绿 = 三个接口的架构通了。T8 不验证模型、只验证**上下文构造与注入点位置**：A 的标志要与确定性分支的实际写入同源（同一个布尔变量，不是两处独立判断）；PPP-AR 的注入点位置要能被「AR 已固定 → A 不降级」断言暴露；D 的 `ratioPassed` 要能暴露注入点被挪错位置。若只在测试里硬编码常量，T8 会假绿——实现上必须从真实状态读取。

**v1.5 落地状态**：T1–T5、T7、T9–T11 已由 `AiPppStage1Test`（14 条）覆盖并通过，其中 T9 按上表口径拆成「部分行非法只影响该行」与「异常/长度不符整体不干预」两条；T6 已覆盖「A 只改 `sol.stat`、不动 `qr`」部分；T8 的注入点位置断言待 Step 3 在真实 AR 链路接入后补齐（Step 1 只有 `PppProcessor.processEpoch` 的单点调用，无 AR 固定历元可构造）。

**D 当前的全部验证就是 T1 / T3 / T6 / T8 / T9 / T10 / T11 七条契约**（§5.3 D-1），除此之外无任何可用验证手段，直到 D-2 给出链路可用性结论。其中 **T9 在 D 上预期失败**（拒绝固定不可回滚，§5.5），该失败即 §5.6「D 不注入」的契约级证据。

---

## 9. 模型侧 ONNX 契约

| 接口 | 输入 | 输出 | 与 RTK 侧的差异 |
|---|---|---|---|
| A `PppSolutionValidator` | `[batch, N]`，`N = 3 + ns×4 + 3` | `[batch, 1]` sigmoid | 特征是非差语义（无 `ddres` 残差） |
| B `PppVarianceScaler` | `[batch, ns×nf, M]` | `[batch, ns×nf]`，sigmoid 映射到 `[1, max]` | 无 `biasMeters`，只有 `varianceScale` |
| D `PppRtkFixValidator` | `[batch, K]` | `[batch, 1]` sigmoid | 输入是固定上下文，不是逐星特征（只做契约） |

A 的 `N` 含 `deterministicDegraded`（3 个位置方差 + 4 组逐星量 + `convergenceMetric` / `biasState` / `deterministicDegraded` 三个标量）。`ratioPassed` 同理计入 D 的 `K`。

**为什么 `deterministicDegraded` 必须进模型输入（而非仅作归因字段）**：

1. **学习目标需要它**。A 的收益是「确定性基线之上的增量」。模型若不知道确定性会不会拦，只能学「这个历元是否不可信」——其中一部分已被确定性拦住，A 再判是重复。知道该标志，学习目标才聚焦到「确定性没拦住的那部分里，哪些确实不可信」。
2. **模型无法从 `positionVar` 自行推出该标志**。`pppQrThreshold` 是可配置阈值且不在模型输入里：同一组 `positionVar`，阈值取 0.01 与取 0.1 时确定性是否拦截的结果完全不同。
3. **A2 / A3 的输入分布不会因此漂移**。§3.5 的解耦规则已保证标志始终按 `posVar > pppQrThreshold` 计算、与分支是否启用无关。A2 组默认 `Double.MAX_VALUE`，标志恒 `false`；A3 组若同样不启用阈值，标志也恒 `false`。只有 A3 组**启用**确定性阈值时标志才可能为 `true`——这正是 A2 与 A3 的定义差异，是实验设计，不是输入漂移。

- **导出与加载**：与 RTK 侧一致，`torch.onnx.export` → `OnnxInference` 加载 → batch 探针 1/7/64 全过。
- **归一化**：每个接口独立 `norm.json`，训练集拟合，推理侧原样加载。禁止推理侧动态归一化。
- **加载失败即不注入**：模型文件缺失 / ONNX 解析失败 / 算子不支持，一律返回 `null`（等价未注入），禁止把异常穿透到 core。core 侧仍以 S1（§1.4）兜底，故 provider 侧的加载失败与运行期失败走同一条退化路径。
- **训练代码同批交付**：推理契约与训练代码必须同批落地（§12.2）。只交 ONNX 等于交一个不可审计、不可复现的权重，在安全攸关场景不可接受。

---

## 10. 收益排序与结论

| 排序 | 接口 | 收益维度 | 收益可量测性 | 实现成本 | PoC 范围 |
|---|---|---|---|---|---|
| 1 | A `PppSolutionValidator` | 可靠性 | 流程可量（D1 有参考坐标）；结论需真实数据 | 中 | 是（先做 P4 确定性基线） |
| 2 | B `PppVarianceScaler` | 可靠性（无精度） | 中（方差校准度间接量）；结论需真实数据 | 中 | 是（可选） |
| 3 | D `PppRtkFixValidator` | 可靠性 | 当前为零（坐标不输出 + 拒绝不可回滚 + AR 链路在 D3 上退化） | 大（依赖算法与输出修复） | 否（只做契约 D-1 + 开源链路复核 D-2） |

**实现顺序建议**：A → B → D。理由不是「前置条件」，是「收益可量测性从高到低」。

**Step 2 的门禁作用**：A 的排序第 1 建立在「`qr` 基线拦截率不会把增量空间吃光」这个未量化的假设上。Step 2 用 0.5 天确定性代码把这个假设变成数字——拦截率过高时 A 直接降级或暂缓，不必先投 2–3 天做完 SPI 再发现增量上限不足。B 与 D 无对应的分母步骤：B 的分母是解析方差模型本身（已存在），D 的分母是 ratio 单门限（已存在）。

**收益结论的可产出性（v1.4）**：上表「收益可量测性」是**设计期判断**（「若有合适数据能否量出」），不是「本仓库能量出」。本仓库无真实数据，**不产出任何收益结论**（§12.1）；排序只用于排开发顺序，不得被引用为「A 比 B 更值得做」的证据。

**安全攸关补充**：三个接口的失效安全约束（§1.4 S1–S6）先于收益排序。D 因不满足 S2（拒绝固定不可回滚）而无法进入注入实现——这本身就是安全约束生效的例证：**不是 D 没有收益，而是 D 的失效模式不合格**。

**一句话结论**：三个接口的共同点是「只做可靠性、不做精度」：A 在 `SOLQ_PPP` 后补降级标记（收益分母是 `qr` 基线），B 修正解析方差模型的低估（收益是方差校准度，无精度承诺），D 在 ratio 门限后补固定校验（当前三重阻塞：坐标不输出、拒绝不可回滚、AR 链路在 D3 批次上退化；接口契约完整但不在 PoC 范围，只做契约测试与开源数据链路复核）。判据统一为「AI 增量 = 确定性 + AI 相对确定性基线的改善」。本文只定义技术实现路径，**交付结论仍以 [PPP_AI_ASSESSMENT.md](PPP_AI_ASSESSMENT.md) §8「本阶段不接入」为准**。

---

## 11. 评估协议：A0–A3 四组与触发结构三计数

A 的收益判定不能只看「A 开了之后错误率降了多少」，必须把「确定性基线贡献」与「AI 独立贡献」拆开。

| 组 | 确定性 `qr` 基线 | AI 注入 | 用途 |
|---|---|---|---|
| A0 | 关 | 关 | 基线组（与 v3.4.0 逐位一致的对照） |
| A1 | 开 | 关 | 量出确定性基线的拦截率（Step 2 产物，§3.2） |
| A2 | 关 | 开 | 量出 AI 单独的拦截率 |
| A3 | 开 | 开 | 端到端；AI 增量 = A3 相对 A1 的改善 |

触发结构三计数（每个历元只落一个桶，来源是 `deterministicDegraded` 与 A 的返回值）：

| 计数 | 条件 | 含义 |
|---|---|---|
| 确定性触发 | `deterministicDegraded == true` | 基线已拦，A 的写入幂等 |
| AI 独立触发 | `deterministicDegraded == false` 且 `confidence < 阈值` | A 的独立贡献（收益分子） |
| 两者皆未触发 | 其余 | 正常输出 |

「AI 增量 = A3 相对 A1」的口径：A3 比 A1 多拦的历元里，只有**判错的**才算收益；多拦的**判对的**计入有效输出率损失（≤1 pp 判据，§3.7）。三计数是这套口径的唯一数据来源，因此 `deterministicDegraded` 必须与确定性分支的实际写入同源（§8 T8），不能是两处独立判断。

B 与 D 无四组结构：B 的对照是「解析方差模型 vs 解析方差模型 + AI 膨胀」，D 的对照是「ratio 单门限 vs ratio + AI 门控」（D 当前不在 PoC 范围，见 §5）。

**执行边界（v1.4）**：A0–A3 是给**有真实数据的执行方**（社区 / 后续拿到现场数据的阶段）的协议。本仓库只做两件事：

1. **链路自测**：在 D1 上把 A0–A3 四条路径各跑一遍，确认「组间差异确由开关引起、三计数能落、`deterministicDegraded` 同源」——这是 §8 T8 的数据版，属契约级验证；
2. **工具就绪**：`metrics.json` 的字段与四组口径对齐，社区拿到数据即可直接跑。

**不得**用 D1 / D2 的 A0–A3 数字写收益结论：单站单日、相邻历元强相关，拦截率差异不具统计意义（`training/common/dataset.py` 已明确禁止随机切分，同一约束适用于评估）。把「自测跑通」误读为「已验证收益」，是安全攸关场景下最危险的一类误读。

---

## 12. 工程交付与开源移交（v1.4 新增）

### 12.1 终点的重新定义

本仓库**没有真实（现场 / 生产）数据**。可用的是 D1（IGS ALGO 开源，单站单日）与 D2（公开 NTRIP 流，单日单流）这类公开数据：够跑通链路、够做契约与自测，**不够支撑收益结论**——收益判据要求跨站、跨天、跨环境覆盖，且需要判错真值。

因此终点从「收益评估」改为三段交付：

| 段 | 判据 | 由谁完成 |
|---|---|---|
| 链路跑通 | §8 的 T1–T11 全绿；D1 上 A / B 端到端跑完不崩；trace 三计数可落 | 本仓库 |
| 训练代码落地 | 环境自检 / 组装 / 训练 / 导出四条命令在干净环境跑通，产物与 Java 侧契约对齐 | 本仓库 |
| 收益结论 | A0–A3 四组在真实数据上出数 | **社区**（本仓库只提供协议与工具） |

**「自测」的边界**：可以在 D1 / D2 上跑 A0–A3 看数字，但只能写「链路自测结果」，不得写「收益结论」，不得据此调整 §10 排序。单站单日、相邻历元高度相关，任何拦截率差异都不具统计意义。

### 12.2 训练代码是必须交付物，不是可选项

对齐现有 `rtklib-ai/training/` 的既定布局（`common/` + 每接口一目录 + `assemble.py` / `train.py`），PPP 三接口的落地物必须同批交付：

| 交付物 | 路径（沿用现有布局） | 对应接口 |
|---|---|---|
| 数据集格式与两层切分 | `rtklib-ai/training/common/ppp_dataset.py` | A / B 共用 |
| 组装（采集 CSV → Parquet） | `training/ppp_solution_validator/assemble.py`、`training/ppp_variance_scaler/assemble.py` | A / B |
| 训练 | 同名目录下 `train.py` | A / B |
| 导出与契约自证 | 复用 `common/export_onnx.py`（batch 探针 1/7/64、输出落在 core 钳位区间） | A / B |
| 环境自检 | 复用 `common/check_env.py`，`requirements.txt` 按需追加 | 全部 |
| 采集器（Java 侧） | `PppCollectSession`，对齐现有 `CollectSession` 模式：CSV + trace，join key 为 `(run_id, epoch)` | A / B |
| 产物 | `<name>.onnx` / `<name>.norm.json` / `<name>.metrics.json` | A / B |

**为什么必须一起交付**：只交推理侧 = 交一个无法复现的黑盒。社区拿到 `onnx` 却拿不到训练代码，等于拿到不可审计的权重——安全攸关场景下不可接受（与 §1.4 的失效可审计同源）。反过来，只交训练代码不交 core 注入点，等于交一个无法落地的模型。

三条工程化硬要求：

1. **可复现**：随机种子固定并写入 `metrics.json`；`requirements.txt` 锁最低版本；`check_env` 在 CI 先跑；产物必须记录训练集的 `station` / `day` 清单，禁止「不知道用哪些数据训的」。
2. **可验证**：`metrics.json` 必须含**按站 / 按天**两层指标，不得只报池化指标（池化会被单站主导，与 `training/common/dataset.py` 禁止随机切分的理由同源）。
3. **可替换**：模型文件路径由 `AiOpts` 指定，加载失败即不注入（§9）。社区可用自己的数据重训并替换权重，core 不改。

### 12.3 开源移交清单

| 项 | 要求 | 不满足的后果 |
|---|---|---|
| 许可 | 与仓库现有 LICENSE 兼容；训练代码、`onnx`、`norm.json` 同许可 | 无法合并 |
| 数据 | 仓库内不含私有 / 授权受限数据；D1 / D2 的来源与许可见 §2.4 并在 README 标注 | 法律风险 |
| 凭据 | 无 NTRIP 账号、无 token、无硬编码绝对路径 | 泄露 / 不可移植 |
| 模型卡 | 每个 `onnx` 附 model card：训练数据范围、特征定义、已知失效模式、**未验证收益** | 下游误用 |
| 缺口声明 | 文档与 README 明写「收益结论未产出，需真实数据」 | 被当作已交付能力 |
| 可复现命令 | 四条命令（`check_env` / `assemble` / `train` / `export`）写在训练侧 README，一条不缺 | 社区无法接手 |

**移交时的口径**：本仓库交付的是「一个失效安全、可审计、可复现的接口框架 + 一套训练工具链」，不是「一个已验证有效的 AI」。这个区别必须在 README、模型卡、issue 模板三处同时写明，否则后续必然出现「已接入即已有效」的误读。

### 12.4 v3.5.0 交付状态（v1.7 新增）

| 段（§12.1） | 状态 | 可核对的证据 |
|---|---|---|
| 链路跑通 | 完成 | `AiPppStage1Test`（T1–T11 口径，19 条）全绿；`PppRtkFixValidatorTest`（D 契约，不注入）全绿；`PppRtkSsrReplay` 按「任一接口可用即注入」启动并逐项打印 A / B 状态 |
| 训练代码落地 | 完成 | `training/common/ppp_dataset.py`（A 10 维 / B 12 维）、`ppp_solution_validator/` 与 `ppp_variance_scaler/`（`assemble.py` / `train.py`）、Java 采集器 `PppCollectSession` + `RecordingPppSolutionValidator` / `RecordingPppVarianceScaler`；四命令见 `rtklib-ai/training/README.md` §8 |
| 收益结论 | **未产出**（按 §12.1 交社区） | 本仓库无真实数据；`onnx` / `norm.json` / `metrics.json` 需社区用自有数据训练后生成，仓库内不预置权重 |

**D 仍不注入**：D 的阻塞是「坐标不进输出 + NL 固定不稳定」双重事实（§5.5），与 A / B 收口无关；D 只保留 SPI、上下文与契约测试。

**注入开关的唯一判定**：`OnnxAiProvider.isAnyInterfaceAvailable()`。禁止再以单个接口的可用性（如 `isObsCorrectorAvailable()`）当总开关——那会让 A / B 在「仅接口三存在」的配置下被连坐关闭。
