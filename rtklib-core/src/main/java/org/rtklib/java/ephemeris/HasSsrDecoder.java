package org.rtklib.java.ephemeris;

import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.time.TimeSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Galileo HAS (High Accuracy Service) 解码器。
 *
 * <p>解码Galileo HAS COMPACT-SSR消息，将改正数映射到Nav.ssr结构，
 * 复用现有SSR改正链（SsrCorrector、SsrIono）。</p>
 *
 * <p>HAS消息类型：
 * <ul>
 *   <li>MT1: HAS Header - 包含TOI、mask ID、 validity interval</li>
 *   <li>MT2: Satellite Mask - 定义参与的卫星</li>
 *   <li>MT3: Signal Mask - 定义参与的信号</li>
 *   <li>MT4: Orbit Correction - 轨道改正（径向/切向/法向）</li>
 *   <li>MT5: Clock Full Correction - 完整钟差改正</li>
 *   <li>MT6: Clock Delta Correction - 增量钟差改正</li>
 *   <li>MT7: Code Bias - 码偏差</li>
 *   <li>MT8: Phase Bias - 相位偏差</li>
 *   <li>MT9: URA - 用户测距精度</li>
 * </ul>
 *
 * <p>参考：Galileo OS SIS ICD, Issue 4.0 (2023)</p>
 */
public class HasSsrDecoder {
    private static final Logger LOG = LoggerFactory.getLogger(HasSsrDecoder.class);

    private static final int HAS_MT_HEADER = 1;
    private static final int HAS_MT_SAT_MASK = 2;
    private static final int HAS_MT_SIG_MASK = 3;
    private static final int HAS_MT_ORBIT = 4;
    private static final int HAS_MT_CLK_FULL = 5;
    private static final int HAS_MT_CLK_DELTA = 6;
    private static final int HAS_MT_CODE_BIAS = 7;
    private static final int HAS_MT_PHASE_BIAS = 8;
    private static final int HAS_MT_URA = 9;

    private static final int MAX_HAS_SAT = 64;
    private static final int MAX_HAS_SIG = 32;

    private int hasToid;
    private int hasMaskId;
    private GTime hasTime = new GTime();
    private int hasNsat;
    private int hasNsig;
    private int[] hasSatList = new int[MAX_HAS_SAT];
    private int[] hasSigList = new int[MAX_HAS_SIG];
    private boolean[] hasSatMask = new boolean[MAX_HAS_SAT];
    private boolean[] hasSigMask = new boolean[MAX_HAS_SIG];

    private Nav nav;

    public HasSsrDecoder(Nav nav) {
        this.nav = nav;
    }

    /**
     * 解码HAS COMPACT-SSR消息。
     *
     * @param buff 消息缓冲区（bit流）
     * @param len  消息长度（bits）
     * @return 解码成功返回true
     */
    public boolean decode(int[] buff, int len) {
        if (buff == null || len < 24) return false;

        try {
            int i = 0;
            int mt = getbitu(buff, i, 4); i += 4;

            switch (mt) {
                case HAS_MT_HEADER:
                    return decodeHeader(buff, i);
                case HAS_MT_SAT_MASK:
                    return decodeSatMask(buff, i);
                case HAS_MT_SIG_MASK:
                    return decodeSigMask(buff, i);
                case HAS_MT_ORBIT:
                    return decodeOrbit(buff, i);
                case HAS_MT_CLK_FULL:
                    return decodeClockFull(buff, i);
                case HAS_MT_CLK_DELTA:
                    return decodeClockDelta(buff, i);
                case HAS_MT_CODE_BIAS:
                    return decodeCodeBias(buff, i);
                case HAS_MT_PHASE_BIAS:
                    return decodePhaseBias(buff, i);
                case HAS_MT_URA:
                    return decodeUra(buff, i);
                default:
                    LOG.debug("HAS: unknown MT={}", mt);
                    return false;
            }
        } catch (Exception e) {
            LOG.error("HAS decode error: {}", e.getMessage());
            return false;
        }
    }

    private boolean decodeHeader(int[] buff, int i) {
        hasToid = getbitu(buff, i, 4); i += 4;
        hasMaskId = getbitu(buff, i, 5); i += 5;
        int gpsTow = getbitu(buff, i, 20); i += 20;
        int gpsWn = getbitu(buff, i, 10); i += 10;

        hasTime = TimeSystem.gpst2time(gpsWn, gpsTow * 1.0);

        LOG.debug("HAS Header: TOID={} maskId={} tow={} wn={}", hasToid, hasMaskId, gpsTow, gpsWn);
        return true;
    }

    private boolean decodeSatMask(int[] buff, int i) {
        hasNsat = 0;
        for (int j = 0; j < MAX_HAS_SAT; j++) {
            hasSatMask[j] = false;
        }

        int nmask = getbitu(buff, i, 6); i += 6;
        for (int j = 0; j < nmask; j++) {
            int gnssId = getbitu(buff, i, 4); i += 4;
            int satMaskLen = getbitu(buff, i, 4); i += 4;

            int sys = gnssIdToSys(gnssId);
            for (int k = 0; k < satMaskLen && k < 64; k++) {
                int included = getbitu(buff, i, 1); i += 1;
                if (included != 0) {
                    int prn = k + 1;
                    int sat = SatUtils.satno(sys, prn);
                    if (sat > 0 && sat <= Constants.MAXSAT && hasNsat < MAX_HAS_SAT) {
                        hasSatList[hasNsat] = sat;
                        hasSatMask[hasNsat] = true;
                        hasNsat++;
                    }
                }
            }
        }

        LOG.debug("HAS SatMask: nsat={}", hasNsat);
        return true;
    }

    private boolean decodeSigMask(int[] buff, int i) {
        hasNsig = 0;
        for (int j = 0; j < MAX_HAS_SIG; j++) {
            hasSigMask[j] = false;
        }

        int nsigTotal = getbitu(buff, i, 5); i += 5;
        for (int j = 0; j < nsigTotal && hasNsig < MAX_HAS_SIG; j++) {
            int sigCode = getbitu(buff, i, 5); i += 5;
            if (sigCode > 0 && sigCode < Constants.MAXCODE) {
                hasSigList[hasNsig] = sigCode;
                hasSigMask[hasNsig] = true;
                hasNsig++;
            }
        }

        LOG.debug("HAS SigMask: nsig={}", hasNsig);
        return true;
    }

    private boolean decodeOrbit(int[] buff, int i) {
        if (nav.ssr == null) return false;

        int iodSet = getbitu(buff, i, 5); i += 5;

        for (int s = 0; s < hasNsat; s++) {
            if (!hasSatMask[s]) continue;
            int sat = hasSatList[s];
            if (sat <= 0 || sat > Constants.MAXSAT) continue;

            int iode = getbitu(buff, i, 8); i += 8;
            int dr = getbits(buff, i, 13); i += 13;
            int da = getbits(buff, i, 13); i += 13;
            int dc = getbits(buff, i, 13); i += 13;
            int ddr = getbits(buff, i, 10); i += 10;
            int dda = getbits(buff, i, 10); i += 10;
            int ddc = getbits(buff, i, 10); i += 10;

            Ssr ssr = getOrCreateSsr(sat);
            ssr.t0[0] = new GTime(hasTime);
            ssr.iod[0] = iodSet;
            ssr.iode = iode;
            ssr.deph[0] = dr * 0.0025;
            ssr.deph[1] = da * 0.0025;
            ssr.deph[2] = dc * 0.0025;
            ssr.ddeph[0] = ddr * 0.00025;
            ssr.ddeph[1] = dda * 0.00025;
            ssr.ddeph[2] = ddc * 0.00025;
            ssr.update = 1;
        }

        LOG.debug("HAS Orbit: iodSet={}", iodSet);
        return true;
    }

    private boolean decodeClockFull(int[] buff, int i) {
        if (nav.ssr == null) return false;

        int iodSet = getbitu(buff, i, 5); i += 5;

        for (int s = 0; s < hasNsat; s++) {
            if (!hasSatMask[s]) continue;
            int sat = hasSatList[s];
            if (sat <= 0 || sat > Constants.MAXSAT) continue;

            int dclk0 = getbits(buff, i, 15); i += 15;
            int dclk1 = getbits(buff, i, 12); i += 12;
            int dclk2 = getbits(buff, i, 10); i += 10;

            Ssr ssr = getOrCreateSsr(sat);
            ssr.t0[1] = new GTime(hasTime);
            ssr.iod[1] = iodSet;
            ssr.dclk[0] = dclk0 * 0.0025;
            ssr.dclk[1] = dclk1 * 0.0025e-3;
            ssr.dclk[2] = dclk2 * 0.0025e-6;
            ssr.update = 1;
        }

        LOG.debug("HAS ClockFull: iodSet={}", iodSet);
        return true;
    }

    private boolean decodeClockDelta(int[] buff, int i) {
        if (nav.ssr == null) return false;

        int iodSet = getbitu(buff, i, 5); i += 5;

        for (int s = 0; s < hasNsat; s++) {
            if (!hasSatMask[s]) continue;
            int sat = hasSatList[s];
            if (sat <= 0 || sat > Constants.MAXSAT) continue;

            int ddclk = getbits(buff, i, 11); i += 11;

            Ssr ssr = getOrCreateSsr(sat);
            if (ssr.t0[1] != null && ssr.t0[1].time > 0) {
                ssr.dclk[0] += ddclk * 0.0025;
            }
            ssr.update = 1;
        }

        LOG.debug("HAS ClockDelta: iodSet={}", iodSet);
        return true;
    }

    private boolean decodeCodeBias(int[] buff, int i) {
        if (nav.ssr == null) return false;

        int iodSet = getbitu(buff, i, 5); i += 5;

        for (int s = 0; s < hasNsat; s++) {
            if (!hasSatMask[s]) continue;
            int sat = hasSatList[s];
            if (sat <= 0 || sat > Constants.MAXSAT) continue;

            Ssr ssr = getOrCreateSsr(sat);
            ssr.t0[4] = new GTime(hasTime);
            ssr.iod[4] = iodSet;

            for (int sig = 0; sig < hasNsig; sig++) {
                if (!hasSigMask[sig]) continue;
                int code = hasSigList[sig];
                if (code <= 0 || code >= Constants.MAXCODE) continue;

                int cb = getbits(buff, i, 11); i += 11;
                ssr.cbias[code - 1] = (float) (cb * 0.02);
            }
            ssr.update = 1;
        }

        LOG.debug("HAS CodeBias: iodSet={}", iodSet);
        return true;
    }

    private boolean decodePhaseBias(int[] buff, int i) {
        if (nav.ssr == null) return false;

        int iodSet = getbitu(buff, i, 5); i += 5;

        for (int s = 0; s < hasNsat; s++) {
            if (!hasSatMask[s]) continue;
            int sat = hasSatList[s];
            if (sat <= 0 || sat > Constants.MAXSAT) continue;

            Ssr ssr = getOrCreateSsr(sat);
            ssr.t0[5] = new GTime(hasTime);
            ssr.iod[5] = iodSet;

            for (int sig = 0; sig < hasNsig; sig++) {
                if (!hasSigMask[sig]) continue;
                int code = hasSigList[sig];
                if (code <= 0 || code >= Constants.MAXCODE) continue;

                int di = getbitu(buff, i, 3); i += 3;
                int pb = getbits(buff, i, 15); i += 15;

                ssr.pbias[code - 1] = pb * 0.001;
                ssr.dispInd[code - 1] = di;
                if (di != 0) {
                    ssr.dispBias[code - 1] = ssr.pbias[code - 1];
                }
            }
            ssr.update = 1;
        }

        LOG.debug("HAS PhaseBias: iodSet={}", iodSet);
        return true;
    }

    private boolean decodeUra(int[] buff, int i) {
        if (nav.ssr == null) return false;

        int iodSet = getbitu(buff, i, 5); i += 5;

        for (int s = 0; s < hasNsat; s++) {
            if (!hasSatMask[s]) continue;
            int sat = hasSatList[s];
            if (sat <= 0 || sat > Constants.MAXSAT) continue;

            int uraClass = getbitu(buff, i, 3); i += 3;
            int uraVal = getbitu(buff, i, 4); i += 4;

            Ssr ssr = getOrCreateSsr(sat);
            ssr.ura = (uraClass << 4) | uraVal;
            ssr.update = 1;
        }

        LOG.debug("HAS URA: iodSet={}", iodSet);
        return true;
    }

    private Ssr getOrCreateSsr(int sat) {
        if (nav.ssr == null) {
            nav.ssr = new Ssr[Constants.MAXSAT];
        }
        if (nav.ssr[sat - 1] == null) {
            nav.ssr[sat - 1] = new Ssr();
        }
        return nav.ssr[sat - 1];
    }

    private int gnssIdToSys(int gnssId) {
        switch (gnssId) {
            case 0: return Constants.SYS_GPS;
            case 1: return Constants.SYS_GAL;
            case 2: return Constants.SYS_QZS;
            case 3: return Constants.SYS_CMP;
            default: return Constants.SYS_GPS;
        }
    }

    private static int getbitu(int[] buff, int pos, int len) {
        int val = 0;
        for (int j = 0; j < len; j++) {
            int bitPos = pos + j;
            int byteIdx = bitPos / 8;
            int bitIdx = 7 - (bitPos % 8);
            if (byteIdx < buff.length) {
                val = (val << 1) | ((buff[byteIdx] >> bitIdx) & 1);
            }
        }
        return val;
    }

    private static int getbits(int[] buff, int pos, int len) {
        int val = getbitu(buff, pos, len);
        if ((val & (1 << (len - 1))) != 0) {
            val -= (1 << len);
        }
        return val;
    }
}
