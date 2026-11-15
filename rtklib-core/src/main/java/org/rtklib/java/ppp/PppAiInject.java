package org.rtklib.java.ppp;

import java.util.Arrays;
import org.rtklib.java.ai.spi.PppObservationContext;
import org.rtklib.java.ai.spi.PppRtkFixContext;
import org.rtklib.java.ai.spi.PppRtkFixValidator;
import org.rtklib.java.ai.spi.PppSatFeature;
import org.rtklib.java.ai.spi.PppSolutionContext;
import org.rtklib.java.ai.spi.PppSolutionValidator;
import org.rtklib.java.ai.spi.PppVarianceScaler;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.AiPppState;
import org.rtklib.java.data.Obsd;
import org.rtklib.java.data.Rtk;
import org.rtklib.java.data.Ssat;
import org.rtklib.java.trace.Trace;

/**
 * PPP 体系 AI 接口的上下文装配与施加（A / B / D）。
 * <p>
 * 只负责「取快照 → 调 provider → 单侧施加」，失效安全与熔断记账全部交给
 * {@link PppAiGuard}。provider 未注入时所有方法首行即返回，不改动任何数值（I-1）。
 * </p>
 * <p>
 * 类本身 {@code public} 只为让 {@code org.rtklib.java.ppprtk} 的 PPP-RTK 固定注入点
 * 复用同一套失效安全运行时；方法可见性保持包内，不构成对外 API。
 * </p>
 */
public final class PppAiInject {

    private PppAiInject() {
    }

    /** 接口 B 解析：未注入返回 {@code null}（熔断期间同样返回 {@code null}，等价未注入）。 */
    static PppVarianceScaler varianceScaler(Rtk rtk) {
        return PppAiGuard.varianceScaler(rtk);
    }

    /**
     * 接口 B 施加：把方差膨胀因子乘到 R 的对角元。
     * <p>
     * 每历元最多一次批量推理，结果按 {@code rtk.epoch} 缓存，同历元的后续迭代直接复用
     * （{@code pppRes} 每历元最多被调用 16 次，不缓存会重复推理）。
     * 只改 R 对角元、只做膨胀（{@code scale >= 1.0}），不改 {@code varr} 与任何状态（S2）。
     * </p>
     *
     * @param nv      本历元被接受的观测行数
     * @param rowSat  行序 → 卫星号（长度 {@code >= nv}）
     * @param rowFrq  行序 → 频点号（长度 {@code >= nv}）
     * @param var     行序 → 观测方差对角线（{@code varr[row]}，长度 {@code >= nv}），仅用于采集侧 σ
     * @param R       待施加的观测方差矩阵（{@code nv × nv}）
     */
    /** 兼容未提供 σ 的调用：{@code var} 取 {@code null}，采集侧 σ 全为 NaN（不影响解算）。 */
    static void applyVarianceScale(Rtk rtk, int nv, int[] rowSat, int[] rowFrq,
                                   double[] R, PppVarianceScaler scaler) {
        applyVarianceScale(rtk, nv, rowSat, rowFrq, null, R, scaler);
    }

    static void applyVarianceScale(Rtk rtk, int nv, int[] rowSat, int[] rowFrq,
                                   double[] var, double[] R, PppVarianceScaler scaler) {
        try {
            applyVarianceScale0(rtk, nv, rowSat, rowFrq, var, R, scaler);
        } catch (Throwable t) {
            PppAiGuard.onDegrade(rtk, AiPppState.IF_VARIANCE, "internal_error");
        }
    }

    private static void applyVarianceScale0(Rtk rtk, int nv, int[] rowSat, int[] rowFrq,
                                           double[] var, double[] R, PppVarianceScaler scaler) {
        if (nv <= 0) {
            return;
        }
        int nf = rtk.opt.nf;
        AiPppState st = PppAiGuard.state(rtk);
        boolean needInfer = st.scaleEpoch != rtk.epoch || !st.scaleValid
                || !sameSats(st, rowSat, nv);
        if (needInfer) {
            if (!infer(rtk, nv, rowSat, rowFrq, var, nf, st, scaler)) {
                return;
            }
        }
        applyRows(rtk, nv, rowSat, rowFrq, nf, R, st);
    }

    /** 是否与缓存命中的卫星集合一致（顺序无关，按行序首次出现顺序）。 */
    private static boolean sameSats(AiPppState st, int[] rowSat, int nv) {
        int n = 0;
        int[] distinct = st.scaleSats;
        for (int j = 0; j < nv; j++) {
            if (!contains(distinct, rowSat[j])) {
                return false;
            }
        }
        for (int j = 0; j < nv; j++) {
            boolean seen = false;
            for (int k = 0; k < j; k++) {
                if (rowSat[k] == rowSat[j]) {
                    seen = true;
                    break;
                }
            }
            if (!seen) {
                n++;
            }
        }
        return n == distinct.length;
    }

    private static boolean contains(int[] arr, int v) {
        for (int i = 0; i < arr.length; i++) {
            if (arr[i] == v) {
                return true;
            }
        }
        return false;
    }

    /**
     * 单次批量推理并写入历元缓存。
     *
     * @return {@code true} = 结果可用；{@code false} = 本历元按不干预处理（S1）
     */
    private static boolean infer(Rtk rtk, int nv, int[] rowSat, int[] rowFrq,
                                 double[] var, int nf,
                                 AiPppState st, PppVarianceScaler scaler) {
        int ns = 0;
        int[] uniq = new int[nv];
        int[] rowIdx = new int[nv];
        for (int j = 0; j < nv; j++) {
            int k = 0;
            while (k < ns && uniq[k] != rowSat[j]) {
                k++;
            }
            if (k == ns) {
                uniq[ns++] = rowSat[j];
            }
            rowIdx[j] = k;
        }

        PppSatFeature[] feats = new PppSatFeature[ns];
        for (int k = 0; k < ns; k++) {
            feats[k] = feature(rtk, uniq[k], nf);
        }
        double[] sigma = new double[ns * nf];
        Arrays.fill(sigma, Double.NaN);
        if (var != null) {
            for (int j = 0; j < nv && j < var.length; j++) {
                int idx = rowIdx[j] * nf + rowFrq[j];
                if (idx >= 0 && idx < sigma.length && var[j] > 0.0) {
                    sigma[idx] = Math.sqrt(var[j]);
                }
            }
        }
        PppObservationContext ctx = new PppObservationContext(
                rtk.epoch, ns, nf, rtk.tt, feats, sigma);

        long budget = PppAiGuard.budgetNanos(rtk);
        long t0 = System.nanoTime();
        double[] raw;
        try {
            raw = scaler.varianceScale(ctx);
        } catch (Throwable t) {
            PppAiGuard.onDegrade(rtk, AiPppState.IF_VARIANCE, "exception");
            st.scaleEpoch = rtk.epoch;
            st.scaleValid = false;
            return false;
        }
        long elapsed = System.nanoTime() - t0;

        if (raw == null || raw.length != ns * nf) {
            PppAiGuard.onDegrade(rtk, AiPppState.IF_VARIANCE,
                    raw == null ? "null" : "invalid_output");
            st.scaleEpoch = rtk.epoch;
            st.scaleValid = false;
            return false;
        }

        double[] scaled = new double[ns * nf];
        boolean anyInflate = false;
        for (int i = 0; i < scaled.length; i++) {
            scaled[i] = PppAiGuard.clampScale(rtk, raw[i]);
            if (scaled[i] > 1.0) {
                anyInflate = true;
            }
        }
        if (!anyInflate) {
            PppAiGuard.onSuccess(rtk, AiPppState.IF_VARIANCE);
            st.scaleEpoch = rtk.epoch;
            st.scaleValid = false;
            return false;
        }

        st.scaleSats = uniq;
        st.scaleVals = scaled;
        st.scaleNf = nf;
        st.scaleEpoch = rtk.epoch;
        st.scaleValid = true;

        if (PppAiGuard.overBudget(t0, budget)) {
            PppAiGuard.onDegrade(rtk, AiPppState.IF_VARIANCE, "over_budget");
        } else {
            PppAiGuard.onSuccess(rtk, AiPppState.IF_VARIANCE);
        }
        if (PppAiGuard.traceDegrades(rtk)) {
            Trace.emit(PppAiGuard.TOPIC, "B_APPLY", rtk.traceConfig, rtk.traceCallback,
                    rtk.epoch, rtk.sol.time,
                    "ns", ns, "nv", nv, "ms", elapsed / 1_000_000.0);
        }
        return true;
    }

    /** 按行序把缓存因子乘到 R 对角元（找不到对应因子时按 1.0，不干预）。 */
    private static void applyRows(Rtk rtk, int nv, int[] rowSat, int[] rowFrq, int nf,
                                  double[] R, AiPppState st) {
        if (!st.scaleValid || st.scaleNf != nf) {
            return;
        }
        for (int j = 0; j < nv; j++) {
            int idx = -1;
            for (int k = 0; k < st.scaleSats.length; k++) {
                if (st.scaleSats[k] == rowSat[j]) {
                    idx = k;
                    break;
                }
            }
            if (idx < 0) {
                continue;
            }
            int frq = rowFrq[j];
            if (frq < 0 || frq >= nf) {
                continue;
            }
            double s = st.scaleVals[idx * nf + frq];
            if (s > 1.0) {
                R[j * nv + j] *= s;
            }
        }
    }

    /** 单星特征快照：非差残差语义，直接取 {@code Ssat.resp/resc}（§4.3）。 */
    private static PppSatFeature feature(Rtk rtk, int sat, int nf) {
        Ssat ss = rtk.ssat[sat - 1];
        double[] snr = new double[nf];
        double[] resp = new double[nf];
        double[] resc = new double[nf];
        int[] lock = new int[nf];
        int[] slip = new int[nf];
        int[] half = new int[nf];
        int[] vsat = new int[nf];
        for (int f = 0; f < nf; f++) {
            snr[f] = ss.snrRover[f];
            resp[f] = ss.resp[f];
            resc[f] = ss.resc[f];
            lock[f] = ss.lock[f];
            slip[f] = ss.slip[f];
            half[f] = ss.half[f];
            vsat[f] = ss.vsat[f];
        }
        return new PppSatFeature(sat, ss.sys, ss.azel[0], ss.azel[1],
                snr, resp, resc, lock, slip, half, vsat);
    }

    /**
     * 接口 A 施加：解有效性置信度低于阈值时把本历元降级为 {@code SOLQ_NONE}。
     * <p>
     * 只在 {@code SOLQ_PPP} 上生效（S4）；确定性 qr 门限已把本历元降级时（{@code SOLQ_NONE}
     * 且 {@code pppQrDegraded}）仍照常调用，用于区分「确定性拦的」与「AI 独立拦的」——
     * 该历元写入是幂等的（§3.3）。{@code SOLQ_FIX} / {@code SOLQ_FLOAT} 一律不触碰。
     * </p>
     */
    static void applySolutionValidator(Rtk rtk) {
        try {
            applySolutionValidator0(rtk);
        } catch (Throwable t) {
            PppAiGuard.onDegrade(rtk, AiPppState.IF_SOLUTION, "internal_error");
        }
    }

    private static void applySolutionValidator0(Rtk rtk) {
        PppSolutionValidator v = PppAiGuard.solutionValidator(rtk);
        if (v == null) {
            return;
        }
        int stat = rtk.sol.stat;
        boolean deterministicDegraded = rtk.pppQrDegraded;
        if (stat != Constants.SOLQ_PPP
                && !(stat == Constants.SOLQ_NONE && deterministicDegraded)) {
            return;
        }
        PppSolutionContext ctx = solutionContext(rtk);
        long budget = PppAiGuard.budgetNanos(rtk);
        long t0 = System.nanoTime();
        Double conf;
        try {
            conf = v.confidence(ctx);
        } catch (Throwable t) {
            PppAiGuard.onDegrade(rtk, AiPppState.IF_SOLUTION, "exception");
            return;
        }
        if (conf == null || !Double.isFinite(conf) || conf < 0.0 || conf > 1.0) {
            PppAiGuard.onDegrade(rtk, AiPppState.IF_SOLUTION, "invalid_output");
            return;
        }
        if (PppAiGuard.overBudget(t0, budget)) {
            PppAiGuard.onDegrade(rtk, AiPppState.IF_SOLUTION, "over_budget");
        } else {
            PppAiGuard.onSuccess(rtk, AiPppState.IF_SOLUTION);
        }
        if (conf < PppAiGuard.solutionMinConfidence(rtk)) {
            rtk.sol.stat = Constants.SOLQ_NONE;
            if (PppAiGuard.traceDegrades(rtk)) {
                Trace.emit(PppAiGuard.TOPIC, "A_DOWNGRADE", rtk.traceConfig, rtk.traceCallback,
                        rtk.epoch, rtk.sol.time, "confidence", conf, "ns", (int) rtk.sol.ns);
            }
        }
    }

    /**
     * 接口 D 施加：PPP-RTK 固定候选提交前的校验闸门（只否决、不优选，§5）。
     *
     * <p>本方法在候选已算好、尚未提交任何求解状态时调用，因此</p>
     * <ul>
     *   <li>返回 {@code true}：调用方按确定性结果提交固定解；</li>
     *   <li>返回 {@code false}：调用方丢弃候选、保持浮点解（S2 不残留脏状态）；</li>
     *   <li>未注入 / 熔断 / {@code null} / NaN / 越界 / 异常 / 超预算：返回 {@code true}
     *       （不干预，保留确定性固定解，S1 只降级不升级）。</li>
     * </ul>
     *
     * @return {@code false} 仅当 provider 给出合法置信度且低于 {@code pppRtkFixMinConfidence}
     */
    public static boolean validateRtkFix(Rtk rtk, PppRtkFixContext ctx) {
        try {
            return validateRtkFix0(rtk, ctx);
        } catch (Throwable t) {
            PppAiGuard.onDegrade(rtk, AiPppState.IF_RTK_FIX, "internal_error");
            return true;
        }
    }

    private static boolean validateRtkFix0(Rtk rtk, PppRtkFixContext ctx) {
        PppRtkFixValidator v = PppAiGuard.rtkFixValidator(rtk);
        if (v == null) {
            return true;
        }
        long budget = PppAiGuard.budgetNanos(rtk);
        long t0 = System.nanoTime();
        Double conf;
        try {
            conf = v.confidence(ctx);
        } catch (Throwable t) {
            PppAiGuard.onDegrade(rtk, AiPppState.IF_RTK_FIX, "exception");
            return true;
        }
        long elapsed = System.nanoTime() - t0;
        if (conf == null || !Double.isFinite(conf) || conf < 0.0 || conf > 1.0) {
            PppAiGuard.onDegrade(rtk, AiPppState.IF_RTK_FIX, "invalid_output");
            return true;
        }
        if (PppAiGuard.overBudget(t0, budget)) {
            PppAiGuard.onDegrade(rtk, AiPppState.IF_RTK_FIX, "over_budget");
            return true;
        }
        PppAiGuard.onSuccess(rtk, AiPppState.IF_RTK_FIX);
        if (conf < PppAiGuard.rtkFixMinConfidence(rtk)) {
            if (PppAiGuard.traceDegrades(rtk)) {
                Trace.emit(PppAiGuard.TOPIC, "RTK_FIX_REJECT", rtk.traceConfig, rtk.traceCallback,
                        rtk.epoch, rtk.sol.time,
                        "confidence", conf, "nfix", ctx.nFixed(),
                        "ratioWl", ctx.ratioWl(), "ratioNl", ctx.ratioNl(),
                        "ms", elapsed / 1_000_000.0);
            }
            return false;
        }
        return true;
    }

    /** 接口 A 快照：位置方差取自 {@code sol.qr}，残差取自 {@code Ssat.resp/resc}。 */
    private static PppSolutionContext solutionContext(Rtk rtk) {
        int nf = rtk.opt.nf;
        int[] sats = new int[Constants.MAXSAT];
        int ns = 0;
        for (int i = 0; i < Constants.MAXSAT && ns < sats.length; i++) {
            if (rtk.ssat[i].vs != 0) {
                sats[ns++] = i + 1;
            }
        }
        double[] elevation = new double[ns];
        double[] snr = new double[ns];
        double[] residuals = new double[ns * nf * 2];
        int nres = 0;
        int biasState = 0;
        for (int k = 0; k < ns; k++) {
            Ssat ss = rtk.ssat[sats[k] - 1];
            elevation[k] = ss.azel[1];
            snr[k] = ss.snrRover[0];
            for (int f = 0; f < nf; f++) {
                residuals[nres++] = ss.resc[f];
                residuals[nres++] = ss.resp[f];
            }
            if (rtk.x[PppCore.IB(sats[k], 0, rtk.opt)] != 0.0) {
                biasState++;
            }
        }
        double[] positionVar = new double[]{
                rtk.sol.qr[0], rtk.sol.qr[1], rtk.sol.qr[2]};
        double convergence = Double.isFinite(rtk.sol.innovRms) ? rtk.sol.innovRms : 0.0;
        return new PppSolutionContext(rtk.epoch, ns, nf, positionVar,
                java.util.Arrays.copyOf(residuals, nres), elevation, snr,
                convergence, biasState, rtk.pppQrDegraded);
    }
}
