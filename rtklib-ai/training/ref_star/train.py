"""接口四（参考星风险判别）训练管道入口。

流程与接口三完全同构：读 Parquet → 可行性前置（双阈值）→ 两层切分（按站、再按天）
→ 训练集拟合归一化 → 训练 → 早停 → 导出 ONNX + ``norm.json`` → 契约校验 → 测试站评估。

用法（在 ``rtklib-ai`` 目录下执行）::

    python -m training.ref_star.train --demo
    python -m training.ref_star.train --dataset data/refstar_sliprisk.parquet --out-dir training/out

三条不能含糊的地方：

1. **示例数据只证明链路跑通，不承诺模型效果。** 真实数据的训练产物在没有步骤 0
   （``is_ref`` trace + 同配置重跑采集）时无效，本脚本无法替你验证这一点。
2. **双阈值终止条件先于训练执行。** 事件数 &lt; 50 或正例比例 &lt; 1% 时直接终止管线
   （退出码 2），不产出模型：样本量不足时训出来的东西只会给人「接口四已可用」的错觉。
3. **伪相关探测。** 单个特征与标签的点二列相关系数绝对值 ≥ 0.98 即判为疑似泄漏并终止。
   接口四最容易泄漏的两维（``resp``/``resc``）已结构性剔除（H1），此探测是第二道闸。
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np

try:
    from training.common import dataset as common_dataset
    from training.common import export_onnx
    from training.ref_star import dataset
except ImportError:  # 允许直接以脚本方式运行
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
    from training.common import dataset as common_dataset
    from training.common import export_onnx
    from training.ref_star import dataset

#: 双阈值终止条件：事件数与正例比例任一不达标即终止（不产出模型）。
MIN_EVENTS = 50
MIN_POSITIVE_RATE = 0.01

#: 伪相关阈值：单特征与标签的 |点二列相关| 达到此值即判为疑似泄漏。
PSEUDO_CORRELATION_MAX = 0.98

#: 与 ``AiOpts`` 默认值对齐的部署参数（只用于评估报告，不写进模型）。
DEFAULT_RISK_MAX = 0.8
DEFAULT_SWITCH_RATE_MAX = 0.02

#: 结构性恒零列（单频链路下 ``gf`` 未被赋值）。保留占位、不删维，``std`` 按 1.0 兜底。
CONSTANT_COLS = ("f7",)


class _Normalizer:
    """训练集拟合、推理侧原样加载的归一化（禁止按运行数据动态归一化）。"""

    def __init__(self, mean: np.ndarray, std: np.ndarray):
        self.mean = np.asarray(mean, dtype=np.float32)
        self.std = np.asarray(std, dtype=np.float32)

    @classmethod
    def fit(cls, x: np.ndarray) -> "_Normalizer":
        mean = x.mean(axis=0)
        std = x.std(axis=0)
        std = np.where((std < 1e-8) | ~np.isfinite(std), 1.0, std)
        return cls(mean, std)

    @classmethod
    def load(cls, path: Path) -> "_Normalizer":
        payload = json.loads(Path(path).read_text(encoding="utf-8"))
        return cls(np.array(payload["mean"], dtype=np.float64),
                   np.array(payload["std"], dtype=np.float64))

    def apply(self, x: np.ndarray) -> np.ndarray:
        return (np.asarray(x, dtype=np.float32) - self.mean) / self.std


def auc(score: np.ndarray, label: np.ndarray) -> float:
    """ROC AUC（秩和口径，无需 sklearn）。

    标签里正负例都可能为空（小测试站），此时返回 ``nan`` 而不是 0.5——
    把「无法计算」当成「等于随机」会让评估报告凭空出现一个中性结论。
    """
    score = np.asarray(score, dtype=np.float64)
    label = np.asarray(label, dtype=np.float64) > 0.5
    n_pos = int(label.sum())
    n_neg = int((~label).sum())
    if n_pos == 0 or n_neg == 0:
        return float("nan")
    order = np.argsort(score, kind="mergesort")
    ranks = np.empty(score.size, dtype=np.float64)
    ranks[order] = np.arange(1, score.size + 1, dtype=np.float64)
    # 并列分数取平均秩，否则 AUC 会依赖排序稳定性（同一分数重复出现时结果漂移）
    sorted_score = score[order]
    i = 0
    while i < score.size:
        j = i
        while j + 1 < score.size and sorted_score[j + 1] == sorted_score[i]:
            j += 1
        if j > i:
            ranks[order[i:j + 1]] = (i + j + 2) / 2.0
        i = j + 1
    return float((ranks[label].sum() - n_pos * (n_pos + 1) / 2.0) / (n_pos * n_neg))


def pseudo_correlation(x: np.ndarray, y: np.ndarray) -> dict:
    """点二列相关：每个特征与 0/1 标签的相关系数（泄漏探测用）。"""
    y = np.asarray(y, dtype=np.float64)
    if y.size == 0 or y.std() < 1e-12:
        return {"max_abs": 0.0, "max_feature": None, "per_feature": {}}
    out = {}
    for i in range(x.shape[1]):
        col = np.asarray(x[:, i], dtype=np.float64)
        if col.std() < 1e-12:
            out[dataset.FEATURE_COLUMNS[i]] = 0.0
            continue
        out[dataset.FEATURE_COLUMNS[i]] = float(np.corrcoef(col, y)[0, 1])
    worst = max(out.items(), key=lambda kv: abs(kv[1]))
    return {"max_abs": abs(worst[1]), "max_feature": worst[0], "per_feature": out}


def feasibility(table, min_events: int = MIN_EVENTS,
                min_pos_rate: float = MIN_POSITIVE_RATE) -> dict:
    """双阈值终止条件：事件数、正例比例。"""
    events = dataset.event_count(table)
    rate = dataset.positive_rate(table)
    if events < min_events:
        reason = f"事件数不足：{events} < {min_events}（相邻历元高度相关，按事件计数）"
    elif rate < min_pos_rate:
        reason = (f"正例比例过低：{rate:.2%} < {min_pos_rate:.0%}"
                  f"（滑相是稀疏事件，样本量不足以标定阈值）")
    else:
        reason = ""
    return {
        "rows": int(len(table)),
        "events": events,
        "positive_rate": rate,
        "min_events": min_events,
        "min_positive_rate": min_pos_rate,
        "passed": not reason,
        "reason": reason,
    }


def build_model(hidden: int):
    """MLP + sigmoid 头：输出天然落在 ``[0,1]``，与 core 的钳位口径一致。"""
    import torch
    from torch import nn

    class RefStarNet(nn.Module):
        def __init__(self):
            super().__init__()
            self.fc1 = nn.Linear(dataset.DIM, hidden)
            self.fc2 = nn.Linear(hidden, hidden)
            self.head = nn.Linear(hidden, 1)

        def forward(self, x):
            h = torch.relu(self.fc1(x))
            h = torch.relu(self.fc2(h))
            return torch.sigmoid(self.head(h))

    return RefStarNet()


def train(model, x_train, y_train, x_val, y_val, epochs: int, batch_size: int,
          lr: float, patience: int, seed: int, pos_weight: float):
    """加权二元交叉熵 + 按验证 AUC 早停。

    正例稀疏（滑相事件少），不加 ``pos_weight`` 会让模型退化成「恒输出 0」——
    AUC 仍可能是 0.5 以上（因为排序几乎不变），但阈值 ``refStarRiskMax`` 永远不会被越过，
    接口四实际上从未生效。早停用 AUC 而不是损失：部署侧只关心「高风险排在前面」，
    交叉熵的绝对值会被类别权重带偏。
    """
    import torch

    torch.manual_seed(seed)
    opt = torch.optim.Adam(model.parameters(), lr=lr)
    xt = torch.from_numpy(np.asarray(x_train, dtype=np.float32))
    yt = torch.from_numpy(np.asarray(y_train, dtype=np.float32)).view(-1, 1)
    xv = torch.from_numpy(np.asarray(x_val, dtype=np.float32))
    pw = torch.tensor([pos_weight], dtype=torch.float32)

    def loss_fn(xb, yb):
        logits = model(xb).clamp(1e-6, 1.0 - 1e-6)
        loss = -(pw * yb * torch.log(logits) + (1.0 - yb) * torch.log(1.0 - logits))
        return loss.mean()

    best = {"auc": -1.0, "loss": float("inf"), "state": None, "epoch": -1}
    bad = 0
    n = xt.shape[0]
    yv_np = np.asarray(y_val, dtype=np.float64)
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
            val_score = model(xv).numpy().reshape(-1)
            val_loss = float(loss_fn(xv, yt.new_tensor(yv_np).view(-1, 1)))
        val_auc = auc(val_score, yv_np)
        if not np.isfinite(val_auc):
            val_auc = -1.0
        print(f"  epoch {epoch + 1:3d}/{epochs}: train={total / n:.4f} "
              f"val={val_loss:.4f} val_auc={val_auc:.4f}")
        if val_auc > best["auc"] + 1e-5:
            best = {"auc": val_auc, "loss": val_loss,
                    "state": {k: v.clone() for k, v in model.state_dict().items()},
                    "epoch": epoch + 1}
            bad = 0
        else:
            bad += 1
            if bad >= patience:
                print(f"  早停：验证 AUC 连续 {patience} 轮未改善（最佳 epoch={best['epoch']}）")
                break
    if best["state"] is not None:
        model.load_state_dict(best["state"])
    model.eval()
    return best


def _onnx_predict(onnx_path: Path, x: np.ndarray, batch: int = 1024) -> np.ndarray:
    import onnxruntime as ort

    sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    name = sess.get_inputs()[0].name
    outs = []
    for i in range(0, x.shape[0], batch):
        outs.append(sess.run(None, {name: np.asarray(x[i:i + batch], dtype=np.float32)})[0])
    return np.concatenate(outs, axis=0).reshape(-1)


def evaluate(table, norm: _Normalizer, onnx_path: Path,
             risk_max: float = DEFAULT_RISK_MAX,
             switch_rate_max: float = DEFAULT_SWITCH_RATE_MAX) -> dict:
    """测试站评估：AUC、阈值处的精确率/召回率、高风险历元占比（切换预算代理）。

    ``switch_budget_proxy`` 只是代理：真实切换率的分母是「每组合法历元数」，
    且受迟滞与熔断共同约束，只能由 Java 侧实测。此处给的是**上界直觉**——
    若高风险行占比本身就远超 ``refStarSwitchRateMax``，说明阈值定得太松，
    上线后必然撞熔断。
    """
    x = dataset.features(table)
    y = dataset.labels(table).astype(np.float64)
    station = np.asarray(table["station"].to_pylist())

    score = _onnx_predict(onnx_path, norm.apply(x))
    flag = score > risk_max

    result = {
        "n_rows": int(y.size),
        "positive_rate": float(y.mean()) if y.size else 0.0,
        "auc": auc(score, y),
        "risk_max": risk_max,
        "at_threshold": {
            "flagged_rows": int(flag.sum()),
            "flagged_rate": float(flag.mean()) if y.size else 0.0,
            "precision": float(y[flag].mean()) if flag.any() else 0.0,
            "recall": float(y[flag].sum() / y.sum()) if y.sum() > 0 else 0.0,
        },
        "score_range": [float(score.min()), float(score.max())] if score.size else [0.0, 0.0],
        "switch_budget_proxy": {
            "flagged_rate": float(flag.mean()) if y.size else 0.0,
            "switch_rate_max": switch_rate_max,
            "note": "代理值：真实切换率的分母是每组合法历元数，且受迟滞/熔断约束，须由 Java 侧实测",
        },
        "per_station": {},
    }
    for st in sorted(set(station.tolist())):
        m = station == st
        result["per_station"][st] = {
            "n_rows": int(m.sum()),
            "positive_rate": float(y[m].mean()),
            "auc": auc(score[m], y[m]),
            "flagged_rate": float(flag[m].mean()),
        }
    return result


def main(argv: "list[str] | None" = None) -> int:
    ap = argparse.ArgumentParser(description="接口四（参考星风险判别）训练")
    ap.add_argument("--dataset", type=Path, help="Parquet 数据集路径")
    ap.add_argument("--demo", action="store_true", help="生成「仅演示」数据集后训练")
    ap.add_argument("--out-dir", type=Path, default=Path("training/out"))
    ap.add_argument("--epochs", type=int, default=30)
    ap.add_argument("--batch-size", type=int, default=256)
    ap.add_argument("--lr", type=float, default=3e-3)
    ap.add_argument("--hidden", type=int, default=32)
    ap.add_argument("--patience", type=int, default=5)
    ap.add_argument("--seed", type=int, default=20261114)
    ap.add_argument("--risk-max", type=float, default=DEFAULT_RISK_MAX,
                    help="评估用的风险阈值（须与 AiOpts.refStarRiskMax 一致）")
    ap.add_argument("--min-events", type=int, default=MIN_EVENTS,
                    help="双阈值之一：事件数下限，低于此值终止管线（退出码 2）")
    ap.add_argument("--min-pos-rate", type=float, default=MIN_POSITIVE_RATE,
                    help="双阈值之一：正例比例下限，低于此值终止管线（退出码 2）")
    ap.add_argument("--eval-only", action="store_true",
                    help="复用已有 ONNX + norm.json，只跑契约校验与评估（不训练、不导出）")
    args = ap.parse_args(argv)

    if not args.demo and args.dataset is None:
        ap.error("必须指定 --dataset 或 --demo")

    args.out_dir.mkdir(parents=True, exist_ok=True)

    if args.demo:
        table = dataset.make_demo(seed=args.seed)
        path = args.out_dir / "demo_refstar_sliprisk.parquet"
        dataset.write(table, path, note="仅演示：证明链路跑通，不承诺模型效果")
        print(f"已生成演示数据集: {path}（{len(table)} 行）")
    else:
        table = dataset.read(args.dataset)

    feas = feasibility(table, args.min_events, args.min_pos_rate)
    print(f"\n可行性前置（双阈值）: 行={feas['rows']} 事件={feas['events']} "
          f"正例比例={feas['positive_rate']:.2%}")
    if not feas["passed"]:
        print(f"  [终止] {feas['reason']}")
        print("  管线不产出模型：样本量不足时训出的产物会让人误以为接口四已可用。")
        print("  补救：扩站/扩日、或降低滑相事件的漏采（确认步骤 0 的 IS_REF 与特征同配置）。")
        out = args.out_dir / "refstar_sliprisk.metrics.json"
        out.write_text(json.dumps({"mode": "terminated", "feasibility": feas},
                                  ensure_ascii=False, indent=2), encoding="utf-8")
        print(f"  终止记录已写入: {out}")
        return 2

    split = common_dataset.two_layer_split(table, seed=args.seed)
    print("两层切分（先按站、再按天）:\n" + split.summary())

    x_train = dataset.features(split.train)
    y_train = dataset.labels(split.train).astype(np.float64)
    detected = [dataset.FEATURE_COLUMNS[i] for i in range(dataset.DIM)
                if float(x_train[:, i].std()) < 1e-8]
    corr = pseudo_correlation(x_train, y_train)
    print(f"伪相关探测: max|corr|={corr['max_abs']:.3f}"
          f"（{corr['max_feature']}），阈值 {PSEUDO_CORRELATION_MAX}")
    if corr["max_abs"] >= PSEUDO_CORRELATION_MAX:
        print(f"  [终止] 特征 {corr['max_feature']} 与标签的相关系数过高，疑似标签泄漏。")
        print("  接口四最常见的泄漏源是 resp/resc（已结构性剔除）；请检查是否误加了"
              "「当历元是否参考星」这类当历元标签。")
        return 2
    if sorted(detected) != sorted(CONSTANT_COLS):
        print(f"  [提示] 实测恒零列 {detected}，声明 {list(CONSTANT_COLS)}"
              f"（不一致不阻断，仅影响归一化解释）")

    onnx_path = args.out_dir / "refstar_sliprisk.onnx"
    norm_path = args.out_dir / "refstar_sliprisk.norm.json"

    if args.eval_only:
        if not onnx_path.exists() or not norm_path.exists():
            ap.error(f"--eval-only 需要已存在的 {onnx_path} 与 {norm_path}")
        norm = _Normalizer.load(norm_path)
        best = {"auc": float("nan"), "loss": float("nan"), "epoch": -1}
        print(f"仅评估模式：复用 {onnx_path.name} + {norm_path.name}（不训练、不导出）")
    else:
        x_val = dataset.features(split.val)
        y_val = dataset.labels(split.val).astype(np.float64)
        norm = _Normalizer.fit(x_train)
        print(f"归一化参数已拟合（仅训练集，{x_train.shape[0]} 行），"
              f"恒零列 {len(detected)} 个（按 1.0 兜底，保留占位不删维）")

        pos = float(y_train.sum())
        neg = float(y_train.size - pos)
        pos_weight = float(max(1.0, neg / max(pos, 1.0)))
        print(f"正例权重 pos_weight={pos_weight:.2f}（正例 {int(pos)} / 负例 {int(neg)}）")

        model = build_model(args.hidden)
        best = train(model, norm.apply(x_train), y_train, norm.apply(x_val), y_val,
                     args.epochs, args.batch_size, args.lr, args.patience, args.seed,
                     pos_weight)

        export_onnx.export(model, onnx_path, dim=dataset.DIM)
        export_onnx.write_norm(norm_path, norm.mean, norm.std,
                               note="由 training/ref_star/train.py 拟合（仅训练集）",
                               dim=dataset.DIM, constant_cols=list(CONSTANT_COLS))
        print(f"导出完成: {onnx_path} + {norm_path}")

    contract = export_onnx.check_contract_confidence(onnx_path, dim=dataset.DIM)
    print(f"契约校验通过: 输入 {contract['input_shape']} / 输出 {contract['output_shape']}"
          f" / batch 探针 {sorted(contract['batch_probes'])}")

    test = evaluate(split.test, norm, onnx_path, args.risk_max)
    result = {
        "mode": "eval-only" if args.eval_only else "train",
        "best_epoch": best["epoch"],
        "best_val_auc": best["auc"],
        "best_val_loss": best["loss"],
        "feasibility": feas,
        "pseudo_correlation": {"max_abs": corr["max_abs"],
                               "max_feature": corr["max_feature"],
                               "threshold": PSEUDO_CORRELATION_MAX},
        "constant_cols": {"declared": list(CONSTANT_COLS), "detected": detected},
        "split": {
            "train_stations": split.train_stations,
            "val_stations": split.val_stations,
            "test_stations": split.test_stations,
        },
        "contract": contract,
        "test": test,
        "note": "仅演示数据集不承诺模型效果；接口四是实验接口（默认关闭、仅显式注入），"
                "风险分学的是滑相前兆而非 NLOS 判别，不得当污染判别器使用",
    }
    metrics_path = args.out_dir / "refstar_sliprisk.metrics.json"
    metrics_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")

    print("\n测试站评估（未参与训练与早停）:")
    print(f"  行={test['n_rows']} 正例比例={test['positive_rate']:.2%} AUC={test['auc']:.4f}")
    at = test["at_threshold"]
    print(f"  阈值 {test['risk_max']}: 触发行={at['flagged_rows']}（{at['flagged_rate']:.2%}）"
          f" precision={at['precision']:.3f} recall={at['recall']:.3f}")
    print(f"  切换预算代理: 高风险行占比 {test['switch_budget_proxy']['flagged_rate']:.2%}"
          f"（上限 {test['switch_budget_proxy']['switch_rate_max']:.0%}，代理口径见 metrics）")
    for st, m in test["per_station"].items():
        print(f"    {st}: n={m['n_rows']} pos={m['positive_rate']:.2%} "
              f"AUC={m['auc']:.4f} flagged={m['flagged_rate']:.2%}")
    print(f"  评估结果已写入: {metrics_path}")
    print("\n提醒：真实数据必须先有步骤 0（未注入状态跑 AI4/IS_REF，再同配置重跑采集），"
          "否则训练产物无效、R1-6 不可验证。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())