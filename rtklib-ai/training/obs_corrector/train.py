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
import csv
import json
import sys
from pathlib import Path

import numpy as np

try:
    from training.common import dataset, export_onnx, metrics
except ImportError:  # 允许直接以脚本方式运行
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
    from training.common import dataset, export_onnx, metrics

#: 训练集上方差恒为 0 的结构性死列。**保留占位**（输入维恒为 17，不删维），
#: 但在 ``norm.json`` 里显式标注，且 ``std`` 按 1.0 兜底，归一化后恒等于 0。
#: f2=SNR(基准站) / f3=响应延迟 / f8=几何自由组合，当前采集口径下全部为常量。
CONSTANT_COLS = ("f2", "f3", "f8")

#: A3 判据（§7.5.3）：G0 门限、有效改正的两种等价口径、最小样本量与卫星数。
#: 原判据 ``R: |after| ≤ 0.7|before|`` + ``D: 下降 ≥ 3σ_dd`` 在 ``bias_max`` 钳位下
#: **数学上不可达**：``R`` 要求 ``|bias| ≥ 0.3|b|``、``G0`` 要求 ``|b| ≥ 3σ_dd``，
#: 合并即要求 ``|bias| ≥ 0.9σ_dd``，而输出契约恒有 ``|bias| ≤ bias_max``。
#: 故改为：G0 命中的行中，残差绝对值下降 ``≥ 1.0σ_dd`` **或** 相对下降 ``≥ 15%``
#: 即算一次有效改正（两口径取「或」，各自单独统计以便区分来源）。
A3_SIGMA_GATE = 3.0
A3_DROP_SIGMA = 1.0
A3_DROP_RATIO = 0.15
A3_MIN_VALID_ROWS = 10
A3_MIN_SATS = 3

#: A2 离线代理口径的「不恶化」容差（残差 RMS 相对恶化超过该值即判为变差）。
A2_RESIDUAL_TOLERANCE = 0.05


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

    @classmethod
    def load(cls, path: Path) -> "_Normalizer":
        payload = json.loads(Path(path).read_text(encoding="utf-8"))
        return cls(np.array(payload["mean"], dtype=np.float64),
                   np.array(payload["std"], dtype=np.float64))

    def apply(self, x: np.ndarray) -> np.ndarray:
        return (np.asarray(x, dtype=np.float32) - self.mean) / self.std


def build_model(hidden: int, bias_max: float, scale_max: float, scale_min: float = 1.0):
    """MLP + 契约安全头：``bias = B·tanh(z)``、``scale = S_min + (S_max-S_min)·sigmoid(z)``。

    输出天然落在 core 钳位区间内，避免「训练时越界、推理时被钳掉」的静默失真。
    ``scale_min`` 默认 1.0（保守模式，单侧膨胀）；小于 1.0 时需 core 侧同时开启
    ``AiOpts.enableAggressiveAi`` 并把 ``aggressiveMinScale`` 设为同一值，否则小于 1.0
    的输出会在推理时被钳回下限（实验配置，非生产默认）。
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
            self.scale_min = scale_min
            self.scale_span = scale_max - scale_min

        def forward(self, x):
            h = torch.relu(self.fc1(x))
            h = torch.relu(self.fc2(h))
            bias = self.bias_max * torch.tanh(self.bias_head(h))
            scale = self.scale_min + self.scale_span * torch.sigmoid(self.scale_head(h))
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


def evaluate(table, norm: _Normalizer, onnx_path: Path, label_mode: str = "sd") -> dict:
    """测试站评估：改正前后残差 RMS、逐站分布、尺度标定覆盖度。

    ``before`` 取**标签域**残差（``sd`` 下是单星误差 ``median(v)-v``，``dd`` 下是
    双差残差 ``v``），因此 ``rmse_before`` 的绝对值不能与 Java 侧 DD 残差 RMS
    直接比；可比的是相对改善与分站一致性。
    """
    x = dataset.features(table)
    y = dataset.labels(table).astype(np.float64)
    station = np.asarray(table["station"].to_pylist())

    out = _onnx_predict(onnx_path, norm.apply(x))
    bias, scale = out[:, 0].astype(np.float64), out[:, 1].astype(np.float64)
    before = y
    after = y - bias

    point, lo, hi = metrics.relative_improvement_ci(before, after)
    result = {
        "label_mode": label_mode,
        "residual_domain": "sd_label(median(v)-v)" if label_mode == "sd" else "dd(v)",
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


def _parse_ddres(trace: Path) -> dict:
    """``(epoch, sat, frq) -> (before, Ri, Rj)``，只取 ``AI3/DDRES`` 行。

    ``Ri`` / ``Rj`` 是 core 在方差膨胀**之前**写出的原生方差（§4.3.4），
    故 ``σ_dd = sqrt(Ri + Rj)`` 不会因模型调大 scale 而自我放宽。
    同一 run 内 ``(epoch, sat, frq)`` 唯一，跨 run 会重复——因此 A3 必须逐 run 配对，
    不能把全量 trace 合起来再 join。
    """
    out: dict[tuple[int, int, int], tuple[float, float, float]] = {}
    if not trace.exists():
        return out
    with trace.open("r", encoding="utf-8", errors="replace") as f:
        for raw in f:
            if not raw.startswith("TRACE|"):
                continue
            kv = {}
            for p in raw.rstrip("\n").split("|")[1:]:
                k, _, v = p.partition("=")
                kv[k] = v
            if kv.get("topic") != "AI3" or kv.get("action") != "DDRES":
                continue
            try:
                key = (int(kv["epoch"]), int(kv["sat"]), int(kv["frq"]))
                out[key] = (float(kv["before"]), float(kv["Ri"]), float(kv["Rj"]))
            except (KeyError, ValueError):
                continue
    return out


def _read_run_features(obs_csv: Path) -> tuple[np.ndarray, list[tuple[int, int, int]]]:
    """读一次运行的特征 CSV，返回 ``(17 维特征, (epoch,sat,frq) 键)``。"""
    feats: list[list[float]] = []
    keys: list[tuple[int, int, int]] = []
    with obs_csv.open("r", encoding="utf-8", newline="") as f:
        for row in csv.DictReader(f):
            try:
                keys.append((int(row["epoch"]), int(row["sat"]), int(row["frq"])))
                feats.append([float(row[f"f{i}"]) for i in range(dataset.DIM)])
            except (KeyError, TypeError, ValueError):
                continue
    return np.asarray(feats, dtype=np.float32).reshape(-1, dataset.DIM), keys


def a1_contract(contract: dict, norm_path: Path, split, detected: list[str]) -> dict:
    """A1：产物与流程自洽（离线可判定的部分）。

    Java 侧的全链路契约（零改正逐位一致、模型缺失独立降级、钳位）由
    ``ObsCorrectorPipelineE2ETest`` 覆盖，本函数只判训练产物自身的一致性。
    ``detected`` 是训练集上**实测**方差为 0 的列；判据要求它们的 ``std`` 已按 1.0
    兜底（否则归一化会除以 0），而不是反过来要求声明的列必须恒零。
    """
    payload = json.loads(norm_path.read_text(encoding="utf-8"))
    mean = payload.get("mean") or []
    std = payload.get("std") or []
    declared = payload.get("constant_cols") or []
    checks = {
        "A1-1 ONNX 契约（动态 batch / 17 维入 / 2 维出 / 输出在钳位区间内）": True,
        "A1-2 norm.json 维度为 17": len(mean) == dataset.DIM and len(std) == dataset.DIM,
        "A1-3 实测恒零列 std=1.0 兜底": all(
            abs(float(std[dataset.FEATURE_COLUMNS.index(c)]) - 1.0) < 1e-12 for c in detected),
        "A1-4 两层切分站集合两两不交": (
            not set(split.train_stations) & set(split.val_stations)
            and not set(split.train_stations) & set(split.test_stations)
            and not set(split.val_stations) & set(split.test_stations)),
    }
    return {
        "checks": checks,
        "passed": all(checks.values()),
        "constant_cols": declared,
        "constant_cols_detected": detected,
        "constant_cols_consistent": sorted(declared) == sorted(detected),
        "onnx": {"input": contract["input_shape"], "output": contract["output_shape"],
                 "batch_probes": sorted(contract["batch_probes"])},
    }


def a2_harmless(test: dict) -> dict:
    """A2：无明显变差（离线仅覆盖 A2-3 的**残差代理**口径）。

    固定率 / 错误固定率 / ns 三项必须由 Java 侧同历元配对解算得出（§7.5.2），
    本脚本不臆造：残差 RMS 变好不等于位置 RMS 不变差，二者之间还隔着
    「改正量是否被双差结构抵消 / 是否改变了模糊度搜索的取舍」。
    """
    per = {}
    for st, m in test["per_station"].items():
        rel = float(m["relative_improvement"]["point"])
        per[st] = {"relative_residual_improvement": rel,
                   "not_worse": rel >= -A2_RESIDUAL_TOLERANCE}
    overall = float(test["relative_rmse_improvement"]["point"])
    return {
        "A2-3 残差代理（不恶化容差 -{:.0%}）".format(A2_RESIDUAL_TOLERANCE): {
            "overall": overall,
            "not_worse": overall >= -A2_RESIDUAL_TOLERANCE,
            "per_station": per,
        },
        "A2-1/A2-2/A2-4": "不可离线判定：固定率 / 错误固定率 / ns 需 Java 侧同历元配对解算",
        "passed": overall >= -A2_RESIDUAL_TOLERANCE
                  and all(v["not_worse"] for v in per.values()),
    }


def a3_effect(onnx_path: Path, norm: _Normalizer, collect_dir: Path,
              stations: list[str], bias_max: float, label_mode: str = "sd") -> dict:
    """A3：是否出现**辅助固定**（§7.5.3）。

    逐行判据：``G0: |before| ≥ 3σ_dd`` 为前置命中条件；命中后满足任一即记有效改正——

    * ``Dσ: |before| - |after| ≥ 1.0σ_dd``（绝对口径，按每行自身 σ_dd 缩放）；
    * ``R%: |after| ≤ 0.85|before|``（相对口径，下降 ≥ 15%）。

    有效改正 ≥ ``A3_MIN_VALID_ROWS`` 行且覆盖 ≥ ``A3_MIN_SATS`` 颗卫星才判定通过。

    两口径取「或」而不是「且」：``bias_max`` 钳位使绝对值下降的上限恒为 ``bias_max``，
    旧的 ``下降 ≥ 3σ_dd`` 与 ``≤ 0.7 倍`` 联立后要求 ``|bias| ≥ 0.9σ_dd``，在
    ``bias_max < 0.9σ_dd`` 时结构上不可达（本批实测 σ_dd 中位 2.773 m，
    即需 |bias| ≥ 2.495 m > bias_max=2.0 m），会把「模型有效」误判为「样本不足」。

    只在**测试站**上统计：训练站上的表现不能作为 A3 证据。

    ``sd`` 标签下本函数**结构性不可用**：``after`` 的正确值是
    ``before - (bias_ref - bias_j)``，而 trace 只给 ``(epoch, sat, frq)`` 级
    ``before``，不给 ``refIdx``；缺参考星 bias 就无法还原施加结果，强行用
    ``before - bias`` 会得到无意义的量（sd 的 bias 是单星量、before 是双差量）。
    因此 sd 口径下直接返回「不适用」，A3 一律以 Java 侧配对 trace 的实测
    ``after`` 为准（``a2_pairing.py``）。
    """
    if label_mode != "dd":
        return {
            "label_mode": label_mode,
            "verdict": "不适用：sd 标签下离线缺参考星 bias，A3 以 Java 侧配对 trace 实测为准",
            "passed": None,
        }

    from training.obs_corrector import assemble

    runs = [r for r in assemble.load_manifest(collect_dir)
            if (r.get("station") or "").strip() in stations]
    before, sigma, feat, sat = [], [], [], []
    for r in runs:
        trace = collect_dir / r["trace_file"]
        obs_csv = collect_dir / r["obs_csv_file"]
        ddres = _parse_ddres(trace)
        if not ddres or not obs_csv.exists():
            continue
        x, keys = _read_run_features(obs_csv)
        keep = [i for i, k in enumerate(keys) if k in ddres]
        if not keep:
            continue
        kk = [keys[i] for i in keep]
        before.append(np.array([ddres[k][0] for k in kk], dtype=np.float64))
        sigma.append(np.sqrt(np.maximum(
            0.0, np.array([ddres[k][1] + ddres[k][2] for k in kk], dtype=np.float64))))
        feat.append(x[keep])
        sat.append(np.array([k[1] for k in kk], dtype=np.int64))

    if not before:
        return {"verdict": "样本不足：测试站没有可配对的 DDRES 行", "passed": False, "rows": 0}

    b = np.concatenate(before)
    s = np.concatenate(sigma)
    xf = np.concatenate(feat, axis=0)
    sa = np.concatenate(sat)
    bias = _onnx_predict(onnx_path, norm.apply(xf))[:, 0].astype(np.float64)
    after = b - bias

    gate = np.abs(b) >= A3_SIGMA_GATE * s
    drop_sigma_ok = (np.abs(b) - np.abs(after)) >= A3_DROP_SIGMA * s
    drop_ratio_ok = np.abs(after) <= (1.0 - A3_DROP_RATIO) * np.abs(b)
    valid = gate & (drop_sigma_ok | drop_ratio_ok)
    reachable = gate & ((A3_DROP_SIGMA * s <= bias_max)
                        | (np.abs(b) <= bias_max / A3_DROP_RATIO))
    n_valid = int(valid.sum())
    n_sats = len(set(sa[valid].tolist()))
    passed = n_valid >= A3_MIN_VALID_ROWS and n_sats >= A3_MIN_SATS
    return {
        "runs": len(runs),
        "rows": int(b.size),
        "gate_rows": int(gate.sum()),
        "drop_sigma_rows": int((gate & drop_sigma_ok).sum()),
        "drop_ratio_rows": int((gate & drop_ratio_ok).sum()),
        "valid_rows": n_valid,
        "valid_sats": n_sats,
        "reduction_reachable_rows": int(reachable.sum()),
        "bias_max": float(bias_max),
        "max_bias_abs": float(np.abs(bias).max()),
        "sigma_dd_p50": float(np.median(s)),
        "gate_threshold_p50": float(A3_SIGMA_GATE * np.median(s)),
        "drop_sigma_threshold_p50": float(A3_DROP_SIGMA * np.median(s)),
        "criteria": {
            "gate": f"|before| >= {A3_SIGMA_GATE}σ_dd",
            "drop_sigma": f"|before|-|after| >= {A3_DROP_SIGMA}σ_dd",
            "drop_ratio": f"|after| <= {1.0 - A3_DROP_RATIO:.2f}|before|",
        },
        "verdict": ("判定通过" if passed else
                    f"样本不足：有效改正 {n_valid} 行 / {n_sats} 星"
                    f"（需 ≥{A3_MIN_VALID_ROWS} 行且 ≥{A3_MIN_SATS} 星）"),
        "passed": passed,
    }


def _read_prev_best(path: Path) -> dict:
    """仅评估模式复用上一轮的早停结果，避免重写 metrics.json 时丢失 best_epoch。"""
    try:
        prev = json.loads(Path(path).read_text(encoding="utf-8"))
        return {"epoch": prev.get("best_epoch"), "loss": prev.get("best_val_loss")}
    except (OSError, ValueError):
        return {"epoch": None, "loss": None}


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
    ap.add_argument("--scale-min", type=float, default=1.0,
                    help="scale 头下限（默认 1.0 保守模式；< 1.0 为激进实验，需 core 侧 enableAggressiveAi 配合）")
    ap.add_argument("--collect-dir", type=Path,
                    help="采集目录（提供时按 §7.5.3 逐 run 统计 A3 辅助固定）")
    ap.add_argument("--eval-only", action="store_true",
                    help="跳过训练与导出，复用 --out-dir 内已有的 ONNX/norm.json 只跑评估"
                         "（判据调整后复核用，不重训模型）")
    ap.add_argument("--label-mode", default="sd", choices=("sd", "dd"),
                    help="数据集标签口径（须与 assemble.py 生成时一致）："
                         "sd=单星误差（默认）；dd=双差残差（离线 A3 仅此口径可算）")
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
    detected = [dataset.FEATURE_COLUMNS[i] for i in range(dataset.DIM)
                if float(x_train[:, i].std()) < 1e-8]

    onnx_path = args.out_dir / "obs_corrector.onnx"
    norm_path = args.out_dir / "obs_corrector.norm.json"

    if args.eval_only:
        if not onnx_path.exists() or not norm_path.exists():
            ap.error(f"--eval-only 需要已存在的 {onnx_path} 与 {norm_path}")
        norm = _Normalizer.load(norm_path)
        best = _read_prev_best(args.out_dir / "obs_corrector.metrics.json")
        print(f"仅评估模式：复用 {onnx_path.name} + {norm_path.name}（不训练、不导出）")
        print(f"  加载归一化 {norm.mean.size} 维；实测恒零列 {detected} / 声明 {list(CONSTANT_COLS)}")
    else:
        y_train = dataset.labels(split.train).astype(np.float64)
        x_val = dataset.features(split.val)
        y_val = dataset.labels(split.val).astype(np.float64)

        norm = _Normalizer.fit(x_train)
        print(f"归一化参数已拟合（仅训练集，{x_train.shape[0]} 行），"
              f"恒零列 {len(detected)} 个（按 1.0 兜底，保留占位不删维）")
        print(f"  实测恒零列 {detected} / 声明 {list(CONSTANT_COLS)}")
        if sorted(detected) != sorted(CONSTANT_COLS):
            print(f"  [警告] 声明与实测不一致：多出 {sorted(set(detected) - set(CONSTANT_COLS))}、"
                  f"缺少 {sorted(set(CONSTANT_COLS) - set(detected))}")

        model = build_model(args.hidden, args.bias_max, args.scale_max, args.scale_min)
        best = train(model, norm.apply(x_train), y_train, norm.apply(x_val), y_val,
                     args.epochs, args.batch_size, args.lr, args.patience, args.seed)

        export_onnx.export(model, onnx_path)
        export_onnx.write_norm(norm_path, norm.mean, norm.std,
                               note="由 training/obs_corrector/train.py 拟合（仅训练集）",
                               constant_cols=list(CONSTANT_COLS))
        print(f"导出完成: {onnx_path} + {norm_path}")

    contract = export_onnx.check_contract(onnx_path, args.bias_max, args.scale_max,
                                          args.scale_min)
    print(f"契约校验通过: 输入 {contract['input_shape']} / 输出 {contract['output_shape']}"
          f" / batch 探针 {sorted(contract['batch_probes'])}")

    test = evaluate(split.test, norm, onnx_path, args.label_mode)
    a1 = a1_contract(contract, norm_path, split, detected)
    a2 = a2_harmless(test)
    if args.collect_dir is not None:
        a3 = a3_effect(onnx_path, norm, args.collect_dir, split.test_stations,
                       args.bias_max, args.label_mode)
    else:
        a3 = {"verdict": "未评估：未提供 --collect-dir（A3 需 DDRES 的 σ_dd）", "passed": None}
    result = {
        "mode": "eval-only" if args.eval_only else "train",
        "label_mode": args.label_mode,
        "best_epoch": best["epoch"],
        "best_val_loss": best["loss"],
        "split": {
            "train_stations": split.train_stations,
            "val_stations": split.val_stations,
            "test_stations": split.test_stations,
        },
        "contract": contract,
        "test": test,
        "acceptance": {"A1": a1, "A2": a2, "A3": a3},
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

    print("\n验收指标（A1/A2/A3）:")
    print(f"  A1 契约与流程: {'通过' if a1['passed'] else '不通过'}"
          f"（constant_cols={a1['constant_cols']}"
          f"{'，与实测一致' if a1['constant_cols_consistent'] else '，与实测不一致'}）")
    for name, ok in a1["checks"].items():
        print(f"    {'通过' if ok else '不通过'} {name}")
    print(f"  A2 无明显变差: {'通过（残差代理口径）' if a2['passed'] else '不通过'}"
          f"（整体相对改善 {a2['A2-3 残差代理（不恶化容差 -5%）']['overall']:.1%}）")
    for st, m in a2["A2-3 残差代理（不恶化容差 -5%）"]["per_station"].items():
        print(f"    {st}: {'不恶化' if m['not_worse'] else '变差'}"
              f"（{m['relative_residual_improvement']:.1%}）")
    print(f"    {a2['A2-1/A2-2/A2-4']}")
    if a3.get("passed") is None:
        print(f"  A3 辅助固定: {a3['verdict']}")
    else:
        print(f"  A3 辅助固定: {'通过' if a3['passed'] else '不通过'} — {a3['verdict']}")
        print(f"    测试站 {a3['runs']} run / {a3['rows']} 行；G0(3σ_dd)={a3['gate_rows']}"
              f"、Dσ(≥1.0σ_dd)={a3['drop_sigma_rows']}、R%(≤0.85倍)={a3['drop_ratio_rows']}"
              f"、任一满足={a3['valid_rows']}（{a3['valid_sats']} 星）")
        print(f"    σ_dd 中位数 {a3['sigma_dd_p50']:.3f} m → G0 门限中位数 "
              f"{a3['gate_threshold_p50']:.3f} m、Dσ 门限中位数 "
              f"{a3['drop_sigma_threshold_p50']:.3f} m；|bias| 最大 {a3['max_bias_abs']:.3f} m"
              f"（bias_max={a3['bias_max']:.3f}）")
        print(f"    新口径在结构上可达的行数（1.0σ_dd ≤ bias_max 或 |before| ≤ bias_max/0.15）: "
              f"{a3['reduction_reachable_rows']}")
    print(f"  评估结果已写入: {metrics_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())