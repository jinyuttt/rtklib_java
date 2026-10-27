package org.rtklib.java.adjust;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.adjust.engine.ResidualWhiteness;
import org.rtklib.java.adjust.model.BaselineEpoch;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 残差白度检验：白噪声应通过，显著自相关应被判失败，样本不足不作判定。
 */
class ResidualWhitenessTest {

    private static final List<BaselineEpoch.BaselineEntry> ONE_BASE =
            List.of(new BaselineEpoch.BaselineEntry(
                    "A", null, new double[3], null, null));

    @Test
    @DisplayName("白噪声残差通过检验")
    void testWhiteNoisePasses() {
        ResidualWhiteness whiteness = new ResidualWhiteness();
        Random rnd = new Random(7);

        for (int epoch = 0; epoch < 200; epoch++) {
            whiteness.feed(ONE_BASE, new double[]{
                    rnd.nextGaussian(), rnd.nextGaussian(), rnd.nextGaussian()});
        }

        ResidualWhiteness.Report report = whiteness.report();

        assertTrue(report.pass(), "白噪声不应被判为含系统性误差");
        assertTrue(report.series().size() == 3, "应产生 3 条序列（每轴一条）");
        for (ResidualWhiteness.Series s : report.series()) {
            assertTrue(s.pass(), "轴 " + s.axis() + " lag1=" + s.lag1() + " runsZ=" + s.runsZ());
        }
    }

    @Test
    @DisplayName("强自相关残差被判失败")
    void testAutocorrelatedFails() {
        ResidualWhiteness whiteness = new ResidualWhiteness();
        Random rnd = new Random(11);

        double prev = 0.0;
        for (int epoch = 0; epoch < 200; epoch++) {
            prev = 0.9 * prev + rnd.nextGaussian();
            whiteness.feed(ONE_BASE, new double[]{prev, prev, prev});
        }

        ResidualWhiteness.Report report = whiteness.report();

        assertFalse(report.pass(), "AR(1) 残差应被检出");
        for (ResidualWhiteness.Series s : report.series()) {
            assertFalse(s.pass());
            assertTrue(Math.abs(s.lag1()) > 2.58 / Math.sqrt(s.samples()),
                    "lag1 应超出白噪声置信带，实际=" + s.lag1());
        }
    }

    @Test
    @DisplayName("样本不足（< 30）不作判定")
    void testInsufficientSamplesUndetermined() {
        ResidualWhiteness whiteness = new ResidualWhiteness();
        double prev = 0.0;
        for (int epoch = 0; epoch < 10; epoch++) {
            prev = 0.9 * prev + 1.0;
            whiteness.feed(ONE_BASE, new double[]{prev, prev, prev});
        }

        ResidualWhiteness.Report report = whiteness.report();

        assertTrue(report.pass(), "样本不足时不应报警");
        for (ResidualWhiteness.Series s : report.series()) {
            assertTrue(s.undetermined());
        }
    }
}
