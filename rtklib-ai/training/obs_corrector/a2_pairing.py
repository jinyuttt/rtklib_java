"""接口三 A2/A3 **双跑配对**统计（设计文档 §7.5.2 / §7.5.3）。

数据来源是 Java 侧 ``ObsCorrectorPairingTest`` 落盘的原始配对 CSV：

* ``pairing.A.sol.csv`` / ``pairing.C.sol.csv`` / ``pairing.D.sol.csv``：
  逐历元解（stat/ns/ECEF）；
* ``pairing.A.ai3.csv`` / ``pairing.C.ai3.csv`` / ``pairing.D.ai3.csv``：
  逐 ``(历元, 星, 频点)`` 的 ``before`` / ``after`` / ``Ri`` / ``Rj``；
* ``_pairing_runs.csv``：每次运行的 ``total_epochs`` 与计数。

三组含义：``A`` 不注入；``C`` bias+scale 全开；``D`` 强制 ``biasMeters≡0``、
仅保留 ``varianceScale``（把两个头拆开，才能分清指标变化是谁的功劳）。
``D`` 组缺文件时自动降级为只做 A/C 对照。

**为什么必须配对**：A2 的四条判据都是「同历元」比较，不是跨数据集比两个比例。
A 组与 C/D 组的解算口径由 ``createOpt()`` 唯一来源保证逐位一致，本脚本只做
「按键 join + 逐站统计」，不再二次解释残差；配对历元取三组**交集**，
保证 A 组基线在 C/D 两次对照中逐位相同。

**真值口径**：本数据集为现场设备，RINEX 头坐标不可用，故参考坐标沿用
``fix_validator.assemble`` 的自动真值（逐日稳健中心 + 跨日中位裁决），
并且**只用 A 组（未注入）的固定解**估计——用 C 组会污染参考、把待评估的改正
算进真值里。C 组与 A 组共用同一参考坐标，误差方向才可比。

用法（在 ``rtklib-ai`` 目录下执行）::

    python -m training.obs_corrector.a2_pairing \
        --pairing-dir ../data/fix-validator/pairing \
        --metrics out/obs_corrector.metrics.json \
        --threshold 0.20 \
        --out out/a2_pairing.json
"""

from __future__ import annotations

import argparse
import csv
import json
import math
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np

try:
    from training.common import metrics
    from training.fix_validator import assemble as fv_assemble
    from training.obs_corrector import train as train_mod
except ImportError:  # 允许直接以脚本方式运行
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
    from training.common import metrics
    from training.fix_validator import assemble as fv_assemble
    from training.obs_corrector import train as train_mod

#: 错误固定判定阈值（D7，v1.7 统一口径）：3D 误差 > 该值即判为错误固定。
#: 与 ``fix_validator.assemble.DEFAULT_THRESHOLD_M`` 同口径，不得在此另设默认值。
DEFAULT_THRESHOLD_M = fv_assemble.DEFAULT_THRESHOLD_M

#: A2 判据阈值（§7.5.2）。
A2_FIX_DROP_PP = 1.0
A2_RMS_REL_TOL = 0.05
A2_RMS_ABS_TOL_M = 0.01
#: A2-4「不显著减少」的显著性水平（配对符号检验）。
A2_NS_ALPHA = 0.05
#: A2-4 的**实用下限**（v1.8 修正）：ns 均值下降必须 > 该值才可能判不通过。
#:
#: 只挂符号检验会过功效：本批配对历元 13.5 万，0.03 颗的均值差（相对 0.4%）
#: 也必然 p<0.05，于是「几乎不可测的抖动」被判成「明显变差」。符号检验回答的是
#: 「差异是否可分辨」，而 A2 要回答的是「差异是否值得在意」，两者必须同时成立：
#: 不通过 ⟺ 均值下降 > 0.1 颗 **且** 符号检验显著（双侧 p < 0.05）。
A2_NS_DROP_EPOCHS = 0.1

#: 配对实验组：``c`` = bias+scale 全开（主判据对象）；
#: ``d`` = 强制 biasMeters≡0、仅保留 varianceScale（隔离尺度头的单独贡献）。
#: 两组与 A 组共用同一份 ``createOpt()`` 解算口径，唯一差别是 AI 注入方式。
EXPERIMENT_GROUPS = ("c", "d")

SOL_FIELDS = ("station", "day", "hour", "group", "gpst", "stat", "ns", "ratio",
              "x", "y", "z")
AI3_FIELDS = ("station", "day", "hour", "group", "epoch", "gpst", "sat", "frq",
              "before", "after", "ri", "rj", "rej")


def _read_csv(path: Path) -> list[dict]:
    if not path.is_file():
        raise SystemExit(f"缺少配对数据: {path}（先跑 ObsCorrectorPairingTest）")
    with path.open("r", encoding="utf-8", newline="") as f:
        return list(csv.DictReader(f))


def load_sol(path: Path) -> dict[tuple, dict]:
    """``(station, day, hour, gpst) -> 解``；同一 run 内 gpst 唯一。"""
    out: dict[tuple, dict] = {}
    for row in _read_csv(path):
        try:
            key = (row["station"], row["day"], row["hour"], row["gpst"])
            out[key] = {
                "stat": int(row["stat"]),
                "ns": int(row["ns"]),
                "ratio": float(row["ratio"]),
                "xyz": (float(row["x"]), float(row["y"]), float(row["z"])),
            }
        except (KeyError, TypeError, ValueError):
            continue
    return out


def _ecef_to_enu(pos: np.ndarray, ref: np.ndarray) -> np.ndarray:
    """ECEF 差 → ENU（以参考点经纬度为旋转基准）。"""
    x, y, z = (float(v) for v in ref)
    lon = math.atan2(y, x)
    lat = math.atan2(z, math.hypot(x, y))
    dx = np.asarray(pos, dtype=np.float64) - np.asarray(ref, dtype=np.float64)
    e = -math.sin(lon) * dx[0] + math.cos(lon) * dx[1]
    n = (-math.sin(lat) * math.cos(lon) * dx[0]
         - math.sin(lat) * math.sin(lon) * dx[1] + math.cos(lat) * dx[2])
    u = (math.cos(lat) * math.cos(lon) * dx[0]
         + math.cos(lat) * math.sin(lon) * dx[1] + math.sin(lat) * dx[2])
    return np.array([e, n, u], dtype=np.float64)


def station_references(sol_a: dict[tuple, dict]) -> tuple[dict, dict]:
    """只用 **A 组固定解** 估计逐站参考坐标（自动真值口径）。

    逐日稳健中心 + 跨日中位裁决，与 ``fix_validator.assemble`` 完全同一实现；
    只有 1 个观测日的站无法跨日校验，仍取中心但记 ``single_day=True`` 警示。
    """
    day_positions: dict[tuple[str, str], list] = defaultdict(list)
    for (st, day, _hour, _gpst), s in sol_a.items():
        if s["stat"] == 1:
            day_positions[(st, day)].append(s["xyz"])

    refs: dict[str, np.ndarray] = {}
    report: dict[str, dict] = {}
    for st in sorted({s for s, _ in day_positions}):
        days = sorted({d for s, d in day_positions if s == st})
        centers = {d: fv_assemble.estimate_center(day_positions[(st, d)],
                                                  fv_assemble.DEFAULT_CLIP_M)
                   for d in days}
        if len(centers) >= 2:
            ref, kept, dropped, dev = fv_assemble._consistent_days(
                centers, fv_assemble.DEFAULT_MAX_DAY_OFFSET_M)
        else:
            ref = np.median(np.stack(list(centers.values())), axis=0)
            kept, dropped, dev = centers, {}, {}
        refs[st] = ref
        report[st] = {
            "n_days": len(days),
            "n_fix_positions": sum(len(day_positions[(st, d)]) for d in days),
            "days_used": sorted(kept),
            "days_dropped": {d: float(v) for d, v in dropped.items()},
            "day_offset_m": {d: float(v) for d, v in dev.items()},
            "single_day": len(days) < 2,
            "xyz": [float(v) for v in ref],
        }
    return refs, report


def scan_ai3(path: Path) -> dict:
    """流式扫描 AI3 行，产出 A3 统计与零基线自检所需的量。

    不把全量行读进内存：全量 6 站 × 4 日 × 24 小时约百万行，``(epoch,sat,frq)``
    键的 dict 会吃掉数 GB。这里只累计标量、逐站计数与逐历元的卫星集合。
    """
    res = {
        "rows": 0,
        "rej_rows": 0,
        "per_station": defaultdict(lambda: {
            "rows": 0, "rej_rows": 0,
            "gate_rows": 0, "drop_sigma_rows": 0, "drop_ratio_rows": 0,
            "valid_rows": 0, "sigma_dd": [], "sats": set(),
        }),
        "epoch_sats": defaultdict(set),
        "zero_baseline_max_abs_diff": 0.0,
        "zero_baseline_nonzero_rows": 0,
        "missing_var_rows": 0,
    }
    with path.open("r", encoding="utf-8", newline="") as f:
        for row in csv.DictReader(f):
            try:
                st = row["station"]
                before = float(row["before"])
                after = float(row["after"])
                rej = row["rej"] == "1"
            except (KeyError, TypeError, ValueError):
                continue
            res["rows"] += 1
            if rej:
                res["rej_rows"] += 1
                res["per_station"][st]["rej_rows"] += 1
                continue
            diff = abs(after - before)
            if diff > res["zero_baseline_max_abs_diff"]:
                res["zero_baseline_max_abs_diff"] = diff
            if diff > 1e-9:
                res["zero_baseline_nonzero_rows"] += 1

            try:
                ri = float(row["ri"])
                rj = float(row["rj"])
            except (KeyError, TypeError, ValueError):
                res["missing_var_rows"] += 1
                continue
            sigma = math.sqrt(max(0.0, ri + rj))

            ent = res["per_station"][st]
            ent["rows"] += 1
            ent["sigma_dd"].append(sigma)
            gate = abs(before) >= train_mod.A3_SIGMA_GATE * sigma
            if not gate:
                continue
            ent["gate_rows"] += 1
            ent["sats"].add(row["sat"])
            drop_sigma = (abs(before) - abs(after)) >= train_mod.A3_DROP_SIGMA * sigma
            drop_ratio = abs(after) <= (1.0 - train_mod.A3_DROP_RATIO) * abs(before)
            if drop_sigma:
                ent["drop_sigma_rows"] += 1
            if drop_ratio:
                ent["drop_ratio_rows"] += 1
            if drop_sigma or drop_ratio:
                ent["valid_rows"] += 1

            key = (st, row["day"], row["hour"], row["gpst"])
            res["epoch_sats"][key].add(row["sat"])
    return res


def _median(values: list[float]) -> float:
    return float(np.median(values)) if values else 0.0


def _group_of(station: str, split: dict) -> str:
    if station in split.get("test_stations", []):
        return "test"
    if station in split.get("val_stations", []):
        return "val"
    if station in split.get("train_stations", []):
        return "train"
    return "unknown"


def _rms(values: np.ndarray) -> float:
    return float(np.sqrt(np.mean(np.square(values))) if values.size else 0.0)


def analyse(pairing_dir: Path, metrics_path: Path, threshold_m: float) -> dict:
    sol: dict[str, dict[tuple, dict]] = {"a": load_sol(pairing_dir / "pairing.A.sol.csv")}
    for g in EXPERIMENT_GROUPS:
        path = pairing_dir / f"pairing.{g.upper()}.sol.csv"
        if path.is_file():
            sol[g] = load_sol(path)
    present = [g for g in EXPERIMENT_GROUPS if g in sol]

    ai3: dict[str, dict | None] = {}
    for g in ["a"] + present:
        path = pairing_dir / f"pairing.{g.upper()}.ai3.csv"
        ai3[g] = scan_ai3(path) if path.is_file() else None

    split = json.loads(metrics_path.read_text(encoding="utf-8"))["split"]
    refs, ref_report = station_references(sol["a"])

    # 三组取**交集**作为配对历元：A 组基线在 C/D 两次对照里必须逐位相同，
    # 否则「A 组固定率」会因配对集合不同而漂移，Δpp 就失去可比性。
    common = set(sol["a"])
    for g in present:
        common &= set(sol[g])
    paired = sorted(common)
    unpaired = {g: len(set(sol[g]) - common) for g in present}

    per_station: dict[str, dict] = {}
    for key in paired:
        st, day, hour, _gpst = key
        ent = per_station.setdefault(st, {
            "n_epochs": 0,
            "n_fix": defaultdict(int), "n_wrong": defaultdict(int),
            "enu": defaultdict(list), "ns": defaultdict(list),
            "ratio": defaultdict(list),
        })
        ent["n_epochs"] += 1
        ref = refs.get(st)
        for g in ["a"] + present:
            sx = sol[g][key]
            ent["ns"][g].append(sx["ns"])
            ent["ratio"][g].append(sx["ratio"])
            if ref is None:
                continue
            ent["enu"][g].append(_ecef_to_enu(sx["xyz"], ref))
            err = float(np.linalg.norm(np.asarray(sx["xyz"]) - ref))
            if sx["stat"] == 1:
                ent["n_fix"][g] += 1
                if err > threshold_m:
                    ent["n_wrong"][g] += 1

    stations: dict[str, dict] = {}
    for st, ent in per_station.items():
        n_epochs = ent["n_epochs"]
        stats_a = metrics.FixStats(n_epochs, ent["n_fix"]["a"], ent["n_wrong"]["a"])
        enu_a = np.asarray(ent["enu"]["a"], dtype=np.float64).reshape(-1, 3)
        out = {
            "role": _group_of(st, split),
            "n_epochs": n_epochs,
            "reference": ref_report.get(st),
            "fix_rate_a": stats_a.fix_rate,
            "wrong_fix_rate_a": stats_a.wrong_fix_rate,
            "n_wrong_fix_a": stats_a.n_wrong_fix,
            "n_fix_a": stats_a.n_fix,
            "h_rms_a": _rms(enu_a[:, 0]),
            "v_rms_a": _rms(enu_a[:, 2]),
            "h2d_rms_a": _rms(np.hypot(enu_a[:, 0], enu_a[:, 1])),
            "ns_mean_a": float(np.mean(ent["ns"]["a"])),
            "ratio_median_a": _median(ent["ratio"]["a"]),
        }
        for g in present:
            stats_g = metrics.FixStats(n_epochs, ent["n_fix"][g], ent["n_wrong"][g])
            enu_g = np.asarray(ent["enu"][g], dtype=np.float64).reshape(-1, 3)
            d_ns = (np.asarray(ent["ns"]["a"], dtype=np.float64)
                    - np.asarray(ent["ns"][g], dtype=np.float64))
            # d_ns > 0 即该组比 A 组少星（A2-4 关心的方向），d_ns < 0 即该组多星。
            n_worse = int((d_ns > 0).sum())
            n_better = int((d_ns < 0).sum())
            ns_p = (metrics.mcnemar_exact(n_better, n_worse)
                    if (n_better or n_worse) else 1.0)
            ent_ai3 = ai3[g]["per_station"].get(st) if ai3.get(g) else None
            out.update({
                f"fix_rate_{g}": stats_g.fix_rate,
                f"fix_delta_pp_{g}": (stats_a.fix_rate - stats_g.fix_rate) * 100.0,
                f"wrong_fix_rate_{g}": stats_g.wrong_fix_rate,
                f"n_wrong_fix_{g}": stats_g.n_wrong_fix,
                f"n_fix_{g}": stats_g.n_fix,
                f"h_rms_{g}": _rms(enu_g[:, 0]),
                f"v_rms_{g}": _rms(enu_g[:, 2]),
                f"h2d_rms_{g}": _rms(np.hypot(enu_g[:, 0], enu_g[:, 1])),
                f"ns_mean_{g}": float(np.mean(ent["ns"][g])),
                f"ns_paired_better_{g}": n_better,
                f"ns_paired_worse_{g}": n_worse,
                f"ns_sign_test_p_{g}": ns_p,
                f"ratio_median_{g}": _median(ent["ratio"][g]),
                f"ai3_rows_{g}": int(ent_ai3["rows"]) if ent_ai3 else 0,
                f"ai3_rej_rows_{g}": int(ent_ai3["rej_rows"]) if ent_ai3 else 0,
                f"a3_gate_rows_{g}": int(ent_ai3["gate_rows"]) if ent_ai3 else 0,
                f"a3_valid_rows_{g}": int(ent_ai3["valid_rows"]) if ent_ai3 else 0,
                f"a3_valid_sats_{g}": len(ent_ai3["sats"]) if ent_ai3 else 0,
                f"a3_sigma_dd_p50_{g}": _median(ent_ai3["sigma_dd"]) if ent_ai3 else 0.0,
            })
        stations[st] = out

    def agg(names: list[str], g: str) -> dict:
        pick = [stations[s] for s in names if s in stations]
        n_epochs = sum(s["n_epochs"] for s in pick)
        stats_a = metrics.FixStats(n_epochs,
                                   sum(s["n_fix_a"] for s in pick),
                                   sum(s["n_wrong_fix_a"] for s in pick))
        stats_g = metrics.FixStats(n_epochs,
                                   sum(s[f"n_fix_{g}"] for s in pick),
                                   sum(s[f"n_wrong_fix_{g}"] for s in pick))
        ns_better = sum(s[f"ns_paired_better_{g}"] for s in pick)
        ns_worse = sum(s[f"ns_paired_worse_{g}"] for s in pick)
        return {
            "stations": sorted(s for s in names if s in stations),
            "group": g,
            "n_epochs": n_epochs,
            "fix_rate_a": stats_a.fix_rate,
            f"fix_rate_{g}": stats_g.fix_rate,
            f"fix_delta_pp_{g}": (stats_a.fix_rate - stats_g.fix_rate) * 100.0,
            "wrong_fix_rate_a": stats_a.wrong_fix_rate,
            f"wrong_fix_rate_{g}": stats_g.wrong_fix_rate,
            "n_wrong_fix_a": stats_a.n_wrong_fix,
            f"n_wrong_fix_{g}": stats_g.n_wrong_fix,
            "h_rms_a": _rms(np.asarray([s["h2d_rms_a"] for s in pick])),
            f"h_rms_{g}": _rms(np.asarray([s[f"h2d_rms_{g}"] for s in pick])),
            "v_rms_a": _rms(np.asarray([s["v_rms_a"] for s in pick])),
            f"v_rms_{g}": _rms(np.asarray([s[f"v_rms_{g}"] for s in pick])),
            "ns_mean_a": float(np.mean([s["ns_mean_a"] for s in pick])) if pick else 0.0,
            f"ns_mean_{g}": (float(np.mean([s[f"ns_mean_{g}"] for s in pick]))
                             if pick else 0.0),
            f"ns_paired_better_{g}": ns_better,
            f"ns_paired_worse_{g}": ns_worse,
            f"ns_sign_test_p_{g}": (metrics.mcnemar_exact(ns_better, ns_worse)
                                    if (ns_better or ns_worse) else 1.0),
            f"a3_gate_rows_{g}": sum(s[f"a3_gate_rows_{g}"] for s in pick),
            f"a3_valid_rows_{g}": sum(s[f"a3_valid_rows_{g}"] for s in pick),
        }

    all_stations = sorted(stations)
    test_stations = sorted(s for s in stations if stations[s]["role"] == "test")
    scopes = {
        "all": all_stations,
        "train": sorted(s for s in stations if stations[s]["role"] == "train"),
        "val": sorted(s for s in stations if stations[s]["role"] == "val"),
        "test": test_stations,
    }
    groups = {g: {name: agg(names, g) for name, names in scopes.items()}
              for g in present}

    a2 = {g: _judge_a2(groups[g]["all"], stations, all_stations, g) for g in present}
    a3 = {g: _judge_a3(ai3[g], test_stations, all_stations, g) for g in present}

    def zb(g: str) -> dict:
        s = ai3.get(g)
        if s is None:
            return {"present": False}
        return {
            "present": True,
            "max_abs_after_minus_before": s["zero_baseline_max_abs_diff"],
            "nonzero_rows": s["zero_baseline_nonzero_rows"],
            "rows": s["rows"],
            "valid_rows": sum(v["valid_rows"] for v in s["per_station"].values()),
            "missing_var_rows": s["missing_var_rows"],
        }

    return {
        "config": {
            "pairing_dir": str(pairing_dir),
            "metrics": str(metrics_path),
            "wrong_fix_threshold_m": threshold_m,
            "a2_fix_drop_pp": A2_FIX_DROP_PP,
            "a2_rms_rel_tol": A2_RMS_REL_TOL,
            "a2_rms_abs_tol_m": A2_RMS_ABS_TOL_M,
            "a2_ns_alpha": A2_NS_ALPHA,
            "a2_ns_drop_epochs": A2_NS_DROP_EPOCHS,
            "a2_ns_criterion": (f"不通过 ⟺ ns 均值下降 > {A2_NS_DROP_EPOCHS} 颗 "
                                f"且 配对符号检验 p < {A2_NS_ALPHA}"),
            "groups_present": present,
            "group_meaning": {
                "c": "biasMeters + varianceScale 全开（主判据对象）",
                "d": "强制 biasMeters≡0，仅 varianceScale 生效（隔离尺度头）",
            },
            "a3_gate": f"|before| >= {train_mod.A3_SIGMA_GATE}σ_dd",
            "a3_drop_sigma": f"|before|-|after| >= {train_mod.A3_DROP_SIGMA}σ_dd",
            "a3_drop_ratio": f"|after| <= {1.0 - train_mod.A3_DROP_RATIO:.2f}|before|",
        },
        "coverage": {
            "paired_epochs": len(paired),
            "unpaired": unpaired,
        },
        "stations": stations,
        "groups": groups,
        "test_stations": test_stations,
        "a2": a2,
        "a3": a3,
        "zero_baseline": {g: zb(g) for g in ["a"] + present},
    }


def _judge_a2(overall: dict, stations: dict, names: list[str], g: str) -> dict:
    """A2-1~A2-4（§7.5.2）；``g`` 为对照组（``c`` 全开 / ``d`` 仅 scale）。

    总判定取**整体口径**（该组 vs A 组，与 §7.5.2「C 组 vs A 组」的写法一致），
    逐站结果一并给出：逐站变差的分站须在报告中单独披露，不得用整体通过掩盖。
    """
    a2_1 = {
        "group": g,
        "fix_delta_pp": overall[f"fix_delta_pp_{g}"],
        "threshold_pp": A2_FIX_DROP_PP,
        "passed": overall[f"fix_delta_pp_{g}"] <= A2_FIX_DROP_PP,
        "per_station": {s: {"fix_delta_pp": stations[s][f"fix_delta_pp_{g}"],
                            "passed": stations[s][f"fix_delta_pp_{g}"] <= A2_FIX_DROP_PP}
                        for s in names},
    }
    rise = overall[f"wrong_fix_rate_{g}"] - overall["wrong_fix_rate_a"]
    a2_2 = {
        "group": g,
        "wrong_fix_rate_a": overall["wrong_fix_rate_a"],
        "wrong_fix_rate_g": overall[f"wrong_fix_rate_{g}"],
        "delta": rise,
        "passed": rise <= 0.0,
        "per_station": {s: {
            "wrong_fix_rate_a": stations[s]["wrong_fix_rate_a"],
            "wrong_fix_rate_g": stations[s][f"wrong_fix_rate_{g}"],
            "delta": stations[s][f"wrong_fix_rate_{g}"] - stations[s]["wrong_fix_rate_a"],
            "passed": (stations[s][f"wrong_fix_rate_{g}"]
                       <= stations[s]["wrong_fix_rate_a"]),
        } for s in names},
    }

    def rms_ok(a: float, b: float) -> bool:
        if a <= 0.0:
            return b <= 0.0
        return (b - a) <= max(A2_RMS_REL_TOL * a, A2_RMS_ABS_TOL_M)

    a2_3 = {
        "group": g,
        "h_rms_a": overall["h_rms_a"], "h_rms_g": overall[f"h_rms_{g}"],
        "v_rms_a": overall["v_rms_a"], "v_rms_g": overall[f"v_rms_{g}"],
        "passed": rms_ok(overall["h_rms_a"], overall[f"h_rms_{g}"])
                  and rms_ok(overall["v_rms_a"], overall[f"v_rms_{g}"]),
        "per_station": {s: {
            "h_rms_a": stations[s]["h2d_rms_a"], "h_rms_g": stations[s][f"h2d_rms_{g}"],
            "v_rms_a": stations[s]["v_rms_a"], "v_rms_g": stations[s][f"v_rms_{g}"],
            "passed": rms_ok(stations[s]["h2d_rms_a"], stations[s][f"h2d_rms_{g}"])
                      and rms_ok(stations[s]["v_rms_a"], stations[s][f"v_rms_{g}"]),
        } for s in names},
    }

    def ns_fail(mean_a: float, mean_g: float, p: float) -> bool:
        """A2-4：均值下降 > 0.1 颗 **且** 符号检验显著，才算「明显变差」。"""
        return (mean_a - mean_g) > A2_NS_DROP_EPOCHS and p < A2_NS_ALPHA

    ns_shrink = overall["ns_mean_a"] - overall[f"ns_mean_{g}"]
    a2_4 = {
        "group": g,
        "ns_mean_a": overall["ns_mean_a"], "ns_mean_g": overall[f"ns_mean_{g}"],
        "ns_mean_delta": -ns_shrink,
        "ns_drop_epochs": ns_shrink,
        "ns_drop_gt_threshold": ns_shrink > A2_NS_DROP_EPOCHS,
        "ns_paired_better": overall[f"ns_paired_better_{g}"],
        "ns_paired_worse": overall[f"ns_paired_worse_{g}"],
        "ns_sign_test_p": overall[f"ns_sign_test_p_{g}"],
        "ns_significant": overall[f"ns_sign_test_p_{g}"] < A2_NS_ALPHA,
        "criterion": (f"不通过 ⟺ 均值下降 > {A2_NS_DROP_EPOCHS} 颗 "
                      f"且 符号检验 p < {A2_NS_ALPHA}"),
        "passed": not ns_fail(overall["ns_mean_a"], overall[f"ns_mean_{g}"],
                              overall[f"ns_sign_test_p_{g}"]),
        "per_station": {s: {
            "ns_mean_a": stations[s]["ns_mean_a"],
            "ns_mean_g": stations[s][f"ns_mean_{g}"],
            "ns_drop_epochs": stations[s]["ns_mean_a"] - stations[s][f"ns_mean_{g}"],
            "sign_test_p": stations[s][f"ns_sign_test_p_{g}"],
            "passed": not ns_fail(stations[s]["ns_mean_a"],
                                  stations[s][f"ns_mean_{g}"],
                                  stations[s][f"ns_sign_test_p_{g}"]),
        } for s in names},
    }
    passed = all(x["passed"] for x in (a2_1, a2_2, a2_3, a2_4))
    return {"A2-1": a2_1, "A2-2": a2_2, "A2-3": a2_3, "A2-4": a2_4,
            "passed": passed,
            "verdict": "通过" if passed else "不通过"}


def _judge_a3(ai3_g: dict, test_stations: list[str], names: list[str],
              group: str = "c") -> dict:
    """A3 真实复核（§7.5.3）：只用该组 trace 的 ``after``，不用离线代理。

    判定范围严格限于**未参与训练、也未参与早停**的测试站；训练站/早停站的
    ``after`` 不能作为 A3 证据。逐站结果仍全量给出，便于看泛化差异。

    ``d`` 组（bias≡0）的有效改正应恒为 0：它是对「trace 是否真的按组注入」的
    反证，若 d 组也出现有效改正，说明 bias 并未真正置零。
    """
    per = {}
    for st in names:
        ent = ai3_g["per_station"].get(st)
        per[st] = {
            "rows": int(ent["rows"]) if ent else 0,
            "gate_rows": int(ent["gate_rows"]) if ent else 0,
            "drop_sigma_rows": int(ent["drop_sigma_rows"]) if ent else 0,
            "drop_ratio_rows": int(ent["drop_ratio_rows"]) if ent else 0,
            "valid_rows": int(ent["valid_rows"]) if ent else 0,
            "valid_sats": len(ent["sats"]) if ent else 0,
            "sigma_dd_p50": _median(ent["sigma_dd"]) if ent else 0.0,
            "is_test_station": st in test_stations,
        }
    n_valid = sum(per[s]["valid_rows"] for s in test_stations)
    sats = set()
    for st in test_stations:
        ent = ai3_g["per_station"].get(st)
        if ent:
            sats |= ent["sats"]
    n_sats = len(sats)
    gate_rows = sum(per[s]["gate_rows"] for s in test_stations)
    passed = n_valid >= train_mod.A3_MIN_VALID_ROWS and n_sats >= train_mod.A3_MIN_SATS
    return {
        "group": group,
        "test_stations": test_stations,
        "rows": sum(per[s]["rows"] for s in test_stations),
        "gate_rows": gate_rows,
        "valid_rows": n_valid,
        "valid_sats": n_sats,
        "sigma_dd_p50": _median([per[s]["sigma_dd_p50"] for s in test_stations]),
        "passed": passed,
        "verdict": (f"通过（{n_valid} 行 / {n_sats} 星）" if passed
                    else f"不通过（{n_valid} 行 / {n_sats} 星，"
                         f"需 ≥{train_mod.A3_MIN_VALID_ROWS} 行且 "
                         f"≥{train_mod.A3_MIN_SATS} 星）"),
        "per_station": per,
    }


def _fmt_pct(v: float) -> str:
    return f"{v * 100:.2f}%"


def print_table(res: dict) -> None:
    st = res["stations"]
    present = res["config"]["groups_present"]
    names = res["test_stations"] + [s for s in sorted(st) if s not in res["test_stations"]]

    print("\n表 1 解算质量（A=不注入；C=bias+scale；D=仅 scale）")
    head1 = f"{'站':<16}{'角色':<7}{'配对历元':>9}{'固定率A':>9}"
    for g in present:
        head1 += f"{'固定率' + g.upper():>9}{'Δpp' + g.upper():>8}"
    head1 += f"{'错固A':>8}" + "".join(f"{'错固' + g.upper():>8}" for g in present)
    head1 += f"{'H_A':>7}" + "".join(f"{'H_' + g.upper():>7}" for g in present)
    head1 += f"{'V_A':>7}" + "".join(f"{'V_' + g.upper():>7}" for g in present)
    print(head1)
    print("-" * len(head1.encode("gbk")))
    for s in names:
        v = st[s]
        mark = "★" if v["role"] == "test" else " "
        line = (f"{mark}{s:<15}{v['role']:<7}{v['n_epochs']:>9}"
                f"{_fmt_pct(v['fix_rate_a']):>9}")
        for g in present:
            line += (f"{_fmt_pct(v[f'fix_rate_{g}']):>9}"
                     f"{v[f'fix_delta_pp_{g}']:>8.2f}")
        line += f"{_fmt_pct(v['wrong_fix_rate_a']):>8}"
        for g in present:
            line += f"{_fmt_pct(v[f'wrong_fix_rate_{g}']):>8}"
        line += f"{v['h2d_rms_a'] * 100:>7.1f}"
        for g in present:
            line += f"{v[f'h2d_rms_{g}'] * 100:>7.1f}"
        line += f"{v['v_rms_a'] * 100:>7.1f}"
        for g in present:
            line += f"{v[f'v_rms_{g}'] * 100:>7.1f}"
        print(line)

    print("\n表 2 ns 与 A3（Δns = A 组 - 该组，正=该组卫星数更少）")
    head2 = f"{'站':<16}{'nsA':>6}"
    for g in present:
        head2 += f"{'ns' + g.upper():>6}{'Δns' + g.upper():>8}{'p' + g.upper():>9}"
    for g in present:
        head2 += f"{'A3行' + g.upper():>8}{'A3有效' + g.upper():>9}"
    print(head2)
    print("-" * len(head2.encode("gbk")))
    for s in names:
        v = st[s]
        mark = "★" if v["role"] == "test" else " "
        line = f"{mark}{s:<15}{v['ns_mean_a']:>6.1f}"
        for g in present:
            line += (f"{v[f'ns_mean_{g}']:>6.1f}"
                     f"{v['ns_mean_a'] - v[f'ns_mean_{g}']:>8.2f}"
                     f"{v[f'ns_sign_test_p_{g}']:>9.2e}")
        for g in present:
            line += (f"{v[f'ai3_rows_{g}']:>8}"
                     f"{v[f'a3_valid_rows_{g}']:>9}")
        print(line)

    print("\n位置 RMS 单位 cm（H 为水平 2D，V 为垂直）；错固 = 3D 误差 > "
          f"{res['config']['wrong_fix_threshold_m']:.2f} m 的固定解占比（分母为固定历元）")

    for key, label in (("test", "★ 测试站（未见）"), ("val", "早停站"),
                       ("train", "训练站"), ("all", "合计")):
        if not res["groups"]["c"][key]["stations"]:
            continue
        print(f"\n {label}")
        for g in present:
            gg = res["groups"][g][key]
            print(f"   [{g}] {len(gg['stations']):>2} 站 {gg['n_epochs']:>7} 历元"
                  f"  固定率 {_fmt_pct(gg['fix_rate_a'])}→{_fmt_pct(gg[f'fix_rate_{g}'])}"
                  f"（{gg[f'fix_delta_pp_{g}']:+.2f} pp）"
                  f"  错固 {_fmt_pct(gg['wrong_fix_rate_a'])}→"
                  f"{_fmt_pct(gg[f'wrong_fix_rate_{g}'])}"
                  f"  H {gg['h_rms_a'] * 100:.1f}→{gg[f'h_rms_{g}'] * 100:.1f} cm"
                  f"  V {gg['v_rms_a'] * 100:.1f}→{gg[f'v_rms_{g}'] * 100:.1f} cm"
                  f"  ns {gg['ns_mean_a']:.2f}→{gg[f'ns_mean_{g}']:.2f}"
                  f"（Δ{gg['ns_mean_a'] - gg[f'ns_mean_{g}']:+.2f}，"
                  f"p={gg[f'ns_sign_test_p_{g}']:.2e}）")

    for g in present:
        a2 = res["a2"][g]
        print(f"\nA2 总判定[{g}]: {a2['verdict']}")
        for k in ("A2-1", "A2-2", "A2-3", "A2-4"):
            print(f"  {k}: {'通过' if a2[k]['passed'] else '不通过'}")
        a2_4 = a2["A2-4"]
        print(f"    A2-4 细节: 均值下降 {a2_4['ns_drop_epochs']:.3f} 颗"
              f"（阈值 {A2_NS_DROP_EPOCHS}，"
              f"{'已超' if a2_4['ns_drop_gt_threshold'] else '未超'}）"
              f" / 符号检验 p={a2_4['ns_sign_test_p']:.2e}"
              f"（{'显著' if a2_4['ns_significant'] else '不显著'}）")

    for g in present:
        a3 = res["a3"][g]
        print(f"\nA3 真实复核[{g}]（测试站 {a3['test_stations']}）: {a3['verdict']}")
        print(f"  行 {a3['rows']} / G0 {a3['gate_rows']} / 有效 {a3['valid_rows']}"
              f"（{a3['valid_sats']} 星）/ σ_dd 中位 {a3['sigma_dd_p50']:.3f} m")
        if g == "d":
            print("  注: D 组 bias≡0，有效改正应恒为 0；其 A3 数值是「注入是否真置零」"
                  "的反证，不参与 A3 判据。")

    for g in ("a",) + tuple(present):
        z = res["zero_baseline"].get(g) or {}
        if not z.get("present"):
            continue
        print(f"\n零基线自检[{g}]：max|after-before| = "
              f"{z['max_abs_after_minus_before']:.3e}"
              f"（非零行 {z['nonzero_rows']}/{z['rows']}）"
              f"，有效改正 {z['valid_rows']} 行")


def main(argv=None) -> int:
    repo = Path(__file__).resolve().parents[3]
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--pairing-dir", type=Path,
                    default=repo / "data" / "fix-validator" / "pairing")
    ap.add_argument("--metrics", type=Path,
                    default=Path(__file__).resolve().parents[1] / "out"
                    / "obs_corrector.metrics.json")
    ap.add_argument("--threshold", type=float, default=DEFAULT_THRESHOLD_M,
                    help=f"错误固定阈值（米），默认 {DEFAULT_THRESHOLD_M:.2f}（D7）")
    ap.add_argument("--out", type=Path,
                    default=Path(__file__).resolve().parents[1] / "out"
                    / "a2_pairing.json")
    args = ap.parse_args(argv)

    res = analyse(args.pairing_dir, args.metrics, args.threshold)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(res, ensure_ascii=False, indent=2),
                        encoding="utf-8")
    print_table(res)
    print(f"\n报告已写入: {args.out}")
    ok = res["a2"]["c"]["passed"] and res["a3"]["c"]["passed"]
    return 0 if ok else 1


if __name__ == "__main__":
    raise SystemExit(main())