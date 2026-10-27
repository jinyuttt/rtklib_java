package org.rtklib.java.adjust;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.rtklib.java.adjust.datasource.RtcmFileDataSource;
import org.rtklib.java.adjust.engine.BaseStationDiagnoser;
import org.rtklib.java.adjust.engine.BaselineConsistencyChecker;
import org.rtklib.java.adjust.engine.MultiBaselineAdjust;
import org.rtklib.java.adjust.engine.NetworkAdjustment;
import org.rtklib.java.adjust.engine.ResidualWhiteness;
import org.rtklib.java.adjust.model.AdjustDiagnosisConfig;
import org.rtklib.java.adjust.model.BaseStationDiagnosis;
import org.rtklib.java.adjust.model.MultiBaselineConfig;
import org.rtklib.java.adjust.model.ReliabilityGrade;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.PrcOpt;
import org.rtklib.java.data.SolData;
import org.rtklib.java.data.SolutionStatus;
import org.rtklib.java.data.Sta;
import org.rtklib.java.rtkpos.RtkProcessor;
import org.rtklib.java.rtcm.RtcmCallbackDecoder;
import org.rtklib.java.rtcm.RtcmDataHandler;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * adjust 真实数据测试：与 rtklib-joint 的 JointRtkRealDataTest 使用同一批数据
 * （2 基站 + 1 测站 RTCM3 录制流），验证 SBS（core 逐基线独立解算）+ 平差的完整链路。
 *
 * <p>数据路径见 {@code mb-adjust-real-data.properties}（本地文件，不入库）。
 * 数据缺失时按跳过处理，不判定失败。</p>
 */
@DisplayName("MultiBaselineAdjust 真实数据测试（2基站1测站）")
public class MultiBaselineAdjustRealDataTest {

    private static final Properties PROPS = new Properties();
    private static boolean propsLoaded = false;

    private static String prop(String key, String def) {
        if (!propsLoaded) {
            propsLoaded = true;
            try (InputStream is = MultiBaselineAdjustRealDataTest.class
                    .getResourceAsStream("/mb-adjust-real-data.properties")) {
                if (is != null) PROPS.load(is);
            } catch (IOException ignored) {}
        }
        return PROPS.getProperty(key, def);
    }

    private static final String DATA_ROOT = prop("data.root", "");
    private static final String[] BASES = prop("base.group", "").split("[,\\s]+");
    private static final String ROVER = prop("rover.id", "");
    private static final String DATE = prop("date", "2026-10-26");
    private static final int TEST_HOUR = Integer.parseInt(prop("test.hour", "8"));
    private static final int START_HOUR = Integer.parseInt(prop("start.hour", "0"));
    private static final int END_HOUR = Integer.parseInt(prop("end.hour", "17"));

    private static final boolean DATA_AVAILABLE = !DATA_ROOT.isEmpty()
            && Files.isDirectory(Paths.get(DATA_ROOT))
            && BASES.length >= 2 && !BASES[0].isEmpty() && !ROVER.isEmpty();

    @BeforeAll
    static void checkData() {
        if (!DATA_AVAILABLE) {
            System.out.println("adjust 测试数据不可用，跳过。请配置 rtklib-adjust/src/test/resources/mb-adjust-real-data.properties");
        } else {
            System.out.printf("数据配置: root=%s, bases=%s, rover=%s, date=%s%n",
                    DATA_ROOT, Arrays.toString(BASES), ROVER, DATE);
        }
    }

    private static String rtcmPath(String station, int hour) {
        return String.format("%s\\%s\\%s\\%d.rtcm3", DATA_ROOT, station, DATE, hour);
    }

    private static boolean hourAvailable(int hour) {
        return Files.exists(Paths.get(rtcmPath(BASES[0], hour)))
                && Files.exists(Paths.get(rtcmPath(BASES[1], hour)))
                && Files.exists(Paths.get(rtcmPath(ROVER, hour)));
    }

    /** 与 JointRtkRealDataTest.createJointOpt 保持一致，保证两条链路可比。 */
    private static PrcOpt createOpt() {
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

    /** 从基站 RTCM 流的 1005/1006 报文提取 ECEF 坐标。 */
    private static double[] extractBasePos(byte[] rtcmData) {
        double[][] pos = {null};
        RtcmDataHandler handler = new RtcmDataHandler() {
            @Override public void onStation(Sta sta) {
                if (sta.pos != null && sta.pos.length >= 3
                        && (sta.pos[0] != 0 || sta.pos[1] != 0 || sta.pos[2] != 0)) {
                    pos[0] = sta.pos.clone();
                }
            }
            @Override public void onSsr(org.rtklib.java.data.Ssr ssr) {}
            @Override public void onEph(org.rtklib.java.data.Eph eph) {}
            @Override public void onGeph(org.rtklib.java.data.Geph geph) {}
            @Override public void onObservationEpoch(org.rtklib.java.rtcm.ObservationEpoch epoch) {}
            @Override public void onAuxData(org.rtklib.java.rtcm.AuxData aux) {}
            @Override public void onFinish() {}
        };
        RtcmCallbackDecoder decoder = new RtcmCallbackDecoder(handler);
        decoder.feed(rtcmData, 0, rtcmData.length);
        decoder.finish();
        return pos[0];
    }

    /** 单条基线独立 RTK（core SBS），返回解算序列。 */
    private static RtkProcessor.RtkResult runBaseline(String roverFile, String baseFile) throws IOException {
        RtkProcessor rtk = new RtkProcessor(createOpt());
        return rtk.process(roverFile, baseFile);
    }

    private static void printBaselineStat(String label, RtkProcessor.RtkResult res) {
        long fix = res.solutions.stream().filter(s -> s.status == SolutionStatus.FIX).count();
        long flt = res.solutions.stream().filter(s -> s.status == SolutionStatus.FLOAT).count();
        long sgl = res.solutions.size() - fix - flt;
        System.out.printf("  %s: 总=%d FIX=%d(%+.1f%%) FLOAT=%d SINGLE=%d%n",
                label, res.solutions.size(), fix,
                res.solutions.isEmpty() ? 0 : 100.0 * fix / res.solutions.size(),
                flt, sgl);
    }

    /** 某状态解的 ECEF 均值；无该状态解返回 null。 */
    private static double[] meanSol(List<SolData> solutions, SolutionStatus status) {
        double[] sum = null;
        int n = 0;
        for (SolData sd : solutions) {
            if (sd.status != status) continue;
            org.rtklib.java.data.Position p = sd.getPosition(org.rtklib.java.data.CoordType.ECEF);
            if (p == null) continue;
            if (sum == null) sum = new double[3];
            sum[0] += p.v1; sum[1] += p.v2; sum[2] += p.v3;
            n++;
        }
        return (sum == null || n == 0) ? null : new double[]{sum[0] / n, sum[1] / n, sum[2] / n};
    }

    /**
     * 基站坐标归因诊断：SINGLE 解是 rover 的 SPP 绝对坐标（与基站无关，均值精度 ~0.5-1m），
     * FIX 解继承基站坐标误差（R' = R + δ）。FIX 均值偏离 SPP 均值 ~0 的基站坐标正确，
     * 偏离 ~4.77m 的基站 RTCM 1005 坐标存在系统差——用于把自由网的对称位移归因到单基站。
     */
    private static void printSppAnchorCheck(String baseA, String baseB,
                                            RtkProcessor.RtkResult resA, RtkProcessor.RtkResult resB) {
        System.out.println("  --- SPP anchor check ---");
        double[] spp = meanSol(resA.solutions, SolutionStatus.SINGLE);
        double[] fixA = meanSol(resA.solutions, SolutionStatus.FIX);
        double[] fixB = meanSol(resB.solutions, SolutionStatus.FIX);
        if (spp == null || fixA == null || fixB == null) {
            System.out.println("  missing SINGLE/FIX solutions, skip");
            return;
        }
        double dAx = fixA[0] - spp[0], dAy = fixA[1] - spp[1], dAz = fixA[2] - spp[2];
        double dBx = fixB[0] - spp[0], dBy = fixB[1] - spp[1], dBz = fixB[2] - spp[2];
        System.out.printf("  FIX(%s) - SPP: (%+.4f, %+.4f, %+.4f) |d|=%.4f m%n",
                baseA, dAx, dAy, dAz, Math.sqrt(dAx * dAx + dAy * dAy + dAz * dAz));
        System.out.printf("  FIX(%s) - SPP: (%+.4f, %+.4f, %+.4f) |d|=%.4f m%n",
                baseB, dBx, dBy, dBz, Math.sqrt(dBx * dBx + dBy * dBy + dBz * dBz));
    }

    /**
     * 文档机制验证（ADJUST_USAGE.md「基站异常诊断」）：k=2 时 SPP 定位各基站、
     * 与 RTCM 1005 对比偏差最大者为异常；再以正常基站为参考静态解算建议坐标，
     * 输出 BaseStationDiagnosis——即 adjust 的工程闭环（识别异常基站 + 建议坐标）。
     */
    private static List<BaseStationDiagnosis> runBaseDiagnosis(String baseA, String baseB,
                                                               String baseAFile, String baseBFile,
                                                               String roverFile) {
        System.out.println("  --- 基站异常诊断（BaseStationDiagnoser, k=2 SPP定向+静态建议坐标）---");
        AdjustDiagnosisConfig dcfg = AdjustDiagnosisConfig.builder()
                .enable(true)
                .sigma0Threshold(3.0)
                .diagnoseWindow(100)
                .diagnoseRatio(0.8)
                .useSppForDirection(true)
                .useStaticForCorrection(true)
                .minStaticDataHours(0.5)
                .cacheDiagnosis(true)
                .build();
        BaseStationDiagnoser diagnoser = new BaseStationDiagnoser(dcfg);
        diagnoser.addBaseStation(new RtcmFileDataSource(baseA, List.of(baseAFile)));
        diagnoser.addBaseStation(new RtcmFileDataSource(baseB, List.of(baseBFile)));
        diagnoser.setRover(new RtcmFileDataSource(ROVER, List.of(roverFile)));

        List<BaseStationDiagnosis> diagnoses = diagnoser.diagnose(createOpt());
        if (diagnoses.isEmpty()) {
            System.out.println("  未诊断出异常基站");
        }
        for (BaseStationDiagnosis d : diagnoses) {
            System.out.println("  " + d);
        }
        return diagnoses;
    }

    /**
     * 把两条独立基线的 FIX 解按历元时间配对，喂给 MultiBaselineAdjust。
     * 返回喂入的历元数。
     */
    private static int feedAdjust(MultiBaselineAdjust adjust,
                                  RtkProcessor.RtkResult resA, RtkProcessor.RtkResult resB) {
        Map<String, SolData[]> epochs = new LinkedHashMap<>();
        for (SolData sd : resA.solutions) {
            if (sd.status == SolutionStatus.FIX) {
                epochs.computeIfAbsent(sd.timeStr, k -> new SolData[2])[0] = sd;
            }
        }
        for (SolData sd : resB.solutions) {
            if (sd.status == SolutionStatus.FIX) {
                SolData[] pair = epochs.computeIfAbsent(sd.timeStr, k -> new SolData[2]);
                if (pair[1] == null) pair[1] = sd;
            }
        }

        int fed = 0;
        for (Map.Entry<String, SolData[]> e : epochs.entrySet()) {
            String tag = e.getKey();
            SolData[] pair = e.getValue();
            if (pair[0] != null) { adjust.feedBaseline(BASES[0], pair[0], tag); fed++; }
            if (pair[1] != null) { adjust.feedBaseline(BASES[1], pair[1], tag); fed++; }
        }
        return fed;
    }

    private static MultiBaselineAdjust createAdjust(String baseA, String baseB,
                                                    double[] posA, double[] posB,
                                                    Map<ReliabilityGrade, Integer> gradeCount,
                                                    int[] suspectCount, int[] inconsistentCount,
                                                    List<double[]> epochEnuSigma,
                                                    List<Double> epochSigma0,
                                                    List<Double> epochCrossVal) {
        MultiBaselineConfig config = MultiBaselineConfig.builder()
                .baseStationIds(baseA, baseB)
                .baseCoordinateSigma(0.02)   // RTCM 基站坐标 1~2cm 系统偏差
                .build();
        MultiBaselineAdjust adjust = MultiBaselineAdjust.create(config);
        adjust.registerBasePosition(baseA, posA);
        adjust.registerBasePosition(baseB, posB);
        adjust.onEpochResult((coord, detail) -> {
            gradeCount.merge(coord.grade(), 1, Integer::sum);
            if (detail.success && detail.p01Xyz != null && detail.Dx != null) {
                epochEnuSigma.add(enuSigmas(detail.p01Xyz, detail.Dx));
                epochSigma0.add(detail.sigma0);
            }
            if (coord.crossValidationDist() >= 0) epochCrossVal.add(coord.crossValidationDist());
        });
        adjust.onSuspect((baseId, dist, tag) -> suspectCount[0]++);
        adjust.onConsistency((baseId, drift, tag) -> inconsistentCount[0]++);
        return adjust;
    }

    /** ECEF 协方差 3x3 → ENU 标准差 {σE, σN, σU}（m）。 */
    private static double[] enuSigmas(double[] xyz, double[][] cov) {
        double[] llh = new double[3];
        CoordTransform.ecef2pos(xyz, llh);
        double lat = llh[0], lon = llh[1];
        double sl = Math.sin(lat), cl = Math.cos(lat);
        double so = Math.sin(lon), co = Math.cos(lon);
        double[][] R = {
                {-so, co, 0},
                {-sl * co, -sl * so, cl},
                {cl * co, cl * so, sl}
        };
        double[] out = new double[3];
        for (int i = 0; i < 3; i++) {
            double var = 0;
            for (int a = 0; a < 3; a++) {
                for (int b = 0; b < 3; b++) {
                    var += R[i][a] * cov[a][b] * R[i][b];
                }
            }
            out[i] = Math.sqrt(Math.max(var, 0));
        }
        return out;
    }

    private static double percentile(List<Double> v, double p) {
        if (v.isEmpty()) return Double.NaN;
        List<Double> s = new ArrayList<>(v);
        Collections.sort(s);
        int idx = (int) Math.min(s.size() - 1, Math.round(p * (s.size() - 1)));
        return s.get(idx);
    }

    private static void printAdjustReports(MultiBaselineAdjust adjust) {
        // 一致性检核
        BaselineConsistencyChecker.Report cr = adjust.getConsistencyReport();
        System.out.println("  --- 一致性检核 ---");
        for (BaselineConsistencyChecker.PairDrift pd : cr.drifts()) {
            System.out.printf("  基站对 %s-%s: 样本=%d 漂移范数=%.4f m%n",
                    pd.baseA(), pd.baseB(), pd.samples(), pd.driftNorm());
        }
        for (BaselineConsistencyChecker.BaseDrift bd : cr.baseDrifts()) {
            System.out.printf("  基站 %s: 配对=%d 位移范数=%.4f m%n",
                    bd.baseId(), bd.pairs(), bd.norm());
        }
        System.out.printf("  一致性: %s 可疑基站=%s%n", cr.pass() ? "通过" : "异常", cr.suspectBaseIds());

        // 残差白度检验
        ResidualWhiteness.Report wr = adjust.getResidualWhitenessReport();
        System.out.println("  --- 残差白度 ---");
        for (ResidualWhiteness.Series s : wr.series()) {
            if (s.undetermined()) {
                System.out.printf("  %s 轴%d: 样本=%d 样本不足，不作判定%n", s.baseId(), s.axis(), s.samples());
            } else {
                System.out.printf("  %s 轴%d: 样本=%d lag1=%+.3f runsZ=%+.2f %s%n",
                        s.baseId(), s.axis(), s.samples(), s.lag1(), s.runsZ(),
                        s.pass() ? "白" : "非白");
            }
        }
        System.out.printf("  白度总体: %s（序列数=%d）%n", wr.pass() ? "通过" : "存在非白残差", wr.series().size());
    }

    private static void printNetworkResult(String label, NetworkAdjustment.Result r) {
        System.out.printf("  --- %s --- success=%s%n", label, r.success());
        if (!r.success()) {
            System.out.println("  " + r.message());
            return;
        }
        if (r.roverLlh() != null && r.roverLlh().length >= 3) {
            System.out.printf("  Rover LLH=(%.9f, %.9f, %.4f)%n",
                    Math.toDegrees(r.roverLlh()[0]), Math.toDegrees(r.roverLlh()[1]), r.roverLlh()[2]);
        }
        if (r.roverCov() != null) {
            double[] sig = enuSigmas(r.roverXyz(), r.roverCov());
            System.out.printf("  Rover ENU σ = (%.1f, %.1f, %.1f) mm%n", sig[0] * 1e3, sig[1] * 1e3, sig[2] * 1e3);
        }
        System.out.printf("  sigma0=%.4f dof=%d 观测=%d 未知数=%d%n",
                r.sigma0(), r.dof(), r.obsCount(), r.unknownCount());
        r.baseDelta().forEach((baseId, delta) -> {
            double norm = Math.sqrt(delta[0] * delta[0] + delta[1] * delta[1] + delta[2] * delta[2]);
            System.out.printf("  基站 %s 位移: (%+.4f, %+.4f, %+.4f) |d|=%.4f m%n",
                    baseId, delta[0], delta[1], delta[2], norm);
        });
    }

    @Test
    @DisplayName("单小时：SBS双基线 → 逐历元平差 → 整时段网平差")
    void testAdjustSingleHour() throws IOException {
        assumeTrue(DATA_AVAILABLE, "adjust 测试数据不可用");
        int hour = TEST_HOUR;
        assumeTrue(hourAvailable(hour), "第" + hour + "小时数据缺失");

        System.out.printf("=== adjust 单小时验证: %s %02d时 ===%n", DATE, hour);
        String baseA = BASES[0], baseB = BASES[1];
        String roverFile = rtcmPath(ROVER, hour);
        String baseAFile = rtcmPath(baseA, hour);
        String baseBFile = rtcmPath(baseB, hour);

        double[] posA = extractBasePos(Files.readAllBytes(Paths.get(baseAFile)));
        double[] posB = extractBasePos(Files.readAllBytes(Paths.get(baseBFile)));
        assumeTrue(posA != null && posB != null, "基站流无 RTCM 1005/1006，无法注册基站坐标");
        System.out.printf("  基站 %s: (%.4f, %.4f, %.4f)%n", baseA, posA[0], posA[1], posA[2]);
        System.out.printf("  基站 %s: (%.4f, %.4f, %.4f)%n", baseB, posB[0], posB[1], posB[2]);

        System.out.println("  --- 独立基线 RTK（core SBS）---");
        RtkProcessor.RtkResult resA = runBaseline(roverFile, baseAFile);
        RtkProcessor.RtkResult resB = runBaseline(roverFile, baseBFile);
        printBaselineStat("基线A(" + baseA + ")", resA);
        printBaselineStat("基线B(" + baseB + ")", resB);
        printSppAnchorCheck(baseA, baseB, resA, resB);
        List<BaseStationDiagnosis> diagnoses = runBaseDiagnosis(baseA, baseB, baseAFile, baseBFile, roverFile);

        // 工程闭环：用建议坐标修正异常基站 → 重新 RTK + 平差 → 验证 sigma0/crossVal 收敛
        BaseStationDiagnosis diag = diagnoses.stream()
                .filter(d -> d.suggestedXyz != null)
                .findFirst().orElse(null);
        if (diag != null) {
            System.out.println("  --- 闭环验证: 用建议坐标修正 " + diag.baseId + " 后重新平差 ---");
            boolean fixIsA = diag.baseId.equals(baseA);
            PrcOpt opt2 = createOpt();
            opt2.rb = new double[]{diag.suggestedXyz[0], diag.suggestedXyz[1], diag.suggestedXyz[2]};
            opt2.refpos = Constants.POSOPT_POS_XYZ;
            opt2.intpref = 1;
            RtkProcessor.RtkResult resFixed = new RtkProcessor(opt2)
                    .process(roverFile, fixIsA ? baseAFile : baseBFile);
            printBaselineStat("修正后基线(" + diag.baseId + ")", resFixed);

            Map<ReliabilityGrade, Integer> gradeCount2 = new EnumMap<>(ReliabilityGrade.class);
            int[] suspectCount2 = {0}, inconsistentCount2 = {0};
            List<double[]> enuSigma2 = new ArrayList<>();
            List<Double> sigma0List2 = new ArrayList<>();
            List<Double> crossVal2 = new ArrayList<>();
            MultiBaselineAdjust adjust2 = createAdjust(baseA, baseB,
                    fixIsA ? diag.suggestedXyz : posA, fixIsA ? posB : diag.suggestedXyz,
                    gradeCount2, suspectCount2, inconsistentCount2,
                    enuSigma2, sigma0List2, crossVal2);
            int fed2 = feedAdjust(adjust2, fixIsA ? resFixed : resA, fixIsA ? resB : resFixed);
            adjust2.flush();
            System.out.printf("  喂入基线解=%d 平差历元=%d%n", fed2, adjust2.getEpochIndex());
            gradeCount2.forEach((g, c) -> System.out.printf("  %-14s %d%n", g, c));
            if (!crossVal2.isEmpty()) {
                System.out.printf("  双基线互差 crossVal: 中位=%.1fmm P90=%.1fmm 最大=%.1fmm%n",
                        percentile(crossVal2, 0.5) * 1e3, percentile(crossVal2, 0.9) * 1e3,
                        Collections.max(crossVal2) * 1e3);
            }
            printNetworkResult("约束平差(修正后)", adjust2.computeNetworkAdjustment(false));
        }

        Map<ReliabilityGrade, Integer> gradeCount = new EnumMap<>(ReliabilityGrade.class);
        int[] suspectCount = {0}, inconsistentCount = {0};
        List<double[]> epochEnuSigma = new ArrayList<>();
        List<Double> epochSigma0 = new ArrayList<>();
        List<Double> epochCrossVal = new ArrayList<>();
        MultiBaselineAdjust adjust = createAdjust(baseA, baseB, posA, posB,
                gradeCount, suspectCount, inconsistentCount,
                epochEnuSigma, epochSigma0, epochCrossVal);

        int fed = feedAdjust(adjust, resA, resB);
        adjust.flush();

        System.out.println("  --- 逐历元平差 ---");
        System.out.printf("  喂入基线解=%d 平差历元=%d 网观测=%d%n",
                fed, adjust.getEpochIndex(), adjust.getNetworkObsCount());
        gradeCount.forEach((g, c) -> System.out.printf("  %-14s %d%n", g, c));
        System.out.printf("  假固定嫌疑告警=%d 一致性告警=%d%n", suspectCount[0], inconsistentCount[0]);

        if (!epochEnuSigma.isEmpty()) {
            double mE = 0, mN = 0, mU = 0;
            for (double[] s : epochEnuSigma) { mE += s[0]; mN += s[1]; mU += s[2]; }
            int n = epochEnuSigma.size();
            System.out.printf("  逐历元融合 σ 均值: E=%.1fmm N=%.1fmm U=%.1fmm%n",
                    mE / n * 1e3, mN / n * 1e3, mU / n * 1e3);
        }
        if (!epochCrossVal.isEmpty()) {
            System.out.printf("  双基线互差 crossVal: 中位=%.1fmm P90=%.1fmm 最大=%.1fmm%n",
                    percentile(epochCrossVal, 0.5) * 1e3, percentile(epochCrossVal, 0.9) * 1e3,
                    Collections.max(epochCrossVal) * 1e3);
        }
        if (!epochSigma0.isEmpty()) {
            System.out.printf("  逐历元 sigma0: 中位=%.2f（>1 表示标称协方差偏乐观）%n",
                    percentile(epochSigma0, 0.5));
        }
        printAdjustReports(adjust);

        System.out.println("  --- 整时段网平差 ---");
        NetworkAdjustment.Result constrained = adjust.computeNetworkAdjustment(false);
        printNetworkResult("约束平差（基站坐标已知）", constrained);
        NetworkAdjustment.Result free = adjust.computeNetworkAdjustment(true);
        printNetworkResult("自由网平差（重心基准）", free);

        assertTrue(adjust.getEpochIndex() > 0, "应有历元参与平差");
        assertTrue(adjust.getNetworkObsCount() > 0, "应累积网平差观测");
        assertTrue(constrained.success(), "约束平差应成功: " + constrained.message());
    }

    @Test
    @Tag("manual")
    @DisplayName("整时段（0~17时）：跨历元网平差累积")
    void testAdjustWholeSpan() throws IOException {
        assumeTrue(DATA_AVAILABLE, "adjust 测试数据不可用");

        System.out.printf("=== adjust 整时段网平差: %s %02d~%02d时 ===%n", DATE, START_HOUR, END_HOUR);
        String baseA = BASES[0], baseB = BASES[1];

        double[] posA = null, posB = null;
        int hoursUsed = 0;
        long totalFed = 0;

        Map<ReliabilityGrade, Integer> gradeCount = new EnumMap<>(ReliabilityGrade.class);
        int[] suspectCount = {0}, inconsistentCount = {0};
        List<double[]> epochEnuSigma = new ArrayList<>();
        List<Double> epochSigma0 = new ArrayList<>();
        List<Double> epochCrossVal = new ArrayList<>();

        // 先用第一个可用小时提取基站坐标
        for (int h = START_HOUR; h <= END_HOUR && posA == null; h++) {
            if (hourAvailable(h)) {
                posA = extractBasePos(Files.readAllBytes(Paths.get(rtcmPath(baseA, h))));
                posB = extractBasePos(Files.readAllBytes(Paths.get(rtcmPath(baseB, h))));
            }
        }
        assumeTrue(posA != null && posB != null, "基站流无 RTCM 1005/1006");

        MultiBaselineAdjust adjust = createAdjust(baseA, baseB, posA, posB,
                gradeCount, suspectCount, inconsistentCount,
                epochEnuSigma, epochSigma0, epochCrossVal);

        for (int h = START_HOUR; h <= END_HOUR; h++) {
            if (!hourAvailable(h)) {
                System.out.printf("  %02d时: 数据缺失，跳过%n", h);
                continue;
            }
            RtkProcessor.RtkResult resA = runBaseline(rtcmPath(ROVER, h), rtcmPath(baseA, h));
            RtkProcessor.RtkResult resB = runBaseline(rtcmPath(ROVER, h), rtcmPath(baseB, h));
            int fed = feedAdjust(adjust, resA, resB);
            totalFed += fed;
            hoursUsed++;
            long fixA = resA.solutions.stream().filter(s -> s.status == SolutionStatus.FIX).count();
            long fixB = resB.solutions.stream().filter(s -> s.status == SolutionStatus.FIX).count();
            System.out.printf("  %02d时: FIX A=%d B=%d 喂入=%d%n", h, fixA, fixB, fed);
        }
        adjust.flush();

        System.out.println("----------------------------------------");
        System.out.printf("  使用小时=%d 喂入基线解=%d 平差历元=%d 网观测=%d%n",
                hoursUsed, totalFed, adjust.getEpochIndex(), adjust.getNetworkObsCount());
        gradeCount.forEach((g, c) -> System.out.printf("  %-14s %d%n", g, c));
        System.out.printf("  假固定嫌疑告警=%d 一致性告警=%d%n", suspectCount[0], inconsistentCount[0]);
        printAdjustReports(adjust);

        printNetworkResult("约束平差（全时段）", adjust.computeNetworkAdjustment(false));
        printNetworkResult("自由网平差（全时段）", adjust.computeNetworkAdjustment(true));

        assertTrue(adjust.getEpochIndex() > 0, "应有历元参与平差");
    }
}
