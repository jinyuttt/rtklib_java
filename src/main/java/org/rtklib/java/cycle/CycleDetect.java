package org.rtklib.java.cycle;

import org.rtklib.java.common.RtklibCommon;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.time.TimeSystem;

/**
 * Cycle slip detection and repair algorithms aligned with RTKLIB rtkcmn.c.
 * 
 * Key algorithms implemented:
 * - TurboEdit: Geometry-free combination for cycle slip detection
 * - Melbourne-Wübbena: MW combination for cycle slip detection
 * - Slip detection using carrier phase and code measurements
 */
public final class CycleDetect {
    private CycleDetect() {
    }

    private static final double THRES_GF = 0.02;
    private static final double THRES_MW = 0.25;
    private static final double THRES_SLIP = 0.01;

    /**
     * Detect cycle slips using geometry-free and Melbourne-Wübbena combinations.
     * @param obs   Observation data array
     * @param n     Number of observations
     * @param nav   Navigation data
     * @param opt   Processing options
     * @param ssat  Satellite status array
     */
    public static void detectSlips(Obsd[] obs, int n, Nav nav, PrcOpt opt, Ssat[] ssat) {
        int i, j, f, f1, f2, sys;
        int[] prn = new int[1];
        double[] freq = new double[Constants.NFREQ];
        double[] gf = new double[Constants.NFREQ];
        double[] mw = new double[Constants.NFREQ];
        double dt, L1, L2, P1, P2;

        for (i = 0; i < n; i++) {
            sys = SatUtils.satsys(obs[i].sat, prn);
            
            for (f = 0; f < Constants.NFREQ; f++) {
                freq[f] = SatUtils.sat2freq(obs[i].sat, obs[i].code[f], nav);
            }

            for (f1 = 0, f2 = 1; f1 < opt.nf - 1 && f2 < opt.nf; f1++, f2++) {
                if (freq[f1] == 0.0 || freq[f2] == 0.0) continue;

                L1 = obs[i].L[f1];
                L2 = obs[i].L[f2];
                P1 = obs[i].P[f1];
                P2 = obs[i].P[f2];

                if (L1 != 0.0 && L2 != 0.0) {
                    gf[f1] = L1 - L2;
                    
                    if (P1 != 0.0 && P2 != 0.0) {
                        mw[f1] = (L1 + L2) - (freq[f1] + freq[f2]) / (freq[f1] - freq[f2]) * (P1 - P2) / Constants.CLIGHT;
                    }
                }

                dt = Math.abs(TimeSystem.timediff(obs[i].time, ssat[obs[i].sat - 1].pt[0][f1]));
                
                if (dt > 0 && dt < 10.0) {
                    double dgf = Math.abs(gf[f1] - ssat[obs[i].sat - 1].gf[f1]);
                    double dmw = Math.abs(mw[f1] - ssat[obs[i].sat - 1].mw[f1]);

                    if (dgf > THRES_GF && obs[i].LLI[f1] == 0) {
                        ssat[obs[i].sat - 1].slip[f1] = 1;
                    }
                    if (dmw > THRES_MW && obs[i].LLI[f1] == 0) {
                        ssat[obs[i].sat - 1].slip[f1] = 1;
                    }
                }

                ssat[obs[i].sat - 1].gf[f1] = gf[f1];
                ssat[obs[i].sat - 1].mw[f1] = mw[f1];
                ssat[obs[i].sat - 1].pt[0][f1] = new GTime(obs[i].time);
            }
        }
    }

    /**
     * Detect slips using carrier phase rate of change.
     * @param obs   Observation data array
     * @param n     Number of observations
     * @param opt   Processing options
     * @param ssat  Satellite status array
     */
    public static void detectSlipsRate(Obsd[] obs, int n, PrcOpt opt, Ssat[] ssat) {
        int i, f;
        double dt, rate, dL;

        for (i = 0; i < n; i++) {
            for (f = 0; f < opt.nf; f++) {
                if (obs[i].L[f] == 0.0) continue;

                dt = Math.abs(TimeSystem.timediff(obs[i].time, ssat[obs[i].sat - 1].pt[1][f]));
                
                if (dt > 0.5 && dt < 10.0 && ssat[obs[i].sat - 1].ph[1][f] != 0.0) {
                    dL = obs[i].L[f] - ssat[obs[i].sat - 1].ph[1][f];
                    rate = Math.abs(dL / dt);
                    
                    if (rate > THRES_SLIP && obs[i].LLI[f] == 0) {
                        ssat[obs[i].sat - 1].slip[f] = 1;
                    }
                }

                ssat[obs[i].sat - 1].ph[1][f] = obs[i].L[f];
                ssat[obs[i].sat - 1].pt[1][f] = new GTime(obs[i].time);
            }
        }
    }

    /**
     * Reset slip detection status.
     * @param ssat  Satellite status array
     */
    public static void resetSlips(Ssat[] ssat) {
        for (int i = 0; i < Constants.MAXSAT; i++) {
            for (int f = 0; f < Constants.NFREQ; f++) {
                ssat[i].slip[f] = 0;
                ssat[i].half[f] = 0;
                ssat[i].lock[f] = 0;
            }
        }
    }
}