package org.rtklib.java.ppp;

import org.rtklib.java.config.AiOpts;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.Rtk;
import org.rtklib.java.trace.Trace;

/**
 * 确定性基线（非 AI）：位置方差门限降级（§3.2）。
 *
 * 只做一件事：{@code qr[0]+qr[1]+qr[2] > pppQrThreshold} 时把本历元的
 * {@code SOLQ_PPP} 降为 {@code SOLQ_NONE}。默认门限 {@code Double.MAX_VALUE}
 * 即分支关闭，输出与未启用时逐位一致（I-1）。
 *
 * 归因标志 {@code rtk.pppQrDegraded} 的口径与「分支是否启用」解耦：无论门限是否打开，
 * 都按同一判据计算，供接口 A 的上下文使用（§3.3 / §3.5）。该标志只读 {@code sol.qr}，
 * 不改变任何数值。
 */
final class PppQrGate {

    private PppQrGate() {
    }

    /** 当前门限；未配置 AI 选项时取默认值（分支关闭）。 */
    static double threshold(Rtk rtk) {
        AiOpts ai = rtk.rtkConfig == null ? null : rtk.rtkConfig.ai;
        return ai == null ? AiOpts.DEFAULT_PPP_QR_THRESHOLD : ai.pppQrThreshold;
    }

    /**
     * 计算位置方差门限判据，并在门限打开且当前为 {@code SOLQ_PPP} 时施加降级。
     *
     * 只降级 {@code SOLQ_PPP}：{@code SOLQ_FIX}（AR 已固定）与 {@code SOLQ_FLOAT} 不触碰，
     * {@code SOLQ_NONE} 天然幂等（S3 / S4 单调性）。位置方差非有限（{@code NaN} / {@code Inf}）
     * 时判为「不可判定」，按不干预处理（S1 失效方向）。
     *
     * @return 本历元是否满足降级判据（与是否真正写入 {@code sol.stat} 无关）
     */
    static boolean apply(Rtk rtk) {
        double posVar = (double) rtk.sol.qr[0] + rtk.sol.qr[1] + rtk.sol.qr[2];
        boolean degraded = Double.isFinite(posVar) && posVar > threshold(rtk);
        rtk.pppQrDegraded = degraded;
        if (!degraded || rtk.sol.stat != Constants.SOLQ_PPP) {
            return degraded;
        }
        rtk.sol.stat = Constants.SOLQ_NONE;
        AiOpts ai = rtk.rtkConfig == null ? null : rtk.rtkConfig.ai;
        if (ai == null || ai.traceDegrades) {
            Trace.emit(PppAiGuard.TOPIC, "QR_GATE_DOWNGRADE", rtk.traceConfig, rtk.traceCallback,
                    rtk.epoch, rtk.sol.time, "posVar", posVar, "threshold", threshold(rtk),
                    "ns", (int) rtk.sol.ns);
        }
        return degraded;
    }
}