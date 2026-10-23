# 项目终期技术摘要

> 版本：**v3.1.0（封存版）** · 日期：2026-10-23
> 完整档案：[ALGORITHM_FIX_LOG.md](ALGORITHM_FIX_LOG.md)（FIX-ALG-001~029） · 版本变更：[CHANGELOG.md](CHANGELOG.md)

## 摘要

**Java 版 RTKLIB 引擎，通过 29 项算法修复，在保证 Fail-Safe 的前提下实现了多系统 PPP-AR、非组合 PPP 和实时 NTRIP 流接入。**

引擎以 RTKLIB 2.5.0 C 源码为唯一数值基准，按 C 文件一一映射类结构，状态向量、协方差存储、常量与边界处理逐项对齐。29 项修复全部遵循同一条纪律：**未开启扩展开关时，结果与 C 版逐值一致**；扩展开关默认关闭，异常时自动降级（AR 失败回退浮点、无 SSR 退化为普通 PPP），不存在"要么固定、要么丢弃历元"的二值风险。

能力边界：SPP / RTK（含长基线、Cascade AR）/ 浮点 PPP / PPP-AR（双频、三频、部分固定）/ PPP-RTK（SSR 路由与解算链路）；产品链覆盖广播星历、精密 SP3/CLK、DCB（BIA/BSX/DCB）、时变 OSB（30 s BIA）、RTCM SSR 与紧凑 SSR（QZSS CLAS）解码。

---

## 表 1　精度实测汇总（浮点 / 固定 / 优化项）

| 层次 | 配置 / 场景 | 数据与产品 | 指标 | 实测结果 | 对照 |
|---|---|---|---|---|---|
| 浮点 | PPP IFLC 双频 | ALGO 2024 DOY060 + WUM | 2D RMS | **1.78 cm** | 与 C 版逐值一致（零回归） |
| 浮点 | PPP 3F 三频 | ALGO 2026 DOY288 + GBM | 2D RMS | 4.30 cm | 3F 浮点自身量级 |
| 固定 | PPP-AR 3F（EWL+WL+NL） | ALGO 2024 DOY060 + WUM | 固定率 / 2D RMS | **65.7%（1226 历元）/ 3.00 cm** | C 版参考 3.54 cm（本实现更优） |
| 固定 | PPP-AR 双频 Partial | 同上 | 固定历元 / E 分量 | 38 次全为真 / 32.9 → **1.56 cm** | 修复前 50 次全为假 Fix（约 2 m 偏差） |
| 固定 | 双频主路径 / FixHold | 同上 | 2D RMS | 14.75 cm | 整周平移自由度受限，安全回退浮点 |
| 跨产品 | PPP-AR 全套 7 配置 | ALGO 2026 DOY288 + GBM | 固定率 | 0%（δ 门拦截） | 证明整周平移为数学普适，非 WUM 数据质量；7/7 零连带回归 |
| 非组合 | EST 六配置 | ALGO 2880 历元 | 存活历元 | 401~509 → **2880/2880** | EST+DCB 4→1537；EST+多系统+DCB 0→1760 |
| 优化项 | GPT3 + VMF3 对流层 | HKWS / HKSL 2018 DOY157 全天 | U RMS | **0.806 m / 1.662 m** | NMF 基线 0.821 / 1.684（−1.9% / −1.3%） |
| 优化项 | ANTEX PCO/PCV + 卫星 PCO | GreatPvt 场景 | PPP 存活率 / dU | 17.3% → **60.8%** / +0.126 → +0.034 m | mm→m 单位转换与 satantoff 补齐 |
| 实时 | NTRIP 2.0 + MSM7 | WUH20 实时流 30 s | 帧完整性 | 74 KB / **262 帧 CRC 全通过** | chunked 与 gzip 解码补全 |
| 实时 | SPP（广播星历） | WUH20 录制流 73 历元 | 3D 偏差 | 1.89 m | 站点 1006 电文参考坐标 |
| 基线 | RTK 事后 | tokyo r1 | Fix 历元 / 成功率 | 0 → **570** / 85.8% → **97.9%** | 卫星 ID 空格填充解析修复 |

> 口径说明：固定解的 2D RMS 均在收敛期后统计；"与 C 逐值一致"指未开启扩展开关的默认路径。

---

## 表 2　Bug 闭环索引（FIX-ALG-001 ~ 029）

| 编号 | 模块 | 问题 → 闭环结果 |
|---|---|---|
| 001 | PppCore | `exc` 排除标志跨历元泄漏 → 入口清零，static solved 1/30→14/30 |
| 002 | RtkCore | 频率槽压缩重排致 IFLC 双差相位污染 → 移除，Fix 率恢复 |
| 003 | SatUtils | `satid2no` 未跳空白（`G 5` 类 ID）→ trim 对齐 C `sscanf`，Fix 0→570 |
| 004 | RinexParser | 槽位仲裁未对齐 C `set_index` → 固定槽位语义，Fix 回到正确基线 |
| 005 | RtkCore | `ddres/udbias/varerr` 阈值与 IFLC 方差未对齐 → 逐项对齐 C |
| 006 | RtkCore | CascadeAR 与 IFLC 冲突 → 互斥守卫，恢复标准 LAMBDA 基线 |
| 007 | CascadeAR | 伪逐级（三次同源 LAMBDA）+ 缺 restamb → 真逐级重写，消除假 Fix 簇 |
| 008 | RtkCore | `relpos` 入口 `azel/freq` 残留 → 清零，修复低高度角误选星 |
| 009 | PppCore | `ppos` 入口 `azel` 残留 → 清零，修复高度角逻辑污染 |
| 010 | RtkCore/PostPos | SPP 丢多系统 → 对齐 `pntpos` navsys 过滤，ISB 有估计值 |
| 011 | PppCore | `udclk_ppp` 缺 C 双分支 → 恢复 + ISB 钟初始化开关（默认关） |
| 012 | EphModel | `satposPrec` 回退广播星历、缺相对论改正 → 对齐 `peph2pos` |
| 013 | Sp3Reader | `pephclk` 丢 SP3 内嵌钟、`pephpos` 缺 std → 补齐 |
| 014 | PcvReader | ANTEX 数值漏乘 1e-3（mm→m）→ PPP 存活率 17.3%→60.8% |
| 015 | Sp3Reader/EphModel | 缺 `satantoff` 卫星 PCO 改正 → 补齐，dU +0.126→+0.034 m |
| 016 | ClkReader | CLK std 字段列偏移 59→60 → 对齐 `readrnxclk` |
| 017 | RinexParser | 星历缓冲不扩容致丢星 → 动态扩容，对齐 `add_eph` |
| 018 | RinexParser | 缺 `decodeNavh`（Klobuchar 恒零）→ PPP 全历元无解，修复后 14/14 |
| 019 | 测试基准 | 参考坐标未做板块运动外推 → 改用同日 IGS20 解，dE −0.365→−0.002 m |
| 020 | DcbReader 等 | EST 死锁 → 移植 `readdcb` + robust 开关，EST 0→401~509 |
| 021 | PppCore | `udbias/udiono` 重置被条件阻塞 → 对齐 C，EST 2880/2880 |
| 022 | PppAmbFix | WL 门限 PRIDE 对齐（OSB 完备性 + maxdev）→ EST+ISB 4→19；Partial 假 Fix 暴露 |
| 023 | PppAmbFix | NL 弧段级机制（minsav + σ/dev 门）→ 假 Fix 50→38 全真，E 32.9→1.56 cm |
| 024 | PppAmbFix | 3F 固定率 0%（LAMBDA 对角退化）→ median 对齐 + 逐星取整，0→65.7% |
| 025 | PppAmbFix | NL 构造公式验证 + EST 分支系数符号修复；弧段均值实验负结果回退 |
| 026 | PppAmbFix | 双频 IFLC 逐星取整 + δ 门 → 三模式复活、0 污染；整周平移自由度定性 |
| 027 | OsbReader | 时变 OSB 按常量读取 → 时间戳序列 + 列偏移自适应（后被 028 部分修正） |
| 028 | OsbReader/PppAmbFix | 3F 67.81 cm 回归三因素（EWL 快照、sh 双令牌、MW 单次施加）→ 3.00 cm |
| 029 | PppOptimizations/Gpt3GridReader | VMF3 缺连续分数分母 + ZHD 单位错配 + bw 错配 → U 向反超 NMF 基线 |

---

## 表 3　未来扩展路线（HAS / IMU / CLAS）

| 方向 | 现状 | 缺口与前置条件 | 接入方式 | 优先级 |
|---|---|---|---|---|
| **QZSS CLAS** | `CompactSsrDecoder` 已实现 MT4073 子类型 1-12 解码；`enableCompactSsr` 开关就位 | 缺公开 L6 二进制样本做端到端冒烟；区域改正覆盖日本及周边 | 解码结果写入 `nav.ssr` 后走 `ppprtkos()`，与 RTCM SSR 同构 | 高（唯一可立即验证） |
| **Galileo HAS** | 未实现，`GalHasReader` 为已登记架构债 | IDD 为专有二进制（需对齐 2.4.3 b34 解码）；互联网 IDD 需注册审批；无公开事后存档 | 新增独立转换层 → `nav.ssr`，复用现有 SsrCorrector | 中 |
| **IMU 紧组合** | 无实现，当前为纯 GNSS | 缺 IMU 数据与标定；EKF 状态需扩姿态/速度/零偏，动力学模型改动最大 | 扩展 `PppCore` 状态向量与过程噪声，天线杆臂纳入观测模型 | 低 |
| **SSR 权限接入**（前置） | CAS 观测流可用，SSRA*/SSRC* 未授权；北航 2025 数据集待申请 | 账号权限与凭据（详见 [ROADMAP.md](ROADMAP.md) §8.8） | 仅需改 Caster 地址 + `enablePppRtk = true` | 中 |

---

## 封存说明

本版为**代码封存版**：算法能力冻结，仅接受 bug 修复，不再新增特性。SSR 数据源权限到位后，接入成本被压缩到两处改动——

```java
// 1. 改 Caster 地址与挂载点
NtripClient client = new NtripClient(host, port, mountpoint, username, password, listener);

// 2. 打开 PPP-RTK 路由
cfg.enablePppRtk = true;
```

底层链路（RTCM/SSR 解码 → `nav.ssr` → SsrCorrector → ppprtkos）已全程贯通并有离线单测覆盖，无需再改算法代码。
