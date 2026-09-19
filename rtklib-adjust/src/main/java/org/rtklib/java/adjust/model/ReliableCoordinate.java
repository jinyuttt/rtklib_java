package org.rtklib.java.adjust.model;

import org.rtklib.java.data.CoordType;
import org.rtklib.java.data.Position;
import org.rtklib.java.data.SolutionStatus;
import org.rtklib.java.data.SolData;

public record ReliableCoordinate(
        double[] xyz,
        double[] llh,
        ReliabilityGrade grade,
        double protectionLevel,
        double sigma0,
        double crossValidationDist,
        String suspectBaseId,
        String epochTag,
        int usedBaselineCount
) {

    public boolean isReliable() {
        return grade != null && grade.isReliable();
    }

    public static ReliableCoordinate fromAdjustResult(AdjustResult result) {
        if (result == null || !result.success || result.p01Xyz == null) {
            return null;
        }

        double[] xyz = result.p01Xyz.clone();
        double[] llh = new double[3];
        try {
            org.rtklib.java.coord.CoordTransform.ecef2pos(xyz, llh);
        } catch (Exception e) {
            llh = new double[]{Double.NaN, Double.NaN, Double.NaN};
        }

        return new ReliableCoordinate(
                xyz, llh,
                result.reliabilityGrade,
                result.protectionLevel,
                result.sigma0,
                result.crossValidationDist,
                result.suspectBaseId,
                result.epochTag,
                result.usedBaselineCount
        );
    }

    public static ReliableCoordinate fromSingleBaseline(SolData solData, String epochTag) {
        if (solData == null || solData.status != SolutionStatus.FIX) {
            return null;
        }

        Position pos = solData.getPosition(CoordType.ECEF);
        if (pos == null) return null;

        double[] xyz = new double[]{pos.v1, pos.v2, pos.v3};
        double[] llh = new double[3];
        try {
            org.rtklib.java.coord.CoordTransform.ecef2pos(xyz, llh);
        } catch (Exception e) {
            llh = new double[]{Double.NaN, Double.NaN, Double.NaN};
        }

        return new ReliableCoordinate(
                xyz, llh,
                ReliabilityGrade.UNVERIFIED,
                Double.NaN,
                Double.NaN,
                Double.NaN,
                null,
                epochTag,
                1
        );
    }
}