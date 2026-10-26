package org.rtklib.java.joint;

import org.rtklib.java.constants.Constants;

public class BaselineObs {
    public double[] y;
    public double[] e;
    public double[] azel;
    public double[] freq;
    public int[] sat;
    public int[] iu;
    public int[] ir;
    public int ns;
    public int nu;
    public int nr;
    public double[] rs;
    public double[] dts;
    public double[] vare;
    public int[] svh;

    public BaselineObs() {
        y = new double[Constants.NFREQ * 2 * Constants.MAXOBS];
        e = new double[3 * Constants.MAXOBS];
        azel = new double[Constants.MAXSAT * 2];
        freq = new double[Constants.NFREQ * Constants.MAXOBS];
        sat = new int[Constants.MAXSAT];
        iu = new int[Constants.MAXSAT];
        ir = new int[Constants.MAXSAT];
        rs = new double[Constants.MAXOBS * 6];
        dts = new double[Constants.MAXOBS * 2];
        vare = new double[Constants.MAXOBS];
        svh = new int[Constants.MAXOBS];
    }

    public void clear() {
        ns = 0;
        nu = 0;
        nr = 0;
        for (int i = 0; i < azel.length; i++) azel[i] = 0.0;
        for (int i = 0; i < freq.length; i++) freq[i] = 0.0;
        for (int i = 0; i < sat.length; i++) sat[i] = 0;
        for (int i = 0; i < iu.length; i++) iu[i] = 0;
        for (int i = 0; i < ir.length; i++) ir[i] = 0;
    }
}