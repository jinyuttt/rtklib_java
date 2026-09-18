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
├── rtkpos/        RTK 相对定位核心（含周跳检测、潮汐改正）
├── time/          时间系统（GPS时、UTC转换）
├── trace/         追踪日志系统（RtkTrace/PppTrace）
└── troposphere/   对流层延迟模型
```

### rtklib-adjust — 多基线间接平差

单历元GNSS多基线间接平差模块。基于高斯-马尔可夫最小二乘模型，对同一历元多条独立RTK基线进行融合平差，输出流动站最优坐标及精度评定（σ₀检验 + Baarda数据探测）。
将多基站定位数据合并为单条基线，进行联合解算。
例如：基站A-测站M，基站B-测站M，结果融合测站M的坐标。

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

## 文档

### rtklib-core

| 文档 | 说明 |
|------|------|
| [使用指南](docs/USAGE_GUIDE.md) | 各功能模块使用方法、API 示例、输出字段含义 |
| [技术文档](docs/RTKLIB_JAVA_TECHNICAL_REFERENCE.md) | 关键数据结构、状态索引、常量定义及已知问题 |
| [实现差异](docs/RTKLIB_Differences.md) | Java 版与 C 版的有意差异说明 |
| [矩阵存储参考](docs/MATRIX_DIMENSION_REFERENCE.md) | Kalman 滤波矩阵维度、存储约定及运算差异 |
| [优化介绍](docs/RTK_Extra_Optimizations.md) | Java 版额外优化项（C 版没有的），独立开关控制 |

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