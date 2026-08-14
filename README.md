# RTKLIB-Java

RTKLIB 的 Java 移植版本，基于 [RTKLIB 2.5.0](https://github.com/tomojitakasu/RTKLIB) 开源 GNSS 定位库。

## 项目简介

RTKLIB-Java 将 C 语言编写的 RTKLIB 核心 GNSS 定位算法移植为 Java 实现，提供与原版功能对齐的卫星定位计算能力。项目定位为**算法引擎库（Library）**而非独立软件，可嵌入 Java 应用中实现 GNSS 数据处理与定位解算。

> **功能边界**：Java版专注核心定位算法（SPP/RTK/PPP），不包含C版的网络通信（NTRIP/TCP/串口）、接收机原始协议（u-blox/NovAtel等）、NMEA输出等功能。详见 [实现差异文档第14章](docs/RTKLIB_Differences.md)。

### 支持的定位模式

| 模式 | 常量 | 说明 |
|------|------|------|
| SPP | `PMODE_SINGLE` | 单点定位（伪距） |
| DGPS | `PMODE_DGPS` | 差分 GPS |
| Static | `PMODE_STATIC` | 静态相对定位 |
| Kinematic | `PMODE_KINEMA` | 动态相对定位 |
| Moving-Base | `PMODE_MOVEB` | 移动基线 |
| Fixed | `PMODE_FIXED` | 固定位置 |
| PPP Kinematic | `PMODE_PPP_KINEMA` | PPP动态定位 |
| PPP Static | `PMODE_PPP_STATIC` | PPP静态定位 |
| PPP Fixed | `PMODE_PPP_FIXED` | PPP固定坐标 |

### PPP 关键改正项

| 改正项 | 读取器 | 文件格式 | 影响量级 |
|--------|--------|----------|----------|
| 天线相位中心偏差 (PCV) | `PcvReader` | ANTEX (.atx), NGS (.pcv) | 10-15 cm |
| 差分码偏差 (DCB) | `DcbReader` | BIA, BSX, DCB | 几十 cm（伪距） |
| 海潮负荷 (OTL) | `OtlReader` | BLQ | 1-5 cm（沿海站） |
| 固体潮 | `Tides.tidedisp()` | 内置模型 | ~30 cm |
| 极潮 | `Tides.tidePole()` | ERP文件 | ~1-2 cm |

### 数据输入格式

| 格式 | 读写 | 说明 |
|------|------|------|
| RTCM 3 | 解码 ✅ | MSM4/5/6、多系统星历、SSR改正 |
| RINEX 3.x | 读写 ✅ | OBS/NAV/CLK/SP3 |
| 接收机原始协议 | ❌ | u-blox/NovAtel/Septentrio等，需先用convbin转换 |
| NMEA 0183 | ❌ | 仅定义常量，无编解码实现 |

### 库级参数体系

作为库而非软件，RTKLIB-Java 在 RTKLIB 原有参数基础上增加了库级参数，替代原版通过配置文件区分的处理模式：

| 参数 | 字段 | 可选值 | 默认值 | 说明 |
|------|------|--------|--------|------|
| 处理模式 | `procmode` | `PROCMODE_REALTIME(0)` / `PROCMODE_POST(1)` | `PROCMODE_POST` | 实时流 / 事后处理 |
| 参考站位置模式 | `refposmode` | `REFPOS_FIXED(0)` / `REFPOS_SPP_AVERAGE(1)` / `REFPOS_RTCM(2)` | `REFPOS_FIXED` | 固定值 / SPP均值 / RTCM动态 |

## 文档

| 文档 | 说明 |
|------|------|
| [使用指南](docs/USAGE_GUIDE.md) | 各功能模块使用方法、API 示例、输出字段含义 |
| [矩阵存储参考](docs/MATRIX_DIMENSION_REFERENCE.md) | Kalman 滤波矩阵维度、存储约定及运算差异 |
| [优化介绍](docs/RTK_Extra_Optimizations.md) | Java 版额外优化项（C 版没有的），独立开关控制 |
| [技术文档](docs/RTKLIB_JAVA_TECHNICAL_REFERENCE.md) | 关键数据结构、状态索引、常量定义及已知问题 |
| [实现差异](docs/RTKLIB_Differences.md) | Java 版与 C 版的有意差异说明 |

## 模块结构

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

## 核心调用链

```
RtkPos.rtkpos()                    ← 外层入口（历元循环）
  └── RtkCore.rtkpos()             ← 核心入口（单历元处理）
        ├── PntPos.pntpos()        ← SPP单点定位
        │     ├── EphModel.satposs()   ← 卫星位置计算
        │     ├── SppCore.estpos()     ← 最小二乘定位
        │     ├── PntPos.raimFde()     ← RAIM故障检测与排除
        │     └── PntPos.estvel()      ← 多普勒速度估计
        └── RtkCore.relpos()       ← RTK相对定位
              ├── Kalman滤波
              ├── 双差观测值
              └── LAMBDA模糊度固定
PppProcessor.processRinex()        ← PPP事后处理入口
  └── PppCore.pppos()              ← PPP核心入口
        ├── PntPos.pntpos()        ← SPP初始化位置
        ├── EphModel.satposs()     ← 精密卫星位置
        ├── PppCore.udstate()      ← 状态更新（位置/钟差/对流层/模糊度）
        ├── PppCore.corrMeas()     ← 观测值修正（IFLC组合/码偏差）
        └── Kalman滤波             ← EKF滤波更新
```

## 环境要求

- Java 17+
- Maven 3.6+

## 依赖

| 依赖 | 版本 | 用途 |
|------|------|------|
| EJML | 0.43.1 | 矩阵运算（最小二乘、Kalman滤波） |
| SLF4J | 2.0.9 | 日志接口 |
| Logback | 1.4.14 | 日志实现 |
| JUnit 5 | 5.10.1 | 单元测试 |

## 构建

```
mvn compile
```

## 测试

```
mvn test
```

## 快速使用

> 详细使用方法和输出字段含义见 [使用指南](docs/USAGE_GUIDE.md)。

### SPP 单点定位

```java
// RTCM 文件 SPP
PrcOpt opt = SppProcessor.createDefaultOpt();
SppProcessor spp = new SppProcessor(opt);
SppProcessor.SppResult result = spp.process("data.rtcm3");

// RINEX 文件 SPP
RinexSppProcessor.SppResult result =
    RinexSppProcessor.processRinex("ROVER.obs", "ROVER.nav");

for (SolData sd : result.solutions) {
    Position llh = sd.getPosition(CoordType.LLH);
    if (llh != null) {
        System.out.printf("Lat=%.8f Lon=%.8f H=%.3f%n", llh.v1, llh.v2, llh.v3);
    }
}
```

### RTK 相对定位

```java
// RTCM 文件 RTK
PrcOpt opt = RtkProcessor.createDefaultOpt();
opt.modear = Constants.ARMODE_FIXHOLD;
RtkProcessor rtk = new RtkProcessor(opt);
RtkProcessor.RtkResult result = rtk.process("rover.rtcm3", "base.rtcm3");

// RINEX 文件 RTK
RtkProcessor.RtkResult result =
    RinexRtkProcessor.processRinex("ROVER.obs", "BASE.obs", "NAV.nav", opt);

for (SolData sd : result.solutions) {
    if (sd.status == SolutionStatus.FIX) {
        System.out.println("Fixed solution");
    }
}
```

### PPP 精密单点定位

```java
PrcOpt opt = PppProcessor.createDefaultOpt();
PppProcessor ppp = new PppProcessor(opt);
PppProcessor.PppResult result =
    ppp.processRinex("rover.obs", "rover.nav", "igs.sp3", "igs.clk");
```

### RTCM 数据解码

```java
// 回调模式（推荐）- 自动合并历元，输出字段语义统一
RtcmCallbackDecoder decoder = new RtcmCallbackDecoder(handler);
decoder.feed(fileData, 0, fileData.length);
decoder.finish();

// 底层模式 - 逐字节解码
Rtcm rtcm = new Rtcm();
int pos = 0;
while (pos < data.length) {
    int consumed = rtcm.input(data, pos, data.length - pos);
    if (consumed > 0) { pos += consumed; }
    else { pos++; }
}
```

### RTCM 转 RINEX

```java
RtcmFileToRinexConverter.convertFile("data.rtcm3", 3.05, "D:/output", "ROVER");
```

## 参考来源

- [RTKLIB 2.5.0](https://github.com/tomojitakasu/RTKLIB) - 原始 C 语言实现
- [RTKLIB Manual](http://www.rtklib.com/rtklib_document.htm) - 算法原理与使用说明

> C版对齐状态、方法命名规则、测试验证状态详见 [技术文档](docs/RTKLIB_JAVA_TECHNICAL_REFERENCE.md) 第16~18章。

## License

本项目基于 RTKLIB 原始代码移植，遵循 RTKLIB 的 BSD-2-Clause 许可证。