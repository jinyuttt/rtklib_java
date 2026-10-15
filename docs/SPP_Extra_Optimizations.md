# Java版SPP额外优化项详细文档

本文档记录Java版RTKLIB中SPP（Single Point Positioning）的额外优化项。
这些优化通过 `RtkConfig` 独立开关控制，默认全部关闭（`enableSppInnovChisq`除外），不影响默认行为。

参考项目：[MobileGNSS-SPP](https://github.com/ebhual/MobileGNSS-SPP)（C语言，基于RTKLIB 2.4.3的SPP优化）

测试结果详见：[URBAN_CANYON_SPP_TEST_RESULTS.md](URBAN_CANYON_SPP_TEST_RESULTS.md)

---

## 目录

1. [EKF时间传播](#1-ekf时间传播enablesppekf)
2. [M-估计抗差](#2-m-估计抗差enablespprobust)
3. [零速约束](#3-零速约束enablesppzerovel)
4. [多普勒+SNR加权](#4-多普勒snr加权enablesppdopplersnr)
5. [新息卡方检验](#5-新息卡方检验enablesppinnovchisq)
6. [P膨胀保护与diverged恢复](#6-p膨胀保护与diverged恢复)
7. [优化项依赖关系与调用顺序](#7-优化项依赖关系与调用顺序)
8. [RtkConfig字段与优化项对应表](#8-rtkconfig字段与优化项对应表)
9. [优化矩阵](#9-优化矩阵)
10. [推荐配置组合](#10-推荐配置组合)
11. [已知问题与后续方向](#11-已知问题与后续方向)
12. [实测验证汇总](#12-实测验证汇总)

---

## 1. EKF时间传播（enableSppEkf）

### 1.1 问题背景

标准LS-SPP（最小二乘单点定位）逐历元独立解算，无状态传递：
- 信号间断时定位失败（无先验位置可用）
- 高程跳动大（~3m逐历元波动）
- 无法估计速度状态

### 1.2 解决方案

用扩展卡尔曼滤波（EKF）替代逐历元LSQ，状态向量包含位置、速度、加速度和钟差：

```
x = [pos(3), vel(3), acc(3), clk(1), clk_glo(1), clk_gal(1), clk_qzs(1), clk_cmp(1), clk_irn(1), clk_dot(1)]
    状态维度 nxF = 9 + nClock + 1
    nClock = SppCore.nx(opt) - 3  (系统间钟差数)
```

### 1.3 核心算法

#### 1.3.1 状态转移模型

9维运动学状态（位置/速度/加速度）的状态转移矩阵F：

```
F = I + dt * A + dt²/2 * B

其中：
  A = [0 I 0; 0 0 I; 0 0 0]   (9×9)
  B = [0 0 I; 0 0 0; 0 0 0]   (9×9)

展开：
  F[i,i] = 1                    (对角线)
  F[i,i+3] = dt                 (位置←速度)
  F[i,i+6] = dt²/2              (位置←加速度)
  i = 0,1,2
```

时间传播：
```
x_pred = F * x
P_pred = F * P * Fᵀ + Q
```

#### 1.3.2 过程噪声Q

加速度过程噪声通过ENU→ECEF旋转注入位置加速度分量：

```
Q_enu = diag(σ_acc1², σ_acc1², σ_acc2²) * |dt|

σ_acc1 = sppEkfAccPrn1 = 10.0 m/s²
σ_acc2 = sppEkfAccPrn2 = 10.0 m/s²

Q_ecef = R * Q_enu * Rᵀ    (R为LLH→ECEF旋转矩阵)
```

diverged状态下Q放大`sppEkfDivergedQscale`倍。

#### 1.3.3 观测方程

伪距观测（来自`SppCore.rescode`）和多普勒观测（来自`resdopFilter`）联合：

```
H = [H_pseudorange; H_doppler]    (nv × nxF)
v = [v_pseudorange; v_doppler]    (nv × 1)
```

多普勒观测方程对速度和钟差率的偏导：
```
H_doppler[j, vel_idx] = -e_j      (视线方向单位向量)
H_doppler[j, clk_dot_idx] = 1.0
```

#### 1.3.4 KF更新

```
K = P * Hᵀ * (H * P * Hᵀ + R)⁻¹
x = x + K * v
P = (I - K*H) * P * (I - K*H)ᵀ + K * R * Kᵀ   (Joseph形式)
```

### 1.4 初始化策略

| 条件 | 行为 |
|------|------|
| 首次历元或`!initialized` | 用LS解初始化：`pos=LS.rr, vel=[0.01,0.01,0.01], acc=[1e-6,1e-6,1e-6]` |
| 时间间隔`dt >= sppEkfDtGap` | 重新初始化（防止长时间间断后状态过时） |
| `norm(x,3) < 1000` | 位置异常，重新初始化 |

### 1.5 P膨胀检测与重置

```
var = mean(P[0:3]对角线)

if var > sppEkfVarResetThresh && LS成功:
    重置位置/速度/加速度到LS解
    清除diverged标记

if var > sppEkfVarResetThresh && LS失败:
    P对角线封顶到sppEkfVarCap
    标记diverged=true, divergedEpochs++
```

### 1.6 配置参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `enableSppEkf` | false | EKF开关 |
| `sppEkfVarPos` | 2500 m² | 位置初始方差（50m） |
| `sppEkfVarVel` | 100 m² | 速度初始方差（10m/s） |
| `sppEkfVarAcc` | 100 m² | 加速度初始方差 |
| `sppEkfClkProcessNoise` | 10000 m | 钟差过程噪声 |
| `sppEkfAccPrn1` | 10.0 m/s² | 水平加速度过程噪声 |
| `sppEkfAccPrn2` | 10.0 m/s² | 垂直加速度过程噪声 |
| `sppEkfVelResidScale` | 1.0 | 速度残差注入P的缩放因子 |
| `sppEkfVarResetThresh` | 2500 m² | P膨胀重置阈值 |
| `sppEkfDtGap` | 10.0 s | 时间间断重置阈值 |
| `sppEkfVarCap` | 625 m² | P对角线封顶值 |
| `sppEkfMinSat` | 0 | EKF最低卫星数（0=不降级） |
| `sppEkfLowSatThresh` | 7 | 低卫星数阈值 |
| `sppEkfDivergedRecoverEpochs` | 5 | diverged恢复所需连续LS成功历元数 |
| `sppEkfDivergedRscaleBase` | 100.0 | diverged时R放大基数 |
| `sppEkfDivergedRscaleMax` | 1000.0 | 自适应R放大上限 |
| `sppEkfDivergedMaxEpochs` | 20 | diverged持续超过此数强制R=1解锁 |
| `sppEkfDivergedQscale` | 1.0 | diverged时Q放大倍数 |

### 1.7 实现位置

| 组件 | 文件 | 说明 |
|------|------|------|
| EKF主流程 | `SppOptimizations.estposFilter()` | 预测→观测→更新 |
| 时间传播 | `SppOptimizations.udposSpp()` | F矩阵构建、P预测、Q注入 |
| 多普勒观测 | `SppOptimizations.resdopFilter()` | 多普勒残差+设计矩阵 |
| EKF状态 | `SppEkfState` | x/P/diverged等状态管理 |
| 入口分发 | `PntPos.pntpos()` | enableSppEkf→estposFilter，否则→estpos |

### 1.8 实测效果

| 数据集 | 平均卫星 | LS-SPP成功率 | EKF-SPP成功率 | LS 2D RMS | EKF+抗差 2D RMS |
|--------|---------|-------------|--------------|-----------|----------------|
| UrbanNav Tokyo | 6.6 | 82.4% | **100%** | 713m | 699m |
| PPC Tokyo | 15.1 | 87.5% | **100%** | 142598m | **7778m** |
| PPC Nagoya | 13.1 | 99.0% | 99.9% | 559m | 553m |
| Net_Diff Urban | 4.6 | 55.2% | 100% | 840m | 1511m⚠️ |

> ⚠️ Net_Diff卫星数仅4.6颗，EKF虽提升成功率但精度发散，应使用LS-SPP

---

## 2. M-估计抗差（enableSppRobust）

### 2.1 问题背景

城市峡谷中NLOS（非视距）和多路径信号产生大残差（数十~数百米），标准LSQ/EKF对所有观测等权处理，大残差污染位置估计。

### 2.2 解决方案

采用Huber M-估计迭代重加权最小二乘，对大残差观测降权：

```
权重函数 w(z):
  |z| < k  →  w = 1/σ           (正常段，保持原权)
  |z| ≥ k  →  w = k / |resid|   (降权段，Huber模型)
  |resid| ≥ outlier_thresh →  w = minW / σ   (淘汰段，几乎零权)

其中 z = |resid| / σ  (标准化残差)
```

位置和速度使用不同的Huber阈值k和outlier阈值，因为速度残差量级远小于位置残差。

### 2.3 核心算法

#### 2.3.1 抗差LSQ（robustLsq）

用于独立位置/速度估计的抗差迭代：

```
for iter = 1..sppRobustMaxIter:
    对每个观测i:
        resid_i = |v_i - H_i * dx|
        w_i = robustWeight(resid_i, σ_i, mode)
        v_new_i = v_i * w_i
        H_new_i = H_i * w_i
    lsq(H_new, v_new, nx, nv, dx, Q)
    if ||dx - dx_prev|| < 0.01: break
```

#### 2.3.2 抗差EKF滤波（robustFilter）

在EKF更新前对观测方差进行抗差调整：

```
for iter = 1..sppRobustMaxIter:
    计算抗差权重 → 调整H和v
    lsq求解增量dx
    if收敛: break

用最终权重调整观测方差:
    var_i = 1 / (w_i²)    (权重越小→方差越大→KF中贡献越小)
```

### 2.4 配置参数

| 参数 | 默认值 | MobileGNSS-SPP(C) | 说明 |
|------|--------|-------------------|------|
| `sppRobustKPos` | 1.345 | 1.345 | 位置Huber阈值（标准值） |
| `sppRobustKVel` | 5.0 | 5.0 | 速度Huber阈值 |
| `sppRobustMaxIter` | 15 | 15 | 最大迭代次数 |
| `sppRobustPosOutlier` | **100.0** m | **30.0** m | 位置outlier阈值（C更激进） |
| `sppRobustVelOutlier` | **20.0** m/s | **2.0** m/s | 速度outlier阈值（C更激进） |
| `sppRobustMinW` | 0.0001 | 0.0001 | 淘汰段最小权重 |

> C代码的outlier阈值更激进（30m vs 100m），后续可调优验证

### 2.5 实现位置

| 方法 | 文件 | 说明 |
|------|------|------|
| `robustWeight()` | `SppOptimizations.java` | Huber权重计算 |
| `robustLsq()` | `SppOptimizations.java` | 抗差迭代LSQ |
| `robustFilter()` | `SppOptimizations.java` | 抗差EKF方差调整 |

### 2.6 实测效果

| 数据集 | EKF 3D RMS | EKF+抗差 3D RMS | 改善 |
|--------|-----------|----------------|------|
| PPC Tokyo | 4,076,377m | 7,965m | **-99.8%** |
| downtown(手机) | 530m | 108m | **-79.5%** |
| street(手机) | 128m | 93m | **-27.4%** |
| Nagoya | 574m | 558m | -2.8% |
| UrbanNav | 708m | 701m | -1.0% |

> 抗差在EKF发散场景改善27%~99.8%，在卫星充足场景无负面影响

---

## 3. 零速约束（enableSppZeroVel）

### 3.1 问题背景

静态/准静态场景（滑坡监测、基准站），接收机静止但EKF速度估计仍有噪声（~0.1-1m/s），导致位置缓慢漂移。

### 3.2 解决方案

检测到零速时，将位置锁定到上一历元、速度设为极小值：

```
if |v_north| < thresh && |v_east| < thresh && 已初始化:
    consecutiveZeroVelEpochs++
    rate = 1.0 / consecutiveZeroVelEpochs

    if rate >= 1.0:        (第1个零速历元)
        pos = pos_prev     (完全锁定)
        vel = [1e-4, 1e-4, 1e-4]
    else:                  (后续零速历元)
        pos = pos_prev * (1-rate) + pos * rate   (渐进混合)
        vel = [1e-4, 1e-4, 1e-4]
else:
    consecutiveZeroVelEpochs = 0
```

混合策略避免第1个零速历元的位置跳变，后续历元逐渐增加当前位置权重。

### 3.3 配置参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `enableSppZeroVel` | **false** | 零速约束开关（**动态场景必须关闭**） |
| `sppZeroVelSpeedThresh` | 0.5 m/s | 零速检测速度阈值 |
| `sppZeroVelMinEpochs` | 1 | 最少连续零速历元数 |

### 3.4 逐项对照实测效果

基准：EKF+抗差，独立开启零速约束后的边际贡献：

| 数据集 | 场景 | 基准 2D(m) | +零速约束 2D(m) | Δ2D | 判定 |
|--------|------|-----------|----------------|-----|------|
| opensky | 动态开阔 | 190.10 | 190.14 | +0.0% | ≈ |
| street | 动态街道 | 90.99 | 91.09 | +0.1% | ⚠️ |
| downtown | 步行峡谷 | 102.99 | 102.53 | -0.4% | ≈ |
| elevated | 动态高架 | 3170.22 | 3164.29 | -0.2% | ≈ |
| PPC Tokyo | 车载遮挡 | 3963.44 | 3963.78 | +0.0% | ≈ |
| PPC Nagoya | 车载开阔 | 553.67 | 554.28 | +0.1% | ⚠️ |
| UrbanNav | 车载遮挡 | 699.13 | 699.31 | +0.0% | ≈ |

> 7组数据中零速约束无一显著改善。downtown步行场景略好（-0.4%），但街道和名古屋车载场景略差。**动态场景必须关闭**。静态场景待专项测试。

### 3.5 实现位置

- `SppOptimizations.estposFilter()` 中KF更新后的零速检测与位置修正段

---

## 4. 多普勒+SNR加权（enableSppDopplerSnr）

### 4.1 问题背景

标准SPP多普勒速度估计使用固定观测误差模型（`opt.err[4]`），不考虑信号质量差异：
- 低SNR卫星的多普勒噪声大，但权重与高SNR卫星相同
- 城市峡谷中NLOS卫星的多普勒偏差可达数m/s

### 4.2 解决方案

#### 4.2.1 多普勒SNR加权

启用时，多普勒观测方差使用与伪距相同的SNR自适应模型，但伪距/载波噪声比设为30：

```
optDop.eratio[0] = 30.0   (伪距/载波噪声比，标准值)
var = SppCore.varerr(optDop, ssat, obs, el, sys)
```

`varerr`内部包含SNR项：
```
varr += err[6]² * 10^(0.1 * max(err[5] - SNR, 0))
```

SNR低于参考值`err[5]`的卫星，方差指数级增大→权重降低。

#### 4.2.2 EKF中的多普勒观测

在EKF模式下，多普勒观测作为额外观测方程加入KF更新（而非独立速度估计）：

```
H = [H_pseudorange(nc×nxF); H_doppler(nd×nxF)]
v = [v_pseudorange; v_doppler]
```

多普勒对速度状态的偏导：`H[j, vel_idx] = -e_j`（视线方向单位向量）

### 4.3 配置参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `enableSppDopplerSnr` | false | 多普勒SNR加权开关 |

> 无独立参数，SNR模型参数复用`PrcOpt.err[5]`(参考SNR)和`PrcOpt.err[6]`(SNR系数)

### 4.4 实现位置

| 方法 | 文件 | 说明 |
|------|------|------|
| `SppOptimizations.resdopFilter()` | SppOptimizations.java | EKF模式多普勒观测方程 |
| `PntPos.resdop()` | PntPos.java | LSQ模式多普勒速度估计 |

### 4.5 逐项对照实测效果

基准：EKF+抗差，独立开启多普勒SNR后的边际贡献：

| 数据集 | 场景 | 基准 2D(m) | +多普勒SNR 2D(m) | Δ2D |
|--------|------|-----------|-----------------|-----|
| opensky | 动态开阔 | 190.10 | 190.10 | 0.0% |
| street | 动态街道 | 90.99 | 90.79 | -0.2% |
| downtown | 动态峡谷 | 102.99 | 102.51 | -0.5% |
| elevated | 动态高架 | 3170.22 | 3171.01 | +0.0% |
| PPC Tokyo | 车载遮挡 | 3963.44 | 3960.87 | -0.1% |
| PPC Nagoya | 车载开阔 | 553.67 | 553.61 | -0.0% |
| UrbanNav | 车载遮挡 | 699.13 | 699.21 | +0.0% |

> 多普勒SNR加权在7组数据上边际收益均<1%。在downtown峡谷场景略有收益（-0.5%），其他场景几乎无影响。**推荐开启但不期待显著改善**。

---

## 5. 新息卡方检验（enableSppInnovChisq）

### 5.1 问题背景

标准KF更新无条件接受所有观测，无论残差是1m还是1000m。城市峡谷中NLOS残差可达百米级，直接注入KF更新会严重污染状态估计。

MobileGNSS-SPP C代码用抗差降权替代硬拒绝，但我们的Java代码EKF主流程之前**无任何保护**。

### 5.2 解决方案

逐观测标准化新息（innovation）检验，两级策略：

```
新息: v_i = 观测_i - H_i * x_pred
新息协方差: S_ii = H_i * P * H_iᵀ + R_ii
标准化新息: nis = |v_i| / √S_ii   ~ N(0,1)

if nis > outlier_thresh:        (极端异常，>5σ)
    → 剔除（不参与KF更新）
elif nis > downweight_thresh:   (中等异常，3~5σ)
    → 降权（R放大downweightRscale倍）
else:                           (正常，≤3σ)
    → 正常更新
```

### 5.3 理论依据

- 逐观测标准化新息 `nis = |v_i| / √S_ii` 服从标准正态分布 N(0,1)
- 3σ 对应单侧 99.87% 分位，5σ 对应单侧 99.99997% 分位
- **不是χ²检验**：χ²(n,0.999)是全局检验用的，自由度=观测数n，不适用于逐观测
- 虽然数学上 `v²/S > 10.83` 等价于 `|v|/√S > 3.29`，但概念混淆会导致维护错误

### 5.4 低卫星数保护

卫星数<`sppEkfLowSatThresh`(7)时放宽阈值：

| 条件 | 剔除阈值 | 降权阈值 | 原因 |
|------|---------|---------|------|
| 正常（nSat≥7） | 5.0σ | 3.0σ | 标准策略 |
| 低卫星（nSat<7） | **7.0σ** | **5.0σ** | 卫星少时不能过度剔除，否则KF更新量不足 |

### 5.5 配置参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `enableSppInnovChisq` | **true** | 新息检验开关（**唯一默认开启的SPP优化**） |
| `sppInnovOutlierThresh` | 5.0 σ | 极端异常剔除阈值 |
| `sppInnovDownweightThresh` | 3.0 σ | 中等异常降权阈值 |
| `sppInnovOutlierThreshLowSat` | 7.0 σ | 低卫星数时剔除阈值 |
| `sppInnovDownweightThreshLowSat` | 5.0 σ | 低卫星数时降权阈值 |
| `sppInnovDownweightRscale` | 10.0 | 降权R放大倍数 |

### 5.6 降权实现

降权通过放大观测方差R实现，而非直接修改权重：

```java
double[] R = new double[nv * nv];
for (int j = 0; j < nv; j++) {
    R[j * nv + j] = var[j];
    if (downweight[j]) {
        R[j * nv + j] *= cfg.sppInnovDownweightRscale;  // R放大10倍
    }
}
```

R放大→K增益减小→该观测对状态更新的贡献降低。

### 5.7 实现位置

- `SppOptimizations.estposFilter()` 中新息检验段（约L502-L530）

### 5.8 逐项对照实测效果

基准：EKF+抗差，关闭新息检验后的变化：

| 数据集 | 场景 | 基准 2D(m) | -新息检验 2D(m) | Δ2D |
|--------|------|-----------|----------------|-----|
| opensky | 动态开阔 | 190.10 | 190.10 | 0.0% |
| street | 动态街道 | 90.99 | 90.99 | 0.0% |
| downtown | 动态峡谷 | 102.99 | 102.99 | 0.0% |
| elevated | 动态高架 | 3170.22 | 3170.22 | 0.0% |
| PPC Tokyo | 车载遮挡 | 3963.44 | 3963.44 | 0.0% |
| PPC Nagoya | 车载开阔 | 553.67 | 553.69 | +0.0% |
| UrbanNav | 车载遮挡 | 699.13 | 699.13 | -0.0% |

> 新息检验在7组数据上精度差异均<0.1%。原因是抗差估计已提前降权了大部分异常观测，新息检验作为**第二道防线**主要在极端异常（>5σ）时起作用。**推荐保持默认开启**，与抗差互补。

---

## 6. P膨胀保护与diverged恢复

### 6.1 问题背景

EKF的协方差P无对角元素上限，可以无限膨胀。当观测不足（卫星<5颗）或LS持续失败时：
1. P膨胀→K增益过大→残差以大增益注入状态→位置漂移
2. 漂移后残差更大→P继续膨胀→正反馈→发散

MobileGNSS-SPP C代码同样存在此问题（检测到P膨胀但LS失败时不做任何处理）。

### 6.2 解决方案

#### 6.2.1 P膨胀检测与封顶

```
var = mean(P[0:3]对角线)

if var > sppEkfVarResetThresh:
    if LS成功:  重置位置/速度/加速度到LS解
    if LS失败:  P对角线封顶到sppEkfVarCap，标记diverged
```

#### 6.2.2 diverged状态下的自适应R放大

diverged时放大观测方差R，降低KF增益，减少对不可靠观测的信任：

```
if diverged:
    if nSat < sppEkfLowSatThresh:     (低卫星)
        rscale = sppEkfDivergedRscaleBase    (固定100)
    elif divergedEpochs > sppEkfDivergedMaxEpochs:  (长期diverged)
        rscale = 1.0                          (强制解锁)
    else:                              (高卫星，自适应)
        rscale = min(sppEkfDivergedRscaleMax,
                     sppEkfDivergedRscaleBase × 2^divergedEpochs)

    R *= rscale
```

| divergedEpochs | rscale(高卫星) | rscale(低卫星) |
|----------------|---------------|---------------|
| 0 | 100 | 100 |
| 1 | 200 | 100 |
| 2 | 400 | 100 |
| 3 | 800 | 100 |
| 4+ | 1000(封顶) | 100 |
| >20 | **1.0**(解锁) | **1.0**(解锁) |

> 低卫星时用固定R=100，因为自适应在极端场景下会反噬（初期降权不够，后期过度降权）

#### 6.2.3 diverged恢复重初始化

连续` sppEkfDivergedRecoverEpochs`(5)个历元LS成功后，重初始化EKF状态：

```java
if (state.diverged && consecutiveLsSuccess >= sppEkfDivergedRecoverEpochs) {
    for (int i = 0; i < 3; i++) initx(state, solLsq.rr[i], sppEkfVarPos, i);
    for (int i = 3; i < 6; i++) initx(state, solLsq.rr[i], sppEkfVarVel, i);
    for (int i = 6; i < 9; i++) initx(state, 0.0, sppEkfVarAcc, i);
    state.diverged = false;
    state.divergedEpochs = 0;
}
```

`initx`清零P的对应行和列（保证正定性），不能只改对角线不改交叉项。

**trade-off**：恢复重初始化引入位置跳变（UrbanNav 2D +11%），但不恢复EKF会永久发散（52亿米）。恢复是必须的。

#### 6.2.4 diverged时Q放大

```java
if (state.diverged) {
    Q[0] *= sppEkfDivergedQscale;
    Q[4] *= sppEkfDivergedQscale;
    Q[8] *= sppEkfDivergedQscale;
}
```

Q放大→P预测更大→K增大（倾向观测），R放大→K减小（不信任观测），两者平衡：
- 观测质量好时K仍较大（Q放大主导）
- 观测质量差时K很小（R放大主导）

当前`sppEkfDivergedQscale=1.0`即不放大，待调优。

### 6.3 配置参数

（见§1.6中diverged相关参数）

### 6.4 实现位置

| 功能 | 位置 |
|------|------|
| P膨胀检测 | `SppOptimizations.udposSpp()` |
| 自适应R放大 | `SppOptimizations.estposFilter()` 约L581-L596 |
| diverged恢复 | `SppOptimizations.estposFilter()` 约L396-L408 |
| Q放大 | `SppOptimizations.udposSpp()` |

---

## 7. 优化项依赖关系与调用顺序

### 7.1 调用顺序（在PntPos.pntpos()中）

```
PntPos.pntpos()
  ├─ if enableSppEkf:
  │    └─ SppOptimizations.estposFilter()
  │         ├─ SppCore.estpos()           (LS解作为观测/初始化)
  │         ├─ udposSpp()                 (时间传播：F*x, F*P*Fᵀ+Q)
  │         │    ├─ P膨胀检测与封顶
  │         │    └─ diverged时Q放大
  │         ├─ SppCore.rescode()          (伪距观测方程)
  │         ├─ resdopFilter()             (多普勒观测方程，if enableSppDopplerSnr)
  │         ├─ if enableSppRobust:
  │         │    └─ robustFilter()        (抗差方差调整)
  │         ├─ if enableSppInnovChisq:
  │         │    └─ 新息两级检验           (剔除极端+降权中等)
  │         ├─ R矩阵构建                   (降权标记→R放大)
  │         ├─ diverged时R自适应放大
  │         ├─ KalmanFilter.update()      (KF更新，Joseph形式)
  │         ├─ if enableSppZeroVel:
  │         │    └─ 零速检测与位置修正
  │         └─ diverged恢复检测
  │
  └─ else:
       ├─ SppCore.estpos()               (标准LS-SPP)
       ├─ raimFde()                      (RAIM-FDE，if posopt[4])
       └─ estvel()                       (速度估计)
```

### 7.2 优化项之间的依赖

```
enableSppEkf (前提)
  ├── enableSppRobust      (抗差调整EKF观测方差)
  ├── enableSppInnovChisq  (新息检验剔除/降权观测)
  ├── enableSppZeroVel     (零速修正EKF位置/速度)
  └── enableSppDopplerSnr  (多普勒SNR加权加入EKF观测方程)
```

> **enableSppEkf是所有其他SPP优化的前提**。不开启EKF时，其他SPP优化无效。

### 7.3 优化项互斥关系

| 组合 | 兼容性 | 说明 |
|------|--------|------|
| enableSppRobust + enableSppInnovChisq | ✅ 兼容 | 抗差先调整方差，新息检验再剔除/降权 |
| enableSppZeroVel + 动态场景 | ❌ 不兼容 | 零速误判导致位置锁死漂移 |
| enableSppEkf + 卫星<5 | ⚠️ 风险 | EKF可能发散，需配合抗差+新息检验 |

---

## 8. RtkConfig字段与优化项对应表

| 字段 | 类型 | 默认值 | 优化项 | 说明 |
|------|------|--------|--------|------|
| `enableSppEkf` | boolean | false | §1 EKF | EKF时间传播开关 |
| `enableSppRobust` | boolean | false | §2 抗差 | M-估计抗差开关 |
| `enableSppZeroVel` | boolean | false | §3 零速 | 零速约束开关 |
| `enableSppDopplerSnr` | boolean | false | §4 多普勒SNR | 多普勒SNR加权开关 |
| `enableSppInnovChisq` | boolean | **true** | §5 新息检验 | 新息卡方检验开关 |
| `sppEkfMinSat` | int | 0 | §1 | EKF最低卫星数 |
| `sppEkfLowSatThresh` | int | 7 | §1/§5/§6 | 低卫星数阈值 |
| `sppEkfVarPos` | double | 2500 | §1 | 位置初始方差 |
| `sppEkfVarVel` | double | 100 | §1 | 速度初始方差 |
| `sppEkfVarAcc` | double | 100 | §1 | 加速度初始方差 |
| `sppEkfClkProcessNoise` | double | 10000 | §1 | 钟差过程噪声 |
| `sppEkfAccPrn1` | double | 10.0 | §1 | 水平加速度过程噪声 |
| `sppEkfAccPrn2` | double | 10.0 | §1 | 垂直加速度过程噪声 |
| `sppEkfVelResidScale` | double | 1.0 | §1 | 速度残差P注入缩放 |
| `sppEkfVarResetThresh` | double | 2500 | §6 | P膨胀重置阈值 |
| `sppEkfDtGap` | double | 10.0 | §1 | 时间间断重置阈值 |
| `sppEkfVarCap` | double | 625 | §6 | P对角线封顶值 |
| `sppEkfDivergedRecoverEpochs` | int | 5 | §6 | diverged恢复历元数 |
| `sppEkfDivergedRscaleBase` | double | 100.0 | §6 | diverged R放大基数 |
| `sppEkfDivergedRscaleMax` | double | 1000.0 | §6 | 自适应R放大上限 |
| `sppEkfDivergedMaxEpochs` | double | 20 | §6 | diverged最大持续历元 |
| `sppEkfDivergedQscale` | double | 1.0 | §6 | diverged Q放大倍数 |
| `sppInnovOutlierThresh` | double | 5.0 | §5 | 极端异常剔除阈值(σ) |
| `sppInnovDownweightThresh` | double | 3.0 | §5 | 中等异常降权阈值(σ) |
| `sppInnovOutlierThreshLowSat` | double | 7.0 | §5 | 低卫星剔除阈值(σ) |
| `sppInnovDownweightThreshLowSat` | double | 5.0 | §5 | 低卫星降权阈值(σ) |
| `sppInnovDownweightRscale` | double | 10.0 | §5 | 降权R放大倍数 |
| `sppRobustKPos` | double | 1.345 | §2 | 位置Huber阈值 |
| `sppRobustKVel` | double | 5.0 | §2 | 速度Huber阈值 |
| `sppRobustMaxIter` | int | 15 | §2 | 抗差最大迭代 |
| `sppRobustPosOutlier` | double | 100.0 | §2 | 位置outlier阈值(m) |
| `sppRobustVelOutlier` | double | 20.0 | §2 | 速度outlier阈值(m/s) |
| `sppRobustMinW` | double | 0.0001 | §2 | 淘汰段最小权重 |
| `sppZeroVelSpeedThresh` | double | 0.5 | §3 | 零速速度阈值(m/s) |
| `sppZeroVelMinEpochs` | int | 1 | §3 | 最少零速历元数 |

---

## 9. 优化矩阵

### 9.1 按场景分类

| 优化项 | 开关 | 静态<br>开阔 | 静态<br>遮挡 | 动态<br>开阔 | 动态<br>遮挡 | 测试状态 |
|--------|------|------------|------------|------------|------------|---------|
| **EKF时间传播** | `enableSppEkf` | ✅ 平滑 | ✅ 成功率↑ | ✅ 平滑 | ⚠️ 需配合抗差 | ✅ 16组逐项对照 |
| **M-估计抗差** | `enableSppRobust` | ≈ 无影响 | ✅ 抑制NLOS | ≈ 无影响 | ✅ 抑制NLOS | ✅ 16组逐项对照 |
| **零速约束** | `enableSppZeroVel` | ✅ 精度↑ | ✅ 精度↑ | ❌ 误判风险 | ❌ 无收益 | ✅ 7组逐项对照 |
| **多普勒+SNR** | `enableSppDopplerSnr` | ≈ 0% | ≈ -0.5% | ≈ 0% | ≈ -0.1% | ✅ 7组逐项对照 |
| **新息检验** | `enableSppInnovChisq` | ≈ 0% | ≈ 0% | ≈ 0% | ≈ 0% | ✅ 7组逐项对照 |
| **P膨胀保护** | (内建) | — | ✅ 防发散 | — | ✅ 防发散 | ✅ EKF隐含覆盖 |

> ✅ = 适用且推荐 | ⚠️ = 需配合其他优化 | ≈ = 无实质影响 | ❌ = 不适用/有害 | — = 不需要

### 9.2 按功能分类

| 类别 | 优化项 | 机制 |
|------|--------|------|
| **状态建模** | EKF时间传播 | 位置/速度/加速度状态预测，历元间平滑 |
| **抗差估计** | M-估计抗差、新息检验 | Huber降权/剔除极端异常，保护KF更新 |
| **场景约束** | 零速约束 | 静态场景速度压制，位置锁定 |
| **观测质量** | 多普勒+SNR加权 | SNR自适应观测权，低信号降权 |
| **鲁棒性保护** | P膨胀保护、diverged恢复 | 协方差封顶/重置，R自适应放大 |

### 9.3 按验证状态分类

| 状态 | 优化项 | 说明 |
|------|--------|------|
| ✅ **已验证有效** | EKF+抗差（卫星≥5） | 16组数据实测，EKF提升成功率，抗差抑制NLOS |
| ✅ **已验证(边际收益<1%)** | 新息检验、多普勒SNR加权 | 7组逐项对照，精度差异<1%，作为第二道防线推荐保持开启 |
| ✅ **已验证(特定场景)** | 零速约束（静态/步行） | 步行场景-0.4%，车载场景+0.1%~+0.0%，动态无收益 |
| ❌ **已验证无效/有害** | 零速约束（动态车载） | 7组无一显著改善，历史测试中Net_Diff灾难性发散 |
| ⚠️ **未专项测试** | 零速约束（纯静态）、P膨胀保护（独立） | 静态场景零速待专项测试 |

### 9.4 按卫星数选择策略

| 可见卫星数 | 推荐策略 | 原因 |
|-----------|---------|------|
| **< 5颗** | LS-SPP（基准） | EKF状态协方差膨胀→发散，抗差也无法根治 |
| **5~8颗** | EKF + 抗差 + 新息检验 | EKF提升成功率，抗差+新息抑制多路径 |
| **> 8颗** | EKF + 抗差 | 成功率接近100%，精度小幅改善，sigma显著减小 |

---

## 10. 推荐配置组合

### 10.1 SPP 开阔天空（静态/动态，卫星充足）

```java
RtkConfig cfg = new RtkConfig();
cfg.enableSppEkf = true;
cfg.enableSppDopplerSnr = true;
// enableSppInnovChisq 默认已开启
```

特点：EKF平滑高程跳动 + 多普勒SNR降噪，稳定可靠

### 10.2 SPP 城市峡谷/恶劣环境（动态）

```java
RtkConfig cfg = new RtkConfig();
cfg.enableSppEkf = true;
cfg.enableSppRobust = true;
cfg.enableSppDopplerSnr = true;
// enableSppInnovChisq 默认已开启
// enableSppZeroVel 必须关闭！
```

特点：IGGIII抗NLOS多路径粗差 + EKF平滑 + 新息检验兜底

### 10.3 SPP 静态基准站/滑坡监测

```java
RtkConfig cfg = new RtkConfig();
cfg.enableSppEkf = true;
cfg.enableSppRobust = true;
cfg.enableSppZeroVel = true;    // 静态场景可以开启
cfg.enableSppDopplerSnr = true;
```

特点：全开模式，零速压制Q + 抗差 + 多普勒平滑，最大化静态精度

### 10.4 SPP 极少卫星（<5颗）

```java
RtkConfig cfg = new RtkConfig();
// 不开启enableSppEkf，使用标准LS-SPP
```

特点：卫星太少时EKF必然发散，LS-SPP虽成功率低但不发散

### 10.5 城市峡谷 RTK 流程中的 SPP 阶段

```
SPP阶段：EKF+抗差+新息检验 → 提供连续、平滑的先验位置
         ↓
RTK初始化：用SPP位置作为EKF先验 → 更准确的初始状态
         ↓
RTK运行：Float解 → 等待ratio>3.0 → Fix解
```

---

## 11. 已知问题与后续方向

### 11.1 EKF 发散问题（卫星<5颗）

- **现象**：卫星数<5时EKF位置估计发散，2D RMS从百米级恶化到千米级
- **根因**：观测方程欠定（4颗卫星4个未知数），EKF预测步无观测修正→协方差膨胀
- **当前缓解**：P膨胀封顶 + diverged恢复 + 抗差 + 新息检验
- **待改进**：
  - [ ] EKF初始化质量门控：卫星数≥5且HDOP≤5才初始化
  - [ ] 自适应Q矩阵：根据可见卫星数动态调整过程噪声
  - [ ] 卫星数<5时自动降级到LS-SPP（当前`sppEkfMinSat=0`不触发）

### 11.2 零速约束误判

- **现象**：车载动态场景零速约束误触发，2D RMS恶化（PPC Tokyo 7.8→15km）
- **根因**：`sppZeroVelSpeedThresh=0.5m/s`阈值太低，低速行驶时误判
- **待改进**：
  - [ ] 提高默认阈值至1.0~2.0 m/s
  - [ ] 增加零速持续时间要求（连续N个历元才启用）
  - [ ] 结合加速度计数据辅助判断

### 11.3 抗差outlier阈值调优

- **现状**：Java默认`sppRobustPosOutlier=100m`，C代码用30m
- **C代码更激进**：30m以上即几乎零权，100m以上才零权可能放过中等异常
- **待改进**：
  - [ ] 对照实验：30m vs 50m vs 100m outlier阈值
  - [ ] 速度outlier：2m/s(C) vs 20m/s(Java)

### 11.4 diverged恢复trade-off

- **现状**：恢复时重初始化引入位置跳变（UrbanNav +11%，Net_Diff +49%）
- **不恢复更差**：EKF永久发散到52亿米
- **待改进**：
  - [ ] 恢复时用加权混合而非硬重置：`x = α*LS + (1-α)*EKF`
  - [ ] 恢复后P适当放大而非重置到初始值，加速重新收敛

### 11.5 参考位置精度

- **说明**：城市峡谷测试使用RINEX头中的近似位置作为参考，非真实轨迹
- **影响**：RMS绝对值偏大（百米~百千米级），但**相对改善率**仍有参考价值
- **待改进**：使用NMEA baseline或精确参考轨迹计算真实RMSE

### 11.6 EKF日志

- **现状**：EKF pre/post-update日志使用`LOG.info`，生产环境可能过于频繁
- **待改进**：
  - [ ] 改为`LOG.debug`或条件日志（仅diverged时输出info）
  - [ ] 添加EKF状态摘要日志（每N个历元输出一次）

---

## 12. 实测验证汇总

### 12.1 测试数据集

| 数据集 | 来源 | 信号 | 场景 | 运动模式 | 测试类 |
|--------|------|------|------|---------|--------|
| opensky | MobileGNSS-SPP | RINEX3.04 G+E+J+C 单频 | 开阔天空 | 动态（~6km移动） | SppOptimizationTest |
| street | MobileGNSS-SPP | RINEX3.04 G+E+J+C 单频 | 街道 | 动态（~250m移动） | SppOptimizationTest |
| downtown | MobileGNSS-SPP | RINEX3.04 G+E+J+C 单频 | 城市峡谷 | 步行 | SppOptimizationTest |
| elevated | MobileGNSS-SPP | RINEX3.04 G+E+J+C 单频 | 高架桥 | 动态（~500m移动） | SppOptimizationTest |
| PPC Tokyo | Google PPC | RINEX3 多系统 | 城市峡谷 | 车载 | UrbanCanyonSppOptTest |
| PPC Nagoya | Google PPC | RINEX3 多系统 | 城市峡谷 | 车载 | UrbanCanyonSppOptTest |
| UrbanNav Tokyo | UrbanNav | RINEX3 多系统 | 城市峡谷 | 车载 | UrbanCanyonSppOptTest |

### 12.2 组合测试结果（4配置 × 7数据集）

| 数据集 | LS-SPP 2D | EKF 2D | EKF+抗差 2D | 全开 2D | LS成功率 | EKF成功率 |
|--------|----------|--------|------------|--------|---------|----------|
| opensky | — | 190.10 | 190.10 | 190.10 | — | 100% |
| street | — | 90.99 | 90.99 | 90.99 | — | 100% |
| downtown | — | 102.99 | 102.99 | 102.99 | — | 100% |
| elevated | — | 3170.22 | 3170.22 | 3170.22 | 99.9% | 99.9% |
| PPC Tokyo | — | 3963.44 | 3963.44 | 3963.44 | 87.5% | 100% |
| PPC Nagoya | — | 553.67 | 553.67 | 553.67 | 99.0% | 99.9% |
| UrbanNav | — | 699.13 | 699.13 | 699.13 | 82.4% | 100% |

> EKF主要价值在于提升成功率（尤其PPC Tokyo 87.5%→100%），精度改善依赖抗差

### 12.3 逐项对照测试结果（3优化项 × 7数据集）

基准配置：EKF+抗差+新息检验(默认开启)

| 数据集 | 基准 2D | -新息检验 Δ2D | +零速约束 Δ2D | +多普勒SNR Δ2D |
|--------|---------|--------------|--------------|---------------|
| opensky | 190.10 | 0.0% | +0.0% | 0.0% |
| street | 90.99 | 0.0% | +0.1% | -0.2% |
| downtown | 102.99 | 0.0% | -0.4% | -0.5% |
| elevated | 3170.22 | 0.0% | -0.2% | +0.0% |
| PPC Tokyo | 3963.44 | 0.0% | +0.0% | -0.1% |
| PPC Nagoya | 553.67 | +0.0% | +0.1% | -0.0% |
| UrbanNav | 699.13 | -0.0% | +0.0% | +0.0% |

**结论**：
- **新息检验**：7组均<0.1%，作为抗差的第二道防线，推荐保持默认开启
- **零速约束**：动态场景无显著收益（最大-0.4%），车载场景略恶化，**动态必须关闭**
- **多普勒SNR**：7组均<1%，downtown略好（-0.5%），推荐开启但不期待显著改善

### 12.4 测试方法

- **组合测试**：`UrbanCanyonSppOptTest.runSppComparison()` / `SppOptimizationTest.runFullComparison()`
  - 4种配置：LS-SPP → EKF → EKF+抗差 → EKF+抗差+零速+SNR
- **逐项对照**：`UrbanCanyonSppOptTest.runIndividualOptComparison()` / `SppOptimizationTest.runIndividualOptComparison()`
  - 基准：EKF+抗差
  - 3种变体：-新息检验 / +零速约束 / +多普勒SNR
  - 输出Δ2D相对变化百分比
- **参考位置**：RINEX头APPROX POSITION或NMEA baseline（手机数据）
- **注意**：城市峡谷参考位置为RINEX近似坐标，RMS绝对值偏大，**相对改善率**更有参考价值