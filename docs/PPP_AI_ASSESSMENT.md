# PPP 体系 AI 接入技术评估

**文档性质**：技术评估（非实施定稿）
**版本**：v1.1（2026-11-14；追加 §11「技术实现验证口径 P0~P4」，修订 §9 重开条件 1，吸收 FIX-ALG-054 代码事实。v1.0 定稿于 2026-11-09、2026-11-10 同步所引设计文档至 v1.4；v1.1 同步至 v1.11）
**评估范围**：PPP 浮点 / PPP-AR / PPP-RTK 三条链路
**基线**：rtklib-java v3.4.0、《GNSS AI 增强开发文档 v1.11》
**结论（交付层面）**：**PPP 体系本阶段不接入 AI**（§8）。三条链路各自独立成立，不依赖彼此。
**结论（技术实现层面，v1.1 追加）**：允许按 **P0~P4** 口径开展技术可实现性验证（PoC），**不要求 G-1 / G-3 / 统计功效达标**；但 P0 四条安全契约不可降。技术通过 ≠ 有效，不得引用为交付依据（§11）。

---

## 1. 文档定位

### 1.1 本文回答的问题

只有一个：**在 PPP 体系里，AI 应该放在哪里，以及值不值得接入。**

| 在范围内 | 不在范围内 | 理由 |
|---|---|---|
| PPP 浮点（`PppCore`） | 单基线 RTK（`RtkCore`） | 接口一 / 接口三已在 RTK 双差链路落地，有独立文档与评估协议 |
| PPP-AR（`PppArEngine` / `PppAmbFix`） | joint 多基线 | 依赖方向与 DD 语义，AI 特征无法基线无关，另案处理 |
| PPP-RTK（`PppRtkCore` / `PppRtkAmbFix`） | PPP-FIXED | `PMODE_PPP_FIXED = 9` 为死常量，无行为分支，实际等价 PPP-KINEMA |

### 1.2 与既有文档的关系

| 文档 | 作用 | 与本文的边界 |
|---|---|---|
| [AI_ENHANCEMENT_DESIGN.md](AI_ENHANCEMENT_DESIGN.md) v1.11 | 接口一 / 接口三的契约、注入点、§7 评估协议（G-1~G-5） | 仅覆盖 RTK 双差链路；本文不定义任何契约 |
| 本文 | PPP 侧候选梳理与接入结论 | 契约细节需立项后按上文档 §4 / §7 格式另行编写 |
| [SSR_ACCESS_PLAYBOOK.md](SSR_ACCESS_PLAYBOOK.md) | PPP-RTK 的 SSR 可得性、生产实测配置与验收结论 | 本文 §3.3、§4.2 的实测依据来源 |

### 1.3 AI 在 PPP 体系中的定位

PPP 体系的解算链由确定性数学构成：最小二乘 → Kalman 滤波 → 模糊度固定 → 协方差传播。AI 若要进入这条链，只能落在三个层次，且各层能力边界不同：

| 层 | AI 可以做什么 | AI 不可以做什么 | 本文结论 |
|---|---|---|---|
| 框架层 | 提供 provider 注入机制，承载多个接口共存 | 决定解算逻辑 | 复用，仅需 `AiProvider` 加 `default` 方法（§7） |
| 接口层 | 只读地改**权重、门控、标记** | 写**状态、先验、整数** | 三条链路均无合格候选（§5） |
| 模型层 | 学习「经验常数」在特定场景下的失配量 | 逼近闭式最优解（最小二乘、Kalman 增益、LAMBDA、协方差传播） | 无训练对象（§5、§8） |

一句话概括定位：**AI 在 PPP 体系中最多是「经验常数的替代者」，不是「解算链的组成部件」**。本文全部否决结论都从这个定位推出（§2）。

---

## 2. 判定框架

### 2.1 收益来源：只能替换经验常数

| 环节类型 | 例子 | AI 是否有收益 |
|---|---|---|
| 数学最优 | 最小二乘、Kalman 增益、LAMBDA 搜索、协方差传播 | 无。闭式最优解，AI 逼近只会更差 |
| 经验近似 | `thresslip = 0.05`、`maxinno = 30`、`THRES_REJECT = 4.0`、仰角/SNR 方差模型、`arRatioNl = 3.0` | 有。人拍的常数在特定场景必然失配 |

候选接口的价值，等价于「该处经验常数在目标场景下的失配程度」。

### 2.2 只读原则（安全边界）

接口输出只允许改**权重、门控、标记**，不允许写**状态、先验、整数**。

| 输出类型 | 最坏后果 | 可回滚性 |
|---|---|---|
| 权重（`varianceScale`） | 退化为基线 | 可（下一历元重算） |
| 门控（`accept` / `stat` 标记） | 少输出一个历元 | 可 |
| 状态（偏差进 `v[]`、先验、整数） | 污染下游 AR 反推 | 不可 |

违反只读原则的候选不进入候选表（§6）。

### 2.3 三重门槛

只读原则只筛「能不能接」，不筛「值不值得接」。一个 PPP 侧候选要成立，必须同时通过三关：

| 门槛 | 判据 | 不通过的后果 |
|---|---|---|
| **M1 对象存在** | 要拦截的坏解真的会输出到 `sol` | 没有收益对象，训练样本也无处来 |
| **M2 收益可量测** | 收益能用参考真值直接量测（误差、拦截率） | 无法给出 G-1 类判据，评估协议无从写 |
| **M3 有确定性对照** | 能用几行确定性代码先量出「不做 AI 能拦多少」 | 无法证明 AI 增量，等于把结论建立在未验证的假设上 |

M3 是本文新增的硬门槛，也是否决候选 A 的直接依据：**如果确定性方案已拦住大部分，AI 的增量不足以支撑训练管线成本；如果拦不住，那时才谈模型，也才有基线可比。**

### 2.4 三门槛对候选的作用

| 候选 | M1 对象存在 | M2 收益可量测 | M3 确定性对照 | 结果 |
|---|---|---|---|---|
| A PPP 解有效性 | 部分（浮点解确实输出） | 是（参考轨迹） | **否**（`qr` 阈值几行即可，未做） | 否决（§5.1） |
| D PPP-RTK 固定校验 | **否**（AR 不进输出 + NL 从未固定） | 否 | 否 | 否决（§5.2） |
| B PPP 纯方差判别 | 是 | 是 | 否（且受降级语义阻塞） | 否决（§5.3） |

---

## 3. PPP 体系现状（代码事实）

本节只陈述代码事实，不作评价。三条链路的现状是后续全部结论的事实基础。

### 3.1 PPP 浮点链路：三道前置之后才定级

`updateStat` 里的 `ns` 判断（[PppCore.java#L1234-L1236](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppp/PppCore.java#L1234-L1236)）：

```java
if (rtk.sol.stat != Constants.SOLQ_FIX) {
    rtk.sol.stat = (byte)(rtk.sol.ns < MIN_NSAT_SOL ? Constants.SOLQ_NONE : stat);
}
```

但它只在 commit 成功时被调用（[PppCore.java#L249-L251](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppp/PppCore.java#L249-L251)），而 `stat = SOLQ_PPP` 只在 commit 分支被赋值（[PppCore.java#L239-L246](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppp/PppCore.java#L239-L246)）。进入该分支前已过三道关：

| 关 | 位置 | 判据 |
|---|---|---|
| 迭代上限 | [PppCore.java#L209](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppp/PppCore.java#L209) | `MAX_ITER = 8`（[L32](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppp/PppCore.java#L32)），8 次内未 commit 则 `stat` 停在 `SOLQ_SINGLE` |
| KF 数值 | [PppCore.java#L235-L237](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppp/PppCore.java#L235-L237) | `KalmanFilter.update` 返回 `info != 0` → `break`，不 commit |
| 后验残差 | [PppCore.java#L239-L246](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppp/PppCore.java#L239-L246)、[#L968](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppp/PppCore.java#L968) | 后验重算按 `THRES_REJECT = 4.0`（[L763](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppp/PppCore.java#L763)）剔除，发生剔除则 `pppRes` 返回 0 → 不 commit |

三道关全未通过时 `stat` 停在 `SOLQ_SINGLE`，`updateStat` 不被调用，`sol.stat` 不变 → `PppProcessor` 侧 `sol.stat != SOLQ_NONE` 不成立 → **该历元根本不输出**。

因此 PPP 浮点的真实失效形态不是「把米级误差当厘米级输出」，而是两点：

1. **`SOLQ_PPP` 无精度等级**——RTK 的 `SOLQ_FIX` / `SOLQ_FLOAT` 隐含厘米级 / 分米级，PPP 只有一个 `SOLQ_PPP`，可能对应 3 cm，也可能对应 3 dm，下游无法按状态分流；
2. **协方差已算出但未参与定级**——同一函数 6 行之后就把位置方差写进解（[PppCore.java#L1238-L1241](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppp/PppCore.java#L1238-L1241)）：

```java
rtk.sol.qr[i] = (float) rtk.P[i * nx + i];   // 位置方差已导出，stat 未使用
```

第 2 点是候选 A 的确定性替代方案来源（§5.1）。

### 3.2 PPP-AR 链路：门控密度足够

PPP-AR 的门控参数（[PppArOpts.java#L16-L47](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/config/PppArOpts.java#L16-L47)）：`arRatioWl = 2.0`、`arRatioNl = 3.0`、`arWlMaxdev = 0.20`、`arNlMaxdev = 0.15`、`arNlMaxsig = 0.15`、`arNlMinsav = 4`，另有 δ 门一致性检验。**六道门控参数 + 一致性检验**，已覆盖固定校验的功能位置，AI 在此处是重复建设。

PPP-AR 固定校验另被两项数据条件排除：错误固定率 0.1%–1%，按 G-1 前置（≥100 事件）需 160 站·时级数据；且该链路可解释性要求高。见 §6。

### 3.3 PPP-RTK 链路：门控弱，坐标不进输出

**门控确实弱。** `PppRtkAmbFix` 只有一个 ratio 3.0 单点检验，之后无任何检查：

| # | 事实 | 位置 |
|---|---|---|
| 1 | 门控只有 `ratio < arRatio` 一处（默认 3.0） | [PppRtkAmbFix.java#L141-L148](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppprtk/PppRtkAmbFix.java#L141-L148) |
| 2 | 固定后**无验后校验**——直接写 `xa[idx]`、`sol.stat = SOLQ_FIX`。对比 RTK 有 `valpos(v, R, vflg, nv, 4.0)` | [PppRtkAmbFix.java#L163-L187](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppprtk/PppRtkAmbFix.java#L163-L187) |
| 3 | 固定后**不回滚、不重解**——`Pa` 对应行列清零、`ssat[].fix[] = 1`，无位置条件更新 | [PppRtkAmbFix.java#L168-L173](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppprtk/PppRtkAmbFix.java#L168-L173) |

对照 PPP-AR 的六道门控，**PPP-RTK 一条都不具备**。这是结构性缺失，不是参数偏松。

**但坐标不进输出。** 三条事实：

**① `fix[]` 是逐历元重置的，不是持久标记**

[PppRtkCore.java#L115-L119](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppprtk/PppRtkCore.java#L115-L119) 在 `ppprtkos` 入口无条件执行 `rtk.ssat[i].fix[j] = 0`。AR 打的 `fix[f] = 1/2` 到下一历元即消失。

**② UC（非组合）路径不回写滤波器**

[PppRtkAmbFix.java#L160-L188](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppprtk/PppRtkAmbFix.java#L160-L188) 只改局部 `xa[idx]`、清 `Pa` 行列，然后 `rtk.xa = xa; rtk.Pa = Pa;`。全仓库读回 `xa`/`Pa` 的只有 `RtkCore` / `CascadeAR` / `PartialAR`，**`ppprtk` 包内零处**。UC 下滤波器状态不受污染。

**③ 位置从不重解，`updateStat` 已补 FIX 分支（FIX-ALG-054，2026-11-11）**

UC 路径 `xa` 是 `rtk.x` 的整体拷贝，只改模糊度分量，`xa[0..2]` 恒等于浮点位置。`updateStat` 当前形态（[PppRtkCore.java#L1017-L1045](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppprtk/PppRtkCore.java#L1017-L1045)）：

```java
// 入口（ppprtkos）无条件重置：标签逐历元独立，不再锁存
rtk.sol.stat = Constants.SOLQ_NONE;

// updateStat：只认本历元 AR 写入的标签；ns 不足时 FIX 同样降级
boolean arFixed = rtk.sol.stat == Constants.SOLQ_FIX;
if (rtk.sol.ns < MIN_NSAT_SOL) {
    rtk.sol.stat = Constants.SOLQ_NONE;
    arFixed = false;
} else {
    rtk.sol.stat = (byte)(arFixed ? Constants.SOLQ_FIX : stat);
}

// FIX 时从 xa/Pa 取 rr/qr（对齐 RtkCore 的 stat==SOLQ_FIX 分支）
if (arFixed && rtk.xa != null && rtk.Pa != null) {
    for (int i = 0; i < 3; i++) {
        rtk.sol.rr[i] = rtk.xa[i];
        rtk.sol.qr[i] = (float) rtk.Pa[i * nx + i];
    }
} else {
    for (int i = 0; i < 3; i++) {
        rtk.sol.rr[i] = rtk.x[i];
        rtk.sol.qr[i] = (float) rtk.P[i * nx + i];
    }
}
```

**v1.0 原文此处写「PPP-RTK 侧没有这个分支」，该表述已作废**：FIX-ALG-054 已补齐 FIX 分支，与 [RtkCore.java#L539-L546](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L539-L546) 对齐；`sol.stat` 亦由「永久锁存」改为「逐历元真实」。

但**位置条件更新仍未实现**：代码注释自述「IFLC 分支当前不做位置条件更新，`xa[0..2]` 恒等于 `x[0..2]`……位置重解需另立条目」。故结论收敛为——**`stat` 真实、坐标仍是浮点**。

净结果：

| 配置 | 输出位置 | 输出协方差 | `sol.stat` | 跨历元污染 |
|---|---|---|---|---|
| UC + `enableFixHold = false`（默认） | 浮点 | 浮点 | `SOLQ_FIX` | **无** |
| UC + `enableFixHold = true` | 浮点（经 hold 伪观测间接牵动） | 浮点 | `SOLQ_FIX` | 有（`fixAndHold` 写 `rtk.x`/`rtk.P`） |
| IFLC（任意） | 浮点 | 浮点 | `SOLQ_FIX` | 有（`initx(..., 0.0, idx)` 焊死模糊度状态） |

默认配置是 `enableAR = false`、`enableFixHold = false`（[PppRtkOpts.java#L17-L21](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/config/PppRtkOpts.java#L17-L21)）。而 [SSR_ACCESS_PLAYBOOK.md](SSR_ACCESS_PLAYBOOK.md) 记录的生产实测配置是**三频 3F（非 IFLC）+ 未开 fix-and-hold**，恰好落在**第一行**——即「AR 只留下一个标签」。

### 3.4 三条链路的共同结构结论

| 链路 | 缺陷是否存在 | 缺陷是否进入输出 | 是否有训练样本 |
|---|---|---|---|
| PPP 浮点 | 有（`SOLQ_PPP` 无精度等级） | **是**（浮点解真实输出） | 是 |
| PPP-AR | 弱（门控已密） | 是 | 否（错误固定 0.1%–1%，G-1 前置需 160 站·时） |
| PPP-RTK | 强（单点门控） | **部分**（`stat` 逐历元真实、坐标仍是浮点，§3.3） | 否（NL 固定不稳定，05 时起归零） |

**「缺陷存在」与「缺陷可见」是两件事。** PPP-RTK 的缺陷最严重，但坐标不重解、结果不输出，**收益对象不完整**（FIX-ALG-054 后 `stat` 真实、坐标仍是浮点）；PPP 浮点的缺陷最轻，但它是唯一真实输出、可用真值量测的链路。这张表是 §5 全部否决结论的依据。

---

## 4. 候选接口

### 4.1 候选 A：PPP 解有效性判别

**目标**：给 `SOLQ_PPP` 补一个精度等级，或按位置方差把不可信历元降级。

**接口改动：需要新接口。** `FixQualityContext` 的字段（`adrRms` / `prrRms` / `pcrRms`）均来自 `ddres` 双差残差，PPP 非差链路无对应物。两个选择：

| 方案 | 做法 | 评价 |
|---|---|---|
| A-1 | 新建 `SolutionValidityChecker.validate(SolutionValidityContext)`，context 为非差语义（PPP 残差、`P` 对角元/条件数、`ns`、收敛度、bias 状态） | 推荐。与已冻结的接口一 SPI 隔离，不污染 `FixQualityContext` |
| A-2 | 泛化 `FixQualityContext` 为「解质量上下文」 | 不推荐。破坏已冻结 SPI，需重跑 I-1，且两者特征不同源 |

**模型改动：需要新模型。** 特征全新建，不能复用 `fix_validator.onnx`。参考方法：

| 方法 | 来源 | 特点 |
|---|---|---|
| Isolation Forest | 武大李星星团队 2024，实时 PPP 改正数异常检测 | 无监督，不需稀有事件标签，只做告警标记 |
| Dense autoencoder | ION GNSS+ 2026，CORS 质量监测 | 输入含 PPP 无电离层伪距残差、SNR、多路径组合、仰角方位角，无监督 |

两者均为「只读标记」范式，与候选定位一致。但无监督是**可选项，不是低成本理由**：PPP 默认配置下 `SOLQ_PPP` 输出率高，「误差超阈值」并非稀有事件；监督路线可直接复用接口一已建成的全套管线（采集器 → trace 对齐 → 真值打标 → 两层切分 → 验证集扫阈值 → G-1~G-4），且能与 G-1 横向比较；无监督路线必须新写「告警精度/召回」协议，异常分数没有 G-1 这个量。

**框架改动：复用。**

**待明确**：有效解阈值（3 cm / 10 cm / 1 m，决定 A 拦掉多少历元、收益-代价平衡点，与接口一 0.1 m 标定同类）；特征来源逐项确认；标签生成需带参考轨迹的 PPP 数据。

### 4.2 候选 D：PPP-RTK 固定校验

**目标**：补上 `PppRtkAmbFix` 缺失的验后校验。

**前置第 0 条（确定性工程，与 AI 无关）**：先修 §3.3 的输出缺陷，否则校验对象残缺——校验器面对的是「浮点解 + FIX 标签」，学不到「固定坐标错在哪里」。两处改动：

1. UC 路径在 `xa[idx]` 赋值后做位置条件更新（RTKLIB 原生 `resamb` 语义）；
2. `updateStat` 补 `if (stat == SOLQ_FIX)` 分支，从 `xa`/`Pa` 取 `rr`/`qr`（照抄 [RtkCore.java#L539-L546](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L539-L546)）。

**接口改动：需要新接口。** 特征来源（WL/NL 两级 ratio、SSR 改正质量、固定后残差）与 `FixQualityContext` 的双差语义不同源，需新建 `PppRtkFixValidator` + context。

**模型改动：需要新模型。** 参考方法：XGBoost（服务端网络端模糊度验证）或轻量 MLP（用户端）。ION GNSS+ 2025 用 XGBoost 做 PPP-RTK 网络端模糊度验证，测地站宽巷固定正确率 75% → 97%，错误固定率 4.5% → 1% 以下；但该工作为服务端，非用户端解算链。

**框架改动：复用。**

**数据前置：SSR 已可得，固定事件不可得。**

依据 [SSR_ACCESS_PLAYBOOK.md](SSR_ACCESS_PLAYBOOK.md)：

- CAS `SSRA01CAS0` / `SSRC01CAS0` 已接入并完成录制/回放闭环验证，2026-11-05 实时复测 60 s 收到 42,802 字节 / 87 帧、92 颗星可用；
- CAS0 含相位偏差（`1270` BDS），故**具备 AR 条件**；`CAS1` 是 4076 专有电文，core `Rtcm` 不识别，不可用（该文档 §七 已知坑 9）；
- 账号密码不入库，走环境变量/本机配置（同文档 §三）。

剩余前置只有「固定事件基数」，而这一项**已有实测结论，且不利**：该文档 §六 验收清单记录「WL 100% 固定、NL 受可见卫星数限制未达 FIX（结论归档，算法冻结不调门限）」，§七 说明生产用三频 3F。

即：**NL 从未固定 → 生产上不存在 `SOLQ_FIX` 输出 → 既没有收益对象，也没有训练样本**。这与 §3.3 的「即使固定也不进输出」叠加，构成 D 的双重阻塞。

### 4.3 候选 B：PPP 纯方差判别

**定义**：接口三（`ObsCorrector`）实际做了两件性质不同的事：

```java
v[nv] -= aiBias[...] - aiBias[...];   // 改偏差 → 进状态 → 危险
Ri[nv] *= aiScale[...];               // 改方差 → 不进状态 → 安全
```

`aiBias` 通过 `v` 进入 KF 更新、污染模糊度状态；`aiScale` 只改观测权重，不改变状态物理含义。B 即「只输出 `varianceScale`、`biasMeters` 恒为 `null`」的裁剪版。

**接口改动：存在硬阻塞。** 直觉方案「impl 返回 `biasMeters = null`、`varianceScale` 有值」不成立——core 的校验会**丢弃整个改正**（[RtkCore.java#L1743-L1747](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1743-L1747)）：

```java
if (cc == null || cc.biasMeters() == null || cc.varianceScale() == null
        || cc.biasMeters().length != ns * nf || cc.varianceScale().length != ns * nf) {
    LOG.warn("接口三输出非法（null 或长度不符，期望 {}），本历元按不干预处理（epoch={}）", ...);
    return;
}
```

SPI javadoc 同义（[ObsCorrector.java#L16-L21](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ai/spi/ObsCorrector.java#L16-L21)）。按直觉方案原样实现，B 运行时等于**恒不干预**，不是「只改权重」。只有两条路：

| 路径 | 代价 |
|---|---|
| 放宽 core 的 null 检查（`biasMeters == null` 时 `aiBias` 全 0、`scale` 照用） | 改已冻结的降级语义，**必须重跑 I-1**，降级分支从两条变三条 |
| 新增独立 SPI（如 `VarianceScaler`） | 与 A/D 同等量级：`AiProvider` 加方法 + `AiHooks` 解析 + 目标链路注入点 |

**模型改动：需要新模型。** 训练目标与接口三不同：

| 项 | 接口三 | B |
|---|---|---|
| 回归目标 | `bias` + `σ_m` | 仅 `varianceScale` |
| 标签来源 | 逐星伪距误差真值 | 残差异常间接监督，或定位误差反传 |
| 可否复用 | — | 不可复用接口三模型 |

参考方法：pyrtklib / TDL-GNSS 的权重学习思路（用定位误差作为损失间接学权重），但需从逐星改为批量推理，训练目标重新设计。

**框架改动：复用。**

**两条被澄清的限制：**

1. **joint 不是天然可复用**。若用 DD 残差做特征，同一 rover 卫星在基线 A / B 各有参考星与残差，`scale` 必然不一致，仍进不了 joint（与 [AI_ENHANCEMENT_DESIGN.md](AI_ENHANCEMENT_DESIGN.md) §1.5.1 / D11 一致）。B 只有一个能进 joint 的变体：特征基线无关（仰角、SNR、非差多路径组合、非差伪距残差）。
2. **跨链路不是零成本**。RTK 与 PPP 的观测方差来自两套独立实现（`RtkCore.varerr` 与 `PppCore.varerr`），跨链路意味着**两个注入点、两套特征语义（双差 vs 非差）、两个训练集**。

### 4.4 候选总表

| 编号 | 接口 | 目标链路 | 只读性 | 数据成本 | 三层改动量 |
|---|---|---|---|---|---|
| A | 解有效性判别 | PPP（默认配置） | 只读标记 | 低 | 中（新接口 + 新模型，可复用接口一管线） |
| D | 固定校验 | PPP-RTK | 只读门控 | 高（SSR 已可得，但无固定事件） | 大（且前置为算法修复，非 AI） |
| B | 纯方差判别 | PPP / RTK（需两套注入点） | 只改权重 | 中 | 中（须先解降级语义阻塞） |

---

## 5. 逐条否决

本节把 §2.3 的三重门槛逐条落到候选上。**三条否决理由彼此独立，任一成立即足以不接入。**

### 5.1 A 被确定性方案替代（否决依据：M3）

位置方差已经导出到 `sol.qr`（§3.1 第 2 点），存在一条**不需要模型、可审计、零训练成本**的替代方案：

```
qr[0] + qr[1] + qr[2] > 阈值  →  降级
```

| 情形 | 结论 |
|---|---|
| 确定性方案已拦住大部分坏历元 | AI 的增量不足以支撑采集 + 训练 + 评估管线成本 |
| 确定性方案拦不住 | 那时才谈模型，且已有基线可比 |

**必须先量出这个数，A 才谈得上立项。** 当前这个数不存在，所以 A 的收益是未验证的假设，不是结论。

### 5.2 D 无收益对象（否决依据：M1）

两重阻塞，各自独立致命：

| 阻塞 | 事实 | 后果 |
|---|---|---|
| ① 坐标不进输出 | §3.3：位置从不重解；`updateStat` 在 FIX-ALG-054 后已从 `xa`/`Pa` 取值，但 `xa[0..2]` 恒等于 `x[0..2]`，`rr` 仍是浮点坐标 | 固定坐标从未输出，校验器无可拦对象 |
| ② NL 固定不稳定 | §4.2：生产用三频 3F，实测 NL 未达稳定 FIX（11-11 回放 05 时起归零） | 生产上 `SOLQ_FIX` 输出稀疏，收益对象与训练样本均不足 |

**先解决 NL 固定率与输出缺陷，都是确定性工程，不是 AI 能替代的。** D 若要重启，顺序必然是：先修算法 → 再修输出 → 最后才谈校验模型。

### 5.3 B 无精度收益（否决依据：M2 + 降级语义）

B 复用 core 的单边钳位（[RtkCore.java#L1767-L1769](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1767-L1769)：`s < 1.0 → 1.0`），**只能降权、不能升权**。「这条观测比模型认为的更好」无法表达。

| 维度 | B 的收益 |
|---|---|
| 可靠性 | 有。方差膨胀是 NLOS / 多路径的主要抓手 |
| 精度 | **无**。单边钳位决定了它无法改善精度 |
| 安全性 | 优于接口三。权重错误最坏是低估某颗星，不是把错误偏差焊进状态 |

即 B 的价值只剩可靠性一层，且要先付出「改已冻结降级语义并重跑 I-1」或「新增独立 SPI」的代价（§4.3）。在 A 与 D 都已被否决的前提下，B 单独不足以支撑接入。

---

## 6. 明确排除的方向

| 方向 | 排除理由 |
|---|---|
| 接口三接 PPP | 偏差被 `IB(sat, frq)` bias 状态吸收，污染 AR 输入。估计器结构问题，非数据问题 |
| PPP-AR 固定校验 | 数据成本 160 站·时级（错误固定率 0.1%–1%，按 G-1 前置需 100 事件）；且门控已密（§3.2）；可解释性问题 |
| PPP-FIXED | `PMODE_PPP_FIXED = 9` 为死常量，无行为分支，实际等价 PPP-KINEMA |
| PPP-STATIC 固定校验 | 静态长时间平均抹平随机误差，逐历元 AI 干预收益趋近 0 |
| 对流层/电离层先验修正 | 违反只读原则，写状态最坏是灾难 |
| 场景自适应阈值 | 与已有 `AdaptiveController` + `PresetManager` 正面竞争，黑箱不可审计 |
| 周跳检测 | 已有 GF/MW/LLI/Doppler 四道防线，边际收益低 |
| 部分固定子集选择 | 收益太小 |
| 多星联合粗差探测 | 工程重、标签难。**推迟**而非否决，优先级低于 A/B |

---

## 7. 框架层（若未来接入）

本节结论与 §8 不冲突：框架层改动是**接入的前提条件**，不是接入的理由。

### 7.1 现状问题

`AiProvider` 只有两个方法、无 default 实现：

```java
public interface AiProvider {
    FixValidator fixValidator();
    ObsCorrector obsCorrector();
}
```

每加一个新接口，都必须改 core SPI，所有现有实现（`OnnxAiProvider`、测试）编译期全要改。

### 7.2 推荐做法

**core 侧给 `AiProvider` 加 `default { return null; }` 方法，组合逻辑全在 rtklib-ai 侧。**

| 优点 | 说明 |
|---|---|
| 向后兼容 | 现有实现不改，`default` 返回 `null` 即关闭 |
| 不破坏已冻结 SPI | 不需要重跑 I-1 |
| 多接口共存 | `AiHooks` 为 ThreadLocal 单槽，多接口同时开时由 rtklib-ai 侧复合 provider 返回多个实现，core 不动 |

不推荐 `resolve(String key)` 泛型注册——破坏已冻结 SPI，需重跑 I-1。不推荐复合 provider 在 core 侧组装——违反依赖方向。

需注意：`default` 方法只解决「加方法不破坏实现」，**不解决「新增注入点」**。每个新接口仍需在 core 的目标链路加注入点，且**每个注入点各自需要独立的 I-1 验证**。

### 7.3 多接口收益不可线性相加

多个接口串联在同一解算链上，前一个接口的错误会通过状态污染后一个接口的输入。约束：

- 一次只开一个接口做 A/B（对照 [AI_ENHANCEMENT_DESIGN.md](AI_ENHANCEMENT_DESIGN.md) §7.1 四组要求）；
- 每个接口单独过 G-1~G-5，不打包测试；
- 组合开启须重新评估，不能假定叠加有效。

注：只读标记类接口（如 A）不改状态，其错误不会污染权重类接口的输入，只会「少输出一个历元」。此类组合可独立评估；写状态类接口同时开才需联合评估。

---

## 8. 结论：交付层面不接入（技术实现验证见 §11）

**结论：PPP / PPP-AR / PPP-RTK 三条链路，本阶段不新增 AI 接口。**

### 8.1 三条独立理由

1. **唯一「对象确定、收益可量测」的候选（A）能先用确定性手段验证。** 位置方差已导出到 `sol.qr`，加一个阈值降级分支即可量出「不做 AI 能拦多少」。若确定性方案已拦住大部分，AI 的增量不足以支撑训练管线成本；若拦不住，那时再谈模型，也才有基线可比（§5.1）。
2. **D 的收益对象在生产上不完整。** 两重阻塞：① 坐标不进输出（§3.3，FIX-ALG-054 后 `stat` 已真实但坐标仍是浮点）；② NL 固定不稳定（§4.2，11-11 回放 05 时起归零）。收益对象残缺，且无训练样本（§5.2）。
3. **AI 的总前提已在接口一上得出阶段性答案（v1.1 更新）。** 接口一已完成训练与评估：**G-3 原判据在 B 档（4/7）、C 档（6/7）满足；A 档 1 pp / 30% 经数学推导与尾部形态实测，标记为不可达（0/7）**（[AI_ENHANCEMENT_DESIGN.md §7.4.4](AI_ENHANCEMENT_DESIGN.md#L1140-L1148)）。结论应读作「**可交付档位上有方向性收益，原始预注册判据不可达**」，而非「AI 无用」或「AI 已达标」。PPP 侧错误事件更稀有、数据成本更高、评估协议需另写，因此在同一判据体系下更难证明——但这不构成对**技术可实现性**的否决（§11）。

### 8.2 收益量级的诚实估计

| 维度 | 现实可期待的收益 |
|---|---|
| 可靠性 | 显著，是 AI 唯一有明显收益的维度。**可交付档位**（B 档 ≤2 pp / ≥20%、C 档 ≤5 pp / ≥15%）上有方向性收益；原始 30% 判据经 §8.2.2 证明不可达（§7.4.4）。**该数字的前提是「目标解真实输出」**，PPP-RTK 当前只满足一半（§3.3） |
| 精度 | 很小。正常精度由观测质量和改正模型决定。接口三有偏差改正能力，理论上最多 5–15% 残差改善（需实证复核）；**候选 B 完全没有精度收益**（§5.3） |
| 差环境 | 中等偏上，取决于接口选择。选 A 有用；周跳检测收益被四道现有防线吃掉；阈值自适应与已有机制重叠 |

**必须明确的一点**：AI 不会让「差环境变得能固定」。差环境固定不了的根本原因是几何和观测质量，不是判别能力。AI 能做的是在差环境里少输出错解，不是「变好」。目标应写为「降低差环境错误解输出率」，而非「提升差环境固定率」。

### 8.3 不接入 ≠ 否决方向

不动，是把顺序摆正：**先让接口一在真实数据上跑出档位制结论。** 该实验已完成（层次 A：B 档 4/7、C 档 6/7 满足 G-3 原判据；A 档 1 pp / 30% 标记不可达，见 [AI_ENHANCEMENT_DESIGN.md §7.4.4](AI_ENHANCEMENT_DESIGN.md#L1140-L1148)），因此「AI 在 GNSS 固定校验上有没有用」已有阶段性答案：**可交付档位上有方向性收益，幅度未达原始预注册判据**。A / D 的立项前提因此只剩 §5.1 的确定性对照（即 §11.3 的 P4）。

在 PPP 体系内部，确定性工程仍有明确待办，且都不需要 AI：

| 待办 | 性质 | 说明 |
|---|---|---|
| PPP-RTK AR 输出缺陷（§3.3） | 确定性修复 | 位置条件更新 + `updateStat` 的 `SOLQ_FIX` 分支 |
| PPP-RTK NL 固定率（§4.2） | 算法 | 是 D 的任何后续工作的前提，与 AI 无关 |
| `qr` 阈值降级对照（§5.1） | 确定性对照 | 量出「不做 AI 能拦多少」，为 A 提供基线 |
| `SOLQ_PPP` 精度等级 | 接口设计 | 是否引入类似 `FIX`/`FLOAT` 的分级，属确定性设计问题 |

---

## 9. 重开条件

| 触发 | 状态（v1.1 核对） | 动作 |
|---|---|---|
| 接口一在真实数据上通过 **§7.4.4 档位制 B 档或 C 档**（v1.0 原文写「通过 G-1（≥30%）」，该数字已被 §7.4.4 标记为**不可达**，v1.1 修订） | **已满足**：层次 A（交付形态）下 B 档 4/7、C 档 6/7，均满足 G-3 原判据「≥2 个未见站」 | 按 A → D → B 顺序重启评估，复用接口一管线 |
| PPP-RTK 的 NL 能稳定达 FIX，且 AR 输出缺陷修复并通过精度回归 | **未满足**：FIX-ALG-054 已修锁存与 FIX 分支，但位置条件更新未实现；NL 固定 05 时起归零 | 重启 D 的量测与立项评估 |
| 确定性 `qr` 阈值对照显示拦截率 < 50% | **未执行**（全仓库无实现） | A 的 AI 增量才有讨论空间 |

**v1.1 核对结论**：三条重开条件 **1/3 满足**。条件 1 的阻塞并非「没通过」，而是 v1.0 引用了已被 §7.4.4 废止的旧数字——按档位制接口一**已达标**。因此「重启评估」在触发层面已成立，但 A 的模型立项仍须等条件 3 的 `qr` 基线（P4）落地，否则收益增量无法量测（M2）。

---

## 10. 已撤回的论断（防止重启时重犯）

以下是评估过程中被代码事实推翻的旧论断。保留在此，避免重开时按旧依据排序。

| # | 旧论断 | 事实 | 依据 |
|---|---|---|---|
| 1 | 「PPP 解有效性判断仅一行，空白最大」 | `updateStat` 之前还有三道关：`MAX_ITER = 8`、KF `info != 0`、后验残差 `THRES_REJECT = 4.0` | [PppCore.java#L209](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppp/PppCore.java#L209)、[#L235-L246](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppp/PppCore.java#L235-L246) |
| 2 | 「不检查协方差是否可信」 | 协方差已导出到 `sol.qr`，只是未参与 `stat` 定级；真实缺陷是 `SOLQ_PPP` 无精度等级 | [PppCore.java#L1238-L1241](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppp/PppCore.java#L1238-L1241) |
| 3 | 「无监督是 A 数据成本低的核心原因」 | 前提不成立：PPP 输出率高、误差超阈值不稀有；监督路线可复用接口一管线且能与 G-1 横向比较 | [PppCore.java#L1234-L1236](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppp/PppCore.java#L1234-L1236) |
| 4 | 「B 不需要改 SPI，`biasMeters = null` 即可」 | null 会让整个改正被丢弃、`scale` 一并作废，等于恒不干预 | [RtkCore.java#L1743-L1747](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1743-L1747)、[ObsCorrector.java#L16-L21](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ai/spi/ObsCorrector.java#L16-L21) |
| 5 | 「B 天然可复用到 PPP / PPP-RTK / joint」 | joint 需特征基线无关；跨链路需两套注入点与两个训练集 | [AI_ENHANCEMENT_DESIGN.md](AI_ENHANCEMENT_DESIGN.md) §1.5.1 / D11 |
| 6 | 「B 改权重可带来 5–15% 精度改善」 | 单边钳位只能膨胀不能收缩，B 无精度收益；5–15% 归属接口三 | [RtkCore.java#L1767-L1769](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java#L1767-L1769) |
| 7 | 「PPP-RTK 比 PPP-AR 的两级门控更弱」 | 表述不足：PPP-RTK 是结构性缺失三点，PPP-AR 实为 6 个门控参数 + δ 门 | [PppRtkAmbFix.java#L141-L187](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppprtk/PppRtkAmbFix.java#L141-L187)、[PppArOpts.java#L16-L47](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/config/PppArOpts.java#L16-L47) |
| 8 | 「D 是唯一结构性缺失，错误代价跨历元持续，收益-风险比最高」 | 不成立：`fix[]` 每历元入口清零，UC 不回写滤波器；FIX-ALG-054 后 `stat` 已逐历元真实，故「结果不进输出」应精确表述为「**坐标不进输出**」 | [PppRtkCore.java#L115-L119](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppprtk/PppRtkCore.java#L115-L119)、[PppRtkAmbFix.java#L160-L188](file:///D:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppprtk/PppRtkAmbFix.java#L160-L188) |
| 9 | 「D 的错误代价大、拦截收益高」 | 双重阻塞：坐标不重解（`stat` 已真实）+ NL 固定不稳定（05 时起归零） | §3.3、[SSR_ACCESS_PLAYBOOK.md](SSR_ACCESS_PLAYBOOK.md) §六、§七 |
| 10 | 「排序 D → A → B，能开 SSR 就选 D」 | 重排为 A → D → B；且 A 也需先做确定性对照，故本阶段整体不接入 | §5 |

---

## 11. 技术实现验证口径（P0~P4，v1.1 追加）

> 本节回答一个与 §8 不同的问题：**能不能做出来**，而不是**值不值得交付**。§8 的「本阶段不接入」结论不变；本节只新增一条**技术可实现性验证（PoC）**路径，并明确其结论上限。

### 11.1 为什么需要这条路径

数据永远不完整，是本项目的常态约束，不是临时困难。§7.4.1 的原始判据三项都要求**完整且足量的标注数据**：

| 判据 | 原始要求 | 现实约束 |
|---|---|---|
| G-1 | 相对下降 ≥30%，取置信下界 | 错误固定事件数常不足 100，下界必然塌到 0 |
| G-3 | ≥2 个未参与训练与早停的站 | 站数固定，留站后训练站不足 |
| 统计功效 | 95% 置信 / 80% 功效的 MDE 前置 | 样本不足时「不予判定」等价于「永远不判定」 |

且 G-1 的 30% 已被 [AI_ENHANCEMENT_DESIGN.md §8.2.2](AI_ENHANCEMENT_DESIGN.md#L1385-L1393) 证明**数学不可达**：`错误固定数 / AR 总历元 > 3.33%` 时，1 pp 预算内无论模型多好都取不到 30%，16 站中仅 7 站可判定。**这是代数结论，与数据量无关**——扩多少数据都不会改变。

因此本节**不是放宽判据**，而是**换一个问题**：把「有没有效果」换成「机制能不能跑通」。两者不可互相替代（§11.5）。

### 11.2 P0：四条安全契约（不可降级）

与数据量、与效果均无关，是**机制正确性的前提**。任一条不满足，PoC 结论无效。

| # | 契约 | 判据 | 违反后果 |
|---|---|---|---|
| P0-1 | **I-1 零回归** | 不注入 `AiProvider` 时，解算输出与 v3.4.0 逐位一致 | 全部历史结果失去可比性 |
| P0-2 | **降级语义** | 输出非法（`null` / 长度不符 / `NaN` / `Inf`）时按「不干预」处理并留痕 | 坏数据进入解算链 |
| P0-3 | **只读原则** | 只改权重 / 门控 / 标记，不写状态 / 先验 / 整数 | 污染 AR 反推，不可回滚 |
| P0-4 | **失败可回滚** | 每历元重算，下一历元可退回基线 | 变为不可恢复的污染 |

**P0 不因数据不完整而放宽。** 效果类判据可以降，契约类不可以——效果差可以不用，契约破则整个机制失效。

### 11.3 P1~P4：技术通过判据（允许降级）

| # | 判据 | 怎么判 | 数据要求 | 与效果类判据的关系 |
|---|---|---|---|---|
| **P1** | 链路跑通 | 整段数据不抛异常；除预期情形外不产生 `AI_DEGRADE`；抽样对账 `sat`/`frq` 行序 | 任意可跑数据 | 替代 A1 的契约部分 |
| **P2** | 不明显变差 | 相对不注入：固定率下降 ≤ 1.0 pp、错误固定率**不上升**、RMS 恶化 ≤ 5% 或 ≤ 1 cm。**点估计即可，不做显著性** | 单站单日即可 | 替代 A2，去掉 MDE 与置信区间 |
| **P3** | 确实在起作用 | 见 §11.4（**不接受「有输出」**） | 同上 | 替代 A3，去掉「≥10 行 / ≥3 星」门槛 |
| **P4** | 确定性基线 | `qr` 阈值降级方案的拦截率（顺带产出，**非门槛**） | 同上 | 正好是 §5.1 所需的 M3 对照 |

**P2 刻意保守**：固定率允许 1.0 pp 机械损耗，错误固定率一个点都不许涨。与 §7.5.2 的 A2-1/A2-2 同构，仅去掉显著性要求。

### 11.4 P3 的判定细则

[AI_ENHANCEMENT_DESIGN.md §7.5.3](AI_ENHANCEMENT_DESIGN.md#L1229-L1237) 已把「`biasMeters` 非零的卫星占比」列为**弱口径、不算通过**——它只证明有输出，不证明改对了。**即使降标准，这一条也不该退化成空判据**，否则 P3 恒真。

可用的最低形态（二选一，取可得者）：

| 形态 | 判据 | 说明 |
|---|---|---|
| 方向一致性 | 输出改正符号与真值误差符号**同号比例显著高于 50%** | 弱于 A3 的幅度判据，强于「有输出」 |
| 幅度下限 | 改正后残差绝对值的**中位数**低于改正前 | 不要求 A3 的 R%/Dσ 双门限，只看整体趋势 |

**不接受**：仅统计「输出非零行数」「模型调用次数」「推理耗时」——这些是链路指标，不是作用指标。

### 11.5 结论上限（须随 PoC 结论一并写）

| 可以声称 | 不可以声称 |
|---|---|
| 注入机制可用 | PPP 侧 AI 有收益 |
| 链路能跑通、契约正确 | 能降低错误解输出率 |
| impl 确实改变了 `sol` | 达到交付水平 |
| 不注入时零回归 | 可推广到生产 |
| 拦截率基线已量出（P4） | 跨站泛化成立 |

**技术通过 ≠ 有效。** 交付判定仍须走 §7.4 的 G-1~G-5 全套，且 §2.3 的 M2（收益可量测）依然成立。PoC 的产出是「机制可用性证据」，不是「效果证据」。

### 11.6 启动顺序（四步，成本递增）

| 步 | 动作 | 数据 | 产出 |
|---|---|---|---|
| 1 | 框架层：`AiProvider` 加 `default` 方法、`AiHooks` 加解析方法、`PppCore.updateStat` 加注入点 | 零 | P0-1 验证 |
| 2 | 候选 A 的**确定性** impl（`qr` 阈值降级），跑一次回放 | 零模型、零训练 | P1 + P4 基线 |
| 3 | 候选 A 的**模型** impl（新 SPI + 新特征集 + 新模型），用任意规模带参考轨迹的 PPP 数据跑通全链路 | 少量即可 | P1 / P3 |
| 4 | 候选 B 的独立 SPI（`VarianceScaler`），可选 | 少量 | P1 / P3 |

**Step 1 与 Step 2 零数据依赖**，应先做。Step 2 同时完成「机制验证」与「确定性对照」，把原本两个任务合并为一个。

**Step 1 的必要性**：PPP 侧目前**没有任何 `AiHooks` 调用**——全仓库仅 `RtkCore` 两处（`resolveObsCorrector` / `resolveFixValidator`），`PppCore` / `PppRtkCore` 零处。故 PPP 接入必须先新建注入点，这是前置动作，不是可选项。自然注入点为 `PppCore.updateStat`（`stat` 定级与 `rr`/`qr` 导出的唯一汇合处）。

### 11.7 与 §8 / §9 的关系

1. **§8「本阶段不接入」不变**：那是**交付**结论，判据是 G-1~G-5 全套。
2. **本节不构成重开条件**：§9 是「交付重启」的触发，本节是「技术验证」的许可，门槛与结论不同。
3. **§9 条件 1 已修订**（v1.1）：原「通过 G-1（≥30%）」引用了已被 §7.4.4 标记为不可达的旧数字，改为「通过 §7.4.4 档位制 B 档或 C 档」，按该口径**已满足**。

---

## 附录：术语与引用

| 术语 | 含义 |
|---|---|
| 只读原则 | 接口输出只改权重 / 门控 / 标记，不写状态 / 先验 / 整数 |
| 经验常数 | 硬编码阈值、经验公式，在特定场景下必然失配 |
| 数学最优环节 | 闭式最优解，AI 逼近只会更差 |
| M1 / M2 / M3 | 三重门槛：对象存在 / 收益可量测 / 有确定性对照（§2.3） |
| I-1 | 零回归不变量：`AiOpts` 全默认时，解算输出与 v3.4.0 逐位一致 |
| UC / IFLC | 非组合 / 无电离层组合，PPP 的两条观测路径 |

外部参考：

- 武大李星星团队 2024，Isolation Forest 实时 PPP 改正数异常检测
- ION GNSS+ 2026，CORS 质量监测 dense autoencoder
- ION GNSS+ 2025，XGBoost PPP-RTK 网络端模糊度验证
- pyrtklib / TDL-GNSS，RTKLIB 深度学习集成

---

**文档结束。** 本文档为 PPP 体系的 AI 接入技术评估。**交付层面**结论为**本阶段不接入**（§8）；**技术实现层面**允许按 P0~P4 开展 PoC 验证，其结论上限见 §11.5。若未来按 §9 重开交付评估，候选接口的契约细节（特征定义、注入点、降级语义、G 判据）需按 [AI_ENHANCEMENT_DESIGN.md](AI_ENHANCEMENT_DESIGN.md) §4 / §7 格式另行编写。