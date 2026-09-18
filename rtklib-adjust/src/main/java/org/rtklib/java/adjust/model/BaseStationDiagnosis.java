package org.rtklib.java.adjust.model;

import java.util.List;

public class BaseStationDiagnosis {

    public final String baseId;

    public final double[] rtcm1005Xyz;

    public final double[] suggestedXyz;

    public final double[] suggestedLlh;

    public final double[] deviationNeu;

    public final double deviation3d;

    public final AnomalyLevel anomalyLevel;

    public final String suggestion;

    public final double staticFixRate;

    public final double[] staticStdEcef;

    public final int staticFixCount;

    public final int staticTotalCount;

    public final String diagnosisMethod;

    public final double meanSigma0;

    public final int abnormalEpochCount;

    public final int totalEpochCount;

    public enum AnomalyLevel {
        NORMAL("正常 (偏差<0.5m)"),
        WARNING("警告 (偏差0.5~2m)"),
        ERROR("异常 (偏差2~5m)"),
        CRITICAL("严重异常 (偏差>5m)"),
        UNKNOWN("未知 (数据不足)");

        public final String description;
        AnomalyLevel(String description) { this.description = description; }
    }

    private BaseStationDiagnosis(String baseId, double[] rtcm1005Xyz,
                                 double[] suggestedXyz, double[] suggestedLlh,
                                 double[] deviationNeu, double deviation3d,
                                 AnomalyLevel anomalyLevel, String suggestion,
                                 double staticFixRate, double[] staticStdEcef,
                                 int staticFixCount, int staticTotalCount,
                                 String diagnosisMethod, double meanSigma0,
                                 int abnormalEpochCount, int totalEpochCount) {
        this.baseId = baseId;
        this.rtcm1005Xyz = rtcm1005Xyz;
        this.suggestedXyz = suggestedXyz;
        this.suggestedLlh = suggestedLlh;
        this.deviationNeu = deviationNeu;
        this.deviation3d = deviation3d;
        this.anomalyLevel = anomalyLevel;
        this.suggestion = suggestion;
        this.staticFixRate = staticFixRate;
        this.staticStdEcef = staticStdEcef;
        this.staticFixCount = staticFixCount;
        this.staticTotalCount = staticTotalCount;
        this.diagnosisMethod = diagnosisMethod;
        this.meanSigma0 = meanSigma0;
        this.abnormalEpochCount = abnormalEpochCount;
        this.totalEpochCount = totalEpochCount;
    }

    public static BaseStationDiagnosis of(String baseId, double[] rtcm1005Xyz,
                                          double[] suggestedXyz, double[] suggestedLlh,
                                          double[] deviationNeu, double deviation3d,
                                          double staticFixRate, double[] staticStdEcef,
                                          int staticFixCount, int staticTotalCount,
                                          String diagnosisMethod, double meanSigma0,
                                          int abnormalEpochCount, int totalEpochCount) {
        AnomalyLevel level;
        if (deviation3d < 0.5) level = AnomalyLevel.NORMAL;
        else if (deviation3d < 2.0) level = AnomalyLevel.WARNING;
        else if (deviation3d < 5.0) level = AnomalyLevel.ERROR;
        else level = AnomalyLevel.CRITICAL;

        String suggestion;
        if (level == AnomalyLevel.NORMAL) {
            suggestion = "基站坐标正常，无需修正";
        } else if (level == AnomalyLevel.WARNING) {
            suggestion = String.format("基站坐标偏差%.1fm，建议用静态解算坐标替代", deviation3d);
        } else if (level == AnomalyLevel.ERROR) {
            suggestion = String.format("基站坐标偏差%.1fm，强烈建议用静态解算坐标替代", deviation3d);
        } else {
            suggestion = String.format("基站坐标偏差%.1fm，必须修正！请用静态解算坐标或已知控制点坐标替代", deviation3d);
        }

        return new BaseStationDiagnosis(baseId, rtcm1005Xyz, suggestedXyz, suggestedLlh,
                deviationNeu, deviation3d, level, suggestion,
                staticFixRate, staticStdEcef, staticFixCount, staticTotalCount,
                diagnosisMethod, meanSigma0, abnormalEpochCount, totalEpochCount);
    }

    public static BaseStationDiagnosis unknown(String baseId, double[] rtcm1005Xyz,
                                                String reason, double meanSigma0,
                                                int abnormalEpochCount, int totalEpochCount) {
        return new BaseStationDiagnosis(baseId, rtcm1005Xyz, null, null,
                new double[]{0, 0, 0}, 0, AnomalyLevel.UNKNOWN, reason,
                0, new double[]{0, 0, 0}, 0, 0,
                "N/A", meanSigma0, abnormalEpochCount, totalEpochCount);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%n  基站 %s 诊断结果:%n", baseId));
        sb.append("  ─────────────────────────────────\n");
        sb.append(String.format("  异常等级: %s%n", anomalyLevel.description));
        sb.append(String.format("  诊断方法: %s%n", diagnosisMethod));
        sb.append(String.format("  σ₀统计:   异常历元%d/%d, 均值=%.1f%n",
                abnormalEpochCount, totalEpochCount, meanSigma0));
        sb.append(String.format("  RTCM 1005 ECEF: X=%.3f Y=%.3f Z=%.3f%n",
                rtcm1005Xyz[0], rtcm1005Xyz[1], rtcm1005Xyz[2]));
        if (suggestedXyz != null) {
            sb.append(String.format("  建议坐标 ECEF:  X=%.4f Y=%.4f Z=%.4f%n",
                    suggestedXyz[0], suggestedXyz[1], suggestedXyz[2]));
            sb.append(String.format("  建议坐标 LLH:   Lat=%.9f Lon=%.9f H=%.4f%n",
                    Math.toDegrees(suggestedLlh[0]), Math.toDegrees(suggestedLlh[1]), suggestedLlh[2]));
        }
        if (deviation3d > 0) {
            sb.append(String.format("  偏差(NEU):  dN=%.3fm dE=%.3fm dU=%.3fm (3D=%.3fm)%n",
                    deviationNeu[0], deviationNeu[1], deviationNeu[2], deviation3d));
        }
        if (staticTotalCount > 0) {
            sb.append(String.format("  静态解算:   FIX=%d/%d (%.1f%%), σX=%.4f σY=%.4f σZ=%.4f m%n",
                    staticFixCount, staticTotalCount, staticFixRate * 100,
                    staticStdEcef[0], staticStdEcef[1], staticStdEcef[2]));
        }
        sb.append(String.format("  建议: %s%n", suggestion));
        return sb.toString();
    }
}