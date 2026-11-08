# 训练管线（Python）

与 Java 侧完全独立的离线训练管线：读 Parquet → 两层切分 → 训练 → 导出 ONNX + `norm.json` → 契约校验 → 测试站评估。设计依据见 `docs/AI_ENHANCEMENT_DESIGN.md` §6 / §7。

## 1. 依赖

| 包 | 最低版本 | 用途 |
|---|---|---|
| `numpy` | 1.24 | 特征/标签数组、归一化 |
| `pyarrow` | 14.0 | Parquet 读写与 schema 校验 |
| `onnx` | 1.15 | 导出后结构检查 |
| `onnxruntime` | 1.17 | 导出后用真实推理做契约校验与评估 |
| `torch` | 2.0 | 训练（CPU 版即可） |

### 自动安装

```powershell
cd D:\rtklib\rtklib_java\rtklib-ai
python -m pip install -r training/requirements.txt
```

### 环境自检（推荐先跑）

```powershell
cd D:\rtklib\rtklib_java\rtklib-ai
python -m training.common.check_env
```

缺失或版本过低时会直接打印可执行的 `pip install` 命令，并以退出码 1 结束，便于接入 CI。

### 手动安装

```powershell
python -m pip install "numpy>=1.24" "pyarrow>=14.0" "onnx>=1.15" "onnxruntime>=1.17" "torch>=2.0"
```

## 2. 目录

```
training/
├── requirements.txt          # 依赖清单
├── common/
│   ├── check_env.py          # 环境自检
│   ├── dataset.py            # 接口三 Parquet 格式 + 两层切分 + 演示数据生成
│   ├── fix_dataset.py        # 接口一 Parquet 格式（6 维特征、二值标签）
│   ├── export_onnx.py        # ONNX 导出、norm.json 写入、契约校验
│   └── metrics.py            # RMSE / MAE / 相对改善置信区间 / G-1~G-4 判据
├── obs_corrector/train.py    # 接口三（观测改正）训练入口
├── fix_validator/train.py    # 接口一（固定校验）训练入口
└── out/                      # 产物目录（--out-dir 默认值）
```

两个接口共用 `common/` 的切分、导出、判据实现，但**数据格式与模型输出不同**：接口三 17 维输入 → 2 维输出（`bias` + `scale`），接口一 6 维输入 → 1 维输出（置信度）。

## 3. 运行

**工作目录必须是 `rtklib-ai`**，因为入口按包路径 `-m training.xxx` 导入。

```powershell
cd D:\rtklib\rtklib_java\rtklib-ai

# 接口三：演示数据（生成 + 训练 + 导出 + 评估，一条命令跑通）
python -m training.obs_corrector.train --demo --out-dir training/out

# 接口三：真实数据
python -m training.obs_corrector.train --dataset data/obs_corrector.parquet --out-dir training/out

# 接口一：演示数据
python -m training.fix_validator.train --demo --out-dir training/out

# 接口一：真实数据
python -m training.fix_validator.train --dataset data/fix_validator.parquet --out-dir training/out
```

两个入口共用 `--out-dir` 是安全的：产物按模型名前缀区分，不会互相覆盖。

接口三常用参数：`--epochs`（默认 30）、`--batch-size`（256）、`--lr`（3e-3）、`--hidden`（32）、`--patience`（5）、`--seed`、`--bias-max`（2.0）、`--scale-max`（4.0）。

`--bias-max` 必须小于 core 侧钳位 10 m，`--scale-max` 必须 ≤ core 侧钳位 4.0，否则契约校验会失败。

接口一常用参数：`--epochs`（30）、`--batch-size`（256）、`--lr`（3e-3）、`--hidden`（32）、`--patience`（5）、`--seed`、`--wrong-weight`（0 = 自动 `min(20, 正确数/错误数)`）、`--max-fix-drop-pp`（1.0）、`--min-confidence`（0.5，与 core 默认值一致）。


## 4. 数据格式

### 4.1 接口三（观测改正）

统一 Parquet，一行 = 一个「历元 × 卫星 × 频点」。

| 列 | 类型 | 说明 |
|---|---|---|
| `f0`…`f16` | float32 | 17 维特征，顺序与 `ObsCorrectionFeatures` 逐位对应 |
| `label` | float32 | 伪距误差真值（米） |
| `epoch` | int64 | 历元序号 |
| `sat` | int32 | 卫星号 |
| `frq` | int8 | 频点 |
| `station` | string | 站点标识，**切分依据，禁止空值** |
| `day` | string | 日期标识，**切分依据，禁止空值** |
| `solution_stat` | int8 | 解状态 |

#### 特征顺序（`f0`…`f16`）

| 索引 | 名称 | 含义 |
|---|---|---|
| f0 | `el` | 高度角（度） |
| f1 | `az` | 方位角（度） |
| f2 | `snrRover` | 流动站 SNR（dBHz） |
| f3 | `snrBase` | 基准站 SNR（dBHz） |
| f4 | `resp` | 上一历元伪距残差（米） |
| f5 | `resc` | 上一历元载波残差（米） |
| f6 | `lock` | 锁相时长（秒） |
| f7 | `slip` | 周跳标志（0/1） |
| f8 | `half` | 半周标志（0/1） |
| f9 | `gf` | 几何无关组合值 |
| f10 | `vsat` | 该频点是否有效（0/1） |
| f11 | `fix` | 模糊度状态（0/1/2/3） |
| f12 | `sysId` | 星座标识（尾随零位数，非 one-hot） |
| f13 | `respWinMean` | 近 5 历元 `resp` 均值 |
| f14 | `respWinStd` | 近 5 历元 `resp` 标准差 |
| f15 | `rescWinMean` | 近 5 历元 `resc` 均值 |
| f16 | `histLen` | 窗口内实际历元数 / 5，取值 [0.2, 1.0] |

### 4.2 接口一（固定校验）

统一 Parquet，一行 = 一个「历元 × 固定解尝试」，即 core 侧 `aiAcceptFix()` 被调用的那一刻（`nb > 1` 且 `valpos` 通过）。**未进入固定分支的历元不得入库**：接口一在那些历元上不会被调用，混入会污染特征分布。

| 列 | 类型 | 说明 |
|---|---|---|
| `f0`…`f5` | float32 | 6 维特征，顺序与 `FixQualityFeatures` 逐位对应 |
| `label` | float32 | **1 = 固定正确，0 = 错误固定**（二值，其它取值会被拒绝） |
| `epoch` | int64 | 历元序号 |
| `station` | string | 站点标识，**切分依据，禁止空值** |
| `day` | string | 日期标识，**切分依据，禁止空值** |
| `n_code_rows` | int32 | 伪距残差行数（判定 `pcr` 是否为占位 0.0） |
| `n_phase_rows` | int32 | 载波残差行数（判定 `prr` 是否为占位 0.0） |

#### 特征顺序（`f0`…`f5`）

| 索引 | 名称 | 来源 | 含义 |
|---|---|---|---|
| f0 | `adr` | `FixQualityContext.adrRms` | 模糊度差 RMS（整数解离浮点解多远） |
| f1 | `prr` | `prrRms` | 载波残差 RMS（整数解与相位的拟合一致度） |
| f2 | `pcr` | `pcrRms` | 伪距残差 RMS（整数解与伪距的拟合一致度） |
| f3 | `ratio` | `ratio` | LAMBDA ratio（保留作对照基线） |
| f4 | `ns_fixed` | `nb / ns` | 几何冗余度 |
| f5 | `pdop` | `pdop` | 位置 DOP（几何强度） |

`nPhaseRows == 0` 时 `f1` 记 0.0，`nCodeRows == 0` 时 `f2` 记 0.0，`ns <= 0` 时 `f4` 记 0.0——与 `FixQualityFeatures.extractRaw()` 的退化处理一致，否则训练与推理的特征语义会错位。

### 4.3 数据校验与切分（两者共用）

`validate()` 拒绝：缺必需列、`station`/`day` 为空、标签非有限值；接口一另外要求标签严格为 0/1。

`two_layer_split()` 先按站分组（test 2 站 / val 1 站 / 其余训练），再在训练站内部按天切出验证日。**禁止随机切分**：相邻历元高度相关，随机切分会让同一段几何/大气同时进训练与测试，评估虚高。站数少于 4 个、或训练站不足 2 天，会直接报错。

### 4.4 接口一的样本不均衡

错误固定是少数类（真实数据通常 2%~8%）。若按普通 BCE 训练，模型最小损失解是「全部接受」，等于没接 AI。因此 `fix_validator/train.py` 默认按 `min(20, 正确数/错误数)` 自动加权错误固定样本；权重上限 20 是防止少数类梯度主导导致整体置信度被压到阈值以下。

## 5. 产物

| 文件 | 用途 | Java 侧消费方 |
|---|---|---|
| `obs_corrector.onnx` | 接口三模型权重（17→2） | `OnnxInference` |
| `obs_corrector.norm.json` | `mean`/`std` 各 17 维 | `FeatureNorm` |
| `obs_corrector.metrics.json` | 接口三评估报告 | 人工审阅 |
| `fix_validator.onnx` | 接口一模型权重（6→1） | `OnnxInference` |
| `fix_validator.norm.json` | `mean`/`std` 各 6 维 | `FeatureNorm` |
| `fix_validator.metrics.json` | 接口一评估报告 | 人工审阅 |
| `demo_obs_corrector.parquet` | 演示数据（仅 `--demo`） | 无 |
| `demo_fix_validator.parquet` | 演示数据（仅 `--demo`） | 无 |

## 6. 采集（Java 侧）

训练集不是一次跑出来的：单个 RINEX 通常只有几百到几千个固定历元，而错误固定是 2%~8% 的少数类，按 §7.4 的 G-1 前置条件（错误固定事件 ≥ 100）需累计上万行。因此采集按「多次运行累积」设计，每次运行产出一个 CSV，靠 `run_id` 区分。

### 6.1 标准用法（唯一入口）

`CollectSession` 把「注入 provider → 解算 → 清理 `ThreadLocal`」收成一个 try-with-resources：

```java
CollectConfig cfg = CollectConfig.of(Paths.get("out/collect"), "GEJ3034", "2021-304");
try (CollectSession s = CollectSession.open(cfg)) {
    s.runWith(() -> solveOneRinex(cfg));   // 注入 → 解算 → 清理，一步完成
}
```

- 站点/日期是**构造参数**，写在调用点，肉眼可见。本仓库无配置文件机制，也不建议为此新增：批量跑时靠复制粘贴改配置的失败模式是「漏改一次 → 整批标签错站且完全静默」。
- 缺省 `runId` 自动生成（`yyyyMMddTHHmmss-xxxx`），文件名 `{station}_{day}_{runId}.csv`，天然不碰撞。
- 同 `runId` 重复打开会抛 `IOException`（拒绝覆盖）。多跑一次就换一个 `runId`。
- 注入点在执行后**还原**（而非清空）：宿主若已有自己的 provider，不会被采集会话抹掉；`body` 抛异常也照样还原。

### 6.2 采集器的行为

`RecordingFixValidator.validate()` **恒返回 `1.0`**，因此：

- core 走原生固定分支，与不注入逐位一致 → 这份数据同时是 A/B 的对照组基线；
- 不会产生 `AR/AI_REJECT`，采集不改变任何解算结果。

特征复用 `FixQualityFeatures.extractRaw()`，不在采集侧重算——重算会丢掉退化处理（`nPhaseRows==0 → prr=0`），导致训练/推理分布错位。

### 6.3 CSV 列

```
run_id,station,day,epoch,f0,f1,f2,f3,f4,f5,ns,nf,nb,n_code_rows,n_phase_rows
```

比 Parquet 多出 `run_id`（多 run 主键的另一半）与 `ns/nf/nb`（诊断用，组装时丢弃）。`label` 不在 CSV 内：标签由离线真值 join 得到。

### 6.4 多次运行的目录形态

```
out/collect/
├── _runs.csv                                      # 运行清单（见下）
├── GEJ3034_2021-304_20211031T101010-a1b2.csv
└── GEJ3034_2021-304_20211031T101010-a1b2.trace    # 需宿主把 trace 输出到 cfg.tracePath()
```

`_runs.csv` 每次运行结束追加一行：`run_id,station,day,csv_file,trace_file,started_at,finished_at,rows,status`。崩溃的运行没有这一行，组装阶段据此识别残缺数据。

### 6.5 两个硬性前置条件

1. **必须开 trace 且 `samplerate = 1`**：标签来自 `AR/FIX` trace 里的 `x/y/z`。`samplerate > 1` 会让一半历元没有 trace 行，组装时 join 覆盖率跌破门槛（§6.6），整 run 被跳过。`gpst` 只用于诊断，不参与 join。
2. **必须用非 IFLC 电离层配置**：`ionoopt == IONOOPT_IFLC` 时 core 直接跳过接口一，采集器根本不被调用，跑完得到一个只有表头的空 CSV。

### 6.6 组装：CSV → Parquet（`training/fix_validator/assemble.py`）

采集只解决「特征从哪来」。标签必须由**离线真值**产生，core 不参与打标签：

```
采集 CSV (run_id, epoch, f0..f5)  ┐
trace 的 AR/FIX 行 (epoch → x/y/z) ┼→ join → 3D 误差 → label → Parquet
站点已知坐标 (truth.csv)          ┘
```

```bash
python -m training.fix_validator.assemble \
    --collect-dir out/collect --truth truth.csv --out data/fix_validator.parquet
```

**join key 是 `(run_id, epoch)`，不是时间。** `FixQualityContext.epoch` 是进程内计数器，跨运行从 0 重来，所以 `run_id` 是主键的另一半。由于真值是静态已知坐标，打标签**不需要绝对时间**；trace 里的 `gpst` 只进诊断报告，不参与 join。

真值文件两种表头，按列名自动识别（不猜）：

```csv
station,ref_x,ref_y,ref_z        # ECEF
station,lat,lon,hgt              # 大地坐标（度/度/米，WGS84）
```

判定规则：`|固定解 - 已知坐标| ≤ --threshold`（默认 0.05 m）记 `label=1`（正确），否则 `label=0`（**错误固定**，即接口一要拦的对象）。

三类拒绝（都写进 `*.assemble.json` 诊断报告，不静默）：

| 情形 | 处理 |
|---|---|
| `_runs.csv` 中无 `status=ok` | 该运行跳过，不进数据 |
| 站点在 `truth.csv` 里查不到 | 该运行跳过（无真值即无标签） |
| 单 run 的 epoch↔trace 覆盖率 < `--min-join-rate`（默认 0.90） | 整 run 跳过并给出原因 |

覆盖率门槛是**整 run 级别**而不是逐行丢弃：逐行丢弃会让「trace 采样率 50%」这类系统性缺陷退化成「数据少一半但看起来正常」，而指标上完全看不出来。

组装完成后看三件事：

1. `wrong_fix_rate` 是否落在 2%~8%（过高说明阈值或真值有问题，过低说明根本没采到坏历元）；
2. `err_p99 / err_max` 是否与阈值量级相符（若 `max` 恰等于阈值，多半是真值站号错配）；
3. `warnings` 里「站数 < 4」「错误固定 < 100」——前者切分做不了，后者 G-1 判不了。

### 6.7 仍未完成

真实数据进训练前的最后一环已打通（§6.6），但**尚未用真实 RINEX 跑过**。当前 `--demo` 与合成数据只证明链路正确，不构成任何效果承诺。

## 7. 注意事项

- **opset 上限**：`export_onnx.export()` 默认 `opset=17`。Java 侧固定使用 onnxruntime 1.17.3（支持至 opset 19），**不得把 opset 调到 19 以上**，否则 Java 加载失败。
- **行序必须对账**：`sat`/`frq` 必须与推理侧 `idx = i * nf + frq` 的行序一致，否则训练标签与推理特征错位。
- **归一化只拟合训练集**：`norm.json` 由训练集统计量写出，Java 侧原样加载。禁止按运行数据动态归一化。
- **演示数据不承诺效果**：`make_demo()` 的标签由人工隐函数生成，`--demo` 的高改善率只证明链路跑通（§6.3）。
- **采集与组装已可用，但未用真实 RINEX 验证**：Java 侧 `CollectSession` 产 CSV（§6.1），Python 侧 `assemble.py` 产 Parquet（§6.6）。链路已用合成数据端到端跑通，真实数据尚未跑过。
- **接口一的阈值永远在 core 侧**：模型只输出 `[0,1]` 置信度，`AiOpts.fixValidatorMinConfidence` 决定接受与否。训练脚本扫阈值只为给出「建议值」，不写进 ONNX。
- **接口一的固定率代价必须显式约束**：训练脚本在验证集上以「固定率绝对下降 ≤ `--max-fix-drop-pp`（默认 1.0 pp）」为约束最大化错误固定剔除。若不设此约束，模型可通过「全部拒绝」拿到 0 错误固定率，但固定率会崩。