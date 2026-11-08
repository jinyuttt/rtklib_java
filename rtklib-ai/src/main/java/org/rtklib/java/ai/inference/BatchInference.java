package org.rtklib.java.ai.inference;

/**
 * 批量推理抽象（设计文档 §6.1）。
 * <p>
 * 存在的唯一理由：让接口三实现可以在<b>不依赖真实 ONNX 会话</b>的前提下被单测
 * （窗口重置、行序、成对输出校验等逻辑与后端无关）。
 * 生产路径上由 {@link OnnxInference} 实现，等价于原样调用。
 * </p>
 * <p>
 * 契约：输入 {@code float[rows * inputDim]}（行优先压平），输出 {@code float[rows * outputDim]}。
 * </p>
 */
public interface BatchInference {

    /** 模型声明的特征维（接口一 6、接口三 17）。 */
    int inputDim();

    /** 模型声明的输出维（接口一 1、接口三 2）。 */
    int outputDim();

    /**
     * 批量推理。
     *
     * @param batch 行优先压平的输入，长度必须等于 {@code rows * inputDim()}
     * @param rows  样本行数（{@code >= 1}）
     * @return 行优先压平的输出，长度等于 {@code rows * outputDim()}
     * @throws Exception 后端推理失败；调用方按「不干预」降级，不得 fail-closed
     */
    float[] infer(float[] batch, int rows) throws Exception;
}