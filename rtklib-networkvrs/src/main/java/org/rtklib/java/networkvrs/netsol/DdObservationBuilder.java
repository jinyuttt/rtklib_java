package org.rtklib.java.networkvrs.netsol;

import org.rtklib.java.common.ObsCode;
import org.rtklib.java.common.RtklibCommon;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.GTime;
import org.rtklib.java.data.Nav;
import org.rtklib.java.data.Obsd;
import org.rtklib.java.ephemeris.EphModel;
import org.rtklib.java.ppprtk.SsrCorrector;
import org.rtklib.java.troposphere.TroposphereModel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [JAVA-ONLY 原创] 双差观测构造：给定两站在同一历元的观测与广播星历，生成逐卫星双差量。
 * 双差观测方程与信号对选择策略均为本项目自行设计，未移植任何外部开源项目代码。
 *
 * <p>信号对按系统固定优先级选取（BDS 优先 B1I/B3I，GPS 优先 L1C/L2W，GAL 优先 L1C/L5Q），
 * 避免各站自动选频导致频间偏差不一致。参考星取两站最低高度角最大者；
 * 卫星位置按信号发射时刻迭代（2 次），并含 Sagnac 改正。</p>
 *
 * <p>星历来源由 {@code ephopt} 决定：广播星历（默认）、SP3/CLK 精密星历、SSR 改正。</p>
 *
 * <p>GLONASS 需要频率号，本阶段不参与网解。</p>
 */
public final class DdObservationBuilder {

    /** 各系统优先信号对（观测码字符串）。 */
    private static final Map<Integer, List<String[]>> PREFERRED = Map.of(
            Constants.SYS_CMP, List.of(
                    new String[]{"2I", "6I"}, new String[]{"2I", "7I"},
                    new String[]{"1P", "5P"}, new String[]{"1P", "7D"}),
            Constants.SYS_GPS, List.of(
                    new String[]{"1C", "2W"}, new String[]{"1C", "2L"},
                    new String[]{"1W", "2W"}, new String[]{"1C", "5Q"}),
            Constants.SYS_GAL, List.of(
                    new String[]{"1C", "5Q"}, new String[]{"1C", "7Q"},
                    new String[]{"1X", "5X"}, new String[]{"1C", "8Q"}));

    /** 广播星历速度的差商步长（s）。 */
    private static final double VELOCITY_STEP_SEC = 1e-3;

    /**
     * 广播星历版本（等价于 {@code ephopt = EPHOPT_BRDC}）。
     *
     * @param ecefA        站 A 坐标（米）
     * @param ecefB        站 B 坐标（米）
     * @param obsA         站 A 本历元观测
     * @param obsB         站 B 本历元观测
     * @param nav          广播星历
     * @param minElevation 最低高度角（度）
     * @return 双差观测（不含参考星本身）；有效卫星不足时返回空列表
     */
    public List<DdObservation> build(double[] ecefA, double[] ecefB,
                                     List<Obsd> obsA, List<Obsd> obsB,
                                     Nav nav, double minElevation) {
        return build(ecefA, ecefB, obsA, obsB, nav, minElevation, Constants.EPHOPT_BRDC);
    }

    /**
     * @param ecefA        站 A 坐标（米）
     * @param ecefB        站 B 坐标（米）
     * @param obsA         站 A 本历元观测
     * @param obsB         站 B 本历元观测
     * @param nav          星历（{@code EPHOPT_PREC} 时需已装载 SP3/CLK，
     *                     {@code EPHOPT_SSRAPC/SSRCOM} 时需已装载 {@code nav.ssr}）
     * @param minElevation 最低高度角（度）
     * @param ephopt       星历选项：{@link Constants#EPHOPT_BRDC}（默认）、
     *                     {@link Constants#EPHOPT_PREC}、{@link Constants#EPHOPT_SSRAPC}、
     *                     {@link Constants#EPHOPT_SSRCOM}
     * @return 双差观测（不含参考星本身）；有效卫星不足时返回空列表
     */
    public List<DdObservation> build(double[] ecefA, double[] ecefB,
                                     List<Obsd> obsA, List<Obsd> obsB,
                                     Nav nav, double minElevation, int ephopt) {
        Map<Integer, Obsd> mapA = bySat(obsA);
        Map<Integer, Obsd> mapB = bySat(obsB);
        Map<Integer, Satellite> sats = new LinkedHashMap<>();
        Map<Integer, Obsd> obsMapA = new HashMap<>();
        Map<Integer, Obsd> obsMapB = new HashMap<>();
        Map<Integer, SignalPair> pairs = new HashMap<>();

        for (Map.Entry<Integer, Obsd> entry : mapA.entrySet()) {
            int sat = entry.getKey();
            Obsd b = mapB.get(sat);
            if (b == null) {
                continue;
            }
            int[] prn = new int[1];
            int sys = SatUtils.satsys(sat, prn);
            if (sys == Constants.SYS_GLO || sys == Constants.SYS_QZS || sys == Constants.SYS_SBS) {
                continue;
            }
            // BDS GEO 广播轨道精度差，作为参考星会在所有双差中引入公共偏差，直接剔除
            if (sys == Constants.SYS_CMP && (prn[0] <= 5 || prn[0] >= 59)) {
                continue;
            }
            SignalPair pair = select(sys, entry.getValue());
            SignalPair pairB = select(sys, b);
            if (pair == null || pairB == null
                    || pair.code1 != pairB.code1 || pair.code2 != pairB.code2) {
                continue;
            }
            SatState stateA = satPos(entry.getValue().time, nav, sat, ecefA, ephopt, entry.getValue());
            SatState stateB = satPos(b.time, nav, sat, ecefB, ephopt, b);
            if (stateA == null || stateB == null) {
                continue;
            }
            // 两站信号发射时刻不同（基线量级可达 0.1~1 ms），必须各用各的卫星位置，
            // 否则卫星位移会以逐星常量偏差形式进入双差，破坏窄巷固定
            double[] rsA = stateA.rs;
            double[] rsB = stateB.rs;
            double[] azelA = azel(entry.getValue().time, ecefA, rsA);
            double[] azelB = azel(b.time, ecefB, rsB);
            if (azelA == null || azelB == null) {
                continue;
            }
            double satEl = Math.min(Math.toDegrees(azelA[1]), Math.toDegrees(azelB[1]));
            if (satEl < minElevation) {
                continue;
            }
            sats.put(sat, new Satellite(sys, pair, rsA, rsB, azelA, azelB, satEl));
            obsMapA.put(sat, sanitize(entry.getValue(), pair));
            obsMapB.put(sat, sanitize(b, pair));
            pairs.put(sat, pair);
        }
        if (sats.size() < 2) {
            return List.of();
        }

        // 按「系统 + 频点对」分组选参考星：跨系统不能作双差，且同系统不同信号对的 MW/IF 也不能作差
        Map<GroupKey, List<Integer>> groups = new LinkedHashMap<>();
        for (Map.Entry<Integer, Satellite> e : sats.entrySet()) {
            Satellite s = e.getValue();
            groups.computeIfAbsent(new GroupKey(s.sys, s.pair.f1, s.pair.f2), k -> new ArrayList<>())
                    .add(e.getKey());
        }

        double[] posA = new double[3];
        double[] posB = new double[3];
        org.rtklib.java.coord.CoordTransform.ecef2pos(ecefA, posA);
        org.rtklib.java.coord.CoordTransform.ecef2pos(ecefB, posB);

        List<DdObservation> result = new ArrayList<>();
        for (List<Integer> group : groups.values()) {
            if (group.size() < 2) {
                continue;
            }
            int refSat = -1;
            double refEl = -Double.MAX_VALUE;
            for (int sat : group) {
                Satellite s = sats.get(sat);
                if (s.satElevationDeg > refEl) {
                    refEl = s.satElevationDeg;
                    refSat = sat;
                }
            }
            Satellite ref = sats.get(refSat);
            SignalPair refPair = pairs.get(refSat);
            Obsd refA = obsMapA.get(refSat);
            Obsd refB = obsMapB.get(refSat);
            double[] refRangeA = range(ref.rsA, ecefA);
            double[] refRangeB = range(ref.rsB, ecefB);
            double refTropoA = slantTropo(posA, ref.azelA[1]);
            double refTropoB = slantTropo(posB, ref.azelB[1]);
            double refPhaseIfA = ifPhase(refA, refPair);
            double refPhaseIfB = ifPhase(refB, refPair);
            double refCodeIfA = ifCode(refA, refPair);
            double refCodeIfB = ifCode(refB, refPair);
            double refGfA = gfPhase(refA, refPair);
            double refGfB = gfPhase(refB, refPair);
            double refMwA = mw(refA, refPair);
            double refMwB = mw(refB, refPair);
            double refMapA = map(ref.azelA[1]);
            double refMapB = map(ref.azelB[1]);

            for (int sat : group) {
                if (sat == refSat) {
                    continue;
                }
                Satellite s = sats.get(sat);
                Obsd satObsA = obsMapA.get(sat);
                Obsd satObsB = obsMapB.get(sat);
                SignalPair pair = pairs.get(sat);
                double[] rangeA = range(s.rsA, ecefA);
                double[] rangeB = range(s.rsB, ecefB);
                double ddRange = (rangeA[0] - refRangeA[0]) - (rangeB[0] - refRangeB[0]);
                double ddPhaseIf = (ifPhase(satObsA, pair) - refPhaseIfA) - (ifPhase(satObsB, pair) - refPhaseIfB);
                double ddCodeIf = (ifCode(satObsA, pair) - refCodeIfA) - (ifCode(satObsB, pair) - refCodeIfB);
                double ddGf = (gfPhase(satObsA, pair) - refGfA) - (gfPhase(satObsB, pair) - refGfB);
                double ddMw = (mw(satObsA, pair) - refMwA) - (mw(satObsB, pair) - refMwB);
                double ddModelTropo = (slantTropo(posA, s.azelA[1]) - refTropoA)
                        - (slantTropo(posB, s.azelB[1]) - refTropoB);
                double losX = rangeB[1] - refRangeB[1];
                double losY = rangeB[2] - refRangeB[2];
                double losZ = rangeB[3] - refRangeB[3];
                double mapDiffA = map(s.azelA[1]) - refMapA;
                double mapDiffB = map(s.azelB[1]) - refMapB;
                result.add(new DdObservation(sat, refSat, s.sys, pair.f1, pair.f2,
                        Constants.CLIGHT / (pair.f1 - pair.f2),
                        ddRange, ddPhaseIf, ddCodeIf, ddGf, ddMw,
                        ddModelTropo,
                        losX, losY, losZ, mapDiffA, mapDiffB,
                        Math.toDegrees(s.azelB[1]), refEl));
            }
        }
        return result;
    }

    /**
     * @return {@code [range, ex, ey, ez]}；视线单位向量由站指向卫星
     */
    private static double[] range(double[] rs, double[] rr) {
        double[] e = new double[3];
        double r = RtklibCommon.geodist(rs, rr, e);
        return new double[]{r, e[0], e[1], e[2]};
    }

    private static double[] azel(GTime time, double[] ecef, double[] rs) {
        double[] pos = new double[3];
        org.rtklib.java.coord.CoordTransform.ecef2pos(ecef, pos);
        double[] e = new double[3];
        double r = RtklibCommon.geodist(rs, ecef, e);
        if (r <= 0.0) {
            return null;
        }
        double[] azel = new double[2];
        RtklibCommon.satazel(pos, e, azel);
        return azel;
    }

    /**
     * 卫星位置：按信号发射时刻迭代 2 次。
     *
     * <p>{@code EPHOPT_BRDC} 走广播星历；{@code EPHOPT_PREC} 走 SP3/CLK 精密星历
     * （失败即剔除该星，不回退广播）；{@code EPHOPT_SSRAPC/SSRCOM} 在广播星历位置
     * 上叠加 {@code nav.ssr} 轨道改正，无改正数据的卫星保持广播值（短基线双差
     * 下米级轨道差可忽略，剔除反而削弱 LAMBDA 的模糊度支撑）。</p>
     */
    private static SatState satPos(GTime time, Nav nav, int sat, double[] approxRr,
                                   int ephopt, Obsd obs) {
        double[] rs = new double[6];
        double[] dts = new double[2];
        double[] vare = new double[1];
        double rho = 2.0e7;
        boolean precise = ephopt == Constants.EPHOPT_PREC;
        boolean ssr = ephopt == Constants.EPHOPT_SSRAPC || ephopt == Constants.EPHOPT_SSRCOM;
        for (int k = 0; k < 2; k++) {
            GTime transmit = plus(time, -rho / Constants.CLIGHT);
            if (precise) {
                if (!EphModel.satposPrec(transmit, sat, nav, rs, dts, vare)) {
                    return null;
                }
            } else {
                EphModel.satpos(transmit, nav, sat, rs, dts, vare);
                if (rs[0] == 0.0 && rs[1] == 0.0 && rs[2] == 0.0) {
                    return null;
                }
                if (ssr) {
                    // SSR 轨道改正以卫星 RAC 坐标系给出，需要速度构造沿迹/法向基；
                    // 广播星历 satpos 只填位置，此处补算速度（与 EPHOPT_PREC 的算法一致）
                    broadcastVelocity(nav, sat, transmit, rs);
                    applySsr(nav, rs, dts, obs);
                }
            }
            double[] e = new double[3];
            rho = RtklibCommon.geodist(rs, approxRr, e);
        }
        return new SatState(rs);
    }

    /**
     * 广播星历速度：前向差商（与 {@link EphModel#satposPrec} 的速度算法一致）。
     */
    private static void broadcastVelocity(Nav nav, int sat, GTime time, double[] rs) {
        double[] rs2 = new double[6];
        double[] dts2 = new double[2];
        double[] vare2 = new double[1];
        EphModel.satpos(plus(time, VELOCITY_STEP_SEC), nav, sat, rs2, dts2, vare2);
        for (int j = 0; j < 3; j++) {
            rs[3 + j] = (rs2[j] - rs[j]) / VELOCITY_STEP_SEC;
        }
    }

    /**
     * SSR 轨道/钟差改正：无改正数据时保持广播值不变。
     */
    private static void applySsr(Nav nav, double[] rs, double[] dts, Obsd obs) {
        if (nav.ssr == null || obs == null || !SsrCorrector.hasSsrData(nav.ssr, obs.sat)) {
            return;
        }
        Obsd[] obsArr = {obs};
        SsrCorrector.applyOrbitCorrection(nav.ssr, rs, obsArr, 1);
        SsrCorrector.applyClockCorrection(nav.ssr, dts, obsArr, 1);
    }

    private static GTime plus(GTime t, double dt) {
        double sec = t.sec + dt;
        long tt = t.time;
        while (sec < 0.0) {
            sec += 1.0;
            tt -= 1;
        }
        while (sec >= 1.0) {
            sec -= 1.0;
            tt += 1;
        }
        return new GTime(tt, sec);
    }

    private static Map<Integer, Obsd> bySat(List<Obsd> obsList) {
        Map<Integer, Obsd> map = new LinkedHashMap<>();
        for (Obsd obs : obsList) {
            if (obs != null && obs.sat > 0) {
                map.putIfAbsent(obs.sat, obs);
            }
        }
        return map;
    }

    private static SignalPair select(int sys, Obsd obs) {
        List<String[]> candidates = PREFERRED.get(sys);
        if (candidates == null) {
            return null;
        }
        for (String[] pair : candidates) {
            int c1 = ObsCode.obs2code(pair[0]);
            int c2 = ObsCode.obs2code(pair[1]);
            int i1 = index(obs, c1);
            int i2 = index(obs, c2);
            if (i1 < 0 || i2 < 0 || obs.P[i1] == 0.0 || obs.L[i1] == 0.0
                    || obs.P[i2] == 0.0 || obs.L[i2] == 0.0) {
                continue;
            }
            double f1 = ObsCode.code2freq(sys, c1, 0);
            double f2 = ObsCode.code2freq(sys, c2, 0);
            if (f1 > 0.0 && f2 > 0.0 && f1 != f2) {
                return new SignalPair(c1, c2, f1, f2);
            }
        }
        return null;
    }

    private static int index(Obsd obs, int code) {
        for (int j = 0; j < Constants.NFREQ; j++) {
            if (obs.code[j] == code) {
                return j;
            }
        }
        return -1;
    }

    /**
     * 复制一份只保留所用信号对的观测，便于后续按索引取值。
     */
    private static Obsd sanitize(Obsd src, SignalPair pair) {
        Obsd obs = new Obsd(src);
        for (int j = 0; j < Constants.NFREQ; j++) {
            if (obs.code[j] != pair.code1 && obs.code[j] != pair.code2) {
                obs.P[j] = 0.0;
                obs.L[j] = 0.0;
            }
        }
        return obs;
    }

    private static double[] values(Obsd obs, SignalPair pair) {
        int i1 = index(obs, pair.code1);
        int i2 = index(obs, pair.code2);
        return new double[]{obs.P[i1], obs.L[i1], obs.P[i2], obs.L[i2]};
    }

    private static double ifPhase(Obsd obs, SignalPair p) {
        double[] v = values(obs, p);
        double l1 = v[1] * Constants.CLIGHT / p.f1;
        double l2 = v[3] * Constants.CLIGHT / p.f2;
        return (p.f1 * p.f1 * l1 - p.f2 * p.f2 * l2) / (p.f1 * p.f1 - p.f2 * p.f2);
    }

    private static double ifCode(Obsd obs, SignalPair p) {
        double[] v = values(obs, p);
        return (p.f1 * p.f1 * v[0] - p.f2 * p.f2 * v[2]) / (p.f1 * p.f1 - p.f2 * p.f2);
    }

    private static double gfPhase(Obsd obs, SignalPair p) {
        double[] v = values(obs, p);
        return v[1] * Constants.CLIGHT / p.f1 - v[3] * Constants.CLIGHT / p.f2;
    }

    /**
     * Melbourne-Wübbena 宽巷浮点模糊度（周）。
     *
     * <p>{@code N_WL = (L1 - L2) - (f1*P1 + f2*P2)/((f1+f2)*lambda_WL)}，
     * 相位以周计、码以米计，几何与电离层一阶项抵消。</p>
     */
    private static double mw(Obsd obs, SignalPair p) {
        double[] v = values(obs, p);
        double wlPhaseCycles = v[1] - v[3];
        double nlCode = (p.f1 * v[0] + p.f2 * v[2]) / (p.f1 + p.f2);
        return wlPhaseCycles - nlCode / (Constants.CLIGHT / (p.f1 - p.f2));
    }

    private static double map(double elevationRad) {
        double s = Math.sin(elevationRad);
        return s > 1e-3 ? 1.0 / s : 0.0;
    }

    /** 对流层模型气象参数（与 ModelCorrectionProvider 保持一致）。 */
    private static final double REL_HUMI = 0.7;
    private static final double TEMP_KELVIN = 293.15;

    /**
     * Saastamoinen 斜延迟（与 {@code ModelCorrectionProvider} 同一模型参数）。
     */
    private static double slantTropo(double[] pos, double elevationRad) {
        return TroposphereModel.saastamoinen(pos, new double[]{0.0, elevationRad},
                REL_HUMI, TEMP_KELVIN);
    }

    private static final class SignalPair {
        private final int code1;
        private final int code2;
        private final double f1;
        private final double f2;

        private SignalPair(int code1, int code2, double f1, double f2) {
            this.code1 = code1;
            this.code2 = code2;
            this.f1 = f1;
            this.f2 = f2;
        }
    }

    private static final class Satellite {
        private final int sys;
        private final SignalPair pair;
        private final double[] rsA;
        private final double[] rsB;
        private final double[] azelA;
        private final double[] azelB;
        private final double satElevationDeg;

        private Satellite(int sys, SignalPair pair, double[] rsA, double[] rsB,
                          double[] azelA, double[] azelB, double satElevationDeg) {
            this.sys = sys;
            this.pair = pair;
            this.rsA = rsA;
            this.rsB = rsB;
            this.azelA = azelA;
            this.azelB = azelB;
            this.satElevationDeg = satElevationDeg;
        }
    }

    /** 卫星位置（发射时刻几何位置）。 */
    private record SatState(double[] rs) {
    }

    /** 双差分组键：同一系统、同一频点对的卫星才能互相作差。 */
    private record GroupKey(int sys, double f1, double f2) {
    }
}