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


def select_threshold(conf: np.ndarray, y: np.ndarray, max_fix_drop_pp: float = 1.0,
                     step: float = 0.02) -> dict:
    """在验证集上扫阈值：固定率下降不超过 ``max_fix_drop_pp`` 的前提下，最大化错误固定剔除。

    A 组 = 不干预（全部接受），C 组 = 置信度 ``>= t`` 才接受。判据顺序严格照 §7.4：
    先卡 G-2（固定率绝对下降 ≤ 1.0 pp），再在可行域内最大化 G-1 的相对下降。
    验证集上没有任何阈值可行时返回 ``selected = None``，由调用方回落到 core 默认阈值。
    """
    n = int(y.size)
    wrong = y < 0.5
    base_wrong_rate = float(wrong.sum() / n) if n else 0.0

    sweep = []
    best = None
    for t in np.round(np.arange(step, 1.0, step), 4):
        accepted = conf >= float(t)
        n_fix = int(accepted.sum())
        if n_fix == 0:
            continue
        drop_pp = (n - n_fix) / n * 100.0
        wrong_rate = float((wrong & accepted).sum() / n_fix)
        rel = (base_wrong_rate - wrong_rate) / base_wrong_rate if base_wrong_rate > 0 else 0.0
        row = {
            "threshold": float(t),
            "n_fix": n_fix,
            "fix_rate_drop_pp": drop_pp,
            "wrong_fix_rate": wrong_rate,
            "relative_wrong_fix_drop": rel,
        }
        sweep.append(row)
        if drop_pp <= max_fix_drop_pp and (best is None or rel > best["relative_wrong_fix_drop"]):
            best = row

    return {
        "selected": best,
        "base_wrong_fix_rate": base_wrong_rate,
        "max_fix_drop_pp": max_fix_drop_pp,
        "sweep": sweep,
    }


def _group_report(y: np.ndarray, conf: np.ndarray, threshold: float) -> dict:
    """单组（全测试集或单站）的固定质量对比 + G-1~G-4 判据。"""
    accepted = conf >= threshold
    stats_a = metrics.FixStats(n_epochs=int(y.size), n_fix=int(y.size),
                               n_wrong_fix=int((y < 0.5).sum()))
    stats_c = metrics.FixStats(n_epochs=int(y.size), n_fix=int(accepted.sum()),
                               n_wrong_fix=int(((y < 0.5) & accepted).sum()))
    b = int(((y < 0.5) & ~accepted).sum())
    c = int(((y > 0.5) & ~accepted).sum())
    gates = metrics.evaluate_fix_gates(stats_a, stats_c, b, c)
    return {
        "n_epochs": int(y.size),
        "threshold": float(threshold),
        "fix_rate_before": stats_a.fix_rate,
        "fix_rate_after": stats_c.fix_rate,
        "fix_rate_drop_pp": (stats_a.fix_rate - stats_c.fix_rate) * 100.0,
        "wrong_fix_rate_before": stats_a.wrong_fix_rate,
        "wrong_fix_rate_after": stats_c.wrong_fix_rate,
        "n_wrong_before": stats_a.n_wrong_fix,
        "n_wrong_after": stats_c.n_wrong_fix,
        "mcnemar": {"b": b, "c": c, "p": metrics.mcnemar_exact(b, c)},
        "gates": {k: {"passed": v[0], "detail": v[1]} for k, v in gates.gates.items()},
        "gate_notes": gates.notes,
    }


def evaluate(table, norm: _Normalizer, onnx_path: Path, threshold: float) -> dict:
    """测试站评估：全局判据 + 逐站判据（G-3/G-4 需要按站看最坏值）。"""
    x = dataset.features(table)
    y = dataset.labels(table).astype(np.float64)
    station = np.asarray(table["station"].to_pylist())

    conf = _onnx_predict(onnx_path, norm.apply(x))
    result = _group_report(y, conf, threshold)

    per_station = {}
    for st in sorted(set(station.tolist())):
        m = station == st
        per_station[st] = _group_report(y[m], conf[m], threshold)
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
    result["gates"]["G-3"] = {
        "passed": None,
        "detail": "跨站泛化须人工按 per_station 逐站判定后汇总，脚本不代判定",
    }
    return result


def _class_balance(table) -> dict:
    y = dataset.labels(table).astype(np.float64)
    n_wrong = int((y < 0.5).sum())
    return {
        "n_rows": int(y.size),
        "n_correct": int(y.size) - n_wrong,
        "n_wrong": n_wrong,
        "wrong_rate": float(n_wrong / y.size) if y.size else 0.0,
    }


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
    ap.add_argument("--seed", type=int, default=20261107)
    ap.add_argument("--wrong-weight", type=float, default=0.0,
                    help="错误固定样本的损失权重；0 = 自动取 min(20, 正确数/错误数)")
    ap.add_argument("--max-fix-drop-pp", type=float, default=1.0,
                    help="G-2 约束：固定率绝对下降上限（百分点）")
    ap.add_argument("--min-confidence", type=float, default=0.5,
                    help="core 默认阈值（AiOpts.fixValidatorMinConfidence 默认 0.5）")
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

    split = dataset.split(table, seed=args.seed)
    print("两层切分（先按站、再按天，§7.3）:\n" + split.summary())

    balance = _class_balance(table)
    print(f"类别分布：正确 {balance['n_correct']} / 错误 {balance['n_wrong']}"
          f"（错误固定率 {balance['wrong_rate']:.1%}）")

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
    x_val = dataset.features(split.val)
    y_val = dataset.labels(split.val).astype(np.float64)

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

    val_conf = _onnx_predict(onnx_path, norm.apply(x_val))
    selection = select_threshold(val_conf, y_val, args.max_fix_drop_pp)
    if selection["selected"] is None:
        chosen = args.min_confidence
        print(f"验证集上无阈值满足固定率下降 ≤ {args.max_fix_drop_pp} pp，"
              f"回落 core 默认阈值 {chosen}")
    else:
        chosen = selection["selected"]["threshold"]
        print(f"验证集选定阈值 {chosen}：固定率下降 "
              f"{selection['selected']['fix_rate_drop_pp']:.2f} pp，"
              f"错误固定率相对下降 {selection['selected']['relative_wrong_fix_drop']:.1%}")

    test_default = evaluate(split.test, norm, onnx_path, args.min_confidence)
    test_selected = evaluate(split.test, norm, onnx_path, chosen)

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
            "selected_threshold": selection["selected"],
            "fallback_to_default": selection["selected"] is None,
            "base_wrong_fix_rate": selection["base_wrong_fix_rate"],
            "max_fix_drop_pp": selection["max_fix_drop_pp"],
            "sweep": selection["sweep"],
        },
        "test_at_core_default": test_default,
        "test_at_selected": test_selected,
        "note": "仅演示数据集不承诺模型效果（§6.3）；G-5 推理开销须由 Java 侧实测，"
                "G-3 跨站泛化须人工判定",
    }
    metrics_path = args.out_dir / "fix_validator.metrics.json"
    metrics_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")

    def _print_report(title: str, rep: dict) -> None:
        print(f"\n{title}:")
        print(f"  固定率: {rep['fix_rate_before']:.1%} → {rep['fix_rate_after']:.1%}"
              f"（下降 {rep['fix_rate_drop_pp']:.2f} pp）")
        print(f"  错误固定率: {rep['wrong_fix_rate_before']:.1%} → {rep['wrong_fix_rate_after']:.1%}"
              f"（{rep['n_wrong_before']} → {rep['n_wrong_after']} 个历元）")
        print(f"  McNemar: b={rep['mcnemar']['b']}（拦下的错误固定） "
              f"c={rep['mcnemar']['c']}（误拒的正确固定） p={rep['mcnemar']['p']:.3f}")
        for name, g in rep["gates"].items():
            tag = "未判定" if g["passed"] is None else ("通过" if g["passed"] else "未通过")
            print(f"  {name}: {tag} — {g['detail']}")
        for st, m in rep["per_station"].items():
            print(f"    {st}: 错误固定率 {m['wrong_fix_rate_before']:.1%} → "
                  f"{m['wrong_fix_rate_after']:.1%}，固定率下降 {m['fix_rate_drop_pp']:.2f} pp")

    _print_report(f"测试站评估（core 默认阈值 {args.min_confidence}）", test_default)
    _print_report(f"测试站评估（验证集选定阈值 {chosen}）", test_selected)
    print(f"\n评估结果已写入: {metrics_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())