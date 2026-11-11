"""接口一采集结果 → Parquet 训练集：多 run 合并 + 真值打标签。

采集侧（Java ``CollectSession``）每次运行产出一个 CSV（6 维特征）和一个 trace
（``AR/FIX`` 里的固定解坐标）。本脚本把它们与**站点已知坐标**合并成
:mod:`training.common.fix_dataset` 规定的 Parquet 格式。

三段职责划分（不要混）：

============  ==========================================  ==========
阶段           输入                                         输出
============  ==========================================  ==========
采集（Java）   ``FixQualityContext``                         ``{station}_{day}_{run}.csv``
采集（core）   ``Trace.emit("AR","FIX", ... x/y/z)``        ``{station}_{day}_{run}.trace``
组装（本脚本） CSV + trace + 已知坐标                        ``fix_validator.parquet``
============  ==========================================  ==========

两个必须理解的约束：

1. **join key 是 ``(run_id, epoch)``，不是时间。** ``FixQualityContext.epoch`` 是
   进程内计数器，跨运行会从 0 重来，所以 ``run_id`` 是主键的另一半。
   由于真值是站点已知坐标（静态），打标签**不需要绝对时间**；
   trace 里的 ``gpst`` 只用于报告与溯源，不参与 join。
2. **已知坐标真值只适用于静态/已知点观测。** 流动站在动时（kinematic）没有唯一
   真值，这类数据不得用本脚本打标签——那需要 PPK/PPP 事后解，本脚本不提供。
3. **自动真值必须跨日校验。** ``--reference cluster`` 不需要真值文件，但参考坐标
   只能来自**跨日**紧致聚类，且逐日中心必须一致（§9「自动真值自证」）。只用站内
   池化聚类时，整日系统性偏差（天线相位中心、站号错配）会被「主导簇自证为正确」
   掩盖：簇心就是偏差本身，该日全部解都被判为正确。故本脚本强制逐日中心一致性
   校验，任一日中心偏离跨日中位超过 ``--max-day-offset`` 即该站该日拒用。

用法（在 ``rtklib-ai`` 目录下执行）::

    # 有真值文件（可对照 cluster 与 truth 两种口径）
    python -m training.fix_validator.assemble \
        --collect-dir out/collect --truth truth.csv --out data/fix_validator.parquet

    # 无真值文件（现场私有数据，走跨日聚类自动真值）
    python -m training.fix_validator.assemble \
        --collect-dir out/collect-hourly --reference cluster \
        --out data/fix_validator_hourly.parquet

真值文件支持两种表头（按列名自动识别，不猜）：

- ECEF：``station,ref_x,ref_y,ref_z``
- 大地坐标：``station,lat,lon,hgt``（度/度/米，WGS84）
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import sys
from pathlib import Path

import numpy as np
import pyarrow as pa

try:
    from training.common import fix_dataset as dataset
except ImportError:  # 允许直接以脚本方式运行
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
    from training.common import fix_dataset as dataset

MANIFEST_NAME = "_runs.csv"
# 错误固定判定阈值：0.20 m 略大于一个 L1 整周（19.2 cm），物理上等价于「整周解错」。
# 该取值同时是 G-1/G-2 的可行性边界：设 AR 总历元为 A、错误固定为 W，剔除 30% 错误
# 固定至少需要 0.3W 个历元，而 G-2 只允许剔除 0.01A 个历元，故必须 0.3W <= 0.01A，
# 即 W/A <= 3.33%。本批数据固定率约 40%，折算到固定历元口径要求错误固定率 <= 8.4%，
# 实测分布只有阈值取 0.20 m（约 6.9%）落在可行域；取 0.05 m 时该比例为 34%，
# G-1 永远无法达成。
DEFAULT_THRESHOLD_M = 0.20
DEFAULT_MIN_JOIN_RATE = 0.90
MIN_WRONG_EVENTS = 100
DEFAULT_REFERENCE = "cluster"
DEFAULT_CLIP_M = 0.05
DEFAULT_MAX_DAY_OFFSET_M = 0.10
REFERENCE_CHOICES = ("cluster", "truth")

WGS84_A = 6378137.0
WGS84_E2 = 0.0066943799901413165

_CSV_FEATURES = [f"f{i}" for i in range(dataset.DIM)]


class AssembleError(RuntimeError):
    """不可继续的组装错误（真值缺失、join 覆盖率过低等）。"""


def _ecef_from_geodetic(lat_deg: float, lon_deg: float, h: float) -> np.ndarray:
    """WGS84 大地坐标 → ECEF（与 core ``CoordTransform.pos2ecef`` 同一公式）。"""
    lat = math.radians(lat_deg)
    lon = math.radians(lon_deg)
    sin_lat = math.sin(lat)
    n = WGS84_A / math.sqrt(1.0 - WGS84_E2 * sin_lat * sin_lat)
    x = (n + h) * math.cos(lat) * math.cos(lon)
    y = (n + h) * math.cos(lat) * math.sin(lon)
    z = (n * (1.0 - WGS84_E2) + h) * sin_lat
    return np.array([x, y, z], dtype=np.float64)


def load_truth(path: Path) -> dict[str, np.ndarray]:
    """读站点已知坐标。列名决定解释方式，缺列即报错——不做隐式猜测。"""
    with path.open("r", encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        fields = set(reader.fieldnames or [])
        if "station" not in fields:
            raise AssembleError(f"真值文件缺少 station 列: {path}")
        ecef_cols = {"ref_x", "ref_y", "ref_z"} & fields
        geo_cols = {"lat", "lon", "hgt"} & fields
        if ecef_cols == {"ref_x", "ref_y", "ref_z"}:
            kind = "ecef"
        elif geo_cols == {"lat", "lon", "hgt"}:
            kind = "geodetic"
        else:
            raise AssembleError(
                f"真值文件列无法识别，须为 station+ref_x/ref_y/ref_z 或 station+lat/lon/hgt: {path}")

        truth: dict[str, np.ndarray] = {}
        for row in reader:
            st = (row.get("station") or "").strip()
            if not st:
                raise AssembleError(f"真值文件存在空 station 行: {path}")
            if st in truth:
                raise AssembleError(f"真值文件 station 重复: {st}")
            try:
                if kind == "ecef":
                    pos = np.array([float(row["ref_x"]), float(row["ref_y"]),
                                    float(row["ref_z"])], dtype=np.float64)
                else:
                    pos = _ecef_from_geodetic(float(row["lat"]), float(row["lon"]),
                                              float(row["hgt"]))
            except (TypeError, ValueError) as e:
                raise AssembleError(f"真值文件 station={st} 坐标无法解析: {e}") from e
            if not np.all(np.isfinite(pos)):
                raise AssembleError(f"真值文件 station={st} 坐标含非有限值")
            truth[st] = pos
    if not truth:
        raise AssembleError(f"真值文件为空: {path}")
    return truth


def load_manifest(collect_dir: Path) -> list[dict]:
    """读运行清单，只返回 ``status == ok`` 的运行。

    崩溃的运行不会留下 ok 行，这正是识别残缺数据的手段。
    """
    path = collect_dir / MANIFEST_NAME
    if not path.exists():
        raise AssembleError(
            f"缺少运行清单 {path}：采集必须通过 CollectSession 完成（它负责写清单）")
    with path.open("r", encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        rows = [r for r in reader if (r.get("status") or "").strip() == "ok"]
    if not rows:
        raise AssembleError(f"运行清单中没有 status=ok 的运行: {path}")
    return rows


def parse_trace(path: Path) -> tuple[dict[int, np.ndarray], dict]:
    """提取 ``AR/FIX`` 行里的 ``epoch → 固定解 ECEF``，并统计 AR 总历元数。

    只认 ``topic=AR`` 且 ``action=FIX``：其它 action（``FAIL``/``AI_REJECT``）没有固定解，
    不能用来打标签。缺失 ``x/y/z`` 的行计入 ``missing_pos``，不静默丢弃。

    ``ar_lines`` 统计**所有** ``topic=AR`` 行（FIX 与 FAIL 都算），它是固定率的真实分母
    （G-2 判据依赖它）。只数 FIX 行会把固定率算成 100%，使 1pp 预算被严重低估。
    """
    positions: dict[int, np.ndarray] = {}
    stats = {"fix_lines": 0, "missing_pos": 0, "duplicate_epoch": 0, "gpst_first": None,
             "gpst_last": None, "ar_lines": 0}
    if not path.exists():
        raise AssembleError(f"trace 文件不存在: {path}（采集时须开启 AR/FIX trace）")

    with path.open("r", encoding="utf-8", errors="replace") as f:
        for raw in f:
            if not raw.startswith("TRACE|"):
                continue
            parts = raw.rstrip("\n").split("|")
            kv = {}
            for p in parts[1:]:
                k, _, v = p.partition("=")
                kv[k] = v
            if kv.get("topic") != "AR":
                continue
            stats["ar_lines"] += 1
            if kv.get("action") != "FIX":
                continue
            stats["fix_lines"] += 1
            try:
                epoch = int(kv["epoch"])
            except (KeyError, ValueError):
                stats["missing_pos"] += 1
                continue
            try:
                pos = np.array([float(kv["x"]), float(kv["y"]), float(kv["z"])],
                               dtype=np.float64)
            except (KeyError, ValueError):
                stats["missing_pos"] += 1
                continue
            if not np.all(np.isfinite(pos)):
                stats["missing_pos"] += 1
                continue
            if epoch in positions:
                stats["duplicate_epoch"] += 1
            positions[epoch] = pos
            gpst = kv.get("gpst")
            if gpst:
                if stats["gpst_first"] is None:
                    stats["gpst_first"] = gpst
                stats["gpst_last"] = gpst
    return positions, stats


def read_run_csv(path: Path) -> list[dict]:
    """读单次运行的采集 CSV，做列完整性与重复主键检查。"""
    with path.open("r", encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        required = ["run_id", "station", "day", "epoch"] + _CSV_FEATURES + [
            "ns", "nf", "nb", "n_code_rows", "n_phase_rows"]
        missing = [c for c in required if c not in (reader.fieldnames or [])]
        if missing:
            raise AssembleError(f"采集 CSV 缺少列 {missing}: {path}")
        rows = []
        for row in reader:
            try:
                rec = {
                    "run_id": row["run_id"],
                    "station": row["station"],
                    "day": row["day"],
                    "epoch": int(row["epoch"]),
                    "ns": int(row["ns"]),
                    "nf": int(row["nf"]),
                    "nb": int(row["nb"]),
                    "n_code_rows": int(row["n_code_rows"]),
                    "n_phase_rows": int(row["n_phase_rows"]),
                    "f": [float(row[c]) for c in _CSV_FEATURES],
                }
            except (TypeError, ValueError) as e:
                raise AssembleError(f"采集 CSV 行无法解析（{path}）: {e}") from e
            rows.append(rec)
    return rows


def _pct(values: np.ndarray, q: float) -> float:
    return float(np.percentile(values, q)) if values.size else float("nan")


def estimate_center(positions: "np.ndarray | list", clip_m: float, iters: int = 10) -> np.ndarray:
    """静态站的稳健中心估计：迭代裁剪中位数。

    真值文件给的是站点坐标，但实测解可能整体带天线/标石偏心或坐标系差异
    （本批 16 站逐站误差中位在 0.02~0.18 m 间相差 8 倍），若直接用统一阈值
    对原始真值打标签，会把「站点系统性偏差」整批误标成错误固定。这里先用
    全体固定解的中位数，再按 ``clip_m`` 迭代裁剪后重算中位数，得到该站自身
    稳定簇的中心作为标签参考，只保留真正偏离簇心的解为错误固定。
    """
    pts = np.asarray(positions, dtype=np.float64).reshape(-1, 3)
    if pts.shape[0] == 0:
        raise AssembleError("无固定解位置，无法估计站点中心")
    center = np.median(pts, axis=0)
    min_keep = max(3, int(0.1 * pts.shape[0]))
    for _ in range(iters):
        dist = np.linalg.norm(pts - center, axis=1)
        keep = dist <= clip_m
        if int(keep.sum()) < min_keep:
            break
        new_center = np.median(pts[keep], axis=0)
        moved = float(np.linalg.norm(new_center - center))
        center = new_center
        if moved < 1e-4:
            break
    return center


def _consistent_days(centers: dict[str, np.ndarray], max_offset_m: float,
                     max_iters: int = 3) -> tuple[np.ndarray, dict, dict, dict]:
    """跨日中心一致性：迭代剔除偏离中位最远的观测日，返回 (参考坐标, 保留日, 剔除日, 偏离)。

    「主导簇自证」的失效场景是整日系统性偏差：把该日与其余日混在一起做池化聚类，
    只要该日样本多，簇心就被它拉过去，该日全部解反而被判为正确。先算逐日中心、
    再按跨日中位裁决，才能把这类偏差暴露出来。
    """
    kept = dict(centers)
    dropped: dict[str, float] = {}
    ref = np.median(np.stack(list(kept.values())), axis=0)
    for _ in range(max_iters):
        if len(kept) < 2:
            break
        ref = np.median(np.stack(list(kept.values())), axis=0)
        dev = {d: float(np.linalg.norm(c - ref)) for d, c in kept.items()}
        worst = max(dev, key=lambda d: dev[d])
        if dev[worst] <= max_offset_m:
            break
        dropped[worst] = dev[worst]
        kept.pop(worst)
    if not kept:
        raise AssembleError("所有观测日中心互不一致，无法确定参考坐标")
    ref = np.median(np.stack(list(kept.values())), axis=0)
    dev = {d: float(np.linalg.norm(c - ref)) for d, c in kept.items()}
    return ref, kept, dropped, dev


def _station_references(day_positions: dict[tuple[str, str], list],
                        truth: "dict[str, np.ndarray] | None", reference: str,
                        clip_m: float, max_day_offset_m: float
                        ) -> tuple[dict, dict, dict]:
    """逐站确定打标签用的参考坐标，并做跨日一致性校验。

    返回 ``(refs, report, rejected)``：``refs`` 是可用于打标签的站 → 坐标，
    ``report`` 是逐站诊断（含逐日中心与偏离），``rejected`` 是站 → 拒用日列表
    （``[]`` 表示该站整体拒用，原因见 ``report[st]["reason"]``）。

    只有 1 个观测日的站**整体拒用**：没有第二个日中心就无法校验，此时任何自动真值
    都是自证的（§9）。
    """
    refs: dict[str, np.ndarray] = {}
    report: dict[str, dict] = {}
    rejected: dict[str, list[str]] = {}

    for st in sorted({s for s, _ in day_positions}):
        days = sorted({d for s, d in day_positions if s == st})
        centers = {d: estimate_center(day_positions[(st, d)], clip_m) for d in days}
        entry: dict = {
            "n_days": len(days),
            "n_positions": sum(len(day_positions[(st, d)]) for d in days),
            "day_centers_m": {d: [float(v) for v in c] for d, c in centers.items()},
            "day_offset_m": {},
            "dropped_days": {},
            "cluster_xyz": None,
            "offset_from_truth_m": None,
            "reason": None,
        }

        if len(centers) < 2:
            entry["dropped_days"] = {
                d: "仅 1 个观测日，无法做跨日中心一致性校验" for d in days}
            entry["reason"] = "仅 1 个观测日，无法做跨日中心一致性校验（§9 自动真值自证）"
            report[st] = entry
            rejected[st] = []
            continue

        cluster_ref, kept, dropped, dev = _consistent_days(centers, max_day_offset_m)
        entry["cluster_xyz"] = [float(v) for v in cluster_ref]
        entry["day_offset_m"] = {d: float(v) for d, v in dev.items()}
        entry["dropped_days"] = {
            d: f"中心偏离跨日中位 {v:.3f} m > {max_day_offset_m:.3f} m"
            for d, v in dropped.items()}
        if truth and st in truth:
            entry["offset_from_truth_m"] = float(np.linalg.norm(cluster_ref - truth[st]))

        if len(kept) < 2:
            entry["reason"] = (f"跨日一致性校验后仅剩 {len(kept)} 个观测日，"
                               "无法确认参考坐标")
            report[st] = entry
            rejected[st] = []
            continue

        if reference == "truth":
            if not (truth and st in truth):
                entry["reason"] = "reference=truth 但该站无真值文件坐标"
                report[st] = entry
                rejected[st] = []
                continue
            refs[st] = np.asarray(truth[st], dtype=np.float64)
        else:
            refs[st] = cluster_ref

        report[st] = entry
        rejected[st] = sorted(dropped)

    return refs, report, rejected


def assemble(collect_dir: Path, truth: "dict[str, np.ndarray] | None", threshold_m: float,
             min_join_rate: float, reference: str = DEFAULT_REFERENCE,
             clip_m: float = DEFAULT_CLIP_M,
             max_day_offset_m: float = DEFAULT_MAX_DAY_OFFSET_M
             ) -> tuple[object, dict]:
    """把目录下所有 ok 运行合并成 Parquet 表，并产出诊断报告。

    ``reference="cluster"`` 用**跨日一致**的站内稳健中心打标签（推荐，不需要真值文件）；
    ``reference="truth"`` 直接用真值文件坐标打标签（原始口径，便于对照）。
    ``truth`` 可为 ``None``（现场私有数据无真值文件），此时只允许 ``reference="cluster"``。
    ``max_day_offset_m`` 是逐日中心相对跨日中位的容差，超过即该站该日拒用。
    """
    runs = load_manifest(collect_dir)
    report: dict = {
        "threshold_m": threshold_m,
        "min_join_rate": min_join_rate,
        "reference": reference,
        "clip_m": clip_m,
        "max_day_offset_m": max_day_offset_m,
        "runs": [],
        "skipped_runs": [],
        "warnings": [],
    }

    loaded: list[dict] = []
    station_day_positions: dict[tuple[str, str], list[np.ndarray]] = {}

    for run in runs:
        run_id = (run.get("run_id") or "").strip()
        station = (run.get("station") or "").strip()
        day = (run.get("day") or "").strip()
        csv_name = (run.get("csv_file") or "").strip()
        trace_name = (run.get("trace_file") or "").strip()

        info = {"run_id": run_id, "station": station, "day": day,
                "csv_file": csv_name, "trace_file": trace_name}

        if not (csv_name and trace_name):
            report["skipped_runs"].append({**info, "reason": "清单缺少 csv_file/trace_file"})
            continue
        if reference == "truth" and not (truth and station in truth):
            report["skipped_runs"].append({**info, "reason": "站点无已知坐标真值"})
            continue

        try:
            rows = read_run_csv(collect_dir / csv_name)
            positions, tstats = parse_trace(collect_dir / trace_name)
        except AssembleError as e:
            report["skipped_runs"].append({**info, "reason": str(e)})
            continue

        ar_epochs = int(tstats["ar_lines"])
        if ar_epochs <= 0:
            report["skipped_runs"].append({
                **info, "reason": "trace 无 AR 历元行，无法确定固定率分母（G-2 不可评估）"})
            continue

        seen: set[int] = set()
        dup = 0
        for rec in rows:
            if rec["run_id"] != run_id or rec["station"] != station or rec["day"] != day:
                raise AssembleError(
                    f"CSV 行与清单不一致（run={run_id}）：行内 run_id/station/day = "
                    f"{rec['run_id']}/{rec['station']}/{rec['day']}")
            if rec["epoch"] in seen:
                dup += 1
                continue
            seen.add(rec["epoch"])

        kept = len(seen)
        joined = sum(1 for e in seen if e in positions)
        join_rate = joined / kept if kept else 0.0

        info.update({
            "csv_rows": len(rows),
            "unique_epochs": kept,
            "duplicate_epochs": dup,
            "joined": joined,
            "join_rate": join_rate,
            "ar_epochs": ar_epochs,
            "trace_fix_lines": tstats["fix_lines"],
            "trace_missing_pos": tstats["missing_pos"],
            "trace_duplicate_epoch": tstats["duplicate_epoch"],
            "gpst_first": tstats["gpst_first"],
            "gpst_last": tstats["gpst_last"],
        })
        if dup:
            report["warnings"].append(f"run={run_id} 存在 {dup} 个重复 epoch（已去重）")
        if join_rate < min_join_rate:
            report["skipped_runs"].append({
                **info,
                "reason": f"join 覆盖率 {join_rate:.1%} < {min_join_rate:.1%}"
                          f"（trace 缺失/采样/未开 AR/FIX 都会导致此问题）",
            })
            continue

        loaded.append({**info, "rows": rows, "positions": positions,
                       "ar_epochs": ar_epochs})
        station_day_positions.setdefault((station, day), []).extend(
            positions[e] for e in seen if e in positions)

    if not loaded:
        detail = json.dumps(report["skipped_runs"], ensure_ascii=False, indent=2)
        raise AssembleError(f"没有任何可用运行。逐运行原因:\n{detail}")

    refs, ref_report, rejected = _station_references(
        station_day_positions, truth, reference, clip_m, max_day_offset_m)
    report["references"] = ref_report

    dropped_runs: list[dict] = []
    for item in loaded:
        st, day = item["station"], item["day"]
        if st not in refs:
            dropped_runs.append({
                **{k: item[k] for k in ("run_id", "station", "day", "csv_file",
                                        "trace_file")},
                "reason": ref_report[st]["reason"] or "站点参考坐标不可用"})
        elif day in rejected.get(st, []):
            dropped_runs.append({
                **{k: item[k] for k in ("run_id", "station", "day", "csv_file",
                                        "trace_file")},
                "reason": ref_report[st]["dropped_days"].get(day, "跨日一致性校验拒用")})
    if dropped_runs:
        report["skipped_runs"].extend(dropped_runs)
        report["warnings"].append(
            f"跨日一致性校验拒用 {len(dropped_runs)} 次运行"
            f"（{len({(r['station'], r['day']) for r in dropped_runs})} 个站日）")
        loaded = [it for it in loaded
                  if it["station"] in refs
                  and it["day"] not in rejected.get(it["station"], [])]
    if not loaded:
        detail = json.dumps(report["skipped_runs"], ensure_ascii=False, indent=2)
        raise AssembleError(f"跨日一致性校验后无可用运行。逐运行原因:\n{detail}")

    station_col, day_col, epoch_col = [], [], []
    run_id_col, run_epochs_col = [], []
    feat_cols = [[] for _ in range(dataset.DIM)]
    label_col = []
    n_code_col, n_phase_col = [], []
    errors: list[float] = []

    for item in loaded:
        run_id = item["run_id"]
        station = item["station"]
        day = item["day"]
        rows = item["rows"]
        positions = item["positions"]
        ar_epochs = item["ar_epochs"]
        info = {k: item[k] for k in
                ("run_id", "station", "day", "csv_file", "trace_file",
                 "csv_rows", "unique_epochs", "duplicate_epochs", "joined",
                 "join_rate", "ar_epochs", "trace_fix_lines", "trace_missing_pos",
                 "trace_duplicate_epoch", "gpst_first", "gpst_last")}
        seen: set[int] = set()
        wrong = 0
        ref = refs[station]

        run_station: list[str] = []
        run_day: list[str] = []
        run_epoch: list[int] = []
        run_run_id: list[str] = []
        run_run_epochs: list[int] = []
        run_label: list[float] = []
        run_n_code: list[int] = []
        run_n_phase: list[int] = []
        run_feat: list[list[float]] = [[] for _ in range(dataset.DIM)]
        run_errors: list[float] = []

        for rec in rows:
            if rec["epoch"] in seen:
                continue
            seen.add(rec["epoch"])
            pos = positions.get(rec["epoch"])
            if pos is None:
                continue
            err = float(np.linalg.norm(pos - ref))
            run_errors.append(err)
            label = 1.0 if err <= threshold_m else 0.0
            if label == 0.0:
                wrong += 1
            run_station.append(station)
            run_day.append(day)
            run_epoch.append(rec["epoch"])
            run_run_id.append(run_id)
            run_run_epochs.append(ar_epochs)
            run_label.append(label)
            run_n_code.append(rec["n_code_rows"])
            run_n_phase.append(rec["n_phase_rows"])
            for i in range(dataset.DIM):
                run_feat[i].append(rec["f"][i])

        info.update({
            "wrong_fix": wrong,
            "err_p50_m": _pct(np.array(run_errors), 50) if run_errors else float("nan"),
            "err_p99_m": _pct(np.array(run_errors), 99) if run_errors else float("nan"),
        })

        station_col.extend(run_station)
        day_col.extend(run_day)
        epoch_col.extend(run_epoch)
        run_id_col.extend(run_run_id)
        run_epochs_col.extend(run_run_epochs)
        label_col.extend(run_label)
        n_code_col.extend(run_n_code)
        n_phase_col.extend(run_n_phase)
        errors.extend(run_errors)
        for i in range(dataset.DIM):
            feat_cols[i].extend(run_feat[i])
        report["runs"].append(info)

    if not label_col:
        detail = json.dumps(report["skipped_runs"], ensure_ascii=False, indent=2)
        raise AssembleError(f"没有任何可用样本。逐运行原因:\n{detail}")

    table = _build_table(
        feat_cols, label_col, epoch_col, station_col, day_col, run_id_col,
        run_epochs_col, n_code_col, n_phase_col)

    errs = np.asarray(errors, dtype=np.float64)
    ar_total = int(sum(r["ar_epochs"] for r in report["runs"]))
    offsets = np.asarray([v["offset_from_truth_m"] for v in report["references"].values()
                          if v["offset_from_truth_m"] is not None])
    day_offsets = np.asarray([o for v in report["references"].values()
                              for o in v["day_offset_m"].values()])
    n_rejected_days = sum(len(v["dropped_days"]) for v in report["references"].values())
    report["summary"] = {
        "n_rows": len(label_col),
        "n_stations": len(set(station_col)),
        "n_days": len(set(day_col)),
        "n_runs": len(report["runs"]),
        "reference": reference,
        "max_day_offset_m": max_day_offset_m,
        "n_rejected_station_days": n_rejected_days,
        "day_offset_p50_m": _pct(day_offsets, 50),
        "day_offset_max_m": float(day_offsets.max()) if day_offsets.size else float("nan"),
        "ref_offset_p50_m": _pct(offsets, 50),
        "ref_offset_max_m": float(offsets.max()) if offsets.size else float("nan"),
        "ar_epochs": ar_total,
        "fix_rate": float(len(label_col) / ar_total) if ar_total else 0.0,
        "wrong_fix": int(sum(1 for v in label_col if v == 0.0)),
        "wrong_fix_rate": float(sum(1 for v in label_col if v == 0.0) / len(label_col)),
        "err_p50_m": _pct(errs, 50),
        "err_p95_m": _pct(errs, 95),
        "err_p99_m": _pct(errs, 99),
        "err_max_m": float(errs.max()),
    }

    summary = report["summary"]
    if summary["n_stations"] < 4:
        report["warnings"].append(
            f"仅 {summary['n_stations']} 个站：两层切分要求 >= 4 站（test 2 + val 1 + train >= 1），"
            "当前数据不足以切分")
    if summary["wrong_fix"] < MIN_WRONG_EVENTS:
        report["warnings"].append(
            f"错误固定事件仅 {summary['wrong_fix']} 个，低于 G-1 前置条件 {MIN_WRONG_EVENTS}；"
            "指标置信区间会很宽，须继续累积运行")
    if summary["n_days"] < 2:
        report["warnings"].append("仅 1 个观测日：训练站内部无法切出验证日")

    return table, report


def _build_table(feat_cols, label_col, epoch_col, station_col, day_col, run_id_col,
                 run_epochs_col, n_code_col, n_phase_col):
    """按 :func:`fix_dataset.schema` 组装表：列序与类型必须与训练侧完全一致。"""
    arrays = [pa.array(col, type=pa.float32()) for col in feat_cols]
    arrays += [
        pa.array(label_col, type=pa.float32()),
        pa.array(epoch_col, type=pa.int64()),
        pa.array(station_col, type=pa.string()),
        pa.array(day_col, type=pa.string()),
        pa.array(run_id_col, type=pa.string()),
        pa.array(run_epochs_col, type=pa.int32()),
        pa.array(n_code_col, type=pa.int32()),
        pa.array(n_phase_col, type=pa.int32()),
    ]
    return pa.Table.from_arrays(arrays, schema=dataset.schema())


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(
        description="接口一采集结果组装：多 run CSV + trace + 已知坐标 → Parquet")
    ap.add_argument("--collect-dir", type=Path, required=True,
                    help="CollectSession 的输出目录（含 _runs.csv）")
    ap.add_argument("--truth", type=Path, default=None,
                    help="站点已知坐标 CSV（station+ref_x/ref_y/ref_z 或 station+lat/lon/hgt）；"
                         "缺省时只能 --reference cluster（现场私有数据无真值文件）")
    ap.add_argument("--out", type=Path, default=Path("data/fix_validator.parquet"))
    ap.add_argument("--threshold", type=float, default=DEFAULT_THRESHOLD_M,
                    help=f"判定「错误固定」的 3D 误差阈值（米），默认 {DEFAULT_THRESHOLD_M}")
    ap.add_argument("--min-join-rate", type=float, default=DEFAULT_MIN_JOIN_RATE,
                    help=f"单 run 的 epoch-trace 覆盖率下限，默认 {DEFAULT_MIN_JOIN_RATE}")
    ap.add_argument("--reference", choices=REFERENCE_CHOICES, default=DEFAULT_REFERENCE,
                    help=f"标签参考坐标来源，默认 {DEFAULT_REFERENCE}"
                         "（cluster=跨日一致的站内稳健中心，truth=真值文件坐标）")
    ap.add_argument("--clip", type=float, default=DEFAULT_CLIP_M,
                    help=f"cluster 模式下的迭代裁剪半径（米），默认 {DEFAULT_CLIP_M}")
    ap.add_argument("--max-day-offset", type=float, default=DEFAULT_MAX_DAY_OFFSET_M,
                    help=f"逐日中心相对跨日中位的容差（米），超过即该站该日拒用，"
                         f"默认 {DEFAULT_MAX_DAY_OFFSET_M}")
    args = ap.parse_args(argv)

    if args.threshold <= 0:
        ap.error("--threshold 必须为正")
    if not 0.0 <= args.min_join_rate <= 1.0:
        ap.error("--min-join-rate 必须在 [0,1]")
    if args.clip <= 0:
        ap.error("--clip 必须为正")
    if args.max_day_offset <= 0:
        ap.error("--max-day-offset 必须为正")
    if args.reference == "truth" and args.truth is None:
        ap.error("--reference truth 必须同时给 --truth")

    try:
        truth = load_truth(args.truth) if args.truth else None
        table, report = assemble(args.collect_dir, truth, args.threshold,
                                 args.min_join_rate, args.reference, args.clip,
                                 args.max_day_offset)
    except AssembleError as e:
        print(f"组装失败: {e}", file=sys.stderr)
        return 1

    args.out.parent.mkdir(parents=True, exist_ok=True)
    dataset.write(table, args.out, note="由 training/fix_validator/assemble.py 从采集 CSV 组装")

    report_path = args.out.with_suffix(".assemble.json")
    report_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    s = report["summary"]
    print(f"已写出 {args.out}（{s['n_rows']} 行 / {s['n_stations']} 站 / {s['n_days']} 日 "
          f"/ {s['n_runs']} 次运行）")
    print(f"参考坐标={s['reference']}；跨日中心偏离 p50={s['day_offset_p50_m']:.3f} m "
          f"/ max={s['day_offset_max_m']:.3f} m（容差 {s['max_day_offset_m']:.3f} m，"
          f"拒用站日 {s['n_rejected_station_days']} 个）")
    if not np.isnan(s["ref_offset_p50_m"]):
        print(f"跨日簇心与真值文件偏移 p50={s['ref_offset_p50_m']:.3f} m "
              f"/ max={s['ref_offset_max_m']:.3f} m")
    print(f"错误固定 {s['wrong_fix']} 行（{s['wrong_fix_rate']:.2%}），"
          f"误差分位 p50={s['err_p50_m']:.3f} m / p99={s['err_p99_m']:.3f} m "
          f"/ max={s['err_max_m']:.3f} m")
    print(f"诊断报告: {report_path}")
    for w in report["warnings"]:
        print(f"[警告] {w}")
    for r in report["skipped_runs"]:
        print(f"[跳过] run={r.get('station')}/{r.get('day')}/{r.get('run_id')} "
              f"{r.get('reason')}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())