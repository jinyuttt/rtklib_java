package org.rtklib.java.kalman;

import org.ejml.simple.SimpleMatrix;
import org.rtklib.java.common.MatrixUtil;

/**
 * Kalman filter measurement update.
 *
 * <p>Corresponds to RTKLIB filter() in rtkcmn.c.
 * Implements standard EKF measurement update with row-major storage.</p>
 *
 * <pre>
 *   K = P * H' * inv(H * P * H' + R)
 *   x = x + K * v
 *   P = (I - K * H) * P
 * </pre>
 */
public final class KalmanFilter {
    private KalmanFilter() {
    }

    /**
     * Kalman filter measurement update.
     *
     * @param x  state vector [nx], updated in place
     * @param P  covariance matrix [nx*nx] row-major, updated in place
     * @param H  design matrix [nv*nx] row-major (nv observations, nx states)
     * @param v  innovation vector [nv]
     * @param R  measurement noise covariance [nv*nv] row-major
     * @param nx state dimension
     * @param nv measurement dimension
     * @return 0 on success, -1 on failure (singular matrix)
     */
    public static int update(double[] x, double[] P, double[] H, double[] v,
                             double[] R, int nx, int nv) {
        if (nv <= 0 || nx <= 0) return 0;

        try {
            SimpleMatrix HMat = MatrixUtil.createMatrix(H, nv, nx);
            SimpleMatrix PMat = MatrixUtil.createMatrix(P, nx, nx);
            SimpleMatrix RMat = MatrixUtil.createMatrix(R, nv, nv);
            SimpleMatrix X = MatrixUtil.createMatrix(x, nx, 1);

            SimpleMatrix Ht = MatrixUtil.transpose(HMat);

            SimpleMatrix HP = MatrixUtil.multiply(HMat, PMat);
            SimpleMatrix HPHt = MatrixUtil.multiply(HP, Ht);
            SimpleMatrix S = MatrixUtil.add(HPHt, RMat);

            SimpleMatrix SInv = MatrixUtil.invert(S);

            SimpleMatrix PHt = MatrixUtil.multiply(PMat, Ht);
            SimpleMatrix K = MatrixUtil.multiply(PHt, SInv);

            SimpleMatrix V = MatrixUtil.createMatrix(v, nv, 1);
            SimpleMatrix KV = MatrixUtil.multiply(K, V);
            SimpleMatrix XNew = MatrixUtil.add(X, KV);

            SimpleMatrix KH = MatrixUtil.multiply(K, HMat);
            SimpleMatrix I = SimpleMatrix.identity(nx);
            SimpleMatrix IKH = MatrixUtil.subtract(I, KH);
            SimpleMatrix PNew = MatrixUtil.multiply(IKH, PMat);

            MatrixUtil.copyMatrix(XNew, x);
            MatrixUtil.copyMatrix(PNew, P);

            return 0;
        } catch (Exception e) {
            return -1;
        }
    }
}
