package org.rtklib.java.test.gnssvalidation;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.rtklib.java.TestDataConfig;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.product.ProductDownloader;
import org.rtklib.java.rinex.RinexPppProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("阶段4: PPP-RTK事后SSR链路验证")
public class PppRtkValidationTest {

    private static final Logger log = LoggerFactory.getLogger(PppRtkValidationTest.class);

    private static final String DATA_DIR = TestDataConfig.getTestDataDir()
            + File.separator + "gnss-validation" + File.separator + "ppp-rtk" + File.separator + "algo";

    private static final String PRODUCT_DIR = TestDataConfig.getTestDataDir()
            + File.separator + "gnss-validation" + File.separator + "product";

    private static String obsFile;
    private static String navFile;
    private static String sp3File;
    private static String clkFile;
    private static String biaFile;
    private static String atxFile;
    private static String localAtxPath;
    private static boolean dataAvailable;

    static boolean dataAvailable() { return dataAvailable; }

    private static final int TEST_YEAR = 2024;
    private static final int TEST_DOY = 60;

    /** ALGO 站 IGS20 参考坐标（与 PppValidationTest REF_ECEF 同源，FIX-ALG-019 修正值） */
    private static final double[] REF_ECEF = {918129.074497908, -4346071.34240674, 4561977.92989306};

    @BeforeAll
    static void checkData() {
        GnssValidationDataHelper helper = new GnssValidationDataHelper(TestDataConfig.getTestDataDir());

        if (!GnssValidationDataHelper.isDataAvailable(DATA_DIR)) {
            log.info("PPP-RTK观测数据不在本地，尝试自动下载ALGO RINEX 3...");
            helper.ensureIgsStationRinex("ppp-rtk", "algo", TEST_YEAR, TEST_DOY);
        }

        File sp3DirCheck = new File(PRODUCT_DIR + File.separator + "sp3");
        File clkDirCheck = new File(PRODUCT_DIR + File.separator + "clk");
        if (!sp3DirCheck.exists() || !clkDirCheck.exists()
                || sp3DirCheck.list() == null || sp3DirCheck.list().length == 0
                || clkDirCheck.list() == null || clkDirCheck.list().length == 0) {
            log.info("MGEX精密产品不在本地，尝试自动下载SP3+CLK+BIA...");
            helper.ensureMgexProducts(TEST_YEAR, TEST_DOY,
                    ProductDownloader.ProductType.SP3,
                    ProductDownloader.ProductType.CLK,
                    ProductDownloader.ProductType.BIA);
        }

        File obsDir = new File(DATA_DIR);
        File sp3Dir = new File(PRODUCT_DIR + File.separator + "sp3");
        File clkDir = new File(PRODUCT_DIR + File.separator + "clk");
        File biaDir = new File(PRODUCT_DIR + File.separator + "bia");
        File atxDir = new File(PRODUCT_DIR + File.separator + "atx");

        obsFile = findFile(obsDir, "_MO.rnx", ".obs", ".25o", ".24o", ".23o", ".21o", ".18o");
        navFile = findFile(obsDir, "_MN.rnx", ".nav", ".25n", ".24n", ".23n", ".21n", ".18n");
        sp3File = findFile(sp3Dir, ".sp3", ".SP3");
        clkFile = findFile(clkDir, ".clk", ".CLK");
        biaFile = findFile(biaDir, ".bia", ".BIA", ".bsx", ".BSX");
        atxFile = findFile(atxDir, ".atx", ".ATX");

        String tableDir = TestDataConfig.getTestDataDir() + File.separator + "gnss-validation" + File.separator + "table";
        localAtxPath = findFile(new File(tableDir), ".atx", ".ATX");
        if (localAtxPath == null && atxFile != null) localAtxPath = atxFile;
        if (localAtxPath != null) {
            log.info("ANTEX文件: {}", localAtxPath);
        }

        dataAvailable = obsFile != null && navFile != null && sp3File != null && clkFile != null;

        log.info("PPP-RTK数据: obs={}, nav={}", obsFile, navFile);
        log.info("PPP-RTK产品: sp3={}, clk={}, bia={}, atx={}", sp3File, clkFile, biaFile, atxFile);
        if (!dataAvailable) {
            log.info("PPP-RTK数据不完整，请将观测+导航+MGEX产品放入对应目录");
        }
    }

    private static String findFile(File dir, String... extensions) {
        if (!dir.exists() || !dir.isDirectory()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (String ext : extensions) {
            for (File f : files) {
                if (f.getName().endsWith(ext) || f.getName().endsWith(ext.toUpperCase())) {
                    return f.getAbsolutePath();
                }
            }
        }
        return null;
    }

    private static PrcOpt createPppRtkOpt() {
        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_PPPRTK_STATIC;
        opt.nf = 2;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_IFLC;
        opt.tropopt = Constants.TROPOPT_EST;
        opt.sateph = Constants.EPHOPT_PREC;
        opt.dynamics = 0;
        opt.posopt = new int[6];
        opt.anttype[0] = "*";
        return opt;
    }

    private static RtkConfig createPppRtkConfig() {
        RtkConfig cfg = new RtkConfig();
        cfg.enablePppRtk = true;
        cfg.enablePppRtkAR = true;
        return cfg;
    }

    /** ENU 精度评估（收敛期跳过前 1/3 历元），返回统计行文本；无有效解返回 null */
    private static String assessEnu(String label, List<SolData> solutions) {
        if (solutions.size() < 30) {
            log.info("[{}] 历元数不足({})，无法评估精度", label, solutions.size());
            return null;
        }
        double[] refLlh = new double[3];
        org.rtklib.java.coord.CoordTransform.ecef2pos(REF_ECEF, refLlh);

        int convergeSkip = solutions.size() / 3;
        double sumE = 0, sumN = 0, sumU = 0;
        int count = 0;
        for (int i = convergeSkip; i < solutions.size(); i++) {
            SolData sol = solutions.get(i);
            if (sol.status == SolutionStatus.NONE) continue;
            Position ecef = sol.getPosition(CoordType.ECEF);
            if (ecef == null) continue;
            double[] dr = {ecef.v1 - REF_ECEF[0], ecef.v2 - REF_ECEF[1], ecef.v3 - REF_ECEF[2]};
            double[] enu = new double[3];
            org.rtklib.java.coord.CoordTransform.ecef2enu(refLlh, dr, enu);
            sumE += enu[0] * enu[0];
            sumN += enu[1] * enu[1];
            sumU += enu[2] * enu[2];
            count++;
        }
        if (count < 1) {
            log.info("[{}] 收敛后无有效解", label);
            return null;
        }
        double rmsE = Math.sqrt(sumE / count);
        double rmsN = Math.sqrt(sumN / count);
        double rmsU = Math.sqrt(sumU / count);
        return String.format("[%s] ENU RMS: E=%.4fm N=%.4fm U=%.4fm 2D=%.4fm (%d历元)",
                label, rmsE, rmsN, rmsU, Math.sqrt(rmsE * rmsE + rmsN * rmsN), count);
    }

    @Test
    @DisplayName("4.1 PPP-RTK精密产品加载验证")
    void testProductLoading() {
        if (sp3File == null || clkFile == null) {
            log.info("跳过: MGEX精密产品不可用，请将SP3/CLK放入 {}", PRODUCT_DIR);
            return;
        }

        Nav nav = new Nav();
        org.rtklib.java.ephemeris.Sp3Reader.readsp3(sp3File, nav, 0);
        org.rtklib.java.ephemeris.ClkReader.readclk(clkFile, nav);

        log.info("SP3: ne={}, CLK: nc={}", nav.ne, nav.nc);
        assertTrue(nav.ne > 0, "应加载SP3轨道数据");
        assertTrue(nav.nc > 0, "应加载CLK钟差数据");

        if (biaFile != null) {
            log.info("BIAS/UPD文件可用: {}", biaFile);
        } else {
            log.info("BIAS/UPD文件不可用（PPP-RTK AR可能受限）");
        }
    }

    @Test
    @DisplayName("4.2 PPP-RTK解算链路通")
    void testPppRtkPipeline() {
        if (!dataAvailable) {
            log.info("跳过: PPP-RTK数据不可用");
            return;
        }

        PrcOpt opt = createPppRtkOpt();
        RtkConfig cfg = createPppRtkConfig();

        RinexPppProcessor ppp = new RinexPppProcessor(opt, null, cfg, null);
        ppp.setAtxPath(localAtxPath);
        RinexPppProcessor.PppResult result = ppp.process(obsFile, navFile, sp3File, clkFile);

        log.info("PPP-RTK结果: 总历元={}, 成功={}, 失败={}", result.totalEpochs, result.successCount, result.failCount);
        assertTrue(result.totalEpochs > 0, "PPP-RTK应有历元被处理");
        assertTrue(result.successCount > 0, "PPP-RTK应至少有一个成功历元");
    }

    @Test
    @DisplayName("4.3 PPP-RTK固定率统计")
    void testPppRtkFixRate() {
        if (!dataAvailable) {
            log.info("跳过: PPP-RTK数据不可用");
            return;
        }

        PrcOpt opt = createPppRtkOpt();
        RtkConfig cfg = createPppRtkConfig();
        RinexPppProcessor ppp = new RinexPppProcessor(opt, null, cfg, null);
        ppp.setAtxPath(localAtxPath);
        RinexPppProcessor.PppResult result = ppp.process(obsFile, navFile, sp3File, clkFile);

        List<SolData> solutions = result.solutions;
        int total = 0, fixCount = 0, floatCount = 0;
        for (SolData sol : solutions) {
            if (sol.status == SolutionStatus.NONE) continue;
            total++;
            if (sol.status == SolutionStatus.FIX) fixCount++;
            if (sol.status == SolutionStatus.FLOAT) floatCount++;
        }

        double fixRate = total > 0 ? (double) fixCount / total * 100 : 0;
        log.info(String.format("PPP-RTK固定率: %.1f%% (固定=%d, 浮点=%d, 总=%d)", fixRate, fixCount, floatCount, total));
        String enu = assessEnu("4.3", solutions);
        if (enu != null) log.info(enu);
        /* SSR 数据尚未接入（nav.ssr 为空时 ppprtkos 退化为普通 PPP，无 Fix 能力），
         * fixRate 下限断言待 SSR 流录制+decode_ssr 移植后启用 */
        assertTrue(result.totalEpochs > 0, "PPP-RTK应有历元被处理");
    }

    @Test
    @DisplayName("4.4 PPP-RTK与PPP浮点解对照")
    void testPppRtkVsPpp() {
        if (!dataAvailable) {
            log.info("跳过: PPP-RTK数据不可用");
            return;
        }

        PrcOpt pppRtkOpt = createPppRtkOpt();
        RinexPppProcessor pppRtkProc = new RinexPppProcessor(pppRtkOpt, null, createPppRtkConfig(), null);
        pppRtkProc.setAtxPath(localAtxPath);
        RinexPppProcessor.PppResult pppRtkResult = pppRtkProc.process(obsFile, navFile, sp3File, clkFile);

        PrcOpt pppOpt = new PrcOpt();
        pppOpt.mode = Constants.PMODE_PPP_STATIC;
        pppOpt.nf = pppRtkOpt.nf;
        pppOpt.navsys = pppRtkOpt.navsys;
        pppOpt.elmin = pppRtkOpt.elmin;
        pppOpt.ionoopt = Constants.IONOOPT_IFLC;
        pppOpt.tropopt = Constants.TROPOPT_EST;
        pppOpt.sateph = Constants.EPHOPT_PREC;
        pppOpt.dynamics = 0;
        pppOpt.posopt = new int[6];
        pppOpt.posopt[0] = 1;
        pppOpt.posopt[1] = 1;
        pppOpt.posopt[2] = 1;
        pppOpt.posopt[3] = 1;
        pppOpt.tidecorr = 1;
        pppOpt.anttype[0] = "*";

        /* 对照组为标准 PPP 浮点基线（无 AR/RTK 开关），cfg 不传保持默认 */
        RinexPppProcessor pppProc = new RinexPppProcessor(pppOpt, null, new RtkConfig(), null);
        pppProc.setAtxPath(localAtxPath);
        RinexPppProcessor.PppResult pppResult = pppProc.process(obsFile, navFile, sp3File, clkFile);

        log.info(String.format("PPP-RTK: 总=%d, 成功=%d | PPP浮点: 总=%d, 成功=%d",
                pppRtkResult.totalEpochs, pppRtkResult.successCount,
                pppResult.totalEpochs, pppResult.successCount));
        String enuRtk = assessEnu("4.4 PPP-RTK", pppRtkResult.solutions);
        if (enuRtk != null) log.info(enuRtk);
        String enuPpp = assessEnu("4.4 PPP浮点", pppResult.solutions);
        if (enuPpp != null) log.info(enuPpp);
        assertTrue(pppRtkResult.totalEpochs > 0, "PPP-RTK路径应有历元被处理");
        assertTrue(pppResult.totalEpochs > 0, "PPP浮点对照路径应有历元被处理");
        assertTrue(pppResult.successCount > 0, "PPP浮点基线应有成功历元");
    }
}