package org.rtklib.java.rtcm;

public class AuxData {

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