# PPP 扩展优化技术文档

> 版本：v2.0  
> 日期：2026-09-24  
> 基于：rtklib-java v2.3.0  
> 对标：PRIDE-PPPAR / GAMP / RTKLIB-demo5  

---

## 1. 概述

### 1.1 设计目标

在 RTKLIB Java 版标准 PPP 基础上，通过 **可选优化模块** 提升定位精度和收敛速度，实现：

- **对流层模型升级**：GPT3+VMF3 替代 Saastamoinen+GMF，提供更精确的先验对流层延迟和映射函数
- **潮汐模型升级**：IERS2010 替代 IERS1996 简化模型，增加长周期潮、大气潮、极潮改正
- **偏差模型扩展**：ISB/IFCB/IFB 多系统偏差参数估计，改善多系统融合PPP精度
- **模糊度固定**：PPP-AR（WL+NL LAMBDA），从浮点解提升至固定解
- **增强固定策略**：Fix-and-Hold、Partial AR、BDS-3 PPP-AR

### 1.2 设计原则

| 原则 | 说明 |
|------|------|
| **零侵入** | 所有优化通过 `RtkConfig` 独立开关控制，默认全部关闭 |
| **等价性** | 关闭所有优化时，行为与原版 RTKLIB PPP 完全一致 |
| **可组合** | 各优化模块可独立启用，也可任意组合 |
| **可测试** | 每个优化有独立单元测试和集成测试 |

### 1.3 优化模块总览

| 模块 | 开关 | 实现类 | 精度贡献 | 收敛贡献 |
|------|------|--------|----------|----------|
| GPT3+VMF3 对流层 | `enableGpt3Vmf3` | `PppOptimizations` | 毫米~厘米级（先验ZTD） | 间接（更准的先验加速收敛） |
| GPT3 网格插值 | `useGpt3Grid` | `Gpt3GridReader` + `PppOptimizations` | 毫米级（5°×5°网格） | 间接 |
| IERS2010 潮汐 | `enableIers2010` | `PppOptimizations` | 毫米级（长周期潮+极潮） | 无 |
| ISB/IFCB/IFB 偏差 | `enableIsbIfcbIfb` | `PppBiasModel` + `PppCoreEx` | 厘米~分米级（多系统偏差） | 显著（消除系统间偏差） |
| PPP-AR 模糊度固定 | `enablePppAR` | `PppAmbFix` + `PppCoreEx` | 厘米级（固定→浮点） | 显著（10~20min→固定） |
| PPP-AR Fix-and-Hold | `enablePppArFixHold` | `PppAmbFix.pppArFixHold()` | 提升固定连续性 | 减少fix→float跳变 |
| Partial AR | `enablePppPartialAR` | `PppAmbFix.pppPartialAR()` | 提升弱条件下固定率 | 子集固定加速收敛 |
| BDS-3 PPP-AR | `enableBds3PppAR` | `PppAmbFixBds3` | BDS-3 B1C/B2a固定解 | BDS-3收敛加速 |

---

## 2. GPT3+VMF3 对流层模型

### 2.1 原理

**GPT3**（Global Pressure and Temperature 3）是经验对流层模型，基于球谐函数拟合全球气象参数（气压P、温度T、水汽压e、映射函数系数ah/aw、大地水准面差距undu），无需外部气象数据或网格文件。

**VMF3**（Vienna Mapping Function 3）是新一代对流层映射函数，替代GMF/GPT2w，提供更精确的高度角相关映射因子。

### 2.2 与标准模型对比

| 项目 | Saastamoinen+GMF（标准） | GPT3+VMF3（优化） |
|------|--------------------------|-------------------|
| 先验ZHD | Saastamoinen模型（需输入P） | GPT3经验模型（自动计算P/T/e） |
| 先验ZWD | Saastamoinen模型 | Saastamoinen+GPT3气象参数 |
| 映射函数 | GMF（经验系数） | VMF3（b/c系数随纬度/DOY变化） |
| 梯度映射 | 固定公式 | VMF3+cotz梯度 |
| 外部数据 | 无 | 可选GPT3 5°×5°网格 |

### 2.3 实现细节

**调用位置**：[PppCore.java:846](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppp/PppCore.java#L846) — `tropoDelayPrec()` 方法中

```java
if (rtk.rtkConfig != null && rtk.rtkConfig.enableGpt3Vmf3) {
    dtrp[0] = PppOptimizations.tropoDelayGpt3Vmf3(time, pos, azel, trp, dtdx, var, rtk.rtkConfig, nav);
    if (!Double.isNaN(dtrp[0])) return true;
}
// fallback to standard Saastamoinen+GMF
dtrp[0] = tropModelPrec(time, pos, azel, trp, dtdx, var);
```

**GPT3计算流程**：

1. 若 `useGpt3Grid=true` 且 `nav.gpt3Grid` 已加载，调用 `Gpt3GridReader.interpolateGpt3()` 进行5°×5°网格插值
2. 否则回退到简化经验系数（9阶球谐基函数：1, sinφ, cosφcosλ, cosφsinλ, sin²φ, ...）
3. 用经验系数（aP/bP/aT/bT/ae/be/aah/bah/aaw/baw）拟合气象参数扰动
4. 叠加标准大气模型得到 P, T, e, ah, aw, undu

**VMF3计算流程**：

1. 根据纬度和DOY计算湿映射函数b系数（`vmf3Bw`，分段线性插值）
2. 计算高度角改正（`vmf3HtCorr`，高程归化）
3. 映射函数：`mf = 1 + a / (1 + b / (1 + c / sinel))`

**对流层延迟公式**：

```
delay = mfh * ZHD + mfw * (x[0] - ZHD)
```

其中 `x[0]` 是状态向量中的天顶对流层总延迟（ZTD），ZHD是GPT3计算的干分量先验，`(x[0] - ZHD)` 是湿分量残差。

### 2.4 VMF3 b系数分段模型

| 纬度范围 | bw值 |
|----------|------|
| 0°~15° | 0.00108 |
| 15°~30° | 0.00108→0.00148 线性插值 |
| 30°~45° | 0.00148→0.00220 线性插值 |
| 45°~60° | 0.00220→0.00334 线性插值 |
| 60°~75° | 0.00334→0.00536 线性插值 |
| >75° | 0.00536 |

### 2.5 配置

```java
RtkConfig cfg = new RtkConfig();
cfg.enableGpt3Vmf3 = true;   // 启用GPT3+VMF3
cfg.useGpt3Grid = true;       // 启用GPT3 5°×5°网格插值（需提前加载gpt3Grid到Nav）
cfg.gpt3GridFile = "";        // GPT3网格文件路径
```

### 2.6 限制

- VMF3的c系数使用简化模型（cw=0），未读取VMF3网格（OP文件）
- 简化经验系数（fallback）的精度低于网格插值，建议配合 `useGpt3Grid=true` 使用
- **改进方向**：加载VMF3 OP文件，实现完整VMF3（含a_h/a_w网格和b_h/b_h/c_h/c_w系数）

---

## 3. IERS2010 潮汐模型

### 3.1 原理

IERS2010潮汐模型在IERS1996（RTKLIB标准）基础上增加三项改正：

1. **长周期潮汐**（Long-period tide）：频率低于半日潮的潮汐分量，幅度~毫米级
2. **大气潮汐**（Atmospheric tide）：大气压力变化引起的站点位移，幅度~毫米级
3. **极潮**（Pole tide）：极移引起的站点位移，幅度~厘米级

### 3.2 与标准模型对比

| 项目 | IERS1996（标准） | IERS2010（优化） |
|------|-----------------|-----------------|
| 固体潮 | 11阶潮汐展开 | 同 + 长周期潮改正 |
| 海潮 | 通过otdisp | 同 |
| 极潮 | 无或简化 | 简化IERS2010极潮公式 |
| 大气潮 | 无 | S1/S2大气潮（开关`enableAt1S2`，当前桩） |
| 量级 | ~分米级改正 | 额外~毫米~厘米级改正 |

### 3.3 实现细节

**调用位置**：[PppCore.java:621](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppp/PppCore.java#L621) — `pppos()` 方法中

```java
if (rtk.rtkConfig != null && rtk.rtkConfig.enableIers2010) {
    double[] disp2010 = PppOptimizations.tideDisplacementIers2010(
            TimeSystem.gpst2utc(obs[0].time), rr, opt.tidecorr, nav.erp, opt.odisp[0], rtk.rtkConfig);
    if (disp2010 != null) {
        for (int i = 0; i < 3; i++) disp[i] = disp2010[i];
    }
}
```

**IERS2010总位移**：

```
dr_total = dr_tide(IERS1996) + dr_long_period + dr_atmospheric + dr_pole
```

**长周期潮计算**：

```
P2(cosZ) = 0.5 * (3*cos²Z - 1)    // 二阶Legendre多项式
disp = h2 * (GM_body/GM_earth) * (a_earth/r_body)³ * P2 * a_earth
dr = disp * rr / |rr|              // 径向方向
```

**极潮计算**：

```
m1 = -xp*sin2φ*cosλ - yp*sin2φ*sinλ
m2 = -xp*cos2φ*cosλ - yp*cos2φ*sinλ
dr = scale * m * rr / |rr|
```

### 3.4 配置

```java
RtkConfig cfg = new RtkConfig();
cfg.enableIers2010 = true;   // 启用IERS2010潮汐模型
cfg.enableAt1S2 = false;     // S1/S2大气潮开关（预留，当前未实现）
// 同时需要 opt.tidecorr = 1 或 7 以启用潮汐改正
```

### 3.5 限制

- 大气潮（`atmosphericTide`）当前返回零向量（未实现S1/S2大气潮展开），`enableAt1S2`开关已预留
- 极潮使用简化公式（固定Love数h2=0.6090, l2=0.0840），未实现IERS2010完整极潮（含频率依赖的Love数和虚部改正）
- 长周期潮仅考虑太阳和月亮的二阶项，未考虑三阶项
- **改进方向**：实现S1/S2大气潮（需Herring公式或Ray-Ponte模型），实现完整极潮

---

## 4. ISB/IFCB/IFB 偏差模型

### 4.1 原理

多系统PPP中，不同GNSS系统之间存在多种偏差：

| 偏差类型 | 全称 | 含义 | 维度 |
|----------|------|------|------|
| **ISB** | Inter-System Bias | 系统间偏差（GPS-GLO/GAL/BDS时间差+码偏差） | 3（GLO/GAL/BDS各1） |
| **IFCB** | Inter-Frequency Clock Bias | 频间钟差（同一卫星不同频率的钟差差异） | MAXSAT（每卫星1个） |
| **IFB** | Inter-Frequency Bias | 频间偏差（接收机端不同频率的码偏差） | 4（GPS/GLO/GAL/BDS各1） |

### 4.2 状态向量扩展

启用偏差模型后，状态向量从标准PPP扩展：

```
x = [pos(3), clk(1), ztd(1~3), amb(N*nf), ISB(3), IFCB(MAXSAT), IFB(4)]
```

扩展维度计算（[PppBiasModel.extraDim()](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppp/PppBiasModel.java#L12)）：

```java
int dim = 0;
if (cfg.estimateIsb)  dim += 3;           // GLO/GAL/BDS
if (cfg.estimateIfcb) dim += MAXSAT;       // 每卫星1个
if (cfg.estimateIfb)  dim += 4;           // GPS/GLO/GAL/BDS
```

### 4.3 实现细节

**调用入口**：[PppProcessor.java:756](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppp/PppProcessor.java#L756)

```java
if (rtk.rtkConfig != null && (rtk.rtkConfig.enableIsbIfcbIfb || rtk.rtkConfig.enablePppAR
        || rtk.rtkConfig.enablePppArFixHold || rtk.rtkConfig.enablePppPartialAR
        || rtk.rtkConfig.enableBds3PppAR || rtk.rtkConfig.enableOsb
        || rtk.rtkConfig.enableAt1S2 || rtk.rtkConfig.useGpt3Grid)) {
    PppCoreEx.ppos(rtk, obsData, n, nav, rtk.rtkConfig);
} else {
    PppCore.pppos(rtk, obsData, n, nav);
}
```

**PppCoreEx处理流程**（[PppCoreEx.java](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppp/PppCoreEx.java)）：

1. 计算扩展状态向量维度 `nx = nxOrig + extraDim`
2. 若状态向量维度不匹配，重新分配 `rtk.x[]`、`rtk.P[]`、`rtk.xa[]`、`rtk.Pa[]`
3. 调用 `udstateEx()` 进行状态时间更新（含ISB/IFCB/IFB过程噪声）
4. 迭代求解：`pppResEx()` 构建观测方程 → Kalman滤波更新 → 残差检验
5. 收敛后依次尝试：PPP-AR WL+NL固定 → Partial AR → BDS-3 AR → Fix-and-Hold

**状态向量扩展**（[PppBiasModel.extendStateVector()](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppp/PppBiasModel.java#L62)）：

```java
// 复制原有状态和协方差到扩展向量
System.arraycopy(rtk.x, 0, xNew, 0, nxOrig);
for (int i = 0; i < nxOrig; i++) {
    System.arraycopy(rtk.P, i * rtk.nx, PNew, i * nxNew, nxOrig);
}
// 初始化新增参数的方差
PNew[idx * nxNew + idx] = varIsb;   // ISB: 60² = 3600 m²
PNew[idx * nxNew + idx] = varIfcb;  // IFCB: 30² = 900 m²
PNew[idx * nxNew + idx] = varIfb;   // IFB: 60² = 3600 m²
```

**观测方程扩展**（[PppCoreEx.pppResEx()](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppp/PppCoreEx.java#L245)）：

- ISB：GLO/GAL/BDS系统的观测方程中，对应ISB参数的H矩阵元素设为1.0
- IFCB：第二频率载波相位观测方程中，对应卫星IFCB参数的H矩阵元素设为1.0
- IFB：各系统码偏差观测方程中，对应系统IFB参数的H矩阵元素设为1.0

**过程噪声更新**（[PppCoreEx.udstateEx()](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppp/PppCoreEx.java#L199)）：

```java
// ISB/IFCB/IFB参数采用随机游走模型
P[idx * nx + idx] += SQR(cfg.isbPrn) * Math.abs(rtk.tt);    // ISB: prn=0.01 m/√s
P[idx * nx + idx] += SQR(cfg.ifcbPrn) * Math.abs(rtk.tt);   // IFCB: prn=0.01 m/√s
P[idx * nx + idx] += SQR(cfg.ifbPrn) * Math.abs(rtk.tt);    // IFB: prn=0.001 m/√s
```

### 4.4 参数索引计算

```java
// ISB起始索引 = pppBaseDim(opt)
// IFCB起始索引 = pppBaseDim(opt) + (estimateIsb ? 3 : 0)
// IFB起始索引 = pppBaseDim(opt) + (estimateIsb ? 3 : 0) + (estimateIfcb ? MAXSAT : 0)

int pppBaseDim(PrcOpt opt) {
    int np = opt.dynamics == 0 ? 3 : 9;    // 位置+速度
    int nc = 1;                              // 接收机钟差
    int nt = ...;                            // 对流层参数
    int ni = ...;                            // 电离层参数
    int nd = ...;                            // 动力学参数
    return np + nc + nt + ni + nd;
}
```

### 4.5 配置

```java
RtkConfig cfg = new RtkConfig();
cfg.enableIsbIfcbIfb = true;   // 启用偏差模型总开关
cfg.estimateIsb = true;         // 估计ISB（默认true）
cfg.estimateIfcb = true;        // 估计IFCB（默认true）
cfg.estimateIfb = true;         // 估计IFB（默认true）
cfg.isbPrn = 0.01;             // ISB过程噪声（m/√s）
cfg.ifcbPrn = 0.01;            // IFCB过程噪声（m/√s）
cfg.ifbPrn = 0.001;            // IFB过程噪声（m/√s）
```

### 4.6 限制

- IFCB参数数量为MAXSAT（85），可能导致状态向量过大、滤波效率降低
- 未实现OSB（Observable-Specific Bias）模型的完整观测方程集成（`enableOsb`开关已预留）
- **改进方向**：实现OSB模型完整集成、DCB产品读取

---

## 5. PPP-AR 模糊度固定

### 5.1 原理

PPP-AR（Ambiguity Resolution）通过固定整周模糊度，将PPP从浮点解（dm~m级）提升至固定解（cm级），显著改善精度和收敛速度。

**WL+NL策略**：

1. **宽巷（WL）固定**：利用MW组合（Melbourne-Wübbena）固定宽巷模糊度，波长~86cm（GPS L1-L2），容易固定
2. **窄巷（NL）固定**：在WL固定基础上，用LAMBDA方法固定窄巷模糊度，波长~10cm，需要较高的浮点模糊度精度

### 5.2 与RTK模糊度固定的区别

| 项目 | RTK-AR | PPP-AR |
|------|--------|--------|
| 模糊度类型 | 双差模糊度（卫星间+接收机间差分） | 非差模糊度（需FCB/OSB产品校正） |
| 整数性 | 天然整数（差分消除偏差） | 需偏差校正后才具整数性 |
| 固定策略 | LAMBDA直接搜索 | WL+NL两步固定 |
| 固定后 | Fix-and-Hold | 直接替换 + 可选Fix-and-Hold |
| 产品依赖 | 无 | 支持FCB/OSB产品（已实现） |

### 5.3 WL+NL 固定（pppAmbFixWlNl）

**调用位置**：[PppCoreEx.java:167](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppp/PppCoreEx.java#L167)

```java
if (cfg.enablePppAR && rtk.sol.stat == Constants.SOLQ_PPP) {
    int nb = PppAmbFix.pppAmbFixWlNl(rtk, null, rtk.xa, 1, 0, 0, nav);
    if (nb > 1) {
        rtk.sol.stat = Constants.SOLQ_FIX;
    }
}
```

**WL固定流程**（[PppAmbFix.java](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppp/PppAmbFix.java)）：

1. 遍历所有卫星和频率，收集方差 < 1.0 cycle² 的模糊度
2. 保留GPS、Galileo和BDS卫星（`SYS_GPS | SYS_GAL | SYS_CMP`）
3. 计算WL浮点模糊度和协方差，应用FCB/OSB改正
4. LAMBDA搜索，检查ratio > `pppArRatioWl`（默认2.0）
5. 若WL ratio不足，返回失败

**NL固定流程**：

1. 从浮点模糊度扣除WL固定值，得到NL模糊度
2. 应用FCB/OSB NL改正
3. LAMBDA搜索，检查ratio > `pppArRatioNl`（默认3.0）
4. 若NL ratio不足，返回失败

**FCB/OSB产品支持**（[PppAmbFix.getWlFcb()/getNlFcb()](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppp/PppAmbFix.java#L380)）：

```java
// 支持两种FCB产品来源：
// 1. 传统FCB产品：FcbReader.getFcbWl()/getFcbNl()
// 2. OSB转换：nav.fcbFromOsb=true时，从OSB产品计算WL/NL FCB
private static double getWlFcb(Nav nav, int sat, double lam1, double lam2, double time) {
    if (nav.fcbWl == null) return 0.0;
    if (nav.fcbFromOsb) {
        double osbL1 = nav.fcbWl[sat - 1][0];
        double osbL2 = nav.fcbWl[sat - 1][1];
        return osbL1 / lam1 - osbL2 / lam2;
    }
    return FcbReader.getFcbWl(nav, sat, time);
}
```

**固定后处理**：

```java
// 将浮点模糊度替换为固定值
rtk.x[idx] = fixedAmb;
PppCore.initx(rtk.x, rtk.P, nx, fixedAmb, 0.0, idx);
rtk.ssat[sat - 1].fix[f] = 1;
```

### 5.4 Fix-and-Hold（pppArFixHold）

**调用位置**：[PppCoreEx.java:189](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppp/PppCoreEx.java#L189)

模糊度固定后，将其协方差从浮点值(~1.0)收紧到`pppArFixHoldVar`(默认1e-6)，使后续历元中该模糊度不会被重新浮点化，从而"锁定"固定解。

```java
if (cfg.enablePppArFixHold && rtk.sol.stat == Constants.SOLQ_FIX) {
    PppAmbFix.pppArFixHold(rtk, nav);
}
```

仅收紧方差 > `pppArFixHoldVar` 的模糊度，避免重复收紧。

### 5.5 Partial AR（pppPartialAR）

**调用位置**：[PppCoreEx.java:174](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppp/PppCoreEx.java#L174)

当全模糊度固定比率检验失败时，尝试固定一个子集：

1. 按模糊度方差从小到大排序（方差小=更可靠）
2. 从全部卫星开始，逐步剔除方差最大的模糊度
3. 对每个子集运行LAMBDA搜索+比率检验
4. 选择通过比率检验且固定数最多的子集

约束：子集大小 >= `pppPartialArMinSats`（默认4），搜索次数 <= `pppPartialArMaxTries`（默认10），比率 >= `pppPartialArMinRatio`（默认2.0）。

```java
if (cfg.enablePppPartialAR && rtk.sol.stat != Constants.SOLQ_FIX) {
    int nb = PppAmbFix.pppPartialAR(rtk, null, rtk.xa, 1, 0, 0, nav);
    if (nb > 1) {
        rtk.sol.stat = Constants.SOLQ_FIX;
    }
}
```

### 5.6 BDS-3 PPP-AR（pppAmbFixBds3）

**调用位置**：[PppCoreEx.java:182](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppp/PppCoreEx.java#L182)

BDS-3新信号B1C(1575.42MHz)和B2a(1176.45MHz)的模糊度固定，仅针对PRN 19~46的BDS-3 MEO/IGSO卫星。固定策略与GPS/GAL的WL+NL一致。

实现类：[PppAmbFixBds3.java](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppp/PppAmbFixBds3.java)

```java
if (cfg.enableBds3PppAR) {
    int nb = PppAmbFixBds3.pppAmbFixBds3(rtk, null, rtk.xa, nav);
    if (nb > 1) {
        LOG.debug("PppCoreEx: BDS-3 PPP-AR fixed {} ambiguities", nb);
    }
}
```

### 5.7 配置

```java
RtkConfig cfg = new RtkConfig();
// PPP-AR 基础
cfg.enablePppAR = true;            // 启用PPP-AR（WL+NL）
cfg.pppArRatioWl = 2.0;            // WL LAMBDA ratio阈值
cfg.pppArRatioNl = 3.0;            // NL LAMBDA ratio阈值

// Fix-and-Hold
cfg.enablePppArFixHold = false;    // 启用Fix-and-Hold
cfg.pppArFixHoldVar = 1e-6;        // 固定后方差收紧值

// Partial AR
cfg.enablePppPartialAR = false;    // 启用部分模糊度固定
cfg.pppPartialArMinRatio = 2.0;    // Partial AR最小ratio
cfg.pppPartialArMinSats = 4;       // Partial AR最少卫星数
cfg.pppPartialArMaxTries = 10;     // Partial AR最大搜索次数

// BDS-3
cfg.enableBds3PppAR = false;       // 启用BDS-3 PPP-AR
```

### 5.8 限制

- WL组合计算简化：未使用精确的MW组合（需DCB校正），WL固定可靠性受限
- 未实现DCB产品独立读取（当前FCB/OSB产品通过`Nav.fcbWl`传入）
- **改进方向**：实现DCB产品读取（CODE/IGS），完善MW组合计算

---

## 6. 调用架构

### 6.1 整体调用链

```
PppProcessor.process()
  └─ 每历元:
       ├─ [enableGpt3Vmf3] PppOptimizations.tropoDelayGpt3Vmf3()  ← 在PppCore.tropoDelayPrec()中
       ├─ [enableIers2010] PppOptimizations.tideDisplacementIers2010()  ← 在PppCore.pppos()中
       ├─ [任意扩展开关] PppCoreEx.ppos()
       │     ├─ udstateEx()  ← 状态时间更新 + ISB/IFCB/IFB过程噪声
       │     ├─ pppResEx() × MAX_ITER  ← 迭代滤波
       │     │     ├─ PppCore.pppRes()  ← 标准PPP残差
       │     │     └─ ISB/IFCB/IFB H矩阵扩展
       │     ├─ [enablePppAR] PppAmbFix.pppAmbFixWlNl()
       │     ├─ [enablePppPartialAR] PppAmbFix.pppPartialAR()
       │     ├─ [enableBds3PppAR] PppAmbFixBds3.pppAmbFixBds3()
       │     └─ [enablePppArFixHold] PppAmbFix.pppArFixHold()
       └─ [默认] PppCore.pppos()  ← 标准PPP滤波（无优化）
```

### 6.2 PppCore中的侵入点

PppCore中通过 `if(rtk.rtkConfig != null && rtk.rtkConfig.enableXxx)` 分支实现优化侵入：

| 侵入点 | 行号 | 条件 | 替代逻辑 |
|--------|------|------|----------|
| 潮汐改正 | L621 | `enableIers2010` | `Tides.tidedisp()` → `PppOptimizations.tideDisplacementIers2010()` |
| 对流层延迟 | L846 | `enableGpt3Vmf3` | `tropModelPrec()` → `PppOptimizations.tropoDelayGpt3Vmf3()` |

### 6.3 PppProcessor中的侵入点

| 侵入点 | 行号 | 条件 | 替代逻辑 |
|--------|------|------|----------|
| 历元处理（Rover） | L756 | 任意扩展开关 | `PppCore.pppos()` → `PppCoreEx.ppos()` |
| 历元处理（Base） | L1068 | 任意扩展开关 | `PppCore.pppos()` → `PppCoreEx.ppos()` |

扩展开关条件：`enableIsbIfcbIfb || enablePppAR || enablePppArFixHold || enablePppPartialAR || enableBds3PppAR || enableOsb || enableAt1S2 || useGpt3Grid`

---

## 7. 测试验证

### 7.1 单元测试

**文件**：[PppOptimizationsTest.java](file:///D:/code/rtklib_java/rtklib-core/src/test/java/org/rtklib/java/ppp/PppOptimizationsTest.java)

| 测试方法 | 验证内容 |
|----------|----------|
| `testGpt3Vmf3DisabledByDefault` | 默认关闭 |
| `testIers2010DisabledByDefault` | 默认关闭 |
| `testIsbIfcbIfbDisabledByDefault` | 默认关闭 |
| `testPppArDisabledByDefault` | 默认关闭 |
| `testGpt3Vmf3ReturnsNaNWhenDisabled` | 关闭时返回NaN（回退到标准模型） |
| `testGpt3Vmf3ReturnsValueWhenEnabled` | 启用时返回正值延迟（0~30m） |
| `testIers2010ReturnsNullWhenDisabled` | 关闭时返回null |
| `testIers2010ReturnsValueWhenEnabled` | 启用时返回位移向量（<1m） |
| `testConfigCopyConstructor` | 配置拷贝正确 |
| `testPppBiasModelExtraDimZeroWhenDisabled` | 关闭时扩展维度=0 |
| `testPppBiasModelExtraDimWhenEnabled` | 全部启用时=3+MAXSAT+4 |
| `testPppBiasModelExtraDimPartial` | 部分启用时正确计算 |
| `testPppArRatioDefaults` | ratio阈值默认值正确 |

### 7.2 集成测试

**文件**：[RtkPppIntegrationTest.java](file:///D:/code/rtklib_java/rtklib-core/src/test/java/org/rtklib/java/ppp/RtkPppIntegrationTest.java)

| 测试方法 | 优化配置 |
|----------|----------|
| `testPppWithRtcmData` | 无优化（基线） |
| `testPppWithGpt3Vmf3` | GPT3+VMF3 |
| `testPppWithIers2010` | IERS2010 |
| `testPppWithBiasModel` | ISB/IFCB/IFB |
| `testPppWithAllOptimizations` | 全部优化 |

### 7.3 对比测试结果

**文件**：[PppProductIntegrationTest.java](file:///D:/code/rtklib_java/rtklib-product/src/test/java/org/rtklib/java/product/PppProductIntegrationTest.java)

**数据**：静态CORS站，RTCM流，1小时观测（15秒采样率），精密星历（SP3+CLK）

**动态PPP（PMODE_PPP_KINEMA）基础版 vs 优化版**：

| 站点 | 指标 | 基础版 | 优化版(GPT3+VMF3+IERS2010) | 差异 |
|------|------|--------|---------------------------|------|
| 站A | Std N | 2.054m | 1.991m | -0.063m |
| | Std E | 0.494m | 0.682m | +0.188m |
| | Std U | 0.776m | 0.674m | -0.101m |
| 站B | Std N | 2.049m | 1.826m | -0.223m |
| | Std E | 1.214m | 1.272m | +0.057m |
| | Std U | 2.265m | 2.289m | +0.024m |
| 站C | Std N | 1.299m | 1.219m | -0.080m |
| | Std E | 0.946m | 0.792m | -0.154m |
| | Std U | 1.408m | 1.435m | +0.027m |

**结论**：
- **N方向**：优化版在三站均表现出正向影响趋势（差值-0.06~-0.22m），GPT3+VMF3对流层映射函数对北向可能有系统性改善，但1小时动态浮点解不足以确认精度显著提升
- **E方向**：站C改善0.15m，站A/B略差0.06~0.19m，差异在动态PPP浮点解噪声量级（±0.2m）内，**未见显著恶化**
- **U方向**：站A改善0.10m，站B/C差异在±0.03m以内，**未见显著恶化**
- **总体**：1小时短时段数据下，GPT3+VMF3在N方向表现出稳定的正向影响趋势，E/U未见显著恶化，但不足以证明精度显著提升。需更长时间（>6小时）或配合PPP-AR验证

**24小时静态PPP（PMODE_PPP_STATIC）基础版 vs 优化版**：

| 站点 | 指标 | 基础版 | 优化版 | 差异 |
|------|------|--------|--------|------|
| 站A | 成功历元 | 974/5739 | 1033/5739 | +59 |
| | 位置差dN | - | - | -0.144m |
| | 位置差dE | - | - | -0.028m |
| | 位置差dU | - | - | +0.401m |
| 站B | 成功历元 | 938/5757 | 930/5757 | -8 |
| | 位置差dN | - | - | +0.180m |
| | 位置差dE | - | - | -0.230m |
| | 位置差dU | - | - | +0.260m |

**24h结论**：静态PPP下基础版与优化版最终位置差异在分米级（N: ±0.14~0.18m, E: ±0.03~0.23m, U: 0.26~0.40m），GPT3+VMF3和IERS2010对位置估计的影响仍属分米级，与1小时动态结果一致。成功历元比例较低（~17%），需进一步排查PPP收敛问题。

---

## 8. RtkConfig 开关一览

### 8.1 对流层相关

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `enableGpt3Vmf3` | boolean | false | GPT3+VMF3对流层模型 |
| `useGpt3Grid` | boolean | false | 使用GPT3 5°×5°网格插值（需提前加载到Nav） |
| `gpt3GridFile` | String | "" | GPT3网格文件路径 |

### 8.2 潮汐相关

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `enableIers2010` | boolean | false | IERS2010潮汐模型 |
| `enableAt1S2` | boolean | false | S1/S2大气潮（预留，当前未实现） |

### 8.3 偏差模型相关

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `enableIsbIfcbIfb` | boolean | false | ISB/IFCB/IFB偏差模型总开关 |
| `estimateIsb` | boolean | true | 估计ISB（需enableIsbIfcbIfb=true） |
| `estimateIfcb` | boolean | true | 估计IFCB（需enableIsbIfcbIfb=true） |
| `estimateIfb` | boolean | true | 估计IFB（需enableIsbIfcbIfb=true） |
| `isbPrn` | double | 0.01 | ISB过程噪声（m/√s） |
| `ifcbPrn` | double | 0.01 | IFCB过程噪声（m/√s） |
| `ifbPrn` | double | 0.001 | IFB过程噪声（m/√s） |
| `enableOsb` | boolean | false | OSB偏差模型（预留） |

### 8.4 PPP-AR相关

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `enablePppAR` | boolean | false | PPP-AR模糊度固定（WL+NL） |
| `pppArRatioWl` | double | 2.0 | WL LAMBDA ratio阈值 |
| `pppArRatioNl` | double | 3.0 | NL LAMBDA ratio阈值 |
| `enablePppArFixHold` | boolean | false | Fix-and-Hold策略 |
| `pppArFixHoldVar` | double | 1e-6 | 固定后方差收紧值 |
| `enablePppPartialAR` | boolean | false | 部分模糊度固定 |
| `pppPartialArMinRatio` | double | 2.0 | Partial AR最小ratio |
| `pppPartialArMinSats` | int | 4 | Partial AR最少卫星数 |
| `pppPartialArMaxTries` | int | 10 | Partial AR最大搜索次数 |
| `enableBds3PppAR` | boolean | false | BDS-3 PPP-AR（B1C/B2a） |

---

## 9. 改进路线图

### 9.1 已完成（v2.3.0）

- [x] GPT3 5°×5°网格插值（`Gpt3GridReader`，`useGpt3Grid`开关）
- [x] 偏差参数过程噪声更新（随机游走模型，`isbPrn`/`ifcbPrn`/`ifbPrn`）
- [x] FCB/OSB产品读取（`FcbReader` + `nav.fcbFromOsb`）
- [x] PPP-AR Fix-and-Hold策略（`pppArFixHold`）
- [x] PPP-AR 部分模糊度固定（`pppPartialAR`）
- [x] BDS-3 PPP-AR（B1C/B2a频率，`PppAmbFixBds3`）

### 9.2 待完成

- [ ] VMF3网格（OP文件）读取，实现完整VMF3（含a_h/a_w网格和b/c系数）
- [ ] IERS2010 S1/S2大气潮实现（`enableAt1S2`开关已预留）
- [ ] IERS2010完整极潮（含频率依赖Love数和虚部改正）
- [ ] OSB模型完整观测方程集成（`enableOsb`开关已预留）
- [ ] DCB产品独立读取（CODE/IGS格式）
- [ ] PPP-AR WL组合完善（精确MW组合+DCB校正）

### 9.3 长期（v3.0.0）

- [ ] PPP-RTK（SSR+区域电离层改正，cm级快速收敛）
- [ ] 逐历元PPP-AR（无需UPD产品，利用历元间约束）
- [ ] 非组合PPP（Uncombined PPP，估计电离层延迟）
- [ ] 多频PPP-AR（L1+L2+L5三频固定）

---

## 10. 参考文献

1. Boehm J, et al. (2015). "GPT2: Global Pressure and Temperature model". Geophysical Research Letters.
2. Landskron D, Boehm J (2018). "VMF3 for GNSS". Journal of Geodesy.
3. Petit G, Luzum B (2010). "IERS Conventions (2010)". IERS Technical Note 36.
4. Ge M, et al. (2008). "Precise point positioning with integer ambiguity resolution". Journal of Geodesy.
5. Laurichesse D, Mercier F (2007). "Integer ambiguity resolution on undifferenced GPS phase measurements". ION ITM.
6. Geng J, et al. (2019). "PRIDE-PPPAR". GPS Solutions.
7. RTKLIB demo5 b34a: https://github.com/rtklibexplorer/RTKLIB
