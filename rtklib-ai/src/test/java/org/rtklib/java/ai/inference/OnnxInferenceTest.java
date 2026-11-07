package org.rtklib.java.ai.inference;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 阶段 2 首日最小验证（设计文档 §8「阶段 2 的关键风险」）：
 * {@code 6→16→1} dummy MLP 的加载与推理，与 Python 侧前向传播数值对账。
 * <p>
 * 参考值由 {@code src/test/python/gen_dummy_onnx.py} 用 numpy 双精度前向同一份 float32 权重产出；
 * Java 侧走 ONNX Runtime float32 路径，故用 {@code 1e-6} 容差而非逐位相等——
 * 两条路径的算子实现（GEMM 累加顺序、Sigmoid 实现）不同，逐位相等不是可验证契约，
 * 数值一致才是。
 * </p>
 */
@DisplayName("阶段2最小验证：ONNX 加载 + 推理对账")
class OnnxInferenceTest {

    private static final Path MODEL = resolve("/models/dummy_fix_validator.onnx");

    private static final float[] SAMPLE_GOOD = {0.02f, 0.35f, 0.30f, 5.0f, 0.90f, 1.2f};
    private static final float[] SAMPLE_BAD = {0.20f, 1.80f, 1.60f, 1.5f, 0.50f, 3.0f};
    private static final float[] SAMPLE_ZERO = {0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f};

    /** Python 侧参考输出（{@code gen_dummy_onnx.py} 的 ref）。 */
    private static final double[] REF = {0.515910397516176, 0.2839837075276066, 0.5122316023441404};

    private static final double TOL = 1e-6;

    private static OnnxInference inference;

    @BeforeAll
    static void loadModel() throws Exception {
        inference = OnnxInference.load(MODEL);
    }

    @AfterAll
    static void closeModel() {
        if (inference != null) inference.close();
    }

    @Test
    @DisplayName("模型声明维度：输入 6、输出 1")
    void modelShapeIsSixToOne() {
        assertEquals(6, inference.inputDim(), "接口一特征维必须为 6");
        assertEquals(1, inference.outputDim(), "接口一输出维必须为 1");
        assertEquals("confidence", inference.outputName(), "输出节点名");
    }

    @Test
    @DisplayName("批量推理：3 组样本与 Python 参考值一致")
    void batchMatchesPythonReference() throws Exception {
        float[] batch = concat(SAMPLE_GOOD, SAMPLE_BAD, SAMPLE_ZERO);
        float[] out = inference.infer(batch, 3);

        assertEquals(3, out.length, "输出行数必须等于输入行数");
        for (int i = 0; i < 3; i++) {
            assertEquals(REF[i], out[i], TOL, "样本#" + i + " 与 Python 前向结果不一致");
        }
    }

    @Test
    @DisplayName("单行推理与批量推理自洽（同一行结果一致）")
    void singleEqualsBatch() throws Exception {
        float[] out = inference.infer(concat(SAMPLE_GOOD, SAMPLE_BAD, SAMPLE_ZERO), 3);
        assertEquals(out[0], inference.inferSingle(SAMPLE_GOOD), 1e-7, "样本#0 单行 vs 批量");
        assertEquals(out[1], inference.inferSingle(SAMPLE_BAD), 1e-7, "样本#1 单行 vs 批量");
        assertEquals(out[2], inference.inferSingle(SAMPLE_ZERO), 1e-7, "样本#2 单行 vs 批量");
    }

    @Test
    @DisplayName("批量顺序性：行序不影响单行结果，且与逐行拼接一致")
    void rowOrderIsIndependent() throws Exception {
        float[] a = inference.infer(concat(SAMPLE_GOOD, SAMPLE_ZERO), 2);
        float[] b = inference.infer(concat(SAMPLE_ZERO, SAMPLE_GOOD), 2);
        assertEquals(a[0], b[1], 1e-7, "样本#0 在两种行序下一致");
        assertEquals(a[1], b[0], 1e-7, "样本#2 在两种行序下一致");
    }

    @Test
    @DisplayName("输入长度不符时立即失败，不静默补零")
    void rejectsWrongInputLength() {
        assertThrows(IllegalArgumentException.class, () -> inference.infer(SAMPLE_GOOD, 2));
        assertThrows(IllegalArgumentException.class,
                () -> inference.infer(new float[]{1.0f, 2.0f, 3.0f, 4.0f, 5.0f}, 1));
        assertThrows(IllegalArgumentException.class, () -> inference.infer(SAMPLE_GOOD, 0));
    }

    @Test
    @DisplayName("模型文件缺失时 load 立即失败（由 provider 决定降级）")
    void loadFailsOnMissingFile() {
        assertThrows(IllegalArgumentException.class,
                () -> OnnxInference.load(MODEL.resolveSibling("no_such_model.onnx")));
        assertThrows(IllegalArgumentException.class, () -> OnnxInference.load(null));
    }

    @Test
    @DisplayName("推理结果落在 (0,1)：Sigmoid 输出无需额外归一")
    void outputWithinUnitInterval() throws Exception {
        float[] out = inference.infer(concat(SAMPLE_GOOD, SAMPLE_BAD, SAMPLE_ZERO), 3);
        for (int i = 0; i < out.length; i++) {
            assertTrue(out[i] > 0.0f && out[i] < 1.0f, "样本#" + i + " 置信度 " + out[i] + " 越界");
        }
    }

    private static float[] concat(float[]... rows) {
        int dim = rows[0].length;
        float[] flat = new float[rows.length * dim];
        for (int r = 0; r < rows.length; r++) {
            System.arraycopy(rows[r], 0, flat, r * dim, dim);
        }
        return flat;
    }

    private static Path resolve(String resource) {
        try {
            return Paths.get(OnnxInferenceTest.class.getResource(resource).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("无法定位测试资源 " + resource, e);
        }
    }
}