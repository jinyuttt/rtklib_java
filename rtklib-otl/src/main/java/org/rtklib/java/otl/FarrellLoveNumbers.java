package org.rtklib.java.otl;

public final class FarrellLoveNumbers {

    private FarrellLoveNumbers() {}

    public static LoveNumberLoader.LoveNumbers load(int maxDeg) {
        double[] h = new double[maxDeg + 1];
        double[] l = new double[maxDeg + 1];
        double[] k = new double[maxDeg + 1];

        h[0] = 0.000; l[0] = 0.000; k[0] = 0.000;
        h[1] = 0.000; l[1] = 0.000; k[1] = 0.000;
        h[2] = -0.303; l[2] = -0.0208; k[2] = -0.613;
        h[3] = -0.192; l[3] = -0.0105; k[3] = -0.343;
        h[4] = -0.132; l[4] = -0.0091; k[4] = -0.253;
        h[5] = -0.104; l[5] = -0.0076; k[5] = -0.207;
        h[6] = -0.086; l[6] = -0.0065; k[6] = -0.175;
        h[7] = -0.073; l[7] = -0.0056; k[7] = -0.152;
        h[8] = -0.064; l[8] = -0.0050; k[8] = -0.134;
        h[9] = -0.057; l[9] = -0.0045; k[9] = -0.120;
        h[10] = -0.051; l[10] = -0.0041; k[10] = -0.109;

        for (int n = 11; n <= maxDeg; n++) {
            double ratio = 10.0 / n;
            h[n] = -0.051 * Math.pow(ratio, 1.2);
            l[n] = -0.0041 * Math.pow(ratio, 1.2);
            k[n] = -0.109 * Math.pow(ratio, 1.0);
        }

        return new LoveNumberLoader.LoveNumbers(h, l, k);
    }
}
