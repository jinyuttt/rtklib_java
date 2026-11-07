"""生成阶段2最小验证用的 dummy 模型：6 -> 16 -> 1 单隐层 MLP。

无训练依赖（纯随机权重 + 固定种子），目的是打通「导出 ONNX -> Java 加载 -> 推理」链路，
并用同一份权重的 numpy 前向传播给出参考值，供 Java 侧逐位对账。

用法（需 onnx + numpy）：
    python rtklib-ai/src/test/python/gen_dummy_onnx.py
产物：
    rtklib-ai/src/test/resources/models/dummy_fix_validator.onnx
"""

import json
import os

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

rng = np.random.default_rng(20261106)

H = 16
W1 = rng.normal(0.0, 0.35, size=(6, H)).astype(np.float32)
B1 = rng.normal(0.0, 0.1, size=(H,)).astype(np.float32)
W2 = rng.normal(0.0, 0.35, size=(H, 1)).astype(np.float32)
B2 = rng.normal(0.0, 0.1, size=(1,)).astype(np.float32)

inp = helper.make_tensor_value_info("features", TensorProto.FLOAT, ["batch", 6])
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
    "dummy_fix_validator_mlp_6_16_1",
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
    producer_name="rtklib-java-ai-stage2",
    opset_imports=[helper.make_opsetid("", 13)],
)
model.ir_version = 7
onnx.checker.check_model(model)

here = os.path.dirname(os.path.abspath(__file__))
out_dir = os.path.join(here, "..", "resources", "models")
os.makedirs(out_dir, exist_ok=True)
path = os.path.join(out_dir, "dummy_fix_validator.onnx")
onnx.save(model, path)


def forward(x):
    h1 = np.maximum(x @ W1 + B1, 0.0)
    z = h1 @ W2 + B2
    return 1.0 / (1.0 + np.exp(-z))


samples = [
    [0.02, 0.35, 0.30, 5.0, 0.90, 1.2],
    [0.20, 1.80, 1.60, 1.5, 0.50, 3.0],
    [0.00, 0.00, 0.00, 0.0, 0.00, 0.0],
]
ref = []
for s in samples:
    ref.append([float(v) for v in forward(np.array([s], dtype=np.float64))[0]])

print("saved:", path, os.path.getsize(path), "bytes")
print("weights:", json.dumps({
    "W1": W1.flatten().tolist(),
    "B1": B1.flatten().tolist(),
    "W2": W2.flatten().tolist(),
    "B2": B2.flatten().tolist(),
}))
print("samples:", json.dumps(samples))
print("ref:", json.dumps(ref))