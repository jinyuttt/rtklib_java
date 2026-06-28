package org.rtklib.java.trace;

public class TraceControl {
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
}
