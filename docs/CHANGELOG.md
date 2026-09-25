# RTKLIB Java 变更记录

本文件记录版本间的重要变更、Bug修复及与GitHub Issue的对应关系。

格式遵循 [Keep a Changelog](https://keepachangelog.com/)，版本号遵循 [Semantic Versioning](https://semver.org/)。

---

## [2.2.3] - 2026-09-26

### Added

- **TraceLog V2 全模式日志追踪系统**：基于topic+action字符串的高层事件追踪，覆盖SPP/RTK/PPP/PPP-AR/PPP-RTK/Adjust全模式
  - `Trace.emit(topic, action, cfg, cb, epoch, time, kv...)`：唯一入口，6层过滤+拼装+诊断+统计
  - `Trace.summary(cfg, cb)`：汇总输出（total/success/fail/fail_reasons/fix/float/conv_epoch/max_ar_shift）
  - `TraceConfig`：V2配置（topics白名单/actions/samplerate/maxEpoch/maxOutputLines/targetSats/refEcef/convergenceThresh/arShiftWarnThresh）
  - `TraceStats`：累积统计（totalEpochs/successEpochs/failEpochs/failReasons/convEpoch/fixCount/floatCount/maxArShift）
  - 3条内联诊断：①位置偏差d_dN/d_dE/d_dU/d_d3D/d_conv（自动计算，需设refEcef）②AR偏移告警d_WARN=LARGE_SHIFT ③失败原因累积failReasons
  - 收敛历元检测：连续N个历元d3D<thresh时记录convEpoch
  - V1/V2共存：V1（RtkTrace/PppTrace+TraceControl）输出详细阶段数据（H矩阵/R对角线/逐卫星bias），V2输出高层事件+诊断，共享TraceCallback
  - `TraceControl.toTraceConfig()`：V1→V2桥接，一键迁移

- **V2全引擎集成**：29处`Trace.emit()`调用覆盖4个处理引擎
  - RtkCore：11处（SATELLITE/BASELINE/AMBIGUITY/FILTER/AR/POSITION/RESULT）
  - PppCore：7处（SATELLITE/AMBIGUITY/FILTER/POSITION/RESULT）
  - PppCoreEx：7处（同PppCore）
  - SppProcessor：4处（SATELLITE/POSITION/RESULT）

- **V1 RtkCore完整集成**：7处`RtkTrace.traceStage0~6()`调用补全（此前RtkCore中一处V1调用都没有）

- **V2 setter传播**：RtkProcessor/RinexRtkProcessor/PppProcessor/SppProcessor均新增`setTraceConfig()`+`setTraceCallback()`

- **Rtk新增`traceConfig`字段**：与V1的`traceControl`并列，独立控制V2输出

### Changed

- `TraceCallback`新增`default void onLine(String line)`委托给`onTrace()`，V1完全兼容
- `TraceControl`新增`toTraceConfig()`桥接方法，V1 stages位掩码→V2 topics集合

### Verified

- 全项目编译通过（core+adjust+product+otl） ✅

### Milestone

**v2.2.3 标志 GNSS 主要算法内容已完善。** SPP/RTK/PPP/PPP-AR/PPP-RTK 五种定位模式、多基线间接平差、轨道计算、精密产品下载、对流层模型（GPT3+VMF3）、海洋潮汐（BLQ/OTL）、日志追踪（V1+V2）等核心功能均已实现并集成。后续版本主要工作转入**测试验证与Bug修复**，重点包括：SP3星历GEO卫星兼容性、PPP-AR固定率提升、V2日志追踪实测验证、测试数据硬编码路径清理等。

---

## [2.2.2] - 2026-09-25

### Fixed

- **PPP精确模式历元失败率过高（P0修复）**：精确星历模式下75%历元失败的根本原因是SP3产品未覆盖部分BDS GEO卫星
  - **根因**：WUM0MGXRAP SP3产品不含BDS GEO卫星（C01/C02/C03/C07/C09），导致这些卫星位置计算返回(0,0,0)，仰角=-89.8°被剔除，仅剩3颗MEO/IGSO卫星低于MIN_NSAT_SOL(4)要求
  - **修复**：`EphModel.satposPrec()` 增加广播星历降级策略，当精确星历不可用时自动回退到广播星历
  - **效果**：历元成功率从25%提升至75.8%（3倍改进），可用卫星数从3颗增至8颗
  - **实现细节**：新增 `satposBrdcFallback()` 辅助方法，在Sp3Reader.pephpos()或pephclk()失败时调用satposBrdc()获取广播星历位置和钟差
  - **已知限制**：降级卫星使用广播星历精度较低，影响PPP-AR模糊度收敛速度；当前WL固定率仍<1%，需进一步优化（P1问题）
  - 修改文件：`EphModel.java:445-517`

- **PPP-AR Fix-and-Hold缺少最小连续固定历元检查（P2修复）**：`pppArFixHoldMinEp=50`参数已定义但从未被检查
  - **根因**：`PppAmbFix.pppArFixHold()`未检查`rtk.nfix`，导致首次FIX即收紧方差，可能因错误固定导致发散
  - **修复**：在`pppArFixHold()`入口添加`rtk.nfix < cfg.pppArFixHoldMinEp`守卫条件；在`PppCoreEx`中正确管理`rtk.nfix`计数器（FIX时递增，非FIX时归零）
  - **效果**：Fix-and-Hold现在需要连续50个FIX历元后才收紧方差，避免过早锁定
  - 修改文件：`PppAmbFix.java:268`, `PppCoreEx.java:188-197`

- **PPP Partial AR模糊度固定单位错误（P3修复）**：`pppPartialAR()`在米空间直接对浮点值取整，数学上无意义
  - **根因**：LAMBDA方法要求输入为循环（cycle）空间的模糊度（整数特性），但原代码直接使用米空间的`rtk.x[idx]`并`Math.round()`，对不同波长的卫星产生错误固定值
  - **修复**：收集阶段存储每颗卫星的λ₁；构建LAMBDA输入时将y[]和Q[][]从米空间转换到L1循环空间（`y[i]/=lam1`, `Q[i][j]/=(lam1_i*lam1_j)`）；固定后将结果转回米空间（`Math.round(b[i])*lam1`）
  - **效果**：Partial AR现在正确地在L1循环空间执行整数模糊度搜索，固定值物理意义正确
  - 修改文件：`PppAmbFix.java:321-438`

- **SppCore.gettgd() BDS卫星索引越界**：使用`nav.eph[sat-1]`直接索引对BDS卫星（sat=106-114）失败
  - **修复**：改为线性搜索匹配`.sat`字段
  - 修改文件：`SppCore.java:82-100`

- **RinexParser日志误导**：`nav.n`标注为GPS、`nav.na`标注为BDS，实际分别为总星历数和Almanac数
  - **修复**：更正日志标签为Total和Almanac
  - 修改文件：`RinexParser.java:158-159`

---

## [2.2.1] - 2026-09-22

### Added

- **RTK阶段高级模糊度固定优化（6项）**：借鉴GREAT-PVT/PRIDE PPP-AR，通过RtkConfig独立开关控制，默认全部关闭
  - **逐级模糊度固定（Cascade AR, EWL→WL→NL）**：`enableCascadeAR`，利用多频波长差异逐级约束固定模糊度，提升RTK固定连续性和精度
  - **精细化残差编辑与周跳检测**：`enableResidualEdit`，残差时序迭代跳变检测、伪距-载波一致性校验、弧段完整性筛查
  - **部分模糊度固定（Partial AR）**：`enablePartialAR`，全局Ratio检验失败后剔除劣质模糊度维度，保留可信子集完成固定
  - **Bootstrapping成功率联合判据**：`enableBootstrapping`，作为Ratio的互补判据，与Ratio联合判定模糊度固定可靠性
  - **BDS卫星码偏差改正（Wanninger模型）**：`enableBdsCodeBias`，针对BDS GEO/IGSO/MEO卫星码偏差改正，提升BDS固定成功率
  - **参数类型级自适应过程噪声**：`enableParamTypeNoise`，按物理参数区分噪声模型（ZTD随机游走/钟差白噪声/电离层随机游走）
  - 实现类：`RtkOptimizationsCascadeAR`/`RtkOptimizationsResEdit`/`RtkOptimizationsPartialAR`/`RtkOptimizationsBootstrap`/`RtkOptimizationsBdsBias`
  - 侵入方式：RtkCore中8个`if(cfg.enableXxx)`分支，默认关闭时与原版RTKLIB行为完全一致
  - 27个新增单元测试全部通过

- **BDS/Galileo频率常量**：`Constants.FREQB1I`/`FREQB2I`/`FREQB3I`/`FREQE1`/`FREQE5a`，供级联AR和BDS码偏差使用

- **RtkConfig新增20+个开关和参数**：级联AR各级Ratio阈值、残差编辑阈值、部分AR参数、Bootstrapping成功率阈值、BDS码偏差高度角阈值、过程噪声参数等

- **Rtk新增诊断变量**：`cascadeArFixLevel`/`cascadeArLastRatio`/`diagCascadeArEwlFixCount`等，用于优化效果评估

---

## [2.2.0] - 2026-09-22

### Added

- **轨道模块（org.rtklib.java.orbit）**：完整的卫星轨道计算功能
  - SGP4/SDP4轨道传播器：逐行移植自python-sgp4，支持近地/深空/同步轨道
  - TLE解析器：支持文件读取和两行字符串解析
  - 轨道六根数转换：rv2coe/coe2rv，移植自Orekit 12的KeplerianParametersConverter
  - 开普勒方程求解：Halley修正牛顿法，2次迭代达机器精度
  - 二体轨道传播器：KeplerPropagator，仅推进平近点角
  - 统一转换入口：TleConverter（TLE↔六根数、TLE↔状态向量）
  - 数据容器：OrbitalElements、StateVector、Frame（TEME/EME2000/ITRF）
  - 验证：Vallado标准14用例全通过，86颗真实TLE卫星0失败，rv2coe往返误差<0.001km
  - 文档：`ORBIT_MODULE_REFERENCE.md`

---

## [2.1.1] - 2026-09-19

### Added

- **基站稳定性监测（BaseStationMonitor）**：独立工具类，检测基站天线位移并提醒用户
  - 一级检测：60min窗口SPP中位数 vs 自学习参考基准，偏移>10m触发突变告警
  - 二级检测：历史中位数环形缓冲区趋势分析，漂移>5m触发漂移告警
  - 4种数据输入：`onBaseObs`(Obsd数组)、`onRtcmData`(RTCM字节)、`onRtcmFile`(RTCM文件)、`onRinexFile`(RINEX文件)
  - 回调接口`BaseMonitorCallback`：4个default方法（onSppResult/onWindowResult/onBaseMovement/onBaseDrift）
  - 配置类`BaseMonitorConfig`：10个参数（窗口时长、突变阈值、漂移阈值、缓冲区大小等）
  - 完全独立：不绑定RtkProcessor/RtkConfig/Rtk，1基站1实例，多基线共享不重复
  - 实测验证：基站连续6小时，窗口偏移0.00~3.22m，10m阈值下无误报

- **RTK参数敏感性分析**：量化8个PrcOpt参数对FIX率的影响幅度
  - 影响排序：频点数(+68.7%) > 对流层模型(+40.6%) > AR锁定计数(+56.2%) > 定位模式(+22.1%) > 电离层模型(-76.6%) > AR ratio阈值(-14.9%) > AR最小FIX计数(-8.8%) > 潮汐/PCV(0%)
  - 不同站点类型推荐配置（短基线/中长基线/动态/差数据）
  - 文档：`RTK_Parameter_Sensitivity.md`

- **SNR中位数计算顺序修复**：先赋值SNR再计算中位数（原先顺序反，导致读取上一历元值）

- **P0 基线质量加权**：`BaselineEntry.qualityFactor`，根据ratio/numSat/age/DOP对低质量FIX基线降权
  - `BaselineEntry.computeQualityFactor(SolData)`：综合质量因子计算（0.01~1.0）
  - `CovAssembler.assembleGlobalR(epoch, qualityWeight)`：R矩阵对角块按qualityFactor缩放
  - `GnssBaselineAdjust.adjust(epoch, qualityWeight)`：启用质量加权的平差重载
  - 效果：σ₀更稳健，减少假固定对平差结果的污染

- **P1 逐卫星诊断数据提取**：扩展 `SatObsData`，按 `PrcOpt.diagMask` 位掩码控制输出
  - `DIAG_SAT_RESIDUAL`(1)：逐卫星伪距残差resp、载波残差resc
  - `DIAG_SAT_AMBIGUITY`(2)：逐卫星模糊度浮点值amb、标准差stdA
  - `DIAG_SAT_CYCLESLIP`(4)：逐卫星周跳标志slip、GF/MW组合值、拒绝计数rejc
  - `SatObsData.fromSsat(ssat, nf, diagMask)`：按掩码提取诊断字段
  - 未启用时字段为NaN/0，零开销向后兼容

- **P3 H矩阵位置分量提取**：`Sol.hPos`（3×3等效设计矩阵）
  - `DIAG_HPOS`(8)：从relpos()中H[:,0:3]和R计算hPos = (HᵀWH)⁻¹HᵀW
  - `CovAssembler.assembleDesignMatrix(epoch)`：使用各基线hPos替代I₃
  - 无hPos时退化为I₃，与修改前行为一致

- **新息向量摘要提取**：`Sol.innovRms`/`innovMax`/`ddObsCount`
  - `DIAG_INNOVATION`(16)：新息RMS = √(vᵀR⁻¹v/nv)，最大标准化新息，双差观测数

- **PrcOpt新增字段**：`diagMask`（诊断掩码，默认0）、`qualityWeight`（质量加权开关，默认true）

### Changed

- `SolData` 新增 `hPos`/`innovRms`/`innovMax`/`ddObsCount` 字段，新增 `diagMask` 构造函数
- `RtkProcessor` 所有 `new SolData` 调用传入 `opt.diagMask`
- `AdjustRealDataTest.buildBaselineEntry()` 计算qualityFactor，平差启用qualityWeight

### Verified

- rtklib-core 编译通过 ✅
- rtklib-adjust 编译通过 ✅
- GnssBaselineAdjustTest 8 tests passed ✅
- AdjustRealDataTest 8 tests passed ✅
- diagMask=0 时行为与修改前完全一致 ✅

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