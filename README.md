# RTKLIB-Java

RTKLIB 的 Java 移植版本，基于 [RTKLIB 2.5.0](https://github.com/tomojitakasu/RTKLIB) 开源 GNSS 定位库。

## 项目简介

RTKLIB-Java 将 C 语言编写的 RTKLIB 核心 GNSS 定位算法移植为 Java 实现，提供与原版功能对齐的卫星定位计算能力。项目定位为**库（Library）**而非独立软件，可嵌入 Java 应用中实现 GNSS 数据处理与定位解算。

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

### 库级参数体系

作为库而非软件，RTKLIB-Java 在 RTKLIB 原有参数基础上增加了库级参数，替代原版通过配置文件区分的处理模式：

| 参数 | 字段 | 可选值 | 默认值 | 说明 |
|------|------|--------|--------|------|
| 处理模式 | `procmode` | `PROCMODE_REALTIME(0)` / `PROCMODE_POST(1)` | `PROCMODE_POST` | 实时流 / 事后处理 |
| 参考站位置模式 | `refposmode` | `REFPOS_FIXED(0)` / `REFPOS_SPP_AVERAGE(1)` / `REFPOS_RTCM(2)` | `REFPOS_FIXED` | 固定值 / SPP均值 / RTCM动态 |

## 模块结构

```
org.rtklib.java
├── ambiguity/     模糊度解算（LAMBDA算法）
├── common/        通用工具（矩阵运算、卫星工具、观测值编码）
├── constants/     常量定义（物理常数、模式常量、卡方分布表）
├── coord/         坐标变换（ECEF↔LLH、ENU变换）
├── cycle/         周跳检测
├── data/          数据结构（观测值、星历、导航、解算结果等）
├── ephemeris/     星历计算（卫星位置与钟差）
├── ionosphere/    电离层延迟模型
├── kalman/        Kalman滤波器
├── pntpos/        单点定位（SPP、RAIM FDE、速度估计）
├── ppp/           精密单点定位（PPP动态、静态、固定坐标）
├── rinex/         RINEX 文件读写与处理
├── rtcm/          RTCM 数据解码
├── rtkpos/        RTK 相对定位核心
├── time/          时间系统（GPS时、UTC转换）
├── trace/         追踪日志系统（LogTrace）
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

### SPP 单点定位

``java
PrcOpt opt = new PrcOpt();
opt.mode = Constants.PMODE_SINGLE;

Rtk rtk = new Rtk(opt);

// 每个历元调用
int stat = RtkCore.rtkpos(rtk, obs, n, nav);
if (rtk.sol.stat == Constants.SOLQ_SINGLE) {
    double[] pos = new double[3];
    CoordTransform.ecef2pos(rtk.sol.rr, pos);
    System.out.printf("Lat=%.8f Lon=%.8f H=%.3f%n", pos[0], pos[1], pos[2]);
}
``

### RTK 相对定位

``java
PrcOpt opt = new PrcOpt();
opt.mode = Constants.PMODE_KINEMA;
opt.procmode = Constants.PROCMODE_POST;
opt.refposmode = Constants.REFPOS_FIXED;

// 设置基站已知坐标（ECEF）
opt.rb[0] = -2148744.236;
opt.rb[1] =  4426649.117;
opt.rb[2] =  4046168.936;

Rtk rtk = new Rtk(opt);

// 每个历元调用（obs中rcv=1为流动站，rcv=2为基站）
int stat = RtkCore.rtkpos(rtk, obs, n, nav);
if (rtk.sol.stat == Constants.SOLQ_FIX) {
    double[] pos = new double[3];
    CoordTransform.ecef2pos(rtk.sol.rr, pos);
    System.out.printf("FIX: Lat=%.8f Lon=%.8f H=%.3f%n", pos[0], pos[1], pos[2]);
}
``

### PPP 精密单点定位

```java
PrcOpt opt = new PrcOpt();
opt.mode = Constants.PMODE_PPP_KINEMA;
opt.ionoopt = Constants.IONOOPT_IFLC;
opt.tropopt = Constants.TROPOPT_SAAS;

PppProcessor processor = new PppProcessor(opt);

processor.processRinex(obsFilePath, navFilePath, sp3FilePath, clkFilePath);

for (Sol sol : processor.getResults()) {
    if (sol.stat == Constants.SOLQ_PPP_FLOAT) {
        double[] pos = new double[3];
        CoordTransform.ecef2pos(sol.rr, pos);
        System.out.printf(`PPP FLOAT: Lat=%.8f Lon=%.8f H=%.3f%n`,
            pos[0], pos[1], pos[2]);
    }
}
```

### RINEX 文件处理

``java
RinexParser parser = new RinexParser();
Obs obs = parser.parse(obsFilePath, navFilePath);

RinexSppProcessor spp = new RinexSppProcessor(obs, opt);
spp.process();
``

### RTCM 数据解码

``java
Rtcm rtcm = new Rtcm();
byte[] data = Files.readAllBytes(Paths.get(rtcmFilePath));

for (int i = 0; i < data.length; i++) {
    int type = rtcm.decode(data[i]);
    if (type == 1077) {
        // MSM4 观测数据已就绪
        Obsd[] obs = rtcm.obs.obs;
        int n = rtcm.obs.n;
    }
}
``

## 与 C 版 RTKLIB 的对齐状态

### 已对齐的核心流程

| 模块 | C版函数 | Java版方法 | 状态 |
|------|---------|-----------|------|
| SPP | `pntpos()` | `PntPos.pntpos()` | ✅ 完整对齐 |
| SPP核心 | `estpos()` | `SppCore.estpos()` | ✅ |
| RAIM FDE | `raim_fde()` | `PntPos.raimFde()` | ✅ |
| 速度估计 | `estvel()` | `PntPos.estvel()` | ✅ |
| 多普勒残差 | `resdop()` | `PntPos.resdop()` | ✅ |
| RTK入口 | `rtkpos()` | `RtkCore.rtkpos()` | ✅ 完整对齐 |
| 相对定位 | `relpos()` | `RtkCore.relpos()` | ✅ |
| 卫星位置 | `satposs()` | `EphModel.satposs()` | ✅ |
| 坐标变换 | `ecef2pos()`/`xyz2enu()` | `CoordTransform` | ✅ |
| LAMBDA | `lambda()` | `Lambda` | ✅ |
| 周跳检测 | `detslp_*()` | `CycleDetect` | ✅ |
| RINEX读写 | `readrnx()`/`outrnx()` | `RinexParser`/`RinexObsWriter` | ✅ |
| RTCM解码 | `decode_*()` | `Rtcm` | ✅ |
| PPP入口 | `pppos()` | `PppCore.pppos()` | ✅ 基本对齐 |
| PPP状态更新 | `udstate_ppp()` | `PppCore.udstate()` | ✅ |
| PPP观测修正 | `corrMeas()` | `PppCore.corrMeas()` | ✅ |
| PPP RINEX处理 | - | `PppProcessor`/`RinexPppProcessor` | ✅ |
| 追踪日志 | `trace*()` | `LogTrace` | ✅ |

### 已对齐的 rtkpos() 流程细节

| 逻辑 | 说明 | 状态 |
|------|------|------|
| 流动站SPP | `P[0]==0||P[0]>STD_PREC_VAR_THRESH` → `pntpos()` | ✅ |
| SPP失败dynamics容错 | dynamics模式下不直接返回 | ✅ |
| outsingle抑制 | 非SINGLE模式抑制单点解输出 | ✅ |
| 基站坐标设置 | `refposmode!=REFPOS_RTCM` | ✅ |
| MOVEB基站SPP | 基站观测数据独立SPP | ✅ |
| MOVEB age检查 | 时间同步验证 | ✅ |
| ssat后处理 | vs/azel/resp/resc更新 | ✅ |
| eventime传递 | `sol.eventime=obs[0].eventime` | ✅ |

### 未移植项

| 功能 | 优先级 | 原因 |
|------|--------|------|
| Static Start长延迟恢复 | 低 | 边界场景，`tt>300`时重置状态 |
| PPP模糊度固定 | 中 | 需整数模糊度解算，当前仅浮点解 |
| 多系统PPP验证 | 中 | GPS+BDS联合PPP，需多系统精密星历 |

## 方法命名规则

Java版方法名遵循以下规则，在保持Java驼峰命名的同时保留C版函数名核心：

| C版 | Java版 | 规则 |
|-----|--------|------|
| `pntpos()` | `pntpos()` | 无下划线，直接保留 |
| `estpos()` | `estpos()` | 同上 |
| `raim_fde()` | `raimFde()` | 下划线后首字母大写 |
| `detslp_ll()` | `detslpLl()` | 下划线后每段首字母大写 |
| `satposs()` | `satposs()` | 直接保留 |
| `ecef2pos()` | `ecef2pos()` | 直接保留 |

## 参考来源

- [RTKLIB 2.5.0](https://github.com/tomojitakasu/RTKLIB) - 原始 C 语言实现
- [RTKLIB Manual](http://www.rtklib.com/rtklib_document.htm) - 算法原理与使用说明

## 测试验证状态

当前测试以**北斗（BDS）单系统**为主，SPP和RTK已通过BDS数据与C版RTKLIB对比验证。PPP已实现基本功能，IFLC模式下BDS浮点解可正常输出。

多系统（GPS+BDS、GPS+GLONASS等）联合定位尚未充分验证。如有**多系统真实观测数据**条件，欢迎测试验证并反馈结果。

### 已验证场景

| 场景 | 数据源 | 状态 |
|------|--------|------|
| SPP（BDS-only） | RTCM MSM4 | ✅ 240历元与C版亚毫米级匹配 |
| RTK（BDS-only） | RTCM MSM4 | ✅ 浮点解收敛稳定 |
| PPP（BDS-only IFLC） | RINEX + SP3/CLK | ✅ 240历元浮点解输出 |
| SPP（GPS+BDS） | - | ⏳ 待验证 |
| RTK（GPS+BDS） | - | ⏳ 待验证 |
| PPP（GPS+BDS） | - | ⏳ 待验证 |

## License

本项目基于 RTKLIB 原始代码移植，遵循 RTKLIB 的 BSD-2-Clause 许可证。