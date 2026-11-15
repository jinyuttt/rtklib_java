"""ONNX 导出与契约校验（设计文档 §5.3「批量约束」/ §4.3.4 输出契约）。

导出后必须自证三件事，否则 Java 侧只能靠运行时降级兜底：

1. 输入静态 17 维、输出静态 2 维（``biasMeters`` / ``varianceScale`` 成对）；
2. batch 维是**动态**的（每历元一次批量调用 ``ns × nf`` 行）；
3. 输出天然落在 core 钳位区间内（``|bias| ≤ biasMax``、``scale ∈ [1, scaleMax]``），
   避免「模型输出被 core 钳掉」这种静默失真。

> **同步说明（暂存副本）**：本文件是 ``rtklib-ai/training/common/export_onnx.py``
> 的暂存副本，与线上版本**逐字节相同**，仅在文件末尾追加
> ``check_contract_scale``（接口 B 契约校验）。同步时只需追加该函数，
> 不要用整份文件覆盖（以免抹掉其它分支对 export/check_contract 的改动）。
"""

from __future__ import annotations

import json
from pathlib import Path

import numpy as np

DIM = 17
OUT_DIM = 2


def write_norm(path: Path, mean: np.ndarray, std: np.ndarray, note: str | None = None,
               dim: int = DIM, constant_cols: "list[str] | None" = None) -> None:
    """写 ``norm.json``：Java 侧 ``FeatureNorm`` 只读 ``mean`` / ``std`` 两个数组。

    ``dim`` 必须与对应接口的特征维一致（接口三 17、接口一 6），否则 Java 侧
    ``FeatureNorm.load(path, dim)`` 会直接抛错。

    ``constant_cols`` 标注训练集上方差恒为 0 的结构性死列（接口三的 f2/f3/f8）。
    这些列**保留占位**（不删维，输入维恒为 ``dim``），但其 ``std`` 按 1.0 兜底，
    归一化后恒等于 0——下游据此知道该维不携带信息，可跳过解读。
    Java 侧 ``FeatureNorm`` 解析器允许额外字段，故该键对推理无影响。
    """
    mean = np.asarray(mean, dtype=np.float64).reshape(-1)
    std = np.asarray(std, dtype=np.float64).reshape(-1)
    if mean.size != dim or std.size != dim:
        raise ValueError(f"归一化参数长度必须为 {dim}，实际 mean={mean.size} std={std.size}")
    payload = {"mean": [float(v) for v in mean], "std": [float(v) for v in std]}
    if constant_cols:
        payload["constant_cols"] = [str(c) for c in constant_cols]
    if note:
        payload["note"] = note
    path.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")


def export(model, onnx_path: Path, opset: int = 17, dim: int = DIM) -> Path:
    """把训练好的 ``torch.nn.Module`` 导出为 ONNX（动态 batch）。"""
    import torch

    onnx_path.parent.mkdir(parents=True, exist_ok=True)
    model.eval()
    dummy = torch.zeros(1, dim, dtype=torch.float32)
    torch.onnx.export(
        model,
        dummy,
        str(onnx_path),
        input_names=["input"],
        output_names=["output"],
        dynamic_axes={"input": {0: "batch"}, "output": {0: "batch"}},
        opset_version=opset,
        do_constant_folding=True,
        dynamo=False,
    )
    return onnx_path


def check_contract(onnx_path: Path, bias_max: float = 10.0, scale_max: float = 4.0,
                   scale_min: float = 1.0,
                   probes: tuple[int, ...] = (1, 7, 64), dim: int = DIM,
                   out_dim: int = OUT_DIM) -> dict:
    """用 onnxruntime 实测契约：形状、动态 batch、输出范围。

    ``scale_min`` 与训练侧 ``--scale-min`` 保持一致：默认 1.0（单侧膨胀，§4.3.4 规则 1）；
    小于 1.0 时对应 core 的激进模式（``AiOpts.enableAggressiveAi`` + ``aggressiveMinScale``）。
    """
    import onnxruntime as ort

    sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    inp = sess.get_inputs()[0]
    out = sess.get_outputs()[0]
    if len(inp.shape) != 2 or inp.shape[1] != dim:
        raise ValueError(f"输入必须是 [batch, {dim}]，实际 {inp.shape}")
    if len(out.shape) != 2 or out.shape[1] != out_dim:
        raise ValueError(f"输出必须是 [batch, {out_dim}]，实际 {out.shape}")
    if not (isinstance(inp.shape[0], str) or inp.shape[0] < 0):
        raise ValueError(f"batch 维必须是动态的（每历元批量推理），实际 {inp.shape}")

    rng = np.random.default_rng(7)
    report = {"input_shape": list(inp.shape), "output_shape": list(out.shape), "batch_probes": {}}
    for rows in probes:
        x = rng.normal(0.0, 2.0, size=(rows, dim)).astype(np.float32)
        y = sess.run(None, {inp.name: x})[0]
        if y.shape != (rows, out_dim):
            raise ValueError(f"batch={rows} 时输出形状 {y.shape} 不符合契约")
        if not np.all(np.isfinite(y)):
            raise ValueError(f"batch={rows} 时输出含非有限值")
        bias, scale = y[:, 0], y[:, 1]
        if np.max(np.abs(bias)) > bias_max + 1e-6:
            raise ValueError(f"bias 超出 core 钳位上限 {bias_max} m: max|bias|={np.max(np.abs(bias))}")
        if np.min(scale) < scale_min - 1e-6 or np.max(scale) > scale_max + 1e-6:
            raise ValueError(f"scale 超出 [{scale_min}, {scale_max}]: "
                             f"[{np.min(scale)}, {np.max(scale)}]")
        report["batch_probes"][rows] = {
            "max_abs_bias": float(np.max(np.abs(bias))),
            "scale_range": [float(np.min(scale)), float(np.max(scale))],
        }
    return report


def check_contract_confidence(onnx_path: Path, probes: tuple[int, ...] = (1, 7, 64),
                              dim: int = 6) -> dict:
    """接口一契约：输入 ``[batch, dim]``、输出 ``[batch, 1]``、batch 动态、置信度在 ``[0, 1]``。

    接口一只有一个输出（置信度），Java 侧 ``FixValidatorImpl`` 还会再做一次
    ``[0,1]`` 钳位；此处提前校验，避免「训练时越界、推理时被钳掉」的静默失真。
    """
    import onnxruntime as ort

    sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    inp = sess.get_inputs()[0]
    out = sess.get_outputs()[0]
    if len(inp.shape) != 2 or inp.shape[1] != dim:
        raise ValueError(f"输入必须是 [batch, {dim}]，实际 {inp.shape}")
    if len(out.shape) != 2 or out.shape[1] != 1:
        raise ValueError(f"输出必须是 [batch, 1]（单置信度），实际 {out.shape}")
    if not (isinstance(inp.shape[0], str) or inp.shape[0] < 0):
        raise ValueError(f"batch 维必须是动态的（每历元单行推理），实际 {inp.shape}")

    rng = np.random.default_rng(7)
    report = {"input_shape": list(inp.shape), "output_shape": list(out.shape), "batch_probes": {}}
    for rows in probes:
        x = rng.normal(0.0, 2.0, size=(rows, dim)).astype(np.float32)
        y = sess.run(None, {inp.name: x})[0]
        if y.shape != (rows, 1):
            raise ValueError(f"batch={rows} 时输出形状 {y.shape} 不符合契约")
        if not np.all(np.isfinite(y)):
            raise ValueError(f"batch={rows} 时输出含非有限值")
        if np.min(y) < -1e-6 or np.max(y) > 1.0 + 1e-6:
            raise ValueError(f"置信度超出 [0, 1]: [{np.min(y)}, {np.max(y)}]")
        report["batch_probes"][rows] = {
            "confidence_range": [float(np.min(y)), float(np.max(y))],
        }
    return report


def check_contract_scale(onnx_path: Path, dim: int = 12, scale_min: float = 1.0,
                         scale_max: float = 4.0, probes: tuple[int, ...] = (1, 7, 64)) -> dict:
    """接口 B 契约：输入 ``[batch, dim]``、输出 ``[batch, 1]``、batch 动态、因子单侧。

    接口 B 只输出一个**方差膨胀因子**（无量纲），没有 ``biasMeters``——那是接口三的
    输出。单侧约束 ``scale ∈ [scale_min, scale_max]`` 必须在导出时就验死：core 侧
    ``PppVarianceScalerImpl`` 把因子直接乘到 R 的对角元，若模型输出小于 1（缩小方差），
    等于在「增强」观测权重，违反「只降级不升级」的失效安全前提（§4.3.4 规则 1）。

    ``dim`` 默认 12（接口 B 逐星非差特征维），与 ``ppp_dataset.B_DIM`` 一致；
    ``scale_max`` 必须与 ``AiOpts.pppVarianceScaleMax`` 默认值一致。
    """
    import onnxruntime as ort

    sess = ort.InferenceSession(str(onnx_path), providers=["CPUExecutionProvider"])
    inp = sess.get_inputs()[0]
    out = sess.get_outputs()[0]
    if len(inp.shape) != 2 or inp.shape[1] != dim:
        raise ValueError(f"输入必须是 [batch, {dim}]，实际 {inp.shape}")
    if len(out.shape) != 2 or out.shape[1] != 1:
        raise ValueError(f"输出必须是 [batch, 1]（单膨胀因子），实际 {out.shape}")
    if not (isinstance(inp.shape[0], str) or inp.shape[0] < 0):
        raise ValueError(f"batch 维必须是动态的（每历元批量推理），实际 {inp.shape}")

    rng = np.random.default_rng(7)
    report = {"input_shape": list(inp.shape), "output_shape": list(out.shape),
              "scale_min": float(scale_min), "scale_max": float(scale_max),
              "batch_probes": {}}
    for rows in probes:
        x = rng.normal(0.0, 2.0, size=(rows, dim)).astype(np.float32)
        y = sess.run(None, {inp.name: x})[0]
        if y.shape != (rows, 1):
            raise ValueError(f"batch={rows} 时输出形状 {y.shape} 不符合契约")
        if not np.all(np.isfinite(y)):
            raise ValueError(f"batch={rows} 时输出含非有限值")
        if np.min(y) < scale_min - 1e-6 or np.max(y) > scale_max + 1e-6:
            raise ValueError(f"scale 超出 [{scale_min}, {scale_max}]: "
                             f"[{np.min(y)}, {np.max(y)}]")
        report["batch_probes"][rows] = {
            "scale_range": [float(np.min(y)), float(np.max(y))],
        }
    return report