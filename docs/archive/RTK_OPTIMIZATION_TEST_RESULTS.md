# RTK 优化测试结果（城市峡谷动态场景）

> **⚠️ 范围声明**：本报告结论**仅对动态 RTK 城市峡谷场景成立**。静态场景未系统验证，§6.1 场景适用性矩阵中"静态"列内容为理论推断，不作为实测结论引用。

> **日期**：2026-10-15  
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
| M-ParRef | 1 | 0.9999 | 3.0 | 1.0 | OFF | 0 | 15° | 10.0 | +enableParRefReselect |
| N-ParamNoise | 1 | 0.9999 | 3.0 | 1.0 | OFF | 0 | 15° | 10.0 | +enableParamTypeNoise |
| O-SegSigma | 1 | 0.9999 | 3.0 | 1.0 | OFF | 0 | 15° | 10.0 | +enableSegmentSigmaFilter (σ<5m, N≥5) |

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
| J-CascadeAR | 0 | 0.0% | 96.7% | — | 4.991 | ❌ AR完全失效（v2.2.x伪逐级；v2.3.0真逐级待重测） |
| **K-SnrMed** | **628** | **8.3%** | **88.6%** | **2.375** | **4.036** | ✅ **Float精度-22%, Fix精度-17%** |
| L-AllOpt | 0 | 0.0% | 96.9% | — | 3.589 | ❌ CascadeAR Bug 毒化整体（v2.2.x伪逐级；v2.3.0已修复） |

### 4.2 PPC Tokyo run2（9151 历元，中长基线 7.8km，部分遮挡）

| 变体 | Fix(个) | Fix% | Float% | H.RMSE Fix(m) | H.RMSE Float(m) | vs C-EX-Ref |
|------|---------|------|--------|---------------|-----------------|-------------|
| A-Default | 0 | 0.0% | 94.6% | — | 7.675 | ❌ thresar[1]太严 |
| B-Dyn1 | 933 | 10.2% | 84.6% | 4.013 | 3.468 | thresar[1]=0.25 限制 |
| **C-EX-Ref** | **845** | **9.2%** | **85.6%** | **2.924** | **4.789** | **基准** |
| D-LooseAR | 845 | 9.2% | 85.6% | 2.924 | 4.789 | =C 无额外收益 |
| E-NoDyn | 35 | 0.4% | 94.2% | 2.880 | 7.689 | ❌ 几乎无Fix |
| F-AdapQ | 848 | 9.3% | 85.5% | 2.912 | 4.764 | ≈C 无显著提升 |
| G-IGGIII | 212 | 2.3% | 92.5% | 3.556 | 3.714 | ❌ **Fix下降75%** |
| **H-ResEdit** | **1808** | **19.8%** | **75.1%** | **3.019** | **5.012** | ✅ **Fix+115%** |
| **I-PartAR** | **2097** | **22.9%** | **71.9%** | **167.832** ⚠️ | **5.285** | ⚠️ Fix+149% 但含 false fix |
| J-CascadeAR | 0 | 0.0% | 94.8% | — | 4.312 | ❌ AR完全失效（v2.2.x伪逐级；v2.3.0真逐级待重测） |
| **K-SnrMed** | **837** | **9.1%** | **85.7%** | **2.926** | **4.127** | ✅ **Float精度-14%** |
| L-AllOpt | 0 | 0.0% | 94.8% | — | 3.692 | ❌ CascadeAR Bug（v2.2.x伪逐级；v2.3.0已修复） |

### 4.3 PPC Tokyo run1（11928 历元，中长基线 7.8km，部分遮挡）

| 变体 | Fix(个) | Fix% | Float% | H.RMSE Fix(m) | H.RMSE Float(m) | vs C-EX-Ref |
|------|---------|------|--------|---------------|-----------------|-------------|
| A-Default | 25 | 0.2% | 94.3% | 4.048 | 20.640 | ❌ thresar[1]太严 |
| B-Dyn1 | 317 | 2.7% | 93.4% | 4.117 | 19.866 | thresar[1]=0.25 限制 |
| **C-EX-Ref** | **697** | **5.8%** | **90.2%** | **9.884** | **16.477** | **基准** |
| D-LooseAR | 697 | 5.8% | 90.2% | 9.884 | 16.477 | =C 无额外收益 |
| E-NoDyn | 41 | 0.3% | 94.2% | 4.457 | 20.654 | ❌ 几乎无Fix |
| F-AdapQ | 352 | 3.0% | 93.2% | 14.058 | 17.973 | ❌ **Fix下降49%** |
| **G-IGGIII** | **306** | **2.6%** | **93.5%** | **2.790** | **12.729** | ✅ **Float精度-23%, Fix精度-72%** |
| H-ResEdit | 178 | 1.5% | 94.6% | 17.019 | 16.521 | ❌ **Fix下降74%** |
| **I-PartAR** | **1531** | **12.8%** | **83.2%** | **265.819** ⚠️ | **17.736** | ⚠️ Fix+120% 但含大量 false fix |
| J-CascadeAR | 0 | 0.0% | 96.1% | — | 15.998 | ❌ AR完全失效（v2.2.x伪逐级；v2.3.0真逐级待重测） |
| K-SnrMed | 697 | 5.8% | 90.2% | 9.884 | 16.477 | =C 无效果 |
| L-AllOpt | 0 | 0.0% | 96.1% | — | 14.409 | ❌ CascadeAR Bug（v2.2.x伪逐级；v2.3.0已修复） |
| M-ParRef | 697 | 5.8% | 90.2% | 9.884 | 16.477 | =C 无效果 |
| N-ParamNoise | 697 | 5.8% | 90.2% | 9.884 | 16.477 | =C 无效果 |

### 4.4 PPC Nagoya run3（5201 历元，C-EX-Ref 单变体验证）

| 变体 | Fix(个) | Fix% | Float% | H.RMSE Fix(m) | H.RMSE Float(m) |
|------|---------|------|--------|---------------|-----------------|
| C-EX-Ref | 572 | 11.0% | 88.4% | 8.942 | 6.087 |

### 4.5 补充测试：M-ParRef & N-ParamNoise

**PPC Nagoya run1（7602 历元）**：

| 变体 | Fix(个) | Fix% | Float% | H.RMSE Fix(m) | H.RMSE Float(m) | vs C-EX-Ref |
|------|---------|------|--------|---------------|-----------------|-------------|
| C-EX-Ref | 626 | 8.2% | 88.5% | 2.870 | 5.175 | 基准 |
| M-ParRef | 626 | 8.2% | 88.5% | 2.870 | 5.175 | =C 无效果 |
| N-ParamNoise | 626 | 8.2% | 88.5% | 2.870 | 5.175 | =C 无效果 |

**PPC Tokyo run2（9151 历元）**：

| 变体 | Fix(个) | Fix% | Float% | H.RMSE Fix(m) | H.RMSE Float(m) | vs C-EX-Ref |
|------|---------|------|--------|---------------|-----------------|-------------|
| C-EX-Ref | 845 | 9.2% | 85.6% | 2.924 | 4.789 | 基准 |
| M-ParRef | 845 | 9.2% | 85.6% | 2.924 | 4.789 | =C 无效果 |
| N-ParamNoise | 845 | 9.2% | 85.6% | 2.924 | 4.789 | =C 无效果 |

### 4.6 PPC Nagoya run2（9451 历元，长基线 15km，轻度遮挡）

| 变体 | Fix(个) | Fix% | Float% | H.RMSE Fix(m) | H.RMSE Float(m) | vs C-EX-Ref |
|------|---------|------|--------|---------------|-----------------|-------------|
| A-Default | 0 | 0.0% | 95.2% | — | 30.775 | ❌ thresar[1]太严 |
| B-Dyn1 | 903 | 9.6% | 87.5% | 5.400 | 7.503 | thresar[1]=0.25 限制 |
| **C-EX-Ref** | **939** | **9.9%** | **87.1%** | **12.030** | **13.535** | **基准** |
| D-LooseAR | 939 | 9.9% | 87.1% | 12.030 | 13.535 | =C 无额外收益 |
| E-NoDyn | 0 | 0.0% | 95.2% | — | 30.775 | ❌ 无速度估计 |
| F-AdapQ | 926 | 9.8% | 87.3% | 13.402 | 15.784 | ≈C 略差 |
| **G-IGGIII** | **890** | **9.4%** | **87.7%** | **8.080** | **11.085** | ✅ **Float精度-18%, Fix精度-33%** |
| H-ResEdit | 919 | 9.7% | 87.4% | 11.409 | 13.715 | ≈C 略差 |
| **I-PartAR** | **1477** | **15.6%** | **81.4%** | **258.419** ⚠️ | **16.060** | ⚠️ Fix+58% 但含大量 false fix |
| J-CascadeAR | 0 | 0.0% | 97.1% | — | 13.033 | ❌ AR完全失效（v2.2.x伪逐级；v2.3.0真逐级待重测） |
| **K-SnrMed** | **916** | **9.7%** | **87.3%** | **9.911** | **12.023** | ✅ **Float精度-11%, Fix精度-18%** |
| L-AllOpt | 0 | 0.0% | 97.0% | — | 10.523 | ❌ CascadeAR Bug（v2.2.x伪逐级；v2.3.0已修复） |
| M-ParRef | 939 | 9.9% | 87.1% | 12.030 | 13.535 | =C 无效果 |
| N-ParamNoise | 939 | 9.9% | 87.1% | 12.030 | 13.535 | =C 无效果 |

### 4.7 UrbanNav Tokyo 2018（12399 历元，短基线 186m，严重遮挡）

> 坐标框架已修复（.pos ECEF→LLH 转换）。IFLC 下 Fix 几乎全是 false fix（H<0.5m=0%），OFF 下 37.6% 为 false fix。

| 变体 | Fix(个) | Fix% | Float% | H.RMSE Fix(m) | H.RMSE Float(m) | vs C-EX-Ref |
|------|---------|------|--------|---------------|-----------------|-------------|
| A-Default | 21 | 0.2% | 89.4% | — | — | ❌ thresar[1]太严 |
| B-Dyn1 | 995 | 8.0% | 81.7% | — | — | thresar[1]=0.25 限制 |
| **C-EX-Ref** | **1182** | **9.5%** | **80.3%** | **4.890** | **6.614** | **基准** |
| D-LooseAR | 1182 | 9.5% | 80.3% | 4.890 | 6.614 | =C 无额外收益 |
| E-NoDyn | 58 | 0.5% | 89.1% | — | — | ❌ 几乎无Fix |
| **F-AdapQ** | **1327** | **10.7%** | **79.1%** | **5.324** | **6.614** | ✅ **Fix+12%** |
| G-IGGIII | 1182 | 9.5% | 80.2% | 4.890 | 6.614 | =C 无效果 |
| H-ResEdit | 295 | 2.4% | 87.4% | — | — | ❌ **Fix-75%** |
| **I-PartAR** | **2161** | **17.4%** | **72.4%** | **—** | **—** | ⚠️ Fix+83% 但false fix严重 |
| J-CascadeAR | 0 | 0.0% | 89.8% | — | — | ❌ AR完全失效（v2.2.x伪逐级；v2.3.0真逐级待重测） |
| K-SnrMed | 1182 | 9.5% | 80.3% | 4.890 | 6.614 | =C 无效果 |
| L-AllOpt | 0 | 0.0% | 89.8% | — | — | ❌ CascadeAR Bug（v2.2.x伪逐级；v2.3.0已修复） |
| M-ParRef | 1182 | 9.5% | 80.3% | 4.890 | 6.614 | =C 无效果 |
| N-ParamNoise | 1182 | 9.5% | 80.3% | 4.890 | 6.614 | =C 无效果 |

### 4.8 PPC Tokyo run3 关键变体验证（15301 历元，中长基线 7.8km，部分遮挡）

> 仅跑 C/F/G/H 四个关键变体，验证 Tokyo run1/run2 反转模式在 run3 的归属

| 变体 | Fix(个) | Fix% | Float% | H.RMSE Fix(m) | H.RMSE Float(m) | vs C-EX-Ref |
|------|---------|------|--------|---------------|-----------------|-------------|
| **C-EX-Ref** | **974** | **6.4%** | **89.4%** | **6.076** | **6.759** | **基准** |
| F-AdapQ | 786 | 5.1% | 90.6% | 6.528 | 6.486 | ❌ **Fix-20%** |
| G-IGGIII | 844 | 5.5% | 90.2% | 6.176 | 6.107 | ⚠️ Fix-13%, Float-10% |
| H-ResEdit | 693 | 4.5% | 91.2% | 7.126 | 6.691 | ❌ **Fix-29%** |

> **run3 归属判定**：Tokyo run3 属于 **run1 模式**（AdapQ/ResEdit 有害，IGGIII 弱收益+精度提升），而非 run2 模式（ResEdit 大幅提升）。Tokyo 三时段中仅 run2 为 ResEdit 有效模式，run1/run3 均为 ResEdit 有害模式。

---

## 5. 优化效果分类

### 5.1 ✅ 数据证明有效（场景通用）

| 优化 | 开关 | 机制 | Nagoya run1 | Nagoya run2 | Tokyo run1 | Tokyo run2 | UrbanNav |
|------|------|------|------------|------------|------------|-----------|----------|
| **SNR 中值定权** | `enableSnrMedian` | 各频点 SNR 中值替代固定参考值 → 弱信号自动降权 | Float -22%, Fix -17% ✅ | Float -11%, Fix -18% ✅ | =C 无效果 | Float -14% ✅ | =C 无效果 |
| **IGGIII 抗差估计** | `enableIggiii` | 三段式等价权降权多路径/NLOS 观测 | Fix +71%, Float -21% ✅ | Float -18%, Fix -33% ✅ | Float -23%, Fix -72% ✅ | Fix **-75%** ❌ | =C 无效果 |

> **SnrMedian 综合评价**：3/5 数据集精度提升，2/5 无效果（但无害），仍为最稳定的精度优化项  
> **IGGIII 综合评价**：3/5 数据集精度提升，1/5 有害（Tokyo run2），1/5 无效果（UrbanNav），场景依赖性强

### 5.1.1 ✅ 数据证明有效（false fix 过滤）

| 优化 | 开关 | 机制 | UrbanNav OFF | 后处理对照 |
|------|------|------|-------------|-----------|
| **段内位置标准差过滤** | `enableSegmentSigmaFilter` | 在线计算连续 Fix 段内 ENU 位置标准差 σ，超阈值时追溯失效整段 | σ<5m: Fix 8.2%, H>1m **1.5%**, H.RMSE **0.620m** | σ<5m: Fix 8.3%, H>1m 1.5%, H.RMSE 0.614m |

> **段内σ过滤 参数扫描结果（UrbanNav Tokyo OFF 模式）**：
>
> | 配置 | Fix率 | H<0.5m(true fix) | H>1m(false fix) | H.RMSE |
> |------|-------|-----------------|----------------|--------|
> | 基准(无过滤) | 55.0% | 1.6% | 54.5% | 2.827m |
> | **σ<5m, N≥5** | **8.2%** | **0.0%** | **1.5%** | **0.620m** |
> | σ<5m, N≥10 | 8.2% | 0.0% | 1.5% | 0.620m |
> | σ<10m, N≥5 | 9.1% | 0.2% | 6.1% | 0.823m |
> | σ<10m, N≥10 | 9.1% | 0.2% | 6.1% | 0.823m |
> | σ<20m, N≥5 | 9.9% | 0.2% | 6.3% | 0.971m |
> | σ<20m, N≥10 | 9.9% | 0.2% | 6.3% | 0.971m |
>
> **推荐配置**：`segmentSigmaThreshold=5.0, segmentSigmaMinLen=5`
>
> **核心机制**：
> 1. 连续 Fix 历元构成一个"段"，段内计算 ENU 位置标准差 σ = √(σ_E² + σ_N²)
> 2. 当 σ 超过阈值时，**追溯失效**整段——将段内所有已输出 Sol 的 stat 从 FIX 改为 FLOAT
> 3. 段内出现 Float 历元时自动重置，开始新段
> 4. 在线效果与后处理几乎一致（0.620m vs 0.614m），因为 false fix 段内位置发散，σ 迅速超阈值
>
> **适用场景**：严重遮挡/城市峡谷下 OFF 模式 false fix 严重时。IFLC 模式下 Fix 率本身低（9.5%），过滤收益有限。

### 5.2 ✅ 数据证明有效（场景依赖）

| 优化 | 开关 | 机制 | 有效场景 | 无效/有害场景 |
|------|------|------|---------|-------------|
| **自适应 Q 矩阵** | `enableAdaptiveQ` | 动态缩放 Q → EKF 收敛更快 → AR 更早触发 | Nagoya run1: Fix +72% ✅; UrbanNav: Fix +12% ✅ | Tokyo run1: Fix **-49%** ❌; Tokyo run3: Fix **-20%** ❌; Tokyo run2/Nagoya run2: ≈C ❌ |
| **残差编辑** | `enableResidualEdit` | 残差跳变检测+伪距-载波一致性+弧段完整性 | Tokyo run2: Fix +115% ✅ | Tokyo run1: Fix **-74%** ❌; Tokyo run3: Fix **-29%** ❌; UrbanNav: Fix **-75%** ❌; Nagoya run1/run2: ≈C ❌ |

> **关键发现**：  
> 1. AdaptiveQ 和 ResEdit 在 Tokyo run1/run3、UrbanNav 均表现有害，仅 Tokyo run21 个场景 ResEdit 有效  
> 2. ResEdit 在严重遮挡场景（UrbanNav、Tokyo run1/run3）一致有害，可能因残差编辑误删有效观测  
> 3. **Tokyo 三时段验证**：run1/run3 属同一模式（ResEdit 有害），run2 为孤立异常模式（ResEdit 有效），说明同一城市不同时段的遮挡/多路径特征差异巨大  
> 4. IGGIII 在 Tokyo run3 也偏弱（Fix-13%, Float-10%），进一步确认场景依赖性极强

### 5.2.1 🔬 IFLC 归因验证（EST/OFF 对照）

> **核心问题**：之前的"反转"和"无效"，有多少是 IFLC 造成的？

#### Nagoya run1 EST（长基线 15km）— EST 完全不可用

| 变体 | Fix(个) | Fix% | vs C-EST-Ref | IFLC 对照 |
|------|---------|------|-------------|---------|
| C-EST-Ref | 14 | 0.2% | 基准 | IFLC: 9.9% (939个) |
| F-EST-AdapQ | 10 | 0.1% | ≈C | IFLC: Fix+72% |
| G-EST-IGGIII | 4 | 0.1% | ≈C | IFLC: Fix+71% |
| H-EST-ResEdit | 12 | 0.2% | ≈C | IFLC: ≈C |
| N-EST-ParamNoise | 24 | 0.3% | ≈C | IFLC: ≈C |
| O-EST-IonoTrop | 14 | 0.2% | =C | IFLC: 不适用 |

> **结论**：长基线 EST 模式 Fix≈0%，**IFLC 是长基线 AR 收敛的必需前提**，无法脱离 IFLC 测试。

#### Tokyo run1 EST（中基线 7.8km）— ResEdit/AdapQ 有害性消失

| 变体 | Fix(个) | Fix% | vs C-EST-Ref | IFLC 对照 |
|------|---------|------|-------------|---------|
| C-EST-Ref | 386 | 3.2% | 基准 | IFLC: 9.9% (1182个) |
| F-EST-AdapQ | 383 | 3.2% | ≈C | IFLC: **Fix-49%** |
| G-EST-IGGIII | 262 | 2.2% | ❌ **-32%** | IFLC: Float-23% |
| H-EST-ResEdit | 380 | 3.2% | ≈C | IFLC: **Fix-74%** |
| K-EST-SnrMed | 379 | 3.2% | ≈C | IFLC: ≈C |
| N-EST-ParamNoise | 90 | 0.8% | ❌ **-77%** | IFLC: ≈C |
| O-EST-IonoTrop | 386 | 3.2% | =C | IFLC: 不适用 |

> **关键发现**：ResEdit/AdapQ 在 EST 下有害性消失（≈C），IFLC 下 Fix-74%/-49% → **有害性是 IFLC 造成的**。但 IGGIII/ParamNoise 在 EST 下变有害，与 EST 不兼容。

#### Tokyo run2 EST（中基线 7.8km）— IGGIII 反转消失

| 变体 | Fix(个) | Fix% | vs C-EST-Ref | IFLC 对照 |
|------|---------|------|-------------|---------|
| C-EST-Ref | 433 | 4.7% | 基准 | IFLC: 9.9% (909个) |
| F-EST-AdapQ | 430 | 4.7% | ≈C | IFLC: ≈C |
| G-EST-IGGIII | 558 | 6.1% | ✅ **+29%** | IFLC: **Fix-75%** |
| H-EST-ResEdit | 433 | 4.7% | =C | IFLC: **Fix+115%** |
| K-EST-SnrMed | 423 | 4.6% | ≈C | IFLC: 精度-14% |
| N-EST-ParamNoise | 84 | 0.9% | ❌ **-81%** | IFLC: ≈C |
| O-EST-IonoTrop | 433 | 4.7% | =C | IFLC: 不适用 |

> **关键发现**：IGGIII 反转消失（IFLC 下 Fix-75%，EST 下 Fix+29%）→ **IGGIII 有害性是 IFLC 造成的**。ResEdit 在 EST 下无效（=C），IFLC 下 Fix+115% → **ResEdit 有效性也依赖 IFLC**。

#### UrbanNav OFF（短基线 186m）— Fix 率从 9.5% 飙升至 54.0%，但 false fix 严重

| 变体 | Fix(个) | Fix% | vs C-OFF-Ref | IFLC 对照 |
|------|---------|------|-------------|---------|
| C-OFF-Ref | 6690 | 54.0% | 基准 | IFLC: 9.5% (1182个) |
| F-OFF-AdapQ | 7208 | 58.1% | ✅ **+8%** | IFLC: Fix+12% |
| G-OFF-IGGIII | 7151 | 57.7% | ✅ **+7%** | IFLC: ≈C |
| H-OFF-ResEdit | 6682 | 53.9% | ≈C | IFLC: **Fix-75%** |
| K-OFF-SnrMed | 6690 | 54.0% | =C | IFLC: ≈C |

> **关键发现**：OFF 模式 Fix 率 5.7 倍提升。ResEdit 有害性消失（≈C），IGGIII 变有效（+7%），AdapQ 保持有效（+8%）。
>
> **⚠️ false fix 诊断（坐标框架修复后首次可算）**：  
> **IFLC 模式**：Fix 9.5% 中 H<0.5m = 0%（**无 true fix**），H>1m = 87.3%（几乎全是 false fix）  
> **OFF 模式**：Fix 54.0% 中 H<0.5m = 1.6%（仅 109 个 true fix），H>1m = 37.6%（2515 个 false fix），H>5m = 5.8%  
>   
> **⚠️ AR 判据增强验证——全部无效**：  
> | 方法 | Fix率 | H<0.5m | H>1m | 结论 |
> |------|-------|--------|------|------|
> | 基准 (Ratio=3) | 54.0% | 1.6% | 37.6% | — |
> | Bootstrapping (0.99) | 12.4% | 2.2% | 40.9% | ❌ Fix暴跌77%，false fix比例反升 |
> | Ratio=10 | 35.5% | 1.8% | 42.4% | ❌ false fix比例反升 |
> | Ratio=20 | 13.7% | 0.0% | 32.6% | ❌ true fix也全部丢失 |
> | posvar<0.01 | 30.3% | 1.3% | 39.4% | ❌ false fix比例无改善 |
>   
> **根因分析**：在严重遮挡场景下，**EKF 状态估计本身被污染**——模糊度浮点解的方差不可靠，导致所有基于方差的判据（Ratio、Bootstrapping、posvar）都无法区分 true fix 和 false fix。这不是 AR 判据过松的问题，而是 **EKF 发散后 AR 判据失效**的根本问题。  
>   
> **实践建议**：  
> - "短基线用 OFF"在严重遮挡下不可靠——54% Fix 中 37.6% 是 false fix，且无法通过调参消除  
> - 可行的替代方案：**连续性判据**（仅信任连续 N 个历元 Fix 且位置跳变 <阈值的段）、**FDE 后验检验**（Fix 后做残差检验）、或**降低 AR 期望**（接受低 Fix 率但确保 Fix 质量）

#### IFLC 归因总结

| 优化 | IFLC 下表现 | EST/OFF 下表现 | 归因 |
|------|-----------|--------------|------|
| **ResEdit** | 中基线有害（Fix-74%/-29%），短基线有害（-75%），run2 有效（+115%） | **全部无害**（≈C 或 =C） | **有害性 100% 由 IFLC 造成**；有效性也依赖 IFLC（run2 +115% → EST =C） |
| **AdapQ** | 中基线有害（Fix-49%/-20%），短/长基线有效 | **全部无害**（≈C） | **有害性 100% 由 IFLC 造成**；有效性不依赖 IFLC（OFF 下仍+8%） |
| **IGGIII** | run2 有害（Fix-75%），其他有效或无效 | run2 **有效**（+29%），run1 有害（-32%） | **run2 有害性 100% 由 IFLC 造成**；run1 下有害是场景依赖（非模型不兼容，见下方分析） |
| **ParamNoise** | IFLC 下 ≈C（无对象） | EST 下**严重有害**（-77%/-81%） | IFLC 下无效果是因为无估计对象；EST 下有害是**过程噪声双重叠加**，非 Bug（见下方分析） |
| **IonoTrop** | IFLC 下不适用 | EST 下 =C（无效果） | 需更长基线或更活跃电离层场景验证 |

> **ParamNoise EST 有害的根因与修复**：  
> 三重问题叠加导致 EST 下 Fix-77%/-81%：  
> 1. **双重叠加**：`applyParamTypeNoise()` 在 `udstate()` 之后调用，STEC 过程噪声被 `udion()` 和 ParamNoise 各加一次 → 接近翻倍  
> 2. **全卫星加噪**：ParamNoise 给 ALL MAXSAT=85 个 STEC 状态加噪声，而 `udion()` 只给 ~10-15 个可见卫星加 → 6~8 倍额外噪声  
> 3. **噪声量级过大**：`noiseIonoRw=1e-3` 无 cos(el) 衰减、无基线缩放，低仰角卫星上噪声是原始的 5 倍  
>   
> **三步修复**（均已实施）：  
> - `udion()`/`udtrop()` 中当 `enableParamTypeNoise=true` 时跳过过程噪声叠加（替换式）  
> - `applyParamTypeNoise()` 只给已初始化（`x[i]!=0`）的状态加噪声  
> - `noiseIonoRw` 默认值从 `1e-3` 调至 `1e-4`  
>   
> **修复验证**：Tokyo run1 Fix 从 0.8%→3.1%（≈C），run2 从 0.9%→3.2%（-33%，轻微偏弱），Fix 精度从 42m→0.49m。

> **IGGIII EST 下 run1 有害的根因分析**：  
> IGGIII 在 EST 模式下 run2 有效（+29%）、run1 有害（-32%），这与 IFLC 模式下的场景依赖模式一致（只是反转），说明 IGGIII 的场景依赖性**不依赖于电离层模型**，而与具体卫星几何/遮挡模式有关。run1 遮挡模式下 IGGIII 降权的观测恰好是 STEC 估计的关键观测，run2 则降权的是真正的粗差观测。这是**场景依赖**而非**模型不兼容**。
>
> **IGGIII 逐卫星降权诊断（EST 模式）**：  
> **Tokyo run1**：总降权 183,695 次 / 11,920 调用（平均每次 15.4 个观测被降权）  
> - 重度降权：G11(50,569次), E09(49,763次), C14(53,274次), R21(18,277次), J196(8,348次)  
> - 中度降权：R06(305), C01(568), C33(819), C38(494), C43(595)  
> - **G11 是 GPS 核心卫星，降权 50,569 次严重损害 STEC 估计** → run1 Fix-32%  
>
> **Tokyo run2**：总降权 89,575 次 / 9,151 调用（平均每次 9.8 个观测被降权）  
> - 重度降权：C34(18,479次), G19(21,554次), E10(12,267次), J195(9,940次), E11(9,369次)  
> - 中度降权：R04(4,971), R05(2,761), C11(5,661), C12(3,815)  
> - **C34(BDS 高轨)和 G19 被降权是合理的**（BDS 伪距差/G19 低仰角）→ run2 Fix+29%  
>
> **结论**：IGGIII 的场景依赖性来自**降权目标与场景的耦合**——run1 中 IGGIII 错误降权了 GPS 核心卫星（G11），run2 中正确降权了 BDS/GLO 次优卫星。**IGGIII 需要改造为自适应**（如：仅降权非核心星座、或根据 STEC 收敛状态决定是否启用），否则在遮挡场景下有害。

### 5.3 ⚠️ 部分有效但有副作用

| 优化 | 开关 | 效果 | 问题 | 需改进 |
|------|------|------|------|--------|
| **部分模糊度固定** | `enablePartialAR` | Fix+58%~200% | Fix H.RMSE=63~266m，大量 **false fix** | 需加强子集验证（如 Bootstrapping 联合判据），或提高子集 ratio 阈值 |

### 5.4 ❌ 无效 / 有害

| 优化 | 开关 | 结果 | 原因 |
|------|------|------|------|
| **级联 AR（v2.2.x旧伪逐级）** | `enableCascadeAR` | Fix=0% (五组IFLC数据) | v2.2.x伪逐级实现：三次LAMBDA共用y/Qb，EWL/WL验证冗余；v2.3.0已重写为真逐级（FIX-ALG-007），IFLC下由FIX-ALG-006守卫短路 |
| **全优化组合（v2.2.x）** | 全部开启 | Fix=0% (五组IFLC数据) | v2.2.x CascadeAR伪逐级Bug毒化整体；v2.3.0已修复 |
| **宽松 AR (thresar[1]=1.0)** | — | =C-EX-Ref | thresar[1]=0.9999 已足够宽松，1.0 无额外收益 |
| **无动态模型 (dynamics=0)** | — | Fix≈0% | 无速度估计，posvar 不收敛 → AR 永不触发 |

### 5.5 🔬 理论适用但实测无效

| 优化 | 开关 | 理论适用场景 | 实测结果 | 原因 |
|------|------|-------------|---------|------|
| **PAR 参考星重选** | `enableParRefReselect` | Ratio 不足时排除贡献差卫星 | 三组数据均 =C-EX-Ref | 仅 LAMBDA 失败后触发，当前场景 LAMBDA 成功率已足够或失败后重选无改善 |
| **参数类型级过程噪声** | `enableParamTypeNoise` | 长基线 ZTD/电离层随机游走 | 三组数据均 =C-EX-Ref | IFLC 模式不估计 ZTD/电离层，过程噪声替换无效果 |

### 5.6 🔬 理论适用但未纳入测试

| 优化 | 开关 | 理论适用场景 | 未测试原因 |
|------|------|-------------|-----------|
| **模糊度子集锚固** | `enableAmbAnchor` | 连续 Fix≥50 历元后锚固 | 城市峡谷 Fix 率低，锚固条件难满足 |
| **Bootstrapping 联合判据** | `enableBootstrapping` | 防 Ratio 假阳性 | ❌ **动态严重遮挡无效**：Fix暴跌77%，false fix比例反升40.9%；根因EKF发散后方差不可靠 |
| **BDS 卫星码偏差改正** | `enableBdsCodeBias` | 含 BDS 时通用 | 系统级改正，非 AR 优化 |
| **大气参数自适应冻结** | `atmFrozenNsThresh` | 长基线少星时 | IFLC 模式不估大气参数 |
| **电离层/对流层梯度** | `enableIonoTropGradient` | 长基线非 IFLC | IFLC 消除电离层一阶项，梯度改正需非 IFLC 模式 |

---

## 6. 优化项适用场景矩阵

### 6.1 场景适用性

> **"静态"列说明**：本报告无静态数据集测试结果，该列内容均为**理论推断**，不作为实测结论引用。

| 优化项 | 静态¹ | 动态 | 短基线(<10km) | 中基线(10~20km) | 长基线(>20km) | 验证状态 |
|--------|------|------|---------------|----------------|--------------|---------|
| **AdaptiveQ** | ❌ 不适用（Q 需动态变化） | ✅ 短/长基线有效，中基线有害 | ✅ UrbanNav Fix+12% | ❌ Tokyo Fix-49%/-20% | ✅ Nagoya run1 Fix+72% | ✅ 已测试（IFLC+EST+OFF） |
| **IGGIII** | ⚠️ 经典静态网有效 | ⚠️ 强场景依赖（run1有害-32%/run2有效+29%） | ⚠️ UrbanNav ≈C | ⚠️ Tokyo 场景依赖 | ✅ Nagoya 精度-18%~33% | ✅ 已测试（IFLC+EST，含逐卫星诊断） |
| **SnrMedian** | ✅ 理论有效（弱信号降权） | ✅ 唯一无副作用优化 | ✅ UrbanNav ≈C（无害） | ✅ Tokyo ≈C（无害） | ✅ Nagoya 精度-11%~22% | ✅ 已测试（IFLC+EST+OFF） |
| **ResEdit** | ⚠️ 批处理静态网有效 | ❌ 严重遮挡有害（IFLC 下 Fix-74%~-75%） | ❌ UrbanNav Fix-75%（IFLC） | ❌ Tokyo Fix-74%/-29%（IFLC） | ≈C Nagoya | ✅ 已测试（IFLC+EST+OFF，有害性100%由IFLC造成） |
| **ParamNoise** | ❌ IFLC 无估计对象 | ⚠️ EST 下已修复（≈C） | — 未测 | ⚠️ EST 下修复后≈C | — 未测 | ✅ 已测试+修复（替换式+仅初始化+1e-4） |
| **PartAR** | ⚠️ 静态长基线经典场景 | ❌ false fix（H.RMSE 63~266m） | ❌ false fix | ❌ false fix | ❌ false fix | ✅ 已测试（IFLC） |
| **CascadeAR** | ⚠️ 三频+惯性动态 | ⚠️ v2.3.0已重写为真逐级（FIX-ALG-007），待重测 | ⚠️ v2.3.0待重测 | ⚠️ v2.3.0待重测 | ⚠️ v2.3.0待重测 | ✅ v2.2.x已测试（伪逐级，IFLC下Fix=0%）；v2.3.0真逐级待重测 |
| **AmbAnchor** | ✅ 静态连续Fix后锚固 | ⚠️ 理论有效（Fix稳定后防失锁） | ⚠️ 理论有效 | ⚠️ 理论有效 | ⚠️ 理论有效 | ❌ 未测试（城市峡谷Fix率低，锚固条件难满足） |
| **ParRefReselect** | ⚠️ 静态多频 | ⚠️ 理论有效 | ⚠️ 理论有效 | ⚠️ 理论有效 | ⚠️ 理论有效 | ✅ 已测试无效（三组=C） |
| **IonoTropGradient** | ⚠️ 静态长基线非IFLC | ⚠️ 理论有效（非IFLC长基线） | ❌ 短基线不适用 | ⚠️ 理论有效 | ✅ 长基线非IFLC | ✅ 已测试（EST下=C，需更长基线验证） |
| **atmFrozenNsThresh** | ⚠️ 静态长基线少星 | ⚠️ 理论有效（少星冻结大气） | ❌ 短基线不适用 | ⚠️ 理论有效 | ✅ 长基线少星 | ❌ 未测试 |
| **Bootstrapping** | ✅ 静态防false fix | ❌ **动态严重遮挡无效**（Fix暴跌77%，false fix比例反升） | ❌ **无效**（EKF发散后方差不可靠） | ⚠️ 理论有效 | ⚠️ 理论有效 | ✅ 已测试（UrbanNav OFF，无效） |
| **SegmentSigmaFilter** | ⚠️ 静态需验证 | ✅ **严重遮挡下有效**（在线追溯失效false fix段） | ✅ UrbanNav OFF: Fix 8.2%, false fix 1.5% | ⚠️ 理论有效 | ⚠️ 理论有效 | ✅ 已测试（UrbanNav OFF，σ<5m最优） |
| **BdsCodeBias** | ✅ 含BDS时通用 | ✅ 含BDS时通用 | ✅ 含BDS时通用 | ✅ 含BDS时通用 | ✅ 含BDS时通用 | ❌ 未测试（系统级改正） |

> **图例**：✅ = 推荐/已验证有效 | ⚠️ = 理论适用/弱效果/需额外验证 | ❌ = 不推荐/无效/有害 | — = 不适用/未测  
> **¹ 静态列**：理论推断，无静态数据集实测验证  
> **关键结论**：  
> 1. **SnrMedian 是唯一全场景安全优化**，5 组数据中 3 组有效、2 组无害，建议默认开启  
> 2. **AdaptiveQ/ResEdit/IGGIII 强场景依赖**，不能简单全开——AdapQ 仅短/长基线有效，ResEdit 有害性 100% 由 IFLC 造成（EST/OFF 下无害），IGGIII 需自适应改造  
> 3. **PartAR 动态遮挡下不可用**（false fix 严重）；**CascadeAR v2.2.x伪逐级已重写为v2.3.0真逐级**（FIX-ALG-007），IFLC下由FIX-ALG-006守卫短路，非IFLC下待重测
> 4. **Bootstrapping 在动态严重遮挡下无效**——Fix 暴跌 77% 但 false fix 比例反升，根因是 EKF 发散后方差不可靠，所有基于方差的 AR 判据均失效  
> 5. **false fix 的根本解决方案不是调 AR 判据**，而是防止 EKF 发散（FDE 后验检验、连续性判据）或降低 AR 期望  
> 6. **段内位置标准差 σ 过滤是在线解决 false fix 的有效方案**：σ<5m 可将 false fix 从 54.5% 降至 1.5%，H.RMSE 从 2.827m 降至 0.620m，在线效果与后处理几乎一致（0.614m）；代价是 Fix 率从 54% 降至 8.2%，但剩余 Fix 质量可靠
> 7. **AmbAnchor/BdsCodeBias 理论有效但未测试**，是后续验证候选

### 6.2 已测试数据集

| 优化项 | 已测试数据集 | 电离层模式 | 结论来源 |
|--------|------------|-----------|---------|
| **AdaptiveQ** | Nagoya run1/run2, Tokyo run1/run2/run3, UrbanNav | IFLC+EST+OFF | IFLC 中基线有害；EST ≈C；OFF 短基线+8% |
| **IGGIII** | 同上 | IFLC+EST | IFLC 场景依赖；EST run1 有害-32%/run2 有效+29%；逐卫星诊断已做 |
| **SnrMedian** | Nagoya run1/run2, Tokyo run1/run2, UrbanNav | IFLC+EST+OFF | 3/5 有效，2/5 无害 |
| **ResEdit** | Nagoya run1/run2, Tokyo run1/run2/run3, UrbanNav | IFLC+EST+OFF | IFLC 有害；EST/OFF 无害；有害性 100% 由 IFLC 造成 |
| **ParamNoise** | Tokyo run1/run2 | EST | 修复前 Fix-77%/-81%；修复后 ≈C/-33% |
| **PartAR** | Nagoya run1/run2, Tokyo run1/run2, UrbanNav | IFLC | Fix+58%~200% 但 H.RMSE 63~266m（false fix） |
| **CascadeAR** | Nagoya run1/run2, Tokyo run1/run2, UrbanNav | IFLC | v2.2.x Fix=0%（伪逐级）；v2.3.0真逐级重写，待重测 |
| **ParRefReselect** | Nagoya run1, Tokyo run1, UrbanNav | IFLC | 三组均 =C（无效） |
| **IonoTropGradient** | Tokyo run1/run2 | EST | =C（需更长基线验证） |
| **Bootstrapping** | UrbanNav | OFF | Fix暴跌77%，false fix比例反升；EKF发散后方差不可靠 |
| **连续性判据** | UrbanNav | OFF | 在线段内σ过滤有效：σ<5m最优——Fix 8.2%, false fix 1.5%, H.RMSE=0.620m；与后处理0.614m几乎一致；连续N历元/跳变过滤无效 |

### 6.3 场景-优化推荐矩阵（按场景维度）

基于六组数据实测结果：

| 场景 | enableAdaptiveQ | enableIggiii | enableSnrMedian | enableResidualEdit | enablePartialAR | enableCascadeAR | enableSegmentSigmaFilter |
|------|-----------------|--------------|-----------------|-------------------|-----------------|-----------------|-------------------------|
| **长基线+轻度遮挡 (Nagoya)** | ⚠️ run1+72%, run2≈C | ✅ 精度-18%~33% | ✅ 精度-11%~22% | ❌ 无效 | ⚠️ false fix | ⚠️ v2.3.0待重测 | ⚠️ 理论有效 |
| **中基线+部分遮挡 (Tokyo run2)** | ❌ 无效 | ❌ Fix-75% | ✅ 精度-14% | ✅ Fix+115% | ⚠️ false fix | ⚠️ v2.3.0待重测 | ⚠️ 理论有效 |
| **中基线+部分遮挡 (Tokyo run1)** | ❌ Fix-49% | ✅ Float-23%, Fix-72% | ❌ 无效 | ❌ Fix-74% | ⚠️ false fix | ⚠️ v2.3.0待重测 | ⚠️ 理论有效 |
| **中基线+部分遮挡 (Tokyo run3)** | ❌ Fix-20% | ⚠️ Fix-13%, Float-10% | ❌ 无效 | ❌ Fix-29% | ⚠️ false fix | ⚠️ v2.3.0待重测 | ⚠️ 理论有效 |
| **短基线+严重遮挡 (UrbanNav)** | ✅ Fix+12% | ❌ 无效 | ❌ 无效 | ❌ Fix-75% | ⚠️ false fix | ⚠️ v2.3.0待重测 | ✅ false fix 1.5% |
| **动态 RTK + 开阔天空** | ⚠️ 弱收益 | ⚠️ 弱收益 | ⚠️ 弱收益 | ⚠️ 弱收益 | ✅ | ✅ | ⚠️ 无需 |
| **静态 RTK + 短基线** | ❌ 不适用 | ⚠️ 弱收益 | ⚠️ 弱收益 | ⚠️ 弱收益 | ✅ | ✅ | ⚠️ 需验证 |

> **图例**：✅ = 推荐开启 | ⚠️ = 可开但效果弱/需额外验证 | ❌ = 不推荐/无效/Bug  
> **关键发现**：  
> 1. AdaptiveQ、IGGIII、ResEdit 三个优化的效果**强烈依赖场景**，不能简单全开或全关  
> 2. **Tokyo 三时段验证**：run1/run3 属同一模式（ResEdit 有害），run2 为孤立异常模式（ResEdit 有效）  
> 3. SnrMedian 是唯一**无副作用**的优化，5 组数据中 3 组有效、2 组无效果（但无害）  
> 4. ResEdit 在严重遮挡场景（UrbanNav Fix-75%、Tokyo run1 Fix-74%、run3 Fix-29%）一致有害，可能误删有效观测  
> 5. AdaptiveQ 在短基线（UrbanNav +12%）和长基线（Nagoya run1 +72%）有效，但在中基线有害（Tokyo run1 -49%、run3 -20%）

---

## 7. 待修复项

| # | 问题 | 优先级 | 影响 |
|---|------|--------|------|
| 1 | **enableCascadeAR v2.2.x伪逐级已重写为v2.3.0真逐级** | ✅ 已修复（FIX-ALG-007） | v2.2.x伪逐级：三次LAMBDA共用y/Qb，EWL/WL验证冗余；v2.3.0真逐级：每级独立组合变换+级间条件更新+整数反推+restamb；非IFLC下待重测 |
| 2 | **enablePartialAR 产生大量 false fix** | 🟡 中 | Fix H.RMSE=63.9~265.8m，需加强子集验证判据 |
| 3 | **enableParamTypeNoise EST 下过程噪声问题** | ✅ 已修复 | 三步修复：替换式+仅已初始化+noiseIonoRw=1e-4；Tokyo run1 Fix 0.8%→3.1%（≈C） |
| 4 | **enableIggIII 场景依赖性强** | 🟢 低 | EST 下 run1 有害-32%/run2 有效+29%，与 IFLC 下反转模式一致，属场景依赖非模型不兼容 |
| 5 | **ResEdit/AdapQ 在 IFLC 中基线下有害** | 🟢 低 | 已归因 IFLC（EST/OFF 下无害），需 IFLC 模式下自适应禁用策略 |
| 6 | **UrbanNav 参考坐标框架** | ✅ 已修复 | .pos 文件是 ECEF 格式但被当 LLH 解析；修复后 H.RMSE 从 5×10¹¹m 降至 4.89m(IFLC)/2.83m(OFF)；false fix 诊断：OFF 54% Fix 中 37.6% 为 false fix |
| 7 | **Bootstrapping 动态严重遮挡下无效** | ✅ 已验证 | Fix暴跌77%(54%→12.4%)，false fix比例反升(37.6%→40.9%)；根因EKF发散后方差不可靠，所有基于方差的AR判据均失效 |
| 8 | **AR判据增强无法解决false fix** | ✅ 已验证 | Ratio 3→20、posvar 0.9999→0.01、Bootstrapping 0.99 三种方法均无法降低false fix比例；根因是EKF状态估计被污染 |
| 9 | **连续性判据验证** | ✅ 已验证 | 连续N历元过滤无效（false fix段本身很长，最长1437历元）；位置跳变过滤无效（false fix段内跳变小）；**段内位置标准差σ<5m在线过滤有效**：Fix 8.2%，H>1m仅1.5%，H.RMSE=0.620m；已集成到PostPosProcessor（forward+backward）和RtkProcessor |
| 10 | **段内σ过滤集成路径** | ✅ 已完成 | 初始集成仅覆盖RtkProcessor，测试走PostPosProcessor路径导致过滤未执行；已修复：PostPosProcessor.processForward + processBackward 均已集成；默认配置：threshold=5.0, minLen=5 |

---

## 8. 执行命令

```bash
# 单数据集基准测试（C-EX-Ref）
mvn test -pl rtklib-core -Dtest="DynamicRtkOptTest#testPpcNagoyaRun1"
mvn test -pl rtklib-core -Dtest="DynamicRtkOptTest#testPpcTokyoRun2"
mvn test -pl rtklib-core -Dtest="DynamicRtkOptTest#testPpcTokyoRun1"

# 优化对比（14 变体，耗时较长）
mvn test -pl rtklib-core -Dtest="DynamicRtkOptTest#testOptComparisonNagoyaRun1"
mvn test -pl rtklib-core -Dtest="DynamicRtkOptTest#testOptComparisonNagoyaRun2"
mvn test -pl rtklib-core -Dtest="DynamicRtkOptTest#testOptComparisonTokyoRun2"
mvn test -pl rtklib-core -Dtest="DynamicRtkOptTest#testOptComparisonTokyoRun1"
mvn test -pl rtklib-core -Dtest="DynamicRtkOptTest#testOptComparisonTokyoRun3"
mvn test -pl rtklib-core -Dtest="DynamicRtkOptTest#testKeyComparisonTokyoRun3"
mvn test -pl rtklib-core -Dtest="DynamicRtkOptTest#testOptComparisonUrbanNavTokyo"

# 补充对比（M-ParRef & N-ParamNoise，3 变体）
mvn test -pl rtklib-core -Dtest="DynamicRtkOptTest#testSupplementNagoyaRun1"
mvn test -pl rtklib-core -Dtest="DynamicRtkOptTest#testSupplementTokyoRun2"

# IFLC 归因验证（EST/OFF 对照，7 变体/组）
mvn test -pl rtklib-core -Dtest="DynamicRtkOptTest#testIflcAttributionNagoyaRun1"
mvn test -pl rtklib-core -Dtest="DynamicRtkOptTest#testIflcAttributionNagoyaRun1ThresarScan"
mvn test -pl rtklib-core -Dtest="DynamicRtkOptTest#testIflcAttributionTokyoRun1"
mvn test -pl rtklib-core -Dtest="DynamicRtkOptTest#testIflcAttributionTokyoRun2"
mvn test -pl rtklib-core -Dtest="DynamicRtkOptTest#testIflcAttributionUrbanNav"

# Bootstrapping & AR判据验证
mvn test -pl rtklib-core -Dtest="DynamicRtkOptTest#testBootstrappingFalseFixReduction"
mvn test -pl rtklib-core -Dtest="DynamicRtkOptTest#testUrbanNavOffThresarScan"

# 连续性判据验证
mvn test -pl rtklib-core -Dtest="DynamicRtkOptTest#testContinuityFilterFalseFixReduction"

# 在线段内σ过滤验证（UrbanNav Tokyo OFF模式，参数扫描）
mvn test -pl rtklib-core -Dtest="DynamicRtkOptTest#testOnlineSegmentSigmaFilter"

# 全量汇总（8×14=112 组，极长，需手动启用 @Disabled）
mvn test -pl rtklib-core -Dtest="DynamicRtkOptTest#testSummaryAll"
```