package org.rtklib.java.monitor;

import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.pntpos.PntPos;
import org.rtklib.java.rinex.RinexSppProcessor;
import org.rtklib.java.rtcm.Rtcm;
import org.rtklib.java.time.TimeSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 基站稳定性监测器（独立于RtkProcessor）。
 * <p>
 * 通过对基站观测做SPP定位，按时间窗口聚合中位数，检测基站坐标突变和漂移。
 * 完全独立，不绑定RtkProcessor，由调用方创建和管理生命周期。
 * 1个基站1个实例，多基线共享同一基站时不会重复存储。
 * </p>
 * <p>
 * 支持三种数据输入方式：
 * <ul>
 *   <li>{@link #onBaseObs} - 直接传入Obsd观测数组（最底层，调用方自行解码）</li>
 *   <li>{@link #onRtcmData} - 传入RTCM3原始字节，内部解码</li>
 *   <li>{@link #onRtcmFile} - 传入RTCM3文件路径，内部读取解码</li>
 *   <li>{@link #onRinexFile} - 传入RINEX观测文件路径，内部读取</li>
 * </ul>
 * </p>
 *
 * <pre>
 * // 使用示例1：RTCM实时数据流
 * BaseStationMonitor monitor = new BaseStationMonitor(214120, config, callback);
 * monitor.onRtcmData(rtcmBytes, 0, rtcmBytes.length);
 *
 * // 使用示例2：RTCM文件
 * monitor.onRtcmFile("D:\\data\\BASE_STATION\\2026-07-04\\17.rtcm3");
 *
 * // 使用示例3：RINEX文件
 * monitor.onRinexFile("base.obs", "base.nav");
 * </pre>
 */
public class BaseStationMonitor {

    private static final Logger LOG = LoggerFactory.getLogger(BaseStationMonitor.class);

    private final int staid;
    private final BaseMonitorConfig config;
    private final BaseMonitorCallback callback;

    private final PrcOpt sppOpt;

    private final List<double[]> windowPositions = new ArrayList<>();
    private final List<GTime> windowTimes = new ArrayList<>();
    private GTime windowStart = null;

    private double[] referencePos = null;
    private boolean referenceInitialized = false;

    private final double[][] historyPos;
    private final GTime[] historyTime;
    private int historyCount = 0;
    private int historyIdx = 0;

    private GTime lastEpochTime = null;

    private Rtcm rtcmDecoder = null;

    public BaseStationMonitor(int staid, BaseMonitorConfig config, BaseMonitorCallback callback) {
        this.staid = staid;
        this.config = new BaseMonitorConfig(config);
        this.callback = callback != null ? callback : new BaseMonitorCallback() {};

        this.historyPos = new double[this.config.historySize][3];
        this.historyTime = new GTime[this.config.historySize];

        this.sppOpt = createSppOpt();
    }

    public BaseStationMonitor(int staid, BaseMonitorConfig config) {
        this(staid, config, null);
    }

    public BaseStationMonitor(int staid) {
        this(staid, new BaseMonitorConfig(), null);
    }

    private PrcOpt createSppOpt() {
        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_SINGLE;
        opt.nf = 1;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL | Constants.SYS_CMP;
        opt.elmin = config.minElMaskDeg * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;
        opt.modear = Constants.ARMODE_OFF;
        opt.outsingle = 1;
        return opt;
    }

    // ================================================================
    //  数据输入接口
    // ================================================================

    /**
     * 喂入基站观测数据（最底层接口，每历元调用一次）。
     * 内部执行SPP、窗口聚合、突变检测、漂移检测。
     *
     * @param obs  观测数据数组（基站观测在obs[0..n-1]）
     * @param n    观测数据数量
     * @param nav  导航星历
     * @param time 当前历元时间
     */
    public void onBaseObs(Obsd[] obs, int n, Nav nav, GTime time) {
        if (obs == null || n <= 0 || time == null) return;

        if (lastEpochTime != null && TimeSystem.timediff(time, lastEpochTime) == 0.0) return;
        lastEpochTime = new GTime(time);

        if (windowStart == null) {
            windowStart = new GTime(time);
        }

        Obsd[] baseObs = filterObservations(obs, n);
        if (baseObs.length < config.minSatCount) return;

        Sol sol = new Sol();
        int result = PntPos.pntpos(baseObs, baseObs.length, nav, sppOpt, sol, null, null);
        if (result == 0 || sol.stat == Constants.SOLQ_NONE) return;

        if (sol.ns < config.minSatCount) return;
        if (sol.pdop > config.maxPdop) return;

        double[] pos = new double[]{sol.rr[0], sol.rr[1], sol.rr[2]};
        windowPositions.add(pos);
        windowTimes.add(new GTime(time));

        callback.onSppResult(staid, time, pos, sol.ns, sol.pdop);

        double elapsedMin = TimeSystem.timediff(time, windowStart) / 60.0;
        if (elapsedMin >= config.windowMinutes) {
            processWindow(time);
        }
    }

    /**
     * 喂入RTCM3原始字节数据。
     * 内部维护Rtcm解码器和Nav星历，自动解码观测和星历消息。
     * 可多次调用，适合实时数据流场景。
     *
     * @param data   RTCM3原始字节
     * @param offset 起始偏移
     * @param length 字节长度
     */
    public void onRtcmData(byte[] data, int offset, int length) {
        if (data == null || length <= 0) return;

        if (rtcmDecoder == null) {
            rtcmDecoder = new Rtcm();
        }

        int pos = offset;
        int end = offset + length;
        while (pos < end) {
            int consumed = rtcmDecoder.input(data, pos, end - pos);
            pos += consumed;
            if (consumed <= 0) break;

            if (rtcmDecoder.obsflag == 1 && rtcmDecoder.obs.n > 0) {
                Obsd[] obs = new Obsd[rtcmDecoder.obs.n];
                for (int i = 0; i < rtcmDecoder.obs.n; i++) {
                    obs[i] = rtcmDecoder.obs.data[i];
                }
                GTime time = obs[0].time;
                onBaseObs(obs, obs.length, rtcmDecoder.nav, time);
                rtcmDecoder.obsflag = 0;
            }
        }
    }

    /**
     * 喂入RTCM3原始字节数据（便捷方法，从偏移0开始）。
     *
     * @param data RTCM3原始字节
     */
    public void onRtcmData(byte[] data) {
        if (data == null) return;
        onRtcmData(data, 0, data.length);
    }

    /**
     * 喂入RTCM3文件。
     * 读取文件全部内容后解码，适合离线批量分析场景。
     * 可多次调用不同文件，内部Rtcm解码器和Nav星历持续累积。
     *
     * @param filePath RTCM3文件路径
     * @throws IOException 文件读取失败
     */
    public void onRtcmFile(String filePath) throws IOException {
        if (filePath == null || !Files.exists(Paths.get(filePath))) {
            LOG.warn("RTCM文件不存在: {}", filePath);
            return;
        }
        byte[] data = Files.readAllBytes(Paths.get(filePath));
        onRtcmData(data);
    }

    /**
     * 喂入RINEX观测文件。
     * 读取RINEX O+N文件，逐历元SPP后送入监测流水线。
     * 内部使用RinexSppProcessor读取，不依赖RTCM解码器。
     *
     * @param obsFilePath RINEX观测文件路径（.obs）
     * @param navFilePath RINEX导航文件路径（.nav），可为null（使用广播星历文件同名替换）
     * @throws IOException 文件读取失败
     */
    public void onRinexFile(String obsFilePath, String navFilePath) throws IOException {
        if (obsFilePath == null || !Files.exists(Paths.get(obsFilePath))) {
            LOG.warn("RINEX观测文件不存在: {}", obsFilePath);
            return;
        }

        PrcOpt rinexOpt = new PrcOpt(sppOpt);
        RinexSppProcessor spp = new RinexSppProcessor(rinexOpt);
        RinexSppProcessor.SppResult result = spp.process(obsFilePath, navFilePath);

        for (SolData sd : result.solutions) {
            if (sd.status == null) continue;
            Position p = sd.getPosition(CoordType.ECEF);
            if (p == null) continue;
            double[] pos = new double[]{p.v1, p.v2, p.v3};
            GTime time = sd.time;

            if (lastEpochTime != null && TimeSystem.timediff(time, lastEpochTime) == 0.0) continue;
            lastEpochTime = new GTime(time);

            if (windowStart == null) {
                windowStart = new GTime(time);
            }

            windowPositions.add(pos);
            windowTimes.add(new GTime(time));

            callback.onSppResult(staid, time, pos, sd.numSat, sd.pdop);

            double elapsedMin = TimeSystem.timediff(time, windowStart) / 60.0;
            if (elapsedMin >= config.windowMinutes) {
                processWindow(time);
            }
        }
    }

    // ================================================================
    //  内部处理
    // ================================================================

    private Obsd[] filterObservations(Obsd[] obs, int n) {
        List<Obsd> filtered = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            if (obs[i].SNR == null || obs[i].SNR.length < 1) continue;
            if (obs[i].SNR[0] < config.minSnrDbHz) continue;
            filtered.add(obs[i]);
        }
        return filtered.toArray(new Obsd[0]);
    }

    private void processWindow(GTime windowEndTime) {
        if (windowPositions.isEmpty()) {
            resetWindow();
            return;
        }

        double[] median = computeMedian(windowPositions);

        List<double[]> cleaned = removeOutliers(windowPositions, median);
        if (cleaned.size() < config.minValidInWindow) {
            LOG.warn("基站[{}] 窗口有效历元不足({}/{}), 跳过检测",
                    staid, cleaned.size(), config.minValidInWindow);
            resetWindow();
            return;
        }

        median = computeMedian(cleaned);

        if (!referenceInitialized) {
            referencePos = median.clone();
            referenceInitialized = true;
            LOG.info("基站[{}] 参考基准初始化: [{}, {}, {}]",
                    staid,
                    String.format("%.1f", referencePos[0]),
                    String.format("%.1f", referencePos[1]),
                    String.format("%.1f", referencePos[2]));
        }

        double offset = distance(median, referencePos);

        callback.onWindowResult(staid, windowEndTime, median.clone(), offset);

        if (offset > config.movementThresh) {
            String msg = String.format("基站[%d] 可能移动! 偏移 %.1fm (阈值 %.1fm)",
                    staid, offset, config.movementThresh);
            LOG.warn(msg);
            callback.onBaseMovement(staid, windowEndTime, median.clone(),
                    referencePos.clone(), offset, msg);
        }

        addToHistory(median, windowEndTime);

        checkDrift(windowEndTime);

        resetWindow();
    }

    private double[] computeMedian(List<double[]> positions) {
        int n = positions.size();
        double[] result = new double[3];
        for (int axis = 0; axis < 3; axis++) {
            double[] vals = new double[n];
            for (int i = 0; i < n; i++) {
                vals[i] = positions.get(i)[axis];
            }
            Arrays.sort(vals);
            if (n % 2 == 0) {
                result[axis] = (vals[n / 2 - 1] + vals[n / 2]) / 2.0;
            } else {
                result[axis] = vals[n / 2];
            }
        }
        return result;
    }

    private List<double[]> removeOutliers(List<double[]> positions, double[] median) {
        List<double[]> cleaned = new ArrayList<>();
        for (double[] pos : positions) {
            if (distance(pos, median) <= config.outlierThresh) {
                cleaned.add(pos);
            }
        }
        return cleaned;
    }

    private void addToHistory(double[] median, GTime time) {
        historyPos[historyIdx][0] = median[0];
        historyPos[historyIdx][1] = median[1];
        historyPos[historyIdx][2] = median[2];
        historyTime[historyIdx] = new GTime(time);
        historyIdx = (historyIdx + 1) % config.historySize;
        if (historyCount < config.historySize) {
            historyCount++;
        }
    }

    private void checkDrift(GTime currentTime) {
        if (historyCount < 2) return;

        int oldestIdx = (historyIdx - historyCount + config.historySize) % config.historySize;
        double[] oldest = historyPos[oldestIdx];
        GTime oldestTime = historyTime[oldestIdx];

        int newestIdx = (historyIdx - 1 + config.historySize) % config.historySize;
        double[] newest = historyPos[newestIdx];

        double drift = distance(oldest, newest);
        double hours = TimeSystem.timediff(currentTime, oldestTime) / 3600.0;

        if (drift > config.driftThresh && hours > 0) {
            String msg = String.format("基站[%d] 可能漂移! %.1fm / %.1fh (阈值 %.1fm)",
                    staid, drift, hours, config.driftThresh);
            LOG.warn(msg);
            callback.onBaseDrift(staid, currentTime, drift, hours, msg);
        }
    }

    private void resetWindow() {
        windowPositions.clear();
        windowTimes.clear();
        windowStart = null;
    }

    private double distance(double[] a, double[] b) {
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        double dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    // ================================================================
    //  只读查询
    // ================================================================

    public int getStaid() {
        return staid;
    }

    public double[] getReferencePos() {
        return referencePos != null ? referencePos.clone() : null;
    }

    public boolean isReferenceInitialized() {
        return referenceInitialized;
    }

    public int getHistoryCount() {
        return historyCount;
    }

    public double[] getHistoryPos(int i) {
        if (i < 0 || i >= historyCount) return null;
        int idx = (historyIdx - historyCount + i + config.historySize) % config.historySize;
        return historyPos[idx].clone();
    }

    public GTime getHistoryTime(int i) {
        if (i < 0 || i >= historyCount) return null;
        int idx = (historyIdx - historyCount + i + config.historySize) % config.historySize;
        return historyTime[idx];
    }

    public int getWindowPositionCount() {
        return windowPositions.size();
    }

    // ================================================================
    //  控制
    // ================================================================

    public void reset() {
        resetWindow();
        referencePos = null;
        referenceInitialized = false;
        historyCount = 0;
        historyIdx = 0;
        lastEpochTime = null;
        rtcmDecoder = null;
        LOG.info("基站[{}] 监测状态已重置", staid);
    }

    public void resetReference() {
        referencePos = null;
        referenceInitialized = false;
        LOG.info("基站[{}] 参考基准已重置, 下次窗口将重新自学习", staid);
    }
}