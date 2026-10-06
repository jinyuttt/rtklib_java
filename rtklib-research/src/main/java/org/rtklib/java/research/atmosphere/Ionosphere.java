package org.rtklib.java.research.atmosphere;

import org.rtklib.java.research.common.GTime;

/**
 * 电离层延迟模型（research模块自有）。
 */
public final class Ionosphere {
    private Ionosphere() {}

    public static double klobuchar(double[] receiverLLH, double elRad, double azRad,
                                   GTime time, double[] ionoParam) {
        if (elRad <= 0.0) return 0.0;

        double pos[] = receiverLLH;
        double sinp = pos[0] == 0.0 && pos[1] == 0.0 ? Math.sin(Math.toRadians(45.0)) : Math.sin(pos[0]);

        double psi = 0.0137 / (elRad / (Math.PI / 2.0) + 0.0137) - 0.0137;
        double phi_i = Math.asin(sinp * Math.cos(psi) + Math.cos(pos[0]) * Math.sin(psi) * Math.cos(azRad));
        if (phi_i > Math.toRadians(71.5)) phi_i = Math.toRadians(71.5);
        if (phi_i < Math.toRadians(-71.5)) phi_i = Math.toRadians(-71.5);

        double lambda_i = pos[1] + Math.asin(Math.sin(psi) * Math.sin(azRad) / Math.cos(phi_i));
        double phi_m = phi_i + 0.064 * Math.cos(lambda_i - Math.toRadians(1.617));

        double PER = ionoParam[3];
        if (PER < 72000.0) PER = 72000.0;
        double t = 43200.0 * lambda_i / Math.PI + time.toSeconds();
        t = t % 86400.0;
        if (t < 0) t += 86400.0;

        double AMP = ionoParam[0] + phi_m * (ionoParam[1] + phi_m * (ionoParam[2] + phi_m * ionoParam[3]));
        if (AMP < 0.0) AMP = 0.0;

        double x = 2.0 * Math.PI * (t - 50400.0) / PER;
        double F = 1.0 + 16.0 * Math.pow(0.53 - elRad / Math.PI, 3.0);

        double TION;
        if (Math.abs(x) < 1.57) {
            TION = F * (5.0e-9 + AMP * (1.0 - x * x / 2.0 + x * x * x * x / 24.0));
        } else {
            TION = F * 5.0e-9;
        }
        return TION * org.rtklib.java.research.common.GnssConst.CLIGHT;
    }
}