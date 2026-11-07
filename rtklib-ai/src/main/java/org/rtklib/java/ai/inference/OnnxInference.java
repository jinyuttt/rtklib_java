package org.rtklib.java.ai.inference;

import java.nio.FloatBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Set;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OnnxValue;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

/**
 * ONNX Runtime 推理适配层（设计文档 §6.1）。
 * <p>
 * 职责单一：把 {@code float[rows][dim]} 送进模型、把输出压平成 {@code float[rows*outDim]}。
 * 不涉及特征构造、阈值判定、降级策略——那些属于 {@code feature} 与接口实现层，
 * 这样替换后端（ONNX / 纯 Java MLP / 远程服务）时本类可整体替换而不牵动业务语义。
 * </p>
 * <p>
 * 生命周期：{@link OrtEnvironment} 是进程级共享单例，{@link #close()} <b>不</b>关闭它，
 * 只释放本实例持有的 {@link OrtSession}，避免多实例互相干扰。
 * </p>
 * <p>
 * 线程安全：{@link OrtSession#run} 内部是线程安全的，本类无可变字段，
 * 可被多个历元/线程并发调用。
 * </p>
 */
public final class OnnxInference implements AutoCloseable {

    private final OrtEnvironment env;
    private final OrtSession session;
    private final String inputName;
    private final String outputName;
    private final int inDim;
    private final int outDim;

    private OnnxInference(OrtEnvironment env, OrtSession session, String inputName, String outputName,
                          int inDim, int outDim) {
        this.env = env;
        this.session = session;
        this.inputName = inputName;
        this.outputName = outputName;
        this.inDim = inDim;
        this.outDim = outDim;
    }

    /**
     * 加载模型。模型缺失、输入输出个数不为 1、维度为动态或非二维时立即失败，
     * 由调用方决定降级（设计文档 §3.4 要求「不干预」，不允许 fail-closed）。
     */
    public static OnnxInference load(Path modelPath) throws OrtException {
        if (modelPath == null || !Files.isRegularFile(modelPath)) {
            throw new IllegalArgumentException("ONNX 模型文件不存在: " + modelPath);
        }
        OrtEnvironment env = OrtEnvironment.getEnvironment();
        OrtSession session = null;
        try {
            try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                options.setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT);
                session = env.createSession(modelPath.toString(), options);
            }

            Set<String> inputs = session.getInputNames();
            if (inputs.size() != 1) {
                throw new IllegalArgumentException("模型必须恰好 1 个输入，实际 " + inputs.size()
                        + ": " + inputs + "，文件 " + modelPath);
            }
            String inputName = inputs.iterator().next();
            int inDim = staticDim(session.getInputInfo().get(inputName), inputName, modelPath);

            Set<String> outputs = session.getOutputNames();
            if (outputs.size() != 1) {
                throw new IllegalArgumentException("模型必须恰好 1 个输出，实际 " + outputs.size()
                        + ": " + outputs + "，文件 " + modelPath);
            }
            String outputName = outputs.iterator().next();
            int outDim = staticDim(session.getOutputInfo().get(outputName), outputName, modelPath);

            return new OnnxInference(env, session, inputName, outputName, inDim, outDim);
        } catch (RuntimeException e) {
            closeQuietly(session);
            throw e;
        }
    }

    private static void closeQuietly(OrtSession session) {
        if (session == null) return;
        try {
            session.close();
        } catch (OrtException ignored) {
            // 加载失败路径上的二次异常不应掩盖原始失败原因
        }
    }

    private static int staticDim(NodeInfo info, String name, Path modelPath) {
        if (!(info.getInfo() instanceof TensorInfo)) {
            throw new IllegalArgumentException("节点 " + name + " 不是张量，文件 " + modelPath);
        }
        long[] shape = ((TensorInfo) info.getInfo()).getShape();
        if (shape.length != 2) {
            throw new IllegalArgumentException("节点 " + name + " 必须是二维 [batch, dim]，实际 shape="
                    + java.util.Arrays.toString(shape) + "，文件 " + modelPath);
        }
        if (shape[1] <= 0) {
            throw new IllegalArgumentException("节点 " + name + " 的特征维必须是静态值，实际 shape="
                    + java.util.Arrays.toString(shape) + "，文件 " + modelPath);
        }
        return (int) shape[1];
    }

    /** 模型声明的特征维（接口一为 6）。 */
    public int inputDim() {
        return inDim;
    }

    /** 模型声明的输出维（接口一为 1）。 */
    public int outputDim() {
        return outDim;
    }

    /**
     * 单行推理，等价于 {@code infer(x, 1)} 后取首元素。
     */
    public float inferSingle(float[] x) throws OrtException {
        float[] y = infer(x, 1);
        return y[0];
    }

    /**
     * 批量推理。
     *
     * @param batch 行优先压平的输入，长度必须等于 {@code rows * inputDim()}
     * @param rows  样本行数（{@code >= 1}）
     * @return 行优先压平的输出，长度等于 {@code rows * outputDim()}
     */
    public float[] infer(float[] batch, int rows) throws OrtException {
        if (rows < 1) {
            throw new IllegalArgumentException("rows 必须 >= 1，实际 " + rows);
        }
        if (batch == null || batch.length != rows * inDim) {
            throw new IllegalArgumentException("输入长度 "
                    + (batch == null ? "null" : batch.length)
                    + " 与 rows*inputDim=" + (rows * inDim) + " 不一致");
        }
        try (OnnxTensor input = OnnxTensor.createTensor(env, FloatBuffer.wrap(batch),
                new long[]{rows, inDim})) {
            Map<String, OnnxTensor> feeds = Collections.singletonMap(inputName, input);
            try (OrtSession.Result result = session.run(feeds)) {
                OnnxValue value = result.get(0);
                Object raw = value.getValue();
                if (!(raw instanceof float[][])) {
                    throw new IllegalStateException("模型输出必须是二维 float，实际 "
                            + (raw == null ? "null" : raw.getClass().getName()));
                }
                float[][] out = (float[][]) raw;
                if (out.length != rows) {
                    throw new IllegalStateException("模型输出行数 " + out.length + " 与输入行数 " + rows + " 不一致");
                }
                float[] flat = new float[rows * outDim];
                for (int r = 0; r < rows; r++) {
                    if (out[r].length != outDim) {
                        throw new IllegalStateException("第 " + r + " 行输出维 " + out[r].length
                                + " 与声明 " + outDim + " 不一致");
                    }
                    System.arraycopy(out[r], 0, flat, r * outDim, outDim);
                }
                return flat;
            }
        }
    }

    /** 输出节点名，供调试与 Trace 记录。 */
    public String outputName() {
        return outputName;
    }

    @Override
    public void close() {
        try {
            session.close();
        } catch (OrtException e) {
            throw new IllegalStateException("关闭 OrtSession 失败", e);
        }
    }
}