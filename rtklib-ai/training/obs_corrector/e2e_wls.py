"""方案 B：端到端坐标 Loss 的数据装配 + 可导单步 WLS。

与方案 A（人造单差标签 + NLL）的区别只有一处：**标签换成坐标**。数据管道、特征、
切分规则、模型结构、归一化、ONNX 契约全部沿用，因此 Java 侧 SPI + 外挂架构不需要
任何改动，换一个模型文件即可做 A/B 对比。

逐项对齐 Java 侧 ``RtkCore.ddres``（不对齐就会训练/推理分布错位）::

    量      本模块                                     Java 对应
    ------  ---------------------------------------   ---------------------------------
    H 行    u_j - u_ref（ENU，接收机→卫星单位向量）    H[nv*nx+k] = e_j - e_ref
    残差 r  before - H·δ，δ = x_true - x_pred          v = y - f(x_p)
    偏差 β  b_ref - b_j（仅伪距行）                    v -= aiBias[ref] - aiBias[j]
    方差 W  1/(Ri·s_ref + Rj·s_j)                      Ri *= aiScale[ref]; Rj *= aiScale[j]
    坐标    (HᵀWH)⁻¹HᵀW(r-β)                          KalmanFilter 的 Δx

三处容易踩错的地方：

1. **H 必须是 ENU 下的接收机→卫星单位向量之差**。``ddres`` 里 ``e = (rr-rs)/|rr-rs|``
   是「接收机→卫星」，而 ``v = y - f(x)`` 的偏导 ``∂r_j/∂x`` 也是「接收机→卫星」，
   所以 ``H = e_j - e_ref``。用 ``(cos el sin az, cos el cos az, sin el)`` 复原即可，
   不依赖 ECEF 卫星坐标，也不依赖接收机位置（el/az 误差在 1e-7 rad 量级）。
2. **残差要换成「相对真值」**。``before`` 是相对当前解算位置的残差，
   ``before = r + H·δ``，δ = x_true - x_pred 可能到米级（冷启动头几个历元），
   不减掉它就会把解算误差当成观测误差灌进 Loss。
3. **梯度只经 bias/scale 回传**。H、r、Ri/Rj 全部 detach：可导的是「改正量如何影响
   坐标」，不是「几何如何影响坐标」。几何若也进梯度，模型会去拟合几何本身。

关于分组（``--group``）：

- ``run``（默认）：静态站一个小时的整段历元合并成**一次** WLS，未知量仍是 3 个坐标。
  单历元解算的坐标误差 ~1 m 且几乎全是噪声，模型对它没有杠杆（梯度信号被噪声淹没，
  训出来只会是「D 组 scale-only」的翻版）；合并到整段后噪声按 √n 压下去、系统偏差
  留下来，坐标 Loss 才有可学的信号。
- ``epoch``：逐历元单步 WLS，与 TDL-GNSS 的写法一致，保留作对照。
"""

from __future__ import annotations

import csv
import math
from pathlib import Path

import numpy as np

try:
    from training.common import dataset
except ImportError:  # 允许直接以脚本方式运行
    import sys

    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
    from training.common import dataset

#: ``vsat`` 特征列：``RecordingObsCorrector`` 把 ``rtk.validobs`` 写进 f10。
_VSAT_COL = 10
_EL_COL = 0
_AZ_COL = 1

#: 单个 WLS 分组的最少行数：低于它 3 个未知量不可解（也说明该组数据本身有问题）。
MIN_ROWS_PER_GROUP = 6


class E2EError(RuntimeError):
    """数据无法无损复原（缺 trace 字段、找不到参考星、缺真值等）。"""


def load_truth(path: Path) -> dict[str, np.ndarray]:
    """``truth.csv`` → ``station -> ECEF 真值 [3]``（米）。"""
    out: dict[str, np.ndarray] = {}
    with Path(path).open("r", encoding="utf-8", newline="") as f:
        for row in csv.DictReader(f):
            st = (row.get("station") or "").strip()
            if not st:
                continue
            out[st] = np.array([float(row["ref_x"]), float(row["ref_y"]),
                                float(row["ref_z"])], dtype=np.float64)
    return out


def load_solutions(path: Path) -> dict[tuple[str, str, int], tuple[np.ndarray, np.ndarray]]:
    """配对 ``*.sol.csv`` → ``(station, day, hour) -> (gpst 升序, ECEF [n,3])``。

    只保留 ``stat > 0`` 的行。这张表提供两样东西：真值缺失时的兜底真值，以及
    ``x_pred``（上一历元的解算位置，用来把 ``before`` 换到真值参考系）。
    """
    import pandas as pd

    # 采集偶发写入撕裂：两条记录粘成一行（字段数超出），pandas 会按表头宽度截断，
    # 把下一行的 ``day`` 塞进 ``y``。先全按字符串读、再强制转数值并校验 ECEF 量级，
    # 逐行剔除并计数告警——静默吞掉损坏数据比丢掉一个历元危险得多。
    cols = ["station", "day", "hour", "gpst", "stat", "x", "y", "z"]
    df = pd.read_csv(path, usecols=cols, dtype=str, on_bad_lines="skip")
    for c in ("hour", "gpst", "stat", "x", "y", "z"):
        df[c] = pd.to_numeric(df[c], errors="coerce")
    ok = df[["hour", "gpst", "stat", "x", "y", "z"]].notna().all(axis=1)
    ok &= (df[["x", "y", "z"]].abs() < 1e7).all(axis=1)   # ECEF 模长 ~6.4e6 m
    n_bad = int((~ok).sum())
    if n_bad:
        print(f"  [警告] {Path(path).name} 有 {n_bad} 行数值异常（写入撕裂），已剔除")
    df = df[ok]
    df = df[df["stat"] > 0]
    out: dict[tuple[str, str, int], tuple[np.ndarray, np.ndarray]] = {}
    for (st, dy, hr), g in df.groupby(["station", "day", "hour"], sort=False):
        g = g.sort_values("gpst", kind="stable")
        out[(str(st).strip(), str(dy).strip(), int(hr))] = (
            g["gpst"].to_numpy(dtype=np.float64),
            g[["x", "y", "z"]].to_numpy(dtype=np.float64),
        )
    return out


def ecef2enu_matrix(ecef: np.ndarray) -> np.ndarray:
    """ECEF→ENU 旋转矩阵 ``[3,3]``（行序 E/N/U），用于把坐标差旋到 H 所在系。"""
    x, y, z = float(ecef[0]), float(ecef[1]), float(ecef[2])
    lon = math.atan2(y, x)
    lat = math.atan2(z, math.hypot(x, y))
    sl, cl = math.sin(lat), math.cos(lat)
    so, co = math.sin(lon), math.cos(lon)
    return np.array([[-so, co, 0.0],
                     [-sl * co, -sl * so, cl],
                     [cl * co, cl * so, sl]], dtype=np.float64)


def _unit_enu(el_deg: np.ndarray, az_deg: np.ndarray) -> np.ndarray:
    """``(高度角, 方位角)``（度）→ ENU 下的接收机→卫星单位向量 ``[n,3]``。"""
    el = np.deg2rad(np.asarray(el_deg, dtype=np.float64))
    az = np.deg2rad(np.asarray(az_deg, dtype=np.float64))
    return np.column_stack([np.cos(el) * np.sin(az),
                            np.cos(el) * np.cos(az),
                            np.sin(el)])


def parse_trace_ai3(path: Path) -> tuple[dict, set, dict]:
    """提取 ``AI3`` 主题的 DDRES 行与 DDRES_REJ 行。

    返回 ``(ddres, rejected, epoch_gpst)``：

    - ``ddres``: ``(epoch, sat, frq) -> (before, Ri, Rj)``，``Ri/Rj`` 是**膨胀前**的方差；
    - ``rejected``: ``(epoch, sat, frq)`` 被 ``maxinno`` 剔除的行；
    - ``epoch_gpst``: ``epoch -> gpst``（秒）。

    ``before`` 是未扣偏差的双差码残差，``Ri/Rj`` 是该行两端的原生码方差——两者都是
    AI 介入**之前**的量，所以这份采集即使将来换成注入了模型的采集，取出来的仍是干净基线。
    """
    ddres: dict[tuple[int, int, int], tuple[float, float, float]] = {}
    rejected: set[tuple[int, int, int]] = set()
    epoch_gpst: dict[int, float] = {}
    with Path(path).open("r", encoding="utf-8", errors="replace") as f:
        for raw in f:
            if not raw.startswith("TRACE|"):
                continue
            kv: dict[str, str] = {}
            for p in raw.rstrip("\n").split("|")[1:]:
                k, _, v = p.partition("=")
                kv[k] = v
            if kv.get("topic") != "AI3":
                continue
            act = kv.get("action")
            if act not in ("DDRES", "DDRES_REJ"):
                continue
            try:
                ep = int(kv["epoch"])
                sat = int(kv["sat"])
                frq = int(kv["frq"])
            except (KeyError, ValueError):
                continue
            if ep not in epoch_gpst:
                try:
                    epoch_gpst[ep] = float(kv["gpst"])
                except (KeyError, ValueError):
                    pass
            if act == "DDRES":
                try:
                    ddres[(ep, sat, frq)] = (float(kv["before"]), float(kv["Ri"]),
                                             float(kv["Rj"]))
                except (KeyError, ValueError):
                    continue
            else:
                rejected.add((ep, sat, frq))
    return ddres, rejected, epoch_gpst


def _load_obs(path: Path) -> tuple[np.ndarray, np.ndarray, np.ndarray, np.ndarray]:
    """读 ``obs.csv`` → ``(keys[N,3]=(epoch,sat,frq), feat[N,17], el, az)``。"""
    import pandas as pd

    cols = ["epoch", "sat", "frq"] + [f"f{i}" for i in range(dataset.DIM)]
    df = pd.read_csv(path, usecols=cols)
    keys = df[["epoch", "sat", "frq"]].to_numpy(dtype=np.int64)
    feat = df[[f"f{i}" for i in range(dataset.DIM)]].to_numpy(dtype=np.float32)
    return keys, feat, feat[:, _EL_COL], feat[:, _AZ_COL]


def build_run(collect_dir: Path, run: dict, sol_index: dict, truth: dict) -> dict | None:
    """装配单个采集 run 的端到端训练样本。

    返回 ``None`` 表示该 run 不可用（无解算、无 DDRES 行、缺真值），并在 ``reason`` 里
    给出可读原因；返回字典的键见模块文档。
    """
    st = (run.get("station") or "").strip()
    dy = (run.get("day") or "").strip()
    rid = (run.get("run_id") or "").strip()
    if st not in truth:
        return None
    obs_csv = collect_dir / (run.get("obs_csv_file") or "")
    trace = collect_dir / (run.get("trace_file") or "")
    if not obs_csv.exists() or not trace.exists():
        return None
    try:
        hour = int(rid.lstrip("hH"))
    except ValueError:
        return None
    sols = sol_index.get((st, dy, hour))
    if sols is None or sols[0].size < 2:
        return None
    gpst_sol, xyz_sol = sols

    ddres, rejected, epoch_gpst = parse_trace_ai3(trace)
    if not ddres:
        return None

    keys, feat, el, az = _load_obs(obs_csv)
    index = {(int(e), int(s), int(f)): i for i, (e, s, f) in enumerate(keys)}
    # (epoch, frq) -> 该组全部观测行下标：参考星复原要按组看全集，不能按行扫全表
    # （4800 行 × 480 组 × 576 run 的全表扫描是十亿级迭代，装配会慢到不可用）。
    by_epoch_frq: dict[tuple[int, int], list[int]] = {}
    for i, (e, s, f) in enumerate(keys):
        by_epoch_frq.setdefault((int(e), int(f)), []).append(i)
    unit = _unit_enu(el, az)

    # 参考星复原：同一 (epoch, frq) 下，观测集合里既不在 DDRES 行、也没被 maxinno 剔除、
    # 且 validobs 通过的卫星就是参考星；多个候选时取高度角最高者（与 ddres 的评分口径一致）。
    ref_cache: dict[tuple[int, int], int | None] = {}
    n_ref_multi = 0
    n_ref_fallback = 0
    rows: list[tuple[int, int, int, float, float, float, float]] = []
    hrows: list[np.ndarray] = []
    dxs: list[np.ndarray] = []

    rot = ecef2enu_matrix(truth[st])
    for (ep, sat, frq), (before, ri, rj) in ddres.items():
        i_self = index.get((ep, sat, frq))
        if i_self is None:
            continue
        k2 = (ep, frq)
        if k2 not in ref_cache:
            cands: list[int] = []
            valids: list[int] = []
            for i in by_epoch_frq.get(k2, ()):
                if feat[i, _VSAT_COL] <= 0.5:
                    continue
                valids.append(i)
                e, s, f = (int(v) for v in keys[i])
                if (e, s, f) in ddres or (e, s, f) in rejected:
                    continue
                cands.append(i)
            if not cands:
                cands = valids
                n_ref_fallback += 1
            if not cands:
                ref_cache[k2] = None
            else:
                ref_cache[k2] = max(cands, key=lambda i: float(el[i]))
                if len(cands) > 1:
                    n_ref_multi += 1
        i_ref = ref_cache[k2]
        if i_ref is None or i_ref == i_self:
            continue

        # δ = x_true - x_pred（x_pred 取上一历元解；静态零过程噪声，等价于当前先验）
        gpst = epoch_gpst.get(ep)
        if gpst is None:
            continue
        j = int(np.searchsorted(gpst_sol, gpst, side="left")) - 1
        if j < 0:
            continue
        h = unit[i_self] - unit[i_ref]
        dx_enu = rot @ (truth[st] - xyz_sol[j])   # 先验位置误差（真值 - 上一历元解）
        r = float(before) - float(h @ dx_enu)
        rows.append((int(i_self), int(i_ref), int(ep), r, float(before),
                     float(ri), float(rj)))
        hrows.append(h)
        dxs.append(dx_enu)

    if len(rows) < MIN_ROWS_PER_GROUP:
        return None
    arr = np.asarray(rows, dtype=np.float64)
    return {
        "station": st,
        "day": dy,
        "hour": hour,
        "run_id": rid,
        "dx_enu": np.mean(np.asarray(dxs, dtype=np.float64), axis=0),
        "dxr": np.asarray(dxs, dtype=np.float32),
        "row_self": arr[:, 0].astype(np.int64),
        "row_ref": arr[:, 1].astype(np.int64),
        "epoch": arr[:, 2].astype(np.int64),
        "r": arr[:, 3].astype(np.float32),
        "before": arr[:, 4].astype(np.float32),
        "ri": arr[:, 5].astype(np.float32),
        "rj": arr[:, 6].astype(np.float32),
        "h": np.asarray(hrows, dtype=np.float32),
        "feat": feat,
        "n_ref_multi": n_ref_multi,
        "n_ref_fallback": n_ref_fallback,
        "n_ddres": len(ddres),
    }


def build_dataset(collect_dir: Path, sol_csv: Path, truth_csv: Path,
                  runs: list[dict] | None = None,
                  progress: bool = True) -> dict:
    """把全部可用 run 装配成一份按 run 分块的数据包。

    返回字典：

    - ``x``: ``[N, 17]`` 特征（**全部**观测行，含参考星行，模型对每行独立出 bias/scale）；
    - ``row_self`` / ``row_ref``: ``[M]`` 索引到 ``x``；
    - ``h``: ``[M, 3]`` ENU 几何行；``r``: ``[M]`` 真值参考残差；
    - ``ri`` / ``rj``: ``[M]`` 两端原生方差；
    - ``group``: ``[M]`` 所属 run 在 ``runs`` 里的下标（WLS 分组键）；
    - ``runs``: 每个 run 的元数据（station/day/hour/行数）。
    """
    collect_dir = Path(collect_dir)
    truth = load_truth(truth_csv)
    sol_index = load_solutions(sol_csv)
    if runs is None:
        from training.obs_corrector import assemble

        runs = assemble.load_manifest(collect_dir)

    x_parts: list[np.ndarray] = []
    self_parts: list[np.ndarray] = []
    ref_parts: list[np.ndarray] = []
    h_parts: list[np.ndarray] = []
    r_parts: list[np.ndarray] = []
    dxr_parts: list[np.ndarray] = []
    before_parts: list[np.ndarray] = []
    ri_parts: list[np.ndarray] = []
    rj_parts: list[np.ndarray] = []
    gid_parts: list[np.ndarray] = []
    ep_parts: list[np.ndarray] = []
    meta: list[dict] = []
    n_off = 0
    m_off = 0
    n_skipped = 0
    diag = {"n_ref_multi": 0, "n_ref_fallback": 0, "n_ddres": 0, "skipped": 0}

    for k, run in enumerate(runs):
        if progress and (k % 64 == 0 or k + 1 == len(runs)):
            print(f"  装配 {k + 1}/{len(runs)}", flush=True)
        got = build_run(collect_dir, run, sol_index, truth)
        if got is None:
            n_skipped += 1
            diag["skipped"] += 1
            continue
        diag["n_ref_multi"] += got["n_ref_multi"]
        diag["n_ref_fallback"] += got["n_ref_fallback"]
        diag["n_ddres"] += got["n_ddres"]
        x_parts.append(got["feat"])
        self_parts.append(got["row_self"] + n_off)
        ref_parts.append(got["row_ref"] + n_off)
        h_parts.append(got["h"])
        r_parts.append(got["r"])
        dxr_parts.append(got["dxr"])
        before_parts.append(got["before"])
        ri_parts.append(got["ri"])
        rj_parts.append(got["rj"])
        gid_parts.append(np.full(got["r"].shape[0], len(meta), dtype=np.int32))
        ep_parts.append((got["epoch"] + np.int64(len(meta)) * 100000).astype(np.int64))
        meta.append({"station": got["station"], "day": got["day"],
                     "hour": got["hour"], "run_id": got["run_id"],
                     "dx_enu": got["dx_enu"],
                     "n_rows": int(got["r"].shape[0]),
                     "x_start": n_off, "x_stop": n_off + got["feat"].shape[0],
                     "row_start": m_off, "row_stop": m_off + int(got["r"].shape[0])})
        m_off += int(got["r"].shape[0])
        n_off += got["feat"].shape[0]

    if not meta:
        raise E2EError("没有任何 run 能装配出端到端样本（检查采集目录/配对 sol.csv/truth.csv）")

    diag["n_skipped"] = n_skipped
    return {
        "x": np.concatenate(x_parts, axis=0),
        "row_self": np.concatenate(self_parts),
        "row_ref": np.concatenate(ref_parts),
        "h": np.concatenate(h_parts, axis=0),
        "r": np.concatenate(r_parts),
        "dxr": np.concatenate(dxr_parts, axis=0),
        "before": np.concatenate(before_parts),
        "ri": np.concatenate(ri_parts),
        "rj": np.concatenate(rj_parts),
        "group": np.concatenate(gid_parts),
        "epoch_key": np.concatenate(ep_parts),
        "runs": meta,
        "diag": diag,
    }


def split_runs(runs: list[dict], seed: int, n_test_stations: int,
               n_val_stations: int, val_day_fraction: float = 0.25) -> list[dict]:
    """按**现有两层切分规则**给 run 打 ``train/val/test`` 标签。

    直接把 ``(station, day)`` 喂给 :func:`training.common.dataset.two_layer_split`，
    再用它返回的站集合/验证日集合逐 run 判定归属——不另写一套切分逻辑。
    同一份采集上方案 A 与方案 B 必须落在同样的站/日上，否则两组指标不可比。
    """
    import pyarrow as pa

    table = pa.table({"station": [r["station"] for r in runs],
                      "day": [r["day"] for r in runs]})
    split = dataset.two_layer_split(table, seed=seed,
                                    n_test_stations=n_test_stations,
                                    n_val_stations=n_val_stations,
                                    val_day_fraction=val_day_fraction)
    test_set = set(split.test_stations)
    val_set = set(split.val_stations)
    val_days = {st: set(d) for st, d in split.val_days}

    def _role(st: str, dy: str) -> str:
        if st in test_set:
            return "test"
        if st in val_set or dy in val_days.get(st, ()):
            return "val"
        return "train"

    return [{**r, "split": _role(r["station"], r["day"])} for r in runs]


def wls_position(h, w, res, group, n_groups: int, ridge: float = 1e-6):
    """可导单步 WLS：``(HᵀWH)⁻¹HᵀWres``，按 ``group`` 分组批量求解。

    ``h``/``res`` 是常数（调用方已 detach），只有 ``w``（由模型 scale 生成）参与梯度：
    梯度描述的是「改正量如何改变坐标」，不是「几何如何改变坐标」。
    """
    import torch

    eye = torch.eye(3, dtype=h.dtype, device=h.device).unsqueeze(0)
    hh = h.unsqueeze(2) * h.unsqueeze(1)                 # [M,3,3]
    a = torch.zeros(n_groups, 3, 3, dtype=h.dtype, device=h.device)
    a = a.index_add(0, group, hh * w.reshape(-1, 1, 1))
    b = torch.zeros(n_groups, 3, dtype=h.dtype, device=h.device)
    b = b.index_add(0, group, h * (w * res).reshape(-1, 1))
    # 固定量级的岭项：既避免病态几何下的奇异，也让空组返回 0 而不是 NaN。
    reg = ridge * (a.diagonal(dim1=1, dim2=2).sum(dim=1).clamp_min(1e-12))
    a = a + eye * reg.reshape(-1, 1, 1)
    return torch.linalg.solve(a, b.unsqueeze(2)).squeeze(2)


def wls_position_np(h: np.ndarray, w: np.ndarray, res: np.ndarray,
                    group: np.ndarray, n_groups: int) -> np.ndarray:
    """:func:`wls_position` 的 numpy 版，用于无梯度评估。"""
    a = np.zeros((n_groups, 3, 3), dtype=np.float64)
    b = np.zeros((n_groups, 3), dtype=np.float64)
    hh = h[:, :, None] * h[:, None, :]
    np.add.at(a, group, hh * w[:, None, None])
    np.add.at(b, group, h * (w * res)[:, None])
    reg = 1e-6 * np.maximum(np.trace(a, axis1=1, axis2=2), 1e-12)
    a = a + np.eye(3)[None] * reg[:, None, None]
    return np.linalg.solve(a, b[:, :, None])[:, :, 0]