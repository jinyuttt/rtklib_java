# rtklib-vrs 模块设计开发文档

> **包路径**：`org.rtklib.java.vrs`
> **功能**：基于真实基站 RTCM 数据生成 VRS（Virtual Reference Station）虚拟参考站数据
> **算法来源**：单基站 VRS 改正基于标准差分 GNSS 理论；RTCM3 编码翻译自 RTKLIB 2.5.1 `rtcm3e.c`
> **依赖**：`rtklib-core`

## 0. 实现状态

| Phase | 内容 | 状态 |
|-------|------|------|
| Phase 1 | 模块骨架 + VRS 改正算法 | ✅ 完成 |
| Phase 2 | RTCM3 编码器（站坐标 + 星历） | ✅ 完成 |
| Phase 3 | RTCM3 编码器（MSM 观测值） | ✅ 完成（MSM4/MSM5） |
| Phase 4 | MSM7 / SSR / 1230 / 多基站 | ⬜ 未开始 |

**实测结论**（239 历元 BDS 双站数据，详见 §9.4）：

| 场景 | 基线 | FIX 率 | 3D-RMS |
|------|------|--------|--------|
| 原始基站 → 测站 RTK（基准） | 115 m | 91.2% | 5.4710 m |
| VRS（测站附近）→ 测站 RTK | 11 m | 91.2% | 5.4704 m |
| VRS（基站位置）→ 原始基站观测值逐位对比 | 0 m | — | 差值 0.0000 m |

VRS 编解码链路已闭环验证：P/L 数值、信号码、历元时间戳、LLI 全部与原始基站一致。

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

### 1.1 模块边界

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
    └── Rtcm3EphEncoder.java       # 星历透传编码（1019/1020/1042/1045）
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
- MSM4：伪距精距 15 bit，载波精距 22 bit，无扩展；锁相指示 4 bit（0~15）
- MSM5：伪距精距 22 bit，载波精距 29 bit，有扩展字段；锁相指示 10 bit（0~1023）

##### 信号码映射（`code2obs`）

`gen_msm_index` 与 `gen_msm_sig` 都依赖 **内部观测码 → RINEX 观测字符**的映射表，用于：

1. 判断该观测值属于哪个 MSM 信号槽位（`toSigid`）
2. 反查频率（`sat2freq`）以把载波周数换算成米

映射缺失会导致 `toSigid` 返回 0、该信号被**静默跳过**，最终编码出的观测值为 0。因此 `code2obs` 必须覆盖全部 `CODE_*` 常量。BDS 尤其容易漏（`CODE_L2I=40 → "2I"`、`CODE_L7I=27 → "7I"`、`CODE_L6I=42 → "6I"`、`CODE_L5P=58 → "5P"`、`CODE_L1P=2 → "1P"`、`CODE_L7D=61 → "7D"`）。

##### MSM 容量限制与信号裁剪（`selectSignals`）

RTCM MSM 的 `cellmask` 长度为 `nsat × nsig` 位，解码端（RTKLIB `decode_msm_head`）强制 `nsat × nsig ≤ 64`，超出即整条消息被丢弃。

当可见卫星数较多时（如 BDS 13 星 × 6 信号 = 78 > 64）单条消息装不下，有两种处理方式：

| 方案 | 做法 | 本模块实现 |
|------|------|-----------|
| 分批发送 | 按 `maxNsat = 64 / nsig` 切分卫星，多条消息用 `sync` 位串联累积 | ⬜ 未实现 |
| 信号裁剪 | 减少信号数至 `nsig ≤ 64 / nsat` | ✅ 已实现 |

当前采用**信号裁剪**，且裁剪策略为「频点覆盖最大化」的贪心选择，而非简单删除高位索引：

- 权重：频点索引 `fi ∈ {0,1}` → 1000，`fi ∈ [2, NFREQ)` → 100，扩展频点 → 1
- 每轮选出「能为最多卫星补齐尚未覆盖频点」的信号
- 效果：优先保住主频点双频组合，避免退化为单频

BDS 实例（13 星 → `maxSig = 64/13 = 4`，实测选取结果）：

```
裁剪前 6 信号: 2I(fi0) 7I(fi1) 7D(fi1) 5P(fi2) 6I(fi3) 1P(fi4)
裁剪后 4 信号: 2I(fi0) 7D(fi1) 7I(fi1) 6I(fi3)
```

频点 0(B1I)、1(B2I)、3(B3I) 保留，丢掉 B2b(5P) 与 B1C(1P)。注意 7D 与 7I 同为 B2I 频点，二者是同一载波的不同伪码分量——按「频点覆盖最大化」规则，先选样本数更多的 7D（约 11 星/历元），再选 7I（约 2 星/历元），因此出现同频点两个信号并存、而独立频点 5P 被裁掉的结果。B1I+B2I 双频组合完整保留，双频 RTK 不受影响。

##### 原始基站的分批实践（实测）

作为对照，原始接收机固件采用**分批发送**而非裁剪信号：BDS 每个历元发 2 条 1124，第一条 10 星 × 6 信号 = 60 ≤ 64，第二条 3~5 星，两条用 MSM 头的 `sync` 位串联。

```
实测 239 历元共 471 条 1124（逐 bit 解析原始字节得到）:
  第 1 条: nsat=10 nsig=6 sync=1  ×236   ← 60 ≤ 64，卡在上限附近
  第 2 条: nsat= 3 nsig=6 sync=0  ×108
           nsat= 4 nsig=6 sync=0  ×100
           nsat= 5 nsig=6 sync=0  × 27   ← 3/4/5 合计 235 条
```

可见卫星总数 13~15，被拆成 10 + (3~5) 两条；`nsig` 两条都保持 6，**不裁剪信号，靠拆分卫星**满足 64 限制。同文件的消息类型统计（1 小时 / 239 历元）：

```
663=1  1006=48  1042=48  1124=471  3099=1
```

即每 5 个历元（75 s）发一次 1006 站坐标与 1042 星历，观测只发 BDS（无 GPS/GAL/QZS）。

解码端靠 `obsflag` 累积同一同步帧的多条消息（[Rtcm.java](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtcm/Rtcm.java#L1749-L1752)）：

- 第 1 条消息 `sync=1` → 解码结束时 `obsflag=0`
- 第 2 条消息进入 `decodeMsmHead` 时 `obsflag==0` → **不清空**已解出的观测，直接追加
- 第 2 条 `sync=0` → `obsflag=1`，下一历元第 1 条消息进来时清空，开始新历元

因此原始文件能完整输出 6 信号 / 5 频点，而本模块单条消息方案只能裁剪到 4 信号。若要完全对齐，需按此规则实现分批。

##### 锁相指示（`lock`）与 LLI

MSM 的 `lock` 字段表示该信号**连续跟踪的历元数**（或失锁时间指示），解码端据此计算 RINEX `LLI`：

```java
// Rtcm.java lossoflock()
lli = (lock == 0 && prevLock == 0) || lock < prevLock ? 1 : 0;   // 1 = 失锁
```

**这是最容易踩的坑**：若编码器把 `lock` 恒写 0，解码端每个历元都会得到 `LLI=1`（失锁），RTK 端 `udbias` 随即把 `ssat.lock[f]` 重置为 `-minlock`（默认 -20），需要连续 20 个历元无失锁才能重新参与模糊度固定——结果就是 **ratio 恒为 0、永远无法 FIX**。

本模块的实现（`updateLock`）：编码器持有 `lockCnt[sat][sig]` / `lockEpoch[sat][sig]` 状态，逐历元递增：

```java
boolean lost = (lli & 1) != 0                          // 源数据本身标记失锁
            || lockEpoch[si][sj] != msmEpochSeq - 1;   // 该信号上历元缺失
if (lost) lockCnt[si][sj] = 0;
else if (lockCnt[si][sj] < maxLock) lockCnt[si][sj]++; // MSM4 饱和 15，MSM5 饱和 1023
lockEpoch[si][sj] = msmEpochSeq;                       // 记录本次观测历元
```

其中 `msmEpochSeq` 由 `VrsGenerator` 在每个观测历元递增，用于判断「上历元是否观测到该信号」。

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

        // 每历元重选星历：按 |toe - 当前时刻| 最小者写入 nav.eph[sat-1]
        selectEphemerisForEpoch(epoch.getTime());

        // VRS 改正
        Obs vrsObs = VrsCorrection.correct(
            epoch, nav, baseSta, vrsPos, opt
        );

        // 编码输出 MSM 观测值
        encoder.obs = vrsObs;
        encoder.sta = makeVrsSta();
        encoder.nav = nav;
        encoder.msmEpochSeq++;      // 驱动 MSM lock 字段的历元连续性判断
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

#### 星历池与逐历元选择

`VrsProcessor` 对输入文件做两遍处理：

1. **预加载**：解码整个文件，把全部 `Eph` / `Geph` 收集进 `List<Eph> ephPool` / `List<Geph> gephPool`，**不写入 `nav.eph` 槽位**
2. **生成**：第二遍逐历元处理，每历元调用 `selectEphemerisForEpoch()`，为每颗卫星挑出 `toe` 与当前时刻最接近的星历

**为什么不能覆盖式存储**：`Nav.eph[sat-1]` 每颗卫星只有一个槽位，若按消息到达顺序覆盖，最终只保留最后一条星历。对 239 历元（约 1 小时）的文件，首历元会错误使用 1 小时后的星历，卫星位置严重偏离，VRS 改正量随之失效。改为「池 + 逐历元选择」后，VRS 输出与原始基站的载波相位差回到 0.0000 m。

> `onEph()` 仍会把星历写入 `nav.eph[sat-1]`（供 `passThroughEph` 透传编码使用），但生成 VRS 改正时以 `selectEphemerisForEpoch()` 的结果为准。

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

> 实际进度见 §0。以下为原始计划，保留作为范围界定依据；Phase 1~3 已完成，Phase 4 未开始。

### Phase 1：模块骨架 + VRS 改正算法（~1000 行）✅

| 任务 | 产出 | 预计工时 |
|------|------|----------|
| 创建模块目录和 pom.xml | `rtklib-vrs/` | 0.5h |
| 实现 `VrsRequiredConfig` + `VrsOptionalConfig` | 配置类 | 1h |
| 实现 `VrsCorrection` | 改正算法核心 | 4h |
| 实现 `VrsGenerator`（暂用 RINEX 输出验证） | 生成器 | 3h |
| 实现 `VrsProcessor` | 顶层入口 | 2h |
| 单元测试：改正量计算验证 | 测试 | 2h |

**里程碑**：给定 RTCM 文件 + 虚拟站位置，能计算出 VRS 改正后的 Obs 数据。

### Phase 2：RTCM3 编码器 — 站坐标 + 星历（~800 行）✅

| 任务 | 产出 | 预计工时 |
|------|------|----------|
| 实现 `Rtcm3Encoder` 主框架 + 帧封装 + CRC | 编码主入口 | 2h |
| 实现 `Rtcm3StationEncoder`（1005/1006） | 站坐标编码 | 2h |
| 实现 `Rtcm3EphEncoder`（1019/1020/1042/1045） | 星历编码 | 4h |
| `BitUtils.set38bits` 新增 | 工具方法 | 0.5h |
| 单元测试：编码→解码→比对 | 测试 | 2h |

**里程碑**：能输出 1005 + 星历消息的 RTCM 文件，且可被 `Rtcm.java` 正确解码。

### Phase 3：RTCM3 编码器 — MSM 观测值（~1500 行）✅

| 任务 | 产出 | 预计工时 |
|------|------|----------|
| 实现 MSM 索引生成（`gen_msm_index`） | 卫星/信号/单元掩码 | 3h |
| 实现 MSM 头部编码（`encode_msm_head`） | MSM 头部 | 2h |
| 实现 MSM4 编码（`encode_msm4`） | GPS/GLO/GAL/BDS | 4h |
| 实现 MSM5 编码（`encode_msm5`） | 扩展精度 | 2h |
| 实现 MSM 卫星/信号数据字段编码 | 精距/载波/锁相/CNR | 4h |
| 集成测试：完整 VRS 流程 | 端到端测试 | 3h |

**里程碑**：完整 VRS 流程：RTCM 文件 → VRS 改正 → RTCM 文件输出，可被标准 NTRIP Client 解码使用。

### Phase 4：扩展与优化 ⬜

| 任务 | 说明 |
|------|------|
| MSM7 支持 | 全精度观测值编码 |
| SSR 改正支持 | 若输入含 SSR，用 SSR 替代广播星历提高改正精度 |
| 1230 GLONASS 频率号 | GLONASS 完整支持所需 |
| 多基站 VRS 扩展 | 网络 RTK 插值模型（MAC/iMAX/FKP），需额外设计 |

---

## 9. 测试策略

### 9.1 单元测试

| 测试类 | 状态 | 测试内容 |
|--------|------|----------|
| `Rtcm3MsmEncoderTest` | ✅ 已实现（5 用例） | MSM4/5 编码 → 解码 → 观测值比对（GPS/BDS 双频） |
| `VrsGeneratorTest` | ✅ 已实现（6 用例） | 输出消息顺序、端到端流程、双差模糊度自洽 |
| `VrsCorrectionTest` | ⬜ 未实现 | 零基线（vrs=base）改正量应为 0；已知基线改正量数值验证 |
| `Rtcm3StationEncoderTest` | ⬜ 未实现 | 1005/1006 编码 → 解码 → 坐标比对 |
| `Rtcm3EphEncoderTest` | ⬜ 未实现 | 1019/1020/1042/1045 编码 → 解码 → 星历参数比对 |

### 9.2 集成测试

| 测试 | 状态 | 内容 |
|------|------|------|
| 端到端 VRS | ✅ | 真实 RTCM 文件 → VrsProcessor → 输出 RTCM → 解码验证 |
| 零基线回归 | ✅ | vrsPos = basePos 时，输出观测值应与输入逐位一致 |
| RTK 反向验证 | ✅ | 原始基站+测站 vs VRS+测站，对比 FIX 率与 3D-RMS |
| 信号集分析 | ✅ | 统计原始基站每系统实际输出信号数/频点数 |
| 短基线验证 | ⬜ | 基线 < 1 km 时，VRS 伪距与真实站伪距差应 < 1 m |

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

**VRS 端到端验证的四个层次**（由弱到强）：

| 层次 | 判据 | 能发现的问题 |
|------|------|-------------|
| L1 数值非零 | 输出 P/L/SNR 不全为 0 | 信号码映射缺失、索引错误 |
| L2 逐位一致 | VRS 设在基站位置时，输出 == 原始基站观测值 | 编解码链路、时间戳、单位换算 |
| L3 改正自洽 | 双差残差 ≈ 0，双差模糊度保持不变 | 几何/对流层/电离层改正量错误 |
| L4 RTK 反向验证 | VRS+测站 RTK 的 FIX 率与精度不劣于原始基站+测站 | 只有这一层能发现 LLI/lock 类「数值对但状态错」的问题 |

> **教训**：L1~L3 全部通过（P/L 逐位一致、残差 0.0000 m）时，VRS 仍可能 0% FIX。原因是 MSM 的 `lock` 字段与由此派生的 `LLI` 不在数值对比范围内，而它直接决定 RTK 能否启动模糊度固定。**任何 VRS 改动都必须跑 L4。**

### 9.4 实测结果

**测试数据**（真实双站 RTCM3，239 历元 / 15 s 采样 / 约 1 小时）：

| 项 | 值 |
|---|---|
| 数据根目录 | `D:\tdengine-jetlinks\jetlinks-data\device_rtcmbin_storage` |
| 基站 | `GS2025090017\2026-10-27\8.rtcm3`（108,003 B） |
| 测站 | `GS2025090003\2026-10-27\8.rtcm3`（115,684 B） |
| 基站坐标 | 28.910469588°N, 101.176917113°E, 2165.3744 m |
| 测站坐标 | 28.909490107°N, 101.177305182°E, 2156.2988 m |
| 基线 | ≈ 115 m |
| 观测内容 | BDS 13~15 星，6 信号 / 5 频点（2I、7I、7D、5P、6I、1P） |

**RTK 对比结果**：

```
原始基站-测站   基线=115m  FIX=218/239(91.2%)  FLOAT=20  SINGLE=1  3D-RMS=5.4710m
VRS-测站        基线= 11m  FIX=218/239(91.2%)  FLOAT=20  SINGLE=1  3D-RMS=5.4704m
```

两者 FIX 历元数完全一致，3D-RMS 相差 0.6 mm（5.4710 → 5.4704），说明 VRS 改正未引入额外误差。绝对 RMS 为米级，源于测试中使用的测站坐标与实际天线相位中心不一致，属系统性偏移，两路一致出现，与 VRS 无关。

**逐位一致性验证**（VRS 设在基站位置）：

```
扫描历元=239  含跳变历元=0  max|dL0|=0.0000m
全历元 LLI_SLIP 计数: 原始基站=0  VRS输出=6   ← 仅首历元初始失锁
历元时间戳: 完全匹配（W2442_172830.000 起）
信号码: 完全一致
```

修复前 `VRS输出 LLI_SLIP 计数 = 3240`（每历元每观测都置 1），修复后降至 6。

**测试类清单**：

| 测试类 | 用例数 | 覆盖内容 |
|--------|--------|----------|
| `Rtcm3MsmEncoderTest` | 5 | MSM4/MSM5 编码→解码闭环，GPS/BDS 双频 |
| `VrsGeneratorTest` | 6 | RTCM 消息顺序分析、完整流程输出、双差模糊度自洽 |
| `VrsRealDataTest` | 6 | 真实双站数据：改正、端到端、RTK 反向验证、逐位对比、信号集分析、VRS@基站分流诊断 |

> **注意**：`VrsRealDataTest` / `VrsGeneratorTest` 依赖本地绝对路径数据，数据缺失时 `@BeforeAll` 会置 `dataAvailable=false`，用例打印「数据不可用」后直接 return —— 表现为**测试通过但实际未执行**。在 CI 或其他机器上需替换数据路径或跳过该模块。

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

### 10.1 已踩过的坑（务必回归）

| 问题 | 现象 | 根因 | 修复 |
|------|------|------|------|
| `code2obs` 映射缺失 | VRS 输出观测值**全为 0** | BDS `CODE_L2I(40)` 无映射 → `toSigid` 返回 0 → 信号被静默跳过 | 补齐全部 70 个 `CODE_*` → RINEX 字符映射 |
| 简单裁剪高位信号 | VRS 退化为**单频**，RTK 无法固定 | 原逻辑从高位索引删信号，恰好删掉 B2I(7I) | 改为「频点覆盖最大化」贪心选择 `selectSignals` |
| MSM `lock` 恒为 0 | VRS 的 P/L 与基站**逐位一致**，但 FIX 率 **0%**，ratio 恒 0 | 解码端 `lossoflock` 判 `lock==0 && prev==0` → `LLI=1`；RTK 每历元重置 `ssat.lock=-20`，永远达不到 AR 门槛 | 编码器维护 `lockCnt`/`lockEpoch` 逐历元递增，`VrsGenerator` 每历元递增 `msmEpochSeq` |
| 星历覆盖式存储 | VRS 与原始基站的观测值差达米级~数十米，且随文件长度增大而恶化 | `nav.eph[sat-1]` 单槽位被最后一条星历覆盖，首历元用了数十分钟后的星历 | 星历池 + `selectEphemerisForEpoch()` 逐历元按 `toe` 最近选择 |
| BDS MSM 时间戳 | 时间戳不匹配 | 编码端与解码端各自对 BDT/GPST 偏移处理不一致会差 14 s | 编解码两端均对 BDS tow 做 +14 s 补偿，保持一致 |

### 10.2 已知限制

| 限制 | 说明 | 影响 |
|------|------|------|
| 单条 MSM 消息 | 未实现分批 + `sync` 累积，`nsat×nsig > 64` 时只能裁剪信号 | VRS 输出信号数少于原始文件（BDS 4 vs 6） |
| 信号裁剪贪心权重 | `fi∈{0,1}` 权重 1000 远高于 `fi≥2` 的 100，同频点冗余信号（7D/7I 同为 B2I）会挤掉独立频点（5P/B2b） | 受限时覆盖的频点数可能少于原始文件；若需对齐须实现分批 |
| 单基站 VRS | 不支持 MAC / iMAX / FKP 网络插值 | 基线 > 30 km 时改正精度下降 |
| 仅离线文件 | 输入输出均为 `byte[]`/文件，无流式接口 | 不支持实时流；但 15 s 批量提交的原始数据可直接用 |
| 测试数据单一 | 仅 BDS 为主的单组数据 | GPS/GAL/QZS 多系统路径未做真实数据验证 |

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