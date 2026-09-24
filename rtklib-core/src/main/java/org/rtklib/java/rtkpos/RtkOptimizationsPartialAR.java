package org.rtklib.java.rtkpos;

import org.rtklib.java.ambiguity.Lambda;
import org.rtklib.java.common.MatrixUtil;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.ejml.simple.SimpleMatrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RtkOptimizationsPartialAR {
    private static final Logger LOG = LoggerFactory.getLogger(RtkOptimizationsPartialAR.class);

    private RtkOptimizationsPartialAR() {
    }

    public static int partialAmbFix(Rtk rtk, double[] bias, double[] xa,
                                     int[] ix, int nb, int gps, int glo, int sbs) {
        RtkConfig cfg = rtk.rtkConfig;
        if (!cfg.enablePartialAR) return -1;

        PrcOpt opt = rtk.opt;
        int nf = (opt.ionoopt == Constants.IONOOPT_IFLC) ? 1 : opt.nf;
        int nx = rtk.nx;
        int na = rtk.na;
        int minSats = cfg.partialArMinSats;
        int maxTries = cfg.partialArMaxSubsetTries;

        if (nb < minSats) return -1;

        rtk.diagPartialArAttemptCount++;

        double[] ambVar = new double[nb];
        int[] sortIdx = new int[nb];
        for (int i = 0; i < nb; i++) {
            int idx1 = ix[i * 2];
            int idx2 = ix[i * 2 + 1];
            double var1 = rtk.P[idx1 * nx + idx1];
            double var2 = rtk.P[idx2 * nx + idx2];
            double cov = rtk.P[idx1 * nx + idx2];
            ambVar[i] = var1 + var2 - 2.0 * cov;
            sortIdx[i] = i;
        }

        for (int i = 0; i < nb - 1; i++) {
            for (int j = i + 1; j < nb; j++) {
                if (ambVar[sortIdx[j]] > ambVar[sortIdx[i]]) {
                    int tmp = sortIdx[i];
                    sortIdx[i] = sortIdx[j];
                    sortIdx[j] = tmp;
                }
            }
        }

        for (int drop = 1; drop <= Math.min(maxTries, nb - minSats); drop++) {
            int subNb = nb - drop;
            if (subNb < minSats - 1) break;

            int[] subIx = new int[subNb * 2];
            int[] keepMap = new int[subNb];
            int k = 0;
            for (int i = 0; i < nb; i++) {
                boolean dropped = false;
                for (int d = 0; d < drop; d++) {
                    if (i == sortIdx[d]) {
                        dropped = true;
                        break;
                    }
                }
                if (!dropped) {
                    keepMap[k] = i;
                    subIx[k * 2] = ix[i * 2];
                    subIx[k * 2 + 1] = ix[i * 2 + 1];
                    k++;
                }
            }

            double[] y = new double[subNb];
            for (int i = 0; i < subNb; i++) {
                y[i] = rtk.x[subIx[i * 2]] - rtk.x[subIx[i * 2 + 1]];
            }

            int nAmb = nx - na;
            SimpleMatrix PMat = MatrixUtil.createMatrix(rtk.P, nx, nx);

            SimpleMatrix QcMat = new SimpleMatrix(nAmb, nAmb);
            for (int j = 0; j < nAmb; j++)
                for (int i = 0; i < nAmb; i++)
                    QcMat.set(i, j, PMat.get(na + i, na + j));

            SimpleMatrix DMat = new SimpleMatrix(subNb, nAmb);
            for (int i = 0; i < subNb; i++) {
                DMat.set(i, subIx[i * 2] - na, 1.0);
                DMat.set(i, subIx[i * 2 + 1] - na, -1.0);
            }

            SimpleMatrix QbMat = MatrixUtil.multiply(MatrixUtil.multiply(DMat, QcMat), MatrixUtil.transpose(DMat));

            double[] Qb = new double[subNb * subNb];
            for (int i = 0; i < subNb; i++)
                for (int j = 0; j < subNb; j++)
                    Qb[i * subNb + j] = QbMat.get(i, j);
            for (int i = 0; i < subNb; i++)
                for (int j = i + 1; j < subNb; j++) {
                    double avg = 0.5 * (Qb[i * subNb + j] + Qb[j * subNb + i]);
                    Qb[i * subNb + j] = avg;
                    Qb[j * subNb + i] = avg;
                }

            double[] b = new double[subNb * 2];
            double[] s = new double[2];
            int info = Lambda.lambda(subNb, 2, y, Qb, b, s);

            if (info != 0 || s[0] <= 0.0) continue;

            double ratio = s[1] / s[0];
            if (ratio < cfg.partialArMinRatio) continue;

            if (cfg.enableBootstrapping) {
                double[] subBias = new double[subNb];
                for (int i = 0; i < subNb; i++) subBias[i] = b[i * 2];
                double successRate = RtkOptimizationsBootstrap.computeSuccessRate(subBias, Qb, subNb);
                if (successRate < cfg.partialArMinBootstrapping) continue;
            }

            LOG.info("PartialAR fixed: drop={} subNb={} ratio={:.2f}", drop, subNb, ratio);

            for (int i = 0; i < na; i++) {
                rtk.xa[i] = rtk.x[i];
                for (int j = 0; j < na; j++) {
                    rtk.Pa[i * na + j] = rtk.P[i * nx + j];
                }
            }

            double[] biasFull = new double[nb];
            for (int i = 0; i < subNb; i++) {
                biasFull[keepMap[i]] = b[i * 2];
            }
            for (int i = 0; i < nb; i++) {
                bias[i] = biasFull[i];
            }

            SimpleMatrix QacMat = new SimpleMatrix(na, nAmb);
            for (int j = 0; j < nAmb; j++)
                for (int i = 0; i < na; i++)
                    QacMat.set(i, j, PMat.get(i, na + j));
            SimpleMatrix QabMat = MatrixUtil.multiply(QacMat, MatrixUtil.transpose(DMat));
            double[] Qab = new double[na * subNb];
            for (int i = 0; i < na; i++)
                for (int j = 0; j < subNb; j++)
                    Qab[i * subNb + j] = QabMat.get(i, j);

            SimpleMatrix QbInvMat = MatrixUtil.createMatrix(Qb, subNb, subNb);
            java.util.Optional<SimpleMatrix> QbInvOpt = MatrixUtil.invertSafe(QbInvMat);
            if (!QbInvOpt.isPresent()) continue;

            SimpleMatrix QbInv = QbInvOpt.get();
            double[] db = new double[subNb];
            for (int i = 0; i < subNb; i++) y[i] -= b[i * 2];
            SimpleMatrix yMat = MatrixUtil.createMatrix(y, subNb, 1);
            SimpleMatrix QabInvMat = MatrixUtil.createMatrix(Qab, na, subNb);

            SimpleMatrix dbMat = MatrixUtil.multiply(QbInv, yMat);
            MatrixUtil.copyMatrix(dbMat, db);

            SimpleMatrix xaMat = MatrixUtil.createMatrix(rtk.xa, na, 1);
            SimpleMatrix xaNew = MatrixUtil.subtract(xaMat, MatrixUtil.multiply(QabInvMat, dbMat));
            MatrixUtil.copyMatrix(xaNew, rtk.xa);

            SimpleMatrix QQMat = MatrixUtil.multiply(QabInvMat, QbInv);
            SimpleMatrix PaMat = MatrixUtil.createMatrix(rtk.Pa, na, na);
            SimpleMatrix PaNew = MatrixUtil.subtract(PaMat, MatrixUtil.multiply(QQMat, MatrixUtil.transpose(QabInvMat)));
            MatrixUtil.copyMatrix(PaNew, rtk.Pa);

            rtk.sol.ratio = (float) ratio;
            rtk.diagPartialArFixCount++;
            return nb;
        }

        return -1;
    }
}