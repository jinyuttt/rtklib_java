package org.rtklib.java.rinex;

import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.ephemeris.ClkReader;
import org.rtklib.java.ephemeris.Sp3Reader;
import org.rtklib.java.pntpos.PntPos;
import org.rtklib.java.pntpos.PosHandler;
import org.rtklib.java.ppp.PppCore;
import org.rtklib.java.rtkpos.CombinedFilter;
import org.rtklib.java.rtkpos.RtkCore;
import org.rtklib.java.rtkpos.RtkProcessor;
import org.rtklib.java.time.TimeSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class PostPosProcessor {

    private static final Logger log = LoggerFactory.getLogger(PostPosProcessor.class);

    private static final String POS_HEADER =
            "# Post-Processing Result\n" +
            "#  Date       Time       lat(deg)      lon(deg)     height(m)  Q  ns   sdn(m)   sde(m)   sdu(m)  sdne(m)  sdeu(m)  sdun(m) age(s)  ratio gdop  pdop  hdop  vdop\n";

    private final PrcOpt opt;
    private final SolOpt sopt;
    private final PosHandler handler;
    private final Writer writer;

    public PostPosProcessor(PrcOpt opt, SolOpt sopt, PosHandler handler, OutputStream outputStream) {
        this.opt = new PrcOpt(opt);
        this.sopt = sopt != null ? new SolOpt(sopt) : new SolOpt();
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

    public PostPosProcessor(PrcOpt opt, SolOpt sopt, PosHandler handler) {
        this(opt, sopt, handler, null);
    }

    public PostPosProcessor(PrcOpt opt, SolOpt sopt) {
        this(opt, sopt, null, null);
    }

    public PostPosProcessor(PrcOpt opt) {
        this(opt, null, null, null);
    }

    public PostPosResult process(String roverObsPath, String baseObsPath,
                                 String navPath, String sp3Path, String clkPath) {
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

        if (sp3Path != null) {
            Sp3Reader.readsp3(sp3Path, nav, 0);
            log.info("Loaded SP3: {} ephemerides", nav.ne);
        }
        if (clkPath != null) {
            ClkReader.readclk(clkPath, nav);
            log.info("Loaded CLK: {} records", nav.nc);
        }

        if (roverParser.obs.n == 0) {
            log.warn("No observation data in RINEX file");
            return new PostPosResult(0, 0, 0, List.of());
        }

        double[] approxPos = null;
        if (roverParser.sta != null && roverParser.sta.pos != null
                && (roverParser.sta.pos[0] != 0.0 || roverParser.sta.pos[1] != 0.0 || roverParser.sta.pos[2] != 0.0)) {
            approxPos = roverParser.sta.pos.clone();
            log.info("Using approximate position from RINEX header: ({}, {}, {})",
                    approxPos[0], approxPos[1], approxPos[2]);
        }

        List<List<Obsd>> roverEpochs = groupObsByEpoch(roverParser.obs.data, roverParser.obs.n);
        log.info("PostPos: rover {} total observations, {} epochs", roverParser.obs.n, roverEpochs.size());

        List<List<Obsd>> baseEpochs = null;
        double[] basePos = null;
        if (baseObsPath != null && (opt.mode == Constants.PMODE_DGPS || opt.mode == Constants.PMODE_KINEMA
                || opt.mode == Constants.PMODE_STATIC || opt.mode == Constants.PMODE_STATIC_START
                || opt.mode == Constants.PMODE_MOVEB || opt.mode == Constants.PMODE_FIXED)) {
            RinexParser baseParser = new RinexParser();
            boolean baseOk = baseParser.parseObs(baseObsPath);
            if (!baseOk) {
                throw new RuntimeException("Failed to parse base RINEX observation file: " + baseObsPath);
            }

            if (baseParser.sta != null && baseParser.sta.pos != null
                    && (baseParser.sta.pos[0] != 0.0 || baseParser.sta.pos[1] != 0.0 || baseParser.sta.pos[2] != 0.0)) {
                basePos = baseParser.sta.pos.clone();
                log.info("Using base station position from RINEX header: ({}, {}, {})",
                        basePos[0], basePos[1], basePos[2]);
            }

            if (baseParser.obs.n > 0) {
                baseEpochs = groupObsByEpoch(baseParser.obs.data, baseParser.obs.n);
                log.info("PostPos: base {} total observations, {} epochs", baseParser.obs.n, baseEpochs.size());
            }
        }

        if (opt.soltype == Constants.SOLTYPE_FORWARD) {
            return processForward(roverEpochs, baseEpochs, nav, approxPos, basePos);
        } else if (opt.soltype == Constants.SOLTYPE_BACKWARD) {
            return processBackward(roverEpochs, baseEpochs, nav, approxPos, basePos);
        } else {
            return processCombined(roverEpochs, baseEpochs, nav, approxPos, basePos);
        }
    }

    public PostPosResult process(String roverObsPath, String baseObsPath, String navPath) {
        return process(roverObsPath, baseObsPath, navPath, null, null);
    }

    public PostPosResult process(String roverObsPath, String navPath) {
        return process(roverObsPath, null, navPath, null, null);
    }

    private PostPosResult processForward(List<List<Obsd>> roverEpochs,
                                          List<List<Obsd>> baseEpochs, Nav nav,
                                          double[] approxPos, double[] basePos) {
        Rtk rtk = createRtk(approxPos, basePos);
        int totalEpochs = 0, successCount = 0, failCount = 0;
        List<Sol> solutions = new ArrayList<>();

        List<List<Obsd>> baseEpochsCopy = copyBaseEpochs(baseEpochs);

        for (List<Obsd> roverEpoch : roverEpochs) {
            Obsd[] obs = buildEpochObs(roverEpoch, baseEpochsCopy);
            totalEpochs++;
            int result = RtkCore.rtkpos(rtk, obs, obs.length, nav);
            if (result == 1 && rtk.sol.stat != Constants.SOLQ_NONE) {
                successCount++;
                Sol solCopy = new Sol(rtk.sol);
                solutions.add(solCopy);
                outputSolution(solCopy);
            } else {
                failCount++;
            }
        }

        finishOutput(totalEpochs, successCount, failCount);
        return new PostPosResult(totalEpochs, successCount, failCount, toSolDataList(solutions));
    }

    private PostPosResult processBackward(List<List<Obsd>> roverEpochs,
                                           List<List<Obsd>> baseEpochs, Nav nav,
                                           double[] approxPos, double[] basePos) {
        Rtk rtk = createRtk(approxPos, basePos);
        int totalEpochs = 0, successCount = 0, failCount = 0;
        List<Sol> solutions = new ArrayList<>();

        List<List<Obsd>> baseEpochsCopy = copyBaseEpochs(baseEpochs);
        List<List<Obsd>> reversedRover = new ArrayList<>(roverEpochs);
        Collections.reverse(reversedRover);
        List<List<Obsd>> reversedBase = baseEpochsCopy != null ? new ArrayList<>(baseEpochsCopy) : null;
        if (reversedBase != null) {
            Collections.reverse(reversedBase);
        }

        for (List<Obsd> roverEpoch : reversedRover) {
            Obsd[] obs = buildEpochObs(roverEpoch, reversedBase);
            totalEpochs++;
            int result = RtkCore.rtkpos(rtk, obs, obs.length, nav);
            if (result == 1 && rtk.sol.stat != Constants.SOLQ_NONE) {
                successCount++;
                Sol solCopy = new Sol(rtk.sol);
                solutions.add(solCopy);
                outputSolution(solCopy);
            } else {
                failCount++;
            }
        }

        Collections.reverse(solutions);

        finishOutput(totalEpochs, successCount, failCount);
        return new PostPosResult(totalEpochs, successCount, failCount, toSolDataList(solutions));
    }

    private PostPosResult processCombined(List<List<Obsd>> roverEpochs,
                                           List<List<Obsd>> baseEpochs, Nav nav,
                                           double[] approxPos, double[] basePos) {
        Rtk rtkF = createRtk(approxPos, basePos);
        List<Sol> solfList = new ArrayList<>();
        List<double[]> rbfList = new ArrayList<>();

        List<List<Obsd>> baseEpochsFwd = copyBaseEpochs(baseEpochs);

        for (List<Obsd> roverEpoch : roverEpochs) {
            Obsd[] obs = buildEpochObs(roverEpoch, baseEpochsFwd);
            int result = RtkCore.rtkpos(rtkF, obs, obs.length, nav);
            if (result == 1 && rtkF.sol.stat != Constants.SOLQ_NONE) {
                solfList.add(new Sol(rtkF.sol));
                rbfList.add(new double[]{rtkF.rb[0], rtkF.rb[1], rtkF.rb[2]});
            } else {
                solfList.add(null);
                rbfList.add(null);
            }
        }
        log.info("Forward pass: {} epochs, {} valid solutions", roverEpochs.size(), solfList.stream().filter(s -> s != null).count());

        Rtk rtkB;
        if (opt.soltype == Constants.SOLTYPE_COMBINED_NORESET) {
            rtkB = rtkF;
        } else {
            rtkB = createRtk(approxPos, basePos);
        }

        List<Sol> solbList = new ArrayList<>();
        List<double[]> rbbList = new ArrayList<>();

        List<List<Obsd>> baseEpochsBwd = copyBaseEpochs(baseEpochs);
        List<List<Obsd>> reversedRover = new ArrayList<>(roverEpochs);
        Collections.reverse(reversedRover);
        List<List<Obsd>> reversedBase = baseEpochsBwd != null ? new ArrayList<>(baseEpochsBwd) : null;
        if (reversedBase != null) {
            Collections.reverse(reversedBase);
        }

        for (List<Obsd> roverEpoch : reversedRover) {
            Obsd[] obs = buildEpochObs(roverEpoch, reversedBase);
            int result = RtkCore.rtkpos(rtkB, obs, obs.length, nav);
            if (result == 1 && rtkB.sol.stat != Constants.SOLQ_NONE) {
                solbList.add(new Sol(rtkB.sol));
                rbbList.add(new double[]{rtkB.rb[0], rtkB.rb[1], rtkB.rb[2]});
            } else {
                solbList.add(null);
                rbbList.add(null);
            }
        }
        Collections.reverse(solbList);
        Collections.reverse(rbbList);
        log.info("Backward pass: {} epochs, {} valid solutions", roverEpochs.size(), solbList.stream().filter(s -> s != null).count());

        Sol[] solf = solfList.toArray(new Sol[0]);
        Sol[] solb = solbList.toArray(new Sol[0]);
        double[][] rbf = rbfList.toArray(new double[0][]);
        double[][] rbb = rbbList.toArray(new double[0][]);

        List<Sol> combined = CombinedFilter.combine(solf, solb, rbf, rbb, opt, sopt);

        int totalEpochs = combined.size();
        int successCount = (int) combined.stream().filter(s -> s != null && s.stat != Constants.SOLQ_NONE).count();
        int failCount = totalEpochs - successCount;

        for (Sol sol : combined) {
            if (sol != null) {
                outputSolution(sol);
            }
        }

        finishOutput(totalEpochs, successCount, failCount);
        return new PostPosResult(totalEpochs, successCount, failCount, toSolDataList(combined));
    }

    private Rtk createRtk(double[] approxPos, double[] basePos) {
        Rtk rtk = new Rtk();
        rtk.opt = new PrcOpt(opt);

        if (approxPos != null) {
            System.arraycopy(approxPos, 0, rtk.sol.rr, 0, 3);
        }
        if (basePos != null) {
            System.arraycopy(basePos, 0, rtk.rb, 0, 3);
        }

        return rtk;
    }

    private Obsd[] buildEpochObs(List<Obsd> roverEpoch, List<List<Obsd>> baseEpochs) {
        List<Obsd> epochObs = new ArrayList<>(roverEpoch);
        GTime epochTime = roverEpoch.get(0).time;

        for (Obsd o : epochObs) {
            o.rcv = 1;
        }

        if (baseEpochs != null && (opt.mode == Constants.PMODE_DGPS || opt.mode == Constants.PMODE_KINEMA
                || opt.mode == Constants.PMODE_STATIC || opt.mode == Constants.PMODE_STATIC_START
                || opt.mode == Constants.PMODE_MOVEB || opt.mode == Constants.PMODE_FIXED)) {
            List<Obsd> baseMatch = findMatchingBaseEpoch(baseEpochs, epochTime);
            if (baseMatch != null) {
                for (Obsd o : baseMatch) {
                    o.rcv = 2;
                }
                epochObs.addAll(baseMatch);
            }
        }

        return epochObs.toArray(new Obsd[0]);
    }

    private List<List<Obsd>> copyBaseEpochs(List<List<Obsd>> baseEpochs) {
        if (baseEpochs == null) return null;
        List<List<Obsd>> copy = new ArrayList<>();
        for (List<Obsd> epoch : baseEpochs) {
            copy.add(new ArrayList<>(epoch));
        }
        return copy;
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

    private void outputSolution(Sol sol) {
        if (handler != null) {
            handler.onSolution(new Sol(sol), null);
            handler.onResult(new SolData(sol, opt.posMask));
        }
        if (writer != null) {
            try {
                writer.write(RtkProcessor.formatSolutionLine(sol));
                writer.flush();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }
    }

    private void finishOutput(int totalEpochs, int successCount, int failCount) {
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
    }

    private List<SolData> toSolDataList(List<Sol> solutions) {
        List<SolData> list = new ArrayList<>();
        for (Sol sol : solutions) {
            if (sol != null) {
                list.add(new SolData(sol, opt.posMask));
            }
        }
        return list;
    }

    public static class PostPosResult {
        public final int totalEpochs;
        public final int successCount;
        public final int failCount;
        public final List<SolData> solutions;

        public PostPosResult(int totalEpochs, int successCount, int failCount, List<SolData> solutions) {
            this.totalEpochs = totalEpochs;
            this.successCount = successCount;
            this.failCount = failCount;
            this.solutions = solutions;
        }
    }
}