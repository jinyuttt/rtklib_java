package org.rtklib.java.adjust.model;

import org.ejml.simple.SimpleMatrix;

public class AdjustResult {

    public final String epochTag;

    public final double[] dx;

    public final double[][] Dx;

    public final double[] v;

    public final double sigma0;

    public final int dof;

    public final SimpleMatrix Qv;

    public final double[] baardaT;

    public final double[] p01Xyz;

    public final int usedBaselineCount;

    public final boolean success;

    public final String errorMessage;

    public final ReliabilityGrade reliabilityGrade;

    public final double crossValidationDist;

    public final double protectionLevel;

    public final String suspectBaseId;

    private AdjustResult(String epochTag, double[] dx, double[][] Dx, double[] v,
                         double sigma0, int dof, SimpleMatrix Qv, double[] baardaT,
                         double[] p01Xyz, int usedBaselineCount) {
        this(epochTag, dx, Dx, v, sigma0, dof, Qv, baardaT, p01Xyz, usedBaselineCount,
                null, Double.NaN, Double.NaN, null);
    }

    private AdjustResult(String epochTag, double[] dx, double[][] Dx, double[] v,
                         double sigma0, int dof, SimpleMatrix Qv, double[] baardaT,
                         double[] p01Xyz, int usedBaselineCount,
                         ReliabilityGrade reliabilityGrade, double crossValidationDist,
                         double protectionLevel, String suspectBaseId) {
        this.epochTag = epochTag;
        this.dx = dx;
        this.Dx = Dx;
        this.v = v;
        this.sigma0 = sigma0;
        this.dof = dof;
        this.Qv = Qv;
        this.baardaT = baardaT;
        this.p01Xyz = p01Xyz;
        this.usedBaselineCount = usedBaselineCount;
        this.success = true;
        this.errorMessage = null;
        this.reliabilityGrade = reliabilityGrade;
        this.crossValidationDist = crossValidationDist;
        this.protectionLevel = protectionLevel;
        this.suspectBaseId = suspectBaseId;
    }

    private AdjustResult(String epochTag, int usedBaselineCount, String errorMessage) {
        this.epochTag = epochTag;
        this.dx = null;
        this.Dx = null;
        this.v = null;
        this.sigma0 = Double.NaN;
        this.dof = 0;
        this.Qv = null;
        this.baardaT = null;
        this.p01Xyz = null;
        this.usedBaselineCount = usedBaselineCount;
        this.success = false;
        this.errorMessage = errorMessage;
        this.reliabilityGrade = null;
        this.crossValidationDist = Double.NaN;
        this.protectionLevel = Double.NaN;
        this.suspectBaseId = null;
    }

    public static AdjustResult success(String epochTag, double[] dx, double[][] Dx,
                                       double[] v, double sigma0, int dof,
                                       SimpleMatrix Qv, double[] baardaT,
                                       double[] p01Xyz, int usedBaselineCount) {
        return new AdjustResult(epochTag, dx, Dx, v, sigma0, dof, Qv, baardaT, p01Xyz, usedBaselineCount);
    }

    public static AdjustResult success(String epochTag, double[] dx, double[][] Dx,
                                       double[] v, double sigma0, int dof,
                                       SimpleMatrix Qv, double[] baardaT,
                                       double[] p01Xyz, int usedBaselineCount,
                                       ReliabilityGrade reliabilityGrade, double crossValidationDist,
                                       double protectionLevel, String suspectBaseId) {
        return new AdjustResult(epochTag, dx, Dx, v, sigma0, dof, Qv, baardaT, p01Xyz, usedBaselineCount,
                reliabilityGrade, crossValidationDist, protectionLevel, suspectBaseId);
    }

    public static AdjustResult failure(String epochTag, int usedBaselineCount, String errorMessage) {
        return new AdjustResult(epochTag, usedBaselineCount, errorMessage);
    }
}