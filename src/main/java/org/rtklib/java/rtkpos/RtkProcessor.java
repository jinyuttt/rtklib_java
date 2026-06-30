package org.rtklib.java.rtkpos;

import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.pntpos.PosHandler;
import org.rtklib.java.rtcm.Rtcm;
import org.rtklib.java.time.TimeSystem;
import org.rtklib.java.trace.TraceCallback;
import org.rtklib.java.trace.TraceControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.rtklib.java.common.CompatFileIO;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * RTK相对定位处理器。
 * <p>
 * 流水线处理：feed()每解析出一个观测历元就立即定位，通过回调或输出流实时输出结果。
 * finish()仅表示数据输入结束，处理剩余缓存历元并通知完成。
 * </p>
 * <p>
 * 与SppProcessor不同，RTK需要流动站和基准站的观测数据。
 * RTCM数据流中，流动站观测（rcv=1）和基准站观测（rcv=2）通过RTCM消息中的
 * 基准站天线坐标（1005/1006）自动区分。基准站坐标从RTCM消息中提取，
 * 也可通过PrcOpt.rb手动设置。
 * </p>
 * <p>
 * 输出支持（独立工作，只要存在就使用）：
 * <ul>
 *   <li>OutputStream：定位结果以.pos格式逐行写入并刷新，可为null</li>
 *   <li>PosHandler回调：实时通知定位结果/失败/完成，可为null</li>
 * </ul>
 * </p>
 * <p>
 * 注意：RtkProcessor不可重用，调用finish()后即完成生命周期。
 * </p>
 *
 * <pre>
 * // 示例1：批量模式（RTCM文件）
 * RtkProcessor rtk = new RtkProcessor(opt, handler, outputStream);
 * RtkResult result = rtk.process("rover_rtcm", "base_rtcm");
 *
 * // 示例2：批量模式（单RTCM流，含流动站+基准站数据）
 * RtkProcessor rtk = new RtkProcessor(opt, handler, outputStream);
 * RtkResult result = rtk.process("combined.rtcm");
 *
 * // 示例3：流式模式（网络实时数据）
 * RtkProcessor rtk = new RtkProcessor(opt, handler, outputStream);
 * while (running) {
 *     byte[] roverChunk = roverStream.read();
 *     byte[] baseChunk = baseStream.read();
 *     rtk.feedRover(roverChunk);
 *     rtk.feedBase(baseChunk);
 * }
 * RtkResult result = rtk.finish();
 *
 * // 示例4：byte[]输入
 * RtkProcessor rtk = new RtkProcessor(opt);
 * RtkResult result = rtk.process(roverBytes, baseBytes);
 * </pre>
 */
public class RtkProcessor {

    private static final Logger log = LoggerFactory.getLogger(RtkProcessor.class);

    private static final String POS_HEADER =
            "# RTK (Relative Positioning) Result\n" +
            "#  Date       Time       lat(deg)      lon(deg)     height(m)  Q  ns   sdn(m)   sde(m)   sdu(m)  sdne(m)  sdeu(m)  sdun(m) age(s)  ratio\n";

    private PrcOpt opt;
    private final PosHandler handler;
    private final Writer writer;

    private final Rtk rtk;
    private final Rtcm rtcmRover = new Rtcm();
    private final Rtcm rtcmBase = new Rtcm();

    private boolean hasBasePos = false;
    private boolean ephReady = false;
    private boolean finished = false;

    private final List<Obsd[]> pendingRoverObsList = new ArrayList<>();
    private final List<Integer> pendingRoverObsCountList = new ArrayList<>();
    private final List<GTime> pendingRoverObsTimeList = new ArrayList<>();

    private final List<Obsd[]> pendingBaseObsList = new ArrayList<>();
    private final List<Integer> pendingBaseObsCountList = new ArrayList<>();
    private final List<GTime> pendingBaseObsTimeList = new ArrayList<>();

    private final Map<Integer, Integer> ephTypeCount = new TreeMap<>();
    private int totalEpochs = 0;
    private int successCount = 0;
    private int failCount = 0;
    private int outputCount = 0;
    private int sleepInterval = 100;
    private int sleepMs = 10;
    private final List<Sol> solutions = new ArrayList<>();

    private byte[] pendingRover = new byte[4096];
    private int pendingRoverLen = 0;
    private byte[] pendingBase = new byte[4096];
    private int pendingBaseLen = 0;

    /**
     * 构造RTK处理器。
     *
     * @param opt          定位处理选项，内部会进行深拷贝
     * @param handler      定位结果回调，可为null
     * @param outputStream 输出流，可为null。定位结果将以.pos格式逐行写入并刷新
     */
    public RtkProcessor(PrcOpt opt, PosHandler handler, OutputStream outputStream) {
        this.opt = new PrcOpt(opt);
        this.handler = handler;
        this.rtk = new Rtk();
        this.rtk.opt = this.opt;
        initFromOpt();

        if (outputStream != null) {
            this.writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
            try {
                this.writer.write(POS_HEADER);
                this.writer.flush();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        } else {
            this.writer = null;
        }
    }

    public RtkProcessor(PrcOpt opt, PosHandler handler) {
        this(opt, handler, null);
    }

    public RtkProcessor(PrcOpt opt) {
        this(opt, null, null);
    }

    public RtkProcessor() {
        this(createDefaultOpt(), null, null);
    }

    public void setOutputThrottle(int interval, int sleepMs) {
        this.sleepInterval = interval;
        this.sleepMs = sleepMs;
    }

    private void initFromOpt() {
        if (opt != null) {
            this.sleepInterval = opt.outputThrottleInterval;
            this.sleepMs = opt.outputThrottleSleepMs;
        }
    }

    public static PrcOpt createDefaultOpt() {
        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_KINEMA;
        opt.nf = 3;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL | Constants.SYS_CMP;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_EST;
        opt.modear = Constants.ARMODE_OFF;
        opt.refposmode = Constants.REFPOS_RTCM;
        opt.procmode = Constants.PROCMODE_POST;
        opt.intpref = 1;
        opt.maxtdiff = 30.0;
        opt.outsingle = 1;
        opt.outputThrottleInterval = 100;
        opt.outputThrottleSleepMs = 10;
        return opt;
    }

    public void setTraceControl(TraceControl traceControl) {
        rtk.traceControl = traceControl;
    }

    public void setTraceCallback(TraceCallback traceCallback) {
        rtk.traceCallback = traceCallback;
    }

    public void setBasePosition(double[] pos) {
        if (pos != null && pos.length >= 3) {
            System.arraycopy(pos, 0, rtk.rb, 0, 3);
            System.arraycopy(pos, 0, opt.rb, 0, 3);
            rtk.opt.rb = opt.rb;
            hasBasePos = true;
        }
    }

    public void setMode(int mode) { opt.mode = mode; rtk.opt.mode = mode; }

    public void setNavsys(int navsys) { opt.navsys = navsys; rtk.opt.navsys = navsys; }

    public void setNumFreq(int nf) { opt.nf = nf; rtk.opt.nf = nf; }

    public void setElevationMask(double elminDeg) {
        opt.elmin = elminDeg * Constants.D2R;
        rtk.opt.elmin = opt.elmin;
    }

    public void setIonoOpt(int ionoopt) { opt.ionoopt = ionoopt; rtk.opt.ionoopt = ionoopt; }

    public void setTropOpt(int tropopt) { opt.tropopt = tropopt; rtk.opt.tropopt = tropopt; }

    public void setArMode(int modear) { opt.modear = modear; rtk.opt.modear = modear; }

    public void setRefPosMode(int refposmode) { opt.refposmode = refposmode; rtk.opt.refposmode = refposmode; }

    public void setProcMode(int procmode) { opt.procmode = procmode; rtk.opt.procmode = procmode; }

    public void setDynamics(int dynamics) { opt.dynamics = dynamics; rtk.opt.dynamics = dynamics; }

    public void setOutSingle(int outsingle) { opt.outsingle = outsingle; rtk.opt.outsingle = outsingle; }

    public void setIntPref(int intpref) { opt.intpref = intpref; rtk.opt.intpref = intpref; }

    public void setMaxTdiff(double maxtdiff) { opt.maxtdiff = maxtdiff; rtk.opt.maxtdiff = maxtdiff; }

    public void setMinLock(int minlock) { opt.minlock = minlock; rtk.opt.minlock = minlock; }

    public void setMinFix(int minfix) { opt.minfix = minfix; rtk.opt.minfix = minfix; }

    public void setMinFixSats(int minfixsats) { opt.minfixsats = minfixsats; rtk.opt.minfixsats = minfixsats; }

    public void setMinHoldSats(int minholdsats) { opt.minholdsats = minholdsats; rtk.opt.minholdsats = minholdsats; }

    public void setElMaskAr(double elmaskarDeg) {
        opt.elmaskar = elmaskarDeg * Constants.D2R;
        rtk.opt.elmaskar = opt.elmaskar;
    }

    public void setElMaskHold(double elmaskholdDeg) {
        opt.elmaskhold = elmaskholdDeg * Constants.D2R;
        rtk.opt.elmaskhold = opt.elmaskhold;
    }

    public void setMaxOutage(int maxout) { opt.maxout = maxout; rtk.opt.maxout = maxout; }

    public void setArFilter(int arfilter) { opt.arfilter = arfilter; rtk.opt.arfilter = arfilter; }

    public void setRatioThreshold(double ratio) {
        if (opt.thresar == null || opt.thresar.length == 0) opt.thresar = new double[8];
        opt.thresar[1] = ratio;
        rtk.opt.thresar = opt.thresar.clone();
    }

    public void setBaselineConstraint(double length, double sigma) {
        opt.baseline = new double[]{length, sigma};
        rtk.opt.baseline = opt.baseline.clone();
    }

    public void setExcludedSats(int[] exsats) {
        opt.exsats = exsats.clone();
        rtk.opt.exsats = opt.exsats.clone();
    }

    public PrcOpt getOpt() { return opt; }

    public void feedRover(byte[] data, int offset, int length) {
        if (finished) {
            throw new IllegalStateException("Processor already finished");
        }
        if (length <= 0) return;

        pendingRover = ensureCapacity(pendingRover, pendingRoverLen + length);
        System.arraycopy(data, offset, pendingRover, pendingRoverLen, length);
        pendingRoverLen += length;

        int pos = 0;
        while (pos < pendingRoverLen) {
            int consumed = rtcmRover.input(pendingRover, pos, pendingRoverLen - pos);
            if (consumed > 0) {
                onRtcmRoverMessage();
                pos += consumed;
            } else if (consumed == 0) {
                break;
            } else {
                pos++;
            }
        }

        if (pos > 0) {
            if (pos < pendingRoverLen) {
                System.arraycopy(pendingRover, pos, pendingRover, 0, pendingRoverLen - pos);
            }
            pendingRoverLen -= pos;
        }

        tryProcessPending();
    }

    public void feedRover(byte[] data) {
        feedRover(data, 0, data.length);
    }

    public void feedBase(byte[] data, int offset, int length) {
        if (finished) {
            throw new IllegalStateException("Processor already finished");
        }
        if (length <= 0) return;

        pendingBase = ensureCapacity(pendingBase, pendingBaseLen + length);
        System.arraycopy(data, offset, pendingBase, pendingBaseLen, length);
        pendingBaseLen += length;

        int pos = 0;
        while (pos < pendingBaseLen) {
            int consumed = rtcmBase.input(pendingBase, pos, pendingBaseLen - pos);
            if (consumed > 0) {
                onRtcmBaseMessage();
                pos += consumed;
            } else if (consumed == 0) {
                break;
            } else {
                pos++;
            }
        }

        if (pos > 0) {
            if (pos < pendingBaseLen) {
                System.arraycopy(pendingBase, pos, pendingBase, 0, pendingBaseLen - pos);
            }
            pendingBaseLen -= pos;
        }

        tryProcessPending();
    }

    public void feedBase(byte[] data) {
        feedBase(data, 0, data.length);
    }

    private void onRtcmRoverMessage() {
    int type = rtcmRover.type;
    if (isEphemerisType(type) && rtcmRover.ephsat != 0) {
        ephTypeCount.merge(type, 1, Integer::sum);
        ephReady = true;
    }
    if ((type == 1005 || type == 1006) && rtcmRover.sta != null) {
        if (!hasBasePos) {
            System.arraycopy(rtcmRover.sta.pos, 0, rtk.rb, 0, 3);
            hasBasePos = true;
            log.info("Base station position from rover RTCM {} (single-stream): ({}, {}, {})",
                    type, rtk.rb[0], rtk.rb[1], rtk.rb[2]);
        }
    }
    if (isObsType(type) && rtcmRover.obs.n > 0 && rtcmRover.obsflag == 1) {
            int n = rtcmRover.obs.n;
            Obsd[] obsCopy = new Obsd[n];
            for (int i = 0; i < n; i++) {
                obsCopy[i] = copyObsd(rtcmRover.obs.data[i]);
                obsCopy[i].rcv = 1;
            }
            pendingRoverObsList.add(obsCopy);
            pendingRoverObsCountList.add(n);
            pendingRoverObsTimeList.add(rtcmRover.obs.data[0].time);
        }
    }

    private void onRtcmBaseMessage() {
        int type = rtcmBase.type;
        if (isEphemerisType(type) && rtcmBase.ephsat != 0) {
            ephTypeCount.merge(type, 1, Integer::sum);
            ephReady = true;
        }
        if ((type == 1005 || type == 1006) && rtcmBase.sta != null) {
            if (!hasBasePos) {
                System.arraycopy(rtcmBase.sta.pos, 0, rtk.rb, 0, 3);
                hasBasePos = true;
                log.info("Base station position from RTCM {}: ({}, {}, {})",
                        type, rtk.rb[0], rtk.rb[1], rtk.rb[2]);
            }
        }
        if (isObsType(type) && rtcmBase.obs.n > 0 && rtcmBase.obsflag == 1) {
            int n = rtcmBase.obs.n;
            Obsd[] obsCopy = new Obsd[n];
            for (int i = 0; i < n; i++) {
                obsCopy[i] = copyObsd(rtcmBase.obs.data[i]);
                obsCopy[i].rcv = 2;
            }
            pendingBaseObsList.add(obsCopy);
            pendingBaseObsCountList.add(n);
            pendingBaseObsTimeList.add(rtcmBase.obs.data[0].time);
        }
    }

    private void tryProcessPending() {
        if (!ephReady) return;
        if (pendingRoverObsList.isEmpty()) return;

        int ephWeek = findEphWeek();
        if (ephWeek > 0) {
            correctObsWeek(pendingRoverObsList, pendingRoverObsTimeList, ephWeek);
            correctObsWeek(pendingBaseObsList, pendingBaseObsTimeList, ephWeek);
        }

        while (!pendingRoverObsList.isEmpty()) {
            Obsd[] roverObs = pendingRoverObsList.remove(0);
            int roverN = pendingRoverObsCountList.remove(0);
            GTime roverTime = pendingRoverObsTimeList.remove(0);

            Obsd[] baseObs = findMatchingBaseObs(roverTime);
            int baseN = (baseObs != null) ? baseObs.length : 0;

            Obsd[] combined = new Obsd[roverN + baseN];
            System.arraycopy(roverObs, 0, combined, 0, roverN);
            if (baseObs != null) {
                System.arraycopy(baseObs, 0, combined, roverN, baseN);
            }

            Nav nav = rtcmRover.nav;
            mergeNav(nav, rtcmBase.nav);
            processEpoch(combined, roverN + baseN, roverTime, nav);
        }
    }

    private Obsd[] findMatchingBaseObs(GTime roverTime) {
        if (pendingBaseObsList.isEmpty()) return null;

        double bestDt = Double.MAX_VALUE;
        int bestIdx = -1;
        double maxTdiff = opt.maxtdiff > 0 ? opt.maxtdiff : 30.0;

        for (int i = 0; i < pendingBaseObsTimeList.size(); i++) {
            double dt = Math.abs(TimeSystem.timediff(roverTime, pendingBaseObsTimeList.get(i)));
            if (dt < bestDt && dt <= maxTdiff) {
                bestDt = dt;
                bestIdx = i;
            }
        }

        if (bestIdx < 0) return null;

        Obsd[] result = pendingBaseObsList.remove(bestIdx);
        pendingBaseObsCountList.remove(bestIdx);
        pendingBaseObsTimeList.remove(bestIdx);
        return result;
    }

    private void processEpoch(Obsd[] obs, int n, GTime time, Nav nav) {
        totalEpochs++;


        int result = RtkCore.rtkpos(rtk, obs, n, nav);

        if (result == 1 && rtk.sol.stat != Constants.SOLQ_NONE) {
            successCount++;
            Sol solCopy = new Sol(rtk.sol);
            solutions.add(solCopy);

            if (handler != null) {
                handler.onSolution(new Sol(rtk.sol), copySsatArray(rtk.ssat));
            }
            if (writer != null) {
                try {
                    writer.write(formatSolutionLine(solCopy));
                    writer.flush();
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        } else {
            failCount++;
            if (handler != null) {
                handler.onPosFail(time, "RTK positioning failed");
            }
        }

        outputCount++;
        if (sleepInterval > 0 && outputCount % sleepInterval == 0) {
            try { Thread.sleep(sleepMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private int findEphWeek() {
        Nav[] navs = {rtcmRover.nav, rtcmBase.nav};
        for (Nav nav : navs) {
            for (int i = 0; i < nav.eph.length; i++) {
                if (nav.eph[i] != null && nav.eph[i].A > 0) {
                    int[] wkArr = new int[1];
                    TimeSystem.time2gpst(nav.eph[i].toe, wkArr);
                    if (wkArr[0] > 0) return wkArr[0];
                }
            }
        }
        return -1;
    }

    private void correctObsWeek(List<Obsd[]> obsList, List<GTime> timeList, int ephWeek) {
        for (int i = 0; i < timeList.size(); i++) {
            GTime ot = timeList.get(i);
            int[] wkArr = new int[1];
            double sow = TimeSystem.time2gpst(ot, wkArr);
            if (wkArr[0] == 0 && sow > 0) {
                GTime corrected = TimeSystem.gpst2time(ephWeek, sow);
                timeList.set(i, corrected);
                for (int j = 0; j < obsList.get(i).length; j++) {
                    obsList.get(i)[j].time = corrected;
                }
            }
        }
    }

    private void mergeNav(Nav dst, Nav src) {
        for (int i = 0; i < src.eph.length; i++) {
            if (src.eph[i] != null && src.eph[i].A > 0) {
                int sat = src.eph[i].sat;
                if (sat > 0 && sat <= dst.eph.length) {
                    if (dst.eph[sat - 1] == null || dst.eph[sat - 1].A <= 0) {
                        dst.eph[sat - 1] = src.eph[i];
                    }
                }
            }
        }
    }

    public RtkResult process(byte[] roverData, byte[] baseData) {
        if (finished) {
            throw new IllegalStateException("Processor already finished");
        }

        Rtcm batchRtcmRover = new Rtcm();
        Rtcm batchRtcmBase = new Rtcm();
        List<Obsd[]> batchRoverObsList = new ArrayList<>();
        List<Integer> batchRoverObsCountList = new ArrayList<>();
        List<GTime> batchRoverObsTimeList = new ArrayList<>();
        List<Obsd[]> batchBaseObsList = new ArrayList<>();
        List<Integer> batchBaseObsCountList = new ArrayList<>();
        List<GTime> batchBaseObsTimeList = new ArrayList<>();
        double[] batchBasePos = new double[3];
        boolean batchHasBasePos = false;
        Map<Integer, Integer> batchEphTypeCount = new TreeMap<>();

        parseRtcmBatch(roverData, batchRtcmRover, batchRoverObsList,
                batchRoverObsCountList, batchRoverObsTimeList,
                batchEphTypeCount, 1);

        if (baseData != null) {
        parseRtcmBatch(baseData, batchRtcmBase, batchBaseObsList,
                batchBaseObsCountList, batchBaseObsTimeList,
                batchEphTypeCount, 2);

        if (batchRtcmBase.sta != null &&
                (batchRtcmBase.sta.pos[0] != 0 || batchRtcmBase.sta.pos[1] != 0 || batchRtcmBase.sta.pos[2] != 0)) {
            System.arraycopy(batchRtcmBase.sta.pos, 0, batchBasePos, 0, 3);
            batchHasBasePos = true;
        }
    }

    if (!batchHasBasePos && batchRtcmRover.sta != null &&
                (batchRtcmRover.sta.pos[0] != 0 || batchRtcmRover.sta.pos[1] != 0 || batchRtcmRover.sta.pos[2] != 0)) {
            System.arraycopy(batchRtcmRover.sta.pos, 0, batchBasePos, 0, 3);
            batchHasBasePos = true;
            log.info("Base station position from rover RTCM (single-stream): ({}, {}, {})",
                    batchBasePos[0], batchBasePos[1], batchBasePos[2]);
        }

        int ephWeek = -1;
        Nav[] navs = {batchRtcmRover.nav, batchRtcmBase.nav};
        for (Nav nav : navs) {
            for (int i = 0; i < nav.eph.length; i++) {
                if (nav.eph[i] != null && nav.eph[i].A > 0) {
                    int[] wkArr = new int[1];
                    TimeSystem.time2gpst(nav.eph[i].toe, wkArr);
                    if (wkArr[0] > 0) {
                        ephWeek = wkArr[0];
                        break;
                    }
                }
            }
            if (ephWeek > 0) break;
        }

        if (ephWeek > 0) {
            correctObsWeek(batchRoverObsList, batchRoverObsTimeList, ephWeek);
            correctObsWeek(batchBaseObsList, batchBaseObsTimeList, ephWeek);
        }

        if (batchHasBasePos && !hasBasePos) {
            System.arraycopy(batchBasePos, 0, rtk.rb, 0, 3);
            hasBasePos = true;
        }

        mergeNav(batchRtcmRover.nav, batchRtcmBase.nav);

        log.info("RTK Batch: roverEpochs={}, baseEpochs={}, ephTypes={}",
                batchRoverObsList.size(), batchBaseObsList.size(), batchEphTypeCount);

        for (int epoch = 0; epoch < batchRoverObsList.size(); epoch++) {
            Obsd[] roverObs = batchRoverObsList.get(epoch);
            int roverN = batchRoverObsCountList.get(epoch);
            GTime roverTime = batchRoverObsTimeList.get(epoch);

            Obsd[] baseObs = findMatchingBaseObsFromList(
                    batchBaseObsList, batchBaseObsCountList, batchBaseObsTimeList, roverTime);
            int baseN = (baseObs != null) ? baseObs.length : 0;

            Obsd[] combined = new Obsd[roverN + baseN];
            System.arraycopy(roverObs, 0, combined, 0, roverN);
            if (baseObs != null) {
                System.arraycopy(baseObs, 0, combined, roverN, baseN);
            }

            mergeNav(batchRtcmRover.nav, batchRtcmBase.nav);
            processEpoch(combined, roverN + baseN, roverTime, batchRtcmRover.nav);
        }

        rtcmRover.nav = batchRtcmRover.nav;
        rtcmBase.nav = batchRtcmBase.nav;
        ephReady = true;
        finished = true;
        ephTypeCount.putAll(batchEphTypeCount);

        return finishInternal();
    }

    public RtkResult process(byte[] data) {
        return process(data, null);
    }

    public RtkResult process(String roverFilePath, String baseFilePath) throws IOException {
        byte[] roverData = CompatFileIO.readAllBytes(roverFilePath);
        byte[] baseData = (baseFilePath != null) ? CompatFileIO.readAllBytes(baseFilePath) : null;
        return process(roverData, baseData);
    }

    public RtkResult process(String filePath) throws IOException {
        byte[] data = CompatFileIO.readAllBytes(filePath);
        return process(data);
    }

    public RtkResult finish() {
        if (finished) {
            throw new IllegalStateException("Processor already finished");
        }
        finished = true;

        if (ephReady && !pendingRoverObsList.isEmpty()) {
            tryProcessPending();
        }

        for (int i = 0; i < pendingRoverObsList.size(); i++) {
            totalEpochs++;
            failCount++;
            if (handler != null) {
                handler.onPosFail(pendingRoverObsTimeList.get(i), "No ephemeris or no base data");
            }
        }
        pendingRoverObsList.clear();
        pendingRoverObsCountList.clear();
        pendingRoverObsTimeList.clear();
        pendingBaseObsList.clear();
        pendingBaseObsCountList.clear();
        pendingBaseObsTimeList.clear();

        return finishInternal();
    }

    private RtkResult finishInternal() {
        log.info("RTK complete: total={}, success={}, fail={}, ephTypes={}",
                totalEpochs, successCount, failCount, ephTypeCount);

        if (writer != null) {
            try {
                writer.write(String.format("# Total: %d, Success: %d, Fail: %d\n",
                        totalEpochs, successCount, failCount));
                writer.flush();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        if (handler != null) {
            handler.onFinish(totalEpochs, successCount, failCount);
        }

        return new RtkResult(totalEpochs, successCount, failCount, solutions);
    }

    public void reset() {
        rtk.sol = new Sol();
        rtk.nx = 0;
        rtk.na = 0;
        rtk.x = new double[Constants.NX_RTK];
        rtk.P = new double[Constants.NX_RTK * Constants.NX_RTK];
        rtk.xa = new double[Constants.NX_RTK];
        rtk.Pa = new double[Constants.NX_RTK * Constants.NX_RTK];
        rtk.nfix = 0;
        rtk.nepoch = new int[2];
        rtk.epoch = 0;
        rtk.tt = 0.0;
        rtk.intpres_nb = 0;
        for (int i = 0; i < Constants.MAXSAT; i++) {
            rtk.ssat[i] = new Ssat();
        }

        pendingRoverObsList.clear();
        pendingRoverObsCountList.clear();
        pendingRoverObsTimeList.clear();
        pendingBaseObsList.clear();
        pendingBaseObsCountList.clear();
        pendingBaseObsTimeList.clear();

        totalEpochs = 0;
        successCount = 0;
        failCount = 0;
        solutions.clear();

        pendingRoverLen = 0;
        pendingBaseLen = 0;
        finished = false;
    }

    public Rtk getRtk() {
        return rtk;
    }

    private void parseRtcmBatch(byte[] data, Rtcm rtcm,
                                List<Obsd[]> obsList, List<Integer> obsCountList,
                                List<GTime> obsTimeList,
                                Map<Integer, Integer> ephCount, int rcv) {
        int offset = 0;
        while (offset < data.length) {
            int consumed = rtcm.input(data, offset, data.length - offset);
            if (consumed <= 0) {
                offset++;
                continue;
            }
            offset += consumed;

            int type = rtcm.type;
            if (isEphemerisType(type) && rtcm.ephsat != 0) {
                ephCount.merge(type, 1, Integer::sum);
            }
            if (isObsType(type) && rtcm.obs.n > 0 && rtcm.obsflag == 1) {
                int n = rtcm.obs.n;
                Obsd[] obsCopy = new Obsd[n];
                for (int i = 0; i < n; i++) {
                    obsCopy[i] = copyObsd(rtcm.obs.data[i]);
                    obsCopy[i].rcv = rcv;
                }
                obsList.add(obsCopy);
                obsCountList.add(n);
                obsTimeList.add(rtcm.obs.data[0].time);
            }
        }
    }

    private Obsd[] findMatchingBaseObsFromList(List<Obsd[]> baseObsList,
                                                List<Integer> baseObsCountList,
                                                List<GTime> baseObsTimeList,
                                                GTime roverTime) {
        if (baseObsList.isEmpty()) return null;

        double bestDt = Double.MAX_VALUE;
        int bestIdx = -1;
        double maxTdiff = opt.maxtdiff > 0 ? opt.maxtdiff : 30.0;

        for (int i = 0; i < baseObsTimeList.size(); i++) {
            double dt = Math.abs(TimeSystem.timediff(roverTime, baseObsTimeList.get(i)));
            if (dt < bestDt && dt <= maxTdiff) {
                bestDt = dt;
                bestIdx = i;
            }
        }

        if (bestIdx < 0) return null;

        Obsd[] result = baseObsList.remove(bestIdx);
        baseObsCountList.remove(bestIdx);
        baseObsTimeList.remove(bestIdx);
        return result;
    }

    public static String formatSolutionLine(Sol sol) {
        double[] llh = new double[3];
        CoordTransform.ecef2pos(sol.rr, llh);
        double latDeg = llh[0] * Constants.R2D;
        double lonDeg = llh[1] * Constants.R2D;
        double height = llh[2];

        double[] ymdhms = TimeSystem.time2ymdhms(sol.time);
        String dateStr = String.format("%04d/%02d/%02d", (int) ymdhms[0], (int) ymdhms[1], (int) ymdhms[2]);
        String timeStr = String.format("%02d:%02d:%09.6f", (int) ymdhms[3], (int) ymdhms[4], ymdhms[5]);

        String qStr;
        switch (sol.stat) {
            case Constants.SOLQ_FIX:    qStr = "1"; break;
            case Constants.SOLQ_FLOAT:  qStr = "2"; break;
            case Constants.SOLQ_SBAS:   qStr = "3"; break;
            case Constants.SOLQ_DGPS:   qStr = "4"; break;
            case Constants.SOLQ_SINGLE: qStr = "5"; break;
            case Constants.SOLQ_PPP:    qStr = "6"; break;
            default:                    qStr = "0"; break;
        }

        double sdn = sol.qr[0] > 0 ? Math.sqrt(sol.qr[0]) : 0;
        double sde = sol.qr[1] > 0 ? Math.sqrt(sol.qr[1]) : 0;
        double sdu = sol.qr[2] > 0 ? Math.sqrt(sol.qr[2]) : 0;
        double sdne = sol.qr[3] > 0 ? Math.sqrt(Math.abs(sol.qr[3])) : 0;
        double sdeu = sol.qr[4] > 0 ? Math.sqrt(Math.abs(sol.qr[4])) : 0;
        double sdun = sol.qr[5] > 0 ? Math.sqrt(Math.abs(sol.qr[5])) : 0;

        return String.format("  %s %s %14.9f %14.9f %10.4f  %s %3d %8.4f %8.4f %8.4f %8.4f %8.4f %8.4f %5.1f %6.1f\n",
                dateStr, timeStr, latDeg, lonDeg, height,
                qStr, sol.ns,
                sdn, sde, sdu, sdne, sdeu, sdun,
                sol.age, sol.ratio);
    }

    public static void writePosFile(RtkResult result, String outputPath) throws IOException {
        try (BufferedWriter w = new BufferedWriter(new FileWriter(outputPath))) {
            w.write(POS_HEADER);
            for (Sol sol : result.solutions) {
                w.write(formatSolutionLine(sol));
            }
            w.write(String.format("# Total: %d, Success: %d, Fail: %d\n",
                    result.totalEpochs, result.successCount, result.failCount));
        }
    }

    private static Obsd copyObsd(Obsd src) {
        Obsd dst = new Obsd();
        dst.time = src.time;
        dst.sat = src.sat;
        dst.rcv = src.rcv;
        dst.code = src.code.clone();
        dst.P = src.P.clone();
        dst.L = src.L.clone();
        dst.SNR = src.SNR.clone();
        dst.LLI = src.LLI.clone();
        return dst;
    }

    private static Ssat[] copySsatArray(Ssat[] ssat) {
        Ssat[] copy = new Ssat[ssat.length];
        for (int i = 0; i < ssat.length; i++) {
            copy[i] = new Ssat();
            Ssat src = ssat[i];
            Ssat dst = copy[i];
            dst.sys = src.sys;
            dst.vs = src.vs;
            System.arraycopy(src.azel, 0, dst.azel, 0, src.azel.length);
            System.arraycopy(src.resp, 0, dst.resp, 0, src.resp.length);
            System.arraycopy(src.resc, 0, dst.resc, 0, src.resc.length);
            System.arraycopy(src.vsat, 0, dst.vsat, 0, src.vsat.length);
            System.arraycopy(src.fix, 0, dst.fix, 0, src.fix.length);
            System.arraycopy(src.slip, 0, dst.slip, 0, src.slip.length);
            System.arraycopy(src.amb, 0, dst.amb, 0, src.amb.length);
        }
        return copy;
    }

    private byte[] ensureCapacity(byte[] buf, int need) {
        if (need > buf.length) {
            return Arrays.copyOf(buf, Math.max(buf.length * 2, need + 1024));
        }
        return buf;
    }

    private static boolean isObsType(int type) {
        return (type >= 1001 && type <= 1004)
                || (type >= 1074 && type <= 1077)
                || (type >= 1084 && type <= 1087)
                || (type >= 1094 && type <= 1097)
                || (type >= 1104 && type <= 1107)
                || (type >= 1114 && type <= 1117)
                || (type >= 1124 && type <= 1127)
                || (type >= 1134 && type <= 1137);
    }

    private static boolean isEphemerisType(int type) {
        return type == 1019 || type == 1020
                || type == 1041 || type == 1042
                || type == 1044 || type == 1045 || type == 1046;
    }

    public static class RtkResult {
        public final int totalEpochs;
        public final int successCount;
        public final int failCount;
        public final List<Sol> solutions;

        public RtkResult(int totalEpochs, int successCount, int failCount, List<Sol> solutions) {
            this.totalEpochs = totalEpochs;
            this.successCount = successCount;
            this.failCount = failCount;
            this.solutions = solutions;
        }
    }
}