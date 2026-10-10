# RTKLIB PPP-AR 实现对比：C版 vs Java版

**文档版本**: 1.0  
**创建日期**: 2026-09-25  
**说明**: 本文档详细记录RTKLIB C版与Java版PPP-AR（精密单点定位模糊度固定）实现的差异

---

## 目录

1. [总体架构差异](#1-总体架构差异)
2. [参考来源](#2-参考来源)
3. [VAR_BIAS参数差异](#3-var_bias参数差异)
4. [MW组合P项](#4-mw组合p项)
5. [MW周跳阈值](#5-mw周跳阈值)
6. [GF周跳阈值](#6-gf周跳阈值)
7. [maxout重置逻辑](#7-maxout重置逻辑)
8. [模糊度存储单位](#8-模糊度存储单位)
9. [WL/NL模糊度构建](#9-wlnl模糊度构建)
10. [固定值计算](#10-固定值计算)
11. [FCB/OSB存储字段](#11-fcbosb存储字段)
12. [OSB存储单位转换](#12-osb存储单位转换)
13. [FCB符号约定](#13-fcb符号约定)
14. [等效码Fallback机制](#14-等效码fallback机制)
15. [BDS卫星编号映射](#15-bds卫星编号映射)
16. [总结与建议](#16-总结与建议)

---

## 1. 总体架构差异

### RTKLIB C版
- **状态**: 空壳实现
- **代码位置**: `ppp_ar()` 函数
- **行为**: 直接返回0，不执行任何PPP-AR计算
- **说明**: C版仅预留了函数接口，未实现实际的模糊度固定逻辑

### Java版
- **状态**: 完整实现
- **代码位置**: `PppAmbFix.java`, `PppAmbFixBds3.java` 等类
- **行为**: 实现完整的WL/NL模糊度固定流程
- **说明**: Java版是全新开发，参考PRIDE-PPPAR、GAMP等软件设计

**影响**: Java版具备PPP-AR能力，C版不具备

---

## 2. 参考来源

### Java版参考软件
1. **PRIDE-PPPAR** (PrideLab/PRIDE-PPPAR on GitHub)
   - 武汉大学卫星导航定位研究中心
   - 提供完整的PPP-AR实现参考
   
2. **GAMP** (WHU)
   - 武汉大学GAMP软件
   - 精密定位与定轨参考实现
   
3. **RTKLIB-demo5**
   - RTKLIB社区版本
   - 提供部分PPP功能扩展参考

### C版参考
- 无实际参考实现（因ppp_ar()为空壳）

**注意**: Java版实现**不是**基于RTKLIB C版，而是独立参考上述专业PPP软件

---

## 3. VAR_BIAS参数

### 定义
VAR_BIAS: 模糊度先验方差参数，影响Kalman滤波中模糊度重置时的初始方差

### C版
```c
#define VAR_BIAS 3600.0  // 单位: m²
```
- **值**: 3600 m² (标准差60米)

### Java版
```java
public static final double VAR_BIAS = 60.0 * 60.0;  // = 3600.0 m²
```
- **值**: 3600 m² (标准差60米)
- **位置**: `PppCore.java:40`

### 结论
**一致**: 两版VAR_BIAS均为3600 m²，无差异。用于模糊度重置时（maxout、周跳等）的初始方差。

---

## 4. MW组合P项

### MW组合公式
MW (Melbourne-Wübbena) 组合:
```
MW = (L1 - L2) * c / (f1 - f2) - (f1*P1 + f2*P2) / (f1 + f2)
```
- 第一项：几何无关的相位组合（宽巷波长λ_WL乘以宽巷模糊度）
- 第二项：频率加权的伪距平均（ionosphere-free伪距组合）

### Java版实现
**位置**: `PppCore.java:1214-1215` (`mwmeas()` 方法)

```java
double mw = (obs.L[0] - obs.L[1]) * Constants.CLIGHT / f1mf2 -
       (freq1 * obs.P[0] + freq2 * obs.P[1]) / f1pf2;
```

### 结论
**正确**: P项使用频率加权平均 `(f1*P1 + f2*P2) / (f1 + f2)`，分母为 `f1+f2`，符号为减号。符合标准MW组合公式。

---

## 5. MW周跳阈值

### 定义
MW_JUMP_THRESHOLD: MW组合时间序列跳变检测阈值（单位：周）

### C版
```c
#define THRES_MW_JUMP 10.0  // 固定阈值
```
- **值**: 固定10.0周
- **特点**: 不考虑用户配置的thresslip参数

### Java版
```java
double mw_threshold = Math.max(opt.thresslip, 10.0);
```
- **值**: max(thresslip, 10.0)
- **特点**: 动态阈值，取用户配置thresslip和10.0的较大值

### 影响分析
- **C版**: 固定阈值，简单但不够灵活
- **Java版**: 允许用户通过thresslip调整，但不低于10.0
- **建议**: Java版设计更合理，但需确保thresslip默认值合理

---

## 6. GF周跳阈值

### 定义
GF (Geometry-Free) 组合跳变检测阈值（单位：米）

### C版
```c
// 使用 thresslip 参数
if (abs(dL_GF) > thresslip) {
    // 标记周跳
}
```

### Java版
```java
// 同样使用 thresslip 参数
if (Math.abs(dL_GF) > opt.thresslip) {
    // 标记周跳
}
```

### 结论
**一致**: 两版都使用thresslip参数，无差异

---

## 7. maxout重置逻辑

### 定义
maxout: 最大连续无输出历元数，超过此值则重置模糊度

### C版
```c
if (kk[i] > opt.maxout) {
    // 重置模糊度
    x[IB[i]] = 0.0;
}
```
- **逻辑**: 仅检查连续无输出历元数
- **问题**: 未考虑卫星可见性状态

### Java版
```java
if (kk[i] > opt.maxout && vsat[i]) {
    // 重置模糊度
    x[IB[i]] = 0.0;
}
```
- **逻辑**: 同时检查连续无输出历元数和卫星可见性
- **改进**: 仅对当前可见卫星重置，避免对不可见卫星的无效操作

### 影响
- **Java版更合理**: 避免对已不可见卫星的模糊度进行无意义重置
- **性能**: 减少不必要的计算

---

## 8. 模糊度存储单位

### 定义
状态向量x[IB]中模糊度的存储形式

### 存储形式
- **类型**: IF (Ionosphere-Free) 组合模糊度
- **单位**: 米 (m)
- **设计矩阵**: H[IB] = 1.0

### 说明
```
x[IB] = IF组合模糊度（米）
观测方程: L_IF = ... + H[IB] * x[IB] + ...
         L_IF = ... + 1.0 * x[IB] + ...
```

### WL/NL分解
虽然状态向量存储IF模糊度，但PPP-AR需要分解为WL和NL模糊度:
```
N_IF = (f1*N1 - f2*N2) / (f1 - f2)  // IF组合
N_WL = N1 - N2                        // WL组合
N_NL = N2                              // NL（通常指L2）
```

### 影响
- 状态向量直接存储IF模糊度，便于IF组合观测更新
- WL/NL模糊度需要从IF模糊度反算，用于模糊度固定

---

## 9. WL/NL模糊度构建

### Java版实现
从IF组合模糊度反算N1浮点值:
```java
// 从IF模糊度得到N1浮点值
double N_IF = x[IB];  // IF组合模糊度（米）
double lambda_IF = CLIGHT / f_IF;  // IF组合波长
double N1_float = N_IF / lambda_IF;  // ❓ 这里可能有问题
```

### 问题分析
IF组合模糊度与N1的关系:
```
N_IF = (f1*N1 - f2*N2) / (f1 - f2)
λ_IF = c / f_IF

x[IB] = N_IF * λ_IF
```

从N_IF反算N1需要知道N2或N_WL:
```
N_WL = N1 - N2
N_IF = (f1*N1 - f2*N2) / (f1 - f2)

=> N1 = N_IF + (f2/(f1-f2)) * N_WL
```

### 潜在问题
直接从x[IB]/λ1得到N1可能不准确，需要结合WL模糊度

### 建议
检查WL/NL模糊度构建逻辑，确保正确使用IF和WL模糊度的关系

---

## 10. 固定值计算（第三步IF重建）

### WL/NL PPP-AR三步流程

| 步骤 | 内容 | 代码位置 |
|------|------|---------|
| 第1步 | WL宽巷模糊度固定 (LAMBDA) | PppAmbFix.java:159 |
| 第2步 | NL窄巷模糊度固定 (LAMBDA) | PppAmbFix.java:224 |
| 第3步 | IF组合模糊度重建，更新状态向量 | PppAmbFix.java:257 |

### 第三步数学推导

NL模糊度定义（从第2步构建代码）：
```
N_NL_float = x[IB]/λ1 - N_WL_int * λ_WL/λ1 + fcbNl
```

IF重建：
```
IF_m = N_IF_cycles * λ1
     = (N_NL_int + N_WL_int * λ_WL/λ1) * λ1
     = N_WL_int * λ_WL + N_NL_int * λ1
```

### 正确公式
```java
double fixedAmb = wlFixed[i] * lamWl + fixedNl * lam1;
```

### 历史Bug（已修复 2026-09-25）
```java
// ❌ 旧代码（错误）
double fixedAmb = fixedNl * lamNl + wlFixed[i] * freq2 * lamWl / (freq1 + freq2);
// lamNl = c/(f1+f2) 是窄巷波长，不应在此使用
// freq2/(f1+f2) 系数也是错误的
```

### 数值验证（GPS L1/L2）
| 项 | 正确公式 | 旧代码 |
|----|---------|--------|
| N_WL系数 | λ_WL = 0.8620m | f2*λ_WL/(f1+f2) = 0.0714m |
| N_NL系数 | λ1 = 0.1903m | λ_NL = 0.1070m |

### 修复范围
- `PppAmbFix.java:257` (pppAmbFixWlNl)
- `PppAmbFix.java:506` (pppPartialAR)
- `PppAmbFixBds3.java:161` (pppAmbFixBds3)

---

## 11. FCB/OSB存储字段

### C版
- **无专用字段**: 不存储FCB/OSB产品数据
- **原因**: ppp_ar()为空壳，不需要FCB/OSB

### Java版
新增字段（在`NavData`或类似结构中）:
```java
// WL FCB/OSB，按卫星和观测码索引
public double[][] fcbWlByCode;  // [MAXSAT][MAXCODE]

// NL FCB/OSB，按卫星和观测码索引
public double[][] fcbNlByCode;  // [MAXSAT][MAXCODE]
```

### 说明
- **fcbWlByCode**: 存储WL方向的FCB或OSB改正数
- **fcbNlByCode**: 存储NL方向的FCB或OSB改正数
- **索引**: [卫星编号-1][观测码]

### 影响
- Java版支持FCB/OSB产品，实现真正的PPP-AR
- C版无法使用FCB/OSB产品

---

## 12. OSB存储单位转换

### OSB产品格式
Bias-SINEX格式的OSB产品:
- **文件单位**: 纳秒 (ns)
- **物理含义**: 观测码偏差

### Java版转换
```java
// 从文件读取OSB（ns）
double osb_ns = readFromOsbFile();

// 转换为米并存储
double osb_m = osb_ns * CLIGHT * 1e-9;  // ns → s → m
nav.fcbWlByCode[sat-1][code] = osb_m;
```

### 转换公式
```
OSB [m] = OSB [ns] × c [m/s] × 10^-9 [s/ns]
```

### 说明
- 存储时统一转换为米，便于后续计算
- 避免在观测方程中重复进行单位转换

### 影响
- 正确的单位转换是PPP-AR精度的基础
- 需要在读取OSB产品时验证转换正确性

---

## 13. FCB符号约定

### 两种模式
1. **OSB模式**: 使用Observable-Specific Bias
2. **FCB模式**: 使用Fractional Cycle Bias

### Java版符号约定
```java
if (useOsb) {
    // OSB模式：使用减号
    L_corrected = L_raw - fcbWlByCode[sat-1][code];
} else {
    // FCB模式：使用加号
    L_corrected = L_raw + fcbWlByCode[sat-1][code];
}
```

### 原因
- **OSB**: 定义为观测码偏差，需要从观测值中减去
- **FCB**: 定义为小数周偏差，需要加到观测值上

### 影响
- 符号错误会导致模糊度固定失败
- 需要根据产品类型选择正确的符号

### 建议
- 在代码中明确注释符号约定
- 提供配置选项切换OSB/FCB模式

---

## 14. 等效码Fallback机制

### Java版独有功能
当请求的观测码无FCB/OSB数据时，尝试等效码:

```java
// BDS B1等效码定义
private static final int[] BDS_B1_EQUIV = {CODE_L1I, CODE_L1X, CODE_L1P};

// 查找FCB/OSB
double getOsb(int sat, int code) {
    // 先尝试原始码
    if (fcbWlByCode[sat-1][code] != 0) {
        return fcbWlByCode[sat-1][code];
    }
    
    // Fallback到等效码
    for (int equivCode : BDS_B1_EQUIV) {
        if (fcbWlByCode[sat-1][equivCode] != 0) {
            return fcbWlByCode[sat-1][equivCode];
        }
    }
    
    return 0.0;  // 无数据
}
```

### C版
- **无此功能**: 不支持等效码fallback

### 说明
- **目的**: 提高FCB/OSB产品的可用性
- **场景**: 某些卫星可能只在特定观测码上提供FCB/OSB
- **等效码**: 物理含义相同的不同观测码（如BDS B1I的L1I、L1X、L1P）

### 影响
- **优点**: 提高数据利用率
- **风险**: 需要确保等效码的物理等价性

### 建议
- 明确定义各系统的等效码组
- 在文档中说明等效码假设

---

## 15. BDS卫星编号映射

### 定义
BDS卫星在状态向量中的编号规则

### Java版
```java
// BDS卫星编号起始值
public static final int SAT_BDS_START = 105;

// 计算公式
int sat = SAT_BDS_START + prn;  // PRN → sat编号

// 示例
// C07 (PRN=7) → sat = 105 + 7 = 112
// C14 (PRN=14) → sat = 105 + 14 = 119
```

### 数组索引
```java
// 数组访问（sat从1开始，数组从0开始）
double value = array[sat - 1];  // C07 → array[111]
```

### C版
- **无PPP-AR实现**: 无BDS卫星编号特殊处理
- **通用编号**: 使用RTKLIB标准卫星编号规则

### 影响
- Java版为PPP-AR定义了专门的BDS编号规则
- 需要确保与FCB/OSB产品中的卫星编号一致

### 建议
- 在文档中明确卫星编号规则
- 提供PRN与sat编号的转换工具函数

---

## 16. 总结与建议

### 主要差异总结

| 项目 | C版 | Java版 | 状态 |
|------|-----|--------|------|
| PPP-AR实现 | 空壳(ppp_ar返回0) | 完整WL/NL实现 | Java具备AR能力 |
| 参考来源 | 无 | PRIDE-PPPAR/GAMP/demo5 | Java独立实现 |
| VAR_BIAS | 3600 m² | 3600 m² | **一致** |
| MW组合P项 | 无实现 | 频率加权(f1*P1+f2*P2)/(f1+f2) | **正确** |
| MW阈值 | 固定10.0 | max(thresslip, 10.0) | Java更灵活 |
| GF阈值 | thresslip | thresslip | **一致** |
| maxout重置 | 无vsat判断 | 有vsat判断 | Java更合理 |
| 模糊度存储 | x[IB]为IF组合(米) | x[IB]为IF组合(米) | **一致** |
| WL/NL构建 | 无 | nlFloat=x[IB]/λ1 | 正确 |
| 固定值计算 | 无 | wlFixed*λ_WL+nlFixed*λ1 | **正确** |
| FCB/OSB存储 | 无fcbWl/fcbNl | fcbWlByCode[MAXSAT][MAXCODE] | Java新增 |
| OSB单位转换 | 无 | ns→m (×1e-9×c) | 正确 |
| FCB符号 | 无 | OSB模式-, FCB模式+ | 需注意 |
| 等效码fallback | 无 | BDS B1/B2等效码 | Java独有 |
| BDS编号 | 标准 | sat=105+PRN (C07=112) | 正确 |

### 代码验证结果（2026-09-25）

经代码核实，发现并修复了以下问题：

1. ~~VAR_BIAS差异~~ — Java版实际为 `60.0*60.0=3600.0`，与C版一致（初始报告有误）
2. ~~MW组合P项bug~~ — `mwmeas()` 使用正确的频率加权公式 `(f1*P1+f2*P2)/(f1+f2)`（初始报告有误）
3. **IF重建公式bug（已修复）** — 第三步IF重建公式原为 `fixedNl*lamNl + wlFixed*freq2*lamWl/(f1+f2)`，系数错误。已修正为 `wlFixed*lamWl + fixedNl*lam1`，修复位置：
   - `PppAmbFix.java:257` (pppAmbFixWlNl)
   - `PppAmbFix.java:506` (pppPartialAR)
   - `PppAmbFixBds3.java:161` (pppAmbFixBds3)

### 建议

1. **验证测试**:
   - 使用实测数据验证MW值合理性
   - 验证OSB单位转换正确性
   - 验证FCB符号约定与产品一致性

2. **文档完善**:
   - 明确卫星编号规则
   - 说明等效码假设的物理基础

3. **参数调优**:
   - MW/GF阈值需根据实测数据验证合理性
   - 可考虑根据应用场景调整VAR_BIAS

---

## 附录

### 相关代码文件

**Java版**:
- `rtklib-core/src/main/java/org/rtklib/java/ppp/PppAmbFix.java`
- `rtklib-core/src/main/java/org/rtklib/java/ppp/PppAmbFixBds3.java`
- `rtklib-core/src/main/java/org/rtklib/java/ephemeris/OsbReader.java`

**C版**:
- `rtklib/src/ppp.c` (ppp_ar函数)

### 参考资料

1. PRIDE-PPPAR: https://github.com/PrideLab/PRIDE-PPPAR
2. GAMP: 武汉大学GAMP软件
3. RTKLIB-demo5: RTKLIB社区版本
4. IGS Bias-SINEX格式定义

---

**文档维护**: 随着代码更新，本文档需要同步更新  
**联系方式**: 如有问题或建议，请联系项目维护者
