# RTKLIB-Java

RTKLIB 的 Java 移植版本，基于 [RTKLIB 2.5.0](https://github.com/tomojitakasu/RTKLIB) 开源 GNSS 定位库。

项目定位为**算法引擎库（Library）**而非独立软件，可嵌入 Java 应用中实现 GNSS 数据处理与定位解算。

> **当前版本 v3.5.0（2026-11-12）**：许可证合规重构版本。`rtklib-otl` 负荷潮模块与 GPT3 气象网格读取器已按 IERS Conventions (2010)、Doodson (1921)、Schureman (1958)、Farrell (1972)、Landskron & Böhm (2018)、Askne & Nordius (1987) 等公开文献独立重写，不再与任何外部 Fortran 源码存在函数名、变量名、数组索引或循环结构对应关系；仓库内全部第三方编译产物（RTKLIB-EX 可执行文件、Fortran 参考程序及其二进制、`libopenblas.a`）已从当前主干与 Git 历史中彻底移除，并加入 `.gitignore` 永久防护。对外 API 保持不变，OTL 数值与历史基线逐字节一致，GPT3 输出与重写前逐位一致（12 点回归测试固化）。同版新增 `rtklib-ai` 接口三的激进模式实验配置（默认关闭）。算法修复的闭环索引与精度实测见 [终期技术摘要](docs/PROJECT_FINAL_SUMMARY.md)，版本级变更见 [CHANGELOG](docs/CHANGELOG.md)。
>
> **评估口径提醒**：自 FIX-ALG-062 起，**固定率不得单独作为质量指标**——必须附带绝对坐标 RMS、错固率（误差 > 0.5 m 的 FIX 历元占比）、相位残差统计与 A/C/D 双跑配对。历史上一批「高固定率」结论已被证实是锁存造成的假 FIX 计数。

> **功能边界**：Java版专注核心定位算法（SPP/RTK/PPP/PPP-AR/PPP-RTK），数据流通讯由 `rtklib-stream` 模块提供（NTRIP客户端 + NTRIP Caster 服务端 + 串口 + GGA 解析），不包含C版的TCP/UDP服务端、接收机原始协议（u-blox/NovAtel等）、NMEA输出等功能。详见 [实现差异文档第14章](docs/RTKLIB_Differences.md)。

## 模块

### rtklib-core — 核心定位算法

支持 SPP（米级）、RTK（厘米~毫米）、PPP（分米~厘米）、PPP-AR（厘米级）、PPP-RTK（厘米级快速收敛）五种定位模式，数据入口支持 RTCM3 实时流与 RINEX 2.x/3.x 事后文件。

| 定位模式 | 常量 | 精度 | 数据入口 |
|----------|------|------|----------|
| SPP | `PMODE_SINGLE` | 米级 | RTCM3 / RINEX |
| RTK | `PMODE_KINEMA` / `PMODE_STATIC` / `PMODE_MOVEB` | 厘米~毫米 | RTCM3 / RINEX |
| PPP | `PMODE_PPP_KINEMA` / `PMODE_PPP_STATIC` | 分米~厘米 | RTCM3 / RINEX |
| PPP-AR | `PMODE_PPP_KINEMA` + FCB/OSB | 厘米级（模糊度固定） | RTCM3 / RINEX + SP3/CLK/FCB(OSB) |
| PPP-RTK | `PMODE_PPP_KINEMA` + SSR | 厘米级（快速收敛） | RTCM3 (SSR) |

核心包结构：

```
org.rtklib.java
├── adaptive/      自适应引擎（场景判定、参数预设、覆盖层、审计日志、安全监控）
├── aggregate/     历元聚合（鲁棒加权 / Kalman+RTS 平滑）
├── ambiguity/     模糊度解算（LAMBDA算法）
├── common/        通用工具（矩阵运算、卫星工具、观测值编码）
├── config/        配置对象（RtkConfig 及 Common/Rtk/Spp/Ppp/PppAr/PppRtk/Adaptive 子配置）
├── constants/     常量定义（物理常数、模式常量、卡方分布表）
├── coord/         坐标变换（ECEF↔LLH、ENU变换）
├── cssr/          紧凑SSR（CSSR/4073）解码与格网改正
├── data/          数据结构（观测值、星历、导航、解算结果等）
├── ephemeris/     星历计算（卫星位置与钟差）、PCV/DCB/OTL读取
├── ionosphere/    电离层延迟模型
├── kalman/        Kalman滤波器
├── monitor/       基站稳定性监测（SPP中位数窗口，检测坐标突变/漂移）
├── orbit/         轨道计算（TLE解析、SGP4/SDP4传播、开普勒传播、六根数转换）
├── pntpos/        单点定位（SPP、RAIM FDE、速度估计）
├── postpos/       事后处理（.pos 文件 RTS 平滑，实验性）
├── ppp/           精密单点定位（PPP动态、静态、固定坐标）
├── pppar/         PPP-AR引擎（AR调度、AR配置只读视图）
├── ppprtk/        PPP-RTK定位（SSR改正、模糊度固定）
├── rinex/         RINEX 文件读写与处理
├── rtcm/          RTCM 数据解码（含 CustomMessageDecoder 扩展点）
├── rtkpos/        RTK 相对定位核心（含周跳检测、潮汐改正、高级模糊度固定优化）
├── tide/          潮汐位移（IERS2010 大气潮 S1/S2）
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

GNSS数据流通讯模块，对齐原版RTKLIB的 `stream.c` 核心功能，提供NTRIP客户端、NTRIP Caster 服务端、GGA 解析和串口通讯能力。

- **NTRIP客户端**：连接NTRIP Caster获取差分数据流（RTCM3观测值、SSR改正数等），支持NTRIP v2.0、HTTP Basic认证、Sourcetable查询、分块传输（chunked）与 gzip 解码、断线自动重连、NMEA GGA上行
- **NTRIP Caster（服务端）**：监听 TCP、播发 Sourcetable、接受挂载点订阅、HTTP Basic 鉴权、接收客户端上行 GGA。`publish(mountpoint, byte[])` 向指定挂载点全部订阅者广播；每会话独立写线程 + 有界队列，队列满丢弃最旧帧，慢客户端不影响其他订阅者。**Caster 只做连接与分发，不生成改正数据**——下行内容由应用层提供
- **GGA 解析**：`NmeaGgaParser` 兼容任意 talker ID、强制校验和、坏行静默忽略；`NmeaGga.ellipsoidHeight()` 按 `h = H + N` 把正高换算为椭球高，`toVrsPosition()` 直接给出 `[lat(rad), lon(rad), h(m)]`
- **SSL/TLS**：支持TLS加密连接，兼容EUREF/IGS等要求HTTPS的Caster；可自定义SSLContext（自签名证书、双向TLS认证）
- **串口通讯**：基于jSerialComm实现跨平台串口读写（Windows COM、Linux /dev/ttyUSB*、macOS /dev/cu.*），支持可配置波特率/数据位/校验位/流控
- **统一监听器**：`StreamListener` 接口统一数据/错误/关闭事件回调

```
org.rtklib.java.stream
├── StreamListener        数据流监听器接口
├── NtripClient           NTRIP客户端（JDK Socket + SSL/TLS）
├── NtripClientConfig     NTRIP连接配置（含SSL设置）
├── SerialPort            串口流客户端（jSerialComm）
├── SerialPortConfig      串口配置
├── caster/               NTRIP Caster 服务端（独立包，3.3.0）
│   ├── NtripCaster       监听/握手/鉴权/Sourcetable/广播
│   ├── NtripCasterConfig 监听地址、挂载点、鉴权用户
│   ├── MountPoint        挂载点描述（对应 STR 记录）
│   ├── CasterSession     单订阅会话 + 异步写队列
│   └── NtripCasterListener 连接/断开/GGA 事件回调
└── nmea/                 NMEA 解析（独立包，3.3.0）
    ├── NmeaGga           GGA 值对象（含椭球高换算）
    └── NmeaGgaParser     流式解析器
```

> **实时流接入能力**：本项目已打通 NTRIP 2.0 协议，成功验证了 CAS IGS 实时 MSM 观测流（WUH20）的接入与解析；CAS 的 SSR 改正流也已接入，完成「实时录制 → 离线回放 → 改正应用」闭环验证，实时 PPP-RTK 链路已跑通。Caster 侧用本模块既有 `NtripClient` 反连做互操作验收（`NtripCasterClientInteropTest`），两侧实现互通即证明握手、鉴权、分帧一致。

### rtklib-adjust — 多基线间接平差

单历元GNSS多基线间接平差模块。基于高斯-马尔可夫最小二乘模型，对同一历元多条独立RTK基线进行融合平差，输出流动站最优坐标及精度评定（σ₀检验 + Baarda数据探测）。

支持基站异常诊断：当σ₀持续超限时，自动定位异常基站并通过静态解算给出建议坐标。

可选 **FLOAT 补位**（`allowFloat`，默认关闭）：FIX 基线不足 2 条时由 FLOAT 补位，出解覆盖率 90.8% → 100.0%（实测 2 基站 251 历元），补位历元如实标注为 `UNVERIFIED`，不影响原有 FIX 判级。

协方差来源支持两种：Kalman 后验 `Pa`（`Sol.qr`，默认）与 core 新增的**单历元归约位置协方差** `Q_pos`（`PrcOpt.DIAG_QPOS`，切断跨历元相关性）。两者经 `SolData.covEcef` 统一透传，切换不改解算坐标。

已通过2基站1测站连续9小时RTCM实测数据验证。基线质量加权、逐卫星残差与 H 矩阵位置分量等诊断输出，见 [使用说明](docs/ADJUST_USAGE.md)；实测结果见 [技术参考第10~11章](docs/ADJUST_TECHNICAL_REFERENCE.md#10-实测数据验证)。

```
org.rtklib.java.adjust
├── model/          BaselineEpoch（历元容器）、AdjustResult（平差结果）、BaseStationDiagnosis（诊断结果）
├── covariance/     CovAssembler（协方差拼接核心）
├── engine/         GnssBaselineAdjust（平差引擎）、BaseStationDiagnoser（基站诊断器）
└── datasource/     GnssDataSource（数据源接口）、RtcmFileDataSource、RinexFileDataSource、RtcmMemoryDataSource
```

### rtklib-research — 前沿研究沙盒

面向 FGO（因子图优化）、IMU 预积分与场景自适应的研究模块。与 `rtklib-core` **无代码级耦合**（仅依赖 EJML/slf4j），独立演进、不参与主模块依赖链；API 可能随研究进展变动，生产集成请优先使用 `rtklib-core`。

功能清单见 [FEATURES.md](rtklib-research/FEATURES.md)，用法见 [USAGE.md](rtklib-research/USAGE.md)，开发状态与缺口见 [DEVELOPMENT.md](rtklib-research/DEVELOPMENT.md)。

### rtklib-otl — 海潮负荷BLQ系数生成

从FES2004全球海潮模型直接提取BLQ海潮负荷系数，或计算任意时刻的OTL位移时间序列。独立模块，与rtklib-core无代码级耦合。

- **BLQ系数提取**：`BlqCalculator.extract(lat, lon)` → 3分量 × 11分潮的振幅/相位
- **位移时间序列**：`BlqCalculator.extractDisplacement(lat, lon, startMjd, endMjd, interval)` → ENU位移(mm)
- **标准BLQ输出**：`BlqWriter.write()` 生成兼容GOTIC2/RTKLIB格式的BLQ文件（站名置于列名行前两字符之后，可被 `rtklib-core` 的 `OtlReader.readblq()` 读回）
- **球谐综合**：最大100阶，FES2004球谐系数 + Farrell Love数
- **命令行工具**：`java -jar rtklib-otl-3.5.0.jar -lat 29.65 -lon 91.13 -sta LHASA -o lhasa.blq`

OTL 理论基于 IERS 2010 Conventions，参考了 FES2004 全球海潮模型。本项目为纯 Java 独立实现。验证：4站 BLQ 输出与数值参考基线逐字节一致（振幅/相位差异均为0.0000）；23站边界测试（极区89°、赤道、海岸线、极端经度）100%通过。详见 [海潮负荷模块技术参考](docs/OTL_MODULE_REFERENCE.md)。

```
org.rtklib.java.otl
├── BlqCalculator        BLQ系数提取 + 位移时间序列合成
├── OceanLoadingModel    FES2004系数 + 负荷Love数数据集合
├── GeodeticSite         测站几何与重力参数（WGS84 + Somigliana）
├── LegendreTable        完全归一化Legendre函数及一/二阶导数
├── AstronomicalArgument 历元天文幅角（IERS 2010 表5.3）
├── DoodsonArgument      Doodson编码分解与相位合成
├── NodalCorrection      交点潮调制因子f、相位改正u、相位偏置
├── ComplexNumber        复数振幅表示
├── BlqWriter            标准BLQ格式文件输出
├── BlqConsts            物理常数、分潮名称与Doodson数
├── Fes2004Loader        FES2004球谐系数加载
└── LoveNumberLoader     Farrell Love数加载
```

### rtklib-vrs — 虚拟参考站生成

以单个真实基站的 RTCM3 观测值为输入，在指定虚拟位置合成虚拟参考站（VRS）观测值——基站观测加上几何距离、对流层、电离层差分改正量——再编码为标准 RTCM3 输出，可被通用 RTCM3 解码器（含 NTRIP Client）解析。

- **VRS 改正**：几何距离 + 对流层（Saastamoinen/GMF）+ 电离层（Klobuchar）差分改正
- **RTCM3 编码**：1005/1006 站坐标、1019/1020/1042/1045 星历、MSM4/MSM5 观测值（1074-1125）
- **星历池管理**：逐历元按 `|toe − t|` 最近选择星历，避免长文件首历元用错星历
- **MSM 分批发送**：`nsat×nsig > 64` 时按卫星分批（每批 ≤ `64/nsig` 颗，`sigmask` 跨批保持一致，`sync` 位串联累积），不裁剪信号，双频不退化
- **锁相指示**：跨历元维护 `lock` 计数，确保输出 LLI 正确（否则下游 RTK 永远无法 FIX）

验证：真实双站 RTCM3（239 历元 / 15 s）——原始基站+测站 FIX 77.0%，VRS+测站 FIX 76.6%，VRS 设在基站位置时 P/L 逐位一致。详见 [VRS 模块设计文档](docs/VRS_MODULE_DESIGN.md)。

### rtklib-joint — 多基线联合 RTK

观测层联合解算：k 条基线的观测值送入**同一个 Kalman 滤波器**，Rover 坐标只出现一次但被 k 组观测同时约束。特点是**逐历元实时**、**环境好时精度提高**（共享位置约束加快模糊度收敛、提高 FIX 率）；全部 FIX 后精度与单基线等价。与 `rtklib-adjust`（结果层平差，属可靠性工具）互补。

- **状态向量**：Rover 坐标共享 + 各基线模糊度（大气维度划分见设计文档 §2.1.1）
- **历元对齐**：`EpochAligner` 统一多基站时间标签
- **分层架构**：`JointRtkProcessor`（外壳：数据源 + 解码 + 输出）/ `JointProcessor` → `JointCore`（内核：算法入口与联合 rtkpos 主循环）
- **输入双模式**：`feedRover`/`feedBase` 实时字节流（RTCM3），`process`/`processRinex` 离线文件

状态：**核心实现已完成并经真实数据验证**（2 基站 + 1 测站 RTCM3 单小时 236 历元，单/双基线 FIX 91.5%，与单基线参考 91.2% 持平）。详见 [联合 RTK 设计文档](docs/JOINT_RTK_DESIGN.md)、[joint vs adjust 对比](docs/JOINT_VS_ADJUST_ANALYSIS.md)。

### rtklib-networkvrs — 多基站网络 VRS

在 `rtklib-vrs` 单基站基础上组网，把每颗卫星的误差分解为**色散项**（电离层）与**非色散项**（对流层+轨道残差），按流动站位置空间插值后生成虚拟观测值，目标是突破单站改正 >30 km 的退化（对标 30~70 km 可靠、FIX≥90%）。

- **多站同步**：`MultiStationSynchronizer` + `SynchronizedEpoch` 统一多基站历元标签
- **网解引擎**：`BaselineNetwork` 组网 → `DdObservationBuilder` 逐基线双差 → `BaselineWindowSolver` 窗口解（宽巷/窄巷 AR）
- **误差分解**：`NetworkErrorDecomposer` 把双差残差还原为各站相对主站的色散（电离层）/非色散（对流层+轨道）改正
- **空间插值**：`CorrectionInterpolator` 接口 + `IdwInterpolator`（默认）+ `FkpInterpolator`（平面拟合，站点近共线可回退 IDW）
- **输出桥接**：`VrsOutputBridge` 复用 `rtklib-vrs` 的 RTCM3 编码（1005/1006、MSM4/MSM5）
- **数据加载**：`RinexStationLoader` / `RtcmStationLoader` / `RinexNavLoader` 统一三站以上输入
- **MAC 改正合成**：`MacCorrectionBuilder`（档 3 已冻结，只做改正合成，无编码与输出编排）

状态：**档 1 完成、档 2 已实现、档 3 冻结**。档 1 = 多站同步 + IDW 插值 + 相对主站改正 + VRS 输出桥；档 2 = 网解引擎 + 误差分解 + FKP，真实三站验证，窄巷固定已闭环（BRDC 与精密星历 ratio 均 > 3）；档 3 MAC 仅改正合成。设计文档已定稿（v1.0），详见 [网络 VRS 设计文档](docs/NETWORK_VRS_DESIGN.md)。

### rtklib-ext-igs — IGS 专有 RTCM3 扩展

4076 是 RTCM 分配给 IGS 的**专有消息号**（不是 RTCM 通用 SSR），本模块通过 `rtklib-core` 的 `CustomMessageDecoder` 扩展点注册解码器。

- **公共头部**：版本、子类型、历元、更新间隔、多消息指示、IOD SSR、提供者 ID、解算 ID
- **子类型识别**：卫星级（IM21~IM127：GPS/GLONASS/Galileo/QZSS/BDS/SBAS × 轨道/钟差/码偏差/相位偏差/URA），并解析轨道类 CRS 指示、相位偏差类一致性指示与卫星数；电离层 IM201/IM202
- **IM201 球谐**：VTEC 质量指示（9 bit ×0.05 TECU）+ 分层数，逐层层高/阶 N/次 M 与 C/S 系数（int16 ×0.005 TECU）
- **VTEC 合成**：完全归一化勒让德（Heiskanen & Moritz）→ VTEC 合成 → 穿刺点几何 → 单层 STEC 映射 → 伪距延迟
- **版本兼容**：标准规定版本为 1，实测 CAS `*CAS1` 流为 0，两者都接受

状态：**IM201 解码闭环**（头部→球谐系数→VTEC/STEC）；4076 卫星级改正数数值未解析，VTEC 未接入定位链路。协议细节见 [NETWORK_VRS_DESIGN.md §3.4](docs/NETWORK_VRS_DESIGN.md)。

### rtklib-ai — AI 增强（可选模块）

`rtklib-core` 只放 SPI（`AiProvider` / `AiHooks` / `AiOpts`），本模块提供实现与训练管线。**默认不启用**，未注入时 core 行为与无 AI 完全一致。

- **接口一（假固定判别）**：`FixValidatorImpl` + `FixQualityFeatures`（6 维固定后残差类特征），判断当前 FIX 是否可信
- **接口二（固定决策辅助）**：冻结，不实施
- **接口三（观测改正）**：`ObsCorrectorImpl` + `ObsCorrectionFeatures`（逐卫星逐频点特征），在进入解算前修正观测值
- **推理层**：`OnnxAiProvider` / `OnnxInference` / `BatchInference`（ONNX Runtime 1.17.3），`FeatureNorm` 归一化
- **采集层**：`CollectConfig` / `CollectSession` / `ObsCorrectorCollectSession` / `RecordingFixValidator` / `RecordingObsCorrector`，把运行中的特征与标签实时录制为训练集
- **训练管线**（`training/`，Python）：`fix_validator/`（接口一）、`obs_corrector/`（接口三）、`common/`（数据集/指标/ONNX 导出）

状态：接口一/接口三**代码已落地**；接口二按决策冻结。完整评估与逐项数据见 [GNSS-RTK-AI-report.md](docs/GNSS-RTK-AI-report.md)。

> **关于模型**：仓库内**不提供可用于生产的预训练权重**，仅有 `src/test/resources/models/` 下的 `dummy_*` 演示模型，用途是契约测试与流水线冒烟，不具备实际改正能力。GNSS 数据在不同环境下分布差异极大（星座组合与可见星几何、遮挡与多路径形态、接收机型号与天线、采样率与截止高度角），在 A 环境训练的模型直接搬到 B 环境通常不成立。**实际使用必须用本机场景数据自行训练模型**，采集 → 组装 → 训练 → 导出 ONNX 的完整流程见 [训练管线说明](rtklib-ai/training/README.md)。

> **验证结论**：经真实北斗恶劣峡谷数据双跑验证：受限于人造标签的物理精度与端到端不可控性，本模块当前仅作保守的方差膨胀（降权）辅助，默认关闭。彻底废除只看固定率的评估模式，真实配对数据见 [AI 评估报告](docs/GNSS-RTK-AI-report.md)。

### rtklib-test — 验证与示例

跨模块端到端验证套件与可运行示例。**不是库**，不被其它模块依赖，仅用于回归与实测。

- **精度验证**：`SppValidationTest` / `RtkValidationTest` / `PppValidationTest` / `PppArValidationTest` / `PppRtkValidationTest`（GNSS 验证数据流水线）
- **集成与自适应**：`RtkPppIntegrationTest`、`AdaptiveWiringE2ETest`、`AdaptiveEngineHarnessTest`
- **链路检查**：`SsrAccessCheckTest`（CAS SSR 接入）、`ReplayBdsSigCheckTest`（录制回放）
- **算法回归**：`PppArStatLatchTest`（FIX 锁存）、`PppInitBiasSanityTest`（初始模糊度门限）、`CJavaEpochDiffTest`（与 C 版逐历元对照）、`ShortBaselineOptSweepTest`（短基线开关扫描）
- **示例程序**（`src/main`）：`PppRtkDemo`、`PppRtkPipeline`、`PppRtkRealtimeDemo`、`PppRtkSsrReplay`、`PppBdsPipeline`、`PppArDiag`、`Sp3Diag`

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
| [算法修复档案](docs/ALGORITHM_FIX_LOG.md) | FIX-ALG-001~062 逐条 C 依据 / 根因 / 验证（改算法前必读） |
| [测试用例与数据指南](docs/DATA_GUIDE.md) | 测试规范、测试类总览、数据依赖、优化矩阵测试用例、上传规范 |
| [轨道模块技术参考](docs/ORBIT_MODULE_REFERENCE.md) | TLE解析、SGP4/SDP4传播、六根数转换、二体传播 |
| [开发路线图](docs/ROADMAP.md) | 各模块当前状态、未完成事项（按需激活/冻结/WONTFIX）、暂缓与恢复条件；历史梯队记录归档于 [docs/archive/ROADMAP_v3.3.0.md](docs/archive/ROADMAP_v3.3.0.md) |
| [自适应引擎设计](docs/gnss-adaptive-design-v3.md) | 场景判定、参数预设、安全机制、日志体系、包结构、测试策略 |

### 项目级

| 文档 | 说明 |
|------|------|
| [终期技术摘要](docs/PROJECT_FINAL_SUMMARY.md) | 一句话定位 + 精度实测汇总 + FIX-ALG 闭环索引 + HAS/IMU/CLAS 扩展路线 |
| [变更日志](docs/CHANGELOG.md) | 版本级变更摘要（当前至 3.5.0） |
| [AI 增强设计](docs/AI_ENHANCEMENT_DESIGN.md) | AI 三接口 SPI 契约、特征定义、训练管线与降级策略（v1.11） |
| [AI 评估报告](docs/GNSS-RTK-AI-report.md) | 接口一/接口三的完整评估结论与逐项实测数据 |
| [发布说明](RELEASE.md) | v3.5.0 发布稿：许可证合规重构、配置项、兼容性、已知问题、验收口径 |
| [SSR 接入手册](docs/SSR_ACCESS_PLAYBOOK.md) | 接入操作与验收（已闭环）：两行改动、五步接入、开关表、验收清单与已知坑 |

### rtklib-product

| 文档 | 说明 |
|------|------|
| [精密产品下载技术参考](docs/PRODUCT_MODULE_REFERENCE.md) | IGS精密产品自动下载、URL优先级、缓存策略、API示例 |

### rtklib-stream

| 文档 | 说明 |
|------|------|
| — | 模块代码含完整中文Javadoc，暂无独立文档；`caster/`、`nmea/` 为 3.3.0 新增独立包 |

### rtklib-otl

| 文档 | 说明 |
|------|------|
| [海潮负荷模块技术参考](docs/OTL_MODULE_REFERENCE.md) | BLQ系数提取、位移时间序列、命令行用法、API示例、验证结果 |

### rtklib-research

| 文档 | 说明 |
|------|------|
| [功能文档](rtklib-research/FEATURES.md) | 全部功能清单：数据结构、因子图因子表、模糊度算法、后端与场景分类、IMU 预积分 |
| [使用文档](rtklib-research/USAGE.md) | 环境要求、FE-GUT 数据集准备、EKF/FGO 后端代码示例、测试运行 |
| [开发文档](rtklib-research/DEVELOPMENT.md) | 模块定位、包级完成度表、核心缺口、FE-GUT 参考项目说明 |

### rtklib-adjust

| 文档 | 说明 |
|------|------|
| [使用说明](docs/ADJUST_USAGE.md) | API 示例、输出字段含义 |
| [技术参考](docs/ADJUST_TECHNICAL_REFERENCE.md) | 数学模型、协方差构造、理论局限性、联合解算可行性评估 |

### rtklib-vrs

| 文档 | 说明 |
|------|------|
| [模块设计文档](docs/VRS_MODULE_DESIGN.md) | 实现状态、VRS 改正算法、RTCM3 编码器设计、MSM 分批发送与锁相指示、测试策略与实测结果、已踩过的坑 |

### rtklib-joint

| 文档 | 说明 |
|------|------|
| [联合RTK设计文档](docs/JOINT_RTK_DESIGN.md) | 观测层联合解算数学模型、状态向量与大气维度划分、分层架构、基站位移监测、实现路线 |
| [joint vs adjust 对比](docs/JOINT_VS_ADJUST_ANALYSIS.md) | 观测层联合 vs 结果层平差的融合层级、故障隔离、FIX 后精度等价性、选型结论 |

### rtklib-networkvrs

| 文档 | 说明 |
|------|------|
| [网络VRS设计文档](docs/NETWORK_VRS_DESIGN.md) | 多站同步、误差分解与空间插值、档 1/档 2 实施计划、4076/IM201 球谐协议（§3.4）、四层验证策略 |

### rtklib-ext-igs

| 文档 | 说明 |
|------|------|
| [IGS SSR v1.00 标准原文](docs/reference/igs_ssr_v1.pdf) | 4076 字段定义（§8.4.1 Table 24~27）、VTEC 球谐模型（§6）、IDF 表（Table 6/7） |
| [网络VRS设计文档 §3.4](docs/NETWORK_VRS_DESIGN.md) | 本项目对该协议的落地解读：位序表、系数个数公式、实测样本核对、IM202 存疑说明 |

### rtklib-ai

| 文档 | 说明 |
|------|------|
| [AI 增强设计](docs/AI_ENHANCEMENT_DESIGN.md) | SPI 契约、特征定义、输出钳位、训练管线与降级策略（v1.11） |
| [AI 评估报告](docs/GNSS-RTK-AI-report.md) | 接口一/接口三评估结论与逐项实测数据 |
| [训练管线说明](rtklib-ai/training/README.md) | 数据集组装、训练与 ONNX 导出流程 |

### rtklib-test

| 文档 | 说明 |
|------|------|
| [验证流水线说明](rtklib-test/src/test/java/org/rtklib/java/test/gnssvalidation/GNSS_VALIDATION_PIPELINE.md) | GNSS 验证数据集组织、执行方式与结果口径 |
| [测试开发指南](docs/TEST_DEVELOPMENT_GUIDE.md) | 测试开发标准流程与常见模式 |
| [测试用例与数据指南](docs/DATA_GUIDE.md) | 测试类总览、公共测试数据清单、大文件仓库 |

## 使用建议

本项目各模块按影响面和风险分为两类，使用者可根据自身需求选择对应的集成策略：

### 第一类：数据解析/辅助型

> 直接使用，不会有实质影响，结论明确。有问题提交 Issue 或自行修改后提交 PR。

不涉及定位解算核心逻辑，侧重点在数据获取、格式解析、辅助改正数计算。集成到业务系统时**不直接承担定位精度风险**——`rtklib-vrs` / `rtklib-networkvrs` 产出的是虚拟观测值，风险转嫁给下游 RTK，故其风险单列为「中」：

| 模块 | 功能 | 集成风险 |
|------|------|----------|
| `rtklib-stream` | NTRIP客户端 + NTRIP Caster服务端 + 串口通讯 | 极低 — 纯数据流，输出为RTCM字节流 |
| `rtklib-product` | IGS精密产品自动下载 | 极低 — 纯文件下载，与定位算法解耦 |
| `rtklib-ext-igs` | IGS 专有 4076 解码（公共头部 + IM201 球谐 + VTEC/STEC 合成） | 低 — 产出电离层延迟改正，4076 卫星级数值未解析，未接入定位链路 |
| `rtklib-otl` | 海潮负荷BLQ系数/位移 | 极低 — mm级改正数，对定位无实质性负面风险 |
| `rtklib-adjust` | 多基线间接平差 | 低 — 输入为已解算的RTK基线，不修改定位核心 |
| `rtklib-vrs` | 单基站VRS虚拟观测值生成 | 中 — 输出虚拟观测值供下游RTK，编码/锁相错误会直接导致流动站无法FIX |
| `rtklib-networkvrs` | 多基站网络VRS（档 1/2） | 中 — 依赖 `rtklib-vrs` 编码器，改正插值正确性影响30km以上基线 |
| `rtklib-test` | 验证套件与示例 | 无 — 不被其它模块依赖，仅本地运行 |
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
| `rtklib-joint` | 多基线观测层联合 RTK | **高** — 独立解算引擎，无共享故障隔离；状态向量与大气维度划分已按 §2.1.1 落地（2026-11-04），代码无待实施项，按需激活（真实场景调优等场景驱动） |

涉及 `pntpos/`（SPP）、`rtkpos/`（RTK）、`ppp/`（PPP）、`ppprtk/`（PPP-RTK）、`kalman/`（EKF）、`ambiguity/`（LAMBDA）六个子模块。辅助模块（rtcm/rinex/ephemeris/coord/time/ionosphere/troposphere/common）属于第一类，无风险。

1. 确认自己的数据场景与本项目已验证的场景匹配（基线长度、电离层活跃度、遮挡程度等）
2. 用自己的已知参考数据跑通本项目的完整流程，对比预期结果
3. 信任且测试通过 → 直接集成使用；同时关注 [优化功能矩阵](docs/CORE_OPTIMIZATION_MATRIX.md) 按场景选择优化开关
4. 不确定 → 作为对比工具保留，与原系统并行对比，积累足够置信度后再替换
5. 发现缺陷 → 分析根因后提交 Issue，或修改后提交 PR

> **原则**：本项目定位算法经过真实数据验证（详见开发状态），但所有集成仍建议以自有测试数据验证通过为前提。不盲目信任任何代码的精度声明。

## 场景置信度

本项目按**实测验证程度**将支持场景分为两级，使用者应据此评估集成风险。

### 已实测验证

以下场景已有公开/私有数据端到端验证（与参考解一致，或已记录实测指标）：

| 场景 | 定位模式 | 数据来源 | 验证方式 |
|------|----------|----------|----------|
| 开阔静态 SPP | SPP | MobileGNSS-SPP (G+C) | vs C版参考结果 |
| 城市街道 SPP | SPP | MobileGNSS-SPP (G+C) | vs C版参考结果 |
| BDS RTCM SPP | SPP | 本地RTCM3 (C) | 逐历元成功 |
| 短基线 RTK Kinematic | RTK | RINEX2.10 GPS base+rover | vs C版参考结果 |
| 多系统 RTK Static | RTK-Static | GREAT-PVT RTKFLT (G+E+C+R+J) | 端到端解算 |
| 中长基线 RTK Static (~42 km) | RTK-Static | 香港SatRef HKWS+HKSL 2024 DOY060 30s | 链路级：2880/2880 历元全通，FIX 率 1.3%，FIX-only 2D RMS 0.106 m |
| 动态车载 RTK | RTK-Kinematic | PPC-Dataset Tokyo/Nagoya（车载 5Hz）+ UrbanNav Tokyo 2018（车载 10Hz） | Fix/Float 率与 H.RMSE 对照 RTKLIB-EX（`RtkBenchmarkTest`、`DynamicRtkOptTest`） |
| BDS RTK (RTCM) | RTK | 本地RTCM3 base+rover | 私有数据验证 |
| 多系统 PPP Static | PPP-Static | GREAT-PVT PPPFLT (G+E+C+R) | 端到端解算 |
| PPP + GPT3+VMF3 | PPP | 同上 + CODE产品 | 端到端解算 |
| PPP + IERS2010 | PPP | 同上 | 端到端解算 |
| PPP + ISB/IFCB | PPP | 同上 | 端到端解算 |
| PPP-AR + Fix-and-Hold | PPP-AR | 同上 + WHU UPD(WL/NL) | 端到端解算 |
| PPP-AR 三频 (EWL+WL+NL) | PPP-AR | ALGO 2024 DOY060 + WUM | 固定率 65.7%（1226 历元）/ 2D RMS 3.00 cm（C 参考 3.54 cm） |
| PPP + GPT3 真网格 | PPP | HKWS / HKSL 2018 DOY157 | U RMS 0.806 / 1.662 m，优于 NMF 基线 |
| 实时 NTRIP 流 + MSM7 | SPP | WUH20 实时流（CAS） | 262 帧 CRC 全通过；73 历元 SPP 3D 偏差 1.89 m |
| 实时 PPP-RTK 链路 | PPP-RTK | CAS IGS 实时 SSR 流（录制 → 离线回放） | 链路级：SSR 改正链生效、ENU 与事后产品同量级；AR 受可见卫星数限制未达 FIX |
| 精密产品读取 | — | IGS CODE WHU (SP3/CLK/IONEX/OSB/ATX) | 文件解析验证 |
| 多基线间接平差 | Adjust | 本地RTCM3 2基站9小时 | σ₀+Baarda验证 |

### 框架已实现，待场景数据验证

以下功能代码已实现且通过单元测试，但缺少真实场景数据端到端验证。按数据可得性分三级：

**数据公开可得，缺集成验证**（优先级高，可自行下载）：

| 场景 | 定位模式 | 数据获取方式 |
|------|----------|-------------|
| PPP-AR 24h 静态精度 | PPP-AR | IGS 24h RINEX + CODE MGEX SP3/CLK + WHU UPD，CDDIS 免费注册下载 |
| 超长基线 RTK (>100 km) | RTK-Static | IGS 双站 RINEX（如 WTZR+BRUX ~600 km），CDDIS 免费注册下载；10~100 km 中长基线已做链路级验证（见上表），>100 km 仍需梯度参数与精细大气建模 |

> 下载脚本：`test-data/download-igs-data.ps1`（CDDIS 需先配置 [NASA Earthdata Login](https://urs.earthdata.nasa.gov/users/new)）

**数据部分可得，需筛选适配**（工程量中等）：

| 场景 | 定位模式 | 数据获取方式 |
|------|----------|-------------|
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

**Phase 1 完成 — 基础能力建设**：RTKLIB C 版核心算法全移植（SPP/RTK/PPP/PPP-RTK）、LAMBDA、RTCM3、RINEX3、Kalman、精密产品、NTRIP、轨道模块、海潮负荷。

**Phase 2 进行中 — 高级优化与独立模块**（基本完成）：

| 模块 | 状态 | 测试方式 |
|------|------|----------|
| SPP 单点定位 + EKF + 抗差 + 零速约束 | 通过 | 真实RINEX数据 vs C版参考结果 |
| RTK 相对定位 + 6项高级优化 | 通过 | 真实RINEX数据 vs C版参考结果 |
| PPP + PPP-AR + 5项高级优化 | 通过 | 真实RINEX+精密星历数据 |
| PPP-RTK 基础（SSR改正） | 通过 | SSR 解码→`nav.ssr`→`SsrCorrector`→`ppprtkos()` 链路；解码经公开 SSR 样本单测（`SsrDecodeSmokeTest`） |
| Compact SSR / CLAS / HAS 解码 | 通过 | 单元测试（CLAS MT4073 子类型 1-12、HAS MT1~MT9）/ CSSRlib 交叉验证 |
| 多基线间接平差 | 通过 | 真实RTCM实测数据 |
| VRS 单基站生成 | 通过 | 真实双站 RTCM3（239 历元/15 s），VRS@基站位置 P/L 逐位一致 |
| 网络 VRS 多基站 | 部分实现（档 1/档 2） | 档 1（IDW）/档 2（网解+误差分解+FKP）已实现并真实三站验证；BRDC 下仅宽巷固定、窄巷待 SSR/精密星历；档 3 MAC 冻结 |
| 联合 RTK（joint） | 部分实现 | 单/双基线真实数据 FIX 91.5%（与 SBS 91.2% 持平） |
| IGS SSR 4076 解码 | 部分完成（IM201 闭环） | 公共头部 + 子类型识别 + IM201 球谐 + VTEC/STEC 合成；4076 卫星级数值未解析，VTEC 未接入定位链路 |
| 因子图优化 FGO + IMU 预积分 | 通过 | FE-GUT 仿真数据集 |
| 轨道模块 + 海潮负荷 | 通过 | Vallado（轨道）/ 4 站 BLQ 数值基线逐字节一致（海潮） |
| 自适应引擎框架 | 进行中 | 14类实现，编译通过；待集成测试验证 |
| AI 增强（`rtklib-ai`） | 部分完成；代码已落地；仓库仅含测试用演示模型 | SPI 注入不启用即零影响；接口二冻结；各环境需自行训练模型；详见 [AI 评估报告](docs/GNSS-RTK-AI-report.md) |

剩余：PPP-RTK 区域大气约束、完好性保护级（HPL/VPL）、ARAIM、固定解完好性增强——均属研究深化方向，**无上游场景驱动，定版不再投入（WONTFIX）**。

**梯队 0/1 完成 — 性能基准与工程质量**（已完成 2026-10-12）：JMH 微基准 + 端到端计时基线、RtkCore/PppCore 工作数组复用、SLF4J 日志统一、异常捕获改进、SSR 对流层直接改正、34 个关键算法单元测试。

**梯队 2/3 已关闭**：3A Kalman 优化与 3.5B null 消除经 JMH 基准实测后放弃；CI 真实数据回归、CLAS STEC 多项式、场景验证等依赖外部数据/条件，随 v3.1.0 封存整体 WONTFIX。恢复条件见 [开发路线图 §2](docs/ROADMAP.md)。

**项目设计不做**（功能定义不符合本项目定位，非缺口、非暂缓）：TCP 客户端、TCP/UDP 服务端、接收机原始协议（u-blox/NovAtel 等）、NMEA 输出、GPX/KML 输出、大地水准面/基准转换。详见 [实现差异文档第14章](docs/RTKLIB_Differences.md)。

> **注**：NTRIP Caster 已不在上表内——`rtklib-stream` 于 v3.3.0 实现了 Caster 服务端（Sourcetable/订阅/鉴权/GGA 上行），这是相对 C 版的能力扩展，非移植项。

## 附录：开源引用与致谢（Attribution）

本项目的代码归属分两级标注，源文件头部统一声明：

- `[JAVA-ONLY 原创]`：本项目从零推导/自行设计的实现，不依赖任何现成开源代码；
- `[REF: 外部项目]`：核心算法思想、公式推导或解码逻辑参考/移植自外部开源项目。

`[REF: ...]` 标注可直接在源码中检索（`grep "[REF: "`），下表与源码标注一一对应。

### A.1 代码来源清单

| # | 项目 | 作者 / 团队 | 许可证 | 参考模块（Java 侧） | 引用方式 |
|---|---|---|---|---|---|
| 1 | [RTKLIB 2.5.0](https://github.com/tomojitakasu/RTKLIB) | Tomoji Takasu | BSD-2-Clause | 全 core 主干：`rtkpos.c`（[RtkCore](rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java)）、`ppp.c`（[PppCore](rtklib-core/src/main/java/org/rtklib/java/ppp/PppCore.java)）、`pntpos.c`/`rtkcmn.c`/`ephemeris.c`/`ionosphere.c`/`troposphere.c`/`rtcm3.c`/`solution.c`/`postpos.c`/`tides.c`；`preceph.c` 的 `readdcb()` → [DcbReader](rtklib-core/src/main/java/org/rtklib/java/ephemeris/DcbReader.java) | 逐行移植 |
| 2 | [RTKLIB-EX (demo5) 2.5.1](https://github.com/rtklibexplorer/RTKLIB) | rtklibexplorer | BSD-2-Clause | `ppp.c:1258-1261` 的 `xp/Pp` 迭代语义；`rtkpos.c` 的 `manage_amb_LAMBDA()` 多次尝试框架 → [RtkOptimizationsPartialAR](rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkOptimizationsPartialAR.java)；部分 SSR 电文补丁 → [Rtcm](rtklib-core/src/main/java/org/rtklib/java/rtcm/Rtcm.java) | 逐行移植 / 框架参考 |
| 3 | [cssrlib](https://github.com/hirokawa/cssrlib) | Rui Hirokawa | MIT | RTCM 1043 SBAS 星历字段布局（`rtcm.py` 的 `decode_sbs_eph`）→ [Rtcm.decodeType1043](rtklib-core/src/main/java/org/rtklib/java/rtcm/Rtcm.java) | 字段布局移植 |
| 4 | [python-sgp4](https://github.com/brandon-rhodes/python-sgp4) | Brandon Rhodes | MIT | `propagation.py` / `io.py` / `sgp4init` → [Sgp4Propagation](rtklib-core/src/main/java/org/rtklib/java/orbit/Sgp4Propagation.java)、[TleParser](rtklib-core/src/main/java/org/rtklib/java/orbit/TleParser.java)、[Sgp4Time](rtklib-core/src/main/java/org/rtklib/java/orbit/Sgp4Time.java) | 逐行移植 |
| 5 | [Orekit 12](https://gitlab.orekit.org/orekit/orekit) | CS GROUP（原 CS Systèmes d'Information） | Apache-2.0 | `KeplerianParametersConverter` / `KeplerianAnomalyUtility` 的 `rv2coe`/`coe2rv`/`solveKepler`/双曲开普勒求解 → [OrbitalMechanics](rtklib-core/src/main/java/org/rtklib/java/orbit/OrbitalMechanics.java) | 纯数学核心抽取（不引入运行时依赖） |
| 6 | [PRIDE-PPPAR](https://github.com/WU-PRIDE/PRIDE-PPPAR) | 武汉大学 PRIDE Lab（张传银 等） | **GPL-3.0** | ① WL/NL 门限与弧段机制（`nl_maxdev`、'Critical search' 的 `minsav`、`fixamb_search` 的取整搜索思想）→ [PppAmbFix](rtklib-core/src/main/java/org/rtklib/java/ppp/PppAmbFix.java)；② OTL 四支 `.f90` → **曾逐行移植，已整体废弃重写**（详见 [A.4](#a4-rtklib-otl-的独立性说明已处置)） | ① **仅思想参考**（未复制源码，数据结构/门限落点/控制流均不同）；② 旧版属衍生作品，**已删除并重写**，当前实现为独立实现 |
| 7 | [GREAT-PVT](https://github.com/GREAT-WHU/GREAT-PVT) | GREAT-WHU（武汉大学） | **GPL-3.0** | `gambfix` 的 EWL→WL→NL 分级与级间约束传递思想（`gambiguity.cpp` 的 `processBatch(mode)`、虚拟观测段）→ [RtkOptimizationsCascadeAR](rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkOptimizationsCascadeAR.java) | **仅思想参考**（未复制源码） |

### A.2 数据与标准引用（非代码来源）

以下内容为公开发布的数据产品或技术标准，其数值/格式不构成代码来源，不产生许可证传染：

| 数据 / 标准 | 提供方 | 用途 |
|---|---|---|
| FES2004 海潮网格 | LEGOS / CNES | 海潮负荷（[Fes2004Loader](rtklib-otl/src/main/java/org/rtklib/java/otl/Fes2004Loader.java) 解析自研） |
| IERS Conventions 2010 负荷 Love 数 | IERS | 负荷潮系数（[LoveNumberLoader](rtklib-otl/src/main/java/org/rtklib/java/otl/LoveNumberLoader.java) 解析自研） |
| Farrell (1972) 负荷 Love 数表 | W. E. Farrell | 点负荷格林函数（[FarrellLoveNumbers](rtklib-otl/src/main/java/org/rtklib/java/otl/FarrellLoveNumbers.java) 常量封装自研） |
| BLQ 格式（IERS 约定） | IERS | [BlqWriter](rtklib-otl/src/main/java/org/rtklib/java/otl/BlqWriter.java) 写出格式 |
| RTCM 3.x 标准电文（含 1043/SSR） | RTCM SC-104 | 编解码字段定义（公开协议，非代码来源） |
| FCB / OSB 产品（WHU、CNES、IGS-MGEX） | WHU / CNES / IGS | PPP-AR 偏差改正（[FcbReader](rtklib-core/src/main/java/org/rtklib/java/ephemeris/FcbReader.java) 解析自研） |
| ASTRO5 天文幅角（R. D. Ray, NASA/GSFC, 2003） | NASA/GSFC 公开文献 | 潮汐天文幅角公式 |
| IERS Conventions 2010 表 5.3 基本天文幅角 | IERS | 月球/太阳平均黄经多项式（[AstronomicalArgument](rtklib-otl/src/main/java/org/rtklib/java/otl/AstronomicalArgument.java) 独立实现） |
| Doodson (1921) 潮汐幅角编码 / Schureman (1958) 交点潮调制 | 公开文献 | 分潮相位合成与 f、u 调制（[DoodsonArgument](rtklib-otl/src/main/java/org/rtklib/java/otl/DoodsonArgument.java)、[NodalCorrection](rtklib-otl/src/main/java/org/rtklib/java/otl/NodalCorrection.java) 独立实现） |
| GPT3 5°网格（TU Wien） | TU Wien | 气压/温度/比湿先验值（[Gpt3GridReader](rtklib-core/src/main/java/org/rtklib/java/ephemeris/Gpt3GridReader.java) 按 Landskron & Böhm (2018) 模型独立实现） |
| Landskron & Böhm (2018) VMF3/GPT3 模型；Askne & Nordius (1987) 水汽压公式 | 公开文献 | 高程归约与网格插值公式（同上，独立实现） |

### A.3 BSD-2-Clause 兼容性校验

本项目整体采用 BSD-2-Clause。逐项判定：

| 上游许可证 | 兼容性 | 依据 | 分发义务 |
|---|---|---|---|
| BSD-2-Clause（RTKLIB 2.5.0 / RTKLIB-EX） | 兼容 | 同族宽松许可 | 保留原版权声明、条件与免责声明 |
| MIT（cssrlib / python-sgp4） | 兼容 | MIT 为宽松许可，可并入 BSD-2-Clause 作品 | 保留 MIT 版权与许可声明 |
| Apache-2.0（Orekit 12） | 兼容 | 宽松许可，允许再分发与再许可 | 保留版权/NOTICE；若上游含 NOTICE 文件须随附 |
| GPL-3.0（PRIDE-PPPAR） | 当前无冲突 | ①WL/NL 门限与弧段仅参考算法思想与参数语义，未复制源码；②OTL 四支 `.f90` 的逐行移植版本已整体删除并按公开标准重写（见 [A.4](#a4-rtklib-otl-的独立性说明已处置)） | 无；若未来再次复制其源码则转为不兼容 |
| GPL-3.0（GREAT-PVT） | 当前无冲突 | 仅参考算法思想，未复制源码，不构成衍生作品 | 无；若未来复制其源码则转为不兼容 |

**结论**：RTKLIB 2.5.0、RTKLIB-EX、cssrlib、python-sgp4、Orekit 五类来源均与 BSD-2-Clause 兼容；PRIDE-PPPAR 的 WL/NL 部分与 GREAT-PVT 仅作思想参考，不含逐行移植代码；PRIDE-PPPAR 的 OTL 四支 `.f90` 逐行移植版本已删除并重写为独立实现，当前不存在许可证冲突。

### A.4 `rtklib-otl` 的独立性说明（已处置）

`rtklib-otl` 曾存在按外部 Fortran 源码逐行移植的早期版本。该版本已整体废弃并重写：

- 现实现以 IERS Conventions (2010) 第 7 章的海洋潮汐负荷球谐合成公式、IERS 2010 表 5.3 的基本天文幅角、Doodson (1921) 幅角编码、Schureman (1958) 交点潮调制级数、Farrell (1972) 负荷 Love 数定义为依据，属公开物理公式的独立实现；
- 代码组织改为面向对象分层（测站几何、球谐基函数表、幅角值对象、调制量值对象、复数振幅），不再与任何 `.f90` 文件存在函数名、变量名、数组打包索引或循环结构的对应关系；
- 正确性以数值等价性验证代替结构对照：4 站 BLQ 振幅/相位与参考基线逐字节一致（差异 0.0000），位移时间序列差异 < 1e-10 mm。

因此 `rtklib-otl` 当前归属为 `[JAVA-ONLY 原创]`，可按 BSD-2-Clause 分发。

### A.5 原创模块归属（对照）

以下模块为纯 `[JAVA-ONLY 原创]`，**无任何外部开源代码依赖**，归属权不得错配给 RTKLIB-EX 或其他项目：

| 模块 | 原创内容 |
|---|---|
| `rtklib-vrs` | 单基站 VRS 生成、MSM 编码、锁相指示跨历元维护（`lockCnt`/`lockEpoch`/`msmEpochSeq`） |
| `rtklib-otl` | 海潮负荷球谐合成、完全归一化 Legendre 函数与导数、天文幅角/Doodson 编码/交点潮调制、测站几何与重力参数（依据 IERS 2010、FES2004、Farrell 1972 公开公式独立实现） |
| `rtklib-networkvrs` | 多站同步、双差观测构建、网解引擎、基线窗口求解与级联固定、误差分解、FKP/IDW 空间插值、MAC 改正构建 |
| `rtklib-adjust` | 多基线平差引擎、网络平差、基站诊断、残差白度检验（自相关系数 + 游程检验组合） |
| core 内 JAVA-ONLY 扩展 | 死循环熔断（FIX-ALG-037/044）、状态清理（周跳/历元状态复位）、AI 钩子（`AiHooks`/`FixValidator`）、`RtkOptimizations` 系列可选分支、FCB 读取 |

> core 主干仍以 RTKLIB 2.5.0 C 版为对齐基准，`[REF: RTKLIB 2.5.0 C]` 与上述 JAVA-ONLY 扩展在同一文件中并存时，注释已逐项区分。

> C版对齐状态、方法命名规则、测试验证状态详见 [技术文档](docs/RTKLIB_JAVA_TECHNICAL_REFERENCE.md) 第16~18章。

## License

本项目基于 RTKLIB 原始代码移植，遵循 RTKLIB 的 BSD-2-Clause 许可证。第三方来源的许可证与兼容性判定见上节 [A.3](#a3-bsd-2-clause-兼容性校验)。