# rtklib-research 使用文档

## 1. 环境要求

- **JDK**: 17+
- **构建工具**: Maven 3.6+
- **依赖**: EJML 0.41（矩阵运算）
- **测试数据集**: FE-GUT 仿真数据集（`reference-projects/FE-GUT/dataset/`）

## 2. 快速开始

### 2.1 编译

```bash
cd rtklib-research
mvn compile
```

### 2.2 运行测试

```bash
# 全部测试（含集成测试，需要 FE-GUT 数据集）
mvn test

# 仅单元测试（无需数据集）
mvn test -Dtest=PreintegrationTest
```

### 2.3 数据集准备

```
rtklib_java/
├── rtklib-research/
└── reference-projects/
    └── FE-GUT/
        └── dataset/
            ├── pseudo_range.csv
            ├── pseudo_range_rate.csv
            ├── carrier_phase.csv
            ├── sat_positions.csv
            ├── sat_velocities.csv
            ├── uwb_data.csv
            └── truth_nav.csv
```

## 3. 基本用法

### 3.1 EKF 后端（单历元）

```java
import org.rtklib.java.research.pipeline.*;
import org.rtklib.java.research.data.*;
import java.util.List;

// 1. 加载数据集
DatasetLoader loader = new DatasetLoader("path/to/FE-GUT/dataset");
loader.loadAll();

// 2. 配置 EKF 后端
SolverConfig config = SolverConfig.forEkf();
config.elMaskDeg = 15.0;
config.snrMaskDbHz = 20.0;
config.maxIterations = 10;

// 3. 创建并初始化
SolverBackend backend = new EkfBackend("EKF");
backend.initialize(config);

// 4. 逐历元处理
List<ObservationEpoch> epochs = loader.getEpochs(0, 100);
Navigation nav = new Navigation();
List<Solution> solutions = backend.solveBatch(epochs, nav);

// 5. 统计
SolverStatistics stats = backend.statistics();
System.out.printf("Total: %d, Fix: %d, Avg time: %.2f ms%n",
    stats.totalEpochs, stats.fixEpochs, stats.avgComputeTimeMs);
```

### 3.2 FGO 后端（滑动窗口）

```java
SolverConfig config = SolverConfig.forFgo();
config.windowSize = 30;
config.useSlidingWindow = true;
config.useRobustLoss = true;
config.robustLossType = "HUBER";

SolverBackend backend = new FgoBackend("FGO");
backend.initialize(config);
List<Solution> solutions = backend.solveBatch(epochs, nav);
```

### 3.3 FGO + SwitchVariable（🆕 开关变量机制）

```java
// 方式1: 快速工厂方法
SolverConfig switchConfig = SolverConfig.forFgoWithSwitch();
switchConfig.switchPriorSigma = 0.1;  // 先验标准差（越小越约束 s→1.0）

FgoBackend switchBackend = new FgoBackend("FGO+Switch");
switchBackend.initialize(switchConfig);
List<Solution> solutions = switchBackend.solveBatch(epochs, nav);

// 查看开关变量状态
System.out.println("Switch info: " + switchBackend.lastSwitchInfo());
// 输出示例: "TC:sw=0.97 PH:sw=1.00"（值<1.0 表示观测被降权）
```

### 3.4 FGO + 场景自适应（🆕 在线环境感知）

```java
SolverConfig adaptiveConfig = new SolverConfig();
adaptiveConfig.backendName = "FGO+Scene";
adaptiveConfig.windowSize = 30;
adaptiveConfig.useSceneAdaptive = true;  // 开启自动策略切换

FgoBackend adaptiveBackend = new FgoBackend("FGO+Scene");
adaptiveBackend.initialize(adaptiveConfig);
List<Solution> solutions = adaptiveBackend.solveBatch(epochs, nav);

// 输出: OPEN_SKY→无鲁棒, PARTIAL_BLOCKED→Huber, URBAN_CANYON→SwitchVariable
```

### 3.5 EKF vs FGO 对比

```java
BackendComparator comparator = new BackendComparator();
comparator.addBackend(ekfBackend);
comparator.addBackend(fgoBackend);
comparator.compareAndReport(ekfSolutions);
System.out.println(comparator.formatComparisonTable());
```

## 4. FGO 详细配置

| 配置项 | 说明 | 默认值 |
|:---|:---|:---|
| `windowSize` | 滑动窗口大小 | 30 |
| `useSlidingWindow` | 是否边缘化（false=全批量） | true |
| `maxIterations` | Gauss-Newton 最大迭代 | 20 |
| `convergenceThreshold` | 收敛阈值 | 1e-6 |
| `useRobustLoss` | 启用 Huber 损失 | false |
| `robustLossThreshold` | Huber 阈值（σ 倍） | 1.5 |
| `usePhaseFactor` | 启用载波相位 | true |
| `useIace` | LAMBDA 失败后 IACE 兜底 | false |
| `usePartialAr` | 部分模糊度固定 | true |
| `useSwitchVariable` 🆕 | 启用开关变量机制 | false |
| `switchPriorSigma` 🆕 | 开关先验标准差 | 0.1 |
| `useSceneAdaptive` 🆕 | 启用场景自适应策略 | false |

### 全批量模式

```java
SolverConfig config = new SolverConfig();
config.backendName = "FGO-Batch";
config.useSlidingWindow = false;  // 永不边缘化
config.usePhaseFactor = true;
config.maxIterations = 30;
```

### Huber 鲁棒损失

```java
SolverConfig config = SolverConfig.forFgoWithHuber();
config.useRobustLoss = true;
config.robustLossType = "huber";
config.robustLossThreshold = 1.345;  // 95% 渐进效率
```

### SwitchVariable + Huber 双重鲁棒（🆕）

```java
SolverConfig config = SolverConfig.forFgoWithSwitchAndHuber();
// 等价于:
// config.useSwitchVariable = true;
// config.useRobustLoss = true;
// config.robustLossType = "HUBER";
// 效果: 开关变量关闭异常观测 + Huber降权残差噪声
```

## 5. 模糊度解算

### 5.1 三种策略对比

| 策略 | 类名 | 特点 |
|:---|:---|:---|
| LAMBDA/MLAMBDA | `LambdaSolver` | 全局整数最小二乘，精度最高 |
| Partial AR | `PartialArSolver` | 多因子排序，排除低质量卫星 |
| IACE | `IaceEstimator` | 逐卫星聚类，鲁棒性好 |

### 5.2 级联策略

```
FGO 浮点解 ─→ LambdaSolver.solve()
                ├── ratio >= 3.0 → FIX
                └── 失败 →
                      ├── PartialArSolver → FIX/FLOAT
                      └── 仍失败 + useIace=true →
                            IaceEstimator.estimate() → FIX/FLOAT
```

### 5.3 独立使用 IACE

```java
IaceEstimator estimator = new IaceEstimator(5, 30);
for (ObservationEpoch epoch : epochs) {
    // 提取浮点模糊度...
    for (int i = 0; i < nSat; i++) {
        estimator.addSample(satId, floatAmb[i]);
    }
    IaceResult ir = estimator.estimate(satIds);
    if (ir.success) {
        System.out.printf("IACE fix: score=%.2f, cluster=%d%n",
            ir.clusterScore, ir.bestClusterSize);
    }
}
```

### 5.4 多因子 Partial AR 权重

```java
PartialArSolver solver = new PartialArSolver(3.0, 4);

// 自定义四因子权重:
solver.setMultiFactorWeights(0.30, 0.30, 0.20, 0.20);
//   wElev=0.30  wSnr=0.30  wResidual=0.20  wVariance=0.20

PartialArResult result = solver.solveMultiFactor(
    floatAmb, ambCov, elevations, snrValues, phaseResiduals);
```

## 6. 场景评估

### 6.1 离线统计评估

```java
SceneEvaluator evaluator = new SceneEvaluator();
Map<Scene, SceneStats> stats = evaluator.evaluate(solutions, elevations, refPos);

System.out.printf("%-16s | %4s | %6s | %8s | %8s | %8s%n",
    "Scene", "Cnt", "Fix%", "RMS_E(m)", "RMS_N(m)", "RMS_U(m)");
for (Map.Entry<Scene, SceneStats> e : stats.entrySet()) {
    SceneStats s = e.getValue();
    String label = e.getKey() != null ? e.getKey().label : "OVERALL";
    System.out.printf("%-16s | %4d | %5.1f%% | %8.3f | %8.3f | %8.3f%n",
        label, s.count, s.fixRate * 100, s.rmsE, s.rmsN, s.rmsU);
}
```

### 6.2 在线场景分类（🆕）

```java
// 方法1: 纯卫星数分类
Scene scene = SceneEvaluator.classify(nSat);
// nSat > 8  → OPEN_SKY
// nSat 5-8  → PARTIAL_BLOCKED
// nSat < 5  → URBAN_CANYON

// 方法2: 高度角+卫星数精确分类
Scene scene = SceneEvaluator.classify(avgElevationDeg, nSat);

// 方法3: 自动获取自适应策略
SolverConfig adapted = SceneEvaluator.adaptiveStrategy(baseConfig, scene);
// OPEN_SKY       → useRobustLoss=false, useSwitchVariable=false
// PARTIAL_BLOCKED → useRobustLoss=true, Huber k=1.345
// URBAN_CANYON   → useSwitchVariable=true, sigma=0.1
```

## 7. IMU 预积分（独立使用）

```java
import org.rtklib.java.research.integration.*;
import org.rtklib.java.research.data.*;
import java.util.List;

List<ImuData> imuBuffer = new ArrayList<>();
for (int i = 0; i < 200; i++) {
    ImuData imu = new ImuData();
    imu.time = new GTime(0, i * 0.005);  // 200 Hz IMU
    imu.accel = new double[]{ax, ay, az};
    imu.gyro = new double[]{gx, gy, gz};
    imuBuffer.add(imu);
}

ImuBias bias = new ImuBias(
    new double[]{ba_x, ba_y, ba_z},
    new double[]{bg_x, bg_y, bg_z});

PreintegrationResult result = PreintegrationResult.integrate(
    imuBuffer, bias,
    0.01, 0.001,      // accel/gyro noise
    0.0001, 0.00001,   // bias random walk
    new double[]{0, 0, 9.81});  // gravity

// 结果:
SimpleMatrix dR = result.deltaR;    // 3x3, SO(3)
SimpleMatrix dV = result.deltaV;    // 3x1, body frame
SimpleMatrix dP = result.deltaP;    // 3x1, body frame
double dt = result.totalDt;         // 积分时长
```

**精度验证结果**（静止 IMU，100 帧 @ 100 Hz）：
- ΔR = I（精确到 1e-8）
- Δv_z = g·Δt（误差 < 2%）
- Δp_z = 0.5·g·Δt²（误差 < 2%）

## 8. 自定义扩展

### 8.1 自定义随机模型

```java
public class MyStochasticModel implements StochasticModel {
    @Override
    public SimpleMatrix observationCovariance(
            double[] elevations, int[] systems, double scale) {
        int n = elevations.length;
        SimpleMatrix R = new SimpleMatrix(n, n);
        for (int i = 0; i < n; i++) {
            R.set(i, i, 1.0 / Math.sin(Math.toRadians(elevations[i])));
        }
        return R;
    }

    @Override
    public SimpleMatrix processNoiseCovariance(int dim, double dt) {
        return SimpleMatrix.identity(dim).scale(0.01 * dt);
    }
}

// 注入:
config.stochasticModel = new MyStochasticModel();
```

### 8.2 自定义因子

```java
public class IonosphereFreeFactor extends Factor {
    public IonosphereFreeFactor(Variable stateVar, double[] obsP1, double[] obsP2,
                                 double[][] satPos, double[] satClkBias) {
        super("IF", Arrays.asList(stateVar));
        // ...
    }

    @Override public SimpleMatrix residual() { /* IF 组合残差 */ }
    @Override public SimpleMatrix jacobian(int vi) { /* 雅可比 */ }
    @Override public double error() { /* 误差 */ }
    @Override public int residualDimension() { return nSat; }
    @Override public List<Integer> parameterBlockSizes() { return Arrays.asList(11); }
    @Override public SimpleMatrix noiseCovariance() { /* R 矩阵 */ }
}

// 加入因子图:
graph.addFactor(new IonosphereFreeFactor(stateVar, p1Obs, p2Obs, satPos, clkBias));
```

## 9. 完整实验脚本模板

```java
public class FullExperiment {
    public static void main(String[] args) throws Exception {
        DatasetLoader loader = new DatasetLoader(
            "../reference-projects/FE-GUT/dataset");
        loader.loadAll();
        List<ObservationEpoch> epochs = loader.getEpochs(0, 500);
        Navigation nav = new Navigation();

        Map<String, SolverBackend> backends = Map.of(
            "EKF",    createEkf(),
            "FGO",    createFgo(false),
            "FGO+IA", createFgo(true));

        for (var entry : backends.entrySet()) {
            String name = entry.getKey();
            SolverBackend backend = entry.getValue();
            List<Solution> solutions = backend.solveBatch(epochs, nav);
            SolverStatistics stats = backend.statistics();

            System.out.printf("%-8s | FixRate=%5.1f%% | RMS=%.3f m | %.2f ms%n",
                name,
                100.0 * stats.fixEpochs / stats.totalEpochs,
                compute3dRMS(solutions),
                stats.avgComputeTimeMs);
        }
    }

    static SolverBackend createEkf() {
        EkfBackend b = new EkfBackend("EKF");
        b.initialize(SolverConfig.forEkf());
        return b;
    }

    static SolverBackend createFgo(boolean useIace) {
        SolverConfig cfg = SolverConfig.forFgoWithHuber();
        cfg.useIace = useIace;
        FgoBackend b = new FgoBackend(cfg.backendName);
        b.initialize(cfg);
        return b;
    }
}
```

## 10. 常见问题

**Q: 如何切换全批量/滑动窗口？**
```
config.useSlidingWindow = false;  // 全批量
config.useSlidingWindow = true;   // 滑动窗口（边缘化 keep windowSize）
```

**Q: n > 12 模糊度如何解算？**
MLAMBDA 树搜索自动处理。n=12 约 20ms，n>20 时建议配合 Partial AR 排除低质量卫星。

**Q: LAMBDA 和 IACE 的区别？**
- LAMBDA: 全局整数最小二乘，依赖协方差，精度高
- IACE: 逐卫星聚类，不依赖协方差，鲁棒性强

级联使用：`config.useIace = true` 后 LAMBDA 失败自动切 IACE。

**Q: IMU 预积分如何接入 FgoBackend？**
当前预积分数学核心可独立测试。接入 FgoBackend 需要：状态维度 11→21（加旋转+零偏），
提供 IMU 数据源。数学层已就绪。

**Q: 性能预期？**
- EKF 单历元: ~0.5 ms
- FGO 滑动窗口 30: ~5-15 ms（依赖卫星数）
- LAMBDA n=8: ~3 ms
- IACE: ~0.2 ms/卫星
- IMU 预积分 200 帧: ~2 ms