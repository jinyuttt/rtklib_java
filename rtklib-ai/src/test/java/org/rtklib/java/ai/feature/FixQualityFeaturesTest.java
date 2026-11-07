package org.rtklib.java.ai.feature;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.ai.spi.FixQualityContext;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接口一特征提取自洽测试（设计文档 §5.1）。
 * <p>
 * 覆盖两类风险：特征顺序错位（训练/推理列名对不上）与退化输入未兜底
 * （{@code 0/0} 除法、{@code NaN} 入模型污染整批输出）。
 * </p>
 */
@DisplayName("接口一特征提取：顺序与退化兜底")
class FixQualityFeaturesTest {

    private static FixQualityContext ctx(int ns, int nb, int nCodeRows, int nPhaseRows,
                                         double adr, double prr, double pcr, float ratio, double pdop) {
        return new FixQualityContext(1, ns, 2, nb, ratio, pdop, adr, prr, pcr, nCodeRows, nPhaseRows);
    }

    @Test
    @DisplayName("特征顺序定死：adr/prr/pcr/ratio/ns_fixed/pdop")
    void featureOrderIsFixed() {
        double[] f = FixQualityFeatures.extractRaw(
                ctx(10, 8, 5, 5, 0.02, 0.35, 0.30, 5.0f, 1.2));

        assertEquals(FixQualityFeatures.DIM, f.length);
        assertEquals(0.02, f[FixQualityFeatures.IDX_ADR], 1e-12);
        assertEquals(0.35, f[FixQualityFeatures.IDX_PRR], 1e-12);
        assertEquals(0.30, f[FixQualityFeatures.IDX_PCR], 1e-12);
        assertEquals(5.0, f[FixQualityFeatures.IDX_RATIO], 1e-6);
        assertEquals(0.8, f[FixQualityFeatures.IDX_NS_FIXED], 1e-12, "ns_fixed = nb/ns 由 impl 计算");
        assertEquals(1.2, f[FixQualityFeatures.IDX_PDOP], 1e-12);
    }

    @Test
    @DisplayName("无载波行：prr 填 0.0（占位而非真实测量）")
    void prrZeroWhenNoPhaseRows() {
        double[] f = FixQualityFeatures.extractRaw(ctx(10, 8, 12, 0, 0.02, 0.99, 0.30, 5.0f, 1.2));
        assertEquals(0.0, f[FixQualityFeatures.IDX_PRR], 0.0, "nPhaseRows=0 时 prr 必须为 0.0");
        assertEquals(0.30, f[FixQualityFeatures.IDX_PCR], 1e-12, "伪距行有效，pcr 保留");
    }

    @Test
    @DisplayName("无伪距行：pcr 填 0.0（单频 DGPS 场景可能无载波行）")
    void pcrZeroWhenNoCodeRows() {
        double[] f = FixQualityFeatures.extractRaw(ctx(10, 8, 0, 20, 0.02, 0.35, 0.99, 5.0f, 1.2));
        assertEquals(0.0, f[FixQualityFeatures.IDX_PCR], 0.0, "nCodeRows=0 时 pcr 必须为 0.0");
        assertEquals(0.35, f[FixQualityFeatures.IDX_PRR], 1e-12, "载波行有效，prr 保留");
    }

    @Test
    @DisplayName("ns <= 0：ns_fixed 取 0.0，不得除零/不得 Infinity")
    void nsFixedZeroWhenNoSatellites() {
        double[] f = FixQualityFeatures.extractRaw(ctx(0, 0, 1, 1, 0.02, 0.35, 0.30, 5.0f, 1.2));
        assertEquals(0.0, f[FixQualityFeatures.IDX_NS_FIXED], 0.0);
        assertTrue(Double.isFinite(f[FixQualityFeatures.IDX_NS_FIXED]));
    }

    @Test
    @DisplayName("NaN/Inf 一律归零：毒值不得进入模型污染整批输出")
    void nonFiniteIsSanitized() {
        double[] f = FixQualityFeatures.extractRaw(
                ctx(10, 8, 3, 3, Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY,
                        5.0f, Double.NaN));
        for (int i = 0; i < f.length; i++) {
            assertTrue(Double.isFinite(f[i]), "第 " + i + " 维仍非有限: " + f[i]);
        }
        assertEquals(0.0, f[FixQualityFeatures.IDX_ADR], 0.0);
        assertEquals(0.0, f[FixQualityFeatures.IDX_PRR], 0.0);
        assertEquals(0.0, f[FixQualityFeatures.IDX_PCR], 0.0);
        assertEquals(0.0, f[FixQualityFeatures.IDX_PDOP], 0.0);
    }

    @Test
    @DisplayName("null 上下文立即失败（调用方 bug 应早暴露）")
    void nullContextRejected() {
        assertThrows(IllegalArgumentException.class, () -> FixQualityFeatures.extractRaw(null));
    }

    @Test
    @DisplayName("恒等归一化：输出与原始特征逐位一致（仅 double→float 收窄）")
    void identityNormKeepsValues() {
        double[] raw = FixQualityFeatures.extractRaw(
                ctx(10, 8, 5, 5, 0.02, 0.35, 0.30, 5.0f, 1.2));
        float[] x = FeatureNorm.identity().normalize(raw);
        assertTrue(FeatureNorm.identity().isIdentity());
        assertEquals(FixQualityFeatures.DIM, x.length);
        for (int i = 0; i < x.length; i++) {
            assertEquals((float) raw[i], x[i], 0.0f);
        }
    }

    @Test
    @DisplayName("mean/std 归一化：(x-mean)/std，std=0 只减均值不除零")
    void normAppliesMeanAndStd() throws Exception {
        java.nio.file.Path p = java.nio.file.Files.createTempFile("norm", ".json");
        try {
            java.nio.file.Files.writeString(p,
                    "{\"mean\":[0,0,0,0,0.5,0],\"std\":[1,1,1,1,0,2]}");
            FeatureNorm norm = FeatureNorm.load(p);
            assertFalse(norm.isIdentity());

            double[] raw = {1.0, 2.0, 3.0, 4.0, 0.8, 6.0};
            float[] x = norm.normalize(raw);
            assertArrayEquals(new float[]{1.0f, 2.0f, 3.0f, 4.0f, 0.3f, 3.0f}, x, 1e-6f,
                    "std=0 的第 5 维只减均值");
        } finally {
            java.nio.file.Files.deleteIfExists(p);
        }
    }

    @Test
    @DisplayName("norm.json 缺失 → 恒等归一化；维度非法 → 立即失败")
    void normLoadEdgeCases() {
        assertTrue(FeatureNorm.load(null).isIdentity());
        assertTrue(FeatureNorm.load(java.nio.file.Path.of("no_such_norm.json")).isIdentity());

        assertThrows(IllegalArgumentException.class,
                () -> FeatureNorm.load(writeTemp("{\"mean\":[0,0,0],\"std\":[1,1,1]}")));
    }

    private static java.nio.file.Path writeTemp(String content) {
        try {
            java.nio.file.Path p = java.nio.file.Files.createTempFile("norm", ".json");
            java.nio.file.Files.writeString(p, content);
            p.toFile().deleteOnExit();
            return p;
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }
}