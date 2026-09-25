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
    private static final double LAM_WL_BDS3 = Constants.CLIGHT / (FREQ_B1C - FREQ_B2A);
    private static final double LAM_NL_BDS3 = Constants.CLIGHT / (FREQ_B1C + FREQ_B2A);

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
            double ambVarCyc = LAM_B1C > 0 ? ambVar / (LAM_B1C * LAM_B1C) : ambVar;
            if (ambVar <= 0 || ambVarCyc > 16.0) continue;

            // Skip satellites without OSB data when using OSB mode
            if (nav.fcbFromOsb && nav.fcbWlByCode != null) {
                int code0 = rtk.ssat[sat - 1].code[0][0];
                int code1 = rtk.ssat[sat - 1].code[1][0];
                double osb0 = getOsb(nav, sat, code0);
                double osb1 = getOsb(nav, sat, code1);
                if (osb0 == 0.0 || osb1 == 0.0) {
                    LOG.debug("BDS-3 WL skip sat={} no OSB: code0={} osb0={} code1={} osb1={}",
                        sat, code0, osb0, code1, osb1);
                    continue;
                }
            }

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

            int code0 = rtk.ssat[sat - 1].code[0][0];
            int code1 = rtk.ssat[sat - 1].code[1][0];
            double mw = rtk.ssat[sat - 1].mw[0];
            double fcbWl = getWlFcb(nav, sat, code0, code1, rtk.sol.time.time);

            yWl[i] = mw / LAM_WL_BDS3 + (nav.fcbFromOsb ? -fcbWl : fcbWl);

            for (int j = 0; j < nb; j++) {
                int satJ = satList[j];
                int idxJ = PppCore.IB(satJ, 0, opt);
                QbWl[i * nb + j] = rtk.P[idx * nx + idxJ] / (LAM_WL_BDS3 * LAM_WL_BDS3);
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
            wlFixed[i] = (int) Math.round(bWl[i * 2]);
        }

        double[] yNl = new double[nb];
        double[] QbNl = new double[nb * nb];

        for (int i = 0; i < nb; i++) {
            int sat = satList[i];
            int idx = PppCore.IB(sat, 0, opt);

            int code0 = rtk.ssat[sat - 1].code[0][0];
            double nlFloat = rtk.x[idx] / LAM_NL_BDS3 - wlFixed[i] * FREQ_B2A / (FREQ_B1C - FREQ_B2A);
            double fcbNl = getNlFcb(nav, sat, code0, rtk.sol.time.time);

            yNl[i] = nlFloat + (nav.fcbFromOsb ? -fcbNl : fcbNl);

            for (int j = 0; j < nb; j++) {
                int satJ = satList[j];
                int idxJ = PppCore.IB(satJ, 0, opt);
                QbNl[i * nb + j] = rtk.P[idx * nx + idxJ] / (LAM_NL_BDS3 * LAM_NL_BDS3);
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

            double fixedNl = Math.round(bNl[i * 2]);
            double fixedAmb = fixedNl * LAM_NL_BDS3 + wlFixed[i] * FREQ_B2A * LAM_WL_BDS3 / (FREQ_B1C + FREQ_B2A);

            rtk.x[idx] = fixedAmb;
            PppCore.initx(rtk.x, rtk.P, nx, fixedAmb, 0.0, idx);
            rtk.ssat[sat - 1].fix[0] = 1;

            if (xa != null) xa[idx] = fixedAmb;
        }

        LOG.debug("BDS-3 PPP-AR: fixed {} ambiguities (WL ratio={}, NL ratio={})",
                nb, String.format("%.2f", wlRatio), String.format("%.2f", nlRatio));
        return nb;
    }

    // B2b/B2I fallback: same as PppAmbFix.BDS_B2B_EQUIV, see comments there.
    private static final int[] BDS_B2B_EQUIV = {Constants.CODE_L7I, Constants.CODE_L7D};

    private static double getOsb(Nav nav, int sat, int code) {
        if (code < 0 || code > Constants.MAXCODE) return 0.0;
        double osb = nav.fcbWlByCode[sat - 1][code];
        if (osb != 0.0) return osb;
        if (code == Constants.CODE_L7I || code == Constants.CODE_L7D) {
            for (int ec : BDS_B2B_EQUIV) {
                if (ec != code && ec >= 0 && ec <= Constants.MAXCODE) {
                    double v = nav.fcbWlByCode[sat - 1][ec];
                    if (v != 0.0) return v;
                }
            }
        }
        return 0.0;
    }

    private static double getWlFcb(Nav nav, int sat, int code0, int code1, double time) {
        if (nav.fcbFromOsb) {
            if (nav.fcbWlByCode == null) return 0.0;
            if (sat < 1 || sat > Constants.MAXSAT) return 0.0;
            double osbL1 = getOsb(nav, sat, code0);
            double osbL2 = getOsb(nav, sat, code1);
            double wlFcb = osbL1 / LAM_B1C - osbL2 / LAM_B2A;
            String prn = org.rtklib.java.common.SatUtils.satno2id(sat);
            System.out.printf(java.util.Locale.US,
                "FCB-DIAG-BDS3 prn=%s sat=%d code0=%d code1=%d osbL1=%.6f osbL2=%.6f wlFcb=%.6f%n",
                prn, sat, code0, code1, osbL1, osbL2, wlFcb);
            return wlFcb;
        }
        return org.rtklib.java.ephemeris.FcbReader.getFcbWl(nav, sat, time);
    }

    private static double getNlFcb(Nav nav, int sat, int code0, double time) {
        if (nav.fcbFromOsb) {
            if (nav.fcbWlByCode == null) return 0.0;
            if (sat < 1 || sat > Constants.MAXSAT) return 0.0;
            double osbL1 = getOsb(nav, sat, code0);
            return osbL1 / LAM_NL_BDS3;
        }
        return org.rtklib.java.ephemeris.FcbReader.getFcbNl(nav, sat, time);
    }
}