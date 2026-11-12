"""双接口协同四跑（A/B/C/D）配对统计与归因。

读取 ``DualInterfacePairingTest`` 落下的四组同历元配对数据，回答三个问题：

* **B vs A**：接口一单独注入时，固定率损失多少、其中多少是「误杀好固」、
  多少是「切掉错固」；
* **C vs A**：接口三单独注入时，多造了多少错固、净固定率与 RMS 变化；
* **D vs C**：在接口三已生效的前提下，接口一又切掉多少错固、同时误杀多少好固。

统计口径与 ``a2_pairing.py`` 完全一致：错误固定阈值取
``fix_validator.assemble.DEFAULT_THRESHOLD_M``；参考坐标**只用 A 组固定解**估计；
四组取配对历元交集；H RMS 取逐站 2D 水平 RMS 再跨站 RMS（与报告 §4.2 同口径）。

用法：
    python -m training.obs_corrector.dual_interface_pairing \
        --pairing-dir ../data/fix-validator/pairing-dual \
        --fix-metrics ../data/fix-validator/out/fix_validator.metrics.json \
        --obs-metrics training/out/obs_corrector_e2e.metrics.json
"""

from __future__ import annotations

import argparse
import json
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np

try:
    from training.fix_validator import assemble as fv_assemble
    from training.obs_corrector import a2_pairing as a2
except ImportError:  # 允许直接以脚本方式运行
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
    from training.fix_validator import assemble as fv_assemble
    from training.obs_corrector import a2_pairing as a2

DEFAULT_THRESHOLD_M = fv_assemble.DEFAULT_THRESHOLD_M

GROUPS = ("a", "b", "c", "d")
GROUP_MEANING = {
    "a": "不注入（基线，参考坐标来源）",
    "b": "仅接口一 fix_validator",
    "c": "仅接口三 obs_corrector_e2e",
    "d": "接口一 + 接口三",
}

#: 归因状态：固定且 3D 误差 ≤ 阈值 = good；固定且 > 阈值 = wrong；未固定 = none。
STATE_GOOD = "good"
STATE_WRONG = "wrong"
STATE_NONE = "none"


def _read_csv(path: Path) -> list[dict]:
    if not path.is_file():
        raise SystemExit(f"缺少配对数据: {path}（先跑 DualInterfacePairingTest）")
    return a2._read_csv(path)


def load_rejects(path: Path) -> dict[str, dict[tuple, dict]]:
    """``group -> (station, day, hour, gpst) -> AI_REJECT 行``。"""
    out: dict[str, dict[tuple, dict]] = {"b": {}, "d": {}}
    if not path.is_file():
        return out
    for row in _read_csv(path):
        g = row.get("group", "").lower()
        if g not in out:
            continue
        key = (row["station"], row["day"], row["hour"], row["gpst"])
        out[g][key] = row
    return out


def classify(sol_row: dict | None, ref: np.ndarray | None,
             threshold_m: float) -> str:
    if sol_row is None or ref is None or sol_row["stat"] != 1:
        return STATE_NONE
    err = float(np.linalg.norm(np.asarray(sol_row["xyz"], dtype=np.float64) - ref))
    return STATE_WRONG if err > threshold_m else STATE_GOOD


def station_stats(sol: dict[tuple, dict], paired: list[tuple],
                  refs: dict[str, np.ndarray], threshold_m: float) -> dict[str, dict]:
    per: dict[str, dict] = {}
    for key in paired:
        st = key[0]
        ent = per.setdefault(st, {
            "n_epochs": 0, "n_fix": 0, "n_wrong": 0,
            "enu": [], "ns": [],
        })
        ent["n_epochs"] += 1
        sx = sol.get(key)
        if sx is None:
            continue
        ent["ns"].append(sx["ns"])
        ref = refs.get(st)
        if ref is None:
            continue
        ent["enu"].append(a2._ecef_to_enu(np.asarray(sx["xyz"]), ref))
        if sx["stat"] == 1:
            ent["n_fix"] += 1
            err = float(np.linalg.norm(
                np.asarray(sx["xyz"], dtype=np.float64) - ref))
            if err > threshold_m:
                ent["n_wrong"] += 1
    return per


def aggregate(per: dict[str, dict], names: list[str]) -> dict:
    pick = [per[s] for s in names if s in per]
    n_epochs = sum(s["n_epochs"] for s in pick)
    n_fix = sum(s["n_fix"] for s in pick)
    n_wrong = sum(s["n_wrong"] for s in pick)
    enu = (np.vstack([np.asarray(s["enu"], dtype=np.float64).reshape(-1, 3)
                      for s in pick]) if pick else np.zeros((0, 3)))
    # 与报告 §4.2 同口径：逐站 2D 水平 RMS，再跨站 RMS。
    h_station = np.asarray([a2._rms(np.hypot(
        np.asarray(s["enu"], dtype=np.float64).reshape(-1, 3)[:, 0],
        np.asarray(s["enu"], dtype=np.float64).reshape(-1, 3)[:, 1]))
        for s in pick if s["enu"]], dtype=np.float64)
    v_station = np.asarray([a2._rms(
        np.asarray(s["enu"], dtype=np.float64).reshape(-1, 3)[:, 2])
        for s in pick if s["enu"]], dtype=np.float64)
    return {
        "stations": sorted(s for s in names if s in per),
        "n_epochs": n_epochs,
        "n_fix": n_fix,
        "n_wrong": n_wrong,
        "fix_rate": n_fix / n_epochs if n_epochs else 0.0,
        "wrong_fix_rate": n_wrong / n_fix if n_fix else 0.0,
        "h_rms_station_pooled": a2._rms(h_station),
        "v_rms_station_pooled": a2._rms(v_station),
        "h2d_rms_global": (a2._rms(np.hypot(enu[:, 0], enu[:, 1]))
                           if enu.size else 0.0),
        "v_rms_global": a2._rms(enu[:, 2]) if enu.size else 0.0,
        "ns_mean": (float(np.mean(np.concatenate(
            [np.asarray(s["ns"], dtype=np.float64) for s in pick]))
            if any(s["ns"] for s in pick) else 0.0)),
    }


def transitions(base: dict, treat: dict, paired: list[tuple],
                refs: dict[str, np.ndarray], threshold_m: float,
                scope: set[str]) -> dict:
    trans: dict[tuple[str, str], int] = defaultdict(int)
    for key in paired:
        st = key[0]
        if st not in scope:
            continue
        ref = refs.get(st)
        if ref is None:
            continue
        sb = classify(base.get(key), ref, threshold_m)
        stt = classify(treat.get(key), ref, threshold_m)
        trans[(sb, stt)] += 1
    n_good_base = sum(v for (s, _), v in trans.items() if s == STATE_GOOD)
    n_wrong_base = sum(v for (s, _), v in trans.items() if s == STATE_WRONG)
    n_fix_base = n_good_base + n_wrong_base
    n_fix_treat = sum(v for (_, t), v in trans.items()
                      if t in (STATE_GOOD, STATE_WRONG))
    n_wrong_treat = sum(v for (_, t), v in trans.items() if t == STATE_WRONG)
    n_epochs = sum(trans.values())
    return {
        "n_epochs": n_epochs,
        "n_fix_base": n_fix_base,
        "n_wrong_base": n_wrong_base,
        "n_fix_treat": n_fix_treat,
        "n_wrong_treat": n_wrong_treat,
        "fix_delta_pp": ((n_fix_treat - n_fix_base) / n_epochs * 100.0
                         if n_epochs else 0.0),
        "wrong_delta": n_wrong_treat - n_wrong_base,
        "wrong_cut": trans[(STATE_WRONG, STATE_NONE)],
        "wrong_kept": trans[(STATE_WRONG, STATE_WRONG)],
        "wrong_to_good": trans[(STATE_WRONG, STATE_GOOD)],
        "good_killed": trans[(STATE_GOOD, STATE_NONE)],
        "good_to_wrong": trans[(STATE_GOOD, STATE_WRONG)],
        "good_kept": trans[(STATE_GOOD, STATE_GOOD)],
        "new_fix_good": trans[(STATE_NONE, STATE_GOOD)],
        "new_fix_wrong": trans[(STATE_NONE, STATE_WRONG)],
        "good_kill_rate": (trans[(STATE_GOOD, STATE_NONE)] / n_good_base
                           if n_good_base else 0.0),
        "wrong_cut_rate": (trans[(STATE_WRONG, STATE_NONE)] / n_wrong_base
                           if n_wrong_base else 0.0),
        "transitions": {f"{s}->{t}": v for (s, t), v in sorted(trans.items())},
    }


def reject_breakdown(rej: dict[tuple, dict], baseline: dict,
                     refs: dict[str, np.ndarray], threshold_m: float,
                     scope: set[str]) -> dict:
    by = {STATE_GOOD: 0, STATE_WRONG: 0, STATE_NONE: 0}
    in_scope = 0
    for key in rej:
        if key[0] not in scope:
            continue
        in_scope += 1
        by[classify(baseline.get(key), refs.get(key[0]), threshold_m)] += 1
    return {"rejects": in_scope, "by_baseline_state": by}


def _split_roles(metrics_path: Path) -> dict:
    if not metrics_path.is_file():
        return {}
    return json.loads(metrics_path.read_text(encoding="utf-8")).get("split", {})


def _fmt_pct(v: float) -> str:
    return f"{v * 100:.2f}%"


def analyse(pairing_dir: Path, fix_metrics: Path, obs_metrics: Path,
            threshold_m: float) -> dict:
    sol = {g: a2.load_sol(pairing_dir / f"pairing.{g.upper()}.sol.csv")
           for g in GROUPS}
    rej = load_rejects(pairing_dir / "pairing.rejects.csv")

    refs, ref_report = a2.station_references(sol["a"])
    common = set(sol["a"])
    for g in GROUPS[1:]:
        common &= set(sol[g])
    paired = sorted(common)
    unpaired = {g: len(set(sol[g]) - common) for g in GROUPS[1:]}

    per = {g: station_stats(sol[g], paired, refs, threshold_m) for g in GROUPS}
    stations = sorted(per["a"])

    fix_split = _split_roles(fix_metrics)
    obs_split = _split_roles(obs_metrics)

    def held_out(split: dict, st: str) -> bool:
        return (st not in split.get("train_stations", [])
                and st not in split.get("val_stations", []))

    scopes = {
        "all": set(stations),
        "fix_test": {s for s in stations if s in fix_split.get("test_stations", [])},
        "obs_test": {s for s in stations if s in obs_split.get("test_stations", [])},
        "both_holdout": {s for s in stations
                         if held_out(fix_split, s) and held_out(obs_split, s)},
        "fix_holdout": {s for s in stations if held_out(fix_split, s)},
        "obs_holdout": {s for s in stations if held_out(obs_split, s)},
    }

    table: dict[str, dict] = {}
    for scope, names in scopes.items():
        rows = {}
        for g in GROUPS:
            row = aggregate(per[g], sorted(names))
            if g in rej:
                baseline = sol["a"] if g == "b" else sol["c"]
                row["reject"] = reject_breakdown(
                    rej[g], baseline, refs, threshold_m, names)
            else:
                row["reject"] = {"rejects": 0, "by_baseline_state":
                                 {STATE_GOOD: 0, STATE_WRONG: 0, STATE_NONE: 0}}
            rows[g] = row
        attribution = {
            "b_vs_a": transitions(sol["a"], sol["b"], paired, refs,
                                  threshold_m, names),
            "c_vs_a": transitions(sol["a"], sol["c"], paired, refs,
                                  threshold_m, names),
            "d_vs_c": transitions(sol["c"], sol["d"], paired, refs,
                                  threshold_m, names),
        }
        table[scope] = {"rows": rows, "attribution": attribution}

    # 互补/抵消判定只在整体口径上做一次，逐范围数字全部披露。
    overall = table["all"]
    a, b, c, d = (overall["rows"][g] for g in GROUPS)
    dvc = overall["attribution"]["d_vs_c"]
    criteria = {
        "d_fix_rate_not_below_a": d["fix_rate"] >= a["fix_rate"],
        "d_wrong_fix_rate_below_a": d["wrong_fix_rate"] < a["wrong_fix_rate"],
        "d_wrong_fix_rate_below_c": d["wrong_fix_rate"] < c["wrong_fix_rate"],
        "d_wrong_fix_abs_below_c": d["n_wrong"] < c["n_wrong"],
        "d_rms_not_worse_than_a": (d["h_rms_station_pooled"]
                                   <= a["h_rms_station_pooled"] + 1e-9
                                   and d["v_rms_station_pooled"]
                                   <= a["v_rms_station_pooled"] + 1e-9),
    }
    complementary = all(criteria.values())
    net_effect = {
        "d_minus_c_wrong_cut": dvc["wrong_cut"],
        "d_minus_c_good_killed": dvc["good_killed"],
        "d_minus_c_net_wrong": dvc["wrong_delta"],
        "d_minus_c_fix_delta_pp": dvc["fix_delta_pp"],
        "benefit_cost_ratio": (dvc["wrong_cut"] / dvc["good_killed"]
                               if dvc["good_killed"] else None),
    }

    return {
        "config": {
            "pairing_dir": str(pairing_dir),
            "fix_metrics": str(fix_metrics),
            "obs_metrics": str(obs_metrics),
            "wrong_fix_threshold_m": threshold_m,
            "group_meaning": GROUP_MEANING,
            "scopes": {k: sorted(v) for k, v in scopes.items()},
        },
        "coverage": {"paired_epochs": len(paired), "unpaired": unpaired},
        "references": ref_report,
        "table": table,
        "verdict": {
            "complementary": complementary,
            "criteria": criteria,
            "d_vs_c_net_effect": net_effect,
        },
    }


def print_table(res: dict) -> None:
    print("\n=== 四组对比（范围: all）===")
    print("| 组 | 注入 | 固定率 | 错误固定率 | 错固绝对数 | H RMS | V RMS | AI_REJECT |")
    print("|---|---|---|---|---|---|---|---|")
    rows = res["table"]["all"]["rows"]
    for g in GROUPS:
        r = rows[g]
        print(f"| {g.upper()} | {GROUP_MEANING[g]} | {_fmt_pct(r['fix_rate'])} | "
              f"{_fmt_pct(r['wrong_fix_rate'])} | {r['n_wrong']} | "
              f"{r['h_rms_station_pooled'] * 100:.1f} cm | "
              f"{r['v_rms_station_pooled'] * 100:.1f} cm | "
              f"{r['reject']['rejects']} |")

    print("\n=== 归因（范围: all）===")
    att = res["table"]["all"]["attribution"]
    bva, cva, dvc = att["b_vs_a"], att["c_vs_a"], att["d_vs_c"]
    print(f"B vs A: 固定率 {bva['fix_delta_pp']:+.2f} pp；"
          f"误杀好固 {bva['good_killed']}（{_fmt_pct(bva['good_kill_rate'])}）；"
          f"切掉错固 {bva['wrong_cut']}（{_fmt_pct(bva['wrong_cut_rate'])}）；"
          f"错固残留 {bva['wrong_kept']}")
    print(f"C vs A: 固定率 {cva['fix_delta_pp']:+.2f} pp；"
          f"新增错固 {cva['new_fix_wrong']}；错固净变 {cva['wrong_delta']:+d}；"
          f"错固转好 {cva['wrong_to_good']}")
    print(f"D vs C: 固定率 {dvc['fix_delta_pp']:+.2f} pp；"
          f"切掉错固 {dvc['wrong_cut']}；误杀好固 {dvc['good_killed']}；"
          f"错固净变 {dvc['wrong_delta']:+d}；"
          f"错固残留 {dvc['wrong_kept']}；新增错固 {dvc['new_fix_wrong']}")

    rej = res["table"]["all"]["rows"]
    for g in ("b", "d"):
        rb = rej[g]["reject"]["by_baseline_state"]
        print(f"{g.upper()} 组 AI_REJECT={rej[g]['reject']['rejects']}："
              f"按基线解判定 good={rb[STATE_GOOD]} / wrong={rb[STATE_WRONG]} / "
              f"none={rb[STATE_NONE]}")

    verdict = res["verdict"]
    print("\n=== 判定 ===")
    for k, v in verdict["criteria"].items():
        print(f"{'PASS' if v else 'FAIL'}  {k}")
    print("结论: " + ("优势互补" if verdict["complementary"] else "未达优势互补（可能互相抵消）"))
    net = verdict["d_vs_c_net_effect"]
    print(f"D−C 净效果: 切错固 {net['d_minus_c_wrong_cut']}，"
          f"误杀好固 {net['d_minus_c_good_killed']}，"
          f"净错固 {net['d_minus_c_net_wrong']:+d}，"
          f"固定率 {net['d_minus_c_fix_delta_pp']:+.2f} pp，"
          f"收益/代价={net['benefit_cost_ratio']}")


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--pairing-dir", type=Path,
                    default=Path("..") / "data" / "fix-validator" / "pairing-dual")
    ap.add_argument("--fix-metrics", type=Path,
                    default=Path("..") / "data" / "fix-validator" / "out"
                    / "fix_validator.metrics.json")
    ap.add_argument("--obs-metrics", type=Path,
                    default=Path("training") / "out" / "obs_corrector_e2e.metrics.json")
    ap.add_argument("--threshold", type=float, default=DEFAULT_THRESHOLD_M,
                    help=f"错误固定阈值（米），默认 {DEFAULT_THRESHOLD_M:.2f}（D7）")
    ap.add_argument("--out", type=Path,
                    default=Path("training") / "out" / "dual_interface_pairing.json")
    args = ap.parse_args(argv)

    res = analyse(args.pairing_dir, args.fix_metrics, args.obs_metrics,
                  args.threshold)
    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(res, ensure_ascii=False, indent=2),
                        encoding="utf-8")
    print_table(res)
    print(f"\nJSON: {args.out}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())