package org.rtklib.java.rtkpos;

import org.rtklib.java.common.SatUtils;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RtkOptimizationsResEdit {
    private static final Logger LOG = LoggerFactory.getLogger(RtkOptimizationsResEdit.class);

    private static final int MAX_RES_HISTORY = 100;

    private RtkOptimizationsResEdit() {
    }

    public static void checkCycleSlip(Rtk rtk, double[] v, double[] R, int[] vflg, int nv) {
        RtkConfig cfg = rtk.rtkConfig;
        if (!cfg.enableResidualEdit) return;

        PrcOpt opt = rtk.opt;
        int nf = (opt.ionoopt == Constants.IONOOPT_IFLC) ? 1 : opt.nf;
        double jumpThresh = cfg.residEditJumpThresh;

        int slipCount = 0;
        for (int i = 0; i < nv; i++) {
            if (vflg[i] < 1) continue;
            double sigma = Math.sqrt(Math.max(R[i * nv + i], 1e-20));
            double normRes = Math.abs(v[i]) / sigma;
            if (normRes > jumpThresh) {
                int sat = (vflg[i] >> 8) & 0xFF;
                int freq = (vflg[i] >> 4) & 0x0F;
                int type = vflg[i] & 0x0F;
                if (sat >= 1 && sat <= Constants.MAXSAT && freq >= 0 && freq < nf) {
                    if (type == 1) {
                        rtk.ssat[sat - 1].slip[freq] |= Constants.LLI_SLIP;
                        slipCount++;
                        LOG.debug("ResEdit: cycle slip detected sat={} f={} normRes={:.2f}", sat, freq, normRes);
                    }
                }
            }
        }
        rtk.diagResEditSlipCount += slipCount;
    }

    public static void checkPcConsistency(Rtk rtk, Obsd[] obs, int[] sat, int ns, int nf, Nav nav) {
        RtkConfig cfg = rtk.rtkConfig;
        if (!cfg.enableResidualEdit) return;

        double thresh = cfg.residEditPcConsistThresh;
        int rejectCount = 0;

        for (int i = 0; i < ns; i++) {
            int s = sat[i] - 1;
            for (int f = 0; f < nf && f < obs[i].L.length && f < obs[i].P.length; f++) {
                if (obs[i].L[f] == 0.0 || obs[i].P[f] == 0.0) continue;

                double freq = SatUtils.sat2freq(sat[i], obs[i].code[f], nav);
                if (freq == 0.0) continue;
                double wavelength = Constants.CLIGHT / freq;

                double phaseDist = obs[i].L[f] * wavelength;
                double codeDist = obs[i].P[f];
                double pcDiff = Math.abs(phaseDist - codeDist);
                double pcDiffCycles = pcDiff / wavelength;

                double sigma = wavelength * 0.5;
                double normDiff = pcDiffCycles / sigma;

                if (normDiff > thresh) {
                    rtk.ssat[s].slip[f] |= Constants.LLI_SLIP;
                    rejectCount++;
                    LOG.debug("ResEdit: P-C inconsistency sat={} f={} normDiff={:.2f}", sat[i], f, normDiff);
                }
            }
        }
        rtk.diagResEditPcRejectCount += rejectCount;
    }

    public static void screenArcIntegrity(Rtk rtk) {
        RtkConfig cfg = rtk.rtkConfig;
        if (!cfg.enableResidualEdit) return;

        PrcOpt opt = rtk.opt;
        int nf = (opt.ionoopt == Constants.IONOOPT_IFLC) ? 1 : opt.nf;
        int minArcLen = cfg.residEditMinArcLen;
        int resetCount = 0;

        for (int i = 0; i < Constants.MAXSAT; i++) {
            for (int f = 0; f < nf; f++) {
                int lock = rtk.ssat[i].lock[f];
                if (lock > 0 && lock < minArcLen) {
                    if (rtk.ssat[i].vsat[f] != 0) {
                        int outc = (int) rtk.ssat[i].outc[f];
                        if (outc > minArcLen / 2) {
                            rtk.ssat[i].lock[f] = -opt.minlock;
                            rtk.ssat[i].outc[f] = 0;
                            int idx = IB(i + 1, f, opt);
                            if (idx < rtk.nx && rtk.x[idx] != 0.0) {
                                rtk.x[idx] = 0.0;
                                rtk.P[idx * rtk.nx + idx] = 0.0;
                            }
                            resetCount++;
                            LOG.debug("ResEdit: arc reset sat={} f={} lock={} outc={}", i + 1, f, lock, outc);
                        }
                    }
                }
            }
        }
        rtk.diagResEditArcResetCount += resetCount;
    }

    private static int IB(int sat, int f, PrcOpt opt) {
        int np = (opt.dynamics == 0) ? 3 : 9;
        int ni = (opt.ionoopt == Constants.IONOOPT_EST) ?
                 (opt.ionoGradient ? Constants.MAXSAT * 3 : Constants.MAXSAT) : 0;
        int nt = (opt.tropopt < Constants.TROPOPT_EST) ? 0 :
                 (opt.tropopt < Constants.TROPOPT_ESTG) ? 2 : 6;
        int nl = (opt.glomodear != Constants.GLO_ARMODE_AUTOCAL) ? 0 : Constants.NFREQGLO;
        int nr = np + ni + nt + nl;
        return nr + Constants.MAXSAT * f + (sat - 1);
    }
}