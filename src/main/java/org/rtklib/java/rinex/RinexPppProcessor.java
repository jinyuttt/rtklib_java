package org.rtklib.java.rinex;

import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.ephemeris.ClkReader;
import org.rtklib.java.ephemeris.Sp3Reader;
import org.rtklib.java.pntpos.PntPos;
import org.rtklib.java.ppp.PppCore;
import org.rtklib.java.time.TimeSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class RinexPppProcessor {

    private static final Logger log = LoggerFactory.getLogger(RinexPppProcessor.class);

    private static final String POS_HEADER =
            "# PPP (Precise Point Positioning) Result - RINEX Input (EXPERIMENTAL)\n" +
            "#  Date       Time       lat(deg)      lon(deg)     height(m)  Q  ns   sdn(m)   sde(m)   sdu(m)  sdne(m)  sdeu(m)  sdun(m) age(s)  ratio\n";

    private final PrcOpt opt;
    private final Rtk rtk;

    private int totalEpochs = 0;
    private int successCount = 0;
    private int failCount = 0;
    private final List<Sol> solutions = new ArrayList<>();
    private final Writer writer;

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

    public RinexPppProcessor(PrcOpt opt) {
        this(opt, null);
    }

    public RinexPppProcessor(PrcOpt opt, OutputStream outputStream) {
        this.opt = new PrcOpt(opt);
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

    public PppResult process(String obsFilePath, String navFilePath,
                             String sp3FilePath, String clkFilePath) {
        RinexParser parser = new RinexParser();
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
        log.info("PPP: {} total observations, {} epochs", parser.obs.n, epochGroups.size());

        for (List<Obsd> epochObs : epochGroups) {
            Obsd[] obsArray = epochObs.toArray(new Obsd[0]);
            int n = obsArray.length;
            processEpoch(obsArray, n, parser.nav);
        }

        log.info("PPP complete: total={}, success={}, fail={}", totalEpochs, successCount, failCount);

        if (writer != null) {
            try {
                writer.write(String.format("# Total: %d, Success: %d, Fail: %d\n",
                        totalEpochs, successCount, failCount));
                writer.flush();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }

        return new PppResult(totalEpochs, successCount, failCount, solutions);
    }

    private void processEpoch(Obsd[] obs, int n, Nav nav) {
        totalEpochs++;

        for (int i = 0; i < n; i++) obs[i].rcv = 1;

        GTime prevTime = new GTime(rtk.sol.time);
        if (rtk.sol.stat == Constants.SOLQ_NONE) {
            PntPos.pntpos(obs, n, nav, opt, rtk.sol, null, rtk.ssat);
        } else {
            rtk.sol.time = obs[0].time;
        }

        if (prevTime.time != 0) {
            rtk.tt = TimeSystem.timediff(rtk.sol.time, prevTime);
        } else {
            rtk.tt = 0.0;
        }

        PppCore.pppos(rtk, obs, n, nav);

        if (rtk.sol.stat != Constants.SOLQ_NONE) {
            successCount++;
            solutions.add(new Sol(rtk.sol));
            writeSolution(rtk.sol);
        } else {
            failCount++;
        }
    }

    private void writeSolution(Sol sol) {
        if (writer == null) return;
        try {
            double[] pos = new double[3];
            CoordTransform.ecef2pos(sol.rr, pos);
            double lat = Math.toDegrees(pos[0]);
            double lon = Math.toDegrees(pos[1]);
            double hgt = pos[2];
            double[] ep = new double[6];
            TimeSystem.time2epoch(sol.time, ep);
            String timeStr = String.format("%04.0f/%02.0f/%02.0f %02.0f:%02.0f:%06.3f",
                    ep[0], ep[1], ep[2], ep[3], ep[4], ep[5]);
            writer.write(String.format("%s  %12.9f %13.9f %10.3f %2d %3d %8.4f %8.4f %8.4f %8.4f %8.4f %8.4f\n",
                    timeStr,
                    lat, lon, hgt, sol.stat, sol.ns,
                    Math.sqrt(sol.qr[0]), Math.sqrt(sol.qr[1]), Math.sqrt(sol.qr[2]),
                    Math.sqrt(Math.abs(sol.qr[3])), Math.sqrt(Math.abs(sol.qr[4])),
                    Math.sqrt(Math.abs(sol.qr[5]))));
            writer.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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