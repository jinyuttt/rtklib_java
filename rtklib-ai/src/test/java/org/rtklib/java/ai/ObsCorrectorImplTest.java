package org.rtklib.java.ai;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.ai.feature.FeatureNorm;
import org.rtklib.java.ai.feature.ObsCorrectionFeatures;
import org.rtklib.java.ai.inference.BatchInference;
import org.rtklib.java.ai.spi.CodeCorrection;
import org.rtklib.java.ai.spi.ObsCorrectionContext;
import org.rtklib.java.ai.spi.SatFeature;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接口三实现测试（设计文档 §4.3 / §5.3 / §6.1）。
 * <p>
 * 用桩后端替换 ONNX，覆盖与模型质量无关、但错一次就是静默错行/静默失效的部分：
 * 行序 {@code i*nf+frq}、短窗三条重置规则、成对输出契约、毒值兜底。
 * </p>
 */
@DisplayName("ObsCorrectorImpl：行序、短窗与输出契约")
class ObsCorrectorImplTest {

    private static final double DT = 1.0;

    /** 记录最后一次输入的可配置桩后端。 */
    private static final class StubInference implements BatchInference {

        private final int inDim;
        private final int outDim;
        float[] lastBatch;
        int lastRows;
        float[] forcedOut;
        Exception toThrow;

        StubInference(int inDim, int outDim) {
            this.inDim = inDim;
            this.outDim = outDim;
        }

        @Override
        public int inputDim() {
            return inDim;
        }

        @Override
        public int outputDim() {
            return outDim;
        }

        @Override
        public float[] infer(float[] batch, int rows) throws Exception {
            this.lastBatch = batch;
            this.lastRows = rows;
            if (toThrow != null) throw toThrow;
            if (forcedOut != null) return forcedOut;
            float[] out = new float[rows * outDim];
            for (int i = 0; i < rows; i++) {
                out[i * outDim] = 0.3f;
                out[i * outDim + 1] = 1.2f;
            }
            return out;
        }
    }

    private static SatFeature sat(int sat, int sys, double elDeg, int slipFrq1, double resp) {
        return new SatFeature(sat, sys, Math.toRadians(10.0), Math.toRadians(elDeg),
                new double[]{45.0, 40.0}, new double[]{42.0, 38.0},
                new double[]{resp, resp}, new double[]{0.02, 0.03},
                new int[]{100, 90}, new int[]{0, slipFrq1}, new int[]{0, 0},
                new double[]{0.5, 0.6}, new int[]{1, 1}, new int[]{2, 1});
    }

    private static ObsCorrectionContext ctx(int epoch, double dt, int nf, SatFeature... sats) {
        return new ObsCorrectionContext(epoch, sats.length, nf, dt, sats);
    }

    /** 读取第 {@code row} 行第 {@code col} 列特征（桩后端最后一批输入）。 */
    private static double feature(StubInference stub, int row, int col) {
        return stub.lastBatch[row * ObsCorrectionFeatures.DIM + col];
    }

    private static double histLen(StubInference stub, int row) {
        return feature(stub, row, ObsCorrectionFeatures.IDX_HIST_LEN);
    }

    @Test
    @DisplayName("行序 = i*nf+frq：特征必须按 sat[] 顺序与频点顺序压平，一次批量推理")
    void rowOrderIsSatThenFreq() {
        StubInference stub = new StubInference(ObsCorrectionFeatures.DIM, 2);
        ObsCorrectorImpl impl = new ObsCorrectorImpl(stub, null);

        CodeCorrection c = impl.correct(ctx(1, DT, 2,
                sat(3, 0x01, 45.0, 0, 1.0), sat(11, 0x08, 20.0, 0, 2.0)));

        assertEquals(4, stub.lastRows, "ns=2, nf=2 → 4 行，只调一次推理");
        assertEquals(4 * ObsCorrectionFeatures.DIM, stub.lastBatch.length);
        assertEquals(4, c.biasMeters().length);
        assertEquals(4, c.varianceScale().length);

        assertEquals(45.0, feature(stub, 0, ObsCorrectionFeatures.IDX_EL), 1e-9, "row0 = sat[0] frq0");
        assertEquals(20.0, feature(stub, 2, ObsCorrectionFeatures.IDX_EL), 1e-9, "row2 = sat[1] frq0");
        assertEquals(2.0, feature(stub, 2, ObsCorrectionFeatures.IDX_RESP), 1e-12);
        assertEquals(0.0, feature(stub, 0, ObsCorrectionFeatures.IDX_SYS_ID), 1e-12, "GPS");
        assertEquals(3.0, feature(stub, 2, ObsCorrectionFeatures.IDX_SYS_ID), 1e-12, "GAL");

        for (int idx = 0; idx < 4; idx++) {
            assertEquals(0.3, c.biasMeters()[idx], 1e-6, "bias 取 out[2*idx]");
            assertEquals(1.2, c.varianceScale()[idx], 1e-6, "scale 取 out[2*idx+1]");
        }
    }

    @Test
    @DisplayName("短窗：首历元 0.2，逐历元累加到 1.0 后封顶")
    void windowGrowsToCap() {
        StubInference stub = new StubInference(ObsCorrectionFeatures.DIM, 2);
        ObsCorrectorImpl impl = new ObsCorrectorImpl(stub, null);
        double[] expect = {0.2, 0.4, 0.6, 0.8, 1.0, 1.0};
        for (int k = 0; k < expect.length; k++) {
            impl.correct(ctx(k + 1, DT, 1, sat(3, 0x01, 45.0, 0, 1.0)));
            assertEquals(expect[k], histLen(stub, 0), 1e-6, "第 " + (k + 1) + " 历元 histLen");
        }
        assertEquals(1, impl.windowSize());
    }

    @Test
    @DisplayName("窗口均值：5 历元样本按实际入窗值聚合（含当前历元）")
    void windowAggregateUsesPushedSamples() {
        StubInference stub = new StubInference(ObsCorrectionFeatures.DIM, 2);
        ObsCorrectorImpl impl = new ObsCorrectorImpl(stub, null);
        for (int k = 1; k <= 5; k++) {
            impl.correct(ctx(k, DT, 1, sat(3, 0x01, 45.0, 0, k)));
        }
        assertEquals(3.0, feature(stub, 0, ObsCorrectionFeatures.IDX_RESP_WIN_MEAN), 1e-6,
                "resp = 1..5 的均值 = 3");
        assertEquals(Math.sqrt(2.5), feature(stub, 0, ObsCorrectionFeatures.IDX_RESP_WIN_STD), 1e-6,
                "样本标准差 = sqrt(2.5)");
    }

    @Test
    @DisplayName("数据缺口：epoch 间隔 > 1.5*dt → 清空窗口（histLen 回到 0.2）")
    void gapClearsWindows() {
        StubInference stub = new StubInference(ObsCorrectionFeatures.DIM, 2);
        ObsCorrectorImpl impl = new ObsCorrectorImpl(stub, null);
        impl.correct(ctx(1, DT, 1, sat(3, 0x01, 45.0, 0, 1.0)));
        impl.correct(ctx(2, DT, 1, sat(3, 0x01, 45.0, 0, 1.0)));
        assertEquals(0.4, histLen(stub, 0), 1e-6);

        impl.correct(ctx(4, DT, 1, sat(3, 0x01, 45.0, 0, 1.0)));
        assertEquals(0.2, histLen(stub, 0), 1e-6, "跳过一个历元必须清空");
    }

    @Test
    @DisplayName("历元倒退（同一实例跨会话复用）→ 视为新会话，清空全部窗口")
    void epochRegressionStartsNewSession() {
        StubInference stub = new StubInference(ObsCorrectionFeatures.DIM, 2);
        ObsCorrectorImpl impl = new ObsCorrectorImpl(stub, null);
        for (int k = 1; k <= 3; k++) {
            impl.correct(ctx(k, DT, 1, sat(3, 0x01, 45.0, 0, 1.0)));
        }
        assertEquals(0.6, histLen(stub, 0), 1e-6, "会话内累加到 3 个样本");

        impl.correct(ctx(1, DT, 1, sat(3, 0x01, 45.0, 0, 1.0)));
        assertEquals(0.2, histLen(stub, 0), 1e-6,
                "epoch 从 3 回到 1 必须清空窗口，否则上一会话样本会泄漏进新会话");
    }

    @Test
    @DisplayName("dt 不可用（<=0 或非有限）→ 每历元清空，histLen 恒 0.2")
    void unusableDtClearsEveryEpoch() {
        StubInference stub = new StubInference(ObsCorrectionFeatures.DIM, 2);
        ObsCorrectorImpl impl = new ObsCorrectorImpl(stub, null);
        impl.correct(ctx(1, 0.0, 1, sat(3, 0x01, 45.0, 0, 1.0)));
        assertEquals(0.2, histLen(stub, 0), 1e-6);
        impl.correct(ctx(2, Double.NaN, 1, sat(3, 0x01, 45.0, 0, 1.0)));
        assertEquals(0.2, histLen(stub, 0), 1e-6);
    }

    @Test
    @DisplayName("周跳只清该 (sat, frq)：其他频点窗口必须保留")
    void slipClearsOnlyThatRow() {
        StubInference stub = new StubInference(ObsCorrectionFeatures.DIM, 2);
        ObsCorrectorImpl impl = new ObsCorrectorImpl(stub, null);
        impl.correct(ctx(1, DT, 2, sat(3, 0x01, 45.0, 0, 1.0)));
        impl.correct(ctx(2, DT, 2, sat(3, 0x01, 45.0, 1, 1.0)));

        assertEquals(0.4, histLen(stub, 0), 1e-6, "frq0 无周跳，窗口保留");
        assertEquals(0.2, histLen(stub, 1), 1e-6, "frq1 有周跳，窗口清零");
    }

    @Test
    @DisplayName("卫星消失 → 窗口移除，不随历元数无界增长")
    void vanishedSatIsRemoved() {
        StubInference stub = new StubInference(ObsCorrectionFeatures.DIM, 2);
        ObsCorrectorImpl impl = new ObsCorrectorImpl(stub, null);
        impl.correct(ctx(1, DT, 1, sat(3, 0x01, 45.0, 0, 1.0), sat(11, 0x08, 20.0, 0, 1.0)));
        assertEquals(2, impl.windowSize());
        impl.correct(ctx(2, DT, 1, sat(3, 0x01, 45.0, 0, 1.0)));
        assertEquals(1, impl.windowSize(), "消失卫星的窗口必须释放");
    }

    @Test
    @DisplayName("契约违规 fail-fast：输入维必须 17、输出维必须 2")
    void shapeContractViolations() {
        assertThrows(IllegalArgumentException.class,
                () -> new ObsCorrectorImpl(new StubInference(6, 2), null), "输入维 6 是接口一的");
        assertThrows(IllegalArgumentException.class,
                () -> new ObsCorrectorImpl(new StubInference(ObsCorrectionFeatures.DIM, 1), null),
                "只输出 bias 属契约违规（§4.3.4）");
        assertThrows(IllegalArgumentException.class, () -> new ObsCorrectorImpl(null, null));
    }

    @Test
    @DisplayName("模型输出长度不符 → 抛异常（由 core 降级为不干预，绝不按错行施加）")
    void outputLengthMismatchThrows() {
        StubInference stub = new StubInference(ObsCorrectionFeatures.DIM, 2);
        stub.forcedOut = new float[]{0.1f};
        ObsCorrectorImpl impl = new ObsCorrectorImpl(stub, null);
        assertThrows(IllegalStateException.class,
                () -> impl.correct(ctx(1, DT, 1, sat(3, 0x01, 45.0, 0, 1.0))));
    }

    @Test
    @DisplayName("后端异常向上抛出（core 捕获后按不干预处理）")
    void backendFailurePropagates() {
        StubInference stub = new StubInference(ObsCorrectionFeatures.DIM, 2);
        stub.toThrow = new IllegalStateException("onnx boom");
        ObsCorrectorImpl impl = new ObsCorrectorImpl(stub, null);
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> impl.correct(ctx(1, DT, 1, sat(3, 0x01, 45.0, 0, 1.0))));
        assertNotNull(e.getCause(), "必须保留后端原始异常");
    }

    @Test
    @DisplayName("非法上下文 → 直接不干预（不推理、不抛异常）")
    void invalidContextReturnsNone() {
        StubInference stub = new StubInference(ObsCorrectionFeatures.DIM, 2);
        ObsCorrectorImpl impl = new ObsCorrectorImpl(stub, null);

        CodeCorrection c = impl.correct(new ObsCorrectionContext(1, 0, 2, DT, new SatFeature[0]));
        assertEquals(CodeCorrection.NONE, c);

        CodeCorrection c2 = impl.correct(new ObsCorrectionContext(2, 2, 2, DT,
                new SatFeature[]{sat(3, 0x01, 45.0, 0, 1.0)}));
        assertEquals(CodeCorrection.NONE, c2, "sats 长度 != ns 视为非法");
        assertTrue(stub.lastRows == 0, "非法上下文不得触发推理");
    }

    @Test
    @DisplayName("毒值兜底：NaN 残差不入模（归一化后仍有限）")
    void nonFiniteFeaturesDoNotReachModel() {
        StubInference stub = new StubInference(ObsCorrectionFeatures.DIM, 2);
        ObsCorrectorImpl impl = new ObsCorrectorImpl(stub, FeatureNorm.identity(ObsCorrectionFeatures.DIM));
        impl.correct(ctx(1, DT, 1, sat(3, 0x01, 45.0, 0, Double.NaN)));

        for (int i = 0; i < stub.lastBatch.length; i++) {
            assertTrue(Float.isFinite(stub.lastBatch[i]), "第 " + i + " 维仍非有限");
        }
        assertEquals(0.0, feature(stub, 0, ObsCorrectionFeatures.IDX_RESP), 1e-12);
    }

    @Test
    @DisplayName("恒等归一化与维数无关（无参数即无错位风险），带参数且维不符才 fail-fast")
    void normDimensionMismatchFailsFast() {
        assertDoesNotThrow(() -> new ObsCorrectorImpl(
                new StubInference(ObsCorrectionFeatures.DIM, 2),
                FeatureNorm.identity(6)),
                "identity 归一化没有 mean/std，维数不影响结果，不应拒绝");
    }

    @Test
    @DisplayName("reset 清空状态：跨会话重放不残留历史")
    void resetClearsState() {
        StubInference stub = new StubInference(ObsCorrectionFeatures.DIM, 2);
        ObsCorrectorImpl impl = new ObsCorrectorImpl(stub, null);
        impl.correct(ctx(5, DT, 1, sat(3, 0x01, 45.0, 0, 1.0)));
        impl.reset();
        assertEquals(0, impl.windowSize());
        impl.correct(ctx(6, DT, 1, sat(3, 0x01, 45.0, 0, 1.0)));
        assertEquals(0.2, histLen(stub, 0), 1e-6, "reset 后首历元");
    }

    @Test
    @DisplayName("单历元输出只读：不改写入参快照（core 只读契约）")
    void inputSnapshotNotMutated() {
        SatFeature sf = sat(3, 0x01, 45.0, 0, 1.0);
        double[] before = sf.resp().clone();
        StubInference stub = new StubInference(ObsCorrectionFeatures.DIM, 2);
        ObsCorrectorImpl impl = new ObsCorrectorImpl(stub, null);
        impl.correct(ctx(1, DT, 1, sf));
        assertArrayEquals(before, sf.resp(), 0.0, "impl 不得写入 core 分配的数组");
    }
}