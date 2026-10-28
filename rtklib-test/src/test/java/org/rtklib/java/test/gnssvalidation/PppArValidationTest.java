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
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("阶段5: PPP-AR模糊度固定验证")
public class PppArValidationTest {

    private static final Logger log = LoggerFactory.getLogger(PppArValidationTest.class);

    /* 数据集选择（FIX-ALG-027 验证用）：默认 2024/WUM 常量 OSB 基线；GBM 288
     * 30s 时变 OSB 数据集通过环境变量切换（surefire 不透传 -D 自定义属性，
     * 项目约定改用 env，子进程自动继承）。 */
    private static String envSub(String envKey, String def) {
        String v = System.getenv(envKey);
        return (v == null || v.isEmpty()) ? def : v;
    }

    private static final String DATA_DIR = TestDataConfig.getTestDataDir()
            + File.separator + "gnss-validation" + File.separator + envSub("PPP_DATA_SUBDIR", "ppp" + File.separator + "algo");

    private static final String PRODUCT_DIR = TestDataConfig.getTestDataDir()
            + File.separator + "gnss-validation" + File.separator + envSub("PPP_PRODUCT_SUBDIR", "product");

    private static final String TABLE_DIR = TestDataConfig.getTestDataDir()
            + File.separator + "gnss-validation" + File.separator + envSub("PPP_TABLE_SUBDIR", "table");

    private static String obsFile;
    private static String navFile;
    private static String sp3File;
    private static String clkFile;
    private static String fcbFile;
    private static String updFile;
    private static String biaFile;
    private static String osbFile;
    private static String atxPath;
    private static boolean dataAvailable;
    private static boolean arProductAvailable;

    private static final int TEST_YEAR = Integer.parseInt(envSub("PPP_TEST_YEAR", "2024"));
    private static final int TEST_DOY = Integer.parseInt(envSub("PPP_TEST_DOY", "60"));

    /** 参考坐标：默认 FIX-ALG-019 ALGO 固定值；GBM 288 数据集经 PPP_REF_ECEF
     *  覆盖（X,Y,Z 米，逗号分隔）。框架差异（IGS14→IGS20）表现为 cm 级常量
     *  偏置，对 A/B 模式对比无影响。 */
    private static final double[] REF_ECEF = parseRefEcef();

    private static double[] parseRefEcef() {
        String v = System.getenv("PPP_REF_ECEF");
        if (v != null && !v.isEmpty()) {
            String[] p = v.split(",");
            if (p.length == 3) {
                try {
                    return new double[]{Double.parseDouble(p[0].trim()),
                            Double.parseDouble(p[1].trim()), Double.parseDouble(p[2].trim())};
                } catch (NumberFormatException ignored) {
                }
            }
        }
        return new double[]{918129.074497908, -4346071.34240674, 4561977.92989306};
    }

    @BeforeAll
    static void checkData() {
        GnssValidationDataHelper helper = new GnssValidationDataHelper(TestDataConfig.getTestDataDir());

        if (!GnssValidationDataHelper.isDataAvailable(DATA_DIR)) {
            log.info("PPP-AR观测数据不在本地，尝试自动下载ALGO RINEX 3...");
            helper.ensureIgsStationRinex("ppp", "algo", TEST_YEAR, TEST_DOY);
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

        File biaDirCheck = new File(PRODUCT_DIR + File.separator + "bia");
        boolean biaAvailable = biaDirCheck.exists() && biaDirCheck.list() != null && biaDirCheck.list().length > 0;
        File fcbDirCheck = new File(PRODUCT_DIR + File.separator + "fcb");
        File updDirCheck = new File(PRODUCT_DIR + File.separator + "upd");
        boolean fcbMissing = !fcbDirCheck.exists() || fcbDirCheck.list() == null || fcbDirCheck.list().length == 0;
        boolean updMissing = !updDirCheck.exists() || updDirCheck.list() == null || updDirCheck.list().length == 0;
        if (fcbMissing && updMissing) {
            if (biaAvailable) {
                log.info("FCB/UPD不可用，但BIA/OSB产品已存在，将使用BIA作为FCB替代（OsbReader→fcbWlByCode）");
            } else {
                log.info("PPP-AR模糊度产品(FCB/UPD/BIA)均不在本地，尝试自动下载FCB/UPD...");
                helper.ensureMgexProducts(TEST_YEAR, TEST_DOY,
                        ProductDownloader.ProductType.FCB,
                        ProductDownloader.ProductType.UPD);
            }
        }

        File obsDir = new File(DATA_DIR);
        File sp3Dir = new File(PRODUCT_DIR + File.separator + "sp3");
        File clkDir = new File(PRODUCT_DIR + File.separator + "clk");
        File biaDir = new File(PRODUCT_DIR + File.separator + "bia");
        File fcbDir = new File(PRODUCT_DIR + File.separator + "fcb");
        File updDir = new File(PRODUCT_DIR + File.separator + "upd");

        obsFile = findFile(obsDir, "_MO.rnx", ".obs", ".25o", ".24o", ".23o", ".21o", ".18o");
        navFile = findFile(obsDir, "_MN.rnx", ".nav", ".25n", ".24n", ".23n", ".21n", ".18n");
        sp3File = findFile(sp3Dir, ".sp3", ".SP3");
        clkFile = findFile(clkDir, ".clk", ".CLK");
        biaFile = findFile(biaDir, ".bia", ".BIA", ".bsx", ".BSX");
        atxPath = findFile(new File(TABLE_DIR), ".atx", ".ATX");

        fcbFile = findFile(fcbDir, ".fcb", ".FCB");
        updFile = findFile(updDir, ".upd", ".UPD");
        osbFile = findFile(biaDir, ".osb", ".OSB");

        dataAvailable = obsFile != null && navFile != null && sp3File != null && clkFile != null;
        arProductAvailable = fcbFile != null || updFile != null || biaFile != null || osbFile != null;

        log.info("PPP-AR数据: obs={}, nav={}", obsFile, navFile);
        log.info("PPP-AR产品: sp3={}, clk={}, bia={}", sp3File, clkFile, biaFile);
        log.info("PPP-AR模糊度产品: fcb={}, upd={}, osb={}", fcbFile, updFile, osbFile);
        if (arProductAvailable) {
            if (fcbFile == null && updFile == null && (biaFile != null || osbFile != null)) {
                log.info("PPP-AR: FCB/UPD不可用，使用BIA/OSB作为FCB替代（OsbReader→fcbWlByCode, fcbFromOsb=true）");
            }
        } else {
            log.info("PPP-AR模糊度产品不可用，需FCB/UPD/BIA/OSB文件启用模糊度固定");
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

    private static PrcOpt createPppArOpt() {
        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_PPP_STATIC;
        opt.nf = 2;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL;
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
        return opt;
    }

    private static RtkConfig createPppArConfig() {
        RtkConfig cfg = new RtkConfig();
        cfg.ppp.enableIsbClockInit = true;
        cfg.pppAr.enableAR = true;
        cfg.pppAr.arRatioWl = 2.0;
        cfg.pppAr.arRatioNl = 3.0;
        // FIX-ALG-026: integer-domain delta gate for IFLC rounding branches
        // (rejects whole-cycle WL/NL OSB biases invisible to frac gates)
        cfg.pppAr.enableDeltaGate = true;
        if (fcbFile != null) cfg.pppAr.fcbFile = fcbFile;
        if (updFile != null) cfg.pppAr.updFile = updFile;
        return cfg;
    }

    private static RtkConfig createPppArFixHoldConfig() {
        RtkConfig cfg = createPppArConfig();
        cfg.pppAr.enableFixHold = true;
        cfg.pppAr.fixHoldVar = 1e-6;
        cfg.pppAr.fixHoldMinEp = 50;
        return cfg;
    }

    private static RtkConfig createPppPartialArConfig() {
        RtkConfig cfg = createPppArConfig();
        cfg.pppAr.enablePartialAR = true;
        cfg.pppAr.partialArMinRatio = 2.0;
        cfg.pppAr.partialArMinSats = 4;
        return cfg;
    }

    private static RtkConfig createPppArIonEstConfig() {
        RtkConfig cfg = createPppArConfig();
        cfg.ppp.enableIsbIfcbIfb = true;
        return cfg;
    }

    private static void evaluatePppArAccuracy(PrcOpt opt, RtkConfig cfg, String label) {
        if (!dataAvailable) {
            log.info("跳过[{}]: 数据不可用", label);
            return;
        }

        SolOpt solOpt = new SolOpt();
        solOpt.solstatic = 0;
        RinexPppProcessor ppp = new RinexPppProcessor(opt, solOpt, cfg, null);
        ppp.setAtxPath(atxPath);
        if (fcbFile != null) ppp.setFcbPath(fcbFile);
        if (osbFile != null) ppp.setOsbPath(osbFile);
        if (updFile != null) ppp.setUpdPath(updFile);
        if (biaFile != null) ppp.setOsbPath(biaFile);
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
        log.info(String.format("[%s] 总历元=%d, 成功=%d, 失败=%d, 固定率=%.1f%% (fix=%d, float=%d)",
                label, result.totalEpochs, result.successCount, result.failCount, fixRate, fixCount, floatCount));

        if (solutions.size() < 30) {
            log.info("[{}] 历元数不足，无法评估精度", label);
            return;
        }

        double[] refLlh = new double[3];
        org.rtklib.java.coord.CoordTransform.ecef2pos(REF_ECEF, refLlh);

        int convergeSkip = solutions.size() / 3;
        double sumE = 0, sumN = 0, sumU = 0;
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
    @DisplayName("5.1 PPP-AR模糊度产品可用性检查")
    void testArProductAvailability() {
        log.info("FCB文件: {}", fcbFile != null ? fcbFile : "不可用");
        log.info("UPD文件: {}", updFile != null ? updFile : "不可用");
        log.info("BIA文件: {}", biaFile != null ? biaFile : "不可用");
        log.info("OSB文件: {}", osbFile != null ? osbFile : "不可用");
        if (!arProductAvailable) {
            log.info("PPP-AR需FCB/UPD/OSB产品，当前不可用，后续AR测试将跳过");
        }
    }

    @Test
    @DisplayName("5.2 PPP浮点解基线 (对照)")
    void testPppFloatBaseline() {
        if (!dataAvailable) {
            log.info("跳过: 数据不可用");
            return;
        }

        PrcOpt opt = createPppArOpt();
        RtkConfig cfg = new RtkConfig();
        cfg.ppp.enableIsbClockInit = true;
        evaluatePppArAccuracy(opt, cfg, "PPP-Float");
    }

    @Test
    @DisplayName("5.3 PPP-AR WL+NL固定")
    void testPppArWlNl() {
        if (!arProductAvailable) {
            log.info("跳过: 模糊度产品(FCB/UPD/OSB)不可用");
            return;
        }
        evaluatePppArAccuracy(createPppArOpt(), createPppArConfig(), "PPP-AR(WL+NL)");
    }

    @Test
    @DisplayName("5.4 PPP-AR Fix-and-Hold")
    void testPppArFixHold() {
        if (!arProductAvailable) {
            log.info("跳过: 模糊度产品(FCB/UPD/OSB)不可用");
            return;
        }
        evaluatePppArAccuracy(createPppArOpt(), createPppArFixHoldConfig(), "PPP-AR(FixHold)");
    }

    @Test
    @DisplayName("5.5 PPP-AR Partial AR")
    void testPppArPartial() {
        if (!arProductAvailable) {
            log.info("跳过: 模糊度产品(FCB/UPD/OSB)不可用");
            return;
        }
        evaluatePppArAccuracy(createPppArOpt(), createPppPartialArConfig(), "PPP-AR(Partial)");
    }

    @Test
    @DisplayName("5.6 PPP-AR + 非组合PPP + ISB")
    void testPppArIonEstIsb() {
        if (!arProductAvailable) {
            log.info("跳过: 模糊度产品(FCB/UPD/OSB)不可用");
            return;
        }
        PrcOpt opt = createPppArOpt();
        opt.ionoopt = Constants.IONOOPT_EST;
        evaluatePppArAccuracy(opt, createPppArIonEstConfig(), "PPP-AR(EST+ISB)");
    }

    @Test
    @DisplayName("5.7 PPP-AR 三频 EWL+WL+NL 级联固定")
    void testPppAr3FreqEwlWlNl() {
        if (!arProductAvailable) {
            log.info("跳过: 模糊度产品(FCB/UPD/OSB)不可用");
            return;
        }
        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_PPP_STATIC;
        opt.nf = 3;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GAL;
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

        RtkConfig cfg = new RtkConfig();
        cfg.ppp.enableIsbClockInit = true;
        cfg.pppAr.enableMultiFreqAR = true;
        cfg.pppAr.enableAR = false;
        cfg.pppAr.arRatioWl = 2.0;
        cfg.pppAr.arRatioNl = 3.0;
        // FIX-ALG-028: 3F branch previously ran without the integer-domain
        // delta gate — wrong WL/NL integers were frozen via initx(var=0).
        // Delta gate available but OFF for the primary A/B (gold-standard
        // 65.7%/3.54cm comparability); a gate-on run records the interception
        // behavior separately.
        cfg.pppAr.enableDeltaGate = false;
        if (fcbFile != null) cfg.pppAr.fcbFile = fcbFile;
        if (updFile != null) cfg.pppAr.updFile = updFile;

        evaluatePppArAccuracy(opt, cfg, "PPP-AR(3F-EWL+WL+NL)");
    }
}