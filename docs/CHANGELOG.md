# RTKLIB Java 变更记录

本文件记录版本间的重要变更、Bug修复及与GitHub Issue的对应关系。

格式遵循 [Keep a Changelog](https://keepachangelog.com/)，版本号遵循 [Semantic Versioning](https://semver.org/)。

---

## [3.1.0] - 2026-10-23

### Build / CI

- **CI 减负**：`ci.yml` 测试步骤排除 JMH 基准 `JmhRunnerTest`（全量运行 20min+ 且无回归价值），需要时本地单独执行 `mvn test -pl rtklib-core "-Dtest=JmhRunnerTest" "-DfailIfNoTests=false"`
- **发布收尾**：`release.yml` 的 Release 正文改为 `body_path: RELEASE.md`（不再使用自动生成说明，避免与发布稿口径不一致）；产物补齐 `rtklib-otl` 与 `rtklib-research` 两个 jar，与 README 模块清单一致

### Docs

- **封存版本**：算法内容与 2.4.0 完全一致（无算法变更），新增《项目终期技术摘要》（`docs/PROJECT_FINAL_SUMMARY.md`），全模块版本号统一升至 3.1.0，作为定版封存基线

## [2.4.0] - 2026-10-23

### Added

- **DcbReader 码偏差产品读取（C 对齐移植）**：完整移植 RTKLIB preceph.c `readdcb()`（L514-544）——入口清零 `nav.cbias`（对齐 C L523-525），`.BIA/.BSX` 走 `readbiaf`（Bias-SINEX OSB/DSB 记录，C L449-502）、`.DCB` 走 `readdcbf`（P1-C1/P2-C2 传统格式，C L378-417），值统一 ×1E-9×CLIGHT（ns→m），GAL 频率映射对齐 C（L1→freq0、L5→freq1，E5b 丢弃）；码偏移索引经 `ObsCode.obs2code` + `SppCore.code2biasIx`（对齐 C `init_bias_ix` 的 −1=不支持语义）。防御性修复 C 两处缺陷：OSB 对未收录码（bias_ix=−1）时 C 写 `cbias[-2]` 越界（UB），Java 跳过；readdcb 失败时 C 的 −1 哨兵被 corr_meas 应用为 −1 m 伪距偏移，Java 保持 0
- **DCB 接线 RINEX 路径**：`PostPosProcessor.setDcbPath()` / `RinexPppProcessor.setDcbPath()`（RTCM 路径 `PppProcessor` 原已有 readdcb 调用），对应 C postpos 的 `fopt->dcb`
- **EST 模式健壮初始化开关 [JAVA-ONLY]**：`RtkConfig.enableIonoEstRobust`（默认 `false`，关闭时与 C 行为逐值一致）——IONOOPT_EST 首历元（及周跳/超时重置）对本历元刚 initx 的 bias/iono 状态（协方差恰为 VAR_BIAS/VAR_IONO=3600，尚未被任何 KF 更新吸收）跳过相位行 prefit maxinno 剔除，让 EKF 一次性吸收常数偏差；仅放宽相位行（code==0），码行剔除与 C 一致；拷贝构造同步复制。实测 ALGO 2880 历元：EST 6 配置 0→401~509 success（EST+MultiSys E 4.8/N 1.5/U 5.2 cm），IFLC 基线 2679/2880 不变（详见 `ALGORITHM_FIX_LOG.md` FIX-ALG-020）
- **PppValidationTest 3.14/3.15**：EST±MultiSys + WUM0MGXRAP_20240600000_01D_01D_OSB.BIA（2024 DOY 060，与测试数据日期匹配，DCB 开关关闭）对照测试

### Fixed

- **GPT3+VMF3 对流层路径 U 向恶化闭环**（FIX-ALG-029）：三缺陷叠加——① `PppOptimizations.vmf3()` 只实现了映射函数的分子 `1+a/(1+b/(1+c))`、缺失连续分数分母，el=15° 时 mfh 算得 1.00001（正确 3.863），斜延迟每星少修正约 6.6m，为 U 向 +793% 的主毒源；② `saastamoinenZhdZwd` 系数 `0.00002412·p` 要求 p 为 Pa 而输入为 hPa，ZHD 低估 100 倍（2.44m→0.024m），解释了网格与 fallback 路径同错；③ ch/cw 硬编码 0、bw 误用 GMF 分段（75° 达 0.00536），一并按 Landskron & Böhm 2018 官方值修正。同时 `Gpt3GridReader` 按官方 `gpt3_5.f90` 全文重写（36×72 格元中心 / 64 列 / 谐波相位直接取 doy / 高程归约 / 极区最近邻），`RtkConfig.gpt3GridFile` 由死字段接线至 `PppProcessor` 的 `process(byte[])`（`batchRtcm.nav`）与 `processRinex`（`parser.nav`）双路径。实测全天 2880 历元：真网格 HKWS U 7.365→0.806m（基线 0.821，−1.9%）、HKSL 1.662m（基线 1.684，−1.3%），3D 均优于 NMF 基线，非 GPT3 配置逐值零连带回归；环境变量 `PPP_GPT3_GRID` 可覆盖网格路径，测试默认探测 `test-data/product/gpt3/gpt3_5.grd`
- **NTRIP Sourcetable 响应解码补全**：`NtripClient.getSourcetable()` 原实现直接按行读取 socket，Caster 若对 Sourcetable 返回 `Transfer-Encoding: chunked` 或 `Content-Encoding: gzip`，块长行/压缩字节会混入文本导致解析失败。现解析响应头并按 HTTP 编码施加的逆序处理：先解块（新增 `ChunkedInputStream`，RFC 7230 §4.1，支持块扩展与 0 长度结束块、块尾 CRLF 消费）再解压（`GZIPInputStream`）；同时跳过状态行以兼容 NTRIP 1.0（`SOURCETABLE 200 OK`）与 2.0（`HTTP/1.1 200 OK`）。数据流（挂载点订阅）路径行为不变。新增 `NtripSourcetableDecodeTest` 以本地 ServerSocket 模拟三种响应形态（明文 / chunked / chunked+gzip），3/3 通过；`NtripLiveRecordTest` 因无凭据按 assume 跳过，全模块 4 用例 0 失败
- **PppAmbFix WL 门限按 PRIDE-PPPAR 对齐**（FIX-ALG-022）：① OSB 完备性门槛扩展至码 OSB（对齐 PRIDE `lsq.f90` nbias_used≥4 语义，L1/L2 相位+码四信号 OSB 齐全才可参与 AR，否则保浮点——GAL/BDS 第二频码 OSB 缺失星与无 OSB 行星被排除出候选）；② 主路径与 PartialAR 增加中位数对齐 maxdev 检验（`RtkConfig.pppArWlMaxdev=0.20`，PRIDE config_template 'Widelane decision' 第 1 参数，bdeci prob=0 硬条件；maxsig=0.15 被既有 3σ>0.2 弧段预滤覆盖未重复实现）；③ PartialAR OSB 模式双重改正修复（`yWl=wlMean−fcbWl` 中 fcbWl 二次改正，修复为 `wlMean−medCand`）。缺口②（WL 弧段加权平均）经核对与 PRIDE `lsq_add_obs.f90` 完全等价，未修改。A/B 实测：EST+ISB fix 4→19（0.2%→1.0%，RMS 不变为非假 Fix），Partial fix 0→50（均为 ~2m 假 Fix，per-epoch NL 自洽假 Fix 既有问题被双重改正掩盖后暴露，列为后续修复项）；PppValidationTest 浮点基线无回归
- **PppAmbFix NL 弧段级机制（PRIDE-PPPAR 对齐）**（FIX-ALG-023）：修复 PartialAR per-epoch NL LAMBDA 自洽假 Fix（FIX-ALG-022 后暴露的 50 个 ~2m 假 Fix）。新增 Ssat NL 弧段累加字段（xrnl/rn/xsnl/ninl）与 `accumulateNlArc()`（wele 高度角权重、slip/lock 重置、x=0 未初始化哨兵跳过，累加点在 `pppPartialAR` 入口滤波更新后）；`RtkConfig` 新增 `pppArNlMaxdev/pppArNlMaxsig=0.15`（PRIDE 'Narrowlane decision'）与 `pppArNlMinsav=4`（'Critical search'），拷贝构造同步复制；候选级弧段成熟度门（ninl<minsav 拒绝）+ σ 门（ninl>1）+ 中位数对齐 NL dev 门（临时 WL 整数 wlProv 构造 nlProv，消公共接收机 NL UPD）；子集 LAMBDA 输入改用弧段均值（原始量 x[IB]/λ_NL 累加、固定时以当期 wlFixed 补减，弧段序列与单历元 WL 结果解耦）；PppCore 偏差超时重置与 slip 重初始化两点补弧段重置。PRIDE chisq 角色由 σ门+dev门+ratio 组合承担（整数域统计已实证无法拦截自洽假 Fix）。实测：Partial fix 50（全假）→38（全真，1.3%），ENU E 32.9cm→1.56cm 回落至浮点基线；FixHold/3F/WL+NL/EST+ISB 零回归；主路径与 3F 路径 NL 仍 per-epoch 列为后续候选
- **PppAmbFix 3F PPP-AR WL/NL median 对齐 + IFLC 逐星取整**（FIX-ALG-024）：修复 3F 配置（EWL+WL+NL）固定率 0%。三处编译作用域错误修复（`hasDualFreq`→`useDualFreqWl`、`info`/`wlRatio`/`nlRatio` 声明提升）；WL epoch median 对齐（`medWl3f`，LAMBDA 与取整分支共用 datum，消除公共接收机 WL UPD——OSB 产品仅改卫星端的残余）；IFLC 分支 WL/NL 均改逐星取整（median 对齐 + frac<0.25 门 + nFixed≥4，对角协方差下 LAMBDA ratio 恒 1.00 无区分度），LAMBDA 仅保留 EST 双频路径；NL 重构改用 nlFixed[]。实测：3F fix 0→1226（65.7%），ENU RMS E=2.16/N=2.81/U=5.08cm 与浮点基线同量级无假 Fix；其余 6 配置零回归
- **PppAmbFix 3F NL 构造公式验证 + EST 分支系数/符号修复 + 弧段均值实验负结果**（FIX-ALG-025）：IFLC 下 nlFloat = x[IB]/λ_NL − wlFixed·f2/(f1−f2) ≡ N1（L1 整周模糊度），重构公式精确复原 IF 模糊度真值，datum 完全自洽；EST 分支减项 `wlFixed·f2/(f1−f2)` → **`+ wlFixed·f2/(f1+f2)`**（系数与符号同时修正，N1 = NL + f2·WL/(f1+f2)，符号与 IFLC 分支相反——两分支原始量 N2 系数符号相反，消项方向相反），重构改为 n1=fixedNl、n2=fixedNl−wlFixed；3F 弧段均值实验（FIX-ALG-023 机制接入）负结果回退——fallback 式设计放行收敛期瞬态弧段，fix 1226→1454 但 2D 3.54→8.21cm、dE/dU 8/11cm 系统偏差，弧段均值仅适用于 Partial 的拒绝式+全门场景，3F 保持 per-epoch
- **PppCore bias/iono 超时重置链对齐 C ppp.c**（FIX-ALG-021）：`udbias_ppp` 超时重置原被 `vsat!=0` 条件阻塞且重置后清 outc/vsat，被剔卫星 stale bias 永不重置、永久退出解算——对齐 C L744-748 改为 `++outc>maxout || modear==ARMODE_INST` 时无条件 `initx(0.0, 0.0)`（方差 0.0 擦除，不清 outc/vsat；clk_jump 无 Java 对应机制已注明）；`udiono_ppp` 重初始化原多加 `outc<=GAP_RESION` 条件，GAP_RESION 重置后 outc 恒超限导致电离层永不重建——对齐 C L681 改回 `x[j]==0.0` 无条件初始化，并移除 C 没有的两处 `testsnr()` 调用。MW/GF 周跳检测历史清理保留为 JAVA-ONLY 扩展。实测 ALGO 2880 历元：EST 6 配置 401~509→2880（100%），EST+DCB 4→1537，EST+MultiSys+DCB 0→1760，IFLC 基线 2679→2880
- **RtkCore PPP-RTK 分发缺失修复**：`RtkCore.rtkpos()` 事后 PPP 分支不含 `enablePppRtk` 判定，`PMODE_PPPRTK_STATIC/KINEMA` 在 RINEX 路径永远落入 `PppCore.pppos()`/`PppCoreEx.ppos()`，`PppRtkCore.ppprtkos()` 无法到达（RTCM 实时路径 `PppProcessor` L816-825 已有此分发）。修复：分发优先级对齐 PppProcessor——`enablePppRtk` 优先于 AR/偏差开关组；无 SSR 数据时 `ppprtkos` 内部退化为普通 PPP
- **PppRtkValidationTest 骨架修复**：4.2/4.3/4.4 原以单参 `new RinexPppProcessor(opt)` 构造，`enablePppRtk/enablePppRtkAR` 开关从未传入处理器（cfg 悬空）；修复为 4 参构造传入 cfg，4.3/4.4 补 ENU 精度评估（ALGO IGS20 参考坐标，收敛期跳过前 1/3）与基本断言；固定率下限断言待 SSR 数据接入后启用（nav.ssr 为空时 ppprtkos 退化为普通 PPP 无 Fix 能力）
- **PppAmbFix/PppCore/OsbReader WUM 3F 67.81cm 回归三因素闭环**（FIX-ALG-028）：① `getEwlFcb` 恢复快照语义——EWL 弧段均值相位项无 OSB 改正，其无偏化全依赖固定时 `-fcbEwl` 单次常量补偿，027 切 getOsbAt 后 GPS L5Q 900s 时变序列使补偿值全天漂移 ±1.84 EWL 周（1900/2491 条序列记录与快照不一致），EWL 取整基准逐历元漂移致 3F 全路径中毒（A/B 探针：仅回退即 65.34→7.53cm）；② OsbReader sh 列偏移升级双令牌判别 v2——原单令牌规则把 WUM 行中落在 PRN 域内的 SVN（GPS 43-64 十六颗 + BDS-2 C005-C019 十一颗）误判为 GBM 版式致 27 星 OSB 静默丢失；③ MW 组合 WL 偏差改正"恰好施加一次"裁决（2×2 矩阵实测：单次+无码OSB=3.00cm 终态 / 双重+码OSB=3.54cm / 错配组合 7.5~8cm 中毒）——mwmeas OSB 分支移除码 OSB 施加（仅相位 OSB），3F WL 固定时不再补偿 ±fcbWl（与 MAIN 单次分支语义统一）；附 FIX-ALG-027 勘误（"WUM 零影响"证伪 + 日边界时间戳假设证伪）。实测 7/7：3F 3.00cm（优于金标准 3.54cm），MAIN 双频与 Float 逐值一致零连带回归
- **OsbReader 时变 OSB 读取兼容**（FIX-ALG-027）：GBM 0MGXRAP 30s 时变 BIA 支持——① Bias-SINEX START/END 列解析（parseSinexTime "yyyy:ddd:sssss"→GPST 秒）+ `Nav.osbSeries` 时间戳序列，key=((sat,code))×2+(码0/相位1)（C1C/L1C 共享底层码号必须分键，混流会使相位改正误用码偏差）；② GBM 行 SVN 列空缺列偏移自适应（WUM 填 SVN→PRN 在 tokens[2]，GBM 空→tokens[1]，按 tokens[1] 是否为合法卫星号判定），GBM 259MB 全文 0 记录→phaseOSB=871709+codeOSB=1348308 全解析；③ 码 OSB timedShort（区间<86400s）只进 osbSeries 不进 cbias（防 2880 行累加爆炸），全天区间维持 WUM cbias 常量语义；④ `PppAmbFix.getOsbAt(time)` 按历元取值（未命中取最近记录保底、回退快照），getWlFcb/getNlFcb/getEwlFcb(+time)、`PppCore.getCodeOsb(+time)`、PppAmbFixBds3 同步，完备性检查保留快照语义。GBM-2026288 全套 7/7：双频 WL+NL/FixHold/Partial 固定率 0% 跨产品复现（δ 门 datum+2.25 周拦截、回退与浮点逐值一致）印证 FIX-ALG-026 整周平移自由度普适性；3F 0%（WUM 65.7%）列待深挖；EST+ISB fix=7（0.3%）。注：本条目"列偏移自适应"规则已被 FIX-ALG-028 双令牌判别 v2 取代，"WUM 常量语义零影响"结论已被 FIX-ALG-028 证伪并勘误
- **mwmeas MW-OSB 诊断插桩清理**：移除 30 分钟限频的 System.out 逐星打印及配套 mwCorCyc 死代码（FIX-ALG-026 深挖使命已完成，OSB 应用语义结论已固化至算法修复文档），OSB 扣除逻辑本身不变
- **PppAmbFix 双频主路径/PartialAR IFLC 逐星取整分支**（FIX-ALG-026）：修复双频 WL+NL/FixHold 固定率 0%（ratio~1.05）与 Partial 1.3%——IFLC 模式（单组合模糊度/星）下 rtk.P 给出对角 QbWl/QbNl，LAMBDA 退化为逐星取整且 ratio 恒 ~1.0-1.05。主路径 WL 阶段跳过 QbWl/LAMBDA，对已 median 对齐的 MW 弧段均值逐星取整（frac<0.25 门 + nFixed≥4 + 原位压缩）；NL 阶段不再填 QbNl，对 nlFloat 做 frac 中位数对齐后逐星取整；`pppPartialAR` 增加早期返回式同构 IFLC 分支（WL 取整对象 wlVals−medCand，NL 取整对象弧段均值 xrnl/rn 构造的 nlProv + kept 子集二次 median 对齐，Partial 语义由上游拒绝式 dev 门保持）；修复 NL else 分支 `info` 块级作用域编译错误。新增 `FRACDUMP` 插桩（环境变量 `PPPAR_FRAC_DUMP=1`，默认关闭；6 个插桩点 MAIN-WL/MAIN-NL/PARTIAL-WL/PARTIAL-NL/3F-WL/3F-NL）。实测 PppArValidationTest 7/7：WL+NL/FixHold/Partial 三模式复活（2D 14.75/14.75/29.84cm），3F 逐值零回归（2D 3.54cm）、Float 零回归；FRACDUMP 交叉定位 wrong fix 源头为 WL 级整周 OSB 偏差（PRN 23/25/26，主路径与已验证 3F 参考逐弧段比对偏差 ±1，frac 门对整周级偏差原理性盲区，收紧门/方差门均无效）。追加整数域 δ 门（JAVA-ONLY `enablePppArDeltaGate` 默认关闭 + `pppArDeltaMax=0.5` 半周界，双重规则：基准自检 |medDelta|>0.5 整历元放弃防 var=0 冻结投毒 + 逐星剔除 dev 超限；三轮标定 0.4 全拒/2.0 放行 NL±1 投毒 3.19cm/0.5+基准自检 0 污染）：该数据集收敛期 clean 星仅 ≤3 颗与 nFixed≥4 约定冲突，δ 门开启时双频固定率为 0（安全回退浮点 1.78cm）。处置收口（用户裁决）：维持 nFixed≥4，双频 IFLC 记录为"无差分 median 对齐整周平移自由度 + clean 星不足"固有受限（深挖修正根因定性——真实偏差为 fcbWl=+0.7714 相位 OSB WL 组合 frac 跨界，非 L2 码 OSB 产品误差；3F 的 −fcbWl 为双重改正、成功属"错进错出"），生产推荐 3F，min=3 不采纳；pppArDeltaMax=0.5 为半周界数学锚定值非拟合参数（详见 `ALGORITHM_FIX_LOG.md` FIX-ALG-026）

### Docs

- `ALGORITHM_FIX_LOG.md`：新增 FIX-ALG-022（PPP-AR WL 门限 PRIDE-PPPAR 对齐：OSB 完备性 + maxdev 检验 + PartialAR 双重改正修复 + A/B 验证 + Partial 假 Fix 已知问题）
- `ALGORITHM_FIX_LOG.md`：新增 FIX-ALG-023（NL 弧段级机制：弧段累加 + minsav 成熟度门 + σ/dev 统计门 + chisq 等价性说明 + A/B 验证，Partial 假 Fix 50→38 全真）
- `ALGORITHM_FIX_LOG.md`：新增 FIX-ALG-024（3F PPP-AR WL/NL epoch median 对齐 + IFLC 逐星取整 + 编译作用域修复，3F fix 0→1226 / 65.7%，ENU 厘米级无假 Fix）
- `ALGORITHM_FIX_LOG.md`：新增 FIX-ALG-025（3F NL 构造公式验证 IFLC nlFloat≡N1 + EST 分支系数修复 + 弧段均值实验负结果回退归档）
- `ALGORITHM_FIX_LOG.md`：新增 FIX-ALG-026（双频主路径/PartialAR IFLC 逐星取整分支 + FRACDUMP 定位插桩 + δ 门三轮标定，三模式复活 + 3F 零回归；深挖修正根因定性：整周平移自由度 + 3F 双重改正错进错出；处置收口：双频 IFLC 记录受限、生产推荐 3F + 架构债记录）
- `ALGORITHM_FIX_LOG.md`：新增 FIX-ALG-027（时变 OSB 读取：osbSeries 时间戳序列码/相位分键 + GBM SVN 空缺列偏移自适应 + cbias 累加守卫；GBM-2026288 全套 7/7，双频 0% 跨产品印证整周平移自由度普适性 + 3F 0% 待深挖；更正 FIX-ALG-026 数据集名为 ALGO 2024060 + WUM BIA）
- `ALGORITHM_FIX_LOG.md`：新增 FIX-ALG-028（WUM 3F 67.81cm 回归三因素根因闭环：getEwlFcb 快照恢复 + sh 双令牌判别 v2 + MW 组合 OSB 单次施加 2×2 矩阵裁决 3.00cm 终态；含 FIX-ALG-027 两项勘误：WUM 零影响证伪 + 日边界时间戳假设证伪）
- `ALGORITHM_FIX_LOG.md`：新增 FIX-ALG-020（EST 死锁根因 + 双方案修复 + 实测验证；修正此前"C 靠自动加载 DCB 规避死锁"推断——卫星-only OSB 产品实测仅 0→4，接收机码偏差才是泄漏主项）
- `RTKLIB_Differences.md`：新增 §18（EST 首历元死锁与 DCB 产品加载，C 固有缺陷 + 两处 C 缺陷的 Java 防御差异 + enableIonoEstRobust JAVA-ONLY 语义）
- `PPP_Optimization_Systems.md`：新增 §7（EST 死锁修复与实测，修正结论与 EST 体系后续方向）

### Fixed

- **RtkCore PPP-AR 路由缺失修复**：`RtkCore.rtkpos()` 原硬编码 `PppCore.pppos()`，PPP 模式下无论 `enablePppAR` 是否开启均走基础浮点路径，AR/偏差改正代码完全绕过——`PppArValidationTest` 固定率 0% 的根因。修复：增加 `cfg` 判断分支，当 AR/偏差任一开关开启时路由到 `PppCoreEx.ppos()`，否则走 `PppCore.pppos()`。路径A（`PppProcessor`）原有此分发，路径B（`RinexPppProcessor → RtkCore`）缺失，现已对齐
- **DcbReader.initCbias 三维数组遍历类型修复**：`nav.cbias` 类型为 `double[MAXSAT][NFREQ][NFREQ]`，`initCbias` 中 `for (double[] frqBias : nav.cbias)` 将 `double[][]` 赋给 `double[]`，编译错误。修复为 `for (double[][] satBias : nav.cbias)`
- **PppCore diagSat 调试遗留清理**：`PppCore.ppos()` 中 `diagSat` 变量未定义（非组合PPP调试遗留），编译错误。移除相关 `System.out.printf` 调试代码

### Changed

- **PPP_Optimization_Systems.md 更新**：§2.2 新增路由架构（两条处理路径 A/B 及 AR 分发差异）；§2.5 新增当前测试状态（路由修复验证、AR 执行诊断、PppCoreEx 浮点解质量退化问题记录）

### Known Issues

- **PppCoreEx 浮点解质量退化**：同一 ALGO 数据，`PppCoreEx.ppos()` ENU RMS = 0.424/1.311/0.694m，比 `PppCore.pppos()` 的 0.025/0.007/0.044m 差约 50 倍。AR 在劣质浮点解上无法固定（WL ratio 恒为 1.0）。待排查 `pppResEx()` 与 `pppres()` 差异

## [2.3.3] - 2026-10-18

### Docs

- `ROADMAP.md` §8.1：4A 暂缓原因表述修订，分离技术可行性与场景缺失（"技术可行（RAIM FDE 已就绪），但保护级需风险预算公式 + HAL/VAL 告警阈值，当前无车规/航空/监测场景驱动，暂缓"）

## [2.3.2] - 2026-10-16

### Fixed

- **RtkCore relpos azel/freq 跨历元泄漏修复**：`RtkCore.relpos()` 入口新增 `Arrays.fill(azel, 0, n*2, 0.0)` 与 `Arrays.fill(freq, 0, n*nf, 0.0)`——C 版 `rtkpos.c` L1986 `azel=zeros(2,n), freq=zeros(nf,n)` 每历元零初始化，Java 工作缓冲复用 `wb.azel/wb.freq` 从未清零；若某卫星因 `geodist` 失败（`r<=0`）被 `zdres` 跳过，其 azel 残留上历元值导致 `selsat` 误选（`ns` 膨胀），虽 `ddres` 的 `validobs` 会过滤但 `udbias` 无此防护（详见 `ALGORITHM_FIX_LOG.md` FIX-ALG-008）
- **PppCore pppos azel 跨历元泄漏修复**：`PppCore.pppos()` 入口新增 `Arrays.fill(azel, 0, n*2, 0.0)`——C 版 `ppp.c` L1199 `azel=zeros(2,n)` 每历元零初始化，Java 工作缓冲复用 `wb.azel` 从未清零；若某卫星因 `geodist` 失败被 `pppRes` 跳过，其 azel 残留值可污染下游高度角依赖逻辑（详见 `ALGORITHM_FIX_LOG.md` FIX-ALG-009）

### Docs

- `RTK_OPTIMIZATION_TEST_RESULTS.md`：§4 表格 10 处 CascadeAR 行补齐版本标注（v2.2.x伪逐级/v2.3.0真逐级待重测）；§6.3 推荐矩阵 5 处 `❌ Bug` → `⚠️ v2.3.0待重测`
- `CHANGELOG.md`：v2.3.0 `Unreleased` → 正式日期 `2026-10-15`

## [2.3.0] - 2026-10-15

### Fixed

- **pppos 排除标志跨历元泄漏修复**：`PppCore.pppos()` 入口新增 `Arrays.fill(exc, 0)`——C 版 `ppp.c` L1185 `exc[MAXOBS]={0}` 每历元重建置零，Java 工作缓冲复用 `wb.exc` 从未清零，ep1 残差剔除标志泄漏至后续历元致健康卫星误剔、nv 死亡螺旋；GreatPvt PPP static solved 1/30→14/30（详见 `ALGORITHM_FIX_LOG.md` FIX-ALG-001）
- **RTK IFLC 频槽混合根因修复**：移除 `RtkCore.rtkpos()` 中的 `compactObsFreq` 调用——槽位压缩导致双站 slot1 指向不同频率（流动站 B2a vs 基准站 B2b），IFLC 双差 bias≈1e6 m 毒化全部模糊度，Float 发散、Fix 率 0%（PPC-Dataset Tokyo run1 实证；详见 `RTKLIB_Differences.md` §16）
- **satid2no 空格填充 PRN 解析修复**：RINEX 3 导航文件按 `G%2d` 写出的单位数 PRN（"G 5"/"E 4"/"R 7"）被 `Integer.parseInt` 抛 NumberFormatException 而静默丢弃全部单位数 PRN 广播星历 → satposs rs=0 → 双站 azel=0 → DD 卫星数坍缩（Tokyo run1 selsat ns 21→9）→ posvar 超过 thresar[1]=0.1 → AR 永不触发（Fix=0）；修复对齐 C `sscanf("%c%d")` 空白跳过语义（rtkcmn.c satid2no L475-501），Tokyo run1 Fix 0→570、Float 8986→10927、成功率 85.8%→97.9%（详见 `RTKLIB_Differences.md` §16.7）
- **RINEX 3 观测槽位分配修复**：`RinexParser` 原对每个观测类型直接 `code2idx` 写入、后写覆盖先写，违反 C `rinex.c set_index` 的 CODEPRIS 优先级仲裁语义（同槽多码型选最高 pri、同 code 共享主槽、落选进 NEXOBS）——rover GPS slot1 被 C2L 覆盖 C2W、base slot0 被 C1X 覆盖 C1C，码型通道偏差污染 IFLC udbias，导致 tokyo run2 过度固定（1188 vs C 15）与 nagoya run1 固定不足（527 vs C 1594）；修复后 6 组 PPC 数据集 Fix 合计 5025→4250（C 3784），首历元 init 值与 C trace 逐位一致（详见 `RTKLIB_Differences.md` §16.8）
- **ddres/udbias C 对齐**：maxinno 拒绝阈值 `opt.maxinno[code]*threshadj`、统一拒绝处理（`vsat=0, rejc++, continue`）；`varerr()` IFLC 模式 `var *= 9.0`（对齐 demo5 `SQR(3.0)`）
- 诊断插桩清理：relpos/udbias/zdres/ddres 的 [DIAG-TEMP] 日志全部移除

### Added

- **逐卫星 IFLC 频率对协商 [JAVA-ONLY]**：`RtkConfig.enableIflcPairNegotiation`（默认 `false`，关闭时与 RTKLIB C 逐位一致）——两站公共频段协商 + sticky 保持 + 切换按周跳重置模糊度，使流动站缺 B2b 的 BDS-3 卫星以 B1I+B2a 参与 IFLC RTK（PPC-Dataset Tokyo run1 场景；详见 `RTKLIB_Differences.md` §16.6）
- **SPP 优化扩展**：SppOptimizations 系列（详见 `docs/SPP_Extra_Optimizations.md`、`URBAN_CANYON_SPP_TEST_RESULTS.md`）
- **CascadeAR 真逐级重写 [JAVA-ONLY]**：v2.2.x 伪逐级实现（三次LAMBDA共用y/Qb，EWL/WL验证冗余）→ v2.3.0 真逐级固定（每级独立组合变换 z=C·y/Qz=C·Q·Cᵀ + 级间条件更新 + 整数反推 + restamb + 动态ratio阈值），参考 GREAT-PVT gambfix 逐级思想（详见 `ALGORITHM_FIX_LOG.md` FIX-ALG-007）
- **城市峡谷数据诊断测试**：`UrbanCanyonDataCheckTest`、`UrbanCanyonRtkDiagTest`（Net_Diff Urban 单频用例 @Disabled）、`IflcPairNegotiationTest`（协商语义 8 项单元测试）

### Docs

- `RTKLIB_Differences.md`：声明 C 基准为 demo5 EX 2.5.0；新增 §16 compactObsFreq 差异记录与 §16.6 逐卫星 IFLC 频率对协商实施记录；§13/§15 小节编号修正；代码围栏修复
- `RTKLIB_JAVA_TECHNICAL_REFERENCE.md`：修正 NEXOBS（26→2）、thresar 语义（thresar[0]=ratio 阈值）、Joseph 形式伪代码；章节重编号（双 §11/§12 消除）；文件索引改为函数名定位

## [2.2.8] - 2026-10-10

### Fixed

- **Release 工作流**：移除不必要的测试数据下载步骤（Release 仅源码/JAR 打包），v2.2.7 workflow 因此在 Ubuntu 环境失败未产出 Release
- **CI 工作流**：Ubuntu 环境直接使用 bash 命令，移除 pwsh 调用分支

### Changed

- pom 版本 2.2.7 → 2.2.8；推送 v2.2.8 tag 成功触发 Release，4 个 JAR（rtklib-core/adjust/product/stream）正常上传

## [2.2.7] - 2026-10-10

### Added

- **GitHub Actions CI 工作流**：push/PR 自动触发 JDK17 maven test，缓存 test-data，上传测试报告
- **GitHub Actions Release 工作流**：tag v* 触发，版本校验，构建打包，自动创建 GitHub Release 并上传 jar

### Changed

- **README 场景置信度声明**：前置 13 项已实测验证 + 7 项框架已实现待外部数据验证，提前管理使用者预期
- **DATA_GUIDE.md "私有"标注修正**：7 处"私有数据"措辞修正为"含设备ID不可公开"，明确核心障碍是特定场景公开数据不存在
- **ROADMAP.md Phase3 拆分**：场景验证标记为🤝社区贡献项，工程质量为作者可推进项
- **CI 工作流优化**：缓存 glob 从 `*.meta` 修正为 `**/*.meta` 覆盖子目录；增加缓存未命中时自动下载大文件测试数据步骤
- **Release 工作流优化**：合并冗余的 `package -DskipTests` + `test` 为单步 `package`

### Removed

- **删除重复工作流**：移除 GitHub 自动生成的 `maven.yml` 和 `maven-publish-github-packages.yml`，由 `ci.yml` + `release.yml` 统一接管
- **归档开发期文档**：`RTK_TraceLog_Design.md`、`PPP-AR-C-vs-Java-Comparison.md`、`PPP_RTK_Development_Plan.md` 移入 `docs/archive/`

---

## [2.2.6] - 2026-10-10

### Added

- **测试覆盖扩展（基于公共数据）**：使用 GREAT-PVT / MobileGNSS-SPP / IGS 等公开数据集扩展测试用例
  - `VerifyPositioningTest`：新增 RTK-Static、DGPS、RTK-Fixed 模式定位测试
  - `GreatPvtPositioningTest`：新增 PPP-Static、PPP+GPT3+VMF3、PPP+IERS2010、PPP+ISB/IFCB/IFB、PPP-AR+FixHold、RTK-Static 多系统测试
  - `ProductReaderTest`：新增 SP3/CLK/IONEX/OSB/ATX 精密产品文件读取测试
  - `RtkOptimizationsConfigTest`：新增 R2 模糊度锚固、R3 大气冻结、R6 PAR 重选、R7 梯度、R13 参数噪声配置与数值验证

- **测试开发指南**：新增 `docs/TEST_DEVELOPMENT_GUIDE.md`，包含数据准备、.meta 字段枚举、测试模板（RINEX 后处理 / RTCM 流式 / 私有数据）、定位模式速查表

### Changed

- **DATA_GUIDE.md 修正**：
  - 统一 `sample_rate` 字段，删除冗余 `interval`
  - 补全 `scenario`/`role`/`mode`/`systems` 字段完整枚举
  - 修正 GreatPvtPositioningTest / SppCompareTest 数据来源标注为"公开"
  - RTCM 流式 RTK 从"❌根本没有"移至"⚠️有数据但不完整"（本地私有数据已验证，缺可公开数据）
  - 拆分"如何新增测试"章节至独立文档

---

## [2.2.5] - 2026-10-08

### Added

- **SPP 优化功能（基于 MobileGNSS-SPP 项目）**：开关控制式 SPP 增强，通过 `RtkConfig` 四个独立开关启用
  - `enableSppEkf`：EKF 时间传播（位置/速度/加速度），替代逐历元独立 LSQ，实现历元间状态平滑
  - `enableSppRobust`：M-估计抗差（IGGIII核函数），抑制多路径/粗差观测
  - `enableSppZeroVel`：零速约束（速度<0.5m/s 且位置差分<0.05m → 压制过程噪声），提高静态/低速精度
  - `enableSppDopplerSnr`：多普勒+SNR 加权，利用载波平滑降低观测噪声
  - 通过 `SppProcessor.setRtkConfig()` 传入配置，基准 RTKLIB 代码不受影响
  - 测试验证：Street 场景 1256/1256 历元成功，OpenSky 场景 1603/1603 历元成功
  - 测试验证：BDS-only RTCM 230/230 历元成功，EKF 高程平滑效果显著（波动 <0.5m vs LSQ 跳动 ~3m）

### Fixed

- **QZSS 星历解析失败**：`SatUtils.satid2no()` 未实现 QZSS PRN 偏移（RTKLIB C 版 `satid2no` 逻辑），导致 `'J04'` 被解析为无效卫星号
  - 修复：添加 `case 'J': sys = SYS_QZS; prn += MINPRNQZS - 1; break;`
  
- **QZSS 状态向量缺失**：`SppCore.nx()` 和 `sysIdx()` 未计入 `SYS_QZS`，导致 QZSS 钟差不被估计
  - 修复：在 `nx()` 中添加 QZSS 维度计数，在 `sysIdx()` 中添加 QZSS 索引映射
  - 修复：`SppCore.estpos()` 写入 QZSS 钟差至 `sol.dtr[5]`
  - 修复：`SppOptimizations.estposFilter()` EKF 模式下的 QZSS 钟差处理

- **RINEX NAV 文件行长度不足**：导航文件部分行长度 60~79 字符时 `substring(60,80)` 抛出 `StringIndexOutOfBoundsException`
  - 修复：所有行统一填充至 80 字符

- **星历缓冲区溢满**：多星座场景下 NAV 文件可能包含 7000+ 条星历，原 `nmax = MAXSAT * 2 = 428` 严重不足
  - 修复：`Nav.nmax` 扩大至 `MAXSAT * 50`，`Nav.ngmax` 扩大至 `MAXSAT * 10`
  - 实测：Street 场景 6870 条星历正确存储（含 5384 GAL + 429 GPS + 960 BDS + 97 QZS）

- **OBS 解析系统变量误用**：删除局部 `int sys` 后使用类成员 `char sys` 作为系统常量，导致频率索引错误
  - 修复：显式调用 `SatUtils.satsys(obsd.sat, null)` 初始化系统常量

### Changed

- **`Nav.java` 构造函数**：初始化 `cbias` 和 `rbias` 数组，防止 `SppCore.prange()` 空指针
- **`SppProcessorTest` `@BeforeAll`**：容错处理，默认 RTCM 文件不存在时跳过而非崩溃

---

## [2.2.4] - 2026-09-29

### Added

- **rtklib-stream 数据流通讯模块**：与 rtklib-core/product/adjust/otl 平级的新模块，对齐原版RTKLIB `stream.c` 核心功能
  - `NtripClient`：NTRIP v2.0客户端，支持连接Caster获取实时数据流、Sourcetable查询、HTTP Basic认证、断线自动重连（可配置间隔和最大次数）、NMEA GGA上行
  - `NtripClientConfig`：连接配置（主机/端口/挂载点/认证/超时/重连策略/SSL）
  - `SerialPort`：跨平台串口客户端，基于jSerialComm，支持事件驱动异步读取、同步写入、系统串口列举
  - `SerialPortConfig`：串口配置（端口名/波特率/数据位/停止位/校验位/流控/超时）
  - `StreamListener`：统一数据流监听器接口（onData/onError/onClosed）
  - **SSL/TLS支持**：NtripClient支持TLS加密连接，兼容EUREF/IGS等要求HTTPS的Caster；可自定义SSLContext（自签名证书、双向TLS认证）；默认启用TLSv1.2+TLSv1.3
  - 完整中文Javadoc注释，含类说明、使用流程示例、线程模型说明、参数默认值一览

### Changed

- 根pom.xml新增 `rtklib-stream` 模块
- README.md更新：新增rtklib-stream模块说明、更新功能边界描述、更新补充说明
- RTKLIB_Differences.md更新：第14.1节数据流与网络协议表更新NTRIP客户端和串口为已实现

---

## [2.2.3] - 2026-09-26

### Added

- **紧凑SSR/CLAS解码器**（`org.rtklib.java.cssr`包）：QZSS CLAS L6专有格式解码
  - `CompactSsrDecoder`：L6帧头解析+MT4073子类型1-12全消息解码（Mask/Orbit/Clock/CBias/PBias/Bias/URA/STEC/Grid/Combined/Atmos/HrClock）
  - `LocalCorr`：本地改正数据结构（STEC多项式系数+Trop函数项+网格残差）
  - `GridDefinition`：网格坐标读取+最近点查找+双线性插值权重
  - `CssrMessageType`：消息类型枚举
  - 全局改正（轨道/钟差/偏差）解码后自动写入`Nav.ssr[]`，复用现有`SsrCorrector`
  - 本地改正（STEC+Trop）通过`getStec()`/`getTrop()`多项式插值+网格残差获取
  - `RtkConfig.enableCompactSsr`开关控制，默认关闭
  - 参考实现：CSSRlib（Rui Hirokawa, Python）

- **TraceLog V2 全模式日志追踪系统**：基于topic+action字符串的高层事件追踪，覆盖SPP/RTK/PPP/PPP-AR/PPP-RTK/Adjust全模式
  - `Trace.emit(topic, action, cfg, cb, epoch, time, kv...)`：唯一入口，6层过滤+拼装+诊断+统计
  - `Trace.summary(cfg, cb)`：汇总输出（total/success/fail/fail_reasons/fix/float/conv_epoch/max_ar_shift）
  - `TraceConfig`：V2配置（topics白名单/actions/samplerate/maxEpoch/maxOutputLines/targetSats/refEcef/convergenceThresh/arShiftWarnThresh）
  - `TraceStats`：累积统计（totalEpochs/successEpochs/failEpochs/failReasons/convEpoch/fixCount/floatCount/maxArShift）
  - 3条内联诊断：①位置偏差d_dN/d_dE/d_dU/d_d3D/d_conv（自动计算，需设refEcef）②AR偏移告警d_WARN=LARGE_SHIFT ③失败原因累积failReasons
  - 收敛历元检测：连续N个历元d3D<thresh时记录convEpoch
  - V1/V2共存：V1（RtkTrace/PppTrace+TraceControl）输出详细阶段数据（H矩阵/R对角线/逐卫星bias），V2输出高层事件+诊断，共享TraceCallback
  - `TraceControl.toTraceConfig()`：V1→V2桥接，一键迁移

- **V2全引擎集成**：29处`Trace.emit()`调用覆盖4个处理引擎
  - RtkCore：11处（SATELLITE/BASELINE/AMBIGUITY/FILTER/AR/POSITION/RESULT）
  - PppCore：7处（SATELLITE/AMBIGUITY/FILTER/POSITION/RESULT）
  - PppCoreEx：7处（同PppCore）
  - SppProcessor：4处（SATELLITE/POSITION/RESULT）

- **V1 RtkCore完整集成**：7处`RtkTrace.traceStage0~6()`调用补全（此前RtkCore中一处V1调用都没有）

- **V2 setter传播**：RtkProcessor/RinexRtkProcessor/PppProcessor/SppProcessor均新增`setTraceConfig()`+`setTraceCallback()`

- **Rtk新增`traceConfig`字段**：与V1的`traceControl`并列，独立控制V2输出

### Changed

- `TraceCallback`新增`default void onLine(String line)`委托给`onTrace()`，V1完全兼容
- `TraceControl`新增`toTraceConfig()`桥接方法，V1 stages位掩码→V2 topics集合

### Verified

- 全项目编译通过（core+adjust+product+otl） ✅

### Milestone

**v2.2.3 标志 GNSS 主要算法内容已完善。** SPP/RTK/PPP/PPP-AR/PPP-RTK 五种定位模式、多基线间接平差、轨道计算、精密产品下载、对流层模型（GPT3+VMF3）、海洋潮汐（BLQ/OTL）、日志追踪（V1+V2）等核心功能均已实现并集成。后续版本主要工作转入**测试验证与Bug修复**，重点包括：SP3星历GEO卫星兼容性、PPP-AR固定率提升、V2日志追踪实测验证、测试数据硬编码路径清理等。

---

## [2.2.2] - 2026-09-25

### Fixed

- **PPP精确模式历元失败率过高（P0修复）**：精确星历模式下75%历元失败的根本原因是SP3产品未覆盖部分BDS GEO卫星
  - **根因**：WUM0MGXRAP SP3产品不含BDS GEO卫星（C01/C02/C03/C07/C09），导致这些卫星位置计算返回(0,0,0)，仰角=-89.8°被剔除，仅剩3颗MEO/IGSO卫星低于MIN_NSAT_SOL(4)要求
  - **修复**：`EphModel.satposPrec()` 增加广播星历降级策略，当精确星历不可用时自动回退到广播星历
  - **效果**：历元成功率从25%提升至75.8%（3倍改进），可用卫星数从3颗增至8颗
  - **实现细节**：新增 `satposBrdcFallback()` 辅助方法，在Sp3Reader.pephpos()或pephclk()失败时调用satposBrdc()获取广播星历位置和钟差
  - **已知限制**：降级卫星使用广播星历精度较低，影响PPP-AR模糊度收敛速度；当前WL固定率仍<1%，需进一步优化（P1问题）
  - 修改文件：`EphModel.java:445-517`

- **PPP-AR Fix-and-Hold缺少最小连续固定历元检查（P2修复）**：`pppArFixHoldMinEp=50`参数已定义但从未被检查
  - **根因**：`PppAmbFix.pppArFixHold()`未检查`rtk.nfix`，导致首次FIX即收紧方差，可能因错误固定导致发散
  - **修复**：在`pppArFixHold()`入口添加`rtk.nfix < cfg.pppArFixHoldMinEp`守卫条件；在`PppCoreEx`中正确管理`rtk.nfix`计数器（FIX时递增，非FIX时归零）
  - **效果**：Fix-and-Hold现在需要连续50个FIX历元后才收紧方差，避免过早锁定
  - 修改文件：`PppAmbFix.java:268`, `PppCoreEx.java:188-197`

- **PPP Partial AR模糊度固定单位错误（P3修复）**：`pppPartialAR()`在米空间直接对浮点值取整，数学上无意义
  - **根因**：LAMBDA方法要求输入为循环（cycle）空间的模糊度（整数特性），但原代码直接使用米空间的`rtk.x[idx]`并`Math.round()`，对不同波长的卫星产生错误固定值
  - **修复**：收集阶段存储每颗卫星的λ₁；构建LAMBDA输入时将y[]和Q[][]从米空间转换到L1循环空间（`y[i]/=lam1`, `Q[i][j]/=(lam1_i*lam1_j)`）；固定后将结果转回米空间（`Math.round(b[i])*lam1`）
  - **效果**：Partial AR现在正确地在L1循环空间执行整数模糊度搜索，固定值物理意义正确
  - 修改文件：`PppAmbFix.java:321-438`

- **SppCore.gettgd() BDS卫星索引越界**：使用`nav.eph[sat-1]`直接索引对BDS卫星（sat=106-114）失败
  - **修复**：改为线性搜索匹配`.sat`字段
  - 修改文件：`SppCore.java:82-100`

- **RinexParser日志误导**：`nav.n`标注为GPS、`nav.na`标注为BDS，实际分别为总星历数和Almanac数
  - **修复**：更正日志标签为Total和Almanac
  - 修改文件：`RinexParser.java:158-159`

---

## [2.2.1] - 2026-09-22

### Added

- **RTK阶段高级模糊度固定优化（6项）**：借鉴GREAT-PVT/PRIDE PPP-AR，通过RtkConfig独立开关控制，默认全部关闭
  - **逐级模糊度固定（Cascade AR, EWL→WL→NL）**：`enableCascadeAR`，利用多频波长差异逐级约束固定模糊度，提升RTK固定连续性和精度
  - **精细化残差编辑与周跳检测**：`enableResidualEdit`，残差时序迭代跳变检测、伪距-载波一致性校验、弧段完整性筛查
  - **部分模糊度固定（Partial AR）**：`enablePartialAR`，全局Ratio检验失败后剔除劣质模糊度维度，保留可信子集完成固定
  - **Bootstrapping成功率联合判据**：`enableBootstrapping`，作为Ratio的互补判据，与Ratio联合判定模糊度固定可靠性
  - **BDS卫星码偏差改正（Wanninger模型）**：`enableBdsCodeBias`，针对BDS GEO/IGSO/MEO卫星码偏差改正，提升BDS固定成功率
  - **参数类型级自适应过程噪声**：`enableParamTypeNoise`，按物理参数区分噪声模型（ZTD随机游走/钟差白噪声/电离层随机游走）
  - 实现类：`RtkOptimizationsCascadeAR`/`RtkOptimizationsResEdit`/`RtkOptimizationsPartialAR`/`RtkOptimizationsBootstrap`/`RtkOptimizationsBdsBias`
  - 侵入方式：RtkCore中8个`if(cfg.enableXxx)`分支，默认关闭时与原版RTKLIB行为完全一致
  - 27个新增单元测试全部通过

- **BDS/Galileo频率常量**：`Constants.FREQB1I`/`FREQB2I`/`FREQB3I`/`FREQE1`/`FREQE5a`，供级联AR和BDS码偏差使用

- **RtkConfig新增20+个开关和参数**：级联AR各级Ratio阈值、残差编辑阈值、部分AR参数、Bootstrapping成功率阈值、BDS码偏差高度角阈值、过程噪声参数等

- **Rtk新增诊断变量**：`cascadeArFixLevel`/`cascadeArLastRatio`/`diagCascadeArEwlFixCount`等，用于优化效果评估

---

## [2.2.0] - 2026-09-22

### Added

- **轨道模块（org.rtklib.java.orbit）**：完整的卫星轨道计算功能
  - SGP4/SDP4轨道传播器：逐行移植自python-sgp4，支持近地/深空/同步轨道
  - TLE解析器：支持文件读取和两行字符串解析
  - 轨道六根数转换：rv2coe/coe2rv，移植自Orekit 12的KeplerianParametersConverter
  - 开普勒方程求解：Halley修正牛顿法，2次迭代达机器精度
  - 二体轨道传播器：KeplerPropagator，仅推进平近点角
  - 统一转换入口：TleConverter（TLE↔六根数、TLE↔状态向量）
  - 数据容器：OrbitalElements、StateVector、Frame（TEME）
  - 验证：Vallado标准14用例全通过，86颗真实TLE卫星0失败，rv2coe往返误差<0.001km
  - 文档：`ORBIT_MODULE_REFERENCE.md`

---

## [2.1.1] - 2026-09-19

### Added

- **基站稳定性监测（BaseStationMonitor）**：独立工具类，检测基站天线位移并提醒用户
  - 一级检测：60min窗口SPP中位数 vs 自学习参考基准，偏移>10m触发突变告警
  - 二级检测：历史中位数环形缓冲区趋势分析，漂移>5m触发漂移告警
  - 4种数据输入：`onBaseObs`(Obsd数组)、`onRtcmData`(RTCM字节)、`onRtcmFile`(RTCM文件)、`onRinexFile`(RINEX文件)
  - 回调接口`BaseMonitorCallback`：4个default方法（onSppResult/onWindowResult/onBaseMovement/onBaseDrift）
  - 配置类`BaseMonitorConfig`：10个参数（窗口时长、突变阈值、漂移阈值、缓冲区大小等）
  - 完全独立：不绑定RtkProcessor/RtkConfig/Rtk，1基站1实例，多基线共享不重复
  - 实测验证：基站连续6小时，窗口偏移0.00~3.22m，10m阈值下无误报

- **RTK参数敏感性分析**：量化8个PrcOpt参数对FIX率的影响幅度
  - 影响排序：频点数(+68.7%) > 对流层模型(+40.6%) > AR锁定计数(+56.2%) > 定位模式(+22.1%) > 电离层模型(-76.6%) > AR ratio阈值(-14.9%) > AR最小FIX计数(-8.8%) > 潮汐/PCV(0%)
  - 不同站点类型推荐配置（短基线/中长基线/动态/差数据）
  - 文档：`RTK_Parameter_Sensitivity.md`

- **SNR中位数计算顺序修复**：先赋值SNR再计算中位数（原先顺序反，导致读取上一历元值）

- **P0 基线质量加权**：`BaselineEntry.qualityFactor`，根据ratio/numSat/age/DOP对低质量FIX基线降权
  - `BaselineEntry.computeQualityFactor(SolData)`：综合质量因子计算（0.01~1.0）
  - `CovAssembler.assembleGlobalR(epoch, qualityWeight)`：R矩阵对角块按qualityFactor缩放
  - `GnssBaselineAdjust.adjust(epoch, qualityWeight)`：启用质量加权的平差重载
  - 效果：σ₀更稳健，减少假固定对平差结果的污染

- **P1 逐卫星诊断数据提取**：扩展 `SatObsData`，按 `PrcOpt.diagMask` 位掩码控制输出
  - `DIAG_SAT_RESIDUAL`(1)：逐卫星伪距残差resp、载波残差resc
  - `DIAG_SAT_AMBIGUITY`(2)：逐卫星模糊度浮点值amb、标准差stdA
  - `DIAG_SAT_CYCLESLIP`(4)：逐卫星周跳标志slip、GF/MW组合值、拒绝计数rejc
  - `SatObsData.fromSsat(ssat, nf, diagMask)`：按掩码提取诊断字段
  - 未启用时字段为NaN/0，零开销向后兼容

- **P3 H矩阵位置分量提取**：`Sol.hPos`（3×3等效设计矩阵）
  - `DIAG_HPOS`(8)：从relpos()中H[:,0:3]和R计算hPos = (HᵀWH)⁻¹HᵀW
  - `CovAssembler.assembleDesignMatrix(epoch)`：使用各基线hPos替代I₃
  - 无hPos时退化为I₃，与修改前行为一致

- **新息向量摘要提取**：`Sol.innovRms`/`innovMax`/`ddObsCount`
  - `DIAG_INNOVATION`(16)：新息RMS = √(vᵀR⁻¹v/nv)，最大标准化新息，双差观测数

- **PrcOpt新增字段**：`diagMask`（诊断掩码，默认0）、`qualityWeight`（质量加权开关，默认true）

### Changed

- `SolData` 新增 `hPos`/`innovRms`/`innovMax`/`ddObsCount` 字段，新增 `diagMask` 构造函数
- `RtkProcessor` 所有 `new SolData` 调用传入 `opt.diagMask`
- `AdjustRealDataTest.buildBaselineEntry()` 计算qualityFactor，平差启用qualityWeight

### Verified

- rtklib-core 编译通过 ✅
- rtklib-adjust 编译通过 ✅
- GnssBaselineAdjustTest 8 tests passed ✅
- AdjustRealDataTest 8 tests passed ✅
- diagMask=0 时行为与修改前完全一致 ✅

---

## [2.0.7] - 2026-09-19

### Added

- **rtklib-adjust 实测数据验证**：新增 `AdjustRealDataTest`，使用2基站1测站连续9小时RTCM实测数据完整验证多基线平差功能
  - `testMultiBaselineAdjust9Hours()`：2基站1测站连续9小时RTK解算+多基线平差，验证FIX比例、σ₀分布、Baarda T统计量
  - `testSppBaseStationPosition()`：SPP定位基站坐标，与RTCM 1005对比，验证基站坐标偏差检测
  - `testStaticSolveBaseA()`：静态模式解算基站精确坐标，验证基站坐标修正后平差σ₀收敛
  - `testAdjustWithSppBaseAOnly()` / `testAdjustWithSppBaseBOnly()`：单基站SPP替换验证，对比修正前后σ₀变化
  - `testCrossValidationStation0002()`：交叉验证第二测站基线一致性
  - `testAdjustWithCorrectedBaseB()`：修正基站坐标后平差验证
  - 数据配置通过 `test-data.properties` 管理（模板 `test-data.properties.template`），不提交到仓库

- **基站异常诊断功能**：新增 `BaseStationDiagnoser`，当σ₀持续超限时自动定位异常基站并给出建议坐标
  - 2基线场景：SPP定位辅助判断异常方向
  - k≥3基线场景：残差分析定位异常基站
  - 静态解算给出建议坐标（mm级相对精度）
  - 滑动窗口+超限比例触发机制，避免偶然异常误触发
  - 诊断结果缓存，修正坐标后可清除重新验证
  - 回调推送，每个基站只推送一次

- **数据源抽象**：新增 `GnssDataSource` 接口及三种实现
  - `RtcmFileDataSource`：事后RTCM文件，支持多文件（多小时数据）
  - `RinexFileDataSource`：事后RINEX文件，需obs+nav文件
  - `RtcmMemoryDataSource`：实时RTCM流，逐帧feed累积数据
  - 统一接口：SPP定位、RTK基线解算、静态解算、天线坐标提取

- **诊断模型**：新增 `AdjustDiagnosisConfig`（诊断配置）、`BaseStationDiagnosis`（诊断结果）、`BaseDiagnosisHandler`（回调接口）
  - 异常等级：NORMAL / WARNING / ERROR / CRITICAL / UNKNOWN
  - 3D偏差阈值：<0.5m NORMAL、0.5~2m WARNING、2~5m ERROR、>5m CRITICAL

- **基站诊断测试**：新增 `BaseStationDiagnoserTest`，验证2基站诊断流程和缓存机制

### Verified

- 实测数据：2基站1测站，连续9小时RTCM数据 ✅
- 多基线平差：双FIX历元平差成功，σ₀分布合理 ✅
- 基站坐标偏差检测：SPP定位与RTCM 1005对比，成功识别异常基站 ✅
- 静态解算修正：修正基站坐标后σ₀显著收敛 ✅
- 基站诊断器：异常基站定位、建议坐标生成、缓存机制 ✅

---

## [2.1.0] - 2026-09-16

### Added

- **RinexObsWriter 新增 obstype 观测类型选项**（对齐 RTKLIB C 版 convbin/rtkconv 的 obstype 选项）
  - 新增位标志常量：`OBSTYPE_PR`(0x01)、`OBSTYPE_CP`(0x02)、`OBSTYPE_DOP`(0x04)、`OBSTYPE_SNR`(0x08)、`OBSTYPE_ALL`(0x0F)
  - 新增 `setObstype(int)` 方法，控制 RINEX 3.x 输出中包含哪些观测类型（C/L/D/S）
  - 默认值 `OBSTYPE_ALL`（输出 CLDS），与 RTKLIB rtkconv GUI 默认行为一致
  - `collectObsCodes()` 根据 obstype 位标志动态生成观测类型前缀列表

- **RtcmToRinexConverter 新增 obstype 传递**
  - 新增 `obstype` 字段（默认 `OBSTYPE_ALL`）及 `setObstype(int)` setter
  - `initializeWriters()` 中创建 `RinexObsWriter` 后立即调用 `setObstype()` 传递选项

- **RtcmFileToRinexConverter 新增 obstype 传递**
  - 新增 `obstype` 字段（默认 `OBSTYPE_ALL`）及 `setObstype(int)` setter
  - `convert()` 中创建 `RtcmToRinexConverter` 后调用 `setObstype()` 传递选项

### Fixed

- **[#1](https://github.com/jinyuttt/rtklib_java/issues/1) RinexObsWriter 缺失 C 版的 obstype 观测类型开关机制：SNR(S*) 与多普勒(D*) 无法输出，写入端 'S'/'D' 分支成为死代码**
  - 原因：`collectObsCodes()` 硬编码仅生成 C 和 L 前缀，缺少 D 和 S，导致 RINEX 头部无 D*/S* 观测类型声明，写入端 `typePrefix == 'D'` / `typePrefix == 'S'` 分支永远不会执行
  - 修复：通过 obstype 位标志动态控制输出前缀，默认包含全部 CLDS，与 RTKLIB C 版 convbin/rtkconv 的 obstype 选项对齐
  - 附修：`RtcmToRinexConverter` obstype 设置时序问题 — `obsWriter` 在 `convert()` 内部 `initializeWriters()` 中创建，外部无法在创建后、写入前设置 obstype；改为在 `RtcmToRinexConverter` 中保存 obstype 字段，`initializeWriters()` 创建 obsWriter 后立即传递
  - 影响链路：`RtcmFileToRinexConverter` → `RtcmToRinexConverter` → `RinexObsWriter`

### Changed

- **GnssBaselineAdjustTest 适配 EJML API 变更**
  - `getNumCols()` → `numCols()`，适配 EJML 0.41 矩阵方法名

### Verified

- 编译通过：`mvn compile -pl rtklib-core` ✅
- 测试通过：`RinexObsWriterTest`（2 用例） ✅
  - `testObstypeAll()`：默认 OBSTYPE_ALL 输出包含 C2I、L2I、D2I、S2I ✅
  - `testObstypeCL()`：仅 OBSTYPE_PR|OBSTYPE_CP 输出包含 C2I、L2I，不含 D2I、S2I ✅

---

## 修改文件清单

| 文件 | 变更类型 | 说明 |
|------|---------|------|
| `rtklib-core/.../rinex/RinexObsWriter.java` | 修改 | 新增 obstype 常量/字段/setter，修改 collectObsCodes() 动态生成观测类型 |
| `rtklib-core/.../rinex/RtcmToRinexConverter.java` | 修改 | 新增 obstype 字段/setter，initializeWriters() 中传递给 obsWriter |
| `rtklib-core/.../rinex/RtcmFileToRinexConverter.java` | 修改 | 新增 obstype 字段/setter，convert() 中传递给 RtcmToRinexConverter |
| `rtklib-core/.../RinexObsWriterTest.java` | 新增 | 验证 obstype 选项功能的测试用例 |
| `rtklib-adjust/.../GnssBaselineAdjustTest.java` | 修改 | 适配 EJML 0.41 API |

---

## 使用示例

### 默认行为（输出全部 CLDS）

```java
RtcmFileToRinexConverter converter = new RtcmFileToRinexConverter(3.05, outputDir, "BASE");
converter.convert(rtcmFilePath);
// 输出包含 C2I L2I D2I S2I 等全部观测类型
```

### 仅输出伪距和载波（CL）

```java
RtcmFileToRinexConverter converter = new RtcmFileToRinexConverter(3.05, outputDir, "BASE");
converter.setObstype(RinexObsWriter.OBSTYPE_PR | RinexObsWriter.OBSTYPE_CP);
converter.convert(rtcmFilePath);
// 输出仅包含 C2I L2I，不含 D2I S2I
```

### 直接使用 RinexObsWriter

```java
RinexObsWriter writer = new RinexObsWriter(3.05, obsFile, sta);
writer.setObstype(RinexObsWriter.OBSTYPE_PR | RinexObsWriter.OBSTYPE_CP);
writer.setObsData(obs);
writer.write();
```

### 通过 RtcmToRinexConverter

```java
RtcmToRinexConverter converter = new RtcmToRinexConverter(3.05, outputDir, "BASE");
converter.setObstype(RinexObsWriter.OBSTYPE_PR | RinexObsWriter.OBSTYPE_CP | RinexObsWriter.OBSTYPE_DOP);
converter.convert(rtcmData, len);
// 输出包含 C L D，不含 S
```