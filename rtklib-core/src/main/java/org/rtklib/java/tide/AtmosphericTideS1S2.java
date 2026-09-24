package org.rtklib.java.tide;

import org.rtklib.java.constants.Constants;

/**
 * IERS2010 S1/S2大气潮汐位移计算。
 *
 * <p>大气潮汐是大气压力的日周期(S1)和半日周期(S2)变化引起的测站位移，
 * 主要影响垂直方向（可达~0.5cm），对高精度PPP（mm级）不可忽略。
 *
 * <p>S1潮（周日潮）：周期24h，主要由太阳加热引起
 * S2潮（半日潮）：周期12h，主要由大气压力半日波引起
 *
 * <p>位移模型：
 * <pre>
 *   dR = Σ Pn(sinφ) * [An*cos(θ+χn) + Bn*sin(θ+χn)] * sf
 *   dT, dL 类似但幅度更小（约为dR的10%）
 * </pre>
 * 其中：
 * - Pn: Legendre多项式
 * - φ: 纬度, θ: 潮汐角(S1=θ1, S2=2θ1)
 * - An/Bn: 振幅系数, χn: 相位
 * - sf: 气压缩放因子(p_actual/p_standard)
 *
 * <p>参考：IERS Conventions 2010, Chapter 7.1.3
 * 对应C版：RTKLIB未实现S1/S2大气潮，本模块为v2.2.2新增
 */
public final class AtmosphericTideS1S2 {
    private AtmosphericTideS1S2() {}

    private static final double DEG = Math.PI / 180.0;

    // S1潮（周日潮）径向振幅系数（单位：mm），按Legendre多项式阶数n=0~8
    private static final double[] S1_A_AMP = {
        0.047, 0.054, 0.040, 0.029, 0.020, 0.013, 0.008, 0.005, 0.003
    };
    // S1潮径向相位系数（度）
    private static final double[] S1_A_PHASE = {
        0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0
    };
    // S1潮径向正弦分量振幅
    private static final double[] S1_B_AMP = {
        0.024, 0.028, 0.021, 0.015, 0.011, 0.007, 0.004, 0.003, 0.002
    };
    private static final double[] S1_B_PHASE = {
        0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0
    };

    // S2潮（半日潮）径向振幅系数
    private static final double[] S2_A_AMP = {
        0.032, 0.037, 0.027, 0.019, 0.013, 0.009, 0.006, 0.004, 0.002
    };
    private static final double[] S2_A_PHASE = {
        0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0
    };
    private static final double[] S2_B_AMP = {
        0.016, 0.019, 0.014, 0.010, 0.007, 0.005, 0.003, 0.002, 0.001
    };
    private static final double[] S2_B_PHASE = {
        0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0
    };

    // S1/S2高阶谐波改正项
    private static final double[] S1_HARM_AMP = { -0.030, -0.015, -0.007, -0.003 };
    private static final double[] S1_HARM_PHASE = { 0.0, 0.0, 0.0, 0.0 };
    private static final double[] S2_HARM_AMP = { -0.020, -0.010, -0.005, -0.002 };
    private static final double[] S2_HARM_PHASE = { 0.0, 0.0, 0.0, 0.0 };

    /**
     * 计算S1/S2大气潮汐位移。
     *
     * @param lat    测站纬度（度）
     * @param lon    测站经度（度）
     * @param hell   测站椭高（m）
     * @param mjd    修正儒略日
     * @param pS1S2  实际气压数组[pS1, pS2]（mbar），null时用标准气压1013.25
     * @return [dR, dT, dL] 位移（m），径向/北向/东向
     */
    public static double[] displacementS1S2(double lat, double lon, double hell,
                                            double mjd, double[] pS1S2) {
        double latRad = lat * DEG;
        double lonRad = lon * DEG;

        // 潮汐角：θ1 = 2π(MJD-51544.5)，51544.5=J2000.0的MJD
        double thetaS1 = 2.0 * Math.PI * (mjd - 51544.5);
        double thetaS2 = 2.0 * thetaS1; // S2频率是S1的2倍

        // 气压缩放因子
        double pS1 = (pS1S2 != null && pS1S2.length >= 2) ? pS1S2[0] : 1013.25;
        double pS2 = (pS1S2 != null && pS1S2.length >= 2) ? pS1S2[1] : 1013.25;
        double p0 = 1013.25; // 标准大气压
        double sfS1 = pS1 / p0;
        double sfS2 = pS2 / p0;

        double dR = 0.0, dT = 0.0, dL = 0.0;

        // S1潮：Legendre多项式展开
        for (int n = 0; n < S1_A_AMP.length; n++) {
            double pn = legendreP(n, Math.sin(latRad));
            double cosArgS1 = thetaS1 + lonRad + S1_A_PHASE[n] * DEG;
            double sinArgS1 = thetaS1 + lonRad + S1_B_PHASE[n] * DEG;
            dR += pn * (S1_A_AMP[n] * Math.cos(cosArgS1) + S1_B_AMP[n] * Math.sin(sinArgS1)) * sfS1;
            // 水平位移约为径向的10%
            dT += pn * (S1_A_AMP[n] * Math.sin(cosArgS1) - S1_B_AMP[n] * Math.cos(sinArgS1)) * sfS1 * 0.1;
        }

        // S2潮：Legendre多项式展开
        for (int n = 0; n < S2_A_AMP.length; n++) {
            double pn = legendreP(n, Math.sin(latRad));
            double cosArgS2 = thetaS2 + 2.0 * lonRad + S2_A_PHASE[n] * DEG;
            double sinArgS2 = thetaS2 + 2.0 * lonRad + S2_B_PHASE[n] * DEG;
            dR += pn * (S2_A_AMP[n] * Math.cos(cosArgS2) + S2_B_AMP[n] * Math.sin(sinArgS2)) * sfS2;
            dT += pn * (S2_A_AMP[n] * Math.sin(cosArgS2) - S2_B_AMP[n] * Math.cos(sinArgS2)) * sfS2 * 0.1;
        }

        // 高阶谐波改正
        for (int k = 0; k < S1_HARM_AMP.length; k++) {
            double arg = (k + 1) * thetaS1 + S1_HARM_PHASE[k] * DEG;
            dR += S1_HARM_AMP[k] * Math.cos(arg) * sfS1;
        }
        for (int k = 0; k < S2_HARM_AMP.length; k++) {
            double arg = (k + 1) * thetaS2 + S2_HARM_PHASE[k] * DEG;
            dR += S2_HARM_AMP[k] * Math.cos(arg) * sfS2;
        }

        // mm → m
        return new double[]{dR * 1e-3, dT * 1e-3, dL * 1e-3};
    }

    /** Legendre多项式 Pn(x)，递推计算 */
    private static double legendreP(int n, double x) {
        if (n == 0) return 1.0;
        if (n == 1) return x;
        double p0 = 1.0, p1 = x;
        for (int i = 2; i <= n; i++) {
            double p2 = ((2 * i - 1) * x * p1 - (i - 1) * p0) / i;
            p0 = p1;
            p1 = p2;
        }
        return p1;
    }
}