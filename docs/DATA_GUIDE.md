# 测试用例与数据指南

> **版本**：v1.1  
> **日期**：2026-10-23  
> **前置文档**：[test-data/README.md](../test-data/README.md)（数据规范）、[优化功能矩阵](CORE_OPTIMIZATION_MATRIX.md)（优化项定义）

> **适用范围**：数据与元数据规范（第 0 章）适用于全部模块；第 1 章 §1.1 列出按定位模式分类的主要算法测试，§1.4 补齐诊断/基准/其它模块测试，§1.5 列出 rtklib-test 的端到端验证测试（数据位于 `test-data/gnss-validation/`，未就绪时自动跳过）。

---

## 0. 测试规范

### 0.1 数据分类

| 类型 | 存放位置 | 是否提交 | 说明 |
|------|----------|----------|------|
| 测试夹具 | `src/test/resources/` | ✅ 提交 | 单元测试必需的小文件（验证参考值、标准TLE等） |
| 公共测试数据 | `test-data/` | ✅ 提交 | GNSS观测/星历/参考解等，≤2MB，无隐私 |
| 私有测试数据 | 本地磁盘 | ❌ 不提交 | 含设备ID/不可公开的RTCM流，通过 `test-data.properties` 配置 |
| 大文件数据 | 独立数据仓库 | ❌ 不提交 | >2MB，通过下载脚本获取 |

### 0.2 元数据文件规范（.meta）

每个测试数据文件须附带同名 `.meta` 文件，记录数据属性。

#### 必填字段

| 字段 | 说明 | 示例 |
|------|------|------|
| `scenario` | 数据场景 | `open-sky`, `urban`, `downtown`, `street`, `elevated`, `forest`, `indoor`, `highway`, `long-baseline` |
| `role` | 数据角色 | `rover`, `base`, `reference`, `nav`, `product` |
| `systems` | 卫星系统 | `G`, `G+R`, `G+E+J+C`, `G+R+E+C+J+S`（G=GPS, R=GLONASS, E=Galileo, J=QZSS, C=BDS, S=SBAS） |
| `date` | 观测日期 | `2025-04-08` |
| `source` | 数据来源项目 | `MobileGNSS-SPP`, `Net_Diff`, `rtklib`, `IGS`, `GFZ` |
| `license` | 数据许可 | `MIT`, `IGS-data-policy`, `public`, `BSD-2-Clause` |

#### 选填字段

| 字段 | 说明 | 示例 |
|------|------|------|
| `sample_rate` | 采样间隔 | `1s`, `30s` |
| `duration` | 数据时长 | `30min`, `24h` |
| `receiver` | 接收机型号 | `UNISOC uis7865`, `u-blox`, `Leica GR50` |
| `antenna` | 天线型号 | `unknown`, `LEIAR25.R4` |
| `base.id` | 基站标识（RTK数据） | `3040`, `VRS-001` |
| `base.distance` | 基线长度（RTK数据） | `1.2km`, `15km` |
| `mode` | 定位模式（参考解） | `spp`, `dgps`, `rtk`, `rtk-static`, `rtk-fixed`, `ppp`, `ppp-ar`, `ppp-rtk` |
| `software` | 解算软件（参考解） | `RTKLIB demo5 b34k`, `LAMBDA` |
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
receiver=UNISOC uis7865
license=MIT
description=城市峡谷手机GNSS数据，含NLOS/多路径（数据集标识: uis7865_6h10_go_a32b）
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
1. 私有配置 (test-data.properties 中的显式路径，支持文件路径和目录路径，目录模式详见 §0.7)
2. 公共数据 (test-data/ 目录下的文件)
3. 占位符路径 (无数据时使用，测试自动跳过)
```

> **目录模式**：当 `rover.file` / `base.file` 配置为目录路径时，`TestDataConfig` 自动扫描目录下所有 `.rtcm3` 文件并按相对路径配对（详见 §0.7）。目录模式仅适用于私有配置，公共数据不使用目录模式。

### 0.5 测试运行条件

| 测试类型 | 数据来源 | 运行条件 | 无数据时行为 |
|----------|----------|----------|-------------|
| 单元测试 | `src/test/resources/` 内置 | 始终运行 | N/A |
| 算法验证 | `test-data/` 公共数据 | 始终运行 | 跳过（AssumeTrue） |
| 精度验证 | 公开精密产品+观测数据 | 始终运行 | 跳过（AssumeTrue） |
| 集成测试 | 特定场景数据（部分无公开来源） | 需本地配置 | 跳过 |
| 退化测试 | 本地私有数据（有公共数据回退） | 始终运行（退化模式） | 退化为低级模式（如RTK→SPP） |

> **退化测试**（⚠️）：测试始终可运行，但无私有数据时退化为低级模式。例如 RTK 测试在缺 base+rover 对时退化为 SPP，仍可验证解码和基本定位流程，但无法验证 RTK Fix 率。

### 0.6 获取测试数据

```powershell
# 下载大文件数据（从独立数据仓库）
./test-data/download-test-data.ps1

# 配置私有数据路径
cp rtklib-core/src/test/resources/test-data.properties.template `
   rtklib-core/src/test/resources/test-data.properties
# 编辑填入本地路径
```

### 0.7 私有 RTCM3 数据配置（目录模式）

RTK/PPP 测试依赖本地私有 RTCM3 数据（含设备ID，不可公开），通过 `rover.file` 和 `base.file` 配置，**同时支持文件路径和目录路径**。

#### 数据目录结构

```
设备ID/日期/小时.rtcm3
```

示例：

```
D:\rtcm3\
├── BASE_ID\
│   └── 2026-05-20\
│       ├── 08.rtcm3
│       ├── 09.rtcm3
│       └── 10.rtcm3
└── ROVER_ID\
    └── 2026-05-20\
        ├── 08.rtcm3
        ├── 09.rtcm3
        └── 10.rtcm3
```

#### 配置方式

在 `test-data.properties` 中设置：

```properties
# 单文件（1小时）
rover.file=D:\rtcm3\ROVER_ID\2026-05-20\08.rtcm3
base.file=D:\rtcm3\BASE_ID\2026-05-20\08.rtcm3

# 目录（整天）
rover.file=D:\rtcm3\ROVER_ID\2026-05-20
base.file=D:\rtcm3\BASE_ID\2026-05-20

# 目录（某设备所有数据，慎用）
rover.file=D:\rtcm3\ROVER_ID
base.file=D:\rtcm3\BASE_ID
```

**通过目录深度控制数据范围**，无需过滤参数：

| 配置路径深度 | 数据范围 | 适用场景 |
|-------------|---------|---------|
| `设备/日期/小时.rtcm3` | 单小时 | 快速验证 |
| `设备/日期/` | 该日所有小时 | 整天RTK/PPP |
| `设备/` | 所有日期和小时 | 长期统计（慎用） |

#### 配对规则

目录模式下自动配对：以基站目录为基准扫描 `.rtcm3` 文件，通过**相对路径**匹配流动站目录下同名文件，缺失则跳过。要求 base 和 rover 目录下**文件相对路径一致**（子目录结构和文件名相同）。程序按路径字符串排序（非时间排序），**小时文件名必须补零**（`08.rtcm3` 而非 `8.rtcm3`），否则字符串排序结果与时间顺序不符（`10.rtcm3` 会排在 `8.rtcm3` 前）。

> ⚠️ 程序按路径字符串排序，不按时间排序。若文件名不满足补零约定，排序结果将与时间顺序不一致，请自行编写测试样例。

#### 代码使用

```java
// 获取所有 base-rover 文件对
List<String[]> pairs = TestDataConfig.resolveBaseRoverPairs();
for (String[] pair : pairs) {
    String baseFile = pair[0];
    String roverFile = pair[1];
}

// 单独解析路径（SPP等单站场景）
List<String> files = TestDataConfig.resolveRtcmFiles(TestDataConfig.getRoverFile());
```

> **完整说明**详见 [TEST_DEVELOPMENT_GUIDE.md §3.5](TEST_DEVELOPMENT_GUIDE.md)。

---

## 1. 测试类总览

### 1.1 按定位模式分类

| 测试类 | 定位模式 | 数据依赖 | 数据来源 | 无数据可运行 |
|--------|----------|----------|----------|-------------|
| **SppTest** | SPP | RTCM3 (G+R+C) | 公共: rtcm3_gmsd | ✅ |
| **SppProcessorTest** | SPP | RTCM3 (G+R+C) | 公共: rtcm3_gmsd | ✅ |
| **SppOptimizationTest** | SPP | RINEX3.04 (G+E+J+C) | 公共: rinex304_gejc_elevated + 数据仓库4场景 | ✅ |
| **SppCompareTest** | SPP | RINEX2.10 (BDS) | 公开: RTKLIB_EX_2.5.0 | ✅ |
| **SppSmoothAndSbasTest** | SPP | 无（纯数值） | 内置 | ✅ |
| **RinexSppTest** | SPP | RTCM3→RINEX→SPP | 公共: rtcm3_gmsd + open-sky_base | ✅ |
| **RinexSppProcessorTest** | SPP | RTCM3→RINEX→SPP | 公共: rtcm3_gmsd | ✅ |
| **RtkTest** | RTK | RTCM3 (BDS双频观测,全系统配置) | **私有: base+rover对**(⚠️退化SPP) | ⚠️ |
| **RtkProcessorTest** | RTK | RTCM3 (BDS双频观测,全系统配置) | **私有: base+rover对**(⚠️退化SPP) | ⚠️ |
| **RtkLocalTest** | RTK | RTCM3 (G+R+C) | 公共: rtcm3_gmsd (rover=base同文件) | ✅ |
| **RtkOptimizationTest** | RTK | RTCM3 (BDS双频观测,全系统配置) | **私有: base+rover对**(⚠️退化SPP) | ⚠️ |
| **RtkOptimizationIndividualTest** | RTK | RTCM3 (BDS双频观测,全系统配置) | **私有: base+rover对**(⚠️退化SPP) | ⚠️ |
| **RtkArDebugTest** | RTK | RTCM3 (BDS双频观测,全系统配置) | **私有: base+rover对**(⚠️退化SPP) | ⚠️ |
| **RtkTraceTest** | RTK | RTCM3 (私有设备ID) | **私有: base+rover对**(无公共回退) | ❌ |
| **RtkBenchmarkTest** | RTK | RINEX3.02/3.04 (G+R+E+J+C) | 公共: PPC-Dataset + UrbanNav | ✅ |
| **LogTraceTest** | RTK | RTCM3 (G+R+C) | 公共: rtcm3_gmsd (fallback本地配置) | ✅ |
| **RtkIonoptTest** | RTK | RTCM3 (私有设备ID) | **私有: rtcm.base.dir** | ❌ |
| **LandslideMonitorTest** | RTK | RTCM3 (G+R+C) + RTCM3 | 公共: rtcm3_gmsd + open-sky_base | ✅ |
| **ForwardBackwardFilterTest** | RTK | RINEX2.10 + NAV | 公共: 0759+3040+0759n | ✅ |
| **VerifyPositioningTest** | SPP/RTK/RTK-Static/DGPS/RTK-Fixed | RINEX3.02 BDS (已清除) | 公共: rinex302_bds (数据已清除，测试跳过) | ❌ |
| **GreatPvtPositioningTest** | SPP/RTK/RTK-Static/PPP/PPP-Static/PPP-AR/PPP-AR+FixHold/PPP+GPT3/PPP+IERS/PPP+ISB | RINEX3.04+SP3+CLK+UPD | 公开: GREAT-PVT sample_data (未下载) | ❌ |
| **PppOptimizationsTest** | PPP | 无（纯配置验证） | 内置 | ✅ |
| **PppArFixVerificationTest** | PPP-AR | 无（构造输入） | 内置 | ✅ |
| **B1OsbEquivalenceTest** | PPP-AR | 无（构造输入） | 内置 | ✅ |
| **Gpt3GridReaderTest** | PPP 对流层 | GPT3 5°×5° 网格 | 公共: product/gpt3/ | ✅ |
| **RtkOptimizationsBdsBiasTest** | RTK | 无（纯数值） | 内置 | ✅ |
| **RtkOptimizationsPartialARTest** | RTK | 无（纯数值） | 内置 | ✅ |
| **RtkOptimizationsCascadeARTest** | RTK | 无（纯数值） | 内置 | ✅ |
| **RtkOptimizationsResEditTest** | RTK | 无（纯数值） | 内置 | ✅ |
| **RtkOptimizationsBootstrapTest** | RTK | 无（纯数值） | 内置 | ✅ |
| **RtkOptimizationsConfigTest** | RTK | 无（配置+数值） | 内置 | ✅ |
| **ProductReaderTest** | 产品读取 | SP3+CLK+IONEX+OSB+ATX | 公共: product/ | ✅ |
| **CompactSsrDecoderTest** | PPP-RTK | 无（构造输入） | 内置 | ✅ |
| **SsrDecodeSmokeTest** | PPP-RTK | RTCM SSR 单帧样本 ×10 | 公共: rtcm/ssr/ | ✅ |
| **NtripSourcetableDecodeTest** | NTRIP | 本地 ServerSocket 模拟（明文 / chunked / gzip） | 内置 | ✅ |
| **NtripLiveRecordTest** | NTRIP | 实时 Caster 凭据 + 30 s 录制 | **私有: NTRIP_* 环境变量** | ❌ |
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

### 1.2 需要本地/私有数据的测试

> **核心障碍**：特定场景的公开数据不存在，而非"私有数据"本身。大部分测试已使用公开数据集（GREAT-PVT sample_data、RTKLIB_EX_2.5.0、IGS产品等）。RtkIonoptTest 因使用含设备ID的RTCM3流、Wuh20MsmSppTest 因使用账号自采录制流，依赖本地私有数据；RtkTest/RtkOptimizationTest等虽有本地私有数据验证结果，但缺可公开的同步base+rover RTCM3文件对。

| 测试类 | 原因 | 所需数据 | 无私有数据时行为 | 配置方式 |
|--------|------|----------|------------------|----------|
| **RtkTest** | 需base+rover RTCM3对（含设备ID，不可公开） | base+rover对(BDS双频) | ⚠️退化SPP | `test-data.properties` 中 `rover.file` + `base.file` |
| **RtkProcessorTest** | 同RtkTest | 同上 | ⚠️退化SPP | 同上 |
| **RtkOptimizationTest** | 同RtkTest | 同上 | ⚠️退化SPP | 同上 |
| **RtkOptimizationIndividualTest** | 同RtkTest | 同上 | ⚠️退化SPP | 同上 |
| **RtkArDebugTest** | 同RtkTest | 同上 | ⚠️退化SPP | 同上 |
| **RtkTraceTest** | 需base+rover RTCM3对（含设备ID，不可公开），无公共数据回退 | base+rover对 | ❌跳过 | `test-data.properties` 中 `rover.file` + `base.file` |
| **RtkIonoptTest** | 需完整RTCM3基站+流动站多小时数据（含设备ID，不可公开） | 设备ID+日期+时段 | ❌跳过 | `test-data.properties` 中 `rover.file` + `base.file`（支持目录模式，详见 §0.7） |
| **Wuh20MsmSppTest** | 自采 RTCM MSM7 录制流（CAS 账号获取） | 录制文件 + 当日 BRDC 星历 | ❌跳过 | 环境变量 `WUH20_RTCM` / `WUH20_BRDC`；默认路径 `test-data/gnss-validation/` |
| **NtripLiveRecordTest** | 需实时 Caster 账号（密码不入库） | 30 s 实时流 | ❌跳过 | 环境变量 `NTRIP_HOST` / `NTRIP_PORT` / `NTRIP_MOUNT` / `NTRIP_USER` / `NTRIP_PASS` |

### 1.3 数据覆盖与缺口分析

#### ✅ 已有真实数据覆盖的场景

| 场景 | 数据来源 | 数据类型 | 动态/静态 | 说明 |
|------|----------|----------|-----------|------|
| **城市峡谷SPP** | MobileGNSS-SPP downtown | RINEX3.04 G+E+J+C 单频 | **动态**（~140m移动） | 手机城市峡谷，含NLOS/多路径 |
| **街道SPP** | MobileGNSS-SPP street | RINEX3.04 G+E+J+C 单频 | **动态**（~250m移动） | 手机街道场景，部分遮挡 |
| **开阔地SPP** | MobileGNSS-SPP opensky | RINEX3.04 G+E+J+C 单频 | **动态**（~6km移动） | 手机开阔地，车载长距离 |
| **高架桥SPP** | MobileGNSS-SPP elevated | RINEX3.04 G+E+J+C 单频 | **动态**（~500m移动） | 手机高架桥场景 |
| **城市RTK** | Net_Diff urban | RINEX3.02 G+E+J+C 双频 | **准静态**（~3m微动） | base+rover对，含参考固定解 |
| **城市峡谷动态RTK** | PPC-Dataset | RINEX3.04 G+R+E+J+C 双频 | **动态**（车载） | Tokyo/Nagoya 6 runs, Septentrio+Trimble, 含参考真值 |
| **城市峡谷动态RTK** | UrbanNav | RINEX3.02 G+R+E+J+C 三频 | **动态**（车载） | Tokyo 2018, Trimble NetR9, 10Hz |
| **开阔地RTK** | rtklib test | RINEX2.10 GPS 双频 | 静态 | 短基线0759+3040 |
| **PPP** | Net_Diff | RINEX3.02+SP3+CLK+IONEX | 静态/动态 | wtza/wtzr/hksl站 |
| **多系统PPP** | GREAT-PVT PPPFLT | RINEX3.04 G+E+C+R + CODE SP3/CLK | 静态 | IGS站GODN, 30s采样 |
| **多系统RTK** | GREAT-PVT RTKFLT | RINEX3.03/3.04 G+E+C+R+J base+rover | 动态 | SEPT+WUDA, 1s采样 |
| **PPP-AR (WL+NL)** | GREAT-PVT PPPFLT | RINEX3.04 G+E+C + CODE SP3/CLK + WHU UPD(WL/NL) | 静态/动态 | GODN站, GPS双频, Fix-and-Hold |

#### ⚠️ 有数据但不完整的场景

| 场景 | 当前数据 | 缺口 | 欢迎上传 |
|------|----------|------|----------|
| **城市峡谷RTK** | PPC-Dataset (Tokyo/Nagoya, 2024) + UrbanNav (Tokyo, 2018)，含base+rover对和参考真值 | RtkBenchmarkTest待收紧阈值并启用精度验证 | ✅ |
| **RTCM二进制RTK** | 本地私有数据(base+rover RTCM3对)已验证通过：RtkTest Fix率69.5%(Fix=82/118)，RtkProcessorTest全部通过 | 缺可公开的同步base+rover RTCM3文件对 | ✅ |
| **PPP-AR完整验证** | 有精密产品(SP3+CLK+OSB+ATX+IONEX)+UPD WL/EWL(≤2MB在test-data)+UPD NL(>2MB在独立数据仓库)+SP3/BSX(≤2MB在test-data)+CLK/brdc(>2MB在独立数据仓库) | 缺24h长时段+参考Fixed解对比 | ✅ |
| **多频(≥3频)RTK** | GODN站RINEX3.04含BDS B1I+B3I双频+多频(10min≤2MB在test-data，24h>2MB在独立数据仓库) | 缺BDS三频数据 | ✅ |
| **RTCM流式RTK** | 本地RTCM3 base+rover文件已验证通过（含设备ID不可公开） | 缺可公开的同步base+rover RTCM3文件对 | ✅ |

#### ❌ 根本没有真实数据测试的场景

| 场景 | 需要的数据 | 欢迎上传 |
|------|-----------|----------|
| **深城市/峡谷RTK** | 更极端场景（天空角<30°，多路径严重）的base+rover对 | ✅ |
| **高速动态RTK** | PPC-Dataset/UrbanNav为车载（~30km/h），缺高速(>80km/h)动态轨迹base+rover对 | ✅ |
| **超长基线RTK (>100km)** | >100km的base+rover对，需梯度参数与精细大气建模；10~100km中长基线已做链路级验证（见 §2 阶段2长基线实测记录） | ✅ |
| **PPP-RTK实时** | CAS IGS SSR 流已做「录制 → 回放」链路级验证；缺 Galileo HAS / QZSS CLAS 等其它 SSR 源的可公开样本 | ✅ |
| **森林/林冠** | 树冠遮挡，信噪比低 | ✅ |
| **室内定位** | 严重遮挡 | ✅ |

> **RTCM流式定位说明**：当前SPP已有RTCM直接定位测试（`SppTest`、`SppProcessorTest`）。RTK的RTCM流式端到端测试已有本地私有数据验证（`RtkTest` Fix率69.5%、`RtkProcessorTest`全部通过、`RtkOptimizationTest` Fix率62.7%~69.5%），但该数据含设备ID不可公开。公共数据下这些测试退化为SPP回退，无法产生RTK Fix率。现有公共RTCM文件中，`rtcm3_gmsd`为rover观测流，`open-sky_base`为VRS差分改正流而非标准base观测值流，无法构成RTK双流输入。如有可公开的同步base+rover RTCM3文件对，欢迎上传补充。

### 1.4 诊断 / 基准 / 其它模块测试

> §1.1 只收录按定位模式分类的主体算法测试，下表补齐诊断类、基准类以及 rtklib-adjust / rtklib-product / rtklib-research 的测试。

| 测试类 | 模块 | 关注点 | 数据依赖 | 无数据可运行 |
|--------|------|--------|----------|-------------|
| **CoreAlgorithmTest** | rtklib-core | 核心算法单元测试 | 内置 | ✅ |
| **IflcPairNegotiationTest** | rtklib-core | IFLC 逐卫星频率对协商（sticky 策略） | 内置 | ✅ |
| **TestDataConfigTest** | rtklib-core | 目录模式 base/rover 配对解析 | 内置 | ✅ |
| **RtcmDataCheckTest** | rtklib-core | RTCM 电文类型统计（硬编码 `D:\rtcmdata`，目录不存在则跳过） | **私有: D:\rtcmdata** | ❌ |
| **RtcmTimeDiagTest** | rtklib-core | RTCM 观测时刻与星历 toe 诊断 | 公共: rtcm3_gmsd | ✅ |
| **CssrDiagTest** | rtklib-core | 紧凑 SSR `nv=0` 诊断 | 公共: rinex304_gej_3034/sept | ✅ |
| **RinexRtkProcessorTest** | rtklib-core | RINEX 短基线 RTK 处理 | 公共: rinex210_gps_0759/3040、rinex304_gej | ✅ |
| **UrbanCanyonSppOptTest** | rtklib-core | 城市峡谷 SPP 优化对比（UC-1~） | 公共: MobileGNSS-SPP / PPC / UrbanNav | ✅ |
| **UrbanCanyonRtkDiagTest** | rtklib-core | 城市峡谷 RTK 深度诊断 | 公共: Net_Diff urban（urban_rover/base_20000719） | ✅ |
| **UrbanCanyonDataCheckTest** | rtklib-core | 城市峡谷 RINEX 数据检查 + SPP 分析 | 公共: Net_Diff urban + PPC | ✅ |
| **CascadeArBehaviorTest** | rtklib-core | CascadeAR EST 模式 A/B 行为对比 | 公共: PPC-Dataset（`CASCADE_AB_*` 环境变量可裁剪数据集） | ✅ |
| **DynamicRtkOptTest** | rtklib-core | 城市峡谷 RTK 12 变体对比（见 ROADMAP §7.0.6） | 公共: PPC + UrbanNav + Net_Diff | ✅ |
| **E2ETimingTest** | rtklib-core | 端到端耗时基线（梯队0） | 公共: rinex304_gej | ✅ |
| **JmhRunnerTest** | rtklib-core | JMH 微基准（全量运行 20min+，回归时可用 `-Dtest="!JmhRunnerTest"` 排除） | 内置 | ✅ |
| **GnssBaselineAdjustTest** | rtklib-adjust | 单/多基线平差与 Baarda 检验（合成观测） | 内置 | ✅ |
| **AdjustRealDataTest** | rtklib-adjust | 2 基站 1 测站多基线平差（连续 9h RTCM） | **私有: adjust 配置** | ❌ |
| **BaseStationDiagnoserTest** | rtklib-adjust | 基站坐标异常诊断（2 基站 1 测站） | **私有: adjust 配置** | ❌ |
| **BaseStationMonitorTest** | rtklib-adjust | 60min 窗口 SPP 中位数与突变监测 | **私有: adjust 配置** | ❌ |
| **ProductDownloaderTest** | rtklib-product | 精密产品下载 | 网络: IGS / WHU 镜像 | ❌ |
| **PppProductIntegrationTest** | rtklib-product | ProductDownloader + PppProcessor 集成 | 网络 + 公共产品 | ❌ |
| **PipelineIntegrationTest** | rtklib-research | FGO 管线集成 | 内置/合成 | ✅ |
| **PreintegrationTest** | rtklib-research | IMU 预积分 | 内置/合成 | ✅ |

> rtklib-adjust 使用**独立的** `rtklib-adjust/src/test/resources/test-data.properties`（`data.root` / `base.group` / `rover.group` / `date`），与 core 的 `test-data.properties` 互不干扰；未配置时上述三个私有数据测试自动跳过。

### 1.5 rtklib-test 端到端验证测试

> 位于 `rtklib-test/src/test/java/org/rtklib/java/test/gnssvalidation/`，数据统一放在 `test-data/gnss-validation/`，由 `GnssValidationDataHelper` 按需下载（BKG HTTPS 优先、WHU FTP 回退）；数据缺失时测试自动跳过。核心模块回归不依赖这些用例。

| 测试类 | 验证阶段 | 内容 | 环境变量 |
|--------|----------|------|----------|
| **SppValidationTest** | 阶段1 | RINEX 解析 + SPP 解算链路、时序合理性、成功率、对比 ALGO 站 ITRF 坐标的 ENU RMS | — |
| **RtkValidationTest** | 阶段2 | 短基线（HKWS-HKSS，~6.8km）与长基线（HKWS-HKSL，~42km）RTK 链路、固定率与 ENU RMS 对照 | — |
| **PppValidationTest** | 阶段3 | SP3/CLK 加载、PPP 链路、SINEX 参考比对、收敛后精度，以及 EST/多系统/ISB/IERS2010 等优化项逐项对照 | `PPP_DATA_SUBDIR` / `PPP_PRODUCT_SUBDIR` / `PPP_TABLE_SUBDIR` / `PPP_TEST_YEAR` / `PPP_TEST_DOY` |
| **PppArValidationTest** | 阶段3-AR | PPP-AR 全链路（IFLC / 三频 / EST+ISB 等配置组合）固定率与精度 | 同上 + `PPP_REF_ECEF` |
| **PppRtkValidationTest** | 阶段4 | PPP-RTK 事后 SSR 链路：产品加载、解算、固定率、与 PPP 浮点解对照 | 同上 |

> 上述环境变量用于切换数据集（默认 `2024/WUM` 基线），须通过**环境变量**设置而非 `mvn -D`，原因见 [TEST_DEVELOPMENT_GUIDE.md](TEST_DEVELOPMENT_GUIDE.md)。

#### 阶段2 长基线 RTK 实测记录（2026-10-28）

数据：`HKWS`(rover) + `HKSL`(base)，2024 DOY060，30 s 采样，全天 2880 历元；
配置：`PMODE_STATIC`、`IONOOPT_IFLC`、`TROPOPT_EST`、`ARMODE_FIXHOLD`，优化项 `enableSnrMedian` + `enableIggiii` + `enableParamTypeNoise` + `atmFrozenNsThresh=7`。

| 指标 | 结果 |
|------|------|
| 处理历元 | total=2880，success=2880，fail=0（**链路全通**） |
| 固定率 | **1.3%**（FIX 38 / FLOAT 2842），FIX 集中在历元 [1084..1201] |
| 基线长度 | 42511.249 m（FIX 解均值 42510.894 m），期望 ~42 km |
| 全历元 ENU RMS | E 1.017 m / N 0.522 m / U 1.262 m，2D 1.143 m |
| FIX-only ENU RMS | E 0.105 m / N 0.018 m / U 0.044 m，2D 0.106 m |

> **口径说明**：参考坐标取 RINEX 头 `APPROX POSITION`，非 ITRF 真值，故基线偏差 +511 m（+1.22%）不代表解算误差，绝对值也不能作为精度结论。
> **结论定位**：本用例只证明**长基线链路可跑通、FIX 解内部一致性达分米级**，属于表面（链路级）验证；**固定率仅 1.3%，长基线 AR 能力与 R7 梯度 / R13 参数噪声等长基线优化项的实际增益尚未验证**，不作为长基线精度达标依据。

---

## 2. 公共测试数据文件清单

### 2.1 RINEX 观测文件

| 文件 | 格式 | 系统 | 场景 | 角色 | 用途 |
|------|------|------|------|------|------|
| `rinex210_gps_0759.05o` | RINEX 2.10 | G | open-sky | rover | RTK流动站(GPS双频L1/L2) |
| `rinex210_gps_3040.05o` | RINEX 2.10 | G | open-sky | base | RTK基站(GPS双频L1/L2) |
| `rinex304_gej_3034.21o` | RINEX 3.04 | G+E+J | open-sky | rover | 多系统1min采样（3034为测站编号） |
| `rinex304_gej_sept.21o` | RINEX 3.04 | G+E+J | open-sky | rover | Septentrio接收机1min（sept为接收机型号缩写） |
| `rinex304_gejc_elevated.25o` | RINEX 3.04 | G+E+J+C | urban | rover | 手机高架桥场景(SPP优化测试) |
| `rinex304_gejc_street.25o` | RINEX 3.04 | G+E+J+C | street | rover | 手机街道场景(SPP优化测试) |
| `rinex304_godn_10min.23o` | RINEX 3.04 | G+R+E+C | open-sky | rover | IGS GODN站10分钟截取，含BDS多频 |

> **大文件(>2MB)在独立数据仓库**：`opensky_rover_20250408.25o`、`downtown_rover_20250408.25o`、`godn3050.23o`(24h)、`harb3050.23o`，通过 `download-test-data.ps1` 获取。

### 2.2 RINEX 导航文件

| 文件 | 格式 | 系统 | 用途 |
|------|------|------|------|
| `rinex210_gps_0759.05n` | RINEX 2.10 | G | GPS导航星历(配合0759观测) |
| `rinex210_gps_3040.05n` | RINEX 2.10 | G | GPS导航星历(配合3040观测) |
| `rinex304_mixed_sept.21p` | RINEX 3.04 | G+E+J | 多系统混合导航 |
| `rinex304_gejc_elevated.25n` | RINEX 3.04 | G+E+J+C | 手机场景导航星历 |
| `rinex302_qzss.21q` | RINEX 3.02 | J | QZSS导航星历 |

### 2.3 RTCM3 文件

| 文件 | 系统 | 场景 | 角色 | 用途 |
|------|------|------|------|------|
| `open-sky_base_20090515.rtcm3` | G | open-sky | base(VRS) | VRS差分改正数流(非标准base观测值，含NovAtel私有消息) |
| `rtcm3_gmsd_20121014.rtcm3` | G+R+C | open-sky | rover/自差分 | 标准RTCM3消息(G+R+C三系统)，可作rover或rover=base自差分 |

### 2.4 精密产品

| 文件 | 类型 | 用途 |
|------|------|------|
| `igs15904.sp3` | SP3精密轨道 | IGS最终轨道 |
| `igs15904.clk` | CLK精密钟差 | IGS最终钟差 |
| `igsg1570.18i` | IONEX电离层 | IGS电离层格网 |
| `test.atx` | ANTEX天线改正 | 天线PCV/PCO改正 |
| `cod_osb_2021265.bia` | Bias-SINEX | CODE码偏差(OSB) |
| `cod0mgxfin_2023305.sp3` | SP3精密轨道 | CODE MGEX最终轨道(2023 DOY305) |
| `cas0mgxrap_2023305.bsx` | Bias-SINEX | CAS MGEX DCB/OSB(2023 DOY305) |
| `upd/upd_wl_2023305_G`, `upd_wl_2023305_E`, `upd_wl_2023305_C` | UPD宽巷 | WHU宽巷UPD(GPS/GAL/BDS) |
| `upd/upd_ewl_2023305_G`, `upd_ewl_2023305_E`, `upd_ewl_2023305_C` | UPD超宽巷 | WHU超宽巷UPD(GPS/GAL/BDS) |
| `upd/upd_ewl24_2023305_C`, `upd_ewl25_2023305_E` | UPD超宽巷24/25 | WHU超宽巷UPD(BDS B1c/B2a频点, GAL E5a/E5b频点) |

> **大文件(>2MB)在独立数据仓库**，完整清单见 [§6 精密产品大文件](#6-大文件数据仓库)，通过 `download-test-data.ps1` 获取。

### 2.5 PPC-Dataset 城市峡谷RTK数据

| 文件 | 格式 | 系统 | 场景 | 角色 | 用途 |
|------|------|------|------|------|------|
| `ppc/PPC-Dataset/tokyo/run1/rover.obs` | RINEX 3.04 | G+R+E+J+C | downtown | rover | Septentrio Mosaic-X5, 5Hz |
| `ppc/PPC-Dataset/tokyo/run1/base.obs` | RINEX 3.04 | G+R+E+J+C | downtown | base | Trimble Alloy, 1Hz |
| `ppc/PPC-Dataset/tokyo/run1/base.nav` | RINEX 3.04 | G+R+E+J+C | downtown | nav | 广播星历 |
| `ppc/PPC-Dataset/tokyo/run1/reference.csv` | CSV | - | downtown | reference | Applanix真值 (5Hz, RMSE 5cm) |
| `ppc/PPC-Dataset/tokyo/run2/*` | 同上 | 同上 | downtown | 同上 | Tokyo run2 |
| `ppc/PPC-Dataset/tokyo/run3/*` | 同上 | 同上 | downtown | 同上 | Tokyo run3 |
| `ppc/PPC-Dataset/nagoya/run1/*` | 同上 | 同上 | urban | 同上 | Nagoya run1 |
| `ppc/PPC-Dataset/nagoya/run2/*` | 同上 | 同上 | urban | 同上 | Nagoya run2 |
| `ppc/PPC-Dataset/nagoya/run3/*` | 同上 | 同上 | urban | 同上 | Nagoya run3 |

> **来源**: [taroz/PPC-Dataset](https://github.com/taroz/PPC-Dataset) (千叶工业大学), License: CC-BY-4.0
> **下载**: OneDrive (155MB zip), 详见 `test-data/ppc/PPC-Dataset/README.md`
> **.meta规范**：外部数据集（PPC-Dataset、UrbanNav）沿用原始命名（如`rover.obs`、`tokyo3530.18o`），不强制要求`.meta`文件。如需新增为独立测试数据文件（如截取子集），则须按§0.2规范附带`.meta`。

### 2.6 UrbanNav 城市峡谷RTK数据

| 文件 | 格式 | 系统 | 场景 | 角色 | 用途 |
|------|------|------|------|------|------|
| `urbannav/tokyo/data_tokyo/tokyo3530.18o` | RINEX 3.02 | G+R+E+J+C | downtown | rover | Trimble NetR9, 10Hz |
| `urbannav/tokyo/data_tokyo/tokyo3530_base.18o` | RINEX 3.02 | G+R+E+J+C | downtown | base | Trimble NetR9, 1Hz |
| `urbannav/tokyo/data_tokyo/brdm3530.18p` | RINEX 3.03 | G+R+E+J+C | downtown | nav | MGEX广播星历 |
| `urbannav/tokyo/data_tokyo/tokyo_pva_ref.mat` | MATLAB | - | downtown | reference | 参考真值 |

> **来源**: [IPNL-POLYU/UrbanNavDataset](https://github.com/IPNL-POLYU/UrbanNavDataset) (香港理工大学), License: CC-BY-4.0
> **下载**: Dropbox (4.14GB zip) 或 GINav Gitee镜像 (12.3MB 7z), 详见 `test-data/urbannav/`

### 2.7 配置文件

| 文件 | 用途 |
|------|------|
| `open-sky_rtk_kinematic.conf` | 开阔地RTK动态定位参考配置 |

---

### 2.8 城市峡谷RTK数据详细说明

> 本节汇总所有城市峡谷RTK测试数据的实测诊断结果，避免复现时疑惑。

#### 2.8.1 数据集总览

| # | 数据集 | 来源 | 地点 | 日期 | 运动模式 | 基线 | Rover接收机 | Base接收机 |
|---|--------|------|------|------|----------|------|------------|-----------|
| 0 | Net_Diff Urban | Net_Diff | Tokyo | 2017-07-19 | 准静态(~3m微动) | **199m** | u-blox (单频) | Trimble NetR9 (多频) |
| 1 | PPC Tokyo run1 | PPC-Dataset | Tokyo | 2024-06-xx | 动态(车载) | **7.8km** | SEPT Mosaic-X5 (5Hz) | Trimble Alloy (1Hz) |
| 2 | PPC Tokyo run2 | PPC-Dataset | Tokyo | 2024-06-xx | 动态(车载) | **7.8km** | SEPT Mosaic-X5 (5Hz) | Trimble Alloy (1Hz) |
| 3 | PPC Tokyo run3 | PPC-Dataset | Tokyo | 2024-06-xx | 动态(车载) | **7.8km** | SEPT Mosaic-X5 (5Hz) | Trimble Alloy (1Hz) |
| 4 | PPC Nagoya run1 | PPC-Dataset | Nagoya | 2024-06-xx | 动态(车载) | **15km** | SEPT Mosaic-X5 (5Hz) | Trimble NetR9 (1Hz) |
| 5 | PPC Nagoya run2 | PPC-Dataset | Nagoya | 2024-06-xx | 动态(车载) | **15km** | SEPT Mosaic-X5 (5Hz) | Trimble NetR9 (1Hz) |
| 6 | PPC Nagoya run3 | PPC-Dataset | Nagoya | 2024-06-xx | 动态(车载) | **15km** | SEPT Mosaic-X5 (5Hz) | Trimble NetR9 (1Hz) |
| 7 | UrbanNav Tokyo | UrbanNav | Tokyo | 2018-12-19 | 动态(车载) | **186m** | Trimble NetR9 (10Hz) | Trimble NetR9 (1Hz) |

#### 2.8.2 RINEX头信息与观测类型兼容性

##### Net_Diff Urban

| 项目 | Rover | Base |
|------|-------|------|
| RINEX版本 | 3.02 | 3.02 |
| 接收机 | u-blox (RTKCONV 2.4.2转换) | Trimble NetR9 5.20 |
| 近似位置 | 35.668°N, 139.791°E, 35m | 35.666°N, 139.792°E, 62m |
| 采样率 | ~0.2s (非均匀) | 1s |
| 观测类型 | G(4): C1C L1C D1C S1' | G(16): C1C C2W C2A C5X L1C L2W L2A L5X ... |
|  | E(4): C1C L1C D1C S1C | R(12): C1C C1P C2C L1C L1P L2C ... |
|  | J(4): C1C L1C D1C S1C | J(24): C1C C1X C1Z C2X C5X C6X L1C L1X L1Z L2X L5X L6X ... |
|  | C(4): C1I L1I D1I S1I | C(12): C1I C6I C7I L1I L6I L7I ... |
|  | **无R系统** | E(16): C1X C5X C7X C8X L1X L5X L7X L8X ... |

**观测类型兼容性（关键问题）**：

| 系统 | Rover伪距 | Base伪距 | 兼容结果 |
|------|----------|---------|---------|
| G | C1C | C1C, C2W | ⚠ 仅单频兼容 (C1C+L1C) |
| E | C1C | C1X, C5X, C7X, C8X | ❌ **无兼容！** C1C≠C1X (RINEX3属性码不同) |
| J | C1C | C1C, C1X, C1Z, C2X | ⚠ 仅单频兼容 (C1C+L1C) |
| C | C1I | C1I, C6I, C7I | ⚠ 仅单频兼容 (C1I+L1I) |
| R | 无 | C1C, C1P, C2C | ❌ Rover无GLONASS |

> ⚠️ **Net_Diff Urban 的 Galileo 完全不兼容**：Rover用C1C（C=跟踪通道），Base用C1X（X=无码跟踪），RINEX 3.02中属性码不同导致无法匹配。这是该数据集RTK性能极差的主要原因之一。

##### PPC-Dataset (Tokyo run1-3, Nagoya run1-3)

| 项目 | Rover | Base |
|------|-------|------|
| RINEX版本 | 3.04 | 3.04 |
| 接收机 | Septentrio Mosaic-X5 | Trimble Alloy / NetR9 |
| 采样率 | 5Hz (0.2s) | 1Hz |
| 观测类型 | G+R+E+J+C 双频 | G+R+E+J+C 双频 |
| 兼容性 | ✅ 所有系统双频兼容 | |

> PPC-Dataset 的 Rover 和 Base 观测类型完全兼容，双频可用。但基线较长（Tokyo ~7.8km, Nagoya ~15km），超出典型短基线RTK的10km阈值。

##### UrbanNav Tokyo

| 项目 | Rover | Base |
|------|-------|------|
| RINEX版本 | 3.02 | 3.02 |
| 接收机 | Trimble NetR9 | Trimble NetR9 |
| 采样率 | 10Hz (0.1s) | 1Hz |
| 观测类型 | G+R+E+J+C 双频 | G+R+E+J+C 双频 |
| 兼容性 | ✅ 所有系统双频兼容 | |

> UrbanNav 基线仅186m，最适合短基线RTK。但城市峡谷遮挡严重（SPP成功率仅67.6%）。

#### 2.8.3 SPP单点定位诊断

| # | 数据集 | 总历元 | SPP成功率 | 平均卫星数 | 平均坐标 | 判定 |
|---|--------|--------|----------|-----------|---------|------|
| 0 | Net_Diff Urban | 9907 | **40.0%** | **4.6** | 35.662°N, 139.789°E | ⚠ SPP成功率极低，信号遮挡严重 |
| 1 | PPC Tokyo run1 | 11928 | 99.9% | 16.8 | 35.645°N, 139.784°E | ✅ 数据OK |
| 2 | PPC Tokyo run2 | 9000 | 99.9% | 16.8 | 35.645°N, 139.784°E | ✅ 数据OK |
| 3 | PPC Tokyo run3 | 15301 | 99.9% | 16.8 | 35.645°N, 139.784°E | ✅ 数据OK |
| 4 | PPC Nagoya run1 | 7602 | 99.0% | 12.7 | 35.169°N, 136.882°E | ✅ 数据OK |
| 5 | PPC Nagoya run2 | 9451 | 99.7% | 12.7 | 35.168°N, 136.890°E | ✅ 数据OK |
| 6 | PPC Nagoya run3 | 5201 | 100% | 12.8 | 35.165°N, 136.903°E | ✅ 数据OK |
| 7 | UrbanNav Tokyo | 12395 | **67.6%** | **5.7** | 35.626°N, 139.781°E | ⚠ SPP成功率低，遮挡较严重 |

> **解读**：SPP成功率反映Rover端可见卫星的充足程度。Net_Diff Urban仅40%（u-blox低成本接收机+城市峡谷），UrbanNav Tokyo 67.6%（Trimble高端接收机但遮挡严重），PPC数据集均>99%（Septentrio高端接收机，遮挡相对较轻）。

#### 2.8.4 RTK定位诊断

**thresar[1]=0.1（旧结果，AR前置检查几乎不通过）**：

| # | 数据集 | 总历元 | Fix | Float | None | Float 2Dσ | Float坐标 |
|---|--------|--------|------|-------|------|----------|----------|
| 0 | Net_Diff Urban | 9907 | 0 (0%) | 172 (1.7%) | 1774 (17.9%) | 2.035m | 35.661°N, 139.787°E ✅ |
| 1 | PPC Tokyo run1 | 11928 | 0 (0%) | 618 (5.2%) | 281 (2.4%) | 1.776m | 35.680°N, 139.770°E ✅ |
| 2 | PPC Tokyo run2 | 9151 | 0 (0%) | ~1.5% | - | - | ✅ |
| 3 | PPC Tokyo run3 | 15301 | ~0.1% | - | - | - | ⚠ Fix为错误Fix(false fix) |
| 4 | PPC Nagoya run1 | 7602 | 9 (0.1%) | 1324 (17.4%) | 179 (2.4%) | 1.326m | 35.169°N, 136.882°E ✅ |
| 5 | PPC Nagoya run2 | 9451 | - | - | - | - | - |
| 6 | PPC Nagoya run3 | 5201 | - | - | - | - | - |
| 7 | UrbanNav Tokyo | 12395 | 0 (0%) | 827 (6.7%) | 161 (1.3%) | 1.473m | 35.626°N, 139.781°E ✅ |

**thresar[1]=0.9999（对齐 RTKLIB-EX 默认值，C-EX-Ref 全量结果）**：

| # | 数据集 | 历元 | Fix% | Float% | H.RMSE(m) |
|---|--------|------|------|--------|-----------|
| 0 | Net_Diff Urban (准静态) | 9907 | 1.4% | 24.9% | 9.795 |
| 1 | PPC Tokyo run1 | 11928 | 6.8% | 69.8% | 11.846 |
| 2 | PPC Tokyo run2 | 9151 | 12.3% | 69.1% | 1.531 |
| 3 | PPC Tokyo run3 | 15301 | 3.9% | 73.8% | 9.701 |
| 4 | PPC Nagoya run1 | 7602 | 19.8% | 59.3% | 5.733 |
| 5 | PPC Nagoya run2 | 9451 | 6.3% | 75.8% | 13.202 |
| 6 | PPC Nagoya run3 | 5201 | 7.5% | 79.8% | 12.800 |
| 7 | UrbanNav Tokyo 2018 | 12395 | 5.5% | 43.7% | — |

> **关键发现**：
> - **thresar[1] 是 Fix 率的决定性参数**：`thresar[1]` 从 0.1 改为 0.9999（对齐 RTKLIB-EX 默认值）后，Fix 率从 0% 跃升。`manage_amb()` 中 AR 前置检查 `posvar < thresar[1]`，旧值 0.1 几乎永不满足。
> - **所有Float解坐标都在日本范围内**，2D sigma约1.3~2.0m，差分定位本身在工作
> - **旧结果中大量历元连Float都没出来**（90%+历元为None/SPP失败），原因是双差观测不足（`ddres nv=0 < 4`）
> - **Nagoya run1的9个Fix和Tokyo run3的少量Fix是错误Fix(false fix)**：水平RMSE达7亿米但垂直正常，说明AR固定到了错误整周模糊度

#### 2.8.5 各数据集RTK性能差的原因分析

| 数据集 | 主要限制因素 | 次要因素 |
|--------|------------|---------|
| **Net_Diff Urban** | ①Rover仅单频(u-blox)，无法消除电离层延迟 ②Galileo完全不兼容(C1C≠C1X) ③SPP成功率仅40%，平均4.6颗卫星 | 基线199m理论上最理想 |
| **PPC Tokyo** | ①基线7.8km，超出短基线RTK阈值 ②城市峡谷多路径，AR ratio低 | 数据质量本身OK，双频兼容 |
| **PPC Nagoya** | ①基线15km，远超短基线RTK阈值，电离层残差大 ②城市峡谷多路径 | Nagoya run1 Float率17.4%相对较高 |
| **UrbanNav Tokyo** | ①城市峡谷遮挡最严重(SPP 67.6%，5.7颗卫星) ②10Hz高采样率但1Hz base需内插 | 基线186m最理想，双频兼容 |

#### 2.8.6 已知Bug与注意事项

##### Tokyo run2 的 ArrayIndexOutOfBoundsException (已修复)

- **现象**：PPC Tokyo run2 处理时 `RtkCore.zdres()` 第1083行抛出 `ArrayIndexOutOfBoundsException: Index 576 out of bounds for length 576`
- **根因**：`RtkWorkBuffer` 中 `rs[]` 等数组按 `MAXOBS=96` 固定分配，但某些历元 rover+base 总观测数 `nu+nr=97 > 96`，导致 `rs[idx*6]` 越界（`idx=96, 96*6=576`）
- **触发条件**：多系统(5系统) + rover高频(5Hz) + base高频(1Hz) → 某些历元卫星总数超过96
- **修复**：在 `RtkCore.relpos()` 入口添加 `n > MAXOBS` 截断保护，丢弃base端最后1颗卫星观测
- **影响**：截断1颗base卫星对定位精度影响极小，但避免了崩溃

##### Net_Diff Urban 数据需下载

- Net_Diff 的RINEX/NAV文件(>2MB)存放在独立数据仓库 `rtklib-java-data`，需运行下载脚本：
  ```powershell
  powershell -ExecutionPolicy Bypass -File test-data/download-test-data.ps1
  ```
- 涉及文件：`rinex/urban_rover_20000719.17o`(15.2MB), `rinex/urban_base_20000719.17o`(26.1MB), `nav/urban_20000719.17p`(6.0MB)

##### PPC-Dataset 数据需下载

- PPC-Dataset 的run目录通过 `.gitignore` 排除，需按 `test-data/ppc/PPC-Dataset/README.md` 下载

#### 2.8.7 测试类参考

| 测试类 | 用途 |
|--------|------|
| `UrbanCanyonDataCheckTest` | 数据深度诊断：RINEX头检查、SPP分析、RTK Float解详细统计 |
| `UrbanCanyonRtkDiagTest` | RTK完整诊断：Fix/Float/Single/None统计、坐标RMSE、ratio分布 |
| `urbancanyon.UrbanCanyonSppOptTest` | SPP优化对比：LS/EKF/抗差/零速各策略在城市峡谷下的效果 |

> 📊 SPP优化详细测试结果见 [URBAN_CANYON_SPP_TEST_RESULTS.md](archive/URBAN_CANYON_SPP_TEST_RESULTS.md)（已归档：历史实测）

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
| 数据 | 公开: RTKLIB_EX_2.5.0目录下的RINEX+C版参考结果 |
| 验证 | Java SPP结果与C版spp_bds.pos逐历元对比 |
| ⚠️ | 需本地RTKLIB C版参考结果目录 |

---

### 3.2 RTK 相对定位测试

#### RtkTest — RTK基础定位

| 项目 | 说明 |
|------|------|
| 数据 | 本地私有RTCM3: base+rover对（BDS双频观测，含设备ID不可公开）；公共数据`rtcm3_gmsd`为单文件无法构成RTK双流，仅SPP回退 |
| 模式 | RTK Kinematic (PMODE_KINEMA) |
| 配置 | G+R+E+C全系统+BDS双频, IONOOPT_BRDC, ARMODE_FIXHOLD |
| 验证 | 历元数>0，FIX率统计 |
| 实测结论 | 本地私有数据: Fix率69.5%(Fix=82/118)；公共数据: 无RTK双流输入，仅SPP回退 |
| 注意 | 走RTCM二进制路径（ephTypes={1019,1020}），不经PostPosProcessor.buildEpochObs()排序 |

#### RtkBenchmarkTest — RTK基准测试（公共城市峡谷数据集）

| 项目 | 说明 |
|------|------|
| 数据 | PPC-Dataset (Tokyo run1/run2, Nagoya run1) + UrbanNav (Tokyo 2018) |
| 模式 | RTK Kinematic (PMODE_KINEMA) |
| 配置 | G+R+E+J+C双频, IONOOPT_BRDC, TROPOPT_SAAS, ARMODE_FIXHOLD, elmin=15° |
| 验证 | 历元数>0, 成功率>2%, Fix率≥阈值, 水平RMSE<阈值(仅Fix率达标时) |
| 参考真值 | PPC-Dataset: reference.csv (Applanix POS LVX-120, 5Hz, RMSE 5cm) |
| 场景 | downtown (Tokyo), urban (Nagoya) |
| 实测结论 | 当前仅验证不崩溃(阈值2%), 待收紧阈值并启用精度验证 |

#### RtkOptimizationTest — RTK优化逐项测试

| 项目 | 说明 |
|------|------|
| 数据 | 本地私有RTCM3: base+rover对(BDS双频)；公共数据`rtcm3_gmsd`为单文件无法构成RTK双流，仅SPP回退 |
| 模式 | RTK Kinematic |
| 测试组 | 0_基准AR, 1_自适应Q, 2_IGGIII, 3_SNR中值, 4_PAR重选, 5_锚固, 6_残差编辑, 7_逐级AR, 8_部分AR, 9_Bootstrapping, 10_BDS码偏差, 11_参数噪声, 12_大气冻结, 13_全开 |
| 验证 | 各组FIX率、平均坐标、STD统计 |

> **测试组→R编号映射**：0=基准(无R编号), 1=R1自适应Q, 2=R4 IGGIII, 3=R5 SNR中值, 4=R6 PAR重选, 5=R2锚固, 6=R9残差编辑, 7=R8逐级AR, 8=R10部分AR, 9=R11 Bootstrapping, 10=R12 BDS码偏差, 11=R7参数噪声(配置项), 12=R3大气冻结(配置项), 13=全开。R3/R7为配置项，在§4.2逐项对比中不单独列出。

| 实测结论 | 本地私有数据(base+rover RTCM3对): Fix率62.7%~69.5%(AR开启)；公共数据`rtcm3_gmsd`为单文件无法构成RTK双流，仅SPP回退 |

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

#### RtkTraceTest — 追踪日志

| 项目 | 说明 |
|------|------|
| 数据 | 本地私有RTCM3: base+rover对（含设备ID，不可公开）；无公共数据回退，缺数据时跳过 |
| 验证 | Trace输出完整性、各阶段日志 |

#### LogTraceTest — 日志追踪

| 项目 | 说明 |
|------|------|
| 数据 | 本地私有RTCM3: base+rover对（优先）；fallback公共`rtcm3_gmsd`(rover=base同文件，G+R+C) |
| 验证 | 日志输出完整性 |

#### RtkIonoptTest — 电离层自由组合测试

| 项目 | 说明 |
|------|------|
| 数据 | 本地RTCM3基站+流动站多小时数据（含设备ID，不可公开） |
| ⚠️ | 需配置 `test-data.properties` 中设备ID和日期 |

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
| 数据 | RINEX3.02 BDS双频 (rinex302_bds_rover/base.26o/26n，**数据已清除**) |
| 验证 | SPP/RTK/PPP三种模式基本功能 |
| 状态 | 数据已清除，测试跳过 |

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
| 数据 | 公开: GREAT-PVT PPPFLT+RTKFLT (RINEX3.04+SP3+CLK+UPD)，**未下载** |
| 配置 | `greatpvt.ppp.dir` + `greatpvt.rtk.dir` |
| 测试用例 | 8个：RINEX3.04解析、SP3/CLK/UPD加载、SPP多系统、PPP浮点、PPP-AR(WL+NL)、RTK多系统、RTK GPS-only、compactObsFreq |
| 验证 | 各模式历元成功率>0，PPP-AR有FLOAT解 |
| 场景 | IGS站GODN(PPP), SEPT+WUDA(RTK), 多系统G+E+C+R |
| 状态 | 数据未下载，测试跳过 |

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

> **编号说明**：R1~R12编号与[优化功能矩阵](CORE_OPTIMIZATION_MATRIX.md)一致，不连续是因为R3(电离层梯度)和R7(大气冻结)属于配置项而非独立优化开关，不适用于逐项对比测试。R13(参数噪声注入P阵)为R7的辅助配置项，用于测试噪声建模对滤波稳定性的影响，仅在RtkOptimizationsConfigTest中验证。

| 优化项 | 开关 | 测试类 | 开启测试 | 关闭测试 | 数据 | 预期差异 |
|--------|------|--------|----------|----------|------|----------|
| R1 自适应Q | `enableAdaptiveQ` | RtkOptimizationIndividualTest | 开启 | 基线 | 私有RTCM3 BDS双频(⚠️退化SPP) | Static: FIX率不变; Kinematic: 运动跟踪↑ |
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

> **编号说明**：P1~P8编号与[优化功能矩阵](CORE_OPTIMIZATION_MATRIX.md)一致，跳过P2(PPP-RTK)是因为PPP-RTK为独立模式而非PPP优化项，在P6/P7/P8中单独测试。

| 优化项 | 开关 | 测试类 | 验证方式 | 数据需求 |
|--------|------|--------|----------|----------|
| P1 GPT3+VMF3 | `enableGpt3Vmf3` | PppOptimizationsTest | 配置验证+返回值 | 内置(无数据) |
| P3 IERS2010 | `enableIers2010` | PppOptimizationsTest | 配置验证 | 内置 |
| P4 ISB/IFCB/IFB | `enableIsbIfcbIfb` | PppOptimizationsTest | 配置验证 | 内置 |
| P5 OSB | `enableOsb` | PppOptimizationsTest | 配置验证 | 内置 |
| P6 PPP-AR | `enablePppAR` | PppArFixVerificationTest + GreatPvtPositioningTest | 数值验证+端到端(GREAT-PVT UPD) | 内置+公开(GREAT-PVT) |
| P7 Fix-and-Hold | `enablePppArFixHold` | PppArFixVerificationTest | 最小历元守卫 | 内置 |
| P8 Partial AR | `enablePppPartialAR` | PppArFixVerificationTest | 周空间转换 | 内置 |

---

## 5. 推荐配置组合与对应测试

| 场景 | 推荐配置 | 测试类 | 数据 |
|------|----------|--------|------|
| **SPP开阔地** | EKF + 多普勒SNR | SppOptimizationTest | rinex304_gejc_elevated |
| **SPP城市** | EKF + 抗差 + 多普勒SNR | SppOptimizationTest | rinex304_gejc_elevated |
| **SPP静态监测** | EKF + 抗差 + 零速 + 多普勒SNR | SppOptimizationTest | rinex304_gejc_elevated |
| **RTK短基线静态** | 自适应Q + 锚固 + 大气冻结 | LandslideMonitorTest | open-sky_base (RTKLIB sample) |
| **RTK短基线动态** | 自适应Q + IGGIII + SNR中值 + 逐级AR + 部分AR + Bootstrapping | RtkOptimizationTest | 私有RTCM3 BDS双频(⚠️退化SPP) |
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

#### 精密产品大文件（PPP-AR / IGS MGEX）

| 文件 | 大小 | 来源 | 用途 |
|------|------|------|------|
| `product/cod0mgxfin_2023305.clk` | 33.9MB | CODE MGEX | CODE MGEX最终钟差(2023 DOY305) |
| `upd/upd_nl_2023305_G` | ~3.6MB | WHU | GPS窄巷UPD(PPP-AR) |
| `upd/upd_nl_2023305_E` | ~2.5MB | WHU | Galileo窄巷UPD(PPP-AR) |
| `upd/upd_nl_2023305_C` | ~3.0MB | WHU | BDS窄巷UPD(PPP-AR) |
| `product/ifcb_2023305` | 3.3MB | WHU | IFCB(频间码偏差，WHU命名无扩展名) |
| `nav/brdc3050.23p` | 11.6MB | IGS | 广播星历(2023 DOY305) |

> 以上精密产品大文件通过 `download-test-data.ps1` 获取，与 §2.4 精密产品小文件（≤2MB，已在主仓库）互补。

#### 网络VRS三角形数据集（EUREF/EPN，2026 DOY300）

三站 30 s 全天观测，站距 46.8~64.6 km，用于多基站网络 RTK（VRS）验证，详见 [NETWORK_VRS_DESIGN.md §3.2.1](NETWORK_VRS_DESIGN.md)。

| 文件 | 大小 | 来源 | 用途 |
|------|------|------|------|
| `gnss-validation/network-vrs/euref-2026300/CIMO00ITA_R_20263000000_01D_30S_MO.crx.gz` | 4.5MB | EUREF/EPN（BKG 镜像） | 意大利 CIMO 站原始 Hatanaka |
| `gnss-validation/network-vrs/euref-2026300/PRAT00ITA_R_20263000000_01D_30S_MO.crx.gz` | 2.5MB | EUREF/EPN（BKG 镜像） | 意大利 PRAT 站原始 Hatanaka |
| `gnss-validation/network-vrs/euref-2026300/VIRG00ITA_R_20263000000_01D_30S_MO.crx.gz` | 4.1MB | EUREF/EPN（BKG 镜像） | 意大利 VIRG 站原始 Hatanaka |
| `gnss-validation/network-vrs/euref-2026300/*.rnx` | 84.3MB | 同上，crx2rnx 解压 | 三站标准 RINEX 3.04 观测 |
| `gnss-validation/network-vrs/euref-2026300/stations.csv` | <1KB | 本项目生成 | 站名/DOMES/XYZ/BLH/文件名 |
| `gnss-validation/network-vrs/euref-2026300/baselines.csv` | <1KB | 本项目生成 | 三条基线长度与 ENU 分量 |
| `gnss-validation/network-vrs/euref-2026300/EPNIGB08.CRD` | ~0.1MB | EUREF | 98 站官方坐标（IGb08 框架） |

> 原始 `.crx.gz` 与解压 `.rnx` **同时保留**：前者体积小、可复现解压结果，后者供测试直接读取。
> 本地工作副本在 `data/network-vrs/2026/300/`（`data/` 在 `.gitignore`，不入主仓库）。

---

## 7. 上传测试数据

欢迎上传测试数据以扩展测试覆盖！请遵循以下规范：

### 7.1 上传前检查

- [ ] 单文件 ≤ 2MB（RTCM ≤ 500KB）
- [ ] 命名符合 `{scenario}_{role}_{date}[_{type}].{ext}` 格式
- [ ] 附带 `.meta` 文件且必填字段完整（scenario, role, systems, date, source, license）
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

按数据可得性分级：

**数据公开可得，缺集成验证**（可自行下载，优先级高）：

1. **PPP-AR 24h 静态精度** — IGS 24h RINEX + CODE MGEX SP3/CLK + WHU UPD，CDDIS 免费注册下载
2. **超长基线 RTK (>100km)** — IGS 双站 RINEX（如 WTZR+BRUX ~600km），CDDIS 免费注册下载；10~100km 中长基线已做链路级验证（HKWS+HKSL ~42km，见 §2）
3. **高速动态 RTK (>80 km/h)** — PPC-Dataset/UrbanNav 车载数据（~30 km/h）已实测（见 §2），缺高速轨迹的 base+rover 对

> 下载脚本：`test-data/download-igs-data.ps1`（CDDIS 需先配置 [NASA Earthdata Login](https://urs.earthdata.nasa.gov/users/new)）
>
> ⚠️ 以上数据虽公开可得，但需筛选出符合本仓库 `.meta` 规范的子集，并转换为项目测试框架可直接使用的格式（RINEX/RTCM3+参考真值）。Google Smartphone Decimeter Challenge 为城市动态数据，与下方"城市峡谷/深遮挡"场景有重叠，但该数据集缺标准base+rover对和真值。

**数据部分可得，需筛选适配**（工程量中等）：

4. **PPP-RTK** — MADOCA/Galileo HAS/QZSS CLAS SSR 流可注册获取，需录制+事后产品对齐
5. **多频(≥3频)** — IGS MGEX 三频测站 RINEX（BDS-3 B1c/B2a/B3i 或 GAL E1/E5a/E5b）

**数据确实稀缺，需自采或社区贡献**：

6. **城市峡谷/深遮挡 RTK** — 带真值的 base+rover 深遮挡成对数据基本无公开来源（Google SDC 为手机单站，缺base+rover对）
7. **Moving-Base RTK** — 公开双移动接收机同步观测数据极少
8. **森林/林冠** — 树冠遮挡，信噪比低，公开数据稀缺
9. **RTCM流式RTK公开数据** — 可公开的同步base+rover RTCM3文件对（本地数据已验证但含设备ID不可公开）

---

> 📖 **如何新增一个测试**：参见 [TEST_DEVELOPMENT_GUIDE.md](TEST_DEVELOPMENT_GUIDE.md)