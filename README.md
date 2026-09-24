# RTKLIB-Java

RTKLIB 的 Java 移植版本，基于 [RTKLIB 2.5.0](https://github.com/tomojitakasu/RTKLIB) 开源 GNSS 定位库。

项目定位为**算法引擎库（Library）**而非独立软件，可嵌入 Java 应用中实现 GNSS 数据处理与定位解算。

> **功能边界**：Java版专注核心定位算法（SPP/RTK/PPP），不包含C版的网络通信（NTRIP/TCP/串口）、接收机原始协议（u-blox/NovAtel等）、NMEA输出等功能。详见 [实现差异文档第14章](docs/RTKLIB_Differences.md)。

## 模块

### rtklib-core — 核心定位算法

支持 SPP（米级）、RTK（厘米~毫米）、PPP（分米~厘米）三种定位模式，数据入口支持 RTCM3 实时流和 RINEX 3.x 事后文件以及RTCM解码。

| 定位模式 | 常量 | 精度 | 数据入口 |
|----------|------|------|----------|
| SPP | `PMODE_SINGLE` | 米级 | RTCM3 / RINEX |
| RTK | `PMODE_KINEMA` / `PMODE_STATIC` / `PMODE_MOVEB` | 厘米~毫米 | RTCM3 / RINEX |
| PPP | `PMODE_PPP_KINEMA` / `PMODE_PPP_STATIC` | 分米~厘米 | RTCM3 / RINEX |

核心包结构：

```
org.rtklib.java
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
├── rinex/         RINEX 文件读写与处理
├── rtcm/          RTCM 数据解码
├── rtkpos/        RTK 相对定位核心（含周跳检测、潮汐改正、高级模糊度固定优化）
├── time/          时间系统（GPS时、UTC转换）
├── trace/         追踪日志系统（RtkTrace/PppTrace）
└── troposphere/   对流层延迟模型
```

RTK高级模糊度固定优化（v2.2.1新增，默认关闭，通过`RtkConfig`开关控制）：

| 优化项 | 开关 | 来源 | 说明 |
|--------|------|------|------|
| 逐级模糊度固定(EWL→WL→NL) | `enableCascadeAR` | GREAT-PVT | 多频逐级约束，提升固定连续性 |
| 精细化残差编辑与周跳检测 | `enableResidualEdit` | PRIDE | 残差跳变+P-C一致性+弧段筛查 |
| 部分模糊度固定(Partial AR) | `enablePartialAR` | GREAT-PVT | 剔除劣质维度，保留可信子集 |
| Bootstrapping成功率联合判据 | `enableBootstrapping` | GREAT-PVT | Ratio的互补判据 |
| BDS码偏差改正(Wanninger) | `enableBdsCodeBias` | PRIDE | GEO/IGSO/MEO码偏差改正 |
| 参数类型级自适应过程噪声 | `enableParamTypeNoise` | PRIDE | ZTD/电离层随机游走建模 |

轨道模块（v2.2.0新增）：TLE解析、SGP4/SDP4轨道传播、轨道六根数转换、二体传播。验证：Vallado标准14用例全通过，86颗真实TLE卫星0失败。详见 [轨道模块技术参考](docs/ORBIT_MODULE_REFERENCE.md)。

### rtklib-product — 精密产品下载

IGS/MGEX精密产品自动下载器，下载规则对齐PRIDE-PPPAR v3.2 `pdp3.sh`。支持SP3/CLK/ERP/BIA/OBX/FCB/UPD/OSB/DCB/VMF3/GPT3共11类产品，FTPS/FTP/HTTPS多协议多镜像源自动回退、本地缓存和GZ解压。

- **多镜像源回退**：bdspride.com → igs.ign.fr → igs.gnsswhu.cn，自动切换
- **PPP-AR专用**：FCB宽窄巷偏差、UPD非校准相位延迟
- **对流层产品**：VMF3映射函数网格、GPT3 5°网格
- **偏差产品**：OSB观测特定偏差、DCB差分码偏差
- **离线模式**：仅使用本地缓存，不发起网络请求

详见 [精密产品下载技术参考](docs/PRODUCT_MODULE_REFERENCE.md)。

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

## 文档

### rtklib-core

| 文档 | 说明 |
|------|------|
| [使用指南](docs/USAGE_GUIDE.md) | 各功能模块使用方法、API 示例、输出字段含义 |
| [技术文档](docs/RTKLIB_JAVA_TECHNICAL_REFERENCE.md) | 关键数据结构、状态索引、常量定义及已知问题 |
| [实现差异](docs/RTKLIB_Differences.md) | Java 版与 C 版的有意差异说明 |
| [矩阵存储参考](docs/MATRIX_DIMENSION_REFERENCE.md) | Kalman 滤波矩阵维度、存储约定及运算差异 |
| [优化介绍](docs/RTK_Extra_Optimizations.md) | Java 版额外优化项（C 版没有的），独立开关控制 |
| [轨道模块技术参考](docs/ORBIT_MODULE_REFERENCE.md) | TLE解析、SGP4/SDP4传播、六根数转换、二体传播 |

### rtklib-product

| 文档 | 说明 |
|------|------|
| [精密产品下载技术参考](docs/PRODUCT_MODULE_REFERENCE.md) | IGS精密产品自动下载、URL优先级、缓存策略、API示例 |

### rtklib-otl

| 文档 | 说明 |
|------|------|
| [海潮负荷模块技术参考](docs/OTL_MODULE_REFERENCE.md) | BLQ系数提取、位移时间序列、命令行用法、API示例、验证结果 |

### rtklib-adjust

| 文档 | 说明 |
|------|------|
| [使用说明](docs/ADJUST_USAGE.md) | API 示例、输出字段含义 |
| [技术参考](docs/ADJUST_TECHNICAL_REFERENCE.md) | 数学模型、协方差构造、理论局限性、联合解算可行性评估 |

## 环境要求

- Java 17+
- Maven 3.6+

## 构建

```
mvn compile
```

## 测试

```
mvn test
```

## 参考来源

- [RTKLIB 2.5.0](https://github.com/tomojitakasu/RTKLIB) - 原始 C 语言实现
- [RTKLIB Manual](http://www.rtklib.com/rtklib_document.htm) - 算法原理与使用说明

> C版对齐状态、方法命名规则、测试验证状态详见 [技术文档](docs/RTKLIB_JAVA_TECHNICAL_REFERENCE.md) 第16~18章。

## License

本项目基于 RTKLIB 原始代码移植，遵循 RTKLIB 的 BSD-2-Clause 许可证。
