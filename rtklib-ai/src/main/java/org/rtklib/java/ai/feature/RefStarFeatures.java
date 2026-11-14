package org.rtklib.java.ai.feature;

import org.rtklib.java.ai.spi.SatFeature;

/**
 * 接口四特征提取（设计文档 §5.1，输入向量定死 15 维，每行 = 一个 {@code (sat i, frq)}）。
 *
 * 本类只做「单星快照 + core 已算好的窗口统计 → 原始特征」，不做归一化（见 {@link FeatureNorm}），
 * 也不维护任何窗口状态：{@code snrWinMean} / {@code snrWinStd} / {@code refCount}
 * 由 core 在 {@code AiRefStarState} 中维护后经 {@code RefStarContext} 下传，
 * impl 侧重算必然与推理侧错位，且错位是静默的。
 *
 * 列名 {@code f0..f14} 与 §5.1 逐位对应，训练与推理必须完全一致：
 *
 * <pre>
 * f0  el         高度角（弧度转度）
 * f1  az         方位角（度）
 * f2  snrRover   流动站 SNR（dBHz）
 * f3  snrBase    基准站 SNR（dBHz）
 * f4  lock       锁相时长（秒）
 * f5  slip       周跳位标志归一为 0/1
 * f6  half       半周位标志归一为 0/1
 * f7  gf         几何无关组合值（当历元）
 * f8  vsat       该频点是否有效（0/1）
 * f9  fix        模糊度状态 0/1/2/3
 * f10 sysId      星座标识 = numberOfTrailingZeros(sys 位掩码)，不做 one-hot
 * f11 wasRef     近 W 历元内是否当过参考星（refCount &gt; 0 归一为 0/1）
 * f12 snrWinMean 主频点流动站 SNR 近 W 历元均值（dBHz）
 * f13 snrWinStd  主频点流动站 SNR 近 W 历元标准差（dBHz）
 * f14 refCount   近 W 历元内被选为参考星的次数 / W，取值 [0, 1]
 * </pre>
 *
 * <b>{@code resp}/{@code resc} 被结构性禁用（H1）</b>：参考星的残差恒 0 由构造决定，
 * 一旦入特征，模型只需学「残差 == 0 ⇒ 高风险」就能拿到接近 1 的 AUC。core 已在
 * {@code RefStarContext} 构造时把这两维强制置 0，本类<b>不提供</b>对应列，
 * 使泄漏在特征层就无法发生（而不是靠训练时自觉剔除）。
 *
 * {@code wasRef} 的口径说明：训练集里正负例<b>都</b>是参考星（§5.2 要求负例必须显式
 * 断言为参考星），故「当历元是否参考星」在样本内是常量、无信息量；真正可用的是
 * 「近 W 历元的参考星历史」，因此 {@code wasRef} 取 {@code refCount > 0}，
 * 与 {@code refCount} 构成「二值 + 连续」两档，均不含当历元标签。
 */
public final class RefStarFeatures {

    /** 接口四特征维数（§5.1 定死，与 ONNX 输入维必须相等）。 */
    public static final int DIM = 15;

    /** SNR 窗口长度 W（与 {@code AiOpts.refStarWindowEpochs} 默认值一致，仅用于换算）。 */
    public static final int WINDOW = 5;

    public static final int IDX_EL = 0;
    public static final int IDX_AZ = 1;
    public static final int IDX_SNR_ROVER = 2;
    public static final int IDX_SNR_BASE = 3;
    public static final int IDX_LOCK = 4;
    public static final int IDX_SLIP = 5;
    public static final int IDX_HALF = 6;
    public static final int IDX_GF = 7;
    public static final int IDX_VSAT = 8;
    public static final int IDX_FIX = 9;
    public static final int IDX_SYS_ID = 10;
    public static final int IDX_WAS_REF = 11;
    public static final int IDX_SNR_WIN_MEAN = 12;
    public static final int IDX_SNR_WIN_STD = 13;
    public static final int IDX_REF_COUNT = 14;

    private RefStarFeatures() {
    }

    /**
     * 提取单行 15 维原始特征。
     *
     * 退化处理：缺失观测填 {@code 0}（{@code snr} 正常范围 0–60 dBHz，0 可区分）；
     * 非有限值一律归零——{@code NaN} 进入 ONNX 会污染整批输出。
     *
     * @param sf         单星特征，非 {@code null}
     * @param frq        频点下标，越界按缺失处理
     * @param snrWinMean 该星主频点 SNR 窗口均值（dBHz）
     * @param snrWinStd  该星主频点 SNR 窗口标准差（dBHz）
     * @param refCount   该星近 W 历元内被选为参考星的次数（{@code 0..W}）
     * @return 长度恒为 {@link #DIM} 的原始特征
     */
    public static double[] extractRow(SatFeature sf, int frq, double snrWinMean,
                                      double snrWinStd, int refCount) {
        if (sf == null) {
            throw new IllegalArgumentException("SatFeature 不得为 null");
        }
        double[] f = new double[DIM];
        f[IDX_EL] = sanitize(Math.toDegrees(sf.el()));
        f[IDX_AZ] = sanitize(Math.toDegrees(sf.az()));
        f[IDX_SNR_ROVER] = sanitize(pickD(sf.snrRover(), frq));
        f[IDX_SNR_BASE] = sanitize(pickD(sf.snrBase(), frq));
        f[IDX_LOCK] = Math.max(0.0, sanitize(pickI(sf.lock(), frq)));
        f[IDX_SLIP] = pickI(sf.slip(), frq) != 0 ? 1.0 : 0.0;
        f[IDX_HALF] = pickI(sf.half(), frq) != 0 ? 1.0 : 0.0;
        f[IDX_GF] = sanitize(pickD(sf.gf(), frq));
        f[IDX_VSAT] = pickI(sf.vsat(), frq) != 0 ? 1.0 : 0.0;
        f[IDX_FIX] = sanitize(pickI(sf.fix(), frq));
        f[IDX_SYS_ID] = sysId(sf.sys());
        f[IDX_WAS_REF] = refCount > 0 ? 1.0 : 0.0;
        f[IDX_SNR_WIN_MEAN] = sanitize(snrWinMean);
        f[IDX_SNR_WIN_STD] = sanitize(snrWinStd);
        f[IDX_REF_COUNT] = Math.min(1.0, Math.max(0.0, refCount / (double) WINDOW));
        return f;
    }

    /**
     * 星座标识：{@code sys} 是 {@code SYS_??} 位掩码，取尾随零个数。
     *
     * 不做 one-hot（星座数会随版本增长，输入维会漂移）；也绝不能直接喂位掩码
     * （{@code 0x01/0x04/0x08} 在数值上无序关系，MLP 会学出伪序）。
     */
    public static double sysId(int sys) {
        if (sys == 0) return 0.0;
        return Integer.numberOfTrailingZeros(sys);
    }

    private static double pickD(double[] arr, int idx) {
        if (arr == null || idx < 0 || idx >= arr.length) return 0.0;
        return arr[idx];
    }

    private static int pickI(int[] arr, int idx) {
        if (arr == null || idx < 0 || idx >= arr.length) return 0;
        return arr[idx];
    }

    /** 非有限值归零，与接口三一致。 */
    public static double sanitize(double v) {
        return Double.isFinite(v) ? v : 0.0;
    }
}