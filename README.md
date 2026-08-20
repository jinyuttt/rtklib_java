# RTKLIB-Java

RTKLIB 的 Java 移植版本，基于 [RTKLIB 2.5.0](https://github.com/tomojitakasu/RTKLIB) 开源 GNSS 定位库。

## 项目简介

RTKLIB-Java 将 C 语言编写的 RTKLIB 核心 GNSS 定位算法移植为 Java 实现，提供与原版功能对齐的卫星定位计算能力。项目定位为**算法引擎库（Library）**而非独立软件，可嵌入 Java 应用中实现 GNSS 数据处理与定位解算。

> **功能边界**：Java版专注核心定位算法（SPP/RTK/PPP），不包含C版的网络通信（NTRIP/TCP/串口）、接收机原始协议（u-blox/NovAtel等）、NMEA输出等功能。详见 [实现差异文档第14章](docs/RTKLIB_Differences.md)。

### 支持的定位模式

**维度一：定位模式**（`PrcOpt.mode`，必设，默认 `PMODE_SINGLE`）

| 模式 | 常量 | 精度 | 数据入口 | 说明 |
|------|------|------|----------|------|
| SPP | `PMODE_SINGLE` | 米级 | 实时流(RTCM3) / 事后(RINEX) | 伪距单点定位，仅正向 |
| RTK | `PMODE_KINEMA` / `PMODE_STATIC` / `PMODE_MOVEB` | 厘米~毫米 | 实时流(RTCM3) / 事后(RINEX) | 载波相位差分，支持双向 |
| PPP | `PMODE_PPP_KINEMA` / `PMODE_PPP_STATIC` | 分米~厘米 | 实时流(RTCM3) / 事后(RINEX) | 精密单点定位，事后支持双向 |

> DGPS（`PMODE_DGPS`）未独立实现，RTK模式已覆盖。`PMODE_FIXED`/`PMODE_PPP_FIXED`仅输出基线或固定坐标。
>
> **数据格式**：实时流仅RTCM 3（MSM4/5/6），事后仅RINEX 3.x（OBS/NAV/CLK/SP3）。不支持接收机原始协议、NMEA编解码、NTRIP/TCP/串口。

**维度二：状态模式**（不是独立字段，隐含在 `mode` 中，设置 `mode` 即同时确定）

| 状态 | 适用定位 | 对应 mode 常量 | 说明 |
|------|----------|----------------|------|
| 动态 (Kinematic) | SPP | `PMODE_SINGLE` | 接收机运动，逐历元独立估计位置 |
| 动态 (Kinematic) | RTK | `PMODE_KINEMA` | 接收机运动，载波相位差分 |
| 静态 (Static) | RTK | `PMODE_STATIC` | 接收机固定，位置不随历元变化，精度更高 |
| 移动基线 (Moving-Base) | RTK | `PMODE_MOVEB` | 基站也移动的短基线差分 |
| 动态 (Kinematic) | PPP | `PMODE_PPP_KINEMA` | 精密单点，动态 |
| 静态 (Static) | PPP | `PMODE_PPP_STATIC` | 精密单点，静态 |

> `mode` 一个值同时决定定位模式（SPP/RTK/PPP）和状态模式（动态/静态/移动基线），不需要也不存在单独设置状态模式的字段。

**维度三：参考站位置模式**（`PrcOpt.refpos`，仅RTK需要，默认 `POSOPT_POS_XYZ`）

| 模式 | 常量 | 说明 |
|------|------|------|
| 固定LLH | `POSOPT_POS_LLH` | 从`PrcOpt.rb`读取（LLH格式，自动转ECEF） |
| 固定XYZ | `POSOPT_POS_XYZ` | 从`PrcOpt.rb`读取（ECEF格式，默认） |
| SPP均值 | `POSOPT_SINGLE` | 用基准站观测数据SPP定位取均值 |
| RINEX头 | `POSOPT_RINEX` | 从RINEX观测文件头读取近似坐标 |
| RTCM动态 | `POSOPT_RTCM` | 从RTCM 1005/1006消息实时获取 |

**处理方向与双向缓存**

| 方向 | SPP | RTK | PPP | 说明 |
|------|:---:|:---:|:---:|------|
| 正向 | ✅ | ✅ | ✅ | 逐历元向前滤波，实时输出 |
| 批处理双向 | ❌ | ✅ `RtkProcessor` | ✅ `PppProcessor` | `cacheMaxEpochs>0`且历元>10时自动触发 |
| 事后双向 | — | ✅ `PostPosProcessor` | ✅ `PostPosProcessor` | `soltype=SOLTYPE_COMBINED` |
| 实时双向 | ❌ | ✅ `RtkProcessor` | ❌ | 缓存满自动触发反向→合并 |

> SPP为绝对定位，每历元独立求解，正反向结果相同，双向无意义。RTK/PPP批处理双向：`process()`方法中，若`cacheMaxEpochs>0`且正向成功历元>10，自动执行反向→CombinedFilter合并。详见 [使用指南-双向缓存](docs/USAGE_GUIDE.md#9-实时流双向缓存)。

### 模式配置示例

```java
PrcOpt opt = new PrcOpt();

// 维度一+二：定位模式+状态模式（一个mode值同时确定）
opt.mode = Constants.PMODE_SINGLE;        // SPP 动态
opt.mode = Constants.PMODE_KINEMA;        // RTK 动态
opt.mode = Constants.PMODE_STATIC;        // RTK 静态
opt.mode = Constants.PMODE_MOVEB;         // RTK 移动基线
opt.mode = Constants.PMODE_PPP_KINEMA;    // PPP 动态
opt.mode = Constants.PMODE_PPP_STATIC;    // PPP 静态

// 维度三：参考站位置模式（仅RTK需要，SPP/PPP不涉及）
opt.refpos = Constants.POSOPT_POS_XYZ;    // 固定XYZ（默认，从opt.rb读取）
opt.refpos = Constants.POSOPT_RTCM;       // RTCM动态获取（实时流常用）
opt.refpos = Constants.POSOPT_SINGLE;     // SPP均值（事后常用）
opt.rb = new double[]{-2267749.0, 5009154.0, 3220906.0}; // 基站ECEF坐标（refpos=POSOPT_POS_XYZ时使用）

// 处理方向
opt.soltype = Constants.SOLTYPE_FORWARD;     // 仅正向（默认）
opt.soltype = Constants.SOLTYPE_COMBINED;    // 正向+反向+合并（事后PostPosProcessor）
opt.cacheMaxEpochs = 240;                     // 实时双向：缓存满240历元触发反向（RtkProcessor）
```

### 库级参数体系

作为库而非软件，RTKLIB-Java 在 RTKLIB 原有参数基础上增加了库级参数，替代原版通过配置文件区分的处理模式：

| 参数 | 字段 | 可选值 | 默认值 | 说明 |
|------|------|--------|--------|------|
| 双向缓存大小 | `cacheMaxEpochs` | `0` / `>0` | `0` | 0=纯正向，>0=缓存满触发反向滤波+合并 |
| 输出节流间隔 | `outputThrottleInterval` | `0` / `>0` | `100` | 每N个历元节流一次，0=不节流 |
| 输出节流休眠 | `outputThrottleSleepMs` | 任意 | `10` | 节流时休眠毫秒数 |
| 电离层梯度估计 | `ionoGradient` | `true` / `false` | `false` | false=仅VTEC，true=VTEC+Gn+Ge逐星估计 |
| 位置输出格式 | `posMask` | `POS_ECEF` / `POS_LLH` / `POS_ENU` 位组合 | `ECEF\|LLH` | 控制SolData输出哪些坐标系 |
| 参考站位置模式 | `refpos` | `POSOPT_POS_XYZ` / `POSOPT_RTCM` 等 | `POSOPT_POS_XYZ` | 固定值 / RTCM动态 / SPP均值 |
| 静态单解输出 | `SolOpt.solstatic` | `0` / `1` | `0` | 0=逐历元输出，1=只输出最优解（需mode=STATIC或PPP_STATIC） |
| 静态输出窗口 | `SolOpt.solStaticWindow` | `0` / `>0` | `0` | 0=finish时输出，>0=每N个历元输出1个bestSol |

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

 ### 实时流 + 双向缓存

```java
// 配置：每240历元触发一次反向滤波+合并
PrcOpt opt = RtkProcessor.createDefaultOpt();
opt.cacheMaxEpochs = 240;  // 0:纯正向(默认)  >0:缓存满触发反向

RtkProcessor rtk = new RtkProcessor(opt, handler);

// 带sourceId投喂数据（自动缓存）
rtk.feedRover("rover_device_001", roverData);
rtk.feedBase("base_BJFS", baseData);

// 缓存满240时自动触发：反向处理 → CombinedFilter合并 → 通过handler.onResult()输出
// 应用方也可手动触发
RtkProcessor.RtkResult improved = rtk.reprocess("rover_device_001");
```

### 静态模式单解输出

RTK 和 PPP 静态模式均支持，机制一致：所有历元参与滤波，只输出1个最优解。
`solstatic=1` 仅在 `mode=PMODE_STATIC`/`PMODE_STATIC_START`/`PMODE_PPP_STATIC` 时生效。

```java
// RTK：1小时RTCM文件，600个历元全参与滤波，只输出1个最优解
PrcOpt opt = RtkProcessor.createDefaultOpt();
opt.mode = Constants.PMODE_STATIC;

SolOpt solOpt = new SolOpt();
solOpt.solstatic = 1;              // 只输出最优解
solOpt.solStaticWindow = 0;        // 0=finish时输出

RtkProcessor rtk = new RtkProcessor(opt, solOpt, handler, null);
RtkProcessor.RtkResult result = rtk.process("rover.rtcm3", "base.rtcm3");
// result.solutions 只有1个SolData（质量最好的历元）

// PPP：同理
PrcOpt pppOpt = PppProcessor.createDefaultOpt();
pppOpt.mode = Constants.PMODE_PPP_STATIC;

PppProcessor ppp = new PppProcessor(pppOpt, solOpt, handler, null);
ppp.loadSp3("igs.sp3"); ppp.loadClk("igs.clk");
PppProcessor.PppResult result = ppp.process("rover.rtcm3");

// 流水窗口模式：每360个历元自动输出1个bestSol
solOpt.solStaticWindow = 360;
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