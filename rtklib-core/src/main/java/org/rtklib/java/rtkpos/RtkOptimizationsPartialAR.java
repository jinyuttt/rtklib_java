package org.rtklib.java.rtkpos;

import org.rtklib.java.ambiguity.Lambda;
import org.rtklib.java.common.MatrixUtil;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.ejml.simple.SimpleMatrix;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * [JAVA-ONLY 原创] + [REF: RTKLIB-EX 2.5.1] 部分模糊度固定（子集搜索）。
 *
 * <p><b>[REF: RTKLIB-EX 2.5.1]</b>：{@code rtkpos.c} 的 {@code manage_amb_LAMBDA()}
 * （注释原文 "partial fix techniques and multiple attempts"）提供了「首次全解 → 失败后
 * 剔除部分卫星 → 重跑 LAMBDA」的多次尝试框架，本类沿用该框架思想（非代码移植）。</p>
 *
 * <p><b>[JAVA-ONLY 原创]</b>：子集选择策略为按模糊度方差从大到小排序后逐级剔除（RTKLIB-EX
 * 按卫星列表顺序剔除下一颗并改写 lock），该选择策略、子集尝试上限、最小卫星数与 ratio 门限
 * 均由 {@link RtkConfig} 配置化，属本项目自行设计。</p>
 *
 * <p><b>未标注 GREAT-PVT</b>：本类未采用 GREAT-PVT {@code gambfix} 的 EWL→WL→NL 级联
 * （该级联思想由 {@link RtkOptimizationsCascadeAR} 承接），故不归因于该项目。</p>
 */
public final class RtkOptimizationsPartialAR {
    private static final Logger LOG = LoggerFactory.getLogger(RtkOptimizationsPartialAR.class);

    private RtkOptimizationsPartialAR() {
    }

    public static int partialAmbFix(Rtk rtk, double[] bias, double[] xa,
                                     int[] ix, int nb, int gps, int glo, int sbs) {
        RtkConfig cfg = rtk.rtkConfig;
        if (!cfg.rtk.enablePartialAR) return -1;

        PrcOpt opt = rtk.opt;
        int nf = (opt.ionoopt == Constants.IONOOPT_IFLC) ? 1 : opt.nf;
        int nx = rtk.nx;
        int na = rtk.na;
        int minSats = cfg.rtk.partialArMinSats;
        int maxTries = cfg.rtk.partialArMaxSubsetTries;

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
            if (ratio < cfg.rtk.partialArMinRatio) continue;

            if (cfg.rtk.enableBootstrapping) {
                double[] subBias = new double[subNb];
                for (int i = 0; i < subNb; i++) subBias[i] = b[i * 2];
                double successRate = RtkOptimizationsBootstrap.computeSuccessRate(subBias, Qb, subNb);
                if (successRate < cfg.rtk.partialArMinBootstrapping) continue;
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