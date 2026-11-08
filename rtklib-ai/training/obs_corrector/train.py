"""接口三（观测改正）训练管道入口（设计文档 §6.1 / §6.3 / §7）。

流程定死为：读 Parquet → 两层切分（按站、再按天）→ 训练集拟合归一化 → 训练
→ 早停 → 导出 ONNX + ``norm.json`` → 契约校验 → 在测试站上评估。

用法（在 ``rtklib-ai`` 目录下执行）::

    python -m training.obs_corrector.train --demo
    python -m training.obs_corrector.train --dataset data/obs_corrector.parquet --out-dir out

**示例数据只证明链路跑通，不承诺模型效果**（§6.3）。
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np

try:
    from training.common import dataset, export_onnx, metrics
except ImportError:  # 允许直接以脚本方式运行
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
    from training.common import dataset, export_onnx, metrics


class _Normalizer:
    """训练集拟合、推理侧原样加载的归一化（禁止按运行数据动态归一化，§5.3）。"""

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


def build_model(hidden: int, bias_max: float, scale_max: float):
    """MLP + 契约安全头：``bias = B·tanh(z)``、``scale = 1 + (S-1)·sigmoid(z)``。

    输出天然落在 core 钳位区间内，避免「训练时越界、推理时被钳掉」的静默失真。
    """
    import torch
    from torch import nn

    class ObsCorrectorNet(nn.Module):
        def __init__(self):
            super().__init__()
            self.fc1 = nn.Linear(dataset.DIM, hidden)
            self.fc2 = nn.Linear(hidden, hidden)
            self.bias_head = nn.Linear(hidden, 1)
            self.scale_head = nn.Linear(hidden, 1)
            self.bias_max = bias_max
            self.scale_span = scale_max - 1.0

        def forward(self, x):
            h = torch.relu(self.fc1(x))
            h = torch.relu(self.fc2(h))
            bias = self.bias_max * torch.tanh(self.bias_head(h))
            scale = 1.0 + self.scale_span * torch.sigmoid(self.scale_head(h))
            return torch.cat([bias, scale], dim=1)

    return ObsCorrectorNet()


def train(model, x_train, y_train, x_val, y_val, epochs: int, batch_size: int,
          lr: float, patience: int, seed: int):
    """高斯负对数似然训练 + 早停。

    单个标量标签（伪距误差真值，米）同时监督两个输出：``bias`` 拟合均值，
    ``scale`` 拟合残差标准差——这正是 §4.3.4 要求的不确定性传播来源。
    """
    import torch

    torch.manual_seed(seed)
    opt = torch.optim.Adam(model.parameters(), lr=lr)
    xt = torch.from_numpy(np.asarray(x_train, dtype=np.float32))
    yt = torch.from_numpy(np.asarray(y_train, dtype=np.float32))
    xv = torch.from_numpy(np.asarray(x_val, dtype=np.float32))
    yv = torch.from_numpy(np.asarray(y_val, dtype=np.float32))

    def loss_fn(xb, yb):
        out = model(xb)
        bias, scale = out[:, 0], out[:, 1]
        var = scale * scale
        return torch.mean(torch.log(var) + (yb - bias) ** 2 / var)

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
    import onnxruntime as ort

    sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    name = sess.get_inputs()[0].name
    outs = []
    for i in range(0, x.shape[0], batch):
        outs.append(sess.run(None, {name: np.asarray(x[i:i + batch], dtype=np.float32)})[0])
    return np.concatenate(outs, axis=0)


def evaluate(table, norm: _Normalizer, onnx_path: Path) -> dict:
    """测试站评估：改正前后残差 RMS、逐站分布、尺度标定覆盖度。"""
    x = dataset.features(table)
    y = dataset.labels(table).astype(np.float64)
    station = np.asarray(table["station"].to_pylist())

    out = _onnx_predict(onnx_path, norm.apply(x))
    bias, scale = out[:, 0].astype(np.float64), out[:, 1].astype(np.float64)
    before = y
    after = y - bias

    point, lo, hi = metrics.relative_improvement_ci(before, after)
    result = {
        "n_rows": int(y.size),
        "rmse_before": metrics.rmse(before, np.zeros_like(before)),
        "rmse_after": metrics.rmse(after, np.zeros_like(after)),
        "mae_before": metrics.mae(before, np.zeros_like(before)),
        "mae_after": metrics.mae(after, np.zeros_like(after)),
        "relative_rmse_improvement": {"point": point, "ci95": [lo, hi]},
        "scale_1sigma_coverage": float(np.mean(np.abs(after) <= scale)),
        "per_station": {},
    }
    for st in sorted(set(station.tolist())):
        m = station == st
        p, l, h = metrics.relative_improvement_ci(before[m], after[m])
        result["per_station"][st] = {
            "n_rows": int(m.sum()),
            "rmse_before": metrics.rmse(before[m], np.zeros(int(m.sum()))),
            "rmse_after": metrics.rmse(after[m], np.zeros(int(m.sum()))),
            "relative_improvement": {"point": p, "ci95": [l, h]},
        }
    return result


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="接口三训练管道（仅演示数据不承诺效果）")
    ap.add_argument("--dataset", type=Path, help="Parquet 数据集路径")
    ap.add_argument("--demo", action="store_true", help="生成「仅演示」数据集后训练")
    ap.add_argument("--out-dir", type=Path, default=Path("training/out"))
    ap.add_argument("--epochs", type=int, default=30)
    ap.add_argument("--batch-size", type=int, default=256)
    ap.add_argument("--lr", type=float, default=3e-3)
    ap.add_argument("--hidden", type=int, default=32)
    ap.add_argument("--patience", type=int, default=5)
    ap.add_argument("--seed", type=int, default=20261107)
    ap.add_argument("--bias-max", type=float, default=2.0, help="bias 头饱和值（米，须 < core 钳位 10 m）")
    ap.add_argument("--scale-max", type=float, default=4.0, help="scale 头上限（须 ≤ core 钳位 4.0）")
    args = ap.parse_args(argv)

    if not args.demo and args.dataset is None:
        ap.error("必须指定 --dataset 或 --demo")

    args.out_dir.mkdir(parents=True, exist_ok=True)

    if args.demo:
        table = dataset.make_demo(seed=args.seed)
        path = args.out_dir / "demo_obs_corrector.parquet"
        dataset.write(table, path, note="仅演示：证明链路跑通，不承诺模型效果")
        print(f"已生成演示数据集: {path}（{len(table)} 行）")
    else:
        table = dataset.read(args.dataset)

    split = dataset.two_layer_split(table, seed=args.seed)
    print("两层切分（先按站、再按天，§7.3）:\n" + split.summary())

    x_train = dataset.features(split.train)
    y_train = dataset.labels(split.train).astype(np.float64)
    x_val = dataset.features(split.val)
    y_val = dataset.labels(split.val).astype(np.float64)

    norm = _Normalizer.fit(x_train)
    print(f"归一化参数已拟合（仅训练集，{x_train.shape[0]} 行），"
          f"恒零列 {int(np.sum(norm.std == 1.0))} 个（按 1.0 兜底）")

    model = build_model(args.hidden, args.bias_max, args.scale_max)
    best = train(model, norm.apply(x_train), y_train, norm.apply(x_val), y_val,
                 args.epochs, args.batch_size, args.lr, args.patience, args.seed)

    onnx_path = args.out_dir / "obs_corrector.onnx"
    export_onnx.export(model, onnx_path)
    norm_path = args.out_dir / "obs_corrector.norm.json"
    export_onnx.write_norm(norm_path, norm.mean, norm.std,
                           note="由 training/obs_corrector/train.py 拟合（仅训练集）")

    contract = export_onnx.check_contract(onnx_path, args.bias_max, args.scale_max)
    print(f"导出完成: {onnx_path} + {norm_path}")
    print(f"契约校验通过: 输入 {contract['input_shape']} / 输出 {contract['output_shape']}"
          f" / batch 探针 {sorted(contract['batch_probes'])}")

    test = evaluate(split.test, norm, onnx_path)
    result = {
        "best_epoch": best["epoch"],
        "best_val_loss": best["loss"],
        "split": {
            "train_stations": split.train_stations,
            "val_stations": split.val_stations,
            "test_stations": split.test_stations,
        },
        "contract": contract,
        "test": test,
        "note": "仅演示数据集不承诺模型效果（§6.3）；G-1~G-4 属接口一判据，"
                "接口三在此报告残差收益，G-5 推理开销须由 Java 侧实测",
    }
    metrics_path = args.out_dir / "obs_corrector.metrics.json"
    metrics_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")

    print("\n测试站评估（未参与训练与早停）:")
    print(f"  残差 RMS: {result['test']['rmse_before']:.4f} m → {result['test']['rmse_after']:.4f} m")
    print(f"  相对改善: {result['test']['relative_rmse_improvement']['point']:.1%}"
          f"（95% CI [{result['test']['relative_rmse_improvement']['ci95'][0]:.1%}, "
          f"{result['test']['relative_rmse_improvement']['ci95'][1]:.1%}]）")
    for st, m in result["test"]["per_station"].items():
        print(f"    {st}: RMS {m['rmse_before']:.4f} → {m['rmse_after']:.4f} m"
              f"（改善 {m['relative_improvement']['point']:.1%}）")
    print(f"  评估结果已写入: {metrics_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())