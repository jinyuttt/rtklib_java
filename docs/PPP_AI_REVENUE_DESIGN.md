# PPP 体系 AI 接入收益化设计

**文档性质**：设计文档（收益导向）
**版本**：v1.0（2026-11-14）
**基线**：rtklib-java v3.5.0（当前 `pom.xml`；I-1 对照基准仍为冻结的 v3.4.0 tag）、《GNSS AI 增强开发文档 v1.4》、《PPP 体系 AI 接入技术评估 v1.0》
**定位**：本文件不回答「该不该接」，只回答「如果接，接口怎么设计、收益从哪来、怎么量」。

---

## 1. 设计原则

### 1.1 每条设计必须绑定一个收益来源

不写「为了接 AI 而接 AI」的接口。每个接口的设计都从「它替换的是哪个经验常数、收益在哪个维度、用什么量测」反推。

| 接口 | 替换的经验常数 | 收益维度 | 收益量测方式 |
|---|---|---|---|
| A `PppSolutionValidator` | 「`SOLQ_PPP` 一律可信」这个隐含假设 | 可靠性：错误解输出率 | 参考轨迹误差 vs 降级标记 |
| B `PppVarianceScaler` | `PppCore.varerr` 的解析方差模型 | 可靠性：NLOS / 多路径导致的方差低估 | 参考轨迹误差 + 方差校准度 |
| D `PppRtkFixValidator` | `ratio < 3.0` 这个单点门限 | 可靠性：错误固定率 | 固定解误差 vs 拒绝标记 |

### 1.2 三条共同约束

- **只读原则**：只改权重、门控、标记，不写状态、先验、整数。
- **降级不干预**：`null` / `NaN` / 非有限 / 抛异常 → 按不干预处理，禁止 fail-closed。
- **I-1 零回归**：未注入时行为与基线逐位一致。

### 1.3 收益维度声明

三个接口都只做**可靠性**，不做**精度**。AI 不会让「差环境变得能固定」，只会「在差环境里少输出错解」。目标应写为「降低错误解输出率」，而非「提升固定率 / 精度」。

---

## 2. 候选 A：`PppSolutionValidator`（PPP 解有效性判别）

### 2.1 收益来源

PPP 浮点的真实失效形态不是「米级误差当厘米级输出」，而是 `SOLQ_PPP` 没有精度等级——3 cm 和 3 dm 都是 `SOLQ_PPP`，下游无法按状态分流。A 的收益是：**在 `SOLQ_PPP` 之后补一个降级标记，把不可信历元从「输出」变成「不输出」**。

### 2.2 收益分母：确定性基线必须先量

A 的收益上限 = 「qr 阈值确定性方案能拦多少」的补集。设计上必须先有一个确定性对照分支：

```java
// PppCore，确定性对照分支（非 AI，先量基线）
boolean deterministicDegraded = false;
double posVar = rtk.sol.qr[0] + rtk.sol.qr[1] + rtk.sol.qr[2];
if (posVar > ai.pppQrThreshold) {
    deterministicDegraded = true;
    if (ai.pppQrThreshold != Double.MAX_VALUE) {
        rtk.sol.stat = Constants.SOLQ_NONE;
    }
}
```

这个分支不是 A 的前置条件，是 A 的**收益分母**。量出「确定性拦 60%」之后，A 的收益上限就是剩下 40% 里能多拦多少。`deterministicDegraded` 在此处产生、随 context 传给 A（§2.3），是 §6.3 三个归因计数的唯一来源；门限未启用时标志仍按同一口径计算，只是不写 `sol.stat`（原因见 §2.5）。

### 2.3 SPI 定义

```java
public interface PppSolutionValidator {
    /**
     * 返回解有效性置信度 ∈ [0,1]，1.0 = 完全可信。
     * null / NaN / 非有限 / 抛异常 → 降级为 1.0（不干预）。
     * 禁止修改 Rtk / Ssat 状态。
     */
    Double validate(PppSolutionContext ctx);
}

public record PppSolutionContext(
    int epoch,
    int ns,                    // 参与解算的卫星数
    double[] positionVar,      // P 对角元 E/N/U（3 维）
    double[] residuals,        // PPP 非差验后残差（长度 ns）
    double[] elevation,        // 逐星仰角
    double[] snr,              // 逐星 SNR
    double convergenceMetric,  // 滤波收敛度（迭代次数 / 新息 RMS）
    int biasState,             // 偏差状态标志
    boolean deterministicDegraded // 确定性 qr 基线是否已把本历元降级为 SOLQ_NONE
) {}
```

`deterministicDegraded` 是**归因字段**，不改变注入点行为。理由：A 的收益是「确定性基线之上的增量」，若确定性已降级，A 再降级是重复；若确定性未降级而 A 判不可信，那才是 A 的独立贡献。没有这个标志，评估时分不清「A 拦的」与「确定性拦的」（§6.3 的三个计数依赖它）。

注入点照常执行、不做 `if (!deterministicDegraded)` 短路：该历元已是 `SOLQ_NONE` 时 A 的写入是幂等的，短路反而会让「AI 独立触发次数」在两种分支下走不同代码路径、难以对拍。

### 2.4 注入点

`PppCore.updateStat` 之后、`commit` 之前：

```java
PppSolutionValidator sv = AiHooks.resolvePppSolutionValidator(rtk);
if (sv != null) {
    Double confidence;
    try {
        confidence = sv.validate(buildPppSolutionContext(rtk, deterministicDegraded));
    } catch (Throwable t) {
        confidence = 1.0;
    }
    if (confidence == null || !Double.isFinite(confidence)) confidence = 1.0;
    if (confidence < ai.pppSolutionMinConfidence) {
        rtk.sol.stat = Constants.SOLQ_NONE;
        Trace.emit("PPP", "AI_PPP_REJECT", "confidence", confidence,
                "ns", rtk.sol.ns, "detDegraded", deterministicDegraded);
    }
}
```

只改 `sol.stat`，不改 `sol.rr` / `sol.qr` / `rtk.x` / `rtk.P`。`Trace` 里同时打 `detDegraded`，是为了让 §6.3 的三个计数可以从 trace 离线重算，不必重跑 A1 / A2 组。

### 2.5 配置字段

```java
public double pppSolutionMinConfidence = 0.5;   // [0,1]，惰性钳位
public double pppQrThreshold = Double.MAX_VALUE; // 确定性基线门限，默认等于关闭
```

`pppQrThreshold` 属于阶段 1.5 的确定性分支（§7），**默认 `Double.MAX_VALUE` 即分支永不触发**，保证 I-1：默认配置下 `posVar > pppQrThreshold` 恒为 false，行为与基线逐位一致。

`deterministicDegraded` 标志的取值口径必须与分支是否启用**解耦**：无论 `pppQrThreshold` 是否打开，都按 `posVar > pppQrThreshold` 计算标志，只在门限打开时才真正写 `sol.stat = SOLQ_NONE`。原因是 §6.1 的 A2 组（仅 AI、不做确定性降级）与 A3 组（确定性 + AI）必须喂给模型同一分布的特征——若 A2 组里标志恒为 `false`、A3 组里标志有 `true`，模型的输入分布在两组间就漂移了，A2 与 A3 的对比失去意义。默认 `Double.MAX_VALUE` 时标志恒为 `false`，A2 组即自然满足该口径。

### 2.6 降级与 I-1

| 条件 | 行为 |
|---|---|
| `sv == null` | 不进分支，行为与基线逐位一致 |
| 返回 `null` / `NaN` / 非有限 / 抛异常 | 按 1.0（不干预） |
| `confidence >= 阈值` | 不干预 |

**I-1 可证**：未注入时分支不存在；注入但输出全 1.0 时，`confidence < 阈值` 恒为 false，`sol.stat` 不被改写。确定性分支默认 `pppQrThreshold = Double.MAX_VALUE` 时不触发；`deterministicDegraded` 的计算只读 `sol.qr`，不写任何状态，对基线无影响（§2.5）。

### 2.7 收益量测协议

| 指标 | 定义 | 判据方向 |
|---|---|---|
| 错误解输出率 | 输出历元中误差 > 阈值的占比 | 下降 |
| 有效输出率 | 输出历元中误差 ≤ 阈值的占比 | 下降 ≤ 1 pp |
| AI 增量 | 确定性基线之上多拦的错误历元数（= §6.3「AI 独立触发次数」里判错的那些） | 须 > 0 且统计显著 |

---

## 3. 候选 B：`PppVarianceScaler`（PPP 观测方差缩放）

### 3.1 收益来源

`PppCore.varerr` 的解析方差模型是经验公式（仰角 + SNR + 基线长 + 钟漂）。在多路径 / NLOS 场景下会系统性低估方差。B 的收益是：**用 AI 预测的方差膨胀因子，修正解析模型的低估**。

注意：B 只有可靠性收益，没有精度收益。单边钳位只能膨胀不能收缩。

### 3.2 路径选择：新增独立 SPI，不碰 RTK 侧降级语义

| 路径 | 做法 | 代价 |
|---|---|---|
| 路径 1 | 放宽 `RtkCore` 的 `null` 检查 | 改已冻结降级语义，重跑 I-1 |
| 路径 2（推荐） | 新增独立 SPI `PppVarianceScaler` | 与 A 同等量级接口工作，但不碰 RTK 侧降级语义 |

### 3.3 SPI 定义

```java
public interface PppVarianceScaler {
    /**
     * 批量：对 ns 颗卫星输出方差膨胀因子 ∈ [1.0, +∞)，行序 = ctx.sats() 顺序。
     * null / 长度不符 / 抛异常 → 降级为全 1.0。
     */
    double[] varianceScale(PppObservationContext ctx);
}

public record PppObservationContext(
    int epoch, int ns, int nf, double dt,
    PppSatFeature[] sats
) {}

public record PppSatFeature(
    int sat, int sys,
    double az, double el,
    double[] snr,      // 长度 nf
    double[] resp,     // 非差伪距残差（与 RTK 侧双差残差不同源，不可混用训练集）
    double[] resc,     // 非差载波残差（同上）
    int[] lock, int[] slip, int[] half, int[] vsat
) {}
```

**`resp` / `resc` 是非差语义，必须与接口三的双差残差严格区分。** RTK 侧接口三的 `resp` / `resc` 是双差残差，PPP 侧是逐星非差残差——两者的量纲、分布、物理含义都不同（双差已消掉接收机钟差与卫星钟差，非差没有）。训练 B 的模型时若误用接口三的训练集，会造成特征分布漂移：归一化参数按双差分布拟合，推理侧喂非差残差，输入整体 z 漂移，模型输出无意义。这个坑在接口三的 H1（`resp` / `resc` 标签泄漏）里已经踩过一次，PPP 侧须在字段注释与训练管线里同时标注，**两个接口的训练集不可互换、不可拼接**。

### 3.4 注入点

`PppCore.varerr` 返回后、进入 `R` 矩阵之前，每历元一次批量推理：

```java
double[] aiScale = null;
PppVarianceScaler vs = AiHooks.resolvePppVarianceScaler(rtk);
if (vs != null) {
    try {
        aiScale = vs.varianceScale(buildPppObservationContext(rtk, ...));
        if (aiScale == null || aiScale.length != ns) aiScale = null;
    } catch (Throwable t) {
        aiScale = null;
    }
}
if (aiScale != null) {
    for (int i = 0; i < ns; i++) {
        double s = aiScale[i];
        if (!Double.isFinite(s) || s < 1.0) s = 1.0;
        if (s > ai.pppVarianceScaleMax) s = ai.pppVarianceScaleMax;
        R[i * nx + i] *= s;
    }
}
```

没有 `biasMeters`：PPP 的偏差会被 `IB(sat, frq)` bias 状态吸收，写偏差进状态会污染 AR 输入。只改方差是安全的。

### 3.5 配置字段

```java
public double pppVarianceScaleMax = 4.0; // [1.0, +∞)
```

### 3.6 降级与 I-1

| 条件 | 行为 |
|---|---|
| `vs == null` | `aiScale = null`，不进施加分支 |
| 返回 `null` / 长度不符 / 抛异常 | `aiScale = null` |
| 单值 < 1.0 或非有限 | 该行按 1.0 |
| 单值 > `pppVarianceScaleMax` | 钳到上限，告警一次 |

**I-1 可证**：未注入时 `aiScale == null`；注入但输出全 1.0 时，`R *= 1.0` 不改变数值。

### 3.7 收益量测协议

| 指标 | 定义 | 判据方向 |
|---|---|---|
| 方差校准度 | 实际残差平方 / 预测方差（理想值 1.0） | 向 1.0 收敛 |
| 错误解输出率 | 输出历元中误差 > 阈值的占比 | 下降 |
| 收敛速度 | 达到稳定解所需历元数 | 不显著变慢 |
| 精度 | 位置 RMS | 不承诺改善 |

---

## 4. 候选 D：`PppRtkFixValidator`（PPP-RTK 固定校验）

### 4.1 收益来源

`PppRtkAmbFix` 只有 `ratio < 3.0` 一个门限，固定后无验后校验。D 的收益是：**在 ratio 门限之后加一道 AI 门控，拦截「ratio 通过但固定坐标错误」的历元**。

### 4.2 当前收益对象状态

当前零收益的原因不是「设计不出来」，是「固定坐标不进输出」——校验器面对「浮点解 + FIX 标签」，没有「固定坐标错在哪里」可学。但**接口设计本身是完整的**，不依赖输出缺陷是否修复。修复输出缺陷后，接口不需要改，只是收益对象出现了。

### 4.3 SPI 定义

```java
public interface PppRtkFixValidator {
    /**
     * 返回固定解置信度 ∈ [0,1]，1.0 = 完全可信。
     * null / NaN / 抛异常 → 降级为 1.0（保留 FIX）。
     */
    Double validate(PppRtkFixContext ctx);
}

public record PppRtkFixContext(
    int epoch, int ns,
    double ratioWl, double ratioNl,
    double maxDevWl, double maxDevNl, double maxSigNl,
    int nFixed,
    double[] postResiduals,   // 固定后验后残差
    double[] positionVar,     // 固定后位置方差（3 维）
    boolean ratioPassed       // ratio 门限是否已通过（true = 已进固定分支）
) {}
```

`ratioPassed` 把「D 只在 ratio 通过后判」的语义在 context 里显式化，而不是靠调用位置隐式保证。`PppRtkAmbFix` 可能因 `ratio < 3.0` 根本没进固定分支，此时 `validate` 不该被调用；字段存在的作用是让这条约束在**契约层面可断言**——实现若被后续重构挪到 ratio 判定之前（如 partial AR、多频逐级固定新增的入口），`ratioPassed == false` 的历元会被记录而不是静默误判。

按定义，注入点执行时该字段恒为 `true`（见 §4.4）；契约测试 T8（§8）对此断言，一旦出现 `false` 即说明注入点被挪错位置。

### 4.4 注入点

`PppRtkAmbFix` 固定成功后、写 `xa[idx]` / `sol.stat = SOLQ_FIX` 之前：

```java
Double fixConfidence = 1.0;
PppRtkFixValidator fv = AiHooks.resolvePppRtkFixValidator(rtk);
if (fv != null) {
    try {
        fixConfidence = fv.validate(buildPppRtkFixContext(... /* ratioPassed = true */));
    } catch (Throwable t) {
        fixConfidence = 1.0;
    }
    if (fixConfidence == null || !Double.isFinite(fixConfidence)) fixConfidence = 1.0;
}
if (fixConfidence < ai.pppRtkFixMinConfidence) {
    Trace.emit("PPP", "AI_PPP_RTK_FIX_REJECT", "confidence", fixConfidence);
    return; // 拒绝固定，保持浮点
}
// 原有固定逻辑
```

拒绝固定只影响本历元的 `xa` / `sol.stat`，不改 `Pa`、不改 `rtk.x`、不回写滤波器。

构造 `PppRtkFixContext` 时 `ratioPassed` 必须显式传 `true`（该行代码只会在 ratio 判定通过之后到达）。不写成字面量 `true` 而保留字段，是为了让契约测试能验证「注入点位置正确」，而不是让字段永远等于常量而无从检查。

### 4.5 配置字段

```java
public double pppRtkFixMinConfidence = 0.5; // [0,1]
```

### 4.6 降级与 I-1

| 条件 | 行为 |
|---|---|
| `fv == null` | 不进分支，固定逻辑与基线一致 |
| 返回 `null` / `NaN` / 抛异常 | 按 1.0（保留 FIX） |
| `confidence >= 阈值` | 不干预 |
| `confidence < 阈值` | 拒绝固定，保持浮点 |

### 4.7 收益量测协议

| 指标 | 定义 | 判据方向 |
|---|---|---|
| 错误固定率 | 固定解误差 > 阈值的占比 | 下降 |
| 固定率 | FIX 历元占比 | 下降 ≤ 1 pp |
| AI 增量 | 相对 ratio 单门限多拦的错误固定 | 须 > 0 且统计显著 |

---

## 5. 框架层：`AiProvider` 扩展

### 5.1 接口定义

```java
public interface AiProvider {
    default FixValidator fixValidator() { return null; }
    default ObsCorrector obsCorrector() { return null; }
    default PppSolutionValidator pppSolutionValidator() { return null; }
    default PppVarianceScaler pppVarianceScaler() { return null; }
    default PppRtkFixValidator pppRtkFixValidator() { return null; }
}
```

### 5.2 `AiHooks` 扩展

```java
public static PppSolutionValidator resolvePppSolutionValidator(Rtk rtk) {
    if (rtk != null && rtk.aiPppSolutionValidator != null) return rtk.aiPppSolutionValidator;
    AiProvider p = TL.get();
    return p == null ? null : p.pppSolutionValidator();
}

// 其余两个照此模式
```

### 5.3 多接口共存约束

- `AiHooks` 保持 ThreadLocal 单槽，多接口同时开时由 `rtklib-ai` 侧复合 provider 返回多个实现；
- 一次只开一个接口做 A/B；
- 每个接口单独过判据，不打包测试；
- 只读标记类接口（A）可独立评估；写状态类接口同时开才需联合评估。

---

## 6. 收益评估协议（三接口共用）

### 6.1 四组对照

| 组 | 配置 | 用途 |
|---|---|---|
| A0 | 基线（无 AI、无确定性降级） | 收益的绝对基线 |
| A1 | 仅确定性降级（A 的 qr 阈值 / B 的解析方差 / D 的 ratio 单门限） | AI 增量的分母 |
| A2 | 仅 AI（注入，不做确定性降级） | AI 的独立效应 |
| A3 | 确定性 + AI | 组合效应 |

**AI 增量 = A3 相对 A1 的改善**，不是 A3 相对 A0。这是收益评估的核心口径。

### 6.2 统计口径

- A/B 必须同历元配对（不得跨数据集比两个独立比例）；
- 置信区间用配对 bootstrap，不要求 MDE 前置（「不恶化」是单侧保守判据）；
- 两层切分照用：按站切 + 站内按天切，禁止随机切分。

### 6.3 必须报告的辅助指标

- `ns` / `vsat` 变化（参考星 / 卫星可用性是否被间接影响）；
- 降级事件计数（区分「没判」与「判了但压住」）；
- 推理耗时（单历元批量调用，非逐卫星累加）。

**触发结构三计数（A 专属，按历元配对）**：

| 指标 | 定义 |
|---|---|
| 确定性基线触发次数 | A1 组中被 qr 阈值降级的历元数 |
| AI 独立触发次数 | A2 组中 AI 降级但确定性基线未降级的历元数 |
| 两者同时触发次数 | A3 组中确定性基线与 AI 都降级的历元数 |

这三个数决定 A 的增量到底来自「AI 补了确定性的漏」还是「AI 和确定性重复」。没有它们，§6.1 的「AI 增量 = A3 − A1」只是一个差值，看不出结构。

三者构成一个集合分解，可用于交叉校验计数是否自洽：

```
A3 的降级集合 = 确定性触发 ∪ AI 独立触发
             = (确定性仅触发) ∪ (两者同时触发) ∪ (AI 独立触发)
```

其中「AI 独立触发次数」恒等于 `|A3 降级集合 \ A1 降级集合|`，也就是 §2.7 判据里「确定性基线之上多拦的错误历元数」的**分子**。若该计数为 0，A 的 AI 增量必为 0——此时不论 AUC 多高，A 都没有收益，应直接判定不交付。

`deterministicDegraded` 标志（§2.3）就是为产出这三个计数而存在的：A3 组里逐历元读它即可把降级归因，无需重跑 A1。

---

## 7. 实现顺序与工作量

| 阶段 | 内容 | 依赖 | 工作量 |
|---|---|---|---|
| 1 | `AiProvider` 加 `default` 方法 + `AiHooks` 加 resolve | 无 | 0.5 天 |
| 1.5 | **A 的收益分母测量**：在 `PppCore` 加确定性 `qr` 阈值降级分支，在真实数据上量出拦截率 | 无（确定性代码，不需要 AI） | 0.5 天 |
| 2 | A：SPI + 注入点 + `AiOpts` + 契约测试 | 阶段 1、1.5 | 2–3 天 |
| 3 | B：SPI + `varerr` 注入点 + 单侧钳位 | 阶段 1 | 2–3 天 |
| 4 | D：SPI + 固定前注入点 | 阶段 1 | 2–3 天 |

**阶段 1.5 是阶段 2 的 go / no-go 门，不是可选项。** 如果 `qr` 基线拦截率已经 80%，A 的 AI 增量上限只有 20%，阶段 2 的 2–3 天投入可能不值得；如果拦截率 30%，A 的增量空间大，阶段 2 值得做。这个数不量，阶段 2 的投入是盲目的。

阶段 1.5 的产物同时是 §6.1 的 A1 组基线代码——它既是收益分母，也是评估协议的必需组，不是一次性探针。

每个阶段包含：SPI 定义（core，零依赖）、`AiOpts` 字段（惰性钳位）、注入点（唯一分支）、降级路径、I-1 零回归测试、契约测试（构造输入，不需要真实数据）。阶段 1.5 例外：它是纯确定性代码，只要求 I-1 零回归测试（默认 `qrThreshold` 关闭时必须与基线逐位一致）。

---

## 8. 契约测试清单（不需要数据）

| 测试 | 断言 |
|---|---|
| T1 未注入 | 三个 resolve 均返回 `null`，core 行为与基线逐位一致 |
| T2 注入但全 1.0 | 三个接口均不干预，行为与基线一致 |
| T3 降级 | 返回 `null` / `NaN` / 抛异常 → 按不干预处理，写 `AI_DEGRADE` trace |
| T4 长度不符 | B 返回长度 ≠ ns → 整体降级为全 1.0 |
| T5 钳位 | B 返回 < 1.0 → 按 1.0；> `pppVarianceScaleMax` → 钳到上限并告警 |
| T6 只读性 | A 只改 `sol.stat`，`sol.rr` / `sol.qr` 不变；D 拒绝固定时 `rtk.x` / `Pa` 不变 |
| T7 异常隔离 | provider 抛 `RuntimeException` → core 不崩，按不干预处理 |
| T8 上下文标志 | A 的 `deterministicDegraded` 与实际降级状态一致；D 的 `ratioPassed` 恒为 `true`（为 `false` 即注入点被挪错位置） |

这 8 条全绿 = 三个接口的架构通了。

T8 不验证模型、只验证**上下文构造**：A 的标志要与确定性分支的实际写入同源（同一个布尔变量，不是两处独立判断），D 的标志要能暴露注入点位置错误。两个字段都是归因 / 契约字段，若只在测试里硬编码为常量，T8 就会假绿——所以实现上必须从真实状态读取。

---

## 9. 模型侧 ONNX 契约

| 接口 | 输入 | 输出 | 与 RTK 侧的差异 |
|---|---|---|---|
| A `PppSolutionValidator` | `[batch, N]`，`N = 3 + ns×4 + 3` | `[batch, 1]` sigmoid | 特征是非差语义（无 `ddres` 残差） |
| B `PppVarianceScaler` | `[batch, ns×nf, M]` | `[batch, ns×nf]`，sigmoid 映射到 `[1, max]` | 无 `biasMeters`，只有 `varianceScale` |
| D `PppRtkFixValidator` | `[batch, K]` | `[batch, 1]` sigmoid | 输入是固定上下文，不是逐星特征 |

A 的 `N` 比初版多 1，来自 §2.3 新增的 `deterministicDegraded`（3 个位置方差 + 4 组逐星量 + `convergenceMetric` / `biasState` / `deterministicDegraded` 三个标量）。`ratioPassed` 同理计入 D 的 `K`（§4.3）。

**为什么 `deterministicDegraded` 必须进模型输入（而非仅作归因字段）**，三条理由：

1. **学习目标需要它**。A 的收益是「确定性基线之上的增量」。模型若不知道确定性会不会拦，只能学「这个历元是否不可信」——其中一部分已被确定性拦住，A 再判是重复。知道该标志，学习目标才聚焦到「确定性没拦住的那部分里，哪些确实不可信」。否则 A 退化成「学整个不可信子集」，收益口径随之改变。
2. **模型无法从 `positionVar` 自行推出该标志**。`pppQrThreshold` 是可配置阈值且不在模型输入里：同一组 `positionVar`，阈值取 0.01 与取 0.1 时确定性是否拦截的结果完全不同。模型看得到 `positionVar` 的数值，却不知道当前阈值是多少，因此推不出「确定性会不会拦」。
3. **A2 / A3 的输入分布不会因此漂移**。§2.5 的解耦规则已保证：标志始终按 `posVar > pppQrThreshold` 计算、与分支是否启用无关。A2 组默认 `Double.MAX_VALUE`，标志恒 `false`；A3 组若同样不启用门限，标志也恒 `false`。只有 A3 组**启用**确定性门限时标志才可能为 `true`——这正是 A2 与 A3 的定义差异，是实验设计，不是输入漂移。

- **导出与加载**：与 RTK 侧一致，`torch.onnx.export` → `OnnxInference` 加载 → batch 探针 1/7/64 全过。
- **归一化**：每个接口独立 `norm.json`，训练集拟合，推理侧原样加载。禁止推理侧动态归一化。

---

## 10. 收益排序与结论

| 排序 | 接口 | 收益维度 | 收益可量测性 | 实现成本 |
|---|---|---|---|---|
| 1 | A `PppSolutionValidator` | 可靠性 | 高（有参考轨迹即可量） | 中 |
| 2 | B `PppVarianceScaler` | 可靠性（无精度） | 中（方差校准度间接量） | 中 |
| 3 | D `PppRtkFixValidator` | 可靠性 | 当前为零（对象不存在） | 大（依赖输出修复） |

**实现顺序建议**：A → B → D。理由不是「前置条件」，是「收益可量测性从高到低」。

**阶段 1.5 的门禁作用**：A 的排序第 1 建立在「qr 基线拦截率不会把增量空间吃光」这个未量化的假设上。阶段 1.5 用 0.5 天确定性代码把这个假设变成数字——拦截率过高时 A 直接降级或暂缓，不必先投 2–3 天做完 SPI 再发现增量上限不足。B 与 D 无对应的分母步骤：B 的分母是解析方差模型本身（已存在），D 的分母是 ratio 单门限（已存在），都不需要新增对照分支。

**一句话结论**：三个接口的共同点是「只做可靠性、不做精度」：A 在 `SOLQ_PPP` 后补降级标记（收益分母是 qr 基线），B 修正解析方差模型的低估（收益是方差校准度，无精度承诺），D 在 ratio 门限后补固定校验（当前无收益对象，但接口设计完整）。判据统一为「AI 增量 = 确定性 + AI 相对确定性基线的改善」。