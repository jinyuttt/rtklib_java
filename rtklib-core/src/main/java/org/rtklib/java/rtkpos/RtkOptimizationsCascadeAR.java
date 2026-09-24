package org.rtklib.java.rtkpos;

import org.rtklib.java.ambiguity.Lambda;
import org.rtklib.java.common.MatrixUtil;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.ejml.simple.SimpleMatrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class RtkOptimizationsCascadeAR {
    private static final Logger LOG = LoggerFactory.getLogger(RtkOptimizationsCascadeAR.class);

    private RtkOptimizationsCascadeAR() {
    }

    public static final int LEVEL_NONE = 0;
    public static final int LEVEL_EWL = 1;
    public static final int LEVEL_WL = 2;
    public static final int LEVEL_NL = 3;

    static double ewlWavelength(int sys, Nav nav) {
        if ((sys & Constants.SYS_GPS) != 0) return Constants.CLIGHT / (Constants.FREQL1 - Constants.FREQL2);
        if ((sys & Constants.SYS_GAL) != 0) return Constants.CLIGHT / (Constants.FREQE1 - Constants.FREQE5a);
        if ((sys & Constants.SYS_CMP) != 0) return Constants.CLIGHT / (Constants.FREQB1I - Constants.FREQB3I);
        return 0.0;
    }

    static double wlWavelength(int sys, Nav nav) {
        if ((sys & Constants.SYS_GPS) != 0) return Constants.CLIGHT / (Constants.FREQL1 - Constants.FREQL2);
        if ((sys & Constants.SYS_GAL) != 0) return Constants.CLIGHT / (Constants.FREQE1 - Constants.FREQE5b);
        if ((sys & Constants.SYS_CMP) != 0) return Constants.CLIGHT / (Constants.FREQB1I - Constants.FREQB2I);
        return 0.0;
    }

    static double nlWavelength(int sys, int freqIdx, Nav nav) {
        if ((sys & Constants.SYS_GPS) != 0) return Constants.CLIGHT / Constants.FREQL1;
        if ((sys & Constants.SYS_GAL) != 0) return Constants.CLIGHT / Constants.FREQE1;
        if ((sys & Constants.SYS_CMP) != 0) return Constants.CLIGHT / Constants.FREQB1I;
        return 0.0;
    }

    public static int cascadeAmbFix(Rtk rtk, double[] bias, double[] xa,
                                     int gps, int glo, int sbs, Nav nav) {
        RtkConfig cfg = rtk.rtkConfig;
        if (!cfg.enableCascadeAR) return -1;

        PrcOpt opt = rtk.opt;
        int nf = (opt.ionoopt == Constants.IONOOPT_IFLC) ? 1 : opt.nf;
        int nx = rtk.nx;
        int na = rtk.na;

        if (nf < 2) {
            LOG.debug("CascadeAR skipped: nf={} < 2", nf);
            return -1;
        }

        rtk.sol.ratio = 0.0f;
        rtk.nb_ar = 0;

        int[] ix = new int[nx * 2];
        int nb = RtkOptimizations.ddidxFallback(rtk, ix, gps, glo, sbs);
        if (nb < opt.minfixsats - 1) return -1;
        rtk.nb_ar = nb;

        int[] freeMap = new int[nb];
        int[] anchorMap = new int[nb];
        int freeCount = 0;
        int anchorCount = 0;

        if (cfg.enableAmbAnchor) {
            for (int i = 0; i < nb; i++) {
                int satIdx = (ix[i * 2 + 1] - na) % Constants.MAXSAT;
                int f = (ix[i * 2 + 1] - na) / Constants.MAXSAT;
                if (satIdx >= 0 && satIdx < Constants.MAXSAT && f >= 0 && f < nf) {
                    int globalIdx = satIdx * nf + f;
                    if (rtk.ambAnchored[globalIdx]) {
                        anchorMap[anchorCount++] = i;
                    } else {
                        freeMap[freeCount++] = i;
                    }
                } else {
                    freeMap[freeCount++] = i;
                }
            }
        } else {
            for (int i = 0; i < nb; i++) freeMap[i] = i;
            freeCount = nb;
        }

        int nbLambda = freeCount;
        if (nbLambda < opt.minfixsats - 1) return -1;

        double[] yFull = new double[nb];
        for (int i = 0; i < nb; i++) {
            yFull[i] = rtk.x[ix[i * 2]] - rtk.x[ix[i * 2 + 1]];
        }

        double[] y = new double[nbLambda];
        for (int i = 0; i < freeCount; i++) {
            y[i] = yFull[freeMap[i]];
        }

        int[] ixUsed = new int[nbLambda * 2];
        for (int i = 0; i < freeCount; i++) {
            ixUsed[i * 2] = ix[freeMap[i] * 2];
            ixUsed[i * 2 + 1] = ix[freeMap[i] * 2 + 1];
        }

        int nAmb = nx - na;
        SimpleMatrix PMat = MatrixUtil.createMatrix(rtk.P, nx, nx);

        SimpleMatrix QcMat = new SimpleMatrix(nAmb, nAmb);
        for (int j = 0; j < nAmb; j++)
            for (int i = 0; i < nAmb; i++)
                QcMat.set(i, j, PMat.get(na + i, na + j));

        SimpleMatrix QacMat = new SimpleMatrix(na, nAmb);
        for (int j = 0; j < nAmb; j++)
            for (int i = 0; i < na; i++)
                QacMat.set(i, j, PMat.get(i, na + j));

        SimpleMatrix DMat = new SimpleMatrix(nbLambda, nAmb);
        for (int i = 0; i < nbLambda; i++) {
            DMat.set(i, ixUsed[i * 2] - na, 1.0);
            DMat.set(i, ixUsed[i * 2 + 1] - na, -1.0);
        }

        SimpleMatrix QbMat = MatrixUtil.multiply(MatrixUtil.multiply(DMat, QcMat), MatrixUtil.transpose(DMat));
        SimpleMatrix QabMat = MatrixUtil.multiply(QacMat, MatrixUtil.transpose(DMat));

        double[] Qb = new double[nbLambda * nbLambda];
        for (int i = 0; i < nbLambda; i++)
            for (int j = 0; j < nbLambda; j++)
                Qb[i * nbLambda + j] = QbMat.get(i, j);
        for (int i = 0; i < nbLambda; i++)
            for (int j = i + 1; j < nbLambda; j++) {
                double avg = 0.5 * (Qb[i * nbLambda + j] + Qb[j * nbLambda + i]);
                Qb[i * nbLambda + j] = avg;
                Qb[j * nbLambda + i] = avg;
            }

        double[] Qab = new double[na * nbLambda];
        for (int i = 0; i < na; i++)
            for (int j = 0; j < nbLambda; j++)
                Qab[i * nbLambda + j] = QabMat.get(i, j);

        double[] ewlBias = null;
        int ewlFixed = 0;

        if (nf >= 3) {
            double ewlRatio = runLambdaLevel(nbLambda, y, Qb, cfg.cascadeArRatioEwl);
            rtk.cascadeArLastRatio[0] = ewlRatio;
            if (ewlRatio >= cfg.cascadeArRatioEwl) {
                ewlFixed = nbLambda;
                rtk.diagCascadeArEwlFixCount++;
                LOG.debug("CascadeAR EWL fixed: nb={} ratio={:.2f}", nbLambda, ewlRatio);
            }
        }

        double wlRatio = runLambdaLevel(nbLambda, y, Qb, cfg.cascadeArRatioWl);
        rtk.cascadeArLastRatio[1] = wlRatio;
        int wlFixed = 0;
        if (wlRatio >= cfg.cascadeArRatioWl) {
            wlFixed = nbLambda;
            rtk.diagCascadeArWlFixCount++;
            LOG.debug("CascadeAR WL fixed: nb={} ratio={:.2f}", nbLambda, wlRatio);
        }

        double[] b = new double[nbLambda * 2];
        double[] s = new double[2];
        int info = Lambda.lambda(nbLambda, 2, y, Qb, b, s);

        if (info != 0) return -1;

        double nlRatio = s[0] > 0 ? s[1] / s[0] : 0.0;
        rtk.cascadeArLastRatio[2] = nlRatio;
        rtk.sol.ratio = (float) nlRatio;
        if (rtk.sol.ratio > 999.9f) rtk.sol.ratio = 999.9f;

        if (nlRatio < cfg.cascadeArRatioNl) {
            LOG.debug("CascadeAR NL failed: ratio={:.2f} < thresh={:.2f}", nlRatio, cfg.cascadeArRatioNl);
            return -1;
        }

        if (cfg.cascadeArBootstrapping && cfg.enableBootstrapping) {
            if (!RtkOptimizationsBootstrap.validateFix(rtk, b, nbLambda)) {
                LOG.debug("CascadeAR NL Bootstrapping rejected");
                rtk.sol.ratio = 0.0f;
                rtk.diagBootstrapRejectCount++;
                return -1;
            }
        }

        rtk.diagCascadeArNlFixCount++;

        for (int i = 0; i < na; i++) {
            rtk.xa[i] = rtk.x[i];
            for (int j = 0; j < na; j++) {
                rtk.Pa[i * na + j] = rtk.P[i * nx + j];
            }
        }

        double[] biasFull = new double[nb];
        for (int i = 0; i < nbLambda; i++) {
            double fixedVal = b[i * 2];
            biasFull[freeMap[i]] = fixedVal;
            y[i] -= fixedVal;
        }
        if (cfg.enableAmbAnchor) {
            for (int i = 0; i < anchorCount; i++) {
                int idx = anchorMap[i];
                biasFull[idx] = Math.round(yFull[idx]);
            }
        }
        for (int i = 0; i < nb; i++) {
            bias[i] = biasFull[i];
        }

        SimpleMatrix QbInvMat = MatrixUtil.createMatrix(Qb, nbLambda, nbLambda);
        java.util.Optional<SimpleMatrix> QbInvOpt = MatrixUtil.invertSafe(QbInvMat);
        if (!QbInvOpt.isPresent()) return 0;

        SimpleMatrix QbInv = QbInvOpt.get();
        SimpleMatrix yMat = MatrixUtil.createMatrix(y, nbLambda, 1);
        SimpleMatrix QabInvMat = MatrixUtil.createMatrix(Qab, na, nbLambda);

        SimpleMatrix dbMat = MatrixUtil.multiply(QbInv, yMat);
        double[] db = new double[nbLambda];
        MatrixUtil.copyMatrix(dbMat, db);

        SimpleMatrix xaMat = MatrixUtil.createMatrix(rtk.xa, na, 1);
        SimpleMatrix xaNew = MatrixUtil.subtract(xaMat, MatrixUtil.multiply(QabInvMat, dbMat));
        MatrixUtil.copyMatrix(xaNew, rtk.xa);

        SimpleMatrix QQMat = MatrixUtil.multiply(QabInvMat, QbInv);
        double[] QQ = new double[na * nbLambda];
        MatrixUtil.copyMatrix(QQMat, QQ);

        SimpleMatrix PaMat = MatrixUtil.createMatrix(rtk.Pa, na, na);
        SimpleMatrix PaNew = MatrixUtil.subtract(PaMat, MatrixUtil.multiply(QQMat, MatrixUtil.transpose(QabInvMat)));
        MatrixUtil.copyMatrix(PaNew, rtk.Pa);

        for (int i = 0; i < nbLambda; i++) {
            int ambIdx = ixUsed[i * 2 + 1] - na;
            int satIdx = ambIdx % Constants.MAXSAT;
            int f = ambIdx / Constants.MAXSAT;
            if (satIdx >= 0 && satIdx < Constants.MAXSAT && f >= 0 && f < nf) {
                int globalIdx = satIdx * nf + f;
                rtk.cascadeArFixLevel[globalIdx] = LEVEL_NL;
            }
        }

        LOG.info("CascadeAR NL fixed: epoch={} nb={} ratio={:.2f} ewl={} wl={}",
                rtk.epoch, nbLambda, nlRatio, ewlFixed, wlFixed);

        return nb;
    }

    static double runLambdaLevel(int n, double[] y, double[] Qb, double ratioThresh) {
        if (n < 1) return 0.0;

        double[] yCopy = new double[n];
        double[] QbCopy = new double[n * n];
        System.arraycopy(y, 0, yCopy, 0, n);
        System.arraycopy(Qb, 0, QbCopy, 0, n * n);

        double[] b = new double[n * 2];
        double[] s = new double[2];
        int info = Lambda.lambda(n, 2, yCopy, QbCopy, b, s);

        if (info != 0 || s[0] <= 0.0) return 0.0;
        return s[1] / s[0];
    }
}