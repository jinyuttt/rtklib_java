package org.rtklib.java.kalman;

import org.ejml.simple.SimpleMatrix;
import org.rtklib.java.common.MatrixUtil;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kalman filter measurement update.
 *
 * Corresponds to RTKLIB filter() in rtkcmn.c.
 * Implements state compression like C version: only non-zero states (x[i]!=0 && P[i+i*nx]>0)
 * participate in the Kalman update, significantly improving numerical stability and
 * matching C version behavior.
 *
 * Covariance update uses the Joseph form: P = (I-KH)*P*(I-KH)^T + K*R*K^T,
 * which structurally guarantees symmetry and positive semi-definiteness.
 * Mathematically equivalent to the standard form P = (I-KH)*P used in C version
 * (RTKLIB rtkcmn.c filter_), but with superior numerical stability under
 * finite-precision arithmetic (measured asymmetry ~1e-10 vs ~1e-9 for standard form
 * in RTK scenarios). Performance difference is negligible with EJML for k~18.
 */
public final class KalmanFilter {

    private static final Logger LOG = LoggerFactory.getLogger(KalmanFilter.class);

    private static final double KF_PSD_FLOOR = 1e-9;

    /* 最近一次 update() 实际参与压缩更新的状态索引集合（供上层链路验证
     * 正定化护甲的覆盖范围；本类仅提供观测，不参与任何计算逻辑）。 */
    private static volatile int[] lastActiveIdx = new int[0];

    public static int[] lastActiveIndices() {
        return lastActiveIdx;
    }

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
        return update(x, P, H, v, R, nx, nv, true);
    }

    /**
     * Kalman filter measurement update with state compression.
     *
     * @param skipZeroHCols if true, states whose H column is entirely zero are excluded
     *        from the update (Joint dual-baseline cross-pollution protection). If false,
     *        strictly follows RTKLIB C filter(): only x[i]!=0 && P[i+i*n]>0 states are
     *        updated, so position/troposphere states are also corrected through their
     *        cross-covariance with the ambiguity states (required by RTK holdamb).
     */
    public static int update(double[] x, double[] P, double[] H, double[] v,
                             double[] R, int nx, int nv, boolean skipZeroHCols) {
        if (nv <= 0 || nx <= 0) return 0;

        int[] ix = new int[nx];
        int k = 0;
        for (int i = 0; i < nx; i++) {
            if (x[i] != 0.0 && P[i * nx + i] > 0.0) {
                if (skipZeroHCols) {
                    boolean hasNonZero = false;
                    for (int j = 0; j < nv; j++) {
                        if (Math.abs(H[j * nx + i]) > 0.0) {
                            hasNonZero = true;
                            break;
                        }
                    }
                    if (!hasNonZero) continue;
                }
                ix[k++] = i;
            }
        }
        int[] snapshot = new int[k];
        System.arraycopy(ix, 0, snapshot, 0, k);
        lastActiveIdx = snapshot;

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
                for (int j = i + 1; j < k; j++) {
                    double aij = PcNew.get(i, j), aji = PcNew.get(j, i);
                    double m = 0.5 * (aij + aji);
                    PcNew.set(i, j, m);
                    PcNew.set(j, i, m);
                }
            }
            double peigMin = minEigenValue(PcNew, k);
            if (!(peigMin >= KF_PSD_FLOOR)) {
                LOG.warn("[KF-WARN] non-PSD covariance after update eigMin={} k={} nv={} -> project to PSD", peigMin, k, nv);
                projectPsd(PcNew, k, KF_PSD_FLOOR);
            }
            for (int i = 0; i < k; i++) {
                double dii = PcNew.get(i, i);
                if (dii < KF_PSD_FLOOR) {
                    LOG.warn("[KF-WARN] Diag forced positive state={} Pbefore={} Pafter={} nx={} nv={} k={}", ix[i], Pc[i * k + i], dii, nx, nv, k);
                    PcNew.set(i, i, KF_PSD_FLOOR);
                }
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

    private static org.ejml.dense.row.decomposition.eig.SymmetricQRAlgorithmDecomposition_DDRM eigDecomp(SimpleMatrix m, int n, boolean vectors) {
        org.ejml.data.DMatrixRMaj a = new org.ejml.data.DMatrixRMaj(n, n);
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) a.set(i, j, m.get(i, j));
        org.ejml.dense.row.decomposition.eig.SymmetricQRAlgorithmDecomposition_DDRM eig =
                new org.ejml.dense.row.decomposition.eig.SymmetricQRAlgorithmDecomposition_DDRM(vectors);
        return eig.decompose(a) ? eig : null;
    }

    private static double minEigenValue(SimpleMatrix m, int n) {
        org.ejml.dense.row.decomposition.eig.SymmetricQRAlgorithmDecomposition_DDRM eig = eigDecomp(m, n, false);
        if (eig == null) return Double.NaN;
        double mn = Double.MAX_VALUE;
        for (int i = 0; i < n; i++) mn = Math.min(mn, eig.getEigenvalue(i).real);
        return mn;
    }

    private static void projectPsd(SimpleMatrix m, int n, double floor) {
        org.ejml.dense.row.decomposition.eig.SymmetricQRAlgorithmDecomposition_DDRM eig = eigDecomp(m, n, true);
        if (eig == null) {
            LOG.warn("[KF-WARN] PSD projection: eigen decomposition failed, n={} -> jitter fallback", n);
            jitterPsd(m, n, floor);
            return;
        }
        double[] out = new double[n * n];
        for (int i = 0; i < n; i++) {
            double lam = Math.max(eig.getEigenvalue(i).real, floor);
            org.ejml.data.DMatrixRMaj vec = eig.getEigenVector(i);
            for (int r = 0; r < n; r++) {
                double vr = vec.get(r, 0);
                for (int c = 0; c < n; c++) out[r * n + c] += lam * vr * vec.get(c, 0);
            }
        }
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) m.set(i, j, out[i * n + j]);
    }

    private static void jitterPsd(SimpleMatrix m, int n, double floor) {
        double scale = 0.0;
        for (int i = 0; i < n; i++) scale = Math.max(scale, Math.abs(m.get(i, i)));
        double jitter = Math.max(floor, scale * 1e-10);
        for (int it = 0; it < 30; it++) {
            org.ejml.data.DMatrixRMaj a = new org.ejml.data.DMatrixRMaj(n, n);
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) a.set(i, j, m.get(i, j) + (i == j ? jitter : 0.0));
            }
            org.ejml.dense.row.decomposition.chol.CholeskyDecompositionInner_DDRM chol =
                    new org.ejml.dense.row.decomposition.chol.CholeskyDecompositionInner_DDRM(true);
            if (chol.decompose(a)) {
                for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) m.set(i, j, a.get(i, j));
                LOG.warn("[KF-WARN] PSD jitter applied: n={} jitter={} iter={}", n, jitter, it);
                return;
            }
            jitter *= 4.0;
        }
        LOG.warn("[KF-WARN] PSD jitter failed, matrix left as-is: n={}", n);
    }
}
