"""接口 A（PPP 解有效性判别）训练管道入口（设计文档 §12.2 / §3.5）。

流程定死为：读 Parquet → 两层切分（按站、再按天）→ 训练集拟合归一化 → 训练
→ 早停 → 独立验证站上标定阈值 → 导出 ONNX + ``norm.json`` → 契约校验
→ 测试站评估（池化 + 按站 + 按天两层指标）。

与接口一（固定校验）的三点关键差异：

1. **决策对象是「整历元解」而非「固定解」**：标签 ``1`` = 该历元解可信，
   ``0`` = 应降级。core 侧只把 ``sol.stat`` 降级，不改坐标、不改协方差（§3.5）；
2. **阈值只决定降级率，不决定坐标**：``AiOpts.pppSolutionMinConfidence`` 默认 0.5，
   换阈值不换模型。标定目标是「在误降级预算内最大化对不可信历元的召回」——
   PPP 没有 RTK 的 G-1/G-2 固定率约束，约束换成 ``--max-false-reject``；
3. **归因字段 ``deterministic_degraded``**：确定性 qr 基线可能已经把同一历元降级。
   报告单独给出基线降级率与「AI 与基线重叠比例」，否则无法判断 AI 的边际贡献。

用法（在 ``rtklib-ai`` 目录下执行）::

    python -m training.ppp_solution_validator.train --demo
    python -m training.ppp_solution_validator.train \\
        --dataset data/ppp_solution_validator.parquet --out-dir training/out

**演示数据只证明链路跑通，不承诺模型效果**（§12.1）。
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np

try:
    from training.common import export_onnx, metrics
    from training.common import ppp_dataset as dataset
except ImportError:  # 允许直接以脚本方式运行
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
    from training.common import export_onnx, metrics
    from training.common import ppp_dataset as dataset

MODEL_NAME = "ppp_solution_validator.onnx"
NORM_NAME = "ppp_solution_validator.norm.json"
METRICS_NAME = "ppp_solution_validator.metrics.json"


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

    class PppSolutionValidatorNet(nn.Module):
        def __init__(self):
            super().__init__()
            self.fc1 = nn.Linear(dataset.A_DIM, hidden)
            self.fc2 = nn.Linear(hidden, hidden)
            self.head = nn.Linear(hidden, 1)

        def _trunk(self, x):
            h = torch.relu(self.fc1(x))
            return torch.relu(self.fc2(h))

        def forward(self, x):
            return torch.sigmoid(self.head(self._trunk(x)))

        def logits(self, x):
            return self.head(self._trunk(x)).squeeze(1)

    return PppSolutionValidatorNet()


def train(model, x_train, y_train, x_val, y_val, epochs: int, batch_size: int,
          lr: float, patience: int, seed: int, invalid_weight: float) -> dict:
    """加权 BCE + 早停。

    ``invalid_weight`` 作用在 ``label = 0``（不可信历元）上：不可信历元是少数类，
    不加权时「全部放行」就是损失最小的解，模型学不到任何判别力。
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
        w = torch.where(yb > 0.5, torch.ones_like(yb),
                        torch.full_like(yb, float(invalid_weight)))
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
        print(f"  epoch {epoch + 1:3d}/{epochs}: train={total / n:.4f} val={val_loss:.4f}")
        if val_loss < best["loss"] - 1e-4:
            best = {"loss": val_loss,
                    "state": {k: v.clone() for k, v in model.state_dict().items()},
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


def _onnx_predict(onnx_path: Path, x: np.ndarray, batch: int = 4096) -> np.ndarray:
    """用导出的 ONNX 实测推理（评估必须走产物，不能走训练态模型）。"""
    import onnxruntime as ort

    sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    name = sess.get_inputs()[0].name
    outs = []
    for i in range(0, x.shape[0], batch):
        outs.append(sess.run(None, {name: np.asarray(x[i:i + batch], dtype=np.float32)})[0])
    return np.concatenate(outs, axis=0)[:, 0].astype(np.float64)


def _auc(conf: np.ndarray, invalid: np.ndarray) -> "float | None":
    """ROC-AUC（Mann-Whitney U，含并列秩处理）：置信度越高越「可信」，
    因此用 ``1 - conf`` 作为「不可信」分数。"""
    if invalid.size == 0 or np.all(invalid) or np.all(~invalid):
        return None
    score = 1.0 - np.asarray(conf, dtype=np.float64)
    pos = score[invalid]
    neg = score[~invalid]
    order = np.argsort(np.concatenate([pos, neg]), kind="mergesort")
    ranks = np.empty(order.size, dtype=np.float64)
    ranks[order] = np.arange(1, order.size + 1, dtype=np.float64)
    # 并列值取平均秩
    vals = np.concatenate([pos, neg])
    sorted_vals = vals[order]
    i = 0
    while i < sorted_vals.size:
        j = i
        while j + 1 < sorted_vals.size and sorted_vals[j + 1] == sorted_vals[i]:
            j += 1
        if j > i:
            ranks[order[i:j + 1]] = np.mean(ranks[order[i:j + 1]])
        i = j + 1
    u = ranks[:pos.size].sum() - pos.size * (pos.size + 1) / 2.0
    return float(u / (pos.size * neg.size))


def _rates(y: np.ndarray, conf: np.ndarray, threshold: float,
           total_epochs: "int | None" = None,
           baseline_degraded: "np.ndarray | None" = None) -> dict:
    """单个分组上的降级率 / 召回 / 误降级率。"""
    y = np.asarray(y, dtype=np.float64)
    conf = np.asarray(conf, dtype=np.float64)
    accepted = conf >= float(threshold)
    invalid = y < 0.5
    tp = int(np.sum(~accepted & invalid))
    fn = int(np.sum(accepted & invalid))
    fp = int(np.sum(~accepted & ~invalid))
    tn = int(np.sum(accepted & ~invalid))
    n = int(y.size)
    out = {
        "n_rows": n,
        "n_invalid": tp + fn,
        "n_downgraded": tp + fp,
        "downgrade_rate": (tp + fp) / n if n else None,
        "recall": tp / (tp + fn) if (tp + fn) else None,
        "recall_ci95": (metrics.wilson_interval(tp, tp + fn)
                        if (tp + fn) else None),
        "miss_rate": fn / (tp + fn) if (tp + fn) else None,
        "false_reject_rate": fp / (fp + tn) if (fp + tn) else None,
        "false_reject_ci95": (metrics.wilson_interval(fp, fp + tn)
                              if (fp + tn) else None),
        "precision": tp / (tp + fp) if (tp + fp) else None,
        "auc": _auc(conf, invalid),
        "conf_p50": float(np.median(conf)) if n else None,
    }
    if total_epochs:
        out["downgrade_share_of_run"] = (tp + fp) / float(total_epochs)
    if baseline_degraded is not None:
        b = np.asarray(baseline_degraded) > 0
        out["baseline_degraded_rate"] = float(b.mean()) if n else None
        ai_rej_invalid = ~accepted & invalid
        out["overlap_with_baseline"] = (float(np.mean(b[ai_rej_invalid]))
                                        if ai_rej_invalid.any() else None)
    return out


def select_threshold(station_data: dict, max_false_reject: float,
                     n_quantiles: int = 401) -> dict:
    """在独立验证站上标定全局阈值：误降级预算内最大化**最坏站**召回。

    单一全局阈值的两个结构性风险与接口一相同：各站置信度分布不同（同一阈值在不同
    站降级比例差别很大），且单站挑点会带乐观偏差。因此候选阈值取所有标定站置信度
    分位点的并集，逐候选先检查**每个站**的误降级率是否 ≤ 预算，再在可行候选里最大化
    「最坏站召回」，最后按 (最坏站召回, 平均召回, -降级率) 三级排序。
    """
    if not station_data:
        raise ValueError("标定至少需要一个验证站")

    cands = []
    for conf, _ in station_data.values():
        cands.append(np.quantile(np.asarray(conf, dtype=np.float64),
                                 np.linspace(0.0, 1.0, n_quantiles)))
    cands.append(np.arange(0.05, 0.96, 0.05))
    cands = np.unique(np.round(np.concatenate(cands), 6))

    best = None
    n_feasible = 0
    for t in cands:
        per = {}
        feasible = True
        for st, (conf, y) in station_data.items():
            r = _rates(y, conf, float(t))
            per[st] = r
            fr = r["false_reject_rate"]
            if fr is not None and fr > float(max_false_reject):
                feasible = False
                break
        if not feasible:
            continue
        n_feasible += 1
        recalls = [r["recall"] for r in per.values() if r["recall"] is not None]
        if not recalls:
            continue
        key = (min(recalls), float(np.mean(recalls)),
               -float(np.mean([r["downgrade_rate"] for r in per.values()])))
        if best is None or key > best["key"]:
            best = {"key": key, "threshold": float(t), "per_station": per,
                    "worst_recall": min(recalls)}

    return {
        "selected": None if best is None else {
            "threshold": best["threshold"],
            "worst_recall": best["worst_recall"],
            "per_station": best["per_station"],
        },
        "n_candidates": int(cands.size),
        "n_feasible": int(n_feasible),
        "max_false_reject": float(max_false_reject),
    }


def _days(table) -> list:
    return sorted(set(table["day"].to_pylist()))


def evaluate(table, norm: _Normalizer, onnx_path: Path, threshold: float) -> dict:
    """测试站评估：池化 + 逐站 + 逐日两层指标（§12.2 硬要求 2）。"""
    x = dataset.features_a(table)
    y = dataset.labels_a(table).astype(np.float64)
    base = dataset.deterministic_degraded_a(table)
    station = np.asarray(table["station"].to_pylist())
    day = np.asarray(table["day"].to_pylist())

    conf = _onnx_predict(onnx_path, norm.apply(x))
    pooled = _rates(y, conf, threshold, dataset.total_epochs(table), base)

    per_station = {}
    for st in sorted(set(station.tolist())):
        m = station == st
        sub = table.take(np.nonzero(m)[0])
        per_station[st] = _rates(y[m], conf[m], threshold, dataset.total_epochs(sub), base[m])

    per_day = {}
    for d in sorted(set(day.tolist())):
        m = day == d
        sub = table.take(np.nonzero(m)[0])
        per_day[d] = _rates(y[m], conf[m], threshold, dataset.total_epochs(sub), base[m])

    worst = max(per_station.values(),
                key=lambda r: (r["false_reject_rate"] or 0.0))
    return {
        "threshold": float(threshold),
        "pooled": pooled,
        "per_station": per_station,
        "per_day": per_day,
        "worst_station_false_reject": {
            "rate": worst["false_reject_rate"],
            "downgrade_rate": worst["downgrade_rate"],
        },
    }


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(
        description="接口 A 训练管道（PPP 解有效性判别；仅演示数据不承诺效果）")
    ap.add_argument("--dataset", type=Path, help="Parquet 数据集路径")
    ap.add_argument("--demo", action="store_true", help="生成「仅演示」数据集后训练")
    ap.add_argument("--out-dir", type=Path, default=Path("training/out"))
    ap.add_argument("--epochs", type=int, default=40)
    ap.add_argument("--batch-size", type=int, default=256)
    ap.add_argument("--lr", type=float, default=3e-3)
    ap.add_argument("--hidden", type=int, default=32)
    ap.add_argument("--patience", type=int, default=6)
    ap.add_argument("--seed", type=int, default=20261114)
    ap.add_argument("--n-val-stations", type=int, default=2,
                    help="阈值标定用的独立验证站个数；≥2 才能约束「单一全局阈值"
                         "在每个站都不超误降级预算」")
    ap.add_argument("--invalid-weight", type=float, default=0.0,
                    help="不可信历元的损失权重；0 = 自动取 min(20, 可信数/不可信数)")
    ap.add_argument("--max-false-reject", type=float, default=0.02,
                    help="误降级预算：可信历元被降级的比例上限（默认 2%%）")
    ap.add_argument("--min-confidence", type=float, default=0.5,
                    help="core 默认阈值（AiOpts.pppSolutionMinConfidence 默认 0.5）")
    args = ap.parse_args(argv)

    if not args.demo and args.dataset is None:
        ap.error("必须指定 --dataset 或 --demo")

    args.out_dir.mkdir(parents=True, exist_ok=True)

    if args.demo:
        table = dataset.make_demo_a(seed=args.seed)
        path = args.out_dir / "demo_ppp_solution_validator.parquet"
        dataset.write_a(table, path, note="仅演示：证明链路跑通，不承诺模型效果")
        print(f"已生成演示数据集: {path}（{len(table)} 行）")
    else:
        table = dataset.read_a(args.dataset)

    label = dataset.labels_a(table)
    n_invalid = int((label < 0.5).sum())
    print(f"数据集：{len(table)} 行 / {len(set(table['station'].to_pylist()))} 站 / "
          f"{len(_days(table))} 天；不可信历元 {n_invalid}（{n_invalid / len(table):.1%}）；"
          f"确定性基线降级率 {float(np.mean(dataset.deterministic_degraded_a(table))):.1%}")

    split = dataset.split(table, seed=args.seed, n_val_stations=args.n_val_stations,
                          n_test_stations=2)
    print("\n两层切分（先按站、再按天，§7.3）:\n" + split.summary())

    y_train = dataset.labels_a(split.train).astype(np.float64)
    n_inv_train = int((y_train < 0.5).sum())
    n_ok_train = int(y_train.size) - n_inv_train
    if n_inv_train == 0 or n_ok_train == 0:
        raise SystemExit("训练集只含单一类别，无法训练判别模型；须扩数据或调整切分")

    invalid_weight = args.invalid_weight
    if invalid_weight <= 0.0:
        invalid_weight = float(min(20.0, n_ok_train / n_inv_train))
    print(f"不可信历元损失权重: {invalid_weight:.2f}（自动计算时上限 20，防止梯度主导）")

    x_train = dataset.features_a(split.train)
    early_stop = split.val
    x_val = dataset.features_a(early_stop)
    y_val = dataset.labels_a(early_stop).astype(np.float64)

    norm = _Normalizer.fit(x_train)
    print(f"归一化参数已拟合（仅训练集 {x_train.shape[0]} 行），"
          f"恒零列 {int(np.sum(norm.std == 1.0))} 个（按 1.0 兜底）")

    model = build_model(args.hidden)
    best = train(model, norm.apply(x_train), y_train, norm.apply(x_val), y_val,
                 args.epochs, args.batch_size, args.lr, args.patience, args.seed,
                 invalid_weight)

    onnx_path = args.out_dir / MODEL_NAME
    export_onnx.export(model, onnx_path, dim=dataset.A_DIM)
    norm_path = args.out_dir / NORM_NAME
    export_onnx.write_norm(norm_path, norm.mean, norm.std,
                           note="由 training/ppp_solution_validator/train.py 拟合（仅训练集）",
                           dim=dataset.A_DIM)
    contract = export_onnx.check_contract_confidence(onnx_path, dim=dataset.A_DIM)
    print(f"导出完成: {onnx_path} + {norm_path}")
    print(f"契约校验通过: 输入 {contract['input_shape']} / 输出 {contract['output_shape']}"
          f" / batch 探针 {sorted(contract['batch_probes'])}")

    station_col = np.asarray(split.val["station"].to_pylist())
    station_data = {}
    for st in split.val_stations:
        m = station_col == st
        sub = split.val.take(np.nonzero(m)[0])
        if len(sub) < 50:
            continue
        conf = _onnx_predict(onnx_path, norm.apply(dataset.features_a(sub)))
        station_data[st] = (conf, dataset.labels_a(sub).astype(np.float64))

    if not station_data:
        chosen = args.min_confidence
        selection = None
        print("未参与训练的验证站样本不足，回落 core 默认阈值")
    else:
        selection = select_threshold(station_data, args.max_false_reject)
        print(f"阈值标定集 = 未参与训练的验证站 {sorted(station_data)}"
              f"（{sum(len(v[0]) for v in station_data.values())} 行）："
              f"约束每站误降级率 ≤ {args.max_false_reject:.1%}，最大化最坏站召回")
        if selection["selected"] is None:
            chosen = args.min_confidence
            print(f"标定站上无阈值满足误降级预算，回落 core 默认阈值 {chosen}")
        else:
            chosen = selection["selected"]["threshold"]
            detail = "，".join(f"{st}: 召回 {r['recall'] if r['recall'] is not None else float('nan'):.1%}"
                              f" / 误降级 {(r['false_reject_rate'] or 0.0):.2%}"
                              for st, r in selection["selected"]["per_station"].items())
            print(f"标定选定阈值 {chosen}：最坏站召回 "
                  f"{selection['selected']['worst_recall']:.1%}（{detail}）")

    test_default = evaluate(split.test, norm, onnx_path, args.min_confidence)
    test_selected = evaluate(split.test, norm, onnx_path, chosen)

    result = {
        "seed": args.seed,
        "hparams": {"epochs": args.epochs, "batch_size": args.batch_size,
                    "lr": args.lr, "hidden": args.hidden, "patience": args.patience,
                    "invalid_weight": invalid_weight},
        "dataset": {
            "path": str(args.dataset) if args.dataset else "demo",
            "n_rows": int(len(table)),
            "stations": sorted(set(table["station"].to_pylist())),
            "days": _days(table),
            "invalid_rate": float(n_invalid / len(table)),
        },
        "split": {
            "train_stations": split.train_stations,
            "train_days": _days(split.train),
            "val_stations": split.val_stations,
            "val_days": _days(split.val),
            "test_stations": split.test_stations,
            "test_days": _days(split.test),
            "train_rows": int(len(split.train)),
            "val_rows": int(len(split.val)),
            "test_rows": int(len(split.test)),
        },
        "train_class_balance": {"n_trusted": n_ok_train, "n_invalid": n_inv_train,
                                "invalid_rate": float(n_inv_train / y_train.size)},
        "best_epoch": best["epoch"],
        "best_val_loss": best["loss"],
        "contract": contract,
        "threshold_selection": {
            "calibration_stations": sorted(station_data),
            "selected": selection["selected"] if selection else None,
            "fallback_to_default": selection is None or selection["selected"] is None,
            "max_false_reject": args.max_false_reject,
            "n_candidates": selection["n_candidates"] if selection else 0,
            "n_feasible": selection["n_feasible"] if selection else 0,
        },
        "test_at_core_default": test_default,
        "test_at_selected": test_selected,
        "artifacts": {"onnx": str(onnx_path), "norm": str(norm_path)},
        "note": ("仅演示数据集不承诺模型效果（§12.1）。接口 A 只改 sol.stat，"
                 "不改坐标与协方差；阈值由 core 侧 AiOpts.pppSolutionMinConfidence 决定。"
                 "指标含池化 / 按站 / 按天两层（§12.2 硬要求 2），池化会被单站主导。"
                 "deterministic_degraded 为归因字段，用于区分 AI 独立发现与基线重叠。"),
    }
    metrics_path = args.out_dir / METRICS_NAME
    metrics_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")

    def _print(title: str, rep: dict) -> None:
        def pct(v, digits: int = 2) -> str:
            return "n/a" if v is None else f"{v:.{digits}%}"

        def num(v, digits: int = 3) -> str:
            return "n/a" if v is None else f"{v:.{digits}f}"

        p = rep["pooled"]
        print(f"\n{title}（阈值 {rep['threshold']}）:")
        print(f"  降级率 {pct(p['downgrade_rate'])}"
              + (f"（占 run 历元 {pct(p['downgrade_share_of_run'])}）"
                 if p.get("downgrade_share_of_run") is not None else ""))
        print(f"  召回 {pct(p['recall'], 1)} / 误降级 {pct(p['false_reject_rate'])}"
              f" / 精确率 {pct(p['precision'], 1)} / AUC {num(p['auc'])}")
        print(f"  确定性基线降级率 {pct(p.get('baseline_degraded_rate'))}"
              f"（AI 拦截中与基线重叠 {pct(p.get('overlap_with_baseline'), 1)}）")
        print("  逐站：" + "，".join(
            f"{st} 降级 {pct(r['downgrade_rate'])} / 召回 {pct(r['recall'], 1)}"
            for st, r in rep["per_station"].items()))
        print("  逐日：" + "，".join(
            f"{d} 降级 {pct(r['downgrade_rate'])}"
            for d, r in rep["per_day"].items()))

    _print("测试站 @ core 默认阈值", test_default)
    _print("测试站 @ 标定阈值", test_selected)
    print(f"\n评估报告: {metrics_path}")
    print("提醒：单站单日、相邻历元高度相关，任何拦截率差异都不具统计意义（§12.1）。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())