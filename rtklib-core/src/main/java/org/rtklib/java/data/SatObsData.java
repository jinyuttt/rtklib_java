package org.rtklib.java.data;

import java.io.Serializable;
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
 *
 * <h3>[Java扩展] 诊断字段（diagMask控制）</h3>
 * <ul>
 *   <li>{@code resp}：伪距残差(m)，DIAG_SAT_RESIDUAL时有效</li>
 *   <li>{@code resc}：载波残差(m)，DIAG_SAT_RESIDUAL时有效</li>
 *   <li>{@code slip}：周跳标志，DIAG_SAT_CYCLESLIP时有效</li>
 *   <li>{@code gf}：GF组合值(m)，DIAG_SAT_CYCLESLIP时有效</li>
 *   <li>{@code mw}：MW组合值(周)，DIAG_SAT_CYCLESLIP时有效</li>
 *   <li>{@code rejc}：拒绝计数，DIAG_SAT_CYCLESLIP时有效</li>
 *   <li>{@code amb}：模糊度浮点值(周)，DIAG_SAT_AMBIGUITY时有效</li>
 *   <li>{@code stdA}：模糊度标准差(周)，DIAG_SAT_AMBIGUITY时有效</li>
 * </ul>
 * <p>未启用时对应字段为NaN或默认值，零开销向后兼容。</p>
 */
public class SatObsData implements Serializable {
    private static final long serialVersionUID = 1L;

    public final String satPrn;

    public final char satSys;

    public final float az;

    public final float el;

    public final float cn0;

    public final float cn0Base;

    public final boolean used;

    public final int fixState;

    /** [Java扩展] 伪距残差(m)，diagMask & DIAG_SAT_RESIDUAL 时有效，否则NaN */
    public final double resp;

    /** [Java扩展] 载波残差(m)，diagMask & DIAG_SAT_RESIDUAL 时有效，否则NaN */
    public final double resc;

    /** [Java扩展] 周跳标志(0=无周跳)，diagMask & DIAG_SAT_CYCLESLIP 时有效，否则0 */
    public final int slip;

    /** [Java扩展] GF组合值(m)，diagMask & DIAG_SAT_CYCLESLIP 时有效，否则NaN */
    public final double gf;

    /** [Java扩展] MW组合值(周)，diagMask & DIAG_SAT_CYCLESLIP 时有效，否则NaN */
    public final double mw;

    /** [Java扩展] 拒绝计数，diagMask & DIAG_SAT_CYCLESLIP 时有效，否则0 */
    public final long rejc;

    /** [Java扩展] 模糊度浮点值(周)，diagMask & DIAG_SAT_AMBIGUITY 时有效，否则NaN */
    public final double amb;

    /** [Java扩展] 模糊度标准差(周)，diagMask & DIAG_SAT_AMBIGUITY 时有效，否则NaN */
    public final double stdA;

    public SatObsData(String satPrn, char satSys, float az, float el,
                      float cn0, float cn0Base, boolean used, int fixState) {
        this(satPrn, satSys, az, el, cn0, cn0Base, used, fixState,
                Double.NaN, Double.NaN, 0, Double.NaN, Double.NaN, 0L, Double.NaN, Double.NaN);
    }

    public SatObsData(String satPrn, char satSys, float az, float el,
                      float cn0, float cn0Base, boolean used, int fixState,
                      double resp, double resc, int slip, double gf, double mw,
                      long rejc, double amb, double stdA) {
        this.satPrn = satPrn;
        this.satSys = satSys;
        this.az = az;
        this.el = el;
        this.cn0 = cn0;
        this.cn0Base = cn0Base;
        this.used = used;
        this.fixState = fixState;
        this.resp = resp;
        this.resc = resc;
        this.slip = slip;
        this.gf = gf;
        this.mw = mw;
        this.rejc = rejc;
        this.amb = amb;
        this.stdA = stdA;
    }

    /**
     * 从 Ssat 数组构建所有卫星的观测数据列表。
     *
     * @param ssat  卫星状态数组（长度 MAXSAT）
     * @param nf    频率数（用于取 SNR 的频率索引）
     * @return 卫星观测数据列表（仅包含高度角 > 0 的卫星）
     */
    public static java.util.List<SatObsData> fromSsat(Ssat[] ssat, int nf) {
        return fromSsat(ssat, nf, 0);
    }

    /**
     * [Java扩展] 从 Ssat 数组构建所有卫星的观测数据列表，按diagMask提取诊断字段。
     *
     * @param ssat     卫星状态数组（长度 MAXSAT）
     * @param nf       频率数（用于取 SNR 的频率索引）
     * @param diagMask 诊断掩码（位或PrcOpt.DIAG_???），0=仅基础字段
     * @return 卫星观测数据列表（仅包含高度角 > 0 的卫星）
     */
    public static java.util.List<SatObsData> fromSsat(Ssat[] ssat, int nf, int diagMask) {
        java.util.List<SatObsData> list = new java.util.ArrayList<>();
        if (ssat == null) return list;

        boolean extractResidual  = (diagMask & PrcOpt.DIAG_SAT_RESIDUAL)  != 0;
        boolean extractAmbiguity = (diagMask & PrcOpt.DIAG_SAT_AMBIGUITY) != 0;
        boolean extractCycleslip = (diagMask & PrcOpt.DIAG_SAT_CYCLESLIP) != 0;

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

            double respVal  = extractResidual  && s.resp != null && freqIdx < s.resp.length  ? s.resp[freqIdx]  : Double.NaN;
            double rescVal  = extractResidual  && s.resc != null && freqIdx < s.resc.length  ? s.resc[freqIdx]  : Double.NaN;
            int slipVal     = extractCycleslip && s.slip != null && freqIdx < s.slip.length  ? s.slip[freqIdx]  : 0;
            double gfVal    = extractCycleslip && s.gf   != null && freqIdx < s.gf.length    ? s.gf[freqIdx]    : Double.NaN;
            double mwVal    = extractCycleslip && s.mw   != null && freqIdx < s.mw.length    ? s.mw[freqIdx]    : Double.NaN;
            long rejcVal    = extractCycleslip && s.rejc != null && freqIdx < s.rejc.length  ? s.rejc[freqIdx]  : 0L;
            double ambVal   = extractAmbiguity && s.amb  != null && freqIdx < s.amb.length   ? s.amb[freqIdx]   : Double.NaN;
            double stdAVal  = extractAmbiguity && s.stdA != null && freqIdx < s.stdA.length  ? s.stdA[freqIdx]  : Double.NaN;

            list.add(new SatObsData(prn, sysChar, az, el, cn0, cn0Base, used, fixState,
                    respVal, rescVal, slipVal, gfVal, mwVal, rejcVal, ambVal, stdAVal));
        }
        return list;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%s %c  az=%6.1f el=%5.1f  C/N0=%5.1f dBHz  used=%s  fix=%d",
                satPrn, satSys, az, el, cn0, used, fixState));
        if (!Double.isNaN(resc))  sb.append(String.format("  resc=%.4f", resc));
        if (!Double.isNaN(resp))  sb.append(String.format("  resp=%.4f", resp));
        if (slip != 0)            sb.append("  SLIP");
        if (!Double.isNaN(amb))   sb.append(String.format("  amb=%.3f", amb));
        return sb.toString();
    }
}