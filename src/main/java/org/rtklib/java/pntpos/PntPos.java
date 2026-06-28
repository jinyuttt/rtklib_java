package org.rtklib.java.pntpos;

import org.rtklib.java.common.RtklibCommon;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.ephemeris.EphModel;

public final class PntPos {
    private static final int MAXITR = 10;

    private PntPos() {
    }

    public static int pntpos(Obsd[] obs, int n, Nav nav, PrcOpt opt, Sol sol, double[] azel, Ssat[] ssat) {
        PrcOpt opt_ = new PrcOpt(opt);

        sol.stat = Constants.SOLQ_NONE;

        if (n <= 0) {
            return 0;
        }

        sol.time = obs[0].time;
        sol.eventime = obs[0].eventime;

        if (ssat != null) {
            for (int i = 0; i < Constants.MAXSAT; i++) {
                ssat[i].snrRover[0] = 0;
                ssat[i].snrBase[0] = 0;
            }
            for (int i = 0; i < n; i++) {
                if (obs[i].sat > 0 && obs[i].sat <= Constants.MAXSAT) {
                    ssat[obs[i].sat - 1].snrRover[0] = obs[i].SNR[0];
                }
            }
        }

        if (opt_.mode != Constants.PMODE_SINGLE) {
            opt_.ionoopt = Constants.IONOOPT_BRDC;
            opt_.tropopt = Constants.TROPOPT_SAAS;
        }

        double[] rs = new double[n * 6];
        double[] dts = new double[n * 2];
        double[] vare = new double[n];
        int[] svh = new int[n];

        EphModel.satposs(sol.time, obs, n, nav, rs, dts, vare, svh);

        int[] vsat = new int[n];
        double[] resp = new double[n];
        String[] msg = new String[1];
        double[] azel_ = new double[n * 2];

        int stat = SppCore.estpos(obs, n, rs, dts, vare, svh, nav, opt_, ssat, sol, azel_, vsat, resp, msg);

        if (stat == 0 && n >= 6 && opt_.posopt[4] != 0) {
            stat = raimFde(obs, n, rs, dts, vare, svh, nav, opt_, ssat, sol, azel_, vsat, resp, msg);
        }

        if (stat != 0) {
            estvel(obs, n, rs, dts, nav, opt_, sol, azel_, vsat);
        }

        if (azel != null) {
            for (int i = 0; i < n * 2; i++) {
                azel[i] = azel_[i];
            }
        }

        if (ssat != null) {
            for (int i = 0; i < Constants.MAXSAT; i++) {
                ssat[i].vs = 0;
                ssat[i].azel[0] = 0.0;
                ssat[i].azel[1] = 0.0;
                ssat[i].resp[0] = 0.0;
                ssat[i].resc[0] = 0.0;
            }
            for (int i = 0; i < n; i++) {
                if (obs[i].sat < 1 || obs[i].sat > Constants.MAXSAT) continue;
                ssat[obs[i].sat - 1].azel[0] = azel_[i * 2];
                ssat[obs[i].sat - 1].azel[1] = azel_[1 + i * 2];
                if (vsat[i] == 0) continue;
                ssat[obs[i].sat - 1].vs = 1;
                ssat[obs[i].sat - 1].resp[0] = resp[i];
            }
        }

        return stat;
    }

    static int raimFde(Obsd[] obs, int n, double[] rs, double[] dts,
                       double[] vare, int[] svh, Nav nav, PrcOpt opt,
                       Ssat[] ssat, Sol sol, double[] azel, int[] vsat,
                       double[] resp, String[] msg) {
        Sol solE = new Sol();
        double rms = 100.0;
        int stat = 0;
        int sat = 0;

        Obsd[] obsE = new Obsd[n];
        double[] rsE = new double[n * 6];
        double[] dtsE = new double[n * 2];
        double[] vareE = new double[n];
        int[] svhE = new int[n];
        double[] azelE = new double[n * 2];
        int[] vsatE = new int[n];
        double[] respE = new double[n];
        String[] msgE = new String[1];

        for (int i = 0; i < n; i++) {

            int k = 0;
            for (int j = 0; j < n; j++) {
                if (j == i) continue;
                obsE[k] = obs[j];
                System.arraycopy(rs, j * 6, rsE, k * 6, 6);
                System.arraycopy(dts, j * 2, dtsE, k * 2, 2);
                vareE[k] = vare[j];
                svhE[k] = svh[j];
                k++;
            }

            if (SppCore.estpos(obsE, n - 1, rsE, dtsE, vareE, svhE, nav, opt,
                    ssat, solE, azelE, vsatE, respE, msgE) == 0) {
                continue;
            }

            int nvsat = 0;
            double rmsE = 0.0;
            for (int j = 0; j < n - 1; j++) {
                if (vsatE[j] == 0) continue;
                rmsE += respE[j] * respE[j];
                nvsat++;
            }
            if (nvsat < 5) {
                continue;
            }
            rmsE = Math.sqrt(rmsE / nvsat);

            if (rmsE > rms) continue;

            k = 0;
            for (int j = 0; j < n; j++) {
                if (j == i) continue;
                azel[j * 2] = azelE[k * 2];
                azel[j * 2 + 1] = azelE[k * 2 + 1];
                vsat[j] = vsatE[k];
                resp[j] = respE[k];
                k++;
            }
            stat = 1;
            solE.eventime = sol.eventime;
            sol.time = solE.time;
            sol.stat = solE.stat;
            sol.ns = solE.ns;
            sol.type = solE.type;
            System.arraycopy(solE.rr, 0, sol.rr, 0, sol.rr.length);
            System.arraycopy(solE.qr, 0, sol.qr, 0, sol.qr.length);
            System.arraycopy(solE.dtr, 0, sol.dtr, 0, sol.dtr.length);
            sat = obs[i].sat;
            rms = rmsE;
            vsat[i] = 0;
            msg[0] = msgE[0];
        }

        return stat;
    }

    static void estvel(Obsd[] obs, int n, double[] rs, double[] dts,
                       Nav nav, PrcOpt opt, Sol sol, double[] azel, int[] vsat) {
        double[] x = new double[4];
        double[] dx = new double[4];
        double[] Q = new double[16];
        double err = opt.err[4];
        int nv;
        double[] v = new double[n];
        double[] H = new double[4 * n];

        for (int i = 0; i < MAXITR; i++) {
            nv = resdop(obs, n, rs, dts, nav, sol.rr, x, azel, vsat, err, v, H);
            if (nv < 4) break;

            if (RtklibCommon.lsq(H, v, 4, nv, dx, Q) != 0) break;

            for (int j = 0; j < 4; j++) x[j] += dx[j];

            if (RtklibCommon.norm(dx, 4) < 1E-6) {
                System.arraycopy(x, 0, sol.rr, 3, 3);
                sol.qv[0] = (float) Q[0];
                sol.qv[1] = (float) Q[5];
                sol.qv[2] = (float) Q[10];
                sol.qv[3] = (float) Q[1];
                sol.qv[4] = (float) Q[6];
                sol.qv[5] = (float) Q[2];
                break;
            }
        }
    }

    static int resdop(Obsd[] obs, int n, double[] rs, double[] dts,
                      Nav nav, double[] rr, double[] x, double[] azel,
                      int[] vsat, double err, double[] v, double[] H) {
        double[] pos = new double[3];
        double[] E = new double[9];
        double[] a = new double[3];
        double[] e = new double[3];
        double[] vs = new double[3];
        int nv = 0;

        CoordTransform.ecef2pos(rr, pos);
        CoordTransform.xyz2enu(pos, E);

        for (int i = 0; i < n && i < Constants.MAXOBS; i++) {
            double freq = SatUtils.sat2freq(obs[i].sat, obs[i].code[0], nav);

            if (obs[i].D[0] == 0.0 || freq == 0.0 || vsat[i] == 0 ||
                    Math.sqrt(rs[3 + i * 6] * rs[3 + i * 6] + rs[4 + i * 6] * rs[4 + i * 6] + rs[5 + i * 6] * rs[5 + i * 6]) <= 0.0) {
                continue;
            }

            double cosel = Math.cos(azel[1 + i * 2]);
            a[0] = Math.sin(azel[i * 2]) * cosel;
            a[1] = Math.cos(azel[i * 2]) * cosel;
            a[2] = Math.sin(azel[1 + i * 2]);

            for (int j = 0; j < 3; j++) {
                e[j] = E[j] * a[0] + E[j + 3] * a[1] + E[j + 6] * a[2];
            }

            for (int j = 0; j < 3; j++) {
                vs[j] = rs[j + 3 + i * 6] - x[j];
            }

            double rate = e[0] * vs[0] + e[1] * vs[1] + e[2] * vs[2] +
                    Constants.OMGE / Constants.CLIGHT *
                    (rs[4 + i * 6] * rr[0] + rs[1 + i * 6] * x[0] -
                     rs[3 + i * 6] * rr[1] - rs[i * 6] * x[1]);

            double sig = (err <= 0.0) ? 1.0 : err * Constants.CLIGHT / freq;

            v[nv] = (-obs[i].D[0] * Constants.CLIGHT / freq - (rate + x[3] - Constants.CLIGHT * dts[1 + i * 2])) / sig;

            for (int j = 0; j < 4; j++) {
                H[nv * 4 + j] = ((j < 3) ? -e[j] : 1.0) / sig;
            }
            nv++;
        }
        return nv;
    }
}