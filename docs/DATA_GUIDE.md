# 测试用例与数据指南

> **版本**：v1.0  
> **日期**：2026-10-09  
> **前置文档**：[test-data/README.md](../test-data/README.md)（数据规范）、[优化功能矩阵](CORE_OPTIMIZATION_MATRIX.md)（优化项定义）

---

## 0. 测试规范

### 0.1 数据分类

| 类型 | 存放位置 | 是否提交 | 说明 |
|------|----------|----------|------|
| 测试夹具 | `src/test/resources/` | ✅ 提交 | 单元测试必需的小文件（验证参考值、标准TLE等） |
| 公共测试数据 | `test-data/` | ✅ 提交 | GNSS观测/星历/参考解等，≤2MB，无隐私 |
| 私有测试数据 | 本地磁盘 | ❌ 不提交 | 大文件/含设备ID，通过 `test-data.properties` 配置 |
| 大文件数据 | 独立数据仓库 | ❌ 不提交 | >2MB，通过下载脚本获取 |

### 0.2 元数据文件规范（.meta）

每个测试数据文件须附带同名 `.meta` 文件，记录数据属性。

#### 必填字段

| 字段 | 说明 | 示例 |
|------|------|------|
| `scenario` | 数据场景 | `open-sky`, `urban`, `downtown`, `street`, `elevated`, `forest`, `indoor` |
| `role` | 数据角色 | `rover`, `base`, `reference`, `nav` |
| `systems` | 卫星系统 | `G`, `G+R`, `G+E+J+C`, `G+R+E+C+J+S` |
| `date` | 观测日期 | `2025-04-08` |
| `source` | 数据来源项目 | `MobileGNSS-SPP`, `Net_Diff`, `rtklib`, `IGS`, `GFZ` |

#### 选填字段

| 字段 | 说明 | 示例 |
|------|------|------|
| `sample_rate` | 采样间隔 | `1s`, `30s` |
| `duration` | 数据时长 | `30min`, `24h` |
| `receiver` | 接收机型号 | `UNISOC uis7865`, `u-blox`, `Leica GR50` |
| `antenna` | 天线型号 | `unknown`, `LEIAR25.R4` |
| `interval` | 采样间隔（同sample_rate） | `1s` |
| `base.id` | 基站标识（RTK数据） | `3040`, `VRS-001` |
| `base.distance` | 基线长度（RTK数据） | `1.2km`, `15km` |
| `mode` | 定位模式（参考解） | `spp`, `rtk`, `ppp`, `ppp-ar` |
| `software` | 解算软件（参考解） | `RTKLIB demo5 b34k`, `LAMBDA` |
| `license` | 数据许可 | `MIT`, `IGS-data-policy`, `public` |
| `description` | 自由描述 | `城市峡谷手机GNSS数据，含NLOS/多路径` |
| `location` | 采集地点 | `Beijing downtown`, `Hong Kong IGS` |
| `type` | 产品类型（精密产品） | `sp3`, `clk`, `ionex`, `atx`, `bia` |
| `test_ids` | 引用该数据的测试类 | `SppOptimizationTest,RinexFormatTest` |

#### .meta 文件示例

```
scenario=downtown
role=rover
systems=G+E+J+C
date=2025-04-08
source=MobileGNSS-SPP
sample_rate=1s
duration=30min
receiver=UNISOC uis7865_6h10_go_a32b
license=MIT
description=城市峡谷手机GNSS数据，含NLOS/多路径
location=Beijing downtown
test_ids=SppOptimizationTest
```

### 0.3 数据License

本仓库测试数据均可自由用于研究和测试。各数据源License如下：

| 数据源 | License | 说明 |
|--------|---------|------|
| MobileGNSS-SPP | MIT | 可自由使用、修改、分发 |
| cssrlib | MIT | 可自由使用、修改、分发 |
| Net_Diff | 无明确License | 公开样例数据，用于测试 |
| rtklib | BSD-style | RTKLIB原始测试数据 |
| IGS精密产品 | IGS Data Policy | 免费用于研究，需引用IGS |
| GFZ/CODE产品 | IGS Data Policy | 同IGS政策 |

> 所有测试数据仅供研究和测试使用。精密产品使用时请按IGS数据政策引用相应分析中心。

### 0.4 路径解析优先级

所有测试类通过 `TestDataConfig` 统一管理路径，按以下优先级解析：

```
1. 私有配置 (test-data.properties 中的显式路径)
2. 公共数据 (test-data/ 目录下的文件)
3. 占位符路径 (无数据时使用，测试自动跳过)
```

### 0.5 测试运行条件

| 测试类型 | 数据来源 | 运行条件 | 无数据时行为 |
|----------|----------|----------|-------------|
| 单元测试 | `src/test/resources/` 内置 | 始终运行 | N/A |
| 算法验证 | `test-data/` 公共数据 | 始终运行 | 跳过（AssumeTrue） |
| 精度验证 | 私有RTCM+精密产品 | 需本地配置 | 跳过 |
| 集成测试 | 私有base/rover对 | 需本地配置 | 跳过 |

### 0.6 获取测试数据

```powershell
# 下载大文件数据（从独立数据仓库）
./test-data/download-test-data.ps1

# 配置私有数据路径
cp rtklib-core/src/test/resources/test-data.properties.template `
   rtklib-core/src/test/resources/test-data.properties
# 编辑填入本地路径
```

---

## 1. 测试类总览

### 1.1 按定位模式分类

| 测试类 | 定位模式 | 数据依赖 | 数据来源 | 无数据可运行 |
|--------|----------|----------|----------|-------------|
| **SppTest** | SPP | RTCM3 (G+R+C) | 公共: rtcm3_gmsd | ✅ |
| **SppProcessorTest** | SPP | RTCM3 (G+R+C) | 公共: rtcm3_gmsd | ✅ |
| **SppOptimizationTest** | SPP | RINEX3.04 (G+E+J+C) | 公共: rinex304_gejc_elevated + 数据仓库4场景 | ✅ |
| **SppCompareTest** | SPP | RINEX2.10 (BDS) | **私有: RTKLIB_EX_2.5.0** | ❌ |
| **SppSmoothAndSbasTest** | SPP | 无（纯数值） | 内置 | ✅ |
| **RinexSppTest** | SPP | RTCM3→RINEX→SPP | 公共: rtcm3_gmsd + open-sky_base | ✅ |
| **RinexSppProcessorTest** | SPP | RTCM3→RINEX→SPP | 公共: rtcm3_gmsd | ✅ |
| **RtkTest** | RTK | RINEX2.10 (GPS双频) | 公共: 0759+3040 | ✅ |
| **RtkProcessorTest** | RTK | RINEX2.10 (GPS双频) | 公共: 0759+3040 | ✅ |
| **RtkLocalTest** | RTK | RINEX2.10 (GPS双频) | 公共: 0759+3040 | ✅ |
| **RtkOptimizationTest** | RTK | RINEX2.10 (GPS双频) | 公共: 0759+3040 | ✅ |
| **RtkOptimizationIndividualTest** | RTK | RINEX2.10 (GPS双频) | 公共: 0759+3040 | ✅ |
| **RtkArDebugTest** | RTK | RINEX2.10 (GPS双频) | 公共: 0759+3040 | ✅ |
| **RtkTraceTest** | RTK | RINEX2.10 (GPS双频) | 公共: 0759+3040 | ✅ |
| **LogTraceTest** | RTK | RINEX2.10 (GPS双频) | 公共: 0759+3040 | ✅ |
| **RtkIonoptTest** | RTK | RTCM3 (私有设备ID) | **私有: rtcm.base.dir** | ❌ |
| **LandslideMonitorTest** | RTK | RTCM3 (G+R+C) + RTCM3 | 公共: rtcm3_gmsd + open-sky_base | ✅ |
| **ForwardBackwardFilterTest** | RTK | RINEX2.10 + NAV | 公共: 0759+3040+0759n | ✅ |
| **VerifyPositioningTest** | SPP/RTK/RTK-Static/DGPS/RTK-Fixed | RINEX2.10 + NAV | 公共: 0759+3040+0759n+3040n | ✅ |
| **GreatPvtPositioningTest** | SPP/RTK/RTK-Static/PPP/PPP-Static/PPP-AR/PPP-AR+FixHold/PPP+GPT3/PPP+IERS/PPP+ISB | RINEX3.04+SP3+CLK+UPD | **私有: GREAT-PVT** | ❌ |
| **PppOptimizationsTest** | PPP | 无（纯配置验证） | 内置 | ✅ |
| **PppArFixVerificationTest** | PPP-AR | 无（构造输入） | 内置 | ✅ |
| **B1OsbEquivalenceTest** | PPP-AR | 无（构造输入） | 内置 | ✅ |
| **RtkOptimizationsBdsBiasTest** | RTK | 无（纯数值） | 内置 | ✅ |
| **RtkOptimizationsPartialARTest** | RTK | 无（纯数值） | 内置 | ✅ |
| **RtkOptimizationsCascadeARTest** | RTK | 无（纯数值） | 内置 | ✅ |
| **RtkOptimizationsResEditTest** | RTK | 无（纯数值） | 内置 | ✅ |
| **RtkOptimizationsBootstrapTest** | RTK | 无（纯数值） | 内置 | ✅ |
| **RtkOptimizationsConfigTest** | RTK | 无（配置+数值） | 内置 | ✅ |
| **ProductReaderTest** | 产品读取 | SP3+CLK+IONEX+OSB+ATX | 公共: product/ | ✅ |
| **CompactSsrDecoderTest** | PPP-RTK | 无（构造输入） | 内置 | ✅ |
| **SampleDataRtcmTest** | 数据解析 | RTCM3 (私有消息) | 公共: open-sky_base | ✅ |
| **RtcmFileParserTest** | 数据解析 | RTCM3 (私有消息) | 公共: open-sky_base | ✅ |
| **RtcmSampleDataTest** | 数据解析 | RTCM3 + TLE | 公共: open-sky_base + tle | ✅ |
| **RtcmCallbackDecoderTest** | 数据解析 | RTCM3 | 公共: open-sky_base | ✅ |
| **RtcmOutputCheckTest** | 数据解析 | RTCM3 (G+R+C) | 公共: rtcm3_gmsd | ✅ |
| **RinexConversionTest** | 数据转换 | RTCM3→RINEX | 公共: rtcm3_gmsd + open-sky_base | ✅ |
| **RinexObsWriterTest** | 数据转换 | RTCM3→RINEX | 公共: open-sky_base | ✅ |
| **Sgp4PropagatorTest** | 轨道 | TLE标准验证数据 | 内置: sgp4-ver.tle | ✅ |
| **OrbitalElementsTest** | 轨道 | TLE标准验证数据 | 内置: sgp4-ver.tle | ✅ |
| **AmbiguityStateSerializationTest** | 序列化 | 无（构造输入） | 内置 | ✅ |
| **EpochCacheTest** | 缓存 | 无（构造输入） | 内置 | ✅ |

### 1.2 必须使用私有数据的测试

| 测试类 | 原因 | 所需数据 | 配置方式 |
|--------|------|----------|----------|
| **RtkIonoptTest** | 需要完整RTCM3基站+流动站多小时数据 | 设备ID+日期+时段 | `test-data.properties` 中 `baserover.group1` + `ionopt.date` |
| **SppCompareTest** | 需要RTKLIB C版参考结果文件 | RTKLIB_EX_2.5.0目录 | 硬编码路径 `D:\rtklib\rtklib_java\RTKLIB_EX_2.5.0` |
| **GreatPvtPositioningTest** | 需要GREAT-PVT多系统精密数据+UPD产品 | RINEX3.04+SP3+CLK+UPD(WL/NL) | `test-data.properties` 中 `greatpvt.ppp.dir` + `greatpvt.rtk.dir` |

### 1.3 数据覆盖与缺口分析

#### ✅ 已有真实数据覆盖的场景

| 场景 | 数据来源 | 数据类型 | 动态/静态 | 说明 |
|------|----------|----------|-----------|------|
| **城市峡谷SPP** | MobileGNSS-SPP downtown | RINEX3.04 G+E+J+C 单频 | **动态**（~140m移动） | 手机城市峡谷，含NLOS/多路径 |
| **街道SPP** | MobileGNSS-SPP street | RINEX3.04 G+E+J+C 单频 | **动态**（~250m移动） | 手机街道场景，部分遮挡 |
| **开阔地SPP** | MobileGNSS-SPP opensky | RINEX3.04 G+E+J+C 单频 | **动态**（~6km移动） | 手机开阔地，车载长距离 |
| **高架桥SPP** | MobileGNSS-SPP elevated | RINEX3.04 G+E+J+C 单频 | **动态**（~500m移动） | 手机高架桥场景 |
| **城市RTK** | Net_Diff urban | RINEX3.02 G+E+J+C 双频 | **准静态**（~3m微动） | base+rover对，含参考固定解 |
| **开阔地RTK** | rtklib test | RINEX2.10 GPS 双频 | 静态 | 短基线0759+3040 |
| **PPP** | Net_Diff | RINEX3.02+SP3+CLK+IONEX | 静态/动态 | wtza/wtzr/hksl站 |
| **多系统PPP** | GREAT-PVT PPPFLT | RINEX3.04 G+E+C+R + CODE SP3/CLK | 静态 | IGS站GODN, 30s采样 |
| **多系统RTK** | GREAT-PVT RTKFLT | RINEX3.03/3.04 G+E+C+R+J base+rover | 动态 | SEPT+WUDA, 1s采样 |
| **PPP-AR (WL+NL)** | GREAT-PVT PPPFLT | RINEX3.04 G+E+C + CODE SP3/CLK + WHU UPD(WL/NL) | 静态/动态 | GODN站, GPS双频, Fix-and-Hold |

#### ⚠️ 有数据但不完整的场景

| 场景 | 当前数据 | 缺口 | 欢迎上传 |
|------|----------|------|----------|
| **城市峡谷RTK** | 有SPP数据(MobileGNSS downtown)，有准静态RTK(Net_Diff urban) | 缺**动态**城市峡谷RTK base+rover对 | ✅ |
| **PPP-AR完整验证** | 有精密产品(SP3+CLK+UPD+OSB+ATX) | 缺24h长时段+参考Fixed解对比 | ✅ |
| **多频(≥3频)RTK** | 有BDS双频数据 | 缺三频BDS/GAL数据 | ✅ |

#### ❌ 根本没有真实数据测试的场景

| 场景 | 需要的数据 | 欢迎上传 |
|------|-----------|----------|
| **峡谷/深城市RTK** | 两侧高楼遮挡，天空角严重受限的base+rover对 | ✅ |
| **车载动态RTK** | 真实**高速动态**轨迹base+rover（>50km/h） | ✅ |
| **长基线RTK (>10km)** | 长基线base+rover，需估计大气参数 | ✅ |
| **PPP-RTK实时** | SSR实时流数据 | ✅ |
| **森林/林冠** | 树冠遮挡，信噪比低 | ✅ |
| **室内定位** | 严重遮挡 | ✅ |

---

## 2. 公共测试数据文件清单

### 2.1 RINEX 观测文件

| 文件 | 格式 | 系统 | 场景 | 角色 | 用途 |
|------|------|------|------|------|------|
| `rinex210_gps_0759.05o` | RINEX 2.10 | G | open-sky | rover | RTK流动站(GPS双频L1/L2) |
| `rinex210_gps_3040.05o` | RINEX 2.10 | G | open-sky | base | RTK基站(GPS双频L1/L2) |
| `rinex304_gej_3034.21o` | RINEX 3.04 | G+E+J | open-sky | rover | 多系统1min采样 |
| `rinex304_gej_sept.21o` | RINEX 3.04 | G+E+J | open-sky | rover | Septentrio接收机1min |
| `rinex304_gejc_elevated.25o` | RINEX 3.04 | G+E+J+C | urban | rover | 手机高架桥场景(SPP优化测试) |
| `rinex304_bds_base.26o` | RINEX 3.04 | C | open-sky | base | BDS多频基站 |
| `rinex302_bds_base.26o` | RINEX 3.02 | C | open-sky | base | BDS单频基站 |
| `rinex302_bds_rover.26o` | RINEX 3.02 | C | open-sky | rover | BDS流动站 |

### 2.2 RINEX 导航文件

| 文件 | 格式 | 系统 | 用途 |
|------|------|------|------|
| `rinex210_gps_0759.05n` | RINEX 2.10 | G | GPS导航星历(配合0759观测) |
| `rinex210_gps_3040.05n` | RINEX 2.10 | G | GPS导航星历(配合3040观测) |
| `rinex304_mixed_sept.21p` | RINEX 3.04 | G+E+J | 多系统混合导航 |
| `rinex304_gejc_elevated.25n` | RINEX 3.04 | G+E+J+C | 手机场景导航星历 |
| `rinex302_qzss.21q` | RINEX 3.02 | J | QZSS导航星历 |
| `rinex302_bds_rover.26n` | RINEX 3.02 | C | BDS导航星历 |

### 2.3 RTCM3 文件

| 文件 | 系统 | 场景 | 角色 | 用途 |
|------|------|------|------|------|
| `open-sky_base_20090515.rtcm3` | G | open-sky | base | VRS差分改正数流(含NovAtel私有消息) |
| `rtcm3_gmsd_20121014.rtcm3` | G+R+C | open-sky | rover | 标准RTCM3消息(G+R+C三系统) |

### 2.4 精密产品

| 文件 | 类型 | 用途 |
|------|------|------|
| `igs15904.sp3` | SP3精密轨道 | IGS最终轨道 |
| `igs15904.clk` | CLK精密钟差 | IGS最终钟差 |
| `igsg1570.18i` | IONEX电离层 | IGS电离层格网 |
| `test.atx` | ANTEX天线改正 | 天线PCV/PCO改正 |
| `cod_osb_2021265.bia` | Bias-SINEX | CODE码偏差(OSB) |

### 2.5 配置文件

| 文件 | 用途 |
|------|------|
| `open-sky_rtk_kinematic.conf` | 开阔地RTK动态定位参考配置 |

---

## 3. 测试用例详情

### 3.1 SPP 单点定位测试

#### SppTest — SPP基础定位

| 项目 | 说明 |
|------|------|
| 数据 | `rtcm/rtcm3_gmsd_20121014.rtcm3` (G+R+C三系统) |
| 模式 | SPP (PMODE_SINGLE) |
| 配置 | navsys=SYS_CMP, nf=2, elmin=15°, IONOOPT_BRDC, TROPOPT_SAAS |
| 验证 | 输出.pos文件，历元数>0，成功定位数>0 |
| 场景 | open-sky，BDS单系统 |

#### SppOptimizationTest — SPP优化对比

| 项目 | 说明 |
|------|------|
| 数据 | `rinex/rinex304_gejc_elevated.25o` + `nav/rinex304_gejc_elevated.25n` (G+E+J+C四系统) |
| 数据来源 | MobileGNSS-SPP项目，手机UNISOC芯片采集的**动态**数据 |
| 模式 | SPP (PMODE_SINGLE) |
| 测试组 | 1. LSQ基准 2. EKF 3. EKF+抗差 4. EKF+抗差+零速+多普勒SNR |
| 验证 | 各组历元成功率、3D偏差、高程稳定性 |
| 场景 | urban(高架桥)，手机GNSS，**动态**（~500m移动） |
| 实测结论 | EKF高程波动<0.5m vs LSQ ~3m；四系统1256/1256全成功 |

> **数据仓库扩展**：MobileGNSS-SPP还提供3个额外动态场景数据（存放于独立数据仓库）：
> - `downtown_rover_20250408.25o` — 城市峡谷（~140m动态移动，含NLOS/多路径）
> - `street_rover_20250312.25o` — 街道场景（~250m动态移动，部分遮挡）
> - `opensky_rover_20250408.25o` — 开阔地（~6km车载长距离动态）
>
> 这些数据可用于扩展SppOptimizationTest，实现4场景SPP精度对比测试。

#### SppProcessorTest — SPP封装API

| 项目 | 说明 |
|------|------|
| 数据 | `rtcm/rtcm3_gmsd_20121014.rtcm3` |
| 验证 | 回调解数量匹配、finish回调正确 |

#### SppSmoothAndSbasTest — SPP平滑与SBAS

| 项目 | 说明 |
|------|------|
| 数据 | 无（纯数值构造） |
| 验证 | 滑动窗口均值、溢出处理、SBAS消息解析 |

#### SppCompareTest — Java vs C对比

| 项目 | 说明 |
|------|------|
| 数据 | **私有**: RTKLIB_EX_2.5.0目录下的RINEX+C版参考结果 |
| 验证 | Java SPP结果与C版spp_bds.pos逐历元对比 |
| ⚠️ | **必须使用自己的数据**，需本地RTKLIB C版参考结果 |

---

### 3.2 RTK 相对定位测试

#### RtkTest — RTK基础定位

| 项目 | 说明 |
|------|------|
| 数据 | rover: `rinex210_gps_0759.05o`, base: `rinex210_gps_3040.05o` |
| 模式 | RTK Kinematic (PMODE_KINEMA) |
| 配置 | GPS双频, IONOOPT_IFLC, ARMODE_FIXHOLD |
| 验证 | 历元数>0，FIX率统计 |

#### RtkOptimizationTest — RTK优化逐项测试

| 项目 | 说明 |
|------|------|
| 数据 | rover: `rinex210_gps_0759.05o`, base: `rinex210_gps_3040.05o` |
| 模式 | RTK Kinematic |
| 测试组 | 0_基准AR, 1_自适应Q, 2_IGGIII, 3_SNR中值, 4_PAR重选, 5_锚固, 6_残差编辑, 7_逐级AR, 8_部分AR, 9_Bootstrapping, 10_BDS码偏差, 11_参数噪声, 12_大气冻结, 13_全开 |
| 验证 | 各组FIX率、平均坐标、STD统计 |
| 实测结论 | Static下自适应Q: FIX率81.0%→81.0%(等价性) |

#### RtkOptimizationIndividualTest — RTK优化逐项独立测试

| 项目 | 说明 |
|------|------|
| 数据 | 同RtkOptimizationTest |
| 测试组 | 0_基线, 1_自适应Q, 2_IGGIII, 3_SNR中值, ... 逐项独立@Test |
| 特点 | 每项优化独立测试方法，便于单独运行和调试 |

#### RtkArDebugTest — AR调试

| 项目 | 说明 |
|------|------|
| 数据 | 同RtkOptimizationTest |
| 目的 | 调试AR为何没有Fix解，逐步开启AR和优化 |

#### RtkTraceTest / LogTraceTest — 追踪日志

| 项目 | 说明 |
|------|------|
| 数据 | 同RtkOptimizationTest |
| 验证 | Trace输出完整性、各阶段日志 |

#### RtkIonoptTest — 电离层自由组合测试

| 项目 | 说明 |
|------|------|
| 数据 | **私有**: RTCM3基站+流动站多小时数据 |
| ⚠️ | **必须使用自己的数据**，需配置设备ID和日期 |

#### LandslideMonitorTest — 滑坡监测

| 项目 | 说明 |
|------|------|
| 数据 | rover: `rtcm3_gmsd_20121014.rtcm3`, base: `open-sky_base_20090515.rtcm3` |
| 模式 | RTK Static + 自适应Q + 锚固 + 大气冻结 |
| 验证 | 零速压制有效性、FIX连续性 |

#### ForwardBackwardFilterTest — 前向-后向滤波

| 项目 | 说明 |
|------|------|
| 数据 | rover: `rinex210_gps_0759.05o`, base: `rinex210_gps_3040.05o`, nav: `rinex210_gps_0759.05n` |
| 验证 | Smoother数值正确性、CombinedFilter双向融合 |

#### VerifyPositioningTest — SPP/RTK/PPP综合验证

| 项目 | 说明 |
|------|------|
| 数据 | RINEX2.10 GPS双频(obs+nav) |
| 验证 | SPP/RTK/PPP三种模式基本功能 |

---

### 3.3 PPP 精密单点定位测试

#### PppOptimizationsTest — PPP优化配置验证

| 项目 | 说明 |
|------|------|
| 数据 | 无（纯配置验证） |
| 验证 | 各优化项默认关闭、启用后返回有效值 |

#### PppArFixVerificationTest — PPP-AR数值验证

| 项目 | 说明 |
|------|------|
| 数据 | 无（构造受控输入） |
| 验证 | Fix-and-Hold最小历元守卫、Partial AR周空间转换修复正确性 |

#### B1OsbEquivalenceTest — B1 OSB等价映射

| 项目 | 说明 |
|------|------|
| 数据 | 无（构造Nav输入） |
| 验证 | L1I/L1X/L1P等价查找、反向查找、无数据时返回0 |

#### GreatPvtPositioningTest — GREAT-PVT多系统定位测试

| 项目 | 说明 |
|------|------|
| 数据 | **私有**: GREAT-PVT PPPFLT+RTKFLT (RINEX3.04+SP3+CLK+UPD) |
| 配置 | `greatpvt.ppp.dir` + `greatpvt.rtk.dir` |
| 测试用例 | 8个：RINEX3.04解析、SP3/CLK/UPD加载、SPP多系统、PPP浮点、PPP-AR(WL+NL)、RTK多系统、RTK GPS-only、compactObsFreq |
| 验证 | 各模式历元成功率>0，PPP-AR有FLOAT解 |
| 场景 | IGS站GODN(PPP), SEPT+WUDA(RTK), 多系统G+E+C+R |

> **UPD产品格式**：GREAT-PVT提供WHU格式的UPD文件（`upd_wl_2023305_G`/`upd_nl_2023305_G`等），
> 与WHU/PRIDE标准格式不同（sat在首列而非time在首列）。`UpdReader` 已兼容两种格式，
> 并通过文件名自动推断WL/NL类型（`_wl_`→宽巷, `_nl_`→窄巷, `_ewl_`→超宽巷）。
> 多次调用`readUpd`会追加合并而非覆盖。

---

### 3.4 数据解析与转换测试

#### SampleDataRtcmTest — 公共RTCM3帧扫描

| 项目 | 说明 |
|------|------|
| 数据 | `rtcm/open-sky_base_20090515.rtcm3` (NovAtel私有消息) |
| 验证 | 帧同步+CRC校验(>99%)、私有消息类型识别(7872) |

#### RtcmFileParserTest — RTCM3文件解析

| 项目 | 说明 |
|------|------|
| 数据 | `rtcm/open-sky_base_20090515.rtcm3` |
| 验证 | 原始帧扫描、解码器消息统计、观测历元提取 |

#### RtcmSampleDataTest — RTCM3样例数据

| 项目 | 说明 |
|------|------|
| 数据 | `rtcm/open-sky_base_20090515.rtcm3` + TLE |
| 验证 | 帧扫描、解码、RINEX转换 |

#### RtcmCallbackDecoderTest — 回调解码器

| 项目 | 说明 |
|------|------|
| 数据 | `rtcm/open-sky_base_20090515.rtcm3` |
| 验证 | 观测历元解码、时间改正 |

#### RtcmOutputCheckTest — RTCM输出检查

| 项目 | 说明 |
|------|------|
| 数据 | `rtcm/rtcm3_gmsd_20121014.rtcm3` |
| 验证 | 5种输出类型(观测/星历/GLO星历/SSR/站台) |

#### RinexConversionTest — RINEX转换

| 项目 | 说明 |
|------|------|
| 数据 | rover: `rtcm3_gmsd`, base: `open-sky_base` |
| 验证 | RTCM→RINEX转换、文件生成、内容验证 |

#### RinexObsWriterTest — RINEX写入

| 项目 | 说明 |
|------|------|
| 数据 | `rtcm/open-sky_base_20090515.rtcm3` |
| 验证 | 观测类型完整性(C/L/D/S) |

---

### 3.5 轨道模块测试

#### Sgp4PropagatorTest — SGP4标准验证

| 项目 | 说明 |
|------|------|
| 数据 | `src/test/resources/sgp4-ver.tle` (Vallado标准14用例) |
| 验证 | WGS72 TEME坐标系下位置速度精度 |

#### OrbitalElementsTest — 轨道六根数转换

| 项目 | 说明 |
|------|------|
| 数据 | 同上 |
| 验证 | rv2coe→coe2rv往返转换 |

---

### 3.6 RTK优化项单元测试（纯数值，无数据依赖）

| 测试类 | 优化项 | 验证内容 |
|--------|--------|----------|
| RtkOptimizationsBdsBiasTest | R12 BDS码偏差 | GEO/IGSO/MEO偏差值、非BDS返回零 |
| RtkOptimizationsPartialARTest | R10 部分AR | 默认关闭返回-1、模糊度不足返回-1 |
| RtkOptimizationsCascadeARTest | R8 逐级AR | 默认关闭返回-1、单频跳过、级别常量 |
| RtkOptimizationsResEditTest | R9 残差编辑 | 默认关闭不修改状态、短弧段重置 |
| RtkOptimizationsBootstrapTest | R11 Bootstrapping | 高/低置信度成功率、单模糊度、空输入 |
| RtkOptimizationsConfigTest | R2/R3/R6/R7/R13 | 默认关闭、开启配置、R3阈值触发、R13噪声注入P阵 |
| ProductReaderTest | SP3/CLK/IONEX/OSB/ATX | 各格式文件加载、SP3+CLK联合加载 |

---

### 3.7 其他测试

| 测试类 | 验证内容 |
|--------|----------|
| CompactSsrDecoderTest | QZSS CLAS紧凑SSR解码(Mask/Orbit/Clk/Cbias) |
| AmbiguityStateSerializationTest | Rtk对象序列化往返 |
| EpochCacheTest | 内存历元缓存基本操作和范围查询 |

---

## 4. 优化矩阵测试用例

基于 [优化功能矩阵](CORE_OPTIMIZATION_MATRIX.md)，以下为各优化项的开启/关闭对比测试设计。

### 4.1 SPP优化项测试

| 优化项 | 开关 | 测试类 | 开启测试 | 关闭测试 | 数据 | 预期差异 |
|--------|------|--------|----------|----------|------|----------|
| S1 EKF时间传播 | `enableSppEkf` | SppOptimizationTest | EKF模式 | LSQ模式 | rinex304_gejc_elevated | EKF高程波动<0.5m vs LSQ ~3m |
| S2 M-估计抗差 | `enableSppRobust` | SppOptimizationTest | EKF+抗差 | EKF | 同上 | 城市NLOS场景精度提升 |
| S3 零速约束 | `enableSppZeroVel` | SppOptimizationTest | 全开 | EKF+抗差 | 同上 | 静态/低速精度提升 |
| S4 多普勒+SNR加权 | `enableSppDopplerSnr` | SppOptimizationTest | 全开 | 无多普勒 | 同上 | 伪距平滑+自适应权 |

### 4.2 RTK优化项测试

| 优化项 | 开关 | 测试类 | 开启测试 | 关闭测试 | 数据 | 预期差异 |
|--------|------|--------|----------|----------|------|----------|
| R1 自适应Q | `enableAdaptiveQ` | RtkOptimizationIndividualTest | 开启 | 基线 | rinex210 GPS | Static: FIX率不变; Kinematic: 运动跟踪↑ |
| R2 模糊度锚固 | `enableAmbAnchor` | RtkOptimizationIndividualTest | 开启 | 基线 | 同上 | 长弧段FIX连续性↑ |
| R4 IGGIII抗差 | `enableIggiii` | RtkOptimizationIndividualTest | 开启 | 基线 | 同上 | 多路径/NLOS降权 |
| R5 SNR中值 | `enableSnrMedian` | RtkOptimizationIndividualTest | 开启 | 基线 | 同上 | 弱信号自适应降权 |
| R6 PAR重选 | `enableParRefReselect` | RtkOptimizationIndividualTest | 开启 | 基线 | 同上 | Ratio不足时重选参考星 |
| R8 逐级AR | `enableCascadeAR` | RtkOptimizationIndividualTest | 开启 | 基线 | 同上 | 多频EWL→WL→NL逐级固定 |
| R9 残差编辑 | `enableResidualEdit` | RtkOptimizationIndividualTest | 开启 | 基线 | 同上 | 周跳/弧段完整性检测 |
| R10 部分AR | `enablePartialAR` | RtkOptimizationIndividualTest | 开启 | 基线 | 同上 | Ratio失败→子集固定 |
| R11 Bootstrapping | `enableBootstrapping` | RtkOptimizationIndividualTest | 开启 | 基线 | 同上 | Ratio假阳性拒绝 |
| R12 BDS码偏差 | `enableBdsCodeBias` | RtkOptimizationIndividualTest | 开启 | 基线 | 同上 | BDS GEO/IGSO偏差改正 |

### 4.3 PPP优化项测试

| 优化项 | 开关 | 测试类 | 验证方式 | 数据需求 |
|--------|------|--------|----------|----------|
| P1 GPT3+VMF3 | `enableGpt3Vmf3` | PppOptimizationsTest | 配置验证+返回值 | 内置(无数据) |
| P3 IERS2010 | `enableIers2010` | PppOptimizationsTest | 配置验证 | 内置 |
| P4 ISB/IFCB/IFB | `enableIsbIfcbIfb` | PppOptimizationsTest | 配置验证 | 内置 |
| P5 OSB | `enableOsb` | PppOptimizationsTest | 配置验证 | 内置 |
| P6 PPP-AR | `enablePppAR` | PppArFixVerificationTest + GreatPvtPositioningTest | 数值验证+端到端(GREAT-PVT UPD) | 内置+私有(GREAT-PVT) |
| P7 Fix-and-Hold | `enablePppArFixHold` | PppArFixVerificationTest | 最小历元守卫 | 内置 |
| P8 Partial AR | `enablePppPartialAR` | PppArFixVerificationTest | 周空间转换 | 内置 |

---

## 5. 推荐配置组合与对应测试

| 场景 | 推荐配置 | 测试类 | 数据 |
|------|----------|--------|------|
| **SPP开阔地** | EKF + 多普勒SNR | SppOptimizationTest | rinex304_gejc_elevated |
| **SPP城市** | EKF + 抗差 + 多普勒SNR | SppOptimizationTest | rinex304_gejc_elevated |
| **SPP静态监测** | EKF + 抗差 + 零速 + 多普勒SNR | SppOptimizationTest | rinex304_gejc_elevated |
| **RTK短基线静态** | 自适应Q + 锚固 + 大气冻结 | LandslideMonitorTest | rtcm3_gmsd + open-sky_base |
| **RTK短基线动态** | 自适应Q + IGGIII + SNR中值 + 逐级AR + 部分AR + Bootstrapping | RtkOptimizationTest | rinex210 GPS双频 |
| **RTK长基线** | 梯度 + 参数噪声 + 大气冻结 | RtkOptimizationTest | **需长基线数据** |
| **PPP静态** | GPT3+VMF3 + IERS2010 + ISB + PPP-AR + Fix-Hold | PppOptimizationsTest | **需长时段+产品** |
| **PPP-RTK** | PPP-RTK + PPP-RTK AR + Fix-Hold | CompactSsrDecoderTest | **需SSR数据** |

---

## 6. 大文件数据仓库

超过 2MB 的测试数据存放在独立数据仓库，不占用主仓库空间。

| 项目 | 地址 |
|------|------|
| 数据仓库 | `https://github.com/jinyurrr/rtklib-java-data.git` |
| 指针文件 | `test-data/.data-repo` |
| 下载脚本 | `test-data/download-test-data.ps1` |

### 大文件清单

#### 城市RTK数据（Net_Diff）

| 文件 | 大小 | 来源 | 用途 |
|------|------|------|------|
| `rinex/urban_base_20000719.17o` | 24.9MB | Net_Diff | 城市RTK基站(G+E+J+C)，准静态 |
| `rinex/urban_rover_20000719.17o` | 14.5MB | Net_Diff | 城市RTK流动站(G+E+J+C)，准静态 |
| `nav/urban_20000719.17p` | 5.7MB | Net_Diff | 城区多系统NAV |
| `reference/urban_rtk_20000719.pos` | 1.3MB | Net_Diff | 城市RTK参考固定解 |

#### 手机多场景动态数据（MobileGNSS-SPP）

| 文件 | 大小 | 来源 | 场景 | 动态 |
|------|------|------|------|------|
| `rinex/downtown_rover_20250408.25o` | 3.1MB | MobileGNSS-SPP | 城市峡谷 | 动态(~140m) |
| `nav/downtown_rover_20250408.25n` | 4.4MB | MobileGNSS-SPP | 城市峡谷 | - |
| `nmea/downtown_base_20250408.nmea` | 2.9MB | MobileGNSS-SPP | 城市峡谷 | 基站NMEA |
| `nmea/downtown_rtk_20250408.nmea` | 2.7MB | MobileGNSS-SPP | 城市峡谷 | RTK结果 |
| `rinex/street_rover_20250312.25o` | 1.9MB | MobileGNSS-SPP | 街道 | 动态(~250m) |
| `nav/street_rover_20250312.25n` | 4.4MB | MobileGNSS-SPP | 街道 | - |
| `nmea/street_base_20250312.nmea` | 1.8MB | MobileGNSS-SPP | 街道 | 基站NMEA |
| `nmea/street_rtk_20250312.nmea` | 2.3MB | MobileGNSS-SPP | 街道 | RTK结果 |
| `rinex/opensky_rover_20250408.25o` | 2.3MB | MobileGNSS-SPP | 开阔地 | 动态(~6km) |
| `nav/opensky_rover_20250408.25n` | 4.4MB | MobileGNSS-SPP | 开阔地 | - |
| `nmea/opensky_base_20250408.nmea` | 2.5MB | MobileGNSS-SPP | 开阔地 | 基站NMEA |
| `nmea/opensky_rtk_20250408.nmea` | 2.5MB | MobileGNSS-SPP | 开阔地 | RTK结果 |

#### PPP数据（Net_Diff）

| 文件 | 大小 | 来源 | 用途 |
|------|------|------|------|
| `rinex/ppp_hksl_20180606.18o` | 19MB | Net_Diff | 香港站PPP(G+R+E+C+J+S) |
| `rinex/ppp_hkws_20180606.18o` | 18.2MB | Net_Diff | 香港站PPP |
| `rinex/ppp_wtza_20180606.18o` | 4.5MB | Net_Diff | PPP动态站 |
| `rinex/ppp_wtzr_20180606.18o` | 10.7MB | Net_Diff | PPP动态站 |
| `nav/ppp_20180606.18p` | 5.3MB | Net_Diff | PPP多系统NAV |
| `product/gbm20043.sp3` | 1.9MB | GFZ | GFZ精密轨道 |
| `product/gbm20043.clk` | 17.7MB | GFZ | GFZ精密钟差 |
| `product/igsg1570.18i` | 0.8MB | IGS | IGS电离层格网 |
| `reference/ppp_wtza_20180606.pos` | 0.4MB | Net_Diff | PPP参考解 |
| `reference/ppp_wtzr_20180606.pos` | 0.4MB | Net_Diff | PPP参考解 |

---

## 7. 上传测试数据

欢迎上传测试数据以扩展测试覆盖！请遵循以下规范：

### 7.1 上传前检查

- [ ] 单文件 ≤ 2MB（RTCM ≤ 500KB）
- [ ] 命名符合 `{scenario}_{role}_{date}[_{type}].{ext}` 格式
- [ ] 附带 `.meta` 文件且必填字段完整（scenario, role, systems, date, source）
- [ ] `.meta` 中 `license` 字段已标注（MIT / IGS-data-policy / public / BSD-2-Clause）
- [ ] `.meta` 中 `test_ids` 字段已标注引用该数据的测试类
- [ ] `.meta` 中 `scenario` 和 `role` 正确标注
- [ ] RTK数据标注了 `base.id` 和 `base.distance`
- [ ] 参考解标注了 `mode` 和 `software`
- [ ] 无敏感信息（设备序列号、IP地址等）

### 7.2 上传步骤

1. Fork仓库
2. 在 `test-data/` 对应子目录放入数据文件 + `.meta` 文件
3. 提交PR，标题格式：`test-data: 添加{场景}_{角色}_{日期}测试数据`

### 7.3 亟需的数据场景

按优先级排序：

1. **城市峡谷动态RTK** — 动态base+rover对，含NLOS/多路径，验证IGGIII/SNR中值/残差编辑（已有准静态RTK和动态SPP，缺动态RTK）
2. **车载高速动态RTK** — >50km/h真实动态轨迹base+rover，验证自适应Q/逐级AR（已有手机低速动态SPP，缺高速RTK）
3. **长基线RTK (>10km)** — 验证梯度/参数噪声/大气冻结
4. **PPP-AR精度验证** — 24h静态+参考Fixed解，与GREAT-PVT结果对比（已有端到端测试，缺精度验证）
5. **PPP-RTK** — SSR实时流，验证PPP-RTK处理器
6. **多频(≥3频)** — 三频BDS/GAL，验证逐级AR多频扩展（已有双频BDS，缺三频）
7. **峡谷/深城市RTK** — 两侧高楼遮挡，天空角严重受限
8. **森林/林冠** — 树冠遮挡，信噪比低