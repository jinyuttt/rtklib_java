package org.rtklib.java.otl;

/**
 * Normalized associated Legendre functions and their derivatives.
 * Ported from BelPnmdt.f90 (Zhang Chuanyin, PRIDE PPP-AR).
 * Uses improved Belikov recursion for numerical stability.
 */
public final class SphericalHarmonic {

    private SphericalHarmonic() {}

    /**
     * Compute fully normalized associated Legendre functions Pnm(cosθ)
     * and their first/second derivatives with respect to θ.
     *
     * @param maxn maximum degree
     * @param t    cos(θ)
     * @return double[3][(maxn+2)^2]: [0]=Pnm, [1]=dPnm/dθ, [2]=d²Pnm/dθ²
     *         Index mapping: k = n*(n+1)/2 + m + 1
     */
    public static double[][] belPnmDt(int maxn, double t) {
        int size = (maxn + 2) * (maxn + 2) + 1;
        double[] pnm = new double[size];
        double[] dpt1 = new double[size];
        double[] dpt2 = new double[size];

        double u = Math.sqrt(1.0 - t * t);
        pnm[1] = 1.0;
        pnm[2] = Math.sqrt(3.0) * t;
        pnm[3] = Math.sqrt(3.0) * u;

        for (int n = 1; n <= maxn; n++) {
            double a = Math.sqrt(2.0 * n + 1.0) / Math.sqrt(2.0 * n - 1.0);
            double b = Math.sqrt(2.0 * (n - 1.0) * (2.0 * n + 1.0)) / Math.sqrt((2.0 * n - 1.0) * n);
            int kk = n * (n + 1) / 2 + 1;
            int k1 = n * (n - 1) / 2 + 1;
            int k2 = n * (n - 1) / 2 + 2;
            pnm[kk] = a * t * pnm[k1] - b * u / 2.0 * pnm[k2];

            for (int m = 1; m <= n; m++) {
                kk = n * (n + 1) / 2 + m + 1;
                k1 = n * (n - 1) / 2 + m + 1;
                k2 = n * (n - 1) / 2 + m + 2;
                int k3 = n * (n - 1) / 2 + m;
                double c = a / n * Math.sqrt((double) (n * n - m * m));
                double d = a / n / 2.0 * Math.sqrt((double) ((n - m) * (n - m - 1)));
                double e = a / n / 2.0 * Math.sqrt((double) ((n + m) * (n + m - 1)));
                if (m == 1) e *= Math.sqrt(2.0);
                pnm[kk] = c * t * pnm[k1] - d * u * pnm[k2] + e * u * pnm[k3];
            }
        }

        // First and second derivatives
        dpt1[1] = 0.0;
        dpt1[2] = -Math.sqrt(3.0) * u;
        dpt1[3] = Math.sqrt(3.0) * t;
        dpt2[1] = 0.0;
        dpt2[2] = -Math.sqrt(3.0) * t;
        dpt2[3] = -Math.sqrt(3.0) * u;

        for (int n = 2; n <= maxn; n++) {
            int kk, k1, k2;
            double anm, bnm;

            // m=0
            kk = n * (n + 1) / 2 + 1;
            k1 = n * (n + 1) / 2 + 2;
            dpt1[kk] = -Math.sqrt(n * (n + 1) / 2.0) * pnm[k1];

            // m=1, first derivative
            kk = n * (n + 1) / 2 + 2;
            k1 = n * (n + 1) / 2 + 1;
            k2 = n * (n + 1) / 2 + 3;
            anm = Math.sqrt(2.0 * n) * Math.sqrt(n + 1.0) / 2.0;
            bnm = -Math.sqrt(n - 1.0) * Math.sqrt(n + 2.0) / 2.0;
            dpt1[kk] = anm * pnm[k1] + bnm * pnm[k2];

            // m=0, second derivative
            kk = n * (n + 1) / 2 + 1;
            k1 = n * (n + 1) / 2 + 3;
            anm = -(double) (n * (n + 1)) / 2.0;
            bnm = Math.sqrt((double) (n * (n - 1))) * Math.sqrt((double) ((n + 1) * (n + 2))) / Math.sqrt(8.0);
            dpt2[kk] = anm * pnm[kk] + bnm * pnm[k1];

            // m=1, second derivative
            kk = n * (n + 1) / 2 + 2;
            k1 = n * (n + 1) / 2 + 4;
            anm = -(2.0 * n * (n + 1) + (n - 1.0) * (n + 2.0)) / 4.0;
            bnm = Math.sqrt((double) ((n - 2) * (n - 1))) * Math.sqrt((double) ((n + 2) * (n + 3))) / 4.0;
            dpt2[kk] = anm * pnm[kk] + bnm * pnm[k1];

            // m>=2
            for (int m = 2; m <= n; m++) {
                kk = n * (n + 1) / 2 + m + 1;
                k1 = n * (n + 1) / 2 + m;
                k2 = n * (n + 1) / 2 + m + 2;
                anm = Math.sqrt(n + m) * Math.sqrt(n - m + 1.0) / 2.0;
                bnm = -Math.sqrt(n - m) * Math.sqrt(n + m + 1.0) / 2.0;
                dpt1[kk] = anm * pnm[k1] + bnm * pnm[k2];

                // second derivative
                k1 = n * (n + 1) / 2 + m - 1;
                k2 = n * (n + 1) / 2 + m + 3;
                anm = Math.sqrt((double) ((n - m + 1) * (n - m + 2))) * Math.sqrt((double) ((n + m - 1) * (n + m))) / 4.0;
                bnm = -((double) ((n - m + 1) * (n + m) + (n - m) * (n + m + 1))) / 4.0;
                double cnm = Math.sqrt((double) ((n - m - 1) * (n - m))) * Math.sqrt((double) ((n + m + 1) * (n + m + 2))) / 4.0;
                dpt2[kk] = anm * pnm[k1] + bnm * pnm[kk] + cnm * pnm[k2];
            }
        }

        return new double[][]{pnm, dpt1, dpt2};
    }
}
