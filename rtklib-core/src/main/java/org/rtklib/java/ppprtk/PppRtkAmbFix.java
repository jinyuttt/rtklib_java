package org.rtklib.java.ppprtk;

import org.rtklib.java.ambiguity.Lambda;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.kalman.KalmanFilter;
import org.rtklib.java.ppp.PppAmbFix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PppRtkAmbFix {
    private PppRtkAmbFix() {
    }

    private static final Logger LOG = LoggerFactory.getLogger(PppRtkAmbFix.class);

    public static void ppprtkAmbFix(Rtk rtk, Nav nav) {
        RtkConfig cfg = rtk.rtkConfig;
        if (cfg == null || !cfg.enablePppRtkAR) return;

        PrcOpt opt = rtk.opt;

        if (opt.ionoopt == Constants.IONOOPT_IFLC) {
            ppprtkAmbFixIf(rtk, nav);
        } else {
            ppprtkAmbFixUc(rtk, nav);
        }
    }

    /**
     * IFLC模式AR：x[IB]存储IF组合模糊度（米），不是整数，不能直接LAMBDA。
     * 必须用WL+NL两步固定（委托给PppAmbFix.pppAmbFixWlNl）。
     */
    private static void ppprtkAmbFixIf(Rtk rtk, Nav nav) {
        RtkConfig cfg = rtk.rtkConfig;
        int nx = rtk.nx;

        double[] xa = rtk.xa;
        if (xa == null || xa.length != nx) {
            xa = new double[nx];
        }
        System.arraycopy(rtk.x, 0, xa, 0, nx);

        int nFixed = PppAmbFix.pppAmbFixWlNl(rtk, null, xa, 0, 0, 0, nav);
        if (nFixed <= 0) {
            LOG.debug("PPP-RTK AR (IF): WL+NL fix failed");
            return;
        }

        rtk.xa = xa;
        if (rtk.Pa == null || rtk.Pa.length != nx * nx) {
            rtk.Pa = new double[nx * nx];
        }
        System.arraycopy(rtk.P, 0, rtk.Pa, 0, nx * nx);

        rtk.nfix++;

        if (cfg.enablePppRtkFixHold && rtk.nfix >= cfg.pppRtkFixHoldMinEpoch) {
            PppAmbFix.pppArFixHold(rtk, nav);
        }

        for (int i = 0; i < 3 && i < nx; i++) {
            rtk.sol.rr[i] = xa[i];
        }
        rtk.sol.stat = Constants.SOLQ_FIX;
        LOG.debug("PPP-RTK AR (IF): fixed {} ambiguities via WL+NL cascade", nFixed);
    }

    /**
     * 非IFLC模式（非组合）AR：x[IB]存储单频模糊度（米），除以λ后是整数周，可直接LAMBDA。
     */
    private static void ppprtkAmbFixUc(Rtk rtk, Nav nav) {
        RtkConfig cfg = rtk.rtkConfig;
        PrcOpt opt = rtk.opt;
        int nf = PppRtkCore.NF(opt);
        int nx = rtk.nx;

        int nb = 0;
        int[] satList = new int[Constants.MAXSAT * nf];
        int[] freqList = new int[Constants.MAXSAT * nf];
        int[] idxList = new int[Constants.MAXSAT * nf];

        for (int sat = 1; sat <= Constants.MAXSAT; sat++) {
            if (rtk.ssat[sat - 1].vs == 0) continue;
            if (rtk.ssat[sat - 1].azel[1] < opt.elmin) continue;

            int sys = SatUtils.satsys(sat, null);
            if ((sys & (Constants.SYS_GPS | Constants.SYS_GAL)) == 0) continue;

            for (int f = 0; f < nf; f++) {
                if (rtk.ssat[sat - 1].vsat[f] == 0) continue;
                if (rtk.ssat[sat - 1].lock[f] < opt.minlock) continue;

                int idx = PppRtkCore.IB(sat, f, opt);
                if (idx >= nx) continue;

                double ambVar = rtk.P[idx * nx + idx];
                if (ambVar <= 0.0 || ambVar > 1.0) continue;

                double freq = SatUtils.sat2freq(sat, rtk.ssat[sat - 1].code[f][0], nav);
                double lam = freq > 0 ? Constants.CLIGHT / freq : 0.0;
                if (lam == 0.0) continue;

                satList[nb] = sat;
                freqList[nb] = f;
                idxList[nb] = idx;
                nb++;
            }
        }

        if (nb < 4) {
            LOG.debug("PPP-RTK AR: too few ambiguities nb={}", nb);
            return;
        }

        double[] a = new double[nb];
        double[] Q = new double[nb * nb];
        for (int i = 0; i < nb; i++) {
            int idx = idxList[i];
            double freq = SatUtils.sat2freq(satList[i], rtk.ssat[satList[i] - 1].code[freqList[i]][0], nav);
            double lam = freq > 0 ? Constants.CLIGHT / freq : 0.0;
            if (lam == 0.0) { a[i] = 0.0; continue; }
            a[i] = rtk.x[idx] / lam;
            for (int j = 0; j < nb; j++) {
                Q[i * nb + j] = rtk.P[idx * nx + idxList[j]] / (lam * lam);
            }
        }

        int m = 2;
        double[] F = new double[nb * m];
        double[] s = new double[m];

        int info = Lambda.lambda(nb, m, a, Q, F, s);
        if (info != 0) {
            LOG.debug("PPP-RTK AR: LAMBDA failed info={}", info);
            return;
        }

        double ratio = (s[1] > 0) ? s[0] / s[1] : 999.0;
        double arRatio = cfg.pppRtkArRatio;

        LOG.debug("PPP-RTK AR: nb={} ratio={} threshold={}", nb, String.format("%.3f", ratio), String.format("%.3f", arRatio));

        if (ratio < arRatio) {
            LOG.debug("PPP-RTK AR: ratio test failed");
            return;
        }

        double[] xa = rtk.xa;
        double[] Pa = rtk.Pa;
        if (xa == null || xa.length != nx) {
            xa = new double[nx];
            Pa = new double[nx * nx];
        }
        System.arraycopy(rtk.x, 0, xa, 0, nx);
        System.arraycopy(rtk.P, 0, Pa, 0, nx * nx);

        for (int i = 0; i < nb; i++) {
            int idx = idxList[i];
            double freq = SatUtils.sat2freq(satList[i], rtk.ssat[satList[i] - 1].code[freqList[i]][0], nav);
            double lam = freq > 0 ? Constants.CLIGHT / freq : 0.0;
            if (lam == 0.0) continue;

            xa[idx] = F[i * m] * lam;
            for (int j = 0; j < nx; j++) {
                Pa[idx * nx + j] = 0.0;
                Pa[j * nx + idx] = 0.0;
            }
            Pa[idx * nx + idx] = 0.0;

            rtk.ssat[satList[i] - 1].fix[freqList[i]] = 1;
        }

        rtk.xa = xa;
        rtk.Pa = Pa;
        rtk.nfix++;

        if (cfg.enablePppRtkFixHold && rtk.nfix >= cfg.pppRtkFixHoldMinEpoch) {
            fixAndHold(rtk, nav, satList, freqList, idxList, nb);
        }

        for (int i = 0; i < 3 && i < nx; i++) {
            rtk.sol.rr[i] = xa[i];
        }
        rtk.sol.stat = Constants.SOLQ_FIX;
    }

    private static void fixAndHold(Rtk rtk, Nav nav, int[] satList, int[] freqList,
                                    int[] idxList, int nb) {
        RtkConfig cfg = rtk.rtkConfig;
        PrcOpt opt = rtk.opt;
        int nx = rtk.nx;
        double holdVar = cfg.pppRtkFixHoldVar;

        for (int i = 0; i < nb; i++) {
            int sat = satList[i];
            int f = freqList[i];
            int idx = idxList[i];
            if (idx >= nx) continue;

            double freq = SatUtils.sat2freq(sat, rtk.ssat[sat - 1].code[f][0], nav);
            double lam = freq > 0 ? Constants.CLIGHT / freq : 0.0;
            if (lam == 0.0) continue;

            double fixedAmb = rtk.xa[idx];
            double diff = rtk.x[idx] - fixedAmb;

            double[] vH = new double[nx];
            vH[idx] = 1.0;
            double[] vR = new double[]{holdVar};

            double[] v = new double[]{diff};
            KalmanFilter.update(rtk.x, rtk.P, vH, v, vR, nx, 1);

            rtk.ssat[sat - 1].fix[f] = 2;
        }

        rtk.holdambFlag = 1;
        LOG.debug("PPP-RTK: fix-and-hold applied for {} ambiguities", nb);
    }
}