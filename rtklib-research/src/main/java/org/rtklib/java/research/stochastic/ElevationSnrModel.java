package org.rtklib.java.research.stochastic;

import org.ejml.simple.SimpleMatrix;
import org.rtklib.java.research.common.GnssConst;
import org.rtklib.java.research.common.SatId;

/**
 * 高度角/C-N0加权随机模型（research模块基线模型）。
 *
 * <p>观测噪声（对角）：
 *   σ² = (sigmaCode · efact / sin(el))² · scaleFactor
 *
 * <p>过程噪声：
 *   12 维状态 → 分段白噪声（position/velocity/acceleration/clock/uwb）
 *   其他维度 → 恒等缩放
 */
public class ElevationSnrModel implements StochasticModel {
    private final double sigmaCode;
    private final double sigmaPhase;
    private final double sigmaDoppler;

    public ElevationSnrModel() {
        this(0.3, 0.003, 0.1);
    }

    public ElevationSnrModel(double sigmaCode, double sigmaPhase, double sigmaDoppler) {
        this.sigmaCode = sigmaCode;
        this.sigmaPhase = sigmaPhase;
        this.sigmaDoppler = sigmaDoppler;
    }

    @Override
    public String name() {
        return "ElevationSNR";
    }

    @Override
    public SimpleMatrix observationCovariance(double[] elevations, int[] sys, double scaleFactor) {
        int n = elevations.length;
        SimpleMatrix R = new SimpleMatrix(n, n);
        for (int i = 0; i < n; i++) {
            double sinel = Math.sin(Math.max(elevations[i], 5.0 * GnssConst.D2R));
            double efact = (sys != null && i < sys.length) ? SatId.systemErrorFactor(sys[i]) : 1.0;
            double sigma = sigmaCode * efact / sinel;
            R.set(i, i, sigma * sigma * scaleFactor);
        }
        return R;
    }

    @Override
    public SimpleMatrix processNoiseCovariance(int dim, double dt) {
        double d2 = dt * dt;
        double d3 = dt * d2;
        double qa = 0.25 * 0.25;

        SimpleMatrix Q = new SimpleMatrix(dim, dim);

        if (dim >= 12) {
            Q.set(0, 0, qa * d3 / 3.0);
            Q.set(0, 3, qa * d2 / 2.0);
            Q.set(1, 1, qa * d3 / 3.0);
            Q.set(1, 4, qa * d2 / 2.0);
            Q.set(2, 2, qa * d3 / 3.0);
            Q.set(2, 5, qa * d2 / 2.0);
            Q.set(3, 0, qa * d2 / 2.0);
            Q.set(3, 3, qa * dt);
            Q.set(4, 1, qa * d2 / 2.0);
            Q.set(4, 4, qa * dt);
            Q.set(5, 2, qa * d2 / 2.0);
            Q.set(5, 5, qa * dt);

            double sigmaAcc = 0.25;
            Q.set(6, 6, sigmaAcc * sigmaAcc * dt);
            Q.set(7, 7, sigmaAcc * sigmaAcc * dt);
            Q.set(8, 8, sigmaAcc * sigmaAcc * dt);

            Q.set(9, 9, 3.0 * 3.0 * dt);
            Q.set(10, 10, 0.1 * 0.1 * dt);
            Q.set(11, 11, 0.01 * 0.01 * dt);
        } else if (dim == 11) {
            Q.set(0, 0, qa * d3 / 3.0);
            Q.set(0, 3, qa * d2 / 2.0);
            Q.set(1, 1, qa * d3 / 3.0);
            Q.set(1, 4, qa * d2 / 2.0);
            Q.set(2, 2, qa * d3 / 3.0);
            Q.set(2, 5, qa * d2 / 2.0);
            Q.set(3, 0, qa * d2 / 2.0);
            Q.set(3, 3, qa * dt);
            Q.set(4, 1, qa * d2 / 2.0);
            Q.set(4, 4, qa * dt);
            Q.set(5, 2, qa * d2 / 2.0);
            Q.set(5, 5, qa * dt);
            Q.set(6, 6, 0.25 * 0.25 * dt);
            Q.set(7, 7, 0.25 * 0.25 * dt);
            Q.set(8, 8, 0.25 * 0.25 * dt);
            Q.set(9, 9, 3.0 * 3.0 * dt);
            Q.set(10, 10, 0.1 * 0.1 * dt);
        } else {
            double sigmaAmb = 60.0;
            for (int i = 0; i < dim; i++) {
                Q.set(i, i, sigmaAmb * sigmaAmb * dt);
            }
        }

        return Q;
    }
}