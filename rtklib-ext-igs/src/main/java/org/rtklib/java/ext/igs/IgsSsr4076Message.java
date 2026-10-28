package org.rtklib.java.ext.igs;

import java.util.ArrayList;
import java.util.List;

/**
 * IGS SSR（RTCM 专有消息号 4076）一条消息的公共头部解析结果。
 *
 * <p>本类承载头部信息、子类型识别，以及 IM201 的电离层球谐模型；
 * 卫星级改正数仍未解析。未解析的字段统一为 {@link #NO_FIELD}（或 {@link Double#NaN}）。</p>
 */
public class IgsSsr4076Message {

    /** 表示该字段在当前子类型中不存在或未解析 */
    public static final int NO_FIELD = -1;

    /** IGS SSR 版本（3 bit）。规范 v1.00 为 1；实测 CAS 的 *CAS1 流为 0 */
    public int version;
    /** IGS 子类型消息号（8 bit），例如 21、201 */
    public int subtype;
    /** RTCM3 消息体字节数（不含 3 字节帧头与 3 字节 CRC） */
    public int payloadLength;

    /** SSR 历元时间（20 bit，秒；需结合周数才能换算绝对时间） */
    public int epochTimeSec = NO_FIELD;
    /** SSR 更新间隔（秒） */
    public int updateIntervalSec = NO_FIELD;
    /** 多消息指示（1 bit） */
    public int multipleMessageIndicator = NO_FIELD;
    /** IOD SSR（4 bit） */
    public int iodSsr = NO_FIELD;
    /** SSR 提供者 ID（16 bit） */
    public int providerId = NO_FIELD;
    /** SSR 解算 ID（4 bit） */
    public int solutionId = NO_FIELD;
    /** 全球/区域坐标框架指示（1 bit），仅轨道类消息有 */
    public int crsIndicator = NO_FIELD;
    /** 相位偏差一致性指示（1 bit），仅相位偏差消息有 */
    public int consistencyMaintained = NO_FIELD;
    /** MW 一致性指示（1 bit），仅相位偏差消息有 */
    public int mwConsistencyMaintained = NO_FIELD;
    /** 卫星数（6 bit），卫星级子类型解析，否则为 {@link #NO_FIELD} */
    public int numberOfSatellites = NO_FIELD;
    /** 电离层分层数，仅 IM201/IM202 有 */
    public int numberOfIonosphericLayers = NO_FIELD;
    /** VTEC 质量指示（TECU），仅 IM201/IM202 有 */
    public double vtecQualityIndicator = Double.NaN;
    /** 电离层球谐层模型，仅 IM201 有（按消息内顺序）；其它子类型为空表 */
    public final List<IgsSsrVtecLayer> ionosphericLayers = new ArrayList<>();

    /**
     * 子类型的可读名称。
     * @return 名称；未知子类型返回 "unknown"
     */
    public String subtypeName() {
        if (subtype == 201) return "IM201 电离层 VTEC 球谐";
        if (subtype == 202) return "IM202 电离层 VTEC-RMS";

        int index = (subtype - 21) % 20;
        int system = (subtype - 21) / 20;
        if (subtype < 21 || subtype > 127 || index > 6) return "unknown";
        String[] systems = {"GPS", "GLONASS", "Galileo", "QZSS", "BDS", "SBAS"};
        String[] contents = {
                "轨道改正", "钟差改正", "轨道+钟差", "高频钟差", "码偏差", "相位偏差", "URA"
        };
        return systems[system] + " " + contents[index];
    }

    @Override
    public String toString() {
        return "IGS SSR 4076 " + subtypeName()
                + " [version=" + version + ", subtype=" + subtype
                + ", epoch=" + epochTimeSec + ", updateInterval=" + updateIntervalSec
                + ", provider=" + providerId + ", solution=" + solutionId
                + ", nsat=" + numberOfSatellites
                + (ionosphericLayers.isEmpty() ? "" : ", layers=" + ionosphericLayers)
                + "]";
    }
}
