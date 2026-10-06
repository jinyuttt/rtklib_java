package org.rtklib.java.research.ambiguity;

import java.util.ArrayList;
import java.util.List;
import org.ejml.simple.SimpleMatrix;
import org.rtklib.java.research.common.MatrixOps;

/**
 * Partial AR（部分模糊度固定）（research模块自有）。
 *
 * <p>按多因子加权得分排序，逐步固定子集模糊度。
 * 支持传统单因子（高度角/方差）和综合多因子（高度角+SNR+相位残差+方差）两种模式。</p>
 */
public class PartialArSolver {
    private final double ratioThreshold;
    private final int minAmbiguities;

    private double wElev = 0.35;
    private double wSnr = 0.25;
    private double wResidual = 0.15;
    private double wVariance = 0.25;

    public PartialArSolver() {
        this(3.0, 4);
    }

    public PartialArSolver(double ratioThreshold, int minAmbiguities) {
        this.ratioThreshold = ratioThreshold;
        this.minAmbiguities = minAmbiguities;
    }

    public void setMultiFactorWeights(double wElev, double wSnr, double wResidual, double wVariance) {
        this.wElev = wElev;
        this.wSnr = wSnr;
        this.wResidual = wResidual;
        this.wVariance = wVariance;
    }

    /**
     * 传统单因子排序（高度角优先，退化为方差）。
     */
    public PartialArResult solve(SimpleMatrix floatAmb, SimpleMatrix ambCov, double[] elevations) {
        return solveMultiFactor(floatAmb, ambCov, elevations, null, null);
    }

    /**
     * 多因子加权排序。
     *
     * <p>score_i = wElev·norm(el) + wSnr·norm(snr) + wRes·norm(-|res|) + wVar·norm(-var)
     *
     * @param floatAmb        浮点模糊度 (n×1)
     * @param ambCov          模糊度协方差 (n×n)
     * @param elevations      高度角 (deg)，可为 null
     * @param snrValues       SNR (dBHz)，可为 null
     * @param phaseResiduals  相位残差 (m)，可为 null
     * @return PartialArResult
     */
    public PartialArResult solveMultiFactor(SimpleMatrix floatAmb, SimpleMatrix ambCov,
                                             double[] elevations, double[] snrValues,
                                             double[] phaseResiduals) {
        PartialArResult result = new PartialArResult();
        int n = MatrixOps.rows(floatAmb);
        result.totalAmbiguities = n;

        boolean hasMultiFactor = elevations != null && snrValues != null && phaseResiduals != null;

        List<Integer> order;
        if (hasMultiFactor) {
            order = rankByMultiFactor(n, elevations, snrValues, phaseResiduals, ambCov);
        } else if (elevations != null && elevations.length == n) {
            order = rankByElevation(n, elevations);
        } else {
            order = rankByVariance(n, ambCov);
        }

        result.fixedIndices = new ArrayList<>();
        result.excludedIndices = new ArrayList<>();

        for (int k = n; k >= minAmbiguities; k--) {
            List<Integer> subset = order.subList(0, k);
            SimpleMatrix subAmb = extractSubset(floatAmb, subset);
            SimpleMatrix subCov = extractSubmatrix(ambCov, subset);

            LambdaSolver.LambdaResult lambdaResult = LambdaSolver.solve(subAmb, subCov, 2);

            if (lambdaResult.fixed && lambdaResult.ratio < 1.0 / ratioThreshold) {
                result.fixedIndices = new ArrayList<>(subset);
                result.fixedAmbiguity = lambdaResult.fixedAmbiguity;
                result.ratio = lambdaResult.ratio;
                result.success = true;

                for (int i = 0; i < n; i++) {
                    if (!result.fixedIndices.contains(i)) {
                        result.excludedIndices.add(i);
                    }
                }
                return result;
            }
        }

        result.success = false;
        result.excludedIndices = new ArrayList<>(order);
        return result;
    }

    private List<Integer> rankByMultiFactor(int n, double[] el, double[] snr,
                                             double[] res, SimpleMatrix cov) {
        double maxEl = max(el, n);
        double maxSnr = max(snr, n);
        double maxAbsRes = maxAbs(res, n);
        double maxVar = 0;
        for (int i = 0; i < n; i++) maxVar = Math.max(maxVar, cov.get(i, i));

        double[] scores = new double[n];
        for (int i = 0; i < n; i++) {
            double sEl = maxEl > 0 ? el[i] / maxEl : 0;
            double sSnr = maxSnr > 0 ? snr[i] / maxSnr : 0;
            double sRes = maxAbsRes > 0 ? (1.0 - Math.abs(res[i]) / maxAbsRes) : 0;
            double sVar = maxVar > 0 ? (1.0 - cov.get(i, i) / maxVar) : 0;
            scores[i] = wElev * sEl + wSnr * sSnr + wResidual * sRes + wVariance * sVar;
        }

        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) order.add(i);
        order.sort((a, b) -> Double.compare(scores[b], scores[a]));
        return order;
    }

    private List<Integer> rankByElevation(int n, double[] el) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) order.add(i);
        order.sort((a, b) -> Double.compare(el[b], el[a]));
        return order;
    }

    private List<Integer> rankByVariance(int n, SimpleMatrix cov) {
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < n; i++) order.add(i);
        order.sort((a, b) -> Double.compare(cov.get(a, a), cov.get(b, b)));
        return order;
    }

    private double max(double[] arr, int n) {
        double m = 0;
        for (int i = 0; i < n; i++) m = Math.max(m, arr[i]);
        return m;
    }

    private double maxAbs(double[] arr, int n) {
        double m = 0;
        for (int i = 0; i < n; i++) m = Math.max(m, Math.abs(arr[i]));
        return m;
    }

    private SimpleMatrix extractSubset(SimpleMatrix v, List<Integer> indices) {
        SimpleMatrix result = new SimpleMatrix(indices.size(), 1);
        for (int i = 0; i < indices.size(); i++) {
            result.set(i, 0, v.get(indices.get(i), 0));
        }
        return result;
    }

    private SimpleMatrix extractSubmatrix(SimpleMatrix M, List<Integer> indices) {
        int n = indices.size();
        SimpleMatrix result = new SimpleMatrix(n, n);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                result.set(i, j, M.get(indices.get(i), indices.get(j)));
            }
        }
        return result;
    }

    public static class PartialArResult {
        public boolean success;
        public int totalAmbiguities;
        public List<Integer> fixedIndices;
        public List<Integer> excludedIndices;
        public SimpleMatrix fixedAmbiguity;
        public double ratio;

        @Override
        public String toString() {
            return String.format("PartialAR[success=%b, fixed=%d/%d, ratio=%.3f]",
                    success, fixedIndices != null ? fixedIndices.size() : 0, totalAmbiguities, ratio);
        }
    }
}