package org.rtklib.java.ppp;

import org.rtklib.java.ambiguity.Lambda;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.ephemeris.FcbReader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
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

            // Skip satellites without OSB data when using OSB mode.
            // PRIDE-PPPAR lsq.f90 L382-395: AR eligibility requires nbias_used >= 4, i.e.
            // all four signals (L1/L2 phase + code) bias-corrected; incomplete-OSB
            // satellites stay in the float solution only.
            if (nav.fcbFromOsb && nav.fcbWlByCode != null) {
                int code0 = rtk.ssat[sat - 1].code[0][0];
                int code1 = rtk.ssat[sat - 1].code[1][0];
                double osb0 = getOsb(nav, sat, code0);
                double osb1 = getOsb(nav, sat, code1);
                double osbP0 = PppCore.getCodeOsb(nav, sat, code0, 0.0);
                double osbP1 = PppCore.getCodeOsb(nav, sat, code1, 0.0);
                if (osb0 == 0.0 || osb1 == 0.0 || osbP0 == 0.0 || osbP1 == 0.0) {
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

        // Pass 1: WL float values (cycles) for all candidates
        double[] yWlAll = new double[nbWl];
        int[] satAll = new int[nbWl];
        int[] freqAll = new int[nbWl];

        for (int i = 0; i < nbWl; i++) {
            int sat = satList[i];
            satAll[i] = sat;
            freqAll[i] = freqList[i];

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

            double wlMean = rtk.ssat[sat - 1].rw > 0
                ? rtk.ssat[sat - 1].xrwl / rtk.ssat[sat - 1].rw : mw / lamWl;

            if (nav.fcbFromOsb) {
                yWlAll[i] = wlMean;
            } else {
                double fcbWl = getWlFcb(nav, sat, code0, code1, lam1, lam2, rtk.sol.time.time);
                yWlAll[i] = wlMean + fcbWl;
            }

            if (i < 12) {
                double wlRound = Math.round(yWlAll[i]);
                double wlFrac = yWlAll[i] - wlRound;
                double wlSig = 999.0;
                if (rtk.ssat[sat - 1].rw > 0 && rtk.ssat[sat - 1].niwl > 1) {
                    double wVar = rtk.ssat[sat - 1].xswl / rtk.ssat[sat - 1].rw - wlMean * wlMean;
                    if (wVar > 0) wlSig = Math.sqrt(wVar) / Math.sqrt(rtk.ssat[sat - 1].niwl - 1);
                }
                double osbL1 = nav.fcbWlByCode != null && code0 >= 0 && code0 <= Constants.MAXCODE ? nav.fcbWlByCode[sat - 1][code0] : 0;
                double osbL2 = nav.fcbWlByCode != null && code1 >= 0 && code1 <= Constants.MAXCODE ? nav.fcbWlByCode[sat - 1][code1] : 0;
                double osbP1 = PppCore.getCodeOsb(nav, sat, code0, 0.0);
                double osbP2 = PppCore.getCodeOsb(nav, sat, code1, 0.0);
                double mwCycles = mw / lamWl;
                LOG.info(String.format(Locale.US,
                    "WL-SAT sat=%d code0=%d code1=%d osbL1=%.4fm osbL2=%.4fm osbP1=%.4fm osbP2=%.4fm mw=%.4fcyc wlMean=%.4f niwl=%d wlSig=%.4f yWl=%.4f round=%.0f frac=%.4f",
                    sat, code0, code1, osbL1, osbL2, osbP1, osbP2, mwCycles, wlMean, rtk.ssat[sat - 1].niwl, wlSig, yWlAll[i], wlRound, wlFrac));
            }
        }

        // Pass 2: PRIDE-PPPAR bdeci deviation gate (fixamb_rounding.f90: dev >= wl_maxdev
        // -> prob=0 -> reject). Undifferenced WL values share a common receiver UPD offset
        // under the integer-clock datum, so the deviation is measured against the epoch
        // median — the undifferenced counterpart of PRIDE's single-difference rwl, where
        // the receiver offset cancels between the two satellites of the pair.
        double[] fracs = new double[nbWl];
        for (int i = 0; i < nbWl; i++) {
            fracs[i] = yWlAll[i] - Math.round(yWlAll[i]);
        }
        double[] sortedFracs = fracs.clone();
        Arrays.sort(sortedFracs);
        double medFrac = (nbWl % 2 == 1) ? sortedFracs[nbWl / 2]
            : 0.5 * (sortedFracs[nbWl / 2 - 1] + sortedFracs[nbWl / 2]);

        int nSkipDev = 0;
        int nbKeep = 0;
        double[] yWl = new double[nbWl];
        int[] satKeep = new int[nbWl];
        int[] freqKeep = new int[nbWl];
        for (int i = 0; i < nbWl; i++) {
            double dev = fracs[i] - medFrac;
            dev -= Math.round(dev);
            if (Math.abs(dev) >= cfg.pppArWlMaxdev) {
                nSkipDev++;
                continue;
            }
            // Align to the integer datum before LAMBDA: undifferenced WL values carry a
            // common receiver WL UPD offset (PRIDE cancels it via single-difference rwl);
            // subtracting the epoch median maps the WL values onto the true N_wl grid so
            // the fixed integers and the subsequent NL construction share a consistent datum.
            yWl[nbKeep] = yWlAll[i] - medFrac;
            satKeep[nbKeep] = satAll[i];
            freqKeep[nbKeep] = freqAll[i];
            nbKeep++;
        }
        if (nSkipDev > 0) {
            LOG.info(String.format(Locale.US,
                "PPP-AR: WL dev gate: medFrac=%+.3f maxdev=%.2f rejected=%d",
                medFrac, cfg.pppArWlMaxdev, nSkipDev));
        }
        if (nbKeep < 4) {
            LOG.info(String.format(Locale.US,
                "PPP-AR: too few WL ambiguities after dev gate: kept=%d rejected=%d", nbKeep, nSkipDev));
            return -1;
        }
        nbWl = nbKeep;
        satList = satKeep;
        freqList = freqKeep;

        // Pass 3: WL fixing. In IFLC mode the filter carries a single combined
        // ambiguity per satellite, so rtk.P yields a diagonal QbWl whose LAMBDA
        // search degenerates to per-satellite rounding with ratio ~1.0 (no
        // cross-covariance to discriminate candidates; same defect as the 3F
        // IFLC branch, see FIX-ALG-024/026): fix by per-satellite rounding of
        // the median-aligned MW arc means directly.
        int[] wlFixed = new int[nbWl];
        double wlRatio;
        if (opt.ionoopt == Constants.IONOOPT_IFLC) {
            int nKeep = 0;
            int nFixed = 0;
            double maxFrac = 0.0;
            for (int i = 0; i < nbWl; i++) {
                int prov = (int) Math.round(yWl[i]);
                double frac = Math.abs(yWl[i] - prov);
                if (frac > maxFrac) maxFrac = frac;
                if (frac >= 0.25) continue;
                wlFixed[nKeep] = prov;
                satList[nKeep] = satList[i];
                freqList[nKeep] = freqList[i];
                yWl[nKeep] = yWl[i];
                nKeep++;
                nFixed++;
            }
            wlRatio = 0.0; // rounding path has no ratio semantics
            LOG.info(String.format(Locale.US,
                "PPP-AR: WL rounding nb=%d nFixed=%d(%.0f%%) maxFrac=%.4f",
                nbWl, nFixed, nbWl > 0 ? 100.0 * nFixed / nbWl : 0, maxFrac));
            if (nFixed < 4) {
                LOG.info("PPP-AR: too few WL fixed (rounding): {}", nFixed);
                return -1;
            }
            nbWl = nKeep;
            dumpFrac("MAIN-WL", satList, nbWl, yWl, wlFixed, 0.0);
        } else {
            double[] QbWl = new double[nbWl * nbWl];
            for (int i = 0; i < nbWl; i++) {
                int sat = satList[i];
                int idx = PppCore.IB(sat, 0, opt);
                int codeI = rtk.ssat[sat - 1].code[0][0];
                int codeJ = rtk.ssat[sat - 1].code[1][0];
                double f1 = SatUtils.sat2freq(sat, codeI, nav);
                double f2 = SatUtils.sat2freq(sat, codeJ, nav);
                if (f1 == 0.0 || f2 == 0.0) {
                    f1 = Constants.FREQL1;
                    f2 = Constants.FREQL2;
                }
                double lamWl = Constants.CLIGHT / (f1 - f2);
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

            wlRatio = sWl[0] > 0 ? sWl[1] / sWl[0] : 0.0;
            LOG.info("PPP-AR: WL nb={} ratio={}", nbWl, String.format("%.2f", wlRatio));

            if (wlRatio < cfg.pppArRatioWl) {
                LOG.info("PPP-AR: WL ratio too low: {} < {}", String.format("%.2f", wlRatio), cfg.pppArRatioWl);
                return -1;
            }

            int nWlFixed = 0;
            for (int i = 0; i < nbWl; i++) {
                wlFixed[i] = (int) Math.round(bWl[i * 2]);
                double wlFrac = Math.abs(yWl[i] - wlFixed[i]);
                if (wlFrac < 0.25) nWlFixed++;
            }
            LOG.info(String.format(Locale.US,
                "PPP-AR: WL fixed stats: nbWl=%d nWlFixed=%d(%.0f%%) ratio=%.2f",
                nbWl, nWlFixed, nbWl > 0 ? 100.0 * nWlFixed / nbWl : 0, wlRatio));
        }

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

            if (opt.ionoopt != Constants.IONOOPT_IFLC) {
                for (int j = 0; j < nbWl; j++) {
                    int satJ = satList[j];
                    int idxJ = PppCore.IB(satJ, 0, opt);
                    QbNl[i * nbWl + j] = rtk.P[idx * nx + idxJ] / (lamNl * lamNl);
                }
            }
            nbNl++;
        }

        if (nbNl < 4) {
            LOG.info("PPP-AR: too few NL ambiguities: nbNl={}", nbNl);
            return -1;
        }

        int[] nlFixed = new int[nbNl];
        double nlRatio;
        if (opt.ionoopt == Constants.IONOOPT_IFLC) {
            // IFLC: per-satellite rounding with epoch-median alignment — the
            // median removes the common receiver NL UPD (satellite-side-only
            // OSB products do not), mapping the NL values onto the true N1
            // integer grid. Same datum fix as the WL rounding path and the 3F
            // IFLC branch.
            double[] frNl = new double[nbNl];
            for (int i = 0; i < nbNl; i++) {
                frNl[i] = yNl[i] - Math.round(yNl[i]);
            }
            double[] sortedNl = frNl.clone();
            Arrays.sort(sortedNl);
            double medNl = (nbNl % 2 == 1) ? sortedNl[nbNl / 2]
                : 0.5 * (sortedNl[nbNl / 2 - 1] + sortedNl[nbNl / 2]);

            int nKeep = 0;
            int nFixed = 0;
            double maxFrac = 0.0;
            for (int i = 0; i < nbNl; i++) {
                int prov = (int) Math.round(yNl[i] - medNl);
                double frac = Math.abs(yNl[i] - medNl - prov);
                if (frac > maxFrac) maxFrac = frac;
                if (frac >= 0.25) continue;
                nlFixed[nKeep] = prov;
                satList[nKeep] = satList[i];
                freqList[nKeep] = freqList[i];
                yNl[nKeep] = yNl[i] - medNl;
                nKeep++;
                nFixed++;
            }
            nlRatio = 0.0;
            rtk.sol.ratio = 0.0f;
            LOG.info(String.format(Locale.US,
                "PPP-AR: NL rounding nb=%d nFixed=%d(%.0f%%) medNl=%+.4f maxFrac=%.4f",
                nbNl, nFixed, nbNl > 0 ? 100.0 * nFixed / nbNl : 0, medNl, maxFrac));
            if (nFixed < 4) {
                LOG.info("PPP-AR: too few NL fixed (rounding): {}", nFixed);
                return -1;
            }
            nbNl = nKeep;
            dumpFrac("MAIN-NL", satList, nbNl, yNl, nlFixed, 0.0);

            // Integer-domain cross-validation (delta gate, JAVA-ONLY, FIX-ALG-026):
            // residual of each candidate fixed ambiguity against the float state,
            // normalized by lambda_NL, median-aligned. A wrong WL integer (+-1)
            // shifts the reconstruction by ~3.5 NL cycles, a wrong NL integer by
            // ~1 cycle, while clean satellites stay within ~0.3 cycles — the frac
            // gates are blind to whole-cycle OSB biases (PRN 23/25/26 evidence).
            if (cfg.enablePppArDeltaGate) {
                double[] deltas = new double[nbNl];
                for (int i = 0; i < nbNl; i++) {
                    int sat = satList[i];
                    int code0 = rtk.ssat[sat - 1].code[0][0];
                    int code1 = rtk.ssat[sat - 1].code[1][0];
                    double freq1 = SatUtils.sat2freq(sat, code0, nav);
                    double freq2 = SatUtils.sat2freq(sat, code1, nav);
                    if (freq1 == 0.0) freq1 = Constants.FREQ1_CMP;
                    if (freq2 == 0.0) freq2 = Constants.FREQ2_CMP;
                    double lamWl = Constants.CLIGHT / (freq1 - freq2);
                    double lamNl = Constants.CLIGHT / (freq1 + freq2);
                    double fixedAmb = nlFixed[i] * lamNl
                        + wlFixed[i] * freq2 * lamWl / (freq1 + freq2);
                    int idx = PppCore.IB(sat, freqList[i], opt);
                    deltas[i] = (fixedAmb - rtk.x[idx]) / lamNl;
                }
                double[] sortedDelta = deltas.clone();
                Arrays.sort(sortedDelta);
                double medDelta = (nbNl % 2 == 1) ? sortedDelta[nbNl / 2]
                    : 0.5 * (sortedDelta[nbNl / 2 - 1] + sortedDelta[nbNl / 2]);
                // Datum self-check (half-cycle bound): the median grid offset must
                // be small before any fix is applied — applying with a shifted
                // datum freezes wrong integers (var=0) and poisons the series.
                if (Math.abs(medDelta) > cfg.pppArDeltaMax) {
                    LOG.info(String.format(Locale.US,
                        "PPP-AR: delta gate datum offset %+.4f > %.2f, skip fix",
                        medDelta, cfg.pppArDeltaMax));
                    return -1;
                }
                int nKeepDelta = 0;
                for (int i = 0; i < nbNl; i++) {
                    double dev = deltas[i] - medDelta;
                    if (Math.abs(dev) > cfg.pppArDeltaMax) {
                        LOG.info(String.format(Locale.US,
                            "PPP-AR delta gate reject: sat=%d dev=%+.4f cyc",
                            satList[i], dev));
                        continue;
                    }
                    satList[nKeepDelta] = satList[i];
                    freqList[nKeepDelta] = freqList[i];
                    wlFixed[nKeepDelta] = wlFixed[i];
                    nlFixed[nKeepDelta] = nlFixed[i];
                    nKeepDelta++;
                }
                LOG.info(String.format(Locale.US,
                    "PPP-AR: delta gate nb=%d nKeep=%d medDelta=%+.4f",
                    nbNl, nKeepDelta, medDelta));
                if (nKeepDelta < 4) {
                    LOG.info("PPP-AR: too few ambiguities after delta gate: {}", nKeepDelta);
                    return -1;
                }
                nbNl = nKeepDelta;
            }
        } else {
            double[] bNl = new double[nbNl * 2];
            double[] sNl = new double[2];
            int info = Lambda.lambda(nbNl, 2, yNl, QbNl, bNl, sNl);

            if (info != 0) {
                LOG.info("PPP-AR: NL LAMBDA failed: info={}", info);
                return -1;
            }

            nlRatio = sNl[0] > 0 ? sNl[1] / sNl[0] : 0.0;
            rtk.sol.ratio = (float) nlRatio;
            LOG.info("PPP-AR: NL nb={} ratio={}", nbNl, String.format("%.2f", nlRatio));

            if (nlRatio < cfg.pppArRatioNl) {
                LOG.info("PPP-AR: NL ratio too low: {} < {}", String.format("%.2f", nlRatio), cfg.pppArRatioNl);
                return -1;
            }

            for (int i = 0; i < nbNl; i++) {
                nlFixed[i] = (int) Math.round(bNl[i * 2]);
            }
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

            double fixedNl = nlFixed[i];
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

        /* JAVA-ONLY NL arc accumulation: must run after the filter update
         * (this entry point is called post-update) so the accumulated series
         * matches the states consumed at fix time (PRIDE-PPPAR lsq_add_obs). */
        accumulateNlArc(rtk, opt, nav);

        int nb = 0;
        int nSkipNlArc = 0;
        int nSkipNlSig = 0;
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

            // JAVA-ONLY NL arc gates (PRIDE-PPPAR 'Critical search' minsav + arc
            // sigma): an arc shorter than pppArNlMinsav epochs, or whose weighted
            // arc mean has too large a standard error, is not statistically mature
            // for narrow-lane fixing — the satellite stays in the float solution.
            if (rtk.ssat[sat - 1].ninl < cfg.pppArNlMinsav || rtk.ssat[sat - 1].rn <= 0.0) {
                nSkipNlArc++;
                continue;
            }
            if (rtk.ssat[sat - 1].ninl > 1) {
                double nlMeanArc = rtk.ssat[sat - 1].xrnl / rtk.ssat[sat - 1].rn;
                double nlVarArc = rtk.ssat[sat - 1].xsnl / rtk.ssat[sat - 1].rn - nlMeanArc * nlMeanArc;
                if (nlVarArc > 0.0 && Math.sqrt(nlVarArc) / Math.sqrt(rtk.ssat[sat - 1].ninl - 1)
                    > cfg.pppArNlMaxsig) {
                    nSkipNlSig++;
                    continue;
                }
            }

            // PRIDE-PPPAR lsq.f90: AR eligibility requires complete OSB (L1/L2 phase + code);
            // incomplete-OSB satellites stay in the float solution only
            if (nav.fcbFromOsb && nav.fcbWlByCode != null) {
                int code1 = rtk.ssat[sat - 1].code[1][0];
                if (getOsb(nav, sat, code0) == 0.0 || getOsb(nav, sat, code1) == 0.0
                    || PppCore.getCodeOsb(nav, sat, code0, 0.0) == 0.0
                    || PppCore.getCodeOsb(nav, sat, code1, 0.0) == 0.0) {
                    continue;
                }
            }

            satList[nb] = sat;
            freqList[nb] = f;
            ambVar[nb] = var;
            nb++;
        }

        if (nSkipNlArc > 0 || nSkipNlSig > 0) {
            LOG.info(String.format(Locale.US,
                "PPP Partial AR: NL arc gates: skipArc=%d skipSig=%d", nSkipNlArc, nSkipNlSig));
        }
        if (nb < cfg.pppPartialArMinSats) {
            LOG.info("PPP Partial AR: too few candidates: nb={}", nb);
            return -1;
        }

        // PRIDE-PPPAR bdeci deviation gate at candidate level (median-aligned,
        // undifferenced counterpart of PRIDE single-difference rwl): satellites whose
        // OSB-corrected WL deviates from the coherent integer grid are excluded from
        // every subset before the variance-ordered search.
        double[] fracsCand = new double[nb];
        double[] wlVals = new double[nb];
        for (int i = 0; i < nb; i++) {
            int sat = satList[i];
            int code0 = rtk.ssat[sat - 1].code[0][0];
            int code1 = rtk.ssat[sat - 1].code[1][0];
            double freq1 = SatUtils.sat2freq(sat, code0, nav);
            double freq2 = SatUtils.sat2freq(sat, code1, nav);
            if (freq1 == 0.0 || freq2 == 0.0) {
                freq1 = Constants.FREQL1;
                freq2 = Constants.FREQL2;
            }
            double lamWl = Constants.CLIGHT / (freq1 - freq2);
            double wlVal = rtk.ssat[sat - 1].rw > 0
                ? rtk.ssat[sat - 1].xrwl / rtk.ssat[sat - 1].rw
                : rtk.ssat[sat - 1].mw[0] / lamWl;
            if (!nav.fcbFromOsb) {
                wlVal += getWlFcb(nav, sat, code0, code1,
                    Constants.CLIGHT / freq1, Constants.CLIGHT / freq2, rtk.sol.time.time);
            }
            wlVals[i] = wlVal;
            fracsCand[i] = wlVal - Math.round(wlVal);
        }
        double[] sortedCand = fracsCand.clone();
        Arrays.sort(sortedCand);
        double medCand = (nb % 2 == 1) ? sortedCand[nb / 2]
            : 0.5 * (sortedCand[nb / 2 - 1] + sortedCand[nb / 2]);

        // JAVA-ONLY NL deviation gate (PRIDE-PPPAR 'Narrowlane decision' nl_maxdev):
        // provisional NL per candidate from the arc-averaged state and the
        // median-aligned candidate WL integer; median alignment removes the common
        // receiver NL UPD (undifferenced counterpart of PRIDE's SD cancellation).
        double[] frNl = new double[nb];
        for (int i = 0; i < nb; i++) {
            int sat = satList[i];
            int code0 = rtk.ssat[sat - 1].code[0][0];
            int code1 = rtk.ssat[sat - 1].code[1][0];
            double freq1 = SatUtils.sat2freq(sat, code0, nav);
            double freq2 = SatUtils.sat2freq(sat, code1, nav);
            if (freq1 == 0.0 || freq2 == 0.0) {
                freq1 = Constants.FREQL1;
                freq2 = Constants.FREQL2;
            }
            double lamNl = Constants.CLIGHT / (freq1 + freq2);
            int wlProv = (int) Math.round(wlVals[i] - medCand);
            double fcbNl = getNlFcb(nav, sat, code0, lamNl, rtk.sol.time.time);
            double nlProv = rtk.ssat[sat - 1].xrnl / rtk.ssat[sat - 1].rn
                - wlProv * freq2 / (freq1 - freq2)
                + (nav.fcbFromOsb ? -fcbNl : fcbNl);
            frNl[i] = nlProv - Math.round(nlProv);
        }
        double[] sortedNl = frNl.clone();
        Arrays.sort(sortedNl);
        double medNl = (nb % 2 == 1) ? sortedNl[nb / 2]
            : 0.5 * (sortedNl[nb / 2 - 1] + sortedNl[nb / 2]);

        int nbKeep = 0;
        int nSkipNlDev = 0;
        for (int i = 0; i < nb; i++) {
            double dev = fracsCand[i] - medCand;
            dev -= Math.round(dev);
            if (Math.abs(dev) >= cfg.pppArWlMaxdev) continue;
            double devNl = frNl[i] - medNl;
            devNl -= Math.round(devNl);
            if (Math.abs(devNl) >= cfg.pppArNlMaxdev) {
                nSkipNlDev++;
                continue;
            }
            satList[nbKeep] = satList[i];
                freqList[nbKeep] = freqList[i];
                ambVar[nbKeep] = ambVar[i];
                wlVals[nbKeep] = wlVals[i];
                nbKeep++;
        }
        if (nbKeep < nb) {
            LOG.info(String.format(Locale.US,
                "PPP Partial AR: WL/NL dev gates: med=%+.3f/%+.3f maxdev=%.2f/%.2f rejected=%d (nl=%d)",
                medCand, medNl, cfg.pppArWlMaxdev, cfg.pppArNlMaxdev,
                nb - nbKeep, nSkipNlDev));
        }
        if (nbKeep < cfg.pppPartialArMinSats) {
            LOG.info("PPP Partial AR: too few candidates after dev gate: kept={}", nbKeep);
            return -1;
        }
        nb = nbKeep;

        // IFLC: the filter carries a single combined ambiguity per satellite, so
        // rtk.P yields diagonal QbWl/QbNl and the LAMBDA subset search below
        // degenerates to per-satellite rounding with ratio ~1.0 (see FIX-ALG-026).
        // Same remedy as the main path: per-satellite rounding of the
        // median-aligned MW/NL arc means, with candidates already disciplined by
        // the rejective WL/NL dev gates above (partial-AR semantics preserved).
        if (opt.ionoopt == Constants.IONOOPT_IFLC) {
            int nbWlKeep = 0;
            double maxFracWl = 0.0;
            int[] wlKeep = new int[nb];
            double[] wlValF = new double[nb];
            for (int i = 0; i < nb; i++) {
                double yWlI = wlVals[i] - medCand;
                int prov = (int) Math.round(yWlI);
                double frac = Math.abs(yWlI - prov);
                if (frac > maxFracWl) maxFracWl = frac;
                if (frac >= 0.25) continue;
                satList[nbWlKeep] = satList[i];
                freqList[nbWlKeep] = freqList[i];
                wlKeep[nbWlKeep] = prov;
                wlValF[nbWlKeep] = yWlI;
                nbWlKeep++;
            }
            LOG.info(String.format(Locale.US,
                "PPP Partial AR: WL rounding nb=%d nFixed=%d maxFrac=%.4f",
                nb, nbWlKeep, maxFracWl));
            if (nbWlKeep < cfg.pppPartialArMinSats) {
                LOG.info("PPP Partial AR: too few WL fixed (rounding): {}", nbWlKeep);
                return -1;
            }
            dumpFrac("PARTIAL-WL", satList, nbWlKeep, wlValF, wlKeep, 0.0);

            // NL provisional values from the arc means and the WL integers above
            double[] yNlProv = new double[nbWlKeep];
            for (int i = 0; i < nbWlKeep; i++) {
                int sat = satList[i];
                int code0 = rtk.ssat[sat - 1].code[0][0];
                int code1 = rtk.ssat[sat - 1].code[1][0];
                double freq1 = SatUtils.sat2freq(sat, code0, nav);
                double freq2 = SatUtils.sat2freq(sat, code1, nav);
                if (freq1 == 0.0 || freq2 == 0.0) {
                    freq1 = Constants.FREQL1;
                    freq2 = Constants.FREQL2;
                }
                double lamNl = Constants.CLIGHT / (freq1 + freq2);
                // candidates passed the minsav gate, so xrnl/rn is valid here
                double nlBase = rtk.ssat[sat - 1].xrnl / rtk.ssat[sat - 1].rn;
                double nlFloat = nlBase - wlKeep[i] * freq2 / (freq1 - freq2);
                double fcbNl = getNlFcb(nav, sat, code0, lamNl, rtk.sol.time.time);
                yNlProv[i] = nlFloat + (nav.fcbFromOsb ? -fcbNl : fcbNl);
            }
            // Second median alignment on the kept subset (receiver NL UPD removal,
            // same datum fix as the main path rounding branch)
            double[] frNl2 = new double[nbWlKeep];
            for (int i = 0; i < nbWlKeep; i++) frNl2[i] = yNlProv[i] - Math.round(yNlProv[i]);
            double[] sortedNl2 = frNl2.clone();
            Arrays.sort(sortedNl2);
            double medNl2 = (nbWlKeep % 2 == 1) ? sortedNl2[nbWlKeep / 2]
                : 0.5 * (sortedNl2[nbWlKeep / 2 - 1] + sortedNl2[nbWlKeep / 2]);

            int nNlFixed = 0;
            double maxFracNl = 0.0;
            int[] nlVal = new int[nbWlKeep];
            double[] nlValF = new double[nbWlKeep];
            for (int i = 0; i < nbWlKeep; i++) {
                int prov = (int) Math.round(yNlProv[i] - medNl2);
                double frac = Math.abs(yNlProv[i] - medNl2 - prov);
                if (frac > maxFracNl) maxFracNl = frac;
                if (frac >= 0.25) continue;
                satList[nNlFixed] = satList[i];
                freqList[nNlFixed] = freqList[i];
                wlKeep[nNlFixed] = wlKeep[i];
                nlVal[nNlFixed] = prov;
                nlValF[nNlFixed] = yNlProv[i] - medNl2;
                nNlFixed++;
            }
            LOG.info(String.format(Locale.US,
                "PPP Partial AR: NL rounding nb=%d nFixed=%d medNl=%+.4f maxFrac=%.4f",
                nbWlKeep, nNlFixed, medNl2, maxFracNl));
            if (nNlFixed < cfg.pppPartialArMinSats) {
                LOG.info("PPP Partial AR: too few NL fixed (rounding): {}", nNlFixed);
                return -1;
            }
            rtk.sol.ratio = 0.0f;
            dumpFrac("PARTIAL-NL", satList, nNlFixed, nlValF, nlVal, 0.0);

            // Integer-domain cross-validation (delta gate, JAVA-ONLY, FIX-ALG-026),
            // same as the main path IFLC branch: rejects candidates whose
            // reconstructed ambiguity deviates from the float state by more than
            // pppArDeltaMax NL cycles from the ensemble median (whole-cycle WL/NL
            // integer errors are invisible to the frac gates).
            if (cfg.enablePppArDeltaGate) {
                double[] deltas = new double[nNlFixed];
                for (int i = 0; i < nNlFixed; i++) {
                    int sat = satList[i];
                    int code0 = rtk.ssat[sat - 1].code[0][0];
                    int code1 = rtk.ssat[sat - 1].code[1][0];
                    double freq1 = SatUtils.sat2freq(sat, code0, nav);
                    double freq2 = SatUtils.sat2freq(sat, code1, nav);
                    if (freq1 == 0.0) freq1 = Constants.FREQ1_CMP;
                    if (freq2 == 0.0) freq2 = Constants.FREQ2_CMP;
                    double lamWl = Constants.CLIGHT / (freq1 - freq2);
                    double lamNl = Constants.CLIGHT / (freq1 + freq2);
                    double fixedAmb = nlVal[i] * lamNl
                        + wlKeep[i] * freq2 * lamWl / (freq1 + freq2);
                    int idx = PppCore.IB(sat, freqList[i], opt);
                    deltas[i] = (fixedAmb - rtk.x[idx]) / lamNl;
                }
                double[] sortedDelta = deltas.clone();
                Arrays.sort(sortedDelta);
                double medDelta = (nNlFixed % 2 == 1) ? sortedDelta[nNlFixed / 2]
                    : 0.5 * (sortedDelta[nNlFixed / 2 - 1] + sortedDelta[nNlFixed / 2]);
                // Datum self-check (half-cycle bound), same rationale as the
                // main path IFLC branch: never fix with a shifted datum.
                if (Math.abs(medDelta) > cfg.pppArDeltaMax) {
                    LOG.info(String.format(Locale.US,
                        "PPP Partial AR: delta gate datum offset %+.4f > %.2f, skip fix",
                        medDelta, cfg.pppArDeltaMax));
                    return -1;
                }
                int nKeepDelta = 0;
                for (int i = 0; i < nNlFixed; i++) {
                    double dev = deltas[i] - medDelta;
                    if (Math.abs(dev) > cfg.pppArDeltaMax) {
                        LOG.info(String.format(Locale.US,
                            "PPP Partial AR delta gate reject: sat=%d dev=%+.4f cyc",
                            satList[i], dev));
                        continue;
                    }
                    satList[nKeepDelta] = satList[i];
                    freqList[nKeepDelta] = freqList[i];
                    wlKeep[nKeepDelta] = wlKeep[i];
                    nlVal[nKeepDelta] = nlVal[i];
                    nlValF[nKeepDelta] = nlValF[i];
                    nKeepDelta++;
                }
                LOG.info(String.format(Locale.US,
                    "PPP Partial AR: delta gate nb=%d nKeep=%d medDelta=%+.4f",
                    nNlFixed, nKeepDelta, medDelta));
                if (nKeepDelta < cfg.pppPartialArMinSats) {
                    LOG.info("PPP Partial AR: too few ambiguities after delta gate: {}",
                        nKeepDelta);
                    return -1;
                }
                nNlFixed = nKeepDelta;
            }

            for (int i = 0; i < nNlFixed; i++) {
                int sat = satList[i];
                int f = freqList[i];
                int idx = PppCore.IB(sat, f, opt);
                int code0 = rtk.ssat[sat - 1].code[0][0];
                int code1 = rtk.ssat[sat - 1].code[1][0];
                double freq1 = SatUtils.sat2freq(sat, code0, nav);
                double freq2 = SatUtils.sat2freq(sat, code1, nav);
                if (freq1 == 0.0) freq1 = Constants.FREQ1_CMP;
                if (freq2 == 0.0) freq2 = Constants.FREQ2_CMP;
                double lamWl = Constants.CLIGHT / (freq1 - freq2);
                double lamNl = Constants.CLIGHT / (freq1 + freq2);

                double fixedAmb = nlVal[i] * lamNl + wlKeep[i] * freq2 * lamWl / (freq1 + freq2);

                rtk.x[idx] = fixedAmb;
                PppCore.initx(rtk.x, rtk.P, nx, fixedAmb, 0.0, idx);
                rtk.ssat[sat - 1].fix[f] = 1;
                if (xa != null) xa[idx] = fixedAmb;
            }

            LOG.info("PPP Partial AR (IFLC): fixed {} ambiguities (WL+NL rounding)", nNlFixed);
            return nNlFixed;
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
                double wlMean = rtk.ssat[sat - 1].rw > 0
                    ? rtk.ssat[sat - 1].xrwl / rtk.ssat[sat - 1].rw : mw / lamWl;
                // OSB mode: observations are already OSB-corrected in mwmeas — no extra
                // FCB term (aligns with pppAmbFixWlNl; the previous -fcbWl double-corrected).
                // -medCand removes the common receiver WL UPD offset (same as the main path).
                if (nav.fcbFromOsb) {
                    yWl[i] = wlMean - medCand;
                } else {
                    double fcbWl = getWlFcb(nav, sat, code0, code1, lam1, lam2, rtk.sol.time.time);
                    yWl[i] = wlMean + fcbWl - medCand;
                }

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
                // JAVA-ONLY: arc-averaged state-derived NL (PRIDE-PPPAR arc mean);
                // candidates passed the minsav gate, so xrnl/rn is valid here
                double nlBase = rtk.ssat[sat - 1].xrnl / rtk.ssat[sat - 1].rn;
                double nlFloat = nlBase - wlFixed[i] * freq2 / (freq1 - freq2);
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

    /**
     * NL弧段累加 [JAVA-ONLY]（PRIDE-PPPAR lsq_add_obs.f90 弧段累加机制的窄巷应用）。
     *
     * <p>每历元将滤波后的模糊度状态换算为窄巷周值（x[IB]/λ_NL），按高度角权重 wele
     * （el>=30°取1.0，否则2·sin(el)，与WL/EWL弧段一致）累加进当前弧段；周跳
     * （ssat.slip[0]，每历元由udbias_ppp入口清零后由detslp检测器置位）或重新锁定
     * （lock<=1）时重置弧段。必须在滤波更新之后调用（pppPartialAR入口处），使累加
     * 序列与固定时消费的状态一致。
     *
     * <p>累加的原始量不含WL改正项（−N_wl·f2/(f1−f2)），该项在固定时用当期WL固定值
     * 补减，保证弧段序列与单历元WL固定结果解耦。x[idx]==0.0（状态未初始化哨兵）不
     * 累加，避免污染弧段统计。
     */
    private static void accumulateNlArc(Rtk rtk, PrcOpt opt, Nav nav) {
        int nx = rtk.nx;
        for (int sat = 1; sat <= Constants.MAXSAT; sat++) {
            if (rtk.ssat[sat - 1].vs == 0) continue;
            if (rtk.ssat[sat - 1].azel[1] < opt.elmin) continue;

            int idx = PppCore.IB(sat, 0, opt);
            if (idx >= nx) continue;

            int code0 = rtk.ssat[sat - 1].code[0][0];
            int code1 = rtk.ssat[sat - 1].code[1][0];
            double f1 = SatUtils.sat2freq(sat, code0, nav);
            double f2 = SatUtils.sat2freq(sat, code1, nav);
            if (f1 <= 0.0 || f2 <= 0.0 || Math.abs(f1 - f2) < 1.0) continue;
            if (rtk.x[idx] == 0.0) continue;

            double lamNl = Constants.CLIGHT / (f1 + f2);
            double nlCyc = rtk.x[idx] / lamNl;

            double el = rtk.ssat[sat - 1].azel[1];
            double wele = 1.0;
            if (el > 0 && el < 30.0 * Constants.D2R) {
                wele = 2.0 * Math.sin(el);
            } else if (el <= 0) {
                wele = 0.0;
            }

            if (rtk.ssat[sat - 1].slip[0] != 0 || rtk.ssat[sat - 1].lock[0] <= 1) {
                rtk.ssat[sat - 1].xrnl = 0.0;
                rtk.ssat[sat - 1].rn = 0.0;
                rtk.ssat[sat - 1].xsnl = 0.0;
                rtk.ssat[sat - 1].ninl = 0;
            }

            if (wele > 0) {
                rtk.ssat[sat - 1].xrnl += nlCyc * wele;
                rtk.ssat[sat - 1].rn += wele;
                rtk.ssat[sat - 1].xsnl += wele * nlCyc * nlCyc;
                rtk.ssat[sat - 1].ninl++;
            }
        }
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

    /** 时变 OSB 序列取值：time∈[start,end) 命中返回记录值；未命中（数据缺口）
     *  取时间上最近的记录值保底。无序列返回 0.0（由调用方回退常量快照）。
     *  phase=true 查相位 OSB 键（高半区），false 查码 OSB 键（低半区）。 */
    private static double getOsbSeries(Nav nav, int sat, int code, double time, boolean phase) {
        if (nav.osbSeries == null || sat < 1 || sat > Constants.MAXSAT) return 0.0;
        java.util.List<double[]> list = nav.osbSeries.get(
                ((long) (sat - 1) * (Constants.MAXCODE + 1) + code) * 2 + (phase ? 1 : 0));
        if (list == null || list.isEmpty()) return 0.0;
        for (double[] r : list) {
            if (time >= r[0] && time < r[1]) return r[2];
        }
        double[] best = null;
        double bestD = Double.MAX_VALUE;
        for (double[] r : list) {
            double d = time < r[0] ? r[0] - time : time - (r[1] - 1e-3);
            if (d < bestD) { bestD = d; best = r; }
        }
        return best != null ? best[2] : 0.0;
    }

    /** 时变 OSB 取值（FIX-ALG-027）：优先按历元时间命中 osbSeries 区间，
     *  含 BDS B1/B2B 等价码回退；全部未命中回退常量快照 getOsb。
     *  完备性检查（仅判非零）继续使用 3 参 getOsb（快照语义）。 */
    static double getOsbAt(Nav nav, int sat, int code, double time) {
        if (code < 0 || code > Constants.MAXCODE) return 0.0;
        double v = getOsbSeries(nav, sat, code, time, true);
        if (v != 0.0) return v;
        if (code == Constants.CODE_L7I || code == Constants.CODE_L7D) {
            for (int ec : BDS_B2B_EQUIV) {
                if (ec != code && ec >= 0 && ec <= Constants.MAXCODE) {
                    v = getOsbSeries(nav, sat, ec, time, true);
                    if (v != 0.0) return v;
                }
            }
        }
        if (code == Constants.CODE_L1I || code == Constants.CODE_L1X || code == Constants.CODE_L1P) {
            for (int ec : BDS_B1_EQUIV) {
                if (ec != code && ec >= 0 && ec <= Constants.MAXCODE) {
                    v = getOsbSeries(nav, sat, ec, time, true);
                    if (v != 0.0) return v;
                }
            }
        }
        return getOsb(nav, sat, code);
    }

    private static double getWlFcb(Nav nav, int sat, int code0, int code1,
                                   double lam1, double lam2, double time) {
        if (nav.fcbFromOsb) {
            if (nav.fcbWlByCode == null) return 0.0;
            if (sat < 1 || sat > Constants.MAXSAT) return 0.0;
            double osbL1 = getOsbAt(nav, sat, code0, time);
            double osbL2 = getOsbAt(nav, sat, code1, time);
            double wlFcb = osbL1 / lam1 - osbL2 / lam2;
            String prn = SatUtils.satno2id(sat);
            LOG.debug("FCB-DIAG prn={} sat={} code0={} code1={} osbL1={} osbL2={} lam1={} lam2={} wlFcb={}",
                prn, sat, code0, code1, 
                String.format("%.6f", osbL1), String.format("%.6f", osbL2),
                String.format("%.6f", lam1), String.format("%.6f", lam2),
                String.format("%.6f", wlFcb));
            return wlFcb;
        }
        return FcbReader.getFcbWl(nav, sat, time);
    }

    private static double getNlFcb(Nav nav, int sat, int code0, double lam1, double time) {
        if (nav.fcbFromOsb) {
            if (nav.fcbWlByCode == null) return 0.0;
            if (sat < 1 || sat > Constants.MAXSAT) return 0.0;
            double osbL1 = getOsbAt(nav, sat, code0, time);
            return osbL1 / lam1;
        }
        return FcbReader.getFcbNl(nav, sat, time);
    }

    private static double getEwlFcb(Nav nav, int sat, int code1, int code2,
                                     double lam2, double lam3, double time) {
        if (nav.fcbFromOsb) {
            if (nav.fcbWlByCode == null) return 0.0;
            if (sat < 1 || sat > Constants.MAXSAT) return 0.0;
            /* FIX-ALG-028: EWL 固定级的补偿对象是弧段平均量（xrewl/rewl），而
             * ewlmeas 的相位项不做 OSB 改正（A=none），弧段均值携带的是未改正
             * 原始观测，其无偏化完全依赖此处的单次常量补偿。因此补偿值必须与
             * 弧段平均匹配——使用快照常量语义（getOsb）。027 曾切到 getOsbAt
             * 逐历元取值，GPS L5Q 900s 时变序列使补偿值全天漂移 ±1-2 EWL 周
             * （实测 G06 L5Q：逐区间 -0.045m vs 末行快照 -0.153m，差 1.84 周），
             * EWL 取整基准逐历元漂移，WUM 3F RMS 3.54cm→65cm 中毒（A/B 探针
             * 证实：仅回退 getOsbAt→getOsb 即恢复 7.5cm）。 */
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

            double fcbEwl = getEwlFcb(nav, sat, code1, code2, lam2, lam3, rtk.sol.time.time);
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
        boolean useDualFreqWl = (opt.ionoopt != Constants.IONOOPT_IFLC)
            && PppCore.IB(satList[0], 1, opt) < nx;

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
            /* FIX-ALG-028：OSB 路径的 WL 偏差改正只在观测方程做一次（mwmeas
             * 已减 osbL1/lam1 − osbL2/lam2，wlMean 相对原始 MW 恰被抬升
             * +fcbWl），固定时不再补偿 ±fcbWl——与 MAIN 单次分支（L164-165）
             * 语义统一。WUM 自洽产品的 WL 偏差同现于 MW 组合的相位项与码项，
             * 改正必须恰好施加一次：若在 mwmeas 再施码 OSB（对 MW 的偏移
             * ≈ +fcbWl），则必须配固定时 -fcbWl 回吐一次（2×2 矩阵实测：
             * 单次+无码OSB=3.00cm / 双重+码OSB=3.54cm，与金标准 3.54cm 同级；
             * 错配组合 单次+码OSB=7.53cm / 双重+无码OSB=7.97cm 中毒）。
             * FCB legacy 路径保持原语义。 */
            if (nav.fcbFromOsb) {
                yWl[i] = wlMean;
            } else {
                yWl[i] = wlMean + fcbWl;
            }

            int idx1 = PppCore.IB(sat, 0, opt);
            int idx2 = PppCore.IB(sat, 1, opt);
            boolean hasDualFreq = (opt.ionoopt != Constants.IONOOPT_IFLC) && idx2 < nx;

            double wlVarMw = 0.0;
            if (rtk.ssat[sat - 1].rw > 0 && rtk.ssat[sat - 1].niwl > 1) {
                double m = rtk.ssat[sat - 1].xrwl / rtk.ssat[sat - 1].rw;
                double v = rtk.ssat[sat - 1].xswl / rtk.ssat[sat - 1].rw - m * m;
                if (v > 0) wlVarMw = v / (rtk.ssat[sat - 1].niwl - 1);
            }

            for (int j = 0; j < nb; j++) {
                int satJ = satList[j];
                int idxJ1 = PppCore.IB(satJ, 0, opt);
                int idxJ2 = PppCore.IB(satJ, 1, opt);
                boolean hasDualJ = (opt.ionoopt != Constants.IONOOPT_IFLC) && idxJ2 < nx;

                if (hasDualFreq && hasDualJ) {
                    double lam1J = Constants.CLIGHT / SatUtils.sat2freq(satJ, rtk.ssat[satJ - 1].code[0][0], nav);
                    double lam2J = Constants.CLIGHT / SatUtils.sat2freq(satJ, rtk.ssat[satJ - 1].code[1][0], nav);
                    QbWl[i * nb + j] = rtk.P[idx1 * nx + idxJ1] / (lam1 * lam1J)
                                      + rtk.P[idx2 * nx + idxJ2] / (lam2 * lam2J)
                                      - rtk.P[idx1 * nx + idxJ2] / (lam1 * lam2J)
                                      - rtk.P[idx2 * nx + idxJ1] / (lam2 * lam1J);
                } else if (i == j) {
                    if (wlVarMw > 0) {
                        QbWl[i * nb + j] = wlVarMw;
                    } else {
                        double varP = rtk.P[idx1 * nx + idxJ1] / (lamWl * lamWl);
                        QbWl[i * nb + j] = varP > 0 ? varP : 1.0;
                    }
                }
            }
        }

        // Median-align the OSB/FCB-corrected WL values before fixing (same datum
        // fix as the dual-freq main path): undifferenced WL values carry a common
        // receiver WL UPD offset that the satellite-side-only OSB product does not
        // remove; subtracting the epoch median maps the WL values onto the true
        // N_wl grid so the frac checks below are meaningful.
        double[] fracsWl3f = new double[nb];
        for (int i = 0; i < nb; i++) {
            fracsWl3f[i] = yWl[i] - Math.round(yWl[i]);
        }
        double[] sortedWl3f = fracsWl3f.clone();
        Arrays.sort(sortedWl3f);
        double medWl3f = (nb % 2 == 1) ? sortedWl3f[nb / 2]
            : 0.5 * (sortedWl3f[nb / 2 - 1] + sortedWl3f[nb / 2]);
        for (int i = 0; i < nb; i++) {
            yWl[i] -= medWl3f;
        }
        LOG.info(String.format(Locale.US,
            "PPP-AR 3F: WL median alignment: medFrac=%+.4f", medWl3f));

        int[] wlFixed = new int[nb];
        int nWlFixed = 0;
        double wlFracMax = 0.0;

        int info;
        double wlRatio = 0.0; // per-satellite rounding path has no ratio semantics
        if (useDualFreqWl) {
            double[] bWl = new double[nb * 2];
            double[] sWl = new double[2];
            info = Lambda.lambda(nb, 2, yWl, QbWl, bWl, sWl);

            if (info != 0) {
                LOG.info("PPP-AR 3F: WL LAMBDA failed: info={}", info);
                return -1;
            }

            wlRatio = sWl[0] > 0 ? sWl[1] / sWl[0] : 0.0;
            LOG.info("PPP-AR 3F: WL nb={} ratio={}", nb, String.format("%.2f", wlRatio));

            if (wlRatio < cfg.pppArRatioWl) {
                LOG.info("PPP-AR 3F: WL ratio too low: {} < {}", String.format("%.2f", wlRatio), cfg.pppArRatioWl);
                return -1;
            }

            for (int i = 0; i < nb; i++) {
                wlFixed[i] = (int) Math.round(bWl[i * 2]);
                double frac = Math.abs(yWl[i] - wlFixed[i]);
                if (frac < 0.25) nWlFixed++;
                if (frac > wlFracMax) wlFracMax = frac;
            }
        } else {
            for (int i = 0; i < nb; i++) {
                wlFixed[i] = (int) Math.round(yWl[i]);
                double frac = Math.abs(yWl[i] - wlFixed[i]);
                if (frac < 0.25) nWlFixed++;
                if (frac > wlFracMax) wlFracMax = frac;
            }
            LOG.info(String.format(Locale.US,
                "PPP-AR 3F: WL rounding nb=%d nFixed=%d(%.0f%%) maxFrac=%.4f",
                nb, nWlFixed, nb > 0 ? 100.0 * nWlFixed / nb : 0, wlFracMax));
            dumpFrac("3F-WL", satList, nb, yWl, wlFixed, 0.0);

            if (nWlFixed < 4) {
                LOG.info("PPP-AR 3F: too few WL fixed: {}", nWlFixed);
                return -1;
            }
        }

        double[] yNl = new double[nb];
        double[] QbNl = new double[nb * nb];

        for (int i = 0; i < nb; i++) {
            int sat = satList[i];
            int idx1 = PppCore.IB(sat, 0, opt);
            int idx2 = PppCore.IB(sat, 1, opt);

            int code0 = rtk.ssat[sat - 1].code[0][0];
            int code1 = rtk.ssat[sat - 1].code[1][0];
            double freq1 = SatUtils.sat2freq(sat, code0, nav);
            if (freq1 == 0.0) freq1 = Constants.FREQL1;

            double freq2 = SatUtils.sat2freq(sat, code1, nav);
            if (freq2 == 0.0) freq2 = Constants.FREQL2;

            double lam1 = Constants.CLIGHT / freq1;
            double lam2 = Constants.CLIGHT / freq2;
            double lamNl = Constants.CLIGHT / (freq1 + freq2);

            boolean hasDualFreq = (opt.ionoopt != Constants.IONOOPT_IFLC) && idx2 < nx;
            double nlFloat;
            if (hasDualFreq) {
                // (f1,f2)-weighted mean (narrow-lane NL) plus f2/(f1+f2)·WL is
                // mathematically N1 (the L1 integer ambiguity): N1 = NL +
                // f2·WL/(f1+f2). Note the sign differs from the IFLC branch
                // below (minus) because the raw quantities there carry a
                // NEGATIVE N2 coefficient ((f1·N1−f2·N2)/(f1−f2)), which
                // cancels against −WL·f2; here the raw NL carries +f2·N2,
                // which cancels only against +WL·f2.
                double n1 = rtk.x[idx1] / lam1;
                double n2 = rtk.x[idx2] / lam2;
                nlFloat = (freq1 * n1 + freq2 * n2) / (freq1 + freq2) + wlFixed[i] * freq2 / (freq1 + freq2);
            } else {
                /* NL arc average was trialed here (FIX-ALG-023 mechanism) and
                 * REVERTED: fallback-style design (immature arc → per-epoch
                 * value) lets convergence-transient arcs through once ninl
                 * reaches minsav, biasing fixed NL values (2D 3.54→8.21cm,
                 * dE/dU ~8/11cm systematics). pppPartialAR rejects immature
                 * arcs outright and adds σ/dev gates, which is why the arc
                 * average works there; 3F per-epoch filter values are already
                 * converged and validated better (see FIX-ALG-025). */
                nlFloat = rtk.x[idx1] / lamNl - wlFixed[i] * freq2 / (freq1 - freq2);
            }

            double fcbNl = getNlFcb(nav, sat, code0, lamNl, rtk.sol.time.time);
            yNl[i] = nlFloat + (nav.fcbFromOsb ? -fcbNl : fcbNl);

            for (int j = 0; j < nb; j++) {
                int satJ = satList[j];
                int idxJ1 = PppCore.IB(satJ, 0, opt);
                int idxJ2 = PppCore.IB(satJ, 1, opt);
                boolean hasDualJ = (opt.ionoopt != Constants.IONOOPT_IFLC) && idxJ2 < nx;

                if (hasDualFreq && hasDualJ) {
                    double freq1J = SatUtils.sat2freq(satJ, rtk.ssat[satJ - 1].code[0][0], nav);
                    double freq2J = SatUtils.sat2freq(satJ, rtk.ssat[satJ - 1].code[1][0], nav);
                    double lam1J = Constants.CLIGHT / freq1J;
                    double lam2J = Constants.CLIGHT / freq2J;
                    double c1i = freq1 / (freq1 + freq2);
                    double c2i = freq2 / (freq1 + freq2);
                    double c1j = freq1J / (freq1J + freq2J);
                    double c2j = freq2J / (freq1J + freq2J);
                    QbNl[i * nb + j] = c1i * c1j * rtk.P[idx1 * nx + idxJ1] / (lam1 * lam1J)
                                      + c1i * c2j * rtk.P[idx1 * nx + idxJ2] / (lam1 * lam2J)
                                      + c2i * c1j * rtk.P[idx2 * nx + idxJ1] / (lam2 * lam1J)
                                      + c2i * c2j * rtk.P[idx2 * nx + idxJ2] / (lam2 * lam2J);
                } else {
                    QbNl[i * nb + j] = rtk.P[idx1 * nx + idxJ1] / (lamNl * lamNl);
                }
            }
        }

        int[] nlFixed = new int[nb];
        double nlRatio = 0.0; // per-satellite rounding path has no ratio semantics

        if (useDualFreqWl) {
            double[] bNl = new double[nb * 2];
            double[] sNl = new double[2];
            info = Lambda.lambda(nb, 2, yNl, QbNl, bNl, sNl);

            if (info != 0) {
                LOG.info("PPP-AR 3F: NL LAMBDA failed: info={}", info);
                return -1;
            }

            nlRatio = sNl[0] > 0 ? sNl[1] / sNl[0] : 0.0;
            rtk.sol.ratio = (float) nlRatio;
            LOG.info("PPP-AR 3F: NL nb={} ratio={}", nb, String.format("%.2f", nlRatio));

            if (nlRatio < cfg.pppArRatioNl) {
                LOG.info("PPP-AR 3F: NL ratio too low: {} < {}", String.format("%.2f", nlRatio), cfg.pppArRatioNl);
                return -1;
            }

            for (int i = 0; i < nb; i++) {
                nlFixed[i] = (int) Math.round(bNl[i * 2]);
            }
        } else {
            // IFLC: diagonal QbNl makes LAMBDA degenerate (ratio ~1.0); fix NL by
            // per-satellite rounding with epoch-median alignment — same datum fix
            // as the WL rounding path (removes the common receiver NL UPD that the
            // satellite-side-only OSB product does not remove).
            double[] fracsNl = new double[nb];
            for (int i = 0; i < nb; i++) {
                fracsNl[i] = yNl[i] - Math.round(yNl[i]);
            }
            double[] sortedNl3f = fracsNl.clone();
            Arrays.sort(sortedNl3f);
            double medNl3f = (nb % 2 == 1) ? sortedNl3f[nb / 2]
                : 0.5 * (sortedNl3f[nb / 2 - 1] + sortedNl3f[nb / 2]);

            int nNlFixed = 0;
            double nlFracMax = 0.0;
            for (int i = 0; i < nb; i++) {
                nlFixed[i] = (int) Math.round(yNl[i] - medNl3f);
                double frac = Math.abs(yNl[i] - medNl3f - nlFixed[i]);
                if (frac < 0.25) nNlFixed++;
                if (frac > nlFracMax) nlFracMax = frac;
            }
            LOG.info(String.format(Locale.US,
                "PPP-AR 3F: NL rounding nb=%d nFixed=%d(%.0f%%) medFrac=%+.4f maxFrac=%.4f",
                nb, nNlFixed, nb > 0 ? 100.0 * nNlFixed / nb : 0, medNl3f, nlFracMax));
            dumpFrac("3F-NL", satList, nb, yNl, nlFixed, medNl3f);

            if (nNlFixed < 4) {
                LOG.info("PPP-AR 3F: too few NL fixed: {}", nNlFixed);
                return -1;
            }
        }

        // Integer-domain cross-validation (delta gate, JAVA-ONLY, FIX-ALG-028):
        // same guard as the MAIN IFLC path — the frac gates are blind to
        // whole-cycle OSB biases; a wrong WL/NL integer shifts the IFLC
        // reconstruction by ~1-3.5 NL cycles and would be frozen into the
        // filter via initx(var=0). Guarded to the IFLC reconstruction (the
        // dual-freq sub-path keeps its ratio tests), off by default.
        if (cfg.enablePppArDeltaGate && opt.ionoopt == Constants.IONOOPT_IFLC) {
            double[] deltas = new double[nb];
            for (int i = 0; i < nb; i++) {
                int sat = satList[i];
                int code0 = rtk.ssat[sat - 1].code[0][0];
                int code1 = rtk.ssat[sat - 1].code[1][0];
                double freq1 = SatUtils.sat2freq(sat, code0, nav);
                double freq2 = SatUtils.sat2freq(sat, code1, nav);
                if (freq1 == 0.0) freq1 = Constants.FREQ1_CMP;
                if (freq2 == 0.0) freq2 = Constants.FREQ2_CMP;
                double lamWl = Constants.CLIGHT / (freq1 - freq2);
                double lamNl = Constants.CLIGHT / (freq1 + freq2);
                double fixedAmb = nlFixed[i] * lamNl
                    + wlFixed[i] * freq2 * lamWl / (freq1 + freq2);
                int idx = PppCore.IB(sat, 0, opt);
                deltas[i] = (fixedAmb - rtk.x[idx]) / lamNl;
            }
            double[] sortedDelta = deltas.clone();
            Arrays.sort(sortedDelta);
            double medDelta = (nb % 2 == 1) ? sortedDelta[nb / 2]
                : 0.5 * (sortedDelta[nb / 2 - 1] + sortedDelta[nb / 2]);
            // Datum self-check (half-cycle bound): applying with a shifted
            // datum freezes wrong integers (var=0) and poisons the series.
            if (Math.abs(medDelta) > cfg.pppArDeltaMax) {
                LOG.info(String.format(Locale.US,
                    "PPP-AR 3F: delta gate datum offset %+.4f > %.2f, skip fix",
                    medDelta, cfg.pppArDeltaMax));
                return -1;
            }
            int nKeepDelta = 0;
            for (int i = 0; i < nb; i++) {
                double dev = deltas[i] - medDelta;
                if (Math.abs(dev) > cfg.pppArDeltaMax) {
                    LOG.info(String.format(Locale.US,
                        "PPP-AR 3F delta gate reject: sat=%d dev=%+.4f cyc",
                        satList[i], dev));
                    continue;
                }
                satList[nKeepDelta] = satList[i];
                nlFixed[nKeepDelta] = nlFixed[i];
                wlFixed[nKeepDelta] = wlFixed[i];
                nKeepDelta++;
            }
            LOG.info(String.format(Locale.US,
                "PPP-AR 3F: delta gate nb=%d nKeep=%d medDelta=%+.4f",
                nb, nKeepDelta, medDelta));
            if (nKeepDelta < 4) {
                LOG.info("PPP-AR 3F: too few ambiguities after delta gate: {}", nKeepDelta);
                return -1;
            }
            nb = nKeepDelta;
        }

        for (int i = 0; i < nb; i++) {
            int sat = satList[i];
            int idx1 = PppCore.IB(sat, 0, opt);
            int idx2 = PppCore.IB(sat, 1, opt);

            int code0 = rtk.ssat[sat - 1].code[0][0];
            int code1 = rtk.ssat[sat - 1].code[1][0];
            double freq1 = SatUtils.sat2freq(sat, code0, nav);
            double freq2 = SatUtils.sat2freq(sat, code1, nav);
            if (freq1 == 0.0) freq1 = Constants.FREQ1_CMP;
            if (freq2 == 0.0) freq2 = Constants.FREQ2_CMP;
            double lam1 = Constants.CLIGHT / freq1;
            double lam2 = Constants.CLIGHT / freq2;
            double lamWl = Constants.CLIGHT / (freq1 - freq2);
            double lamNl = Constants.CLIGHT / (freq1 + freq2);

            int fixedNl = nlFixed[i];

            boolean hasDualFreq = (opt.ionoopt != Constants.IONOOPT_IFLC) && idx2 < nx;
            if (hasDualFreq) {
                // fixedNl is N1 (see nlFloat construction): N1 → L1, N2 = N1 − WL
                double n1 = fixedNl;
                double n2 = fixedNl - wlFixed[i];
                double fixedL1 = n1 * lam1;
                double fixedL2 = n2 * lam2;

                rtk.x[idx1] = fixedL1;
                PppCore.initx(rtk.x, rtk.P, nx, fixedL1, 0.0, idx1);
                rtk.x[idx2] = fixedL2;
                PppCore.initx(rtk.x, rtk.P, nx, fixedL2, 0.0, idx2);

                if (xa != null) {
                    xa[idx1] = fixedL1;
                    xa[idx2] = fixedL2;
                }

                rtk.ssat[sat - 1].fix[0] = 1;
                rtk.ssat[sat - 1].fix[1] = 1;
            } else {
                double fixedAmb = fixedNl * lamNl + wlFixed[i] * freq2 * lamWl / (freq1 + freq2);

                rtk.x[idx1] = fixedAmb;
                PppCore.initx(rtk.x, rtk.P, nx, fixedAmb, 0.0, idx1);

                if (xa != null) {
                    xa[idx1] = fixedAmb;
                }

                rtk.ssat[sat - 1].fix[0] = 1;
            }
        }

        LOG.info("PPP-AR 3F: fixed {} ambiguities (EWL={}/{}, WL ratio={}, NL ratio={})",
                nb, nEwlFixed, nb, String.format("%.2f", wlRatio), String.format("%.2f", nlRatio));

        return nb;
    }

    /** FRAC dump switch (diagnostics): PPPAR_FRAC_DUMP=1 enables per-satellite
     * frac tracing at the rounding fix points (wrong-fix localization). */
    private static final boolean FRAC_DUMP = "1".equals(System.getenv("PPPAR_FRAC_DUMP"));

    private static void dumpFrac(String tag, int[] sats, int n, double[] vals, int[] fixed, double offset) {
        if (!FRAC_DUMP) return;
        for (int i = 0; i < n; i++) {
            LOG.info(String.format(Locale.US, "FRACDUMP %s sat=%d val=%.4f fixed=%d frac=%+.4f",
                tag, sats[i], vals[i], fixed[i], vals[i] - offset - fixed[i]));
        }
    }
}