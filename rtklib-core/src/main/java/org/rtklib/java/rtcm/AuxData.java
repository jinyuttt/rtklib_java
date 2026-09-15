package org.rtklib.java.rtcm;

import java.io.Serializable;

public class AuxData implements Serializable {
    private static final long serialVersionUID = 1L;

    public final int msgType;
    public final String antdes;
    public final String antsno;
    public final String rectype;

    public AuxData(int msgType, String antdes, String antsno, String rectype) {
        this.msgType = msgType;
        this.antdes = antdes;
        this.antsno = antsno;
        this.rectype = rectype;
    }
}