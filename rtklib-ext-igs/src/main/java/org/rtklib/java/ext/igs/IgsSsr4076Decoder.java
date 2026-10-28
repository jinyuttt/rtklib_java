package org.rtklib.java.ext.igs;

import org.rtklib.java.common.BitUtils;
import org.rtklib.java.rtcm.CustomMessageDecoder;
import org.rtklib.java.rtcm.Rtcm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * IGS SSR 4076 骨架解码器。
 *
 * <p>4076 是 RTCM 分配给 IGS 的专有消息号，不是 RTCM 通用标准 SSR。
 * 本类解析公共头部、子类型识别与 IM201 的电离层球谐模型：</p>
 * <ul>
 *   <li>卫星级子类型（IM21~IM27、IM41~…、IM101~、IM121~）：历元、更新间隔、
 *       IOD、提供者/解算 ID，以及轨道类的 CRS 指示、相位偏差类的一致性指示、卫星数；</li>
 *   <li>IM201（电离层 VTEC 球谐）：公共头部、VTEC 质量指示、分层数，以及逐层的
 *       层高/阶 N/次 M 与 C/S 球谐系数；</li>
 *   <li>IM202：与 IM201 共用头部，但标准未定义其后续系数布局，只解析公共头。</li>
 * </ul>
 * <p><b>不解析</b>卫星级改正数（后续迭代再做）。</p>
 *
 * <p>版本字段：IGS SSR v1.00 规定为 1，但实测 CAS 的 *CAS1 流为 0，
 * 因此 0 和 1 都接受。</p>
 */
public class IgsSsr4076Decoder implements CustomMessageDecoder {

    /** IGS 在 RTCM 中占用的专有消息号 */
    public static final int MESSAGE_TYPE = 4076;

    private static final Logger log = LoggerFactory.getLogger(IgsSsr4076Decoder.class);

    /** 4 bit 更新间隔编码 → 秒 */
    private static final int[] UPDATE_INTERVAL_SEC = {
            1, 2, 5, 10, 15, 30, 60, 120, 240, 300, 600, 900, 1800, 3600, 7200, 10800
    };

    /** 公共头部位数：12(消息号)+3(版本)+8(子类型) */
    private static final int COMMON_START = 23;
    /** 公共头部字段位数：历元20+间隔4+多消息指示1+IOD4+提供者16+解算4 */
    private static final int COMMON_BITS = 49;

    /** 球谐系数缩放：int16 × 0.005 TECU */
    private static final double COEFFICIENT_SCALE = 0.005;

    private final IgsSsr4076Listener listener;

    /** 不带监听器：消息被识别并消费，但不回调 */
    public IgsSsr4076Decoder() {
        this(null);
    }

    /**
     * @param listener 头部解析完成后的回调，可为 null
     */
    public IgsSsr4076Decoder(IgsSsr4076Listener listener) {
        this.listener = listener;
    }

    @Override
    public int messageType() {
        return MESSAGE_TYPE;
    }

    @Override
    public boolean decode(Rtcm rtcm, int type, byte[] payload, int payloadLen) {
        int bitLen = payloadLen * 8;
        if (bitLen < COMMON_START + COMMON_BITS) {
            log.warn("IGS SSR 4076 消息过短: {} 字节", payloadLen);
            return false;
        }

        int version = (int) BitUtils.getbitu(payload, 12, 3);
        int subtype = (int) BitUtils.getbitu(payload, 15, 8);
        if (version > 1) {
            log.warn("IGS SSR 4076 不支持的版本: {}（子类型 {}）", version, subtype);
            return false;
        }

        IgsSsr4076Message msg = new IgsSsr4076Message();
        msg.version = version;
        msg.subtype = subtype;
        msg.payloadLength = payloadLen;

        boolean parsed;
        if (isSatelliteSubtype(subtype)) {
            parsed = parseSatelliteHeader(payload, bitLen, msg);
        } else if (subtype == 201 || subtype == 202) {
            parsed = parseVtecHeader(payload, bitLen, msg);
        } else {
            // 未知子类型：只报类型，不猜测其布局
            log.debug("IGS SSR 4076 未识别的子类型: {}", subtype);
            parsed = true;
        }

        if (parsed && listener != null) {
            listener.onIgsSsr4076Message(msg);
        }
        return parsed;
    }

    /**
     * 解析卫星级子类型的公共头部。
     * @return true 解析成功
     */
    private boolean parseSatelliteHeader(byte[] buff, int bitLen, IgsSsr4076Message msg) {
        int p = readCommonHeader(buff, bitLen, msg);
        if (p < 0) return false;

        int index = (msg.subtype - 21) % 20;
        if (index == 0 || index == 2) {
            // 轨道改正、轨道+钟差：含全球/区域 CRS 指示
            if (!hasBits(bitLen, p, 1)) return false;
            msg.crsIndicator = (int) BitUtils.getbitu(buff, p, 1);
            p += 1;
        } else if (index == 5) {
            // 相位偏差：含色散一致性与 MW 一致性指示
            if (!hasBits(bitLen, p, 2)) return false;
            msg.consistencyMaintained = (int) BitUtils.getbitu(buff, p, 1);
            msg.mwConsistencyMaintained = (int) BitUtils.getbitu(buff, p, 1);
            p += 2;
        }

        if (!hasBits(bitLen, p, 6)) return false;
        msg.numberOfSatellites = (int) BitUtils.getbitu(buff, p, 6);
        return true;
    }

    /**
     * 解析 IM201/IM202 头部（IM202 按 IGS SSR v1 与 IM201 共用头部）。
     * <p>IM201 继续解析逐层的层头与 C/S 球谐系数；IM202 只到公共头。</p>
     * @return true 解析成功
     */
    private boolean parseVtecHeader(byte[] buff, int bitLen, IgsSsr4076Message msg) {
        int p = readCommonHeader(buff, bitLen, msg);
        if (p < 0) return false;
        // VTEC 质量指示 9 bit + 分层数 2 bit
        if (!hasBits(bitLen, p, 11)) return false;
        msg.vtecQualityIndicator = BitUtils.getbitu(buff, p, 9) * 0.05;
        p += 9;
        int layers = (int) BitUtils.getbitu(buff, p, 2) + 1;
        msg.numberOfIonosphericLayers = layers;
        p += 2;
        if (msg.subtype != 201) {
            // IM202 的系数布局未由 IGS SSR v1 定义，不做臆测解析
            return true;
        }
        return parseVtecLayers(buff, bitLen, p, layers, msg);
    }

    /**
     * 解析 IM201 的逐层模型：层头（16 bit）+ 余弦系数（NC 个）+ 正弦系数（NS 个）。
     * <p>系数排列为先把 C 全部读完再读 S，各自按 m 外层、n 内层递增。</p>
     * @return true 解析成功
     */
    private boolean parseVtecLayers(byte[] buff, int bitLen, int pos, int layerCount,
                                    IgsSsr4076Message msg) {
        int p = pos;
        for (int i = 0; i < layerCount; i++) {
            if (!hasBits(bitLen, p, 16)) {
                log.warn("IGS SSR IM201 第 {} 层头部位数不足", i);
                return false;
            }
            double heightKm = BitUtils.getbitu(buff, p, 8) * 10.0;
            int degree = (int) BitUtils.getbitu(buff, p + 8, 4) + 1;
            int order = (int) BitUtils.getbitu(buff, p + 12, 4) + 1;
            p += 16;
            if (order > degree) {
                log.warn("IGS SSR IM201 第 {} 层阶次非法: N={}, M={}", i, degree, order);
                return false;
            }

            int nc = coefficientCount(degree, order);
            int ns = nc - (degree + 1);
            if (!hasBits(bitLen, p, (nc + ns) * 16)) {
                log.warn("IGS SSR IM201 第 {} 层系数位数不足: 需要 {} bit", i, (nc + ns) * 16);
                return false;
            }

            double[][] cnm = new double[degree + 1][degree + 1];
            double[][] snm = new double[degree + 1][degree + 1];
            for (int m = 0; m <= order; m++) {
                for (int n = m; n <= degree; n++) {
                    cnm[n][m] = BitUtils.getbits(buff, p, 16) * COEFFICIENT_SCALE;
                    p += 16;
                }
            }
            for (int m = 1; m <= order; m++) {
                for (int n = m; n <= degree; n++) {
                    snm[n][m] = BitUtils.getbits(buff, p, 16) * COEFFICIENT_SCALE;
                    p += 16;
                }
            }
            msg.ionosphericLayers.add(new IgsSsrVtecLayer(heightKm, degree, order, cnm, snm));
        }
        return true;
    }

    /** 余弦系数个数 NC = (N+1)(N+2)/2 − (N−M)(N−M+1)/2 */
    private static int coefficientCount(int degree, int order) {
        return (degree + 1) * (degree + 2) / 2 - (degree - order) * (degree - order + 1) / 2;
    }

    /**
     * 读取所有卫星级与 VTEC 子类型共用的头部字段。
     * @return 下一个字段的位偏移；位数不足时返回 -1
     */
    private static int readCommonHeader(byte[] buff, int bitLen, IgsSsr4076Message msg) {
        int p = COMMON_START;
        if (!hasBits(bitLen, p, COMMON_BITS)) return -1;

        msg.epochTimeSec = (int) BitUtils.getbitu(buff, p, 20);
        p += 20;
        int updateCode = (int) BitUtils.getbitu(buff, p, 4);
        p += 4;
        msg.updateIntervalSec = UPDATE_INTERVAL_SEC[updateCode];
        msg.multipleMessageIndicator = (int) BitUtils.getbitu(buff, p, 1);
        p += 1;
        msg.iodSsr = (int) BitUtils.getbitu(buff, p, 4);
        p += 4;
        msg.providerId = (int) BitUtils.getbitu(buff, p, 16);
        p += 16;
        msg.solutionId = (int) BitUtils.getbitu(buff, p, 4);
        p += 4;
        return p;
    }

    /** 卫星级子类型：基础 21，各系统依次 +20，内容编码 0~6 */
    private static boolean isSatelliteSubtype(int subtype) {
        return subtype >= 21 && subtype <= 127 && (subtype - 21) % 20 <= 6;
    }

    private static boolean hasBits(int bitLen, int pos, int n) {
        return pos + n <= bitLen;
    }
}
