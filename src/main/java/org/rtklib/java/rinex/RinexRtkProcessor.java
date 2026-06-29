package org.rtklib.java.rinex;

import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.pntpos.PosHandler;
import org.rtklib.java.rtkpos.RtkCore;
import org.rtklib.java.rtkpos.RtkProcessor;
import org.rtklib.java.time.TimeSystem;
import org.rtklib.java.trace.TraceCallback;
import org.rtklib.java.trace.TraceControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * RINEX文件RTK相对定位处理器。
 * <p>
 * 输入RINEX观测文件(.obs)和导航文件(.nav)路径，执行RTK定位并输出结果。
 * 支持流动站和基准站分别提供RINEX文件，也支持单站RINEX文件。
 * 支持回调、输出流和结果对象三种方式获取定位结果。
 * </p>
 *
 * <pre>
 * // 示例1：流动站+基准站RINEX文件RTK定位
 * RinexRtkProcessor rtk = new RinexRtkProcessor(opt, handler, outputStream);
 * RtkResult result = rtk.process("ROVER.obs", "BASE.obs", "ROVER.nav");
 *
 * // 示例2：便捷方法
 * RtkResult result = RinexRtkProcessor.processRinex("ROVER.obs", "BASE.obs", "NAV.nav", opt);
 *
 * // 示例3：单站RINEX（仅SPP，无基准站数据时退化为单点定位）
 * RtkResult result = RinexRtkProcessor.processRinex("ROVER.obs", null, "NAV.nav", opt);
 * </pre>
 */
public class RinexRtkProcessor {

    private static final Logger log = LoggerFactory.getLogger(RinexRtkProcessor.class);

    private static final String POS_HEADER =
            "# RTK (Relative Positioning) Result - RINEX Input\n" +
            "#  Date       Time       lat(deg)      lon(deg)     height(m)  Q  ns   sdn(m)   sde(m)   sdu(m)  sdne(m)  sdeu(m)  sdun(m) age(s)  ratio\n";

    private final PrcOpt opt;
    private final PosHandler handler;
    private final Writer writer;

    private final Rtk rtk;
    private int totalEpochs = 0;
    private int successCount = 0;
    private int failCount = 0;
    private final List<Sol> solutions = new ArrayList<>();

    public RinexRtkProcessor(PrcOpt opt, PosHandler handler, OutputStream outputStream) {
        this.opt = new PrcOpt(opt);
        this.handler = handler;
        this.rtk = new Rtk();
        this.rtk.opt = this.opt;

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

    public RinexRtkProcessor(PrcOpt opt, PosHandler handler) {
        this(opt, handler, null);
    }

    public RinexRtkProcessor(PrcOpt opt) {
        this(opt, null, null);
    }

    public RinexRtkProcessor() {
        this(createDefaultOpt());
    }

    public static PrcOpt createDefaultOpt() {
        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_KINEMA;
        opt.nf = 2;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_CMP;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;
        opt.modear = Constants.ARMODE_FIXHOLD;
        opt.refposmode = Constants.REFPOS_FIXED;
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
        }
    }

    /**
     * 处理RINEX观测文件和导航文件进行RTK定位。
     *
     * @param roverObsPath 流动站RINEX观测文件路径(.obs)
     * @param baseObsPath  基准站RINEX观测文件路径(.obs)，可为null
     * @param navPath      RINEX导航文件路径(.nav)
     * @return RTK定位结果
     */
    public RtkProcessor.RtkResult process(String roverObsPath, String baseObsPath, String navPath) {
        RinexParser roverParser = new RinexParser();
        boolean roverOk = roverParser.parseObs(roverObsPath);
        if (!roverOk) {
            throw new RuntimeException("Failed to parse rover RINEX observation file: " + roverObsPath);
        }

        Nav nav;
        if (navPath != null) {
            RinexParser navParser = new RinexParser();
            boolean navOk = navParser.parseNav(navPath);
            if (!navOk) {
                throw new RuntimeException("Failed to parse RINEX navigation file: " + navPath);
            }
            nav = navParser.nav;
        } else {
            nav = roverParser.nav;
        }

        if (roverParser.obs.n == 0) {
            log.warn("No observation data in RINEX file");
            return new RtkProcessor.RtkResult(0, 0, 0, solutions);
        }

        if (roverParser.sta != null && roverParser.sta.pos != null
                && (roverParser.sta.pos[0] != 0.0 || roverParser.sta.pos[1] != 0.0 || roverParser.sta.pos[2] != 0.0)) {
            if (rtk.rb[0] == 0.0 && rtk.rb[1] == 0.0 && rtk.rb[2] == 0.0) {
                System.arraycopy(roverParser.sta.pos, 0, rtk.rb, 0, 3);
                log.info("Using approximate position from RINEX header as base: ({}, {}, {})",
                        rtk.rb[0], rtk.rb[1], rtk.rb[2]);
            }
        }

        List<List<Obsd>> roverEpochs = groupObsByEpoch(roverParser.obs.data, roverParser.obs.n);
        log.info("RINEX RTK: rover {} total observations, {} epochs", roverParser.obs.n, roverEpochs.size());

        List<List<Obsd>> baseEpochs = null;
        if (baseObsPath != null) {
            RinexParser baseParser = new RinexParser();
            boolean baseOk = baseParser.parseObs(baseObsPath);
            if (!baseOk) {
                throw new RuntimeException("Failed to parse base RINEX observation file: " + baseObsPath);
            }

            if (baseParser.sta != null && baseParser.sta.pos != null
                    && (baseParser.sta.pos[0] != 0.0 || baseParser.sta.pos[1] != 0.0 || baseParser.sta.pos[2] != 0.0)) {
                System.arraycopy(baseParser.sta.pos, 0, rtk.rb, 0, 3);
                log.info("Using base station position from RINEX header: ({}, {}, {})",
                        rtk.rb[0], rtk.rb[1], rtk.rb[2]);
            }

            if (baseParser.obs.n > 0) {
                baseEpochs = groupObsByEpoch(baseParser.obs.data, baseParser.obs.n);
                log.info("RINEX RTK: base {} total observations, {} epochs", baseParser.obs.n, baseEpochs.size());
            }
        }

        for (List<Obsd> roverEpoch : roverEpochs) {
            List<Obsd> epochObs = new ArrayList<>(roverEpoch);
            GTime epochTime = epochObs.get(0).time;

            for (Obsd o : epochObs) {
                o.rcv = 1;
            }

            if (baseEpochs != null) {
                List<Obsd> baseMatch = findMatchingBaseEpoch(baseEpochs, epochTime);
                if (baseMatch != null) {
                    for (Obsd o : baseMatch) {
                        o.rcv = 2;
                    }
                    epochObs.addAll(baseMatch);
                }
            }

            Obsd[] obsArray = epochObs.toArray(new Obsd[0]);
            processEpoch(obsArray, obsArray.length, epochTime, nav);
        }

        log.info("RINEX RTK complete: total={}, success={}, fail={}", totalEpochs, successCount, failCount);

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

        return new RtkProcessor.RtkResult(totalEpochs, successCount, failCount, solutions);
    }

    /**
     * 处理RINEX观测文件（无单独导航文件，使用观测文件中的星历）。
     *
     * @param roverObsPath 流动站RINEX观测文件路径
     * @param baseObsPath  基准站RINEX观测文件路径，可为null
     * @return RTK定位结果
     */
    public RtkProcessor.RtkResult process(String roverObsPath, String baseObsPath) {
        return process(roverObsPath, baseObsPath, null);
    }

    public static RtkProcessor.RtkResult processRinex(String roverObsPath, String baseObsPath,
                                                       String navPath, PrcOpt opt) {
        RinexRtkProcessor processor = new RinexRtkProcessor(opt);
        return processor.process(roverObsPath, baseObsPath, navPath);
    }

    public static RtkProcessor.RtkResult processRinex(String roverObsPath, String baseObsPath,
                                                       String navPath) {
        return processRinex(roverObsPath, baseObsPath, navPath, createDefaultOpt());
    }

    public static RtkProcessor.RtkResult processRinex(String roverObsPath, String baseObsPath) {
        return processRinex(roverObsPath, baseObsPath, null, createDefaultOpt());
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

    private List<Obsd> findMatchingBaseEpoch(List<List<Obsd>> baseEpochs, GTime roverTime) {
        double bestDt = Double.MAX_VALUE;
        int bestIdx = -1;
        double maxTdiff = opt.maxtdiff > 0 ? opt.maxtdiff : 30.0;

        for (int i = 0; i < baseEpochs.size(); i++) {
            if (baseEpochs.get(i).isEmpty()) continue;
            double dt = Math.abs(TimeSystem.timediff(roverTime, baseEpochs.get(i).get(0).time));
            if (dt < bestDt && dt <= maxTdiff) {
                bestDt = dt;
                bestIdx = i;
            }
        }

        if (bestIdx < 0) return null;
        return baseEpochs.remove(bestIdx);
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
                    writer.write(RtkProcessor.formatSolutionLine(solCopy));
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
}