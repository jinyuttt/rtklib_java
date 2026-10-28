package org.rtklib.java.joint;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.PrcOpt;
import org.rtklib.java.data.SolData;
import org.rtklib.java.data.SolutionStatus;
import org.rtklib.java.pntpos.PosHandler;
import org.rtklib.java.rtkpos.RtkProcessor;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

public class JointRtkRealDataTest {

    private static final String DATA_ROOT = JointTestDataConfig.getDataRoot();
    private static final String[] BASES = JointTestDataConfig.getBaseGroup();
    private static final String ROVER = JointTestDataConfig.getRoverId();
    private static final String DATE = JointTestDataConfig.getDate();
    private static final int START_HOUR = JointTestDataConfig.getStartHour();
    private static final int END_HOUR = JointTestDataConfig.getEndHour();

    private static final boolean DATA_AVAILABLE = JointTestDataConfig.isAvailable()
            && BASES.length >= 2
            && !ROVER.isEmpty();

    @BeforeAll
    static void checkData() {
        if (!DATA_AVAILABLE) {
            System.out.println("Joint RTK 测试数据不可用，跳过测试。" + JointTestDataConfig.getMissingMessage());
        } else {
            System.out.printf("数据配置: root=%s, bases=%s, rover=%s, date=%s, hours=%d~%d%n",
                    DATA_ROOT, Arrays.toString(BASES), ROVER, DATE, START_HOUR, END_HOUR);
        }
    }

    private PrcOpt createJointOpt() {
        PrcOpt opt = RtkProcessor.createDefaultOpt();
        opt.mode = Constants.PMODE_KINEMA;
        opt.nf = 2;
        opt.navsys = Constants.SYS_CMP;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;
        opt.modear = Constants.ARMODE_FIXHOLD;
        opt.glomodear = Constants.GLO_ARMODE_OFF;
        opt.bdsmodear = 1;
        opt.refpos = Constants.POSOPT_RTCM;
        opt.intpref = 1;
        opt.maxtdiff = 30.0;
        opt.outsingle = 1;
        opt.elmaskar = 15.0 * Constants.D2R;
        opt.minlock = 20;
        opt.minfix = 20;
        opt.thresar[0] = 3.0;
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

    @Test
    @DisplayName("2基站1测站联合RTK：逐小时RTCM数据")
    void testJointRtk2Base1Rover() throws IOException {
        if (!DATA_AVAILABLE) {
            System.out.println("跳过：数据不可用");
            return;
        }

        String baseA = BASES[0];
        String baseB = BASES[1];

        System.out.println("========================================");
        System.out.println("  Joint RTK 实测数据测试");
        System.out.printf("  基站A: %s%n", baseA);
        System.out.printf("  基站B: %s%n", baseB);
        System.out.printf("  测站:  %s%n", ROVER);
        System.out.printf("  日期:  %s  时段: %02d~%02d时%n", DATE, START_HOUR, END_HOUR);
        System.out.println("========================================");

        PrcOpt opt = createJointOpt();
        JointConfig jcfg = new JointConfig();
        jcfg.maxBaseline = 1;

        int totalEpochs = 0;
        int successCount = 0;
        int failCount = 0;
        int fixCount = 0;
        int floatCount = 0;
        int singleCount = 0;

        for (int hour = START_HOUR; hour <= END_HOUR; hour++) {
            String baseAFile = JointTestDataConfig.getRtcmPath(baseA, hour);
            String baseBFile = JointTestDataConfig.getRtcmPath(baseB, hour);
            String roverFile = JointTestDataConfig.getRtcmPath(ROVER, hour);

            if (!Files.exists(Paths.get(baseAFile)) || !Files.exists(Paths.get(baseBFile)) || !Files.exists(Paths.get(roverFile))) {
                System.out.printf("  %02d时: 数据缺失，跳过%n", hour);
                continue;
            }

            byte[] roverData = Files.readAllBytes(Paths.get(roverFile));
            byte[] baseAData = Files.readAllBytes(Paths.get(baseAFile));
            byte[] baseBData = Files.readAllBytes(Paths.get(baseBFile));

            Map<String, byte[]> baseDataMap = new LinkedHashMap<>();
            baseDataMap.put(baseA, baseAData);

            JointRtkProcessor proc = new JointRtkProcessor(opt, jcfg);
            JointRtkProcessor.JointRtkResult result = proc.process(roverData, baseDataMap);

            int hourFix = (int) result.solutions.stream().filter(s -> s.status == SolutionStatus.FIX).count();
            int hourFloat = (int) result.solutions.stream().filter(s -> s.status == SolutionStatus.FLOAT).count();
            int hourSingle = result.solutions.size() - hourFix - hourFloat;
            double hourFixRatio = result.solutions.isEmpty() ? 0 : 100.0 * hourFix / result.solutions.size();

            totalEpochs += result.totalEpochs;
            successCount += result.successCount;
            failCount += result.failCount;
            fixCount += hourFix;
            floatCount += hourFloat;
            singleCount += hourSingle;

            System.out.printf("  %02d时: 总=%d FIX=%d(%.1f%%) FLOAT=%d SINGLE=%d%n",
                    hour, result.solutions.size(), hourFix, hourFixRatio, hourFloat, hourSingle);
        }

        double overallFixRatio = (fixCount + floatCount + singleCount) > 0
                ? 100.0 * fixCount / (fixCount + floatCount + singleCount) : 0;

        System.out.println("----------------------------------------");
        System.out.printf("  合计: 总历元=%d 成功=%d 失败=%d%n", totalEpochs, successCount, failCount);
        System.out.printf("  FIX=%d(%.1f%%) FLOAT=%d SINGLE=%d%n",
                fixCount, overallFixRatio, floatCount, singleCount);
        System.out.println("========================================");

        assertTrue(totalEpochs > 0, "应有历元数据");
    }

    @Test
    @DisplayName("2基站1测站联合RTK：单小时快速验证")
    void testJointRtkSingleHour() throws IOException {
        if (!DATA_AVAILABLE) {
            System.out.println("跳过：数据不可用");
            return;
        }

        String baseA = BASES[0];
        String baseB = BASES[1];
        int testHour = 8;

        String baseAFile = JointTestDataConfig.getRtcmPath(baseA, testHour);
        String baseBFile = JointTestDataConfig.getRtcmPath(baseB, testHour);
        String roverFile = JointTestDataConfig.getRtcmPath(ROVER, testHour);

        if (!Files.exists(Paths.get(baseAFile)) || !Files.exists(Paths.get(baseBFile)) || !Files.exists(Paths.get(roverFile))) {
            System.out.printf("跳过：第%d小时数据缺失%n", testHour);
            return;
        }

        System.out.printf("%n=== 单小时快速验证: %02d时 ===%n", testHour);

        PrcOpt opt = createJointOpt();

        System.out.println("\n--- 1. 联合RTK 单基线 (仅基线A) ---");
        JointConfig jcfg1 = new JointConfig();
        jcfg1.maxBaseline = 1;
        byte[] roverData = Files.readAllBytes(Paths.get(roverFile));
        byte[] baseAData = Files.readAllBytes(Paths.get(baseAFile));
        Map<String, byte[]> baseDataMap1 = new LinkedHashMap<>();
        baseDataMap1.put(baseA, baseAData);
        JointRtkProcessor proc1 = new JointRtkProcessor(opt, jcfg1);
        JointRtkProcessor.JointRtkResult result1 = proc1.process(roverData, baseDataMap1);
        int fix1 = (int) result1.solutions.stream().filter(s -> s.status == SolutionStatus.FIX).count();
        System.out.printf("  历元=%d  FIX=%d  FLOAT=%d  成功=%d  失败=%d%n",
                result1.solutions.size(), fix1, result1.solutions.size() - fix1, result1.successCount, result1.failCount);

        System.out.println("\n--- 2. 联合RTK 双基线 ---");
        JointConfig jcfg = new JointConfig();
        jcfg.maxBaseline = 2;
        byte[] baseBData = Files.readAllBytes(Paths.get(baseBFile));
        Map<String, byte[]> baseDataMap = new LinkedHashMap<>();
        baseDataMap.put(baseA, baseAData);
        baseDataMap.put(baseB, baseBData);

        JointRtkProcessor proc = new JointRtkProcessor(opt, jcfg);
        JointRtkProcessor.JointRtkResult result = proc.process(roverData, baseDataMap);

        int fix = (int) result.solutions.stream().filter(s -> s.status == SolutionStatus.FIX).count();
        int flt = (int) result.solutions.stream().filter(s -> s.status == SolutionStatus.FLOAT).count();
        int sng = result.solutions.size() - fix - flt;

        System.out.printf("  历元=%d  FIX=%d  FLOAT=%d  SINGLE=%d  成功=%d  失败=%d%n",
                result.solutions.size(), fix, flt, sng, result.successCount, result.failCount);

        System.out.println("\n--- 3. 联合RTK 双基线（基站A坐标外部修正） [FIX-ALG-042验证] ---");
        // 基站的 RTCM 1005 坐标存在 4.77m 误差（诊断器静态解），外部注入修正坐标
        // 验证双基线 FIX=0% 的根因是否为基站坐标矛盾
        PrcOpt optFixed = createJointOpt();
        optFixed.refpos = Constants.POSOPT_POS_XYZ;
        JointConfig jcfgFixed = new JointConfig();
        jcfgFixed.maxBaseline = 2;
        jcfgFixed.baseCoordOverride = new LinkedHashMap<>();
        jcfgFixed.baseCoordOverride.put(baseA, new double[]{-1083471.0560, 5483520.0386, 3066261.4618});
        JointRtkProcessor procFixed = new JointRtkProcessor(optFixed, jcfgFixed);
        Map<String, byte[]> baseDataMapFixed = new LinkedHashMap<>();
        baseDataMapFixed.put(baseA, baseAData);
        baseDataMapFixed.put(baseB, baseBData);
        JointRtkProcessor.JointRtkResult resultFixed = procFixed.process(roverData, baseDataMapFixed);
        int fixFixed = (int) resultFixed.solutions.stream().filter(s -> s.status == SolutionStatus.FIX).count();
        System.out.printf("  历元=%d  FIX=%d(%.1f%%)  FLOAT=%d  成功=%d  失败=%d%n",
                resultFixed.solutions.size(), fixFixed,
                100.0 * fixFixed / Math.max(1, resultFixed.solutions.size()),
                resultFixed.solutions.size() - fixFixed, resultFixed.successCount, resultFixed.failCount);

        if (fix > 0) {
            List<SolData> fixSols = result.solutions.stream()
                    .filter(s -> s.status == SolutionStatus.FIX)
                    .toList();
            SolData first = fixSols.get(0);
            SolData last = fixSols.get(fixSols.size() - 1);
            System.out.printf("  首个FIX: %s%n", first);
            System.out.printf("  末个FIX: %s%n", last);
        }

        assertTrue(result.totalEpochs > 0, "应有历元数据");
    }

    @Test
    @DisplayName("对比：独立基线RTK vs 联合RTK")
    void testJointVsIndependent() throws IOException {
        if (!DATA_AVAILABLE) {
            System.out.println("跳过：数据不可用");
            return;
        }

        String baseA = BASES[0];
        String baseB = BASES[1];
        int testHour = 8;

        String baseAFile = JointTestDataConfig.getRtcmPath(baseA, testHour);
        String baseBFile = JointTestDataConfig.getRtcmPath(baseB, testHour);
        String roverFile = JointTestDataConfig.getRtcmPath(ROVER, testHour);

        if (!Files.exists(Paths.get(baseAFile)) || !Files.exists(Paths.get(baseBFile)) || !Files.exists(Paths.get(roverFile))) {
            System.out.printf("跳过：第%d小时数据缺失%n", testHour);
            return;
        }

        System.out.printf("%n=== 对比测试: 独立基线 vs 联合RTK (%02d时) ===%n", testHour);

        PrcOpt opt = createJointOpt();

        System.out.println("\n--- 1. 独立基线A → 测站 ---");
        RtkProcessor rtkA = new RtkProcessor(opt);
        RtkProcessor.RtkResult resA = rtkA.process(roverFile, baseAFile);
        int fixA = (int) resA.solutions.stream().filter(s -> s.status == SolutionStatus.FIX).count();
        double fixRatioA = resA.solutions.isEmpty() ? 0 : 100.0 * fixA / resA.solutions.size();
        System.out.printf("  历元=%d FIX=%d(%.1f%%)%n", resA.solutions.size(), fixA, fixRatioA);

        System.out.println("\n--- 2. 独立基线B → 测站 ---");
        RtkProcessor rtkB = new RtkProcessor(opt);
        RtkProcessor.RtkResult resB = rtkB.process(roverFile, baseBFile);
        int fixB = (int) resB.solutions.stream().filter(s -> s.status == SolutionStatus.FIX).count();
        double fixRatioB = resB.solutions.isEmpty() ? 0 : 100.0 * fixB / resB.solutions.size();
        System.out.printf("  历元=%d FIX=%d(%.1f%%)%n", resB.solutions.size(), fixB, fixRatioB);

        System.out.println("\n--- 3. 联合RTK (2基站1测站) ---");
        JointConfig jcfg = new JointConfig();
        jcfg.maxBaseline = 2;
        byte[] roverData = Files.readAllBytes(Paths.get(roverFile));
        byte[] baseAData = Files.readAllBytes(Paths.get(baseAFile));
        byte[] baseBData = Files.readAllBytes(Paths.get(baseBFile));
        Map<String, byte[]> baseDataMap = new LinkedHashMap<>();
        baseDataMap.put(baseA, baseAData);
        baseDataMap.put(baseB, baseBData);
        JointRtkProcessor proc = new JointRtkProcessor(opt, jcfg);
        JointRtkProcessor.JointRtkResult resJ = proc.process(roverData, baseDataMap);
        int fixJ = (int) resJ.solutions.stream().filter(s -> s.status == SolutionStatus.FIX).count();
        double fixRatioJ = resJ.solutions.isEmpty() ? 0 : 100.0 * fixJ / resJ.solutions.size();
        System.out.printf("  历元=%d FIX=%d(%.1f%%)%n", resJ.solutions.size(), fixJ, fixRatioJ);

        System.out.println("\n--- 对比汇总 ---");
        System.out.printf("  %-20s  历元  FIX   FIX率%n", "模式");
        System.out.println("  " + "-".repeat(50));
        System.out.printf("  %-20s  %4d  %4d  %5.1f%%%n", "独立基线A", resA.solutions.size(), fixA, fixRatioA);
        System.out.printf("  %-20s  %4d  %4d  %5.1f%%%n", "独立基线B", resB.solutions.size(), fixB, fixRatioB);
        System.out.printf("  %-20s  %4d  %4d  %5.1f%%%n", "联合RTK", resJ.solutions.size(), fixJ, fixRatioJ);
    }

    @Test
    @DisplayName("标准RTK Kinematic模式模糊度诊断")
    void testStandardRtkKinemaDiag() throws IOException {
        if (!DATA_AVAILABLE) {
            System.out.println("跳过：数据不可用");
            return;
        }

        String baseA = BASES[0];
        int testHour = 8;
        String baseAFile = JointTestDataConfig.getRtcmPath(baseA, testHour);
        String roverFile = JointTestDataConfig.getRtcmPath(ROVER, testHour);
        if (!Files.exists(Paths.get(baseAFile)) || !Files.exists(Paths.get(roverFile))) {
            System.out.println("跳过：数据缺失");
            return;
        }

        PrcOpt opt = createJointOpt();
        opt.mode = Constants.PMODE_KINEMA;

        System.out.println("=== 标准RTK Kinematic模式诊断 ===");
        RtkProcessor rtkA = new RtkProcessor(opt);
        RtkProcessor.RtkResult resA = rtkA.process(roverFile, baseAFile);

        org.rtklib.java.data.Rtk rtkObj = rtkA.getRtk();
        int nx = rtkObj.nx;
        double[] P = rtkObj.P;
        double[] x = rtkObj.x;

        double posVar = (P[0] + P[1 * nx + 1] + P[2 * nx + 2]) / 3.0;
        System.out.printf("  最终状态: nx=%d, posVar=%.6f%n", nx, posVar);

        int na = rtkObj.na;
        double sumAmbVar = 0;
        int ambCnt = 0;
        for (int i = na; i < nx; i++) {
            if (P[i * nx + i] > 0) {
                sumAmbVar += P[i * nx + i];
                ambCnt++;
            }
        }
        System.out.printf("  模糊度: na=%d, count=%d, avgVar=%.4f%n", na, ambCnt, ambCnt > 0 ? sumAmbVar / ambCnt : 0);

        double crossNorm = 0;
        for (int i = 0; i < 3; i++) {
            for (int j = na; j < nx; j++) {
                crossNorm += P[i * nx + j] * P[i * nx + j];
            }
        }
        System.out.printf("  交叉协方差范数: %.4f%n", Math.sqrt(crossNorm));

        int fixA = (int) resA.solutions.stream().filter(s -> s.status == SolutionStatus.FIX).count();
        double fixRatioA = resA.solutions.isEmpty() ? 0 : 100.0 * fixA / resA.solutions.size();
        System.out.printf("  FIX率: %d/%d = %.1f%%%n", fixA, resA.solutions.size(), fixRatioA);
    }
}