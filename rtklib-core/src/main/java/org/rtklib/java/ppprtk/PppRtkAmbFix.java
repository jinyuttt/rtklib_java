package org.rtklib.java.ppprtk;

import java.util.Locale;

import org.rtklib.java.ai.spi.PppRtkFixContext;
import org.rtklib.java.ambiguity.Lambda;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.kalman.KalmanFilter;
import org.rtklib.java.ppp.PppAiInject;
import org.rtklib.java.ppp.PppAmbFix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PppRtkAmbFix {
    private PppRtkAmbFix() {
    }

    private static final Logger LOG = LoggerFactory.getLogger(PppRtkAmbFix.class);

    public static void ppprtkAmbFix(Rtk rtk, Nav nav) {
        RtkConfig cfg = rtk.rtkConfig;
        if (cfg == null || !cfg.pppRtk.enableAR) return;

        PrcOpt opt = rtk.opt;

        if (opt.ionoopt == Constants.IONOOPT_IFLC) {
            ppprtkAmbFixIf(rtk, nav);
        } else {
            ppprtkAmbFixUc(rtk, nav);
        }
    }

    /**
     * IFLC模式AR：x[IB]存储IF组合模糊度（米），不是整数，不能直接LAMBDA。
     * 必须用WL+NL两步固定（委托给PppAmbFix.pppAmbFixWlNlCandidate）。
     */
    private static void ppprtkAmbFixIf(Rtk rtk, Nav nav) {
        RtkConfig cfg = rtk.rtkConfig;
        int nx = rtk.nx;

        PppAmbFix.WlNlFix cand = PppAmbFix.pppAmbFixWlNlCandidate(rtk, null, 0, 0, 0, nav);
        if (cand == null) {
            LOG.debug("PPP-RTK AR (IF): WL+NL fix failed");
            return;
        }

        PppRtkFixContext ctx = fixContext(rtk, cand.ratioWl, cand.ratioNl,
                cand.maxDevWl, cand.maxDevNl, cand.postResidualRms, cand.nFixed);
        if (!PppAiInject.validateRtkFix(rtk, ctx)) {
            LOG.debug("PPP-RTK AR (IF): rejected by AI validator, keep float solution");
            return;
        }

        double[] xa = rtk.xa;
        if (xa == null || xa.length != nx) {
            xa = new double[nx];
        }
        System.arraycopy(rtk.x, 0, xa, 0, nx);

        if (rtk.Pa == null || rtk.Pa.length != nx * nx) {
            rtk.Pa = new double[nx * nx];
        }
        System.arraycopy(rtk.P, 0, rtk.Pa, 0, nx * nx);

        cand.applyTo(xa, rtk.Pa, nx);
        cand.applyTo(rtk.x, rtk.P, nx);
        for (int i = 0; i < cand.nFixed; i++) {
            rtk.ssat[cand.sats[i] - 1].fix[cand.freqs[i]] = 1;
        }

        rtk.xa = xa;
        rtk.nfix++;

        if (cfg.pppRtk.enableFixHold && rtk.nfix >= cfg.pppRtk.fixHoldMinEpoch) {
            PppAmbFix.pppArFixHold(rtk, nav);
        }

        for (int i = 0; i < 3 && i < nx; i++) {
            rtk.sol.rr[i] = xa[i];
        }
        rtk.sol.stat = Constants.SOLQ_FIX;
        LOG.debug("PPP-RTK AR (IF): fixed {} ambiguities via WL+NL cascade", cand.nFixed);
    }

    /** 接口 D 上下文装配：只读取候选与当前历元快照，不写任何状态。 */
    private static PppRtkFixContext fixContext(Rtk rtk, double ratioWl, double ratioNl,
                                               double maxDevWl, double maxDevNl,
                                               double postResidualRms, int nFixed) {
        double positionVar = 0.0;
        if (rtk.sol.qr != null) {
            for (int i = 0; i < 3 && i < rtk.sol.qr.length; i++) {
                positionVar += rtk.sol.qr[i];
            }
        }
        return new PppRtkFixContext(rtk.epoch, rtk.sol.ns, ratioWl, ratioNl,
                maxDevWl, maxDevNl, postResidualRms, positionVar,
                rtk.sol.pdop, rtk.sol.prev_ratio1, rtk.sol.prev_ratio2,
                nFixed, 1, rtk.nfix, true);
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
            if (rtk.ssat[sat - 1].azel[1] < opt.elmaskar) continue;

            // [FIX-ALG-060] 系统白名单原为写死的 GPS|GAL，navsys 只配 BDS/QZS 时 UC 路径
            // 静默全跳过（与 FIX-ALG-058 同类缺陷）。改为动态继承 opt.navsys。
            int sys = SatUtils.satsys(sat, null);
            if ((sys & opt.navsys) == 0) continue;

            for (int f = 0; f < nf; f++) {
                if (rtk.ssat[sat - 1].vsat[f] == 0) continue;
                if ((rtk.ssat[sat - 1].slip[f] & Constants.LLI_HALFC) != 0) continue;

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
        double[] lamArr = new double[nb];
        for (int i = 0; i < nb; i++) {
            double freq = SatUtils.sat2freq(satList[i], rtk.ssat[satList[i] - 1].code[freqList[i]][0], nav);
            lamArr[i] = freq > 0 ? Constants.CLIGHT / freq : 0.0;
            a[i] = lamArr[i] > 0.0 ? rtk.x[idxList[i]] / lamArr[i] : 0.0;
        }
        // 米 → 周的单位变换是逐轴对角变换 D=diag(1/lam_i)，
        // 因此协方差必须做完整的合同变换 Q_ij = P_ij/(lam_i*lam_j)。
        // 若对 (i,j) 一律用 lam_i²（旧实现），矩阵不再对称，
        // 对角元素不同频点混排时（如 B1I 0.1920 m 与 B3I 0.2364 m）
        // 会失去正定性，LD 分解的首个对角元即可为负（factorLD 返回 -1）。
        for (int i = 0; i < nb; i++) {
            for (int j = 0; j < nb; j++) {
                double d = lamArr[i] * lamArr[j];
                Q[i * nb + j] = d > 0.0 ? rtk.P[idxList[i] * nx + idxList[j]] / d : 0.0;
            }
        }

        int m = 2;
        double[] F = new double[nb * m];
        double[] s = new double[m];

        if (Lambda.debugAr()) {
            StringBuilder ambSb = new StringBuilder();
            int lamDistinct = 0;
            double lamFirst = 0.0;
            for (int i = 0; i < nb; i++) {
                boolean seen = false;
                for (int k = 0; k < i; k++) {
                    if (Math.abs(lamArr[k] - lamArr[i]) < 1e-9) { seen = true; break; }
                }
                if (!seen) {
                    lamDistinct++;
                    if (lamDistinct == 1) lamFirst = lamArr[i];
                }
                if (i > 0) ambSb.append(", ");
                ambSb.append(SatUtils.satno2id(satList[i])).append("/").append(freqList[i]).append("/").append(String.format(Locale.US, "%.4f", lamArr[i]));
            }
            LOG.info("[AR-AMB] UC nb={} lamDistinct={} lamFirst={} list=[{}]",
                    nb, lamDistinct, String.format(Locale.US, "%.4f", lamFirst), ambSb);
            LOG.info("[AR-Q] UC {}", Lambda.qDiagnostics(nb, Q));
        }
        int[] failInfo = new int[1];
        int info = Lambda.lambda(nb, m, a, Q, F, s, failInfo);
        if (info != 0) {
            if (failInfo[0] >= 0 && failInfo[0] < nb) {
                int fi = failInfo[0];
                LOG.info("[AR-Q-FAIL] UC {} failIdx={} sat={} freq={}",
                        Lambda.qDiagnostics(nb, Q), fi, satList[fi], freqList[fi]);
            }
            LOG.debug("PPP-RTK AR: LAMBDA failed info={}", info);
            return;
        }

        double ratio = (s[0] > 0) ? Math.min(s[1] / s[0], 999.0) : 999.0;
        double arRatio = cfg.pppRtk.arRatio;

        LOG.debug("PPP-RTK AR: nb={} ratio={} threshold={}", nb, String.format("%.3f", ratio), String.format("%.3f", arRatio));

        if (ratio < arRatio) {
            LOG.debug("PPP-RTK AR: ratio test failed");
            return;
        }

        double[] fixedAmb = new double[nb];
        int[] keepIdx = new int[nb];
        int[] keepSat = new int[nb];
        int[] keepFrq = new int[nb];
        int nKeep = 0;
        double maxDev = 0.0;
        double sumDevSq = 0.0;

        for (int i = 0; i < nb; i++) {
            int idx = idxList[i];
            double freq = SatUtils.sat2freq(satList[i], rtk.ssat[satList[i] - 1].code[freqList[i]][0], nav);
            double lam = freq > 0 ? Constants.CLIGHT / freq : 0.0;
            if (lam == 0.0) continue;

            double amb = F[i * m] * lam;
            double dev = (amb - rtk.x[idx]) / lam;

            keepIdx[nKeep] = idx;
            keepSat[nKeep] = satList[i];
            keepFrq[nKeep] = freqList[i];
            fixedAmb[nKeep] = amb;
            nKeep++;

            sumDevSq += dev * dev;
            if (Math.abs(dev) > maxDev) maxDev = Math.abs(dev);
        }

        if (nKeep <= 0) {
            LOG.debug("PPP-RTK AR: no fixable ambiguity");
            return;
        }

        PppRtkFixContext ctx = fixContext(rtk, Double.NaN, ratio, Double.NaN, maxDev,
                Math.sqrt(sumDevSq / nKeep), nKeep);
        if (!PppAiInject.validateRtkFix(rtk, ctx)) {
            LOG.debug("PPP-RTK AR: rejected by AI validator, keep float solution");
            return;
        }

        double[] xa = rtk.xa;
        double[] Pa = rtk.Pa;
        if (xa == null || xa.length != nx || Pa == null || Pa.length != nx * nx) {
            xa = new double[nx];
            Pa = new double[nx * nx];
        }
        System.arraycopy(rtk.x, 0, xa, 0, nx);
        System.arraycopy(rtk.P, 0, Pa, 0, nx * nx);

        for (int i = 0; i < nKeep; i++) {
            int idx = keepIdx[i];
            xa[idx] = fixedAmb[i];
            for (int j = 0; j < nx; j++) {
                Pa[idx * nx + j] = 0.0;
                Pa[j * nx + idx] = 0.0;
            }
            Pa[idx * nx + idx] = 0.0;

            rtk.ssat[keepSat[i] - 1].fix[keepFrq[i]] = 1;
        }

        rtk.xa = xa;
        rtk.Pa = Pa;
        rtk.nfix++;

        if (cfg.pppRtk.enableFixHold && rtk.nfix >= cfg.pppRtk.fixHoldMinEpoch) {
            fixAndHold(rtk, nav, keepSat, keepFrq, keepIdx, nKeep);
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
        double holdVar = cfg.pppRtk.fixHoldVar;

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
