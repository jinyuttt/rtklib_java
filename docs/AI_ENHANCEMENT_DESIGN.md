# GNSS AI 增强开发文档

> **版本**：v1.11（定稿，含执行层附录）
> **日期**：2026-11-12（v1.2 于 2026-11-06 定稿；v1.3 新增 §1.5 与 D11/D12；v1.4 更新实现状态与数据体检；v1.5 新增 §8.2 阶段 4 训练与评估结果；v1.6 闭环 §8.1 小时级采集线与真值口径校验、补 §8.2 留多站 LOMO 探针；v1.7 统一假固定阈值口径到 0.20 m，新增 §7.4.3 剔除规则前置门禁、§7.4.4 可交付口径、§8.2.8 尾部形态证据与 D13/D14/D15；v1.8 新增 §6.4 接口一训练与标定两层次（**明确选层次 A：模型共享、阈值分站**）、§8.2.10 两层次实测对照、§7.5 接口三第一阶段验收（三判据）与 D16/D17，**接口契约与特征定义不变，G-1/G-2/G-3 的阈值数字一字未改**；v1.9 放宽 A3 双门限（§7.5.3）并新增 §8.3 接口三首轮训练复核与 D18；v1.10 并入原 `AI补充.md` 的有效内容——新增 §4.6 输出钳位两种模式（保守现行 / 激进未实施）与 D19、§8.4 方案 B 端到端对照实验结论，**`docs/AI补充.md` 已删除**，其失效内容（`softplus` 无上界、`R ∧ D` 旧口径、两站 A/B 计划）不再保留；v1.11 新增 §0 方案 A/B 术语基线，激进模式由「仅设计」落地为**默认关闭的实验配置**（§4.6 重写 + §4.6.5 单站实测 + `--scale-min` / `-Dpairing.aggressive` 全链路 + D19 更新），**默认值行为与 v1.10 逐位一致**）
> **基线版本**：v3.4.0（2026-11-06，commit `dce304c`，tag `v3.4.0`）
> **状态**：设计定稿。阶段 0–3 已完成（SPI + `rtklib-ai` 推理层 + 接口一接线 + 接口一训练集导出；小时级采集线已组装，16 站 / 4 日 / 113962 行，自动真值经真值文件交叉校验到 cm 级，见 §8.1）；阶段 4 训练链路已完成并跑通，**原 G-1/G-2 组合（1 pp / 30%）在当前特征集下不可达**（留一站 1/7 站、留多站 0/7 组配置满足 G-1+G-2，根因为 6 维特征跨站判别力与全局阈值可迁移性不足 + 分数尾部错误密度不单调，见 §8.2）；v1.7 已落地 pdop 前置门禁并把可交付口径改为成对档（§7.4.4 / D13）；**v1.8 明确接口一的训练形态为层次 A（模型共享、阈值分站），G-3 原判据（≥ 2 未见站）在 B 档 4/7、C 档 6/7 下满足，但加强口径（≥ 5/7、≥ 7/7）只在层次 B 成立、不作为交付证据（§6.4 / §8.2.10 / D16）**；接口三新增第一阶段验收（§7.5 / D17），代码已落地、模型已训练（方案 A，D 组口径 `varianceScaleMax=4.0` / `obsCorrectorMaxBias=2.0`），**A1/A2/A3 全部通过**（§8.3），默认不注入；**方案 B（端到端坐标 Loss）对照实验已执行并判定不交付**，仅作「有效但不可控」的实验 provider 保留（§8.4 / D19）
> **前置文档**：[ALGORITHM_FIX_LOG.md](ALGORITHM_FIX_LOG.md)、[CORE_OPTIMIZATION_MATRIX.md](CORE_OPTIMIZATION_MATRIX.md)、[DATA_GUIDE.md](DATA_GUIDE.md)、[ROADMAP.md](ROADMAP.md)

| 接口 | 设计 | 实现 | 生效条件 | 决策 |
|---|---|---|---|---|
| 接口一 假固定判别 | 定稿 | 推理层与接线已完成（阶段 2），训练集已导出（阶段 3，整日线 12 run / 16152 行；小时线 16 站 / 113962 行）；训练链路已跑通（阶段 4），**G-3 原判据满足（层次 A：B 档 4/7、C 档 6/7），A 档 1 pp / 30% 标记为不可达**（§6.4 / §7.4.4 / §8.2.10） | 未注入即关闭（无 `AiProvider`） | MVP 唯一实施项（§8 阶段 2–4）；训练形态选层次 A（D16） |
| 接口二 固定决策辅助 | **冻结** | 不实施 | — | 与现有 `arfilter` 门控重叠，边际收益为负风险（§4.2、D1） |
| 接口三 观测改正 | 定稿 | **代码已落地**（特征 + 推理 + core 两处施加 + 采集/训练入口，2026-11-09）；**模型已训练（方案 A，D 组口径），A1/A2/A3 全部通过**（§8.3）；**方案 B 端到端对照已执行并判定不交付**，仅作实验 provider 保留（§8.4） | 未注入即关闭（无 `AiProvider`） | 与接口一**并列**，**分两阶段验收**：第一阶段（能用、无害、有作用，§7.5）通过 → 允许显式注入；第二阶段（§7.4.2 的 G-1'~G-5'）通过 → 默认开启（D17）；输出钳位模式选保守（D19） |

**适用范围**：本设计只覆盖单基线 RTK 双差链路（`RtkCore.relpos`）。`rtklib-joint` 与 `rtklib-adjust` **判定为不接入**，理由与重开条件见 §1.5、D11、D12。

实现状态以 §8 为准；与本章摘要冲突时以 §8 为准。

---

## 0. 术语基线：接口三的两种交付方案

本文档中「方案 A / 方案 B」是**接口三训练目标**的专用术语，与 §5.1 接口一「特征选择方案 A / 方案 B」是**两套完全不同的概念**，不可混用。先在此定死，后文一律沿用本表口径。

| 项 | **方案 A：标签监督**（交付形态） | **方案 B：端到端坐标 Loss**（不交付） |
|---|---|---|
| 术语含义 | 用「伪距误差真值」作为标签，监督两个输出头 | 用「定位坐标误差」作为 Loss，直接反传整链 |
| 训练目标 | 高斯负对数似然（`bias` 拟合均值、`scale` 拟合标准差） | 可导单步 WLS 后的坐标残差（`H` detach） |
| 输出契约 | `bias = bias_max·tanh(…)`、`scale = scale_min + (scale_max−scale_min)·sigmoid(…)` | **与方案 A 完全一致** |
| Java 侧代码 | **与方案 B 完全一致**，无任何分支 | 同左 |
| 训练脚本 | `rtklib-ai/training/obs_corrector/train.py` | `rtklib-ai/training/obs_corrector/train_e2e.py`（复用 `build_model`） |
| 产物位置 | `rtklib-ai/training/out/obs_corrector.onnx` | `rtklib-ai/training/out/obs_corrector_e2e.onnx` |
| 实测结果 | 第一阶段 A1/A2/A3 **全部通过**（§8.3），D 组口径下固定率 +6.68 pp、错误固定率 11.11% → 7.94%（§8.4 对照表） | 两轮迭代 H/V RMS 均劣于方案 A（142.5/280.2、149.0/300.8 vs 146.2/265.6），**未达预注册判据** |
| 处置 | **交付**，默认不注入（D17 第一阶段） | **不交付**，仅作「有效但不可控」的实验 provider 保留（§8.4 / D19） |
| 边界 | 只改「学什么」 | 只改「学什么」；**不改**钳位口径（钳位由 §4.6 的保守/激进模式决定，与 A/B 无依赖关系） |

**三条使用约定**：

1. 正文出现「方案 A/B」而未加限定词时，**默认指接口三的训练目标**；指接口一特征选择时必须显式写作「接口一方案 A/B（§5.1）」。
2. 两个方案**共享同一输出契约与同一份 Java 侧代码**，因此「换方案」不需要改 core，只换 ONNX 文件即可。
3. 方案 A/B 与 §4.6 的保守/激进模式**正交**：前者决定训练目标，后者决定推理时 `varianceScale`/`bias` 的钳位区间，四种组合均合法（激进模式默认关闭）。

---

## 1. 定位与边界

### 1.1 这是什么

本文档定义 rtklib-java 在 v3.4.0 之后进入的**新领域**：在现有确定性解算链上增加可插拔的**学习式判别/改正接口**。交付物是**接口 + 特征管线 + 训练管线 + 评估协议**，不是预训练模型。

三条不可动摇的原则：

1. 示例模型仅证明管线跑通，不保证在任何场景有效。
2. 使用者必须用自己的数据重新训练。
3. 接口三的标签获取方式由使用者自行解决（§6.3 给出参考方案）。

### 1.2 这不是什么

**不是求解器替换。** 学习模型只做「判别」和「改正量估计」，不替代最小二乘、不替代 LAMBDA 搜索、不产生协方差。协方差仍由现有法方程产出，AI 只在观测方差阵 `R` 上施加**单侧膨胀**（§4.3.4）。

**不是一阶优化器。** Adam / SGD / 动量法属于数值优化方法，与本文档无关：它们替换的是 `rtklib-core` 内部的求解器，属于算法改进流程，且因不产出协方差、在 GNSS 状态估计中属结构性缺失（v3.4.0 已评估关闭，见对话结论与 §10 D6）。

**不是运行时传感器融合。** 不引入相机 / LiDAR / IMU 在线推理。相机仅可作为离线标签生成工具。

**不是云服务。** 推理在本地进程内完成，不引入网络调用、不引入 Python 进程。

### 1.3 与 v3.4.0 的关系：基线冻结

AI 线**不修改 core 的数值行为**。core 只增加两类东西：

1. 可空的注入点（`AiProvider` 回调接口 + `AiHooks` 注入点，未注入 = 原生行为）
2. 一份只含参数的配置（`AiOpts`，**不含 `enable` 布尔量**）

由此得到一条**可验证的不变量**：

> **I-1（零回归不变量）**：`AiOpts` 全部默认值时，同一输入的解算输出与 v3.4.0 **逐位一致**（`ratio`、`stat`、`rr`、`qr`、残差全部 `byte-identical`）。

这条不变量是阶段 1 的验收判据（§8），也是后续所有阶段的前提。任何破坏 I-1 的改动不得合入。

### 1.4 与 ROADMAP 政策的关系

[ROADMAP.md](ROADMAP.md#L6) 声明「3.1.0 起仅接受 bugfix，不新增算法特性」。本文档不修改该政策，而是给出适用范围界定：

- 该政策约束的是 **core 定位算法的数值特性**（会改变默认输出的行为）。
- AI 线是**独立研究线**：代码在独立模块 `rtklib-ai`，core 侧**不注入即关闭**（无 `AiProvider`），满足 I-1。
- **硬规则**：任何 AI 接口在 core 侧**不得在无显式注入的情况下生效**，除非通过 §7.4 评估闸门并在 ROADMAP 中登记。

若评估闸门通过后要默认开启，须先修改 ROADMAP 政策条款，不得绕过。

### 1.5 适用范围之外：`rtklib-joint` 与 `rtklib-adjust`

**结论：两个模块均不接入 AI，也不纳入本设计文档的后续阶段范围（D11 / D12）。**

这不是「暂缓」，是判定「在当前接口语义下没有收益价值」。分模块记录依据，避免以后重复讨论。

#### 1.5.1 `rtklib-joint`

| # | 事实 | 出处 |
|---|---|---|
| 1 | v1 的接口三**只覆盖 `RtkCore.relpos` 双差链路**，joint 解算路径不受影响；v2 若要覆盖，注入点是唯一可行路径 | §4.3.5、§3.2 理由 3 |
| 2 | 禁止在 joint 内逐星单独推理，否则两条链路偏差不一致；必须复用同一张表 | §4.3.5 第 2 条 |
| 3 | `JointCore.rtkpos` 自行 `nfix++`，无门控点；`Lambda.lambda` 直接调用，绕过 `resamb_LAMBDA` | [JointCore.java#L579](file:///D:/rtklib/rtklib_java/rtklib-joint/src/main/java/org/rtklib/java/joint/JointCore.java#L579)、[L656](file:///D:/rtklib/rtklib_java/rtklib-joint/src/main/java/org/rtklib/java/joint/JointCore.java#L656) |

**判定理由（两条前置未决，且均不是接线工作）**：

1. **跨基线一致性表如何构造未定义。** §4.3.6 的逐行语义是 `idx = i * nf + frq`，`i` 为 `sat[]` 下标，即「卫星-频点」是**单基线**概念。joint 里同一 rover 卫星在每条基线上有各自的参考星与各自的 DD 残差；要让 `varianceScale` 在所有基线上取同一值，推理输入必须把 N 条基线的上下文**合成一行**。这是模型输入设计的重构。
2. **「方差是安全带」的作用域论证在 joint 内不成立。** §4.3.4 的安全带论证前提是**单基线单滤波器**：膨胀只影响该基线。joint 的位置状态（及可选的共享电离层/对流层状态）跨基线共享，某条基线的方差膨胀会经共享状态扩散到全局，安全带无法约束作用范围。**本项为本文档的推论，§4.3.5 未涉及，故不能作为「已论证」引用。**

**收益侧**：joint 的价值在几何冗余（单/双基线 FIX 91.5% 已实测，§2.2）。接口一的作用对象是「已固定解的错判」，接口三的作用对象是「单基线的系统性偏差」。前者在 joint 里被跨基线交叉验证部分覆盖，后者与共享状态耦合后收益-风险比不成立。

#### 1.5.2 `rtklib-adjust`

**本设计文档全文未提及 `adjust` / 多基线 / 网络解**，即它原本就在范围之外。补记三点使其成为可复查的判定：

| # | 事实 | 出处 |
|---|---|---|
| 1 | adjust 走公开 API 进入 `relpos`，**钩子确实会被触发，且每条基线各触发一次** | [RinexFileDataSource.java#L56](file:///D:/rtklib/rtklib_java/rtklib-adjust/src/main/java/org/rtklib/java/adjust/datasource/RinexFileDataSource.java#L56) → [RtkProcessor.java#L715](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkProcessor.java#L715) |
| 2 | §7 的三项指标在多基线语义下**定义错位**：固定率分母变为「基线 × 历元」；错误固定率的偏差是相对基站解而非绝对坐标误差；位置 RMS 是平差**后**坐标，与 AI 作用点隔两层 | §7.2 |
| 3 | `allowFloat` 默认 `false`（仅接纳 FIX），故接口一拒绝一次固定 = **该基线从当历元平差集合中消失** | [BaselineEpoch.java#L128-L136](file:///D:/rtklib/rtklib_java/rtklib-adjust/src/main/java/org/rtklib/java/adjust/model/BaselineEpoch.java#L128-L136) |
| 4 | adjust 已有确定性等价机制：交叉验证阈值 / 可疑阈值 / 协方差膨胀 4× / FLOAT 佐证 0.20 m（`Policy`），重复基线 + 基站漂移 + 环闭合差（`BaselineConsistencyChecker`），lag-1 自相关 + 游程检验（`ResidualWhiteness`） | [GnssBaselineAdjust.java#L52](file:///D:/rtklib/rtklib_java/rtklib-adjust/src/main/java/org/rtklib/java/adjust/engine/GnssBaselineAdjust.java#L52) |

**判定理由**：

1. **单基线里安全的动作，在网络里是结构损伤。** 接口一拒固定在单基线只是退成浮点解；在 adjust 里（事实 3）等于减少参与平差的基线数，几何强度与环闭合冗余同时下降，保护级反而可能变大。
2. **接口三在 adjust 里语义不自洽。** `ObsCorrector` 在 `relpos` 内按基线独立调用，但多条基线共享同一 rover 观测，会得到各自不同的 `bias`，从而**制造出 `BaselineConsistencyChecker` 本要检测的那类异常**。
3. **AI 的优势场景不存在。** AI 的价值在信息稀缺时做判别（单历元、单基线、实时）。adjust 是批处理，有全天数据、多基线冗余、闭合差、白度检验，这些确定性手段在冗余充足时比学习先验更可靠且可审计。
4. **评估协议缺失。** 事实 2 意味着套用 §7.4 的 G-1~G-5 会得到错误结论——不是 AI 无效，而是指标定义错位。要接必须先补多基线评估协议。

#### 1.5.3 重开条件

仅在**同时**满足下列条件时才重开本判定，且须作为新接口立项，不得复用接口一/三：

| 模块 | 重开条件 |
|---|---|
| joint | 出现可约束跨基线污染的状态隔离设计（每条基线独立状态 + 可回滚），且批量上下文可合成单行特征 |
| adjust | 补出多基线评估协议，且新增**只读诊断评分**接口（不改状态、不改坐标、不膨胀协方差），仅作为 `BaseStationDiagnoser` 的第 4 个证据源 |

---

## 2. 基线事实：AI 必须打败的现有机制

这一章是全文档的立论基础，也是「AI 到底还有多少空间」的答案。常见假设是「ratio 门限是唯一防线，因此 ML 有充足空间」。**实际上不是**——v3.4.0 已有多层机制在做同样的事。不先盘点，接口会做成冗余功能。

### 2.1 现有「假固定」防线（接口一的竞争者）

| 机制 | 位置 | 作用 |
|---|---|---|
| ratio 门限 | [manage_amb_LAMBDA](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L2098-L2107) 的 `skip` 判据 + `thresar[0]` | 搜索空间可分性 |
| 动态 ratio 门限 | [RtkCore.java#L1314](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1314) 附近的多项式调整 | 按卫星数修正门限 |
| 固定后残差校验 | `valpos(rtk, v, R, vflg, nv, 4.0)`（固定分支内） | 固定解的验后残差一致性 |
| AR 滤波回退 | `opt.arfilter` 块，`prev_ratio1` / `prev_ratio2` 时序比较 | 用 ratio 历史识别可疑固定 |
| 级联 AR | [RtkOptimizationsCascadeAR](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkOptimizationsCascadeAR.java#L241-L334) | EWL/WL/NL 三级独立门限 |
| 部分固定 | [RtkOptimizationsPartialAR](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkOptimizationsPartialAR.java#L113-L118) | `partialArMinRatio` 逐子集门限 |

**结论**：接口一的价值不在于「有门限」，而在于**门限看不到的量**。ratio 是搜索空间的量，它无法区分两种情形：

- 正确固定：残差与整数解一致 → 残差小且随机
- 错误固定：`ratio` 很高但残差与整数解**系统性不一致**（短基线强相关场景常见）

这就是武大 2026 用 ADR/PRR/PCR 三个**残差类**量而非 ratio 的物理依据。接口一选残差类特征，正是为了覆盖 ratio 的盲区（D2）。

### 2.2 现有「固定率」机制（接口二的竞争者）

接口二的设想是「在固定尝试前判断是否值得试」。但这条路径上**已有两套机制**：

1. **`arfilter` 回退门控**：`prev_ratio2 < thres` 且 `nb_ar >= mindropsats` 时，主动降锁一颗卫星并重跑 `resamb_LAMBDA`（[manage_amb_LAMBDA](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L2113-L2160)）。
2. **`excsat` 剔除重试**：同一次调用内按「降锁 → 重解 → 恢复」做卫星剔除实验。

也就是说，「是否值得尝试 / 该剔除哪颗」已经被 ratio 历史 + 卫星剔除实验部分覆盖。再叠一层 ML 判断，最可能的结果是**把本会成功的固定拦下来**——这不是实现 bug，是设计上的净负收益风险（据此冻结，见 D1）。

### 2.3 现有「抗多路径」机制（接口三的竞争者）

| 机制 | 位置 | 作用 |
|---|---|---|
| IGGIII 抗差 | `RtkOpts.enableIggiii` 等 10 项 | 按标准化残差降权（`k0=3`、`k1=6`） |
| SNR 中位数抗差 | `RtkOpts.enableSnrMedian` 等 10 项 | 相对 SNR 中位数降权，并反馈进 `varerr` |
| 验后残差编辑 | `RtkOptimizationsResEdit.checkCycleSlip` / `checkPcConsistency` | 时序周跳 + 伪距-载波一致性 |
| 观测误差模型 | [varerr](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1450-L1508) | 高度角 + SNR + 基线长 + 钟漂的解析方差 |
| 位置验后校验 | `valpos`（4σ 门限） | 整体解的一致性 |

**结论**：接口三的价值只在**现有解析模型无法表达的模式**上——例如 NLOS 造成的**系统性同向偏差**（残差大且符号一致，IGGIII 会降权但降权只是「不信」，不是「修正」），以及多路径随几何变化的**时变偏差**。IGGIII/SNR 都是「降权」，不改观测值；接口三做「改正」，是本质不同的动作。这也是它必须承担不确定性传播责任的原因（§4.3.4）。

---

## 3. 架构

### 3.1 模块划分：core 只放 SPI，实现独立成模块

| 层 | 位置 | 内容 | 新增依赖 |
|---|---|---|---|
| SPI | `rtklib-core` 内 `org.rtklib.java.ai.spi` | 两个 SPI 接口 + 上下文/输出 record + 注入回调 `AiProvider` + 注入点 `AiHooks`。**不提供 NOOP 实现**（`null` 即关闭，§3.2） | **无** |
| 注入 | `rtklib-core`：`AiProvider` 回调（宿主注入）+ `AiHooks`（`ThreadLocal` 注入点）+ `Rtk` 上可空的实例字段 + `RtkConfig.ai`（`AiOpts`） | 回调挂载与参数配置 | **无** |
| 实现 | **新建 `rtklib-ai` 模块**（依赖 `rtklib-core`） | ONNX Runtime 推理、特征提取、模型加载、异常降级 | `onnxruntime` |
| 训练 | `rtklib-ai/training/`（Python） | PyTorch 训练、ONNX 导出、评估脚本 | PyTorch |

**为什么不让 core 直接引 ONNX**：`onnxruntime` 含平台原生库（十 MB 量级），引入 core 会让所有下游使用者被迫承担；且 core 是「定版核心档案」覆盖的模块，不宜承载实验性代码。SPI 用纯 Java 类型（`int` / `double[]` / `record`），零依赖即可编译。

**成本诚实说明**：新增模块需在根 `pom.xml` 的 `<modules>` 登记，且版本号需随 reactor 同步（当前 12 个 pom → 13 个）。这是新增模块的真实代价，不是零成本。

### 3.2 注入方式：注入回调（`AiProvider`）+ 注入点（`AiHooks`）+ 实例字段覆盖

AI 接口的注入与 `traceCallback` 有一个关键差别：`traceCallback` 是**模块内部**接线，一个 setter 即可；AI 接口是**外部注入**，必须穿透多个 Processor 才能到达 `RtkCore`。

**核心原则：core 只定义「什么时候问」，不定义「谁回答」。** core 侧不得 `import` 任何 `rtklib-ai` 类型；`rtklib-ai` 侧只通过 `org.rtklib.java.ai.spi` 的**纯 JDK 类型**与 core 交互。依赖方向严格单向：`rtklib-ai → rtklib-core`（编译期），core 对 `rtklib-ai` **零引用**。

**`AiProvider`（core 侧 SPI，宿主唯一需要实现/注入的东西）**：

```java
/* rtklib-core: org.rtklib.java.ai.spi.AiProvider */
public interface AiProvider {
    /** 接口一：固定质量校验。返回 null = 本 provider 不提供接口一 */
    FixValidator fixValidator();

    /** 接口三：伪距偏差改正。返回 null = 本 provider 不提供接口三 */
    ObsCorrector obsCorrector();
}
```

**`AiHooks`（core 侧注入点，`ThreadLocal` 持有回调）**：

```java
/* rtklib-core: org.rtklib.java.ai.spi.AiHooks */
public final class AiHooks {
    private static final ThreadLocal<AiProvider> TL = new ThreadLocal<>();

    /** 宿主注入（rtklib-ai 或使用方调用），传 null 即整体关闭 */
    public static void install(AiProvider provider) { TL.set(provider); }

    /** 清除注入，恢复 v3.4.0 原生行为（测试 teardown 必须调用） */
    public static void clear() { TL.remove(); }

    /** 解析优先级：实例字段（Rtk）> 注入点。返回 null 即关闭 */
    static FixValidator resolveFixValidator(Rtk rtk) { /* 实例字段非 null 则取之，否则 provider==null ? null : provider.fixValidator() */ }
    static ObsCorrector resolveObsCorrector(Rtk rtk) { /* 同上 */ }
}
```

调用点保持唯一分支，可 grep：

```java
FixValidator fv = AiHooks.resolveFixValidator(rtk);
if (fv != null) { /* 唯一分支 */ }
```

**为什么用回调，而不是让 core 直接持有实现**：若 core 直接 `new` 或静态引用 `rtklib-ai` 的类，core 就反向依赖实验性模块（§3.1 的依赖污染立即成立），且 `rtklib-ai` 缺席时 core 无法编译。回调把「实现是否存在」推迟到运行时，core 编译期只认接口，从而**彻底避免 core 与 `rtklib-ai` 相互引用**。

**注入示例（`rtklib-ai` 侧或使用方）**：

```java
AiHooks.install(new OnnxAiProvider(modelDir));   // OnnxAiProvider 由 rtklib-ai 实现
// ... 解算 ...
AiHooks.clear();
```

**为什么不用层层注入**（三条理由均来自本仓库实际代码，非假想）：

1. **实例注入会静默丢失**。[PostPosProcessor.createRtk](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rinex/PostPosProcessor.java#L505-L516) 每次解算 `new Rtk()`，只拷 `opt` 与 `rtkConfig`，**不拷 `transient` 字段**；且前向（[L238](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rinex/PostPosProcessor.java#L238)）与反向（[L319](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rinex/PostPosProcessor.java#L319)）各调一次，赋值写在调用点极易漏掉反向，漏了不报错、只是 AI 静默不生效。同一坑已存在于既有先例：`traceCallback` 在 `PostPosProcessor` 上**根本没有 setter**（4 个 Processor 有，它没有）。
2. **接口三的实现对象要在 `static` 上下文可取到**。`relpos` 是 `static`，`ddres` 是 `private static`（[L1546](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1546)），`Rtk` 实例由调用方持有、`RtkCore` 不持有。注入点让 `RtkCore` 无需依赖调用方在每个入口正确设置字段。
3. **`joint` 模块完全在 Processor 体系之外**。[JointDdRes.java#L202-L206](file:///D:/code/rtklib_java/rtklib-joint/src/main/java/org/rtklib/java/joint/JointDdRes.java#L202-L206) 直接静态调用 `RtkCore.varerr`，实例注入到不了这里。v1 接口三不接线 joint（§4.3.5），但 v2 若要覆盖 joint，注入点是唯一可行路径。

**依赖方向严格单向**：`rtklib-core` 只定义 `AiProvider` / `FixValidator` / `ObsCorrector` 三个接口与上下文记录类型，**对 `rtklib-ai` 零引用**；`rtklib-ai` 依赖 `rtklib-core` 实现这些接口，由宿主（或 `rtklib-ai` 的 `OnnxAiProvider`）调用 `AiHooks.install()` 注入。core 的 `pom.xml` 中不得出现 `rtklib-ai`。

**注**：接口三 v1 **不修改 `varerr` 函数本体**（§4.3.5 已改为在 `ddres` 内乘系数），因此「`varerr` 入参是 `RtkState` record」这一条不再是当前设计的理由，仅作为 v2 下沉到 `varerr` 时的前置约束保留。

**为什么用 `ThreadLocal` 而非裸 `static`**：保住测试隔离，以及 §7.1 四组对照的并行执行能力。裸 `static` 会让 I-1 零回归验证退化为「测试顺序相关」——某用例注入后未清理，其后所有「原生行为」用例其实都在带 AI 跑，且**全绿**。

**保留实例字段覆盖**：多会话（不同站挂不同模型）与单测精确控制仍需出口。优先级：**实例字段 > 注入点**。

**`AiProvider` 的两个方法各自独立、可独立返回 `null`**。禁止在 provider 内返回一个「什么都不做」的 NOOP 对象来冒充「未启用」——**NOOP 默认实现会从这里溜回来**，正是本节要消除的东西。接口二已冻结（D1），连方法都不建。

**不设 `enable` 布尔量**。开关由 `null` 兼任；`enabled=true` 而对象为 `null`、`enabled=false` 却注入了对象，这两种非法态在类型层面无法构造。

**参数住 `RtkConfig.ai`（`AiOpts`，`Serializable`），行为对象住注入点/实例字段**。`RtkConfig` 在 `createRtk` 里被深拷贝，参数随配置自动传播；行为对象不随配置走，故注入时须打一条 INFO 日志，避免「配置里开了 AI、实际没跑」的静默不一致。

**代价（已知并接受）**：

1. **测试污染**：全局可变量使 I-1 验证依赖清理纪律 → 必须提供 `AiHooks.clear()`，且测试 teardown 断言 `AiHooks` 为空（§9 风险表）。
2. **并行 A/B 依赖线程传播**：若在 `rtklib-stream` 等线程池中使用，需在任务入口重新注入。
3. **配置无法复现行为对象**：保存的 `RtkConfig` 只能复现参数，模型与会话须由使用方重新注入。

### 3.3 三接口的精确调用位置

| 接口 | 文件 | 位置 | 语义 |
|---|---|---|---|
| 接口一 | [RtkCore.java#L476-L486](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L476-L486) | `boolean valOk = ...` 之后、`if (++rtk.nfix >= opt.minfix)` **之前** | 固定解产出后验证 |
| 接口二 | [manage_amb_LAMBDA](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L2098-L2107) | `skip` 判据之后、`resamb_LAMBDA` 之前 | **冻结，不实施** |
| 接口三 | [relpos 迭代内首轮](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L306-L323) + `ddres` 内两处 | **推理**：`for (i=0;i<opt.niter;i++)` 内、`i == 0` 的 `zdres(rover)` **成功之后**、首次 `ddres` 调用**之前**（[L307-L323](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L307-L323)），每历元 1 次批量推理，产出 `aiBias[]` / `aiScale[]` 局部数组；**施加**：`ddres` 的 `v[nv]` 组装处（[L1640-L1641](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1640-L1641)）与 `Ri`/`Rj` 赋值处（[L1748-L1749](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1748-L1749)），**只读查表** | 批量算改正 / 偏差扣除 / 方差膨胀 |

**接口一的位置必须精确到「`nfix` 自增之前」**，原因是该位置之后有三处不可逆副作用：

```java
if (++rtk.nfix >= opt.minfix) {          // 计数器自增
    if (opt.modear == ARMODE_FIXHOLD) holdamb(rtk, xa_arr);   // 模糊度保持
    if (opt.mode == PMODE_STATIC_START) opt.mode = PMODE_KINEMA;  // 模式切换
}
```

若在 `stat = SOLQ_FIX` 之后才拒绝，这三处已执行，无法回滚。放在 `valOk` 之后、`nfix` 之前，则拒绝只需「不进固定分支」，`rtk.x` / `rtk.P` 仍持有浮点解（[RtkCore.java#L437](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L437) 已拷入），回退天然正确。

**已知不可回滚的副作用**：`manage_amb_LAMBDA` 内部对 `prev_ratio1` / `prev_ratio2` / `excsat` / `lock[]` 的修改在接口一执行前已经发生，拒绝固定不会撤销它们。这**是期望行为**——ratio 确实很高，AR 历史应当如实记录。此点须写入实现注释，避免后续被当作 bug 修复。

**接口三为什么必须拆成「批量算 + 只读施加」两类位置**（性能硬约束，不是风格选择）：

`ddres` 有 **3 个调用点**：[L323](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L323)（迭代内）、[L431](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L431)（浮点解）、[L473](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L473)（固定解回代）。`niter` 默认 1（[PrcOpt.java#L215](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/data/PrcOpt.java#L215)），故每历元 `ddres` 实际执行 **2–3 次**。

在**单次** `ddres` 内，`varerr` 按双差对调用，每对调用两次（`Ri` 与 `Rj`，[L1748-L1749](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1748-L1749)）；双频 20–30 星约 76–116 次。乘以每历元 2–3 次 `ddres`，**合计约 150–350 次/历元**。若在 `varerr` 内做推理，按 1.2 ms/次即 **180–420 ms/历元**，直接不可用。

因此定死为：

1. **每历元算一次**：在迭代循环**第一轮**内、`zdres(rover)` 成功后、首次 `ddres` 之前（`i == 0` 守卫，[L307-L323](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L307-L323)）对 `ns × nf` 个「卫星-频点」批量推理，结果写入 `relpos` 的两个**局部**数组 `aiBias[ns*nf]` / `aiScale[ns*nf]`，作为参数传入 `ddres`。
   **为什么不能放在循环之前（[L306](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L306)）**：`zdres(rover)` 在循环体内（[L307-L311](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L307-L311)），仰角/方位角数组 `azel` 在本历元**尚未计算**；放在循环前只能读到**上一历元**的 `azel`，特征是错的。
2. **施加在 `ddres` 内、但只读**：偏差扣在 `v[nv]` 组装处，方差系数乘在 `Ri`/`Rj` 赋值处。两处都是纯查表，零推理。
3. **`varerr` 函数本体不改**：这使 `rtklib-joint` 的静态调用（[JointDdRes.java#L202-L206](file:///D:/code/rtklib_java/rtklib-joint/src/main/java/org/rtklib/java/joint/JointDdRes.java#L202-L206)）完全不受影响（§4.3.5）。
4. **不修改 `obs` / `y`**：`v[nv]` 每次调用都由 `y` 现场重算，在 `v[nv]` 上扣偏差**天然幂等**——`ddres` 跑 2 次还是 3 次都不会重复扣除（§4.3.6）。

**局部数组而非 `Rtk` 字段**：`aiBias`/`aiScale` 只在未注入时为 `null`、注入时才分配，`null` 路径零分配、零分支外开销，I-1 由此得到结构性保证（§3.4）。

接口一不存在此问题：每历元最多调用一次（固定解产出时），天然安全。这也是接口一必须先做的原因之一。

### 3.4 降级策略：两层，且必须是「不干预」

| 层 | 条件 | 行为 |
|---|---|---|
| 未注入 | 实例字段为 `null` **且** `AiHooks` 注入点取值为 `null` | 原生行为（I-1） |
| 运行时失败 | 模型缺失 / 加载失败 / 推理异常 / 超时 | **返回「不干预」**，记录一次告警，不抛出 |

「不干预」的定义（**与 §4.4 的返回类型严格对应**）：

- 接口一：`confidence = 1.0`。core 侧判 `1.0 >= ai.fixValidatorMinConfidence` 恒为 `accept`（阈值取值范围已限定 `[0,1]`，§4.5）
- 接口三：`biasMeters = 0`、`varianceScale = 1.0`（逐行，含 `nRows` 不足时的补齐行）

**为什么接口一用 `1.0` 而不是布尔 `accept`**：SPI 只输出 `confidence`，判定在 core（D9）。`1.0` 是「最高置信」在阈值语义下的唯一安全取值——若改为输出 `0.0` 表示「弃权」，则会触发拒绝固定，属 fail-closed，违反本节。

**禁止 fail-closed**：推理失败时不能拒绝固定（会把可用系统降级成不可用）。这一条与 §4.3.4 的「方差单侧膨胀」同源——AI 只允许保守，不允许激进。

**「运行时失败」是第三种状态，`null` 表达不了**：`obj != null` 但模型文件缺失、ONNX 抛异常、推理超时，都属此类。因此「没有默认值」这一说法要收窄——`null` 消除的是**配置层的默认**，消不掉**失败时的降级语义**。降级语义必须显式定义，不能留给实现者临场决定。

---

## 4. 接口契约

### 4.1 接口一：假固定判别

| 项 | 定义 |
|---|---|
| 目的 | 拦截**错误固定**，降低假固定率 |
| 自身不写的量 | 浮点解、LAMBDA 搜索、ratio、`prev_ratio1/2`、`excsat` |
| 输入 | `FixQualityContext`，6 维（§5.1），构造于 core 侧（§4.4.4、字段来源见 §4.4.5） |
| 输出 | `confidence`（`double`，`[0,1]`）。**不含 `accept`**（D9） |
| 判定 | **core 判**：`accept = confidence >= ai.fixValidatorMinConfidence` |
| 未注入 | `null` → 不进分支，行为与 v3.4.0 逐位一致（§3.4） |
| 拒绝后果 | `stat` 保持 `SOLQ_FLOAT`，输出浮点解，不进固定分支 |
| 拒绝的连带后果 | `nfix` **不自增**（因为跳过 `if (++rtk.nfix >= opt.minfix)` 整块），故 `minfix` 达到时间被推迟、`ARMODE_FIXHOLD` 的 `holdamb` 不执行、`PMODE_STATIC_START` 不切换。**这是拒绝生效的唯一机制，属期望行为** |
| 拒绝的审计 | 必须发 `Trace.emit("AR","AI_REJECT",..., "nb", nb, "ratio", ratio, "confidence", c, "threshold", t)`；不得静默跳过（否则线上无法回答「AI 拦了什么」） |
| 降级的审计 | 必须发 `Trace.emit("AR","AI_DEGRADE",..., "interface","fix_validator", "error", msg)`，**每会话一次**（用 `Rtk` 上一个 `transient boolean` 去重，避免逐历元刷屏） |

**契约要点 1**：接口一**只否决、不创造**。它不能让浮点解变固定，只能让固定解退回浮点。因此它对「固定率」的影响是**单调下降**，对「错误固定率」也是**单调下降**。评估必须同时看两个指标（§7.2）。

**契约要点 2**：特征中**禁止出现真值派生量**。任何由参考坐标计算的量（如固定解与真值偏差）不得作为输入特征——那是标签，不是特征。违反此条会产生训练集完美、部署失效的典型泄漏。

### 4.2 接口二：固定决策辅助（冻结，不实施）

**决策：冻结。** 不实施、不接线、不训练。保留本节仅为记录决策依据。

冻结理由（按重要性排序）：

1. **已被现有机制覆盖**：`arfilter` + `excsat` 已实现「是否值得尝试 / 剔除哪颗」的门控（§2.2）。
2. **净收益方向不明确**：接口一影响的是已固定解（可证伪的窄问题）；接口二影响的是「是否尝试」，误判的代价是**降低固定率**——而固定率是本项目 v3.4.0 已实测的核心指标（joint 单/双基线 FIX 91.5%）。为一个可能为负的收益去动核心指标，风险收益比不成立。
3. **特征与接口一重叠**：若接口一选方案 B（ADR/PRR/PCR），接口二候选特征几乎同集（§5.2）。两个接口共用特征但标签不同，等于把一个问题拆成两个训练目标，收益不明而维护成本翻倍。

**重开条件**（任一满足才重新评估）：

- 接口一已在 ≥2 个未参与训练的站上证明净收益，且冻结理由 2 的「降低固定率」被实测排除；
- 出现 `arfilter` 明确失效的可复现场景（例如长弧段 ratio 稳定高但始终不固定）。

### 4.3 接口三：观测改正

#### 4.3.1 目的与边界

对多路径 / NLOS 观测做**偏差改正 + 权重膨胀**，而非剔除（剔除会损失几何强度）。

**v1 三条硬边界**（必须定死，否则会污染双差自洽性与模糊度状态）：

1. **仅伪距**。载波相位不做偏差改正——相位偏差估计错误会污染模糊度估计，代价远大于收益。
2. **仅流动站**。双差在 `ddres` 内形成，多路径是**站址相关**误差；对基准站施加流动站学到的偏差会破坏双差的自洽性。v1 只改 `obs[0]`（流动站）。
3. **仅改正不剔除**。剔除由现有 `valpos` / IGGIII / `satexclude` 负责，接口三不重复。

#### 4.3.2 输入

逐卫星、逐频点特征（§5.3），来源为 `Ssat` 与 `Obsd` 的现有字段，无需额外计算。

#### 4.3.3 输出

完整定义见 §4.4.2（`CodeCorrection` 只有 `biasMeters` / `varianceScale` 两个分量，**不含 `sat`/`freq`**——行序即索引）。语义：

- `biasMeters`：伪距偏差改正量（米）。**符号约定**：DD 残差按 `v = (rover_ref − base_ref) − (rover_j − base_j)` 组装（[L1640-L1641](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1640-L1641)），故改正施加为

  ```java
  // refIdx / j 是卫星在 sat[] 中的下标（0-based），不是卫星号
  v[nv] -= aiBias[refIdx * nf + frq] - aiBias[j * nf + frq];
  ```

  即「参考星偏差减去本星偏差」，**不是**「整体减一个标量」。这与「先在流动站观测值上减去偏差、再做双差」在数学上等价，但只有前者幂等（§4.3.6）。

  **索引必须用 `sat[]` 下标，禁止用卫星号 `sat[i] - 1`**：`refIdx`/`j` 由 `ddres` 现场按 `iu[]/ir[]` 选出（[L1585-L1606](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1585-L1606)），同一历元内 3 次 `ddres` 调用的 `refIdx` 可能不同（参考星按仰角/SNR 评分择优）；而卫星号与下标之间没有固定映射，用卫星号索引会越界或错行。
- `varianceScale`：方差膨胀因子，**必须 ≥ 1.0，且 core 侧钳位到 `ai.varianceScaleMax`**（§4.3.4）
- **仅 `code == true`**：`code` 由 `f >= nf` 判定（[L1583](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1583)），`frq = f % nf`（[L1582](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1582)）；相位不施加偏差，但**同样施加 `varianceScale`**（§4.3.1）

#### 4.3.4 不确定性传播（关键契约）

**问题**：`biasMeters` 是一个**均值估计**，必然带残差方差 `σ_m²`。若只减偏差、不把 `σ_m²` 计入 `R`，滤波器会过度自信 → 协方差低估 → ratio 虚高 → **假固定率上升**，与接口一目标反向。

**契约**：

```
R_i' = varianceScale_i × varerr(...)
```

施加位置（**两类，不得合并**，理由见 §3.3）：

| 位置 | 时机 | 动作 |
|---|---|---|
| `relpos` 迭代**首轮**内、`zdres` 之后、首次 `ddres` 之前（`i == 0`，[L307-L323](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L307-L323)） | 每历元 1 次 | 批量推理 `ns × nf` 个「卫星-频点」，产出 `aiBias` / `aiScale` 两个局部数组 |
| `ddres` 的 `v[nv]` 组装处（[L1640-L1641](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1640-L1641)） | 每次 `ddres` | 查表扣偏差：`v[nv] -= aiBias[refIdx*nf+frq] - aiBias[j*nf+frq]`（**仅 `code`**） |
| `ddres` 的 `Ri`/`Rj` 赋值处（[L1748-L1749](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1748-L1749)），**在 `if (!code)` 半周补偿之后**（[L1751-L1752](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1751-L1752)） | 每次 `ddres` | 查表乘系数：`Ri[nv] *= aiScale[refIdx*nf+frq]`、`Rj[nv] *= aiScale[j*nf+frq]` |

**顺序敏感**：`varianceScale` 必须在 `Ri[nv] += 0.01` 之后施加（[L1750-L1753](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1750-L1753)）。若乘在半周补偿之前，`0.01` 会被膨胀因子放大，相位半周历元的方差被系统性高估。

**偏差改正会改变 `maxinno` 剔除行为（已知且期望）**：`maxinno` 门限判定在 `v[nv]` 组装之后、`Ri/Rj` 计算之前（[L1735-L1740](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1735-L1740)）。改正后的残差更小，被剔除的观测更少，`vsat`/`rejc` 也随之变化。这属期望行为（改正的目的就是让残差回到正常量级），但它意味着接口三**间接影响卫星可用性**，评估时必须与原生组对比 `ns` 的变化，不能只看固定率。

**索引方向必须照抄，不得凭直觉对调**：

- `Ri[nv]` ↔ **参考星** `sat[refIdx]`（`Ri` 的 `i` 是 reference）
- `Rj[nv]` ↔ **非参考星** `sat[j]`
- 证据：[L1748](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1748) 传入 `sat[refIdx]`，[L1749](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1749) 传入 `sat[j]`
- 后果若对调：两颗星的膨胀因子互换，且**不报错、不越界**——纯粹静默错误，评估时表现为「模型无效但看不出原因」

**钳位（core 侧强制，不信任 impl）**：

```java
double s = corr.varianceScale();
if (!Double.isFinite(s) || s < 1.0) s = 1.0;            // 单侧膨胀，下限硬钳
if (s > ai.varianceScaleMax) { s = ai.varianceScaleMax; clampWarnOnce(); }

double b = corr.biasMeters();
if (!Double.isFinite(b) || Math.abs(b) > ai.obsCorrectorMaxBias) {
    b = 0.0;                                            // 偏差置 0，方差照旧施加
    biasClampWarnOnce();
}
```

- 下限 `1.0` 硬钳而非报错：模型输出 `0.9` 时按 1.0 处理，等价于该行弃权，不改变系统行为
- 上限钳位**必须告警**：模型持续输出 `1e6` 会让该卫星静默失效（权重≈0）却不报错，等于模型在偷偷剔除卫星，与 §4.3.1「仅改正不剔除」冲突
- `NaN`/`Inf` 按 1.0 处理：这是降级路径（§3.4），不是钳位路径
- **`biasMeters` 超限时置 0，而不是钳到边界值**：钳到 `±10 m` 等于给观测强加一个 10 m 的错误改正，比不改更糟；置 0 则是「本行偏差弃权」，与 §4.3.1 一致。且此时**必须保留 `varianceScale`**——它是模型对自己不确定性的诚实申报，不应因偏差越界而丢弃
- `biasMeters` 的 `NaN`/`Inf` 同样置 0（不是按 1.0，单位不同不可混用）

**IFLC 边界（v1 必须禁用）**：`ionoopt == IONOOPT_IFLC` 时 `nf` 被强制为 1（[L152](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L152)），`y` 中已是**消电离层组合量**，逐频点偏差的物理含义不再成立。因此接口一、接口三**均在 core 侧门禁**（§4.3.7），而非由 impl 自行判断。

**两条强制规则**：

1. **单侧膨胀**：`varianceScale ≥ 1.0`。模型**不得**缩小方差（缩小等于宣称比解析误差模型更可信，无依据）。
2. **成对输出**：施加了 `biasMeters ≠ 0` 的卫星，其 `varianceScale` 必须满足
   `varianceScale ≥ 1.0 + (σ_m / σ_obs)²`，其中 `σ_obs² = varerr(...)`。
   训练侧须同时回归 `bias` 与 `σ_m`；只输出 `bias` 的模型视为**契约违规**，实现侧必须 fail-fast 报错（而不是静默按 1.0 处理）。

**为什么单侧**：这是唯一能让接口三「无论模型多差都不会让系统更差」的结构性保证——模型若无效，`bias` 接近 0、`varianceScale` 接近 1，退化为原生行为；模型若给出错误偏差但方差如实膨胀，滤波器会通过降权自动削弱它。**方差是 AI 的安全带。**

#### 4.3.5 与 `rtklib-joint` 的边界

`rtklib-joint` 的 [JointDdRes.java#L202-L206](file:///D:/code/rtklib_java/rtklib-joint/src/main/java/org/rtklib/java/joint/JointDdRes.java#L202-L206) 直接静态调用 `RtkCore.varerr`，且**不在 `relpos` 调用链内**，拿不到 `relpos` 的局部 `aiBias`/`aiScale`。

因此定死：

1. v1 的接口三**只覆盖 `RtkCore.relpos` 的双差链路**，joint 的解算路径**不受任何影响**（`varerr` 本体不改、`ddres` 不共享）。
2. 若 v2 要覆盖 joint，**必须复用同一张表**（由 joint 自己的批量推理产出），禁止在 joint 内逐星单独推理——同一历元两次推理既翻倍耗时，又会让两条链路的偏差不一致。
3. `RtkCore.varerr` 是 `public static`（[L1450](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1450) 附近），**任何写进 `varerr` 的改动都会外溢到 joint 与所有外部调用方**。这是 v1 禁止改 `varerr` 的第二个理由（第一个是 §3.3 的调用次数）。

#### 4.3.6 施加语义：幂等、逐行、成对

**幂等性的来源**：`ddres` 每次调用都从 `y[]` **现场重算** `v[nv]`（[L1640-L1641](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1640-L1641)），`v[nv]` 不被累积修改。故在赋值语句后紧接一行扣除，无论 `ddres` 跑 2 次还是 3 次，每次都是「从原始 `y` 算出的残差 − 同一个偏差」，**不会重复扣除**。

**禁止的三种写法**（都会破坏幂等或自洽性）：

| 禁止写法 | 后果 |
|---|---|
| 修改 `obs[0].P[i]` / `obs[0].L[i]` 观测值本身 | 偏差被写入历元观测，下一历元沿用；且基准站未同步改正，双差不再自洽 |
| 修改 `y[f + idx * nf * 2]` | `y` 是 `zdres` 产物，同一历元被 3 次 `ddres` 复用；改正会被叠加 3 次 |
| 只在某一次 `ddres` 调用中施加（如仅 L473 的固定解回代） | 浮点解与固定解的残差来自不同世界，AR 输入不一致；且随 `niter` 变化而行为漂移 |

**逐行语义**：`aiBias`/`aiScale` 长度为 `ns*nf`，行序 `idx = i*nf + frq`（`i` = `sat[]` 下标）。`biasMeters` 只对 `code` 行生效；`varianceScale` 对同一 `(i, frq)` 的 **code 行与 carrier 行同时生效**（同一颗星同一频点的观测质量是同一个判断，不应让伪距与相位拿到互相矛盾的权重）。

**成对输出的 fail-fast**：见 §4.3.4 两条强制规则。impl 返回 `varianceScale == null` 而 `biasMeters != null` 时，core **直接按降级处理并发 `AI_DEGRADE` trace**，不猜测默认值。

#### 4.3.7 IFLC 统一门禁（core 侧，接口一与接口三共用）

**决策：门禁放在 core 的注入点，不放进 context 让 impl 判断。**

理由：`ionoopt` 是**解算配置**，不是「本历元的观测事实」。把它塞进 `FixQualityContext` 会让接口一的 impl 需要理解一个与特征无关的 core 枚举，且**两个 impl 都要各自实现一遍门禁**——一旦其中一处漏写，IFLC 下就会用消电离层组合量去套「逐频点偏差」的模型，输出无意义但不报错。门禁集中在 core 侧，只有一处，且与 §3.4「未注入 = 不干预」是同一语义。

**定死的门禁行为**（`opt.ionoopt == Constants.IONOOPT_IFLC` 时）：

| 接口 | 门禁行为 | 等价于 |
|---|---|---|
| 接口三 | **不调用 `ObsCorrector.correct()`**，`aiBias`/`aiScale` 保持 `null` | 未注入 |
| 接口一 | **不调用 `FixValidator.validate()`**，本历元按 `accept` 处理（**不拒绝固定**） | 未注入 |

**为什么接口一按 `accept` 而非 `reject`**：门禁是「该接口在本配置下不可用」，不是「本历元判定为错误固定」。默认放行保持 I-1（IFLC 下若未注入，行为与 v3.4.0 逐位一致），若按 `reject` 处理则等于 AI 在 IFLC 场景下**无条件禁用固定解**——这是比不启用更激进的干预，且与 §3.4「禁止 fail-closed」直接冲突。

**trace**：每会话一次 `AR/AI_DEGRADE`，`reason=ionoopt=IFLC`，受 `AiOpts.traceDegrades` 控制。**只发一次**，不要每历元刷屏（IFLC 是全配置生效，不是偶发事件）。

**门禁判定位置**：`relpos` 内已有 `nf` 局部量（[L152](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L152)），接口三门禁复用同一判断即可，**不重复读 `opt.ionoopt`**：

```java
boolean aiUsable = (opt.ionoopt != Constants.IONOOPT_IFLC);
```

---

### 4.4 SPI 完整定义（可直接编译）

**位置**：`rtklib-core/src/main/java/org/rtklib/java/ai/spi/`。**零第三方依赖**，只用 JDK 类型（`int` / `double` / `double[]` / `record`），故 core 编译期不引入任何 AI 生态。

#### 4.4.1 接口一：`FixValidator`

```java
package org.rtklib.java.ai.spi;

public interface FixValidator {
    /**
     * 固定质量校验。返回置信度，范围 [0,1]，1.0 = 完全信任（等价于不干预）。
     * 禁止抛异常：任何异常由 core 侧捕获并按 1.0 处理（§3.4 降级）。
     * 禁止在此方法内修改任何 Rtk / Ssat 状态。
     */
    double validate(FixQualityContext ctx);
}
```

**为什么只输出 `confidence` 而不输出 `accept`**（D9）：若 SPI 输出布尔，阈值判断就有两处（impl 内一处、core 内一处），两处不一致时会出现「impl 认为该拒、core 认为该收」的静默矛盾；且阈值随数据变化需调参时，impl 重新训练才能改阈值。只输出连续量，**阈值永远在 core 的 `AiOpts` 里**，调阈值不需要换模型。

#### 4.4.2 接口三：`ObsCorrector` + `CodeCorrection`

```java
package org.rtklib.java.ai.spi;

public interface ObsCorrector {
    /**
     * 批量推理：一次调用覆盖 ns*nf 个「卫星-频点」组合。
     * 返回 null，或返回值的数组长度 != ctx.ns()*ctx.nf() → 视为降级（bias=0, scale=1）。
     * 禁止逐卫星回调（每历元只允许一次调用，§3.3）。
     */
    CodeCorrection correct(ObsCorrectionContext ctx);
}
```

```java
package org.rtklib.java.ai.spi;

/**
 * 行序即索引：idx = i*nf + frq，i 为卫星在 relpos 的 sat[] 中的下标（0-based）。
 * 两个数组长度必须都等于 ns*nf；任一为 null 即视为降级。
 */
public record CodeCorrection(double[] biasMeters, double[] varianceScale) {
    public static final CodeCorrection NONE = new CodeCorrection(null, null);
}
```

**`CodeCorrection` 不含卫星号/频点号字段**：若带卫星号，core 就要做「卫星号 → 下标」映射，而 `refIdx`/`j` 是下标（[L1585-L1606](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1585-L1606)），映射一旦写错就是静默错行。**行序约定是唯一索引**，core 与 impl 共用同一份 `ctx.sats()` 顺序，零映射。

#### 4.4.3 `ObsCorrectionContext`（接口三输入）

```java
package org.rtklib.java.ai.spi;

public record ObsCorrectionContext(
        int epoch,          // rtk.epoch
        int ns,             // 参与双差的卫星数（relpos 局部变量 ns）
        int nf,             // 频点数（IFLC 时恒 1，此时两接口均被门禁屏蔽，§4.3.7）
        double dt,          // 采样间隔（秒）
        SatFeature[] sats) { }   // 长度 == ns，顺序 == relpos 的 sat[] 顺序
```

```java
package org.rtklib.java.ai.spi;

/** 单星特征。所有长度 nf 的数组均由 core 按 nf 分配并填充，impl 不得写入。 */
public record SatFeature(
        int sat,             // 卫星号，仅用于 trace/调试，不参与索引
        int sys,             // SatUtils.satsys(sat, null)
        double az,           // azel[iu[i]*2]      —— 工作缓冲区，非 ssat.azel
        double el,           // azel[iu[i]*2 + 1]
        double[] snrRover,   // ssat[sat-1].snrRover
        double[] snrBase,    // ssat[sat-1].snrBase
        double[] resp,       // ssat[sat-1].resp    —— 上一历元伪距残差
        double[] resc,       // ssat[sat-1].resc    —— 上一历元载波残差
        int[] lock,          // ssat[sat-1].lock
        int[] slip,          // ssat[sat-1].slip
        int[] half,          // ssat[sat-1].half
        double[] gf,         // ssat[sat-1].gf
        int[] vsat,          // ssat[sat-1].vsat
        int[] fix) { }       // ssat[sat-1].fix     —— 0未固定/1浮点/2固定/3已锚定
```

#### 4.4.4 `FixQualityContext`（接口一输入）

```java
package org.rtklib.java.ai.spi;

public record FixQualityContext(
        int epoch,          // rtk.epoch；供 impl 去重/trace，不是特征（见下）
        int ns,             // rtk.sol.ns（L442-L451 统计的非零 vsat 卫星数）
        int nf,             // 频点数；供 impl 归一化/对账，不是特征（见下）
        int nb,             // manage_amb_LAMBDA 返回值（L462）
        float ratio,        // rtk.sol.ratio（L1970/L1880）
        double pdop,        // 局部重算，见 §5.1
        double adrRms,      // 模糊度差 RMS，见 §5.1
        double prrRms,      // 载波残差 RMS，见 §5.1
        double pcrRms,      // 伪距残差 RMS，见 §5.1
        int nCodeRows,      // pcrRms 的有效行数（0 表示无有效伪距行），不是特征
        int nPhaseRows) { } // prrRms 的有效行数（0 表示无有效载波行），不是特征
```

**`nfix` 不在本 record 里，且不得加**：`rtk.nfix` 是「连续满足固定条件的历元计数」，**不是 §5.1 的 6 维特征之一**。把它塞进 context 会让 impl 拿到一个无处安放的字段（要么当特征用 → 特征集悄悄从 6 维变 7 维，§8 阶段 2 的 `6→16→1` 与 §6.2 的 `N = 5` 全部失效；要么忽略 → 字段纯冗余，且诱导后续实现者「顺手用一下」）。若将来确实要把它作为特征（它衡量固定状态连续性，对「稳定错误固定」有判别力），必须走 §10 决策流程，同步改 §5.1 维数、§8 输入维、§6.2 列数，**不允许局部加字段**。

**字段与特征的对应关系（定死，避免字段漂移）**：

| context 字段 | 对应特征 | 说明 |
|---|---|---|
| `adrRms` | f0 `adr` | |
| `prrRms` | f1 `prr` | 有效性由 `nPhaseRows > 0` 判定 |
| `pcrRms` | f2 `pcr` | 有效性由 `nCodeRows > 0` 判定 |
| `ratio` | f3 `ratio` | 对照基线 |
| `nb` + `ns` | f4 `ns_fixed` | **`ns_fixed = nb / ns` 由 impl 做除法**，core 不预先算 |
| `pdop` | f5 `pdop` | |
| `nCodeRows` / `nPhaseRows` | — | **不直接入特征**：它们只用于判定 `pcrRms`/`prrRms` 是否可信（`0` = 该 RMS 是占位值而非真实测量） |
| `nf` | — | **不直接入特征**：供 impl 做特征归一化、调试输出，以及与 `nCodeRows`/`nPhaseRows` 对账（无剔除时应满足 `nCodeRows + nPhaseRows = (ns - 1) * nf * 2`，见 §5.1 `v[]` 行数规则） |
| `epoch` | — | **不直接入特征**：供 impl 去重、trace 与短窗跨会话重置使用（§5.3） |

`nCodeRows == 0` 时 `pcrRms` 必须填 `0.0`，且**模型必须能处理该退化输入**（单频 DGPS 场景可能无载波行）。用 `0.0` 而非 `NaN`：`NaN` 进入 ONNX 会污染整个 batch 输出。

**为什么 `ns_fixed` 的除法留在 impl**：core 侧算就得引入 `double` 字段，而 `nb`/`ns` 都已在 record 里；在 impl 里算不增加接口面，且 `ns == 0` 的退化（本分支不会出现，但 impl 不应依赖该前提）由 impl 自己兜底为 `0.0`。**`ns <= 0` 时 `ns_fixed` 必须取 `0.0`，不得除零**。

#### 4.4.5 字段来源与取值时机（接口一，精确到行）

插入点：`if (valOk) {`（[L477](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L477)）之后、`if (++rtk.nfix >= opt.minfix)`（[L478](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L478)）之前。

| 字段 | 来源 | 陷阱 |
|---|---|---|
| `ratio` | `rtk.sol.ratio`（L1970 / L1880） | 已是**本历元**值，可直取 |
| `ns` | `rtk.sol.ns`（L442-L451） | 是「非零 `vsat` 卫星数」，不是 `relpos` 的局部 `ns` |
| `nf` | `relpos` 的局部 `nf`（[L152](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L152)） | 已是 IFLC 归一后的值（IFLC 时恒 1）；**不要用 `opt.nf`**，否则 IFLC 下会与实际链路不符 |
| `nb` | `nb`（L462） | `nb <= 1` 时不会进入本分支（L467 守卫） |
| `adrRms` | `rtk.x` vs `xa_arr`，索引 `IB(sat[i], f, opt)`（[L703](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L703)） | 只统计 `x[ib] != 0 && xa_arr[ib] != 0` 的项；`xa_arr` 是 L460 的工作缓冲区，插入点仍在作用域内 |
| `prrRms` / `pcrRms` | L473 那次 `ddres` 的 `v[]` + `vflg[]` + `nv` | 解码规则见 §5.1；**`vflg` 没有有效位** |
| `pdop` | `wb.azel` + `RtklibCommon.dops`（[L596](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L596) 同签名） | **`rtk.sol.pdop` 恒为 0**，见 §5.1，禁用 |
| `epoch` | `rtk.epoch` | 直取 |

**插入点为什么必须在 L478 自增之前**：`if (++rtk.nfix >= opt.minfix)` 这个分支体在接口一拒绝时会被整体跳过（§4.4.2）。若插入点放在 L478 之后，`nfix` 已自增，拒绝就无法阻止计数推进——本历元会被计为一次成功固定，`minfix` 门槛与 `rtk.nfix` 的语义都被破坏。**插入点在 L477 之后、L478 之前是唯一正确位置**，与 `nfix` 是否入特征无关。

**接口一与接口三同时开启时的耦合**：接口三的偏差施加在 `v[nv]` 上（§4.3.6），因此 L473 的 `v[]` 已是**改正后**残差，`prr`/`pcr` 随之变化。这是设计选择（接口一看到的是「已改正的世界」），但**训练与推理必须固定在同一开关组合下**，禁止「关接口三采特征、开接口三推理」。

---

### 4.5 `AiOpts` 字段定义

`AiOpts` 挂在 `RtkConfig.ai`，`Serializable`，**逐字段 `copyFrom`**（§4.5.3）。

#### 4.5.1 字段表

| 字段 | 类型 | 默认值 | 取值范围 | 生效位置 |
|---|---|---|---|---|
| `fixValidatorMinConfidence` | `double` | `0.5` | `[0.0, 1.0]` | 接口一判定：`accept = confidence >= 该值`（L477 分支内） |
| `varianceScaleMax` | `double` | `4.0` | `[1.0, +∞)` | 接口三方差膨胀上限钳位（§4.3.4） |
| `obsCorrectorMaxBias` | `double` | `10.0` | `(0.0, +∞)`，`≤ 0.0` 时钳到 `10.0` 并告警 | 接口三偏差幅值钳位（§4.3.4）：任一行满足 `Math.abs(biasMeters) > 该值` 时，**该行 `biasMeters` 置 0**（`varianceScale` 仍按原值施加，因为它只膨胀不缩小），并发一次 `AI_DEGRADE` trace |
| `traceRejects` | `boolean` | `true` | — | 接口一拒绝时是否发 `AR/AI_REJECT` trace（§4.1 审计） |
| `traceDegrades` | `boolean` | `true` | — | 降级时是否发 `AR/AI_DEGRADE` trace（每会话一次） |
| `enableAggressiveAi` | `boolean` | `false` | — | 激进模式开关（§4.6）：`false` 时 `varianceScale` 下限硬钳 `1.0`（单侧膨胀）；`true` 时下限放开到 `aggressiveMinScale` |
| `aggressiveMinScale` | `double` | `0.1` | `(0.0, 1.0]`，`> 1.0` 钳到 `1.0`（等价保守模式）并告警 | 激进模式下 `varianceScale` 的硬下限，防止法方程奇异 |

**关于两个激进模式字段**：它们是 §4.6 的实现入口，**默认值即保守模式**，因此 `AiOpts` 全默认时 I-1 仍成立（字段存在但不参与任何数值路径：`enableAggressiveAi=false` 时 `RtkCore` 走 `minScale = 1.0` 分支，与 v3.4.0 逐位一致）。`enableAggressiveAi` 是**唯一**的例外性 `enable` 布尔量：它不表示「AI 是否启用」（那仍由是否注入 `AiProvider` 兼任），只表示「钳位口径是否放宽」，二者语义不同。

**没有 `enableXxx` 布尔量**（§3.2）：开关由「是否注入了对象」兼任。`AiOpts` 里出现 `enable` 字段就意味着一份「开了但没生效」的配置合法存在，这是要消除的非法态。唯一例外是 `enableAggressiveAi`：它控制**钳位口径**而非 AI 开关，关掉它不会让 AI 失效，只会把 `scale` 下限收回 `1.0`。

**没有 `modelPath`**：模型路径属于**实现**（`rtklib-ai` 的 `OnnxAiProvider` 构造参数），不属于 core 的配置。放进 `AiOpts` 会让 core 侧 `RtkConfig` 携带一个它永远不读的字符串，并诱导「配置即能启用 AI」的误解。

**没有推理超时字段**：Java 侧对同步调用做超时只能靠额外线程，代价远大于收益；超时由 impl 自行保证（模型体积与输入规模在阶段 2 定死）。

#### 4.5.2 校验

`AiOpts` 在**首次被读取时**做一次范围校验（不是构造时，避免反序列化默认值触发）：

```java
if (fixValidatorMinConfidence < 0.0 || fixValidatorMinConfidence > 1.0) → 钳到 [0,1] + 告警
if (varianceScaleMax < 1.0) → 钳到 1.0 + 告警
if (obsCorrectorMaxBias <= 0.0) → 钳到 10.0 + 告警
if (aggressiveMinScale 非有限 或 <= 0.0) → 回退 0.1 + 告警
if (aggressiveMinScale > 1.0) → 钳到 1.0 + 告警（等价保守模式）
```

钳位而非抛异常：配置错误不应让解算崩溃（与 §3.4「AI 只允许保守」一致）。

#### 4.5.3 `copyFrom`（必须实现，否则重建即丢参数）

`RtkConfig` 是逐字段 `copyFrom`，`createRtk` 每次解算都重建 `Rtk` 并拷贝配置（[PostPosProcessor.java#L505-L516](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rinex/PostPosProcessor.java#L505-L516)）。若 `AiOpts` 不参与拷贝，前向/反向两次 `createRtk` 会把参数重置为默认值，表现为「配置里改了阈值但行为没变」。

```java
public void copyFrom(AiOpts o) {
    if (o == null) return;
    this.fixValidatorMinConfidence = o.fixValidatorMinConfidence;
    this.varianceScaleMax          = o.varianceScaleMax;
    this.obsCorrectorMaxBias       = o.obsCorrectorMaxBias;
    this.traceRejects              = o.traceRejects;
    this.traceDegrades             = o.traceDegrades;
    this.enableAggressiveAi        = o.enableAggressiveAi;
    this.aggressiveMinScale        = o.aggressiveMinScale;
}
```

并在 `RtkConfig.copyFrom` 内加一行 `this.ai.copyFrom(other.ai);`（`ai` 字段**不得为 `null`**，默认 `new AiOpts()`；用 `null` 表示「未配置」会引入第三种状态）。

---

### 4.6 输出钳位的两种模式：保守（默认）/ 激进（**已实现为实验配置，默认关闭**，D19）

> 本节由原 `docs/AI补充.md` §2 并入（v1.10）。**设计已落地为可开关的实验配置（v1.11）**：`AiOpts.enableAggressiveAi` + `AiOpts.aggressiveMinScale` 已进入代码与 `copyFrom`，`RtkCore.aiComputeCorrection` 的 `scale` 下限由固定 `1.0` 改为按开关取值。**默认值即保守模式**，全默认时与 v3.4.0 逐位一致（I-1 成立）。

#### 4.6.1 动机

§4.3.4 的钳位对**超短基线静态场景**（追求绝对稳）是正确的：`varianceScale` 单侧膨胀（`≥ 1.0`）保证「模型再差也不会让系统更差」，偏差超限置 0 保证「不施加错误改正」。但对**城市峡谷等动态场景**，这套约束可能过紧——此时观测质量确实可能优于解析 `varerr` 的默认假设，且系统性偏差可能超过 `obsCorrectorMaxBias`。因此提供一个可选的「激进模式」，**仅供实验与对照，不作生产默认**。

#### 4.6.2 实现规格（已落地，逐条对应代码）

| 项 | 保守模式（**默认**） | 激进模式（实验，`enableAggressiveAi=true`） |
|---|---|---|
| 配置 | `enableAggressiveAi = false`（默认值），行为与 v3.4.0 一致 | `AiOpts.enableAggressiveAi = true`，已纳入 `copyFrom`（§4.5.3） |
| `varianceScale` 下限 | 硬钳 `1.0`（`< 1.0` 按 `1.0`，等价弃权） | 下限取 `aggressiveMinScale`（默认 `0.1`），**仍为硬下限**，防止法方程奇异 |
| `varianceScale` 上限 | `varianceScaleMax`（默认 `4.0`） | 同左（上限**不放宽**） |
| `biasMeters` 超限 | 超 `obsCorrectorMaxBias` 即置 0（不钳到边界） | **同左**（偏差幅值上限**不放宽**，仅 scale 下限放开） |
| 训练侧配合 | `train.py` 默认 `--scale-min 1.0` | 须用 `--scale-min` 取同一值训练（如 `0.5`），并同步 `export_onnx.check_contract(scale_min=…)`；否则模型输出被钳回下限，等于静默退回保守模式 |
| I-1 不变量 | 满足 | **同样满足**：`enableAggressiveAi=false` 时不进入任何新数值分支 |

**代码落点**：

- `AiOpts`：`DEFAULT_AGGRESSIVE_MIN_SCALE = 0.1`、`enableAggressiveAi`、`aggressiveMinScale`、`clampMinScale()` 校验（§4.5.2）。
- `RtkCore.aiComputeCorrection`：`double minScale = (ai != null && ai.enableAggressiveAi) ? ai.aggressiveMinScale : 1.0;`，原 `s < 1.0 → 1.0` 改为 `s < minScale → minScale`。
- 训练/契约：`build_model(..., scale_min)` 输出头为 `scale = scale_min + (scale_max−scale_min)·sigmoid(…)`；`check_contract(..., scale_min)` 区间校验同步。
- 测试入口：`DualInterfacePairingTest` 的 `-Dpairing.aggressive` / `-Dpairing.min-scale` 透传到 `RtkConfig.ai`。

**本次实现的边界（刻意不做）**：`bias` 上限**未放宽**（§4.6.3 理由 1 的核心风险在 `scale < 1.0`，放宽 `bias` 属另一条独立风险，须单独论证）；`varianceScaleMax` **未提高**；未引入任何自动切换（激进/保守只能由配置显式指定）。

#### 4.6.3 为什么仍然默认关闭（三条理由不变）

1. **激进模式直接违反 §4.3.4 的强制规则 1（单侧膨胀），使「方差是安全带」这一结构性保证失效。** 允许 `scale < 1.0` 等于让模型宣称「我比解析误差模型更可信」，而当前没有任何可审计的判据支持这一宣称；一旦模型系统性偏乐观，后果是协方差低估 → ratio 虚高 → **假固定率上升**，正是接口一/接口三共同要防的方向。这条不是「调参风险」，是**契约级冲突**。
2. **本数据无法验证其收益。** 现有全部结论（§8.2 / §8.3）来自 6 站 × 4 日、1.1 km × 1.0 km 山谷、静态站、近似同址（§8.2.9）——既无城市峡谷，也无动态载体，激进模式的适用场景**一个样本都没有**。
3. **I-1 需持续回归。** 新增布尔字段必须走「未注入即逐位一致」的回归验证（§1.3），且后续任何 `minScale` 取值变化都要重跑该回归。

#### 4.6.4 与「端到端」的关系

激进模式与方案 B（端到端坐标 Loss，§0.1 / §8.4）**没有依赖关系**：前者改的是 core 的钳位口径，后者改的是训练目标函数。即使未来 E2E 满足 §8.4.6 的四条前置条件，激进模式仍须**单独**过 D19 的重开条件。

#### 4.6.5 实测记录（单站单日探针，2026-11-12）

用 `--scale-min 0.5` 重训的模型（实测 `scale` 输出范围 `[0.5, 3.999]`）在 `DEVICE_ID_REDACTED / 2026-06-06` 全 24 小时跑四组配对：

| 组 | 固定率 | 错误固定率 | 错固绝对数 | H RMS | V RMS |
|---|---|---|---|---|---|
| A（基线） | 34.49% | 11.11% | 212 | 116.9 cm | 225.8 cm |
| C（仅接口三，激进） | 38.79% | 6.80% | 146 | 89.3 cm | 160.1 cm |
| D（双接口，激进） | 38.59% | 6.46% | 138 | 89.3 cm | 160.1 cm |

与同站同日保守口径（C：41.17% / 8.29% / 189、D：40.96% / 7.94% / 180）相比：**固定率均下降约 2.4 pp，错误固定率再降约 1.5 pp，错固绝对数少 42~43**。结论：激进模式在本场景下**以固定率为代价换错固下降**，不是净改善，与 §4.6.3 理由 2 的预期一致。**单站单日样本，不足以支撑任何交付判断**；全量 576 run 未跑。

---

## 5. 特征定义（定死）

特征集必须在设计阶段一次定死，否则阻塞特征提取与训练管线搭建。此处全部定死，依据为「物理可解释 + 现有字段可直取」。

### 5.1 接口一：6 维固定后残差类特征（决策 D2：选方案 B）

**决策：采用方案 B（残差类特征 + 少量传统量），放弃方案 A（7 维统计量）。**

| # | 特征 | 定义 | 来源（精确到行） | 物理解释 |
|---|---|---|---|---|
| 1 | `adr` | 模糊度差 RMS：`sqrt(mean((x[ib] − xa_arr[ib])²))`，`ib = IB(sat[i], f, opt)`，只取 `x[ib] != 0 && xa_arr[ib] != 0` 的项 | `rtk.x` 与 `xa_arr`（[L460](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L460)、`manage_amb_LAMBDA` 输出）；索引函数 [IB](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L703) | 整数解离浮点解多远 |
| 2 | `prr` | 相位残差 RMS：L473 那次 `ddres` 的 `v[]` 中 `isCode == 0` 行的 RMS | `v[]` / `vflg[]`（[L1766](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1766)） | 整数解与相位的拟合一致度 |
| 3 | `pcr` | 伪距残差 RMS：同上 `isCode == 1` 行 | 同上 | 整数解与伪距的拟合一致度 |
| 4 | `ratio` | LAMBDA ratio | `rtk.sol.ratio`（[L1970](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1970) / [L1880](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1880)） | 搜索空间可分性（保留作对照基线） |
| 5 | `ns_fixed` | `nb / rtk.sol.ns` | `nb`（[L462](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L462)）、`rtk.sol.ns`（[L442-L451](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L442-L451)） | 几何冗余度 |
| 6 | `pdop` | 位置 DOP，**局部重算**（`rtk.sol.pdop` 恒 0，不可用） | `wb.azel` + `RtklibCommon.dops`（[L596](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L596) 同签名） | 几何强度 |

**`vflg` 解码规则（[L1766](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1766)）**——`vflg[nv]` 只有一个位域，**没有「有效位」**：

```java
int satRef    = (vflg[nv] >> 16) & 0xFF;
int satJ      = (vflg[nv] >>  8) & 0xFF;
boolean isCode = ((vflg[nv] >> 4) & 0x01) == 1;
int frq        =  vflg[nv] & 0x0F;
```

`v[]` 中每一行都是**有效行**：无效行在 `nv++` 之前就 `continue` 了（[L1740](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1740) 的 `maxinno` 剔除、`validobs` 过滤等），因此不存在「按位过滤」这一步。**误把 `vflg & 1` 当作有效位（它其实是 `frq`）会静默丢掉所有奇数频点的行**，`prr`/`pcr` 变成半数样本的 RMS 而不报错。

**`pdop` 为什么必须局部重算**：`rtk.sol.pdop` 的赋值在 [L586-L598](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L586-L598)，输入是 `rtk.ssat[i].azel`；而 `ssat[].azel` 在**全仓库范围内从未被赋值**（`Ssat` 构造只分配 `new double[2]`，`zdres`/`ddres` 都不写它）。故 `dopNs` 恒为 0、`rtk.sol.pdop` **恒为 0**。仰角必须取自 `relpos` 的工作缓冲区 `wb.azel`（[L194](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L194)），按 `iu[i]` 索引：

```java
double[] dopAzel = new double[Constants.MAXSAT * 2];
int dopNs = 0;
for (int i = 0; i < ns; i++) {
    dopAzel[dopNs * 2]     = azel[iu[i] * 2];
    dopAzel[dopNs * 2 + 1] = azel[iu[i] * 2 + 1];
    dopNs++;
}
double[] dop = new double[4];
RtklibCommon.dops(dopNs, dopAzel, opt.elmin, dop);   // 与 L596 同签名
double pdopLocal = dop[1];                            // dop = {gdop, pdop, hdop, vdop}
```

**不得回写 `rtk.sol.pdop`**：回写会改变 `sol` 输出与 `RESULT` trace 的 `pdop` 字段，破坏 I-1 的日志基线（§8）。

**`prr`/`pcr` 必须取自 `v[]` 而非 `ssat.resp/resc`**：`ddres` 入口把 `resp`/`resc` 清零（[L1572-L1573](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1572-L1573) 附近），且只在**非参考星**上写值（[L1718](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1718)/[L1720](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1720)），参考星恒为 0。用 `resp`/`resc` 会系统性低估 RMS（少掉全部参考星行），且 `resp`/`resc` 是**按频点**存（长度 `nf`），无法区分 code/phase 行。

**选 B 的理由**：

1. **覆盖 ratio 的盲区**（§2.1）：ratio 高但 `prr` / `pcr` 系统性偏大，正是错误固定的典型形态。方案 A 的 7 维里 3 个（ratio / ADOP / PDOP）都是**搜索空间或几何**的量，与现有门限高度共线，边际信息少。
2. **文献可比**：方案 B 有公开的、同特征集的实测结果可作对照基线（尽管不可作为本项目验收指标）；方案 A 的 7 维特征混用了两套不同来源的工作，两套之间不可混用，直接组合会引入来源不一致的风险。
3. **字段可直取**：`adr` / `prr` / `pcr` 全部来自固定分支已算出的量，零额外计算（§3.3 位置处 `v`、`xa`、`x` 均在作用域内）。

**保留 `ratio` 的用途**：只作对照基线，用于评估「ML 是否真的超过现有门限」，不作主特征。若消融实验显示去掉 `ratio` 后性能不变，说明 ML 学到了新信息；若性能大降，说明它只是在拟合 ratio，应重新审视。

### 5.2 接口二：冻结，特征列不定

接口二冻结（D1），不定义特征列。若将来重开，候选集与 §5.1 前 3 项重叠，届时重新评估是否合并。

### 5.3 接口三：逐卫星逐频点特征（决策 D3）

**决策：v1 用 MLP，单历元 + 短窗聚合；1D-CNN 暂不做。**

| # | 特征 | 来源（精确到行/字段） | 说明 |
|---|---|---|---|
| 1 | 高度角、方位角 | `wb.azel`，按 `iu[i]` 索引（工作缓冲区 [L194](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L194)；由 `zdres` 在 [L1222-L1223](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1222-L1223) 写入） | **不是** `ssat[].azel`——该字段恒为 0（§5.1） |
| 2 | 流动站 SNR、基准站 SNR | `ssat[sat-1].snrRover[f]`、`snrBase[f]` | 每历元由 `zdres` 写 |
| 3 | 上一历元伪距残差、载波残差 | `ssat[sat-1].resp[frq]`、`resc[frq]` | **按频点存（长度 `nf`），不区分 code/phase 行**；只在非参考星写（[L1718](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1718)/[L1720](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1720)）；参考星恒 0；`ddres` 入口清零（[L1572-L1573](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1572-L1573) 附近） |
| 4 | 锁相时长 | `ssat[sat-1].lock[f]` | 每历元由 `udstate` 更新 |
| 5 | 周跳标志、半周标志 | `ssat[sat-1].slip[f]`、`half[f]` | 位标志（`LLI_SLIP` / `LLI_HALFC`），不是布尔 |
| 6 | 几何无关组合值 | `ssat[sat-1].gf[frq]` | **仅当 `opt.thresslip != 0` 时由 `detslpGf` 写**，否则恒 0；存的是当历元组合值，不是变化率 |
| 7 | 系统标识、卫星可用、模糊度状态 | `SatUtils.satsys(sat, null)`、`ssat[sat-1].vsat[f]`、`ssat[sat-1].fix[f]` | `fix`：0 未固定 / 1 浮点 / 2 固定 / 3 已锚定 |

**`mw` / `phw` 已从特征集中删除**：这两个字段**只由 PPP 路径写入**（`PppCore` / `PppRtkCore`），RTK 的 `relpos` 链路从不写它们，恒为 0。把它们当特征就是喂两列常量，白占模型容量并让特征重要性分析失真。

**为什么 v1 不用 1D-CNN**：`resp` / `resc` / `gf` 已经以标量形式携带了跨历元上下文（它们本身就是上一历元或跨频点构造量）。用 13 维单历元特征 + 4 维短窗聚合（近 5 历元均值 / 标准差 / 历史长度）即可表达，MLP 足够。1D-CNN 需要真正的残差序列输入，属 v2 议题；且纯 Java 手写前向传播的备选方案在 CNN 下成本显著上升，故 v1 不采用。

**输入向量定死为 17 维（每行 = 一个 `(sat i, frq)`）**：

| 序号 | 维度 | 取值 | 缺失/退化处理 |
|---|---|---|---|
| 0 | `el` | `SatFeature.el`（弧度转度） | 不缺失（`zdres` 已按 `elmin` 过滤） |
| 1 | `az` | `SatFeature.az`（度） | 不缺失 |
| 2 | `snrRover` | `snrRover[frq]` | 无观测填 `0`（`snr` 正常范围 0–60 dBHz，`0` 可区分） |
| 3 | `snrBase` | `snrBase[frq]` | 同上 |
| 4 | `resp` | `resp[frq]`（上一历元） | 参考星或首历元填 `0` |
| 5 | `resc` | `resc[frq]`（上一历元） | 同上 |
| 6 | `lock` | `lock[frq]`（秒） | `0` = 未锁定 |
| 7 | `slip` | `slip[frq] != 0 ? 1 : 0` | 位标志归一为 0/1 |
| 8 | `half` | `half[frq] != 0 ? 1 : 0` | 同上 |
| 9 | `gf` | `gf[frq]` | `opt.thresslip == 0` 时恒 0（**须在 `norm.json` 中标注该列可能恒零**） |
| 10 | `vsat` | `vsat[frq]` | 恒 1（进入本表的星已通过 `zdres` 过滤） |
| 11 | `fix` | `fix[frq]` | 0/1/2/3，按整数归一 |
| 12 | `sysId` | `Integer.numberOfTrailingZeros(sys)`，`sys` 是**位掩码**（[Constants.java#L156-L163](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/constants/Constants.java#L156-L163)：`SYS_GPS=0x01, SYS_SBS=0x02, SYS_GLO=0x04, SYS_GAL=0x08, SYS_QZS=0x10, SYS_CMP=0x20, SYS_IRN=0x40, SYS_LEO=0x80`），故 `sysId` 取值为 `GPS=0 SBS=1 GLO=2 GAL=3 QZS=4 CMP=5 IRN=6 LEO=7` | **不做 one-hot**：星座数会随版本增长，one-hot 会让输入维漂移；且**绝不能直接喂 `sys` 原始位掩码**（`0x01/0x04/0x08` 在数值上毫无序关系，MLP 会学出「GAL 比 GLO 大」这种伪关系） |
| 13 | `respWinMean` | 近 5 历元 `resp` 均值 | 历史不足 5 时按已有历元数取均值 |
| 14 | `respWinStd` | 近 5 历元 `resp` 标准差 | 不足 2 个样本填 `0` |
| 15 | `rescWinMean` | 近 5 历元 `resc` 均值 | 同 13 |
| 16 | `histLen` | 当前窗口内实际历元数 / 5，取值 `[0.2, 1.0]` | 让模型能区分「均值真的稳」与「只有 1 个样本」 |

**短窗状态住在 impl，不住 core**：core 的 `ObsCorrectionContext` 只给**当前历元快照**（§4.4.3），窗口由 `ObsCorrectorImpl` 自己按 `(sat, frq)` 维护一个长度 5 的环形缓冲。定死三条重置规则，否则窗口会跨周跳/跨数据缺口污染：

1. `epoch` 间隔 > `1.5 * dt`（数据缺口）→ 清空该星全部窗口
2. `slip[frq] != 0`（该频点周跳）→ 只清空该 `(sat, frq)` 的窗口
3. `ctx.sats()` 中不再出现的 `sat` → 从缓冲区移除（避免无界增长）

**`dt` 的唯一来源是 `ctx.dt()`，且必须兜底**：`dt` 取自 `relpos` 的 `TimeSystem.timediff(obs[0].time, obs[nu].time)`（[L221](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L221)），`ddres` 用的也是同一个值。impl **不得自行从历元时间戳推算 `dt`**（那会引入第二套时间基准，与 core 的判定不一致）。

`dt <= 0` 或非有限时 `1.5 * dt` 的判定失效（负值会让所有历元都被判为「缺口」，`0` 会让任何间隔都判为缺口），故定死：

```java
double dt = ctx.dt();
boolean dtUsable = Double.isFinite(dt) && dt > 0.0;
// dtUsable == false → 按「清空全部窗口」处理（含当前历元重建），
//                     该历元 histLen 按 1 个样本计（即 0.2）
```

**为什么 `dt` 不可用时清空而不是沿用上一次的 `dt`**：沿用一个不可信的值会让窗口跨越真实数据缺口却不自知，等于把缺口前后的残差混在一起做均值——这是静默的标签污染。清空的代价只是该历元退化为单样本（`histLen = 0.2`），模型对此已有显式输入维度（f16），代价可接受。

**窗口长度 5 是定值，不做自适应**：它同时决定了 impl 的常驻内存上界（`ns_max × nf × 5 × 2 double` ≈ 1 KB 量级），可忽略。

**归一化要求**：所有特征必须在**训练集上拟合**归一化参数并固化到模型旁（`norm.json`），推理侧原样加载。禁止推理侧按运行数据动态归一化——那会让同一模型在不同数据上行为漂移。

**批量约束**：模型必须支持**一次调用处理 `ns × nf` 行**（而非逐卫星调用）。这是 §3.3 / §4.3.4「每历元批量推理 + `varerr` 只读」的结构前提，也是 G-5（单历元 ≤ 2 ms）成立的条件。若模型导出后不支持动态 batch，须在 ONNX 导出时固定 `batch = ns_max × nf` 并对不足的行做 mask。

---

## 6. 训练管线与标签

### 6.1 目录布局

```
rtklib-core/src/main/java/org/rtklib/java/ai/spi/
├── AiProvider.java                       # 注入回调（§3.2）
├── AiHooks.java                          # 注入点（ThreadLocal，§3.2）
├── FixValidator.java                     # 接口一 SPI（§4.4.1）
├── FixQualityContext.java                # 接口一输入（§4.4.4）
├── ObsCorrector.java                     # 接口三 SPI（§4.4.2）
├── ObsCorrectionContext.java             # 接口三输入（§4.4.3）
├── SatFeature.java                       # 单星特征（§4.4.3）
└── CodeCorrection.java                   # 接口三输出（§4.4.2）

rtklib-ai/
├── src/main/java/org/rtklib/java/ai/
│   ├── OnnxAiProvider.java               # 实现 AiProvider（组装下面两个实现）
│   ├── inference/OnnxInference.java      # 统一推理适配层（含批量调用）
│   ├── feature/FixQualityFeatures.java   # 接口一特征提取（§5.1）
│   ├── feature/ObsCorrectionFeatures.java# 接口三特征提取（§5.3）
│   ├── FixValidatorImpl.java             # 实现 FixValidator
│   └── ObsCorrectorImpl.java             # 实现 ObsCorrector
├── training/                             # 训练管线（Python，与 Java 侧独立）
│   ├── common/dataset.py                 # 统一数据格式
│   ├── common/export_onnx.py             # 统一导出
│   ├── common/metrics.py                 # 固定率/错误固定率/RMS
│   ├── fix_validator/train.py
│   └── obs_corrector/train.py
└── models/                                # 示例 .onnx（仅演示，禁止直接部署）
```

**注意**：不建 `AiAdvisorImpl` 门面。接口一与接口三各自实现各自 SPI，由 `OnnxAiProvider` 组装后一次性注入（§3.2）。

### 6.2 数据格式

统一 Parquet（列式、可校验 schema）：

- 特征列：`f0..fN`，列名与 §5 编号一致（接口一 `N = 5`（6 维，§5.1）；接口三按 §5.3 编号）
- 标签列：`label`
- 元数据列：`epoch`、`sat`、`frq`、`station`、`day`、`solution_stat`

**行粒度**：

| 接口 | 一行代表 | `sat` / `frq` |
|---|---|---|
| 接口一 | 一个历元（固定解产出时） | 留空（`-1`） |
| 接口三 | 一个「历元 × 卫星 × 频点」 | 必填 |

**元数据不是可选项**：`station` 与 `day` 是 §7.3 防泄漏切分的依据，缺失则无法做可信评估。接口三的 `sat` 与 `frq` 用于与推理侧 `idx = i*nf + frq` 行序对账——采样与推理的行序约定必须一致，否则训练标签与推理特征错位。

### 6.3 标签定义

**接口一**：固定解位置与参考真值偏差 > 阈值 → `label = 1`（错误固定）。

- 阈值 **0.20 m，已统一（v1.7，D7）**，与 `assemble.py` 的 `DEFAULT_THRESHOLD_M` 同口径，详见 §8.1 口径统一表。0.05 m / 0.1 m 两个旧值均已废弃：0.05 m 低于一个 L1 波长，落在其中的误差是「解对但精度退化」而非「解错整周」；口径分叉会让「扩特征是否有效」与「阈值是否改变」两个变量纠缠。
- 后续标定只允许在 0.20 m 口径下**向上收紧**（理由与标定方法：静态站水平精度实测可达厘米级，0.20 m 对静态数据可能过宽，会在标定集上画「固定解偏差」的累积分布，取双峰之间的谷底或 99 分位，并记录最终取值与依据）。
- 真值来源：已知坐标的静态站（`DATA_GUIDE.md §0.7` 的私有 4 站数据集可满足）。

**接口三**：逐卫星伪距误差真值。**这是接口三训练的强制前置阶段，不是「使用者自行解决」的可选项**（v1.7 改口）。标签方案未定型且未通过交叉校验之前，接口三的训练与评估**不得启动**——否则模型学到的量与被施加的量不是同一个量，§4.3.4 的膨胀因子也无法校准。

首选方案（**短基线 DGPS 差分隔离**）：

1. **DGPS 差分隔离**：参考站与目标站同步观测，基线长度控制到多路径不相关的尺度内（建议 ≤ 1–2 km，须实测复核）。单差消除卫星钟差、星历与对流层公共项后，残差即「目标站单星伪距误差 + 参考站自身误差」；参考站自身误差用其长时间静态解的低通估计扣除，或用两个参考站交叉验证来界定其量级。
2. **标签必须是单星，不是双差残差**：§4.3.3 的施加公式逐「卫星-频点」扣 `aiBias[refIdx*nf+frq] - aiBias[j*nf+frq]`，双差残差里参考星误差已被消掉；拿双差残差当标签，模型学的是「双差量」而被扣的是「单星量」，改正后残差不会回到正常量级（且不报错，属静默错误）。
3. **与 §4.1 契约要点 2 对齐：标签不得进特征**。接口三特征只允许来自观测值本身（`SatFeature`，§5.3）；任何由参考站/真值计算的量只作标签，违反即 §4.1 所述典型泄漏。
4. **单站场景不成立**：无参考站即无标签，接口三在此场景下**不得训练**，也不得以「无标签自监督」替代——无标签就无法验证 §4.3.4 的 `varianceScale` 是否校准，而膨胀不足会直接把假固定率推高（与接口一目标反向）。

备用方案（**仅在首选不可行时启用，且必须在评估报告中标注标签来源**）：参考轨迹反算残差（需高精度参考轨迹）、惯导组合（需高精度惯导）。

**示例数据集只证明链路跑通，不承诺模型效果**，且须在文件头标注「仅演示」。

### 6.4 接口一的训练与标定：两个层次（v1.8，D16）

「按站标定」这四个字必须拆开：**标阈值**与**训模型**是两件事，样本量、部署形态、风险完全不同。上一版（D15）只写了「按站标定」，未指明是哪一层，本节定死。

#### 6.4.1 层次 A：模型共享 + 阈值分站

| 项 | 内容 |
|---|---|
| 定义 | **一个模型**，训练用全部站（排除被评估站）；**阈值按站给出**，每站用自己的数据标定 |
| 样本量 | 模型：其余站全部数据（本数据 ≈ 10 万行级）；阈值：单站固定解样本 3260~14216 行（§8.1） |
| 优势 | 满足 §7.4.1 的 G-3（**未见站**）语义；新站无需该站数据即可冷启动（模型已在，阈值可用默认或运行时自适应）；只维护一个 ONNX |
| 风险 | **模型本身仍是全局的**。若特征跨站不可迁移，分站阈值救不了——这正是 §8.2.5 的 LOMO 0/7 与 §8.2.10 的「差 1 站」所指 |

#### 6.4.2 层次 B：按站训练模型 + 按站标定阈值（完全按站）

| 项 | 内容 |
|---|---|
| 定义 | **每站一个模型、一个阈值**；站内按天切（前半天训练、后半天测试，§7.3 第二层） |
| 样本量 | 每站 4 天（§8.1），训练/测试各 2 天；固定解样本 3260~14216 行/站，其中错误固定 ≥ 100 才够 MDE（§7.4.1 第 2 条） |
| 优势 | 站址特有多路径、`snrRover` 硬件差异可被模型直接学到；探针显示站内信号确实存在（§8.2.10 的 2 pp 档 5/7、5 pp 档 7/7） |
| 风险 | ①**新站无模型**（冷启动不可用，必须先采该站数据）；②每站仅 2 天训练数据，过拟合风险高，且**无法用留站暴露**（没有第二个站可留，只能留天）；③16 个模型 × 版本管理；④**G-3 的「未见站」语义被取消**，只剩「站内按天泛化」 |

#### 6.4.3 决策：选层次 A，层次 B 仅作「已知站增强」的可选形态（D16）

实测对照（同一探针协议、同一 0.20 m 口径、同一 `pdop ≤ 8` 门禁，详见 §8.2.10）：

| 档 | 层次 A（共享模型） | 层次 B（站内模型） | 差异来源 |
|---|---|---|---|
| 1 pp / 30% | 0 / 7 | 0 / 7 | 两层次同为不可达（**结论更硬**：不是模型粒度问题） |
| 2 pp / 20% | **4 / 7** | **5 / 7** | 唯一影响达标结果的差异站 `S4`：A 为 −4%，B 为 55% |
| 5 pp / 15% | **6 / 7** | **7 / 7** | 同上：`S4` A 为 −8%，B 为 78% |

（逐站明细见 §8.2.10：除 `S4` 外，`848643` 反而是层次 A 更好（2 pp 档 47% vs 35%），其余 5 站基本持平。故「B 更强」并不普遍成立。）

**为什么仍选 A（三条理由）**：

1. **B 的增量收益恰好只落在 1 个站，且是站址特异的站**。`S4` 是全场多路径最强的站（其站内模型在 2 pp 档即达 55%，跨站模型为 −4%）。这证明「站址特异信号」存在，但也证明它**不可迁移**——它救不了任何别的站，只能为该站定制一个模型。
2. **B 取消 G-3 语义**。选 B 等于把判据从「未见站」降级为「站内按天」，这不是实现选择，而是判据降级，须走 §7.4 的判据修订，代价远大于 +1 站。§7.4.1 的自由度约束（未见站、单一全局阈值、配对下界、不挑站、不用测试标签反选）也不允许这么做。
3. **B 的部署不可行**。新站没有数据 → 没有模型 → 接口一在新站上等于不存在；而接口一的价值恰恰是「第一次到某站就能判假固定」。

**B 的重开条件（写死，避免反复）**：站点被确认为**固定基础设施**（同一站址长期运行、不新增站），且每站数据 ≥ 4 天可做站内按天留一，且评估报告**明确标注形态为 B**（不得与 A 混报）。三条同时成立才可把 B 作为「已知站增强」交付。

#### 6.4.4 阈值来源：全局单阈值与分站分位不得混用

层次 A 内部还有一层区分，报告里必须写清用的是哪一个：

| 阈值来源 | 怎么定 | 用途 | 能否作 G-3 证据 |
|---|---|---|---|
| **全局单阈值** | 在标定站（未参与训练、未参与早停）上联合标定，所有站共用一个数（`train.py` 的 `select_threshold_multi`） | **G-3 判定**、默认部署值 | **可以**（这才是「未见站」的严格口径） |
| **分站分位阈值** | 由该站**自身置信度分布的分位**给出，不含标签（预算 `k = floor(预算比例 × 该站 AR 历元)`，取第 k 小的分数） | 部署期自适应（每站可用自己的分数分布定阈值） | **不可以**：它用了被评估站的分数分布，属**转导式**（未见标签，但见了该站的分数分布） |

**§8.2.10 与 §7.4.4 的逐站数字均按「分站分位阈值」口径**，即转导式。写报告时须标注：「未见标签，但阈值取自该站自身分数分位」，不得表述为「未见站」。全局单阈值口径的逐站达标率尚未测（`train.py` 全链路只在 2 标定 + 2 测试的单次配置下跑过，见 §8.2.9 第 3 条）。

---

## 7. 评估协议

仅对比「固定率、错误固定率、RMS」不足以判断净收益——均值改善可能掩盖单站恶化，单次运行可能掩盖时序不稳定。故补足四条。

### 7.1 对照组必须齐全

同一数据、同一配置下跑四组：

| 组 | 配置 |
|---|---|
| A 原生 | `AiOpts` 全默认（v3.4.0 行为） |
| B 接口一 | 仅开接口一 |
| C 接口三 | 仅开接口三 |
| D 组合 | 接口一 + 接口三 |

缺 D 组就无法判断两者是否相互抵消（接口三升固定率、接口一降假固定，叠加后可能互相掩盖）。

### 7.2 三指标同看，不得只看一个

- 固定率（`SOLQ_FIX` 历元占比）
- **错误固定率**（固定解偏差 > 阈值占比，分母取固定历元数）
- 位置 RMS（水平 / 垂直）

接口一的正确表述是「用固定率换错误固定率」，因此**单看固定率下降不能判定失败**，单看错误固定率下降也不能判定成功。必须给**联合判据**（§7.4）。

### 7.3 两层切分：必须按站切分，禁止随机切分

GNSS 相邻历元高度相关（同卫星、同几何、同大气），随机切分训练/测试会造成严重泄漏，得到虚高的评估结果。

但**仅按天切分同样不够**。接口一、接口三的特征绝大多数是**站址相关**的：多路径是站址的物理属性，`snrRover` 是该站接收机/天线的硬件特性，`resp`/`resc` 与 `gf` 也带站址指纹。同一站不同天的数据进不同集合时，模型仍能学到「这个站的硬件指纹」而不是「这是错误固定」，于是站内交叉验证漂亮、换站即失效。

因此定死为**两层切分**：

| 层 | 规则 | 作用 |
|---|---|---|
| 第一层（必须） | **不同 `station` 进不同集合**：训练站、验证站、测试站互不重叠 | 保证评估测的是跨站泛化，而非站内记忆 |
| 第二层（站内） | 同站内**再按 `day` 切分**（训练日 / 验证日） | 早停与超参选择不得跨站泄漏 |

强制要求：

- **禁止随机切分**（`sklearn` 默认的 `train_test_split` 直接不可用）
- 训练集 / 验证集 / 测试集三者**互不重叠**，且**不同站**
- 测试集至少包含 **2 个未参与训练、也未参与早停的站**（§7.4 G-3 判据）
- 元数据列 `station`、`day` 缺失的数据**不得进入评估**（§6.2）

### 7.4 净收益判据（拟定阈值，实施时复核）

本节给**两套互相独立的判据**：§7.4.1 属接口一，§7.4.2 属接口三。二者不共享判据编号，也**不存在「接口三须先过接口一」的依赖**（§8 阶段表已改为并列）。若两者同时启用，另按 §7.1 跑 D 组验证不相互抵消。

#### 7.4.1 接口一判据（G-1~G-5）

接口一在 core 侧**内置注入**（即无需使用方显式注入也生效）须**同时**满足：

| # | 判据 | 拟定阈值 |
|---|---|---|
| G-1 | 错误固定率相对下降 | ≥ 30%（**取 95% 置信下界**，且须过样本量前置，见下文） |
| G-2 | 固定率绝对下降 | ≤ 1.0 个百分点 |
| G-3 | 跨站泛化 | ≥ 2 个**未参与训练、也未参与早停**的站均满足 G-1、G-2（§7.3） |
| G-4 | 最坏单站不恶化 | 任一站的错误固定率不上升 |
| G-5 | 推理开销 | 单历元 ≤ 2 ms（与文献量级一致；接口三按**每历元一次批量调用**计，非逐卫星累加） |

**G-4 是最重要的一条**：均值改善但某站恶化，说明模型在学场景特征而非错误模式，属不可部署。宁可整体收益小，不可局部恶化。

**G-1 必须带统计显著性，否则「相对下降 30%」可能是噪声**：错误固定本身是低频事件（典型场景下 A 组错误固定率常在 1%–5%），分母小，`30%` 相对下降在小样本下极易被随机波动跨过。定死三条：

1. **G-1 必须报告 95% 置信区间**。错误固定率是比例量，用 **Wilson score interval**（不要用正态近似——小比例、小样本下正态近似会给出负下界）。判据改为「**相对下降的置信下界 ≥ 30%**」，而非点估计 ≥ 30%。
2. **样本量前置条件（MDE）**：评估前先算最小可检测效应（MDE）。若 A 组错误固定事件数不足以在 95% 置信、80% 功效下检测出 30% 相对下降，则该测试集的 G-1 **不予判定**（既不算通过也不算失败），必须扩数据。给出可用的经验门限：A 组错误固定事件数 **< 100** 时不做 G-1 判定。
3. **A/B 必须是配对比较**。同一历元分别以「注入」与「未注入」跑两次（§8 的零回归基线天然提供未注入侧），按历元配对统计（McNemar 检验适用于「固定正确/错误」的二分类翻转），**不得跨数据集比较两个独立集合的比例**——那会把站点/日期差异混入判据。

**G-2 的「1.0 个百分点」同样要报置信区间**，但它衡量的是「不恶化」，方向保守，故用点估计 + 单侧上界即可，不要求 MDE 前置。

**v1.7 补充（结论，不是放宽）：G-1 ≥ 30% 与 G-2 ≤ 1.0 pp 这一「成对组合」在当前特征集下不可达。** 上表三个数字**保留不动**，本节只追加一条否定结论，可交付口径另立 §7.4.4（D13）。

不可达的原因是**物理层面的**，不是模型容量或阈值选点问题（§8.2.5 已扫遍全部可行阈值、§8.2.6 已排除五项实现/口径原因）：

1. **预算与降幅在同一份数据上数学互斥**（§8.2.2 可行性推导）：G-2 的 1 pp 只允许剔除「置信度最低的约 1% 历元」，而 G-1 的 30% 相对下降要求这 1% 里装着错误固定总数的 30%——这等价于要求「错误固定密度在最低 1% 档是整体的 30 倍」。实测该档只占 0%~25%。
2. **分数尾部不单调**（§8.2.8）：7 个可判定站中，错误率随分数降低而单调递增的站数为 **0/7**。最低分档并非「最可能错」，而是「几何最差但解对了」——`pdop` 中位数显著高于整体，错误固定率却是 0%。故紧预算会把预算花在无风险样本上。

因此「1 pp 预算内取到 30% 相对下降」在本数据、本特征集下**不存在可行阈值**，属不可达而非未调优。

#### 7.4.2 接口三判据映射（G-1'~G-5'）

**为什么不能直接套 G-1/G-2**：G-1 是「错误固定率相对下降」、G-2 是「固定率绝对下降」，两者都是接口一「**只否决、不创造**」（§4.1 契约要点 1）的直接后果。接口三方向相反：它**改观测值、不剔除**（§4.3.1），目的是让残差回到正常量级、改善定位。因此主指标必须是位置 RMS，「假固定率」从主指标降级为**约束**——不上升即可，不要求下降。

| # | 判据 | 类型 | 拟定阈值 |
|---|---|---|---|
| G-1' | 水平位置 RMS 相对改善 | 主指标 | ≥ 10%（**取 95% 置信下界**，配对 bootstrap） |
| G-1b' | 垂直位置 RMS 不恶化 | 主指标 | 相对变化 ≤ +2%（单侧上界） |
| G-2' | 假固定率不上升 | **约束** | 错误固定率相对变化 ≤ 0（点估计与单侧上界均 ≤ 0） |
| G-3' | 跨站泛化 | 主指标 | ≥ 2 个**未参与训练、也未参与早停**的站均满足 G-1'、G-2'（§7.3） |
| G-4' | 最坏单站不恶化 | 约束 | 任一站的**水平 RMS** 不上升 |
| G-5' | 推理开销 | 约束 | 单历元 ≤ 2 ms（**按每历元一次批量调用计**，非逐卫星累加） |

**G-2' 有结构性依据，但仍须实测**：§4.3.4 把 `σ_m²` 计入 `R`，且 `varianceScale` 被 core 钳位到 ≥ 1.0（**单侧膨胀**），结构上不会让滤波器更自信。但「结构上不会」不等于「实测不上升」——若模型只给偏差、不给足方差（`varianceScale` 低估），ratio 照样虚高。故 G-2' 必须实测，不得以契约推导替代测量。

**辅助指标必须报告：`ns` / `vsat` 的变化**。§4.3.4 已明确偏差改正会改变 `maxinno` 剔除行为（改正后残差变小 → 被剔除的观测变少 → `vsat`/`rejc` 随之变化）。这属期望行为，但它意味着接口三**间接影响卫星可用性**：只看固定率会漏掉「靠多收卫星换来的改善」，也可能掩盖「靠多剔除卫星掩盖的恶化」。报告须给 A / C / D 三组的 `ns` 中位数与 `vsat` 分布对比。

**统计口径沿用 §7.4.1，不新造**：

- **配对 bootstrap**：同一历元分别以「注入」与「未注入」跑两次（§7.1 的 A 组与 C 组天然提供配对两侧），按历元配对重采样；**不得跨数据集比较两个独立集合**——那会把站点/日期差异混入判据。
- **MDE 前置，但门限换成连续量口径**：§7.4.1 的「错误固定事件数 < 100 不判定」是离散事件门限，接口三的效应量是连续量（RMS），直接套会误伤。改为「**配对 RMS 差异的 95% CI 半宽 < 目标效应（10%）的 1/2** 才判定」，否则该测试集的 G-1' **不予判定**（既不算通过也不算失败），须扩数据。
- **两层切分不变**：§7.3 的按站切分 + 站内按天切分照用。接口三特征同样带站址指纹（多路径是站址物理属性、`snrRover` 是该站硬件特性），随机切分照样泄漏。

#### 7.4.3 接口一剔除规则：几何退化前置门禁（v1.7，D14）

接口一的运行时语义是「只否决、不创造」（§4.1 契约要点 1），全部收益来自「剔掉哪些历元」。规则定死为**两段式**，且**阈值联合标定、单站评估、全局评估、core 运行时四处必须逐字对齐**——否则会出现「标定时放过、评估时剔除」的口径漂移，指标不可复现：

1. **前置门禁**：`pdop > 8.0` 的历元**不参与剔除**，一律按「接受」处理。core 侧在 `RtkCore.aiAcceptFix()` 的阈值比较**之前**返回 `true`；训练侧在 `_accepted()` 里以 `conf >= threshold OR pdop > PDOP_ABSTAIN_MAX` 表达同一语义。
2. **预算内剔除**：剩余历元按 `confidence` 升序剔除最低分，剔除总量 `≤` 预算（`floor(AR 历元 × 预算比例)`）。

**为什么需要门禁**（依据见 §8.2.8）：分数最低的 0–1% 档不是「最可能错」，而是「几何最差」——该档 `pdop` 中位数显著高于整体，错误固定率却是 0%。原因是高 DOP 历元即使解错也很难通过 `ratio` 与方差检验，于是「固定且被固定在错解上」的样本在这一档**结构性缺席**。紧预算（1–2 pp）下不加门禁，预算会全部花在「高 DOP 但正确」的样本上：既无收益，又挤掉了真正该剔的历元。实测 2 pp / 20% 档从 3/7 提升到 5/7。

**常数 8.0 的含义**：本数据 `pdop` 上尾 95 分位在 6~7 之间，8.0 只切掉个位数百分比的最差几何历元，因此**不改变 G-2 的预算语义**（被门禁放行的历元本就不该被剔除）。该常数是**单点定义**：core 侧与训练侧 `PDOP_ABSTAIN_MAX` 必须同值（当前 8.0），改任一处即视为改判据，须走新决策记录（D14）。

#### 7.4.4 可交付口径（v1.7，D13）

「预算」与「降幅」是**成对**指标，单看任一侧都会误导：只看 30% 降幅会逼出「几乎不剔除」的阈值（预算趋 0 时相对下降无定义，§8.2.5 的排序键退化即此病）；只看 1 pp 预算会逼出「全剔」的阈值（固定率掉光但 G-1 归零）。故可交付口径改为**按站统计的成对档位**，任一档成立即视为接口一在「当前特征集 + 当前数据」下达到可交付水平：

| 档 | 预算（G-2） | 相对下降下界（G-1） | G-3 原判据 | 加强口径 | 实测：层次 A（交付形态） | 实测：层次 B（站内上界） |
|---|---|---|---|---|---|---|
| A | ≤ 1.0 pp | ≥ 30% | ≥ 2 / 7 站 | ≥ 5 / 7 站 | **0 / 7 —— 不可达（标记，不删除）** | 0 / 7 —— 不可达 |
| B | ≤ 2.0 pp | ≥ 20% | ≥ 2 / 7 站 | ≥ 5 / 7 站 | **4 / 7 —— 满足 G-3，未达加强口径（差 1 站）** | 5 / 7 —— 达标 |
| C | ≤ 5.0 pp | ≥ 15% | ≥ 2 / 7 站 | ≥ 7 / 7 站 | **6 / 7 —— 满足 G-3，未达加强口径（差 1 站）** | 7 / 7 —— 达标 |

**「加强口径」与「G-3 原判据」必须分开读**：G-3 是 §7.4.1 定死的「≥ 2 个未见站」，A 档外的 B/C 两档在交付形态（层次 A）下都满足；「≥ 5/7」「≥ 7/7」是 v1.7 为压低方差额外加的稳健性要求，**只在层次 B（站内模型）下成立**，而层次 B 取消「未见站」语义（§6.4.3），故**加强口径不作交付证据**。上一版把 5/7、7/7 直接写成「达标」而未标注形态，属口径缺失，v1.8 已更正（§8.2.10）。

**口径边界（四条，防止误读）**：

1. **逐站统计，不是单次配置通过**。分母是 7 个可做 G-1/G-3 判定的站，每站轮流充当「未参与训练、也未参与早停」的站（§7.3）。单次 LOMO「2 标定 + 2 测试」配置下 G-3 的「≥ 2 站」等价于 2/2，方差极大（实测 B 档 1/2、C 档 1/2），**不能用作可交付判据**；它只作「链路能产出达标站」的存在性证据。
2. **档位只在 0.20 m 假固定阈值口径下成立**（D7）。换口径须重测，不得跨口径引用本节数字。
3. **A 档标记为不可达，而非删除**。它仍是「换特征集或扩数据后须重新挑战」的目标（§7.4.1 的 v1.7 补充），不是被放弃的判据。
4. **必须标注形态与阈值来源**（§6.4.4）。「层次 A / 层次 B」与「全局单阈值 / 分站分位阈值」是两组独立标注：本节逐站数字均为**层次 A + 分站分位阈值**（转导式，未见标签但见了该站分数分布），不得写成「未见站 + 全局阈值」。混标即视为口径造假。

**与「不放宽判据」的关系**：G-1 的 30%、G-2 的 1.0 pp、G-3 的「≥ 2 未见站」三个**数字一字未改**；改的是「预算-降幅」成对组合的可交付档，因为原组合与数据的错误固定密度**物理冲突**（§8.2.2 数学互斥 + §8.2.8 尾部形态 0/7）。判据的自由度（未见站、单一全局阈值、配对 bootstrap 下界、不挑站、不用测试标签反选阈值）**一条未增**。故这是「承认原判据与物理不符」，不是「为凑指标放宽」。

### 7.5 接口三第一阶段验收（v1.8，D17）

**为什么接口三不能直接套 G-1'~G-5'**：接口一的语义是「只否决、不创造」，故主指标必然是「用固定率换错误固定率」，且失败即不部署；接口三的语义是**改观测值、不剔除**（§4.3.1），它的价值要经过「残差变好 → 定位变好 → 固定变好」三级传导，任何一级都可能被其他误差项吸收。因此接口三的验收**分两阶段**：

| 阶段 | 目标 | 通过后能做什么 | 判据 |
|---|---|---|---|
| **第一阶段** | 先证明**能用、无害、有作用** | 允许**显式注入**（默认仍关，无 `AiProvider` 即不生效） | 本节 A1~A3 |
| 第二阶段 | 证明**有净收益** | 允许**默认开启**（须走 D8 流程） | §7.4.2 的 G-1'~G-5' |

**本节不修改 §7.4**：G-1'~G-5' 一字不动，只是把「默认开启」的门槛与「允许显式注入」的门槛分开。三条判据的共性是**都不要求「改善」**——只要求「链路没坏、指标没烂、确实在起作用」。

#### 7.5.1 A1：功能流程正常（契约检查，不看效果）

**这是「测试通过」，不是「效果通过」，不涉及 RMS。**

| # | 检查项 | 怎么判 | 对应条款 |
|---|---|---|---|
| A1-1 | C 组（仅开接口三）能跑完 | 整段数据不抛异常；除预期内情形外**不产生 `AI_DEGRADE`** | §3.4、§4.3.4 |
| A1-2 | 成对输出 fail-fast | 构造「只给 `biasMeters`、不给 `varianceScale`」的假 impl，确认 core 按降级处理（bias=0、scale=1）并发出 `AI_DEGRADE` | §4.3.6 |
| A1-3 | 幂等性 | 同一历元对同一份 `ddres` 施加 2 次与 3 次，`v[nv]` 扣偏差的结果**逐位一致** | §4.3.6 |
| A1-4 | 行序对齐 | `aiBias` 的索引 `idx = i*nf + frq` 与 `ctx.sats()` 顺序一致；抽样对账特征与标签的 `sat`/`frq` | §4.3.3、§6.2 |
| A1-5 | IFLC 门禁 | `ionoopt == IONOOPT_IFLC` 时**不调用** `correct()`，行为等价于未注入 | §4.3.7 |
| A1-6 | 钳位与降级 | `varianceScale < 1.0` 按 1.0；`\|biasMeters\| > obsCorrectorMaxBias` 置 0（不钳到边界）；`NaN`/`Inf` 按降级 | §4.3.4 |

#### 7.5.2 A2：没有明显变差（C 组 vs A 组，同历元配对）

**这一条的核心是配对比较**——同一历元分别跑 A 组（原生）与 C 组（仅开接口三），不是跨数据集比两个比例。因为「不恶化」是单侧保守判据，**不需要 MDE 前置**（与 §7.4.1 的 G-1 不同）。

| # | 指标 | 怎么判 | 阈值 |
|---|---|---|---|
| A2-1 | 固定率 | C 组 − A 组，同历元配对 | 绝对下降 **≤ 1.0 pp**（沿用 G-2 的保守口径） |
| A2-2 | 错误固定率 | C 组 vs A 组 | **不上升**（单侧：任何上升即不通过，不给容差） |
| A2-3 | 位置 RMS | 水平 / 垂直分别比 | 不恶化：相对恶化 ≤ 5%，或绝对增量 ≤ 1 cm（取满足其一） |
| A2-4 | `ns` / `vsat` | C 组 vs A 组 | 不显著减少（§4.3.4 的方差膨胀会改变 `maxinno`/`varsat` 的剔除行为，必须对比） |

**A2-2 与 A2-1 的写法不对称，是刻意的**：固定率允许 1 pp 的机械损耗（方差膨胀必然带来一点保守），但错误固定率**一个点都不许涨**——它是接口三唯一可能造成的实质伤害。

#### 7.5.3 A3：有出现辅助了固定（证明「真的在起作用」）

「辅助」有三种量法，从弱到强，**取「中」或「强」，不接受「弱」**：

| 量法 | 含义 | 是否算通过 |
|---|---|---|
| 弱：`biasMeters` 非零的卫星占比 | 模型确实在改观测值 | **不算**：只证明有输出，不证明改对了 |
| 中：改正后 `ddres` 回到「解析误差模型认为的正常量级」，或降幅显著超过噪声 | 改正方向对 | **算**（首选口径） |
| 强：改正后固定解偏差变小 / 固定率上升 | 真的辅助了固定 | **算**（有更强证据时优先报这个） |

**「中」的判定细则（v1.8 修正，D17；双门限 + σ，取代「相对 30% 单门限」）**：

**第一步：定 σ。** 判定用的门限基准是**该双差行的解析标准差** `σ_dd`，不是拍一个 cm 数：

```
σ_dd = sqrt(Ri[nv] + Rj[nv])        // 单位：米；Ri/Rj 为 varerr 返回值（方差，m²）
```

- 取 [RtkCore.java:2082-2083](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L2082-L2083) 的两行 `varerr` 结果**求和再开方**。`ddres` 是双差残差，其正常量级就是 `sqrt(Ri+Rj)`，不是单星 `σ_obs`（单星 σ 会系统性低估双差残差的正常量级）。
- **必须取 `aiScale = 1` 的原生值**，即**方差膨胀之前**的 `Ri/Rj`（[RtkCore.java:2091-2093](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L2091-L2093) 的乘法之前）。若用膨胀后的方差，模型只要把 `varianceScale` 调大就能**自己给自己放宽门限**，A3 立刻失去意义（自我放宽，§4.3.4 的单侧膨胀被反向利用）。
- 用 `σ_dd` 而不用固定 cm 门限的理由：`varerr` 已按仰角 / SNR / 基线长 / 钟漂给出方差（§4.3.4 的 `σ_obs² = varerr(...)`），`σ_dd` 随这些量自适应——大仰角卫星的门限自动收紧、低仰角自动放宽。固定 cm 门限做不到这一点（在大仰角上过松、低仰角上过严）。

**第二步：前置守卫（只统计「改正前明显异常」的样本）。**

```
G0: |ddres_before| >= 3 * σ_dd
```

不满足 G0 的行**一律不计入分母、也不计入有效改正**。这一条正是原「低成本折中方案」的下限保护，在此并入主口径：它挡住「2 cm → 1.4 cm」这类在小残差上按比例算出来的虚高计数（`2 cm` 相对 `3σ ≈ 6 cm` 属正常量级，本就不该算改正）。

**第三步：双门限，满足其一即计 1 次有效改正。**

| 分支 | 条件 | 抓的是什么 |
|---|---|---|
| R%（相对） | `\|after\| ≤ 0.85 × \|before\|`（即相对下降 ≥ 15%） | 大比例改善，即使改正后仍偏离 |
| Dσ（降幅显著） | `\|before\| − \|after\| ≥ 1.0 × σ_dd` | 绝对降幅超过噪声 |

**为什么由 v1.8 的 R(0.7) / D(3σ) 放宽为 R(0.85) / D(1σ)（v1.9 修正，2026-11-11，D18）**：原判据与**输出契约的钳位上限**互相矛盾，在数学上不可达——

1. `R(0.7)` 要求 `|bias| ≥ 0.3|b|`；`G0` 要求 `|b| ≥ 3σ_dd`；联立即要求 `|bias| ≥ 0.9σ_dd`；
2. `D(3σ)` 单独就要求 `|bias| ≥ 3σ_dd`；
3. 而输出契约恒有 `|bias| ≤ bias_max`（默认 2.0 m，§4.3.4）。实测 `σ_dd` 中位数 2.773 m ⇒ `0.9σ_dd = 2.495 m > bias_max`，故 `R ∧ D` 的可行集为空。

实测证据：接口三首轮（6 站 × 4 日 × 24 小时，测试站 192 run / 359724 行）中 `G0` 命中 20563 行，但**结构上可达的行数 `reduction_reachable_rows` = 0**，`R` 与 `D` 命中均为 0——此时 A3 报告「样本不足」是**判据缺陷**，不是模型无效。放宽后同一模型、同一数据：`G0` 20563 行、`R%` 9743 行、`Dσ` 0 行、有效改正 9743 行 / 33 星，**A3 通过**（§8.3）。

两条仍保留「独立分支」而非写成 `max(...)`：`max(0.5b, kσ)` 在 `σ ≪ b` 时退化为比例门限，**反而更严**，抓不到「降了 0.5 m 但比例只有 25%」这类真实改善。

**`Dσ` 分支在本批数据上仍不生效（命中 0 行）**：`1.0σ_dd` 的中位数 2.773 m 已超过 `bias_max = 2.0 m`，即绝对降幅 1σ 的目标本身就超出钳位能力。故本批 A3 的通过**完全由 `R%` 分支贡献**。若要让 `Dσ` 也成为有效判据，须把 `bias_max` 抬到 `≥ σ_dd` 量级（本批约 2.8 m，建议留余量取 3.0 m），这属于改输出契约，须走新决策记录；`R%` 分支不受此限，因为其可达上界是 `bias_max/0.15 = 13.3 m`。

**「回到正常量级」（`|after| ≤ k·σ_dd`，k = 1 或 2）不必单列**：在 G0（`|before| ≥ 3σ`）成立的前提下，`k = 2` 蕴含 `|after| ≤ 2σ ≤ (2/3)|before| ≤ 0.85|before|`，`k = 1` 蕴含更严，**已被 R% 完全覆盖**。所以 `k` 在本口径下不产生新的判定自由度，只作为「R% 分支的物理解释」保留（说明 R% 的门限比「回到 2σ 内」略宽，偏保守取宽以免在 σ 估计偏大时漏判）。

**第四步：非噪声门槛与计数单位。**

1. **计数单位**：一个 `(sat i, frq)` 在一个历元的 **code 行**（`biasMeters` 只施加于 code 行，§4.3.3）；carrier 行不参与 A3。
2. **配对**：`before` 取 A 组（未注入）该行的 `ddres`，`after` 取 C 组（仅开接口三）**同一历元、同一 `(i, frq)`** 的 `ddres`。禁止跨历元、跨星配对。
3. **有效改正次数 ≥ 10 且覆盖 ≥ 3 颗卫星**。低于此数按「样本不足，A3 不予判定」（与 §7.4.1 的 MDE 精神一致，不算通过也不算失败）。
4. **零基线必须恰为 0**：用同一 impl 但 `biasMeters` 全置 0 跑一遍，该跑的有效改正次数**必须为 0**（此时 `after == before`，R 与 D 均不成立）。这不是「显著性检验」，而是**判定链自检**：若非 0，说明配对索引错位或 `before/after` 取错了行（对应 A1-4 的行序对齐）。
   > v1.8 更正：上一版要求「显著高于零基线（≥ 3σ 二项检验）」。在新口径下零基线**恒为 0**，二项检验退化无意义；**统计显著性已内建在门限本身**（G0 的 3σ 与 D 分支的 3σ），不再需要额外检验。

**数据来源（A3 可测量的前提）**：当前 `AI3/CORRECT` trace 只输出 `ns` / `nf` / `dt`（[RtkCore.java:1820-1821](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1820-L1821)），**不含逐行 `ddres` 与 `Ri`/`Rj`**，故 A3 现在无法直接测。须新增一条**仅评估期开启**的逐行 trace（或离线评估开关），输出 `(epoch, i, frq, ddres_before, ddres_after, Ri, Rj)` 六元组；约束两条：①该输出**不得进入生产热路径**（§3.3 的每历元开销约束）；②`biasMeters` 全置 0 的零基线跑必须用**同一开关、同一格式**，否则上面第 4 条的「零基线恒为 0」无法自检。

**第五步：不要求固定率提升。** 只要满足上述四条，A3 即通过——这正是「有出现辅助了固定就行了」的可判定形式。若同时观察到「改正后固定解偏差下降」的历元（强口径），优先报强口径，但**不以强口径为通过前提**。

**为什么 A3 不要求「改善定位」**：接口三的收益链条长（残差 → 定位 → 固定），在本数据（1.1 km × 1.0 km 山谷、静态站、16 站近似同址，§8.2.9）上，定位改善很容易被大气与多路径的公共项吸收。要求第一阶段就出现定位改善，等于把第二阶段的门槛提前，会让接口三永远无法启用。

**为什么不用「与 G-1 的 30% 对齐」来论证 0.85 这个数**：G-1 的 30% 是**站级统计量**（错误固定率的相对下降），A3 的 0.85 是**单条观测**的相对下降，两者量纲不同，不构成互相支持。0.85（降 15%）在此只作「大比例改善」的粗筛，真正的物理基准是 `σ_dd`（见 Dσ 分支）。

#### 7.5.4 与 §7.4 的关系（三句话）

1. **不替代，是分阶段**：A1~A3 通过 → 允许显式注入；G-1'~G-5' 通过 → 才允许默认开启。
2. **不改 §7.4**：G-1'~G-5' 的阈值、口径、自由度约束全部不变，本节只新增一道**更早、更低**的门。
3. **两道门不可互相顶替**：A2 通过不蕴含 G-1'~G-5' 通过（「没变差」不等于「有净收益」）；反之，第二阶段若跳过 A1（链路都没验），其数字也不可信。

---

## 8. 实施阶段与实现状态

**本章是实现状态的权威表。** 与 §1 摘要冲突时以本章为准。

| 阶段 | 内容 | 依赖 | 状态 |
|---|---|---|---|
| 0 | 本文档定稿 | — | 已完成（2026-11-06） |
| 1 | core SPI（`AiProvider` / `AiHooks` / `FixValidator` / `ObsCorrector` + 上下文记录）+ `AiOpts` + **I-1 零回归验证** | 阶段 0 | 已完成（2026-11-06） |
| 2 | `rtklib-ai` 模块 + `OnnxInference` + `OnnxAiProvider` + 接口一接线（未注入 = 关） | 阶段 1 | 已完成（2026-11-07） |
| 3 | 接口一训练数据导出（§5.1 六维特征提取已在阶段 2 落地，本阶段补标签与样本落盘） | 阶段 2 | 已完成（2026-11-10）：整日线 12 run / 6 站 / 2 天 / 16152 行；小时线 16 站 / 4 天 / 113962 行，见 §8.1 |
| 4 | 接口一训练 + 评估（§7 全套协议） | 阶段 3 | **训练链路已完成并跑通；原判据组合（1 pp / 30%）标记为当前特征集下不可达，可交付口径改为成对档（§7.4.4 / D13）**（2026-11-10，见 §8.2）：G-1 口径已按 §7.4 第 3 条修正为配对 bootstrap，pdop 前置门禁已落地（D14），留一站 1/7、留多站 0/7，逐站口径 B 档 5/7、C 档 7/7 |
| 5 | 接口三（含不确定性传播 §4.3.4） | **无（与阶段 4 并列）**：生效前提是自身通过 §7.4.2 的映射判据 G-1'~G-5'，**不依赖接口一** | **代码已完成**（2026-11-09，commit `27490d4`）；**模型已训练（方案 A，D 组口径），A1/A2/A3 第一阶段全部通过**（§8.3），允许显式注入、**默认不注入**；方案 B（端到端坐标 Loss）对照已执行并判定**不交付**，仅作实验 provider（§8.4 / D19） |
| — | 接口二 | — | **冻结**（D1） |
| — | `rtklib-joint` 接入 | — | **不接入**（D11 / §1.5.1） |
| — | `rtklib-adjust` 接入 | — | **不接入**（D12 / §1.5.2） |

**阶段 4 与阶段 5 是并行接口，不是串行依赖**（v1.7）。技术上两者共用 `AiProvider` 的两个**独立方法**，均可独立返回 `null`（§3.2 明文），`AiOpts` 参数也各自独立（`fixValidatorMinConfidence` vs `varianceScaleMax` / `obsCorrectorMaxBias`），因此可独立训练、独立评估、独立上线。阶段 5 的依赖栏写「无」是刻意的：把接口三挂在接口一的判据上，会让「接口一未过闸门」错误地冻结一个与它机制互补、方向不重叠的接口。

**但并列不等于互不影响，D 组验证不可省**：接口三升固定率、接口一降假固定，叠加后可能互相掩盖（§7.1）。两者**同时启用**时，必须在同一数据同一配置下跑 D 组，并按 §7.2 三指标同看确认不相互抵消；D 组未通过时，只能二选一启用，不得两者齐开。

**阶段 1 是唯一阻塞项**，且验收标准只有一条：I-1 逐位一致。在 I-1 未验证前不得进入阶段 2——否则后续所有「零回归」声明都失去依据。

**阶段 1 工作量估计**：0.5–1 天（SPI 四个接口类型（`AiProvider`/`AiHooks`/`FixValidator`/`ObsCorrector`）+ 四个记录类型（`FixQualityContext`/`ObsCorrectionContext`/`SatFeature`/`CodeCorrection`）+ 一个配置类 `AiOpts` + 零回归对比测试）。无第三方依赖，无环境风险。

**阶段 1 的验收还须包含两条结构性检查**：

1. 零回归测试须在 `AiHooks.clear()` 之后运行，且 teardown 断言注入点为空——否则 I-1 可能被前一用例的残留注入掩盖（§9）。
2. `rtklib-core/pom.xml` 的依赖树中**不得出现 `rtklib-ai`**（`mvn dependency:tree` 断言），保证依赖方向单向（§3.2）。

**阶段 2 的关键风险**：ONNX Runtime 在 Java 17 下加载失败。应对：阶段 2 首日先做「`6→16→1` dummy MLP 导出 + 加载 + 推理」的最小验证（输入维与 §5.1 的 6 维一致）；失败则切备选方案——纯 Java 手写 MLP 前向传播（权重从 JSON 加载，代码量 <100 行，接口一与接口三均适用，因 §5.3 已定为 MLP）。此决策须在阶段 2 内闭环并记录，不得悬空。

**阶段 2 风险决策（已闭环，2026-11-07）**：

> **结论：ONNX Runtime 1.17.3 在 Java 17 下加载与推理正常，不启用纯 Java MLP 备选方案。**
> 依据：`6→16→1` dummy MLP 导出后由 Java 侧加载，三组样本输出与 Python（numpy 双精度）前向参考值的偏差 < `1e-6`（两条路径算子实现不同，故以容差而非逐位相等为契约）。备选方案不实施，仅在 ONNX Runtime 未来出现兼容性问题时重新评估。

**阶段 2 的验收证据（2026-11-07）**：

| 检查项 | 结果 |
|---|---|
| `rtklib-ai` 模块内自洽测试（ONNX 加载 / 特征顺序 / 归一化 / provider 降级） | 21/21 通过 |
| 跨模块流程测试（`rtklib-test`：P1 不注入=原生、P2 全接受逐位一致、P3 全拒绝生效、P4/P4b 异常与非有限置信度降级、P5 默认阈值、P6 阈值传播） | 6/6 通过 |
| I-1 零回归（`rtklib-core` 全量测试） | 398 通过 / 0 失败 / 0 错误（51 因数据缺失跳过） |
| 依赖方向单向（`mvn -pl rtklib-core dependency:tree`） | 无 `rtklib-ai`、无 `onnxruntime` |

### 8.1 接口一训练集与数据体检（2026-11-10）

阶段 3 已用真实数据跑通：Java 侧 `CollectSession` 采集 → `training/fix_validator/assemble.py` 组装，产物为 `data/fix-validator/fix_validator.parquet` + `fix_validator.assemble.json`（`data/` 不入库，见 `.gitignore`）。本表数据由 `FixValidatorCollectTest`（整日单文件口径）产出。

| 项 | 值 |
|---|---|
| 运行数 / 站数 / 天数 | 12 / 6 / 2（2024-060、2024-061） |
| 行数（固定历元） | 16152 |
| 错误固定（3D 误差 > 0.05 m） | 3001（18.58%） |
| join 覆盖率 | 12/12 run 均为 1.00（无残缺 run、无重复历元） |
| 固定解 3D 误差 | p50 3.33 cm / p95 9.03 cm / p99 14.86 cm / max 17.16 cm |

**切分可行性（§7.3）**：6 站满足「≥ 4 站」（2 测试站 + 1 验证站 + 训练站 ≥ 2），且两日数据满足站内按天切分，两层切分可执行。3001 个错误固定事件 ≥ 100，**G-1 的 MDE 前置条件已满足**。

**两项须在阶段 4 前闭环的数据体检发现**（否则标签本身不可信）：

1. **`wrong_fix_rate` 18.58% 远超 §7.4 所述典型区间（1%~5%）**，且高度集中在少数站：HKWS 1.0% / 0%、HKSS 2.6% / 0%（两日），而 HKSL 65% / 39%、HKPC 46% / 58%、HKLM 47% / 60%（060 / 061 两日同向偏高），HKST 17% / 17% 居中。
2. **误差量级与「固定解」不相称**：HKSL / HKPC / HKLM 的 `err_p50` 两日落在 4.3–6.7 cm，已接近甚至超过 5 cm 阈值。`p50` 靠近阈值的 run 更可能是**真值口径错配**（天线相位中心 vs 标石、站号错配）而非真实错误固定。

处置：先按 run 复核真值口径。**阈值口径已于 2026-11-10 统一为 0.20 m**（v1.7），三处不一致的处置逐一落定：

| 出现位置 | 原值 | 处置 | 依据 |
|---|---|---|---|
| 整日线 Parquet 组装 | 0.05 m | **废弃**：该口径产物不再作为可用数据集，上表 3001 / 18.58% 只作「采集链路验证」的历史记录，**不得作为基线引用** | 0.05 m 远小于一个 L1 波长（0.1904 m），落在其中的误差是「正确解、精度退化」而非「解错整周」；且 0.05 m 下错误固定占 34%，G-1 的 30% 相对下降与 G-2 的 1 pp 固定率预算在数学上不可同时满足（需 `W/A ≤ 3.33%`，实际 `W/A = 13.6%`） |
| `assemble.py` 默认 | 0.20 m（已改） | **保留，作为唯一口径** | 可行性推导见 `DEFAULT_THRESHOLD_M` 处注释；`--threshold` 仅作历史复现，默认值不得再改 |
| D7（假固定判定阈值） | 0.1 m | **改为 0.20 m**，与上两处对齐；后续标定只允许在 0.20 m 口径下**向上收紧**，不得回到 0.05/0.1 | 口径分叉会让「扩特征是否有效」与「阈值是否改变」两个变量纠缠，无法归因 |

统一后全部训练与评估只使用小时线（0.20 m）这一份数据，`fix_validator.assemble.json` 的 `threshold_m = 0.2` 是唯一可引用的口径标记。

**第二条采集线（按小时切片，已组装，2026-11-10）**：`rtklib-test` 的 `FixValidatorHourlyCollectTest` 面向现场私有数据（`<rinex-root>/<station>/<yyyy-MM-dd>/<hour>.obs|.nav`，每小时一次独立运行、滤波器逐小时重启），产物落在 `data/fix-validator/collect-hourly/`。它**不写 `truth.csv`**（RINEX 头 `APPROX POSITION XYZ` 与实测固定解相差数十米，不能当真值），因此 `assemble.py` 已补齐无真值文件的组装能力：

- `--truth` 改为可选；缺省走 `--reference cluster`（跨日一致的站内稳健中心），`--reference truth` 才要求真值文件。
- 自动真值**必须跨日校验**：先逐日估稳健中心，再以跨日**中位**为参考，逐日中心偏离超 `--max-day-offset`（默认 0.10 m）即该站该日**整体拒用**；只有 1 个观测日的站直接拒用（无第二个日中心可比对，自动真值必然自证）。
- 若提供了真值文件，则同时输出「跨日簇心 vs 真值文件」的偏移，作为真值口径的**独立交叉校验**——这正是本节要求的「真值口径修干净」的验收手段。

本次组装（`--reference cluster --max-day-offset 0.10`，附 `truth.csv` 做交叉校验）：

| 项 | 值 |
|---|---|
| 运行数 / 站数 / 天数 | 1192 / 16 / 4（2026-06-06、06-11、06-18、06-24） |
| 行数（固定历元） / AR 总历元 / 固定率 | 113962 / 280900 / 40.57% |
| 错误固定（3D 误差 > 0.20 m） | 7907（6.94%） |
| 固定解 3D 误差 | p50 3.50 cm / p99 2.85 m / max 135.47 m |
| 逐日中心偏离跨日中位 | p50 0.009 m / max 0.085 m（容差 0.10 m） |
| 跨日簇心 vs 真值文件偏移 | **p50 0.016 m / max 0.049 m** |
| 拒用 | 313 run：277 run 为 join 覆盖率 0%（trace 缺失/未开 AR）、36 run 为跨日一致性校验拒用（2 个站日） |

**真值口径结论**：自动真值与独立真值文件在 **cm 级一致**（p50 1.6 cm、max 4.9 cm，全部小于 5 cm 判定阈值），说明跨日聚类给出的标签没有系统性偏差，§8.2 的评估结论**不是建立在可疑标签之上**。

跨日校验同时给出了它的必要性证据：站 **X** 在 2026-06-18 的日中心偏离跨日中位 **0.865 m**、2026-06-11 偏离 0.194 m，两者均被拒用。若按「站内池化聚类」口径（等价于不设跨日校验，`--max-day-offset 10.0`），这两日的整日系统性偏差会被混入参考坐标，错误固定从 7907 行升至 **8380 行（7.31%）**——即 473 个历元的标签口径被整日偏差污染。这正是 §9「自动真值自证」风险的实际发生形态。

> **站点匿名约定**：本文件涉及现场私有数据的站点一律以代号表示（§8.2 的 7 个可判定站为 **S1~S7**，§8.1 被跨日校验拒用的站为 **X**）。代号与真实设备号的对应表**不入库**，仅在本地留存；下文所有表格与结论只引用代号。代号刻意避开 A/B/C/D，因为那四个字母在 §7.4 已用作评估组名（A 组 / C 组）。

**与 §8.1 整日线的差异**：整日线的 `wrong_fix_rate` 18.58% 与 `err_p50` 3–7 cm 属**真值口径错配**（天线相位中心 vs 标石、站号错配，见上文两项体检发现），小时线在跨日校验 + 真值交叉校验后为 6.94%，更接近 §7.4 的典型区间上限。两条线口径不同，**不得混用**。

---

### 8.2 接口一阶段 4 训练与评估（2026-11-10）

本节记录阶段 4 的实际执行口径与结果。数据集为**现场私有数据**（16 站 `.rtcm3` 小时切片组装，与 §8.1 的 6 站公开数据不同批次，不入库）。

#### 8.2.1 判据口径修正（先于训练，否则结论不可信）

| 项 | 原实现（错） | 现实现（对） | 依据 |
|---|---|---|---|
| G-1 置信下界 | A 组 Wilson 区间与 C 组 Wilson 区间各自独立求下界再相减 | **配对 epoch bootstrap**：同一次重采样同时进入 A、C 两侧 | §7.4 第 3 条「A/B 必须是配对比较」 |

独立区间相减**丢弃了历元配对结构**，等价于把「同一批历元的前后对比」当成「两个独立样本群的比例差」，下界被系统性压低。同一数据实测：配对下界比独立 Wilson 下界高 **15~30 个百分点**。这是「G-1 长期无法达标」的直接原因之一，属实现缺陷而非模型问题。

#### 8.2.2 G-1 可行性前置（数学约束，必须先筛站再判定）

G-2 只允许剔除 `1 pp × AR 总历元` 的固定历元。若某站

```
错误固定数 / AR 总历元 > 3.33%
```

则即使分类器完美、把这 1 pp 预算**全部**用于命中错误固定，相对下降也仍 `< 30%`。这类站的 G-1 与 G-2 **数学互斥**，无论模型多好都不可能通过，**不能用作 G-3 判定站**——否则会把「数学不可达」误读成「模型不行」。16 站中 7 站满足可判定条件，下文记为 **S1~S7**（各表同此编号，对应关系不入库）。

#### 8.2.3 阈值标定口径

三条约束同时生效：

1. **标定集必须是「未参与训练也未参与早停」的独立验证站**。用训练站内的验证日标定，阈值会被「同站好置信度」拉偏，部署到未见站时泛化失效（§7.3 第二层要求早停不跨站，独立验证站因此也不得进早停集）。
2. **多站联合标定**。阈值是绝对值，各站置信度分布不同，同一 `t` 在 A 站剔除 1.0 pp、到 B 站可能剔除 1.9 pp。单站标定必然出现「标定站过、测试站 G-2 超标」。改为：单一全局阈值须让**每个**标定站下降都 ≤ 1 pp，再最大化**最坏站**的 G-1 下界（与 G-4 的保守取向一致）。
3. **候选须覆盖低分段密集区**。纯 `0.02` 步长网格在 sigmoid 低分段（`[0, 0.1]`）过稀，只能用到 0.6 pp 预算，把 1 pp 的可用空间白白浪费。改为「粗网格 + 各标定站置信度分位点并集」。

#### 8.2.4 留一站（LOSO）跨站泛化探针

G-3 的真实模拟：7 个可判定站轮流做「完全未见站」，训练用其余站（站内按天切训练/早停），阈值在该站自身的 1 pp 预算内扫描取最优。

| 未见站 | A 组固定率 | A 组错误固定率 | 1 pp 内最优 rel（点估计） | 配对下界 | 实际下降 | 阈值 |
|---|---|---|---|---|---|---|
| S1 | 22.6% | 10.1% | 28.2% | 23.4% | 1.00 pp | 0.0676 |
| **S2** | 44.8% | 2.6% | **37.6%** | **31.0%** | 0.99 pp | 0.0861 |
| S3 | 38.3% | 3.9% | 15.5% | 11.3% | 1.00 pp | 0.0824 |
| S4 | 65.0% | 1.5% | 12.8% | 7.9% | 0.97 pp | 0.2749 |
| S5 | 66.9% | 0.7% | 28.1% | 19.2% | 0.96 pp | 0.1776 |
| S6 | 47.2% | 3.8% | 30.1% | 25.4% | 0.96 pp | 0.0499 |
| S7 | 47.7% | 3.9% | 26.7% | 21.9% | 0.99 pp | 0.1500 |

**结论：G-3 不可达。** 7 站中仅 1 站（**S2**）在 1 pp 预算内配对下界 ≥ 30%（31.0%），G-3 要求 ≥ 2 站。

更严格地说，上表已是**乐观上界**：每站的阈值是拿该站自己的标签扫出来的，真实部署只有一个全局阈值。全链路实测（2 标定站联合标定 → 阈值 0.105）在测试站上 G-1 通过（下界 41.1%）但 G-2 超标（1.45 pp），G-3 仍为 0 站。

#### 8.2.5 留多站（LOMO）跨站泛化探针（真实部署协议）

LOSO 与真实部署的差距在于：LOSO 每站都用**自己的标签**扫阈值，等价于「为每个站单独训练一个阈值」；真实部署是「一个模型 + 一个全局阈值」，且 G-3 要求 **≥ 2 个未见站同时通过**。因此补做 LOMO：7 个可判定站按轮转构成 7 组「2 标定站 + 2 测试站」配置，训练只用剩下 12 站（站内按天切训练/早停），阈值由 2 个标定站联合标定，再在 2 个测试站上判定——与 `train.py` 的两层切分完全同构。

| 标定站 | 测试站 | 选中阈值 | 测试站下降 pp | 测试站配对下界 | 达标站数 |
|---|---|---|---|---|---|
| S1 / S2 | S3 / S4 | 0.0430 | 0.19 / 0.01 | 0.6% / −0.0% | 0 |
| S2 / S3 | S4 / S5 | 0.1171 | 0.02 / 0.23 | −0.1% / 5.1% | 0 |
| S3 / S4 | S5 / S6 | 0.1000 | 0.25 / 1.11 | 5.1% / 26.5% | 0 |
| S4 / S5 | S6 / S7 | 0.1117 | 1.14 / 0.09 | 23.2% / 2.4% | 0 |
| S5 / S6 | S7 / S1 | 0.0765 | 0.08 / 1.73 | 1.0% / 31.6% | 0 |
| S6 / S7 | S1 / S2 | 0.0696 | 1.48 / 0.63 | 27.4% / 19.6% | 0 |
| S7 / S1 | S2 / S3 | 0.0738 | 0.32 / 0.47 | 10.7% / 1.1% | 0 |

（「测试站下降 pp」为相对 AR 总历元的固定率下降，「配对下界」为 G-1 的配对 bootstrap 2.5% 分位。）

**结论：G-3 通过 0/7 组。** 全部 14 次「未见站」判定中，没有任何一次同时满足 G-2（下降 ≤ 1.0 pp）与 G-1（下界 ≥ 30%）。为区分「阈值选点不行」与「模型不行」，又用标定站扫遍全部可行阈值（仅用标定站数据选点，测试站只用于事后判定），结果仍是 **0/7 组、0/14 站**。

逐站天花板（作为未见测试站、跨配置取最优）：

| 站 | 最优下界 | 对应固定率下降 | 达标次数 |
|---|---|---|---|
| S6 | 47.9% | 3.59 pp（**超 G-2**） | 0/2 |
| S1 | 32.0% | 1.79 pp（**超 G-2**） | 0/2 |
| S7 | 25.9% | 1.69 pp（**超 G-2**） | 0/2 |
| S2 | 19.6% | 0.63 pp | 0/2 |
| S5 | 9.0% | 0.38 pp | 0/2 |
| S3 | 1.1% | 0.24 pp | 0/2 |
| S4 | 0.0% | 0.00 pp | 0/2 |

**判据退化修正（本次同步修复的实现缺陷）**：联合标定原先以「最坏站下界最大」为唯一排序键。当**没有任何阈值能让所有标定站同时达标**时，「几乎不剔除任何历元」的候选下界恰为 0，反而是最大值——于是选出一个形同不干预的阈值（实测选中 0.02，下降 0.00 pp），把「不可行」伪装成「已标定」。改为四级排序键 `(达标站数, 最坏站下界, 平均下界, 固定率下降)`：先最大化「有几个站真能达标」，同等达标站数下取最坏站下界最高者，完全并列时取用满预算者。修正后选中阈值从 0.02 变为 0.07~0.12（真正剔除了历元），但结论仍为 0/7——**不是选点问题**。

#### 8.2.6 根因定位（三项排除）

| 假设 | 检验 | 结论 |
|---|---|---|
| 判据口径过严 | 改用 §7.4 第 3 条要求的配对 bootstrap | **排除**：下界已提升 15~30 pp，仍是唯一合法口径，不能为凑指标放宽 |
| 阈值候选不够密 | 改用分位点并集，可精确取到预算上限 | **排除**：1 pp 预算已能用满 |
| 阈值选点规则退化 | LOMO 中扫遍全部可行阈值（仅用标定站选点） | **排除**：0/7 组、0/14 站，与规则选中阈值结果一致（§8.2.5） |
| 标签口径可疑 | 小时级采集线跨日聚类 + 真值文件交叉校验 | **排除**：自动真值与真值文件 cm 级一致（p50 1.6 cm / max 4.9 cm），见 §8.1 |
| 特征信息量不足 | 站内作弊训练（用本站标签训本站）+ 留一站 + 留多站对比 | **成立**：站内作弊也仅能摸到 ~30% 下界；留一站最优下界 7.9%~31.0%，留多站（真实部署协议）0/14 站达标 |

配套证据：6 维特征的**跨站中位数极差达 2.7~3.9 倍**（站址指纹效应）。这造成双重约束：一方面站内可判别的信息在跨站迁移后失效（G-1 掉到 0~10%），另一方面**单一全局阈值无法在各站同时满足 G-1/G-2**——LOMO 里下界超过 30% 的两个站（**S6** 47.9%、**S1** 32.0%）都是靠 **3.59 pp / 1.79 pp** 的固定率下降换来的，均超 G-2 预算 1 pp。也就是说，**G-2 预算与 G-1 相对降幅之间的换算关系本身依赖站内错误固定密度**，跨站阈值可迁移性才是当前瓶颈，而不是单站判别力上限。

#### 8.2.7 处置

**不调整判据口径**（§7.4 不许为凑指标改口径，也不许挑选测试站）。两块「零新数据资产」已按用户要求先闭环：

| 资产 | 闭环状态 |
|---|---|
| §8.1 小时级采集线 | **已闭环**：`assemble.py` 补齐无真值文件组装（`--reference cluster` + `--max-day-offset`），16 站 / 4 日 / 113962 行已组装，真值口径经独立真值文件校验到 cm 级（§8.1） |
| §8.2 留多站 LOMO | **已闭环**：7 组「2 标定 + 2 测试」配置跑完，G-3 通过 0/7；同步修复联合标定的排序键退化缺陷（§8.2.5） |

**当前结论**：在 §7.4 判据下，接口一**不通过**（G-3 不可达），且该结论**不是**标签可疑或实现缺陷造成的——标签已交叉校验、口径缺陷已修复、选点规则已验证。据此**接受现状**：

> **v1.7 / v1.8 口径更正**：本段的「不通过」特指**原 G-1/G-2 成对组合（1 pp / 30%）**在 LOMO 单次配置下的判定（0/7），**不是**「接口一无任何可交付档」。v1.7 起可交付口径改为成对档（§7.4.4 / D13）；v1.8 起按**交付形态（层次 A）**重算为 B 档 4/7、C 档 6/7，**均满足 G-3 的「≥ 2 未见站」**（§8.2.10）。本段其余结论（不调整判据、保持默认关闭、交付管线而非结果、扩数据/扩特征二选一、不做的三件事）**全部继续有效**。

- 接口一保持**未注入即关闭**（无 `AiProvider` 时 core 行为与 v3.4.0 逐位一致），不进入默认开启流程（D8）。
- 交付物为**管线 + 结论 + ONNX 产物**（`fix_validator.onnx` / `fix_validator.norm.json`），模型仅证明链路可用，不承诺精度（§1.1 三句免责）。
- 后续若要继续，只能二选一，且均须作为新决策立项：

1. **扩数据**：更多站 / 更多天 / 更多场景（尤其高错误固定率但 AR 历元多的站，使 `错误固定数/AR总历元 ≤ 3.33%`）。
2. **扩特征**：§5.1 的 6 维是定死的决策（D2），扩维须走新决策记录，且须先证明新维不泄漏真值派生量（§4.1 契约要点 2）。

**明确不做的三件事**（避免下一轮重复踩坑）：不放宽 G-1/G-2/G-3 任一阈值；不挑选「好看的」测试站；不用测试站标签反选阈值（否则 LOMO 会退化成 LOSO 的乐观上界）。

> **v1.7 说明**：上面三条仍全部有效。D13（§7.4.4）**未改动任何一个阈值数字**，改的是「预算-降幅」成对组合的可交付档，且新增的口径未增加任何判据自由度（§7.4.4 末段）。「不放宽」与「承认原组合物理不可达」是两件事，不是同一件事。

#### 8.2.8 尾部形态与几何退化（v1.7 新证据）

**形态标注（v1.8 补正）**：本节探针是**层次 B（站内模型）**——每站只用本站前半天的数据训练、后半天的数据评估。它证明的是「站内信号存在」的上界，**不是交付形态**。层次 A（共享模型）的对照见 §8.2.10，两者在 1 pp 档同为 0/7、在 2 pp 与 5 pp 档差 1 站。

**动机**：§8.2.5 已排除「阈值选点不行」、§8.2.6 已排除五项实现与口径原因，但「1 pp 预算内为何取不到 30% 相对下降」仍未定位到物理层。本节用两个**零成本探针**（不改特征、不采新数据、不重训）把根因落到分数尾部的错误密度形态上，同时给出 pdop 门禁的量化收益。

**探针设置**：7 个可判定站（S1~S7），站内按天切分（前半训练、后半测试），以逻辑回归作模型代理（与正式 MLP 同特征、同切分、同配对 bootstrap 口径），扫遍全部可行阈值。探针只用于**定位根因**，其数字不作为交付指标；交付指标须由 `train.py` 全链路复现（§8.2.9）。

**证据一：尾部不单调（0/7）**。7 个可判定站中，错误固定率随置信度降低而**单调递增的站数为 0/7**。最低分档（0–1%）的错误固定率在 0%~25% 之间，其中 4 个站为 0%。这意味着「按分数剔除最低档」这一步在最紧预算下**剔不到错误固定**。

**证据二：最低分档是「几何最差」而非「最可能错」**。0–1% 档的 `pdop` 中位数显著高于全站整体，而错误固定率接近 0%。原因是高 DOP 历元即便解错也很难通过 `ratio` 与方差检验——「固定且被固定在错解上」的样本在该档**结构性缺席**；真正的错误固定藏在「几何良好但多路径强」的中段。这直接推出 §7.4.3 的门禁规则。

**证据三：pdop > 8 门禁的量化收益**（同一探针，加门禁前后对照，层次 B）：

| 预算 / 降幅 | 无门禁达标站 | 加 `pdop > 8` 门禁后达标站 |
|---|---|---|
| 1 pp / 30% | 0 / 7 | **0 / 7** |
| 2 pp / 20% | 3 / 7 | **5 / 7** |
| 5 pp / 15% | 7 / 7 | **7 / 7** |

**1 pp 档逐站明细**（点估计 / 95% 下界，无门禁 → 有门禁）：

| 站 | 无门禁 | `pdop ≤ 8` | 变化 |
|---|---|---|---|
| S1 | 15% / 9% | 22% / 16% | +7 |
| S2 | 21% / 14% | 38% / 28% | +14 |
| S3 | 3% / −1% | 5% / 0% | +1 |
| S4 | −2% / −2% | 22% / 12% | +14 |
| S5 | 11% / 4% | 4% / −0% | −7 |
| 848643 | 9% / 5% | 9% / 5% | 0 |
| 860355 | 22% / 17% | 22% / 17% | 0 |

门禁把 4 个站的下界从负值拉回正值，最高下界到 **28%**（`S2`），但**距 30% 仍差 2 个百分点且无一站过线**——这是「不可达」的直接量化依据（§7.4.1 的 v1.7 补充）。注意门禁不是单调增益：`S5` 的点估计从 11% 掉到 4%（该站的低分档里确有错误固定，被门禁放过了），故门禁常数是**取舍**而非纯改进（D14 的重开条件即由此而来）。

**结论**：pdop 门禁是**紧预算下的必要条件**（2 pp 档 3/7 → 5/7），但**不是** 1 pp / 30% 的充分条件。1 pp 档的瓶颈在「最低 1% 档本身不含错误固定」，属数据形态问题，门禁改不了。

#### 8.2.9 处置与方向修正（v1.7）

四件事，逐条对应 D13 / D14 / D15：

1. **pdop 前置过滤已落地**（D14 / §7.4.3）。core 侧 `RtkCore.aiAcceptFix()` 在阈值比较**之前**对 `pdop > 8.0` 直接返回 `true`；训练侧 `train.py` 以 `PDOP_ABSTAIN_MAX = 8.0` + `_accepted()` 在**联合标定、单站报告、全局评估**三处生效，语义逐字对齐（`conf >= threshold || pdop > 8.0`）。**这是本轮唯一可立即验证的收益：零新数据、零新特征、不改 ONNX 模型与归一化参数**，实测 2 pp / 20% 档 3/7 → 5/7。
2. **1 pp / 30% 正式标记为「当前特征集下不可达」**（§7.4.1 的 v1.7 补充、§7.4.4 的 A 档）。**阈值不改**，只记录结论，证据是 §8.2.8 的尾部单调 0/7 与最低档错误率 0%~25%。措辞上区分「试不出来」与「不存在」：本结论属后者——扫遍全部可行阈值仍 0/7（§8.2.5）。
3. **可交付口径改为成对档位**（D13 / §7.4.4）：**B 档 2 pp / 20%（层次 A 4/7，满足 G-3）或 C 档 5 pp / 15%（层次 A 6/7，满足 G-3）**，任一成立即可交付。为什么不是「凑指标」：G-1 的 30%、G-2 的 1.0 pp、G-3 的「≥ 2 未见站」三个数字未改；判据自由度（未见站、单一全局阈值、配对下界、不挑站、不用测试标签选点）一条未增；改的只是被证明**物理不可达**的那一对组合。全链路复核（`train.py`，0.20 m 口径）：A 档测试站 G-3 0/2、全局 G-1 下界未达 30%；B 档测试站 G-3 1/2；C 档测试站 G-3 1/2，但全局 G-1 与 G-2 均已通过。三次运行的可判定站集合随预算档变化，切分随之不同，**故三行不可逐行直接比较**，只作「链路能产出达标站」的存在性证据；逐站达标率以 §8.2.8（层次 B）与 §8.2.10（层次 A）为准（§7.4.4 口径边界第 1 条）。
4. **方向修正（D15）**，三条，均带本数据证据：

| 原方向 | 处置 | 本数据证据 |
|---|---|---|
| 按环境分组（城区/山区、长/短基线等粗粒度分组） | **删除**，替换为**按站标定** | 7 站按高程切成 L/H 两组后，「同组站」与「异组站」交叉对照无稳定优势：1 pp 档 7 站中 1 站同组更优、2 站同组更差、4 站持平；5 pp 档同组与异组几乎并列。粗粒度分组不携带可迁移信息，收益只能落在**站址个体**（多路径与 `snrRover` 是站址物理属性，§7.3） |
| 基站侧特征（基站多路径 / 基线长度） | **暂缓**，等长基线数据到位再议 | 现有 16 站几何跨度：纬度 1128 m、经度 1003 m、高程 850.35~1147.34 m，即 **1.1 km × 1.0 km 的山谷内集群**，基线长度近似恒定，基站侧特征在本数据上**无变异可学** |
| 海洋 vs 内地 | **本数据做不了**，写清边界防误读 | 同上：16 站全部落在同一小区域，不存在「海洋/内地」两类样本。任何跨海/跨内地的泛化结论**在本数据上无证据**，不得引用本阶段结果作为支持或反对 |

#### 8.2.10 层次 A / 层次 B 对照实测（v1.8）

**动机**：§8.2.8 的数字全部来自「每站用本站自己的两天数据、前半天训练后半天测试」的**层次 B**。但 §6.4 已把交付形态定为**层次 A**（共享模型 + 分站阈值）。若两者差很多，§7.4.4 的逐站达标率就必须按层次 A 重算，否则等于用一个**永不部署的形态**来证明可交付。

**探针**：与 §8.2.8 同数据、同特征、同切分、同配对 bootstrap、同逻辑回归代理、同 `pdop ≤ 8` 门禁。唯一差异是**训练集**：层次 A 用**其余 6 站的全部数据**训练，层次 B 只用**本站前半天**。两者都只做「阈值分站」，故差异纯由**模型粒度**贡献，不含阈值口径混淆。

**总表**（达标站数 / 7，判据为相对下降 95% 下界过线）：

| 预算 / 降幅 | 层次 A（共享模型，交付形态） | 层次 B（站内模型，上界） | 差 |
|---|---|---|---|
| 1 pp / 30% | **0 / 7**（最高下界 23%） | **0 / 7**（最高下界 28%） | 0 |
| 2 pp / 20% | **4 / 7** | **5 / 7** | −1 |
| 5 pp / 15% | **6 / 7** | **7 / 7** | −1 |

**逐站明细（点估计 / 下界，两形态均含 `pdop ≤ 8` 门禁）**：

| 站 | 1 pp：A / B | 2 pp：A / B | 5 pp：A / B |
|---|---|---|---|
| S1 | 14/9 ｜ 22/16 | 31/24 ✓ ｜ 36/28 ✓ | 61/52 ✓ ｜ 58/49 ✓ |
| S2 | 28/20 ｜ 38/28 | 55/45 ✓ ｜ 67/58 ✓ | 79/71 ✓ ｜ 78/70 ✓ |
| S3 | 9/4 ｜ 5/0 | 19/11 ✗ ｜ 24/16 ✗ | 37/27 ✓ ｜ 43/33 ✓ |
| S4 | −2/−2 ｜ 22/12 | **−3/−4 ✗ ｜ 67/55 ✓** | **−3/−8 ✗ ｜ 87/78 ✓** |
| S5 | −0/−2 ｜ 4/−0 | 12/4 ✗ ｜ 12/4 ✗ | 38/26 ✓ ｜ 59/47 ✓ |
| 848643 | 29/23 ｜ 9/5 | **47/40 ✓ ｜ 35/29 ✓** | 79/73 ✓ ｜ 74/68 ✓ |
| 860355 | 16/11 ｜ 22/17 | 26/21 ✓ ｜ 28/22 ✓ | 33/26 ✓ ｜ 31/25 ✓ |

**结论（四条，直接决定 §7.4.4 的口径）**：

1. **差距集中在 2 个站，且方向不一致**。`S4` 是层次 B 大幅领先（2 pp 档 −3% → 67%，5 pp 档 −3% → 87%），该站的错误固定几乎完全由**本站特有信号**决定，跨站学不到；`848643` 反过来是层次 A 更好（2 pp 档 47% vs 35%，5 pp 档 79% vs 74%），说明共享模型从其余站获得的样本量优势在该站**抵消了**站址特异信号；其余 5 站两形态基本持平（差距 ≤ 8 个百分点）。
2. **不能笼统说「层次 B 更强」**。B 只在 `S4` 一站有量级优势，另有一站（`848643`）反而更弱。因此「按站训练模型」不是普遍增益，而是**特定站址的定向补偿**——这正是层次 B 只保留为「固定基础设施站可选增强路径」的量化依据（§6.4.3、D16）。
3. **交付形态（层次 A）在 B/C 两档仍满足 G-3 原判据**：4/7 与 6/7 都远超「≥ 2 未见站」，只是未达 v1.7 的加强口径（5/7、7/7）。因此**可交付结论不变**，但表述必须改为「满足 G-3」而非「达标加强口径」（§7.4.4 已按此更正）。
4. **1 pp / 30% 在两形态下同为 0/7**，这是本轮最重要的负面结论：该组合的不可达性**与模型粒度无关**，是数据形态（错误固定密度 + 最低分档不含错误固定）决定的。任何「换个训练方式就能过 1 pp / 30%」的预期都不成立。

**对交付的影响**：本节把「模型粒度」这一维从可交付证据中**排除**——层次 B 的 5/7、7/7 不能用来给层次 A 背书。§7.4.4 的实测列已改为双列并标注形态，避免下一轮再出现「拿层次 B 数字报层次 A 交付」的口径混用。

### 8.3 接口三训练与 A1/A2/A3 复核（2026-11-11）

**数据**：6 站 × 4 日 × 24 小时，576 run 全部 `status=ok`，组装 1,024,348 行（DDRES 行 1,024,348、`REJ` 剔除 2268、重复 0、坏行 0；特征行 2,364,848，未配对 1,340,500 均为「未参与双差 / 参考星 / 被 maxinno 剔除」的正常来源）。标签 join 率 100%。

**两层切分（§7.3，先按站再按天）**：train 388,544 行 / 3 站；val 276,080 行 / 1 站 + 3 站内验证日（`2026-06-24`）；test 359,724 行 / 2 站（`…128507`、`…268595`，未参与训练与早停；设备号末 6 位掩码，对应表不入库，见 §8.1 站点匿名约定）。

**特征口径**：17 维占位不删维。`f6`（`lock`）在特征提取层做 `max(0, lock)` 截断后重新落盘（实测 `f6: min=0, max=219, std=33.41`，负值行 0）。训练集实测恒零列 5 个（`f2/f3/f8/f10/f12`），`std` 全部按 1.0 兜底；`norm.json` 的 `constant_cols` 只标注**结构性**恒零的 `f2/f3/f8`，`f10`（VSAT，本批全为健康星）与 `f12`（SYS_ID，本批全 GPS=5.0）属**本批快照属性**，不纳入标注，故 A1 保留 `constant_cols_consistent=false` 警告（A1 判据本身仍通过）。Java 侧 `FeatureNorm.load()` 只解析 `mean/std`，多余字段被忽略，**无需改 Java 代码**。

**训练（默认超参：epochs=30 / batch=256 / lr=3e-3 / hidden=32 / patience=5）**：`best_epoch=7`，`best_val_loss=3.4613`。产物 `obs_corrector.onnx`（入 `[batch,17]` / 出 `[batch,2]`，batch 探针 1/7/64 全过）+ `obs_corrector.norm.json` + `obs_corrector.metrics.json`。

**A1（契约与流程）通过**：ONNX 契约、17 维 norm、恒零列 std=1.0 兜底、三站集合两两不交，4 项全绿。

**A2（无明显变差，残差代理口径）通过**：测试集 RMS `4.3963 → 3.9314 m`，相对改善 **10.6%**（95% CI [10.5%, 10.7%]）；分站 11.4%（`…128507`）/ 9.9%（`…268595`），均不恶化。1σ 覆盖率 0.713 ⇒ `scale` 略偏保守（偏大），方向上安全。**A2-1/A2-2/A2-4（固定率 / 错误固定率 / ns）离线不可判定**，须 Java 侧同历元配对解算。

**A3（辅助固定）——首轮判据下不通过，放宽后通过**：

| 口径 | G0 | R / R% | D / Dσ | 有效改正 | 判定 |
|---|---|---|---|---|---|
| v1.8 原判据（`R ≤ 0.7` 且 `D ≥ 3σ_dd`） | 20,563 | 0 | 0 | 0 行 / 0 星 | **不通过**（且 `reduction_reachable_rows = 0`，即结构上不可达） |
| v1.9 新判据（`R% ≤ 0.85` **或** `Dσ ≥ 1.0σ_dd`） | 20,563 | **9,743** | 0 | **9,743 行 / 33 星** | **通过** |

新口径可达行数 12,417（旧口径 0），实际命中 9,743，占可达行 **78.5%**。`σ_dd` 中位数 2.773 m ⇒ G0 门限中位 8.319 m、`Dσ` 门限中位 2.773 m；`|bias|` 最大 2.000 m（`bias_max = 2.0`）。

**必须同时记录的限定条件（三条）**：

1. **本次 A3 通过完全由 `R%` 分支贡献，`Dσ` 命中 0 行**。原因：`1.0σ_dd` 中位 2.773 m 已超 `bias_max = 2.0 m`，绝对降幅 1σ 超出钳位能力。故「A3 通过」不构成「模型能改正 1σ 量级的粗差」的证据，只证明「在大残差行上能把残差压掉 ≥ 15%」。要让 `Dσ` 生效须抬 `bias_max` 至 `≥ 3.0 m`（改输出契约，走新决策记录）。
2. **A3 的 `before/after` 在本轮是「同一行 `ddres` 与模型 `bias` 相减」的离线代理**，不是 A 组/C 组双跑配对。§7.5.3 第四步第 2 条要求的「同历元同 `(i, frq)` 双跑配对」须在 Java 侧完成，与 A2 配对一并做。
3. **判据放宽是对「判据缺陷」的修正，不是对模型放水**：旧口径的不可达性由 `reduction_reachable_rows = 0` 直接证明（可行集为空），与模型输出无关；同一模型、同一数据在新旧口径下的差异全部来自判据可行性，不含任何重训。

**下一步（Java 侧）**：启动 A2 配对——同历元配对计算固定率与错误固定率（A 组未注入 vs C 组仅开接口三），并顺带完成 A3 的双跑配对（`biasMeters` 全置 0 的零基线必须恒为 0，§7.5.3 第四步第 4 条）。

### 8.4 方案 B：端到端坐标 Loss 对照实验（2026-11-11，**已执行，判定不交付**）

> 本节由原 `docs/AI补充.md` §1/§3/§4 并入并**改写为执行结果**（v1.10）。原文档 §3 的实现规格（`scale = 1.0 + F.softplus(...)` 无上界、`bias` 硬钳 `[-2,2]`、Loss 为裸 3D MSE）**已作废，不得照此实现**；实际实现见 `training/obs_corrector/train_e2e.py` 与 §8.4.2。完整评估与逐项数据见 [GNSS-RTK-AI-report.md](GNSS-RTK-AI-report.md) §8 / §8.9。

#### 8.4.1 目的与预注册判据

用**最终坐标与真值的误差**直接反传，绕开 §6.3 人造标签的物理口径约束（§5 失效 1 的根因），验证端到端范式能否超过标签监督（方案 A）。

**判据（执行前写定，未事后调整）**：在同一套 Java 双跑配对的**整体 135,198 历元**口径下，端到端模型的 **H RMS 与 V RMS 必须同时低于生产 D 组**（146.2 cm / 265.6 cm）；任一项不达标即**立即停止调参**。

#### 8.4.2 实现要点（与方案 A 的唯一差异是 Loss）

- **契约完全不变**：输入 `[batch, 17]`、输出 `[batch, 2]`（`biasMeters` / `varianceScale`），钳位头仍是 `bias = B·tanh(z)`、`scale = 1 + (S-1)·sigmoid(z)`（[train.py#L96-L97](file:///D:/code/rtklib_java/rtklib-ai/training/obs_corrector/train.py#L96-L97)），与 Java 侧 core 逐位一致；**Java 侧零改动**，只换模型文件。
- **可导单步 WLS**（`training/obs_corrector/e2e_wls.py`）：几何矩阵 `H` **detach**（梯度不得穿透几何结构），仅模型输出的 `bias`/`scale` 参与回传；权重 `W = diag(1 / (base_var · scale))`，观测向量 `y = (raw_obs − comp_ranges) − bias`；只做**一步** `dx`，不迭代（保证可导）。
- **导出**：`obs_corrector_e2e.onnx` + `.norm.json`（入 `training/out/`），产物与方案 A 并存。

#### 8.4.3 两轮迭代与结果

| 轮次 | 配置差异 | 整体 H / V RMS (cm) | 判定 |
|---|---|---|---|
| 生产 D（方案 A 参照） | — | **146.2 / 265.6** | 基准 |
| e2e-v1 | 裸 3 维归一化，`scale_max = 4.0` | 142.5 / 280.2 | H 更好、V 更差 → **未双赢** |
| e2e-v2 | H/V 各自按 L0 归一化，`scale_max = 3.0` | **149.0 / 300.8** | 两项同时更差（+2.8 / +35.2）→ **未双赢，且劣于 v1** |

**结论**：按判据**立即停止调参**（含 epoch / `lambda` / 网络宽度 / 早停策略 / 数据扩充）。**最终交付方案 A**（D 组口径），E2E **不交付**。

#### 8.4.4 客观可用性：E2E 是「有效但不可控」（摘要）

**不能写成「E2E 无效」**，四组量化事实（完整表见报告 §8.9）：

| 维度 | 事实 |
|---|---|
| 有效性 | 相对「完全不注入」基线，3D RMS **362.4 → 311.6 cm（−14.0%）**；e2e-v1 C 的水平 RMS **140.0 cm 为全部 7 组最低**（优于生产 D 的 146.2） |
| 敏感性（post-hoc） | 剔除「基线即不可用」站 `…156203`（占 16.2% 历元、A 组错固率 48.66%）后，e2e-v1 C 在 5 站上 **H/V 双赢**（127.7 / 240.1 vs 136.9 / 246.8）。**该分析不得反推判据**（§7.4 口径变更须走新决策记录） |
| 稳定性 | 仅改「Loss 归一化 + `scale_max`」，整体 V 摆动 **+20.6 cm（+7.4%）**、test 集 V 摆动 **+52.5 cm（+17.3%）** |
| 指标背离 | v1 在验证集（即 `…156203`）离线坐标 Loss 改善 **75.1%**（1.7249 → 0.4298），同一站在 Java 侧载波 KF 的 **V RMS 344.9 → 425.4 cm（+80.5 cm）** |
| 假优势 | e2e-v2 D 绝对错固数 4,101 最低，但绝对固定数少 1,429、错固率仅低 0.04 pp → 是**固定更少**而非**固定更准**，不构成安全优势 |

#### 8.4.5 处置（D19 并列结论）

| 用途 | 结论 |
|---|---|
| 生产默认开启 / 替换方案 A | **不可以**（判据未过 + 不稳定 + 指标背离） |
| **同一 SPI 开关下的实验 provider**（默认关闭，仅 `AiHooks.install(provider)` 显式注入） | **可以**：契约一致、Java 侧零改动、ONNX 仅 8 KB，只换模型文件 |
| 研究基线 / 对照组 | **可以**（「坐标反传」确实带来真实水平增益，该信号值得后续利用） |
| 用于模型选择 / 早停 | **不可以**（§8.4.4 指标背离） |

**正式表述**：端到端（E2E）作为**「有效但不可控」的实验 provider 予以保留**——默认关闭，仅允许显式注入，**不承担任何生产交付责任**，不得作为方案 A 的替代或默认回退目标。

#### 8.4.6 升级为交付候选的四条前置条件（下一阶段立项前提，缺一不可）

1. **至少 3 个独立验证站**：当前 `val` 只有 1 站且该站基线即不可用（错固率 48.66%），早停决策被单站主导。
2. **3 个 seed 稳定性证明**：整体 H/V RMS 极差 **≤ 2%**（以生产 D 为参照）。本轮 7.4% / 17.3% 摆动即反例。
3. **预注册判据**：判据、口径（整体 vs 剔除不可用站）、统计量须训练前落盘，禁止看到结果后调口径。
4. **真实配对驱动的选型闭环**：训练 → Java 双跑 → 统计压到可迭代量级（当前单轮约 15 min × 576 run），并明确「离线指标不得参与模型选择」。

**方法论教训（写入本设计的长期约束）**：**可导 WLS 的码残差坐标 Loss 与 Java 侧载波 KF 的 H/V 误差不是同一个量，甚至不是单调关系**——故任何以「坐标反传」为目标的训练，都不得用离线 Loss 做模型选择，唯一可靠判据是同口径真实配对。

---

## 9. 风险

| 风险 | 说明 | 应对 |
|---|---|---|
| ONNX Runtime 兼容性 | Java 17 下 native 库加载失败 | 阶段 2 首日最小验证；备选纯 Java 前向传播（§8）。**已闭环（1.17.3 验证通过，2026-11-07）** |
| 接口三过度自信 | 只改偏差不膨胀方差 → 假固定率上升 | §4.3.4 强制单侧膨胀 + 成对输出 fail-fast |
| 训练泄漏 | 随机切分导致虚高评估 | §7.3 两层切分：**第一层必须按站**，站内再按天；禁止随机切分 |
| 标签泄漏 | 真值派生量混入特征 | §4.1 契约要点 2 明令禁止 |
| **接口一 f4 `ns_fixed` 越界（> 1.0）** | `ns_fixed = nb / ns`，但 `nb` 计的是**已固定的模糊度个数**（每星每频点一个，上界 `nf × ns`），`ns` 计的是卫星数——双频满固定时该值可达 2.0，而 §5.1 把它当 `[0,1]` 的「几何冗余度」用 | **记为独立 issue `ISSUE-AI1-01`，本轮不修**（只影响特征语义与归一化分布解读，不影响流程正确性：`ns <= 0` 的除零兜底已生效）。重开条件：接口一特征集重定义时，改为 `nb / (nf·ns)`，或改名 `n_amb_fixed` 并在 `norm.json` 重新拟合 |
| **真值口径错配** | 固定解系统性偏离已知坐标（实测 `err_p50` 3–7 cm）→ 整 run 被判为错误固定，`wrong_fix_rate` 18.6% 偏离典型 1%~5% | 组装后逐 run 体检 `err_p50`/`err_p99`；`p50` 接近阈值的 run 先核查真值口径与站号，**不得直接进训练**（§8.1）。**小时线已交叉校验到 cm 级（§8.1）** |
| **自动真值自证** | 从待标注的固定解里估真值，等于让「主导簇」自证为正确，整日系统性偏差无法被发现 | 真值只由**跨日**紧致聚类给出，并做逐日中心一致性校验：任一日中心偏离跨日**中位**超阈值即该站该日拒用；只 1 日的站直接拒用；真值只参与打标签，不得进入特征（§4.1 契约要点 2）。**已实现并实测生效**：站 **X** 两日偏离 0.865 m / 0.194 m 被拒用，否则错误固定被污染 473 行（§8.1） |
| **阈值标定规则退化** | 「最坏站下界最大」单键在**所有站都无法达标**时，会选中「几乎不剔除历元」的阈值（下界恰为 0），把不可行伪装成已标定 | 排序键改为 `(达标站数, 最坏站下界, 平均下界, 固定率下降)`；LOMO 另用「扫遍全部可行阈值」独立复核（§8.2.5） |
| **剔除规则口径漂移** | 标定 / 单站评估 / 全局评估 / core 运行时四处对 `pdop` 门禁的取值或比较方向不一致 → 标定时放过、运行时剔除，指标不可复现且难以排查 | 门禁常数**单点定义**：训练侧 `PDOP_ABSTAIN_MAX` 与 core 侧同值 8.0，判定式统一为 `conf >= threshold \|\| pdop > 8.0`；改任一处即视为改判据，须走新决策记录（D14 / §7.4.3） |
| 场景过拟合 | 学到「这是哪个站」而非「这是错误固定」 | §7.4 G-3/G-4 跨站泛化判据 |
| 示例模型被误用 | 使用者直接部署示例 `.onnx` | 三句免责声明（§1.1）+ `models/` 目录标注「仅演示」 |
| 依赖污染 | ONNX 进入 core 拖累所有下游 | §3.1 core 只放零依赖 SPI |
| 政策冲突 | 与 ROADMAP「仅 bugfix」冲突 | §1.4 适用范围界定 + 默认关闭硬规则 |
| 泛化局限 | GNSS-ML 跨场景泛化差是领域共识 | 交付管线而非结果，泛化责任在使用者 |
| **注入点测试污染** | 用例注入后未清理，其后「原生行为」用例实际带 AI 跑且全绿 → I-1 失去可信度 | `AiHooks.clear()` + teardown 断言注入点为空；`ThreadLocal` 保证线程隔离 |
| **热路径推理** | 接口三若在 `varerr` 内推理，单历元增加 90–140 ms | §3.3 / §4.3.4 强制「每历元批量算 + `varerr` 只读查表」 |

---

## 10. 决策记录

| # | 决策 | 结论 | 依据 | 重开条件 |
|---|---|---|---|---|
| D1 | 接口二是否实施 | **冻结** | §2.2 已被 `arfilter`/`excsat` 覆盖；§4.2 净收益方向不明 | §4.2 两条重开条件任一满足 |
| D2 | 接口一特征集 A/B | **选 B**（ADR/PRR/PCR + ratio/ns/pdop） | §5.1 覆盖 ratio 盲区；字段可直取；文献可比 | 消融显示 `ratio` 贡献压倒性 → 重新审视 |
| D3 | 接口三模型形态 | **v1 用 MLP**，1D-CNN 延后 | §5.3 时序上下文已由 `resp`/`resc`/`gf` 标量化 | 出现必须用残差序列才能表达的模式 |
| D4 | AI 代码放哪 | **新建 `rtklib-ai`**，core 只放 SPI | §3.1 避免 ONNX 污染 core | 无 |
| D5 | 注入方式 | **注入回调**：core 定义 `AiProvider` + `AiHooks`（`ThreadLocal`），`rtklib-ai` 实现并注入；参数在 `RtkConfig.ai`；**不设 `enable` 布尔量** | §3.2 外部注入需穿透 Processor 才能到 `RtkCore`；`createRtk` 重建会丢 `transient` 字段（既有 `traceCallback` 已在 PostPos 丢失）；`varerr` 入参是 record 且 `joint` 在 Processor 体系外；回调接口使依赖方向严格单向，避免 core 与 `rtklib-ai` 相互引用 | 需要跨线程池传播或进程级多租户强隔离时，改为纯实例字段注入 |
| D6 | 是否引入 Adam 等一阶优化器 | **不引入** | 不产出协方差（GNSS 必需）；`R` 已按方差加权，自适应分母破坏统计意义；与 §1.2 边界一致 | 引入需输出协方差的学习模块时重新评估 |
| D7 | 假固定阈值 | **0.20 m（已统一，v1.7）** | §8.1 口径统一表：0.05 m 低于一个 L1 波长且使 G-1/G-2 不可同时满足；0.1 m 与数据集口径分叉。标定只允许在 0.20 m 口径下向上收紧 | 标定出可复现的更严阈值且不破坏 G-1/G-2 可行性时 |
| D8 | 默认开启条件 | **必须过 §7.4 闸门 + ROADMAP 登记** | §1.4 政策约束 | 无 |
| D9 | `accept` 判定归谁 | **core 按 `AiOpts.fixValidatorMinConfidence` 判定；SPI 只输出 `confidence`** | §3.4 / §4.4.1 若 SPI 输出布尔，阈值判定出现两处，不一致即静默矛盾；且调阈值需重新训练 | impl 需要输出「硬拒绝」且不可被阈值覆盖时（当前无此需求） |
| D10 | IFLC 门禁放哪 | **core 侧统一门禁**：`opt.ionoopt == IONOOPT_IFLC` 时两接口均不调用（接口一按 `accept` 放行）；**不把 `ionoopt` 加进 context** | §4.3.7 门禁是配置级判定而非历元事实；放 impl 会变成两处独立实现，漏一处即在 IFLC 下用组合量套逐频点模型且不报错 | impl 需要按星座/频点做差异化门禁时（当前无此需求） |
| D11 | `rtklib-joint` 是否接入 AI | **不接入，且不纳入后续阶段范围** | §1.5.1：逐行语义 `idx = i*nf+frq` 中「卫星-频点」是单基线概念，多基线下无法直接映射；「方差是安全带」的作用域论证以单基线单滤波器为前提，共享状态下不成立（**本文档推论，§4.3.5 未涉及**）；接口一的作用对象被 joint 已有的跨基线交叉验证部分覆盖 | 出现可约束跨基线污染的状态隔离设计（每基线独立状态 + 可回滚），且多基线上下文可合成单行特征（§1.5.3） |
| D12 | `rtklib-adjust` 是否接入 AI | **不接入，且不纳入后续阶段范围** | §1.5.2：`allowFloat=false` 使接口一拒绝一次固定 = 该基线退出当历元平差集合，单基线的安全降级在多基线里变成结构损伤；接口三在 `relpos` 内按基线独立调用，会制造 `BaselineConsistencyChecker` 本要检测的异常；§7 三项指标在多基线语义下定义错位；`Policy` / `BaselineConsistencyChecker` / `ResidualWhiteness` 已覆盖同类收益 | 补出多基线评估协议，且新增**只读诊断评分**接口（不改状态/坐标/协方差），仅作 `BaseStationDiagnoser` 第 4 个证据源（§1.5.3） |
| D13 | 接口一可交付口径（预算-降幅成对档） | **改为成对档：B 档 ≤ 2 pp / ≥ 20%（层次 A 实测 4/7，满足 G-3）或 C 档 ≤ 5 pp / ≥ 15%（层次 A 实测 6/7，满足 G-3）；A 档 ≤ 1 pp / ≥ 30% 标记为「当前特征集下不可达」，保留不删除** | §8.2.2 可行性推导（数学互斥）+ §8.2.8 尾部形态（单调 0/7、最低 1% 档错误率 0%~25%）：1 pp 预算只能覆盖最低 1% 历元，30% 相对下降在该档**物理上不存在**；B/C 档在交付形态（层次 A）下实测 4/7 与 6/7，均满足 G-3 的「≥ 2 未见站」（层次 B 的 5/7、7/7 仅作站内上界，**不作交付证据**，§8.2.10）。**G-1 的 30%、G-2 的 1.0 pp、G-3 的「≥ 2 未见站」三个数字未改**，改的是成对组合的可交付档；判据自由度（未见站、单一全局阈值、配对 bootstrap 下界、不挑站、不用测试标签反选阈值）**一条未增** | 换特征集或扩数据后 A 档重新挑战；或出现「预算 ≤ 1 pp 且相对下降 ≥ 30%」的可复现标定（当前不存在） |
| D14 | 接口一剔除规则是否加几何退化门禁 | **加**：`pdop > 8.0` 的历元一律放行、不参与剔除；剩余历元按 `confidence` 升序剔除且总量 ≤ 预算。core `aiAcceptFix()` 在阈值比较**之前**返回 `true`；训练侧 `PDOP_ABSTAIN_MAX = 8.0` 与 `_accepted()` 在联合标定 / 单站报告 / 全局评估三处同语义 | §8.2.8：最低分档是「几何最差」而非「最可能错」——0–1% 档 `pdop` 显著高于整体、错误率 0%（高 DOP 历元难被固定，「固定且错」结构性缺席）；紧预算下不加门禁会把预算花在「高 DOP 但正确」的样本上。实测 2 pp / 20% 档 3/7 → 5/7，1 pp 档下界抬升但仍 0/7 | 数据扩展到「高 DOP 且高错误密度」场景后重标该常数；或门禁导致某站 G-4 恶化 |
| D15 | 后续方向：按环境分组 / 基站侧特征 / 海洋 vs 内地 | **按环境分组删除，改「按站标定」；基站侧特征暂缓，等长基线数据；海洋 vs 内地在本数据上做不了** | §8.2.9：按高程分组的交叉对照无稳定优势（1 pp 档 7 站中 1 优 / 2 劣 / 4 持平）；16 站几何跨度 1128 m × 1003 m、高程 850~1147 m，基线近似恒定 → 基站侧特征无变异可学；全部站同一小区域，无海洋/内地两类样本 | 出现跨区域（≥ 10 km 或跨气候带）且含不同基线长度的数据时，重开分组与基站侧特征 |
| D16 | 「按站标定」的层次：模型共享还是按站训练 | **选层次 A（模型共享 + 阈值分站）**；层次 B（按站训模型 + 按站标阈值）仅作「固定基础设施站」的可选增强，不作交付形态 | §6.4 + §8.2.10：两层次逐站对照，2 pp 档 4/7 vs 5/7、5 pp 档 6/7 vs 7/7，差距**只落在 `S4` 一站**（A −3% / B 67%），且 `848643` 反向为 A 更好（47% vs 35%）；层次 B 取消 G-3 的「未见站」语义、新站无模型、每站仅 2 天训练数据（过拟合 + 无法留站暴露）；1 pp / 30% 两层次同为 0/7，证明不可达性与模型粒度无关 | 站点被确认为固定基础设施（不新增站、每站 ≥ 4 天数据、报告明确标注形态为 B），三条同时成立时 |
| D17 | 接口三验收是否套用接口一的 G-1'~G-5' | **分两阶段**：第一阶段用 A1~A3（能用 / 无害 / 有作用）→ 允许显式注入；第二阶段用 §7.4.2 的 G-1'~G-5'（有净收益）→ 才允许默认开启。§7.4 的阈值与口径**一字不改** | §7.5：接口三语义是「改观测值、不剔除」，收益经「残差 → 定位 → 固定」三级传导，任一环节都可能被大气/多路径公共项吸收；在本数据（1.1 km × 1.0 km 山谷、16 站近似同址）上若要求第一阶段就出现定位改善，等于把第二阶段门槛提前，接口三将永远无法启用。故第一阶段只验契约（A1）、配对不恶化（A2）、改正确实起作用（A3） | 第二阶段（G-1'~G-5'）通过后，本节判据自动降级为回归测试，不再作为启用的前置门 |
| D17-A3 | A3「有效改正」的门限怎么定 | **双门限 + 解析 σ**：先取 `σ_dd = sqrt(Ri+Rj)`（`varerr` 原生值，`aiScale=1`）；守卫 `\|before\| ≥ 3σ_dd`；满足「相对 ≤ 0.7×before」或「绝对降幅 ≥ 3σ_dd」之一即计 1 次。**不用固定 cm 门限，也不用纯相对 30%** | §7.5.3：①纯相对 30% 在小残差上过松（2 cm→1.4 cm 仍属正常量级却计数）；②纯相对 30% 在大残差上过严（2 m→1.5 m 降 25%，实为显著改善却不计）；③固定 cm 门限不能随仰角/SNR 自适应，大仰角过松、低仰角过严。σ 取 `varerr` 而非固定 cm，因其已按仰角/SNR/基线长/钟漂给出正常残差量级（§4.3.4）；且必须用膨胀前的方差，否则模型可调大 `varianceScale` **自我放宽门限** | 出现「σ_dd 明显偏离实际残差分布」的站（即 `varerr` 标定失真）时，先修 `varerr` 而非改 A3 门限 |
| D18 | D17-A3 的 `R(0.7)` / `D(3σ)` 在 `bias_max` 钳位下不可达，怎么处理 | **放宽为 `R%(≤0.85)` 或 `Dσ(≥1.0σ_dd)`，取「或」**；`G0(3σ_dd)` 守卫与「≥10 行且 ≥3 星」门槛不变。**`bias_max` 不改**（仍 2.0 m） | §8.3：原判据联立后要求 `\|bias\| ≥ 0.9σ_dd`（R）与 `≥ 3σ_dd`（D），而契约恒有 `\|bias\| ≤ bias_max`；实测 `σ_dd` 中位 2.773 m ⇒ 需 2.495 m > 2.0 m，`reduction_reachable_rows = 0`，**可行集为空**——此时 A3 报「样本不足」是判据缺陷而非模型无效。新口径同一模型同一数据：有效改正 9,743 行 / 33 星（可达 12,417，命中 78.5%），A3 通过。放宽的是「单行改善幅度」门限，**不是样本量、不是卫星覆盖、不是零基线自检**，判据自由度未增 | ①`bias_max` 抬到 `≥ 3.0 m`（须先证不破坏 §4.3.4 单侧膨胀与 A2）后，`Dσ` 分支恢复为有效判据，届时重评是否回收至更严门限；②Java 侧双跑配对（A/C 组）落地后，用真实 `after` 复核离线代理口径的偏差 |
| D19 | 是否引入「激进模式」（`enableAggressiveAi`：允许 `varianceScale < 1.0`、放宽 `bias` 上限） | **默认不启用，但已实现为可开关的实验配置（v1.11）**：`scale` 下限可放开到 `aggressiveMinScale`（默认 `0.1`）；**`bias` 上限与 `varianceScaleMax` 不放宽**；生产默认仍为保守模式 | §4.6.3：①与 §4.3.4 强制规则 1（单侧膨胀）**契约级冲突**——允许 `scale < 1.0` 等于模型自称比解析误差模型更可信，无审计判据，安全带失效后直接指向假固定率上升；②本批数据（6 站 × 4 日、1.1 km × 1.0 km 山谷、静态同址，§8.2.9）**无城市峡谷/动态样本**，收益无从验证；③新增布尔字段须重验 I-1（未注入逐位一致，§1.3）；④接口三第一阶段 A1~A3 全部结论均在保守口径下取得（§8.3）。**落地为实验配置的理由**：需要一条可复现的对照通道来量化「放开 `scale` 下限」的真实代价（§4.6.5 单站探针：固定率 −2.4 pp、错固 −42），否则该问题永远停留在纸面推断 | ①拿到城市峡谷/动态载体的数据；②给出「模型可比 `varerr` 更可信」的**可审计判据**（如分场景校准曲线 + 覆盖率下界），并证明 `scale < 1.0` 不破坏 §4.3.4 的安全带论证；③`minScale = 0.1` 下的法方程条件数实测；④全量 576 run 上出现「固定率不降、错固下降」的净改善 |

---

## 附：修订记录

### v1.10 → v1.11 修订（2026-11-12）

| 项 | v1.10 | v1.11 |
|---|---|---|
| §0（新增） | 无术语基线，正文「方案 A/B」同时指接口三训练目标与接口一特征选择，易混 | 新增 §0「术语基线：接口三的两种交付方案」：方案 A（标签监督，交付）/ 方案 B（端到端坐标 Loss，不交付）的定义、脚本、产物、结果、处置与边界，并约定「无限定词时默认指接口三训练目标」 |
| §4.5.1 字段表 | 5 个字段，**不含任何 `enable` 布尔量** | 新增 `enableAggressiveAi`（默认 `false`）与 `aggressiveMinScale`（默认 `0.1`）；说明二者默认值即保守模式、全默认仍满足 I-1，并界定 `enableAggressiveAi` 控制的是钳位口径而非 AI 开关 |
| §4.5.2 校验 | 三条钳位规则 | 追加 `aggressiveMinScale` 的两条规则（非法回退 `0.1`、`> 1.0` 钳到 `1.0`） |
| §4.5.3 `copyFrom` | 5 行 | 追加 `enableAggressiveAi` / `aggressiveMinScale` 两行 |
| §4.6 | 「激进模式**未实施**，代码不落地」，`bias` 上限拟放宽到 `10.0 m` | 改为「**已实现为实验配置，默认关闭**」：补实现规格表、代码落点、刻意不做的边界（`bias` 上限与 `varianceScaleMax` 不放宽）；§4.6.3 三条理由保留；新增 §4.6.5 单站单日实测记录 |
| 训练/契约侧 | 输出头固定 `scale = 1 + (S−1)·sigmoid`，契约校验区间固定 `[1.0, scale_max]` | `build_model(..., scale_min=1.0)`、`train.py --scale-min`、`check_contract(..., scale_min=1.0)`；默认值与旧行为逐位一致 |
| 测试入口 | 无激进模式参数 | `DualInterfacePairingTest` 新增 `-Dpairing.aggressive` / `-Dpairing.min-scale`，并打印实际钳位区间 |
| 决策记录 | D19「暂不实施」 | D19 改为「默认不启用，已实现为可开关实验配置」，重开条件④改为「全量 576 run 出现净改善」 |

**本次未改动**：§4.3.4 的钳位契约数值（`varianceScaleMax=4.0`、`obsCorrectorMaxBias=10.0`）、§7.4 的 G-1'~G-5'、§7.5 的 A1~A3、接口一全部判据、17 维特征集、两层切分规则、方案 A/B 的模型与 Java 侧代码。

**当前真实进度（一句话）**：接口一保持「未注入即关闭」；接口三方案 A 已过第一阶段 A1~A3（允许显式注入、默认关）；方案 B 判定不交付、仅作实验 provider；激进模式已落地为默认关闭的实验配置，单站单日探针显示「固定率 −2.4 pp / 错固 −42」，尚无全量证据。

### v1.9 → v1.10 修订（2026-11-11）

| 项 | v1.9 | v1.10 |
|---|---|---|
| 文档合并 | `docs/AI补充.md`（接口三端到端训练与激进模式方案，2026-11-11 前撰写）与本文档并行存在，其中 §3 的实现规格（`softplus` 无上界、`bias` 硬钳 `[-2,2]`、Loss 为裸 3D MSE）与最终代码相反 | **并入后删除该文件**：有效内容重写为 §4.6（激进模式，D19）与 §8.4（方案 B 执行结论）；失效内容（原 §1 的 10.6% 旧数、原 §3 的作废实现规格、原 §4 的「2 个测试站 / 保守+激进两组」计划）**不保留**，仅在 §8.4 顶部标注「已作废，不得照此实现」 |
| §4.6（新增） | — | 「输出钳位的两种模式」：保守（现行，`scale ≥ 1.0` 硬钳 + `bias` 超限置 0）vs 激进（未实施，允许 `scale < 1.0` 带 `minScale = 0.1`、`bias` 上限放宽）；含 I-1 约束与三条未实施理由 |
| §8.4（新增） | — | 「方案 B：端到端坐标 Loss 对照实验」：预注册判据（整体 135,198 历元 H/V 双赢）、实现要点（可导单步 WLS + `H` detach）、两轮迭代结果（v1 142.5/280.2、v2 149.0/300.8 vs 生产 D 146.2/265.6 → 不交付）、客观可用性摘要（有效性/敏感性/稳定性/指标背离/假优势）、处置（实验 provider）、四条升级前置条件 |
| 状态行 / 接口表 | 接口三「模型未训练、默认不注入」 | 接口三「**模型已训练（方案 A，D 组口径），A1/A2/A3 全部通过**（§8.3），默认不注入；方案 B 对照已执行并判定不交付，仅作实验 provider（§8.4）」 |
| 决策记录 | D1~D18 | 新增 **D19（激进模式暂不实施，设计保留于 §4.6）**，带四条重开条件 |

**本次未改动**：§4.3.4 的钳位契约（保守模式即现行实现）、§4.5.1 字段表（**不新增 `enableAggressiveAi`**）、§7.4 的 G-1'~G-5'、§7.5 的 A1~A3、接口一全部判据、17 维特征集、两层切分规则。

**当前真实进度（一句话）**：接口一保持「未注入即关闭」；接口三方案 A 已过第一阶段 A1~A3（允许显式注入、默认关）；方案 B（端到端）经两轮迭代未达预注册判据，判定不交付、仅作实验 provider 保留；激进模式仅存设计、未落地。

### v1.8 → v1.9 修订（2026-11-11）

| 项 | v1.8 | v1.9 |
|---|---|---|
| §7.5.3 第三步 | `R: \|after\| ≤ 0.7\|before\|` 或 `D: 降幅 ≥ 3σ_dd` | `R%: \|after\| ≤ 0.85\|before\|` 或 `Dσ: 降幅 ≥ 1.0σ_dd`；补「为什么放宽」的不可达性证明（`bias_max` 钳位 vs `0.9σ_dd` 需求）与 `Dσ` 在本批仍不生效的说明 |
| §7.5.3 第四步 | — | 计数门槛（≥10 行、≥3 星）、配对口径、零基线恒为 0 **一字未改** |
| §8.3（新增） | — | 接口三首轮训练与 A1/A2/A3 复核：数据/切分/特征口径、默认超参结果、A2 残差代理 10.6%、A3 新旧口径对照表（0 行 → 9,743 行 / 33 星）、三条限定条件、Java 侧 A2 配对为下一步 |
| 决策记录 | D1~D17、D17-A3 | 新增 **D18（A3 门限因钳位不可达而放宽，`bias_max` 不改）**，带两条重开条件 |
| `training/obs_corrector/train.py` | `A3_REDUCTION_RATIO = 0.7`，`R ∧ D` 联立 | `A3_DROP_SIGMA = 1.0` / `A3_DROP_RATIO = 0.15`，`R% ∨ Dσ`；新增 `--eval-only`（复用已有 ONNX/norm.json 只跑评估，不重训）；metrics 新增 `drop_sigma_rows` / `drop_ratio_rows` / `drop_sigma_threshold_p50` / `criteria` / `mode` |

**本次未改动**：`bias_max`（仍 2.0 m）、`scale_max`（仍 4.0 m）、17 维特征集、两层切分规则、A1/A2 判据、§7.4 的 G-1'~G-5'、接口一全部判据。A3 只改「单行改善幅度」的两个门限数字，**样本量门槛、卫星覆盖门槛、零基线自检、`σ_dd` 取膨胀前方差**四条约束一条未动。

### v1.7 → v1.8 修订（2026-11-10）

| 项 | v1.7 | v1.8 |
|---|---|---|
| 状态行 | 「接口一可交付口径改为成对档（B 档 5/7、C 档 7/7）」 | 标注形态：**层次 A 4/7、6/7（满足 G-3）；层次 B 5/7、7/7 仅作站内上界**；接口一训练形态定为层次 A；接口三改为两阶段验收 |
| §6.4（新增） | — | 「接口一的训练与标定：两个层次」——层次 A（模型共享 + 阈值分站）与层次 B（按站训模型 + 按站标阈值）的定义、样本量、优劣势、实测对照、决策（D16）、阈值来源不得混用（全局单阈值 vs 分站分位） |
| §7.4.4 | 实测列单列，未标形态；「≥ 5/7」「≥ 7/7」直接写成「达标」 | 实测列拆为**层次 A / 层次 B 双列**，并区分「G-3 原判据（≥ 2/7）」与「加强口径（5/7、7/7）」；新增口径边界第 4 条：**必须标注形态与阈值来源**，混标即视为口径造假 |
| §7.5（新增） | — | 「接口三第一阶段验收」：A1 功能流程正常（6 项契约检查，不看效果）、A2 没有明显变差（C 组 vs A 组同历元配对：固定率 ≤ 1.0 pp 下降、错误固定率不上升、RMS 不恶化、`ns`/`vsat` 不显著减少）、A3 有出现辅助了固定（**双门限 + 解析 σ**：`σ_dd = sqrt(Ri+Rj)`（膨胀前）、守卫 `\|before\| ≥ 3σ_dd`、满足「相对 ≤ 0.7×before」或「绝对降幅 ≥ 3σ_dd」即计 1 次，须 ≥ 10 历元且 ≥ 3 卫星，零基线须恒为 0，不要求固定率提升）；明确**不改 §7.4** |
| §8.2.8 | 未标形态，1 pp 逐站下界数字与当前探针输出不一致 | 补「形态标注：本节为层次 B」；逐站明细改为与探针日志逐位一致的点估计/下界双列；补「门禁不是单调增益」的反例（`S5` 点估计 11% → 4%） |
| §8.2.9 第 3 条 | 「B 档 5/7 或 C 档 7/7」 | 「B 档层次 A 4/7、C 档层次 A 6/7，均满足 G-3」 |
| §8.2.10（新增） | — | 层次 A / 层次 B 对照实测：总表（1 pp 0/7 vs 0/7、2 pp 4/7 vs 5/7、5 pp 6/7 vs 7/7）+ 三档逐站明细；结论：差距集中在 `S4`（B 大幅领先）与 `848643`（A 更好），1 pp 档不可达性与模型粒度无关 |
| 决策记录 | D1~D15 | 新增 D16（选层次 A）、D17（接口三两阶段验收）、D17-A3（A3 门限用双门限 + 解析 σ） |
| D13 | 5/7、7/7 未标形态 | 改为「层次 A 4/7、6/7 满足 G-3」，并注明层次 B 数字不作交付证据 |
| `rtklib-core` `RtkCore.aiAcceptFix()` | v1.7 已加 `pdop > 8.0` 前置放行 | 常量命名与注释对齐训练侧 `PDOP_ABSTAIN_MAX`，javadoc 同步说明门禁在阈值比较之前 |

### v1.6 → v1.7 修订（2026-11-10）

| 项 | v1.6 | v1.7 |
|---|---|---|
| 状态行 / 接口表 | 阶段 4「G-3 未通过（留一站 1/7、留多站 0/7）」 | 阶段 4 改为「原判据组合（1 pp / 30%）**当前特征集下不可达**，可交付口径改为成对档（B 档 5/7、C 档 7/7）」 |
| §7.4.1 | 只给 G-1~G-5 与三条统计口径 | 追加 v1.7 否定结论：1 pp / 30% **物理不可达**，附 §8.2.2 数学互斥与 §8.2.8 尾部 0/7 证据；**阈值数字一字未改** |
| §7.4.3（新增） | — | 剔除规则定死为两段式：`pdop > 8.0` 放行 → 剩余按 `confidence` 升序剔除且 ≤ 预算；四处（联合标定 / 单站报告 / 全局评估 / core 运行时）必须逐字对齐 |
| §7.4.4（新增） | — | 可交付口径改为成对档 A/B/C，逐站统计；附三条口径边界（非单次配置、只在 0.20 m 口径、A 档标记不删除） |
| §8.2.8（新增） | — | 尾部形态证据：单调 0/7、0–1% 档错误率 0%~25% 且 `pdop` 显著偏高、门禁量化收益表（2 pp 档 3/7 → 5/7，1 pp 档仍 0/7） |
| §8.2.9（新增） | — | 处置四件事（落地门禁 / 标记不可达 / 改口径 / 方向修正）与「不做的三件事」的 v1.7 说明 |
| §9 风险 | 无剔除规则相关行 | 新增「剔除规则口径漂移」一行 |
| `training/fix_validator/train.py` | 无 pdop 门禁；G-1 目标硬编码 0.30 | 新增 `PDOP_ABSTAIN_MAX = 8.0` / `PDOP_FEATURE_INDEX` / `_accepted()`，在联合标定、单站报告、全局评估三处生效；新增 `--rel-target` 支持动态目标；输出新增 `pdop_abstain_max` / `n_pdop_abstained` |
| `rtklib-core` `RtkCore.aiAcceptFix()` | 仅按 `confidence >= 阈值` 判定 | 阈值比较**之前**对 `pdop > 8.0` 直接返回 `true`（D14），与训练侧同语义 |
| 决策记录 | D1–D12 | 新增 **D13（可交付口径成对档）**、**D14（几何退化门禁）**、**D15（方向修正：按站标定 / 基站侧特征暂缓 / 海洋 vs 内地不可做）**，各带重开条件 |

**本次未改动**：§1–§6 的接口契约、特征定义（6 维，D2）、评估协议主体（§7.1–§7.3）。**G-1 的 30%、G-2 的 1.0 pp、G-3 的「≥ 2 未见站」三个数字一字未改**——v1.7 落地的是「零成本剔除规则改进 + 对不可达组合的正式标记 + 成对可交付档」，未新增任何判据自由度（未见站、单一全局阈值、配对 bootstrap 下界、不挑站、不用测试标签反选阈值）。

**当前真实进度（一句话）**：pdop 前置门禁已落地（本轮唯一可立即验证的收益，2 pp / 20% 档 3/7 → 5/7），1 pp / 30% 正式标记为当前特征集下不可达，可交付口径改为 2 pp / 20%（5/7）或 5 pp / 15%（7/7）；接口一仍保持「未注入即关闭」，不进入默认开启流程。

### v1.5 → v1.6 修订（2026-11-10）

| 项 | v1.5 | v1.6 |
|---|---|---|
| 状态行 / 接口表 | 阶段 3 只记整日线（12 run / 16152 行）；阶段 4「G-3 未通过（留一站 1/7）」 | 阶段 3 补记小时线（16 站 / 4 日 / 113962 行）；阶段 4 改为「留一站 1/7、**留多站 0/7**」 |
| §8.1 第二条采集线 | 「已采到 6 站 × 3 日 × 24 小时，**尚未组装**，`--truth auto` 未实现」 | **已闭环**：`--truth` 改为可选，新增 `--reference cluster` + `--max-day-offset`（跨日中心一致性校验），给出完整组装结果与真值口径交叉校验（p50 1.6 cm / max 4.9 cm） |
| §8.1 | 无跨日校验实测证据 | 新增拒用证据：站 X 两日偏离 0.865 m / 0.194 m 被拒用；不设校验时错误固定从 7907 涨到 8380 行（+473 行污染） |
| §8.1 阈值口径 | 「`assemble.py` 默认 0.05 m vs D7 0.1 m」两处不一致 | 更正为三处并给出归属：整日线那批 Parquet 用 **0.05 m** 组装（3001/18.58% 即该口径）；`assemble.py` 现默认 **0.20 m**（可行性推导：0.05 m 档错误固定占 34%，G-1 的 30% 与 G-2 的 1 pp 不可同时满足）；D7 初始值 0.1 m。小时线结果（7907/6.94%）为 0.20 m 口径 |
| §8.2.5（新增） | — | 留多站 LOMO 探针：7 组「2 标定 + 2 测试」配置，**G-3 通过 0/7、0/14 站**；扫遍全部可行阈值仍 0/7，排除「选点不行」 |
| §8.2.6 / §8.2.7 | 根因定位（三项排除）/ 处置 | 根因定位扩为**五项排除**（新增「阈值选点规则退化」「标签口径可疑」两项排除，均基于 v1.6 新证据）；处置改为「两块资产已闭环 + **接受现状**」 |
| `training/fix_validator/assemble.py` | 强制 `--truth`；真值只用站内池化聚类 | `--truth` 可选；新增 `_consistent_days()` / `_station_references()`（逐日稳健中心 + 跨日中位裁决 + 逐日偏离拒用 + 单日站整体拒用），报告新增 `day_offset_m` / `dropped_days` / `offset_from_truth_m` |
| `training/fix_validator/train.py` | `select_threshold_multi()` 以「最坏站下界最大」单键排序 | 排序键改 `(达标站数, 最坏站下界, 平均下界, 固定率下降)`，消除「全站不可行时选中不干预阈值」的退化 |
| §9 风险 | 「自动真值自证」为纯预案 | 标注**已实现并实测生效**（附拒用与污染行数证据）；新增「阈值标定规则退化」一行 |
| 站点标识 | §8.1/§8.2 直接写真实设备号 | 全部改为代号：7 个可判定站 **S1~S7**、被跨日校验拒用的站 **X**；对应关系不入库。代号避开 A/B/C/D，因这四个字母已用作评估组名 |

**本次未改动**：§1–§7 的任何契约内容。**判据口径（G-1 ≥ 30% 下界、G-2 ≤ 1.0 pp、G-3 ≥ 2 站）一字未改**——v1.6 只补齐数据侧缺口、修正实现缺陷，未放宽任何阈值，未挑选测试站，未用测试站标签反选阈值。

**当前真实进度（一句话）**：两块「零新数据资产」已闭环且标签口径经独立真值文件校验到 cm 级，接口一在 §7.4 判据下**确定不通过**（留一站 1/7、留多站 0/7），据此**接受现状**——保持「未注入即关闭」，交付管线 + 结论 + ONNX 产物，不进入默认开启流程。

### v1.4 → v1.5 修订（2026-11-10）

| 项 | v1.4 | v1.5 |
|---|---|---|
| 状态行 / 接口表 | 阶段 4「未开始」 | 阶段 4「训练链路已完成并跑通，**G-3 未通过**」，指向 §8.2 |
| §8 阶段表 第 4 行 | 未开始 | **训练链路已完成并跑通，判据未通过** |
| §8.2（新增） | — | 记录阶段 4 的判据口径修正、可行性前置、阈值标定口径、留一站探针结果与根因定位 |
| `training/common/metrics.py` | G-1 下界用两组独立 Wilson 区间相减 | 改为**配对 epoch bootstrap**（§7.4 第 3 条），新增 `paired_relative_drop()` / `paired_relative_drop_ci()` / `MIN_WRONG_EVENTS` |
| `training/common/dataset.py` | 验证站从全部站随机抽 | 新增 `val_candidates` 参数，验证站也限定在 G-1 可判定站内 |
| `training/fix_validator/train.py` | 单站标定阈值；早停集含独立验证站 | 新增 `select_threshold_multi()`（多站联合标定）、`--n-val-stations`（默认 2）、`_exclude_stations()`（早停集剔除独立验证站）、`_g1_eligible()` / `_g3_summary()`（可行性前置与 G-3 自动判定）、阈值候选改分位点并集 |

**本次未改动**：§1–§7 的任何契约内容。**判据口径（G-1 ≥ 30% 下界、G-2 ≤ 1.0 pp、G-3 ≥ 2 站）一字未改**——本次只修正实现的统计口径与标定流程，未放宽任何阈值，也未挑选测试站。

**当前真实进度（一句话）**：训练链路可复现可审计，但在 §7.4 判据下**不通过**——留一站探针下 7 个可判定站中仅 1 站满足 G-1+G-2（需 ≥ 2 站），根因是 §5.1 的 6 维特征跨站判别力不足（站内作弊训练也只能摸到 ~30% 下界），见 §8.2.4 / §8.2.5。

### v1.3 → v1.4 修订（2026-11-10）

| 项 | v1.3 | v1.4 |
|---|---|---|
| 状态行 / 接口表 | 阶段 0–2 已完成；接口一训练与评估未开始；**接口三「未开始」** | 阶段 0–3 已完成；接口三改为「**代码已落地**（2026-11-09），模型未训练、默认不注入」——原表述早于代码，属文档滞后 |
| §8 阶段表 | 阶段 3–5 全部「未开始」 | 阶段 3「已完成（2026-11-10）」并给数据规模；阶段 5「代码已完成」 |
| §8.1（新增） | — | 记录接口一训练集（12 run / 6 站 / 2 天 / 16152 行 / 3001 错误固定）与**两项数据体检发现**（`wrong_fix_rate` 18.6% 偏离典型区间；`err_p50` 3–7 cm 疑似真值口径错配），并指出阈值口径两处不一致（`assemble.py` 0.05 m vs D7 0.1 m） |
| §8.1 第二条采集线 | — | 补记 `FixValidatorHourlyCollectTest`（按小时切片、现场私有数据）：已采 6 站 × 3 日 × 24 小时到 `data/fix-validator/collect-hourly/`，因无可用真值且 `--truth auto` 未实现，**尚未组装** |
| §9 风险 | 无真值口径条目 | 新增「真值口径错配」与「自动真值自证」两行，后者给出 `--truth auto` 的跨日聚类 + 逐日一致性校验约束 |

**本次未改动**：§2–§7 的任何契约内容（接口契约、特征定义、评估协议、阶段依赖关系）。v1.4 不引入代码改动。

**当前真实进度（一句话）**：管线全通、模型未训——仓库内 `.onnx` 仅有 `rtklib-ai/src/test/resources/models/` 下三个 dummy（`dummy_fix_validator` / `dummy_obs_corrector` / `dummy_zero_corrector`），只证明链路跑通，不承诺任何精度。

### v1.2 → v1.3 修订（2026-11-08）

| 项 | v1.2 | v1.3 |
|---|---|---|
| 适用范围 | 仅正文提及 joint 边界（§4.3.5）、未涉 adjust | 新增 §1.5「适用范围之外」，把 joint 与 adjust 的**不接入判定**、依据、重开条件固化为可复查条目 |
| 决策记录 | D1–D10 | 新增 **D11（joint 不接入）**、**D12（adjust 不接入）**，各带重开条件 |
| 状态行 | — | 版本号改为 v1.3，标注本次仅新增 §1.5 与 D11/D12，**接口契约、特征定义、评估协议、阶段计划全部不变** |

**本次未改动**：§3–§9 的任何契约内容。v1.3 不引入代码改动。

**阶段状态补充（记录澄清，不改契约）**：接口一训练管线的**工程实现**已建成——Java 侧采集器（`rtklib-ai` `collect` 包，多 run 累积、`AiHooks` 注入与还原）、CSV→Parquet 组装（trace 对齐 + 已知坐标打标 + 两层切分）、训练入口、ONNX 导出与契约校验，均有单元测试覆盖。**真实数据采集与模型训练尚未开始**，故原「阶段 3 为下一个待执行阶段」应读作「阶段 3 的工具已就绪，待真实数据」。

**PPP 侧方向**：PPP / PPP-AR / PPP-RTK 的 AI 接入评估（超出本设计 §1.2 范围）另见 [PPP_AI_ASSESSMENT.md](PPP_AI_ASSESSMENT.md)（技术评估 v1.0；**结论为 PPP 体系本阶段不接入 AI**，含三条链路的代码现状核对、候选逐条否决与重开条件，见该文档 §8 / §9）。

### v1.1 → v1.2 修订（2026-11-06）

| 项 | v1.1 | v1.2 |
|---|---|---|
| 注入方式 | `AiRegistry` 静态注册表 + 实例字段 | **`AiProvider` 注入回调 + `AiHooks` 注入点**（§3.2）。依赖方向严格单向 `rtklib-ai → rtklib-core`，core 对 `rtklib-ai` 零引用；阶段 1 验收新增 `dependency:tree` 断言 |
| 接口三推理位置 | 写在 `relpos` 循环之前 | 改为**迭代首轮 `i == 0`、`zdres` 之后、首次 `ddres` 之前**（§3.3）。理由：`zdres` 在循环体内，循环前 `azel` 是上一历元值 |
| 接口三索引 | 按卫星号 `sat[i] - 1` | 改为**按 `sat[]` 下标 `idx = i * nf + frq`**（§4.3.3）。理由：参考星由 `ddres` 现场择优，同一历元 3 次调用可能不同 |
| 方差膨胀顺序 | 未明确 | 明确**必须在半周 0.01 补偿之后施加**（§4.3.4）。理由：补偿先于膨胀，否则 0.01 被过度放大 |
| 接口一特征 | 仅给字段名 | 补齐**精确到行的定义**：`vflg` 无「有效位」、`pdop` 必须局部重算、`prr`/`pcr` 必须取自 `v[]` 而非 `resp`/`resc`（§5.1） |
| 接口三特征 | 含 `mw` / `phw` / 「变化率」表述 | **删除 `mw`/`phw`**（RTK 链路从不写，恒 0）；`gf` 改为「当历元组合值」且注明依赖 `opt.thresslip`；仰角改取 `wb.azel`（§5.3） |
| 评估切分 | 按天切分，或按站切分 | **两层切分：第一层必须按站，站内再按天**（§7.3）。理由：特征是站址指纹，仅按天切分仍会泄漏 |
| `FixQualityContext` | 含 `nfix`，与特征表不一致 | **删除 `nfix`**（§4.4.4），并新增「字段 ↔ 特征」对应表；`nf`/`epoch`/`nCodeRows`/`nPhaseRows` 明确标注为「不直接入特征」及各自用途 |
| IFLC 门禁 | 分散表述为「接口三禁用、接口一仅在非 IFLC 启用」，未定门禁位置 | 新增 §4.3.7：**core 侧统一门禁（D10）**，两接口均不调用；接口一按 `accept` 放行；`ionoopt` **不进 context** |
| `obsCorrectorMaxBias` | 字段表行内含绝对值竖线，破坏表格且语义不完整 | 改用 `Math.abs(biasMeters)` 表述，补齐「超限置 0 而非钳到边界」「保留 `varianceScale`」「`NaN`/`Inf` 置 0」三条规则（§4.3.4 / §4.5.1） |
| 短窗 `dt` | 只写 `1.5 * dt`，未给来源与兜底 | 定死来源为 `ctx.dt()`（`relpos` L221），`dt <= 0` 或非有限时**清空全部窗口**（§5.3） |
| G-1 显著性 | 仅「≥ 30%」点估计 | 补 Wilson 置信下界、MDE 前置（A 组事件数 < 100 不判定）、配对 McNemar 三条（§7.4） |
| 目录布局 | `AiRegistry.java`、`AiAdvisorImpl` 门面 | `AiProvider.java` / `AiHooks.java`；`OnnxAiProvider` 仅组装，不设门面（§6.1） |

### v1.0 → v1.1 修订（2026-11-06）

| 项 | v1.0 | v1.1 |
|---|---|---|
| 注入方式 | `Rtk` 上单个 `transient` 可空字段，需层层注入 | `AiRegistry`（`ThreadLocal`）+ 实例字段覆盖（§3.2）；理由：外部注入需穿透 Processor、`createRtk` 会丢 transient 字段、`varerr` 入参是 record。**v1.2 已把 `AiRegistry` 替换为 `AiProvider` 回调** |
| 开关 | `AiOpts.enableFixValidator` 布尔量 | **删除**，由 `null` 兼任（§3.2） |
| 降级层数 | 三层（含「未启用」） | 两层：未注入 / 运行时失败（§3.4） |
| 接口三施加点 | `varerr` 两处调用点（含推理） | 每历元批量推理 + `varerr` 只读查表（§3.3 / §4.3.4）；理由：`varerr` 每历元 76–116 次调用 |
| 风险表 | 无注入点相关风险 | 新增「注入点测试污染」「热路径推理」（§9） |