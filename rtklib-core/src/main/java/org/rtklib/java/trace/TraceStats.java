package org.rtklib.java.trace;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

public class TraceStats implements Serializable {
    private static final long serialVersionUID = 1L;

    public int totalEpochs;
    public int successEpochs;
    public int failEpochs;
    public Map<String, Integer> failReasons = new LinkedHashMap<>();
    public int convEpoch;
    public int consecutiveConvEpochs;
    public int fixCount;
    public int floatCount;
    public double maxArShift;
    public int outputLineCount;
}