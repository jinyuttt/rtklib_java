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
| FIX-ALG-029 | 2026-10-23 | ppp/PppOptimizations.java, ephemeris/Gpt3GridReader.java, ppp/PppProcessor.java | GPT3+VMF3 U 向 +793% 三缺陷闭环：vmf3 缺连续分数分母（主导）+ ZHD 单位 hPa/Pa 错配 + ch/cw/bw 对齐官方；Gpt3GridReader 按 f90 全文重写并接线双路径，真网格 HKWS U 7.365→0.806m（基线 0.821）、HKSL 1.662m（基线 1.684） |
| FIX-ALG-030 | 2026-10-23 | ppp/PppProcessor.java, rtcm/Rtcm.java, ppp/PppRtkCore.java | SSR 接入适配：Rtcm.setReferenceTime 暴露给外部；PppProcessor 新增 feedSsr() 独立 SSR 流入口；PppRtkCore 新增 ssrMaxAge 拦截；新增 SsrAccessCheckTest 实时流验证 |
| FIX-ALG-031 | 2026-10-24 | ppp/PppRtkCore.java | 自适应场景切回阈值标定：FLOAT→SINGLE 条件从 nv<4 改为 nv<3；SINGLE→FLOAT 加稳定性检查（连续 5 历元 nv≥6）；加 post=0 预热守卫 |
| FIX-ALG-032 | 2026-10-26 | ppp/PppProcessor.java | 实时流周基准锚点：loadNav 内 setReferenceTime 锚点从 BRDM TOE→CPU 当前时间（rtksvr.c:656 timeget 对齐），修复 adjweek ±3.5d 窗口不够导致 obsWeek 锁死错误周 |
| FIX-ALG-033 | 2026-10-26 | rtcm/Rtcm.java | RTCM 1068（GLO SSR 高精度钟差 SSR6）switch 路由补全：dispatch 里 case 1068 缺失，方法体 decodeType1068() 已存在 |
| FIX-ALG-034 | 2026-10-26 | rtcm/Rtcm.java, ppp/PppProcessor.java | RTCM 1043 按 SBAS 星历重新实现（cssrlib 布局）：首次按 1042 同构假设实现已证伪回退；二次查证 cssrlib decode_sbs_eph 后改为 SBAS 解码，6/6 帧验证通过 |
| FIX-ALG-035 | 2026-10-26 | ppprtk/PppRtkCore.java, rtklib-test/SsrAccessCheckTest.java | IFLC 诊断日志降级为 trace；实时流录制能力（RECORD_OBS/RECORD_SSR）与回放闭环验证；PPP-RTK AR 触发条件实测（4 星固定、NL 3<4 门限，未达 FIX） |
| FIX-ALG-036 | 2026-10-26 | rtcm/Rtcm.java | SSR 解码簇修复：相位偏差系统映射（1265=GPS/1267=GAL/1270=BDS）、轨道帧 BDS iodcrc 位宽（ni=10+nj=8）、码偏差 ssr_sig 表 mode→CODE 映射、比例尺 0.01m、SSR_SIG_BDS mode16/17 原版=0→CODE_L1C（JAVA-ONLY 增强 B1I 支持） |
| FIX-ALG-039 | 2026-10-27 | data/SolData.java、adjust/CovAssembler.java、adjust/GnssBaselineAdjust.java、adjust/MultiBaselineAdjust.java、adjust/DisplacementSeries.java | adjust 平差链路闭环：完整 ECEF 协方差暴露（保留符号）+ R 改块对角独立性模型（删除 ρ=1/k 的融合结果误用）+ dXyz 数据源修正 + 配置项接线 + 时间序列/网平差/白度检验（JAVA-ONLY） |
| FIX-ALG-040 | 2026-10-27 | adjust/NetworkAdjustment.java、adjust/MultiBaselineAdjust.java、adjust/model/MultiBaselineConfig.java、rtklib-adjust/pom.xml、rtklib-core/pom.xml | 自由网平差 O(m²) 内存爆炸 + 残差计算错误双修复；网平差观测时间窗（默认 1h）；manual 测试命令行覆盖修复（JAVA-ONLY） |
| FIX-ALG-041 | 2026-10-27 | adjust/GnssBaselineAdjust.java、adjust/MultiBaselineAdjust.java、adjust/ResidualWhiteness.java | 白度检验失效修复：adjustSingle 残差向量未随结果返回（恒 null）+ SUSPECT 剔除后 entries 与 v 长度错配 + df=0 历元改喂交叉检核残差（JAVA-ONLY） |
| FIX-ALG-046 | 2026-11-05 | networkvrs/netsol/DdObservationBuilder.java、netsol/DdObservation.java、netsol/BaselineWindowSolver.java、ephemeris/EphModel.java | 网解窄巷固定闭环：双差两端共用同一卫星位置（按站 A 发射时刻）→ 逐星常量偏差被 IF 模糊度吸收、浮点 N1 偏 0.3 周而协方差仍按 0.05 周标称 → ratio≈1；改为两站各按本站坐标迭代发射时刻求 satpos，并新增两站残余 ZTD 及变化率状态（σ=0.3 m / 0.1 m·h⁻¹ 弱先验）（JAVA-ONLY） |
| FIX-ALG-047 | 2026-11-05 | ephemeris/EphModel.java、rtklib-core/src/test/java/org/rtklib/java/RinexSppTest.java | 星历最大时效按系统分档（GPS/GAL/QZS/SBS/GLO 2 h、BDS 6 h、IRN 8 h，对齐 seleph/selgeph），移除 7 天统一上限与 `rtklib.eph.wideage` 开关；SBAS 改「t0 最近且 ≤ MAXDTOE_SBS」；RinexSppTest 改为按可用星数最多历元验证（JAVA-ONLY） |
| FIX-ALG-048 | 2026-11-05 | joint/BaseSkeletonNetwork.java | 闸3 骨架网 FIX 恒为 0：参考长度标定用 `stat < SOLQ_FLOAT` 判「非 FIX」（SOLQ_FIX=1 < SOLQ_FLOAT=2）导致 FIX 全被丢弃、且曾用 FLOAT 解采样污染参考长度；改为参考长度只接受 SOLQ_FIX，FIX/FLOAT 均可进监测；修复后 fix=215/235、587.302 m 与坐标几何值毫米级一致（JAVA-ONLY） |
| FIX-ALG-049 | 2026-11-06 | networkvrs/netsol/BaselineWindowSolver.java、networkvrs/config/NetworkConfig.java | 窄巷固定率偏低根因是周跳切弧对「正常缺测」过敏：1.5×采样间隔阈值把 30 s 采样下缺 1 历元的正常间隔（2×30 s）判为失锁，63 次误切弧把长弧切成短弧，模糊度 σ 膨胀到 2~6 周、LAMBDA ratio 塌到噪声底；改为间隙阈值可配置 `maxArcGapFactor=3.0`，周跳仍由 0.05 m 阶跃判据负责；固定率 69.4%→80.6%、基线重复性 0.180→0.160 m、平均 ratio 5.38→6.04（40 历元窗口；默认 20 历元窗口 34.7%→37.5%，瓶颈不在切弧）。2026-11-06 复核更正：1.5× 失效的根因是「倍数 × 有偏尺度（前 20 间隔均值，被缺测抬高）」，非倍数本身，3.0 的语义是「允许连续缺 2 个历元」而非调出来的最优倍数；尺度已改为全窗下中位数（判据语义不变、零 API 变更，条目内「尺度更正」）；`maxArcGapSeconds` 绝对秒数方案评估后不做、正式关闭（条目内「评估后不做」）（JAVA-ONLY，core 零改动） |
| FIX-ALG-050 | 2026-11-10 | rtkpos/RtkCore.java | ddres 对流层项按 C `rtkpos.c` 结构重写：卫星循环前逐星各调一次 `prectrop` 缓存 `tropu/tropr/dtdxu/dtdxr`，循环内补上 `v[nv]-=(tropu[ref]-tropu[j])-(tropr[ref]-tropr[j])` 并按 `IT(0)/IT(1)` 分列赋值 H（原实现缺 v 修正、两站偏导写进同一索引、每对星重复调 2 次） |
| FIX-ALG-051 | 2026-11-10 | kalman/KalmanFilter.java、rtkpos/RtkCore.java | **短基线固定率 0.07%→73.82%（C 73.78%）**：`KalmanFilter.update` 的状态压缩比 C `filter()` 多一条「H 列非零」过滤（FIX-ALG-042 P4 为 joint 双基线引入），使 `holdamb` 伪观测只更新模糊度对角块、位置/对流层交叉协方差被丢弃 → maxV 单调发散、ep28 跨整周 2.1154 周 → LAMBDA 失败、nb 30→28 卫星排除死亡螺旋。改为可选参数 `skipZeroHCols`（默认 true 保持 joint 行为逐位不变），`relpos`/`holdamb` 两处显式 false 对齐 C；holdamb 触发 21→181 次/前 200 历元，同为 FIX 2122 个、平均差 −0.0169/0.0115/−0.0299 m、水平差 >0.1 m 历元数 0 |
| FIX-ALG-052 | 2026-11-10 | time/TimeSystem.java | `MDAY` 四年周期（1461 天）表闰年位置错位：29 天放在第 2 个 12 项块（1971）而非第 3 块（1972），使闰年（2020/2024 等）3 月 1 日以后 `time2epoch()` 反算日期整体早一天；改为与 C `mday` 表逐项一致（`epoch2time()` 闰年判据本就正确） |
| FIX-ALG-053 | 2026-11-10 | ppp/PppCore.java | `pppos` 迭代起点对齐 C：`matcpy(xp/Pp ← rtk->x/P)` 原在迭代循环体内，每次迭代都从未提交的 `rtk` 状态重新起步，使「迭代精化」退化为「同一初值重复求解」；移到 `for (iter...)` 之前（C `ppp.c L1219-1238`） |
| FIX-ALG-057 | 2026-11-11 | ppp/PppCore.java、ppp/PppCoreEx.java、pppar/PppArEngine.java | PPP-AR 固定解永久锁存（FIX-054 同源）：`pppos`/`ppos` 逐历元入口新增 `clearFixLatch` 清除上一历元 AR 写入的 `SOLQ_FIX`（只清 FIX，保留 `outsingle=1` 时 SPP 的 SINGLE 语义），`updateStat` 的 FIX 标签同样受 `MIN_NSAT_SOL=4` 降级，`PppArEngine` 入口加 ns 门控 —— 修复后 FIX 必须逐历元由本历元 AR 重新获得 |
| FIX-ALG-058 | 2026-11-11 | ppp/PppAmbFix.java | AR 系统白名单 `SYS_GPS\|SYS_GAL\|SYS_CMP` 写死改为动态继承 `opt.navsys`（三处：WlNl / PartialAR / EwlWlNl），navsys 只配 GLO/QZS/IRN 时不再静默全跳过；无 OSB/FCB 覆盖的系统由既有 WL/NL 质量门自然剔除 |
| FIX-ALG-059 | 2026-11-11 | ppprtk/PppRtkCore.java | **PPP-RTK 好站全天 FIX 2270/3815（59.5%），修复前恒 <1000**：相位行 pre-fit 门限错用 `PrcOpt.maxinno[0]=5.0`（该值按 RTK `ddres` 索引约定为码行设计，PPP 侧 `code==0` 却是相位行）→ 一次 ~9m 接收机钟跳当历元把 13 颗星的相位行全部剔除（nv 28→15），此后长期只剩码行更新、可见星 4~6 无法恢复。相位行改按 C `ppp.c maxinno[0]=30.0` 固定 30m，码行仍取 `opt.maxinno[1]`，删除原 `epoch<=60` 临时放宽 |
| FIX-ALG-060 | 2026-11-11 | ppprtk/PppRtkAmbFix.java | `ppprtkAmbFixUc` 系统白名单 `SYS_GPS\|SYS_GAL` 写死改为动态继承 `opt.navsys`（与 FIX-ALG-058 同类缺陷）；**同批实测确认 PPP-RTK 非 IFLC（UC/EST，nf=3）链路整体退化**：0015 hour00 `fix=0 / ns=1 / vsat0=0`，故接口三 AI 在 PPP-RTK 链上既无合法注入点、也无可用非 IFLC 载体，A/B 对比不可执行（详见该条目「接口三 A/B 可行性核查」） |

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

---

## FIX-ALG-030 / SSR 接入适配（2026-10-23）

**性质**：接入适配，非算法特性改动。定版阶段允许的最小改动范畴。
**背景**：用户需求"先打通 SSR 流程，结果精度不重要"。用匿名可达的 CAS
`SSRC00CAS0`（仅轨道+钟差，无相位偏差）+ 本地多系统广播星历 + 观测录制，
离线回放打通 RTCM SSR 解码 → nav.ssr[] → SsrCorrector → ppprtkos 全链路。
**实测**：2026-296 WUH20 观测 + 295 BRDM 多系统日产品 + 296 CAS SSR；
ppprtkos ENTRY 24/24 历元有输出，epoch 5/14/24 `sol.stat=SOLQ_PPP`
即 PPP-RTK 解算器**实际产出解**。`nv=0` 主因（r<=0=38）为 DOY 295
星历 TOE 距观测 TOE 25h45m（satposs 选不到足够近的 TOE），属星历新鲜度
数据问题，非代码缺陷；maxinno=-3.8~-5.5km 为观测与 SSR 时刻差 6.3h
（SSR 轨道改正套错历元），属时间对齐精度问题。

### 改动 1：RTCM SSR 头部解码移植缺失

**文件**：`rtklib-core/.../rtcm/Rtcm.java`
**问题**：原 Java 版 `decodeSsrHead` 直接从 24+12=36 位偏移读 head
字段，完全跳过了 C 版 `decode_ssr_epoch` 的 20 位 GLO-17/TOP-20 位历元
时间读取，导致 `Rtcm.this.time` 停留在 week=0、SSR 头部的 UDI/sync/IOD
也整体错位。实测 SSR t0.time 全为 0、nav.ssr 1284 槽 t0 零填。
**修复**：新增 `decodeSsrEpoch(int sys)` 方法（对齐 C `decode_ssr_epoch`），
读取 GLO 17 位日内秒或其他系统 20 位周内秒并归算到 `this.time`；
`decodeSsrHead` 重写为 8 字段顺序（UDI/sync/refd/IOD/provider/solution/
nsat）；7 处 SSR 电文调用点统一改为先 `decodeSsrEpoch` 再 `decodeSsrHead`。
**影响范围**：所有 RTCM SSR 电文（1057/1058/1063/1064/1240/1241/
1258/1259/1264~1270）。不影响非 SSR 电文路径。

### 改动 2：PppProcessor.loadNav + setReferenceTime

**文件**：`rtklib-core/.../ppp/PppProcessor.java`
**问题**：纯 MSM 录制流（无 1019/1020/1041 等星历电文）里 `Rtcm.this.time`
停留在 week=0，`adjweek(tow)` 把观测历元归算成 week=0，satposs 匹配
week=2441 的 BRDC 星历失败，全体卫星 rs=0、el=-89.8°。实时流里这一步
由星历电文自动完成，离线回放必须手工补。
**修复**：新增 `public Nav getNav()` 供 SSR 时间对齐脚本访问 nav.ssr[]；
新增 `public boolean loadNav(String navFilePath)` 合并外部 RINEX V3
多系统导航到 `rtcm.nav`，置 `ephReady=true`；loadNav 末尾自动把外部星历
首个有效 TOE 调 `rtcm.setReferenceTime(refTime)` 写回，让后续观测
adjweek 用正确的 week 基准。Spp 初始化加 try/catch 保护（SPP 崩不
影响后续 PPP）。
**影响范围**：仅离线回放路径；实时流里 `ephReady` 由 onEph 置位，
`setReferenceTime` 不生效。

### 改动 3：SSR 接入诊断开关 + 时间对齐脚本

**文件**：`rtklib-core/.../ppprtk/PppRtkCore.java`
**问题**：离线回放 SSR 需与观测做时间对齐（SSR 录制时刻 ≠ 观测时刻），
原代码没有逐星剔除原因统计，nv=0 的根因不可观测。
**修复**：新增 `PPP_DEBUG_REJ=1` 环境变量开关，前 3 历元在 ppprtkRes
内统计 r<=0 / el<elmin / excl / trop / iono / bias0 / maxinno 各分支
的剔除数量并打印；同时 SsrAccessCheckTest（本地 gitignore 工具）新增
`alignSsrToObs(Nav, String)` 时间对齐逻辑，自动读取 nav.ssr[] 全部
t0 并相对观测首历元整体平移。
**影响范围**：诊断代码默认关闭，不改变任何数值路径。

### 顺带发现（归档，非本次修复）

1. RinexParser.readNavEphV3 对 GLO prn>24（R25~R36）和 Galileo 星历
   解析有缺口（BRDM 文件 GAL=0、R28 invalid），属既有缺陷，归档后续。
2. CDDIS daily 分支仍兼容 HTTP Basic Auth；hourly 分支已升级到 Earthdata
   OAuth Code Flow（302→urs→redirect→proxyauth），当前 Basic Auth 无法
   下小时产品。定版阶段不新增 OAuth 支持，先用 DOY 295 日产品完成链路
   验证，等实时流权限开通后再处理。

---

## FIX-ALG-031 / 自适应场景切回阈值标定（2026-10-24）

**性质**：默认参数标定缺陷修复 + 阈值可配置化。不改判定逻辑、不改解算算法。

**问题**：`SNR_CANYON_TO_OPEN` 原值 38.0 dB-Hz，而引擎判定使用的是
`FeatureMetrics` 的 EMA 平滑值（α=0.1），不是原始逐历元均值。真实数据实测：

| 数据 | EMA 平滑 avgSnr 峰值 | 原阈值 38 命中历元 |
|------|---------------------|--------------------|
| opensky_rover_20250408（开阔） | **37.90** | **0** |
| downtown_rover_20250408（峡谷） | 32.39 | 0 |

开阔场景的平滑峰值低于 38，`slidingSnr > 38` 在数学上永不成立，导致引擎
激活后永久停留在初始 URBAN_CANYON，场景切换能力实际失效（opensky 全程
0 次切换，仅静态位置跳变回滚路径可见事件）。

**修复**：

1. `RtkConfig` 新增 4 个阈值字段（`adaptiveNsatOpenToCanyon=8`、
   `adaptiveSnrOpenToCanyon=30.0`、`adaptiveNsatCanyonToOpen=14`、
   `adaptiveSnrCanyonToOpen=34.0`），拷贝构造同步；
   `AdaptiveController.activate()` 解析配置值，字段 <=0 时回退内置兜底常量。
2. `SNR_CANYON_TO_OPEN` 兜底常量 38.0 → 34.0。

**标定依据**（canyon→open 的 snr 阈值扫描，nSat 条件同时满足）：

| 阈值 | opensky 命中 | downtown 命中 |
|------|-------------|---------------|
| 38 | 0 | 0 |
| 36 | 48 | 0 |
| 35 | 55 | 0 |
| **34** | **233** | **0** |
| 33 | 747 | 0 |
| 32 | 1034 | **14（开始误报）** |

34 为最优分界：开阔场景可稳定触发，峡谷场景零误报。

**实测（opensky SPP 端到端）**：切换链 epoch=10 CANYON→OPEN
（nSat 26/snr 37.7）、epoch=290 OPEN→CANYON（snr 20.7）、
epoch=880 CANYON→OPEN（snr 34.1），1603 历元解算正常，默认关闭路径
两次运行仍逐值一致（零干预不变）。

**影响范围**：仅自适应场景判定阈值；关闭 `enableAdaptiveScene` 时无任何影响。

---

## FIX-ALG-032 / 实时流周基准锚点：BRDM TOE → CPU 当前时间（2026-10-26）

**性质**：调用方适配修复。不改 Rtcm 核心算法，仅修正 `PppProcessor.loadNav()` 里 `setReferenceTime` 的锚点源选择。

**C 版依据**：`rtksvr.c:656` `timeget()` → 实时流场景直接用 CPU time 作为周基准，与观测同周，adjweek ±3.5d 窗口足够覆盖。

**问题**：SSR 实时流接入时 `loadNav()` 内部用预载 BRDM 文件的第一颗星 TOE 做锚点：

```java
// 原代码（已删除）
refTime = dst.eph[i].toe;  // BRDM TOE, week=2441（2026-10-22）
rtcm.setReferenceTime(refTime);  // 锁死 week=2441, timeInitialized=true
```

BRDM TOE 过期 4 天，而实时观测属于 week=2442（2026-10-26）。`adjweek(tow)` ±3.5d 窗口（±302400s）不足以覆盖 4 天差（345600s），观测时间戳被算成 week=2441，导致后续 `satposs` 因时间不匹配返回 `rs=0`、全部卫星被 el<elmin 剔除、`nv=0`、PPP 解状态停在 SINGLE。

此外，WUH20 观测流（RTCM 3.3/3.4）在不同时间窗口下发的星历消息类型不稳定：Day 295 观测到 1042（BDS-2 D1/D2）/1045（GAL F/NAV）/1046（GAL I/NAV），但 Day 299 只发 **1043（BDS-3 B-CNAV1）**、没有 1042。RTKLIB 2.5.0 C 版 `rtcm3.c:2584-2610` 的 switch 里**同样没有 case 1043**（1043 是 RTCM 3.4 新增的北斗三号 B-CNAV1 星历消息），Java Rtcm 也没有——流中所有 BDS 星历帧被静默丢弃。锚点一旦锁死到错误周（week=2441），即使后续 1045/1046 帧到达，也因周差超过 adjweek ±3.5d 窗口被错误纠偏。

**RTCM 10403.3/10403.4 星历消息对应表**（纠正历史误判）：

| MsgType | 系统 | 电文说明 | RTKLIB 2.5.0 | Java Rtcm |
|---------|------|---------|-------------|-----------|
| 1019 | GPS | LNAV | ✅ | ✅ |
| 1020 | GLO | FDMA | ✅ | ✅ |
| 1041 | NavIC | IRNSS | ✅ | — |
| **1042** | **BDS** | **D1/D2（BDS-2，老信号）** | ✅ decode_type1042 | ✅ decodeType1042 |
| **1043** | **BDS** | **B-CNAV1（BDS-3，新信号 B1C/B2a）** | ❌ 未实现 | ❌ 未实现 |
| 1044 | QZSS | QZSS 广播星历 | ✅ | ✅ |
| 1045 | GAL | F/NAV（E1） | ✅ | ✅ |
| 1046 | GAL | I/NAV（E5b） | ✅ | ✅ |

> WUH20 NTRIP 源只下发 BDS-3 B-CNAV1（1043）、不下发老 BDS-2 D1/D2（1042），这就是流中 BDS 星历全部被丢弃的根因。1042 和 1043 虽然都是北斗星历，但 B-CNAV1 的参数结构（IOD/AODE 字段、时钟修正系数编号等）和 D1/D2 不同，不能共用一套解析函数。

**修复**：`PppProcessor.loadNav()` L282-303，锚点源从 BRDM TOE 改为 CPU 当前时间：

```java
// 修复后
GTime refTime = TimeSystem.timeget();  // CPU 当前 Unix 秒
rtcm.setReferenceTime(refTime);         // 锚定到当前周
```

CPU time 与实时观测同周（只差几秒），adjweek ±3.5d 窗口绝对够用。后续若流中到达可解码星历消息（1019/1045/1046 等），Rtcm 内部会用星历自身 week 纠偏——这里的 setReferenceTime 只是兜底初始锚定，不影响正常的星历纠正路径。

**验证**：编译通过（`mvn compile -pl rtklib-core`）。Day 299 BRDC 替换后，WUH20+SSRC01CAS0 实时流 60s 测试 **62/62 历元全部产出 PPP 解**，week=2442（正确），station pos ECEF 捕获成功（RTCM 1006）。解位置 ECEF 差 station ~(3, 7, 4)m，实时 PPP 初始解合理收敛。

**影响范围**：仅影响 `rtcm.time == 0` 且已调用 `loadNav()` 的实时流接入场景。离线回放场景（Rtcm.time 已由文件 timetag 初始化）不受影响。

## FIX-ALG-033 / RTCM 1068 注册缺失：GLO SSR 高精度钟差（SSR6）switch 路由补全（2026-10-26）

**性质**：缺陷修复——方法体已存在但未在 switch 中注册，属于典型的路由遗漏。

**C 版依据**：`rtcm3.c:2622` `case 1068: ret=decode_ssr6(rtcm,SYS_GLO,0); break;` — C 版明确有 1068 的 SSR6 路由。

**问题**：`Rtcm.dispatch(int type)` 的 switch 中，GLO SSR 消息 case 序列 1063-1067（SSR1-SSR5）之后直接跳到 1071（MSM），**1068 缺失**。但 Java 版 `decodeType1068()` 方法体已存在（L1196），正确调用 `decodeSsrHrClock(Constants.SYS_GLO)`。收到 1068 帧时被 `dispatch` 漏过，静默丢弃。

**修复**：一行补全 switch 路由：

```java
// 修复前
case 1067: return decodeType1067();
case 1071: return decodeType1071();

// 修复后
case 1067: return decodeType1067();
case 1068: return decodeType1068();  // ← 新增
case 1071: return decodeType1071();
```

**验证**：编译通过（`mvn compile -pl rtklib-core`）。

**影响范围**：仅影响接入发 GLO SSR6（高精度钟差）帧的 SSR 源时的 GLO 钟差修正。不发 1068 的 SSR 源无行为变化。

## FIX-ALG-034 / RTCM 1043 真实身份：SBAS 星历（cssrlib 布局）实现（2026-10-26）

**结论先行**：RTCM 1043 不是 BDS-3 B-CNAV1 星历（这是此前误判），而是 **SBAS 卫星星历**（WAAS/EGNOS/GAGAN/SDCM 等）。cssrlib 项目已按 RTCM 3.x 标准实现 `decode_sbs_eph()`，本次按同布局在 Java 实现 `decodeType1043()`，6/6 帧实测验证通过。

### 真相链条

1. **第一次实现（已废弃）**：按 1042（BDS D1/D2）字段顺序假设解码，与 BRDC RINEX 比对差 265 km、week 值域不合法，证伪回退
2. **用户提示查 RTKLIB 2.5.1**：本地 `RTKLIB-2.5.1/src` 只剩 CMakeLists.txt（骨架未填充），`RTKLIB_EX_2.5.1` 同样无 rtcm3.c
3. **本地已有正确答案**：`d:\code\cssrlib\src\cssrlib\rtcm.py:4029` `elif self.msgtype == 1043: self.subtype = sRTCM.SBS_EPH; i, seph = self.decode_sbs_eph(msg, i)` — **SBAS 星历**
4. **数值自洽验证**：用 SBAS 布局解码录制的 6 帧 1043，结果全部合理：
   - PRN = 121/128/131/136（SBAS 标准号段 120-158）
   - xyz 量级 ≈ 42,000 km（GEO 轨道典型）
   - toc 递增（16000→16048→16064），af0 ≈ 5.6e-9 s
   - 218 bit 单帧完整星历，与 cssrlib 字段表严格一致

### SBAS 字段布局（218 bit）

```
u6  svid  (SBAS PRN = svid + 119, RTCM 约定)
u8  iodn  (Issue of Data Navigation)
u13 toc   ×16 s   (time of clock)
u4  ura
s30 x     ×0.08 m        (ECEF)
s30 y     ×0.08 m
s25 z     ×0.4  m
s17 vx    ×6.25e-4 m/s
s17 vy    ×6.25e-4 m/s
s18 vz    ×4e-3   m/s
s10 ax    ×1.25e-5 m/s²
s10 ay    ×1.25e-5 m/s²
s10 az    ×6.25e-5 m/s²
s12 af0   ×2^-31  s      (clock bias)
s8  af1   ×2^-40  s/s    (clock drift)
```

### 实现要点

- [Rtcm.java](file:///d:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/rtcm/Rtcm.java#L900) 新增 `decodeType1043()`，dispatch 注册 `case 1043`
- 写入 `nav.seph[sat-1]`（Java 已有 `Seph` 类对应 C 版 `seph_t`）
- `svid ≤ 0 || > 39` 检查（RTCM 标准 SBAS PRN 范围 120-158）
- t0 按 cssrlib 对齐：取当日 GPST 的 86400 秒边界 + toc
- 1043 不携带健康标志，`seph.svh = 0`
- [PppProcessor.java](file:///d:/code/rtklib_java/rtklib-core/src/main/java/org/rtklib/java/ppp/PppProcessor.java#L1377) `isEphemerisType()` 加 1043

### 验证

- 编译通过
- 回放 `obs_wuh20_20261026.rtcm3` + `ssr_ssrc01cas0_20261026.rtcm3`（120 s）：
  - `ephTypes={1043=6, 1045=2}`（1043 全部被识别为星历帧）
  - `total=119 success=119 fail=0`（解算结果不变，SBAS 不影响 PPP）
  - VERDICT=OK

### 影响

- WUH20 流中 1043 帧全部来自 SBAS GEO 卫星（EGNOS/WAAS 等），与 BDS 无关——这解释了为什么之前用 1042 同构布局解出的 "BDS week=483" 等字段不合理
- PPP/RTK 主流程不使用 SBAS 星历（SBAS 参与解算需特殊配置），但**不再丢失**该消息类型，nav.seph 后续可用于 SBAS 辅助定位或完整性监测
- 对 BDS-3 用户的实际意义：**BDS-3 星历仍需靠 `loadNav()` 预载 BRDM/BRDC 文件覆盖**（FIX-ALG-032），1043 不承担 BDS 星历角色

---

## FIX-ALG-034 历史废弃记录（第一次错误实现）

**结论先行**：曾按"1042 同构布局"假设实现 `decodeType1043()`，并在实时流上得到 `ephTypes={1043=5}`；但**与 BRDC RINEX 同卫星时钟参数做数值比对后不成立**，故已整体回退（删除方法、撤销 switch 注册、撤销 `isEphemerisType` 的 1043 分支），恢复为"收到 1043 直接丢弃"。回退后回放回归 `total=119 success=119 fail=0`、`ephTypes={1045=2}`，行为与改动前一致。

### 当初的假设（不成立）

按 1042（BDS D1/D2）字段顺序解读 1043 payload：`6 PRN | 13 week | 4 URA | 14 IDOT | 5 IODE | 17 TOC | 11 f2 | 22 f1 | 24 f0 | 5 IODC | ...`。

### 证伪证据

从录制文件 `test-data/gnss-validation/rtcm/obs_wuh20_20261026.rtcm3` 抽出 6 帧 1043（payload 244 bit），按假设解码：

| PRN | 解出 week | 解出 f0 | BRDC 同星 a0 | 差值 |
|-----|-----------|---------|--------------|------|
| C10 | 483 | 7.126e-06 | -8.781e-04 | 8.85e-04 s ≈ 265 km |
| C13 | 451 | 1.111e-05 | -9.347e-04 | 9.46e-04 s |
| C03 | 483 | 0 | -1.651e-07 | 1.65e-07 s |

- `week=483` 既不是 GPS 周（2442）也不是 BDT 周（1086）
- `toc` 解出 292760/171760 等非整小时值，不符合星历整点发布惯例
- 决定性检验：以 BRDC RINEX 的 a0（2^-33 定点，24 bit 补码）为模板在 payload 全 bit 串中搜索，**零命中**；a1 同理
- 反证：若该布局成立并写入 `nav.eph`，时钟偏差被改写近 1 ms（≈300 km），比丢弃报文危害大得多

### 回退内容

1. `rtcm/Rtcm.java`：删除 `decodeType1043()`，`dispatch` 不再注册 `case 1043`
2. `ppp/PppProcessor.java`：`isEphemerisType()` 恢复为不含 1043

### 影响与现状

WUH20 流只发 1043、不发 1042 —— BDS 星历仍完全依赖 `loadNav()` 预载的 BRDM/BRDC 文件，这也是当前架构的既定前提（FIX-ALG-032）。**1043 未实现不构成解算缺陷**。

### 后续若要真正实现，需满足其一

1. 获取 RTCM 10403.4 正式标准文本（付费，含 1043 字段表与缩放因子）
2. 找到已实现 1043 的开源参考（RTKLIB 主线和 pyrtcm 均无：RTKLIB 2.5.0 早于 RTCM 3.4；pyrtcm 覆盖至 RTCM 3.3）
3. 用"同卫星多历元 + BRDC 真值"做定点编码搜索（本轮已证明单纯按 1042 类比不可行）

---

## FIX-ALG-034 原始记录（历史，已废弃）

**性质**：标准能力补齐——C 版 `rtcm3.c:2584-2610` 同样没有 case 1043（RTKLIB 2.5.0 发布于 2021，早于 RTCM 3.4）。本次从零实现 Java 版 `decodeType1043()`。

**背景**：WUH20 NTRIP 源只下发 BDS-3 B-CNAV1 星历（RTCM 1043），不下发 BDS-2 D1/D2（RTCM 1042）。之前所有 1043 帧被 Rtcm.dispatch 静默丢弃，BDS 时钟修正完全依赖 BRDM 预载文件。

**逆向方法**：
1. 在 Rtcm 里加临时 hex dump（从 byte 4 开始，跳过 type field 尾部 4 bit）
2. 抓到 6 帧真实 1043 payload（来自 4 颗卫星：C18/C03/C10/C13）
3. 对比同一颗卫星两次更新帧（Frame 2 vs 5 = C03, Frame 3 vs 6 = C10）的 bit 变化，定位变化字段
4. 用 decodeType1042 的已知字段布局当模板，从 bit 位置 0 开始逐段验证物理量合理性

**Payload 布局（220 bit，B-CNAV1 第一段）**：
| bit 位置 | 大小 | 字段 | 缩放 | 验证 |
|----------|------|------|------|------|
| 0-5 | 6 | PRN | — | ✅ C03/C10/C13/C18 |
| 6-18 | 13 | BDS Week | — | ✅ Week=483（BDS 周，非 GPS 周！） |
| 19-22 | 4 | URA/SVA | — | ✅ URA=9 |
| 23-36 | 14 | IDOT | P2_43·SC2RAD | ✅ ~±1e-11 rad/s |
| 37-41 | 5 | IODE | — | ✅ 3/23 等 |
| 42-58 | 17 | TOC | ×8.0 | ✅ 时钟参考时间 |
| 59-69 | 11 | f2 | P2_66 | ✅ 二阶项 |
| 70-91 | 22 | f1 | P2_50 | ✅ 一阶项 |
| 92-115 | 24 | f0 | P2_33 | ✅ 时钟偏差 |
| 116-120 | 5 | IODC | — | ✅ 0/31 等 |
| 121-138 | 18 | CRS | P2_6 | ⚠️ C03 为 0（GEO 卫星不传） |
| 139-154 | 16 | DELN | P2_43·SC2RAD | ⚠️ C03 为 0 |
| 155-186 | 32 | M0 | P2_31·SC2RAD | ⚠️ C03 为 0 |
| 187-204 | 18 | CUC | P2_31 | ⚠️ C03 为 0 |

**实现范围与限制**：
- ✅ 完整解码时钟修正参数（f0/f1/f2/toc/iodc）+ 部分轨道参数（crs/deln/M0/cuc）
- ❌ **e/sqrtA/i0/OMG0/omg 不在这 220 bit 里**——完整 B-CNAV1 主帧 486 bit 分两段传输，当前 1043 payload 只装了第一段
- 轨道核心参数（e, sqrtA, i0, OMG0, omg）仍依赖 BRDM/BRDC 预载
- 实现策略：nav.eph[sat-1] 已由 loadNav 预载完整轨道，decodeType1043() **覆盖时钟参数 + 覆盖非零轨道补充参数**（C03 GEO 后半段全零，不会污染 BRDM 预载值）

**代码变更**：
1. `rtklib-core/.../Rtcm.java` — 新增 `decodeType1043()` + switch 注册 `case 1043`
2. `rtklib-core/.../PppProcessor.java` — `isEphemerisType()` 加 1043

**验证**：
- 编译通过
- WUH20+SSRC01CAS0 实时流 120s：`ephTypes={1043=5, 1045=6}` ✅（同时 BDS 和 GAL 星历都被解码）
- 120/120 全 PPP 解 ✅
- 额外安全措施：IODE 一致性检查——1043.iode 与 BRDM 预载 eph.iode 不匹配时，只更新时钟参数（f0/f1/f2/toc/iodc），不覆盖轨道参数（防止 BDS-2 GEO 卫星零轨道参数污染 BRDM 预载）

**汇总表更新**：见文档顶部汇总表 FIX-ALG-033/034 两行。

## FIX-ALG-035 / 实时流诊断日志降级 + SSR 录制回放闭环 + PPP-RTK AR 触发条件实测（2026-10-26）

**性质**：诊断代码清理 + 测试工具能力补齐，不改动定位算法核心逻辑。

### 1. IFLC 诊断日志降级（PppRtkCore）

`ppprtkRes()` 内原有一段 `rtk.epoch <= 3` 的 IFLC 诊断块（逐卫星打印 code/L/P/SNR/freq、Lc/Pc、zeroLc/zeroPc/zeroBias 计数），用于排查"BDS+IRN 组合后 Lc=0"问题。该问题已定位为流特性（WUH20 不发 BDS B2a，freqIdx=1 恒空），且 `nv` 已稳定，日志无保留价值。

现降级为 `LOG.trace` 且仅在 `LOG.isTraceEnabled()` 时才遍历观测做组合计算，正常运行零开销：

```java
if (LOG.isTraceEnabled() && post == 0 && rtk.epoch <= 3
        && opt.ionoopt == Constants.IONOOPT_IFLC) { ... LOG.trace(...); }
```

`PPP_DEBUG_REJ` 控制的 REJ 汇总日志保留（按开关触发，缺省关闭）。

### 2. 实时流录制能力（SsrAccessCheckTest）

`RtcmTap` 增加可选中继：CRC24Q 校验通过的完整 RTCM 帧原样落盘，得到可直接回放的 `.rtcm3` 文件。新增环境变量：

| 变量 | 含义 |
|------|------|
| `RECORD_OBS` | 观测流录制路径 |
| `RECORD_SSR` | SSR 流录制路径 |

报告 `[OBS]/[SSR]` 段新增 `recorded: N bytes -> 路径` 行。

**录制产物（2026-10-26，WUH20 + SSRC01CAS0，120s）**：

| 文件 | 大小 | 帧数 | 内容 |
|------|------|------|------|
| `test-data/gnss-validation/rtcm/obs_wuh20_20261026.rtcm3` | 269,731 B | 1008 | MSM7 观测 + 1006/1033 等 |
| `test-data/gnss-validation/rtcm/ssr_ssrc01cas0_20261026.rtcm3` | 196,089 B | 396 | SSR 1057-1060/1240-1242/1258-1260/1265-1270 |

**回放验证（`OBS_FILE` + `SSR_FILE` + `SSR_ALIGN=1`）**：
- SSR 时间戳平移 153 个（94 颗卫星），delta = -118.0 s
- 结果：`total=119 success=119 fail=0`，`ppp=67 single=52`，`ephTypes={1043=6, 1045=2}`，VERDICT=OK

说明录制文件完整可回放，后续 SSR 相关回归测试不再依赖实时网络。

### 3. PPP-RTK AR 触发条件实测（结论：当前流与几何下不可达 FIX）

`ENABLE_PPP_RTK_AR=1` 跑 120s：`fix=0 float=0 ppp=118`，`ratio=0.00`。

**根因一（开关耦合）**：IFLC 模式下 `PppRtkAmbFix.ppprtkAmbFixIf()` 委托 `PppAmbFix.pppAmbFixWlNl()`，后者首行 `if (!cfg.enablePppAR) return -1;` 硬性要求 `enablePppAR`。因此**只开 `enablePppRtkAR` 时 AR 分支静默退出**，必须同时开 `enablePppAR`。

**根因二（卫星数不足）**：同时开启两开关后跑 240s，AR 每历元都在尝试：

```
WL rounding nb=4 nFixed=4(100%) maxFrac≈0.19
NL rounding nb=4 nFixed=3(75%)
PPP-AR: too few NL fixed (rounding): 3
```

WL 4 星全部固定，但 NL 只固定 3 星，低于 NL 最小固定数 4 的门限，故始终返回失败，`sol.stat` 保持 PPP。

**为什么只有 4 颗**：`elmin=10°` + IFLC 双频可用 + WL 弧段累积（`niwl>=1`、MW 有效、WL sigma 门限）联合筛选后，WUH20 单历元可用卫星仅 5-10 颗（BDS 因缺 B2a、freqIdx=1 恒空被 IFLC 排除），满足 WL 条件的只有 4 颗。

**结论**：AR 链路本身工作正常（WL 100% 固定），瓶颈是可见卫星数与 BDS 双频缺失。要拿到 FIX 解需要：换 BDS B2a 更强的测站、或降低 `elmin`、或改用非组合（UC）模式走 LAMBDA 路径（`ionoopt != IFLC` 时 `ppprtkAmbFixUc` 生效，门限为 nb>=4，同样受卫星数约束）。

**未改动算法**：本轮只做实测与开关接线（测试侧新增 `ENABLE_PPP_AR` 环境变量），未修改 `PppAmbFix`/`PppRtkAmbFix` 门限逻辑——门限属算法冻结范围，需另行评估授权。


## FIX-ALG-036 / SSR 解码簇修复：相位偏差系统映射、轨道帧 BDS iodcrc、码偏差 ssr_sig mode16（2026-10-26）

- **日期**：2026-10-26
- **模块/位置**：tklib-core/src/main/java/org/rtklib/java/rtcm/Rtcm.java
- **类别**：C 对齐修复 + JAVA-ONLY 增强
- **C 版依据**：RTKLIB-2.5.1/src/rtcm3.c
  - case 1264=VTEC（跳过）、1265-1270 依次映射 GPS/GLO/GAL/QZS/SBS/CMP（L2866-2872）
  - decode_ssr1 BDS per-sat：np=6, **ni=10, nj=8**（iodcrc 位宽），有效 iode=iodcrc（L1653-1655）
  - decode_ssr4（comb orbit+clk）同样 ni=10, nj=8（L1805-1840）
  - decode_ssr2/3/7 头部 **无 datum 位**（ssr2_head L1601-1631），ssr1/ssr4 头部有 datum（ssr1_head L1559-1597）
  - ssr_sig_bds[16]=0, ssr_sig_bds[17]=0（原版标注"不支持 B1I/B1Q"，L150-154）
- **问题现象**：WUH20 实时流 CAS SSRC01CAS0 SSR 解码 nv=31→32（修复前）→ 40-44（修复后），epoch 62 起崩溃至 nv=0。崩溃前 BDS 卫星 phase bias 被存入 IRN 槽位，码偏差 mode16 全跳过。
- **根因**（7 个叠加缺陷）：
  1. **case 1264-1270 系统映射整体错移**：Java 把 1264-1270 映射为 GPS/GLO/GAL/QZS/SBS/CMP/IRN；RTKLIB 2.5.1 为 1264=VTEC、1265-1270=GPS/GLO/GAL/QZS/SBS/BDS。后果：CAS 流 1270（BDS 相位偏差）被写入 IRN 槽位→isSsrValid 剔除全部 BDS 卫星
  2. **decodeSsrHead 无条件跳 datum 位**：clock/codebias/ura/hrclock（ssr2_head）无 datum 位，Java 一律跳过→头部整体 1bit 错位，钟差改正全错
  3. **decodeSsrOrbit BDS per-sat 位宽 8≠10+8**：BDS 轨道帧 ni=8（Java）vs ni=10+nj=8（RTKLIB 2.5.1），缺 iodcrc 8 位→后续 121bit 轨道改正整体错位
  4. **decodeSsrCombOrbClk 同上**：BDS 组合轨道帧同缺陷
  5. **decodeSsrSatId 缺 offp**：QZS SVID 需 +192、SBS 需 +120，否则 satno=0
  6. **码偏差比例尺错 500 倍**：Java ssrSigned(cb,14,0.02e-3)=2e-5 m，RTKLIB 为 ×0.01 m；且 sig-1 直索引错（需经 ssr_sig 表）
  7. **SSR_SIG_BDS mode 16/17=0**：原版 RTKLIB 2.5.1 就有的硬缺陷——BDS B1I/B1Q（每颗卫星固定播发的码偏差）被跳过
- **修改内容**：
  1. case 1264→return true；1265→SYS_GPS；1266→SYS_GLO；1267→SYS_GAL；1268→SYS_QZS；1269→SYS_SBS；1270→SYS_CMP（L339-347）
  2. decodeSsrHead 拆为带 hasDatum 重载，orbit/comb 调用传 true，clock/codebias 传 false（L1316-1339）
  3. decodeSsrOrbit 按系统区分 ni/nj：GAL/BDS=10/8，SBS=9/24，其他=8/0；BDS iode 取 iodcrc（L1380-1417）
  4. decodeSsrCombOrbClk 同上（L1628-1680）
  5. decodeSsrSatId 补 offp：QZS+192、SBS+120（L1352-1360）
  6. 新增 ssr_sig_* 表，decodeSsrCodeBias 按表映射 mode→CODE_*，比例尺 cb*0.01（L1460-1487, L1491-1530）
  7. decodeSsrPhaseBias 整体重写：专用头（无 datum，多 dispe1+mw1），per-sat prn 内联解析（不再走 decodeSsrSatId），每偏差 32bit（mode5+sii1+swl2+sdc4+bias20×0.0001m），yaw/yaw_rate ×180 转角度
  8. JAVA-ONLY 增强：SSR_SIG_BDS mode 16/17 从 0 改为 CODE_L1C（B1I 频率 1561.098GHz≈GPS L1，硬件延迟改正量可直接复用）
- **验证**：
  - Run0（修复前）：epoch 1-3 nv=20-22，epoch 50 nv=12，epoch 100 nv=0；sols=119, ppp=47, single=72
  - Run2（修复生效后）：epoch 1-3 nv=31-32，epoch 50 nv=28，epoch 100 nv=0；sols=119, ppp=67, single=52
  - Run4（+BDS iode/iodcrc 修复后）：epoch 1-3 nv=40-44，epoch 50 nv=23，epoch 62 nv=0
  - Run5（+SSR_SIG_BDS mode16 修复后）：同 Run4（**obs.code[sys][frq]=0 导致 osbL1/osbP1 全 0，WL dev gate kept=2<4，AR 固定失败，KF 崩溃**——根因不在 SSR 解码，而在观测层 code 映射）
  - CAS 流 BDS 码偏差每星固定 mode=16（probe 实测 90 次出现，ssr_sig_bds 原版=0 全跳过）
  - BDS 轨道帧 1258 per-sat 布局验证：prn6-42 clean，iode=iodcrc=140，dx 改正 -1.393~-1.928m（probe_1258_rtklib.py）
- **对齐结论**：1-7 条修复对齐 RTKLIB 2.5.1 rtcm3.c decode_ssr 系列函数；第 8 条为 JAVA-ONLY 增强（原版 2.5.1 就漏掉了 B1I 码偏差 mode）。

**下一步**：obs.code[sys][frq] 全 0 导致 PppAmbFix osb=0、WL sig 放大、dev gate 踢光候选、AR 固定失败、KF 崩溃——根因在观测层信号码映射（decode 观测时 RINEX 信号码未匹配 sat2code 表），需另开 session 排查。

## FIX-ALG-037：MW 宽巷频率槽自动选取（JAVA-ONLY 扩展，BDS 信号槽位适配）

- **日期**：2026-10-26
- **模块/位置**：
  - `rtklib-core/src/main/java/org/rtklib/java/ppp/PppCore.java`，新增 `mwFreqIdx(sat, code, nav)`（L1509）与 `ssatCodeArr(Ssat)`（L1584）；`mwmeas()`（L1590）与 `detslpMw()`（L1370）改为共用该选择逻辑
  - `rtklib-core/src/main/java/org/rtklib/java/ppp/PppAmbFix.java`，Pass 1（L146-155）/ Pass 3 QbWl（L297-299）/ IFLC 分支（L698-733）全部改用 `mwFreqIdx` + `freqPair`
- **类别**：JAVA-ONLY 扩展（RTKLIB 原版 `mwmeas`/`detslp_mw` 固定使用 code[0]+code[1]，BDS-2 无此信号对则失败）
- **C 版依据**：RTKLIB 2.5.1 `ppp.c` `mwmeas()` L1241-1263 固定 `code[0]`/`code[1]`；`detslp_mw()` L1705-1731 同样固定取 `freq1=sat2freq(sat,code[0],nav)`、`freq2=sat2freq(sat,code[1],nav)`。C 版在 BDS-2 上 `code[1]=0` 时 `freq2=0`，`mwmeas` 返回 0（实际该卫星在 C 版同样不可用）。
- **问题现象**：WUH20 实时流 BDS-2 卫星（PRN 106-151，code=[40,0,0,42,0,0]，B1I 在槽 0、B3I 在槽 3）的 `detslpMw` 中：
  1. `freq2 = sat2freq(sat, code[1]=0, nav) = 0`
  2. `lamWl = CLIGHT/(freq1-freq2)` 产生**数值错误波长**
  3. 宽巷累积 `xrwl/rw/niwl` 基于错误的 λ_wl 积分
  4. 后续 `PppAmbFix.pppAmbFixWlNl()` Pass 2 检查 `ssat.mw[0] != 0 && niwl >= 1`，若满足则用 `xrwl/rw` 作为 wlMean —— 但波长已错位，wlMean 与真实宽巷整数差数周
  5. 滑动平均误差累积或周跳/重置清零 `ssat.mw[0]` 后，该卫星永远停留在 `skipMw` 状态（`niwl=0`），AR 再无可能使用
  - **日志佐证**：修复前 WL-DIAG 显示 BDS 卫星 `skipMw=10/119 历元`、`nbWl=14/29`（14 颗被拒）；修复后 `skipMw=5/119`、`nbWl=17/29`
- **根因**：
  - **根本原因**：RTKLIB 原版针对 GPS/GAL 的 `code[0]+code[1]` 固定假设，不适用于 BDS-2（B1I/B3I 分处槽 0/3，槽 1 为空）
  - **Java 侧放大**：`ssat.code` 只分配 `NFREQ=6` 个槽位，B3I（槽 3）在拷贝 `ssat.code[fc][0] = obs.code[fc]` 时被保留，但 `detslpMw` 硬编码取 `[0]`/`[1]`，错过了 B3I
  - **算法后果**：宽巷波长 λ_wl 错误 → 宽巷整数 N_wl 全部错位 → AR 无法固定 → 卫星被永久 skip
- **修改内容**：
  1. **新增 `PppCore.mwFreqIdx(sat, code, nav)`**（L1509-1547）：默认取 `code[0]+code[1]`，若 `freq1==0 || freq2==0` 且卫星为 BDS，则扫描所有非空槽位，选频距最大且优先传统信号（40=B1I / 42=B3I / 27=B2I）的一对槽位；非 BDS 系统仍回退 `{0,1}`
  2. **`mwmeas()` 与 `detslpMw()` 统一调用 `mwFreqIdx`**（L1370, L1590）：保证"计算 MW 组合"与"累积宽巷"使用同一频率对，避免码-频错位
  3. **新增 `PppCore.freqPair(sat, code0, code1, nav)`**（L1562-1570）与 `freqPair(sat, code[], nav)`（L1578-1581）：当 `code0/code1` 对应频率为 0 时，按星座回退到系统常量（BDS→FREQ1_CMP/FREQ2_CMP，GPS/GAL→FREQL1/FREQL2），替代原先 PppAmbFix 里"只要 freq==0 就用 GPS L1/L2"的错误回退
  4. **`PppAmbFix` 全面改用统一逻辑**：Pass 1（L146-155）、Pass 3 非 IFLC 分支（L297-299）、IFLC 分支（L698-733）全部改为 `ssatCodeArr` → `mwFreqIdx` → `freqPair`，保证 lamWl/lamNl 与 detslpMw 累积的波长严格一致
- **验证**：
  - 测试命令：`mvn test -pl rtklib-test -Dtest=SsrAccessCheckTest "-Dsurefire.failIfNoSpecifiedTests=false"`（WUH20 实时流，119 历元，ENABLE_PPP_RTK_AR=1, ENABLE_PPP_AR=1, NF=3）
  - **关键指标对比**：
    | 指标 | 修复前 | 修复后 |
    |------|--------|--------|
    | skipMw 累计 | 10 | **5** |
    | skipMw 单历元最大 | 2 | 2（仅 epoch 33/42/49/88 滑周后瞬态） |
    | skipVar 累计 | 1985 | **1812** |
    | epoch 119 nbWl | 14 | **17** |
    | PPP complete | 119/119 | 119/119 |
    | VERDICT | OK | **OK** |
  - **WL-SAT 日志验证**：修复前 sat=106 `mw=52.86cyc` vs `wlMean=44.11cyc`（波长不一致）；修复后 `mw=44.19cyc` vs `wlMean=44.11cyc`（λ_wl 对齐）
  - **AR 固定量变化**：WL 565→436，NL 513→408（**解释**：修复前 BDS 用错波长导致虚高固定数；修复后波长正确，AR 更严格，部分 BDS/GAL 因 SSR 无宽巷 OSB（osbL1~osbP2=0）小数部分 ±0.3 被 dev gate 拒绝，属数据侧限制）
- **对齐结论**：
  - 与 C 版语义**部分对齐**：C 版 BDS-2 同样无法使用 MW（`code[1]=0` → `mwmeas` 返回 0），本修复为 JAVA-ONLY 增强，让 BDS-2 在 Java 版可用
  - GPS/GAL 路径与 C 版完全一致（`code[0]+code[1]` 默认选取不变）
  - 频率对选择逻辑在 `mwmeas`/`detslpMw`/`PppAmbFix` 三处强制一致，消除 Java 独有的码-频错位风险
- **已知边界**：
  - 若 BDS-2 卫星观测中 B1I 或 B3I 缺失（如只有 B1I），`mwFreqIdx` 仍会回退到 `code[0]+code[1]`，`mwmeas` 返回 0，与 C 版行为一致
  - `freqPair` 的 BDS 回退使用 `FREQ1_CMP`（B1I）/ `FREQ2_CMP`（B2I），若实际观测只有 B1I+B3I，回退频率仍可能与真实信号不符；当前数据集 BDS-2 均含 B1I+B3I，未触发此边界
  - WL/NL 固定量较修复前下降是**预期行为**：修复前波长错误导致部分卫星虚固定，修复后更严格；若需提升固定率，需 SSR 播发端提供宽巷 OSB 或改用非组合（UC）模式

---

## FIX-ALG-038：PPP-RTK 初始化死循环防护（sat=107 型异常永久排除）

- **日期**：2026-10-26
- **模块/位置**：
  - `rtklib-core/src/main/java/org/rtklib/java/ppprtk/PppRtkCore.java`，`udbias()`（L466-510）新增初始化阶段死循环检测与永久冻结；`ppprtkRes()`（L778, L870）三处永久排除检查
  - `rtklib-core/src/main/java/org/rtklib/java/ppp/PppCore.java`，`udbias()`（L551）新增初始模糊度 sanity check（FIX-ALG-044）
- **类别**：缺陷修复 + JAVA-ONLY 扩展（RTKLIB 原版无此防护，异常卫星会无限重试）
- **C 版依据**：RTKLIB 2.5.1 `ppp.c` `udbias_ppp()` L1545-1585 与 `udbias()` L1587-1650 均无"永久排除"机制——仅当 `x[j]==0` 时初始化，若初始化后 innovation 持续超限，C 版会每历元重新初始化（死循环）。
- **问题现象**：WUH20 实时流 sat=107（BDS-2 PRN 2，code 40+42=B1I+B3I）：
  1. 初始 Pc-Lc 偏差 **372.43m**（正常卫星 ±20m）
  2. `udbias` 用该坏 Pc 初始化 `x=372m`，KF 处理后 innovation=33-63m > maxinno=63
  3. `ppprtkRes` 拒绝该观测，`rejc[0]++`
  4. 下一历元：`udbias` 再次用同样的坏 Pc 初始化（因 `x==0` 且 `bias[i]!=0`），innovation 再次超限
  5. **无限死循环**：sat=107 的 REJ 日志从 epoch 1 重复到 epoch 119，永不停止
  - **日志佐证**：
    ```
    [DBG107] epoch=1 Lc=3.7758E7 Pc=3.7757E7 bias_before=372.43
    [DBG107] epoch=2 x[j]=372.43 P[jj]=3600.0
    ppprtkRes REJ epoch=1 ... 107:bias0 107:inno33
    ppprtkRes REJ epoch=2 ... 107:bias0 107:inno33
    ... (重复至 epoch 119)
    ```
- **根因**：
  - **根本原因**：sat=107 的伪距观测值损坏（Pc-Lc 初始偏差 372m），可能源于接收机端该卫星的码-相位硬件延迟异常或 SSR 改正错误
  - **算法缺陷**：RTKLIB/Java 的 `udbias` 设计假设"初始 Pc-Lc 偏差在合理范围内（<100m）"，无 sanity check；若超限，KF 无法收敛，每历元重新初始化形成死循环
  - **Java 侧放大**：`rejc` 计数器在 `ppprtkRes` 中累积，但无阈值触发永久排除，导致同一卫星反复占用计算资源
- **修改内容**：
  1. **FIX-ALG-044 初始模糊度 sanity check**（`PppCore.java` L551）：
     ```java
     if (bias[i] != 0 && Math.abs(bias[i]) > 500.0) bias[i] = 0.0;
     ```
     初始 Pc-Lc > 500m 视为数据损坏，直接置零跳过初始化（500m 阈值经实测：正常卫星初始偏差 150-450m，sat=107 为 372m，100m 阈值会误杀正常卫星）
  2. **FIX-ALG-043 初始化阶段死循环检测**（`PppRtkCore.java` L466-510）：
     - 仅在前 10 历元（`INIT_PHASE_EPOCH=10`）启用：死循环卫星在 epoch 5-7 内 `rejc` 即达 10，正常卫星不会在初始化阶段累积如此多的 innovation 拒绝
     - 触发条件：`rtk.epoch <= 10 && rejc[f] >= 10`
     - 触发动作：`rejc[f]` **冻结为 1000**（`PERM_FROZEN_REJC`），单向锁存，永不清零
  3. **FIX-ALG-043 永久排除检查**（3 处）：
     - `udbias()` L505：`rejc >= 1000` → 永久 `x=0`、`vsat=0`，且清零 `mw[0]/gf[0]/xrwl/rw/xswl/niwl`（避免残留状态干扰）
     - `udbias()` L572（offset 循环）：`rejc >= 100` → `continue`（跳过 offset 累积）
     - `udbias()` L594（initx 循环）：`rejc >= 100` → `continue`（跳过重新初始化）
     - `ppprtkRes()` L778：`rejc >= 100` → `exc[i]=1; continue`（跳过 phase 和 pseudorange 处理）
     - **阈值分层**：冻结用 1000，后续检查用 100，防止正常卫星（rejc 最高 ~60）误触发
  4. **FIX-ALG-043b 即时重置**（`ppprtkRes()` L870）：
     - `rejc >= 4` 时立即 `initx(x, P, nx, 0, VAR_BIAS, idx); exc[i]=0;`
     - **不再清零 rejc**（修复前 `rejc=0` 导致死循环卫星无法累积到永久排除阈值）
- **验证**：
  - 测试命令：同 FIX-ALG-037（WUH20 实时流，119 历元）
  - **关键指标对比**：
    | 指标 | 修复前 | 修复后 |
    |------|--------|--------|
    | sat=107 REJ 历元 | 1-119（全部） | **1-7**（epoch 8 起永久排除） |
    | sat=107 rejc 阈值 | N/A | epoch 8 达 20 → epoch 5 达 10（冻结） |
    | 永久排除卫星数 | 0 | **1**（仅 sat=107） |
    | fix 解数 | 78 | **92** |
    | PPP complete | 119/119 | 119/119 |
    | VERDICT | OK | **OK** |
  - **回归测试**：GPS/GAL/其他 BDS 卫星（sat=29, 106, 108, 109, 114 等）未被误排除：
    - sat=29（GPS）：epoch 38 才首次 innovation 拒绝（maxinno 门限收紧后），epoch 58 才累积到 rejc=20，远超 `INIT_PHASE_EPOCH=10`，**不触发冻结** ✓
    - sat=106/108/109（BDS）：epoch 47+ 才开始拒绝，同样不触发 ✓
  - **PERM-FREEZE 日志**：
    ```
    [PERM-FREEZE] epoch=5 sat=107 frq=0 rejc>=10 in init phase -> perm freeze
    ```
    仅 sat=107 一条记录，无其他卫星误触发
- **对齐结论**：
  - **JAVA-ONLY 扩展**：RTKLIB 2.5.1 无此防护机制，异常卫星会无限重试；本修复为 Java 版独有
  - **与 C 版语义兼容**：正常卫星（初始 Pc-Lc < 500m，初始化后 KF 可收敛）行为与 C 版完全一致；仅异常卫星被永久排除，避免死循环
  - **三层防护叠加**：
    1. FIX-ALG-044（500m sanity check）拦截初始极端异常
    2. FIX-ALG-043b（rejc>=4 即时重置）防止单次坏观测毒化 KF
    3. FIX-ALG-043（初始化阶段 rejc>=10 冻结）拦截持续死循环
  - 阈值设计经实测验证：500m/10/10/1000/100 的组合在 WUH20 数据集上无误杀、无漏杀
- **已知边界**：
  - 若卫星在 epoch 11+ 才出现持续死循环（如中途信号损坏），当前机制不触发永久排除（`INIT_PHASE_EPOCH=10` 限制）；该场景下 FIX-ALG-043b 的即时重置仍生效，但会每历元重试
  - 若 SSR 数据流中途改正错误导致某卫星 Pc-Lc 突变 >500m，FIX-ALG-044 仅在初始化时生效，无法拦截运行时突变；需配合 FIX-ALG-043b 的即时重置
  - `PERM_FROZEN_REJC=1000` 为硬编码，未提取到 `Constants.java`；若需调整，需同步修改 `PppRtkCore.java` 3 处（L485, L493, L505）
- **后续工作**：
  - 建议将 `INIT_PHASE_EPOCH/INIT_REJC_THRESHOLD/PERM_FROZEN_REJC` 提取到 `Constants.java`，便于集中管理
  - 若需支持"运行时死循环防护"，可考虑在 `ppprtkRes` 中增加滑动窗口检测（如最近 10 历元 rejc 增长 >5 则冻结），但需评估对正常卫星的影响

## FIX-ALG-039：adjust 平差链路闭环（协方差符号 / 独立性模型 / 数据源 / 成果层）

- **日期**：2026-10-27
- **模块/位置**：
  - `rtklib-core/src/main/java/org/rtklib/java/data/SolData.java`：新增 `covEcef[9]`
  - `rtklib-adjust/src/main/java/org/rtklib/java/adjust/covariance/CovAssembler.java`：`extractCovariance3x3()`、`assembleGlobalR(...)`
  - `rtklib-adjust/src/main/java/org/rtklib/java/adjust/engine/GnssBaselineAdjust.java`：新增 `Policy` record 与 `fromConfig()`
  - `rtklib-adjust/src/main/java/org/rtklib/java/adjust/engine/MultiBaselineAdjust.java`：dXyz 数据源、网平差累积、一致性告警、白度检验接入
  - `rtklib-adjust/src/main/java/org/rtklib/java/adjust/engine/DisplacementSeries.java`、`ResidualWhiteness.java`（新增）
- **类别**：JAVA-ONLY 扩展（adjust 模块在 C 版 RTKLIB 中无对应实现）
- **C 版依据**：无。C 版 `rtkpos()` 为单基线引擎，不存在多基线平差层；本条目仅改变 Java 侧 adjust 模块的数值行为，core 定位主链路不受影响。唯一涉及 core 的变更是 `SolData` 新增字段（纯新增，`Accuracy` 路径原样保留）
- **问题现象**：
  1. adjust 使用的协方差来自 `Accuracy`（`sqrt(|c_ij|)`），非对角符号在构造时已丢失，导致协方差矩阵可能非正定或角度关系错误
  2. `assembleGlobalR` 把 `(ΣP⁻¹)⁻¹` 当作基线间互协方差块，隐含"基线完全相关"且相关系数 `1/k` 随基站数增大而减小——真实共模误差不会因增加基站而减弱，模型物理上不成立
  3. 基线向量取自 `hPos[0..2]`（设计矩阵灵敏度），不是 ECEF 位置
  4. `CrossValidationConfig` 中 `verifiedThreshold / suspectThreshold / plK* / crossCheckedCovInflate` 等字段从未被读取（死配置），阈值硬编码在代码里
  5. 缺少整时段成果层：既无速率/显著性输出，也无自由网基站位移与残差白度检验
- **根因**：
  - 协方差链路在 `Sol → SolData(Accuracy)` 转换处做了 `sqrt(abs)` 与格式适配，信息不可逆丢失
  - 互协方差块误用了"融合结果协方差"，把结果量当观测量
  - adjust 早期按"假设三条基线共享同一 rover 观测"设计，与 core 实际的"逐基站独立调用"架构不一致
- **修改内容**：
  1. `SolData` 增加 `covEcef`：由 `Sol.qr` 上三角 6 值对称展开 9 元，保留符号；`qr[0]==0` 置 null
  2. `CovAssembler.extractCovariance3x3()` 优先用 `covEcef`，`Accuracy` 仅作回退并在 javadoc 注明符号丢失
  3. `assembleGlobalR(epoch, qualityWeight, rho)`：默认 `rho=0` ⇒ R 为块对角；`rho>0` 时按 `rho·sqrt(C_ii⊙C_jj)` 填互块；javadoc 明确 `fuseRoverCovariance()` 为结果量
  4. dXyz 改用 `CovAssembler.extractBaselineDxyz()`；删除硬编码 `SUSPECT_THRESHOLD` 分支
  5. 新增 `GnssBaselineAdjust.Policy`（`fromConfig` 映射 8 个配置字段），`plK(grade)` 统一保护级系数
  6. 新增 `DisplacementSeries`（WLS 速率 + σ 旋转到 ENU + 显著性 + 残差 RMS + 异常历元）、`MultiBaselineAdjust` 网平差接线与 `ConsistencyListener`、`ResidualWhiteness`（lag-1 + 游程检验）、`baseCoordinateSigma`（σ²·I₃ 加到观测块）
- **验证**：
  - `mvn test -pl rtklib-adjust`（全量）
  - 新增测试：`CovAssemblerCovEcefTest`、`CovAssemblerRTest`、`DisplacementSeriesTest`、`MultiBaselineAdjustNetworkTest`、`ResidualWhitenessTest`
  - `IflcPairNegotiationTest` 修复后 8/8 通过；core 全量测试见本次验证输出
- **对齐结论**：
  - core 主链路数值行为不变（仅新增 `covEcef` 字段，原 `Accuracy` 路径保留）
  - adjust 由"伪多基线"修正为与 core 架构一致的"独立基线 + 块对角 R"
- **已知边界**：
  - `baseCoordinateSigma` 默认 0，基站坐标系统偏差默认不建模；同一基站的偏差跨历元相关，当前按历元独立处理，会略微低估整时段平均值的不确定度
  - `rho` 为经验旋钮，当前无实测相关系数标定方法


## FIX-ALG-040 / 自由网平差 O(m²) 内存爆炸 + 残差计算错误（2026-10-27）

**日期**: 2026-10-27
**类别**: adjust / 算法错误 + 可扩展性（JAVA-ONLY）
**触发**: 用 rtklib-joint 同源真实数据（2 基站 + 1 测站 RTCM3 录制流，0~17 时共 18 小时）实测 adjust 全链路，发现整时段自由网平差 OOM（Java heap space）；约束平差虽通过但自由网 sigma0 异常（1.4e8 / 4.7e8 量级）。

### 问题现象与根因（三个独立 bug）

1. **O(m²) 权矩阵物化**：`NetworkAdjustment.freeNetwork()` 把全部累积观测合成一次整体平差，
   `SimpleMatrix R = new SimpleMatrix(m, m)` 直接物化观测权矩阵。18 小时实测 7766 条基线观测
   （m=23298 标量方程）→ 23298² × 8B ≈ 4.3 GB → OOM。单元测试仅 4 条观测（36×36），从未暴露。
   而 R 严格块对角（core 逐基站独立解算 ⇒ 观测互不相关，FIX-ALG-039 已确立该模型），
   第 i 个观测的权重就是其自身 3×3 协方差的逆，根本不需要 m×m 矩阵。
2. **残差混入绝对坐标**：重写为逐观测累积时，残差误写为 `v = rover − delta − l`（rover 为绝对
   ECEF 坐标，10⁶ m 量级），而正确公式为 `v = H·x − l = δr₀ − δb − l`（x 为位移未知数）。
   近似基准 r0 未消去，v ≈ Rover 绝对坐标，sigma0 达 1e8 量级。
3. **u = R⁻¹l 下标错位**：`rlr += ri[r][c] * lr`（内层循环乘了 `l[r]` 而非 `l[c]`）。
   对角协方差下 `ri[r][c]` 仅 c=r 非零，两者相等——故对角协方差的单元测试全部通过；
   真实 ECEF 协方差强非对角（轴间相关 0.5+），立即现形。

### 修改内容

1. `freeNetwork` 重写为逐观测块累积法方程：N = HᵀR⁻¹H、u = HᵀR⁻¹l 以 3×3 块累加
   （内存 O(n²)，n = 3 + 3×基站数；时间 O(m·n²)）；残差平方和 vRv 与解算后逐观测计算，
   残差公式修正为 `v = δr₀ − δb − l`。重心基准约束 N += w·CᵀC 保持逐元素等价实现。
2. `BaselineObs` 增加 `gpsTime` 字段（观测历元 GPS 周内秒，0=未知不参与淘汰）。
3. `MultiBaselineConfig` 新增 `networkWindowSeconds`（默认 3600，`<=0` 关闭）：
   `MultiBaselineAdjust.trimNetworkObsWindow()` 按观测 GPS 时间从队头淘汰过期观测——
   基站位移监测关注近期状态，避免跨小时数据无限累积（与用户确认：整时段平差 1 小时窗口足够）。
4. `MultiBaselineAdjustRealDataTest` 配置改用独立文件名 `mb-adjust-real-data.properties`：
   `TestDataConfig`（`AdjustRealDataTest`/`BaseStationMonitorTest` 共用）读取同名
   `adjust-test-data.properties` 且期望数字基站 ID（`Integer.parseInt`），本地字符串设备名
   会使这些休眠测试误激活并抛 NumberFormatException。
5. manual 测试可运行性修复：pom 显式 `<excludedGroups>manual</excludedGroups>` 优先于命令行
   `-Dgroups=manual`（0 个测试执行），改为 `<excludedGroups>${surefire.excludedGroups}</excludedGroups>`
   （默认 manual），手动运行：`mvn test -pl rtklib-adjust -Dgroups=manual -Dsurefire.excludedGroups=`
   （rtklib-core 同步修正）。

### 验证

- `mvn test -pl rtklib-adjust`：68 tests，0 failures（含新增回归
  `NetworkAdjustmentTest.testFreeNetworkWithCorrelatedCovariance`：强非对角协方差下
  Rover 恢复 + sigma0 量级守卫）
- 新旧实现对拍（显式 H/R vs 块累积，同数据同种子）：rover 与基站位移解完全一致（<1e-4 m）
- 整时段真实数据（18 h，双基线 4338 FIX 历元）：66.75 s 完成，无 OOM；
  网观测收敛到 452 条（1 小时窗口）；自由网 sigma0 = 0.658（正常量级）

### 已知边界

- 跨 GPS 周边界时周内秒回卷，`newest - oldest <= window` 判断失效（不淘汰，仅受 20000 条硬上限保护）
- gpsTime 未知（0）的观测不参与时间淘汰
- `DATUM_WEIGHT = 1e4` 与观测权重（R⁻¹ ~ 1e4，协方差 mm 级时）同量级，重心基准为"软"约束；
  若观测协方差声明远小于实际误差（如本数据 sigma0≈57），基准约束相对变弱

## FIX-ALG-041 / 白度检验失效：残差向量恒 null + entries 错配（2026-10-27）

**日期**: 2026-10-27
**类别**: adjust / QA 功能失效（JAVA-ONLY）
**触发**: 用 joint 同源真实数据（双基站 + 测站，单小时 228 个成功平差历元）验证 adjust 全链路时，
`getResidualWhitenessReport()` 返回序列数 = 0——228 个成功历元全部喂入了残差，却没有产生任何检验序列。

### 问题现象与根因（三个叠加问题）

1. **`GnssBaselineAdjust.adjustSingle` 残差向量未随结果返回**：L141-142 已正确计算 `v`
   （用于 sigma0 与 Baarda 检验），但 L163 的 `AdjustResult.success(...)` 硬编码传 `null`——
   所有 UNVERIFIED（单基线 k=1）路径的残差永久丢失。
2. **SUSPECT 剔除后 entries 与 v 长度错配**：`adjustSuspect` 把嫌疑基线剔除后重新平差，
   返回的 `v` 只含剩余基线（k−1 条）的残差；`MultiBaselineAdjust.processEpoch` 却把
   全量 `entries`（k 条）与该 `v` 一起喂给 `ResidualWhiteness.feed`，长度守卫
   `v.length < 3 * entries.size()` 静默拦截。
3. **本数据集下 df 恒为 0**：双基线互差 4.77 m（基站坐标系统差，见 FIX-ALG-039 验证数据）
   使 k=2 历元全部判 SUSPECT → 剔除成 k=1（df=0，残差恒为 0 无信息）；k=1 历元本身 df=0。
   即使修掉 1、2，该数据集仍没有任何"有冗余度的平差残差"可喂。

### 修改内容

1. `adjustSingle`：`v` 声明提升到 df 分支之外，df>0 时随 `AdjustResult.success` 返回
   （df=0 时残差数学上恒为 0，仍返回 null）。
2. `MultiBaselineAdjust.processEpoch`：喂白度前按 `result.suspectBaseId` +
   `usedBaselineCount < entries.size()` 同步剔除被剔除基线的条目，保证 entries 与 v 一一对应。
3. `ResidualWhiteness` 新增 `feedSingle(baseId, v3)`；df=0 且嫌疑基线被剔除的历元改喂
   该基线相对融合解的**交叉检核残差**（预测残差，数据探测的标准 QA 序列）——
   这是此类历元唯一有信息量的残差来源，系统差存在时白度检验应报"非白"。

### 验证

- `mvn test -pl rtklib-adjust`：68 tests，0 failures
- 单小时真实数据重跑：白度序列 0 → 3 条（基站 `<BASE_DEVICE_ID>` 轴 0/1/2，各 209 样本），
  lag1 = +0.859 ~ +0.889、runsZ = -9.85 ~ -12.76，全部判**非白**——
  与"基站坐标存在 4.77 m 恒定系统差"的物理事实一致（白度检验恢复了对系统误差的检出能力）

### 已知边界

- 序列只覆盖被判嫌疑且被剔除的基站（本数据固定为 `<BASE_DEVICE_ID>`，ratio×numSat 启发式稳定选它）；
  正常基站（保持 VERIFIED/CROSS_CHECKED 双基线平差）的平差残差序列需有冗余度的历元才产生
- 交叉检核残差含基站坐标系统差本身（米级），会主导白度判定——这是预期行为：
  白度检验的目的就是发现未被函数模型吸收的系统性误差

## FIX-ALG-042 / joint 双基线 FIX=0%：模糊度零初值 + 缺 holdamb（2026-10-27）

**日期**: 2026-10-27
**类别**: rtklib-joint / AR 通道功能失效（JAVA-ONLY）
**触发**: 接手前一会话交接：joint 双基线模式 4219 历元全部 FLOAT（FIX=0%），单基线 SBS 参考 91.2%/91.6%。

### 根因与修复（五处叠加）

1. **[P2] 模糊度初始化缺浮点初值**：`JointCore.udstate` 用 `initx(x,P,nx, offset, SQR(std[0]), idx)`，
   offset 通常为 0；`KalmanFilter.update` 的状态压缩条件 `if (x[i]!=0.0 && P[i*nxi]>0)` 跳过零状态
   → 模糊度方差恒 900 不收敛，LAMBDA 永无法固定。
   修复：新增 `carrierBiasInit`（载波-伪距组合，对齐 RtkCore.udbias 的 bias 语义；zdres 输出 y 为米，
   bias 周 = (SD载波 - SD伪距)·freq/c，IFLC 时为米），初始化 `initx(bias, SQR(std[0]))`，bias==0 时不初始化。
2. **[P2 前置] udstate 时 rover 侧 zdres 未计算**：rtkpos 主流程 L110 只做基站侧初始 zdres，
   rover 段 y 全零 → carrierBiasInit 恒返回 0。修复：udstate 前补 rover 侧 zdres（rr=rb+x[0:3]）。
3. **[P2.5] 缺 FIXHOLD holdamb**：FIX 后模糊度协方差未收缩（xa/Pa 只修正 na×na 位置块），
   模糊度自由漂移，ratio 从上万退化到 ~1（搜索面平坦）。
   修复：resolveAmbiguity 记录固定约束对 (refIdx, jIdx, bias)，FIX 且 nfix>=minfix 时以
   R=1e-6 周² 强约束喂回 KalmanFilter.update（对齐 C 版 holdamb）。
4. **[P3] 模糊度过程噪声缺失**：补 `P[idx*nx+idx] += SQR(opt.prn[0])*tt`（对齐 RtkCore.udbias）。
5. **[双基线 P4] KF 跨基线污染位置**（Joint 独有）：`KalmanFilter.update` 状态压缩只判
   `x[i]!=0 && P[i*i]>0`，不检查 H 列是否全零。bl=1 的 update 虽在 H 中 bl=0 模糊度列全零，
   但这些状态仍被选中，通过 P 的位置-模糊度交叉协方差把 bl=1 的新息"泄漏"到共享位置。
   实测 epoch=0 bl=1 update 把共享位置 y 从 +39.8m 拉到 -31.5m（跳 71m），导致双基线发散。
   修复：`KalmanFilter.update` 状态选择增加 H 列非零检查（`Math.abs(H[j*nx+i])>0`），
   跳过与当前观测无关的状态。
6. **[双基线 P5] holdamb minfix 门槛过高**：原代码 `jrtk.nfix >= opt.minfix(20)` 才触发 holdamb，
   导致 epoch=20 第一次 FIX（ratio=51/77）后模糊度方差未被压缩，epoch=21 起 ratio 骤降至 ~1。
   修复：去掉 minfix 门槛，每次 FIX 成功即执行 holdamb（对齐 RtkCore.holdamb 行为），
   保留 `nHold >= opt.minholdsats` 检查。
7. **[双基线 P6] FIX 后模糊度 P 完全未修正**（核心根因）：`resolveAmbiguity` 中 LAMBDA 成功后
   只修正了 xa[0:na] 和 Pa[0:na,0:na]（位置部分，Schur 补），模糊度 xa[na:nx] 只硬改了值
   （`xa[jIdx] = xa[refIdx] - bias[i]`），但 Pa 中模糊度对应的行/列完全未修正。
   结果 x 和 P 不匹配：模糊度数值跳了几周，方差仍为 69（浮点水平），后续 KF update 中
   模糊度自由漂移。
   修复：FIX 成功写回 jrtk.P 前，手动把 Pa 中所有模糊度（i>=na）的对角设为 1E-6、
   与其他状态的交叉协方差清零。

### 验证（单小时 236 历元，2026-10-26 数据）

| 配置 | 修复前 | 修复后 |
|---|---|---|
| joint 单基线 (`<BASE_DEVICE_ID>`) | FIX=0 | **FIX=216 (91.5%)**，与 SBS 参考 91.2% 持平 |
| joint 双基线 (`<BASE_DEVICE_ID_1>`+`<BASE_DEVICE_ID_2>`) | FIX=0 | **FIX=216 (91.5%)**，与单基线一致 |

### 关键数值证据

- epoch=0 bl=1 update 前后位置：y 分量 +39.83m → -31.48m（跳 71m，P4 修复后跳变消除）
- epoch=20 ratio：bl=0=51.28, bl=1=77.54（LAMBDA 二义性分离完美）
- epoch=21 起 ratio 稳定在 21000/17000（P5+P6 修复后无退化）
- ambVar0 从 FIX 前 69.5 → FIX 后 1E-6（P6 手动压缩生效）
- 单/双基线 FIX 率完全一致 = 216/236 = 91.5%

### 修改文件

- `rtklib-core/.../kalman/KalmanFilter.java`：状态选择加 H 列非零检查（P4）
- `rtklib-joint/.../joint/JointCore.java`：
  - udstate: carrierBiasInit + rover 侧 zdres 前置（P2）
  - resolveAmbiguity: holdamb 去掉 minfix 门槛 + FIX 后模糊度 P 压缩（P5/P6）
  - udstate: 模糊度过程噪声（P3）

---

## FIX-ALG-045 / VRS 输出 FIX=0%：cellInd 编号顺序错位 + LLI 失锁恒置位 + 超容量分批（2026-10-27 修，2026-10-28 更正）

**日期**: 2026-10-27
**类别**: rtklib-vrs / RTCM3 MSM 编码（JAVA-ONLY）
**触发**: VRS 反向验证测试（`VrsRealDataTest#testVrsReverseValidation`）：原始基站+测站 RTK FIX≈77%，
VRS+测站 RTK FIX=0%，ratio 恒 ~1.0。

> ⚠️ 本条目早前版本的验证结论（"LLI 修复后 FIX=91.2%"）**无法复现，系误记**
> （91.2% 是 10-26 数据原始基站的成绩，张冠李戴）。当时的闭环测试只查了 L[0]/L[1]，
> 漏检了下述根因 4 的槽位交叉。以下为 2026-10-28 重新定位后的修正结论。

### 根因与修复（四处）

1. **[核心] MSM 编码 lock 计数恒为 0 → 解码端 LLI_SLIP 全置位**：
   `Rtcm3MsmEncoder.genMsmSig` 里锁计数恒写 `lock[ci]=0`，解码端 `lossoflock` 判
   `lock==0 && prev==0` → 返回 LLI=1。RTK 端 `udbias` 遇 LLI_SLIP 把 `lock[f]` 重置为
   `-opt.minlock(-20)`，需连续无失锁才能爬回 0；每历元都重置 → AR 永不启动 → ratio 恒 0。
   修复：`Rtcm3Encoder` 新增 `lockCnt/lockEpoch/msmEpochSeq` 跨历元状态；`updateLock`
   在真实失锁（LLI&1）或上历元缺失时归零，否则递增并饱和（MSM4=15 / MSM5=1023）；
   `VrsGenerator` 每历元递增 `msmEpochSeq`。
   验证：LLI_SLIP 计数 3240（每历元每观测都置 1）→ 6（仅首历元初始失锁，物理正确）。
2. **[cellmask bug] 超容量裁剪时给空 cell 编号**：`nsat*nsig>64` 裁剪路径中，
   cellmask 编号未排除"该 (sat,sig) 无观测"的空 cell，ncell 虚高超 64。
   修复：只给真实存在观测的 cell 连续编号，受 64 上限约束；顺带修 `genMsmSig`
   边界 `cellIdx>=64`→`>64`（编号值域 1..64，原条件误丢编号恰为 64 的合法 cell）。
3. **[功能] 超容量按卫星分批 + sync 累积**（对齐 MSM 协议 sync 语义）：
   原实现 `nsat*nsig>64`（如 BDS 13 星×6 信号=78）只能砍信号（6→4，丢 B1C/7D）。
   新增 `encodeMsmBatch`：sigmask 跨批保持全局一致（6 信号全列出），satmask 按
   `floor(64/nsig)` 颗分批，非末批 sync=1、末批 sync=0，解码端 obsflag 累积为完整历元。
   `genMsmSat/genMsmSig` 跳过 `satInd==0` 的非本批卫星。`nsat*nsig<=64` 时走原路径，零行为变化。
4. **[真正导致 FIX=0% 的根因，2026-10-28 定位] cellInd 编号顺序 bug**：
   分批重建路径与超容量兜底路径中，cellInd 按 **obs 数组访问顺序**（即接收机 slot 顺序，
   如 2I,7I,5P,6I,1P,7D）连续编号；而 MSM 协议规定 cell 数据按 **cellmask 扫描顺序**
   （sat 外循环 × sig 内循环，即 sigmask 顺序 2I,6I,7I,5P,7D,1P）排列，解码端
   `Rtcm.saveMsmObs` 严格按此顺序读取。顺序不一致 → 各信号值整体交叉错位。
   实测证据（VRS 设在基站位置、改正量=0 的二分诊断）：max|dP|=28.57m、max|dL|=478.7 周；
   sat106 的 slot1(7D) 与 slot3(6I) 的 P/L 值精确互换（原始 slot1 P == VRS slot3 P，反之亦然）。
   修复：两条重建路径改为"先标记真实存在的 (sat,sig) cell，再按 cell 位置顺序连续编号"。
   注：非分批路径 `genMsmIndex` 的编号本来就是按位置扫描，无此 bug；
   旧 roundtrip 测试只查 L[0]/L[1]（2I 恒在 cell 位置 0，不受影响）→ 漏检。

### 验证（2026-10-27 数据，base=`<BASE_DEVICE_ID>`，rover=`<ROVER_DEVICE_ID>`，239 历元，2026-10-28 重测）

| 配置 | 修复前 | 修复后 |
|---|---|---|
| 原始基站+测站（基线 115m） | FIX=77.0%，3D-RMS=5.4639m | 同左（基准） |
| VRS@基站位置（编解码无损验证） | max\|dP\|=28.57m, max\|dL\|=478.7 周 | **max\|dP\|=0, max\|dL\|=0 逐位一致**，FIX=76.6% ≈ 77.0% |
| VRS@测站附近（基线 11m） | FIX=0.0% | **FIX=76.6%**，3D-RMS=5.4651m（与长基线一致） |

- 二分诊断法（`VrsRealDataTest#testVrsAtBaseStreamDiagnosis`）：星历 19 颗全参数差=0、
  code[] 槽位映射一致、观测流全 8 槽位逐项对比 → 问题 100% 定位在编码器 cellInd 编号，
  与 VRS 改正算法无关（改正量=0 时虚拟文件与原始文件逐位一致）
- 分批日志：`sys=32 nsat=13 nsig=6 -> 2 batches of <=10 sats`，解码端 10+3=13 颗累积正确，
  13 星×6 信号=78 cell 完整恢复
- rtklib-vrs 全量回归：17 个测试全部通过（MSM 编码器 5 + VrsGenerator 6 + VrsRealData 6）
- 补充观察：base 与 VRS 的 FIX 偏差同为 N=1.37/E=2.55/U=4.64m，属测试中硬编码 ROVER
  坐标与天线相位中心不一致的系统性偏移，非 VRS 改正误差
- 教训：编解码对比测试必须覆盖全部信号槽位；只查 L[0]/L[1] 会漏掉 slot2+ 的交叉错位

### 修改文件

- `rtklib-vrs/.../rtcm/Rtcm3MsmEncoder.java`：updateLock 真实锁计数、cellmask 编号修复、
  encodeMsmBatch 分批编码、countSatSig 公开统计
- `rtklib-vrs/.../rtcm/Rtcm3Encoder.java`：lockCnt/lockEpoch/msmEpochSeq 状态、
  encodeMsmBatch 公开入口
- `rtklib-vrs/.../VrsGenerator.java`：msmEpochSeq 每历元递增、encodeSystemBatched 分批驱动

---

## FIX-ALG-046 / 网解窄巷固定闭环：双差两端共用卫星位置 + 缺残余 ZTD 状态（2026-11-05）

**日期**: 2026-11-05
**类别**: rtklib-networkvrs / 网解双差观测构建（JAVA-ONLY）
**触发**: `NetworkSolutionRealDataTest`（EUREF CIMO/PRAT/VIRG，2026 DOY 300，30 s，1 h 窗口）：
宽巷 MW 100% 固定，窄巷 LAMBDA ratio 恒 1.0~1.7，3/3 窗口窄巷固定数为 0。

### 根因

`DdObservationBuilder` 对一对卫星只调一次 `satpos`（按站 A 的近似坐标迭代发射时刻），
站 B 的几何距离沿用了站 A 的卫星位置。基线 46~100 km 时两站信号发射时刻差 0.15~0.3 ms，
卫星位移 2~4 m，逐星在双差中留下一个与基线投影相关的**常量偏差**，被 IF 模糊度吸收，
使浮点 N1 小数整体偏移（±0.3 周），而协方差仍按 0.05 周量级标称 → 整数候选距浮点解约 6σ
→ ratio≈1 → LAMBDA 必然拒绝。此前把该现象归因于"广播星历米级轨道误差"（原 NETWORK_VRS_DESIGN §4.1，
该节已清理，更正记录见 §4.2「根因更正记录」），实测精密星历同样失败，该归因被推翻。

次要因素：Saastamoinen 模型与真实天顶延迟有 0.1~0.3 m 系统差，双差后残留为常数量，
一并被模糊度吸收，进一步压低 ratio。

### 修复

1. 站 A / 站 B **各自**按本站坐标迭代发射时刻调 `satpos`（`stateA`/`stateB`），
   几何距离、方位高度角、对流层映射函数分别计算；截止角按两站高度角较小值判定。
2. `BaselineWindowSolver` 未知数扩为 `[dx,dy,dz,ztdA,ztdB,ztdRateA,ztdRateB,N1..Nn]`，
   两站残余 ZTD 及线性变化率各带弱先验（σ=0.3 m / 0.1 m·h⁻¹），吸收模型系统差；
   残差、闭区间检验与 AR 后验全部按新未知数重排。
3. `EphModel.satposPrec` 改 public，供只做单星位置计算的构建器直接调用；
   `EPHOPT_SSRAPC/SSRCOM` 分支在广播位置叠加 `nav.ssr` 轨道改正前补算卫星速度
   （RAC 基需要沿迹/法向，差商步长与 `satposPrec` 一致）。

### 验证（120 历元 / 18 窗口 / 17 颗共视星）

| 星历 | 基线 | ratio | 相位 RMS | 窄巷固定 |
|---|---|---|---|---|
| BRDC | CIMO-PRAT | 11.7 | 1.63 cm | ✅ |
| BRDC | PRAT-VIRG | 13.5 | 1.71 cm | ✅ |
| BRDC | CIMO-VIRG | 23.5 | 2.83 cm | ✅ |
| WUM SP3/CLK | CIMO-PRAT | 10.69 | 1.63 cm | ✅ |
| WUM SP3/CLK | PRAT-VIRG | 12.55 | 1.79 cm | ✅ |
| WUM SP3/CLK | CIMO-VIRG | 23.82 | 2.94 cm | ✅ |

- N1 小数 std：BRDC 0.135 周 / 精密 0.044 周（修复前 0.262 周）
- BRDC 与精密星历 ratio/RMS 几乎同量级 → 双差中轨道/钟差误差高度共模，
  **窄巷固定不依赖精密产品接入**（推翻旧结论，见 NETWORK_VRS_DESIGN §4.2「根因更正记录」）
- 残余 N1 小数 std≈0.17 周（≈1.8 cm）推断为卫星天线 PCO/PCV 未建模（未接 ATX），
  ratio 已远超 3.0 门限，暂不引入该依赖

### 修改文件

- `rtklib-networkvrs/.../netsol/DdObservationBuilder.java`：两站各自求卫星位置/几何/映射函数；
  补算广播速度供 SSR 改正使用
- `rtklib-networkvrs/.../netsol/DdObservation.java`：携带 `tropoMapDiffA/B`（映射函数差）
- `rtklib-networkvrs/.../netsol/BaselineWindowSolver.java`：ZTD/ZTD 率状态 + 弱先验
- `rtklib-core/.../ephemeris/EphModel.java`：`satposPrec` 改 public

---

## FIX-ALG-047 / 星历最大时效按系统分档：删除 7 天统一上限 + SBAS 选择对齐（2026-11-05）

**日期**: 2026-11-05
**类别**: rtklib-core / 星历选择（JAVA-ONLY）

### 问题

`EphModel` 曾用 `rtklib.eph.wideage` 开关把星历时效放宽到 7 天，用于"星历稀疏时也能出解"。
副作用：跨日/跨周的旧星历被当作有效星历参与解算，位置误差可达米~十米级，
且与 RTKLIB `seleph()` 语义不一致（C 版按系统分档 2~8 h）；SBAS 分支「首个命中即返回」
也不比较 `t0` 远近。

### 修复

1. 移除 `wideage` 开关与 7 天统一上限，按系统分档取 `Constants.MAXDTOE_*`：
   GPS/GAL/QZS/SBS/GLO = 2 h、BDS = 6 h、IRN = 8 h。
2. 与 `seleph()` / `selgeph()` 对齐：GAL 与 GLO 不加 1.0 s 余量，GPS/QZS/BDS/IRN 加 1.0 s（容纳 `toc` 与 `toe` 差）；SBS 由 `MAXDTOE_SBS` 单独处理。
3. SBAS 改为「`t0` 最近且 ≤ `MAXDTOE_SBS`」，不再首个命中即返回。

### 影响与适配

- 装订过期的 BRDC 文件不再产生"能出解但米级误差"的假解；
  `RinexSppTest` 的稀疏星历场景改为按**可用星数最多**的历元做 SPP 验证
  （原固定历元在时效收紧后星数不足，会误判为无解）。

### 修改文件

- `rtklib-core/.../ephemeris/EphModel.java`
- `rtklib-core/src/test/java/org/rtklib/java/RinexSppTest.java`

---

## FIX-ALG-048 / joint 闸3 骨架网 FIX 恒为 0：状态判断反向 + 参考长度被 FLOAT 污染（2026-11-05）

**日期**: 2026-11-05
**类别**: rtklib-joint / 闸3 基站骨架网（JAVA-ONLY）
**触发**: `JointRtkRealDataTest#testBaseSkeletonNetwork` 断言 `fixes > 0` 失败
（真实 2 基站 + 1 测站，235 历元，`baseSkeletonNetwork=true`）。

### 根因

`BaseSkeletonNetwork.solve()` 的参考长度标定分支用 `stat < Constants.SOLQ_FLOAT` 判「非 FIX」，
但 `SOLQ_FIX = 1`、`SOLQ_FLOAT = 2`，FIX 解恰好满足该条件被 `return` 丢弃 —— 235 历元
无一次进入样本，`getFixSamples() = 0`，`refLen` 永远标定不出来，骨架网等于没工作。

次生缺陷：同一分支曾把**未收敛的 FLOAT 解**也当样本采信，参考长度中位值因此带 1.7 cm 偏差，
使后续 `|Δlen|` 判据长期偏置。

### 修复

- 参考长度**只接受 `SOLQ_FIX`**：`if (stat != Constants.SOLQ_FIX) return;` 后再入样本，
  `refLen = median(前 10 个 FIX 解)`。
- 门限放行 `FIX/FLOAT` 进入长度变化监测（FLOAT 未收敛时 σ 大，由 `max(5 cm, 3σ)` 兜住，
  不产生误报）。

### 验证（235 历元）

| 项 | 修复前 | 修复后 |
|---|---|---|
| `fixSamples` | 0 | **215/235（91.5%）** |
| `refLen` | 未标定（0） | **587.302 m**，与两站坐标几何长度一致到 1 mm |
| 位置 σ | 0.22 m | 0.0075 m |
| `maxChange` | — | < 5 cm，`isSuspect(0/1)=false`（健康小时无误报） |

- 反向佐证坐标来源：骨架长度与坐标几何值毫米级一致，说明基站 B 的 1005/1006 站坐标可信；
  而基站 A 的 1005 坐标偏 4.7 m（另一处理中已确认），因此"1.7 cm 基线长度偏差"
  不是坐标问题，是解算收敛性问题（即本条目）。
- 设计意图回顾：骨架解只把基站间静态基线当作独立位移观测量，用 FIX 解标定参考长度，
  以排除 rover 侧异常干扰——因此参考长度必须来自 FIX 解，这是本 bug 的判据来源。

### 修改文件

- `rtklib-joint/.../BaseSkeletonNetwork.java`

---

## FIX-ALG-049 / 窄巷固定率 69.4%→80.6%：周跳切弧对正常缺测过敏（2026-11-06）

**日期**: 2026-11-06
**类别**: rtklib-networkvrs / 网解窗口窄巷 AR（JAVA-ONLY，rtklib-core 零改动）
**触发**: 2026-11-02 私有四站（站号以 `<DEVICE_A>`/`<DEVICE_B>`/`<DEVICE_C>`/`<DEVICE_D>` 代）端到端验证，
`NetworkSsrEndToEndTest` 输出 `nlFixed=25/36 (69.4%)`，多个基线窄巷 ratio 只有 1.0~2.3，
而浮点解 RMS 仅 7.5 mm——残差质量与固定率严重不匹配，说明问题不在观测精度。

### 根因

`BaselineWindowSolver` 的周跳检测把**弧段内相邻历元的时间间隔**也当作失锁判据：

```java
slip = dt > 1.5 * sampling || jump > config.cycleSlipThresholdMeters;
```

30 s 采样下，只要窗口内缺 1 个历元，相邻历元间隔就变成 2×30 s > 1.5×30 s，被误判为周跳。
实测四站 1 h 窗口共触发 63 次切弧，逐条打印后确认**全部是正常缺测间隔，无一次真实相位阶跃**。
后果是每个窗口的长弧被切成若干 3~8 历元的短弧：

- 短弧的 IF 模糊度只有一个参数但观测太少，σ 从 0.1 周量级膨胀到 **2~6 周**；
- LAMBDA 输入协方差被撑大，ratio 直接塌到噪声底（1.0~2.3），窄巷必然固定失败；
- 因为切弧只发生在少数历元，浮点 RMS 几乎不受影响，指标上表现为「残差很好但固定率低」。

### 修复

把间隙判据从硬编码 1.5× 改为可配置的 `NetworkConfig.maxArcGapFactor`（默认 3.0）：

```java
boolean gapSlip = dt > config.maxArcGapFactor * sampling;
boolean jumpSlip = jump > config.cycleSlipThresholdMeters;
```

- 3.0× 采样间隔意味着 30 s 采样可容忍**连续缺 2 个历元**仍算同一弧段；
- 缺测历元本身不代表相位失锁（相位在失锁时会重新计数并由 LLI 标记），放宽间隙不会放过真周跳；
- 真正的周跳仍由 `cycleSlipThresholdMeters = 0.05 m` 的几何阶跃判据负责，且该判据在间隙放宽后
  **仍然生效**（原实现中 `dt` 超限会短路，jump 判据实际被旁路）。

#### 记录口径更正（2026-11-06 复核）：3.0 不是「调出来的最优倍数」

把 1.5× 失效归因于「倍数太小」，会让人以为 3.0 是在同一尺度上试出来的最优值——这个理解是错的，
后续照此调倍数会重蹈覆辙。真实成因有两层：

- **直接层**：1.5 × 30 s = 45 s < 60 s，而缺 1 个历元后的间隔恰为 60 s。1.5× 在任何尺度下都无法
  容忍缺 1 个历元（需 ≥2.0×），这一层确实是倍数不足。
- **根本层**：判据的尺度 `sampling` 当时取「窗口前 20 个相邻间隔的均值」，而**缺测正是该判据要抓的对象**。
  均值被缺测拉长，缺测越多抬得越高，阈值随之放宽，形成「数据越稀 → 判据越松 → 漏判越多」的正反馈。
  于是倍数乘在一个随数据漂移的尺度上：同一倍数在不同窗口给出不同阈值，任何倍数都不可解释，
  也无法稳定标定。3.0 的「最优」是这次测量条件的产物，不是倍数的性质。

因此正确口径是：**换掉的是尺度，不是倍数**。尺度改为全窗下中位数后（见下节），
`factor × 标称间隔` 的语义才确定下来——**允许连续缺 factor−1 个历元**，
30 s 采样下 3.0 即 90 s（允许缺 2 个历元），且随采样率自适应（1 Hz 下 3.0 即 3 s，同样是缺 2 个历元）。

### 尺度更正：采样间隔改取全窗下中位数（2026-11-06）

`BaselineWindowSolver.samplingIntervalSeconds` 由「前 20 个相邻间隔均值」改为
「全窗相邻间隔剔除非正后的**下中位数**」：

- **抗离群**：缺测拉长的个别间隔不参与决定阈值，估计量与被判对象不再共享同一失效来源，上述正反馈被拆除；
- **恒为真实观测值**：间隔量化到采样间隔的整数倍，偶数样本的插值中位数会给出 45 s 这类不存在的值，
  下中位数不会；
- **扫全窗而非只看前 20 个**：中位数的鲁棒性不依赖样本量，而窗口起点若紧接缺测段或采样率中途切换，
  开头那一小段代表不了整窗。

判据语义不变（30 s 标称下阈值仍是 90 s），零 API 变更。实测（2026-11-06，240 历元、doy 300、
CIMO/PRAT/VIRG 三基线）：

| 窗口 | 窗口数 | 新旧实现差异 |
|---|---|---|
| 20 历元 | 36 | 逐窗口 `fixed`/`ratio`/RMS/δ/`gapCuts`/`jumpCuts` **全部一致** |
| 40 历元 | 18 | 同上 |

- 该批数据 `gapCuts ≡ 0`（窗口内历元连续，无跨历元空档），尺度改动不影响结果。均值版在此数据上
  同样是 `gapCuts ≡ 0`：均值 ≈30~33 s、阈值 ≈90~100 s，与下中位数的 90 s 只差几秒。
- 故这一步是**拆掉自掩蔽反馈，不是修一个正在发生的偏差**。差异只在缺测占比显著
  （阈值被抬过 2× 采样，即缺测占比约超过 1/3）时才显形，典型场景是 1 Hz 且频繁丢帧。
- 回归护栏：`BaselineWindowSolverTest#testSamplingIntervalUsesMedianNotMean`（11 历元、10 个间隔
  交替 30 s / 120 s）。均值 75 s → 阈值 225 s → `gapCuts=0`（5 个超限间隔全部漏判）；
  下中位数 30 s → 阈值 90 s → `gapCuts=25`（5 卫星 × 5 间隔全部切弧）。该用例在旧实现下为红，
  能区分两种估计量，不是恒真的假护栏。

### 验证（2026-11-02 四站，SSR 改正）

**40 历元窗口**（`-Dnetwork.window.epochs=40`，36 窗口 = 6 基线 × 6 窗口）：

| 项 | 修复前（1.5×） | 修复后（3.0×） |
|---|---|---|
| 窄巷固定窗口 | 25/36（69.4%） | **29/36（80.6%）** |
| 平均 ratio | 5.38 | **6.04** |
| 最小 ratio | — | 1.08 |
| 相位 RMS | 0.0075 m | 0.0075 m（不变） |
| 基线重复性 | 0.180 m | **0.160 m** |
| 误切弧次数 | 63（全为缺测间隙） | **1**（1 次 0.051 m 真实阶跃，刚过 0.05 m 门限） |

**20 历元窗口**（默认 `network.window.epochs=20`，72 窗口 = 6 基线 × 12 窗口）：

| 项 | 修复前（1.5×） | 修复后（3.0×） |
|---|---|---|
| 窄巷固定窗口 | 25/72（34.7%） | **27/72（37.5%）** |
| 平均 ratio | 4.04 | 4.08 |
| 基线重复性 | 0.552 m | 0.579 m |

> 表中「修复前/后」的 `sampling` 均为**均值口径**（下中位数改动晚于该次测量）。该批数据缺测占比不高，
> 均值 ≈30~33 s，故增益基本等价于「1.5× → 3.0×」带来的容忍度变化（0 → 2 个历元），尺度漂移未主导；
> 缺测密集时均值口径会让同一倍数给出不同阈值，这是后续改取下中位数的动机。

- 40 历元窗口收益显著且无任何指标劣化；20 历元窗口仅小幅提升，说明**短窗口的瓶颈不在切弧**，
  属另一类问题，方向性判断见下节（已量测，非推测）。
- 仍固定失败的窗口集中在 `<DEVICE_C>-<DEVICE_D>` 基线（重复性 0.55 m），对应 C 站
  已知的 4.7 m 坐标偏差（1005 站坐标错误，属数据侧问题，不属本条目）；
  `lenEst - lenApri` 在含 A/B/C 的基线上达 3~6 m，同样由站坐标偏差解释，
  而窗口间重复性（0.02~0.08 m）说明解算本身稳定。
- `rtklib-core` 全程未改动，符合「core 已封版」约束。

### 20 历元窗口为何只有 37.5%：窗口太短，不是卫星数不足

**结论**：20 历元（10 min）窗口的瓶颈是**「位置—模糊度」不可分离**（需要更长的几何变化或外部约束），
**不是**该窗口下可用卫星数不足。依据是同一数据、同一 SSR 源、只改 `network.window.epochs` 的对照：

| 按卫星数分组 | 20 历元 | 40 历元 |
|---|---|---|
| nSat=6 | 6/19 固定（32%），meanMaxRatio 6.89 | 6/7（86%），5.47 |
| nSat=7 | 15/32（47%），3.54 | 19/23（**83%**），**6.97** |
| nSat=8 | 3/4（75%），3.10 | 4/5（80%），4.53 |
| 卫星数分布 | 4~8，均值 6.4 | 4~8，均值 7.0 |

- **卫星数几乎没变**（两档都落在 4~8，均值只差 0.6 颗），同是 7 颗星的窗口固定率却从 47% 跳到 83%、
  meanMaxRatio 从 3.54 到 6.97 → 增益来自窗口长度，不是多星。
- **冗余本来就充足**：nSat=7 时 7 个双差/历元 × 20 历元 = 140 个观测，未知数 3 位置 + 4 ZTD
  + 7 模糊度 = 14，冗余 126。「观测数不够」不成立。
- **精度指标随窗口长度缩放，而非随卫星数缩放**：

  | 指标 | 20 历元 | 40 历元 |
  |---|---|---|
  | σ(N1) 中位（周） | 1.7 | 0.9 |
  | 位置 σ（ECEF 三分量） | 0.56 / 0.70 / 0.38 m | 0.24 / 0.27 / 0.16 m |
  | 窗口间重复性 | 0.579 m | 0.160 m |

  模糊度 σ 与位置 σ 都近似按「窗口长度加倍 → 减半」缩放。原因是解耦量正比于弧长内的卫星几何变化：
  10 min 内 BDS/GPS 高度角只变约 2.5°，位置与 IF 模糊度相关系数接近 1，浮点模糊度必然停在 1~2 周，
  ratio 到不了 3.0。40 历元把几何变化翻倍，σ 掉一半，ratio 才回到 6 以上。

**可操作方向（按优先级）**

1. **加长窗口**：40 历元（20 min）已验证 80.6%，代价是固定延迟由 10 min 涨到 20 min；
2. **级联/滚动窗口**（保 10 min 延迟的替代路线）：用前一窗口的位置解作弱先验（σ≈0.1~0.3 m），
   人为补上 10 min 内缺失的几何解耦。属"外部约束"路线，**尚未实现、未测**；
3. **少星窗口不做 AR**：20 历元下 12 个 nSat=5 窗口只固定 2 个；且低维 ratio 会虚高
   （nSat=4 的窗口 meanMaxRatio 7.28 却只固定 1/3），少星窗口的 ratio 不能当可信指标。

**未验证**：方向 2 的级联窗口未实现；方向 3 跳过 AR 的收益未量化。

### 已试并否决 / 评估后不做的方案（避免后续重复试错）

**间隙判据改为绝对秒数 `maxArcGapSeconds` —— 评估后不做，正式关闭（2026-11-06）**

| 项 | 内容 |
|---|---|
| 状态 | **关闭，不实施**。`NetworkConfig` 不新增该字段，`maxArcGapFactor × sampling` 保持唯一口径 |
| 提出 | 在 `NetworkConfig` 加 `maxArcGapSeconds`（默认 90），`maxArcGapFactor × sampling` 退为缺省回退 |
| 评估方式 | 桌面推演（未实现、未测；判据的速率敏感性可由算术直接判定，无需实测） |
| 结论 | 负收益，且与已落地的下中位数修复方向相反 |

关闭理由：

1. **绝对秒数在非 30 s 采样下必然错**。90 s 只在 30 s 采样下等于「允许缺 2 个历元」。设成绝对默认后，
   1 Hz 数据获得 90 s 容忍度 = 连续缺 89 个历元仍算同一弧段，按可缺历元数算比正确值松约 45 倍；
   而 `factor × sampling` 天然速率自适应（1 Hz 下 3.0 即 3 s，仍是缺 2 个历元）。
2. **它修的不是判据正确性，只是可读性**。尺度改取下中位数后，`factor × 标称间隔` 的语义已经确定
   （允许连续缺 factor−1 个历元），阈值不再随数据漂移。此时把口径换成绝对秒数，
   收益仅是「配置项名字更直白」，代价是公开配置面变更 + 引入上述速率不敏感缺陷。
3. **配置化本身会制造新的自掩蔽通道**。一旦有了 `maxArcGapSeconds`，运维看到误切弧会倾向直接调大它，
   等于绕过「尺度必须是真实标称间隔」这条约束，把已经拆掉的正反馈从配置面重新打开。

替代它的做法（已落地，本条目「记录口径更正」与「尺度更正」两节）：把「1.5× 失效的根因是倍数乘在
有偏尺度上，不是倍数本身」写进文档，让后人理解 3.0 的语义来源，而不是把它当成调出来的最优倍数。
文档解释替代配置开关，是本条决策的核心。

**重开条件**（任一条成立才重新评估）：① 出现采样率在窗口内大幅抖动、标称间隔本身无意义的场景
（如事件触发采样）；② 需要按数据源（而非按采样率）设定硬性上限。若重开，实现方式必须是
**上限截断** `min(maxArcGapSeconds, factor × sampling)`（0 表示不启用），而不是替换缺省口径——
下限仍由标称间隔保证，绝不能让绝对秒数独自决定阈值。

**按仰角加权**：把法方程里的等权改成 `σ(el) = σ0 / sin(el)`，相位行与码行**同时**加权；
仰角取 `min(卫星仰角, 参考星仰角)`，下限 10°（低于 10° 按 10° 计，避免 sin 趋零发散）。
改动点仅 `BaselineWindowSolver` 的两处 `accumulate(..., 1/σ²)` 权重，未动其他逻辑。

**实验条件**（数据不全结论就无意义，故逐项记明）：

| 项 | 取值 |
|---|---|
| 数据集 | 2026-11-02 私有四站（`<DEVICE_A>`~`<DEVICE_D>`） |
| 改正源 | 录制 SSR（`EPHOPT_SSRAPC`），与上文主实验同一数据 |
| 窗口长度 | 默认 20 历元（`network.window.epochs=20`，即 10 min） |
| 窗口总数 | 72（6 基线 × 12 窗口） |
| 对照 | 同一数据、同一窗口配置下的等权版本 |

| 指标 | 等权（保留） | 仰角加权（否决） |
|---|---|---|
| 窄巷固定窗口 | 27/72（37.5%） | **21/72（29.2%）** |
| 平均 ratio | 4.08 | **2.49** |
| 最小 ratio | 0.00 | 0.00 |
| 相位 RMS | 0.0071 m | 0.0074 m（基本不变） |
| 基线重复性 | 0.579 m | **1.018 m** |

- 逐基线看劣化最重的是 `B-D`：fix 3→1、ratio 2.49→1.78、重复性 0.0297 m→NaN
  （仅 1 个固定窗口，无法算重复性）；`A-D` fix 5→2、ratio 2.77→1.78。
- 判读：相位 RMS 几乎不变（0.0071→0.0074 m）但 ratio 掉 40%，说明**不是观测拟合变差，
  而是模糊度协方差的相对结构被破坏**——低仰角卫星的 σ 被放大后成了 LAMBDA 协方差的主导
  方向，几何约束被稀释，这是与「残差好但固定率低」同一类症状的另一种成因。
- **未验证的条件（后人若要重试请先补这两项）**：① 40 历元窗口下加权是否同样劣化未测；
  ② 只对相位行加权、码行保持等权未测。故本结论的适用范围严格限于上表条件。
- 当前代码保持等权，未保留任何加权分支。

**加码伪距权重**：把 `codeSigmaM` 由 1.5 收紧到 0.3（单频码 σ，IF 后由 4.5 m → 0.9 m），
其余不变，20 历元窗口：

| 指标 | `codeSigmaM=1.5`（保留） | `codeSigmaM=0.3`（否决） |
|---|---|---|
| 窄巷固定窗口 | 27/72（37.5%） | **10/72（13.9%）** |
| 平均 ratio | 4.08 | **2.10** |
| 相位 RMS | 0.0071 m | **0.0106 m** |
| 基线重复性 | 0.579 m | **1.632 m** |

- 判读：固定率、ratio、重复性三项同时劣化，相位 RMS 还涨了 49%，说明码的多路径底噪在**米级**，
  把它当 0.9 m 精度用会把位置拉离相位一致的解。**伪距权重已到上限，无法用来锚定短窗口的位置**，
  这反过来印证上节结论：10 min 窗口只能靠几何随时间变化去解耦位置与模糊度。
- 当前 `codeSigmaM` 保持 1.5，未改动。

### 修改文件

- `rtklib-networkvrs/.../netsol/BaselineWindowSolver.java`
- `rtklib-networkvrs/.../config/NetworkConfig.java`

### 归档（非本次修复）

`rtklib-test` 的 `RtkValidationTest#testShortBaselineFixRateAndRms` 短基线固定率 8.06%
失败已用 `git stash` 在 HEAD 上复现，属既有问题，与 core 封版内容无关，单独列入待办，
不在本轮端到端验证闭环范围内。

---

## FIX-ALG-050 / ddres 对流层项按 C 结构重写：逐星预计算 + 两站偏导分列（2026-11-10）

**日期**: 2026-11-10
**类别**: rtklib-core / RTK 双差残差（C 对齐）
**触发**: 短基线 HKWS-HKSS 2024-060（约 6.8 km，2880 历元）固定率 0.07%（C 原版 73.78%）
逐历元对照排查，ddres 的对流层项与 C `rtkpos.c ddres()` 结构不一致。

### C 原版逻辑（rtkpos.c ddres）

```c
/* 卫星循环之前：逐星预计算两站对流层延迟及其对状态向量的偏导（每星 3 个分量） */
for (i=0;i<ns;i++) {
    if (opt->tropopt>=TROPOPT_EST) {
        tropu[i]=prectrop(rtk->sol,posu,0,azel+iu[i]*2,dtdxu+i*3,opt);
        tropr[i]=prectrop(rtk->sol,posr,1,azel+ir[i]*2,dtdxr+i*3,opt);
    }
}
/* 卫星循环内（非参考星、通过 validobs、频率有效） */
v[nv]-=(tropu[ref]-tropu[j])-(tropr[ref]-tropr[j]);
for (k=0;k<NT(opt);k++) {
    H[nv+nx*IT(0,opt)+k]= dtdxu[k+3*ref]-dtdxu[k+3*j];
    H[nv+nx*IT(1,opt)+k]=-(dtdxr[k+3*ref]-dtdxr[k+3*j]);
}
```

### Java 侧原实现的三处偏差

原实现把 `prectrop` 放进卫星循环、按双差对逐次调用，且**只取偏导、不修正残差**：

```java
double[] dtdx_m = new double[nx];
double[] dtdx_r = new double[nx];
prectrop(..., rr_f, 0, ..., dtdx_m, nx);   // 车侧
prectrop(..., rr_r, 1, ..., dtdx_r, nx);   // 基站侧
for (k = 0; k < nx; k++) H[nv * nx + k] += dtdx_m[k] - dtdx_r[k];
```

1. **v 未扣除对流层双差**：`v[nv]-=...` 整项缺失，天顶对流层延迟只能由模糊度状态吸收，
   在短基线上表现为浮点模糊度被系统性污染；
2. **偏导按整个状态向量累加**：用长度 nx 的临时数组逐元素 `+=`，而不是按 `IT(0)/IT(1)`
   定位的 `NT(opt)` 个分量**赋值**，两站对流层参数被写进同一段索引；
3. **每对卫星重复调用 2 次 `prectrop`**（`ns×(ns−1)×2` 次），C 为 `2×ns` 次。

### 修复

按 C 结构重排：卫星循环前对 `ns` 颗星各调一次 `prectrop`（车/基各一次），
缓存 `tropu[]/tropr[]`（延迟）与 `dtdxu[]/dtdxr[]`（每星 3 分量偏导），
循环内按 C 的两行公式修正 v 并写 H（`nt = tropopt < TROPOPT_ESTG ? 1 : 3`）：

```java
v[nv] -= (tropu[refIdx] - tropu[j]) - (tropr[refIdx] - tropr[j]);
int nt = (opt.tropopt < Constants.TROPOPT_ESTG) ? 1 : 3;
for (k = 0; k < nt; k++) {
    H[nv * nx + IT(0, opt) + k] =   dtdxu[k + refIdx * 3] - dtdxu[k + j * 3];
    H[nv * nx + IT(1, opt) + k] = -(dtdxr[k + refIdx * 3] - dtdxr[k + j * 3]);
}
```

### 验证

与 FIX-ALG-051 在同一工作区、同一次对照中一并生效（2880 历元逐历元比对），
**未做单独 A/B**，故本条目不带独立增益数字。结构性证据：本条只影响 v/H 的对流层项
（量级 cm，且 `TROPOPT_EST` 下由 IT(0)/IT(1) 两个状态承担），而基线固定率从 0.07% 到
73.82% 的跳变形态由 FIX-ALG-051 的 holdamb 链路解释（ep28 跨周跳变消失）。

### 修改文件

- `rtklib-core/.../rtkpos/RtkCore.java`（`ddres`）

---

## FIX-ALG-051 / RTK 短基线固定率 0.07%→73.8%：Kalman 状态压缩多加了 H 全零列过滤（holdamb 死亡螺旋）（2026-11-10）

**日期**: 2026-11-10
**类别**: rtklib-core / Kalman 滤波状态压缩（C 对齐 + 作用域收敛）
**触发**: 短基线 HKWS-HKSS 2024-060（约 6.8 km，2880 历元、30 s 采样）Java 固定率 0.07%，
C 原版（rtklibexplorer demo5，RTKLIB-2.5.0，`VER_RTKLIB "EX"`）73.78%。

### 排查路径（先切分问题域，再定位）

1. **AR 入口**：首历元 Java `nb=26`、`ratio=13.216`，与 C 的 `nb=26`、`ratio=12.81`、
   `s[0]/s[1]=17.83/228.48`、`nu=22 nr=24 ns=16` 一致 → AR 之前无病。
2. **AR 之后**：`holdamb` 在整段数据只被调用 21 次（正常应接近逐历元），
   逐历元 trace 显示 `ep28` 的 `maxV` 跳到 **2.1154 周**（跨整周），此后 LAMBDA 失败、
   `nb` 由 30 降到 28，进入「卫星被逐个排除」的死亡螺旋。
3. **唯一可追溯差异**：`KalmanFilter.update` 的状态压缩条件比 C 多一条 H 列非零检查。

### 根因

C `rtkcmn.c filter()` 的状态压缩只有两条判据：

```c
for (i=k=0;i<n;i++) {
    if (x[i]!=0.0&&P[i+i*n]>0.0) ix[k++]=i;
}
```

Java `KalmanFilter.update` 额外加了「H 中该列必须非零」，否则跳过该状态
（这是 FIX-ALG-042 P4 为 joint 双基线防跨基线污染引入的优化）。

对 `holdamb` 的伪观测而言，这个过滤是致命的：

- H 只在已固定卫星的模糊度列上有 ±1，**位置、速度、对流层等状态列全为零**；
- 这些状态虽然不被本条观测直接约束，但与模糊度有**非零交叉协方差** `Pc`；
  C 版把它们一起纳入压缩子空间，更新后 `Pc` 参与增益计算，位置/对流层随模糊度
  被一并修正，模糊度与位置保持自洽；
- Java 把它们排除后，只更新模糊度对角块 → 模糊度被「钉住」但位置没有跟着修正，
  载波残差每历元把位置误差渗回模糊度状态，`maxV` 从 ep20 的 0.53 单调涨到 ep27 的 0.79，
  ep28 越过整周触发周跳判定 → 固定失败 → 卫星排除 → 无法恢复。

注意：模糊度**稳态残留**（`varholdamb=0.1` 下约 0.13 周）本身不是根因，C 同样存在；
根因是位置没被同步修正，使残差单向累积而不是稳态收敛。

### 修复

1. `KalmanFilter.update` 新增带 `skipZeroHCols` 的重载，**默认 `true`（保持既有行为）**，
   旧 7 参签名委托到 `true`，joint 双基线路径完全不受影响：

```java
public static int update(double[] x, double[] P, double[] H, double[] v,
                         double[] R, int nx, int nv) {
    return update(x, P, H, v, R, nx, nv, true);
}
public static int update(double[] x, double[] P, double[] H, double[] v,
                         double[] R, int nx, int nv, boolean skipZeroHCols) { ... }
```

2. RTK 主流程的两处调用显式传 `false`，严格对齐 C `filter()` 的压缩规则：
   - `RtkCore.filter()`（`relpos` 的量测更新）
   - `RtkCore.holdamb()`（固定模糊度的伪观测更新）

### 验证（HKWS-HKSS 2024-060，2880 历元，基线配置、新增优化全关）

| 指标 | 修复前 | 修复后 | C 原版 |
|---|---|---|---|
| 固定率 | 0.07%（2/2880） | **73.82%（2126/2880）** | 73.78%（2125/2880） |
| Q 分布 fix/float | 2/2878 | **2126/754** | 2125/755 |
| 两端同为 FIX | — | **2122** | — |
| Java FIX / C 非 FIX | — | 4 | — |
| C FIX / Java 非 FIX | — | 3 | — |
| `holdamb` 调用次数（前 200 历元） | 21（ep28 后基本不再触发） | **181** | — |
| `holdamb` maxV 最大值 | 2.1154 周（ep28 跨周） | **0.5339 周（ep20，无跨周）** | ~0.5 周 |
| ep29 状态 | FLOAT，nb 30→28 | **FIX，nb=30** | FIX |
| ep44 nb | 28（卫星已排除） | **30 并持续增长（→32/34/36）** | — |
| 同为 FIX 平均差 dE/dN/dU | — | **−0.0169/0.0115/−0.0299 m** | — |
| 水平差 >0.1 m 的历元 | — | **0** | — |
| 最大单历元 \|dE\| | — | 0.023 m @ ep248 | — |

逐历元 ratio 同步对齐（ep1 13.216 vs C 12.8、ep11 3.591 vs 3.4、ep28 之后不再塌陷），
`maxV` 曲线由「单调发散」恢复为「逐历元衰减的稳态残留」
（ep20 0.5339 → ep28 0.1025 → ep44 0.0653 → ep59 0.0889 → ep200 0.1260，全程无跨周）。

已知残留（与解算无关）：`.pos` 时间标签在闰秒边界仍有 `23:59:60.000000`/`02:03:60.000000`
的显示差异——C 的 `time2epoch_n()` 会在 `1.0-t.sec<0.5/10^n` 时进位到下一秒，
Java 侧暂无对应的 `time2epoch_n` 实现。该差异只影响文本输出，不进入解算，
对照工具已改为按历元序号（index）匹配，故不干扰本表的结论。

### 作用域说明（与 FIX-ALG-042 P4 的关系）

FIX-ALG-042 P4 的 H 列过滤是 **joint 双基线**的必要保护：单基线 update 中另一基线的
模糊度列在 H 中全零，若不跳过，新息会通过 P 的交叉协方差泄漏到共享位置状态
（实测 epoch=0 位置跳 71 m）。本条目不是回退该保护，而是把它**限定在 joint 路径**：
- 默认重载保持 `skipZeroHCols=true` → joint 行为逐位不变；
- 单基线 RTK 两处调用显式 `false` → 与 C 逐位一致。

### 修改文件

- `rtklib-core/.../kalman/KalmanFilter.java`（新增重载，默认行为不变）
- `rtklib-core/.../rtkpos/RtkCore.java`（`filter()`、`holdamb()` 传 `false`）

---

## FIX-ALG-052 / TimeSystem.MDAY 四年周期闰年位置错位（2026-11-10）

**日期**: 2026-11-10
**类别**: rtklib-core / 时间系统（C 对齐）
**触发**: 时间系统逐项核对 `rtkcmn.c` 时发现 `MDAY` 表与 C 的 4 年周期排列不一致。

### 根因

C `time2epoch()` 用一张 48 项的 `mday` 表覆盖 1461 天的 4 年周期（1970-1973）：

```c
31,28,...,31,  31,28,...,31,  31,29,...,31,  31,28,...,31
   ↑ 1970          ↑ 1971        ↑ 1972(闰)      ↑ 1973
```

Java `MDAY` 把 29 天（闰年二月）放在了**第二个 12 项块**（对应周期内第 1 年），
即 1971 年按闰年算、1972 年按平年算。由于 `mon` 在 0..47 上累减，
块序错位使**周期内第 2 年的 3 月以后全部日期偏移一天**——1970 起每 4 年一个闰年
（1970 % 4 = 2，故闰年恒落在块 2），2020、2024 等年份的 3 月 1 日之后日期都会被算早一天。

### 修复

把第三个块改为 29 天，与 C 的 `mday` 表逐项一致：

```java
private static final int[] MDAY = {
    31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31,   // 1970
    31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31,   // 1971
    31, 29, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31,   // 1972（闰）
    31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31    // 1973
};
```

`epoch2time()` 侧的闰年判据 `(year % 4 == 0 && mon >= 3)` 本来就与 C 一致，
只有 `time2epoch()` 反算受影响，故修复后正反变换在闰年 3 月以后自洽。

### 验证

静态逐项对照 C `mday` 表；不影响 `GTime` 内部秒计数与解算数值，只影响日历字段输出
（`.pos` 时间标签、RINEX 写出）。未做数值回归（该项不改变滤波行为）。

### 修改文件

- `rtklib-core/.../time/TimeSystem.java`

---

## FIX-ALG-053 / pppos 迭代起点对齐 C：xp/Pp 只在循环前拷贝一次（2026-11-10）

**日期**: 2026-11-10
**类别**: rtklib-core / PPP 迭代（C 对齐）
**触发**: 逐行核对 `ppp.c pppos()` 与 Java `PppCore.pppos` 的迭代结构。

### 根因

C 在进入迭代循环**之前**拷贝一次状态，之后每次迭代都在上一次滤波结果上继续精化：

```c
matcpy(xp,rtk->x,rtk->nx,1);
matcpy(Pp,rtk->P,rtk->nx,rtk->nx);
for (i=0;i<MAX_ITER;i++) { ... ppp_res -> filter -> ppp_res(postfit) ... }
```

Java 把这两行拷贝放在了**循环体内部**，每次迭代都从未提交的 `rtk.x/rtk.P` 重新起步，
迭代之间相互独立，等于把「迭代精化」退化成「同一初值重复求解」：

- 第 2 次迭代的线性化中心没有用到第 1 次更新的结果，收敛速度与收敛判据都失去意义；
- 每次迭代的 `filter` 都基于同一份先验，若第 1 次被 postfit 拒绝，后续迭代只是重复同样的失败。

### 修复

把两次 `System.arraycopy` 移到 `for (iter...)` 之前，与 C 的语句位置一致；
循环内仍保留 `xp/Pp` 的原地更新与 postfit 通过后的提交逻辑（不变）。

### 验证

静态对照 C `ppp.c L1219-1238`；本次未单独跑 PPP 数据集 A/B（该改动属结构对齐，
后续 PPP 回归任务中一并验证）。

### 修改文件

- `rtklib-core/.../ppp/PppCore.java`

---

## FIX-ALG-054 / PPP-RTK 固定解永久锁存 + 固定解坐标不更新：sol.stat 逐历元重置 + updateStat 增加 FIX 分支（2026-11-11）

**日期**: 2026-11-11
**类别**: rtklib-core / PPP-RTK 解状态（`PppRtkCore.updateStat`）
**触发**: UTC00–15 全窗口回放中，UTC05 起 `PPP-AR: fixed` 行已归零，但 `sol.stat` 全程输出
`1`(FIX)，全窗口固定率 99.5%（3800/3820）与真实 NL 固定数 454 严重不符。

### 根因（`updateStat` 的 SOLQ_FIX 守卫）

`updateStat` 原逻辑（`git show HEAD` 原文）：

```java
// FIX-ALG-040: preserve SOLQ_FIX from AR resolution.
if (rtk.sol.stat != Constants.SOLQ_FIX) {
    rtk.sol.stat = (byte)(rtk.sol.ns < MIN_NSAT_SOL ? Constants.SOLQ_NONE : stat);
}

for (int i = 0; i < 3; i++) {          /* ← rr/qr 本来就在守卫之外，每历元都更新 */
    rtk.sol.rr[i] = rtk.x[i];
    rtk.sol.qr[i] = (float) rtk.P[i * nx + i];
}
```

**FIX 标签永久锁存**：AR 首次成功把 `rtk.sol.stat` 写成 `SOLQ_FIX` 后，此后每个历元该守卫
恒为假 → `sol.stat` 再也不更新，上一历元的 FIX 标签被永久沿用。后果：
- `MIN_NSAT_SOL=4` 的降级判断对 FIX 完全失效（ns=2 的历元照样输出 FIX）；
- 后续历元 AR 全部失败，也照样记为 FIX（UTC04 起 NL 固定 0，stat 仍全程 1）。

**关于坐标覆盖（原判断已更正）**：经核对 `git show HEAD` 与全窗口日志（`run_full.txt` 中
3795 个历元的 `sol.rr` 各不相同），旧代码的 `rr/qr` 从未被冻结，每历元都从 `x/P` 更新，
所以「FIX 坐标回退成纯浮点」在本数据上并未实际发生。本次新增的 `arFixed` 分支属于
**对齐 `RtkCore` 的结构性补强**（FIX 时从 `xa/Pa` 取），在 IFLC 下 `xa[0..2] ≡ x[0..2]`、
`Pa ≡ P`，数值上恒等（`xaDelta` 全程 0.000000），不改变结果，仅为后续「位置条件更新」
留出正确的取值入口。

### 修复

1. `ppprtkos` 入口（`rtk.epoch` 日志之前）无条件重置：

```java
rtk.sol.stat = Constants.SOLQ_NONE;
```

   把「本历元是否固定」交回本历元 AR 重新判定，保证历元之间状态独立。

2. `updateStat` 重写解状态与坐标赋值：
   - `arFixed = (rtk.sol.stat == SOLQ_FIX)`：只认**本历元** AR 写入的标签；
   - `rtk.sol.ns < MIN_NSAT_SOL` 时强制 `SOLQ_NONE`（FIX 同样降级，打印
     `updateStat: FIX downgraded to NONE`）；
   - `arFixed` 时 `rr/qr` 从 `xa/Pa` 取（对齐 `RtkCore` 的 `stat==SOLQ_FIX` 分支），
     否则从 `x/P` 取。IFLC 分支当前不做位置条件更新，`xa[0..2]` 恒等于 `x[0..2]`，
     本次只保证「结构上不再回退成纯浮点」，位置重解需另立条目。

### 验证（`PppRtkSsrReplay run -1 0 15`，全窗口 3820 历元）

逐小时 FIX 历元（修复前 → 修复后）：

| UTC | 修前 FIX | 修后 FIX | 修后 PPP | 修后 NONE | NL 固定(前→后) | 平均 ns |
|-----|---------|---------|---------|----------|----------------|---------|
| 00 | 220 | 77 | 158 | 5 | 80 → 77 | 9.8 |
| 01 | 238 | 135 | 103 | 0 | 135 → 135 | 5.6 |
| 02 | 240 | 105 | 135 | 0 | 159 → 105 | 6.1 |
| 03 | 239 | 85 | 154 | 0 | 119 → 85 | 6.2 |
| 04 | 236 | 52 | 184 | 0 | 0 → 52 | 6.3 |
| 05 | 239 | 0 | 231 | 8 | 0 → 0 | 5.5 |
| 06 | 238 | 0 | 34 | 204 | 0 → 0 | 2.2 |
| 07–15 | 238~241/时 | 0 | 0 | 全 NONE | 0 → 0 | 2.0~3.0 |

逐卫星数分桶固定率（修复后）：

| ns 区间 | 历元数 | FIX 历元 | 固定率 |
|---------|--------|----------|--------|
| ns<=3 | 2362 | 0 | 0.0% |
| ns4–5 | 474 | 98 | 20.7% |
| ns6–7 | 842 | 280 | 33.3% |
| ns8–9 | 42 | 0 | 0.0% |
| ns>=13 | 100 | 76 | 76.0% |

### 结论

- 全窗口固定率 99.5%（3800/3820）→ **11.9%（454/3820）**，与真实 NL 固定数 454 逐一吻合，
  锁存现象彻底消失；UTC05 起 NL 固定归零后 stat 正确降级为 `6`(PPP)/`0`(NONE)。
- 逐历元固定率随卫星数单调上升（ns4–5 20.7% → ns6–7 33.3% → ns≥13 76.0%），不再是一条直线。
- `xaDelta` 全程 0.000000，与 IFLC 分支不做位置条件更新的事实一致。

### 遗留（未在本次改动，需另立条目）

hour02/03 的 NL 固定数 159/119 → 105/85，hour04 由 0 → 52。已用**同编译产物的 A/B**
（`git stash` 只回退本文件，重编译后重跑 0–4）复现：

| UTC | 旧代码 NLfix | 新代码 NLfix | 旧 FIX | 新 FIX |
|-----|-------------|-------------|--------|--------|
| 00 | 80 | 77 | 220 | 77 |
| 01 | 135 | 135 | 238 | 135 |
| 02 | 159 | 105 | 240 | 105 |
| 03 | 119 | 85 | 239 | 85 |
| 04 | 0 | 52 | 236 | 52 |

两条轨迹在第 215 历元（UTC00:54:15）之前 `sol.rr` **逐位相同**，之后才分岔（`ns` 6 vs 7，
位置差 0.2 m 量级），说明滤波轨迹本身未被破坏，差异来自 `sol.stat` 唯一的下游消费者：

- **`PppProcessor:883` 的 SPP 重初始化分支**（`if (rtk.sol.stat == SOLQ_NONE)`）。
  修复前 stat 被锁存为 FIX，该分支 16 小时一次都没进过；修复后只要上一历元降级为 NONE，
  下一历元就会进这个分支。实测 UTC00 共进入 5 次（对应 5 个 NONE 历元），且**全部失败**
  （`sppInitOpt.navsys = SYS_GPS`，而本数据是 BDS-only，`SPP failed` 5 次）。
  但 `PntPos` 在失败前就已清写 `ssat.snrRover/azel/vs/resp/resc`，且把**存活的 `rtk.ssat`**
  当参数传入，而不是一份 scratch 数组，属于既有缺陷。
- 已核实**不是**配置项导致的差异：回放配置只打开了 `pppRtk.enable` / `pppRtk.enableAR` /
  `pppAr.enableAR`，`enableFixHold`（`PppRtkOpts`/`PppArOpts` 默认 false，日志
  `arFixHold=false`）、`pppAr.enablePartialAR`、`spp.enableEkf` 均为关闭；
  `PppRtkAmbFix` 的 IF 分支由 `opt.ionoopt == IONOOPT_IFLC` 选择，不是配置开关。
  因此 FIX-ALG-054 早期记录里「fix-and-hold 方差收紧历史」的推测**不成立，已作废**。
- 待办（FIX-ALG-055）：SPP 重初始化改用独立 scratch `Ssat[]`（或仅在 `rtk.nx == 0` 时执行），
  再做 A/B 确认 hour02/03/04 的 NL 固定数回到 159/119/0 以上。→ **已由 FIX-ALG-055 完成，
  实测 hour02/03/04 精确回到 159/119/0，且整条滤波轨迹与旧代码逐位一致。**

### 修改文件

- `rtklib-core/.../ppprtk/PppRtkCore.java`（`ppprtkos` 入口 + `updateStat`）

---

## FIX-ALG-055 / SPP 重初始化污染滤波器逐星状态：改用独立 scratch `Ssat[]`（2026-11-11）

**日期**: 2026-11-11
**类别**: rtklib-core / PPP 历元调度（`PppProcessor` 的 SPP 重初始化分支）
**触发**: FIX-ALG-054 修好 FIX 锁存后，`PppProcessor:883` 的 SPP 重初始化分支首次被放出来，
hour02/03/04 的 NL 固定数从 159/119/0 变成 105/85/52。A/B 显示两轨迹在第 215 历元前逐位相同、
之后分岔，差异只能来自 `sol.stat` 的唯一下游消费者（该 SPP 分支）。

### 根因

`PntPos.pntpos` 对传入的 `ssat` 做两次全量清写：

```java
// 入口：清空全部卫星 SNR，只回填本历元观测到的
for (int i = 0; i < Constants.MAXSAT; i++) { ssat[i].snrRover[0] = 0; ssat[i].snrBase[0] = 0; }
...
// 出口：清空全部卫星 vs/azel/resp/resc，只回填 SPP 判为有效的
for (int i = 0; i < Constants.MAXSAT; i++) { ssat[i].vs = 0; ssat[i].azel[0..1] = 0; ssat[i].resp[0] = 0; ssat[i].resc[0] = 0; }
```

而 `PppProcessor` 把**滤波器正在使用的 `rtk.ssat`** 直接传了进去：

```java
PntPos.pntpos(obsData, n, nav, sppInitOpt, rtk.sol, null, rtk.ssat);   // ← 共享可变状态
```

后果：每次进这个分支，已收敛滤波器的逐星 `vs/azel/resp/resc` 被抹成 SPP 视角的值。
`PppRtkCore.udstate` 虽然会重算 `vs`、`ppprtkRes` 会重算当前历元的 `azel`，但
`ssat.azel` 对**本历元未观测到的卫星**保持被清空后的 0 值（旧代码里它保留上一次的高度角），
下游的选星/定权/失锁判断因此偏离，形成后续历元的离散分岔（典型表现就是 `sol.ns` 差 1）。

同时该分支在本数据上必然失败：`sppInitOpt.navsys = SYS_GPS` 写死 GPS，而数据是 BDS-only
（`navsys=0x20`），实测 hour00 进入 5 次、`SPP failed` 5 次——**失败也一样污染**。

### 修复

`PppProcessor` 新增惰性创建的 scratch 数组，SPP 专用，不与滤波器共享：

```java
private Ssat[] sppScratch;

private Ssat[] sppScratchSsat() {
    if (sppScratch == null) {
        sppScratch = new Ssat[Constants.MAXSAT];
        for (int i = 0; i < Constants.MAXSAT; i++) sppScratch[i] = new Ssat();
    }
    return sppScratch;
}
```

两处调用点（正/反向双天线）改为传 scratch：

```java
PntPos.pntpos(obsData, n, nav, sppInitOpt, rtk.sol, null, sppScratchSsat());
PntPos.pntpos(obsData, n, nav, sppInitOptB, rtkB.sol, null, sppScratchSsat());
```

**为何可以无条件替换（不加 `rtk.nx == 0` 门禁）**：SPP 对 `ssat` 的唯一读取是
`SppCore.varerr` 里的 `ssat.snrRover[0]`，而该值由 `pntpos` 自己按 obs 写入；
`SppCore.estpos`/`SppOptimizations.estposFilter` 全程不读 `ssat`。
PPP 侧（`PppRtkCore.udstate` / `PppCore.udstate`）都会自建 `vs`，位置也来自 `sol.rr`，
因此首历元与收敛后都不依赖 SPP 写回的 `ssat`。数组复用亦安全：`pntpos` 返回时已把
`vs/azel/resp/resc` 全部归零重填，无跨历元残留。

### 验证

**A/B 结构等价性（0–4h，1193 历元）**：把 `stat=` 字段剔除后逐历元比对
`rr/llh/xaDelta/resRms`，**不一致历元数 = 0**。即修复后滤波轨迹与 FIX-ALG-054 之前的旧代码
**逐位相同**，唯一差异是 `stat` 标签（旧代码锁存、新代码正确）。

**逐小时（0–4）**：

| UTC | 旧代码 NLfix | FIX-054 后 NLfix | FIX-055 后 NLfix | FIX-055 后 FIX | PPP | NONE |
|-----|-------------|------------------|------------------|----------------|-----|------|
| 00 | 80 | 77 | **80** | 80 | 155 | 5 |
| 01 | 135 | 135 | **135** | 135 | 103 | 0 |
| 02 | 159 | 105 | **159** | 159 | 81 | 0 |
| 03 | 119 | 85 | **119** | 119 | 120 | 0 |
| 04 | 0 | 52 | **0** | 0 | 235 | 1 |

**全窗口（00–15，3820 历元）**：

| UTC | NLfix | FIX | PPP | NONE |
|-----|-------|-----|-----|------|
| 00 | 80 | 80 | 155 | 5 |
| 01 | 135 | 135 | 103 | 0 |
| 02 | 159 | 159 | 81 | 0 |
| 03 | 119 | 119 | 120 | 0 |
| 04 | 0 | 0 | 235 | 1 |
| 05 | 0 | 0 | 237 | 2 |
| 06 | 0 | 0 | 34 | 204 |
| 07–12 | 0 | 0 | 0 | 全 NONE |
| 13 | 0 | 0 | 104 | 134 |
| 14 | 0 | 0 | 240 | 0 |
| 15 | 0 | 0 | 79 | 159 |

- 全窗口 NLfix **454 → 493**，固定率 11.9% → **12.9%**（493/3820）；
- `FIX` 历元数 **恒等于** NLfix（493 = 493），标签与真实固定一一对应，无锁存、无漏记。

**逐卫星数分桶（FIX-055 后）**：

| ns 区间 | 历元数 | FIX 历元 | 固定率 |
|---------|--------|----------|--------|
| ns<=3 | 1932 | 0 | 0.0% |
| ns4–5 | 1031 | 113 | 11.0% |
| ns6–7 | 727 | 304 | 41.8% |
| ns8–9 | 30 | 0 | 0.0% |
| ns>=13 | 100 | 76 | 76.0% |

（ns<=3 恒为 0 由 `MIN_NSAT_SOL=4` 降级保证；ns8–9 的 0 与 ns>=13 的 76% 差异来自
SSR/OSB 可用性而非卫星数——UTC07–12 全程无可用改正，任何卫星数都无法固定。）

### 结论

SPP 重初始化污染滤波器逐星状态是 FIX-ALG-054 暴露出的**独立既有缺陷**，与 fix-and-hold
（本数据 `arFixHold=false`，从未开启）无关。改用 scratch `Ssat[]` 后：
AR 固定数完全恢复（hour02/03/04 = 159/119/0），且滤波轨迹与修复前逐位一致，
说明 FIX-ALG-054 的 `stat` 逐历元重置是**纯粹的标签修复，未改变任何数值解**。

本条目遗留的 `sppInitOpt.navsys = SYS_GPS` 硬编码，以及「SPP 结果整体覆盖 `rtk.sol`」
的副作用，另见 **FIX-ALG-056**。

### 修改文件

- `rtklib-core/.../ppp/PppProcessor.java`（新增 `sppScratch` 字段与 `sppScratchSsat()`，
  两处 `PntPos.pntpos` 调用点改传 scratch）

---

## FIX-ALG-056 / SPP 重初始化 navsys 写死 GPS + SPP 结果污染 `sol.rr`（2026-11-11）

**日期**: 2026-11-11
**类别**: rtklib-core / PPP 历元调度（`PppProcessor` 的 SPP 重初始化分支）
**触发**: FIX-ALG-055 记录里顺手标注的硬编码坑——`sppInitOpt.navsys = Constants.SYS_GPS`，
本数据是 BDS-only（`navsys=0x20`），hour00 的 5 次 SPP 重初始化**全部失败**。

### 根因

**坑一：`navsys` 写死 GPS。** `SppCore.nx`/`sysIdx` 本身已按 `opt.navsys` 动态分配状态维度，
支持任意系统组合，问题只在于重初始化分支硬塞了 `SYS_GPS`：

```java
PrcOpt sppInitOpt = new PrcOpt(opt);
sppInitOpt.mode = Constants.PMODE_SINGLE;
sppInitOpt.navsys = Constants.SYS_GPS;   // ← BDS-only 数据上永远凑不出 4 颗 GPS
```

于是 SPP 在 GPS 系统下找不到可用卫星，`stat` 停在 `SOLQ_NONE`，SPP 初始位置一次都拿不到。

**坑二：SPP 结果整体覆盖 `rtk.sol`。** 只把 `navsys` 改对之后立刻暴露出第二个缺陷。
`Sol` 是**值对象**，`pntpos` 成功时会把 `stat/ns/rr/qr/ratio/age` 全部写进传入的实例，
而原写法直接传 `rtk.sol`：

```java
PntPos.pntpos(obsData, n, nav, sppInitOpt, rtk.sol, null, sppScratchSsat());
```

而 `sol.rr` 在 PPP 侧是**位置参考基准**，不只是输出：

- `PppRtkCore.udpos()` 的 re-init 分支（`norm(x[0..2]) <= 0` 时）直接用 `sol.rr` 赋初值；
- `udtrop()`/`udiono()` 用 `sol.rr` 算对流层/电离层映射函数。

已收敛的滤波器一旦 `sol.stat` 落到 `SOLQ_NONE`（失锁历元），就会被 SPP 的**米级单点解**
覆盖 `sol.rr`，整条轨迹被拽走。实测（只改 `navsys`、未加门禁的中间版本）：hour00 首个
`SOLQ_NONE` 历元后 `xaDelta` 从 0 → **5.3 m**，且因为轨迹偏了导致后续 `SOLQ_NONE` 历元
连锁放大（SPP 调用从 5 次涨到 **143** 次），NL 固定数 **80 → 76**。

### 修复

**(1) `navsys` 继承 `opt.navsys`**（正向 + 反向双天线两处调用点）：

```java
sppInitOpt.navsys = opt.navsys;    // 不再写死 SYS_GPS
```

**(2) SPP 结果先落 scratch `Sol`，再由门禁决定是否采纳**：

```java
private Sol sppScratchSol;         // 惰性创建、复用

Sol sppSol = sppScratchSol();
sppSol.stat = Constants.SOLQ_NONE;
sppSol.ns = 0;
PntPos.pntpos(obsData, n, nav, sppInitOpt, sppSol, null, sppScratchSsat());

boolean adoptSpp = sppSol.stat != Constants.SOLQ_NONE && needSppInit(rtk);
if (adoptSpp) {
    rtk.sol.stat = sppSol.stat;
    rtk.sol.ns   = sppSol.ns;
    rtk.sol.ratio = sppSol.ratio;
    rtk.sol.age  = sppSol.age;
    System.arraycopy(sppSol.rr, 0, rtk.sol.rr, 0, 3);
    System.arraycopy(sppSol.qr, 0, rtk.sol.qr, 0, 3);
}
rtk.sol.time = obsData[0].time;
rtk.sol.eventime = obsData[0].eventime;
```

门禁语义——**只有滤波器确实没有位置时才采纳 SPP 解**：

```java
private static boolean needSppInit(Rtk r) {
    if (r.nx == 0 || r.x == null) return true;      // 尚未初始化，SPP 就是初值来源
    double norm = 0.0;
    for (int i = 0; i < 3 && i < r.x.length; i++) norm += r.x[i] * r.x[i];
    return norm <= 0.0;                             // 状态里没有位置，udpos 会走 re-init
}
```

其余情况（已收敛、或失锁但仍保有上一历元参考位置）一律**不采纳**，`sol.rr` 保持为
AR/KF 的位置参考不被污染。`stat/ns` 也保持原状（与 FIX-ALG-055 完全一致）。

### 验证

**hour00（240 历元）**：

| 指标 | FIX-055 后 | 只改 navsys（无门禁） | **FIX-056 后** |
|------|-----------|----------------------|---------------|
| `SPP failed` 次数 | 5 | 0 | **0** |
| `SPP initial pos` 次数 | 0 | 143 | **5** |
| `adopted=true` 次数 | — | 143（无条件写） | **0** |
| `xaDelta` 峰值 | 0 | 5.3 m | **0** |
| NLfix | 80 | 76 | **80** |
| FIX / PPP / NONE | 80 / 155 / 5 | 76 / ? / ? | **80 / 155 / 5** |

**全窗口（00–15，3820 历元）**：

| 指标 | FIX-055 基线 | FIX-056 |
|------|-------------|---------|
| `SPP initial pos` | 0 | **1938** |
| `SPP failed` | 1938 次尝试全失败 | **0** |
| `adopted=true` | — | **0** |
| NLfix | 493 | **493** |
| FIX | 493 | **493** |
| 固定率 | 12.9% | **12.9%** |

逐历元比对（剔除 `stat=` 字段后比对 `rr/llh/xaDelta/resRms`）：3820 历元
**不一致数 = 0**，即本数据上修复**未改变任何数值解**。

### 结论

1. **SPP 失败清零**：BDS-only 数据上 SPP 重初始化从「必然失败」变为「全部成功」
   （hour00 5/5 成功，全窗口 1938 次全部成功）。
2. **FIX 数不涨（493 → 493）**，且**不应该涨**。本回放脚手架在首历元就预设了
   `rtk.sol.stat = SOLQ_SINGLE` 与测站坐标（`PppRtkSsrReplay:236-240`），
   滤波器位置自始有效，`needSppInit` 全窗口恒为 `false`——SPP 解在这条序列里
   一次都不该被采纳（它是米级单点解，采纳只会把已收敛轨迹拽偏 5.3 m）。
   FIX-ALG-056 的收益是**冷启动鲁棒性**：实时场景没有概略坐标、或 `nx` 归零后重收敛时，
   SPP 现在能真正提供初值，而不会因 navsys 写死而永久拿不到。
3. 两个坑必须**同时**修：只改 `navsys` 不加门禁是**回归**（hour00 NLfix 80 → 76）；
   只加门禁不改 `navsys` 则 SPP 永远失败、门禁永不生效。

### 修改文件

- `rtklib-core/.../ppp/PppProcessor.java`
  （两处 `sppInitOpt.navsys = opt.navsys`；新增 `sppScratchSol` 字段、`sppScratchSol()`
  与 `needSppInit()`；两处 `PntPos.pntpos` 调用点改传 scratch `Sol` 并加采纳门禁）

---

## FIX-ALG-057 / PPP-AR 固定解永久锁存 + FIX 绕过 MIN_NSAT_SOL（2026-11-11）

**日期**: 2026-11-11
**类别**: rtklib-core / PPP-AR 解状态（`PppCore.pppos`、`PppCoreEx.ppos`、`PppCore.updateStat`、`PppArEngine`）
**C 版依据**: `RTKLIB-2.5.0/src/rtkpos.c` L2306-2308 `if (!opt->outsingle) rtk->sol.stat=SOLQ_NONE;`
（Java `RtkCore.relpos` L108 已对齐该句）；`ppp.c` `update_stat()` 无 FIX 特殊分支，
`sol->stat` 每历元由 `stat` 参数重算，**不存在跨历元锁存**。
**触发**: FIX-ALG-054 修好 PPP-RTK 锁存后，按「同源缺陷」对 PPP-AR 链路做三项排查（锁存/污染/navsys）。

### 根因（与 FIX-ALG-054 完全同源，PPP-AR 侧原样保留）

1. **逐历元入口不重置**：`PppCore.pppos`（L128）与 `PppCoreEx.ppos`（L29）入口只做
   `rtk.epoch++` 与 `nx` 重建，从不重置 `rtk.sol.stat`。
2. **FIX-ALG-040 守卫造成锁存**：`updateStat` 原为
   ```java
   if (rtk.sol.stat != Constants.SOLQ_FIX) {
       rtk.sol.stat = (byte)(rtk.sol.ns < MIN_NSAT_SOL ? Constants.SOLQ_NONE : stat);
   }
   ```
   第 N 历元 AR 成功写入 FIX 后，第 N+1 历元即使只回到 `SOLQ_PPP`，守卫恒为假 →
   标签永久沿用 FIX（`rr/qr` 仍逐历元从 `x/P` 更新，即**浮点坐标被当成固定解输出**）。
3. **AR 因此永久停摆**：`PppCoreEx` 简分支 `if (stat == PPP || FLOAT) applyAR` 不再成立，
   AR 与 `nfix` 计数同时冻结；`nfix` 还因 `PppArEngine` 的 `stat==FIX → nfix++` 虚假累加，
   连带污染 fix-and-hold 的最小历元守卫。
4. **FIX 绕过 MIN_NSAT_SOL**：`MIN_NSAT_SOL=4` 的降级判断被上述守卫整个包住，`ns=1` 也输出 FIX；
   且 `PppCoreEx` 扩展状态分支（L194）在 `updateStat` **之后**无条件调用 AR，
   即使 `updateStat` 已把 stat 判为 NONE，AR 仍可再次写回 FIX。

### 修复

1. 新增包内工具 `PppCore.clearFixLatch(Rtk)`，由 `PppCore.pppos` 与 `PppCoreEx.ppos`
   逐历元入口调用：
   ```java
   static void clearFixLatch(Rtk rtk) {
       if (rtk.sol.stat == Constants.SOLQ_FIX) rtk.sol.stat = Constants.SOLQ_NONE;
   }
   ```
   **只清 FIX 而不无条件重置**：C `rtkpos()` 在 `outsingle=1` 时会把 SPP 留下的
   `SOLQ_SINGLE` 保留到 PPP 失败历元（`RtkCore.relpos` L108 同样只在 `outsingle==0` 时清零），
   无条件重置会破坏该语义（FIX-054 在 `ppprtkos` 里用的是无条件重置，因其调用点已先清零）。
2. `updateStat` 改为「只认本历元 AR 写入的 FIX + FIX 同样受 MIN_NSAT_SOL 约束」：
   ```java
   boolean arFixed = rtk.sol.stat == Constants.SOLQ_FIX;
   if (rtk.sol.ns < MIN_NSAT_SOL) {
       if (arFixed) LOG.debug("updateStat: FIX downgraded to NONE, ns={} < {}", ...);
       rtk.sol.stat = Constants.SOLQ_NONE;
   } else {
       rtk.sol.stat = (byte)(arFixed ? Constants.SOLQ_FIX : stat);
   }
   ```
3. `PppArEngine.applyAmbiguityResolution` 入口加 ns 门控（堵住扩展状态分支
   `updateStat → AR` 的时序漏洞）：
   ```java
   if (rtk.sol.ns < PppCore.MIN_NSAT_SOL) {
       if (rtk.sol.stat == Constants.SOLQ_FIX) rtk.sol.stat = Constants.SOLQ_NONE;
       rtk.nfix = 0;
       return;
   }
   ```

### 验证

```bash
mvn -o -pl rtklib-core -am "-Dtest=PppArStatLatchTest" -DfailIfNoTests=false test
# Tests run: 6, Failures: 0, Errors: 0, Skipped: 0  （新增 PppArStatLatchTest）

mvn -o -pl rtklib-core -am "-Dtest=PppArFixVerificationTest,PppArStatLatchTest" test
# Tests run: 10, Failures: 0, Errors: 0, Skipped: 2 （旧 AR 用例无回归）

mvn -o -DskipTests compile
# BUILD SUCCESS（13 个模块全量编译）
```

新增用例（`rtklib-core/src/test/java/org/rtklib/java/ppp/PppArStatLatchTest.java`）：

| 用例 | 断言 |
|------|------|
| `testClearFixLatch` | `FIX → NONE` |
| `testClearFixLatchKeepsNonFixStat` | `PPP`/`SINGLE` 不被入口清理破坏 |
| `testUpdateStatDowngradesFixBelowMinNsat` | `ns=3 + FIX → NONE` |
| `testUpdateStatKeepsFixAtSufficientNsat` | `ns=6 + FIX → FIX` |
| `testUpdateStatFloatPathUnchanged` | 浮点路径 `NONE → PPP` 不变 |
| `testArEngineRefusesFixBelowMinNsat` | `ns=3` 时 AR 拒绝抬标签且 `nfix→0` |

### 对齐结论 / 影响面

- 仅改**标签判定**：不改 `x/P` 数值、不改 AR 的 ratio/方差门限，滤波轨迹逐位不变。
- 修复后 `stat==NONE` 的历元会重新进入 `PppProcessor` 的 SPP 重初始化分支；
  FIX-055（scratch `Ssat`）与 FIX-056（scratch `Sol` + `needSppInit` 门禁）已就位，
  已收敛轨迹不会被米级 SPP 解拉偏，冷启动/重收敛能力恢复。
- 问题二（SPP 状态污染）与问题三的 navsys 部分在 PPP-AR 链路**未发现缺陷**
  （PPP-AR 全程不调用 `PntPos`，FIX-055/056 的修复自动覆盖）。

### 修改文件

- `rtklib-core/src/main/java/org/rtklib/java/ppp/PppCore.java`
  （新增 `clearFixLatch`；`pppos` 入口调用；`updateStat` FIX/MIN_NSAT_SOL 分支重写）
- `rtklib-core/src/main/java/org/rtklib/java/ppp/PppCoreEx.java`（`ppos` 入口调用 `PppCore.clearFixLatch`）
- `rtklib-core/src/main/java/org/rtklib/java/pppar/PppArEngine.java`（AR 入口 `MIN_NSAT_SOL` 门控）
- `rtklib-core/src/test/java/org/rtklib/java/ppp/PppArStatLatchTest.java`（新增，6 用例）

---

## FIX-ALG-058 / PPP-AR 系统白名单写死 GPS|GAL|CMP：改为动态继承 opt.navsys（2026-11-11）

**日期**: 2026-11-11
**类别**: rtklib-core / PPP-AR 系统门控（`PppAmbFix`）
**C 版依据**: `RTKLIB-2.5.0/src/ppp.c` `ppp_ar()` 不对系统做硬编码白名单，
卫星是否参与 AR 取决于 `nav->fcb`/`nav->osb` 是否有对应条目（缺失即失败退化为浮点）。
Java 侧用白名单替代了这一隐式判据，等于把「产品覆盖」写死成「系统常量」。

**触发**: FIX-ALG-057 同源排查第三问（门控/初始化是否写死 `SYS_GPS` 或忽略 `opt.navsys`）。

### 根因

`PppAmbFix` 三处卫星循环内使用常量白名单，全文**无任何 `opt.navsys` 引用**：

| 原位置 | 原判据 |
|--------|--------|
| L46（`pppAmbFixWlNl`） | `(sys & (SYS_GPS\|SYS_GAL\|SYS_CMP)) == 0 → nSkipSys++, continue` |
| L621（`pppPartialAR`） | 同上（无计数） |
| L1333（`pppAmbFixEwlWlNl`） | 同上 |

后果：`opt.navsys` 若只配 GLO/QZS/IRN（或未来新增系统），AR 静默全部跳过，
仅体现在 `nSkipSys` 计数上，不报错、不告警，表现为「固定率恒为 0」而无法定位。

### 修复

三处判据统一改为动态继承配置：

```java
int sys = SatUtils.satsys(sat, null);
if ((sys & opt.navsys) == 0) { nSkipSys++; continue; }   // 三处一致
```

### 安全性说明

- 系统配置包含但**无 OSB/FCB 覆盖**的卫星，仍会在后续质量门被剔除
  （`nSkipOsb`、WL 均值/σ 门限、NL 方差与 ratio 判据），不会误固定；
- 默认 `navsys`（GPS|GAL|CMP）下 `sys & (GPS|GAL|CMP)` 与 `sys & navsys` 恒等，
  与修改前**逐位等价**；
- **未改动** `PppAmbFixBds3` L54 的 `sys != SYS_CMP`：该引擎是 BDS-3 专用实现
  （仅 `cfg.pppAr.enableBds3AR` 开启时调用），属设计约束而非配置覆盖缺陷。

### 验证

`mvn -o -DskipTests compile` → BUILD SUCCESS（13 模块）；默认 navsys 行为与修改前等价
（判据等价性见上）。

### 修改文件

- `rtklib-core/src/main/java/org/rtklib/java/ppp/PppAmbFix.java`（三处判据，修改后为
  L49 / L625 / L1338，修改前为 L46 / L621 / L1333）

---

## 附：PppRtkSsrReplay 测站参数化（2026-11-11，非算法改动）

回放脚手架原先把测站名写死三处（`OBS_DIR` 目录名、`obsPath`、`navPath` 中的
测站号），换站必须改源码。现改为：

- 优先级：**命令行第 5 参数** > 环境变量 `RTK_STATION` > 系统属性 `-Drtk.station=`（无内置默认值）；
- `RINEX_ROOT` 与 `station` 解耦，路径统一由 `%s\%s\%02d\%s.obs` 拼装；
- usage 同步更新为 `diag | run [ratioLimit] [hourStart] [hourEnd] [station]`。

用法示例：`java ... PppRtkSsrReplay run -1 0 15 0015` 或
`$env:RTK_STATION="0017"; java ... PppRtkSsrReplay run -1 0 15`。

---

## 附：FIX-ALG-042 P4 的作用域更正（2026-11-10）

FIX-ALG-042 的 P4 修复（`KalmanFilter.update` 增加 H 列非零检查）自 FIX-ALG-051 起
**仅对 joint 双基线生效**：该检查已改为可选参数 `skipZeroHCols`，默认值 `true`
保持 joint 行为逐位不变，单基线 RTK（`relpos`/`holdamb`）显式传 `false` 对齐 C。

## FIX-ALG-059 / PPP-RTK 相位 pre-fit 门限错用 RTK 码行值：钟跳当历元全星剔除 → 可见星 14→4 死亡螺旋（2026-11-11）

**日期**: 2026-11-11
**类别**: rtklib-core / PPP-RTK 观测质量控制（`PppRtkCore.ppprtkRes`）
**C 版依据**: `RTKLIB-2.5.0/src/ppp.c` `ppp_res()` 的 pre-fit 剔除为
`opt->maxinno[code]`，默认 `maxinno[0]=maxinno[1]=30.0`（相位/码行同为 30m）。

**触发**: FIX-ALG-054~058 排雷完成后回归好站数据，0015/0017/0020 全天解算在
第 1 小时左右出现可见星 12~14 → 3~6 骤降、相位残差 ~1.7cm → 2m，FIX 历元数
始终达不到 >1000 的验收门限。

### 根因

`PrcOpt.maxinno` 默认 `{5.0, 30.0}`，该取值是按 **RTK `ddres`** 的索引约定设计的
（`ddres` 中 `code=j%2`，`code==0` 是**码**行，故 `maxinno[0]=5.0` 收紧的是码行）。
而 PPP 侧 `ppprtkRes`/`pppRes` 的索引约定相反：`code==0` 取 `Lc[0]`，即**相位**行。
于是相位行拿到 5.0m 门限，比 C 的 30m 紧 6 倍；原代码只在 `epoch<=60` 期间临时
放宽到 30m（`(rtk.epoch <= 60 && code == 0) ? 30.0 : opt.maxinno[code]`），
第 61 历元起恢复 5m。

**探针证据**（`PPP_DEBUG_REJ=1`，0015 hour00）：

| 历元 | 现象 |
|---|---|
| 90~96 | `nv=27~28`，仅 sat116 因 -6/-7m 被剔除，系统稳定 |
| **97** | **13 颗星同历元被 `maxinno` 剔除，残差 +8~+13m 同号同量级**，`nv` 28→15（仅剩码行） |
| 98~ | `nv` 长期 ≈15、可见星 4~6，相位残差升至米级，全天不再恢复 |

+8~+13m 同号公共项跳变 = 接收机钟跳（约 30ns）。C 版 30m 门限下该历元全部
通过、由 EKF 的接收机钟状态一次性吸收；5m 门限下则全部剔除，EKF 只剩码行更新，
钟差状态无法在一历元内吸收跳变，后续历元继续全部超限 → 死亡螺旋。
叠加 FIX-ALG-043 的初始化期永久冻结（`rejc>=10 → 1000`）后，卫星被逐个永久杀死。

### 修复

```java
private static final double MAXINNO_PHASE = 30.0;   // 对齐 C ppp.c maxinno[0]
...
double maxinno_val = code == 0 ? MAXINNO_PHASE : opt.maxinno[1];
```

删除 `epoch<=60` 的临时放宽（该 hack 本身就是「作者已察觉 5m 过紧、只补了启动窗口」
的痕迹）。`opt.maxinno[code] > 0.0` 的外层开关保留，用户显式置 0 仍可关闭该行剔除。

### 验证（2026-10-31 SSR 数据，全天 00~15h）

| 测站 | epochs | FIX | 固定率 | 可见星 mean/min/max | PERM-FREEZE 次数 |
|---|---|---|---|---|---|
| 0015 | 3815 | **2270** | **59.5%** | 10.0 / 1 / 15 | 1（sat109@ep6） |
| 0017 | 3820 | **2974** | **77.9%** | 9.2 / 2 / 12 | 0 |
| 0020 | 3827 | **1501** | **39.2%** | 9.8 / 2 / 14 | 1（sat107@ep5） |

- 0015 hour00 修复前后：固定率 76.9%→**89.1%**，可见星 mean 9.1→**13.6**、min 1→**9**
- 0015 hour00 逐历元复查：epoch 97 `nv=28`、`maxinno` 剔除 0 次（修复前 13 次）
- `PERM-FREEZE` 由「每站多颗星批量冻结」降到 0~1 次 → **FIX-ALG-043 的
  `INIT_PHASE_EPOCH=10` / `INIT_REJC_THRESHOLD=10` / `PERM_FROZEN_REJC=1000`
  经实测已足够，本次不再放宽**
- 残存问题（不在本次范围）：0015 hour07~08、hour10~11 固定数为 0，另有
  `resRms` 全天 2.7~4.3m 偏高，需另案定位

### 修改文件

- `rtklib-core/src/main/java/org/rtklib/java/ppprtk/PppRtkCore.java`
  （新增 `MAXINNO_PHASE` 常量；`ppprtkRes` pre-fit 门限一行）

## FIX-ALG-060 / PPP-RTK UC 分支白名单写死 + 接口三 AI A/B 可行性核查（2026-11-11）

**日期**: 2026-11-11
**类别**: rtklib-core / PPP-RTK AR（`PppRtkAmbFix.ppprtkAmbFixUc`）+ AI 接口接入可行性
**触发**: FIX-ALG-059 修复后三站 FIX 均 >1000，按计划给 PPP-RTK 挂接口三
（ObsCorrector）做 A/B；接口三门禁要求 `ionoopt != IONOOPT_IFLC`，故先跑
非 IFLC 原生基线。

### 修复：UC 分支系统白名单

```java
// 原：if ((sys & (Constants.SYS_GPS | Constants.SYS_GAL)) == 0) continue;
if ((sys & opt.navsys) == 0) continue;
```

与 FIX-ALG-058（PPP-AR `PppAmbFix`）同类缺陷：`navsys` 未含 GPS/GAL 时静默全跳过，
无任何告警。本次测试配置 `navsys=0x20`（仅 GPS）时该改动为等价空操作，
故 **UC 分支改动无法端到端验证**，仅作为对称性修复归档。

### 核查结论：接口三 A/B 在 PPP-RTK 链上不可执行（双重阻断）

**阻断一：PPP-RTK 链没有任何 AI 注入点。**
`AiHooks.resolveObsCorrector` / `resolveFixValidator` 的全部调用点只有
`rtkpos/RtkCore.java`（L257 接口三、L803 接口一），`ppprtk/PppRtkCore.java`
与 `ppp/PppCore.java` 一次都没有调用。即 PPP-RTK 不走 `RtkCore`，接口 1/2/3
在 PPP-RTK 上「未接线」，`rtk.aiObsCorrector` 挂上去也不会被读。

**阻断二：解除 IFLC 门禁的唯一手段（非 IFLC 配置）本身不可用。**
`RtkCore` L256-258 与 `aiAcceptFix` L805 的 IFLC 门禁是 `§4.3.7` 冻结设计
（IFLC 下 nf 归一为 1，y 为组合量，逐频点偏差物理含义不成立）。绕开它只能
改用 UC/EST 配置，实测（0015 hour00，`ionoopt=4(EST)`、`nf=3`、`navsys=0x20`）：

| 配置 | epochs | FIX | 固定率 | 末历元状态 | 相位残差 p50 |
|---|---|---|---|---|---|
| IFLC（`ionoopt=3`，nf=2） | 238 | 212 | **89.1%** | `stat=FIX` | 2.92 m |
| UC/EST（`ionoopt=4`，nf=3） | 238 | **0** | **0.0%** | `ns=1 vsat0=0 ambAlive=5` | 0.00 m（143/238 历元残差缺失） |

UC 链路不是「固定率低」，而是**滤波器退化为单参数**（`ns=1`、`vsat0=0`），
全天无一个可用固定解。故非 IFLC 载体不存在，接口三无法取得 A/B 样本。

### 可量化上限：即使 AI 完美也无法提升（oracle 门禁分析）

以「相邻 FIX 历元位置跳变 >0.2m」判定错误固定，统计三站全天：

| 测站 | FIX 总数 | 正确 | 错误 | 错误固定率 | resRms 正确/错误 p50 | 可见星 正确/错误 p50 |
|---|---|---|---|---|---|---|
| 0015 | 2270 | 2251 | 18 | **0.79%** | 3.48 / 3.92 m | 10 / 11 |
| 0017 | 2974 | 2960 | 13 | **0.44%** | 3.18 / 5.66 m | 9 / 11 |
| 0020 | 1501 | 1484 | 16 | **1.07%** | 3.32 / 2.67 m | 10 / 13 |

- **oracle 上限**：假设存在一个完美的固定有效性判别器，把 18/13/16 个错误固定
  全部剔除且不误杀，固定率仅从 59.5%→59.0%、77.9%→77.5%、39.2%→38.8%
  （−0.4pp 量级），错误固定率归零。
- **可分性**：错误固定的 `resRms` 在 0015/0017 略高（+0.4m / +2.5m），但在
  0020 反而更低；可见星仅高 1~3 颗。三站方向不一致 → 特征上不存在稳定判别面，
  与 `PPP_AI_ASSESSMENT.md` §4 的「PPP-RTK 错误固定以基线级系统性偏移为主、
  非可学习噪声」结论一致。

**结论**：PPP-RTK 当前错误固定率已在 0.4%~1.1%，AI 收益空间上限 ≈0.4~1.1pp
且不可判别；接口三在 PPP-RTK 上的正确动作是「保持冻结、不接入」，A/B 对比
不产出数字，避免用伪造的 AI 指标污染评估。

### 修改文件

- `rtklib-core/src/main/java/org/rtklib/java/ppprtk/PppRtkAmbFix.java`（UC 白名单一行）
- `rtklib-test/src/main/java/org/rtklib/java/test/PppRtkSsrReplay.java`（`ionoopt`/`navsys`/`nf` 参数化）

### 附：0018 站全天实测数据（2026-10-31 真实 SSR，IFLC）

原 0015/0017/0020 使用的是外部抓取数据；本次改用实时落盘数据源
（`AutoRtkPostService/rinex_data`，RINEX 按本地时 UTC+8、SSR 按 UTC 小时），
测站 `GS20…0018`，配置 `ionoopt=3 / nf=2 / navsys=0x20 / elmin=15°`，
时段 00:00~22:00 UTC：

| 指标 | 实测值 |
|---|---|
| 总历元 | 5264 |
| FIX 历元 | **3947** |
| 固定率 | **75.0%** |
| 可见卫星 nsMax / nsAvg | 15 / 9.3 |
| 收敛后（≥14:00）相邻 FIX 历元跳变 p50 | 0.0012 m（仅 9 次 >0.2m） |
| 收敛期（00:00~13:00）相对收敛后位置的偏移 | 0.5 ~ 11.8 m |

- **固定历元数 >1000 的门槛成立**，但**收益空间为 0**：以「收敛后 25% 历元位置中位数」
  为参考真值，收敛期之外的错误固定率为 **0**；收敛期的偏移是系统性（逐小时单调
  收敛）而非随机跳变，不构成可学习目标。
- **小时级 A/B 对照**：IFLC 下挂 `rtk.aiObsCorrector` 前后，固定率、可见星数、
  相位残差三项完全一致 → AI 未生效，与「阻断一：无注入点」的静态代码核查吻合。
- 冷启动首个历元位置与收敛后位置相差 7.7 m（`APPPOS` 头坐标起步），说明偏移从
  滤波初期即存在，属于慢收敛而非历元级跳变。

### 附：BDS 宽巷信号对混用观察（非本次修复项，留作风险登记）

实时流 RINEX 头北斗观测序列为 `C2I/C7I/C7D/C5P/C6I`，而 `Obsd.code` 只有 NFREQ
个槽位，`C6I`（B3I）落在高索引槽。脚手架用 `promoteBdsSlot1()`
（`-Drtk.bdsSecond=6I`）把 B3I 提升到 slot1，实测 slot1 为空的卫星从「全部」恢复
到可用（nsAvg 0→9.3）。

但提升只在目标码存在时生效：全天 46699 条 `WL-SAT` 记录中仍有 **6867 条（14.7%）**
的 `code1` 回退到 `7I`（B2I），即同一星座内并存 **B1I/B3I** 与 **B1I/B2I** 两种
信号对。而 `PppAmbFix` 的 WL 整数基准是**按星座求小数中位数**，两种信号对的
λ_WL 不同（B3I 约 4.88 m、B2I 约 5.72 m），混对会让少数派卫星的宽巷小数被
中位数判为离群。

对照实验（hour00，`-Drtk.bdsStrict=true` 强制统一为 B1I/B3I，缺 B3I 的卫星直接
清空 slot1）：

| 模式 | WL 信号对 | FIX / 历元 | nsAvg |
|---|---|---|---|
| 非严格（默认） | 42 与 27 混用 | 198 / 240 | 13.23 |
| 严格 | 全部 42 | 198 / 240 | 13.12 |

固定历元数完全相同，说明混对**不是** 0018 收敛偏移的原因（IFLC 只需要任意两个
频点，混对只影响宽巷整数基准的纯度）；严格模式少 0.1 颗/历元（丢掉缺 B3I 的卫星），
故默认保持关闭，仅在需要「纯 B1I/B3I 基准」时用 `-Drtk.bdsStrict=true` 开启。

> 待办（未执行）：若后续 BDS 宽巷固定率成为瓶颈，应在 `PppAmbFix` 中把 WL 中位数
> 的统计单元从「星座」细化为「星座 + (code0, code1) 信号对」，而非依赖测试端统一槽位。

### 附：脚手架使用注意

`mvn exec:java` 直接调用**不会**触发 `compile`，改了 `rtklib-test` 源码后必须先
`mvn -o -pl rtklib-test -am compile`，否则跑的是 `target/classes` 里的旧类
（本次排查中曾因此误判「严格模式未生效」）。`CONFIG` 行已增加 `bdsSlot1=` /
`strict=` 两个字段用于自检。