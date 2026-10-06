# rtklib-research 功能文档

## 1. 模块概述

`rtklib-research` 是 RTKLIB 的前沿研究模块，与现有 `rtklib-core` 等模块完全独立。
目标是实验传统 RTKLIB 架构无法容纳的底层创新——因子图优化（FGO）、整数模糊度聚类估计（IACE）、
IMU 预积分紧耦合等。

**核心设计原则**：
- **零依赖隔离**：不依赖 `rtklib-core`，自有全部数据结构（`Observation`、`Ephemeris`、`Solution`）
- **SolverBackend 统一接口**：EKF / FGO / 学习型 / 紧融合通过同一接口切换，直接对比
- **FE-GUT 数据集驱动**：所有算法用仿真数据集验证，确保数学正确性

## 2. 全部功能清单

### 2.1 数据结构（`data/` 包 · 9 个文件）

| 类名 | 说明 | 维度 |
|:---|:---|:---|
| `Observation` | 单卫星观测（伪距、相位、多普勒、SNR） | — |
| `ObservationEpoch` | 历元级观测列表 + 时间 | — |
| `Ephemeris` | 广播星历（GPS Kepler + GLONASS PZ-90） | 38 字段 |
| `Navigation` | 导航数据容器（星历 + UWB 参数） | — |
| `Solution` | 解算结果（位置/速度/模糊度/协方差） | [0..3], [0..nSat] |
| `SolutionStatus` | 解状态枚举（FIX / FLOAT / SINGLE） | — |
| `UwbObservation` | UWB 测距观测 | — |
| `ImuData` | IMU 原始数据（加速度计+陀螺仪，body frame） | accel[3], gyro[3] |
| `ImuBias` | IMU 零偏状态 | ba[3], bg[3] |

### 2.2 共同工具（`common/` 包 · 7 个文件）

| 类名 | 说明 |
|:---|:---|
| `GTime` | GPS 时间（整秒 + 小数秒，支持 BDT/UTC 转换） |
| `GnssConst` | GNSS 物理常数（光速、引力常数、频率等） |
| `SatId` | 卫星 ID 编解码（系统码 + PRN） |
| `Coordinates` | ECEF↔LLH 坐标变换 |
| `EarthModel` | 地球模型（WGS84 椭球参数） |
| `MatrixOps` | EJML 矩阵工具（取列/取行/取块） |
| `Rotation` | 3D 旋转（四元数↔DCM） |
| `RotationUtils` | SO(3) 李群工具（exp/log 映射、right Jacobian） |

### 2.3 因子图优化（`factorgraph/` 包 · 9 个文件）

#### 核心框架

| 类名 | 说明 |
|:---|:---|
| `Variable` | 图节点变量（维数+值+固定标记） |
| `Factor` | 抽象基类（残差/雅可比/噪声协方差） |
| `FactorGraph` | 因子图容器（变量管理+增量优化） |
| `MarginalizationFactor` | 边缘化先验因子（Schur 补→线性先验） |
| `MarginalizationInfo` | 边缘化信息管理（滑动窗口状态移除） |
| `ResidualBlockInfo` | 残差块元信息（关联变量+loss 处理） |
| `RobustLoss` | 鲁棒核函数（Huber 1.5，防异常值污染） |

#### 具体因子

| 因子 | 观测模型 | 残差维数 | 参数块 |
|:---|:---|:---|:---|
| `TcFactor` | 紧耦合：伪距 + 伪距率 + UWB | 3nSat + nUwb | state[11] |
| `PseudorangeFactor` | 独立伪距：P = ρ + c·(tr-ts) | nSat | state[11] |
| `DopplerFactor` | 多普勒：D = ρ̇ + c·(ṫr-ṫs) | nSat | state[11] |
| `PhaseFactor` | 载波相位：λ·φ = ρ + c·(tr-ts) + λ·N | nSat | state[11], amb[nSat] |
| `PredictFactor` | 状态预测/模糊度转移 | dim | state_{t-1}, state_t |
| `PreintegFactor` | IMU 预积分：ΔR/Δv/Δp | 15 | state_k[21], state_{k+1}[21] |

### 2.4 模糊度解算（`ambiguity/` 包 · 4 个文件）

| 类名 | 算法 | 关键参数 | 复杂度 |
|:---|:---|:---|:---|
| `LambdaSolver` | LAMBDA + MLAMBDA 树搜索 | ratio≥3.0 | n≤3: 0.1ms<br>n≤12: 20ms |
| `PartialArSolver` | 部分模糊度固定（多因子加权） | minAmb=4, ratio=3.0 | O(n²·k) |
| `IaceEstimator` | 1D-DBSCAN 聚类（逐卫星独立） | eps∈[0.1,0.5] cycle<br>consistency≥50% | O(n·bufSize²) |
| `IaceResult` | IACE 输出结果 | score, clusterSize | — |

**级联策略**（FgoBackend 内建）：
```
LAMBDA → 失败 → IACE 聚类兜底
```

**多因子 Partial AR 加权公式**：
```
score_i = 0.35·norm(elevation_i) + 0.25·norm(snr_i)
        + 0.15·norm(-|phaseResidual_i|) + 0.25·norm(-variance_i)
```

### 2.5 状态估计后端（`pipeline/` 包 · 7 个文件）

| 类名 | 说明 |
|:---|:---|
| `SolverBackend` | 统一后端接口（solve/solveBatch/statistics） |
| `EkfBackend` | EKF 顺序滤波（预测+更新+M测更新） |
| `FgoBackend` | FGO 批量优化（滑动窗口+边缘化+因子图） |
| `SolverConfig` | 求解器配置（窗口/鲁棒/模糊度策略） |
| `SolverStatistics` | 运行统计（定位率/固定率/耗时） |
| `DatasetLoader` | FE-GUT 数据集加载器 |
| `BackendComparator` | 多后端对比工具（RMS/STD/FixRate 表） |
| `SceneEvaluator` | 分场景评估（Open Sky / Partial / Urban Canyon） |

**场景分类规则**：
| 场景 | 高度角 | 卫星数 |
|:---|:---|:---|
| OPEN_SKY | ≥30° | ≥8 |
| PARTIAL_BLOCKED | — | — |
| URBAN_CANYON | ≤15° | ≤4 |

### 2.6 IMU 预积分（`integration/` 包 · 3 个文件）

| 类名 | 说明 |
|:---|:---|
| `EkfState` | EKF 状态向量（12 维标准 / 抗差矩阵注入） |
| `IntegrationState` | 紧耦合积分状态 [r,v,a,tu,fu,t_uwb] |
| `StateModel` | 状态转移矩阵生成器 |
| `PreintegrationResult` | 预积分结果（ΔR/Δv/Δp + 6 偏雅可比 + 15×15 协方差） |

### 2.7 随机模型（`stochastic/` 包 · 2 个文件）

| 类名 | 说明 |
|:---|:---|
| `StochasticModel` | 抽象接口（obsCov / processNoise） |
| `ElevationSnrModel` | 高度角+SNR 加权（正弦映射，σ² = σ₀²/sin²(el)） |

### 2.8 大气延迟（`atmosphere/` 包 · 2 个文件）

| 类名 | 模型 |
|:---|:---|
| `Ionosphere` | Klobuchar 模型（8 参数广播星历） |
| `Troposphere` | Saastamoinen 模型（温度/气压/水汽压） |

### 2.9 星历计算（`ephemeris/` 包 · 1 个文件）

| 类名 | 支持系统 | 方法 |
|:---|:---|:---|
| `SatellitePosition` | GPS/GAL/QZS/CMP + GLO | Kepler 轨道 + RK4 数值积分 |

## 3. 功能关系图

```
                    SolverBackend (接口)
                    /              \
              EkfBackend        FgoBackend
                |                  |
          EkfState          FactorGraph
        StateModel          ├── TcFactor
        StochasticModel     ├── PseudorangeFactor
                            ├── DopplerFactor
                            ├── PhaseFactor + Variable(amb)
                            ├── PredictFactor
                            ├── PreintegFactor + PreintegrationResult
                            └── MarginalizationFactor

              └── 模糊度解算 ──┐
                               ├── LambdaSolver (MLAMBDA)
                               ├── PartialArSolver (多因子)
                               └── IaceEstimator (1D-DBSCAN)

              DatasetLoader → FE-GUT 数据集
                    ↓
              SceneEvaluator → 分场景统计
                    ↓
              BackendComparator → 对比表
```

## 4. 对应研究方向

| 功能 | 研究方向 | 参考论文/项目 |
|:---|:---|:---|
| FGO 滑动窗口 | 因子图优化 GNSS | FE-GUT, "Factor Graph Optimization for GNSS" |
| MLAMBDA 树搜索 | 整数最小二乘 | Teunissen (1995), Chang (2005) |
| IACE 聚类估计 | 聚类模糊度解算 | IACE: Integer Ambiguity Clustering Estimation |
| 多因子 Partial AR | 鲁棒部分固定 | "Partial Ambiguity Resolution with Multi-Factor" |
| IMU 预积分 | 视觉-惯性里程计 | Forster et al., TRO 2017 |
| SO(3) exp/log | 李群李代数 | "A micro Lie theory for state estimation" |
| DopplerFactor | GNSS 速度约束 | Doppler-based velocity estimation |
| Huber 鲁棒损失 | M-估计 | Huber (1964) |
| SceneEvaluator | 场景适应性评估 | Urban GNSS positioning evaluation |

## 5. 编译状态

```
  源文件: 50 个 Java (main)
  测试文件: 2 个 Java (test)
  测试通过: 8/8
  JDK: 17
  构建: Maven 3.x + EJML 0.41
```