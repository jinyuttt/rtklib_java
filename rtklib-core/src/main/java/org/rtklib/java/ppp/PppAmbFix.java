package org.rtklib.java.ppp;

import org.rtklib.java.ambiguity.Lambda;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.ephemeris.FcbReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

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

        int nSkipVar = 0, nSkipVs = 0, nSkipSys = 0, nSkipIdx = 0, nSkipOsb = 0, nSkipMw = 0, nSkipWlSig = 0;
        double minVar = Double.MAX_VALUE, maxVar = 0;
        int nSigLt01 = 0, nSig01to1 = 0, nSig1to10 = 0, nSigGt10 = 0;
        int[] diagEpochs = {1, 2, 3, 5, 10, 20, 40, 80, 160, 320, 640, 1280, 2560};
        boolean detailedDiag = false;
        for (int de : diagEpochs) { if (rtk.epoch == de) { detailedDiag = true; break; } }
        for (int sat = 1; sat <= Constants.MAXSAT; sat++) {
            if (rtk.ssat[sat - 1].vs == 0) { nSkipVs++; continue; }
            if (rtk.ssat[sat - 1].azel[1] < opt.elmin) { nSkipVs++; continue; }

            int sys = SatUtils.satsys(sat, null);
            if ((sys & (Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_CMP)) == 0) { nSkipSys++; continue; }

            int f = 0;
            int idx = PppCore.IB(sat, f, opt);
            if (idx >= nx) { nSkipIdx++; continue; }

            double ambVar = rtk.P[idx * nx + idx];
            double ambVal = rtk.x[idx];
            if (ambVar > 0 && ambVar < minVar) minVar = ambVar;
            if (ambVar > maxVar) maxVar = ambVar;

            if (ambVar > 0) {
                double sigM = Math.sqrt(ambVar);
                if (sigM < 0.1) nSigLt01++;
                else if (sigM < 1.0) nSig01to1++;
                else if (sigM < 10.0) nSig1to10++;
                else nSigGt10++;
            }

            if (detailedDiag) {
                double freq1 = SatUtils.sat2freq(sat, rtk.ssat[sat - 1].code[0][0], nav);
                double lam1 = freq1 > 0 ? Constants.CLIGHT / freq1 : 0.19;
                double freq2 = SatUtils.sat2freq(sat, rtk.ssat[sat - 1].code[1][0], nav);
                double lamWl = (freq1 > 0 && freq2 > 0) ? Constants.CLIGHT / (freq1 - freq2) : 0.862;
                double ambCycles = lam1 > 0 ? ambVal / lam1 : 0;
                double ambVarCycles = lam1 > 0 ? ambVar / (lam1 * lam1) : 0;
                double sigWlCyc = lamWl > 0 ? Math.sqrt(Math.max(0, ambVar)) / lamWl : 0;
                String sysStr = sys == Constants.SYS_GPS ? "G" : sys == Constants.SYS_GAL ? "E" : sys == Constants.SYS_CMP ? "C" : "?";
                LOG.info(String.format(Locale.US,
                    "WL-DIAG sat=%d sys=%s x=%.4fm (%.2fcyc) P=%.4em² (%.4ecyc²) sig_m=%.4f sig_wl=%.2fcyc idx=%d",
                    sat, sysStr, ambVal, ambCycles, ambVar, ambVarCycles,
                    Math.sqrt(Math.max(0, ambVar)), sigWlCyc, idx));
            }

            double freq1 = SatUtils.sat2freq(sat, rtk.ssat[sat - 1].code[0][0], nav);
            double lam1 = freq1 > 0 ? Constants.CLIGHT / freq1 : 0.19;
            double ambVarCyc = lam1 > 0 ? ambVar / (lam1 * lam1) : ambVar;
            if (ambVar <= 0 || ambVarCyc > 16.0) { nSkipVar++; continue; }

            // Skip satellites without valid MW (accumulated or single-epoch fallback)
            boolean hasWlAccum = rtk.ssat[sat - 1].niwl >= 1 && rtk.ssat[sat - 1].rw > 0;
            double mwRaw = rtk.ssat[sat - 1].mw[0];
            if (!hasWlAccum && mwRaw == 0.0) { nSkipMw++; continue; }

            // WL sigma quality control (PRIDE-PPPAR: 3*sigma > 0.2 cycles → reject)
            if (hasWlAccum) {
                double wlMean = rtk.ssat[sat - 1].xrwl / rtk.ssat[sat - 1].rw;
                double wlVar = rtk.ssat[sat - 1].xswl / rtk.ssat[sat - 1].rw - wlMean * wlMean;
                double wlSigma = rtk.ssat[sat - 1].niwl > 1 && wlVar > 0
                    ? Math.sqrt(wlVar) / Math.sqrt(rtk.ssat[sat - 1].niwl - 1)
                    : 999.0;
                if (wlSigma * 3.0 > 0.2) { nSkipWlSig++; continue; }
            }

            // Skip satellites without OSB data when using OSB mode
            if (nav.fcbFromOsb && nav.fcbWlByCode != null) {
                int code0 = rtk.ssat[sat - 1].code[0][0];
                int code1 = rtk.ssat[sat - 1].code[1][0];
                double osb0 = getOsb(nav, sat, code0);
                double osb1 = getOsb(nav, sat, code1);
                if (osb0 == 0.0 || osb1 == 0.0) {
                    nSkipOsb++;
                    LOG.debug("WL skip sat={} no OSB: code0={} osb0={} code1={} osb1={}",
                        sat, code0, osb0, code1, osb1);
                    continue;
                }
            }

            satList[nbWl] = sat;
            freqList[nbWl] = f;
            nbWl++;
        }
        int totalValid = nbWl + nSkipVar + nSkipOsb + nSkipMw + nSkipWlSig;
        LOG.info(String.format(Locale.US,
            "WL-DIAG epoch=%d valid=%d nbWl=%d skipVar=%d skipOsb=%d skipMw=%d skipWlSig=%d skipVs=%d skipSys=%d skipIdx=%d minVar=%s maxVar=%s | sig<0.1m=%d sig0.1~1=%d sig1~10=%d sig>10=%d",
            rtk.epoch, totalValid, nbWl, nSkipVar, nSkipOsb, nSkipMw, nSkipWlSig, nSkipVs, nSkipSys, nSkipIdx,
            minVar < Double.MAX_VALUE ? String.format("%.4e", minVar) : "N/A",
            maxVar > 0 ? String.format("%.4e", maxVar) : "N/A",
            nSigLt01, nSig01to1, nSig1to10, nSigGt10));

        if (nbWl < 4) {
            LOG.info("PPP-AR: too few valid ambiguities: nbWl={}", nbWl);
            return -1;
        }

        double[] yWl = new double[nbWl];
        double[] QbWl = new double[nbWl * nbWl];

        for (int i = 0; i < nbWl; i++) {
            int sat = satList[i];
            int idx = PppCore.IB(sat, 0, opt);

            int code0 = rtk.ssat[sat - 1].code[0][0];
            int code1 = rtk.ssat[sat - 1].code[1][0];
            double freq1 = SatUtils.sat2freq(sat, code0, nav);
            double freq2 = SatUtils.sat2freq(sat, code1, nav);
            if (freq1 == 0.0 || freq2 == 0.0) {
                freq1 = Constants.FREQL1;
                freq2 = Constants.FREQL2;
            }

            double lam1 = Constants.CLIGHT / freq1;
            double lam2 = Constants.CLIGHT / freq2;
            double lamWl = Constants.CLIGHT / (freq1 - freq2);

            double mw = rtk.ssat[sat - 1].mw[0];
            double fcbWl = getWlFcb(nav, sat, code0, code1, lam1, lam2, rtk.sol.time.time);

            double wlMean = rtk.ssat[sat - 1].rw > 0
                ? rtk.ssat[sat - 1].xrwl / rtk.ssat[sat - 1].rw : mw / lamWl;
            yWl[i] = wlMean + (nav.fcbFromOsb ? -fcbWl : fcbWl);

            if (i < 12) {
                double wlRound = Math.round(yWl[i]);
                double wlFrac = yWl[i] - wlRound;
                double wlSig = 999.0;
                if (rtk.ssat[sat - 1].rw > 0 && rtk.ssat[sat - 1].niwl > 1) {
                    double wVar = rtk.ssat[sat - 1].xswl / rtk.ssat[sat - 1].rw - wlMean * wlMean;
                    if (wVar > 0) wlSig = Math.sqrt(wVar) / Math.sqrt(rtk.ssat[sat - 1].niwl - 1);
                }
                LOG.info(String.format(Locale.US,
                    "WL-SAT sat=%d wlMean=%.4f niwl=%d wlSig=%.4f fcbWl=%.4f yWl=%.4f round=%.0f frac=%.4f",
                    sat, wlMean, rtk.ssat[sat - 1].niwl, wlSig, fcbWl, yWl[i], wlRound, wlFrac));
            }

            for (int j = 0; j < nbWl; j++) {
                int satJ = satList[j];
                int idxJ = PppCore.IB(satJ, 0, opt);
                QbWl[i * nbWl + j] = rtk.P[idx * nx + idxJ] / (lamWl * lamWl);
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
        int nWlFixed = 0;
        for (int i = 0; i < nbWl; i++) {
            wlFixed[i] = (int) Math.round(bWl[i * 2]);
            double wlFrac = Math.abs(yWl[i] - wlFixed[i]);
            if (wlFrac < 0.25) nWlFixed++;
        }
        LOG.info(String.format(Locale.US,
            "PPP-AR: WL fixed stats: nbWl=%d nWlFixed=%d(%.0f%%) ratio=%.2f",
            nbWl, nWlFixed, nbWl > 0 ? 100.0 * nWlFixed / nbWl : 0, wlRatio));

        int nbNl = 0;
        double[] yNl = new double[nbWl];
        double[] QbNl = new double[nbWl * nbWl];

        for (int i = 0; i < nbWl; i++) {
            int sat = satList[i];
            int idx = PppCore.IB(sat, 0, opt);

            int code0 = rtk.ssat[sat - 1].code[0][0];
            int code1 = rtk.ssat[sat - 1].code[1][0];
            double freq1 = SatUtils.sat2freq(sat, code0, nav);
            if (freq1 == 0.0) freq1 = Constants.FREQL1;
            double lam1 = Constants.CLIGHT / freq1;

            double freq2 = SatUtils.sat2freq(sat, code1, nav);
            if (freq2 == 0.0) freq2 = Constants.FREQL2;
            double lamWl = Constants.CLIGHT / (freq1 - freq2);

            double lamNl = Constants.CLIGHT / (freq1 + freq2);
            double nlFloat = rtk.x[idx] / lamNl - wlFixed[i] * freq2 / (freq1 - freq2);

            double fcbNl = getNlFcb(nav, sat, code0, lamNl, rtk.sol.time.time);

            yNl[i] = nlFloat + (nav.fcbFromOsb ? -fcbNl : fcbNl);

            for (int j = 0; j < nbWl; j++) {
                int satJ = satList[j];
                int idxJ = PppCore.IB(satJ, 0, opt);
                QbNl[i * nbWl + j] = rtk.P[idx * nx + idxJ] / (lamNl * lamNl);
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

            int code0Nl = rtk.ssat[sat - 1].code[0][0];
            int code1Nl = rtk.ssat[sat - 1].code[1][0];
            double freq1Nl = SatUtils.sat2freq(sat, code0Nl, nav);
            double freq2Nl = SatUtils.sat2freq(sat, code1Nl, nav);
            if (freq1Nl == 0.0) freq1Nl = Constants.FREQ1_CMP;
            if (freq2Nl == 0.0) freq2Nl = Constants.FREQ2_CMP;
            double lam1Nl = Constants.CLIGHT / freq1Nl;
            double lam2Nl = Constants.CLIGHT / freq2Nl;
            double lamWlNl = Constants.CLIGHT / (freq1Nl - freq2Nl);

            double fixedNl = Math.round(bNl[i * 2]);
            double lamNlNl = Constants.CLIGHT / (freq1Nl + freq2Nl);
            double fixedAmb = fixedNl * lamNlNl + wlFixed[i] * freq2Nl * lamWlNl / (freq1Nl + freq2Nl);

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
        if (rtk.nfix < cfg.pppArFixHoldMinEp) return;

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
     * PPP部分模糊度固定（Partial AR）——基于WL+NL两步固定框架。
     *
     * <p>数学原理：PPP中x[IB]存储的是IF组合模糊度（单位：米），不能直接对它做LAMBDA。
     * 正确做法是复用WL+NL两步固定：
     * 1. 构建WL宽巷模糊度（周），做LAMBDA搜索
     * 2. 若WL全集ratio不够，按方差排序剔除最大方差的卫星，对子集做WL LAMBDA
     * 3. WL固定后，构建NL窄巷模糊度（周），做LAMBDA搜索
     * 4. 若NL全集ratio不够，同样做子集搜索
     * 5. 最终固定 N₁ = N_WL + N_NL（周），再乘λ₁转回米
     *
     * <p>与pppAmbFixWlNl的区别：当WL或NL的ratio不够时，不直接放弃，
     * 而是尝试剔除方差最大的卫星子集，寻找能通过ratio检验的最大子集。
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
        double[] ambVar = new double[Constants.MAXSAT * nf];

        for (int sat = 1; sat <= Constants.MAXSAT; sat++) {
            if (rtk.ssat[sat - 1].vs == 0) continue;
            if (rtk.ssat[sat - 1].azel[1] < opt.elmin) continue;

            int sys = SatUtils.satsys(sat, null);
            if ((sys & (Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_CMP)) == 0) continue;

            int f = 0;
            int idx = PppCore.IB(sat, f, opt);
            if (idx >= nx) continue;

            int code0 = rtk.ssat[sat - 1].code[0][0];
            double freq1 = SatUtils.sat2freq(sat, code0, nav);
            double lam1 = freq1 > 0 ? Constants.CLIGHT / freq1 : 0.19;

            double var = rtk.P[idx * nx + idx];
            double varCyc = lam1 > 0 ? var / (lam1 * lam1) : var;
            if (var <= 0 || varCyc > 16.0) continue;

            satList[nb] = sat;
            freqList[nb] = f;
            ambVar[nb] = var;
            nb++;
        }

        if (nb < cfg.pppPartialArMinSats) {
            LOG.info("PPP Partial AR: too few candidates: nb={}", nb);
            return -1;
        }

        int[] perm = new int[nb];
        for (int i = 0; i < nb; i++) perm[i] = i;
        sortByVariance(perm, ambVar, nb);

        int nBestTotal = 0;
        int[] bestWlFixed = new int[nb];
        int[] bestNlFixed = new int[nb];
        int[] bestPerm = new int[nb];
        double bestNlRatio = 0.0;

        for (int nTry = nb; nTry >= cfg.pppPartialArMinSats; nTry--) {
            if (nb - nTry > cfg.pppPartialArMaxTries) break;

            int[] subIdx = new int[nTry];
            for (int i = 0; i < nTry; i++) subIdx[i] = perm[i];

            double[] yWl = new double[nTry];
            double[] QbWl = new double[nTry * nTry];

            for (int i = 0; i < nTry; i++) {
                int sat = satList[subIdx[i]];
                int idx = PppCore.IB(sat, 0, opt);

                int code0 = rtk.ssat[sat - 1].code[0][0];
                int code1 = rtk.ssat[sat - 1].code[1][0];
                double freq1 = SatUtils.sat2freq(sat, code0, nav);
                double freq2 = SatUtils.sat2freq(sat, code1, nav);
                if (freq1 == 0.0 || freq2 == 0.0) {
                    freq1 = Constants.FREQL1;
                    freq2 = Constants.FREQL2;
                }

                double lam1 = Constants.CLIGHT / freq1;
                double lam2 = Constants.CLIGHT / freq2;
                double lamWl = Constants.CLIGHT / (freq1 - freq2);

                double mw = rtk.ssat[sat - 1].mw[0];
                double fcbWl = getWlFcb(nav, sat, code0, code1, lam1, lam2, rtk.sol.time.time);
                double wlMean = rtk.ssat[sat - 1].rw > 0
                    ? rtk.ssat[sat - 1].xrwl / rtk.ssat[sat - 1].rw : mw / lamWl;
                yWl[i] = wlMean + (nav.fcbFromOsb ? -fcbWl : fcbWl);

                for (int j = 0; j < nTry; j++) {
                    int satJ = satList[subIdx[j]];
                    int idxJ = PppCore.IB(satJ, 0, opt);
                    QbWl[i * nTry + j] = rtk.P[idx * nx + idxJ] / (lamWl * lamWl);
                }
            }

            double[] bWl = new double[nTry * 2];
            double[] sWl = new double[2];
            int info = Lambda.lambda(nTry, 2, yWl, QbWl, bWl, sWl);
            if (info != 0) continue;

            double wlRatio = sWl[0] > 0 ? sWl[1] / sWl[0] : 0.0;
            if (wlRatio < cfg.pppPartialArMinRatio) continue;

            int[] wlFixed = new int[nTry];
            int nWlOk = 0;
            for (int i = 0; i < nTry; i++) {
                wlFixed[i] = (int) Math.round(bWl[i * 2]);
                if (Math.abs(yWl[i] - wlFixed[i]) < 0.25) nWlOk++;
            }

            double[] yNl = new double[nTry];
            double[] QbNl = new double[nTry * nTry];

            for (int i = 0; i < nTry; i++) {
                int sat = satList[subIdx[i]];
                int idx = PppCore.IB(sat, 0, opt);

                int code0 = rtk.ssat[sat - 1].code[0][0];
                int code1 = rtk.ssat[sat - 1].code[1][0];
                double freq1 = SatUtils.sat2freq(sat, code0, nav);
                if (freq1 == 0.0) freq1 = Constants.FREQL1;

                double freq2 = SatUtils.sat2freq(sat, code1, nav);
                if (freq2 == 0.0) freq2 = Constants.FREQL2;

                double lamNl = Constants.CLIGHT / (freq1 + freq2);
                double nlFloat = rtk.x[idx] / lamNl - wlFixed[i] * freq2 / (freq1 - freq2);
                double fcbNl = getNlFcb(nav, sat, code0, lamNl, rtk.sol.time.time);
                yNl[i] = nlFloat + (nav.fcbFromOsb ? -fcbNl : fcbNl);

                for (int j = 0; j < nTry; j++) {
                    int satJ = satList[subIdx[j]];
                    int idxJ = PppCore.IB(satJ, 0, opt);
                    QbNl[i * nTry + j] = rtk.P[idx * nx + idxJ] / (lamNl * lamNl);
                }
            }

            double[] bNl = new double[nTry * 2];
            double[] sNl = new double[2];
            info = Lambda.lambda(nTry, 2, yNl, QbNl, bNl, sNl);
            if (info != 0) continue;

            double nlRatio = sNl[0] > 0 ? sNl[1] / sNl[0] : 0.0;
            if (nlRatio < cfg.pppPartialArMinRatio) continue;

            int[] nlFixed = new int[nTry];
            for (int i = 0; i < nTry; i++) nlFixed[i] = (int) Math.round(bNl[i * 2]);

            nBestTotal = nTry;
            bestNlRatio = nlRatio;
            System.arraycopy(wlFixed, 0, bestWlFixed, 0, nTry);
            System.arraycopy(nlFixed, 0, bestNlFixed, 0, nTry);
            System.arraycopy(subIdx, 0, bestPerm, 0, nTry);
            rtk.sol.ratio = (float) nlRatio;

            LOG.info(String.format(Locale.US,
                "PPP Partial AR: candidate nTry=%d wlRatio=%.2f nlRatio=%.2f nWlOk=%d",
                nTry, wlRatio, nlRatio, nWlOk));
            break;
        }

        if (nBestTotal < cfg.pppPartialArMinSats) {
            LOG.info("PPP Partial AR: no valid subset found (best={})", nBestTotal);
            return -1;
        }

        for (int i = 0; i < nBestTotal; i++) {
            int sat = satList[bestPerm[i]];
            int f = freqList[bestPerm[i]];
            int idx = PppCore.IB(sat, f, opt);

            int code0 = rtk.ssat[sat - 1].code[0][0];
            int code1 = rtk.ssat[sat - 1].code[1][0];
            double freq1 = SatUtils.sat2freq(sat, code0, nav);
            double freq2 = SatUtils.sat2freq(sat, code1, nav);
            if (freq1 == 0.0) freq1 = Constants.FREQ1_CMP;
            if (freq2 == 0.0) freq2 = Constants.FREQ2_CMP;
            double lamWl = Constants.CLIGHT / (freq1 - freq2);
            double lamNl = Constants.CLIGHT / (freq1 + freq2);

            double fixedAmb = bestNlFixed[i] * lamNl + bestWlFixed[i] * freq2 * lamWl / (freq1 + freq2);

            rtk.x[idx] = fixedAmb;
            PppCore.initx(rtk.x, rtk.P, nx, fixedAmb, 0.0, idx);
            rtk.ssat[sat - 1].fix[f] = 1;
            if (xa != null) xa[idx] = fixedAmb;
        }

        LOG.info("PPP Partial AR: fixed {}/{} ambiguities (WL+NL, nlRatio={})",
                nBestTotal, nb, String.format("%.2f", bestNlRatio));

        return nBestTotal;
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

    // B2b/B2I fallback: L7I(BDS-2 B2I I) <-> L7D(BDS-3 B2b Data), same freq 1207.14MHz
    // Basis: BDS ICD (B2I=L7I, B2b=L7D), WUM0MGXRAP only provides L7D for BDS-3,
    // industry practice (L7D as L7I fallback per 2018 literature).
    // ASSUMPTION: L7D OSB is valid substitute for L7I OSB at same frequency.
    // This holds when OSB product only provides L7D (current WUM0MGXRAP).
    // If a future product provides both L7I and L7I with different values,
    // fcbWlByCode must use Double[] (null=unfilled) to distinguish true-0 from missing.
    private static final int[] BDS_B2B_EQUIV = {Constants.CODE_L7I, Constants.CODE_L7D};

    // B1I/B1X/B1P fallback: L1I(BDS-2 B1I) <-> L1X(BDS-3 B1X) <-> L1P(BDS-3 B1P), same freq 1561.098MHz
    // WUM0MGXRAP provides L1X for BDS-3, but observations may use L1I or L1P.
    // ASSUMPTION: L1X OSB is valid substitute for L1I/L1P OSB at same frequency.
    private static final int[] BDS_B1_EQUIV = {Constants.CODE_L1I, Constants.CODE_L1X, Constants.CODE_L1P};

    static double getOsb(Nav nav, int sat, int code) {
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
        if (code == Constants.CODE_L1I || code == Constants.CODE_L1X || code == Constants.CODE_L1P) {
            for (int ec : BDS_B1_EQUIV) {
                if (ec != code && ec >= 0 && ec <= Constants.MAXCODE) {
                    double v = nav.fcbWlByCode[sat - 1][ec];
                    if (v != 0.0) return v;
                }
            }
        }
        return 0.0;
    }

    private static double getWlFcb(Nav nav, int sat, int code0, int code1,
                                   double lam1, double lam2, double time) {
        if (nav.fcbFromOsb) {
            if (nav.fcbWlByCode == null) return 0.0;
            if (sat < 1 || sat > Constants.MAXSAT) return 0.0;
            double osbL1 = getOsb(nav, sat, code0);
            double osbL2 = getOsb(nav, sat, code1);
            double wlFcb = osbL1 / lam1 - osbL2 / lam2;
            String prn = SatUtils.satno2id(sat);
            System.out.printf(Locale.US,
                "FCB-DIAG prn=%s sat=%d code0=%d code1=%d osbL1=%.6f osbL2=%.6f lam1=%.6f lam2=%.6f wlFcb=%.6f%n",
                prn, sat, code0, code1, osbL1, osbL2, lam1, lam2, wlFcb);
            return wlFcb;
        }
        return FcbReader.getFcbWl(nav, sat, time);
    }

    private static double getNlFcb(Nav nav, int sat, int code0, double lam1, double time) {
        if (nav.fcbFromOsb) {
            if (nav.fcbWlByCode == null) return 0.0;
            if (sat < 1 || sat > Constants.MAXSAT) return 0.0;
            double osbL1 = getOsb(nav, sat, code0);
            return osbL1 / lam1;
        }
        return FcbReader.getFcbNl(nav, sat, time);
    }

    private static double getEwlFcb(Nav nav, int sat, int code1, int code2,
                                     double lam2, double lam3) {
        if (nav.fcbFromOsb) {
            if (nav.fcbWlByCode == null) return 0.0;
            if (sat < 1 || sat > Constants.MAXSAT) return 0.0;
            double osb2 = getOsb(nav, sat, code1);
            double osb3 = getOsb(nav, sat, code2);
            return osb2 / lam2 - osb3 / lam3;
        }
        return 0.0;
    }

    /**
     * 三频PPP-AR：EWL→WL→NL级联固定。
     *
     * <p>数学原理：利用第三频点（L5/E5a/B2a）形成超宽巷（EWL）组合，
     * 其波长极长（GPS L5-L2: ~5.86m, Galileo E5a-E5b: ~9.77m），
     * 可直接取整固定，无需LAMBDA搜索。
     *
     * <p>级联步骤：
     * <ol>
     *   <li>EWL：MW-like组合(f2,f3)，取整固定（波长极长，码噪声仅~0.03-0.09周）</li>
     *   <li>WL：用固定EWL验证候选卫星，LAMBDA搜索固定</li>
     *   <li>NL：用固定WL构建NL模糊度，LAMBDA搜索固定</li>
     *   <li>重构：N_fixed = N_NL*λ_NL + N_WL*f2*λ_WL/(f1+f2)</li>
     * </ol>
     *
     * <p>对应C版：RTKLIB未实现三频PPP-AR，本模块为v2.3.0新增
     */
    public static int pppAmbFixEwlWlNl(Rtk rtk, double[] bias, double[] xa,
                                         int gps, int glo, int sbs, Nav nav) {
        RtkConfig cfg = rtk.rtkConfig;
        if (!cfg.enableMultiFreqAR) return -1;
        LOG.info("PPP-AR: pppAmbFixEwlWlNl called, epoch={}, nx={}", rtk.epoch, rtk.nx);

        PrcOpt opt = rtk.opt;
        int nf = PppCore.NF(opt);
        int nx = rtk.nx;

        int nb = 0;
        int[] satList = new int[Constants.MAXSAT * nf];

        int nSkipVar = 0, nSkipVs = 0, nSkipSys = 0, nSkipIdx = 0;
        int nSkip3Freq = 0, nSkipEwl = 0, nSkipOsb = 0;

        for (int sat = 1; sat <= Constants.MAXSAT; sat++) {
            if (rtk.ssat[sat - 1].vs == 0) { nSkipVs++; continue; }
            if (rtk.ssat[sat - 1].azel[1] < opt.elmin) { nSkipVs++; continue; }

            int sys = SatUtils.satsys(sat, null);
            if ((sys & (Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_CMP)) == 0) { nSkipSys++; continue; }

            int idx = PppCore.IB(sat, 0, opt);
            if (idx >= nx) { nSkipIdx++; continue; }

            double ambVar = rtk.P[idx * nx + idx];
            double freq1 = SatUtils.sat2freq(sat, rtk.ssat[sat - 1].code[0][0], nav);
            double lam1 = freq1 > 0 ? Constants.CLIGHT / freq1 : 0.19;
            double ambVarCyc = lam1 > 0 ? ambVar / (lam1 * lam1) : ambVar;
            if (ambVar <= 0 || ambVarCyc > 16.0) { nSkipVar++; continue; }

            int code0 = rtk.ssat[sat - 1].code[0][0];
            int code1 = rtk.ssat[sat - 1].code[1][0];
            int code2 = rtk.ssat[sat - 1].code[2][0];
            if (code0 == 0 || code1 == 0 || code2 == 0) { nSkip3Freq++; continue; }

            double freq2 = SatUtils.sat2freq(sat, code1, nav);
            double freq3 = SatUtils.sat2freq(sat, code2, nav);
            if (freq2 == 0.0 || freq3 == 0.0) { nSkip3Freq++; continue; }

            boolean hasEwlAccum = rtk.ssat[sat - 1].niewl >= 1 && rtk.ssat[sat - 1].rew > 0;
            double ewlRaw = rtk.ssat[sat - 1].mw[1];
            if (!hasEwlAccum && ewlRaw == 0.0) { nSkipEwl++; continue; }

            if (nav.fcbFromOsb && nav.fcbWlByCode != null) {
                double osb0 = getOsb(nav, sat, code0);
                double osb1 = getOsb(nav, sat, code1);
                double osb2 = getOsb(nav, sat, code2);
                if (osb0 == 0.0 || osb1 == 0.0 || osb2 == 0.0) {
                    nSkipOsb++;
                    continue;
                }
            }

            satList[nb] = sat;
            nb++;
        }

        LOG.info(String.format(Locale.US,
            "3F-AR epoch=%d candidates=%d skipVar=%d skip3Freq=%d skipEwl=%d skipOsb=%d skipVs=%d skipSys=%d skipIdx=%d",
            rtk.epoch, nb, nSkipVar, nSkip3Freq, nSkipEwl, nSkipOsb, nSkipVs, nSkipSys, nSkipIdx));

        if (nb < 4) {
            LOG.info("PPP-AR 3F: too few candidates: nb={}", nb);
            return -1;
        }

        double[] ewlFixed = new double[nb];
        int nEwlFixed = 0;

        for (int i = 0; i < nb; i++) {
            int sat = satList[i];
            int code1 = rtk.ssat[sat - 1].code[1][0];
            int code2 = rtk.ssat[sat - 1].code[2][0];
            double freq2 = SatUtils.sat2freq(sat, code1, nav);
            double freq3 = SatUtils.sat2freq(sat, code2, nav);
            if (freq2 == 0.0 || freq3 == 0.0) { freq2 = Constants.FREQL2; freq3 = Constants.FREQL5; }

            double lam2 = Constants.CLIGHT / freq2;
            double lam3 = Constants.CLIGHT / freq3;
            double lamEwl = Constants.CLIGHT / (freq2 - freq3);

            double ewl;
            if (rtk.ssat[sat - 1].rew > 0) {
                ewl = rtk.ssat[sat - 1].xrewl / rtk.ssat[sat - 1].rew;
            } else {
                ewl = rtk.ssat[sat - 1].mw[1] / lamEwl;
            }

            double fcbEwl = getEwlFcb(nav, sat, code1, code2, lam2, lam3);
            double ewlCorr = ewl + (nav.fcbFromOsb ? -fcbEwl : fcbEwl);
            ewlFixed[i] = Math.round(ewlCorr);
            double ewlFrac = Math.abs(ewlCorr - ewlFixed[i]);

            if (i < 12) {
                double ewlSig = 999.0;
                if (rtk.ssat[sat - 1].rew > 0 && rtk.ssat[sat - 1].niewl > 1) {
                    double ewlMean = rtk.ssat[sat - 1].xrewl / rtk.ssat[sat - 1].rew;
                    double ewlVar = rtk.ssat[sat - 1].xsewl / rtk.ssat[sat - 1].rew - ewlMean * ewlMean;
                    if (ewlVar > 0) ewlSig = Math.sqrt(ewlVar) / Math.sqrt(rtk.ssat[sat - 1].niewl - 1);
                }
                LOG.info(String.format(Locale.US,
                    "EWL-SAT sat=%d ewl=%.4f niwl=%d ewlSig=%.4f fcbEwl=%.4f corr=%.4f fixed=%.0f frac=%.4f",
                    sat, ewl, rtk.ssat[sat - 1].niewl, ewlSig, fcbEwl, ewlCorr, ewlFixed[i], ewlFrac));
            }

            if (ewlFrac < 0.25) nEwlFixed++;
        }

        LOG.info(String.format(Locale.US,
            "PPP-AR 3F: EWL fixed %d/%d (%.0f%%)", nEwlFixed, nb, nb > 0 ? 100.0 * nEwlFixed / nb : 0));

        if (nEwlFixed < 4) {
            LOG.info("PPP-AR 3F: too few EWL fixed: {}", nEwlFixed);
            return -1;
        }

        double[] yWl = new double[nb];
        double[] QbWl = new double[nb * nb];

        for (int i = 0; i < nb; i++) {
            int sat = satList[i];
            int idx = PppCore.IB(sat, 0, opt);

            int code0 = rtk.ssat[sat - 1].code[0][0];
            int code1 = rtk.ssat[sat - 1].code[1][0];
            double freq1 = SatUtils.sat2freq(sat, code0, nav);
            double freq2 = SatUtils.sat2freq(sat, code1, nav);
            if (freq1 == 0.0 || freq2 == 0.0) { freq1 = Constants.FREQL1; freq2 = Constants.FREQL2; }

            double lam1 = Constants.CLIGHT / freq1;
            double lam2 = Constants.CLIGHT / freq2;
            double lamWl = Constants.CLIGHT / (freq1 - freq2);

            double mw = rtk.ssat[sat - 1].mw[0];
            double fcbWl = getWlFcb(nav, sat, code0, code1, lam1, lam2, rtk.sol.time.time);

            double wlMean = rtk.ssat[sat - 1].rw > 0
                ? rtk.ssat[sat - 1].xrwl / rtk.ssat[sat - 1].rw : mw / lamWl;
            yWl[i] = wlMean + (nav.fcbFromOsb ? -fcbWl : fcbWl);

            for (int j = 0; j < nb; j++) {
                int satJ = satList[j];
                int idxJ = PppCore.IB(satJ, 0, opt);
                QbWl[i * nb + j] = rtk.P[idx * nx + idxJ] / (lamWl * lamWl);
            }
        }

        double[] bWl = new double[nb * 2];
        double[] sWl = new double[2];
        int info = Lambda.lambda(nb, 2, yWl, QbWl, bWl, sWl);

        if (info != 0) {
            LOG.info("PPP-AR 3F: WL LAMBDA failed: info={}", info);
            return -1;
        }

        double wlRatio = sWl[0] > 0 ? sWl[1] / sWl[0] : 0.0;
        LOG.info("PPP-AR 3F: WL nb={} ratio={}", nb, String.format("%.2f", wlRatio));

        if (wlRatio < cfg.pppArRatioWl) {
            LOG.info("PPP-AR 3F: WL ratio too low: {} < {}", String.format("%.2f", wlRatio), cfg.pppArRatioWl);
            return -1;
        }

        int[] wlFixed = new int[nb];
        int nWlFixed = 0;
        for (int i = 0; i < nb; i++) {
            wlFixed[i] = (int) Math.round(bWl[i * 2]);
            if (Math.abs(yWl[i] - wlFixed[i]) < 0.25) nWlFixed++;
        }

        double[] yNl = new double[nb];
        double[] QbNl = new double[nb * nb];

        for (int i = 0; i < nb; i++) {
            int sat = satList[i];
            int idx = PppCore.IB(sat, 0, opt);

            int code0 = rtk.ssat[sat - 1].code[0][0];
            int code1 = rtk.ssat[sat - 1].code[1][0];
            double freq1 = SatUtils.sat2freq(sat, code0, nav);
            if (freq1 == 0.0) freq1 = Constants.FREQL1;

            double freq2 = SatUtils.sat2freq(sat, code1, nav);
            if (freq2 == 0.0) freq2 = Constants.FREQL2;

            double lamNl = Constants.CLIGHT / (freq1 + freq2);
            double nlFloat = rtk.x[idx] / lamNl - wlFixed[i] * freq2 / (freq1 - freq2);

            double fcbNl = getNlFcb(nav, sat, code0, lamNl, rtk.sol.time.time);
            yNl[i] = nlFloat + (nav.fcbFromOsb ? -fcbNl : fcbNl);

            for (int j = 0; j < nb; j++) {
                int satJ = satList[j];
                int idxJ = PppCore.IB(satJ, 0, opt);
                QbNl[i * nb + j] = rtk.P[idx * nx + idxJ] / (lamNl * lamNl);
            }
        }

        double[] bNl = new double[nb * 2];
        double[] sNl = new double[2];
        info = Lambda.lambda(nb, 2, yNl, QbNl, bNl, sNl);

        if (info != 0) {
            LOG.info("PPP-AR 3F: NL LAMBDA failed: info={}", info);
            return -1;
        }

        double nlRatio = sNl[0] > 0 ? sNl[1] / sNl[0] : 0.0;
        rtk.sol.ratio = (float) nlRatio;
        LOG.info("PPP-AR 3F: NL nb={} ratio={}", nb, String.format("%.2f", nlRatio));

        if (nlRatio < cfg.pppArRatioNl) {
            LOG.info("PPP-AR 3F: NL ratio too low: {} < {}", String.format("%.2f", nlRatio), cfg.pppArRatioNl);
            return -1;
        }

        for (int i = 0; i < nb; i++) {
            int sat = satList[i];
            int idx = PppCore.IB(sat, 0, opt);

            int code0 = rtk.ssat[sat - 1].code[0][0];
            int code1 = rtk.ssat[sat - 1].code[1][0];
            double freq1 = SatUtils.sat2freq(sat, code0, nav);
            double freq2 = SatUtils.sat2freq(sat, code1, nav);
            if (freq1 == 0.0) freq1 = Constants.FREQ1_CMP;
            if (freq2 == 0.0) freq2 = Constants.FREQ2_CMP;
            double lamWl = Constants.CLIGHT / (freq1 - freq2);
            double lamNl = Constants.CLIGHT / (freq1 + freq2);

            double fixedNl = Math.round(bNl[i * 2]);
            double fixedAmb = fixedNl * lamNl + wlFixed[i] * freq2 * lamWl / (freq1 + freq2);

            rtk.x[idx] = fixedAmb;
            PppCore.initx(rtk.x, rtk.P, nx, fixedAmb, 0.0, idx);

            if (xa != null) {
                xa[idx] = fixedAmb;
            }

            rtk.ssat[sat - 1].fix[0] = 1;
        }

        LOG.info("PPP-AR 3F: fixed {} ambiguities (EWL={}/{}, WL ratio={}, NL ratio={})",
                nb, nEwlFixed, nb, String.format("%.2f", wlRatio), String.format("%.2f", nlRatio));

        return nb;
    }
}