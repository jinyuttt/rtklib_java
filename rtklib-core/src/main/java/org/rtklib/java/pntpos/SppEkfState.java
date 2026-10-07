package org.rtklib.java.pntpos;

import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.GTime;
import org.rtklib.java.data.PrcOpt;

import java.io.Serializable;

public class SppEkfState implements Serializable {
    private static final long serialVersionUID = 1L;

    public int nxF;
    public double[] x;
    public double[] P;
    public GTime prevTime;
    public double[] prevPos;
    public int consecutiveZeroVelEpochs;
    public boolean initialized;

    public SppEkfState(int nxF) {
        this.nxF = nxF;
        this.x = new double[nxF];
        this.P = new double[nxF * nxF];
        this.prevTime = null;
        this.prevPos = new double[3];
        this.consecutiveZeroVelEpochs = 0;
        this.initialized = false;
    }

    public void init(double[] pos, double[] vel, RtkConfig cfg) {
        for (int i = 0; i < nxF; i++) {
            x[i] = 0.0;
            for (int j = 0; j < nxF; j++) {
                P[i * nxF + j] = 0.0;
            }
        }
        for (int i = 0; i < 3 && i < pos.length; i++) {
            x[i] = pos[i];
            P[i * nxF + i] = cfg.sppEkfVarPos;
        }
        for (int i = 0; i < 3 && i < vel.length; i++) {
            x[i + 3] = vel[i];
            P[(i + 3) * nxF + (i + 3)] = cfg.sppEkfVarVel;
        }
        for (int i = 6; i < 9; i++) {
            x[i] = 1e-6;
            P[i * nxF + i] = cfg.sppEkfVarAcc;
        }
        for (int i = 9; i < nxF; i++) {
            x[i] = 0.001;
            P[i * nxF + i] = 1000.0 * 1000.0;
        }
        initialized = true;
    }

    public void reset() {
        for (int i = 0; i < nxF; i++) {
            x[i] = 0.0;
            for (int j = 0; j < nxF; j++) {
                P[i * nxF + j] = 0.0;
            }
        }
        prevTime = null;
        prevPos = new double[3];
        consecutiveZeroVelEpochs = 0;
        initialized = false;
    }

    public static int computeNxF(PrcOpt opt) {
        int nClock = SppCore.nx(opt) - 3;
        return 9 + nClock + 1;
    }
}