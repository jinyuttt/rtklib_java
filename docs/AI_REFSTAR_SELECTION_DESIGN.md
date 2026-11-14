# AI 辅助选星（参考星污染判别）设计与评估

> 版本 **v1.1**（2026-11-14）· 基线 **v3.5.0** · 状态：**设计评审通过（有条件）· 代码未落地**
> 关联文档：[AI_ENHANCEMENT_DESIGN.md](AI_ENHANCEMENT_DESIGN.md)（接口一/三的契约与评估体例）、[GNSS-RTK-AI-report.md](GNSS-RTK-AI-report.md)（实测数据）
> v1.1 变更（不影响设计结论）：§5.4 补 `P` 的步骤 0 依赖、R2-b 收紧为「恰好第 2 名」、终止阈值改为双阈值（事件数 < 50 或比例 < 1%）、新增文献引用纪律 C1–C4。详见文末变更记录。

| 项 | 内容 |
|---|---|
| 接口编号 | **接口四**（接口二已冻结，不复用其编号） |
| 设计 | 定稿（**v1.1**，含 5 处硬伤修正 + 4 处判据补强） |
| 实现 | **未落地**（本文只给设计，不含代码） |
| 生效条件 | 未注入即关闭（无 `AiProvider`）；IFLC 模式下不调用 |
| 交付形态 | **实验 provider**，默认关、仅显式注入，**不承担生产责任** |
| 决策 | **D20**（见 §8）：第一阶段只验「无害 + 存在性」，收益留待城市数据 |
| 前置门槛 | **步骤 0**（`is_ref` trace + 重跑采集）：第 1 步事件数、R1-6、§5.4 的 `P` 都依赖它；未做则相关判据一律标 `unverifiable` |

---

## 0. 摘要

### 0.1 一句话结论

**方向成立、架构兼容、可以立项；但原方案若原样实施，会得到「看起来成功、其实是假结论」的结果。** 修正 5 处（其中 2 处致命）后判定为**有条件通过**。

### 0.2 判定表

| 维度 | 判定 | 依据 |
|---|---|---|
| 与现有 SPI 架构的兼容性 | **通过** | 与接口三同构：`resolve → null 即不干预`，`rsv == null` 时 core 分支完全不存在（§3.5） |
| 语义独立性（不塞进 `ObsCorrector`） | **通过** | 接口三改「观测值」，本接口改「选哪颗当参考」，降级语义不同（§1.2） |
| 特征复用 | **不通过，须改** | `resp`/`resc` 对参考星**恒为 0**，直接复用等于标签泄漏（H1） |
| core 施加点 | **位置正确，写法须改** | 每历元 2~3 次 `ddres`，模型调用必须在循环外并加幂等门闩（H2/H3） |
| 训练管线复用 | **通过，但缺前置步骤** | 现有采集 CSV 无「谁当参考星」字段，必须新增 trace 并重跑采集（H5） |
| 「零新数据」 | **不成立** | 原始观测不新增，但**采集必须重跑**（H5） |
| 成本估算「1–2 周」 | **仅在 ≥4 路并行时成立** | 576 run/组 × 4 组 ≈ 24 天串行（§6.4） |
| 交付形态 | **与项目体例一致** | 与方案 B 端到端（§8.4.5 / D19）的处置同构：实验 provider、默认关 |
| **阈值可标定**（v1.1） | **依赖步骤 0，否则不可验** | `P` 的分母是「参考星历元」，无 `is_ref` trace 则不可计算 → R1-6 标 `unverifiable`（§5.4.1） |
| **判据可证伪性**（v1.1） | **通过（已收紧）** | R2-b 断言唯一（恰好第 2 名）、终止阈值双条件（事件数 < 50 或比例 < 1%），均可被实现错误直接证伪（§6.2 / §5.2） |
| **文献引用**（v1.1） | **通过（已收紧）** | 设计不依赖任何外部文献；不可核验引用降级为「背景参考 / 待验证假设」（附：文献引用纪律 C1–C4） |

### 0.3 术语定死

本文档出现三个易混概念，先在此定死，后文一律沿用：

| 术语 | 含义 | 本接口是否改动 |
|---|---|---|
| **选星**（`selsat`） | 决定**哪些卫星参与解算**（按高度角/系统过滤） | **不改** |
| **参考星**（`refIdx`） | `ddres` 内为每个 `(系统 m, 码/相位 f)` 组独立选出的「被减掉的那颗」 | **改**（仅在风险超阈值时换） |
| **风险分**（`refStarRisk`） | 该星作为参考星时，**随后 W 历元内出现锁相丢失/周跳**的预测风险 ∈ [0,1] | 新增输出 |

**命名诚实性要求**：风险分训练自锁相事件弱标签（§5.2），学到的是**滑相前兆**，不是 NLOS 判别。因此：

- 接口方法名用 `refStarRisk(...)`，**不用** `contaminationProbability(...)`；
- 产物文件名 `refstar_sliprisk.onnx`，不用 `refstar_contamination.onnx`；
- 报告与文档**禁止**出现「NLOS 判别准确率」「多路径识别准确率」字样（§6.3 已禁「污染判断准确率」，命名不得先把话说满）。

---

## 1. 定位与边界

### 1.1 是什么

在 `RtkCore.relpos` 的**参考星择优之后、双差行组装之前**增加一个可插拔的判别点：若模型判定**当前参考星风险超阈值**，则回退到次优候选，其余历元**完全不干预**。

设计逻辑是「只否决、不优选」——不是「AI 觉得另一颗更好就换」，而是「当前参考星被判定为风险超阈才换」。

> **文献引用纪律（v1.1 收紧）**：原方案援引的「2026 年 ScienceDirect XGBoost 在线参考星优选」工作，**当前无可核验标识（标题 / 作者 / DOI），因此不得作为本设计的论据**。本文档的判定依据一律来自 §2 的代码事实与项目内既有结论（§7.1 / §7.3 / §7.5 / D17 / D19），不依赖任何外部文献。若后续补到可核验标识，只能作为「背景参考」写入文末附录，**不得用于支撑设计选择或验收判据**。详见文末 [附：文献引用纪律](#附文献引用纪律)。

### 1.2 为什么必须是独立接口，不能复用接口三

| 维度 | 接口三 `ObsCorrector` | 接口四 `RefStarValidator` |
|---|---|---|
| 改什么 | 观测值（`bias` + `varianceScale`） | 「选哪颗当参考」 |
| 降级语义 | `bias = 0` / `scale = 1`（等价不改） | **全部不否决**（等价不换） |
| 失败伤害 | 方差膨胀过头 → 固定率下降 | 误换参考星 → **破坏双差自洽性** |
| 索引空间 | `idx = i * nf + frq`（按 `sat[]` 下标） | 按 `(m, f)` 组，每组一个参考星 |

混进一个 SPI 会让两种降级语义纠缠：接口三降级是「不改正」，接口四降级是「不否决」，两者既不该共享阈值，也不该共享 trace 主题。

### 1.3 不是什么

- **不是求解器替换**，不改 LAMBDA、不改法方程、不产生协方差。
- **不是 `selsat` 替换**：不增删参与解算的卫星（§1.1 术语表）。
- **不是交付功能**：默认关、仅显式注入，与 D19 对方案 B 的处置同构。
- **不证明 AI 比 RTKLIB 选得准**：只证明「链路跑通、判定无害、确实在起作用」。

---

## 2. 现有代码事实核对

这一节是本设计的全部依据。**每一条都与原方案的假设有出入或需要确认**。

| # | 代码事实 | 证据 | 对原方案的影响 |
|---|---|---|---|
| F1 | `AiProvider` 只有两个方法，`AiHooks` 用 `ThreadLocal` + 「实例字段优先」解析 | [AiProvider.java](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ai/spi/AiProvider.java#L14-L18)、[AiHooks.java#L57-L79](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ai/spi/AiHooks.java#L57-L79) | 新增方法可直接照抄 resolve 模式，**成立** |
| F2 | **参考星不是单数**：`m` 遍历 6 个系统、`f` 遍历 `0..nf*2-1`（码/相位分开），每个 `(m,f)` 组独立择优 | [RtkCore.java#L1955-L1996](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1955-L1996) | nf=2 时每历元最多 **12 组**决策；切换状态、计数、报告必须按组维护 |
| F3 | **`resp`/`resc` 对参考星恒为 0**（契约明写，代码侧只对非参考星回填） | [SatFeature.java#L15-L16](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ai/spi/SatFeature.java#L15-L16)、[RtkCore.java#L2124-L2128](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L2124-L2128) | **致命**：原方案「复用 17 维」= 泄漏（H1） |
| F4 | 上下文 `sats()` 是**过滤后的子集**（剔除流动站无几何的退化行），长度 ≠ `ns`；接口三用 `rowSat[]` 反查回 `sat[]` 下标 | [RtkCore.java#L1767-L1800](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1767-L1800) | 原方案 `contamination[refIdx]` **下标空间错误**（H2） |
| F5 | `ddres` 每历元被调用 2~3 次（`opt.niter` 迭代）；接口三的模型调用在循环外、`i == 0` 一次性 | [RtkCore.java#L360-L367](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L360-L367) | 切换决策若写在 `ddres` 内会被执行 2~3 次（H3） |
| F6 | IFLC 门禁在 `relpos` 调用点（`opt.ionoopt != IONOOPT_IFLC` 才 resolve），**`AiHooks` 内没有任何 IFLC 判断** | [RtkCore.java#L265-L271](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L265-L271)、[AiHooks.java](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ai/spi/AiHooks.java#L1-L86) | 原方案「AiHooks 内统一处理」是**事实错误**，须照抄接口三写法 |
| F7 | `AiOpts` 无 `enable` 字段，阈值改动不需换模型，`validate()` 惰性钳位不抛异常 | [AiOpts.java#L9-L27](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/config/AiOpts.java#L9-L27) | 新增阈值字段照此体例，**成立** |
| F8 | 现有采集 CSV 列为 `run_id, station, day, epoch, sat, frq, f0..f16`；`rtklib-ai` 全目录 grep `refIdx / is_ref / refstar` **零命中** | [assemble.py#L78-L79](file:///D:/rtklib/rtklib_java/rtklib-ai/training/obs_corrector/assemble.py#L78-L79) | 弱标签正例定义**无字段可依**，必须新增 trace 并重跑采集（H5） |
| F9 | 现有评估体例：四组 A/B/C/D、两层切分、两阶段验收（§7.1 / §7.3 / §7.5） | [AI_ENHANCEMENT_DESIGN.md#L1028-L1067](file:///D:/rtklib/rtklib_java/docs/AI_ENHANCEMENT_DESIGN.md#L1028-L1067) | 原方案的组命名与 D 组语义**与既有报告冲突**（§6.1） |
| F10 | 单轮全量 576 run ≈ 15 min/run | [AI_ENHANCEMENT_DESIGN.md#L1660](file:///D:/rtklib/rtklib_java/docs/AI_ENHANCEMENT_DESIGN.md#L1660) | 四组串行 ≈ 24 天（§6.4） |

---

## 3. 架构设计（修正后）

### 3.1 SPI

```java
// AiProvider 新增第三个方法，与 fixValidator() / obsCorrector() 并列
RefStarValidator refStarValidator();   // 返回 null = 不提供

public interface RefStarValidator {
    /**
     * 批量：对 ctx.sats() 中每颗卫星输出「作为参考星的风险分」∈ [0,1]，
     * 行序必须与 ctx.sats() 一致。风险越高 = 越不适合当参考星。
     * 返回 null 或长度不符 → 降级：全部按 0.0（等价不干预）。
     */
    double[] refStarRisk(RefStarContext ctx);
}

public record RefStarContext(
        int epoch, int ns, int nf, double dt,
        SatFeature[] sats,      // 复用 §4.4.3 的 SatFeature，但 resp/resc 语义见 §5.1
        double[] snrWinMean,    // 新增：snrRover 近 5 历元窗口均值（按 sats 行序、按主频点）
        double[] snrWinStd,     // 新增：snrRover 近 5 历元窗口标准差
        int[] refCount) {}      // 新增：该星在近 W 历元内被选为参考星的次数（0..W）
```

**为什么 `refCount` 必给**：模型必须能区分「这颗星以前从没当过参考星」与「它当参考星时一直很稳」。没有这一维，模型只能用 `resp == 0` 去反推「它当过参考星」——那正是 H1 要消灭的捷径。

### 3.2 `AiHooks.resolve`

```java
public static RefStarValidator resolveRefStarValidator(Rtk rtk) {
    if (rtk != null) {
        validateAiOpts(rtk);
        if (rtk.aiRefStarValidator != null) return rtk.aiRefStarValidator;
    }
    AiProvider p = TL.get();
    return p == null ? null : p.refStarValidator();
}
```

逐字照抄 [AiHooks.java#L57-L79](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ai/spi/AiHooks.java#L57-L79) 的既有模式，不引入新机制。

### 3.3 core 施加点

**位置**：`relpos` 循环外（与接口三并列），**不在 `ddres` 内**：

```java
// relpos，selsat 之后
RefStarValidator aiRefStar = (opt.ionoopt != Constants.IONOOPT_IFLC)
        ? AiHooks.resolveRefStarValidator(rtk) : null;
double[] aiRefRisk = (aiRefStar != null) ? new double[ns] : null;
int[] aiRefOverride = (aiRefStar != null) ? new int[6 * nf * 2] : null;  // -1 = 不换
boolean aiRefDone = false;
```

```java
// 迭代首轮 zdres(rover) 之后、首次 ddres 之前（与接口三同一行位置）
if (i == 0 && aiRefStar != null && !aiRefDone) {
    aiRefDone = true;
    aiComputeRefStarRisk(rtk, obs, sat, iu, ir, ns, nf, azel, aiRefRisk, aiRefStar, cfg, cb);
    aiDecideRefStarSwitch(rtk, azel, iu, ir, y, sat, ns, nf, aiRefRisk, aiRefOverride, cfg, cb);
}
```

**`aiDecideRefStarSwitch` 必须复刻原生择优循环**，逐 `(m, f)` 组：用与 [RtkCore.java#L1955-L1996](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1955-L1996) **完全相同**的评分函数（含 `enableSnrMedian` 加权分支）选出原始 `refIdx`（= 评分第 1 名），再判断是否需要换。

**切换规则（v1.1 定死为「只降一级，不级联」）**：

```
if risk[refIdx] <= refStarRiskMax:        override = -1            // 不换
else if 第 2 名在候选池内 且 el降幅 <= refStarMaxElDrop:
                                          override = 第 2 名        // 降一级
else:                                     override = -1            // 保持原参考星，禁止顺延到第 3 名
```

三条不得妥协：

1. **只降一级**：第 2 名被门槛拒绝时**直接放弃**，不试第 3 名。级联降级会让几何损失不可控，也使 R2-b 失去唯一断言。
2. **原始 refIdx 必须按「未切换」的原生评分排序**，不能用上一历元的 `override` 结果作为本次的排序基准——否则错误会逐历元累积。
3. **同组迟滞**：`refStarHoldEpochs` 内不再进入上述判定，直接 `override = -1`，并写 `AI4 / AI_REFSTAR_HOLD`。

**`ddres` 内只做一次只读查表**（参考星已选出、双差行组装之前）：

```java
int groupKey = m * (nf * 2) + f;
if (aiRefOverride != null) {
    int ov = aiRefOverride[groupKey];
    if (ov >= 0 && ov < ns && validobs(iu[ov], ir[ov], f, nf, y)
            && (rtk.ssat[sat[ov] - 1].slip[frq] & Constants.LLI_SLIP) == 0) {
        refIdx = ov;
    }
}
```

**这个 `aiRefOverride` 缓存的三个作用**（原方案没有）：

1. **幂等**：同一历元 3 次 `ddres` 读到同一张表 → 结果逐位一致（R2-c）；
2. **一致**：码组与相位组各自独立决策，不会因迭代次数不同而漂移；
3. **可回滚**：override 失效（`validobs` 不再成立或该星周跳）时自动退回原生 `refIdx`，不需要额外的撤销逻辑。

### 3.4 `AiOpts` 新增字段

| 字段 | 默认值 | 语义 |
|---|---|---|
| `refStarRiskMax` | **0.8** | 风险分超过该值才触发切换。**不用 0.5**：误换的代价（破坏双差自洽）远高于漏判 |
| `refStarHoldEpochs` | **15** | 同一 `(m,f)` 组切换后 N 历元内禁止再切（H3 迟滞） |
| `refStarFallbackTopK` | **3** | fallback **候选池**的上限（原生评分前 K 名）。**注意：池大小不等于选中规则**——实际只允许降一级到第 2 名，见 §3.3 / R2-b（v1.1 收紧） |
| `refStarMaxElDrop` | **15.0**（度） | fallback 相对原参考星的最大高度角降幅（H4） |
| `refStarSwitchRateMax` | **0.02** | 单 run 内某组切换历元占比上限；超限即判「模型过判」，停止该组干预并写 trace |
| `refStarWindowEpochs` | **5** | 风险标签与 `snrWinMean/std` 的窗口长度 W |

`validate()` 按现有体例惰性钳位：`refStarRiskMax ∈ [0,1]`、`refStarHoldEpochs ≥ 0`、`refStarFallbackTopK ≥ 1`、`refStarMaxElDrop ∈ [0,90]`、`refStarSwitchRateMax ∈ [0,1]`。

### 3.5 零回归不变量（I-1）

| 条件 | 保证 |
|---|---|
| 未注入（`resolve` 返回 null） | `aiRefRisk` / `aiRefOverride` 均为 `null`，`ddres` 内的查表分支**整体不存在**；行为与 v3.5.0 **逐位一致** |
| 注入但输出全 0.0 | 所有组 `risk ≤ 阈值` → `override` 全 `-1` → 参考星与原生选择一致（R2-a） |
| `ionoopt == IONOOPT_IFLC` | 不 `resolve`、不调用，等价未注入 |
| 模型抛异常 / 返回 null / 长度不符 | 降级为全 0.0 并写 `AI4 / AI_DEGRADE`（绝不 fail-closed） |

**为什么「全 0.0 必须逐位一致」是可证的**：`aiRefOverride` 只在 `risk > refStarRiskMax` 时被写成非 -1，其余恒 -1；查表分支只在非 -1 时改写 `refIdx`。故全 0.0 输入下，参考星选择路径与无注入路径**在指令级等价**。

### 3.6 trace 与可观测性

新增主题 `AI4`：

| 事件 | 字段 |
|---|---|
| `AI4 / REFSTAR_RISK` | `epoch`、逐星 `sat, risk, wasRef, refCount`（可选，评估期开） |
| `AI4 / AI_REFSTAR_SWITCH` | `epoch`、`group=(m,f)`、`orig=原参考星`、`fallback=新参考星`、`risk`、`elDrop`、`topK` |
| `AI4 / AI_REFSTAR_HOLD` | 触发但被迟滞拦下的记录（用于区分「没判」与「判了但压住」） |
| `AI4 / AI_REFSTAR_RATE_STOP` | 该组切换率超 `refStarSwitchRateMax`，本 run 内停止该组干预 |
| `AI4 / AI_DEGRADE` | 降级原因（`null` / `length_mismatch` / `exception` / `non_finite`） |

---

## 4. 五处硬伤与修正

### H1（致命）`resp`/`resc` 对参考星恒 0 → 标签泄漏

**症状**：`SatFeature` 契约明写「参考星恒 0」（[SatFeature.java#L15-L16](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ai/spi/SatFeature.java#L15-L16)）；代码侧只有非参考星被回填（[RtkCore.java#L2124-L2128](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L2124-L2128)）。

**后果链**：正例（参考星）`resp = resc = 0` 由构造决定 **100% 恒零**，负例绝大多数非零 → 模型不需要学任何污染特征，只要学「`resp == 0` ⇒ 高风险」就能拿到接近 1 的 AUC → 所有参考星被判高风险 → 每历元切换 → 参考星振荡 → **R4 存在性轻松通过、R3 无害性必然失败**，而会被误读成「数据里污染事件多」。

**修正**（四条，缺一不可）：

1. 参考星判别特征集**剔除 `resp`/`resc`**。可用维：`snrRover`/`snrBase`、`lock`、`slip`、`half`、`gf`、`el`、`az`、`vsat`、`fix` + 新增 SNR 窗口 2 维 + `refCount`。
2. 加 **`wasRef` / `respMissing` 掩码**，让模型知道「残差为 0 是缺失，不是干净」。
3. **伪相关探测**（必须写进 R1）：用「含残差」与「去残差」两套特征各训一次。若 AUC 从 ~1 掉到 ~0.5，即证明原信号全在缺失值模式上。
4. R1 硬断言：喂「全部参考星、全部干净」的构造输入，输出不得恒为 1。

### H2（严重）`contamination[refIdx]` 下标空间错误

**症状**：`refIdx` 是 `sat[]` 下标；`ctx.sats()` 是按 `el >= elmin` 过滤后的子集（[RtkCore.java#L1767-L1800](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1767-L1800) 的 `rowSat` 机制）。两者长度与下标空间都不同。

**后果**：不抛异常，只是静默取到相邻星的风险分——正是 A1-4「行序对齐」要抓的那类静默错误。

**修正**：`refStarRisk` 返回值按 `ctx.sats()` 顺序；core 用 `rowSat[]` 反填成 `sat[]` 索引的 `aiRefRisk[ns]`。与接口三 `aiBias` 的映射口径**完全一致**，不得另起一套。

### H3（严重）逐历元独立判定 + 无迟滞 = 参考星振荡

**症状**：无记忆二值控制器。两颗星风险都在 0.8 附近时，必然 A→B→A→B 逐历元反复切换；且若决策写在 `ddres` 内，会被 2~3 次迭代重复执行。

**修正**：`refStarHoldEpochs`（最小驻留）+ `refStarSwitchRateMax`（过判检测器）+ `aiRefOverride` 当历元缓存（幂等）。三条同时生效。

### H4（重要）「不损失几何强度」不成立

**症状**：原方案说「fallback 不从星座中剔除卫星，其余照常参与双差」——只解决了「卫星数不变」，没解决**参考星参与该组全部 DD 对**。把参考星从 80° 换到 25°，所有 DD 行方差按 `varerr(eli)` 抬升、DD 协方差条件数恶化，固定率下降与污染无关。

**修正（v1.1 收紧为「只降一级」）**：fallback **只允许取原生评分第 2 名**（第 1 名即原参考星），且须同时满足：

1. 第 2 名落在候选池内（`refStarFallbackTopK`）；
2. 相对原参考星的高度角降幅 ≤ `refStarMaxElDrop`。

**任一条不满足则保持原参考星（等价不干预），严禁顺延到第 3 名及更后**。理由：级联降级会让几何损失不可控（第 2 名被门槛拒绝往往意味着整组几何已退化，此时任何更远的候选只会更差），且使 R2-b 无法给出唯一断言。

候选评分函数必须复用原生（含 `snrMedian` 加权分支），否则 `enableSnrMedian` 开启时两套选星口径冲突。

### H5（重要）「零新数据」不成立：现有采集没有「谁当参考星」

**症状**：现有 collect CSV 只有 `run_id, station, day, epoch, sat, frq, f0..f16`（[assemble.py#L78-L79](file:///D:/rtklib/rtklib_java/rtklib-ai/training/obs_corrector/assemble.py#L78-L79)）；`rtklib-ai` 全目录无 `refIdx / is_ref / refstar` 命中。

**不可反推**：文档自认「未配对 1,340,500 均为『未参与双差 / **参考星** / 被 maxinno 剔除』的正常来源」（[§8.2.1](file:///D:/rtklib/rtklib_java/docs/AI_ENHANCEMENT_DESIGN.md#L1577)），这三类在数据里**不可区分**，而参考星恰恰是 `resp = resc = 0` 的那一类——与「没参与双差」完全同形。

**修正**：core 侧新增 `is_ref` trace 字段（按 `epoch, sat, frq, code/phase`），**重跑采集**。原始观测数据不新增，但采集必须重跑，否则执行顺序第 1 步（生成弱标签）直接卡死。

---

## 5. 特征与训练

### 5.1 特征集

| 组 | 维度 | 内容 |
|---|---|---|
| 基础 | 12 | `el`、`az`、`snrRover`、`snrBase`、`lock`、`slip`、`half`、`gf`、`vsat`、`fix`、`sys`（one-hot 压缩为 1 维系统码）、`wasRef` 掩码 |
| 时序窗口（新增） | 2 | `snrWinMean`、`snrWinStd`（`snrRover` 近 5 历元） |
| 参考星历史（新增） | 1 | `refCount`（近 W 历元内被选为参考星的次数 / W） |
| **禁用** | — | **`resp`、`resc`**（H1） |

窗口逻辑复用 [ObsCorrectionFeatures.java](file:///D:/rtklib/rtklib_java/rtklib-ai/src/main/java/org/rtklib/java/ai/feature/ObsCorrectionFeatures.java) 的 `WINDOW = 5` 环形缓冲与缺口重置规则（间隔 > 1.5*dt 清空），**但不改接口三的 17 维定义**：两个 provider 各有自己的特征提取器，互不影响。

**为什么补窗口方差**：窗口均值 + 方差是原方案唯一值得采纳的补充维度，但**理由必须落在本工程内可验的事实上，不能靠外部文献**：

1. 接口三的 `ObsCorrectionFeatures` 已证明「5 历元窗口 + 缺口重置」这套机制在本项目数据上可跑通（[ObsCorrectionFeatures.java](file:///D:/rtklib/rtklib_java/rtklib-ai/src/main/java/org/rtklib/java/ai/feature/ObsCorrectionFeatures.java)），复用无新增风险；
2. 均值抹掉波动、方差保留波动——这是统计量的定义性质，不需要文献支撑；
3. 「仅用均值会失效」这一物理主张**本文档不作为论据**，而是列为**待验证假设**：若 R1-4 伪相关探测显示窗口维贡献接近零，则删掉这两维重训，不为其寻找外部理由。

> 原方案在此处援引的「有研究指出 NLOS 的 SNR 方差显著高于 LOS」属**不可核验引用**，按 §1.1 的引用纪律不得作为设计依据。若需保留该物理动机，须补可核验标识后写入文末附录。

### 5.2 弱标签（方案 A：锁相事件）

| 类别 | 定义 |
|---|---|
| **正例**（污染） | 某星在历元 `t` 被选为参考星，且在 `t+1 ~ t+W` 内发生 `lock` 丢失或 `slip != 0` |
| **负例**（干净） | 同一站同一日，该星**同样被选为参考星**，且前后 W 历元 `lock` 稳定、无 `slip` |

**硬性要求**：负例必须**显式断言为「被选为参考星」**。若 `assemble.py` 自然地把「非参考星行」也当负例，正负例就在「是否参考星」这一维上分离了，等于把 H1 的泄漏再引入一遍。

**方案 B（双差残差形态）只做交叉验证**，不作主标签：残差本身可能已被参考星污染，属循环论证。

**前置决策点（v1.1 双阈值，任一触发即终止）**：

| 条件 | 阈值 | 为什么是这个阈值 |
|---|---|---|
| 正例比例（按参考星历元） | **< 1%** | 原方案口径保留。比例过低说明「参考星滑相」不是本数据的可观测现象 |
| **正例事件数**（按 `(station, day, sat, event)` 聚类后计数） | **< 50** | **v1.1 新增，且是主判据**。行数会骗人：一段连续污染在 5 历元窗口下能膨胀成几十行，但只算 **1 个事件**。50 是「能撑起按事件聚类 bootstrap 的最小样本量」——少于 50 个事件，置信区间宽度会超过任何判据的容差（§5.3 要求按事件聚类），训练出的模型也无法跨站泛化 |

**判定写法**：`事件数 < 50` **或** `比例 < 1%` → 写决策记录「本数据场景下参考星污染不是主要误差源，需城市数据后重开」，管线终止。这不是失败，是正确结论。

**两条都必须在步骤 0（`is_ref` trace + 重跑采集）之后才可计算**——见 §5.4 的同一依赖。

### 5.3 切分与训练

- **两层切分照用**（[§7.3](file:///D:/rtklib/rtklib_java/docs/AI_ENHANCEMENT_DESIGN.md#L1049-L1067)）：不同 `station` 进不同集合 + 站内按 `day` 切分。**禁止随机切分**。
- 模型：MLP（与接口三一致），入 `[batch, 15]`，出 `[batch, 1]`（sigmoid）。
- 目标：二分类交叉熵。**不追求准确率**——弱标签下准确率无意义；目标是「输出单调的风险分」，不是「拟合弱标签噪声」。
- **置信区间必须按事件聚类 bootstrap**：相邻历元标签高度重叠（`t` 与 `t+1` 的正例大概率是同一段连续污染），行级 bootstrap 会给出虚假地窄的区间。按 `(station, day, sat, event)` 聚类。
- 产物：`refstar_sliprisk.onnx` / `.norm.json` / `.metrics.json`，`metrics.json` 必须标注：

```json
{"label_source": "lock_event_weak",
 "note": "弱标签仅供管线跑通，有效性待参考系统数据",
 "features_excluded": ["resp", "resc"],
 "pseudo_correlation_probe": {"auc_with_resid": 0.0, "auc_without_resid": 0.0}}
```

### 5.4 阈值标定（不许硬编码了事）

#### 5.4.1 前置依赖：`P` 的可测性完全取决于步骤 0

**这是 v1.1 补强的第一处，与 H5 直接联动。**

`P` 的定义是「**参考星**在随后 W 历元内滑相的发生率」，分母是**参考星历元**，不是全部卫星行。因此：

| 状态 | 后果 |
|---|---|
| 步骤 0 未执行（无 `is_ref` trace） | 数据里**无法识别哪些行是参考星**（H5：参考星与「未参与双差」在 `resp = resc = 0` 上完全同形）→ `P` **不可计算** → R1-6 **不可验证** → `refStarRiskMax = 0.8` 退化为**纯拍脑袋的魔数**，H1 的过判症状**无法被门禁拦住** |
| 步骤 0 已执行 | `P` 可计算，R1-6 有效，阈值有标定证据 |

**因此本节的执行前置是「步骤 0 完成」，不是「模型训练完成」**。若有人跳过步骤 0 直接训模型，本节的 R1-6 必须标为 **`unverifiable`**，且**不得进入第一阶段验收**——这一条要写进报告，不许静默跳过。

#### 5.4.2 `P` 的精确定义

在**步骤 0 重跑采集后的 A 组（无干预）数据**上，逐 `(m, f)` 组统计：

```
P = |{ 参考星历元 e : 该组在 e 的参考星 s，在 e+1..e+W 内出现 lock 丢失或 slip != 0 }|
    / |{ 参考星历元 e }|
```

四条口径约束（缺一条 `P` 就不可比）：

1. **分母是参考星历元，不是卫星行**——否则会系统性稀释（每历元只有 1 颗参考星，却有 ns 颗星）；
2. **按 `(m, f)` 组分别统计后再合并**，不用「全星座合并」——各组参考星互相独立，合并会掩盖某组的高发率；
3. **W、滑相判定（`lock` 丢失 / `slip != 0`）必须与 §5.2 标签定义逐字一致**——口径不一致时 `P` 与模型输出不可比；
4. **按站分别报告**，并给合并值——站间差异大时（多路径是站址物理属性），单一 `P` 会掩盖异质性。

#### 5.4.3 判据

> 模型输出 > `refStarRiskMax` 的**参考星历元占比** ≤ `3 × P`

**为什么 `3 × P` 而不是别的**：模型在弱标签下必然有假阳性（标签本身不可靠），故不能要求「与 `P` 相等」；但 3 倍已是宽容上界——超过 3 倍即说明模型在**整体过判**（H1 的典型症状：把所有参考星都判高风险），此时无论切换次数多少，R3 无害性必挂。

**两个必须写死的边界**：

| 边界 | 规则 |
|---|---|
| 若第 1 步通过（事件数 ≥ 50），则 `P > 0` | `3 × P` 良定义，判据有效。这是 §5.2 与本节**唯一的顺序依赖**：先过 §5.2 的 50 事件门槛，才轮得到本节 |
| 若 `P == 0`（理论上只在跳过 §5.2 时出现） | `3 × P = 0` → 判据退化为「超阈次数必须为 0」。此时**不判通过**，按 §5.4.1 标 `unverifiable` 并终止，不许用「0 次超阈」冒充达标 |

**超阈占比必须与 `refStarSwitchRateMax` 一起看**：前者是「模型说该换的比例」，后者是「实际换成的比例」（受迟滞与几何门槛削减）。两者差距过大说明门禁在大量吞掉模型的判定，需在报告中解释，不许只报切换次数。

---

## 6. 评估协议

### 6.1 组定义（与既有体例对齐）

| 组 | 配置 | 用途 |
|---|---|---|
| **A** | 不注入 | 基线 |
| **B** | 仅接口一 | **本轮不参与**（既有数据复用即可） |
| **C** | 仅接口三 | 已有 |
| **R** | 仅接口四 | 参考星判别的独立效应 |
| **D** | 接口四 + 接口三 | 耦合验证（**不可省**） |
| **E** | 接口四 + 接口三 + 接口一 | 三接口叠加（可选，第二阶段再做） |

**为什么不能沿用原方案的 D = R + C**：[§7.1](file:///D:/rtklib/rtklib_java/docs/AI_ENHANCEMENT_DESIGN.md#L1028-L1037) 的 D 组已定义为「接口一 + 接口三」，历史报告全部按该口径写。重定义会让读者无法对齐历史数据。报告表头必须显式声明「B 组不参与本轮」。

**为什么 D 组不可省**：参考星切换会改变 `ddres` 的 `refIdx`，进而改变接口三 `v -= aiBias[refIdx] - aiBias[j]` 中的参考星项（[RtkCore.java#L2100](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L2100)）。

### 6.2 判据（R1–R4）

#### R1 契约检查（不看效果）

| # | 检查项 |
|---|---|
| R1-1 | ONNX 加载、batch 探针、降级路径（`null` / 长度不符 / `NaN` / 抛异常 → 全 0.0 且写 `AI_DEGRADE`） |
| R1-2 | IFLC 门禁：`ionoopt == IONOOPT_IFLC` 时不调用，行为等价未注入 |
| R1-3 | 行序对齐：`refStarRisk` 按 `ctx.sats()` 顺序，`rowSat` 反填后 `aiRefRisk[sat[]下标]` 逐星对账 |
| R1-4 | **伪相关探测**：去残差特征后 AUC 须显著下降（否则特征重做） |
| R1-5 | **构造输入断言**：全部参考星且全部干净时，输出不得恒为 1 |
| R1-6 | 阈值标定：风险超阈的参考星历元占比 ≤ 3 × 基线发生率 `P`（§5.4.3）。**步骤 0 未执行时本项标 `unverifiable`，不得进入第一阶段验收**（§5.4.1） |

#### R2 零基线 / 极值 / 幂等（三条，取代原方案单条）

| # | 内容 |
|---|---|
| R2-a | 输出全 0.0 → 切换次数 = 0，且参考星序列与 A 组**逐历元一致** |
| R2-b | 输出全 1.0 → fallback 必须**恰好是原生评分第 2 名**（第 1 名即原参考星），**不得是第 3 名或更后**；若第 2 名违反几何门槛（`el` 降幅 > `refStarMaxElDrop`），则**切换次数必须为 0**（**不许顺延到第 3 名**）。且全程不得振荡（验证 H3/H4） |
| R2-c | 幂等：同一历元 3 次 `ddres` 调用，切换结果逐位一致（验证 `aiRefOverride`） |

**R2-b 为什么必须是「恰好第 2 名」而不是「落在前 K 名」（v1.1 收紧）**：

| 写法 | 缺陷 |
|---|---|
| 旧：落在前 K 名（K=3） | ① 无法区分「几何门槛正确拒绝 → 不切换」与「代码错误地顺延到第 3 名」——两种行为都满足断言；② K 是 `AiOpts` 可配字段，断言随配置漂移，不可复现；③ 全 1.0 输入下所有候选都超阈，正确行为只有一种（取次优），「前 K」把唯一正确答案放宽成了 K 种 |
| 新：恰好第 2 名 + 门槛拒绝则 0 次切换 | ① 断言唯一、可复现、与 `refStarFallbackTopK` 的取值无关（K 只决定「允许候选池」，不决定「选中谁」）；② 把「拒绝」与「顺延」这两种语义**显式分离**，代码若写成「循环找第一个合格候选」会立刻被这条断言抓住；③ 与 §3.3 的 `aiDecideRefStarSwitch` 实现口径一致：**只允许降一级，不允许级联降级** |

原方案的 R2「全 0.0 → 0 次切换」是阈值逻辑的必然结果，单独测不出东西，故拆为三条。

#### R3 无害性（R 组 vs A 组，同历元配对）

**必须四条全给**，照 [§7.5.2 A2](file:///D:/rtklib/rtklib_java/docs/AI_ENHANCEMENT_DESIGN.md#L1185-L1200) 的完整口径，不能只抄前两条：

| # | 指标 | 阈值 |
|---|---|---|
| R3-1 | 固定率 | 绝对下降 ≤ **1.0 pp** |
| R3-2 | 错误固定率 | **不上升**（单侧，零容差） |
| R3-3 | 位置 RMS（水平/垂直分别比） | 相对恶化 ≤ 5%，**或**绝对增量 ≤ 1 cm（满足其一） |
| R3-4 | `ns` / `vsat` | 不显著减少 |

**R3-4 对本接口尤其关键**：换参考星会改变 `validobs` 与 `maxinno` 的剔除路径（[RtkCore.java#L2130-L2160](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L2130-L2160)），`ns` 掉一颗就是几何直接损失。

#### R4 存在性（分层，不用单一「切换次数 > 0」）

| 项 | 内容 |
|---|---|
| R4-a | per-`(m,f)` 组的切换计数（nf=2 时最多 12 列） |
| R4-b | 切换涉及的卫星对（orig → fallback） |
| R4-c | 切换后该组 DD 行数变化（`cntBefore` → `cntAfter`） |

单一「切换次数 > 0」太容易被 1 次伪切换满足（尤其 H1 未修时）。**R4 结果为 0 时写「本数据未触发，正确结论」，不视为失败。**

### 6.3 不能验的三件事

| 项 | 为什么不能验 |
|---|---|
| 污染判断准确率 | 弱标签本身不可靠，用它训练的模型判断准确率是循环论证 |
| 切换后 RMS 改善 | 静态山谷数据里参考星污染事件大概率极少，切换次数可能为零，没有可检测的改善效应 |
| 「AI 比 RTKLIB 选得准」 | 同上，且与交付形态不符（本接口只否决、不优选） |

### 6.4 成本与降采样

| 口径 | 数字 |
|---|---|
| 单 run | ≈ 15 min（[§8.9.4](file:///D:/rtklib/rtklib_java/docs/AI_ENHANCEMENT_DESIGN.md#L1660)） |
| 全量 576 run/组 | ≈ 144 h ≈ 6 天 |
| 四组（A/R/C/D）串行 | ≈ **24 天** |
| 原方案「1–2 周」 | 仅 ≥ 4 路并行时成立 |

**第一阶段降采样（建议）**：2 站 × 2 日 × 24 h = **96 run/组**，四组共 384 run，串行约 4 天。先看切换次数：

- 切换次数 = 0 → 按本方案自己的口径「正确结论」收口，省掉 3 组 × 480 run；
- 切换次数 > 0 → 再上全量 576 run/组。

---

## 7. 执行顺序

| 步 | 内容 | 产出 / 决策点 |
|---|---|---|
| **0**（新增） | core 加 `is_ref` trace 字段（按 `epoch, sat, frq, code/phase`），**重跑采集** | 带参考星标记的采集集。**这是 §5.2 与 §5.4 的共同前置**：没有它，事件数与 `P` 都算不出来 |
| 1 | 生成弱标签（§5.2），统计**正例比例 + 事件数（按 `(station, day, sat, event)` 聚类）** | **双阈值，任一触发即终止**：`事件数 < 50` **或** `比例 < 1%` → 写「需城市数据后重开」，**终止**，不调参凑数（§5.2） |
| 2 | 训练二分类器（**剔除 `resp`/`resc`**，加 `wasRef` 掩码、SNR 窗口 2 维、`refCount`），导出 ONNX | `refstar_sliprisk.onnx` / `.norm.json` / `.metrics.json`；**伪相关探测**（R1-4）必须过；**同时计算基线 `P`**（§5.4.2）供 R1-6 用 |
| 3 | SPI + `AiHooks.resolve` + core 否决逻辑（`rowSat` 映射、`aiRefOverride`、迟滞、**只降一级**、切换率上限） | I-1 零回归**逐位一致**（§3.5）；R2-b 断言（恰好第 2 名）通过 |
| 4 | 先跑 96 run/组 × 4 组（A/R/C/D） | R1–R4 判定；切换次数 = 0 → 直接收口 |
| 5 | 若切换次数 > 0，上全量 576 run/组 | 全量 R1–R4 |
| 6 | 报告：只写「链路跑通、切换次数、无害性」，标注 `label_source: lock_event_weak`；**若步骤 0 未做则 R1-6 标 `unverifiable`** | 决策记录 **D20** |

**不依赖数据、可立即做的部分**：SPI 骨架 + `AiHooks.resolve` + `AiOpts` 字段 + 契约测试 + I-1 零回归，约 **1–2 天**。

---

## 8. 决策记录 D20

| 项 | 内容 |
|---|---|
| **编号** | D20（D19「激进模式」为当前最新，D20 未被占用） |
| **决策** | 接口四（参考星风险判别）**立项为实验 provider**：默认关、仅显式注入、不承担生产责任 |
| **交付形态** | 与方案 B 端到端（[§8.4.5](file:///D:/rtklib/rtklib_java/docs/AI_ENHANCEMENT_DESIGN.md#L1644) / D19）同构：链路保留、不默认开启 |
| **验收分两阶段** | **第一阶段**：R1 契约 + R2 零基线 + R3 无害 + R4 存在性 → 允许显式注入；**第二阶段**（有城市数据后）：污染事件率下降、固定解 RMS 改善 → 才允许默认开启 |
| **不追求** | 不追求准确率、不追求收益、不追求「优于 RTKLIB 选星」 |
| **重开条件（第二阶段）** | ① 获得含 LOS/NLOS 真值或参考系统标签的城市数据；② 第一阶段 R1–R4 全部通过且切换次数 > 0；③ 全量 576 run/组 出现净改善（固定率不降、错固不升、RMS 改善） |
| **终止条件** | 第 1 步**双阈值任一触发**：弱标签**事件数 < 50** **或** 正例比例 < 1% → 管线终止并记录，不调参凑数（§5.2） |
| **门槛依赖** | 步骤 0（`is_ref` trace + 重跑采集）是第 1 步、R1-6、§5.4 的 `P` 的**共同前置**；未做则上述判据一律标 `unverifiable`，不得静默跳过（§5.4.1） |

**必须同步写入 [AI_ENHANCEMENT_DESIGN.md](AI_ENHANCEMENT_DESIGN.md) 的三件事**：

1. §8 阶段表新增一行「接口四：实验 provider，默认关、仅显式注入」；
2. §6.3 标签方案表新增一行「参考星 LOS/污染：锁相事件弱标签，**仅供管线跑通**，有效性待参考系统数据」；
3. 决策记录区新增 D20（内容同上）。

---

## 9. 诚实前提

在静态山谷数据上，本功能大概率**「跑得通但看不出效果」**。

理由是间接证据而非直接证据：[§8.2.8](file:///D:/rtklib/rtklib_java/docs/AI_ENHANCEMENT_DESIGN.md#L1489-L1499) 的尾部形态（最低 `pdop` 档错误固定率 0%）只能说明**误差源不集中在几何退化档**，**不能**排除参考星污染事件的存在。正确表述是：

> **无直接证据**表明本数据集存在参考星污染事件；§8.2.8 的尾部形态不构成「污染事件极少」的证明。

因此跑通后最可能的结果是「参考星切换次数接近于零」。**这不是失败，这正是「管线跑通、收益待城市数据」的正确形态。**

但前提是**探针本身没被 H1 污染**。否则「切换次数很多」会被读成「污染很多」，那比调参凑数更不诚实。

---

## 附：文献引用纪律

本设计**不依赖任何外部文献**。理由不是「文献无用」，而是本项目对引用有可核验性要求：[AI_ENHANCEMENT_DESIGN.md](AI_ENHANCEMENT_DESIGN.md) 的 §6.3、§8.4 所引来源均可查证，本文档不得引入不可查证的引用去支撑设计选择。

| 规则 | 内容 |
|---|---|
| C1 | **无标识不引用**：进入正文的前提是「标题 + 作者 + 发表载体 + DOI（或稳定 URL）」四项齐全。缺任一项即视为不可核验 |
| C2 | **不可核验引用只能进附录，不得进正文论据链**：可作为「背景参考」列出并显式标注 `unverified`，但**不得**用于支撑设计选择、阈值取值、验收判据或重开条件 |
| C3 | **不得用文献替代本工程证据**：任何设计决定的依据必须落在 §2 的代码事实、项目内既有结论（§7.1 / §7.3 / §7.5 / D17 / D19）或可在本项目数据上验证的探针上 |
| C4 | **物理动机可保留，但须标为假设**：若某物理主张（如「NLOS 的 SNR 方差高于 LOS」）无法给出可核验标识，只能写成「待验证假设」并绑定证伪方式（如 R1-4 的伪相关探测），不得写成「有研究表明」 |

**当前状态**：原方案援引的两处外部文献（① 2026 年 ScienceDirect XGBoost 在线参考星优选；② 「有研究指出 NLOS 的 SNR 方差显著高于 LOS」）**均无可核验标识，按 C1/C2 不进入正文论据链**。§1.1 与 §5.1 已按此规则改写。若后续补到标识，请追加到下表并标注「仅背景参考」。

| # | 文献 | 标识 | 状态 | 允许用途 |
|---|---|---|---|---|
| — | （暂无） | — | — | — |

---

## 附：文档元信息与引用说明

| 项 | 内容 |
|---|---|
| 版本 | **v1.1（2026-11-14）** |
| 基线 | rtklib-java v3.5.0 |
| 代码核对范围 | `rtklib-core/ai/spi`、`RtkCore.relpos/ddres`、`AiOpts`、`rtklib-ai/training` |
| 体例参照 | [AI_ENHANCEMENT_DESIGN.md](AI_ENHANCEMENT_DESIGN.md) §7.1（对照组）、§7.3（两层切分）、§7.5（两阶段验收）、D17/D19（实验 provider 处置） |
| 外部文献 | **无**。引用纪律见上节「附：文献引用纪律」（C1–C4） |
| 与代码的关系 | 本文件只定义设计，**不含任何已落地代码**；实现状态以 `AI_ENHANCEMENT_DESIGN.md` §8 为准 |

### 变更记录

| 版本 | 变更 | 性质 |
|---|---|---|
| v1.0 | 首次定稿：判定表、F1–F10 代码事实核对、H1–H5 硬伤与修正、R1–R4 探针、D20 | 设计结论 |
| **v1.1** | ① §5.4 补 **5.4.1 前置依赖**——`P` 的测量完全依赖步骤 0 的 `is_ref` trace，与 H5 联动；未做则 R1-6 标 `unverifiable`，不得静默跳过。② **R2-b 从「落在前 K 名」收紧为「恰好第 2 名」**，并定死「门槛拒绝则 0 次切换、不许顺延」；同步收紧 §3.3 切换规则与 H4 修正。③ **§7 第 1 步终止阈值改为双阈值**：`事件数 < 50` **或** 比例 < 1%（事件数为主判据），同步更新 D20 终止条件。④ 新增 **附：文献引用纪律（C1–C4）**，§1.1 与 §5.1 两处不可核验引用降级为「待验证假设 / 背景参考」，不再作论据 | **不影响设计结论**，均为判据可证伪性与引用纪律的补强 |