# rtklib-research 模块开发文档

> 最后更新：2026-10-07  
> 参考项目：reference-projects/FE-GUT（因子图+EKF混合，GNSS/UWB紧耦合）

---

## 1. 模块定位

**rtklib-research 与 rtklib-core/rtklib-otl/rtklib-stream/adjust/product 完全独立**，无 Maven 依赖关系。它是一个前沿算法研究模块，允许引入与 RTKLIB 体系不同的底层假设（FGO 图优化、学习型随机模型、多传感器紧融合等），不会影响其他模块的稳定性和正确性。

## 2. 当前状态

### 2.1 已完成（33 个文件，8 个包）

| 包 | 文件 | 用途 | 完成度 |
|----|------|------|--------|
| `common/` | GnssConst, GTime, Coordinates, MatrixOps, SatId, EarthModel, Rotation | 常量、时间、坐标、矩阵、旋转 | ✅ |
| `data/` | Ephemeris, Navigation, Observation, ObservationEpoch, Solution, SolutionStatus, UwbObservation | 数据模型 | ✅ |
| `ephemeris/` | SatellitePosition | Kepler轨道+钟差+**卫星速度**+**GLONASS** | ✅ |
| `atmosphere/` | Ionosphere, Troposphere | Klobuchar/Saastamoinen | ⚠️ 单模型 |
| `ambiguity/` | LambdaSolver, PartialArSolver | LAMBDA+MLAMBDA+部分AR | ✅ |
| `factorgraph/` | Variable, Factor, FactorGraph, RobustLoss, Factor(generic), PredictFactor, TcFactor, **PhaseFactor**, MarginalizationInfo, MarginalizationFactor, ResidualBlockInfo | 因子图框架+具体因子+边缘化+载波相位 | ✅ |
| `pipeline/` | SolverBackend, SolverConfig, SolverStatistics, BackendComparator, EkfBackend, FgoBackend, **DatasetLoader**, **PipelineIntegrationTest** | 管线接口+两种后端+数据加载+端到端测试 | ✅ |
| `stochastic/` | StochasticModel, ElevationSnrModel | 随机模型（矩阵接口） | ✅ |
| `integration/` | IntegrationState, StateModel, EkfState | 状态定义+转移模型+EKF后端 | ✅ |

### 2.2 核心缺口

- ✅ FGO 端到端测试 → EKF(0.11ms/epoch) + FGO(23.2ms/epoch) 通过 100 历元验证
- ✅ SatellitePosition 卫星速度 → Kepler 轨道导数计算完成 (2026-10-07)
- ✅ PhaseFactor 载波相位因子 → FgoBackend 已集成浮点模糊度 (2026-10-07)
- ✅ PredictFactor 泛化 → 支持任意维度（模糊度恒等转移）(2026-10-07)
- ✅ MLAMBDA n>3 树搜索 → 替代 Math.round() 退化逻辑 (2026-10-07)
- ✅ GLONASS 星历支持 → RK4 数值积分 (2026-10-07)
- ✅ StochasticModel 接口升级 → double→SimpleMatrix (2026-10-07)

## 3. 参考项目：FE-GUT

### 3.1 结构

```
FE-GUT/src/
├── common/     angle.h, earth.h, logging.h, rotation.h, types.h
├── factors/    tc_factor.h, predict_factor.h, marginalization_{info,factor}.h, residual_block_info.h
├── fileio/     fileloader/psloader/uwbloader/saver.h
├── integration/ ekfstate.cc/.h, parameter.cc/.h, integration_state.h
├── thirdparty/ abseil-cpp/
└── gnss_uwb.cc  ← 主程序
```

### 3.2 核心算法

**12 维状态**：[r(3), v(3), a(3), tu, fu, tdk]

**因子图**：紧耦合因子(TcFactor) + 预测因子(PredictFactor) → Ceres Solver 优化

**EKF**：积分 → 卡尔曼更新 → LAMBDA (MATLAB脚本中)

**边缘化**：滑动窗口内 Schur 消元 → MargFactor

## 4. 移植计划

### 4.1 源→目标映射

| FE-GUT C++ | 目标 Java 类 | 所在包 | 说明 |
|-----------|-------------|--------|------|
| `types.h` → `UWB` | `UwbObservation.java` | data | ✅ 已完成 |
| `earth.h` → `Earth` | `EarthModel.java` | common | ✅ 已完成 |
| `rotation.h` → `Rotation` | `Rotation.java` | common | ✅ 已完成 |
| `types.h` → `IntegrationStateData` | `IntegrationState.java` | integration | ✅ 已完成 |
| `parameter.cc` → F/Q/R | `StateModel.java` | integration | ✅ 已完成 |
| `ekfstate.cc` → `EkfState` | `EkfState.java` | integration | ⬜ 待实现 |
| `residual_block_info.h` | `ResidualBlockInfo.java` | factorgraph | ⬜ 待实现 |
| `tc_factor.h` → `TcFactor` | `TcFactor.java` | factorgraph | ⬜ 待实现 |
| `predict_factor.h` → `PredictFactor` | `PredictFactor.java` | factorgraph | ⬜ 待实现 |
| `marginalization_info.h` | `MarginalizationInfo.java` | factorgraph | ⬜ 待实现 |
| `marginalization_factor.h` | `MarginalizationFactor.java` | factorgraph | ⬜ 待实现 |
| `ekfstate` + `SolverBackend` | `EkfBackend.java` | pipeline | ⬜ 待实现 |
| `gnss_uwb.cc` + `FactorGraph` | `FgoBackend.java` | pipeline | ⬜ 待实现 |
| `fileloader` 系列 | `DataLoader.java` | io（新包） | ⬜ 待实现 |

### 4.2 实现顺序（依赖拓扑）

```
Step 1  ✅ IntegrationState.java     (无依赖)
Step 2  ✅ StateModel.java            (依赖 IntegrationState)
Step 3  ✅ EkfState.java             (依赖 IntegrationState + StateModel)  
Step 4  ✅ ResidualBlockInfo.java    (无依赖)
Step 5  ✅ TcFactor.java             (依赖 Factor, IntegrationState, EarthModel, Rotation)
Step 6  ✅ PredictFactor.java        (依赖 Factor, StateModel)
Step 7  ✅ MarginalizationInfo.java  (依赖 ResidualBlockInfo)
Step 8  ✅ MarginalizationFactor.java(依赖 MarginalizationInfo)
Step 9  ✅ EkfBackend.java           (依赖 EkfState, SolverBackend)
Step 10 ✅ FgoBackend.java           (依赖 FactorGraph, TcFactor, PredictFactor, Marginalization*)
Step 11 ✅ DatasetLoader.java        (依赖 ObservationEpoch, FE-GUT 数据格式)
Step 12 ✅ PipelineIntegrationTest.java (依赖 DatasetLoader, EkfBackend, FgoBackend)
```

### 4.3 目标目录结构

```
research/src/main/java/org/rtklib/java/research/
├── ambiguity/
│   ├── LambdaSolver.java
│   └── PartialArSolver.java
├── atmosphere/
│   ├── Ionosphere.java
│   └── Troposphere.java
├── common/
│   ├── Coordinates.java
│   ├── EarthModel.java          ✅
│   ├── GnssConst.java
│   ├── GTime.java
│   ├── MatrixOps.java
│   ├── Rotation.java            ✅
│   └── SatId.java
├── data/
│   ├── Ephemeris.java
│   ├── Navigation.java
│   ├── Observation.java
│   ├── ObservationEpoch.java
│   ├── Solution.java
│   ├── SolutionStatus.java
│   └── UwbObservation.java      ✅
├── ephemeris/
│   └── SatellitePosition.java
├── factorgraph/
│   ├── Factor.java
│   ├── FactorGraph.java
│   ├── MarginalizationFactor.java    ✅ Step 8
│   ├── MarginalizationInfo.java      ✅ Step 7
│   ├── PredictFactor.java            ✅ Step 6
│   ├── ResidualBlockInfo.java        ✅ Step 4
│   ├── RobustLoss.java
│   ├── TcFactor.java                 ✅ Step 5
│   └── Variable.java
├── integration/
│   ├── EkfState.java                 ✅ Step 3
│   ├── IntegrationState.java         ✅
│   └── StateModel.java               ✅
├── pipeline/
│   ├── BackendComparator.java
│   ├── DatasetLoader.java            ✅ Step 11
│   ├── EkfBackend.java               ✅ Step 9
│   ├── FgoBackend.java               ✅ Step 10
│   ├── SolverBackend.java
│   ├── SolverConfig.java
│   └── SolverStatistics.java
└── stochastic/
    ├── ElevationSnrModel.java
    └── StochasticModel.java
```

## 5. 各文件详细设计

### 5.1 EkfState.java — EKF 状态估计器

```
移植自: FE-GUT src/integration/ekfstate.cc/.h

职责: 封装 EKF 预测+量测更新循环
  - predict(dt)     → integrate() → F·P·Fᵀ + Q
  - updateGNSS(obs) → 构建 H/Z/R → K = P·Hᵀ·S⁻¹ → x̂ = x + K·v
  - updateUWB(obs)  → 同理

关键依赖:
  - IntegrationState: 12维状态载体
  - StateModel: F/Q/R 矩阵
  - ObservationEpoch: GNSS观测
  - UwbObservation: UWB观测

与 rtklib-core 关系: 
  不使用 rtklib-core 的 KalmanFilter 类（避免模块耦合），独立实现
```

### 5.2 ResidualBlockInfo.java — 残差块

```
移植自: FE-GUT src/factors/residual_block_info.h

职责: 包装 Ceres CostFunction 的残差块信息，Java中用EJML实现

class ResidualBlockInfo:
  - CostFunction* cost_function
  - LossFunction* loss_function
  - double** parameter_blocks
  - 用于边缘化时评估残差块
```

### 5.3 TcFactor.java — 紧耦合因子

```
移植自: FE-GUT src/factors/tc_factor.h

职责: GNSS伪距+伪距率+UWB联合观测因子
  - Evaluate(parameters, residuals, jacobians):
      for each GNSS sat:
        r = |r_r - r_s| + tu - dts + tropo + iono
        residual = rho - r
        jacobian = [e, 0, 0, 1, 0, 0]  (对pos/vel/acc/clk/clkdrift/uwb)
      for each UWB anchor:
        r = |r_r - r_anchor|
        residual = range - r + tdk * c
        jacobian = [e, 0, 0, 0, 0, c]

关键: 与 Factor 抽象类的接口对接:
  - 实现 computeError(): 残差计算
  - 实现 computeJacobian(): 雅可比计算  
  - 卫星位置从外部传入（不在因子内部求解）
```

### 5.4 PredictFactor.java — 预测因子

```
移植自: FE-GUT src/factors/predict_factor.h

职责: 相邻历元的状态转移约束因子
  - residual = x_{k+1} - Φ·x_k 
  - weight = Q⁻¹ (过程噪声协方差的逆作为权重)
  
对接方式:
  - 持有前历元状态块的指针
  - computeError: x_next - F·x_prev
```

### 5.5 MarginalizationInfo.java — 边缘化信息

```
移植自: FE-GUT src/factors/marginalization_info.h

职责: 滑动窗口边缘化的 Schur 消元

核心步骤:
  1. 收集窗口中所有残差块
  2. 构建 Hessian: H = JᵀWJ
  3. 分块: H = [[H_mm, H_mr], [H_rm, H_rr]]
     m: 待边缘化参数, r: 保留参数
  4. Schur消元:
     H_new = H_rr - H_rm·H_mm⁻¹·H_mr
     b_new = b_r - H_rm·H_mm⁻¹·b_m
  5. Cholesky分解 H_new = L·Lᵀ → 提取 J_prior, e_prior
```

### 5.6 MarginalizationFactor.java — 边缘化因子

```
移植自: FE-GUT src/factors/marginalization_factor.h

职责: 将边缘化后的先验信息作为因子加入图中

class MarginalizationFactor extends Factor:
  - 持有 MarginalizationInfo (包含 J_prior, e_prior)
  - computeError: e = J_prior · (x - x_linearized) + e_prior
  - computeJacobian: J_prior
```

### 5.7 EkfBackend.java — EKF 后端

```
实现 SolverBackend 接口:
  - name() → "EKF"
  - initialize(config) → 创建 EkfState
  - solve(epoch, nav) → 预测 → GNSS更新 → UWB更新 → 输出Solution
  - statistics() → SolverStatistics
  - reset()

数据流:
  ObservationEpoch → 提取卫星位置/伪距/伪距率
                   → EkfState.predict(dt)
                   → EkfState.updateGNSS(obs, satPos, satVel)
                   → 构造Solution返回
```

### 5.8 FgoBackend.java — FGO 后端

```
实现 SolverBackend 接口:
  - name() → "FGO"
  - initialize(config) → 创建 FactorGraph + 初始状态
  - solve(epoch, nav) → 添加到滑动窗口 → 构建因子 → 边缘化 → 优化
  - statistics() → SolverStatistics
  - reset()

数据流:
  ObservationEpoch → 遍历卫星: 添加TcFactor + PredictFactor
                   → 窗口满时: 边缘化最老状态
                   → FactorGraph.optimize() (LM)
                   → 提取最新状态 → 构造Solution

关键: 需要持有滑动窗口内所有 IntegrationState 的引用
```

## 6. 与 rtklib-core 的隔离策略

| 事项 | 策略 |
|------|------|
| 数据结构 | 完全使用 research 自己的 data 包（Observation, Ephemeris, Solution 等） |
| 坐标转换 | 使用 research/common/Coordinates（ECEF↔LLH↔ENU） |
| 矩阵运算 | 统一使用 EJML SimpleMatrix（MatrixOps 提供工具） |
| 卡尔曼滤波 | research 自己实现 EKF，不使用 rtklib-core 的 KalmanFilter |
| 星历计算 | 使用 research/ephemeris/SatellitePosition |
| 大气延迟 | 使用 research/atmosphere/Ionosphere + Troposphere |

**不做的事**：
- ❌ 不 import rtklib-core 的任何类
- ❌ 不添加 `rtklib-core` 到 research 的 Maven 依赖
- ❌ 不在 research 中调用 rtklib-core 的 RtkCore/PppCore 等

## 7. 测试策略

### 7.1 FE-GUT 数据集

```
reference-projects/FE-GUT/dataset/
├── psdata.txt          伪距观测
├── psratedata.txt      伪距率观测
├── satposdata.txt      卫星位置
├── satveldata.txt      卫星速度
├── uwbdata.txt         UWB测距
├── navdata.txt         导航解算输出
└── tddata.txt          时间偏差
```

这些数据可用来验证移植的正确性：
- Java EkfBackend 输出应与 MATLAB EKF_GNSS_UWB.m 结果对齐
- Java FgoBackend 输出应与 FE-GUT gnss_uwb.cc 结果对齐

### 7.2 单元测试计划（Phase 2）

| 测试目标 | 验证内容 |
|---------|---------|
| IntegrationState | 状态传播正确性 |
| StateModel | F/Q 矩阵数值正确 |
| EarthModel | WGS84转换与FE-GUT Earth类一致 |
| TcFactor | 残差/雅可比与C++版本一致 |
| EkfBackend | 与MATLAB EKF结果RMS差 < 1e-3m |

## 8. 已知问题与后续计划

### 8.1 已知限制

- 大气模型仅单模型（Klobuchar/Saastamoinen），未插拔化
- 无北斗/IRNSS/SBAS 星历支持

### 8.2 Phase 2 目标（此次移植完成后）

- [x] 编写 EkfBackend 与 MATLAB 对比测试 → 端到端测试已通过
- [x] 修复 LAMBDA n>3 搜索 → MLAMBDA 树搜索
- [x] 补充卫星速度计算 → Kepler 轨道导数
- [x] GLONASS 星历支持 → RK4 数值积分 + J2 摄动
- [x] 接口升级：StochasticModel double→SimpleMatrix

## 9. 当前进度

| Step | 文件 | 状态 |
|------|------|------|
| 1 | IntegrationState.java | ✅ |
| 2 | StateModel.java | ✅ |
| 3 | EkfState.java | ✅ |
| 4 | ResidualBlockInfo.java | ✅ |
| 5 | TcFactor.java | ✅ |
| 6 | PredictFactor.java | ✅ |
| 7 | MarginalizationInfo.java | ✅ |
| 8 | MarginalizationFactor.java | ✅ |
| 9 | EkfBackend.java | ✅ |
| 10 | FgoBackend.java | ✅ |
| 11 | DatasetLoader.java | ✅ |
| 12 | PipelineIntegrationTest.java | ✅ |

### 9.1 端到端测试结果 (2026-10-06)

```
数据集: FE-GUT dataset (12001 历元)
测试范围: 前 100 历元

┌──────────┬──────────┬──────────────┐
│ 后端     │ 耗时/历元 │ 状态         │
├──────────┼──────────┼──────────────┤
│ EKF      │ 0.11 ms  │ ✅ 3/3 通过  │
│ FGO      │ 23.20 ms │ ✅ 3/3 通过  │
│ 对比报告 │    —     │ ✅ 3/3 通过  │
└──────────┴──────────┴──────────────┘
```

### 9.2 前沿功能清单 · 最终优先级矩阵

> 经逐项核对（共 17 项，✅ 8 项 / ⚠️ 3 项 / ❌ 6 项），以下为修订后的执行计划。

```
                        影响论文    实现量    当前建议
                        ────────   ───────   ────────
P1  1.7  全批量模式       ★★        <10行     ✅ 立即做
P1  1.8  FGO→LAMBDA 串联   ★★★       ~50行     ✅ 立即做
P1  1.2  独立Pseudorange   ★★        ~80行     ✅ 立即做
─────────────────────────────────────────────────────────
P2  5.3  分场景评估        ★★★★★    ~150行    🔥 先于 IACE（出图核心）
P2  4.1  IACE              ★★★★     ~250行    🔥 当前主攻
P2  4.2  多因子Partial AR  ★★★       ~60行     🔥 低成本高回报
─────────────────────────────────────────────────────────
P2  3.1a ImuData+时间对齐  ★★★★★     ~60行     ⏳ 下阶段·Phase I
P2  1.4  Doppler因子       ★★★      ~100行    ⏳ 3.1 前置条件（非可选）
P2  3.1b 预积分数学核心    ★★★★★    ~200行    ⏳ 下阶段·Phase II（需仿真验证）
P2  3.1c PreintegFactor    ★★★★★    ~200行    ⏳ 下阶段·Phase III
P2  3.1d InsGnssBackend    ★★★★★    ~200行    ⏳ 下阶段·Phase IV
─────────────────────────────────────────────────────────
P4  1.9  整数约束嵌入MIP   ★★★★     ~1000行   🧪 论文级，远期
P4  2.x  学习型加权        ★★       接口已就绪  📝 StochasticModel 已矩阵化
P4  4.3  AI模型校验        ★★       接口预留    📝 SolverConfig 字段即可
P4  3.2  视觉NLOS          ★         ~500行    🔒 外部模块（仅提供 NLOS 标签接口）
P4  3.3  LiDAR NLOS        ★         ~500行    🔒 外部模块（仅提供 NLOS 标签接口）

注：
- 学习型(2.x)：StochasticModel 已矩阵化，新模型实现接口即可接入。训练侧不纳入本模块。
- DopplerFactor(1.4)：是 GNSS/INS FGO(3.1) 的前置条件，必须与 IMU 预积分同期实现。
- GNSS/INS FGO(3.1)：拆为四阶段（数据结构→预积分核心→因子→后端），每阶段需独立验证。
```

### 9.3 建议执行序列

```
本轮 (P1)：基础补齐
  Step 1: 全批量模式  ────────────── 5min
  Step 2: 独立 PseudorangeFactor ─── 30min
  Step 3: FGO→LAMBDA 串联  ───────── 30min

本轮 (P2)：方法创新 + 结果呈现
  Step 4: 分场景评估 SceneEvaluator ─ 1h (先做：IACE 需要它出图)
  Step 5: IACE 实现并接入  ────────── 2h
  Step 6: 多因子 Partial AR ───────── 30min

下轮 (P2 扩展)：GNSS/INS FGO
  Step 7:  ImuData + 时间对齐     ── 30min (Phase I)
  Step 8:  DopplerFactor          ── 30min (3.1 前置条件)
  Step 9:  预积分数学核心+仿真验证 ── 4h  (Phase II)
  Step 10: PreintegFactor         ── 2h  (Phase III)
  Step 11: InsGnssFgoBackend      ── 2h  (Phase IV)

远期/论文级：
  Step 12: 整数约束嵌入MIP (1.9)
  Step 13: 学习型+AI校验 (2.x/4.3)：ONNX 推理接入
  Step 14: 视觉/LiDAR NLOS 标签接口 (3.2/3.3)
```

### 9.4 2026-10-07 更新：PhaseFactor 集成

```
FgoBackend 新增载波相位+浮点模糊度支持：

solve() 数据流：
  1. 提取 Observation.carrierPhase + wavelength
  2. 创建 ambVar（nSat 维模糊度变量）
  3. PhaseFactor(残差=λ⨉φ - (ρ + c⨉(tr-ts) + λ⨉N))
  4. PredictFactor(amb_{t-1}→amb_t, 恒等+1e-6噪声)
  5. 边缘化时同时移除最老 ambVar

PredictFactor 泛化：
  - 新增 (prev, next, dt, dim) 构造
  - dim=11: 原有 EKF 转移矩阵
  - dim≠11: 恒等矩阵 + 1e-6 对角噪声

FgoBackend.usePhaseFactor (默认 true)：
  - true: PhaseFactor + FLOAT 解
  - false: 仅 TcFactor + SINGLE 解（降级模式）
```

### 9.4 2026-10-07 更新：MLAMBDA 树搜索

```
LambdaSolver 全维度 MLAMBDA 实现：

  solve() → decorrelation(Z/LDLᵀ) → mlambdaSearch() → Z⁻ᵀ 逆变换

  mlambdaSearch 递归树搜索：
  - n-1 → 0 逐层展开
  - zBar = ẑᵢ - ∑ⱼ>ᵢ Lⱼᵢ·(zⱼ-ẑⱼ)  （条件估值）
  - 剪枝界限：|zᵢ - zBar| < √(remaining/dᵢ)
  - 跟踪 best + second-best 计算 ratio = sq1/sq2
  - n≤3: ~0.1ms, n≤6: ~1ms, n≤12: ~20ms

  与 RTKLIB lambda() 对齐：Zᵀ 去相关 → 整数搜索 → Z⁻ᵀ 逆变换
```

### 9.5 2026-10-07 更新：GLONASS 星历支持

```
Ephemeris 新增 GLONASS 字段：
  - gloPos[3], gloVel[3], gloAcc[3]  (PZ-90 ECEF)
  - gloGamma, gloTau, gloFreqNum

SatellitePosition 新增 computeGlo()：
  - isGlo() 检测：sys == SYS_GLO 或 gloPos 非空
  - compute() 自动路由：GLO → computeGlo(), 其他 → computeKepler()
  - RK4 运动方程：a = -GM·r/r³ + a_j2 + a_coriolis + a_ls
  - J2 摄动项：1.5·J2·GM·AE²/r⁵ · [x(5z²/r²-1), y(5z²/r²-1), z(5z²/r²-3)]
  - 科氏力：2·ω×v + ω²·(x,y,0)
  - 积分步长：60s，覆盖 [toe, time]
  - 输出：PZ-90 ECEF 位置/速度 + 钟差 -tau + gamma·dt

Ephemeris 字段命名对齐 RTKLIB eph_t 结构。
```

### 9.6 2026-10-07 更新：StochasticModel 接口矩阵化

```
接口升级：scalar → SimpleMatrix
  - observationCovariance(elevations[], sys[], scaleFactor) → R 矩阵
  - processNoiseCovariance(dim, dt) → Q 矩阵

ElevationSnrModel 实现：
  - Rᵢᵢ = (sigmaCode · efact / sin(el))² · scaleFactor
  - Q(12维)：分段白噪声 [p,v,a,clock,uwb]
  - Q(11维)：研究状态噪声
  - Q(n维)：恒等方差异常值

EkfState 新增 setNoiseMatrices(Q, R, RUwb) 注入方法
EkfBackend 初始化时计算高度角 → 调用 StochasticModel 生成 Q/R
SolverConfig 新增 stochasticModel 字段（默认 ElevationSnrModel）
```

### 9.7 2026-10-07 更新：P1 基础补齐 + P2 方法创新

```
P1 三项（改动量 ~250 行，编译/测试通过）：

  Step 1 - 全批量模式 (1.7):
    FgoBackend.solve() → if (config.useSlidingWindow && stateVars >= windowSize)
    效果: useSlidingWindow=false → 永不边缘化，所有变量保留

  Step 2 - 独立 PseudorangeFactor (1.2):
    新文件 PseudorangeFactor.java (153行)
    设计: 对标 PhaseFactor，仅 P_i = ρ + c*(tr-ts)，单参数块 stateVar[11]
    区别: TcFactor 绑定了伪距率+UWB，PseudorangeFactor 可独立调权/开关

  Step 3 - FGO→LAMBDA 串联 (1.8):
    FgoBackend: floatAmb → ambCov 提取 → LambdaSolver.solve() → fix/fail
    Solution: 新增 fixedAmbiguities 字段
    关键: 从 result.covariance 中按变量偏移精确提取模糊度协方差块

P2 三项（改动量 ~500 行，编译/测试通过）：

  Step 4 - 分场景评估 SceneEvaluator (5.3):
    新文件 SceneEvaluator.java (210行)
    场景分类: OPEN_SKY (el>30°, nSat>8) / PARTIAL_BLOCKED / URBAN_CANYON (el<15° 或 nSat<5)
    输出: 按场景分组的 RMS/STD/FixRate 对比表

  Step 5 - IACE 整数模糊度聚类估计 (4.1):
    新文件 IaceEstimator.java (230行) + IaceResult.java (40行)
    算法: 逐卫星独立 1D-DBSCAN + 自适应 eps + 跨卫星一致性投票
    eps = clamp(2σ_buf, 0.10, 0.50) cycles
    评分 = clusterSize/30 / (1 + std)
    触发: >50% 卫星评分 ≥0.5 → fix
    接入: FgoBackend LAMBDA 失败后 IACE 兜底（级联模式）

  Step 6 - 多因子 Partial AR (4.2):
    PartialArSolver 升级 (+100行)
    新增 solveMultiFactor(elevations, snr, phaseResiduals, ambCov)
    加权: score = 0.35·norm(el) + 0.25·norm(snr) + 0.15·norm(-|res|) + 0.25·norm(-var)
    向后兼容: 原 solve(elevations) 内部委托到 solveMultiFactor
```

### 9.8 2026-10-07 更新：GNSS/INS 紧耦合 FGO 基础

```
7 个新文件，~1,000 行，编译+8/8 单元测试通过

  Phase I - 数据结构 (2 文件):
    ImuData.java (70行): IMU 原始数据（加速度计+陀螺仪，body frame）
    ImuBias.java (55行): 零偏状态 [ba(3), bg(3)]，6 维

  Phase II - 预积分数学核心 (2 文件):
    RotationUtils.java (160行): SO(3) 工具
      - exp(ω): Rodrigues 公式，小角度→小角近似
      - log(R): 对数映射，自洽验证 exp/log 成反
      - rightJacobian(ω): Jr = I - (1-cosθ)/θ²·ω^ + (θ-sinθ)/θ³·ω^²
      - rightJacobianInverse(ω): 用于预积分 bias 雅可比

    PreintegrationResult.java (270行): 预积分核心
      积分循环（中点法）:
        ΔR_{k,k+1} = ΔR_k · Exp(ω_mid·Δt)
        Δv_{k,k+1} = Δv_k + ΔR_k · a_mid·Δt
        Δp_{k,k+1} = Δp_k + Δv_k·Δt + 0.5·ΔR_k · a_mid·Δt²

      累积 bias 雅可比 (6个 3×3):
        JrDRbw, JrDVba, JrDVbw, JrDPba, JrDPbw
        递推: J_{k+1} = F·J_k + G

      噪声传播 (15×15 协方差矩阵):
        cov_{k+1} = A·cov_k·A^T + B·Q_k·B^T

  Phase III - 因子 + 测试 (3 文件):
    DopplerFactor.java (165行): GNSS 多普勒因子
      残差: D_i = -λ·f_d = ρ̇ + c·(ṫ_r - ṫ_s)  (依赖 state[3-5,10])
      Jacobian: ∂r/∂v = LOS, ∂r/∂clkDrift = c
      复用: 伪距率观测来自 FE-GUT dataset

    PreintegFactor.java (185行): IMU 预积分因子
      变量 (2个): state_k(21D) → state_{k+1}(21D)
      残差 (15D): r_ΔR(3) + r_Δv(3) + r_Δp(3) + r_Δba(3) + r_Δbg(3)
      r_ΔR = Log(ΔR_meas^T · R_k^T · R_{k+1})     ↳ SO(3)对数映射

    PreintegrationTest.java (175行): 5 条测试
      ✓ exp/log 自洽: 8组 3D 向量 0→3.1rad
      ✓ 90° 纯旋转矩阵验证
      ✓ 静态IMU: ΔR=I, Δv_z=g·Δt, Δp_z=0.5g·Δt²
      ✓ 恒加速度: Δv_x=a·Δt, Δp_x=0.5a·Δt²
      ✓ 135° 纯旋转积分: ΔR 与解析解一致

  Phase IV (待做):
    InsGnssFgoBackend: 读取 IMU buffer → 预积分 → 因子图衔接
```