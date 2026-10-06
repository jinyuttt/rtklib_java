package org.rtklib.java.research.data;

import java.io.Serializable;
import org.rtklib.java.research.common.GTime;

/**
 * UWB测距观测数据（移植自FE-GUT types.h UWB结构体）。
 *
 * <p>记录一个历元中多个UWB锚点的距离观测值。</p>
 */
public class UwbObservation implements Serializable {
    private static final long serialVersionUID = 1L;

    public GTime time;
    public double[] ranges;
    public double[][] anchorPositions;

    public UwbObservation() {
        this.time = new GTime();
        this.ranges = new double[0];
        this.anchorPositions = new double[0][3];
    }

    public UwbObservation(GTime time, double[] ranges, double[][] anchorPositions) {
        this.time = new GTime(time);
        this.ranges = ranges.clone();
        this.anchorPositions = new double[anchorPositions.length][3];
        for (int i = 0; i < anchorPositions.length; i++) {
            System.arraycopy(anchorPositions[i], 0, this.anchorPositions[i], 0, 3);
        }
    }

    public int numAnchors() {
        return ranges.length;
    }

    @Override
    public String toString() {
        return String.format("UWB[%s, nAnchor=%d]", time, ranges.length);
    }
}