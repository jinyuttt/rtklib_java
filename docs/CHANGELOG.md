# RTKLIB Java 变更记录

本文件记录版本间的重要变更、Bug修复及与GitHub Issue的对应关系。

格式遵循 [Keep a Changelog](https://keepachangelog.com/)，版本号遵循 [Semantic Versioning](https://semver.org/)。

---

## [2.0.7] - 2026-09-19

### Added

- **rtklib-adjust 实测数据验证**：新增 `AdjustRealDataTest`，使用2基站1测站连续9小时RTCM实测数据完整验证多基线平差功能
  - `testMultiBaselineAdjust9Hours()`：2基站1测站连续9小时RTK解算+多基线平差，验证FIX比例、σ₀分布、Baarda T统计量
  - `testSppBaseStationPosition()`：SPP定位基站坐标，与RTCM 1005对比，验证基站坐标偏差检测
  - `testStaticSolveBaseA()`：静态模式解算基站精确坐标，验证基站坐标修正后平差σ₀收敛
  - `testAdjustWithSppBaseAOnly()` / `testAdjustWithSppBaseBOnly()`：单基站SPP替换验证，对比修正前后σ₀变化
  - `testCrossValidationStation0002()`：交叉验证第二测站基线一致性
  - `testAdjustWithCorrectedBaseB()`：修正基站坐标后平差验证
  - 数据配置通过 `test-data.properties` 管理（模板 `test-data.properties.template`），不提交到仓库

- **基站异常诊断功能**：新增 `BaseStationDiagnoser`，当σ₀持续超限时自动定位异常基站并给出建议坐标
  - 2基线场景：SPP定位辅助判断异常方向
  - k≥3基线场景：残差分析定位异常基站
  - 静态解算给出建议坐标（mm级相对精度）
  - 滑动窗口+超限比例触发机制，避免偶然异常误触发
  - 诊断结果缓存，修正坐标后可清除重新验证
  - 回调推送，每个基站只推送一次

- **数据源抽象**：新增 `GnssDataSource` 接口及三种实现
  - `RtcmFileDataSource`：事后RTCM文件，支持多文件（多小时数据）
  - `RinexFileDataSource`：事后RINEX文件，需obs+nav文件
  - `RtcmMemoryDataSource`：实时RTCM流，逐帧feed累积数据
  - 统一接口：SPP定位、RTK基线解算、静态解算、天线坐标提取

- **诊断模型**：新增 `AdjustDiagnosisConfig`（诊断配置）、`BaseStationDiagnosis`（诊断结果）、`BaseDiagnosisHandler`（回调接口）
  - 异常等级：NORMAL / WARNING / ERROR / CRITICAL / UNKNOWN
  - 3D偏差阈值：<0.5m NORMAL、0.5~2m WARNING、2~5m ERROR、>5m CRITICAL

- **基站诊断测试**：新增 `BaseStationDiagnoserTest`，验证2基站诊断流程和缓存机制

### Verified

- 实测数据：2基站1测站，连续9小时RTCM数据 ✅
- 多基线平差：双FIX历元平差成功，σ₀分布合理 ✅
- 基站坐标偏差检测：SPP定位与RTCM 1005对比，成功识别异常基站 ✅
- 静态解算修正：修正基站坐标后σ₀显著收敛 ✅
- 基站诊断器：异常基站定位、建议坐标生成、缓存机制 ✅

---

## [2.1.0] - 2026-09-16

### Added

- **RinexObsWriter 新增 obstype 观测类型选项**（对齐 RTKLIB C 版 convbin/rtkconv 的 obstype 选项）
  - 新增位标志常量：`OBSTYPE_PR`(0x01)、`OBSTYPE_CP`(0x02)、`OBSTYPE_DOP`(0x04)、`OBSTYPE_SNR`(0x08)、`OBSTYPE_ALL`(0x0F)
  - 新增 `setObstype(int)` 方法，控制 RINEX 3.x 输出中包含哪些观测类型（C/L/D/S）
  - 默认值 `OBSTYPE_ALL`（输出 CLDS），与 RTKLIB rtkconv GUI 默认行为一致
  - `collectObsCodes()` 根据 obstype 位标志动态生成观测类型前缀列表

- **RtcmToRinexConverter 新增 obstype 传递**
  - 新增 `obstype` 字段（默认 `OBSTYPE_ALL`）及 `setObstype(int)` setter
  - `initializeWriters()` 中创建 `RinexObsWriter` 后立即调用 `setObstype()` 传递选项

- **RtcmFileToRinexConverter 新增 obstype 传递**
  - 新增 `obstype` 字段（默认 `OBSTYPE_ALL`）及 `setObstype(int)` setter
  - `convert()` 中创建 `RtcmToRinexConverter` 后调用 `setObstype()` 传递选项

### Fixed

- **[#1](https://github.com/jinyuttt/rtklib_java/issues/1) RinexObsWriter 缺失 C 版的 obstype 观测类型开关机制：SNR(S*) 与多普勒(D*) 无法输出，写入端 'S'/'D' 分支成为死代码**
  - 原因：`collectObsCodes()` 硬编码仅生成 C 和 L 前缀，缺少 D 和 S，导致 RINEX 头部无 D*/S* 观测类型声明，写入端 `typePrefix == 'D'` / `typePrefix == 'S'` 分支永远不会执行
  - 修复：通过 obstype 位标志动态控制输出前缀，默认包含全部 CLDS，与 RTKLIB C 版 convbin/rtkconv 的 obstype 选项对齐
  - 附修：`RtcmToRinexConverter` obstype 设置时序问题 — `obsWriter` 在 `convert()` 内部 `initializeWriters()` 中创建，外部无法在创建后、写入前设置 obstype；改为在 `RtcmToRinexConverter` 中保存 obstype 字段，`initializeWriters()` 创建 obsWriter 后立即传递
  - 影响链路：`RtcmFileToRinexConverter` → `RtcmToRinexConverter` → `RinexObsWriter`

### Changed

- **GnssBaselineAdjustTest 适配 EJML API 变更**
  - `getNumCols()` → `numCols()`，适配 EJML 0.41 矩阵方法名

### Verified

- 编译通过：`mvn compile -pl rtklib-core` ✅
- 测试通过：`RinexObsWriterTest`（2 用例） ✅
  - `testObstypeAll()`：默认 OBSTYPE_ALL 输出包含 C2I、L2I、D2I、S2I ✅
  - `testObstypeCL()`：仅 OBSTYPE_PR|OBSTYPE_CP 输出包含 C2I、L2I，不含 D2I、S2I ✅

---

## 修改文件清单

| 文件 | 变更类型 | 说明 |
|------|---------|------|
| `rtklib-core/.../rinex/RinexObsWriter.java` | 修改 | 新增 obstype 常量/字段/setter，修改 collectObsCodes() 动态生成观测类型 |
| `rtklib-core/.../rinex/RtcmToRinexConverter.java` | 修改 | 新增 obstype 字段/setter，initializeWriters() 中传递给 obsWriter |
| `rtklib-core/.../rinex/RtcmFileToRinexConverter.java` | 修改 | 新增 obstype 字段/setter，convert() 中传递给 RtcmToRinexConverter |
| `rtklib-core/.../RinexObsWriterTest.java` | 新增 | 验证 obstype 选项功能的测试用例 |
| `rtklib-adjust/.../GnssBaselineAdjustTest.java` | 修改 | 适配 EJML 0.41 API |

---

## 使用示例

### 默认行为（输出全部 CLDS）

```java
RtcmFileToRinexConverter converter = new RtcmFileToRinexConverter(3.05, outputDir, "BASE");
converter.convert(rtcmFilePath);
// 输出包含 C2I L2I D2I S2I 等全部观测类型
```

### 仅输出伪距和载波（CL）

```java
RtcmFileToRinexConverter converter = new RtcmFileToRinexConverter(3.05, outputDir, "BASE");
converter.setObstype(RinexObsWriter.OBSTYPE_PR | RinexObsWriter.OBSTYPE_CP);
converter.convert(rtcmFilePath);
// 输出仅包含 C2I L2I，不含 D2I S2I
```

### 直接使用 RinexObsWriter

```java
RinexObsWriter writer = new RinexObsWriter(3.05, obsFile, sta);
writer.setObstype(RinexObsWriter.OBSTYPE_PR | RinexObsWriter.OBSTYPE_CP);
writer.setObsData(obs);
writer.write();
```

### 通过 RtcmToRinexConverter

```java
RtcmToRinexConverter converter = new RtcmToRinexConverter(3.05, outputDir, "BASE");
converter.setObstype(RinexObsWriter.OBSTYPE_PR | RinexObsWriter.OBSTYPE_CP | RinexObsWriter.OBSTYPE_DOP);
converter.convert(rtcmData, len);
// 输出包含 C L D，不含 S
```