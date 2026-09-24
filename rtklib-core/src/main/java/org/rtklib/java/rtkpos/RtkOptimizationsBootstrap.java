package org.rtklib.java.rtkpos;

import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RtkOptimizationsBootstrap {
    private static final Logger LOG = LoggerFactory.getLogger(RtkOptimizationsBootstrap.class);

    private RtkOptimizationsBootstrap() {
    }

    public static double computeSuccessRate(double[] a, double[] Q, int n) {
        if (n < 1) return 0.0;

        double[] sigma = new double[n];
        for (int i = 0; i < n; i++) {
            sigma[i] = Math.sqrt(Math.max(Q[i * n + i], 1e-30));
        }

        double successRate = 1.0;
        for (int i = 0; i < n; i++) {
            double frac = a[i] - Math.round(a[i]);
            double halfNorm = 0.5 / sigma[i];
            double prob = erfApprox(halfNorm / Math.sqrt(2.0));
            if (prob < 0.0) prob = 0.0;
            if (prob > 1.0) prob = 1.0;
            successRate *= prob;
        }

        return successRate;
    }

    public static boolean validateFix(Rtk rtk, double[] bias, int nb) {
        RtkConfig cfg = rtk.rtkConfig;
        if (!cfg.enableBootstrapping) return true;

        if (nb < 1) return false;

        double[] a = new double[nb];
        double[] Qb = extractAmbCovariance(rtk, nb);

        for (int i = 0; i < nb; i++) {
            a[i] = bias != null && bias.length > i * 2 ? bias[i * 2] : 0.0;
        }

        double successRate = computeSuccessRate(a, Qb, nb);

        boolean passed = successRate >= cfg.bootstrappingMinSuccess;
        if (!passed) {
            LOG.debug("Bootstrap rejected: successRate={:.4f} < min={:.4f} nb={}",
                    successRate, cfg.bootstrappingMinSuccess, nb);
        }
        return passed;
    }

    static double[] extractAmbCovariance(Rtk rtk, int nb) {
        double[] Qb = new double[nb * nb];
        int nx = rtk.nx;
        int na = rtk.na;
        int nAmb = nx - na;

        if (nAmb <= 0 || nb > nAmb) {
            for (int i = 0; i < nb; i++) Qb[i * nb + i] = 1.0;
            return Qb;
        }

        for (int i = 0; i < nb && i < nAmb; i++) {
            for (int j = 0; j < nb && j < nAmb; j++) {
                Qb[i * nb + j] = rtk.P[(na + i) * nx + (na + j)];
            }
        }
        return Qb;
    }

    static double erfcApprox(double x) {
        if (x < 0.0) return 2.0 - erfcApprox(-x);
        if (x == 0.0) return 1.0;
        if (x > 6.0) return 0.0;

        double t = 1.0 / (1.0 + 0.3275911 * x);
        double result = t * (0.254829592 + t * (-0.284496736 + t * (1.421413741
                + t * (-1.453152027 + t * 1.061405429))));
        result *= Math.exp(-x * x);
        return result;
    }

    static double erfApprox(double x) {
        return 1.0 - erfcApprox(x);
    }
}