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

---

## 4. 验证测试通过

```powershell
# 运行单个测试类
mvn test -pl rtklib-core -Dtest="MyNewRtkTest"

# 运行并查看结果摘要
mvn test -pl rtklib-core -Dtest="MyNewRtkTest" 2>&1 | Select-String "Tests run|BUILD"
```

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