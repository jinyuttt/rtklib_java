


































package org.rtklib.java.adjust;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.adjust.engine.MultiBaselineAdjust;
import org.rtklib.java.adjust.model.AdjustResult;
import org.rtklib.java.adjust.model.MultiBaselineConfig;
import org.rtklib.java.adjust.model.ReliabilityGrade;
import org.rtklib.java.data.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 准入门限 floatAgreementThreshold 的受控扫描测试。
 *
 * <p>真实数据中"1 FIX + FLOAT"的历元极稀少，无法回答"0.20 m 阈值会不会
 * 误杀有效补位"。本测试用合成的 1FIX+FLOAT 历元，让 FLOAT 与 FIX 的位置较差
 * 逐档跨过阈值，直接检验四件事：</p>
 * <ol>
 *   <li>准入与等级是否为同一单调映射（无"准入但 UNVERIFIED"的中间态）；</li>
 *   <li>被拒 FLOAT 是否显式带出到 {@link AdjustResult#rejectedFloats}；</li>
 *   <li>拒绝后 {@code crossValidationDist} 是否为 NaN 而非 0.0；</li>
 *   <li>门限生效时保护级 PL 的实际变化幅度（参与基线 2 → 1）。</li>
 * </ol>
 */
class FloatAgreementThresholdSweepTest {

    private static final double P01_X = -2267749.0;
    private static final double P01_Y = 5009154.0;
    private static final double P01_Z = 3221290.0;

    private static final String BASE_A = "A";
    private static final String BASE_B = "B";

    private static final double FIX_COV_DIAG = 1.0e-6;
    private static final double FLOAT_COV_DIAG = 1.0e-4;

    private static final double THRESHOLD = 0.20;

    @Test
    @DisplayName("门限扫描: 准入<=>SINGLE_VERIFIED，拒绝<=>UNVERIFIED")
    void testSweep() {
        double[] offsets = {0.02, 0.10, 0.19, 0.21, 0.50, 4.80};

        System.out.printf("=== 准入门限扫描 floatAgreementThreshold=%.2fm ===%n", THRESHOLD);
        System.out.println("  较差(m)  判定  k  等级             crossVal(m)  PL(m)"
                + "    对照(阈值10m) k  等级             PL(m)");

        for (double off : offsets) {
            AdjustResult gated = adjustOneEpoch(off, THRESHOLD);
            AdjustResult ungated = adjustOneEpoch(off, 10.0);

            boolean admitted = gated.usedBaselineCount == 2;
            boolean verified = gated.reliabilityGrade == ReliabilityGrade.SINGLE_VERIFIED;

            System.out.printf("  %6.3f   %-4s %d  %-15s %-11s %-8.4f %d  %-15s %.4f%n",
                    off,
                    admitted ? "准入" : "拒绝",
                    gated.usedBaselineCount,
                    gated.reliabilityGrade,
                    Double.isNaN(gated.crossValidationDist)
                            ? "NaN" : String.format("%.4f", gated.crossValidationDist),
                    gated.protectionLevel,
                    ungated.usedBaselineCount,
                    ungated.reliabilityGrade,
                    ungated.protectionLevel);

            assertEquals(admitted, verified,
                    "准入与等级必须是同一单调映射：较差=" + off + "m，准入=" + admitted
                            + "，等级=" + gated.reliabilityGrade);

            if (off <= THRESHOLD) {
                assertEquals(2, gated.usedBaselineCount, "未超阈值应准入，较差=" + off);
                assertEquals(ReliabilityGrade.SINGLE_VERIFIED, gated.reliabilityGrade,
                        "准入时应判 SINGLE_VERIFIED，较差=" + off);
                assertEquals(off, gated.crossValidationDist, 1e-3,
                        "准入时 crossValidationDist 应为 FLOAT-FIX 较差，较差=" + off);
                assertTrue(gated.rejectedFloats.isEmpty(),
                        "准入时不应有拒绝记录，较差=" + off);
                assertEquals(ungated.protectionLevel, gated.protectionLevel, 1e-12,
                        "门限未触发时两配置必须给出相同 PL，较差=" + off);
            } else {
                assertEquals(1, gated.usedBaselineCount, "超阈值应拒绝，较差=" + off);
                assertEquals(ReliabilityGrade.UNVERIFIED, gated.reliabilityGrade,
                        "拒绝时应判 UNVERIFIED，较差=" + off);
                assertTrue(Double.isNaN(gated.crossValidationDist),
                        "拒绝后无参照物，crossValidationDist 应为 NaN 而非 0.0，较差=" + off);
                assertEquals(1, gated.rejectedFloats.size(),
                        "被拒 FLOAT 必须显式带出，不得静默丢弃，较差=" + off);
                AdjustResult.RejectedFloat r = gated.rejectedFloats.get(0);
                assertEquals(BASE_B, r.baseId(), "拒绝记录应含被拒基站");
                assertEquals(off, r.distanceToFix(), 1e-3, "拒绝记录应含实际较差");
                assertEquals(THRESHOLD, r.threshold(), 1e-12, "拒绝记录应含阈值");
            }
        }
    }

    /**
     * 合成一个"1 FIX + 1 FLOAT"历元并跑完整准入链路。
     *
     * @param floatOffset FLOAT 相对 FIX 的 ECEF 位置偏移 (m)
     * @param threshold   准入门限 (m)
     * @return 该历元的平差结果
     */
    private static AdjustResult adjustOneEpoch(double floatOffset, double threshold) {
        MultiBaselineConfig cfg = MultiBaselineConfig.builder()
                .baseStationIds(BASE_A, BASE_B)
                .solutionAdmission(MultiBaselineConfig.SolutionAdmissionConfig.builder()
                        .allowFloat(true)
                        .floatAgreementThreshold(threshold)
                        .build())
                .build();

        MultiBaselineAdjust adj = MultiBaselineAdjust.create(cfg);
        AdjustResult[] captured = new AdjustResult[1];
        adj.onEpochResult((coord, detail) -> {
            if ("t0".equals(coord.epochTag())) captured[0] = detail;
        });

        adj.feedBaseline(BASE_A, sol(P01_X, P01_Y, P01_Z, SolutionStatus.FIX, FIX_COV_DIAG), "t0");
        adj.feedBaseline(BASE_B, sol(P01_X + floatOffset, P01_Y, P01_Z,
                SolutionStatus.FLOAT, FLOAT_COV_DIAG), "t0");
        adj.feedBaseline(BASE_A, sol(P01_X, P01_Y, P01_Z, SolutionStatus.FIX, FIX_COV_DIAG), "t1");
        adj.flush();

        assertNotNull(captured[0], "历元 t0 应产生平差结果");
        return captured[0];
    }

    private static SolData sol(double x, double y, double z,
                               SolutionStatus status, double covDiag) {
        Sol sol = new Sol();
        sol.time = new GTime();
        sol.rr = new double[]{x, y, z, 0, 0, 0};
        sol.qr = new float[]{(float) covDiag, (float) covDiag, (float) covDiag, 0f, 0f, 0f};
        sol.stat = (byte) status.code;
        sol.ns = (byte) 12;
        sol.ratio = 5.0f;
        sol.dtr = new double[]{0};
        sol.qv = new float[]{0, 0, 0, 0, 0, 0};
        return new SolData(sol, PrcOpt.POS_ECEF);
    }
}