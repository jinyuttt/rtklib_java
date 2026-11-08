"""接口三（观测改正）训练数据格式：统一 Parquet + 两层切分（设计文档 §6.2 / §7.3）。

一行 = 一个「历元 × 卫星 × 频点」，列名与 §5.3 的 17 维特征逐位对应。

必须遵守的两条硬约束：

1. ``station`` 与 ``day`` 是切分依据，缺失的数据**不得进入评估**；
2. 切分必须是「先按站、再按天」的两层切分，**禁止随机切分**（相邻历元高度相关，
   随机切分会让同一段几何/大气同时出现在训练与测试里，评估结果虚高）。
"""

from __future__ import annotations

import numpy as np
import pyarrow as pa
import pyarrow.parquet as pq

DIM = 17
FEATURE_COLUMNS = [f"f{i}" for i in range(DIM)]
LABEL_COLUMN = "label"
META_COLUMNS = ("epoch", "sat", "frq", "station", "day", "solution_stat")
REQUIRED_COLUMNS = tuple(FEATURE_COLUMNS) + (LABEL_COLUMN,) + META_COLUMNS

_SCHEMA = pa.schema(
    [(c, pa.float32()) for c in FEATURE_COLUMNS]
    + [
        (LABEL_COLUMN, pa.float32()),
        ("epoch", pa.int64()),
        ("sat", pa.int32()),
        ("frq", pa.int8()),
        ("station", pa.string()),
        ("day", pa.string()),
        ("solution_stat", pa.int8()),
    ]
)


def schema() -> pa.Schema:
    """数据集 schema（列式、可校验）。"""
    return _SCHEMA


def validate(table: pa.Table) -> None:
    """校验列齐全、类型可转换、元数据非空——不合格的数据不得进入训练/评估。"""
    missing = [c for c in REQUIRED_COLUMNS if c not in table.column_names]
    if missing:
        raise ValueError(f"数据集缺少必需列: {missing}")

    for name in ("station", "day"):
        values = table[name].to_pylist()
        if any(v is None or str(v).strip() == "" for v in values):
            raise ValueError(f"列 {name} 存在空值：切分依据缺失的数据不得进入评估（§7.3）")

    label = np.asarray(table[LABEL_COLUMN].to_numpy(zero_copy_only=False), dtype=np.float64)
    if label.size == 0:
        raise ValueError("数据集为空")
    if not np.all(np.isfinite(label)):
        raise ValueError("标签列存在非有限值，须在采集阶段清洗（NaN 会污染训练）")


def read(path) -> pa.Table:
    """读取 Parquet 并立即校验。"""
    table = pq.read_table(str(path))
    validate(table)
    return table


def write(table: pa.Table, path, note: str | None = None) -> None:
    """写出 Parquet；``note`` 写入 schema metadata（示例数据集须标注「仅演示」）。"""
    validate(table)
    meta = dict(table.schema.metadata or {})
    meta[b"rtklib-ai"] = b"obs_corrector_v1"
    if note:
        meta[b"note"] = note.encode("utf-8")
    pq.write_table(table.replace_schema_metadata(meta), str(path))


def features(table: pa.Table) -> np.ndarray:
    """取 17 维特征矩阵 ``[n, 17]``（float32）。"""
    cols = [np.asarray(table[c].to_numpy(zero_copy_only=False), dtype=np.float32)
            for c in FEATURE_COLUMNS]
    return np.stack(cols, axis=1)


def labels(table: pa.Table) -> np.ndarray:
    """取标签向量 ``[n]``（float32，单位为米）。"""
    return np.asarray(table[LABEL_COLUMN].to_numpy(zero_copy_only=False), dtype=np.float32)


class Split:
    """两层切分结果。``train``/``val``/``test`` 三者站互不重叠（§7.3）。"""

    def __init__(self, train, val, test, train_stations, val_stations, test_stations,
                 train_days, val_days):
        self.train = train
        self.val = val
        self.test = test
        self.train_stations = train_stations
        self.val_stations = val_stations
        self.test_stations = test_stations
        self.train_days = train_days
        self.val_days = val_days

    def summary(self) -> str:
        held_out = ", ".join(f"{st}:{','.join(d)}" for st, d in self.val_days)
        return (f"train: {len(self.train)} 行 / 站 {self.train_stations}\n"
                f"val  : {len(self.val)} 行 / 站 {self.val_stations}"
                f" + 训练站内验证日 [{held_out}]\n"
                f"test : {len(self.test)} 行 / 站 {self.test_stations}（未参与训练与早停）")


def two_layer_split(table: pa.Table, seed: int = 0, n_test_stations: int = 2,
                    n_val_stations: int = 1, val_day_fraction: float = 0.25) -> Split:
    """两层切分：第一层按 ``station``，第二层在训练站内按 ``day``（§7.3）。

    - 测试站：≥ ``n_test_stations``，不参与训练、也不参与早停；
    - 验证站：≥ ``n_val_stations``，与训练站/测试站互不重叠；
    - 训练站内再按 ``day`` 切出「验证日」，用于早停与超参选择（站内不跨站泄漏）。
    """
    stations = sorted({str(v) for v in table["station"].to_pylist()})
    if len(stations) < n_test_stations + n_val_stations + 1:
        raise ValueError(f"站数 {len(stations)} 不足以做两层切分："
                         f"至少需要 {n_test_stations + n_val_stations + 1} 个不同站")

    rng = np.random.default_rng(seed)
    order = rng.permutation(len(stations))
    shuffled = [stations[i] for i in order]
    test_stations = shuffled[:n_test_stations]
    val_stations = shuffled[n_test_stations:n_test_stations + n_val_stations]
    train_stations = shuffled[n_test_stations + n_val_stations:]

    station_col = table["station"].to_pylist()
    day_col = table["day"].to_pylist()

    train_mask = np.zeros(len(table), dtype=bool)
    val_mask = np.zeros(len(table), dtype=bool)
    test_mask = np.zeros(len(table), dtype=bool)

    train_station_set = set(train_stations)
    val_station_set = set(val_stations)
    test_station_set = set(test_stations)

    train_days, val_days = [], []
    for st in train_stations:
        days = sorted({d for s, d in zip(station_col, day_col) if s == st})
        if len(days) < 2:
            raise ValueError(f"训练站 {st} 只有 {len(days)} 天，无法做站内按天切分（§7.3）")
        n_val = max(1, int(round(len(days) * val_day_fraction)))
        val_days_local = days[-n_val:]
        train_days_local = days[:-n_val]
        train_days.append((st, train_days_local))
        val_days.append((st, val_days_local))

    for k, (s, d) in enumerate(zip(station_col, day_col)):
        if s in test_station_set:
            test_mask[k] = True
        elif s in val_station_set:
            val_mask[k] = True
        elif s in train_station_set:
            local_val = [vd for st, vd in val_days if st == s][0]
            if d in local_val:
                val_mask[k] = True
            else:
                train_mask[k] = True

    return Split(
        train=table.filter(pa.array(train_mask)),
        val=table.filter(pa.array(val_mask)),
        test=table.filter(pa.array(test_mask)),
        train_stations=train_stations,
        val_stations=val_stations,
        test_stations=test_stations,
        train_days=train_days,
        val_days=val_days,
    )


def make_demo(n_stations: int = 6, n_days: int = 3, n_epochs: int = 150,
              n_sat: int = 12, seed: int = 20261107) -> pa.Table:
    """生成「仅演示」数据集：证明链路可跑通，**不承诺模型效果**（§6.3）。

    特征按 §5.3 的物理取值范围采样（高度角 15–90°、SNR 20–50 dBHz 等），
    标签由一条含多路径/低仰角/周跳项的隐函数生成，使模型有可学结构。
    """
    rng = np.random.default_rng(seed)
    n = n_stations * n_days * n_epochs * n_sat

    station = np.repeat([f"S{i}" for i in range(n_stations)], n_days * n_epochs * n_sat)
    day = np.tile(np.repeat([f"D{i}" for i in range(n_days)], n_epochs * n_sat), n_stations)
    epoch = np.tile(np.repeat(np.arange(n_epochs), n_sat), n_stations * n_days)
    sat = np.tile(np.tile(np.arange(1, n_sat + 1), n_epochs), n_stations * n_days)

    el = rng.uniform(15.0, 90.0, n)
    az = rng.uniform(0.0, 360.0, n)
    snr_rover = 20.0 + 30.0 * (el / 90.0) + rng.normal(0.0, 2.0, n)
    snr_base = 20.0 + 30.0 * (el / 90.0) + rng.normal(0.0, 2.0, n)
    resp = rng.normal(0.0, 0.6, n)
    resc = resp + rng.normal(0.0, 0.05, n)
    lock = np.where(rng.random(n) < 0.05, 0.0, rng.integers(0, 200, n))
    slip = (rng.random(n) < 0.03).astype(np.float64)
    half = (rng.random(n) < 0.01).astype(np.float64)
    gf = rng.normal(0.0, 0.4, n)
    vsat = np.ones(n)
    fix = rng.integers(0, 4, n).astype(np.float64)
    sys_id = rng.integers(0, 4, n).astype(np.float64)
    resp_win_mean = resp + rng.normal(0.0, 0.15, n)
    resp_win_std = np.abs(rng.normal(0.3, 0.1, n))
    resc_win_mean = resc + rng.normal(0.0, 0.05, n)
    hist_len = rng.choice([0.2, 0.4, 0.6, 0.8, 1.0], n)

    truth = (
        1.1 * np.tanh((32.0 - snr_rover) / 6.0)
        + 0.6 * np.tanh(resp / 1.5)
        + 0.4 * np.cos(np.deg2rad(az)) * np.exp(-el / 45.0)
        + 0.5 * slip - 0.3 * gf
        + 0.2 * np.sin(2.0 * np.deg2rad(el))
    )
    label = truth + rng.normal(0.0, 0.25, n)

    table = pa.Table.from_arrays(
        [pa.array(col, type=pa.float32()) for col in (
            el, az, snr_rover, snr_base, resp, resc, lock, slip, half, gf,
            vsat, fix, sys_id, resp_win_mean, resp_win_std, resc_win_mean, hist_len,
        )]
        + [
            pa.array(label, type=pa.float32()),
            pa.array(epoch, type=pa.int64()),
            pa.array(sat, type=pa.int32()),
            pa.array(np.zeros(n, dtype=np.int8), type=pa.int8()),
            pa.array(station, type=pa.string()),
            pa.array(day, type=pa.string()),
            pa.array(np.ones(n, dtype=np.int8), type=pa.int8()),
        ],
        schema=_SCHEMA,
    )
    return table