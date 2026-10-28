package org.rtklib.java.test.gnssvalidation;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.rtklib.java.TestDataConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.data.*;
import org.rtklib.java.product.ProductDownloader;
import org.rtklib.java.rinex.RinexPppProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("阶段3: 事后PPP链路验证")
public class PppValidationTest {

    private static final Logger log = LoggerFactory.getLogger(PppValidationTest.class);

    private static final String DATA_DIR = TestDataConfig.getTestDataDir()
            + File.separator + "gnss-validation" + File.separator + "ppp" + File.separator + "algo";

    private static final String PRODUCT_DIR = TestDataConfig.getTestDataDir()
            + File.separator + "gnss-validation" + File.separator + "product";

    private static final String REFERENCE_DIR = TestDataConfig.getTestDataDir()
            + File.separator + "gnss-validation" + File.separator + "reference";

    private static final String ATX_DIR = TestDataConfig.getTestDataDir()
            + File.separator + "gnss-validation" + File.separator + "table";

    private static String obsFile;
    private static String navFile;
    private static String sp3File;
    private static String clkFile;
    private static String sinexFile;
    private static String atxPath;
    private static String dcbFile;
    private static boolean dataAvailable;

    static boolean dataAvailable() { return dataAvailable; }

    private static final int TEST_YEAR = 2024;
    private static final int TEST_DOY = 60;

    @BeforeAll
    static void checkData() {
        GnssValidationDataHelper helper = new GnssValidationDataHelper(TestDataConfig.getTestDataDir());

        if (!GnssValidationDataHelper.isDataAvailable(DATA_DIR)) {
            log.info("PPP观测数据不在本地，尝试自动下载ALGO RINEX 3...");
            helper.ensureIgsStationRinex("ppp", "algo", TEST_YEAR, TEST_DOY);
        }

        File sp3Dir = new File(PRODUCT_DIR + File.separator + "sp3");
        File clkDir = new File(PRODUCT_DIR + File.separator + "clk");
        if (!sp3Dir.exists() || !clkDir.exists() || sp3Dir.list().length == 0 || clkDir.list().length == 0) {
            log.info("MGEX精密产品不在本地，尝试自动下载SP3+CLK...");
            helper.ensureMgexProducts(TEST_YEAR, TEST_DOY,
                    ProductDownloader.ProductType.SP3, ProductDownloader.ProductType.CLK);
        }

        File obsDir = new File(DATA_DIR);
        sp3Dir = new File(PRODUCT_DIR + File.separator + "sp3");
        clkDir = new File(PRODUCT_DIR + File.separator + "clk");
        File refDir = new File(REFERENCE_DIR);

        obsFile = findFile(obsDir, "_MO.rnx", ".obs", ".25o", ".24o", ".23o", ".21o", ".18o");
        navFile = findFile(obsDir, "_MN.rnx", ".nav", ".25n", ".24n", ".23n", ".21n", ".18n");
        sp3File = findFile(sp3Dir, ".sp3", ".SP3");
        clkFile = findFile(clkDir, ".clk", ".CLK");
        sinexFile = findFile(refDir, ".snx", ".SNX", ".pos", ".POS");

        atxPath = findAtxFile(ATX_DIR);
        if (atxPath != null) {
            log.info("ANTEX文件: {}", atxPath);
        } else {
            log.info("ANTEX文件不可用，天线PCV改正将不启用");
        }

        /* WUM OSB BIA 码偏差产品（2024 DOY 060，与 TEST_YEAR/TEST_DOY 匹配） */
        dcbFile = findFile(new File(PRODUCT_DIR + File.separator + "bia"), ".BIA", ".bia");
        log.info("DCB产品: {}", dcbFile);

        dataAvailable = obsFile != null && navFile != null && sp3File != null && clkFile != null;

        log.info("PPP数据: obs={}, nav={}", obsFile, navFile);
        log.info("PPP产品: sp3={}, clk={}", sp3File, clkFile);
        log.info("SINEX参考: {}", sinexFile);
        if (!dataAvailable) {
            log.info("PPP数据不完整，请将BJFS观测+导航+MGEX产品放入对应目录");
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

    /** JAVA-ONLY 开关：PPP 精密模式用 SPP 的系统间偏差(ISB)初始化各系统接收机钟差
     * （RTKLIB 2.5.0 ppp.c udclk_ppp 精密分支忽略 ISB，GLO/GAL 残差数百米被永久剔除，
     * 实际仅 GPS 参与解算；开启后多系统 PPP 可用性显著改善，见 FIX-ALG-011） */
    private static RtkConfig createPppConfig() {
        RtkConfig cfg = new RtkConfig();
        cfg.ppp.enableIsbClockInit = true;
        return cfg;
    }

    private static PrcOpt createPppOpt() {
        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_PPP_STATIC;
        opt.nf = 2;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GLO;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_IFLC;
        opt.tropopt = Constants.TROPOPT_EST;
        opt.sateph = Constants.EPHOPT_PREC;
        opt.dynamics = 0;
        opt.posopt = new int[6];
        opt.posopt[0] = 1;
        opt.posopt[1] = 1;
        opt.posopt[2] = 1;
        opt.posopt[3] = 1;
        opt.tidecorr = 1;
        opt.anttype[0] = "*";
        opt.thresslip = 9999.0;
        opt.maxinno = new double[]{5.0, 30.0};
        log.info("[调参配置] navsys=GPS+GLO, ionoopt=IFLC, sateph=PREC");
        return opt;
    }

    private static PrcOpt createPppOptMultiSys() {
        PrcOpt opt = createPppOpt();
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL | Constants.SYS_CMP;
        log.info("[调参配置] navsys=GPS+GLO+GAL+CMP (多系统扩展)");
        return opt;
    }

    private static PrcOpt createPppOptIonEst() {
        PrcOpt opt = createPppOpt();
        opt.ionoopt = Constants.IONOOPT_EST;
        log.info("[调参配置] ionoopt=EST (非组合PPP, 电离层逐卫星估计)");
        return opt;
    }

    /** EST 模式专用配置：JAVA-ONLY enableIonoEstRobust（FIX-ALG-020，
     * 首历元对刚初始化的 bias/ion 状态跳过相位 prefit 剔除，解除 C 固有死锁） */
    private static RtkConfig createPppConfigIonoEstRobust() {
        RtkConfig cfg = createPppConfig();
        cfg.ppp.enableIonoEstRobust = true;
        log.info("[调参配置] enableIonoEstRobust=true (EST 首历元健壮初始化)");
        return cfg;
    }

    private static PrcOpt createPppOptIonEstMultiSys() {
        PrcOpt opt = createPppOpt();
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL | Constants.SYS_CMP;
        opt.ionoopt = Constants.IONOOPT_EST;
        log.info("[调参配置] navsys=GPS+GLO+GAL+CMP, ionoopt=EST (非组合+多系统)");
        return opt;
    }

    private static RtkConfig createPppConfigIsb() {
        RtkConfig cfg = createPppConfig();
        cfg.ppp.enableIsbIfcbIfb = true;
        log.info("[调参配置] enableIsbIfcbIfb=true (ISB/IFCB/IFB偏差估计)");
        return cfg;
    }

    private static RtkConfig createPppConfigIers2010() {
        RtkConfig cfg = createPppConfig();
        cfg.ppp.enableIers2010 = true;
        cfg.ppp.enableAt1S2 = true;
        log.info("[调参配置] enableIers2010=true, enableAt1S2=true");
        return cfg;
    }

    private static final double[] REF_ECEF = {918129.074497908, -4346071.34240674, 4561977.92989306};

    private static void evaluatePppAccuracy(PrcOpt opt, RtkConfig cfg, String label) {
        evaluatePppAccuracy(opt, cfg, label, null);
    }

    private static void evaluatePppAccuracy(PrcOpt opt, RtkConfig cfg, String label, String dcbPath) {
        if (!dataAvailable) {
            log.info("跳过[{}]: PPP数据不可用", label);
            return;
        }

        SolOpt solOpt = new SolOpt();
        solOpt.solstatic = 0;
        RinexPppProcessor ppp = new RinexPppProcessor(opt, solOpt, cfg, null);
        ppp.setAtxPath(atxPath);
        if (dcbPath != null) ppp.setDcbPath(dcbPath);
        RinexPppProcessor.PppResult result = ppp.process(obsFile, navFile, sp3File, clkFile);

        List<SolData> solutions = result.solutions;
        log.info("[{}] 总历元={}, 成功={}, 失败={}", label, result.totalEpochs, result.successCount, result.failCount);
        if (solutions.size() < 30) {
            log.info("[{}] 历元数不足，无法评估精度", label);
            return;
        }

        double[] refLlh = new double[3];
        org.rtklib.java.coord.CoordTransform.ecef2pos(REF_ECEF, refLlh);

        int convergeSkip = solutions.size() / 3;
        double sumE = 0, sumN = 0, sumU = 0;
        double sumAbsE = 0, sumAbsN = 0, sumAbsU = 0;
        double meanE = 0, meanN = 0, meanU = 0;
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
            sumAbsE += Math.abs(enu[0]);
            sumAbsN += Math.abs(enu[1]);
            sumAbsU += Math.abs(enu[2]);
            meanE += enu[0];
            meanN += enu[1];
            meanU += enu[2];
            count++;
        }

        if (count < 1) { log.info("[{}] 收敛后无有效解", label); return; }
        double rmsE = Math.sqrt(sumE / count);
        double rmsN = Math.sqrt(sumN / count);
        double rmsU = Math.sqrt(sumU / count);
        meanE /= count; meanN /= count; meanU /= count;
        log.info(String.format("[%s] ENU RMS: E=%.4fm N=%.4fm U=%.4fm 2D=%.4fm | Mean: dE=%.4fm dN=%.4fm dU=%.4fm | %d历元",
                label, rmsE, rmsN, rmsU, Math.sqrt(rmsE * rmsE + rmsN * rmsN), meanE, meanN, meanU, count));
    }

    @Test
    @DisplayName("3.1 SP3/CLK精密产品加载")
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
    }

    @Test
    @DisplayName("3.2 PPP解算链路通")
    void testPppPipeline() {
        if (!dataAvailable) {
            log.info("跳过: PPP数据不可用");
            return;
        }

        PrcOpt opt = createPppOpt();
        RinexPppProcessor ppp = new RinexPppProcessor(opt, null, createPppConfig(), null);
        ppp.setAtxPath(atxPath);
        RinexPppProcessor.PppResult result = ppp.process(obsFile, navFile, sp3File, clkFile);

        log.info("PPP结果: 总历元={}, 成功={}, 失败={}", result.totalEpochs, result.successCount, result.failCount);
        assertTrue(result.totalEpochs > 0, "PPP应有历元被处理");
        assertTrue(result.successCount > 0, "PPP应有成功历元");
    }

    @Test
    @DisplayName("3.3 PPP坐标与SINEX参考值比对")
    void testPppVsSinex() {
        if (!dataAvailable) {
            log.info("跳过: PPP数据不可用");
            return;
        }
        if (sinexFile == null) {
            log.info("跳过: SINEX参考坐标不可用，请将参考坐标文件放入 {}", REFERENCE_DIR);
            return;
        }

        PrcOpt opt = createPppOpt();
        SolOpt solOpt = new SolOpt();
        solOpt.solstatic = 0;
        RinexPppProcessor ppp = new RinexPppProcessor(opt, solOpt, createPppConfig(), null);
        ppp.setAtxPath(atxPath);
        RinexPppProcessor.PppResult result = ppp.process(obsFile, navFile, sp3File, clkFile);

        List<SolData> solutions = result.solutions;
        assertNotNull(solutions);
        assertFalse(solutions.isEmpty());

        int pppCount = 0;
        double sumLat = 0, sumLon = 0, sumH = 0;
        for (SolData sol : solutions) {
            if (sol.status == SolutionStatus.NONE) continue;
            Position llh = sol.getPosition(CoordType.LLH);
            if (llh == null) continue;
            sumLat += llh.v1 * Constants.R2D;
            sumLon += llh.v2 * Constants.R2D;
            sumH += llh.v3;
            pppCount++;
        }

        if (pppCount > 0) {
            double avgLat = sumLat / pppCount;
            double avgLon = sumLon / pppCount;
            double avgH = sumH / pppCount;
            log.info(String.format("PPP平均坐标: 纬度=%.6f°, 经度=%.6f°, 高度=%.3fm", avgLat, avgLon, avgH));
            log.info("SINEX参考文件: {} (需手动比对或实现自动解析)", sinexFile);
        }
    }

    @Test
    @DisplayName("3.4 PPP收敛后精度统计")
    void testPppConvergence() {
        if (!dataAvailable) {
            log.info("跳过: PPP数据不可用");
            return;
        }

        PrcOpt opt = createPppOpt();
        SolOpt solOpt = new SolOpt();
        solOpt.solstatic = 0;
        RinexPppProcessor ppp = new RinexPppProcessor(opt, solOpt, createPppConfig(), null);
        ppp.setAtxPath(atxPath);
        RinexPppProcessor.PppResult result = ppp.process(obsFile, navFile, sp3File, clkFile);

        List<SolData> solutions = result.solutions;
        log.info("PPP收敛评估: solutions.size()={}, totalEpochs={}, successCount={}, failCount={}",
                solutions.size(), result.totalEpochs, result.successCount, result.failCount);
        if (solutions.size() < 30) {
            log.info("历元数不足，无法评估收敛");
            return;
        }

        for (int i = 0; i < Math.min(5, solutions.size()); i++) {
            SolData s = solutions.get(i);
            Position p = s.getPosition(CoordType.ECEF);
            if (p != null) log.info(String.format("  sol[%d]: X=%.4f Y=%.4f Z=%.4f stat=%s", i, p.v1, p.v2, p.v3, s.status));
        }
        for (int i = Math.max(0, solutions.size() - 5); i < solutions.size(); i++) {
            SolData s = solutions.get(i);
            Position p = s.getPosition(CoordType.ECEF);
            if (p != null) log.info(String.format("  sol[%d]: X=%.4f Y=%.4f Z=%.4f stat=%s", i, p.v1, p.v2, p.v3, s.status));
        }

        // UNR/NGL (Nevada Geodetic Lab, JPL orbits, IGS20 frame) daily solution
        // for ALGO on the observation epoch 2023-11-01 (23NOV01), from
        // https://geodesy.unr.edu/gps_timeseries/IGS20/txyz/ALGO.txyz2
        // Independent same-day reference — see ALGORITHM_FIX_LOG (E-bias root cause).
        double[] refEcef = {918129.074497908, -4346071.34240674, 4561977.92989306};
        double[] refLlh = new double[3];
        org.rtklib.java.coord.CoordTransform.ecef2pos(refEcef, refLlh);
        log.info(String.format("ALGO参考坐标(IGb14/ITRF2014): X=%.5f Y=%.5f Z=%.5f  lat=%.9f° lon=%.9f° h=%.4fm",
                refEcef[0], refEcef[1], refEcef[2], refLlh[0]*Constants.R2D, refLlh[1]*Constants.R2D, refLlh[2]));

        int convergeSkip = solutions.size() / 3;
        log.info("收敛跳过前 {} 个历元 (1/3)", convergeSkip);

        double[] meanEcef = {0, 0, 0};
        int meanCount = 0;
        for (int i = convergeSkip; i < solutions.size(); i++) {
            SolData sol = solutions.get(i);
            if (sol.status == SolutionStatus.NONE) continue;
            Position ecef = sol.getPosition(CoordType.ECEF);
            if (ecef == null) continue;
            meanEcef[0] += ecef.v1;
            meanEcef[1] += ecef.v2;
            meanEcef[2] += ecef.v3;
            meanCount++;
        }
        if (meanCount == 0) { log.info("收敛后无有效解"); return; }
        meanEcef[0] /= meanCount;
        meanEcef[1] /= meanCount;
        meanEcef[2] /= meanCount;

        double[] meanLlh = new double[3];
        org.rtklib.java.coord.CoordTransform.ecef2pos(meanEcef, meanLlh);
        log.info(String.format("PPP均值坐标: X=%.3f Y=%.3f Z=%.3f  lat=%.6f° lon=%.6f° h=%.3fm",
                meanEcef[0], meanEcef[1], meanEcef[2], meanLlh[0]*Constants.R2D, meanLlh[1]*Constants.R2D, meanLlh[2]));

        double[] drMean = {meanEcef[0] - refEcef[0], meanEcef[1] - refEcef[1], meanEcef[2] - refEcef[2]};
        double[] enuMean = new double[3];
        org.rtklib.java.coord.CoordTransform.ecef2enu(refLlh, drMean, enuMean);
        log.info(String.format("PPP均值 vs ALGO参考: dE=%.4fm dN=%.4fm dU=%.4fm 2D=%.4fm",
                enuMean[0], enuMean[1], enuMean[2], Math.sqrt(enuMean[0]*enuMean[0] + enuMean[1]*enuMean[1])));

        double sumE = 0, sumN = 0, sumU = 0;
        double sumAbsE = 0, sumAbsN = 0, sumAbsU = 0;
        int count = 0;
        Position firstEcef = null, lastEcef = null;
        for (int i = convergeSkip; i < solutions.size(); i++) {
            SolData sol = solutions.get(i);
            if (sol.status == SolutionStatus.NONE) continue;
            Position ecef = sol.getPosition(CoordType.ECEF);
            if (ecef == null) continue;
            if (firstEcef == null) firstEcef = ecef;
            lastEcef = ecef;
            double[] dr = {ecef.v1 - refEcef[0], ecef.v2 - refEcef[1], ecef.v3 - refEcef[2]};
            double[] enu = new double[3];
            org.rtklib.java.coord.CoordTransform.ecef2enu(refLlh, dr, enu);
            sumE += enu[0] * enu[0];
            sumN += enu[1] * enu[1];
            sumU += enu[2] * enu[2];
            sumAbsE += Math.abs(enu[0]);
            sumAbsN += Math.abs(enu[1]);
            sumAbsU += Math.abs(enu[2]);
            count++;
        }

        if (count > 1 && firstEcef != null && lastEcef != null) {
            double rmsE = Math.sqrt(sumE / count);
            double rmsN = Math.sqrt(sumN / count);
            double rmsU = Math.sqrt(sumU / count);
            log.info(String.format("PPP收敛后(跳过前1/3) vs ALGO: ENU RMS: E=%.4fm, N=%.4fm, U=%.4fm, 2D=%.4fm (%d历元)",
                    rmsE, rmsN, rmsU, Math.sqrt(rmsE*rmsE + rmsN*rmsN), count));
            log.info(String.format("PPP收敛后 vs ALGO: ENU MeanAbs: E=%.4fm, N=%.4fm, U=%.4fm",
                    sumAbsE / count, sumAbsN / count, sumAbsU / count));
            double[] coordDr = {lastEcef.v1 - firstEcef.v1, lastEcef.v2 - firstEcef.v2, lastEcef.v3 - firstEcef.v3};
            double coordDrNorm = Math.sqrt(coordDr[0]*coordDr[0] + coordDr[1]*coordDr[1] + coordDr[2]*coordDr[2]);
            log.info(String.format("坐标漂移诊断: 首末历元ECEF差=%.6fm (若~0则坐标焊死)", coordDrNorm));
        }
    }

    @Test
    @DisplayName("3.5 优化项: 非组合PPP (ionoopt=EST)")
    void testOptIonEst() {
        evaluatePppAccuracy(createPppOptIonEst(), createPppConfigIonoEstRobust(), "IFLC→EST");
    }

    @Test
    @DisplayName("3.6 优化项: 多系统 (GPS+GLO+GAL+CMP)")
    void testOptMultiSys() {
        evaluatePppAccuracy(createPppOptMultiSys(), createPppConfig(), "G+G→G+G+E+C");
    }

    @Test
    @DisplayName("3.7 优化项: 非组合PPP + 多系统")
    void testOptIonEstMultiSys() {
        evaluatePppAccuracy(createPppOptIonEstMultiSys(), createPppConfigIonoEstRobust(), "EST+MultiSys");
    }

    @Test
    @DisplayName("3.8 优化项: ISB/IFCB/IFB偏差估计 (IFLC+ISB耦合恶化N/U, 改用EST)")
    void testOptIsb() {
        RtkConfig cfg = createPppConfigIsb();
        cfg.ppp.enableIonoEstRobust = true;
        evaluatePppAccuracy(createPppOptIonEst(), cfg, "ISB+EST");
    }

    @Test
    @DisplayName("3.9 优化项: ISB + 非组合PPP + 多系统")
    void testOptIsbIonEst() {
        RtkConfig cfg = createPppConfigIsb();
        cfg.ppp.enableIonoEstRobust = true;
        evaluatePppAccuracy(createPppOptIonEstMultiSys(), cfg, "ISB+EST+Multi");
    }

    @Test
    @DisplayName("3.10 优化项: ISB + IFLC + 多系统 (IFLC+ISB耦合在多系统下的缓解效果)")
    void testOptIsbIonEstMultiSys() {
        RtkConfig cfg = createPppConfigIsb();
        evaluatePppAccuracy(createPppOptMultiSys(), cfg, "ISB+IFLC+Multi");
    }

    @Test
    @DisplayName("3.11 优化项: IERS2010潮汐改正")
    void testOptIers2010() {
        evaluatePppAccuracy(createPppOpt(), createPppConfigIers2010(), "IERS2010");
    }

    @Test
    @DisplayName("3.12 优化项: IERS2010 + 非组合PPP + 多系统")
    void testOptIers2010IonEstMultiSys() {
        RtkConfig cfg = createPppConfigIers2010();
        cfg.ppp.enableIonoEstRobust = true;
        evaluatePppAccuracy(createPppOptIonEstMultiSys(), cfg, "IERS2010+EST+Multi");
    }

    @Test
    @DisplayName("3.13 优化项: 全量最优组合 (ISB+IERS2010+EST+MultiSys)")
    void testOptAllCombined() {
        RtkConfig cfg = createPppConfig();
        cfg.ppp.enableIsbIfcbIfb = true;
        cfg.ppp.enableIers2010 = true;
        cfg.ppp.enableAt1S2 = true;
        cfg.ppp.enableIonoEstRobust = true;
        evaluatePppAccuracy(createPppOptIonEstMultiSys(), cfg, "ALL-COMBINED");
    }

    @Test
    @DisplayName("3.14 优化项: 非组合PPP + WUM DCB 产品 + enableIonoEstRobust")
    void testOptIonEstWithDcb() {
        RtkConfig cfg = createPppConfig();
        cfg.ppp.enableIonoEstRobust = true;
        evaluatePppAccuracy(createPppOptIonEst(), cfg, "EST+DCB+Robust", dcbFile);
    }

    @Test
    @DisplayName("3.15 优化项: 非组合PPP+多系统 + WUM DCB 产品 + enableIonoEstRobust")
    void testOptIonEstMultiSysWithDcb() {
        RtkConfig cfg = createPppConfig();
        cfg.ppp.enableIonoEstRobust = true;
        evaluatePppAccuracy(createPppOptIonEstMultiSys(), cfg, "EST+MultiSys+DCB+Robust", dcbFile);
    }
}