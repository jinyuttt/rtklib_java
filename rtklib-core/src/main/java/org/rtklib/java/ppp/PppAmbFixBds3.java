package org.rtklib.java.ppp;

import org.rtklib.java.ambiguity.Lambda;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * BDS-3 PPP-AR模糊度固定（B1C/B2a频率）。
 *
 * <p>BDS-3新信号B1C(1575.42MHz)和B2a(1176.45MHz)的模糊度固定，
 * 仅针对PRN 19~46的BDS-3 MEO/IGSO卫星。
 *
 * <p>固定策略：与GPS/GAL的WL+NL策略一致
 * 1. 宽巷(WL)模糊度：λ_WL = c/(f_B2a - f_B1C)，用LAMBDA搜索+比率检验
 * 2. 窄巷(NL)模糊度：从浮点模糊度扣除宽巷固定值，用LAMBDA搜索+比率检验
 * 3. 固定后更新状态向量和协方差
 *
 * <p>对应C版：RTKLIB未实现BDS-3 PPP-AR，本模块为v2.3.0新增
 */
public final class PppAmbFixBds3 {
    private PppAmbFixBds3() {}

    private static final Logger LOG = LoggerFactory.getLogger(PppAmbFixBds3.class);

    // BDS-3 B1C频率：f = 1.5 × 10.23MHz = 1575.42MHz（与GPS L1同频）
    private static final double FREQ_B1C = 1.5 * 10.23e6;
    // BDS-3 B2a频率：f = 2.5 × 10.23MHz = 1176.45MHz（与GPS L5同频）
    private static final double FREQ_B2A = 2.5 * 10.23e6;
    // 对应波长
    private static final double LAM_B1C = Constants.CLIGHT / FREQ_B1C;
    private static final double LAM_B2A = Constants.CLIGHT / FREQ_B2A;
    // 宽巷波长：λ_WL = c / (f_B2a - f_B1C)
    private static final double LAM_WL_BDS3 = Constants.CLIGHT / (FREQ_B2A - FREQ_B1C);

    public static int pppAmbFixBds3(Rtk rtk, double[] bias, double[] xa, Nav nav) {
        RtkConfig cfg = rtk.rtkConfig;
        if (!cfg.enableBds3PppAR) return -1;

        PrcOpt opt = rtk.opt;
        int nx = rtk.nx;
        int nf = PppCore.NF(opt);

        int nb = 0;
        int[] satList = new int[Constants.MAXSAT];

        for (int sat = 1; sat <= Constants.MAXSAT; sat++) {
            int[] prn = new int[1];
            int sys = SatUtils.satsys(sat, prn);
            if (sys != Constants.SYS_CMP) continue;
            if (prn[0] < 19 || prn[0] > 46) continue;

            if (rtk.ssat[sat - 1].vs == 0) continue;
            if (rtk.ssat[sat - 1].azel[1] < opt.elmin) continue;

            int idx = PppCore.IB(sat, 0, opt);
            if (idx >= nx) continue;

            double ambVar = rtk.P[idx * nx + idx];
            if (ambVar <= 0 || ambVar > 1.0) continue;

            satList[nb] = sat;
            nb++;
        }

        if (nb < 4) {
            LOG.debug("BDS-3 PPP-AR: too few BDS-3 satellites: {}", nb);
            return -1;
        }

        double[] yWl = new double[nb];
        double[] QbWl = new double[nb * nb];

        for (int i = 0; i < nb; i++) {
            int sat = satList[i];
            int idx = PppCore.IB(sat, 0, opt);

            double nlFloat = rtk.x[idx] / LAM_B1C;
            double fcbWl = getWlFcb(nav, sat, rtk.sol.time.time);

            yWl[i] = nlFloat * LAM_B1C / LAM_WL_BDS3 + fcbWl;

            for (int j = 0; j < nb; j++) {
                int satJ = satList[j];
                int idxJ = PppCore.IB(satJ, 0, opt);
                QbWl[i * nb + j] = rtk.P[idx * nx + idxJ] * (LAM_B1C / LAM_WL_BDS3) * (LAM_B1C / LAM_WL_BDS3);
            }
        }

        double[] bWl = new double[nb * 2];
        double[] sWl = new double[2];
        int info = Lambda.lambda(nb, 2, yWl, QbWl, bWl, sWl);
        if (info != 0) return -1;

        double wlRatio = sWl[0] > 0 ? sWl[1] / sWl[0] : 0.0;
        if (wlRatio < cfg.pppArRatioWl) {
            LOG.debug("BDS-3 PPP-AR: WL ratio too low: {}", String.format("%.2f", wlRatio));
            return -1;
        }

        int[] wlFixed = new int[nb];
        for (int i = 0; i < nb; i++) {
            wlFixed[i] = (int) Math.round(bWl[i]);
        }

        double[] yNl = new double[nb];
        double[] QbNl = new double[nb * nb];

        for (int i = 0; i < nb; i++) {
            int sat = satList[i];
            int idx = PppCore.IB(sat, 0, opt);

            double nlFloat = (rtk.x[idx] / LAM_B1C - wlFixed[i] * LAM_WL_BDS3 / LAM_B1C);
            double fcbNl = getNlFcb(nav, sat, rtk.sol.time.time);

            yNl[i] = nlFloat + fcbNl;

            for (int j = 0; j < nb; j++) {
                int satJ = satList[j];
                int idxJ = PppCore.IB(satJ, 0, opt);
                QbNl[i * nb + j] = rtk.P[idx * nx + idxJ] / (LAM_B1C * LAM_B1C);
            }
        }

        double[] bNl = new double[nb * 2];
        double[] sNl = new double[2];
        info = Lambda.lambda(nb, 2, yNl, QbNl, bNl, sNl);
        if (info != 0) return -1;

        double nlRatio = sNl[0] > 0 ? sNl[1] / sNl[0] : 0.0;
        if (nlRatio < cfg.pppArRatioNl) {
            LOG.debug("BDS-3 PPP-AR: NL ratio too low: {}", String.format("%.2f", nlRatio));
            return -1;
        }

        for (int i = 0; i < nb; i++) {
            int sat = satList[i];
            int idx = PppCore.IB(sat, 0, opt);

            double fixedNl = Math.round(bNl[i]);
            double fixedAmb = wlFixed[i] * LAM_B1C + fixedNl * LAM_B1C;

            rtk.x[idx] = fixedAmb;
            PppCore.initx(rtk.x, rtk.P, nx, fixedAmb, 0.0, idx);
            rtk.ssat[sat - 1].fix[0] = 1;

            if (xa != null) xa[idx] = fixedAmb;
        }

        LOG.debug("BDS-3 PPP-AR: fixed {} ambiguities (WL ratio={}, NL ratio={})",
                nb, String.format("%.2f", wlRatio), String.format("%.2f", nlRatio));
        return nb;
    }

    private static double getWlFcb(Nav nav, int sat, double time) {
        if (nav.fcbWl == null) return 0.0;
        if (nav.fcbFromOsb) {
            if (sat < 1 || sat > nav.fcbWl.length) return 0.0;
            double osbL1 = nav.fcbWl[sat - 1][0];
            double osbL2 = (nav.fcbWl[sat - 1].length > 1) ? nav.fcbWl[sat - 1][1] : 0.0;
            return osbL1 / LAM_B1C - osbL2 / LAM_B2A;
        }
        return org.rtklib.java.ephemeris.FcbReader.getFcbWl(nav, sat, time);
    }

    private static double getNlFcb(Nav nav, int sat, double time) {
        if (nav.fcbWl == null) return 0.0;
        if (nav.fcbFromOsb) {
            if (sat < 1 || sat > nav.fcbWl.length) return 0.0;
            double osbL1 = nav.fcbWl[sat - 1][0];
            return osbL1 / LAM_B1C;
        }
        return org.rtklib.java.ephemeris.FcbReader.getFcbNl(nav, sat, time);
    }
}