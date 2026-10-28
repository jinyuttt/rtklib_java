# RTKLIB-Java 开发路线图（v3.3.0 归档）

> **状态**: 历史归档，冻结于 2026-10-28（v3.3.0）。内容不再更新，章节编号与锚点保持原样以兼容旧引用。
>
> 当前路线图见 [../ROADMAP.md](../ROADMAP.md)。本文档保留 v1.0~v2.1 的完整梯队执行记录、
> 已关闭/已放弃项的决策依据（JMH 基准、城市峡谷实测、thresar[1] 发现等），供追溯使用。

> 本文档定义项目后续开发优先级、依赖关系和暂缓项。基础功能已完善，路线图集中在运行时性能、工程质量、研究深度扩展三个维度。

---

## 0. 各模块当前状态总览

| 模块 | 定位 | 状态说明 |
|------|------|----------|
| rtklib-core | 核心定位算法（SPP/RTK/PPP/PPP-RTK）+ 自适应引擎 | 四大定位模式全实现，高级优化已接齐（CascadeAR/PartialAR/GPT3+VMF3/IERS2010等）。RAIM FDE 已移植（`PntPos.raimFde()`）。自适应引擎框架已实现（14类）。剩余缺口（完好性监测/保护级/ARAIM、PPP-RTK区域大气约束）均为 WONTFIX——无上游场景驱动，不再投入 |
| rtklib-product | 精密产品下载 | IGS/MGEX 11类产品全支持，多镜像源回退，可按需扩展其他中心 |
| rtklib-stream | NTRIP/串口 | NTRIP 客户端 + SSL/TLS + **NTRIP Caster（服务端）** + GGA 解析 + 串口 + 统一监听器；缺：TCP 客户端（无生产驱动） |
| rtklib-adjust | 多基线间接平差 | 核心流程+基站诊断+P0~P3优化；可按需扩展多时段累积平差 |
| rtklib-vrs | 单基站虚拟参考站 | **临时激活**（GNSS 领域按需启用，不纳入梯队规划）：真实双站 RTCM3 239 历元验证，VRS+测站 FIX 76.6% vs 原始基站 77.0% |
| rtklib-joint | 多基线联合 RTK | **临时激活**（GNSS 领域按需启用，不纳入梯队规划）：k 条基线共享同一 Kalman 滤波器的 Rover 坐标；真实 2 基站+1 测站验证，双基线 FIX 91.5% 与单基线 91.2% 持平 |
| rtklib-otl | 海潮负荷BLQ | Fortran对比验证通过；属成熟稳定辅助模块 |
| rtklib-research | 前沿研究（FGO/IACE/预积分） | FGO框架+IMU预积分+场景自适应已齐；缺：完好性保护级、PPP-FGO |
| rtklib-test | 测试/演示 | SPP/RTK城市峡谷优化对比测试(DynamicRtkOptTest 12变体+UrbanCanyonSppOptTest)+NtripClient实时演示 |

> 状态说明中的缺口统计**不含 RTKLIB C 版中明确不实现的功能**（TCP客户端、接收机原始协议等）。NTRIP Caster 已于 3.3.0 实现（服务端监听/订阅/鉴权/GGA 上行），与 RTKLIB C 版「无 Caster」的定位差异不再适用。

---

## 1. 路线图阶段概览

按优先级和依赖关系分为四个梯队 + 暂缓项。梯队内按执行顺序排列，梯队间有前置依赖。

### 1.1 梯队汇总

| 梯队 | 状态 | 核心原则 | 交付物 |
|------|------|----------|--------|
| **0** 性能基准 | 🟢✅ 已完成 | 无基准不优化 | JMH微基准 + 端到端计时 + GC监控基线 |
| **1** 立刻做 | 🟢✅ 已完成 | 收益确定、难度低、不改变算法语义 | 3B数组复用 + 3C算术优化 + 3.5A日志 + 3.5C异常 + 3.5D对流层接入 + 3.5F测试 |
| **2** 需前置验证 | 🔴 已关闭 | 收益大但需数据/语义支撑 → JMH基准实测放弃 | 3A Kalman优化 (放弃) + 3.5B null消除 (放弃) |
| **2.5** 城市峡谷优化 | 🟡 部分落地（已冻结） | 仅 UC-1 已落地，其余随封存冻结 | 新息卡方检验（`enableSppInnovChisq`） |
| **3** 依赖外部条件 | 🔴 WONTFIX | 代码基础好但缺数据/场景 | CI回归 + 场景验证 + CLAS多项式 + 约束引擎 |
| **—** 暂不建议投入 | 🔴 WONTFIX | 无上游场景驱动，定版不投入 | 完好性(4A~D) + 长期研究 |

### 1.2 梯队依赖与执行顺序

```
梯队0 (基准建立)
  │
  ▼
梯队1 (立刻做，按行内顺序)
  3C → 3.5A → 3B-1 → 3.5C → 3.5D → 3.5F
                 │                           │
                 └─ RtkCore/PppCore 工作数组  └─ 回归验证覆盖
  │
  ▼
梯队2 (需前置验证)
  ├── 3A+3B-2 前置: multiply_kk_kk 基准 + 乘法路径 SimpleMatrix 开销 > 5%
  ├── 3.5B    前置: 语义审查区分 "未配置" vs "默认配置"
  └── 每项完成后做基准对比
  │
  ▼
梯队3 (依赖外部条件)
  ├── 3.5G CI回归 ── 依赖: 测试数据入库
  ├── 场景验证 ── 依赖: 社区贡献数据
  ├── 4E CLAS多项式 ── 依赖: LocalCorr结构(已就绪) + 3.5D验证
  ├── 4F 对流层SSR验证 ── 依赖: 3.5D + 真实SSR数据
  └── 4G 伪观测约束引擎 ── 依赖: 4E + 4F

暂缓项 (无上游场景驱动，等恢复条件触发):
  4A/B 完好性基础 → 4C/D ARAIM+固定解完好性 → Phase 5 长期研究

注: rtklib-research 独立演进，不参与上述依赖链；rtklib-vrs / rtklib-joint 为 GNSS 领域临时激活的功能，同样不参与梯队依赖链。

> **v3.1.0 封存策略**: 项目于 2026-10-23 封存（tag `v3.1.0`）。3.1.0 起仅接受 bugfix，不再新增算法特性。
> 梯队2 (3A/3.5B) 已确认放弃，梯队3 及暂缓项全部标记 WONTFIX。
> 恢复条件详见 §9。
```

---

## 2. Phase 1 — 基础能力建设 ✅

### 2.1 完成清单

- RTKLIB C 版核心算法移植：SPP / RTK / PPP / PPP-RTK 四种定位模式
- LAMBDA 模糊度解算 + Fix-and-Hold
- RTCM3 解码（MT1001-1004 / MSM / SSR MT1057-1068）
- RINEX 3.x 读写
- Kalman 滤波框架
- 坐标变换 / 时间系统 / 电离层 / 对流层模型
- 精密产品下载（rtklib-product）
- NTRIP 客户端（rtklib-stream）
- NTRIP Caster 服务端（rtklib-stream，3.3.0）
- 轨道模块（TLE/SGP4/SDP4）
- 海潮负荷 OTL（rtklib-otl）

### 2.2 分模块回顾

| 模块/能力 | 说明 |
|-----------|------|
| SPP 单点定位 | Kalman 框架 + pntpos 重写 |
| RTK 相对定位 | rtkpos 核心 + LAMBDA Fix-and-Hold |
| PPP 精密单点定位 | PPP 动态/静态/精密产品读取 |
| PPP-RTK 基础（SSR 改正） | SSR 解码 + 状态空间表示 |
| RTCM3 解码（MT1001-1068） | MSM + SSR + 观测值/星历 |
| RINEX 3.x 读写 | 观测/导航/气象/头文件 |
| Kalman + 矩阵运算 + 时间系统 | 核心基础设施 |
| 星历 + 坐标变换 + 电离层对流层 | GPS/GAL/BDS/QZS 广播星历 |
| 精密产品下载（rtklib-product） | 11 类产品 + 多镜像源 |
| NTRIP 客户端（rtklib-stream） | 含 SSL/TLS + 自动重连 |
| NTRIP Caster 服务端（rtklib-stream） | Sourcetable + 挂载点订阅 + Basic 鉴权 + GGA 上行；异步队列按会话隔离 |
| GGA 解析（rtklib-stream） | 任意 talker ID、校验和、正高→椭球高换算 |
| 轨道模块（TLE/SGP4/SDP4） | Vallado 14 用例验证 |
| 海潮负荷 OTL（rtklib-otl） | Fortran 对比验证 |
| Trace 日志 + SolData 扩展 | 分散在各模块 |

---

## 3. Phase 2 — 高级优化与独立模块 🟡

> **状态**: 基本完成，剩余项非阻塞

### 3.1 分模块回顾

| 模块/能力 | 说明 |
|-----------|------|
| CascadeAR 逐级模糊度固定 | EWL→WL→NL 约束链 |
| Partial AR 部分模糊度固定 | GREAT-PVT 多因子加权 |
| ResidualEdit + Bootstrapping | PRIDE 残差编辑 |
| BDS Wanninger 码偏差 + 参数噪声 | GEO/IGSO/MEO 码偏差 |
| PPP-AR + Fix-and-Hold + PartialAR | 整周固定 + 固定后约束 |
| GPT3 + VMF3 + IERS2010 | 对流层先验 + 潮汐改正 |
| Compact SSR / CLAS / HAS 解码 | CSSRlib 交叉验证 |
| SPP EKF + 抗差 + 零速约束 | |
| 多基线间接平差 | 高斯-马尔可夫 + Baarda |
| FGO 框架 + IMU 预积分 | 滑动窗口 + 边缘化 + SwitchVariable |
| IACE 聚类 + 场景自适应 | FE-GUT 数据集验证 |

### 3.2 SPP 高级优化 ✅

> 参考 MobileGNSS-SPP，全部经 `SppOptimizationTest` 逐项对照实测。开关位于 `RtkConfig.spp`（`SppOpts`），除新息检验外默认关闭。

| 优化项 | 开关 | 默认 | 作用 |
|--------|------|------|------|
| EKF 时间传播 | `spp.enableEkf` | false | 位置/速度/加速度 + 钟差状态，历元间平滑；**其余四项的前提** |
| M-估计抗差 | `spp.enableRobust` | false | IGGIII 抗差调整观测方差，抑制 NLOS/多路径 |
| 零速约束 | `spp.enableZeroVel` | false | 零速检测后压制位置方差；动态场景必须关闭 |
| 多普勒+SNR 加权 | `spp.enableDopplerSnr` | false | 多普勒观测方程 + SNR 自适应权 |
| 新息卡方检验 | `spp.enableInnovChisq` | **true** | 新息超 5σ 剔除、超 3σ 降权（低卫星数放宽至 7σ/5σ） |

内建保护（无独立开关）：P 膨胀检测与对角线封顶、diverged 时 R/Q 自适应放大与恢复。

调用链见 [SPP 额外优化文档](SPP_Extra_Optimizations.md)；城市峡谷场景的未落地项见 §7。

### 3.3 RTK 高级模糊度固定优化 ✅

| 优化项 | 开关 | 来源 |
|--------|------|------|
| 逐级模糊度固定 (EWL→WL→NL) | `enableCascadeAR` | GREAT-PVT |
| 精细化残差编辑与周跳检测 | `enableResidualEdit` | PRIDE |
| 部分模糊度固定 (Partial AR) | `enablePartialAR` | GREAT-PVT |
| Bootstrapping 成功率联合判据 | `enableBootstrapping` | GREAT-PVT |
| BDS 码偏差改正 (Wanninger) | `enableBdsCodeBias` | PRIDE |

### 3.4 PPP/PPP-RTK 高级优化 ✅

| 优化项 | 开关 |
|--------|------|
| PPP 模糊度固定 (PPP-AR) | `enablePppAR` |
| PPP Fix-and-Hold | `enablePppArFixHold` |
| PPP 部分模糊度固定 | `enablePppPartialAR` |
| GPT3 + VMF3 对流层 | `enableGpt3Vmf3` |
| IERS2010 潮汐改正 | `enableIers2010` |
| PPP-RTK 基础（SSR改正） | `enablePppRtk` |
| Compact SSR / CLAS 解码 | — |
| Galileo HAS SSR 解码 | — |

### 3.5 RTKLIB C 差异消除（已关闭，定版不再投入）

| 差异 | 说明 | 状态 |
|------|------|------|
| **pos1-posopt5 RAIM FDE** | C 版 `pntpos.c raim_fde()` → Java `PntPos.raimFde()`，逐卫星剔除故障检测排除 | ✅ 已移植 |
| **ssat_t / 完好性辅助** | `ssat.ura` 已部分迁入 `Ssr.ura`，但 `ssat.h` 其他字段未移植 | 🔴 WONTFIX |
| **SPP RAIM 保护级** | C 版 RAIM 只做 FDE 不输出 PL，Java 版也没有 | 🔴 WONTFIX |

---

## 4. 梯队0 — 性能基准建立 🟢

> **编号对照**: 原 3.5E | **状态**: 最前置 | **难度**: 低
>
> 核心原则：**无基准不优化**。所有梯队1/2的优化效果必须有量化数据支撑，否则无法客观证明收益。

### 4.1 基准内容

| 基准维度 | 测量目标 | 工具 |
|----------|----------|------|
| JMH 微基准 | KalmanFilter.update() 单次耗时、矩阵运算热点 | JMH 1.36 |
| 端到端计时 | PPP 单历元耗时、RTK relpos() 耗时 | `System.nanoTime()` 打点 |
| GC 监控 | FullGC 频率、YoungGC 次数/停顿 | `-Xlog:gc*` 或 jstat |
| 内存分配 | 每个历元 `new double[]` 累计大小 | async-profiler / JFR |

### 4.2 基准数据与执行要求

- **测试数据**: 精选 1h 静态 RINEX + 1h 车载 RINEX，存入 `test-data/` 作为基线数据集
- **环境固定**: JDK 版本、JVM 参数（`-Xmx -Xms -XX:+UseG1GC`）
- **结果存档**: 每个基准结果写入 `benchmarks/baseline.md`，变异系数 (CV) < 5% 才可用
- **持续对比**: 梯队1和梯队2每项完成后，用相同基准数据做前后对比，更新 `benchmarks/` 目录

---

## 5. 梯队1 — 立刻做 🟢✅ 已完成

> 核心原则：**收益确定、难度低、不改变算法语义**。不依赖 profiling、不依赖外部数据、不会引入精度回退风险。
>
> **完成时间**: 2026-10-12。全部 6 项执行完毕；3B-2 与 3A 同路径，经 JMH 基准实测确认放弃（详见 §6.2）。

### 5.1 完成清单

| 编号 | 内容 | 状态 | 结果 |
|------|------|------|------|
| 3C | `Math.pow(x,2)` 改为 `x*x` | ✅ 完成 | 5 文件 18 处替换；`static final` 常量已由编译器折叠，无需改动 |
| 3.5A | `System.out/err` 改为 SLF4J | ✅ 完成 | 7 文件日志统一可配置；CLI 用法输出保留 |
| 3B-1 | RtkCore/PppCore 工作数组预分配复用 | ✅ 完成 | 新增 RtkWorkBuffer/PppWorkBuffer，约 22 处数组复用，消除每历元临时数组分配 |
| 3.5C | 宽泛异常捕获改进 | ✅ 完成 | 13 处补充日志（10 文件）；Trace 回调按设计保持静默 |
| 3.5D | SSR 对流层直接改正接入 | ✅ 完成 | `TROPOPT_SSR` 分支接入 CSSR 对流层；三重守卫保证无 SSR 产品时行为不变 |
| 3.5F | 关键算法单元测试补充 | ✅ 完成 | 34 用例 7 类，覆盖梯队1全部改动路径 |
| 3B-2 | KalmanFilter 中间 SimpleMatrix 对象复用 | ❌ 放弃 | 与 3A 同路径合并；JMH 实测 SimpleMatrix 非瓶颈，连带放弃 |

---

## 6. 梯队2 — 需前置验证 � 已关闭

> 核心原则：**收益大但需要数据/语义支撑**，必须先完成前置验证再决定是否执行及方案选择。

### 6.1 执行前提

| 编号 | 优化项 | 前置验证 | 不通过则 |
|------|--------|----------|----------|
| 3A+3B-2 | Kalman SimpleMatrix → DMatrixRMaj + 中间对象复用 | `multiply_kk_kk` JMH 基准: SimpleMatrix 不慢，k≥150 时快 10x (EJML优化块乘法)。结论：放弃。 | ✅ 已决：不执行 |
| 3.5B | RtkConfig null检查消除 | 语义审查区分 "未配置" vs "默认配置" | 保留 null 检查，加注释说明语义 |

### 6.2 3A+3B-2 — Kalman滤波器 SimpleMatrix → DMatrixRMaj + 中间对象复用 ❌ 已放弃

> **编号合并**: 原 3A (SimpleMatrix→DMatrixRMaj) 与 3B-2 (KalmanFilter 中间对象复用)
> 共享同一条技术路径（保留 DMatrixRMaj 引用 → 复用 → 去掉 SimpleMatrix），统一规划避免拆分回退。
> **前置验证结果 (2026-10-12)**: 执行 `multiply_kk_kk` JMH 基准 (k=50/150/300, SimpleMatrix vs 直接 double[] 三重循环)，
> 结论：**SimpleMatrix 计算速度不是瓶颈，不执行 3A**：
> - k=50: SimpleMatrix 125,905 ns vs 直接 122,527 ns — 几乎相同 (1.0x)
> - k=150: SimpleMatrix 3,004,454 ns vs 直接 3,200,958 ns — SimpleMatrix **更快** (0.9x)
> - k=300: SimpleMatrix 2,214,268 ns vs 直接 24,522,387 ns — SimpleMatrix **快 10x** (0.1x)
>
> EJML 的 `CommonOps_DDRM.mult()` 使用优化块乘法 + SIMD，k≥150 时碾压手写三重循环。
> 真正开销是**分配**而非**计算**：每次 update() 创建 21 SimpleMatrix + 10 DMatrixRMaj (106~2875 KB/call)。
> 但 3B-1 (已完成) 已将工作数组预分配，Kalman 层分配在 RTK 1Hz (106 MB/s) 下可接受。
> 如果后续 Profiling 显示 SimpleMatrix 分配占 >5% 总耗时，再启动 DMatrixRMaj 复用优化。

**优化方案**（验证通过后执行）:

| 方案 | 说明 | 适用条件                               |
|------|------|------------------------------------|
| **方案一**: 改用 EJML `DMatrixRMaj` | 去掉 SimpleMatrix 封装层，零拷贝 wrap `double[]`，保留 EJML 线性代数能力 | 通用                                 |
| **方案二**: 中小规模矩阵手写 | k < 50 时手写 Kalman（矩阵乘、求逆、Joseph），k ≥ 50 回退 EJML | ⛔ 不可取：手写矩阵乘/求逆维护成本高，且 EJML 的底层实现（LU 分解、缓存优化、数值稳定性）优于手写。项目规定：矩阵运算统一使用 EJML，不手写 |

**关键约束**: Joseph 形式协方差更新 `P = (I-KH)P(I-KH)ᵀ + KRKᵀ` 必须保留，这是保证协方差正半定的核心（C版标准形式 `P=(I-KH)P` 在病态条件下差异可达厘米级）。

```
3A 实现细节（方案一）:
├── KalmanFilter.update() 内部:
│   ├── double[] → DMatrixRMaj（零拷贝 wrap，不 new 对象）
│   ├── 矩阵运算: CommonOps_DDRM.mult / multTransB / invert / add / subtract
│   ├── Joseph更新: 保留 (I-KH)P(I-KH)ᵀ + KRKᵀ 结构
│   └── 结果写回 double[] x, P
├── MatrixUtil.java:
│   ├── 新增 wrapDMatrix(data, rows, cols) 零拷贝包装
│   └── 保留现有 SimpleMatrix 接口供非热路径使用
└── 验证: KalmanFilter 单元测试 + 梯队0基准对比 + RTK/PPP端到端精度不变
```

### 6.3 3.5B — RtkConfig null检查消除

**前置验证必要性**: 需要确认 `rtk.rtkConfig == null` 在某些路径中是否确实表示"未配置"语义（而非可以默认初始化的"默认配置"）。

**验证方法**: 全局搜索所有 `rtkConfig` 访问点，区分三类场景：

| 场景 | 语义 | 处理方式 |
|------|------|----------|
| 初始化阶段 `rtkConfig == null` | "尚未配置" | 在 `Rtk` 构造中初始化为 `new RtkConfig()`（默认false） |
| 运行时某路径 `rtkConfig == null` | "故意不传配置" | 保留 null 检查，加注释说明 `// rtkConfig null means XXXX` |
| 测试代码 `rtkConfig == null` | 测试简化 | 允许测试代码设 null，但生产路径确保非 null |

**优化**（语义审查通过后）:

```java
// Rtk 构造器
public Rtk() {
    this.rtkConfig = new RtkConfig(); // 永不为 null，所有开关默认 false
}
// 调用点：直接访问，无需 null 检查
if (rtk.rtkConfig.enableIers2010) { ... }
```

---

## 7. 梯队2.5 — 城市峡谷 SPP/RTK 优化 🟡 部分落地（已冻结）

> **状态（v3.1.0 封存时）**: 仅 UC-1 新息卡方检验已落地（`enableSppInnovChisq`，默认开启）；UC-2 自适应过程噪声、UC-3 EKF warm-up 未实施，UC-4/UC-5 零速门限未采纳（默认值仍为 0.5 m/s / 1 历元），UC-6~9 未启动。随封存冻结，恢复条件见 §10。  
> **前置条件**: EKF 发散 Bug 已修复（`udposSpp` 方差重置条件拆分 + `estposFilter` diverged 状态跳过 KF update）  
> **测试基准**: `archive/URBAN_CANYON_SPP_TEST_RESULTS.md`（历史实测，早于 FIX-ALG-010）  
> **测试类**: `org.rtklib.java.urbancanyon.UrbanCanyonSppOptTest`  
> **测试数据**: Net_Diff Urban / PPC Tokyo / PPC Nagoya / UrbanNav Tokyo

### 7.0.1 当前 SPP 基准（Bug修复后预期）

| 数据集 | 平均卫星 | LS-SPP成功率 | EKF+抗差 2D RMS | 核心限制 |
|--------|---------|-------------|----------------|---------|
| Net_Diff Urban | 4.6 | 55.2% | ~840m（≈LS，不再发散） | 单频+Galileo不兼容+极严重遮挡 |
| PPC Tokyo run1 | 15.1 | 87.5% | 7.8km | 初始化偏差→EKF收敛慢 |
| PPC Nagoya run1 | 13.1 | 99.0% | 553m | 基本正常 |
| UrbanNav Tokyo | 6.6 | 82.4% | 699m | 成功率提升显著 |

### 7.0.2 SPP 算法改进

| # | 优化项 | 修改文件 | 说明 | 工作量 | 优先级 |
|---|--------|---------|------|--------|--------|
| UC-1 | 新息卡方检验 | `SppOptimizations`, `RtkConfig` | KF update前计算 `vᵀS⁻¹v`，超 `sppEkfInnovGate²×nv` 门限时跳过本历元update；防止多路径大残差污染状态 | 1天 | 高 |
| UC-2 | 自适应过程噪声 | `SppOptimizations`, `RtkConfig` | `accPrn = accPrnMin + (accPrn1-accPrnMin)×min(ns/8,1)`；卫星少时Q小→P膨胀慢，卫星多时Q大→更信任观测 | 0.5天 | 中 |
| UC-3 | EKF 初始化 warm-up | `SppOptimizations`, `RtkConfig` | 前 N 个历元用 LS 解输出不更新 EKF；warmup 结束后用 LS 均值重新初始化 EKF | 1天 | 低 |

### 7.0.3 SPP 参数调优

| # | 调优项 | 当前值 | 建议值 | 说明 | 工作量 |
|---|--------|--------|--------|------|--------|
| UC-4 | `sppZeroVelSpeedThresh` | 0.5 m/s | 2.0 m/s | 车载动态等红灯时 EKF 速度噪声>0.5m/s→误判零速→PPC Tokyo 2D RMS 7.8→15km | 0.5天 |
| UC-5 | `sppZeroVelMinEpochs` | 1 | 5 | 连续5个历元(1s@5Hz)才触发零速约束 | 含在UC-4 |

### 7.0.4 RTK 城市峡谷优化（长期）

| # | 方向 | 说明 | 前置 |
|---|------|------|------|
| UC-6 | 部分模糊度固定（PAR） | 城市峡谷全固定困难，先固定高置信度子集 | UC-1~2 |
| UC-7 | 降低 AR ratio 门限 | 当前 ratio>3.0 太保守，城市峡谷可尝试 2.0+验证 | UC-6 |
| UC-8 | 多历元 AR | 累积多历元浮点解再尝试固定，提高 ratio | UC-6 |
| UC-9 | 短基线电离层强约束 | 基线<1km 时强约束双频电离层残差 | UC-1 |

### 7.0.5 执行顺序与验证

```
UC-1 (新息检验) → UC-2 (自适应Q) → UC-4+5 (零速调优) → UC-3 (warm-up) → UC-6~9 (RTK)
```

每项完成后运行：
```bash
mvn test -pl rtklib-core -Dtest="UrbanCanyonSppOptTest"
```
对比 `archive/URBAN_CANYON_SPP_TEST_RESULTS.md` 历史基准，确认改善方向正确、无回归。

### 7.0.6 RTK 城市峡谷优化测试（DynamicRtkOptTest）

> **测试类**: `org.rtklib.java.urbancanyon.DynamicRtkOptTest`  
> **测试数据**: Net_Diff Urban / PPC Tokyo (run1~3) / PPC Nagoya (run1~3) / UrbanNav Tokyo 2018  
> **日期**: 2026-10-13

#### 关键发现：thresar[1] 是 Fix 率的决定性参数

RTKLIB C 版（demo5 EX 2.5.0）默认 `thresar[1] = 0.9999`（最大位置方差阈值 m²），而 RTKLIB 2.5.0 标准版默认 `0.25`。Java 版此前沿用 `0.1`，导致 `manage_amb()` 中 AR 前置检查 `posvar < thresar[1]` 几乎永不满足 → **Fix 率 0%**。

将 `thresar[1]` 从 0.1 改为 0.9999 后，Fix 率从 0% 跃升至当前水平（PPC Nagoya run1 达 19.8%）。**这是 Fix 率提升的真正原因**，而非 Joseph 形式协方差更新。

#### Joseph 形式等价性验证

全量测试（8 个数据集）确认：**Joseph 形式与标准形式在本场景下数学等价**，两者结果完全一致。这与理论一致——当 K 为最优增益时，Joseph 形式是标准形式的数值稳定超集，差异仅在病态条件下显现。

#### C-EX-Ref 全量测试结果（thresar[1]=0.9999）

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

#### 优化变体定义（12 个配置）

| 变体 | 配置要点 | thresar[1] | RtkConfig 开关 |
|------|---------|------------|----------------|
| A-Default | Java默认(dynamics=0) | 0.25 | — |
| B-Dyn1 | dynamics=1, 默认prn | 0.25 | — |
| C-EX-Ref | 对齐RTKLIB-EX配置 | **0.9999** | enableIflcPairNegotiation |
| D-LooseAR | 宽松AR | 1.0 | enableIflcPairNegotiation |
| E-NoDyn | dynamics=0, EX参数 | 0.9999 | enableIflcPairNegotiation |
| F-AdapQ | EX-Ref + 自适应Q | 0.9999 | +enableAdaptiveQ |
| G-IGGIII | EX-Ref + 抗差IGGIII | 0.9999 | +enableIggiii |
| H-ResEdit | EX-Ref + 残差编辑 | 0.9999 | +enableResidualEdit |
| I-PartAR | EX-Ref + 部分模糊度固定 | 0.9999 | +enablePartialAR |
| J-CascadeAR | EX-Ref + 级联AR | 0.9999 | +enableCascadeAR |
| K-SnrMed | EX-Ref + SNR中值定权 | 0.9999 | +enableSnrMedian |
| L-AllOpt | EX-Ref + 全部RTK优化 | 0.9999 | 全部开关开启 |

> **注**: E~L 变体的 `thresar[1]` 须从 0.1 更新为 0.9999（与 C 版默认值一致），否则 AR 前置检查不通过，Fix 率退化为 0%。

#### 执行命令

```bash
mvn test -pl rtklib-core -Dtest="DynamicRtkOptTest#testPpcTokyoRun1"
```

---

## 8. 梯队3 — 依赖外部条件 � WONTFIX（已关闭）

> 核心原则：**代码基础好但缺数据/场景**。v3.1.0 封存后本梯队整体 WONTFIX，不再投入；下列内容仅作为条件具备时的恢复参考。

### 8.1 项目优先级矩阵

| # | 优化项 | 预期收益 | 缺的外部条件 | 代码基础 |
|---|--------|----------|-------------|----------|
| 3.5G | CI 真实数据回归测试 | 持续集成保障 | 测试数据入库 | CI 框架已有 |
| — | 场景验证 | 真实场景置信度 | 社区贡献数据（车载RTK/长基线/城市峡谷/多频PPP） | 测试脚本已有 |
| 4E | CLAS STEC多项式路径打通 | PPP-RTK区域增强 | LocalCorr结构已就绪(100%) | `SsrIono.stecModel()` 优先级链已有 |
| 4F | 对流层SSR改正验证 | 量化直接改正vs估计差距 | 梯队1 3.5D完成 + 真实SSR数据 | `getTrop()` 已实现 |
| 4G | 伪观测值约束引擎 | 收敛加速（论文: 东向85%/高程69%） | 4E + 4F + 区域STEC/ZTD产品 | 数据结构待设计 |

### 8.2 3.5G — CI 真实数据回归测试

**依赖**: 精选测试数据（1h静态RINEX + 1h车载RINEX）入库 `test-data/`。

**目标**: 每次 commit 自动运行端到端定位，对比参考解，精度变化超过阈值则告警。

### 8.3 场景验证（🤝 社区贡献项）

> 以下任务的核心依赖是**外部测试数据**。欢迎社区贡献数据或测试结果。

| 任务 | 需要的数据 | 贡献方式 |
|------|-----------|----------|
| 动态车载 RTK 验证 | 高速动态 base+rover 对（>50km/h） | 上传数据至 `test-data/` 或提交 Issue |
| 长基线 RTK (>10km) | 长基线 base+rover | ⚠️ **数据已入库**（HKWS-HKSL，~42km，2024 DOY060），`RtkValidationTest` 已做链路级验证（FIX 率 1.3%，未达精度验证）；**仍需 ITRF 真值参考坐标**做精度评估与长基线 AR 专项调优 |
| 城市峡谷/深城市 RTK | 高遮挡 base+rover | 同上 |
| 多星座多频 PPP 验证 | 三频 BDS/GAL 数据 + IGS 参考解 | 同上 |
| 长时段静态连续运行 | 24h/72h RINEX 数据 | 同上 |
| PPP-RTK 实时流验证 | IGS SSR 流 + CLAS L6 流 | 同上 |
| 边缘场景回归 | 低仰角/高遮挡/单系统/单频/电离层活跃 | 同上 |

### 8.4 4E — CLAS STEC多项式路径打通

**现状**: `LocalCorr` 数据结构 100% 就绪（`stecQuality`, `ci[]`, `dstec`），`SsrIono.stecModel()` 中优先级链已有（SSR dispBias → IONEX → 广播），仅差 LocalCorr 分支。

```
4E 实现细节:
├── SsrIono.stecModel() 新增 LocalCorr 分支:
│   ├── 插值公式: STEC = ci[0] + ci[1]×dlat + ci[2]×dlon + ci[3]×dlat×dlon + dstec
│   ├── 质量控制: stecQuality 低时 fallback
│   └── 优先级链: SSR dispBias → LocalCorr → IONEX VTEC → 广播模型
└── 依赖: 梯队1 3.5D 接入后验证 LocalCorr + SSR对流层联合效果
```

### 8.5 4F — 对流层SSR改正验证

**依赖**: 梯队1 3.5D 完成 `getTrop()` 接入 + 真实SSR对流层数据（IGS SSR流或CLAS L6流）。

**验证内容**: 对比 `TROPOPT_SSR` 直接改正 vs `udtrop()` 估计 ZTD 的收敛时间/稳态精度。

### 8.6 4G — 伪观测值约束引擎

**依赖**: 4E + 4F + 区域STEC/ZTD产品可用。

**价值**: 论文报告东方向收敛改善 85%、高程改善 69%，是 Phase 4 里最有论文价值的部分。

```
4G 实现细节:
├── ConstraintEngine.java（org.rtklib.java.ppprtk 包下）
├── STEC 约束: 区域电离层产品 → 伪观测方程 → 约束斜距电离层状态量
├── ZTD 约束: 区域对流层产品 → 伪观测方程 → 约束 ZTD 状态量
├── 距离衰减权重: w(dist) = 1 / (1 + (dist/d0)²), d0=50~100km
└── 配置: prcopt.constrainstec / prcopt.constrainztd
```

#### 技术储备（可提前做）

- [ ] 整理 CLAS L6 规范中 STEC/对流层多项式定义
- [ ] 调研可用的区域 STEC/ZTD 产品源（IGS 实时电离层产品、区域监测站网）
- [ ] LocalCorr 结构补全：当前字段已齐，可写 GridDefinition + 双线性插值器
- [ ] 参考 CSSRlib `zdres()` 中 SSR STEC/ZTD 约束实现（Python）

### 8.7 rtklib-research 定位说明

rtklib-research 是**独立的研究沙盒模块**，与 rtklib-core 无代码级耦合，定位为前沿算法的实验场所（FGO/IACE/预积分/场景自适应等）。其价值在于提供独立验证途径，成熟后可按需收录到核心模块。**不要求与 rtklib-core 做数据桥接**，两者各自独立演进。

---

### 8.8 SSR 数据源接入（✅ 已闭环，2026-10-28）

> 状态：**已完成**。CAS `SSRA01CAS0` / `SSRC01CAS0` SSR 挂载点可用，已打通
> 「实时流录制 → 离线回放 → `nav.ssr[]` → `SsrCorrector` → `ppprtkos()`」闭环：
> 30 s 录制 CRC 全通过，120 s 回放 `total=119 success=119 fail=0`（详见
> [ALGORITHM_FIX_LOG.md](ALGORITHM_FIX_LOG.md) FIX-ALG-035/036）。
> 落盘样本：`data/ssr_SSRA01CAS0.rtcm3`（201 帧，全部为 SSR 电文）。

| 来源 | 状态 | 说明 |
|------|------|------|
| CAS IGS `SSRA01CAS0` / `SSRC01CAS0` | ✅ 已接入并验证 | 含轨道/钟差/码偏差/相位偏差；录制回放闭环通过 |
| Galileo HAS IDD | 待注册审批 | 无公开事后存档；`HasSsrDecoder` 已实现 COMPACT-SSR（MT1~MT9）解码，缺 IDD 帧同步与互联网接入 |
| BDS PPP-B2b（BDSet） | 公开归档可用 | 无相位偏差，仅支撑浮点 PPP 验证 |
| 北航 2025 全球区域 SSR | 待离线申请 | 元数据未明确含相位偏差/区域大气改正 |
| QZSS CLAS L6 | 公开样本待寻 | 日本区域；`CompactSsrDecoder` 已就绪，缺公开 L6 二进制存档 |

> 接入手册：[SSR_ACCESS_PLAYBOOK.md](SSR_ACCESS_PLAYBOOK.md)（两行改动 + 五步接入 + 验收清单）。

## 9. 暂不建议投入 🔴

> 核心原则：**无上游场景驱动**。暂不建议投入 ≠ 永远不做，等恢复条件触发后再启动。

### 9.1 完好性监测（原 Phase 4A~D）

> RAIM FDE（`PntPos.raimFde()`）已移植。以下均为 **WONTFIX**。

| 子阶段 | 内容 | 暂缓原因 |
|--------|------|----------|
| 4A RAIM 保护级 | HPL/VPL 计算 + HAL/VAL 告警 | 技术可行（RAIM FDE 已就绪），但保护级需风险预算公式 + HAL/VAL 告警阈值，当前无车规/航空/监测场景驱动，暂缓 |
| 4B GNSS 完好性通道 | 多星座完好性通道 (SIS-A/SIS-M) | 依赖 4A，需 Galileo HAS ICD 完好性信息解析 |
| 4C ARAIM 解分离 | 故障假设子集枚举 + 多假设保护级 | 依赖 4B |
| 4D 固定解完好性增强 | 固定解偏差后验估计 + Ratio/Bootstrapping PL | 依赖 4C |

**参考资源**:
- Urban-RTKLIB `src/fde.c`（413 行）、`src/pntpos.c raim_fde()`
- RTK-EVC `src/rtklib/pntpos.cpp`（C++ RAIM）
- Galileo HAS ICD（完好性信息定义 SIS-A/SIS-M）

### 9.2 PPP-AR 深度重构（方案 C）

> 方案 B 已落地（PppArEngine/PppArConfig 抽出，PppCoreEx 委托）。方案 C 为下一步深度重构，待方案 B 验证通过后推进。

| 内容 | 说明 |
|------|------|
| PppArCore 完全独立包 | PppAmbFix + PppAmbFixBds3 移入 `org.rtklib.java.pppar`，所有 AR 逻辑自包含 |
| PppCoreEx 完全瘦身 | 只管扩展状态建模（ISB/IFCB/IFB），AR 和退化检测全部委托 |
| 退化显式化 | OSB 缺失 / SSR 超时 / 产品过期 → 显式 DegradationEvent，而非隐式 fallback |
| 恢复条件 | 方案 B 运行稳定 + PPP-AR 测试覆盖充分 |

### 9.3 长期研究方向（原 Phase 5）

| 方向 | 说明 | 暂缓原因 |
|------|------|----------|
| PP-FGO 融合 | PPP 固定解 + 因子图优化，紧耦合 IMU/视觉 | FGO 框架成熟度不足 + Phase 4 完好性未量化 |
| IMU-PPP 紧耦合 | IMU 预积分 + PPP 状态量融合 | IMU 硬件接入 + 状态量对齐未完成 |
| 学习型后端 | GNN/Transformer 做观测值质量评估 | 方向宽泛，缺训练数据、推理延迟约束 |
| 多路径建模 | 城市峡谷 NLOS 检测与修正 | Phase 4 SwitchVariable 验证有效后才可推进 |
| VRS/CORS 扩展 | 多基站联合改正数生成 | 观测层已由 rtklib-joint 落地、网解层由 rtklib-networkvrs 档 2 落地，档 3（MAC 改正合成）冻结；不再作为研究方向 |

---

## 10. 暂缓原则与恢复条件

### 暂缓原则

以下情况不启动新功能开发：
- 梯队3 外部条件未满足
- 梯队2 前置验证不通过
- 无明确上游应用场景驱动
- 算法验证成本高且短期无数据支撑

### 恢复条件

**梯队2 恢复条件**:
- 3A: ✅ 已通过 JMH 基准验证 — SimpleMatrix 计算不慢 (k≥150 快 10x)，放弃 3A。如需恢复：Profiling 显示 SimpleMatrix 分配占 >5% 总耗时 (当前 1Hz 下 106 MB/s 可接受)
- 3.5B: 语义审查确认无"故意不传配置"路径

**梯队3 恢复条件**:
- 3.5G: 测试数据入库
- 4E/4F: 梯队1 3.5D 完成 + 真实SSR数据可用
- 4G: 4E + 4F + 区域STEC/ZTD产品可用

**完好性/长期研究恢复条件**:
1. 有真实车规/航空/监测场景需要完好性输出（PL + HAL/VAL）
2. 能获取 SSR STEC/ZTD 实时产品或有区域监测站网
3. 梯队1 + 梯队2 完成（性能优化 + 工程质量加固 + 核心模块通过回归验证）

---

## 11. 变更日志

| 版本 | 变更 |
|------|------|
| v1.0 | 初始版本：整合完好性监测、PPP-RTK区域约束、FGO-PPP融合等方向；明确 Phase 1~5 划分；标记暂缓项 |
| v1.1 | rtklib-research 定位为独立研究沙盒；Phase 4/5 子阶段改为表格+实现细节分离 |
| v1.2 | 去掉所有时间线/时间窗口/人天估算，只保留优先级+依赖+交付物 |
| v1.3 | 新增 Phase 3（运行时性能优化）和 Phase 3.5（工程质量加固）；原 Phase 3 场景验证移入 Phase 3.5 |
| v1.4 | **重大重组**: 改为四梯队制（0→1→2→3）；梯队0新增性能基准；3.5E提前到梯队0；3.5D提到梯队1（只差接线）；3A降级到梯队2（需profiling前置）；3.5B降级到梯队2（需语义审查）；3.5F提到梯队1末尾（回归覆盖）；Phase 4A~D 标记暂不建议投入；原Phase 4E~G保留在梯队3；变更日志→§10 |
| v1.5 | **梯队1全部完成**: 3C (Math.pow优化)、3.5A (SLF4J)、3B-1 (工作数组复用)、3.5C (异常日志)、3.5D (SSR对流层)、3.5F (34单元测试)；3A+3B-2 经 JMH 基准实测确认放弃（SimpleMatrix 计算不慢，EJML 优化块乘法 k≥150 快 10x）；标记 §5 完成、§6.2 放弃 |
| v1.6 | **新增梯队2.5 城市峡谷优化**: UC-1~9（新息检验/自适应Q/warm-up/零速调优/RTK PAR等）；前置条件为 EKF 发散 Bug 修复；测试基准 `URBAN_CANYON_SPP_TEST_RESULTS.md` |
| v1.7 | **梯队2.5 RTK 优化测试**: 新增 §7.0.6 DynamicRtkOptTest 12变体对比测试；记录 thresar[1] 关键发现（0.1→0.9999 是 Fix 率从 0% 提升的真正原因）；Joseph 形式等价性全量验证（8 数据集完全一致）；C-EX-Ref 全量结果表；rtklib-test 状态更新为 80% |
| v1.8 | **定版**: 项目进入 2.3.x 稳定维护期，仅接受 bugfix。梯队2/3 全部关闭（3A+3B-2 JMH验证已放弃，3.5B 无语义异常）。RAIM FDE 标注修正：`PntPos.raimFde()` 已实现（§3.5/§8.1）。完好性/ARAIM/区域大气约束全部 WONTFIX。README 同步更新。 |
| v1.9 | **封存 v3.1.0**（2026-10-23）：版本号 2.4.0 → 3.1.0，3.1.0 起仅接受 bugfix。§8 梯队3 状态由 🟠 统一为 🔴 WONTFIX（与 §1.1 对齐）；§7 梯队2.5 标注为"部分落地（已冻结）"（仅 UC-1 落地）；§8.8 Galileo HAS 口径修正为 `HasSsrDecoder` 已实现 COMPACT-SSR（MT1~MT9）解码，缺 IDD 帧同步与互联网接入；新增 [SSR_ACCESS_PLAYBOOK.md](SSR_ACCESS_PLAYBOOK.md) 接入手册与 [PROJECT_FINAL_SUMMARY.md](PROJECT_FINAL_SUMMARY.md) 终期摘要。 |
| v2.0 | **PPP-AR 轻量重构（方案 B）+ 自适应引擎 v3.0 设计**（2026-10-24）：方案 B 落地 — 新增 `org.rtklib.java.pppar` 包（PppArEngine/PppArConfig），PppCoreEx.applyAmbiguityResolution 委托给 PppArEngine，不改算法逻辑；方案 C（深度重构：PppArCore 完全独立包 + PppCoreEx 完全瘦身 + 退化显式化）写入路线图待激活；自适应引擎 v3.0 设计文档 [gnss-adaptive-design-v3.md](gnss-adaptive-design-v3.md) 定稿 |
| v2.1 | **自适应引擎代码实现**（2026-10-24）：新增 `org.rtklib.java.adaptive` 包，14 类实现（AdaptiveController/GnssScene/AdaptiveMode/FeatureMetrics/PresetManager/AdaptiveOverride/AdaptiveAuditLogger/OptimizationMonitor/OptimizationStats/SampleIntervalDetector/AdaptiveListener/AdaptiveEvent/AdaptiveRecommendation/AdaptiveState）；RtkConfig 新增 12 个自适应字段；全量编译通过 |