# AI 增强扩展方向：接口与模型需求说明

**文档性质**：方向评估稿（非实施定稿）
**版本**：v0.3（2026-11-08；v0.1 初稿，v0.2 代码事实核对与排序修订，v0.3 撤回 D 的收益立论并给出「不接入」结论，逐条见 §12）
**适用范围**：单基线 RTK、PPP（浮点/AR/RTK）、joint 之外的 AI 接口扩展
**基线**：rtklib-java v3.4.0 及《GNSS AI 增强开发文档 v1.3》
**状态**：候选方向梳理，未立项；**本阶段结论为不接入**（§13）

---

## 1. 背景与目的

《GNSS AI 增强开发文档 v1.3》定义的接口一（假固定判别）与接口三（观测改正）仅覆盖单基线 RTK 的 `relpos` 双差链路。在排除 joint、PPP-AR、接口三接 PPP 等方向后，仍存在若干未被现有确定性机制覆盖的空白点。

本文档梳理这些空白点对应的候选接口，明确每个候选在**框架、接口、模型**三层各需改动什么，作为后续立项决策的依据。

**本阶段结论（v0.3）：不接入。** 三条链路的候选均未通过「收益可证明」的门槛，理由与重开条件见 §13。

本文档不定义契约细节，不替代《GNSS AI 增强开发文档》的 §4 接口契约与 §7 评估协议。

---

## 2. 判定框架

### 2.1 收益来源判定

GNSS 解算链分两类环节：

| 环节类型 | 例子 | AI 是否有收益 |
|---|---|---|
| 数学最优 | 最小二乘、Kalman 增益、LAMBDA 搜索、协方差传播 | 无。闭式最优解，AI 逼近只会更差 |
| 经验近似 | `thresslip=0.05`、`maxinno=30`、`THRES_REJECT=4.0`、仰角/SNR 方差模型、`arRatioNl=3.0` | 有。人拍的常数在特定场景必然失配 |

**结论**：AI 只能替换「经验常数」，不能替换「数学推导」。候选接口的价值，等价于「该处经验常数在目标场景下的失配程度」。

### 2.2 只读原则（安全边界）

接口输出只允许改**权重、门控、标记**，不允许写**状态、先验、整数**。

| 输出类型 | 最坏后果 | 可回滚性 |
|---|---|---|
| 权重（`varianceScale`） | 退化为基线 | 可（下一历元重算） |
| 门控（`accept` / `stat` 标记） | 少输出一个历元 | 可 |
| 状态（`aiBias` 进 `v[]`、先验、整数） | 污染下游 AR 反推 | 不可 |

只读原则是候选筛选的第一道闸门。违反者不进入候选表。

---

## 3. 候选接口总表

| 编号 | 接口 | 目标链路 | 现有基线 | 只读性 | 数据成本 | 优先级 |
|---|---|---|---|---|---|---|
| A | 解有效性判别 | PPP（默认配置） | 迭代上限 / KF 数值 / 后验残差三道前置之后，`ns >= MIN_NSAT_SOL` 是最后一道；位置方差已导出但未参与定级 | 只读标记 | 低 | **最优先**（需先做确定性对照） |
| D | PPP-RTK 固定校验 | PPP-RTK | `PppRtkAmbFix` 单点 ratio 3.0，**无验后校验**；但 AR 结果不进输出（§6.1.1），且实测 NL 未达 FIX（§6.5） | 只读门控 | 高（SSR 已可得，但无固定事件） | 次位（**双重阻塞**，见 §6.1.1 / §6.5） |
| B | 纯方差判别 | RTK / PPP（需两套注入点） | 仰角 + SNR 经验方差模型 | 只改权重 | 中 | 末位（受 core 降级语义阻塞，见 §5.2） |

排序依据见 §10，本次修订原因见 §12。

明确排除的候选见 §7。

---

## 4. 候选 A：解有效性判别

### 4.1 问题定义

> **v0.2 更正**：v0.1 称「PPP 解有效性判断仅一行」，与代码不符。该行只是**最后一道**，其调用本身有条件。

`updateStat` 里的 `ns` 判断（[PppCore.java#L1234-L1236](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppp/PppCore.java#L1234-L1236)）：

```java
if (rtk.sol.stat != Constants.SOLQ_FIX) {
    rtk.sol.stat = (byte)(rtk.sol.ns < MIN_NSAT_SOL ? Constants.SOLQ_NONE : stat);
}
```

但它只在 commit 成功时被调用（[PppCore.java#L249-L251](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppp/PppCore.java#L249-L251)）：

```java
if (stat == Constants.SOLQ_PPP) {
    updateStat(rtk, obs, n, stat, nx);
}
```

而 `stat = SOLQ_PPP` 只在 [PppCore.java#L239-L246](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppp/PppCore.java#L239-L246) 的 commit 分支被赋值，进入该分支前已过三道关：

| 关 | 位置 | 判据 |
|---|---|---|
| 迭代上限 | [PppCore.java#L209](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppp/PppCore.java#L209) | `MAX_ITER = 8`（[L32](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppp/PppCore.java#L32)），8 次内未 commit 则 `stat` 停在 `SOLQ_SINGLE` |
| KF 数值 | [PppCore.java#L235-L237](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppp/PppCore.java#L235-L237) | `KalmanFilter.update` 返回 `info != 0` → `break`，不 commit |
| 后验残差 | [PppCore.java#L239-L246](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppp/PppCore.java#L239-L246)、[#L968](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppp/PppCore.java#L968) | 后验重算按 `THRES_REJECT = 4.0`（[L763](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppp/PppCore.java#L763)）剔除，发生剔除则 `pppRes` 返回 0 → 不 commit，转下一轮 |

三道关全未通过时 `stat` 停在 `SOLQ_SINGLE`，`updateStat` 不被调用，`sol.stat` 不变 → `PppProcessor` 侧 `sol.stat != SOLQ_NONE` 不成立 → **该历元根本不输出**。

因此真实失效形态不是「把米级误差当厘米级输出」，而是两点：

1. **`SOLQ_PPP` 无精度等级**——RTK 的 `SOLQ_FIX` / `SOLQ_FLOAT` 隐含厘米级 / 分米级，PPP 只有一个 `SOLQ_PPP`，可能对应 3 cm，也可能对应 3 dm，下游无法按状态分流；
2. **协方差已算出但未参与定级**——同一函数 6 行之后就把位置方差写进解（[PppCore.java#L1238-L1241](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppp/PppCore.java#L1238-L1241)）：

```java
rtk.sol.qr[i] = (float) rtk.P[i * nx + i];   // 位置方差已导出，stat 未使用
```

第 2 点意味着存在一条**确定性替代方案**：`qr[0]+qr[1]+qr[2] > 阈值 → 降级`。它不需要模型、可审计、零训练成本。A 必须先证明「单一标量阈值不够」，否则 AI 在此处是过度设计（前置条件见 §10）。

### 4.2 接口改动

**需要新接口。**

`FixQualityContext` 的字段（`adrRms` / `prrRms` / `pcrRms`）均来自 `ddres` 双差残差，PPP 非差链路无对应物。两个选择：

| 方案 | 做法 | 评价 |
|---|---|---|
| A-1 | 新建 `SolutionValidityChecker.validate(SolutionValidityContext)`，context 为非差语义（PPP 残差、`P` 对角元/条件数、`ns`、收敛度、bias 状态） | 推荐。与已冻结的接口一 SPI 隔离，不污染 `FixQualityContext` |
| A-2 | 泛化 `FixQualityContext` 为「解质量上下文」 | 不推荐。破坏已冻结 SPI，需重跑 I-1，且两者特征不同源 |

### 4.3 模型改动

**需要新模型。** 特征全新建，不能复用 `fix_validator.onnx`。

参考方法：

| 方法 | 来源 | 特点 |
|---|---|---|
| Isolation Forest | 武大李星星团队 2024，实时 PPP 改正数异常检测 | 无监督，不需稀有事件标签，只做告警标记 |
| Dense autoencoder | ION GNSS+ 2026，CORS 质量监测 | 输入含 PPP 无电离层伪距残差、SNR、多路径组合、仰角方位角，无监督 |

两者均为「只读标记」范式，与本候选定位一致。

> **v0.2 更正**：v0.1 把「无监督」当作 A 数据成本低的核心原因，前提不成立。

- PPP 默认配置下 `SOLQ_PPP` 输出率高，**「误差超阈值」并非稀有事件**，G-1 前置（≥100 个事件）在 A 上比接口一容易达成；
- 监督路线可直接复用接口一已建成的全套管线（采集器 → trace 对齐 → 真值打标 → 两层切分 → 验证集扫阈值 → G-1~G-4）；无监督路线必须新写「告警精度/召回」协议，且**无法与接口一横向比较**——G-1 定义是「错误固定率相对下降」，异常分数没有这个量。

结论：无监督是**可选项**，不是低成本理由。选监督反而更省评估成本（§12 第 2 条）。

### 4.4 框架改动

复用。注入机制与解算模式无关。

### 4.5 需明确的事项

| 事项 | 说明 |
|---|---|
| 特征来源 | 新建非差 context，字段来源需逐项确认（PPP 残差、`P` 的哪些量、收敛度如何定义） |
| 有效解阈值 | 3 cm / 10 cm / 1 m。决定 A 拦掉多少历元、收益-代价平衡点。与接口一 0.1 m 标定（D7）同类 |
| 标签生成 | 事后参考轨迹判「该历元误差是否超阈值」。需确认是否具备带参考轨迹的 PPP 数据 |

---

## 5. 候选 B：纯方差判别

### 5.1 问题定义

接口三（`ObsCorrector`）实际做了两件性质不同的事：

```java
v[nv] -= aiBias[...] - aiBias[...];   // 改偏差 → 进状态 → 危险
Ri[nv] *= aiScale[...];               // 改方差 → 不进状态 → 安全
```

`aiBias` 通过 `v` 进入 KF 更新、污染模糊度状态；`aiScale` 只改观测权重，不改变状态物理含义。

**B 的定义**：将接口三裁剪为「只输出 `varianceScale`、`biasMeters` 恒为 `null`」，得到纯权重接口。

### 5.2 接口改动

**v0.1 主张「不需要新接口、不需要改 SPI」；v0.2 核实后该主张不成立**（理由见本节末尾「硬阻塞」）。

`ObsCorrector.correct()` 返回 `CodeCorrection(biasMeters, varianceScale)`，裁剪版让 impl 返回 `biasMeters = null`、`varianceScale` 有值：

- 现有契约「bias ≠ 0 时 scale 须满足下界」在 `bias = null` 时不触发；
- core 侧照常钳位 `scale ≥ 1.0`、`scale ≤ varianceScaleMax`；
- 未注入时 `aiScale == null`，`Ri/Rj` 不乘系数，I-1 不受影响（需跑零回归确认）。

> **v0.2 硬阻塞（已核实代码）**：`biasMeters = null` 会让**整个改正被丢弃**，`varianceScale` 一并作废。按本节 v0.1 描述原样实现，B 运行时等于**恒不干预**，不是「只改权重」。

core 的校验（[RtkCore.java#L1743-L1747](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1743-L1747)）：

```java
if (cc == null || cc.biasMeters() == null || cc.varianceScale() == null
        || cc.biasMeters().length != ns * nf || cc.varianceScale().length != ns * nf) {
    LOG.warn("接口三输出非法（null 或长度不符，期望 {}），本历元按不干预处理（epoch={}）", ...);
    return;
}
```

SPI javadoc 同义（[ObsCorrector.java#L16-L21](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ai/spi/ObsCorrector.java#L16-L21)）：「返回 `null`，或**任一数组**长度不符时视为降级」。所以「不需要改 SPI」的结论不成立，只有两条路：

| 路径 | 代价 |
|---|---|
| 放宽 core 的 null 检查（`biasMeters == null` 时 `aiBias` 全 0、`scale` 照用） | 改已冻结的降级语义，**必须重跑 I-1**，降级分支从两条变三条 |
| 新增独立 SPI（如 `VarianceScaler`） | 与 A/D 同等量级：`AiProvider` 加方法 + `AiHooks` 解析 + 目标链路注入点 |

### 5.3 模型改动

**需要新模型。** 训练目标与接口三不同：

| 项 | 接口三 | B |
|---|---|---|
| 回归目标 | `bias` + `σ_m` | 仅 `varianceScale` |
| 标签来源 | 逐星伪距误差真值 | 残差异常间接监督，或定位误差反传 |
| 可否复用 | — | 不可复用接口三模型 |

参考方法：pyrtklib / TDL-GNSS 的权重学习思路（用定位误差作为损失间接学权重），但需从逐星改为批量推理，训练目标重新设计。

### 5.4 框架改动

复用。

### 5.5 相对接口三的收益

| 维度 | 收益 |
|---|---|
| 安全性 | 从「中」升到「低」。权重错误最坏是低估某颗星，不是把错误偏差焊进状态 |
| 可复用性 | 不需双差参考星语义，可复用到 PPP / PPP-RTK。**joint 需附加条件**（见下），不是「天然通用」 |
| 差环境抓手 | 方差膨胀是 NLOS / 多路径的主要抓手 |

代价是失去偏差改正能力。

> **v0.2 更正（三处）**：

1. **joint 不是「天然可复用」**。若用 DD 残差做特征，同一 rover 卫星在基线 A / B 各有参考星与残差，`scale` 必然不一致，仍进不了 joint——与《GNSS AI 增强开发文档》§1.5.1 / D11 一致。B 只有一个能进 joint 的变体：**特征基线无关**（仰角、SNR、非差多路径组合、非差伪距残差），此时输入对同一卫星在所有基线上唯一。
2. **跨链路不是零成本**。RTK 与 PPP 的观测方差来自两套独立实现（`RtkCore.varerr` 与 `PppCore.varerr`），跨链路意味着**两个注入点、两套特征语义（双差 vs 非差）、两个训练集**。
3. **B 没有精度收益**。它复用 core 的单边钳位（[RtkCore.java#L1767-L1769](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1767-L1769)：`s < 1.0 → 1.0`），**只能降权、不能升权**。「这条观测比模型认为的更好」无法表达，故只带来可靠性收益（§11 相应更正）。

### 5.6 需明确的事项

| 事项 | 说明 |
|---|---|
| 训练标签 | 残差异常间接监督（简单但信号弱）vs 定位误差反传（复杂但接近目标） |
| I-1 验证 | 未注入时 `aiScale == null`，理论上不影响，需跑零回归确认 |
| 与 `varerr` 的关系 | 在 `varerr` 外乘系数（安全），不替换 `varerr` 本体（会外溢到 joint） |

---

## 6. 候选 D：PPP-RTK 固定校验

### 6.1 问题定义

`PppRtkAmbFix` 的 AR 门控只有一个 ratio 3.0 单点检验，比 PPP-AR 的多道门控弱。这条路径的可靠性缺口比 PPP-AR 更大。

> **v0.2 补强 / v0.3 限定**：v0.1 只说了「比 PPP-AR 弱」。对照代码，缺口确实是**结构性缺失**而非参数偏松（下列三点）。但「缺口大」不等于「收益大」——收益还取决于固定解是否真的输出、错误事件是否真的存在，这两项见 §6.1.1 与 §6.5。

| # | 事实 | 位置 |
|---|---|---|
| 1 | 门控只有 `ratio < arRatio` 一处（默认 3.0），之后无任何检查 | [PppRtkAmbFix.java#L141-L148](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppprtk/PppRtkAmbFix.java#L141-L148) |
| 2 | 固定后**无验后校验**——直接写 `xa[idx]`、`sol.stat = SOLQ_FIX`。对比 RTK 有 `valpos(v,R,vflg,nv,4.0)` | [PppRtkAmbFix.java#L163-L187](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppprtk/PppRtkAmbFix.java#L163-L187) |
| 3 | 固定后**不回滚、不重解**——`Pa` 对应行列清零、`ssat[].fix[] = 1`，且**没有任何位置条件更新**（详见 §6.1.1） | [PppRtkAmbFix.java#L168-L173](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppprtk/PppRtkAmbFix.java#L168-L173) |

对照 PPP-AR 的门控密度（[PppArOpts.java#L16-L47](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/config/PppArOpts.java#L16-L47)）：`arRatioWl = 2.0`、`arRatioNl = 3.0`、`arWlMaxdev = 0.20`、`arNlMaxdev = 0.15`、`arNlMaxsig = 0.15`、`arNlMinsav = 4`，另有 δ 门一致性检验。**PPP-RTK 一条都不具备。**

> **v0.3 撤回**：v0.2 由第 3 点推出「错误固定是持续性污染、代价跨历元」，并据此把 D 排为第一优先。**该推论不成立**，逐条核对见 §6.1.1。

### 6.1.1 前置缺陷：AR 结果没有真正进入输出

这是 v0.3 新增的核对结论，也是 D 被降级的直接原因。三条事实：

**① `fix[]` 是逐历元重置的，不是持久标记**

[PppRtkCore.java#L115-L119](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppprtk/PppRtkCore.java#L115-L119) 在 `ppprtkos` 入口无条件执行 `rtk.ssat[i].fix[j] = 0`。AR 打的 `fix[f] = 1/2` 到下一历元即消失。

**② UC（非组合）路径不回写滤波器**

[PppRtkAmbFix.java#L160-L188](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppprtk/PppRtkAmbFix.java#L160-L188) 只改局部 `xa[idx]`、清 `Pa` 行列，然后 `rtk.xa = xa; rtk.Pa = Pa;`。全仓库读回 `xa`/`Pa` 的只有 `RtkCore` / `CascadeAR` / `PartialAR`，**`ppprtk` 包内零处**。所以 UC 下滤波器状态不受污染。

**③ 位置从不重解，且 `updateStat` 无条件覆盖**

UC 路径 `xa` 是 `rtk.x` 的整体拷贝，只改模糊度分量，`xa[0..2]` 恒等于浮点位置。IFLC 路径同样如此——`PppAmbFix` 只写 `rtk.x[idx]` 与 `initx(..., 0.0, idx)`，无位置条件更新。AR 之后紧跟的 [updateStat](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppprtk/PppRtkCore.java#L1003-L1011)：

```java
if (rtk.sol.stat != Constants.SOLQ_FIX) {   // 只护住 stat（FIX-ALG-040）
    rtk.sol.stat = ...;
}
for (int i = 0; i < 3; i++) {
    rtk.sol.rr[i] = rtk.x[i];               // 无条件覆盖回浮点位置
    rtk.sol.qr[i] = (float) rtk.P[i * nx + i];
}
```

对比 RTK 侧的正确形态——[RtkCore.java#L539-L546](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L539-L546) 有完整的 `if (stat == SOLQ_FIX)` 分支，从 `xa`/`Pa` 取 `rr`/`qr`。**PPP-RTK 侧没有这个分支。**

**净结果**：

| 配置 | 输出位置 | 输出协方差 | `sol.stat` | 跨历元污染 |
|---|---|---|---|---|
| UC + `enableFixHold = false`（默认） | 浮点 | 浮点 | `SOLQ_FIX` | **无** |
| UC + `enableFixHold = true` | 浮点（经 hold 伪观测间接牵动） | 浮点 | `SOLQ_FIX` | 有（`fixAndHold` 写 `rtk.x`/`rtk.P`） |
| IFLC（任意） | 浮点 | 浮点 | `SOLQ_FIX` | 有（`initx(...,0.0,idx)` 焊死模糊度状态） |

默认配置是 `enableAR = false`、`enableFixHold = false`（[PppRtkOpts.java#L17-L21](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/config/PppRtkOpts.java#L17-L21)）。而 [SSR_ACCESS_PLAYBOOK.md](SSR_ACCESS_PLAYBOOK.md) 记录的生产实测配置是**三频 3F（非 IFLC）+ 未开 fix-and-hold**，恰好落在**第一行**——即「AR 只留下一个标签」。

**这条推论改变了 D 的收益性质**：D 现在要拦的对象不是「整数错解导致的米级跳变」，而是「**浮点解被标成 FIX**」，误差量级由浮点解本身决定。这与候选 A（PPP 浮点解有效性）是**同一个对象**，差别只在目标标签。D 的额外收益退化为「FIX 标签比 PPP 标签让下游更信任」这一层语义溢价。

### 6.2 接口改动

**前置第 0 条（v0.3 新增）**：先修 §6.1.1 的输出缺陷，再谈校验。需要两处确定性改动：

1. UC 路径在 `xa[idx]` 赋值后做位置条件更新（RTKLIB 原生 `resamb` 语义）；
2. `updateStat` 补 `if (stat == SOLQ_FIX)` 分支，从 `xa`/`Pa` 取 `rr`/`qr`（照抄 [RtkCore.java#L539-L546](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L539-L546)）。

不修这两处，AI 校验的对象是残缺的——校验器面对的是「浮点解 + FIX 标签」，无法学到「固定坐标错在哪里」。

**接口本身需要新建。** 特征来源（WL/NL 两级 ratio、SSR 改正质量、固定后残差）与 `FixQualityContext` 的双差语义不同源，需新建 `PppRtkFixValidator` + context。

### 6.3 模型改动

**需要新模型。** 参考方法：XGBoost（服务端网络端模糊度验证）或轻量 MLP（用户端）。

参考数据：ION GNSS+ 2025 用 XGBoost 做 PPP-RTK 网络端模糊度验证，测地站宽巷固定正确率 75%→97%，错误固定率 4.5%→1% 以下。但该工作为服务端，非用户端解算链。

### 6.4 框架改动

复用。

### 6.5 需明确的事项

| 事项 | 说明 |
|---|---|
| 用户端 vs 服务端 | 用户端需 SSR 数据；服务端不在当前解算链上 |
| 特征来源 | 与 PPP-AR 固定校验不同源，需新建 |
| 数据成本 | 需 SSR 数据 + 固定事件基数。固定率高于 PPP-AR，数据成本相对低，但仍高于 A |

> **v0.2 更新：SSR 可得性已确认（本项前置条件满足）**

依据 [SSR_ACCESS_PLAYBOOK.md](SSR_ACCESS_PLAYBOOK.md)：

- CAS `SSRA01CAS0` / `SSRC01CAS0` 已接入并完成录制/回放闭环验证，2026-11-05 实时复测 60 s 收到 42,802 字节 / 87 帧、92 颗星可用；
- CAS0 含相位偏差（`1270` BDS），故**具备 AR 条件**；`CAS1` 是 4076 专有电文，core `Rtcm` 不识别，不可用（该文档 §七 已知坑 9）；
- 账号密码不入库，走环境变量/本机配置（同文档 §三）。

因此 D 剩余的前置条件只有一项：**固定事件基数**。而这一项**已有实测结论，且结论是不利**——该文档 §六 验收清单记录：「WL 100% 固定、NL 受可见卫星数限制未达 FIX（结论归档，算法冻结不调门限）」，且 §七 说明「生产用三频 3F」。

即：**NL 从未固定 → 生产上不存在 `SOLQ_FIX` 输出 → 既没有收益对象，也没有训练样本**。这与 §6.1.1 的「即使固定也不进输出」叠加，构成 D 的双重阻塞。D 若要重启，必须先解决 NL 固定率，而这是算法问题，不是 AI 问题。

---

## 7. 明确排除的候选

| 方向 | 排除理由 |
|---|---|
| 接口三接 PPP | 偏差被 `IB(sat, frq)` bias 状态吸收，污染 AR 输入。估计器结构问题，非数据问题 |
| PPP-AR 固定校验 | 数据成本 160 站时级（错误固定率 0.1%–1%，按 G-1 前置需 100 事件）；可解释性问题 |
| PPP-FIXED | `PMODE_PPP_FIXED = 9` 为死常量，无行为分支，实际等价 PPP-KINEMA |
| PPP-STATIC 固定校验 | 静态长时间平均抹平随机误差，逐历元 AI 干预收益趋近 0 |
| 对流层/电离层先验修正 | 违反只读原则，写状态最坏是灾难 |
| 场景自适应阈值 | 与已有 `AdaptiveController` + `PresetManager` 正面竞争，黑箱不可审计 |
| 周跳检测 | 已有 GF/MW/LLI/Doppler 四道防线，边际收益低 |
| 部分固定子集选择 | 收益太小 |
| 多星联合粗差探测 | 工程重、标签难。可做但优先级低于 A/B |

---

## 8. 框架层统一改动

### 8.1 现状问题

`AiProvider` 只有两个方法、无 default 实现：

```java
public interface AiProvider {
    FixValidator fixValidator();
    ObsCorrector obsCorrector();
}
```

每加一个新接口，都必须改 core SPI，所有现有实现（`OnnxAiProvider`、测试）编译期全要改。这与「core 只放 SPI、实现独立成模块」的初衷有张力。

### 8.2 推荐做法

**core 侧给 `AiProvider` 加 `default { return null; }` 方法，组合逻辑全在 rtklib-ai 侧。**

| 优点 | 说明 |
|---|---|
| 向后兼容 | 现有实现不改，`default` 返回 `null` 即关闭 |
| 不破坏已冻结 SPI | 不需要重跑 I-1 |
| 多接口共存 | `AiHooks` 为 ThreadLocal 单槽，A/B/D 同时开时由 rtklib-ai 侧复合 provider 返回多个实现，core 不动 |

不推荐 `resolve(String key)` 泛型注册——破坏已冻结 SPI，需重跑 I-1。不推荐复合 provider 在 core 侧组装——违反依赖方向。

### 8.3 多接口收益不可线性相加

多个接口串联在同一解算链上，A 接口的错误会通过状态污染 B 接口的输入。约束：

- 一次只开一个接口做 A/B（对照 §7.1 四组要求）；
- 每个接口单独过 G-1~G-5，不打包测试；
- 组合开启须重新评估，不能假定叠加有效。

注：A（只读标记）不改状态，其错误不会污染 B 的输入，只会「少输出一个历元，B 也没机会作用」。A + B 可独立评估；B + 接口三同时开才需联合评估。

---

## 9. 汇总：三层改动矩阵

| 候选 | 框架 | 接口 | 模型 | 工程改动量 |
|---|---|---|---|---|
| A 解有效性判别 | 复用（仅加 default 方法） | 新建 `SolutionValidityChecker` + 非差 context | 新建；监督可复用接口一管线，无监督为可选（§4.3） | 中 |
| B 纯方差判别 | 复用（仅加 default 方法） | **需改**：`biasMeters = null` 会触发整历元丢弃，须放宽 core null 检查或新增独立 SPI（§5.2） | 新建，只回归 scale | 中（v0.1 记「最小」已更正） |
| D PPP-RTK 固定校验 | 复用（仅加 default 方法） | **需先修 core**（AR 输出缺陷，§6.1.1），再新建 `PppRtkFixValidator` + context | 新建，XGBoost / 轻量 MLP | 大（且前置为算法修复，非 AI） |

框架层统一改动：`AiProvider` 加 `default { return null; }` 方法。

---

## 10. 建议排序与前置条件

> **v0.3 重排**：v0.2 把 D 排第一，依据是「D 是唯一的结构性缺失、错误代价跨历元持续」。§6.1.1 核对后该依据不成立——生产配置（UC + 未开 fix-and-hold）下 AR 结果不进输出，D 的对象退化为「浮点解被打 FIX 标签」，与 A 同源；叠加 §6.5 实测 NL 从未达 FIX，生产上连 `SOLQ_FIX` 都不产生。排序改为 **A → D → B**。

| 顺序 | 候选 | 理由 | 前置条件 |
|---|---|---|---|
| 1 | A 解有效性判别 | 三者中唯一「对象确定存在」的候选：浮点解是真实输出、误差可量测；缺陷真实（`SOLQ_PPP` 无精度等级、位置方差未参与定级） | **先做确定性对照**（`qr` 阈值降级，量出拦多少 / 漏多少）；确定有效解阈值 |
| 2 | D PPP-RTK 固定校验 | 缺口真实（单点门控 + 无验后校验），但**双重阻塞**：(1) §6.1.1 下 AR 结果不进输出；(2) §6.5 实测 NL 从未固定，生产无 `SOLQ_FIX` 输出 | 先解决 NL 固定率（算法问题，非 AI）；再修 AR 输出（§6.2 前置第 0 条）；SSR 已确认可得 |
| 3 | B 纯方差判别 | 受 core 降级语义阻塞（`biasMeters = null` → 整历元丢弃，§5.2）；跨链路需两套注入点；无精度收益（单边钳位） | 先定 B 走哪条路（放宽 core 降级语义并重跑 I-1 / 新增独立 SPI）；确定训练标签 |

**若只做一件事 → 选 A**，理由不再是「空白最大」，而是：

1. **对象确定**：`sol.rr` / `sol.qr` 是真实输出的浮点解，误差可用参考轨迹直接量测；
2. **对照成本低**：确定性替代方案（`qr` 阈值降级）几行代码即可，能先量出「不做 AI 能拦多少」，从而判断 AI 的增量是否值得；
3. D 的对象与 A 同源（§6.1.1），且 D 在生产上无对象（§6.5），A 做成了，D 的增量只剩标签层，边际收益更低。

v0.1 / v0.2 的以下理由均已撤回：

1. ~~「空白最大（一行判断）」~~——不成立，`updateStat` 之前还有迭代上限、KF 数值、后验残差三道关（§4.1）；
2. ~~「标签便宜——不需等稀有事件累积到 100 个」~~——不成立，PPP 默认配置下 `SOLQ_PPP` 输出率高，「误差超阈值」并不稀有；且监督路线本就能复用接口一管线（§4.3）；
3. ~~「D 错误代价跨历元持续，收益-风险比最高」~~——不成立，§6.1.1。

---

## 11. 收益量级的诚实估计

| 维度 | 现实可期待的收益 |
|---|---|
| 可靠性 | 显著。AI 唯一有明显收益的维度。错误固定/错误解拦截率可降 30%+，代价是固定率下降 ≤ 1 pp。**该数字的前提是「固定解真实输出」**，PPP-RTK 当前不满足（§6.1.1） |
| 精度 | 很小。正常精度由观测质量和改正模型决定。接口三有偏差改正能力，理论上最多带来 5–15% 残差改善（该数字需实证复核）；**候选 B 完全没有精度收益**——它复用 core 的单边钳位（只能膨胀、不能收缩），无法表达「这条观测比模型认为的更好」（§5.5 第 3 条） |
| 差环境 | 中等偏上，取决于接口选择。选 A 有用；选周跳检测收益被四道现有防线吃掉；选阈值自适应与已有机制重叠 |

**必须泼冷水的一点**：AI 不会让「差环境变得能固定」。差环境固定不了的根本原因是几何和观测质量，不是判别能力。AI 能做的是在差环境里少输出错解，不是「变好」。目标应写为「降低差环境错误解输出率」，而非「提升差环境固定率」。

---

## 12. 修订记录

### v0.1（初稿）

候选方向梳理，未立项。排序 A → B → D。

### v0.2（2026-11-08）：代码事实核对与排序修订

本次修订**只改事实与排序**，不改框架（§2）、排除表（§7）、框架层方案（§8）的结论。

| # | 位置 | v0.1 表述 | v0.2 修订 | 依据 |
|---|---|---|---|---|
| 1 | §3、§4.1、§10 | 「PPP 解有效性判断仅一行，空白最大」 | `updateStat` 之前还有三道关：`MAX_ITER = 8`、`KF info != 0`、后验残差 `THRES_REJECT = 4.0`；`stat` 只在 commit 分支置 `SOLQ_PPP` | [PppCore.java#L209](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppp/PppCore.java#L209)、[#L235-L246](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppp/PppCore.java#L235-L246)、[#L249-L251](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppp/PppCore.java#L249-L251) |
| 2 | §4.1 | 「不检查协方差是否可信」 | 协方差已导出到 `sol.qr`，只是未参与 `stat` 定级；真实缺陷是 `SOLQ_PPP` 无精度等级 | [PppCore.java#L1238-L1241](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppp/PppCore.java#L1238-L1241) |
| 3 | §4.3 | 「无监督是 A 数据成本低的核心原因」 | 前提不成立：PPP 输出率高、误差超阈值不稀有；监督路线可复用接口一管线，且能与 G-1 横向比较 | [PppCore.java#L1234-L1236](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppp/PppCore.java#L1234-L1236) |
| 4 | §5.2、§9 | 「不需要改 SPI，`biasMeters = null` 即可」 | **硬阻塞**：null 会让整个改正被丢弃、`scale` 一并作废，等于恒不干预。须放宽 core null 检查（重跑 I-1）或新增独立 SPI | [RtkCore.java#L1743-L1747](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1743-L1747)、[ObsCorrector.java#L16-L21](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ai/spi/ObsCorrector.java#L16-L21) |
| 5 | §5.5 | 「天然可复用到 PPP / PPP-RTK / joint」 | joint 需附加条件：特征须基线无关；用 DD 残差仍进不了 joint。跨链路还需两套注入点与两个训练集 | 《AI 增强开发文档》§1.5.1 / D11 |
| 6 | §5.5、§11 | 「改权重可带来 5–15% 精度改善」 | B 复用单边钳位（只能膨胀不能收缩），**无精度收益**；5–15% 归属接口三（有 bias 能力） | [RtkCore.java#L1767-L1769](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1767-L1769) |
| 7 | §6.1 | 「比 PPP-AR 的两级门控更弱」 | 改为结构性缺失三点：单点门控、无验后校验、固定后不可回滚（含 fix-and-hold 固化）；PPP-AR 实为 6 个门控参数 + δ 门 | [PppRtkAmbFix.java#L141-L187](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppprtk/PppRtkAmbFix.java#L141-L187)、[PppArOpts.java#L16-L47](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/config/PppArOpts.java#L16-L47) |
| 8 | §6.5、§10 | 「确认 SSR 数据可得性」为待办 | **已确认**：CAS `SSRA01CAS0` / `SSRC01CAS0` 已接入并闭环验证，含相位偏差；剩余前置为 NL 固定率与错误固定事件基数 | [SSR_ACCESS_PLAYBOOK.md](SSR_ACCESS_PLAYBOOK.md) §二、§六 |
| 9 | §10 | 排序 A → B → D | 重排 **D → A → B**，并保留「仅默认配置可跑则选 A」的分支 | 上述第 1、4、7、8 条 |

### 本次未改动（避免误读）

- **§2 判定框架**：只读原则与「AI 只能替换经验常数」保持。但第 7 条提示 §2.1 对 D 需放宽——D 补的是缺失判据，不是替换常数。此点留待 v0.3 处理。
- **§7 排除表**：结论不变。表中「多星联合粗差探测」为**推迟**而非否决，与其余 8 项性质不同，建议 v0.3 拆表。
- **§8 框架层**：`AiProvider` 加 `default` 方法的方向不变。但「core 不动」不准确——新增接口仍需在 core 加 SPI 方法与目标链路注入点，且**每个注入点各自需要独立的 I-1 验证**。

### v0.3（2026-11-08）：撤回 D 的收益立论，给出不接入结论

v0.2 把 D 排第一，依据是「固定后不可回滚 + fix-and-hold 固化 → 错误代价跨历元持续」。v0.3 核对发现**该依据不成立**，据此重排并给出阶段结论（§13）。

| # | 位置 | v0.2 表述 | v0.3 修订 | 依据 |
|---|---|---|---|---|
| 1 | §6.1 | 「固定后不可回滚，滤波器失去该模糊度的不确定度表达」 | 撤回。`fix[]` **每历元入口清零**，不是持久标记 | [PppRtkCore.java#L115-L119](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppprtk/PppRtkCore.java#L115-L119) |
| 2 | §6.1 | 「UC 路径错误固定会持续污染」 | 撤回。UC 只写局部 `xa`/`Pa`，**不回写滤波器**；`ppprtk` 包内零处读回 `xa`/`Pa` | [PppRtkAmbFix.java#L160-L188](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppprtk/PppRtkAmbFix.java#L160-L188) |
| 3 | §6.1、§11 | 「单次错误代价大、拦截收益高」 | 新增 §6.1.1：**AR 结果不进输出**。位置从不重解，`updateStat` 无条件用 `rtk.x`/`rtk.P` 覆盖 `rr`/`qr`；`stat` 有守卫、`rr` 无守卫 | [PppRtkCore.java#L1003-L1011](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppprtk/PppRtkCore.java#L1003-L1011) vs [RtkCore.java#L539-L546](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L539-L546) |
| 4 | §6.1.1 | —（v0.2 未列） | 新增配置矩阵：默认 `enableAR=false` / `enableFixHold=false`；生产实测为 3F 非 IFLC + 未开 hold，落在「AR 只留标签」一行 | [PppRtkOpts.java#L17-L21](file:///D:/rtklib/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/config/PppRtkOpts.java#L17-L21)、[SSR_ACCESS_PLAYBOOK.md](SSR_ACCESS_PLAYBOOK.md) §六 |
| 5 | §6.2 | 「需要新接口」 | 补**前置第 0 条**：先修 AR 输出（位置条件更新 + `updateStat` 的 `SOLQ_FIX` 分支），再谈校验 | §6.1.1 第 ③ 点 |
| 6 | §6.5 | 「NL 固定率需先量测」 | 改为**已有实测且不利**：NL 从未达 FIX，生产无 `SOLQ_FIX` 输出 → 无收益对象、无训练样本 | [SSR_ACCESS_PLAYBOOK.md](SSR_ACCESS_PLAYBOOK.md) §六、§七 |
| 7 | §10 | 排序 D → A → B；「能开 SSR 就选 D」 | 重排 **A → D → B**；「若只做一件事」改为无条件选 A，理由是「对象确定 + 对照成本低 + D 无对象」 | 上述第 1–6 条 |
| 8 | §11 | 「可靠性：拦截率降 30%+」 | 加前提：该数字成立的前提是「固定解真实输出」，PPP-RTK 当前不满足 | §6.1.1 |
| 9 | §13 | —（v0.2 无此节） | 新增阶段结论「不接入」+ 三条独立理由 + 重开条件 | 全节 |

**本次未改动**：§2（判定框架）、§5（候选 B）、§7（排除表）、§8（框架层）、§9（改动矩阵）的结论。

### 待决事项（v0.3 之后）

1. B 走哪条路：放宽 core 降级语义（重跑 I-1），还是新增独立 SPI；
2. A 是否先做确定性协方差阈值对照（不做则无法证明 AI 增量）；
3. PPP-RTK AR 输出缺陷（§6.1.1）是否立项修复——这是**确定性工程**，与 AI 无关，但它是 D 的任何后续工作的前提。

---

## 13. 本阶段结论：不接入

**结论：PPP / PPP-AR / PPP-RTK 三条链路，本阶段不新增 AI 接口。**

三条理由，均可独立成立：

1. **唯一「对象确定、收益可量测」的候选（A）能先用确定性手段验证**。位置方差已经导出到 `sol.qr`，加一个阈值降级分支即可量出「不做 AI 能拦多少」。若确定性方案已拦住大部分，AI 的增量不足以支撑训练管线成本；若拦不住，那时再谈模型，也才有基线可比。
2. **D 的收益对象在生产上不存在**。两重阻塞：① §6.1.1——AR 结果不进输出，固定坐标从未输出；② §6.5——实测 NL 从未达 FIX，生产上没有 `SOLQ_FIX`。既无收益对象，也无训练样本。先解决 NL 固定率与输出缺陷，都是确定性工程，不是 AI 能替代的。
3. **AI 的总前提尚未被验证**。接口一（RTK 固定校验）的工程管线已建成，但**真实数据采集与模型训练尚未开始**。如果接口一都证明不了「AI 能在固定校验上产生可测增量」，PPP 侧更难证明——PPP 的错误事件更稀有、数据成本更高、评估协议还需另写。

**不动，不是否决方向，而是把顺序摆正**：先让接口一在真实数据上跑出 G-1~G-5 的结论。这是唯一能回答「AI 在 GNSS 固定校验上到底有没有用」的实验，也是 A / D 立项的前提。

### 重开条件

| 触发 | 动作 |
|---|---|
| 接口一在真实数据上通过 G-1（错误固定率相对下降 ≥ 30%） | 按 §10 顺序重启 A，复用接口一管线 |
| PPP-RTK 的 NL 能稳定达 FIX，且 AR 输出缺陷（§6.1.1）修复并通过精度回归 | 重启 D 的量测与立项评估 |
| 确定性 `qr` 阈值对照显示拦截率 < 50% | A 的 AI 增量才有讨论空间 |

---

## 附录：术语与引用

| 术语 | 含义 |
|---|---|
| 只读原则 | 接口输出只改权重/门控/标记，不写状态/先验/整数 |
| 经验常数 | 硬编码阈值、经验公式，在特定场景下必然失配 |
| 数学最优环节 | 闭式最优解，AI 逼近只会更差 |
| I-1 | 零回归不变量：`AiOpts` 全默认时，解算输出与 v3.4.0 逐位一致 |

外部参考：

- 武大李星星团队 2024，Isolation Forest 实时 PPP 改正数异常检测
- ION GNSS+ 2026，CORS 质量监测 dense autoencoder
- ION GNSS+ 2025，XGBoost PPP-RTK 网络端模糊度验证
- pyrtklib / TDL-GNSS，RTKLIB 深度学习集成

---

**文档结束。** 本文档为方向评估稿，候选接口的契约细节（特征定义、注入点、降级语义、G 判据）需在立项后按《GNSS AI 增强开发文档 v1.2》的 §4 / §7 格式另行编写。