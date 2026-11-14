"""接口四采集结果 → Parquet 训练集：15 维特征 + 滑相风险弱标签。

三段职责划分（与接口三同构，不要混）：

============  ==================================================  ==========================
阶段           输入                                                输出
============  ==================================================  ==========================
采集（Java）   ``RefStarContext``（每历元 ns×nf 行）                 ``{station}_{day}_{run}.refstar.csv``
采集（core）   ``Trace.emit("AI4","IS_REF", sat/frq/...)``           ``{station}_{day}_{run}.trace``
组装（本脚本） 特征 CSV + ``AI4/IS_REF`` trace                        ``refstar_sliprisk.parquet``
============  ==================================================  ==========================

**没有步骤 0 就没有标签。** ``IS_REF`` 行必须在<b>未注入接口四</b>的状态下先跑一遍
（此时记录的正是 core 的原生参考星，即标签本身），再用 ``RefStarCollectSession``
在同一配置下重跑一遍产出特征。用注入了模型的 run 去取 ``IS_REF``，
拿到的是「被模型换过之后的参考星」，标签会自我确认。

标签口径（弱标签，§5.2）：

- 只保留 ``is_ref == 1`` 的行（当历元是参考星）；
- 正例：随后 ``W`` 历元内该 ``(sat, frq)`` 出现**滑相**（``slip != 0``）或**失锁**
  （``lock`` 相比当历元下降 / 归零）；
- 负例：随后 ``W`` 历元内该 ``(sat, frq)`` 全程在场、``slip == 0``、``lock`` 不下降；
- 窗口不完整的行（后续历元缺行）**丢弃并计数**——用「缺行」当失锁会把卫星起落
  当成滑相事件，正例比例会被系统性抬高。

事件聚类：同一 ``(station, day, sat)`` 的连续正例历元聚成一个 ``event_id``，
供双阈值终止条件按事件而非按行计数（相邻历元高度相关，按行计数会让样本量虚高）。

用法（在 ``rtklib-ai`` 目录下执行）::

    python -m training.ref_star.assemble \\
        --collect-dir ../data/refstar/collect \\
        --out data/refstar_sliprisk.parquet
"""

from __future__ import annotations

import argparse
import csv
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np
import pyarrow as pa

try:
    from training.ref_star import dataset
except ImportError:  # 允许直接以脚本方式运行
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
    from training.ref_star import dataset

MANIFEST_NAME = "_refstar_runs.csv"
DEFAULT_MIN_JOIN_RATE = 0.90
DEFAULT_LABEL_WINDOW = dataset.DEFAULT_LABEL_WINDOW

_CSV_REQUIRED = ["run_id", "station", "day", "epoch", "sat", "frq"] + \
    [f"f{i}" for i in range(dataset.DIM)]

#: 特征列在 CSV 中的下标（``f4 = lock``、``f5 = slip``，与 ``RefStarFeatures`` 一致）。
_COL_LOCK = 4
_COL_SLIP = 5


class AssembleError(RuntimeError):
    """不可继续的组装错误（清单缺失、列不全、join 覆盖率过低等）。"""


def load_manifest(collect_dir: Path) -> list[dict]:
    """读运行清单，只返回 ``status == ok`` 的运行（崩溃的运行不会留下 ok 行）。"""
    path = collect_dir / MANIFEST_NAME
    if not path.exists():
        raise AssembleError(
            f"缺少运行清单 {path}：采集必须通过 RefStarCollectSession 完成（它负责写清单）")
    with path.open("r", encoding="utf-8", newline="") as f:
        rows = [r for r in csv.DictReader(f) if (r.get("status") or "").strip() == "ok"]
    if not rows:
        raise AssembleError(f"运行清单中没有 status=ok 的运行: {path}")
    return rows


def dedupe_runs(runs: list[dict]) -> tuple[list[dict], list[dict]]:
    """同一 ``(station, day, run_id)`` 的重复采集只保留最新一次。

    理由与接口三完全相同：``(station, day)`` 是切分第二层键，同日两份采集进同一份
    Parquet 后，切分把该日整体分到验证/测试时，训练侧仍有这批行的另一份拷贝，
    评估结果虚高。键里必须带 ``run_id``，否则小时切片数据会被误当重复而缩水。
    """
    latest: dict[tuple[str, str, str], dict] = {}
    dropped: list[dict] = []
    for r in runs:
        key = ((r.get("station") or "").strip(),
               (r.get("day") or "").strip(),
               (r.get("run_id") or "").strip())
        prev = latest.get(key)
        if prev is None:
            latest[key] = r
            continue
        keep, drop = (r, prev) if r.get("finished_at", "") >= prev.get("finished_at", "") \
            else (prev, r)
        latest[key] = keep
        dropped.append(drop)
    return list(latest.values()), dropped


def _kv(line: str) -> dict:
    """解析 ``TRACE|topic=..|action=..|k=v|...`` 为字典。"""
    kv = {}
    for p in line.rstrip("\n").split("|")[1:]:
        k, _, v = p.partition("=")
        kv[k] = v
    return kv


def parse_trace(path: Path) -> tuple[set, dict]:
    """提取 ``AI4/IS_REF`` 行：``(is_ref_keys, stats)``。

    ``is_ref_keys`` = ``{(epoch, sat, frq)}``。同一 ``(epoch, sat, frq)`` 会同时出现在
    code 与 phase 两个 group 里，用集合去重——标签是「这颗星当参考星」，
    与码/相无关。
    """
    keys: set = set()
    stats = defaultdict(int)
    if not path.exists():
        raise AssembleError(f"trace 文件不存在: {path}（采集时须开启 AI4 主题）")

    with path.open("r", encoding="utf-8", errors="replace") as f:
        for raw in f:
            if not raw.startswith("TRACE|"):
                continue
            kv = _kv(raw)
            if kv.get("topic") != "AI4":
                continue
            action = kv.get("action")
            stats[f"ai4_{action}"] += 1
            if action != "IS_REF":
                continue
            try:
                ep = int(kv["epoch"])
                sat = int(kv["sat"])
                frq = int(kv["frq"])
            except (KeyError, ValueError):
                stats["bad_rows"] += 1
                continue
            keys.add((ep, sat, frq))
    return keys, dict(stats)


def _series(path: Path) -> tuple[list[dict], dict, dict]:
    """读一次运行的特征 CSV，返回 ``(行, 序列, join统计)``。

    ``序列`` = ``(sat, frq) -> {epoch: (lock, slip)}``，用于按 W 历元窗口打标签。
    """
    rows: list[dict] = []
    series: dict[tuple[int, int], dict[int, tuple[float, float]]] = defaultdict(dict)
    stats = defaultdict(int)
    with path.open("r", encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        missing = [c for c in _CSV_REQUIRED if c not in (reader.fieldnames or [])]
        if missing:
            raise AssembleError(f"特征 CSV 缺少列 {missing}: {path}")
        for rec in reader:
            stats["feature_rows"] += 1
            try:
                ep = int(rec["epoch"])
                sat = int(rec["sat"])
                frq = int(rec["frq"])
            except (KeyError, ValueError):
                stats["bad_rows"] += 1
                continue
            feats = np.array([float(rec[f"f{i}"]) for i in range(dataset.DIM)], dtype=np.float64)
            if not np.all(np.isfinite(feats)):
                stats["bad_rows"] += 1
                continue
            rows.append({"epoch": ep, "sat": sat, "frq": frq,
                         "station": rec["station"], "day": rec["day"],
                         "features": feats})
            series[(sat, frq)][ep] = (feats[_COL_LOCK], feats[_COL_SLIP])
    return rows, series, dict(stats)


def label_rows(rows: list[dict], series: dict, is_ref: set,
               window: int = DEFAULT_LABEL_WINDOW) -> tuple[list[dict], dict]:
    """按弱标签定义给每行打标签，丢弃非参考星与窗口不完整的行。"""
    kept: list[dict] = []
    stats = defaultdict(int)
    for r in rows:
        key = (r["epoch"], r["sat"], r["frq"])
        if key not in is_ref:
            stats["not_ref_rows"] += 1
            continue
        seq = series[(r["sat"], r["frq"])]
        lock0 = seq[r["epoch"]][0]
        label = 0.0
        complete = True
        for t in range(r["epoch"] + 1, r["epoch"] + window + 1):
            cur = seq.get(t)
            if cur is None:
                complete = False
                break
            lock_t, slip_t = cur
            lost = slip_t != 0.0 or (lock_t < lock0) or (lock_t <= 0.0 < lock0)
            if lost:
                label = 1.0
                break
        if not complete and label == 0.0:
            stats["incomplete_window_rows"] += 1
            continue
        out = dict(r)
        out["label"] = label
        out["is_ref"] = 1
        kept.append(out)
    return kept, dict(stats)


def _cluster_events(rows: list[dict]) -> int:
    """按 ``(station, day, sat)`` 的连续正例历元聚事件，就地写 ``event_id``。"""
    order = sorted(range(len(rows)),
                   key=lambda k: (rows[k]["station"], rows[k]["day"],
                                  rows[k]["sat"], rows[k]["epoch"], rows[k]["frq"]))
    nxt = 0
    prev_key = None
    prev_epoch = None
    cur = -1
    for k in order:
        r = rows[k]
        key = (r["station"], r["day"], r["sat"])
        if r["label"] > 0.5:
            # ``cur < 0`` 必须显式判：上一行是负例时 ``cur`` 已被复位，
            # 此时即使 key 相同且历元连续，也必须开新事件，否则整段正例被写成 -1。
            if cur < 0 or key != prev_key or prev_epoch is None or r["epoch"] != prev_epoch + 1:
                cur = nxt
                nxt += 1
            r["event_id"] = cur
        else:
            r["event_id"] = -1
            cur = -1
        prev_key = key
        prev_epoch = r["epoch"]
    return nxt


def to_table(rows: list[dict]) -> pa.Table:
    """合并为 Parquet 表（列序与 :func:`dataset.schema` 一致）。"""
    f = np.stack([r["features"] for r in rows], axis=0)
    cols = {name: pa.array(f[:, i], type=pa.float32())
            for i, name in enumerate(dataset.FEATURE_COLUMNS)}
    cols[dataset.LABEL_COLUMN] = pa.array(
        np.array([r["label"] for r in rows], dtype=np.float32), type=pa.float32())
    cols["epoch"] = pa.array(np.array([r["epoch"] for r in rows], dtype=np.int64), type=pa.int64())
    cols["sat"] = pa.array(np.array([r["sat"] for r in rows], dtype=np.int32), type=pa.int32())
    cols["frq"] = pa.array(np.array([r["frq"] for r in rows], dtype=np.int8), type=pa.int8())
    cols["station"] = pa.array([r["station"] for r in rows], type=pa.string())
    cols["day"] = pa.array([r["day"] for r in rows], type=pa.string())
    cols["is_ref"] = pa.array(np.ones(len(rows), dtype=np.int8), type=pa.int8())
    cols["event_id"] = pa.array(
        np.array([r["event_id"] for r in rows], dtype=np.int32), type=pa.int32())
    return pa.table({name: cols[name] for name in dataset.REQUIRED_COLUMNS},
                    schema=dataset.schema())


def assemble(collect_dir: Path, out: Path, window: int = DEFAULT_LABEL_WINDOW,
             min_join_rate: float = DEFAULT_MIN_JOIN_RATE,
             note: str | None = None, keep_all_runs: bool = False) -> pa.Table:
    """主流程：清单 → 逐 run join → 打标签 → 合并 → 校验 → 写出。"""
    if window < 1:
        raise AssembleError(f"--window 必须 >= 1: {window}")

    runs = load_manifest(collect_dir)
    if keep_all_runs:
        dropped: list[dict] = []
    else:
        runs, dropped = dedupe_runs(runs)
        for r in dropped:
            print(f"  [跳过] {r['run_id']}: 同站同日已有更新的采集")

    all_rows: list[dict] = []
    totals = defaultdict(int)
    for r in runs:
        csv_path = collect_dir / r["refstar_csv_file"]
        trace = collect_dir / r["trace_file"]
        if not csv_path.exists():
            raise AssembleError(f"清单登记的特征 CSV 不存在: {csv_path}")
        is_ref, tstats = parse_trace(trace)
        for k, v in tstats.items():
            totals[k] += v
        if not is_ref:
            raise AssembleError(
                f"{trace.name} 中没有任何 AI4/IS_REF 行：该 run 未开启 AI4 主题，"
                f"或用了注入模型的 run 取标签（标签必须来自未注入的原生参考星轨迹）")

        rows, series, fstats = _series(csv_path)
        for k, v in fstats.items():
            totals[k] += v
        if not rows:
            raise AssembleError(f"特征 CSV 无有效行: {csv_path}")

        keys_in_csv = {(r["epoch"], r["sat"], r["frq"]) for r in rows}
        hit = sum(1 for k in is_ref if k in keys_in_csv)
        rate = hit / len(is_ref)
        if rate < min_join_rate:
            raise AssembleError(
                f"join 覆盖率过低 {rate:.2%} < {min_join_rate:.2%}: {csv_path.name}。"
                f"特征与标签的 (epoch, sat, frq) 口径不一致，或特征采集与 IS_REF "
                f"不是同一次运行（重跑必须用同一配置）")
        totals["is_ref_rows"] += len(is_ref)
        totals["is_ref_joined"] += hit

        kept, lstats = label_rows(rows, series, is_ref, window)
        for k, v in lstats.items():
            totals[k] += v
        all_rows.extend(kept)
        print(f"  {r['run_id']}: feature_rows={len(rows)} is_ref={len(is_ref)} "
              f"join={rate:.1%} kept={len(kept)}")

    if not all_rows:
        raise AssembleError("没有任何可用样本：全部行都是非参考星或窗口不完整")

    events = _cluster_events(all_rows)
    table = to_table(all_rows)
    dataset.write(table, out, note=note)

    y = dataset.labels(table)
    pos = int(y.sum())
    print(f"\n写出 {out}: {len(table)} 行 / {len(set(table['station'].to_pylist()))} 站 "
          f"/ {len(set(table['day'].to_pylist()))} 日 / 窗口 W={window}")
    print(f"标签: 正例={pos} ({pos / len(table):.2%}) 事件数={events}")
    print(f"IS_REF 行={totals['is_ref_rows']} 命中特征={totals['is_ref_joined']}；"
          f"非参考星行={totals['not_ref_rows']} 窗口不完整={totals['incomplete_window_rows']}")
    print("切分提示：先按 station、再按 day，禁止随机切分（相邻历元高度相关）")
    print("提醒：没有步骤 0（is_ref trace + 同配置重跑）时，本产物不能用于验收")
    return table


def main(argv: "list[str] | None" = None) -> int:
    ap = argparse.ArgumentParser(description="接口四采集结果 → Parquet 训练集")
    ap.add_argument("--collect-dir", required=True, help="采集输出目录（含 _refstar_runs.csv）")
    ap.add_argument("--out", required=True, help="输出 Parquet 路径")
    ap.add_argument("--window", type=int, default=DEFAULT_LABEL_WINDOW,
                    help="标签窗口 W（历元数），须与 AiOpts.refStarWindowEpochs 一致")
    ap.add_argument("--min-join-rate", type=float, default=DEFAULT_MIN_JOIN_RATE,
                    help="IS_REF 行被特征覆盖的最低比例，低于此值直接报错")
    ap.add_argument("--note", default=None, help="写入 Parquet schema metadata 的备注")
    ap.add_argument("--keep-all-runs", action="store_true",
                    help="关闭同站同日去重（默认只保留最新一次采集）")
    args = ap.parse_args(argv)

    try:
        assemble(Path(args.collect_dir), Path(args.out), args.window,
                 args.min_join_rate, args.note, args.keep_all_runs)
    except AssembleError as e:
        print(f"组装失败: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())