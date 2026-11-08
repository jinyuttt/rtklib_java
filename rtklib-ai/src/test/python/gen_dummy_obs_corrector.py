"""生成接口三（观测改正）验证用的 dummy 模型：17 -> 1 的线性 + 契约安全头。

无训练依赖（纯随机权重 + 固定种子），目的是打通「导出 ONNX -> Java 加载 -> 批量推理
-> 偏差/方差施加」链路，并保证输出天然落在 core 钳位区间内：

- ``bias  = bias_max * tanh(x @ WB)``   → ``|bias| <= bias_max``
- ``scale = 1 + span * sigmoid(x @ WS)`` → ``scale in [1, 1 + span]``

用法（需 onnx + numpy + onnxruntime）：
    python rtklib-ai/src/test/python/gen_dummy_obs_corrector.py
产物：
    rtklib-ai/src/test/resources/models/dummy_obs_corrector.onnx   （bias_max=0.1, span=0.5）
    rtklib-ai/src/test/resources/models/dummy_zero_corrector.onnx  （恒零改正，验证 I-1）
"""

import os

import numpy as np
import onnx
from onnx import TensorProto, helper, numpy_helper

SEED = 20261107
DIM = 17


def build(name, wb, ws, bias_max, scale_span):
    inits = [
        numpy_helper.from_array(wb, "WB"),
        numpy_helper.from_array(ws, "WS"),
        numpy_helper.from_array(np.array([bias_max], dtype=np.float32), "c_bias"),
        numpy_helper.from_array(np.array([scale_span], dtype=np.float32), "c_span"),
        numpy_helper.from_array(np.array([1.0], dtype=np.float32), "c_one"),
    ]
    nodes = [
        helper.make_node("MatMul", ["input", "WB"], ["zB"]),
        helper.make_node("Tanh", ["zB"], ["tB"]),
        helper.make_node("Mul", ["tB", "c_bias"], ["bias"]),
        helper.make_node("MatMul", ["input", "WS"], ["zS"]),
        helper.make_node("Sigmoid", ["zS"], ["sS"]),
        helper.make_node("Mul", ["sS", "c_span"], ["sSpan"]),
        helper.make_node("Add", ["sSpan", "c_one"], ["scale"]),
        helper.make_node("Concat", ["bias", "scale"], ["output"], axis=1),
    ]
    graph = helper.make_graph(
        nodes,
        name,
        [helper.make_tensor_value_info("input", TensorProto.FLOAT, ["batch", DIM])],
        [helper.make_tensor_value_info("output", TensorProto.FLOAT, ["batch", 2])],
        initializer=inits,
    )
    model = helper.make_model(graph, opset_imports=[helper.make_opsetid("", 17)])
    model.ir_version = 8
    return model


def main():
    rng = np.random.default_rng(SEED)
    here = os.path.dirname(os.path.abspath(__file__))
    out_dir = os.path.join(here, "..", "resources", "models")
    os.makedirs(out_dir, exist_ok=True)

    models = {
        "dummy_obs_corrector.onnx": build(
            "dummy_obs_corrector",
            rng.normal(0.0, 0.02, (DIM, 1)).astype(np.float32),
            rng.normal(0.0, 0.02, (DIM, 1)).astype(np.float32),
            0.1, 0.5),
        "dummy_zero_corrector.onnx": build(
            "dummy_zero_corrector",
            np.zeros((DIM, 1), np.float32),
            np.zeros((DIM, 1), np.float32),
            0.0, 0.0),
    }

    for name, model in models.items():
        onnx.checker.check_model(model)
        path = os.path.join(out_dir, name)
        onnx.save(model, path)
        print(f"已生成: {path}")

    import onnxruntime as ort

    probe = rng.normal(0.0, 1.0, (3, DIM)).astype(np.float32)
    for name in models:
        sess = ort.InferenceSession(os.path.join(out_dir, name),
                                    providers=["CPUExecutionProvider"])
        y = sess.run(None, {"input": probe})[0]
        print(f"{name} -> {y.shape}\n{y}")


if __name__ == "__main__":
    main()