"""接口 B（PPP 观测方差缩放）训练管道入口（设计文档 §12.2 / §4.6）。

流程定死为：读 Parquet → 两层切分（按站、再按天）→ 训练集拟合归一化 → 训练
→ 早停 → 导出 ONNX + ``norm.json`` → 契约校验 → 测试站评估（池化 + 按站 + 按天）。

**输出契约是单侧膨胀**：模型输出 ``scale = 1 + (scaleMax - 1) * sigmoid(logit)``，
天然落在 ``[1, scaleMax]``，与 ``AiOpts.pppVarianceScaleMax`` 默认 4.0 对齐；
core 侧只把因子乘到 R 的对角元，**不改残差、不改坐标**（§4.6）。没有
``biasMeters`` 输出——那是接口三的东西，接口 B 只做方差缩放。

**损失函数是高斯负对数似然**（去掉常数项）::

    loss = log(s) + (v / s)^2          v = resp / sigma，s = 预测膨胀因子

它同时惩罚「少膨胀」（``(v/s)^2`` 变大）与「过度膨胀」（``log(s)`` 变大），
因此模型不会退化成「一律给 scaleMax」。``s`` 的下界 1 由输出头保证，上界由
``--scale-max`` 保证，与 core 钳位区间一致。

**必须知道的局限（写进 metrics.json 的 note）**：``resp`` 同时出现在特征里，
所以残差 RMS 的下降有相当部分是「用当前残差缩放当前残差」的自证效果。
真实收益必须由 A0–A3 消融（开/关接口 B 对比定位精度与收敛时间）给出，
本脚本只负责证明链路可训练、输出合法、指标可复算。

用法（在 ``rtklib-ai`` 目录下执行）::

    python -m training.ppp_variance_scaler.train --demo
    python -m training.ppp_variance_scaler.train \\
        --dataset data/ppp_variance_scaler.parquet --out-dir training/out
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np

try:
    from training.common import export_onnx
    from training.common import ppp_dataset as dataset
except ImportError:  # 允许直接以脚本方式运行
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
    from training.common import export_onnx
    from training.common import ppp_dataset as dataset

MODEL_NAME = "ppp_variance_scaler.onnx"
NORM_NAME = "ppp_variance_scaler.norm.json"
METRICS_NAME = "ppp_variance_scaler.metrics.json"

#: 与 ``AiOpts.DEFAULT_PPP_VARIANCE_SCALE_MAX`` 一致；改这里必须同步改 core 默认值。
DEFAULT_SCALE_MAX = 4.0


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


def build_model(hidden: int, scale_max: float):
    """MLP + sigmoid 头 → 单侧膨胀因子 ``[1, scale_max]``。"""
    import torch
    from torch import nn

    span = float(scale_max) - 1.0

    class PppVarianceScalerNet(nn.Module):
        def __init__(self):
            super().__init__()
            self.fc1 = nn.Linear(dataset.B_DIM, hidden)
            self.fc2 = nn.Linear(hidden, hidden)
            self.head = nn.Linear(hidden, 1)

        def _trunk(self, x):
            h = torch.relu(self.fc1(x))
            return torch.relu(self.fc2(h))

        def forward(self, x):
            return 1.0 + span * torch.sigmoid(self.head(self._trunk(x)))

        def scale(self, x):
            return self.forward(x).squeeze(1)

    return PppVarianceScalerNet()


def train(model, x_train, v_train, x_val, v_val, epochs: int, batch_size: int,
          lr: float, patience: int, seed: int, v_clip: float) -> dict:
    """高斯 NLL 训练 + 早停。

    ``v_clip`` 把归一化残差钳到 ``[-v_clip, v_clip]``：``|v| > 6σ`` 的行几乎必然是
    异常值，而 ``s`` 上限只有 4，其精确幅度对梯度没有信息量，钳位只是防止
    少量极端行主导整批梯度（与 core 侧「大残差直接剔除」同一取向）。
    """
    import torch

    torch.manual_seed(seed)
    opt = torch.optim.Adam(model.parameters(), lr=lr)
    xt = torch.from_numpy(np.asarray(x_train, dtype=np.float32))
    vt = torch.from_numpy(np.clip(v_train, -v_clip, v_clip).astype(np.float32))
    xv = torch.from_numpy(np.asarray(x_val, dtype=np.float32))
    vv = torch.from_numpy(np.clip(v_val, -v_clip, v_clip).astype(np.float32))

    def loss_fn(xb, vb):
        s = model.scale(xb)
        return torch.mean(torch.log(s) + (vb / s) ** 2)

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
            loss = loss_fn(xt[idx], vt[idx])
            loss.backward()
            opt.step()
            total += float(loss.detach()) * len(idx)
        model.eval()
        with torch.no_grad():
            val_loss = float(loss_fn(xv, vv))
        print(f"  epoch {epoch + 1:3d}/{epochs}: train={total / n:.4f} val={val_loss:.4f}")
        if val_loss < best["loss"] - 1e-5:
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


def _metrics(v: np.ndarray, s: np.ndarray) -> dict:
    """缩放前后的标准化残差指标。

    - ``rms``：``sqrt(mean(v^2))``，理想值 1.0（残差正好等于名义 σ）；
    - ``coverage_1sigma / 2sigma``：``|v| <= 1 / 2`` 的比例，高斯下理想 68.3% / 95.4%；
    - ``mean_scale`` / ``frac_scaled``：平均膨胀因子与「真被膨胀」的行占比
      （``s > 1.001`` 视为生效），即接口 B 的「膨胀率」。
    """
    v = np.asarray(v, dtype=np.float64)
    s = np.asarray(s, dtype=np.float64)
    if v.size == 0:
        return {}
    after = v / s
    return {
        "n_rows": int(v.size),
        "rms_before": float(np.sqrt(np.mean(v ** 2))),
        "rms_after": float(np.sqrt(np.mean(after ** 2))),
        "coverage_1sigma_before": float(np.mean(np.abs(v) <= 1.0)),
        "coverage_1sigma_after": float(np.mean(np.abs(after) <= 1.0)),
        "coverage_2sigma_before": float(np.mean(np.abs(v) <= 2.0)),
        "coverage_2sigma_after": float(np.mean(np.abs(after) <= 2.0)),
        "mean_scale": float(np.mean(s)),
        "p90_scale": float(np.percentile(s, 90)),
        "max_scale": float(np.max(s)),
        "frac_scaled": float(np.mean(s > 1.001)),
        "nll_before": float(np.mean(np.log(1.0) + v ** 2)),
        "nll_after": float(np.mean(np.log(s) + after ** 2)),
    }


def _days(table) -> list:
    return sorted(set(table["day"].to_pylist()))


def evaluate(table, norm: _Normalizer, onnx_path: Path) -> dict:
    """测试站评估：池化 + 逐站 + 逐日两层指标（§12.2 硬要求 2）。"""
    x = dataset.features_b(table)
    v = dataset.target_b(table).astype(np.float64)
    station = np.asarray(table["station"].to_pylist())
    day = np.asarray(table["day"].to_pylist())

    s = _onnx_predict(onnx_path, norm.apply(x))
    pooled = _metrics(v, s)

    per_station = {}
    for st in sorted(set(station.tolist())):
        m = station == st
        per_station[st] = _metrics(v[m], s[m])

    per_day = {}
    for d in sorted(set(day.tolist())):
        m = day == d
        per_day[d] = _metrics(v[m], s[m])

    return {"pooled": pooled, "per_station": per_station, "per_day": per_day}


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(
        description="接口 B 训练管道（PPP 观测方差缩放；仅演示数据不承诺效果）")
    ap.add_argument("--dataset", type=Path, help="Parquet 数据集路径")
    ap.add_argument("--demo", action="store_true", help="生成「仅演示」数据集后训练")
    ap.add_argument("--out-dir", type=Path, default=Path("training/out"))
    ap.add_argument("--epochs", type=int, default=40)
    ap.add_argument("--batch-size", type=int, default=1024)
    ap.add_argument("--lr", type=float, default=3e-3)
    ap.add_argument("--hidden", type=int, default=32)
    ap.add_argument("--patience", type=int, default=6)
    ap.add_argument("--seed", type=int, default=20261114)
    ap.add_argument("--n-val-stations", type=int, default=1)
    ap.add_argument("--scale-max", type=float, default=DEFAULT_SCALE_MAX,
                    help=f"膨胀因子上限，必须与 AiOpts.pppVarianceScaleMax 一致"
                         f"（默认 {DEFAULT_SCALE_MAX}）")
    ap.add_argument("--v-clip", type=float, default=6.0,
                    help="训练时归一化残差的钳位幅度（默认 6，防极端行主导梯度）")
    args = ap.parse_args(argv)

    if not args.demo and args.dataset is None:
        ap.error("必须指定 --dataset 或 --demo")
    if args.scale_max <= 1.0:
        ap.error("--scale-max 必须 > 1（单侧膨胀下界为 1，上界须严格大于 1）")

    args.out_dir.mkdir(parents=True, exist_ok=True)

    if args.demo:
        table = dataset.make_demo_b(seed=args.seed)
        path = args.out_dir / "demo_ppp_variance_scaler.parquet"
        dataset.write_b(table, path, note="仅演示：证明链路跑通，不承诺模型效果")
        print(f"已生成演示数据集: {path}（{len(table)} 行）")
    else:
        table = dataset.read_b(args.dataset)

    v_all = dataset.target_b(table).astype(np.float64)
    print(f"数据集：{len(table)} 行 / {len(set(table['station'].to_pylist()))} 站 / "
          f"{len(_days(table))} 天；|v_norm| p50/p90 = "
          f"{np.percentile(np.abs(v_all), 50):.3f} / "
          f"{np.percentile(np.abs(v_all), 90):.3f}；"
          f"1σ 覆盖率 {float(np.mean(np.abs(v_all) <= 1.0)):.1%}（理想 68.3%）")

    split = dataset.split(table, seed=args.seed, n_val_stations=args.n_val_stations,
                          n_test_stations=2)
    print("\n两层切分（先按站、再按天，§7.3）:\n" + split.summary())

    x_train = dataset.features_b(split.train)
    v_train = dataset.target_b(split.train).astype(np.float64)
    x_val = dataset.features_b(split.val)
    v_val = dataset.target_b(split.val).astype(np.float64)

    norm = _Normalizer.fit(x_train)
    print(f"归一化参数已拟合（仅训练集 {x_train.shape[0]} 行），"
          f"恒零列 {int(np.sum(norm.std == 1.0))} 个（按 1.0 兜底）")

    model = build_model(args.hidden, args.scale_max)
    best = train(model, norm.apply(x_train), v_train, norm.apply(x_val), v_val,
                 args.epochs, args.batch_size, args.lr, args.patience, args.seed,
                 args.v_clip)

    onnx_path = args.out_dir / MODEL_NAME
    export_onnx.export(model, onnx_path, dim=dataset.B_DIM)
    norm_path = args.out_dir / NORM_NAME
    export_onnx.write_norm(norm_path, norm.mean, norm.std,
                           note="由 training/ppp_variance_scaler/train.py 拟合（仅训练集）",
                           dim=dataset.B_DIM)
    contract = export_onnx.check_contract_scale(onnx_path, dim=dataset.B_DIM,
                                                scale_min=1.0,
                                                scale_max=args.scale_max)
    print(f"导出完成: {onnx_path} + {norm_path}")
    print(f"契约校验通过: 输入 {contract['input_shape']} / 输出 {contract['output_shape']}"
          f" / batch 探针 {sorted(contract['batch_probes'])}"
          f" / scale 区间 [{1.0:.1f}, {args.scale_max:.1f}]")

    test = evaluate(split.test, norm, onnx_path)

    result = {
        "seed": args.seed,
        "hparams": {"epochs": args.epochs, "batch_size": args.batch_size,
                    "lr": args.lr, "hidden": args.hidden, "patience": args.patience,
                    "scale_max": args.scale_max, "v_clip": args.v_clip},
        "dataset": {
            "path": str(args.dataset) if args.dataset else "demo",
            "n_rows": int(len(table)),
            "stations": sorted(set(table["station"].to_pylist())),
            "days": _days(table),
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
        "best_epoch": best["epoch"],
        "best_val_loss": best["loss"],
        "contract": contract,
        "test": test,
        "artifacts": {"onnx": str(onnx_path), "norm": str(norm_path)},
        "note": ("仅演示数据集不承诺模型效果（§12.1）。接口 B 只乘 R 对角元，"
                 "不改残差、不改坐标、不剔除卫星；因子单侧约束 [1, scale_max]。"
                 "resp 同时是特征与目标的分子，故残差 RMS 下降含「自适应缩放」的"
                 "自证成分，真实收益须由 A0–A3 消融（开/关接口 B 对比定位精度与"
                 "收敛时间）给出，本报告不得据此下收益结论。"),
    }
    metrics_path = args.out_dir / METRICS_NAME
    metrics_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")

    print("\n测试站指标:")
    p = test["pooled"]
    print(f"  标准化残差 RMS: {p['rms_before']:.3f} → {p['rms_after']:.3f}"
          f"（理想 1.0；下降 {1.0 - p['rms_after'] / p['rms_before']:.1%}）")
    print(f"  1σ 覆盖率: {p['coverage_1sigma_before']:.1%} → {p['coverage_1sigma_after']:.1%}"
          f"（理想 68.3%）")
    print(f"  平均膨胀因子 {p['mean_scale']:.3f}（p90 {p['p90_scale']:.3f} / "
          f"max {p['max_scale']:.3f}）；生效行占比 {p['frac_scaled']:.1%}")
    print("  逐站：" + "，".join(
        f"{st} RMS {r['rms_before']:.2f}→{r['rms_after']:.2f} / 膨胀 {r['mean_scale']:.2f}"
        for st, r in test["per_station"].items()))
    print("  逐日：" + "，".join(
        f"{d} RMS {r['rms_before']:.2f}→{r['rms_after']:.2f}"
        for d, r in test["per_day"].items()))
    print(f"\n评估报告: {metrics_path}")
    print("提醒：单站单日、相邻历元高度相关，任何指标差异都不具统计意义（§12.1）。")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())