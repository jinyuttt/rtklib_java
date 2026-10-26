package org.rtklib.java.joint;

import org.rtklib.java.common.CompatFileIO;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.pntpos.PosHandler;
import org.rtklib.java.rinex.RinexParser;
import org.rtklib.java.rtcm.Rtcm;
import org.rtklib.java.time.TimeSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class JointRtkProcessor {

    private static final Logger log = LoggerFactory.getLogger(JointRtkProcessor.class);

    private static final String POS_HEADER =
            "# Joint RTK (Multi-Baseline) Result\n" +
            "#  Date       Time       lat(deg)      lon(deg)     height(m)  Q  ns   sdn(m)   sde(m)   sdu(m)  sdne(m)  sdeu(m)  sdun(m) age(s)  ratio gdop  pdop  hdop  vdop\n";

    private PrcOpt opt;
    private JointConfig jointConfig;
    private final PosHandler handler;
    private final Writer writer;

    private final JointRtk jrtk;
    private final Rtcm rtcmRover = new Rtcm();
    private final Map<String, Rtcm> rtcmBaseMap = new LinkedHashMap<>();

    private boolean finished = false;
    private boolean ephReady = false;
    private int totalEpochs = 0;
    private int successCount = 0;
    private int failCount = 0;
    private final List<Sol> solutions = new ArrayList<>();

    private final List<Obsd[]> pendingRoverObsList = new ArrayList<>();
    private final List<Integer> pendingRoverObsCountList = new ArrayList<>();
    private final List<GTime> pendingRoverObsTimeList = new ArrayList<>();

    private final Map<String, List<Obsd[]>> pendingBaseObsMap = new LinkedHashMap<>();
    private final Map<String, List<Integer>> pendingBaseObsCountMap = new LinkedHashMap<>();
    private final Map<String, List<GTime>> pendingBaseObsTimeMap = new LinkedHashMap<>();

    private byte[] pendingRover = new byte[4096];
    private int pendingRoverLen = 0;
    private final Map<String, byte[]> pendingBaseBufMap = new LinkedHashMap<>();
    private final Map<String, Integer> pendingBaseLenMap = new LinkedHashMap<>();

    private String lastRoverSourceId = null;
    private final Map<String, String> lastBaseSourceIdMap = new LinkedHashMap<>();

    public JointRtkProcessor(PrcOpt opt, JointConfig jointConfig,
                             SolOpt solOpt, PosHandler handler, OutputStream outputStream) {
        this.opt = new PrcOpt(opt);
        this.jointConfig = jointConfig != null ? jointConfig : new JointConfig();
        this.handler = handler;
        this.jrtk = new JointRtk();
        this.jrtk.opt = this.opt;
        this.jrtk.jointConfig = this.jointConfig;

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

    public JointRtkProcessor(PrcOpt opt, JointConfig jointConfig, PosHandler handler) {
        this(opt, jointConfig, null, handler, null);
    }

    public JointRtkProcessor(PrcOpt opt, JointConfig jointConfig) {
        this(opt, jointConfig, null, null, null);
    }

    public JointRtkProcessor(PrcOpt opt) {
        this(opt, null, null, null, null);
    }

    public void setRoverPosition(double[] pos) {
        if (pos != null && pos.length >= 3) {
            System.arraycopy(pos, 0, jrtk.sol.rr, 0, 3);
        }
    }

    public void addBaseStation(String baseId, double[] baseCoord) {
        if (jrtk.bases == null) {
            jrtk.bases = new BaseStation[jointConfig.maxBaseline];
        }
        int idx = jrtk.nBaseline;
        if (idx >= jointConfig.maxBaseline) {
            throw new IllegalArgumentException("Exceeded maxBaseline=" + jointConfig.maxBaseline);
        }
        jrtk.bases[idx] = new BaseStation(baseId, baseCoord);
        jrtk.nBaseline = idx + 1;

        rtcmBaseMap.computeIfAbsent(baseId, k -> {
            Rtcm rtcm = new Rtcm();
            rtcm.relaxSyncFlag = opt.relaxSyncFlag;
            return rtcm;
        });
        pendingBaseObsMap.put(baseId, new ArrayList<>());
        pendingBaseObsCountMap.put(baseId, new ArrayList<>());
        pendingBaseObsTimeMap.put(baseId, new ArrayList<>());
        pendingBaseBufMap.put(baseId, new byte[4096]);
        pendingBaseLenMap.put(baseId, 0);
    }

    public void setBasePosition(String baseId, double[] pos) {
        BaseStation bs = findBase(baseId);
        if (bs != null && pos != null && pos.length >= 3) {
            System.arraycopy(pos, 0, bs.rb, 0, 3);
        }
    }

    public void feedRover(String sourceId, byte[] data, int offset, int length) {
        this.lastRoverSourceId = sourceId;
        feedRover(data, offset, length);
    }

    public void feedRover(String sourceId, byte[] data) {
        feedRover(sourceId, data, 0, data.length);
    }

    public void feedRover(byte[] data, int offset, int length) {
        if (finished) throw new IllegalStateException("Processor already finished");
        if (length <= 0) return;

        pendingRover = ensureCapacity(pendingRover, pendingRoverLen + length);
        System.arraycopy(data, offset, pendingRover, pendingRoverLen, length);
        pendingRoverLen += length;

        int pos = 0;
        while (pos < pendingRoverLen) {
            int consumed = rtcmRover.input(pendingRover, pos, pendingRoverLen - pos);
            if (consumed > 0) {
                onRtcmRoverMessage();
                pos += consumed;
            } else if (consumed == 0) {
                break;
            } else {
                pos++;
            }
        }

        if (pos > 0) {
            if (pos < pendingRoverLen) {
                System.arraycopy(pendingRover, pos, pendingRover, 0, pendingRoverLen - pos);
            }
            pendingRoverLen -= pos;
        }

        tryProcessPending();
    }

    public void feedRover(byte[] data) {
        feedRover(data, 0, data.length);
    }

    public void feedBase(String baseId, String sourceId, byte[] data, int offset, int length) {
        this.lastBaseSourceIdMap.put(baseId, sourceId);
        feedBase(baseId, data, offset, length);
    }

    public void feedBase(String baseId, String sourceId, byte[] data) {
        feedBase(baseId, sourceId, data, 0, data.length);
    }

    public void feedBase(String baseId, byte[] data, int offset, int length) {
        if (finished) throw new IllegalStateException("Processor already finished");
        if (length <= 0) return;
        Rtcm rtcmBase = rtcmBaseMap.get(baseId);
        if (rtcmBase == null) {
            rtcmBase = new Rtcm();
            rtcmBase.relaxSyncFlag = opt.relaxSyncFlag;
            rtcmBaseMap.put(baseId, rtcmBase);
            pendingBaseObsMap.put(baseId, new ArrayList<>());
            pendingBaseObsCountMap.put(baseId, new ArrayList<>());
            pendingBaseObsTimeMap.put(baseId, new ArrayList<>());
            pendingBaseBufMap.put(baseId, new byte[4096]);
            pendingBaseLenMap.put(baseId, 0);
        }

        byte[] buf = pendingBaseBufMap.get(baseId);
        int bufLen = pendingBaseLenMap.get(baseId);
        buf = ensureCapacity(buf, bufLen + length);
        System.arraycopy(data, offset, buf, bufLen, length);
        bufLen += length;
        pendingBaseBufMap.put(baseId, buf);
        pendingBaseLenMap.put(baseId, bufLen);

        int pos = 0;
        while (pos < bufLen) {
            int consumed = rtcmBase.input(buf, pos, bufLen - pos);
            if (consumed > 0) {
                onRtcmBaseMessage(baseId, rtcmBase);
                pos += consumed;
            } else if (consumed == 0) {
                break;
            } else {
                pos++;
            }
        }

        if (pos > 0) {
            if (pos < bufLen) {
                System.arraycopy(buf, pos, buf, 0, bufLen - pos);
            }
            bufLen -= pos;
            pendingBaseBufMap.put(baseId, buf);
            pendingBaseLenMap.put(baseId, bufLen);
        }

        tryProcessPending();
    }

    public void feedBase(String baseId, byte[] data) {
        feedBase(baseId, data, 0, data.length);
    }

    private void onRtcmRoverMessage() {
        int type = rtcmRover.type;
        if (isEphemerisType(type) && rtcmRover.ephsat != 0) {
            ephReady = true;
        }
        if ((type == 1005 || type == 1006) && rtcmRover.sta != null) {
            updateBasePosFromRtcm(rtcmRover.sta.pos, type, "rover");
        }
        if (isObsType(type) && rtcmRover.obs.n > 0 && rtcmRover.obsflag == 1) {
            int n = rtcmRover.obs.n;
            Obsd[] obsCopy = new Obsd[n];
            for (int i = 0; i < n; i++) {
                obsCopy[i] = copyObsd(rtcmRover.obs.data[i]);
                obsCopy[i].rcv = 1;
            }
            pendingRoverObsList.add(obsCopy);
            pendingRoverObsCountList.add(n);
            pendingRoverObsTimeList.add(rtcmRover.obs.data[0].time);
        }
    }

    private void onRtcmBaseMessage(String baseId, Rtcm rtcmBase) {
        int type = rtcmBase.type;
        if (isEphemerisType(type) && rtcmBase.ephsat != 0) {
            ephReady = true;
        }
        if ((type == 1005 || type == 1006) && rtcmBase.sta != null) {
            BaseStation bs = findBase(baseId);
            if (bs != null) {
                System.arraycopy(rtcmBase.sta.pos, 0, bs.rb, 0, 3);
                log.info("Base {} pos from RTCM {}: ({}, {}, {})",
                        baseId, type, bs.rb[0], bs.rb[1], bs.rb[2]);
            }
        }
        if (isObsType(type) && rtcmBase.obs.n > 0 && rtcmBase.obsflag == 1) {
            int n = rtcmBase.obs.n;
            Obsd[] obsCopy = new Obsd[n];
            for (int i = 0; i < n; i++) {
                obsCopy[i] = copyObsd(rtcmBase.obs.data[i]);
                obsCopy[i].rcv = 2;
            }
            pendingBaseObsMap.get(baseId).add(obsCopy);
            pendingBaseObsCountMap.get(baseId).add(n);
            pendingBaseObsTimeMap.get(baseId).add(rtcmBase.obs.data[0].time);
        }
    }

    private void updateBasePosFromRtcm(double[] staPos, int type, String source) {
        if (staPos == null) return;
        if (jrtk.nBaseline > 0 && jrtk.bases != null && jrtk.bases[0] != null) {
            BaseStation bs = jrtk.bases[0];
            if (bs.rb[0] == 0.0 && bs.rb[1] == 0.0 && bs.rb[2] == 0.0) {
                System.arraycopy(staPos, 0, bs.rb, 0, 3);
                log.info("Base {} pos from {} RTCM {}: ({}, {}, {})",
                        bs.id, source, type, bs.rb[0], bs.rb[1], bs.rb[2]);
            }
        }
    }

    private void tryProcessPending() {
        if (!ephReady) return;
        if (pendingRoverObsList.isEmpty()) return;

        int ephWeek = findEphWeek();
        if (ephWeek > 0) {
            correctObsWeek(pendingRoverObsList, pendingRoverObsTimeList, ephWeek);
            for (String baseId : pendingBaseObsTimeMap.keySet()) {
                correctObsWeek(pendingBaseObsMap.get(baseId), pendingBaseObsTimeMap.get(baseId), ephWeek);
            }
        }

        while (!pendingRoverObsList.isEmpty()) {
            Obsd[] roverObs = pendingRoverObsList.remove(0);
            int roverN = pendingRoverObsCountList.remove(0);
            GTime roverTime = pendingRoverObsTimeList.remove(0);

            Map<String, Obsd[]> baseObsForEpoch = new LinkedHashMap<>();
            Map<String, GTime> baseTimeForEpoch = new LinkedHashMap<>();

            for (String baseId : pendingBaseObsMap.keySet()) {
                Obsd[] matched = findMatchingBaseObs(baseId, roverTime);
                if (matched != null) {
                    baseObsForEpoch.put(baseId, matched);
                    baseTimeForEpoch.put(baseId, roverTime);
                }
            }

            Nav nav = rtcmRover.nav;
            for (Rtcm rtcmBase : rtcmBaseMap.values()) {
                mergeNav(rtcmBase.nav, nav);
            }

            processEpoch(roverObs, roverN, roverTime, baseObsForEpoch, baseTimeForEpoch, nav);
        }
    }

    private Obsd[] findMatchingBaseObs(String baseId, GTime roverTime) {
        List<Obsd[]> obsList = pendingBaseObsMap.get(baseId);
        List<Integer> countList = pendingBaseObsCountMap.get(baseId);
        List<GTime> timeList = pendingBaseObsTimeMap.get(baseId);
        if (obsList == null || obsList.isEmpty()) return null;

        double bestDt = Double.MAX_VALUE;
        int bestIdx = -1;
        double maxTdiff = opt.maxtdiff > 0 ? opt.maxtdiff : 30.0;

        for (int i = 0; i < timeList.size(); i++) {
            double dt = Math.abs(TimeSystem.timediff(roverTime, timeList.get(i)));
            if (dt < bestDt && dt <= maxTdiff) {
                bestDt = dt;
                bestIdx = i;
            }
        }

        if (bestIdx < 0) return null;

        Obsd[] result = obsList.remove(bestIdx);
        countList.remove(bestIdx);
        timeList.remove(bestIdx);
        return result;
    }

    private void processEpoch(Obsd[] roverObs, int roverN, GTime roverTime,
                              Map<String, Obsd[]> baseObsForEpoch,
                              Map<String, GTime> baseTimeForEpoch, Nav nav) {
        totalEpochs++;

        for (String baseId : baseObsForEpoch.keySet()) {
            BaseStation bs = findBase(baseId);
            if (bs != null) {
                bs.obsBuf = baseObsForEpoch.get(baseId);
                bs.obsCount = baseObsForEpoch.get(baseId).length;
                bs.obsTime = baseTimeForEpoch.get(baseId);
            }
        }

        if (jrtk.nBaseline > 0 && jrtk.bases != null && jrtk.bases[0] != null) {
            if (jrtk.rb[0] == 0.0 && jrtk.rb[1] == 0.0 && jrtk.rb[2] == 0.0) {
                System.arraycopy(jrtk.bases[0].rb, 0, jrtk.rb, 0, 3);
                log.info("Set jrtk.rb from base[0] {}: ({}, {}, {})",
                        jrtk.bases[0].id, jrtk.rb[0], jrtk.rb[1], jrtk.rb[2]);
            }
        }

        int result = JointCore.rtkpos(jrtk, roverObs, roverN, jrtk.bases, jrtk.nBaseline, nav);

        log.debug("processEpoch result={}, stat={}, ns={}, nBaseline={}", result, jrtk.sol.stat, jrtk.sol.ns, jrtk.nBaseline);

        if (result == 1 && jrtk.sol.stat != Constants.SOLQ_NONE) {
            successCount++;
            Sol solCopy = new Sol(jrtk.sol);
            solutions.add(solCopy);

            if (handler != null) {
                double[] rb = jrtk.rb;
                handler.onSolution(solCopy, copySsatArray(jrtk.ssat));
                handler.onResult(new SolData(lastRoverSourceId, solCopy, opt.posMask, rb, jrtk.ssat, opt.diagMask));
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
                handler.onPosFail(roverTime, "Joint RTK failed (result=" + result +
                        ", stat=" + jrtk.sol.stat + ", nBaseline=" + jrtk.nBaseline + ")");
            }
        }
    }

    public JointRtkResult process(byte[] roverData, Map<String, byte[]> baseDataMap) {
        if (finished) throw new IllegalStateException("Processor already finished");

        Rtcm batchRtcmRover = new Rtcm();
        batchRtcmRover.relaxSyncFlag = this.rtcmRover.relaxSyncFlag;
        List<Obsd[]> batchRoverObsList = new ArrayList<>();
        List<Integer> batchRoverObsCountList = new ArrayList<>();
        List<GTime> batchRoverObsTimeList = new ArrayList<>();

        parseRtcmBatch(roverData, batchRtcmRover, batchRoverObsList,
                batchRoverObsCountList, batchRoverObsTimeList, 1);

        Map<String, Rtcm> batchRtcmBaseMap = new LinkedHashMap<>();
        Map<String, List<Obsd[]>> batchBaseObsMap = new LinkedHashMap<>();
        Map<String, List<Integer>> batchBaseObsCountMap = new LinkedHashMap<>();
        Map<String, List<GTime>> batchBaseObsTimeMap = new LinkedHashMap<>();

        if (baseDataMap != null) {
            for (Map.Entry<String, byte[]> entry : baseDataMap.entrySet()) {
                String baseId = entry.getKey();
                byte[] baseData = entry.getValue();

                if (findBase(baseId) == null) {
                    addBaseStation(baseId, new double[3]);
                }

                Rtcm batchRtcmBase = new Rtcm();
                batchRtcmBase.relaxSyncFlag = this.rtcmRover.relaxSyncFlag;
                batchRtcmBaseMap.put(baseId, batchRtcmBase);
                List<Obsd[]> obsList = new ArrayList<>();
                List<Integer> countList = new ArrayList<>();
                List<GTime> timeList = new ArrayList<>();
                batchBaseObsMap.put(baseId, obsList);
                batchBaseObsCountMap.put(baseId, countList);
                batchBaseObsTimeMap.put(baseId, timeList);
                parseRtcmBatch(baseData, batchRtcmBase, obsList, countList, timeList, 2);

                if (batchRtcmBase.sta != null &&
                        (batchRtcmBase.sta.pos[0] != 0 || batchRtcmBase.sta.pos[1] != 0 || batchRtcmBase.sta.pos[2] != 0)) {
                    BaseStation bs = findBase(baseId);
                    if (bs != null) {
                        System.arraycopy(batchRtcmBase.sta.pos, 0, bs.rb, 0, 3);
                        log.info("Base {} pos from batch RTCM: ({}, {}, {})", baseId, bs.rb[0], bs.rb[1], bs.rb[2]);
                    }
                }
            }
        }

        if (batchRtcmRover.sta != null &&
                (batchRtcmRover.sta.pos[0] != 0 || batchRtcmRover.sta.pos[1] != 0 || batchRtcmRover.sta.pos[2] != 0)) {
            updateBasePosFromRtcm(batchRtcmRover.sta.pos, 1005, "rover");
        }

        Nav nav = batchRtcmRover.nav;
        for (Rtcm rtcmBase : batchRtcmBaseMap.values()) {
            mergeNav(rtcmBase.nav, nav);
        }

        log.info("Joint RTK Batch: roverEpochs={}, baseStations={}",
                batchRoverObsList.size(), batchBaseObsMap.size());

        for (int epoch = 0; epoch < batchRoverObsList.size(); epoch++) {
            Obsd[] roverObs = batchRoverObsList.get(epoch);
            int roverN = batchRoverObsCountList.get(epoch);
            GTime roverTime = batchRoverObsTimeList.get(epoch);

            Map<String, Obsd[]> baseObsForEpoch = new LinkedHashMap<>();
            Map<String, GTime> baseTimeForEpoch = new LinkedHashMap<>();

            for (String baseId : batchBaseObsMap.keySet()) {
                Obsd[] matched = findMatchingBaseObsFromList(
                        batchBaseObsMap.get(baseId),
                        batchBaseObsCountMap.get(baseId),
                        batchBaseObsTimeMap.get(baseId),
                        roverTime);
                if (matched != null) {
                    baseObsForEpoch.put(baseId, matched);
                    baseTimeForEpoch.put(baseId, roverTime);
                }
            }

            processEpoch(roverObs, roverN, roverTime, baseObsForEpoch, baseTimeForEpoch, nav);
        }

        rtcmRover.nav = batchRtcmRover.nav;
        for (Map.Entry<String, Rtcm> entry : batchRtcmBaseMap.entrySet()) {
            Rtcm existing = rtcmBaseMap.get(entry.getKey());
            if (existing != null) {
                existing.nav = entry.getValue().nav;
            }
        }
        ephReady = true;

        finished = true;
        return finishInternal();
    }

    public JointRtkResult process(String roverFilePath, Map<String, String> baseFilePathMap) throws IOException {
        byte[] roverData = CompatFileIO.readAllBytes(roverFilePath);
        Map<String, byte[]> baseDataMap = new LinkedHashMap<>();
        if (baseFilePathMap != null) {
            for (Map.Entry<String, String> entry : baseFilePathMap.entrySet()) {
                baseDataMap.put(entry.getKey(), CompatFileIO.readAllBytes(entry.getValue()));
            }
        }
        return process(roverData, baseDataMap);
    }

    public JointRtkResult processRinex(String roverObsPath, Map<String, String> baseObsMap,
                                        String navPath) {
        RinexParser roverParser = new RinexParser();
        boolean roverOk = roverParser.parseObs(roverObsPath);
        if (!roverOk) {
            throw new RuntimeException("Failed to parse rover RINEX: " + roverObsPath);
        }

        Nav nav;
        if (navPath != null) {
            RinexParser navParser = new RinexParser();
            boolean navOk = navParser.parseNav(navPath);
            if (!navOk) {
                throw new RuntimeException("Failed to parse RINEX nav: " + navPath);
            }
            nav = navParser.nav;
        } else {
            nav = roverParser.nav;
        }

        List<List<Obsd>> roverEpochs = groupObsByEpoch(roverParser.obs.data, roverParser.obs.n);
        log.info("Joint RINEX: rover {} obs, {} epochs", roverParser.obs.n, roverEpochs.size());

        Map<String, List<List<Obsd>>> baseEpochsMap = new LinkedHashMap<>();
        if (baseObsMap != null) {
            for (Map.Entry<String, String> entry : baseObsMap.entrySet()) {
                String baseId = entry.getKey();
                String basePath = entry.getValue();
                RinexParser baseParser = new RinexParser();
                boolean baseOk = baseParser.parseObs(basePath);
                if (!baseOk) {
                    throw new RuntimeException("Failed to parse base RINEX: " + basePath);
                }
                baseEpochsMap.put(baseId, groupObsByEpoch(baseParser.obs.data, baseParser.obs.n));

                if (baseParser.sta != null && baseParser.sta.pos != null) {
                    BaseStation bs = findBase(baseId);
                    if (bs != null && (bs.rb[0] == 0 && bs.rb[1] == 0 && bs.rb[2] == 0)) {
                        System.arraycopy(baseParser.sta.pos, 0, bs.rb, 0, 3);
                    }
                }
            }
        }

        Map<String, Integer> baseCursorMap = new LinkedHashMap<>();
        for (String baseId : baseEpochsMap.keySet()) {
            baseCursorMap.put(baseId, 0);
        }

        for (List<Obsd> roverEpoch : roverEpochs) {
            GTime epochTime = roverEpoch.get(0).time;
            Obsd[] roverObs = roverEpoch.stream()
                    .filter(o -> (SatUtils.satsys(o.sat, null) & opt.navsys) != 0)
                    .map(o -> { Obsd c = new Obsd(o); c.rcv = 1; return c; })
                    .toArray(Obsd[]::new);
            int roverN = roverObs.length;

            Map<String, Obsd[]> baseObsForEpoch = new LinkedHashMap<>();
            Map<String, GTime> baseTimeForEpoch = new LinkedHashMap<>();

            for (Map.Entry<String, List<List<Obsd>>> entry : baseEpochsMap.entrySet()) {
                String baseId = entry.getKey();
                List<List<Obsd>> baseEpochs = entry.getValue();
                int cursor = baseCursorMap.get(baseId);
                int idx = findNearestBaseEpoch(baseEpochs, epochTime, cursor);
                if (idx >= 0) {
                    baseCursorMap.put(baseId, idx);
                    Obsd[] baseObs = baseEpochs.get(idx).stream()
                            .filter(o -> (SatUtils.satsys(o.sat, null) & opt.navsys) != 0)
                            .map(o -> { Obsd c = new Obsd(o); c.rcv = 2; return c; })
                            .toArray(Obsd[]::new);
                    baseObsForEpoch.put(baseId, baseObs);
                    baseTimeForEpoch.put(baseId, epochTime);
                }
            }

            processEpoch(roverObs, roverN, epochTime, baseObsForEpoch, baseTimeForEpoch, nav);
        }

        finished = true;
        return finishInternal();
    }

    public JointRtkResult finish() {
        if (finished) throw new IllegalStateException("Processor already finished");
        finished = true;

        if (ephReady && !pendingRoverObsList.isEmpty()) {
            tryProcessPending();
        }

        for (int i = 0; i < pendingRoverObsList.size(); i++) {
            totalEpochs++;
            failCount++;
            if (handler != null) {
                handler.onPosFail(pendingRoverObsTimeList.get(i), "No ephemeris or no base data");
            }
        }
        pendingRoverObsList.clear();
        pendingRoverObsCountList.clear();
        pendingRoverObsTimeList.clear();
        for (List<Obsd[]> l : pendingBaseObsMap.values()) l.clear();
        for (List<Integer> l : pendingBaseObsCountMap.values()) l.clear();
        for (List<GTime> l : pendingBaseObsTimeMap.values()) l.clear();

        return finishInternal();
    }

    private JointRtkResult finishInternal() {
        log.info("Joint RTK complete: total={}, success={}, fail={}", totalEpochs, successCount, failCount);

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

    public JointRtk getJointRtk() {
        return jrtk;
    }

    private JointRtkResult buildResult() {
        double[] rb = (jrtk.rb[0] != 0 || jrtk.rb[1] != 0 || jrtk.rb[2] != 0) ? jrtk.rb : null;
        List<SolData> solDataList = solutions.stream()
                .map(sol -> new SolData(lastRoverSourceId, sol, opt.posMask, rb, jrtk.ssat, opt.diagMask))
                .toList();
        return new JointRtkResult(totalEpochs, successCount, failCount, solDataList, solutions);
    }

    private BaseStation findBase(String id) {
        if (jrtk.bases == null) return null;
        for (BaseStation bs : jrtk.bases) {
            if (bs != null && id.equals(bs.id)) return bs;
        }
        return null;
    }

    private int findEphWeek() {
        Nav[] navs = {rtcmRover.nav};
        for (Nav nav : navs) {
            for (int i = 0; i < nav.eph.length; i++) {
                if (nav.eph[i] != null && nav.eph[i].A > 0) {
                    int[] wkArr = new int[1];
                    TimeSystem.time2gpst(nav.eph[i].toe, wkArr);
                    if (wkArr[0] > 0) return wkArr[0];
                }
            }
        }
        for (Rtcm rtcmBase : rtcmBaseMap.values()) {
            Nav nav = rtcmBase.nav;
            for (int i = 0; i < nav.eph.length; i++) {
                if (nav.eph[i] != null && nav.eph[i].A > 0) {
                    int[] wkArr = new int[1];
                    TimeSystem.time2gpst(nav.eph[i].toe, wkArr);
                    if (wkArr[0] > 0) return wkArr[0];
                }
            }
        }
        return -1;
    }

    private void mergeNav(Nav dst, Nav src) {
        for (int i = 0; i < src.eph.length; i++) {
            if (src.eph[i] != null && src.eph[i].A > 0) {
                int sat = src.eph[i].sat;
                if (sat > 0 && sat <= dst.eph.length) {
                    if (dst.eph[sat - 1] == null || dst.eph[sat - 1].A <= 0) {
                        dst.eph[sat - 1] = src.eph[i];
                    }
                }
            }
        }
    }

    private void correctObsWeek(List<Obsd[]> obsList, List<GTime> timeList, int ephWeek) {
        for (int i = 0; i < timeList.size(); i++) {
            GTime ot = timeList.get(i);
            int[] wkArr = new int[1];
            double sow = TimeSystem.time2gpst(ot, wkArr);
            if (wkArr[0] == 0 && sow > 0) {
                GTime corrected = TimeSystem.gpst2time(ephWeek, sow);
                timeList.set(i, corrected);
                for (int j = 0; j < obsList.get(i).length; j++) {
                    obsList.get(i)[j].time = corrected;
                }
            }
        }
    }

    private void parseRtcmBatch(byte[] data, Rtcm rtcm,
                                List<Obsd[]> obsList, List<Integer> obsCountList,
                                List<GTime> obsTimeList, int rcv) {
        int offset = 0;
        while (offset < data.length) {
            int consumed = rtcm.input(data, offset, data.length - offset);
            if (consumed <= 0) {
                offset++;
                continue;
            }
            offset += consumed;

            int type = rtcm.type;
            if (isObsType(type) && rtcm.obs.n > 0 && rtcm.obsflag == 1) {
                int n = rtcm.obs.n;
                Obsd[] obsCopy = new Obsd[n];
                for (int i = 0; i < n; i++) {
                    obsCopy[i] = copyObsd(rtcm.obs.data[i]);
                    obsCopy[i].rcv = rcv;
                }
                obsList.add(obsCopy);
                obsCountList.add(n);
                obsTimeList.add(rtcm.obs.data[0].time);
            }
        }
    }

    private Obsd[] findMatchingBaseObsFromList(List<Obsd[]> baseObsList,
                                                List<Integer> baseObsCountList,
                                                List<GTime> baseObsTimeList,
                                                GTime roverTime) {
        if (baseObsList.isEmpty()) return null;

        double bestDt = Double.MAX_VALUE;
        int bestIdx = -1;
        double maxTdiff = opt.maxtdiff > 0 ? opt.maxtdiff : 30.0;

        for (int i = 0; i < baseObsTimeList.size(); i++) {
            double dt = Math.abs(TimeSystem.timediff(roverTime, baseObsTimeList.get(i)));
            if (dt < bestDt && dt <= maxTdiff) {
                bestDt = dt;
                bestIdx = i;
            }
        }

        if (bestIdx < 0) return null;

        Obsd[] result = baseObsList.remove(bestIdx);
        baseObsCountList.remove(bestIdx);
        baseObsTimeList.remove(bestIdx);
        return result;
    }

    private int findNearestBaseEpoch(List<List<Obsd>> baseEpochs, GTime roverTime, int iobsr) {
        if (baseEpochs == null || baseEpochs.isEmpty()) return -1;
        if (iobsr < 0 || iobsr >= baseEpochs.size()) return -1;
        List<Obsd> cur = baseEpochs.get(iobsr);
        if (cur.isEmpty()) return -1;

        double dt = Math.abs(TimeSystem.timediff(roverTime, cur.get(0).time));
        int best = iobsr;
        int i = iobsr + 1;
        while (i < baseEpochs.size()) {
            List<Obsd> next = baseEpochs.get(i);
            if (next.isEmpty()) { i++; continue; }
            double dtNext = Math.abs(TimeSystem.timediff(roverTime, next.get(0).time));
            if (dtNext > dt) break;
            dt = dtNext;
            best = i;
            i++;
        }
        return best;
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
        if (!current.isEmpty()) groups.add(current);
        return groups;
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

        return String.format("  %s %s %14.9f %14.9f %10.4f  %s %3d %8.4f %8.4f %8.4f %8.4f %8.4f %8.4f %5.1f %6.1f %5.1f %5.1f %5.1f %5.1f\n",
                dateStr, timeStr, latDeg, lonDeg, height,
                qStr, sol.ns,
                sdn, sde, sdu, sdne, sdeu, sdun,
                sol.age, sol.ratio,
                sol.gdop, sol.pdop, sol.hdop, sol.vdop);
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
            dst.snrRover = src.snrRover.clone();
            dst.snrBase = src.snrBase.clone();
            System.arraycopy(src.fix, 0, dst.fix, 0, src.fix.length);
            System.arraycopy(src.slip, 0, dst.slip, 0, src.slip.length);
            System.arraycopy(src.amb, 0, dst.amb, 0, src.amb.length);
        }
        return copy;
    }

    private static byte[] ensureCapacity(byte[] buf, int need) {
        if (need > buf.length) {
            return Arrays.copyOf(buf, Math.max(buf.length * 2, need + 1024));
        }
        return buf;
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

    public static class JointRtkResult implements Serializable {
        private static final long serialVersionUID = 1L;
        public final int totalEpochs;
        public final int successCount;
        public final int failCount;
        public final List<SolData> solutions;
        public final List<Sol> solList;

        public JointRtkResult(int totalEpochs, int successCount, int failCount,
                              List<SolData> solutions, List<Sol> solList) {
            this.totalEpochs = totalEpochs;
            this.successCount = successCount;
            this.failCount = failCount;
            this.solutions = solutions;
            this.solList = solList;
        }
    }
}