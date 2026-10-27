package org.rtklib.java.joint;

import org.rtklib.java.data.GTime;

import java.io.Serializable;

public class BaselineSolution implements Serializable {
    private static final long serialVersionUID = 1L;

    public final String baseId;
    public final GTime time;
    public final double[] position;
    public final double[][] covariance;
    public final int status;
    public final int ns;
    public final float ratio;

    public BaselineSolution(String baseId, GTime time, double[] position,
                            double[][] covariance, int status, int ns, float ratio) {
        this.baseId = baseId;
        this.time = time;
        this.position = position.clone();
        this.covariance = covariance.clone();
        this.status = status;
        this.ns = ns;
        this.ratio = ratio;
    }

    public boolean isFix() {
        return status == 1;
    }

    public boolean isValid() {
        return status >= 1 && status <= 2;
    }

    @Override
    public String toString() {
        return String.format("BaselineSolution[%s, stat=%d, ns=%d, ratio=%.1f, pos=(%.4f,%.4f,%.4f)]",
                baseId, status, ns, ratio, position[0], position[1], position[2]);
    }
}