"""接口 B 采集结果组装：多 run CSV → 带目标的 Parquet（设计文档 §12.2 / §4.6）。

采集 CSV 由 ``PppCollectSession`` 写出，一行一个 ``(sat, frq)`` 行——与 core 侧
``ns × nf`` 的行序完全一致（``idx = satIndex * nf + frq``）：

.. code-block:: text

    run_id,station,day,epoch,run_epochs,sat,frq,nf,f0..f11,resp,sigma

- ``f0..f11`` 与 ``PppVarianceFeatures`` 逐位一致，由采集侧复用同一个特征提取函数；
- ``resp`` 是该行的**非差伪距残差**（米），``sigma`` 是该行的名义标准差（米，
  来自 core 的 R 矩阵对角元开方）。二者只在采集侧可取，Python 侧无法复算；
- 训练目标 ``v_norm = resp / sigma``（无量纲归一化残差），由本脚本生成。

**为什么目标用 ``v_norm`` 而不是米制残差**：``PppVarianceScaler`` 输出的是方差
膨胀因子（无量纲），模型若直接回归米制残差，就得同时学「各系统 / 各频点的 σ 量级」，
跨站跨系统无法共用一套损失。归一化后目标与输出同尺度，物理含义是「该行残差是
名义 σ 的几倍」。

**为什么行级而不是历元级**：core 侧把因子逐行乘到 R 的对角元，行级样本才能让
「低仰角 / 低 SNR / 周跳行」单独得到更大的因子（§4.6）。

拒绝情形（都写进 ``<out>.assemble.json``，不静默）：

======================  ====================================================
情形                     处理
======================  ====================================================
``sigma <= 0`` 或非有限     该行丢弃并计数（core 用 0 方差表示该行被剔除）
``resp`` 非有限            该行丢弃并计数
``_runs.csv`` 无 ``status=ok``  该 run 跳过
单 run 的 ``(epoch, sat, frq)`` 重复  整 run 跳过（主键必须唯一）
======================  ====================================================
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
    from training.fix_validator.assemble import AssembleError, MANIFEST_NAME, _pct
except ImportError:  # 允许直接以脚本方式运行
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
    from training.common import ppp_dataset as dataset
    from training.fix_validator.assemble import AssembleError, MANIFEST_NAME, _pct

MIN_STATIONS_WARN = 4
MIN_ROWS_WARN = 5000

SCALE_MANIFEST_NAME = "_runs_ppp_scale.csv"


def _load_manifest(collect_dir: Path) -> list[dict]:
    """读接口 B 的运行清单，只返回 ``status == ok`` 的运行。

    ``PppCollectSession`` 为接口 B 单独写 ``_runs_ppp_scale.csv``：B 的 ``rows`` 是
    观测行数、A 的 ``rows`` 是历元数，混用同一份清单会让「残缺运行」判定失效。
    目录里没有 B 清单时退回 ``_runs.csv``，兼容只采了单接口的旧目录。
    """
    path = collect_dir / SCALE_MANIFEST_NAME
    if not path.exists():
        path = collect_dir / MANIFEST_NAME
    if not path.exists():
        raise AssembleError(
            f"缺少运行清单 {collect_dir / SCALE_MANIFEST_NAME}："
            "采集必须通过 PppCollectSession 完成（它负责写清单）")
    with path.open("r", encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        rows = [r for r in reader if (r.get("status") or "").strip() == "ok"]
    if not rows:
        raise AssembleError(f"运行清单中没有 status=ok 的运行: {path}")
    return rows


def _read_run_csv(path: Path, n_features: int) -> tuple[list[dict], int, int]:
    """读单 run CSV；返回 (行, 丢弃行数, 重复主键行数)。"""
    required = (["run_id", "station", "day", "epoch", "sat", "frq", "nf", "resp", "sigma"]
                + [f"f{i}" for i in range(n_features)])
    with path.open("r", encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        fields = set(reader.fieldnames or [])
        missing = [c for c in required if c not in fields]
        if missing:
            raise AssembleError(f"采集 CSV 缺少列 {missing}: {path}")

        rows, dropped, dup = [], 0, 0
        seen: set = set()
        for row in reader:
            try:
                sigma = float(row["sigma"])
                resp = float(row["resp"])
                rec = {
                    "run_id": row["run_id"],
                    "station": row["station"],
                    "day": row["day"],
                    "epoch": int(row["epoch"]),
                    "sat": int(row["sat"]),
                    "frq": int(row["frq"]),
                    "nf": int(row["nf"]),
                    "sigma": sigma,
                    "resp": resp,
                    "run_epochs": (int(row["run_epochs"])
                                   if row.get("run_epochs") else 0),
                    "f": [float(row[f"f{i}"]) for i in range(n_features)],
                }
            except (TypeError, ValueError, KeyError) as e:
                raise AssembleError(f"采集 CSV 行无法解析（{path}）: {e}") from e
            if not np.isfinite(sigma) or sigma <= 0.0 or not np.isfinite(resp):
                dropped += 1
                continue
            key = (rec["epoch"], rec["sat"], rec["frq"])
            if key in seen:
                dup += 1
            seen.add(key)
            rows.append(rec)
    return rows, dropped, dup


def _run_epochs(rows: list[dict]) -> int:
    declared = max((r["run_epochs"] for r in rows), default=0)
    if declared > 0:
        return declared
    return max((r["epoch"] for r in rows), default=-1) + 1


def assemble(collect_dir: Path,
             out: Path = Path("data/ppp_variance_scaler.parquet")) -> dict:
    """CSV → Parquet，返回诊断报告（同时写入 ``<out>.assemble.json``）。"""
    manifest = _load_manifest(collect_dir)

    rows: list[dict] = []
    skipped: list[dict] = []
    n_dropped = 0
    for entry in manifest:
        # _load_manifest 已解析到接口 B 清单（_runs_ppp_scale.csv）；csv_file 指向 B 的
        # 行级 CSV。回退 csv_file 仅为兼容「单接口采集目录」的旧清单。
        name = (entry.get("scale_csv_file") or entry.get("csv_file") or "").strip()
        path = collect_dir / name if name else None
        if path is None or not path.exists():
            skipped.append({"run_id": entry.get("run_id"), "reason": "csv 缺失"})
            continue
        run_rows, dropped, dup = _read_run_csv(path, dataset.B_DIM)
        n_dropped += dropped
        if dup:
            skipped.append({"run_id": entry.get("run_id"),
                            "reason": f"(epoch, sat, frq) 重复 {dup} 行"})
            continue
        rows.extend(run_rows)

    if not rows:
        raise AssembleError(f"没有任何可用行：{collect_dir}")

    v_norm = np.array([r["resp"] / r["sigma"] for r in rows], dtype=np.float64)

    by_run: dict = {}
    for r in rows:
        by_run.setdefault(r["run_id"], []).append(r)
    run_epochs = {(rs[0]["station"], rs[0]["day"], rid): _run_epochs(rs)
                  for rid, rs in by_run.items()}

    feat_cols = [np.array([r["f"][k] for r in rows], dtype=np.float64)
                 for k in range(dataset.B_DIM)]
    table = pa.Table.from_arrays(
        [pa.array(c, type=pa.float32()) for c in feat_cols]
        + [pa.array(v_norm, type=pa.float32()),
           pa.array([r["epoch"] for r in rows], type=pa.int64()),
           pa.array([r["station"] for r in rows], type=pa.string()),
           pa.array([r["day"] for r in rows], type=pa.string()),
           pa.array([r["run_id"] for r in rows], type=pa.string()),
           pa.array([run_epochs[(r["station"], r["day"], r["run_id"])] for r in rows],
                    type=pa.int32()),
           pa.array([r["sat"] for r in rows], type=pa.int32()),
           pa.array([r["frq"] for r in rows], type=pa.int32()),
           pa.array([r["nf"] for r in rows], type=pa.int32()),
           pa.array([r["sigma"] for r in rows], type=pa.float32())],
        schema=dataset.schema_b())

    dataset.write_b(table, out, note="ppp_variance_scaler assemble")

    stations = sorted({r["station"] for r in rows})
    abs_v = np.abs(v_norm)
    warnings = []
    if len(stations) < MIN_STATIONS_WARN:
        warnings.append(f"站数 {len(stations)} < {MIN_STATIONS_WARN}：两层切分做不了")
    if len(rows) < MIN_ROWS_WARN:
        warnings.append(f"行数 {len(rows)} < {MIN_ROWS_WARN}：模型只能欠拟合")
    if n_dropped:
        warnings.append(f"丢弃 {n_dropped} 行（sigma<=0 或非有限残差）")
    if float(np.mean(abs_v <= 1.0)) > 0.90:
        warnings.append(f"|v_norm|<=1 占比 {float(np.mean(abs_v <= 1.0)):.1%} 过高："
                        "名义 σ 系统性偏大，膨胀因子将全为 1（模型学不到东西）")

    report = {
        "collect_dir": str(collect_dir),
        "out": str(out),
        "n_rows": int(len(rows)),
        "n_runs": int(len(manifest)),
        "n_skipped_runs": int(len(skipped)),
        "skipped": skipped,
        "n_dropped_rows": int(n_dropped),
        "stations": stations,
        "days": sorted({r["day"] for r in rows}),
        "total_epochs": dataset.total_epochs(table),
        "nf_max": int(max(r["nf"] for r in rows)),
        "v_norm_abs_p50": _pct(abs_v, 50),
        "v_norm_abs_p90": _pct(abs_v, 90),
        "v_norm_abs_p99": _pct(abs_v, 99),
        "coverage_1sigma": float(np.mean(abs_v <= 1.0)),
        "sigma_p50": _pct(np.array([r["sigma"] for r in rows], dtype=np.float64), 50),
        "warnings": warnings,
    }
    json_path = Path(str(out) + ".assemble.json")
    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    print(f"组装完成: {out}（{len(rows)} 行 / {len(stations)} 站 / "
          f"{len(report['days'])} 天 / 丢弃 {n_dropped} 行）")
    print(f"  |v_norm| p50/p90/p99 = {report['v_norm_abs_p50']:.3f} / "
          f"{report['v_norm_abs_p90']:.3f} / {report['v_norm_abs_p99']:.3f}；"
          f"1σ 覆盖率 {report['coverage_1sigma']:.1%}（理想 68.3%）")
    print(f"  名义 σ p50 = {report['sigma_p50']:.3f} m")
    for w in warnings:
        print(f"  WARNING: {w}")
    print(f"  诊断报告: {json_path}")
    return report


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(
        description="接口 B 采集结果组装：多 run CSV → 带目标的 Parquet")
    ap.add_argument("--collect-dir", type=Path, required=True,
                    help="PppCollectSession 的输出目录（含 _runs_ppp_scale.csv）")
    ap.add_argument("--out", type=Path, default=Path("data/ppp_variance_scaler.parquet"))
    args = ap.parse_args(argv)
    assemble(args.collect_dir, args.out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())