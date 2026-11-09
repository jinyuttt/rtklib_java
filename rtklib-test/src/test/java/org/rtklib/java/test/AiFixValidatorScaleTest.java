package org.rtklib.java.test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.TestDataConfig;
import org.rtklib.java.ai.OnnxAiProvider;
import org.rtklib.java.ai.spi.AiHooks;
import org.rtklib.java.ai.spi.AiProvider;
import org.rtklib.java.ai.spi.CodeCorrection;
import org.rtklib.java.ai.spi.FixValidator;
import org.rtklib.java.ai.spi.ObsCorrector;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.PrcOpt;
import org.rtklib.java.data.SolData;
import org.rtklib.java.data.SolOpt;
import org.rtklib.java.data.SolutionStatus;
import org.rtklib.java.rinex.RinexRtkProcessor;
import org.rtklib.java.rtkpos.RtkProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 阶段 2 规模验证：24h 真实静态数据（HKWS-HKSS，基线约 6.8 km，30 s 采样）上的接口一接线。
 * <p>
 * 与 {@link AiFixValidatorFlowTest} 的分工：那个类用 60 历元的短数据快速验证接线语义
 * （日常构建用），本类用约 2880 历元的全天数据给出统计上可信的证据——
 * 短数据里只有 1 个固定解，「全拒绝后 fix 1→0」几乎等于没验证。
 * </p>
 * <p>
 * <b>模型仍是 dummy（随机权重）</b>，故本类只回答「接线在真实数据、真实规模上是否按设计工作」，
 * 不回答「AI 能否识别假固定」——后者需要阶段 3/4 的训练模型与真值标签。
 * 正因如此，所有断言都围绕<b>结构性质</b>（逐位零扰动、只否决不创造）而非模型质量。
 * </p>
 */
@DisplayName("阶段2 规模验证：24h 真实数据（HKWS-HKSS）上的接口一接线")
class AiFixValidatorScaleTest {

    private static final Logger log = LoggerFactory.getLogger(AiFixValidatorScaleTest.class);

    private static final Path MODEL = Paths.get(
            "..", "rtklib-ai", "src", "test", "resources", "models", "dummy_fix_validator.onnx");

    private static String roverObs;
    private static String baseObs;
    private static String navFile;
    private static String atxPath;

    private static RtkProcessor.RtkResult baseline;
    private static RtkProcessor.RtkResult acceptAll;
    private static RtkProcessor.RtkResult defaultThreshold;
    private static RtkProcessor.RtkResult rejectAll;

    private static long baselineFix;

    @BeforeAll
    static void runAllConfigurations() {
        String rtkDir = TestDataConfig.getTestDataDir() + File.separator
                + "gnss-validation" + File.separator + "rtk";
        String shortDir = rtkDir + File.separator + "short-baseline" + File.separator + "hkws-hkss";

        roverObs = findObs(shortDir, "HKWS");
        baseObs = findObs(shortDir, "HKSS");
        navFile = findNav(rtkDir + File.separator + "brdc");
        atxPath = findAtx(TestDataConfig.getTestDataDir() + File.separator
                + "gnss-validation" + File.separator + "table");

        assumeTrue(roverObs != null, "24h HKWS 观测文件不可用: " + shortDir);
        assumeTrue(baseObs != null, "24h HKSS 观测文件不可用: " + shortDir);
        assumeTrue(navFile != null, "BRDC 导航文件不可用");
        assumeTrue(Files.exists(MODEL), "dummy 模型不可用: " + MODEL.toAbsolutePath());

        log.info("规模验证数据: rover={}, base={}, nav={}",
                new File(roverObs).getName(), new File(baseObs).getName(),
                new File(navFile).getName());

        baseline = timed("基线（不注入）", null, new RtkConfig());
        baselineFix = SolBitwise.countFix(baseline);

        try (OnnxAiProvider provider = new OnnxAiProvider(MODEL, null)) {
            assumeTrue(provider.isFixValidatorAvailable(), "dummy 模型加载失败");

            RtkConfig all = new RtkConfig();
            all.ai.fixValidatorMinConfidence = 0.0;
            acceptAll = timed("注入+阈值0.0（全接受）", provider, all);

            defaultThreshold = timed("注入+默认阈值0.5", provider, new RtkConfig());
        }

        rejectAll = timed("桩校验器恒返回0.0（全拒绝）", alwaysRejectProvider(), new RtkConfig());
        AiHooks.clear();

        log.info("=== 规模验证汇总（历元数 {}）===", baseline.totalEpochs);
        logSummary("基线（不注入）", baseline);
        logSummary("注入+阈值0.0（全接受）", acceptAll);
        logSummary("注入+默认阈值0.5", defaultThreshold);
        logSummary("桩校验器恒返回0.0（全拒绝）", rejectAll);
    }

    @AfterAll
    static void tearDown() {
        AiHooks.clear();
    }

    @Test
    @DisplayName("S0：全天数据里固定解数量足够，拒绝路径的验证才有统计意义")
    void baselineHasSubstantialFixes() {
        assertTrue(baseline.totalEpochs > 2000,
                "应处理约 2880 历元，实际 " + baseline.totalEpochs + "（数据可能不完整）");
        assertTrue(baselineFix > 100,
                "全天固定解应远超 100 个，实际 " + baselineFix
                        + "——样本过薄时「全拒绝」断言不具说服力");
        log.info("S0 通过: totalEpochs={}, fix={} ({}%)", baseline.totalEpochs, baselineFix,
                String.format("%.1f", 100.0 * baselineFix / baseline.solutions.size()));
    }

    @Test
    @DisplayName("S1：注入并全接受，全天解算与基线逐位一致（I-1 在真实规模上成立）")
    void acceptAllIsBitIdenticalOverFullDay() {
        assertEquals(baseline.totalEpochs, acceptAll.totalEpochs, "历元总数");
        SolBitwise.assertIdentical(baseline, acceptAll, "全接受");
        log.info("S1 通过: 特征提取+ONNX 推理在每个固定候选上真实执行，结果仍与基线逐位一致");
    }

    @Test
    @DisplayName("S2：全拒绝，全天无固定解且基线中每一个固定解都被压制")
    void rejectAllSuppressesEveryFix() {
        assertEquals(0, SolBitwise.countFix(rejectAll),
                "桩校验器恒拒绝时不得产出任何固定解");
        assertEquals(baseline.totalEpochs, rejectAll.totalEpochs, "历元总数不应改变");

        SolData[] sb = baseline.solutions.toArray(new SolData[0]);
        SolData[] sr = rejectAll.solutions.toArray(new SolData[0]);
        assertEquals(sb.length, sr.length, "解数量");

        int suppressed = 0;
        for (int i = 0; i < sb.length; i++) {
            if (sb[i].status == SolutionStatus.FIX) {
                suppressed++;
                assertTrue(sr[i].status != SolutionStatus.FIX,
                        "epoch#" + i + " " + sb[i].timeStr + " 未被压制");
            }
        }
        assertEquals(baselineFix, suppressed, "基线固定解应逐个被压制");
        log.info("S2 通过: {} 个固定解全部压制为浮点，历元总数不变", suppressed);
    }

    @Test
    @DisplayName("S3：默认阈值 0.5 下 dummy 模型真实参与判决，解算不被破坏")
    void defaultThresholdKeepsSolving() {
        assertEquals(baseline.totalEpochs, defaultThreshold.totalEpochs, "历元总数");
        assertTrue(defaultThreshold.successCount > 0, "开启接口一后不应破坏解算");
        long fix = SolBitwise.countFix(defaultThreshold);
        log.info("S3 通过: total={}, success={}, fix={}（dummy 权重无物理意义，此值仅说明判决链路在跑）",
                defaultThreshold.totalEpochs, defaultThreshold.successCount, fix);
    }

    private static AiProvider alwaysRejectProvider() {
        return new AiProvider() {
            @Override
            public FixValidator fixValidator() {
                return ctx -> 0.0;
            }

            @Override
            public ObsCorrector obsCorrector() {
                return ctx -> CodeCorrection.NONE;
            }
        };
    }

    private static RtkProcessor.RtkResult timed(String label, AiProvider provider, RtkConfig cfg) {
        AiHooks.clear();
        if (provider != null) {
            AiHooks.install(provider);
        }
        long t0 = System.nanoTime();
        RtkProcessor.RtkResult r = run(cfg);
        long ms = (System.nanoTime() - t0) / 1_000_000;
        log.info("{}: 耗时 {} ms, total={}, success={}, fix={}", label, ms, r.totalEpochs,
                r.successCount, SolBitwise.countFix(r));
        return r;
    }

    private static RtkProcessor.RtkResult run(RtkConfig cfg) {
        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_STATIC;
        opt.soltype = Constants.SOLTYPE_FORWARD;
        opt.nf = 2;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_EST;
        opt.modear = Constants.ARMODE_FIXHOLD;
        opt.refpos = Constants.POSOPT_SINGLE;
        opt.anttype[0] = "*";
        opt.anttype[1] = "*";

        cfg.rtk.enableSnrMedian = true;

        SolOpt solOpt = new SolOpt();
        solOpt.solstatic = 0;

        RinexRtkProcessor p = new RinexRtkProcessor(opt, solOpt, cfg, null, null);
        if (atxPath != null) {
            p.setAtxPath(atxPath);
        }
        return p.process(roverObs, baseObs, navFile);
    }

    private static void logSummary(String label, RtkProcessor.RtkResult r) {
        int n = r.solutions.size();
        double fixRate = n > 0 ? 100.0 * SolBitwise.countFix(r) / n : 0.0;
        log.info(String.format("  %-28s total=%-5d success=%-5d fix=%-5d (%5.1f%%)",
                label, r.totalEpochs, r.successCount, SolBitwise.countFix(r), fixRate));
    }

    private static String findObs(String dir, String station) {
        File d = new File(dir);
        File[] files = d.listFiles();
        if (files == null) return null;
        String prefix = station.toUpperCase();
        for (File f : files) {
            String name = f.getName().toUpperCase();
            if (name.startsWith(prefix) && (name.endsWith(".RNX") || name.endsWith(".O")
                    || name.endsWith(".OBS"))) {
                return f.getAbsolutePath();
            }
        }
        return null;
    }

    private static String findNav(String dir) {
        File d = new File(dir);
        File[] files = d.listFiles();
        if (files == null) return null;
        for (File f : files) {
            String name = f.getName().toUpperCase();
            if (name.contains("BRDC") && (name.endsWith(".RNX") || name.endsWith(".N"))) {
                return f.getAbsolutePath();
            }
        }
        return null;
    }

    private static String findAtx(String dir) {
        File d = new File(dir);
        File[] files = d.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.getName().toLowerCase().endsWith(".atx") && f.length() > 0) {
                return f.getAbsolutePath();
            }
        }
        return null;
    }
}