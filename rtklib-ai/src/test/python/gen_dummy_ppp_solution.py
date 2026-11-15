"""生成接口 A（PPP 解有效性判别）最小验证用的 dummy 模型：10 -> 16 -> 1 单隐层 MLP。

无训练依赖（纯随机权重 + 固定种子），目的是打通「导出 ONNX -> Java 加载 -> 推理」链路，
并用同一份权重的 numpy 前向传播给出参考值，供 Java 侧逐位对账。

用法（需 onnx + numpy）：
    python rtklib-ai/src/test/python/gen_dummy_ppp_solution.py
产物：
    rtklib-ai/src/test/resources/models/dummy_ppp_solution_validator.onnx

特征顺序与 rtklib-ai 的 PppSolutionFeatures 逐位一致（f0..f9）：
    sig_x, sig_y, sig_z, sig_3d, res_rms, res_max, el_mean, snr_mean, innov_rms, bias_frac
"""

import os

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

rng = np.random.default_rng(20261114)

DIM = 10
H = 16
W1 = rng.normal(0.0, 0.35, size=(DIM, H)).astype(np.float32)
B1 = rng.normal(0.0, 0.1, size=(H,)).astype(np.float32)
W2 = rng.normal(0.0, 0.35, size=(H, 1)).astype(np.float32)
B2 = rng.normal(0.0, 0.1, size=(1,)).astype(np.float32)

inp = helper.make_tensor_value_info("features", TensorProto.FLOAT, ["batch", DIM])
out = helper.make_tensor_value_info("confidence", TensorProto.FLOAT, ["batch", 1])

nodes = [
    helper.make_node("MatMul", ["features", "W1"], ["h1"]),
    helper.make_node("Add", ["h1", "B1"], ["h1b"]),
    helper.make_node("Relu", ["h1b"], ["h1r"]),
    helper.make_node("MatMul", ["h1r", "W2"], ["h2"]),
    helper.make_node("Add", ["h2", "B2"], ["h2b"]),
    helper.make_node("Sigmoid", ["h2b"], ["confidence"]),
]

graph = helper.make_graph(
    nodes,
    "dummy_ppp_solution_validator_mlp_10_16_1",
    [inp],
    [out],
    initializer=[
        numpy_helper.from_array(W1, "W1"),
        numpy_helper.from_array(B1, "B1"),
        numpy_helper.from_array(W2, "W2"),
        numpy_helper.from_array(B2, "B2"),
    ],
)

model = helper.make_model(
    graph,
    producer_name="rtklib-java-ai-ppp-solution",
    opset_imports=[helper.make_opsetid("", 13)],
)
model.ir_version = 7
onnx.checker.check_model(model)

here = os.path.dirname(os.path.abspath(__file__))
out_dir = os.path.join(here, "..", "resources", "models")
os.makedirs(out_dir, exist_ok=True)
path = os.path.join(out_dir, "dummy_ppp_solution_validator.onnx")
onnx.save(model, path)


def forward(x):
    h1 = np.maximum(x @ W1 + B1, 0.0)
    z = h1 @ W2 + B2
    return 1.0 / (1.0 + np.exp(-z))


samples = [
    [0.02, 0.03, 0.04, float(np.sqrt(0.0029)), 0.35, 0.35, 0.60, 45.0, 0.10, 0.90],
    [0.20, 0.30, 0.40, float(np.sqrt(0.29)), 1.80, 1.80, 0.20, 30.0, 0.80, 0.50],
    [0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0],
]

print("model:", path)
for i, s in enumerate(samples):
    y = float(forward(np.array([s], dtype=np.float64))[0][0])
    print("sample#%d confidence=%.17g" % (i, y))