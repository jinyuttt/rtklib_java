# SolAggregator 历元聚合工具设计文档

> 版本：v1.2（修订版）  
> 日期：2026-10-27  
> 状态：待评审  
> 定位：通用历元聚合工具，输入 `List<SolData>`，输出一个融合结果。不修改 solstatic，不修改滤波器，独立于 RTK/PPP/SPP 主流程。

## 修订记录

| 版本 | 日期 | 修订内容 |
|------|------|---------|
| v1.0 | 2026-10-27 | 初稿 |
| v1.1 | 2026-10-27 | 定稿草案 |
| v1.2 | 2026-10-27 | 评审修订：①明确与 PosFileRtsSmoother 分工；②SolutionStatus.FIX 枚举对齐；③KALMAN_SMOOTH 补充"滤波之滤波"风险；④协方差输出默认改用经验协方差（不偏乐观）；⑤IGG3 迭代到权重稳定；⑥Q 矩阵公式修正为标准常速度模型；⑦time 与输出时刻语义统一；⑧FIX 不足降级策略；⑨性能约束按时间窗口 |

---

## 1. 背景与动机

solstatic 的 bestSol 逻辑是"窗口内按解质量优先级挑一个最早的历元"，输出是单个历元的快照，不是统计融合。对于需要压噪声、抗假固定、或跟踪窗口内真实变化的场景，需要一个独立的聚合工具，对窗口内多个历元做统计融合。

本工具与 solstatic 并存，职责不同：

- **solstatic**：轻量输出，挑一个最优历元，保持现有行为不变；
- **SolAggregator**：统计融合，用窗口内全部高质量历元换一个更稳的结果。

### 1.1 与已有 RTS 平滑工具的分工

项目中已有 `PosFileRtsSmoother`（[PosFileRtsSmoother.java](../rtklib-core/src/main/java/org/rtklib/java/postpos/PosFileRtsSmoother.java)），但定位不同：

| 维度 | PosFileRtsSmoother | SolAggregator |
|------|-------------------|---------------|
| 输入 | RTKLIB `.pos` 文件（ENU 基线） | 内存 `List<SolData>`（ECEF） |
| 时机 | 事后文件批处理 | 实时/准实时内存聚合 |
| 模式 | 简单反转 / 卡尔曼 RTS | 鲁棒加权 / 卡尔曼 RTS |
| 状态 | 测试/实验性质 | 生产级接口 |
| 协方差 | 从 .pos 的 sde/sdn/sdu 重构（丢失符号） | 直接用 `SolData.covEcef`（完整 3×3） |

两者不冲突：PosFileRtsSmoother 面向事后文件评估，SolAggregator 面向在线/准实时内存聚合。

---

## 2. 目标与非目标

### 2.1 目标

- 输入 `List<SolData>`，输出一个融合坐标、融合协方差和状态码；
- 支持两种聚合模式：鲁棒加权（默认）、卡尔曼平滑（跟踪窗口内变化）；
- 纯函数式接口，可独立测试；
- 输出协方差默认用**经验协方差**，不直接使用滤波协方差的加权结果（避免偏乐观）。

### 2.2 非目标

- 不修改 solstatic、RtkProcessor、PppProcessor 的现有行为；
- 不做多基线平差（rtklib-adjust 的职责）；
- 不做基站诊断（rtklib-adjust 的职责）；
- 首期不处理 FLOAT 历元的统计融合（见 §12）。

---

## 3. 输入与输出契约

### 3.1 输入

```java
public static AggregatedSol aggregate(
    List<SolData> window,          // 窗口内历元，按时间升序
    AggregationMode mode,          // ROBUST 或 KALMAN_SMOOTH
    AggregationParams params       // 模式相关参数
);
```

约束：

- `window` 非空，按时间升序排列；
- `window` 可包含 FIX、FLOAT、SINGLE 等不同状态的历元，工具内部按 §4.2 过滤；
- 每个 `SolData` 的 `covEcef` 可以为 null（RTKLIB 约定 `qr[0]==0` 为哨兵，见 [SolData.java#L120-L122](../rtklib-core/src/main/java/org/rtklib/java/data/SolData.java#L120)）。

### 3.2 输出

```java
public final class AggregatedSol {
    public final AggregationStatus status;  // OK / EMPTY_WINDOW / ...
    public final GTime time;                // 输出时刻（见 §3.3）
    public final GTime lastEpochTime;       // 窗口末历元时刻（诊断用）
    public final double[] posEcef;          // 融合坐标 ECEF [3]
    public final double[] covEcef;          // 融合协方差 ECEF [9]，行优先（默认经验协方差）
    public final double[] covEcefTheoretical; // 理论加权协方差 Ĉ（诊断用，偏乐观）
    public final int usedEpochs;            // 实际参与融合的 FIX 历元数
    public final int rejectedEpochs;        // 被过滤的非 FIX + IGG3 置零的 FIX 数
    public final double[] windowStdEcef;    // 窗口内坐标经验标准差 [3]，诊断用
    public final AggregationMode mode;      // 实际使用的模式
}
```

### 3.3 time 字段语义

| 模式 | time 语义 | 理由 |
|------|----------|------|
| ROBUST | 窗口内参与历元的**时间加权中心**（权重 = IGG3 降权后的 w_i） | 代表窗口内统计融合的代表时刻 |
| KALMAN_SMOOTH | **窗口末时刻**（lastEpochTime） | RTS 平滑输出末时刻状态，time 应与输出状态对应 |

`lastEpochTime` 单独记录窗口末历元时刻，供需要"末时刻状态"的调用方使用。两种模式下 `lastEpochTime` 始终为窗口末历元时刻。

> **设计决策**：v1.1 中两种模式统一用"时间加权中心"不合理——KALMAN_SMOOTH 输出的是末时刻平滑状态，time 必须对应末时刻，否则位置与时间不匹配。ROBUST 是静态融合，用加权中心更合理。

`status` 字段：对象永不为 null。失败时 `status` 为非 OK 枚举值，`posEcef`/`covEcef` 为 null，调用方通过 `status` 判断失败原因（见 §8）。

---

## 4. 通用预处理

### 4.1 时间排序与去重

输入按时间升序排序。若存在重复时间戳，保留 `status` 优先级最高的一个（FIX > FLOAT > SINGLE，对齐 [RtkProcessor.java#L744](../rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkProcessor.java#L744) 的 `SOL_PRIO` 逻辑）。

### 4.2 历元过滤

默认只保留 `status == SolutionStatus.FIX` 的历元参与融合。FLOAT/SINGLE 历元不参与。

> 注意：用 `SolutionStatus.FIX` 枚举判断，不用整数 `Constants.SOLQ_FIX`，与 `SolData.status` 字段类型对齐。

统计口径（写死）：

- `rejectedEpochs` = 被过滤的非 FIX 历元数 + FIX 历元中被 IGG3 降权置零的数量；
- `usedEpochs` = 实际参与加权 / 更新的 FIX 历元数。

### 4.3 协方差有效性

`covEcef == null` 的历元视为无有效协方差：

- **ROBUST 模式**：该历元不参与加权，但可参与中位数初值估计（若 `params.useMedianInit = true`）；
- **KALMAN_SMOOTH 模式**：该历元不参与更新，仅做预测（见 §6.3）。

### 4.4 最小样本约束与降级

若 FIX 历元数 < `params.minFixEpochs`（默认 3）：

- 默认行为：返回 `status = INSUFFICIENT_FIX`；
- 若 `params.allowFloatFallback = true`（默认 false）：放宽过滤条件，将 FLOAT 历元纳入，重新统计；若仍不足，返回 `INSUFFICIENT_FIX`。

> **降级策略说明**：首期 `allowFloatFallback` 默认关闭。在 FIX 率较低的场景（如城市峡谷），窗口内 FIX 历元可能持续不足 3 个，导致工具持续输出失败。开启降级后 FLOAT 历元参与融合，但粗差风险上升，需业务方评估。

---

## 5. 模式一：鲁棒加权（ROBUST）

### 5.1 算法流程

1. **初值估计**：若 `params.useMedianInit = true`，取窗口内 FIX 历元坐标的分量中位数作为初值 x₀；否则用 `covEcef` 加权平均作为初值。

2. **标准化残差**：对每个历元 i，计算 `r_i = x_i - x₀`，标准化残差 `z_i = sqrt(r_iᵀ C_i⁻¹ r_i)`，其中 C_i 是该历元的 `covEcef` 3×3 子块。

3. **IGG3 降权**：

   | \|z_i\| 范围 | 权重 w_i |
   |-------------|---------|
   | \|z_i\| ≤ k₀ | 1 |
   | k₀ < \|z_i\| ≤ k₁ | k₀ / \|z_i\| |
   | \|z_i\| > k₁ | 0（剔除） |

   默认 k₀ = 1.5，k₁ = 3.0，可通过 `params` 调整。

4. **加权平均**：用降权后的权重和 C_i⁻¹ 计算融合坐标 x̂ 和理论加权协方差 Ĉ：

   ```
   Ĉ = (Σ w_i C_i⁻¹)⁻¹
   x̂ = Ĉ · Σ w_i C_i⁻¹ x_i
   ```

5. **迭代**：用 x̂ 替代 x₀，重复步骤 2–4，直到权重稳定（`Σ|w_i_new - w_i_old| < 1e-6`）或达到 `params.maxIter`（默认 5）次。

> **修订说明**：v1.1 写"固定迭代一轮"不合理。IGG3 的核心是迭代重加权——只有权重收敛后，粗差历元才会被正确识别和剔除。只迭代一轮在初值偏差大时可能漏判粗差。改为迭代到权重稳定，上限 5 次（对 3×3 矩阵求逆，5 次迭代开销可忽略）。

### 5.2 输出协方差

**默认输出经验协方差**（不偏乐观）：

```
C_empirical = (1 / (n-1)) · Σ (x_i - x̂)(x_i - x̂)ᵀ
```

其中求和仅对 `w_i > 0` 的历元。

**小样本保护**：当 `n < params.minEmpiricalEpochs`（默认 5）时，对 `C_empirical` 对角线施加下限，下限取 Ĉ 对角线的对应分量，避免经验协方差估计过小。

**全 `covEcef == null` 退化**：若所有 FIX 历元的 `covEcef` 均为 null，退化为中位数输出。此时 `posEcef` 为中位数坐标，`covEcef` 填经验协方差矩阵（`C_empirical`）；若样本数不足以估计经验协方差（`n < minFixEpochs`），返回 `status = COV_UNAVAILABLE`。

**字段映射**：

- `AggregatedSol.covEcef` = `C_empirical`（默认输出，不偏乐观）
- `AggregatedSol.covEcefTheoretical` = Ĉ（理论加权协方差，诊断用，偏乐观）
- `AggregatedSol.windowStdEcef` = `sqrt(diag(C_empirical))`

> **修订说明**：v1.1 写"covEcef 默认输出 Ĉ"与"避免偏乐观"的目标矛盾。Ĉ 基于滤波协方差 C_i，若 C_i 偏乐观则 Ĉ 也偏乐观。改为默认输出经验协方差，Ĉ 仅作为诊断参考。

### 5.3 参数

```java
public class RobustParams implements AggregationParams {
    public boolean useMedianInit = true;
    public double k0 = 1.5;
    public double k1 = 3.0;
    public int maxIter = 5;
    public int minFixEpochs = 3;
    public int minEmpiricalEpochs = 5;
    public boolean allowFloatFallback = false;
}
```

---

## 6. 模式二：卡尔曼平滑（KALMAN_SMOOTH）

### 6.1 状态定义

状态 `x = [pos(3), vel(3)]ᵀ`，位置和速度均为 ECEF。

### 6.2 初始化

- 用窗口内第一个有效 FIX 历元的坐标初始化 pos；
- vel 初始化为零；
- 初始协方差 P₀ 用第一个历元的 `covEcef` 扩展（位置块用 `covEcef`，速度块给 `initVelVar`，默认 (1 m/s)²）。

### 6.3 正向滤波与缺失观测处理

对窗口内每个历元：

- **有观测**（`covEcef != null` 且 `status == FIX`）：按常速度模型预测 + 更新；
- **无观测**（`covEcef == null` 或非 FIX）：只做预测，不做更新，累计缺失计数；
- **连续缺失保护**：若连续缺失历元数超过 `params.maxConsecutiveMiss`（默认 10），返回 `status = KALMAN_OBS_LOST`，防止状态发散。

### 6.4 过程噪声与时间步

常速度模型（状态 = [位置, 速度]，输入为加速度白噪声）的过程噪声矩阵 Q 由单一参数 `q`（加速度过程噪声谱密度，m/s²/√Hz）控制。时间步 Δt 取相邻两个历元的实际时间差：

```
Q = q² · [ Δt³/3   Δt²/2 ]
        [ Δt²/2   Δt    ]
```

窗口内历元非等间隔时，逐历元用实际 Δt 计算 Q，不假设固定采样率。

> **修订说明**：v1.1 用了 qPos 和 qVel 两个参数，Q 矩阵公式 `[qPos²·Δt + qVel²·Δt³/3, qVel²·Δt²/2; qVel²·Δt²/2, qVel²·Δt]` 不符合标准常速度模型。标准常速度模型只有一个过程噪声参数（加速度白噪声谱密度 q），Q 矩阵如上。若需要分别控制位置和速度的过程噪声，应使用常加速度模型（状态 = [位置, 速度, 加速度]），留待二期。

### 6.5 RTS 平滑与输出

正向滤波结束后，反向做 RTS 平滑。输出取**窗口末时刻**的平滑状态 pos 作为 `posEcef`，平滑协方差的位置块作为 `covEcef`。`time` 字段为窗口末时刻（与输出状态对应），`lastEpochTime` 同为窗口末时刻。

> **数学严谨性提示**：本模式将 RTK 前向 Kalman 滤波输出的 `SolData` 当作"伪观测"再次做 KF+RTS，属于"滤波之滤波"。前向 RTK 滤波的输出之间存在时间相关性（非独立观测），严格的 RTS 平滑需要原始观测数据。因此本模式的平滑结果**可能过度平滑**，速度估计的可信度低于位置估计。建议仅用于缓慢形变的静态监测场景，动态场景慎用。（参考 [PosFileRtsSmoother.java#L35-L44](../rtklib-core/src/main/java/org/rtklib/java/postpos/PosFileRtsSmoother.java#L35) 的同类警告。）

### 6.6 参数

```java
public class KalmanParams implements AggregationParams {
    public double q = 1e-4;               // 加速度过程噪声谱密度 (m/s²/√Hz)
    public double initVelVar = 1.0;       // 速度初始方差 (m/s)²
    public int minFixEpochs = 3;
    public int maxConsecutiveMiss = 10;   // 连续缺失保护阈值
    public boolean allowFloatFallback = false;
}
```

---

## 7. 参数与模式的关系

```java
public interface AggregationParams {
    int minFixEpochs();
    boolean allowFloatFallback();
}
```

`RobustParams` 和 `KalmanParams` 分别实现该接口。调用方传参时，模式与参数类型必须匹配，否则抛 `IllegalArgumentException`。

---

## 8. 错误处理与状态码

对象永不为 null。失败时通过 `status` 枚举区分原因：

```java
public enum AggregationStatus {
    OK,                  // 成功
    EMPTY_WINDOW,        // 输入窗口为空
    INSUFFICIENT_FIX,    // FIX 历元数 < minFixEpochs（含降级后仍不足）
    COV_UNAVAILABLE,     // 全 covEcef == null 且样本不足以估计经验协方差
    KALMAN_OBS_LOST,     // 卡尔曼连续缺失观测超过阈值
    PARAM_MISMATCH       // 模式与参数类型不匹配
}
```

调用方通过 `status` 判断失败原因，`posEcef`/`covEcef` 在非 OK 时为 null。

---

## 9. 与现有模块的关系

- 不修改 solstatic、RtkProcessor、PppProcessor；
- 输入来自 `RtkProcessor` 的 `solutions` 列表或 solstatic 窗口内的 `SolData` 列表；
- 输出可直接用于小时级上报；

### 9.1 与 rtklib-adjust 的串联

SolAggregator 做**单基线窗口内融合**，rtklib-adjust 做**多基线结果层平差**。两者可串联：

```
单基线窗口 SolData → SolAggregator → 融合 SolData（单条）
                                        ↓
多基站融合 SolData 列表 → rtklib-adjust → 网平差结果
```

具体方式：将 `AggregatedSol` 包装为 `SolData`（构造一个 `Sol` 对象，填入融合坐标和协方差），作为一条"虚拟基线"输入 adjust 的 `BaselineEpoch`。adjust 的 `CovAssembler` 会直接使用其 `covEcef`。

> 注意：串联时 SolAggregator 输出的 `covEcef`（经验协方差）比原始滤波协方差更保守，作为 adjust 的观测协方差更合理。

### 9.2 与 PosFileRtsSmoother 的关系

见 §1.1，两者定位不同，不冲突。

---

## 10. 性能约束

按**时间窗口**而非历元数约束（采样率可能变化）：

- 建议窗口 ≤ 3 小时（15s 采样 = 720 历元，1Hz 采样 = 10800 历元）；
- ROBUST 模式对每个历元做 3×3 求逆 + 最多 5 次迭代，O(N) 复杂度；
- KALMAN_SMOOTH 为 O(N) 复杂度。

超过时由调用方分窗处理。文档不内置窗口长度强制截断。

---

## 11. 可观测性指标

通过 `AggregatedSol` 字段暴露，调用方按需埋点：

- `usedEpochs` / `rejectedEpochs`；
- `windowStdEcef` 三轴；
- KALMAN_SMOOTH 模式的速度估计值（可从平滑状态中提取，作为诊断字段扩展）；
- IGG3 降权置零的历元数（含在 `rejectedEpochs` 中）。

首期不内置日志框架。

---

## 12. 待讨论项与风险提示

| 项 | 说明 | 首期决策 |
|----|------|---------|
| `includeFloat` 开关 | FLOAT 历元质量不可控，粗差风险上升 | 不实现，仅通过 `allowFloatFallback` 在 FIX 不足时降级 |
| 常加速度模型 | 常速度模型仅适合缓慢形变，高速大形变需扩展 | 二期 |
| 协方差缩放因子 | 用经验单位权方差缩放理论协方差 | 二期 |
| 完整 9 元素经验协方差 | 首期 `covEcef` 已输出完整 3×3 经验协方差，`windowStdEcef` 仅对角线开方 | 已满足 |
| KALMAN_SMOOTH 过度平滑 | "滤波之滤波"数学不严格 | 文档已警告，静态场景可用，动态慎用 |

---

## 13. 测试计划

| # | 模式 | 场景 | 验证点 |
|---|------|------|--------|
| 1 | ROBUST | 已知真值静态数据，注入 1–2 个假固定历元 | 融合结果不被污染，rejectedEpochs 正确 |
| 2 | ROBUST | 平稳时段数据 | windowStdEcef 与理论重复性一致 |
| 3 | ROBUST | 全 covEcef == null | 退化为中位数，covEcef 填经验协方差 |
| 4 | ROBUST | IGG3 迭代收敛 | 多次迭代后权重稳定，粗差被正确剔除 |
| 5 | KALMAN_SMOOTH | 人工模拟线性位移 | 平滑输出能跟上末时刻真实位移 |
| 6 | KALMAN_SMOOTH | 连续缺失观测超过 maxConsecutiveMiss | 返回 KALMAN_OBS_LOST |
| 7 | 边界 | 空窗口、FIX 不足、参数类型不匹配 | status 正确 |
| 8 | 边界 | FIX 不足 + allowFloatFallback=true | FLOAT 历元纳入融合 |
| 9 | A/B | 同一窗口 solstatic vs SolAggregator | 对比重复性和假固定抗性 |

---

## 14. 实施路线

| 阶段 | 任务 | 估计 |
|------|------|------|
| Phase 1 | 接口定义、AggregatedSol、AggregationStatus、参数类 | 0.5d |
| Phase 2 | ROBUST 模式实现 + 测试 | 1d |
| Phase 3 | KALMAN_SMOOTH 模式实现 + 测试 | 1.5d |
| Phase 4 | 边界处理、错误码、A/B 对比 | 0.5d |
| **合计** | | **3.5d** |
