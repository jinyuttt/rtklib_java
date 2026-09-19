package org.rtklib.java.adjust.model;

public record BatchCoordinate(
        double[] xyz,
        double[] sigma,
        ReliabilityGrade grade,
        int epochCount,
        int verifiedCount,
        int crossCheckedCount,
        int suspectCount,
        double reliableRatio,
        double plAvg,
        double crossValAvg,
        String firstEpochTag,
        String lastEpochTag
) {

    public double sigmaN() {
        return sigma != null && sigma.length > 0 ? sigma[0] : Double.NaN;
    }

    public double sigmaE() {
        return sigma != null && sigma.length > 1 ? sigma[1] : Double.NaN;
    }

    public double sigmaU() {
        return sigma != null && sigma.length > 2 ? sigma[2] : Double.NaN;
    }

    public boolean isReliable() {
        return grade != null && grade.isReliable();
    }

    @Override
    public String toString() {
        return String.format(
                "BatchCoordinate{grade=%s, σN=%.2fmm, σE=%.2fmm, σU=%.2fmm, " +
                        "epochs=%d, V=%d, CC=%d, S=%d, reliable=%.0f%%, " +
                        "PL=%.3fm, %s~%s}",
                grade,
                sigmaN() * 1000, sigmaE() * 1000, sigmaU() * 1000,
                epochCount, verifiedCount, crossCheckedCount, suspectCount,
                reliableRatio, plAvg,
                firstEpochTag, lastEpochTag);
    }
}