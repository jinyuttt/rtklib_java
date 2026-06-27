# SPP（单点定位）处理流程文档

## 1. 概述

SPP（Single Point Positioning）使用伪距观测值计算接收机位置。本项目提供两种 SPP 封装：

| 封装类 | 输入源 | 包路径 | 适用场景 |
|--------|--------|--------|----------|
| `SppProcessor` | RTCM 实时流/文件 | `org.rtklib.java.pntpos` | 网络实时流、RTCM 文件批量处理 |
| `RinexSppProcessor` | RINEX 观测+导航文件 | `org.rtklib.java.rinex` | 事后处理、RINEX 文件分析 |

两者共享核心算法 `SppCore`，区别仅在于数据输入方式。

---

## 2. 整体流程图

```
输入数据
  │
  ├── RTCM 数据 ──→ SppProcessor ──→ Rtcm 解析 ──→ 观测历元 + 星历
  │
  └── RINEX 文件 ──→ RinexSppProcessor ──→ RinexParser 解析 ──→ 观测历元 + 星历
                                                              │
                                                              ▼
                                                     按历元分组 (groupObsByEpoch)
                                                              │
                                                              ▼
                                              ┌──────────────────────────────┐
                                              │     逐历元 SPP 处理循环       │
                                              │                              │
                                              │  1. 卫星位置计算 (satposs)    │
                                              │  2. 迭代最小二乘 (estpos)     │
                                              │     ├─ 残差计算 (rescode)     │
                                              │     │  ├─ 几何距离            │
                                              │     │  ├─ 高度角/方位角        │
                                              │     │  ├─ 伪距改正 (prange)    │
                                              │     │  ├─ 电离层改正           │
                                              │     │  ├─ 对流层改正           │
                                              │     │  └─ 构建观测方程         │
                                              │     └─ LSQ 求解              │
                                              │  3. 输出结果                  │
                                              └──────────────────────────────┘
                                                              │
                                                              ▼
                                              结果输出：Sol 对象 / .pos 文件 / 回调
```

---

## 3. 核心算法详解

### 3.1 卫星位置计算 — `EphModel.satposs()`

**文件**: [EphModel.java](../src/main/java/org/rtklib/java/ephemeris/EphModel.java)

**功能**: 根据广播星历计算信号发射时刻的卫星位置和钟差。

**关键步骤**:
1. 计算信号传播时间：`t_s = t_recv - P/c`（伪距除以光速）
2. 选择最佳星历（时间差最小的有效星历）
3. 根据卫星系统调用对应的星历计算函数：
   - GPS/GAL/QZS/IRN → `eph2pos()`: 开普勒轨道计算
   - GLO → `geph2pos()`: 数值积分法
   - BDS → `eph2pos()` + GEO 卫星特殊处理（5° 倾角旋转）
4. 输出：卫星位置 `rs[6]`（x,y,z,vx,vy,vz）、钟差 `dts[2]`、方差 `vare`

**BDS 特殊处理**:
- 北斗 GEO 卫星（PRN 1-5）使用 5° 旋转矩阵修正
- BDT 与 GPST 存在 14 秒偏移：`BDT = GPST - 14s`
- 北斗卫星使用 `MU_CMP = 3.986004418E14` 和 `OMGE_CMP = 7.2921150E-5`

### 3.2 伪距改正 — `SppCore.prange()`

**文件**: [SppCore.java](../src/main/java/org/rtklib/java/pntpos/SppCore.java)

**功能**: 对原始伪距进行 TGD（群延迟）改正，支持单频和双频无电离层组合（IFLC）。

**单频模式** (`IONOOPT_BRDC`):
```
P_corrected = P1 - b1
```
其中 `b1` 为 TGD 改正：
- GPS/QZS: `b1 = tgd[0] * CLIGHT`
- BDS B1I: `b1 = tgd[0] * CLIGHT`
- BDS B1Cp: `b1 = tgd[2] * CLIGHT`
- BDS B1Cd: `b1 = (tgd[2] + tgd[4]) * CLIGHT`

**双频 IFLC 模式**:
```
P_iflc = (P2 - γ·P1 - (b2 - γ·b1)) / (1 - γ)
```
其中 `γ = (f1/f2)²`

### 3.3 电离层改正 — `IonosphereModel.ionocorr()`

**文件**: [IonosphereModel.java](../src/main/java/org/rtklib/java/ionosphere/IonosphereModel.java)

**支持模型**:
| 选项 | 模型 | 说明 |
|------|------|------|
| `IONOOPT_BRDC` | Klobuchar | GPS 广播电离层模型，使用 8 个 α/β 参数 |
| `IONOOPT_IFLC` | 无 | 双频无电离层组合，无需电离层改正 |
| `IONOOPT_OFF` | 无 | 不改正，方差设为 25m² |

**Klobuchar 模型流程**:
1. 计算穿刺点地理坐标
2. 计算地方时
3. 判断白天/夜间
4. 白天：计算振幅和周期，三次余弦模型
5. 夜间：固定延迟 5ns

**频率归一化**:
```
dion_L1 = dion × (f_L1 / f_actual)²
```

### 3.4 对流层改正 — `TroposphereModel.tropcorr()`

**文件**: [TroposphereModel.java](../src/main/java/org/rtklib/java/troposphere/TroposphereModel.java)

**支持模型**:
| 选项 | 模型 | 说明 |
|------|------|------|
| `TROPOPT_SAAS` | Saastamoinen | 标准对流层模型 |
| `TROPOPT_OFF` | 无 | 不改正 |

**Saastamoinen 模型**:
```
ZTD = 0.002277 / sin(E) × [P + (1255/T + 0.05)×e - tan²(E)×1.7]
```
- E: 高度角
- P: 大气压 (mbar)
- T: 温度 (K)
- e: 水汽压 (mbar)

### 3.5 最小二乘定位 — `SppCore.estpos()`

**文件**: [SppCore.java](../src/main/java/org/rtklib/java/pntpos/SppCore.java)

**待估参数向量** (NX 维):
| 索引 | 参数 | 说明 |
|------|------|------|
| 0-2 | x, y, z | 接收机 ECEF 坐标 |
| 3 | dtr | 接收机钟差（等效距离） |
| 4+ | dts_sys | 系统间钟差偏差（GLO/GAL/CMP/IRN 按需） |

**NX 计算**:
```
NX = 4 + (GLO启用?1:0) + (GAL启用?1:0) + (CMP启用?1:0) + (IRN启用?1:0)
```

**迭代流程** (最多 10 次):
1. 调用 `rescode()` 构建观测方程 `v = H·dx`
2. 按方差加权（归一化）
3. 调用 `RtklibCommon.lsq()` 求解 `dx`
4. 更新状态：`x += dx`
5. 收敛判断：`‖dx‖ < 1E-4`
6. 收敛后填充 `Sol` 对象

**rescode() 观测方程构建**:
```
v[i] = P[i] - (r[i] + dtr - c·dts_sat[i] + dion[i] + dtrp[i])
H: ∂v/∂x = [-e_x, -e_y, -e_z, 1, 0, ...]  (GPS卫星)
H: ∂v/∂x = [-e_x, -e_y, -e_z, 1, 0, ..., 1_sys, ...]  (非GPS卫星)
```

**系统间钟差处理**:
- GPS 卫星：`mask[0]=1`，钟差由 `x[3]` 吸收
- 非 GPS 卫星：`mask[si-3]=1`，额外参数 `x[si]` 吸收系统间偏差
- 无观测的系统：约束 `x[si]=0`（方差 0.01），避免秩亏

---

## 4. SppProcessor（RTCM 输入）

**文件**: [SppProcessor.java](../src/main/java/org/rtklib/java/pntpos/SppProcessor.java)

### 4.1 两种使用模式

**批量模式**:
```java
SppProcessor spp = new SppProcessor(opt, handler, outputStream);
SppResult result = spp.process("1.rtcm3");  // 一步完成
```

**流式模式**（网络实时数据）:
```java
SppProcessor spp = new SppProcessor(opt, handler, outputStream);
while (running) {
    byte[] chunk = networkStream.read();
    spp.feed(chunk);  // 每个历元立即定位并回调/写流
}
SppResult result = spp.finish();  // 数据输入结束
```

### 4.2 星历缓存机制

- 首次收到星历消息前，观测历元被缓存到 `pendingObsList`
- 星历就绪后（`ephReady=true`），缓存历元被修正时间并依次定位
- 之后新到达的观测历元立即定位，无需缓存

### 4.3 回调接口 — `PosHandler`

```java
public interface PosHandler {
    void onSolution(Sol sol, Ssat[] ssat);  // 定位成功
    void onPosFail(GTime time, String msg); // 定位失败
    void onFinish(int total, int success, int fail);  // 处理完成
}
```

---

## 5. RinexSppProcessor（RINEX 文件输入）

**文件**: [RinexSppProcessor.java](../src/main/java/org/rtklib/java/rinex/RinexSppProcessor.java)

### 5.1 使用方式

```java
// 便捷方法（默认选项）
SppResult result = RinexSppProcessor.processRinex("ROVER.obs", "ROVER.nav");

// 自定义选项
PrcOpt opt = RinexSppProcessor.createDefaultOpt();
opt.navsys = Constants.SYS_CMP;  // 仅北斗
SppResult result = RinexSppProcessor.processRinex("ROVER.obs", "ROVER.nav", opt);

// 实例模式 + 回调
RinexSppProcessor spp = new RinexSppProcessor(opt, handler, outputStream);
SppResult result = spp.process("ROVER.obs", "ROVER.nav");
```

### 5.2 处理流程

1. `RinexParser.parseObs()` 解析观测文件 → `Obs` 数据
2. `RinexParser.parseNav()` 解析导航文件 → `Nav` 数据
3. 从 RINEX 头部提取近似坐标（`APPROX POSITION XYZ`）作为初始值
4. `groupObsByEpoch()` 按时间戳分组观测数据
5. 逐历元调用 `processEpoch()`:
   - `EphModel.satposs()` 计算卫星位置
   - `SppCore.estpos()` 最小二乘定位
6. 输出结果

### 5.3 SppResult 结果对象

```java
public static class SppResult {
    int totalEpochs;   // 总历元数
    int successCount;  // 成功数
    int failCount;     // 失败数
    List<Sol> solutions; // 所有成功解
}
```

---

## 6. RTCM → RINEX → SPP 端到端流程

```
RTCM 文件 (.rtcm3)
       │
       ▼
RtcmFileToRinexConverter.convert()
       │
       ├──→ ROVER.obs (RINEX 3.05 观测文件)
       └──→ ROVER.nav (RINEX 3.05 导航文件)
              │
              ▼
       RinexSppProcessor.process("ROVER.obs", "ROVER.nav")
              │
              ▼
       SppResult (240/240 成功, ECEF 坐标, .pos 文件)
```

**代码示例**:
```java
// 步骤1: RTCM → RINEX
RtcmFileToRinexConverter converter = new RtcmFileToRinexConverter(3.05, outputDir, "ROVER");
converter.convert("1.rtcm3");

// 步骤2: RINEX → SPP
SppResult result = RinexSppProcessor.processRinex(
    outputDir + "/ROVER.obs", outputDir + "/ROVER.nav");
```

---

## 7. 默认配置

```java
PrcOpt opt = new PrcOpt();
opt.mode    = Constants.PMODE_SINGLE;   // 单点定位模式
opt.nf      = 2;                         // 使用2个频点
opt.navsys  = SYS_GPS|SYS_GLO|SYS_GAL|SYS_CMP;  // 四系统
opt.elmin   = 15.0 * D2R;               // 截止高度角15°
opt.ionoopt = Constants.IONOOPT_BRDC;    // 广播电离层模型
opt.tropopt = Constants.TROPOPT_SAAS;    // Saastamoinen对流层模型
```

---

## 8. 关键数据结构

| 类 | 说明 | 文件 |
|----|------|------|
| `Obsd` | 单颗卫星单个历元的观测数据（伪距、载波、SNR等） | `data/Obsd.java` |
| `Obs` | 观测数据集合（Obsd数组 + 数量） | `data/Obs.java` |
| `Nav` | 导航数据（Eph/Geph/Seph数组 + 电离层参数） | `data/Nav.java` |
| `Eph` | GPS/GAL/BDS/QZS/IRN 广播星历 | `data/Eph.java` |
| `Geph` | GLONASS 广播星历 | `data/Geph.java` |
| `Sol` | 定位解（ECEF坐标、协方差、钟差等） | `data/Sol.java` |
| `Ssat` | 单颗卫星的定位状态（方位角、高度角、残差等） | `data/Ssat.java` |
| `PrcOpt` | 处理选项 | `data/PrcOpt.java` |
| `GTime` | GNSS 时间（周+周内秒） | `data/GTime.java` |

---

## 9. 与 RTKLIB C 版本的差异

| 项目 | C 版本 | Java 版本 |
|------|--------|-----------|
| NX 参数维度 | 固定 4+4（或 4+5） | 动态计算，仅包含启用系统 |
| 系统钟差处理 | x[3] 为 GPS 钟差，其他系统为偏差 | x[3] 为公共钟差，非 GPS 系统额外参数 |
| NFREQ | 3 | 6（支持 BDS 全6频点） |
| RINEX 版本 | 支持 2.x/3.x | 仅支持 3.05/3.06 |
| BDS TGD | tgd[0-1] | tgd[0-5]（6频点 TGD） |