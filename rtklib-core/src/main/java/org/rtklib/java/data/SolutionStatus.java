package org.rtklib.java.data;

import org.rtklib.java.constants.Constants;

/**
 * 定位解状态枚举。
 *
 * <p>对应 RTKLIB SOLQ_??? 常量，用于 {@link SolData#status} 字段。</p>
 *
 * <ul>
 *   <li>{@code NONE}   (0)：无解</li>
 *   <li>{@code FIX}    (1)：固定解（整周模糊度已固定）</li>
 *   <li>{@code FLOAT}  (2)：浮点解（模糊度未固定）</li>
 *   <li>{@code SBAS}   (3)：SBAS 差分解</li>
 *   <li>{@code DGPS}   (4)：DGPS 差分解</li>
 *   <li>{@code SINGLE} (5)：单点定位解</li>
 *   <li>{@code PPP}    (6)：精密单点定位解</li>
 *   <li>{@code DR}     (7)：推算解（Dead Reckoning）</li>
 * </ul>
 */
public enum SolutionStatus {
    NONE(Constants.SOLQ_NONE, "None"),
    FIX(Constants.SOLQ_FIX, "Fix"),
    FLOAT(Constants.SOLQ_FLOAT, "Float"),
    SBAS(Constants.SOLQ_SBAS, "SBAS"),
    DGPS(Constants.SOLQ_DGPS, "DGPS"),
    SINGLE(Constants.SOLQ_SINGLE, "Single"),
    PPP(Constants.SOLQ_PPP, "PPP"),
    DR(Constants.SOLQ_DR, "DR");

    public final int code;
    public final String label;

    SolutionStatus(int code, String label) {
        this.code = code;
        this.label = label;
    }

    public static SolutionStatus fromCode(int code) {
        for (SolutionStatus s : values()) {
            if (s.code == code) return s;
        }
        return NONE;
    }

    @Override
    public String toString() {
        return label;
    }
}