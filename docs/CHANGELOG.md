# RTKLIB Java 变更记录

本文件记录版本间的重要变更、Bug修复及与GitHub Issue的对应关系。

格式遵循 [Keep a Changelog](https://keepachangelog.com/)，版本号遵循 [Semantic Versioning](https://semver.org/)。

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