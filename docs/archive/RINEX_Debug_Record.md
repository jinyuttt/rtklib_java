# RINEX 调试记录文档

## 1. 概述

本文档记录 rtklib_java 项目中 RINEX 相关模块开发过程中遇到的问题、调试过程和解决方案，供后续开发参考。

涉及模块：
- `RinexParser` — RINEX 3.x 文件解析
- `RinexObsWriter` — RINEX 3.x 观测文件写入
- `RinexNavWriter` — RINEX 3.x 导航文件写入
- `RtcmToRinexConverter` — RTCM 数据转 RINEX
- `RtcmFileToRinexConverter` — RTCM 文件转 RINEX
- `RinexSppProcessor` — RINEX 文件 SPP 定位

---

## 2. 调试记录

### 2.1 RINEX 2.X 支持移除

**问题**: 项目需要完全移除 RINEX 2.X 支持，仅保留 3.05/3.06。

**修改内容**:
- `RinexObsWriter`: 删除 `writeObsTypesV2()` 和所有 V2 写入方法，构造函数添加版本检查 `version < 3.0` 抛异常
- `RinexNavWriter`: 删除所有 V2 写入方法，构造函数添加版本检查
- `RinexParser`: 删除 V2 解析分支

**关键代码**:
```java
// RinexObsWriter.java / RinexNavWriter.java 构造函数
if (version < 3.0) {
    throw new IllegalArgumentException("RINEX 2.x is not supported. Use version 3.05 or 3.06.");
}
```

**教训**: 移除旧版本支持时，需要在入口处（构造函数）明确拒绝，而非静默忽略，避免运行时出现不可预期的行为。

---

### 2.2 观测文件历元头格式错误导致解析失败

**问题**: `RinexParser.readObsEpoch()` 解析历元头时，`nSat = Integer.parseInt(firstLine.substring(32, 35).trim())` 抛出 `NumberFormatException`，输入为空字符串。

**根因**: `RinexObsWriter` 写入历元头时使用了错误的格式字符串 `%011.7f`，导致秒数字段占 11 位（含前导零），将 flag 和 nSat 字段向右推移，解析时位置偏移。

**RINEX 3.x 历元头标准格式**:
```
>  YYYY MM DD HH MM SS.ffffff  d n  ss
|  |   |  |  |  |  |         |  |  |
0  2   7  10 13 16 19        29 32 35
```
- 位置 29: 历元标志 (flag)
- 位置 32-35: 卫星数 (nSat)

**修复**:
```java
// 修复前（错误）
String epochHeader = String.format("> %4d %2d %2d %2d %2d %011.7f  %d %3d",
    year, month, day, hour, minute, sec, flag, nSat);

// 修复后（正确）
String epochHeader = String.format("> %4d %2d %2d %2d %2d %10.7f  %d %3d",
    year, month, day, hour, minute, sec, flag, nSat);
```

**差异**: `%011.7f` 输出如 `00012.3456789`（11位含前导零），`%10.7f` 输出如 ` 12.3456789`（10位右对齐）。前者导致字段整体右移 1 字符，flag 和 nSat 位置错位。

**教训**: RINEX 格式对字段位置有严格要求，格式字符串必须精确匹配标准。调试时应先用 RTKLIB C 版本生成标准 RINEX 文件，逐字节对比。

---

### 2.3 UTF-8 BOM 导致编译错误

**问题**: Java 编译报错 `非法字符: '\ufeff'`。

**根因**: 部分源文件被编辑器保存为 UTF-8 with BOM 格式，Java 编译器不识别 BOM 标记。

**受影响文件**: `RinexParser.java`, `RinexObsWriter.java`, `RinexNavWriter.java`

**修复**: 使用 `UTF8Encoding(false)` 重写文件，去除 BOM：
```powershell
$utf8NoBom = New-Object System.Text.UTF8Encoding $false
[System.IO.File]::WriteAllLines($filePath, $lines, $utf8NoBom)
```

**教训**: Java 项目应统一使用 UTF-8 without BOM 编码。可在 `.editorconfig` 中配置：
```
[*.java]
charset = utf-8
```

---

### 2.4 GLONASS 星历解析中 `tod` 变量未定义

**问题**: `RinexParser.decodeGeph()` 方法中 `tod` 变量未声明，导致 `NullPointerException`。

**修复**: 在 `decodeGeph()` 方法中添加 `tod` 变量声明：
```java
double tod = data[2] % 86400.0;
```

**教训**: 从 C 代码移植时，需仔细检查每个变量的声明和初始化，C 语言允许隐式声明而 Java 不允许。

---

### 2.5 Windows 路径分隔符问题

**问题**: `RinexConversionTest` 中 `FileNotFoundException`，错误信息为"文件名、目录名或卷标语法不正确"。

**根因**: `RtcmToRinexConverter` 中使用 `/` 拼接路径：
```java
String obsFile = outputDir + "/" + stationName + ".obs";
```
在 Windows 上，当 `outputDir` 以 `\` 结尾或包含空格时，混合使用 `/` 和 `\` 导致路径无效。

**修复**: 使用 `Paths.get()` 替代字符串拼接：
```java
String obsFile = java.nio.file.Paths.get(outputDir, stationName + ".obs").toString();
```

**教训**: 永远不要手动拼接文件路径，始终使用 `Path`/`Paths` API。

---

### 2.6 测试中硬编码路径问题

**问题**: `RinexConversionTest.testCompareObsWithRtklibC()` 使用硬编码路径 `" D:\\code\\rtklib_java\\temp_compare\\java"`，路径前有多余空格。

**修复**:
1. 将 `outputDir` 替换为 `tempDir.toString()`（JUnit 5 `@TempDir`）
2. 为 `testRoverRtcmToRinex()` 添加 `@TempDir` 参数
3. 将 RINEX 版本从 3.02 改为 3.05

**教训**: 测试代码不应依赖硬编码路径，使用 `@TempDir` 保证跨平台和隔离性。

---

### 2.7 BDS 时间系统转换（BDT ↔ GPST）

**问题**: 北斗卫星的观测时间使用 BDT（北斗时），与 GPST（GPS 时）存在 14 秒偏移。

**关键关系**:
```
BDT = GPST - 14s
```

**影响环节**:
1. **RTCM 解码**: MSM 消息中的 BDS 观测时间以 BDT 给出，需转换为 GPST
2. **RINEX 写入**: RINEX 3.x 观测文件头部的 `TIME SYSTEM FIRST` 标记决定时间系统
3. **星历计算**: `EphModel.eph2pos()` 中 BDS 星历的 `toe` 和 `toc` 为 BDT，需在计算前转换

**处理方式**: 在 `Rtcm` 解码 MSM 消息时，对 BDS 卫星的时间加 14 秒转换为 GPST，确保后续所有模块统一使用 GPST。

---

### 2.8 NFREQ 从 3 扩展到 6

**问题**: BDS 有 6 个频点（B1I, B1C, B2I, B2a, B3I, B2b），原 `NFREQ=3` 不足以支持。

**修改**:
- `Constants.NFREQ`: 3 → 6
- `Obsd.P[]`, `Obsd.L[]`, `Obsd.SNR[]`, `Obsd.code[]`: 长度从 NFREQ 调整
- `Ssat.snrRover[]`, `Ssat.snrBase[]`: 长度调整
- `Eph.tgd[]`: 长度从 2 → 6，支持 BDS 6 频点 TGD

**影响**: `SppCore.prange()` 中 BDS TGD 索引映射：
- B1I (CODE_L2I): `tgd[0]`
- B1Cp (CODE_L1P): `tgd[2]`
- B1Cd: `tgd[2] + tgd[4]`

---

### 2.9 RinexParser 历元解析的 BOM 和空行问题

**问题**: `readObsEpoch()` 解析观测文件时，首行可能包含 BOM 或空行，导致解析失败。

**修复**: 在 `parseObs()` 方法中，读取首行后去除 BOM 标记：
```java
String line = reader.readLine();
if (line != null && line.startsWith("\uFEFF")) {
    line = line.substring(1);
}
```

---

### 2.10 RinexSppProcessor 缺少 import 导致编译失败

**问题**: `RinexSppProcessor.java` 缺少 `CoordTransform`, `PosHandler`, `TimeSystem`, `Logger` 的 import。

**修复**: 添加完整 import 列表：
```java
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.pntpos.PosHandler;
import org.rtklib.java.time.TimeSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
```

---

### 2.11 测试类 Ssat import 错误

**问题**: `RinexSppProcessorTest.java` 中 `Ssat` 从 `org.rtklib.java.pntpos` 导入，但实际位于 `org.rtklib.java.data`。

**修复**:
```java
// 错误
import org.rtklib.java.pntpos.Ssat;
// 正确
import org.rtklib.java.data.Ssat;
```

---

## 3. RINEX 3.x 格式要点

### 3.1 观测文件结构

```
     3.05           O   M                   RINEX VERSION / TYPE
...                                     ─┐
C    20250101    000000     GPS         │  头部
...                                     ─┘
> 2025 01 01 00 00  0.000000  0 11      ─┐
C01  22249106.018   ...                  │  历元数据
C02  24451758.438   ...                  │
...                                     ─┘
```

### 3.2 导航文件结构

```
     3.05           N   M                   RINEX VERSION / TYPE
...
C01  2025 01 01 00 00 00 -2.123456789012D-04 ...
     1.234567890123D-03  ...
...
```

### 3.3 关键格式规则

1. **历元头**: `>` 开头，秒数 `%10.7f`，flag 占 1 位，nSat 占 3 位
2. **卫星标识**: 系统字符+PRN（如 `C01`, `G10`）
3. **观测值**: 每行 5 个观测值，每个 14 字符宽
4. **导航星历**: 每行 4 个参数，D 格式科学计数法
5. **时间系统**: 默认 GPS，BDS 观测文件标注 `BDT` 时需转换

---

## 4. 调试方法总结

### 4.1 RINEX 文件对比法

使用 RTKLIB C 版本（`rnx2rtcm`/`convbin`）生成标准 RINEX 文件，与 Java 版本输出逐行对比。

```bash
# C 版本生成参考文件
convbin -r rtcm3 -v 3.05 -o ref.obs -n ref.nav 1.rtcm3

# 对比
diff ref.obs java.obs
diff ref.nav java.nav
```

### 4.2 SPP 结果验证法

使用 RTKLIB C 版本（`rnx2rtcm` + `rtkrcv`）对同一数据执行 SPP，对比定位结果。

```bash
# C 版本 SPP
rtkrcv -in obs=ref.obs nav=ref.nav -out result_c.pos

# Java 版本 SPP
RinexSppProcessor.processRinex("java.obs", "java.nav")
```

### 4.3 逐步验证法

1. 先验证 RTCM 解码正确性（消息类型、卫星数、观测值）
2. 再验证 RINEX 写入正确性（格式、字段位置、数值精度）
3. 再验证 RINEX 解析正确性（读回数据与写入数据一致）
4. 最后验证 SPP 定位正确性（与 C 版本结果对比）

---

## 3.5 RTK 致命坑排查记录（2026-06-27）

### 3.5.1 🚨 卫星位置计算：`satpos` vs `satposs`

**问题**: `RtkCore.relpos()` 中使用 `EphModel.satpos()` 计算卫星位置，该函数不包含信号传播时间迭代。

**根因**: `satpos()` 仅根据给定时刻直接计算卫星位置，而 `satposs()` 才是正确的入口：
1. 先减去 `P/c` 得到信号发射时刻
2. 迭代计算钟差修正
3. 再计算精确的卫星位置

**影响**: 卫星位置偏差约 20-50m（地球自转+钟差），RTK 差分残差巨大。

**修复**: 将 `EphModel.satpos()` 替换为 `EphModel.satposs()`。

**教训**: SPP 中已正确使用 `satposs`，RTK 移植时遗漏了这一关键步骤。**信号传播时间迭代和地球自转修正是卫星位置计算的必要步骤，绝不能省略。**

### 3.5.2 🚨 载波相位波长：`WAVELENGTHS[f]` 索引错误

**问题**: `ddres()` 中使用 `Constants.WAVELENGTHS[f]` 获取载波波长，其中 `f` 是频率索引（0,1,2...）。

**根因**: `WAVELENGTHS` 数组按 GPS L1/L2/L5/L6/E5b/E5ab 顺序排列，但不同卫星系统的频率映射不同：
- BDS B1I (f=0) 对应 1561.098MHz，但 `WAVELENGTHS[0]` 是 L1 (1575.42MHz) 的波长
- 差异约 0.019m，对模糊度固定是致命的

**修复**: 使用 `SatUtils.sat2freq(sat, code, nav)` 获取实际频率，再计算 `lam = CLIGHT / freq`。

```java
// 修复前（错误）
double lam = Constants.WAVELENGTHS[f];

// 修复后（正确）
double freq = SatUtils.sat2freq(sat[i], obs[iu[i]].code[f], nav);
double lam = Constants.CLIGHT / freq;
```

**教训**: **绝不能用频率索引代替实际频率**。不同 GNSS 系统的频率映射完全不同，必须通过 `sat2freq` 获取实际频率。

### 3.5.3 🚨 载波相位电离层改正符号

**问题**: `ddres()` 中载波相位的电离层改正符号与伪距相同。

**根因**: 电离层对伪距产生延迟（+），对载波相位产生超前（-）。单差观测方程：
- 伪距: `ΔP = Δr + Δion + Δtrop`
- 载波: `ΔL·λ = Δr - Δion + Δtrop - ΔN·λ`

**修复**: 载波相位残差中电离层改正取负号。

```java
// 伪距单差残差
v[nv] = (P1 - P2) - (r_f - r_r + x[3]) + (ion_f - ion_r) - (trop_f - trop_r);

// 载波单差残差（注意电离层符号为负）
v[nv] = (L1 - L2) * lam - (r_f - r_r + x[3]) - (ion_f - ion_r) - (trop_f - trop_r) - x[6+i*nf+f] * lam;
```

**教训**: **载波相位电离层改正符号必须与伪距相反**。这是 RTK 最容易犯的错误之一，直接导致模糊度无法固定。

### 3.5.4 🚨 Rtk.nx 初始化为 0

**问题**: `Rtk` 构造函数中 `nx=0`，导致 `relpos()` 中 `x[]` 和 `P[]` 数组大小为 0。

**根因**: RTK 状态向量维度取决于当前历元的共用卫星数和频率数，无法在构造时确定。

**修复**: 在 `relpos()` 中根据 `ns` 和 `nf` 动态计算 `nx = 6 + ns * nf`，当 `rtk.nx != nx` 时重新初始化。

### 3.5.5 🚨 基准站坐标 `rb` 未设置

**问题**: `RtkTest` 中创建 `Rtk` 对象后未设置 `rtk.rb[]`（基准站坐标），默认全为 0。

**根因**: RTK 差分定位需要基准站的精确坐标。`ddres()` 中 `rr_r = rb`，如果 `rb` 为 0，几何距离完全错误。

**修复**: 在测试中先对基准站做 SPP 定位获取近似坐标，然后设置 `rtk.rb`。

```java
double[] basePos = computeBasePosition(baseEpochs, nav);
System.arraycopy(basePos, 0, rtk.rb, 0, 3);
```

### 3.5.6 🚨 RtkPos.rtkpos() 是占位符

**问题**: `RtkPos.rtkpos()` 直接返回 0，未调用 `RtkCore.rtkpos()`。

**修复**: 改为委托调用 `return RtkCore.rtkpos(rtk, obs, n, nav);`

### 3.5.7 调试打印残留

**问题**: `RtklibCommon.lsq()` 和 `EphModel.eph2pos()` 中残留 `System.out.println` 调试输出。

**修复**: 移除所有 `System.out.println`，正式代码应使用 SLF4J Logger。

---

## 5. 已知限制

1. **仅支持 RINEX 3.05/3.06**: 2.x 版本已在代码入口拒绝
2. **BDS GEO 卫星**: 5° 旋转矩阵处理，与 RTKLIB C 版本一致
3. **GLONASS**: 使用数值积分法计算卫星位置，精度受积分步长影响
4. **电离层模型**: 仅实现 Klobuchar，未实现 NeQuick-G（Galileo）
5. **对流层模型**: 仅实现 Saastamoinen，未实现 GMF/VMF