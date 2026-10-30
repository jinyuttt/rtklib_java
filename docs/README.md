# RTKLIB-Java 文档地图

本文件是 `docs/` 的唯一入口，用于回答三个问题：**某件事该看哪份文档**、**这份文档现在还算不算数**、**哪些地方文档已经和代码不一致**。

- 盘点基准：2026-10-28（依据各文件实际内容与最后修改时间，非猜测）
- 本文只做导航与状态标注，**不改写任何文档正文**

## 状态图例

| 标记 | 含义 |
|------|------|
| ✅ 活跃 | 内容与当前代码/结论一致，可持续引用 |
| 🟡 待复核 | 结论可能已被后续修复覆盖，引用前需对照 ALGORITHM_FIX_LOG |
| 📋 阶段性完成 | 计划/清单类文档，任务已执行完毕，仅作历史依据 |
| 🗄 归档 | 已移入 `archive/`，仅作历史追溯，**不应作为当前实现依据** |

## 推荐阅读路径

| 你是谁 | 按顺序读 |
|--------|----------|
| 新接手项目 | 本文件 → [ROADMAP.md](ROADMAP.md)（模块现状）→ [RTKLIB_JAVA_TECHNICAL_REFERENCE.md](RTKLIB_JAVA_TECHNICAL_REFERENCE.md)（架构）→ [USAGE_GUIDE.md](USAGE_GUIDE.md)（跑通） |
| 要改算法 | [ALGORITHM_FIX_LOG.md](ALGORITHM_FIX_LOG.md)（必读，定版期强制）→ [RTKLIB_Differences.md](RTKLIB_Differences.md) → 对应模块优化文档 |
| 要评估优化项 | [CORE_OPTIMIZATION_MATRIX.md](CORE_OPTIMIZATION_MATRIX.md)（总入口）→ RTK/SPP/PPP 各自优化文档 + 测试结果 |
| 要写测试 | [TEST_DEVELOPMENT_GUIDE.md](TEST_DEVELOPMENT_GUIDE.md) → [DATA_GUIDE.md](DATA_GUIDE.md) |

---

## 一、代码变更与修复档案

| 文档 | 定位 | 状态 | 最后更新 |
|------|------|------|----------|
| [CHANGELOG.md](CHANGELOG.md) | 面向用户的版本级变更摘要（当前至 3.3.0） | ✅ 活跃 | 2026-10-28 |
| [ALGORITHM_FIX_LOG.md](ALGORITHM_FIX_LOG.md) | **定版核心档案**：每次改变数值行为的修改（C 依据/根因/验证），现存 FIX-ALG-001~029 | ✅ 活跃 | 2026-10-23 |
| [RTKLIB_Differences.md](RTKLIB_Differences.md) | 与 RTKLIB 2.5.0 C 版的长期差异声明（设计选择，非 Bug） | ✅ 活跃 | 2026-10-21 |

## 二、使用与测试

| 文档 | 定位 | 状态 | 最后更新 |
|------|------|------|----------|
| [USAGE_GUIDE.md](USAGE_GUIDE.md) | RTCM 解码、RINEX 转换、RTK/SPP/PPP 调用示例与输出字段语义 | ✅ 活跃 | 2026-10-08 |
| [SSR_ACCESS_PLAYBOOK.md](SSR_ACCESS_PLAYBOOK.md) | **SSR 接入手册**：CAS/北航/Galileo/QZSS 四条线、凭据管理、两行改动、验收清单与已知坑 | ✅ 活跃 | 2026-10-23 |
| [DATA_GUIDE.md](DATA_GUIDE.md) | 测试类总览、公共测试数据清单、测试用例详情、大文件仓库 | ✅ 活跃 | 2026-10-23 |
| [TEST_DEVELOPMENT_GUIDE.md](TEST_DEVELOPMENT_GUIDE.md) | 测试开发标准流程与常见模式 | ✅ 活跃 | 2026-10-12 |

## 三、架构与技术参考

| 文档 | 定位 | 状态 | 最后更新 |
|------|------|------|----------|
| [RTKLIB_JAVA_TECHNICAL_REFERENCE.md](RTKLIB_JAVA_TECHNICAL_REFERENCE.md) | 状态向量布局、关键常量、矩阵约定、函数调用链、配置模板 | 🟡 待复核（含 "已完成的修复清单/待完善项"，可能滞后于 FIX-ALG-020~029） | 2026-10-14 |
| [MATRIX_DIMENSION_REFERENCE.md](MATRIX_DIMENSION_REFERENCE.md) | H 矩阵构建、filter 压缩、ddcov、varerr 的维度与存储约定 | ✅ 活跃 | 2026-07-13 |
| [ADJUST_TECHNICAL_REFERENCE.md](ADJUST_TECHNICAL_REFERENCE.md) | rtklib-adjust 多基线间接平差：数学模型、协方差构造、理论局限、模块边界（SBS+平差） | ✅ 活跃 | 2026-10-27 |
| [ADJUST_USAGE.md](ADJUST_USAGE.md) | rtklib-adjust 使用说明、加权与诊断配置 | ✅ 活跃 | 2026-10-27 |
| [JOINT_RTK_DESIGN.md](JOINT_RTK_DESIGN.md) | rtklib-joint 多基线联合 RTK（MBS）：与 core 同层的独立引擎、状态向量、大气共享 | ✅ 活跃（核心已实现并实测：单/双基线 FIX 91.5%；AR 通道缺陷已由 FIX-ALG-042 修复；§2.1.1 大气维度修正与 §13 基站位移监测仍为未实施设计项，见文档 §0） | 2026-10-29 |
| [JOINT_VS_ADJUST_ANALYSIS.md](JOINT_VS_ADJUST_ANALYSIS.md) | joint（观测层联合）vs adjust（结果层平差）对比：融合层级、故障隔离、FIX 后精度等价性、选型结论 | ✅ 活跃（v1.2 定稿） | 2026-10-27 |
| [VRS_MODULE_DESIGN.md](VRS_MODULE_DESIGN.md) | rtklib-vrs 虚拟参考站生成：实现状态、单基站改正算法、RTCM3 编码器（含 MSM 信号裁剪与锁相指示）、四层验证策略、实测结果、已踩过的坑与已知限制 | ✅ 活跃 | 2026-10-27 |
| [ORBIT_MODULE_REFERENCE.md](ORBIT_MODULE_REFERENCE.md) | TLE 解析、SGP4/SDP4、六根数转换 | ✅ 活跃 | 2026-10-08 |
| [OTL_MODULE_REFERENCE.md](OTL_MODULE_REFERENCE.md) | 海潮负荷：分潮、BLQ 提取、位移序列、命令行 | ✅ 活跃 | 2026-09-24 |
| [PRODUCT_MODULE_REFERENCE.md](PRODUCT_MODULE_REFERENCE.md) | 精密产品下载：产品类型、镜像优先级、缓存、协议 | ✅ 活跃 | 2026-09-24 |

**外部标准原文**（`docs/reference/`，只读引用，**不得改写原文**）：

| 文件 | 标准 | 用途 | 引用方 |
|------|------|------|--------|
| [igs_ssr_v1.pdf](reference/igs_ssr_v1.pdf) | IGS SSR v1.00（2020-10-05），660 KB / 49 页 | 4076 消息与 IM201 VTEC 球谐的字段定义、系数个数公式、穿刺点与合成算法 | [NETWORK_VRS_DESIGN.md §3.4](NETWORK_VRS_DESIGN.md) |

## 四、优化体系（核心主题）

### 4.1 总入口

| 文档 | 定位 | 状态 | 最后更新 |
|------|------|------|----------|
| [CORE_OPTIMIZATION_MATRIX.md](CORE_OPTIMIZATION_MATRIX.md) | **优化项总矩阵**：定位模式速查、功能总矩阵、静态/动态场景分类、推荐组合、代码默认值与文档偏差表 | ✅ 活跃 | 2026-10-15 |
| [PPP_Optimization_Systems.md](PPP_Optimization_Systems.md) | **PPP 体系指南（必读）**：浮点 PPP 地基 / PPP-AR / PPP-RTK 三分支、测试纪律、场景 Preset | ✅ 活跃 | 2026-10-21 |

### 4.2 RTK

| 文档 | 定位 | 状态 | 最后更新 |
|------|------|------|----------|
| [RTK_Extra_Optimizations.md](RTK_Extra_Optimizations.md) | RTK 独有优化项原理与开关（自适应 Q、锚固、IGGIII、SNR 中值等 7 项） | ✅ 活跃 | 2026-10-16 |

### 4.3 SPP

| 文档 | 定位 | 状态 | 最后更新 |
|------|------|------|----------|
| [SPP_Extra_Optimizations.md](SPP_Extra_Optimizations.md) | SPP 优化项原理与开关（EKF、M 估计、零速、多普勒+SNR 加权等） | ✅ 活跃 | 2026-10-15 |

### 4.4 PPP / PPP-RTK

| 文档 | 定位 | 状态 | 最后更新 |
|------|------|------|----------|
| [PPP_Extra_Optimizations.md](PPP_Extra_Optimizations.md) | PPP 扩展优化项原理 + 调用架构 + §7 逐项对照测试 + 开关一览 | ✅ 活跃 | 2026-10-23 |

## 五、路线图与设计稿

| 文档 | 定位 | 状态 | 最后更新 |
|------|------|------|----------|
| [ROADMAP.md](ROADMAP.md) | 模块状态总览 + 未完成事项（按需激活/冻结/WONTFIX）+ 暂缓与恢复条件；历史梯队记录见 [archive/ROADMAP_v3.3.0.md](archive/ROADMAP_v3.3.0.md) | ✅ 活跃 | 2026-10-28 |
| [PROJECT_FINAL_SUMMARY.md](PROJECT_FINAL_SUMMARY.md) | **终期技术摘要**：一句话定位 + 精度实测 + 001~029 闭环索引 + HAS/IMU/CLAS 扩展路线 | ✅ 活跃 | 2026-10-23 |
| [TraceLog_V2_Design.md](TraceLog_V2_Design.md) | 追踪日志 V2 设计与实现（取代 archive 中的 V1 设计） | ✅ 活跃 | 2026-10-08 |
| [RTKCONFIG_REFACTOR_DESIGN.md](RTKCONFIG_REFACTOR_DESIGN.md) | RtkConfig 组合模式重构：487 行扁平类按模式拆分子配置（Common/Rtk/Spp/Ppp）、向后兼容迁移路径 | 🟡 设计稿（v1.0 定稿，未执行；`RtkConfig` 仍为扁平类） | 2026-10-26 |
| [SOL_AGGREGATOR_DESIGN.md](SOL_AGGREGATOR_DESIGN.md) | SolAggregator 历元聚合工具：鲁棒加权 / 卡尔曼 RTS 两模式、与 solstatic 及 PosFileRtsSmoother 的分工、协方差与降级策略 | 🟡 设计稿（v1.2 待评审；`aggregate` 包已实现，未接入主流程、无测试） | 2026-10-27 |

---

## 六、archive/ 归档（17 份，仅历史追溯）

| 文档 | 原主题 | 被谁取代 |
|------|--------|----------|
| [ROADMAP_v3.3.0.md](archive/ROADMAP_v3.3.0.md) | 路线图 v1.0~v2.1 全部梯队执行记录与决策依据（v3.3.0 归档） | [ROADMAP.md](ROADMAP.md)（重启版，只保留未完成事项） |
| [PPP_RTK_Development_Plan.md](archive/PPP_RTK_Development_Plan.md) | PPP-RTK 开发计划（Phase 1~4 已实现） | ALGORITHM_FIX_LOG + PPP_Optimization_Systems |
| [PPP-AR-C-vs-Java-Comparison.md](archive/PPP-AR-C-vs-Java-Comparison.md) | PPP-AR C/Java 逐项对比（差异分析过程） | ALGORITHM_FIX_LOG FIX-ALG-022/023/024 |
| [RTK_Debug_Record.md](archive/RTK_Debug_Record.md) | RTK 早期 Bug #1~#6 调试记录 | RTKLIB_Differences + FIX-ALG-004~009 |
| [SPP_Debug_Record.md](archive/SPP_Debug_Record.md) | SPP 早期 Bug #1~#5（MSM 索引、Sagnac 等） | ALGORITHM_FIX_LOG FIX-ALG-010 |
| [RINEX_Debug_Record.md](archive/RINEX_Debug_Record.md) | RINEX 格式解析调试（含 3.5 节 RTK 致命坑） | FIX-ALG-003/004 |
| [RTK_Flow.md](archive/RTK_Flow.md) | rtkpos/relpos 流程图与子函数详解 | RTKLIB_JAVA_TECHNICAL_REFERENCE |
| [SPP_Processing_Flow.md](archive/SPP_Processing_Flow.md) | SPP 处理流程图与处理器说明 | USAGE_GUIDE + RTKLIB_JAVA_TECHNICAL_REFERENCE |
| [RTK_TraceLog_Design.md](archive/RTK_TraceLog_Design.md) | 追踪日志 V1 设计 | TraceLog_V2_Design.md |
| [RTK_Parameter_Sensitivity.md](archive/RTK_Parameter_Sensitivity.md) | RTK 参数敏感性排序实验 | CORE_OPTIMIZATION_MATRIX |
| [RTKLIB_JAVA_DEBUG_GUIDE.md](archive/RTKLIB_JAVA_DEBUG_GUIDE.md) | 早期调试日志格式与排查清单 | TEST_DEVELOPMENT_GUIDE |
| [COMPARISON_REPORT.md](archive/COMPARISON_REPORT.md) | Java vs C 早期定位精度对比报告 | 历史实测，结论早于 FIX-ALG-006~010 |
| [PPP_Test_Plan.md](archive/PPP_Test_Plan.md) | PPP 优化项逐项对照测试计划（v1.0，2026-10-15） | PPP_Extra_Optimizations §7 + CORE_OPTIMIZATION_MATRIX |
| [PPPRTK_ALGO_2026288_DOWNLOAD.md](archive/PPPRTK_ALGO_2026288_DOWNLOAD.md) | ALGO 2026 DOY288 数据下载清单（任务已完成） | ALGORITHM_FIX_LOG FIX-ALG-027/028（GBM 2026-288 验证 7/7） |
| [RTK_OPTIMIZATION_TEST_RESULTS.md](archive/RTK_OPTIMIZATION_TEST_RESULTS.md) | 城市峡谷动态 RTK 12 变体实测（2026-10-15） | 历史实测：早于 FIX-ALG-006/007/008，结论不代表当前代码 |
| [URBAN_CANYON_SPP_TEST_RESULTS.md](archive/URBAN_CANYON_SPP_TEST_RESULTS.md) | 城市峡谷 + 手机 SPP 实测（2026-10-12） | 历史实测：早于 FIX-ALG-010（SPP 多系统恢复） |
| [gnss-adaptive-design.md](archive/gnss-adaptive-design.md) | GNSS 引擎自适应切换设计稿 v2.0（未落地） | 无替代；解冻后如启动自适应引擎再复活 |

---

## 七、已知文档问题清单（2026-10-23 盘点，同日已修正）

> 本节保留为审查追溯记录：P0/P1 问题已在 2026-10-23 的文档整理中全部处理，P2 维持现状。

### P0 — 会误导实现

**P0-1 [PPP_Extra_Optimizations.md](PPP_Extra_Optimizations.md) §2 与 §7.4 自相矛盾**

- 原问题：§2.3 仍写 `mf = 1 + a / (1 + b / (1 + c / sinel))`（FIX-ALG-029 已证伪，缺连续分数分母）；§2.4 仍是旧 5 段 bw 表；行号引用 `PppCore.java:846` 失效
- 处置：✅ §2.3 已按 VMF3 连续分数形式重写并补 ZHD/ZWD 公式；§2.4 改为官方分段（≤15° 0.00108 → 75° 0.00225）；行号更正为 L973-975
- 复核（2026-10-23）：同文件 §3.3／§4.2~4.3／§5.3~5.6／§11.2 共 10 处行号已随代码演进失效（`pppos`、`pppRes`、`pppResEx`、`udstateEx`、`getWlFcb` 等），已逐一更正为当前声明行；`tropoDelayPrec()` 并非实际方法名，更正为 `modelTrop()`；§5.3 补上优先执行的 `enableMultiFreqAR` 三频分支

**P0-2 同文件 L58 与 L70 表述冲突**

- 原问题：L58 称"无需外部气象数据或网格文件"，L70 又列"可选网格"
- 处置：✅ 改为两级来源（官方网格优先 / 内置简化先验兜底），并标注兜底路径 U 向劣化约 +50%

### P1 — 结构/一致性

| 编号 | 问题 | 处置 |
|------|------|------|
| P1-1 | ALGORITHM_FIX_LOG 标题层级混用（001~018 用 `###`，020~029 用 `##`） | ✅ 统一为 `##`，仅 027 勘误小节保留 `###` |
| P1-2 | 编号顺序非递增，FIX-ALG-019 无独立章节 | ✅ 文首加编号说明；019 为汇总表行记录，已在说明中标注 |
| P1-3 | PPP_Optimization_Systems 存在两个 `## 7` | ✅ EST 章节改为 §8（8.1~8.3） |
| P1-4 | PPP_Extra 版本基线过期（v2.3.0） | ✅ 更新为 v3.1 / 2026-10-23 / 基于 v3.1.0 |
| P1-5 | TECHNICAL_REFERENCE §8/§9 状态滞后 | ✅ §8 加注：FIX-ALG-020 之后统一指向 ALGORITHM_FIX_LOG |
| P1-6 | DATA_GUIDE 未收录新增测试 | ✅ 补 `Gpt3GridReaderTest`、`SsrDecodeSmokeTest`、`Wuh20MsmSppTest` |

### P2 — 主题重叠（属设计如此，但需明确入口关系）

- **RTK**：[RTK_Extra_Optimizations.md](RTK_Extra_Optimizations.md)（原理）↔ [CORE_OPTIMIZATION_MATRIX.md](CORE_OPTIMIZATION_MATRIX.md)（总矩阵）。原实测报告已归档（结论早于 FIX-ALG-006~008）
- **SPP**：[SPP_Extra_Optimizations.md](SPP_Extra_Optimizations.md)（原理）↔ [CORE_OPTIMIZATION_MATRIX.md](CORE_OPTIMIZATION_MATRIX.md)（总矩阵）。原实测报告已归档（早于 FIX-ALG-010）
- **PPP 三件套**：[PPP_Optimization_Systems.md](PPP_Optimization_Systems.md)（体系）／[PPP_Extra_Optimizations.md](PPP_Extra_Optimizations.md)（原理+实测）／[PROJECT_FINAL_SUMMARY.md](PROJECT_FINAL_SUMMARY.md)（汇总），入口关系清晰；原测试计划与下载清单已归档

---

## 八、维护约定

1. **数值行为变更**只记 [ALGORITHM_FIX_LOG.md](ALGORITHM_FIX_LOG.md)，不在 CHANGELOG 里展开根因；版本级摘要仍写 CHANGELOG
2. 任何结论被新修复推翻时，**在原文标注"已被 FIX-ALG-NNN 取代"并保留旧结论**，不要静默改写（便于追溯）
3. 文档中引用代码行号必须带 `file:///` 链接且指明函数名；纯行号会在重构后失效（P0-1 即此类问题）
4. 新文档入库时同步更新本文件的状态表；阶段性计划完成后标 📋，被取代后移入 `archive/` 并更新对照表