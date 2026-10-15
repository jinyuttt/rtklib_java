package org.rtklib.java.kalman;

import org.ejml.simple.SimpleMatrix;
import org.rtklib.java.common.MatrixUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kalman filter measurement update.
 *
 * <p>Corresponds to RTKLIB filter() in rtkcmn.c.
 * Implements state compression like C version: only non-zero states (x[i]!=0 && P[i+i*nx]>0)
 * participate in the Kalman update, significantly improving numerical stability and
 * matching C version behavior.</p>
 *
 * <p>Covariance update uses the Joseph form: P = (I-KH)*P*(I-KH)^T + K*R*K^T,
 * which structurally guarantees symmetry and positive semi-definiteness.
 * Mathematically equivalent to the standard form P = (I-KH)*P used in C version
 * (RTKLIB rtkcmn.c filter_), but with superior numerical stability under
 * finite-precision arithmetic (measured asymmetry ~1e-10 vs ~1e-9 for standard form
 * in RTK scenarios). Performance difference is negligible with EJML for k~18.</p>
 */
public final class KalmanFilter {

    private static final Logger LOG = LoggerFactory.getLogger(KalmanFilter.class);

    /**
     * Kalman filter measurement update with state compression.
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

        int[] ix = new int[nx];
        int k = 0;
        for (int i = 0; i < nx; i++) {
            if (x[i] != 0.0 && P[i * nx + i] > 0.0) {
                ix[k++] = i;
            }
        }
        if (k == 0) return 0;

        try {
            double[] xc = new double[k];
            double[] Pc = new double[k * k];
            double[] Hc = new double[nv * k];

            for (int i = 0; i < k; i++) {
                xc[i] = x[ix[i]];
                for (int j = 0; j < k; j++) {
                    Pc[i * k + j] = P[ix[i] * nx + ix[j]];
                }
                for (int j = 0; j < nv; j++) {
                    Hc[j * k + i] = H[j * nx + ix[i]];
                }
            }

            SimpleMatrix HcMat = MatrixUtil.createMatrix(Hc, nv, k);
            SimpleMatrix PcMat = MatrixUtil.createMatrix(Pc, k, k);
            SimpleMatrix RMat = MatrixUtil.createMatrix(R, nv, nv);
            SimpleMatrix Xc = MatrixUtil.createMatrix(xc, k, 1);

            SimpleMatrix Hct = MatrixUtil.transpose(HcMat);

            SimpleMatrix HPc = MatrixUtil.multiply(HcMat, PcMat);
            SimpleMatrix HPHt = MatrixUtil.multiply(HPc, Hct);
            SimpleMatrix S = MatrixUtil.add(HPHt, RMat);

            SimpleMatrix SInv = MatrixUtil.invert(S);

            SimpleMatrix PcHt = MatrixUtil.multiply(PcMat, Hct);
            SimpleMatrix K = MatrixUtil.multiply(PcHt, SInv);

            SimpleMatrix V = MatrixUtil.createMatrix(v, nv, 1);
            SimpleMatrix KV = MatrixUtil.multiply(K, V);
            SimpleMatrix XcNew = MatrixUtil.add(Xc, KV);

            SimpleMatrix KHc = MatrixUtil.multiply(K, HcMat);
            SimpleMatrix Ic = SimpleMatrix.identity(k);
            SimpleMatrix IKH = MatrixUtil.subtract(Ic, KHc);

            SimpleMatrix IKHP = MatrixUtil.multiply(IKH, PcMat);
            SimpleMatrix IKHt = MatrixUtil.transpose(IKH);
            SimpleMatrix PcNew = MatrixUtil.multiply(IKHP, IKHt);

            SimpleMatrix KR = MatrixUtil.multiply(K, RMat);
            SimpleMatrix Kt = MatrixUtil.transpose(K);
            SimpleMatrix KRKt = MatrixUtil.multiply(KR, Kt);
            PcNew = MatrixUtil.add(PcNew, KRKt);

            for (int i = 0; i < k; i++) {
                x[ix[i]] = XcNew.get(i, 0);
                for (int j = 0; j < k; j++) {
                    P[ix[i] * nx + ix[j]] = PcNew.get(i, j);
                }
            }

            return 0;
        } catch (Exception e) {
            LOG.debug("KalmanFilter.update failed: {}", e.getMessage());
            return -1;
        }
    }
}