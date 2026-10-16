# RTKLIB Java 算法修复记录

本文件记录**自 v2.3.0 起**所有改变算法数值行为的修改，供定版阶段逐条备查与对齐审查。

> 记录范围与 CHANGELOG（版本变更总览）、`RTKLIB_Differences.md`（与 C 版的长期差异清单）互补：
> - **CHANGELOG.md**：面向用户的版本级变更摘要
> - **RTKLIB_Differences.md**：Java 与 C 版的长期性差异声明
> - **本文件**：每一次算法修改的完整档案（C 版依据、根因、修改内容、验证结果）

---

## 记录规则

### 什么必须记录

自 v2.3.0 起，以下改动**必须**在本文档追加记录：

1. 核心 Core 文件中改变数值行为的修改：状态向量/协方差传播、观测方程、残差与方差、滤波更新、AR 决策、时间系统、坐标转换、星历计算、误差模型等
2. 对齐性修复：任何为对齐 RTKLIB 2.5.0 C 版行为而做的修改
3. JAVA-ONLY 扩展中影响默认关闭路径之外数值输出的部分（如 `RtkConfig` 开关控制的优化，即使默认关闭也应记录）
4. 改变运算顺序或矩阵分解实现的重构（如 Joseph form 乘法序调整），需附数值等价性验证
5. 常量定义修改（阈值、方差、多项式系数等）

### 什么不需要记录

- 测试代码修改（含测试自身 bug 修复）
- 日志/trace/插桩的增删（不改变数值行为）
- 文档、注释、格式化
- 纯代码搬运（字节级等价的位置调整）

### 记录时机与格式

- 修改合入工作树后、提交前，由修改者按下方模板追加条目，ID 递增（FIX-ALG-NNN）
- 一条修改一个条目；一次提交含多个独立算法修改时拆分为多条
- **每条必须包含 C 版依据**（源文件 + 行号 + 语义说明）——无法给出 C 依据的改动须明确标注 JAVA-ONLY 及其开关控制

### 条目模板

```
### FIX-ALG-NNN：<标题>
- 日期：
- 模块/位置：<文件路径 + 函数/行号>
- 类别：C 对齐修复 | 缺陷修复 | JAVA-ONLY 扩展 | 重构等价
- C 版依据：<源文件、行号、代码/语义>
- 问题现象：<数据集 + 量化表现>
- 根因：
- 修改内容：<before/after>
- 验证：<测试命令 + 量化结果>
- 对齐结论：<与 C 逐值一致/等价性说明>
```

---

## 修复记录

### FIX-ALG-001：pppos 排除标志数组 exc 跨历元泄漏修复

- **日期**：2026-10-14
- **模块/位置**：`rtklib-core/src/main/java/org/rtklib/java/ppp/PppCore.java`，`pppos()` 入口（ensureInit 之后）
- **类别**：C 对齐修复（状态生命周期）
- **C 版依据**：`RTKLIB-2.5.0/src/ppp.c` L1185 `pppos()` 局部变量声明：
  ```c
  int i,j,nv,info,svh[MAXOBS],exc[MAXOBS]={0},stat=SOLQ_SINGLE;
  ```
  `exc` 为局部数组，每个 pppos 调用（每历元）重新构造并置零——排除标志仅在历元内迭代间累积，永不跨历元。
- **问题现象**：GREAT-PVT PPP 数据集（`GreatPvtPositioningTest`）testPppKinematic/testPppStatic 有解率劣化。调试日志显示 ep2 起 `pppRes sat=4 sys=1 vs=1 satexcl=0 exc=1`——卫星健康（vsat/satexcl 均正常）却被 exc 标志排除；nv 逐历元死亡螺旋（13→3→0），PPP 长期无解。
- **根因**：Java 侧 `PppWorkBuffer` 跨历元复用（`wb.exc` 仅在首次 `ensureInit()` 分配），而 `pppRes` 的剔除结果写入 `exc` 后无任何清零点。ep1 内 `pppRes` 产生的 prefit maxinno 剔除（exc=1）泄漏至 ep2 及之后所有历元，健康卫星被静默排除。
  此 bug 在历史版本中潜伏未暴露：仅当 ep1 恰好发生残差剔除时才触发，属于条件触发的隐性数值分叉。
- **修改内容**：`pppos()` 入口、工作缓冲取出之后新增：
  ```java
  int[] exc = wb.exc;
  /* C ppp.c pppos(): exc[MAXOBS]={0} is re-initialized at each pppos entry,
   * so exclusions accumulate only across iterations within an epoch and
   * never leak into the next epoch (work buffer is reused across epochs) */
  java.util.Arrays.fill(exc, 0);
  ```
- **验证**：`mvn test -pl rtklib-core "-Dtest=GreatPvtPositioningTest#testPppKinematic+testPppStatic"`
  - Tests run: 2, Failures: 0
  - testPppKinematic solved=1/30（与 HEAD 基线一致）
  - testPppStatic solved=14/30（HEAD 基线为 1/30）
  - nv 轨迹恢复健康：13→10→12→10→10
- **对齐结论**：与 C 版 ppp.c `pppos()` 的 exc 生命周期语义完全一致（每历元重置，历元内迭代累积）。诊断用临时日志（AMB-RESET/SLIP-LLI/SLIP-MW/MW-DIAG 等）已同步清理，`mwmeas` 恢复 C 版二参数签名。
- **同类风险提示**：工作缓冲复用数组（`PppWorkBuffer` 等）凡对照 C 版为"局部变量每历元重建"语义的，均须在使用入口清零。`wb.svh` 由 `satposs` 每历元全量写入，暂无泄漏风险；新增复用数组时需按此模式审查。

### FIX-ALG-002：compactObsFreq 移除——IFLC 频槽混合根因修复

- **日期**：2026-10-13
- **模块/位置**：`rtklib-core/src/main/java/org/rtklib/java/rtk/RtkCore.java`，`rtkpos()` 中 `compactObsFreq` 调用
- **类别**：C 对齐修复（观测槽位语义）
- **C 版依据**：RTKLIB-2.5.0 C 版无 `compactObsFreq`——观测槽位由 `rinex.c set_index()` / `rtcm.c sigindex()` 在输入阶段固定，运行时 `rtkpos()` 不做槽位压缩。Java 版曾添加 `compactObsFreq(obs, n)` 作为 JAVA-ONLY 扩展，按每颗卫星"有数据的槽位"重排，导致双站 slot 索引指向不同频率。
- **问题现象**：PPC-Dataset Tokyo run1 IFLC RTK Fix 率 0%。双站 slot1 指向不同频率（流动站 B2a vs 基准站 B2b），IFLC 双差 bias≈1e6 m 毒化全部模糊度，Float 发散。
- **根因**：`compactObsFreq` 对 rover 和 base 独立压缩槽位，两站观测类型不同时同一 slot 索引指向不同频率，破坏双差观测方程的频率一致性前提。
- **修改内容**：移除 `rtkpos()` 中的 `compactObsFreq` 调用；方法本体保留（不影响其他路径）。
- **验证**：PPC-Dataset Tokyo run1 Fix 率从 0% 恢复（详见 `RTKLIB_Differences.md` §16）。
- **对齐结论**：与 C 版 `rtkpos()` 行为一致——运行时不做槽位压缩。

### FIX-ALG-003：satid2no 空格填充 PRN 解析修复

- **日期**：2026-10-13
- **模块/位置**：`rtklib-core/src/main/java/org/rtklib/java/rtkcmn/SatUtils.java`，`satid2no()`
- **类别**：C 对齐修复（RINEX 导航文件解析）
- **C 版依据**：`RTKLIB-2.5.0/src/rtkcmn.c` L475-501 `satid2no()` 使用 `sscanf("%c%d")`，`%d` 自动跳过前导空白。RINEX 3 导航文件按 `G%2d` 格式写出单位数 PRN（如 "G 5"），`sscanf` 正确解析为卫星号 5。
- **问题现象**：PPC-Dataset Tokyo run1 Fix 率 0%（在 FIX-ALG-002 修复前已暴露）。RINEX 3 导航文件中 "G 5"/"E 4"/"R 7" 等单位数 PRN 被 `Integer.parseInt` 抛 NumberFormatException，`satid2no()` 返回 0（无效），全部单位数 PRN 广播星历被静默丢弃 → satposs rs=0 → 双站 azel=0 → DD 卫星数坍缩（Tokyo run1 selsat ns 21→9）→ posvar 超过 thresar[1] → AR 永不触发。
- **根因**：Java `Integer.parseInt(" 5")` 不跳过前导空格（与 C `sscanf %d` 语义不同），抛异常后被 catch 吞掉。
- **修改内容**：`satid2no()` 解析前 `trim()`，对齐 C `sscanf("%c%d")` 空白跳过语义。
- **验证**：PPC-Dataset Tokyo run1 Fix 0→570、Float 8986→10927、成功率 85.8%→97.9%。
- **对齐结论**：与 C 版 `rtkcmn.c satid2no()` 语义完全一致。

### FIX-ALG-008：RtkCore relpos azel/freq 跨历元泄漏修复

- **日期**：2026-10-16
- **模块/位置**：`rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java`，`relpos()` 入口（ensureInit 之后、satposs 之前）
- **类别**：C 对齐修复（状态生命周期）
- **C 版依据**：`RTKLIB-2.5.0/src/rtkpos.c` L1986 `relpos()` 局部变量分配：
  ```c
  azel=zeros(2,n);        /* [az, el] */
  freq=zeros(nf,n);
  ```
  `azel` 和 `freq` 为 `zeros()`（即 `calloc`）分配，每历元调用 `relpos` 时自动零初始化。若某卫星在 `zdres` 中因 `r<=0`/`el<elmin`/`satexclude` 被 `continue` 跳过，其 azel/freq 槽位保持为 0，不会影响后续逻辑。
- **问题现象**：当某卫星在上一历元有效（azel 有值）但在当前历元因 `geodist` 失败（`r<=0`，通常无有效星历）被 `zdres` 跳过时，`wb.azel` 对应槽位残留上历元值。`selsat()` 中 `azel[1+j*2]>=opt.elmin` 检查可能将该卫星误选，导致 `ns` 膨胀。注意：`zdres` 中 `r>0` 时 azel 已被写入当前历元值（即使后续因 `el<elmin`/`satexclude` 被 continue），故残留仅发生在 `r<=0` 场景。`ddres` 中 `validobs` 会过滤 y=0 的卫星（不参与双差），但 `udbias` 无 `validobs` 防护——误选卫星会进入 `detslpDop/detslpCode/detslpLl/detslpGf` 周跳检测循环（各子函数有 `L[f]==0||D[f]==0`/`gf1==0.0` 防护，不会产生错误周跳标记，但增加无效计算开销）。
- **根因**：Java 侧 `RtkWorkBuffer` 跨历元复用（`wb.azel/wb.freq` 仅在首次 `ensureInit()` 分配为 Java 默认 0.0），但历元间不清零。C 版每次 `relpos` 调用 `azel=zeros(2,n)` 重新零初始化，而 Java 版复用工作缓冲区无重置机制。
  此 bug 属条件触发：仅当卫星在历元间有效性变化（上一历元有效→当前历元 `geodist` 失败）时才暴露，典型场景为星历过期/切换。影响为 `ns` 膨胀及无效计算开销，不直接导致数值结果错误（下游有 validobs/L==0/gf==0 防护），但与 C 版行为不一致。
- **修改内容**：`relpos()` 入口、工作缓冲取出之后、`satposs` 之前新增：
  ```java
  /* C rtkpos.c relpos(): azel=zeros(2,n), freq=zeros(nf,n) — both
   * zero-initialized each call. Work buffer reuse leaves stale values
   * from the previous epoch; if a satellite is skipped by zdres (r<=0,
   * el<elmin, satexclude), its azel/freq slot retains the old value,
   * which can fool selsat (azel[1+j*2]>=elmin) or ddres. */
  Arrays.fill(azel, 0, n * 2, 0.0);
  Arrays.fill(freq, 0, n * nf, 0.0);
  ```
- **验证**：`mvn test -pl rtklib-core "-Dtest=RtkOptimizationsConfigTest,RtkOptimizationsBootstrapTest"` — Tests run: 19, Failures: 0
- **对齐结论**：与 C 版 `rtkpos.c relpos()` 的 azel/freq 生命周期语义完全一致（每历元零初始化）。
- **同类风险提示**：`RtkWorkBuffer` 中其他数组（`rs/dts/vare/svh`）由 `satposs` 每历元全量写入无泄漏风险；`y` 由 `zdres` 入口循环清零；`e` 仅在 `zdres` 中对 `selsat` 选中卫星写入，未被选中的残留值不会被 `ddres` 访问；`vflg` 按 `nv` 索引读写，残留值不在有效范围内。当前仅需 azel/freq 清零。

### FIX-ALG-009：PppCore pppos azel 跨历元泄漏修复

- **日期**：2026-10-16
- **模块/位置**：`rtklib-core/src/main/java/org/rtklib/java/ppp/PppCore.java`，`pppos()` 入口（ensureInit 之后）
- **类别**：C 对齐修复（状态生命周期）
- **C 版依据**：`RTKLIB-2.5.0/src/ppp.c` L1199 `pppos()` 局部变量分配：
  ```c
  azel=zeros(2,n);
  ```
  `azel` 为 `zeros()`（即 `calloc`）分配，每历元调用 `pppos` 时自动零初始化。
- **问题现象**：当某卫星在上一历元有效（azel 有值）但在当前历元因 `geodist` 失败（`r<=0`）被 `pppRes` 跳过时，`wb.azel` 对应槽位残留上历元值，可能污染下游高度角依赖逻辑（如对流层映射函数、电离层映射等）。注意：`pppRes` 中 `r>0` 时 azel 已被写入当前历元值，故残留仅发生在 `r<=0` 场景。
- **根因**：与 FIX-ALG-008 同源——Java 侧 `PppWorkBuffer` 跨历元复用，`wb.azel` 无重置机制。C 版每次 `pppos` 调用 `azel=zeros(2,n)` 零初始化。
- **修改内容**：`pppos()` 入口、工作缓冲取出之后新增：
  ```java
  /* C ppp.c pppos(): exc[MAXOBS]={0} and azel=zeros(2,n) are
   * re-initialized at each pppos entry. Work buffer reuse leaves stale
   * values from the previous epoch; if a satellite is skipped by pppRes
   * (r<=0, el<elmin, satexclude), its azel slot retains the old value,
   * which can corrupt downstream elevation-dependent logic. */
  java.util.Arrays.fill(exc, 0);
  java.util.Arrays.fill(azel, 0, n * 2, 0.0);
  ```
- **验证**：`mvn test -pl rtklib-core "-Dtest=RtkOptimizationsConfigTest,RtkOptimizationsBootstrapTest"` — Tests run: 19, Failures: 0
- **对齐结论**：与 C 版 `ppp.c ppos()` 的 azel 生命周期语义完全一致（每历元零初始化）。

### FIX-ALG-004：RINEX 3 观测槽位分配修复（对齐 C set_index 优先级仲裁）

- **日期**：2026-10-13
- **模块/位置**：`rtklib-core/src/main/java/org/rtklib/java/rinex/RinexParser.java`，观测类型→槽位映射逻辑
- **类别**：C 对齐修复（RINEX 观测文件解析）
- **C 版依据**：`RTKLIB-2.5.0/src/rinex.c` L966-1038 `set_index()`：同槽多码型选最高 CODEPRIS 优先级、同 code 共享主槽、落选进 NEXOBS。Java 版原实现为直接 `code2idx` 写入、后写覆盖先写，无优先级仲裁。
- **问题现象**：PPC-Dataset Tokyo run2 过度固定（Java Fix 1188 vs C 15）、Nagoya run1 固定不足（Java 527 vs C 1594）。rover GPS slot1 被 C2L 覆盖 C2W、base slot0 被 C1X 覆盖 C1C，码型通道偏差污染 IFLC udbias。
- **根因**：Java `RinexParser` 对每个观测类型直接写入槽位，后写覆盖先写，违反 C `set_index` 的 CODEPRIS 优先级仲裁语义。
- **修改内容**：重写槽位分配逻辑，实现 CODEPRIS 优先级仲裁 + NEXOBS 次选，对齐 C `set_index()` 语义。
- **验证**：6 组 PPC 数据集 Fix 合计 5025→4250（C 3784），首历元 init 值与 C trace 逐位一致。
- **对齐结论**：与 C 版 `rinex.c set_index()` 语义一致。

### FIX-ALG-005：ddres/udbias C 对齐（maxinno 阈值 + varerr IFLC 方差）

- **日期**：2026-10-13
- **模块/位置**：`rtklib-core/src/main/java/org/rtklib/java/rtk/RtkCore.java`，`ddres()` + `udbias()` + `varerr()`
- **类别**：C 对齐修复（AR 前置检查 + 观测噪声模型）
- **C 版依据**：
  - `RTKLIB-2.5.0/src/rtkcmn.c` `ddres()` 中 maxinno 拒绝阈值使用 `opt.maxinno[code]*threshadj`，拒绝时 `vsat=0, rejc++, continue`
  - `RTKLIB-2.5.0/src/rtkcmn.c` `varerr()` IFLC 模式 `var *= SQR(3.0)` 即 `var *= 9.0`
- **问题现象**：Java 版 maxinno 阈值计算和拒绝处理与 C 不一致；IFLC 模式方差缩放因子不同，影响双差残差和新息向量。
- **根因**：Java 版 `ddres()` 拒绝处理路径与 C 版不同；`varerr()` IFLC 模式缺少 `var *= 9.0` 缩放。
- **修改内容**：`ddres()` maxinno 阈值改为 `opt.maxinno[code]*threshadj`，统一拒绝处理（`vsat=0, rejc++, continue`）；`varerr()` IFLC 模式新增 `var *= 9.0`（对齐 demo5 `SQR(3.0)`）。
- **验证**：PPC-Dataset 6 组数据集 AR 行为对齐 C 版。
- **对齐结论**：与 C 版 `ddres()`/`udbias()`/`varerr()` 语义一致。

### FIX-ALG-006：CascadeAR 与 IFLC 模式互斥守卫修复

- **日期**：2026-10-15
- **模块/位置**：`rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java`，`resamb_LAMBDA()` 入口（enableCascadeAR 分支）
- **类别**：JAVA-ONLY 扩展缺陷修复（开关组合守卫）
- **C 版依据**：JAVA-ONLY 扩展（`enableCascadeAR`，默认关闭）。C EX fork 对 IFLC+AR 无任何级联处理——`rtkpos.c` 中 `NF(opt)` 在 `IONOOPT_IFLC` 下压缩为 1，标准 LAMBDA 直接作用于单 IF 偏差状态。EWL/WL/NL 逐级固定依赖逐频率原始模糊度状态（EWL 需三频、WL 需双频），而 IFLC 将状态空间压缩为每星 1 个非整数 IF 组合偏差，逐级固定在数学上不成立——两者设计层面互斥。
- **问题现象**：5 个 IFLC 测试数据集（PPC Tokyo run1/run2、PPC Nagoya run1/run2、UrbanNav Tokyo）开启 `enableCascadeAR` 后 Fix 率全部为 0%（Float 解正常）。
- **根因**：`resamb_LAMBDA()` 入口处 `enableCascadeAR` 分支无条件 `return cascadeAmbFix(...)`；而 `cascadeAmbFix` 内部 `nf = (ionoopt==IONOOPT_IFLC) ? 1 : opt.nf`，IFLC 下 `nf<2` 立即返回 -1。返回值直接作为 resamb 失败向下游传播，标准 LAMBDA 路径被短路，AR 每历元必然失败。
- **修改内容**：
  ```java
  // before
  if (rtk.rtkConfig.enableCascadeAR) {
      return RtkOptimizationsCascadeAR.cascadeAmbFix(rtk, bias, xa, gps, glo, sbs, nav);
  }
  // after：IFLC（有效 nf<2）不进入 CascadeAR，落回标准 LAMBDA（与关闭开关逐值一致）
  int nfAr = (rtk.opt.ionoopt == Constants.IONOOPT_IFLC) ? 1 : rtk.opt.nf;
  if (rtk.rtkConfig.enableCascadeAR && nfAr >= 2) {
      return RtkOptimizationsCascadeAR.cascadeAmbFix(rtk, bias, xa, gps, glo, sbs, nav);
  }
  ```
- **验证**：临时验证测试（已删除）对 5 数据集双跑 C-EX-Ref（CONFIGS[2]）与 J-CascadeAR（CONFIGS[9]），两者唯一差异为 enableCascadeAR 开关，守卫生效后必须逐值一致：
  - Tokyo run1：C fix=697 / float=10760，J 全同 [MATCH]
  - Tokyo run2：C fix=845 / float=7831，J 全同 [MATCH]
  - Nagoya run1：C fix=626 / float=6727，J 全同 [MATCH]
  - Nagoya run2：C fix=939 / float=8236，J 全同 [MATCH]
  - UrbanNav Tokyo：C fix=1182 / float=9952，J 全同 [MATCH]
  `RtkOptimizationsCascadeARTest` 6/6 通过（单元级不受影响）。
- **对齐结论**：IFLC + enableCascadeAR 组合的行为恢复为与 enableCascadeAR=off 完全一致的标准 LAMBDA 路径（即已对齐 C-EX-Ref 的既有基线）；非 IFLC 模式下 CascadeAR 行为不变。受影响变体：DynamicRtkOptTest 的 J-CascadeAR（CONFIGS[9]）与 L-AllOpt（CONFIGS[11]，其中 CascadeAR 分量不再生效于 IFLC 数据集）。

### FIX-ALG-007：CascadeAR 伪逐级→真逐级重写

- **日期**：2026-10-15
- **模块/位置**：`rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkOptimizationsCascadeAR.java`，全部重写；`RtkCore.java` 入口回落改造
- **类别**：JAVA-ONLY 扩展缺陷修复（算法重写）
- **C 版依据**：JAVA-ONLY 扩展（`enableCascadeAR`，默认关闭）。算法参考 GREAT-PVT（GREAT-WHU/GREAT, LibGREAT gambfix）逐级模糊度固定思想。C EX fork 无对应实现（无 resamb_WLNL/resamb_TCAR 逐级 AR）。
- **问题现象**：v2.2.x 旧实现（伪逐级）在 RTK 静态短基线测试中 fix 率暴跌（差站 30%→3%/0%，好站 90%→11%），且产生假 Fix（xa 恒零输出基站坐标）。
- **根因**：旧实现 4 个缺陷：
  1. **Bug1 伪逐级**：三次 LAMBDA 共用同一 y/Qb，EWL/WL 验证冗余（ratio 值相同，要么全过要么全不过）
  2. **Bug2 固定阈值**：NL 用固定 cascadeArRatioNl=3.0，无基于卫星数的动态调整
  3. **Bug3 缺 restamb**：xa 参数从未写入 → 假 Fix（输出基站坐标）
  4. **Bug4 失败不阻断**：EWL/WL ratio 未过仍继续 NL，"逐级"形同虚设
  旧伪实现因 IFLC 守卫（FIX-ALG-006）在全部数据集测试中被短路，从未被激活，缺陷潜伏。
- **修改内容**：全部重写为真逐级 Cascade AR：
  1. 每级构建独立组合观测向量与协方差：`z=C·y`、`Qz=C·Q·Cᵀ`，独立执行 LAMBDA 与独立 ratio 门限
  2. 级间通过条件更新传递约束：`K=Q·Cᵀ·Qz⁻¹`，`y←y−K(z−b)`，`Q←Q−K·C·Q`（与 GREAT 虚拟观测注入数学等价）
  3. 整数反推恢复每频率 DD 整数：`N1=b_NL`、`N2=N1−b_WL`、`N5=N2−b_EWL`；不可成链行降级为 float（fix=1）
  4. NL 级门限对齐标准路径动态调整（`AR_POLY_COEFFS` 多项式），`cfg.cascadeArRatioNl` 作为下限
  5. 成功路径完整复刻标准路径：条件更新 `rtk.xa/rtk.Pa` + `RtkCore.restamb()` 恢复单差模糊度
  6. 任一级失败立即 `return -1` 回落标准 LAMBDA 路径；`RtkCore.resamb_LAMBDA()` 入口从短路改为回落式：
     ```java
     int nfAr = (rtk.opt.ionoopt == Constants.IONOOPT_IFLC) ? 1 : rtk.opt.nf;
     if (rtk.rtkConfig.enableCascadeAR && nfAr >= 2) {
         int nbCas = RtkOptimizationsCascadeAR.cascadeAmbFix(...);
         if (nbCas > 0) return nbCas;   // 成功返回；失败（≤0）继续标准 LAMBDA
     }
     ```
- **重写初版在 A/B 验证中暴露的 3 个实现缺陷（同日修复）**：
  1. **Bug5 restamb bias 消费错位（关键缺陷）**：bias[] 按 DD 行序填充，但 `RtkCore.restamb()` 按 (m,f,sat升序) 消费且跳过每组参考行、降级行（fix=1）不占槽位——首个降级行之后全部 bias 错位，错误恢复值经 holdamb（FIXHOLD）锁入滤波器形成冻结假 Fix 簇。修复：bias[] 改为按 restamb 消费序紧凑构建。Tokyo run2 修复前后：Fix 率 1.2%（107）→ 8.9%（816），Fix H.RMSE 5.98m → 1.84m。
  2. **Bug6 pr[0]==-1（仅 f2 行配对）时 freeMap[-1] AIOOBE**：首历元崩溃。修复：映射前守卫。
  3. **Bug7 锚定行 bRowFull 未填充**（enableAmbAnchor=true 时消费 0.0）。修复：消费序构建前按 round(float) 填充锚定行（与标准路径一致）。
- **验证**：`RtkOptimizationsCascadeARTest` 6/6 通过（单元级，含 EWL 波长/守卫/回落）；21/21 RTK 优化套件通过（开关关回归）；`CascadeArBehaviorTest` EST 模式 4 数据集 A/B 行为验证最终回归两轮运行逐位一致，相对门限策略（假 Fix 鉴别仅基线健康区制执行 + 可用性 ≥0.5× 基线）**0 violations**：
  - Tokyo run1：基线 3.2%(386)/Fix H.RMSE 9.52m，级联 3.0%(357)/20.4m（坏尾 >2m 占比 16.0% vs 基线 2.1%，逐级门限在偏差污染区弱于联合动态阈值的固有权衡）
  - Tokyo run2：基线 4.7%(433)/0.45m，级联 8.9%(816)/1.84m（可用性约 2 倍）
  - Nagoya run1：基线 0.2%(14)/19.7m，级联 0.8%(60)/27.0m（基线自身 100% 假 Fix，可用性约 4 倍）
  - Nagoya run2：基线 0.1%(7)/26.6m，级联 1.2%(110)/27.0m（基线自身 100% 假 Fix，可用性约 15 倍）
  完整分布与验收结论见 `RTK_Extra_Optimizations.md` §10.7。
- **对齐结论**：JAVA-ONLY 扩展，默认关闭不影响标准路径（开关关与 FIX-ALG-006 的 IFLC 守卫语义均保持）。非 IFLC + enableCascadeAR=true 时走真逐级路径，失败逐历元回落标准 LAMBDA。残留坏尾（部分历元 H>2m）为浮点偏差漂移陷入错误整数邻域的自洽失效，与标准 LAMBDA 路径同类（基线 Tokyo run1 Fix H.RMSE 9.5m），非级联实现缺陷。

---

## 附：记录变更索引

| ID | 日期 | 模块 | 一句话摘要 |
|----|------|------|-----------|
| FIX-ALG-001 | 2026-10-14 | ppp/PppCore.java | pppos 入口 exc 清零，修复跨历元排除标志泄漏（static solved 1/30→14/30） |
| FIX-ALG-002 | 2026-10-13 | rtk/RtkCore.java | 移除 compactObsFreq，修复 IFLC 频槽混合致 Fix 率 0% |
| FIX-ALG-003 | 2026-10-13 | rtkcmn/SatUtils.java | satid2no 解析前 trim()，对齐 C sscanf 空白跳过（Fix 0→570） |
| FIX-ALG-004 | 2026-10-13 | rinex/RinexParser.java | 槽位分配对齐 C set_index 优先级仲裁（Fix 5025→4250） |
| FIX-ALG-005 | 2026-10-13 | rtk/RtkCore.java | ddres/udbias/varerr 对齐 C（maxinno 阈值 + IFLC var*=9.0） |
| FIX-ALG-006 | 2026-10-15 | rtkpos/RtkCore.java | CascadeAR 与 IFLC 互斥守卫，Fix 0%→恢复标准 LAMBDA 基线（5 数据集与关闭开关逐值一致） |
| FIX-ALG-007 | 2026-10-15 | rtkpos/RtkOptimizationsCascadeAR.java | CascadeAR 伪逐级→真逐级重写：每级独立组合变换+级间条件更新+整数反推+restamb+动态ratio阈值+bias 消费序构建（修复 holdamb 假 Fix 簇） |
| FIX-ALG-008 | 2026-10-16 | rtkpos/RtkCore.java | relpos 入口 azel/freq 清零，修复跨历元方位角/频率残留泄漏（selsat 误选低高度角卫星） |
| FIX-ALG-009 | 2026-10-16 | ppp/PppCore.java | ppos 入口 azel 清零，修复跨历元方位角残留泄漏（高度角依赖逻辑污染） |