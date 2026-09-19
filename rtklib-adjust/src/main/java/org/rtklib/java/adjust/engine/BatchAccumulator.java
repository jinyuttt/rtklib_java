package org.rtklib.java.adjust.engine;

import org.rtklib.java.adjust.model.AdjustResult;
import org.rtklib.java.adjust.model.ReliabilityGrade;

public class BatchAccumulator {

    private final int windowSize;
    private final double[] xSum;
    private final double[] ySum;
    private final double[] zSum;
    private final double[] wSum;
    private int count;
    private int verifiedCount;
    private int crossCheckedCount;
    private int suspectCount;
    private double plSum;
    private double crossValSum;
    private String windowStartTag;
    private String windowEndTag;

    public BatchAccumulator(int windowSize) {
        this.windowSize = windowSize;
        this.xSum = new double[3];
        this.ySum = new double[3];
        this.zSum = new double[3];
        this.wSum = new double[3];
        reset();
    }

    public void feed(AdjustResult result) {
        if (result == null || !result.success || result.p01Xyz == null) {
            return;
        }

        if (count == 0) {
            windowStartTag = result.epochTag;
        }
        windowEndTag = result.epochTag;

        double wN = computeWeight(result, 0);
        double wE = computeWeight(result, 1);
        double wU = computeWeight(result, 2);

        xSum[0] += wN * result.p01Xyz[0];
        ySum[1] += wE * result.p01Xyz[1];
        zSum[2] += wU * result.p01Xyz[2];
        wSum[0] += wN;
        wSum[1] += wE;
        wSum[2] += wU;

        count++;

        if (result.reliabilityGrade != null) {
            switch (result.reliabilityGrade) {
                case VERIFIED -> verifiedCount++;
                case CROSS_CHECKED -> crossCheckedCount++;
                case SUSPECT -> suspectCount++;
                default -> {}
            }
        }

        if (!Double.isNaN(result.protectionLevel)) {
            plSum += result.protectionLevel;
        }
        if (!Double.isNaN(result.crossValidationDist)) {
            crossValSum += result.crossValidationDist;
        }
    }

    public BatchResult flush() {
        if (count == 0) {
            return null;
        }

        double[] xAvg = new double[3];
        double[][] DAvg = new double[3][3];
        for (int i = 0; i < 3; i++) {
            xAvg[i] = (i == 0 ? xSum[0] : (i == 1 ? ySum[1] : zSum[2])) / wSum[i];
            if (wSum[i] > 0) {
                DAvg[i][i] = 1.0 / wSum[i];
            }
        }

        double plAvg = count > 0 ? plSum / count : Double.NaN;
        double crossValAvg = count > 0 ? crossValSum / count : Double.NaN;

        double reliableRatio = count > 0
                ? 100.0 * (verifiedCount + crossCheckedCount) / count : 0;

        ReliabilityGrade batchGrade;
        if (reliableRatio >= 80) {
            batchGrade = ReliabilityGrade.VERIFIED;
        } else if (reliableRatio >= 50) {
            batchGrade = ReliabilityGrade.CROSS_CHECKED;
        } else if (reliableRatio >= 20) {
            batchGrade = ReliabilityGrade.UNVERIFIED;
        } else {
            batchGrade = ReliabilityGrade.SUSPECT;
        }

        BatchResult br = new BatchResult(windowStartTag, windowEndTag, xAvg, DAvg,
                count, verifiedCount, crossCheckedCount, suspectCount,
                batchGrade, reliableRatio, plAvg, crossValAvg);

        reset();
        return br;
    }

    public boolean isFull() {
        return count >= windowSize;
    }

    public int getCount() {
        return count;
    }

    private void reset() {
        for (int i = 0; i < 3; i++) {
            xSum[i] = 0;
            ySum[i] = 0;
            zSum[i] = 0;
            wSum[i] = 0;
        }
        count = 0;
        verifiedCount = 0;
        crossCheckedCount = 0;
        suspectCount = 0;
        plSum = 0;
        crossValSum = 0;
        windowStartTag = null;
        windowEndTag = null;
    }

    private double computeWeight(AdjustResult result, int axis) {
        double baseWeight;
        if (result.Dx != null && result.Dx[axis][axis] > 0) {
            baseWeight = 1.0 / result.Dx[axis][axis];
        } else {
            baseWeight = 1.0;
        }

        if (result.reliabilityGrade == null) {
            return baseWeight * 0.1;
        }

        return switch (result.reliabilityGrade) {
            case VERIFIED -> baseWeight * 1.0;
            case CROSS_CHECKED -> baseWeight * 0.5;
            case SINGLE_VERIFIED -> baseWeight * 0.2;
            case UNVERIFIED -> baseWeight * 0.1;
            case SUSPECT -> baseWeight * 0.01;
        };
    }

    public static class BatchResult {
        public final String windowStartTag;
        public final String windowEndTag;
        public final double[] xyz;
        public final double[][] Dxyz;
        public final int epochCount;
        public final int verifiedCount;
        public final int crossCheckedCount;
        public final int suspectCount;
        public final ReliabilityGrade grade;
        public final double reliableRatio;
        public final double plAvg;
        public final double crossValAvg;

        public BatchResult(String windowStartTag, String windowEndTag,
                           double[] xyz, double[][] Dxyz,
                           int epochCount, int verifiedCount, int crossCheckedCount,
                           int suspectCount, ReliabilityGrade grade,
                           double reliableRatio, double plAvg, double crossValAvg) {
            this.windowStartTag = windowStartTag;
            this.windowEndTag = windowEndTag;
            this.xyz = xyz;
            this.Dxyz = Dxyz;
            this.epochCount = epochCount;
            this.verifiedCount = verifiedCount;
            this.crossCheckedCount = crossCheckedCount;
            this.suspectCount = suspectCount;
            this.grade = grade;
            this.reliableRatio = reliableRatio;
            this.plAvg = plAvg;
            this.crossValAvg = crossValAvg;
        }

        public double sigmaN() {
            return Dxyz[0][0] > 0 ? Math.sqrt(Dxyz[0][0]) : Double.NaN;
        }

        public double sigmaE() {
            return Dxyz[1][1] > 0 ? Math.sqrt(Dxyz[1][1]) : Double.NaN;
        }

        public double sigmaU() {
            return Dxyz[2][2] > 0 ? Math.sqrt(Dxyz[2][2]) : Double.NaN;
        }
    }
}