package org.rtklib.java.cssr;

public enum CssrMessageType {
    MASK(1),
    ORBIT(2),
    CLOCK(3),
    CBIAS(4),
    PBIAS(5),
    BIAS(6),
    URA(7),
    STEC(8),
    GRID(9),
    SI(10),
    COMBINED(11),
    ATMOS(12),
    AUTH(13),
    VTEC(16),
    HCLOCK(19);

    public final int value;

    CssrMessageType(int value) {
        this.value = value;
    }

    public static CssrMessageType fromValue(int v) {
        for (CssrMessageType t : values()) {
            if (t.value == v) return t;
        }
        return null;
    }
}