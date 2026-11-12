# Release Notes

> 本文面向**使用者**：说明本次发布带来什么、怎么升级、有什么坑。
> 逐条技术明细见 [docs/CHANGELOG.md](docs/CHANGELOG.md)，数值修正的根因与验证见 [docs/ALGORITHM_FIX_LOG.md](docs/ALGORITHM_FIX_LOG.md)。

| 项 | 内容 |
|---|---|
| 发布状态 | **v3.5.0**（2026-11-12） |
| 最后更新 | 2026-11-12 |
| 运行环境 | Java 17、Maven 3.8+ |
| 主要依赖 | EJML 0.41、SLF4J、Logback、ONNX Runtime（`rtklib-ai` 可选） |
| 产物 | `rtklib-core`、`rtklib-stream`、`rtklib-adjust`、`rtklib-product`、`rtklib-otl`、`rtklib-ai` 等 jar |

> 注意：`pom.xml` 与各模块版本均为 `3.5.0`，tag 必须与 pom 版本一致（`release.yml` 会校验，不一致直接失败）。
>
> **v3.5.0 为许可证合规重构版本**：算法与对外 API 不变，`rtklib-otl` 与 `rtklib-core` 的 GPT3 读取器按公开文献独立重写，仓库内第三方编译产物与 GPL 关联源文件已从主干与 Git 历史中彻底移除。**Git 历史已重写，已有克隆必须重新 clone**（见第四章）。

---

## 一、本版重点

### 1. `rtklib-otl` 独立重写（GPL-3.0 → BSD-2 边界清理）

原 `AstroArg` / `SphericalHarmonic` / `BlqExtractor` 为外部 Fortran 源码的逐行移植，按保守口径构成 GPL-3.0 衍生作品，与本项目 BSD-2-Clause 冲突。

- 现按 IERS Conventions (2010) 第 7 章负荷潮球谐合成、IERS 2010 表 5.3 基本天文幅角、Doodson (1921) 编码、Schureman (1958) 交点潮调制、Farrell (1972) 负荷 Love 数等公开公式独立实现
- 改为面向对象分层：`BlqCalculator` / `OceanLoadingModel` / `GeodeticSite` / `LegendreTable` / `AstronomicalArgument` / `DoodsonArgument` / `NodalCorrection` / `ComplexNumber` / `TidalConstituent`
- 不再与任何 `.f90` 存在函数名、变量名、数组打包索引或循环结构对应关系
- **对外 API 不变**：`extract` / `extractDisplacement` 签名保持，全部入口类已切换

数值等价性：4 站 BLQ 输出与历史基线**逐字节一致**（振幅/相位差异 0.0000），位移序列差异 < 1e-10 mm，23 站边界测试（极区 89°、赤道、海岸线、极端经度）100% 通过。

### 2. `Gpt3GridReader` 独立重写

- 原实现按官方 `gpt3_5.f90` 逐行移植，现按 Landskron & Böhm (2018) 网格插值算法与 Askne & Nordius (1987) 大气加权平均温度公式独立实现
- 删除全部 `.f90` 变量名、行号引用与逐点一致注释
- 数值等价性：12 点固定基准转储与重写前**逐位一致**（`Gpt3GridReaderTest` 以 `1e-8` 容差固化），PPP 全天解算零漂移

### 3. 第三方编译产物与 Git 历史清理

- RTKLIB-EX 可执行文件（27 个 `.exe`）、Fortran 参考程序（4 支 `.f90`）及其全部编译产物、`libopenblas.a` 等已从主干移除
- 已用 `git filter-repo` 对 Git 历史做全量重写，抹除上述路径的全部历史提交对象，`refs/original` 与 reflog 同步清理
- `.gitignore` 增加 9 条永久防护规则，防止回归
- 校验：`git log --all -- '*.f90' '*.exe'` 无输出；仓库当前仅含 BSD-2-Clause 独立实现代码

### 4. 接口三激进模式实验配置（默认关闭）

- `AiOpts` 新增 `enableAggressiveAi`（默认 `false`）与 `aggressiveMinScale`（默认 `0.1`）
- 开启后 `RtkCore` 把 `varianceScale` 钳位下限由固定 `1.0` 放宽到 `aggressiveMinScale`，`bias` 上限与 `varianceScaleMax` 保持不变
- 训练侧新增 `--scale-min`，测试侧新增 `-Dpairing.aggressive` / `-Dpairing.min-scale`
- **默认值行为与 3.4.2 逐位一致**，不改变任何现有解算结果；激进模式仅用于实验对照，不参与交付口径（详见 [AI 增强设计 §4.6](docs/AI_ENHANCEMENT_DESIGN.md)）

### 5. 继承自 3.4.2 的算法修复

本版包含 3.4.2 的全部修复：短基线 RTK 固定率 0.07% → 73.82%（C 原版 73.78%）、PPP / PPP-AR / PPP-RTK 三条链路「固定解永久锁存」闭环、`ddres` 对流层项、`MDAY` 闰年错位、`pppos` 迭代起点等 C 对齐缺陷。详见 [docs/CHANGELOG.md](docs/CHANGELOG.md) 的 `[3.4.2]` 小节。

---

## 二、新增与变更的配置项

所有新增开关**默认关闭**，关闭时与既有版本逐值一致；打开才引入扩展行为。

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
| `enableAggressiveAi` | `false` | **v3.5.0 新增，实验项**。放开接口三 `varianceScale` 下限到 `aggressiveMinScale`；不参与交付口径 |
| `aggressiveMinScale` | `0.1` | **v3.5.0 新增**。激进模式下的 `varianceScale` 下限，仅在 `enableAggressiveAi=true` 时生效 |

---

## 三、兼容性与行为变更

1. **对外 API 不变**：`rtklib-otl` 的 `extract` / `extractDisplacement` 签名未变，仅内部类重组（`AstroArg` / `SphericalHarmonic` / `BlqExtractor` → 面向对象分层）。若外部代码直接引用了这三个已删除的类，需改用新入口。
2. **默认路径数值不变**：未开启任何扩展开关时，结果与上一版本逐值一致。OTL 输出逐字节一致，GPT3 输出逐位一致（12 点基准固化）。
3. **Git 历史已重写**：`main`、`feature/ppp-corrections-and-boundary` 与全部 tag 已强制推送；已有克隆无法 `git pull`，必须重新 `git clone`。
4. **对流层结果会变**：仅在使用 `enableGpt3Vmf3` 时生效；未开启时仍走原 NMF/Saastamoinen 基线。
5. **产品文件**：`.BIA`/`.BSX`/`.DCB` 统一按 `ns → m`（×1e-9×c）转换；GAL 仅映射 L1/E5a，E5b 按 C 版语义丢弃。

---

## 四、升级建议

| 场景 | 建议 |
|---|---|
| 已有本地克隆 | **必须重新 `git clone`**，历史已重写，`pull` 会因分叉失败 |
| 静态短基线 RTK | 维持既有配置；本版 RTK 侧无行为变更 |
| 事后 PPP（IFLC） | 可直接升级，浮点结果不变 |
| 想用 PPP-AR | **优先三频 3F**；双频 IFLC 存在整周平移自由度固有受限（见已知问题） |
| 需要高精度对流层 | 开启 `enableGpt3Vmf3` 并下载官方 `gpt3_5.grd` 放入 `test-data/product/gpt3/` |
| 接口三观测改正 | 默认不注入；第一阶段 A1/A2/A3 已通过，仅允许显式注入，第二阶段净收益验收未完成 |
| 接口三激进模式 | 仅作实验对照，**不要用于生产**；默认关闭 |

---

## 五、已知问题

1. **双频 IFLC 的整周平移自由度**：无差分中位数对齐存在整周自由度，公共小数部分跨 0.5 边界会引入 ±1 周系统偏差；`δ 门` 可拦截但会让固定率降到 0（安全回退浮点）。**生产环境建议用 3F**。
2. **`PppCoreEx` 浮点解质量退化**：同一数据 ENU RMS 约为 `PppCore` 的数十倍，AR 因此难以固定，待排查 `pppResEx()` 与 `pppres()` 差异。
3. **GBM 产品的 3F 固定率仍为 0%**：候选完备性门槛（OSB 缺失星过多）导致可用星不足，非回归问题，暂不阻塞发布。
4. **ISB/IFCB/IFB 与部分 EST 配置**：U 向精度仍有显著劣化（+200%~+500%），独立问题待查。
5. **紧凑 SSR 仅完成解码链路**：QZSS CLAS（MT4073）解码已实现；**Galileo HAS 的 IDD 接入尚未实现**，且无公开事后数据可用于验证。
6. **RINEX 2 导航文件不支持**：`RinexParser` 仅解析 RINEX 3 导航记录头。
7. **NTRIP 仅客户端**：不含 Caster/Server；响应状态用子串匹配 `200`，未严格解析状态码。
8. **接口一训练目标不可达**：原 G-1/G-2 组合（1 pp / 30%）在当前 6 维特征集下不可达（留一站 1/7、留多站 0/7 满足），根因为跨站判别力与全局阈值可迁移性不足；可交付口径已改为成对档（详见 [AI 增强设计 §8.2](docs/AI_ENHANCEMENT_DESIGN.md)）。
9. **接口三仅第一阶段验收**：A1/A2/A3（能用/无害/有作用）已通过，模型默认不注入；第二阶段净收益（G-1'~G-5'）未完成。方案 B（端到端坐标 Loss）判定不交付，仅作实验 provider 保留。

---

## 六、验证口径

| 项目 | 数据 | 方式 |
|---|---|---|
| OTL 数值等价 | 4 站 BLQ 基线 + 23 站边界 | BLQ 输出逐字节一致；位移差异 < 1e-10 mm；23 站 100% 通过 |
| GPT3 数值等价 | 12 点固定基准 | `Gpt3GridReaderTest`，容差 `1e-8` |
| Git 历史洁净 | 全仓历史 | `git log --all -- '*.f90' '*.exe'` 无输出；二进制路径无对象 |
| PPP-AR | ALGO 站 2024 DOY060，WUM 精密产品 | `PppArValidationTest` 7/7 通过 |
| 对流层 GPT3 | HKWS / HKSL 2018 DOY157，全天 2880 历元 | `PppIndividualOptTest`，逐配置 A/B/C 对照 |
| 接口三第一阶段 | 单站 + 配对采集 | A1/A2/A3 判据（详见 [AI 增强设计 §7.5](docs/AI_ENHANCEMENT_DESIGN.md)） |
| 全模块编译 | 13 个模块 | `mvn -q -DskipTests package` 全绿 |

回归原则：每次改动均以"未开启该开关时与基线逐值一致"为准入条件。

---

## 七、发布清单（维护者）

- [x] `pom.xml` 与各模块版本号升级至 `3.5.0`（13 个 pom）
- [x] `docs/CHANGELOG.md` 将 `[Unreleased]` 改为 `[3.5.0] - 2026-11-12`
- [x] Git 历史重写完成（`git filter-repo`），`refs/original` 与 reflog 已清理
- [ ] 打新 tag `v3.5.0`（**不可复用旧 tag**，否则不会触发新 workflow）
- [ ] `mvn test` 全绿（含 `-pl rtklib-core`、`-pl rtklib-otl`）
- [ ] `mvn package -DskipTests` 产出全部模块 jar
- [ ] 确认 release 产物不含测试数据与第三方二进制