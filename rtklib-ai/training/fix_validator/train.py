"""接口一（固定校验）训练管道入口（设计文档 §5.1 / §6.1 / §7.4）。

流程定死为：读 Parquet → 两层切分（按站、再按天）→ 训练集拟合归一化 → 训练
→ 早停 → 验证集扫阈值 → 导出 ONNX + ``norm.json`` → 契约校验 → 测试站评估（G-1~G-4）。

与接口三的两点关键差异：

1. **输出只有一个置信度**（``[batch, 1]``，落在 ``[0, 1]``），阈值判定不在这里做，
   永远由 core 侧 ``AiOpts.fixValidatorMinConfidence`` 完成（换阈值不换模型）；
2. **标签是二值「固定是否正确」**，错误固定是少数类，训练按 ``--wrong-weight`` 加权，
   否则模型会退化成「全部接受」——那等于没有 AI。

用法（在 ``rtklib-ai`` 目录下执行）::

    python -m training.fix_validator.train --demo
    python -m training.fix_validator.train --dataset data/fix_validator.parquet --out-dir out

**示例数据只证明链路跑通，不承诺模型效果**（§6.3）。
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np

try:
    from training.common import export_onnx, metrics
    from training.common import fix_dataset as dataset
except ImportError:  # 允许直接以脚本方式运行
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
    from training.common import export_onnx, metrics
    from training.common import fix_dataset as dataset

MODEL_NAME = "fix_validator.onnx"
NORM_NAME = "fix_validator.norm.json"

#: 几何退化门禁（§7.4.3 / 决策 D14）：pdop 超过该值的历元**不参与剔除**。
#: 这类历元「高 DOP 但固定正确」的占比很高，紧预算下会占满低分档、把预算打在
#: 假警报上（实测最低 1% 档的 pdop 显著高于整体，见 §8.2.8）。
PDOP_ABSTAIN_MAX = 8.0
PDOP_FEATURE_INDEX = dataset.FEATURE_NAMES.index("pdop")


def _accepted(conf: np.ndarray, threshold: float, pdop: "np.ndarray | None" = None) -> np.ndarray:
    """接受掩码 = 「置信度达标」∪「几何退化历元（不参与剔除）」。

    pdop 非有限（缺失）时同样按「不参与剔除」处理：与 §9 的「impl 异常/非有限 →
    不干预」同一取向，宁可少剔除，不可因特征缺失而误拒。
    """
    accepted = np.asarray(conf, dtype=np.float64) >= float(threshold)
    if pdop is not None:
        accepted = accepted | ~(np.asarray(pdop, dtype=np.float64) <= PDOP_ABSTAIN_MAX)
    return accepted


class _Normalizer:
    """训练集拟合、推理侧原样加载的归一化（禁止按运行数据动态归一化，§5.1）。"""

    def __init__(self, mean: np.ndarray, std: np.ndarray):
        self.mean = np.asarray(mean, dtype=np.float32)
        self.std = np.asarray(std, dtype=np.float32)

    @classmethod
    def fit(cls, x: np.ndarray) -> "_Normalizer":
        mean = x.mean(axis=0)
        std = x.std(axis=0)
        std = np.where((std < 1e-8) | ~np.isfinite(std), 1.0, std)
        return cls(mean, std)

    def apply(self, x: np.ndarray) -> np.ndarray:
        return (np.asarray(x, dtype=np.float32) - self.mean) / self.std


def build_model(hidden: int):
    """MLP + sigmoid 头：输出即置信度，天然落在 ``[0, 1]``。

    ``logits()`` 单独暴露给训练用：``binary_cross_entropy_with_logits`` 比在
    sigmoid 饱和区上算 BCE 数值更稳，且不会出现 ``log(0)``。
    """
    import torch
    from torch import nn

    class FixValidatorNet(nn.Module):
        def __init__(self):
            super().__init__()
            self.fc1 = nn.Linear(dataset.DIM, hidden)
            self.fc2 = nn.Linear(hidden, hidden)
            self.head = nn.Linear(hidden, 1)

        def _trunk(self, x):
            h = torch.relu(self.fc1(x))
            return torch.relu(self.fc2(h))

        def forward(self, x):
            return torch.sigmoid(self.head(self._trunk(x)))

        def logits(self, x):
            return self.head(self._trunk(x)).squeeze(1)

    return FixValidatorNet()


def train(model, x_train, y_train, x_val, y_val, epochs: int, batch_size: int,
          lr: float, patience: int, seed: int, wrong_weight: float):
    """加权 BCE 训练 + 早停。

    ``wrong_weight`` 作用在 ``label = 0``（错误固定）的样本上：错误固定只占几个百分点，
    不加权时「全部接受」就是损失最小的解，模型学不到任何判别力。
    """
    import torch
    from torch.nn import functional as F

    torch.manual_seed(seed)
    opt = torch.optim.Adam(model.parameters(), lr=lr)
    xt = torch.from_numpy(np.asarray(x_train, dtype=np.float32))
    yt = torch.from_numpy(np.asarray(y_train, dtype=np.float32))
    xv = torch.from_numpy(np.asarray(x_val, dtype=np.float32))
    yv = torch.from_numpy(np.asarray(y_val, dtype=np.float32))

    def loss_fn(xb, yb):
        logit = model.logits(xb)
        w = torch.where(yb > 0.5, torch.ones_like(yb), torch.full_like(yb, wrong_weight))
        return F.binary_cross_entropy_with_logits(logit, yb, weight=w)

    best = {"loss": float("inf"), "state": None, "epoch": -1}
    bad = 0
    n = xt.shape[0]
    for epoch in range(epochs):
        model.train()
        perm = torch.randperm(n)
        total = 0.0
        for i in range(0, n, batch_size):
            idx = perm[i:i + batch_size]
            opt.zero_grad()
            loss = loss_fn(xt[idx], yt[idx])
            loss.backward()
            opt.step()
            total += float(loss.detach()) * len(idx)
        model.eval()
        with torch.no_grad():
            val_loss = float(loss_fn(xv, yv))
        train_loss = total / n
        print(f"  epoch {epoch + 1:3d}/{epochs}: train={train_loss:.4f} val={val_loss:.4f}")
        if val_loss < best["loss"] - 1e-4:
            best = {"loss": val_loss, "state": {k: v.clone() for k, v in model.state_dict().items()},
                    "epoch": epoch + 1}
            bad = 0
        else:
            bad += 1
            if bad >= patience:
                print(f"  早停：验证损失连续 {patience} 轮未改善（最佳 epoch={best['epoch']}）")
                break
    if best["state"] is not None:
        model.load_state_dict(best["state"])
    model.eval()
    return best


def _onnx_predict(onnx_path: Path, x: np.ndarray, batch: int = 1024) -> np.ndarray:
    """用导出的 ONNX 实测推理（评估必须走产物，不能走训练态模型）。"""
    import onnxruntime as ort

    sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    name = sess.get_inputs()[0].name
    outs = []
    for i in range(0, x.shape[0], batch):
        outs.append(sess.run(None, {name: np.asarray(x[i:i + batch], dtype=np.float32)})[0])
    return np.concatenate(outs, axis=0)[:, 0].astype(np.float64)


def _threshold_candidates(conf: np.ndarray, step: float, n_quantiles: int = 2001) -> list:
    """阈值候选：粗网格 + 置信度分位点。

    只用 ``np.arange(0.02, 1, 0.02)`` 会漏掉 [0, 0.1] 这一小段——而 MLP 的 sigmoid 输出
    常常在低分段高度集中，G-2 的 1pp 预算恰好落在这段的几条候选之间，导致「想用满
    预算却跨不过去」。分位点候选把「剔除数量」按固定历元比例细分（默认 0.05% 一档），
    使可行域内能取到接近预算上限的点，从而把 G-1 的可用空间真正释放出来。
    """
    grid = np.round(np.arange(step, 1.0, step), 4)
    q = np.quantile(conf, np.linspace(0.0, 1.0, n_quantiles))
    return np.unique(np.round(np.concatenate([grid, q]), 6)).tolist()


def select_threshold_multi(station_data: dict, max_fix_drop_pp: float = 1.0,
                           rel_target: float = 0.30, step: float = 0.02,
                           n_boot: int = 300) -> dict:
    """多验证站联合标定：单一全局阈值须让**每个**标定站都满足 G-2，再最大化最坏站 G-1。

    单站标定有两个结构性问题，都会让阈值在部署时失准：

    1. **预算错配**：阈值是绝对值，各站置信度分布不同，同一个 ``t`` 在不同站剔除的
       历元比例差别很大。按单站的 1pp 选出的 ``t``，到别的站可能剔除 1.7pp，G-2 直接
       超标——这正是「标定站过、测试站不过」的直接来源。
    2. **乐观偏差**：单站上挑「相对下降最大」的点，等于用该站标签选点，下界必然偏高。

    因此改成：候选阈值取自**所有**标定站置信度的分位点并集（保证低分段足够密），
    对每个候选先算各站固定率下降（O(n)，超标即跳过），再对可行候选算配对下界。

    排序键是 ``(达到 G-1 的站数, 最坏站下界, 平均下界, 固定率下降)``，四级依次比较。
    只用「最坏站下界」单键会退化：当所有候选的最坏下界都 ≤ 0（即没有任何阈值能让
    所有站都达标）时，**不剔除任何历元**的候选下界恰为 0，反而成为最优解，于是选出一个
    形同不干预的阈值。改用四级键后，先最大化「有几个站真能达标」，再在同等达标站数下
    取最坏站下界最高者，最后在完全并列时取用满预算者——既保持 G-4 的保守取向，
    又不会把「什么都没做」当成最优。

    ``station_data`` 每项为 ``(置信度, 标签, AR 总历元, pdop)``；剔除判定走
    :func:`_accepted`，即 **pdop > :data:`PDOP_ABSTAIN_MAX` 的历元不参与剔除**（D14），
    与 :func:`_group_report` / :func:`evaluate` 的判定口径必须一致，否则标定与评估会
    出现「标定时放过、评估时剔除」的口径漂移。
    """
    if not station_data:
        raise ValueError("联合标定至少需要一个验证站")

    cands = []
    for conf, _, _, _ in station_data.values():
        cands.append(np.asarray(_threshold_candidates(conf, step)))
    cands = np.unique(np.round(np.concatenate(cands), 6)).tolist()

    prepared = {}
    for st, (conf, y, total, pdop) in station_data.items():
        conf = np.asarray(conf, dtype=np.float64)
        pdop = np.asarray(pdop, dtype=np.float64)
        wrong = np.asarray(y, dtype=np.float64) < 0.5
        n = int(wrong.size)
        rng = np.random.default_rng(0)
        idx = rng.integers(0, n, size=(n_boot, n))
        prepared[st] = (conf, wrong, n, int(total), pdop, idx)

    best = None
    sweep = []
    for t in cands:
        per_station = {}
        feasible = True
        for st, (conf, wrong, n, total, pdop, idx) in prepared.items():
            accepted = _accepted(conf, float(t), pdop)
            n_fix = int(accepted.sum())
            if n_fix == 0:
                feasible = False
                break
            drop_pp = (n - n_fix) / total * 100.0
            if drop_pp > max_fix_drop_pp:
                feasible = False
                break
            rej = ~accepted
            w_a = wrong[idx].sum(axis=1).astype(np.float64)
            n_c = (n - rej[idx].sum(axis=1)).astype(np.float64)
            w_c = (wrong[idx] & ~rej[idx]).sum(axis=1).astype(np.float64)
            with np.errstate(divide="ignore", invalid="ignore"):
                ratios = 1.0 - (w_c / np.maximum(n_c, 1.0)) / np.maximum(w_a / n, 1e-12)
            ratios = ratios[np.isfinite(ratios)]
            lower = float(np.quantile(ratios, 0.025)) if ratios.size else 0.0
            point = metrics.paired_relative_drop(wrong, rej)
            per_station[st] = {"fix_rate_drop_pp": drop_pp, "relative_drop": point,
                               "lower": lower, "n_fix": n_fix}
        if not feasible:
            continue
        worst_lower = min(v["lower"] for v in per_station.values())
        avg_lower = sum(v["lower"] for v in per_station.values()) / len(per_station)
        n_pass = sum(1 for v in per_station.values() if v["lower"] >= rel_target)
        mean_drop = sum(v["fix_rate_drop_pp"] for v in per_station.values()) / len(per_station)
        row = {"threshold": float(t), "worst_lower": worst_lower,
               "avg_lower": avg_lower, "n_pass_g1": n_pass,
               "mean_fix_drop_pp": mean_drop,
               "all_pass_g1": n_pass == len(per_station), "per_station": per_station}
        sweep.append(row)
        key = (n_pass, worst_lower, avg_lower, mean_drop)
        if best is None or key > best["_key"]:
            best = {**row, "_key": key}

    if best is not None:
        best.pop("_key", None)
    return {
        "selected": best,
        "n_candidates": len(cands),
        "n_feasible": len(sweep),
        "sweep": sweep,
    }


def _group_report(y: np.ndarray, conf: np.ndarray, threshold: float,
                  n_total_epochs: int, max_fix_drop_pp: float = 1.0,
                  rel_target: float = 0.30, pdop: "np.ndarray | None" = None) -> dict:
    """单组（全测试集或单站）的固定质量对比 + G-1~G-4 判据。"""
    accepted = _accepted(conf, threshold, pdop)
    stats_a = metrics.FixStats(n_epochs=int(n_total_epochs), n_fix=int(y.size),
                               n_wrong_fix=int((y < 0.5).sum()))
    stats_c = metrics.FixStats(n_epochs=int(n_total_epochs), n_fix=int(accepted.sum()),
                               n_wrong_fix=int(((y < 0.5) & accepted).sum()))
    b = int(((y < 0.5) & ~accepted).sum())
    c = int(((y > 0.5) & ~accepted).sum())
    gates = metrics.evaluate_fix_gates(stats_a, stats_c, b, c,
                                       max_fix_drop_pp=max_fix_drop_pp,
                                       rel_target=rel_target,
                                       wrong=(y < 0.5), rejected=~accepted)
    n_pdop_abstain = 0 if pdop is None else int(
        (np.asarray(pdop, dtype=np.float64) > PDOP_ABSTAIN_MAX).sum())
    return {
        "n_epochs": int(y.size),
        "n_total_epochs": int(n_total_epochs),
        "threshold": float(threshold),
        "pdop_abstain_max": PDOP_ABSTAIN_MAX,
        "n_pdop_abstained": n_pdop_abstain,
        "fix_rate_before": stats_a.fix_rate,
        "fix_rate_after": stats_c.fix_rate,
        "fix_rate_drop_pp": (stats_a.fix_rate - stats_c.fix_rate) * 100.0,
        "budget_share_of_fix": ((max_fix_drop_pp / 100.0) * n_total_epochs / max(int(y.size), 1)),
        "wrong_fix_rate_before": stats_a.wrong_fix_rate,
        "wrong_fix_rate_after": stats_c.wrong_fix_rate,
        "n_wrong_before": stats_a.n_wrong_fix,
        "n_wrong_after": stats_c.n_wrong_fix,
        "g1_max_achievable": metrics.g1_max_achievable(
            int(n_total_epochs), stats_a.n_wrong_fix, max_fix_drop_pp),
        "mcnemar": {"b": b, "c": c, "p": metrics.mcnemar_exact(b, c)},
        "gates": {k: {"passed": v[0], "detail": v[1]} for k, v in gates.gates.items()},
        "gate_notes": gates.notes,
    }


def evaluate(table, norm: _Normalizer, onnx_path: Path, threshold: float,
             max_fix_drop_pp: float = 1.0, rel_target: float = 0.30) -> dict:
    """测试站评估：全局判据 + 逐站判据（G-3/G-4 需要按站看最坏值）。"""
    x = dataset.features(table)
    y = dataset.labels(table).astype(np.float64)
    station = np.asarray(table["station"].to_pylist())
    n_total = dataset.total_epochs(table)

    conf = _onnx_predict(onnx_path, norm.apply(x))
    pdop = x[:, PDOP_FEATURE_INDEX]
    result = _group_report(y, conf, threshold, n_total, max_fix_drop_pp, rel_target, pdop)

    per_station = {}
    for st in sorted(set(station.tolist())):
        m = station == st
        sub = table.take(np.nonzero(m)[0])
        per_station[st] = _group_report(y[m], conf[m], threshold,
                                        dataset.total_epochs(sub), max_fix_drop_pp,
                                        rel_target, pdop[m])
    result["per_station"] = per_station

    worst = max(per_station.values(),
                key=lambda r: r["wrong_fix_rate_after"] - r["wrong_fix_rate_before"])
    result["gates"]["G-4"] = {
        "passed": all(r["wrong_fix_rate_after"] <= r["wrong_fix_rate_before"] + 1e-12
                      for r in per_station.values()),
        "detail": (f"最坏单站错误固定率 "
                   f"{worst['wrong_fix_rate_before']:.1%} → {worst['wrong_fix_rate_after']:.1%}"
                   f"（逐站明细见 per_station）"),
    }
    result["gates"]["G-3"] = _g3_summary(per_station)
    return result


def _g3_summary(per_station: dict) -> dict:
    """G-3：≥ 2 个测试站同时满足 G-1 与 G-2。

    不可判定（G-1 记 None，例如数学互斥或样本量不足）的站不占名额。
    """
    passing, failing, skipped = [], [], []
    for st, r in per_station.items():
        g1 = r["gates"]["G-1"]["passed"]
        g2 = r["gates"]["G-2"]["passed"]
        if g1 is None:
            skipped.append(st)
        elif g1 and g2:
            passing.append(st)
        else:
            failing.append((st, g1, g2))
    return {
        "passed": len(passing) >= 2,
        "n_passing": len(passing),
        "passing_stations": passing,
        "skipped_stations": skipped,
        "detail": (f"{len(passing)} 个站同时满足 G-1 与 G-2（要求 ≥ 2）：{passing}"
                   + (f"；不予判定 {skipped}" if skipped else "")
                   + (f"；未通过 {[s for s, _, _ in failing]}" if failing else "")),
    }


def _class_balance(table) -> dict:
    y = dataset.labels(table).astype(np.float64)
    n_wrong = int((y < 0.5).sum())
    return {
        "n_rows": int(y.size),
        "n_correct": int(y.size) - n_wrong,
        "n_wrong": n_wrong,
        "wrong_rate": float(n_wrong / y.size) if y.size else 0.0,
    }


def _take_stations(table, stations):
    """只保留指定站的行（用于把「未参与训练的验证站」单独拿出来标定阈值）。"""
    col = np.asarray(table["station"].to_pylist())
    mask = np.isin(col, list(stations))
    return table.take(np.nonzero(mask)[0])


def _exclude_stations(table, stations):
    """剔除指定站的行（用于把独立验证站从早停集里摘出去）。"""
    col = np.asarray(table["station"].to_pylist())
    mask = ~np.isin(col, list(stations))
    return table.take(np.nonzero(mask)[0])


def _station_feasibility(table, max_fix_drop_pp: float) -> dict:
    """逐站 G-1 可行性上限：G-2 预算内最多能剔除的错误固定比例（与模型无关）。

    G-3 要求「≥ 2 个未参与训练与早停的站均满足 G-1、G-2」。若某站的错误固定占
    AR 历元比例过高，剔除 30% 错误固定所需历元数超过 1pp 预算，则两判据数学互斥，
    该站无论模型多好都不可能通过——这类站不能用作 G-3 的判定站。
    另需样本量前置：错误固定事件数不足 ``MIN_WRONG_EVENTS`` 时下界分辨率不够，
    同样只能「不予判定」，也不应占掉 G-3 的名额。
    """
    station = np.asarray(table["station"].to_pylist())
    epochs = dataset.station_epochs(table)
    out = {}
    for st in sorted(set(station.tolist())):
        m = station == st
        n_wrong = int((dataset.labels(table.take(np.nonzero(m)[0])) < 0.5).sum())
        out[st] = {
            "n_wrong": n_wrong,
            "n_total_epochs": int(epochs.get(st, 0)),
            "max_rel_drop": metrics.g1_max_achievable(
                int(epochs.get(st, 0)), n_wrong, max_fix_drop_pp),
            "judgeable": metrics.g1_judgeable(n_wrong),
        }
    return out


def _g1_eligible(f: dict, rel_target: float = 0.30) -> bool:
    """可做 G-1/G-3 判定站的两条前置：数学可行 + 样本量够。"""
    return f["max_rel_drop"] >= rel_target and f["judgeable"]


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="接口一训练管道（仅演示数据不承诺效果）")
    ap.add_argument("--dataset", type=Path, help="Parquet 数据集路径")
    ap.add_argument("--demo", action="store_true", help="生成「仅演示」数据集后训练")
    ap.add_argument("--out-dir", type=Path, default=Path("training/out"))
    ap.add_argument("--epochs", type=int, default=30)
    ap.add_argument("--batch-size", type=int, default=256)
    ap.add_argument("--lr", type=float, default=3e-3)
    ap.add_argument("--hidden", type=int, default=32)
    ap.add_argument("--patience", type=int, default=5)
    ap.add_argument("--n-val-stations", type=int, default=2,
                    help="阈值联合标定用的独立验证站个数；≥2 才能约束「单一全局阈值"
                         "在每个站都满足 G-2」")
    ap.add_argument("--seed", type=int, default=20261107)
    ap.add_argument("--wrong-weight", type=float, default=0.0,
                    help="错误固定样本的损失权重；0 = 自动取 min(20, 正确数/错误数)")
    ap.add_argument("--max-fix-drop-pp", type=float, default=1.0,
                    help="G-2 约束：固定率绝对下降上限（百分点）")
    ap.add_argument("--rel-target", type=float, default=0.30,
                    help="G-1 约束：错误固定率相对下降的配对下界目标（默认 0.30 即 §7.4 原判据；"
                         "2pp/20% 与 5pp/15% 两档见决策 D13）")
    ap.add_argument("--min-confidence", type=float, default=0.5,
                    help="core 默认阈值（AiOpts.fixValidatorMinConfidence 默认 0.5）")
    ap.add_argument("--no-feasible-test", dest="test_candidates_feasible",
                    action="store_false",
                    help="允许把 G-1 数学不可行的站当作测试站（会得到「不予判定」的 G-1）")
    ap.set_defaults(test_candidates_feasible=True)
    args = ap.parse_args(argv)

    if not args.demo and args.dataset is None:
        ap.error("必须指定 --dataset 或 --demo")

    args.out_dir.mkdir(parents=True, exist_ok=True)

    if args.demo:
        table = dataset.make_demo(seed=args.seed)
        path = args.out_dir / "demo_fix_validator.parquet"
        dataset.write(table, path, note="仅演示：证明链路跑通，不承诺模型效果")
        print(f"已生成演示数据集: {path}（{len(table)} 行）")
    else:
        table = dataset.read(args.dataset)

    balance = _class_balance(table)
    print(f"类别分布：正确 {balance['n_correct']} / 错误 {balance['n_wrong']}"
          f"（错误固定率 {balance['wrong_rate']:.1%}）")

    feas = _station_feasibility(table, args.max_fix_drop_pp)
    feasible = [st for st, f in feas.items() if _g1_eligible(f, args.rel_target)]
    print(f"\n逐站 G-1 判定前置（G-2 预算 {args.max_fix_drop_pp:.1f} pp，"
          f"目标 {args.rel_target:.0%}，"
          f"最少错误固定事件 {metrics.MIN_WRONG_EVENTS}）:")
    for st, f in feas.items():
        if not f["judgeable"]:
            mark = "不可判定（错误固定事件不足，需扩数据）"
        elif f["max_rel_drop"] < args.rel_target:
            mark = "不可行（G-1 与 G-2 互斥）"
        else:
            mark = "可判定"
        print(f"  {st}: 错误固定 {f['n_wrong']} / AR 历元 {f['n_total_epochs']}"
              f"（{f['n_wrong'] / max(1, f['n_total_epochs']):.2%}）"
              f"→ {args.max_fix_drop_pp:.1f}pp 预算 "
              f"{args.max_fix_drop_pp / 100.0 * f['n_total_epochs']:.0f} 历元，"
              f"相对下降上限 {f['max_rel_drop']:.1%}，{mark}")
    print(f"  可做 G-1/G-3 判定的站: {len(feasible)} 个 {feasible}")
    print(f"  几何退化门禁（D14）: pdop > {PDOP_ABSTAIN_MAX:g} 的历元不参与剔除；"
          f"该门禁只可能减少剔除量，不会抬高上表的可行性上限")

    split = dataset.split(
        table, seed=args.seed, n_val_stations=args.n_val_stations,
        test_candidates=feasible if args.test_candidates_feasible else None,
        val_candidates=feasible if args.test_candidates_feasible else None)
    print("\n两层切分（先按站、再按天，§7.3）:\n" + split.summary())
    if not args.test_candidates_feasible:
        ineligible_test = [st for st in split.test_stations
                          if not _g1_eligible(feas[st], args.rel_target)]
        if ineligible_test:
            print(f"警告：测试站 {ineligible_test} 不满足 G-1 判定前置"
                  f"（数学不可行或错误固定事件不足），其 G-1 只会给出「不予判定」")

    train_balance = _class_balance(split.train)
    if train_balance["n_wrong"] == 0 or train_balance["n_correct"] == 0:
        raise SystemExit("训练集只含单一类别（无错误固定或无误固定），无法训练判别模型；"
                         "须扩数据或调整切分")

    wrong_weight = args.wrong_weight
    if wrong_weight <= 0.0:
        wrong_weight = float(min(20.0, train_balance["n_correct"] / train_balance["n_wrong"]))
    print(f"错误固定损失权重: {wrong_weight:.2f}（自动计算时上限 20，防止梯度主导）")

    x_train = dataset.features(split.train)
    y_train = dataset.labels(split.train).astype(np.float64)
    early_stop_set = _exclude_stations(split.val, split.val_stations)
    if len(early_stop_set) < 200:
        raise SystemExit(f"早停集（训练站内验证日）仅 {len(early_stop_set)} 行，过小；"
                         f"须扩数据或调整 val_day_fraction")
    print(f"早停集 = 训练站内验证日（{len(early_stop_set)} 行，已剔除独立验证站 "
          f"{split.val_stations}）：§7.3 第二层要求早停不得跨站，独立验证站须"
          f"「未参与训练也未参与早停」，否则不能做 G-3 判定站")
    x_val = dataset.features(early_stop_set)
    y_val = dataset.labels(early_stop_set).astype(np.float64)

    norm = _Normalizer.fit(x_train)
    print(f"归一化参数已拟合（仅训练集，{x_train.shape[0]} 行），"
          f"恒零列 {int(np.sum(norm.std == 1.0))} 个（按 1.0 兜底）")

    model = build_model(args.hidden)
    best = train(model, norm.apply(x_train), y_train, norm.apply(x_val), y_val,
                 args.epochs, args.batch_size, args.lr, args.patience, args.seed,
                 wrong_weight)

    onnx_path = args.out_dir / MODEL_NAME
    export_onnx.export(model, onnx_path, dim=dataset.DIM)
    norm_path = args.out_dir / NORM_NAME
    export_onnx.write_norm(norm_path, norm.mean, norm.std,
                           note="由 training/fix_validator/train.py 拟合（仅训练集）",
                           dim=dataset.DIM)

    contract = export_onnx.check_contract_confidence(onnx_path, dim=dataset.DIM)
    print(f"导出完成: {onnx_path} + {norm_path}")
    print(f"契约校验通过: 输入 {contract['input_shape']} / 输出 {contract['output_shape']}"
          f" / batch 探针 {sorted(contract['batch_probes'])}")

    cal_stations = list(split.val_stations)
    station_data = {}
    for st in cal_stations:
        sub = _take_stations(split.val, [st])
        if len(sub) < 200:
            continue
        xc = dataset.features(sub)
        yc = dataset.labels(sub).astype(np.float64)
        cc = _onnx_predict(onnx_path, norm.apply(xc))
        station_data[st] = (cc, yc, dataset.total_epochs(sub), xc[:, PDOP_FEATURE_INDEX])

    if not station_data:
        print("未参与训练的验证站样本不足，回落到 core 默认阈值")
        chosen = args.min_confidence
        selection = None
    else:
        selection = select_threshold_multi(
            station_data, args.max_fix_drop_pp, rel_target=args.rel_target)
        print(f"阈值联合标定集 = 未参与训练的验证站 {sorted(station_data)}"
              f"（{sum(len(v[0]) for v in station_data.values())} 行）："
              f"单一全局阈值须让每个标定站的固定率下降都 ≤ "
              f"{args.max_fix_drop_pp} pp，再最大化最坏站的 G-1 配对下界"
              f"（目标 {args.rel_target:.0%}，pdop > {PDOP_ABSTAIN_MAX:g} 不参与剔除）")
        if selection["selected"] is None:
            chosen = args.min_confidence
            print(f"标定站上无阈值满足固定率下降 ≤ {args.max_fix_drop_pp} pp，"
                  f"回落 core 默认阈值 {chosen}")
        else:
            chosen = selection["selected"]["threshold"]
            detail = "，".join(
                f"{st}: 下降 {v['fix_rate_drop_pp']:.2f}pp / 下界 {v['lower']:.1%}"
                for st, v in selection["selected"]["per_station"].items())
            print(f"联合标定选定阈值 {chosen}：最坏站 G-1 配对下界 "
                  f"{selection['selected']['worst_lower']:.1%}（{detail}）")

    test_default = evaluate(split.test, norm, onnx_path, args.min_confidence,
                            args.max_fix_drop_pp, args.rel_target)
    test_selected = evaluate(split.test, norm, onnx_path, chosen,
                             args.max_fix_drop_pp, args.rel_target)

    result = {
        "best_epoch": best["epoch"],
        "best_val_loss": best["loss"],
        "split": {
            "train_stations": split.train_stations,
            "val_stations": split.val_stations,
            "test_stations": split.test_stations,
        },
        "class_balance": balance,
        "train_class_balance": train_balance,
        "wrong_weight": wrong_weight,
        "contract": contract,
        "threshold_selection": {
            "calibration_stations": sorted(station_data),
            "selected": selection["selected"] if selection else None,
            "fallback_to_default": selection is None or selection["selected"] is None,
            "max_fix_drop_pp": args.max_fix_drop_pp,
            "rel_target": args.rel_target,
            "pdop_abstain_max": PDOP_ABSTAIN_MAX,
            "n_candidates": selection["n_candidates"] if selection else 0,
            "n_feasible": selection["n_feasible"] if selection else 0,
        },
        "test_at_core_default": test_default,
        "test_at_selected": test_selected,
        "note": "仅演示数据集不承诺模型效果（§6.3）；G-5 推理开销须由 Java 侧实测。"
                "G-1 下界按 §7.4 第 3 条用配对 bootstrap（同一批历元同时决定 A、C 两侧）；"
                "G-2 的预算折算成「剔除固定历元的比例」在跨站差异极大，"
                "单一全局阈值难以在所有站同时满足，须结合 per_station 的 budget_share_of_fix 解读；"
                "剔除判定含几何退化门禁（pdop > PDOP_ABSTAIN_MAX 不参与剔除，D14），"
                "标定与评估同口径",
    }
    metrics_path = args.out_dir / "fix_validator.metrics.json"
    metrics_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")

    def _print_report(title: str, rep: dict) -> None:
        print(f"\n{title}:")
        print(f"  固定率: {rep['fix_rate_before']:.1%} → {rep['fix_rate_after']:.1%}"
              f"（下降 {rep['fix_rate_drop_pp']:.2f} pp）")
        print(f"  几何退化门禁: pdop > {rep['pdop_abstain_max']:g} 的 "
              f"{rep['n_pdop_abstained']} 个历元不参与剔除（D14）")
        print(f"  错误固定率: {rep['wrong_fix_rate_before']:.1%} → {rep['wrong_fix_rate_after']:.1%}"
              f"（{rep['n_wrong_before']} → {rep['n_wrong_after']} 个历元）")
        print(f"  McNemar: b={rep['mcnemar']['b']}（拦下的错误固定） "
              f"c={rep['mcnemar']['c']}（误拒的正确固定） p={rep['mcnemar']['p']:.3f}")
        for name, g in rep["gates"].items():
            tag = "未判定" if g["passed"] is None else ("通过" if g["passed"] else "未通过")
            print(f"  {name}: {tag} — {g['detail']}")
        for st, m in rep["per_station"].items():
            g1 = m["gates"]["G-1"]["passed"]
            g2 = m["gates"]["G-2"]["passed"]
            tag = "未判定" if g1 is None else ("G-1 通过" if g1 else "G-1 未通过")
            print(f"    {st}: 错误固定率 {m['wrong_fix_rate_before']:.1%} → "
                  f"{m['wrong_fix_rate_after']:.1%}，固定率下降 {m['fix_rate_drop_pp']:.2f} pp"
                  f"（预算占固定历元 {m['budget_share_of_fix']:.1%}），{tag}"
                  f" / {'G-2 通过' if g2 else 'G-2 未通过'}")

    _print_report(f"测试站评估（core 默认阈值 {args.min_confidence}）", test_default)
    _print_report(f"测试站评估（验证集选定阈值 {chosen}）", test_selected)
    print(f"\n评估结果已写入: {metrics_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())