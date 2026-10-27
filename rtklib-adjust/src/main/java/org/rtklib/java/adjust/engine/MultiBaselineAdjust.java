package org.rtklib.java.adjust.engine;

import org.rtklib.java.adjust.covariance.CovAssembler;
import org.rtklib.java.adjust.model.*;
import org.rtklib.java.data.Accuracy;
import org.rtklib.java.data.CoordType;
import org.rtklib.java.data.SolData;
import org.rtklib.java.data.SolutionStatus;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class MultiBaselineAdjust {

    private final MultiBaselineConfig config;

    /** 交叉验证阈值与保护级系数（由 config.crossValidation() 映射）。 */
    private final GnssBaselineAdjust.Policy adjustPolicy;
    private final Map<String, SolData> pendingBaselines;
    private String currentEpochTag;
    private int epochIndex;

    private ReliableCoordinate lastCoordinate;
    private AdjustResult lastAdjustResult;

    private final List<ReliableCoordinate> windowCoords;
    private int windowCount;

    private BaseStationDiagnoser diagnoser;
    private final Map<String, BaseStationDiagnosis> latestDiagnoses = new LinkedHashMap<>();

    /** 重复基线 / 基站间漂移 / 异步环闭合差检核。 */
    private final BaselineConsistencyChecker consistency = new BaselineConsistencyChecker();

    /** 逐历元平差残差的白度检验（lag-1 自相关 + 游程检验）。 */
    private final ResidualWhiteness residualWhiteness = new ResidualWhiteness();

    /** 基站 ECEF 坐标表（注册后才启用自由网/约束平差累积）。 */
    private final Map<String, double[]> basePositions = new LinkedHashMap<>();

    /** 供整时段网平差使用的基线观测累积（含真基线向量 Rover − 基站）。 */
    private final Deque<NetworkAdjustment.BaselineObs> networkObs = new ArrayDeque<>();

    /** 网平差观测累积上限，避免长时间运行内存无界增长。 */
    private static final int MAX_NETWORK_OBS = 20000;

    private final List<EpochResultListener> epochListeners = new CopyOnWriteArrayList<>();
    private final List<BatchResultListener> batchListeners = new CopyOnWriteArrayList<>();
    private final List<DiagnosisListener> diagnosisListeners = new CopyOnWriteArrayList<>();
    private final List<SuspectListener> suspectListeners = new CopyOnWriteArrayList<>();
    private final List<ConsistencyListener> consistencyListeners = new CopyOnWriteArrayList<>();

    /** 已告警过的异常基站，避免同一问题每历元重复触发。 */
    private final Set<String> notifiedInconsistent = new LinkedHashSet<>();

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

    /**
     * 基站位移/一致性告警。
     *
     * <p>由 {@link BaselineConsistencyChecker} 的基站间漂移与异步环闭合差触发，
     * 与 {@link SuspectListener}（假固定嫌疑）是两个独立通道。</p>
     */
    @FunctionalInterface
    public interface ConsistencyListener {
        void onInconsistent(String baseId, double driftNorm, String epochTag);
    }

    private MultiBaselineAdjust(MultiBaselineConfig config) {
        this.config = config;
        this.adjustPolicy = GnssBaselineAdjust.Policy.fromConfig(config);
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

    /** 重复基线 / 基站间漂移 / 异步环闭合差的当前检核报告。 */
    public BaselineConsistencyChecker.Report getConsistencyReport() {
        return consistency.report();
    }

    public BaselineConsistencyChecker getConsistencyChecker() {
        return consistency;
    }

    /** 清空一致性检核的累计样本与告警状态。 */
    public void resetConsistency() {
        consistency.reset();
        notifiedInconsistent.clear();
    }

    /** 平差残差的白度检验报告（lag-1 自相关 + 游程检验，逐基站逐坐标轴）。 */
    public ResidualWhiteness.Report getResidualWhitenessReport() {
        return residualWhiteness.report();
    }

    /** 清空残差白度检验的累计样本。 */
    public void resetResidualWhiteness() {
        residualWhiteness.reset();
    }

    /**
     * 把一致性检核结果转成告警回调。
     *
     * <p>只在某基站首次被判异常时触发一次；整网恢复一致后清除告警状态，
     * 若再次异常会重新触发。</p>
     */
    private void checkConsistency() {
        if (consistencyListeners.isEmpty()) return;

        BaselineConsistencyChecker.Report report = consistency.report();
        if (report.pass()) {
            notifiedInconsistent.clear();
            return;
        }

        for (String baseId : report.suspectBaseIds()) {
            if (!notifiedInconsistent.add(baseId)) continue;

            double norm = 0.0;
            for (BaselineConsistencyChecker.BaseDrift bd : report.baseDrifts()) {
                if (bd.baseId().equals(baseId)) {
                    norm = bd.norm();
                    break;
                }
            }
            for (ConsistencyListener l : consistencyListeners) {
                l.onInconsistent(baseId, norm, currentEpochTag);
            }
        }
    }

    /**
     * 注册基站已知 ECEF 坐标，启用整时段网平差（自由网/约束平差）的观测累积。
     *
     * <p>注册后，每次历元处理会把 {@code Rover − 基站} 的真基线向量连同协方差
     * 累积起来，供 {@link #computeNetworkAdjustment(boolean)} 使用。</p>
     *
     * @param baseId 基站标识
     * @param ecef   基站 ECEF 坐标 [X, Y, Z] (m)
     */
    public void registerBasePosition(String baseId, double[] ecef) {
        if (baseId == null || ecef == null || ecef.length < 3) return;
        basePositions.put(baseId, new double[]{ecef[0], ecef[1], ecef[2]});
    }

    /** 已注册的基站数。 */
    public int getRegisteredBaseCount() {
        return basePositions.size();
    }

    /** 当前累积的网平差观测数。 */
    public int getNetworkObsCount() {
        return networkObs.size();
    }

    /**
     * 对已累积的全部观测执行整时段网平差。
     *
     * @param free   true = 自由网（未知数含各基站位移，重心基准）
     *               false = 约束平差（基站坐标视为已知，只解 Rover）
     * @return 平差结果；未注册基站或观测不足时 success=false
     */
    public NetworkAdjustment.Result computeNetworkAdjustment(boolean free) {
        if (basePositions.isEmpty()) {
            return NetworkAdjustment.Result.failure("未注册基站坐标，无法进行网平差");
        }
        List<NetworkAdjustment.BaselineObs> obs = new ArrayList<>(networkObs);
        return free ? NetworkAdjustment.freeNetwork(obs) : NetworkAdjustment.constrained(obs);
    }

    /** 清空网平差观测累积（保留基站坐标表）。 */
    public void resetNetworkObservations() {
        networkObs.clear();
    }

    private void accumulateNetworkObs(String epochTag, List<BaselineEpoch.BaselineEntry> entries) {
        if (basePositions.isEmpty()) return;

        for (BaselineEpoch.BaselineEntry e : entries) {
            double[] baseXyz = basePositions.get(e.baseId);
            if (baseXyz == null || e.solData == null) continue;

            double[][] cov = CovAssembler.extractCovariance3x3(e.solData);
            if (cov == null) continue;
            cov = withBaseCoordinateSigma(cov);

            double[] rover = e.dXyz;
            if (rover == null || rover.length < 3) continue;

            double[] baselineVector = {
                    rover[0] - baseXyz[0],
                    rover[1] - baseXyz[1],
                    rover[2] - baseXyz[2]
            };

            if (networkObs.size() >= MAX_NETWORK_OBS) {
                networkObs.pollFirst();
            }
            double gpsTime = e.solData.time != null ? e.solData.time.time : 0.0;
            networkObs.addLast(new NetworkAdjustment.BaselineObs(
                    e.baseId, epochTag, gpsTime, baseXyz.clone(), baselineVector, cov));
        }
        trimNetworkObsWindow();
    }

    /**
     * 按配置时间窗淘汰过期观测。观测按到达顺序入队（时间单调，跨小时边界除外），
     * 只需从头部检查。gpsTime 未知（0）的观测不参与时间淘汰，仅受条数上限约束。
     */
    private void trimNetworkObsWindow() {
        double window = config.networkWindowSeconds();
        if (window <= 0 || networkObs.isEmpty()) return;
        double newest = networkObs.peekLast().gpsTime();
        if (newest <= 0) return;
        while (!networkObs.isEmpty()) {
            NetworkAdjustment.BaselineObs oldest = networkObs.peekFirst();
            if (oldest.gpsTime() <= 0 || newest - oldest.gpsTime() <= window) break;
            networkObs.pollFirst();
        }
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

    /** 注册基站位移/一致性告警回调。 */
    public MultiBaselineAdjust onConsistency(ConsistencyListener listener) {
        consistencyListeners.add(listener);
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

        Map<String, double[]> baseToRover = new LinkedHashMap<>();
        String[] baseIds = new String[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            BaselineEpoch.BaselineEntry e = entries.get(i);
            baseIds[i] = e.baseId;
            baseToRover.put(e.baseId, e.dXyz);
        }
        consistency.feed(currentEpochTag, baseToRover);
        accumulateNetworkObs(currentEpochTag, entries);
        checkConsistency();

        BaselineEpoch epoch = new BaselineEpoch(currentEpochTag,
                entries.toArray(new BaselineEpoch.BaselineEntry[0]));

        AdjustResult result = GnssBaselineAdjust.adjust(epoch, config.qualityWeight(), adjustPolicy);
        lastAdjustResult = result;

        if (!result.success) return;

        // 白度检验的残差序列必须与"实际参与平差"的基线一一对应：
        // SUSPECT 且嫌疑基线被剔除时，result.v 只含剩余基线的残差，
        // 需同步剔除对应条目，否则长度不匹配会被静默丢弃。
        List<BaselineEpoch.BaselineEntry> whitenedEntries = entries;
        if (result.suspectBaseId != null && result.usedBaselineCount < entries.size()) {
            whitenedEntries = new ArrayList<>();
            for (BaselineEpoch.BaselineEntry e : entries) {
                if (!e.baseId.equals(result.suspectBaseId)) {
                    whitenedEntries.add(e);
                }
            }
        }
        residualWhiteness.feed(whitenedEntries, result.v);

        // 嫌疑基线被剔除的历元（df=0）没有有效平差残差，改喂该基线相对
        // 融合解的交叉检核残差——这是此类历元唯一有信息量的残差序列。
        if (result.suspectBaseId != null && result.usedBaselineCount < entries.size()
                && result.p01Xyz != null) {
            for (BaselineEpoch.BaselineEntry e : entries) {
                if (e.baseId.equals(result.suspectBaseId) && e.dXyz != null && e.dXyz.length >= 3) {
                    residualWhiteness.feedSingle(e.baseId, new double[]{
                            result.p01Xyz[0] - e.dXyz[0],
                            result.p01Xyz[1] - e.dXyz[1],
                            result.p01Xyz[2] - e.dXyz[2]
                    });
                }
            }
        }

        lastCoordinate = ReliableCoordinate.fromAdjustResult(result);
        if (lastCoordinate == null) return;

        if (result.reliabilityGrade == ReliabilityGrade.SUSPECT && result.suspectBaseId != null) {
            for (SuspectListener l : suspectListeners) {
                l.onSuspect(result.suspectBaseId, result.crossValidationDist, currentEpochTag);
            }
        }

        if (diagnoser != null) {
            diagnoser.feedAdjustResult(result, baseIds);
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
        double[] dXyz = CovAssembler.extractBaselineDxyz(solData);
        return dXyz != null ? dXyz : new double[]{0, 0, 0};
    }

    private double[][] extractBaselineCov(SolData solData) {
        double[][] cov = CovAssembler.extractCovariance3x3(solData);
        return withBaseCoordinateSigma(cov != null ? cov : unitCovariance());
    }

    private double[][] extractRoverCov(SolData solData) {
        double[][] cov = CovAssembler.extractCovariance3x3(solData);
        return withBaseCoordinateSigma(cov != null ? cov : unitCovariance());
    }

    /**
     * 把基站坐标 1σ 不确定度按 {@code σ²·I₃} 加到观测协方差块上。
     *
     * <p>core 逐基站独立解算，各基线的基站坐标误差互不相关，因此可直接加到
     * 对应基线的对角块；配置为 0 时原样返回。</p>
     */
    private double[][] withBaseCoordinateSigma(double[][] cov) {
        double sigma = config.baseCoordinateSigma();
        if (cov == null || sigma <= 0) return cov;

        double variance = sigma * sigma;
        double[][] out = new double[3][3];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                out[i][j] = cov[i][j] + (i == j ? variance : 0.0);
            }
        }
        return out;
    }

    private static double[][] unitCovariance() {
        double v = 0.01;
        return new double[][]{{v, 0, 0}, {0, v, 0}, {0, 0, v}};
    }
}