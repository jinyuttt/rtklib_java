"""接口三（观测改正）训练数据格式：统一 Parquet + 两层切分（设计文档 §6.2 / §7.3）。

一行 = 一个「历元 × 卫星 × 频点」，列名与 §5.3 的 17 维特征逐位对应。

必须遵守的两条硬约束：

1. ``station`` 与 ``day`` 是切分依据，缺失的数据**不得进入评估**；
2. 切分必须是「先按站、再按天」的两层切分，**禁止随机切分**（相邻历元高度相关，
   随机切分会让同一段几何/大气同时出现在训练与测试里，评估结果虚高）。
"""

from __future__ import annotations

from pathlib import Path

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
    parent = Path(path).parent
    if str(parent) and not parent.exists():
        parent.mkdir(parents=True, exist_ok=True)
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
                    n_val_stations: int = 1, val_day_fraction: float = 0.25,
                    test_candidates: "list[str] | None" = None,
                    val_candidates: "list[str] | None" = None) -> Split:
    """两层切分：第一层按 ``station``，第二层在训练站内按 ``day``（§7.3）。

    - 测试站：≥ ``n_test_stations``，不参与训练、也不参与早停；
    - 验证站：≥ ``n_val_stations``，与训练站/测试站互不重叠；
    - 训练站内再按 ``day`` 切出「验证日」，用于早停与超参选择（站内不跨站泄漏）。

    ``test_candidates`` 限定测试站的候选池（仍按 ``seed`` 随机挑选）。用于 G-1/G-2
    数据可行性前置：错误固定占 AR 历元比例过高时，剔除 30% 错误固定所需历元数会
    超过 G-2 的 1pp 预算，两条判据数学互斥，这类站不适合做 G-3 的判定站。候选池
    只由 A 组（未干预）标签决定，与模型输出无关，因此不构成对模型结果的挑选。

    ``val_candidates`` 限定**阈值标定站**的候选池，道理相同但方向相反：若拿一个
    G-1 数学不可行的站来标定阈值，它在 1pp 预算内的最优相对下降本来就到不了 30%，
    标出来的阈值会系统性偏保守，部署到可行站时白白浪费预算。标定站必须与判据
    目标同口径，因此也限定在可判定站内。
    """
    stations = sorted({str(v) for v in table["station"].to_pylist()})
    if len(stations) < n_test_stations + n_val_stations + 1:
        raise ValueError(f"站数 {len(stations)} 不足以做两层切分："
                         f"至少需要 {n_test_stations + n_val_stations + 1} 个不同站")

    rng = np.random.default_rng(seed)
    order = rng.permutation(len(stations))
    shuffled = [stations[i] for i in order]

    pool = set(test_candidates) if test_candidates else set(stations)
    test_pool = [s for s in shuffled if s in pool]
    if len(test_pool) < n_test_stations:
        raise ValueError(f"测试站候选池仅 {len(test_pool)} 个站，"
                         f"不足 {n_test_stations} 个")
    test_stations = test_pool[:n_test_stations]
    rest = [s for s in shuffled if s not in set(test_stations)]
    if len(rest) < n_val_stations + 1:
        raise ValueError(f"扣除测试站后仅 {len(rest)} 个站，不足以再切验证站与训练站")
    if val_candidates:
        val_pool = [s for s in rest if s in set(val_candidates)]
        if len(val_pool) < n_val_stations:
            raise ValueError(f"阈值标定站候选池仅 {len(val_pool)} 个站，"
                             f"不足 {n_val_stations} 个（可判定站太少，须扩数据或放宽 G-2）")
        val_stations = val_pool[:n_val_stations]
    else:
        val_stations = rest[:n_val_stations]
    train_stations = [s for s in rest if s not in set(val_stations)]

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

    特征按 §5.3 的物理取值范围采样，且**均值/标准差对齐真实单频链路实测分布**
    （{@code urban_rover_20000719.17o} 上 10 万行的实测值）：归一化参数只在训练集上
    拟合，若训练集与推理侧分布不一致，归一化后的输入会整体偏移（z 漂移）甚至放大
    （std 不匹配），模型输出随之失真。因此演示集也必须贴近推理侧，否则「接口三无害」
    的验收结论会被分布失配掩盖。

    单频链路的三个「死特征」照实取 0：{@code resc}/{@code gf} 在单频模式下不被赋值
    （§5.3 f5/f9），{@code vsat} 在 ddres 之前恒为 0（接口三在 ddres 之前推理）。
    标签由一条含多路径/低仰角/周跳项的隐函数生成，**并去均值**——改正量的语义是
    零均值残差，若隐函数自带直流分量，模型会把该分量当作改正量施加到每个历元，
    在真实链路上表现为整段位置漂移（A2-3 位置 RMS 恶化的典型成因）。
    """
    rng = np.random.default_rng(seed)
    n = n_stations * n_days * n_epochs * n_sat

    station = np.repeat([f"S{i}" for i in range(n_stations)], n_days * n_epochs * n_sat)
    day = np.tile(np.repeat([f"D{i}" for i in range(n_days)], n_epochs * n_sat), n_stations)
    epoch = np.tile(np.repeat(np.arange(n_epochs), n_sat), n_stations * n_days)
    sat = np.tile(np.tile(np.arange(1, n_sat + 1), n_epochs), n_stations * n_days)

    el = np.clip(rng.normal(51.0, 23.0, n), 15.0, 85.0)
    az = rng.uniform(0.0, 360.0, n)
    snr_rover = np.clip(rng.normal(41.5, 7.8, n), 8.0, 55.0)
    snr_base = np.clip(rng.normal(46.7, 2.8, n), 38.9, 51.9)
    resp = rng.normal(0.0, 16.5, n)
    resc = np.zeros(n)
    lock = np.where(rng.random(n) < 0.675, 0.0,
                    np.minimum(rng.exponential(82.0, n), 650.0))
    slip = (rng.random(n) < 0.154).astype(np.float64)
    half = (rng.random(n) < 0.150).astype(np.float64)
    gf = np.zeros(n)
    vsat = np.zeros(n)
    fix = rng.integers(0, 4, n).astype(np.float64)
    sys_id = rng.choice(np.arange(5), size=n,
                        p=[0.40, 0.22, 0.14, 0.12, 0.12]).astype(np.float64)
    resp_win_mean = resp + rng.normal(0.0, 1.5, n)
    resp_win_std = np.where(rng.random(n) < 0.68, 0.0,
                            np.minimum(rng.exponential(0.5, n), 103.0))
    resc_win_mean = np.zeros(n)
    hist_len = rng.choice([0.2, 0.4, 0.6, 0.8], n, p=[0.78, 0.10, 0.07, 0.05])

    truth = (
        1.1 * np.tanh((41.5 - snr_rover) / 6.0)
        + 0.6 * np.tanh(resp / 16.5)
        + 0.4 * np.cos(np.deg2rad(az)) * np.exp(-el / 45.0)
        + 0.5 * slip - 0.3 * gf
        + 0.2 * np.sin(2.0 * np.deg2rad(el))
    )
    truth -= truth.mean()
    # 不可约噪声 std=1.2 m：既符合伪距改正残差的物理量级，又让 scale 头有可学的
    # 残差方差（若噪声 < 1.0 m，高斯 NLL 的最优 var 落在契约下界 1.0 以下，
    # scale 头会被钉死在 1.0，第二个输出头等于未训练）。
    label = truth + rng.normal(0.0, 1.2, n)

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