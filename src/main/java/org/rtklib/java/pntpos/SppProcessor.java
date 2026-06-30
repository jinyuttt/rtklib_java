package org.rtklib.java.pntpos;

import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.ephemeris.EphModel;
import org.rtklib.java.rtcm.Rtcm;
import org.rtklib.java.time.TimeSystem;
import org.rtklib.java.coord.CoordTransform;
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
 * SPP（Single Point Positioning，单点定位）处理器。
 * <p>
 * 流水线处理：feed()每解析出一个观测历元就立即定位，通过回调或输出流实时输出结果。
 * finish()仅表示数据输入结束，处理剩余缓存历元并通知完成。
 * </p>
 * <p>
 * 星历未就绪时，观测历元会被缓存；星历到达后，缓存历元被修正时间并依次定位输出，
 * 之后新到达的观测历元立即定位。
 * </p>
 * <p>
 * 输出支持（独立工作，只要存在就使用）：
 * <ul>
 *   <li>OutputStream：定位结果以.pos格式逐行写入并刷新，可为null</li>
 *   <li>PosHandler回调：实时通知定位结果/失败/完成，可为null</li>
 * </ul>
 * PosHandler为RTK/SPP共用接口，SPP不使用的字段保持默认值。
 * </p>
 * <p>
 * 注意：SppProcessor不可重用，调用finish()后即完成生命周期。
 * </p>
 *
 * <pre>
 * // 示例1：批量模式
 * SppProcessor spp = new SppProcessor(opt, handler, outputStream);
 * SppResult result = spp.process("data.rtcm");
 *
 * // 示例2：流式模式（网络实时数据）
 * SppProcessor spp = new SppProcessor(opt, handler, outputStream);
 * while (running) {
 *     byte[] chunk = networkStream.read();
 *     spp.feed(chunk);  // 每个历元立即定位并回调/写流
 * }
 * SppResult result = spp.finish();  // 数据输入结束
 * </pre>
 */
public class SppProcessor {

    private static final Logger log = LoggerFactory.getLogger(SppProcessor.class);

    private static final String POS_HEADER =
            "# SPP (Single Point Positioning) Result\n" +
            "#  Date       Time       lat(deg)      lon(deg)     height(m)  Q  ns   sdn(m)   sde(m)   sdu(m)  sdne(m)  sdeu(m)  sdun(m) age(s)  ratio\n";

    private final PrcOpt opt;
    private final PosHandler handler;
    private final Writer writer;

    private final Rtcm rtcm = new Rtcm();
    private final Sol sol = new Sol();
    private final Ssat[] ssat = new Ssat[Constants.MAXSAT];

    private final double[] basePos = new double[3];
    private boolean hasBasePos = false;
    private boolean ephReady = false;
    private boolean finished = false;

    private final List<Obsd[]> pendingObsList = new ArrayList<>();
    private final List<Integer> pendingObsCountList = new ArrayList<>();
    private final List<GTime> pendingObsTimeList = new ArrayList<>();

    private final Map<Integer, Integer> ephTypeCount = new TreeMap<>();
    private int totalEpochs = 0;
    private int successCount = 0;
    private int failCount = 0;
    private int outputCount = 0;
    private int sleepInterval = 100;
    private int sleepMs = 10;
    private final List<Sol> solutions = new ArrayList<>();

    private byte[] pending = new byte[4096];
    private int pendingLen = 0;

    /**
     * 构造SPP处理器。
     *
     * @param opt          定位处理选项，内部会进行深拷贝
     * @param handler      定位结果回调，可为null
     * @param outputStream 输出流，可为null。支持文件流、网络流等，
     *                      定位结果将以.pos格式逐行写入并刷新
     */
    public SppProcessor(PrcOpt opt, PosHandler handler, OutputStream outputStream) {
        this.opt = new PrcOpt(opt);
        this.handler = handler;
        for (int i = 0; i < Constants.MAXSAT; i++) ssat[i] = new Ssat();
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

    /**
     * 构造SPP处理器（无输出流）。
     *
     * @param opt     定位处理选项，内部会进行深拷贝
     * @param handler 定位结果回调，可为null
     */
    public SppProcessor(PrcOpt opt, PosHandler handler) {
        this(opt, handler, null);
    }

    /**
     * 构造SPP处理器（无回调和输出流）。
     *
     * @param opt 定位处理选项，内部会进行深拷贝
     */
    public SppProcessor(PrcOpt opt) {
        this(opt, null, null);
    }

    /**
     * 使用默认配置构造SPP处理器，无回调和输出流。
     */
    public SppProcessor() {
        this(createDefaultOpt(), null, null);
    }

    /**
     * 创建SPP默认处理选项。
     *
     * @return 默认的PrcOpt配置
     */
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
        opt.mode = Constants.PMODE_SINGLE;
        opt.nf = 3;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL | Constants.SYS_CMP;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;
        opt.outputThrottleInterval = 100;
        opt.outputThrottleSleepMs = 10;
        return opt;
    }

    /**
     * 流式输入RTCM数据，流水线处理。
     * <p>
     * 解析RTCM消息，每解析出一个观测历元就立即执行SPP定位，
     * 通过回调或输出流实时输出结果。星历未就绪时观测历元会被缓存。
     * 可多次调用以逐步输入数据（如网络实时流）。
     * </p>
     *
     * @param data   RTCM原始字节数据
     * @param offset 数据起始偏移量
     * @param length 数据长度
     * @throws IllegalStateException 已调用finish()后再次调用
     */
    public void feed(byte[] data, int offset, int length) {
        if (finished) {
            throw new IllegalStateException("Processor already finished");
        }
        if (length <= 0) return;

        ensureCapacity(pendingLen + length);
        System.arraycopy(data, offset, pending, pendingLen, length);
        pendingLen += length;

        int pos = 0;
        while (pos < pendingLen) {
            int consumed = rtcm.input(pending, pos, pendingLen - pos);
            if (consumed > 0) {
                onRtcmMessage();
                pos += consumed;
            } else if (consumed == 0) {
                break;
            } else {
                pos++;
            }
        }

        if (pos > 0) {
            if (pos < pendingLen) {
                System.arraycopy(pending, pos, pending, 0, pendingLen - pos);
            }
            pendingLen -= pos;
        }
    }

    /**
     * 流式输入RTCM数据。
     *
     * @param data RTCM原始字节数据
     * @throws IllegalStateException 已调用finish()后再次调用
     */
    public void feed(byte[] data) {
        feed(data, 0, data.length);
    }

    /**
     * 重置处理器状态，使其可继续使用。
     * <p>
     * 重置定位状态（坐标、卫星状态）和统计计数器，清除缓存的观测数据。
     * 保留配置（PrcOpt、PosHandler、OutputStream）和已积累的星历数据（Rtcm/nav），
     * 后续feed()输入的数据可直接使用已有星历进行定位。
     * </p>
     * <p>
     * 典型场景：网络实时流中，一个会话结束后调用finish()获取统计，
     * 再调用reset()开始下一个会话，无需重新等待星历。
     * </p>
     */
    public void reset() {
        sol.rr = new double[6];
        sol.qr = new float[6];
        sol.qv = new float[6];
        sol.dtr = new double[6];
        sol.stat = 0;
        sol.ns = 0;
        sol.type = 0;
        sol.age = 0.0f;
        sol.ratio = 0.0f;
        sol.time = new GTime();

        for (int i = 0; i < Constants.MAXSAT; i++) ssat[i] = new Ssat();

        pendingObsList.clear();
        pendingObsCountList.clear();
        pendingObsTimeList.clear();

        totalEpochs = 0;
        successCount = 0;
        failCount = 0;
        solutions.clear();

        pendingLen = 0;
        finished = false;
    }

    /**
     * 表示数据输入结束，处理剩余缓存历元并通知完成。
     * <p>
     * finish()仅表示不再有新数据输入。如果还有星历未就绪时缓存的观测历元，
     * 会尝试处理（若星历已就绪则定位，否则计入失败）。
     * 最后通过回调或输出流通知处理完成。
     * </p>
     *
     * @return 定位结果汇总
     * @throws IllegalStateException 重复调用
     */
    public SppResult finish() {
        if (finished) {
            throw new IllegalStateException("Processor already finished");
        }
        finished = true;

        if (ephReady && !pendingObsList.isEmpty()) {
            flushPendingObservations();
        }

        for (int i = 0; i < pendingObsList.size(); i++) {
            totalEpochs++;
            failCount++;
            if (handler != null) {
                handler.onPosFail(pendingObsTimeList.get(i), "No ephemeris data");
            }
        }
        pendingObsList.clear();
        pendingObsCountList.clear();
        pendingObsTimeList.clear();

        log.info("SPP complete: total={}, success={}, fail={}, ephTypes={}",
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

        return new SppResult(totalEpochs, successCount, failCount, solutions);
    }

    /**
     * 批量处理RTCM字节数据进行SPP定位。
     * <p>
     * 采用两阶段批处理：先解析所有RTCM消息收集完整星历，再逐历元定位。
     * 确保与rtklib C版本结果一致。结果通过回调或输出流实时输出。
     * </p>
     *
     * @param data RTCM原始字节数据
     * @return 定位结果
     */
    public SppResult process(byte[] data) {
        if (finished) {
            throw new IllegalStateException("Processor already finished");
        }

        Rtcm batchRtcm = new Rtcm();
        List<Obsd[]> batchObsList = new ArrayList<>();
        List<Integer> batchObsCountList = new ArrayList<>();
        List<GTime> batchObsTimeList = new ArrayList<>();
        double[] batchBasePos = new double[3];
        boolean batchHasBasePos = false;
        Map<Integer, Integer> batchEphTypeCount = new TreeMap<>();

        int offset = 0;
        while (offset < data.length) {
            int consumed = batchRtcm.input(data, offset, data.length - offset);
            if (consumed <= 0) {
                offset++;
                continue;
            }
            offset += consumed;

            int type = batchRtcm.type;
            if (type == 1005 || type == 1006) {
                if (batchRtcm.sta != null) {
                    System.arraycopy(batchRtcm.sta.pos, 0, batchBasePos, 0, 3);
                    batchHasBasePos = true;
                }
            }
            if (isEphemerisType(type) && batchRtcm.ephsat != 0) {
                batchEphTypeCount.merge(type, 1, Integer::sum);
            }
            if (isObsType(type) && batchRtcm.obs.n > 0 && batchRtcm.obsflag == 1) {
                int n = batchRtcm.obs.n;
                Obsd[] obsCopy = new Obsd[n];
                for (int i = 0; i < n; i++) {
                    obsCopy[i] = new Obsd();
                    obsCopy[i].time = batchRtcm.obs.data[i].time;
                    obsCopy[i].sat = batchRtcm.obs.data[i].sat;
                    obsCopy[i].code = batchRtcm.obs.data[i].code.clone();
                    obsCopy[i].P = batchRtcm.obs.data[i].P.clone();
                    obsCopy[i].L = batchRtcm.obs.data[i].L.clone();
                    obsCopy[i].SNR = batchRtcm.obs.data[i].SNR.clone();
                    obsCopy[i].LLI = batchRtcm.obs.data[i].LLI.clone();
                }
                batchObsList.add(obsCopy);
                batchObsCountList.add(n);
                batchObsTimeList.add(batchRtcm.obs.data[0].time);
            }
        }

        int ephWeek = -1;
        for (int i = 0; i < batchRtcm.nav.eph.length; i++) {
            if (batchRtcm.nav.eph[i] != null && batchRtcm.nav.eph[i].A > 0) {
                int[] wkArr = new int[1];
                TimeSystem.time2gpst(batchRtcm.nav.eph[i].toe, wkArr);
                if (wkArr[0] > 0) {
                    ephWeek = wkArr[0];
                    break;
                }
            }
        }
        if (ephWeek > 0) {
            for (int i = 0; i < batchObsTimeList.size(); i++) {
                GTime ot = batchObsTimeList.get(i);
                int[] wkArr = new int[1];
                double sow = TimeSystem.time2gpst(ot, wkArr);
                if (wkArr[0] == 0 && sow > 0) {
                    GTime corrected = TimeSystem.gpst2time(ephWeek, sow);
                    batchObsTimeList.set(i, corrected);
                    for (int j = 0; j < batchObsCountList.get(i); j++) {
                        batchObsList.get(i)[j].time = corrected;
                    }
                }
            }
        }

        if (batchHasBasePos && sol.rr[0] == 0.0 && sol.rr[1] == 0.0 && sol.rr[2] == 0.0) {
            sol.rr[0] = batchBasePos[0];
            sol.rr[1] = batchBasePos[1];
            sol.rr[2] = batchBasePos[2];
        }

        log.info("Batch Phase 1 complete: obsEpochs={}, ephTypes={}", batchObsList.size(), batchEphTypeCount);

        for (int epoch = 0; epoch < batchObsList.size(); epoch++) {
            processEpoch(batchObsList.get(epoch), batchObsCountList.get(epoch), batchObsTimeList.get(epoch), batchRtcm.nav);
        }

        rtcm.nav = batchRtcm.nav;
        ephReady = true;
        if (batchHasBasePos) {
            System.arraycopy(batchBasePos, 0, basePos, 0, 3);
            hasBasePos = true;
        }
        finished = true;
        ephTypeCount.putAll(batchEphTypeCount);

        log.info("SPP complete: total={}, success={}, fail={}, ephTypes={}",
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

        return new SppResult(totalEpochs, successCount, failCount, solutions);
    }

    /**
     * 批量处理RTCM文件进行SPP定位。
     *
     * @param filePath RTCM文件路径
     * @return 定位结果
     * @throws IOException 文件读取失败
     */
    public SppResult process(String filePath) throws IOException {
        byte[] data = CompatFileIO.readAllBytes(filePath);
        return process(data);
    }

    /**
     * 将定位结果写入.pos格式文件。
     *
     * @param result     定位结果
     * @param outputPath 输出文件路径
     * @throws IOException 文件写入失败
     */
    public static void writePosFile(SppResult result, String outputPath) throws IOException {
        try (BufferedWriter w = new BufferedWriter(new FileWriter(outputPath))) {
            w.write(POS_HEADER);
            for (Sol sol : result.solutions) {
                w.write(formatSolutionLine(sol));
            }
            w.write(String.format("# Total: %d, Success: %d, Fail: %d\n",
                    result.totalEpochs, result.successCount, result.failCount));
        }
    }

    private void onRtcmMessage() {
        int type = rtcm.type;

        if (type == 1005 || type == 1006) {
            if (rtcm.sta != null) {
                System.arraycopy(rtcm.sta.pos, 0, basePos, 0, 3);
                hasBasePos = true;
                if (sol.rr[0] == 0.0 && sol.rr[1] == 0.0 && sol.rr[2] == 0.0) {
                    sol.rr[0] = basePos[0];
                    sol.rr[1] = basePos[1];
                    sol.rr[2] = basePos[2];
                }
            }
        }

        if (isEphemerisType(type) && rtcm.ephsat != 0) {
            ephTypeCount.merge(type, 1, Integer::sum);
            if (!ephReady) {
                ephReady = true;
                flushPendingObservations();
            }
        }

        if (isObsType(type) && rtcm.obs.n > 0 && rtcm.obsflag == 1) {
            int n = rtcm.obs.n;
            Obsd[] obsCopy = new Obsd[n];
            for (int i = 0; i < n; i++) {
                obsCopy[i] = new Obsd();
                obsCopy[i].time = rtcm.obs.data[i].time;
                obsCopy[i].sat = rtcm.obs.data[i].sat;
                obsCopy[i].code = rtcm.obs.data[i].code.clone();
                obsCopy[i].P = rtcm.obs.data[i].P.clone();
                obsCopy[i].L = rtcm.obs.data[i].L.clone();
                obsCopy[i].SNR = rtcm.obs.data[i].SNR.clone();
                obsCopy[i].LLI = rtcm.obs.data[i].LLI.clone();
            }
            GTime epochTime = rtcm.obs.data[0].time;

            if (ephReady) {
                processEpoch(obsCopy, n, epochTime, rtcm.nav);
            } else {
                pendingObsList.add(obsCopy);
                pendingObsCountList.add(n);
                pendingObsTimeList.add(epochTime);
            }
        }
    }

    private void flushPendingObservations() {
        if (pendingObsList.isEmpty()) return;

        int ephWeek = findEphWeek();
        if (ephWeek > 0) {
            for (int i = 0; i < pendingObsTimeList.size(); i++) {
                GTime ot = pendingObsTimeList.get(i);
                int[] wkArr = new int[1];
                double sow = TimeSystem.time2gpst(ot, wkArr);
                if (wkArr[0] == 0 && sow > 0) {
                    GTime corrected = TimeSystem.gpst2time(ephWeek, sow);
                    pendingObsTimeList.set(i, corrected);
                    for (int j = 0; j < pendingObsCountList.get(i); j++) {
                        pendingObsList.get(i)[j].time = corrected;
                    }
                }
            }
        }

        for (int i = 0; i < pendingObsList.size(); i++) {
            processEpoch(pendingObsList.get(i), pendingObsCountList.get(i), pendingObsTimeList.get(i), rtcm.nav);
        }
        pendingObsList.clear();
        pendingObsCountList.clear();
        pendingObsTimeList.clear();
    }

    private void processEpoch(Obsd[] obsData, int n, GTime time, Nav nav) {
        totalEpochs++;

        double[] rs = new double[n * 6];
        double[] dts = new double[n * 2];
        double[] vare = new double[n];
        int[] svh = new int[n];

        EphModel.satposs(time, obsData, n, nav, rs, dts, vare, svh);

        int[] vsat = new int[n];
        double[] azel = new double[n * 2];
        double[] resp = new double[n];
        String[] msg = new String[1];

        int result = SppCore.estpos(obsData, n, rs, dts, vare, svh,
                nav, opt, ssat, sol, azel, vsat, resp, msg);

        if (result == 1) {
            successCount++;
            Sol solCopy = new Sol(sol);
            solutions.add(solCopy);

            if (handler != null) {
                handler.onSolution(new Sol(sol), copySsatArray(ssat));
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
                handler.onPosFail(time, msg[0]);
            }
        }

        outputCount++;
        if (sleepInterval > 0 && outputCount % sleepInterval == 0) {
            try { Thread.sleep(sleepMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
    }

    private int findEphWeek() {
        for (int i = 0; i < rtcm.nav.eph.length; i++) {
            if (rtcm.nav.eph[i] != null && rtcm.nav.eph[i].A > 0) {
                int[] wkArr = new int[1];
                TimeSystem.time2gpst(rtcm.nav.eph[i].toe, wkArr);
                if (wkArr[0] > 0) {
                    return wkArr[0];
                }
            }
        }
        return -1;
    }

    static String formatSolutionLine(Sol sol) {
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

    private void ensureCapacity(int need) {
        if (need > pending.length) {
            pending = Arrays.copyOf(pending, Math.max(pending.length * 2, need + 1024));
        }
    }

    /**
     * SPP定位结果。
     */
    public static class SppResult {
        /** 总历元数 */
        public final int totalEpochs;
        /** 定位成功历元数 */
        public final int successCount;
        /** 定位失败历元数 */
        public final int failCount;
        /** 定位成功的结果列表 */
        public final List<Sol> solutions;

        SppResult(int totalEpochs, int successCount, int failCount, List<Sol> solutions) {
            this.totalEpochs = totalEpochs;
            this.successCount = successCount;
            this.failCount = failCount;
            this.solutions = solutions;
        }
    }
}