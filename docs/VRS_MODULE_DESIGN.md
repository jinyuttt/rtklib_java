# rtklib-vrs 模块设计开发文档

> **包路径**：`org.rtklib.java.vrs`
> **功能**：基于真实基站 RTCM 数据生成 VRS（Virtual Reference Station）虚拟参考站数据
> **算法来源**：单基站 VRS 改正基于标准差分 GNSS 理论；RTCM3 编码翻译自 RTKLIB 2.5.1 `rtcm3e.c`
> **依赖**：`rtklib-core`

---

## 1. 模块总览

```
输入 RTCM 文件 ──读取──> RtcmCallbackDecoder ──回调──> VrsGenerator
                                                       │
                                                       ├─ onStation(sta)    → 记录基站位置
                                                       ├─ onEph(eph)        → 收集星历
                                                       ├─ onGeph(geph)      → 收集 GLONASS 星历
                                                       └─ onObservationEpoch → VrsCorrection
                                                                                │
                                                                                ▼
                                                          基站 obs + 改正量 = 虚拟站 obs_vrs
                                                                                │
                                                                                ▼
                                                          Rtcm3Encoder.encode() → byte[]
                                                                                │
                                                                                ▼
                                                          写入输出 RTCM 文件
```

### 1.1 �!模块边界

```
rtklib-core（RTCM 解码、星历计算、大气模型、坐标转换）
        ↓  RtcmDataHandler 回调 + Nav/Obs/Sta 数据结构
rtklib-vrs（VRS 改正 + RTCM3 编码）
        ↓  输出 RTCM 文件
用户
```

本模块**不依赖** rtklib-stream、rtklib2adjust/adjust、rtklib-joint 等其他模块，仅依赖 rtklib-core。

### 1.2 核心价值

| 价值 | 说明 |
|------|------|
| VRS 虚拟参考站生成 | 在任意指定位置生成虚拟站观测值，供网络 RTK 差分服务使用 |
| RTCM3 编码能力 | 填补 rtklib-core 只有解码没有编码的空白 |
| 离线文件处理 | 输入/输出均为 RTCM 文件，无需网络流，便于调试和批处理 |

---

## 2. 包结构与类职责

```
org.rtklib.java.vrs
├── config/
│   ├── VrsRequiredConfig.java     # 必需参数配置（无默认值，用户必须提供）
│   └── VrsOptionalConfig.java     # 可选参数配置（全部有合理默认值）
├── VrsProcessor.java              # 顶层处理器：RTCM文件 → VRS → RTCM文件
├── VrsGenerator.java              # VRS 核心生成器（实现 RtcmDataHandler）
├── VrsCorrection.java             # VRS 改正计算（几何/电离层/对流层差分）
└── rtcm/
    ├── Rtcm3Encoder.java          # RTCM3 编码主入口 + 帧封装. + CRC
    ├── Rtcm3MsmEncoder.java       # MSM 消息编码（1071-1127）
    ├── Rtcm3StationEncoder.java   # 站坐标编码（1005/1006）
(   └── Rtcm3EphEncoder.java       # 星历透传编码（1019/1020/1042/1045）
```

### 2.1 类职责详表

| 类 | 职责 | 依赖 core 组件 | 代码量估计 |
|----|------|----------------|-----------|
| `VrsProcessor` | 顶层入口：读 RTCM → 驱动解码 → 生成 VRS → 写 RTCM | `RtcmCallbackDecoder` | ~150行 |
| `VrsGenerator` | 实现 `RtcmDataHandler`，收集 obs/nav/sta，调用改正，驱动编码输出 | `RtcmDataHandler`, `Nav`, `Sta`, `Obs` | ~300行 |
| `VrsCorrection` | 纯计算：几何距离改正 + 电离层差分 + 对流层差分 | `EphModel`, `RtklibCommon`, `IonosphereModel`, `TroposphereModel`, `CoordTransform` | ~500行 |
| `Rtcm3Encoder` | RTCM3 bit 级编码主入口，帧封装 + CRC-24Q | `BitUtils`, `CrcUtils` | ~400行 |
| `Rtcm3MsmEncoder` | MSM1-7 消息编码（1071-1127） | `Rtcm3Encoder`, `BitUtils` | ~1200行 |
| `Rtcm3StationEncoder` | 1005/1006 站坐标编码 | `Rtcm3Encoder`, `BitUtils` | ~150行 |
| `Rtcm3EphEncoder` | 星历消息编码（1019/1020/1042/1045） | `Rtcm3Encoder`, `BitUtils` | ~600行 |

**总计约 ~3300 行**

---

## 3. 配置类设计

### 3.1 VrsRequiredConfig — 必需参数

无默认值，用户必须提供。缺少任一项应抛出 `IllegalArgumentException`。

| 字段 | 类型 | 说明 | 约束 |
|------|------|------|------|
| `vrsLat` | `double` | 虚拟站纬度（度） | -90 ~ 90 |
| `vrsLon` | `double` | 虚拟站经度（度） | -180 ~ 180 |
| `vrsHeight` | `double` | 虚拟站椭球高（米） | 无硬约束 |
| `stationId` | `String` | 虚拟站标识/挂载点名 | 非空 |
| `inputRtcmPath` | `String` | 输入 RTCM 文件路径 | 文件存在 |
| `outputRtcmPath` | `String` | 输出 RTCM 文件路径 | 非空 |

```java
package org.rtklib.java.vrs.config;

import java.io.Serializable;

public class VrsRequiredConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    public double vrsLat;
    public double vrsLon;
    public double vrsHeight;
    public String stationId;
    public String inputRtcmPath;
    public String outputRtcmPath;

    public void validate() {
        if (vrsLat < -90.0 || vrsLat > 90.0)
            throw new IllegalArgumentException("vrsLat out of range: " + vrsLat);
        if (vrsLon < -180.0 || vrsLon > 180.0)
            throw new IllegalArgumentException("vrsLon out of range: " + vrsLon);
        if (stationId == null || stationId.isEmpty())
            throw new IllegalArgumentException("stationId is required");
        if (inputRtcmPath == null || inputRtcmPath.isEmpty())
            throw new IllegalArgumentException("inputRtcmPath is required");
        if (outputRtcmPath == null || outputRtcmPath.isEmpty())
            throw new IllegalArgumentException("outputRtcmPath is required");
    }
}
```

### 3.2 VrsOptionalConfig — 可选参数

全部有合理默认值，用户可按需覆盖。

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `ionoModel` | `int` | `IONOOPT_BRDC` | 电离层改正模型 |
| `tropModel` | `int` | `TROPOPT_SAAS` | 对流层改正模型 |
| `elMaskDeg` | `double` | `10.0` | 截止高度角（度） |
| `navsys` | `int` | `SYS_GPS\|SYS_GLO\|SYS_GAL\|SYS_CMP` | 输出 GNSS 系统 |
| `msmType` | `int` | `4` | MSM 消息类型（1-7），默认 MSM4 |
| `stationMsgType` | `int` | `1005` | 站坐标消息类型（1005 或 1006） |
| `antType` | `String` | `""` | 虚拟站天线类型描述 |
| `recType` | `String` | `""` | 虚拟站接收机类型描述 |
| `passThroughEph` | `boolean` | `true` | 是否透传星历到输出 |
| `rtcmStationId` | `int` | `1` | RTCM 域 Station ID（1-4095） |
| `seqNo` | `int` | `0` | IODS（Issue of Data Station），0-7 |

```java
package org.rtklib.java.vrs.config;

import java.io.Serializable;
import org.rtklib.java.constants.Constants;

public class VrsOptionalConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    public int ionoModel     = Constants.IONOOPT_BRDC;
    public int tropModel     = Constants.TROPOPT_SAAS;
    public double elMaskDeg  = 10.0;
    public int navsys        = Constants.SYS_GPS | Constants.SYS_GLO
                            | Constants.SYS_GAL | Constants.SYS_CMP;
    public int msmType       = 4;
    public int stationMsgType = 1005;
    public String antType    = "";
    public String recType    = "";
    public boolean passThroughEph = true;
    public int rtcmStationId = 1;
    public int seqNo         = 0;
}
```

---

## 4. 核心算法

### 4.1 VRS 改正算法（VrsCorrection）

**原理**：已知真实基站位置 `rr_base` 和观测值 `obs_base`，在虚拟位置 `rr_vrs` 处生成观测值 `obs_vrs`。

**单基站 VRS 改正公式**（标准差分 GNSS 理论）：

```
对每颗卫星 i 在历元 t:

  1. 卫星位置与钟差
     rs[i], dts[i] = EphModel.satpos(t, nav, sat_i)

  2. 几何距离改正
     r_base[i] = geodist(rs[i], rr_base_ecef, e_base[i])
     r_vrs[i]  = geodist(rs[i], rr_vrs_ecef,  e_vrs[i])
     Δgeom[i]  = r_vrs[i] - r_base[i]

  3. 方位角/高度角
     satazel(pos_base, e_base[i], azel_base[i])
     satazel(pos_vrs,  e_vrs[i],  azel_vrs[i])

  4. 对流层差分改正
     trop_base[i] = tropcorr(t, nav, pos_base, azel_base[i], tropopt)
     trop_vrs[i]  = tropcorr(t, nav, pos_vrs,  azel_vrs[i],  tropopt)
     Δtrop[i] = trop_vrs[i] - trop_base[i]

  5. 电离层差分改正
     ion_base[i] = ionocorr(t, nav, sat_i, pos_base, azel_base[i], ionoopt)
     ion_vrs[i]  = ionocorr(t, nav, sat_i, pos_vrs,  azel_vrs[i],  ionoopt)
     Δion[i] = ion_vrs[i] - ion_base[i]

  6. 改正观测值
     P_vrs[i,f] = P_base[i,f] + Δgeom[i] + Δtrop[i] + Δion[i]
     L_vrs[i,f] = L_base[i,f] + (Δgeom[i] + Δtrop[i] - Δion[i]) / λ[i,f]
     D_vrs[i,f] ≈ D_base[i,f]    （多普勒变化极小，直接复用）
```

**关键点**：步骤 1-5 的每个子函数在 rtklib-core 中**全部已有**：

| 步骤 | core 组件 | 方法 |
|------|-----------|------|
| 卫星位置 | `EphModel` | `satpos(time, nav, sat, rs, dts, vare)` |
| 几何距离 | `RtklibCommon` | `geodist(rs, rr, e)` |
| 方位角 | `RtklibCommon` | `satazel(pos, e, azel)` |
| 坐标转换 | `CoordTransform` | `pos2ecef(pos, r)`, `ecef2pos(r, pos)` |
| 对流层 | `TroposphereModel` | `tropcorr(time, nav, pos, azel, tropopt, out)` |
| 电离层 | `IonosphereModel` | `ionocorr(time, nav, sat, pos, azel, ionoopt, out)` |
| 波长 | `SatUtils` | `sat2freq(sat, code, nav)` → λ = CLIGHT / freq |

### 4.2 算法精度说明

| 改正项 | 模型 | 典型精度 | 备注 |
|--------|------|----------|------|
| 几何距离 | 精确计算 | < 1 mm | 无模型误差 |
| 对流层 | Saastamoinen + GMF | ~1-3 cm 差分残差 | 基线 < 50 km 时差分效果好 |
| 电离层 | Klobuchar 广播 | ~2-5 m 单点，差分后 ~10-30 cm | 基线越短差分效果越好；有 SSR 时可提升至 cm 级 |
| 整体 | — | 基线 < 30 km 时 VRS 伪距精度 ~5-10 cm | 载波相位经差分后可达 mm 级 |

**限制**：当前为单基站 VRS，不支持多基站网络插值。多基站 VRS（MAC、iMAX、FKP 等）需额外实现网络插值模型，作为后续扩展。

---

## 5. RTCM3 编码器设计

### 5.1 参考源

翻译自 **RTKLIB 2.5.1** `src/rtcm3e.c`（2834 行），位于本项目 `RTKLIB-2.5.1/src/rtcm3e.c`。

选择 2.5.1 而非 VrsCaster 的 2.4.3 版本原因：
- 2.5.1 多 618 行，支持更多消息类型（1042 BDS 星历、1046 GAL F/NAV、63 SSR draft、1131-1137 IRNSS、4073/4076 CSSR）
- API 更优：`encode_rtcm3(rtcm, type, subtype, sync)` 多了 `subtype` 参数
- 与 Java 解码器 `Rtcm.java` 对齐同一版本

### 5.2 VRS 输出必需的 RTCM3 消息

| 优先级 | 消息类型 | 说明 | 对应 C 函数 |
|--------|----------|------|-------------|
| **P0 必须** | 1005 | 天线位置（ECEF） | `encode_type1005` |
| **P0 必须** | 1074/1084/1094/1124 | GPS/GLO/GAL/BDS MSM4 观测值 | `encode_msm4` |
| **P1 重要** | 1019 | GPS 星历 | `encode_type1019` |
| **P1 重要** | 1020 | GLONASS 星历 | `encode_type1020` |
| **P1 重要** | 1042 | BDS 星历 | `encode_type1042` |
| **P1 重要** | 1045 | Galileo 星历 | `encode_type1045` |
| **P2 可选** | 1006 | 天线位置 + 天线高 | `encode_type1006` |
| **P2 可选** | 1075/1085/1095/1125 | MSM5 观测值 | `encode_msm5` |
| **P2 可选** | 1007/1008 | 天线描述 | `encode_type1007/1008` |
| **P3 扩展** | 1077/1087/1097/1127 | MSM7（全精度） | `encode_msm7` |
| **P3 扩展: | 1230 | GLONASS 频率号 | `encode_type1230` |

### 5.3 编码器类设计

#### Rtcm3Encoder — 主入口

```java
public class Rtcm3Encoder {

    private byte[] buff = new byte[1024];
    private int nbit;

    public int stationId = 1;
    public int seqNo = 0;
    public Sta sta = new Sta();
    public Nav nav = new Nav();
    public Obs obs = new Obs();
    public int ephsat = 0;

    // 帧封装：preamble(0xD3) + length(0+6bit+10bit) + data + CRC-24Q
    public byte[] encode(int type, int subtype, int sync) {
        nbit = 24; // 跳过 preamble + length 域（编码完成后回填）
        boolean ok = false;
        switch (type) {
            case 1005: ok = Rtcm3StationEncoder.encodeType1005(this, sync); break;
            case 1006: ok = Rtcm3StationEncoder.encodeType1006(this, sync); break;
            case 1019: ok = Rtcm3EphEncoder.encodeType1019(this, sync); break;
            case 1020: ok = Rtcm3EphEncoder.encodeType1020(this, sync); break;
            case 1042: ok = Rtcm3EphEncoder.encodeType1042(this, sync); break;
            case 1045: ok = Rtcm3EphEncoder.encodeType1045(this, sync); break;
            default:
                if (type >= 1071 && type <= 1127)
                    ok = Rtcm3MsmEncoder.encodeMsm(this, type, sync);
                break;
        }
        if (!ok) return null;
        return frameBuffer();
    }

    // 回填长度 + 计算 CRC-24Q + 组装完整帧
    private byte[] frameBuffer() { ... }
}
```

#### Rtcm3MsmEncoder — MSM 消息编码

对应 C 版 `encode_msm_head` + `encode_msm1~7` + `gen_msm_index/sat/sig` 系列。

核心流程：
1. **gen_msm_index**：根据 obs 中的卫星和信号，生成卫星掩码（64 bit）、信号掩码（32 bit）、单元掩码
2. **encode_msm_head**：编码 MSM 头部（消息号、站 ID、历元时间、掩码）
3. **卫星数据字段**：整毫秒粗距、粗距小数、精度扩展
4. **信号数据字段**：伪距精距、载波精距、锁相指示、半周模糊度、CNR、多普勒

MSM4 与 MSM5 的区别：
- MSM4：伪距精距 15 bit，载波精距 22 bit，无扩展
- MSM5：伪距精距 22 bit，载波精距 29 bit，有扩展字段

#### Rtcm3StationEncoder — 站坐标编码

对应 C 版 `encode_type1005` / `encode_type1006`。

1005 帧格式：
```
msg_no(12) + staid(12) + itrf_year(6) + gps_ind(1) + glo_ind(1) + gal_ind(1) +
ref_ind(1) + arp_x(38) + osc_ind(1) + reserved(1) + arp_y(38) +
quarter_cycle(2) + arp_z(38)
```
ARP 坐标分辨率 0.0001 m，使用 `set38bits` 编码。

#### Rtcm3EphEncoder — 星历编码

对应 C 版 `encode_type1019` / `encode_type1020` / `encode_type1042` / `encode_type1045`。

各星历消息的 bit 域定义参照 RTCM 3.3 标准和 C 版实现，核心操作为：
- 缩放因子转换：`ROUND(value / scale)`
- 无符号/有符号 bit 写入：`setbitu` / `setbits`
- 特殊处理：GLONASS 频率号、BDS 时间转换（GPST→BDT）

### 5.4 Bit 操作工具

编码所需的 bit 操作**已有**于 rtklib-core：

| 工具 | 类 | 方法 | 说明 |
|------|-----|------|------|
| 无符号写入 | `BitUtils` | `setbitu(buff, pos, len, data)` | ✅ 已有 |
| 有符号写入 | `BitUtils` | `setbits(buff, pos, len, data)` | ✅ 已有 |
| CRC-24Q | `CrcUtils` | `rtk_crc24q(buff, len)` | ✅ 已有 |
| CRC-32Q | `CrcUtils` | `rtk_crc32q(buff, len)` | ✅ 已有 |

**需新增**：`set38bits`（1005/1006 中 38 bit 无符号字段，C 版用特殊处理实现）。

```java
// 在 BitUtils 中新增
public static void set38bits(byte[] buff, int pos, long data) {
    setbitu(buff, pos, 2, (int)((data >> 36) & 0x3));
    setbitu(buff, pos + 2, 32, (int)(data & 0xFFFFFFFFL));
    setbitu(buff, pos + 34, 4, (int)((data >> 32) & 0xF));
}
```

---

## 6. 核心类接口设计

### 6.1 VrsProcessor — 顶层处理器

```java
package org.rtklib.java.vrs;

public class VrsProcessor {

    public void process(VrsRequiredConfig required, VrsOptionalConfig optional) {
        required.validate();

        // 1. 构建虚拟站位置
        double[] vrsPos = new double[] {
            required.vrsLat * Constants.D2R,
            required.vrsLon * Constants.D2R,
            required.vrsHeight
        };

        // 2. 构建编码器
        Rtcm3Encoder encoder = new Rtcm3Encoder();
        encoder.stationId = optional.rtcmStationId;
        encoder.seqNo = optional.seqNo;

        // 3. 构建生成器
        VrsGenerator generator = new VrsGenerator(
            vrsPos, required.stationId, optional, encoder,
            required.outputRtcmPath
        );

        // 4. 构建解码器
        RtcmCallbackDecoder decoder = new RtcmCallbackDecoder(generator);

        // 5. 读取输入文件并驱动解码
        byte[] fileBytes = Files.readAllBytes(Paths.get(required.inputRtcmPath));
        decoder.feed(fileBytes, 0, fileBytes.length);
        decoder.finish();

        // 6. 关闭输出
        generator.close();
    }
}
```

### 6.2 VrsGenerator — 核心生成器

```java
package org.rtklib.java.vrs;

public class VrsGenerator implements RtcmDataHandler {

    private final double[] vrsPos;          // 虚拟站位置 (rad, rad, m)
    private final String stationId;
    private final VrsOptionalConfig opt;
    private final Rtcm3Encoder encoder;
    private final OutputStream outputStream;

    private final Nav nav = new Nav();      // 收集的星历
    private Sta baseSta;                    // 基站位置
    private boolean staWritten = false;     // 站坐标是否已输出

    @Override
    public void onStation(Sta sta) {
        this.baseSta = sta;
    }

    @Override
    public void onEph(Eph eph) {
        // 收集到星历 → 存入 nav
        nav.eph[eph.sat - 1] = eph;
        // 若配置透传，编码输出星历消息
        if (opt.passThroughEph) {
            encoder.nav = nav;
            encoder.ephsat = eph.sat;
            int msgType = ephMsgType(eph.sat);
            byte[] msg = encoder.encode(msgType, 0, 0);
            if (msg != null) writeMsg(msg);
        }
    }

    @Override
    public void onGeph(Geph geph) {
        nav.geph[geph.sat - 1] = geph;
        if (opt.passThroughEph) {
            encoder.nav = nav;
            encoder.ephsat = geph.sat;
            byte[] msg = encoder.encode(1020, 0, 0);
            if (msg != null) writeMsg(msg);
        }
    }

    @Override
    public void onObservationEpoch(ObservationEpoch epoch) {
        if (baseSta == null) return;

        // 首次输出虚拟站坐标
        if (!staWritten) {
            writeVrsStation();
            staWritten = true;
        }

        // VRS 改正
        Obs vrsObs = VrsCorrection.correct(
            epoch, nav, baseSta, vrsPos, opt
        );

        // 编码输出 MSM 观测值
        encoder.obs = vrsObs;
        encoder.sta = makeVrsSta();
        for (int sys : new int[]{SYS_GPS, SYS_GLO, SYS_GAL, SYS_CMP}) {
            if ((opt.navsys & sys) == 0) continue;
            int msgBase = msmMsgBase(sys, opt.msmType);
            byte[] msg = encoder.encode(msgBase, 0, 0);
            if (msg != null) writeMsg(msg);
        }
    }

    @Override
    public void onSsr(Ssr ssr) { /* 暂不处理 */ }

    @Override
    public void onAuxData(AuxData aux) { /* 暂不处理 */ }

    @Override
    public void onFinish() { /* 资源清理 */ }
}
```

### 6.3 VrsCorrection — 改正计算

```java
package org.rtklib.java.vrs;

public class VrsCorrection {

    public static Obs correct(ObservationEpoch epoch, Nav nav,
                              Sta baseSta, double[] vrsPos,
                              VrsOptionalConfig opt) {
        double elMaskRad = opt.elMaskDeg * Constants.D2R;

        double[] basePos = new double[3];
        CoordTransform.ecef2pos(baseSta.pos, basePos);

        double[] vrsEcef = new double[3];
        CoordTransform.pos2ecef(vrsPos, vrsEcef);

        Obs vrsObs = new Obs();
        vrsObs.n = 0;

        for (Obsd baseObsd : epoch.getObservations()) {
            if ((SatUtils.satsys(baseObsd.sat, null) & opt.navsys) == 0) continue;

            // 1. 卫星位置
            double[] rs = new double[3], dts = new double[3], vare = new double[1];
            EphModel.satpos(baseObsd.time, nav, baseObsd.sat, rs, dts, vare);
            if (rs[0] == 0.0 && rs[1] == 0.0 && rs[2] == 0.0) continue;

            // 2. 几何距离改正
            double[] eBase = new double[3], eVrs = new double[3];
            double rBase = RtklibCommon.geodist(rs, baseSta.pos, eBase);
            double rVrs  = RtklibCommon.geodist(rs, vrsEcef, eVrs);
            if (rBase <= 0.0 || rVrs <= 0.0) continue;
            double dGeom = rVrs - rBase;

            // 3. 方位角/高度角
            double[] azelBase = new double[2], azelVrs = new double[2];
            RtklibCommon.satazel(basePos, eBase, azelBase);
            RtklibCommon.satazel(vrsPos, eVrs, azelVrs);
            if (azelVrs[1] < elMaskRad) continue;

            // 4. 对流层差分改正
            double[] tropOut = new double[2];
            TroposphereModel.tropcorr(baseObsd.time, nav, basePos, azelBase, opt.tropModel, tropOut);
            double tropBase = tropOut[0];
            TroposphereModel.tropcorr(baseObsd.time, nav, vrsPos, azelVrs, opt.tropModel, tropOut);
            double tropVrs = tropOut[0];
            double dTrop = tropVrs - tropBase;

            // 5. 电离层差分改正
            double[] ionOut = new double[2];
            IonosphereModel.ionocorr(baseObsd.time, nav, baseObsd.sat, basePos, azelBase, opt.ionoModel, ionOut);
            double ionBase = ionOut[0];
            IonosphereModel.ionocorr(baseObsd.time, nav, baseObsd.sat, vrsPos, azelVrs, opt.ionoModel, ionOut);
            double ionVrs = ionOut[0];
            double dIon = ionVrs - ionBase;

            // 6. 改正观测值
            Obsd vrsObsd = new Obsd(baseObsd); // 深拷贝
            for (int f = 0; f < Constants.NFREQ; f++) {
                double freq = SatUtils.sat2freq(baseObsd.sat, baseObsd.code[f], nav);
                double lam = freq > 0.0 ? Constants.CLIGHT / freq : 0.0;

                // 伪距: P_vrs = P_base + dGeom + dTrop + dIon
                if (baseObsd.P[f] != 0.0) {
                    vrsObsd.P[f] = baseObsd.P[f] + dGeom + dTrop + dIon;
                }
                // 载波: L_vrs = L_base + (dGeom + dTrop - dIon) / λ
                if (baseObsd.L[f] != 0.0 && lam > 0.0) {
                    vrsObsd.L[f] = baseObsd.L[f] + (dGeom + dTrop - dIon) / lam;
                }
                // 多普勒: 直接复用
            }
            vrsObs.data[vrsObs.n++] = vrsObsd;
        }
        return vrsObs;
    }
}
```

---

## 7. 构建配置

### 7.1 pom.xml

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <parent>
        <groupId>org.rtklib</groupId>
        <artifactId>rtklib-java</artifactId>
        <version>3.2.0</version>
    </parent>

    <artifactId>rtklib-vrs</artifactId>
    <packaging>jar</packaging>
    <name>rtklib-vrs</name>
    <description>VRS (Virtual Reference Station) module</description>

    <dependencies>
        <dependency>
            <groupId>org.rtklib</groupId>
            <artifactId>rtklib-core</artifactId>
            <version>${project.version}</version>
        </dependency>
        <dependency>
            <groupId>org.junit.jupiter</groupId>
            <artifactId>junit-jupiter</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>
</project>
```

### 7.2 父 pom.xml 修改

在 `rtklib-java/pom.xml` 的 `<modules>` 中添加：

```xml
<module>rtklib-vrs</module>
```

---

## 8. 开发计划

### Phase 1：模块骨架 + VRS 改正算法（~1000 行）

| 任务 | 产出 | 预计工时 |
|------|------|----------|
| 创建模块目录和 pom.xml | `rtklib-vrs/` | 0.5h |
| 实现 `VrsRequiredConfig` + `VrsOptionalConfig` | 配置类 | 1h |
| 实现 `VrsCorrection` | 改正算法核心 | 4h |
| 实现 `VrsGenerator`（暂用 RINEX 输出验证） | 生成器 | 3h |
| 实现 `VrsProcessor` | 顶层入口 | 2h |
| 单元测试：改正量计算验证 | 测试 | 2h |

**里程碑**：给定 RTCM 文件 + 虚拟站位置，能计算出 VRS 改正后的 Obs 数据。

### Phase 2：RTCM3 编码器 — 站坐标 + 星历（~800 行）

| 任务 | 产出 | 预计工时 |
|------|------|----------|
| 实现 `Rtcm3Encoder` 主框架 + 帧封装 + CRC | 编码主入口 | 2h |
| 实现 `Rtcm3StationEncoder`（1005/1006） | 站坐标编码 | 2h |
| 实现 `Rtcm3EphEncoder`（1019/1020/1042/1045） | 星历编码 | 4h |
| `BitUtils.set38bits` 新增 | 工具方法 | 0.5h |
| 单元测试：编码→解码→比对 | 测试 | 2h |

**里程碑**：能输出 1005 + 星历消息的 RTCM 文件，且可被 `Rtcm.java` 正确解码。

### Phase 3：RTCM3 编码器 — MSM 观测值（~1500 行）

| 任务 | 产出 | 预计工时 |
|------|------|----------|
| 实现 MSM 索引生成（`gen_msm_index`） | 卫星/信号/单元掩码 | 3h |
| 实现 MSM 头部编码（`encode_msm_head`） | MSM 头部 | 2h |
| 实现 MSM4 编码（`encode_msm4`） | GPS/GLO/GAL/BDS | 4h |
| 实现 MSM5 编码（`encode_msm5`） | 扩展精度 | 2h |
| 实现 MSM 卫星/信号数据字段编码 | 精距/载波/锁相/CNR | 4h |
| 集成测试：完整 VRS 流程 | 端到端测试 | 3h |

**里程碑**：完整 VRS 流程：RTCM 文件 → VRS 改正 → RTCM 文件输出，可被标准 NTRIP Client 解码使用。

### Phase 4：扩展与优化

| 任务 | 说明 |
|------|------|
| MSM7 支持 | 全精度观测值编码 |
| SSR 改正支持 | 若输入含 SSR，用 SSR 替代广播星历提高改正精度 |
| 1230 GLONASS 频率号 | GLONASS 完整支持所需 |
| 多基站 VRS 扩展 | 网络 RTK 插值模型（MAC/iMAX/FKP），需额外设计 |

---

## 9. 测试策略

### 9.1 单元测试

| 测试类 | 测试内容 |
|--------|----------|
| `VrsCorrectionTest` | 改正量计算：零基线（vrs=base）改正量应为 0；已知基线改正量数值验证 |
| `Rtcm3StationEncoderTest` | 1005/1006 编码 → 解码 → 坐标比对 |
| `Rtcm3EphEncoderTest` | 1019/1020/1042/1045 编码 → 解码 → 星历参数比对 |
| `Rtcm3MsmEncoderTest` | MSM4/5 编码 → 解码 → 观测值比对 |

### 9.2 集成测试

| 测试 | 内容 |
|------|------|
| 端到端 VRS | 输入真实 RTCM 文件 → VrsProcessor.process() → 输出 RTCM 文件 → 解码验证 |
| 零基线回归 | vrsPos = basePos 时，输出观测值应与输入完全一致（改正量为 0） |
| 短基线验证 | 基线 < 1 km 时，VRS 伪距与真实站伪距差应 < 1 m |

### 9.3 验证方法

**编码正确性**：编码 → 解码 → 比对原始数据，这是最可靠的验证手段。

```
原始 obs/nav/sta → Rtcm3Encoder.encode() → byte[]
                                                    ↓
                                              Rtcm.input() 解码
                                                    ↓
                                              解码后 obs'/nav'/sta'
                                                    ↓
                                              比对 obs == obs', nav == nav', sta == sta'
```

---

## 10. 风险与注意事项

| 风险 | 影响 | 缓解措施 |
|------|------|----------|
| MSM 编码 bit 级操作复杂 | 编码错误难以调试 | 编码→解码→比对闭环验证 |
| 38 bit 字段编码 | 1005/1006 坐标精度 | 参照 C 版 `set38bits` 逐 bit 验证 |
| 信号 ID 映射 | MSM 信号掩码与观测值对应关系 | 严格按 RTCM 3.3 标准信号编号表 |
| GLONASS 频率号 | 1020 星历和 108x MSM 的 FN 字段 | 从 `Geph.frq` 获取，确保非零 |
| BDS 时间系统 | 1042 星历和 112x MSM 使用 BDT | `gpst2bdt` 转换，与 C 版对齐 |
| 电离层模型精度 | 基线 > 50 km 时差分残差增大 | 文档说明适用范围；后续支持 SSR |
| Obsd 深拷贝 | 改正时修改观测值不能影响原始数据 | `new Obsd(baseObsd)` 深拷贝 |

---

## 11. 与 VrsCaster 的关系

| 维度 | VrsCaster (C++) | rtk$lib-vrs (Java) |
|------|-----------------|---------------------|
| 定位 | NTRIP Caster + VRS 协议转发 | VRS 生成 + RTCM 文件处理 |
| VRS 算法 | 无（靠远端 VRS Server） | 自含（单基站改正算法） |
| RTCM 编码 | 无（只转发） | 有（翻译自 RTKLIB 2.5.1 rtcm3e.c） |
| 数据源 | 网络流（NTRIP） | RTCM 文件 |
| 协议 | 自定义 VRS 协议（0xDA 前导） | 标准 RTCM3 |
| 依赖 | RTKLIB 2.4.3 | rtklib-core（对齐 2.5.1） |

**结论**：rtklib-vrs 借鉴了 VrsCaster 的 VRS 概念，但实现完全独立，不依赖 VrsCaster 的自定义协议，使用标准 RTCM3 格式。