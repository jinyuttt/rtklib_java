package org.rtklib.java.trace;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Set;

public class TraceConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    public boolean enabled = false;
    public Set<String> topics = new HashSet<>();
    public Set<String> actions = null;
    public int samplerate = 1;
    public int maxEpoch = 0;
    public int maxOutputLines = 0;
    public int[] targetSats = new int[0];

    public double[] refEcef = null;
    public double convergenceThresh = 0.1;
    public int convergenceEpochs = 10;
    public double arShiftWarnThresh = 5.0;

    public final TraceStats stats = new TraceStats();
}