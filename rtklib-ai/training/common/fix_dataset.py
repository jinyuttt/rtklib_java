"""接口一（固定校验）训练数据格式：统一 Parquet + 两层切分（设计文档 §5.1 / §7.3）。

一行 = 一个「历元 × 固定解尝试」，即 core 侧 ``aiAcceptFix()`` 被调用的那一刻
（``nb > 1`` 且 ``valpos`` 通过）。**未进入固定分支的历元不得入库**：接口一在那些
历元上根本不会被调用，混入会污染特征分布。

必须遵守的两条硬约束与接口三相同：

1. ``station`` 与 ``day`` 是切分依据，缺失的数据**不得进入评估**；
2. 切分复用 :func:`dataset.two_layer_split`：先按站、再按天，**禁止随机切分**。

标签语义：``label = 1.0`` 表示该次固定**正确**，``0.0`` 表示**错误固定**。
错误固定才是接口一要拦的对象，因此它是训练中需要加权的少数类。
"""

from __future__ import annotations

import numpy as np
import pyarrow as pa
import pyarrow.parquet as pq

try:
    from training.common.dataset import two_layer_split
except ImportError:  # 允许直接以脚本方式运行
    import sys
    from pathlib import Path

    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
    from training.common.dataset import two_layer_split

DIM = 6
FEATURE_COLUMNS = [f"f{i}" for i in range(DIM)]
FEATURE_NAMES = ("adr", "prr", "pcr", "ratio", "ns_fixed", "pdop")
LABEL_COLUMN = "label"
META_COLUMNS = ("epoch", "station", "day", "n_code_rows", "n_phase_rows")
REQUIRED_COLUMNS = tuple(FEATURE_COLUMNS) + (LABEL_COLUMN,) + META_COLUMNS

_SCHEMA = pa.schema(
    [(c, pa.float32()) for c in FEATURE_COLUMNS]
    + [
        (LABEL_COLUMN, pa.float32()),
        ("epoch", pa.int64()),
        ("station", pa.string()),
        ("day", pa.string()),
        ("n_code_rows", pa.int32()),
        ("n_phase_rows", pa.int32()),
    ]
)


def schema() -> pa.Schema:
    """数据集 schema（列式、可校验）。"""
    return _SCHEMA


def validate(table: pa.Table) -> None:
    """校验列齐全、切分依据非空、标签为 0/1 二值。

    ``n_code_rows`` / ``n_phase_rows`` 用于判定 ``pcr`` / ``prr`` 是否为占位 0.0
    （与 :class:`FixQualityFeatures` 的退化处理对齐），故必须随行保存。
    """
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
    if not np.all((label == 0.0) | (label == 1.0)):
        raise ValueError("标签必须是 0/1（0=错误固定，1=固定正确），"
                         "其它取值说明标签构造有误")


def read(path) -> pa.Table:
    """读取 Parquet 并立即校验。"""
    table = pq.read_table(str(path))
    validate(table)
    return table


def write(table: pa.Table, path, note: str | None = None) -> None:
    """写出 Parquet；``note`` 写入 schema metadata（示例数据集须标注「仅演示」）。"""
    validate(table)
    meta = dict(table.schema.metadata or {})
    meta[b"rtklib-ai"] = b"fix_validator_v1"
    if note:
        meta[b"note"] = note.encode("utf-8")
    pq.write_table(table.replace_schema_metadata(meta), str(path))


def features(table: pa.Table) -> np.ndarray:
    """取 6 维特征矩阵 ``[n, 6]``（float32），列序与 ``FixQualityFeatures`` 逐位一致。"""
    cols = [np.asarray(table[c].to_numpy(zero_copy_only=False), dtype=np.float32)
            for c in FEATURE_COLUMNS]
    return np.stack(cols, axis=1)


def labels(table: pa.Table) -> np.ndarray:
    """取标签向量 ``[n]``（float32，1=固定正确，0=错误固定）。"""
    return np.asarray(table[LABEL_COLUMN].to_numpy(zero_copy_only=False), dtype=np.float32)


def split(table: pa.Table, seed: int = 0, n_test_stations: int = 2,
          n_val_stations: int = 1, val_day_fraction: float = 0.25):
    """两层切分（复用 :func:`dataset.two_layer_split`，两者共用 ``station``/``day`` 语义）。"""
    return two_layer_split(table, seed=seed, n_test_stations=n_test_stations,
                           n_val_stations=n_val_stations, val_day_fraction=val_day_fraction)


def make_demo(n_stations: int = 6, n_days: int = 3, n_epochs: int = 150,
              seed: int = 20261107) -> pa.Table:
    """生成「仅演示」数据集：证明链路可跑通，**不承诺模型效果**（§6.3）。

    特征按 §5.1 的物理取值范围采样，标签由一条含「模糊度差/残差/ratio/冗余度」
    的隐函数生成，使模型有可学结构；错误固定比例约 5%–15%，接近真实短基线场景。
    """
    rng = np.random.default_rng(seed)
    n = n_stations * n_days * n_epochs

    station = np.repeat([f"S{i}" for i in range(n_stations)], n_days * n_epochs)
    day = np.tile(np.repeat([f"D{i}" for i in range(n_days)], n_epochs), n_stations)
    epoch = np.tile(np.arange(n_epochs), n_stations * n_days)

    adr = np.abs(rng.normal(0.8, 0.5, n))
    prr = np.abs(rng.normal(0.05, 0.03, n))
    pcr = np.abs(rng.normal(0.9, 0.6, n))
    ratio = 1.0 + np.abs(rng.normal(2.0, 1.0, n))
    ns_fixed = np.clip(rng.normal(0.75, 0.15, n), 0.2, 1.0)
    pdop = np.abs(rng.normal(2.6, 0.9, n)) + 1.0
    n_code_rows = rng.integers(6, 24, n).astype(np.int32)
    n_phase_rows = rng.integers(6, 24, n).astype(np.int32)

    logit_wrong = (
        -3.2
        + 1.5 * np.tanh((adr - 1.0) / 0.6)
        + 1.1 * np.tanh((prr - 0.05) / 0.03)
        + 0.9 * np.tanh((pcr - 1.0) / 0.7)
        - 1.4 * np.tanh((ratio - 2.5) / 1.2)
        + 1.0 * np.tanh((0.75 - ns_fixed) / 0.2)
        + 0.7 * np.tanh((pdop - 3.0) / 1.2)
    )
    p_wrong = 1.0 / (1.0 + np.exp(-logit_wrong))
    label = (rng.random(n) >= p_wrong).astype(np.float32)

    table = pa.Table.from_arrays(
        [pa.array(col, type=pa.float32()) for col in (
            adr, prr, pcr, ratio, ns_fixed, pdop,
        )]
        + [
            pa.array(label, type=pa.float32()),
            pa.array(epoch, type=pa.int64()),
            pa.array(station, type=pa.string()),
            pa.array(day, type=pa.string()),
            pa.array(n_code_rows, type=pa.int32()),
            pa.array(n_phase_rows, type=pa.int32()),
        ],
        schema=_SCHEMA,
    )
    return table