package org.rtklib.java.adjust.engine;

import org.rtklib.java.adjust.model.*;
import org.rtklib.java.data.Accuracy;
import org.rtklib.java.data.CoordType;
import org.rtklib.java.data.SolData;
import org.rtklib.java.data.SolutionStatus;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class MultiBaselineAdjust {

    private final MultiBaselineConfig config;
    private final Map<String, SolData> pendingBaselines;
    private String currentEpochTag;
    private int epochIndex;

    private ReliableCoordinate lastCoordinate;
    private AdjustResult lastAdjustResult;

    private final List<ReliableCoordinate> windowCoords;
    private int windowCount;

    private BaseStationDiagnoser diagnoser;
    private final Map<String, BaseStationDiagnosis> latestDiagnoses = new LinkedHashMap<>();

    private final List<EpochResultListener> epochListeners = new CopyOnWriteArrayList<>();
    private final List<BatchResultListener> batchListeners = new CopyOnWriteArrayList<>();
    private final List<DiagnosisListener> diagnosisListeners = new CopyOnWriteArrayList<>();
    private final List<SuspectListener> suspectListeners = new CopyOnWriteArrayList<>();

    @FunctionalInterface
    public interface EpochResultListener {
        void onResult(ReliableCoordinate coord, AdjustResult detail);
    }

    @FunctionalInterface
    public interface BatchResultListener {
        void onResult(BatchCoordinate batch);
    }

    @FunctionalInterface
    public interface DiagnosisListener {
        void onDiagnosis(BaseStationDiagnosis diagnosis);
    }

    @FunctionalInterface
    public interface SuspectListener {
        void onSuspect(String suspectBaseId, double crossValDist, String epochTag);
    }

    private MultiBaselineAdjust(MultiBaselineConfig config) {
        this.config = config;
        this.pendingBaselines = new LinkedHashMap<>();
        this.windowCoords = new ArrayList<>();
        this.windowCount = 0;
        this.epochIndex = 0;

        if (config.diagnosis().enable()) {
            AdjustDiagnosisConfig diagConfig = AdjustDiagnosisConfig.builder()
                    .enable(true)
                    .sigma0Threshold(config.diagnosis().sigma0Threshold())
                    .diagnoseWindow(config.diagnosis().diagnoseWindow())
                    .diagnoseRatio(config.diagnosis().diagnoseRatio())
                    .build();
            this.diagnoser = new BaseStationDiagnoser(diagConfig);
            this.diagnoser.setHandler(diagnosis -> {
                latestDiagnoses.put(diagnosis.baseId, diagnosis);
                for (DiagnosisListener l : diagnosisListeners) {
                    l.onDiagnosis(diagnosis);
                }
            });
        }
    }

    public static MultiBaselineAdjust create(MultiBaselineConfig config) {
        return new MultiBaselineAdjust(config);
    }

    public static MultiBaselineAdjust create() {
        return new MultiBaselineAdjust(MultiBaselineConfig.defaults());
    }

    public void feedBaseline(String baseId, SolData solData, String epochTag) {
        if (baseId == null || solData == null) return;

        if (currentEpochTag == null || !currentEpochTag.equals(epochTag)) {
            if (currentEpochTag != null && !pendingBaselines.isEmpty()) {
                processEpoch();
            }
            pendingBaselines.clear();
            currentEpochTag = epochTag;
        }

        if (solData.status == SolutionStatus.FIX) {
            pendingBaselines.put(baseId, solData);
        }
    }

    public void feedBaseline(String baseId, SolData solData) {
        feedBaseline(baseId, solData, String.valueOf(epochIndex));
    }

    public ReliableCoordinate flush() {
        if (currentEpochTag != null && !pendingBaselines.isEmpty()) {
            processEpoch();
        }
        pendingBaselines.clear();
        currentEpochTag = null;

        if (!windowCoords.isEmpty()) {
            BatchCoordinate batch = computeBatch(windowCoords);
            for (BatchResultListener l : batchListeners) {
                l.onResult(batch);
            }
            windowCoords.clear();
            windowCount = 0;
        }

        return lastCoordinate;
    }

    public ReliableCoordinate getCoordinate() {
        return lastCoordinate;
    }

    public AdjustResult getLastAdjustResult() {
        return lastAdjustResult;
    }

    public BaseStationDiagnosis getLatestDiagnosis(String baseId) {
        return latestDiagnoses.get(baseId);
    }

    public Collection<BaseStationDiagnosis> getAllDiagnoses() {
        return Collections.unmodifiableCollection(latestDiagnoses.values());
    }

    public boolean hasDiagnosis() {
        return !latestDiagnoses.isEmpty();
    }

    public MultiBaselineAdjust onEpochResult(EpochResultListener listener) {
        epochListeners.add(listener);
        return this;
    }

    public MultiBaselineAdjust onBatchResult(BatchResultListener listener) {
        batchListeners.add(listener);
        return this;
    }

    public MultiBaselineAdjust onDiagnosis(DiagnosisListener listener) {
        diagnosisListeners.add(listener);
        return this;
    }

    public MultiBaselineAdjust onSuspect(SuspectListener listener) {
        suspectListeners.add(listener);
        return this;
    }

    public int getEpochIndex() {
        return epochIndex;
    }

    public int getWindowCount() {
        return windowCount;
    }

    private void processEpoch() {
        epochIndex++;

        List<BaselineEpoch.BaselineEntry> entries = new ArrayList<>();
        for (Map.Entry<String, SolData> e : pendingBaselines.entrySet()) {
            BaselineEpoch.BaselineEntry entry = buildBaselineEntry(e.getKey(), e.getValue());
            if (entry != null) {
                entries.add(entry);
            }
        }

        if (entries.isEmpty()) return;

        BaselineEpoch epoch = new BaselineEpoch(currentEpochTag,
                entries.toArray(new BaselineEpoch.BaselineEntry[0]));

        AdjustResult result = GnssBaselineAdjust.adjust(epoch, config.qualityWeight());
        lastAdjustResult = result;

        if (!result.success) return;

        lastCoordinate = ReliableCoordinate.fromAdjustResult(result);
        if (lastCoordinate == null) return;

        if (result.reliabilityGrade == ReliabilityGrade.SUSPECT && result.suspectBaseId != null) {
            for (SuspectListener l : suspectListeners) {
                l.onSuspect(result.suspectBaseId, result.crossValidationDist, currentEpochTag);
            }
        }

        if (diagnoser != null) {
            diagnoser.feedAdjustResult(result);
        }

        windowCoords.add(lastCoordinate);
        windowCount++;

        for (EpochResultListener l : epochListeners) {
            l.onResult(lastCoordinate, result);
        }

        int batchSize = config.batch().enabled() ? config.batch().windowSize() : 0;
        if (batchSize > 0 && windowCount >= batchSize) {
            BatchCoordinate batch = computeBatch(windowCoords);
            for (BatchResultListener l : batchListeners) {
                l.onResult(batch);
            }
            windowCoords.clear();
            windowCount = 0;
        }
    }

    private BatchCoordinate computeBatch(List<ReliableCoordinate> coords) {
        if (coords.isEmpty()) return null;

        double[] xSum = {0, 0, 0};
        double[] wSum = {0, 0, 0};
        int verifiedCount = 0;
        int crossCheckedCount = 0;
        int suspectCount = 0;
        double plSum = 0;
        double crossValSum = 0;
        String firstTag = null;
        String lastTag = null;

        for (ReliableCoordinate c : coords) {
            if (firstTag == null) firstTag = c.epochTag();
            lastTag = c.epochTag();

            double w = gradeWeight(c.grade());
            for (int i = 0; i < 3; i++) {
                xSum[i] += w * c.xyz()[i];
                wSum[i] += w;
            }

            if (c.grade() == ReliabilityGrade.VERIFIED) verifiedCount++;
            else if (c.grade() == ReliabilityGrade.CROSS_CHECKED) crossCheckedCount++;
            else if (c.grade() == ReliabilityGrade.SUSPECT) suspectCount++;

            if (!Double.isNaN(c.protectionLevel())) plSum += c.protectionLevel();
            if (!Double.isNaN(c.crossValidationDist())) crossValSum += c.crossValidationDist();
        }

        double[] xyz = new double[3];
        double[] sigma = new double[3];
        for (int i = 0; i < 3; i++) {
            xyz[i] = wSum[i] > 0 ? xSum[i] / wSum[i] : 0;
        }

        double[] variance = {0, 0, 0};
        for (ReliableCoordinate c : coords) {
            double w = gradeWeight(c.grade());
            for (int i = 0; i < 3; i++) {
                double d = c.xyz()[i] - xyz[i];
                variance[i] += w * d * d;
            }
        }
        for (int i = 0; i < 3; i++) {
            sigma[i] = wSum[i] > 0 ? Math.sqrt(variance[i] / wSum[i]) : Double.NaN;
        }

        double reliableRatio = 100.0 * (verifiedCount + crossCheckedCount) / coords.size();
        ReliabilityGrade batchGrade;
        if (reliableRatio >= 80) batchGrade = ReliabilityGrade.VERIFIED;
        else if (reliableRatio >= 50) batchGrade = ReliabilityGrade.CROSS_CHECKED;
        else if (reliableRatio >= 20) batchGrade = ReliabilityGrade.UNVERIFIED;
        else batchGrade = ReliabilityGrade.SUSPECT;

        double plAvg = plSum / coords.size();
        double crossValAvg = crossValSum / coords.size();

        return new BatchCoordinate(
                xyz, sigma, batchGrade,
                coords.size(), verifiedCount, crossCheckedCount, suspectCount,
                reliableRatio, plAvg, crossValAvg,
                firstTag, lastTag);
    }

    private double gradeWeight(ReliabilityGrade grade) {
        if (grade == null) return 0.1;
        return switch (grade) {
            case VERIFIED -> 1.0;
            case CROSS_CHECKED -> 0.5;
            case SINGLE_VERIFIED -> 0.2;
            case UNVERIFIED -> 0.1;
            case SUSPECT -> 0.01;
        };
    }

    private BaselineEpoch.BaselineEntry buildBaselineEntry(String baseId, SolData solData) {
        try {
            double[] dXyz = extractBaselineVector(solData);
            double[][] cBaseline = extractBaselineCov(solData);
            double[][] pRover = extractRoverCov(solData);
            double qf = BaselineEpoch.BaselineEntry.computeQualityFactor(solData);
            return new BaselineEpoch.BaselineEntry(baseId, solData, dXyz, cBaseline, pRover, qf);
        } catch (Exception e) {
            return null;
        }
    }

    private double[] extractBaselineVector(SolData solData) {
        if (solData.hPos != null && solData.hPos.length >= 3) {
            return new double[]{solData.hPos[0], solData.hPos[1], solData.hPos[2]};
        }
        return new double[]{0, 0, 0};
    }

    private double[][] extractBaselineCov(SolData solData) {
        Accuracy acc = solData.getAccuracy(CoordType.ECEF);
        if (acc != null) {
            double vx = acc.s1 * acc.s1;
            double vy = acc.s2 * acc.s2;
            double vz = acc.s3 * acc.s3;
            return new double[][]{
                    {vx, 0, 0},
                    {0, vy, 0},
                    {0, 0, vz}
            };
        }
        double v = 0.01;
        return new double[][]{{v, 0, 0}, {0, v, 0}, {0, 0, v}};
    }

    private double[][] extractRoverCov(SolData solData) {
        Accuracy acc = solData.getAccuracy(CoordType.ECEF);
        if (acc != null) {
            double vx = acc.s1 * acc.s1;
            double vy = acc.s2 * acc.s2;
            double vz = acc.s3 * acc.s3;
            return new double[][]{
                    {vx, 0, 0},
                    {0, vy, 0},
                    {0, 0, vz}
            };
        }
        double v = 0.01;
        return new double[][]{{v, 0, 0}, {0, v, 0}, {0, 0, v}};
    }
}