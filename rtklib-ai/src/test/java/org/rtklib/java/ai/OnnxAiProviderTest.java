package org.rtklib.java.ai;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.ai.spi.FixQualityContext;
import org.rtklib.java.ai.spi.FixValidator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link OnnxAiProvider} 组装自洽测试：模型 → 特征 → 推理 全链在同一模块内闭环。
 * <p>
 * 这里断言的是「组装正确性」，不是解算质量：dummy 模型的置信度没有物理意义，
 * 但它必须与 Python 参考值一致，才能证明「特征顺序 + 归一化 + 推理」三段拼接没有错位。
 * </p>
 */
@DisplayName("OnnxAiProvider 组装自洽")
class OnnxAiProviderTest {

    private static final Path MODEL = resolve("/models/dummy_fix_validator.onnx");

    @Test
    @DisplayName("加载 dummy 模型后接口一可用，接口三为 null（阶段2 未实现）")
    void assemblesFixValidatorOnly() {
        try (OnnxAiProvider provider = new OnnxAiProvider(MODEL, null)) {
            assertTrue(provider.isFixValidatorAvailable(), "dummy 模型应能加载");
            assertNotNull(provider.fixValidator());
            assertNull(provider.obsCorrector(), "阶段2 不实现接口三：必须返回 null（不干预）");
        }
    }

    @Test
    @DisplayName("端到端置信度与 Python 参考值一致（证明特征顺序无错位）")
    void confidenceMatchesPythonReference() {
        try (OnnxAiProvider provider = new OnnxAiProvider(MODEL, null)) {
            FixValidator v = provider.fixValidator();
            assertNotNull(v);

            // 样本#0：[adr, prr, pcr, ratio, ns_fixed, pdop] = [0.02, 0.35, 0.30, 5.0, 0.90, 1.2]
            // ns_fixed = nb/ns = 9/10 = 0.9
            FixQualityContext good = new FixQualityContext(
                    1, 10, 2, 9, 5.0f, 1.2, 0.02, 0.35, 0.30, 5, 5);
            assertEquals(0.515910397516176, v.validate(good), 1e-6, "样本#0");

            // 样本#1：ns_fixed = 5/10 = 0.5
            FixQualityContext bad = new FixQualityContext(
                    2, 10, 2, 5, 1.5f, 3.0, 0.20, 1.80, 1.60, 5, 5);
            assertEquals(0.2839837075276066, v.validate(bad), 1e-6, "样本#1");

            // 样本#2：全零（ns>0 保证 ns_fixed 不除零）
            FixQualityContext zero = new FixQualityContext(
                    3, 1, 2, 0, 0.0f, 0.0, 0.0, 0.0, 0.0, 0, 0);
            assertEquals(0.5122316023441404, v.validate(zero), 1e-6, "样本#2");
        }
    }

    @Test
    @DisplayName("模型缺失 → 降级为不干预（不抛异常、不 fail-closed）")
    void missingModelDegradesToNoIntervention() {
        try (OnnxAiProvider provider =
                     new OnnxAiProvider(MODEL.resolveSibling("no_such.onnx"), null)) {
            assertFalse(provider.isFixValidatorAvailable());
            assertNull(provider.fixValidator());
            assertNull(provider.obsCorrector());
        }
        try (OnnxAiProvider provider = new OnnxAiProvider((Path) null)) {
            assertNull(provider.fixValidator());
        }
    }

    @Test
    @DisplayName("归一化参数缺失 → 恒等归一化仍可推理")
    void identityNormWhenNormFileMissing() {
        try (OnnxAiProvider provider = new OnnxAiProvider(MODEL,
                MODEL.resolveSibling("no_such.norm.json"))) {
            FixValidator v = provider.fixValidator();
            assertNotNull(v, "无 norm.json 时应退化为恒等归一化而非加载失败");
            FixQualityContext ctx = new FixQualityContext(
                    1, 10, 2, 9, 5.0f, 1.2, 0.02, 0.35, 0.30, 5, 5);
            assertEquals(0.515910397516176, v.validate(ctx), 1e-6);
        }
    }

    @Test
    @DisplayName("FixValidatorImpl 维度契约：输出维必须为 1")
    void fixValidatorImplChecksShape() {
        assertThrows(IllegalArgumentException.class, () -> new FixValidatorImpl(null, null));
    }

    private static Path resolve(String resource) {
        try {
            return Paths.get(OnnxAiProviderTest.class.getResource(resource).toURI());
        } catch (URISyntaxException e) {
            throw new IllegalStateException("无法定位测试资源 " + resource, e);
        }
    }
}