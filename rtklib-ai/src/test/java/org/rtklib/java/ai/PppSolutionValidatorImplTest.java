package org.rtklib.java.ai;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.ai.feature.PppSolutionFeatures;
import org.rtklib.java.ai.inference.OnnxInference;
import org.rtklib.java.ai.spi.PppSolutionContext;
import org.rtklib.java.ai.spi.PppSolutionValidator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接口 A（PPP 解有效性判别）组装自洽测试：模型 → 特征 → 推理 全链闭环。
 *
 * 断言的是「组装正确性」，不是解算质量：dummy 模型的置信度没有物理意义，
 * 但必须与 Python 参考值一致，才能证明「特征顺序 + 归一化 + 推理」三段拼接没有错位。
 */
@DisplayName("接口 A：PppSolutionValidatorImpl 组装自洽")
class PppSolutionValidatorImplTest {

    private static final Path MODEL = resolve("/models/dummy_ppp_solution_validator.onnx");
    private static final Path FIX_MODEL = resolve("/models/dummy_fix_validator.onnx");

    @Test
    @DisplayName("特征维契约：10 维模型可装配，6 维模型必须被拒绝")
    void checksFeatureDim() throws Exception {
        try (OnnxInference inf = OnnxInference.load(MODEL)) {
            assertEquals(PppSolutionFeatures.DIM, inf.inputDim());
            assertNotNull(new PppSolutionValidatorImpl(inf, null));
        }
        try (OnnxInference wrong = OnnxInference.load(FIX_MODEL)) {
            assertThrows(IllegalArgumentException.class,
                    () -> new PppSolutionValidatorImpl(wrong, null),
                    "输入维不符必须尽早失败，不得静默跑出无意义置信度");
        }
        assertThrows(IllegalArgumentException.class,
                () -> new PppSolutionValidatorImpl(null, null));
    }

    @Test
    @DisplayName("端到端置信度与 Python 参考值一致（证明特征顺序无错位）")
    void confidenceMatchesPythonReference() throws Exception {
        try (OnnxInference inf = OnnxInference.load(MODEL)) {
            PppSolutionValidator v = new PppSolutionValidatorImpl(inf, null);

            // 样本#0：positionVar=[4e-4, 9e-4, 1.6e-3] → sig=[0.02,0.03,0.04], sig_3d=sqrt(0.0029)
            // residuals=[-0.35,0.35] → rms=max=0.35；el=0.60；snr=45；innov=0.10；biasFrac=9/10
            assertEquals(2.8295294271958748e-07, v.confidence(ctx0()), 1e-6, "样本#0");

            // 样本#1：positionVar=[0.04,0.09,0.16] → sig=[0.2,0.3,0.4], sig_3d=sqrt(0.29)
            assertEquals(0.00015399396995746174, v.confidence(ctx1()), 1e-6, "样本#1");

            // 样本#2：全零（ns=0 保证 biasFrac 不除零）
            assertEquals(0.52101507755883925, v.confidence(ctx2()), 1e-6, "样本#2");
        }
    }

    @Test
    @DisplayName("特征提取退化处理：负方差/NaN/空数组一律归零，不产生 NaN 特征")
    void featuresNeverContainNaN() {
        PppSolutionContext degenerate = new PppSolutionContext(
                7, 3, 2,
                new double[]{-1.0, Double.NaN, Double.POSITIVE_INFINITY},
                new double[]{Double.NaN},
                new double[]{},
                null,
                Double.NaN, 0, false);

        double[] f = PppSolutionFeatures.extractRaw(degenerate);
        assertEquals(PppSolutionFeatures.DIM, f.length);
        for (int i = 0; i < f.length; i++) {
            assertTrue(Double.isFinite(f[i]), "特征 f" + i + " 不得为非有限值: " + f[i]);
        }
        assertEquals(0.0, f[PppSolutionFeatures.IDX_SIG_X], 0.0, "负方差钳位为 0 后再开方");
        assertEquals(0.0, f[PppSolutionFeatures.IDX_SIG_3D], 0.0);
        assertEquals(0.0, f[PppSolutionFeatures.IDX_RES_RMS], 0.0, "全 NaN 残差按无信息处理");
        assertEquals(0.0, f[PppSolutionFeatures.IDX_BIAS_FRAC], 0.0);
        assertThrows(IllegalArgumentException.class,
                () -> PppSolutionFeatures.extractRaw(null));
    }

    @Test
    @DisplayName("OnnxAiProvider 组装：接口 A 独立加载，不影响接口一/三/四")
    void providerAssemblesInterfaceA() {
        try (OnnxAiProvider provider = new OnnxAiProvider(
                null, null, null, null, null, null, MODEL, null)) {
            assertTrue(provider.isPppSolutionAvailable(), "dummy 模型应能加载");
            assertNotNull(provider.pppSolutionValidator());
            assertNull(provider.fixValidator(), "接口一未给模型：必须为 null（不干预）");
            assertNull(provider.obsCorrector(), "接口三未给模型：必须为 null（不干预）");
            assertNull(provider.refStarValidator(), "接口四未给模型：必须为 null（不干预）");
        }
        try (OnnxAiProvider provider = new OnnxAiProvider(
                null, null, null, null, null, null,
                MODEL.resolveSibling("no_such.onnx"), null)) {
            assertFalse(provider.isPppSolutionAvailable(), "模型缺失必须降级为不干预");
            assertNull(provider.pppSolutionValidator());
        }
    }

    private static PppSolutionContext ctx0() {
        return new PppSolutionContext(1, 10, 2,
                new double[]{0.0004, 0.0009, 0.0016},
                new double[]{-0.35, 0.35},
                new double[]{0.60},
                new double[]{45.0},
                0.10, 9, false);
    }

    private static PppSolutionContext ctx1() {
        return new PppSolutionContext(2, 10, 2,
                new double[]{0.04, 0.09, 0.16},
                new double[]{-1.80, 1.80},
                new double[]{0.20},
                new double[]{30.0},
                0.80, 5, false);
    }

    private static PppSolutionContext ctx2() {
        return new PppSolutionContext(3, 0, 2,
                new double[]{0.0, 0.0, 0.0},
                new double[]{},
                new double[]{},
                new double[]{},
                0.0, 0, false);
    }

    private static Path resolve(String resource) {
        try {
            return Paths.get(PppSolutionValidatorImplTest.class.getResource(resource).toURI());
        } catch (URISyntaxException | NullPointerException e) {
            throw new IllegalStateException("测试资源缺失: " + resource, e);
        }
    }
}