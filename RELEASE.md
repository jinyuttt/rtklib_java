# Release Notes

> 本文面向**使用者**：说明本次发布带来什么、怎么升级、有什么坑。
> 逐条技术明细见 [docs/CHANGELOG.md](docs/CHANGELOG.md)，数值修正的根因与验证见 [docs/ALGORITHM_FIX_LOG.md](docs/ALGORITHM_FIX_LOG.md)（FIX-ALG-020 ~ 029）。

| 项 | 内容 |
|---|---|
| 发布状态 | 封存版 **v3.1.0**（2026-10-23） |
| 最后更新 | 2026-10-23 |
| 运行环境 | Java 17、Maven 3.8+ |
| 主要依赖 | EJML 0.41、SLF4J、Logback |
| 产物 | `rtklib-core`、`rtklib-stream`、`rtklib-adjust`、`rtklib-product` 四个 jar |

> 注意：`pom.xml` 与各模块版本均为 `3.1.0`，tag 必须与 pom 版本一致（`release.yml` 会校验，不一致直接失败）。
>
> **v3.1.0 为封存版本**：算法内容与 v2.4.0 完全一致（无算法变更），新增 [docs/PROJECT_FINAL_SUMMARY.md](docs/PROJECT_FINAL_SUMMARY.md)（终期技术摘要），仅版本号与文档同步。

---

## 一、本版重点

### 1. PPP-AR 从"不可用"到"可生产"

| 配置 | 修复前 | 修复后 |
|---|---|---|
| 三频 3F（EWL+WL+NL） | 固定率 0% | **65.7%**，ENU RMS E 2.16 / N 2.81 / U 5.08 cm，与浮点基线同量级、无假 Fix |
| 双频 PartialAR | 固定 50 次**全为假 Fix**（~2 m 偏差） | 固定 38 次**全为真**，E 分量 32.9 cm → 1.56 cm |
| 双频主路径 / FixHold | 固定率 0%（ratio≈1.0） | 已恢复（IFLC 对角协方差下改用逐星取整） |

WUM 2024 DOY060 数据上，3F 固定解 2D 精度 **3.00 cm**，优于 C 版 RTKLIB 参考结果的 3.54 cm。

### 2. 非组合 PPP（IONOOPT=EST）从 0 到满解

- 修复 `udbias_ppp` / `udiono_ppp` 超时重置链（对齐 C `ppp.c`）：被剔除卫星的过期偏差不再永久滞留，电离层状态可重建。
- ALGO 2880 历元：EST 六组配置由 401~509 提升到 **2880/2880**；EST+DCB 4→1537，EST+多系统+DCB 0→1760。
- IFLC 基线 2679→2880，无副作用。

### 3. 精密产品支持扩展

- **DCB 产品读取**（`DcbReader`，移植 C `readdcb`）：支持 `.BIA` / `.BSX`（Bias-SINEX OSB/DSB）与 `.DCB`（P1-C1/P2-C2），已接入 RINEX 与 RTCM 两条 PPP 路径。
- **时变 OSB（30 s BIA）**：按时间戳序列取值，不再把时变文件当全天常量覆盖；同时兼容 GBM 与 WUM 两种列布局（含 SVN 列空缺），修复此前 27 颗星的 OSB 被静默丢弃。

### 4. 对流层 GPT3 + VMF3

- 修正映射函数（原实现缺失连续分数分母，等效于未做斜延迟改正）与 ZHD 单位错配（低估 100 倍），并补齐 c 系数谐波与 bw 分段。
- 实测全天 2880 历元：加载官方 `gpt3_5.grd` 后 **HKWS U 0.806 m（基线 0.821）、HKSL 1.662 m（基线 1.684）**，3D 均优于 NMF 基线。
- 网格文件可选；不加载时退回内置简化模型（U 向约 +50%，不建议生产使用）。

### 5. PPP-RTK 路由修复

- `RtkCore.rtkpos()` 事后 PPP 分支此前不含 `enablePppRtk` 判定，`PMODE_PPPRTK_STATIC/KINEMA` 在 RINEX 路径永远走不到 `ppprtkos()`。现已与实时路径对齐；无 SSR 数据时自动退化为普通 PPP。

### 6. NTRIP 2.0 兼容性补全

- Sourcetable 现已支持 `Transfer-Encoding: chunked` 与 `Content-Encoding: gzip`，并同时兼容 NTRIP 1.0 与 2.0 的状态行。
- 挂载点数据流路径（含分块解码）行为不变；实时验证：WUH20 流 30 s / 74 KB / 262 帧 CRC 全部通过。

---

## 二、新增与变更的配置项

所有新增开关**默认关闭**，关闭时与 RTKLIB 2.5.0 C 版逐值一致；打开才引入 Java 扩展行为。

| 开关 | 默认 | 说明 |
|---|---|---|
| `enableIonoEstRobust` | `false` | **JAVA-ONLY**。EST 首历元（及重置后）跳过相位行 maxinno 剔除，让滤波器一次吸收常数偏差；码行行为不变 |
| `pppArWlMaxdev` | `0.20` | 宽巷取整前的中位数对齐残差门（PRIDE-PPPAR "Widelane decision"） |
| `pppArNlMaxdev` / `pppArNlMaxsig` | `0.15` / `0.15` | 窄巷弧段级取整统计门 |
| `pppArNlMinsav` | `4` | 窄巷弧段最小成熟历元数，不足则拒绝固定 |
| `enablePppArDeltaGate` | `false` | **JAVA-ONLY**。整数域 δ 门（含基准自检），用于拦截整周平移导致的错误固定 |
| `pppArDeltaMax` | `0.5` | δ 门的半周界数学锚定值，非拟合参数 |
| `enableGpt3Vmf3` | `false` | 启用 GPT3 气象先验 + VMF3 映射函数 |
| `gpt3GridFile` | `null` | GPT3 5°×5° 网格路径（官方 `gpt3_5.grd`）；不设置则用内置近似 |
| `vmf3OpFile` | `null` | VMF3 逐时次系数文件（可选，覆盖 ah/aw/bH/bW） |
| `useGpt3Grid` | `false` | 增加湿延迟状态维度，改变滤波模型，需单独验证 |
| `enableCompactSsr` | `false` | 紧凑 SSR（QZSS CLAS 等）解码入口 |

---

## 三、兼容性与行为变更

1. **默认路径数值不变**：未开启任何扩展开关时，结果与 RTKLIB 2.5.0 C 版逐值一致（本版已验证 IFLC 浮点基线零回归）。
2. **PPP-AR 结果会变**：开启 AR 后，3F / Partial 的固定率与固定解位置与旧版不同，属修复而非回退；如需与历史结果对比，请固定配置与产品版本。
3. **对流层结果会变**：仅在使用 `enableGpt3Vmf3` 时生效；未开启时仍走原 NMF/Saastamoinen 基线。
4. **NTRIP Sourcetable 返回值**：现在返回纯表格文本（不带块长行/压缩字节），依赖旧"原始字节流"的调用方需注意。
5. **产品文件**：`.BIA`/`.BSX`/`.DCB` 统一按 `ns → m`（×1e-9×c）转换；GAL 仅映射 L1/E5a，E5b 按 C 版语义丢弃。

---

## 四、升级建议

| 场景 | 建议 |
|---|---|
| 静态短基线 RTK | 维持既有配置；本版 RTK 侧无行为变更 |
| 事后 PPP（IFLC） | 可直接升级，浮点结果不变 |
| 想用 PPP-AR | **优先三频 3F**；双频 IFLC 存在整周平移自由度固有受限（见已知问题） |
| 非组合 PPP（EST） | 建议开启 `enableIonoEstRobust` 并配置 DCB 产品 |
| 需要高精度对流层 | 开启 `enableGpt3Vmf3` 并下载官方 `gpt3_5.grd` 放入 `test-data/product/gpt3/` |
| 实时流接入 | 可直接使用；Sourcetable 现可解析 chunked/gzip 响应 |

---

## 五、已知问题

1. **双频 IFLC 的整周平移自由度**：无差分中位数对齐存在整周自由度，公共小数部分跨 0.5 边界会引入 ±1 周系统偏差；`δ 门` 可拦截但会让固定率降到 0（安全回退浮点）。**生产环境建议用 3F**。
2. **`PppCoreEx` 浮点解质量退化**：同一数据 ENU RMS 约为 `PppCore` 的数十倍，AR 因此难以固定，待排查 `pppResEx()` 与 `pppres()` 差异。
3. **GBM 产品的 3F 固定率仍为 0%**：候选完备性门槛（OSB 缺失星过多）导致可用星不足，非回归问题，暂不阻塞发布。
4. **ISB/IFCB/IFB 与部分 EST 配置**：U 向精度仍有显著劣化（+200%~+500%），与本次对流层修复无关，独立问题待查。
5. **紧凑 SSR 仅完成解码链路**：QZSS CLAS（MT4073）解码已实现；**Galileo HAS 的 IDD 接入尚未实现**，且无公开事后数据可用于验证。
6. **RINEX 2 导航文件不支持**：`RinexParser` 仅解析 RINEX 3 导航记录头。
7. **NTRIP 仅客户端**：不含 Caster/Server；响应状态用子串匹配 `200`，未严格解析状态码。

---

## 六、验证口径

| 项目 | 数据 | 方式 |
|---|---|---|
| PPP-AR | ALGO 站 2024 DOY060，WUM 精密产品 | `PppArValidationTest` 7/7 通过 |
| PPP-AR（GBM） | ALGO 2026 DOY288，GBM 产品 | 7/7 通过；3F 0% 见已知问题 3 |
| 对流层 GPT3 | HKWS / HKSL 2018 DOY157，全天 2880 历元 | `PppIndividualOptTest`，逐配置 A/B/C 对照 |
| NTRIP | 本地模拟 Caster + WUH20 实时流 | `NtripSourcetableDecodeTest` 3/3；实时 262 帧 CRC 通过 |

回归原则：每次改动均以"未开启该开关时与基线逐值一致"为准入条件。

---

## 七、发布清单（维护者）

- [x] `pom.xml` 版本号升级至 `3.1.0`
- [x] `docs/CHANGELOG.md` 将 `[Unreleased]` 改为正式版本与日期
- [x] 打新 tag `v3.1.0`（**不可复用旧 tag**，否则不会触发新 workflow）
- [ ] `mvn test` 全绿（含 `-pl rtklib-stream`、`-pl rtklib-core`）
- [ ] `mvn package -DskipTests` 产出四个 jar
- [ ] 确认 release 产物不含测试数据