package org.rtklib.java.trace;

import java.io.Serializable;

public class TraceControl implements Serializable {
    private static final long serialVersionUID = 1L;
    public static final int STAGE_INPUT   = 1 << 0;
    public static final int STAGE_SATPOS  = 1 << 1;
    public static final int STAGE_UDSTATE = 1 << 2;
    public static final int STAGE_DDRES   = 1 << 3;
    public static final int STAGE_FILTER  = 1 << 4;
    public static final int STAGE_LAMBDA  = 1 << 5;
    public static final int STAGE_RESULT  = 1 << 6;

    public static final int CONTENT_RESIDUAL_ONLY = 1 << 0;
    public static final int CONTENT_H_MATRIX      = 1 << 1;
    public static final int CONTENT_SUMMARY_ONLY  = 1 << 2;

    public boolean enabled = false;
    public int stages = 0;
    public int contentFlags = 0;
    public int maxEpochs = 0;
    public int samplerate = 1;
    public int[] targetSats = new int[0];

    private static final String[][] STAGE_TOPIC_MAP = {
        {"SATELLITE"},       // STAGE_INPUT
        {"EPHEMERIS"},       // STAGE_SATPOS
        {"BASELINE", "AMBIGUITY"}, // STAGE_UDSTATE
        {"FILTER"},          // STAGE_DDRES
        {"FILTER"},          // STAGE_FILTER
        {"AR"},              // STAGE_LAMBDA
        {"POSITION", "RESULT"} // STAGE_RESULT
    };

    public TraceConfig toTraceConfig() {
        TraceConfig cfg = new TraceConfig();
        cfg.enabled = this.enabled;
        cfg.maxEpoch = this.maxEpochs;
        cfg.samplerate = this.samplerate;
        cfg.targetSats = this.targetSats;
        if ((this.contentFlags & CONTENT_H_MATRIX) != 0) {
            if (cfg.actions == null) cfg.actions = new java.util.HashSet<>();
            cfg.actions.add("H_MATRIX");
        }
        if ((this.contentFlags & CONTENT_SUMMARY_ONLY) != 0) {
            if (cfg.actions == null) cfg.actions = new java.util.HashSet<>();
            cfg.actions.add("SUMMARY_ONLY");
        }
        for (int i = 0; i < STAGE_TOPIC_MAP.length; i++) {
            if ((this.stages & (1 << i)) != 0) {
                for (String topic : STAGE_TOPIC_MAP[i]) {
                    cfg.topics.add(topic);
                }
            }
        }
        return cfg;
    }
}