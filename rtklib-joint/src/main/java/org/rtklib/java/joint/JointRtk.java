package org.rtklib.java.joint;

import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.PrcOpt;
import org.rtklib.java.data.RtkState;
import org.rtklib.java.data.Ssat;
import org.rtklib.java.data.Sol;
import org.rtklib.java.rtkpos.RtkCore;

import java.io.Serializable;

public class JointRtk implements Serializable {
    private static final long serialVersionUID = 1L;

    public Sol sol;
    public double[] rb;
    public double[] x;
    public double[] P;
    public double[] xa;
    public double[] Pa;
    public int nx;
    public int na;

    public int nBaseline;
    public BaseStation[] bases;
    public int[] baselineOffset;
    public int[] baselineAmbCount;

    public Ssat[] ssat;

    public PrcOpt opt;
    public JointConfig jointConfig;

    public int nfix;
    public int epoch;

    public JointRtk() {
        sol = new Sol();
        rb = new double[3];
        ssat = new Ssat[Constants.MAXSAT];
        for (int i = 0; i < Constants.MAXSAT; i++) {
            ssat[i] = new Ssat();
        }
    }

    public RtkState toState() {
        RtkConfig rtkConfig = (jointConfig != null) ? new RtkConfig() : new RtkConfig();
        double[] snrMedian = new double[2];
        double[] safeX = (x != null) ? x : new double[0];
        return new RtkState(opt, ssat, rtkConfig, snrMedian, sol, safeX, 0.0);
    }

    public int NP() {
        return RtkCore.NP(toState());
    }

    public int NI() {
        return RtkCore.NI(toState());
    }

    public int NT() {
        return RtkCore.NT(toState());
    }

    public int NB() {
        return RtkCore.NB(toState());
    }

    public int NR() {
        return RtkCore.NR(toState());
    }

    public int NL() {
        return RtkCore.NL(toState());
    }

    public int II(int baseline, int sat) {
        if (opt == null || baseline < 0 || baseline >= nBaseline) return -1;
        int np = NP();
        int niPerSat = (opt.ionoGradient) ? 3 : 1;
        int niPerBaseline = Constants.MAXSAT * niPerSat;
        return np + baseline * niPerBaseline + (sat - 1) * niPerSat;
    }

    public int IT(int baseline, int r) {
        if (opt == null || baseline < 0 || baseline >= nBaseline) return -1;
        int np = NP();
        int ni = NI() > 0 ? nBaseline * NI() : 0;
        int ntPerR = (opt.tropopt >= Constants.TROPOPT_ESTG) ? 3 : 1;
        int ntPerBaseline = 2 * ntPerR;
        return np + ni + baseline * ntPerBaseline + r * ntPerR;
    }

    public int IB(int baseline, int sat, int f) {
        if (baselineOffset == null || baseline < 0 || baseline >= baselineOffset.length) return -1;
        return baselineOffset[baseline] + Constants.MAXSAT * f + (sat - 1);
    }

    public int baselineDim(int i) {
        if (baselineOffset == null || i >= nBaseline) return 0;
        if (i == nBaseline - 1) return nx - baselineOffset[i];
        return baselineOffset[i + 1] - baselineOffset[i];
    }
}