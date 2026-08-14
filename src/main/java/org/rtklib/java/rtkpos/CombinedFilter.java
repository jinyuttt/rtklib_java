package org.rtklib.java.rtkpos;

import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.GTime;
import org.rtklib.java.data.PrcOpt;
import org.rtklib.java.data.Sol;
import org.rtklib.java.data.SolOpt;
import org.rtklib.java.time.TimeSystem;

import java.util.ArrayList;
import java.util.List;

public class CombinedFilter {

    private static final double DTTOL = 0.005;
    private static final int[] PRI = {7, 1, 2, 3, 4, 5, 1, 6};

    private CombinedFilter() {
    }

    public static List<Sol> combine(Sol[] solf, Sol[] solb,
                                     double[][] rbf, double[][] rbb,
                                     PrcOpt popt, SolOpt sopt) {
        List<Sol> results = new ArrayList<>();

        boolean solstatic = sopt != null && sopt.solstatic != 0 &&
                (popt.mode == Constants.PMODE_STATIC || popt.mode == Constants.PMODE_STATIC_START
                        || popt.mode == Constants.PMODE_PPP_STATIC);

        Sol bestSol = null;
        GTime bestTime = null;

        int i = 0, j = solb.length - 1;

        while (i < solf.length && j >= 0) {
            Sol sols;

            if (solf[i] == null && solb[j] == null) {
                i++;
                j--;
                continue;
            }

            if (solf[i] == null) {
                sols = new Sol(solb[j]);
                i++;
                j--;
                addResult(results, sols, solstatic, bestSol, bestTime);
                if (solstatic) updateBest(sols, bestTime);
                continue;
            }

            if (solb[j] == null) {
                sols = new Sol(solf[i]);
                i++;
                j--;
                addResult(results, sols, solstatic, bestSol, bestTime);
                if (solstatic) updateBest(sols, bestTime);
                continue;
            }

            double tt = TimeSystem.timediff(solf[i].time, solb[j].time);

            if (tt < -DTTOL) {
                sols = new Sol(solf[i]);
                j++;
            } else if (tt > DTTOL) {
                sols = new Sol(solb[j]);
                i--;
            } else if (PRI[solf[i].stat] < PRI[solb[j].stat]) {
                sols = new Sol(solf[i]);
            } else if (PRI[solf[i].stat] > PRI[solb[j].stat]) {
                sols = new Sol(solb[j]);
            } else {
                sols = new Sol(solf[i]);
                sols.time = TimeSystem.timeadd(sols.time, -tt / 2.0);

                if ((popt.mode == Constants.PMODE_KINEMA || popt.mode == Constants.PMODE_MOVEB)
                        && sols.stat == Constants.SOLQ_FIX) {
                    if (!valcomb(solf[i], solb[j], rbf[i], rbb[j], popt)) {
                        sols.stat = Constants.SOLQ_FLOAT;
                    }
                }

                double[] Qf = buildQFromQr(solf[i].qr);
                double[] Qb = buildQFromQr(solb[j].qr);
                double[] Qs = new double[9];

                if (popt.mode == Constants.PMODE_MOVEB) {
                    double[] rr_f = new double[3];
                    double[] rr_b = new double[3];
                    double[] rr_s = new double[3];
                    for (int k = 0; k < 3; k++) {
                        rr_f[k] = solf[i].rr[k] - (rbf[i] != null ? rbf[i][k] : 0);
                        rr_b[k] = solb[j].rr[k] - (rbb[j] != null ? rbb[j][k] : 0);
                    }
                    if (Smoother.smooth(rr_f, Qf, rr_b, Qb, 3, rr_s, Qs) == 0) {
                        for (int k = 0; k < 3; k++) {
                            sols.rr[k] = (rbf[i] != null ? rbf[i][k] : 0) + rr_s[k];
                        }
                        extractQr(Qs, sols.qr);
                    }
                } else {
                    double[] xs = new double[3];
                    if (Smoother.smooth(solf[i].rr, Qf, solb[j].rr, Qb, 3, xs, Qs) == 0) {
                        System.arraycopy(xs, 0, sols.rr, 0, 3);
                        extractQr(Qs, sols.qr);
                    }
                }

                if (popt.dynamics != 0) {
                    double[] Qfv = buildQFromQr(solf[i].qv);
                    double[] Qbv = buildQFromQr(solb[j].qv);
                    double[] Qsv = new double[9];
                    double[] vf = new double[]{solf[i].rr[3], solf[i].rr[4], solf[i].rr[5]};
                    double[] vb = new double[]{solb[j].rr[3], solb[j].rr[4], solb[j].rr[5]};
                    double[] vs = new double[3];
                    if (Smoother.smooth(vf, Qfv, vb, Qbv, 3, vs, Qsv) == 0) {
                        System.arraycopy(vs, 0, sols.rr, 3, 3);
                        extractQr(Qsv, sols.qv);
                    }
                }
            }

            if (!solstatic) {
                results.add(sols);
            } else {
                if (bestSol == null || PRI[sols.stat] <= PRI[bestSol.stat]) {
                    bestSol = sols;
                    if (bestTime == null || TimeSystem.timediff(sols.time, bestTime) < 0.0) {
                        bestTime = new GTime(sols.time);
                    }
                }
            }

            i++;
            j--;
        }

        if (solstatic && bestSol != null) {
            bestSol.time = bestTime != null ? bestTime : bestSol.time;
            results.add(bestSol);
        }

        return results;
    }

    private static double[] buildQFromQr(float[] qr) {
        double[] Q = new double[9];
        Q[0] = qr[0]; Q[4] = qr[1]; Q[8] = qr[2];
        Q[1] = Q[3] = qr[3];
        Q[5] = Q[7] = qr[4];
        Q[2] = Q[6] = qr[5];
        return Q;
    }

    private static void extractQr(double[] Q, float[] qr) {
        qr[0] = (float) Q[0];
        qr[1] = (float) Q[4];
        qr[2] = (float) Q[8];
        qr[3] = (float) Q[1];
        qr[4] = (float) Q[5];
        qr[5] = (float) Q[2];
    }

    private static boolean valcomb(Sol solf, Sol solb,
                                    double[] rbf, double[] rbb, PrcOpt popt) {
        double[] dr = new double[3];
        double[] var = new double[3];

        for (int i = 0; i < 3; i++) {
            dr[i] = solf.rr[i] - solb.rr[i];
            if (popt.mode == Constants.PMODE_MOVEB) {
                dr[i] -= ((rbf != null ? rbf[i] : 0) - (rbb != null ? rbb[i] : 0));
            }
            var[i] = (double) solf.qr[i] + (double) solb.qr[i];
        }

        for (int i = 0; i < 3; i++) {
            if (dr[i] * dr[i] > 16.0 * var[i]) {
                return false;
            }
        }

        return true;
    }

    private static void addResult(List<Sol> results, Sol sol, boolean solstatic,
                                   Sol bestSol, GTime bestTime) {
        if (!solstatic) {
            results.add(sol);
        }
    }

    private static void updateBest(Sol sol, GTime bestTime) {
        // placeholder for static mode best tracking
    }
}