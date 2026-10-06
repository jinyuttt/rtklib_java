package org.rtklib.java.research.ambiguity;

import org.ejml.simple.SimpleMatrix;
import org.rtklib.java.research.common.MatrixOps;

/**
 * LAMBDA/MLAMBDA 模糊度固定（research模块自有）。
 *
 * <p>实现 LAMBDA（Least-squares AMBiguity Decorrelation Adjustment）算法：
 * 1. LDLᵀ 分解 + 整数高斯变换去相关
 * 2. MLAMBDA 树搜索（支持 n≥1 的全整数最小二乘搜索）
 * 3. Ratio 检验</p>
 *
 * <p>去相关 → Zᵀ 变换 → 搜索 → Z⁻ᵀ 逆变换。</p>
 */
public final class LambdaSolver {

    private static final double CHI2_FACTOR = 9999.0;

    private LambdaSolver() {}

    public static LambdaResult solve(SimpleMatrix floatAmb, SimpleMatrix ambCov, int numCandidates) {
        LambdaResult result = new LambdaResult();
        int n = MatrixOps.rows(floatAmb);

        if (n == 0) {
            result.success = false;
            return result;
        }

        SimpleMatrix L = SimpleMatrix.identity(n);
        SimpleMatrix D = new SimpleMatrix(n, 1);
        SimpleMatrix Z = SimpleMatrix.identity(n);

        for (int i = 0; i < n; i++) {
            D.set(i, 0, ambCov.get(i, i));
        }

        decorrelation(ambCov, L, D, Z);

        SimpleMatrix zFloat = Z.transpose().mult(floatAmb);

        result.floatAmbiguity = floatAmb.copy();
        result.covariance = ambCov.copy();
        result.transformMatrix = Z.copy();
        result.success = true;
        result.numAmbiguities = n;

        SimpleMatrix fixedDecorrelated = mlambdaSearch(zFloat, L, D, numCandidates, result);
        result.fixedAmbiguity = Z.invert().transpose().mult(fixedDecorrelated);

        computeRatio(result, floatAmb, ambCov);

        return result;
    }

    private static void decorrelation(SimpleMatrix Q, SimpleMatrix L, SimpleMatrix D, SimpleMatrix Z) {
        int n = MatrixOps.rows(Q);
        int loopCount = 0;
        int maxLoop = 1000;

        while (loopCount < maxLoop) {
            boolean swapped = false;
            for (int j = n - 1; j > 0; j--) {
                for (int i = j - 1; i >= 0; i--) {
                    double delta = Math.round(L.get(j, i));
                    if (Math.abs(delta) > 0.5) {
                        for (int k = 0; k < n; k++) {
                            Z.set(i, k, Z.get(i, k) - (int) delta * Z.get(j, k));
                        }
                        for (int k = 0; k <= i; k++) {
                            L.set(j, k, L.get(j, k) - delta * L.get(i, k));
                        }
                    }
                }
            }

            for (int j = 0; j < n - 1; j++) {
                double dj = D.get(j, 0);
                double dj1 = D.get(j + 1, 0);
                double lj = L.get(j + 1, j);
                double delta = dj + lj * lj * dj1;
                if (dj > delta) {
                    double a = dj1 / delta;
                    double b = lj * dj1 / delta;
                    dj1 = dj * dj1 / delta;
                    dj = delta;

                    D.set(j, 0, dj);
                    D.set(j + 1, 0, dj1);
                    L.set(j + 1, j, b);

                    for (int k = 0; k < j; k++) {
                        double e = L.get(j, k);
                        L.set(j, k, L.get(j + 1, k));
                        L.set(j + 1, k, e);
                    }
                    for (int ii = j + 2; ii < n; ii++) {
                        double e = L.get(ii, j);
                        L.set(ii, j, a * L.get(ii, j) + b * L.get(ii, j + 1));
                        L.set(ii, j + 1, -lj * L.get(ii, j) + L.get(ii, j + 1));
                    }
                    for (int k = 0; k < n; k++) {
                        double e = Z.get(j, k);
                        Z.set(j, k, Z.get(j + 1, k));
                        Z.set(j + 1, k, e);
                    }
                    swapped = true;
                }
            }

            loopCount++;
            if (!swapped) break;
        }
    }

    /**
     * MLAMBDA 树搜索：在去相关空间中搜索整数最小二乘解。
     *
     * <p>代价函数: F(z) = ∑_{i=0}^{n-1} dᵢ·(zᵢ - ẑᵢ + ∑_{j=i+1}^{n-1} lⱼᵢ·(zⱼ - ẑⱼ))²
     *
     * <p>从第 n-1 层向第 0 层递归搜索，收集 numCandidates 个候选。
     */
    private static SimpleMatrix mlambdaSearch(SimpleMatrix zFloat, SimpleMatrix L, SimpleMatrix D,
                                               int numCandidates, LambdaResult result) {
        int n = zFloat.getNumElements();

        double[] zFlat = new double[n];
        for (int i = 0; i < n; i++) zFlat[i] = zFloat.get(i, 0);

        double[] Ddiag = new double[n];
        for (int i = 0; i < n; i++) Ddiag[i] = D.get(i, 0);

        SearchContext ctx = new SearchContext(n, numCandidates);
        ctx.zFloat = zFlat;
        ctx.L = extractLMatrix(L, n);
        ctx.D = Ddiag;

        ctx.startSearch();

        if (ctx.bestCost == Double.MAX_VALUE) {
            SimpleMatrix fallback = new SimpleMatrix(n, 1);
            for (int i = 0; i < n; i++) {
                fallback.set(i, 0, Math.round(zFlat[i]));
            }
            return fallback;
        }

        result.ratio = computeRatioFromSearch(ctx);
        result.fixed = result.ratio < 0.333;

        SimpleMatrix best = new SimpleMatrix(n, 1);
        for (int i = 0; i < n; i++) {
            best.set(i, 0, ctx.bestSolution[i]);
        }
        return best;
    }

    private static double[][] extractLMatrix(SimpleMatrix L, int n) {
        double[][] l = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                l[i][j] = L.get(i, j);
            }
        }
        return l;
    }

    private static double computeRatioFromSearch(SearchContext ctx) {
        double sumFixed = ctx.bestCost;
        double best2 = ctx.secondBestCost;
        if (best2 <= 0 || sumFixed <= 0) return 999.0;
        return sumFixed / best2;
    }

    private static void computeRatio(LambdaResult result, SimpleMatrix floatAmb, SimpleMatrix ambCov) {
        int n = result.numAmbiguities;
        SimpleMatrix diff = floatAmb.minus(result.fixedAmbiguity);
        SimpleMatrix Qinv = ambCov.invert();
        SimpleMatrix dT = diff.transpose();
        double sq1 = dT.mult(Qinv).mult(diff).get(0, 0);

        SimpleMatrix secondBest = new SimpleMatrix(n, 1);
        for (int i = 0; i < n; i++) {
            double fi = result.fixedAmbiguity.get(i, 0);
            double fl = floatAmb.get(i, 0);
            secondBest.set(i, 0, fl - fi > 0 ? fi - 1 : fi + 1);
        }
        SimpleMatrix diff2 = floatAmb.minus(secondBest);
        double sq2 = diff2.transpose().mult(Qinv).mult(diff2).get(0, 0);

        result.ratio = sq2 > 0 ? sq1 / sq2 : 0.0;
        result.fixed = result.ratio < 1.0 / 3.0;
    }

    /**
     * MLAMBDA 树搜索上下文。
     *
     * <p>状态变量：
     * <ul>
     *   <li>zᵢ: 当前搜索的整数候选值（第 i 层）
     *   <li>sᵢ: 从第 i 层到第 n-1 层的累计代价
     *   <li>z̄ᵢ: 第 i 层的条件浮点值（给定上层已固定的整数后）
     * </ul>
     * 
     * <p>搜索从 i=n-1 开始，逐层向下展开，利用 LᵀDL 对角结构剪枝。
     */
    private static class SearchContext {
        final int n;
        final int maxCandidates;
        final int[] zBest;
        double[] zFloat;
        double[][] L;
        double[] D;

        double bestCost;
        double secondBestCost;
        int[] bestSolution;

        int[] zCurr;
        double[] zCond;
        double[] sAccum;

        SearchContext(int n, int maxCandidates) {
            this.n = n;
            this.maxCandidates = Math.max(maxCandidates, 2);
            this.zBest = new int[n];
            this.zCurr = new int[n];
            this.zCond = new double[n];
            this.sAccum = new double[n];
            this.bestCost = Double.MAX_VALUE;
            this.secondBestCost = Double.MAX_VALUE;
        }

        void startSearch() {
            double chi2 = CHI2_FACTOR * n;
            searchLevel(n - 1, 0.0, chi2);
        }

        /**
         * 递归搜索第 level 层。
         *
         * @param level    当前搜索层级（0 到 n-1）
         * @param costBelow 下层已累计的代价 ∑_{j=level+1}^{n-1} dⱼ·(zⱼ - z̄ⱼ)²
         * @param remaining 剩余可用的代价预算
         */
        void searchLevel(int level, double costBelow, double remaining) {
            if (level < 0) {
                double totalCost = costBelow;
                if (totalCost < bestCost) {
                    secondBestCost = bestCost;
                    bestCost = totalCost;
                    bestSolution = zCurr.clone();
                } else if (totalCost < secondBestCost && totalCost > bestCost + 1e-12) {
                    secondBestCost = totalCost;
                }
                return;
            }

            double zBar = zFloat[level];
            for (int j = level + 1; j < n; j++) {
                zBar -= L[j][level] * (zCurr[j] - zFloat[j]);
            }
            zCond[level] = zBar;

            double d = D[level];
            double step = Math.sqrt(Math.max(remaining / d, 0.0));

            int lo = (int) Math.ceil(zBar - step);
            int hi = (int) Math.floor(zBar + step);

            for (int candidate = lo; candidate <= hi; candidate++) {
                double delta = candidate - zBar;
                double newCost = costBelow + d * delta * delta;

                if (newCost >= bestCost) continue;

                zCurr[level] = candidate;
                sAccum[level] = newCost;

                double newRemaining = remaining - d * delta * delta;
                searchLevel(level - 1, newCost, Math.max(newRemaining, 0.0));

                if (bestCost < Double.MAX_VALUE) {
                    double bestDelta = bestCost - costBelow;
                    remaining = Math.min(remaining, bestDelta);
                }
            }
        }
    }

    public static class LambdaResult {
        public boolean success;
        public boolean fixed;
        public int numAmbiguities;
        public SimpleMatrix floatAmbiguity;
        public SimpleMatrix fixedAmbiguity;
        public SimpleMatrix covariance;
        public SimpleMatrix transformMatrix;
        public double ratio;

        @Override
        public String toString() {
            return String.format("Lambda[n=%d, fixed=%b, ratio=%.3f]", numAmbiguities, fixed, ratio);
        }
    }
}