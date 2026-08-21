package org.rtklib.java.data;

import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;

/**
 * 卫星观测数据输出类，用于 monitor/trace/日志 输出。
 *
 * <p>从内部 {@link Ssat}（C 对齐结构）+ {@link Obsd} 提取单颗卫星的
 * 观测摘要信息，供外部系统（监控、日志、trace）使用。</p>
 *
 * <h3>字段说明</h3>
 * <ul>
 *   <li>{@code satPrn}：卫星编号字符串，如 "G01"、"C12"、"R07"、"E03"</li>
 *   <li>{@code satSys}：卫星系统单字符，如 'G'(GPS)、'R'(GLONASS)、'C'(BDS)、'E'(Galileo)</li>
 *   <li>{@code az}：方位角（度），0~360</li>
 *   <li>{@code el}：高度角（度），0~90</li>
 *   <li>{@code cn0}：载噪比 C/N0 (dB-Hz)，流动站第一频率的 SNR 值</li>
 *   <li>{@code cn0Base}：基准站载噪比 C/N0 (dB-Hz)</li>
 *   <li>{@code used}：该卫星是否被用于定位解算</li>
 *   <li>{@code fixState}：模糊度固定状态（0=未固定, 1=浮点, 2=固定）</li>
 * </ul>
 */
public class SatObsData {

    public final String satPrn;

    public final char satSys;

    public final float az;

    public final float el;

    public final float cn0;

    public final float cn0Base;

    public final boolean used;

    public final int fixState;

    public SatObsData(String satPrn, char satSys, float az, float el,
                      float cn0, float cn0Base, boolean used, int fixState) {
        this.satPrn = satPrn;
        this.satSys = satSys;
        this.az = az;
        this.el = el;
        this.cn0 = cn0;
        this.cn0Base = cn0Base;
        this.used = used;
        this.fixState = fixState;
    }

    /**
     * 从 Ssat 数组构建所有卫星的观测数据列表。
     *
     * @param ssat  卫星状态数组（长度 MAXSAT）
     * @param nf    频率数（用于取 SNR 的频率索引）
     * @return 卫星观测数据列表（仅包含高度角 > 0 的卫星）
     */
    public static java.util.List<SatObsData> fromSsat(Ssat[] ssat, int nf) {
        java.util.List<SatObsData> list = new java.util.ArrayList<>();
        if (ssat == null) return list;

        for (int i = 0; i < ssat.length; i++) {
            Ssat s = ssat[i];
            if (s.azel[1] <= 0.0) continue;

            int sat = i + 1;
            String prn = SatUtils.satno2id(sat);
            if (prn.isEmpty()) continue;

            char sysChar;
            int sys = SatUtils.satsys(sat, null);
            switch (sys) {
                case Constants.SYS_GPS: sysChar = 'G'; break;
                case Constants.SYS_GLO: sysChar = 'R'; break;
                case Constants.SYS_GAL: sysChar = 'E'; break;
                case Constants.SYS_QZS: sysChar = 'J'; break;
                case Constants.SYS_CMP: sysChar = 'C'; break;
                case Constants.SYS_IRN: sysChar = 'I'; break;
                case Constants.SYS_SBS: sysChar = 'S'; break;
                default: sysChar = '?'; break;
            }

            float az = (float) (s.azel[0] * Constants.R2D);
            float el = (float) (s.azel[1] * Constants.R2D);

            int freqIdx = Math.min(nf - 1, Constants.NFREQ - 1);
            if (freqIdx < 0) freqIdx = 0;
            float cn0 = (s.snrRover != null && freqIdx < s.snrRover.length) ? s.snrRover[freqIdx] : 0.0f;
            float cn0Base = (s.snrBase != null && freqIdx < s.snrBase.length) ? s.snrBase[freqIdx] : 0.0f;

            boolean used = s.vs != 0;
            int fixState = (s.fix != null && freqIdx < s.fix.length) ? s.fix[freqIdx] : 0;

            list.add(new SatObsData(prn, sysChar, az, el, cn0, cn0Base, used, fixState));
        }
        return list;
    }

    @Override
    public String toString() {
        return String.format("%s %c  az=%6.1f el=%5.1f  C/N0=%5.1f dBHz  used=%s  fix=%d",
                satPrn, satSys, az, el, cn0, used, fixState);
    }
}