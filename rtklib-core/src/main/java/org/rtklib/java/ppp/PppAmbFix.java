package org.rtklib.java.ppp;

import org.rtklib.java.ambiguity.Lambda;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.ephemeris.FcbReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class PppAmbFix {
    private PppAmbFix() {
    }

    private static final Logger LOG = LoggerFactory.getLogger(PppAmbFix.class);

    public static int pppAmbFixWlNl(Rtk rtk, double[] bias, double[] xa,
                                     int gps, int glo, int sbs, Nav nav) {
        RtkConfig cfg = rtk.rtkConfig;
        if (!cfg.enablePppAR) return -1;
        LOG.info("PPP-AR: pppAmbFixWlNl called, epoch={}, nx={}", rtk.epoch, rtk.nx);

        PrcOpt opt = rtk.opt;
        int nf = PppCore.NF(opt);
        int nx = rtk.nx;

        int nbWl = 0;
        int[] satList = new int[Constants.MAXSAT * nf];
        int[] freqList = new int[Constants.MAXSAT * nf];

        for (int sat = 1; sat <= Constants.MAXSAT; sat++) {
            if (rtk.ssat[sat - 1].vs == 0) continue;
            if (rtk.ssat[sat - 1].azel[1] < opt.elmin) continue;

            int sys = SatUtils.satsys(sat, null);
            if ((sys & (Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_CMP)) == 0) continue;

            int f = 0;
            int idx = PppCore.IB(sat, f, opt);
            if (idx >= nx) continue;

            double ambVar = rtk.P[idx * nx + idx];
            if (ambVar <= 0 || ambVar > 1.0) continue;

            satList[nbWl] = sat;
            freqList[nbWl] = f;
            nbWl++;
        }

        if (nbWl < 4) {
            LOG.info("PPP-AR: too few valid ambiguities: nbWl={}", nbWl);
            return -1;
        }

        double[] yWl = new double[nbWl];
        double[] QbWl = new double[nbWl * nbWl];

        for (int i = 0; i < nbWl; i++) {
            int sat = satList[i];
            int idx = PppCore.IB(sat, 0, opt);

            double freq1 = SatUtils.sat2freq(sat, 0, nav);
            double freq2 = SatUtils.sat2freq(sat, 1, nav);
            if (freq1 == 0.0 || freq2 == 0.0) {
                freq1 = Constants.FREQL1;
                freq2 = Constants.FREQL2;
            }

            double lam1 = Constants.CLIGHT / freq1;
            double lam2 = Constants.CLIGHT / freq2;
            double lamWl = Constants.CLIGHT / (freq1 - freq2);

            double nlFloat = rtk.x[idx] / lam1;

            double fcbWl = getWlFcb(nav, sat, lam1, lam2, rtk.sol.time.time);

            yWl[i] = nlFloat * lam1 / lamWl + fcbWl;

            for (int j = 0; j < nbWl; j++) {
                int satJ = satList[j];
                int idxJ = PppCore.IB(satJ, 0, opt);
                QbWl[i * nbWl + j] = rtk.P[idx * nx + idxJ] * (lam1 / lamWl) * (lam1 / lamWl);
            }
        }

        double[] bWl = new double[nbWl * 2];
        double[] sWl = new double[2];
        int info = Lambda.lambda(nbWl, 2, yWl, QbWl, bWl, sWl);

        if (info != 0) {
            LOG.info("PPP-AR: WL LAMBDA failed: info={}", info);
            return -1;
        }

        double wlRatio = sWl[0] > 0 ? sWl[1] / sWl[0] : 0.0;
        LOG.info("PPP-AR: WL nb={} ratio={}", nbWl, String.format("%.2f", wlRatio));

        if (wlRatio < cfg.pppArRatioWl) {
            LOG.info("PPP-AR: WL ratio too low: {} < {}", String.format("%.2f", wlRatio), cfg.pppArRatioWl);
            return -1;
        }

        int[] wlFixed = new int[nbWl];
        for (int i = 0; i < nbWl; i++) {
            wlFixed[i] = (int) Math.round(bWl[i]);
        }

        int nbNl = 0;
        double[] yNl = new double[nbWl];
        double[] QbNl = new double[nbWl * nbWl];

        for (int i = 0; i < nbWl; i++) {
            int sat = satList[i];
            int idx = PppCore.IB(sat, 0, opt);

            double freq1 = SatUtils.sat2freq(sat, 0, nav);
            if (freq1 == 0.0) freq1 = Constants.FREQL1;
            double lam1 = Constants.CLIGHT / freq1;

            double freq2 = SatUtils.sat2freq(sat, 1, nav);
            if (freq2 == 0.0) freq2 = Constants.FREQL2;
            double lamWl = Constants.CLIGHT / (freq1 - freq2);

            double nlFloat = (rtk.x[idx] / lam1 - wlFixed[i] * lamWl / lam1);

            double fcbNl = getNlFcb(nav, sat, lam1, rtk.sol.time.time);

            yNl[i] = nlFloat + fcbNl;

            for (int j = 0; j < nbWl; j++) {
                int satJ = satList[j];
                int idxJ = PppCore.IB(satJ, 0, opt);
                QbNl[i * nbWl + j] = rtk.P[idx * nx + idxJ] / (lam1 * lam1);
            }
            nbNl++;
        }

        if (nbNl < 4) {
            LOG.info("PPP-AR: too few NL ambiguities: nbNl={}", nbNl);
            return -1;
        }

        double[] bNl = new double[nbNl * 2];
        double[] sNl = new double[2];
        info = Lambda.lambda(nbNl, 2, yNl, QbNl, bNl, sNl);

        if (info != 0) {
            LOG.info("PPP-AR: NL LAMBDA failed: info={}", info);
            return -1;
        }

        double nlRatio = sNl[0] > 0 ? sNl[1] / sNl[0] : 0.0;
        rtk.sol.ratio = (float) nlRatio;
        LOG.info("PPP-AR: NL nb={} ratio={}", nbNl, String.format("%.2f", nlRatio));

        if (nlRatio < cfg.pppArRatioNl) {
            LOG.info("PPP-AR: NL ratio too low: {} < {}", String.format("%.2f", nlRatio), cfg.pppArRatioNl);
            return -1;
        }

        for (int i = 0; i < nbNl; i++) {
            int sat = satList[i];
            int f = freqList[i];
            int idx = PppCore.IB(sat, f, opt);

            double freq1 = SatUtils.sat2freq(sat, 0, nav);
            if (freq1 == 0.0) freq1 = Constants.FREQL1;
            double lam1 = Constants.CLIGHT / freq1;

            double fixedNl = Math.round(bNl[i]);
            double fixedAmb = (wlFixed[i] * lam1 + fixedNl * lam1);

            rtk.x[idx] = fixedAmb;
            PppCore.initx(rtk.x, rtk.P, nx, fixedAmb, 0.0, idx);

            if (xa != null) {
                xa[idx] = fixedAmb;
            }

            rtk.ssat[sat - 1].fix[f] = 1;
        }

        LOG.info("PPP-AR: fixed {} ambiguities (WL ratio={}, NL ratio={})",
                nbNl, String.format("%.2f", wlRatio), String.format("%.2f", nlRatio));

        return nbNl;
    }

    /**
     * PPP-AR Fix-and-Hold策略：将已固定模糊度的方差收紧到极小值。
     *
     * <p>原理：模糊度固定后，将其协方差从浮点值(~1.0)收紧到pppArFixHoldVar(~1e-6)，
     * 使后续历元中该模糊度不会被重新浮点化，从而"锁定"固定解。
     *
     * <p>触发条件：rtk.sol.stat == SOLQ_FIX（当前历元已成功固定）
     * 安全保护：仅收紧方差 > pppArFixHoldVar 的模糊度（避免重复收紧）
     *
     * <p>对应C版：RTKLIB RTK模式有类似策略，PPP模式未实现，本模块为v2.3.0新增
     */
    public static void pppArFixHold(Rtk rtk, Nav nav) {
        RtkConfig cfg = rtk.rtkConfig;
        if (!cfg.enablePppArFixHold) return;

        PrcOpt opt = rtk.opt;
        int nx = rtk.nx;
        int nf = PppCore.NF(opt);
        int nFixed = 0;

        for (int sat = 1; sat <= Constants.MAXSAT; sat++) {
            for (int f = 0; f < nf; f++) {
                if (rtk.ssat[sat - 1].fix[f] != 1) continue;

                int idx = PppCore.IB(sat, f, opt);
                if (idx >= nx) continue;

                double ambVar = rtk.P[idx * nx + idx];
                if (ambVar > cfg.pppArFixHoldVar) {
                    PppCore.initx(rtk.x, rtk.P, nx, rtk.x[idx], cfg.pppArFixHoldVar, idx);
                    nFixed++;
                }
            }
        }

        if (nFixed > 0) {
            LOG.debug("PPP-AR Fix-and-Hold: tightened {} ambiguities", nFixed);
        }
    }

    /**
     * PPP部分模糊度固定（Partial AR）。
     *
     * <p>当全模糊度固定比率检验失败时，尝试固定一个子集：
     * 1. 按模糊度方差从小到大排序（方差小=更可靠）
     * 2. 从全部卫星开始，逐步剔除方差最大的模糊度
     * 3. 对每个子集运行LAMBDA搜索+比率检验
     * 4. 选择通过比率检验且固定数最多的子集
     *
     * <p>约束：
     * - 子集大小 >= pppPartialArMinSats（最少固定卫星数）
     * - 搜索次数 <= pppPartialArMaxTries（防止组合爆炸）
     * - 比率 >= pppPartialArMinRatio（可靠性阈值）
     *
     * <p>对应C版：RTKLIB未实现Partial AR，本模块为v2.3.0新增
     */
    public static int pppPartialAR(Rtk rtk, double[] bias, double[] xa,
                                    int gps, int glo, int sbs, Nav nav) {
        RtkConfig cfg = rtk.rtkConfig;
        if (!cfg.enablePppPartialAR) return -1;

        PrcOpt opt = rtk.opt;
        int nf = PppCore.NF(opt);
        int nx = rtk.nx;

        int nb = 0;
        int[] satList = new int[Constants.MAXSAT * nf];
        int[] freqList = new int[Constants.MAXSAT * nf];
        double[] ambFloat = new double[Constants.MAXSAT * nf];
        double[] ambVar = new double[Constants.MAXSAT * nf];

        for (int sat = 1; sat <= Constants.MAXSAT; sat++) {
            if (rtk.ssat[sat - 1].vs == 0) continue;
            if (rtk.ssat[sat - 1].azel[1] < opt.elmin) continue;

            int sys = SatUtils.satsys(sat, null);
            if ((sys & (Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_CMP)) == 0) continue;

            int f = 0;
            int idx = PppCore.IB(sat, f, opt);
            if (idx >= nx) continue;

            double var = rtk.P[idx * nx + idx];
            if (var <= 0 || var > 1.0) continue;

            satList[nb] = sat;
            freqList[nb] = f;
            ambFloat[nb] = rtk.x[idx];
            ambVar[nb] = var;
            nb++;
        }

        if (nb < cfg.pppPartialArMinSats) return -1;

        double[] y = new double[nb];
        double[] Q = new double[nb * nb];
        for (int i = 0; i < nb; i++) {
            int idx = PppCore.IB(satList[i], freqList[i], opt);
            y[i] = ambFloat[i];
            for (int j = 0; j < nb; j++) {
                int idxJ = PppCore.IB(satList[j], freqList[j], opt);
                Q[i * nb + j] = rtk.P[idx * nx + idxJ];
            }
        }

        double[] b = new double[nb * 2];
        double[] s = new double[2];
        int info = Lambda.lambda(nb, 2, y, Q, b, s);
        if (info != 0) return -1;

        double ratio = s[0] > 0 ? s[1] / s[0] : 0.0;
        if (ratio >= cfg.pppPartialArMinRatio) {
            for (int i = 0; i < nb; i++) {
                int sat = satList[i];
                int f = freqList[i];
                int idx = PppCore.IB(sat, f, opt);
                double fixedAmb = Math.round(b[i]);
                rtk.x[idx] = fixedAmb;
                PppCore.initx(rtk.x, rtk.P, nx, fixedAmb, 0.0, idx);
                rtk.ssat[sat - 1].fix[f] = 1;
                if (xa != null) xa[idx] = fixedAmb;
            }
            LOG.debug("PPP Partial AR: all {} fixed, ratio={}", nb, String.format("%.2f", ratio));
            return nb;
        }

        int nBest = 0;
        int[] bestSubset = new int[nb];
        double bestRatio = 0.0;

        for (int nTry = nb - 1; nTry >= cfg.pppPartialArMinSats; nTry--) {
            if (nb - nTry > cfg.pppPartialArMaxTries) break;

            int[] perm = new int[nb];
            for (int i = 0; i < nb; i++) perm[i] = i;
            sortByVariance(perm, ambVar, nb);

            double[] ySub = new double[nTry];
            double[] QSub = new double[nTry * nTry];
            for (int i = 0; i < nTry; i++) {
                ySub[i] = y[perm[i]];
                for (int j = 0; j < nTry; j++) {
                    QSub[i * nTry + j] = Q[perm[i] * nb + perm[j]];
                }
            }

            double[] bSub = new double[nTry * 2];
            double[] sSub = new double[2];
            info = Lambda.lambda(nTry, 2, ySub, QSub, bSub, sSub);
            if (info != 0) continue;

            double r = sSub[0] > 0 ? sSub[1] / sSub[0] : 0.0;
            if (r >= cfg.pppPartialArMinRatio && r > bestRatio) {
                bestRatio = r;
                nBest = nTry;
                System.arraycopy(perm, 0, bestSubset, 0, nTry);
            }
        }

        if (nBest >= cfg.pppPartialArMinSats) {
            for (int i = 0; i < nBest; i++) {
                int sat = satList[bestSubset[i]];
                int f = freqList[bestSubset[i]];
                int idx = PppCore.IB(sat, f, opt);
                double fixedAmb = Math.round(ambFloat[bestSubset[i]]);
                rtk.x[idx] = fixedAmb;
                PppCore.initx(rtk.x, rtk.P, nx, fixedAmb, 0.0, idx);
                rtk.ssat[sat - 1].fix[f] = 1;
                if (xa != null) xa[idx] = fixedAmb;
            }
            LOG.debug("PPP Partial AR: {}/{} fixed, ratio={}", nBest, nb, String.format("%.2f", bestRatio));
            return nBest;
        }

        LOG.debug("PPP Partial AR: no valid subset found");
        return -1;
    }

    private static void sortByVariance(int[] perm, double[] var, int n) {
        for (int i = 0; i < n - 1; i++) {
            for (int j = i + 1; j < n; j++) {
                if (var[perm[j]] < var[perm[i]]) {
                    int tmp = perm[i];
                    perm[i] = perm[j];
                    perm[j] = tmp;
                }
            }
        }
    }

    private static double getWlFcb(Nav nav, int sat, double lam1, double lam2, double time) {
        if (nav.fcbWl == null) return 0.0;
        if (nav.fcbFromOsb) {
            if (sat < 1 || sat > nav.fcbWl.length) return 0.0;
            double osbL1 = nav.fcbWl[sat - 1][0];
            double osbL2 = (nav.fcbWl[sat - 1].length > 1) ? nav.fcbWl[sat - 1][1] : 0.0;
            return osbL1 / lam1 - osbL2 / lam2;
        }
        return FcbReader.getFcbWl(nav, sat, time);
    }

    private static double getNlFcb(Nav nav, int sat, double lam1, double time) {
        if (nav.fcbWl == null) return 0.0;
        if (nav.fcbFromOsb) {
            if (sat < 1 || sat > nav.fcbWl.length) return 0.0;
            double osbL1 = nav.fcbWl[sat - 1][0];
            return osbL1 / lam1;
        }
        return FcbReader.getFcbNl(nav, sat, time);
    }
}