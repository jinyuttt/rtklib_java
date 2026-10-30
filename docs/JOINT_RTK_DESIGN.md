# rtklib-joint 联合 RTK 解算模块设计文档

> 版本：v1.2  
> 日期：2026-10-26（2026-10-28 核实修订：2.1.1 待实施状态、Phase 0/1 工时、13.11.6 缺陷状态；13.7 改为中性路径区分表述；2026-10-29 状态对齐：新增 §0 实现状态总表，修正 §3.3 模块结构 / §4.5 配置开关 / §7 Phase 进度 / §13.8 落地状态的"已实现·未实现"标注）  
> 状态：定稿（设计与实现并存：§2.1.1 大气维度修正、§13 基站位移监测属未实施设计项）

---

## 0. 实现状态总表（2026-10-29 核实）

> 本章是**唯一的实现状态权威表**。第 2/3/4/7/13 章写的是设计意图与数学模型，
> 与本章冲突时以本章为准（各章正文已就地补注差异）。

### 0.1 已实现并实测

| 能力 | 代码落点 | 实测/依据 |
|---|---|---|
| 联合 rtkpos 主循环（SPP 初始化 → 逐基线 zdres/selsat → 历元对齐 → 迭代 Kalman 更新 → AR → holdamb） | `JointCore.rtkpos()` | — |
| 多基线状态向量：rover 位置共享 + 每基线模糊度 | `JointCore.rtkpos()`（`nx`/`baselineOffset` 计算）、`JointRtk.IB()` | 2 基线 |
| 同系统内全局统一参考星（§9） | `EpochAligner.selectGlobalRefSatBySys()` | — |
| 各基线独立 LAMBDA 固定 + FIX 后 holdamb 强约束 + 模糊度 P 压缩 | `JointCore.resolveAmbiguity()` | FIX-ALG-042，单/双基线 FIX 91.5%（236 历元，BDS 双频） |
| 模糊度浮点初值（载波-伪距组合）、模糊度过程噪声、周跳分支状态归零 | `JointCore.udstate()` / `carrierBiasInit()` | FIX-ALG-042 P2/P3 |
| 输入双模式：RTCM3 字节流（`feedRover`/`feedBase`/`process(byte[])`）与文件（`process(path)`/`processRinex()`） | `JointRtkProcessor` | 实测走 RTCM3 字节流 |
| 基站坐标外部覆盖（绕过 RTCM 1005 播发坐标） | `JointConfig.baseCoordOverride` | FIX-ALG-042 验证项 3 |
| 两层入口：`JointRtkProcessor`（外壳：解码/历元匹配/输出）→ `JointProcessor`（内核直调）→ `JointCore.rtkpos()` | 两类均调 `JointCore.rtkpos()` | — |

### 0.2 未实现（含"配置项存在但无代码消费"）

| 项 | 现状 | 影响 |
|---|---|---|
| 大气参数维度修正（§2.1.1） | 仍是"每基线一份大气"（`II()/IT()` 的 `baseline` 在最外层） | 大气层面等效退回 SBS，多耗 k 倍冗余度 |
| `JointConfig.sharedIono` / `sharedTrop` / `sharedIonoMaxDist` | **声明后无任何读取**；`JointRtk.toState()` 每次新建 `RtkConfig`，joint 配置不进入 core | 改开关无行为变化 |
| `JointConfig.independentAmbFix` / `jointAmbFix` | 无读取；实际固定为"各基线独立 LAMBDA"，联合固定（§2.7 后续增强）未做 | — |
| `JointConfig.blockDiagonalR` / `modelCrossCorrelation` | 无读取；R 实际按基线分块构造 | 跨基线相关性未建模 |
| `JointConfig.jointCycleSlip` | 无读取；joint **未调用** `RtkCore.detslp*`，`ssat.slip` 从未被写入 | 周跳不会被检出/重置，长时段可靠性存疑 |
| `JointConfig.useNetworkAdjustment` | 无读取 | — |
| 电离层/对流层过程噪声与初值（`prn[1]`/`prn[2]`、`initx`） | 未接线 | EST 大气状态为无效维度（P 恒 0，不吸收误差） |
| `BaseStabilityMonitor`（§13 三道闸） | **仅设计**，模块内无此类 | 基站位移不可检测 |
| k>2 基线 / GLONASS、Galileo / 三频 | 代码结构通用，**但实测仅覆盖 BDS 双频 2 基线** | 未验证 |

### 0.3 测试现状

| 项 | 现状 |
|---|---|
| 测试类 | `JointRtkRealDataTest`（4 个用例） |
| 数据来源 | 本地设备存储 RTCM3（`joint-test-data.properties` 不入库，仅 template 入库） |
| 断言强度 | 仅 `totalEpochs > 0`；FIX 率、精度、与 SBS 对照均为**打印**而非断言 |
| 覆盖缺口 | 主用例 `maxBaseline=1`（只喂基站 A，标题"2基站"名不符实）；双基线仅在单小时用例（固定第 8 小时）中运行；无参考坐标→无 ENU RMS |

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
| 协方差 | 对角块为 core 输出原始协方差（Sol.qr），默认块对角（基线独立） | 自然建模跨基线相关性 |
| 精度天花板 | 结果空间加权，事后时段平均可优于单基线 | FLOAT 阶段显著提升；FIX 阶段 ≈ 单基线 |
| 实时性 | ❌ 事后（时段批处理） | ✅ 逐历元实时 |
| 计算量 | 低 | 中（状态维度更大） |

**定位定论**：joint 与 `rtklib-core` 是**同一层**的两个 RTK 引擎（MBS / SBS），二者互斥可替换；`rtklib-adjust` 是建在 core 之上的后处理网平差层，与 joint 没有接口关系。joint 之所以不能接 adjust，是因为它在观测层联合后不再输出"多条独立基线解"，而那正是 adjust 的观测量。

---

## 2. 数学模型

### 2.1 状态向量

> **实现状态（2026-10-28 核实）**：当前代码仍是下述「每基线一份大气」结构（`JointCore.java:146-171` 的 `nx` / `baselineOffset` 计算、`JointRtk.java:79-94` 的 `II()` / `IT()`）。本节描述的是**现状**，正确结构见 2.1.1，修正**尚未落地**。

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

#### 2.1.1 修订说明：大气参数的维度划分（待实施）

上式中电离层/对流层被放在**基线维度**内，即 k 条基线各持一份大气参数。这在多基线联合场景下属于**维度划分错误**：

- 大气参数在物理上属于「接收机 / 卫星 / 空间场」，只有**模糊度**属于「基线」
- Rover 只有一个，天顶湿延迟也只有一个；拆成 k 份独立状态后，各基线会收敛到不同的大气实现，共享 rover 坐标的约束反而自相矛盾
- 后果：大气层面事实上退回 SBS，同时额外消耗 k 倍冗余度

代码证据（`JointRtk.java:81-94`），`baseline` 位于索引最外层：

```java
II(baseline, sat) = np + baseline * MAXSAT * niPerSat + (sat-1) * niPerSat
IT(baseline, r)   = np + ni + baseline * (2*ntPerR) + r * ntPerR
```

**修正后的分组**：

```
x = [ pos ] + [ rover 大气 · 全局共享 ] + [ base 大气 · 每站一份 ] + Σ_baseline ( amb_b )
```

**配置语义对照（修正后与 RTKLIB 对齐）**：

| 开关 | RTKLIB 单基线语义 | 联合模式（修正后） | 参数数（k 基线 / ns 星） |
|---|---|---|---|
| `ionoopt=OFF` | 不估 | 不估 | 0 |
| `ionoopt=BRDC` | 模型计算 | 模型计算，不估 | 0 |
| `ionoopt=IFLC` | 无电离层组合 | 无电离层组合 | 0 |
| `ionoopt=EST` | 每星 1 个 | **共享**每星 1 个（rover 视角） | ns |
| `ionoopt=GRADIENT`（新增） | — | 平面梯度 `I₀+a·x+b·y` | 3 |
| `tropopt=OFF` | 不估 | 不估 | 0 |
| `tropopt=SAAS` | 模型计算 | 模型计算 | 0 |
| `tropopt=EST` | rover + base 各 1 | **rover 共享 1** + 每站 1 | 1 + k |
| `tropopt=ESTG` | rover + base 各 3 | **rover 共享 3** + 每站 3 | 3 + 3k |

**约束**：rover 侧大气参数**不允许**按基线配置（物理上不存在多个 rover），必须全局统一；base 侧可按站覆盖（默认沿用 `SAAS`）。

**参数规模收益**（2 基线 / 13 星 / `tropopt=EST`, `ionoopt=EST`）：

| 项 | 当前 | 修正后 |
|---|---|---|
| 电离层 | 26 | 13 |
| 对流层 | 4 | 3（rover 1 + base 2） |
| 合计 | 30 | 16 |
| nx（含 pos 3 + 模糊度 48） | 81 | 67 |
| 多余观测 df（nv=96） | 15 | 29 |

**落地方式**：只改索引函数（`NI()`, `NT()`, `II()`, `IT()`, `nx` 计算，`baselineOffset[]` 起点），`ddres` / `udpos` / `udtrop` / `udiono` 的算法逻辑不变。

**补充核实（2026-10-28）**：`JointCore.udstate()` 目前只给模糊度加过程噪声（`prn[0]`），未实现电离层/对流层的过程噪声与初值（无 `prn[1]`/`prn[2]`、无 `initx`）。因此当前代码里 EST 大气状态是**无效维度**（P 恒为 0、不吸收任何误差）。修正维度时需同时把 `prn[1]`/`prn[2]` 与初值接线，否则收益仍为零。

### 2.2 状态索引函数

> **实现状态（2026-10-28 核实）**：当前代码即此「每基线独立偏移」结构，`II()/IT()` 的 `baseline` 位于索引最外层；修正后的分组方式见 2.1.1。

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
| validobs() | private static | public static | 无（2026-10-28 仍未改，joint 用本地副本） |
| initx() | private static | public static | 无（2026-10-28 仍未改，joint 用本地副本） |
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

> 2026-10-29 按实际代码树修订。原设计的 `JointRelPos/JointUdState/JointUdBias/JointAmbFix`
> 与 `config/`、`model/` 子包**未拆出**：这些职责目前内联在 `JointCore`（`udstate`/`resolveAmbiguity`）
> 与扁平包 `org.rtklib.java.joint` 中，属后续重构项，不影响功能。

```
rtklib-joint/
  ├── pom.xml
  └── src/main/java/org/rtklib/java/joint/
      ├── JointRtkProcessor.java        ← 外壳：RTCM/RINEX 数据流、历元匹配、结果输出
      ├── JointProcessor.java           ← 内核入口，多基线数据流管理
      ├── JointCore.java                ← 联合 rtkpos 主循环 + udstate + 模糊度固定
      ├── JointDdRes.java               ← 联合双差观测方程（含 initx）
      ├── EpochAligner.java             ← 多基站历元对齐 + 全局参考星选择
      ├── JointConfig.java              ← 联合解算配置（见 §4.5 存根说明）
      ├── JointRtk.java                 ← 联合滤波器状态（含 toState() 与 II/IT/IB 索引）
      ├── BaseStation.java             ← 基站标识 + 坐标 + 观测缓冲
      ├── BaselineGroup.java            ← 同一历元的多基线观测集合
      ├── BaselineObs.java              ← 单基线观测/残差容器
      └── BaselineSolution.java         ← 单基线解（中间量）
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

> **实现状态（2026-10-29 核实）**：上表除 `maxBaseline`、`maxTimeDiff`、`baseCoordOverride`
> 外，**全部为存根字段**——`JointCore`/`JointProcessor` 未读取任何一个，且 `JointRtk.toState()`
> 每次新建 `RtkConfig`，joint 配置不会流入 core。因此"共享电离层/共享对流层/联合固定/
> 跨基线 R 建模/联合周跳"这些开关**改动后无任何行为变化**。详见 §0.2。

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

两者**不在同一条链路上**，也不是可以互相替换的同层组件：

```
route A（后处理，本项目主线）
  rtklib-core（SBS，每基站一条独立 Kalman）
        ↓ SolData（多条独立 FIX 基线）
  rtklib-adjust（网平差，结果空间）
        ↓ 平差坐标 / 精度评定 / 基站位移

route B（实时，独立引擎）
  rtklib-joint（MBS，一个 Kalman，DD 方程全进）
        ↓ 联合解坐标
  （直接输出，不经过 adjust）
```

| | rtklib-adjust | rtklib-joint |
|---|---|---|
| 层级 | core 之上的后处理层 | 与 core 同层的 RTK 引擎 |
| 输入 | SolData（已解算的独立基线） | Obsd（原始观测） |
| 时机 | 事后（时段批处理） | 实时（逐历元） |
| 输出 | 平差坐标 + 可靠性评定 + 基站位移 | 联合解坐标 |
| 与另一方的关系 | 无接口 | 无接口 |

**joint 不能接 adjust**：它在观测层把 k 条基线并进同一个滤波器、一次解算，输出时"多条独立基线解"这个中间产物已不存在，而 adjust 的观测量正是那些独立基线解。

**选型原则**：

- 实时、追求固定率、rover 在网内、基站坐标可信 → joint（MBS）
- 事后、要出成果、要判基站位移、要故障隔离 → core + adjust（SBS + 网平差）
- 两者可**并行**运行（实时告警 + 事后成果交叉验证），但不是流水线串联

### 6.3 与 rtklib-product

joint 模块的 `JointSol` 可被 product 模块消费，格式化输出与现有 `Sol` 兼容。

---

## 7. 实现路线

### 执行顺序（调整后）

核心原则：**先验证联合 RTK 有效性，再重构配置**。RtkConfig 组合模式重构不是 joint 的硬前置——当前 RtkState 用扁平 RtkConfig 完全可行，重构后精简 record 是优化而非前提。

```
Step 0: RtkState record（用当前扁平 RtkConfig）+ core 可见性修改     1d
Step 1: joint Phase 1（2 基线 + 双频 GPS）                         13d
Step 2: 真实数据验证——固定率是否提升                                 —
Step 3: 若验证通过 → RtkConfig 重构 → RtkState 精简               5d
Step 4: joint Phase 2 + 3                                        13d
```

先做 joint 的理由：
- 核心假设优先验证（14d vs 17.5d，早 3.5d 知道联合 RTK 是否有效）
- 新增模块零回归风险（不改 core 任何一行，不影响其他并行开发）
- RtkConfig 重构后置更稳（joint 跑通后可实测对比，而非假设收益）

### Phase 0：core 改动（前置）

| 任务 | 估计 | 说明 |
|------|------|------|
| RtkState record | 0.5d | Java 17 record，用当前扁平 RtkConfig |
| core 方法可见性修改 | 0.5d | 18 个 private→public static，8 个方法签名 Rtk→RtkState。截至 2026-10-28 已完成 16/18 并提交；剩余 `validobs`、`initx` 属清理债，joint 用等价本地副本替代，不阻塞 Phase 1（见第 11 节） |
| **合计** | **1d** | |

### Phase 1：最小可用版本（2 基线 + 双频 GPS）

| 任务 | 估计 | 说明 |
|------|------|------|
| 模块骨架 + pom.xml | 0.5d | Maven 模块、依赖 core |
| JointRtk + BaseStation | 1d | 状态向量、索引函数 |
| 大气参数维度修正（2.1.1 节） | 1~2d | 改 `NI()/NT()/II()/IT()`、`nx` 计算、`baselineOffset[]` 起点；`ddres`/`udpos`/`udtrop`/`udiono` 算法逻辑不变。若同时补齐 `udstate` 的 `prn[1]`/`prn[2]` 过程噪声与初值，另加 ~1d |
| JointObsModel | 0.5d | 调用 core 改可见性后的方法（RtkState 传参），无需复制 |
| JointDdRes | 2d | 联合双差观测方程（核心） |
| JointUdState + JointUdBias | 1.5d | 状态预测、模糊度更新 |
| JointRelPos + JointCore | 2d | 主循环 |
| JointAmbFix（独立固定） | 1d | 各基线独立 LAMBDA |
| EpochAligner | 0.5d | 2 基线历元对齐 |
| JointProcessor | 1d | 入口数据流 |
| 测试 + 验证 | 2d | 与单基线对比、固定率验证 |
| **合计** | **~13d** | |

验证标准：
- 2 基线联合解的 FLOAT 解精度优于单基线
- 2 基线联合解的固定率 ≥ 单基线
- 固定解精度 ≈ 单基线（天花板验证）

> **执行状态（2026-10-29）**：**基本完成，唯一未落地项是"大气参数维度修正（2.1.1）"**。
> 实际实现为"每基线一份大气"，其余任务（骨架、JointRtk/BaseStation、JointDdRes、
> udstate、JointCore 主循环、独立 LAMBDA、EpochAligner、JointProcessor、测试）均已落地。
> 验证结果：双基站双基线 FIX 91.5% vs 单基线 91.2%（BDS 双频，236 历元），
> 三条验证标准均满足。FIX-ALG-042 的 7 处修复详见 `ALGORITHM_FIX_LOG.md`。

### Phase 2：通用化（k 基线 + 多系统）

> **执行状态（2026-10-29）**：**部分完成**。k 基线通用化与事后 RINEX 流程（`processRinex()`）
> 代码已具备，但**实测只覆盖 BDS 双频 2 基线**；GLO/GAL 与三频未验证。

| 任务 | 估计 |
|------|------|
| k 基线通用化 | 2d |
| BDS + GAL 支持 | 2d |
| 三频支持 | 1d |
| 事后 RINEX 流程 | 2d |
| **合计** | **~7d** |

### Phase 3：增强

> **执行状态（2026-10-29）**：**未开始**（四项全部未实现，见 §0.2）。

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
> 状态：**清理债，不阻塞 Phase 1**
> 核实（2026-10-28）：3.2 节所列 18 个方法中 16 个已为 `public static`（HEAD 已提交，`zdres`/`varerr`/`prectrop`/`selsat`/`antmodel` 等均含 `RtkState` 签名改造）。仅 `validobs`、`initx` 仍为 `private static`，joint 侧保留本地副本；两者与 core 逐字符相同、为纯函数，功能等价，**Phase 1 可正常推进**。
> 处置（2026-10-28）：`JointDdRes.SQR` 本地副本已删除（所有调用点早已使用 `RtkCore.SQR`）。`validobs`/`initx` 暂不切换——`RtkCore.java` 工作区存在 config 结构重构未提交（`cfg.pppRtk.enable` 等 23 增 23 删），此时插入改动会制造冲突与 blame 污染，待该重构提交、工作区干净后再切。

### 11.1 背景

core 中 `RtkCore` 的 `validobs`、`initx` 方法当前为 `private static`，joint 模块无法直接调用。
由于 core 另一方正在使用中，**禁止修改 core**，因此 joint 模块中临时本地重新实现了这两个方法。

### 11.2 joint 模块临时本地实现

| 方法 | 位置 | 对应 core 方法 | 状态 |
|------|------|---------------|------|
| `JointDdRes.validobs(int iu, int ir, int f, int nf, double[] y)` | [JointDdRes.java](file:///D:/code/rtklib_java/rtklib-joint/src/main/java/org/rtklib/java/joint/JointDdRes.java) | `RtkCore.validobs` | 保留，待切换 |
| `JointDdRes.initx(double[] x, double[] P, int nx, double xi, double var, int i)` | [JointDdRes.java](file:///D:/code/rtklib_java/rtklib-joint/src/main/java/org/rtklib/java/joint/JointDdRes.java) | `RtkCore.initx` | 保留，待切换 |
| ~~`JointDdRes.SQR(double x)`~~ | ~~[JointDdRes.java](file:///D:/code/rtklib_java/rtklib-joint/src/main/java/org/rtklib/java/joint/JointDdRes.java)~~ | `RtkCore.SQR`（已是 public） | **已删除（2026-10-28）** |

### 11.3 清理步骤

**已完成**：

1. ~~删除 `JointDdRes.SQR` 本地实现~~ — 2026-10-28 完成，全部调用点使用 `RtkCore.SQR`

**待 core 重构提交后执行**（不阻塞 Phase 1）：

2. 将 `RtkCore.validobs` 从 `private static` 改为 `public static`
3. 将 `RtkCore.initx` 从 `private static` 改为 `public static`
4. 删除 `JointDdRes.validobs` 本地实现，替换调用为 `RtkCore.validobs`
5. 删除 `JointDdRes.initx` 本地实现，替换调用为 `RtkCore.initx`
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

---

## 13. 基站位移监测与可靠性控制

### 13.1 问题：联合解算会"摊平"基站误差

联合滤波把 rover 坐标设为全局共享状态（见 §2.1）。这一设计的副作用是：

- 任一基站 b 发生位移 δb_b 时，rover 坐标**无法同时满足所有基线**
- 滤波把矛盾**平摊**到所有基线，结果是 rover 解被拉偏，而**没有任何单基线明显报错**
- 对比独立基线模式：位移基站的解偏离、其他基线仍正确 → 通过"多解一致性"即可识别

| 模式 | 基站位移的表现 | 是否可见 |
|------|---------------|---------|
| 独立基线 | 该基线 rover 解系统性偏离，其他基线正常 | ✅ 天然可见 |
| 联合解算 | rover 共享状态被拉偏，全部基线残差同时轻微抬高 | ❌ 被摊平 |

**结论**：联合模式比独立模式**更需要**逐基线的残差级质量控制。这不是可选增强，而是联合方案的必要安全网。

### 13.2 模块内已有的可观测量

反演基站位移所需的输入**在当前代码中已全部存在**，无需新增观测数据：

| 变量 | 出处 | 用途 |
|------|------|------|
| `v[]` 按基线分块 | `JointDdRes.ddres()` | 残差主体 |
| `nb[]` 基线观测块大小 | `JointDdRes.ddres()` | 分块依据 |
| `bo.e[3*MAXOBS]` 视线向量 | `RtkCore.zdres()` | 反演位移方向 |
| `bo.vare[]` 观测方差 | `zdres` → `varerr()` | 最小二乘定权 |
| `sol.ratio`（每基线） | `resolveAmbiguity()` | 粗检告警 |
| `ssat[].lock` | 模糊度管理 | 间接指标 |
| `bs.rb` | `BaseStation` | 位移基准 |

### 13.3 候选方法对比

| 编号 | 方法 | 数据来源 | 灵敏度 | 实时性 | 定位到基站 | 定方向 | 实现成本 |
|------|------|---------|--------|--------|-----------|--------|---------|
| A | 载波残差反演 δb | 已有 | cm 级/历元 | ✅ | ✅ | ✅ | 低 |
| B | 每基线 χ² + 残差偏置检验 | 已有 | dm 级/历元 | ✅ | ✅ | ❌ | 极低 |
| C | 独立单基线解交叉比对 | 需再跑 N 次单基线 | cm 级 | ✅ | ✅ | ✅ | 中 |
| D | 基站间基线骨架网 | 需 base-base 单基线解 | mm 级（长时段） | 准实时 | ✅ | ✅ | 中 |
| E | ratio / FIX 率骤降 | 已有（`sol.ratio`） | m 级 | ✅ | ✅ | ❌ | 极低 |
| F | 基站静态 PPP 自检 | 需 PPP 长时段 | mm 级 | 小时级 | ✅ | ✅ | 高 |

**说明**：

- **A** 是唯一能在单历元同时给出"哪台基站"和"往哪动"的方法，且不依赖模糊度固定。
- **B** 是 A 的前置粗筛，成本几乎为零，适合做第一道闸。
- **C** 本质是"用独立解给联合解做旁证"。本项目已验证独立基线 FIX 率 91%，能力现成，但实时跑 N 份解算代价高，建议作离线验证工具。
- **D** 完全绕开 rover，是物理上最干净的位移判据，但需要基站间基线解算能力（`RtkCore` 单基线可直接复用）。
- **E** 只能告警不能定位，作为兜底。
- **F** 周期体检（如每日夜间），不做实时。

### 13.4 推荐方案：三道闸

```
                 ┌────────────────────────────────┐
  每历元 ────────→│ 闸1  每基线 χ² + 偏置检验       │ 快、粗，定位到基线
                 └───────────┬────────────────────┘
                             ↓
                 ┌────────────────────────────────┐
  每历元 ────────→│ 闸2  载波残差反演 Δδb           │ 定位到基站 + 方向
                 └───────────┬────────────────────┘
                             ↓
                 ┌────────────────────────────────┐
  每 N 分钟 ─────→│ 闸3  基站间基线骨架网           │ 不依赖 rover，最干净
                 └────────────────────────────────┘
```

### 13.5 数学推导：载波残差反演基站位移

设基站 b 发生位移 δb（ECEF，米）。非差几何距离的一阶变化为：

```
ρ'_B,j = |rs_j - rb - δb| ≈ ρ_B,j - e_jᵀ·δb
```

由于零差观测值 `y = cp - ρ`，基站端 `y` 变化 `+e_jᵀ·δb`。带入双差（rover 端不变）：

```
v_j  +=  (e_j - e_ref)ᵀ · δb
```

对基线 b 的全部非参考星 j 与全部频点 f 建立观测方程：

```
v_{j,f} = (e_j - e_ref)ᵀ · δb + ε,     ε ~ N(0, σ²_{j,f})
```

权取 `w = 1/vare`，最小二乘解：

```
A_j   = e_j - e_ref                       (3×1)
N     = Σ_j Σ_f  w_{j,f} · A_j A_jᵀ       (3×3)
δb    = N⁻¹ · Σ_j Σ_f w_{j,f} · A_j · v_{j,f}
Q_δb  = N⁻¹,     σ_δb = sqrt(diag(Q_δb))
```

**典型精度估计**（13 颗共视、2 频、载波 σ≈5mm、|e_j − e_ref|≈0.5）：

```
σ_δb ≈ 5mm / sqrt(24) / 0.5  ≈  7 mm
```

即单历元即可达到**厘米级**位移检测能力。

#### 关键性质：对模糊度误差免疫

载波残差中的模糊度误差 `λ·δN` 是**逐历元常量**（未发生周跳时）。因此：

- 单历元 `δb` 的**绝对值**会被模糊度偏置污染，不能直接采信
- 历元差分 `Δδb = δb(t₂) - δb(t₁)` 中，模糊度项**完全抵消**

```
Δδb = (e_j - e_ref)ᵀ · [δb(t₂) - δb(t₁)]  + (模糊度项相减抵消)  + Δε
```

**结论**：判据使用 `Δδb` 而非 `δb` 绝对值。由此，**即使当前 AR 完全不能固定（FIX=0%），闸2 依然有效**——因为它检测的是"基站有没有动"，与模糊度是否固定解耦。

这个性质对当前调试阶段尤其重要：可以先落地基站监测，不必等 AR 问题解决。

### 13.6 判据与状态机

每个基站维护一个独立状态：

```
UNKNOWN ──→ STABLE ──→ DRIFTING ──→ DISPLACED ──→ REJECTED
              ↑          │            │
              └──────────┘(恢复)       └─(人工确认后可回 STABLE)
```

**判据**：

| 闸 | 检验量 | 判定 |
|----|--------|------|
| 闸1 | `χ² = Σ (v² / vare)` vs `χ²_{1-α}(nv)` | 超限 → 该基线异常计数 +1 |
| 闸2 | `\|Δδb\|` vs `k·σ_Δδb`，k=3 | 超限且持续 M 历元 → DRIFTING |
| 闸2 | `\|Δδb\|` vs 绝对阈值（建议 5cm） | 超限 → DISPLACED |
| 闸3 | 基站间基线长度变化 vs 阈值（建议 1cm） | 超限 → DISPLACED |

**处置**：

| 状态 | 处置 |
|------|------|
| STABLE | 全权参与联合滤波 |
| DRIFTING | 降权，`R_b *= k²`（k 由 `|Δδb|/σ` 决定） |
| DISPLACED | 从 `bases[]` 剔除，nx 变化需重构 `P` 相应块 |
| REJECTED | 永久排除并告警，需人工介入 |

### 13.7 与既有工程监测方法的路径区分

本节方案面向**观测层、历元级**的实时质量控制（双差残差反演 + χ² 统计检验），与工程监测领域常见的**坐标层、期级**做法（先解基线再网平差，三维七参数 Helmert 归算 + 递推加权，依赖首期基准成果）在数据前提、时间尺度、数学路径上均不同：

| 维度 | 坐标层 / 期级方法 | 本节方案 |
|---|---|---|
| 解算层级 | 坐标层（先算基线，再网平差） | **观测层**（双差残差直接参与） |
| 时间尺度 | 期级（年 / 半年） | 历元级（秒级） |
| 核心数学 | Helmert 归算 + 递推加权 | 残差反演 + χ² 统计检验 |
| 数据前提 | 需首期基准成果 | 无需历史，自适应 |
| 位移判定 | 归算后较差 > 阈值 | `\|Δδb\|` 统计检验 + 持续历元数 |
| 适用场景 | 长期形变监测（mm 级） | 实时 RTK 质量控制（cm 级） |

两者互补而非替代：

- 坐标层 / 期级方法适合**成果层**的长期基准维护（跨期系统误差消除、沉降量计算）
- 本节方案适合**解算层**的实时质量控制（单历元异常基站识别）
- 若未来做"实时解算 + 长期形变"一体化，两层都应保留

**实现说明**：本节只保留"多站互检、基准可靠性优先"这一通用工程思想，代码为独立实现；对外部资料的任何引用与比对均不纳入本仓库。

### 13.8 实现落点

> **实现状态（2026-10-29）**：**未实现**。以下为设计稿，`rtklib-joint` 中无 `BaseStabilityMonitor`
> 类，`JointCore.rtkpos()` 中也无对应调用点。本章属后续迭代（Phase 3 之后）。

新增类 `org.rtklib.java.joint.BaseStabilityMonitor`：

```java
public class BaseStabilityMonitor {
    /** 每个历元 Kalman 更新后调用 */
    void update(int epoch, JointRtk jrtk, BaselineObs[] blObs, int[] nb,
                double[] v, int nv, int[] vflg);

    /** 基站稳定状态：UNKNOWN/STABLE/DRIFTING/DISPLACED/REJECTED */
    int getStatus(int baseline);

    /** 降权系数，1.0 表示全权 */
    double getWeight(int baseline);

    /** 最近一次反演的位移量（ECEF，米） */
    double[] getDisplacement(int baseline);

    /** 位移量标准差 */
    double getDisplacementSigma(int baseline);
}
```

**调用点**：`JointCore.rtkpos()` 中 `KalmanFilter.update()` 之后、`resolveAmbiguity()` 之前。

**数据传递**：`ddres()` 已按基线顺序填充 `v[]`，配合 `nb[]` 即可切出每基线残差块，无需修改 `JointDdRes` 接口。视线向量从 `blObs[b].e[]` 取，与 `bo.sat[]` / `bo.iu[]` 对齐。

### 13.9 分期计划

| 阶段 | 内容 | 是否影响解算 |
|------|------|------------|
| Phase A | 闸1 + 闸2 落地，**只读**，输出日志与告警 | 否 |
| Phase B | 闸2 结果联动降权（`R_b` 放大） | 是 |
| Phase C | 闸3 基站间基线骨架网（复用 `RtkCore` 单基线） | 是 |
| Phase D | DISPLACED 剔除 + 滤波状态重构（nx 变化处理） | 是 |

**建议先做 Phase A**：零风险，且能立刻回答当前调试中的关键问题——"FIX=0% 到底是 rover 侧问题，还是某台基站的数据问题"。A 阶段日志可以直接给出每台基站的残差健康度。

### 13.10 待定问题

1. 闸2 的绝对阈值需要按基线长度标定。短基线（<10km）对流层/电离层几乎抵消，5cm 合理；长基线需放宽。
2. `Δδb` 的时间基线选择：相邻历元（1s）噪声大但响应快；跨 M 历元（如 60s）更稳但滞后。需实测确定。
3. 基站数 N 增大时，闸2 独立反演 N 次的开销。nx=100 规模下 3×3 求逆可忽略，但若 N>8 建议合并成一次联合反演。
4. 基站位移与"基站天线被换/被重新对中"的区分：后者是阶跃且不可恢复，应直接 REJECTED 而非 DRIFTING。

### 13.11 现有 rtklib-adjust 诊断能力盘点

在新增 `BaseStabilityMonitor` 之前，先盘点 `rtklib-adjust` 模块**已经实现**的基站可靠性判断能力，避免重复造轮子。

#### 13.11.1 功能定位

`rtklib-adjust` 在**多基线网平差**链路中，利用"同一 rover 被多条基线独立解算"的冗余，判断**哪台基站的坐标不可信**，并在必要时用静态解算给出替代坐标建议。全程只给建议，不自动修改。

注意：该能力建立在"多解冗余"之上（每条基线独立输出 rover 坐标）。**联合 RTK 只有一个共享 rover 坐标，因此这套判据不能直接移植**——这是 §13 提出独立的观测层方案的根本原因。

#### 13.11.2 类与职责

| 类 | 职责 |
|---|---|
| `MultiBaselineAdjust` | 调度层：收基线 → 组历元 → 调平差 → 分发事件 → 窗口融合 |
| `GnssBaselineAdjust` | 平差引擎：最小二乘 + 交叉验证 + Baarda 粗差探测 |
| `BaseStationDiagnoser` | 诊断器：σ0 滑窗触发 + 深度诊断 + 缓存 |
| `BaseStationDiagnosis` | 诊断结果：偏差（NEU/3D）、异常等级、建议坐标 |
| `AdjustDiagnosisConfig` | 诊断配置（Builder） |
| `ReliabilityGrade` | 5 级可靠性：VERIFIED / CROSS_CHECKED / SINGLE_VERIFIED / UNVERIFIED / SUSPECT |
| `BaseDiagnosisHandler` | 诊断结果回调 |
| `BatchAccumulator` / `BatchCoordinate` | 窗口批量融合 |

#### 13.11.3 数据流

```
feedBaseline(baseId, SolData, epochTag)
        │  按 epochTag 分组，只收 FIX 解
        ↓
   processEpoch()
        │
        ├─→ GnssBaselineAdjust.adjust()
        │       ├─ Layer 1 交叉验证  → ReliabilityGrade + suspectBaseId
        │       └─ Layer 2 Baarda   → σ0, Qv, baardaT[]
        │
        ├─→ SUSPECT? → suspectListeners.onSuspect()
        ├─→ diagnoser.feedAdjustResult(result)
        │       └─ Layer 3 σ0 滑窗 → 触发 → Layer 4 深度诊断
        └─→ windowCoords 累积 → computeBatch() → BatchCoordinate
```

#### 13.11.4 四层判据

**Layer 1 交叉验证（每历元）**

同历元 k 条基线的 rover ECEF 坐标两两求距，取最大距离 `maxDist`：

| maxDist | 等级 | 处置 |
|---|---|---|
| < 2 cm | VERIFIED | 正常融合 |
| < 5 cm | CROSS_CHECKED | 协方差膨胀 4× |
| ≥ 5 cm | SUSPECT | 剔除可疑基线重算 + 触发告警 |

定位可疑基站用 `score = ratio × numSat`，**分低者判为坏基站**（假设假固定 → ratio 低、卫星少）。

**Layer 2 Baarda 粗差探测（每次平差）**

```
Qv      = R − H·N⁻¹·Hᵀ
σ0      = sqrt(vᵀRv / df)
baardaT = |v_i| / (σ0 · sqrt(qv_ii))
```

逐观测的 w-test，能定位到具体基线分量，比整体 χ² 精细。

**Layer 3 σ0 滑窗触发**

窗口 100 历元，统计 `σ0 > 3.0` 的占比；占比 ≥ 80% 触发深度诊断。单历元偶发超标不报，连续超标才报——全局 χ² 检验的连续化版本。

**Layer 4 深度诊断（一次性 + 缓存）**

```
① 定位异常基站
   k ≥ 3 → identifyByResidual()        ← Baarda 残差判据（2026-10-28 已实现，仅定位最大偏差单站）
   k = 2 → identifyBySpp()             ← SPP 均值 vs RTCM 1005，>1m 判异常

② 定量诊断
   以另一台好基站为参考跑静态 RTK（PMODE_STATIC + FIXHOLD）
   所有 FIX 解的 ECEF 均值 = 建议坐标
   与 RTCM 1005 比对 → dN / dE / dU / 3D 偏差 / fixRate / σ

③ 分级（只建议，不自动改）
   <0.5m NORMAL / 0.5~2m WARNING / 2~5m ERROR / >5m CRITICAL
```

#### 13.11.5 配置项

| 参数 | 默认 | 说明 |
|---|---|---|
| `enable` | false | 总开关 |
| `sigma0Threshold` | 3.0 | σ0 超标判定线 |
| `diagnoseWindow` | 100 | 滑窗历元数 |
| `diagnoseRatio` | 0.8 | 触发深度诊断的超标占比 |
| `useSppForDirection` | true | 用 SPP 定位异常基站 |
| `useStaticForCorrection` | true | 用静态解给建议坐标 |
| `minStaticDataHours` | 2.0 | 深度诊断最少数据时长 |
| `cacheDiagnosis` | true | 诊断结果缓存，避免重复计算 |

#### 13.11.6 已知缺陷

| # | 问题 | 影响 |
|---|---|---|
| 1 | ~~`identifyByResidual()` 返回空列表~~ **已修复** | 2026-10-28 提交 a5e1a21 改为 Baarda 残差判据（`BaseStationDiagnoser.java:235`），k ≥ 3 场景已有 `BaseStationDiagnoserResidualTest` 覆盖 |
| 2 | `crossCheckedThreshold == suspectThreshold == 0.05`（`MultiBaselineConfig.java:53-54`） | 第三个分支是死代码，实际只有 2cm / 5cm 两档 |
| 3 | Layer 4 依赖 RTCM 1005 播发坐标 | 基站换天线但 1005 未更新 → 把正常基站判成异常；且只挑最大偏差一个，多站同时位移会漏检 |

> **归属**：上表剩余两项（#2 阈值死分支、#3 Layer 4 的 1005 依赖与单站局限）属于 **rtklib-adjust** 的待办，不属 joint。joint 的 `BaseStabilityMonitor` 只需在设计中规避同样问题（阈值档位必须严格递增；坐标自检不依赖播发坐标；支持多站同时位移）。

#### 13.11.7 概念混淆：假固定 ≠ 基站位移

代码把两类性质完全不同的问题合并进同一个 `suspectBaseId`：

| 问题 | 本质 | 应由哪层抓 |
|---|---|---|
| **假固定** | 基站坐标正确，AR 解错 | Layer 1 / 2 |
| **基站位移** | 基站坐标本身错了 | Layer 4 |

`identifySuspect` 用"ratio 低 = 坏基站"的假设，只在**假固定**场景成立。若真发生基站位移，该基线的 ratio 可能依然很高（长基线照样能固定，只是坐标错了），此时这条判据会指向**错误的基站**。新增 `BaseStabilityMonitor` 时应明确区分这两类事件，不要复用同一套状态机。

#### 13.11.8 能力边界

| 能 | 不能 |
|---|---|
| 判"这批解里有基站不可信" | 判位移**方向与量值**（只有标量距离） |
| k=2 时定位到具体基站 | k≥3 时仅能定位最大偏差单站（多站同时位移会漏检） |
| 给替代坐标建议 | 自动剔除/降权（只回调） |
| 依赖多解冗余做交叉检验 | 无冗余时（单基站）完全失效 |

#### 13.11.9 与本章方案的分工

```
adjust：有"多解离散"（独立基线各自出解）→ 但只知道差了几厘米，不知道往哪动
joint ：只有一个共享 rover 坐标 → 看不到离散，但握有完整 v/e/Qv → 能反演位移方向
```

| 能力 | adjust 现状 | joint 应做 |
|---|---|---|
| Baarda w-test | ✅ 已实现（`GnssBaselineAdjust`） | **照搬**，joint 有 `v/H/R`，Qv 可算 |
| σ0 滑窗触发 | ✅ 已实现（`BaseStationDiagnoser`） | **照搬**，替换 §13 闸1 的整体 χ² |
| 多解交叉验证 | ✅ 已实现（每历元实时） | ❌ 联合模式无多解，不可移植；可作离线旁证 |
| 残差反演 δb | ❌ 无 | ✅ joint 独有，§13.5 |
| 基站间骨架网 | ❌ 无 | ⚠️ 两边都缺，Phase C |

**结论**：`BaseStabilityMonitor` 的闸1 直接用 Baarda w-test + σ0 滑窗（现成算法），闸2 用 §13.5 的残差反演，闸3 另行建设。`identifyByResidual()` 的空实现已于 2026-10-28 补齐（见 13.11.6），§13.11.6 剩余两项记入 **rtklib-adjust** 待办，joint 侧无需动作。

---

## 14. 与传统静态解算流程的对标

本章把本模块与业界通用的**工程静态解算流程**（外业采集 + 内业后处理）做覆盖度对标，明确"我们做到了哪一段、缺哪一段"。

### 14.1 参照流程

业界标准流程分两个阶段、六个步骤：

**外业数据采集**

| # | 步骤 | 要点 |
|---|------|------|
| 1 | 前期准备 | 按精度要求布设控制网点、规划观测时段、确认采样间隔（常规 E 级网 5~10 s，高等级网 ≤5 s）、卫星高度截止角（平原 15°，山区 20°） |
| 2 | 外业观测 | 接收机对中整平、静态存储模式、双频接收机普通基线 ≥30 min、长基线/高等级网 ≥45 min；记录测站号、仪器号、仪器高、开关机时间 |
| 3 | 数据导出 | 原始观测文件拷贝，确认文件完整无损 |

**内业后处理解算**

| # | 步骤 | 要点 |
|---|------|------|
| 4 | 项目初始化 | 新建项目，设置控制等级、当地坐标系、中央子午线、高程基准 |
| 5 | 数据导入与校验 | 批量导入外业观测文件，逐一对天线高、接收机类型，确保与手簿记录一致 |
| 6 | **基线解算** | 设置基线解算参数，**短基线默认通过差分消去大气误差，长基线开启天顶对流层延迟估算**；出现浮点解时调整高度截止角、剔除质量差的卫星信号重解，直到所有基线达到"固定解" |
| 7 | **基线质量检核** | 逐项检查**重复基线、同步环、异步环闭合差**，确保符合测量规范限差 |
| 8 | **网平差计算** | 导入已知控制点坐标，依次执行**自由网平差、WGS84 约束平差、当地坐标系三维/二维约束平差**，完成坐标系统转换 |
| 9 | 成果输出 | 生成含基线精度、平差结果、点位中误差的完整报告，输出测站高精度坐标成果 |

> 说明：原文编号为 1~3、1~6 两段，此处统一为 1~9 连续编号。

### 14.2 关键呼应：第 6 步的配置哲学

> "**短基线默认通过差分消去大气误差，长基线开启天顶对流层延迟估算**"

这句话是**行业标准做法**，与 RTKLIB 的 `ionoopt` / `tropopt` 开关逻辑同源。它给出两条明确结论：

1. **大气模型的选择应由用户按基线长度配置**，不应在代码里按阈值写死；
2. **短基线不估大气，靠双差消去**——这正是 §2 中"当前实现每基线都估大气"需要修正的方向。

但**配置化有一个前提**：必须先修正大气参数的维度划分（见 §2.1 修订说明）。否则同一个 `tropopt=EST` 开关在联合模式下会展开成 k 份独立的 rover ZWD，语义与 RTKLIB 不一致。

### 14.3 逐项覆盖度对照

| 参照流程步骤 | 本仓库对应能力 | 判定 |
|---|---|---|
| 1~3 外业采集（布网、观测、导出） | 无（外业作业范畴，非软件职责） | ❌ |
| 4~5 项目初始化、数据导入校验 | 部分：`RtcmFileDataSource` / `RinexFileDataSource` 可导入；无项目化管理与天线高校验 | 🔶 |
| **6 基线解算** | `rtklib-core` 单基线 ✅；`rtklib-joint` 联合解算 🔶；**但 `ionoopt`/`tropopt` 的配置策略缺失——当前每基线都估大气** | 🔶 |
| **7 基线质量检核** | 交叉验证 ≈ 同步环 ✅；Baarda 粗差探测 ✅；**重复基线 ❌；异步环闭合差 ❌** | 🔶 |
| **8 网平差计算** | `rtklib-adjust` 有基线向量→坐标的最小二乘平差内核 + 精度评定 ✅；**自由网基准 ❌；WGS84 约束平差 ❌；七参数坐标转换 ❌** | 🔶 |
| 9 成果输出 | `AdjustResult`（σ0、协方差、baardaT）、`ReliabilityGrade`、`BaseStationDiagnosis` ✅；**完整报告与当地坐标成果 ❌** | 🔶 |

### 14.4 时间层级差异：历元级 vs 时段级

本仓库的 `rtklib-adjust` 与参照流程**不在同一时间层级**：

```
参照流程（时段级，批处理）：
  外业 30~45 min  ──→  一次性解算整段  ──→  网平差  ──→  坐标成果

rtklib-adjust（历元级，准实时）：
  feedBaseline(baseId, SolData, epochTag)   ← 逐历元喂入
        ↓
  processEpoch()                             ← 每历元平差一次
        ↓
  windowCoords 累积 → computeBatch()         ← 窗口滑动融合
```

`windowCoords` + `computeBatch()` 是**窗口滑动平均**，不等于参照流程的"整段静态解算"。若要完整对标时段级批处理，需要另外实现整时段累积与一次性解算的路径。

### 14.5 缺口清单与归属

| 缺口 | 归属模块 | 优先级 |
|---|---|---|
| `ionoopt`/`tropopt` 配置策略 + 大气参数维度修正 | `rtklib-joint`（§2.1 修订） | 高 |
| 异步环 / 重复基线闭合差检核 | `rtklib-adjust` 新增 | 中 |
| 自由网基准 + 约束平差 + 七参数坐标转换 | `rtklib-adjust` 新增 | 视交付需求 |
| 成果报告（点位中误差、精度统计） | `rtklib-adjust` 新增 | 视交付需求 |
| 整时段批处理路径（对标 30~45 min 静态） | `rtklib-adjust` 新增 | 低（窗口融合已可近似） |

**关于异步环**：这是**多基站网络独有**的检核手段，交叉验证无法替代。

```
同步环：同一历元 A→B、B→C、C→A 闭合        ← 已有（交叉验证）
异步环：不同时段 A→B(t1)、B→C(t2)、C→A(t3)  ← 完全没有
```

异步环能发现**时间相关的系统误差**（基站缓慢位移、天线相位中心变化、多路径时段变化），§13 闸3"基站间骨架网"可直接吸收该思路。

### 14.6 与 SBS / 网平差路线的关系

参照流程属于 **SBS（单基线独立解）+ 网平差** 路线：

```
基线1 ──→ 解 ──→ 基线向量1 ┐
基线2 ──→ 解 ──→ 基线向量2 ├─→ 第8步 网平差（坐标层融合）──→ 坐标
基线3 ──→ 解 ──→ 基线向量3 ┘
```

本模块（`rtklib-joint`）属于 **MBS（观测层联合）** 路线：

```
基线1 ┐
基线2 ├─→ 同一个状态向量、观测层一次性联合估计 ──→ 坐标 + 各基线模糊度
基线3 ┘
```

差异的核心：**网平差这一步在参照流程里是独立的第 8 步（坐标层），在 MBS 里被提前合并进观测方程**。

两者不是替代关系：

| | SBS + 网平差（参照流程） | MBS（本模块） |
|---|---|---|
| 时间粒度 | 时段级批处理 | 历元级 / 流式 |
| 大气处理 | 基线内差分消去或单基线估计 | 可全网共享（梯度模型） |
| 坐标融合 | 事后坐标层平差 | 观测层联合估计 |
| 冗余利用 | 基线间事后加权 | 历元内即时耦合 |
| 适用 | 事后高精度坐标成果 | 实时/准实时定位与形变告警 |

### 14.7 选型建议

| 目标 | 推荐路径 |
|---|---|
| **A. 事后输出高精度坐标成果**（对标参照流程） | `rtklib-core`（单基线解算）+ `rtklib-adjust`（网平差 + 质量检核）。优先补异步环、自由网/约束平差、坐标转换。**`rtklib-joint` 在这个场景不是必需** |
| **B. 实时/准实时定位与形变告警** | `rtklib-joint` 优先。先修大气维度语义 + 配置化，再解决 FIX 率问题 |
| **C. 长基线（>10 km）联合解算** | `rtklib-joint` 的 MBS 梯度模型才有实质收益；短基线场景 MBS 相对 SBS 增益有限 |