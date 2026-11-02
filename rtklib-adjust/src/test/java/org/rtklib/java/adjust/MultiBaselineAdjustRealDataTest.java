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
import org.rtklib.java.adjust.model.AdjustResult;
import org.rtklib.java.adjust.model.BaseStationDiagnosis;
import org.rtklib.java.adjust.model.MultiBaselineConfig;
import org.rtklib.java.adjust.model.ReliabilityGrade;
import org.rtklib.java.adjust.model.ReliableCoordinate;
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
        return feedAdjust(adjust, resA, resB, false);
    }

    private static int feedAdjust(MultiBaselineAdjust adjust,
                                  RtkProcessor.RtkResult resA, RtkProcessor.RtkResult resB,
                                  boolean includeFloat) {
        Map<String, SolData[]> epochs = new LinkedHashMap<>();
        for (SolData sd : resA.solutions) {
            if (sd.status == SolutionStatus.FIX || (includeFloat && sd.status == SolutionStatus.FLOAT)) {
                epochs.computeIfAbsent(sd.timeStr, k -> new SolData[2])[0] = sd;
            }
        }
        for (SolData sd : resB.solutions) {
            if (sd.status == SolutionStatus.FIX || (includeFloat && sd.status == SolutionStatus.FLOAT)) {
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

    /** 把 A/B 两条基线的全部解（FIX + FLOAT）按历元时间配对喂入，准入裁决交给引擎内部。 */
    private static int feedAllSolutions(MultiBaselineAdjust adjust,
                                        RtkProcessor.RtkResult resA, RtkProcessor.RtkResult resB) {
        Map<String, SolData[]> epochs = new LinkedHashMap<>();
        for (SolData sd : resA.solutions) {
            if (sd.status != SolutionStatus.FIX && sd.status != SolutionStatus.FLOAT) continue;
            SolData[] pair = epochs.computeIfAbsent(sd.timeStr, k -> new SolData[2]);
            if (pair[0] == null || sd.status == SolutionStatus.FIX) pair[0] = sd;
        }
        for (SolData sd : resB.solutions) {
            if (sd.status != SolutionStatus.FIX && sd.status != SolutionStatus.FLOAT) continue;
            SolData[] pair = epochs.computeIfAbsent(sd.timeStr, k -> new SolData[2]);
            if (pair[1] == null || sd.status == SolutionStatus.FIX) pair[1] = sd;
        }

        int fed = 0;
        for (Map.Entry<String, SolData[]> e : epochs.entrySet()) {
            SolData[] pair = e.getValue();
            if (pair[0] != null) { adjust.feedBaseline(BASES[0], pair[0], e.getKey()); fed++; }
            if (pair[1] != null) { adjust.feedBaseline(BASES[1], pair[1], e.getKey()); fed++; }
        }
        return fed;
    }

    private static double dist3(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    /**
     * FLOAT 补位 A/B 对照：同一批真实数据分别喂给"仅 FIX"与"FIX+FLOAT"两条管线。
     *
     * <p>验证三点：</p>
     * <ol>
     *   <li>收益：FIX 不足两条的历元由 FLOAT 补位后自由度增加、不再降级为 k=1；</li>
     *   <li>代价：FLOAT 降权后融合坐标相对"仅 FIX"结果的偏移量（应远小于 FLOAT-FIX 较差）；</li>
     *   <li>隔离：网平差观测数、一致性检核、约束平差结果与"仅 FIX"完全一致。</li>
     * </ol>
     */
    @Test
    @DisplayName("FLOAT补位 A/B 对照：仅FIX vs FIX+FLOAT")
    void testFloatAdmissionCompare() throws IOException {
        assumeTrue(DATA_AVAILABLE, "adjust 测试数据不可用");
        int hour = TEST_HOUR;
        assumeTrue(hourAvailable(hour), "第" + hour + "小时数据缺失");

        System.out.printf("=== FLOAT 补位 A/B 对照: %s %02d时 ===%n", DATE, hour);
        String baseA = BASES[0], baseB = BASES[1];
        String roverFile = rtcmPath(ROVER, hour);
        String baseAFile = rtcmPath(baseA, hour);
        String baseBFile = rtcmPath(baseB, hour);

        double[] posA = extractBasePos(Files.readAllBytes(Paths.get(baseAFile)));
        double[] posB = extractBasePos(Files.readAllBytes(Paths.get(baseBFile)));
        assumeTrue(posA != null && posB != null, "基站流无 RTCM 1005/1006，无法注册基站坐标");

        // 两个场景：常规掩蔽 15°（FIX 率高）与 30°（模拟遮挡/城市，FIX 率低）
        for (double elminDeg : new double[]{15.0, 30.0}) {
            PrcOpt opt = createOpt();
            opt.elmin = elminDeg * Constants.D2R;
            opt.elmaskar = opt.elmin;
            System.out.printf("--- 场景：仰角掩蔽 %.0f° ---%n", elminDeg);

            RtkProcessor.RtkResult resA = new RtkProcessor(opt).process(roverFile, baseAFile);
            RtkProcessor.RtkResult resB = new RtkProcessor(opt).process(roverFile, baseBFile);
            printBaselineStat("基线A(" + baseA + ")", resA);
            printBaselineStat("基线B(" + baseB + ")", resB);

            Map<String, SolutionStatus[]> paired = new TreeMap<>();
            for (SolData sd : resA.solutions) {
                if (sd.status == SolutionStatus.FIX || sd.status == SolutionStatus.FLOAT) {
                    paired.computeIfAbsent(sd.timeStr, k -> new SolutionStatus[2])[0] = sd.status;
                }
            }
            for (SolData sd : resB.solutions) {
                if (sd.status == SolutionStatus.FIX || sd.status == SolutionStatus.FLOAT) {
                    paired.computeIfAbsent(sd.timeStr, k -> new SolutionStatus[2])[1] = sd.status;
                }
            }
            int bothFix = 0, oneFix = 0, noFix = 0;
            for (SolutionStatus[] p : paired.values()) {
                int fix = (p[0] == SolutionStatus.FIX ? 1 : 0) + (p[1] == SolutionStatus.FIX ? 1 : 0);
                if (fix >= 2) bothFix++;
                else if (fix == 1) oneFix++;
                else noFix++;
            }
            System.out.printf("  历元 FIX 条数分布: 双FIX=%d 单FIX=%d 无FIX=%d（配对历元=%d）%n",
                    bothFix, oneFix, noFix, paired.size());

            Map<String, ReliableCoordinate> fixCoords = new TreeMap<>();
            Map<String, ReliableCoordinate> floatCoords = new TreeMap<>();
            Map<ReliabilityGrade, Integer> fixGrades = new EnumMap<>(ReliabilityGrade.class);
            Map<ReliabilityGrade, Integer> floatGrades = new EnumMap<>(ReliabilityGrade.class);
            Map<String, List<AdjustResult.RejectedFloat>> rejFloat = new TreeMap<>();

            MultiBaselineConfig cfgFix = MultiBaselineConfig.builder()
                    .baseStationIds(baseA, baseB)
                    .baseCoordinateSigma(0.02)
                    .build();
            MultiBaselineConfig cfgFloat = MultiBaselineConfig.builder()
                    .baseStationIds(baseA, baseB)
                    .baseCoordinateSigma(0.02)
                    .allowFloat(true)
                    .build();

            MultiBaselineAdjust adjFix = MultiBaselineAdjust.create(cfgFix);
            adjFix.registerBasePosition(baseA, posA);
            adjFix.registerBasePosition(baseB, posB);
            adjFix.onEpochResult((c, d) -> {
                fixCoords.put(c.epochTag(), c);
                fixGrades.merge(c.grade(), 1, Integer::sum);
            });

            MultiBaselineAdjust adjFloat = MultiBaselineAdjust.create(cfgFloat);
            adjFloat.registerBasePosition(baseA, posA);
            adjFloat.registerBasePosition(baseB, posB);
            adjFloat.onEpochResult((c, d) -> {
                floatCoords.put(c.epochTag(), c);
                floatGrades.merge(c.grade(), 1, Integer::sum);
                if (!d.rejectedFloats.isEmpty()) rejFloat.put(c.epochTag(), d.rejectedFloats);
            });

            int fedFix = feedAllSolutions(adjFix, resA, resB);
            int fedFloat = feedAllSolutions(adjFloat, resA, resB);
            adjFix.flush();
            adjFloat.flush();

            System.out.println("  --- 管线规模 ---");
            System.out.printf("  仅FIX    : 喂入=%d 平差历元=%d 网观测=%d%n",
                    fedFix, adjFix.getEpochIndex(), adjFix.getNetworkObsCount());
            System.out.printf("  FIX+FLOAT: 喂入=%d 平差历元=%d 网观测=%d%n",
                    fedFloat, adjFloat.getEpochIndex(), adjFloat.getNetworkObsCount());

            System.out.println("  --- 覆盖率（配对历元中实际出解的比例） ---");
            int pairedN = paired.size();
            double covFix = pairedN == 0 ? 0.0 : fixCoords.size() * 100.0 / pairedN;
            double covFloat = pairedN == 0 ? 0.0 : floatCoords.size() * 100.0 / pairedN;
            System.out.printf("  仅FIX    : %d/%d = %.1f%%%n", fixCoords.size(), pairedN, covFix);
            System.out.printf("  FIX+FLOAT: %d/%d = %.1f%%（%+.1f 个百分点）%n",
                    floatCoords.size(), pairedN, covFloat, covFloat - covFix);

            System.out.println("  --- 等级分布 ---");
            System.out.print("  仅FIX    : ");
            printGradeCounts(fixGrades);
            System.out.print("  FIX+FLOAT: ");
            printGradeCounts(floatGrades);

            List<Double> allDiffs = new ArrayList<>();
            List<Double> oneFixDiffs = new ArrayList<>();
            List<Double> oneFixCrossVal = new ArrayList<>();
            int oneFixCompensated = 0;
            int oneFixVerified = 0;
            int noFixSolved = 0;
            int noFixK1 = 0;
            int noFixK2 = 0;
            for (Map.Entry<String, SolutionStatus[]> e : paired.entrySet()) {
                String tag = e.getKey();
                SolutionStatus[] p = e.getValue();
                int fix = (p[0] == SolutionStatus.FIX ? 1 : 0) + (p[1] == SolutionStatus.FIX ? 1 : 0);
                ReliableCoordinate cf = fixCoords.get(tag);
                ReliableCoordinate cc = floatCoords.get(tag);
                if (cf != null && cc != null) {
                    allDiffs.add(dist3(cf.xyz(), cc.xyz()));
                }
                if (fix == 1) {
                    if (cf != null && cc != null) {
                        oneFixDiffs.add(dist3(cf.xyz(), cc.xyz()));
                        if (cc.usedBaselineCount() > cf.usedBaselineCount()) {
                            oneFixCompensated++;
                            if (cc.grade() == ReliabilityGrade.SINGLE_VERIFIED) oneFixVerified++;
                        }
                    }
                    if (cc != null && cc.crossValidationDist() >= 0) {
                        oneFixCrossVal.add(cc.crossValidationDist());
                    }
                }
                if (fix == 0 && cc != null && cf == null) {
                    noFixSolved++;
                    if (cc.usedBaselineCount() >= 2) noFixK2++;
                    else noFixK1++;
                }
            }

            System.out.println("  --- 收益：单FIX/无FIX 历元 ---");
            System.out.printf("  单FIX历元=%d 其中 FLOAT 补位升为 k>=2 的历元=%d（判 SINGLE_VERIFIED=%d）%n",
                    oneFix, oneFixCompensated, oneFixVerified);
            System.out.printf("  无FIX历元=%d 其中 FLOAT 补位后可解=%d（k=1:%d, k>=2:%d）%n",
                    noFix, noFixSolved, noFixK1, noFixK2);
            List<Double> rejDists = new ArrayList<>();
            for (List<AdjustResult.RejectedFloat> rs : rejFloat.values()) {
                for (AdjustResult.RejectedFloat r : rs) rejDists.add(r.distanceToFix());
            }
            int rejEpochs = rejFloat.size();
            int rejEpochStillSolved = 0;
            int rejEpochFixArmSolved = 0;
            for (String tag : rejFloat.keySet()) {
                if (fixCoords.containsKey(tag)) rejEpochFixArmSolved++;
                if (floatCoords.containsKey(tag)) rejEpochStillSolved++;
            }
            if (!rejDists.isEmpty()) {
                double th = rejFloat.values().iterator().next().get(0).threshold();
                System.out.printf("  准入门限=%.2fm：拒绝 FLOAT 的历元=%d 条数=%d 较差中位=%.1fmm 最大=%.1fmm%n",
                        th, rejEpochs, rejDists.size(),
                        percentile(rejDists, 0.5) * 1e3, Collections.max(rejDists) * 1e3);
                System.out.printf("  被拒历元仍出解=%d/%d（拒绝只删 FLOAT 补位，不删历元）%n",
                        rejEpochStillSolved, rejEpochFixArmSolved);
            } else {
                System.out.println("  准入门限未触发：本场景无 FLOAT 与 FIX 较差超阈值的历元");
            }
            System.out.printf("  有效补位（较差≤阈值，准入并判 SINGLE_VERIFIED）历元=%d%n",
                    oneFixCrossVal.size());
            if (!oneFixCrossVal.isEmpty()) {
                System.out.printf("  FLOAT 与 FIX 位置较差: 中位=%.1fmm P90=%.1fmm 最大=%.1fmm%n",
                        percentile(oneFixCrossVal, 0.5) * 1e3, percentile(oneFixCrossVal, 0.9) * 1e3,
                        Collections.max(oneFixCrossVal) * 1e3);
            }

            System.out.println("  --- 代价：融合坐标相对仅FIX 的偏移 ---");
            if (!oneFixDiffs.isEmpty()) {
                System.out.printf("  单FIX历元: 中位=%.2fmm P90=%.2fmm 最大=%.2fmm%n",
                        percentile(oneFixDiffs, 0.5) * 1e3, percentile(oneFixDiffs, 0.9) * 1e3,
                        Collections.max(oneFixDiffs) * 1e3);
            }
            if (!allDiffs.isEmpty()) {
                System.out.printf("  全部共同历元: 中位=%.2fmm P90=%.2fmm 最大=%.2fmm%n",
                        percentile(allDiffs, 0.5) * 1e3, percentile(allDiffs, 0.9) * 1e3,
                        Collections.max(allDiffs) * 1e3);
            }

            System.out.println("  --- 隔离：网平差与一致性 ---");
            BaselineConsistencyChecker.Report crFix = adjFix.getConsistencyReport();
            BaselineConsistencyChecker.Report crFloat = adjFloat.getConsistencyReport();
            System.out.printf("  一致性 仅FIX=%s%s  FIX+FLOAT=%s%s%n",
                    crFix.pass() ? "通过" : "异常", crFix.suspectBaseIds(),
                    crFloat.pass() ? "通过" : "异常", crFloat.suspectBaseIds());
            NetworkAdjustment.Result nrFix = adjFix.computeNetworkAdjustment(false);
            NetworkAdjustment.Result nrFloat = adjFloat.computeNetworkAdjustment(false);
            printNetworkResult("约束平差(仅FIX)", nrFix);
            printNetworkResult("约束平差(FIX+FLOAT)", nrFloat);
            if (nrFix.success() && nrFloat.success()) {
                System.out.printf("  约束平差 Rover 坐标差=%.6f mm（应为 0，FLOAT 未进入网平差观测）%n",
                        dist3(nrFix.roverXyz(), nrFloat.roverXyz()) * 1e3);
            }

            assertTrue(adjFloat.getEpochIndex() >= adjFix.getEpochIndex(), "FLOAT 补位不应减少平差历元");
            assertTrue(floatCoords.size() >= fixCoords.size(),
                    "FLOAT 补位不应降低覆盖率：仅FIX=" + fixCoords.size() + " FIX+FLOAT=" + floatCoords.size());
            assertEquals(rejEpochFixArmSolved, rejEpochStillSolved,
                    "被拒历元必须仍出解：拒绝只删 FLOAT 补位，不删历元");
            assertEquals(adjFix.getNetworkObsCount(), adjFloat.getNetworkObsCount(),
                    "网平差观测必须与仅FIX完全一致（FLOAT 不参与累积）");
            assertEquals(crFix.pass(), crFloat.pass(), "一致性判定必须与仅FIX一致");
            assertEquals(crFix.suspectBaseIds(), crFloat.suspectBaseIds(), "可疑基站列表必须与仅FIX一致");
            if (oneFix > 0) {
                assertTrue(oneFixCompensated > 0 || noFixSolved > 0,
                        "单FIX/无FIX历元应被 FLOAT 补位，实际补偿=" + oneFixCompensated);
            }
            if (nrFix.success() && nrFloat.success()) {
                assertEquals(0.0, dist3(nrFix.roverXyz(), nrFloat.roverXyz()), 1e-6,
                        "约束平差结果不应受 FLOAT 影响");
            }
            if (!oneFixDiffs.isEmpty()) {
                assertTrue(Collections.max(oneFixDiffs) < 0.05,
                        "单FIX历元坐标偏移应 < 5cm，实际=" + Collections.max(oneFixDiffs) * 1e3 + "mm");
            }
        }
    }

    private static void printGradeCounts(Map<ReliabilityGrade, Integer> grades) {
        for (ReliabilityGrade g : ReliabilityGrade.values()) {
            Integer n = grades.get(g);
            if (n != null && n > 0) System.out.printf("%s=%d ", g, n);
        }
        System.out.println();
    }

    private static double[][] cov9To3x3(double[] q) {
        double[][] m = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) m[i][j] = q[i * 3 + j];
        }
        return m;
    }

    /** 逐元素比值（分母为 0 或非有限值时跳过）。 */
    private static List<Double> ratioOf(List<Double> num, List<Double> den) {
        List<Double> out = new ArrayList<>();
        int n = Math.min(num.size(), den.size());
        for (int i = 0; i < n; i++) {
            double a = num.get(i), b = den.get(i);
            if (Double.isFinite(a) && Double.isFinite(b) && b > 0) out.add(a / b);
        }
        return out;
    }

    private static double[] ecefOf(SolData sd) {
        org.rtklib.java.data.Position p = sd.getPosition(org.rtklib.java.data.CoordType.ECEF);
        return p == null ? null : new double[]{p.v1, p.v2, p.v3};
    }

    /**
     * 按 adjust 的配对口径统计每个历元的构成：{fixCount, floatCount, maxDist}。
     * maxDist 为 FLOAT 相对唯一 FIX 的位置较差 (m)，与
     * {@code GnssBaselineAdjust.maxDistanceToFix} 同口径，仅当 fixCount==1 时有效。
     */
    private static Map<String, double[]> epochComposition(RtkProcessor.RtkResult resA,
                                                          RtkProcessor.RtkResult resB) {
        Map<String, SolData[]> pair = new LinkedHashMap<>();
        for (SolData sd : resA.solutions) {
            if (sd.status != SolutionStatus.FIX && sd.status != SolutionStatus.FLOAT) continue;
            SolData[] p = pair.computeIfAbsent(sd.timeStr, k -> new SolData[2]);
            if (p[0] == null || (p[0].status != SolutionStatus.FIX && sd.status == SolutionStatus.FIX)) {
                p[0] = sd;
            }
        }
        for (SolData sd : resB.solutions) {
            if (sd.status != SolutionStatus.FIX && sd.status != SolutionStatus.FLOAT) continue;
            SolData[] p = pair.computeIfAbsent(sd.timeStr, k -> new SolData[2]);
            if (p[1] == null || (p[1].status != SolutionStatus.FIX && sd.status == SolutionStatus.FIX)) {
                p[1] = sd;
            }
        }
        Map<String, double[]> out = new LinkedHashMap<>();
        for (Map.Entry<String, SolData[]> e : pair.entrySet()) {
            SolData[] p = e.getValue();
            int nFix = 0, nFlt = 0;
            double[] fixPos = null;
            double maxDist = 0.0;
            for (SolData sd : p) {
                if (sd == null) continue;
                if (sd.status == SolutionStatus.FIX) {
                    nFix++;
                    if (fixPos == null) fixPos = ecefOf(sd);
                } else {
                    nFlt++;
                }
            }
            if (nFix == 1 && fixPos != null) {
                for (SolData sd : p) {
                    if (sd == null || sd.status != SolutionStatus.FLOAT) continue;
                    double[] fp = ecefOf(sd);
                    if (fp != null) maxDist = Math.max(maxDist, dist3(fixPos, fp));
                }
            }
            out.put(e.getKey(), new double[]{nFix, nFlt, maxDist});
        }
        return out;
    }

    /**
     * 路线A A/B 对照：同一批真实数据分别用 Kalman 后验 Pa（{@code sol.qr}）与
     * 单历元归约法方程 {@code Q_pos}（{@code sol.qPos}）作为协方差来源。
     *
     * <p>验证三点：</p>
     * <ol>
     *   <li>开关只改协方差，<b>不改变</b>解算坐标与历元数；</li>
     *   <li>Q_pos 相对 Pa 的量级变化（Pa 含跨历元相关性，通常偏小）；</li>
     *   <li>该变化确实传导到 adjust 的 Dx 与保护级 PL。</li>
     * </ol>
     */
    @Test
    @DisplayName("路线A A/B 对照：Kalman后验Pa vs 单历元归约Q_pos")
    void testQposCovSourceCompare() throws IOException {
        assumeTrue(DATA_AVAILABLE, "adjust 测试数据不可用");
        int hour = TEST_HOUR;
        assumeTrue(hourAvailable(hour), "第" + hour + "小时数据缺失");

        System.out.printf("=== 路线A 协方差来源 A/B 对照: %s %02d时 ===%n", DATE, hour);
        String baseA = BASES[0], baseB = BASES[1];
        String roverFile = rtcmPath(ROVER, hour);
        String baseAFile = rtcmPath(baseA, hour);
        String baseBFile = rtcmPath(baseB, hour);

        double[] posA = extractBasePos(Files.readAllBytes(Paths.get(baseAFile)));
        double[] posB = extractBasePos(Files.readAllBytes(Paths.get(baseBFile)));
        assumeTrue(posA != null && posB != null, "基站流无 RTCM 1005/1006，无法注册基站坐标");

        PrcOpt optPa = createOpt();
        PrcOpt optQpos = createOpt();
        optQpos.diagMask = PrcOpt.DIAG_QPOS;

        RtkProcessor.RtkResult aPa = new RtkProcessor(optPa).process(roverFile, baseAFile);
        RtkProcessor.RtkResult aQp = new RtkProcessor(optQpos).process(roverFile, baseAFile);
        RtkProcessor.RtkResult bPa = new RtkProcessor(optPa).process(roverFile, baseBFile);
        RtkProcessor.RtkResult bQp = new RtkProcessor(optQpos).process(roverFile, baseBFile);

        System.out.println("  --- 基线解算统计 ---");
        printBaselineStat("基线A(Pa)  ", aPa);
        printBaselineStat("基线A(Q_pos)", aQp);
        printBaselineStat("基线B(Pa)  ", bPa);
        printBaselineStat("基线B(Q_pos)", bQp);

        assertEquals(aPa.solutions.size(), aQp.solutions.size(), "开关不应改变历元数");
        assertEquals(bPa.solutions.size(), bQp.solutions.size(), "开关不应改变历元数");
        int posMoved = 0;
        for (int i = 0; i < aPa.solutions.size(); i++) {
            double[] p0 = ecefOf(aPa.solutions.get(i));
            double[] p1 = ecefOf(aQp.solutions.get(i));
            if (p0 != null && p1 != null && dist3(p0, p1) > 1e-9) posMoved++;
        }
        assertEquals(0, posMoved, "Q_pos 只改协方差，不应改变解算坐标");

        System.out.println("  --- 开关有效性 ---");
        long fixA = aQp.solutions.stream().filter(s -> s.status == SolutionStatus.FIX).count();
        long fltA = aQp.solutions.stream().filter(s -> s.status == SolutionStatus.FLOAT).count();
        long qposA = aQp.solutions.stream().filter(s -> s.qPos != null).count();
        long legacyA = aPa.solutions.stream().filter(s -> s.qPos != null).count();
        System.out.printf("  基线A: FIX=%d FLOAT=%d | qPos非空=%d | 未开开关时qPos非空=%d%n",
                fixA, fltA, qposA, legacyA);
        assertEquals(0, legacyA, "未开 DIAG_QPOS 时不应产出 qPos");
        assertTrue(qposA >= fixA + fltA, "FIX/FLOAT 解都应带 qPos，实际=" + qposA);

        System.out.println("  --- 协方差量级：Q_pos vs Pa（ENU σ，FIX 历元）---");
        List<Double> rE = new ArrayList<>();
        List<Double> rN = new ArrayList<>();
        List<Double> rU = new ArrayList<>();
        List<Double> trRatio = new ArrayList<>();
        List<Double> absPaE = new ArrayList<>();
        List<Double> absPaN = new ArrayList<>();
        List<Double> absPaU = new ArrayList<>();
        List<Double> absQpE = new ArrayList<>();
        List<Double> absQpN = new ArrayList<>();
        List<Double> absQpU = new ArrayList<>();
        int qpSmaller = 0, qpLarger = 0, qpSame = 0;
        for (int i = 0; i < aPa.solutions.size(); i++) {
            SolData s0 = aPa.solutions.get(i);
            SolData s1 = aQp.solutions.get(i);
            if (s0.status != SolutionStatus.FIX || s0.covEcef == null || s1.covEcef == null) continue;
            double[] xyz = ecefOf(s1);
            if (xyz == null) continue;
            double[] e0 = enuSigmas(xyz, cov9To3x3(s0.covEcef));
            double[] e1 = enuSigmas(xyz, cov9To3x3(s1.covEcef));
            if (e0[0] <= 0 || e0[1] <= 0 || e0[2] <= 0) continue;
            rE.add(e1[0] / e0[0]);
            rN.add(e1[1] / e0[1]);
            rU.add(e1[2] / e0[2]);
            double sum0 = e0[0] + e0[1] + e0[2];
            double sum1 = e1[0] + e1[1] + e1[2];
            trRatio.add(sum1 / sum0);
            if (sum1 / sum0 < 0.99) qpSmaller++;
            else if (sum1 / sum0 > 1.01) qpLarger++;
            else qpSame++;
            absPaE.add(e0[0] * 1e3); absPaN.add(e0[1] * 1e3); absPaU.add(e0[2] * 1e3);
            absQpE.add(e1[0] * 1e3); absQpN.add(e1[1] * 1e3); absQpU.add(e1[2] * 1e3);
        }
        if (!trRatio.isEmpty()) {
            System.out.printf("  Pa   σE/N/U 中位 = (%.2f, %.2f, %.2f) mm%n",
                    percentile(absPaE, 0.5), percentile(absPaN, 0.5), percentile(absPaU, 0.5));
            System.out.printf("  Q_pos σE/N/U 中位 = (%.2f, %.2f, %.2f) mm%n",
                    percentile(absQpE, 0.5), percentile(absQpN, 0.5), percentile(absQpU, 0.5));
            System.out.printf("  σE 比值: 最小=%.4f P10=%.4f 中位=%.4f P90=%.4f 最大=%.4f%n",
                    Collections.min(rE), percentile(rE, 0.1), percentile(rE, 0.5),
                    percentile(rE, 0.9), Collections.max(rE));
            System.out.printf("  σN 比值: 最小=%.4f P10=%.4f 中位=%.4f P90=%.4f 最大=%.4f%n",
                    Collections.min(rN), percentile(rN, 0.1), percentile(rN, 0.5),
                    percentile(rN, 0.9), Collections.max(rN));
            System.out.printf("  σU 比值: 最小=%.4f P10=%.4f 中位=%.4f P90=%.4f 最大=%.4f%n",
                    Collections.min(rU), percentile(rU, 0.1), percentile(rU, 0.5),
                    percentile(rU, 0.9), Collections.max(rU));
            System.out.printf("  三轴和比值: 中位=%.4f（Q_pos偏小=%d 相当=%d 偏大=%d，FIX 历元=%d）%n",
                    percentile(trRatio, 0.5), qpSmaller, qpSame, qpLarger, trRatio.size());
        } else {
            System.out.println("  无可用 FIX 协方差样本");
        }

        List<Double> fE = new ArrayList<>();
        List<Double> fN = new ArrayList<>();
        List<Double> fU = new ArrayList<>();
        List<Double> fTr = new ArrayList<>();
        List<Double> fixNs = new ArrayList<>();
        List<Double> fltNs = new ArrayList<>();
        for (int i = 0; i < aPa.solutions.size(); i++) {
            SolData s0 = aPa.solutions.get(i);
            SolData s1 = aQp.solutions.get(i);
            if (s0.status != SolutionStatus.FLOAT || s0.covEcef == null || s1.covEcef == null) continue;
            fltNs.add((double) s0.numSat);
            double[] xyz = ecefOf(s1);
            if (xyz == null) continue;
            double[] e0 = enuSigmas(xyz, cov9To3x3(s0.covEcef));
            double[] e1 = enuSigmas(xyz, cov9To3x3(s1.covEcef));
            if (e0[0] <= 0 || e0[1] <= 0 || e0[2] <= 0) continue;
            fE.add(e1[0] / e0[0]);
            fN.add(e1[1] / e0[1]);
            fU.add(e1[2] / e0[2]);
            fTr.add((e1[0] + e1[1] + e1[2]) / (e0[0] + e0[1] + e0[2]));
        }
        for (SolData s0 : aPa.solutions) {
            if (s0.status == SolutionStatus.FIX) fixNs.add((double) s0.numSat);
        }
        if (!fTr.isEmpty()) {
            System.out.printf("  FLOAT 历元 σE 比值: 中位=%.4f [%.4f, %.4f]  σN: 中位=%.4f [%.4f, %.4f]  σU: 中位=%.4f [%.4f, %.4f]%n",
                    percentile(fE, 0.5), Collections.min(fE), Collections.max(fE),
                    percentile(fN, 0.5), Collections.min(fN), Collections.max(fN),
                    percentile(fU, 0.5), Collections.min(fU), Collections.max(fU));
            System.out.printf("  FLOAT 历元 三轴和比值: 中位=%.4f [%.4f, %.4f]（样本=%d）%n",
                    percentile(fTr, 0.5), Collections.min(fTr), Collections.max(fTr), fTr.size());
            System.out.printf("  卫星数中位：FIX=%.0f FLOAT=%.0f（单历元信息量与几何的差异来源）%n",
                    percentile(fixNs, 0.5), percentile(fltNs, 0.5));
        } else {
            System.out.println("  无可用 FLOAT 协方差样本");
        }

        System.out.println("  --- adjust 侧传导（仅 FIX 管线）---");
        Map<String, double[]> dxPa = new TreeMap<>();
        Map<String, double[]> dxQp = new TreeMap<>();
        Map<String, double[]> solPa = new TreeMap<>();
        Map<String, double[]> solQp = new TreeMap<>();
        Map<String, Double> plPa = new TreeMap<>();
        Map<String, Double> plQp = new TreeMap<>();

        MultiBaselineConfig cfgPa = MultiBaselineConfig.builder()
                .baseStationIds(baseA, baseB).baseCoordinateSigma(0.02).build();
        MultiBaselineAdjust adjPa = MultiBaselineAdjust.create(cfgPa);
        adjPa.registerBasePosition(baseA, posA);
        adjPa.registerBasePosition(baseB, posB);
        adjPa.onEpochResult((c, d) -> {
            plPa.put(c.epochTag(), c.protectionLevel());
            if (d.success && d.Dx != null && d.p01Xyz != null) {
                dxPa.put(c.epochTag(), enuSigmas(d.p01Xyz, d.Dx));
                solPa.put(c.epochTag(), d.p01Xyz.clone());
            }
        });

        MultiBaselineConfig cfgQp = MultiBaselineConfig.builder()
                .baseStationIds(baseA, baseB).baseCoordinateSigma(0.02).build();
        MultiBaselineAdjust adjQp = MultiBaselineAdjust.create(cfgQp);
        adjQp.registerBasePosition(baseA, posA);
        adjQp.registerBasePosition(baseB, posB);
        adjQp.onEpochResult((c, d) -> {
            plQp.put(c.epochTag(), c.protectionLevel());
            if (d.success && d.Dx != null && d.p01Xyz != null) {
                dxQp.put(c.epochTag(), enuSigmas(d.p01Xyz, d.Dx));
                solQp.put(c.epochTag(), d.p01Xyz.clone());
            }
        });

        int fedPa = feedAdjust(adjPa, aPa, bPa);
        int fedQp = feedAdjust(adjQp, aQp, bQp);
        adjPa.flush();
        adjQp.flush();
        System.out.printf("  Pa   : 喂入=%d 平差历元=%d 网观测=%d%n",
                fedPa, adjPa.getEpochIndex(), adjPa.getNetworkObsCount());
        System.out.printf("  Q_pos: 喂入=%d 平差历元=%d 网观测=%d%n",
                fedQp, adjQp.getEpochIndex(), adjQp.getNetworkObsCount());
        assertEquals(adjPa.getEpochIndex(), adjQp.getEpochIndex(), "协方差来源不应改变平差历元数");

        List<Double> adjE = new ArrayList<>();
        List<Double> adjN = new ArrayList<>();
        List<Double> adjU = new ArrayList<>();
        List<Double> adjPl = new ArrayList<>();
        List<Double> adjPos = new ArrayList<>();
        for (String tag : dxPa.keySet()) {
            double[] d0 = dxPa.get(tag);
            double[] d1 = dxQp.get(tag);
            if (d1 == null) continue;
            if (d0[0] > 0 && d0[1] > 0 && d0[2] > 0) {
                adjE.add(d1[0] / d0[0]);
                adjN.add(d1[1] / d0[1]);
                adjU.add(d1[2] / d0[2]);
            }
            double p0 = plPa.getOrDefault(tag, Double.NaN);
            double p1 = plQp.getOrDefault(tag, Double.NaN);
            if (!Double.isNaN(p0) && p0 > 0 && !Double.isNaN(p1)) adjPl.add(p1 / p0);
            double[] s0 = solPa.get(tag);
            double[] s1 = solQp.get(tag);
            if (s0 != null && s1 != null) adjPos.add(dist3(s0, s1));
        }
        if (!adjE.isEmpty()) {
            System.out.printf("  adjust σE 比值: 中位=%.3f P10=%.3f P90=%.3f%n",
                    percentile(adjE, 0.5), percentile(adjE, 0.1), percentile(adjE, 0.9));
            System.out.printf("  adjust σN 比值: 中位=%.3f P10=%.3f P90=%.3f%n",
                    percentile(adjN, 0.5), percentile(adjN, 0.1), percentile(adjN, 0.9));
            System.out.printf("  adjust σU 比值: 中位=%.3f P10=%.3f P90=%.3f%n",
                    percentile(adjU, 0.5), percentile(adjU, 0.1), percentile(adjU, 0.9));
        }
        if (!adjPl.isEmpty()) {
            System.out.printf("  保护级 PL 比值: 中位=%.3f P10=%.3f P90=%.3f%n",
                    percentile(adjPl, 0.5), percentile(adjPl, 0.1), percentile(adjPl, 0.9));
        }
        if (!adjPos.isEmpty()) {
            System.out.printf("  平差坐标差(Pa vs Q_pos): 中位=%.3fmm P90=%.3fmm 最大=%.3fmm%n",
                    percentile(adjPos, 0.5) * 1e3, percentile(adjPos, 0.9) * 1e3,
                    Collections.max(adjPos) * 1e3);
            assertTrue(Collections.max(adjPos) < 0.005,
                    "换协方差来源后平差坐标漂移应 < 5mm，实际="
                            + Collections.max(adjPos) * 1e3 + "mm");
        }

        System.out.println("  --- FLOAT 分支：allowFloat=true, floatCovInflate=1.0，喂入含 FLOAT ---");
        Map<String, double[]> fdxPa = new TreeMap<>();
        Map<String, double[]> fdxQp = new TreeMap<>();
        Map<String, double[]> fsolPa = new TreeMap<>();
        Map<String, double[]> fsolQp = new TreeMap<>();
        Map<String, Double> fplPa = new TreeMap<>();
        Map<String, Double> fplQp = new TreeMap<>();
        Map<String, List<AdjustResult.RejectedFloat>> frejPa = new TreeMap<>();
        Map<String, List<AdjustResult.RejectedFloat>> frejQp = new TreeMap<>();
        Map<String, Double> fdistPa = new TreeMap<>();
        Map<String, Double> fdistQp = new TreeMap<>();
        Map<String, ReliableCoordinate> fcoordPa = new TreeMap<>();
        Map<String, ReliableCoordinate> fcoordQp = new TreeMap<>();
        MultiBaselineConfig cfgFPa = MultiBaselineConfig.builder()
                .baseStationIds(baseA, baseB).baseCoordinateSigma(0.02)
                .solutionAdmission(MultiBaselineConfig.SolutionAdmissionConfig.builder()
                        .allowFloat(true).floatCovInflate(1.0).build())
                .build();
        MultiBaselineAdjust fAdjPa = MultiBaselineAdjust.create(cfgFPa);
        fAdjPa.registerBasePosition(baseA, posA);
        fAdjPa.registerBasePosition(baseB, posB);
        fAdjPa.onEpochResult((c, d) -> {
            fplPa.put(c.epochTag(), c.protectionLevel());
            fdistPa.put(c.epochTag(), c.crossValidationDist());
            fcoordPa.put(c.epochTag(), c);
            if (!d.rejectedFloats.isEmpty()) frejPa.put(c.epochTag(), d.rejectedFloats);
            if (d.success && d.Dx != null && d.p01Xyz != null) {
                fdxPa.put(c.epochTag(), enuSigmas(d.p01Xyz, d.Dx));
                fsolPa.put(c.epochTag(), d.p01Xyz.clone());
            }
        });
        MultiBaselineConfig cfgFQp = MultiBaselineConfig.builder()
                .baseStationIds(baseA, baseB).baseCoordinateSigma(0.02)
                .solutionAdmission(MultiBaselineConfig.SolutionAdmissionConfig.builder()
                        .allowFloat(true).floatCovInflate(1.0).build())
                .build();
        MultiBaselineAdjust fAdjQp = MultiBaselineAdjust.create(cfgFQp);
        fAdjQp.registerBasePosition(baseA, posA);
        fAdjQp.registerBasePosition(baseB, posB);
        fAdjQp.onEpochResult((c, d) -> {
            fplQp.put(c.epochTag(), c.protectionLevel());
            fdistQp.put(c.epochTag(), c.crossValidationDist());
            fcoordQp.put(c.epochTag(), c);
            if (!d.rejectedFloats.isEmpty()) frejQp.put(c.epochTag(), d.rejectedFloats);
            if (d.success && d.Dx != null && d.p01Xyz != null) {
                fdxQp.put(c.epochTag(), enuSigmas(d.p01Xyz, d.Dx));
                fsolQp.put(c.epochTag(), d.p01Xyz.clone());
            }
        });
        int fFedPa = feedAdjust(fAdjPa, aPa, bPa, true);
        int fFedQp = feedAdjust(fAdjQp, aQp, bQp, true);
        fAdjPa.flush();
        fAdjQp.flush();
        System.out.printf("  Pa   : 喂入=%d 平差历元=%d 网观测=%d%n",
                fFedPa, fAdjPa.getEpochIndex(), fAdjPa.getNetworkObsCount());
        System.out.printf("  Q_pos: 喂入=%d 平差历元=%d 网观测=%d%n",
                fFedQp, fAdjQp.getEpochIndex(), fAdjQp.getNetworkObsCount());
        assertEquals(fAdjPa.getNetworkObsCount(), fAdjQp.getNetworkObsCount(),
                "网平差观测仅吃 FIX，不应受 FLOAT 协方差来源影响");
        assertEquals(fAdjPa.getEpochIndex(), fAdjQp.getEpochIndex(),
                "协方差来源不应改变平差历元数");

        List<Double> fAdjE = new ArrayList<>();
        List<Double> fAdjN = new ArrayList<>();
        List<Double> fAdjU = new ArrayList<>();
        List<Double> fDrift = new ArrayList<>();
        Map<String, double[]> comp = epochComposition(aPa, bPa);
        List<Double> gatedDrift = new ArrayList<>();
        List<Double> openDrift = new ArrayList<>();
        List<Double> pureFloatDrift = new ArrayList<>();
        List<Double> plPurePa = new ArrayList<>();
        List<Double> plPureQp = new ArrayList<>();
        List<Double> plOnePa = new ArrayList<>();
        List<Double> plOneQp = new ArrayList<>();
        List<Double> oneFixMaxDist = new ArrayList<>();
        List<Double> oneFixDriftOverDist = new ArrayList<>();
        int nPureFloatEpoch = 0;
        int nOneFixFloatEpoch = 0;
        int nNoFloatEpochDrift = 0;
        for (String tag : fdxPa.keySet()) {
            double[] d0 = fdxPa.get(tag);
            double[] d1 = fdxQp.get(tag);
            if (d1 == null) continue;
            if (d0[0] > 0 && d0[1] > 0 && d0[2] > 0) {
                fAdjE.add(d1[0] / d0[0]);
                fAdjN.add(d1[1] / d0[1]);
                fAdjU.add(d1[2] / d0[2]);
            }
            double[] s0 = fsolPa.get(tag);
            double[] s1 = fsolQp.get(tag);
            if (s0 != null && s1 != null) fDrift.add(dist3(s0, s1));
            if (s0 == null || s1 == null) continue;
            double[] c = comp.get(tag);
            if (c == null) continue;
            double drift = dist3(s0, s1);
            if (c[1] == 0) {
                if (drift > 0.20) nNoFloatEpochDrift++;
                continue;
            }
            if (c[0] == 0) {
                nPureFloatEpoch++;
                pureFloatDrift.add(drift);
                double pp = fplPa.getOrDefault(tag, Double.NaN);
                double pq = fplQp.getOrDefault(tag, Double.NaN);
                if (!Double.isNaN(pp) && !Double.isNaN(pq)) {
                    plPurePa.add(pp);
                    plPureQp.add(pq);
                }
            } else if (c[0] == 1) {
                nOneFixFloatEpoch++;
                double pp = fplPa.getOrDefault(tag, Double.NaN);
                double pq = fplQp.getOrDefault(tag, Double.NaN);
                if (!Double.isNaN(pp) && !Double.isNaN(pq)) {
                    plOnePa.add(pp);
                    plOneQp.add(pq);
                }
                if (c[2] > 0.20) gatedDrift.add(drift);
                else openDrift.add(drift);
                oneFixMaxDist.add(c[2]);
                oneFixDriftOverDist.add(drift / Math.max(c[2], 1e-9));
            }
        }
        if (!fAdjE.isEmpty()) {
            System.out.printf("  adjust σE 比值: 中位=%.4f P10=%.4f P90=%.4f 最大=%.4f%n",
                    percentile(fAdjE, 0.5), percentile(fAdjE, 0.1),
                    percentile(fAdjE, 0.9), Collections.max(fAdjE));
            System.out.printf("  adjust σN 比值: 中位=%.4f P10=%.4f P90=%.4f 最大=%.4f%n",
                    percentile(fAdjN, 0.5), percentile(fAdjN, 0.1),
                    percentile(fAdjN, 0.9), Collections.max(fAdjN));
            System.out.printf("  adjust σU 比值: 中位=%.4f P10=%.4f P90=%.4f 最大=%.4f（样本=%d）%n",
                    percentile(fAdjU, 0.5), percentile(fAdjU, 0.1),
                    percentile(fAdjU, 0.9), Collections.max(fAdjU), fAdjE.size());
        }
        if (!fDrift.isEmpty()) {
            long over = fDrift.stream().filter(v -> v > 0.20).count();
            System.out.printf("  融合坐标漂移: 中位=%.3fmm P90=%.3fmm 最大=%.3fmm | >0.20m 的历元=%d%n",
                    percentile(fDrift, 0.5) * 1e3, percentile(fDrift, 0.9) * 1e3,
                    Collections.max(fDrift) * 1e3, over);
            System.out.printf("  含FLOAT历元构成: 纯FLOAT补位(0FIX)=%d | 1FIX+FLOAT=%d%n",
                    nPureFloatEpoch, nOneFixFloatEpoch);
            System.out.printf("  不含FLOAT历元中漂移>0.20m 的个数=%d（应恒为0）%n", nNoFloatEpochDrift);
            if (!pureFloatDrift.isEmpty()) {
                System.out.printf("  纯FLOAT补位历元漂移: 中位=%.3fmm 最大=%.3fmm | >0.20m 个数=%d%n",
                        percentile(pureFloatDrift, 0.5) * 1e3,
                        Collections.max(pureFloatDrift) * 1e3,
                        pureFloatDrift.stream().filter(v -> v > 0.20).count());
                List<Double> refX = new ArrayList<>();
                List<Double> refY = new ArrayList<>();
                List<Double> refZ = new ArrayList<>();
                for (String t : fsolQp.keySet()) {
                    double[] c = comp.get(t);
                    if (c == null || c[1] > 0) continue;
                    double[] p = fsolQp.get(t);
                    refX.add(p[0]);
                    refY.add(p[1]);
                    refZ.add(p[2]);
                }
                if (refX.size() > 10) {
                    double[] ref = {percentile(refX, 0.5), percentile(refY, 0.5), percentile(refZ, 0.5)};
                    List<Double> devFix = new ArrayList<>();
                    for (String t : fsolQp.keySet()) {
                        double[] c = comp.get(t);
                        if (c == null || c[1] > 0) continue;
                        devFix.add(dist3(fsolQp.get(t), ref));
                    }
                    List<Double> devFloat = new ArrayList<>();
                    for (String t : fsolQp.keySet()) {
                        double[] c = comp.get(t);
                        if (c == null || c[0] != 0 || c[1] == 0) continue;
                        devFloat.add(dist3(fsolQp.get(t), ref));
                    }
                    System.out.printf("  相对FIX段中位坐标: 不含FLOAT历元偏离 中位=%.3fm 最大=%.3fm%n",
                            percentile(devFix, 0.5), Collections.max(devFix));
                    System.out.printf("  相对FIX段中位坐标: 纯FLOAT历元偏离 中位=%.3fm 最大=%.3fm%n",
                            percentile(devFloat, 0.5), Collections.max(devFloat));
                }
            }
            if (!plPurePa.isEmpty()) {
                System.out.printf("  纯FLOAT补位 PL: Pa 中位=%.3fm  Q_pos 中位=%.3fm  比值中位=%.3f%n",
                        percentile(plPurePa, 0.5), percentile(plPureQp, 0.5),
                        percentile(ratioOf(plPureQp, plPurePa), 0.5));
            }
            if (!plOnePa.isEmpty()) {
                System.out.printf("  1FIX+FLOAT   PL: Pa 中位=%.3fm  Q_pos 中位=%.3fm  比值中位=%.3f%n",
                        percentile(plOnePa, 0.5), percentile(plOneQp, 0.5),
                        percentile(ratioOf(plOneQp, plOnePa), 0.5));
            }
            if (!oneFixMaxDist.isEmpty()) {
                System.out.printf("  1FIX+FLOAT   FLOAT-FIX较差: 中位=%.3fm 最大=%.3fm | 漂移/较差 比值中位=%.3f%n",
                        percentile(oneFixMaxDist, 0.5) * 1e3,
                        Collections.max(oneFixMaxDist) * 1e3,
                        percentile(oneFixDriftOverDist, 0.5));
            }
            if (!gatedDrift.isEmpty()) {
                System.out.printf("  较差>0.20m 的历元: 样本=%d 漂移中位=%.3fmm 最大=%.3fmm%n",
                        gatedDrift.size(), percentile(gatedDrift, 0.5) * 1e3,
                        Collections.max(gatedDrift) * 1e3);
            }
            if (!openDrift.isEmpty()) {
                System.out.printf("  较差≤0.20m 的历元: 样本=%d 漂移中位=%.3fmm 最大=%.3fmm%n",
                        openDrift.size(), percentile(openDrift, 0.5) * 1e3,
                        Collections.max(openDrift) * 1e3);
            }
        }

        System.out.println("  --- 准入门限：FLOAT-FIX 较差 > floatAgreementThreshold 则不进入 WLS ---");
        assertEquals(frejPa.keySet(), frejQp.keySet(),
                "准入是位置判据，与协方差来源无关，两臂拒绝历元必须一致");
        int nGatedEpoch = frejPa.size();
        double minGatedDist = Double.POSITIVE_INFINITY;
        double maxGatedDist = 0;
        int nGatedDistNotAboveThreshold = 0;
        int nGatedDriftNonZero = 0;
        double maxGatedDrift = 0;
        int nGatedCrossValNotNaN = 0;
        int nGatedUsedNotOne = 0;
        int nGatedGradeNotUnverified = 0;
        int nGatedFloatStillAdmitted = 0;
        for (Map.Entry<String, List<AdjustResult.RejectedFloat>> e : frejPa.entrySet()) {
            String tag = e.getKey();
            for (AdjustResult.RejectedFloat r : e.getValue()) {
                minGatedDist = Math.min(minGatedDist, r.distanceToFix());
                maxGatedDist = Math.max(maxGatedDist, r.distanceToFix());
                if (r.distanceToFix() <= r.threshold()) nGatedDistNotAboveThreshold++;
            }
            double[] s0 = fsolPa.get(tag);
            double[] s1 = fsolQp.get(tag);
            double drift = (s0 != null && s1 != null) ? dist3(s0, s1) : Double.NaN;
            if (Double.isFinite(drift)) {
                maxGatedDrift = Math.max(maxGatedDrift, drift);
                if (drift > 1e-6) nGatedDriftNonZero++;
            }
            if (!Double.isNaN(fdistPa.getOrDefault(tag, 0.0))) nGatedCrossValNotNaN++;
            ReliableCoordinate c0 = fcoordPa.get(tag);
            ReliableCoordinate c1 = fcoordQp.get(tag);
            if (c0 != null && c0.usedBaselineCount() != 1) nGatedUsedNotOne++;
            if (c1 != null && c1.usedBaselineCount() != 1) nGatedUsedNotOne++;
            if (c0 != null && c0.grade() != ReliabilityGrade.UNVERIFIED) nGatedGradeNotUnverified++;
            if (c1 != null && c1.grade() != ReliabilityGrade.UNVERIFIED) nGatedGradeNotUnverified++;
            double[] comp0 = comp.get(tag);
            if (comp0 != null && comp0[1] > e.getValue().size()) nGatedFloatStillAdmitted++;
            System.out.printf("    拒绝 %s: 较差=%.3fm 漂移=%.3fmm k(Pa)=%d k(Qp)=%d grade(Pa)=%s crossVal(Pa)=%s%n",
                    tag, e.getValue().get(0).distanceToFix(), drift * 1e3,
                    c0 == null ? -1 : c0.usedBaselineCount(),
                    c1 == null ? -1 : c1.usedBaselineCount(),
                    c0 == null ? "null" : c0.grade(),
                    c0 == null ? "null" : String.valueOf(c0.crossValidationDist()));
        }
        System.out.printf("  拒绝历元=%d 条数=%d 较差范围=[%.3fm, %.3fm] 被拒后仍留 FLOAT 的历元=%d%n",
                nGatedEpoch, frejPa.values().stream().mapToInt(List::size).sum(),
                nGatedEpoch > 0 ? minGatedDist : Double.NaN, maxGatedDist,
                nGatedFloatStillAdmitted);
        System.out.printf("  被拒历元两臂坐标漂移>1µm 的个数=%d（应为0，两臂输入已相同）最大=%.3fµm%n",
                nGatedDriftNonZero, maxGatedDrift * 1e6);
        System.out.printf("  被拒历元 crossValidationDist 非 NaN 的个数=%d（应为0，无参照物即无值）%n",
                nGatedCrossValNotNaN);
        assertEquals(0, nGatedDistNotAboveThreshold,
                "被拒绝的 FLOAT 较差必须严格大于阈值");
        assertEquals(0, nGatedDriftNonZero,
                "门限生效后两臂在该历元输入相同，融合坐标漂移必须 < 1µm，实际="
                        + maxGatedDrift * 1e6 + "µm");
        assertEquals(0, nGatedCrossValNotNaN,
                "被拒绝的 FLOAT 不在平差内，crossValidationDist 应为 NaN 而非 0.0");
        assertEquals(0, nGatedUsedNotOne,
                "被拒绝后本历元只剩 1 条 FIX，usedBaselineCount 必须为 1");
        assertEquals(0, nGatedGradeNotUnverified,
                "被拒绝后零自由度、无交叉验证，等级必须为 UNVERIFIED");
    }
}