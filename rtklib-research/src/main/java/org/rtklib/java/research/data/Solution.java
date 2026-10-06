package org.rtklib.java.research.data;

import java.io.Serializable;
import org.rtklib.java.research.common.GTime;
import org.ejml.simple.SimpleMatrix;

/**
 * 定位解（research模块自有）。
 *
 * <p>包含位置、速度、协方差、状态等信息。</p>
 */
public class Solution implements Serializable {
    private static final long serialVersionUID = 1L;

    public GTime time;
    public double[] position;
    public double[] velocity;
    public SimpleMatrix positionCov;
    public SolutionStatus status;
    public int numSatellites;
    public double age;
    public double ratio;
    public double pdop;
    public double hdop;
    public double vdop;
    public double[] receiverClockBias;
    public double[] floatAmbiguities;
    public double[] fixedAmbiguities;
    public String backendName;
    public long computeTimeMs;

    public Solution() {
        this.time = new GTime();
        this.position = new double[3];
        this.velocity = new double[3];
        this.positionCov = new SimpleMatrix(3, 3);
        this.status = SolutionStatus.NONE;
        this.numSatellites = 0;
        this.age = 0.0;
        this.ratio = 0.0;
        this.pdop = 0.0;
        this.hdop = 0.0;
        this.vdop = 0.0;
        this.receiverClockBias = new double[1];
        this.backendName = "";
        this.computeTimeMs = 0;
    }

    public Solution(GTime time, double[] position, SolutionStatus status) {
        this();
        this.time = new GTime(time);
        System.arraycopy(position, 0, this.position, 0, Math.min(position.length, 3));
        this.status = status;
    }

    public double[] getPositionENU(double[] referencePosition) {
        double[] dr = new double[3];
        for (int i = 0; i < 3; i++) dr[i] = this.position[i] - referencePosition[i];
        double[] llh = new double[3];
        org.rtklib.java.research.common.Coordinates.ecef2llh(referencePosition, llh);
        double[] enu = new double[3];
        org.rtklib.java.research.common.Coordinates.ecef2enu(llh, dr, enu);
        return enu;
    }

    public boolean isFixed() {
        return status == SolutionStatus.FIX;
    }

    public boolean isFloat() {
        return status == SolutionStatus.FLOAT;
    }

    public double positionRms() {
        if (positionCov == null) return Double.NaN;
        double var = (positionCov.get(0, 0) + positionCov.get(1, 1) + positionCov.get(2, 2)) / 3.0;
        return var > 0 ? Math.sqrt(var) : 0.0;
    }

    @Override
    public String toString() {
        return String.format("Sol[%s, %s, nSat=%d, ratio=%.2f, rms=%.4f m, backend=%s]",
                time, status, numSatellites, ratio, positionRms(), backendName);
    }
}