# GNSS 处理链路自动验证方案

> 四阶段递进验证：SPP → 短/长基线 RTK 对照 → 事后 PPP → PPP-RTK 事后 SSR
> 实时 Galileo HAS 独立接入，不混入主流程。

---

## 一、总体架构

```
┌─────────────────────────────────────────────────────────────────────┐
│                     GNSS 链路验证主流程                              │
│                                                                     │
│  阶段1          阶段2              阶段3           阶段4             │
│  SPP单站   ──→  短/长基线RTK  ──→  事后PPP   ──→  PPP-RTK事后SSR   │
│  链路通        固定率+RMS对照      SINEX比对       链路验证           │
│                                                                     │
│  SppValidation   RtkValidation    PppValidation   PppRtkValidation  │
│  Test.java       Test.java        Test.java       Test.java         │
└─────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│                    自动数据获取层                                     │
│                                                                     │
│  GnssValidationDataHelper (测试内)     ProductDownloader (产品模块)  │
│  ├── BJFS RINEX → 武大IGS FTP         ├── SP3/CLK → bdspride/IGN  │
│  ├── SatRef RINEX → FTPS/HTTPS        ├── BIA/ERP → bdspride/WHU  │
│  └── Hatanaka解压 (crx2rnx)           └── VMF3/GPT3 → Vienna TU   │
└─────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
                    ┌──────────────────┐
                    │ 收尾：Galileo HAS │
                    │ (NTRIP实时SSR)    │
                    │ 需注册，独立步骤   │
                    └──────────────────┘
```

### 数据获取职责划分

| 数据 | 下载实现 | 模块 | 理由 |
|------|---------|------|------|
| BJFS RINEX (IGS站) | `GnssValidationDataHelper.ensureBjfsRinex()` | 测试内 | 业务验证专用，非通用功能 |
| SatRef RINEX (HKWS/HKSS/HKSL) | `GnssValidationDataHelper.ensureSatRefRinex()` | 测试内 | 业务验证专用，非通用功能 |
| Hatanaka解压 | `GnssValidationDataHelper.hatanakaDecompress()` | 测试内 | SatRef数据格式需求 |
| MGEX SP3/CLK/ERP/BIA | `ProductDownloader.download()` | 产品模块 | 通用精密产品下载，同时测试产品模块 |

### 各阶段核心产出

| 阶段 | 测试类 | 核心产出 | 通过标准 |
|------|--------|---------|---------|
| 1 | `SppValidationTest` | 数据解析+SPP解算+坐标时序链路通 | 成功率 > 50% |
| 2 | `RtkValidationTest` | 固定率、ENU RMS、距离衰减效应 | 短基线固定率 > 长基线 |
| 3 | `PppValidationTest` | SINEX坐标比对、收敛后精度 | 链路通+坐标合理 |
| 4 | `PppRtkValidationTest` | SSR改正数解码+应用+定位链路通 | 链路通，不追求实时性能 |

---

## 二、数据目录结构

测试代码通过 `TestDataConfig.getTestDataDir()` 定位根目录（默认 `test-data/`），
所有验证数据统一放在 `gnss-validation/` 子目录下：

```
test-data/
└── gnss-validation/
    ├── spp/
    │   └── bjfs/                          ← 阶段1: BJFS单站
    │       ├── bjfs0010.25o               (RINEX观测)
    │       └── bjfs0010.25n               (RINEX导航)
    │
    ├── rtk/
    │   ├── short-baseline/
    │   │   └── hkws-hkss/                 ← 阶段2短基线: HKWS(rover)+HKSS(base)
    │   │       ├── rover*.obs  或  hkws*.25o
    │   │       ├── base*.obs   或  hkss*.25o
    │   │       └── *.nav      或  *.25n
    │   │
    │   └── long-baseline/
    │       └── hkws-hksl/                 ← 阶段2长基线: HKWS(base)+HKSL(rover)
    │           ├── rover*.obs  或  hksl*.25o
    │           ├── base*.obs   或  hkws*.25o
    │           └── *.nav      或  *.25n
    │
    ├── ppp/
    │   └── bjfs/                          ← 阶段3: BJFS+MGEX产品
    │       ├── bjfs0010.25o               (RINEX观测)
    │       └── bjfs0010.25n               (RINEX导航)
    │
    ├── ppp-rtk/
    │   └── bjfs/                          ← 阶段4: BJFS+MGEX产品+BIAS/ATX
    │       ├── bjfs0010.25o               (RINEX观测)
    │       └── bjfs0010.25n               (RINEX导航)
    │
    ├── product/                           ← 阶段3/4共用精密产品
    │   ├── sp3/
    │   │   └── COD0MGXFIN_20240010000_01D_05M_ORB.SP3
    │   ├── clk/
    │   │   └── COD0MGXFIN_20240010000_01D_30S_CLK.CLK
    │   ├── bia/                           ← 阶段4额外
    │   │   └── *.bia 或 *.bsx
    │   └── atx/                           ← 阶段4额外
    │       └── *.atx
    │
    └── reference/                         ← 阶段3参考坐标
        └── *.snx 或 *.pos
```

### 文件名匹配规则

各测试类的 `@BeforeAll` 按扩展名和前缀匹配文件：

| 测试 | 观测文件匹配 | 导航文件匹配 |
|------|-------------|-------------|
| SPP | `.25o` `.24o` `.23o` `.22o` `.21o` `.18o` `.obs` | `.25n` `.24n` `.23n` `.22n` `.21n` `.18n` `.nav` |
| RTK | 同上，另需文件名含 `rover`/`base` 前缀 | 同上 |
| PPP | 同SPP | 同SPP |
| PPP-RTK | 同SPP | 同SPP |

---

## 三、数据源与下载

### 3.1 数据源总览

| 数据 | 来源 | 协议 | 认证 | 自动化 |
|------|------|------|------|--------|
| BJFS obs+nav | 武大IGS数据中心 | FTP匿名 | 无需 | ✅ 全自动 |
| HKWS/HKSS/HKSL obs | 香港SatRef | FTPS(990)/HTTPS(443) 匿名 | 无需 | ✅ 全自动 |
| MGEX SP3/CLK | 武大IGS / CDDIS | FTP匿名 / HTTPS | CDDIS需Earthdata | ✅/⚠️ |
| MGEX BIA/ATX | CDDIS | HTTPS | 需Earthdata | ⚠️ 半自动 |
| SINEX参考坐标 | IGS | HTTPS | 无需 | ✅ 全自动 |
| Galileo HAS SSR | GSC NTRIP | NTRIP | 需注册审批 | ❌ 手动 |

### 3.2 香港SatRef（短/长基线RTK观测数据）

- **下载主机**: `rinex.geodetic.gov.hk`
- **协议**: FTPS（端口990）或 HTTPS（端口443），匿名登录
- **数据格式**: Hatanaka压缩RINEX（`.d`文件），需 `crx2rnx` 解压
- **采样率**: 1小时1s/5s、1天5s/30s
- **站对设计**:

| 组别 | 站对 | 基线长度 | 定位 |
|------|------|---------|------|
| 短基线 | HKWS–HKSS | 6.8 km | 短基线RTK性能评估 |
| 长基线对照 | HKWS–HKSL | ~42 km | 中长基线对照 |

- **注意事项**:
  - 旧FTP（端口21）已暂停，必须用FTPS或HTTPS
  - 下载前确认两站天线型号（从RINEX文件头 `ANTENNA TYPE` 字段）
  - 每次下载最大50MB，1天5s文件约16MB/站，分批下载即可
  - 备选短基线：HKSS–HKST（9.6km）

### 3.3 BJFS观测数据（SPP/PPP/PPP-RTK共用）

- **来源**: IGS数据中心
- **推荐下载通道**: 武汉大学IGS数据中心 `ftp://igs.gnsswhu.cn/`
  - 国内下载速度快，匿名FTP可直接拉取
  - 路径模式: `/pub/gps/data/daily/YYYY/DDD/yyO/bjfsDDDo.yyd.gz`
- **备选**: CDDIS `https://cddis.nasa.gov/archive/gnss/data/daily/`
  - 需NASA Earthdata Login（免费注册）

### 3.4 CODE MGEX精密产品（PPP/PPP-RTK用）

- **推荐通道**: 武汉大学IGS镜像 `ftp://igs.gnsswhu.cn/`
- **CODE官方**: `ftp.aiub.unibe.ch/CODE_MGEX/CODE/yyyy/`
- **CDDIS**: `https://cddis.nasa.gov/archive/gnss/products/mgex/WWWW/`
- **文件命名**:
  - 轨道: `COD0MGXFIN_YYYYDDD0000_01D_05M_ORB.SP3.gz`
  - 钟差: `COD0MGXFIN_YYYYDDD0000_01D_30S_CLK.CLK.gz`
  - 电离层: `CODGDDD0.yyi.gz`
  - ERP: `CODDDD0.yye.gz`

### 3.5 现有下载脚本

| 脚本 | 用途 | 覆盖范围 |
|------|------|---------|
| `test-data/download-test-data.ps1` | 从GitHub数据仓库克隆 | rinex/nav/rtcm/product/reference/nmea/config/tle |
| `test-data/download-igs-data.ps1` | IGS/MGEX公开数据 | WTZR/BRUX/PORE RINEX + CODE MGEX SP3/CLK + IONEX + ERP |

> **注意**: 现有脚本不覆盖 `gnss-validation/` 目录结构，需创建专用下载脚本
> `test-data/download-gnss-validation-data.ps1`，见第六节。

---

## 四、处理策略

### 4.1 阶段1：SPP（SppValidationTest）

| 项目 | 设置 | 理由 |
|------|------|------|
| 定位模式 | SPP（单点定位） | 验证观测解析+广播星历解算链路 |
| 卫星系统 | 默认（GPS） | 单系统即可验证链路 |
| 高度截止角 | 默认 | 不追求精度 |

**测试用例**:
- `1.1` RINEX解析与SPP解算链路通
- `1.2` 坐标时序输出与合理性检查（纬度±89°、经度±180°、高度±9000m）
- `1.3` SPP解算成功率统计（>50%）

### 4.2 阶段2：短/长基线RTK对照（RtkValidationTest）

**两组必须用同一套参数，控制变量**:

| 项目 | 设置 | 理由 |
|------|------|------|
| 定位模式 | Static | 评估基线性能，非动态场景 |
| 频率数 | 2（双频） | 提高固定率 |
| 卫星系统 | GPS+GLONASS+Galileo | 多系统提高可用卫星数 |
| 高度截止角 | 15° | 两组一致 |
| 电离层 | 广播星历（BRDC） | 短/中基线足够 |
| 对流层 | 估计（EST） | 标准处理 |
| 模糊度固定 | Fix-and-Hold | 固定率是核心对比指标 |
| 基站坐标 | 先验XYZ | 从RINEX头或已知坐标 |
| 采样率 | 1s或5s，两组一致 | 1小时1s或1天30s均可 |
| 天线PCV | 按实际型号加载ANTEX | 避免PCV引入额外差异 |

**测试用例**:
- `2.1` 短基线RTK链路通（HKWS-HKSS, ~6.8km）
- `2.2` 短基线固定率与ENU RMS统计
- `2.3` 长基线RTK链路通（HKWS-HKSL, ~42km）
- `2.4` 长基线固定率与ENU RMS统计
- `2.5` 短/长基线对照：固定率与RMS对比

**预期**: 6.8km短基线的固定率和RMS应明显优于42km长基线。

### 4.3 阶段3：事后PPP（PppValidationTest）

| 项目 | 设置 | 理由 |
|------|------|------|
| 定位模式 | PPP Static | 事后静态PPP |
| 频率数 | 2（双频IF） | 消除一阶电离层 |
| 卫星系统 | GPS+GLONASS+Galileo | 多系统加速收敛 |
| 高度截止角 | 15° | 标准设置 |
| 电离层 | IFLC（无电离层组合） | PPP标准处理 |
| 对流层 | 估计（EST） | PPP标准处理 |
| 星历类型 | 精密星历（PRECE） | 使用SP3/CLK |
| 动力学 | 关 | 纯静态 |

**测试用例**:
- `3.1` SP3/CLK精密产品加载
- `3.2` PPP解算链路通
- `3.3` PPP坐标与SINEX参考值比对
- `3.4` PPP收敛后精度统计（跳过前1/3历元）

### 4.4 阶段4：PPP-RTK事后SSR（PppRtkValidationTest）

| 项目 | 设置 | 理由 |
|------|------|------|
| 定位模式 | PPP-RTK Static | 事后SSR改正 |
| 其余 | 同阶段3 | 保持一致 |
| BIAS/UPD | 可选 | 有则支持AR，无则浮点解 |
| ANTEX | 可选 | 天线相位中心改正 |

**测试用例**:
- `4.1` PPP-RTK精密产品加载验证
- `4.2` PPP-RTK解算链路通
- `4.3` PPP-RTK固定率统计
- `4.4` PPP-RTK与PPP浮点解对照

---

## 五、执行指南

### 5.1 前置条件

1. **Java 17+** 和 **Maven 3.8+**
2. **测试数据**: 按第六节下载数据到 `test-data/gnss-validation/`
3. **crx2rnx**（可选）: SatRef数据为Hatanaka压缩格式，需解压
4. **NASA Earthdata账号**（可选）: CDDIS下载需要，免费注册

### 5.2 编译项目

```bash
cd D:\code\rtklib_java
mvn install -DskipTests -pl rtklib-core,rtklib-stream,rtklib-adjust,rtklib-product -am
```

### 5.3 逐阶段运行

```bash
# 阶段1: SPP
mvn test -Dtest="org.rtklib.java.test.gnssvalidation.SppValidationTest" -pl rtklib-test

# 阶段2: 短/长基线RTK对照
mvn test -Dtest="org.rtklib.java.test.gnssvalidation.RtkValidationTest" -pl rtklib-test

# 阶段3: 事后PPP
mvn test -Dtest="org.rtklib.java.test.gnssvalidation.PppValidationTest" -pl rtklib-test

# 阶段4: PPP-RTK事后SSR
mvn test -Dtest="org.rtklib.java.test.gnssvalidation.PppRtkValidationTest" -pl rtklib-test
```

### 5.4 一次性全部运行

```bash
mvn test -Dtest="org.rtklib.java.test.gnssvalidation.*" -pl rtklib-test
```

### 5.5 数据不可用时的行为

所有测试类在 `@BeforeAll` 中检测数据可用性。**数据不可用时测试不会失败**，
而是优雅跳过（日志输出跳过原因和期望的数据目录路径）：

```
SPP数据文件未找到于: ...\test-data\gnss-validation\spp\bjfs
跳过: 数据不可用，请将BJFS RINEX观测+导航文件放入 ...\test-data\gnss-validation\spp\bjfs
```

---

## 六、自动数据获取机制

**不需要外部下载脚本**。所有数据获取在测试 `@BeforeAll` 中自动完成：
本地已有数据则跳过下载，否则自动从远程获取。

### 6.1 数据获取流程

```
@BeforeAll checkData():
  1. 检查本地数据目录是否已有RINEX文件
  2. 若无 → GnssValidationDataHelper 自动下载
  3. 检查本地是否已有MGEX精密产品
  4. 若无 → ProductDownloader 自动下载（同时测试产品模块）
  5. 扫描本地文件，设置 dataAvailable 标志
```

### 6.2 各阶段数据获取详情

| 阶段 | 观测数据获取 | 精密产品获取 |
|------|-------------|-------------|
| 1 SPP | `helper.ensureBjfsRinex("spp", year, doy)` → 武大IGS FTP | 无需 |
| 2 RTK | `helper.ensureSatRefRinex(dir, station, year, doy, "30s")` → SatRef FTPS/HTTPS | 无需 |
| 3 PPP | `helper.ensureBjfsRinex("ppp", year, doy)` → 武大IGS FTP | `ProductDownloader.download(date, SP3, CLK)` |
| 4 PPP-RTK | `helper.ensureBjfsRinex("ppp-rtk", year, doy)` → 武大IGS FTP | `ProductDownloader.download(date, SP3, CLK, BIA)` |

### 6.3 GnssValidationDataHelper 核心方法

| 方法 | 功能 | 数据源 |
|------|------|--------|
| `ensureBjfsRinex(subDir, year, doy)` | 下载BJFS站RINEX obs+nav | 武大IGS FTP → IGN回退 |
| `ensureIgsRinex(dir, station, year, doy)` | 下载任意IGS站RINEX | 武大IGS FTP → IGN回退 |
| `ensureSatRefRinex(dir, station, year, doy, sampling)` | 下载香港SatRef站RINEX | SatRef FTPS(990) → HTTPS回退 |
| `ensureMgexProducts(year, doy, types)` | 下载MGEX精密产品 | `ProductDownloader`（bdspride/IGN/WHU） |
| `hatanakaDecompress(path)` | Compact RINEX → 标准RINEX | 纯Java实现，无需外部crx2rnx |
| `decompressGz(path)` | gzip解压 | 纯Java实现 |

### 6.4 下载优先级与回退

**BJFS RINEX (IGS站)**:
1. 武大IGS `ftp://igs.gnsswhu.cn/pub/gps/data/daily/` — 国内首选
2. IGN `ftp://igs.ign.fr/pub/igs/data/daily/` — 国际回退

**SatRef RINEX (香港站)**:
1. FTPS `ftps://rinex.geodetic.gov.hk:990/RINEX3/` — 官方首选
2. HTTPS `https://rinex.geodetic.gov.hk/RINEX3/` — 回退

**MGEX精密产品 (ProductDownloader)**:
1. `ftps://bdspride.com/wum/{GPSW}/` — PRIDE主源
2. `ftp://igs.ign.fr/pub/igs/products/mgex/{GPSW}/` — IGS官方
3. `ftp://igs.gnsswhu.cn/pub/whu/phasebias/{YYYY}/` — WHU镜像
4. `ftps://bdspride.com/wcc/{GPSW}/` — WCC回退
5. WHU IGS2/COD最终产品 — 历史回退

### 6.5 认证配置

| 数据源 | 认证方式 | 配置 |
|--------|---------|------|
| SatRef | 匿名 | 无需配置 |
| 武大IGS | 匿名FTP | 无需配置 |
| bdspride.com | 匿名FTPS | 无需配置 |
| IGN | 匿名FTP | 无需配置 |
| CDDIS | NASA Earthdata | `~/.rtklib/credentials.properties`: `cddis.username=USER` `cddis.password=PASS` |
| GSC HAS | NTRIP凭证 | 需在 https://gsc-europa.eu 注册审批 |

### 6.6 测试参数配置

各测试类通过常量指定测试日期，可按需修改：

```java
// SppValidationTest.java / RtkValidationTest.java / PppValidationTest.java / PppRtkValidationTest.java
private static final int TEST_YEAR = 2024;  // 修改为所需年份
private static final int TEST_DOY = 001;    // 修改为所需年积日
```

---

## 七、风险与前置确认

| 事项 | 需确认内容 | 影响 | 处理方式 |
|------|-----------|------|---------|
| HKWS/HKSS天线型号 | 下载RINEX文件头查 `ANTENNA TYPE` | 决定"天线一致"能否写入结论 | 不一致时短基线结果仍有效，仅PCV优势不成立 |
| 两组数据时段匹配 | 同一UTC日期、同一小时窗口 | 排除电离层日变化对固定率对比的干扰 | 脚本默认同日同时段 |
| SatRef下载限制 | 每次50MB | 1天5s文件约16MB/站 | 4站分批下载 |
| 长基线固定率可能很低 | 42km中长基线在电离层活跃时段难固定 | 固定率过低时对照效果弱 | 记录浮点解RMS作为对照，不强行追求固定 |
| BJFS数据可得性 | 武大IGS镜像是否及时更新 | 数据延迟影响测试执行 | 备选CDDIS（需认证） |
| CDDIS认证 | 需Earthdata账号 | 无认证则MGEX BIA/ATX下载失败 | 阶段4 BIA/ATX标记为可选，不影响链路验证 |

---

## 八、验证检查清单

### 阶段1: SPP ✅

- [ ] BJFS RINEX obs+nav 文件已放入 `gnss-validation/spp/bjfs/`
- [ ] `1.1` RINEX解析与SPP解算链路通 — 总历元>0, 成功历元>0
- [ ] `1.2` 坐标时序合理性 — 纬度±89°, 经度±180°, 高度±9000m
- [ ] `1.3` SPP成功率 > 50%

### 阶段2: 短/长基线RTK对照 ✅

- [ ] HKWS/HKSS RINEX 已放入 `gnss-validation/rtk/short-baseline/hkws-hkss/`
- [ ] HKWS/HKSL RINEX 已放入 `gnss-validation/rtk/long-baseline/hkws-hksl/`
- [ ] 两站天线型号已确认（RINEX头 `ANTENNA TYPE`）
- [ ] `2.1` 短基线链路通
- [ ] `2.2` 短基线固定率+ENU RMS
- [ ] `2.3` 长基线链路通
- [ ] `2.4` 长基线固定率+ENU RMS
- [ ] `2.5` 对照：短基线固定率 > 长基线固定率

### 阶段3: 事后PPP ✅

- [ ] BJFS RINEX 已放入 `gnss-validation/ppp/bjfs/`
- [ ] MGEX SP3/CLK 已放入 `gnss-validation/product/sp3/` 和 `clk/`
- [ ] SINEX参考 已放入 `gnss-validation/reference/`
- [ ] `3.1` SP3/CLK加载成功 (ne>0, nc>0)
- [ ] `3.2` PPP解算链路通
- [ ] `3.3` PPP坐标与SINEX参考比对
- [ ] `3.4` PPP收敛后ENU RMS

### 阶段4: PPP-RTK事后SSR ✅

- [ ] BJFS RINEX 已放入 `gnss-validation/ppp-rtk/bjfs/`
- [ ] MGEX SP3/CLK 已放入（与阶段3共用）
- [ ] BIAS/UPD 已放入 `gnss-validation/product/bia/`（可选）
- [ ] ANTEX 已放入 `gnss-validation/product/atx/`（可选）
- [ ] `4.1` 精密产品加载成功
- [ ] `4.2` PPP-RTK解算链路通
- [ ] `4.3` PPP-RTK固定率统计
- [ ] `4.4` PPP-RTK与PPP浮点解对照

### 收尾: Galileo HAS（独立步骤）

- [ ] GSC账号已注册审批
- [ ] NTRIP连接凭证已获取
- [ ] HAS改正数解码链路通
- [ ] PPP实时解算链路通

---

## 九、已知站参考坐标

### IGS站（ITRF2020, ~2023.0）

| 站名 | 位置 | X (m) | Y (m) | Z (m) |
|------|------|-------|-------|-------|
| WTZR | Wettzell, DE | 4079145.424 | 931779.648 | 4801558.831 |
| BRUX | Brussels, BE | 4027894.006 | 307045.599 | 4919542.097 |
| PORE | Poree, FR | 4187485.753 | 266541.208 | 4770261.078 |

### 香港SatRef站

| 站名 | 位置 | 大致坐标 | 用途 |
|------|------|---------|------|
| HKWS | 西贡 | 22.3°N 114.3°E | 短基线rover + 长基线base |
| HKSS | 沙田 | 22.4°N 114.2°E | 短基线base |
| HKSL | 鲗鱼涌 | 22.3°N 114.2°E | 长基线rover |

> 精确坐标需从RINEX文件头或SatRef官方站信息获取。

---

## 十、扩展与后续

1. **中国大陆短基线**: 国家地震科学数据中心有260+基准站，但需离线申请，
   拿到数据后手动放入脚本输入目录，跑同一套RTKLIB配置
2. **城市峡谷/深遮挡**: 公开base+rover深遮挡成对数据基本不存在，
   需自行采集或寻找受限数据源
3. **Moving-Base**: 公开双移动接收机同步观测数据极少
4. **实时PPP-RTK**: MADOCA/HAS/CLAS可注册获取，但需录制+对齐，工程量大
5. **动态车载RTK**: RTKLIB sample / rtkexplorer F9P / Google SDC 数据集