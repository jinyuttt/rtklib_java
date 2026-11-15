package org.rtklib.java.ai.feature;

import org.rtklib.java.ai.spi.PppSolutionContext;

/**
 * 接口 A 特征提取（设计文档 §3.3，10 维固定解质量类特征）。
 * <p>
 * 本类只做「上下文 → 原始特征」，不做归一化（见 {@link FeatureNorm}）：
 * 归一化参数必须来自训练集并固化到模型旁，推理侧不得按运行数据动态拟合。
 * </p>
 * <p>
 * 特征顺序定死，训练与推理必须逐位一致（列名 {@code f0..f9}）：
 * </p>
 * <pre>
 * f0 sig_x     ECEF X 位置标准差（sqrt(positionVar[0])，米）
 * f1 sig_y     ECEF Y 位置标准差（米）
 * f2 sig_z     ECEF Z 位置标准差（米）
 * f3 sig_3d    三维位置标准差（sqrt(sum(positionVar))，米）
 * f4 res_rms   非差验后残差 RMS（米）
 * f5 res_max   非差验后残差最大绝对值（米）
 * f6 el_mean   逐星仰角均值（弧度）
 * f7 snr_mean  逐星 SNR 均值（dBHz）
 * f8 innov_rms 新息 RMS（米，滤波收敛度）
 * f9 bias_frac 已初始化偏差状态的卫星占比（biasState / ns）
 * </pre>
 * <p>
 * {@code deterministicDegraded} 刻意<b>不</b>作为特征：它是归因字段（§3.5），
 * 若进入特征会让 A2（仅 AI）与 A3（确定性 + AI）两组的输入分布漂移。
 * </p>
 * <p>
 * 退化处理：{@code positionVar} 缺失/长度不足按 0.0；方差为负按 0.0（钳位后再开方，
 * 避免 {@code NaN}）；残差/仰角/SNR 为空时均值类特征取 0.0；{@code ns <= 0} 时
 * {@code bias_frac = 0.0}，不得除零。一律用 {@code 0.0} 而非 {@code NaN}：
 * {@code NaN} 进入 ONNX 会污染整批输出。
 * </p>
 */
public final class PppSolutionFeatures {

    /** 接口 A 特征维数（与 ONNX 输入维必须相等）。 */
    public static final int DIM = 10;

    public static final int IDX_SIG_X = 0;
    public static final int IDX_SIG_Y = 1;
    public static final int IDX_SIG_Z = 2;
    public static final int IDX_SIG_3D = 3;
    public static final int IDX_RES_RMS = 4;
    public static final int IDX_RES_MAX = 5;
    public static final int IDX_EL_MEAN = 6;
    public static final int IDX_SNR_MEAN = 7;
    public static final int IDX_INNOV_RMS = 8;
    public static final int IDX_BIAS_FRAC = 9;

    private PppSolutionFeatures() {
    }

    /**
     * 提取 {@link #DIM} 维原始特征。
     *
     * @param ctx 接口 A 输入，非 {@code null}
     * @return 长度恒为 {@link #DIM} 的原始特征，可能含 0.0 占位
     */
    public static double[] extractRaw(PppSolutionContext ctx) {
        if (ctx == null) {
            throw new IllegalArgumentException("PppSolutionContext 不得为 null");
        }
        double[] f = new double[DIM];

        double[] pv = ctx.positionVar();
        double vx = variance(pv, 0);
        double vy = variance(pv, 1);
        double vz = variance(pv, 2);
        f[IDX_SIG_X] = Math.sqrt(vx);
        f[IDX_SIG_Y] = Math.sqrt(vy);
        f[IDX_SIG_Z] = Math.sqrt(vz);
        f[IDX_SIG_3D] = Math.sqrt(vx + vy + vz);

        double[] res = ctx.residuals();
        if (res != null && res.length > 0) {
            double sumSq = 0.0;
            double maxAbs = 0.0;
            int n = 0;
            for (double r : res) {
                if (!Double.isFinite(r)) continue;
                sumSq += r * r;
                double a = Math.abs(r);
                if (a > maxAbs) maxAbs = a;
                n++;
            }
            if (n > 0) {
                f[IDX_RES_RMS] = Math.sqrt(sumSq / n);
                f[IDX_RES_MAX] = maxAbs;
            }
        }

        f[IDX_EL_MEAN] = mean(ctx.elevation());
        f[IDX_SNR_MEAN] = mean(ctx.snr());
        f[IDX_INNOV_RMS] = sanitize(ctx.convergenceMetric());
        f[IDX_BIAS_FRAC] = ctx.ns() > 0 ? (double) ctx.biasState() / ctx.ns() : 0.0;
        return f;
    }

    private static double variance(double[] pv, int idx) {
        if (pv == null || pv.length <= idx) return 0.0;
        double v = pv[idx];
        if (!Double.isFinite(v) || v <= 0.0) return 0.0;
        return v;
    }

    private static double mean(double[] a) {
        if (a == null || a.length == 0) return 0.0;
        double sum = 0.0;
        int n = 0;
        for (double v : a) {
            if (!Double.isFinite(v)) continue;
            sum += v;
            n++;
        }
        return n > 0 ? sum / n : 0.0;
    }

    /** 非有限值一律归零：{@code NaN}/{@code Inf} 会污染整批推理输出。 */
    static double sanitize(double v) {
        return Double.isFinite(v) ? v : 0.0;
    }
}