# Java版RTK额外优化项详细文档

本文档记录Java版RTKLIB中**RTKLIB C版没有的**额外优化项。
这些优化通过 `RtkConfig` 独立开关控制，默认全部关闭，不影响默认行为。

与C版同功能的实现差异（如矩阵存储、状态向量表示等）见 `RTKLIB_Differences.md`。

---

## 目录

1. [滑动窗自适应Q矩阵](#1-滑动窗自适应q矩阵enableadaptiveq)
2. [模糊度子集锚固](#2-模糊度子集锚固enableambanchor)
3. [大气参数自适应冻结](#3-大气参数自适应冻结atmfrozennsthresh)
4. [IGGIII抗差估计](#4-iggiii抗差估计enableiggiii)
5. [SNR中值参考星选择](#5-snr中值参考星选择enablesnrmedian)
6. [PAR参考星重选](#6-par参考星重选enableparrefreselect)
7. [电离层/对流层梯度参数估计](#7-电离层对流层梯度参数估计enableionotropgradient)
8. [优化项依赖关系与调用顺序](#8-优化项依赖关系与调用顺序)
9. [基站稳定性监测](#9-基站稳定性监测basestationmonitor)
10. [逐级模糊度固定](#10-逐级模糊度固定enablecascadear)
11. [精细化残差编辑与周跳检测](#11-精细化残差编辑与周跳检测enableresidualedit)
12. [部分模糊度固定](#12-部分模糊度固定enablepartialar)
13. [Bootstrapping成功率联合判据](#13-bootstrapping成功率联合判据enablebootstrapping)
14. [BDS卫星码偏差改正](#14-bds卫星码偏差改正enablebdscodebias)
15. [参数类型级自适应过程噪声](#15-参数类型级自适应过程噪声enableparamtypenoise)
16. [段内位置标准差过滤](#16-段内位置标准差过滤enablesegmentsigmafilter)
17. [优化矩阵](#17-优化矩阵)

---

## 1. 滑动窗自适应Q矩阵（enableAdaptiveQ）

> **⚠️ 适用场景**: 动态模式（`PMODE_KINEMA` 或 `dynamics=1`）下有效。
> 静态模式（`PMODE_STATIC`）下 `dynamics=0`，状态向量无速度/加速度分量，
> Q矩阵仅含位置过程噪声，缩放qScale对已收敛的模糊度无实质影响。
> 测试数据表明：static模式下开启自适应Q，FIX率无变化（81.0%→81.0%）；
> kinema模式下效果待验证。

### 1.1 问题背景

C版RTKLIB使用固定过程噪声 `Q = prn[3]² * |tt|`，不区分静态/动态场景。
静态场景下Q过大导致滤波平滑不足，动态场景下Q过小导致跟踪滞后。
实际应用（如滑坡监测）需要静态时高精度、动态时快速响应。

### 1.2 解决方案

环形滑动窗计算位置增量RMS，通过Sigmoid映射到Q缩放因子α，
实现静态时噪声压制、动态时噪声放大。

### 1.3 核心算法

```
输入: 当前位置 x[0..2] + rb[0..2], 上一历元位置 xOld[0..2]

1. 计算位置增量:
   posInc = ||curPos - xOld||

2. 写入环形缓冲区:
   posWin[winIdx] = posInc
   winIdx = (winIdx + 1) % winSize
   winCnt = min(winCnt + 1, winSize)

3. 计算位置增量RMS:
   sigmaPos = sqrt(var(posWin[0..winCnt-1]))

4. Sigmoid映射:
   if sigmaPos ≤ staticThresh(0.001):
       scale = scaleMinStatic(0.01)       // 强压制
   elif sigmaPos ≥ dynamicThresh(0.05):
       scale = scaleMaxDynamic(5.0)        // 放大
   else:
       t = (sigmaPos - staticThresh) / (dynamicThresh - staticThresh)
       sigmoid = 1 / (1 + exp(-10*(t-0.5)))
       scale = scaleMinStatic + sigmoid * (scaleMaxDynamic - scaleMinStatic)

5. 卫星数修正:
   nsFactor = min(ns / nsRef(8), 1.5)
   scale *= nsFactor

6. PDOP修正:
   pdopFactor = min(pdopRef(3) / PDOP, 2.0)
   scale *= pdopFactor

7. 零速检测修正:
   if isZeroVelocity():
       scaleMin = scaleMinZeroVel(0.1)    // 更强压制
   else:
       scaleMin = scaleMinMoving(0.5)
   scale = clamp(scale, scaleMin, scaleMax(2.0))

8. 协方差保护:
   if trace(P[0..8]) > traceThresh(1e6):
       scale = 1.0                         // 协方差发散时不干预

9. 输出:
   rtk.qScale = scale
```

### 1.4 零速检测算法

```
1. 速度检测 (dynamics模式):
   speed = ||x[3..5]||
   if speed ≥ zeroVelSpeedThresh(0.5 m/s) → 非零速，重置计数

2. 位置差分检测 (所有模式):
   posDiff = ||curPos - prevPosForZeroVel||
   if posDiff ≥ zeroVelPosDiffThresh(0.05 m) → 非零速，重置计数

3. 连续性判断:
   consecutiveZeroVelEpochs++
   if consecutiveZeroVelEpochs ≥ zeroVelConsecutiveEpochs(3) → 零速
```

### 1.5 Q缩放应用位置

`RtkCore.udstate()` → `udpos()` 中：
```java
if (rtk.rtkConfig.enableAdaptiveQ && rtk.qScale != 1.0) {
    qh *= rtk.qScale * rtk.qScale;
    qv *= rtk.qScale * rtk.qScale;
}
```

缩放因子作用于位置过程噪声 `qh`（水平）和 `qv`（垂直），
因为是方差缩放所以用 `qScale²`。

### 1.6 配置参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `enableAdaptiveQ` | false | 总开关 |
| `adaptiveQNsRef` | 8.0 | 卫星数参考值 |
| `adaptiveQPdopRef` | 3.0 | PDOP参考值 |
| `adaptiveQScaleMinZeroVel` | 0.1 | 零速时最小缩放 |
| `adaptiveQScaleMax` | 2.0 | 最大缩放 |
| `adaptiveQScaleMinMoving` | 0.5 | 运动时最小缩放 |
| `zeroVelSpeedThresh` | 0.5 | 零速速度阈值 (m/s) |
| `zeroVelPosDiffThresh` | 0.05 | 零速位置差阈值 (m) |
| `zeroVelConsecutiveEpochs` | 3 | 零速连续历元数 |
| `zeroVelStdThresh` | 0.2 | 零速标准差阈值 |
| `adaptiveQTraceThresh` | 1e6 | 协方差迹保护阈值 |
| `adaptiveQWinSize` | 50 | 滑动窗大小 |
| `adaptiveQStaticThresh` | 0.001 | 静态RMS阈值 (m) |
| `adaptiveQDynamicThresh` | 0.05 | 动态RMS阈值 (m) |
| `adaptiveQScaleMinStatic` | 0.01 | 静态最小缩放 |
| `adaptiveQScaleMaxDynamic` | 5.0 | 动态最大缩放 |

### 1.7 新增字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `Rtk.xOld[3]` | double[] | 上一历元绝对位置 |
| `Rtk.posWin[100]` | double[] | 位置增量环形缓冲区 |
| `Rtk.winIdx` | int | 环形缓冲区写入指针 |
| `Rtk.winCnt` | int | 环形缓冲区有效数据数 |
| `Rtk.qScale` | double | 当前Q缩放因子 |
| `Rtk.prevPosForZeroVel[3]` | double[] | 零速检测用上一位置 |
| `Rtk.consecutiveZeroVelEpochs` | int | 连续零速历元计数 |

### 1.8 实现位置

| 函数 | 文件 | 说明 |
|------|------|------|
| `computeQScale()` | RtkOptimizations.java | 计算Q缩放因子 |
| `isZeroVelocity()` | RtkOptimizations.java | 零速检测 |
| `udpos()` | RtkCore.java | 应用Q缩放 |

---

## 2. 模糊度子集锚固（enableAmbAnchor）

### 2.1 问题背景

C版Fix-and-Hold策略的核心缺陷：LAMBDA搜索失败时，所有模糊度
（包括已稳定固定的）都可能被重置，导致解跳变。实际场景中，
某些模糊度已经连续固定数百历元，其整数值高度可信，
不应因少数卫星信号恶化而被牵连。

### 2.2 解决方案

将连续固定超过阈值的模糊度标记为"锚固"，锚固模糊度：
- 不参与LAMBDA搜索（直接取整作为固定值）
- 在holdamb中管理锚固计数
- 非固定解时重置未固定卫星的锚固计数

### 2.3 核心算法

#### 2.3.1 resamb_LAMBDA() 中的锚固分离

```
输入: ddidx() 产生的双差索引 ix[0..2*nb-1]
      ix[2*i]   = 参考星模糊度状态索引 (在 rtk.x 中的位置)
      ix[2*i+1] = 非参考星模糊度状态索引

步骤1: 分类
  对每个双差对 i:
    satIdx = (ix[2*i+1] - na) % MAXSAT    // 从状态索引反推卫星号
    f      = (ix[2*i+1] - na) / MAXSAT     // 从状态索引反推频点
    globalIdx = satIdx * nf + f
    if ambAnchored[globalIdx]:
        anchorMap[anchorCount++] = i        // 已锚固，记入锚固组
    else:
        freeMap[freeCount++] = i            // 未锚固，记入自由组

步骤2: 仅对自由组执行LAMBDA搜索
  nbLambda = freeCount
  构建 y[], Qb[], Qab[] 时只使用 freeMap[] 对应的 ix 条目
  LAMBDA搜索维度 = nbLambda (而非 nb)

步骤3: 锚固双差的固定值
  bias[idx] = Math.round(yFull[idx])       // 浮点双差值直接取整
  不需要LAMBDA搜索，因为锚固模糊度协方差极小

步骤4: 特殊情况 — 所有模糊度都已锚固 (freeCount == 0)
  直接返回成功，ratio = 999.9
  所有模糊度用浮点值取整作为固定值
  restamb() 中 xa[index[0]] 保持浮点值，xa[index[j]] = xa[index[0]] - round(x[0]-x[j])

步骤5: LAMBDA成功后
  bias[] 数组包含所有 nb 个双差的固定值:
    自由组: 来自 LAMBDA 结果 b[]
    锚固组: 来自 Math.round(yFull[])
  restamb() 使用完整的 bias[] 数组构建 xa
```

#### 2.3.2 holdamb() 中的锚固计数管理

```
步骤1: 固定解时 (sol.stat == SOLQ_FIX)
  对每颗卫星 i, 每个频点 f:
    if fix[f] > 0:                         // 该频点参与了解算
        ambAnchorCount[i*nf+f]++
        if ambAnchorCount[i*nf+f] ≥ ambAnchorMinFixCount(100):
            ambAnchored[i*nf+f] = true     // 标记为锚固

步骤2: 非固定解时 (sol.stat != SOLQ_FIX)
  对每颗卫星 i, 每个频点 f:
    if fix[f] ≤ 0:                         // 该频点未参与解算
        ambAnchorCount[i*nf+f] = 0         // 重置计数
  注意: 不取消已锚固标志 ambAnchored，保护长期稳定模糊度

步骤3: holdamb 的 Kalman 约束
  所有模糊度（包括锚固的）仍然参与 hold 约束
  使用统一的 varholdamb 协方差
  锚固的协方差压制效果通过 LAMBDA 跳过实现，而非修改 R 矩阵
```

### 2.4 与C版Fix-and-Hold的对比

| 环节 | C版行为 | Java版锚固增强 |
|------|--------|---------------|
| `resamb_LAMBDA()` | 所有双差参与LAMBDA搜索 | 分离锚固/自由组，仅自由组搜索 |
| `restamb()` | 使用LAMBDA结果构建xa | 使用混合结果（LAMBDA+取整） |
| `holdamb()` | 统一varholdamb约束 | 增加锚固计数管理，非固定解重置计数 |
| `ddidx()` | 不区分锚固状态 | 不变（锚固分离在resamb_LAMBDA中进行） |
| LAMBDA失败 | 所有模糊度回到浮点 | 锚固模糊度保持固定值 |

### 2.5 配置参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `enableAmbAnchor` | false | 总开关 |
| `ambAnchorMinFixCount` | 100 | 连续固定多少历元后锚固 |
| `ambAnchorVar` | 1e-9 | 锚固模糊度目标协方差（当前未直接使用） |

### 2.6 新增字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `Rtk.ambAnchored[MAXSAT*NF]` | boolean[] | 每颗卫星每个频点是否已锚固 |
| `Rtk.ambAnchorCount[MAXSAT*NF]` | int[] | 每颗卫星每个频点的连续固定计数 |

### 2.7 已知限制

1. **锚固协方差未显式压制**: 当前通过跳过LAMBDA搜索等效实现锚固效果，
   但在Kalman滤波的时间更新中，锚固模糊度的协方差仍会按正常过程噪声增长。
   如果需要在滤波层面也保护锚固状态，需在 `udbias()` 中对锚固模糊度
   施加额外约束（将协方差压制到 `ambAnchorVar`）。

2. **锚固不可逆**: 一旦 `ambAnchored[globalIdx] = true`，即使后续
   该卫星信号质量恶化，也不会取消锚固。仅在卫星完全失锁（fix[f] ≤ 0）
   时重置计数，但不取消锚固标志。极端情况下可能需要手动重置。

3. **梯度参数兼容**: 启用 `ionoGradient` 时，II() 索引每星3个参数，
   锚固映射通过 `(ix[2*i+1] - na) % MAXSAT` 计算卫星索引，
   与梯度模式兼容（MAXSAT取模确保得到正确的卫星号）。

### 2.8 实现位置

| 函数 | 文件 | 说明 |
|------|------|------|
| `resamb_LAMBDA()` | RtkCore.java | 锚固/自由分离，LAMBDA搜索仅用自由组 |
| `holdamb()` | RtkCore.java | 锚固计数管理，非固定解重置 |

---

## 3. 大气参数自适应冻结（atmFrozenNsThresh） 🔭长基线

> **适用场景**: 长基线（>10km），启用电离层/对流层估计时。
> 短基线不估计大气参数，此优化无效果。

### 3.1 问题背景

C版RTKLIB无论卫星数多少，每历元都更新电离层/对流层过程噪声。
当可用卫星数很少时（如城市峡谷），法方程病态，大气参数的过程噪声
会导致虚假的坐标跳变。

### 3.2 解决方案

当可用卫星数低于阈值时，跳过 `udion()` 和 `udtrop()` 的过程噪声更新，
冻结大气参数状态，防止少星时的参数漂移。

### 3.3 核心算法

```
udion() 入口:
  if atmFrozenNsThresh > 0 && ns < atmFrozenNsThresh:
      return    // 跳过整个udion，不更新电离层参数

udtrop() 入口:
  if atmFrozenNsThresh > 0 && ns < atmFrozenNsThresh:
      return    // 跳过整个udtrop，不更新对流层参数
```

### 3.4 配置参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `atmFrozenNsThresh` | 7 | 卫星数低于此值时冻结大气参数（0=关闭） |

### 3.5 实现位置

| 函数 | 文件 | 说明 |
|------|------|------|
| `udion()` | RtkCore.java | 入口检查 ns < atmFrozenNsThresh 时 return |
| `udtrop()` | RtkCore.java | 入口检查 ns < atmFrozenNsThresh 时 return |

---

## 4. IGGIII抗差估计（enableIggiii）

### 4.1 问题背景

C版RTKLIB使用标准最小二乘，所有观测等权处理。
实际场景中存在多路径、信号遮挡等粗差，标准最小二乘对粗差敏感，
可能导致模糊度重置和坐标跳变。

### 4.2 解决方案

基于IGGIII（Institute of Geodesy and Geophysics III）等价权函数，
根据标准化残差动态降权，抑制粗差对Kalman滤波的影响。

### 4.3 核心算法

```
输入: v[] (双差残差), H[] (设计矩阵), R[] (观测噪声), P[] (状态协方差)

步骤1: 计算 H*P*H' 对角线 diag[]
  diag[i] = H[i,*] * P * H[i,*]^T    (预测残差方差)

步骤2: 计算标准化残差
  对每个观测 i:
    sigma = sqrt(R[i,i])
    predVar = max(diag[i], 1e-30)
    innovation = |v[i]| / sqrt(predVar + sigma²)

步骤3: IGGIII 等价权
  innovation ≤ K0(1.5)           → w = 1.0              (正常段)
  K0 < innovation ≤ K1(3.0)     → w = K0 / innovation  (可疑段，降权)
  innovation > K1(3.0)          → w = minW(1e-4)       (淘汰段，几乎零权)

步骤4: 低高度角卫星额外惩罚
  if el < lowElMask(10°) 且 innovation > lowElNormThresh(2.5):
      w = min(w, lowElW(0.01))

步骤5: 多频一致性惩罚
  对每颗卫星，收集所有频点的最小权重 minSatW
  if minSatW < multiFreqW(0.01):
      该星所有频点权重不超过 multiFreqW
  目的: 一个频点异常时，其他频点也降权，保持多频一致性

步骤6: 修改观测噪声
  R[i,j] /= w[i]   (等效于对角加权，权越小噪声越大)
```

### 4.4 数学原理

IGGIII等价权函数是中国科学院测量与地球物理研究所提出的抗差估计方法。
其核心思想是将观测分为三段：

- **正常段** (|ṽ| ≤ K0): 保留全部信息，权重1.0
- **可疑段** (K0 < |ṽ| ≤ K1): 按比例降权，w = K0/|ṽ|
- **淘汰段** (|ṽ| > K1): 几乎零权，w → 0

其中 ṽ 是标准化残差，K0 和 K1 是根据统计检验确定的阈值。
K0 = 1.5 对应约87%置信区间，K1 = 3.0 对应约99.7%置信区间。

### 4.5 配置参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `enableIggiii` | false | 总开关 |
| `iggiiiK0` | 1.5 | 正常段上界 |
| `iggiiiK1` | 3.0 | 可疑段上界（淘汰段起始） |
| `iggiiiMinW` | 1e-4 | 淘汰段最小权重 |
| `iggiiiLowElMask` | 10° (rad) | 低高度角阈值 |
| `iggiiiLowElNormThresh` | 2.5 | 低高度角降权残差阈值 |
| `iggiiiLowElW` | 0.01 | 低高度角权重上限 |
| `iggiiiMultiFreqW` | 0.01 | 多频一致性权重上限 |
| `iggiiiLowElExtraIterMask` | 15° (rad) | 额外迭代低高度角阈值（预留） |

### 4.6 实现位置

| 函数 | 文件 | 说明 |
|------|------|------|
| `applyIggiii()` | RtkOptimizations.java | 完整IGGIII实现 |
| `computeHPHtDiagNative()` | RtkOptimizations.java | 计算H*P*H'对角线 |
| `relpos()` | RtkCore.java | ddres之后、filter之前调用 |

### 4.7 HPHt对角线计算：EJML版本与Native版本

IGG-III需要计算新息协方差 `S = R + HPHᵀ` 的对角线元素，用于标准化新息。

**当前使用EJML版本** `computeHPHtDiag()`：
```java
// EJML实现：完整矩阵乘法后提取对角线
SimpleMatrix Hmat = MatrixUtil.createMatrix(H, nv, nx);
SimpleMatrix Pmat = MatrixUtil.createMatrix(P, nx, nx);
SimpleMatrix HPHt = Hmat.mult(Pmat).mult(Hmat.transpose());
for (int i = 0; i < nv; i++) diag[i] = HPHt.get(i, i);
```

**保留的Native版本** `computeHPHtDiagNative()`：
```java
// 手写数组运算：只算对角线，跳过非对角线元素
// Step1: PH[i,j] = Σ_k P[i,k] * H[j,k]
// Step2: diag[i] = Σ_k H[i,k] * PH[k,i]
```

| 版本 | 复杂度 | 优点 | 缺点 |
|------|--------|------|------|
| EJML（当前） | O(nx²×nv + nx×nv²) | 代码简洁，与项目风格一致 | 计算了nv²-nv个无用元素 |
| Native | O(nx²×nv + nx×nv) | 无冗余计算 | 手写循环，与项目EJML风格不一致 |

**性能差异**：典型RTK场景（nx=60, nv=60），FLOP浪费约49%，但绝对耗时在微秒级，1Hz RTK可忽略。
若需10~20Hz或嵌入式场景，可切换到Native版本。

---

## 5. SNR中值参考星选择（enableSnrMedian）

### 5.1 问题背景

C版RTKLIB参考星仅按高度角选择，不考虑信号质量。
低SNR卫星被选为参考星时，双差观测质量差，影响模糊度固定。

### 5.2 解决方案

计算各频点SNR中值，用于改进参考星选择和观测权重。
SNR中值作为该频点的信号质量基准，用于：
- 观测方差计算时用SNR中值替代固定参考值，弱信号卫星方差增大
- 参考星选择时综合考虑高度角和SNR中值接近度

### 5.3 核心算法

#### 5.3.1 SNR中值计算

```
对每个频点 f:
  1. 筛选有效卫星:
     el > snrMedianMinEl(10°)
     lockTime > snrMedianMinLockTime(10s)
     SNR > snrMedianAbsMin(20 dB-Hz)

  2. 计算中值:
     if 有效卫星数 ≥ snrMedianMinSatsForFallback(3):
         取SNR中值
     else:
         使用 snrMedianFallbackPhaseRef(40 dB-Hz) 作为默认

  3. 存储:
     rtk.snrMedian[f] = 中值
```

#### 5.3.2 观测方差调整

```
varerr() 中:
  snrRef = opt.err[5]                          // 默认: 固定参考SNR
  if enableSnrMedian && snrMedian[frq] 有效:
      snrRef = rtk.snrMedian[frq]              // 替换: 中值参考SNR

  var += e² * (10^(0.1*(snrRef - snr_rover)) + 10^(0.1*(snrRef - snr_base)))
```

SNR低于中值的卫星，`snrRef - snr_rover` 更大，方差更大，等效降权。

#### 5.3.3 参考星选择

```
ddres() 中参考星选择:
  if enableSnrMedian && snrMedian[frq] 有效:
      snrScore = 1 - |snr_j - snrMedian| / max(snrMedian, 1)
      score = el_j + snrScore * snrMedianKPhase
      选 score 最大的卫星为参考星
  else:
      选高度角最大的卫星为参考星（原始策略）
```

评分策略：高度角为主（弧度值约0~1.57），SNR中值接近度为辅（0~1），
`snrMedianKPhase`(0.5) 控制SNR权重。SNR越接近中值的卫星评分越高，
避免选择SNR异常（过高或过低）的卫星作为参考星。

### 5.4 配置参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `enableSnrMedian` | false | 总开关 |
| `snrMedianMinEl` | 10° (rad) | 最低高度角 |
| `snrMedianMinLockTime` | 10.0 | 最小锁定时间 (s) |
| `snrMedianWindowSize` | 20 | 历史窗口大小（预留） |
| `snrMedianKCode` | 2.0 | 伪距SNR缩放因子（预留） |
| `snrMedianKPhase` | 0.5 | 载波SNR缩放因子（预留） |
| `snrMedianMinSnr` | 25.0 | 最低SNR (dB-Hz) |
| `snrMedianInvalidVar` | 1e6 | 无效SNR方差（预留） |
| `snrMedianMinSatsForFallback` | 3 | 回退到默认值的最少卫星数 |
| `snrMedianFallbackCodeRef` | 35.0 | 伪距默认参考SNR |
| `snrMedianFallbackPhaseRef` | 40.0 | 载波默认参考SNR |
| `snrMedianAbsMin` | 20.0 | 绝对最低SNR |

### 5.5 新增字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `Rtk.snrMedian[NFREQ]` | double[] | 各频点SNR中值 |
| `Rtk.snrMedianHistory[NFREQ][20]` | double[][] | SNR中值历史（预留） |
| `Rtk.snrMedianHistoryCount` | int | 历史计数（预留） |

### 5.6 实现位置

| 函数 | 文件 | 说明 |
|------|------|------|
| `computeSnrMedian()` | RtkOptimizations.java | SNR中值计算 |
| `varerr()` | RtkCore.java | 用snrMedian替代opt.err[5]作为SNR参考值 |
| `ddres()` | RtkCore.java | 参考星选择综合评分（高度角+SNR中值接近度） |
| `relpos()` | RtkCore.java | 在udstate之前调用computeSnrMedian |

---

## 6. PAR参考星重选（enableParRefReselect）

### 6.1 问题背景

C版RTKLIB的 `ddidx()` 中参考星按高度角选择，选择后不再变化。
当参考星信号质量下降（如多路径、低高度角）时，整个双差组质量恶化，
LAMBDA搜索可能失败。此时如果能重选参考星，可能找到更好的双差组合。

### 6.2 解决方案

`buildParIndex()` 替代 `ddidx()` 的参考星选择逻辑，实现PAR
（Partial Ambiguity Resolution）参考星重选：当LAMBDA ratio不足时，
排除贡献最大的卫星并重选参考星。

### 6.3 核心算法

```
buildParIndex() 替代 ddidx():

步骤1: 按系统分组选择参考星
  对每个系统 m (GPS/GLO/GAL/BDS/SBS/QZS):
    对每个频点 f:
      a. 遍历 MAXSAT 个卫星，找第一个满足条件的作为参考星:
         - x[IB(sat,f)] != 0 (状态已初始化)
         - testSys(sys, m) (属于当前系统)
         - vsat[f] != 0 (当前历元有效)
         - lock[f] >= 0 (锁定足够)
         - slip & HALFC == 0 (无半周跳变)
         - azel[1] >= elmaskar (高度角足够)
         - 不在排除列表中
      b. 标记参考星: fix[f] = 2

步骤2: 构建双差对
  对每个非参考星:
    if 满足固定条件:
        ix[nb*2] = refI, ix[nb*2+1] = j
        fix[f] = 2
    elif 在排除列表中:
        fix[f] = 1 (标记但不参与)
    else:
        fix[f] = 1 (不满足条件)

步骤3: PAR重选机制
  if 上一历元 ratio < thres 且 nb_ar >= mindropsats:
      排除贡献最大的卫星
      parConsecutiveReselectCount++
  if parConsecutiveReselectCount > parMaxConsecutiveReselect(3):
      重置排除列表，回退到 ddidxFallback()

步骤4: 排除卫星管理
  parExcludedSats[] 记录被排除的卫星号
  parExcludedSatCount 记录排除数量
  下一历元恢复排除（非永久排除）
```

### 6.4 与C版ddidx()的对比

| 环节 | C版 ddidx() | Java版 buildParIndex() |
|------|-------------|----------------------|
| 参考星选择 | 第一个满足条件的（按MAXSAT顺序） | 同上，但排除列表中的卫星跳过 |
| 排除机制 | 无 | parExcludedSats[] 排除贡献差的卫星 |
| 重选触发 | 无 | ratio不足时触发 |
| 回退策略 | 无 | 连续重选超限后回退到ddidxFallback() |
| fix[f]标记 | 0/1/2 | 0/1/2，含义相同 |

### 6.5 配置参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `enableParRefReselect` | false | 总开关 |
| `parElMask` | 15.0 | PAR参考星最低高度角 (度) |
| `parMaxConsecutiveReselect` | 3 | 最大连续重选次数 |

### 6.6 新增字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `Rtk.parConsecutiveReselectCount` | int | 连续重选计数 |
| `Rtk.parExcludedSats[MAXSAT]` | int[] | 被排除的卫星号列表 |
| `Rtk.parExcludedSatCount` | int | 排除卫星数量 |
| `Rtk.parPrevRefSat[NFREQ]` | int[] | 上一历元各频点参考星 |

### 6.7 实现位置

| 函数 | 文件 | 说明 |
|------|------|------|
| `buildParIndex()` | RtkOptimizations.java | PAR参考星选择和排除 |
| `ddidxFallback()` | RtkOptimizations.java | 关闭开关时的回退方案 |
| `ddidx()` | RtkCore.java | 根据开关选择调用 |
| `manage_amb_LAMBDA()` | RtkCore.java | PAR重选触发逻辑 |

### 6.8 ⚠️ 已知代码问题：`anyRefReselect` 死代码（已修复 2026-07-19）

`buildParIndex()` 中 `anyRefReselect` 变量原先始终为 `false`，PAR重选机制从未触发。

**修复**: 在参考星与上一历元不同时设置 `anyRefReselect = true`：
```java
int newRefSat = (refI - k) + 1;
if (rtk.parPrevRefSat[f] > 0 && rtk.parPrevRefSat[f] != newRefSat) {
    anyRefReselect = true;
}
rtk.parPrevRefSat[f] = newRefSat;
```

同时简化了 `buildParIndex()` 的参数签名，移除未使用的 `sat`, `ns`, `nf` 参数，
`ddidx()` 调用从 `buildParIndex(rtk, null, 0, 0, ix, gps, glo, sbs)` 改为
`buildParIndex(rtk, ix, gps, glo, sbs)`。

---

## 7. 电离层/对流层梯度参数估计（enableIonoTropGradient） 🔭长基线

> **适用场景**: 长基线（>20km），电离层空间不均匀性显著时。
> 短基线（<10km）电离层误差高度相关，双差可消除，梯度参数无意义。

### 7.1 问题背景

C版RTKLIB 2.5.0已有 `ionoGradient` 功能，但通过 `PrcOpt.ionoGradient` 控制，
与其他优化项的 `RtkConfig` 管理体系不一致。Java版将其纳入 `RtkConfig` 统一管理，
并添加梯度参数的独立配置。

### 7.2 与C版的关系

此优化**不是**Java版独有的。C版RTKLIB已有 `opt.ionoGradient` 开关，
功能等价。Java版的改进是：
1. 将开关纳入 `RtkConfig` 统一管理
2. 添加梯度参数的独立配置（`gradientIonoInitVar`, `gradientIonoPrn`）
3. 在 `relpos()` 入口自动同步 `enableIonoTropGradient → opt.ionoGradient`

### 7.3 核心算法

```
relpos() 入口同步:
  if enableIonoTropGradient && !opt.ionoGradient:
      opt.ionoGradient = true

状态向量维度变化:
  NI = MAXSAT * 3 (每星 VTEC + Gn + Ge)
  II(sat, opt) = NP + (sat-1)*3

ddres() 中电离层梯度H矩阵偏导数:
  对流动星 j 和参考星 refIdx:
    // VTEC偏导数（原有）
    H[nv*nx + ii_m] += im_m * freqRatio_m
    H[nv*nx + ii_r] -= im_r * freqRatio_r

    // Gn 北向梯度偏导数（新增）
    cotz = 1/tan(el)
    grad_n = im * cotz * cos(az)
    didxGn = x[ii] * grad_n * freqRatio
    H[nv*nx + ii_m+1] += didxGn_m
    H[nv*nx + ii_r+1] -= didxGn_r

    // Ge 东向梯度偏导数（新增）
    grad_e = im * cotz * sin(az)
    didxGe = x[ii] * grad_e * freqRatio
    H[nv*nx + ii_m+2] += didxGe_m
    H[nv*nx + ii_r+2] -= didxGe_r

prectrop() 中对流层梯度偏导数（TROPOPT_ESTG模式）:
  对流层延迟 = ZWD × m_w + Gn × m_w × cot(el) × cos(az) + Ge × m_w × cot(el) × sin(az)
  dtdx[IT(r)]   = m_w              // ZWD偏导数
  dtdx[IT(r)+1] = m_w × cot(el) × cos(az) × x[IT(r)]  // Gn偏导数
  dtdx[IT(r)+2] = m_w × cot(el) × sin(az) × x[IT(r)]  // Ge偏导数
  其中 r=0 为流动站, r=1 为基准站
```

### 7.4 梯度参数的物理意义

每颗卫星的电离层参数从1个（VTEC）扩展为3个：

| 索引 | 参数 | 物理意义 |
|------|------|---------|
| II(sat)+0 | VTEC | 天顶总电子含量 |
| II(sat)+1 | Gn | 北向梯度 (VTEC/距离) |
| II(sat)+2 | Ge | 东向梯度 (VTEC/距离) |

梯度参数通过 `cot(el) * cos(az)` 和 `cot(el) * sin(az)` 映射到
斜路径电离层延迟，反映电离层空间不均匀性。

### 7.5 配置参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `enableIonoTropGradient` | false | 总开关（同步到opt.ionoGradient） |
| `gradientIonoInitVar` | 1e-4 | 梯度参数初始方差 |
| `gradientIonoPrn` | 1e-3 | 梯度参数过程噪声 |

### 7.6 实现位置

| 函数 | 文件 | 说明 |
|------|------|------|
| `relpos()` | RtkCore.java | 同步开关 |
| `NI()/II()` | RtkCore.java | 维度和索引计算 |
| `ddres()` | RtkCore.java | 电离层梯度H矩阵偏导数（Gn/Ge） |
| `prectrop()` | RtkCore.java | 对流层梯度偏导数（TROPOPT_ESTG模式） |
| `udion()` | RtkCore.java | 梯度参数过程噪声 |

### 7.7 ⚠️ 已知代码问题：梯度参数未初始化/未更新（已修复 2026-07-19）

当 `ionoGradient=true` 时，每颗卫星有3个电离层参数（VTEC + Gn + Ge），
原先 `udion()` 只初始化和更新 VTEC（`II(sat)+0`），梯度参数 Gn（`II(sat)+1`）
和 Ge（`II(sat)+2`）从未被初始化或更新过程噪声。

**修复**: 在 `udion()` 中添加梯度参数的初始化和过程噪声更新：

```java
// GAP_RESION 重置时同时重置梯度参数
if (ionoGrad) {
    x[j + 1] = 0.0;
    x[j + 2] = 0.0;
}

// 新卫星初始化时同时初始化梯度参数
if (ionoGrad) {
    initx(x, P, nx, 0.0, gradInitVar, j + 1);  // Gn
    initx(x, P, nx, 0.0, gradInitVar, j + 2);  // Ge
}

// 过程噪声更新时同时更新梯度参数
if (ionoGrad) {
    P[(j + 1) * nx + (j + 1)] += SQR(gradPrn) * Math.abs(rtk.tt);  // Gn
    P[(j + 2) * nx + (j + 2)] += SQR(gradPrn) * Math.abs(rtk.tt);  // Ge
}
```

使用 `gradientIonoInitVar`(1e-4) 和 `gradientIonoPrn`(1e-3) 配置参数。

---

## 8. 优化项依赖关系与调用顺序

### 8.1 调用顺序（在relpos()中）

```
relpos() {
    // 1. 同步梯度开关
    if (enableIonoTropGradient) opt.ionoGradient = true

    // 2. SNR中值计算（在udstate之前，为参考星选择提供信息）
    computeSnrMedian()

    // 3. 时间更新
    udstate() {
        udpos()     // 自适应Q缩放在此应用
        udion()     // 大气冻结在此检查
        udtrop()
        udrcvbias()
        udbias()
    }

    // 4. Kalman迭代
    for (iter = 0; niter; iter++) {
        zdres()     // 零差残差
        ddres()     // 双差残差（梯度参数在此贡献）

        // 5. IGGIII抗差估计（在filter之前修改R）
        applyIggiii()

        filter()    // Kalman测量更新
    }

    // 6. 浮点解验证
    zdres() + ddres() + valpos()

    // 7. 模糊度固定
    manage_amb_LAMBDA() {
        ddidx() / buildParIndex()   // PAR参考星重选
        resamb_LAMBDA()             // 锚固分离在此执行
    }

    // 8. 固定解验证
    zdres(xa) + ddres(xa) + valpos()

    // 9. Hold约束
    holdamb()    // 锚固计数管理在此执行
}
```

### 8.2 优化项之间的依赖

```
enableSnrMedian ──→ buildParIndex() (SNR中值可用于参考星选择)
enableAdaptiveQ ──→ udpos() (Q缩放)
enableIggiii ─────→ ddres() → filter() (修改R矩阵)
enableParRefReselect → ddidx() (参考星选择策略)
enableAmbAnchor ──→ resamb_LAMBDA() + holdamb() (锚固分离和计数)
enableIonoTropGradient → NI()/II()/ddres()/udion() (梯度参数)
atmFrozenNsThresh → udion() (冻结检查)
```

各优化项之间无强依赖，可独立启用。但以下组合有协同效果：
- `enableSnrMedian` + `enableParRefReselect`: SNR中值辅助参考星选择
- `enableAmbAnchor` + `enableIggiii`: 抗差估计减少粗差，锚固保护稳定模糊度
- `enableAdaptiveQ` + `atmFrozenNsThresh`: 静态时Q压制+大气冻结，最大化精度

### 8.3 RtkConfig字段与优化项对应表

| 字段 | 所属优化项 |
|------|-----------|
| `enableParRefReselect` | PAR参考星重选 |
| `parElMask` | PAR参考星重选 |
| `parMaxConsecutiveReselect` | PAR参考星重选 |
| `enableAdaptiveQ` | 滑动窗自适应Q |
| `adaptiveQ*` | 滑动窗自适应Q |
| `zeroVel*` | 滑动窗自适应Q |
| `enableIggiii` | IGGIII抗差估计 |
| `iggiii*` | IGGIII抗差估计 |
| `enableSnrMedian` | SNR中值参考星 |
| `snrMedian*` | SNR中值参考星 |
| `enableIonoTropGradient` | 电离层梯度 |
| `gradientIono*` | 电离层梯度 |
| `enableAmbAnchor` | 模糊度子集锚固 |
| `ambAnchor*` | 模糊度子集锚固 |
| `atmFrozenNsThresh` | 大气参数冻结 |
| `enableSegmentSigmaFilter` | 段内σ过滤 |
| `segmentSigmaThreshold` | 段内σ过滤 |
| `segmentSigmaMinLen` | 段内σ过滤 |

### 8.4 Rtk字段与优化项对应表

| 字段 | 所属优化项 |
|------|-----------|
| `xOld[3]` | 滑动窗自适应Q |
| `posWin[100]` | 滑动窗自适应Q |
| `winIdx`, `winCnt` | 滑动窗自适应Q |
| `qScale` | 滑动窗自适应Q |
| `prevPosForZeroVel[3]` | 滑动窗自适应Q |
| `consecutiveZeroVelEpochs` | 滑动窗自适应Q |
| `snrMedian[NFREQ]` | SNR中值参考星 |
| `snrMedianHistory[NFREQ][20]` | SNR中值参考星 |
| `snrMedianHistoryCount` | SNR中值参考星 |
| `ambAnchored[MAXSAT*NF]` | 模糊度子集锚固 |
| `ambAnchorCount[MAXSAT*NF]` | 模糊度子集锚固 |
| `parConsecutiveReselectCount` | PAR参考星重选 |
| `parExcludedSats[MAXSAT]` | PAR参考星重选 |
| `parExcludedSatCount` | PAR参考星重选 |
| `parPrevRefSat[NFREQ]` | PAR参考星重选 |

---

## 9. 基站稳定性监测（BaseStationMonitor）

> **独立工具类**，不绑定RtkProcessor，由调用方创建和管理生命周期。
> 1个基站1个实例，多基线共享同一基站时不会重复存储。

### 9.1 问题背景

RTK定位假设基站坐标固定。如果基站天线实际发生了位移（支架倾斜、天线被碰、
冻融等），所有RTK解会产生系统性偏差，但用户不知道。需要一个独立机制
检测基站坐标异常变化并提醒用户。

### 9.2 解决方案

对基站观测做SPP定位，按时间窗口聚合中位数，与参考基准对比检测突变和漂移。

```
基站观测 → [SPP解算] → [观测域+解结果过滤] → [60min窗口聚合]
                                                    ↓
                                          窗口中位数 vs 参考基准
                                                    ↓
                                          偏移 > 阈值 → 回调提醒用户
                                                    ↓
                                          [二级: 历史中位数趋势] → 漂移检测
```

### 9.3 核心算法

#### 9.3.1 一级检测：窗口SPP中位数 + 突变检测

```
每历元:
  1. 提取基站观测, 过滤低SNR卫星
  2. SPP解算, 过滤: numSat < minSatCount 或 PDOP > maxPdop
  3. 加入窗口

窗口满 (elapsed ≥ windowMinutes):
  4. 计算窗口内SPP坐标中位数
  5. 离群剔除: distance(pos, median) > outlierThresh 的历元剔除
  6. 重新计算中位数
  7. 参考基准自学习: 首次窗口的中位数作为参考基准
  8. 突变检测: distance(median, referencePos) > movementThresh → 告警
  9. 中位数存入二级环形缓冲区
  10. 清空窗口
```

#### 9.3.2 二级检测：历史趋势漂移检测

```
每次存入二级缓冲区后:
  if historyCount ≥ 2:
      drift = distance(oldest_median, newest_median)
      hours = time_diff / 3600
      if drift > driftThresh → 漂移告警
```

#### 9.3.3 SPP窗口差分精度

SPP绝对精度5-10m，但**窗口间差分后有效精度约1m**：

```
窗口1中位数 = 真实坐标 + SPP系统偏差 + 噪声_1
窗口2中位数 = 真实坐标 + 位移 + SPP系统偏差 + 噪声_2
差分 = 位移 + (噪声_2 - 噪声_1)
```

SPP系统偏差（电离层延迟、卫星钟差等）在相邻60min窗口高度相关，做差后基本消除。
实测验证：基站连续6小时，窗口间偏移0.00~3.22m，10m阈值下无误报。

### 9.4 检测能力

| 移动幅度 | 可检测性 | 说明 |
|---------|---------|------|
| >10m | ✅ 可靠 | SPP窗口差分精度~1m，远低于阈值 |
| 3~10m | ⚠️ 有风险 | 电离层日变化可能干扰 |
| <3m | ❌ 不可行 | SPP精度不够 |

### 9.5 数据输入接口

| 接口 | 场景 | 说明 |
|------|------|------|
| `onBaseObs(Obsd[], n, Nav, GTime)` | 最底层 | 调用方自行解码，每历元调用 |
| `onRtcmData(byte[])` | 实时RTCM字节流 | 内部维护Rtcm解码器，自动解码 |
| `onRtcmData(byte[], offset, length)` | 实时RTCM字节流（指定偏移） | |
| `onRtcmFile(String)` | RTCM文件离线分析 | 读取文件后解码 |
| `onRinexFile(String, String)` | RINEX文件离线分析 | 内部用RinexSppProcessor读取 |

### 9.6 回调接口

```java
public interface BaseMonitorCallback {
    // 每个SPP结果
    default void onSppResult(int staid, GTime time, double[] pos, int numSat, double pdop) {}

    // 每个窗口的中位数和偏移
    default void onWindowResult(int staid, GTime time, double[] medianPos, double offset) {}

    // 突变告警: 窗口中位数偏移超过movementThresh
    default void onBaseMovement(int staid, GTime time, double[] median, double[] ref,
                                double offset, String msg) {}

    // 漂移告警: 历史中位数首尾差超过driftThresh
    default void onBaseDrift(int staid, GTime time, double drift, double hours, String msg) {}
}
```

全部default方法，调用方按需实现，不强制。

### 9.7 配置参数（BaseMonitorConfig）

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `windowMinutes` | 60.0 | 一级窗口时长（分钟） |
| `movementThresh` | 10.0 | 突变检测阈值（米） |
| `driftThresh` | 5.0 | 二级漂移检测阈值（米） |
| `historySize` | 24 | 二级环形缓冲区大小（窗口个数） |
| `minSatCount` | 4 | SPP最少卫星数 |
| `maxPdop` | 6.0 | SPP最大PDOP |
| `outlierThresh` | 3.0 | 离群剔除阈值（米） |
| `minValidInWindow` | 10 | 窗口最少有效历元数 |
| `minSnrDbHz` | 20.0 | 最低SNR (dB-Hz) |
| `minElMaskDeg` | 10.0 | 最低高度角（度） |

### 9.8 使用示例

```java
// 创建监测器: 1基站1实例
BaseMonitorConfig config = new BaseMonitorConfig();
config.windowMinutes = 60.0;
config.movementThresh = 10.0;

BaseMonitorCallback callback = new BaseMonitorCallback() {
    @Override
    public void onBaseMovement(int staid, GTime time, double[] median, double[] ref,
                               double offset, String msg) {
        // 发送告警到运维系统
        alertService.send(msg);
    }
};

BaseStationMonitor monitor = new BaseStationMonitor(214120, config, callback);

// 方式1: 实时RTCM数据流
monitor.onRtcmData(rtcmBytes);

// 方式2: RTCM文件
monitor.onRtcmFile("D:\\data\\BASE_STATION\\2026-07-04\\17.rtcm3");

// 方式3: RINEX文件
monitor.onRinexFile("base.obs", "base.nav");

// 方式4: 直接传入Obsd观测
monitor.onBaseObs(obs, n, nav, time);

// 多基线共享同一基站: 同一个monitor实例
RtkProcessor rtk1 = ...; // rover1 → 基站A
RtkProcessor rtk2 = ...; // rover2 → 基站A
// 两个RtkProcessor都把基站观测推给同一个monitor

// 查询状态
monitor.isReferenceInitialized();
monitor.getReferencePos();
monitor.getHistoryCount();

// 重置
monitor.resetReference(); // 重置参考基准，下次窗口重新自学习
monitor.reset();          // 重置所有状态
```

### 9.9 内部存储

| 存储 | 大小 | 生命周期 |
|------|------|---------|
| 一级窗口SPP坐标 | ~90KB峰值 | 窗口满计算后清空 |
| 二级中位数环形缓冲 | ~1KB | 常驻，有上限(historySize) |
| 参考基准 | 24B | 常驻 |
| **总计** | **~90KB峰值，1KB常驻** | |

### 9.10 设计原则

1. **完全独立**：不绑定RtkProcessor/RtkConfig/Rtk，纯新增3个类
2. **1基站1实例**：调用方管理生命周期，多基线共享同一基站不重复存储
3. **同步调用**：try-catch包裹，异常不影响RTK主流程；1Hz下SPP <1ms
4. **自学习参考基准**：首次窗口中位数自动初始化，无需外部输入已知坐标
5. **回调+日志**：检测结果通过回调推送，同时记录WARN日志
6. **配置灵活**：窗口大小、阈值、缓冲区大小均可配置

### 9.11 实现位置

| 类 | 包 | 说明 |
|---|---|------|
| `BaseStationMonitor` | org.rtklib.java.monitor | 核心监测器 |
| `BaseMonitorConfig` | org.rtklib.java.monitor | 配置类 |
| `BaseMonitorCallback` | org.rtklib.java.monitor | 回调接口 |

### 9.12 测试验证

| 测试 | 结果 |
|------|------|
| RTCM文件输入 (6小时) | SPP成功1429历元, 5个窗口, 偏移0.00~3.22m, 无误报 ✅ |
| 模拟移动检测 | 正常数据0告警, resetReference正确重置 ✅ |
| RTCM字节数据输入 | 与文件输入结果一致 ✅ |

---

## 10. 逐级模糊度固定（enableCascadeAR）

> **来源**: GREAT-PVT（GREAT-WHU/GREAT，LibGREAT gambfix 逐级固定思想）
> **适用场景**: 多频（≥2频）非IFLC RTK，提升模糊度固定连续性
> **优先级**: P1
> **状态**: ✅ v2.3.0 已重写为真逐级固定（FIX-ALG-007），修复旧伪逐级实现四大缺陷

### 10.1 问题背景

经典LAMBDA搜索直接在窄巷(NL)模糊度空间搜索，当模糊度方差较大时Ratio值低，
导致固定失败。逐级AR利用多频波长差异，先在超宽巷(EWL)和宽巷(WL)空间固定，
由于EWL/WL波长长、取整容差大，固定成功率高，逐级条件约束后NL搜索空间大幅缩小。

### 10.2 核心算法（v2.3.0 真逐级实现）

所有组合均在**周（cycle）域**进行，LAMBDA 直接作用于无量纲周值，与系统频率无关。
DD 组合作用于**同一卫星对**（参考星按频率独立选取，跨频率按精确卫星对配对）。

```
1. 组合定义（GPS L1/L2/L5 / GAL E1/E5a/E5b / BDS B1I/B2I/B3I）：
   EWL（nf≥3）: N_ewl = N_f2 − N_f5      （拍频波长 GPS≈5.86m / GAL≈9.77m / BDS≈4.88m）
   WL         : N_wl  = N_f1 − N_f2      （拍频波长 GPS≈0.86m）
   NL         : N_nl  = N_f1
   反推       : N_f1 = b_nl；N_f2 = b_nl − b_wl；N_f5 = N_f2 − b_ewl

2. 每级独立变换与搜索（修复旧Bug1）：
   z = C·y；Qz = C·Q·Cᵀ → LAMBDA(z,Qz) → 独立 ratio 门限
   级间条件更新（与 GREAT 虚拟观测注入数学等价）：
   K = Q·Cᵀ·Qz⁻¹；y ← y − K(z−b)；Q ← Q − K·C·Q

3. 级别顺序：EWL（nf≥3）→ WL → NL（最长波长最先固定）

4. NL 级为最终固定级：
   - ratio 阈值对齐标准路径动态多项式调整（C rtkpos.c ar_poly_coeffs），
     并取 cfg.cascadeArRatioNl 为下限（修复旧Bug2）
   - sol.ratio/sol.thres 语义与标准路径一致（下游 arfilter/fix-and-hold 可用）

5. 整数反推与降级（修复旧Bug3/Bug4）：
   - 每频率 DD 整数由级间整数关系反推；不可成链（跨频率行缺失）的行降级为
     float（fix=1）
   - bias[] 按 restamb 的消费序紧凑构建：(m,f,sat升序) 遍历 fix==2 行、跳过每组
     参考行、降级行不占槽位——避免降级行导致的 bias 消费错位（Bug5，A/B 验证发现）
   - 成功路径完整复刻标准路径：仅对已固定行做条件更新 rtk.xa/rtk.Pa +
     restamb() 恢复单差模糊度写入 xa 参数
   - 任一级 LAMBDA 失败 / ratio 未过门限 → 返回 -1 → 回落标准 LAMBDA 路径
```

### 10.3 配置参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `enableCascadeAR` | false | 总开关 |
| `cascadeArRatioEwl` | 1.5 | EWL级Ratio阈值 |
| `cascadeArRatioWl` | 2.0 | WL级Ratio阈值 |
| `cascadeArRatioNl` | 3.0 | NL级Ratio阈值下限（实际门限=max(动态多项式阈值, 此值)，修复旧Bug2） |
| `cascadeArBootstrapping` | false | 级联AR中启用Bootstrapping校验（作用于NL级） |

### 10.4 侵入方式

`RtkCore.resamb_LAMBDA()` 开头分支（含失败回落，IFLC 守卫保留）：

```java
int nfAr = (rtk.opt.ionoopt == Constants.IONOOPT_IFLC) ? 1 : rtk.opt.nf;
if (rtk.rtkConfig.enableCascadeAR && nfAr >= 2) {
    int nbCas = RtkOptimizationsCascadeAR.cascadeAmbFix(rtk, bias, xa, gps, glo, sbs, nav);
    if (nbCas > 0) return nbCas;   // 级联成功直接返回；失败（≤0）回落标准 LAMBDA
}
```

### 10.5 回退保证

- `enableCascadeAR=false`（默认）→ 完全跳过，走原版LAMBDA逻辑，逐值一致。
- IFLC 模式（`ionoopt=IONOOPT_IFLC`）→ 守卫短路，与 FIX-ALG-006 语义一致。
- 级联任一级失败 → 回落标准 LAMBDA 路径（含动态阈值/PartialAR 可达），
  可用性不低于基线。

### 10.6 实现位置

- 算法：`org.rtklib.java.rtkpos.RtkOptimizationsCascadeAR`（真逐级重写）
- 入口：`RtkCore.resamb_LAMBDA()` 头部分支
- 测试：
  - `org.rtklib.java.rtkpos.RtkOptimizationsCascadeARTest`（单元测试，6个）
  - `org.rtklib.java.urbancanyon.CascadeArBehaviorTest`（EST 模式 4 数据集 A/B
    行为验证，无假 Fix 硬门限，FIX-ALG-007 验收）

### 10.7 已知问题（已全部修复，FIX-ALG-007，2026-10-15）

> 以下为 **v2.2.x 旧伪逐级实现**的缺陷记录，v2.3.0 重写后不再存在。
> 历史实测数据（§17.3 中 CascadeAR 行 fix 率暴跌）均为旧实现的结果。

| 缺陷 | 旧实现行为 | 修复方式 |
|------|-----------|---------|
| Bug1 伪逐级 | 三次LAMBDA共用同一 y/Qb，EWL/WL验证冗余 | 每级独立组合变换 C·y、C·Q·Cᵀ + 独立 LAMBDA + 级间条件更新 |
| Bug2 固定阈值 | NL 用固定 cascadeArRatioNl=3.0 | 对齐标准路径动态多项式阈值，cfg 值作为下限 |
| Bug3 缺 restamb | xa 参数从未写入 → 假 Fix（输出基站坐标） | 成功路径调用 RtkCore.restamb()，并仅对已固定行做条件更新 |
| Bug4 失败不阻断 | EWL/WL ratio 未过仍继续 NL | 任一级失败立即 return -1 回落标准路径 |
| Bug5 restamb bias 消费错位（A/B 验证发现） | 重写初版 bias[] 按 DD 行序填充，但 restamb 按 (m,f,sat升序) 消费且降级行不占槽位 → 错位后错误整数经 holdamb 锁入滤波器（FIXHOLD 冻结假 Fix 簇） | bias[] 改为按 restamb 消费序紧凑构建 |
| Bug6 无 f1 行配对 AIOOBE（A/B 验证发现） | pr[0]==-1（仅 f2 行）时 freeMap[-1] 越界，首历元崩溃 | 映射前守卫 pr[f]>=0 |
| Bug7 锚定行 bias 槽位丢失（代码审查发现） | 重写初版锚定行 bRowFull 未填充（消费 0.0） | 消费序构建前按 round(float) 填充锚定行 |

**A/B 验证关键结论**（PPC Tokyo run2，EST 模式）：
- Bug5 修复前：Fix 率 1.2%（107），Fix H.RMSE 5.98m——错误整数被 holdamb 锁定后
  形成冻结假 Fix 簇（u≈34.6m 持续 14 历元），坏历元 56/63 在 ±3 历元内有级联固定。
- Bug5 修复后：Fix 率 8.9%（816，高于基线 4.7%/433），Fix H.RMSE 1.84m
  （p50=2.8cm、p90=20cm）；残差坏尾（约 7%，H>2m）为浮点偏差漂移陷入错误整数
  邻域的自洽失效，与标准 LAMBDA 路径同类（基线 Tokyo run1 Fix H.RMSE 9.5m），
  非级联实现缺陷。

**全量 4 数据集最终 A/B 回归**（PPC-Dataset，EST 模式，FIXHOLD，两轮运行逐位一致）：

| 数据集 | 基线 Fix 率(fix) | 级联 Fix 率(fix) | 基线 Fix H.RMSE | 级联 Fix H.RMSE |
|--------|-----------------|-----------------|-----------------|-----------------|
| Tokyo run1 | 3.2% (386) | 3.0% (357) | 9.52m | 20.4m |
| Tokyo run2 | 4.7% (433) | 8.9% (816) | 0.45m | 1.84m |
| Nagoya run1 | 0.2% (14) | 0.8% (60) | 19.7m | 27.0m |
| Nagoya run2 | 0.1% (7) | 1.2% (110) | 26.6m | 27.0m |

Fix 逐历元水平误差分布（p50 / max，>2m 占比）：

| 数据集 | 基线 | 级联 |
|--------|------|------|
| Tokyo run1 | 7.8cm / 110m，2.1% | 5.3cm / 164m，16.0% |
| Tokyo run2 | 2.4cm / 3.5m，2.3% | 2.8cm / 18.0m，7.7% |
| Nagoya run1 | 13.7m / 36.4m，100% | 25.6m / 50.0m，100% |
| Nagoya run2 | 20.4m / 42.7m，100% | 24.8m / 79.7m，100% |

**验收结论**（相对门限策略，`CascadeArBehaviorTest`）：
- 假 Fix 鉴别仅在基线健康区制（基线 Fix p50 < 1m）执行——Nagoya 两数据集基线
  自身 100% 假 Fix（城市峡谷浮点偏差漂移，级联与基线同源），绝对门限对基线不成立；
- Tokyo 两数据集基线健康，级联 p50 均 < 1m，通过鉴别；可用性门限
  （级联 Fix 率 ≥ 0.5× 基线）4 数据集全部通过，**0 violations**；
- 可用性：Nagoya 4~15 倍、Tokyo run2 约 2 倍、run1 持平（0.94×）；
- 已知代价：Tokyo run1 级联坏尾比例 16.0% vs 基线 2.1%——逐级门限
  （WL ratio=2.0）在浮点偏差污染区弱于标准路径联合动态阈值（≈4.41），
  属"可用性换稳健性"的固有权衡；`cascadeArRatioWl` 可调参收紧。

**兼容性说明**：IFLC 模式下状态空间压缩为每星单 IF 组合偏差，逐级固定不适用
（守卫短路，详见 FIX-ALG-006）；GLO/SBAS 行的 N1−N2 组合同为整数关系，
参与 WL/NL 级（EWL 仅限三频系统）。

---

## 11. 精细化残差编辑与周跳检测（enableResidualEdit）

> **来源**: PRIDE PPP-AR
> **适用场景**: 所有RTK模式，是高级AR策略生效的底层基础
> **优先级**: P0

### 11.1 问题背景

原版RTKLIB的周跳检测仅依赖LLI标志和MW/GF组合，对缓变周跳、接收机钟跳、
多路径引起的伪距-载波不一致检测能力不足。错误模糊度弧段会污染EKF状态，
导致后续AR失败或假固定。

### 11.2 三重检测机制

```
1. 残差时序跳变检测（checkCycleSlip）
   - 对EKF后验残差v归一化: normRes = |v[i]| / sqrt(R[i*nv+i])
   - normRes > residEditJumpThresh(4.0σ) → 标记周跳
   - 在ddres()后调用，利用最新残差信息

2. 伪距-载波一致性校验（checkPcConsistency）
   - P-C差分 = 2*I + b（电离层+偏差）
   - 历元间差分消除偏差，仅含电离层变化+噪声
   - |Δ(P-C)| > residEditPcConsistThresh(3.0σ) → 标记周跳

3. 弧段完整性筛查（screenArcIntegrity）
   - lock < residEditMinArcLen(10) 且 outc > 0.5*lock → 弧段过短且频繁中断
   → 重置模糊度状态（lock=-minlock, outc=0）
   - 在udbias()末尾调用，确保下一历元重新初始化
```

### 11.3 配置参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `enableResidualEdit` | false | 总开关 |
| `residEditJumpThresh` | 4.0 | 残差跳变阈值（σ倍数） |
| `residEditMinArcLen` | 10 | 最小弧段长度（历元数） |
| `residEditPcConsistThresh` | 3.0 | 伪距-载波一致性阈值（σ倍数） |

### 11.4 侵入方式

3个侵入点，均为if分支：

```java
// ddres()后
RtkOptimizationsResEdit.checkCycleSlip(rtk, v, R, vflg, nv);
RtkOptimizationsResEdit.checkPcConsistency(rtk, obs, sat, ns, nf, nav);

// udbias()末尾
RtkOptimizationsResEdit.screenArcIntegrity(rtk);
```

### 11.5 回退保证

`enableResidualEdit=false`（默认）→ 三个方法首行return，完全跳过。

### 11.6 实现位置

- 算法：`org.rtklib.java.rtkpos.RtkOptimizationsResEdit`
- 测试：`org.rtklib.java.rtkpos.RtkOptimizationsResEditTest`（5个测试）

---

## 12. 部分模糊度固定（enablePartialAR）

> **来源**: GREAT-PVT
> **适用场景**: 全局Ratio检验失败后，尝试保留可信子集完成固定
> **优先级**: P1

### 12.1 问题背景

全局Ratio检验要求所有模糊度联合通过，但实际中常有个别卫星模糊度质量差
（低高度角、多路径、频繁周跳），导致整体Ratio低而无法固定。部分AR剔除
劣质维度，保留可信子集完成固定，平衡固定率与可靠性。

### 12.2 核心算法

```
1. 全局Ratio失败后触发
2. 按模糊度方差排序（方差大→质量差）
3. 逐步剔除方差最大的模糊度：
   for drop = 1 to maxSubsetTries:
     subNb = nb - drop
     if subNb < minSats → 停止
     构建子集 → LAMBDA搜索 → Ratio检验
     if Ratio >= partialArMinRatio:
       Bootstrapping校验（如启用）
       通过 → 返回固定结果
4. 所有子集尝试失败 → 返回-1（浮点解）
```

### 12.3 配置参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `enablePartialAR` | false | 总开关 |
| `partialArMinRatio` | 2.0 | 部分AR最小Ratio阈值 |
| `partialArMinSats` | 4 | 部分AR最小保留卫星数 |
| `partialArMinBootstrapping` | 0.99 | 部分AR最小Bootstrapping成功率 |
| `partialArMaxSubsetTries` | 10 | 最大子集尝试次数 |

### 12.4 侵入方式

`RtkCore.resamb_LAMBDA()` Ratio失败后1行if分支：

```java
if (nb <= 0 && rtk.rtkConfig.enablePartialAR) {
    int nbPar = RtkOptimizationsPartialAR.partialAmbFix(rtk, bias, xa, ix, rtk.nb_ar, gps, glo, sbs);
    if (nbPar > 1) return nbPar;
}
```

### 12.5 回退保证

`enablePartialAR=false`（默认）→ 完全跳过，走原版逻辑（全局Ratio失败即浮点解）。

### 12.6 实现位置

- 算法：`org.rtklib.java.rtkpos.RtkOptimizationsPartialAR`
- 测试：`org.rtklib.java.rtkpos.RtkOptimizationsPartialARTest`（3个测试）

---

## 13. Bootstrapping成功率联合判据（enableBootstrapping）

> **来源**: GREAT-PVT
> **适用场景**: 作为Ratio的互补判据，防止Ratio偶然通过但模糊度不可靠的情况
> **优先级**: P1

### 13.1 问题背景

Ratio检验仅比较LAMBDA搜索前两个候选解的二次型比值，无法保证固定结果的
统计可靠性。Bootstrapping成功率基于模糊度浮点值与方差的概率分布，计算
所有模糊度同时取整成功的概率，是Ratio的必要补充。

### 13.2 核心算法

```
对每个模糊度i:
  halfNorm = 0.5 / σ_i    （σ_i为模糊度标准差）
  prob_i = erf(halfNorm / √2)  （单个模糊度取整成功概率）

联合成功率 = Π prob_i  （独立假设下的联合概率）

判定：
  联合成功率 >= bootstrappingMinSuccess(0.99) → 通过
  否则 → 拒绝固定
```

erf(x)通过erfc(x) = 1 - erf(x)计算，erfc使用Horner有理逼近。

### 13.3 配置参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `enableBootstrapping` | false | 总开关 |
| `bootstrappingMinSuccess` | 0.99 | 最小成功率阈值 |
| `bootstrappingWithRatio` | true | 与Ratio联合使用（Ratio通过后再校验） |

### 13.4 侵入方式

`RtkCore.resamb_LAMBDA()` Ratio通过后1行if分支：

```java
if (rtk.rtkConfig.enableBootstrapping) {
    if (!RtkOptimizationsBootstrap.validateFix(rtk, b, nbLambda)) {
        rtk.sol.ratio = 0.0f;
        nb = 0;
        return nb;
    }
}
```

### 13.5 回退保证

`enableBootstrapping=false`（默认）→ 完全跳过，仅靠Ratio判据。

### 13.6 实现位置

- 算法：`org.rtklib.java.rtkpos.RtkOptimizationsBootstrap`
- 测试：`org.rtklib.java.rtkpos.RtkOptimizationsBootstrapTest`（7个测试）

---

## 14. BDS卫星码偏差改正（enableBdsCodeBias）

> **来源**: PRIDE PPP-AR（Wanninger模型）
> **适用场景**: 包含BDS卫星的RTK解算，特别是GEO和低高度角IGSO/MEO卫星
> **优先级**: P2

### 14.1 问题背景

BDS卫星存在显著的码偏差（Code Bias），特别是GEO卫星（PRN 1-5）偏差可达-0.58m，
IGSO卫星（PRN 6-10）在低高度角时偏差也较大。这些偏差如果不改正，会导致
伪距观测值系统性偏差，降低模糊度固定成功率。

### 14.2 Wanninger模型

```
GEO卫星（PRN 1-5）：
  bias[0] = -0.58m（B1I固定值）
  bias[1] = -0.50m（B2I固定值）
  bias[2] = -0.40m（B3I固定值）

IGSO卫星（PRN 6-10）：
  基于高度角线性插值：
  el=10°: bias ≈ -0.45m
  el=30°: bias ≈ -0.20m
  el=60°: bias ≈ -0.05m

MEO卫星（PRN 11-46）：
  基于高度角线性插值：
  el=10°: bias ≈ -0.25m
  el=30°: bias ≈ -0.10m
  el=60°: bias ≈ -0.02m
```

### 14.3 配置参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `enableBdsCodeBias` | false | 总开关 |
| `bdsCodeBiasElThresh` | 30.0 | 高度角阈值（度），高于此值不改正 |
| `bdsCodeBiasForceOn` | false | 强制对所有BDS卫星启用（忽略高度角阈值） |

### 14.4 侵入方式

`RtkCore.relpos()` udstate()后1行if分支：

```java
RtkOptimizationsBdsBias.applyBdsCodeBias(rtk, obs, iu, ir, ns, nf, nav);
```

内部修改伪距观测值 `obs[iu[i]].P[f] -= bias[f]`，对BDS卫星施加码偏差改正。

### 14.5 回退保证

`enableBdsCodeBias=false`（默认）→ 方法首行return，伪距观测值不修改。

### 14.6 实现位置

- 算法：`org.rtklib.java.rtkpos.RtkOptimizationsBdsBias`
- 测试：`org.rtklib.java.rtkpos.RtkOptimizationsBdsBiasTest`（6个测试）

---

## 15. 参数类型级自适应过程噪声（enableParamTypeNoise）

> **来源**: PRIDE PPP-AR
> **适用场景**: 中长基线RTK，ZTD/电离层等时变参数过程噪声需精细建模
> **优先级**: P2

### 15.1 问题背景

原版RTKLIB对所有时变参数使用统一的 `prn[i]² * |tt|` 过程噪声模型，
不区分参数物理特性。ZTD适合随机游走模型（方差随时间增长），钟差适合
白噪声模型（方差恒定），电离层适合随机游走但速率与ZTD不同。

### 15.2 核心算法

```
按状态索引区分参数类型，施加不同噪声模型：

位置(0..np-1)：不使用随机游走（位置本身是状态，非时变参数）
ZTD(np+ni..np+ni+nt-1)：随机游走 → P[i*nx+i] += noiseZtdRw² * |tt|
电离层(np..np+ni-1)：随机游走 → P[i*nx+i] += noiseIonoRw² * |tt|
钟差/模糊度：保持原逻辑不变
```

### 15.3 与enableAdaptiveQ的关系

| 优化项 | 控制对象 | 机制 |
|--------|---------|------|
| `enableAdaptiveQ` | 位置过程噪声 | 运动状态级缩放（静态压制/动态放大） |
| `enableParamTypeNoise` | ZTD/电离层过程噪声 | 参数类型级区分（随机游走/白噪声） |

两者互补，可叠加使用。

### 15.4 配置参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `enableParamTypeNoise` | false | 总开关 |
| `noiseZtdRw` | 1e-4 | ZTD随机游走过程噪声（m/√s） |
| `noiseClkWhite` | 1e2 | 钟差白噪声（m） |
| `noiseIonoRw` | 1e-3 | 电离层随机游走过程噪声（m/√s） |

### 15.5 侵入方式

`RtkCore.relpos()` udstate()后1行if分支：

```java
RtkOptimizations.applyParamTypeNoise(rtk, rtk.P, rtk.nx, rtk.tt);
```

### 15.6 回退保证

`enableParamTypeNoise=false`（默认）→ 方法首行return，走原有 `prn[i]²*|tt|` 逻辑。

### 15.7 实现位置

- 算法：`org.rtklib.java.rtkpos.RtkOptimizations.applyParamTypeNoise()`
- 已有测试覆盖

---

## 16. 段内位置标准差过滤（enableSegmentSigmaFilter）

> **适用场景**: 动态RTK严重遮挡/城市峡谷场景，false fix比例高时
> **优先级**: P0（实测唯一有效的在线false fix过滤方案）
> **测试验证**: UrbanNav Tokyo OFF模式，σ<5m: Fix 8.2%, H>1m仅1.5%, H.RMSE=0.620m

### 16.1 问题背景

动态RTK在严重遮挡场景下，EKF状态可能因观测不足而发散，但Ratio检验
仍偶然通过，产生false fix（固定解位置偏差>1m）。这些false fix段
内部位置一致（标准差小），但与真实轨迹偏差大，无法通过连续性或跳变检测。

后处理分析发现：false fix段的位置标准差σ显著大于true fix段。
true fix段σ通常<1m，false fix段σ通常>10m，两者分布几乎无重叠。

### 16.2 解决方案

在线计算连续Fix段内ENU位置标准差σ，当σ超过阈值时追溯失效整段——
将段内所有已输出Sol的stat从SOLQ_FIX改为SOLQ_FLOAT。

### 16.3 核心算法

```
输入: 每历元的 rtk.sol.rr[ECEF坐标], rtk.sol.stat, solutionIndex

步骤1: 状态转换
  if stat != SOLQ_FIX:
      reset()  // 清空段状态，开始新段
      return OK

步骤2: ECEF→ENU转换
  ecef2pos(rr, posBuf)  // ECEF→LLH
  if segLen == 0:
      refLat = posBuf[0], refLon = posBuf[1]  // 段起始参考点
      segStartIdx = solutionIndex
  dLat = (lat - refLat) × RE_WGS84
  dLon = (lon - refLon) × cos(refLat) × RE_WGS84
  e = dLon, n = dLat  // 东向/北向位移

步骤3: 累积统计量
  segLen++
  sumE += e, sumN += n
  sumE2 += e², sumN2 += n²

步骤4: σ计算与判定（segLen ≥ minLen后）
  varE = sumE2/segLen - (sumE/segLen)²
  varN = sumN2/segLen - (sumN/segLen)²
  σ = √(max(0,varE) + max(0,varN))

  if σ ≥ threshold && !segInvalidated:
      segInvalidated = true
      return CheckResult(false, segStartIdx, solutionIndex-1)
      // 追溯失效: 从segStartIdx到当前历元前一个

  if segInvalidated:
      return CheckResult(false, -1, -1)  // 已失效段，当前历元也降为Float

  return OK
```

### 16.4 追溯失效机制

在线处理中，解是逐历元输出的。当σ超阈值时，段内之前已输出的Fix解
需要被追溯修改为Float。实现方式：

```java
// PostPosProcessor.processForward() / processBackward()
SegmentSigmaFilter.CheckResult cr = filter.checkAndAdd(
    rtk.sol.rr, rtk.sol.type, rtk.sol.stat, solutions.size());
if (!cr.accepted) {
    rtk.sol.stat = Constants.SOLQ_FLOAT;  // 当前历元降为Float
}
if (cr.invalidateFrom >= 0) {
    // 追溯失效: 修改已输出Sol的stat
    for (int i = cr.invalidateFrom; i <= cr.invalidateTo; i++) {
        solutions.get(i).stat = Constants.SOLQ_FLOAT;
    }
}
```

### 16.5 在线 vs 后处理差异

| 特性 | 后处理 | 在线（本实现） |
|------|--------|---------------|
| σ超阈值时刻 | 可整段失效 | 追溯修改已输出Sol.stat |
| 失效范围 | 段内所有历元 | 段起始到σ超阈值前一个历元 |
| 效果差异 | 完全一致 | 略逊（σ超阈值时刻的历元本身仍为Float） |
| 实测差异 | H.RMSE=0.614m | H.RMSE=0.620m（差异<1%） |

### 16.6 参数扫描结果（UrbanNav Tokyo OFF模式）

| 配置 | Fix率 | H<0.5m(true fix) | H>1m(false fix) | H.RMSE |
|------|-------|-----------------|----------------|--------|
| 基准(无过滤) | 55.0% | 1.6% | 54.5% | 2.827m |
| **σ<5m, N≥5** | **8.2%** | **0.0%** | **1.5%** | **0.620m** |
| σ<5m, N≥10 | 8.2% | 0.0% | 1.5% | 0.620m |
| σ<10m, N≥5 | 9.1% | 0.2% | 6.1% | 0.823m |
| σ<10m, N≥10 | 9.1% | 0.2% | 6.1% | 0.823m |
| σ<20m, N≥5 | 9.9% | 0.2% | 6.3% | 0.971m |
| σ<20m, N≥10 | 9.9% | 0.2% | 6.3% | 0.971m |

> **推荐配置**: `segmentSigmaThreshold=5.0, segmentSigmaMinLen=5`
> - minLen参数（5 vs 10）对结果无影响
> - σ<5m是最严格阈值，false fix最低（1.5%），H.RMSE最优（0.620m）
> - σ<10m/20m阈值放宽后false fix回升，因为部分发散段σ在5~20m之间

### 16.7 配置参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `enableSegmentSigmaFilter` | false | 总开关 |
| `segmentSigmaThreshold` | 5.0 | σ阈值（米），超过则追溯失效整段 |
| `segmentSigmaMinLen` | 5 | 最小段长度，段内历元数少于此值不判定 |

### 16.8 侵入方式

3个侵入点：

```java
// PostPosProcessor.processForward()
if (forwardSigmaFilter != null && rtk.sol.stat == Constants.SOLQ_FIX) {
    SegmentSigmaFilter.CheckResult cr = forwardSigmaFilter.checkAndAdd(...);
    // 处理追溯失效
}

// PostPosProcessor.processBackward()
if (backwardSigmaFilter != null && rtk.sol.stat == Constants.SOLQ_FIX) {
    SegmentSigmaFilter.CheckResult cr = backwardSigmaFilter.checkAndAdd(...);
    // 处理追溯失效
}

// RtkProcessor.processEpoch()
if (segmentSigmaFilter != null && rtk.sol.stat == Constants.SOLQ_FIX) {
    SegmentSigmaFilter.CheckResult cr = segmentSigmaFilter.checkAndAdd(...);
    // 处理追溯失效
}
```

### 16.9 回退保证

`enableSegmentSigmaFilter=false`（默认）→ 不创建SegmentSigmaFilter实例，完全跳过。

### 16.10 实现位置

| 函数/类 | 文件 | 说明 |
|---------|------|------|
| `SegmentSigmaFilter` | SegmentSigmaFilter.java | 核心过滤器 |
| `CheckResult` | SegmentSigmaFilter.java | 返回值（accepted + 失效范围） |
| `processForward()` | PostPosProcessor.java | Forward路径集成 |
| `processBackward()` | PostPosProcessor.java | Backward路径集成 |
| `processEpoch()` | RtkProcessor.java | 实时处理路径集成 |

---

## 17. 优化矩阵

### 17.1 按场景分类

| 优化项 | 开关 | 静态短基线 | 静态长基线 | 动态开阔 | 动态遮挡 | 测试状态 |
|--------|------|-----------|-----------|---------|---------|---------|
| **滑动窗自适应Q** | `enableAdaptiveQ` | ❌ 不适用 | ❌ 不适用 | ⚠️ 理论推断，无开阔实测 | ✅ Fix+12% | ✅ 5数据集（遮挡） |
| **模糊度子集锚固** | `enableAmbAnchor` | ✅ 锚固保护 | ✅ 锚固保护 | ✅ 锚固保护 | ❌ Fix率低难满足 | ⚠️ 未专项测试 |
| **大气参数冻结** | `atmFrozenNsThresh` | ❌ 短基线不估大气 | ✅ 少星时防漂移 | ✅ 少星时防漂移 | ✅ 少星时防漂移 | ⚠️ 未专项测试 |
| **IGGIII抗差估计** | `enableIggiii` | ⚠️ 弱收益 | ⚠️ 理论有效，待静态实测 | ⚠️ 弱收益 | ⚠️ 场景依赖 | ✅ 5数据集（动态） |
| **SNR中值定权** | `enableSnrMedian` | ⚠️ 弱收益 | ✅ 精度-11%~22% | ⚠️ 弱收益 | ⚠️ 无效果但无害 | ✅ 5数据集 |
| **PAR参考星重选** | `enableParRefReselect` | ⚠️ 理论有效 | ⚠️ 理论有效 | ⚠️ 理论有效 | ⚠️ 理论有效 | ⚠️ 未专项测试 |
| **电离层梯度** | `enableIonoTropGradient` | ❌ 短基线无需 | ✅ 长基线必要 | ❌ 短基线无需 | ❌ 短基线无需 | ⚠️ 未专项测试 |
| **基站稳定性监测** | `BaseStationMonitor` | ✅ 滑坡监测 | ✅ 长基线基站 | ✅ 通用 | ✅ 通用 | ✅ RTCM+RINEX |
| **逐级模糊度固定** | `enableCascadeAR` | ⚠️ 动态场景已验证(§10.7)，静态短基线未重测 | ⚠️ 静态长基线未重测 | ⚠️ 开阔天空无实测 | ✅ EST动态可用性提升（Nagoya 4~15×/run2 2×） | ✅ 单元测试+EST A/B回归 0 violations |
| **残差编辑周跳** | `enableResidualEdit` | ⚠️ 微弱(实测) | ✅ 弧段筛查 | ✅ 跳变检测 | ❌ IFLC下有害 | ✅ 单元测试+场景实测 |
| **部分模糊度固定** | `enablePartialAR` | ✅ **假固定-67%** | ✅ 提升Fix率 | ✅ 提升Fix率 | ⚠️ false fix风险 | ✅ 单元测试+场景实测 |
| **Bootstrapping判据** | `enableBootstrapping` | ✅ **假固定-81%** | ✅ 防假阳性 | ✅ 防假阳性 | ❌ 动态遮挡无效 | ✅ 单元测试+UrbanNav+场景实测 |
| **BDS码偏差改正** | `enableBdsCodeBias` | ✅ 含BDS通用 | ✅ 含BDS通用 | ✅ 含BDS通用 | ✅ 含BDS通用 | ✅ 单元测试 |
| **参数类型级噪声** | `enableParamTypeNoise` | ❌ 短基线不估大气 | ✅ ZTD/电离层精细建模 | ❌ 短基线不估大气 | ❌ 短基线不估大气 | ⚠️ 未专项测试 |
| **段内σ过滤** | `enableSegmentSigmaFilter` | ⚠️ 需验证 | ⚠️ 需验证 | ⚠️ 无需(遮挡少) | ✅ **false fix 1.5%** | ✅ UrbanNav OFF |

> ⚠️ **表注**：本表"动态开阔"列均为理论推断，本次测试无开阔天空数据集（测试数据集为UrbanNav严重遮挡、PPC部分遮挡）。标注"⚠️ 弱收益"的项在开阔天空下实际效果待验证。
> ⚠️ **长基线口径**：`静态长基线`列标注"未专项测试"的项，指**优化项本身未做长基线 A/B 对照**。长基线链路已由 `RtkValidationTest` 跑通（HKWS-HKSL，~42km，2880 历元，fail=0），但 FIX 率仅 1.3%、以 FLOAT 为主，参考坐标为 RINEX 头近似值，属表面（链路级）验证，尚不能作为长基线精度或增益结论。

### 17.2 按功能分类

| 类别 | 优化项 | 核心作用 |
|------|--------|---------|
| **过程噪声建模** | 自适应Q、参数类型级噪声、大气冻结 | 根据运动状态/参数类型/卫星数动态调整Q矩阵 |
| **抗差估计** | IGGIII、SNR中值定权 | 降权粗差/NLOS/弱信号观测 |
| **模糊度固定增强** | 逐级AR、部分AR、Bootstrapping、锚固 | 提升Fix率和/或可靠性 |
| **观测质量增强** | 残差编辑、BDS码偏差、PAR参考星重选 | 改善输入观测质量 |
| **后验验证** | 段内σ过滤 | 在线过滤false fix |
| **监测工具** | 基站稳定性监测 | 独立检测基站位移 |

### 17.3 静态短基线RTK逐项对照实测

**测试文件**：[RtkOptimizationTest.java](file:///D:/code/rtklib_java/rtklib-core/src/test/java/org/rtklib/java/RtkOptimizationTest.java)

**场景**：RTK静态，短基线，Fix-and-Hold模式

**基准配置**：标准RTK（LAMBDA + Fix-and-Hold）

> ⚠️ 注意：本节为**RTK模式**测试，非PPP-AR。CascadeAR/PartialAR/Bootstrapping等在RTK和PPP-AR中的行为可能不同。

#### 差站-小偏移

| 配置 | fix率 | avgRatio | stdN(m) | stdE(m) | stdU(m) | 假固定 | maxOff(m) |
|------|-------|----------|---------|---------|---------|--------|-----------|
| baseline | 30.3% | 6.58 | 0.60 | 1.71 | 0.89 | 21 | 9.68 |
| CascadeAR | 3.2% | 10.00 | - | - | - | 0 | - |
| PartialAR+Bootstrap | 12.9% | 4.46 | 1.12 | 2.28 | 1.73 | 4 | 8.88 |
| ResEdit+BdsBias | 25.9% | 6.46 | 0.67 | 1.95 | 0.99 | 16 | 9.53 |
| SegmentSigma | 30.3% | 6.58 | 0.60 | 1.71 | 0.89 | 21 | 9.68 |
| AllRecommended | 3.5% | 8.13 | - | - | - | 0 | - |
| All+IGGIII | 2.3% | 4.35 | - | - | - | 0 | - |

#### 差站-大偏移

| 配置 | fix率 | avgRatio | stdN(m) | stdE(m) | stdU(m) | 假固定 | maxOff(m) |
|------|-------|----------|---------|---------|---------|--------|-----------|
| baseline | 25.6% | 12.96 | 0.41 | 0.21 | 1.45 | 24 | 9.96 |
| CascadeAR | 0.0% | - | - | - | - | 0 | - |
| PartialAR+Bootstrap | 28.4% | 5.75 | 0.48 | 0.65 | 1.50 | 8 | 10.58 |
| ResEdit+BdsBias | 23.5% | 11.95 | 0.53 | 0.14 | 1.72 | 24 | 10.03 |
| SegmentSigma | 25.6% | 12.96 | 0.41 | 0.21 | 1.45 | 24 | 9.96 |
| AllRecommended | 0.2% | 6.81 | - | - | - | 0 | - |
| All+IGGIII | 0.1% | 3.05 | - | - | - | 0 | - |

#### 好站-对照1

| 配置 | fix率 | avgRatio | stdN(m) | stdE(m) | stdU(m) | 假固定 | maxOff(m) |
|------|-------|----------|---------|---------|---------|--------|-----------|
| baseline | 90.0% | 90.69 | 0.013 | 0.023 | 0.018 | 4 | 0.69 |
| CascadeAR | 11.2% | 5.48 | - | - | - | 0 | - |
| PartialAR+Bootstrap | 90.0% | 24.20 | 0.014 | 0.027 | 0.053 | 2 | 0.67 |
| ResEdit+BdsBias | 90.3% | 90.64 | 0.043 | 0.050 | 0.039 | 5 | 1.55 |
| SegmentSigma | 90.0% | 90.69 | 0.013 | 0.023 | 0.018 | 4 | 0.69 |
| AllRecommended | 4.0% | 3.50 | - | - | - | 0 | - |
| All+IGGIII | 5.3% | 5.95 | - | - | - | 0 | - |

#### 逐项对照结论

| 优化项 | 差站fix率 | 好站fix率 | 假固定 | 评价 |
|--------|----------|----------|--------|------|
| **CascadeAR** | ❌ 30%→3%/0% | ❌ 90%→11% | ✅ 0 | **旧伪逐级实现历史数据（v2.2.x，静态短基线场景），v2.3.0 真逐级重写后此场景未重测**；重写后已通过 PPC-Dataset 动态场景 4 数据集 A/B 行为验证（0 violations，见 §10.7） |
| **PartialAR+Bootstrap** | ✅ 25.6%→28.4%(大偏移) | ✅ 90%不变 | ✅ 24→8(-67%) | **差站最有效**：大偏移提升fix率+2.8%，假固定减少67% |
| **ResEdit+BdsBias** | ⚠️ 略降 | ✅ 90%→90.3% | ❌ 不变 | 微弱效果，好站略升，差站略降 |
| **SegmentSigma** | ➖ 无变化 | ➖ 无变化 | ➖ 无变化 | 未触发，符合预期（静态短基线false fix少，σ未超阈值；该优化专为动态遮挡设计） |
| **AllRecommended** | ❌ 暴跌至0.2%~3.5% | ❌ 暴跌至4% | ✅ 0 | CascadeAR负面效应占主导 |
| **All+IGGIII** | ❌ 暴跌至0.1%~2.3% | ❌ 暴跌至5.3% | ✅ 0 | IGGIII无额外效果 |

**静态短基线推荐**：
- ✅ **推荐开启**：`enablePartialAR` + `enableBootstrapping`（显著减少假固定，大偏移差站还能提升fix率；**仅限RTK静态短基线**，动态严重遮挡下Bootstrapping无效见§13.5）
- ⚠️ **可选开启**：`enableResidualEdit` + `enableBdsCodeBias`（微弱效果，不恶化）
- ⚠️ **暂不建议开启**：`enableCascadeAR`（v2.3.0已重写为真逐级固定，动态场景验证通过见§10.7；静态短基线场景为旧伪实现数据，重写后未重测）
- ❌ **不应开启**：AllRecommended组合（CascadeAR拖累整体）
- ➖ **无效果**：`enableSegmentSigmaFilter`（静态短基线未触发，该优化专为动态遮挡设计）

### 17.4 按验证状态分类

| 状态 | 优化项 | 说明 |
|------|--------|------|
| ✅ **稳定有效** | 段内σ过滤 | 唯一跨场景稳定有效的优化（UrbanNav OFF: false fix 1.5%） |
| ✅ **场景有效** | IGGIII（动态长基线）、SNR中值（3/5数据集）、PartialAR+Bootstrap（RTK静态短基线）、AdaptiveQ（短基线严重遮挡） | 有效但依赖场景，部分场景下无效甚至有害 |
| ✅ **已验证（单元测试）** | 残差编辑、部分AR、Bootstrapping、BDS码偏差、基站监测 | 单元测试通过，待场景实测 |
| ⚠️ **场景依赖（已重测）** | **CascadeAR（v2.3.0 真逐级重写）**、**PartialAR（动态遮挡，false fix严重）** | CascadeAR：动态城市峡谷 4 数据集 A/B 通过（可用性 Nagoya 4~15×、run2 2×、run1 持平；0 violations 见 §10.7），但偏差污染区坏尾比例升高（run1 16% vs 2.1%），静态短基线未重测；PartialAR在动态遮挡下H.RMSE 63~266m |
| ⚠️ **已验证无效/场景受限** | 自适应Q（静态无效）、Bootstrapping（动态遮挡无效）、SegmentSigma（静态未触发） | 特定场景下无效果或不适用 |
| ⚠️ **未专项测试** | 锚固、大气冻结、PAR重选、电离层梯度、参数类型级噪声 | 理论有效，待实测验证 |

### 17.5 推荐组合

| 场景 | 推荐开启 | 不推荐 |
|------|---------|--------|
| **静态短基线** | `enablePartialAR` + `enableBootstrapping` + `enableSnrMedian`（弱收益，无害） + `enableResidualEdit`（微弱） + `enableBdsCodeBias`（微弱） | `enableCascadeAR`（动态场景已验证见§10.7，静态短基线场景未重测）、`enableAdaptiveQ`（无效）、`enableSegmentSigmaFilter`（未触发） |
| **静态长基线** | `enableIggiii` + `enableSnrMedian` + `enableParamTypeNoise` + `enableIonoTropGradient` | — （组合仅为理论推荐；长基线仅验证链路可跑通，FIX 率 1.3%，组合增益未做 A/B） |
| **动态开阔天空** | `enableSnrMedian` + `enableResidualEdit` | `enableCascadeAR`（城市峡谷已验证有效见§10.7，开阔天空无实测）、`enableSegmentSigmaFilter`（无需）、`enableAdaptiveQ`（理论推断弱收益，无开阔天空实测） |
| **动态城市峡谷** | `enableSegmentSigmaFilter`(σ<5m) + `enableSnrMedian`（无效果但无害，作为安全项保留） + `enableCascadeAR`（可选：可用性 Nagoya 4~15×/run2 2×，偏差污染区坏尾升高代价见§10.7） | `enableResidualEdit`（IFLC下有害，UrbanNav -75%）、`enableBootstrapping`（无效） |

> 💡 **短基线严重遮挡例外**：`enableAdaptiveQ`在UrbanNav短基线严重遮挡下Fix+12%有效，但中基线有害（Tokyo -49%/-20%）。短基线遮挡场景可加开，中基线禁用。
| **动态混合场景** | `enableSnrMedian` + `enableSegmentSigmaFilter`(σ<5m) | `enableResidualEdit`（IFLC下场景依赖）、`enableIggiii`（场景依赖）、`enableCascadeAR`（可选，城市峡谷子场景验证通过，见§10.7） |