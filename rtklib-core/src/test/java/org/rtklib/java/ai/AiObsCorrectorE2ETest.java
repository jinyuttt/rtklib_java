package org.rtklib.java.ai;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.rtklib.java.TestDataConfig;
import org.rtklib.java.ai.spi.AiHooks;
import org.rtklib.java.ai.spi.AiProvider;
import org.rtklib.java.ai.spi.CodeCorrection;
import org.rtklib.java.ai.spi.FixValidator;
import org.rtklib.java.ai.spi.ObsCorrectionContext;
import org.rtklib.java.ai.spi.ObsCorrector;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.CoordType;
import org.rtklib.java.data.Position;
import org.rtklib.java.data.PrcOpt;
import org.rtklib.java.data.SolData;
import org.rtklib.java.rinex.RinexRtkProcessor;
import org.rtklib.java.rtkpos.RtkProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 接口三（伪距偏差改正）端到端注入验证：真实公开 RINEX 数据 + 真实 relpos 解算。
 * <p>
 * 本类只使用 core 侧 SPI 与桩实现，不依赖 {@code rtklib-ai}，用于独立验证三件事：
 * <ol>
 *   <li><b>I-1 零回归</b>：注入 provider 但接口三返回 {@code null} 时，解算结果与完全不注入逐位一致；</li>
 *   <li><b>改正确实生效</b>：注入确定性偏差后，解算位置发生可观测变化；</li>
 *   <li><b>降级绝不 fail-closed</b>：抛异常、越界、非法长度、方差 {@code < 1.0} 时全部回退到「不干预」。</li>
 * </ol>
 * 另外验证 IFLC 模式下 core 侧统一门禁：接口三一次都不应被调用。
 * </p>
 * <p>
 * 数据源：Net_Diff 公开短基线（199m，G+E+J+C，rover 单频 L1）与 cssrlib 公开中基线
 * （5.3km，G+E+J 双频），均为仓库 {@code test-data/} 内置的公开数据集。
 * </p>
 */
@DisplayName("接口三端到端：公开 RINEX 数据注入验证")
@Tag("manual")
class AiObsCorrectorE2ETest {

    private static final Logger log = LoggerFactory.getLogger(AiObsCorrectorE2ETest.class);

    private static final String TD = TestDataConfig.getTestDataDir();

    private static final String URB_ROVER = TD + "/rinex/urban_rover_20000719.17o";
    private static final String URB_BASE = TD + "/rinex/urban_base_20000719.17o";
    private static final String URB_NAV = TD + "/nav/urban_20000719.17p";

    private static final String GEJ_ROVER = TD + "/rinex/rinex304_gej_3034.21o";
    private static final String GEJ_BASE = TD + "/rinex/rinex304_gej_sept.21o";
    private static final String GEJ_NAV = TD + "/nav/rinex304_mixed_sept.21p";

    private static boolean urbAvail;
    private static boolean gejAvail;
    private static RtkProcessor.RtkResult baseline;

    @BeforeAll
    static void setup() {
        urbAvail = Files.exists(Paths.get(URB_ROVER))
                && Files.exists(Paths.get(URB_BASE))
                && Files.exists(Paths.get(URB_NAV));
        gejAvail = Files.exists(Paths.get(GEJ_ROVER))
                && Files.exists(Paths.get(GEJ_BASE))
                && Files.exists(Paths.get(GEJ_NAV));
        if (!urbAvail) {
            log.warn("urban 公开数据缺失，跳过接口三端到端验证");
            return;
        }
        AiHooks.clear();
        baseline = RinexRtkProcessor.processRinex(URB_ROVER, URB_BASE, URB_NAV, urbanOpt(Constants.IONOOPT_BRDC));
        log.info("baseline（未注入 AI）: total={} success={} fail={}",
                baseline.totalEpochs, baseline.successCount, baseline.failCount);
    }

    @AfterEach
    void clearHook() {
        AiHooks.clear();
    }

    @AfterAll
    static void clearAfterAll() {
        AiHooks.clear();
    }

    private static PrcOpt urbanOpt(int ionoopt) {
        PrcOpt opt = RinexRtkProcessor.createDefaultOpt();
        opt.mode = Constants.PMODE_KINEMA;
        opt.nf = 1;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_QZS | Constants.SYS_CMP;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = ionoopt;
        opt.tropopt = Constants.TROPOPT_SAAS;
        opt.modear = Constants.ARMODE_FIXHOLD;
        opt.refpos = Constants.POSOPT_POS_XYZ;
        return opt;
    }

    private static PrcOpt gejOpt() {
        PrcOpt opt = RinexRtkProcessor.createDefaultOpt();
        opt.mode = Constants.PMODE_STATIC;
        opt.nf = 2;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_QZS;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_IFLC;
        opt.tropopt = Constants.TROPOPT_SAAS;
        opt.modear = Constants.ARMODE_FIXHOLD;
        opt.refpos = Constants.POSOPT_POS_XYZ;
        return opt;
    }

    private static RtkProcessor.RtkResult runUrban(ObsCorrector corrector) {
        AiHooks.install(new AiProvider() {
            @Override
            public FixValidator fixValidator() {
                return null;
            }

            @Override
            public ObsCorrector obsCorrector() {
                return corrector;
            }
        });
        return RinexRtkProcessor.processRinex(URB_ROVER, URB_BASE, URB_NAV, urbanOpt(Constants.IONOOPT_BRDC));
    }

    /** 两个结果在 ECEF 上的最大分量差（仅比较双方都有解的历元）。 */
    private static double maxEcefDiff(RtkProcessor.RtkResult a, RtkProcessor.RtkResult b) {
        List<SolData> sa = a.solutions;
        List<SolData> sb = b.solutions;
        int n = Math.min(sa.size(), sb.size());
        double max = 0.0;
        for (int k = 0; k < n; k++) {
            Position pa = sa.get(k).getPosition(CoordType.ECEF);
            Position pb = sb.get(k).getPosition(CoordType.ECEF);
            if (pa == null || pb == null) continue;
            max = Math.max(max, Math.abs(pa.v1 - pb.v1));
            max = Math.max(max, Math.abs(pa.v2 - pb.v2));
            max = Math.max(max, Math.abs(pa.v3 - pb.v3));
        }
        return max;
    }

    private static void assertBitIdentical(RtkProcessor.RtkResult actual, String what) {
        assertEquals(baseline.totalEpochs, actual.totalEpochs, what + "：历元总数必须一致");
        assertEquals(baseline.successCount, actual.successCount, what + "：成功历元数必须一致");
        assertEquals(0.0, maxEcefDiff(baseline, actual), what + "：解算结果必须与基线逐位一致（I-1）");
    }

    @Test
    @DisplayName("I-1：注入 provider 但接口三返回 null，解算逐位一致")
    void testNullCorrectorIsBitIdentical() {
        Assumptions.assumeTrue(urbAvail, "urban 公开数据缺失");
        RtkProcessor.RtkResult r = runUrban(null);
        assertBitIdentical(r, "接口三返回 null");
        log.info("I-1 通过：未提供接口三时与 v3.4.0 逐位一致");
    }

    @Test
    @DisplayName("改正生效：±3m 确定性伪距偏差应显著改变解算位置")
    void testDeterministicBiasChangesSolution() {
        Assumptions.assumeTrue(urbAvail, "urban 公开数据缺失");
        RecordingCorrector c = new RecordingCorrector((i, nf, frq) -> (i % 2 == 0 ? 3.0 : -3.0), 1.0);
        RtkProcessor.RtkResult r = runUrban(c);

        assertTrue(c.calls > 0, "接口三应至少被调用一次");
        assertEquals(baseline.totalEpochs, r.totalEpochs, "历元总数必须一致");
        assertEquals(0, c.contractViolations, "core 必须提供 sats 长度 == ns 的合法快照");
        assertEquals(0, c.nonFiniteOutputs, "桩输出应全为有限值");
        assertTrue(c.maxNs >= 4, "参与双差的卫星数应 ≥ 4，实际 " + c.maxNs);
        assertEquals(1, c.seenNf, "单频配置下 ctx.nf 必须为 1");

        double diff = maxEcefDiff(baseline, r);
        log.info("注入 ±3m 伪距偏差：调用 {} 次，ECEF 最大变化 {} m，success={}",
                c.calls, String.format("%.4f", diff), r.successCount);
        assertTrue(diff > 0.5, "改正后解算位置应发生可观测变化，实际最大变化 " + diff + " m");
        assertFiniteSolutions(r);
    }

    @Test
    @DisplayName("降级：抛异常 / 非法输出长度 → 不干预，且与基线逐位一致")
    void testDegradeNeverFailsClosed() {
        Assumptions.assumeTrue(urbAvail, "urban 公开数据缺失");
        RecordingCorrector c = new RecordingCorrector(null, 1.0);
        c.mode = RecordingCorrector.Mode.THROW_ON_ODD_EPOCH;
        RtkProcessor.RtkResult r = runUrban(c);

        assertTrue(c.calls > 0, "接口三应至少被调用一次");
        assertBitIdentical(r, "异常/非法输出降级");
        log.info("降级路径通过：{} 次调用（奇历元抛异常、偶历元返回错长数组），结果与基线一致", c.calls);
    }

    @Test
    @DisplayName("钳位：偏差超限丢弃、方差 < 1.0 抬回 1.0 → 不干预，且与基线逐位一致")
    void testClampRulesAreBitIdentical() {
        Assumptions.assumeTrue(urbAvail, "urban 公开数据缺失");
        RecordingCorrector c = new RecordingCorrector((i, nf, frq) -> 1e9, 0.3);
        RtkProcessor.RtkResult r = runUrban(c);

        assertTrue(c.calls > 0, "接口三应至少被调用一次");
        assertBitIdentical(r, "超限偏差 + 欠膨胀方差钳位");
        log.info("钳位路径通过：偏差 1e9 m 全部超限丢弃、方差 0.3 全部抬回 1.0，结果与基线一致");
    }

    @Test
    @DisplayName("IFLC 门禁：core 侧统一屏蔽接口三，一次都不调用")
    void testIflicGateSkipsObsCorrector() {
        Assumptions.assumeTrue(gejAvail, "G+E+J 公开数据缺失");
        RecordingCorrector c = new RecordingCorrector((i, nf, frq) -> 0.5, 1.0);
        AiHooks.install(new AiProvider() {
            @Override
            public FixValidator fixValidator() {
                return null;
            }

            @Override
            public ObsCorrector obsCorrector() {
                return c;
            }
        });
        RtkProcessor.RtkResult r = RinexRtkProcessor.processRinex(GEJ_ROVER, GEJ_BASE, GEJ_NAV, gejOpt());
        log.info("IFLC 解算：total={} success={}，接口三调用 {} 次", r.totalEpochs, r.successCount, c.calls);
        assertTrue(r.totalEpochs > 0, "IFLC 模式下应正常处理历元");
        assertTrue(r.successCount > 0, "IFLC 模式下应有解");
        assertEquals(0, c.calls, "IFLC 模式下接口三必须被 core 侧门禁屏蔽");
    }

    private static void assertFiniteSolutions(RtkProcessor.RtkResult r) {
        int n = 0;
        for (SolData sd : r.solutions) {
            Position p = sd.getPosition(CoordType.ECEF);
            if (p == null) continue;
            assertTrue(Double.isFinite(p.v1) && Double.isFinite(p.v2) && Double.isFinite(p.v3),
                    "解算位置必须为有限值：" + sd.timeStr);
            n++;
        }
        assertTrue(n > 0, "应至少有解");
    }

    /** 记录调用次数并校验 SPI 契约（长度、行序、有限性）的桩实现。 */
    private static final class RecordingCorrector implements ObsCorrector {

        enum Mode { NORMAL, THROW_ON_ODD_EPOCH }

        interface BiasFn {
            double apply(int satIndex, int nf, int frq);
        }

        private final BiasFn biasFn;
        private final double scale;
        Mode mode = Mode.NORMAL;

        int calls;
        int maxNs;
        int seenNf;
        int contractViolations;
        int nonFiniteOutputs;

        RecordingCorrector(BiasFn biasFn, double scale) {
            this.biasFn = biasFn;
            this.scale = scale;
        }

        @Override
        public CodeCorrection correct(ObsCorrectionContext ctx) {
            calls++;
            if (mode == Mode.THROW_ON_ODD_EPOCH) {
                if (ctx.epoch() % 2 == 1) {
                    throw new IllegalStateException("桩实现故意抛出异常（历元 " + ctx.epoch() + "）");
                }
                return new CodeCorrection(new double[ctx.ns() * ctx.nf() + 1],
                        new double[ctx.ns() * ctx.nf() + 1]);
            }
            maxNs = Math.max(maxNs, ctx.ns());
            seenNf = ctx.nf();
            if (ctx.sats() == null || ctx.sats().length != ctx.ns()) {
                contractViolations++;
                return CodeCorrection.NONE;
            }
            int rows = ctx.ns() * ctx.nf();
            double[] bias = new double[rows];
            double[] scaleArr = new double[rows];
            for (int i = 0; i < ctx.ns(); i++) {
                for (int frq = 0; frq < ctx.nf(); frq++) {
                    bias[i * ctx.nf() + frq] = biasFn == null ? 0.0 : biasFn.apply(i, ctx.nf(), frq);
                    scaleArr[i * ctx.nf() + frq] = scale;
                }
            }
            for (double v : bias) if (!Double.isFinite(v)) nonFiniteOutputs++;
            for (double v : scaleArr) if (!Double.isFinite(v)) nonFiniteOutputs++;
            return new CodeCorrection(bias, scaleArr);
        }
    }
}