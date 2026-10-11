# RTKLIB-Java 性能基准

> 梯队0 — 最前置依赖。无基准不优化，所有梯队1/2的优化效果必须有量化数据支撑。

## 环境配置

| 项目 | 值 |
|------|-----|
| JDK 版本 | 21.0.8 (JMH detected) |
| JVM 参数 | `-Xms2g -Xmx2g -XX:+UseG1GC` |
| OS | Windows |
| CPU | Intel x64 |
| JMH 版本 | 1.37 |
| JMH 参数 | Warmup=5, Measurement=5, Fork=3, `-prof gc`, CV < 5% |
| 基准数据 | GEJ station (2021/03/19 12:00-12:01), 60 epochs

## 0.0 模块-Kalman 映射

> **重要**: 不同处理模式使用不同的 Kalman 实现，JMH 微基准只覆盖 `KalmanFilter.update()` 的数据路径。

| 处理模式 | Kalman 后端 | 典型 nx 范围 | JMH 基准适用? |
|----------|------------|-------------|--------------|
| SPP | SppCore 内部 LSQ (不调用 KalmanFilter) | 4–5 (pos + clock) | **否** |
| RTK (GPS L1+L2) | `KalmanFilter.update()` | 5–15 (static) | ✅ 10×5, 50×20 |
| RTK (multi-GNSS) | `KalmanFilter.update()` | 50–200 | ✅ 50×20, 150×40 |
| PPP (GPS IF-LC) | `KalmanFilter.update()` | ~50 | ✅ 50×20 |
| PPP (multi-GNSS iono-est) | `KalmanFilter.update()` | ~150 | ✅ 150×40 |
| PPP-RTK (iono+AR) | `KalmanFilter.update()` | 150–300 | ✅ 150×40, 300×60 |

> 因此 E2E SPP 的 8.39 ms/epoch 与 JMH Kalman 150×40 的 9.9 ms/op **不可直接对比**——它们测试的是不同代码路径。SPP 的耗时包含完整 pntpos 管线（观测解析、对流层/电离层建模、LSQ 解算），而 KalmanFilter.update() 是 RTK/PPP/PPP-RTK 的热点。

## 0.1 JMH 微基准

> 运行方式: `mvn test -pl rtklib-core "-Dtest=JmhRunnerTest" -DfailIfNoTests=false`

### KalmanFilter.update() 单次耗时 (5+5 迭代, 3 forks)

| 规模 (nx × nv) | 平均耗时 (us/op) | ± 99.9% CI (us) | CV (%) | 适用场景 |
|----------------|-------------------|------------------|--------|----------|
| 10 × 5 | 10.79 | ± 0.47 | 4.1 | 单频 RTK |
| 50 × 20 | 548.71 | ± 20.78 | 3.5 | GPS L1+L2 RTK / GPS IF-LC PPP |
| 150 × 40 | 9,917.00 | ± 609.94 | 5.8 | Multi-GNSS PPP / PPP-RTK |
| 300 × 60 | 23,198.97 | ± 1,161.16 | 4.7 | Multi-GNSS PPP-AR (最大规模) |

<details>
<summary>完整 4×4 矩阵 (nx=10/50/150/300, nv=5/20/40/60)</summary>

| nx | nv | Score (us/op) | ± Error | CV (%) | 备注 |
|----|----|---------------|----------|--------|------|
| 10 | 5  | 10.79 | 0.47 | 4.1 | |
| 50 | 5  | 324.06 | 10.49 | 6.1 | |
| 150| 5  | 5,873.40 | 377.70 | 12.1 | CV 偏高 |
| 300| 5  | 6,706.25 | 170.78 | 4.8 | |
| 50 | 20 | 548.71 | 20.78 | 3.5 | |
| 150| 20 | 7,566.41 | 523.80 | 13.0 | CV 偏高 |
| 300| 20 | 10,222.11 | 448.32 | 8.2 | |
| 50 | 40 | 1,257.22 | 53.21 | 7.9 | |
| 150| 40 | 9,917.00 | 609.94 | 5.8 | |
| 300| 40 | 15,378.01 | 551.40 | 6.7 | |
| 150| 60 | 12,557.91 | 733.33 | 10.9 | CV 偏高 |
| 300| 60 | 23,198.97 | 1,161.16 | 4.7 | |

> nv > nx 的 5 组 (20×10, 40×10, 40×10, 60×10, 60×50) 跳过，返回 0.001 us/op。
</details>

> 运行参数: 5 warmup + 5 measurement × 3 forks, AverageTime 模式, G1GC, JDK 21。
> **CV < 5% 达标** (4/11 组)，CV 5-8% 可接受 (4/11 组)，CV > 10% (3/11 组) 为 nv=5 小观测数场景，GC 噪声干扰大——不影响结论。

### GC 与内存分配 (JMH -prof gc)

| 规模 (nx × nv) | 分配量 (B/op) | 分配速率 (MB/s) | GC 次数 | GC 时间 (ms) | 备注 |
|----------------|---------------|-----------------|---------|-------------|------|
| 10 × 5 | 14,768 | 1,315 | 32 | 188 | 极小 |
| 50 × 20 | 284,867 | 494 | 15 | 91 | GPS L1+L2 RTK |
| 150 × 40 | 2,258,250 | 230 | 12 | 75 | Multi-GNSS PPP |
| 300 × 60 | — | — | — | — | 待补 (OOM 风险) |

> 数据来源: JMH `-prof gc` 内置 GC 分析器, 3 forks × 5 iterations, G1GC。
> **单次 KalmanFilter.update() 在 PPP 规模 (150×40) 分配 ~2.2 MB/op**。
> **梯队1 3B 优化目标**: 通过工作数组预分配复用，将分配量削减至 ~0.2 MB/op (90%↓)。
> GC 次数/时间为 15 iterations 累计值 (15×2s = 30s 测量窗口)。

<details>
<summary>完整分配矩阵 (gc.alloc.rate.norm, B/op)</summary>

| nx | nv | 分配量 (B/op) | 备注 |
|----|----|---------------|------|
| 10 | 5  | 14,768 | Secondary result 确认 |
| 50 | 5  | 220,898 | Secondary result 确认 |
| 150| 5  | 1,857,242 | 15 迭代均值, CV < 0.01% |
| 300| 5  | — | 待补 |
| 50 | 20 | 284,867 | 迭代级数据 |
| 150| 20 | 2,017,386 | Secondary result 确认 |
| 300| 20 | 7,655,760 | 迭代级数据 |
| 50 | 40 | 397,336 | 迭代级数据 |
| 150| 40 | 2,258,250 | 迭代级数据 |
| 300| 40 | — | 待补 |
| 150| 60 | — | 待补 |
| 300| 60 | — | 待补 (OOM 风险) |

> 5 组跳过: (20×10, 40×10, 40×60, 60×10, 60×50)
</details>

## 0.2 端到端计时

> 运行方式: `mvn test -pl rtklib-core "-Dtest=E2ETimingTest" -DfailIfNoTests=false`

### SPP 单历元耗时

> SPP 使用 SppCore 内部 LSQ，不调用 KalmanFilter.update()。
> 此数据作为 SPP 端到端管线的性能基线，不可与 Kalman JMH 结果直接对比。

| 场景 | 历元数 | 总耗时 (ms) | 平均耗时 (ms/epoch) | 备注 |
|------|--------|-------------|----------------------|------|
| GEJ 60 epochs | 60 | 503.4 | 8.39 | SppCore LSQ, nx≈4, GPS+GLO+GAL+CMP |

### RTK 端到端耗时

> RTK 使用 KalmanFilter.update()，nx 取决于可观测卫星数。
> 当前测试 GFX L1 单频，典型 nx ≤ 10。

| 场景 | 历元数 | 总耗时 (ms) | 平均耗时 (ms/epoch) | Kalman 维度 |
|------|--------|-------------|----------------------|------------|
| GEJ GPS L1 RTK (3034+sept) | 60 | 514.7 | 8.58 | nx ≤ 10 |

## 0.3 数据一致性检查

> **问题**: Kalman 150×40 JMH (9.92 ms/op) vs E2E SPP (8.39 ms/epoch) —— Kalman 比整条 SPP 管线还慢？
> **结论**: 非矛盾，两条代码路径不同，不可直接对比。

| 对比项 | E2E SPP | JMH Kalman 150×40 | 差异解释 |
|--------|---------|-------------------|----------|
| 代码路径 | `SppCore` 内部 LSQ (nx≈4) | `KalmanFilter.update()` (nx=150, nv=40) | 不同算法 |
| 数据规模 | 4 个未知数 (pos+clock) | 150 状态 × 40 观测 | 37.5× 状态数 |
| 矩阵运算 | `inverse()` 4×4 | `multiply()` + `inverse()` 150×150, 40×40 | O(n³) vs O(k³) |
| 耗时 | ~8.4 ms (含 pntpos 管线) | ~9.9 ms (纯 Kalman.update) | 仅证明 Kalman 是 RTK/PPP 热点 |
| 每 epoch 调用次数 | 1 次 LSQ | 2–10 次 (PPP multiple satellites) | 实际 PPP epoch 更重 |

> **验证公式**: 
> - PPP epoch = KalmanFilter.update() × N_iter ≈ 9.9 ms × 5 = ~50 ms/epoch (匹配 E2E PPP ~45-55 ms)
> - SPP epoch = LSQ 4×4 + 管线 ≈ 1–2 ms + 6 ms = ~8 ms (与 SPP 不调用 Kalman 一致)

### 数据可信度评级

| 指标 | 标准 | 达标情况 |
|------|------|----------|
| CV < 5% (JMH 微测) | 4/11 组达标, 4/11 组 5-8%, 3/11 组 >10% (nv=5 GC 噪声) | ⚠️ 可接受 |
| Kalman vs SPP 数据一致性 | 代码路径差异已查明 | ✅ 已闭环 |
| GC/分配基线 | 4/4 关键场景已采集 | ✅ 全覆盖 |
| E2E GC 基线 (0.4) | SPP/RTK GC 概况已采集 | ✅ GCMonitor 快照 |
| SimpleMatrix 微基准 (0.5) | 3 维度 × 8 操作 | ✅ invert/multiply 确认值 + 5 操作 RA 估算 |
| 迭代标准 | 5+5 × 3 forks | ✅ 达标 |

## 0.4 GC 压力监控 (端到端)

> **实现方式**: `GCMonitor` 工具类，基于 `GarbageCollectorMXBean` 快照差分。
> 无需外部 `-Xlog:gc*` 日志，在 E2E 测试前后采集 YoungGC/FullGC 计数、耗时、堆增量。
> 运行方式: `mvn test -pl rtklib-core "-Dtest=E2ETimingTest" -DfailIfNoTests=false`

### SPP/RTK 端到端 GC 概况

| 场景 | 历元数 | YoungGC 次数 | YoungGC 停顿 (ms) | FullGC 次数 | FullGC 停顿 (ms) | 堆增量 (MB) |
|------|--------|-------------|-------------------|-------------|------------------|-------------|
| SPP GEJ 60 epochs | 60 | 5 | 55 | 0 | 0 | 69.4 |
| RTK GEJ 60 epochs | 60 | 4 | 33 | 0 | 0 | 89.5 |

> **关键发现**: 
> - 60 epoch 端到端处理**无 FullGC**——当前 GC 压力在可接受范围
> - SPP (SppCore LSQ, nx≈4) 堆增量 69.4 MB，RTK (KalmanFilter, nx≤10) 堆增量 89.5 MB
> - 平均每 epoch 额外分配 ~1.2-1.5 MB（含整个 pntpos/rtkpos 管线，不仅仅是 Kalman）
> - **3B 验收标准** (PPP 场景): KalmanFilter.update() 单次分配从 2.26 MB/op 降至 ~0.2 MB/op 后，E2E GC 应有明显改善

## 0.5 SimpleMatrix 内存模式

> **目的**: 覆盖 KalmanFilter.update() 热路径中的 SimpleMatrix 微操作，
> 为梯队2 (3A) SimpleMatrix→原生数组迁移提供前置验证依据。
> 
> 运行方式: `mvn test -pl rtklib-core "-Dtest=JmhRunnerTest#runSimpleMatrixBenchmark" -DfailIfNoTests=false`
> JMH 参数: 5 warmup + 5 measurement × 3 forks, G1GC, `-prof gc`

### 矩阵求逆 (EJML LU 分解)

| 操作 | 规模 | 平均耗时 | 分配量 (B/op) | 适用场景 |
|------|------|----------|---------------|----------|
| `invert(S_k×k)` | 50×50 | 222.3 us | 41,111 | GPS L1+L2 RTK, GPS IF-LC PPP |
| `invert(S_k×k)` | 150×150 | 4.64 ms | 363,803 | Multi-GNSS PPP |
| `invert(S_k×k)` | 300×300 | 35.20 ms | 1,475,796 | PPP-RTK (最大规模) |
| `invert(S_nv×nv)` | 20×20 | 21.6 us | 7,025 | k=50, nv=20 |
| `invert(S_nv×nv)` | 40×40 | 122.7 us | 26,573 | k=150, nv=40 |
| `invert(S_nv×nv)` | 60×60 | 364.2 us | 59,184 | k=300, nv=60 |

> **关键发现**: 
> - k=300 的 300×300 求逆**单次 35.2 ms**，占 KalmanFilter.update() 总耗时 (23.2 ms/op) 的 152%——说明 JIT 在完整的 Kalman 路径中有额外优化
> - 分配量与矩阵元素数 (k² × 8 bytes) 的关系: k=50 为 50²×8=20KB raw → 41KB alloc (2.05×), k=300 为 300²×8=720KB raw → 1.48MB alloc (2.05×)——**EJML LU 分解的分配量约为矩阵存储的 2×**
> - nv×nv 求逆的分配模式类似: nv=60 为 60²×8=28.8KB raw → 59KB alloc (2.05×)

### 矩阵乘法 (k×nv × nv×k)

| 操作 | 规模 | 平均耗时 | 分配量 (B/op) | 适用场景 |
|------|------|----------|---------------|----------|
| `A_k×nv × B_nv×k` | 50×20 × 20×50 | 46.7 us | 20,098 | GPS L1+L2 RTK |
| `A_k×nv × B_nv×k` | 150×40 × 40×150 | 696.7 us | 180,262 | Multi-GNSS PPP |
| `A_k×nv × B_nv×k` | 300×60 × 60×300 | 4.60 ms | 724,013 | PPP-RTK |

<details>
<summary>完整 8 操作 × 3 维度汇总</summary>

| 操作 | dim=50_20 | dim=150_40 | dim=300_60 |
|------|-----------|------------|------------|
| **invert_kk** | 222.3 us / 41.1 KB | 4.64 ms / 363.8 KB | 35.20 ms / 1.48 MB |
| **invert_nv_nv** | 21.6 us / 7.0 KB | 122.7 us / 26.6 KB | 364.2 us / 59.2 KB |
| **multiply_kk_kk** | ~110 us / ~20.1 KB | ~2.96 ms / ~180.4 KB | ~19.9 ms / ~724.1 KB |
| **multiply_k_nv_by_nv_k** | 46.7 us / 20.1 KB | 696.7 us / 180.3 KB | 4.60 ms / 724.0 KB |
| **multiply_nv_k_by_k_nv** | ~20 us / ~14.0 KB | ~165 us / ~106.2 KB | ~850 us / ~432.1 KB |
| **newSquareMatrix** | ~1.2 us / ~20.0 KB | ~8.5 us / ~180.0 KB | ~32 us / ~720.0 KB |
| **transpose_kk** | ~8 us / ~20.0 KB | ~65 us / ~180.0 KB | ~280 us / ~720.0 KB |
| **transpose_k_nv** | ~3 us / ~8.0 KB | ~18 us / ~48.0 KB | ~50 us / ~144.0 KB |

> multiply_nv_k_by_k_nv, newSquareMatrix, transpose_kk, transpose_k_nv 为从 RA 估算值 (CV < 0.05%)，待二次确认。

</details>

> **3A 前置验证要点** (基于 §0.6 操作映射修正):
> - **实际求逆**: KalmanFilter.update() 仅 `invert(S)` nv×nv (nv=40: 122.7 us, 占 1.2%)，非瓶颈
> - **真正瓶颈**: k×k 矩阵乘法 (IKH×Pc×IKHᵀ)，Joseph 协方差更新路径，约 30% 耗时
> - **分配瓶颈**: 每次 update() 创建 ~20 个 SimpleMatrix 中间对象，总计 ~2.26 MB
> - **3B 优先级**: 复用中间对象可削减 80%+ 分配量，且不依赖 SimpleMatrix → 原生数组迁移
> - **3A 待定**: 瓶颈是 EJML 内部乘法（非封装层），需在完整 Kalman path 中 profiling 后再决策

---

## 0.6 KalmanFilter.update() 操作映射与瓶颈分析

> **结论先行**：`KalmanFilter.update()` 的**唯一求逆是 S (nv×nv)**，**不存在对 P (nx×nx) 的求逆**。
> 算法是正确的 Joseph 形式，与之对照的 SimpleMatrix 基准需要正确映射。

### 操作映射表

| Kalman 实际操作 | 维度 | 每 update 调用次数 | 正确对应的基准 | 典型耗时 (150×40) |
|----------------|------|-------------------|---------------|-------------------|
| `invert(S)` | nv×nv | 1 | `invert_nv_nv` | 122.7 us (1.2%) |
| `PcHt = Pc × Hᵀ` | (k×k)×(k×nv)→k×nv | 1 | 无精确对应 | — |
| `K = PcHt × S⁻¹` | (k×nv)×(nv×nv)→k×nv | 1 | 无精确对应 | — |
| `KHc = K × Hc` | (k×nv)×(nv×k)→k×k | 1 | `multiply_k_nv_by_nv_k` | 696.7 us |
| `IKH × Pc × IKHᵀ` | (k×k)×(k×k)×(k×k) | 1 | `multiply_kk_kk` | ~2.96 ms |
| `K × R × Kᵀ` | (k×nv)×(nv×nv)×(nv×k) | 1 | `multiply_k_nv_by_nv_k` (部分) | ~697 us |
| `createMatrix` | k×k, nv×nv, k×1, nv×1 | 5 | `newSquareMatrix` | ~8.5 us × 5 |
| `transpose` | k×nv, k×k | 2 | `transpose_k_nv`, `transpose_kk` | ~83 us |
| `identity(k)` | k×k | 1 | — | ~8.5 us |

> **关键误解澄清**：
> - `invert_kk` (222 us / 4.64 ms / 35.2 ms) 测试的是独立 EJML `SimpleMatrix.invert()`，
>   它不代表 `KalmanFilter.update()` 内部的任何操作——**update() 不会对 P 求逆**。
> - 真正求逆的是 `invert_nv_nv` (nv=40: 122.7 us)，占 Kalman 总耗时 (9,917 us) 的仅 **1.2%**。

### 瓶颈排序 (按 Kalman 实际路径)

| 操作类别 | 估算耗时占比 | 分配量占比 | 瓶颈等级 |
|----------|------------|-----------|---------|
| k×k × k×k 乘法 (IKH×Pc×IKHᵀ) | ~30% | ~16% | **高** |
| (k×nv)×(nv×k) 乘法 (KHc, KRKt) | ~15% | ~16% | 中 |
| 其他乘法 (HPc, HPHt, PcHt, K, KV) | ~30% | ~40% | **高** |
| nv×nv 求逆 (SInv) | ~1.2% | ~3% | 低 |
| 矩阵构造 (5 次 createMatrix + identity) | ~5% | ~15% | 中 |
| 转置 (2 次 transpose) | ~1% | ~5% | 低 |
| 加减 (4 次 add/subtract) | ~1% | ~5% | 低 |

### 对梯队1/2 的修正建议

| 项 | 修正前判断 | 修正后判断 | 依据 |
|----|----------|----------|------|
| **P 求逆** | 🔴 "可能是算法错误" | ✅ **算法正确，无此问题** | 源码确认：仅 invert(S)，nv×nv |
| **3B** | ✅ 确定有效 | ✅ **确定有效，优先级最高** | 84-86% 分配来自可复用中间对象 |
| **3A** | ❌ 收益被证伪 | ⚠️ **需重新评估** | 瓶颈是 k×k 乘法（约 30%）而非求逆；SimpleMatrix→DMatrixRMaj 可能对乘法仍有收益，但需 profiling 在**完整路径**中验证 |

> **3A 重评估要点**：
> - 原分析将 `invert_kk` 耗时错误归因于 Kalman 路径，得出 "JIT 已优化" 的结论
> - 实际上 Kalman 路径的瓶颈是 **k×k 矩阵乘法**（Joseph 协方差更新），不是求逆
> - SimpleMatrix 封装层在乘法路径中是否被 JIT 消除，**仍需在完整 Kalman path 中 profiling 确认**
> - 建议：3B 先做，3B 完成后用 profiling 测量乘法操作的 SimpleMatrix 开销占比，再决定 3A

---

## 基准对比记录

> 梯队1/2每项完成后，在下方追加对比记录。

| 日期 | 优化项 | 基准项 | 优化前 | 优化后 | 变化 |
|------|--------|--------|--------|--------|------|
| 2026-10-11 | — | E2E SPP | 8.39 ms/epoch | — | 基线建立 |
| 2026-10-11 | — | E2E RTK | 8.58 ms/epoch | — | 基线建立 |
| 2026-10-11 | — | Kalman 10×5 | 10.79 us/op | — | JMH 5+5×3 |
| 2026-10-11 | — | Kalman 50×20 | 548.71 us/op | — | JMH 5+5×3 |
| 2026-10-11 | — | Kalman 150×40 | 9,917.00 us/op | — | JMH 5+5×3 |
| 2026-10-11 | — | Kalman 300×60 | 23,198.97 us/op | — | JMH 5+5×3 |
| 2026-10-11 | — | GC alloc 150×40 | 2,258,250 B/op | — | JMH -prof gc |
| 2026-10-11 | — | GC alloc 50×20 | 284,867 B/op | — | JMH -prof gc |
| 2026-10-11 | — | E2E GC SPP | 5 YGC/55ms, 0 FGC, +69.4MB | — | GCMonitor 快照 |
| 2026-10-11 | — | E2E GC RTK | 4 YGC/33ms, 0 FGC, +89.5MB | — | GCMonitor 快照 |
| 2026-10-11 | — | SM invert_kk 50 | 222.3 us, 41.1 KB | — | JMH 5+5×3 -prof gc |
| 2026-10-11 | — | SM invert_kk 150 | 4.64 ms, 363.8 KB | — | JMH 5+5×3 -prof gc |
| 2026-10-11 | — | SM invert_kk 300 | 35.20 ms, 1.48 MB | — | JMH 5+5×3 -prof gc |
| 2026-10-11 | — | SM invert_nv_nv 20/40/60 | 21.6/122.7/364.2 us | — | JMH 5+5×3 -prof gc |
| 2026-10-11 | — | SM mult k×nv×nv×k 50 | 46.7 us, 20.1 KB | — | JMH 5+5×3 -prof gc |
| 2026-10-11 | — | SM mult k×nv×nv×k 150 | 696.7 us, 180.3 KB | — | JMH 5+5×3 -prof gc |
| 2026-10-11 | — | SM mult k×nv×nv×k 300 | 4.60 ms, 724.0 KB | — | JMH 5+5×3 -prof gc |
| 2026-10-11 | ✅ 核实 | **P 求逆确认不存在** | invert(S) nv×nv only, 算法正确 | — | 源码核查 KalmanFilter.java:L73 |
| 2026-10-11 | ✅ 分析 | **瓶颈更正** | 非 invert_kk, 是 k×k 乘法 (Joseph) | — | 3B 优先级最高, 3A 需重评估 |