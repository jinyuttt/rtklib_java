package org.rtklib.java.research.ambiguity;

import org.ejml.simple.SimpleMatrix;

/**
 * IACE（整数模糊度聚类估计）结果。
 *
 * <p>与 LAMBDA 的 LambdaResult 对应，提供聚类评分为替代 Ratio 检验。</p>
 */
public class IaceResult {
    public boolean success;
    public SimpleMatrix fixedAmbiguities;
    public double clusterScore;
    public int numSatellites;
    public int numSatellitesFixed;
    public int bestClusterSize;

    public static IaceResult fail() {
        IaceResult r = new IaceResult();
        r.success = false;
        return r;
    }

    public static IaceResult success(SimpleMatrix fixed, double score, int nSat, int nFixed, int clusterSize) {
        IaceResult r = new IaceResult();
        r.success = true;
        r.fixedAmbiguities = fixed;
        r.clusterScore = score;
        r.numSatellites = nSat;
        r.numSatellitesFixed = nFixed;
        r.bestClusterSize = clusterSize;
        return r;
    }

    @Override
    public String toString() {
        return String.format("IaceResult[ok=%b, fixed=%d/%d, score=%.3f]",
                success, numSatellitesFixed, numSatellites, clusterScore);
    }
}