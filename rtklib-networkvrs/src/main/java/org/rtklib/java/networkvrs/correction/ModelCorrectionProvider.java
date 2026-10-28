package org.rtklib.java.networkvrs.correction;

import org.rtklib.java.common.ObsCode;
import org.rtklib.java.common.RtklibCommon;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.Eph;
import org.rtklib.java.data.Nav;
import org.rtklib.java.data.Obsd;
import org.rtklib.java.ephemeris.EphModel;
import org.rtklib.java.networkvrs.NetworkVrsProcessor;
import org.rtklib.java.networkvrs.sync.StationObservation;
import org.rtklib.java.troposphere.TroposphereModel;

import java.util.HashMap;
import java.util.Map;

/**
 * 档 1 改正量提供者：观测直接估计的色散项 + 模型计算的非色散项。
 *
 * <ul>
 *   <li>电离层：双频码几何无关组合 {@code P1-P2}（固定 B1I/B3I），用载波相位变化做 Hatch 平滑；
 *       输出为第一频点的斜延迟（米）。载波相位缺失时退化为单历元码组合。</li>
 *   <li>对流层：Saastamoinen 模型，按卫星高度角映射为斜延迟（米），
 *       温湿度取固定值（293.15 K / 70%）。</li>
 *   <li>轨道残差：暂记为 0，档 2 网解分离后再替换。</li>
 * </ul>
 *
 * <p>限制：GLONASS 需要频率号（未接入）直接返回 null；平滑与高度角都依赖本站钟面时间，
 * 跨历元调用需按时间升序（{@link NetworkVrsProcessor} 已保证）。</p>
 */
public class ModelCorrectionProvider implements NetworkVrsProcessor.CorrectionProvider {

    /** 平滑窗口上限（历元数），15 s 采样约 25 min。 */
    private static final int MAX_SMOOTH = 100;
    /** 载波 GF 跳变阈值（米），超过视为周跳并重置平滑。 */
    private static final double SLIP_THRESHOLD = 0.15;
    /** 相邻历元最大间隔（秒），超过视为中断。 */
    private static final double MAX_GAP = 60.0;
    private static final double REL_HUMI = 0.7;
    private static final double TEMP_KELVIN = 293.15;
    /** 参与网络改正的最低卫星高度角（度）。 */
    private static final double MIN_ELEVATION_DEG = 20.0;
    private static final double MIN_ELEVATION_RAD = Math.toRadians(MIN_ELEVATION_DEG);
    /** 固定 B1I/B3I 频点对，避免各站各星自动选频导致频间码偏差不一致。 */
    private static final int CODE_B1I = ObsCode.obs2code("2I");
    private static final int CODE_B3I = ObsCode.obs2code("6I");
    /** BDS 首选码频点对（与网解的双差构造保持一致），按优先级尝试。 */
    private static final int[][] CMP_PAIRS = new int[][]{
            {CODE_B1I, CODE_B3I},
            {CODE_B1I, ObsCode.obs2code("7I")},
            {ObsCode.obs2code("1P"), ObsCode.obs2code("5P")}};
    private static final int[][] GPS_PAIRS = new int[][]{
            {ObsCode.obs2code("1C"), ObsCode.obs2code("2W")},
            {ObsCode.obs2code("1C"), ObsCode.obs2code("2L")},
            {ObsCode.obs2code("1W"), ObsCode.obs2code("2W")},
            {ObsCode.obs2code("1C"), ObsCode.obs2code("5Q")}};
    private static final int[][] GAL_PAIRS = new int[][]{
            {ObsCode.obs2code("1C"), ObsCode.obs2code("5Q")},
            {ObsCode.obs2code("1C"), ObsCode.obs2code("7Q")},
            {ObsCode.obs2code("1X"), ObsCode.obs2code("5X")},
            {ObsCode.obs2code("1C"), ObsCode.obs2code("8Q")}};

    private static int[][] preferredPairs(int sys) {
        if (sys == Constants.SYS_CMP) {
            return CMP_PAIRS;
        }
        if (sys == Constants.SYS_GPS) {
            return GPS_PAIRS;
        }
        if (sys == Constants.SYS_GAL) {
            return GAL_PAIRS;
        }
        return null;
    }

    private final Map<String, Nav> navs = new HashMap<>();
    private final Map<String, SmoothState> states = new HashMap<>();

    @Override
    public NetworkVrsProcessor.CorrectionValue compute(StationObservation station, Obsd obs) {
        if (station == null || obs == null || obs.sat == 0) {
            return null;
        }
        int[] prn = new int[1];
        int sys = SatUtils.satsys(obs.sat, prn);
        if (sys == Constants.SYS_GLO || sys == Constants.SYS_QZS || sys == Constants.SYS_SBS) {
            return null;
        }

        double[] azel = satelliteAzEl(station, obs);
        if (azel == null || azel[1] < MIN_ELEVATION_RAD) {
            return null;
        }
        double iono = slantIono(station, obs, sys);
        if (Double.isNaN(iono)) {
            return null;
        }
        double tropo = TroposphereModel.saastamoinen(station.pos(), azel, REL_HUMI, TEMP_KELVIN);
        if (Double.isNaN(tropo)) {
            return null;
        }
        return new NetworkVrsProcessor.CorrectionValue(iono, tropo, 0.0);
    }

    /**
     * 双频码几何无关组合，Hatch 平滑。
     *
     * @return 第一频点的斜电离层延迟（米）；不可用时返回 NaN
     */
    private double slantIono(StationObservation station, Obsd obs, int sys) {
        int[][] pairs = preferredPairs(sys);
        if (pairs == null) {
            return Double.NaN;
        }
        int idx1 = -1;
        int idx2 = -1;
        for (int[] pair : pairs) {
            int a = slotOf(obs, pair[0]);
            int b = slotOf(obs, pair[1]);
            if (a >= 0 && b >= 0) {
                idx1 = a;
                idx2 = b;
                break;
            }
        }
        if (idx1 < 0 || idx2 < 0) {
            return Double.NaN;
        }
        double f1 = ObsCode.code2freq(sys, obs.code[idx1], 0);
        double f2 = ObsCode.code2freq(sys, obs.code[idx2], 0);
        if (f1 <= 0.0 || f2 <= 0.0) {
            return Double.NaN;
        }
        double[] freq = {f1, f2};
        double[] code = {obs.P[idx1], obs.P[idx2]};
        double[] phase = new double[2];
        boolean hasPhase = obs.L[idx1] != 0.0 && obs.L[idx2] != 0.0;
        if (hasPhase) {
            phase[0] = obs.L[idx1] * Constants.CLIGHT / f1;
            phase[1] = obs.L[idx2] * Constants.CLIGHT / f2;
        }

        double gfCode = code[0] - code[1];
        double ionoScale = freq[1] * freq[1] / (freq[1] * freq[1] - freq[0] * freq[0]);
        if (!hasPhase) {
            return gfCode * ionoScale;
        }

        String key = station.id() + "/" + obs.sat;
        double gfPhase = phase[0] - phase[1];
        long timeKey = Math.round((obs.time.time + obs.time.sec) * 1000.0);
        SmoothState state = states.get(key);
        double smoothed;
        if (state != null
                && (timeKey - state.timeKey) / 1000.0 <= MAX_GAP
                && Math.abs(freq[0] - state.freq1) < 1.0
                && Math.abs(freq[1] - state.freq2) < 1.0
                && Math.abs(gfPhase - state.gfPhase) <= SLIP_THRESHOLD) {
            state.n = Math.min(state.n + 1, MAX_SMOOTH);
            smoothed = (gfCode + (state.n - 1) * (state.smoothed + (gfPhase - state.gfPhase))) / state.n;
        } else {
            state = new SmoothState();
            state.n = 1;
            smoothed = gfCode;
        }
        state.timeKey = timeKey;
        state.gfPhase = gfPhase;
        state.smoothed = smoothed;
        state.freq1 = freq[0];
        state.freq2 = freq[1];
        states.put(key, state);

        return smoothed * ionoScale;
    }

    /**
     * @return 观测中该码对应的信号槽位；不存在或码观测缺失时返回 -1
     */
    private static int slotOf(Obsd obs, int code) {
        for (int j = 0; j < Constants.NFREQ; j++) {
            if (obs.code[j] == code && obs.P[j] != 0.0) {
                return j;
            }
        }
        return -1;
    }

    /**
     * 由广播星历计算卫星方位高度角。
     *
     * @return [方位角, 高度角]（弧度）；星历缺失或位置无效时返回 null
     */
    private double[] satelliteAzEl(StationObservation station, Obsd obs) {
        Nav nav = navs.computeIfAbsent(station.id(), id -> buildNav(station));
        double[] rs = new double[6];
        double[] dts = new double[2];
        double[] vare = new double[1];
        EphModel.satpos(obs.time, nav, obs.sat, rs, dts, vare);
        if (rs[0] == 0.0 && rs[1] == 0.0 && rs[2] == 0.0) {
            return null;
        }
        double[] ecef = station.ecef();
        double[] e = {rs[0] - ecef[0], rs[1] - ecef[1], rs[2] - ecef[2]};
        double range = Math.sqrt(e[0] * e[0] + e[1] * e[1] + e[2] * e[2]);
        if (range <= 0.0) {
            return null;
        }
        e[0] /= range;
        e[1] /= range;
        e[2] /= range;
        double[] azel = new double[2];
        RtklibCommon.satazel(station.pos(), e, azel);
        return azel;
    }

    private static Nav buildNav(StationObservation station) {
        Nav nav = new Nav();
        for (Eph eph : station.ephemerides()) {
            if (eph.sat > 0 && eph.sat - 1 < nav.eph.length) {
                nav.eph[eph.sat - 1] = new Eph(eph);
            }
        }
        return nav;
    }

    /**
     * 单站单星载波平滑状态。
     */
    private static final class SmoothState {
        private long timeKey;
        private double gfPhase;
        private double smoothed;
        private double freq1;
        private double freq2;
        private int n;
    }
}
