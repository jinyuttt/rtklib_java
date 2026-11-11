"""接口三采集结果 → Parquet 训练集：17 维特征 + 码残差标签。

三段职责划分（不要混）：

============  ==============================================  ==================
阶段           输入                                            输出
============  ==============================================  ==================
采集（Java）   ``ObsCorrectionContext``（每历元 ns×nf 行）        ``{station}_{day}_{run}.obs.csv``
采集（core）   ``Trace.emit("AI3","DDRES", before/after/Ri/Rj)``  ``{station}_{day}_{run}.trace``
组装（本脚本） 特征 CSV + ``AI3`` trace                               ``obs_corrector.parquet``
============  ==============================================  ==================

三个必须理解的约束：

1. **join key 是 ``(epoch, sat, frq)``，不是时间，也不是列表下标。**
   ``epoch`` 是 core 的进程内历元计数（同一 run 内单调递增），特征行与
   ``DDRES`` 行由同一次解算在同一历元写出，故三列足以唯一定位一行。
   按时间 join 会跨历元错配（同一秒内 ns 行的时间戳完全相同）；
   按下标 join 会在「某些卫星本历元没出现」时整体错位。
2. **标签取 ``DDRES`` 的 ``before``，不取 ``after``。** ``after`` 是扣掉
   ``aiBias`` 之后的残差；采集侧恒不干预（bias=0）时两者相等，但一旦有人
   误把采集会话接到真模型上，``after`` 就变成「已被改正过」的量，再拿来训练
   就是自举污染。``before`` 的定义（[RtkCore.java:2050-2052]）明确是
   「未扣偏差的同一行残差」，与 A 组（无干预）逐位等价。
3. **``DDRES_REJ`` 行默认不进训练集。** 这些行是被 ``maxinno`` 判为野值的样本
   （``|before| > maxinno``），残差量级是米级甚至十米级，把它们当标签会训练出
   一个「预测大偏差」的模型，而模型真正要修的是几厘米到几十厘米的系统性偏差。
   它们仍被统计并报告（``rejected_rows``），便于判断数据质量。

标签口径（``--label-mode``，默认 ``sd``）：

- ``sd``（**默认，唯一可用于训练的口径**）：``label = median_{sat}(before) - before``，
  即把双差残差 ``v = S_ref - S_j`` 还原成**单星误差 ``S_j``**（mod 每 (epoch, frq)
  公共常量）。§6.3 要求标签必须是单星量而非双差残差：core 的施加公式是
  ``v -= aiBias[ref] - aiBias[j]``（**单星量之差**），若拿双差残差当标签，
  学到的量与被扣的量口径不同，改正方向整体反号且不报错（静默错误）。
  中位数正是那个被双差消掉的参考星项（``S_ref = median(v) + median(S)``）。
  **注意符号**：是 ``median - before``，不是 ``before - median``（后者得到 ``-S_j``，
  施加后残差翻倍）。
- ``dd``（**禁止用于训练，仅保留以复现历史结果**）：直接取双差码残差
  ``before``。用它训出的模型在 Java 侧施加时符号与残差同向，实测把
  ``mean|after|`` 从 2.55 m 拉大到 3.45 m（2026-11-11 全量配对复核）。
  **两种口径都必须在验收报告里标注**，不得混用后比较结果。

用法（在 ``rtklib-ai`` 目录下执行）::

    python -m training.obs_corrector.assemble \\
        --collect-dir ../data/fix-validator/collect-obs \\
        --out data/obs_corrector.parquet

前置：采集侧须用 ``ObsCorrectorCollectSession`` 且 trace 打开 ``AI3`` 主题
（见 ``FixValidatorCollectTest#collectObsCorrector``）。
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
    from training.common import dataset
except ImportError:  # 允许直接以脚本方式运行
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
    from training.common import dataset

MANIFEST_NAME = "_obs_runs.csv"
DEFAULT_MIN_JOIN_RATE = 0.90
DEFAULT_LABEL_MODE = "sd"
LABEL_MODES = ("sd", "dd")

_CSV_REQUIRED = ["run_id", "station", "day", "epoch", "sat", "frq"] + \
    [f"f{i}" for i in range(dataset.DIM)]


class AssembleError(RuntimeError):
    """不可继续的组装错误（清单缺失、列不全、join 覆盖率过低等）。"""


def load_manifest(collect_dir: Path) -> list[dict]:
    """读运行清单，只返回 ``status == ok`` 的运行（崩溃的运行不会留下 ok 行）。"""
    path = collect_dir / MANIFEST_NAME
    if not path.exists():
        raise AssembleError(
            f"缺少运行清单 {path}：采集必须通过 ObsCorrectorCollectSession 完成（它负责写清单）")
    with path.open("r", encoding="utf-8", newline="") as f:
        rows = [r for r in csv.DictReader(f) if (r.get("status") or "").strip() == "ok"]
    if not rows:
        raise AssembleError(f"运行清单中没有 status=ok 的运行: {path}")
    return rows


def dedupe_runs(runs: list[dict]) -> tuple[list[dict], list[dict]]:
    """同一 ``(station, day, run_id)`` 的多次采集只保留**最新一次**，返回 ``(保留, 丢弃)``。

    这不是可选的洁癖：``(station, day)`` 是两层切分的第二层键，同一天的两份采集
    进同一份 Parquet 后，切分把该日整体分到验证/测试时，训练侧会同时存在这批行的
    另一份拷贝——同一历元同一条观测既训练又被评估，评估结果直接虚高（§7.3 禁止的
    泄漏，只不过泄漏源不是随机切分而是重复采集）。保留最新一次是最小惊讶的规则：
    后采的通常就是为修正前一次配置/缺陷而重采的。``--keep-all-runs`` 可关闭。

    键里必须带 ``run_id``：小时切片数据同一 ``(station, day)`` 有 ``h00``..``h23``
    共 24 次运行，它们覆盖的是**互不重叠**的历元区间。若仍按 ``(station, day)`` 去重，
    一天只剩 1 小时，其余 23 小时被当成「重复采集」丢弃，数据集会缩水 24 倍。
    带上 ``run_id`` 后语义才正确：去重针对的是「同一片段的重复采集」，
    而不是「同一天的不同片段」。
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
        if (r.get("finished_at") or "") >= (prev.get("finished_at") or ""):
            dropped.append(prev)
            latest[key] = r
        else:
            dropped.append(r)
    kept = list(latest.values())
    return kept, dropped


def _kv(line: str) -> dict:
    """解析 ``TRACE|topic=..|action=..|k=v|...`` 为字典。"""
    kv = {}
    for p in line.rstrip("\n").split("|")[1:]:
        k, _, v = p.partition("=")
        kv[k] = v
    return kv


def parse_trace(path: Path, include_rejected: bool) -> tuple[dict, dict, dict]:
    """提取标签行、历元解状态、统计。

    返回 ``(labels, ar_state, stats)``：

    - ``labels``: ``(epoch, sat, frq) -> before``（米）
    - ``ar_state``: ``epoch -> 2(FIX) / 1(FAIL)``，用于数据集的 ``solution_stat``
    - ``stats``: 各类行数统计，用于报告
    """
    labels: dict[tuple[int, int, int], float] = {}
    ar_state: dict[int, int] = {}
    stats = {"ddres_rows": 0, "rejected_rows": 0, "kept_rows": 0,
             "bad_rows": 0, "duplicate_rows": 0, "fix_epochs": 0, "fail_epochs": 0,
             "corrector_epochs": 0}
    if not path.exists():
        raise AssembleError(f"trace 文件不存在: {path}（采集时须开启 AI3 主题）")

    with path.open("r", encoding="utf-8", errors="replace") as f:
        for raw in f:
            if not raw.startswith("TRACE|"):
                continue
            kv = _kv(raw)
            topic = kv.get("topic")
            action = kv.get("action")
            if topic == "AI3" and action == "CORRECT":
                stats["corrector_epochs"] += 1
                continue
            if topic == "AR":
                try:
                    ep = int(kv["epoch"])
                except (KeyError, ValueError):
                    continue
                if action == "FIX":
                    ar_state[ep] = 2
                    stats["fix_epochs"] += 1
                elif action == "FAIL":
                    ar_state.setdefault(ep, 1)
                    stats["fail_epochs"] += 1
                continue
            if topic != "AI3" or action not in ("DDRES", "DDRES_REJ"):
                continue

            if action == "DDRES_REJ":
                stats["rejected_rows"] += 1
                if not include_rejected:
                    continue
            else:
                stats["ddres_rows"] += 1

            try:
                ep = int(kv["epoch"])
                sat = int(kv["sat"])
                frq = int(kv["frq"])
                before = float(kv["before"])
            except (KeyError, ValueError):
                stats["bad_rows"] += 1
                continue
            if not np.isfinite(before):
                stats["bad_rows"] += 1
                continue
            key = (ep, sat, frq)
            if key in labels:
                stats["duplicate_rows"] += 1
                continue
            labels[key] = before
            stats["kept_rows"] += 1

    return labels, ar_state, stats


def _epoch_medians(labels: dict) -> dict:
    """``(epoch, frq) -> 该历元该频点全体卫星 before 的中位数``（``sd`` 口径用）。"""
    groups: dict[tuple[int, int], list[float]] = defaultdict(list)
    for (ep, _sat, frq), v in labels.items():
        groups[(ep, frq)].append(v)
    return {k: float(np.median(v)) for k, v in groups.items()}


def sd_label(before: float, median: float) -> float:
    """``sd`` 标签：``median(before) - before``（**不是** ``before - median``）。

    ``before`` 是双差码残差 ``v = S_ref - S_j``（``S`` 为单星伪距误差），core 的
    施加公式是 ``v -= aiBias[ref] - aiBias[j]``，故要令 ``v' -> 0`` 必须有
    ``aiBias_j = S_j``，即 **``aiBias = -v``（mod 每 (epoch, frq) 常量）**。
    ``median(v) = S_ref - median(S)`` 正好给出那个被双差消掉的参考星项 ``S_ref``，
    所以 ``S_j = median(v) - v``。写成 ``v - median(v)`` 会得到 ``-S_j``，
    改正方向整体反号（2026-11-11 全量配对复核：``mean|after|`` 2.55 m → 3.45 m，
    76.7% 的行残差被拉大）。
    """
    return median - before


def read_run(obs_csv: Path, labels: dict, ar_state: dict, label_mode: str) -> dict:
    """把一次运行的特征 CSV 与标签 join 起来，返回列式数组。"""
    medians = _epoch_medians(labels) if label_mode == "sd" else None
    feats: list[tuple] = []
    epoch: list[int] = []
    sat: list[int] = []
    frq: list[int] = []
    label: list[float] = []
    stat: list[int] = []
    station: list[str] = []
    day: list[str] = []
    seen: set = set()
    join_stats = {"feature_rows": 0, "joined_rows": 0, "duplicate_rows": 0, "bad_rows": 0}

    with obs_csv.open("r", encoding="utf-8", newline="") as f:
        reader = csv.DictReader(f)
        missing = [c for c in _CSV_REQUIRED if c not in (reader.fieldnames or [])]
        if missing:
            raise AssembleError(f"特征 CSV 缺少列 {missing}: {obs_csv}")
        for row in reader:
            join_stats["feature_rows"] += 1
            try:
                ep = int(row["epoch"])
                s = int(row["sat"])
                q = int(row["frq"])
                f = tuple(float(row[f"f{i}"]) for i in range(dataset.DIM))
            except (TypeError, ValueError):
                join_stats["bad_rows"] += 1
                continue
            key = (ep, s, q)
            if key not in labels:
                continue
            if key in seen:
                join_stats["duplicate_rows"] += 1
                continue
            seen.add(key)

            y = labels[key]
            if label_mode == "sd":
                y = sd_label(y, medians.get((ep, q), 0.0))
            if not np.isfinite(y):
                join_stats["bad_rows"] += 1
                continue

            feats.append(f)
            epoch.append(ep)
            sat.append(s)
            frq.append(q)
            label.append(y)
            stat.append(ar_state.get(ep, 0))
            station.append(row["station"])
            day.append(row["day"])
            join_stats["joined_rows"] += 1

    if not feats:
        raise AssembleError(f"特征与标签零交集: {obs_csv}（trace 主题或 epoch 口径不一致？）")

    return {
        "features": np.asarray(feats, dtype=np.float32),
        "epoch": np.asarray(epoch, dtype=np.int64),
        "sat": np.asarray(sat, dtype=np.int32),
        "frq": np.asarray(frq, dtype=np.int8),
        "label": np.asarray(label, dtype=np.float32),
        "solution_stat": np.asarray(stat, dtype=np.int8),
        "station": station,
        "day": day,
        "join": join_stats,
    }


def to_table(chunks: list[dict]) -> pa.Table:
    """合并各 run 的列式数组为 Parquet 表（列序与 :func:`dataset.schema` 一致）。"""
    f = np.concatenate([c["features"] for c in chunks], axis=0)
    cols = {}
    for i, name in enumerate(dataset.FEATURE_COLUMNS):
        cols[name] = pa.array(f[:, i], type=pa.float32())
    cols[dataset.LABEL_COLUMN] = pa.array(
        np.concatenate([c["label"] for c in chunks]), type=pa.float32())
    cols["epoch"] = pa.array(np.concatenate([c["epoch"] for c in chunks]), type=pa.int64())
    cols["sat"] = pa.array(np.concatenate([c["sat"] for c in chunks]), type=pa.int32())
    cols["frq"] = pa.array(np.concatenate([c["frq"] for c in chunks]), type=pa.int8())
    cols["solution_stat"] = pa.array(
        np.concatenate([c["solution_stat"] for c in chunks]), type=pa.int8())
    cols["station"] = pa.array(sum((c["station"] for c in chunks), []), type=pa.string())
    cols["day"] = pa.array(sum((c["day"] for c in chunks), []), type=pa.string())
    return pa.table({name: cols[name] for name in dataset.REQUIRED_COLUMNS},
                    schema=dataset.schema())


def assemble(collect_dir: Path, out: Path, label_mode: str = DEFAULT_LABEL_MODE,
             min_join_rate: float = DEFAULT_MIN_JOIN_RATE,
             include_rejected: bool = False, note: str | None = None,
             keep_all_runs: bool = False) -> pa.Table:
    """主流程：清单 → 逐 run join → 合并 → 校验 → 写出。"""
    if label_mode not in LABEL_MODES:
        raise AssembleError(f"--label-mode 仅支持 {LABEL_MODES}: {label_mode}")
    if label_mode == "dd":
        print("  [警告] dd 标签（双差残差）与 core 的单星施加口径符号相反，"
              "训出的 bias 会把残差拉大；仅用于复现历史结果，禁止用于训练（§6.3）",
              file=sys.stderr)

    runs = load_manifest(collect_dir)
    if keep_all_runs:
        dropped: list[dict] = []
    else:
        runs, dropped = dedupe_runs(runs)
        for r in dropped:
            print(f"  [跳过] {r['run_id']}: 同站同日已有更新的采集，"
                  f"保留最新可避免同日数据在切分后跨折泄漏")
    chunks: list[dict] = []
    totals = defaultdict(int)

    for r in runs:
        obs_csv = collect_dir / r["obs_csv_file"]
        trace = collect_dir / r["trace_file"]
        if not obs_csv.exists():
            raise AssembleError(f"清单登记的特征 CSV 不存在: {obs_csv}")
        labels, ar_state, tstats = parse_trace(trace, include_rejected)
        if not labels:
            raise AssembleError(
                f"{trace.name} 中没有任何 AI3/DDRES 标签行：该 run 是旧版采集或未开启 AI3 主题。"
                f"请用 ObsCorrectorCollectSession 重新采集（或用更新的 run 覆盖它）")
        for k, v in tstats.items():
            totals[k] += v
        chunk = read_run(obs_csv, labels, ar_state, label_mode)
        for k, v in chunk["join"].items():
            totals[k] += v

        rate = chunk["join"]["joined_rows"] / len(labels)
        if rate < min_join_rate:
            raise AssembleError(
                f"join 覆盖率过低 {rate:.2%} < {min_join_rate:.2%}: {obs_csv.name}。"
                f"特征与标签的 (epoch, sat, frq) 口径不一致，或采集侧未开启 AI3 主题")
        chunks.append(chunk)
        print(f"  {r['run_id']}: rows={chunk['join']['joined_rows']} "
              f"join={rate:.1%} label_mean={chunk['label'].mean():.4f} "
              f"label_std={chunk['label'].std():.4f}")

    table = to_table(chunks)
    dataset.write(table, out, note=note)

    y = dataset.labels(table)
    print(f"\n写出 {out}: {len(table)} 行 / {len(set(table['station'].to_pylist()))} 站 "
          f"/ {len(set(table['day'].to_pylist()))} 日 / 标签口径={label_mode}")
    print(f"标签: mean={y.mean():.4f} std={y.std():.4f} "
          f"p1={np.percentile(y, 1):.4f} p50={np.percentile(y, 50):.4f} "
          f"p99={np.percentile(y, 99):.4f} min={y.min():.4f} max={y.max():.4f}")
    print(f"DDRES 行={totals['ddres_rows']} 剔除(REJ)={totals['rejected_rows']} "
          f"入集={totals['kept_rows']} 重复={totals['duplicate_rows']} "
          f"坏行={totals['bad_rows']}")
    print(f"特征行={totals['feature_rows']} 未配对={totals['feature_rows'] - totals['joined_rows']} "
          f"（未配对的正常来源：本历元未参与双差的卫星/参考星/被 maxinno 剔除的行）")
    print(f"AR: FIX 历元={totals['fix_epochs']} FAIL 历元={totals['fail_epochs']}；"
          f"CORRECT 历元={totals['corrector_epochs']}")
    print("切分提示：先按 station、再按 day，禁止随机切分（相邻历元高度相关，§7.3）")
    return table


def main(argv: "list[str] | None" = None) -> int:
    ap = argparse.ArgumentParser(description="接口三采集结果 → Parquet 训练集")
    ap.add_argument("--collect-dir", required=True, help="采集输出目录（含 _obs_runs.csv）")
    ap.add_argument("--out", required=True, help="输出 Parquet 路径")
    ap.add_argument("--label-mode", default=DEFAULT_LABEL_MODE, choices=LABEL_MODES,
                    help="sd=单星标签（默认，与 core 施加口径一致，§6.3）；"
                         "dd=双差残差（禁止用于训练，仅用于复现历史结果）")
    ap.add_argument("--min-join-rate", type=float, default=DEFAULT_MIN_JOIN_RATE,
                    help="标签行被特征覆盖的最低比例，低于此值直接报错")
    ap.add_argument("--include-rejected", action="store_true",
                    help="把 DDRES_REJ（被 maxinno 判为野值）的行也纳入训练集")
    ap.add_argument("--note", default=None, help="写入 Parquet schema metadata 的备注")
    ap.add_argument("--keep-all-runs", action="store_true",
                    help="关闭同站同日去重（默认只保留最新一次采集，避免同日数据跨折泄漏）")
    args = ap.parse_args(argv)

    try:
        assemble(Path(args.collect_dir), Path(args.out), args.label_mode,
                 args.min_join_rate, args.include_rejected, args.note,
                 args.keep_all_runs)
    except AssembleError as e:
        print(f"组装失败: {e}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())