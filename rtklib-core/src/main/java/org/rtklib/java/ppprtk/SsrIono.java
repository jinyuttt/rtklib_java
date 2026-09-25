package org.rtklib.java.ppprtk;

import org.rtklib.java.common.ObsCode;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.GTime;
import org.rtklib.java.data.Nav;
import org.rtklib.java.data.Ssr;
import org.rtklib.java.ionosphere.IonosphereModel;
import org.rtklib.java.time.TimeSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class SsrIono {
    private SsrIono() {
    }

    private static final Logger LOG = LoggerFactory.getLogger(SsrIono.class);

    private static final double C2_40_3 = 40.3e16;
    private static final double MAX_SSR_AGE = 600.0;
    private static final double VAR_SSR_IONO_MIN = 0.01;
    private static final double VAR_SSR_IONO_AGE_RATE = 1e-4;

    public static double ssrIonoDelay(GTime time, Nav nav, int sat,
                                       double[] pos, double[] azel,
                                       int freq, double wavelength) {
        double freqHz = Constants.CLIGHT / wavelength;
        if (freqHz == 0.0) return 0.0;
        return C2_40_3 / (freqHz * freqHz) * stecModel(time, nav, sat, pos, azel);
    }

    public static double stecModel(GTime time, Nav nav, int sat,
                                    double[] pos, double[] azel) {
        if (nav.ssr != null && sat >= 1 && sat <= Constants.MAXSAT) {
            Ssr ssr = nav.ssr[sat - 1];
            if (ssr != null && ssr.update != 0) {
                double stecSsr = extractSsrStec(ssr, sat, time, nav);
                if (stecSsr != 0.0) {
                    return stecSsr;
                }
            }
        }

        double[] ionOut = new double[2];
        IonosphereModel.ionocorr(time, nav, sat, pos, azel, Constants.IONOOPT_BRDC, ionOut);
        return ionOut[0];
    }

    /**
     * 从SSR改正数提取STEC。
     *
     * 优先级：
     * 1. dispBias直接提供的色散偏差（CLAS/HAS产品）→ 几何无频组合求STEC
     * 2. 仅有dispInd标记时，无法直接提取STEC → 返回0（退化为广播模型）
     */
    private static double extractSsrStec(Ssr ssr, int sat, GTime time, Nav nav) {
        if (ssr.t0[5] != null) {
            double age = Math.abs(TimeSystem.timediff(time, ssr.t0[5]));
            if (age > MAX_SSR_AGE) return 0.0;
        }

        int sys = SatUtils.satsys(sat, null);
        double stec = extractStecFromDispBias(ssr, sat, sys, nav);
        if (stec != 0.0) return stec;

        return 0.0;
    }

    /**
     * 从色散偏差(dispBias)提取STEC。
     *
     * 原理：当SSR产品（CLAS/HAS）提供两个频率的色散偏差时，
     * 几何无频组合 GF_disp = dispBias_f1 - dispBias_f2
     * 与STEC的关系：GF_disp = 40.3e16 * STEC * (1/f1^2 - 1/f2^2)
     * 因此：STEC = GF_disp / (40.3e16 * (1/f1^2 - 1/f2^2))
     */
    private static double extractStecFromDispBias(Ssr ssr, int sat, int sys, Nav nav) {
        int[] dispCodes = new int[2];
        double[] dispFreqs = new double[2];
        int nDisp = 0;

        int[] testCodes = getRepresentativeCodes(sys, sat, nav);

        for (int i = 0; i < testCodes.length && nDisp < 2; i++) {
            int code = testCodes[i];
            if (code <= 0 || code >= Constants.MAXCODE) continue;
            if (ssr.dispInd[code - 1] == 0) continue;

            double db = ssr.dispBias[code - 1];
            if (db == 0.0) continue;

            double freq = ObsCode.code2freq(sys, code, 0);
            if (freq <= 0.0) continue;

            boolean isDuplicate = false;
            for (int j = 0; j < nDisp; j++) {
                if (Math.abs(dispFreqs[j] - freq) < 1.0) {
                    isDuplicate = true;
                    break;
                }
            }
            if (isDuplicate) continue;

            dispCodes[nDisp] = code;
            dispFreqs[nDisp] = freq;
            nDisp++;
        }

        if (nDisp < 2) return 0.0;

        double f1 = dispFreqs[0];
        double f2 = dispFreqs[1];
        double mu = C2_40_3 * (1.0 / (f1 * f1) - 1.0 / (f2 * f2));
        if (Math.abs(mu) < 1e-10) return 0.0;

        double gfDisp = ssr.dispBias[dispCodes[0] - 1] - ssr.dispBias[dispCodes[1] - 1];
        double stec = gfDisp / mu;

        if (stec < 0.0 || stec > 300.0) return 0.0;

        return stec;
    }

    /**
     * 获取各系统的代表性观测码（用于查找dispBias）。
     */
    private static int[] getRepresentativeCodes(int sys, int sat, Nav nav) {
        if ((sys & Constants.SYS_GPS) != 0) {
            return new int[]{
                Constants.CODE_L1C, Constants.CODE_L2W, Constants.CODE_L2X,
                Constants.CODE_L5Q, Constants.CODE_L1X, Constants.CODE_L2L
            };
        }
        if ((sys & Constants.SYS_GAL) != 0) {
            return new int[]{
                Constants.CODE_L1C, Constants.CODE_L5Q,
                Constants.CODE_L7Q, Constants.CODE_L8Q,
                Constants.CODE_L1X, Constants.CODE_L5X
            };
        }
        if ((sys & Constants.SYS_CMP) != 0) {
            return new int[]{
                Constants.CODE_L2I, Constants.CODE_L7I,
                Constants.CODE_L6I, Constants.CODE_L1X,
                Constants.CODE_L5X, Constants.CODE_L7X
            };
        }
        if ((sys & Constants.SYS_GLO) != 0) {
            return new int[]{
                Constants.CODE_L1C, Constants.CODE_L2C,
                Constants.CODE_L1X, Constants.CODE_L2X
            };
        }
        return new int[0];
    }

    /**
     * SSR电离层方差。基于数据龄号和色散偏差可用性。
     */
    public static double ssrIonoVar(Ssr ssr, GTime time) {
        if (ssr == null || ssr.t0[5] == null) return VAR_SSR_IONO_MIN;

        double age = Math.abs(TimeSystem.timediff(time, ssr.t0[5]));
        if (age > MAX_SSR_AGE) return 1e6;

        double var = VAR_SSR_IONO_MIN + VAR_SSR_IONO_AGE_RATE * age * age;

        boolean hasDispBias = false;
        for (int i = 0; i < ssr.dispBias.length; i++) {
            if (ssr.dispBias[i] != 0.0) {
                hasDispBias = true;
                break;
            }
        }
        if (!hasDispBias) {
            var *= 10.0;
        }

        return var;
    }

    public static boolean hasSsrIono(Ssr[] ssr, int sat) {
        if (ssr == null || sat < 1 || sat > Constants.MAXSAT) return false;
        Ssr s = ssr[sat - 1];
        if (s == null || s.update == 0) return false;

        for (int i = 0; i < s.dispBias.length; i++) {
            if (s.dispBias[i] != 0.0) return true;
        }
        for (int i = 0; i < s.dispInd.length; i++) {
            if (s.dispInd[i] != 0) return true;
        }
        return false;
    }

    public static double ssrIonoMapFunc(double[] pos, double[] azel) {
        return IonosphereModel.ionmapf(pos, azel);
    }
}
