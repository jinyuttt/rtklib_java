# RTKLIB Java 算法修复记录

本文件记录**自 v2.3.0 起**所有改变算法数值行为的修改，供定版阶段逐条备查与对齐审查。

> 说明：条目按修复归档顺序排列，编号并非严格递增（如 FIX-ALG-004 位于 011 之后）；完整编号索引见文末汇总表。FIX-ALG-019 仅以汇总表行记录、无独立章节。

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

## FIX-ALG-001：pppos 排除标志数组 exc 跨历元泄漏修复

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

## FIX-ALG-002：compactObsFreq 移除——IFLC 频槽混合根因修复

- **日期**：2026-10-13
- **模块/位置**：`rtklib-core/src/main/java/org/rtklib/java/rtk/RtkCore.java`，`rtkpos()` 中 `compactObsFreq` 调用
- **类别**：C 对齐修复（观测槽位语义）
- **C 版依据**：RTKLIB-2.5.0 C 版无 `compactObsFreq`——观测槽位由 `rinex.c set_index()` / `rtcm.c sigindex()` 在输入阶段固定，运行时 `rtkpos()` 不做槽位压缩。Java 版曾添加 `compactObsFreq(obs, n)` 作为 JAVA-ONLY 扩展，按每颗卫星"有数据的槽位"重排，导致双站 slot 索引指向不同频率。
- **问题现象**：PPC-Dataset Tokyo run1 IFLC RTK Fix 率 0%。双站 slot1 指向不同频率（流动站 B2a vs 基准站 B2b），IFLC 双差 bias≈1e6 m 毒化全部模糊度，Float 发散。
- **根因**：`compactObsFreq` 对 rover 和 base 独立压缩槽位，两站观测类型不同时同一 slot 索引指向不同频率，破坏双差观测方程的频率一致性前提。
- **修改内容**：移除 `rtkpos()` 中的 `compactObsFreq` 调用；方法本体保留（不影响其他路径）。
- **验证**：PPC-Dataset Tokyo run1 Fix 率从 0% 恢复（详见 `RTKLIB_Differences.md` §16）。
- **对齐结论**：与 C 版 `rtkpos()` 行为一致——运行时不做槽位压缩。

## FIX-ALG-003：satid2no 空格填充 PRN 解析修复

- **日期**：2026-10-13
- **模块/位置**：`rtklib-core/src/main/java/org/rtklib/java/rtkcmn/SatUtils.java`，`satid2no()`
- **类别**：C 对齐修复（RINEX 导航文件解析）
- **C 版依据**：`RTKLIB-2.5.0/src/rtkcmn.c` L475-501 `satid2no()` 使用 `sscanf("%c%d")`，`%d` 自动跳过前导空白。RINEX 3 导航文件按 `G%2d` 格式写出单位数 PRN（如 "G 5"），`sscanf` 正确解析为卫星号 5。
- **问题现象**：PPC-Dataset Tokyo run1 Fix 率 0%（在 FIX-ALG-002 修复前已暴露）。RINEX 3 导航文件中 "G 5"/"E 4"/"R 7" 等单位数 PRN 被 `Integer.parseInt` 抛 NumberFormatException，`satid2no()` 返回 0（无效），全部单位数 PRN 广播星历被静默丢弃 → satposs rs=0 → 双站 azel=0 → DD 卫星数坍缩（Tokyo run1 selsat ns 21→9）→ posvar 超过 thresar[1] → AR 永不触发。
- **根因**：Java `Integer.parseInt(" 5")` 不跳过前导空格（与 C `sscanf %d` 语义不同），抛异常后被 catch 吞掉。
- **修改内容**：`satid2no()` 解析前 `trim()`，对齐 C `sscanf("%c%d")` 空白跳过语义。
- **验证**：PPC-Dataset Tokyo run1 Fix 0→570、Float 8986→10927、成功率 85.8%→97.9%。
- **对齐结论**：与 C 版 `rtkcmn.c satid2no()` 语义完全一致。

## FIX-ALG-008：RtkCore relpos azel/freq 跨历元泄漏修复

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

## FIX-ALG-009：PppCore pppos azel 跨历元泄漏修复

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

## FIX-ALG-010：SPP 恢复多系统解算（对齐 C pntpos 导航系统过滤语义）

- **日期**：2026-10-16
- **模块/位置**：`rtklib-core/src/main/java/org/rtklib/java/rtkpos/RtkCore.java`（rtkpos SPP 初始化）、`rtklib-core/src/main/java/org/rtklib/java/rinex/PostPosProcessor.java`（观测过滤）
- **类别**：C 对齐修复（SPP 导航系统选择）
- **C 版依据**：`RTKLIB-2.5.0/src/rtkpos.c` L2364 附近 rtkpos 的 SPP 初始化调用 `pntpos(obs,nu,nav,rtk->opt,&rtk->sol,NULL,rtk->ssat)`，传入完整 `rtk->opt`（`opt.navsys` 为用户配置的多系统集合）；`pntpos.c` 内部按 `navsys` 过滤卫星。C 版 SPP 天然支持多系统联合解算。
- **问题现象**：PPP 多系统场景下 GLONASS 钟差初值 `sol.dtr[1]` 恒为 0（仅 GPS 参与 SPP），`udclk_ppp` 初始化的 GLO 钟差初值与正确值偏差约 790 m，GLO/GAL 观测残差超 maxinno 被剔除。
- **根因**：Java 侧 SPP 调用链（rtkpos SPP 初始化的 `sppInitOpt` 与 PostPosProcessor 的观测过滤）将导航系统强制为 GPS-only，导致 `sol.dtr[1..]`（各系统-GPS ISB）无估计值。此前 Java 仅 GPS 的行为偏离 C 版。
- **修改内容**：SPP 初始化保留 `opt.navsys`（`sppInitOpt = new PrcOpt(opt)` 不再覆盖为 GPS-only）；观测过滤按 `satsys(o.sat) & opt.navsys` 语义对齐 C（rover/base/SPP 路径统一）。修复后 SPP 输出 `dtr[0]`（GPS 钟）、`dtr[1]`（GLO-GPS）、`dtr[2]`（GAL-GPS），与 C `pntpos.c` L452-459 语义一致。
- **验证**：PppValidationTest 数据集 SPP 输出 `dtr[1]≈-790m`（GLO-GPS ISB 有值），PPP GLO 残差从 -750 m 收敛至数十米，nv 28→42。
- **对齐结论**：与 C 版 rtkpos SPP 初始化及 pntpos 导航系统过滤语义一致。

## FIX-ALG-011：udclk_ppp 恢复 C 双分支结构 + ISB 钟差初始化 JAVA-ONLY 开关

- **日期**：2026-10-16
- **模块/位置**：`rtklib-core/src/main/java/org/rtklib/java/ppp/PppCore.java`，`udclk_ppp()`
- **类别**：C 对齐修复 + JAVA-ONLY 扩展（`RtkConfig.enablePppIsbClockInit`，默认关闭）
- **C 版依据**：`RTKLIB-2.5.0/src/ppp.c` L612-631 `udclk_ppp()`：
  ```c
  for (i=0;i<NSYS;i++) {
      if (rtk->opt.sateph==EPHOPT_PREC) {
          /* time of prec ephemeris is based gpst */
          /* neglect receiver inter-system bias  */
          dtr=rtk->sol.dtr[0];
      }
      else {
          dtr=i==0?rtk->sol.dtr[0]:rtk->sol.dtr[0]+rtk->sol.dtr[i];
      }
      initx(rtk,CLIGHT*dtr,VAR_CLK,IC(i,&rtk->opt));
  }
  ```
  C 为双分支：精密星历模式忽略 ISB（全部系统用 `dtr[0]`），广播星历模式用 `dtr[0]+dtr[i]`（各系统 ISB）。
- **问题现象**：多系统 PPP（EPHOPT_PREC）存活率仅 1.5%（44/2880）。GLO 钟差初值用 `dtr[0]`（≈797 m）而非正确值（`dtr[0]+dtr[1]≈7 m`），GLO 残差 ≈-750 m 超 maxinno 被剔除；`initx` 每历元重置（C 白噪声设计）导致剔除永久化——C 版精密模式实际上仅 GPS 参与解算。
- **根因**：C 版设计假设"精密钟差统一 GPST 尺、接收机 ISB 可忽略"，但实测 GLO-GPS 接收机 ISB 达 μs 级（数百米），假设不成立；Java 侧此前的无条件 ISB 修复则删除了 C 的精密分支，导致默认路径偏离 C。
- **修改内容**：恢复 C 双分支结构；新增 `RtkConfig.enablePppIsbClockInit`（默认 false）控制精密模式行为：
  ```java
  if (i == 0 || opt.sateph != Constants.EPHOPT_PREC || rtk.rtkConfig.enablePppIsbClockInit) {
      dtr = i == 0 ? rtk.sol.dtr[0] : rtk.sol.dtr[0] + rtk.sol.dtr[i];
  } else {
      dtr = rtk.sol.dtr[0];   /* C EPHOPT_PREC 分支：neglect ISB */
  }
  ```
  开关关闭（默认）：与 C 逐值一致（精密模式 GLO/GAL 钟差用 `dtr[0]` 初始化）。开关开启：精密模式也采用 ISB 初始化（广播语义），GLO 初值偏差从 790 m 降至约 49 m，GLO 参与解算（nv 28→42）。
- **验证**：`mvn compile -pl rtklib-core` 通过。行为验证（PppValidationTest 数据集，开关开启）：GLO 钟差收敛 10m→44m→32m，残差 -750m→约 50m。存活率 1.5% 的问题另有 Galileo ISB 无法估计（BRDC 无 GAL 广播星历，`dtr[2]≈0`）与 PPP 收敛特性等因素，需单独排查。
- **对齐结论**：默认路径与 C `ppp.c udclk_ppp()` 双分支语义逐值一致；`enablePppIsbClockInit` 为 JAVA-ONLY 改善（记录于 RTKLIB_Differences.md）。

## FIX-ALG-004：RINEX 3 观测槽位分配修复（对齐 C set_index 优先级仲裁）

- **日期**：2026-10-13
- **模块/位置**：`rtklib-core/src/main/java/org/rtklib/java/rinex/RinexParser.java`，观测类型→槽位映射逻辑
- **类别**：C 对齐修复（RINEX 观测文件解析）
- **C 版依据**：`RTKLIB-2.5.0/src/rinex.c` L966-1038 `set_index()`：同槽多码型选最高 CODEPRIS 优先级、同 code 共享主槽、落选进 NEXOBS。Java 版原实现为直接 `code2idx` 写入、后写覆盖先写，无优先级仲裁。
- **问题现象**：PPC-Dataset Tokyo run2 过度固定（Java Fix 1188 vs C 15）、Nagoya run1 固定不足（Java 527 vs C 1594）。rover GPS slot1 被 C2L 覆盖 C2W、base slot0 被 C1X 覆盖 C1C，码型通道偏差污染 IFLC udbias。
- **根因**：Java `RinexParser` 对每个观测类型直接写入槽位，后写覆盖先写，违反 C `set_index` 的 CODEPRIS 优先级仲裁语义。
- **修改内容**：重写槽位分配逻辑，实现 CODEPRIS 优先级仲裁 + NEXOBS 次选，对齐 C `set_index()` 语义。
- **验证**：6 组 PPC 数据集 Fix 合计 5025→4250（C 3784），首历元 init 值与 C trace 逐位一致。
- **对齐结论**：与 C 版 `rinex.c set_index()` 语义一致。

## FIX-ALG-005：ddres/udbias C 对齐（maxinno 阈值 + varerr IFLC 方差）

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

## FIX-ALG-006：CascadeAR 与 IFLC 模式互斥守卫修复

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

## FIX-ALG-007：CascadeAR 伪逐级→真逐级重写

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

## FIX-ALG-012：satposPrec 精密星历分支重写（删除广播回落 + 补相对论改正 + vare 合并）

- **日期**：2026-10-20
- **模块/位置**：`rtklib-core/src/main/java/org/rtklib/java/ephemeris/EphModel.java`，`satposPrec()`；删除 `satposBrdcFallback()`
- **类别**：C 对齐修复（EPHOPT_PREC 卫星位置/钟差链路）
- **C 版依据**：`RTKLIB-2.5.0/src/ephemeris.c` L756 `satpos()` EPHOPT_PREC 分支：
  ```c
  if (!peph2pos(time,sat,nav,1,rs,dts,var)) break; else return 1;
  ```
  peph 失败即丢弃该卫星（`*svh=-1`），**无广播星历回落**；`RTKLIB-2.5.0/src/preceph.c` L784-823 `peph2pos()`：相对论改正 `dts[0]=dtss[0]-2.0*dot3(rs,rs+3)/CLIGHT/CLIGHT`（L812-819）、`*var=vare+varc`（L820）。
- **问题现象**：ALGO 站 PPP 数据集（PppValidationTest，2880 历元）存活率仅 17.3%（497/2880），收敛解锁定在错误吸引子（末端仍差 ~56m，毫米级稳定）。
- **根因**：Java `satposPrec` 在 pephpos/pephclk 失败时回落广播星历（C 无此路径，引入非 C 语义的卫星位置源混杂）；缺失相对论改正（钟差量级 ~1m）；`vare` 仅含位置方差未合并钟差方差。
- **修改内容**：
  1. pephpos/pephclk 任一失败即 `return false`（卫星丢弃，对齐 C `*svh=-1`）
  2. 补相对论改正：`dts[0]=dtss[0]-2.0*(rs·v)/CLIGHT²`、`dts[1]=(dtst[0]-dtss[0])/tt`
  3. `vare[0]=vareP[0]+varcP[0]`
  4. 删除 `satposBrdcFallback()` 方法及诊断日志
- **验证**：`mvn compile -pl rtklib-core` 通过。PppValidationTest：dU −64.6m→−55.6m 略改善，存活率仍 497/2880（非根因或非全部根因，最终根因见 FIX-ALG-014/015）。
- **对齐结论**：与 C `satpos()` EPHOPT_PREC 分支 + `peph2pos()` 语义逐值一致。

## FIX-ALG-013：pephclk/pephpos 失败语义与 std/varc 对齐

- **日期**：2026-10-20
- **模块/位置**：`rtklib-core/src/main/java/org/rtklib/java/ephemeris/Sp3Reader.java`，`pephclk()` / `pephpos()`
- **类别**：C 对齐修复（精密钟差失败语义）
- **C 版依据**：`RTKLIB-2.5.0/src/preceph.c` L651-656 `pephclk()`：
  ```c
  if (nav->nc<2||timediff(time,nav->pclk[0].time)<-MAXDTE||
      timediff(time,nav->pclk[nav->nc-1].time)>MAXDTE) {
      return 1;   /* no prec clock: keep dts set by pephpos (SP3 clock) */
  }
  ```
  无 CLK 产品/时间越界时**返回 1 且不动 dts**（保留 pephpos 写入的 SP3 内嵌钟差）；钟差中断（c=0）才返回 0（L671/675/685）。`pephpos()` L605-613/L620-638：vare=位置 std 范数+外推误差、varc=钟差 std×CLIGHT+外推误差的平方。
- **问题现象**：同 FIX-ALG-012 数据集；无 CLK 记录时段或 CLK 覆盖缺口时卫星被错误丢弃。
- **根因**：Java `pephclk` 在 nc<2/越界时返回 0 并清零 dts（C 语义为返回 1 且保留 SP3 钟）；`pephpos` 未填 std/varc（方差恒 0，滤波权矩阵失真）。
- **修改内容**：`pephclk` nc<2/越界返回 1 且不触碰 dts；`pephpos` 补齐 C L605-638 的 std/varc 计算（位置三轴 std 范数 + EXTERR_EPH 外推项；钟差 std×CLIGHT ± EXTERR_CLK 外推项）。
- **验证**：`mvn compile -pl rtklib-core` 通过；与 FIX-ALG-012 联合验证（存活率 497/2880，同上）。
- **对齐结论**：与 C `pephclk()`/`pephpos()` 失败分支与方差语义逐值一致。

## FIX-ALG-014：PcvReader.decodef 缺 mm→m 转换（PPP 存活率根因）

- **日期**：2026-10-20
- **模块/位置**：`rtklib-core/src/main/java/org/rtklib/java/ephemeris/PcvReader.java`，`decodef()`（readantex/readngspcv 共用）
- **类别**：C 对齐修复（ANTEX 单位制）
- **C 版依据**：`RTKLIB-2.5.0/src/rtkcmn.c` L2462-2472 `decodef()`：
  ```c
  for (i=0,p=strtok_r(p," ",&q);p&&i<n;p=strtok_r(NULL," ",&q)) {
      v[i++]=atof(p)*1E-3;    /* mm -> m */
  }
  ```
  ANTEX/NGS PCV 文件所有数值（PCO 与 PCV）均为 mm，decodef 统一 ×1E-3 转 m；readantex（L2537-2605）与 readngspcv（L2513-2528）共用此函数。
- **问题现象**：ALGO 站 PPP 存活率 17.3%，相位/码 prefit 残差每历元 8-16 星达 5-45m 超 maxinno 被剔除，nv 塌缩至 4-10。手工核算 IF(C1W,C2W) 组合与 PPP 运行观测量的逐星偏差 K：G05=+13.55、G06=+50.27、G09=+28.30、G11=+50.36、G12=+48.63m（正电离层必为负号，排除电离层来源；corrMeas 本身经 DiagObsTest 实测正确）。
- **根因**：Java `decodef` 直接 `Double.parseDouble` 无 ×1E-3，PCO/PCV 以 1000 倍加载（如 BLOCK IIF Up=1501.80mm 被当 1.5018m 变 1501.8m）。接收机天线 AOAD/M_T 与卫星天线改正值整体膨胀千倍，经 `antmodel`/`satantpcv` 进入 dants/dantr，观测值被逐星错误改正，残差呈 per-satellite 数十米量级。此前 IF 组合构造、槽位仲裁、SSR/OSB、导航解析等假设均被逐一排除（数值证据见上）。
- **修改内容**：
  ```java
  // before: vals.add(Double.parseDouble(tokens[i]));
  vals.add(Double.parseDouble(tokens[i]) * 1E-3);
  ```
  readngspcv 共用 decodef，与 C 一致（NGS 文件值同为 mm）。
- **验证**：PppValidationTest（ALGO，testPppConvergence）：存活率 497→**1750**/2880（17.3%→60.8%）；dU −55.6m→**+0.126m**；收敛后 ENU RMS E=0.362/N=0.040/U=0.128m；首历元即达米级收敛，末端历元 ECEF 偏差 ~0.4m。
- **对齐结论**：与 C `rtkcmn.c decodef()` 单位制逐值一致；Java `antmodel_s`（无负号）、`interpvar` ×5 索引技巧经核对与 C L3919-3929 一致，未改动。

## FIX-ALG-015：satantoff 卫星天线 PCO 改正移植（缺失实现）

- **日期**：2026-10-20
- **模块/位置**：`rtklib-core/src/main/java/org/rtklib/java/ephemeris/Sp3Reader.java` 新增 `satantoff()`；`EphModel.satposPrec()` 应用
- **类别**：C 对齐修复（卫星 PCO：质心→相位中心）
- **C 版依据**：`RTKLIB-2.5.0/src/preceph.c` L707-765 `satantoff()`：以卫星位置反向为 ez、太阳方向构建星固系单位矢量 (ex,ey,ez)，按系统取双频（GPS/QZS L1-L2、GLO G1-G2 经 sat2freq、GAL E1-E5b、BDS B1I-B2I、IRN L5-S）算 IF 系数 C1/C2，`dant[i]=C1·off[0]·e+C2·off[1]·e`。`peph2pos()` L804-811 在 opt=1（`ephemeris.c` L756 satpos 硬编码）时应用于卫星位置 `rs[i]=rss[i]+dant[i]`（速度不加）。
- **问题现象**：FIX-ALG-014 修复后 dU 仍有 +0.126m 系统差（卫星 PCO Up≈1.5m 径向，主体被钟差估计吸收，残余呈 U 向系统偏置）。
- **根因**：Java 全代码库无 `satantoff` 实现——卫星 PCO 从未应用于 SP3 轨道（SP3 为质心坐标）；`PppCore.satantpcv` 仅对应 C ppp.c 的 PCV 改正（C 分工：PCO 在 preceph.c satantoff 应用于卫星位置，PCV 在 ppp.c satantpcv 应用于观测值）。
- **修改内容**：`Sp3Reader.satantoff()` 按上述 C 语义实现（依赖 `Tides.sunmoonpos`、`SatUtils.sat2freq`、`CoordTransform.normv3/cross3`，nav.pcvs 零填充时 dant=0 安全退化）；`satposPrec` 中：
  ```java
  double[] dant = new double[3];
  Sp3Reader.satantoff(time, rss, sat, nav, dant);
  rs[i] = rss[i] + dant[i];          /* 位置加 dant */
  rs[i + 3] = (rst[i] - rss[i]) / tt; /* 速度不加（对齐 C L808-811） */
  ```
- **验证**：PppValidationTest（ALGO）：存活率 1750→**1762**/2880（61.2%）；**dU +0.126m→+0.034m**，U RMS 0.128→0.036m；dE≈−0.36m 不变（卫星径向 PCO 不投影到水平，该常值偏置与参考坐标历元/板块漂移量级吻合，非算法问题）。
- **对齐结论**：与 C `preceph.c satantoff()` + `peph2pos()` opt=1 路径逐值一致；单一入口 `satposPrec`，SPP/RTK/PPP/PPP-RTK 的 EPHOPT_PREC 路径统一生效，无重复应用（pephpos/pephclk 仅被 satposPrec 调用）。

---

## FIX-ALG-016：RINEX CLK 文件钟差 std 字段列偏移修复

- **日期**：2026-10-21
- **模块/位置**：`rtklib-core/src/main/java/org/rtklib/java/ephemeris/ClkReader.java`
- **类别**：C 对齐修复（解析列偏移）
- **C 版依据**：`RTKLIB-2.5.0/src/rinex.c` `readrnxclk()` L1563：`data[i]=str2num(buff,40+off+i*20,19)`——钟差值字段占 19 字符，std 字段起始于 **60+off**（19 字符 + 1 列分隔符）。
- **问题现象**：CLK 文件解析出的钟差 std 偏小/错位，卫星钟差方差（`dts` var）异常。
- **根因**：Java 读取 std 用 `str2num(line, 59+timeOff, 19)`，列偏移差 1，读到相邻字符。
- **修改内容**：`clkStd` 起始列 59+timeOff → **60+timeOff**。
- **验证**：PppValidationTest 通过，PPP 精度无回归。

---

## FIX-ALG-017：RINEX 星历缓冲满时动态扩容（对齐 C add_eph/add_geph/add_seph）

- **日期**：2026-10-21
- **模块/位置**：`rtklib-core/src/main/java/org/rtklib/java/rinex/RinexParser.java`
- **类别**：C 对齐修复（数据丢失）
- **C 版依据**：`RTKLIB-2.5.0/src/rinex.c` `add_eph()`/`add_geph()`/`add_seph()`：缓冲满时 realloc 按 1024 扩容，**永不丢弃**星历。
- **问题现象**：多系统/全天生成 nav 文件（星历数超过初始 nmax）时，超出部分星历被静默丢弃（仅 log.warn），卫星定位可用星减少。
- **根因**：Java 三处解码在 `nav.n >= nav.nmax` 时直接返回丢弃。
- **修改内容**：decodeEph/decodeGeph/decodeSeph 均改为按 C 语义扩容 1024 并 arraycopy 迁移，新条目照常写入。
- **验证**：`mvn compile -pl rtklib-core` 通过；GreatPvtPositioningTest 全量数据加载后星历计数完整。

---

## FIX-ALG-018：RINEX 导航文件头部 ion/utc/leap 参数解析缺失（Klobuchar 未生效致 PPP 全历元无解根因）

- **日期**：2026-10-21
- **模块/位置**：`rtklib-core/src/main/java/org/rtklib/java/rinex/RinexParser.java` 新增 `decodeNavh()`
- **类别**：C 对齐修复（功能缺失）
- **C 版依据**：`RTKLIB-2.5.0/src/rinex.c` `decode_navh()` L521-630：解析 ION ALPHA/ION BETA（ver.2）、DELTA-UTC、IONOSPHERIC CORR（ver.3：GPSA/GPSB/GAL/QZSA/QZSB/BDSA/BDSB/IRNA/IRNB）、TIME SYSTEM CORR（GPUT/GLUT/GLGP/GAUT/QZUT/BDUT/SBUT/IRUT）、LEAP SECONDS，填充 `nav->ion_gps/ion_gal/ion_qzs/ion_cmp/ion_irn` 与 `nav->utc_*`。
- **问题现象**：GreatPvt 场景（G+E+C，IONOOPT_IFLC/BRDC，EPHOPT_PREC）PPP 全历元无解：SPP init 残差 −5.8~−13.5 m 全负且随仰角变化；PPP iter-0 预拟合残差继承 SPP 误差后触发 maxinno=5.0 连环剔星，exc 跨迭代持久 → ns<4 → SOLQ_NONE。
- **根因**：Java `readNavHeader()` 仅解析 RINEX VERSION 与 END OF HEADER，**全代码库无任何 ion_gps/utc_gps 写入点**——Klobuchar 电离层参数恒为全零，`ionmodel()` 对 BRDC/IFLC 退化路径返回 0，SPP 伪距残差含全电离层延迟。
- **修改内容**：新增 `decodeNavh(String line, String label)` 完整移植 C decode_navh（列偏移与 C str2num 逐字段一致，空列安全返回 0）；`readNavHeader()` 循环对非 VERSION/END OF HEADER 行调用之。
- **验证**：GreatPvtPositioningTest **14/14 通过**（修复前 testPppKinematic/testPppStatic 全历元无解失败）；PppValidationTest 4/4 通过（ALGO 场景 2880/2880 有解、ENU RMS 0.37m 无回归）。

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
| FIX-ALG-010 | 2026-10-16 | rtkpos/RtkCore.java, rinex/PostPosProcessor.java | SPP 恢复多系统解算（对齐 C pntpos navsys 过滤），sol.dtr[i] ISB 有估计值 |
| FIX-ALG-011 | 2026-10-16 | ppp/PppCore.java | udclk_ppp 恢复 C 双分支 + enablePppIsbClockInit 开关（JAVA-ONLY，默认关闭=C 对齐） |
| FIX-ALG-012 | 2026-10-20 | ephemeris/EphModel.java | satposPrec 重写：删除广播回落、补相对论改正、vare=vare+varc（对齐 peph2pos） |
| FIX-ALG-013 | 2026-10-20 | ephemeris/Sp3Reader.java | pephclk 无 CLK 返回 1 保留 SP3 钟；pephpos 补 std/varc |
| FIX-ALG-014 | 2026-10-20 | ephemeris/PcvReader.java | decodef 补 ×1E-3（ANTEX mm→m），PPP 存活率 17.3%→60.8% 根因 |
| FIX-ALG-015 | 2026-10-20 | ephemeris/Sp3Reader.java, EphModel.java | 新增 satantoff 卫星 PCO 改正，dU +0.126m→+0.034m |
| FIX-ALG-016 | 2026-10-21 | ephemeris/ClkReader.java | CLK std 字段列偏移 59→60（对齐 C readrnxclk） |
| FIX-ALG-017 | 2026-10-21 | rinex/RinexParser.java | 星历缓冲动态扩容（对齐 C add_eph/add_geph/add_seph，不再丢星历） |
| FIX-ALG-018 | 2026-10-21 | rinex/RinexParser.java | 补 decodeNavh：nav 头部 ion/utc/leap 解析缺失，Klobuchar 恒零致 PPP 全历元无解（GreatPvt 14/14） |
| FIX-ALG-019 | 2026-10-21 | rtklib-test PppValidationTest.java（测试基准） | ALGO 参考坐标未外推观测历元：北美板块 E 向 -1.65cm/yr×13.8yr≈-0.23m 被误判为解算系统差。改用 UNR/NGL 同日解（IGS20）为基准，dE -0.365→-0.002m |
| FIX-ALG-020 | 2026-10-21 | ephemeris/DcbReader.java（新增）、PostPosProcessor、RinexPppProcessor、RtkConfig、PppCore | EST 死锁：移植 readdcb（DCB 产品加载）+ enableIonoEstRobust 开关，EST 0→401~509/2880 |
| FIX-ALG-021 | 2026-10-21 | ppp/PppCore.java | EST 残余失败：udbias 超时重置被 vsat 条件阻塞 + udiono 重初始化被 outc 条件阻塞，对齐 C ppp.c L681/L744-748 |
| FIX-ALG-022 | 2026-10-21 | ppp/PppAmbFix.java, config/RtkConfig.java | PPP-AR WL 门限 PRIDE 对齐：OSB 完备性（四信号）+ maxdev 检验 + PartialAR 双重改正修复（EST+ISB fix 4→19；Partial 0→50 暴露假 Fix） |
| FIX-ALG-023 | 2026-10-21 | ppp/PppAmbFix.java, ppp/PppCore.java, data/Ssat.java, config/RtkConfig.java | NL 弧段级机制（PRIDE 对齐）：弧段累加 + minsav + σ/dev 统计门，Partial 假 Fix 50（全假 ~2m）→38（全真），E 32.9cm→1.56cm |
| FIX-ALG-024 | 2026-10-22 | ppp/PppAmbFix.java | 3F PPP-AR：WL/NL epoch median 对齐 + IFLC 逐星取整（对角协方差下 LAMBDA 退化修复）+ 编译作用域修复，3F fix 0→1226（65.7%） |
| FIX-ALG-025 | 2026-10-22 | ppp/PppAmbFix.java | 3F NL 构造公式验证（IFLC nlFloat≡N1 与重构自洽）+ EST 分支系数修复（f2/(f1−f2)→f2/(f1+f2)）+ 重构自洽；弧段均值实验负结果回退（2D 3.54→8.21cm） |
| FIX-ALG-026 | 2026-10-22 | ppp/PppAmbFix.java | 双频主路径/PartialAR IFLC 逐星取整分支（对角协方差 LAMBDA 退化修复，WL+NL/FixHold/Partial 复活）+ FRACDUMP 插桩 + δ 门（整数域一致性检验，半周界+基准自检，0 污染）；深挖修正根因：无差分 median 对齐整周平移自由度（fcbWl=+0.7714 相位 OSB frac 跨界）+ 3F 双重改正错进错出；处置收口：双频 IFLC 记录受限、生产推荐 3F、min=3 不采纳 |
| FIX-ALG-027 | 2026-10-22 | ephemeris/OsbReader.java, data/Nav.java, ppp/PppAmbFix.java, ppp/PppCore.java, ppp/PppAmbFixBds3.java | 时变 OSB 读取兼容：Bias-SINEX START/END 解析 + osbSeries 时间戳序列（码/相位分键 ×2）+ GBM SVN 空缺列偏移自适应 + 码 OSB cbias 累加守卫（timedShort）；WUM 常量语义零影响 |
| FIX-ALG-028 | 2026-10-22 | ephemeris/OsbReader.java, ppp/PppAmbFix.java, ppp/PppCore.java | WUM 3F 67.81cm 回归三因素闭环：getEwlFcb 恢复快照（L5Q 时变补偿失配）+ sh 双令牌判别 v2（27 星 OSB 丢失）+ MW 组合 OSB 单次施加裁决（2×2 矩阵：单次+无码OSB=3.00cm 终态，027 勘误） |
## FIX-ALG-020 EST 模式死锁：DCB 产品加载 + enableIonoEstRobust 开关

**日期**: 2026-10-21
**类别**: PPP / 缺失功能移植 + JAVA-ONLY 兜底开关
**现象**: 非组合 PPP（IONOOPT_EST）6 个测试配置存活率 0/2880（SOLQ_PPP 无一成功）。

### 根因

EST 模式首历元各卫星电离层状态由双频伪距差初始化（udiono），MW 组合初始化相位偏差
（udbias）。伪距差含卫星+接收机码偏差（Δdcb），其泄漏项 `-mapf·Δdcb/(γ-1)`（γ=f1²/f2²，
GPS≈1.647）随仰角映射函数放大后进入相位 prefit 残差（典型 L1 +17.8 m、L2 −7 m），超过
maxinno[0]=5.0 门限被全部剔除。码行观测方程不含 bias/iono 偏导（H=0），码行更新无法
收缩这两个状态的协方差 → 相位残差持续超限 → bias/iono 永不更新 → 永久 SOLQ_NONE。
C 2.5.0 的应对是 postpos.c L1092-1108 自动加载 DCB 产品（cbias 哨兵 −1 + readdcb）。

### 修复内容

1. **DcbReader**（新增，org.rtklib.java.ephemeris）：完整移植 C preceph.c readdcb()
   （L514-544）：
   - 入口清零 nav.cbias（对齐 C L523-525）；
   - `.BIA/.BSX → readbiaf`（Bias-SINEX OSB/DSB 记录，C L449-502）：obs1[0]=='C' 只取
     码偏差；值 str2num(buff,70,21)；频率映射 L1→freq0、非 GAL L2→freq1、GAL L5→freq1
     （E5b 记录 C 同样丢弃）；OSB ref 码→全体 cbias 加 ref 偏移、非 ref→cbias[ix-1]−=值；
     DSB 仅同频、ref 码赋值/取负；值统一 ×1E-9×CLIGHT（ns→m）；
   - `.DCB → readdcbf`（传统 P1-C1/P2-C2 格式，C L378-417）：卫星 cbias[sat-1][type-1][0]，
     接收机 rbias[i][G/R][type-1]（stas 提供时）；
   - 码偏移索引经 ObsCode.obs2code + SppCore.code2biasIx（对齐 C obs2code/code2bias_ix，
     init_bias_ix 初始化 −1 语义逐值一致）；
   - 与 C 的有意差异：C readbiaf OSB else 分支未检查 bias_ix>0，对未收录码（bias_ix=−1）
     会写 cbias[−2] 越界（UB）；Java 防御性跳过。C readdcb 失败时 −1 哨兵被 corr_meas
     应用为 −1 m 伪距偏移（二次缺陷）；Java 失败时保持 0（无偏移）。
2. **RINEX 路径接线**：PostPosProcessor.setDcbPath()/RinexPppProcessor.setDcbPath()
   （对齐 RTCM 路径 PppProcessor 已有的 readdcb 调用）。
3. **RtkConfig.enableIonoEstRobust**（JAVA-ONLY，默认 false）：开启后 EST 模式对本历元
   刚 initx 的 bias/iono 状态（协方差恰为 VAR_BIAS/VAR_IONO=3600，尚未被任何 KF 更新
   吸收，覆盖首历元与周跳/超时重置）跳过相位行的 prefit maxinno 剔除，让 EKF 一次性
   吸收常数偏差；状态被估计一历元后自动恢复正常剔除。仅放宽相位行（code==0），
   码行剔除与 C 一致；开关关闭时判定恒 false，与 C 行为逐值一致。
   拷贝构造已同步复制该字段。
4. **测试**：PppValidationTest 新增 3.14/3.15（EST±MultiSys + WUM DCB，无开关），
   6 个既有 EST 配置改用 enableIonoEstRobust=true。WUM0MGXRAP_20240600000_01D_01D_OSB.BIA
   （2024 DOY 060，与测试数据日期匹配）列窗口 str2num(70,21) 逐值核验命中。

### 验证（PppValidationTest，2024 DOY 060 ALGO，2880 历元）

| 配置 | 修复前 success | 修复后 success | ENU RMS（统计窗口） |
|---|---|---|---|
| IFLC→EST + Robust | 0/2880 | 401/2880 | E=5.55cm N=3.73cm U=3.21cm |
| EST+MultiSys + Robust | 0/2880 | 509/2880 | E=4.82cm N=1.52cm U=5.24cm |
| ISB+EST + Robust | 0/2880 | 401/2880 | — |
| ISB+EST+Multi + Robust | 0/2880 | 415/2880 | — |
| IERS2010+EST+Multi + Robust | 0/2880 | 509/2880 | — |
| ALL-COMBINED + Robust | 0/2880 | 415/2880 | — |
| EST+DCB（无开关，C 对齐正解） | 0/2880 | 4/2880 | — |
| EST+MultiSys+DCB（无开关） | 0/2880 | 0/2880 | — |
| IFLC 基线（对照） | 2679/2880 | 不变 | — |

Tests run: 15, Failures: 0, Errors: 0（BUILD SUCCESS）。

### 结论与记录

- enableIonoEstRobust 解除死锁有效（0→401-509），EST+MultiSys 精度 E 4.8cm/N 1.5cm/
  U 5.2cm。成功率 14-18% 显著低于 IFLC 基线 93%：当时推测剩余失败源于接收机 DCB
  与"被剔卫星的 outc 重置链"，属 EST 体系固有困难——后续 FIX-ALG-021 实锤重置链
  为两处 Java 移植偏差并修复，EST 6 配置已达 2880/2880；接收机 DCB 缺产品的影响
  由 enableIonoEstRobust 与 DCB 产品两条路径兜底。
- **修正此前结论**：\"C 靠 postpos 自动加载 DCB 规避 EST 死锁\"的推断被实测证伪——
  仅含卫星 OSB 的 BIA/BSX 产品几乎无效果（0→4），接收机码偏差才是泄漏主项。
  C 版 headless postpos 的 EST 行为需另行实测确认（尚无 C 版 EST 参考结果）。
- DCB 移植本身（DcbReader）对 RTCM/RINEX 路径的码偏差应用是独立价值：IFLC/SPP
  体系的码偏差修正与未来 PPP-AR/OSB 体系复用。

## FIX-ALG-021 EST 残余失败：bias/iono 超时重置链与 C 偏差

**日期**: 2026-10-21
**类别**: PPP / 移植偏差修正
**现象**: FIX-ALG-020 后 EST 成功率 401~509/2880 仍远低于 IFLC 基线 93%。被剔卫星
一旦退出就几乎不再回归，无法随几何条件改善重新进入解算。

### 根因（两处 Java 与 C ppp.c 的实质偏差）

1. **udbias 相位偏差超时重置条件被改**（原 PppCore L477-489 vs C ppp.c L744-748）：
   - C：`if (++outc[f] > maxout || modear==ARMODE_INST || clk_jump) initx(0.0, 0.0, IB(...))`
     ——无条件重置、方差 0.0（擦除状态）、不清 outc/vsat；
   - Java 原实现：`if (++outc[f] > maxout && vsat[f] != 0)` 且要求 `x[idx]!=0.0`、
     方差用 VAR_BIAS、重置后 `outc[f]=0; vsat[f]=0`。
   - 后果链：卫星被 pppRes 剔除后 vsat 不再置位（原实现重置时还会主动清 vsat），
     `vsat!=0` 条件不满足 → 被剔卫星的 stale bias **永不重置** → 卫星重新出现时
     仍带旧 bias → prefit 残差超限继续被剔 → 永久退出解算。
2. **udiono 电离层重初始化多加 outc 条件**（原 PppCore L427 vs C ppp.c L681）：
   - C：`if (x[j]==0.0)` 无条件初始化——GAP_RESION 重置（L674-676 直接 `x[j]=0.0`，
     outc 不动）后下一观测历元即重建状态；
   - Java 原实现：`if (x[j]==0.0 && outc[0] <= GAP_RESION)`——重置发生时 outc 已超
     gap 且无人清零 → 条件恒假 → **电离层状态永不重建** → 该卫星 iono 恒 0、
     残差超限被剔 → 永久退出。
3. **附带偏差**：Java udiono 初始化分支多了两处 `testsnr()` 调用（C udiono_ppp 无
   SNR 掩码检查），低 SNR 卫星电离层无法初始化。

### 修复内容（PppCore.java）

1. udbias 重置块对齐 C L744-748：`++outc[f] > maxout || modear == ARMODE_INST`
   时无条件 `initx(x, P, nx, 0.0, 0.0, IB(i+1, f, opt))`；移除 vsat 条件、x[idx]!=0
   守卫、VAR_BIAS、outc/vsat 清零。C 的 `||clk_jump`（日边界钟跳，posopt[5]）无 Java
   对应机制，注释注明。MW/GF 周跳检测历史清理（mw/gf/xrwl/rw/xswl/niwl）保留为
   JAVA-ONLY 扩展（注释标注），重置后重建周跳检测基准。
2. udiono 初始化对齐 C L681-697：条件改回 `if (x[j] == 0.0)`，检查顺序对齐 C
   （P[0]/P[f2]/freq1/freq2 四条件一并判断），移除两处 testsnr。

### 验证（PppValidationTest，2024 DOY 060 ALGO，2880 历元，15/15 通过）

| 配置 | 修复前 success（FIX-ALG-020 后） | 修复后 success | 修复后 ENU RMS（统计窗口） |
|---|---|---|---|
| IFLC→EST + Robust | 401/2880 | **2880/2880** | E=3.83cm N=0.86cm U=6.01cm |
| EST+MultiSys + Robust | 509/2880 | **2880/2880** | E=3.07cm N=0.71cm U=6.40cm |
| ISB+EST + Robust | 401/2880 | **2880/2880** | — |
| ISB+EST+Multi + Robust | 415/2880 | **2880/2880** | — |
| IERS2010+EST+Multi + Robust | 509/2880 | **2880/2880** | — |
| ALL-COMBINED + Robust | 415/2880 | **2880/2880** | — |
| EST+DCB（无开关，C 对齐正解） | 4/2880 | **1537/2880** | E=2.39cm N=1.70cm U=8.36cm |
| EST+MultiSys+DCB（无开关） | 0/2880 | **1760/2880** | E=5.87cm N=1.47cm U=9.87cm |
| IFLC 基线（对照） | 2679/2880 | **2880/2880** | E=1.32cm N=1.01cm U=5.07cm |

IFLC 基线与全部 EST 配置同步受益：被剔卫星经超时重置后可重新进入解算。
ISB/IERS2010 系配置的 ENU RMS（E 0.42/N 1.07~1.40m）为该项自身系统差
（FIX-ALG-020 已记录"ISB、IERS2010 优化项当前无可见收益"），与本修复无关。

### 结论

- 超时重置链修复后，被剔卫星在 outc 超过 maxout（默认 5）后 bias/iono 被擦除，
  重新观测时经 MW/LC 与伪距差重新初始化，可重新进入解算——EST 体系内卫星级
  死亡循环解除。clk_jump 机制未移植（需 posopt[5] 日边界处理链），日边界数据的
  行为与 C 存在已知差异，不影响常规单日解算。

---

## FIX-ALG-022：PPP-AR WL 门限按 PRIDE-PPPAR 对齐（OSB 完备性 + maxdev 检验 + PartialAR 双重改正修复）

日期：2026-10-21　类别：JAVA-ONLY 扩展（PPP-AR，无 RTKLIB 2.5.0 C 对齐锚点，参考锚点 PRIDE-PPPAR 3.0）

### 背景

PPP-AR（WUM OSB 绝对改正模式）固定率极低且此前诊断确认 WUM 产品存在卫星级
MW 残余偏差（30 颗 OSB 齐全 GPS 星中 10 颗 ±0.12~0.22 周）。按 PRIDE-PPPAR
参考实施三缺口清单：① OSB 完备性门槛 ② WL 弧段加权平均 ③ maxdev/maxsig
统计检验。

### 核对结论（缺口②无需修改）

- `PppCore` 弧段累加（xrwl/rw/xswl/niwl）与 PRIDE `lsq_add_obs.f90` L140-170
  完全等价：wele 权重（el≥30° 取 1.0，否则 2·sin(el)）一致；PRIDE 的 zw 基准
  （弧段首历元 MW）在写 amb 文件时加回（`lsq.f90` L584 `xrwl = xrwl/rw + zw`），
  Java 直接累加绝对 MW 数学等价。
- σ 公式一致：`lsq.f90` L578-584 `xswl = sqrt(E[x²] − mean²)/sqrt(iobs−1)` 与
  Java `sqrt(wlVar)/sqrt(niwl−1)` 相同。
- PRIDE `read_ambiguity.f90` 的弧段预滤 `xswl×3 > 0.2 周` 与 Java L90-97 的
  `wlSigma×3 > 0.2` 拒绝一致（已存在）。

### 修改内容

1. **RtkConfig**：新增 `pppArWlMaxdev = 0.20`（PRIDE `table/config_template`
   'Widelane decision = 0.20 0.15 1000.' 第 1 参数，单位周），拷贝构造同步复制。

2. **PppAmbFix.pppAmbFixWlNl**（主路径）：
   - **OSB 完备性门槛扩展**（缺口①）：原仅检查两个相位 OSB，现对齐 PRIDE
     `lsq.f90` L382-395 `nbias_used ≥ 4` 语义——L1/L2 相位与码四个信号的 OSB
     全部非零才可参与 AR，缺失者仅保浮点。该门槛将 GAL/BDS（第二频码 OSB 全 0）
     与 G01/G08（无 OSB 行）排除出 AR 候选。
   - **maxdev 统计检验**（缺口③）：对齐 PRIDE `fixamb_rounding.f90` L88 +
     `bdeci` 的 prob=0 硬条件——`|frac − 历元中位数| ≥ pppArWlMaxdev` 的卫星
     拒绝参与 WL 固定。中位数对齐是未差分模式对 PRIDE 单差 rwl 的必要对应
     （SD 中接收机公共偏差自然消除，未差分模式下必须显式去除）。
   - **LAMBDA 输入整数基准对齐**：通过门限的卫星 yWl 减去历元中位数后进
     LAMBDA，使固定整数位于真实 N_wl 网格（公共平移不改变整数最小二乘解，
     但保证固定值语义与 NL 构造一致）。
   - wl_maxsig=0.15 未单独实现：PRIDE 管线中该门限被更严的弧段预滤
     （3σ>0.2，即 σ_mean>0.0667）覆盖，属冗余条件（SD σ=sqrt(σ1²+σ2²)≤0.094
     恒满足 0.15 门限），记录于此备查。
   - `nbWl < 4` 下限检查移至 dev 门限之后（对齐 PRIDE 'Critical search'
     minsav=4 语义）。

3. **PppAmbFix.pppPartialAR**：
   - 同步加入 OSB 完备性门槛与候选级中位数 dev 门限。
   - **双重改正修复**：OSB 模式下原代码 `yWl = wlMean − fcbWl`，而 wlMean 已经
     在 mwmeas 中完成绝对 OSB 改正，getWlFcb 在 OSB 模式返回
     `osbL1/λ1 − osbL2/λ2`（非零），构成二次改正；修复为 `yWl = wlMean − medCand`
     （与主路径一致，legacy FCB 模式保留 `+ fcbWl`）。

### A/B 验证（PppArValidationTest，ALGO 2024 DOY 060，2880 历元，6/6 通过）

基线 = 仅回退本轮修改的两个文件（PppAmbFix.java / RtkConfig.java）重建；
修复版 = 本轮全部修改；其余工作区状态两轮完全一致。

| 配置 | 基线 fix（固定率） | 修复后 fix（固定率） | 基线 ENU RMS | 修复后 ENU RMS |
|---|---|---|---|---|
| FixHold | 0（0.0%） | 0（0.0%） | E=1.44cm N=1.05cm U=4.89cm | 同基线 |
| Partial | 0（0.0%） | **50（1.7%）** | E=1.44cm N=1.05cm U=4.89cm | E=32.9cm N=10.4cm U=24.9cm |
| WL+NL | 0（0.0%） | 0（0.0%） | 同上 | 同上 |
| EST+ISB | 4（0.2%） | **19（1.0%）** | E=42.4cm N=131.1cm U=69.4cm | 同基线（4 位小数一致） |

- EST+ISB：固定历元误差与浮点误差同量级（RMS 4 位小数不变），非假 Fix。
- Partial：双重改正修复使 LAMBDA 输入从垃圾（原二次改正导致 ratio 恒败、
  fix=0 的"负保护"）变为合理，50 个 fix 中按 RMS 分解每固定历元误差约 2m，
  **全部为假 Fix**——见下节已知问题。
- dev 门限运行期零拒绝/少量拒绝（medFrac≈−0.095，发散星偏差多在 0.12~0.19
  区间，低于 0.20 门限），当前主要生效项为 OSB 完备性门槛。

### 已知问题（后续修复项，本轮不处理）

Partial 配置新暴露的假 Fix 属 per-epoch NL LAMBDA 自洽假 Fix（与既有认知
"整数域统计门限无法拦截自洽假 Fix"同类）：逐历元 LAMBDA 缺少 PRIDE 的弧段级
平均（session 级 xrwl/xamb）、minsav=4 保留下限、chisq 全局检验与 SD 结构
兜底。PRIDE 通过弧段累计+统计检验拒绝实现兜底，Java per-epoch 实现需要对应
的 NL 弧段机制才能获得可信固定，已列为后续修复方向。

### 结论

- 缺口①③已按 PRIDE 语义落地，缺口②经核对确认既有实现等价、无需修改。
- EST+ISB 固定率 0.2%→1.0%，浮点基线（PppValidationTest）无回归。
- Partial 路径的假 Fix 为既有深层问题（per-epoch NL 自洽假 Fix）被双重改正
  bug 掩盖后的暴露，非本轮修改引入的算法错误；双重改正本身必须修复。

---

## FIX-ALG-023：PPP-AR NL 弧段级机制（Partial 路径 per-epoch NL 自洽假 Fix 修复）

日期：2026-10-21　类别：JAVA-ONLY 扩展（PPP-AR，无 RTKLIB 2.5.0 C 对齐锚点，参考锚点 PRIDE-PPPAR 3.0）

### 背景与问题现象

FIX-ALG-022 修复 PartialAR 双重改正后，Partial 配置暴露 50 个固定历元
（1.7%），但按 RMS 分解每固定历元误差约 2 m，ENU RMS E=32.9 cm——
**全部为假 Fix**。根因为 per-epoch NL LAMBDA 自洽假 Fix：逐历元 NL 浮点值
缺少 PRIDE 的弧段级平均与统计检验兜底，单历元噪声即可将浮点值推入错误
整数邻域（浮点已自洽，ratio 检验通过；整数域统计门限无法拦截——坏 fix 的
z 反而更小，方向反直觉，见既有认知）。

PRIDE-PPPAR 对应机制（源码实证）：

- 弧段累加：`lsq_add_obs.f90` L140-170，MW 与滤波 NL 状态以 wele 权重累加
  （el≥30° 取 1.0，否则 2·sin(el)），周跳/失锁开启新弧段；
- σ 统计：`lsq.f90` L578-584，`sigma = sqrt(E[x²] − mean²)/sqrt(iobs−1)`；
- 决策门限：`table/config_template` 'Narrowlane decision = 0.15 0.15 1000.'
  （nl_maxdev/nl_maxsig，单位周）与 'Critical search = 3 4 1.8 3.0'
  （minsav=4，弧段最短累积历元数）；`bdeci.f90` dev≥cutdev 或
  sigm>cutsig → prob=0（拒绝参与固定）。

### 修改内容（7 项）

1. **Ssat**：新增 NL 弧段累加字段 `xrnl`（加权和，周）、`rn`（权重和）、
   `xsnl`（加权平方和，周²）、`ninl`（累积历元数），与既有 WL 弧段字段
   （xrwl/rw/xswl/niwl）对称。
2. **RtkConfig**：新增 `pppArNlMaxdev = 0.15`、`pppArNlMaxsig = 0.15`
   （PRIDE 'Narrowlane decision' 第 1/2 参数）、`pppArNlMinsav = 4`
   （PRIDE 'Critical search' minsav），拷贝构造同步复制。
3. **PppCore** 两处偏差状态重置点补 NL 弧段重置（xrnl=rn=xsnl=0, ninl=0）：
   udbias 超时重置点（FIX-ALG-021 对齐 C L744-748 处）与 slip 重初始化点
   （对齐 C L681 处）——偏差状态重建等价于开启新弧段。
4. **PppAmbFix 新增 `accumulateNlArc()`**，在 `pppPartialAR` 入口（滤波更新
   后，与固定消费的状态一致）调用：遍历有效卫星（vs==0/azel<elmin/
   idx>=nx 跳过；`x[idx]==0.0` 未初始化哨兵跳过；f1/f2 由 code[0][0]/
   code[1][0] 经 sat2freq 求取，`|f1−f2|<1.0` 无双频跳过），将滤波 NL
   状态原始量 `x[IB]/λ_NL` 以 wele 权重累加（el≥30°→1.0，0<el<30°→
   2·sin(el)，el≤0→0）；`slip[0]!=0 || lock[0]<=1` 时重置该星弧段。
5. **候选级 NL 弧段成熟度门**：`ninl < pppArNlMinsav || rn <= 0` 的卫星
   拒绝参与固定（单历元弧直接拒绝）；ninl>1 时附加 σ 门
   `sqrt(xsnl/rn − mean²)/sqrt(ninl−1) > pppArNlMaxsig` 拒绝（ninl=1 时
   σ 无定义故跳过检查，兼容合成单测 minsav=1 设置）。
6. **候选级 NL dev 门**：`wlProv = round(wlVal − medCand)`（候选级临时 WL
   整数，中位数对齐消公共接收机 NL UPD——undifferenced 模式对 PRIDE
   单差公共偏差相消的必要对应），`nlProv = xrnl/rn − wlProv·f2/(f1−f2)
   ± fcbNl`（OSB 模式取 −fcbNl），`|frNl − medNl| ≥ pppArNlMaxdev` 的
   卫星拒绝。
7. **子集 NL 浮点值改用弧段均值**：LAMBDA 输入由 per-epoch 状态改为
   `nlFloat = xrnl/rn − wlFixed[i]·f2/(f1−f2)`。弧段累加的原始量不含
   WL 改正项（与单历元 WL 固定结果解耦，避免 WL 结果反复触发污染弧段），
   固定时用当期 wlFixed 补减。子集 yNl 保持既有 datum 行为不做中位数
   对齐，仅门限检验使用中位数对齐。

### chisq 等价性说明

PRIDE 全局 chisq 检验的角色由本轮组合承担：弧段 σ 门（nl_maxsig）+ dev 门
（nl_maxdev）+ 既有 ratio 检验。整数域 post-fix 统计（|y0−b|/σ）已实证
无法拦截自洽假 Fix（好 fix 的 z 大、坏 fix 的 z 反而小——浮点已陷入错误
整数邻域），故不做整数域 chisq。

### A/B 验证（PppArValidationTest，ALGO 2024 DOY 060，2880 历元，7/7 通过）

| 配置 | 修复前 fix（固定率） | 修复后 fix（固定率） | 修复前 ENU RMS | 修复后 ENU RMS |
|---|---|---|---|---|
| Partial | 50（1.7%，全为 ~2m 假 Fix） | **38（1.3%，全真）** | E=32.9cm N=10.4cm U=24.9cm | E=1.56cm N=1.03cm U=4.85cm |
| FixHold | 0（0.0%） | 0（0.0%） | E=1.44cm N=1.05cm U=4.89cm | 不变 |
| 3F-EWL+WL+NL | 0（0.0%） | 0（0.0%） | 同上 | 不变 |
| WL+NL | 0（0.0%） | 0（0.0%） | 同上 | 不变 |
| EST+ISB | 19（1.0%） | 19（1.0%） | E=42.4cm N=131.1cm U=69.4cm | 不变 |

- Partial 修复后 38 个固定历元误差与浮点同量级（ENU 2D=0.0187m），假 Fix
  全部消除；固定率 1.7%→1.3%（弧段成熟度门 minsav=4 的预期代价），ENU RMS
  回落至浮点基线水平（0.0144/0.0105/0.0489）。
- NL ratio 值健康（3.13~19.23）。
- `PppArFixVerificationTest` 4/4 通过（3 处合成单测 cfg 补
  `pppArNlMinsav = 1`——真实管线默认 4，合成单历元设置需放宽成熟度门）。
- `PppValidationTest` 15/15 通过，浮点基线无回归。

### 对齐结论

- undifferenced 模式的中位数对齐与 PRIDE 单差接收机公共偏差相消数学等价。
- 弧段累加原始量选择（x[IB]/λ_NL，WL 改正项固定时补减）保证弧段序列与
  单历元 WL 固定结果解耦，是未差分实现区别于 PRIDE SD 流水线的关键适配点。
- 主路径 `pppAmbFixWlNl` 与 3F 路径（EWL+WL+NL）的 NL 仍为 per-epoch，
  未接入弧段机制，列为后续候选修复项（两路径当前 fix=0，无假 Fix 风险
  暴露）。

---

## FIX-ALG-024：3F PPP-AR（EWL+WL+NL）WL/NL epoch median 对齐 + IFLC 逐星取整

日期：2026-10-22　类别：JAVA-ONLY 扩展（3F PPP-AR，无 RTKLIB 2.5.0 C 对齐锚点，参考锚点 PRIDE-PPPAR 3.0）

### 问题现象

3F 配置（IONOOPT_IFLC + EWL/WL/NL 三级固定）固定率 0%，三处卡点：

1. **WL 阶段小数偏差**：undifferenced WL 值含公共接收机 WL UPD（OSB 产品
   仅改正卫星端），逐星取整 frac 分布 0.15~0.48，nFixed<4 恒失败；
2. **NL 阶段 LAMBDA 退化**：IFLC 下 QbNl 为对角阵（rtk.P IFLC 单模糊度
   协方差 / λ_NL²），LAMBDA 丧失交叉协方差优势退化为逐星独立取整，
   ratio 恒 1.00 < 3.0；
3. **编译错误**：3F 方法内 `hasDualFreq`（循环内定义循环外使用）、
   `info`、`wlRatio`（分支内定义分支外使用）三处变量作用域错误。

### 修改内容

1. **编译作用域修复（3 处）**：WL 固定分支条件 `hasDualFreq`→
   `useDualFreqWl`（方法级判定）；`info`/`wlRatio`/`nlRatio` 声明提升至
   分支外（取整分支无 ratio 语义，默认 0.0）。
2. **WL epoch median 对齐**：OSB/FCB 改正后、固定前统一计算 frac 中位数
   `medWl3f` 并从 yWl 减去——LAMBDA 分支（EST 双频）与逐星取整分支
   （IFLC）共用同一 datum。与主路径 medFrac 语义一致（undifferenced 模式
   对 PRIDE 单差接收机公共偏差相消的必要对应）。
3. **IFLC 分支改逐星取整（WL 与 NL 同构）**：median 对齐 + round +
   frac<0.25 门 + nFixed≥4 下限；LAMBDA 仅保留 EST 双频路径（非对角
   协方差下有效）。NL 取整对象为 yNl（NL 滤波状态 − 对齐 wlFixed·
   f2/(f1−f2) + OSB 改正），medNl3f 对齐后取整。
4. **NL 重构循环**：`fixedNl` 由 `round(bNl[i*2])` 改为 `nlFixed[i]`。

### datum 自洽性说明

medWl3f/medNl3f 仅去除公共 frac 偏移（|med|<0.5 时 round(y−med) 恰为
真实整数网格）；fixedNl 与 wlFixed 均落在真实整数，重构公式
`fixedAmb = N_nl·λ_NL + N_wl·f2·λ_WL/(f1+f2)` 使用真实整数，无残余
datum 偏移。NL 构造中 wlFixed 与 NL 状态的 UPD 关系经 medNl3f 二次
对齐后自洽。

### 验证（PppArValidationTest，ALGO 2024 DOY 060，2880 历元，7/7 通过）

| 阶段 | 修复前 | 修复后 |
|---|---|---|
| EWL 门 | 卡 30% | 后期 70%（7/10） |
| WL 取整 | frac 0.15~0.48，nFixed<4 失败 | 8/10（80%）通过，maxFrac≈0.31 |
| NL 固定 | LAMBDA ratio 恒 1.00 | rounding 50~70% 通过（medFrac −0.03~−0.28） |
| **3F 配置** | **fix=0（0.0%）** | **fix=1226（65.7%）** |

- 3F 固定解 ENU RMS E=2.16cm N=2.81cm U=5.08cm 2D=3.54cm，与浮点基线
  （E=2.23cm N=0.85cm U=4.70cm）同量级，无假 Fix 迹象（假 Fix 典型
  ~2m 误差）。
- 其余 6 配置（Partial 38 真 fix / FixHold / WL+NL / EST+ISB 等）零回归。

### 结论

- 对角协方差下 LAMBDA 退化的正确处理是逐星取整 + median 对齐，而非
  强行 LAMBDA（ratio 检验在退化场景无区分度）。
- NL 弧段机制（FIX-ALG-023，Partial 路径）未接入 3F 路径——3F NL 为
  per-epoch 取整，本数据集未见假 Fix，弧段化列为后续候选。

---

## FIX-ALG-025：3F NL 构造公式验证 + EST 分支系数修复 + 弧段均值实验负结果

日期：2026-10-22　类别：缺陷修复（EST 分支公式）+ JAVA-ONLY 实验记录（弧段化负结果回退）

### NL 浮点构造公式验证（IFLC 语义，P1 清单项）

IFLC 下 x[IB] 为 IF 组合模糊度（米）：`x[IB] = c·(f1·N1 − f2·N2)/(f1²−f2²)`。
推导链：

- `x[IB]/λ_NL = (f1·N1 − f2·N2)/(f1−f2)`（λ_NL = c/(f1+f2)）
- `nlFloat = x[IB]/λ_NL − wlFixed·f2/(f1−f2) = [f1·N1 − f2·N2 − f2·(N1−N2)]/(f1−f2) ≡ N1`

**结论：nlFloat 数学上恒等于 L1 整周模糊度 N1**（变量名 "NL" 有历史误导，
实为 N1），落在均匀整数格，取整/frac 门语义正确。重构公式
`fixedAmb = N1·λ_NL + WL·f2·λ_WL/(f1+f2) = c·(f1·N1 − f2·N2)/(f1²−f2²)`
精确复原 IF 模糊度真值——与取整路径 datum 完全自洽，FIX-ALG-024 的
1226 个真 fix（无假 Fix）为实测佐证。

### EST 分支系数缺陷修复

`nlFloat = (f1·n1 + f2·n2)/(f1+f2) − wlFixed·f2/(f1−f2)`（n1/n2 为 L1/L2
模糊度周值）中减项系数错误：减 f2/(f1−f2) 后该量既非 N1 也非窄巷 NL
（通分分子 = N1·(f1²−2f1f2−f2²) + 2f1f2·N2，非整数格点），且与重构公式
`n1 = fixedNl + f2·WL/(f1+f2)`（假设 fixedNl 为窄巷 NL）不自洽。

修复（与 IFLC 分支语义统一为 N1；注意两分支符号相反）：

- 构造：减项 `wlFixed·f2/(f1−f2)` → **`+ wlFixed·f2/(f1+f2)`**（系数与
  符号同时修正；此时 `(f1·n1+f2·n2)/(f1+f2) + WL·f2/(f1+f2) ≡ n1`：
  分子 = N1·(f1+f2)，L1 整周。符号与 IFLC 分支（减）相反，因 EST 原始
  量（窄巷 NL）N2 系数为 +f2，须 +WL·f2 展开的 −f2·N2 相消；IFLC 原始
  量 N2 系数为 −f2，−WL·f2 的 +f2·N2 相消）；
- 重构：`n1 = fixedNl`（直接为 L1）、`n2 = fixedNl − wlFixed[i]`
  （N2 = N1 − WL），消除中间换算。

勘误记录：本条目初稿曾将构造修复误写为"减 f2/(f1+f2) ≡ n1"（N1 系数
误算为 f1−f2），经复核（用户 2026-10-22 指出）确认正确符号为**加号**，
N1 = NL + f2·WL/(f1+f2)。

注意：EST 3F 分支当前无测试覆盖（3F 测试用 IONOOPT_IFLC；EST 双频
PPP-AR 走主路径 pppAmbFixWlNl），正确性由公式推导保证。

### 弧段均值实验（负结果，已回退）

将 FIX-ALG-023 弧段机制接入 3F：入口 `accumulateNlArc()` + IFLC NL 取整
优先用弧段均值（immature 弧 fallback 回 per-epoch）。实测（ALGO 2880
历元）：

| 指标 | per-epoch（FIX-ALG-024） | 弧段均值实验 |
|---|---|---|
| fix | 1226（65.7%） | 1454（68.5%） |
| ENU RMS | E=2.16 N=2.81 U=5.08 2D=3.54cm | E=7.99 N=1.91 U=12.14 **2D=8.21cm** |
| Mean dE/dU | +2.06/+1.65cm | **+7.97/−11.21cm（系统性）** |

固定率提升但质量显著恶化：fallback 式设计在 ninl 达到 minsav 后即放行
弧段均值，收敛期起始的弧段其均值被初始瞬态拖累（弧段均值滞后于已收敛
的滤波值），产生同向 NL 偏差 → 假 Fix（cm~dm 级）。对比 pppPartialAR
弧段均值有效的原因：**拒绝式**（immature 弧整星拒绝，不 fallback）+
σ 门（xsnl 统计）+ dev 门三重防护。3F 若引入弧段均值需同步移植全部
拒绝式门控，收益存疑（per-epoch 滤波值已收敛且质量更优）。

**决策：回退 3F 弧段均值，保持 per-epoch**（代码内留注释说明原因）。

### 验证（PppArValidationTest 7/7 通过）

- 3F 配置恢复 FIX-ALG-024 状态：fix=1226（65.7%），ENU 2D=3.54cm；
- Partial（38 真 fix）/ EST+ISB（19）/ 其余配置零回归。

### 结论

- IFLC NL 构造语义为 N1（非窄巷 NL），与重构公式自洽——3F 链路公式
  体系完备性确认；
- EST 分支系数缺陷已修（无测试覆盖，公式推导保证）；
- 弧段均值适用于"拒绝式 + 全门"场景（Partial），不适用于 fallback 式
  取整场景（3F）；3F 弧段化以负结果归档，不再列为候选。

## FIX-ALG-026：双频主路径/PartialAR IFLC 逐星取整分支 + FRACDUMP 定位插桩

**日期**: 2026-10-22
**类别**: 缺陷修复（IFLC LAMBDA 退化）+ 诊断插桩
**前置**: FIX-ALG-024（3F 同类修复）、FIX-ALG-025（NL 构造语义 ≡ N1 确认）。
用户《PPP-AR 全模式核实报告》指出：双频主路径 WL+NL / FixHold 固定率 0%
（ratio ~1.05）、Partial 1.3%——IFLC 模式（单组合模糊度/星）下 rtk.P 给出
对角 QbWl/QbNl，LAMBDA 搜索退化为逐星取整且 ratio 恒 ~1.0-1.05，过不了
ratio 门。

### 修复内容（P0，用户《修正后的实施方案》）

1. **主路径 `pppAmbFixWlNl` IFLC 分支**（WL 阶段）：跳过 QbWl/LAMBDA，
   直接对已 median 对齐的 MW 弧段均值逐星取整；frac≥0.25 剔除并原位压缩
   satList/freqList/yWl；nFixed<4 返回 −1；wlRatio=0（取整路径无 ratio
   语义）。
2. **主路径 NL 阶段 IFLC 分支**：QbNl 不再填充（对角阵无意义）；对原始
   nlFloat 做 frac 中位数对齐（消接收机 NL UPD）→ 逐星取整 + frac<0.25
   门 + 压缩；nFixed<4 返回 −1；`rtk.sol.ratio=0`。
3. **`pppPartialAR` 同构 IFLC 分支**（早期返回式，位于方差序子集搜索
   之前）：WL 取整对象 = wlVals−medCand（候选级 median 对齐）；NL 取整
   对象 = 弧段均值 xrnl/rn 构造的 nlProv（与 dev 门 provisional 同源），
   kept 子集上二次 median 对齐后取整；应用 `fixedAmb = N1·λ_NL +
   WL·f2·λ_WL/(f1+f2)`。Partial 语义由上游拒绝式 dev 门（WL maxdev +
   NL maxdev + minsav + σ 门）保持。
4. **FRACDUMP 插桩**（环境变量 `PPPAR_FRAC_DUMP=1` 启用，默认关闭）：
   `dumpFrac(tag,sats,n,vals,fixed,offset)` 输出每星对齐值/固定整数/
   frac；插桩点 MAIN-WL/MAIN-NL/PARTIAL-WL/PARTIAL-NL/3F-WL（offset 0，
   值已对齐）/3F-NL（offset=medNl3f，值未预对齐）。
5. 编译修复：NL else 分支 `info` 补 `int` 声明（WL else 的声明为块级
   作用域）。

### 验证（PppArValidationTest 7/7 通过，PPPAR_FRAC_DUMP=1）

| 模式 | 修复前 | 修复后 |
|---|---|---|
| PPP-AR(WL+NL) | 0% 固定（ratio~1.05） | 复活：2D=14.75cm，Mean dE=+7.95/dN=−12.20/dU=+33.13cm |
| PPP-AR(FixHold) | 0% 固定 | 复活：与 WL+NL 逐值相同（弧段均值稳定 → 每历元相同整数，hold 无额外可见效应） |
| PPP-AR(Partial) | 1.3% | 复活：2D=29.84cm，Mean dU=+48.53cm |
| PPP-AR(3F) | 2D=3.54cm | **逐值零回归**（E=2.16/N=2.81/U=5.08, Mean +2.06/−2.75/+1.65cm） |
| PPP-Float | 2D=1.78cm | 零回归 |
| PPP-AR(EST+ISB) | 发散 | 无回归（本修复不触及 EST 分支） |

三模式复活但固定解带**恒定**系统偏移（RMS≈Mean，全时序常数偏移）→
存在 wrong fix（用户核实报告对 3F 同类问题的判定口径：Mean 偏移暴露
RMS 稀释的系统性 wrong fix）。

### BUG#2 定位（FRACDUMP 分析，wrong fix 源头确认）

**方法**：3F 配置已验证正确（3.54cm 近真值），其 MW 弧段均值无时钟基准、
同弧段固定整数必须与主路径一致。逐卫星比对 MAIN-WL 与 3F-WL 的主弧段
众数整数。

**结论：wrong fix 源头 = WL 阶段，NL 阶段排除**：

| 卫星 | MAIN-WL 主弧段整数（N/占比） | 3F-WL 参考 | 偏差 |
|---|---|---|---|
| PRN 23 | −4（1709/100%） | −5（154/88.5%） | **+1** |
| PRN 25 | −19/−39/−48（三弧段） | −20/−40/−49 | **+1（全弧段一致）** |
| PRN 26 | −11（1362/100%） | −10（351/96.4%） | **−1** |
| PRN 30 | −33（1289/93.1%） | −32（204/49.9%） | 疑似 −1（3F 双值，存疑） |

其余重叠卫星（2/3/4/5/6/7/9/11/14/18/24/27/28/32 等）逐弧段一致。

- **NL 阶段排除依据**：kept 集合满足 |frac|=|b_i−e|<0.25<0.5，数学上
  不可能取整错；NL 错误只能是 WL 错误经 nlFloat 构造（±1.43 周平移）
  与 NL median 被拖动后的连锁表现。MAIN-NL 的 kept frac 全部 <0.25 佐证。
- **机制**：PRN 23/25/26 的 OSB 改正后 MW 弧段均值携带 ~±0.9 周**系统性
  偏差**（相对真实整数格）。frac 门只度量相对取整值的小数（错星仅
  −0.09~−0.1），对整周级偏差**原理性盲区**；wlSig 门度量弧段内散差，
  系统偏差不可见。错误 WL 经 nlFloat = N1 分量 + (N_wl_true−N_wl_used)·
  f2/(f1−f2) 平移 ∓1.43 周进入 NL 阶段，拖动 NL median 后连锁固定错误
  N1（重建 fixedAmb 误差 ≈ ±0.377m/星，4 星足以解释 dU=+33cm）。
- **修复预案评估**（用户 P1 预案）：
  - 收紧 frac 门 0.25→0.15：**无效**——错星 frac 仅 −0.09，反而误伤
    frac −0.12~−0.16 的正确星（PRN 14/28/4/11/18，其负偏为 OSB 残差，
    |bias|<0.5 取整仍正确）；
  - 方差/σ 门：**无效**——偏差为系统性，弧段内散差小，σ 门已通过；
  - 候选可行方向（**待定版边界确认，未实施**）：整数域全星一致性检验
    （δ 门）：对每颗拟固定卫星计算 δ_i = (fixedAmb_i − x[IB]_float)/
    λ_NL，以全星中位数为基准剔除 |δ_i−med| 超限者。WL 错 ±1 时偏差
    ≈±3.5 NL 周、NL 错 ±1 时 ≈±1 周，均远超 clean 星（<0.3 周）——
    单一检验同时覆盖 WL/NL 两级整周错误。属新增检验机制，是否超出
    定版阶段"仅修 bug 不增特性"边界待用户裁决。
- 附注：MAIN 路径仅固定 GPS（GAL 被 OSB 完备性门挡出，3F 路径可固定
  GAL 61-95）——双频 IFLC 的 GAL 信号 OSB 完备性问题另行排查。

### δ 门实施（用户裁决后追加，JAVA-ONLY 开关 `enablePppArDeltaGate`，默认关闭）

**机制**：对每颗拟固定卫星计算 δ_i = (fixedAmb_i − x[IB]_float)/λ_NL（NL 周），
双重规则（门限共用 `pppArDeltaMax`，默认 0.5 半周界）：

1. **基准自检**（先行）：|medDelta| > 门限 → 整历元放弃固定。medDelta 为
   浮点-整数格公共偏移的估计，未收敛/基准错位时必须放弃——否则病态
   历元以 initx var=0 冻结错误整数并永久污染时序（实测 46 通过历元中
   18 个 |medDelta|≥1 周、最大 −2.90，是 2D 3.19cm 残余偏差的根源）。
2. **逐星剔除**：dev_i = δ_i − medDelta 超门限者剔除。dev 对公共收敛
   偏移免疫（grid_offset 相消），仅含整数错误与 OSB 残差散布：NL 错
   ±1 → dev≈±1、WL 错 ±1 → dev≈±3.5、clean 星 |dev|≈残差散布 <0.3 周。

**标定过程**（三轮实测）：

| 门限 | 结果 | 结论 |
|---|---|---|
| 0.4（无基准自检） | 6521/6521 全拒，固定率 0 | 收敛瞬态污染统计，门限过严 |
| 2.0（无基准自检） | 46/5816 通过，2D 3.19cm（Mean dE=+2.59） | 放行 NL±1 错误星（dev≈1<2.0），var=0 冻结投毒 |
| 0.5 + 基准自检 | 5996/6521 基准拦截 + 525 收敛期通过，0 次错误冻结，dev 分布在 (0.5,0.7) 存在天然间隙 | 门限最优；0 污染 |

**最终状态**：δ 门数学闭合（半周界下错误整数 sat 的 dev≥0.7 必被拒，
clean 星 dev<0.5 必保留），但该数据集（ALGO 2024060 + WUM BIA）收敛期
每历元 clean 星仅 ≤3 颗（约 7 候选中 ~4 颗带整周错误，WL±1 主导——即
PRN 23/25/26 类 OSB 整周偏差星），nFixed≥4 约定无法满足 → δ 门开启时
双频 IFLC PPP-AR 固定率为 0，安全回退浮点（1.78cm，与浮点基线逐值一致）。

**根因链（深挖后修正，2026-10-22，推翻原"L2 码 OSB 残余 ~0.9 周"定性）**：

1. **真实偏差量为 fcbWl=+0.7714 周**——WUM BIA 相位 OSB 的 WL 组合
   （osbL1/λ1−osbL2/λ2，PRN G25 实测 osbL1=−0.6396m/osbL2=−1.0092m
   逐值吻合），产品无误差；原 "~0.9 周 L2 码 OSB 残余" 系跨运行单点
   对比的混叠值。PRN 23/25/26 的三弧段一致 ±1 由 frac 跨界恒定产生
   （PRN 25 fcbWl frac=0.77 → 恒 +1），非产品固有误差。
2. **无差分 median 对齐存在整周平移自由度**（原理性，PRIDE-PPPAR 用
   星间单差规避）：medFrac 仅消除公共偏移的小数部分，整周部分原样
   保留 → 取整后全星共同偏移 round(UPD+残差中位数)；绝对整周锚依赖
   "对齐前公共偏移 frac<0.5" 的隐含假设，本数据集 NL 级跨界。
3. **medDelta 分布证据**（δ 门 5996 拒绝历元直方图）：[5,6) 占 42%
   （IFLC 浮点模糊度收敛慢，距整数格 ~0.6 WL 周）、[1,3) 占 42%、
   [3,4) 占 8%（含 +3.5326 = 全星 WL 锚 +1 的精确理论值
   0.37775/0.10695）；通过基准自检的 431 历元 medDelta 聚在 ±0.5/±0.9
   不趋于 0 → MAIN 固定整数与浮点格点间存在稳定半周~1 周系统差。
4. **3F 的 −fcbWl 为双重改正**（mwmeas 已做相位 OSB 改正一次，3F
   分支再减）：成功属"错进错出"——多减的逐星 fcbWl 使 frac 散布变大
   （MAIN 对齐后极窄 / 3F 0~0.37 形态相反），但整周平移恰好落界内，
   且 3F 取整不依赖浮点模糊度收敛（弧段 MW 直接取整）。3F 无 0.91 周
   误差（frac=0.0000 + 3.54cm 金标准）；0.7714 为 MAIN/3F 差值。

**处置收口（用户裁决 2026-10-22）**：维持 nFixed≥4 约定，双频 IFLC
PPP-AR 记录为 "无差分对齐整周平移自由度 + 数据集 clean 星不足" 的固有
受限（选项 b，深挖已完成并修正定性）；生产推荐 3F 路径。min=3 不予
采纳：medDelta≠0 的历元被基准自检整历元拦截，缩门槛不改变拦截结果；
δ 门开启时双频固定率 0 为安全回退（fail-safe），非缺陷。
pppArDeltaMax=0.5 为半周界数学锚定值（整数错误最小 dev=1 周、clean 星
散布实测 <0.5、(0.5,0.7) 实测天然间隙），非数据拟合参数，定版冻结。

### 状态

已闭环。P0 修复实施并验证（7/7 通过，3F/Float 零回归，三模式机制复活：
δ 门关时 WL+NL/FixHold 14.75cm、Partial 29.84cm）；δ 门三轮标定完成
（0.4 全拒 → 2.0 投毒 3.19cm → 0.5+基准自检 0 污染，数学闭合）；深挖
完成并修正根因定性（L2 码 OSB → 整周平移自由度 + 3F 双重改正错进错出，
见根因链）；双频 IFLC 固定率 0 按 "记录受限、生产推荐 3F" 收口。

**架构债（解冻后重构路径，定版冻结期不动）**：① OSB 应收拢至组合
构造点单次应用——mwmeas 已是此形态，ewlmeas/窄巷弧段待补齐；② 对齐
机制从 medFrac 升级为星间单差（完全消除接收机 UPD 含整周部分，PRIDE
语义），整周平移自由度方可真正消失；③ 3F 补偿（fcbEwl/fcbWl/fcbNl
三级）切断需在 ①② 完成后统一进行，3F 为已验证金标准（3.54cm），
定版阶段禁止修改。

**TODO (ARCH-DEBT)④：方案 B——OSB 观测方程单点应用架构切换**
（2026-10-22 方案 A/B 评审冻结，实时 SSR 流接入或解冻后执行）。
方案 B 内容：`enableOsb=true`（滤波观测方程 OSB 改正，PppOsbModel +
PppCore pppres L787-L793/L867 链路已实现，当前默认 false 且全仓库
无启用点，实际滤波为原始态）+ OSB 模式下固定时全部去 fcb 补偿
（3F WL 及所有 NL，数学推导：滤波已消 b_sat 后再减 fcbNl 为多减，
per-satellite 偏差 median 无法消除，偏离整数格）。前置四项（缺一
不可，全部完成前禁止切换）：

1. **EWL 缺 OSB**：ewlmeas 无 OSB 应用，fcbEwl 须保留或补 ewlmeas 改正，否则 EWL 取整零改正；
2. **BDS3 同构**：PppAmbFixBds3（WL L101 / NL L137）与 3F 同样的 OSB 模式处理需同步整改；
3. **伪距行错用相位 OSB**：PppOsbModel.osbCorrectionIfComb 的相位 OSB 组合经 PppCore 残差模型同时加进伪距行（Lc/Pc 两行共用 osbCorr），码行应使用 cbiasOsb 码 OSB 的 IF 组合；
4. **全链路重验证**：enableOsb=true 改变 MAIN/Partial/FixHold 全部浮点行为，需全链路重验证后方可收口。

## FIX-ALG-027：时变 OSB（Bias-SINEX 30s 序列）读取兼容性修复

**日期**：2026-10-22　**性质**：定版期 bug 修复（用户排雷指令 #6，不新增算法特性）

### 背景与动机

用户指令排雷：WUM BIA 为常量型（每星每码每天一条全天区间记录），
CNES GBM 0MGXRAP ABS.BIA 为 30s 时变（259MB，每星每码 ~2880 条），
OsbReader 照 WUM 常量逻辑实现。缺陷三处实锤：

1. **时间维度缺失**：START/END 列完全不解析——相位 OSB 逐行覆盖写
   `fcbWlByCode`（时变文件最终生效**最后一个历元**的值）；码 OSB 进
   `nav.cbias` 累加（2880 行累加爆炸，被 PPP 码路径对 cbias 的免疫性
   掩盖，SPP 路径受害）。
2. **GBM 行 SVN 列空缺**（列偏移）：GBM 行 PRN 落 tokens[1]，WUM 行
   填 SVN（如 G061，编号超出 PRN 域 satid2no=0）使 PRN 在 tokens[2]；
   原 tokens[2]/tokens[3] 假设使 satid2no 拿到 "C1C" 恒 0，GBM 259MB
   全文 **0 记录**被解析（2 秒读完=全部行被跳过）。
3. **码/相位混流**（加载验证阶段发现）：C1C（码 OSB）与 L1C（相位
   OSB）共享底层码号 CODE_L1C，单 (sat,code) 键使两路 OSB 写入同一
   序列（G25-L1C 列表 5762=2×2881 实锤），相位改正会**误用码偏差值**。

### 修复内容

- `data/Nav.java`：新增 `osbSeries`（`Map<Long,List<double[]>>`），
  key=((sat-1)*(MAXCODE+1)+code)×2+(码OSB:0/相位OSB:1)，
  value=List<double[]{startGpsSec,endGpsSec,valueM}>。
- `ephemeris/OsbReader.java`：
  - `parseSinexTime("yyyy:ddd:sssss")`→GPST Unix 秒；
  - 列偏移自适应：tokens[1] 为合法卫星号（GBM，SVN 空缺）→
    prn=tokens[1]/obs1=tokens[2]；否则（WUM，SVN 填充）→
    prn=tokens[2]/obs1=tokens[3]；VALUE 固定列 70-90 两版式通用；
  - 相位 OSB：快照 fcbWlByCode 覆盖写保留 + 分键写入 osbSeries；
  - 码 OSB：timedShort（区间 <86400s，如 GBM 30s）**只进 osbSeries
    不进 cbias**（防累加爆炸）；全天区间（WUM）维持 cbias 常量累加
    原语义；cbiasOsb 快照两种都写。
- `ppp/PppAmbFix.java`：`getOsbSeries(+phase)`/`getOsbAt(nav,sat,code,
  time)`——按历元命中区间，未命中取最近记录保底，最终回退快照
  getOsb；getWlFcb/getNlFcb 数值取值走 getOsbAt，getEwlFcb 加 time
  参数；完备性检查（判非零语义）保留 3 参快照。
- `ppp/PppCore.java`：`getCodeOsb(+time)` 时变优先（码键）/回退
  cbiasOsb 快照；mwmeas 两处 OSB 改正传 obs.time。
- `ppp/PppAmbFixBds3.java`：getWlFcb/getNlFcb 同步走 getOsbAt。
- **行为保持**：WUM 2024-060 常量数据集零影响（全天区间 te−ts=86400
  → cbias 原路径；相位快照值不变）。

### 验证

- 临时测试 TempOsbTimeSeriesTest（已删）：GBM 259MB 加载 3.7s/199MB，
  phaseOSB=871709 + codeOSB=1348308；G25 C1C(code)/L1C(phase)/L2W(phase)
  各 2881 条**独立**序列；osbAt(00:15)=0.2426m → osbAt(23:45)=0.2483m
  时变命中（快照=末记录 0.1955m 同量级）；GBM 下 G25 fcbWl≈−0.23 周
  （WUM 为 +0.77，不同产品不同值，合理）。
- 全套验证（GBM-2026288，PppArValidationTest 经 env 切换：PPP_DATA_SUBDIR=
  ppp/algo288、PPP_PRODUCT_SUBDIR=product288、PPP_TABLE_SUBDIR=table288、
  PPP_TEST_YEAR=2026、PPP_TEST_DOY=288；REF_ECEF 沿用 FIX-ALG-019 固定值，
  IGS14→IGS20 框架差为 cm 级常量偏置不影响 A/B 对比；ALGO 观测经
  tools/CRX2RNX.exe 由 crx.gz 转换）：

| 模式 | 总历元 | 固定率 | 2D RMS | 备注 |
|---|---|---|---|---|
| Float | 2880 | — | 3.14cm | dE=−2.66cm 系框架常量差 |
| WL+NL | 2880 | 0% (fix=0) | 3.14cm | 与 Float 逐值一致（安全回退） |
| FixHold | 2880 | 0% (fix=0) | 3.14cm | 同上 |
| Partial | 2880 | 0% (fix=0) | 3.14cm | 同上 |
| 3F-EWL+WL+NL | 2880 | 0% (fix=0) | 4.30cm | 3F IFLC 浮点自身量级 |
| EST+ISB | 2792/2880 | 0.3% (fix=7) | 20.87cm | 与 WUM 量级一致 |

**FIX-ALG-026 普适性结论**（本数据集的核心验证目标）：

1. ✅ **双频固定率 0% 跨产品复现**（WUM→GBM）——印证"无差分 median
   对齐整周平移自由度"为**普适数学规律，非 WUM 数据质量问题**。δ 门
   日志实锤：WL/NL 取整本身正常过门（WL nb=6/6 maxFrac=0.098、NL
   nFixed=4/6），但 datum offset **+2.2475 周 > 0.5** 被基准自检整历元
   拦截 → 0 假 Fix，回退解与浮点基线逐值一致。
2. ⚠️ **新现象（待深挖，非回归）**：3F 固定率 65.7%（WUM）→ 0%（GBM），
   候选拒绝链以 skipOsb=15 主导；3F"错进错出"对时变 OSB **不自动成立**，
   其成立条件（fcbWl frac<0.5 隐含假设 + 三频 OSB 完备性）待专项分析；
   定版冻结期不改 3F 代码，仅记录。
3. EST+ISB fix=7（0.3%），与 WUM 数据集（0.2%~1.0%）量级一致。

### 数据集笔误更正

FIX-ALG-026 三轮标定/深挖实际数据集为 **ALGO 2024060 + WUM 常量 BIA**
（TEST_YEAR=2024/DOY=60），该文档此前写"ALGO 2026288 + GBM OSB"有误
（GBM 2026288 数据在本条目首次投入运行）。

### 测试数据落位（不入 git，.gitignore 已含 test-data/gnss-validation/）

- `test-data/gnss-validation/product288/{sp3,clk,bia}/`：GBM0MGXRAP_
  20262880000_01D_05M_ORB.SP3、30S_CLK.CLK、30S_ABS.BIA(259MB)、ATT.OBX
- `test-data/gnss-validation/table288/`：igs20_2437.atx
- `test-data/gnss-validation/ppp/algo288/`：ALGO00CAN_R_20262880000_01D_
  30S_MO.crx.gz（Earthdata 手动下载）→ tools/CRX2RNX.exe → MO.rnx(31MB)；
  BRDM00DLR_S_20262880000_01D_MN.rnx

## FIX-ALG-028：WUM 3F 67.81cm 回归三因素闭环 + MW 组合 OSB 单次施加裁决

### 背景与问题现象

FIX-ALG-027 落地后，WUM 2024-060 基线出现可复现真回归：3F-EWL+WL+NL
路径 RMS 从金标准 3.54cm 恶化至 67.81cm，且上会话三轮探针读数
（65.34/72.61/66.62cm）互相矛盾、无法定位变量。本条目以受控 2×2 矩阵
完成根因闭环与终态裁决。

### 根因链（三因素）

**因素 1（主导毒源）：getEwlFcb 逐历元取值 × EWL 弧段平均量补偿失配**。

EWL 弧段均值（xrewl/rewl）的相位项在 ewlmeas 中完全不做 OSB 改正，
其无偏化完全依赖固定时 `-fcbEwl` 单次常量补偿——补偿对象是**弧段平均
量**，因此补偿值必须用快照常量语义。027 将 getEwlFcb 切到 getOsbAt
逐历元取值后，WUM GPS L5Q 行为 900s 时变区间（96 条/星），补偿值全天
漂移：G06 L5Q 逐区间 -0.045m vs 末行快照 -0.153m（差 1.84 EWL 周），
EWL 取整基准逐历元漂移 → 3F 全路径中毒。临时诊断 OsbSeriesCompareTest
实测：2491 条相位序列记录中 1900 条（76%）与快照不一致（worst：
sat=6 code=25，n=96）。

修复：getEwlFcb 恢复快照语义（getOsb）。A/B 探针证实：仅回退
getOsbAt→getOsb 即 65.34cm→7.53cm。

**因素 2（解析缺陷，非 3F 直接毒源）：OsbReader sh 列偏移启发式误判**。

原单令牌规则（仅判 tokens[1] 是否为合法卫星号）把 WUM 行中恰好落在
PRN 域内的 SVN 误判为 GBM 版式：GPS SVN 43-64 共 16 颗（G13/G22/G21/
G07/G05/G20/G31/G17/G15/G16/G29/G12/G19/G02/G25/G30）+ BDS-2 SVN
C005-C019 共 11 颗（C06~C16），obs1 变成 PRN 令牌，整行静默跳过，
27 颗卫星 OSB 全部丢失 → 双频路径完备性受损（这 27 星在 3F 候选中已被
skip3Freq 先行剔除，故非 3F 直接毒源，但污染上会话探针读数的判读）。

修复：双令牌判别 v2——tokens[1]/tokens[2] 均可解析为卫星号 → WUM
双列版式（sh=1）；仅 tokens[1] 可解析 → GBM 版式（sh=0）；tokens[1]
不可解析（SVN 超出 PRN 域）→ sh=1。GBM 行 tokens[2] 为观测码
（satid2no=0），行为与原规则逐值一致。

**因素 3（耦合平衡）：MW 组合 WL 偏差改正恰好施加一次原则（2×2 矩阵）**。

WUM 自洽产品的 WL 偏差同现于 MW 组合的相位项与码项（mwmeas 相位 OSB
差分对 wlMean 的抬升恰为 +fcbWl；码 OSB 施加对 MW 的偏移 ≈ +fcbWl），
故 WL 改正必须**恰好施加一次**——两种合法单次施加路线、两种错配组合
全部实测：

| 配置 | 3F WL 固定时 | mwmeas 码 OSB | 2D RMS | 历元 |
|---|---|---|---|---|
| 027 期树（EWL 逐历元 + sh 破损） | 双重 −fcbWl | 施加 | 67.81cm | — |
| Run A | 单次 | 施加 | 7.53cm | 1430 |
| Run B | 双重 −fcbWl | 施加 | 3.54cm | 1245 |
| Run C | 双重 −fcbWl | 不施加 | 7.97cm | 1326 |
| **Run D（终态）** | **单次** | **不施加** | **3.00cm** | 1276 |
| 金标准（025 期树，参照） | 双重 −fcbWl | 施加 | 3.54cm | — |

机制说明：Run B 中固定时 −fcbWl 与 mwmeas 相位 OSB（getWlFcb 读同一
OSB 值）精确抵消，净效果 = 仅码 OSB 改正；Run D 净效果 = 仅相位 OSB
改正。两条路线均为合法单次施加（产品相位/码自洽性的两条体现），错配
组合 A（两次施加）与 C（零次施加）取整中毒 7.5~8cm。

### 修复内容

- `ephemeris/OsbReader.java`：sh 启发式升级双令牌判别 v2（因素 2）；
  相位分支同步写入 osbSeries（与快照同值同单位）。
- `ppp/PppAmbFix.java`：
  - getEwlFcb 恢复快照语义 getOsb（因素 1，弧段平均量补偿一致性）；
  - 3F WL OSB 路径固定时不补偿 ±fcbWl（单次，因素 3 终态），与 MAIN
    单次分支（L164-165）语义统一；
  - getOsbAt 恢复完整时变实现（series 命中 → BDS B1/B2B 等价码回退 →
    快照兜底）。
- `ppp/PppCore.java`：mwmeas OSB 分支移除码 OSB 施加（仅施相位 OSB，
  因素 3 终态）；码 OSB 保留用于 AR 候选完备性检查（PppAmbFix
  getCodeOsb）并经 nav.cbias 进入 EWL 组合。
- 上会话批准的三步方案中步骤 1（3F δ 门移植）已完成并保留
  （`enablePppArDeltaGate` 门控，3F 测试配置中保持 false，Run D 读数
  为无 δ 门纯配置，与金标准直接可比）。

### 验证（PppArValidationTest，ALGO 2024-060 + WUM BIA，终态 Run D 配置，7/7 通过）

| 模式 | 2D RMS | 历元 | 备注 |
|---|---|---|---|
| Float | 1.78cm | 1920 | 基线 |
| WL+NL | 1.78cm | 1920 | 与 Float 逐值一致（δ 门拦截 fix=0，FIX-ALG-026 记载状态） |
| FixHold | 1.78cm | 1920 | 同上 |
| Partial | 1.78cm | 1920 | 同上 |
| **3F-EWL+WL+NL** | **3.00cm** | 1276 | **优于金标准 3.54cm** |
| EST+ISB | 137.76cm | 1249 | 与"发散"基线量级一致 |

MAIN 双频路径与 Float 逐值一致（同 RMS/Mean/历元数）= 零连带回归。

### 终态裁决说明

选择 Run D 而非 Run B（保守恢复 027 前配对）的理由：

1. 实测最优 3.00cm（历元数 1276 > 1245）；
2. 与 MAIN 单次语义统一，顺带落地 ARCH-DEBT④「OSB 单点应用」的 WL 部分；
3. 不依赖「码 OSB 偏移恰等 fcbWl」的产品自洽巧合，对非自洽产品更稳健；
4. 如需严格复刻金标准配对（双重 −fcbWl + 码 OSB 施加），按
   PppAmbFix/PppCore 注释记载的矩阵回切即可（Run B = 3.54cm 备选）。

## FIX-ALG-027 勘误

- 原条目「行为保持：WUM 2024-060 常量数据集零影响」**证伪**：回归实发
  （3F 67.81cm）。
- 原根因假设「getWlFcb/getNlFcb 调 getOsbAt 对 WUM 日边界时间戳格式
  （2024:059:86400→2024:060:00000）处理错误」**证伪**：WUM 全天区间
  te−ts=86400，getOsbAt 按历元命中返回与快照同值；真实机制为本条目
  因素 1（getEwlFcb 逐历元 × GPS L5Q 900s 时变序列 × 弧段平均量补偿
  失配）。
- 原条目验证表（GBM-2026288 7/7 通过）仍然有效；其列偏移自适应描述
  由本条目双令牌规则 v2 取代。

## FIX-ALG-029：GPT3+VMF3 U 向恶化 +793% 三缺陷闭环（VMF3 映射函数公式 / ZHD 单位错配 / c 系数缺失）

### 背景与问题现象

`docs/PPP_Extra_Optimizations.md` §7.4 逐项对照长期记录 GPT3+VMF3
优化项 U 向严重恶化（旧表 HKWS +191.9%、HKSL +143.5%）。本会话以
HKWS 全天 2880 历元复测（代码状态已含 FIX-ALG-021~028），恶化幅度
进一步扩大：fallback U=7.332m（ΔU +792.8%）、真网格 U=7.365m
（ΔU +796.8%），基线 U=0.821m。用户归因问题二选一：缺 GPT3 网格
文件（先验 ZTD 异常），还是 VMF3 映射函数代码缺陷？

### 排查过程（假设一证伪 → 假设二坐实）

**假设一（缺网格文件）证伪**：

1. 下载 TU Wien 官方 5°×5° 网格 `gpt3_5.grd`（1,146,104 字节，2592
   格元中心）与官方参考实现 `gpt3_5.f90`（21,851 字节）。
2. 发现旧 `Gpt3GridReader` 按幻觉格式解析（37×73 节点、11 列、
   doy−28 相位偏移），与真实文件（36×72 格元中心、64 列、谐波相位
   直接取 doy）完全不匹配——读真文件会把经度当气压。依据 f90 全文
   重写读取器（含高程归约、极区最近邻、双线性插值，单测 4/4）。
3. 发现 `RtkConfig.gpt3GridFile` 为死字段（全库无消费点），且
   `PppProcessor` 三 nav 对象断链（批处理历元用 `batchRtcm.nav` /
   `parser.nav`，旧 `loadGpt3Grid()` 加载进 `rtcm.nav` 无效）。在
   `process(byte[])` 与 `processRinex` 双路径补齐自动加载。
4. A/B 实测：真网格 U=7.365m ≈ fallback U=7.332m——**缺数据文件
   不是毒源**（两条路径共用代码问题）。

**假设二（代码公式）坐实**：审计 `PppOptimizations` 对照官方
VMF3 公式（Landskron & Böhm 2018）与本项目 RTKLIB 基线
（`TroposphereModel.saastamoinen` / `tropModelPrec`），定位缺陷。

### 根因（三缺陷叠加）

**缺陷 1（主导）：`vmf3()` 映射函数仅实现分子，缺失连续分数分母。**

原实现：

```java
mfh = 1.0 + ah / (1.0 + bH / (1.0 + ch / sinel));
```

这不是任何已发表映射函数。正确 VMF3（与 VMF1/GMF 同构连续分数）：

```
m(e) = (1 + a/(1 + b/(1 + c))) / (sin(e) + a/(sin(e) + b/(sin(e) + c)))
```

原式在 el=15° 算出 ≈1.00001，正确值 ≈3.86318——映射函数完全失效，
静力学斜延迟每星少修正 ~6.6m（zhd·Δmf），且误差随高度角变化无法被
单一 ZTD 状态吸收 → U 向爆炸。与观测（ΔU +793%）量级吻合。

**缺陷 2：ZHD 单位错配（低估 100 倍）。**

`saastamoinenZhdZwd` 用 `zhd = 0.00002412*p`（该系数要求 p 单位
Pa），但 Gpt3GridReader 与 fallback 均输入 hPa（~995.94）→ zhd
算出 0.024m 而非 2.44m。这解释了真网格与 fallback 结果几乎一致的
现象（两路径同错）。同时该函数 zwd 公式与 e 无关（废公式）、分母
cos²φ ≠ 标准 cos(2φ)，均对齐 `TroposphereModel.saastamoinen`
基线（p 单位 hPa）重写。

**缺陷 3：c 系数硬编码 0 + bw 用错分段表。**

VMF3 官方：ch = 0.062 + 0.001·cos(t) + 0.005·cos(2t)，cw = 0.001
（t = 2π(doy−28)/365.25）；bw 纬度分段 ≤15°:0.00108、15°~75° 线性
至 0.00225、>75°:0.00225。原实现 ch=cw=0 且 bw 在 75° 达 0.00536
（误用 GMF 节点值）。ch/bw 量级影响小（cm 级），一并对齐官方值。

**排除项**：`delay = mfh*zhd + mfw*(x[0]-zhd)` 与基线
`tropModelPrec` 完全一致（x[0] = ZTD 总量语义，由
`udtrop_ppp` 以 `sbstropcorr` 天顶值初始化，H 行 = dtdx[0] = mfw），
非缺陷；`dtdx[1]/[2]` 梯度形式与基线一致，非缺陷。

### 修复内容

- `ppp/PppOptimizations.java`：
  - `vmf3()` 重写为官方 VMF3 连续分数公式（分子/分母完整），ch/cw
    按 VMF3 论文谐波取值，高度修正仅施加于静力学分量（与 NMF 基线
    `tropmapf` 的 wet 不修正约定一致）；
  - `vmf3Bw()` 改为官方分段（0.00108 → 0.00225 线性，>75° 常量），
    移除未使用的 doy 参数；
  - `saastamoinenZhdZwd(p, Tkelvin, lat, hell, e)` 重写：zhd = 
    0.0022768·p/(1−0.00266·cos(2φ)−0.00028·h/1E3)（p: hPa）、
    zwd = 0.002277·(1255/T+0.05)·e（T: K, e: hPa），与
    `TroposphereModel.saastamoinen` 基线逐系数一致；调用处传入
    网格水汽压 e；
  - `gpt3Fallback` 温度基准改开尔文（原摄氏导致 zwd 对数失真）。
- 本会话前序（同条目归档）：
  - `ephemeris/Gpt3GridReader.java`：按官方 gpt3_5.f90 全文重写
    （36×72 格元中心、64 列、谐波 doy 相位、高程归约、极区最近邻、
    双线性插值，输出 p(hPa)/T(K)/e(hPa)/ah/aw），单测 4/4；
  - `ppp/PppProcessor.java`：`process(byte[])`（L517-522，加载进
    `batchRtcm.nav`）与 `processRinex`（L594-599，加载进
    `parser.nav`）双路径自动加载 `RtkConfig.gpt3GridFile`，修复
    死字段断链。

### 验证（PppIndividualOptTest，HKWS 全天 2880 历元，skip=30）

| 配置 | N(m) | E(m) | U(m) | 3D(m) | ΔU |
|---|---|---|---|---|---|
| 基准 PPP(IFLC+NMF) | 0.325 | 0.522 | 0.821 | 1.026 | — |
| + GPT3+VMF3（fallback） | 0.193 | 0.957 | 1.276 | 1.607 | +55.3%（原 +792.8%）|
| **+ GPT3+VMF3+GRID（真网格）** | 0.324 | 0.522 | **0.806** | **1.014** | **−1.9%** |
| + IERS2010 / +IERS2010+S1/S2 / +ISB/IFCB/IFB / EST | — | — | — | — | 与修复前逐值一致 |

- 真网格路径 U=0.806m **优于基线** 0.821m（3D 1.014 优于 1.026），
  GPT3+VMF3 优化项由 ❌ 翻转为 ✅；
- 非 GPT3 配置逐值不变 = 零连带回归；
- fallback 残余 +55.3% 属兜底先验质量限制（简化球谐 ah/aw 系数与
  水汽压先验），生产使用建议加载官方 gpt3_5.grd（测试默认探测
  `test-data/product/gpt3/gpt3_5.grd`，可用环境变量
  `PPP_GPT3_GRID` 覆盖）。

### 遗留记录（不阻塞定版）

1. ISB/IFCB/IFB 分支 U +521% 为独立问题（本次修复前已存在，与本
   条目无耦合），归档后续排查；
2. `gpt3Fallback` 水汽压先验 `e0=0.01·exp(−0.000639h)` 偏小约 3 个
   量级（仅影响 zwd 调试日志，不参与 delay 计算），未修改；
3. VMF3 OP 文件（`vmf3OpFile`，逐时次 ah/aw/bH/bW）路径未在本次
   实测（无公开样例数据），代码路径保持原有插值逻辑不变。