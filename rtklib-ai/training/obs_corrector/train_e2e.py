"""方案 B：端到端坐标 Loss 训练（``train_e2e.py``）。

架构完全不变（SPI + 外挂）：输入 17 维特征、输出 ``[batch, 2]``（``biasMeters`` /
``varianceScale``），导出 ``obs_corrector_e2e.onnx`` + ``obs_corrector_e2e.norm.json``，
Java 侧只换模型文件即可与方案 A / C / D 组做同历元配对。

与 :mod:`training.obs_corrector.train`（方案 A，人造单差标签 + 高斯 NLL）的区别**只有
Loss 一项**：这里不造标签，而是用 :func:`training.obs_corrector.e2e_wls.wls_position`
把 bias/scale 一路可导地算到坐标，直接最小化「解算坐标与真值的平方误差」。

Loss 的三项组成::

    L = H/L0_h + V/L0_v + λ_b·mean(β²) + λ_s·mean((scale-1)²)

- ``H`` / ``V``：每个 WLS 分组解算坐标的水平（2D）与垂直误差平方和；
- ``L0_h`` / ``L0_v``：同一分组在「零改正 + 单位方差」下的 ``H`` / ``V``（训练集上
  一次性算出）。**两者各自归一化**：裸用 3 维平方和时，垂直误差（~6.9 m）量级远大于
  水平（~3.8 m），梯度会被垂直项独占，模型被训练成偏重垂直，单个早停站的垂直回归
  就能主导早停决策。分别归一化后两项起点都是 1.0，模型对水平/垂直同等负责；
- ``λ_b``：``β`` 的 L2。**这一项不是装饰**：坐标 Loss 只对 ``H`` 的列空间敏感，
  ``β`` 沿 ``H`` 零空间的分量对 Loss 完全没有梯度，没有这一项模型会在这个方向上
  自由漂移（输出幅度虚高而坐标不变，等于把有效改正量让给了噪声）；
- ``λ_s``：``scale`` 的 L2。同理，方差整体等比缩放不改变 WLS 解，
  没有这一项 scale 会漂到任意值而不受惩罚。

用法（在 ``rtklib-ai`` 目录下执行）::

    python -m training.obs_corrector.train_e2e --diag-only
    python -m training.obs_corrector.train_e2e --epochs 30
"""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

import numpy as np

try:
    from training.common import export_onnx
    from training.obs_corrector import e2e_wls
    from training.obs_corrector import train as a_train
except ImportError:  # 允许直接以脚本方式运行
    sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
    from training.common import export_onnx
    from training.obs_corrector import e2e_wls
    from training.obs_corrector import train as a_train

DEFAULT_COLLECT_DIR = Path("../data/fix-validator/collect-obs-hourly-4d")
DEFAULT_SOL_CSV = Path("../data/fix-validator/pairing/pairing.A.sol.csv")
DEFAULT_TRUTH_CSV = Path("../data/fix-validator/truth.csv")


class _Packed:
    """把装配结果一次性搬到 torch 侧，训练/评估按 run 切片取用（避免每轮重复转换）。"""

    def __init__(self, pack: dict, norm: a_train._Normalizer):
        import torch

        self.meta = pack["runs"]
        self.x = torch.from_numpy(pack["x"])
        self.x_mean = torch.from_numpy(norm.mean.reshape(1, -1))
        self.x_std = torch.from_numpy(norm.std.reshape(1, -1))
        self.h = torch.from_numpy(pack["h"])
        self.r = torch.from_numpy(pack["r"])
        self.ri = torch.from_numpy(pack["ri"])
        self.rj = torch.from_numpy(pack["rj"])
        self.row_self = torch.from_numpy(pack["row_self"])
        self.row_ref = torch.from_numpy(pack["row_ref"])
        self.group = torch.from_numpy(pack["group"])
        self.epoch_key = torch.from_numpy(pack["epoch_key"])

    def rows(self, rid: int) -> slice:
        """单个 run 在行方向上的切片（装配时按 run 顺序追加，故必然连续）。"""
        return slice(self.meta[rid]["row_start"], self.meta[rid]["row_stop"])

    def features(self, rid: int):
        m = self.meta[rid]
        return self.x[m["x_start"]:m["x_stop"]]


def _remap_group(key):
    """把分组键重映射到 ``0..G-1``（``index_add`` 要求目标下标从 0 起密集）。"""
    import torch

    uniq, inv = torch.unique(key, return_inverse=True)
    return inv, int(uniq.numel())


def _coord(model, packed: _Packed, rid: int, group_mode: str,
           zero_correction: bool = False):
    """单个 run 的可导坐标解：返回 ``(delta[G,3], β 或 None, scale 或 None)``。

    ``zero_correction=True`` 时强制 ``β=0, scale=1``，用于算 ``L0`` 与「改正前」基线。
    """
    sl = packed.rows(rid)
    key = packed.group[sl] if group_mode == "run" else packed.epoch_key[sl]
    gid, gcount = _remap_group(key)
    if zero_correction:
        w = 1.0 / (packed.ri[sl] + packed.rj[sl])
        return e2e_wls.wls_position(packed.h[sl], w, packed.r[sl], gid, gcount), None, None

    xn = (packed.features(rid) - packed.x_mean) / packed.x_std
    out = model(xn)
    x_off = packed.meta[rid]["x_start"]
    i_self = packed.row_self[sl] - x_off
    i_ref = packed.row_ref[sl] - x_off
    bias = out[:, 0]
    scale = out[:, 1]
    beta = bias[i_ref] - bias[i_self]
    var = (packed.ri[sl] * scale[i_ref] + packed.rj[sl] * scale[i_self]).clamp_min(1e-6)
    delta = e2e_wls.wls_position(packed.h[sl], 1.0 / var, packed.r[sl] - beta, gid, gcount)
    return delta, beta, scale


def _batch_loss(model, packed: _Packed, run_ids: list[int], group_mode: str,
                l0: tuple[float, float], lam_b: float, lam_s: float) -> tuple:
    """一个 run 批次的 Loss：``H/L0_h + V/L0_v`` 为主项，两个 L2 为正则。

    逐 run 前向再累加梯度（而不是把整批拼成一个张量）：每个 run 的行/特征都是连续
    切片，逐 run 处理既不会跨 run 串行，也能让内存占用与 ``--batch-runs`` 解耦。
    """
    import torch

    coords, betas, scales = [], [], []
    for rid in run_ids:
        d, b, s = _coord(model, packed, rid, group_mode)
        coords.append(d)
        betas.append(b)
        scales.append(s)
    d = torch.cat(coords, dim=0)
    h_norm = (d[:, 0].pow(2) + d[:, 1].pow(2)).mean() / l0[0]
    v_norm = d[:, 2].pow(2).mean() / l0[1]
    coord_loss = h_norm + v_norm
    beta_mean = torch.cat(betas).pow(2).mean()
    scale_mean = (torch.cat(scales) - 1.0).pow(2).mean()
    return coord_loss, coord_loss + lam_b * beta_mean + lam_s * scale_mean, (h_norm, v_norm)


def _l0(packed: _Packed, run_ids: list[int], group_mode: str) -> tuple[float, float]:
    """零改正 + 单位方差下的 ``(H, V)`` 均方误差（Loss 各自的归一化基准）。"""
    import torch

    h_total, v_total, count = 0.0, 0.0, 0
    with torch.no_grad():
        for rid in run_ids:
            d = _coord(None, packed, rid, group_mode, True)[0]
            h_total += float((d[:, 0].pow(2) + d[:, 1].pow(2)).sum())
            v_total += float(d[:, 2].pow(2).sum())
            count += int(d.shape[0])
    return h_total / max(count, 1), v_total / max(count, 1)


def _evaluate(model, packed: _Packed, run_ids: list[int], group_mode: str,
              l0: tuple[float, float]) -> dict:
    """评估：坐标 Loss（H/V 各自归一化）、改正前后逐组 ENU 误差的 H/V RMS、模型输出幅度。"""
    import torch

    enu_before, enu_after = [], []
    betas, scales = [], []
    model.eval()
    with torch.no_grad():
        for rid in run_ids:
            d0 = _coord(None, packed, rid, group_mode, True)[0]
            d1, b, s = _coord(model, packed, rid, group_mode)
            enu_before.append(d0.numpy())
            enu_after.append(d1.numpy())
            betas.append(b.numpy())
            scales.append(s.numpy())
    eb = np.concatenate(enu_before).astype(np.float64)
    ea = np.concatenate(enu_after).astype(np.float64)

    def _hv(enu: np.ndarray) -> dict:
        h = np.sqrt(enu[:, 0] ** 2 + enu[:, 1] ** 2)
        v = np.abs(enu[:, 2])
        return {"h_rms_mm": float(np.sqrt(np.mean(h ** 2)) * 1000.0),
                "v_rms_mm": float(np.sqrt(np.mean(v ** 2)) * 1000.0)}

    def _norm(enu: np.ndarray) -> tuple[float, float]:
        return (float(np.mean(enu[:, 0] ** 2 + enu[:, 1] ** 2)) / l0[0],
                float(np.mean(enu[:, 2] ** 2)) / l0[1])

    nb, na = _norm(eb), _norm(ea)
    norm_before, norm_after = nb[0] + nb[1], na[0] + na[1]
    b = np.concatenate(betas)
    s = np.concatenate(scales)
    return {
        "n_groups": int(eb.shape[0]),
        "group_mode": group_mode,
        "coord_mean_m2": {"before": float(np.mean(np.sum(eb ** 2, axis=1))),
                          "after": float(np.mean(np.sum(ea ** 2, axis=1)))},
        "coord_loss_normalized": {"before": norm_before, "after": norm_after},
        "coord_loss_components": {
            "before": {"h": nb[0], "v": nb[1]},
            "after": {"h": na[0], "v": na[1]},
        },
        "relative_coord_improvement": float(1.0 - norm_after / norm_before)
                                       if norm_before > 0 else 0.0,
        "coordinate_error": {"before": _hv(eb), "after": _hv(ea)},
        "model_output": {
            "bias_abs_mean_m": float(np.mean(np.abs(b))),
            "bias_abs_p95_m": float(np.quantile(np.abs(b), 0.95)),
            "bias_abs_max_m": float(np.max(np.abs(b))),
            "scale_mean": float(np.mean(s)),
            "scale_p05": float(np.quantile(s, 0.05)),
            "scale_p95": float(np.quantile(s, 0.95)),
            "scale_frac_at_floor": float(np.mean(s <= 1.001)),
        },
    }


def _baseline_deltas(pack: dict, run_ids: list[int], limit: int = 64):
    """用 numpy WLS 复核参考系换算，返回 ``(WLS(r), WLS(before), WLS(h·dx), 逐行相关系数)``。

    校验逻辑（都是非平凡判据，不是恒等式）：

    1. ``WLS(before) - WLS(r)`` 必须等于 ``WLS(h·dx)``——这是换算的**逐行**代数后果，
       用 ``dx`` 的 run 均值去比会误判（权重把逐行投影回归成一个完全不同的向量）；
    2. ``corr(h·dx, before) > 0``：投影与残差同向，说明 ``r = before - h·dx`` 扣的是
       真值参考系下的位置误差，而不是写反了符号（写反时相关系数会显著为负）；
    3. ``mean|r| ≤ mean|before|``：扣掉位置误差投影后残差幅度不应变大。

    注意 ``WLS(r)`` 本身**不会**接近 0：这里的码残差含 ~2.5 m 的多路径，而配对解算
    走的是载波 RTK，多路径没被位置解吸收，因此它留在残差里正是模型要学的 bias。
    """
    dr, db, dp, cors = [], [], [], []
    for rid in run_ids[:limit]:
        m = pack["runs"][rid]
        sl = slice(m["row_start"], m["row_stop"])
        h = pack["h"][sl].astype(np.float64)
        w = 1.0 / (pack["ri"][sl] + pack["rj"][sl]).astype(np.float64)
        g = np.zeros(h.shape[0], dtype=np.int64)
        before = pack["before"][sl].astype(np.float64)
        res = pack["r"][sl].astype(np.float64)
        proj = np.einsum("ij,ij->i", h, pack["dxr"][sl].astype(np.float64))
        dr.append(e2e_wls.wls_position_np(h, w, res, g, 1)[0])
        db.append(e2e_wls.wls_position_np(h, w, before, g, 1)[0])
        dp.append(e2e_wls.wls_position_np(h, w, proj, g, 1)[0])
        cors.append(float(np.corrcoef(proj, before)[0, 1])
                    if proj.std() > 1e-9 and before.std() > 1e-9 else 0.0)
    return np.asarray(dr), np.asarray(db), np.asarray(dp), np.asarray(cors)


def _hv_rms_mm(d: np.ndarray) -> tuple[float, float]:
    h = np.sqrt(d[:, 0] ** 2 + d[:, 1] ** 2)
    v = np.abs(d[:, 2])
    return float(np.sqrt(np.mean(h ** 2)) * 1000.0), float(np.sqrt(np.mean(v ** 2)) * 1000.0)


def main(argv=None) -> int:
    ap = argparse.ArgumentParser(description="接口三方案 B：端到端坐标 Loss 训练")
    ap.add_argument("--collect-dir", type=Path, default=DEFAULT_COLLECT_DIR)
    ap.add_argument("--sol-csv", type=Path, default=DEFAULT_SOL_CSV,
                    help="A 组配对解算结果（提供 x_pred 与兜底真值参考系）")
    ap.add_argument("--truth-csv", type=Path, default=DEFAULT_TRUTH_CSV)
    ap.add_argument("--out-dir", type=Path, default=Path("training/out"))
    ap.add_argument("--epochs", type=int, default=30)
    ap.add_argument("--batch-runs", type=int, default=32, help="每个优化步的 run 数")
    ap.add_argument("--lr", type=float, default=1e-3)
    ap.add_argument("--hidden", type=int, default=32)
    ap.add_argument("--patience", type=int, default=6)
    ap.add_argument("--seed", type=int, default=20261107)
    ap.add_argument("--bias-max", type=float, default=2.0)
    ap.add_argument("--scale-max", type=float, default=3.0,
                    help="varianceScale 上界（默认 3.0：收紧以抑制对差站的过度降权）")
    ap.add_argument("--scale-min", type=float, default=1.0,
                    help="varianceScale 下界（默认 1.0，单侧膨胀）；小于 1.0 时对应 core 的"
                         "激进模式（AiOpts.enableAggressiveAi + aggressiveMinScale），"
                         "需在配对侧同时传 -Dpairing.aggressive=true -Dpairing.min-scale 相同值")
    ap.add_argument("--group", dest="group_mode", default="run", choices=("run", "epoch"),
                    help="WLS 分组：run=整段静态解（默认，信号不被单历元噪声淹没）；"
                         "epoch=逐历元单步（与 TDL-GNSS 写法一致，作对照）")
    ap.add_argument("--lambda-bias", type=float, default=0.01, help="β 的 L2 系数（H 零空间锚定）")
    ap.add_argument("--lambda-scale", type=float, default=0.01, help="(scale-1)² 的 L2 系数")
    ap.add_argument("--n-test-stations", type=int, default=2)
    ap.add_argument("--n-val-stations", type=int, default=1)
    ap.add_argument("--diag-only", action="store_true", help="只装配 + 打印数据诊断，不训练")
    args = ap.parse_args(argv)

    args.out_dir.mkdir(parents=True, exist_ok=True)

    print("装配端到端样本（DDRES 行 + 参考星复原 + 真值参考残差）...")
    pack = e2e_wls.build_dataset(args.collect_dir, args.sol_csv, args.truth_csv)
    runs = e2e_wls.split_runs(pack["runs"], seed=args.seed,
                              n_test_stations=args.n_test_stations,
                              n_val_stations=args.n_val_stations)
    by_split = {k: [i for i, r in enumerate(runs) if r["split"] == k]
                for k in ("train", "val", "test")}
    n_rows = pack["h"].shape[0]
    print(f"  可用 run {len(runs)} / 观测行 {pack['x'].shape[0]} / DDRES 行 {n_rows}")
    print(f"  参考星复原：候选数>1 的 (epoch,frq) {pack['diag']['n_ref_multi']} 个，"
          f"退化为「有效集合最高高度角」{pack['diag']['n_ref_fallback']} 个，"
          f"整 run 丢弃 {pack['diag']['n_skipped']} 个")
    for k, ids in by_split.items():
        sts = sorted({runs[i]["station"] for i in ids})
        days = sorted({(runs[i]["station"], runs[i]["day"]) for i in ids})
        print(f"  {k:5s}: {len(ids)} run / 站 {sts} / 站-日 {len(days)} 组")

    r_abs = float(np.mean(np.abs(pack["r"])))
    b_abs = float(np.mean(np.abs(pack["before"])))
    print(f"  残差幅度：|before| {b_abs:.3f} m → |r| {r_abs:.3f} m（r 已换到真值参考系）")

    # 参考系换算的三条校验（详见 ``_baseline_deltas`` 文档）。逐行比较而非比 run 均值：
    # WLS(before) - WLS(r) = WLS(h·dx)，是逐行投影的加权回归，不等于 dx 的均值。
    dr, db, dp, cors = _baseline_deltas(pack, by_split["train"])
    dh_e, dv_e = _hv_rms_mm((db - dr) - dp)
    print(f"  参考系校验（{len(dr)} 个训练 run）：")
    print(f"    ① 代数一致性 WLS(before)-WLS(r) vs WLS(h·dx)：偏差 H {dh_e:.2f} / V {dv_e:.2f} mm")
    print(f"    ② 投影相关性 corr(h·dx, before)：均值 {cors.mean():.3f}"
          f"（负相关 {int((cors < 0).sum())}/{len(cors)} 个 run）")
    print(f"    ③ 残差幅度 mean|before| {b_abs:.3f} m → mean|r| {r_abs:.3f} m")
    ok = dh_e < 1.0 and dv_e < 1.0 and cors.mean() > 0 and r_abs <= b_abs * 1.05
    if ok:
        print("  → 换算校验通过：r 已换到真值参考系，符号与历元配对一致")
    else:
        print("  [警告] 换算校验未通过：检查 x_pred 的历元配对（searchsorted 取上一历元）与 dx 符号")

    if args.diag_only:
        return 0

    train_rows = np.concatenate(
        [pack["x"][pack["runs"][i]["x_start"]:pack["runs"][i]["x_stop"]]
         for i in by_split["train"]], axis=0)
    norm = a_train._Normalizer.fit(train_rows)
    print(f"  归一化已拟合（仅训练集 {train_rows.shape[0]} 行，不含验证/测试站）")

    packed = _Packed(pack, norm)
    l0_train = _l0(packed, by_split["train"], args.group_mode)
    l0_val = _l0(packed, by_split["val"], args.group_mode)
    print(f"  L0（零改正基线，H/V 各自归一化）：train H {np.sqrt(l0_train[0]) * 1000:.1f}"
          f" / V {np.sqrt(l0_train[1]) * 1000:.1f} mm；"
          f"val H {np.sqrt(l0_val[0]) * 1000:.1f} / V {np.sqrt(l0_val[1]) * 1000:.1f} mm")

    import torch

    torch.manual_seed(args.seed)
    model = a_train.build_model(args.hidden, args.bias_max, args.scale_max, args.scale_min)
    opt = torch.optim.Adam(model.parameters(), lr=args.lr)
    best = {"loss": float("inf"), "state": None, "epoch": -1}
    bad = 0
    rng = np.random.default_rng(args.seed)

    for epoch in range(args.epochs):
        model.train()
        order = rng.permutation(by_split["train"])
        total, steps = 0.0, 0
        for i in range(0, len(order), args.batch_runs):
            chunk = [int(v) for v in order[i:i + args.batch_runs]]
            opt.zero_grad()
            coord_loss, loss, _ = _batch_loss(model, packed, chunk, args.group_mode,
                                              l0_train, args.lambda_bias, args.lambda_scale)
            loss.backward()
            opt.step()
            total += float(loss.detach()) * len(chunk)
            steps += len(chunk)
        model.eval()
        with torch.no_grad():
            val = [float(_batch_loss(model, packed, [int(v)], args.group_mode,
                                     l0_train, args.lambda_bias, args.lambda_scale)[1])
                   for v in by_split["val"]]
        val_loss = float(np.mean(val)) if val else float("inf")
        train_loss = total / max(steps, 1)
        print(f"  epoch {epoch + 1:3d}/{args.epochs}: train={train_loss:.5f} val={val_loss:.5f}")
        if val_loss < best["loss"] - 1e-5:
            best = {"loss": val_loss, "epoch": epoch + 1,
                    "state": {k: v.clone() for k, v in model.state_dict().items()}}
            bad = 0
        else:
            bad += 1
            if bad >= args.patience:
                print(f"  早停：验证 Loss 连续 {args.patience} 轮未改善（最佳 epoch={best['epoch']}）")
                break
    if best["state"] is not None:
        model.load_state_dict(best["state"])
    model.eval()

    onnx_path = args.out_dir / "obs_corrector_e2e.onnx"
    norm_path = args.out_dir / "obs_corrector_e2e.norm.json"
    export_onnx.export(model, onnx_path)
    export_onnx.write_norm(norm_path, norm.mean, norm.std,
                           note="training/obs_corrector/train_e2e.py（方案 B：坐标 Loss，仅训练集拟合）",
                           constant_cols=list(a_train.CONSTANT_COLS))
    contract = export_onnx.check_contract(onnx_path, args.bias_max, args.scale_max,
                                          args.scale_min)
    print(f"导出完成: {onnx_path.name} + {norm_path.name}"
          f"（输入 {contract['input_shape']} / 输出 {contract['output_shape']}）")

    evals = {}
    for k in ("val", "test"):
        evals[k] = _evaluate(model, packed, by_split[k], args.group_mode, l0_train)
        e = evals[k]
        print(f"\n{k} 组（{e['n_groups']} 个 WLS 分组，{e['group_mode']} 口径）:")
        print(f"  坐标 Loss（H/V 各自归一化）: {e['coord_loss_normalized']['before']:.4f}"
              f" → {e['coord_loss_normalized']['after']:.4f}"
              f"（相对改善 {e['relative_coord_improvement']:.1%}）")
        c = e["coord_loss_components"]
        print(f"    分量 H {c['before']['h']:.4f} → {c['after']['h']:.4f}；"
              f"V {c['before']['v']:.4f} → {c['after']['v']:.4f}")
        print(f"  坐标误差 RMS: H {e['coordinate_error']['before']['h_rms_mm']:.1f} mm"
              f" → {e['coordinate_error']['after']['h_rms_mm']:.1f} mm；"
              f"V {e['coordinate_error']['before']['v_rms_mm']:.1f} mm"
              f" → {e['coordinate_error']['after']['v_rms_mm']:.1f} mm")
        if "model_output" in e:
            m = e["model_output"]
            print(f"  模型输出: |bias| 均值 {m['bias_abs_mean_m']:.3f} m"
                  f"（p95 {m['bias_abs_p95_m']:.3f} / max {m['bias_abs_max_m']:.3f}），"
                  f"scale 均值 {m['scale_mean']:.3f}"
                  f"（p05 {m['scale_p05']:.3f} / p95 {m['scale_p95']:.3f}，"
                  f"贴地板比例 {m['scale_frac_at_floor']:.1%}）")

    result = {
        "scheme": "B-end-to-end-coordinate-loss",
        "group_mode": args.group_mode,
        "best_epoch": best["epoch"],
        "best_val_loss": best["loss"],
        # ``split`` 必须与方案 A 的 metrics 同构（a2_pairing 直接读这三个扁平键）；
        # 训练站内验证日会被同时算进 train 与 val，故 val_stations 取差集避免重复归属。
        "split": {
            "train_stations": sorted({runs[i]["station"] for i in by_split["train"]}),
            "val_stations": sorted({runs[i]["station"] for i in by_split["val"]}
                                   - {runs[i]["station"] for i in by_split["train"]}),
            "test_stations": sorted({runs[i]["station"] for i in by_split["test"]}),
        },
        "split_runs": {k: len(v) for k, v in by_split.items()},
        "data": {
            "n_obs_rows": int(pack["x"].shape[0]),
            "n_ddres_rows": int(n_rows),
            "n_runs_used": len(runs),
            "ref_reconstruction": pack["diag"],
            "mean_abs_before_m": b_abs,
            "mean_abs_truth_referenced_m": r_abs,
        },
        "l0": {"train": {"h": l0_train[0], "v": l0_train[1]},
               "val": {"h": l0_val[0], "v": l0_val[1]}},
        "hyper": {"lambda_bias": args.lambda_bias, "lambda_scale": args.lambda_scale,
                  "bias_max": args.bias_max, "scale_max": args.scale_max,
                  "scale_min": args.scale_min,
                  "hidden": args.hidden, "lr": args.lr, "batch_runs": args.batch_runs},
        "eval": evals,
        "contract": contract,
        "note": "坐标误差是本脚本用 DDRES 码残差做的静态码解算代理，"
                "与 Java 侧载波 RTK 的 H/V RMS 不是同一口径；两者的可比结论必须以"
                "ObsCorrectorPairingTest 的同历元配对结果为准",
    }
    metrics_path = args.out_dir / "obs_corrector_e2e.metrics.json"
    metrics_path.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"\n指标已写入 {metrics_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())