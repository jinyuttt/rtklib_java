package org.rtklib.java.rtkpos;

import org.ejml.simple.SimpleMatrix;
import org.rtklib.java.ambiguity.Lambda;
import org.rtklib.java.common.MatrixUtil;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.Nav;
import org.rtklib.java.data.PrcOpt;
import org.rtklib.java.data.Rtk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * [JAVA-ONLY] 真逐级 Cascade AR（EWL→WL→NL 逐级约束固定）。
 *
 * <p>算法参考 GREAT-PVT（GREAT-WHU/GREAT, LibGREAT gambfix）逐级模糊度固定思想，
 * 结合 RTKLIB 双差（DD）状态空间实现。与 RTKLIB 2.5.0 无对齐锚点（官方 2.4.3 的
 * resamb_WLNL/TCAR 未移植），属 JAVA-ONLY 扩展，正确性通过数据集行为指标验证。
 * 由 {@link RtkConfig#enableCascadeAR} 开关控制，默认关闭；关闭或 IFLC 模式下
 * 不进入本路径，与标准 LAMBDA 路径逐值一致。</p>
 *
 * <h3>与旧实现（伪级联）的差异</h3>
 * <ol>
 *   <li>每级构建独立的组合观测向量与协方差（z=C·y、Qz=C·Q·Cᵀ），独立执行 LAMBDA
 *       与独立 ratio 门限；旧实现三级共用同一 y/Qb，为冗余验证。</li>
 *   <li>级间通过条件更新传递约束：K=Q·Cᵀ·Qz⁻¹，y←y−K(z−b)、Q←Q−K·C·Q
 *       （与 GREAT 虚拟观测注入数学等价）。</li>
 *   <li>整数反推恢复每频率 DD 整数：N1=b_NL、N2=N1−b_WL、N5=N2−b_EWL（周单位），
 *       不可成链的行降级为 float（fix=1），保证 restamb 语义一致。</li>
 *   <li>NL 级门限对齐标准路径动态调整（C rtkpos.c ar_poly_coeffs 多项式），
 *       并以 cfg.cascadeArRatioNl 作为下限，修复旧实现固定阈值缺陷。</li>
 *   <li>成功路径完整复刻标准路径：条件更新 rtk.xa/rtk.Pa + restamb 恢复单差模糊度，
 *       修复旧实现缺失 restamb 导致 xa 恒零（输出基站坐标）的假 Fix 问题。</li>
 * </ol>
 *
 * <h3>组合定义（周单位，DD 作用于同一卫星对）</h3>
 * <ul>
 *   <li>EWL（nf≥3）：N_EWL = N_f2 − N_f5（GPS L2−L5 / GAL E5a−E5b / BDS B2I−B3I）</li>
 *   <li>WL：N_WL = N_f1 − N_f2</li>
 *   <li>NL：N_NL = N_f1</li>
 *   <li>反推：N_f1 = b_NL；N_f2 = b_NL − b_WL；N_f5 = N_f2 − b_EWL</li>
 * </ul>
 *
 * <p>组合均在周（cycle）域进行，LAMBDA 直接作用于无量纲周值，与系统频率无关；
 * 波长辅助函数仅用于诊断。参考星按（系统,频率）独立选取，故跨频率组合按
 * 精确卫星对（参考星+流动星）配对构建。</p>
 */
public final class RtkOptimizationsCascadeAR {
    private static final Logger LOG = LoggerFactory.getLogger(RtkOptimizationsCascadeAR.class);

    private RtkOptimizationsCascadeAR() {
    }

    public static final int LEVEL_NONE = 0;
    public static final int LEVEL_EWL = 1;
    public static final int LEVEL_WL = 2;
    public static final int LEVEL_NL = 3;

    /**
     * EWL 拍频波长（诊断用途；组合在周域进行，波长符号不影响整数性，
     * 此处取绝对值保证物理波长为正）。
     */
    static double ewlWavelength(int sys, Nav nav) {
        if ((sys & Constants.SYS_GPS) != 0) return Constants.CLIGHT / Math.abs(Constants.FREQL2 - Constants.FREQL5);
        if ((sys & Constants.SYS_GAL) != 0) return Constants.CLIGHT / Math.abs(Constants.FREQE5a - Constants.FREQE5b);
        if ((sys & Constants.SYS_CMP) != 0) return Constants.CLIGHT / Math.abs(Constants.FREQB2I - Constants.FREQB3I);
        return 0.0;
    }

    static double wlWavelength(int sys, Nav nav) {
        if ((sys & Constants.SYS_GPS) != 0) return Constants.CLIGHT / (Constants.FREQL1 - Constants.FREQL2);
        if ((sys & Constants.SYS_GAL) != 0) return Constants.CLIGHT / (Constants.FREQE1 - Constants.FREQE5a);
        if ((sys & Constants.SYS_CMP) != 0) return Constants.CLIGHT / (Constants.FREQB1I - Constants.FREQB2I);
        return 0.0;
    }

    static double nlWavelength(int sys, int freqIdx, Nav nav) {
        if ((sys & Constants.SYS_GPS) != 0) return Constants.CLIGHT / Constants.FREQL1;
        if ((sys & Constants.SYS_GAL) != 0) return Constants.CLIGHT / Constants.FREQE1;
        if ((sys & Constants.SYS_CMP) != 0) return Constants.CLIGHT / Constants.FREQB1I;
        return 0.0;
    }

    /**
     * 逐级 Cascade AR 固定入口。任一级失败返回 -1，由 RtkCore.resamb_LAMBDA
     * 回落标准 LAMBDA 路径；成功返回 DD 行数 nb（语义同标准路径）。
     */
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

        // ---- 1. 双差索引构建（与标准路径一致） ----
        int[] ix = new int[nx * 2];
        int nb = RtkOptimizations.ddidxFallback(rtk, ix, gps, glo, sbs);
        if (nb < opt.minfixsats - 1) return -1;
        rtk.nb_ar = nb;

        // ---- 2. 锚定/自由行划分（与标准路径 enableAmbAnchor 语义一致） ----
        int[] freeMap = new int[nb];
        int[] anchorMap = new int[nb];
        int freeCount = 0;
        int anchorCount = 0;

        if (cfg.enableAmbAnchor) {
            for (int i = 0; i < nb; i++) {
                int ambIdx = ix[i * 2 + 1] - na;
                int satIdx = ambIdx % Constants.MAXSAT;
                int f = ambIdx / Constants.MAXSAT;
                if (satIdx >= 0 && satIdx < Constants.MAXSAT && f >= 0 && f < nf
                        && rtk.ambAnchored[satIdx * nf + f]) {
                    anchorMap[anchorCount++] = i;
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

        // ---- 3. 自由行浮点 DD 模糊度与协方差（与标准路径一致） ----
        double[] yFull = new double[nb];
        for (int i = 0; i < nb; i++) {
            yFull[i] = rtk.x[ix[i * 2]] - rtk.x[ix[i * 2 + 1]];
        }

        double[] y = new double[nbLambda];    // 工作副本（逐级条件更新）
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
        SimpleMatrix QbMat = symmetrize(MatrixUtil.multiply(MatrixUtil.multiply(DMat, QcMat), MatrixUtil.transpose(DMat)));
        SimpleMatrix QabMat = MatrixUtil.multiply(QacMat, MatrixUtil.transpose(DMat));
        SimpleMatrix Q = QbMat.copy();   // 级联工作协方差

        // ---- 4. 按卫星对分组自由行（参考星按频率独立选取，须精确配对） ----
        // pairKey = refSat0*(MAXSAT+1)+rovSat0；value = {rowF1, rowF2, rowF5}（自由行下标，-1 缺失）
        Map<Long, int[]> pairs = new LinkedHashMap<>();
        for (int r = 0; r < nbLambda; r++) {
            int ambRef = ixUsed[r * 2] - na;
            int ambRov = ixUsed[r * 2 + 1] - na;
            int f = ambRov / Constants.MAXSAT;
            int refSat = ambRef % Constants.MAXSAT;
            int rovSat = ambRov % Constants.MAXSAT;
            if (f < 0 || f >= nf) continue;
            long key = (long) refSat * (Constants.MAXSAT + 1) + rovSat;
            int[] pr = pairs.computeIfAbsent(key, k -> new int[]{-1, -1, -1});
            pr[f] = r;
        }

        int nPairs = pairs.size();
        int nEwl = 0, nWl = 0, nNl = 0;
        for (int[] pr : pairs.values()) {
            if (nf >= 3 && pr[1] >= 0 && pr[2] >= 0) nEwl++;
            if (pr[0] >= 0 && pr[1] >= 0) nWl++;
            if (pr[0] >= 0) nNl++;
        }
        if (nNl < opt.minfixsats - 1) {
            LOG.debug("CascadeAR skipped: NL pairs {} < minfixsats-1 {}", nNl, opt.minfixsats - 1);
            return -1;
        }

        // ---- 5. 构建各级组合行（卫星对 → 级内行号双向索引） ----
        int[] ewlR2 = new int[nEwl];
        int[] ewlR5 = new int[nEwl];
        int[] wlR1 = new int[nWl];
        int[] wlR2 = new int[nWl];
        int[] nlR1 = new int[nNl];
        int[] pairEwl = new int[nPairs];
        int[] pairWl = new int[nPairs];
        int[] pairNl = new int[nPairs];
        int iE = 0, iW = 0, iN = 0, p = 0;
        for (int[] pr : pairs.values()) {
            pairEwl[p] = -1;
            pairWl[p] = -1;
            pairNl[p] = -1;
            if (nf >= 3 && pr[1] >= 0 && pr[2] >= 0) {
                ewlR2[iE] = pr[1];
                ewlR5[iE] = pr[2];
                pairEwl[p] = iE++;
            }
            if (pr[0] >= 0 && pr[1] >= 0) {
                wlR1[iW] = pr[0];
                wlR2[iW] = pr[1];
                pairWl[p] = iW++;
            }
            if (pr[0] >= 0) {
                nlR1[iN] = pr[0];
                pairNl[p] = iN++;
            }
            p++;
        }

        double[] bEwl = new double[Math.max(nEwl, 1) * 2];
        double[] bWl = new double[Math.max(nWl, 1) * 2];
        double[] bNl = new double[Math.max(nNl, 1) * 2];
        double[] ratioOut = new double[1];

        // ---- 6a. EWL 级（nf>=3，最长波长最先固定） ----
        if (nEwl > 0) {
            SimpleMatrix CEwl = new SimpleMatrix(nEwl, nbLambda);
            for (int i = 0; i < nEwl; i++) {
                CEwl.set(i, ewlR2[i], 1.0);
                CEwl.set(i, ewlR5[i], -1.0);
            }
            int rc = fixLevel(CEwl, y, Q, cfg.cascadeArRatioEwl, bEwl, ratioOut);
            rtk.cascadeArLastRatio[0] = ratioOut[0];
            if (rc != 0) {
                LOG.debug("CascadeAR EWL failed: rc={} ratio={} thresh={}", rc, ratioOut[0], cfg.cascadeArRatioEwl);
                return -1;
            }
            rtk.diagCascadeArEwlFixCount++;
            LOG.debug("CascadeAR EWL fixed: nb={} ratio={}", nEwl, String.format("%.2f", ratioOut[0]));
        }

        // ---- 6b. WL 级（条件于 EWL 固定值） ----
        if (nWl > 0) {
            SimpleMatrix CWl = new SimpleMatrix(nWl, nbLambda);
            for (int i = 0; i < nWl; i++) {
                CWl.set(i, wlR1[i], 1.0);
                CWl.set(i, wlR2[i], -1.0);
            }
            int rc = fixLevel(CWl, y, Q, cfg.cascadeArRatioWl, bWl, ratioOut);
            rtk.cascadeArLastRatio[1] = ratioOut[0];
            if (rc != 0) {
                LOG.debug("CascadeAR WL failed: rc={} ratio={} thresh={}", rc, ratioOut[0], cfg.cascadeArRatioWl);
                return -1;
            }
            rtk.diagCascadeArWlFixCount++;
            LOG.debug("CascadeAR WL fixed: nb={} ratio={}", nWl, String.format("%.2f", ratioOut[0]));
        }

        // ---- 6c. NL 级（条件于 EWL/WL 的 f1 双差模糊度，最终固定级） ----
        double[] zNl = new double[nNl];
        double[] QNl = new double[nNl * nNl];
        for (int i = 0; i < nNl; i++) {
            zNl[i] = y[nlR1[i]];
            for (int j = 0; j < nNl; j++) {
                QNl[i * nNl + j] = Q.get(nlR1[i], nlR1[j]);
            }
        }
        symmetrizeInPlace(QNl, nNl);

        double[] s = new double[2];
        int info = Lambda.lambda(nNl, 2, zNl, QNl, bNl, s);
        if (info != 0 || s[0] <= 0.0) {
            LOG.debug("CascadeAR NL lambda failed: info={}", info);
            return -1;
        }

        double nlRatio = s[1] / s[0];
        rtk.cascadeArLastRatio[2] = nlRatio;
        rtk.sol.ratio = (float) nlRatio;
        if (rtk.sol.ratio > 999.9f) rtk.sol.ratio = 999.9f;

        // 动态 ratio 门限（对齐标准路径 C rtkpos.c resamb_LAMBDA 多项式调整），
        // cfg.cascadeArRatioNl 作为下限（修复旧实现固定阈值缺陷）
        float dynThres;
        if (opt.thresar[5] != opt.thresar[6]) {
            int nb1 = nNl < 50 ? nNl : 50; /* poly only fitted for upto 50 sat pairs */
            double[] coeff = new double[3];
            for (int i = 0; i < 3; i++) {
                coeff[i] = RtkCore.AR_POLY_COEFFS[i][0];
                for (int j = 1; j < 5; j++) {
                    coeff[i] = coeff[i] * opt.thresar[0] + RtkCore.AR_POLY_COEFFS[i][j];
                }
            }
            dynThres = (float) coeff[0];
            for (int i = 1; i < 3; i++) {
                dynThres = (float) (dynThres * 1.0 / (nb1 + 1.0) + coeff[i]);
            }
            dynThres = (float) Math.min(Math.max(dynThres, opt.thresar[5]), opt.thresar[6]);
        } else {
            dynThres = (float) opt.thresar[0];
        }
        rtk.sol.thres = dynThres;

        double nlThres = Math.max(dynThres, cfg.cascadeArRatioNl);
        if (nlRatio < nlThres) {
            LOG.debug("CascadeAR NL failed: ratio={} < thresh={}", String.format("%.2f", nlRatio), String.format("%.2f", nlThres));
            return -1;
        }

        if (cfg.cascadeArBootstrapping && cfg.enableBootstrapping) {
            if (!RtkOptimizationsBootstrap.validateFix(rtk, bNl, nNl)) {
                LOG.debug("CascadeAR NL Bootstrapping rejected");
                rtk.sol.ratio = 0.0f;
                rtk.diagBootstrapRejectCount++;
                return -1;
            }
        }
        rtk.diagCascadeArNlFixCount++;

        // ---- 7. 整数反推：N1=b_NL、N2=N1−b_WL、N5=N2−b_EWL；不可成链行降级为 float ----
        // 按 DD 全行索引（freeMap 逆映射），供消费序 bias 构建与条件更新共用
        double[] bRowFull = new double[nb];
        boolean[] fixedRowFull = new boolean[nb];
        int pIdx = 0;
        for (int[] pr : pairs.values()) {
            int e = pairEwl[pIdx];
            int w = pairWl[pIdx];
            int n = pairNl[pIdx];
            int rowF1 = pr[0] >= 0 ? freeMap[pr[0]] : -1;
            int rowF2 = pr[1] >= 0 ? freeMap[pr[1]] : -1;
            int rowF5 = pr[2] >= 0 ? freeMap[pr[2]] : -1;
            boolean hasN1 = rowF1 >= 0;                   // NL 行覆盖所有 f1 自由行
            boolean hasN2 = rowF1 >= 0 && rowF2 >= 0;     // WL 需 f1+f2 自由行同时存在
            boolean hasN5 = rowF2 >= 0 && rowF5 >= 0;     // EWL 反推需完整链 f1+f2+f5
            double n1 = 0.0, n2v = 0.0;
            if (hasN1) {
                n1 = bNl[n * 2];
                bRowFull[rowF1] = n1;
                fixedRowFull[rowF1] = true;
            }
            if (hasN2) {
                n2v = n1 - bWl[w * 2];
                bRowFull[rowF2] = n2v;
                fixedRowFull[rowF2] = true;
            }
            if (hasN5) {
                bRowFull[rowF5] = n2v - bEwl[e * 2];
                fixedRowFull[rowF5] = true;
            }
            if (rowF2 >= 0 && !hasN2) {
                LOG.debug("CascadeAR downgrade f2: epoch={} sat={} (WL chain broken)",
                        rtk.epoch, (ixUsed[pr[1] * 2 + 1] - na) % Constants.MAXSAT + 1);
                downgradeRow(rtk, ixUsed[pr[1] * 2 + 1], na, nf);
            }
            if (rowF5 >= 0 && !hasN5) {
                LOG.debug("CascadeAR downgrade f5: epoch={} sat={} (EWL chain broken)",
                        rtk.epoch, (ixUsed[pr[2] * 2 + 1] - na) % Constants.MAXSAT + 1);
                downgradeRow(rtk, ixUsed[pr[2] * 2 + 1], na, nf);
            }
            pIdx++;
        }

        // ---- 8. 仅对已固定行执行条件更新（与标准路径数学形式一致） ----
        int nFx = 0;
        for (int r = 0; r < nbLambda; r++) {
            if (fixedRowFull[freeMap[r]]) nFx++;
        }
        if (nFx < 1) return -1;

        int[] fxRows = new int[nFx];
        int iFx = 0;
        for (int r = 0; r < nbLambda; r++) {
            if (fixedRowFull[freeMap[r]]) fxRows[iFx++] = freeMap[r];
        }

        SimpleMatrix D2 = new SimpleMatrix(nFx, nAmb);
        for (int i = 0; i < nFx; i++) {
            D2.set(i, ix[fxRows[i] * 2] - na, 1.0);
            D2.set(i, ix[fxRows[i] * 2 + 1] - na, -1.0);
        }
        SimpleMatrix Qb2Mat = symmetrize(MatrixUtil.multiply(MatrixUtil.multiply(D2, QcMat), MatrixUtil.transpose(D2)));
        SimpleMatrix Qab2Mat = MatrixUtil.multiply(QacMat, MatrixUtil.transpose(D2));

        for (int i = 0; i < na; i++) {
            rtk.xa[i] = rtk.x[i];
            for (int j = 0; j < na; j++) {
                rtk.Pa[i * na + j] = rtk.P[i * nx + j];
            }
        }

        double[] y2 = new double[nFx];
        for (int i = 0; i < nFx; i++) {
            y2[i] = yFull[fxRows[i]] - bRowFull[fxRows[i]];
        }

        Optional<SimpleMatrix> Qb2InvOpt = MatrixUtil.invertSafe(Qb2Mat);
        if (!Qb2InvOpt.isPresent()) {
            return 0;   // 对齐标准路径：求逆失败 → nb=0
        }
        SimpleMatrix Qb2Inv = Qb2InvOpt.get();
        SimpleMatrix y2Mat = MatrixUtil.createMatrix(y2, nFx, 1);
        SimpleMatrix dbMat = MatrixUtil.multiply(Qb2Inv, y2Mat);
        SimpleMatrix xaMat = MatrixUtil.createMatrix(rtk.xa, na, 1);
        SimpleMatrix xaNew = MatrixUtil.subtract(xaMat, MatrixUtil.multiply(Qab2Mat, dbMat));
        MatrixUtil.copyMatrix(xaNew, rtk.xa);

        SimpleMatrix QQMat = MatrixUtil.multiply(Qab2Mat, Qb2Inv);
        SimpleMatrix PaMat = MatrixUtil.createMatrix(rtk.Pa, na, na);
        SimpleMatrix PaNew = MatrixUtil.subtract(PaMat, MatrixUtil.multiply(QQMat, MatrixUtil.transpose(Qab2Mat)));
        MatrixUtil.copyMatrix(PaNew, rtk.Pa);

        // ---- 8.5 整数-浮点一致性诊断：固定整数与原始浮点 DD 的标准化残差 ----
        // 合法固定应满足 |y0−b| ≲ 1 周（最近整数）且 z 不显著超出若干 σ；
        // maxD（绝对差，周）定性区分"半周偏差吸收"（<0.5）与"组合污染"（≫1），
        // z（σ 归一化）反映滤波器收敛后的统计一致性。
        double maxZ = 0.0, maxD = 0.0, sigAtMax = 0.0;
        double sumZ2 = 0.0;
        for (int i = 0; i < nFx; i++) {
            int row = fxRows[i];
            double var = QbMat.get(row, row);
            double d = Math.abs(yFull[row] - bRowFull[row]);
            if (d > maxD) {
                maxD = d;
                sigAtMax = var > 0.0 ? Math.sqrt(var) : 0.0;
            }
            if (var <= 0.0) continue;
            double z = d / Math.sqrt(var);
            sumZ2 += z * z;
            if (z > maxZ) maxZ = z;
        }
        rtk.diagCascadeArMaxZ = maxZ;
        rtk.diagCascadeArRmsZ = nFx > 0 ? Math.sqrt(sumZ2 / nFx) : 0.0;
        rtk.diagCascadeArMaxD = maxD;
        LOG.info("CascadeAR fixed: epoch={} nb={} nFx={} nNl={} nWl={} maxD={} sigMaxD={} maxZ={} rmsZ={} ratio={}",
                rtk.epoch, nb, nFx, nNl, nWl, String.format("%.3f", maxD),
                String.format("%.4f", sigAtMax), String.format("%.2f", maxZ),
                String.format("%.2f", rtk.diagCascadeArRmsZ), String.format("%.2f", nlRatio));

        // ---- 9. bias 按 restamb 消费序构建 + restamb 恢复单差模糊度 ----
        // restamb 按 (m,f,sat升序) 遍历 fix==2 行并跳过每组参考行消费 bias[]；
        // 降级行（fix=1）不占消费槽位，故 bias 必须按消费序紧凑填充（修复消费错位）。
        // 锚定行（fix==2）以 round(float) 参与，与标准路径一致。
        for (int i = 0; i < anchorCount; i++) {
            int row = anchorMap[i];
            bRowFull[row] = Math.round(yFull[row]);
        }
        java.util.Map<Integer, Integer> rovRowOfState = new java.util.HashMap<>();
        for (int r = 0; r < nb; r++) {
            rovRowOfState.put(ix[r * 2 + 1], r);
        }
        int nvBias = 0;
        for (int m = 0; m < 6; m++) {
            for (int f = 0; f < nf; f++) {
                int nGrp = 0;
                for (int i = 0; i < Constants.MAXSAT; i++) {
                    if (!RtkCore.testSys(rtk.ssat[i].sys, m) || rtk.ssat[i].fix[f] != 2) continue;
                    if (nGrp == 0) {
                        nGrp++;   // 组内首个 fix==2 卫星 = ddidx 参考星，保留 float 不消费
                        continue;
                    }
                    Integer row = rovRowOfState.get(na + f * Constants.MAXSAT + i);
                    if (row == null) continue;   // 不可达：fix==2 的 rov 卫星必有 DD 行
                    bias[nvBias++] = bRowFull[row];
                }
            }
        }

        RtkCore.restamb(rtk, bias, nb, xa);

        // ---- 10. 诊断标记 ----
        for (int i = 0; i < nbLambda; i++) {
            if (!fixedRowFull[freeMap[i]]) continue;
            int ambIdx = ixUsed[i * 2 + 1] - na;
            int satIdx = ambIdx % Constants.MAXSAT;
            int f = ambIdx / Constants.MAXSAT;
            if (satIdx >= 0 && satIdx < Constants.MAXSAT && f >= 0 && f < Constants.NFREQ) {
                int globalIdx = satIdx * nf + f;
                rtk.cascadeArFixLevel[globalIdx] = (f == 0) ? LEVEL_NL : (f == 1) ? LEVEL_WL : LEVEL_EWL;
            }
        }

        return nb;
    }

    /**
     * 单级组合固定：z=C·y、Qz=C·Q·Cᵀ → LAMBDA → ratio 门限 → 条件更新。
     *
     * @param C        组合矩阵 [nL × nbF]
     * @param y        浮点 DD 模糊度 [nbF]，成功时被条件更新覆盖
     * @param Q        浮点协方差 [nbF × nbF]，成功时被条件更新覆盖
     * @param thresh   ratio 门限
     * @param bOut     LAMBDA 固定解 [nL*2]（输出）
     * @param ratioOut ratio（输出）
     * @return 0 成功；-1 LAMBDA 失败/求逆失败；1 ratio 未过门限
     */
    private static int fixLevel(SimpleMatrix C, double[] y, SimpleMatrix Q,
                                double thresh, double[] bOut, double[] ratioOut) {
        int nL = C.numRows();
        int nbF = C.numCols();

        SimpleMatrix zMat = MatrixUtil.multiply(C, MatrixUtil.createMatrix(y, nbF, 1));
        SimpleMatrix QzMat = symmetrize(MatrixUtil.multiply(MatrixUtil.multiply(C, Q), MatrixUtil.transpose(C)));

        double[] z = new double[nL];
        MatrixUtil.copyMatrix(zMat, z);
        double[] Qz = new double[nL * nL];
        MatrixUtil.copyMatrix(QzMat, Qz);

        double[] s = new double[2];
        int info = Lambda.lambda(nL, 2, z, Qz, bOut, s);
        if (info != 0 || s[0] <= 0.0) return -1;

        double ratio = s[1] / s[0];
        ratioOut[0] = ratio;
        if (ratio < thresh) return 1;

        Optional<SimpleMatrix> QzInvOpt = MatrixUtil.invertSafe(QzMat);
        if (!QzInvOpt.isPresent()) return -1;
        SimpleMatrix QzInv = QzInvOpt.get();

        // 条件更新：K = Q·Cᵀ·Qz⁻¹；y ← y − K·(z−b)；Q ← Q − K·C·Q
        SimpleMatrix K = MatrixUtil.multiply(MatrixUtil.multiply(Q, MatrixUtil.transpose(C)), QzInv);
        double[] bVec = new double[nL];
        for (int i = 0; i < nL; i++) {
            bVec[i] = bOut[i * 2];
        }
        SimpleMatrix vMat = MatrixUtil.subtract(zMat, MatrixUtil.createMatrix(bVec, nL, 1));
        SimpleMatrix yNew = MatrixUtil.subtract(MatrixUtil.createMatrix(y, nbF, 1), MatrixUtil.multiply(K, vMat));
        MatrixUtil.copyMatrix(yNew, y);

        SimpleMatrix QNew = MatrixUtil.subtract(Q, MatrixUtil.multiply(K, MatrixUtil.multiply(C, Q)));
        for (int i = 0; i < nbF; i++) {
            for (int j = 0; j < nbF; j++) {
                Q.set(i, j, QNew.get(i, j));
            }
        }
        return 0;
    }

    /**
     * 将不可成链固定的 DD 行降级为 float（fix=1），保证 restamb 按 fix==2
     * 消费 bias[] 的顺序与数量一致。
     */
    private static void downgradeRow(Rtk rtk, int rovStateIdx, int na, int nf) {
        int ambIdx = rovStateIdx - na;
        int f = ambIdx / Constants.MAXSAT;
        int sat = ambIdx % Constants.MAXSAT;
        if (sat >= 0 && sat < Constants.MAXSAT && f >= 0 && f < nf) {
            rtk.ssat[sat].fix[f] = 1;
        }
    }

    private static SimpleMatrix symmetrize(SimpleMatrix m) {
        int n = m.numRows();
        SimpleMatrix out = new SimpleMatrix(n, n);
        for (int i = 0; i < n; i++) {
            out.set(i, i, m.get(i, i));
            for (int j = i + 1; j < n; j++) {
                double avg = 0.5 * (m.get(i, j) + m.get(j, i));
                out.set(i, j, avg);
                out.set(j, i, avg);
            }
        }
        return out;
    }

    private static void symmetrizeInPlace(double[] Q, int n) {
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                double avg = 0.5 * (Q[i * n + j] + Q[j * n + i]);
                Q[i * n + j] = avg;
                Q[j * n + i] = avg;
            }
        }
    }
}
