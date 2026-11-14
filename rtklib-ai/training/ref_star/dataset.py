"""接口四（参考星风险判别）训练数据格式：统一 Parquet + 两层切分。

一行 = 一个「历元 × 卫星 × 频点」，列名与 ``RefStarFeatures`` 的 15 维特征逐位对应。

三条硬约束（与接口三同构，但语义不同，不要混用接口三的数据集）：

1. ``station`` 与 ``day`` 是切分依据，缺失的数据不得进入评估；
2. 切分必须是「先按站、再按天」的两层切分，禁止随机切分（相邻历元高度相关）；
3. **样本只有参考星**（``is_ref == 1``）：正例是「当历元是参考星、且随后 W 历元内
   发生滑相/失锁」，负例是「当历元是参考星、且随后 W 历元内保持稳定」。
   非参考星的行不进入数据集——模型学的是「这颗星当参考星安不安全」，
   而不是「这颗星能不能当参考星」（后者是 core 的择优职责，§1.1 只否决不优选）。

特征集里**没有** ``resp``/``resc``：参考星残差恒 0 由构造决定，一旦入特征就是标签泄漏。
Java 侧 ``RefStarContext`` 已在构造时把这两维强制置 0（H1），此处仅作结构保证。
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
import pyarrow as pa
import pyarrow.parquet as pq

#: 接口四特征维（§5.1 定死，必须与 ``RefStarFeatures.DIM`` 相等）。
DIM = 15
FEATURE_COLUMNS = [f"f{i}" for i in range(DIM)]
LABEL_COLUMN = "label"
META_COLUMNS = ("epoch", "sat", "frq", "station", "day", "is_ref", "event_id")
REQUIRED_COLUMNS = tuple(FEATURE_COLUMNS) + (LABEL_COLUMN,) + META_COLUMNS

#: 标签窗口 W（与 ``AiOpts.refStarWindowEpochs`` 默认值一致）。
DEFAULT_LABEL_WINDOW = 5

_SCHEMA = pa.schema(
    [(c, pa.float32()) for c in FEATURE_COLUMNS]
    + [
        (LABEL_COLUMN, pa.float32()),
        ("epoch", pa.int64()),
        ("sat", pa.int32()),
        ("frq", pa.int8()),
        ("station", pa.string()),
        ("day", pa.string()),
        ("is_ref", pa.int8()),
        ("event_id", pa.int32()),
    ]
)


def schema() -> pa.Schema:
    """数据集 schema（列式、可校验）。"""
    return _SCHEMA


def validate(table: pa.Table) -> None:
    """校验列齐全、切分依据非空、标签二值、样本全是参考星。"""
    missing = [c for c in REQUIRED_COLUMNS if c not in table.column_names]
    if missing:
        raise ValueError(f"数据集缺少必需列: {missing}")

    for name in ("station", "day"):
        values = table[name].to_pylist()
        if any(v is None or str(v).strip() == "" for v in values):
            raise ValueError(f"列 {name} 存在空值：切分依据缺失的数据不得进入评估")

    if len(table) == 0:
        raise ValueError("数据集为空")

    label = np.asarray(table[LABEL_COLUMN].to_numpy(zero_copy_only=False), dtype=np.float64)
    if not np.all(np.isfinite(label)):
        raise ValueError("标签列存在非有限值，须在组装阶段清洗")
    if not np.all((label == 0.0) | (label == 1.0)):
        raise ValueError("标签必须是二值 0/1（滑相风险事件），不允许回归口径")

    is_ref = np.asarray(table["is_ref"].to_numpy(zero_copy_only=False), dtype=np.int64)
    if not np.all(is_ref == 1):
        n_bad = int((is_ref != 1).sum())
        raise ValueError(
            f"数据集含 {n_bad} 行非参考星样本：正负例都必须是参考星（§5.2），"
            f"否则「非参考星」与「参考星」的分布差异会被当成风险信号学进去")


def read(path) -> pa.Table:
    """读取 Parquet 并立即校验。"""
    table = pq.read_table(str(path))
    validate(table)
    return table


def write(table: pa.Table, path, note: str | None = None) -> None:
    """写出 Parquet；``note`` 写入 schema metadata（示例数据集须标注「仅演示」）。"""
    validate(table)
    meta = dict(table.schema.metadata or {})
    meta[b"rtklib-ai"] = b"refstar_v1"
    if note:
        meta[b"note"] = note.encode("utf-8")
    parent = Path(path).parent
    if str(parent) and not parent.exists():
        parent.mkdir(parents=True, exist_ok=True)
    pq.write_table(table.replace_schema_metadata(meta), str(path))


def features(table: pa.Table) -> np.ndarray:
    """取 15 维特征矩阵 ``[n, 15]``（float32）。"""
    cols = [np.asarray(table[c].to_numpy(zero_copy_only=False), dtype=np.float32)
            for c in FEATURE_COLUMNS]
    return np.stack(cols, axis=1)


def labels(table: pa.Table) -> np.ndarray:
    """取标签向量 ``[n]``（float32，0/1）。"""
    return np.asarray(table[LABEL_COLUMN].to_numpy(zero_copy_only=False), dtype=np.float32)


def event_count(table: pa.Table) -> int:
    """独立事件数：同一 ``(station, day, sat)`` 连续正例历元聚成一个事件。

    **为什么按事件而不是按行计数**：相邻历元高度相关，同一段污染会连续几十个历元都是
    正例，按行计数会把一个事件当成几十个样本，样本量虚高、置信区间假窄。
    双阈值终止条件（事件数 &lt; 50 或正例比例 &lt; 1%）用的就是这个口径。
    """
    ev = np.asarray(table["event_id"].to_numpy(zero_copy_only=False), dtype=np.int64)
    return int(len({int(v) for v in ev if v >= 0}))


def positive_rate(table: pa.Table) -> float:
    y = labels(table)
    return float(y.mean()) if y.size else 0.0


def make_demo(n_stations: int = 6, n_days: int = 3, n_epochs: int = 120,
              n_sat: int = 10, seed: int = 20261114) -> pa.Table:
    """生成「仅演示」数据集：证明链路可跑通，**不承诺模型效果**。

    与接口三的演示集合同理：特征按物理取值范围采样，且均值/标准差对齐单频链路实测分布，
    因为归一化参数只在训练集上拟合，训练集与推理侧分布不一致会让输入整体 z 漂移。

    标签按「参考星 + 随后 W 历元滑相/失锁」的物理定义生成：先在每条
    ``(station, day, sat)`` 序列上按物理风险采样「滑相事件」，再回看 W 历元打标——
    低仰角、低 SNR、SNR 抖动大的序列事件率更高。**正例比例因此落在 2%~3% 量级**，
    与真实链路一致（参考星失锁是稀疏事件）。若演示集把正例做到 30%，
    阈值标定出来的 ``refStarRiskMax`` 会让切换率直接撞上熔断上限，
    演示集看起来"能用"、上线即熔断。

    **不能逐行独立采样标签**：逐行独立会让正例在时间轴上散开，事件聚类（
    :func:`event_count`）退化成「正例行数」，双阈值里的事件数判据失效。

    **也不能逐历元独立采样特征**：标签在 ``t`` 取决于 ``t+1..t+W`` 的事件，而事件率
    取决于那些历元的仰角/SNR。若特征逐历元独立采样，``t`` 处的特征与 ``t+1..t+W`` 处的
    风险因素互不相关，标签就几乎不可学（实测 AUC ≈ 0.54，阈值处 precision = 0），
    看起来像实现有问题。真实链路的仰角/SNR 是时间连续的，故此处按
    ``(station, day, sat)`` 生成平滑序列（随机游走归一化），使「当历元特征」确实
    携带「随后 W 历元风险」的信息。

    演示集的行数与事件数按双阈值（事件 ≥ 50、正例比例 ≥ 1%）可通过来设置，
    但**通过不代表可用**：没有步骤 0（``is_ref`` trace + 重跑采集）时，
    真实数据的训练产物无效，本函数只用于验证代码链路。
    """
    rng = np.random.default_rng(seed)
    seq_shape = (n_stations, n_days, n_sat, n_epochs)
    shape = (n_stations, n_days, n_epochs, n_sat)
    n = int(np.prod(shape))

    station = np.repeat([f"S{i}" for i in range(n_stations)], n_days * n_epochs * n_sat)
    day = np.tile(np.repeat([f"D{i}" for i in range(n_days)], n_epochs * n_sat), n_stations)
    epoch = np.tile(np.repeat(np.arange(n_epochs), n_sat), n_stations * n_days)
    sat = np.tile(np.tile(np.arange(1, n_sat + 1), n_epochs), n_stations * n_days)

    def flat(a):
        return a.transpose(0, 1, 3, 2).reshape(-1)

    el = flat(np.clip(_smooth(rng, seq_shape, 52.0, 20.0), 15.0, 85.0))
    az = flat((rng.uniform(0.0, 360.0, seq_shape[:3] + (1,))
               + 0.5 * np.arange(n_epochs)) % 360.0 + np.zeros(seq_shape))
    snr_rover = flat(np.clip(_smooth(rng, seq_shape, 41.5, 7.8), 8.0, 55.0))
    snr_base = flat(np.clip(_smooth(rng, seq_shape, 46.7, 2.8), 38.9, 51.9))
    lock = flat(np.where(rng.random(seq_shape) < 0.30, 0.0,
                         np.minimum(rng.exponential(120.0, seq_shape), 900.0)))
    half = flat((rng.random(seq_shape) < 0.06).astype(np.float64))
    # 单频链路不赋值 gf：恒 0 与 CONSTANT_COLS 声明一致（归一化按 1.0 兜底）
    gf = np.zeros(n, dtype=np.float64)
    vsat = flat((rng.random(seq_shape) < 0.97).astype(np.float64))
    fix = flat(np.repeat(rng.integers(0, 4, seq_shape[:3] + (1,)), n_epochs, axis=3)
               .astype(np.float64))
    # sysId 逐卫星恒定（同一颗星的星座不随历元变化），故按卫星生成后再广播
    sys_per_sat = rng.choice(np.arange(5), size=(n_stations, n_days, n_sat, 1),
                             p=[0.40, 0.22, 0.14, 0.12, 0.12]).astype(np.float64)
    sys_id = flat(sys_per_sat + np.zeros(seq_shape))
    ref_count = flat(rng.integers(0, DEFAULT_LABEL_WINDOW + 1, seq_shape).astype(np.float64))
    snr_win_mean = flat(np.clip(_smooth(rng, seq_shape, 41.5, 7.8)
                                + _smooth(rng, seq_shape, 0.0, 1.0), 8.0, 55.0))
    snr_win_std = flat(np.clip(np.abs(_smooth(rng, seq_shape, 2.2, 1.6)), 0.0, 12.0))

    el_t = el.reshape(shape)
    snr_mean_t = snr_win_mean.reshape(shape)
    snr_std_t = snr_win_std.reshape(shape)
    slip = _sample_slips(rng, shape, el_t, snr_mean_t, snr_std_t).reshape(-1)
    label = _label_from_slips(slip.reshape(shape), DEFAULT_LABEL_WINDOW).reshape(-1)

    cols = [el, az, snr_rover, snr_base, lock, slip, half, gf, vsat, fix,
            sys_id, (ref_count > 0).astype(np.float64), snr_win_mean, snr_win_std,
            ref_count / DEFAULT_LABEL_WINDOW]

    event_id = _cluster_events(station, day, sat, epoch, label)

    table = pa.table(
        {**{f"f{i}": pa.array(cols[i].astype(np.float32), type=pa.float32())
           for i in range(DIM)},
         LABEL_COLUMN: pa.array(label.astype(np.float32), type=pa.float32()),
         "epoch": pa.array(epoch.astype(np.int64), type=pa.int64()),
         "sat": pa.array(sat.astype(np.int32), type=pa.int32()),
         "frq": pa.array(np.zeros(n, dtype=np.int8), type=pa.int8()),
         "station": pa.array(station, type=pa.string()),
         "day": pa.array(day, type=pa.string()),
         "is_ref": pa.array(np.ones(n, dtype=np.int8), type=pa.int8()),
         "event_id": pa.array(event_id, type=pa.int32())},
        schema=_SCHEMA,
    )
    return table


def _smooth(rng, shape, mean: float, sd: float) -> np.ndarray:
    """生成按时间连续的序列：随机游走 → 逐序列去均值、归一到目标 ``sd``。

    真实链路的仰角/SNR 不会逐历元跳变，相邻历元高度相关；逐历元独立采样会让
    「当历元特征」无法预测「随后 W 历元的风险」，标签变得不可学。
    """
    w = np.cumsum(rng.normal(0.0, 1.0, shape), axis=-1)
    w -= w.mean(axis=-1, keepdims=True)
    s = w.std(axis=-1, keepdims=True)
    w = np.divide(w, s, out=np.zeros_like(w), where=s > 1e-9)
    return mean + sd * w


def _sample_slips(rng, shape, el, snr_win_mean, snr_win_std) -> np.ndarray:
    """按物理风险采样滑相/失锁事件（1 = 该历元该星发生滑相）。

    事件率由低仰角、低 SNR、SNR 抖动大共同抬高；基线刻意压到 0.5% 量级，
    使「回看 W 历元」后的正例比例落在 2%~3%，与真实参考星失锁的稀疏度一致。

    系数不是随手取的：``-6.6`` 的基线让逐历元事件率 ≈ 0.5%，而三个风险项的标准差
    之和约 1.9，使高风险尾部（p90）的事件率比均值高一个量级——这正是模型要学的
    排序，若系数过小（如 -0.045/-0.03/0.28），单特征 AUC 会退到 0.52 附近，
    链路虽然跑通但"学不到东西"，容易被误判成实现有问题。
    """
    logit = (-6.6
             - 0.09 * (snr_win_mean - 41.5)
             - 0.07 * (el - 52.0)
             + 0.55 * (snr_win_std - 2.2))
    p = 1.0 / (1.0 + np.exp(-logit.reshape(shape)))
    return (rng.random(shape) < p).astype(np.float64)


def _label_from_slips(slip: np.ndarray, window: int) -> np.ndarray:
    """``label[t] = 1`` 当 ``(t, t+W]`` 内发生滑相；序列末尾窗口截断（演示集不丢行）。"""
    cum = np.cumsum(slip, axis=2)
    label = np.zeros(slip.shape, dtype=np.float64)
    n_epochs = slip.shape[2]
    for t in range(n_epochs):
        end = min(t + window, n_epochs - 1)
        label[:, :, t, :] = (cum[:, :, end, :] - cum[:, :, t, :]) > 0.0
    return label


def _cluster_events(station, day, sat, epoch, label) -> np.ndarray:
    """把同一 ``(station, day, sat)`` 的连续正例历元聚成一个事件 id（负例为 -1）。"""
    event_id = np.full(label.shape, -1, dtype=np.int32)
    order = np.lexsort((epoch, sat, day, station))
    nxt = 0
    prev_key = None
    prev_epoch = None
    cur = -1
    for k in order:
        key = (station[k], day[k], int(sat[k]))
        if label[k] > 0.5:
            # ``cur < 0`` 必须显式判：上一个历元是负例时 ``cur`` 已被复位，
            # 此时即使 key 相同且历元连续，也必须开新事件，否则整段正例被写成 -1。
            if cur < 0 or key != prev_key or prev_epoch is None \
                    or int(epoch[k]) != prev_epoch + 1:
                cur = nxt
                nxt += 1
            event_id[k] = cur
        else:
            cur = -1
        prev_key = key
        prev_epoch = int(epoch[k])
    return event_id