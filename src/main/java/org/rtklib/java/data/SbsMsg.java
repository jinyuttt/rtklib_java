package org.rtklib.java.data;

public class SbsMsg {
    public int week;
    public int tow;
    public int prn;
    public byte[] msg;

    public SbsMsg() {
        this.week = 0;
        this.tow = 0;
        this.prn = 0;
        this.msg = new byte[29];
    }
}