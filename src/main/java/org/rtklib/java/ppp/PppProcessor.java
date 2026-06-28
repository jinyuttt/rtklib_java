package org.rtklib.java.ppp;

import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.ephemeris.ClkReader;
import org.rtklib.java.ephemeris.Sp3Reader;
import org.rtklib.java.pntpos.PntPos;
import org.rtklib.java.pntpos.PosHandler;
import org.rtklib.java.rtcm.Rtcm;
import org.rtklib.java.time.TimeSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * PPP（Precise Point Positioning，精密单点定位）处理器。
 * <p>
 * 实验性模块，非主要功能。
 * </p>
 * <p>
 * 流水线处理：feed()每解析出一个观测历元就立即定位，通过回调或输出流实时输出结果。
 * 星历未就绪时，观测历元会被缓存；星历到达后，缓存历元被修正时间并依次定位输出，
 * 之后新到达的观测历元立即定位。
 * </p>
 * <p>
 * PPP需要精密星历（SP3）和精密钟差（CLK）数据以获得高精度定位结果。
 * 可通过loadSp3()/loadClk()加载，也可在process()时指定文件路径。
 * 当无精密星历时，退化为使用广播星历。
 * </p>
 * <p>
 * 输出支持（独立工作，只要存在就使用）：
 * <ul>
 *   <li>OutputStream：定位结果以.pos格式逐行写入并刷新，可为null</li>
 *   <li>PosHandler回调：实时通知定位结果/失败/完成，可为null</li>
 * </ul>
 * PosHandler为RTK/SPP/PPP共用接口。
 * </p>
 * <p>
 * 注意：PppProcessor不可重用，调用finish()后即完成生命周期。
 * </p>
 *
 * <pre>
 * // 示例1：批量模式（RTCM文件 + 精密星历）
 * PppProcessor ppp = new PppProcessor(opt, handler, outputStream);
 * ppp.loadSp3("igs15904.sp3");
 * ppp.loadClk("igs15904.clk");
 * PppResult result = ppp.process("rover.rtcm3");
 *
 * // 示例2：批量模式（RINEX文件）
 * PppProcessor ppp = new PppProcessor(opt, handler, outputStream);
 * PppResult result = ppp.processRinex("rover.obs", "rover.nav", "igs.sp3", "igs.clk");
 *
 * // 示例3：流式模式（网络实时数据）
 * PppProcessor ppp = new PppProcessor(opt, handler, outputStream);
 * ppp.loadSp3("igs.sp3");
 * ppp.loadClk("igs.clk");
 * while (running) {
 *     byte[] chunk = networkStream.read();
 *     ppp.feed(chunk);
 * }
 * PppResult result = ppp.finish();
 * </pre>
 */
public class PppProcessor {

    private static final Logger log = LoggerFactory.getLogger(PppProcessor.class);

    private static final String POS_HEADER =
            "# PPP (Precise Point Positioning) Result (EXPERIMENTAL)\n" +
            "#  Date       Time       lat(deg)      lon(deg)     height(m)  Q  ns   sdn(m)   sde(m)   sdu(m)  sdne(m)  sdeu(m)  sdun(m) age(s)  ratio\n";

    private final PrcOpt opt;
    private final PosHandler handler;
    private final Writer writer;

    private final Rtk rtk;
    private final Rtcm rtcm = new Rtcm();

    private boolean ephReady = false;
    private boolean finished = false;

    private final List<Obsd[]> pendingObsList = new ArrayList<>();
    private final List<Integer> pendingObsCountList = new ArrayList<>();
    private final List<GTime> pendingObsTimeList = new ArrayList<>();

    private final Map<Integer, Integer> ephTypeCount = new TreeMap<>();
    private int totalEpochs = 0;
    private int successCount = 0;
    private int failCount = 0;
    private final List<Sol> solutions = new ArrayList<>();

    private byte[] pending = new byte[4096];
    private int pendingLen = 0;

    public PppProcessor(PrcOpt opt, PosHandler handler, OutputStream outputStream) {
        this.opt = new PrcOpt(opt);
        this.handler = handler;
        this.rtk = new Rtk();
        this.rtk.opt = this.opt;
        for (int i = 0; i < Constants.MAXSAT; i++) {
            this.rtk.ssat[i] = new Ssat();
        }

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

    public PppProcessor(PrcOpt opt, PosHandler handler) {
        this(opt, handler, null);
    }

    public PppProcessor(PrcOpt opt) {
        this(opt, null, null);
    }

    public PppProcessor() {
        this(createDefaultOpt(), null, null);
    }

    public static PrcOpt createDefaultOpt() {
        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_PPP_KINEMA;
        opt.nf = 2;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL | Constants.SYS_CMP;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_IFLC;
        opt.tropopt = Constants.TROPOPT_EST;
        opt.sateph = Constants.EPHOPT_PREC;
        opt.dynamics = 0;
        opt.posopt = new int[6];
        return opt;
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

    public void setSatEph(int sateph) { opt.sateph = sateph; rtk.opt.sateph = sateph; }

    public void setDynamics(int dynamics) { opt.dynamics = dynamics; rtk.opt.dynamics = dynamics; }

    public PrcOpt getOpt() { return opt; }

    public Rtk getRtk() { return rtk; }

    public void loadSp3(String sp3FilePath) {
        Sp3Reader.readsp3(sp3FilePath, rtcm.nav, 0);
        log.info("Loaded SP3: {} ephemerides", rtcm.nav.ne);
    }

    public void loadClk(String clkFilePath) {
        ClkReader.readclk(clkFilePath, rtcm.nav);
        log.info("Loaded CLK: {} records", rtcm.nav.nc);
    }

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

    public void feed(byte[] data) {
        feed(data, 0, data.length);
    }

    public void reset() {
        rtk.sol = new Sol();
        rtk.nx = 0;
        rtk.na = 0;
        rtk.x = null;
        rtk.P = null;
        rtk.xa = null;
        rtk.Pa = null;
        rtk.nfix = 0;
        rtk.nepoch = new int[2];
        rtk.epoch = 0;
        rtk.tt = 0.0;
        for (int i = 0; i < Constants.MAXSAT; i++) {
            rtk.ssat[i] = new Ssat();
        }

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

    public PppResult finish() {
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

        return finishInternal();
    }

    public PppResult process(byte[] data) {
        if (finished) {
            throw new IllegalStateException("Processor already finished");
        }

        Rtcm batchRtcm = new Rtcm();
        List<Obsd[]> batchObsList = new ArrayList<>();
        List<Integer> batchObsCountList = new ArrayList<>();
        List<GTime> batchObsTimeList = new ArrayList<>();
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
            if (isEphemerisType(type) && batchRtcm.ephsat != 0) {
                batchEphTypeCount.merge(type, 1, Integer::sum);
            }
            if (isObsType(type) && batchRtcm.obs.n > 0 && batchRtcm.obsflag == 1) {
                int n = batchRtcm.obs.n;
                Obsd[] obsCopy = new Obsd[n];
                for (int i = 0; i < n; i++) {
                    obsCopy[i] = copyObsd(batchRtcm.obs.data[i]);
                    obsCopy[i].rcv = 1;
                }
                batchObsList.add(obsCopy);
                batchObsCountList.add(n);
                batchObsTimeList.add(batchRtcm.obs.data[0].time);
            }
        }

        if (rtcm.nav.ne > 0) {
            batchRtcm.nav.peph = rtcm.nav.peph;
            batchRtcm.nav.ne = rtcm.nav.ne;
            batchRtcm.nav.nemax = rtcm.nav.nemax;
        }
        if (rtcm.nav.nc > 0) {
            batchRtcm.nav.pclk = rtcm.nav.pclk;
            batchRtcm.nav.nc = rtcm.nav.nc;
            batchRtcm.nav.ncmax = rtcm.nav.ncmax;
        }

        int ephWeek = findEphWeek(batchRtcm.nav);
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

        log.info("PPP Batch: obsEpochs={}, ephTypes={}, sp3={}, clk={}",
                batchObsList.size(), batchEphTypeCount,
                batchRtcm.nav.ne, batchRtcm.nav.nc);

        for (int epoch = 0; epoch < batchObsList.size(); epoch++) {
            processEpoch(batchObsList.get(epoch), batchObsCountList.get(epoch),
                    batchObsTimeList.get(epoch), batchRtcm.nav);
        }

        rtcm.nav = batchRtcm.nav;
        ephReady = true;
        finished = true;
        ephTypeCount.putAll(batchEphTypeCount);

        return finishInternal();
    }

    public PppResult process(String filePath) throws IOException {
        byte[] data = Files.readAllBytes(Paths.get(filePath));
        return process(data);
    }

    public PppResult processRinex(String obsFilePath, String navFilePath,
                                  String sp3FilePath, String clkFilePath) {
        if (finished) {
            throw new IllegalStateException("Processor already finished");
        }

        org.rtklib.java.rinex.RinexParser parser = new org.rtklib.java.rinex.RinexParser();
        boolean obsOk = parser.parseObs(obsFilePath);
        if (!obsOk) {
            throw new RuntimeException("Failed to parse RINEX observation file: " + obsFilePath);
        }
        if (navFilePath != null) {
            parser.parseNav(navFilePath);
        }

        if (sp3FilePath != null) {
            Sp3Reader.readsp3(sp3FilePath, parser.nav, 0);
            log.info("Loaded SP3: {} ephemerides", parser.nav.ne);
        }
        if (clkFilePath != null) {
            ClkReader.readclk(clkFilePath, parser.nav);
            log.info("Loaded CLK: {} records", parser.nav.nc);
        }

        if (parser.obs.n == 0) {
            log.warn("No observation data in RINEX file");
            finished = true;
            return new PppResult(0, 0, 0, solutions);
        }

        if (parser.sta != null && parser.sta.pos != null
                && (parser.sta.pos[0] != 0.0 || parser.sta.pos[1] != 0.0 || parser.sta.pos[2] != 0.0)) {
            rtk.sol.rr[0] = parser.sta.pos[0];
            rtk.sol.rr[1] = parser.sta.pos[1];
            rtk.sol.rr[2] = parser.sta.pos[2];
            log.info("Using approximate position from RINEX header");
        }

        List<List<Obsd>> epochGroups = groupObsByEpoch(parser.obs.data, parser.obs.n);
        log.info("PPP RINEX: {} total observations, {} epochs", parser.obs.n, epochGroups.size());

        for (List<Obsd> epochObs : epochGroups) {
            Obsd[] obsArray = epochObs.toArray(new Obsd[0]);
            int n = obsArray.length;
            for (int i = 0; i < n; i++) obsArray[i].rcv = 1;
            processEpoch(obsArray, n, obsArray[0].time, parser.nav);
        }

        finished = true;
        return finishInternal();
    }

    public static void writePosFile(PppResult result, String outputPath) throws IOException {
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
                obsCopy[i] = copyObsd(rtcm.obs.data[i]);
                obsCopy[i].rcv = 1;
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

        int ephWeek = findEphWeek(rtcm.nav);
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
            processEpoch(pendingObsList.get(i), pendingObsCountList.get(i),
                    pendingObsTimeList.get(i), rtcm.nav);
        }
        pendingObsList.clear();
        pendingObsCountList.clear();
        pendingObsTimeList.clear();
    }

    private void processEpoch(Obsd[] obsData, int n, GTime time, Nav nav) {
        totalEpochs++;

        GTime prevTime = new GTime(rtk.sol.time);
        if (rtk.sol.stat == Constants.SOLQ_NONE) {
            PntPos.pntpos(obsData, n, nav, opt, rtk.sol, null, rtk.ssat);
        } else {
            rtk.sol.time = obsData[0].time;
        }

        if (prevTime.time != 0) {
            rtk.tt = TimeSystem.timediff(rtk.sol.time, prevTime);
        } else {
            rtk.tt = 0.0;
        }

        PppCore.pppos(rtk, obsData, n, nav);

        if (rtk.sol.stat != Constants.SOLQ_NONE) {
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
                handler.onPosFail(time, "PPP positioning failed");
            }
        }
    }

    private PppResult finishInternal() {
        log.info("PPP complete: total={}, success={}, fail={}, ephTypes={}",
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

        return new PppResult(totalEpochs, successCount, failCount, solutions);
    }

    private int findEphWeek(Nav nav) {
        for (int i = 0; i < nav.eph.length; i++) {
            if (nav.eph[i] != null && nav.eph[i].A > 0) {
                int[] wkArr = new int[1];
                TimeSystem.time2gpst(nav.eph[i].toe, wkArr);
                if (wkArr[0] > 0) return wkArr[0];
            }
        }
        return -1;
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

    private List<List<Obsd>> groupObsByEpoch(Obsd[] data, int n) {
        List<List<Obsd>> groups = new ArrayList<>();
        if (n == 0) return groups;

        List<Obsd> current = new ArrayList<>();
        GTime currentTime = new GTime(data[0].time);

        for (int i = 0; i < n; i++) {
            if (!data[i].time.equals(currentTime)) {
                groups.add(current);
                current = new ArrayList<>();
                currentTime = new GTime(data[i].time);
            }
            current.add(data[i]);
        }
        if (!current.isEmpty()) groups.add(current);
        return groups;
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

    private void ensureCapacity(int need) {
        if (need > pending.length) {
            pending = Arrays.copyOf(pending, Math.max(pending.length * 2, need + 1024));
        }
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

    public static class PppResult {
        public final int totalEpochs;
        public final int successCount;
        public final int failCount;
        public final List<Sol> solutions;

        public PppResult(int totalEpochs, int successCount, int failCount, List<Sol> solutions) {
            this.totalEpochs = totalEpochs;
            this.successCount = successCount;
            this.failCount = failCount;
            this.solutions = solutions;
        }
    }
}