package org.rtklib.java.rinex;

import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.rtkpos.RtkProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * RINEX PPP processor (thin shell, delegates to PostPosProcessor).
 */
public class RinexPppProcessor {

    private static final Logger log = LoggerFactory.getLogger(RinexPppProcessor.class);

    private static final String POS_HEADER =
            "# PPP (Precise Point Positioning) Result - RINEX Input (EXPERIMENTAL)\n" +
            "#  Date       Time       lat(deg)      lon(deg)     height(m)  Q  ns   sdn(m)   sde(m)   sdu(m)  sdne(m)  sdeu(m)  sdun(m) age(s)  ratio gdop  pdop  hdop  vdop\n";

    private final PrcOpt opt;
    private final SolOpt solOpt;
    private final Writer writer;
    private RtkConfig rtkConfig;
    private String atxPath;
    private String fcbPath;
    private String osbPath;
    private String updPath;
    private String dcbPath;

    private int totalEpochs = 0;
    private int successCount = 0;
    private int failCount = 0;
    private final List<Sol> solutions = new ArrayList<>();

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
        opt.posopt[0] = 1;
        opt.posopt[1] = 1;
        opt.posopt[2] = 1;
        opt.posopt[3] = 1;
        opt.tidecorr = 1;
        return opt;
    }

    public RinexPppProcessor(PrcOpt opt, SolOpt solOpt, OutputStream outputStream) {
        this.opt = new PrcOpt(opt);
        this.solOpt = solOpt != null ? new SolOpt(solOpt) : null;
        if (outputStream != null) {
            this.writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8));
            try { this.writer.write(POS_HEADER); this.writer.flush(); } catch (IOException e) { throw new UncheckedIOException(e); }
        } else { this.writer = null; }
    }

    public RinexPppProcessor(PrcOpt opt, OutputStream outputStream) {
        this(opt, null, outputStream);
    }

    public RinexPppProcessor(PrcOpt opt, SolOpt solOpt) {
        this(opt, solOpt, null);
    }

    public RinexPppProcessor(PrcOpt opt) {
        this(opt, null, null);
    }

    /** 构造带 RtkConfig 的处理器（JAVA-ONLY 开关如 enablePppIsbClockInit 经此传入） */
    public RinexPppProcessor(PrcOpt opt, SolOpt solOpt, RtkConfig rtkConfig, OutputStream outputStream) {
        this(opt, solOpt, outputStream);
        this.rtkConfig = rtkConfig;
    }

    public void setAtxPath(String atxPath) {
        this.atxPath = atxPath;
    }

    public void setFcbPath(String fcbPath) {
        this.fcbPath = fcbPath;
    }

    public void setOsbPath(String osbPath) {
        this.osbPath = osbPath;
    }

    public void setUpdPath(String updPath) {
        this.updPath = updPath;
    }

    public void setDcbPath(String dcbPath) {
        this.dcbPath = dcbPath;
    }

    public PppResult process(String obsFilePath, String navFilePath,
                             String sp3FilePath, String clkFilePath) {
        PrcOpt procOpt = new PrcOpt(opt);
        SolOpt effectiveSolOpt;
        if (solOpt != null) {
            effectiveSolOpt = new SolOpt(solOpt);
        } else {
            effectiveSolOpt = new SolOpt();
            if (isStaticMode(procOpt.mode)) {
                effectiveSolOpt.solstatic = 1;
            }
        }
        PostPosProcessor proc = rtkConfig != null
                ? new PostPosProcessor(procOpt, effectiveSolOpt, rtkConfig)
                : new PostPosProcessor(procOpt, effectiveSolOpt);
        if (fcbPath != null) proc.setFcbPath(fcbPath);
        if (osbPath != null) proc.setOsbPath(osbPath);
        if (updPath != null) proc.setUpdPath(updPath);
        if (dcbPath != null) proc.setDcbPath(dcbPath);
        PostPosProcessor.PostPosResult result = proc.process(obsFilePath, null, navFilePath, sp3FilePath, clkFilePath, null, atxPath);

        totalEpochs = result.totalEpochs;
        successCount = result.successCount;
        failCount = result.failCount;
        for (Sol sol : result.solList) { solutions.add(new Sol(sol)); }

        log.info("RINEX PPP: total={}, success={}, fail={}", totalEpochs, successCount, failCount);

        if (writer != null) {
            try {
                writer.write(String.format("# Total: %d, Success: %d, Fail: %d\n", totalEpochs, successCount, failCount));
                writer.flush();
            } catch (IOException e) { throw new UncheckedIOException(e); }
        }
        return new PppResult(totalEpochs, successCount, failCount, result.solutions);
    }

    private PppResult buildResult() {
        List<SolData> solDataList = solutions.stream()
                .map(sol -> new SolData(sol, opt.posMask))
                .toList();
        return new PppResult(totalEpochs, successCount, failCount, solDataList);
    }

    private static boolean isStaticMode(int mode) {
        return mode == Constants.PMODE_PPP_STATIC;
    }

    public static class PppResult {
        public final int totalEpochs;
        public final int successCount;
        public final int failCount;
        public final List<SolData> solutions;

        public PppResult(int totalEpochs, int successCount, int failCount, List<SolData> solutions) {
            this.totalEpochs = totalEpochs;
            this.successCount = successCount;
            this.failCount = failCount;
            this.solutions = solutions;
        }
    }
}