# RTKLIB-Java 开发路线图

> 本文档定义项目后续开发优先级、依赖关系和暂缓项。基础功能已完善，路线图集中在研究深度扩展和工程质量加固。

---

## 0. 各模块当前状态总览

| 模块 | 定位 | 完成度 | 状态说明 |
|------|------|--------|----------|
| rtklib-core | 核心定位算法（SPP/RTK/PPP/PPP-RTK） | **95%** | 四大定位模式全实现，高级优化已接齐（CascadeAR/PartialAR/GPT3+VMF3/IERS2010等），主要缺口：完好性监测、PPP-RTK区域大气约束 |
| rtklib-product | 精密产品下载 | **90%** | IGS/MGEX 11类产品全支持，多镜像源回退，可按需扩展其他中心 |
| rtklib-stream | NTRIP/串口 | **85%** | NTRIP客户端+SSL/TLS+串口+统一监听器；缺：TCP客户端（无生产驱动） |
| rtklib-adjust | 多基线间接平差 | **85%** | 核心流程+基站诊断+P0~P3优化；可按需扩展多时段累积平差 |
| rtklib-otl | 海潮负荷BLQ | **95%** | Fortran对比验证通过；属成熟稳定辅助模块 |
| rtklib-research | 前沿研究（FGO/IACE/预积分） | **70%** | FGO框架+IMU预积分+场景自适应已齐；缺：完好性保护级、PPP-FGO |
| rtklib-test | 测试/演示 | **—** | 真实数据测试脚本+NtripClient实时演示 |

> 95% / 90% / 85% / 70% 为相对项目自身定位的估计值，**不含 RTKLIB C 版中明确不实现的功能**（TCP服务端、NTRIP Caster、接收机原始协议等）。

---

## 1. 路线图阶段概览

按优先级和依赖关系分为五个阶段。

### 1.1 阶段汇总

| Phase | 状态 | 交付物 |
|-------|------|--------|
| **1** 基础能力建设 | ✅ 完成 | SPP/RTK/PPP/PPP-RTK + 产品/流/轨道/OTL |
| **2** 高级优化与独立模块 | 🟡 基本完成 | CascadeAR/PartialAR/PPP-AR/FGO框架/间接平差 |
| **3** 测试加固 + 工程质量 | 🟢 部分启动 | 真实数据回归 + C版差异消除 + 性能基准（场景验证为🤝社区贡献项） |
| **4** 完好性 + 区域约束 | 🟠 暂缓 | RAIM FDE/保护级 PL/CLAS多项式/约束引擎 |
| **5** 长期研究 | 🔴 暂缓 | ARAIM/固定解保护级/PP-FGO/IMU紧耦合 |

### 1.2 阶段依赖关系

```
Phase 1 ──→ Phase 2 ──→ Phase 3
                             │
               ┌─────────────┼──────────────┐
               ▼             ▼              ▼
         Phase 4A/B     Phase 4E/F       Phase 4G/H
         (完好性基础)   (CLAS路径打通)    (伪观测约束引擎)
               │             │              │
               └──────┬──────┴──────────────┘
                      ▼
                  Phase 5 长期研究

注: rtklib-research 独立演进，不参与上述依赖链
```

---

## 2. Phase 1 — 基础能力建设 ✅

### 2.1 完成清单

- RTKLIB C 版核心算法移植：SPP / RTK / PPP / PPP-RTK 四种定位模式
- LAMBDA 模糊度解算 + Fix-and-Hold
- RTCM3 解码（MT1001-1004 / MSM / SSR MT1057-1068）
- RINEX 3.x 读写
- Kalman 滤波框架
- 坐标变换 / 时间系统 / 电离层 / 对流层模型
- 精密产品下载（rtklib-product）
- NTRIP 客户端（rtklib-stream）
- 轨道模块（TLE/SGP4/SDP4）
- 海潮负荷 OTL（rtklib-otl）

### 2.2 分模块回顾

| 模块/能力 | 说明 |
|-----------|------|
| SPP 单点定位 | Kalman 框架 + pntpos 重写 |
| RTK 相对定位 | rtkpos 核心 + LAMBDA Fix-and-Hold |
| PPP 精密单点定位 | PPP 动态/静态/精密产品读取 |
| PPP-RTK 基础（SSR 改正） | SSR 解码 + 状态空间表示 |
| RTCM3 解码（MT1001-1068） | MSM + SSR + 观测值/星历 |
| RINEX 3.x 读写 | 观测/导航/气象/头文件 |
| Kalman + 矩阵运算 + 时间系统 | 核心基础设施 |
| 星历 + 坐标变换 + 电离层对流层 | GPS/GAL/BDS/QZS 广播星历 |
| 精密产品下载（rtklib-product） | 11 类产品 + 多镜像源 |
| NTRIP 客户端（rtklib-stream） | 含 SSL/TLS + 自动重连 |
| 轨道模块（TLE/SGP4/SDP4） | Vallado 14 用例验证 |
| 海潮负荷 OTL（rtklib-otl） | Fortran 对比验证 |
| Trace 日志 + SolData 扩展 | 分散在各模块 |

---

## 3. Phase 2 — 高级优化与独立模块 🟡

> **状态**: 基本完成，剩余项非阻塞

### 3.1 分模块回顾

| 模块/能力 | 说明 |
|-----------|------|
| CascadeAR 逐级模糊度固定 | EWL→WL→NL 约束链 |
| Partial AR 部分模糊度固定 | GREAT-PVT 多因子加权 |
| ResidualEdit + Bootstrapping | PRIDE 残差编辑 |
| BDS Wanninger 码偏差 + 参数噪声 | GEO/IGSO/MEO 码偏差 |
| PPP-AR + Fix-and-Hold + PartialAR | 整周固定 + 固定后约束 |
| GPT3 + VMF3 + IERS2010 | 对流层先验 + 潮汐改正 |
| Compact SSR / CLAS / HAS 解码 | CSSRlib 交叉验证 |
| SPP EKF + 抗差 + 零速约束 | |
| 多基线间接平差 | 高斯-马尔可夫 + Baarda |
| FGO 框架 + IMU 预积分 | 滑动窗口 + 边缘化 + SwitchVariable |
| IACE 聚类 + 场景自适应 | FE-GUT 数据集验证 |

### 3.2 RTK 高级模糊度固定优化 ✅

| 优化项 | 开关 | 来源 | 完成度 |
|--------|------|------|--------|
| 逐级模糊度固定 (EWL→WL→NL) | `enableCascadeAR` | GREAT-PVT | ✅ |
| 精细化残差编辑与周跳检测 | `enableResidualEdit` | PRIDE | ✅ |
| 部分模糊度固定 (Partial AR) | `enablePartialAR` | GREAT-PVT | ✅ |
| Bootstrapping 成功率联合判据 | `enableBootstrapping` | GREAT-PVT | ✅ |
| BDS 码偏差改正 (Wanninger) | `enableBdsCodeBias` | PRIDE | ✅ |

### 3.3 PPP/PPP-RTK 高级优化 ✅

| 优化项 | 开关 | 完成度 |
|--------|------|--------|
| PPP 模糊度固定 (PPP-AR) | `enablePppAR` | ✅ |
| PPP Fix-and-Hold | `enablePppArFixHold` | ✅ |
| PPP 部分模糊度固定 | `enablePppPartialAR` | ✅ |
| GPT3 + VMF3 对流层 | `enableGpt3Vmf3` | ✅ |
| IERS2010 潮汐改正 | `enableIers2010` | ✅ |
| PPP-RTK 基础（SSR改正） | `enablePppRtk` | ✅ |
| Compact SSR / CLAS 解码 | — | ✅ |
| Galileo HAS SSR 解码 | — | ✅ |
| SPP EKF + 抗差 + 零速约束 | `enableSppEkf/SppRobust/SppZeroVel` | ✅ |

### 3.4 RTKLIB C 差异消除（🟡 可做但非阻塞）

| 差异 | 说明 | 依赖 |
|------|------|------|
| **pos1-posopt5 RAIM FDE** | `pntpos.c raim_fde()` 未移植，SPP/PPP 层无故障检测排除 | C 版 fde.c + pntpos.c 参考 |
| **ssat_t / 完好性辅助** | `ssat.ura` 已部分迁入 `Ssr.ura`，但 `ssat.h` 其他字段未移植 | 无 |
| **SPP RAIM 保护级** | C 版 RAIM 只做 FDE 不输出 PL，Java 版也没有 | Phase 4 完好性管线 |

---

## 4. Phase 3 — 测试加固与工程质量 🟢

> **状态**: 部分启动（v2.2.6 已扩展公共数据测试覆盖）
>
> 核心模块已全部跑通，需扩大测试覆盖面并加固工程质量。

### 4.1 场景验证（🤝 社区贡献项）

> 以下任务的核心依赖是**外部测试数据**（车载RTK、长基线、城市峡谷等），非作者个人可完成。欢迎社区贡献数据或测试结果。

| 任务 | 需要的数据 | 贡献方式 |
|------|-----------|----------|
| 动态车载 RTK 验证 | 高速动态 base+rover 对（>50km/h） | 上传数据至 `test-data/` 或提交 Issue |
| 长基线 RTK (>10km) | 长基线 base+rover | 同上 |
| 城市峡谷/深城市 RTK | 高遮挡 base+rover | 同上 |
| 多星座多频 PPP 验证 | 三频 BDS/GAL 数据 + IGS 参考解 | 同上 |
| 长时段静态连续运行 | 24h/72h RINEX 数据 | 同上 |
| PPP-RTK 实时流验证 | IGS SSR 流 + CLAS L6 流 | 同上 |
| 边缘场景回归 | 低仰角/高遮挡/单系统/单频/电离层活跃 | 同上 |

### 4.2 工程质量（作者可推进）

| 任务 | 依赖 | 说明 |
|------|------|------|
| 关键算法单元测试补充 | 无 | Kalman 矩阵维度、SSAT/URA 解码、SSR 消息完整性 |
| CI 真实数据回归测试 | 测试数据入库 | 当前仅运行单元测试，需加入 RINEX 回归 |
| 性能基准 | 无 | PPP 单历元耗时、SSR 解码吞吐率、文件解析速度 |
| C 版差异消除（RAIM FDE + ssat_t） | C 版 fde.c 参考 | 见 §3.4 |
| SSR 对流层直接改正接入 | `CompactSsrDecoder.getTrop()` 已有 | `PppRtkCore` 的 `TROPOPT_SSR` 分支调用 `getTrop()` 直接改正，跳过 ZTD 估计；超出 C 版的增强 |

### 4.3 rtklib-research 定位说明

rtklib-research 是**独立的研究沙盒模块**，与 rtklib-core 无代码级耦合，定位为前沿算法的实验场所（FGO/IACE/预积分/场景自适应等）。其价值在于提供独立验证途径，成熟后可按需收录到核心模块。**不要求与 rtklib-core 做数据桥接**，两者各自独立演进。

---

## 5. Phase 4 — 完好性监测 & PPP-RTK 区域约束 🟠

> **状态**: 暂缓
>
> 深度研究方向，算法验证成本高，暂无上游场景驱动。以下为技术储备阶段可做的事：整理公式、收集参考代码、设计数据结构。

### 5.1 完好性监测（RAIM → ARAIM → 保护级）

#### 现状

| 能力 | 状态 | 代码位置 | 完成度 |
|------|------|----------|--------|
| C 版 RAIM FDE（基础） | ✅ 在 Urban-RTKLIB `fde.c` / `pntpos.c` | `reference-projects/` | 100%（C代码） |
| Java RAIM FDE | ❌ 未移植 | — | 0% |
| 保护级 PL / HAL / VAL | ❌ 无 | — | 0% |
| ARAIM（多系统解分离） | ❌ 无 | — | 0% |
| 固定解完好性风险 | ❌ 无 | — | 0% |

#### 开发路径

| 子阶段 | 依赖 | 交付物 |
|--------|------|--------|
| **4A** 基础 RAIM FDE 移植 | C 版 fde.c + pntpos.c | `RaimFde.java`：完备性检测 + 最大残差排除 + 重定位验证；配置 `prcopt.raimopt`；Sol 扩展 `raimStat/raimExclSat` |
| **4B** 保护级 PL | 4A | `ProtectionLevel.java`：HPL/VPL 计算；输入位置协方差+观测几何+卫星集合；输出 `sol.hpl/sol.vpl` + HAL/VAL 告警 |
| **4C** ARAIM 解分离 | 4B | 故障假设子集枚举 + 各假设条件解/保护级 + 加权融合 + 固定解保护级 |
| **4D** 固定解完好性增强 | 4C | 固定解偏差后验估计 + 固定/浮点一致性检验 + 基于 Ratio/Bootstrapping 的固定解 PL |

```
4A 实现细节:
├── 新建包 org.rtklib.java.integrity
├── RaimFde.java — 完备性检测 + 最大残差排除 + 重定位验证
├── 在 PntPos / RtkCore / PppCore 的 EKF 后接入 pre-fit 一致性检验
├── 新增配置 prcopt.raimopt（0=off, 1=检测, 2=检测+排除）
└── Sol 输出扩展：sol.raimStat / sol.raimExclSat

4B 实现细节:
├── ProtectionLevel.java — HPL/VPL 计算
├── 输入：位置协方差 + 观测几何 + 已用卫星集合
├── 输出：sol.hpl / sol.vpl + HAL/VAL 告警
└── 公式：PL = K × sqrt(diag(P))，K 基于完好性风险假设

4C 实现细节:
├── 故障假设子集枚举（多故障场景）
├── 各假设条件解 + 条件保护级
├── 加权融合（各假设先验概率）
└── 固定解保护级（固定解风险 > 浮点解，需额外方差膨胀）

4D 实现细节:
├── 固定解偏差后验估计
├── 固定解 vs 浮点解一致性检验
└── 基于 Ratio/Bootstrapping 成功率的固定解 PL 输出
```

#### 参考资源

- Urban-RTKLIB `src/fde.c`（413 行，RTK 层面 NIS/残差 FDE）
- Urban-RTKLIB `src/pntpos.c raim_fde()`（SPP 层面 RAIM）
- RTK-EVC `src/rtklib/pntpos.cpp`（C++ RAIM）
- Galileo HAS ICD（完好性信息定义 SIS-A/SIS-M）
- rtklib-research `SwitchWrapperFactor` / `SwitchPriorFactor`（故障隔离基础设施，可复用）

### 5.2 PPP-RTK 区域大气约束

#### 现状

| 能力 | 状态 | 代码位置 | 完成度 |
|------|------|----------|--------|
| SSR STEC 直接改正（色散偏差 → STEC） | ✅ | `ppprtk/SsrIono.java:63-129` | ~80% |
| SSR STEC → 残差直接改正（IONOOPT_SSR） | ✅ | `ppprtk/PppRtkCore.java:613-625` | ~75% |
| IONEX VTEC → STEC fallback | ✅ | `ppprtk/SsrIono.java:218-259` | ~60% |
| Compact SSR / CLAS L6 解码 | ✅ | `cssr/CompactSsrDecoder.java` | ~85% |
| **LocalCorr 数据结构（STEC/对流层多项式）** | ✅ 结构就绪 | `cssr/LocalCorr.java:27-38` | 100% |
| CLAS STEC 多项式插值路径 | ❌ 未打通 | — | ~20% |
| SSR 对流层直接改正 | ⚠️ 有代码未接入 | `CompactSsrDecoder.getTrop()` 已实现多项式插值+网格残差，但无调用方；`PppRtkCore` 的 `TROPOPT_SSR` 仍走 `udtrop()` 估计 ZTD（与 C 版一致）；接入后可减少 ZTD 状态量、加速收敛，属于超出 C 版的增强 |
| **STEC 外部产品 → 伪观测值约束滤波** | ❌ | — | 0% |
| **ZTD 外部产品 → 伪观测值约束滤波** | ❌ | — | 0% |

#### 关键差距：SSR 直接改正 ≠ 区域约束

```
当前实现（直接改正）：
  SSR STEC 可用 → dion = C/f² × STEC → 作为已知量从残差减掉
  无 SSR       → 走估计路径（udiono 初始化斜距电离层估计）

论文目标（区域约束）：
  区域 STEC/ZTD 产品 → 作为伪观测值约束进入 Kalman 滤波
  → 约束状态量漂移
  → 收敛时间东方向改善 85%、高程改善 69%

两者关键区别：
  直接改正 = 给一个值，不让滤波管
  区域约束 = 给一个先验 + 方差，让滤波融合
```

#### 开发路径

| 子阶段 | 依赖 | 交付物 |
|--------|------|--------|
| **4E** CLAS 多项式路径打通 | LocalCorr 结构 | `SsrIono.stecModel()` 新增 LocalCorr 分支；插值公式 `STEC = ci[0]+ci[1]×dlat+ci[2]×dlon+ci[3]×dlat×dlon+dstec`；优先级链 SSR dispBias → LocalCorr → IONEX → 广播 |
| **4F** 对流层 SSR 改正验证 | 4E + Phase 3 SSR对流层接入 | 验证 `getTrop()` 直接改正 vs 估计 ZTD 的收敛差异；确认 H 矩阵 ZTD 偏导数置零后滤波稳定 |
| **4G** 伪观测值约束引擎 | 4E + 4F | `ConstraintEngine.java`；STEC/ZTD 伪观测方程约束状态量；距离衰减权重 `w(dist)=1/(1+(dist/d0)²)`；配置 `prcopt.constrainstec/constrainztd` |
| **4H** 收敛加速验证 | 4G | 对比：无约束 vs 直接改正 vs 伪观测值约束；指标：收敛时间/稳态精度/固定率；验证论文数据 |

```
4E 实现细节:
├── SsrIono.stecModel() 新增 LocalCorr 分支
├── 插值公式: STEC = ci[0] + ci[1]×dlat + ci[2]×dlon + ci[3]×dlat×dlon + dstec
├── 质量控制: LocalCorr.stecQuality 低时 fallback
└── 当前优先级链: SSR dispBias → LocalCorr 多项式 → IONEX VTEC → 广播模型

4F 实现细节:
├── Phase 3 已完成接入：PppRtkCore.modelTrop() 的 TROPOPT_SSR 分支调用 getTrop()
├── 本阶段验证：直接改正 vs 估计 ZTD 的收敛差异
├── H 矩阵 ZTD 偏导数置零后滤波稳定性确认
└── 如直接改正效果优于估计，则 TROPOPT_SSR 默认走直接改正路径

4G 实现细节:
├── ConstraintEngine.java（org.rtklib.java.ppprtk 包下）
├── STEC 约束: 区域电离层产品 → 伪观测方程 → 约束斜距电离层状态量
├── ZTD 约束: 区域对流层产品 → 伪观测方程 → 约束 ZTD 状态量
├── 距离衰减权重: w(dist) = 1 / (1 + (dist/d0)²), d0=50~100km
└── 配置: prcopt.constrainstec / prcopt.constrainztd

4H 实现细节:
├── 对比: 无约束 vs 直接改正 vs 伪观测值约束
├── 指标: 收敛时间（东/北/高程）、稳态精度、固定率
└── 验证论文数据: 东方向改善 85%、高程改善 69%
```

#### 技术储备（放缓期间可做）

- [ ] 整理 CLAS L6 规范中 STEC/对流层多项式定义
- [ ] 调研可用的区域 STEC/ZTD 产品源（IGS 实时电离层产品、区域监测站网）
- [ ] LocalCorr 结构补全：当前字段已齐，可写 GridDefinition + 双线性插值器
- [ ] 参考 CSSRlib `zdres()` 中 SSR STEC/ZTD 约束实现（Python）

---

## 6. Phase 5 — 长期研究方向 🔴

> **状态**: 暂缓
>
> 依赖 Phase 4 基础设施，且无上游场景。

| 方向 | 说明 | 前置条件 |
|------|------|----------|
| **PP-FGO 融合** | PPP 固定解 + 因子图优化，紧耦合 IMU/视觉/SLAM | rtklib-research FGO 成熟 + Phase 4 完好性量化 |
| **IMU-PPP 紧耦合** | IMU 预积分（rtklib-research 已有）+ PPP 状态量融合 | IMU 硬件接入 + 状态量对齐 |
| **学习型后端** | 使用 GNN/Transformer 做观测值质量评估或残差估计 | 大规模训练数据集 |
| **多路径建模** | 城市峡谷场景的多路径 NLOS 检测与修正 | Phase 4 SwitchVariable 验证有效后 |
| **VRS/CORS 扩展** | 多基站联合改正数生成（当前仅支持单站 RTCM） | 多基站网络接入 |

---

## 7. 暂缓原则与恢复条件

### 暂缓原则

以下情况不启动新功能开发：
- 无明确上游应用场景驱动
- 算法验证成本高且短期无数据支撑
- 与现有已实现功能相比提升不明显
- 依赖外部数据源但数据源尚未准备就绪

### 恢复条件

Phase 4 恢复开发的触发条件：
1. 有真实车规/航空/监测场景需要完好性输出（PL + HAL/VAL）
2. 能获取 SSR STEC/ZTD 实时产品或有区域监测站网
3. Phase 3 测试加固完成（核心模块通过真实数据回归验证）

---

## 8. 变更日志

| 版本 | 变更 |
|------|------|
| v1.0 | 初始版本：整合完好性监测、PPP-RTK区域约束、FGO-PPP融合等方向；明确 Phase 1~5 划分；标记暂缓项 |
| v1.1 | rtklib-research 定位为独立研究沙盒；Phase 4/5 子阶段改为表格+实现细节分离 |
| v1.2 | 去掉所有时间线/时间窗口/人天估算，只保留优先级+依赖+交付物 |