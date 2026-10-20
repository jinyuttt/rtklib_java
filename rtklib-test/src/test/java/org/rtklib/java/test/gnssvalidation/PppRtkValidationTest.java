package org.rtklib.java.test.gnssvalidation;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.rtklib.java.TestDataConfig;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
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
            + File.separator + "gnss-validation" + File.separator + "ppp-rtk" + File.separator + "bjfs";

    private static final String PRODUCT_DIR = TestDataConfig.getTestDataDir()
            + File.separator + "gnss-validation" + File.separator + "product";

    private static String obsFile;
    private static String navFile;
    private static String sp3File;
    private static String clkFile;
    private static String biaFile;
    private static String atxFile;
    private static boolean dataAvailable;

    static boolean dataAvailable() { return dataAvailable; }

    @BeforeAll
    static void checkData() {
        File obsDir = new File(DATA_DIR);
        File sp3Dir = new File(PRODUCT_DIR + File.separator + "sp3");
        File clkDir = new File(PRODUCT_DIR + File.separator + "clk");
        File biaDir = new File(PRODUCT_DIR + File.separator + "bia");
        File atxDir = new File(PRODUCT_DIR + File.separator + "atx");

        obsFile = findFile(obsDir, ".obs", ".25o", ".24o", ".23o", ".21o", ".18o");
        navFile = findFile(obsDir, ".nav", ".25n", ".24n", ".23n", ".21n", ".18n");
        sp3File = findFile(sp3Dir, ".sp3", ".SP3");
        clkFile = findFile(clkDir, ".clk", ".CLK");
        biaFile = findFile(biaDir, ".bia", ".BIA", ".bsx", ".BSX");
        atxFile = findFile(atxDir, ".atx", ".ATX");

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
        opt.ionoopt = Constants.IONOOPT_IF5;
        opt.tropopt = Constants.TROPOPT_EST;
        opt.sateph = Constants.EPHOPT_PREC;
        opt.dynamics = 0;
        opt.posopt = new int[6];
        return opt;
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
    void testPppR?tkPipeline() {
        if (!dataAvailable) {
            log.info("跳过: PPP-RTK数据不可用");
            return;
        }

        PrcOpt opt = createPppRtkOpt();
        RtkConfig cfg = new RtkConfig();
        cfg.enablePppRtk = true;
        cfg.enablePppRtkAR = true;

        RinexPppProcessor ppp = new RinexPppProcessor(opt);
        RinexPppProcessor.PppResult result = ppp.process(obsFile, navFile, sp3File, clkFile);

        log.info("PPP-RTK结果: 总历元={}, 成功={}, 失败={}", result.totalEpochs, result.successCount, result.failCount);
        assertTrue(result.totalEpochs > 0, "PPP-RTK应有历元被处理");
    }

    @Test
    @DisplayName("4.3 PPP-RTK固定率统计")
    void testPppRtkFixRate() {
        if (!dataAvailable) {
            log.info("跳过: PPP-RTK数据不可用");
            return;
        }

        PrcOpt opt = createPppRtkOpt();
        RinexPppProcessor ppp = new RinexPppProcessor(opt);
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
    }

    @Test
    @DisplayName("4.4 PPP-RTK与PPP浮点解对照")
    void testPppRtkVsPpp() {
        if (!dataAvailable) {
            log.info("跳过: PPP-RTK数据不可用");
            return;
        }

        PrcOpt pppRtkOpt = createPppRtkOpt();
        RinexPppProcessor pppRtkProc = new RinexPppProcessor(pppRtkOpt);
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

        RinexPppProcessor pppProc = new RinexPppProcessor(pppOpt);
        RinexPppProcessor.PppResult pppResult = pppProc.process(obsFile, navFile, sp3File, clkFile);

        log.info(String.format("PPP-RTK: 总=%d, 成功=%d | PPP浮点: 总=%d, 成功=%d",
                pppRtkResult.totalEpochs, pppRtkResult.successCount,
                pppResult.totalEpochs, pppResult.successCount));
    }
}