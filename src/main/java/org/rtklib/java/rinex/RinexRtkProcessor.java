package org.rtklib.java.rinex;

import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.pntpos.PosHandler;
import org.rtklib.java.rtkpos.RtkProcessor;
import org.rtklib.java.trace.TraceCallback;
import org.rtklib.java.trace.TraceControl;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * RINEX文件RTK相对定位处理器（薄壳，委托PostPosProcessor）。
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
 * </pre>
 */
public class RinexRtkProcessor {

    private static final Logger log = LoggerFactory.getLogger(RinexRtkProcessor.class);

    private static final String POS_HEADER =
            "# RTK (Relative Positioning) Result - RINEX Input\n" +
            "#  Date       Time       lat(deg)      lon(deg)     height(m)  Q  ns   sdn(m)   sde(m)   sdu(m)  sdne(m)  sdeu(m)  sdun(m) age(s)  ratio gdop  pdop  hdop  vdop\n";

    private final PrcOpt opt;
    private final SolOpt solOpt;
    private final PosHandler handler;
    private final Writer writer;

    private final Rtk rtk;
    private int totalEpochs = 0;
    private int successCount = 0;
    private int failCount = 0;
    private final List<Sol> solutions = new ArrayList<>();

    public RinexRtkProcessor(PrcOpt opt, SolOpt solOpt, PosHandler handler, OutputStream outputStream) {
        this.opt = new PrcOpt(opt);
        this.solOpt = solOpt != null ? new SolOpt(solOpt) : null;
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

    public RinexRtkProcessor(PrcOpt opt, PosHandler handler, OutputStream outputStream) {
        this(opt, null, handler, outputStream);
    }

    public RinexRtkProcessor(PrcOpt opt, PosHandler handler) {
        this(opt, null, handler, null);
    }

    public RinexRtkProcessor(PrcOpt opt) {
        this(opt, null, null, null);
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
        opt.refpos = Constants.POSOPT_POS_XYZ;
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
     * 委托PostPosProcessor，支持前向/后向/组合模式。
     *
     * @param roverObsPath 流动站RINEX观测文件路径(.obs)
     * @param baseObsPath  基准站RINEX观测文件路径(.obs)，可为null
     * @param navPath      RINEX导航文件路径(.nav)
     * @return RTK定位结果
     */
    public RtkProcessor.RtkResult process(String roverObsPath, String baseObsPath, String navPath) {
        PrcOpt procOpt = new PrcOpt(opt);
        if (rtk.rb[0] != 0.0 || rtk.rb[1] != 0.0 || rtk.rb[2] != 0.0) {
            procOpt.rb = new double[]{rtk.rb[0], rtk.rb[1], rtk.rb[2]};
        }
        SolOpt effectiveSolOpt = solOpt != null ? new SolOpt(solOpt) : new SolOpt();
        if (isStaticMode(procOpt.mode) && effectiveSolOpt.solstatic == 0) {
            effectiveSolOpt.solstatic = 1;
        }
        PostPosProcessor proc = new PostPosProcessor(procOpt, effectiveSolOpt);
        PostPosProcessor.PostPosResult result = proc.process(roverObsPath, baseObsPath, navPath);

        totalEpochs = result.totalEpochs;
        successCount = result.successCount;
        failCount = result.failCount;
        for (Sol sol : result.solList) {
            solutions.add(new Sol(sol));
        }

        log.info("RINEX RTK: total={}, success={}, fail={}", totalEpochs, successCount, failCount);

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
        return buildResult();
    }

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

    private RtkProcessor.RtkResult buildResult() {
        double[] rb = (rtk.rb[0] != 0 || rtk.rb[1] != 0 || rtk.rb[2] != 0) ? rtk.rb : null;
        List<SolData> solDataList = solutions.stream()
                .map(sol -> new SolData(sol, opt.posMask, rb))
                .toList();
        return new RtkProcessor.RtkResult(totalEpochs, successCount, failCount, solDataList);
    }

    private static boolean isStaticMode(int mode) {
        return mode == Constants.PMODE_STATIC || mode == Constants.PMODE_STATIC_START;
    }
}