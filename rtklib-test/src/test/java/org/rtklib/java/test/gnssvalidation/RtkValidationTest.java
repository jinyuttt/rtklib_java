package org.rtklib.java.test.gnssvalidation;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.rtklib.java.TestDataConfig;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.product.ProductDownloader;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.rinex.RinexParser;
import org.rtklib.java.rinex.RinexRtkProcessor;
import org.rtklib.java.rtkpos.RtkProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("阶段2: 短/长基线RTK对照验证")
public class RtkValidationTest {

    private static final Logger log = LoggerFactory.getLogger(RtkValidationTest.class);

    private static final String DATA_DIR = TestDataConfig.getTestDataDir()
            + File.separator + "gnss-validation" + File.separator + "rtk";

    private static final String SHORT_DIR = DATA_DIR + File.separator + "short-baseline" + File.separator + "hkws-hkss";
    private static final String LONG_DIR = DATA_DIR + File.separator + "long-baseline" + File.separator + "hkws-hksl";
    private static final String NAV_DIR = DATA_DIR + File.separator + "brdc";
    private static final String ATX_DIR = TestDataConfig.getTestDataDir()
            + File.separator + "gnss-validation" + File.separator + "table";

    private static String shortRoverObs, shortBaseObs, shortNav;
    private static String longRoverObs, longBaseObs, longNav;
    private static String atxPath;
    private static double[] shortBasePos, longBasePos;
    private static boolean shortDataAvailable;
    private static boolean longDataAvailable;

    static boolean shortDataAvailable() { return shortDataAvailable; }
    static boolean longDataAvailable() { return longDataAvailable; }

    private static final int TEST_YEAR = 2024;
    private static final int TEST_DOY = 60;

    @BeforeAll
    static void checkData() {
        GnssValidationDataHelper helper = new GnssValidationDataHelper(TestDataConfig.getTestDataDir());

        if (!GnssValidationDataHelper.isDataAvailable(SHORT_DIR)) {
            log.info("短基线数据不在本地，尝试自动下载SatRef HKWS+HKSS...");
            helper.ensureSatRefRinex(SHORT_DIR, "hkws", TEST_YEAR, TEST_DOY, "30s");
            helper.ensureSatRefRinex(SHORT_DIR, "hkss", TEST_YEAR, TEST_DOY, "30s");
        }
        if (!GnssValidationDataHelper.isDataAvailable(LONG_DIR)) {
            log.info("长基线数据不在本地，尝试自动下载SatRef HKWS+HKSL...");
            helper.ensureSatRefRinex(LONG_DIR, "hkws", TEST_YEAR, TEST_DOY, "30s");
            helper.ensureSatRefRinex(LONG_DIR, "hksl", TEST_YEAR, TEST_DOY, "30s");
        }

        if (!GnssValidationDataHelper.isDataAvailable(NAV_DIR)) {
            log.info("导航文件不在本地，尝试从IGS BRDC下载...");
            helper.ensureIgsRinex(NAV_DIR, "BRDC", TEST_YEAR, TEST_DOY);
        }

        atxPath = findAtxFile(ATX_DIR);
        if (atxPath == null) {
            log.info("ANTEX文件不在本地，尝试从IGS下载...");
            atxPath = downloadAntex(ATX_DIR);
        }
        if (atxPath != null) {
            log.info("ANTEX文件: {}", atxPath);
        } else {
            log.info("ANTEX文件不可用，天线PCV改正将不启用");
        }

        shortNav = findNavFile(NAV_DIR);
        longNav = shortNav;

        shortDataAvailable = findRtkPair(SHORT_DIR, "HKWS", "HKSS", "短基线HKWS-HKSS");
        if (shortDataAvailable) {
            shortRoverObs = findObsByStation(SHORT_DIR, "HKWS");
            shortBaseObs = findObsByStation(SHORT_DIR, "HKSS");
            shortDataAvailable = shortRoverObs != null && shortBaseObs != null && shortNav != null;
            if (shortDataAvailable) {
                shortBasePos = readRinexApproxPos(shortBaseObs);
                if (shortBasePos != null) {
                    log.info("短基线基准站(HKSS)近似ECEF: ({}, {}, {})",
                            String.format("%.3f", shortBasePos[0]),
                            String.format("%.3f", shortBasePos[1]),
                            String.format("%.3f", shortBasePos[2]));
                }
            }
            log.info("短基线数据: rover(HKWS)={}, base(HKSS)={}, nav={}", shortRoverObs, shortBaseObs, shortNav);
        }

        longDataAvailable = findRtkPair(LONG_DIR, "HKWS", "HKSL", "长基线HKWS-HKSL");
        if (longDataAvailable) {
            longRoverObs = findObsByStation(LONG_DIR, "HKWS");
            longBaseObs = findObsByStation(LONG_DIR, "HKSL");
            longDataAvailable = longRoverObs != null && longBaseObs != null && longNav != null;
            if (longDataAvailable) {
                longBasePos = readRinexApproxPos(longBaseObs);
                if (longBasePos != null) {
                    log.info("长基线基准站(HKSL)近似ECEF: ({}, {}, {})",
                            String.format("%.3f", longBasePos[0]),
                            String.format("%.3f", longBasePos[1]),
                            String.format("%.3f", longBasePos[2]));
                }
            }
            log.info("长基线数据: rover(HKWS)={}, base(HKSL)={}, nav={}", longRoverObs, longBaseObs, longNav);
        }
    }

    private static double[] readRinexApproxPos(String obsFile) {
        try {
            RinexParser parser = new RinexParser();
            if (parser.parseObs(obsFile)) {
                double[] pos = parser.sta.pos;
                if (pos != null && (pos[0] != 0 || pos[1] != 0 || pos[2] != 0)) {
                    return pos.clone();
                }
            }
        } catch (Exception e) {
            log.warn("读取RINEX近似坐标失败: {}", e.getMessage());
        }
        return null;
    }

    private static boolean findRtkPair(String dir, String rover, String base, String label) {
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
        boolean hasRover = false, hasBase = false;
        for (File f : files) {
            String name = f.getName().toUpperCase();
            if (name.startsWith(rover.toUpperCase()) && (name.endsWith(".RNX") || name.endsWith(".O") || name.endsWith(".OBS"))) hasRover = true;
            if (name.startsWith(base.toUpperCase()) && (name.endsWith(".RNX") || name.endsWith(".O") || name.endsWith(".OBS"))) hasBase = true;
        }
        if (!hasRover || !hasBase) {
            log.info("{}缺少观测文件: rover={} base={}", label, hasRover, hasBase);
            return false;
        }
        return true;
    }

    private static String findObsByStation(String dir, String station) {
        File d = new File(dir);
        File[] files = d.listFiles();
        if (files == null) return null;
        String stUpper = station.toUpperCase();
        for (File f : files) {
            String name = f.getName().toUpperCase();
            if (name.startsWith(stUpper) && (name.endsWith("_MO.RNX") || name.endsWith(".RNX"))) {
                return f.getAbsolutePath();
            }
        }
        for (File f : files) {
            String name = f.getName().toUpperCase();
            if (name.startsWith(stUpper) && (name.endsWith(".O") || name.endsWith(".OBS"))) {
                return f.getAbsolutePath();
            }
        }
        return null;
    }

    private static String findNavFile(String dir) {
        File d = new File(dir);
        if (!d.exists() || !d.isDirectory()) return null;
        File[] files = d.listFiles();
        if (files == null) return null;
        for (File f : files) {
            String name = f.getName().toUpperCase();
            if (name.contains("BRDC") && (name.endsWith("_MN.RNX") || name.endsWith(".RNX"))) {
                return f.getAbsolutePath();
            }
        }
        for (File f : files) {
            String name = f.getName().toUpperCase();
            if (name.contains("BRDC") && (name.endsWith(".N") || name.endsWith(".NAV"))) {
                return f.getAbsolutePath();
            }
        }
        for (File f : files) {
            String name = f.getName().toUpperCase();
            if (name.endsWith("_MN.RNX") || name.endsWith(".N") || name.endsWith(".NAV")) {
                return f.getAbsolutePath();
            }
        }
        return null;
    }

    private static String findAtxFile(String dir) {
        File d = new File(dir);
        if (!d.exists() || !d.isDirectory()) return null;
        File[] files = d.listFiles();
        if (files == null) return null;
        for (File f : files) {
            String name = f.getName().toLowerCase();
            if (name.endsWith(".atx") && f.length() > 0) {
                return f.getAbsolutePath();
            }
        }
        return null;
    }

    private static String downloadAntex(String dir) {
        try {
            File d = new File(dir);
            if (!d.exists()) d.mkdirs();
            ProductDownloader dl = new ProductDownloader(
                    Paths.get(TestDataConfig.getTestDataDir()).resolve("gnss-validation").toString());
            String result = dl.downloadAntex("igs20_2317.atx");
            if (result != null) {
                File src = new File(result);
                File dst = new File(dir, src.getName());
                if (!dst.exists() || dst.length() == 0) {
                    java.nio.file.Files.copy(src.toPath(), dst.toPath(),
                            java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                return dst.getAbsolutePath();
            }
        } catch (Exception e) {
            log.warn("ANTEX下载失败: {}", e.getMessage());
        }
        return null;
    }

    private static PrcOpt createRtkOpt() {
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
        return opt;
    }

    private static SolOpt createRtkSolOpt() {
        SolOpt solOpt = new SolOpt();
        solOpt.solstatic = 0;
        return solOpt;
    }

    private static RtkConfig createRtkConfig() {
        RtkConfig cfg = new RtkConfig();
        cfg.enableSnrMedian = true;
        return cfg;
    }

    private static PrcOpt createLongRtkOpt() {
        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_STATIC;
        opt.soltype = Constants.SOLTYPE_FORWARD;
        opt.nf = 2;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_IFLC;
        opt.tropopt = Constants.TROPOPT_EST;
        opt.modear = Constants.ARMODE_FIXHOLD;
        opt.refpos = Constants.POSOPT_SINGLE;
        opt.elmaskar = 15.0 * Constants.D2R;
        opt.elmaskhold = 15.0 * Constants.D2R;
        opt.niter = 2;
        opt.tidecorr = 1;
        opt.eratio[0] = 100.0;
        opt.eratio[1] = 100.0;
        opt.std[1] = 1.0;
        opt.prn[1] = 1E-2;
        opt.snrmask.ena0 = 1;
        for (int i = 0; i < Constants.NFREQ; i++) {
            opt.snrmask.mask[i * Constants.NFREQ + 0] = 25.0;
            opt.snrmask.mask[i * Constants.NFREQ + 1] = 25.0;
        }
        opt.anttype[0] = "*";
        opt.anttype[1] = "*";
        return opt;
    }

    private static RtkConfig createLongRtkConfig() {
        RtkConfig cfg = new RtkConfig();
        cfg.enableSnrMedian = true;
        cfg.enableIggiii = true;
        cfg.enableParamTypeNoise = true;
        cfg.atmFrozenNsThresh = 7;
        return cfg;
    }

    @Test
    @DisplayName("2.1 短基线RTK链路通 (HKWS-HKSS, ~6.8km)")
    void testShortBaselinePipeline() {
        if (!shortDataAvailable) {
            log.info("跳过: 短基线数据不可用，请将HKWS/HKSS RINEX文件放入 {}", SHORT_DIR);
            return;
        }

        PrcOpt opt = createRtkOpt();
        SolOpt solOpt = createRtkSolOpt();
        RinexRtkProcessor rtk = new RinexRtkProcessor(opt, solOpt, createRtkConfig(), null, null);
        rtk.setAtxPath(atxPath);
        RtkProcessor.RtkResult result = rtk.process(shortRoverObs, shortBaseObs, shortNav);

        log.info("RTK result: totalEpochs={}, success={}, fail={}, solutions.size()={}",
                result.totalEpochs, result.successCount, result.failCount, result.solutions.size());

        RtkMetrics metrics = computeRtkMetrics(result.solutions, shortBasePos);
        logRtkMetrics("短基线HKWS-HKSS", 6.8, metrics);

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
        SolOpt solOpt = createRtkSolOpt();
        RinexRtkProcessor rtk = new RinexRtkProcessor(opt, solOpt, createRtkConfig(), null, null);
        rtk.setAtxPath(atxPath);
        RtkProcessor.RtkResult result = rtk.process(shortRoverObs, shortBaseObs, shortNav);

        RtkMetrics metrics = computeRtkMetrics(result.solutions, shortBasePos);
        logRtkMetrics("短基线HKWS-HKSS", 6.8, metrics);

        assertTrue(metrics.fixRate > 80, "短基线固定率应>80%: " + metrics.fixRate);
    }

    @Test
    @DisplayName("2.3 长基线RTK链路通 (HKWS-HKSL, ~42km)")
    void testLongBaselinePipeline() {
        if (!longDataAvailable) {
            log.info("跳过: 长基线数据不可用，请将HKWS/HKSL RINEX文件放入 {}", LONG_DIR);
            return;
        }

        PrcOpt opt = createLongRtkOpt();
        SolOpt solOpt = createRtkSolOpt();
        RinexRtkProcessor rtk = new RinexRtkProcessor(opt, solOpt, createLongRtkConfig(), null, null);
        rtk.setAtxPath(atxPath);
        RtkProcessor.RtkResult result = rtk.process(longRoverObs, longBaseObs, longNav);

        RtkMetrics metrics = computeRtkMetrics(result.solutions, longBasePos);
        logRtkMetrics("长基线HKWS-HKSL", 42.0, metrics);

        assertTrue(result.totalEpochs > 0, "长基线应有历元被处理");
    }

    @Test
    @DisplayName("2.4 长基线固定率与ENU RMS统计")
    void testLongBaselineFixRateAndRms() {
        if (!longDataAvailable) {
            log.info("跳过: 长基线数据不可用");
            return;
        }

        PrcOpt opt = createLongRtkOpt();
        SolOpt solOpt = createRtkSolOpt();
        RinexRtkProcessor rtk = new RinexRtkProcessor(opt, solOpt, createLongRtkConfig(), null, null);
        rtk.setAtxPath(atxPath);
        RtkProcessor.RtkResult result = rtk.process(longRoverObs, longBaseObs, longNav);

        RtkMetrics metrics = computeRtkMetrics(result.solutions, longBasePos);
        logRtkMetrics("长基线HKWS-HKSL", 42.0, metrics);
    }

    @Test
    @DisplayName("2.5 短/长基线对照：固定率与RMS对比")
    void testBaselineComparison() {
        if (!shortDataAvailable || !longDataAvailable) {
            log.info("跳过: 需要短基线和长基线数据均可用");
            return;
        }

        PrcOpt shortOpt = createRtkOpt();
        PrcOpt longOpt = createLongRtkOpt();
        SolOpt solOpt = createRtkSolOpt();

        RinexRtkProcessor rtkShort = new RinexRtkProcessor(shortOpt, solOpt, createRtkConfig(), null, null);
        rtkShort.setAtxPath(atxPath);
        RtkProcessor.RtkResult shortResult = rtkShort.process(shortRoverObs, shortBaseObs, shortNav);
        RtkMetrics shortMetrics = computeRtkMetrics(shortResult.solutions, shortBasePos);

        RinexRtkProcessor rtkLong = new RinexRtkProcessor(longOpt, solOpt, createLongRtkConfig(), null, null);
        rtkLong.setAtxPath(atxPath);
        RtkProcessor.RtkResult longResult = rtkLong.process(longRoverObs, longBaseObs, longNav);
        RtkMetrics longMetrics = computeRtkMetrics(longResult.solutions, longBasePos);

        log.info("=== 短/长基线对照 ===");
        logRtkMetrics("短基线HKWS-HKSS", 6.8, shortMetrics);
        logRtkMetrics("长基线HKWS-HKSL", 42.0, longMetrics);

        if (shortMetrics.fixRate > 0 && longMetrics.fixRate > 0) {
            log.info(String.format("固定率差异: %.1f%% (短基线应更高)", shortMetrics.fixRate - longMetrics.fixRate));
        }
    }

    private static RtkMetrics computeRtkMetrics(List<SolData> solutions, double[] baseEcef) {
        RtkMetrics m = new RtkMetrics();
        if (solutions == null || solutions.isEmpty()) return m;

        m.totalCount = solutions.size();

        int fixCount = 0, floatCount = 0, singleCount = 0, noneCount = 0;
        for (SolData sol : solutions) {
            switch (sol.status) {
                case FIX:    fixCount++;    break;
                case FLOAT:  floatCount++;  break;
                case SINGLE: singleCount++; break;
                case NONE:   noneCount++;   break;
                default:                     break;
            }
        }
        m.fixCount = fixCount;
        m.floatCount = floatCount;
        m.singleCount = singleCount;
        m.noneCount = noneCount;
        m.fixRate = m.totalCount > 0 ? (double) fixCount / m.totalCount * 100 : 0;

        int firstFix = -1, lastFix = -1;
        for (int i = 0; i < solutions.size(); i++) {
            if (solutions.get(i).status == SolutionStatus.FIX) {
                if (firstFix < 0) firstFix = i;
                lastFix = i;
            }
        }
        m.firstFixIdx = firstFix;
        m.lastFixIdx = lastFix;

        double sumX = 0, sumY = 0, sumZ = 0;
        int ecefCount = 0;
        for (SolData sol : solutions) {
            if (sol.status == SolutionStatus.NONE) continue;
            Position ecef = sol.getPosition(CoordType.ECEF);
            if (ecef == null) continue;
            sumX += ecef.v1; sumY += ecef.v2; sumZ += ecef.v3;
            ecefCount++;
        }
        if (ecefCount < 2) return m;

        double meanX = sumX / ecefCount;
        double meanY = sumY / ecefCount;
        double meanZ = sumZ / ecefCount;

        if (baseEcef != null && baseEcef.length >= 3) {
            double dx = meanX - baseEcef[0];
            double dy = meanY - baseEcef[1];
            double dz = meanZ - baseEcef[2];
            m.baselineLength = Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        if (fixCount > 0) {
            double sumFx = 0, sumFy = 0, sumFz = 0;
            for (SolData sol : solutions) {
                if (sol.status != SolutionStatus.FIX) continue;
                Position ecef = sol.getPosition(CoordType.ECEF);
                if (ecef == null) continue;
                sumFx += ecef.v1; sumFy += ecef.v2; sumFz += ecef.v3;
            }
            double fixMeanX = sumFx / fixCount;
            double fixMeanY = sumFy / fixCount;
            double fixMeanZ = sumFz / fixCount;
            m.fixMeanEcef = new double[]{fixMeanX, fixMeanY, fixMeanZ};
            if (baseEcef != null && baseEcef.length >= 3) {
                double dx = fixMeanX - baseEcef[0];
                double dy = fixMeanY - baseEcef[1];
                double dz = fixMeanZ - baseEcef[2];
                m.fixBaselineLength = Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
        }

        double[] meanRr = new double[]{meanX, meanY, meanZ};
        double[] meanPos = new double[3];
        CoordTransform.ecef2pos(meanRr, meanPos);

        double sseE = 0, sseN = 0, sseU = 0;
        double maxE = 0, maxN = 0, maxU = 0;
        double[] dr = new double[3];
        double[] enu = new double[3];
        int rmsCount = 0;

        for (SolData sol : solutions) {
            if (sol.status == SolutionStatus.NONE) continue;
            Position ecef = sol.getPosition(CoordType.ECEF);
            if (ecef == null) continue;

            dr[0] = ecef.v1 - meanX;
            dr[1] = ecef.v2 - meanY;
            dr[2] = ecef.v3 - meanZ;
            CoordTransform.ecef2enu(meanPos, dr, enu);

            sseE += enu[0] * enu[0];
            sseN += enu[1] * enu[1];
            sseU += enu[2] * enu[2];
            maxE = Math.max(maxE, Math.abs(enu[0]));
            maxN = Math.max(maxN, Math.abs(enu[1]));
            maxU = Math.max(maxU, Math.abs(enu[2]));
            rmsCount++;
        }

        if (rmsCount > 1) {
            m.rmsE = Math.sqrt(sseE / rmsCount);
            m.rmsN = Math.sqrt(sseN / rmsCount);
            m.rmsU = Math.sqrt(sseU / rmsCount);
            m.stdE = Math.sqrt(sseE / (rmsCount - 1));
            m.stdN = Math.sqrt(sseN / (rmsCount - 1));
            m.stdU = Math.sqrt(sseU / (rmsCount - 1));
        }
        m.maxE = maxE; m.maxN = maxN; m.maxU = maxU;

        if (fixCount > 1 && m.fixMeanEcef != null) {
            double[] fixMeanPos = new double[3];
            CoordTransform.ecef2pos(m.fixMeanEcef, fixMeanPos);
            double sseFE = 0, sseFN = 0, sseFU = 0;
            double maxFE = 0, maxFN = 0, maxFU = 0;
            double[] drF = new double[3];
            double[] enuF = new double[3];
            int fixRmsCount = 0;
            for (SolData sol : solutions) {
                if (sol.status != SolutionStatus.FIX) continue;
                Position ecef = sol.getPosition(CoordType.ECEF);
                if (ecef == null) continue;
                drF[0] = ecef.v1 - m.fixMeanEcef[0];
                drF[1] = ecef.v2 - m.fixMeanEcef[1];
                drF[2] = ecef.v3 - m.fixMeanEcef[2];
                CoordTransform.ecef2enu(fixMeanPos, drF, enuF);
                sseFE += enuF[0] * enuF[0];
                sseFN += enuF[1] * enuF[1];
                sseFU += enuF[2] * enuF[2];
                maxFE = Math.max(maxFE, Math.abs(enuF[0]));
                maxFN = Math.max(maxFN, Math.abs(enuF[1]));
                maxFU = Math.max(maxFU, Math.abs(enuF[2]));
                fixRmsCount++;
            }
            if (fixRmsCount > 1) {
                m.fixRmsE = Math.sqrt(sseFE / fixRmsCount);
                m.fixRmsN = Math.sqrt(sseFN / fixRmsCount);
                m.fixRmsU = Math.sqrt(sseFU / fixRmsCount);
            }
            m.fixMaxE = maxFE; m.fixMaxN = maxFN; m.fixMaxU = maxFU;
        }

        m.meanLat = meanPos[0] / Constants.D2R;
        m.meanLon = meanPos[1] / Constants.D2R;
        m.meanH = meanPos[2];
        m.validCount = rmsCount;

        return m;
    }

    private static void logRtkMetrics(String label, double expectedKm, RtkMetrics m) {
        log.info("╔══════════════════════════════════════════════╗");
        log.info("║  {} RTK 精度评估", label);
        log.info("╠══════════════════════════════════════════════╣");
        log.info("║ 历元: 总={} FIX={} FLOAT={} SINGLE={} NONE={}",
                m.totalCount, m.fixCount, m.floatCount, m.singleCount, m.noneCount);
        log.info("║ 固定率: {}% ({}/{})", String.format("%.1f", m.fixRate), m.fixCount, m.totalCount);
        if (m.firstFixIdx >= 0) {
            log.info("║ FIX历元范围: [{}..{}] / {}", m.firstFixIdx, m.lastFixIdx, m.totalCount);
        }
        log.info("╠══════════════════════════════════════════════╣");
        log.info("║ 基线长度: {} m (期望 ~{} km)", String.format("%.3f", m.baselineLength), String.format("%.1f", expectedKm));
        if (m.fixBaselineLength > 0) {
            log.info("║ FIX基线长度: {} m (仅FIX解平均)", String.format("%.3f", m.fixBaselineLength));
        }
        if (expectedKm > 0 && m.baselineLength > 0) {
            double expectedM = expectedKm * 1000;
            double err = m.baselineLength - expectedM;
            log.info("║ 基线偏差: {} m ({}%)", String.format("%+.3f", err), String.format("%+.2f", err / expectedM * 100));
            if (Math.abs(err) < 0.01) {
                log.info("║ [EXCELLENT] 基线长度偏差 < 1cm: 坐标转换与相对定位逻辑天衣无缝!");
            } else if (Math.abs(err) < 0.05) {
                log.info("║ [GOOD] 基线长度偏差 < 5cm: 坐标转换精度优秀");
            } else if (Math.abs(err) < 1.0) {
                log.info("║ [OK] 基线长度偏差 < 1m: 可接受");
            } else {
                log.info("║ [WARN] 基线长度偏差偏大，检查基准站坐标!");
            }
        }
        log.info("╠══════════════════════════════════════════════╣");
        log.info("║ ENU 精度 (参考=所有有效历元平均坐标)");
        log.info("║   E(东): std={}m  RMS={}m  max={}m",
                String.format("%.4f", m.stdE), String.format("%.4f", m.rmsE), String.format("%.4f", m.maxE));
        log.info("║   N(北): std={}m  RMS={}m  max={}m",
                String.format("%.4f", m.stdN), String.format("%.4f", m.rmsN), String.format("%.4f", m.maxN));
        log.info("║   U(天): std={}m  RMS={}m  max={}m",
                String.format("%.4f", m.stdU), String.format("%.4f", m.rmsU), String.format("%.4f", m.maxU));
        double rms2D = Math.sqrt(m.rmsE * m.rmsE + m.rmsN * m.rmsN);
        log.info("║   2D水平RMS = {}m", String.format("%.4f", rms2D));
        if (m.fixRmsE > 0 || m.fixRmsN > 0) {
            log.info("║ --- FIX解 ENU 精度 (参考=FIX解平均坐标) ---");
            log.info("║   E(东): RMS={}m  max={}m", String.format("%.4f", m.fixRmsE), String.format("%.4f", m.fixMaxE));
            log.info("║   N(北): RMS={}m  max={}m", String.format("%.4f", m.fixRmsN), String.format("%.4f", m.fixMaxN));
            log.info("║   U(天): RMS={}m  max={}m", String.format("%.4f", m.fixRmsU), String.format("%.4f", m.fixMaxU));
            double fixRms2D = Math.sqrt(m.fixRmsE * m.fixRmsE + m.fixRmsN * m.fixRmsN);
            log.info("║   2D水平RMS = {}m", String.format("%.4f", fixRms2D));
            if (m.fixRmsE < 0.01 && m.fixRmsN < 0.01 && m.fixRmsU < 0.03) {
                log.info("║ [EXCELLENT] FIX水平<1cm 高程<3cm: 稳如老狗!");
            } else if (m.fixRmsE < 0.05 && m.fixRmsN < 0.05 && m.fixRmsU < 0.1) {
                log.info("║ [GOOD] FIX水平<5cm 高程<10cm: RTK固定解精度正常");
            }
        }
        if (m.rmsE < 0.01 && m.rmsN < 0.01 && m.rmsU < 0.03) {
            log.info("║ [EXCELLENT] 水平<1cm 高程<3cm: 稳如老狗!");
        } else if (m.rmsE < 0.05 && m.rmsN < 0.05 && m.rmsU < 0.1) {
            log.info("║ [GOOD] 水平<5cm 高程<10cm: RTK固定解精度正常");
        } else if (m.rmsE < 0.1 && m.rmsN < 0.1) {
            log.info("║ [OK] 水平<10cm: RTK解算精度可接受");
        } else {
            log.info("║ [WARN] RMS偏大，检查模糊度固定情况");
        }
        log.info("╚══════════════════════════════════════════════╝");
    }

    private static class RtkMetrics {
        int totalCount = 0;
        int fixCount = 0, floatCount = 0, singleCount = 0, noneCount = 0;
        double fixRate = 0;
        int firstFixIdx = -1, lastFixIdx = -1;
        double rmsE = 0, rmsN = 0, rmsU = 0;
        double stdE = 0, stdN = 0, stdU = 0;
        double maxE = 0, maxN = 0, maxU = 0;
        double fixRmsE = 0, fixRmsN = 0, fixRmsU = 0;
        double fixMaxE = 0, fixMaxN = 0, fixMaxU = 0;
        double baselineLength = 0;
        double fixBaselineLength = 0;
        double[] fixMeanEcef = null;
        double meanLat = 0, meanLon = 0, meanH = 0;
        int validCount = 0;
    }
}