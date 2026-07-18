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

---

## 1. 滑动窗自适应Q矩阵（enableAdaptiveQ）

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

## 3. 大气参数自适应冻结（atmFrozenNsThresh）

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

udtrop() 未实现冻结（待补充）:
  当前仅 udion() 有冻结逻辑
```

### 3.4 配置参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `atmFrozenNsThresh` | 7 | 卫星数低于此值时冻结大气参数（0=关闭） |

### 3.5 实现位置

| 函数 | 文件 | 说明 |
|------|------|------|
| `udion()` | RtkCore.java | 入口检查 ns < atmFrozenNsThresh 时 return |

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

---

## 5. SNR中值参考星选择（enableSnrMedian）

### 5.1 问题背景

C版RTKLIB参考星仅按高度角选择，不考虑信号质量。
低SNR卫星被选为参考星时，双差观测质量差，影响模糊度固定。

### 5.2 解决方案

计算各频点SNR中值，用于改进参考星选择和观测权重。
SNR中值作为该频点的信号质量基准，可用于：
- 参考星选择时偏好高SNR卫星
- 观测权重调整时考虑SNR偏离

### 5.3 核心算法

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
| `computeSnrMedian()` | RtkOptimizations.java | 完整实现 |
| `relpos()` | RtkCore.java | 在udstate之前调用 |

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

---

## 7. 电离层/对流层梯度参数估计（enableIonoTropGradient）

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

ddres() 中梯度对双差残差的贡献:
  v[i] -= scaleI * cot(elI) * cos(azI) * xState[iiI+1]   // Gn 北向梯度
        + scaleJ * cot(elJ) * cos(azJ) * xState[iiJ+1]
  v[i] -= scaleI * cot(elI) * sin(azI) * xState[iiI+2]   // Ge 东向梯度
        + scaleJ * cot(elJ) * sin(azJ) * xState[iiJ+2]

H矩阵对应偏导数:
  H[nvOut*nx + iiI+1] += scaleI * cot(elI) * cos(azI)
  H[nvOut*nx + iiI+2] += scaleI * cot(elI) * sin(azI)
  H[nvOut*nx + iiJ+1] -= scaleJ * cot(elJ) * cos(azJ)
  H[nvOut*nx + iiJ+2] -= scaleJ * cot(elJ) * sin(azJ)
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
| `ddres()` | RtkCore.java | 梯度残差和H矩阵 |
| `udion()` | RtkCore.java | 梯度参数过程噪声 |

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