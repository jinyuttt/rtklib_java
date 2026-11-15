"""PPP 接口 A / B 训练数据格式与两层切分（设计文档 §12.2，A / B 共用）。

两个接口的数据集**结构不同、语义不同，禁止混用**：

================  ==============================================  ==============================
接口               一行是什么                                      标签 / 目标
================  ==============================================  ==============================
A 解有效性判别     一个历元（``aiApplySolutionValidator`` 被调用）    ``label`` 二值：该历元解是否可信
B 观测方差缩放     一个 ``(sat, frq)`` 行（``ns × nf`` 行序）           ``v_norm`` 连续量：``残差 / 名义 σ``
================  ==============================================  ==============================

两条硬约束与接口一 / 三完全相同（与 ``training/common/dataset.py`` 同源）：

1. ``station`` 与 ``day`` 是切分依据，缺失的数据**不得进入评估**；
2. 切分复用 :func:`dataset.two_layer_split`：先按站、再按天，**禁止随机切分**。

**A 与 B 的残差语义不同，训练集不可互换、不可拼接**（设计文档 §4.3）：A 的
``res_rms`` 来自 PPP 非差验后残差汇总，B 的 ``resp`` / ``resc`` 是逐星非差残差；
RTK 侧接口三的双差残差更不能拿来训 B——双差已消掉接收机钟差与卫星钟差，分布不同。
"""

from __future__ import annotations

import sys
from pathlib import Path

import numpy as np
import pyarrow as pa
import pyarrow.parquet as pq

try:
    from training.common.dataset import two_layer_split
except ImportError:  # 允许直接以脚本方式运行
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
    from training.common.dataset import two_layer_split

# --------------------------------------------------------------------------- 接口 A

A_DIM = 10
A_FEATURE_COLUMNS = [f"f{i}" for i in range(A_DIM)]
#: 列序与 ``org.rtklib.java.ai.feature.PppSolutionFeatures`` 逐位一致。
A_FEATURE_NAMES = (
    "sig_x", "sig_y", "sig_z", "sig_3d",
    "res_rms", "res_max", "el_mean", "snr_mean",
    "innov_rms", "bias_frac",
)
A_LABEL_COLUMN = "label"

# --------------------------------------------------------------------------- 接口 B

B_DIM = 12
B_FEATURE_COLUMNS = [f"f{i}" for i in range(B_DIM)]
#: 列序与 ``org.rtklib.java.ai.feature.PppVarianceFeatures`` 逐位一致。
B_FEATURE_NAMES = (
    "el", "az", "snr", "resp", "resc",
    "abs_resp", "abs_resc", "lock",
    "slip", "half", "vsat", "frq",
)
B_TARGET_COLUMN = "v_norm"

# --------------------------------------------------------------------------- 公共元数据

_META_SCHEMA = [
    ("epoch", pa.int64()),
    ("station", pa.string()),
    ("day", pa.string()),
    ("run_id", pa.string()),
    ("run_epochs", pa.int32()),
]
_META_COLUMNS = tuple(name for name, _ in _META_SCHEMA)

A_EXTRA_SCHEMA = [
    ("pos_err_3d", pa.float32()),
    ("ns", pa.int32()),
    ("deterministic_degraded", pa.int8()),
]
A_REQUIRED_COLUMNS = (tuple(A_FEATURE_COLUMNS) + (A_LABEL_COLUMN,) + _META_COLUMNS
                      + tuple(name for name, _ in A_EXTRA_SCHEMA))

B_EXTRA_SCHEMA = [
    ("sat", pa.int32()),
    ("frq", pa.int32()),
    ("nf", pa.int32()),
    ("sigma", pa.float32()),
]
B_REQUIRED_COLUMNS = (tuple(B_FEATURE_COLUMNS) + (B_TARGET_COLUMN,) + _META_COLUMNS
                      + tuple(name for name, _ in B_EXTRA_SCHEMA))

_A_SCHEMA = pa.schema(
    [(c, pa.float32()) for c in A_FEATURE_COLUMNS]
    + [(A_LABEL_COLUMN, pa.float32())]
    + _META_SCHEMA
    + A_EXTRA_SCHEMA
)

_B_SCHEMA = pa.schema(
    [(c, pa.float32()) for c in B_FEATURE_COLUMNS]
    + [(B_TARGET_COLUMN, pa.float32())]
    + _META_SCHEMA
    + B_EXTRA_SCHEMA
)


# --------------------------------------------------------------------------- 公共校验


def _validate_meta(table: pa.Table, required: tuple) -> None:
    missing = [c for c in required if c not in table.column_names]
    if missing:
        raise ValueError(f"数据集缺少必需列: {missing}")

    for name in ("station", "day"):
        values = table[name].to_pylist()
        if any(v is None or str(v).strip() == "" for v in values):
            raise ValueError(f"列 {name} 存在空值：切分依据缺失的数据不得进入评估（§7.3）")

    values = table["run_id"].to_pylist()
    if any(v is None or str(v).strip() == "" for v in values):
        raise ValueError("列 run_id 存在空值：降级率/膨胀率的分母依赖运行身份")

    epochs = np.asarray(table["run_epochs"].to_numpy(zero_copy_only=False), dtype=np.int64)
    if epochs.size and np.any(epochs <= 0):
        raise ValueError("列 run_epochs 存在非正值：该 run 的历元数必须 ≥ 1")

    if len(table) == 0:
        raise ValueError("数据集为空")


def _write(table: pa.Table, path, note: str | None, tag: bytes) -> None:
    meta = dict(table.schema.metadata or {})
    meta[b"rtklib-ai"] = tag
    if note:
        meta[b"note"] = note.encode("utf-8")
    pq.write_table(table.replace_schema_metadata(meta), str(path))


def _features(table: pa.Table, columns: list) -> np.ndarray:
    cols = [np.asarray(table[c].to_numpy(zero_copy_only=False), dtype=np.float32)
            for c in columns]
    return np.stack(cols, axis=1)


def total_epochs(table: pa.Table) -> int:
    """该表覆盖的**历元总数**（降级率 / 膨胀率的分母）。

    按运行身份 ``(station, day, run_id)`` 去重后累加 ``run_epochs``：同一 run 的
    每一行都带同一个 ``run_epochs``，直接求和会把分母放大成 ``行数 × 历元数``。
    接口 B 一行是 ``(sat, frq)`` 而非历元，这个去重尤其必要。
    """
    seen: dict = {}
    for st, dy, rid, n in zip(table["station"].to_pylist(), table["day"].to_pylist(),
                              table["run_id"].to_pylist(),
                              table["run_epochs"].to_pylist()):
        seen[(st, dy, rid)] = int(n)
    return int(sum(seen.values()))


def station_epochs(table: pa.Table) -> dict:
    """逐站历元总数（分站指标的分母）。"""
    seen: dict = {}
    for st, dy, rid, n in zip(table["station"].to_pylist(), table["day"].to_pylist(),
                              table["run_id"].to_pylist(),
                              table["run_epochs"].to_pylist()):
        seen[(st, dy, rid)] = (st, int(n))
    out: dict = {}
    for st, n in seen.values():
        out[st] = out.get(st, 0) + n
    return out


def split(table: pa.Table, seed: int = 0, n_test_stations: int = 2,
          n_val_stations: int = 1, val_day_fraction: float = 0.25,
          test_candidates: "list[str] | None" = None,
          val_candidates: "list[str] | None" = None):
    """两层切分（A / B 共用，语义同 :func:`dataset.two_layer_split`）。"""
    return two_layer_split(table, seed=seed, n_test_stations=n_test_stations,
                           n_val_stations=n_val_stations,
                           val_day_fraction=val_day_fraction,
                           test_candidates=test_candidates,
                           val_candidates=val_candidates)


# --------------------------------------------------------------------------- 接口 A API


def schema_a() -> pa.Schema:
    return _A_SCHEMA


def validate_a(table: pa.Table) -> None:
    """校验列齐全、切分依据非空、``label`` 为 0/1 二值。

    ``label = 1`` 表示该历元解可信，``0`` 表示不可信（应被降级）。不可信历元是少数类，
    训练侧按 ``--invalid-weight`` 加权，否则模型退化成「全部放行」。
    """
    _validate_meta(table, A_REQUIRED_COLUMNS)

    for name in A_FEATURE_COLUMNS:
        v = np.asarray(table[name].to_numpy(zero_copy_only=False), dtype=np.float64)
        if not np.all(np.isfinite(v)):
            raise ValueError(f"特征列 {name} 含非有限值：NaN/Inf 会污染整批推理")

    label = np.asarray(table[A_LABEL_COLUMN].to_numpy(zero_copy_only=False), dtype=np.float64)
    if not np.all(np.isfinite(label)):
        raise ValueError("标签列存在非有限值，须在采集阶段清洗")
    if not np.all((label == 0.0) | (label == 1.0)):
        raise ValueError("标签必须是 0/1（1=解可信，0=应降级），其它取值说明标签构造有误")


def read_a(path) -> pa.Table:
    table = pq.read_table(str(path))
    validate_a(table)
    return table


def write_a(table: pa.Table, path, note: str | None = None) -> None:
    validate_a(table)
    _write(table, path, note, b"ppp_solution_validator_v1")


def features_a(table: pa.Table) -> np.ndarray:
    """``[n, 10]``，列序与 ``PppSolutionFeatures`` 逐位一致。"""
    return _features(table, A_FEATURE_COLUMNS)


def labels_a(table: pa.Table) -> np.ndarray:
    """``[n]``，1=解可信，0=应降级。"""
    return np.asarray(table[A_LABEL_COLUMN].to_numpy(zero_copy_only=False), dtype=np.float32)


def pos_err_a(table: pa.Table) -> np.ndarray:
    """三维定位误差真值（米），仅用于报告与阈值标定，**不得**作为模型输入。"""
    return np.asarray(table["pos_err_3d"].to_numpy(zero_copy_only=False), dtype=np.float64)


def deterministic_degraded_a(table: pa.Table) -> np.ndarray:
    """确定性 qr 基线是否已把该历元降级（归因字段，非特征，§3.5）。"""
    return np.asarray(table["deterministic_degraded"].to_numpy(zero_copy_only=False),
                      dtype=np.int64)


# --------------------------------------------------------------------------- 接口 B API


def schema_b() -> pa.Schema:
    return _B_SCHEMA


def validate_b(table: pa.Table) -> None:
    """校验列齐全、切分依据非空、``v_norm`` 有限、``0 <= frq < nf``。

    ``v_norm`` 是**归一化残差**（``resp / sigma``，无量纲）：它让训练目标与
    ``PppVarianceScaler`` 的输出（方差膨胀因子，无量纲）处于同一尺度，
    跨站 / 跨系统共用一套损失，不必按系统改 σ 量级。
    """
    _validate_meta(table, B_REQUIRED_COLUMNS)

    for name in B_FEATURE_COLUMNS:
        v = np.asarray(table[name].to_numpy(zero_copy_only=False), dtype=np.float64)
        if not np.all(np.isfinite(v)):
            raise ValueError(f"特征列 {name} 含非有限值：NaN/Inf 会污染整批推理")

    target = np.asarray(table[B_TARGET_COLUMN].to_numpy(zero_copy_only=False), dtype=np.float64)
    if not np.all(np.isfinite(target)):
        raise ValueError("目标列 v_norm 含非有限值：sigma 为 0 的行须在采集阶段剔除")

    nf = np.asarray(table["nf"].to_numpy(zero_copy_only=False), dtype=np.int64)
    frq = np.asarray(table["frq"].to_numpy(zero_copy_only=False), dtype=np.int64)
    if np.any(nf <= 0):
        raise ValueError("列 nf 存在非正值")
    if np.any((frq < 0) | (frq >= nf)):
        raise ValueError("列 frq 越界：必须满足 0 <= frq < nf（行序契约 satIndex*nf+frq）")


def read_b(path) -> pa.Table:
    table = pq.read_table(str(path))
    validate_b(table)
    return table


def write_b(table: pa.Table, path, note: str | None = None) -> None:
    validate_b(table)
    _write(table, path, note, b"ppp_variance_scaler_v1")


def features_b(table: pa.Table) -> np.ndarray:
    """``[rows, 12]``，列序与 ``PppVarianceFeatures`` 逐位一致。"""
    return _features(table, B_FEATURE_COLUMNS)


def target_b(table: pa.Table) -> np.ndarray:
    """``[rows]``，归一化残差 ``v / σ``（训练目标，非二值标签）。"""
    return np.asarray(table[B_TARGET_COLUMN].to_numpy(zero_copy_only=False), dtype=np.float32)


def sigma_b(table: pa.Table) -> np.ndarray:
    """``[rows]``，名义 σ（米），仅用于报告与「等效米制残差」还原。"""
    return np.asarray(table["sigma"].to_numpy(zero_copy_only=False), dtype=np.float64)


# --------------------------------------------------------------------------- 演示数据


def make_demo_a(n_stations: int = 6, n_days: int = 3, n_epochs: int = 200,
                seed: int = 20261114, invalid_fraction: float = 0.12) -> pa.Table:
    """生成「仅演示」A 数据集：证明链路可跑通，**不承诺模型效果**（§12.1）。

    特征按 PPP 收敛过程的物理取值范围采样；``pos_err_3d`` 由位置标准差乘一个
    对数正态因子得到（误差 ≫ 预测 σ 正是需要降级的场景），标签按误差超限生成。
    为保持类别比例稳定，取误差最大的 ``invalid_fraction`` 为不可信历元。
    """
    rng = np.random.default_rng(seed)
    n = n_stations * n_days * n_epochs

    station = np.repeat([f"S{i}" for i in range(n_stations)], n_days * n_epochs)
    day = np.tile(np.repeat([f"D{i}" for i in range(n_days)], n_epochs), n_stations)
    epoch = np.tile(np.arange(n_epochs), n_stations * n_days)

    sig = np.abs(rng.lognormal(mean=-2.6, sigma=0.9, size=(n, 3)))
    sig_x, sig_y, sig_z = sig[:, 0], sig[:, 1], sig[:, 2]
    sig_3d = np.sqrt(sig_x ** 2 + sig_y ** 2 + sig_z ** 2)

    res_rms = np.abs(rng.normal(0.9, 0.5, n))
    res_max = res_rms * (1.2 + np.abs(rng.normal(0.0, 0.5, n)))
    el_mean = np.clip(rng.normal(0.62, 0.18, n), 0.05, 1.5)
    snr_mean = np.clip(rng.normal(42.0, 6.0, n), 15.0, 60.0)
    innov_rms = np.abs(rng.normal(0.35, 0.30, n))
    bias_frac = np.clip(rng.beta(5.0, 2.0, n), 0.0, 1.0)

    pos_err_3d = sig_3d * np.abs(rng.lognormal(mean=0.15, sigma=0.75, size=n))

    k = int(round(n * float(invalid_fraction)))
    label = np.ones(n, dtype=np.float32)
    if k > 0:
        label[np.argsort(pos_err_3d)[-k:]] = 0.0

    # 确定性基线（qr 门禁）的模拟降级：一半落在不可信历元、一半落在可信历元，
    # 后者即基线自身的误降级。这样演示数据才真正走到 metrics 的归因字段
    # （baseline_degraded_rate / overlap_with_baseline），否则该分支在演示里是死代码。
    det = np.zeros(n, dtype=np.int8)
    if k > 0:
        invalid_idx = np.argsort(pos_err_3d)[-k:]
        trusted_idx = np.setdiff1d(np.arange(n), invalid_idx)
        half = min(k // 2, invalid_idx.size)
        if half > 0:
            det[rng.choice(invalid_idx, size=half, replace=False)] = 1
        rest = min(k - half, trusted_idx.size)
        if rest > 0:
            det[rng.choice(trusted_idx, size=rest, replace=False)] = 1

    return pa.table({
        "f0": sig_x.astype(np.float32),
        "f1": sig_y.astype(np.float32),
        "f2": sig_z.astype(np.float32),
        "f3": sig_3d.astype(np.float32),
        "f4": res_rms.astype(np.float32),
        "f5": res_max.astype(np.float32),
        "f6": el_mean.astype(np.float32),
        "f7": snr_mean.astype(np.float32),
        "f8": innov_rms.astype(np.float32),
        "f9": bias_frac.astype(np.float32),
        A_LABEL_COLUMN: label,
        "epoch": epoch.astype(np.int64),
        "station": station,
        "day": day,
        "run_id": np.repeat("r0", n),
        "run_epochs": np.full(n, n_epochs, dtype=np.int32),
        "pos_err_3d": pos_err_3d.astype(np.float32),
        "ns": np.full(n, 18, dtype=np.int32),
        "deterministic_degraded": det,
    }, schema=_A_SCHEMA)


def make_demo_b(n_stations: int = 6, n_days: int = 3, n_epochs: int = 100,
                ns: int = 12, nf: int = 2, seed: int = 20261114) -> pa.Table:
    """生成「仅演示」B 数据集：行数 = ``站 × 天 × 历元 × ns × nf``。

    ``v_norm`` 由「仰角低 / SNR 低 / 周跳 / 半周」驱动一个潜方差因子生成，
    使模型有可学结构（该结构不是真实物理结论，仅用于验证链路与可训练性）。
    """
    rng = np.random.default_rng(seed)
    n_epoch = n_stations * n_days * n_epochs
    rows = n_epoch * ns * nf

    station = np.repeat([f"S{i}" for i in range(n_stations)], n_days * n_epochs * ns * nf)
    day = np.tile(np.repeat([f"D{i}" for i in range(n_days)], n_epochs * ns * nf), n_stations)
    epoch = np.tile(np.repeat(np.arange(n_epochs), ns * nf), n_stations * n_days)
    sat = np.tile(np.repeat(np.arange(1, ns + 1), nf), n_epoch)
    frq = np.tile(np.arange(nf), n_epoch * ns)

    el = np.clip(rng.normal(0.55, 0.22, rows), 0.02, 1.55)
    az = rng.uniform(0.0, 2.0 * np.pi, rows)
    snr = np.clip(rng.normal(40.0 - 25.0 * (0.55 - el), 4.0, rows), 12.0, 60.0)
    slip = (rng.random(rows) < 0.02).astype(np.float32)
    half = (rng.random(rows) < 0.05).astype(np.float32)
    vsat = np.ones(rows, dtype=np.float32)

    sigma = np.clip(0.30 + 0.9 * np.exp(-el * 2.0) + 0.02 * (45.0 - snr), 0.12, 3.0)
    inflate = (1.0
               + 1.8 * np.exp(-el * 2.5)
               + 0.9 * np.clip((38.0 - snr) / 20.0, 0.0, 1.0)
               + 3.0 * slip
               + 0.6 * half)
    v_norm = rng.normal(0.0, np.sqrt(inflate))
    resp = (v_norm * sigma).astype(np.float32)
    resc = (rng.normal(0.0, 0.02, rows) * sigma).astype(np.float32)

    return pa.table({
        "f0": el.astype(np.float32),
        "f1": az.astype(np.float32),
        "f2": snr.astype(np.float32),
        "f3": resp.astype(np.float32),
        "f4": resc.astype(np.float32),
        "f5": np.abs(resp).astype(np.float32),
        "f6": np.abs(resc).astype(np.float32),
        "f7": rng.uniform(0.0, 600.0, rows).astype(np.float32),
        "f8": slip.astype(np.float32),
        "f9": half.astype(np.float32),
        "f10": vsat.astype(np.float32),
        "f11": frq.astype(np.float32),
        B_TARGET_COLUMN: v_norm.astype(np.float32),
        "epoch": epoch.astype(np.int64),
        "station": station,
        "day": day,
        "run_id": np.repeat("r0", rows),
        "run_epochs": np.full(rows, n_epochs, dtype=np.int32),
        "sat": sat.astype(np.int32),
        "frq": frq.astype(np.int32),
        "nf": np.full(rows, nf, dtype=np.int32),
        "sigma": sigma.astype(np.float32),
    }, schema=_B_SCHEMA)