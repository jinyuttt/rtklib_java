# RTKLIB-Java

RTKLIB 的 Java 移植版本，基于 [RTKLIB 2.5.0](https://github.com/tomojitakasu/RTKLIB) 开源 GNSS 定位库。

项目定位为**算法引擎库（Library）**而非独立软件，可嵌入 Java 应用中实现 GNSS 数据处理与定位解算。

> **当前版本 v3.2.0（2026-10-24）**：算法能力已冻结，自适应引擎框架已实现。29 项算法修复的闭环索引与精度实测见 [终期技术摘要](docs/PROJECT_FINAL_SUMMARY.md)，版本级变更见 [CHANGELOG](docs/CHANGELOG.md)。

> **功能边界**：Java版专注核心定位算法（SPP/RTK/PPP/PPP-AR/PPP-RTK），数据流通讯由 `rtklib-stream` 模块提供（NTRIP客户端+串口），不包含C版的TCP/UDP服务端、NTRIP Caster、接收机原始协议（u-blox/NovAtel等）、NMEA输出等功能。详见 [实现差异文档第14章](docs/RTKLIB_Differences.md)。

## 模块

### rtklib-core — 核心定位算法

支持 SPP（米级）、RTK（厘米~毫米）、PPP（分米~厘米）、PPP-AR（厘米级）、PPP-RTK（厘米级快速收敛）五种定位模式，数据入口支持 RTCM3 实时流与 RINEX 2.x/3.x 事后文件。

| 定位模式 | 常量 | 精度 | 数据入口 |
|----------|------|------|----------|
| SPP | `PMODE_SINGLE` | 米级 | RTCM3 / RINEX |
| RTK | `PMODE_KINEMA` / `PMODE_STATIC` / `PMODE_MOVEB` | 厘米~毫米 | RTCM3 / RINEX |
| PPP | `PMODE_PPP_KINEMA` / `PMODE_PPP_STATIC` | 分米~厘米 | RTCM3 / RINEX |
| PPP-AR | `PMODE_PPP_KINEMA` + FCB/OSB | 厘米级（模糊度固定） | RINEX + SP3/CLK/FCB |
| PPP-RTK | `PMODE_PPP_KINEMA` + SSR | 厘米级（快速收敛） | RTCM3 (SSR) |

核心包结构：

```
org.rtklib.java
├── adaptive/      自适应引擎（场景判定、参数预设、覆盖层、审计日志、安全监控）
├── ambiguity/     模糊度解算（LAMBDA算法）
├── common/        通用工具（矩阵运算、卫星工具、观测值编码）
├── constants/     常量定义（物理常数、模式常量、卡方分布表）
├── coord/         坐标变换（ECEF↔LLH、ENU变换）
├── data/          数据结构（观测值、星历、导航、解算结果等）
├── ephemeris/     星历计算（卫星位置与钟差）、PCV/DCB/OTL读取
├── ionosphere/    电离层延迟模型
├── kalman/        Kalman滤波器
├── pntpos/        单点定位（SPP、RAIM FDE、速度估计）
├── ppp/           精密单点定位（PPP动态、静态、固定坐标）
├── pppar/         PPP-AR引擎（AR调度、AR配置只读视图）
├── ppprtk/        PPP-RTK定位（SSR改正、模糊度固定）
├── rinex/         RINEX 文件读写与处理
├── rtcm/          RTCM 数据解码
├── rtkpos/        RTK 相对定位核心（含周跳检测、潮汐改正、高级模糊度固定优化）
├── time/          时间系统（GPS时、UTC转换）
├── trace/         追踪日志系统（RtkTrace/PppTrace）
└── troposphere/   对流层延迟模型
```

高级优化（默认关闭，通过`RtkConfig`开关控制）：

**RTK模糊度固定优化**（v2.2.1）：

| 优化项 | 开关 | 来源 | 说明 |
|--------|------|------|------|
| 逐级模糊度固定(EWL→WL→NL) | `enableCascadeAR` | GREAT-PVT | 多频逐级约束，提升固定连续性 |
| 精细化残差编辑与周跳检测 | `enableResidualEdit` | PRIDE | 残差跳变+P-C一致性+弧段筛查 |
| 部分模糊度固定(Partial AR) | `enablePartialAR` | GREAT-PVT | 剔除劣质维度，保留可信子集 |
| Bootstrapping成功率联合判据 | `enableBootstrapping` | GREAT-PVT | Ratio的互补判据 |
| BDS码偏差改正(Wanninger) | `enableBdsCodeBias` | PRIDE | GEO/IGSO/MEO码偏差改正 |
| 参数类型级自适应过程噪声 | `enableParamTypeNoise` | PRIDE | ZTD/电离层随机游走建模 |

**PPP/PPP-RTK优化**（v2.2.2）：

| 优化项 | 开关 | 说明 |
|--------|------|------|
| PPP模糊度固定(PPP-AR) | `enablePppAR` | 整周模糊度固定，提升至厘米级 |
| PPP Fix-and-Hold | `enablePppArFixHold` | 固定后持续约束，维持固定解 |
| PPP部分模糊度固定 | `enablePppPartialAR` | 剔除劣质模糊度，保留可信子集 |
| PPP-RTK | `enablePppRtk` | SSR改正+状态空间表示，快速收敛 |
| PPP-RTK模糊度固定 | `enablePppRtkAR` | PPP-RTK模式下的模糊度固定 |
| PPP-RTK Fix-and-Hold | `enablePppRtkFixHold` | PPP-RTK固定后持续约束 |
| GPT3+VMF3对流层 | `enableGpt3Vmf3` | GPT3先验+VMF3映射函数替代Saastamoinen+GMF |
| IERS2010潮汐改正 | `enableIers2010` | IERS2010标准潮汐模型（极移、海潮负荷等） |

**自适应引擎**（v3.2.0）：

| 组件 | 类 | 说明 |
|------|-----|------|
| 主控 | `AdaptiveController` | 生命周期管理、场景判定、静态保护、位置跳变回滚 |
| 场景枚举 | `GnssScene` | OPEN_SKY / URBAN_CANYON |
| 模式枚举 | `AdaptiveMode` | SPP / RTK / PPP / PPP_AR / PPP_RTK |
| 特征提取 | `FeatureMetrics` | 滑动平均 nSat/SNR + 进入\|\|/退出&& 判定 |
| 参数预设 | `PresetManager` | 5模式×2场景预设表，白名单/黑名单过滤 |
| 覆盖层 | `AdaptiveOverride` | 启动快照原始值，引擎写覆盖，关闭恢复 |
| 审计日志 | `AdaptiveAuditLogger` | CSV格式完整决策记录，自动轮转 |
| 安全监控 | `OptimizationMonitor` | 过度降权WARN + 解发散自动关 |
| 采样检测 | `SampleIntervalDetector` | 前30历元间隔统计 + 异常WARN |
| 回调接口 | `AdaptiveListener` | onSceneSwitch / onDegradation / onParamRecommendation |

设计文档：[gnss-adaptive-design-v3.md](docs/gnss-adaptive-design-v3.md)

轨道模块（v2.2.0新增）：TLE解析、SGP4/SDP4轨道传播、轨道六根数转换、二体传播。验证：Vallado标准14用例全通过，86颗真实TLE卫星0失败。详见 [轨道模块技术参考](docs/ORBIT_MODULE_REFERENCE.md)。

### rtklib-product — 精密产品下载

IGS/MGEX精密产品自动下载器，下载规则对齐PRIDE-PPPAR v3.2 `pdp3.sh`。支持SP3/CLK/ERP/BIA/OBX/FCB/UPD/OSB/DCB/VMF3/GPT3共11类产品，FTPS/FTP/HTTPS多协议多镜像源自动回退、本地缓存和GZ解压。

- **多镜像源回退**：bdspride.com → igs.ign.fr → igs.gnsswhu.cn，自动切换
- **PPP-AR专用**：FCB宽窄巷偏差、UPD非校准相位延迟
- **对流层产品**：VMF3映射函数网格、GPT3 5°网格
- **偏差产品**：OSB观测特定偏差、DCB差分码偏差
- **离线模式**：仅使用本地缓存，不发起网络请求

详见 [精密产品下载技术参考](docs/PRODUCT_MODULE_REFERENCE.md)。

### rtklib-stream — 数据流通讯

GNSS数据流通讯模块，对齐原版RTKLIB的 `stream.c` 核心功能，提供NTRIP客户端和串口通讯能力。

- **NTRIP客户端**：连接NTRIP Caster获取差分数据流（RTCM3观测值、SSR改正数等），支持NTRIP v2.0、HTTP Basic认证、Sourcetable查询、分块传输（chunked）与 gzip 解码、断线自动重连、NMEA GGA上行
- **SSL/TLS**：支持TLS加密连接，兼容EUREF/IGS等要求HTTPS的Caster；可自定义SSLContext（自签名证书、双向TLS认证）
- **串口通讯**：基于jSerialComm实现跨平台串口读写（Windows COM、Linux /dev/ttyUSB*、macOS /dev/cu.*），支持可配置波特率/数据位/校验位/流控
- **统一监听器**：`StreamListener` 接口统一数据/错误/关闭事件回调

```
org.rtklib.java.stream
├── StreamListener        数据流监听器接口
├── NtripClient           NTRIP客户端（JDK Socket + SSL/TLS）
├── NtripClientConfig     NTRIP连接配置（含SSL设置）
├── SerialPort            串口流客户端（jSerialComm）
└── SerialPortConfig      串口配置
```

> **实时流接入能力**：本项目已打通 NTRIP 2.0 协议，成功验证了 CAS IGS 实时 MSM 观测流（WUH20）的接入与解析；CAS 的 SSR 改正流（`SSRA01CAS0`/`SSRC01CAS0`）也已接入，完成「实时录制 → 离线回放 → 改正应用」闭环验证，实时 PPP-RTK 链路已跑通。

### rtklib-adjust — 多基线间接平差

单历元GNSS多基线间接平差模块。基于高斯-马尔可夫最小二乘模型，对同一历元多条独立RTK基线进行融合平差，输出流动站最优坐标及精度评定（σ₀检验 + Baarda数据探测）。

支持基站异常诊断：当σ₀持续超限时，自动定位异常基站并通过静态解算给出建议坐标。

**P0~P3优化**（v2.1.1新增）：
- **P0 基线质量加权**：根据ratio/numSat/age/DOP对低质量FIX基线降权，σ₀更稳健
- **P1 逐卫星残差提取**：从Ssat提取伪距/载波残差、模糊度、周跳等结构化信息到SolData
- **P3 H矩阵位置分量提取**：提取设计矩阵中位置分量，反映基线对Rover坐标的实际灵敏度
- 通过 `PrcOpt.diagMask` 位掩码控制输出，默认关闭，零开销向后兼容

已通过2基站1测站连续9小时RTCM实测数据验证，详见 [技术参考第10~11章](docs/ADJUST_TECHNICAL_REFERENCE.md#10-实测数据验证)。

```
org.rtklib.java.adjust
├── model/          BaselineEpoch（历元容器）、AdjustResult（平差结果）、BaseStationDiagnosis（诊断结果）
├── covariance/     CovAssembler（协方差拼接核心）
├── engine/         GnssBaselineAdjust（平差引擎）、BaseStationDiagnoser（基站诊断器）
└── datasource/     GnssDataSource（数据源接口）、RtcmFileDataSource、RinexFileDataSource、RtcmMemoryDataSource
```

### rtklib-research — 前沿研究沙盒

面向 FGO（因子图优化）、IMU 预积分与场景自适应的研究模块。与 `rtklib-core` **无代码级耦合**（仅依赖 EJML/slf4j），独立演进、不参与主模块依赖链；API 可能随研究进展变动，生产集成请优先使用 `rtklib-core`。

- **FGO 框架**：滑动窗口优化 + 边缘化 + SwitchVariable
- **IMU 预积分**：预积分量与协方差传播
- **IACE 聚类 / 场景自适应**：FE-GUT 数据集验证

验证：FE-GUT 仿真数据集（`PipelineIntegrationTest`、`PreintegrationTest`）。

### rtklib-otl — 海潮负荷BLQ系数生成

从FES2004全球海潮模型直接提取BLQ海潮负荷系数，或计算任意时刻的OTL位移时间序列。独立模块，与rtklib-core无代码级耦合。

- **BLQ系数提取**：`BlqExtractor.extract(lat, lon)` → 3分量 × 11分潮的振幅/相位
- **位移时间序列**：`BlqExtractor.extractDisplacement(lat, lon, startMjd, endMjd, interval)` → ENU位移(mm)
- **标准BLQ输出**：`BlqWriter.write()` 生成兼容GOTIC2/RTKLIB格式的BLQ文件
- **球谐综合**：最大100阶，FES2004球谐系数 + Farrell Love数
- **命令行工具**：`java -jar rtklib-otl.jar -lat 29.65 -lon 91.13 -sta LHASA -o lhasa.blq`

验证：4站Java vs Fortran振幅/相位差异均为0.0000；25站边界测试（极区89°、赤道、海岸线、极端经度）100%通过。详见 [海潮负荷模块技术参考](docs/OTL_MODULE_REFERENCE.md)。

```
org.rtklib.java.otl
├── BlqExtractor      BLQ系数提取 + 位移时间序列计算
├── BlqWriter         标准BLQ格式文件输出
├── BlqConsts         物理常数、分潮名称与Doodson数
├── Fes2004Loader     FES2004球谐系数加载
├── SphericalHarmonic 归一化Legendre函数及导数（Belikov递推）
├── AstroArg          天文参数(ASTRO5)、节点改正、Doodson解码
└── LoveNumberLoader  Farrell Love数加载
```

### rtklib-vrs — 虚拟参考站生成

基于真实基站 RTCM3 数据生成 VRS（Virtual Reference Station）虚拟参考站数据。单基站差分改正 + RTCM3 编码，输出可被标准 NTRIP Client 解码的 RTCM3 文件。

- **VRS 改正**：几何距离 + 对流层（Saastamoinen/GMF）+ 电离层（Klobuchar）差分改正
- **RTCM3 编码**：1005/1006 站坐标、1019/1020/1042/1045 星历、MSM4/MSM5 观测值（1074-1125）
- **星历池管理**：逐历元按 `|toe − t|` 最近选择星历，避免长文件首历元用错星历
- **MSM 信号裁剪**：`nsat×nsig > 64` 时按「频点覆盖最大化」贪心选择，保住双频不退化
- **锁相指示**：跨历元维护 `lock` 计数，确保输出 LLI 正确（否则下游 RTK 永远无法 FIX）

验证：真实双站 RTCM3（239 历元 / 15 s）——VRS+测站 RTK 与原始基站+测站 FIX 率均为 91.2%，VRS 设在基站位置时 P/L 逐位一致（max|dL|=0.0000 m）。详见 [VRS 模块设计文档](docs/VRS_MODULE_DESIGN.md)。

## 文档

> 全部文档的索引、状态标注（活跃/待复核/归档）与已知不一致清单见 [文档地图](docs/README.md)。

### rtklib-core

| 文档 | 说明 |
|------|------|
| [使用指南](docs/USAGE_GUIDE.md) | 各功能模块使用方法、API 示例、输出字段含义 |
| [技术文档](docs/RTKLIB_JAVA_TECHNICAL_REFERENCE.md) | 关键数据结构、状态索引、常量定义及已知问题 |
| [实现差异](docs/RTKLIB_Differences.md) | Java 版与 C 版的有意差异说明 |
| [矩阵存储参考](docs/MATRIX_DIMENSION_REFERENCE.md) | Kalman 滤波矩阵维度、存储约定及运算差异 |
| [优化介绍](docs/RTK_Extra_Optimizations.md) | Java 版额外优化项（C 版没有的），独立开关控制 |
| [PPP 优化说明](docs/PPP_Extra_Optimizations.md) | PPP 扩展优化项原理、调用架构与逐项对照测试 |
| [SPP 优化说明](docs/SPP_Extra_Optimizations.md) | SPP 优化项（EKF、M 估计、零速约束、多普勒+SNR 加权） |
| [PPP 体系指南](docs/PPP_Optimization_Systems.md) | 浮点 PPP / PPP-AR / PPP-RTK 三分支的定位与测试纪律 |
| [优化功能矩阵](docs/CORE_OPTIMIZATION_MATRIX.md) | 按定位模式×场景分类的优化功能矩阵，含静态实测结论、动态适用性、基线类型标记 |
| [算法修复档案](docs/ALGORITHM_FIX_LOG.md) | FIX-ALG-001~029 逐条 C 依据 / 根因 / 验证（改算法前必读） |
| [测试用例与数据指南](docs/DATA_GUIDE.md) | 测试规范、测试类总览、数据依赖、优化矩阵测试用例、上传规范 |
| [轨道模块技术参考](docs/ORBIT_MODULE_REFERENCE.md) | TLE解析、SGP4/SDP4传播、六根数转换、二体传播 |
| [开发路线图](docs/ROADMAP.md) | 各模块完成度、四梯队（0~3）规划、v3.1.0 封存策略、暂缓与恢复条件 |
| [自适应引擎设计](docs/gnss-adaptive-design-v3.md) | 场景判定、参数预设、安全机制、日志体系、包结构、测试策略 |

### 项目级

| 文档 | 说明 |
|------|------|
| [终期技术摘要](docs/PROJECT_FINAL_SUMMARY.md) | 一句话定位 + 精度实测汇总 + FIX-ALG 001~029 闭环索引 + HAS/IMU/CLAS 扩展路线 |
| [变更日志](docs/CHANGELOG.md) | 版本级变更摘要（当前至 3.1.0） |
| [发布说明](RELEASE.md) | v3.1.0 封存版发布稿：本版重点、配置项、兼容性、已知问题、验收口径 |
| [SSR 接入手册](docs/SSR_ACCESS_PLAYBOOK.md) | 接入操作与验收（已闭环）：两行改动、五步接入、开关表、验收清单与已知坑 |

### rtklib-product

| 文档 | 说明 |
|------|------|
| [精密产品下载技术参考](docs/PRODUCT_MODULE_REFERENCE.md) | IGS精密产品自动下载、URL优先级、缓存策略、API示例 |

### rtklib-stream

| 文档 | 说明 |
|------|------|
| — | 模块代码含完整中文Javadoc，暂无独立文档 |

### rtklib-otl

| 文档 | 说明 |
|------|------|
| [海潮负荷模块技术参考](docs/OTL_MODULE_REFERENCE.md) | BLQ系数提取、位移时间序列、命令行用法、API示例、验证结果 |

### rtklib-adjust

| 文档 | 说明 |
|------|------|
| [使用说明](docs/ADJUST_USAGE.md) | API 示例、输出字段含义 |
| [技术参考](docs/ADJUST_TECHNICAL_REFERENCE.md) | 数学模型、协方差构造、理论局限性、联合解算可行性评估 |

### rtklib-vrs

| 文档 | 说明 |
|------|------|
| [模块设计文档](docs/VRS_MODULE_DESIGN.md) | 实现状态、VRS 改正算法、RTCM3 编码器设计、MSM 信号裁剪与锁相指示、测试策略与实测结果、已踩过的坑 |

## 使用建议

本项目各模块按影响面和风险分为两类，使用者可根据自身需求选择对应的集成策略：

### 第一类：数据解析/辅助型

> 直接使用，不会有实质影响，结论明确。有问题提交 Issue 或自行修改后提交 PR。

不涉及定位解算核心逻辑，侧重点在数据获取、格式解析、辅助改正数计算。集成到业务系统时**无定位精度风险**：

| 模块 | 功能 | 集成风险 |
|------|------|----------|
| `rtklib-stream` | NTRIP客户端 + 串口通讯 | 极低 — 纯数据流，输出为RTCM字节流 |
| `rtklib-product` | IGS精密产品自动下载 | 极低 — 纯文件下载，与定位算法解耦 |
| `rtklib-otl` | 海潮负荷BLQ系数/位移 | 极低 — mm级改正数，对定位无实质性负面风险 |
| `rtklib-adjust` | 多基线间接平差 | 低 — 输入为已解算的RTK基线，不修改定位核心 |
| `rtklib-core`（辅助部分） | 数据解析与模型 | 低 — 不涉及定位解算，子模块可独立使用： |

`rtklib-core` 中大量子模块属于数据解析和模型计算，不包含定位解算逻辑，可直接复用：

| 子模块 | 功能 | 说明 |
|--------|------|------|
| `rtcm/` | RTCM 数据解码 | 解析RTCM3各消息类型（观测值、星历、SSR等） |
| `rinex/` | RINEX 文件读写 | 支持 2.x/3.x 观测文件、导航文件、气象文件 |
| `ephemeris/` | 星历计算 | 卫星位置/钟差计算、PCV/DCB/OTL读取 |
| `coord/` | 坐标变换 | ECEF↔LLH变换、ENU变换、大地基准转换 |
| `time/` | 时间系统 | GPS时/UTC/GLO时/BDS周 等互转 |
| `ionosphere/` | 电离层模型 | Klobuchar/GIM/IONEX等电离层延迟计算 |
| `troposphere/` | 对流层模型 | Saastamoinen/GMF/VMF1等延迟计算和映射函数 |
| `common/` | 通用工具 | 矩阵运算、卫星ID编解码、周跳检测工具函数 |

### 第二类：定位解算/算法型

> 先用自己的测试数据验证有效性，确认通过后再集成使用；不信任时先集成为对比工具，独立运行发现问题再决定是否替换。

涉及状态估计（EKF）、模糊度固定、质量控制等核心算法，直接影响定位结果。建议流程：

| 模块 | 功能 | 集成风险 |
|------|------|----------|
| `rtklib-core`（定位部分） | SPP/RTK/PPP/PPP-RTK 全模式定位 | **高** — 定位精度依赖算法实现正确性 |

涉及 `pntpos/`（SPP）、`rtkpos/`（RTK）、`ppp/`（PPP）、`ppprtk/`（PPP-RTK）、`kalman/`（EKF）、`ambiguity/`（LAMBDA）六个子模块。辅助模块（rtcm/rinex/ephemeris/coord/time/ionosphere/troposphere/common）属于第一类，无风险。

1. 确认自己的数据场景与本项目已验证的场景匹配（基线长度、电离层活跃度、遮挡程度等）
2. 用自己的已知参考数据跑通本项目的完整流程，对比预期结果
3. 信任且测试通过 → 直接集成使用；同时关注 [优化功能矩阵](docs/CORE_OPTIMIZATION_MATRIX.md) 按场景选择优化开关
4. 不确定 → 作为对比工具保留，与原系统并行对比，积累足够置信度后再替换
5. 发现缺陷 → 分析根因后提交 Issue，或修改后提交 PR

> **原则**：本项目定位算法经过真实数据验证（详见开发状态），但所有集成仍建议以自有测试数据验证通过为前提。不盲目信任任何代码的精度声明。

## 场景置信度

本项目按**实测验证程度**将支持场景分为两级，使用者应据此评估集成风险。

### ✅ 已实测验证

以下场景已有公开/私有数据端到端验证，结果与参考解一致：

| 场景 | 定位模式 | 数据来源 | 验证方式 |
|------|----------|----------|----------|
| 开阔静态 SPP | SPP | MobileGNSS-SPP (G+C) | vs C版参考结果 |
| 城市街道 SPP | SPP | MobileGNSS-SPP (G+C) | vs C版参考结果 |
| BDS RTCM SPP | SPP | 本地RTCM3 (C) | 逐历元成功 |
| 短基线 RTK Kinematic | RTK | RINEX2.10 GPS base+rover | vs C版参考结果 |
| 多系统 RTK Static | RTK-Static | GREAT-PVT RTKFLT (G+E+C+R+J) | 端到端解算 |
| BDS RTK (RTCM) | RTK | 本地RTCM3 base+rover | 私有数据验证 |
| 多系统 PPP Static | PPP-Static | GREAT-PVT PPPFLT (G+E+C+R) | 端到端解算 |
| PPP + GPT3+VMF3 | PPP | 同上 + CODE产品 | 端到端解算 |
| PPP + IERS2010 | PPP | 同上 | 端到端解算 |
| PPP + ISB/IFCB | PPP | 同上 | 端到端解算 |
| PPP-AR + Fix-and-Hold | PPP-AR | 同上 + WHU UPD(WL/NL) | 端到端解算 |
| PPP-AR 三频 (EWL+WL+NL) | PPP-AR | ALGO 2024 DOY060 + WUM | 固定率 65.7%（1226 历元）/ 2D RMS 3.00 cm（C 参考 3.54 cm） |
| PPP + GPT3 真网格 | PPP | HKWS / HKSL 2018 DOY157 | U RMS 0.806 / 1.662 m，优于 NMF 基线 |
| 实时 NTRIP 流 + MSM7 | SPP | WUH20 实时流（CAS） | 262 帧 CRC 全通过；73 历元 SPP 3D 偏差 1.89 m |
| 精密产品读取 | — | IGS CODE WHU (SP3/CLK/IONEX/OSB/ATX) | 文件解析验证 |
| 多基线间接平差 | Adjust | 本地RTCM3 2基站9小时 | σ₀+Baarda验证 |

### 🔧 框架已实现，待场景数据验证

以下功能代码已实现且通过单元测试，但缺少真实场景数据端到端验证。按数据可得性分三级：

**数据公开可得，缺集成验证**（优先级高，可自行下载）：

| 场景 | 定位模式 | 数据获取方式 |
|------|----------|-------------|
| PPP-AR 24h 静态精度 | PPP-AR | IGS 24h RINEX + CODE MGEX SP3/CLK + WHU UPD，CDDIS 免费注册下载 |
| 长基线 RTK (>10km) | RTK-Static | IGS 双站 RINEX（如 WTZR+BRUX ~600km），CDDIS 免费注册下载 |
| 动态车载 RTK | RTK-Kinematic | RTKLIB sample / rtkexplorer F9P 数据 / Google Smartphone Decimeter Challenge |

> 下载脚本：`test-data/download-igs-data.ps1`（CDDIS 需先配置 [NASA Earthdata Login](https://urs.earthdata.nasa.gov/users/new)）

**数据部分可得，需筛选适配**（工程量中等）：

| 场景 | 定位模式 | 数据获取方式 |
|------|----------|-------------|
| PPP-RTK 实时 | PPP-RTK | CAS / 北航 / Galileo HAS / QZSS CLAS 四条线的权限门槛与接入步骤见 [SSR 接入手册](docs/SSR_ACCESS_PLAYBOOK.md) |
| 三频 RTK/PPP | RTK/PPP | IGS MGEX 三频测站 RINEX（BDS-3 B1c/B2a/B3i 或 GAL E1/E5a/E5b） |

**数据确实稀缺，需自采或社区贡献**：

| 场景 | 定位模式 | 障碍 |
|------|----------|------|
| 城市峡谷/深遮挡 RTK | RTK | 带真值的 base+rover 深遮挡成对数据基本无公开来源 |
| Moving-Base RTK | RTK-MoveB | 公开双移动接收机同步观测数据极少 |

> 如有可公开的上述数据，欢迎上传至 `test-data/` 目录或提交 Issue。详见 [测试用例与数据指南](docs/DATA_GUIDE.md)。

## 环境要求

- Java 17+
- Maven 3.6+

## 开发状态

基础功能已完善，覆盖主流GNSS定位算法与工程落地。详细规划见 [开发路线图](docs/ROADMAP.md)。

**Phase 1 ✅ — 基础能力建设**：RTKLIB C 版核心算法全移植（SPP/RTK/PPP/PPP-RTK）、LAMBDA、RTCM3、RINEX3、Kalman、精密产品、NTRIP、轨道模块、海潮负荷。

**Phase 2 🟡 — 高级优化与独立模块**（基本完成）：

| 模块 | 状态 | 测试方式 |
|------|------|----------|
| SPP 单点定位 + EKF + 抗差 + 零速约束 | ✅ | 真实RINEX数据 vs C版参考结果 |
| RTK 相对定位 + 6项高级优化 | ✅ | 真实RINEX数据 vs C版参考结果 |
| PPP + PPP-AR + 5项高级优化 | ✅ | 真实RINEX+精密星历数据 |
| PPP-RTK 基础（SSR改正） | ✅ | SSR 解码→`nav.ssr`→`SsrCorrector`→`ppprtkos()` 链路；解码经公开 SSR 样本单测（`SsrDecodeSmokeTest`） |
| Compact SSR / CLAS / HAS 解码 | ✅ | 单元测试（CLAS MT4073 子类型 1-12、HAS MT1~MT9）/ CSSRlib 交叉验证 |
| 多基线间接平差 | ✅ | 真实RTCM实测数据 |
| 因子图优化 FGO + IMU 预积分 | ✅ | FE-GUT 仿真数据集 |
| 轨道模块 + 海潮负荷 | ✅ | Vallado/Fortran 参考对比 |
| 自适应引擎框架 | ✅ | 14类实现，编译通过；待集成测试验证 |

剩余：PPP-RTK 区域大气约束、完好性保护级（HPL/VPL）、ARAIM、固定解完好性增强——均属研究深化方向，**无上游场景驱动，定版不再投入（WONTFIX）**。

**梯队 0/1 ✅ — 性能基准与工程质量**（已完成 2026-10-12）：JMH 微基准 + 端到端计时基线、RtkCore/PppCore 工作数组复用、SLF4J 日志统一、异常捕获改进、SSR 对流层直接改正、34 个关键算法单元测试。

**梯队 2/3 🔴 — 已关闭**：3A Kalman 优化与 3.5B null 消除经 JMH 基准实测后放弃；CI 真实数据回归、CLAS STEC 多项式、场景验证等依赖外部数据/条件，随 v3.1.0 封存整体 WONTFIX。恢复条件见 [开发路线图 §10](docs/ROADMAP.md)。

**未实现的C版功能**（设计上不实现）：TCP/UDP服务端、NTRIP Caster、接收机原始协议（u-blox/NovAtel等）、NMEA输出、GPX/KML输出、大地水准面/基准转换。详见 [实现差异文档第14章](docs/RTKLIB_Differences.md)。

## 参考来源

- [RTKLIB 2.5.0](https://github.com/tomojitakasu/RTKLIB) - 原始 C 语言实现
- [RTKLIB Manual](http://www.rtklib.com/rtklib_document.htm) - 算法原理与使用说明

> C版对齐状态、方法命名规则、测试验证状态详见 [技术文档](docs/RTKLIB_JAVA_TECHNICAL_REFERENCE.md) 第16~18章。

## 补充说明

**rtklib-stream 模块**：`rtklib-stream` 提供 NTRIP 客户端和串口通讯能力，是实时数据接入的推荐方式。NTRIP 客户端支持 SSL/TLS、自动重连和 Sourcetable 查询；串口客户端基于 jSerialComm 实现跨平台支持。两者通过统一的 `StreamListener` 接口回调数据，上层应用收到数据后通过 `PppProcessor.feed()` 或 `RtkProcessor.feed()` 将字节流喂入核心算法即可。

**rtklib-test 中的 NtripClient**：`rtklib-test` 中的 `NtripClient` 是早期为方便 PPP-RTK 实时测试而编写的简易版本，功能已被 `rtklib-stream` 模块取代，保留仅为向后兼容。新代码应使用 `org.rtklib.java.stream.NtripClient`。

## License

本项目基于 RTKLIB 原始代码移植，遵循 RTKLIB 的 BSD-2-Clause 许可证。