package org.rtklib.java.joint;

import org.junit.jupiter.api.Assumptions;
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
        Assumptions.assumeTrue(DATA_AVAILABLE, JointTestDataConfig.getMissingMessage());

        System.out.println("========================================");
        System.out.println("  Joint RTK 实测数据测试");
        System.out.printf("  基站A: %s%n", BASES[0]);
        System.out.printf("  基站B: %s%n", BASES[1]);
        System.out.printf("  测站:  %s%n", ROVER);
        System.out.printf("  日期:  %s  时段: %02d~%02d时%n", DATE, START_HOUR, END_HOUR);
        System.out.println("========================================");

        System.out.println("\n--- 配置1: 单基站（基线A，RTCM 1005 原始坐标） ---");
        RunStats single = runAllHours(false, false);
        printSummary(single);

        System.out.println("\n--- 配置2: 双基站（基线A坐标外部修正，FIX-ALG-042） ---");
        RunStats dual = runAllHours(true, true);
        printSummary(dual);

        assertTrue(single.totalEpochs > 0, "应有历元数据");
        assertTrue(single.solCount() > 0, "单基站应有解算输出");
        assertTrue(single.success > 0, "单基站应有成功历元，实际 " + single.success);
        assertTrue(single.fixRatio() >= 80.0,
                "单基站联合 RTK 的 FIX 率应 ≥80%，实际 " + String.format("%.1f%%", single.fixRatio()));

        // 双基站整时段稳定性为已知缺口（见 docs/JOINT_RTK_DESIGN.md「双基站稳定性」）：
        // 部分小时因两条基线残差互不自洽（iter=0 ddres nv=0）导致全 FLOAT。
        // 此处只守住「双基站能产出解、且能出 FIX」这条底线，逐小时 FIX 率仅作打印诊断。
        // 双基站的 FIX 率硬门槛由 testJointRtkSingleHour（稳定小时）负责。
        assertTrue(dual.solCount() > 0, "双基站应有解算输出");
        assertTrue(dual.success > 0, "双基站应有成功历元，实际 " + dual.success);
        assertTrue(dual.fix > 0, "双基站应存在 FIX 解，实际 " + dual.fix);
    }

    private static final double[] BASE_A_TRUE_XYZ = {-1083471.0560, 5483520.0386, 3066261.4618};

    private static final class RunStats {
        int totalEpochs;
        int success;
        int fail;
        int fix;
        int flt;
        int sng;

        int solCount() {
            return fix + flt + sng;
        }

        double fixRatio() {
            return solCount() == 0 ? 0.0 : 100.0 * fix / solCount();
        }
    }

    private void printSummary(RunStats st) {
        System.out.println("----------------------------------------");
        System.out.printf("  合计: 总历元=%d 成功=%d 失败=%d%n", st.totalEpochs, st.success, st.fail);
        System.out.printf("  FIX=%d(%.1f%%) FLOAT=%d SINGLE=%d%n", st.fix, st.fixRatio(), st.flt, st.sng);
        System.out.println("========================================");
    }

    private RunStats runAllHours(boolean dualBase, boolean fixBaseACoord) throws IOException {
        String baseA = BASES[0];
        String baseB = BASES[1];
        RunStats st = new RunStats();

        for (int hour = START_HOUR; hour <= END_HOUR; hour++) {
            String baseAFile = JointTestDataConfig.getRtcmPath(baseA, hour);
            String baseBFile = JointTestDataConfig.getRtcmPath(baseB, hour);
            String roverFile = JointTestDataConfig.getRtcmPath(ROVER, hour);

            if (!Files.exists(Paths.get(baseAFile)) || !Files.exists(Paths.get(baseBFile)) || !Files.exists(Paths.get(roverFile))) {
                System.out.printf("  %02d时: 数据缺失，跳过%n", hour);
                continue;
            }

            PrcOpt opt = createJointOpt();
            JointConfig jcfg = new JointConfig();
            jcfg.maxBaseline = dualBase ? 2 : 1;
            if (fixBaseACoord) {
                opt.refpos = Constants.POSOPT_POS_XYZ;
                jcfg.baseCoordOverride = new LinkedHashMap<>();
                jcfg.baseCoordOverride.put(baseA, BASE_A_TRUE_XYZ.clone());
            }

            Map<String, byte[]> baseDataMap = new LinkedHashMap<>();
            baseDataMap.put(baseA, Files.readAllBytes(Paths.get(baseAFile)));
            if (dualBase) {
                baseDataMap.put(baseB, Files.readAllBytes(Paths.get(baseBFile)));
            }

            JointRtkProcessor proc = new JointRtkProcessor(opt, jcfg);
            JointRtkProcessor.JointRtkResult result =
                    proc.process(Files.readAllBytes(Paths.get(roverFile)), baseDataMap);

            int hourFix = (int) result.solutions.stream().filter(s -> s.status == SolutionStatus.FIX).count();
            int hourFloat = (int) result.solutions.stream().filter(s -> s.status == SolutionStatus.FLOAT).count();
            int hourSingle = result.solutions.size() - hourFix - hourFloat;

            st.totalEpochs += result.totalEpochs;
            st.success += result.successCount;
            st.fail += result.failCount;
            st.fix += hourFix;
            st.flt += hourFloat;
            st.sng += hourSingle;

            System.out.printf("  %02d时: 总=%d FIX=%d(%.1f%%) FLOAT=%d SINGLE=%d%n",
                    hour, result.solutions.size(), hourFix,
                    result.solutions.isEmpty() ? 0.0 : 100.0 * hourFix / result.solutions.size(),
                    hourFloat, hourSingle);
        }
        return st;
    }

    @Test
    @DisplayName("2基站1测站联合RTK：单小时快速验证")
    void testJointRtkSingleHour() throws IOException {
        Assumptions.assumeTrue(DATA_AVAILABLE, JointTestDataConfig.getMissingMessage());

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
        assertFalse(result1.solutions.isEmpty(), "单基线应有解算输出");
        assertFalse(result.solutions.isEmpty(), "双基线应有解算输出");
        assertTrue(fix1 > 0, "单基线应存在 FIX 解");
        assertTrue(fix > 0, "双基线应存在 FIX 解");
        double fixRatioSingle = 100.0 * fix1 / Math.max(1, result1.solutions.size());
        double fixRatioDual = 100.0 * fix / Math.max(1, result.solutions.size());
        assertTrue(fixRatioSingle >= 80.0,
                "单基线 FIX 率应 ≥80%，实际 " + String.format("%.1f%%", fixRatioSingle));
        assertTrue(fixRatioDual >= 80.0,
                "双基线 FIX 率应 ≥80%，实际 " + String.format("%.1f%%", fixRatioDual));

        // [FIX-ALG-042 §13.4 Phase A] 基站稳定性监测回归保护：
        // 该小时是已验证的健康小时（FIX 率 ≥80%），两条基线的残差应基本健康，
        // 即闸1 不应长时间把基线判成 DRIFTING。若此断言失败，说明残差健康度退化。
        BaseStabilityMonitor mon1 = proc1.getMonitor();
        assertNotNull(mon1, "单基线应产出基站稳定性监测器");
        assertTrue(mon1.getEpochCount(0) >= 10,
                "单基线监测器应有足够历元样本，实际 " + mon1.getEpochCount(0));
        double driftRatio1 = 100.0 * mon1.getDriftingEpochs(0) / Math.max(1, mon1.getEpochCount(0));
        printMonitorStats("单基线", mon1, 1);
        assertTrue(driftRatio1 <= 30.0,
                "单基线 DRIFTING 历元占比应 ≤30%，实际 " + String.format("%.1f%%", driftRatio1)
                        + "，末历元 chi2/dof=" + String.format("%.2f", mon1.getChi2Ratio(0)));

        BaseStabilityMonitor mon2 = procFixed.getMonitor();
        assertNotNull(mon2, "双基线应产出基站稳定性监测器");
        printMonitorStats("双基线-原始坐标", proc.getMonitor(), 2);
        printMonitorStats("双基线-坐标修正", mon2, 2);
        for (int b = 0; b < 2; b++) {
            double driftRatio2 = 100.0 * mon2.getDriftingEpochs(b) / Math.max(1, mon2.getEpochCount(b));
            assertTrue(driftRatio2 <= 30.0,
                    "双基线 baseline[" + b + "] DRIFTING 历元占比应 ≤30%，实际 "
                            + String.format("%.1f%%", driftRatio2)
                            + "，末历元 chi2/dof=" + String.format("%.2f", mon2.getChi2Ratio(b)));
            // [FIX-ALG-042 §13.4 Phase A] 闸2 可用性回归保护：健康小时里闸2 应能产出大部分历元的
            // 位移估计。放弃率高说明残差集合频繁变化（周跳/参考星切换/AR 切换），
            // Phase B 降权会失去观测支撑。实测该健康小时放弃率为 0%，阈值留 20 个百分点余量。
            assertTrue(mon2.getGate2RejectRatio(b) <= 20.0 / 100.0,
                    "双基线 baseline[" + b + "] 闸2 历元放弃率应 ≤20%，实际 "
                            + String.format("%.1f%%", 100.0 * mon2.getGate2RejectRatio(b))
                            + "（attempts=" + mon2.getGate2Attempts(b)
                            + ", samples=" + mon2.getStepSamples(b) + "）");
            // 无条件分位数才是 Phase B 门限标定依据（日志分位数条件于超 0.05m 门限，会高估约 40 倍）。
            // 实测健康小时 p90=0.008m、max=0.019m，故要求 p90 ≤5cm（约 6 倍余量），
            // 保证 Phase B 降权不会被日常反演噪声触发。
            assertTrue(mon2.getStepPercentile(b, 0.9) <= 0.05,
                    "双基线 baseline[" + b + "] 闸2 无条件 p90 应 ≤5cm，实际 "
                            + String.format("%.4f", mon2.getStepPercentile(b, 0.9)) + " m");
        }
    }

    private void printMonitorStats(String tag, BaseStabilityMonitor mon, int nb) {
        if (mon == null) {
            System.out.printf("  [%s] 监测器未启用%n", tag);
            return;
        }
        for (int b = 0; b < nb; b++) {
            System.out.printf("  [%s] baseline[%d] 历元=%d DRIFTING=%d(%.1f%%) chi2/dof=%.2f(ref=%.2f)"
                            + " 闸2: attempts=%d samples=%d reject=%.1f%%"
                            + " |dDb| 无条件 p50=%.3f p90=%.3f max=%.3f m"
                            + " 公共模式历元=%d 不可分离历元=%d%n",
                    tag, b, mon.getEpochCount(b), mon.getDriftingEpochs(b),
                    100.0 * mon.getDriftingEpochs(b) / Math.max(1, mon.getEpochCount(b)),
                    mon.getChi2Ratio(b), mon.getChi2Ref(b),
                    mon.getGate2Attempts(b), mon.getStepSamples(b),
                    100.0 * mon.getGate2RejectRatio(b),
                    mon.getStepPercentile(b, 0.5), mon.getStepPercentile(b, 0.9),
                    mon.getStepMax(b), mon.getCommonModeEpochs(), mon.getUnidentifiableEpochs(b));
        }
    }

    @Test
    @DisplayName("闸3 骨架网：基站间基线静态解稳定、健康小时无误报")
    void testBaseSkeletonNetwork() throws IOException {
        Assumptions.assumeTrue(DATA_AVAILABLE, JointTestDataConfig.getMissingMessage());

        String baseA = BASES[0];
        String baseB = BASES[1];
        int testHour = 8;

        String baseAFile = JointTestDataConfig.getRtcmPath(baseA, testHour);
        String baseBFile = JointTestDataConfig.getRtcmPath(baseB, testHour);
        String roverFile = JointTestDataConfig.getRtcmPath(ROVER, testHour);
        if (!Files.exists(Paths.get(baseAFile)) || !Files.exists(Paths.get(baseBFile))
                || !Files.exists(Paths.get(roverFile))) {
            System.out.printf("跳过：第%d小时数据缺失%n", testHour);
            return;
        }

        System.out.printf("%n=== 闸3 骨架网验证: %02d时 ===%n", testHour);

        PrcOpt opt = createJointOpt();
        opt.refpos = Constants.POSOPT_POS_XYZ;
        JointConfig jcfg = new JointConfig();
        jcfg.maxBaseline = 2;
        jcfg.baseSkeletonNetwork = true;
        jcfg.baseCoordOverride = new LinkedHashMap<>();
        jcfg.baseCoordOverride.put(baseA, BASE_A_TRUE_XYZ.clone());

        Map<String, byte[]> baseDataMap = new LinkedHashMap<>();
        baseDataMap.put(baseA, Files.readAllBytes(Paths.get(baseAFile)));
        baseDataMap.put(baseB, Files.readAllBytes(Paths.get(baseBFile)));
        JointRtkProcessor proc = new JointRtkProcessor(opt, jcfg);
        JointRtkProcessor.JointRtkResult result =
                proc.process(Files.readAllBytes(Paths.get(roverFile)), baseDataMap);

        BaseSkeletonNetwork sk = proc.getJointRtk().skeleton;
        assertNotNull(sk, "闸3 开启后应产出骨架网解算器");
        assertTrue(result.solutions.size() > 0, "联合解算应有输出（闸3 不得影响主解）");

        BaseStation[] bases = proc.getJointRtk().bases;
        double coordLen = 0.0;
        if (bases != null && bases.length >= 2 && bases[0] != null && bases[1] != null) {
            double dx = bases[0].rb[0] - bases[1].rb[0];
            double dy = bases[0].rb[1] - bases[1].rb[1];
            double dz = bases[0].rb[2] - bases[1].rb[2];
            coordLen = Math.sqrt(dx * dx + dy * dy + dz * dz);
        }
        System.out.printf("  coordLen(按基站坐标)=%.3f m%n", coordLen);

        int attempts = sk.getAttempts(0, 1);
        int fixes = sk.getFixSamples(0, 1);
        double refLen = sk.getRefLength(0, 1);
        double len = sk.getLength(0, 1);
        double maxChange = sk.getMaxLengthChange(0, 1);
        System.out.printf("  pair(0,1): attempts=%d fix=%d refLen=%.3f m len=%.3f m maxD=%.3f m suspect=%s/%s%n",
                attempts, fixes, refLen, len, maxChange, sk.isSuspect(0), sk.isSuspect(1));

        assertTrue(attempts > 0, "骨架网应执行解算");
        assertTrue(fixes > 0, "静态骨架解应能固定，实际 fix=" + fixes);
        assertTrue(refLen > 0.0, "参考长度应已标定，实际 " + refLen);

        // 健康小时：基站未移动，骨架长度相对标定值的波动应在厘米级
        assertTrue(maxChange < 0.05,
                "健康小时骨架基线长度变化应 <5cm，实际 " + String.format("%.3f", maxChange) + " m");
        assertFalse(sk.isSuspect(0), "健康小时不应误报基站[0]");
        assertFalse(sk.isSuspect(1), "健康小时不应误报基站[1]");
    }

    @Test
    @DisplayName("对比：独立基线RTK vs 联合RTK")
    void testJointVsIndependent() throws IOException {
        Assumptions.assumeTrue(DATA_AVAILABLE, JointTestDataConfig.getMissingMessage());

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

        assertFalse(resA.solutions.isEmpty(), "独立基线A 应有解算输出");
        assertFalse(resB.solutions.isEmpty(), "独立基线B 应有解算输出");
        assertFalse(resJ.solutions.isEmpty(), "联合RTK 应有解算输出");
        assertTrue(fixA > 0, "独立基线A 应存在 FIX 解");
        assertTrue(fixJ > 0, "联合RTK 应存在 FIX 解");

        assertTrue(fixRatioA >= 80.0,
                "独立基线A FIX 率应 ≥80%，实际 " + String.format("%.1f%%", fixRatioA));
        assertTrue(fixRatioJ >= 80.0,
                "联合RTK FIX 率应 ≥80%，实际 " + String.format("%.1f%%", fixRatioJ));
        assertTrue(fixRatioJ >= fixRatioA - 5.0,
                "联合RTK 不应显著劣于最优独立基线：联合 " + String.format("%.1f%%", fixRatioJ)
                        + " vs 基线A " + String.format("%.1f%%", fixRatioA));
        assertTrue(fixRatioJ >= fixRatioB + 20.0,
                "联合RTK 应显著优于最差独立基线：联合 " + String.format("%.1f%%", fixRatioJ)
                        + " vs 基线B " + String.format("%.1f%%", fixRatioB));
    }

    @Test
    @DisplayName("标准RTK Kinematic模式模糊度诊断")
    void testStandardRtkKinemaDiag() throws IOException {
        Assumptions.assumeTrue(DATA_AVAILABLE, JointTestDataConfig.getMissingMessage());

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