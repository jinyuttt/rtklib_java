package org.rtklib.java.rinex;

import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.ephemeris.EphModel;
import org.rtklib.java.pntpos.PosHandler;
import org.rtklib.java.pntpos.SppCore;
import org.rtklib.java.time.TimeSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
/**
 * RINEX文件单点定位处理器。
 * <p>
 * 输入RINEX观测文件(.obs)和导航文件(.nav)路径，执行SPP定位并输出结果。
 * 支持回调、输出流和结果对象三种方式获取定位结果。
 * </p>
 *
 * <pre>
 * // 示例：RINEX文件SPP定位
 * RinexSppProcessor spp = new RinexSppProcessor(opt, handler, outputStream);
 * SppResult result = spp.process("ROVER.obs", "ROVER.nav");
 *
 * // 或使用便捷方法
 * SppResult result = RinexSppProcessor.processRinex("ROVER.obs", "ROVER.nav", opt);
 * </pre>
 */
public class RinexSppProcessor {

    private static final Logger log = LoggerFactory.getLogger(RinexSppProcessor.class);

    private static final String POS_HEADER =
            "# SPP (Single Point Positioning) Result - RINEX Input\n" +
            "#  Date       Time       lat(deg)      lon(deg)     height(m)  Q  ns   sdn(m)   sde(m)   sdu(m)  sdne(m)  sdeu(m)  sdun(m) age(s)  ratio\n";

    private final PrcOpt opt;
    private final PosHandler handler;
    private final Writer writer;

    private final Sol sol = new Sol();
    private final Ssat[] ssat = new Ssat[Constants.MAXSAT];

    private int totalEpochs = 0;
    private int successCount = 0;
    private int failCount = 0;
    private final List<Sol> solutions = new ArrayList<>();

    /**
     * 构造RINEX SPP处理器。
     *
     * @param opt          定位处理选项，内部会进行深拷贝
     * @param handler      定位结果回调，可为null
     * @param outputStream 输出流，可为null
     */
    public RinexSppProcessor(PrcOpt opt, PosHandler handler, OutputStream outputStream) {
        this.opt = new PrcOpt(opt);
        this.handler = handler;
        for (int i = 0; i < Constants.MAXSAT; i++) ssat[i] = new Ssat();

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

    public RinexSppProcessor(PrcOpt opt, PosHandler handler) {
        this(opt, handler, null);
    }

    public RinexSppProcessor(PrcOpt opt) {
        this(opt, null, null);
    }

    public RinexSppProcessor() {
        this(createDefaultOpt(), null, null);
    }

    public static PrcOpt createDefaultOpt() {
        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_SINGLE;
        opt.nf = 2;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL | Constants.SYS_CMP;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;
        return opt;
    }

    /**
     * 处理RINEX观测文件和导航文件，执行SPP定位。
     *
     * @param obsFilePath RINEX观测文件路径(.obs)
     * @param navFilePath RINEX导航文件路径(.nav)
     * @return 定位结果
     */
    public SppResult process(String obsFilePath, String navFilePath) {
        RinexParser parser = new RinexParser();
        boolean obsOk = parser.parseObs(obsFilePath);
        if (!obsOk) {
            throw new RuntimeException("Failed to parse RINEX observation file: " + obsFilePath);
        }
        boolean navOk = parser.parseNav(navFilePath);
        if (!navOk) {
            throw new RuntimeException("Failed to parse RINEX navigation file: " + navFilePath);
        }

        if (parser.obs.n == 0) {
            log.warn("No observation data in RINEX file");
            return new SppResult(0, 0, 0, solutions);
        }

        if (parser.sta != null && parser.sta.pos != null
                && (parser.sta.pos[0] != 0.0 || parser.sta.pos[1] != 0.0 || parser.sta.pos[2] != 0.0)) {
            sol.rr[0] = parser.sta.pos[0];
            sol.rr[1] = parser.sta.pos[1];
            sol.rr[2] = parser.sta.pos[2];
            log.info("Using approximate position from RINEX header: ({}, {}, {})",
                    sol.rr[0], sol.rr[1], sol.rr[2]);
        }

        List<List<Obsd>> epochGroups = groupObsByEpoch(parser.obs.data, parser.obs.n);
        log.info("RINEX SPP: {} total observations, {} epochs", parser.obs.n, epochGroups.size());

        for (List<Obsd> epochObs : epochGroups) {
            Obsd[] obsArray = epochObs.toArray(new Obsd[0]);
            int n = obsArray.length;
            GTime epochTime = obsArray[0].time;
            processEpoch(obsArray, n, epochTime, parser.nav);
        }

        log.info("RINEX SPP complete: total={}, success={}, fail={}", totalEpochs, successCount, failCount);

        if (writer != null) {
            try {
                writer.write(String.format("# Total: %d, Success: %d, Fail: %d\n", totalEpochs, successCount, failCount));
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
     * 处理RINEX观测文件和导航文件（导航文件路径可为null，仅使用观测文件中的星历）。
     *
     * @param obsFilePath RINEX观测文件路径(.obs)
     * @param navFilePath RINEX导航文件路径(.nav)，可为null
     * @param opt         定位处理选项
     * @return 定位结果
     */
    public static SppResult processRinex(String obsFilePath, String navFilePath, PrcOpt opt) {
        RinexSppProcessor processor = new RinexSppProcessor(opt);
        return processor.process(obsFilePath, navFilePath);
    }

    /**
     * 处理RINEX观测文件和导航文件（使用默认选项）。
     *
     * @param obsFilePath RINEX观测文件路径(.obs)
     * @param navFilePath RINEX导航文件路径(.nav)
     * @return 定位结果
     */
    public static SppResult processRinex(String obsFilePath, String navFilePath) {
        return processRinex(obsFilePath, navFilePath, createDefaultOpt());
    }

    private List<List<Obsd>> groupObsByEpoch(Obsd[] data, int n) {
        List<List<Obsd>> groups = new ArrayList<>();
        if (n == 0) return groups;

        List<Obsd> current = new ArrayList<>();
        GTime currentTime = data[0].time;

        for (int i = 0; i < n; i++) {
            if (!data[i].time.equals(currentTime)) {
                groups.add(current);
                current = new ArrayList<>();
                currentTime = data[i].time;
            }
            current.add(data[i]);
        }
        if (!current.isEmpty()) {
            groups.add(current);
        }
        return groups;
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

    public static class SppResult {
        public final int totalEpochs;
        public final int successCount;
        public final int failCount;
        public final List<Sol> solutions;

        SppResult(int totalEpochs, int successCount, int failCount, List<Sol> solutions) {
            this.totalEpochs = totalEpochs;
            this.successCount = successCount;
            this.failCount = failCount;
            this.solutions = solutions;
        }
    }
}
