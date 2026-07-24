package org.rtklib.java.data;

import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.trace.TraceControl;
import org.rtklib.java.trace.TraceCallback;
import java.util.Arrays;

public class Rtk {
    public Sol sol;

    public double[] rb;

    public int nx;

    public int na;

    public double[] x;

    public double[] P;

    public double[] xa;

    public double[] Pa;

    public int nfix;

    public double tt;

    public int nband;

    public int[] nepoch;

    public int epoch;

    public int intpres_nb;

    public Obsd[] intpres_obsb;

    public Ssat[] ssat;

    public PrcOpt opt;

    public TraceControl traceControl;

    public TraceCallback traceCallback;

    public Ambc ambc;

    public int nb_ar;

    public int excsat;

    public int holdambFlag;

    public RtkConfig rtkConfig;

    public double qScale;

    public double[] snrMedian;

    public double[][] snrMedianHistory;

    public int snrMedianHistoryCount;

    public int consecutiveZeroVelEpochs;

    public double[] prevPosForZeroVel;

    public int parConsecutiveReselectCount;

    public int[] parExcludedSats;

    public int parExcludedSatCount;

    public int[] parPrevRefSat;

    public double[] xOld;

    public double[] posWin;

    public int winIdx;

    public int winCnt;

    public boolean[] ambAnchored;

    public int[] ambAnchorCount;

    public Rtk() {
        this.sol = new Sol();
        this.rb = new double[6];
        this.nx = 0;
        this.na = 0;
        this.x = new double[Constants.NX_RTK];
        this.P = new double[Constants.NX_RTK * Constants.NX_RTK];
        // 初始化P矩阵对角线为大值（与C版rtkinit()一致）
        double initialVar = 1e10;
        for (int i = 0; i < Constants.NX_RTK; i++) {
            this.P[i * Constants.NX_RTK + i] = initialVar;
        }
        this.xa = new double[Constants.NX_RTK];
        this.Pa = new double[Constants.NX_RTK * Constants.NX_RTK];
        this.nfix = 0;
        this.nband = 0;
        this.nepoch = new int[2];
        this.epoch = 0;
        this.intpres_nb = 0;
        this.intpres_obsb = new Obsd[Constants.MAXOBS];
        this.ssat = new Ssat[Constants.MAXSAT];
        for (int i = 0; i < Constants.MAXSAT; i++) {
            this.ssat[i] = new Ssat();
        }
        this.opt = new PrcOpt();
        this.ambc = new Ambc();
        this.nb_ar = 0;
        this.excsat = 0;
        this.holdambFlag = 0;
        this.traceControl = null;
        this.traceCallback = null;
        this.rtkConfig = new RtkConfig();
        this.qScale = 1.0;
        this.snrMedian = new double[Constants.NFREQ];
        this.snrMedianHistory = new double[Constants.NFREQ][20];
        this.snrMedianHistoryCount = 0;
        this.consecutiveZeroVelEpochs = 0;
        this.prevPosForZeroVel = new double[3];
        this.parConsecutiveReselectCount = 0;
        this.parExcludedSats = new int[Constants.MAXSAT];
        this.parExcludedSatCount = 0;
        this.parPrevRefSat = new int[Constants.NFREQ];
        for (int i = 0; i < Constants.NFREQ; i++) this.parPrevRefSat[i] = -1;
        this.xOld = new double[3];
        this.posWin = new double[100];
        this.winIdx = 0;
        this.winCnt = 0;
        this.ambAnchored = new boolean[Constants.MAXSAT * Constants.NFREQ];
        this.ambAnchorCount = new int[Constants.MAXSAT * Constants.NFREQ];
    }
}