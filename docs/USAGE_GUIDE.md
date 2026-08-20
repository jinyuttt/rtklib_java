# RTKLIB-Java 使用指南

本文档详细介绍 RTKLIB-Java 各功能模块的使用方法及输出字段含义。

---

## 目录

1. [RTCM 解码](#1-rtcm-解码)
2. [RINEX 转换](#2-rinex-转换)
3. [RINEX 文件定位](#3-rinex-文件定位)
4. [RTK 实时定位](#4-rtk-实时定位)
5. [SPP 实时定位](#5-spp-实时定位)
6. [PPP 精密单点定位](#6-ppp-精密单点定位)
7. [输出字段含义](#7-输出字段含义)
8. [观测值字段统一语义](#8-观测值字段统一语义)
9. [实时流双向缓存](#9-实时流双向缓存)

---

## 1. RTCM 解码

### 1.1 回调模式（推荐）

使用 `RtcmCallbackDecoder` 进行流式解码，通过 `RtcmDataHandler` 回调接口获取解码结果。
调用方无需关心底层 RTCM 消息类型，所有观测数据已合并为统一的历元输出。

```java
RtcmDataHandler handler = new RtcmDataHandler() {
    @Override
    public void onObservationEpoch(ObservationEpoch epoch) {
        // 每个历元的观测数据（同一时刻多个MSM消息已自动合并）
        for (Obsd obs : epoch.getObservations()) {
            int sat = obs.sat;          // 卫星号
            double[] P = obs.P;         // 伪距 (m)
            double[] L = obs.L;         // 载波相位 (cycle)
            float[] D = obs.D;          // 多普勒 (Hz)
            float[] SNR = obs.SNR;      // 信噪比 (dBHz)
            int[] LLI = obs.LLI;        // 失锁标志
            int[] code = obs.code;      // 码类型
            float[] Pstd = obs.Pstd;    // 伪距标准差 (m)
            float[] Lstd = obs.Lstd;    // 载波相位标准差 (m)
        }
    }

    @Override
    public void onEph(Eph eph) {
        // GPS/BDS/GAL/QZS 星历
    }

    @Override
    public void onGeph(Geph geph) {
        // GLONASS 星历
    }

    @Override
    public void onStation(Sta sta) {
        // 基站天线位置 (RTCM 1005/1006)
        double[] pos = sta.pos;  // ECEF坐标 (m)
    }

    @Override
    public void onSsr(Ssr ssr) {
        // SSR 轨道/钟差修正
    }

    @Override
    public void onAuxData(AuxData aux) {
        // 天线描述/接收机类型
    }

    @Override
    public void onFinish() {
        // 解码完成
    }
};

RtcmCallbackDecoder decoder = new RtcmCallbackDecoder(handler);

// 流式输入（网络实时数据）
decoder.feed(data, 0, data.length);

// 或批量输入
byte[] fileData = Files.readAllBytes(Paths.get("data.rtcm3"));
decoder.feed(fileData, 0, fileData.length);

// 结束
decoder.finish();
```

### 1.2 底层模式

直接使用 `Rtcm` 类进行底层解码，适合需要精细控制消息类型的场景：

```java
Rtcm rtcm = new Rtcm();
byte[] data = Files.readAllBytes(Paths.get("data.rtcm3"));

int pos = 0;
while (pos < data.length) {
    int consumed = rtcm.input(data, pos, data.length - pos);
    if (consumed > 0) {
        int type = rtcm.type;
        if (rtcm.obs.n > 0 && rtcm.obsflag == 1) {
            Obsd[] obs = rtcm.obs.data;
            int n = rtcm.obs.n;
        }
        pos += consumed;
    } else if (consumed == 0) {
        break; // 需要更多数据
    } else {
        pos++; // 跳过错误字节
    }
}
```

### 1.3 支持的 RTCM 消息类型

| 类型 | 说明 |
|------|------|
| 1001-1004 | GPS 遗留观测值（L1/L2） |
| 1005-1006 | 基站天线位置 |
| 1007-1008 | 天线描述 |
| 1009-1012 | GLONASS 遗留观测值 |
| 1019 | GPS 星历 |
| 1020 | GLONASS 星历 |
| 1033 | 接收机与天线描述 |
| 1041-1046 | 各系统星历 |
| 1057-1068 | SSR 修正 |
| 1071-1077 | GPS MSM |
| 1081-1087 | GLONASS MSM |
| 1091-1097 | Galileo MSM |
| 1101-1107 | SBAS MSM |
| 1111-1117 | QZSS MSM |
| 1121-1127 | BDS MSM |
| 1131-1137 | IRNSS MSM |

---

## 2. RINEX 转换

### 2.1 RTCM 文件转 RINEX

```java
// 方式1：构造器模式
RtcmFileToRinexConverter converter = new RtcmFileToRinexConverter(3.05, "D:/output", "ROVER");
boolean ok = converter.convert("D:/data/1.rtcm3");
// 输出: D:/output/ROVER.obs, D:/output/ROVER.nav

// 方式2：便捷静态方法
boolean ok = RtcmFileToRinexConverter.convertFile(
    "D:/data/1.rtcm3", 3.05, "D:/output", "ROVER");

// 获取输出文件路径
String obsPath = converter.getObsFilePath();  // D:/output/ROVER.obs
String navPath = converter.getNavFilePath();  // D:/output/ROVER.nav
```

### 2.2 RINEX 文件读取

```java
RinexParser parser = new RinexParser();

// 解析观测文件
boolean obsOk = parser.parseObs("ROVER.obs");

// 解析导航文件
boolean navOk = parser.parseNav("ROVER.nav");

// 获取数据
Obs obs = parser.obs;       // 观测数据
Nav nav = parser.nav;       // 导航数据
Sta sta = parser.sta;       // 站信息（含近似坐标、天线描述等）
```

### 2.3 RINEX 文件写入

```java
// 写入观测文件
RinexObsWriter obsWriter = new RinexObsWriter(3.05, "output.obs", sta);
obsWriter.setObsData(obs);
obsWriter.write();

// 写入导航文件
RinexNavWriter navWriter = new RinexNavWriter(3.05, "output.nav");
navWriter.setNavData(nav);
navWriter.write();
```

---

## 3. RINEX 文件定位

RINEX 文件定位使用 `RinexSppProcessor`、`RinexRtkProcessor`、`RinexPppProcessor`，
输入 RINEX 文件路径，直接输出定位结果。

### 3.1 RINEX SPP

```java
PrcOpt opt = RinexSppProcessor.createDefaultOpt();
RinexSppProcessor spp = new RinexSppProcessor(opt);
RinexSppProcessor.SppResult result = spp.process("ROVER.obs", "ROVER.nav");

// 便捷方法
RinexSppProcessor.SppResult result =
    RinexSppProcessor.processRinex("ROVER.obs", "ROVER.nav");

// 获取结果
for (Sol sol : result.solutions) {
    double[] pos = new double[3];
    CoordTransform.ecef2pos(sol.rr, pos);
    System.out.printf("Lat=%.9f Lon=%.9f H=%.4f%n",
        Math.toDegrees(pos[0]), Math.toDegrees(pos[1]), pos[2]);
}

// 写入 .pos 文件
RinexSppProcessor.writePosFile(result, "output.pos");
```

### 3.2 RINEX RTK

```java
PrcOpt opt = RinexRtkProcessor.createDefaultOpt();
RinexRtkProcessor rtk = new RinexRtkProcessor(opt);

// 设置基站坐标（ECEF），也可从RINEX头自动读取
rtk.setBasePosition(new double[]{-2148744.236, 4426649.117, 4046168.936});

RtkProcessor.RtkResult result = rtk.process("ROVER.obs", "BASE.obs", "NAV.nav");

// 便捷方法
RtkProcessor.RtkResult result =
    RinexRtkProcessor.processRinex("ROVER.obs", "BASE.obs", "NAV.nav", opt);

// 判断解算质量
for (Sol sol : result.solutions) {
    if (sol.stat == Constants.SOLQ_FIX) {
        // 固定解（最高精度，厘米级）
    } else if (sol.stat == Constants.SOLQ_FLOAT) {
        // 浮点解（分米级）
    } else if (sol.stat == Constants.SOLQ_SINGLE) {
        // 单点解（米级，RTK未收敛时的退化解）
    }
}
```

### 3.3 RINEX PPP

```java
PrcOpt opt = RinexPppProcessor.createDefaultOpt();
RinexPppProcessor ppp = new RinexPppProcessor(opt);

RinexPppProcessor.PppResult result =
    ppp.process("ROVER.obs", "ROVER.nav", "igs.sp3", "igs.clk");
```

---

## 4. RTK 实时定位

### 4.1 批量模式（RTCM 文件）

```java
PrcOpt opt = RtkProcessor.createDefaultOpt();
opt.modear = Constants.ARMODE_FIXHOLD;  // 启用模糊度固定

RtkProcessor rtk = new RtkProcessor(opt, handler, outputStream);
RtkProcessor.RtkResult result = rtk.process("rover.rtcm3", "base.rtcm3");

// 单RTCM流（含流动站+基准站数据）
RtkProcessor.RtkResult result = rtk.process("combined.rtcm3");
```

### 4.2 流式模式（网络实时数据）

```java
PrcOpt opt = RtkProcessor.createDefaultOpt();
PosHandler handler = new PosHandler() {
    @Override
    public void onSolution(Sol sol, Ssat[] ssat) {
        // 实时定位结果
        double[] pos = new double[3];
        CoordTransform.ecef2pos(sol.rr, pos);
        System.out.printf("RTK: Q=%d, ns=%d, Lat=%.8f%n",
            sol.stat, sol.ns, Math.toDegrees(pos[0]));
    }
    @Override
    public void onPosFail(GTime time, String msg) {
        // 定位失败
    }
    @Override
    public void onFinish(int total, int success, int fail) {
        // 处理完成
    }
};

RtkProcessor rtk = new RtkProcessor(opt, handler, outputStream);

// 流式输入
while (running) {
    byte[] roverChunk = roverStream.read();
    byte[] baseChunk = baseStream.read();
    rtk.feedRover(roverChunk);
    rtk.feedBase(baseChunk);
}

RtkProcessor.RtkResult result = rtk.finish();
```

### 4.3 静态模式单解输出（solstatic）

RTK 静态定位场景下，所有历元参与卡尔曼滤波平滑，但只需输出1个最优结果。
通过 `SolOpt.solstatic` 和 `SolOpt.solStaticWindow` 控制。

#### 4.3.1 两种输出模式

| 模式 | `solstatic` | `solStaticWindow` | 触发时机 | 适用场景 |
|------|-------------|-------------------|----------|----------|
| 逐历元输出（默认） | 0 | — | 每个历元 | 动态监测 |
| Finish模式 | 1 | 0 | `finish()` 时输出1个bestSol | 批处理/外部控制结束 |
| 窗口模式 | 1 | >0 | 每 N 个历元自动输出1个bestSol | 流水定期出结果 |

#### 4.3.2 使用示例

```java
PrcOpt opt = RtkProcessor.createDefaultOpt();
opt.mode = Constants.PMODE_STATIC;       // 静态模式（必须）
opt.modear = Constants.ARMODE_FIXHOLD;

// Finish模式：全部历元滤波，finish()时输出1个最优解
SolOpt solOpt = new SolOpt();
solOpt.solstatic = 1;
solOpt.solStaticWindow = 0;

RtkProcessor rtk = new RtkProcessor(opt, solOpt, handler, null);
rtk.process("rover.rtcm3", "base.rtcm3");
// → result.solutions 只有1个SolData（质量最好的那个历元）

// 窗口模式：每360个历元自动输出1个最优解，滤波不中断
SolOpt solOpt = new SolOpt();
solOpt.solstatic = 1;
solOpt.solStaticWindow = 360;

RtkProcessor rtk = new RtkProcessor(opt, solOpt, handler, null);
while (running) {
    rtk.feedRover(roverData);
    rtk.feedBase(baseData);
}
rtk.finish();
// → 每360个历元输出1个bestSol，finish时输出剩余不完整窗口的bestSol
```

#### 4.3.3 bestSol 选择逻辑

与 RTKLIB C 版 `PostPosProcessor` 一致，按解质量优先级选择：

| 优先级 | 解状态 | 值 |
|--------|--------|-----|
| 1（最优） | FIX（固定解） | 1 |
| 2 | FLOAT（浮点解） | 2 |
| 3 | SBAS | 3 |
| 4 | DGPS | 4 |
| 5 | SINGLE（单点解） | 5 |
| 6（最差） | NONE（无解） | 0 |

同优先级时，选择时间最早的解（`timediff < 0`）。

#### 4.3.4 关键行为

- **滤波不中断**：窗口模式输出 bestSol 后，只重置 bestSol 追踪，**不重置**卡尔曼滤波器状态（`rtk.x`、`rtk.P` 等），滤波器持续收敛
- **solutions 列表**：所有历元的 Sol 仍记录在 `solutions` 中（供双向组合滤波使用），`solStaticOutputs` 只记录输出的 bestSol
- **buildResult()**：solstatic 时 `RtkResult.solutions` 来自 `solStaticOutputs`（只含 bestSol），否则来自 `solutions`（全部历元）
- **前提条件**：`solstatic=1` 仅在 `mode=PMODE_STATIC` 或 `mode=PMODE_STATIC_START` 时生效，其他模式自动退化为逐历元输出

### 4.4 配置选项

```java
PrcOpt opt = RtkProcessor.createDefaultOpt();
opt.mode = Constants.PMODE_KINEMA;       // 定位模式
opt.nf = 3;                               // 使用频率数
opt.navsys = Constants.SYS_GPS | Constants.SYS_CMP;  // 卫星系统
opt.elmin = 15.0 * Constants.D2R;         // 高度角限制 (rad)
opt.modear = Constants.ARMODE_FIXHOLD;    // 模糊度固定模式
opt.refpos = Constants.POSOPT_RTCM;   // 基站坐标来源
opt.maxtdiff = 30.0;                      // 最大时间差 (s)
opt.outsingle = 1;                        // RTK失败时输出SPP解
```

---

## 5. SPP 实时定位

### 5.1 批量模式

```java
PrcOpt opt = SppProcessor.createDefaultOpt();
SppProcessor spp = new SppProcessor(opt, handler, outputStream);
SppProcessor.SppResult result = spp.process("data.rtcm3");

// 或从文件路径
SppProcessor.SppResult result = spp.process(Paths.get("data.rtcm3"));
```

### 5.2 流式模式

```java
SppProcessor spp = new SppProcessor(opt, handler, outputStream);

while (running) {
    byte[] chunk = networkStream.read();
    spp.feed(chunk);
}

SppProcessor.SppResult result = spp.finish();
```

### 5.3 重置与复用

```java
SppProcessor spp = new SppProcessor(opt);

// 第一个会话
spp.feed(data1);
SppProcessor.SppResult result1 = spp.finish();

// 重置，保留星历数据
spp.reset();

// 第二个会话（无需重新等待星历）
spp.feed(data2);
SppProcessor.SppResult result2 = spp.finish();
```

---

## 6. PPP 精密单点定位

### 6.1 RTCM 文件 + 精密星历

```java
PrcOpt opt = PppProcessor.createDefaultOpt();
PppProcessor ppp = new PppProcessor(opt, handler, outputStream);

ppp.loadSp3("igs15904.sp3");
ppp.loadClk("igs15904.clk");

PppProcessor.PppResult result = ppp.process("rover.rtcm3");
```

### 6.2 RINEX 文件

```java
PppProcessor ppp = new PppProcessor(opt);
PppProcessor.PppResult result =
    ppp.processRinex("rover.obs", "rover.nav", "igs.sp3", "igs.clk");
```

### 6.3 流式模式

```java
PppProcessor ppp = new PppProcessor(opt, handler, outputStream);
ppp.loadSp3("igs.sp3");
ppp.loadClk("igs.clk");

while (running) {
    byte[] chunk = networkStream.read();
    ppp.feed(chunk);
}

PppProcessor.PppResult result = ppp.finish();
```

---

## 7. 输出字段含义

### 7.1 Sol（定位解）

| 字段 | 类型 | 含义 |
|------|------|------|
| `time` | GTime | 定位时刻 (GPST) |
| `rr[0..2]` | double | ECEF 坐标 x, y, z (m) |
| `rr[3..5]` | double | ECEF 速度 vx, vy, vz (m/s)（dynamics=1时有效） |
| `qr[0..5]` | float | 位置方差-协方差 (m²)：c_xx, c_yy, c_zz, c_xy, c_yz, c_zx |
| `qv[0..5]` | float | 速度方差-协方差 (m²/s²) |
| `stat` | byte | 解算状态：0=无解, 1=固定, 2=浮点, 3=SBAS, 4=DGPS, 5=单点, 6=PPP |
| `ns` | byte | 有效卫星数 |
| `age` | float | 差分龄期 (s) |
| `ratio` | float | AR ratio 值 |
| `dtr[0..6]` | double | 接收机钟差 (s)：0=GPS, 1=GLO, 2=GAL, 3=BDS, 4=QZS |

### 7.2 Ssat（卫星状态）

| 字段 | 类型 | 含义 |
|------|------|------|
| `sys` | int | 卫星系统 (SYS_GPS 等) |
| `vs` | int | 卫星有效性 (0=无效, 1=有效) |
| `azel[0..1]` | double | 方位角、高度角 (rad) |
| `vsat[f]` | int | 频率 f 上是否有效 (0/1) |
| `fix[f]` | int | 模糊度状态：0=未固定, 1=浮点, 2=固定 |
| `slip[f]` | int | 周跳标志：0=无周跳, 非0=有周跳 |
| `resp[f]` | double | 伪距残差 (m) |
| `resc[f]` | double | 载波相位残差 (m) |
| `amb[f]` | double | 模糊度值 (cycle) |
| `snrRover[f]` | float | 流动站 SNR (dBHz) |
| `snrBase[f]` | float | 基准站 SNR (dBHz) |

### 7.3 ObservationEpoch（观测历元）

| 字段 | 类型 | 含义 |
|------|------|------|
| `time` | GTime | 历元时间 (GPST) |
| `obsList` | List\<Obsd\> | 各卫星观测数据列表 |

### 7.4 .pos 文件格式

```
  2026/07/31 02:00:00.000000  39.123456789  117.123456789    12.3456  1  12  0.0100  0.0080  0.0150  0.0020  0.0030  0.0010   1.0   42.0
```

| 列 | 含义 |
|----|------|
| 1 | 日期 (YYYY/MM/DD) |
| 2 | 时间 (HH:MM:SS.ssssss) |
| 3 | 纬度 (deg) |
| 4 | 经度 (deg) |
| 5 | 高度 (m) |
| 6 | 解算质量 Q：1=固定, 2=浮点, 3=SBAS, 4=DGPS, 5=单点, 6=PPP |
| 7 | 有效卫星数 |
| 8-10 | N/E/U 标准差 (m) |
| 11-13 | NE/EU/UN 标准差 (m) |
| 14 | 差分龄期 (s) |
| 15 | AR ratio |

### 7.5 SppResult / RtkResult / PppResult

| 字段 | 类型 | 含义 |
|------|------|------|
| `totalEpochs` | int | 总历元数 |
| `successCount` | int | 成功定位历元数 |
| `failCount` | int | 失败历元数 |
| `solutions` | List\<Sol\> | 所有成功定位的解算结果列表 |

---

## 8. 观测值字段统一语义

无论数据来源是 RTCM（MSM/遗留格式）还是 RINEX，`Obsd` 输出字段的语义是统一的，
调用方无需关心底层消息类型差异。

### 8.1 Obsd 字段说明

| 字段 | 单位 | 说明 |
|------|------|------|
| `P[f]` | 米 (m) | 伪距观测值 |
| `L[f]` | 周 (cycle) | 载波相位观测值，需乘波长 λ 转米 |
| `D[f]` | Hz | 多普勒观测值 |
| `SNR[f]` | dBHz | 信号载噪比 |
| `LLI[f]` | 无量纲 | 失锁标志：bit0=周跳, bit1=半周模糊度, bit2=BOC跟踪 |
| `code[f]` | 常量 | 码类型（CODE_L1C, CODE_L2P 等） |
| `Pstd[f]` | 米 (m) | 伪距标准差（RINEX 从信号强度等级转换，RTCM 为 0） |
| `Lstd[f]` | 米 (m) | 载波相位标准差（RINEX 从信号强度等级转换，RTCM 为 0） |
| `sat` | 无 | 卫星号 (1..MAXSAT) |
| `rcv` | 无 | 接收机ID：1=流动站, 2=基准站 |
| `time` | GTime | 观测时间 (GPST) |

### 8.2 不同数据源的转换规则

| 来源 | SNR | LLI | Pstd/Lstd |
|------|-----|-----|-----------|
| RINEX 3.x | 从信号强度字段读取 (dBHz) | 从 LLI 字段读取 (&3 掩码) | 从信号强度等级转换 |
| RTCM MSM | 6 bits x 1 dB = dBHz | lossoflock() + half-cycle | 0（C 版同样不设置） |
| RTCM 遗留 (1002/1004) | 8 bits x 0.25 dB -> snratio() | lossoflock() | 0 |
| RTCM 遗留 (1001/1003) | 不含观测数据 | 不含观测数据 | 不含观测数据 |

### 8.3 调用方无需关心的内部处理

以下处理由库内部完成，调用方无需关心数据来源差异：

1. **LLI 统一**：RINEX 的 `&3` 掩码、RTCM 的 `lossoflock()` 检测，输出统一为 bit0=周跳、bit1=半周
2. **SNR 统一**：所有来源输出均为 dBHz
3. **伪距计算**：RTCM 遗留格式的 `pr x 0.02 + amb x PRUNIT` 已在解码层完成
4. **载波相位计算**：RTCM 的 `adjcp()` 周跳调整已在解码层完成
5. **周跳持久化**：RINEX 的 `saveslips/restslips` 机制确保跨历元周跳标志不丢失
6. **历元合并**：`RtcmCallbackDecoder` 自动合并同一时刻的多个 MSM 消息
7. **时间修正**：观测历元周数自动从星历时间修正，无需调用方处理

---

## 9. 实时流双向缓存

RTK 实时流场景下，通过缓存观测数据实现双向滤波（正向+反向），合并后提升定位精度。
C 版 RTKLIB 的反向滤波仅支持事后批处理（文件读完→正向→反向→合并），Java 版扩展到实时流场景。

### 9.1 基本原理

```
正向滤波：  epoch1 → epoch2 → ... → epochN  （实时逐历元处理）
反向滤波：  epochN → ... → epoch2 → epoch1  （缓存满后逆序处理）
合并输出：  CombinedFilter 加权平均正向和反向结果
```

反向滤波利用后续历元的信息修正前期模糊度未收敛时的精度损失，
对 RTK 初始化阶段和周跳恢复阶段效果显著。

### 9.2 配置与使用

```java
PrcOpt opt = RtkProcessor.createDefaultOpt();
opt.cacheMaxEpochs = 240;  // 关键配置：0=纯正向(默认), >0=缓存满触发反向

PosHandler handler = new PosHandler() {
    @Override
    public void onResult(SolData solData) {
        // solData.sourceId 可区分不同数据源
        // 正向结果实时推送，反向合并结果在缓存满时批量推送
        System.out.printf("[%s] Q=%d sourceId=%s%n",
            solData.timeStr, solData.status.getCode(), solData.sourceId);
    }
    // ... onSolution, onPosFail, onFinish
};

RtkProcessor rtk = new RtkProcessor(opt, handler);

// 带sourceId投喂数据（自动缓存）
rtk.feedRover("rover_device_001", roverData);
rtk.feedBase("base_BJFS", baseData);

// 缓存满240历元时自动触发：反向处理 → CombinedFilter合并 → handler.onResult()输出
```

### 9.3 触发机制

| 条件 | 行为 |
|------|------|
| `cacheMaxEpochs = 0` | 纯正向，不缓存（默认，与无缓存时行为一致） |
| `cacheMaxEpochs > 0` 且缓存满 | 自动触发反向滤波 → 合并 → 通过 `handler.onResult()` 输出 → 清空缓存 |
| 手动调用 `reprocess(sourceId)` | 立即对指定数据源的缓存数据执行反向+合并 |

### 9.4 数据源标识 (sourceId)

`sourceId` 由调用方定义，用于区分不同测站/设备的数据：

```java
// 多数据源场景
rtk.feedRover("station_A", roverDataA);  // 缓存按 sourceId 分桶
rtk.feedRover("station_B", roverDataB);  // 互不干扰

// 回调中通过 SolData.sourceId 区分结果来源
```

`sourceId` 可为 null（向后兼容 `feed(data)` 等价于 `feed(null, data)`），但 null 不参与缓存。

### 9.5 缓存实现

| 实现 | 类 | 说明 |
|------|------|------|
| 内存缓存 | `InMemoryEpochCache` | 环形缓冲区，超容量自动丢弃最旧历元（默认） |
| 外部缓存 | `ExternalEpochCache` | 通过 `ExternalCacheProvider` 接口对接 Redis/数据库/文件 |

```java
// 使用外部缓存
ExternalCacheProvider provider = new MyRedisCacheProvider();
EpochCache externalCache = new ExternalEpochCache(provider, 240);
rtk.setEpochCache(externalCache);
```

### 9.6 事后双向滤波

事后处理（RINEX文件）通过 `PostPosProcessor` + `soltype` 配置实现双向，RTK 和 PPP 均支持：

```java
PrcOpt opt = new PrcOpt();
opt.mode = Constants.PMODE_KINEMA;
opt.soltype = Constants.SOLTYPE_COMBINED;  // 正向+反向+合并

PostPosProcessor post = new PostPosProcessor(opt, sopt, handler);
PostPosProcessor.PostPosResult result = post.process("rover.obs", "base.obs", "nav.nav");
```

| soltype | 常量 | 说明 |
|---------|------|------|
| 0 | `SOLTYPE_FORWARD` | 仅正向 |
| 1 | `SOLTYPE_BACKWARD` | 仅反向 |
| 2 | `SOLTYPE_COMBINED` | 正向+反向+合并（反向独立初始化） |
| 3 | `SOLTYPE_COMBINED_NORESET` | 正向+反向+合并（反向复用正向状态） |

### 9.7 批处理双向

RtkProcessor/PppProcessor 的 `process()` 批处理方法支持双向滤波（SPP不支持，见9.8）：

```java
PrcOpt opt = new PrcOpt();
opt.mode = Constants.PMODE_KINEMA;
opt.cacheMaxEpochs = 240;  // >0 开启批处理双向

RtkProcessor rtk = new RtkProcessor(opt, handler);
RtkProcessor.RtkResult result = rtk.process(roverData, baseData);
// 正向成功历元>10时，自动执行：反向→CombinedFilter合并→返回合并结果
```

触发条件：`cacheMaxEpochs > 0` 且正向成功历元数 > 10。不满足条件时退化为纯正向。

### 9.8 适用范围

| 场景 | SPP | RTK | PPP |
|------|:---:|:---:|:---:|
| 实时正向 | ✅ | ✅ | ✅ |
| 批处理双向（process方法） | ❌ | ✅ | ✅ |
| 实时双向（缓存触发） | ❌ | ✅ | ❌ |
| 事后双向（soltype配置） | — | ✅ | ✅ |

SPP为绝对定位，每历元独立求解，正反向结果相同，双向无意义。

---

## 常量参考

### 定位模式 (PrcOpt.mode)

| 常量 | 值 | 说明 |
|------|---|------|
| `PMODE_SINGLE` | 0 | 单点定位 |
| `PMODE_DGPS` | 1 | 差分 GPS |
| `PMODE_KINEMA` | 3 | 动态相对定位 |
| `PMODE_STATIC` | 2 | 静态相对定位 |
| `PMODE_MOVEB` | 4 | 移动基线 |
| `PMODE_FIXED` | 5 | 固定位置 |
| `PMODE_PPP_KINEMA` | 6 | PPP 动态 |
| `PMODE_PPP_STATIC` | 7 | PPP 静态 |

### 模糊度固定模式 (PrcOpt.modear)

| 常量 | 值 | 说明 |
|------|---|------|
| `ARMODE_OFF` | 0 | 不固定 |
| `ARMODE_CONT` | 1 | 连续 |
| `ARMODE_INST` | 2 | 瞬时 |
| `ARMODE_FIXHOLD` | 3 | 固定+保持 |

### 解算状态 (Sol.stat)

| 常量 | 值 | 说明 |
|------|---|------|
| `SOLQ_NONE` | 0 | 无解 |
| `SOLQ_FIX` | 1 | 固定解 |
| `SOLQ_FLOAT` | 2 | 浮点解 |
| `SOLQ_SBAS` | 3 | SBAS 解 |
| `SOLQ_DGPS` | 4 | DGPS 解 |
| `SOLQ_SINGLE` | 5 | 单点解 |
| `SOLQ_PPP` | 6 | PPP 解 |

### 滤波方向 (PrcOpt.soltype)

| 常量 | 值 | 说明 |
|------|---|------|
| `SOLTYPE_FORWARD` | 0 | 正向 |
| `SOLTYPE_BACKWARD` | 1 | 反向 |
| `SOLTYPE_COMBINED` | 2 | 正向+反向+合并 |
| `SOLTYPE_COMBINED_NORESET` | 3 | 正向+反向+合并（反向复用正向状态） |

### 参考站位置模式 (PrcOpt.refpos)

| 常量 | 值 | 说明 |
|------|---|------|
| `POSOPT_POS_LLH` | 0 | 固定LLH（从PrcOpt.rb读取） |
| `POSOPT_POS_XYZ` | 1 | 固定XYZ（从PrcOpt.rb读取，默认） |
| `POSOPT_SINGLE` | 2 | SPP均值 |
| `POSOPT_FILE` | 3 | 从文件读取 |
| `POSOPT_RINEX` | 4 | RINEX头近似坐标 |
| `POSOPT_RTCM` | 5 | RTCM 1005/1006动态获取 |

### 卫星系统

| 常量 | 值 | 说明 |
|------|---|------|
| `SYS_GPS` | 0x01 | GPS |
| `SYS_SBS` | 0x02 | SBAS |
| `SYS_GLO` | 0x04 | GLONASS |
| `SYS_GAL` | 0x08 | Galileo |
| `SYS_CMP` | 0x10 | 北斗 |
| `SYS_QZS` | 0x20 | QZSS |
| `SYS_IRN` | 0x40 | IRNSS |

### 电离层模型 (PrcOpt.ionoopt)

| 常量 | 值 | 说明 |
|------|---|------|
| `IONOOPT_OFF` | 0 | 不修正 |
| `IONOOPT_BRDC` | 1 | 广播星历修正 |
| `IONOOPT_IFLC` | 3 | 无电离层组合 |

### 对流层模型 (PrcOpt.tropopt)

| 常量 | 值 | 说明 |
|------|---|------|
| `TROPOPT_OFF` | 0 | 不修正 |
| `TROPOPT_SAAS` | 1 | Saastamoinen 模型 |
| `TROPOPT_EST` | 2 | 估计 |