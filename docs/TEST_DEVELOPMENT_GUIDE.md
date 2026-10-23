# 测试开发指南

> 如何新增一个GNSS定位测试用例：从数据准备到测试编写到文档更新的完整流程。

---

## 1. 总体流程

```
准备数据 → 编写.meta → 编写测试类 → 验证通过 → 更新DATA_GUIDE.md → 提交
```

---

## 2. 准备测试数据

### 2.1 数据分类

| 类型 | 存放位置 | 是否提交 | 说明 |
|------|----------|----------|------|
| 测试夹具 | `src/test/resources/` | ✅ | 单元测试必需的小文件 |
| 公共测试数据 | `test-data/` | ✅ | ≤2MB，无隐私 |
| 私有测试数据 | 本地磁盘 | ❌ | 大文件/含设备ID，通过 `test-data.properties` 配置 |
| 大文件数据 | 独立数据仓库 | ❌ | >2MB，通过下载脚本获取 |

### 2.2 放入数据文件

以短基线RTK数据为例：

```powershell
test-data/rinex/open-sky_rover_20260101.25o
test-data/rinex/open-sky_base_20260101.25o
test-data/nav/open-sky_20260101.25n
```

### 2.3 编写.meta元数据文件

每个数据文件须附带同名 `.meta` 文件。

#### 必填字段

| 字段 | 说明 | 枚举值 |
|------|------|--------|
| `scenario` | 数据场景 | `open-sky`, `urban`, `downtown`, `street`, `elevated`, `forest`, `indoor`, `highway`, `long-baseline` |
| `role` | 数据角色 | `rover`, `base`, `reference`, `nav`, `product` |
| `systems` | 卫星系统 | `G`, `G+R`, `G+E+J+C`, `G+R+E+C+J+S`（G=GPS, R=GLONASS, E=Galileo, J=QZSS, C=BDS, S=SBAS） |
| `date` | 观测日期 | `2026-01-01` |
| `source` | 数据来源项目 | `MobileGNSS-SPP`, `Net_Diff`, `rtklib`, `IGS`, `GFZ` |

#### 选填字段

| 字段 | 说明 | 示例 |
|------|------|------|
| `sample_rate` | 采样间隔 | `1s`, `30s` |
| `duration` | 数据时长 | `30min`, `24h` |
| `receiver` | 接收机型号 | `u-blox F9P`, `Leica GR50` |
| `antenna` | 天线型号 | `unknown`, `LEIAR25.R4` |
| `base.id` | 基站标识 | `3040`, `VRS-001` |
| `base.distance` | 基线长度 | `1.5km`, `15km` |
| `mode` | 定位模式 | `spp`, `dgps`, `rtk`, `rtk-static`, `rtk-fixed`, `ppp`, `ppp-ar`, `ppp-rtk` |
| `software` | 解算软件 | `RTKLIB demo5 b34k`, `GREAT-PVT` |
| `license` | 数据许可 | `MIT`, `IGS-data-policy`, `public`, `BSD-2-Clause` |
| `description` | 自由描述 | `短基线RTK流动站` |
| `location` | 采集地点 | `Beijing downtown`, `Hong Kong IGS` |
| `type` | 产品类型 | `sp3`, `clk`, `ionex`, `atx`, `bia` |
| `test_ids` | 引用该数据的测试类 | `MyNewRtkTest` |

#### 示例

```
# open-sky_rover_20260101.25o.meta
scenario=open-sky
role=rover
systems=G+E+C
date=2026-01-01
source=MyProject
sample_rate=1s
duration=30min
receiver=u-blox F9P
license=MIT
description=短基线RTK流动站
test_ids=MyNewRtkTest
```

```
# open-sky_base_20260101.25o.meta
scenario=open-sky
role=base
systems=G+E+C
date=2026-01-01
source=MyProject
sample_rate=1s
duration=30min
receiver=u-blox F9P
base.id=BASE01
base.distance=1.5km
license=MIT
description=短基线RTK基站
test_ids=MyNewRtkTest
```

---

## 3. 编写测试类

### 3.1 RINEX后处理定位测试模板

```java
package org.rtklib.java;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.rtklib.java.config.PrcOpt;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.rinex.PostPosProcessor;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("My new RTK Static test")
class MyNewRtkTest {

    private static final String ROVER_OBS =
        TestDataConfig.getTestDataFile("rinex/open-sky_rover_20260101.25o");
    private static final String BASE_OBS =
        TestDataConfig.getTestDataFile("rinex/open-sky_base_20260101.25o");
    private static final String NAV =
        TestDataConfig.getTestDataFile("nav/open-sky_20260101.25n");

    @Test
    @DisplayName("RTK Static positioning with G+E+C short baseline")
    void testRtkStatic() {
        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_STATIC;
        opt.nf = 2;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_CMP;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;
        opt.soltype = Constants.SOLTYPE_FORWARD;

        PostPosProcessor proc = new PostPosProcessor(opt);
        PostPosProcessor.PostPosResult result = proc.process(ROVER_OBS, BASE_OBS, NAV);

        assertTrue(result.successCount > 0, "RTK Static should solve at least one epoch");
    }
}
```

### 3.2 SPP流式定位测试模板（RTCM输入）

```java
@Test
@DisplayName("SPP with RTCM stream")
void testSppRtcm() {
    PrcOpt opt = new PrcOpt();
    opt.mode = Constants.PMODE_SINGLE;
    opt.navsys = Constants.SYS_GPS | Constants.SYS_CMP;
    opt.elmin = 15.0 * Constants.D2R;

    List<Sol> sols = new ArrayList<>();
    SppProcessor spp = new SppProcessor(opt, new PosHandler() {
        @Override public void onSolution(Sol sol, Ssat[] ssat) { sols.add(sol); }
        @Override public void onPosFail(GTime time, String msg) {}
        @Override public void onFinish(int total, int success, int fail) {
            log.info("SPP finish: total={}, success={}, fail={}", total, success, fail);
        }
    });

    byte[] rtcmData = Files.readAllBytes(Paths.get(RTCM_FILE));
    SppProcessor.SppResult result = spp.process(rtcmData);
    assertTrue(result.successCount > 0, "SPP should solve at least one epoch");
}
```

### 3.3 数据可能不存在的处理

使用 `Assumptions.assumeTrue()` 在数据不存在时自动跳过测试：

```java
import org.junit.jupiter.api.Assumptions;

@Test
void testWithOptionalData() {
    String file = TestDataConfig.getTestDataFile("product/igs15904.sp3");
    Assumptions.assumeTrue(Files.exists(Paths.get(file)), "SP3 file not available");

    Nav nav = new Nav();
    Sp3Reader.readsp3(file, nav, 0);
    assertTrue(nav.ne > 0);
}
```

### 3.4 私有数据路径配置

在 `test-data.properties` 中配置私有数据路径：

```properties
# test-data.properties (不提交到仓库)
greatpvt.ppp.dir=D:/code/GREAT-PVT/sample_data/PPPFLT
greatpvt.rtk.dir=D:/code/GREAT-PVT/sample_data/RTKFLT
```

测试类通过 `TestDataConfig.get()` 读取：

```java
String pppDir = TestDataConfig.get("greatpvt.ppp.dir", "");
Assumptions.assumeTrue(!pppDir.isEmpty(), "GREAT-PVT PPP dir not configured");
```

### 3.4.1 各模块配置说明

`test-data.properties` 按模块分为两套独立配置：

| 模块 | 配置类 | 配置文件位置 | 模板文件 | 说明 |
|------|--------|-------------|---------|------|
| **rtklib-core** | `org.rtklib.java.TestDataConfig` | 项目根目录或 `rtklib-core/src/test/resources/` | `test-data.properties.template` ✅ | 核心模块配置，含 `rover.file`/`base.file` 目录模式 |
| **rtklib-test** | 同上（引用 core 的 TestDataConfig） | 同上 | 无（共用 core 配置） | PPP/RTK 集成测试，依赖 core 的全局配置 |
| **rtklib-adjust** | `org.rtklib.java.adjust.TestDataConfig`（独立） | `rtklib-adjust/src/test/resources/` | `test-data.properties.template` ✅ | 独立配置，字段不同（`data.root`, `base.a`, `rover`, `date`） |
| **rtklib-product** | 无 | 无 | 无 | 不依赖私有数据 |
| **rtklib-research** | 无 | 无 | 无 | 不依赖私有数据 |

> **core 和 adjust 的配置互不干扰**：core 的 `TestDataConfig` 搜索项目根目录和 core 的 resources；adjust 有自己独立的 `TestDataConfig` 和配置文件。rtklib-test 模块直接引用 core 的 `TestDataConfig`，无需额外配置。

### 3.5 RTK/PPP 私有 RTCM3 数据配置（目录模式）

RTK 和 PPP 测试依赖本地私有 RTCM3 数据（含设备ID，不可公开）。通过 `rover.file` 和 `base.file` 配置，**同时支持文件路径和目录路径**，无需额外配置项。

#### 数据目录结构

RTCM3 数据按 `设备ID/日期/小时.rtcm3` 层级存储：

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

在 `test-data.properties` 中设置 `rover.file` 和 `base.file`：

```properties
# 单文件模式（处理1小时数据）
rover.file=D:\rtcm3\ROVER_ID\2026-05-20\08.rtcm3
base.file=D:\rtcm3\BASE_ID\2026-05-20\08.rtcm3

# 目录模式（处理整天数据）
rover.file=D:\rtcm3\ROVER_ID\2026-05-20
base.file=D:\rtcm3\BASE_ID\2026-05-20

# 目录模式（处理某设备所有数据，慎用）
rover.file=D:\rtcm3\ROVER_ID
base.file=D:\rtcm3\BASE_ID
```

**通过目录深度控制数据范围**，无需过滤参数：

| 配置路径深度 | 数据范围 | 适用场景 |
|-------------|---------|---------|
| `设备/日期/小时.rtcm3` | 单小时文件 | 快速验证 |
| `设备/日期/` | 该日所有小时 | 整天RTK/PPP解算 |
| `设备/` | 该设备所有日期和小时 | 长期统计（慎用） |

#### 目录模式配对规则

当 `rover.file` 和 `base.file` 均为目录时，自动配对：

1. 以基站目录为基准，递归扫描所有 `.rtcm3` 文件
2. 计算每个基站文件相对于基站目录的**相对路径**（如 `2026-05-20/8.rtcm3`）
3. 在流动站目录下查找**同名相对路径**的文件
4. 找到则配对，未找到则跳过该基站文件
5. 文件按路径字符串字典序排列

**要求**：
- 基站和流动站目录下的文件**相对路径必须一致**（即文件名和子目录结构相同）
- 小时文件名**补零**（`08.rtcm3` 而非 `8.rtcm3`），否则 `10.rtcm3` 会排在 `8.rtcm3` 前面（字典序 `10 < 8 < 9`）

> ⚠️ 程序按路径字符串排序，不按时间排序。若数据文件名不满足补零约定，请自行编写测试样例处理。

#### 代码使用

```java
// 方式1：获取所有 base-rover 文件对（目录模式推荐）
List<String[]> pairs = TestDataConfig.resolveBaseRoverPairs();
Assumptions.assumeTrue(!pairs.isEmpty(), "No base-rover data configured");
for (String[] pair : pairs) {
    String baseFile = pair[0];  // 基站文件绝对路径
    String roverFile = pair[1]; // 流动站文件绝对路径
    // 逐对处理...
}

// 方式2：单独解析 rover 或 base 路径（SPP等单站场景）
List<String> roverFiles = TestDataConfig.resolveRtcmFiles(TestDataConfig.getRoverFile());
for (String file : roverFiles) {
    // 逐文件处理...
}
```

#### 配置步骤

```powershell
# 1. 复制模板
cp rtklib-core/src/test/resources/test-data.properties.template `
   test-data.properties

# 2. 编辑填入本地路径
# rover.file=D:\rtcm3\ROVER_ID\2026-05-20
# base.file=D:\rtcm3\BASE_ID\2026-05-20

# 3. test-data.properties 已被 .gitignore 排除，不会提交到仓库
```

---

## 4. 验证测试通过

```powershell
# 运行单个测试类
mvn test -pl rtklib-core -Dtest="MyNewRtkTest"

# 运行并查看结果摘要
mvn test -pl rtklib-core -Dtest="MyNewRtkTest" 2>&1 | Select-String "Tests run|BUILD"
```

> **注意**：surefire 不会把 `mvn -D自定义键=值` 传给 fork 出的 JVM。测试内的开关请用 `System.getenv()` 读取环境变量，需要按次覆盖时先 `$env:MY_FLAG="1"` 再运行。

> **全量回归**：`mvn test` 会连同 JMH 基准 `JmhRunnerTest` 一起执行（20min+，无回归价值）。日常与 CI 统一用 `mvn test "-Dtest=!JmhRunnerTest" "-Dsurefire.failIfNoSpecifiedTests=false"`（PowerShell 下 `-D` 值含 `.` 会被拆词，必须加引号）；需要基准数据时再单独跑 `JmhRunnerTest`。

---

## 5. 更新文档

在 [DATA_GUIDE.md](DATA_GUIDE.md) 中以下位置添加条目：

1. **1.1 总览表** — 新增一行，如：`| **MyNewRtkTest** | RTK-Static | RINEX3.04 (G+E+C) | 公共: open-sky | ✅ |`
2. **2.x 数据清单** — 新增数据文件条目
3. **3.x 测试详情** — 新增 `#### MyNewRtkTest` 小节，描述数据、模式、配置、验证内容
4. **4.x 优化矩阵**（如涉及优化项）— 补充对应行

---

## 6. 提交

```powershell
git add test-data/ rtklib-core/src/test/ docs/DATA_GUIDE.md
git commit -m "test: add MyNewRtkTest for RTK Static with G+E+C short baseline"
```

---

## 7. 常见模式速查

| 定位模式 | `opt.mode` | 需要base数据 | 典型配置 |
|----------|-----------|--------------|----------|
| SPP | `PMODE_SINGLE` | ❌ | navsys, ionoopt=BRDC, tropopt=SAAS |
| DGPS | `PMODE_DGPS` | ✅ | 同SPP + base obs |
| RTK Kinematic | `PMODE_KINEMA` | ✅ | nf=2, ARMODE_FIXHOLD |
| RTK Static | `PMODE_STATIC` | ✅ | 同Kinematic |
| RTK Fixed | `PMODE_FIXED` | ✅ | 需设置opt.ru已知坐标 |
| PPP Kinematic | `PMODE_PPP_KINEMA` | ❌ | 需SP3+CLK精密产品 |
| PPP Static | `PMODE_PPP_STATIC` | ❌ | 同PPP Kinematic |