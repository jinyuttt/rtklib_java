# RTKLIB-Java 开发路线图

> 本文档只描述**当前模块状态与未完成事项**，不记录已完成项的执行过程。
> 历史梯队执行记录与决策依据见 [archive/ROADMAP_v3.3.0.md](archive/ROADMAP_v3.3.0.md)。

**当前版本**: 3.3.0（2026-10-28）。**3.1.0 起仅接受 bugfix，不新增算法特性。**

---

## 0. 模块状态总览

| 模块 | 定位 | 状态 |
|------|------|------|
| rtklib-core | 核心定位算法（SPP/RTK/PPP/PPP-RTK）+ 自适应引擎 | 四大定位模式全实现；CascadeAR / PartialAR / GPT3+VMF3 / IERS2010 / RAIM FDE 已接齐；自适应引擎 14 类已实现。缺口：完好性监测/保护级/ARAIM、PPP-RTK 区域大气约束（WONTFIX，见 §1.3） |
| rtklib-product | 精密产品下载 | IGS/MGEX 11 类产品全支持，多镜像源回退；可按需扩展其他分析中心 |
| rtklib-stream | NTRIP/串口 | NTRIP 客户端 + SSL/TLS + NTRIP Caster + GGA 解析 + 串口 + 统一监听器；TCP 客户端不在项目设计范围内（见 §1.3） |
| rtklib-adjust | 多基线间接平差 | 核心流程 + 基站诊断 + P0~P3 优化；可按需扩展多时段累积平差 |
| rtklib-vrs | 单基站虚拟参考站 | **临时激活**（GNSS 领域按需启用，不纳入梯队规划）：真实双站 239 历元，VRS FIX 76.6% vs 原始基站 77.0% |
| rtklib-joint | 多基线联合 RTK | **临时激活**（同上）：k 条基线共享同一 Kalman 滤波器的 Rover 坐标；真实 2 基站 + 1 测站，双基线 FIX 91.5% vs 单基线 91.2% |
| rtklib-networkvrs | 多基站网络 VRS | 档 1（IDW 插值 + 相对主站改正）已完成并经真实三站数据验证；档 2（网解 + 误差分解 + FKP）已实现，BRDC 下降级为「宽巷固定 + IF 浮点」，窄巷固定待 SSR/精密星历 |
| rtklib-otl | 海潮负荷 BLQ | Fortran 对比验证通过；成熟稳定辅助模块 |
| rtklib-research | 前沿研究（FGO/IACE/预积分） | FGO 框架 + IMU 预积分 + 场景自适应已齐；独立沙盒，不参与核心依赖链 |
| rtklib-test | 测试/演示 | SPP/RTK 城市峡谷优化对比测试 + NTRIP 客户端实时演示 |

> 缺口统计**不含项目设计边界外的功能**（TCP 客户端、接收机原始协议等，见 §1.3）。

---

## 1. 未完成事项

> 只列**尚未闭环**的项。编号沿用历史文档中的既有 ID（UC-*、3.5G、4A~4G 等），避免重排导致旧引用失效。

### 1.1 按需激活（无阻塞，等场景驱动）

| 模块 | 事项 | 启动条件 |
|------|------|----------|
| rtklib-vrs / rtklib-joint | 真实场景调优 | GNSS 领域出现明确需求 |
| rtklib-adjust | 多时段累积平差 | 出现跨时段基线网平差需求 |
| rtklib-product | 扩展其他分析中心产品 | 出现对应产品需求 |
| rtklib-research | PPP-FGO、学习型后端等 | 沙盒内自驱演进，不设里程碑 |

### 1.2 冻结（代码基础已就绪，缺验收判据）

| 事项 | 已具备 | 冻结原因 | 恢复条件 |
|------|--------|----------|----------|
| 梯队 2.5 城市峡谷 UC-2~UC-9 | UC-1 新息卡方检验已落地（`enableSppInnovChisq`，默认开启） | v3.1.0 封存时冻结，其余项未实施；UC-4/UC-5 零速门限未采纳（默认仍为 0.5 m/s / 1 历元） | 见 §2 |

### 1.3 明确不做（WONTFIX）

| 事项 | 说明 |
|------|------|
| 完好性 4A~4D | RAIM 保护级 / 完好性通道 / ARAIM 解分离 / 固定解完好性。RAIM FDE 已移植，但无车规/航空/监测场景驱动 |
| PPP-RTK 区域大气约束 4E~4G | CLAS STEC 多项式、对流层 SSR 验证、伪观测约束引擎；缺真实 SSR 区域产品 |
| 梯队 3 CI 回归（3.5G） | 缺入库的精选测试数据 |
| Kalman 矩阵层重构 3A+3B-2 | JMH 实测 SimpleMatrix 非瓶颈（k≥150 时快 10x），已放弃 |
| RtkConfig null 检查消除 3.5B | 无语义异常，保留 null 检查 |
| TCP 客户端、接收机原始协议 | **功能定义不符合本项目定位，项目设计不做**（非缺口、非暂缓，无恢复条件） |

---

## 2. 暂缓原则与恢复条件

### 暂缓原则

以下情况不启动新功能开发：

- 外部条件未满足（缺数据 / 缺产品 / 缺场景）
- 前置验证不通过
- 无明确上游应用场景驱动
- 算法验证成本高且短期无数据支撑

### 恢复条件

| 事项 | 恢复条件 |
|------|----------|
| 3.5G CI 回归 | 精选测试数据入库（1h 静态 + 1h 车载 RINEX） |
| 4E / 4F | 真实 SSR 数据可用（LocalCorr 结构已就绪） |
| 4G | 4E + 4F 完成 + 区域 STEC/ZTD 产品可用 |
| 完好性 4A~4D | ① 出现需要 PL + HAL/VAL 输出的真实场景；② 可获取 SSR STEC/ZTD 实时产品或具备区域监测站网；③ 核心模块回归验证通过 |
| 3A+3B-2 | Profiling 显示 SimpleMatrix 分配占 >5% 总耗时（当前 1 Hz 下 106 MB/s 可接受） |

---

## 3. 文档索引

| 内容 | 文档 |
|------|------|
| 历史路线图（v1.0~v2.1 全部执行记录、JMH 基准、城市峡谷实测） | [archive/ROADMAP_v3.3.0.md](archive/ROADMAP_v3.3.0.md) |
| 版本变更、破坏性 API、测试清单 | [CHANGELOG.md](CHANGELOG.md) |
| 算法修复记录与实测数据 | [ALGORITHM_FIX_LOG.md](ALGORITHM_FIX_LOG.md) |
| SPP / RTK / PPP 优化细节与开关 | [SPP_Extra_Optimizations.md](SPP_Extra_Optimizations.md)、[RTK_Extra_Optimizations.md](RTK_Extra_Optimizations.md)、[PPP_Extra_Optimizations.md](PPP_Extra_Optimizations.md) |
| SSR 数据源接入手册与接入现状 | [SSR_ACCESS_PLAYBOOK.md](SSR_ACCESS_PLAYBOOK.md) |
| 网络 VRS 设计（档位划分与冻结说明） | [NETWORK_VRS_DESIGN.md](NETWORK_VRS_DESIGN.md) |
| 自适应引擎设计 | [gnss-adaptive-design-v3.md](gnss-adaptive-design-v3.md) |
| 使用与测试 | [USAGE_GUIDE.md](USAGE_GUIDE.md)、[TEST_DEVELOPMENT_GUIDE.md](TEST_DEVELOPMENT_GUIDE.md) |

---

## 4. 文档维护约定

- **路线图只写未来**：已完成项在发布时收敛为一行状态，随后移出本文档。
- **单一信息源**：决策依据 → 设计文档；已完成 → CHANGELOG.md；未完成 → 本文档，三者不重复。
- **不写实现细节**：代码片段、文件行号、JMH 数值、实现树不属于路线图，应落在对应设计文档。
- **编号稳定**：沿用既有 ID（UC-*、3.5G、4A~4G 等），不因章节重排而重新编号。
- **归档触发**：已关闭内容占比过半，或版本封存/大版本切换时，归档至 `archive/ROADMAP_v<version>.md` 并重启本文档。