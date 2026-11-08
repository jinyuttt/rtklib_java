package org.rtklib.java.ai.feature;

import org.rtklib.java.ai.spi.SatFeature;

/**
 * 接口三特征提取（设计文档 §5.3，输入向量定死 17 维，每行 = 一个 {@code (sat i, frq)}）。
 * <p>
 * 本类只做「单星快照 + 窗口聚合 → 原始特征」，不做归一化（见 {@link FeatureNorm}），
 * 也不维护短窗状态（窗口住在 {@code ObsCorrectorImpl}，core 只给当前历元快照）。
 * </p>
 * <p>
 * 列名 {@code f0..f16} 与 §5.3 编号逐位对应，训练与推理必须完全一致：
 * </p>
 * <pre>
 * f0  el            高度角（弧度转度）
 * f1  az            方位角（度）
 * f2  snrRover      流动站 SNR（dBHz）
 * f3  snrBase       基准站 SNR（dBHz）
 * f4  resp          上一历元伪距残差（米）
 * f5  resc          上一历元载波残差（米）
 * f6  lock          锁相时长（秒）
 * f7  slip          周跳位标志归一为 0/1
 * f8  half          半周位标志归一为 0/1
 * f9  gf            几何无关组合值（当历元）
 * f10 vsat          该频点是否有效（0/1）
 * f11 fix           模糊度状态 0/1/2/3
 * f12 sysId         星座标识 = numberOfTrailingZeros(sys 位掩码)，不做 one-hot
 * f13 respWinMean   近 5 历元 resp 均值
 * f14 respWinStd    近 5 历元 resp 标准差（不足 2 样本填 0）
 * f15 rescWinMean   近 5 历元 resc 均值
 * f16 histLen       窗口内实际历元数 / 5，取值 [0.2, 1.0]
 * </pre>
 */
public final class ObsCorrectionFeatures {

    /** 接口三特征维数（§5.3 定死，与 ONNX 输入维必须相等）。 */
    public static final int DIM = 17;

    /** 短窗长度（定值，不做自适应，§5.3）。 */
    public static final int WINDOW = 5;

    public static final int IDX_EL = 0;
    public static final int IDX_AZ = 1;
    public static final int IDX_SNR_ROVER = 2;
    public static final int IDX_SNR_BASE = 3;
    public static final int IDX_RESP = 4;
    public static final int IDX_RESC = 5;
    public static final int IDX_LOCK = 6;
    public static final int IDX_SLIP = 7;
    public static final int IDX_HALF = 8;
    public static final int IDX_GF = 9;
    public static final int IDX_VSAT = 10;
    public static final int IDX_FIX = 11;
    public static final int IDX_SYS_ID = 12;
    public static final int IDX_RESP_WIN_MEAN = 13;
    public static final int IDX_RESP_WIN_STD = 14;
    public static final int IDX_RESC_WIN_MEAN = 15;
    public static final int IDX_HIST_LEN = 16;

    private ObsCorrectionFeatures() {
    }

    /**
     * 短窗聚合结果。{@code n} 为窗口内实际样本数（含当前历元，取值 1..{@value #WINDOW}）。
     */
    public record WindowAgg(double respMean, double respStd, double rescMean, int n) {

        /** 空窗口：均值/标准差为 0，样本数按 0 计（仅用于降级路径）。 */
        public static final WindowAgg EMPTY = new WindowAgg(0.0, 0.0, 0.0, 0);
    }

    /**
     * 提取单行 17 维原始特征。
     * <p>
     * 退化处理（§5.3）：缺失观测填 {@code 0}（{@code snr} 正常范围 0–60 dBHz，0 可区分）；
     * {@code resp}/{@code resc} 对参考星恒 0；{@code histLen} 至少 {@code 0.2}。
     * 非有限值一律归零——{@code NaN} 进入 ONNX 会污染整批输出。
     * </p>
     *
     * @param sf  单星特征，非 {@code null}
     * @param frq 频点下标，必须落在数组长度内（越界按缺失处理）
     * @param agg 该 {@code (sat, frq)} 的短窗聚合，非 {@code null}
     * @return 长度恒为 {@link #DIM} 的原始特征
     */
    public static double[] extractRow(SatFeature sf, int frq, WindowAgg agg) {
        if (sf == null) {
            throw new IllegalArgumentException("SatFeature 不得为 null");
        }
        double[] f = new double[DIM];
        f[IDX_EL] = sanitize(Math.toDegrees(sf.el()));
        f[IDX_AZ] = sanitize(Math.toDegrees(sf.az()));
        f[IDX_SNR_ROVER] = sanitize(pickD(sf.snrRover(), frq));
        f[IDX_SNR_BASE] = sanitize(pickD(sf.snrBase(), frq));
        f[IDX_RESP] = sanitize(pickD(sf.resp(), frq));
        f[IDX_RESC] = sanitize(pickD(sf.resc(), frq));
        f[IDX_LOCK] = sanitize(pickI(sf.lock(), frq));
        f[IDX_SLIP] = pickI(sf.slip(), frq) != 0 ? 1.0 : 0.0;
        f[IDX_HALF] = pickI(sf.half(), frq) != 0 ? 1.0 : 0.0;
        f[IDX_GF] = sanitize(pickD(sf.gf(), frq));
        f[IDX_VSAT] = pickI(sf.vsat(), frq) != 0 ? 1.0 : 0.0;
        f[IDX_FIX] = sanitize(pickI(sf.fix(), frq));
        f[IDX_SYS_ID] = sysId(sf.sys());
        f[IDX_RESP_WIN_MEAN] = sanitize(agg.respMean());
        f[IDX_RESP_WIN_STD] = sanitize(agg.respStd());
        f[IDX_RESC_WIN_MEAN] = sanitize(agg.rescMean());
        f[IDX_HIST_LEN] = agg.n() <= 0 ? 0.0 : Math.min(1.0, (double) agg.n() / WINDOW);
        return f;
    }

    /**
     * 星座标识：{@code sys} 是 {@code SYS_??} 位掩码，取尾随零个数。
     * <p>
     * 不做 one-hot（星座数会随版本增长，输入维会漂移）；也绝不能直接喂位掩码
     * （{@code 0x01/0x04/0x08} 在数值上无序关系，MLP 会学出伪序）。
     * </p>
     */
    public static double sysId(int sys) {
        if (sys == 0) return 0.0;
        return Integer.numberOfTrailingZeros(sys);
    }

    private static double pickD(double[] arr, int idx) {
        if (arr == null || idx < 0 || idx >= arr.length) return 0.0;
        return arr[idx];
    }

    private static double pickI(int[] arr, int idx) {
        if (arr == null || idx < 0 || idx >= arr.length) return 0.0;
        return arr[idx];
    }

    /** 非有限值归零，与接口一一致（§5.1/§5.3）。 */
    public static double sanitize(double v) {
        return Double.isFinite(v) ? v : 0.0;
    }
}