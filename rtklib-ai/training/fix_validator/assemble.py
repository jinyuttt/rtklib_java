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

用法（在 ``rtklib-ai`` 目录下执行）::

    python -m training.fix_validator.assemble \
        --collect-dir out/collect --truth truth.csv --out data/fix_validator.parquet

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
DEFAULT_THRESHOLD_M = 0.05
DEFAULT_MIN_JOIN_RATE = 0.90
MIN_WRONG_EVENTS = 100

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
    """提取 ``AR/FIX`` 行里的 ``epoch → 固定解 ECEF``。

    只认 ``topic=AR`` 且 ``action=FIX``：其它 action（``FAIL``/``AI_REJECT``）没有固定解，
    不能用来打标签。缺失 ``x/y/z`` 的行计入 ``missing_pos``，不静默丢弃。
    """
    positions: dict[int, np.ndarray] = {}
    stats = {"fix_lines": 0, "missing_pos": 0, "duplicate_epoch": 0, "gpst_first": None,
             "gpst_last": None}
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
            if kv.get("topic") != "AR" or kv.get("action") != "FIX":
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


def assemble(collect_dir: Path, truth: dict[str, np.ndarray], threshold_m: float,
             min_join_rate: float) -> tuple[object, dict]:
    """把目录下所有 ok 运行合并成 Parquet 表，并产出诊断报告。"""
    runs = load_manifest(collect_dir)
    report: dict = {
        "threshold_m": threshold_m,
        "min_join_rate": min_join_rate,
        "runs": [],
        "skipped_runs": [],
        "warnings": [],
    }

    station_col, day_col, epoch_col = [], [], []
    feat_cols = [[] for _ in range(dataset.DIM)]
    label_col = []
    n_code_col, n_phase_col = [], []
    errors: list[float] = []

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
        if station not in truth:
            report["skipped_runs"].append({**info, "reason": "站点无已知坐标真值"})
            continue

        try:
            rows = read_run_csv(collect_dir / csv_name)
            positions, tstats = parse_trace(collect_dir / trace_name)
        except AssembleError as e:
            report["skipped_runs"].append({**info, "reason": str(e)})
            continue

        seen: set[int] = set()
        dup = 0
        kept = 0
        wrong = 0
        ref = truth[station]

        run_station: list[str] = []
        run_day: list[str] = []
        run_epoch: list[int] = []
        run_label: list[float] = []
        run_n_code: list[int] = []
        run_n_phase: list[int] = []
        run_feat: list[list[float]] = [[] for _ in range(dataset.DIM)]
        run_errors: list[float] = []

        for rec in rows:
            if rec["run_id"] != run_id or rec["station"] != station or rec["day"] != day:
                raise AssembleError(
                    f"CSV 行与清单不一致（run={run_id}）：行内 run_id/station/day = "
                    f"{rec['run_id']}/{rec['station']}/{rec['day']}")
            if rec["epoch"] in seen:
                dup += 1
                continue
            seen.add(rec["epoch"])
            kept += 1
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
            run_label.append(label)
            run_n_code.append(rec["n_code_rows"])
            run_n_phase.append(rec["n_phase_rows"])
            for i in range(dataset.DIM):
                run_feat[i].append(rec["f"][i])

        join_rate = len(run_label) / kept if kept else 0.0
        info.update({
            "csv_rows": len(rows),
            "unique_epochs": kept,
            "duplicate_epochs": dup,
            "joined": len(run_label),
            "join_rate": join_rate,
            "wrong_fix": wrong,
            "trace_fix_lines": tstats["fix_lines"],
            "trace_missing_pos": tstats["missing_pos"],
            "trace_duplicate_epoch": tstats["duplicate_epoch"],
            "gpst_first": tstats["gpst_first"],
            "gpst_last": tstats["gpst_last"],
            "err_p50_m": _pct(np.array(run_errors), 50) if run_errors else float("nan"),
            "err_p99_m": _pct(np.array(run_errors), 99) if run_errors else float("nan"),
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

        station_col.extend(run_station)
        day_col.extend(run_day)
        epoch_col.extend(run_epoch)
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
        feat_cols, label_col, epoch_col, station_col, day_col, n_code_col, n_phase_col)

    errs = np.asarray(errors, dtype=np.float64)
    report["summary"] = {
        "n_rows": len(label_col),
        "n_stations": len(set(station_col)),
        "n_days": len(set(day_col)),
        "n_runs": len(report["runs"]),
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
            f"仅 {summary['n_stations']} 个站：两层切分要求 ≥ 4 站（test 2 + val 1 + train ≥ 1），"
            "当前数据不足以切分")
    if summary["wrong_fix"] < MIN_WRONG_EVENTS:
        report["warnings"].append(
            f"错误固定事件仅 {summary['wrong_fix']} 个，低于 G-1 前置条件 {MIN_WRONG_EVENTS}；"
            "指标置信区间会很宽，须继续累积运行")
    if summary["n_days"] < 2:
        report["warnings"].append("仅 1 个观测日：训练站内部无法切出验证日")

    return table, report


def _build_table(feat_cols, label_col, epoch_col, station_col, day_col,
                 n_code_col, n_phase_col):
    """按 :func:`fix_dataset.schema` 组装表：列序与类型必须与训练侧完全一致。"""
    arrays = [pa.array(col, type=pa.float32()) for col in feat_cols]
    arrays += [
        pa.array(label_col, type=pa.float32()),
        pa.array(epoch_col, type=pa.int64()),
        pa.array(station_col, type=pa.string()),
        pa.array(day_col, type=pa.string()),
        pa.array(n_code_col, type=pa.int32()),
        pa.array(n_phase_col, type=pa.int32()),
    ]
    return pa.Table.from_arrays(arrays, schema=dataset.schema())


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(
        description="接口一采集结果组装：多 run CSV + trace + 已知坐标 → Parquet")
    ap.add_argument("--collect-dir", type=Path, required=True,
                    help="CollectSession 的输出目录（含 _runs.csv）")
    ap.add_argument("--truth", type=Path, required=True,
                    help="站点已知坐标 CSV（station+ref_x/ref_y/ref_z 或 station+lat/lon/hgt）")
    ap.add_argument("--out", type=Path, default=Path("data/fix_validator.parquet"))
    ap.add_argument("--threshold", type=float, default=DEFAULT_THRESHOLD_M,
                    help=f"判定「错误固定」的 3D 误差阈值（米），默认 {DEFAULT_THRESHOLD_M}")
    ap.add_argument("--min-join-rate", type=float, default=DEFAULT_MIN_JOIN_RATE,
                    help=f"单 run 的 epoch↔trace 覆盖率下限，默认 {DEFAULT_MIN_JOIN_RATE}")
    args = ap.parse_args(argv)

    if args.threshold <= 0:
        ap.error("--threshold 必须为正")
    if not 0.0 <= args.min_join_rate <= 1.0:
        ap.error("--min-join-rate 必须在 [0,1]")

    try:
        truth = load_truth(args.truth)
        table, report = assemble(args.collect_dir, truth, args.threshold, args.min_join_rate)
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
    print(f"错误固定 {s['wrong_fix']} 行（{s['wrong_fix_rate']:.2%}），"
          f"误差分位 p50={s['err_p50_m']:.3f} m / p99={s['err_p99_m']:.3f} m "
          f"/ max={s['err_max_m']:.3f} m")
    print(f"诊断报告: {report_path}")
    for w in report["warnings"]:
        print(f"[警告] {w}")
    for r in report["skipped_runs"]:
        print(f"[跳过] run={r.get('run_id')} {r.get('reason')}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())