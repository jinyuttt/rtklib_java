package org.rtklib.java.test.gnssvalidation;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.rtklib.java.TestDataConfig;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.rinex.RinexRtkProcessor;
import org.rtklib.java.rtkpos.RtkProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("阶段2: 短/长基线RTK对照验证")
public class RtkValidationTest {

    private static final Logger log = LoggerFactory.getLogger(RtkValidationTest.class);

    private static final String DATA_DIR = TestDataConfig.getTestDataDir()
            + File.separator + "gnss-validation" + File.separator + "rtk";

    private static final String SHORT_DIR = DATA_DIR + File.separator + "short-baseline" + File.separator + "hkws-hkss";
    private static final String LONG_DIR = DATA_DIR + File.separator + "long-baseline" + File.separator + "hkws-hksl";

    private static String shortRoverObs, shortBaseObs, shortNav;
    private static String longRoverObs, longBaseObs, longNav;
    private static boolean shortDataAvailable;
    private static boolean longDataAvailable;

    static boolean shortDataAvailable() { return shortDataAvailable; }
    static boolean longDataAvailable() { return longDataAvailable; }

    @BeforeAll
    static void checkData() {
        shortDataAvailable = findRtkFiles(SHORT_DIR, "短基线HKWS-HKSS");
        longDataAvailable = findRtkFiles(LONG_DIR, "长基线HKWS-HKSL");

        if (shortDataAvailable) {
            shortRoverObs = findFile(SHORT_DIR, "rover", ".obs", ".25o", ".24o", ".23o", ".21o", ".18o");
            shortBaseObs = findFile(SHORT_DIR, "base", ".obs", ".25o", ".24o", ".23o", ".21o", ".18o");
            shortNav = findFile(SHORT_DIR, "", ".nav", ".25n", ".24n", ".23n", ".21n", ".18n");
            log.info("短基线数据: rover={}, base={}, nav={}", shortRoverObs, shortBaseObs, shortNav);
        }
        if (longDataAvailable) {
            longRoverObs = findFile(LONG_DIR, "rover", ".obs", ".25o", ".24o", ".23o", ".21o", ".18o");
            longBaseObs = findFile(LONG_DIR, "base", ".obs", ".25o", ".24o", ".23o", ".21o", ".18o");
            longNav = findFile(LONG_DIR, "", ".nav", ".25n", ".24n", ".23n", ".21n", ".18n");
            log.info("长基线数据: rover={}, base={}, nav={}", longRoverObs, longBaseObs, longNav);
        }
    }

    private static boolean findRtkFiles(String dir, String label) {
        File d = new File(dir);
        if (!d.exists() || !d.isDirectory()) {
            log.info("{}数据目录不存在: {}", label, dir);
            return false;
        }
        File[] files = d.listFiles();
        if (files == null || files.length == 0) {
            log.info("{}数据目录为空: {}", label, dir);
            return false;
        }
        return true;
    }

    private static String findFile(String dir, String prefix, String... extensions) {
        File d = new File(dir);
        File[] files = d.listFiles();
        if (files == null) return null;
        for (String ext : extensions) {
            for (File f : files) {
                if (f.getName().toLowerCase().endsWith(ext) && f.getName().toLowerCase().contains(prefix.toLowerCase())) {
                    return f.getAbsolutePath();
                }
            }
        }
        for (String ext : extensions) {
            for (File f : files) {
                if (f.getName().toLowerCase().endsWith(ext)) {
                    return f.getAbsolutePath();
                }
            }
        }
        return null;
    }

    private static PrcOpt createRtkOpt() {
        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_STATIC;
        opt.nf = 2;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_EST;
        opt.modear = Constants.ARMODE_FIXHOLD;
        opt.refpos = Constants.POSOPT_POS_XYZ;
        return opt;
    }

    @Test
    @DisplayName("2.1 短基线RTK链路通 (HKWS-HKSS, ~6.8km)")
    void testShortBaselinePipeline() {
        if (!shortDataAvailable) {
            log.info("跳过: 短基线数据不可用，请将HKWS/HKSS RINEX文件放入 {}", SHORT_DIR);
            return;
        }

        PrcOpt opt = createRtkOpt();
        RinexRtkProcessor rtk = new RinexRtkProcessor(opt);
        RtkProcessor.RtkResult result = rtk.process(shortRoverObs, shortBaseObs, shortNav);

        log.info("短基线RTK: 总历元={}, 成功={}, 失败={}", result.totalEpochs, result.successCount, result.failCount);
        assertTrue(result.totalEpochs > 0, "短基线应有历元被处理");
        assertTrue(result.successCount > 0, "短基线应有成功历元");
    }

    @Test
    @DisplayName("2.2 短基线固定率与ENU RMS统计")
    void testShortBaselineFixRateAndRms() {
        if (!shortDataAvailable) {
            log.info("跳过: 短基线数据不可用");
            return;
        }

        PrcOpt opt = createRtkOpt();
        RinexRtkProcessor rtk = new RinexRtkProcessor(opt);
        RtkProcessor.RtkResult result = rtk.process(shortRoverObs, shortBaseObs, shortNav);

        RtkMetrics metrics = computeRtkMetrics(result.solutions);
        log.info(String.format("短基线指标: 固定率=%.1f%% (%d/%d), ENU RMS=[%.4f, %.4f, %.4f]m, 基线长度=%.1fm",
                metrics.fixRate, metrics.fixCount, metrics.totalCount,
                metrics.rmsE, metrics.rmsN, metrics.rmsU, metrics.baselineLength));
    }

    @Test
    @DisplayName("2.3 长基线RTK链路通 (HKWS-HKSL, ~42km)")
    void testLongBaselinePipeline() {
        if (!longDataAvailable) {
            log.info("跳过: 长基线数据不可用，请将HKWS/HKSL RINEX文件放入 {}", LONG_DIR);
            return;
        }

        PrcOpt opt = createRtkOpt();
        RinexRtkProcessor rtk = new RinexRtkProcessor(opt);
        RtkProcessor.RtkResult result = rtk.process(longRoverObs, longBaseObs, longNav);

        log.info("长基线RTK: 总历元={}, 成功={}, 失败={}", result.totalEpochs, result.successCount, result.failCount);
        assertTrue(result.totalEpochs > 0, "长基线应有历元被处理");
    }

    @Test
    @DisplayName("2.4 长基线固定率与ENU RMS统计")
    void testLongBaselineFixRateAndRms() {
        if (!longDataAvailable) {
            log.info("跳过: 长基线数据不可用");
            return;
        }

        PrcOpt opt = createRtkOpt();
        RinexRtkProcessor rtk = new RinexRtkProcessor(opt);
        RtkProcessor.RtkResult result = rtk.process(longRoverObs, longBaseObs, longNav);

        RtkMetrics metrics = computeRtkMetrics(result.solutions);
        log.info(String.format("长基线指标: 固定率=%.1f%% (%d/%d), ENU RMS=[%.4f, %.4f, %.4f]m, 基线长度=%.1fm",
                metrics.fixRate, metrics.fixCount, metrics.totalCount,
                metrics.rmsE, metrics.rmsN, metrics.rmsU, metrics.baselineLength));
    }

    @Test
    @DisplayName("2.5 短/长基线对照：固定率与RMS对比")
    void testBaselineComparison() {
        if (!shortDataAvailable || !longDataAvailable) {
            log.info("跳过: 需要短基线和长基线数据均可用");
            return;
        }

        PrcOpt opt = createRtkOpt();

        RinexRtkProcessor rtkShort = new RinexRtkProcessor(opt);
        RtkProcessor.RtkResult shortResult = rtkShort.process(shortRoverObs, shortBaseObs, shortNav);
        RtkMetrics shortMetrics = computeRtkMetrics(shortResult.solutions);

        RinexRtkProcessor rtkLong = new RinexRtkProcessor(opt);
        RtkProcessor.RtkResult longResult = rtkLong.process(longRoverObs, longBaseObs, longNav);
        RtkMetrics longMetrics = computeRtkMetrics(longResult.solutions);

        log.info("=== 短/长基线对照 ===");
        log.info(String.format("短基线(6.8km): 固定率=%.1f%%, ENU RMS=[%.4f, %.4f, %.4f]m",
                shortMetrics.fixRate, shortMetrics.rmsE, shortMetrics.rmsN, shortMetrics.rmsU));
        log.info(String.format("长基线(42km):  固定率=%.1f%%, ENU RMS=[%.4f, %.4f, %.4f]m",
                longMetrics.fixRate, longMetrics.rmsE, longMetrics.rmsN, longMetrics.rmsU));
        log.info(String.format("基线长度: 短=%.1fm, 长=%.1fm", shortMetrics.baselineLength, longMetrics.baselineLength));

        if (shortMetrics.fixRate > 0 && longMetrics.fixRate > 0) {
            log.info(String.format("固定率差异: %.1f%% (短基线应更高)", shortMetrics.fixRate - longMetrics.fixRate));
        }
    }

    private static RtkMetrics computeRtkMetrics(List<SolData> solutions) {
        RtkMetrics m = new RtkMetrics();
        if (solutions == null || solutions.isEmpty()) return m;

        m.totalCount = solutions.size();
        double sumE = 0, sumN = 0, sumU = 0;
        double refE = 0, refN = 0, refU = 0;
        boolean hasRef = false;

        for (SolData sol : solutions) {
            if (sol.status == SolutionStatus.FIX) {
                m.fixCount++;
                if (!hasRef && sol.getPosition(CoordType.LLH) != null) {
                    Position llh = sol.getPosition(CoordType.LLH);
                    double[] refRr = new double[3];
                    CoordTransform.pos2ecef(new double[]{llh.val1, llh.val2, llh.val3}, refRr);
                    double[] refEnu = new double[3];
                    hasRef = true;
                }
            }
        }

        m.fixRate = m.totalCount > 0 ? (double) m.fixCount / m.totalCount * 100 : 0;

        if (m.fixCount > 1) {
            double meanE = 0, meanN = 0, meanU = 0;
            int enuCount = 0;
            double[] firstEcef = null;
            for (SolData sol : solutions) {
                if (sol.status != SolutionStatus.FIX) continue;
                Position ecef = sol.getPosition(CoordType.ECEF);
                if (ecef == null) continue;
                if (firstEcef == null) {
                    firstEcef = new double[]{ecef.val1, ecef.val2, ecef.val3};
                    continue;
                }
                double[] rr = new double[]{ecef.val1, ecef.val2, ecef.val3};
                double[] enu = new double[3];
                double[] pos = new double[3];
                CoordTransform.ecef2pos(firstEcef, pos);
                double[] dr = new double[]{rr[0] - firstEcef[0], rr[1] - firstEcef[1], rr[2] - firstEcef[2]};
                CoordTransform.ecef2enu(pos, dr, enu);
                meanE += enu[0]; meanN += enu[1]; meanU += enu[2];
                enuCount++;
            }
            if (enuCount > 0) {
                meanE /= enuCount; meanN /= enuCount; meanU /= enuCount;
                double sseE = 0, sseN = 0, sseU = 0;
                int rmsCount = 0;
                for (SolData sol : solutions) {
                    if (sol.status != SolutionStatus.FIX) continue;
                    Position ecef = sol.getPosition(CoordType.ECEF);
                    if (ecef == null || firstEcef == null) continue;
                    double[] rr = new double[]{ecef.val1, ecef.val2, ecef.val3};
                    double[] enu = new double[3];
                    double[] pos = new double[3];
                    CoordTransform.ecef2pos(firstEcef, pos);
                    double[] dr = new double[]{rr[0] - firstEcef[0], rr[1] - firstEcef[1], rr[2] - firstEcef[2]};
                    CoordTransform.ecef2enu(pos, dr, enu);
                    sseE += (enu[0] - meanE) * (enu[0] - meanE);
                    sseN += (enu[1] - meanN) * (enu[1] - meanN);
                    sseU += (enu[2] - meanU) * (enu[2] - meanU);
                    rmsCount++;
                }
                if (rmsCount > 1) {
                    m.rmsE = Math.sqrt(sseE / (rmsCount - 1));
                    m.rmsN = Math.sqrt(sseN / (rmsCount - 1));
                    m.rmsU = Math.sqrt(sseU / (rmsCount - 1));
                }
            }
        }

        if (solutions.size() >= 2) {
            SolData first = null, last = null;
            for (SolData sol : solutions) {
                if (sol.status == SolutionStatus.NONE) continue;
                if (first == null) first = sol;
                last = sol;
            }
            if (first != null && last != null) {
                Position p1 = first.getPosition(CoordType.LLH);
                Position p2 = last.getPosition(CoordType.LLH);
                if (p1 != null && p2 != null) {
                    double[] r1 = new double[3], r2 = new double[3];
                    CoordTransform.pos2ecef(new double[]{p1.val1, p1.val2, p1.val3}, r1);
                    CoordTransform.pos2ecef(new double[]{p2.val1, p2.val2, p2.val3}, r2);
                    double dx = r1[0] - r2[0], dy = r1[1] - r2[1], dz = r1[2] - r2[2];
                    m.baselineLength = Math.sqrt(dx * dx + dy * dy + dz * dz);
                }
            }
        }

        return m;
    }

    private static class RtkMetrics {
        int totalCount = 0;
        int fixCount = 0;
        double fixRate = 0;
        double rmsE = 0, rmsN = 0, rmsU = 0;
        double baselineLength = 0;
    }
}