package org.rtklib.java.ai.feature;

import org.rtklib.java.ai.spi.PppSatFeature;

/**
 * 接口 B 特征提取（设计文档 §4.3，12 维逐星逐频特征）。
 *
 * 只做「上下文 → 原始特征」，不做归一化（见 {@link FeatureNorm}）：归一化参数必须来自
 * 训练集并固化到模型旁，推理侧不得按运行数据动态拟合。
 *
 * 特征顺序定死，训练与推理必须逐位一致（列名 {@code f0..f11}，见
 * {@code training/common/ppp_dataset.py} 的 {@code B_FEATURE_NAMES}）：
 *
 * f0  el        高度角（弧度）
 * f1  az        方位角（弧度）
 * f2  snr       该频点 SNR（dBHz）
 * f3  resp      该频点非差伪距残差（米，带符号）
 * f4  resc      该频点非差载波残差（米，带符号）
 * f5  abs_resp  伪距残差绝对值（米）
 * f6  abs_resc  载波残差绝对值（米）
 * f7  lock      锁相时长（秒）
 * f8  slip      周跳位标志（0/1，非累计计数）
 * f9  half      半周位标志（0/1）
 * f10 vsat      该频点是否参与解算（0/1）
 * f11 frq       频点号（0 起）
 *
 * {@code resp} / {@code resc} 是**非差**语义（接收机钟差与卫星钟差均未消掉），
 * 与接口三的双差残差严格区分（§4.3）：双差残差分布不同，两个训练集不可互换、不可拼接。
 *
 * 退化处理：数组长度不足该频点、或值非有限，一律取 {@code 0.0}。用 {@code 0.0} 而非
 * {@code NaN}：{@code NaN} 进入 ONNX 会污染整批输出，使整历元的因子全部失效。
 */
public final class PppVarianceFeatures {

    /** 接口 B 特征维数（与 ONNX 输入维必须相等）。 */
    public static final int DIM = 12;

    public static final int IDX_EL = 0;
    public static final int IDX_AZ = 1;
    public static final int IDX_SNR = 2;
    public static final int IDX_RESP = 3;
    public static final int IDX_RESC = 4;
    public static final int IDX_ABS_RESP = 5;
    public static final int IDX_ABS_RESC = 6;
    public static final int IDX_LOCK = 7;
    public static final int IDX_SLIP = 8;
    public static final int IDX_HALF = 9;
    public static final int IDX_VSAT = 10;
    public static final int IDX_FRQ = 11;

    private PppVarianceFeatures() {
    }

    /**
     * 提取某颗星某频点的 {@link #DIM} 维原始特征。
     *
     * @param sat 单星特征，非 {@code null}
     * @param frq 频点号（0 起）
     * @return 长度恒为 {@link #DIM} 的原始特征，可能含 0.0 占位
     */
    public static double[] extractRaw(PppSatFeature sat, int frq) {
        if (sat == null) {
            throw new IllegalArgumentException("PppSatFeature 不得为 null");
        }
        double[] f = new double[DIM];
        f[IDX_EL] = sanitize(sat.el());
        f[IDX_AZ] = sanitize(sat.az());
        f[IDX_SNR] = at(sat.snr(), frq);
        double resp = at(sat.resp(), frq);
        double resc = at(sat.resc(), frq);
        f[IDX_RESP] = resp;
        f[IDX_RESC] = resc;
        f[IDX_ABS_RESP] = Math.abs(resp);
        f[IDX_ABS_RESC] = Math.abs(resc);
        f[IDX_LOCK] = (double) iat(sat.lock(), frq);
        f[IDX_SLIP] = (double) iat(sat.slip(), frq);
        f[IDX_HALF] = (double) iat(sat.half(), frq);
        f[IDX_VSAT] = (double) iat(sat.vsat(), frq);
        f[IDX_FRQ] = frq;
        return f;
    }

    private static double at(double[] a, int frq) {
        if (a == null || frq < 0 || frq >= a.length) return 0.0;
        return sanitize(a[frq]);
    }

    private static int iat(int[] a, int frq) {
        if (a == null || frq < 0 || frq >= a.length) return 0;
        return a[frq];
    }

    /** 非有限值一律归零：{@code NaN}/{@code Inf} 会污染整批推理输出。 */
    static double sanitize(double v) {
        return Double.isFinite(v) ? v : 0.0;
    }
}