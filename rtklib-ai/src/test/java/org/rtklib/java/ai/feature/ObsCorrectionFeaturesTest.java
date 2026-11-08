package org.rtklib.java.ai.feature;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.ai.spi.SatFeature;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接口三特征提取自洽测试（设计文档 §5.3）。
 * <p>
 * 与接口一同类风险：<b>列序错位</b>（训练列名与推理顺序对不上，模型再好也是静默错行）
 * 与<b>毒值入模</b>（{@code NaN} 会污染整批 {@code ns×nf} 行输出）。
 * 另外验证 {@code sysId} 必须是尾随零而非位掩码原值。
 * </p>
 */
@DisplayName("接口三特征提取：17 维列序与退化兜底")
class ObsCorrectionFeaturesTest {

    private static SatFeature sat(int sat, int sys, double elDeg, double azDeg) {
        return new SatFeature(sat, sys, Math.toRadians(azDeg), Math.toRadians(elDeg),
                new double[]{45.0, 40.0}, new double[]{42.0, 38.0},
                new double[]{1.5, 2.5}, new double[]{0.02, 0.03},
                new int[]{100, 90}, new int[]{0, 1}, new int[]{0, 0},
                new double[]{0.5, 0.6}, new int[]{1, 1}, new int[]{2, 1});
    }

    @Test
    @DisplayName("17 维列序定死：el/az/snr/snr/resp/resc/lock/slip/half/gf/vsat/fix/sys/窗口四列")
    void featureOrderIsFixed() {
        ObsCorrectionFeatures.WindowAgg agg = new ObsCorrectionFeatures.WindowAgg(1.5, 0.7, 2.5, 3);
        double[] f = ObsCorrectionFeatures.extractRow(sat(3, 0x01, 30.0, 120.0), 1, agg);

        assertEquals(ObsCorrectionFeatures.DIM, f.length, "维数必须是 17");
        assertEquals(30.0, f[ObsCorrectionFeatures.IDX_EL], 1e-9, "el 弧度转度");
        assertEquals(120.0, f[ObsCorrectionFeatures.IDX_AZ], 1e-9, "az 弧度转度");
        assertEquals(40.0, f[ObsCorrectionFeatures.IDX_SNR_ROVER], 1e-12, "frq=1 取下标 1");
        assertEquals(38.0, f[ObsCorrectionFeatures.IDX_SNR_BASE], 1e-12);
        assertEquals(2.5, f[ObsCorrectionFeatures.IDX_RESP], 1e-12);
        assertEquals(0.03, f[ObsCorrectionFeatures.IDX_RESC], 1e-12);
        assertEquals(90.0, f[ObsCorrectionFeatures.IDX_LOCK], 1e-12);
        assertEquals(1.0, f[ObsCorrectionFeatures.IDX_SLIP], 0.0, "位标志归一为 1");
        assertEquals(0.0, f[ObsCorrectionFeatures.IDX_HALF], 0.0);
        assertEquals(0.6, f[ObsCorrectionFeatures.IDX_GF], 1e-12);
        assertEquals(1.0, f[ObsCorrectionFeatures.IDX_VSAT], 0.0);
        assertEquals(1.0, f[ObsCorrectionFeatures.IDX_FIX], 0.0);
        assertEquals(0.0, f[ObsCorrectionFeatures.IDX_SYS_ID], 0.0, "GPS 0x01 → 0");
        assertEquals(1.5, f[ObsCorrectionFeatures.IDX_RESP_WIN_MEAN], 1e-12);
        assertEquals(0.7, f[ObsCorrectionFeatures.IDX_RESP_WIN_STD], 1e-12);
        assertEquals(2.5, f[ObsCorrectionFeatures.IDX_RESC_WIN_MEAN], 1e-12);
        assertEquals(0.6, f[ObsCorrectionFeatures.IDX_HIST_LEN], 1e-12, "3/5 = 0.6");
    }

    @Test
    @DisplayName("sysId 取尾随零：不得直接喂位掩码（0x04 与 0x08 数值无序关系）")
    void sysIdIsTrailingZerosNotBitmask() {
        assertEquals(0.0, ObsCorrectionFeatures.sysId(0x01), 0.0, "GPS");
        assertEquals(2.0, ObsCorrectionFeatures.sysId(0x04), 0.0, "GLO");
        assertEquals(3.0, ObsCorrectionFeatures.sysId(0x08), 0.0, "GAL");
        assertEquals(4.0, ObsCorrectionFeatures.sysId(0x10), 0.0, "QZS");
        assertEquals(0.0, ObsCorrectionFeatures.sysId(0), 0.0, "未知系统归零");
    }

    @Test
    @DisplayName("histLen 边界：空窗口 0.0，满窗 1.0，永不超过 1.0")
    void histLenBounds() {
        assertEquals(0.0, ObsCorrectionFeatures.extractRow(
                sat(1, 0x01, 30.0, 10.0), 0, ObsCorrectionFeatures.WindowAgg.EMPTY)
                [ObsCorrectionFeatures.IDX_HIST_LEN], 0.0);
        assertEquals(0.2, ObsCorrectionFeatures.extractRow(
                sat(1, 0x01, 30.0, 10.0), 0, new ObsCorrectionFeatures.WindowAgg(0, 0, 0, 1))
                [ObsCorrectionFeatures.IDX_HIST_LEN], 1e-12, "首历元按 1 个样本计");
        assertEquals(1.0, ObsCorrectionFeatures.extractRow(
                sat(1, 0x01, 30.0, 10.0), 0, new ObsCorrectionFeatures.WindowAgg(0, 0, 0, 5))
                [ObsCorrectionFeatures.IDX_HIST_LEN], 1e-12);
    }

    @Test
    @DisplayName("NaN/Inf 一律归零：毒值不得进入模型污染整批输出")
    void nonFiniteIsSanitized() {
        SatFeature bad = new SatFeature(1, 0x01, Double.NaN, Double.POSITIVE_INFINITY,
                new double[]{Double.NaN}, new double[]{Double.NEGATIVE_INFINITY},
                new double[]{Double.NaN}, new double[]{Double.NaN},
                new int[]{0}, new int[]{0}, new int[]{0},
                new double[]{Double.NaN}, new int[]{0}, new int[]{0});
        double[] f = ObsCorrectionFeatures.extractRow(bad, 0,
                new ObsCorrectionFeatures.WindowAgg(Double.NaN, Double.NaN, Double.NaN, 1));
        for (int i = 0; i < f.length; i++) {
            assertTrue(Double.isFinite(f[i]), "第 " + i + " 维仍非有限: " + f[i]);
        }
    }

    @Test
    @DisplayName("数组越界/为 null 按缺失处理（不得抛异常打断整历元）")
    void outOfRangePicksZero() {
        SatFeature shortArrays = new SatFeature(1, 0x01, 0.5, 0.5,
                new double[]{45.0}, null, null, null,
                new int[]{10}, new int[]{0}, new int[]{0},
                null, new int[]{1}, new int[]{0});
        double[] f = ObsCorrectionFeatures.extractRow(shortArrays, 3,
                ObsCorrectionFeatures.WindowAgg.EMPTY);
        assertEquals(0.0, f[ObsCorrectionFeatures.IDX_SNR_ROVER], 0.0, "越界按缺失");
        assertEquals(0.0, f[ObsCorrectionFeatures.IDX_SNR_BASE], 0.0, "null 数组按缺失");
        assertEquals(0.0, f[ObsCorrectionFeatures.IDX_LOCK], 0.0);
    }
}