package org.rtklib.java.rinex;

import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.pntpos.PosHandler;
import org.rtklib.java.rtkpos.RtkProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * RINEX SPP processor (thin shell, delegates to PostPosProcessor).
 */
public class RinexSppProcessor {

    private static final Logger log = LoggerFactory.getLogger(RinexSppProcessor.class);

    private static final String POS_HEADER =
            "# SPP (Single Point Positioning) Result - RINEX Input\n" +
            "#  Date       Time       lat(deg)      lon(deg)     height(m)  Q  ns   sdn(m)   sde(m)   sdu(m)  sdne(m)  sdeu(m)  sdun(m) age(s)  ratio gdop  pdop  hdop  vdop\n";

    private final PrcOpt opt;
    private final PosHandler handler;
    private final Writer writer;

    private int totalEpochs = 0;
    private int successCount = 0;
    private int failCount = 0;
    private final List<Sol> solutions = new ArrayList<>();

    public RinexSppProcessor(PrcOpt opt, PosHandler handler, OutputStream outputStream) {
        this.opt = new PrcOpt(opt);
        this.handler = handler;

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

    public SppResult process(String obsFilePath, String navFilePath) {
        PrcOpt procOpt = new PrcOpt(opt);
        PostPosProcessor proc = new PostPosProcessor(procOpt, new SolOpt());
        PostPosProcessor.PostPosResult result = proc.process(obsFilePath, null, navFilePath);

        totalEpochs = result.totalEpochs;
        successCount = result.successCount;
        failCount = result.failCount;
        for (Sol sol : result.solList) {
            solutions.add(new Sol(sol));
        }

        log.info("RINEX SPP: total={}, success={}, fail={}", totalEpochs, successCount, failCount);

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

    public static SppResult processRinex(String obsFilePath, String navFilePath, PrcOpt opt) {
        return new RinexSppProcessor(opt).process(obsFilePath, navFilePath);
    }

    public static SppResult processRinex(String obsFilePath, String navFilePath) {
        return processRinex(obsFilePath, navFilePath, createDefaultOpt());
    }

    private SppResult buildResult() {
        List<SolData> solDataList = solutions.stream()
                .map(sol -> new SolData(sol, opt.posMask))
                .toList();
        return new SppResult(totalEpochs, successCount, failCount, solDataList);
    }

    public static class SppResult {
        public final int totalEpochs;
        public final int successCount;
        public final int failCount;
        public final List<SolData> solutions;

        public SppResult(int totalEpochs, int successCount, int failCount, List<SolData> solutions) {
            this.totalEpochs = totalEpochs;
            this.successCount = successCount;
            this.failCount = failCount;
            this.solutions = solutions;
        }
    }
}