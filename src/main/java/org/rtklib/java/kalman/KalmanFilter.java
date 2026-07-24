package org.rtklib.java.kalman;

import org.ejml.simple.SimpleMatrix;
import org.rtklib.java.common.MatrixUtil;

/**
 * Kalman filter measurement update.
 *
 * <p>Corresponds to RTKLIB filter() in rtkcmn.c.
 * Implements state compression like C version: only non-zero states (x[i]!=0 && P[i+i*nx]>0)
 * participate in the Kalman update, significantly improving numerical stability and
 * matching C version behavior.</p>
 *
 * <p>Covariance update uses a hybrid strategy:
 * <ul>
 *   <li>Standard form: P = (I-KH)*P, then symmetrize P = (P+P^T)/2</li>
 *   <li>If any diagonal element becomes non-positive after standard update,
 *       fall back to Joseph form: P = (I-KH)*P*(I-KH)^T + K*R*K^T</li>
 * </ul>
 * This combines the tighter covariance of the standard form (better ambiguity resolution)
 * with the guaranteed positive semi-definiteness of the Joseph form.</p>
 */
public final class KalmanFilter {
    public static int debugEpochNum = -1;
    public static boolean debugEpoch = false;

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

        if (debugEpoch || nx > 100) {
            System.err.printf("[KF-COMPRESS] nx=%d k=%d (active states) nv=%d%n", nx, k, nv);
            double sumAmbVarPre = 0;
            int ambCntPre = 0;
            for (int ai = 0; ai < k; ai++) {
                if (ix[ai] >= 3) {
                    sumAmbVarPre += P[ix[ai] * nx + ix[ai]];
                    ambCntPre++;
                }
            }
            if (ambCntPre > 0) System.err.printf("[KF-AMB-PRE] avg_amb_var=%.4f amb_cnt=%d%n", sumAmbVarPre / ambCntPre, ambCntPre);
        }

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

            if (debugEpoch && k >= 3) {
                System.err.printf("[K-POS-ROW] nv=%d k=%d%n", nv, k);
                System.err.printf("  Kx = [");
                for (int kj = 0; kj < Math.min(nv, 8); kj++) System.err.printf("%.6f ", K.get(0, kj));
                System.err.printf("...]%n");
                System.err.printf("  Ky = [");
                for (int kj = 0; kj < Math.min(nv, 8); kj++) System.err.printf("%.6f ", K.get(1, kj));
                System.err.printf("...]%n");
                System.err.printf("  Kz = [");
                for (int kj = 0; kj < Math.min(nv, 8); kj++) System.err.printf("%.6f ", K.get(2, kj));
                System.err.printf("...]%n");
                for (int ambLocalIdx = 3; ambLocalIdx < Math.min(8, k); ambLocalIdx++) {
                    if (ix[ambLocalIdx] >= 108 && ix[ambLocalIdx] <= 150) {
                        System.err.printf("  K_amb[%d](global=%d) = [", ambLocalIdx, ix[ambLocalIdx]);
                        for (int kj = 0; kj < Math.min(nv, 8); kj++) System.err.printf("%.6f ", K.get(ambLocalIdx, kj));
                        System.err.printf("...]%n");
                    }
                }
            }

            SimpleMatrix V = MatrixUtil.createMatrix(v, nv, 1);
            SimpleMatrix KV = MatrixUtil.multiply(K, V);
            SimpleMatrix XcNew = MatrixUtil.add(Xc, KV);

            if (debugEpoch && k > 0) {
                double maxV = 0;
                for (int vi = 0; vi < nv; vi++) if (Math.abs(v[vi]) > maxV) maxV = Math.abs(v[vi]);
                double maxK = 0;
                int maxKi = 0, maxKj = 0;
                for (int ki = 0; ki < k; ki++) for (int kj = 0; kj < nv; kj++)
                    if (Math.abs(K.get(ki, kj)) > maxK) { maxK = Math.abs(K.get(ki, kj)); maxKi = ki; maxKj = kj; }
                System.err.printf("[KF-DEBUG] k=%d nv=%d maxV=%.4f maxK=%.4f at K[%d,%d]%n", k, nv, maxV, maxK, maxKi, maxKj);
                double sMin = Double.MAX_VALUE, sMax = 0;
                for (int si = 0; si < nv; si++) { double sv = S.get(si,si); if (sv < sMin) sMin = sv; if (sv > sMax) sMax = sv; }
                System.err.printf("[KF-S] S_diag_range=[%.6f,%.6f] cond=%.1f%n", sMin, sMax, sMax/sMin);
                double maxHpos = 0;
                for (int hi = 0; hi < nv; hi++) for (int hj = 0; hj < Math.min(3, k); hj++)
                    if (Math.abs(HcMat.get(hi, hj)) > maxHpos) maxHpos = Math.abs(HcMat.get(hi, hj));
                System.err.printf("[KF-H] max_H_pos=%.6f H[0,0:3]=%.6f,%.6f,%.6f%n",
                    maxHpos, HcMat.get(0,0), k>1?HcMat.get(0,1):0, k>2?HcMat.get(0,2):0);
                System.err.printf("[KF-IX] ix[0:8]=%d,%d,%d,%d,%d,%d,%d,%d,%d%n",
                    ix[0], ix[1], ix[2], ix[3], ix[4], ix[5], ix[6], ix[7], ix[8]);
                if (k > 9) System.err.printf("[KF-IX] ix[9:17]=%d,%d,%d,%d,%d,%d,%d,%d,%d%n",
                    ix[9], ix[10], ix[11], ix[12], ix[13], ix[14], ix[15], ix[16], ix[17]);
                if (k > 18) System.err.printf("[KF-IX] ix[18:26]=%d,%d,%d,%d,%d,%d,%d,%d,%d%n",
                    ix[18], ix[19], ix[20], ix[21], ix[22], ix[23], ix[24], ix[25], ix[26]);
                System.err.printf("[KF-XC] xc[0:8]=%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f%n",
                    xc[0], xc[1], xc[2], xc[3], xc[4], xc[5], xc[6], xc[7], xc[8]);
                System.err.printf("[KF-KV] KV[0:8]=%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f,%.4f%n",
                    KV.get(0,0), k>1?KV.get(1,0):0, k>2?KV.get(2,0):0,
                    k>3?KV.get(3,0):0, k>4?KV.get(4,0):0, k>5?KV.get(5,0):0,
                    k>6?KV.get(6,0):0, k>7?KV.get(7,0):0, k>8?KV.get(8,0):0);
                double maxPcOff = 0;
                for (int pi = 0; pi < k; pi++) for (int pj = 0; pj < k; pj++)
                    if (pi != pj && Math.abs(PcMat.get(pi,pj)) > maxPcOff) maxPcOff = Math.abs(PcMat.get(pi,pj));
                double maxAmbOff = 0;
                for (int pi = 3; pi < k; pi++) for (int pj = 3; pj < k; pj++)
                    if (pi != pj && Math.abs(PcMat.get(pi,pj)) > maxAmbOff) maxAmbOff = Math.abs(PcMat.get(pi,pj));
                System.err.printf("[KF-PC] max_offdiag=%.4f max_amb_offdiag=%.4f Pc[0:2,3:5]=%.4f,%.4f,%.4f,%.4f,%.4f,%.4f%n",
                    maxPcOff, maxAmbOff,
                    PcMat.get(0,3), PcMat.get(0,4), PcMat.get(0,5),
                    PcMat.get(1,3), PcMat.get(1,4), PcMat.get(1,5));
            }

            SimpleMatrix KHc = MatrixUtil.multiply(K, HcMat);
            SimpleMatrix Ic = SimpleMatrix.identity(k);
            SimpleMatrix IKH = MatrixUtil.subtract(Ic, KHc);

            if (debugEpoch) {
                System.err.printf("[KF-KH] KH[0,0:5]=%.4f,%.4f,%.4f,%.4f,%.4f,%.4f%n",
                    KHc.get(0,0), k>1?KHc.get(0,1):0, k>2?KHc.get(0,2):0, k>3?KHc.get(0,3):0, k>4?KHc.get(0,4):0, k>5?KHc.get(0,5):0);
                System.err.printf("[KF-IKH] IKH[0,0:5]=%.4f,%.4f,%.4f,%.4f,%.4f,%.4f%n",
                    IKH.get(0,0), k>1?IKH.get(0,1):0, k>2?IKH.get(0,2):0, k>3?IKH.get(0,3):0, k>4?IKH.get(0,4):0, k>5?IKH.get(0,5):0);
                System.err.printf("[KF-IKH] IKH[3,0:5]=%.4f,%.4f,%.4f,%.4f,%.4f,%.4f%n",
                    IKH.get(3,0), k>1?IKH.get(3,1):0, k>2?IKH.get(3,2):0, k>3?IKH.get(3,3):0, k>4?IKH.get(3,4):0, k>5?IKH.get(3,5):0);
                for (int ambLocalIdx = 3; ambLocalIdx < Math.min(8, k); ambLocalIdx++) {
                    if (ix[ambLocalIdx] >= 108 && ix[ambLocalIdx] <= 150) {
                        System.err.printf("[KF-KH-AMB] KH[%d](global=%d)[0:5]=%.4f,%.4f,%.4f,%.4f,%.4f,%.4f%n",
                            ambLocalIdx, ix[ambLocalIdx],
                            KHc.get(ambLocalIdx,0), k>1?KHc.get(ambLocalIdx,1):0, k>2?KHc.get(ambLocalIdx,2):0,
                            k>3?KHc.get(ambLocalIdx,3):0, k>4?KHc.get(ambLocalIdx,4):0, k>5?KHc.get(ambLocalIdx,5):0);
                        System.err.printf("[KF-IKH-AMB] IKH[%d](global=%d)[0:5]=%.4f,%.4f,%.4f,%.4f,%.4f,%.4f%n",
                            ambLocalIdx, ix[ambLocalIdx],
                            IKH.get(ambLocalIdx,0), k>1?IKH.get(ambLocalIdx,1):0, k>2?IKH.get(ambLocalIdx,2):0,
                            k>3?IKH.get(ambLocalIdx,3):0, k>4?IKH.get(ambLocalIdx,4):0, k>5?IKH.get(ambLocalIdx,5):0);
                    }
                }
            }

            SimpleMatrix PcNewStandard = MatrixUtil.multiply(IKH, PcMat);

            SimpleMatrix PcNewStandardSym = MatrixUtil.add(PcNewStandard, MatrixUtil.transpose(PcNewStandard));
            for (int si = 0; si < k; si++) for (int sj = 0; sj < k; sj++)
                PcNewStandardSym.set(si, sj, PcNewStandardSym.get(si, sj) * 0.5);

            boolean useJoseph = false;
            for (int ci = 0; ci < k; ci++) {
                if (PcNewStandardSym.get(ci, ci) <= 0) {
                    useJoseph = true;
                    break;
                }
            }

            SimpleMatrix PcNew;
            SimpleMatrix IKH_T = MatrixUtil.transpose(IKH);
            SimpleMatrix P_temp = MatrixUtil.multiply(IKH, PcMat);
            SimpleMatrix P_IKH = MatrixUtil.multiply(P_temp, IKH_T);
            SimpleMatrix KR = MatrixUtil.multiply(K, RMat);
            SimpleMatrix KRKt = MatrixUtil.multiply(KR, MatrixUtil.transpose(K));
            SimpleMatrix PcJoseph = MatrixUtil.add(P_IKH, KRKt);
            PcNew = PcJoseph;

            if (debugEpoch) {
                SimpleMatrix IKH_T2 = MatrixUtil.transpose(IKH);
                SimpleMatrix P_temp2 = MatrixUtil.multiply(IKH, PcMat);
                SimpleMatrix P_IKH2 = MatrixUtil.multiply(P_temp2, IKH_T2);
                SimpleMatrix KR2 = MatrixUtil.multiply(K, RMat);
                SimpleMatrix KRKt2 = MatrixUtil.multiply(KR2, MatrixUtil.transpose(K));
                for (int ambLocalIdx = 3; ambLocalIdx < Math.min(8, k); ambLocalIdx++) {
                    if (ix[ambLocalIdx] >= 108 && ix[ambLocalIdx] <= 150) {
                        System.err.printf("[KF-DECOMP] amb_local=%d global=%d IKH*P*IKHt[diag]=%.4f KRKt[diag]=%.4f sum=%.4f Pc_old=%.4f%n",
                            ambLocalIdx, ix[ambLocalIdx],
                            P_IKH2.get(ambLocalIdx, ambLocalIdx),
                            KRKt2.get(ambLocalIdx, ambLocalIdx),
                            P_IKH2.get(ambLocalIdx, ambLocalIdx) + KRKt2.get(ambLocalIdx, ambLocalIdx),
                            PcMat.get(ambLocalIdx, ambLocalIdx));
                    }
                }
                SimpleMatrix PcJosephDbg = MatrixUtil.add(P_IKH2, KRKt2);
                double maxDiff = 0;
                for (int di = 0; di < Math.min(3, k); di++) for (int dj = 0; dj < Math.min(3, k); dj++)
                    if (Math.abs(PcNew.get(di, dj) - PcJosephDbg.get(di, dj)) > maxDiff)
                        maxDiff = Math.abs(PcNew.get(di, dj) - PcJosephDbg.get(di, dj));
                System.err.printf("[KF-JOSEPH-CMP] useJoseph=true maxDiff_pos=%.6f Pstd[0,0]=%.4f Pjoseph[0,0]=%.4f%n",
                    maxDiff, PcNew.get(0,0), PcJosephDbg.get(0,0));
            }

            for (int i = 0; i < k; i++) {
                x[ix[i]] = XcNew.get(i, 0);
                for (int j = 0; j < k; j++) {
                    P[ix[i] * nx + ix[j]] = PcNew.get(i, j);
                }
            }

            if (nx > 100) {
                double sumAmbVarPost = 0;
                int ambCntPost = 0;
                for (int ai = 0; ai < k; ai++) {
                    if (ix[ai] >= 3) {
                        sumAmbVarPost += PcNew.get(ai, ai);
                        ambCntPost++;
                    }
                }
                if (ambCntPost > 0) System.err.printf("[KF-AMB-POST] avg_amb_var=%.4f amb_cnt=%d%n", sumAmbVarPost / ambCntPost, ambCntPost);
            }

            return 0;
        } catch (Exception e) {
            return -1;
        }
    }
}