# GNSS AI 增强开发文档

> **版本**：v1.2（定稿，含执行层附录）
> **日期**：2026-11-06
> **基线版本**：v3.4.0（2026-11-06，commit `dce304c`，tag `v3.4.0`）
> **状态**：设计定稿。阶段 0–2 已完成（SPI + `rtklib-ai` 推理层 + 接口一接线）；接口一训练与评估（阶段 3–4）未开始，接口三未开始
> **前置文档**：[ALGORITHM_FIX_LOG.md](ALGORITHM_FIX_LOG.md)、[CORE_OPTIMIZATION_MATRIX.md](CORE_OPTIMIZATION_MATRIX.md)、[DATA_GUIDE.md](DATA_GUIDE.md)、[ROADMAP.md](ROADMAP.md)

| 接口 | 设计 | 实现 | 生效条件 | 决策 |
|---|---|---|---|---|
| 接口一 假固定判别 | 定稿 | 推理层与接线已完成（阶段 2），模型训练未开始 | 未注入即关闭（无 `AiProvider`） | MVP 唯一实施项（§8 阶段 2–4） |
| 接口二 固定决策辅助 | **冻结** | 不实施 | — | 与现有 `arfilter` 门控重叠，边际收益为负风险（§4.2、D1） |
| 接口三 观测改正 | 定稿 | 未开始 | 未注入即关闭（无 `AiProvider`） | 接口一验证有效后再启动（§8 阶段 5） |

实现状态以 §8 为准；与本章摘要冲突时以 §8 为准。

---

## 1. 定位与边界

### 1.1 这是什么

本文档定义 rtklib-java 在 v3.4.0 之后进入的**新领域**：在现有确定性解算链上增加可插拔的**学习式判别/改正接口**。交付物是**接口 + 特征管线 + 训练管线 + 评估协议**，不是预训练模型。

三条不可动摇的原则：

1. 示例模型仅证明管线跑通，不保证在任何场景有效。
2. 使用者必须用自己的数据重新训练。
3. 接口三的标签获取方式由使用者自行解决（§6.3 给出参考方案）。

### 1.2 这不是什么

**不是求解器替换。** 学习模型只做「判别」和「改正量估计」，不替代最小二乘、不替代 LAMBDA 搜索、不产生协方差。协方差仍由现有法方程产出，AI 只在观测方差阵 `R` 上施加**单侧膨胀**（§4.3.4）。

**不是一阶优化器。** Adam / SGD / 动量法属于数值优化方法，与本文档无关：它们替换的是 `rtklib-core` 内部的求解器，属于算法改进流程，且因不产出协方差、在 GNSS 状态估计中属结构性缺失（v3.4.0 已评估关闭，见对话结论与 §10 D6）。

**不是运行时传感器融合。** 不引入相机 / LiDAR / IMU 在线推理。相机仅可作为离线标签生成工具。

**不是云服务。** 推理在本地进程内完成，不引入网络调用、不引入 Python 进程。

### 1.3 与 v3.4.0 的关系：基线冻结

AI 线**不修改 core 的数值行为**。core 只增加两类东西：

1. 可空的注入点（`AiProvider` 回调接口 + `AiHooks` 注入点，未注入 = 原生行为）
2. 一份只含参数的配置（`AiOpts`，**不含 `enable` 布尔量**）

由此得到一条**可验证的不变量**：

> **I-1（零回归不变量）**：`AiOpts` 全部默认值时，同一输入的解算输出与 v3.4.0 **逐位一致**（`ratio`、`stat`、`rr`、`qr`、残差全部 `byte-identical`）。

这条不变量是阶段 1 的验收判据（§8），也是后续所有阶段的前提。任何破坏 I-1 的改动不得合入。

### 1.4 与 ROADMAP 政策的关系

[ROADMAP.md](ROADMAP.md#L6) 声明「3.1.0 起仅接受 bugfix，不新增算法特性」。本文档不修改该政策，而是给出适用范围界定：

- 该政策约束的是 **core 定位算法的数值特性**（会改变默认输出的行为）。
- AI 线是**独立研究线**：代码在独立模块 `rtklib-ai`，core 侧**不注入即关闭**（无 `AiProvider`），满足 I-1。
- **硬规则**：任何 AI 接口在 core 侧**不得在无显式注入的情况下生效**，除非通过 §7.4 评估闸门并在 ROADMAP 中登记。

若评估闸门通过后要默认开启，须先修改 ROADMAP 政策条款，不得绕过。

---

## 2. 基线事实：AI 必须打败的现有机制

这一章是全文档的立论基础，也是「AI 到底还有多少空间」的答案。常见假设是「ratio 门限是唯一防线，因此 ML 有充足空间」。**实际上不是**——v3.4.0 已有多层机制在做同样的事。不先盘点，接口会做成冗余功能。

### 2.1 现有「假固定」防线（接口一的竞争者）

| 机制 | 位置 | 作用 |
|---|---|---|
| ratio 门限 | [manage_amb_LAMBDA](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L2098-L2107) 的 `skip` 判据 + `thresar[0]` | 搜索空间可分性 |
| 动态 ratio 门限 | [RtkCore.java#L1314](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1314) 附近的多项式调整 | 按卫星数修正门限 |
| 固定后残差校验 | `valpos(rtk, v, R, vflg, nv, 4.0)`（固定分支内） | 固定解的验后残差一致性 |
| AR 滤波回退 | `opt.arfilter` 块，`prev_ratio1` / `prev_ratio2` 时序比较 | 用 ratio 历史识别可疑固定 |
| 级联 AR | [RtkOptimizationsCascadeAR](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkOptimizationsCascadeAR.java#L241-L334) | EWL/WL/NL 三级独立门限 |
| 部分固定 | [RtkOptimizationsPartialAR](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkOptimizationsPartialAR.java#L113-L118) | `partialArMinRatio` 逐子集门限 |

**结论**：接口一的价值不在于「有门限」，而在于**门限看不到的量**。ratio 是搜索空间的量，它无法区分两种情形：

- 正确固定：残差与整数解一致 → 残差小且随机
- 错误固定：`ratio` 很高但残差与整数解**系统性不一致**（短基线强相关场景常见）

这就是武大 2026 用 ADR/PRR/PCR 三个**残差类**量而非 ratio 的物理依据。接口一选残差类特征，正是为了覆盖 ratio 的盲区（D2）。

### 2.2 现有「固定率」机制（接口二的竞争者）

接口二的设想是「在固定尝试前判断是否值得试」。但这条路径上**已有两套机制**：

1. **`arfilter` 回退门控**：`prev_ratio2 < thres` 且 `nb_ar >= mindropsats` 时，主动降锁一颗卫星并重跑 `resamb_LAMBDA`（[manage_amb_LAMBDA](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L2113-L2160)）。
2. **`excsat` 剔除重试**：同一次调用内按「降锁 → 重解 → 恢复」做卫星剔除实验。

也就是说，「是否值得尝试 / 该剔除哪颗」已经被 ratio 历史 + 卫星剔除实验部分覆盖。再叠一层 ML 判断，最可能的结果是**把本会成功的固定拦下来**——这不是实现 bug，是设计上的净负收益风险（据此冻结，见 D1）。

### 2.3 现有「抗多路径」机制（接口三的竞争者）

| 机制 | 位置 | 作用 |
|---|---|---|
| IGGIII 抗差 | `RtkOpts.enableIggiii` 等 10 项 | 按标准化残差降权（`k0=3`、`k1=6`） |
| SNR 中位数抗差 | `RtkOpts.enableSnrMedian` 等 10 项 | 相对 SNR 中位数降权，并反馈进 `varerr` |
| 验后残差编辑 | `RtkOptimizationsResEdit.checkCycleSlip` / `checkPcConsistency` | 时序周跳 + 伪距-载波一致性 |
| 观测误差模型 | [varerr](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1450-L1508) | 高度角 + SNR + 基线长 + 钟漂的解析方差 |
| 位置验后校验 | `valpos`（4σ 门限） | 整体解的一致性 |

**结论**：接口三的价值只在**现有解析模型无法表达的模式**上——例如 NLOS 造成的**系统性同向偏差**（残差大且符号一致，IGGIII 会降权但降权只是「不信」，不是「修正」），以及多路径随几何变化的**时变偏差**。IGGIII/SNR 都是「降权」，不改观测值；接口三做「改正」，是本质不同的动作。这也是它必须承担不确定性传播责任的原因（§4.3.4）。

---

## 3. 架构

### 3.1 模块划分：core 只放 SPI，实现独立成模块

| 层 | 位置 | 内容 | 新增依赖 |
|---|---|---|---|
| SPI | `rtklib-core` 内 `org.rtklib.java.ai.spi` | 两个 SPI 接口 + 上下文/输出 record + 注入回调 `AiProvider` + 注入点 `AiHooks`。**不提供 NOOP 实现**（`null` 即关闭，§3.2） | **无** |
| 注入 | `rtklib-core`：`AiProvider` 回调（宿主注入）+ `AiHooks`（`ThreadLocal` 注入点）+ `Rtk` 上可空的实例字段 + `RtkConfig.ai`（`AiOpts`） | 回调挂载与参数配置 | **无** |
| 实现 | **新建 `rtklib-ai` 模块**（依赖 `rtklib-core`） | ONNX Runtime 推理、特征提取、模型加载、异常降级 | `onnxruntime` |
| 训练 | `rtklib-ai/training/`（Python） | PyTorch 训练、ONNX 导出、评估脚本 | PyTorch |

**为什么不让 core 直接引 ONNX**：`onnxruntime` 含平台原生库（十 MB 量级），引入 core 会让所有下游使用者被迫承担；且 core 是「定版核心档案」覆盖的模块，不宜承载实验性代码。SPI 用纯 Java 类型（`int` / `double[]` / `record`），零依赖即可编译。

**成本诚实说明**：新增模块需在根 `pom.xml` 的 `<modules>` 登记，且版本号需随 reactor 同步（当前 12 个 pom → 13 个）。这是新增模块的真实代价，不是零成本。

### 3.2 注入方式：注入回调（`AiProvider`）+ 注入点（`AiHooks`）+ 实例字段覆盖

AI 接口的注入与 `traceCallback` 有一个关键差别：`traceCallback` 是**模块内部**接线，一个 setter 即可；AI 接口是**外部注入**，必须穿透多个 Processor 才能到达 `RtkCore`。

**核心原则：core 只定义「什么时候问」，不定义「谁回答」。** core 侧不得 `import` 任何 `rtklib-ai` 类型；`rtklib-ai` 侧只通过 `org.rtklib.java.ai.spi` 的**纯 JDK 类型**与 core 交互。依赖方向严格单向：`rtklib-ai → rtklib-core`（编译期），core 对 `rtklib-ai` **零引用**。

**`AiProvider`（core 侧 SPI，宿主唯一需要实现/注入的东西）**：

```java
/* rtklib-core: org.rtklib.java.ai.spi.AiProvider */
public interface AiProvider {
    /** 接口一：固定质量校验。返回 null = 本 provider 不提供接口一 */
    FixValidator fixValidator();

    /** 接口三：伪距偏差改正。返回 null = 本 provider 不提供接口三 */
    ObsCorrector obsCorrector();
}
```

**`AiHooks`（core 侧注入点，`ThreadLocal` 持有回调）**：

```java
/* rtklib-core: org.rtklib.java.ai.spi.AiHooks */
public final class AiHooks {
    private static final ThreadLocal<AiProvider> TL = new ThreadLocal<>();

    /** 宿主注入（rtklib-ai 或使用方调用），传 null 即整体关闭 */
    public static void install(AiProvider provider) { TL.set(provider); }

    /** 清除注入，恢复 v3.4.0 原生行为（测试 teardown 必须调用） */
    public static void clear() { TL.remove(); }

    /** 解析优先级：实例字段（Rtk）> 注入点。返回 null 即关闭 */
    static FixValidator resolveFixValidator(Rtk rtk) { /* 实例字段非 null 则取之，否则 provider==null ? null : provider.fixValidator() */ }
    static ObsCorrector resolveObsCorrector(Rtk rtk) { /* 同上 */ }
}
```

调用点保持唯一分支，可 grep：

```java
FixValidator fv = AiHooks.resolveFixValidator(rtk);
if (fv != null) { /* 唯一分支 */ }
```

**为什么用回调，而不是让 core 直接持有实现**：若 core 直接 `new` 或静态引用 `rtklib-ai` 的类，core 就反向依赖实验性模块（§3.1 的依赖污染立即成立），且 `rtklib-ai` 缺席时 core 无法编译。回调把「实现是否存在」推迟到运行时，core 编译期只认接口，从而**彻底避免 core 与 `rtklib-ai` 相互引用**。

**注入示例（`rtklib-ai` 侧或使用方）**：

```java
AiHooks.install(new OnnxAiProvider(modelDir));   // OnnxAiProvider 由 rtklib-ai 实现
// ... 解算 ...
AiHooks.clear();
```

**为什么不用层层注入**（三条理由均来自本仓库实际代码，非假想）：

1. **实例注入会静默丢失**。[PostPosProcessor.createRtk](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rinex/PostPosProcessor.java#L505-L516) 每次解算 `new Rtk()`，只拷 `opt` 与 `rtkConfig`，**不拷 `transient` 字段**；且前向（[L238](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rinex/PostPosProcessor.java#L238)）与反向（[L319](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rinex/PostPosProcessor.java#L319)）各调一次，赋值写在调用点极易漏掉反向，漏了不报错、只是 AI 静默不生效。同一坑已存在于既有先例：`traceCallback` 在 `PostPosProcessor` 上**根本没有 setter**（4 个 Processor 有，它没有）。
2. **接口三的实现对象要在 `static` 上下文可取到**。`relpos` 是 `static`，`ddres` 是 `private static`（[L1546](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1546)），`Rtk` 实例由调用方持有、`RtkCore` 不持有。注入点让 `RtkCore` 无需依赖调用方在每个入口正确设置字段。
3. **`joint` 模块完全在 Processor 体系之外**。[JointDdRes.java#L202-L206](file:///D:/code/rtklib_java/rtklib-joint/src/main/java/org/rtklib/java/joint/JointDdRes.java#L202-L206) 直接静态调用 `RtkCore.varerr`，实例注入到不了这里。v1 接口三不接线 joint（§4.3.5），但 v2 若要覆盖 joint，注入点是唯一可行路径。

**依赖方向严格单向**：`rtklib-core` 只定义 `AiProvider` / `FixValidator` / `ObsCorrector` 三个接口与上下文记录类型，**对 `rtklib-ai` 零引用**；`rtklib-ai` 依赖 `rtklib-core` 实现这些接口，由宿主（或 `rtklib-ai` 的 `OnnxAiProvider`）调用 `AiHooks.install()` 注入。core 的 `pom.xml` 中不得出现 `rtklib-ai`。

**注**：接口三 v1 **不修改 `varerr` 函数本体**（§4.3.5 已改为在 `ddres` 内乘系数），因此「`varerr` 入参是 `RtkState` record」这一条不再是当前设计的理由，仅作为 v2 下沉到 `varerr` 时的前置约束保留。

**为什么用 `ThreadLocal` 而非裸 `static`**：保住测试隔离，以及 §7.1 四组对照的并行执行能力。裸 `static` 会让 I-1 零回归验证退化为「测试顺序相关」——某用例注入后未清理，其后所有「原生行为」用例其实都在带 AI 跑，且**全绿**。

**保留实例字段覆盖**：多会话（不同站挂不同模型）与单测精确控制仍需出口。优先级：**实例字段 > 注入点**。

**`AiProvider` 的两个方法各自独立、可独立返回 `null`**。禁止在 provider 内返回一个「什么都不做」的 NOOP 对象来冒充「未启用」——**NOOP 默认实现会从这里溜回来**，正是本节要消除的东西。接口二已冻结（D1），连方法都不建。

**不设 `enable` 布尔量**。开关由 `null` 兼任；`enabled=true` 而对象为 `null`、`enabled=false` 却注入了对象，这两种非法态在类型层面无法构造。

**参数住 `RtkConfig.ai`（`AiOpts`，`Serializable`），行为对象住注入点/实例字段**。`RtkConfig` 在 `createRtk` 里被深拷贝，参数随配置自动传播；行为对象不随配置走，故注入时须打一条 INFO 日志，避免「配置里开了 AI、实际没跑」的静默不一致。

**代价（已知并接受）**：

1. **测试污染**：全局可变量使 I-1 验证依赖清理纪律 → 必须提供 `AiHooks.clear()`，且测试 teardown 断言 `AiHooks` 为空（§9 风险表）。
2. **并行 A/B 依赖线程传播**：若在 `rtklib-stream` 等线程池中使用，需在任务入口重新注入。
3. **配置无法复现行为对象**：保存的 `RtkConfig` 只能复现参数，模型与会话须由使用方重新注入。

### 3.3 三接口的精确调用位置

| 接口 | 文件 | 位置 | 语义 |
|---|---|---|---|
| 接口一 | [RtkCore.java#L476-L486](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L476-L486) | `boolean valOk = ...` 之后、`if (++rtk.nfix >= opt.minfix)` **之前** | 固定解产出后验证 |
| 接口二 | [manage_amb_LAMBDA](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L2098-L2107) | `skip` 判据之后、`resamb_LAMBDA` 之前 | **冻结，不实施** |
| 接口三 | [relpos 迭代内首轮](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L306-L323) + `ddres` 内两处 | **推理**：`for (i=0;i<opt.niter;i++)` 内、`i == 0` 的 `zdres(rover)` **成功之后**、首次 `ddres` 调用**之前**（[L307-L323](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L307-L323)），每历元 1 次批量推理，产出 `aiBias[]` / `aiScale[]` 局部数组；**施加**：`ddres` 的 `v[nv]` 组装处（[L1640-L1641](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1640-L1641)）与 `Ri`/`Rj` 赋值处（[L1748-L1749](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1748-L1749)），**只读查表** | 批量算改正 / 偏差扣除 / 方差膨胀 |

**接口一的位置必须精确到「`nfix` 自增之前」**，原因是该位置之后有三处不可逆副作用：

```java
if (++rtk.nfix >= opt.minfix) {          // 计数器自增
    if (opt.modear == ARMODE_FIXHOLD) holdamb(rtk, xa_arr);   // 模糊度保持
    if (opt.mode == PMODE_STATIC_START) opt.mode = PMODE_KINEMA;  // 模式切换
}
```

若在 `stat = SOLQ_FIX` 之后才拒绝，这三处已执行，无法回滚。放在 `valOk` 之后、`nfix` 之前，则拒绝只需「不进固定分支」，`rtk.x` / `rtk.P` 仍持有浮点解（[RtkCore.java#L437](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L437) 已拷入），回退天然正确。

**已知不可回滚的副作用**：`manage_amb_LAMBDA` 内部对 `prev_ratio1` / `prev_ratio2` / `excsat` / `lock[]` 的修改在接口一执行前已经发生，拒绝固定不会撤销它们。这**是期望行为**——ratio 确实很高，AR 历史应当如实记录。此点须写入实现注释，避免后续被当作 bug 修复。

**接口三为什么必须拆成「批量算 + 只读施加」两类位置**（性能硬约束，不是风格选择）：

`ddres` 有 **3 个调用点**：[L323](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L323)（迭代内）、[L431](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L431)（浮点解）、[L473](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L473)（固定解回代）。`niter` 默认 1（[PrcOpt.java#L215](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/data/PrcOpt.java#L215)），故每历元 `ddres` 实际执行 **2–3 次**。

在**单次** `ddres` 内，`varerr` 按双差对调用，每对调用两次（`Ri` 与 `Rj`，[L1748-L1749](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1748-L1749)）；双频 20–30 星约 76–116 次。乘以每历元 2–3 次 `ddres`，**合计约 150–350 次/历元**。若在 `varerr` 内做推理，按 1.2 ms/次即 **180–420 ms/历元**，直接不可用。

因此定死为：

1. **每历元算一次**：在迭代循环**第一轮**内、`zdres(rover)` 成功后、首次 `ddres` 之前（`i == 0` 守卫，[L307-L323](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L307-L323)）对 `ns × nf` 个「卫星-频点」批量推理，结果写入 `relpos` 的两个**局部**数组 `aiBias[ns*nf]` / `aiScale[ns*nf]`，作为参数传入 `ddres`。
   **为什么不能放在循环之前（[L306](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L306)）**：`zdres(rover)` 在循环体内（[L307-L311](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L307-L311)），仰角/方位角数组 `azel` 在本历元**尚未计算**；放在循环前只能读到**上一历元**的 `azel`，特征是错的。
2. **施加在 `ddres` 内、但只读**：偏差扣在 `v[nv]` 组装处，方差系数乘在 `Ri`/`Rj` 赋值处。两处都是纯查表，零推理。
3. **`varerr` 函数本体不改**：这使 `rtklib-joint` 的静态调用（[JointDdRes.java#L202-L206](file:///D:/code/rtklib_java/rtklib-joint/src/main/java/org/rtklib/java/joint/JointDdRes.java#L202-L206)）完全不受影响（§4.3.5）。
4. **不修改 `obs` / `y`**：`v[nv]` 每次调用都由 `y` 现场重算，在 `v[nv]` 上扣偏差**天然幂等**——`ddres` 跑 2 次还是 3 次都不会重复扣除（§4.3.6）。

**局部数组而非 `Rtk` 字段**：`aiBias`/`aiScale` 只在未注入时为 `null`、注入时才分配，`null` 路径零分配、零分支外开销，I-1 由此得到结构性保证（§3.4）。

接口一不存在此问题：每历元最多调用一次（固定解产出时），天然安全。这也是接口一必须先做的原因之一。

### 3.4 降级策略：两层，且必须是「不干预」

| 层 | 条件 | 行为 |
|---|---|---|
| 未注入 | 实例字段为 `null` **且** `AiHooks` 注入点取值为 `null` | 原生行为（I-1） |
| 运行时失败 | 模型缺失 / 加载失败 / 推理异常 / 超时 | **返回「不干预」**，记录一次告警，不抛出 |

「不干预」的定义（**与 §4.4 的返回类型严格对应**）：

- 接口一：`confidence = 1.0`。core 侧判 `1.0 >= ai.fixValidatorMinConfidence` 恒为 `accept`（阈值取值范围已限定 `[0,1]`，§4.5）
- 接口三：`biasMeters = 0`、`varianceScale = 1.0`（逐行，含 `nRows` 不足时的补齐行）

**为什么接口一用 `1.0` 而不是布尔 `accept`**：SPI 只输出 `confidence`，判定在 core（D9）。`1.0` 是「最高置信」在阈值语义下的唯一安全取值——若改为输出 `0.0` 表示「弃权」，则会触发拒绝固定，属 fail-closed，违反本节。

**禁止 fail-closed**：推理失败时不能拒绝固定（会把可用系统降级成不可用）。这一条与 §4.3.4 的「方差单侧膨胀」同源——AI 只允许保守，不允许激进。

**「运行时失败」是第三种状态，`null` 表达不了**：`obj != null` 但模型文件缺失、ONNX 抛异常、推理超时，都属此类。因此「没有默认值」这一说法要收窄——`null` 消除的是**配置层的默认**，消不掉**失败时的降级语义**。降级语义必须显式定义，不能留给实现者临场决定。

---

## 4. 接口契约

### 4.1 接口一：假固定判别

| 项 | 定义 |
|---|---|
| 目的 | 拦截**错误固定**，降低假固定率 |
| 自身不写的量 | 浮点解、LAMBDA 搜索、ratio、`prev_ratio1/2`、`excsat` |
| 输入 | `FixQualityContext`，6 维（§5.1），构造于 core 侧（§4.4.4、字段来源见 §4.4.5） |
| 输出 | `confidence`（`double`，`[0,1]`）。**不含 `accept`**（D9） |
| 判定 | **core 判**：`accept = confidence >= ai.fixValidatorMinConfidence` |
| 未注入 | `null` → 不进分支，行为与 v3.4.0 逐位一致（§3.4） |
| 拒绝后果 | `stat` 保持 `SOLQ_FLOAT`，输出浮点解，不进固定分支 |
| 拒绝的连带后果 | `nfix` **不自增**（因为跳过 `if (++rtk.nfix >= opt.minfix)` 整块），故 `minfix` 达到时间被推迟、`ARMODE_FIXHOLD` 的 `holdamb` 不执行、`PMODE_STATIC_START` 不切换。**这是拒绝生效的唯一机制，属期望行为** |
| 拒绝的审计 | 必须发 `Trace.emit("AR","AI_REJECT",..., "nb", nb, "ratio", ratio, "confidence", c, "threshold", t)`；不得静默跳过（否则线上无法回答「AI 拦了什么」） |
| 降级的审计 | 必须发 `Trace.emit("AR","AI_DEGRADE",..., "interface","fix_validator", "error", msg)`，**每会话一次**（用 `Rtk` 上一个 `transient boolean` 去重，避免逐历元刷屏） |

**契约要点 1**：接口一**只否决、不创造**。它不能让浮点解变固定，只能让固定解退回浮点。因此它对「固定率」的影响是**单调下降**，对「错误固定率」也是**单调下降**。评估必须同时看两个指标（§7.2）。

**契约要点 2**：特征中**禁止出现真值派生量**。任何由参考坐标计算的量（如固定解与真值偏差）不得作为输入特征——那是标签，不是特征。违反此条会产生训练集完美、部署失效的典型泄漏。

### 4.2 接口二：固定决策辅助（冻结，不实施）

**决策：冻结。** 不实施、不接线、不训练。保留本节仅为记录决策依据。

冻结理由（按重要性排序）：

1. **已被现有机制覆盖**：`arfilter` + `excsat` 已实现「是否值得尝试 / 剔除哪颗」的门控（§2.2）。
2. **净收益方向不明确**：接口一影响的是已固定解（可证伪的窄问题）；接口二影响的是「是否尝试」，误判的代价是**降低固定率**——而固定率是本项目 v3.4.0 已实测的核心指标（joint 单/双基线 FIX 91.5%）。为一个可能为负的收益去动核心指标，风险收益比不成立。
3. **特征与接口一重叠**：若接口一选方案 B（ADR/PRR/PCR），接口二候选特征几乎同集（§5.2）。两个接口共用特征但标签不同，等于把一个问题拆成两个训练目标，收益不明而维护成本翻倍。

**重开条件**（任一满足才重新评估）：

- 接口一已在 ≥2 个未参与训练的站上证明净收益，且冻结理由 2 的「降低固定率」被实测排除；
- 出现 `arfilter` 明确失效的可复现场景（例如长弧段 ratio 稳定高但始终不固定）。

### 4.3 接口三：观测改正

#### 4.3.1 目的与边界

对多路径 / NLOS 观测做**偏差改正 + 权重膨胀**，而非剔除（剔除会损失几何强度）。

**v1 三条硬边界**（必须定死，否则会污染双差自洽性与模糊度状态）：

1. **仅伪距**。载波相位不做偏差改正——相位偏差估计错误会污染模糊度估计，代价远大于收益。
2. **仅流动站**。双差在 `ddres` 内形成，多路径是**站址相关**误差；对基准站施加流动站学到的偏差会破坏双差的自洽性。v1 只改 `obs[0]`（流动站）。
3. **仅改正不剔除**。剔除由现有 `valpos` / IGGIII / `satexclude` 负责，接口三不重复。

#### 4.3.2 输入

逐卫星、逐频点特征（§5.3），来源为 `Ssat` 与 `Obsd` 的现有字段，无需额外计算。

#### 4.3.3 输出

完整定义见 §4.4.2（`CodeCorrection` 只有 `biasMeters` / `varianceScale` 两个分量，**不含 `sat`/`freq`**——行序即索引）。语义：

- `biasMeters`：伪距偏差改正量（米）。**符号约定**：DD 残差按 `v = (rover_ref − base_ref) − (rover_j − base_j)` 组装（[L1640-L1641](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1640-L1641)），故改正施加为

  ```java
  // refIdx / j 是卫星在 sat[] 中的下标（0-based），不是卫星号
  v[nv] -= aiBias[refIdx * nf + frq] - aiBias[j * nf + frq];
  ```

  即「参考星偏差减去本星偏差」，**不是**「整体减一个标量」。这与「先在流动站观测值上减去偏差、再做双差」在数学上等价，但只有前者幂等（§4.3.6）。

  **索引必须用 `sat[]` 下标，禁止用卫星号 `sat[i] - 1`**：`refIdx`/`j` 由 `ddres` 现场按 `iu[]/ir[]` 选出（[L1585-L1606](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1585-L1606)），同一历元内 3 次 `ddres` 调用的 `refIdx` 可能不同（参考星按仰角/SNR 评分择优）；而卫星号与下标之间没有固定映射，用卫星号索引会越界或错行。
- `varianceScale`：方差膨胀因子，**必须 ≥ 1.0，且 core 侧钳位到 `ai.varianceScaleMax`**（§4.3.4）
- **仅 `code == true`**：`code` 由 `f >= nf` 判定（[L1583](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1583)），`frq = f % nf`（[L1582](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1582)）；相位不施加偏差，但**同样施加 `varianceScale`**（§4.3.1）

#### 4.3.4 不确定性传播（关键契约）

**问题**：`biasMeters` 是一个**均值估计**，必然带残差方差 `σ_m²`。若只减偏差、不把 `σ_m²` 计入 `R`，滤波器会过度自信 → 协方差低估 → ratio 虚高 → **假固定率上升**，与接口一目标反向。

**契约**：

```
R_i' = varianceScale_i × varerr(...)
```

施加位置（**两类，不得合并**，理由见 §3.3）：

| 位置 | 时机 | 动作 |
|---|---|---|
| `relpos` 迭代**首轮**内、`zdres` 之后、首次 `ddres` 之前（`i == 0`，[L307-L323](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L307-L323)） | 每历元 1 次 | 批量推理 `ns × nf` 个「卫星-频点」，产出 `aiBias` / `aiScale` 两个局部数组 |
| `ddres` 的 `v[nv]` 组装处（[L1640-L1641](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1640-L1641)） | 每次 `ddres` | 查表扣偏差：`v[nv] -= aiBias[refIdx*nf+frq] - aiBias[j*nf+frq]`（**仅 `code`**） |
| `ddres` 的 `Ri`/`Rj` 赋值处（[L1748-L1749](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1748-L1749)），**在 `if (!code)` 半周补偿之后**（[L1751-L1752](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1751-L1752)） | 每次 `ddres` | 查表乘系数：`Ri[nv] *= aiScale[refIdx*nf+frq]`、`Rj[nv] *= aiScale[j*nf+frq]` |

**顺序敏感**：`varianceScale` 必须在 `Ri[nv] += 0.01` 之后施加（[L1750-L1753](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1750-L1753)）。若乘在半周补偿之前，`0.01` 会被膨胀因子放大，相位半周历元的方差被系统性高估。

**偏差改正会改变 `maxinno` 剔除行为（已知且期望）**：`maxinno` 门限判定在 `v[nv]` 组装之后、`Ri/Rj` 计算之前（[L1735-L1740](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1735-L1740)）。改正后的残差更小，被剔除的观测更少，`vsat`/`rejc` 也随之变化。这属期望行为（改正的目的就是让残差回到正常量级），但它意味着接口三**间接影响卫星可用性**，评估时必须与原生组对比 `ns` 的变化，不能只看固定率。

**索引方向必须照抄，不得凭直觉对调**：

- `Ri[nv]` ↔ **参考星** `sat[refIdx]`（`Ri` 的 `i` 是 reference）
- `Rj[nv]` ↔ **非参考星** `sat[j]`
- 证据：[L1748](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1748) 传入 `sat[refIdx]`，[L1749](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1749) 传入 `sat[j]`
- 后果若对调：两颗星的膨胀因子互换，且**不报错、不越界**——纯粹静默错误，评估时表现为「模型无效但看不出原因」

**钳位（core 侧强制，不信任 impl）**：

```java
double s = corr.varianceScale();
if (!Double.isFinite(s) || s < 1.0) s = 1.0;            // 单侧膨胀，下限硬钳
if (s > ai.varianceScaleMax) { s = ai.varianceScaleMax; clampWarnOnce(); }

double b = corr.biasMeters();
if (!Double.isFinite(b) || Math.abs(b) > ai.obsCorrectorMaxBias) {
    b = 0.0;                                            // 偏差置 0，方差照旧施加
    biasClampWarnOnce();
}
```

- 下限 `1.0` 硬钳而非报错：模型输出 `0.9` 时按 1.0 处理，等价于该行弃权，不改变系统行为
- 上限钳位**必须告警**：模型持续输出 `1e6` 会让该卫星静默失效（权重≈0）却不报错，等于模型在偷偷剔除卫星，与 §4.3.1「仅改正不剔除」冲突
- `NaN`/`Inf` 按 1.0 处理：这是降级路径（§3.4），不是钳位路径
- **`biasMeters` 超限时置 0，而不是钳到边界值**：钳到 `±10 m` 等于给观测强加一个 10 m 的错误改正，比不改更糟；置 0 则是「本行偏差弃权」，与 §4.3.1 一致。且此时**必须保留 `varianceScale`**——它是模型对自己不确定性的诚实申报，不应因偏差越界而丢弃
- `biasMeters` 的 `NaN`/`Inf` 同样置 0（不是按 1.0，单位不同不可混用）

**IFLC 边界（v1 必须禁用）**：`ionoopt == IONOOPT_IFLC` 时 `nf` 被强制为 1（[L152](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L152)），`y` 中已是**消电离层组合量**，逐频点偏差的物理含义不再成立。因此接口一、接口三**均在 core 侧门禁**（§4.3.7），而非由 impl 自行判断。

**两条强制规则**：

1. **单侧膨胀**：`varianceScale ≥ 1.0`。模型**不得**缩小方差（缩小等于宣称比解析误差模型更可信，无依据）。
2. **成对输出**：施加了 `biasMeters ≠ 0` 的卫星，其 `varianceScale` 必须满足
   `varianceScale ≥ 1.0 + (σ_m / σ_obs)²`，其中 `σ_obs² = varerr(...)`。
   训练侧须同时回归 `bias` 与 `σ_m`；只输出 `bias` 的模型视为**契约违规**，实现侧必须 fail-fast 报错（而不是静默按 1.0 处理）。

**为什么单侧**：这是唯一能让接口三「无论模型多差都不会让系统更差」的结构性保证——模型若无效，`bias` 接近 0、`varianceScale` 接近 1，退化为原生行为；模型若给出错误偏差但方差如实膨胀，滤波器会通过降权自动削弱它。**方差是 AI 的安全带。**

#### 4.3.5 与 `rtklib-joint` 的边界

`rtklib-joint` 的 [JointDdRes.java#L202-L206](file:///D:/code/rtklib_java/rtklib-joint/src/main/java/org/rtklib/java/joint/JointDdRes.java#L202-L206) 直接静态调用 `RtkCore.varerr`，且**不在 `relpos` 调用链内**，拿不到 `relpos` 的局部 `aiBias`/`aiScale`。

因此定死：

1. v1 的接口三**只覆盖 `RtkCore.relpos` 的双差链路**，joint 的解算路径**不受任何影响**（`varerr` 本体不改、`ddres` 不共享）。
2. 若 v2 要覆盖 joint，**必须复用同一张表**（由 joint 自己的批量推理产出），禁止在 joint 内逐星单独推理——同一历元两次推理既翻倍耗时，又会让两条链路的偏差不一致。
3. `RtkCore.varerr` 是 `public static`（[L1450](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1450) 附近），**任何写进 `varerr` 的改动都会外溢到 joint 与所有外部调用方**。这是 v1 禁止改 `varerr` 的第二个理由（第一个是 §3.3 的调用次数）。

#### 4.3.6 施加语义：幂等、逐行、成对

**幂等性的来源**：`ddres` 每次调用都从 `y[]` **现场重算** `v[nv]`（[L1640-L1641](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1640-L1641)），`v[nv]` 不被累积修改。故在赋值语句后紧接一行扣除，无论 `ddres` 跑 2 次还是 3 次，每次都是「从原始 `y` 算出的残差 − 同一个偏差」，**不会重复扣除**。

**禁止的三种写法**（都会破坏幂等或自洽性）：

| 禁止写法 | 后果 |
|---|---|
| 修改 `obs[0].P[i]` / `obs[0].L[i]` 观测值本身 | 偏差被写入历元观测，下一历元沿用；且基准站未同步改正，双差不再自洽 |
| 修改 `y[f + idx * nf * 2]` | `y` 是 `zdres` 产物，同一历元被 3 次 `ddres` 复用；改正会被叠加 3 次 |
| 只在某一次 `ddres` 调用中施加（如仅 L473 的固定解回代） | 浮点解与固定解的残差来自不同世界，AR 输入不一致；且随 `niter` 变化而行为漂移 |

**逐行语义**：`aiBias`/`aiScale` 长度为 `ns*nf`，行序 `idx = i*nf + frq`（`i` = `sat[]` 下标）。`biasMeters` 只对 `code` 行生效；`varianceScale` 对同一 `(i, frq)` 的 **code 行与 carrier 行同时生效**（同一颗星同一频点的观测质量是同一个判断，不应让伪距与相位拿到互相矛盾的权重）。

**成对输出的 fail-fast**：见 §4.3.4 两条强制规则。impl 返回 `varianceScale == null` 而 `biasMeters != null` 时，core **直接按降级处理并发 `AI_DEGRADE` trace**，不猜测默认值。

#### 4.3.7 IFLC 统一门禁（core 侧，接口一与接口三共用）

**决策：门禁放在 core 的注入点，不放进 context 让 impl 判断。**

理由：`ionoopt` 是**解算配置**，不是「本历元的观测事实」。把它塞进 `FixQualityContext` 会让接口一的 impl 需要理解一个与特征无关的 core 枚举，且**两个 impl 都要各自实现一遍门禁**——一旦其中一处漏写，IFLC 下就会用消电离层组合量去套「逐频点偏差」的模型，输出无意义但不报错。门禁集中在 core 侧，只有一处，且与 §3.4「未注入 = 不干预」是同一语义。

**定死的门禁行为**（`opt.ionoopt == Constants.IONOOPT_IFLC` 时）：

| 接口 | 门禁行为 | 等价于 |
|---|---|---|
| 接口三 | **不调用 `ObsCorrector.correct()`**，`aiBias`/`aiScale` 保持 `null` | 未注入 |
| 接口一 | **不调用 `FixValidator.validate()`**，本历元按 `accept` 处理（**不拒绝固定**） | 未注入 |

**为什么接口一按 `accept` 而非 `reject`**：门禁是「该接口在本配置下不可用」，不是「本历元判定为错误固定」。默认放行保持 I-1（IFLC 下若未注入，行为与 v3.4.0 逐位一致），若按 `reject` 处理则等于 AI 在 IFLC 场景下**无条件禁用固定解**——这是比不启用更激进的干预，且与 §3.4「禁止 fail-closed」直接冲突。

**trace**：每会话一次 `AR/AI_DEGRADE`，`reason=ionoopt=IFLC`，受 `AiOpts.traceDegrades` 控制。**只发一次**，不要每历元刷屏（IFLC 是全配置生效，不是偶发事件）。

**门禁判定位置**：`relpos` 内已有 `nf` 局部量（[L152](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L152)），接口三门禁复用同一判断即可，**不重复读 `opt.ionoopt`**：

```java
boolean aiUsable = (opt.ionoopt != Constants.IONOOPT_IFLC);
```

---

### 4.4 SPI 完整定义（可直接编译）

**位置**：`rtklib-core/src/main/java/org/rtklib/java/ai/spi/`。**零第三方依赖**，只用 JDK 类型（`int` / `double` / `double[]` / `record`），故 core 编译期不引入任何 AI 生态。

#### 4.4.1 接口一：`FixValidator`

```java
package org.rtklib.java.ai.spi;

public interface FixValidator {
    /**
     * 固定质量校验。返回置信度，范围 [0,1]，1.0 = 完全信任（等价于不干预）。
     * 禁止抛异常：任何异常由 core 侧捕获并按 1.0 处理（§3.4 降级）。
     * 禁止在此方法内修改任何 Rtk / Ssat 状态。
     */
    double validate(FixQualityContext ctx);
}
```

**为什么只输出 `confidence` 而不输出 `accept`**（D9）：若 SPI 输出布尔，阈值判断就有两处（impl 内一处、core 内一处），两处不一致时会出现「impl 认为该拒、core 认为该收」的静默矛盾；且阈值随数据变化需调参时，impl 重新训练才能改阈值。只输出连续量，**阈值永远在 core 的 `AiOpts` 里**，调阈值不需要换模型。

#### 4.4.2 接口三：`ObsCorrector` + `CodeCorrection`

```java
package org.rtklib.java.ai.spi;

public interface ObsCorrector {
    /**
     * 批量推理：一次调用覆盖 ns*nf 个「卫星-频点」组合。
     * 返回 null，或返回值的数组长度 != ctx.ns()*ctx.nf() → 视为降级（bias=0, scale=1）。
     * 禁止逐卫星回调（每历元只允许一次调用，§3.3）。
     */
    CodeCorrection correct(ObsCorrectionContext ctx);
}
```

```java
package org.rtklib.java.ai.spi;

/**
 * 行序即索引：idx = i*nf + frq，i 为卫星在 relpos 的 sat[] 中的下标（0-based）。
 * 两个数组长度必须都等于 ns*nf；任一为 null 即视为降级。
 */
public record CodeCorrection(double[] biasMeters, double[] varianceScale) {
    public static final CodeCorrection NONE = new CodeCorrection(null, null);
}
```

**`CodeCorrection` 不含卫星号/频点号字段**：若带卫星号，core 就要做「卫星号 → 下标」映射，而 `refIdx`/`j` 是下标（[L1585-L1606](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1585-L1606)），映射一旦写错就是静默错行。**行序约定是唯一索引**，core 与 impl 共用同一份 `ctx.sats()` 顺序，零映射。

#### 4.4.3 `ObsCorrectionContext`（接口三输入）

```java
package org.rtklib.java.ai.spi;

public record ObsCorrectionContext(
        int epoch,          // rtk.epoch
        int ns,             // 参与双差的卫星数（relpos 局部变量 ns）
        int nf,             // 频点数（IFLC 时恒 1，此时两接口均被门禁屏蔽，§4.3.7）
        double dt,          // 采样间隔（秒）
        SatFeature[] sats) { }   // 长度 == ns，顺序 == relpos 的 sat[] 顺序
```

```java
package org.rtklib.java.ai.spi;

/** 单星特征。所有长度 nf 的数组均由 core 按 nf 分配并填充，impl 不得写入。 */
public record SatFeature(
        int sat,             // 卫星号，仅用于 trace/调试，不参与索引
        int sys,             // SatUtils.satsys(sat, null)
        double az,           // azel[iu[i]*2]      —— 工作缓冲区，非 ssat.azel
        double el,           // azel[iu[i]*2 + 1]
        double[] snrRover,   // ssat[sat-1].snrRover
        double[] snrBase,    // ssat[sat-1].snrBase
        double[] resp,       // ssat[sat-1].resp    —— 上一历元伪距残差
        double[] resc,       // ssat[sat-1].resc    —— 上一历元载波残差
        int[] lock,          // ssat[sat-1].lock
        int[] slip,          // ssat[sat-1].slip
        int[] half,          // ssat[sat-1].half
        double[] gf,         // ssat[sat-1].gf
        int[] vsat,          // ssat[sat-1].vsat
        int[] fix) { }       // ssat[sat-1].fix     —— 0未固定/1浮点/2固定/3已锚定
```

#### 4.4.4 `FixQualityContext`（接口一输入）

```java
package org.rtklib.java.ai.spi;

public record FixQualityContext(
        int epoch,          // rtk.epoch；供 impl 去重/trace，不是特征（见下）
        int ns,             // rtk.sol.ns（L442-L451 统计的非零 vsat 卫星数）
        int nf,             // 频点数；供 impl 归一化/对账，不是特征（见下）
        int nb,             // manage_amb_LAMBDA 返回值（L462）
        float ratio,        // rtk.sol.ratio（L1970/L1880）
        double pdop,        // 局部重算，见 §5.1
        double adrRms,      // 模糊度差 RMS，见 §5.1
        double prrRms,      // 载波残差 RMS，见 §5.1
        double pcrRms,      // 伪距残差 RMS，见 §5.1
        int nCodeRows,      // pcrRms 的有效行数（0 表示无有效伪距行），不是特征
        int nPhaseRows) { } // prrRms 的有效行数（0 表示无有效载波行），不是特征
```

**`nfix` 不在本 record 里，且不得加**：`rtk.nfix` 是「连续满足固定条件的历元计数」，**不是 §5.1 的 6 维特征之一**。把它塞进 context 会让 impl 拿到一个无处安放的字段（要么当特征用 → 特征集悄悄从 6 维变 7 维，§8 阶段 2 的 `6→16→1` 与 §6.2 的 `N = 5` 全部失效；要么忽略 → 字段纯冗余，且诱导后续实现者「顺手用一下」）。若将来确实要把它作为特征（它衡量固定状态连续性，对「稳定错误固定」有判别力），必须走 §10 决策流程，同步改 §5.1 维数、§8 输入维、§6.2 列数，**不允许局部加字段**。

**字段与特征的对应关系（定死，避免字段漂移）**：

| context 字段 | 对应特征 | 说明 |
|---|---|---|
| `adrRms` | f0 `adr` | |
| `prrRms` | f1 `prr` | 有效性由 `nPhaseRows > 0` 判定 |
| `pcrRms` | f2 `pcr` | 有效性由 `nCodeRows > 0` 判定 |
| `ratio` | f3 `ratio` | 对照基线 |
| `nb` + `ns` | f4 `ns_fixed` | **`ns_fixed = nb / ns` 由 impl 做除法**，core 不预先算 |
| `pdop` | f5 `pdop` | |
| `nCodeRows` / `nPhaseRows` | — | **不直接入特征**：它们只用于判定 `pcrRms`/`prrRms` 是否可信（`0` = 该 RMS 是占位值而非真实测量） |
| `nf` | — | **不直接入特征**：供 impl 做特征归一化、调试输出，以及与 `nCodeRows`/`nPhaseRows` 对账（无剔除时应满足 `nCodeRows + nPhaseRows = (ns - 1) * nf * 2`，见 §5.1 `v[]` 行数规则） |
| `epoch` | — | **不直接入特征**：供 impl 去重、trace 与短窗跨会话重置使用（§5.3） |

`nCodeRows == 0` 时 `pcrRms` 必须填 `0.0`，且**模型必须能处理该退化输入**（单频 DGPS 场景可能无载波行）。用 `0.0` 而非 `NaN`：`NaN` 进入 ONNX 会污染整个 batch 输出。

**为什么 `ns_fixed` 的除法留在 impl**：core 侧算就得引入 `double` 字段，而 `nb`/`ns` 都已在 record 里；在 impl 里算不增加接口面，且 `ns == 0` 的退化（本分支不会出现，但 impl 不应依赖该前提）由 impl 自己兜底为 `0.0`。**`ns <= 0` 时 `ns_fixed` 必须取 `0.0`，不得除零**。

#### 4.4.5 字段来源与取值时机（接口一，精确到行）

插入点：`if (valOk) {`（[L477](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L477)）之后、`if (++rtk.nfix >= opt.minfix)`（[L478](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L478)）之前。

| 字段 | 来源 | 陷阱 |
|---|---|---|
| `ratio` | `rtk.sol.ratio`（L1970 / L1880） | 已是**本历元**值，可直取 |
| `ns` | `rtk.sol.ns`（L442-L451） | 是「非零 `vsat` 卫星数」，不是 `relpos` 的局部 `ns` |
| `nf` | `relpos` 的局部 `nf`（[L152](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L152)） | 已是 IFLC 归一后的值（IFLC 时恒 1）；**不要用 `opt.nf`**，否则 IFLC 下会与实际链路不符 |
| `nb` | `nb`（L462） | `nb <= 1` 时不会进入本分支（L467 守卫） |
| `adrRms` | `rtk.x` vs `xa_arr`，索引 `IB(sat[i], f, opt)`（[L703](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L703)） | 只统计 `x[ib] != 0 && xa_arr[ib] != 0` 的项；`xa_arr` 是 L460 的工作缓冲区，插入点仍在作用域内 |
| `prrRms` / `pcrRms` | L473 那次 `ddres` 的 `v[]` + `vflg[]` + `nv` | 解码规则见 §5.1；**`vflg` 没有有效位** |
| `pdop` | `wb.azel` + `RtklibCommon.dops`（[L596](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L596) 同签名） | **`rtk.sol.pdop` 恒为 0**，见 §5.1，禁用 |
| `epoch` | `rtk.epoch` | 直取 |

**插入点为什么必须在 L478 自增之前**：`if (++rtk.nfix >= opt.minfix)` 这个分支体在接口一拒绝时会被整体跳过（§4.4.2）。若插入点放在 L478 之后，`nfix` 已自增，拒绝就无法阻止计数推进——本历元会被计为一次成功固定，`minfix` 门槛与 `rtk.nfix` 的语义都被破坏。**插入点在 L477 之后、L478 之前是唯一正确位置**，与 `nfix` 是否入特征无关。

**接口一与接口三同时开启时的耦合**：接口三的偏差施加在 `v[nv]` 上（§4.3.6），因此 L473 的 `v[]` 已是**改正后**残差，`prr`/`pcr` 随之变化。这是设计选择（接口一看到的是「已改正的世界」），但**训练与推理必须固定在同一开关组合下**，禁止「关接口三采特征、开接口三推理」。

---

### 4.5 `AiOpts` 字段定义

`AiOpts` 挂在 `RtkConfig.ai`，`Serializable`，**逐字段 `copyFrom`**（§4.5.3）。

#### 4.5.1 字段表

| 字段 | 类型 | 默认值 | 取值范围 | 生效位置 |
|---|---|---|---|---|
| `fixValidatorMinConfidence` | `double` | `0.5` | `[0.0, 1.0]` | 接口一判定：`accept = confidence >= 该值`（L477 分支内） |
| `varianceScaleMax` | `double` | `4.0` | `[1.0, +∞)` | 接口三方差膨胀上限钳位（§4.3.4） |
| `obsCorrectorMaxBias` | `double` | `10.0` | `(0.0, +∞)`，`≤ 0.0` 时钳到 `10.0` 并告警 | 接口三偏差幅值钳位（§4.3.4）：任一行满足 `Math.abs(biasMeters) > 该值` 时，**该行 `biasMeters` 置 0**（`varianceScale` 仍按原值施加，因为它只膨胀不缩小），并发一次 `AI_DEGRADE` trace |
| `traceRejects` | `boolean` | `true` | — | 接口一拒绝时是否发 `AR/AI_REJECT` trace（§4.1 审计） |
| `traceDegrades` | `boolean` | `true` | — | 降级时是否发 `AR/AI_DEGRADE` trace（每会话一次） |

**没有 `enableXxx` 布尔量**（§3.2）：开关由「是否注入了对象」兼任。`AiOpts` 里出现 `enable` 字段就意味着一份「开了但没生效」的配置合法存在，这是要消除的非法态。

**没有 `modelPath`**：模型路径属于**实现**（`rtklib-ai` 的 `OnnxAiProvider` 构造参数），不属于 core 的配置。放进 `AiOpts` 会让 core 侧 `RtkConfig` 携带一个它永远不读的字符串，并诱导「配置即能启用 AI」的误解。

**没有推理超时字段**：Java 侧对同步调用做超时只能靠额外线程，代价远大于收益；超时由 impl 自行保证（模型体积与输入规模在阶段 2 定死）。

#### 4.5.2 校验

`AiOpts` 在**首次被读取时**做一次范围校验（不是构造时，避免反序列化默认值触发）：

```java
if (fixValidatorMinConfidence < 0.0 || fixValidatorMinConfidence > 1.0) → 钳到 [0,1] + 告警
if (varianceScaleMax < 1.0) → 钳到 1.0 + 告警
if (obsCorrectorMaxBias <= 0.0) → 钳到 10.0 + 告警
```

钳位而非抛异常：配置错误不应让解算崩溃（与 §3.4「AI 只允许保守」一致）。

#### 4.5.3 `copyFrom`（必须实现，否则重建即丢参数）

`RtkConfig` 是逐字段 `copyFrom`，`createRtk` 每次解算都重建 `Rtk` 并拷贝配置（[PostPosProcessor.java#L505-L516](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rinex/PostPosProcessor.java#L505-L516)）。若 `AiOpts` 不参与拷贝，前向/反向两次 `createRtk` 会把参数重置为默认值，表现为「配置里改了阈值但行为没变」。

```java
public void copyFrom(AiOpts o) {
    if (o == null) return;
    this.fixValidatorMinConfidence = o.fixValidatorMinConfidence;
    this.varianceScaleMax          = o.varianceScaleMax;
    this.obsCorrectorMaxBias       = o.obsCorrectorMaxBias;
    this.traceRejects              = o.traceRejects;
    this.traceDegrades             = o.traceDegrades;
}
```

并在 `RtkConfig.copyFrom` 内加一行 `this.ai.copyFrom(other.ai);`（`ai` 字段**不得为 `null`**，默认 `new AiOpts()`；用 `null` 表示「未配置」会引入第三种状态）。

---

## 5. 特征定义（定死）

特征集必须在设计阶段一次定死，否则阻塞特征提取与训练管线搭建。此处全部定死，依据为「物理可解释 + 现有字段可直取」。

### 5.1 接口一：6 维固定后残差类特征（决策 D2：选方案 B）

**决策：采用方案 B（残差类特征 + 少量传统量），放弃方案 A（7 维统计量）。**

| # | 特征 | 定义 | 来源（精确到行） | 物理解释 |
|---|---|---|---|---|
| 1 | `adr` | 模糊度差 RMS：`sqrt(mean((x[ib] − xa_arr[ib])²))`，`ib = IB(sat[i], f, opt)`，只取 `x[ib] != 0 && xa_arr[ib] != 0` 的项 | `rtk.x` 与 `xa_arr`（[L460](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L460)、`manage_amb_LAMBDA` 输出）；索引函数 [IB](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L703) | 整数解离浮点解多远 |
| 2 | `prr` | 相位残差 RMS：L473 那次 `ddres` 的 `v[]` 中 `isCode == 0` 行的 RMS | `v[]` / `vflg[]`（[L1766](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1766)） | 整数解与相位的拟合一致度 |
| 3 | `pcr` | 伪距残差 RMS：同上 `isCode == 1` 行 | 同上 | 整数解与伪距的拟合一致度 |
| 4 | `ratio` | LAMBDA ratio | `rtk.sol.ratio`（[L1970](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1970) / [L1880](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1880)） | 搜索空间可分性（保留作对照基线） |
| 5 | `ns_fixed` | `nb / rtk.sol.ns` | `nb`（[L462](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L462)）、`rtk.sol.ns`（[L442-L451](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L442-L451)） | 几何冗余度 |
| 6 | `pdop` | 位置 DOP，**局部重算**（`rtk.sol.pdop` 恒 0，不可用） | `wb.azel` + `RtklibCommon.dops`（[L596](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L596) 同签名） | 几何强度 |

**`vflg` 解码规则（[L1766](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1766)）**——`vflg[nv]` 只有一个位域，**没有「有效位」**：

```java
int satRef    = (vflg[nv] >> 16) & 0xFF;
int satJ      = (vflg[nv] >>  8) & 0xFF;
boolean isCode = ((vflg[nv] >> 4) & 0x01) == 1;
int frq        =  vflg[nv] & 0x0F;
```

`v[]` 中每一行都是**有效行**：无效行在 `nv++` 之前就 `continue` 了（[L1740](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1740) 的 `maxinno` 剔除、`validobs` 过滤等），因此不存在「按位过滤」这一步。**误把 `vflg & 1` 当作有效位（它其实是 `frq`）会静默丢掉所有奇数频点的行**，`prr`/`pcr` 变成半数样本的 RMS 而不报错。

**`pdop` 为什么必须局部重算**：`rtk.sol.pdop` 的赋值在 [L586-L598](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L586-L598)，输入是 `rtk.ssat[i].azel`；而 `ssat[].azel` 在**全仓库范围内从未被赋值**（`Ssat` 构造只分配 `new double[2]`，`zdres`/`ddres` 都不写它）。故 `dopNs` 恒为 0、`rtk.sol.pdop` **恒为 0**。仰角必须取自 `relpos` 的工作缓冲区 `wb.azel`（[L194](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L194)），按 `iu[i]` 索引：

```java
double[] dopAzel = new double[Constants.MAXSAT * 2];
int dopNs = 0;
for (int i = 0; i < ns; i++) {
    dopAzel[dopNs * 2]     = azel[iu[i] * 2];
    dopAzel[dopNs * 2 + 1] = azel[iu[i] * 2 + 1];
    dopNs++;
}
double[] dop = new double[4];
RtklibCommon.dops(dopNs, dopAzel, opt.elmin, dop);   // 与 L596 同签名
double pdopLocal = dop[1];                            // dop = {gdop, pdop, hdop, vdop}
```

**不得回写 `rtk.sol.pdop`**：回写会改变 `sol` 输出与 `RESULT` trace 的 `pdop` 字段，破坏 I-1 的日志基线（§8）。

**`prr`/`pcr` 必须取自 `v[]` 而非 `ssat.resp/resc`**：`ddres` 入口把 `resp`/`resc` 清零（[L1572-L1573](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1572-L1573) 附近），且只在**非参考星**上写值（[L1718](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1718)/[L1720](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1720)），参考星恒为 0。用 `resp`/`resc` 会系统性低估 RMS（少掉全部参考星行），且 `resp`/`resc` 是**按频点**存（长度 `nf`），无法区分 code/phase 行。

**选 B 的理由**：

1. **覆盖 ratio 的盲区**（§2.1）：ratio 高但 `prr` / `pcr` 系统性偏大，正是错误固定的典型形态。方案 A 的 7 维里 3 个（ratio / ADOP / PDOP）都是**搜索空间或几何**的量，与现有门限高度共线，边际信息少。
2. **文献可比**：方案 B 有公开的、同特征集的实测结果可作对照基线（尽管不可作为本项目验收指标）；方案 A 的 7 维特征混用了两套不同来源的工作，两套之间不可混用，直接组合会引入来源不一致的风险。
3. **字段可直取**：`adr` / `prr` / `pcr` 全部来自固定分支已算出的量，零额外计算（§3.3 位置处 `v`、`xa`、`x` 均在作用域内）。

**保留 `ratio` 的用途**：只作对照基线，用于评估「ML 是否真的超过现有门限」，不作主特征。若消融实验显示去掉 `ratio` 后性能不变，说明 ML 学到了新信息；若性能大降，说明它只是在拟合 ratio，应重新审视。

### 5.2 接口二：冻结，特征列不定

接口二冻结（D1），不定义特征列。若将来重开，候选集与 §5.1 前 3 项重叠，届时重新评估是否合并。

### 5.3 接口三：逐卫星逐频点特征（决策 D3）

**决策：v1 用 MLP，单历元 + 短窗聚合；1D-CNN 暂不做。**

| # | 特征 | 来源（精确到行/字段） | 说明 |
|---|---|---|---|
| 1 | 高度角、方位角 | `wb.azel`，按 `iu[i]` 索引（工作缓冲区 [L194](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L194)；由 `zdres` 在 [L1222-L1223](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1222-L1223) 写入） | **不是** `ssat[].azel`——该字段恒为 0（§5.1） |
| 2 | 流动站 SNR、基准站 SNR | `ssat[sat-1].snrRover[f]`、`snrBase[f]` | 每历元由 `zdres` 写 |
| 3 | 上一历元伪距残差、载波残差 | `ssat[sat-1].resp[frq]`、`resc[frq]` | **按频点存（长度 `nf`），不区分 code/phase 行**；只在非参考星写（[L1718](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1718)/[L1720](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1720)）；参考星恒 0；`ddres` 入口清零（[L1572-L1573](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1572-L1573) 附近） |
| 4 | 锁相时长 | `ssat[sat-1].lock[f]` | 每历元由 `udstate` 更新 |
| 5 | 周跳标志、半周标志 | `ssat[sat-1].slip[f]`、`half[f]` | 位标志（`LLI_SLIP` / `LLI_HALFC`），不是布尔 |
| 6 | 几何无关组合值 | `ssat[sat-1].gf[frq]` | **仅当 `opt.thresslip != 0` 时由 `detslpGf` 写**，否则恒 0；存的是当历元组合值，不是变化率 |
| 7 | 系统标识、卫星可用、模糊度状态 | `SatUtils.satsys(sat, null)`、`ssat[sat-1].vsat[f]`、`ssat[sat-1].fix[f]` | `fix`：0 未固定 / 1 浮点 / 2 固定 / 3 已锚定 |

**`mw` / `phw` 已从特征集中删除**：这两个字段**只由 PPP 路径写入**（`PppCore` / `PppRtkCore`），RTK 的 `relpos` 链路从不写它们，恒为 0。把它们当特征就是喂两列常量，白占模型容量并让特征重要性分析失真。

**为什么 v1 不用 1D-CNN**：`resp` / `resc` / `gf` 已经以标量形式携带了跨历元上下文（它们本身就是上一历元或跨频点构造量）。用 13 维单历元特征 + 4 维短窗聚合（近 5 历元均值 / 标准差 / 历史长度）即可表达，MLP 足够。1D-CNN 需要真正的残差序列输入，属 v2 议题；且纯 Java 手写前向传播的备选方案在 CNN 下成本显著上升，故 v1 不采用。

**输入向量定死为 17 维（每行 = 一个 `(sat i, frq)`）**：

| 序号 | 维度 | 取值 | 缺失/退化处理 |
|---|---|---|---|
| 0 | `el` | `SatFeature.el`（弧度转度） | 不缺失（`zdres` 已按 `elmin` 过滤） |
| 1 | `az` | `SatFeature.az`（度） | 不缺失 |
| 2 | `snrRover` | `snrRover[frq]` | 无观测填 `0`（`snr` 正常范围 0–60 dBHz，`0` 可区分） |
| 3 | `snrBase` | `snrBase[frq]` | 同上 |
| 4 | `resp` | `resp[frq]`（上一历元） | 参考星或首历元填 `0` |
| 5 | `resc` | `resc[frq]`（上一历元） | 同上 |
| 6 | `lock` | `lock[frq]`（秒） | `0` = 未锁定 |
| 7 | `slip` | `slip[frq] != 0 ? 1 : 0` | 位标志归一为 0/1 |
| 8 | `half` | `half[frq] != 0 ? 1 : 0` | 同上 |
| 9 | `gf` | `gf[frq]` | `opt.thresslip == 0` 时恒 0（**须在 `norm.json` 中标注该列可能恒零**） |
| 10 | `vsat` | `vsat[frq]` | 恒 1（进入本表的星已通过 `zdres` 过滤） |
| 11 | `fix` | `fix[frq]` | 0/1/2/3，按整数归一 |
| 12 | `sysId` | `Integer.numberOfTrailingZeros(sys)`，`sys` 是**位掩码**（[Constants.java#L156-L163](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/constants/Constants.java#L156-L163)：`SYS_GPS=0x01, SYS_SBS=0x02, SYS_GLO=0x04, SYS_GAL=0x08, SYS_QZS=0x10, SYS_CMP=0x20, SYS_IRN=0x40, SYS_LEO=0x80`），故 `sysId` 取值为 `GPS=0 SBS=1 GLO=2 GAL=3 QZS=4 CMP=5 IRN=6 LEO=7` | **不做 one-hot**：星座数会随版本增长，one-hot 会让输入维漂移；且**绝不能直接喂 `sys` 原始位掩码**（`0x01/0x04/0x08` 在数值上毫无序关系，MLP 会学出「GAL 比 GLO 大」这种伪关系） |
| 13 | `respWinMean` | 近 5 历元 `resp` 均值 | 历史不足 5 时按已有历元数取均值 |
| 14 | `respWinStd` | 近 5 历元 `resp` 标准差 | 不足 2 个样本填 `0` |
| 15 | `rescWinMean` | 近 5 历元 `resc` 均值 | 同 13 |
| 16 | `histLen` | 当前窗口内实际历元数 / 5，取值 `[0.2, 1.0]` | 让模型能区分「均值真的稳」与「只有 1 个样本」 |

**短窗状态住在 impl，不住 core**：core 的 `ObsCorrectionContext` 只给**当前历元快照**（§4.4.3），窗口由 `ObsCorrectorImpl` 自己按 `(sat, frq)` 维护一个长度 5 的环形缓冲。定死三条重置规则，否则窗口会跨周跳/跨数据缺口污染：

1. `epoch` 间隔 > `1.5 * dt`（数据缺口）→ 清空该星全部窗口
2. `slip[frq] != 0`（该频点周跳）→ 只清空该 `(sat, frq)` 的窗口
3. `ctx.sats()` 中不再出现的 `sat` → 从缓冲区移除（避免无界增长）

**`dt` 的唯一来源是 `ctx.dt()`，且必须兜底**：`dt` 取自 `relpos` 的 `TimeSystem.timediff(obs[0].time, obs[nu].time)`（[L221](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L221)），`ddres` 用的也是同一个值。impl **不得自行从历元时间戳推算 `dt`**（那会引入第二套时间基准，与 core 的判定不一致）。

`dt <= 0` 或非有限时 `1.5 * dt` 的判定失效（负值会让所有历元都被判为「缺口」，`0` 会让任何间隔都判为缺口），故定死：

```java
double dt = ctx.dt();
boolean dtUsable = Double.isFinite(dt) && dt > 0.0;
// dtUsable == false → 按「清空全部窗口」处理（含当前历元重建），
//                     该历元 histLen 按 1 个样本计（即 0.2）
```

**为什么 `dt` 不可用时清空而不是沿用上一次的 `dt`**：沿用一个不可信的值会让窗口跨越真实数据缺口却不自知，等于把缺口前后的残差混在一起做均值——这是静默的标签污染。清空的代价只是该历元退化为单样本（`histLen = 0.2`），模型对此已有显式输入维度（f16），代价可接受。

**窗口长度 5 是定值，不做自适应**：它同时决定了 impl 的常驻内存上界（`ns_max × nf × 5 × 2 double` ≈ 1 KB 量级），可忽略。

**归一化要求**：所有特征必须在**训练集上拟合**归一化参数并固化到模型旁（`norm.json`），推理侧原样加载。禁止推理侧按运行数据动态归一化——那会让同一模型在不同数据上行为漂移。

**批量约束**：模型必须支持**一次调用处理 `ns × nf` 行**（而非逐卫星调用）。这是 §3.3 / §4.3.4「每历元批量推理 + `varerr` 只读」的结构前提，也是 G-5（单历元 ≤ 2 ms）成立的条件。若模型导出后不支持动态 batch，须在 ONNX 导出时固定 `batch = ns_max × nf` 并对不足的行做 mask。

---

## 6. 训练管线与标签

### 6.1 目录布局

```
rtklib-core/src/main/java/org/rtklib/java/ai/spi/
├── AiProvider.java                       # 注入回调（§3.2）
├── AiHooks.java                          # 注入点（ThreadLocal，§3.2）
├── FixValidator.java                     # 接口一 SPI（§4.4.1）
├── FixQualityContext.java                # 接口一输入（§4.4.4）
├── ObsCorrector.java                     # 接口三 SPI（§4.4.2）
├── ObsCorrectionContext.java             # 接口三输入（§4.4.3）
├── SatFeature.java                       # 单星特征（§4.4.3）
└── CodeCorrection.java                   # 接口三输出（§4.4.2）

rtklib-ai/
├── src/main/java/org/rtklib/java/ai/
│   ├── OnnxAiProvider.java               # 实现 AiProvider（组装下面两个实现）
│   ├── inference/OnnxInference.java      # 统一推理适配层（含批量调用）
│   ├── feature/FixQualityFeatures.java   # 接口一特征提取（§5.1）
│   ├── feature/ObsCorrectionFeatures.java# 接口三特征提取（§5.3）
│   ├── FixValidatorImpl.java             # 实现 FixValidator
│   └── ObsCorrectorImpl.java             # 实现 ObsCorrector
├── training/                             # 训练管线（Python，与 Java 侧独立）
│   ├── common/dataset.py                 # 统一数据格式
│   ├── common/export_onnx.py             # 统一导出
│   ├── common/metrics.py                 # 固定率/错误固定率/RMS
│   ├── fix_validator/train.py
│   └── obs_corrector/train.py
└── models/                                # 示例 .onnx（仅演示，禁止直接部署）
```

**注意**：不建 `AiAdvisorImpl` 门面。接口一与接口三各自实现各自 SPI，由 `OnnxAiProvider` 组装后一次性注入（§3.2）。

### 6.2 数据格式

统一 Parquet（列式、可校验 schema）：

- 特征列：`f0..fN`，列名与 §5 编号一致（接口一 `N = 5`（6 维，§5.1）；接口三按 §5.3 编号）
- 标签列：`label`
- 元数据列：`epoch`、`sat`、`frq`、`station`、`day`、`solution_stat`

**行粒度**：

| 接口 | 一行代表 | `sat` / `frq` |
|---|---|---|
| 接口一 | 一个历元（固定解产出时） | 留空（`-1`） |
| 接口三 | 一个「历元 × 卫星 × 频点」 | 必填 |

**元数据不是可选项**：`station` 与 `day` 是 §7.3 防泄漏切分的依据，缺失则无法做可信评估。接口三的 `sat` 与 `frq` 用于与推理侧 `idx = i*nf + frq` 行序对账——采样与推理的行序约定必须一致，否则训练标签与推理特征错位。

### 6.3 标签定义

**接口一**：固定解位置与参考真值偏差 > 阈值 → `label = 1`（错误固定）。

- 阈值 **0.1 m 为初始值，须在标定集上复核**。理由：静态站的水平精度实测可达厘米级，0.1 m 对静态数据可能过宽，会把「明显错误」和「刚好超差」混为一类。标定方法：在标定集上画「固定解偏差」的累积分布，取双峰之间的谷底或 99 分位，并记录最终取值与依据。
- 真值来源：已知坐标的静态站（`DATA_GUIDE.md §0.7` 的私有 4 站数据集可满足）。

**接口三**：逐卫星伪距误差真值。使用者通常无法直接获取。参考方案：

1. **DGPS 差分隔离**：要求至少一个参考站与目标站同步观测且可构造短基线；**单站场景不成立**。
2. **参考轨迹反算残差**：需高精度参考轨迹。
3. **惯导组合**：需高精度惯导。

**示例数据集只证明链路跑通，不承诺模型效果**，且须在文件头标注「仅演示」。

---

## 7. 评估协议

仅对比「固定率、错误固定率、RMS」不足以判断净收益——均值改善可能掩盖单站恶化，单次运行可能掩盖时序不稳定。故补足四条。

### 7.1 对照组必须齐全

同一数据、同一配置下跑四组：

| 组 | 配置 |
|---|---|
| A 原生 | `AiOpts` 全默认（v3.4.0 行为） |
| B 接口一 | 仅开接口一 |
| C 接口三 | 仅开接口三 |
| D 组合 | 接口一 + 接口三 |

缺 D 组就无法判断两者是否相互抵消（接口三升固定率、接口一降假固定，叠加后可能互相掩盖）。

### 7.2 三指标同看，不得只看一个

- 固定率（`SOLQ_FIX` 历元占比）
- **错误固定率**（固定解偏差 > 阈值占比，分母取固定历元数）
- 位置 RMS（水平 / 垂直）

接口一的正确表述是「用固定率换错误固定率」，因此**单看固定率下降不能判定失败**，单看错误固定率下降也不能判定成功。必须给**联合判据**（§7.4）。

### 7.3 两层切分：必须按站切分，禁止随机切分

GNSS 相邻历元高度相关（同卫星、同几何、同大气），随机切分训练/测试会造成严重泄漏，得到虚高的评估结果。

但**仅按天切分同样不够**。接口一、接口三的特征绝大多数是**站址相关**的：多路径是站址的物理属性，`snrRover` 是该站接收机/天线的硬件特性，`resp`/`resc` 与 `gf` 也带站址指纹。同一站不同天的数据进不同集合时，模型仍能学到「这个站的硬件指纹」而不是「这是错误固定」，于是站内交叉验证漂亮、换站即失效。

因此定死为**两层切分**：

| 层 | 规则 | 作用 |
|---|---|---|
| 第一层（必须） | **不同 `station` 进不同集合**：训练站、验证站、测试站互不重叠 | 保证评估测的是跨站泛化，而非站内记忆 |
| 第二层（站内） | 同站内**再按 `day` 切分**（训练日 / 验证日） | 早停与超参选择不得跨站泄漏 |

强制要求：

- **禁止随机切分**（`sklearn` 默认的 `train_test_split` 直接不可用）
- 训练集 / 验证集 / 测试集三者**互不重叠**，且**不同站**
- 测试集至少包含 **2 个未参与训练、也未参与早停的站**（§7.4 G-3 判据）
- 元数据列 `station`、`day` 缺失的数据**不得进入评估**（§6.2）

### 7.4 净收益判据（拟定阈值，实施时复核）

接口一在 core 侧**内置注入**（即无需使用方显式注入也生效）须**同时**满足：

| # | 判据 | 拟定阈值 |
|---|---|---|
| G-1 | 错误固定率相对下降 | ≥ 30%（**取 95% 置信下界**，且须过样本量前置，见下文） |
| G-2 | 固定率绝对下降 | ≤ 1.0 个百分点 |
| G-3 | 跨站泛化 | ≥ 2 个**未参与训练、也未参与早停**的站均满足 G-1、G-2（§7.3） |
| G-4 | 最坏单站不恶化 | 任一站的错误固定率不上升 |
| G-5 | 推理开销 | 单历元 ≤ 2 ms（与文献量级一致；接口三按**每历元一次批量调用**计，非逐卫星累加） |

**G-4 是最重要的一条**：均值改善但某站恶化，说明模型在学场景特征而非错误模式，属不可部署。宁可整体收益小，不可局部恶化。

**G-1 必须带统计显著性，否则「相对下降 30%」可能是噪声**：错误固定本身是低频事件（典型场景下 A 组错误固定率常在 1%–5%），分母小，`30%` 相对下降在小样本下极易被随机波动跨过。定死三条：

1. **G-1 必须报告 95% 置信区间**。错误固定率是比例量，用 **Wilson score interval**（不要用正态近似——小比例、小样本下正态近似会给出负下界）。判据改为「**相对下降的置信下界 ≥ 30%**」，而非点估计 ≥ 30%。
2. **样本量前置条件（MDE）**：评估前先算最小可检测效应（MDE）。若 A 组错误固定事件数不足以在 95% 置信、80% 功效下检测出 30% 相对下降，则该测试集的 G-1 **不予判定**（既不算通过也不算失败），必须扩数据。给出可用的经验门限：A 组错误固定事件数 **< 100** 时不做 G-1 判定。
3. **A/B 必须是配对比较**。同一历元分别以「注入」与「未注入」跑两次（§8 的零回归基线天然提供未注入侧），按历元配对统计（McNemar 检验适用于「固定正确/错误」的二分类翻转），**不得跨数据集比较两个独立集合的比例**——那会把站点/日期差异混入判据。

**G-2 的「1.0 个百分点」同样要报置信区间**，但它衡量的是「不恶化」，方向保守，故用点估计 + 单侧上界即可，不要求 MDE 前置。

---

## 8. 实施阶段与实现状态

**本章是实现状态的权威表。** 与 §1 摘要冲突时以本章为准。

| 阶段 | 内容 | 依赖 | 状态 |
|---|---|---|---|
| 0 | 本文档定稿 | — | 已完成（2026-11-06） |
| 1 | core SPI（`AiProvider` / `AiHooks` / `FixValidator` / `ObsCorrector` + 上下文记录）+ `AiOpts` + **I-1 零回归验证** | 阶段 0 | 已完成（2026-11-06） |
| 2 | `rtklib-ai` 模块 + `OnnxInference` + `OnnxAiProvider` + 接口一接线（未注入 = 关） | 阶段 1 | 已完成（2026-11-07） |
| 3 | 接口一训练数据导出（§5.1 六维特征提取已在阶段 2 落地，本阶段补标签与样本落盘） | 阶段 2 | 未开始 |
| 4 | 接口一训练 + 评估（§7 全套协议） | 阶段 3 | 未开始 |
| 5 | 接口三（含不确定性传播 §4.3.4） | 阶段 4 通过 G-1~G-5 | 未开始 |
| — | 接口二 | — | **冻结**（D1） |

**阶段 1 是唯一阻塞项**，且验收标准只有一条：I-1 逐位一致。在 I-1 未验证前不得进入阶段 2——否则后续所有「零回归」声明都失去依据。

**阶段 1 工作量估计**：0.5–1 天（SPI 四个接口类型（`AiProvider`/`AiHooks`/`FixValidator`/`ObsCorrector`）+ 四个记录类型（`FixQualityContext`/`ObsCorrectionContext`/`SatFeature`/`CodeCorrection`）+ 一个配置类 `AiOpts` + 零回归对比测试）。无第三方依赖，无环境风险。

**阶段 1 的验收还须包含两条结构性检查**：

1. 零回归测试须在 `AiHooks.clear()` 之后运行，且 teardown 断言注入点为空——否则 I-1 可能被前一用例的残留注入掩盖（§9）。
2. `rtklib-core/pom.xml` 的依赖树中**不得出现 `rtklib-ai`**（`mvn dependency:tree` 断言），保证依赖方向单向（§3.2）。

**阶段 2 的关键风险**：ONNX Runtime 在 Java 17 下加载失败。应对：阶段 2 首日先做「`6→16→1` dummy MLP 导出 + 加载 + 推理」的最小验证（输入维与 §5.1 的 6 维一致）；失败则切备选方案——纯 Java 手写 MLP 前向传播（权重从 JSON 加载，代码量 <100 行，接口一与接口三均适用，因 §5.3 已定为 MLP）。此决策须在阶段 2 内闭环并记录，不得悬空。

**阶段 2 风险决策（已闭环，2026-11-07）**：

> **结论：ONNX Runtime 1.17.3 在 Java 17 下加载与推理正常，不启用纯 Java MLP 备选方案。**
> 依据：`6→16→1` dummy MLP 导出后由 Java 侧加载，三组样本输出与 Python（numpy 双精度）前向参考值的偏差 < `1e-6`（两条路径算子实现不同，故以容差而非逐位相等为契约）。备选方案不实施，仅在 ONNX Runtime 未来出现兼容性问题时重新评估。

**阶段 2 的验收证据（2026-11-07）**：

| 检查项 | 结果 |
|---|---|
| `rtklib-ai` 模块内自洽测试（ONNX 加载 / 特征顺序 / 归一化 / provider 降级） | 21/21 通过 |
| 跨模块流程测试（`rtklib-test`：P1 不注入=原生、P2 全接受逐位一致、P3 全拒绝生效、P4/P4b 异常与非有限置信度降级、P5 默认阈值、P6 阈值传播） | 6/6 通过 |
| I-1 零回归（`rtklib-core` 全量测试） | 398 通过 / 0 失败 / 0 错误（51 因数据缺失跳过） |
| 依赖方向单向（`mvn -pl rtklib-core dependency:tree`） | 无 `rtklib-ai`、无 `onnxruntime` |

---

## 9. 风险

| 风险 | 说明 | 应对 |
|---|---|---|
| ONNX Runtime 兼容性 | Java 17 下 native 库加载失败 | 阶段 2 首日最小验证；备选纯 Java 前向传播（§8）。**已闭环（1.17.3 验证通过，2026-11-07）** |
| 接口三过度自信 | 只改偏差不膨胀方差 → 假固定率上升 | §4.3.4 强制单侧膨胀 + 成对输出 fail-fast |
| 训练泄漏 | 随机切分导致虚高评估 | §7.3 两层切分：**第一层必须按站**，站内再按天；禁止随机切分 |
| 标签泄漏 | 真值派生量混入特征 | §4.1 契约要点 2 明令禁止 |
| 场景过拟合 | 学到「这是哪个站」而非「这是错误固定」 | §7.4 G-3/G-4 跨站泛化判据 |
| 示例模型被误用 | 使用者直接部署示例 `.onnx` | 三句免责声明（§1.1）+ `models/` 目录标注「仅演示」 |
| 依赖污染 | ONNX 进入 core 拖累所有下游 | §3.1 core 只放零依赖 SPI |
| 政策冲突 | 与 ROADMAP「仅 bugfix」冲突 | §1.4 适用范围界定 + 默认关闭硬规则 |
| 泛化局限 | GNSS-ML 跨场景泛化差是领域共识 | 交付管线而非结果，泛化责任在使用者 |
| **注入点测试污染** | 用例注入后未清理，其后「原生行为」用例实际带 AI 跑且全绿 → I-1 失去可信度 | `AiHooks.clear()` + teardown 断言注入点为空；`ThreadLocal` 保证线程隔离 |
| **热路径推理** | 接口三若在 `varerr` 内推理，单历元增加 90–140 ms | §3.3 / §4.3.4 强制「每历元批量算 + `varerr` 只读查表」 |

---

## 10. 决策记录

| # | 决策 | 结论 | 依据 | 重开条件 |
|---|---|---|---|---|
| D1 | 接口二是否实施 | **冻结** | §2.2 已被 `arfilter`/`excsat` 覆盖；§4.2 净收益方向不明 | §4.2 两条重开条件任一满足 |
| D2 | 接口一特征集 A/B | **选 B**（ADR/PRR/PCR + ratio/ns/pdop） | §5.1 覆盖 ratio 盲区；字段可直取；文献可比 | 消融显示 `ratio` 贡献压倒性 → 重新审视 |
| D3 | 接口三模型形态 | **v1 用 MLP**，1D-CNN 延后 | §5.3 时序上下文已由 `resp`/`resc`/`gf` 标量化 | 出现必须用残差序列才能表达的模式 |
| D4 | AI 代码放哪 | **新建 `rtklib-ai`**，core 只放 SPI | §3.1 避免 ONNX 污染 core | 无 |
| D5 | 注入方式 | **注入回调**：core 定义 `AiProvider` + `AiHooks`（`ThreadLocal`），`rtklib-ai` 实现并注入；参数在 `RtkConfig.ai`；**不设 `enable` 布尔量** | §3.2 外部注入需穿透 Processor 才能到 `RtkCore`；`createRtk` 重建会丢 `transient` 字段（既有 `traceCallback` 已在 PostPos 丢失）；`varerr` 入参是 record 且 `joint` 在 Processor 体系外；回调接口使依赖方向严格单向，避免 core 与 `rtklib-ai` 相互引用 | 需要跨线程池传播或进程级多租户强隔离时，改为纯实例字段注入 |
| D6 | 是否引入 Adam 等一阶优化器 | **不引入** | 不产出协方差（GNSS 必需）；`R` 已按方差加权，自适应分母破坏统计意义；与 §1.2 边界一致 | 引入需输出协方差的学习模块时重新评估 |
| D7 | 假固定阈值 0.1 m | **初始值，须标定** | §6.3 静态站精度可能使 0.1 m 过宽 | 标定完成后以标定值为准 |
| D8 | 默认开启条件 | **必须过 §7.4 闸门 + ROADMAP 登记** | §1.4 政策约束 | 无 |
| D9 | `accept` 判定归谁 | **core 按 `AiOpts.fixValidatorMinConfidence` 判定；SPI 只输出 `confidence`** | §3.4 / §4.4.1 若 SPI 输出布尔，阈值判定出现两处，不一致即静默矛盾；且调阈值需重新训练 | impl 需要输出「硬拒绝」且不可被阈值覆盖时（当前无此需求） |
| D10 | IFLC 门禁放哪 | **core 侧统一门禁**：`opt.ionoopt == IONOOPT_IFLC` 时两接口均不调用（接口一按 `accept` 放行）；**不把 `ionoopt` 加进 context** | §4.3.7 门禁是配置级判定而非历元事实；放 impl 会变成两处独立实现，漏一处即在 IFLC 下用组合量套逐频点模型且不报错 | impl 需要按星座/频点做差异化门禁时（当前无此需求） |

---

## 附：修订记录

### v1.1 → v1.2 修订（2026-11-06）

| 项 | v1.1 | v1.2 |
|---|---|---|
| 注入方式 | `AiRegistry` 静态注册表 + 实例字段 | **`AiProvider` 注入回调 + `AiHooks` 注入点**（§3.2）。依赖方向严格单向 `rtklib-ai → rtklib-core`，core 对 `rtklib-ai` 零引用；阶段 1 验收新增 `dependency:tree` 断言 |
| 接口三推理位置 | 写在 `relpos` 循环之前 | 改为**迭代首轮 `i == 0`、`zdres` 之后、首次 `ddres` 之前**（§3.3）。理由：`zdres` 在循环体内，循环前 `azel` 是上一历元值 |
| 接口三索引 | 按卫星号 `sat[i] - 1` | 改为**按 `sat[]` 下标 `idx = i * nf + frq`**（§4.3.3）。理由：参考星由 `ddres` 现场择优，同一历元 3 次调用可能不同 |
| 方差膨胀顺序 | 未明确 | 明确**必须在半周 0.01 补偿之后施加**（§4.3.4）。理由：补偿先于膨胀，否则 0.01 被过度放大 |
| 接口一特征 | 仅给字段名 | 补齐**精确到行的定义**：`vflg` 无「有效位」、`pdop` 必须局部重算、`prr`/`pcr` 必须取自 `v[]` 而非 `resp`/`resc`（§5.1） |
| 接口三特征 | 含 `mw` / `phw` / 「变化率」表述 | **删除 `mw`/`phw`**（RTK 链路从不写，恒 0）；`gf` 改为「当历元组合值」且注明依赖 `opt.thresslip`；仰角改取 `wb.azel`（§5.3） |
| 评估切分 | 按天切分，或按站切分 | **两层切分：第一层必须按站，站内再按天**（§7.3）。理由：特征是站址指纹，仅按天切分仍会泄漏 |
| `FixQualityContext` | 含 `nfix`，与特征表不一致 | **删除 `nfix`**（§4.4.4），并新增「字段 ↔ 特征」对应表；`nf`/`epoch`/`nCodeRows`/`nPhaseRows` 明确标注为「不直接入特征」及各自用途 |
| IFLC 门禁 | 分散表述为「接口三禁用、接口一仅在非 IFLC 启用」，未定门禁位置 | 新增 §4.3.7：**core 侧统一门禁（D10）**，两接口均不调用；接口一按 `accept` 放行；`ionoopt` **不进 context** |
| `obsCorrectorMaxBias` | 字段表行内含绝对值竖线，破坏表格且语义不完整 | 改用 `Math.abs(biasMeters)` 表述，补齐「超限置 0 而非钳到边界」「保留 `varianceScale`」「`NaN`/`Inf` 置 0」三条规则（§4.3.4 / §4.5.1） |
| 短窗 `dt` | 只写 `1.5 * dt`，未给来源与兜底 | 定死来源为 `ctx.dt()`（`relpos` L221），`dt <= 0` 或非有限时**清空全部窗口**（§5.3） |
| G-1 显著性 | 仅「≥ 30%」点估计 | 补 Wilson 置信下界、MDE 前置（A 组事件数 < 100 不判定）、配对 McNemar 三条（§7.4） |
| 目录布局 | `AiRegistry.java`、`AiAdvisorImpl` 门面 | `AiProvider.java` / `AiHooks.java`；`OnnxAiProvider` 仅组装，不设门面（§6.1） |

### v1.0 → v1.1 修订（2026-11-06）

| 项 | v1.0 | v1.1 |
|---|---|---|
| 注入方式 | `Rtk` 上单个 `transient` 可空字段，需层层注入 | `AiRegistry`（`ThreadLocal`）+ 实例字段覆盖（§3.2）；理由：外部注入需穿透 Processor、`createRtk` 会丢 transient 字段、`varerr` 入参是 record。**v1.2 已把 `AiRegistry` 替换为 `AiProvider` 回调** |
| 开关 | `AiOpts.enableFixValidator` 布尔量 | **删除**，由 `null` 兼任（§3.2） |
| 降级层数 | 三层（含「未启用」） | 两层：未注入 / 运行时失败（§3.4） |
| 接口三施加点 | `varerr` 两处调用点（含推理） | 每历元批量推理 + `varerr` 只读查表（§3.3 / §4.3.4）；理由：`varerr` 每历元 76–116 次调用 |
| 风险表 | 无注入点相关风险 | 新增「注入点测试污染」「热路径推理」（§9） |