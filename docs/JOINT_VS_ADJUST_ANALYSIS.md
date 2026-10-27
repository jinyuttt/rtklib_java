# rtklib-joint 与 rtklib-adjust 对比分析

> 版本：v1.2  
> 日期：2026-10-27  
> 状态：定稿  
> v1.1 修正：adjust 的 WLS 组合是统计最优的，不存在"信息损失"；FIX 后两者位置精度等价（≈σ₀/√N），差异在 FLOAT 阶段模糊度收敛速度与 FIX 率。  
> v1.2 补充：adjust 的 R 矩阵对角块取自 core 输出原始协方差（Sol.qr → SolData.covEcef），非近似值；默认 rho=0 块对角。

---

## 1. 核心结论

| 维度 | rtklib-joint（观测层联合） | rtklib-adjust（结果层平差） |
|------|--------------------------|---------------------------|
| **解决什么** | 精度（观测冗余直接进滤波器） | 可靠性（交叉验证、粗差剔除、保护级） |
| **融合层级** | 观测级（共享 Kalman 滤波器） | 位置级（独立解算后 WLS 平差） |
| **适用环境** | 开阔、短基线、数据质量均匀 | 城市峡谷、遮挡、多路径、质量参差 |
| **故障隔离** | ❌ 共享位置，坏基线污染全体 | ✅ 独立解算，可剔除可疑基线 |
| **完整性监测** | ❌ 无保护级 | ✅ 分级保护级 + Baarda 探测 |

**一句话**：joint 提精度，adjust 保可靠性。两者互补而非替代。

---

## 2. 架构本质差异

### 2.1 数据流

```
joint（MBS 引擎）:
  [rover+baseA obs] ─┐
  [rover+baseB obs] ─┼→ 共享KF（一个 pos 状态）→ 单组解
                      ┘

adjust（结果空间平差）:
  [rover+baseA obs] → 独立RTK → 基线向量A ─┐
  [rover+baseB obs] → 独立RTK → 基线向量B ─┼→ WLS平差 → 解
                                              ┘
```

### 2.2 状态结构差异

**joint**：位置状态全局共享，模糊度按基线分离。

代码证据（[JointCore.java#L150-L151](../rtklib-joint/src/main/java/org/rtklib/java/joint/JointCore.java#L150-L151)）：

```java
int nxNew = npPerBaseline + nBaseline * (niPerBaseline + ntPerBaseline + nbPerBaseline);
jrtk.na  = npPerBaseline + nBaseline * (niPerBaseline + ntPerBaseline);
```

布局：`[pos(3)] [amb_bl0(nb)] [amb_bl1(nb)]`

**adjust**：每条基线完全独立，不共享任何状态。各基线由 `rtklib-core` 独立解算后，将位置向量作为虚拟观测送入平差。

### 2.3 数学本质

- **joint**：`x = [pos, amb_1, amb_2, ...]`，所有基线的双差观测同时约束同一个 `pos`。N 条基线 → 位置方差约降为 1/N（前提：误差零均值、各基线同质）。
- **adjust**：对 k 条独立基线解 `l_i = Rover - Base_i` 做加权最小二乘 `min Σ (l_i - H_i·x)ᵀ R_i⁻¹ (l_i - H_i·x)`。WLS 是统计最优的，FIX 后位置方差 ≈ σ₀/√N。短基线下相对单基线改善 <1% 的原因是残余误差以 Rover 端公共多路径为主（平均无法消除），而非融合信息损失。

---

## 3. 精度分析

### 3.1 joint 的精度收益来源

| 收益 | 机制 | 阶段 |
|------|------|------|
| FLOAT 位置方差 1/N | 共享 pos，N 组观测同时约束 | FLOAT |
| 模糊度收敛加速 | pos 方差小 → 模糊度方差小 → LAMBDA 搜索空间缩小 | FLOAT |
| FIX 率提升 | 模糊度浮点解更紧 → ratio 更高 → 更易固定 | 收敛期 |
| 共享大气参数 | `JointConfig.sharedIono/sharedTrop` 压制残余大气噪声 | 全过程 |

**关键**：joint 的精度优势主要在 **FLOAT 阶段和收敛期**。一旦模糊度全部 FIX，位置精度由几何和固定模糊度决定，与 adjust 等价。

### 3.2 adjust 的精度特征（非"信息损失"）

adjust 的 WLS 组合是**统计最优**的——对 k 个独立 FIX 解做加权最小二乘，不存在信息损失。FIX 后位置方差同样约为 σ₀/√N。

**协方差来源**：R 矩阵对角块直接取自 core 输出的原始协方差（`Sol.qr` → `SolData.covEcef`，完整 3×3 含非对角符号），非近似值。默认 `rho=0`，R 为块对角阵（假设各基线独立）。

adjust 精度不占优的原因是**残余误差结构**，而非融合损失：
- 各基线 FIX 解差异已在毫米级，残余误差主要是 **Rover 端公共多路径**
- 公共误差对所有基线相同，加权平均无法消除它 → 短基线下相对单基线精度改善 <1%
- 各基线模糊度独立解算，FLOAT 阶段无法共享 pos 信息 → 收敛略慢、FIX 率略低

**结论**：adjust 的价值不在精度，而在可靠性。其精度天花板与 joint 相同（σ₀/√N），只是到达天花板的路径更长（独立模糊度收敛）。

---

## 4. 可靠性分析（差环境下的关键差异）

### 4.1 联合滤波的隐含假设

joint 能提精度的前提是**各基线观测误差为零均值随机噪声**。遮挡、多路径是系统性、基线特异的误差，这个前提直接破产。

### 4.2 差环境下 joint 的五种失效模式

#### 失效 1：共享位置 = 共享故障

所有基线的 `KalmanFilter.update` 写同一个 `xp[0:3]`（[JointCore.java#L262](../rtklib-joint/src/main/java/org/rtklib/java/joint/JointCore.java#L262)）。

```
baseA ── 多路径严重 ──┐
                     ├── 共享 pos(3) ──→ 被 baseA 拉偏
baseB ── 信号良好 ────┘
```

baseA 的多路径残差把共享位置拉偏 → baseB 残差也变大 → **好基线被坏基线拖下水**。

**adjust 对策**：各基线独立解算，baseA 偏了直接被交叉验证发现并剔除（[GnssBaselineAdjust.adjustSuspect](../rtklib-adjust/src/main/java/org/rtklib/java/adjust/engine/GnssBaselineAdjust.java#L173)）。

#### 失效 2：模糊度-位置交叉协方差的污染扩散

状态布局 `[pos(3)][amb_bl0][amb_bl1]`，P 矩阵中位置与各基线模糊度有交叉协方差：

```
baseA 模糊度偏 → P[pos, amb_blA] 把 pos 拉偏 → P[pos, amb_blB] 把 baseB 模糊度拉偏
```

误差在状态向量内部循环放大。这与之前修复的 P4 问题同构——P4 是 H 列全零导致的无关更新，这里是真实但错误的观测。

#### 失效 3：全局参考星选择的脆弱性

`EpochAligner.selectGlobalRefSatBySys` 为所有基线选同一颗参考星。若参考星在 baseA 方向遮挡、baseB 方向良好：
- baseA 双差残差系统性偏大
- 联合滤波无法区分"参考星问题"还是"rover 位置问题"
- ratio 可能被 baseB 好数据撑高，掩盖 baseA 故障

#### 失效 4：周跳源定位困难

弱信号下周跳频发。baseA 某星周跳 → amb_blA 跳变 → 共享位置跳变 → baseB 所有星残差同时异常。从 baseB 看像是"所有星同时周跳"，**无法隔离真正故障源**。

#### 失效 5：联合 LAMBDA 的 ratio 稀释

`resolveAmbiguity` 对合并模糊度向量做 LAMBDA，ratio 是全向量度量。baseB 模糊度分离良好可把整体 ratio 拉高，**掩盖 baseA 模糊度的不确定性**，导致假固定。

### 4.3 adjust 的可靠性机制

| 机制 | 代码位置 | 作用 |
|------|---------|------|
| 交叉验证分级 | [GnssBaselineAdjust.java#L82-L88](../rtklib-adjust/src/main/java/org/rtklib/java/adjust/engine/GnssBaselineAdjust.java#L82-L88) | VERIFIED/CROSS_CHECKED/SUSPECT/UNVERIFIED |
| 可疑基线剔除 | adjustSuspect | 移除异常基线后重新平差 |
| Baarda 数据探测 | GnssBaselineAdjust | τ 检验识别粗差观测 |
| 保护级计算 | computeProtectionLevel | 按分级给出 PL，SUSPECT 时取 max(PL, maxDist) |
| 基站坐标诊断 | BaseStationDiagnoser | 检测基站位移/坐标异常 |
| 残差白度检验 | ResidualWhiteness | lag-1 自相关 + 游程检验 |
| 一致性检验 | BaselineConsistencyChecker | 重复基线/漂移/异步环闭合差 |

---

## 5. 对比矩阵

| 场景 | joint | adjust |
|------|-------|--------|
| 开阔、短基线、数据均匀 | FIX 后精度≈σ₀/√N；FLOAT 收敛更快、FIX 率更高（共享 pos 帮助模糊度收敛） | FIX 后精度≈σ₀/√N（WLS 最优，无信息损失）；各基线独立定模糊度，FLOAT 收敛略慢；可靠性强 |
| 单基线卫星不足 | 其他基线观测互补，维持 FIX | 该基线直接无 FIX |
| 某基站坐标有偏 | 全部基线被拉偏，无法察觉 | 交叉验证发现，剔除该基站 |
| 某基线假固定 | ratio 被好基线稀释，难发现 | 各基线独立 ratio，不一致 → SUSPECT |
| 周跳频发 | 故障扩散，难定位 | 各基线独立检测，互不影响 |
| 参考星异常 | 全局受影响 | 各基线自选参考星，独立 |
| 需要保护级/告警 | 不支持 | 原生支持 |
| 整时段最终成果 | 不支持 | NetworkAdjustment 批处理网平差 |
| 实时性 | ✅ 逐历元 KF | ✅ 逐历元平差 |
| 计算量 | 中（状态维度 = 3 + k·nb） | 低（独立解算 + 小矩阵平差） |

---

## 6. 互补关系与组合策略

### 6.1 为什么两者不能直接串联

joint 在观测层把 k 条基线并进同一个滤波器，输出时"多条独立基线解"这个中间产物已不存在；而 adjust 的输入恰恰是那些独立基线解。因此 joint 无法直接接入 adjust。

### 6.2 可行的组合方式

当前 `JointConfig.useNetworkAdjustment=true` 预留了组合路径。实际可行的策略：

1. **并行双轨**：同时跑 joint（提精度）和 SBS+adjust（保可靠），用 adjust 的可靠性分级来 gate joint 的输出。
2. **环境自适应切换**：用 SNR、GDOP、残差 RMS 监测环境，开阔走 joint，恶劣降级到 adjust。
3. **质量加权的 joint**：在 joint 的 R 矩阵中引入按基线质量的权重因子，让坏基线对共享位置的贡献自动降低。

### 6.3 选型建议

| 场景 | 推荐 |
|------|------|
| 测绘、静态、开阔环境 | joint（精度优先） |
| 车载、城市、遮挡多路径 | adjust（可靠性优先） |
| 需要保护级/安全认证 | adjust（完整性监测） |
| 短基线、多基站、数据均匀 | joint（精度收益最大） |
| 基站坐标可能不准 | adjust（基站诊断） |

---

## 7. 已修复的相关问题（FIX-ALG-042）

本次双基线 FIX=0 修复暴露了 joint 架构的三个固有风险点，已修复：

| 编号 | 问题 | 根因 | 修复 |
|------|------|------|------|
| P4 | 跨基线污染位置 | KF 状态压缩不检查 H 列是否全零，无关状态通过交叉协方差泄漏 | [KalmanFilter.java#L47-L52](../rtklib-core/src/main/java/org/rtklib/java/kalman/KalmanFilter.java#L47-L52) 增加 H 列非零检查 |
| P5 | holdamb 触发过晚 | minfix 门槛导致首次 FIX 后模糊度方差未压缩 | 去掉 minfix 门槛，每次 FIX 即 holdamb |
| P6 | FIX 后模糊度 P 未修正 | LAMBDA 后只改模糊度值，不改 P 中对应行/列 | FIX 后手动将模糊度 P 对角设 1E-6，交叉协方差清零 |

这些修复本质上是在弥补 joint 架构"共享状态"带来的耦合风险，但无法从根本上解决差环境下的可靠性问题——那需要 adjust 层的交叉验证机制。

---

## 8. 参考文档

- [JOINT_RTK_DESIGN.md](./JOINT_RTK_DESIGN.md) — joint 模块数学模型与状态设计
- [ADJUST_TECHNICAL_REFERENCE.md](./ADJUST_TECHNICAL_REFERENCE.md) — adjust 模块技术参考
- [ALGORITHM_FIX_LOG.md](./ALGORITHM_FIX_LOG.md) FIX-ALG-042 — 双基线 FIX=0 修复记录
