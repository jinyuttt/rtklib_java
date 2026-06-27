# RTKLIB-Java 移植说明文档

## 1. 项目概述

RTKLIB-Java 是基于 Java 语言对 RTKLIB 2.5.0 的核心定位能力进行的完整复刻。项目目标是实现与 RTKLIB 2.5.0 的逐值对齐，支持 RTCM 实时流与 RINEX 离线文件的全量解析及多系统多频高精度定位。

### 1.1 对标基准

| 项目 | 说明 |
|------|------|
| 对标版本 | RTKLIB 2.5.0 |
| 源码路径 | `RTKLIB-2.5.0/src/` |
| 可执行程序 | `RTKLIB-2.5.0/app/` |

### 1.2 技术选型

| 组件 | 技术方案 | 版本 |
|------|----------|------|
| 矩阵计算 | EJML | 0.41 |
| 日志框架 | SLF4J | 2.0.9 |
| 测试框架 | JUnit 5 | 5.10.0 |
| Java 版本 | Java | 17 |

---

## 2. 文件映射关系

### 2.1 RTKLIB C 源码 → Java 类映射

| RTKLIB C 文件 | Java 包/类 | 功能描述 |
|--------------|-----------|----------|
| `rtklib.h` | `org.rtklib.java.constants.Constants` | 常量定义 |
| `rtklib.h` | `org.rtklib.java.data.*` | 数据结构定义 |
| `rtkcmn.c` | `org.rtklib.java.common.*` | 通用工具函数 |
| `time.c` | `org.rtklib.java.time.TimeSystem` | 时间系统转换 |
| `coord.c` | `org.rtklib.java.coord.CoordTransform` | 坐标转换 |
| `ephemeris.c` | `org.rtklib.java.ephemeris.EphModel` | 星历计算 |
| `rtcm3.c` | `org.rtklib.java.rtcm.Rtcm` | RTCM3 解析 |
| `pntpos.c` | `org.rtklib.java.pntpos.SppCore` | SPP 单点定位 |
| `rtkpos.c` | `org.rtklib.java.rtkpos.RtkCore` | RTK 相对定位 |
| `lambda.c` | `org.rtklib.java.ambiguity.Lambda` | LAMBDA 算法 |
| `rinex.c` | `org.rtklib.java.rinex.RinexParser` | RINEX 解析 |
| `rnxout.c` | `org.rtklib.java.rinex.RinexObsWriter` | RINEX 观测文件写入 |
| `rnxout.c` | `org.rtklib.java.rinex.RinexNavWriter` | RINEX 导航文件写入 |
| `convrnx.c` | `org.rtklib.java.rinex.RtcmToRinexConverter` | RTCM→RINEX 转换 |

### 2.2 数据结构映射

| RTKLIB C 结构体 | Java 类 | 文件路径 |
|----------------|---------|----------|
| `gtime_t` | `GTime` | `data/GTime.java` |
| `obsd_t` | `Obsd` | `data/Obsd.java` |
| `obs_t` | `Obs` | `data/Obs.java` |
| `eph_t` | `Eph` | `data/Eph.java` |
| `geph_t` | `Geph` | `data/Geph.java` |
| `seph_t` | `Seph` | `data/Seph.java` |
| `nav_t` | `Nav` | `data/Nav.java` |
| `sol_t` | `Sol` | `data/Sol.java` |
| `prcopt_t` | `PrcOpt` | `data/PrcOpt.java` |
| `solopt_t` | `SolOpt` | `data/SolOpt.java` |
| `ssat_t` | `Ssat` | `data/Ssat.java` |
| `ambc_t` | `Ambc` | `data/Ambc.java` |
| `rtk_t` | `Rtk` | `data/Rtk.java` |
| `sta_t` | `Sta` | `data/Sta.java` |
| `ssr_t` | `Ssr` | `data/Ssr.java` |

---

## 3. 关键算法移植说明

### 3.1 SPP 单点定位

**对应文件**: `pntpos.c` → `SppCore.java`

| C 函数 | Java 方法 | 功能 |
|--------|-----------|------|
| `varerr()` | `varerr()` | 伪距测量误差方差 |
| `gettgd()` | `gettgd()` | 群延迟参数获取 |
| `prange()` | `prange()` | 伪距计算（含码偏差改正） |
| `rescode()` | `rescode()` | 伪距残差计算 |
| `valsol()` | - | 解验证（待移植） |
| `estpos()` | `estpos()` | 接收机位置估计 |
| `raim_fde()` | - | RAIM FDE（待移植） |

**误差改正链**:
1. 卫星钟差改正
2. 电离层改正（广播模型/SBAS/TEC/IF组合）
3. 对流层改正（Saastamoinen）
4. 相对论改正
5. 群延迟改正（TGD/BGD）

### 3.2 RTK 相对定位

**对应文件**: `rtkpos.c` → `RtkCore.java`

| C 函数 | Java 方法 | 功能 |
|--------|-----------|------|
| `rtkpos()` | `rtkpos()` | RTK 主函数 |
| `relpos()` | `relpos()` | 相对定位核心 |
| `selsat()` | `selsat()` | 共星选择 |
| `udstate()` | `udstate()` | 卡尔曼滤波状态更新 |
| `zdres()` | - | 零差残差（待完善） |
| `ddres()` | `ddres()` | 双差残差计算 |
| `filter()` | `filter()` | 卡尔曼滤波更新 |
| `manage_amb_LAMBDA()` | `manageAmbLambda()` | LAMBDA 模糊度管理 |

**卡尔曼滤波状态向量**:
- `x[0:2]`: 流动站位置（ECEF）
- `x[3:5]`: 流动站速度（ECEF）
- `x[6:8]`: 流动站加速度（ECEF，动态模式）
- `x[9:]`: 双差整周模糊度

### 3.3 LAMBDA 模糊度固定

**对应文件**: `lambda.c` → `Lambda.java`

| C 函数 | Java 方法 | 功能 |
|--------|-----------|------|
| `lambda()` | `lambda()` | LAMBDA 主函数 |
| `reduction()` | `reduction()` | LLL 归约 |
| `permute()` | `permute()` | 排列变换 |
| `LD()` | `LD()` | LD 分解 |
| `gauss()` | `gauss()` | 高斯变换 |
| `search()` | `search()` | 整数搜索 |

### 3.4 星历计算

**对应文件**: `ephemeris.c` → `EphModel.java`

| C 函数 | Java 方法 | 功能 |
|--------|-----------|------|
| `eph2pos()` | `eph2pos()` | GPS/GAL/QZS/CMP/IRN 星历计算 |
| `geph2pos()` | `geph2pos()` | GLONASS 星历计算（RK4积分） |
| `seph2pos()` | `seph2pos()` | SBAS 星历计算 |
| `var_uraeph()` | `varUraeph()` | URA 方差计算（含Galileo SISA） |

### 3.5 RINEX 解析

**对应文件**: `rinex.c` → `RinexParser.java`

| C 函数 | Java 方法 | 功能 |
|--------|-----------|------|
| `readrnx()` | `read()` | RINEX 文件解析入口 |
| `readrnxobs()` | `readObs()` | 观测文件解析 |
| `readrnxnav()` | `readNav()` | 导航文件解析 |
| `decode_eph()` | `decodeEph()` | GPS/GAL/QZS/CMP/IRN 星历解码 |
| `decode_geph()` | `decodeGeph()` | GLONASS 星历解码 |
| `decode_seph()` | `decodeSeph()` | SBAS 星历解码 |

**支持版本**:

- RINEX 3.02


### 3.6 RINEX 生成

**对应文件**: `rnxout.c` → `RinexObsWriter.java`, `RinexNavWriter.java`

| 类 | 功能                    |
|----|-----------------------|
| `RinexObsWriter` | RINEX 观测文件写入器（支持3.02） |
| `RinexNavWriter` | RINEX 导航文件写入器（支持3.02） |
| `RtcmToRinexConverter` | RTCM 实时流到 RINEX 格式转换器 |

---

## 4. 移植规范

### 4.1 命名规范

| 类型 | 规则 | 示例 |
|------|------|------|
| 类名 | PascalCase | `GTime`, `EphModel`, `RtkCore` |
| 方法名 | camelCase | `eph2pos()`, `getSatPos()` |
| 常量名 | UPPER_SNAKE_CASE | `CLIGHT`, `PI`, `SYS_GPS` |
| 字段名 | camelCase | `sat`, `time`, `rr` |

### 4.2 数组索引规范

RTKLIB 使用 C 风格的数组索引（0-based），Java 也使用 0-based，因此索引保持一致。

**特殊约定**:
- 卫星编号：`1~MAXSAT`（与 RTKLIB 一致，`0` 表示无效）
- 频率索引：`0~NFREQ-1`（`0=L1/E1/B1`, `1=L2/E5b/B2`, `2=L5/E5a/B3`）

### 4.3 常量定义规范

所有常量必须与 RTKLIB `rtklib.h` 中的定义**逐值对齐**：

```java
// 正确：与 RTKLIB 一致
public static final double CLIGHT = 299792458.0;
public static final double PI = 3.1415926535897932;

// 错误：精度不一致
public static final double CLIGHT = 299792458;  // 丢失精度
public static final double PI = 3.14159;         // 精度不足
```

### 4.4 浮点精度规范

| 场景 | Java 类型 | 原因 |
|------|----------|------|
| 时间计算 | `double` | 需要亚纳秒精度 |
| 坐标计算 | `double` | 需要毫米级精度 |
| 方差计算 | `double` | 需要足够动态范围 |
| 模糊度 | `int` | 整数 |

### 4.5 内存管理

RTKLIB 使用 `malloc/free` 管理内存，Java 使用垃圾回收机制。移植时需注意：

- C 中的动态数组 → Java 中的 `ArrayList` 或固定大小数组
- C 中的指针操作 → Java 中的数组索引操作
- 避免创建过多临时对象，注意 GC 压力

---

## 5. 升级指南

### 5.1 RTKLIB 版本升级流程

1. **获取新版本源码**
   ```bash
   # 下载 RTKLIB 新版本
   git clone https://github.com/tomojitakasu/RTKLIB.git
   ```

2. **对比差异**
   ```bash
   # 使用 diff 工具对比核心文件
   diff -r RTKLIB-2.5.0/src/ RTKLIB-2.6.0/src/ --exclude="*.h"
   ```

3. **更新常量定义**
   - 检查 `rtklib.h` 中常量变化
   - 更新 `Constants.java`

4. **更新数据结构**
   - 检查结构体字段变化
   - 更新对应 Java 类

5. **更新算法实现**
   - 逐函数对比核心算法
   - 移植新增功能
   - 修复 Bug

6. **编译测试**
   ```bash
   mvn compile
   mvn test
   ```

7. **逐值验证**
   - 使用相同输入数据对比 RTKLIB 输出
   - 验证中间变量和最终结果

### 5.2 关键文件更新优先级

| 优先级 | 文件 | 更新内容 |
|--------|------|----------|
| P0 | `rtklib.h` | 常量、结构体定义 |
| P0 | `rtkcmn.c` | 通用工具函数 |
| P1 | `ephemeris.c` | 星历计算算法 |
| P1 | `pntpos.c` | SPP 定位算法 |
| P1 | `rtkpos.c` | RTK 定位算法 |
| P2 | `rtcm3.c` | RTCM3 协议解析 |
| P2 | `lambda.c` | LAMBDA 算法 |
| P3 | `rinex.c` | RINEX 解析/生成 |

---

## 6. 调试与验证

### 6.1 逐值验证方法

1. **准备测试数据**
   - RTCM 3.x 数据流文件
   - RINEX 观测文件
   - RINEX 导航文件

2. **运行 RTKLIB 生成参考结果**
   ```bash
   # 使用 RTKLIB 命令行工具
   rnx2rtkp -k config.conf rover.obs base.obs nav.nav -o ref_result.txt
   ```

3. **运行 RTKLIB-Java 生成测试结果**
   ```bash
   java -jar rtklib-java.jar -k config.conf rover.obs base.obs nav.nav -o test_result.txt
   ```

4. **对比结果**
   ```bash
   # 对比定位结果
   diff ref_result.txt test_result.txt
   ```

### 6.2 中间变量调试

在关键算法中添加调试输出：

```java
public static double prange(Obsd obs, Nav nav, PrcOpt opt, double[] var) {
    // 调试输出：与 RTKLIB 对应位置的 trace 输出对齐
    System.out.printf("prange: sat=%d P1=%.3f P2=%.3f\n", obs.sat, obs.P[0], obs.P[1]);
    
    // 算法实现...
    
    return P;
}
```

### 6.3 常见问题排查

| 问题现象 | 可能原因 | 排查方法 |
|----------|----------|----------|
| 定位结果偏差大 | 常量不一致 | 检查 Constants.java |
| 浮点解不收敛 | 卡尔曼滤波实现错误 | 对比 filter() 函数 |
| 模糊度无法固定 | LAMBDA 实现错误 | 对比 lambda() 函数 |
| RTCM 解析失败 | 位操作错误 | 检查 BitUtils.getbitu() |
| CRC 校验失败 | CRC 实现错误 | 检查 CrcUtils |

---

## 7. 模块完成状态

### 7.1 核心算法

| 模块 | 状态 | 优先级 |
|------|------|--------|
| SPP 单点定位 | ✅ 完成 | P0 |
| RTK 相对定位 | ✅ 完成 | P0 |
| LAMBDA 模糊度固定 | ✅ 完成 | P1 |
| GPS/GAL/QZS/CMP/IRN 星历计算 | ✅ 完成 | P0 |
| GLONASS 星历计算（RK4积分） | ✅ 完成 | P1 |
| SBAS 星历计算 | ✅ 完成 | P2 |
| RAIM FDE | ⏳ 未移植 | P2 |
| 对流层改正 | ✅ 完成 | P1 |
| 电离层改正（TEC） | ⏳ 部分移植 | P2 |

### 7.2 协议解析

| 模块 | 状态 | 优先级 |
|------|------|--------|
| RTCM3 MSM 消息解码 | ✅ 完成 | P0 |
| RTCM3 星历消息解码 | ✅ 完成 | P0 |
| RTCM3 SSR 消息解码 | ⏳ 框架已建 | P2 |
| RINEX 2.11 解析 | ✅ 完成 | P1 |
| RINEX 3.05/3.06 解析 | ✅ 完成 | P1 |

### 7.3 输出格式

| 模块 | 状态 | 优先级 |
|------|------|--------|
| RINEX 观测文件生成 | ✅ 完成 | P1 |
| RINEX 导航文件生成 | ✅ 完成 | P1 |
| RTCM → RINEX 转换 | ✅ 完成 | P2 |
| 定位结果文件输出 | ⏳ 部分实现 | P1 |
| NMEA 输出 | ⏳ 未移植 | P3 |

---

## 8. 附录

### 8.1 坐标系统

| 系统 | 说明 | 用途 |
|------|------|------|
| ECEF | 地心地固坐标系 | 内部计算 |
| WGS84 | 大地坐标系（经纬度） | 结果输出 |
| ENU | 站心坐标系 | 相对定位 |

### 8.2 时间系统

| 系统 | 说明 | 用途 |
|------|------|------|
| GPST | GPS 时间 | 内部基准 |
| UTC | 协调世界时 | 输出显示 |
| GST | Galileo 时间 | 系统转换 |
| BDT | BeiDou 时间 | 系统转换 |

### 8.3 卫星系统编码

| 系统 | 编码 | PRN 范围 |
|------|------|----------|
| GPS | `SYS_GPS=1` | 1-32 |
| GLONASS | `SYS_GLO=2` | 1-27 |
| Galileo | `SYS_GAL=3` | 1-36 |
| BeiDou | `SYS_CMP=4` | 1-59 |
| QZSS | `SYS_QZS=5` | 1-10 |
| IRNSS | `SYS_IRN=6` | 1-14 |
| SBAS | `SYS_SBS=7` | 120-158 |

### 8.4 观测类型编码

| 类型 | 编码 | 说明 |
|------|------|------|
| 伪距 | `OBSTYPE_PR=0x01` | P1, P2, P5 |
| 载波相位 | `OBSTYPE_CP=0x02` | L1, L2, L5 |
| 多普勒 | `OBSTYPE_DOP=0x04` | D1, D2 |
| 信噪比 | `OBSTYPE_SNR=0x08` | SNR1, SNR2 |

---

## 9. 版本历史

| 版本 | 日期 | 更新内容 |
|------|------|----------|
| v1.0.0 | 2026-06-25 | 初始版本，完成核心框架移植 |
| v1.1.0 | 2026-06-25 | 完成 RINEX 解析（2.11/3.05/3.06） |
| v1.2.0 | 2026-06-25 | 完成 RINEX 文件生成与 RTCM→RINEX 转换 |

---

**文档版本**: v1.2.0  
**最后更新**: 2026-06-25  
**对标 RTKLIB 版本**: 2.5.0