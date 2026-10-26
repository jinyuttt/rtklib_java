# rtklib-joint 联合 RTK 解算模块设计文档

> 版本：v1.0  
> 日期：2026-10-26  
> 状态：定稿

---

## 1. 背景与动机

### 1.1 当前架构的精度天花板

当前 `rtklib-core` 的 RTK 解算为**单基线模式**：一个 Kalman 滤波器处理一条 Rover-Base 基线。多基站场景下，各基线独立解算后在 `rtklib-adjust` 中做结果空间融合。

结果空间融合的数学本质是**加权平均**，其精度改善：

```
σ_fused = √(σ_common² + σ_independent²/k)
```

短基线下 `σ_common >> σ_independent`（Rover 端多路径/天线 PCV 主导），融合对总精度改善 <1%。

**结论**：结果空间融合是可靠性工具，不是精度工具。

### 1.2 观测层联合解算的收益

观测层联合解算将 k 条基线的观测值送入**同一个 Kalman 滤波器**，Rover 坐标只出现一次但被 k 组观测同时约束。

| 指标 | 单基线 | 3 基线联合 | 改善来源 |
|------|--------|-----------|---------|
| FLOAT 解 Rover σ | σ₀ | σ₀/√3 ≈ 0.58σ₀ | 等价观测数 ×3 |
| 模糊度浮点解 σ | σ_N | σ_N/√3 ≈ 0.58σ_N | Rover 位置方差更小 → 模糊度方差更小 |
| LAMBDA 搜索空间体积 | V | V/3^(n/2) | 搜索空间显著缩小 |
| 固定成功率（困难环境） | ~60% | ~85%+ | 模糊度方差下降 → 固定率提升 |
| 固定解精度 | σ_fix | ≈ σ_fix | Rover 端公共误差仍在，<1% 改善 |

**最大实际收益**：困难环境下（部分遮挡、多路径严重）的**固定成功率提升**。

### 1.3 与结果空间融合的定位差异

| 维度 | rtklib-adjust（结果空间） | rtklib-joint（观测空间） |
|------|--------------------------|------------------------|
| 输入 | 逐历元 SolData（已解算） | 逐历元 Obsd（原始观测） |
| 基线解算 | 外部完成 | 内部完成（联合滤波器） |
| Rover 坐标 | 各基线独立解 → 加权平均 | 一个滤波器 → 一组解 |
| 模糊度 | 各基线独立固定 | 各基线独立固定（但方差更小） |
| 协方差 | 近似拼接 | 自然建模跨基线相关性 |
| 精度天花板 | ≈ 单基线 | FLOAT 阶段显著提升；FIX 阶段 ≈ 单基线 |
| 实时性 | ✅ 逐历元 | ✅ 逐历元 |
| 计算量 | 低 | 中（状态维度更大） |

---

## 2. 数学模型

### 2.1 状态向量

k 个基准站，1 个 Rover，双差模式：

```
x = [ δr_R(3),                    ← Rover 位置改正（共享）
      δv_R(3),                    ← Rover 速度改正（dynamics≥1）
      δa_R(3),                    ← Rover 加速度改正（dynamics=2）
      δI_1(nI_1),                 ← 基线1 电离层残差
      δT_1(nT_1),                 ← 基线1 对流层残差
      N_1(nb_1),                  ← 基线1 双差模糊度
      δI_2(nI_2),                 ← 基线2 电离层残差
      δT_2(nT_2),                 ← 基线2 对流层残差
      N_2(nb_2),                  ← 基线2 双差模糊度
      ...
      δI_k(nI_k),                 ← 基线k 电离层残差
      δT_k(nT_k),                 ← 基线k 对流层残差
      N_k(nb_k) ]                 ← 基线k 双差模糊度
```

状态维度：

```
nx = NR + Σ(NB_i)    其中 i = 1..k

NR = 3 + 3·[dynamics≥1] + 3·[dynamics=2]    ← Rover 参数
NB_i = nI_i + nT_i + nb_i                    ← 基线i 参数
```

### 2.2 状态索引函数

Rover 参数索引（与 RtkCore 兼容）：

```
IP(j) = j                              j = 0,1,2  位置
IV(j) = 3 + j                          j = 0,1,2  速度（dynamics≥1）
IA(j) = 6 + j                          j = 0,1,2  加速度（dynamics=2）
```

基线 i 的参数索引（偏移量 `off_i`）：

```
off_1 = NR                              ← 第一条基线起始偏移
off_i = NR + Σ(NB_j), j=1..i-1         ← 第i条基线起始偏移

II_i(sat) = off_i + (sat-1)             ← 基线i 电离层索引
IT_i(r)   = off_i + nI_i + (nT/2)·r    ← 基线i 对流层索引
IB_i(sat,f) = off_i + nI_i + nT_i + MAXSAT·f + (sat-1)  ← 基线i 模糊度索引
```

### 2.3 观测方程

对基线 i（Rover-Base_i），参考卫星 ref_i，共视卫星 j_i：

**零差残差**（复用 core 的 `zdres` 逻辑）：

```
y_R[j] = Φ_R[j] - ρ_R[j] - T_R[j] + I_R[j] - δt_R    ← Rover 零差
y_B[j] = Φ_B[j] - ρ_B[j] - T_B[j] + I_B[j] - δt_B    ← Base_i 零差
```

**双差观测方程**（联合 ddres 核心重写）：

```
v[nv] = (y_R[ref_i] - y_B_i[ref_i]) - (y_R[j_i] - y_B_i[j_i])
        - H_r · δr_R
        - H_I · δI_i
        - H_T · δT_i
        - λ · N_i[j_i,f]
```

**关键**：所有 k 组双差的 `H_r` 都指向**同一个** δr_R（状态向量前 3 个元素）。

### 2.4 H 矩阵结构

```
         δr_R   δI_1  δT_1  N_1   δI_2  δT_2  N_2   ...
H = ┌    H_r1   H_I1  H_T1  λI    0     0     0    ┐   ← 基线1 双差
    │    H_r1   H_I1  H_T1  λI    0     0     0    │   ← 基线1 双差(频2)
    │    H_r2   0     0     0     H_I2  H_T2  λI   │   ← 基线2 双差
    │    H_r2   0     0     0     H_I2  H_T2  λI   │   ← 基线2 双差(频2)
    │    ...                                        │
    └    H_rk   0     0     0     ...   H_Ik  H_Tk  λI  ┘   ← 基线k 双差
```

特征：
- 前 3 列（δr_R）**所有行都有值** → Rover 被所有基线共同约束
- 模糊度列**块对角** → 各基线模糊度独立
- 电离层/对流层列**块对角** → 各基线大气参数独立

### 2.5 R 矩阵（观测噪声协方差）

**首期方案**：块对角近似

```
R = diag(R_1, R_2, ..., R_k)
```

各基线 R_i 沿用 core 的 `varerr()` 逻辑（基于高度角、SNR、码相位比）。

**后续增强**：建模跨基线 Rover 公共噪声

```
R = diag(R_1, R_2, ..., R_k) + σ_common² · J
```

其中 J 是跨基线 Rover 公共误差的关联矩阵。短基线下 σ_common 较小，块对角近似合理。

### 2.6 Kalman 滤波

**预测**（时间更新）：

```
x_k|k-1 = F · x_k-1|k-1
P_k|k-1 = F · P_k-1|k-1 · Fᵀ + Q
```

Q 矩阵块对角：Rover 位置/速度过程噪声 + 各基线电离层/对流层/模糊度过程噪声。

**更新**（量测更新）：

```
K = P_k|k-1 · Hᵀ · (H · P_k|k-1 · Hᵀ + R)⁻¹
x_k|k = x_k|k-1 + K · (z - H · x_k|k-1)
P_k|k = (I - K · H) · P_k|k-1
```

直接复用 core 的 `KalmanFilter.update()`。

### 2.7 模糊度固定

**首期策略**：各基线独立固定

```
对基线 i = 1..k：
  提取 N_i 的浮点解和协方差子块
  LAMBDA(N_i_float, P_Ni) → N_i_fixed
  验证 ratio > threshold
  若通过 → 固定并更新 x, P
```

各基线模糊度方差已比独立解算更小（Rover 位置被多基线共同约束），独立固定已能获得主要收益。

**后续增强**：联合固定

```
LAMBDA([N_1; N_2; ...; N_k], P_N_all) → [N̄_1; N̄_2; ...; N̄_k]
```

理论上最优（搜索空间最小），但维度 = Σnb_i，可能很大。需评估 LAMBDA 搜索效率。

---

## 3. 架构设计

### 3.1 模块定位

```
rtklib-joint：观测层多基线联合 RTK 解算模块
  ├── 依赖：rtklib-core（Maven 依赖，引用类型和方法，不引用数据流）
  ├── 核心重写：联合 Kalman 滤波器、联合双差观测方程、状态管理
  └── 复用 core：星历、大气、天线、Kalman、LAMBDA、数据结构
```

### 3.2 与 core 的交互边界

#### 决策：修改 core（private → public + RtkState 接口），不复制

**理由**：
1. 已有先例：PppCore 的 `antmodel()`/`varerr()` 已在 2026-09-24 从 private 改为 public，供 PppRtkCore 复用
2. 复制 ~660 行（含 IFLC 协商依赖链）后，RtkCore 每月 3-4 次变更需人工同步，不一致风险高
3. RtkState record 成本极低（4 个字段），Rtk 和 JointRtk 各加 1 个 toState() 即可

#### core 改动清单

**新增 RtkState 数据载体**（core 中，Java 17 record）：

```java
public record RtkState(PrcOpt opt, Ssat[] ssat, RtkConfig rtkConfig, double[] snrMedian) {}
```

字段核对（与 Rtk.java 实际字段逐一确认）：

| record 字段 | Rtk.java 字段 | 行号 | 类型 | 名称 |
|------------|--------------|------|------|------|
| `PrcOpt opt` | `public PrcOpt opt` | L45 | ✅ | ✅ |
| `Ssat[] ssat` | `public Ssat[] ssat` | L43 | ✅ | ✅ |
| `RtkConfig rtkConfig` | `public RtkConfig rtkConfig` | L63 | ✅ | ✅ |
| `double[] snrMedian` | `public double[] snrMedian` | L67 | ✅ | ✅ |

语义：数据传递，不是行为抽象。zdres/varerr 等方法需要读这 4 样东西，
Rtk 和 JointRtk 各自打包成 RtkState 传入，类层次完全独立。

**Rtk 加便利方法**（core 中，1 行）：

```java
public class Rtk implements Serializable {
    // ... 现有代码不变 ...
    public RtkState toState() { return new RtkState(opt, ssat, rtkConfig, snrMedian); }
}
```

**JointRtk 加便利方法**（joint 中，1 行）：

```java
public class JointRtk {
    // ... joint 自己的字段 ...
    public RtkState toState() { return new RtkState(opt, ssat, rtkConfig, snrMedian); }
}
```

**调用方式**：

```java
// core 内部（RtkCore.relpos 中）
zdres(rtk.toState(), base, obs, ...);

// joint 内部（JointRelPos 中）
RtkCore.zdres(jrtk.toState(), base, obs, ...);
```

**方法可见性修改**（core 中 RtkCore.java）：

| 方法 | 当前 | 改为 | 签名变更 |
|------|------|------|---------|
| zdres() | private static | public static | `Rtk` → `RtkState`（record） |
| antmodel() | private static | public static | 无（不依赖 Rtk） |
| varerr() | private static | public static | `Rtk` → `RtkState`（record） |
| prectrop() | private static | public static | `Rtk` → `RtkState`（record） |
| selsat() | private static | public static | 无 |
| detslpLl() | private static | public static | `Rtk` → `RtkState`（record） |
| detslpGf() | private static | public static | `Rtk` → `RtkState`（record） |
| detslpCode() | private static | public static | `Rtk` → `RtkState`（record） |
| detslpDop() | private static | public static | `Rtk` → `RtkState`（record） |
| tidedisp() | private static | public static | 无 |
| validobs() | private static | public static | 无 |
| initx() | private static | public static | 无 |
| baseline() | private static | public static | 无 |
| interpvar() | private static | public static | 无 |
| gfobs() | private static | public static | 无 |
| gfobsPair() | private static | public static | 无 |
| iflcSlots() | private static | public static | `Rtk` → `RtkState`（record） |
| iflcNegotiationActive() | private static | public static | `Rtk` → `RtkState`（record） |
| 索引函数 II/IT/IB/IL/NP/NI/NT/NB/NR/NL | private static | public static | 无 |

**core 改动总量**：~1 个新 record + Rtk 加 1 个 toState() + 18 个方法改可见性 + ~8 个方法改参数类型

#### 直接调用（Maven 依赖，不改 core）

| 组件 | core 位置 | 用途 |
|------|----------|------|
| EphModel.satposs() | ephemeris/ | 卫星位置/钟差 |
| IonosphereModel.ionmodel() | ionosphere/ | 电离层延迟 |
| TroposphereModel.* | troposphere/ | 对流层延迟 |
| KalmanFilter.update() | kalman/ | Kalman 量测更新 |
| Lambda.* | ambiguity/ | LAMBDA 搜索 |
| CoordTransform.* | coord/ | 坐标转换 |
| TimeSystem.* | time/ | 时间系统 |
| SatUtils.* | common/ | 卫星系统工具 |
| ObsCode.* | common/ | 信号码映射 |
| RtklibCommon.* | common/ | 通用工具 |
| 数据结构 | data/ | Obsd, Nav, Sol, Ssat, PrcOpt, RtkConfig, GTime, Eph... |

#### 直接调用（core 改动后，joint 可直接复用）

| 组件 | core 位置 | 用途 |
|------|----------|------|
| RtkCore.zdres() | rtkpos/ | 零差残差计算 |
| RtkCore.antmodel() | rtkpos/ | 天线 PCV 模型 |
| RtkCore.varerr() | rtkpos/ | 观测噪声方差 |
| RtkCore.prectrop() | rtkpos/ | 对流层偏导 |
| RtkCore.selsat() | rtkpos/ | 共视卫星选择 |
| RtkCore.detslpLl/Gf/Code/Dop() | rtkpos/ | 周跳检测 |
| RtkCore.iflcSlots() | rtkpos/ | IFLC 频率对协商 |
| RtkCore 索引函数 | rtkpos/ | 状态向量索引 |

#### 完全重写（joint 模块内）

| 组件 | 行数(估) | 说明 |
|------|---------|------|
| JointCore.rtkpos() | ~200 | 联合定位主循环 |
| JointRelPos.relpos() | ~400 | 联合相对定位 |
| JointDdRes.ddres() | ~300 | 联合双差观测方程（核心） |
| JointUdState.udstate() | ~300 | 联合状态预测 |
| JointUdBias.udbias() | ~200 | 联合模糊度状态更新 |
| JointAmbFix.manageAmb() | ~300 | 联合模糊度固定策略 |
| JointRtk | ~200 | 联合滤波器状态 |
| EpochAligner | ~150 | 多基站历元对齐 |
| JointProcessor | ~200 | 入口数据流 |

**总重写量**：~2300 行核心算法（无复制，core 改动自动受益）

### 3.3 模块结构

```
rtklib-joint/
  ├── pom.xml
  └── src/main/java/org/rtklib/java/joint/
      ├── JointProcessor.java           ← 入口，数据流管理
      ├── JointCore.java                ← 联合 rtkpos 主循环
      ├── JointRelPos.java              ← 联合 relpos
      ├── JointDdRes.java               ← 联合双差观测方程（核心重写）
      ├── JointUdState.java             ← 联合状态预测
      ├── JointUdBias.java              ← 联合模糊度状态更新
      ├── JointAmbFix.java              ← 模糊度固定策略
      ├── EpochAligner.java             ← 多基站历元对齐
      ├── config/
      │   └── JointConfig.java          ← 联合解算配置
      └── model/
          ├── JointRtk.java             ← 联合滤波器状态（含 toState() 方法）
          ├── BaseStation.java          ← 基站标识 + 坐标 + 观测缓冲
          ├── BaselineGroup.java        ← 同一历元的多基线观测集合
          └── JointSol.java             ← 联合解输出
```

---

## 4. 核心类设计

### 4.1 JointRtk — 联合滤波器状态

```java
public class JointRtk implements Serializable {
    // ── Rover 共享状态 ──
    public Sol sol;                      // 解输出（Rover 位置/速度/精度）
    public double[] x;                   // 状态向量 [nx]
    public double[] P;                   // 协方差矩阵 [nx*nx]
    public double[] xa;                  // 固定后状态 [na]
    public double[] Pa;                  // 固定后协方差 [na*na]
    public int nx;                       // 状态维度
    public int na;                       // Rover 参数维度（= NR）

    // ── 多基线管理 ──
    public int nBaseline;                // 基线数量 k
    public BaseStation[] bases;          // 基站数组 [k]
    public int[] baselineOffset;         // 各基线在 x 中的偏移 [k+1]
    public int[] baselineAmbCount;       // 各基线模糊度数 [k]

    // ── 卫星状态 ──
    public Ssat[] ssat;                  // 逐卫星状态 [MAXSAT]

    // ── 配置 ──
    public PrcOpt opt;                   // 处理选项（复用 core）
    public JointConfig jointConfig;      // 联合解算配置

    // ── 诊断 ──
    public int nfix;                     // 固定历元计数
    public int epoch;                    // 历元计数

    // ── 索引方法 ──
    public int II(int baseline, int sat) { ... }   // 基线i 电离层索引
    public int IT(int baseline, int r) { ... }     // 基线i 对流层索引
    public int IB(int baseline, int sat, int f) { ... }  // 基线i 模糊度索引
    public int baselineDim(int i) { ... }          // 基线i 参数维度
}
```

### 4.2 BaseStation — 基站

```java
public class BaseStation implements Serializable {
    public String id;                    // 基站标识（"M"/"N"/"Y"）
    public double[] rb;                  // ECEF 坐标 [3]
    public Obsd[] obsBuf;               // 最新观测缓冲
    public int obsCount;                // 有效观测数
    public GTime obsTime;               // 观测时间
    public int nSat;                     // 共视卫星数
    public int[] satIndex;              // 共视卫星索引
    public int refSat;                  // 参考卫星
}
```

### 4.3 EpochAligner — 历元对齐

```java
public class EpochAligner {
    private final double maxTimeDiff;   // 最大时间差（默认 0.05s）

    // 输入各基站最新观测，输出对齐后的 BaselineGroup
    public BaselineGroup align(Map<String, Obsd[]> baseObs, GTime roverTime) { ... }

    // 检查所有基站观测时间是否在容忍范围内
    public boolean isAligned(Map<String, GTime> baseTimes, GTime roverTime) { ... }
}
```

### 4.4 JointDdRes — 联合双差观测方程（核心）

```java
public final class JointDdRes {
    /**
     * 构建多基线联合双差观测方程。
     *
     * 对每条基线 i = 1..k：
     *   对每个共视卫星对 (ref_i, j_i)：
     *     对每个频率 f = 0..nf-1：
     *       v[nv] = 双差残差
     *       H[nv, 0:3] = Rover 位置偏导（所有基线指向同一个 δr_R）
     *       H[nv, II_i] = 电离层偏导
     *       H[nv, IT_i] = 对流层偏导
     *       H[nv, IB_i] = λ（模糊度偏导）
     *       R[nv] = 观测噪声方差
     *
     * @return 有效观测数 nv
     */
    public static int ddres(JointRtk rtk, BaselineGroup group,
                            double[] v, double[][] H, double[] R) { ... }
}
```

### 4.5 JointConfig — 联合解算配置

```java
public class JointConfig implements Serializable {
    // ── 基线配置 ──
    public int maxBaseline = 3;          // 最大基线数
    public double maxTimeDiff = 0.05;    // 历元对齐容忍时间差 (s)

    // ── 大气参数策略 ──
    public boolean sharedIono = true;    // 短基线：电离层残差共享
    public boolean sharedTrop = false;   // 对流层各基线独立
    public double sharedIonoMaxDist = 10000.0;  // 共享电离层最大基线长度 (m)

    // ── 模糊度固定策略 ──
    public boolean independentAmbFix = true;    // 首期：各基线独立固定
    public boolean jointAmbFix = false;         // 后续：联合固定

    // ── R 矩阵策略 ──
    public boolean blockDiagonalR = true;       // 首期：块对角
    public boolean modelCrossCorrelation = false; // 后续：建模跨基线相关性

    // ── 周跳检测 ──
    public boolean jointCycleSlip = true;  // 联合周跳检测（Rover 周跳在所有基线共模）
}
```

---

## 5. 数据流设计

### 5.1 实时模式

```
Rover 实时流 ──decode──→ obs_R
                          │
Base_M 实时流 ──decode──→ obs_M ─┐
Base_N 实时流 ──decode──→ obs_N ─┤── EpochAligner ──→ BaselineGroup
Base_Y 实时流 ──decode──→ obs_Y ─┘
                          │
                          ▼
                   JointCore.rtkpos(jrtk, obs_R, group, nav)
                          │
                          ▼
                     JointSol（Rover 位置 + 精度 + 各基线状态）
```

### 5.2 事后模式

```
RINEX_R ──read──→ obs_R[t1], obs_R[t2], ...
RINEX_M ──read──→ obs_M[t1], obs_M[t2], ...
RINEX_N ──read──→ obs_N[t1], obs_N[t2], ...
RINEX_Y ──read──→ obs_Y[t1], obs_Y[t2], ...
                          │
                    逐历元对齐
                          │
                          ▼
                   JointCore.rtkpos() × N_epochs
                          │
                          ▼
                     联合解时间序列
```

### 5.3 JointProcessor 入口

```java
public class JointProcessor {
    private JointRtk jrtk;
    private EpochAligner aligner;
    private Nav nav;

    // 初始化
    public void init(JointConfig config, PrcOpt opt, double[][] baseCoords) { ... }

    // 输入 Rover 观测
    public void feedRover(Obsd[] obs, int n, GTime time) { ... }

    // 输入基站观测
    public void feedBase(String baseId, Obsd[] obs, int n, GTime time) { ... }

    // 输入导航数据
    public void feedNav(Nav nav) { ... }

    // 处理一个历元（当所有基站数据就绪时自动触发）
    public JointSol process() { ... }
}
```

---

## 6. 与现有模块的关系

### 6.1 与 rtklib-core

```
rtklib-joint ──Maven依赖──→ rtklib-core
  ├── 复用类型：Obsd, Nav, Sol, Ssat, PrcOpt, RtkConfig, GTime, Eph, ...
  ├── 复用方法：EphModel.*, IonosphereModel.*, TroposphereModel.*,
  │            KalmanFilter.*, Lambda.*, CoordTransform.*, ...
  ├── 复用方法（core 改 private→public）：zdres, antmodel, varerr, prectrop,
  │            detslpLl, detslpGf, detslpCode, detslpDop, selsat,
  │            iflcSlots, iflcNegotiationActive, II/IT/IB/IL/NP/NI/NT/NB/NR/NL
  │            （通过 RtkState record 传参，见 3.2 节）
  └── 重写：    ddres, udstate, udbias, relpos（多基线联合核心）
               RtkProcessor → JointProcessor（多基线数据流，见 3.3 节）
```

### 6.2 与 rtklib-adjust

```
rtklib-adjust：结果空间融合（可靠性工具）
  ├── 输入：SolData（已解算基线结果）
  ├── 输出：加权平均坐标 + 可靠性评定
  └── 场景：实时多基站、快速质检

rtklib-joint：观测空间联合（精度+可靠性工具）
  ├── 输入：Obsd（原始观测）
  ├── 输出：联合解坐标 + 精度
  └── 场景：困难环境、事后高精度
```

**两者并存**，互补而非替代：
- 实时场景 + 基线质量好 → adjust（简单、低延迟）
- 困难环境 + 需要固定率 → joint（计算量更大，但固定率更高）
- 事后场景 → joint（精度优先）

### 6.3 与 rtklib-product

joint 模块的 `JointSol` 可被 product 模块消费，格式化输出与现有 `Sol` 兼容。

---

## 7. 实现路线

### 执行顺序（调整后）

核心原则：**先验证联合 RTK 有效性，再重构配置**。RtkConfig 组合模式重构不是 joint 的硬前置——当前 RtkState 用扁平 RtkConfig 完全可行，重构后精简 record 是优化而非前提。

```
Step 0: RtkState record（用当前扁平 RtkConfig）+ core 可见性修改     1d
Step 1: joint Phase 1（2 基线 + 双频 GPS）                         11.5d
Step 2: 真实数据验证——固定率是否提升                                 —
Step 3: 若验证通过 → RtkConfig 重构 → RtkState 精简               5d
Step 4: joint Phase 2 + 3                                        13d
```

先做 joint 的理由：
- 核心假设优先验证（12.5d vs 17.5d，早 5d 知道联合 RTK 是否有效）
- 新增模块零回归风险（不改 core 任何一行，不影响其他并行开发）
- RtkConfig 重构后置更稳（joint 跑通后可实测对比，而非假设收益）

### Phase 0：core 改动（前置）

| 任务 | 估计 | 说明 |
|------|------|------|
| RtkState record | 0.5d | Java 17 record，用当前扁平 RtkConfig |
| core 方法可见性修改 | 0.5d | 18 个 private→public static，8 个方法签名 Rtk→RtkState |
| **合计** | **1d** | |

### Phase 1：最小可用版本（2 基线 + 双频 GPS）

| 任务 | 估计 | 说明 |
|------|------|------|
| 模块骨架 + pom.xml | 0.5d | Maven 模块、依赖 core |
| JointRtk + BaseStation | 1d | 状态向量、索引函数 |
| JointObsModel | 0.5d | 调用 core 改可见性后的方法（RtkState 传参），无需复制 |
| JointDdRes | 2d | 联合双差观测方程（核心） |
| JointUdState + JointUdBias | 1.5d | 状态预测、模糊度更新 |
| JointRelPos + JointCore | 2d | 主循环 |
| JointAmbFix（独立固定） | 1d | 各基线独立 LAMBDA |
| EpochAligner | 0.5d | 2 基线历元对齐 |
| JointProcessor | 1d | 入口数据流 |
| 测试 + 验证 | 2d | 与单基线对比、固定率验证 |
| **合计** | **~11.5d** | |

验证标准：
- 2 基线联合解的 FLOAT 解精度优于单基线
- 2 基线联合解的固定率 ≥ 单基线
- 固定解精度 ≈ 单基线（天花板验证）

### Phase 2：通用化（k 基线 + 多系统）

| 任务 | 估计 |
|------|------|
| k 基线通用化 | 2d |
| BDS + GAL 支持 | 2d |
| 三频支持 | 1d |
| 事后 RINEX 流程 | 2d |
| **合计** | **~7d** |

### Phase 3：增强

| 任务 | 估计 |
|------|------|
| 联合模糊度固定 | 2d |
| 跨基线 R 相关性建模 | 2d |
| 联合周跳检测 | 1d |
| 电离层/对流层共享策略 | 1d |
| **合计** | **~6d** |

---

## 8. 参考软件

### 8.1 源码核实记录

| 软件 | 本地路径 | 语言 | 架构 | 多基线/多站支持 | 核心参考价值 |
|------|---------|------|------|---------------|-------------|
| **Ginan** | D:\code\ginan | C++ | UDUC 非差非组合 + 单一大滤波器 | ✅ **所有接收机存储在同一个 KFState**，自动 chunking | **架构参考**：多站联合 Kalman 的状态组织、chunking 优化 |
| **GICI-LIB** | D:\code\gici-open | C++ | FGO (Ceres) + SD/DD 误差项 | ⚠️ 单基线 RTK（rtk_estimator），但 DD 误差项设计可扩展 | **DD 误差项参考**：`GnssMeasurementDDIndexPairs` 四索引设计、参考星选择 `system_to_base_prn` |
| **GAMIT/GLOBK** | 未下载（MIT 学术授权） | Fortran | LSQ + DD 差分算子 | ✅ **网解模式**：多站联合双差，独立基线选择 + 数学相关性处理 | **数学模型参考**：差分算子算法、跨基线数学相关性、独立基线选择（见 8.4 节） |
| **goGPS Java** | D:\code\goGPS | Java | EJML 矩阵 + KF | ❌ 单基线（`KF_DD_code_phase` 用 `sats.pivot`） | **Java 同语言参考**：矩阵操作、观测组织 |
| **PRIDE-PPPAR** | D:\code\PRIDE-PPPAR | Fortran | LSQ + LAMBDA | ❌ 单站 PPP-AR | **PPP-AR 参考**：WL/NL 分步固定、δ门检验（已移植到 core） |
| **GREAT-PVT** | D:\code\GREAT-PVT | C/Fortran | IAU SOFA + Eigen | ❌ 单站 PPP | 通用 GNSS 基础库参考 |
| **RTKLIB C** | D:\code\rtklib | C | EKF + DD | ❌ 单基线 | **原始参考**：core 的 C 原版 |

### 8.2 Ginan 核心设计（直接可借鉴）

Ginan `ppppp.cpp` 的关键注释：

> *"The distinction between 'Network' and 'User' positioning modes is not required in Ginan. **All receivers are always stored in a large single filter.** Depending on the configuration, the state and its's covariance matrix may turn out to be block-diagonal (user-mode), which will automatically be treated optimally upon the estimation stage by applying 'chunking'."*

**对 joint 的启示**：
- Ginan 用 UDUC（非差非组合），不需要参考星，避免了我们面临的双差参考星问题
- 但我们的 joint 沿袭 core 的 DD 路线（与 RtkCore 数学模型一致），必须解决参考星统一
- Ginan 的 chunking 思想可用于优化：当某些基线观测独立时，P 矩阵自动分块，可并行更新

### 8.3 GICI-LIB 参考星选择（直接可借鉴）

GICI-LIB `gnss_common.cpp:formPseudorangeDDPair()` 的参考星选择逻辑：

```cpp
// system_to_base_prn: 每个系统一个参考星 PRN
std::map<char, std::string> system_to_base_prn;

// 如果外部已指定，直接用
if (system_to_base_prn.find(system) != system_to_base_prn.end() && 
    !system_to_base_prn.at(system).empty()) continue;

// 否则选参考站最高仰角卫星
double max_elevation = 0.0;
for (size_t j = 0; j < sd_pairs.size(); j++) {
    double elevation = satelliteElevation(
        measurement_ref.getSat(sd_pairs[j].ref).sat_position,   // 用参考站位置
        measurement_ref.position);
    if (max_elevation < elevation) {
        system_to_base_prn[system] = sd_pairs[j].rov.prn;
        max_elevation = elevation;
    }
}
```

**DD 对四索引**：每个 DD 观测记录 `rov`, `ref`, `rov_base`, `ref_base`，参考星索引显式传递。

### 8.4 GAMIT/GLOBK 核心参考（学术授权，未下载）

#### 获取方式

- 官网：http://www-gpsg.mit.edu
- 需学术申请（大学/政府机构，非商业用途），获取下载密码后从 MIT FTP 下载
- 文档在线：http://www-gpsg.mit.edu/~simon/gtgk/GAMIT.pdf（GAMIT 处理手册）
- 理论章节：http://rses.anu.edu.au/geodynamics/gps/papers/gamit/chap2_ps.pdf

#### 核心算法：差分算子（Difference Operator）

GAMIT 的核心创新是 **差分算子算法**（Bock et al. 1986, Schaffrin & Bock 1988）：

> *"GAMIT incorporates difference-operator algorithms that map the carrier beat phases into singly and doubly differenced phases. These algorithms extract the maximum relative positioning information from the phase data regardless of the number of data outages, and take into account the correlations that are introduced in the differencing process."*

**对 joint 的关键启示**：

1. **数学相关性**：双差观测之间因共用参考星和共用测站而引入数学相关性。GAMIT 的差分算子显式构建相关协方差矩阵，而非忽略
2. **独立基线选择**：R 个测站、S 颗卫星的网络中，独立双差观测数为 (R-1)(S-1)。GAMIT 选择 R-1 条独立基线（如以一个测站为枢纽），而非处理所有 C(R,2) 条基线
3. **网解 vs 基线解**：GAMIT 的网解模式将所有独立基线在同一个 LSQ 中联合求解，考虑跨基线数学相关性；基线解模式则逐条独立求解，忽略跨基线相关性

#### 与 joint 的对应关系

| GAMIT 概念 | joint 对应 | 说明 |
|-----------|-----------|------|
| 差分算子 D | JointDdRes 中的双差构造 | GAMIT 显式构建 D 矩阵并传播协方差；joint 首期用块对角 R 近似 |
| 独立基线 (R-1 条) | k 条基线（k 个基站 → 1 个 Rover） | joint 中基线数 = 基站数，天然独立（共享 Rover 坐标） |
| 数学相关性 | 跨基线 R 矩阵非对角块 | 共用参考星导致跨基线观测相关；首期忽略，后续补充 |
| 网解模式 | 联合 Kalman 滤波 | joint 的核心目标 |
| 基线解模式 | rtklib-adjust 的结果融合 | 已有实现，joint 是升级 |

#### 推荐下载

据 GAMIT 文档，核心实现位于 `solve/`（LSQ 网解）、`model/`（双差观测模型）、`lib/`（差分算子）目录，具体文件名需下载后确认。

### 8.5 多基线解（MBS）模型论文参考

| 论文 | 年份 | 核心内容 | 与 joint 的关系 |
|------|------|---------|---------------|
| Wang et al., "BDS/GPS Multi-Baseline Relative Positioning for Deformation Monitoring", Remote Sensing 14(16):3884 | 2022 | MBS 模型考虑跨基线相关性，CMBS 模型加入空间相关对流层先验约束；20km 基线 + 200m 高差实测验证 | **直接参考**：MBS = joint 的学术名称，CMBS 的对流层约束可借鉴 |
| Hou et al., "A high-precision deformation monitoring method with GNSS multi-baseline solutions", Acta Geodaetica et Cartographica Sinica | 2025 | 利用时空相关性推导 MBS 解析解，理论分析增加监测站数对浮点解精度的提升，遮挡环境模拟测试验证 | **理论支撑**：证明了多基线联合在困难环境下提升收敛速度和固定率 |
| Santos et al., "Effect of Mathematical Correlation on GPS Network Computation" | 2001 | 对比含/不含数学相关性的网解结果，证明短基线（<10km）影响小，长基线影响显著 | **简化依据**：joint 首期忽略跨基线数学相关性，短基线场景误差可接受 |
| Schaffrin & Bock, "GAMIT Adjustment Algorithm", IUGG General Assembly | 1988 | 差分算子 + 双频 + 电离层约束的完整数学模型 | **理论基础**：GAMIT 的数学模型是 joint 的理论原型 |
| 张宝成等, "一种无须变换参考星的GNSS单基线卡尔曼滤波算法", 测绘学报 44(9):958-964 | 2015 | 通过改写双差观测方程，使参考星不出现在设计矩阵中，避免参考星切换时模糊度重定义问题 | **参考星切换参考**：joint 的 ΔN 变换方案（9.2 节）是此文的简化版；若后续发现 ΔN 变换引入数值问题，可改用此文的无变换方案 |

#### Wang 2022 MBS 状态向量（核心公式）

Wang et al. (2022) 的 MBS 模型状态向量：

```
X = [Xr, Yr, Zr, RZWD_rb1, ..., RZWD_rbm, ∇ΔN_rb1^l1q1, ..., ∇ΔN_rbm^lmqm]^T
```

- `(Xr, Yr, Zr)`：Rover 坐标（所有基线共享）
- `RZWD_rbi`：基线 i 的对流层天顶湿延迟残差（各基线独立）
- `∇ΔN_rbi^liqi`：基线 i 的双差模糊度（各基线独立）

**设计矩阵结构**（m 条基线，每条基线 ni 个 DD 观测）：

```
H = | H1_pos  H1_trop1  0     ...  0     H1_amb1  0      ...  0    |
    | H2_pos  0         H2_trop2 ...  0     0        H2_amb2 ...  0    |
    | ...                                                                 |
    | Hm_pos  0         0     ...  Hm_tropm 0       0       ...  Hm_ambm|
```

- `Hi_pos`：基线 i 对 Rover 坐标的偏导（所有基线共享 Rover，所以都有值）
- `Hi_tropi`：基线 i 对自身对流层参数的偏导（仅对角块）
- `Hi_ambi`：基线 i 对自身模糊度的偏导（仅对角块）

**协方差矩阵结构**（首期忽略跨基线数学相关性）：

```
P = | P_pos    P_pos,t1  ...  P_pos,tm  P_pos,a1  ...  P_pos,am |
    | P_t1,pos P_t1      ...  0         P_t1,a1   ...  0        |
    | ...                                                        |
    | P_am,pos P_a1,t1   ...  0         P_a1      ...  0        |
```

首期简化：跨基线非对角块（P_ti,tj, P_ai,aj, P_ti,aj）置零，仅保留 Rover 坐标相关块。

---

## 9. 联合双差参考星统一设计

### 9.1 问题

单基线 RTK 中，每条基线独立选参考星（最高仰角卫星）。多基线联合时：

- 基站 M 可见 {G10, G12, G15, G23}，选 G10 为参考星
- 基站 N 可见 {G12, G15, G23, G31}，选 G12 为参考星（G10 不可见）
- Rover 可见 {G10, G12, G15, G23, G31}

如果各基线用不同参考星，联合状态向量中同一颗卫星的模糊度在不同基线中含义不同（基线 M 的 N_G15 是相对 G10 的双差模糊度，基线 N 的 N_G15 是相对 G12 的），**数学上不严密**。

### 9.2 方案：全局统一参考星 + 基线局部回退

#### 原则

1. **同一系统内，所有基线使用同一个参考星**——保证联合状态向量中模糊度含义一致
2. **参考星从所有基线的共视卫星中选择**——保证每条基线都能形成双差
3. **某基线看不到全局参考星时，退化为该基线的局部参考星**——不丢弃观测

#### 算法

```
输入：k 条基线的共视卫星集 S_1, S_2, ..., S_k
输出：每个系统的全局参考星 ref[sys]，各基线的有效参考星 ref_k[sys]

对每个卫星系统 sys：
  1. 计算所有基线的交集：S_common = S_1 ∩ S_2 ∩ ... ∩ S_k
  2. if S_common 非空：
       ref[sys] = S_common 中仰角最高者（以 Rover 仰角为准）
       所有基线使用 ref[sys]
  3. else：
       // 没有任何卫星被所有基线同时看到
       // 选被最多基线看到的卫星
       ref[sys] = argmax_{s} |{k : s ∈ S_k}|  （同票取仰角最高）
       看不到 ref[sys] 的基线使用自己的局部参考星
```

#### 对模糊度的影响

- **情况 1（所有基线用同一参考星）**：联合状态向量中，同一卫星的模糊度在所有基线中含义一致，可直接联合 LAMBDA 搜索
- **情况 2（部分基线退化为局部参考星）**：退化的基线中模糊度含义不同，**不能与全局参考星基线联合固定**，但可独立固定

#### 参考星切换处理

参考星切换时，双差模糊度发生重定义：

```
N_new = N_old + ΔN
ΔN = N_ref_old - N_ref_new   （两个参考星的模糊度差）
```

需要：
1. 更新状态向量中对应模糊度的值：`x[IB] += ΔN`
2. 更新协方差：`P` 中对应行/列做相应变换
3. 标记周跳，触发模糊度重初始化

**与 core 现有逻辑的差异**：core 的 `ddres` 每历元隐式重新选参考星（`refIdx`），不做 ΔN 变换，模糊度通过 `detslpLl`/`detslpGf` 检测周跳后 `initx` 直接重置。joint 的 ΔN 变换是**新增逻辑**，目的是在参考星切换时保持模糊度连续性（而非重置），参考 core 的周跳触发时机，但处理方式不同。测试时需验证：参考星切换后模糊度值是否连续（ΔN 变换正确），而非被重置。

### 9.3 实现

```java
// JointRefSatManager.java
public class JointRefSatManager {
    
    // 每个系统的全局参考星
    private int[] globalRefSat = new int[6];  // GPS/GLO/GAL/BDS/QZS/IRN
    
    // 每条基线的有效参考星（可能退化为局部）
    private int[][] baselineRefSat;  // [baselineIdx][sysIdx]
    
    // 每历元更新
    public void update(BaselineGroup baselines, double[] roverAzel) {
        for (int sys = 0; sys < 6; sys++) {
            // 1. 求所有基线的共视卫星交集
            int[] commonSats = intersectAllBaselines(baselines, sys);
            
            if (commonSats.length > 0) {
                // 2. 从交集中选最高仰角
                globalRefSat[sys] = selectHighestElevation(commonSats, roverAzel);
                // 所有基线用全局参考星
                for (int k = 0; k < baselines.size(); k++) {
                    baselineRefSat[k][sys] = globalRefSat[sys];
                }
            } else {
                // 3. 交集为空，选被最多基线看到的卫星
                globalRefSat[sys] = selectMostVisible(baselines, sys, roverAzel);
                // 看不到全局参考星的基线用局部参考星
                for (int k = 0; k < baselines.size(); k++) {
                    if (baselines.get(k).isVisible(globalRefSat[sys])) {
                        baselineRefSat[k][sys] = globalRefSat[sys];
                    } else {
                        baselineRefSat[k][sys] = selectLocalRefSat(baselines.get(k), sys, roverAzel);
                    }
                }
            }
        }
    }
}
```

### 9.4 与 core 现有逻辑的对比

| | core (RtkCore.ddres) | joint (JointDdRes) |
|--|----------------------|-------------------|
| 参考星选择 | 逐系统选 Rover 端最高仰角 | 逐系统选所有基线共视卫星中最高仰角 |
| 参考星数量 | 每系统 1 个（单基线） | 每系统 1 个全局 + 退化的局部 |
| 参考星切换 | 隐式（每历元重新选，模糊度通过 slip 标记重置） | 显式（检测切换，做 ΔN 变换或重置） |
| 退化为局部 | 不适用（单基线总能看到自己的参考星） | 基线看不到全局参考星时退化为局部 |

---

## 10. 风险与缓解

| 风险 | 概率 | 影响 | 缓解 |
|------|------|------|------|
| 状态维度膨胀导致 Kalman 更新慢 | 中 | 中 | 模糊度固定后 marginalize；利用 H 稀疏性 |
| 各基线参考卫星不同导致双差不兼容 | 高 | 高 | **已设计**：全局统一参考星 + 局部回退（第 9 节） |
| 历元对齐延迟影响实时性 | 低 | 低 | 容忍 0.05s 时间差；超时退化为可用基线子集 |
| core 方法可见性修改引入回归 | 低 | 中 | 逐方法改 private→public，每改一个跑全量测试 |
| 联合 LAMBDA 维度过大 | 中 | 中 | 首期用独立固定；联合固定按基线分组降维 |
| 参考星切换导致模糊度不连续 | 中 | 中 | ΔN 变换保持连续性；退化基线独立固定避免交叉污染 |
| sharedIonoMaxDist 阈值与实际基线长度不匹配 | 中 | 中 | 按基线长度动态判断：超阈值时该基线电离层独立估计，不共享 |

---

## 11. 待办：core 方法可见性恢复后清理

> 记录日期：2026-10-26
> 状态：**阻塞中** — 等待 core 另一方处理完成

### 11.1 背景

core 中 `RtkCore` 的 `validobs`、`initx` 方法当前为 `private static`，joint 模块无法直接调用。
由于 core 另一方正在使用中，**禁止修改 core**，因此 joint 模块中临时本地重新实现了这两个方法。

### 11.2 joint 模块临时本地实现

| 方法 | 位置 | 对应 core 方法 |
|------|------|---------------|
| `JointDdRes.validobs(int iu, int ir, int f, int nf, double[] y)` | [JointDdRes.java](file:///D:/code/rtklib_java/rtklib-joint/src/main/java/org/rtklib/java/joint/JointDdRes.java) | `RtkCore.validobs` |
| `JointDdRes.initx(double[] x, double[] P, int nx, double xi, double var, int i)` | [JointDdRes.java](file:///D:/code/rtklib_java/rtklib-joint/src/main/java/org/rtklib/java/joint/JointDdRes.java) | `RtkCore.initx` |
| `JointDdRes.SQR(double x)` | [JointDdRes.java](file:///D:/code/rtklib_java/rtklib-joint/src/main/java/org/rtklib/java/joint/JointDdRes.java) | `RtkCore.SQR`（已是 public，此为冗余备份） |

### 11.3 core 恢复后的清理步骤

当 core 另一方处理完成、允许修改 core 时：

1. 将 `RtkCore.validobs` 从 `private static` 改为 `public static`
2. 将 `RtkCore.initx` 从 `private static` 改为 `public static`
3. 删除 `JointDdRes.validobs` 本地实现，替换调用为 `RtkCore.validobs`
4. 删除 `JointDdRes.initx` 本地实现，替换调用为 `RtkCore.initx`
5. 删除 `JointDdRes.SQR` 本地实现（`RtkCore.SQR` 已是 public static，直接使用）
6. 运行 `mvn compile -pl rtklib-joint -am` 验证编译通过
7. 运行全量测试确认无回归

---

## 12. 分层架构：JointRtkProcessor 与 JointProcessor

> 记录日期：2026-10-26

### 12.1 设计原则

与 core 的分层模式完全对齐：

| 层次 | core | joint | 职责 |
|------|------|-------|------|
| 外壳（数据源+解码+输出） | `RtkProcessor` | `JointRtkProcessor` | RTCM/RINEX/NTRIP 数据流、历元匹配、结果输出 |
| 内核（算法入口） | `RtkCore.rtkpos` | `JointProcessor` → `JointCore.rtkpos` | 纯算法，输入 `Obsd[]` + `Nav`，输出 `JointRtk` |

### 12.2 JointRtkProcessor 支持的数据源

| 模式 | 方法 | 说明 |
|------|------|------|
| 实时 RTCM 流 | `feedRover(byte[])` + `feedBase(id, byte[])` | 持续喂入字节流，内部 `Rtcm` 解码器自动提取 obs/eph/sta |
| 批量 RTCM 文件 | `process(roverBytes, Map<id, baseBytes>)` | 一次性处理完整 RTCM 文件 |
| 批量 RINEX 文件 | `processRinex(roverObs, Map<id, baseObs>, nav)` | RINEX obs/nav 文件路径 |
| NTRIP 流 | 外部 `NtripClient` 回调 `feedBase(id, data)` | NTRIP 挂载点作为基站数据源 |

### 12.3 典型使用场景

**1 rover + N 基站**（最核心场景）：

```java
PrcOpt opt = new PrcOpt();
JointConfig jcfg = new JointConfig();
JointRtkProcessor proc = new JointRtkProcessor(opt, jcfg, handler);

// 添加基站（1个rover对应多个基站）
proc.addBaseStation("BASE_M", new double[]{-2655164.3, 4290657.5, 3845378.2});
proc.addBaseStation("BASE_N", new double[]{-2655198.7, 4290601.3, 3845421.8});
proc.addBaseStation("BASE_Y", new double[]{-2655231.1, 4290545.1, 3845465.4});

// 实时流模式：持续喂入 RTCM 字节
proc.feedRover("NTRIP_ROVER", roverBytes);
proc.feedBase("BASE_M", "NTRIP_M", baseMBytes);
proc.feedBase("BASE_N", "NTRIP_N", baseNBytes);
proc.feedBase("BASE_Y", "NTRIP_Y", baseYBytes);

// 或批量文件模式
JointRtkResult result = proc.process(roverRtcmFile,
    Map.of("BASE_M", baseMRtcmFile, "BASE_N", baseNRtcmFile, "BASE_Y", baseYRtcmFile));

// 或 RINEX 事后模式
JointRtkResult result = proc.processRinex(
    "rover.obs",
    Map.of("BASE_M", "base_m.obs", "BASE_N", "base_n.obs", "BASE_Y", "base_y.obs"),
    "brdc.nav");
```

### 12.4 数据流

```
RTCM bytes ──→ Rtcm decoder ──→ Obsd[] + Nav + Sta
                                        │
RINEX file ──→ RinexParser ──→ Obsd[] + Nav + Sta
                                        │
                              ┌─────────┴─────────┐
                              │  历元匹配/对齐     │
                              │  (findMatching)    │
                              └─────────┬─────────┘
                                        │
                              JointCore.rtkpos()
                                        │
                              ┌─────────┴─────────┐
                              │  PosHandler 回调   │
                              │  OutputStream 输出 │
                              │  JointRtkResult    │
                              └────────────────────┘
```