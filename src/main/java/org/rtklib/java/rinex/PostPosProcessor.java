package org.rtklib.java.rinex;

import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.ephemeris.ClkReader;
import org.rtklib.java.ephemeris.Sp3Reader;
import org.rtklib.java.pntpos.PntPos;
import org.rtklib.java.common.RtklibCommon;
import org.rtklib.java.data.SbsMsg;
import org.rtklib.java.ionosphere.SbasCorrection;
import org.rtklib.java.ionosphere.SbsMsgReader;
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

    private List<SbsMsg> sbsMsgs = Collections.emptyList();
    private int sbsMsgIdx;

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
                                 String navPath, String sp3Path, String clkPath,
                                 String sbsPath) {
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
        if (sbsPath != null) {
            sbsMsgs = SbsMsgReader.readsbsmsg(sbsPath);
            log.info("Loaded {} SBAS messages for epoch-by-epoch application", sbsMsgs.size());
        }

        if (roverParser.obs.n == 0) {
            log.warn("No observation data in RINEX file");
            return new PostPosResult(0, 0, 0, List.of(), List.of());
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
        Sta baseSta = null;
        if (baseObsPath != null && isRelativeMode(opt.mode)) {
            RinexParser baseParser = new RinexParser();
            boolean baseOk = baseParser.parseObs(baseObsPath);
            if (!baseOk) {
                throw new RuntimeException("Failed to parse base RINEX observation file: " + baseObsPath);
            }
            baseSta = baseParser.sta;

            if (baseParser.obs.n > 0) {
                baseEpochs = groupObsByEpoch(baseParser.obs.data, baseParser.obs.n);
                log.info("PostPos: base {} total observations, {} epochs", baseParser.obs.n, baseEpochs.size());
            }
        }

        if (isRelativeMode(opt.mode)) {
            basePos = resolveBasePos(opt.refpos, opt.rb, baseSta, baseEpochs, nav);
        }

        if (opt.mode == Constants.PMODE_FIXED) {
            approxPos = resolveRoverPos(opt.rovpos, opt.ru, roverParser.sta, roverEpochs, nav);
        }

        if (opt.soltype == Constants.SOLTYPE_FORWARD) {
            return processForward(roverEpochs, baseEpochs, nav, approxPos, basePos);
        } else if (opt.soltype == Constants.SOLTYPE_BACKWARD) {
            return processBackward(roverEpochs, baseEpochs, nav, approxPos, basePos);
        } else {
            return processCombined(roverEpochs, baseEpochs, nav, approxPos, basePos);
        }
    }

    public PostPosResult process(String roverObsPath, String baseObsPath,
                                 String navPath, String sp3Path, String clkPath) {
        return process(roverObsPath, baseObsPath, navPath, sp3Path, clkPath, null);
    }

    public PostPosResult process(String roverObsPath, String baseObsPath, String navPath) {
        return process(roverObsPath, baseObsPath, navPath, null, null, null);
    }

    public PostPosResult process(String roverObsPath, String navPath) {
        return process(roverObsPath, null, navPath, null, null, null);
    }

    private PostPosResult processForward(List<List<Obsd>> roverEpochs,
                                          List<List<Obsd>> baseEpochs, Nav nav,
                                          double[] approxPos, double[] basePos) {
        Rtk rtk = createRtk(approxPos, basePos);
        int totalEpochs = 0, successCount = 0, failCount = 0;
        List<Sol> solutions = new ArrayList<>();
        sbsMsgIdx = 0;

        boolean solstatic = sopt != null && sopt.solstatic != 0 &&
                (opt.mode == Constants.PMODE_STATIC || opt.mode == Constants.PMODE_STATIC_START
                        || opt.mode == Constants.PMODE_PPP_STATIC);
        Sol bestSol = null;
        GTime bestTime = null;
        int[] pri = {6, 1, 2, 3, 4, 5, 1, 6};

        List<List<Obsd>> baseEpochsCopy = copyBaseEpochs(baseEpochs);

        for (List<Obsd> roverEpoch : roverEpochs) {
            Obsd[] obs = buildEpochObs(roverEpoch, baseEpochsCopy);
            if (obs.length == 0) continue;
            totalEpochs++;
            applySbsUpTo(obs[0].time, nav);
            RtklibCommon.corrPhaseBiasSsr(obs, obs.length, nav, opt.pppopt);
            int result = RtkCore.rtkpos(rtk, obs, obs.length, nav);
            if (result == 1 && rtk.sol.stat != Constants.SOLQ_NONE) {
                successCount++;
                Sol solCopy = new Sol(rtk.sol);
                if (!solstatic) {
                    solutions.add(solCopy);
                    outputSolution(solCopy);
                } else {
                    if (bestSol == null || pri[solCopy.stat] <= pri[bestSol.stat]) {
                        bestSol = solCopy;
                        if (bestTime == null || TimeSystem.timediff(solCopy.time, bestTime) < 0.0) {
                            bestTime = new GTime(solCopy.time);
                        }
                    }
                }
            } else {
                failCount++;
            }
        }

        if (solstatic && bestSol != null) {
            bestSol.time = bestTime != null ? bestTime : bestSol.time;
            solutions.add(bestSol);
            outputSolution(bestSol);
        }

        finishOutput(totalEpochs, successCount, failCount);
        return new PostPosResult(totalEpochs, successCount, failCount, toSolDataList(solutions), solutions);
    }

    private PostPosResult processBackward(List<List<Obsd>> roverEpochs, List<List<Obsd>> baseEpochs,
                                           Nav nav, double[] approxPos, double[] basePos) {
        Rtk rtk = createRtk(approxPos, basePos);
        int totalEpochs = 0, successCount = 0, failCount = 0;
        List<Sol> solutions = new ArrayList<>();
        sbsMsgIdx = 0;
        applyAllSbs(nav);

        boolean solstatic = sopt != null && sopt.solstatic != 0 &&
                (opt.mode == Constants.PMODE_STATIC || opt.mode == Constants.PMODE_STATIC_START
                        || opt.mode == Constants.PMODE_PPP_STATIC);
        Sol bestSol = null;
        GTime bestTime = null;
        int[] pri = {6, 1, 2, 3, 4, 5, 1, 6};

        List<List<Obsd>> baseEpochsCopy = copyBaseEpochs(baseEpochs);
        List<List<Obsd>> reversedRover = new ArrayList<>(roverEpochs);
        Collections.reverse(reversedRover);
        List<List<Obsd>> reversedBase = baseEpochsCopy != null ? new ArrayList<>(baseEpochsCopy) : null;
        if (reversedBase != null) {
            Collections.reverse(reversedBase);
        }

        for (List<Obsd> roverEpoch : reversedRover) {
            Obsd[] obs = buildEpochObs(roverEpoch, reversedBase);
            if (obs.length == 0) continue;
            totalEpochs++;
            RtklibCommon.corrPhaseBiasSsr(obs, obs.length, nav, opt.pppopt);
            int result = RtkCore.rtkpos(rtk, obs, obs.length, nav);
            if (result == 1 && rtk.sol.stat != Constants.SOLQ_NONE) {
                successCount++;
                Sol solCopy = new Sol(rtk.sol);
                if (!solstatic) {
                    solutions.add(solCopy);
                } else {
                    if (bestSol == null || pri[solCopy.stat] <= pri[bestSol.stat]) {
                        bestSol = solCopy;
                        if (bestTime == null || TimeSystem.timediff(solCopy.time, bestTime) < 0.0) {
                            bestTime = new GTime(solCopy.time);
                        }
                    }
                }
            } else {
                failCount++;
            }
        }

        if (!solstatic) {
            Collections.reverse(solutions);
        } else if (bestSol != null) {
            bestSol.time = bestTime != null ? bestTime : bestSol.time;
            solutions.add(bestSol);
        }

        for (Sol sol : solutions) { outputSolution(sol); }
        finishOutput(totalEpochs, successCount, failCount);
        return new PostPosResult(totalEpochs, successCount, failCount, toSolDataList(solutions), solutions);
    }

    private PostPosResult processCombined(List<List<Obsd>> roverEpochs,
                                           List<List<Obsd>> baseEpochs, Nav nav,
                                           double[] approxPos, double[] basePos) {
        Rtk rtkF = createRtk(approxPos, basePos);
        List<Sol> solfList = new ArrayList<>();
        List<double[]> rbfList = new ArrayList<>();
        sbsMsgIdx = 0;

        List<List<Obsd>> baseEpochsFwd = copyBaseEpochs(baseEpochs);

        for (List<Obsd> roverEpoch : roverEpochs) {
            Obsd[] obs = buildEpochObs(roverEpoch, baseEpochsFwd);
            if (obs.length == 0) {
                solfList.add(null);
                rbfList.add(null);
                continue;
            }
            applySbsUpTo(obs[0].time, nav);
            RtklibCommon.corrPhaseBiasSsr(obs, obs.length, nav, opt.pppopt);
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
            if (obs.length == 0) {
                solbList.add(null);
                rbbList.add(null);
                continue;
            }
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
        return new PostPosResult(totalEpochs, successCount, failCount, toSolDataList(combined), combined);
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
        List<Obsd> epochObs = new ArrayList<>();
        GTime epochTime = roverEpoch.get(0).time;

        for (Obsd o : roverEpoch) {
            o.rcv = 1;
            if ((SatUtils.satsys(o.sat, null) & opt.navsys) != 0
                    && (opt.exsats == null || o.sat <= 0 || o.sat > Constants.MAXSAT || opt.exsats[o.sat - 1] != 1)) {
                epochObs.add(o);
            }
        }

        if (baseEpochs != null && isRelativeMode(opt.mode)) {
            List<Obsd> baseMatch = findMatchingBaseEpoch(baseEpochs, epochTime);
            if (baseMatch != null) {
                for (Obsd o : baseMatch) {
                    o.rcv = 2;
                    if ((SatUtils.satsys(o.sat, null) & opt.navsys) != 0
                            && (opt.exsats == null || o.sat <= 0 || o.sat > Constants.MAXSAT || opt.exsats[o.sat - 1] != 1)) {
                        epochObs.add(o);
                    }
                }
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

    private static boolean isRelativeMode(int mode) {
        return mode == Constants.PMODE_DGPS || mode == Constants.PMODE_KINEMA
                || mode == Constants.PMODE_STATIC || mode == Constants.PMODE_STATIC_START
                || mode == Constants.PMODE_MOVEB || mode == Constants.PMODE_FIXED;
    }

    private void applySbsUpTo(GTime time, Nav nav) {
        while (sbsMsgIdx < sbsMsgs.size()) {
            SbsMsg msg = sbsMsgs.get(sbsMsgIdx);
            GTime msgTime = TimeSystem.gpst2time(msg.week, msg.tow);
            if (TimeSystem.timediff(msgTime, time) > 0.0) break;
            int type = SbasCorrection.sbsupdatecorr(msg, nav);
            if (type >= 0) {
                log.debug("SBAS correction applied: type={}, prn={}, tow={}", type, msg.prn, msg.tow);
            }
            sbsMsgIdx++;
        }
    }

    private void applyAllSbs(Nav nav) {
        while (sbsMsgIdx < sbsMsgs.size()) {
            SbsMsg msg = sbsMsgs.get(sbsMsgIdx);
            int type = SbasCorrection.sbsupdatecorr(msg, nav);
            if (type >= 0) {
                log.debug("SBAS correction applied: type={}, prn={}, tow={}", type, msg.prn, msg.tow);
            }
            sbsMsgIdx++;
        }
    }

    private double[] resolveBasePos(int refpos, double[] optRb, Sta baseSta,
                                     List<List<Obsd>> baseEpochs, Nav nav) {
        switch (refpos) {
            case Constants.POSOPT_POS_LLH:
            case Constants.POSOPT_POS_XYZ:
                if (optRb != null && (optRb[0] != 0.0 || optRb[1] != 0.0 || optRb[2] != 0.0)) {
                    log.info("Base pos from opt.rb (POSOPT_POS_XYZ/LLH): ({}, {}, {})", optRb[0], optRb[1], optRb[2]);
                    return optRb.clone();
                }
                log.warn("opt.rb is zero, fallback to RINEX header");
                return posFromSta(baseSta);
            case Constants.POSOPT_SINGLE:
                double[] avePos = avepos(2, baseEpochs, nav);
                if (avePos != null) {
                    log.info("Base pos from SPP average (POSOPT_SINGLE): ({}, {}, {})", avePos[0], avePos[1], avePos[2]);
                    return avePos;
                }
                log.warn("SPP average failed, fallback to RINEX header");
                return posFromSta(baseSta);
            case Constants.POSOPT_RINEX:
                double[] rinexPos = posFromSta(baseSta);
                if (rinexPos != null) {
                    log.info("Base pos from RINEX header (POSOPT_RINEX): ({}, {}, {})", rinexPos[0], rinexPos[1], rinexPos[2]);
                }
                return rinexPos;
            case Constants.POSOPT_FILE:
                log.warn("POSOPT_FILE not yet supported, fallback to RINEX header");
                return posFromSta(baseSta);
            case Constants.POSOPT_RTCM:
                log.warn("POSOPT_RTCM is for realtime only, fallback to RINEX header");
                return posFromSta(baseSta);
            default:
                return posFromSta(baseSta);
        }
    }

    private double[] resolveRoverPos(int rovpos, double[] optRu, Sta roverSta,
                                      List<List<Obsd>> roverEpochs, Nav nav) {
        switch (rovpos) {
            case Constants.POSOPT_POS_LLH:
            case Constants.POSOPT_POS_XYZ:
                if (optRu != null && (optRu[0] != 0.0 || optRu[1] != 0.0 || optRu[2] != 0.0)) {
                    return optRu.clone();
                }
                return posFromSta(roverSta);
            case Constants.POSOPT_SINGLE:
                double[] avePos = avepos(1, roverEpochs, nav);
                if (avePos != null) return avePos;
                return posFromSta(roverSta);
            case Constants.POSOPT_RINEX:
                return posFromSta(roverSta);
            default:
                return posFromSta(roverSta);
        }
    }

    private static double[] posFromSta(Sta sta) {
        if (sta == null || sta.pos == null
                || (sta.pos[0] == 0.0 && sta.pos[1] == 0.0 && sta.pos[2] == 0.0)) {
            return null;
        }
        double[] rr = sta.pos.clone();

        if (sta.del != null && (sta.del[0] != 0.0 || sta.del[1] != 0.0 || sta.del[2] != 0.0)) {
            double[] pos = new double[3];
            CoordTransform.ecef2pos(rr, pos);
            double[] del_enu = new double[]{sta.del[1], sta.del[0], sta.del[2] + sta.hgt};
            double[] dr = new double[3];
            CoordTransform.enu2ecef(pos, del_enu, dr);
            for (int i = 0; i < 3; i++) rr[i] += dr[i];
        }

        return rr;
    }

    private double[] avepos(int rcv, List<List<Obsd>> epochs, Nav nav) {
        if (epochs == null || epochs.isEmpty()) return null;

        PrcOpt sppOpt = new PrcOpt(opt);
        sppOpt.mode = Constants.PMODE_SINGLE;
        sppOpt.ionoopt = Constants.IONOOPT_BRDC;
        sppOpt.tropopt = Constants.TROPOPT_SAAS;

        Sol sol = new Sol();
        double[] ra = new double[3];
        int n = 0;

        for (List<Obsd> epoch : epochs) {
            List<Obsd> filtered = new ArrayList<>();
            for (Obsd o : epoch) {
                o.rcv = rcv;
                if ((SatUtils.satsys(o.sat, null) & sppOpt.navsys) != 0
                        && (sppOpt.exsats == null || o.sat <= 0 || o.sat > Constants.MAXSAT || sppOpt.exsats[o.sat - 1] != 1)) {
                    filtered.add(o);
                }
            }
            if (filtered.isEmpty()) continue;

            Obsd[] obsData = filtered.toArray(new Obsd[0]);
            int m = obsData.length;

            int result = PntPos.pntpos(obsData, m, nav, sppOpt, sol, null, null);
            if (result == 1 && sol.stat != Constants.SOLQ_NONE) {
                ra[0] += sol.rr[0];
                ra[1] += sol.rr[1];
                ra[2] += sol.rr[2];
                n++;
            }
        }

        if (n <= 0) {
            log.warn("avepos: no valid SPP solutions for rcv={}", rcv);
            return null;
        }
        ra[0] /= n;
        ra[1] /= n;
        ra[2] /= n;
        log.info("avepos: rcv={}, n={}, pos=({},{},{})", rcv, n, ra[0], ra[1], ra[2]);
        return ra;
    }

    public static class PostPosResult {
        public final int totalEpochs;
        public final int successCount;
        public final int failCount;
        public final List<SolData> solutions;
        final List<Sol> solList;

        public PostPosResult(int totalEpochs, int successCount, int failCount,
                             List<SolData> solutions, List<Sol> solList) {
            this.totalEpochs = totalEpochs;
            this.successCount = successCount;
            this.failCount = failCount;
            this.solutions = solutions;
            this.solList = solList;
        }
    }
}