package org.rtklib.java.ai.feature;

import org.rtklib.java.ai.spi.FixQualityContext;

/**
 * 接口一特征提取（设计文档 §5.1，方案 B：6 维固定后残差类特征）。
 * <p>
 * 本类只做「上下文 → 原始特征」，不做归一化（见 {@link FeatureNorm}）：
 * 归一化参数必须来自训练集并固化到模型旁，推理侧不得按运行数据动态拟合（§5.1）。
 * </p>
 * <p>
 * 特征顺序定死，训练与推理必须逐位一致（列名 {@code f0..f5} 与 §5.1 编号对应）：
 * </p>
 * <pre>
 * f0 adr      模糊度差 RMS（整数解离浮点解多远）
 * f1 prr      载波残差 RMS（整数解与相位的拟合一致度）
 * f2 pcr      伪距残差 RMS（整数解与伪距的拟合一致度）
 * f3 ratio    LAMBDA ratio（搜索空间可分性，保留作对照基线）
 * f4 ns_fixed nb / ns（几何冗余度）
 * f5 pdop     位置 DOP（几何强度）
 * </pre>
 */
public final class FixQualityFeatures {

    /** 接口一特征维数（§5.1 定死，与 ONNX 输入维必须相等）。 */
    public static final int DIM = 6;

    public static final int IDX_ADR = 0;
    public static final int IDX_PRR = 1;
    public static final int IDX_PCR = 2;
    public static final int IDX_RATIO = 3;
    public static final int IDX_NS_FIXED = 4;
    public static final int IDX_PDOP = 5;

    private FixQualityFeatures() {
    }

    /**
     * 提取 6 维原始特征。
     * <p>
     * 退化处理（§5.1）：
     * </p>
     * <ul>
     *   <li>{@code nPhaseRows == 0} → {@code prr = 0.0}（占位值而非真实测量）</li>
     *   <li>{@code nCodeRows == 0} → {@code pcr = 0.0}</li>
     *   <li>{@code ns <= 0} → {@code ns_fixed = 0.0}，不得除零</li>
     * </ul>
     * <p>
     * 一律用 {@code 0.0} 而非 {@code NaN}：{@code NaN} 进入 ONNX 会污染整个 batch 输出。
     * </p>
     *
     * @param ctx 接口一输入，非 {@code null}
     * @return 长度恒为 {@link #DIM} 的原始特征，可能含 0.0 占位
     */
    public static double[] extractRaw(FixQualityContext ctx) {
        if (ctx == null) {
            throw new IllegalArgumentException("FixQualityContext 不得为 null");
        }
        double[] f = new double[DIM];
        f[IDX_ADR] = sanitize(ctx.adrRms());
        f[IDX_PRR] = ctx.nPhaseRows() > 0 ? sanitize(ctx.prrRms()) : 0.0;
        f[IDX_PCR] = ctx.nCodeRows() > 0 ? sanitize(ctx.pcrRms()) : 0.0;
        f[IDX_RATIO] = sanitize(ctx.ratio());
        f[IDX_NS_FIXED] = ctx.ns() > 0 ? (double) ctx.nb() / ctx.ns() : 0.0;
        f[IDX_PDOP] = sanitize(ctx.pdop());
        return f;
    }

    /**
     * 非有限值一律归零：{@code NaN}/{@code Inf} 会让整批推理输出污染，且这些量在
     * 上游都是 {@code sqrt(mean(...))} 或 DOP 计算结果，出现非有限值只可能是上游退化，
     * 按「无信息」处理比传播毒值安全（§5.1）。
     */
    static double sanitize(double v) {
        return Double.isFinite(v) ? v : 0.0;
    }
}