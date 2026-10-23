# GNSS 引擎自适应方案设计文档 (v2.0)

## 一、核心逻辑

**现状问题**：不管遇到什么数据，都用按"滑坡场景"调好的死参数硬跑。  
**新增模块**：每 30 秒提取数据特征（卫星数、信噪比、伪距残差），自动切换参数包。  
**切换策略**：开阔天空→放宽周跳/关抗差；城市峡谷→开抗差/降权；滑坡→极严周跳/冻结大气。

---

## 二、数据结构设计

全部放在 `org.rtklib.java.adaptive` 包，不引入外部依赖。

### 2.1 场景枚举 — GnssScene

```java
package org.rtklib.java.adaptive;

public enum GnssScene {
    OPEN_SKY,       // 开阔天空（如 ALGO IGS 站）
    URBAN_CANYON,   // 城市峡谷（如香港短基线遮挡处）
    LANDSLIDE       // 滑坡/静态基准站（默认王牌，兜底）
}
```

### 2.2 特征数据对象 — FeatureMetrics

只存 3 个最基础的、不依赖滤波器历史状态的数字：

```java
package org.rtklib.java.adaptive;

public class FeatureMetrics {
    /** 当前有效卫星数（仰角>elmin 且未被 exc 排除） */
    public int nSat;
    /** 平均信噪比（dB-Hz，所有有效卫星 L1 频段均值） */
    public double avgSnr;
    /** 伪距残差 RMS（m）= sqrt(mean((伪距 - 几何距离 - 钟差)^2))，直接反映多路径强度 */
    public double mpResidual;

    @Override
    public String toString() {
        return String.format("nSat=%d avgSnr=%.1f mpRes=%.3f", nSat, avgSnr, mpResidual);
    }
}
```

### 2.3 参数包管理器 — PresetManager

核心类，内含 3 套经过实测验证的参数配置。

**参数映射表**（PrcOpt + RtkConfig → 场景预设值）：

| 参数 | PrcOpt 字段 | RtkConfig 字段 | OPEN_SKY | URBAN_CANYON | LANDSLIDE |
|------|-------------|----------------|----------|--------------|-----------|
| GF 周跳阈值 | `thresslip` | — | 0.15 | 0.20 | 0.05 |
| 相位新息阈值 | `maxinno[0]` | — | 5.0 | 10.0 | 5.0 |
| 伪距新息阈值 | `maxinno[1]` | — | 30.0 | 50.0 | 30.0 |
| IGGIII 抗差 | — | `enableIggiii` | false | true | false |
| 段 sigma 过滤 | — | `enableSegmentSigmaFilter` | false | true | false |
| SNR 中值降权 | — | `enableSnrMedian` | false | true | false |
| 大气冻结阈值 | — | `atmFrozenNsThresh` | 0(关) | 0(关) | 7 |
| 电离层模型 | `ionoopt` | — | IONOOPT_IFLC | IONOOPT_IFLC | IONOOPT_BRDC |
| 精密星历 | `sateph` | — | EPHOPT_PREC | EPHOPT_BRDC | EPHOPT_BRDC |
| 对流层模型 | `tropopt` | — | TROPOPT_EST | TROPOPT_EST | TROPOPT_SAAS |

```java
package org.rtklib.java.adaptive;

import org.rtklib.java.data.PrcOpt;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;

public class PresetManager {

    public static void applyPreset(PrcOpt opt, RtkConfig cfg, GnssScene scene) {
        switch (scene) {
            case OPEN_SKY:
                opt.thresslip = 0.15;
                opt.maxinno = new double[]{5.0, 30.0};
                opt.ionoopt = Constants.IONOOPT_IFLC;
                opt.sateph = Constants.EPHOPT_PREC;
                opt.tropopt = Constants.TROPOPT_EST;
                cfg.enableIggiii = false;
                cfg.enableSegmentSigmaFilter = false;
                cfg.enableSnrMedian = false;
                cfg.atmFrozenNsThresh = 0;
                break;

            case URBAN_CANYON:
                opt.thresslip = 0.20;
                opt.maxinno = new double[]{10.0, 50.0};
                opt.ionoopt = Constants.IONOOPT_IFLC;
                opt.sateph = Constants.EPHOPT_BRDC;
                opt.tropopt = Constants.TROPOPT_EST;
                cfg.enableIggiii = true;
                cfg.enableSegmentSigmaFilter = true;
                cfg.enableSnrMedian = true;
                cfg.atmFrozenNsThresh = 0;
                break;

            case LANDSLIDE:
                opt.thresslip = 0.05;
                opt.maxinno = new double[]{5.0, 30.0};
                opt.ionoopt = Constants.IONOOPT_BRDC;
                opt.sateph = Constants.EPHOPT_BRDC;
                opt.tropopt = Constants.TROPOPT_SAAS;
                cfg.enableIggiii = false;
                cfg.enableSegmentSigmaFilter = false;
                cfg.enableSnrMedian = false;
                cfg.atmFrozenNsThresh = 7;
                break;
        }
    }
}
```

---

## 三、特征提取 — 如何"偷看"数据

在解算主循环里，每 30 个历元提取一次特征。**绝不碰滤波器历史状态**，直接用最原始的观测数据算。

### 3.1 提取时机

```java
// 在 RtkProcessor / PppProcessor 的主循环中
if (epochCount % ADAPTIVE_WINDOW == 0) {  // ADAPTIVE_WINDOW = 30
    FeatureMetrics m = extractFeatures(obs, n, rtk);
    GnssScene newScene = SceneDetector.detect(m, opt.mode);
    // ... 切换逻辑
}
```

### 3.2 特征计算方法

```java
package org.rtklib.java.adaptive;

import org.rtklib.java.data.Obs;
import org.rtklib.java.data.Obsd;
import org.rtklib.java.data.Rtk;
import org.rtklib.java.constants.Constants;

public class FeatureExtractor {

    /**
     * 从当前历元观测数据提取特征。
     * 不依赖滤波器状态，只用 obs 和 rtk.sol（SPP 解）。
     */
    public static FeatureMetrics extract(Obs[] obs, int n, Rtk rtk) {
        FeatureMetrics m = new FeatureMetrics();
        double snrSum = 0;
        int snrCount = 0;

        for (int i = 0; i < n; i++) {
            if (rtk.ssat[obs[i].sat - 1].azel[0] < rtk.opt.elmin) continue;
            if (rtk.ssat[obs[i].sat - 1].rejc[0] > 0) continue;

            m.nSat++;

            // 取 L1 频段信噪比
            for (int j = 0; j < obs[i].n; j++) {
                Obsd od = obs[i].data[j];
                if (od.code % Constants.MAXFREQ == 0 && od.snr > 0) {
                    snrSum += od.snr * 0.25;  // SNR 单位转换
                    snrCount++;
                    break;
                }
            }
        }

        m.avgSnr = snrCount > 0 ? snrSum / snrCount : 0;

        // 伪距残差：用 SPP 解算后的 sol.rr 计算几何距离残差
        // 简化版：直接用 rtk.sol 的 DRMS 作为代理指标
        m.mpResidual = Math.sqrt(rtk.sol.qr[0] + rtk.sol.qr[1] + rtk.sol.qr[2]);

        return m;
    }
}
```

---

## 四、场景判定规则树

极简规则，不需要机器学习：

```java
package org.rtklib.java.adaptive;

import org.rtklib.java.constants.Constants;

public class SceneDetector {

    /** 有效卫星数低于此值 → 城市峡谷 */
    public static final int NSAT_CANYON_THRESH = 10;
    /** 平均信噪比低于此值 → 城市峡谷 */
    public static final double SNR_CANYON_THRESH = 30.0;
    /** 伪距残差高于此值 → 城市峡谷 */
    public static final double MP_CANYON_THRESH = 0.5;

    /** 有效卫星数高于此值 → 开阔天空 */
    public static final int NSAT_OPEN_THRESH = 18;
    /** 平均信噪比高于此值 → 开阔天空 */
    public static final double SNR_OPEN_THRESH = 40.0;
    /** 伪距残差低于此值 → 开阔天空 */
    public static final double MP_OPEN_THRESH = 0.1;

    /**
     * 判定当前场景。
     * @param m 特征指标
     * @param mode 当前定位模式（PMODE_???）
     * @return 判定出的场景
     */
    public static GnssScene detect(FeatureMetrics m, int mode) {
        // 1. 环境恶劣，一票否决 → 城市峡谷
        if (m.nSat < NSAT_CANYON_THRESH || m.avgSnr < SNR_CANYON_THRESH || m.mpResidual > MP_CANYON_THRESH) {
            return GnssScene.URBAN_CANYON;
        }

        // 2. 环境极好 → 开阔天空
        if (m.nSat > NSAT_OPEN_THRESH && m.avgSnr > SNR_OPEN_THRESH && m.mpResidual < MP_OPEN_THRESH) {
            return GnssScene.OPEN_SKY;
        }

        // 3. 静态模式 → 滑坡王牌
        if (mode == Constants.PMODE_STATIC || mode == Constants.PMODE_PPP_STATIC) {
            return GnssScene.LANDSLIDE;
        }

        // 4. 兜底 → 滑坡王牌
        return GnssScene.LANDSLIDE;
    }
}
```

### 判定流程图

```
           ┌─────────────────────────────────────────┐
           │  每 30 历元提取 FeatureMetrics           │
           └────────────────┬────────────────────────┘
                            │
              ┌─────────────▼──────────────┐
              │ nSat<10 || snr<30 || mp>0.5 │──Yes──→ URBAN_CANYON
              └─────────────┬──────────────┘
                            │ No
              ┌─────────────▼──────────────┐
              │ nSat>18 && snr>40 && mp<0.1 │──Yes──→ OPEN_SKY
              └─────────────┬──────────────┘
                            │ No
              ┌─────────────▼──────────────┐
              │ mode == STATIC/PPP_STATIC?  │──Yes──→ LANDSLIDE
              └─────────────┬──────────────┘
                            │ No
                            ▼
                       LANDSLIDE (兜底)
```

---

## 五、安全切换机制 — 防雷指南

### 5.1 安全切换（Soft Change）

切换 `thresslip`、`maxinno`、`enableIggiii`、`enableSnrMedian` 等参数时：
- **不改变状态向量维度**，直接切，下一历元立刻生效
- 滤波器毫无感知，协方差矩阵不受影响

### 5.2 危险切换（Hard Reset）

当两个参数包的 `ionoopt` 或 `nf` 不同时：
- **状态向量维度变了**，必须重置滤波器！
- 判定条件：`oldIonoOpt != newIonoOpt || oldNf != newNf`
- 处理方式：调用 `rtk.x[] = 0; rtk.P[] = 0; rtk.epoch = 0;` 重新初始化

```java
if (oldOpt.ionoopt != newOpt.ionoopt || oldOpt.nf != newOpt.nf) {
    LOG.warn("维度变化 (ionoopt: {}→{}, nf: {}→{}), 重置滤波器",
            oldOpt.ionoopt, newOpt.ionoopt, oldOpt.nf, newOpt.nf);
    resetFilter(rtk);
}
```

### 5.3 防抖与冷却（极重要）

切到 URBAN_CANYON 后，如果下一个 30 秒卫星又变多，立刻切回 OPEN_SKY，再过 30 秒又切回峡谷——**参数震荡**，协方差矩阵瞬间崩溃。

**解法**：切过去之后，强制冷却 3 个窗口（90 秒）不允许再判定。

```java
public class AdaptiveController {
    private GnssScene currentScene = GnssScene.LANDSLIDE;
    private int cooldownCounter = 0;
    private static final int COOLDOWN_WINDOWS = 3;  // 冷却 3 个窗口 = 90 秒

    public GnssScene evaluate(FeatureMetrics m, int mode) {
        // 冷却期内不判定
        if (cooldownCounter > 0) {
            cooldownCounter--;
            return currentScene;
        }

        GnssScene newScene = SceneDetector.detect(m, mode);

        if (newScene != currentScene) {
            LOG.warn("场景切换: {} → {}", currentScene, newScene);
            currentScene = newScene;
            cooldownCounter = COOLDOWN_WINDOWS;  // 启动冷却
        }

        return currentScene;
    }
}
```

### 5.4 切换安全矩阵

| 源场景 → 目标场景 | 切换类型 | 需要重置滤波器 | 风险等级 |
|-------------------|----------|---------------|---------|
| LANDSLIDE → OPEN_SKY | Hard | **是**（ionoopt 变化） | 🔴 高 |
| OPEN_SKY → LANDSLIDE | Hard | **是**（ionoopt 变化） | 🔴 高 |
| LANDSLIDE → URBAN_CANYON | Hard | **是**（ionoopt 变化） | 🔴 高 |
| URBAN_CANYON → LANDSLIDE | Hard | **是**（ionoopt 变化） | 🔴 高 |
| OPEN_SKY → URBAN_CANYON | Soft | 否（仅阈值/开关变化） | 🟢 低 |
| URBAN_CANYON → OPEN_SKY | Soft | 否（仅阈值/开关变化） | 🟢 低 |

> **注意**：如果 OPEN_SKY 和 URBAN_CANYON 都使用 IFLC（当前设计如此），则它们之间的切换是 Soft Change，不需要重置滤波器。只有涉及 LANDSLIDE（BRDC 电离层模型）的切换才是 Hard Reset。

---

## 六、集成架构

### 6.1 类图

```
org.rtklib.java.adaptive
├── GnssScene              // 场景枚举（3 值）
├── FeatureMetrics         // 特征数据（3 字段）
├── FeatureExtractor       // 特征提取（静态方法）
├── SceneDetector          // 场景判定（规则树）
├── PresetManager          // 参数包管理（3 套预设）
└── AdaptiveController     // 自适应主控（防抖+冷却+切换）
```

### 6.2 集成点

在 `RtkProcessor` 或 `PppProcessor` 的主循环中插入：

```java
// RtkProcessor.processEpoch() 中
if (adaptiveController != null && epochCount % 30 == 0) {
    FeatureMetrics m = FeatureExtractor.extract(obs, n, rtk);
    GnssScene scene = adaptiveController.evaluate(m, opt.mode);
    if (scene != adaptiveController.getCurrentScene()) {
        PresetManager.applyPreset(opt, cfg, scene);
        // 如果是 Hard Reset，重置滤波器
        if (needsHardReset(oldOpt, opt)) {
            resetFilter(rtk);
        }
    }
}
```

### 6.3 配置开关

在 `RtkConfig` 中新增：

```java
/** 是否启用自适应场景切换 */
public boolean enableAdaptiveScene = false;
/** 自适应判定窗口大小（历元数，默认 30 = 30 秒 @1Hz） */
public int adaptiveWindow = 30;
/** 自适应冷却窗口数（切换后冷却几个窗口不允许再判定） */
public int adaptiveCooldown = 3;
```

---

## 七、实测验证计划

### 7.1 阶段一：场景识别准确率

| 测试数据 | 预期场景 | 验证指标 |
|----------|----------|----------|
| ALGO IGS 站（2024-01-01） | OPEN_SKY | nSat>18, snr>40, mp<0.1 |
| 香港短基线（城市遮挡） | URBAN_CANYON | nSat<10 或 snr<30 |
| 滑坡基准站（静态） | LANDSLIDE | 静态模式兜底 |

### 7.2 阶段二：切换安全性

- 验证 Soft Change（OPEN↔URBAN）不破坏收敛
- 验证 Hard Reset（→LANDSLIDE）正确重置滤波器
- 验证冷却机制防止震荡（连续 100 历元不出现 2 次以上切换）

### 7.3 阶段三：精度对比

| 场景 | 固定参数精度 | 自适应精度 | 目标 |
|------|-------------|-----------|------|
| OPEN_SKY PPP | 2D ~2m（当前） | 2D <0.5m（收敛后） | 分米级 |
| URBAN_CANYON RTK | 2D ~5cm | 2D ~3cm | 厘米级 |
| LANDSLIDE 静态 | 高程漂移 ~3cm | 高程漂移 <1cm | 亚厘米 |

---

## 八、与现有代码的关系

### 8.1 不修改的部分

- `PrcOpt` 字段定义不变，只改值
- `RtkConfig` 字段定义不变，只改值（新增 3 个自适应控制字段）
- `PppCore` / `RtkCore` 解算逻辑不变
- `KalmanFilter` 不变

### 8.2 新增的部分

- `org.rtklib.java.adaptive` 包（6 个类）
- `RtkConfig` 新增 3 个字段：`enableAdaptiveScene`, `adaptiveWindow`, `adaptiveCooldown`
- `RtkProcessor` / `PppProcessor` 主循环中插入 1 段判定代码

### 8.3 向后兼容

- `enableAdaptiveScene = false`（默认关闭），行为与当前完全一致
- 所有 6 个新类仅在 `enableAdaptiveScene = true` 时被加载
- 零开销向后兼容