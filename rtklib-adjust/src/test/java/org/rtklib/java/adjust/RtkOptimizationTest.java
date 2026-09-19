package org.rtklib.java.adjust;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.CoordType;
import org.rtklib.java.data.PrcOpt;
import org.rtklib.java.data.SolData;
import org.rtklib.java.data.SolutionStatus;
import org.rtklib.java.rtkpos.RtkProcessor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class RtkOptimizationTest {

    private static final TestDataConfig CFG = new TestDataConfig();
    private static final String DATA_ROOT = CFG.getDataRoot();
    private static final boolean DATA_AVAILABLE = CFG.isAvailable()
            && !DATA_ROOT.isEmpty()
            && !CFG.getBadBase().isEmpty();

    private static final String BAD_BASE = CFG.getBadBase();
    private static final String BAD_ROVER = CFG.getBadRover();

    private static final String GOOD_BASE = CFG.getGoodBase();
    private static final String GOOD_ROVER = CFG.getGoodRover();

    @BeforeAll
    static void checkData() {
        if (!DATA_AVAILABLE) {
            System.out.println("测试数据不可用，跳过测试。" + CFG.getMissingMessage());
        }
    }

    private String buildPath(String station, String date, int hour) {
        return String.format("%s\\%s\\%s\\%d.rtcm3", DATA_ROOT, station, date, hour);
    }

    private PrcOpt createBaseOpt() {
        PrcOpt opt = RtkProcessor.createDefaultOpt();
        opt.mode = Constants.PMODE_STATIC;
        opt.nf = 4;
        opt.navsys = Constants.SYS_CMP;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;
        opt.modear = Constants.ARMODE_FIXHOLD;
        opt.bdsmodear = 1;
        opt.refpos = Constants.POSOPT_RTCM;
        opt.intpref = 1;
        opt.maxtdiff = 30.0;
        opt.outsingle = 1;
        opt.elmaskar = 15.0 * Constants.D2R;
        opt.minlock = 20;
        opt.minfix = 20;
        opt.thresar[0] = 4.0;
        opt.tidecorr = 1;
        opt.posopt[0] = 1;
        opt.posopt[1] = 1;
        opt.posopt[2] = 1;
        opt.err[1] = 0.003;
        opt.err[2] = 0.003;
        opt.err[3] = 0.0;
        opt.err[6] = 0.0;
        opt.err[7] = 0.0;
        return opt;
    }

    private RtkProcessor.RtkResult runRtk(PrcOpt opt, RtkConfig rtkConfig,
                                           String base, String rover, String date,
                                           int startHour, int endHour) throws IOException {
        RtkProcessor rtk = new RtkProcessor(opt);
        if (rtkConfig != null) {
            rtk.setRtkConfig(rtkConfig);
        }

        List<SolData> allSol = new ArrayList<>();
        int totalEpochs = 0, successCount = 0, failCount = 0;

        for (int hour = startHour; hour <= endHour; hour++) {
            String baseFile = buildPath(base, date, hour);
            String roverFile = buildPath(rover, date, hour);
            if (!Files.exists(Paths.get(baseFile)) || !Files.exists(Paths.get(roverFile))) continue;

            RtkProcessor.RtkResult res = rtk.process(roverFile, baseFile);
            allSol.addAll(res.solutions);
            totalEpochs += res.totalEpochs;
            successCount += res.successCount;
            failCount += res.failCount;
            rtk.resetForNextBatch();
        }

        return new RtkProcessor.RtkResult(totalEpochs, successCount, failCount, allSol);
    }

    static class RtkStats {
        final String label;
        final int totalEpochs;
        final int fixCount;
        final int floatCount;
        final int singleCount;
        final double fixRatio;
        final double[] rmsNeu;
        final double meanFixStd3d;

        RtkStats(String label, RtkProcessor.RtkResult result) {
            this.label = label;
            this.totalEpochs = result.totalEpochs;

            int fix = 0, flt = 0, sng = 0;
            double sumFixStd3d = 0;
            int fixWithAcc = 0;
            List<double[]> fixNeuList = new ArrayList<>();

            for (SolData sd : result.solutions) {
                if (sd.status == SolutionStatus.FIX) {
                    fix++;
                    var acc = sd.getAccuracy(CoordType.ENU);
                    if (acc != null) {
                        sumFixStd3d += Math.sqrt(acc.s1 * acc.s1 + acc.s2 * acc.s2 + acc.s3 * acc.s3);
                        fixWithAcc++;
                        fixNeuList.add(new double[]{acc.s1, acc.s2, acc.s3});
                    }
                } else if (sd.status == SolutionStatus.FLOAT) {
                    flt++;
                } else {
                    sng++;
                }
            }

            this.fixCount = fix;
            this.floatCount = flt;
            this.singleCount = sng;
            this.fixRatio = result.solutions.isEmpty() ? 0 : 100.0 * fix / result.solutions.size();
            this.meanFixStd3d = fixWithAcc > 0 ? sumFixStd3d / fixWithAcc : 0;

            if (fix > 0) {
                double rmsN = 0, rmsE = 0, rmsU = 0;
                for (double[] neu : fixNeuList) {
                    rmsN += neu[0] * neu[0];
                    rmsE += neu[1] * neu[1];
                    rmsU += neu[2] * neu[2];
                }
                this.rmsNeu = new double[]{
                        Math.sqrt(rmsN / fix) * 1000,
                        Math.sqrt(rmsE / fix) * 1000,
                        Math.sqrt(rmsU / fix) * 1000
                };
            } else {
                this.rmsNeu = null;
            }
        }

        void print() {
            System.out.printf("  %-28s  历元=%d  FIX=%d(%.1f%%)  FLOAT=%d  SINGLE=%d%n",
                    label, totalEpochs, fixCount, fixRatio, floatCount, singleCount);
            if (rmsNeu != null) {
                System.out.printf("    RMS: σN=%.2fmm σE=%.2fmm σU=%.2fmm  3D=%.4fm%n",
                        rmsNeu[0], rmsNeu[1], rmsNeu[2], meanFixStd3d);
            }
        }
    }

    private RtkConfig createAllOptConfig() {
        RtkConfig cfg = new RtkConfig();
        cfg.enableAdaptiveQ = true;
        cfg.enableIggiii = true;
        cfg.enableSnrMedian = true;
        cfg.snrMedianMinLockTime = 0;
        cfg.enableParRefReselect = true;
        cfg.enableAmbAnchor = true;
        cfg.ambAnchorMinFixCount = 20;
        cfg.enableIonoTropGradient = true;
        return cfg;
    }

    private void runComparison(String title, String base, String rover, String date,
                                int startHour, int endHour) throws IOException {
        System.out.println("\n========================================================");
        System.out.printf("  %s%n", title);
        System.out.printf("  基线: %s → %s%n", base, rover);
        System.out.printf("  日期: %s  时段: %02d:00~%02d:59  系统: 北斗三频%n", date, startHour, endHour);
        System.out.println("========================================================");

        PrcOpt baseOpt = createBaseOpt();
        List<RtkStats> results = new ArrayList<>();

        System.out.println("\n--- 1. 基准配置（无优化） ---");
        RtkStats s1 = new RtkStats("基准(无优化)", runRtk(baseOpt, null, base, rover, date, startHour, endHour));
        s1.print(); results.add(s1);

        System.out.println("\n--- 2. 自适应Q ---");
        RtkConfig cfgAQ = new RtkConfig(); cfgAQ.enableAdaptiveQ = true;
        RtkStats s2 = new RtkStats("自适应Q", runRtk(baseOpt, cfgAQ, base, rover, date, startHour, endHour));
        s2.print(); results.add(s2);

        System.out.println("\n--- 3. IGGIII抗差 ---");
        RtkConfig cfgIG = new RtkConfig(); cfgIG.enableIggiii = true;
        RtkStats s3 = new RtkStats("IGGIII抗差", runRtk(baseOpt, cfgIG, base, rover, date, startHour, endHour));
        s3.print(); results.add(s3);

        System.out.println("\n--- 4. SNR中位数定权 ---");
        RtkConfig cfgSNR = new RtkConfig(); cfgSNR.enableSnrMedian = true; cfgSNR.snrMedianMinLockTime = 0;
        RtkStats s4 = new RtkStats("SNR中位数定权", runRtk(baseOpt, cfgSNR, base, rover, date, startHour, endHour));
        s4.print(); results.add(s4);

        System.out.println("\n--- 5. 参考星重选 ---");
        RtkConfig cfgPR = new RtkConfig(); cfgPR.enableParRefReselect = true;
        RtkStats s5 = new RtkStats("参考星重选", runRtk(baseOpt, cfgPR, base, rover, date, startHour, endHour));
        s5.print(); results.add(s5);

        System.out.println("\n--- 6. 模糊度锚定 ---");
        RtkConfig cfgAA = new RtkConfig(); cfgAA.enableAmbAnchor = true; cfgAA.ambAnchorMinFixCount = 20;
        RtkStats s6 = new RtkStats("模糊度锚定", runRtk(baseOpt, cfgAA, base, rover, date, startHour, endHour));
        s6.print(); results.add(s6);

        System.out.println("\n--- 7. 电离层/对流层梯度 ---");
        RtkConfig cfgGR = new RtkConfig(); cfgGR.enableIonoTropGradient = true;
        RtkStats s7 = new RtkStats("电离层/对流层梯度", runRtk(baseOpt, cfgGR, base, rover, date, startHour, endHour));
        s7.print(); results.add(s7);

        System.out.println("\n--- 8. 全部优化 ---");
        RtkStats s8 = new RtkStats("全部优化", runRtk(baseOpt, createAllOptConfig(), base, rover, date, startHour, endHour));
        s8.print(); results.add(s8);

        System.out.println("\n--------------------------------------------------------");
        System.out.printf("  %-28s  FIX率     σN(mm)   σE(mm)   σU(mm)   3D(m)%n", "配置");
        System.out.println("  " + "-".repeat(80));
        for (RtkStats s : results) {
            if (s.rmsNeu != null) {
                System.out.printf("  %-28s  %5.1f%%    %6.2f    %6.2f    %6.2f    %.4f%n",
                        s.label, s.fixRatio, s.rmsNeu[0], s.rmsNeu[1], s.rmsNeu[2], s.meanFixStd3d);
            } else {
                System.out.printf("  %-28s  %5.1f%%    ---      ---      ---      ---%n",
                        s.label, s.fixRatio);
            }
        }

        double baseFix = s1.fixRatio;
        double bestFix = results.stream().mapToDouble(s -> s.fixRatio).max().orElse(0);
        System.out.println("--------------------------------------------------------");
        System.out.printf("  基准FIX率: %.1f%%  最佳FIX率: %.1f%%  提升: %+.1f%% %s%n",
                baseFix, bestFix, bestFix - baseFix, bestFix > baseFix ? "★" : "");
    }

    @Test
    @DisplayName("配置诊断：不同mode/nf组合的FIX率")
    void testConfigDiagnosis() throws IOException {
        if (!DATA_AVAILABLE) return;

        String date = "2026-07-04";
        System.out.println("\n========================================================");
        System.out.println("  配置诊断：不同mode/nf组合的FIX率");
        System.out.printf("  基线: %s → %s  日期=%s%n", BAD_BASE, BAD_ROVER, date);
        System.out.println("========================================================");

        int[][] configs = {
                {Constants.PMODE_KINEMA, 3},
                {Constants.PMODE_KINEMA, 2},
                {Constants.PMODE_KINEMA, 1},
                {Constants.PMODE_STATIC, 3},
                {Constants.PMODE_STATIC, 2},
                {Constants.PMODE_STATIC, 1},
                {Constants.PMODE_STATIC_START, 3},
                {Constants.PMODE_STATIC_START, 2},
        };
        String[] labels = {
                "KINEMA nf=3", "KINEMA nf=2", "KINEMA nf=1",
                "STATIC nf=3", "STATIC nf=2", "STATIC nf=1",
                "STATIC_START nf=3", "STATIC_START nf=2",
        };

        System.out.printf("  %-22s  历元  FIX   FLOAT  SINGLE  FIX率%n", "配置");
        System.out.println("  " + "-".repeat(65));

        for (int i = 0; i < configs.length; i++) {
            PrcOpt opt = createBaseOpt();
            opt.mode = configs[i][0];
            opt.nf = configs[i][1];

            RtkProcessor.RtkResult res = runRtk(opt, null, BAD_BASE, BAD_ROVER, date, 17, 23);
            int fix = (int) res.solutions.stream().filter(s -> s.status == SolutionStatus.FIX).count();
            int flt = (int) res.solutions.stream().filter(s -> s.status == SolutionStatus.FLOAT).count();
            int sng = res.solutions.size() - fix - flt;
            double rate = res.solutions.isEmpty() ? 0 : 100.0 * fix / res.solutions.size();

            System.out.printf("  %-22s  %4d  %4d  %5d  %5d   %5.1f%%%n",
                    labels[i], res.solutions.size(), fix, flt, sng, rate);
        }
    }

    @Test
    @DisplayName("参数敏感性分析：各参数对FIX率的影响")
    void testParameterSensitivity() throws IOException {
        if (!DATA_AVAILABLE) return;

        String date = "2026-07-04";
        System.out.println("\n========================================================");
        System.out.println("  参数敏感性分析");
        System.out.printf("  基线: %s → %s  日期=%s%n", BAD_BASE, BAD_ROVER, date);
        System.out.println("========================================================");

        PrcOpt base = createBaseOpt();

        System.out.println("\n  === 1. 定位模式(mode) ===");
        System.out.printf("  %-20s  FIX率  FIX   FLOAT  SINGLE%n", "配置");
        System.out.println("  " + "-".repeat(60));
        for (int mode : new int[]{Constants.PMODE_KINEMA, Constants.PMODE_STATIC, Constants.PMODE_STATIC_START}) {
            PrcOpt opt = createBaseOpt();
            opt.mode = mode;
            RtkProcessor.RtkResult res = runRtk(opt, null, BAD_BASE, BAD_ROVER, date, 17, 23);
            int fix = (int) res.solutions.stream().filter(s -> s.status == SolutionStatus.FIX).count();
            int flt = (int) res.solutions.stream().filter(s -> s.status == SolutionStatus.FLOAT).count();
            int sng = res.solutions.size() - fix - flt;
            String label = mode == Constants.PMODE_KINEMA ? "KINEMA" :
                           mode == Constants.PMODE_STATIC ? "STATIC" : "STATIC_START";
            System.out.printf("  %-20s  %5.1f%%  %4d  %5d  %5d%n",
                    label, res.solutions.isEmpty() ? 0 : 100.0 * fix / res.solutions.size(),
                    fix, flt, sng);
        }

        System.out.println("\n  === 2. 频点数(nf) ===");
        System.out.printf("  %-20s  FIX率  FIX   FLOAT  SINGLE%n", "配置");
        System.out.println("  " + "-".repeat(60));
        for (int nf : new int[]{1, 2, 3, 4}) {
            PrcOpt opt = createBaseOpt();
            opt.nf = nf;
            RtkProcessor.RtkResult res = runRtk(opt, null, BAD_BASE, BAD_ROVER, date, 17, 23);
            int fix = (int) res.solutions.stream().filter(s -> s.status == SolutionStatus.FIX).count();
            int flt = (int) res.solutions.stream().filter(s -> s.status == SolutionStatus.FLOAT).count();
            int sng = res.solutions.size() - fix - flt;
            System.out.printf("  nf=%-17d  %5.1f%%  %4d  %5d  %5d%n",
                    nf, res.solutions.isEmpty() ? 0 : 100.0 * fix / res.solutions.size(),
                    fix, flt, sng);
        }

        System.out.println("\n  === 3. 对流层模型(tropopt) ===");
        System.out.printf("  %-20s  FIX率  FIX   FLOAT  SINGLE%n", "配置");
        System.out.println("  " + "-".repeat(60));
        for (int trop : new int[]{Constants.TROPOPT_SAAS, Constants.TROPOPT_EST, Constants.TROPOPT_ESTG}) {
            PrcOpt opt = createBaseOpt();
            opt.tropopt = trop;
            RtkProcessor.RtkResult res = runRtk(opt, null, BAD_BASE, BAD_ROVER, date, 17, 23);
            int fix = (int) res.solutions.stream().filter(s -> s.status == SolutionStatus.FIX).count();
            int flt = (int) res.solutions.stream().filter(s -> s.status == SolutionStatus.FLOAT).count();
            int sng = res.solutions.size() - fix - flt;
            String label = trop == Constants.TROPOPT_SAAS ? "SAASTAMOINEN" :
                           trop == Constants.TROPOPT_EST ? "EST" : "EST+GRAD";
            System.out.printf("  %-20s  %5.1f%%  %4d  %5d  %5d%n",
                    label, res.solutions.isEmpty() ? 0 : 100.0 * fix / res.solutions.size(),
                    fix, flt, sng);
        }

        System.out.println("\n  === 4. AR ratio阈值(thresar) ===");
        System.out.printf("  %-20s  FIX率  FIX   FLOAT  SINGLE%n", "配置");
        System.out.println("  " + "-".repeat(60));
        for (double thres : new double[]{2.0, 3.0, 4.0, 5.0, 6.0}) {
            PrcOpt opt = createBaseOpt();
            opt.thresar[0] = thres;
            RtkProcessor.RtkResult res = runRtk(opt, null, BAD_BASE, BAD_ROVER, date, 17, 23);
            int fix = (int) res.solutions.stream().filter(s -> s.status == SolutionStatus.FIX).count();
            int flt = (int) res.solutions.stream().filter(s -> s.status == SolutionStatus.FLOAT).count();
            int sng = res.solutions.size() - fix - flt;
            System.out.printf("  thresar=%-13.1f  %5.1f%%  %4d  %5d  %5d%n",
                    thres, res.solutions.isEmpty() ? 0 : 100.0 * fix / res.solutions.size(),
                    fix, flt, sng);
        }

        System.out.println("\n  === 5. AR锁定计数(minlock) ===");
        System.out.printf("  %-20s  FIX率  FIX   FLOAT  SINGLE%n", "配置");
        System.out.println("  " + "-".repeat(60));
        for (int ml : new int[]{0, 5, 10, 20, 50}) {
            PrcOpt opt = createBaseOpt();
            opt.minlock = ml;
            RtkProcessor.RtkResult res = runRtk(opt, null, BAD_BASE, BAD_ROVER, date, 17, 23);
            int fix = (int) res.solutions.stream().filter(s -> s.status == SolutionStatus.FIX).count();
            int flt = (int) res.solutions.stream().filter(s -> s.status == SolutionStatus.FLOAT).count();
            int sng = res.solutions.size() - fix - flt;
            System.out.printf("  minlock=%-13d  %5.1f%%  %4d  %5d  %5d%n",
                    ml, res.solutions.isEmpty() ? 0 : 100.0 * fix / res.solutions.size(),
                    fix, flt, sng);
        }

        System.out.println("\n  === 6. AR最小FIX计数(minfix) ===");
        System.out.printf("  %-20s  FIX率  FIX   FLOAT  SINGLE%n", "配置");
        System.out.println("  " + "-".repeat(60));
        for (int mf : new int[]{1, 5, 10, 20, 50}) {
            PrcOpt opt = createBaseOpt();
            opt.minfix = mf;
            RtkProcessor.RtkResult res = runRtk(opt, null, BAD_BASE, BAD_ROVER, date, 17, 23);
            int fix = (int) res.solutions.stream().filter(s -> s.status == SolutionStatus.FIX).count();
            int flt = (int) res.solutions.stream().filter(s -> s.status == SolutionStatus.FLOAT).count();
            int sng = res.solutions.size() - fix - flt;
            System.out.printf("  minfix=%-14d  %5.1f%%  %4d  %5d  %5d%n",
                    mf, res.solutions.isEmpty() ? 0 : 100.0 * fix / res.solutions.size(),
                    fix, flt, sng);
        }

        System.out.println("\n  === 7. 潮汐改正+天线PCV(tidecorr/posopt) ===");
        System.out.printf("  %-20s  FIX率  FIX   FLOAT  SINGLE%n", "配置");
        System.out.println("  " + "-".repeat(60));
        String[][] tideConfigs = {
                {"无潮汐无PCV", "0,0,0,0"},
                {"有潮汐无PCV", "1,0,0,0"},
                {"无潮汐有PCV", "0,1,1,1"},
                {"有潮汐有PCV", "1,1,1,1"},
        };
        for (String[] tc : tideConfigs) {
            PrcOpt opt = createBaseOpt();
            String[] vals = tc[1].split(",");
            opt.tidecorr = Integer.parseInt(vals[0]);
            opt.posopt[0] = Integer.parseInt(vals[1]);
            opt.posopt[1] = Integer.parseInt(vals[2]);
            opt.posopt[2] = Integer.parseInt(vals[3]);
            RtkProcessor.RtkResult res = runRtk(opt, null, BAD_BASE, BAD_ROVER, date, 17, 23);
            int fix = (int) res.solutions.stream().filter(s -> s.status == SolutionStatus.FIX).count();
            int flt = (int) res.solutions.stream().filter(s -> s.status == SolutionStatus.FLOAT).count();
            int sng = res.solutions.size() - fix - flt;
            System.out.printf("  %-20s  %5.1f%%  %4d  %5d  %5d%n",
                    tc[0], res.solutions.isEmpty() ? 0 : 100.0 * fix / res.solutions.size(),
                    fix, flt, sng);
        }

        System.out.println("\n  === 8. 电离层模型(ionoopt) ===");
        System.out.printf("  %-20s  FIX率  FIX   FLOAT  SINGLE%n", "配置");
        System.out.println("  " + "-".repeat(60));
        for (int iono : new int[]{Constants.IONOOPT_BRDC, Constants.IONOOPT_IFLC}) {
            PrcOpt opt = createBaseOpt();
            opt.ionoopt = iono;
            RtkProcessor.RtkResult res = runRtk(opt, null, BAD_BASE, BAD_ROVER, date, 17, 23);
            int fix = (int) res.solutions.stream().filter(s -> s.status == SolutionStatus.FIX).count();
            int flt = (int) res.solutions.stream().filter(s -> s.status == SolutionStatus.FLOAT).count();
            int sng = res.solutions.size() - fix - flt;
            String label = iono == Constants.IONOOPT_BRDC ? "BRDC(广播星历)" : "IFLC(无电离层组合)";
            System.out.printf("  %-20s  %5.1f%%  %4d  %5d  %5d%n",
                    label, res.solutions.isEmpty() ? 0 : 100.0 * fix / res.solutions.size(),
                    fix, flt, sng);
        }
    }

    @Test
    @DisplayName("优化项内部状态诊断")
    void testOptimizationDiagnosis() throws IOException {
        if (!DATA_AVAILABLE) return;

        String date = "2026-07-04";
        System.out.println("\n========================================================");
        System.out.println("  优化项内部状态诊断");
        System.out.printf("  基线: %s → %s  日期=%s%n", BAD_BASE, BAD_ROVER, date);
        System.out.println("========================================================");

        PrcOpt opt = createBaseOpt();

        RtkConfig cfg = new RtkConfig();
        cfg.enableAdaptiveQ = true;
        cfg.enableIggiii = true;
        cfg.enableSnrMedian = true;
        cfg.snrMedianMinLockTime = 0;
        cfg.enableParRefReselect = true;
        cfg.enableAmbAnchor = true;
        cfg.ambAnchorMinFixCount = 20;
        cfg.enableIonoTropGradient = true;

        RtkProcessor rtk = new RtkProcessor(opt);
        rtk.setRtkConfig(cfg);

        int totalEpochs = 0;
        double sumQScale = 0;
        int qScaleNotOne = 0;
        int[] iggDownWeight = new int[24];
        int[] fixByHour = new int[24];
        int[] fltByHour = new int[24];
        int[] sngByHour = new int[24];

        for (int hour = 17; hour <= 20; hour++) {
            String baseFile = buildPath(BAD_BASE, date, hour);
            String roverFile = buildPath(BAD_ROVER, date, hour);
            if (!Files.exists(Paths.get(baseFile)) || !Files.exists(Paths.get(roverFile))) continue;

            RtkProcessor.RtkResult res = rtk.process(roverFile, baseFile);
            rtk.resetForNextBatch();

            int fix = (int) res.solutions.stream().filter(s -> s.status == SolutionStatus.FIX).count();
            int flt = (int) res.solutions.stream().filter(s -> s.status == SolutionStatus.FLOAT).count();
            int sng = res.solutions.size() - fix - flt;
            fixByHour[hour] = fix;
            fltByHour[hour] = flt;
            sngByHour[hour] = sng;

            System.out.printf("%n  === %02d:00 总=%d FIX=%d FLOAT=%d SINGLE=%d ===%n",
                    hour, res.solutions.size(), fix, flt, sng);
        }

        org.rtklib.java.data.Rtk rtkObj = rtk.getRtk();
        System.out.println("\n  --- 自适应Q状态 ---");
        System.out.printf("  qScale = %.6f%n", rtkObj.qScale);
        System.out.printf("  qScale!=1.0的历元数: %d%n", rtkObj.diagQScaleNotOneCount);
        System.out.printf("  qScale平均: %.4f  范围: [%.4f, %.4f]%n",
                rtkObj.diagQScaleNotOneCount > 0 ? rtkObj.diagQScaleSum / (rtkObj.diagQScaleNotOneCount + 1) : 0,
                rtkObj.diagQScaleMin == Double.MAX_VALUE ? 0 : rtkObj.diagQScaleMin,
                rtkObj.diagQScaleMax == Double.MIN_VALUE ? 0 : rtkObj.diagQScaleMax);
        System.out.printf("  posWin(last10)=");
        int start = Math.max(0, rtkObj.winCnt - 10);
        for (int i = start; i < rtkObj.winCnt; i++) {
            int idx = (rtkObj.winIdx + i) % rtkObj.posWin.length;
            System.out.printf("%.6f ", rtkObj.posWin[idx]);
        }
        System.out.println();

        System.out.println("\n  --- IGGIII抗差估计 ---");
        System.out.printf("  降权观测数: %d%n", rtkObj.diagIggDownWeightCount);

        System.out.println("\n  --- SNR中位数定权 ---");
        System.out.printf("  有效中位数计算次数: %d%n", rtkObj.diagSnrMedianValidCount);
        System.out.printf("  fallback使用次数: %d%n", rtkObj.diagSnrMedianFallbackCount);
        for (int f = 0; f < opt.nf && f < rtkObj.snrMedian.length; f++) {
            System.out.printf("  频点%d: snrMedian=%.2f dBHz%n", f, rtkObj.snrMedian[f]);
        }

        System.out.println("\n  --- 模糊度锚定 ---");
        int anchoredCount = 0, anchorPendingCount = 0;
        for (int i = 0; i < rtkObj.ambAnchored.length; i++) {
            if (rtkObj.ambAnchored[i]) anchoredCount++;
            if (rtkObj.ambAnchorCount[i] > 0 && !rtkObj.ambAnchored[i]) anchorPendingCount++;
        }
        System.out.printf("  已锚定模糊度: %d / %d%n", anchoredCount, rtkObj.ambAnchored.length);
        System.out.printf("  等待锚定(已有部分FIX): %d%n", anchorPendingCount);
        System.out.printf("  holdamb执行次数(FIX时): %d%n", rtkObj.diagAmbAnchorAttemptCount);
        System.out.printf("  ambAnchorMinFixCount=%d (需要连续FIX这么多次才锚定)%n", cfg.ambAnchorMinFixCount);

        System.out.println("\n  --- 参考星重选 ---");
        System.out.printf("  重选事件次数: %d%n", rtkObj.diagRefReselectCount);
        System.out.printf("  parConsecutiveReselectCount=%d%n", rtkObj.parConsecutiveReselectCount);
        System.out.printf("  parExcludedSatCount=%d%n", rtkObj.parExcludedSatCount);
        for (int f = 0; f < opt.nf; f++) {
            System.out.printf("  频点%d: prevRefSat=%d%n", f, rtkObj.parPrevRefSat[f]);
        }

        System.out.println("%n  --- 电离层/对流层梯度 ---");
        System.out.printf("  ionoGradient=%b%n", opt.ionoGradient);
        System.out.printf("  tropGradient=未单独实现(由tropopt控制)%n");
    }

    @Test
    @DisplayName("差数据组优化对比")
    void testBadDataOptimization() throws IOException {
        if (!DATA_AVAILABLE) return;
        runComparison("差数据组优化对比", BAD_BASE, BAD_ROVER, "2026-07-04", 17, 23);
        assertTrue(true);
    }

    @Test
    @DisplayName("多天FIX率统计：差数据组6月基准vs全部优化")
    void testBadDataMultiDay() throws IOException {
        if (!DATA_AVAILABLE) return;

        System.out.println("\n========================================================");
        System.out.println("  多天FIX率统计：差数据组 6月");
        System.out.printf("  基线: %s → %s%n", BAD_BASE, BAD_ROVER);
        System.out.println("========================================================");

        PrcOpt opt = createBaseOpt();
        RtkConfig cfgAll = createAllOptConfig();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd");
        LocalDate startDate = LocalDate.of(2026, 6, 1);
        int numDays = 10;

        double totalFixBase = 0, totalSolBase = 0;
        double totalFixOpt = 0, totalSolOpt = 0;

        System.out.printf("  %-12s  %-22s  %-22s  提升%n", "日期", "基准FIX率", "全部优化FIX率");
        System.out.println("  " + "-".repeat(72));

        for (int d = 0; d < numDays; d++) {
            String date = startDate.plusDays(d).format(fmt);
            String checkFile = buildPath(BAD_BASE, date, 18);
            if (!Files.exists(Paths.get(checkFile))) continue;

            RtkProcessor.RtkResult rBase = runRtk(opt, null, BAD_BASE, BAD_ROVER, date, 17, 23);
            RtkProcessor.RtkResult rOpt = runRtk(opt, cfgAll, BAD_BASE, BAD_ROVER, date, 17, 23);

            int fixBase = (int) rBase.solutions.stream().filter(s -> s.status == SolutionStatus.FIX).count();
            int fixOpt = (int) rOpt.solutions.stream().filter(s -> s.status == SolutionStatus.FIX).count();
            double rateBase = rBase.solutions.isEmpty() ? 0 : 100.0 * fixBase / rBase.solutions.size();
            double rateOpt = rOpt.solutions.isEmpty() ? 0 : 100.0 * fixOpt / rOpt.solutions.size();

            totalFixBase += fixBase; totalSolBase += rBase.solutions.size();
            totalFixOpt += fixOpt; totalSolOpt += rOpt.solutions.size();

            String mark = rateOpt > rateBase ? " ★" : "";
            System.out.printf("  %-12s  %5.1f%% (%d/%d)        %5.1f%% (%d/%d)        %+.1f%%%s%n",
                    date, rateBase, fixBase, rBase.solutions.size(),
                    rateOpt, fixOpt, rOpt.solutions.size(),
                    rateOpt - rateBase, mark);
        }

        double overallBase = totalSolBase > 0 ? 100.0 * totalFixBase / totalSolBase : 0;
        double overallOpt = totalSolOpt > 0 ? 100.0 * totalFixOpt / totalSolOpt : 0;
        System.out.println("  " + "-".repeat(72));
        System.out.printf("  %-12s  %5.1f%%                  %5.1f%%                  %+.1f%%%n",
                "合计", overallBase, overallOpt, overallOpt - overallBase);
    }

    @Test
    @DisplayName("数据概览：差数据组6月1日逐小时")
    void testDataOverview() throws IOException {
        if (!DATA_AVAILABLE) return;

        String date = "2026-06-01";
        System.out.println("\n========================================================");
        System.out.println("  数据概览：差数据组");
        System.out.printf("  基线: %s → %s  日期=%s  系统: 北斗三频%n", BAD_BASE, BAD_ROVER, date);
        System.out.println("========================================================");

        PrcOpt opt = createBaseOpt();
        RtkProcessor rtk = new RtkProcessor(opt);

        int totalFix = 0, totalSol = 0;
        for (int hour = 17; hour <= 23; hour++) {
            String baseFile = buildPath(BAD_BASE, date, hour);
            String roverFile = buildPath(BAD_ROVER, date, hour);
            if (!Files.exists(Paths.get(baseFile)) || !Files.exists(Paths.get(roverFile))) continue;

            RtkProcessor.RtkResult res = rtk.process(roverFile, baseFile);
            rtk.resetForNextBatch();

            int fix = (int) res.solutions.stream().filter(s -> s.status == SolutionStatus.FIX).count();
            int flt = (int) res.solutions.stream().filter(s -> s.status == SolutionStatus.FLOAT).count();
            int sng = res.solutions.size() - fix - flt;
            totalFix += fix; totalSol += res.solutions.size();

            System.out.printf("  %02d:00: 总=%d FIX=%d(%.1f%%) FLOAT=%d SINGLE=%d  累计FIX=%d%n",
                    hour, res.solutions.size(), fix,
                    res.solutions.isEmpty() ? 0 : 100.0 * fix / res.solutions.size(),
                    flt, sng, totalFix);

            if (hour == 17 && res.solutions.size() > 0) {
                double[] rb = rtk.getRtk().rb;
                double[] rbLlh = new double[3];
                org.rtklib.java.coord.CoordTransform.ecef2pos(rb, rbLlh);
                System.out.printf("    基站(LLH): %.9f° %.9f° %.4fm%n",
                        Math.toDegrees(rbLlh[0]), Math.toDegrees(rbLlh[1]), rbLlh[2]);
            }
        }
        System.out.printf("%n  累计: 总=%d FIX=%d(%.1f%%)%n",
                totalSol, totalFix, totalSol > 0 ? 100.0 * totalFix / totalSol : 0);
    }
}