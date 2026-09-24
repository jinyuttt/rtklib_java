package org.rtklib.java.rtkpos;

import org.rtklib.java.common.SatUtils;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RtkOptimizationsBdsBias {
    private static final Logger LOG = LoggerFactory.getLogger(RtkOptimizationsBdsBias.class);

    private static final double[] GEO_COEFF = {-0.58, -0.58, -0.58};
    private static final double[] IGSO_COEFF_LOW = {-0.40, -0.40, -0.40};
    private static final double[] IGSO_COEFF_HIGH = {-0.10, -0.10, -0.10};
    private static final double[] MEO_COEFF_LOW = {-0.30, -0.30, -0.30};
    private static final double[] MEO_COEFF_HIGH = {-0.05, -0.05, -0.05};
    private static final double EL_THRESH_LOW = 15.0 * Math.PI / 180.0;
    private static final double EL_THRESH_HIGH = 45.0 * Math.PI / 180.0;

    private RtkOptimizationsBdsBias() {
    }

    public static double[] computeBdsCodeBias(int sat, double el, int nf, Nav nav) {
        double[] bias = new double[nf];
        int[] prn = new int[1];
        int sys = SatUtils.satsys(sat, prn);
        if ((sys & Constants.SYS_CMP) == 0) return bias;

        int prnVal = prn[0];
        boolean isGeo = (prnVal >= 1 && prnVal <= 5);
        boolean isIgso = (prnVal >= 6 && prnVal <= 10);
        boolean isMeo = (prnVal >= 11 && prnVal <= 46);

        for (int f = 0; f < nf; f++) {
            if (isGeo) {
                bias[f] = GEO_COEFF[Math.min(f, GEO_COEFF.length - 1)];
            } else if (isIgso) {
                double[] low = IGSO_COEFF_LOW;
                double[] high = IGSO_COEFF_HIGH;
                int idx = Math.min(f, low.length - 1);
                double t = (el - EL_THRESH_LOW) / (EL_THRESH_HIGH - EL_THRESH_LOW);
                t = Math.max(0.0, Math.min(1.0, t));
                bias[f] = low[idx] + t * (high[idx] - low[idx]);
            } else if (isMeo) {
                double[] low = MEO_COEFF_LOW;
                double[] high = MEO_COEFF_HIGH;
                int idx = Math.min(f, low.length - 1);
                double t = (el - EL_THRESH_LOW) / (EL_THRESH_HIGH - EL_THRESH_LOW);
                t = Math.max(0.0, Math.min(1.0, t));
                bias[f] = low[idx] + t * (high[idx] - low[idx]);
            }
        }

        return bias;
    }

    public static void applyBdsCodeBias(Rtk rtk, Obsd[] obs, int[] iu, int[] ir,
                                         int ns, int nf, Nav nav) {
        RtkConfig cfg = rtk.rtkConfig;
        if (!cfg.enableBdsCodeBias) return;

        double elThreshRad = cfg.bdsCodeBiasElThresh * Math.PI / 180.0;
        int applyCount = 0;

        for (int i = 0; i < ns; i++) {
            int sat = iu[i] >= 0 && iu[i] < obs.length ? getSatFromObs(obs, iu[i]) : 0;
            if (sat < 1 || sat > Constants.MAXSAT) continue;

            int sys = SatUtils.satsys(sat, new int[1]);
            if ((sys & Constants.SYS_CMP) == 0) continue;

            double el = rtk.ssat[sat - 1].azel[1];
            if (el < elThreshRad && !cfg.bdsCodeBiasForceOn) continue;

            double[] codeBias = computeBdsCodeBias(sat, el, nf, nav);
            for (int f = 0; f < nf && f < obs[iu[i]].P.length; f++) {
                if (obs[iu[i]].P[f] != 0.0) {
                    obs[iu[i]].P[f] += codeBias[f];
                }
            }
            if (ir[i] >= 0 && ir[i] < obs.length) {
                for (int f = 0; f < nf && f < obs[ir[i]].P.length; f++) {
                    if (obs[ir[i]].P[f] != 0.0) {
                        obs[ir[i]].P[f] += codeBias[f];
                    }
                }
            }
            applyCount++;
        }

        rtk.diagBdsBiasApplyCount += applyCount;
        if (applyCount > 0) {
            LOG.debug("BdsCodeBias applied to {} satellites", applyCount);
        }
    }

    private static int getSatFromObs(Obsd[] obs, int idx) {
        if (idx >= 0 && idx < obs.length && obs[idx] != null) {
            return obs[idx].sat;
        }
        return 0;
    }
}