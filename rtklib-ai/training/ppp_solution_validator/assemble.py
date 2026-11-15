"""接口 A 采集结果组装：多 run CSV → 带标签的 Parquet（设计文档 §12.2）。

采集 CSV 由 ``PppCollectSession`` 写出，一行一个历元（该历元接口 A 被调用一次）：

.. code-block:: text

    run_id,station,day,epoch,run_epochs,ns,deterministic_degraded,f0..f9,x,y,z

- ``f0..f9`` 与 ``PppSolutionFeatures`` 逐位一致，由采集侧调用**同一个**
  ``PppSolutionFeatures.extractRaw`` 得到——在 Python 侧重算会丢掉退化处理
  （负方差归零、NaN 归零），造成训练/推理分布错位；
- ``x,y,z`` 是该历元的 PPP 解 ECEF 坐标，只用于打标签，**不进入特征**；
- ``deterministic_degraded`` 是确定性 qr 基线是否已降级该历元（归因字段，§3.5），
  用来区分「AI 独立发现」与「AI 与基线重叠」；
- ``run_epochs`` 是该 run 的总历元数（降级率分母），同一 run 每行重复。

**标签只由离线真值产生，core 不参与打标签**（与接口一同源）：3D 误差
``|解 - 参考坐标| <= --threshold`` 记 ``label=1``（解可信），否则 ``0``（应降级）。

参考坐标两种口径（``--reference``）：

==========  ==============================================  ==============================
取值          含义                                            适用
==========  ==============================================  ==============================
``truth``     ``--truth`` 给出的站点坐标（原始口径）             有真值文件时
``cluster``   该站所有解自身的稳健中心（迭代裁剪中位数）        无真值文件；也可有真值文件时作交叉校验
==========  ==============================================  ==============================

``cluster`` 的口径警告与接口一相同：它是「解自身的中心」，对静态 PPP 而言误差尺度
只有厘米级，簇心与真值的差异会被吸收进标签。因此**有真值就必须给 ``--truth``**，
``cluster`` 只用于「确认真值缺失」的现场私有数据，且报告里会明确标注口径。
"""

from __future__ import annotations

import argparse
import csv
import json
import sys
from pathlib import Path

import numpy as np
import pyarrow as pa

try:
    from training.common import ppp_dataset as dataset
    from training.fix_validator.assemble import (AssembleError, estimate_center,
                                                 load_manifest, load_truth, _pct)
except ImportError:  # 允许直接以脚本方式运行
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
    from training.common import ppp_dataset as dataset
    from training.fix_validator.assemble import (AssembleError, estimate_center,
                                                 load_manifest, load_truth, _pct)

DEFAULT_THRESHOLD_M = 0.30
MIN_STATIONS_WARN = 4
MIN_INVALID_WARN = 100


def _read_run_csv(path: Path, n_features: int) -> tuple[list[dict], int]:
    """读单 run 的采集 CSV，做列完整性与数值可解析检查（不静默丢弃坏行）。

    位置缺失（``x/y/z`` 与 ``pos_err_3d`` 同时为空）的行会被丢弃并计数，而不是
    抛错整 run：采集器允许宿主不提供位置（见 ``PppPositionSource``），缺位置的历元
    只意味着「无标签」，不该让同一 run 里其他有标签的历元一起失效。
    """
    required = (["run_id", "station", "day", "epoch"]
                + [f"f{i}" for i in range(n_features)]
                + ["ns", "deterministic_degraded"])
    dropped = 0
    with path.open("r", encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        fields = set(reader.fieldnames or [])
        missing = [c for c in required if c not in fields]
        if missing:
            raise AssembleError(f"采集 CSV 缺少列 {missing}: {path}")
        has_pos = {"x", "y", "z"} <= fields
        has_err = "pos_err_3d" in fields
        if not has_pos and not has_err:
            raise AssembleError(
                f"采集 CSV 既无 x/y/z 也无 pos_err_3d，无法打标签: {path}")

        rows = []
        for row in reader:
            try:
                rec = {
                    "run_id": row["run_id"],
                    "station": row["station"],
                    "day": row["day"],
                    "epoch": int(row["epoch"]),
                    "ns": int(row["ns"]),
                    "deterministic_degraded": int(row["deterministic_degraded"]),
                    "f": [float(row[f"f{i}"]) for i in range(n_features)],
                }
                xyz = [row.get("x"), row.get("y"), row.get("z")]
                if has_pos and all(v not in (None, "") for v in xyz):
                    rec["pos"] = np.array([float(v) for v in xyz], dtype=np.float64)
                elif has_err and row.get("pos_err_3d") not in (None, ""):
                    rec["err"] = float(row["pos_err_3d"])
                else:
                    dropped += 1
                    continue
                rec["run_epochs"] = (int(row["run_epochs"])
                                     if row.get("run_epochs") else 0)
            except (TypeError, ValueError, KeyError) as e:
                raise AssembleError(f"采集 CSV 行无法解析（{path}）: {e}") from e
            rows.append(rec)
    return rows, dropped


def _run_epochs(rows: list[dict]) -> int:
    """该 run 的总历元数：优先用 CSV 的 ``run_epochs``，缺失时退回 ``max(epoch)+1``。"""
    declared = max((r["run_epochs"] for r in rows), default=0)
    if declared > 0:
        return declared
    return max((r["epoch"] for r in rows), default=-1) + 1


def _references(rows: list[dict], truth: "dict[str, np.ndarray] | None",
                reference: str) -> tuple[dict[str, np.ndarray], dict]:
    """按 ``--reference`` 给出每站参考坐标；``truth`` 缺站的 run 整 run 拒用。"""
    stations = sorted({r["station"] for r in rows})
    refs: dict[str, np.ndarray] = {}
    notes: dict[str, str] = {}
    if reference == "truth":
        if not truth:
            raise AssembleError("--reference truth 必须同时给 --truth")
        missing = [st for st in stations if st not in truth]
        if missing:
            raise AssembleError(f"truth.csv 缺少站点 {missing}：无真值即无标签")
        refs = {st: np.asarray(truth[st], dtype=np.float64) for st in stations}
        notes = {st: "truth" for st in stations}
        return refs, notes

    for st in stations:
        pts = np.array([r["pos"] for r in rows if r["station"] == st
                        and "pos" in r], dtype=np.float64).reshape(-1, 3)
        if pts.shape[0] < 10:
            raise AssembleError(f"站 {st} 有效解不足 10 个（{pts.shape[0]}），无法估计簇心")
        center = estimate_center(pts, clip_m=0.5)
        if truth and st in truth:
            offset = float(np.linalg.norm(center - np.asarray(truth[st], dtype=np.float64)))
            notes[st] = f"cluster（与真值文件偏移 {offset:.3f} m，交叉校验）"
        else:
            notes[st] = "cluster（无真值文件，口径自证）"
        refs[st] = center
    return refs, notes


def assemble(collect_dir: Path, truth: "dict[str, np.ndarray] | None" = None,
             threshold_m: float = DEFAULT_THRESHOLD_M, reference: str = "cluster",
             out: Path = Path("data/ppp_solution_validator.parquet")) -> dict:
    """CSV → Parquet，返回诊断报告（同时写入 ``<out>.assemble.json``）。"""
    manifest = load_manifest(collect_dir)

    rows: list[dict] = []
    skipped: list[dict] = []
    n_missing_pos = 0
    for entry in manifest:
        name = (entry.get("csv_file") or "").strip()
        path = collect_dir / name if name else None
        if path is None or not path.exists():
            skipped.append({"run_id": entry.get("run_id"), "reason": "csv 缺失"})
            continue
        run_rows, dropped = _read_run_csv(path, dataset.A_DIM)
        n_missing_pos += dropped
        seen: set = set()
        dup = 0
        for r in run_rows:
            if r["epoch"] in seen:
                dup += 1
            seen.add(r["epoch"])
        if dup:
            skipped.append({"run_id": entry.get("run_id"),
                            "reason": f"epoch 重复 {dup} 行（run_id 内主键必须唯一）"})
            continue
        rows.extend(run_rows)

    if not rows:
        raise AssembleError(f"没有任何可用运行：{collect_dir}")

    refs, ref_notes = _references(rows, truth, reference)

    err = np.empty(len(rows), dtype=np.float64)
    for i, r in enumerate(rows):
        if "err" in r:
            err[i] = r["err"]
        else:
            err[i] = float(np.linalg.norm(r["pos"] - refs[r["station"]]))

    label = (err <= float(threshold_m)).astype(np.float32)

    by_run: dict = {}
    for r in rows:
        by_run.setdefault(r["run_id"], []).append(r)
    run_epochs = {(rs[0]["station"], rs[0]["day"], rid): _run_epochs(rs)
                  for rid, rs in by_run.items()}

    feat_cols = [np.array([r["f"][k] for r in rows], dtype=np.float64)
                 for k in range(dataset.A_DIM)]
    table = pa.Table.from_arrays(
        [pa.array(c, type=pa.float32()) for c in feat_cols]
        + [pa.array(label, type=pa.float32()),
           pa.array([r["epoch"] for r in rows], type=pa.int64()),
           pa.array([r["station"] for r in rows], type=pa.string()),
           pa.array([r["day"] for r in rows], type=pa.string()),
           pa.array([r["run_id"] for r in rows], type=pa.string()),
           pa.array([run_epochs[(r["station"], r["day"], r["run_id"])] for r in rows],
                    type=pa.int32()),
           pa.array(err, type=pa.float32()),
           pa.array([r["ns"] for r in rows], type=pa.int32()),
           pa.array([r["deterministic_degraded"] for r in rows], type=pa.int8())],
        schema=dataset.schema_a())

    dataset.write_a(table, out,
                    note=f"ppp_solution_validator assemble：reference={reference}, "
                         f"threshold={threshold_m} m")

    stations = sorted({r["station"] for r in rows})
    n_invalid = int((label < 0.5).sum())
    warnings = []
    if len(stations) < MIN_STATIONS_WARN:
        warnings.append(f"站数 {len(stations)} < {MIN_STATIONS_WARN}：两层切分做不了")
    if n_invalid < MIN_INVALID_WARN:
        warnings.append(f"不可信历元 {n_invalid} < {MIN_INVALID_WARN}：判别力评估样本不足")
    if reference == "cluster":
        warnings.append("参考坐标取自解自身簇心（无真值文件），标签口径只能自证")
    if n_missing_pos:
        warnings.append(f"{n_missing_pos} 历元缺位置（宿主未提供 PppPositionSource），"
                        "已丢弃、未伪造坐标")

    report = {
        "collect_dir": str(collect_dir),
        "out": str(out),
        "reference": reference,
        "reference_notes": ref_notes,
        "threshold_m": float(threshold_m),
        "n_rows": int(len(rows)),
        "n_runs": int(len(manifest)),
        "n_skipped_runs": int(len(skipped)),
        "skipped": skipped,
        "n_missing_position_rows": int(n_missing_pos),
        "stations": stations,
        "days": sorted({r["day"] for r in rows}),
        "total_epochs": dataset.total_epochs(table),
        "invalid_rate": float(n_invalid / len(rows)),
        "deterministic_degraded_rate": float(
            np.mean([r["deterministic_degraded"] for r in rows])),
        "err_p50": _pct(err, 50), "err_p90": _pct(err, 90),
        "err_p99": _pct(err, 99), "err_max": _pct(err, 100),
        "warnings": warnings,
    }
    json_path = Path(str(out) + ".assemble.json")
    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"组装完成: {out}（{len(rows)} 行 / {len(stations)} 站 / "
          f"{len(report['days'])} 天）")
    print(f"  不可信历元 {n_invalid}（{report['invalid_rate']:.1%}）；"
          f"确定性基线已降级 {report['deterministic_degraded_rate']:.1%}")
    print(f"  3D 误差 p50/p90/p99/max = {_pct(err, 50):.3f} / {_pct(err, 90):.3f} / "
          f"{_pct(err, 99):.3f} / {_pct(err, 100):.3f} m（阈值 {threshold_m} m）")
    for w in warnings:
        print(f"  WARNING: {w}")
    print(f"  诊断报告: {json_path}")
    return report


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(
        description="接口 A 采集结果组装：多 run CSV → 带标签的 Parquet")
    ap.add_argument("--collect-dir", type=Path, required=True,
                    help="PppCollectSession 的输出目录（含 _runs.csv）")
    ap.add_argument("--truth", type=Path, default=None,
                    help="站点已知坐标 CSV（station+ref_x/ref_y/ref_z 或 station+lat/lon/hgt）")
    ap.add_argument("--reference", choices=("truth", "cluster"), default="cluster",
                    help="参考坐标口径：truth 用真值文件，cluster 用解自身稳健簇心")
    ap.add_argument("--threshold", type=float, default=DEFAULT_THRESHOLD_M,
                    help=f"判定「解不可信」的 3D 误差阈值（米），默认 {DEFAULT_THRESHOLD_M}")
    ap.add_argument("--out", type=Path, default=Path("data/ppp_solution_validator.parquet"))
    args = ap.parse_args(argv)

    truth = load_truth(args.truth) if args.truth else None
    assemble(args.collect_dir, truth, args.threshold, args.reference, args.out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())