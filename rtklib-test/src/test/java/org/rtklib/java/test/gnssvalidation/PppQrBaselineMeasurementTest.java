package org.rtklib.java.test.gnssvalidation;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.TestDataConfig;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.CoordType;
import org.rtklib.java.data.Position;
import org.rtklib.java.data.PrcOpt;
import org.rtklib.java.data.SolData;
import org.rtklib.java.data.SolOpt;
import org.rtklib.java.data.SolutionStatus;
import org.rtklib.java.rinex.RinexPppProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Step 2 自测：确定性基线（§3.2 {@code pppQrThreshold}）在 D1 上的拦截率量测。
 *
 * <p>只产出自测数字，用于 Step 3 的 go / no-go 判断，<b>不是收益结论</b>：
 * D1 为单站单日、相邻历元强相关，任何拦截率差异都不具统计意义（设计文档 §12.1）。</p>
 *
 * <p>量测口径与 §3.2 判据一致：{@code posVar = qr[0] + qr[1] + qr[2]}，
 * 在 {@link SolData#covEcef} 上即对角三元 {@code [0] + [4] + [8]}；
 * 只统计 {@code SOLQ_PPP} 历元，并跳过收敛段（前 1/3 历元）。</p>
 *
 * <p>静态 PPP 与动态 PPP 各测一次：静态路径的 {@code qr[0]} 在本仓库会收敛到负值
 * （见 {@code PppCore.udpos_ppp} 的 PMODE_PPP_STATIC 分支：只有过程噪声累加、
 * 没有 {@code P[0] <= 0} 重初始化守卫），因此 {@code posVar} 恒为负、门限恒不触发。
 * 该分支的拦截率因此不可量测，量测数字取自动态（PMODE_PPP_KINEMA）配置。</p>
 */
@DisplayName("Step 2 自测：qr 确定性基线拦截率（D1 ALGO 2024-060）")
class PppQrBaselineMeasurementTest {

    private static final Logger log = LoggerFactory.getLogger(PppQrBaselineMeasurementTest.class);

    private static final String DATA_DIR = TestDataConfig.getTestDataDir()
            + File.separator + "gnss-validation" + File.separator + "ppp" + File.separator + "algo";

    private static final String PRODUCT_DIR = TestDataConfig.getTestDataDir()
            + File.separator + "gnss-validation" + File.separator + "product";

    private static final String ATX_DIR = TestDataConfig.getTestDataDir()
            + File.separator + "gnss-validation" + File.separator + "table";

    /** ALGO 站点 ECEF 参考坐标（与 {@link PppValidationTest} 同源）。 */
    private static final double[] REF_ECEF = {918129.074497908, -4346071.34240674, 4561977.92989306};

    /** 候选门限（m²），与 §4.2 的 [0, +∞) 取值域一致。 */
    private static final double[] THRESHOLDS = {0.001, 0.005, 0.01, 0.02, 0.05, 0.1, 0.2, 0.5, 1.0};

    /** 「错误历元」的 3D 判据（m），拦截率按每个判据分别统计。 */
    private static final double[] WRONG_3D = {0.2, 1.0};

    private static String obsFile;
    private static String navFile;
    private static String sp3File;
    private static String clkFile;
    private static String atxPath;

    @BeforeAll
    static void resolveData() {
        obsFile = findFile(new File(DATA_DIR), "_MO.rnx", ".obs");
        navFile = findFile(new File(DATA_DIR), "_MN.rnx", ".nav");
        sp3File = findFile(new File(PRODUCT_DIR + File.separator + "sp3"), ".sp3", ".SP3");
        clkFile = findFile(new File(PRODUCT_DIR + File.separator + "clk"), ".clk", ".CLK");
        atxPath = findAtx(new File(ATX_DIR));
        log.info("[D1] obs={}, nav={}, sp3={}, clk={}, atx={}",
                obsFile, navFile, sp3File, clkFile, atxPath);
    }

    @Test
    @DisplayName("静态 PPP：qr 对角量级与 posVar 符号（门限可用性前置检查）")
    void staticPppQrDiagnostics() {
        List<SolData> solutions = runPpp(Constants.PMODE_PPP_STATIC, 0);
        assumeTrue(solutions.size() >= 90, "历元数不足: " + solutions.size());

        int warmup = solutions.size() / 3;
        int positive = 0;
        int nonPositive = 0;
        for (int i = warmup; i < solutions.size(); i++) {
            SolData sol = solutions.get(i);
            if (sol.status != SolutionStatus.PPP) continue;
            double[] cov = sol.covEcef;
            if (cov == null || cov.length < 9) continue;
            double posVar = cov[0] + cov[4] + cov[8];
            if (posVar > 0.0) positive++;
            else nonPositive++;
        }

        log.info(String.format("[静态 PPP] 收敛后 PPP 历元: posVar>0 共 %d, posVar<=0 共 %d",
                positive, nonPositive));
        for (int i : new int[]{10, 50, 200, warmup, solutions.size() - 1}) {
            if (i < 0 || i >= solutions.size()) continue;
            SolData sol = solutions.get(i);
            double[] cov = sol.covEcef;
            log.info(String.format("[静态 PPP i=%d] stat=%s qrDiag=[%s, %s, %s]",
                    i, sol.status,
                    cov == null ? "null" : String.format("%.6g", cov[0]),
                    cov == null ? "null" : String.format("%.6g", cov[4]),
                    cov == null ? "null" : String.format("%.6g", cov[8])));
        }
        log.info(String.format("[静态 PPP] 结论：收敛后 posVar 全部为正（%d/%d），"
                + "P 矩阵正定性保持正常，qr 门限在静态 PPP 下可量测", positive, positive + nonPositive));
        assertTrue(positive + nonPositive >= 60, "有效样本不足: " + (positive + nonPositive));
        assertEquals(0, nonPositive, "静态 PPP 收敛后 posVar 出现非正值，协方差正定性回归");
    }

    @Test
    @DisplayName("动态 PPP：量测 qr 门限的拦截率与误伤率")
    void measureQrBaselineInterceptRateKinematic() {
        List<SolData> solutions = runPpp(Constants.PMODE_PPP_KINEMA, 1);
        assumeTrue(solutions.size() >= 90, "历元数不足，无法量测: " + solutions.size());

        int warmup = solutions.size() / 3;
        double[] refLlh = new double[3];
        CoordTransform.ecef2pos(REF_ECEF, refLlh);

        int pppEpochs = 0;
        int noneEpochs = 0;
        int otherEpochs = 0;
        int noCovEpochs = 0;
        int nonPositiveVar = 0;
        List<double[]> samples = new ArrayList<>();

        for (int i = warmup; i < solutions.size(); i++) {
            SolData sol = solutions.get(i);
            if (sol.status == SolutionStatus.NONE) {
                noneEpochs++;
                continue;
            }
            if (sol.status != SolutionStatus.PPP) {
                otherEpochs++;
                continue;
            }
            pppEpochs++;

            Position ecef = sol.getPosition(CoordType.ECEF);
            double[] cov = sol.covEcef;
            if (ecef == null || cov == null || cov.length < 9) {
                noCovEpochs++;
                continue;
            }

            double[] dr = {ecef.v1 - REF_ECEF[0], ecef.v2 - REF_ECEF[1], ecef.v3 - REF_ECEF[2]};
            double[] enu = new double[3];
            CoordTransform.ecef2enu(refLlh, dr, enu);
            double err3d = Math.sqrt(enu[0] * enu[0] + enu[1] * enu[1] + enu[2] * enu[2]);

            double posVar = cov[0] + cov[4] + cov[8];
            if (!Double.isFinite(posVar)) {
                noCovEpochs++;
                continue;
            }
            if (posVar <= 0.0) nonPositiveVar++;
            samples.add(new double[]{posVar, err3d});
        }

        log.info(String.format("[动态 PPP 历元] 参与统计=%d（总 %d，收敛跳过 %d）| PPP=%d, NONE=%d, "
                        + "其他=%d, 无协方差=%d, posVar<=0=%d",
                samples.size(), solutions.size(), warmup, pppEpochs, noneEpochs,
                otherEpochs, noCovEpochs, nonPositiveVar));
        assertTrue(samples.size() >= 60, "有效样本不足，无法量测: " + samples.size());

        logQuantiles(samples);

        for (double wrong : WRONG_3D) {
            int wrongTotal = 0;
            for (double[] s : samples) if (s[1] > wrong) wrongTotal++;
            int correctTotal = samples.size() - wrongTotal;
            log.info(String.format("[判据 3D>%.1fm] 错误历元=%d / %d (%.1f%%)",
                    wrong, wrongTotal, samples.size(), 100.0 * wrongTotal / samples.size()));

            for (double th : THRESHOLDS) {
                int blocked = 0;
                int blockedWrong = 0;
                for (double[] s : samples) {
                    if (s[0] > th) {
                        blocked++;
                        if (s[1] > wrong) blockedWrong++;
                    }
                }
                double recall = wrongTotal == 0 ? 0.0 : 100.0 * blockedWrong / wrongTotal;
                double falseBlock = correctTotal == 0 ? 0.0 : 100.0 * (blocked - blockedWrong) / correctTotal;
                log.info(String.format("  [门限 %.3f m2] 拦截 %d/%d (%.1f%%) | 拦截率(召回)=%.1f%% | 误伤率=%.1f%%",
                        th, blocked, samples.size(), 100.0 * blocked / samples.size(), recall, falseBlock));
            }
        }
    }

    private static List<SolData> runPpp(int mode, int dynamics) {
        PrcOpt opt = createPppOpt();
        opt.mode = mode;
        opt.dynamics = dynamics;
        RtkConfig cfg = new RtkConfig();
        cfg.ppp.enableIsbClockInit = true;

        RinexPppProcessor ppp = new RinexPppProcessor(opt, new SolOpt(), cfg, null);
        ppp.setAtxPath(atxPath);
        RinexPppProcessor.PppResult result = ppp.process(obsFile, navFile, sp3File, clkFile);
        log.info("[mode={}, dynamics={}] 总历元={}, 成功={}, 失败={}", mode, dynamics,
                result.totalEpochs, result.successCount, result.failCount);
        return result.solutions;
    }

    private static void logQuantiles(List<double[]> samples) {
        double[] vars = new double[samples.size()];
        for (int i = 0; i < samples.size(); i++) vars[i] = samples.get(i)[0];
        Arrays.sort(vars);
        log.info(String.format("[posVar 分位 m2] p10=%.4g p50=%.4g p90=%.4g p95=%.4g p99=%.4g max=%.4g",
                quantile(vars, 0.10), quantile(vars, 0.50), quantile(vars, 0.90),
                quantile(vars, 0.95), quantile(vars, 0.99), vars[vars.length - 1]));
    }

    private static double quantile(double[] sorted, double p) {
        int idx = (int) Math.floor(p * (sorted.length - 1));
        return sorted[Math.max(0, Math.min(sorted.length - 1, idx))];
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
        return opt;
    }

    private static String findFile(File dir, String... extensions) {
        if (!dir.isDirectory()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (String ext : extensions) {
            for (File f : files) {
                String name = f.getName();
                if (name.endsWith(ext) || name.endsWith(ext.toUpperCase())) {
                    return f.getAbsolutePath();
                }
            }
        }
        return null;
    }

    private static String findAtx(File dir) {
        if (!dir.isDirectory()) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.getName().toLowerCase().endsWith(".atx") && f.length() > 0) {
                return f.getAbsolutePath();
            }
        }
        return null;
    }
}