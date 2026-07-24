package org.rtklib.java.kalman;

import org.ejml.simple.SimpleMatrix;
import org.rtklib.java.common.MatrixUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kalman filter measurement update.
 *
 * <p>Corresponds to RTKLIB filter() in rtkcmn.c.
 * Uses Joseph form for numerical stability, as EJML matrix operation order differs
 * from C's custom matmul(), causing P to lose positive-definiteness under ill-conditioned H.</p>
 *
 * <pre>
 *   K = P * H' * inv(H * P * H' + R)
 *   x = x + K * v
 *   P = (I - K*H) * P * (I - K*H)^T + K * R * K^T   (Joseph form)
 * </pre>
 */
public final class KalmanFilter {
    private static final Logger log = LoggerFactory.getLogger(KalmanFilter.class);
    private static final boolean TRACE = true; // 跟踪日志开关
    
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

            // Joseph form: P_new = (I-KH)*P*(I-KH)^T + K*R*K^T
            SimpleMatrix KH = MatrixUtil.multiply(K, HMat);
            SimpleMatrix I = SimpleMatrix.identity(nx);
            SimpleMatrix IKH = MatrixUtil.subtract(I, KH);
            SimpleMatrix IKH_T = MatrixUtil.transpose(IKH);
            SimpleMatrix P_temp = MatrixUtil.multiply(IKH, PMat);
            SimpleMatrix P_IKH = MatrixUtil.multiply(P_temp, IKH_T);

            SimpleMatrix Kt = MatrixUtil.transpose(K);
            SimpleMatrix KR = MatrixUtil.multiply(K, RMat);
            SimpleMatrix KRKt = MatrixUtil.multiply(KR, Kt);

            SimpleMatrix PNew = MatrixUtil.add(P_IKH, KRKt);

            MatrixUtil.copyMatrix(XNew, x);
            MatrixUtil.copyMatrix(PNew, P);

            return 0;
        } catch (Exception e) {
            if (TRACE) {
                log.error("Kalman update failed: nx={}, nv={}, error={}", nx, nv, e.getMessage());
            }
            return -1;
        }
    }
}