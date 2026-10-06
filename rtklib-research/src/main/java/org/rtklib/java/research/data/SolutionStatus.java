package org.rtklib.java.research.data;

import java.io.Serializable;

/**
 * 定位解状态枚举（research模块自有）。
 */
public enum SolutionStatus implements Serializable {
    NONE(0, "None"),
    FIX(1, "Fix"),
    FLOAT(2, "Float"),
    SBAS(3, "SBAS"),
    DGPS(4, "DGPS"),
    SINGLE(5, "Single"),
    PPP(6, "PPP"),
    DR(7, "DR");

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