package org.rtklib.java.research.atmosphere;

import org.rtklib.java.research.common.GnssConst;

/**
 * 对流层延迟模型（research模块自有）。
 */
public final class Troposphere {
    private Troposphere() {}

    public static double saastamoinen(double[] receiverLLH, double elRad) {
        if (elRad <= 0.0) return 0.0;

        double sinel = Math.sin(elRad);
        if (sinel < 0.0) sinel = 0.0;

        double h = receiverLLH[2];
        if (h < -1000.0) h = -1000.0;
        if (h > 20000.0) h = 20000.0;

        double pres = 1013.25 * Math.pow(1.0 - 2.2557e-5 * h, 5.2568);
        double temp = 15.0 - 6.5e-3 * h + 273.16;
        double hum = 0.5 * Math.exp(-h / 15000.0);

        double e_w = hum * 6.108 * Math.exp(17.15 * (temp - 273.16) / (temp - 38.35));

        double zhd = 0.0022768 * pres / (1.0 - 0.00266 * Math.cos(2.0 * receiverLLH[0]) - 0.00028 * h / 1000.0);
        double zwd = 0.002277 * (1255.0 / temp + 0.05) * e_w;

        double mapfh = 1.0 / Math.sqrt(1.0 - Math.pow(0.9782 * Math.cos(elRad), 2));
        double mapfw = 1.0 / Math.sqrt(1.0 - Math.pow(0.9562 * Math.cos(elRad), 2));

        return zhd * mapfh + zwd * mapfw;
    }
}