package org.rtklib.java.adjust.engine;

import org.rtklib.java.adjust.covariance.CovAssembler;
import org.rtklib.java.adjust.datasource.GnssDataSource;
import org.rtklib.java.adjust.model.*;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.pntpos.SppProcessor;

import java.util.*;

public class BaseStationDiagnoser {

    private final AdjustDiagnosisConfig config;
    private final List<GnssDataSource> baseStations = new ArrayList<>();
    private GnssDataSource rover;
    private BaseDiagnosisHandler handler;

    private final Map<String, BaseStationDiagnosis> diagnosedCache = new LinkedHashMap<>();
    private final Set<String> notifiedBases = new LinkedHashSet<>();

    private final Deque<Double> sigma0Window = new ArrayDeque<>();
    private int highSigma0Count = 0;
    private double sigma0Sum = 0;
    private boolean pendingDiagnosis = false;

    public BaseStationDiagnoser(AdjustDiagnosisConfig config) {
        this.config = config;
    }

    public void addBaseStation(GnssDataSource base) {
        baseStations.add(base);
    }

    public void setRover(GnssDataSource rover) {
        this.rover = rover;
    }

    public void setHandler(BaseDiagnosisHandler handler) {
        this.handler = handler;
    }

    public void clearDiagnosis(String baseId) {
        diagnosedCache.remove(baseId);
        notifiedBases.remove(baseId);
    }

    public void clearAllDiagnosis() {
        diagnosedCache.clear();
        notifiedBases.clear();
        sigma0Window.clear();
        highSigma0Count = 0;
        sigma0Sum = 0;
        pendingDiagnosis = false;
    }

    public BaseStationDiagnosis getCachedDiagnosis(String baseId) {
        return diagnosedCache.get(baseId);
    }

    public boolean isDiagnosed(String baseId) {
        return diagnosedCache.containsKey(baseId);
    }

    public void feedAdjustResult(AdjustResult result) {
        if (!config.enable || result == null || !result.success || Double.isNaN(result.sigma0)) {
            return;
        }

        double sigma0 = result.sigma0;
        sigma0Sum += sigma0;

        sigma0Window.addLast(sigma0);
        if (sigma0 > config.sigma0Threshold) {
            highSigma0Count++;
        }

        if (sigma0Window.size() > config.diagnoseWindow) {
            double removed = sigma0Window.removeFirst();
            if (removed > config.sigma0Threshold) {
                highSigma0Count--;
            }
        }

        if (sigma0Window.size() >= config.diagnoseWindow) {
            double ratio = (double) highSigma0Count / sigma0Window.size();
            if (ratio >= config.diagnoseRatio) {
                if (!allDiagnosed()) {
                    if (hasEnoughDataForStatic()) {
                        performDeepDiagnosis();
                    } else {
                        pendingDiagnosis = true;
                    }
                }
            }
        }

        if (pendingDiagnosis && hasEnoughDataForStatic()) {
            performDeepDiagnosis();
            pendingDiagnosis = false;
        }
    }

    public List<BaseStationDiagnosis> diagnose(PrcOpt rtkOpt) {
        if (!config.enable || baseStations.size() < 2 || rover == null) {
            return List.of();
        }

        List<SolData> allSol = new ArrayList<>();
        for (GnssDataSource base : baseStations) {
            allSol.addAll(base.rtkPositioning(rover, rtkOpt));
        }

        return performDeepDiagnosis();
    }

    private List<BaseStationDiagnosis> performDeepDiagnosis() {
        int k = baseStations.size();
        List<BaseStationDiagnosis> results = new ArrayList<>();

        if (k == 1) {
            return results;
        }

        double meanSigma0 = sigma0Window.isEmpty() ? 0 : sigma0Sum / sigma0Window.size();
        int abnormalCount = highSigma0Count;
        int totalCount = sigma0Window.size();

        List<String> anomalousIds;
        if (k >= 3) {
            anomalousIds = identifyByResidual();
        } else {
            anomalousIds = identifyBySpp();
        }

        for (String baseId : anomalousIds) {
            if (config.cacheDiagnosis && diagnosedCache.containsKey(baseId)) {
                continue;
            }

            GnssDataSource anomalousBase = findBase(baseId);
            if (anomalousBase == null) continue;

            double[] rtcmPos = anomalousBase.getAntennaPosition();
            if (rtcmPos == null) continue;

            BaseStationDiagnosis diagnosis;
            if (config.useStaticForCorrection) {
                List<GnssDataSource> goodBases = findGoodBases(anomalousIds);
                if (goodBases.isEmpty()) {
                    diagnosis = BaseStationDiagnosis.unknown(baseId, rtcmPos,
                            "无可用参考站进行静态解算", meanSigma0, abnormalCount, totalCount);
                } else {
                    diagnosis = staticDiagnose(anomalousBase, goodBases.get(0),
                            rtcmPos, meanSigma0, abnormalCount, totalCount);
                }
            } else {
                diagnosis = BaseStationDiagnosis.unknown(baseId, rtcmPos,
                        "未启用静态解算，无法提供建议坐标", meanSigma0, abnormalCount, totalCount);
            }

            if (config.cacheDiagnosis) {
                diagnosedCache.put(baseId, diagnosis);
            }

            if (handler != null && !notifiedBases.contains(baseId)) {
                notifiedBases.add(baseId);
                handler.onDiagnosis(diagnosis);
            }

            results.add(diagnosis);
        }

        return results;
    }

    private List<String> identifyByResidual() {
        return List.of();
    }

    private List<String> identifyBySpp() {
        List<String> anomalous = new ArrayList<>();
        double maxSppDeviation = 0;
        String worstBase = null;

        PrcOpt sppOpt = SppProcessor.createDefaultOpt();

        for (GnssDataSource base : baseStations) {
            double[] rtcmPos = base.getAntennaPosition();
            if (rtcmPos == null) continue;

            List<SolData> sppSol = base.sppPositioning(sppOpt);
            if (sppSol.size() < 10) continue;

            Position sppMean = computeMeanEcef(sppSol);
            double dx = sppMean.v1 - rtcmPos[0];
            double dy = sppMean.v2 - rtcmPos[1];
            double dz = sppMean.v3 - rtcmPos[2];
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

            if (dist > maxSppDeviation) {
                maxSppDeviation = dist;
                worstBase = base.getId();
            }
        }

        if (worstBase != null && maxSppDeviation > 1.0) {
            anomalous.add(worstBase);
        }

        return anomalous;
    }

    private BaseStationDiagnosis staticDiagnose(GnssDataSource anomalousBase,
                                                 GnssDataSource refBase,
                                                 double[] rtcmPos,
                                                 double meanSigma0,
                                                 int abnormalCount, int totalCount) {
        PrcOpt staticOpt = createStaticOpt();
        List<SolData> staticSol = anomalousBase.staticPositioning(refBase, staticOpt);

        List<SolData> fixSol = staticSol.stream()
                .filter(sd -> sd.status == SolutionStatus.FIX).toList();

        if (fixSol.isEmpty()) {
            return BaseStationDiagnosis.unknown(anomalousBase.getId(), rtcmPos,
                    "静态解算无FIX解", meanSigma0, abnormalCount, totalCount);
        }

        Position meanEcef = computeMeanEcef(fixSol);
        double[] suggestedXyz = {meanEcef.v1, meanEcef.v2, meanEcef.v3};
        double[] suggestedLlh = new double[3];
        CoordTransform.ecef2pos(suggestedXyz, suggestedLlh);

        double[] rtcmLlh = new double[3];
        CoordTransform.ecef2pos(rtcmPos, rtcmLlh);

        double dN = (suggestedLlh[0] - rtcmLlh[0]) * 6371000;
        double dE = (suggestedLlh[1] - rtcmLlh[1]) * 6371000 * Math.cos(rtcmLlh[0]);
        double dU = suggestedLlh[2] - rtcmLlh[2];
        double dX = suggestedXyz[0] - rtcmPos[0];
        double dY = suggestedXyz[1] - rtcmPos[1];
        double dZ = suggestedXyz[2] - rtcmPos[2];
        double dist3d = Math.sqrt(dX * dX + dY * dY + dZ * dZ);

        double varX = 0, varY = 0, varZ = 0;
        for (SolData sd : fixSol) {
            Position p = sd.getPosition(CoordType.ECEF);
            if (p != null) {
                varX += Math.pow(p.v1 - meanEcef.v1, 2);
                varY += Math.pow(p.v2 - meanEcef.v2, 2);
                varZ += Math.pow(p.v3 - meanEcef.v3, 2);
            }
        }
        double stdX = Math.sqrt(varX / fixSol.size());
        double stdY = Math.sqrt(varY / fixSol.size());
        double stdZ = Math.sqrt(varZ / fixSol.size());

        double fixRate = (double) fixSol.size() / staticSol.size();

        return BaseStationDiagnosis.of(
                anomalousBase.getId(), rtcmPos,
                suggestedXyz, suggestedLlh,
                new double[]{dN, dE, dU}, dist3d,
                fixRate, new double[]{stdX, stdY, stdZ},
                fixSol.size(), staticSol.size(),
                "静态基线解算(参考站:" + refBase.getId() + ")",
                meanSigma0, abnormalCount, totalCount);
    }

    private PrcOpt createStaticOpt() {
        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_STATIC;
        opt.nf = 3;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL | Constants.SYS_CMP;
        opt.elmin = 15.0 * Constants.D2R;
        opt.soltype = 0;
        opt.modear = Constants.ARMODE_FIXHOLD;
        opt.glomodear = Constants.GLO_ARMODE_AUTOCAL;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;
        opt.posMask = 0xFFFF;
        opt.refpos = Constants.POSOPT_RTCM;
        opt.intpref = 1;
        return opt;
    }

    private boolean allDiagnosed() {
        for (GnssDataSource base : baseStations) {
            if (!diagnosedCache.containsKey(base.getId())) return false;
        }
        return true;
    }

    private boolean hasEnoughDataForStatic() {
        for (GnssDataSource base : baseStations) {
            if (base.estimatedDataHours() >= config.minStaticDataHours) return true;
        }
        if (rover != null && rover.estimatedDataHours() >= config.minStaticDataHours) return true;
        return false;
    }

    private List<GnssDataSource> findGoodBases(List<String> anomalousIds) {
        List<GnssDataSource> good = new ArrayList<>();
        for (GnssDataSource base : baseStations) {
            if (!anomalousIds.contains(base.getId())) {
                good.add(base);
            }
        }
        return good;
    }

    private GnssDataSource findBase(String baseId) {
        for (GnssDataSource base : baseStations) {
            if (base.getId().equals(baseId)) return base;
        }
        return null;
    }

    private static Position computeMeanEcef(List<SolData> solList) {
        double sumX = 0, sumY = 0, sumZ = 0;
        int n = 0;
        for (SolData sd : solList) {
            Position p = sd.getPosition(CoordType.ECEF);
            if (p != null) { sumX += p.v1; sumY += p.v2; sumZ += p.v3; n++; }
        }
        if (n == 0) return null;
        return new Position(CoordType.ECEF, sumX / n, sumY / n, sumZ / n);
    }
}