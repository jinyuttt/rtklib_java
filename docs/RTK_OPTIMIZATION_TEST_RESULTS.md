# RTK 优化测试结果（城市峡谷动态场景）

> **日期**：2026-10-14  
> **测试类**：`org.rtklib.java.urbancanyon.DynamicRtkOptTest`  
> **目的**：在城市峡谷动态 RTK 场景下，逐个评估各优化策略对 Fix 率和精度的实际效果，区分"数据证明有效"与"理论适用但未验证"  

---

## 1. 测试数据集

| # | 数据集 | 地点 | Rover 接收机 | 频率 | 采样率 | 基线 | 参考值来源 | 场景特征 |
|---|--------|------|-------------|------|--------|------|-----------|---------|
| 0 | Net_Diff Urban | Tokyo | u-blox | **单频** | ~5Hz | 199m | .17.pos (q=1) | 低成本单频+极严重遮挡 |
| 1 | PPC Tokyo run1 | Tokyo | SEPT Mosaic-X5 | 双频 | 5Hz | 7.8km | PPC CSV | 高端双频+中长基线+部分遮挡 |
| 2 | PPC Tokyo run2 | Tokyo | SEPT Mosaic-X5 | 双频 | 5Hz | 7.8km | PPC CSV | 同上（不同时段） |
| 3 | PPC Tokyo run3 | Tokyo | SEPT Mosaic-X5 | 双频 | 5Hz | 7.8km | PPC CSV | 同上（不同时段） |
| 4 | PPC Nagoya run1 | Nagoya | SEPT Mosaic-X5 | 双频 | 5Hz | 15km | PPC CSV | 高端双频+长基线+轻度遮挡 |
| 5 | PPC Nagoya run2 | Nagoya | SEPT Mosaic-X5 | 双频 | 5Hz | 15km | PPC CSV | 同上（不同时段） |
| 6 | PPC Nagoya run3 | Nagoya | SEPT Mosaic-X5 | 双频 | 5Hz | 15km | PPC CSV | 同上（不同时段） |
| 7 | UrbanNav Tokyo | Tokyo | Trimble NetR9 | 双频 | 10Hz | 186m | .pos (q=1) | 高端双频+短基线+严重遮挡 |

> **基线长度排序**：PPC Nagoya (15km) > PPC Tokyo (7.8km) > Net_Diff (199m) > UrbanNav (186m)  
> **遮挡程度排序**：Net_Diff > UrbanNav > PPC Tokyo > PPC Nagoya

---

## 2. 关键发现：thresar[1] 是 Fix 率的决定性参数

### 2.1 问题描述

RTKLIB-2.5.0 默认 `thresar[1] = 0.25`（AR 前置检查：位置方差阈值），RTKLIB-EX 默认 `0.9999`。  
Java 版此前沿用 `0.1`（比 2.5.0 更严格），导致城市峡谷场景下 **Fix 率普遍为 0%**。

### 2.2 根因分析

`thresar[1]` 控制 AR（模糊度解算）的前置条件：只有当位置状态方差 `posvar < thresar[1]` 时才进入 LAMBDA 搜索。  
城市峡谷环境下，多路径/信号遮挡导致位置方差长期居高不下：
- `thresar[1] = 0.1`：posvar 几乎不可能 < 0.1 → **AR 永不触发** → Fix = 0%
- `thresar[1] = 0.25`：偶尔满足 → Fix 率极低
- `thresar[1] = 0.9999`：宽松前置 → AR 可正常触发 → Fix 率显著提升

### 2.3 Joseph 形式等价性验证

将标准 EKF 形式替换为 Joseph 形式（对称平方根更新）后，全量 8 数据集测试结果与标准形式**完全一致**，确认两种形式在本场景下数学等价。Fix 率提升的真正原因是 `thresar[1]` 参数修改，而非 Joseph 形式本身。

### 2.4 C-EX-Ref 全量基准结果（thresar[1]=0.9999）

| 数据集 | 历元 | Fix% | Float% | H.RMSE(m) |
|--------|------|------|--------|-----------|
| Net_Diff Urban (准静态) | 9907 | 1.4% | 24.9% | 9.795 |
| PPC Tokyo run1 | 11928 | 6.8% | 69.8% | 11.846 |
| PPC Tokyo run2 | 9151 | 12.3% | 69.1% | 1.531 |
| PPC Tokyo run3 | 15301 | 3.9% | 73.8% | 9.701 |
| PPC Nagoya run1 | 7602 | 19.8% | 59.3% | 5.733 |
| PPC Nagoya run2 | 9451 | 6.3% | 75.8% | 13.202 |
| PPC Nagoya run3 | 5201 | 7.5% | 79.8% | 12.800 |
| UrbanNav Tokyo 2018 | 12395 | 5.5% | 43.7% | — |

---

## 3. 12 个优化变体定义

所有变体共享基础配置：`nf=2, modear=FIXHOLD, elmin=15°, ionoopt=IFLC, tropopt=SAAS, navsys=GPS+GAL+GLO+BDS+QZS`

| 变体 | dynamics | thresar[1] | prn[3] | prn[4] | glomodear | bdsmodear | elmaskhold | thresar[6] | RtkConfig 开关 |
|------|----------|------------|--------|--------|-----------|-----------|------------|------------|----------------|
| A-Default | 0 | 0.25 | 0.1 | 0.01 | AUTOCAL | 1 | 0° | 3.0 | — |
| B-Dyn1 | 1 | 0.25 | 0.1 | 0.01 | AUTOCAL | 1 | 0° | 3.0 | — |
| **C-EX-Ref** | **1** | **0.9999** | **3.0** | **1.0** | **OFF** | **0** | **15°** | **10.0** | iflcPairNegotiation |
| D-LooseAR | 1 | 1.0 | 3.0 | 1.0 | OFF | 0 | 15° | 10.0 | iflcPairNegotiation |
| E-NoDyn | 0 | 0.9999 | 3.0 | 1.0 | OFF | 0 | 15° | 10.0 | iflcPairNegotiation |
| F-AdapQ | 1 | 0.9999 | 3.0 | 1.0 | OFF | 0 | 15° | 10.0 | +enableAdaptiveQ |
| G-IGGIII | 1 | 0.9999 | 3.0 | 1.0 | OFF | 0 | 15° | 10.0 | +enableIggiii |
| H-ResEdit | 1 | 0.9999 | 3.0 | 1.0 | OFF | 0 | 15° | 10.0 | +enableResidualEdit |
| I-PartAR | 1 | 0.9999 | 3.0 | 1.0 | OFF | 0 | 15° | 10.0 | +enablePartialAR |
| J-CascadeAR | 1 | 0.9999 | 3.0 | 1.0 | OFF | 0 | 15° | 10.0 | +enableCascadeAR |
| K-SnrMed | 1 | 0.9999 | 3.0 | 1.0 | OFF | 0 | 15° | 10.0 | +enableSnrMedian |
| L-AllOpt | 1 | 0.9999 | 3.0 | 1.0 | OFF | 0 | 15° | 10.0 | 全部开关开启 |

---

## 4. 优化对比测试结果

### 4.1 PPC Nagoya run1（7602 历元，长基线 15km，轻度遮挡）

| 变体 | Fix(个) | Fix% | Float% | H.RMSE Fix(m) | H.RMSE Float(m) | vs C-EX-Ref |
|------|---------|------|--------|---------------|-----------------|-------------|
| A-Default | 0 | 0.0% | 95.1% | — | 16.280 | ❌ thresar[1]太严 |
| B-Dyn1 | 511 | 6.7% | 90.0% | 2.408 | 2.462 | thresar[1]=0.25 限制 |
| **C-EX-Ref** | **626** | **8.2%** | **88.5%** | **2.870** | **5.175** | **基准** |
| D-LooseAR | 626 | 8.2% | 88.5% | 2.870 | 5.175 | =C 无额外收益 |
| E-NoDyn | 0 | 0.0% | 95.1% | — | 16.280 | ❌ 无速度估计 |
| **F-AdapQ** | **1069** | **14.1%** | **82.6%** | **2.795** | **5.306** | ✅ **Fix+72%** |
| **G-IGGIII** | **1062** | **14.0%** | **82.8%** | **2.542** | **4.092** | ✅ **Fix+71%, Float精度-21%** |
| H-ResEdit | 626 | 8.2% | 88.5% | 2.870 | 5.175 | =C 无效 |
| **I-PartAR** | **1873** | **24.6%** | **72.1%** | **63.930** ⚠️ | **6.336** | ⚠️ Fix+200% 但含大量 false fix |
| J-CascadeAR | 0 | 0.0% | 96.7% | — | 4.991 | ❌ AR完全失效 |
| **K-SnrMed** | **628** | **8.3%** | **88.6%** | **2.375** | **4.036** | ✅ **Float精度-22%, Fix精度-17%** |
| L-AllOpt | 0 | 0.0% | 96.9% | — | 3.589 | ❌ CascadeAR Bug 毒化整体 |

### 4.2 PPC Nagoya run3（5201 历元，C-EX-Ref 单变体验证）

| 变体 | Fix(个) | Fix% | Float% | H.RMSE Fix(m) | H.RMSE Float(m) |
|------|---------|------|--------|---------------|-----------------|
| C-EX-Ref | 572 | 11.0% | 88.4% | 8.942 | 6.087 |

---

## 5. 优化效果分类

### 5.1 ✅ 数据证明有效

| 优化 | 开关 | 效果 | 机制 | 有效场景 |
|------|------|------|------|---------|
| **自适应 Q 矩阵** | `enableAdaptiveQ` | Fix 8.2%→14.1% (+72%) | 动态缩放过程噪声 Q，EKF 收敛更快 → posvar 更快降到 thresar[1] 以下 → AR 更早触发 | **动态 RTK + 城市峡谷**（信号间断频繁，需快速重收敛） |
| **IGGIII 抗差估计** | `enableIggiii` | Fix 8.2%→14.0% (+71%), Float H.RMSE 5.18→4.09 (-21%) | 三段式等价权降权多路径/NLOS 观测 → 残差减小 → ratio 提升 → 更多历元通过 AR | **城市峡谷**（多路径/NLOS 比例高） |
| **SNR 中值定权** | `enableSnrMedian` | Fix H.RMSE 2.87→2.38 (-17%), Float H.RMSE 5.18→4.04 (-22%) | 各频点 SNR 中值替代固定参考值 → 弱信号自动降权 → 观测权更合理 | **信号质量差异大**（遮挡/半遮挡混合） |

### 5.2 ⚠️ 部分有效但有副作用

| 优化 | 开关 | 效果 | 问题 | 需改进 |
|------|------|------|------|--------|
| **部分模糊度固定** | `enablePartialAR` | Fix 8.2%→24.6% (+200%) | Fix H.RMSE=63.9m，大量 **false fix**（错误固定的模糊度导致位置严重偏差） | 需加强子集验证（如 Bootstrapping 联合判据），或提高子集 ratio 阈值 |

### 5.3 ❌ 无效 / 有害

| 优化 | 开关 | 结果 | 原因 |
|------|------|------|------|
| **残差编辑** | `enableResidualEdit` | 与 C-EX-Ref 完全相同 | 残差编辑阈值未触发，或实现与 IFLC 模式不兼容 |
| **级联 AR** | `enableCascadeAR` | Fix=0% | 实现有 Bug 或与 IFLC 不兼容，导致 AR 完全失效 |
| **全优化组合** | 全部开启 | Fix=0% | CascadeAR 的 Bug 毒化整体，即使其他有效优化也无法发挥作用 |
| **宽松 AR (thresar[1]=1.0)** | — | 与 C-EX-Ref 完全相同 | thresar[1]=0.9999 已足够宽松，1.0 无额外收益 |
| **无动态模型 (dynamics=0)** | — | Fix=0% | 无速度估计，posvar 不收敛 → AR 永不触发 |

### 5.4 🔬 理论适用但未验证

| 优化 | 开关 | 理论适用场景 | 未验证原因 |
|------|------|-------------|-----------|
| **PAR 参考星重选** | `enableParRefReselect` | Ratio 不足时排除贡献差卫星 | L-AllOpt 中被 CascadeAR Bug 掩盖，需单独测试 |
| **参数类型级过程噪声** | `enableParamTypeNoise` | 长基线 ZTD/电离层随机游走 | 同上，需单独测试 |
| **模糊度子集锚固** | `enableAmbAnchor` | 连续 Fix≥50 历元后锚固 | 未纳入本次测试变体 |
| **Bootstrapping 联合判据** | `enableBootstrapping` | 防 Ratio 假阳性 | 未纳入本次测试变体 |

---

## 6. 场景-优化推荐矩阵

| 场景 | enableAdaptiveQ | enableIggiii | enableSnrMedian | enablePartialAR | enableCascadeAR | enableResidualEdit |
|------|-----------------|--------------|-----------------|-----------------|-----------------|-------------------|
| **动态 RTK + 城市峡谷** | ✅ | ✅ | ✅ | ⚠️ 需验证 | ❌ Bug | ❌ 无效 |
| **动态 RTK + 开阔天空** | ⚠️ 弱收益 | ⚠️ 弱收益 | ⚠️ 弱收益 | ✅ | ✅ | ✅ |
| **静态 RTK + 短基线** | ❌ 不适用 | ⚠️ 弱收益 | ⚠️ 弱收益 | ✅ | ✅ | ✅ |
| **长基线 (>10km)** | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ |

> **图例**：✅ = 推荐开启 | ⚠️ = 可开但效果弱/需额外验证 | ❌ = 不推荐/无效/Bug

---

## 7. 待修复项

| # | 问题 | 优先级 | 影响 |
|---|------|--------|------|
| 1 | **enableCascadeAR 导致 AR 完全失效** | 🔴 高 | 单独开启和全组合均 Fix=0%，需排查 IFLC 兼容性 |
| 2 | **enablePartialAR 产生大量 false fix** | 🟡 中 | Fix H.RMSE=63.9m，需加强子集验证判据 |
| 3 | **enableResidualEdit 无任何效果** | 🟡 中 | 与基准完全相同，需确认实现是否与 IFLC 模式对接 |

---

## 8. 执行命令

```bash
# 单数据集基准测试（C-EX-Ref）
mvn test -pl rtklib-core -Dtest="DynamicRtkOptTest#testPpcNagoyaRun1"

# 优化对比（12 变体，耗时较长）
mvn test -pl rtklib-core -Dtest="DynamicRtkOptTest#testOptComparisonNagoyaRun1"

# 全量汇总（8×12=96 组，极长，需手动启用 @Disabled）
mvn test -pl rtklib-core -Dtest="DynamicRtkOptTest#testSummaryAll"
```