package org.rtklib.java;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.ephemeris.EphModel;
import org.rtklib.java.pntpos.PntPos;
import org.rtklib.java.pntpos.SppCore;
import org.rtklib.java.rtcm.Rtcm;
import org.rtklib.java.rtkpos.RtkCore;
import org.rtklib.java.rinex.RinexObsWriter;
import org.rtklib.java.rinex.RinexNavWriter;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.time.TimeSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RTK Iono-Free LC Test")
public class RtkIonoptTest {

    private static final Logger log = LoggerFactory.getLogger(RtkIonoptTest.class);

    private static final String DATA_BASE = TestDataConfig.getRtcmBaseDir();
    private static final String BASE_ID = TestDataConfig.getBaseRoverPairs("group1")[0];
    private static final String ROVER_ID = TestDataConfig.getBaseRoverPairs("rover1")[0];
    private static final String DATE = TestDataConfig.get("ionopt.date", "2026-05-20");
    private static final int START_HOUR = Integer.parseInt(TestDataConfig.get("ionopt.start.hour", "8"));
    private static final int END_HOUR = Integer.parseInt(TestDataConfig.get("ionopt.end.hour", "10"));

    private static final String RESULT_DIR = TestDataConfig.getResultDir() + "\\ionopt_results";

    private static List<ObsEpoch> roverEpochs;
    private static List<ObsEpoch> baseEpochs;
    private static Nav nav;
    private static byte[] roverData;
    private static byte[] baseData;

    static boolean isObsType(int type) {
        return (type >= 1001 && type <= 1004)
                || (type >= 1074 && type <= 1077)
                || (type >= 1084 && type <= 1087)
                || (type >= 1094 && type <= 1097)
                || (type >= 1104 && type <= 1107)
                || (type >= 1114 && type <= 1117)
                || (type >= 1124 && type <= 1127)
                || (type >= 1134 && type <= 1137);
    }

    private static class ObsEpoch {
        final GTime time;
        final Obsd[] obsd;
        final int n;

        ObsEpoch(GTime time, Obsd[] obsd, int n) {
            this.time = new GTime(time);
            this.obsd = new Obsd[n];
            for (int i = 0; i < n; i++) this.obsd[i] = new Obsd(obsd[i]);
            this.n = n;
        }
    }

    private static List<ObsEpoch> parseObsEpochs(byte[] data, int rcv) {
        List<ObsEpoch> epochs = new ArrayList<>();
        Rtcm rtcm = new Rtcm();
        int offset = 0;
        while (offset < data.length) {
            int consumed = rtcm.input(data, offset, data.length - offset);
            if (consumed <= 0) { offset++; continue; }
            offset += consumed;
            if (isObsType(rtcm.type) && rtcm.obs.n > 0) {
                Obsd[] obsd = new Obsd[rtcm.obs.n];
                for (int i = 0; i < rtcm.obs.n; i++) {
                    obsd[i] = new Obsd(rtcm.obs.data[i]);
                    obsd[i].rcv = rcv;
                }
                epochs.add(new ObsEpoch(rtcm.obs.data[0].time, obsd, rtcm.obs.n));
            }
        }
        return epochs;
    }

    private static Nav parseNavData(byte[] data) {
        Rtcm rtcm = new Rtcm();
        int offset = 0;
        while (offset < data.length) {
            int consumed = rtcm.input(data, offset, data.length - offset);
            if (consumed <= 0) { offset++; continue; }
            offset += consumed;
        }
        return rtcm.nav;
    }

    private static void mergeNavData(Nav target, byte[] data) {
        Rtcm rtcm = new Rtcm();
        int offset = 0;
        while (offset < data.length) {
            int consumed = rtcm.input(data, offset, data.length - offset);
            if (consumed <= 0) { offset++; continue; }
            offset += consumed;
        }
        for (int i = 0; i < rtcm.nav.eph.length; i++) {
            if (rtcm.nav.eph[i] != null && rtcm.nav.eph[i].sat > 0) {
                target.eph[i] = rtcm.nav.eph[i];
            }
        }
        for (int i = 0; i < rtcm.nav.geph.length; i++) {
            if (rtcm.nav.geph[i] != null && rtcm.nav.geph[i].sat > 0) {
                target.geph[i] = rtcm.nav.geph[i];
            }
        }
    }

    private static byte[] loadHourData(String stationId, int hour) throws IOException {
        String path = DATA_BASE + "\\" + stationId + "\\" + DATE + "\\" + hour + ".rtcm3";
        java.io.File file = new java.io.File(path);
        if (!file.exists()) {
            log.warn("文件不存在: {}", path);
            return new byte[0];
        }
        try (FileInputStream fis = new FileInputStream(file)) {
            return fis.readAllBytes();
        }
    }

    private static byte[] loadMultiHourData(String stationId, int startHour, int endHour) throws IOException {
        List<byte[]> chunks = new ArrayList<>();
        int totalLen = 0;
        for (int h = startHour; h < endHour; h++) {
            byte[] chunk = loadHourData(stationId, h);
            if (chunk.length > 0) {
                chunks.add(chunk);
                totalLen += chunk.length;
            }
        }
        byte[] result = new byte[totalLen];
        int pos = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, result, pos, chunk.length);
            pos += chunk.length;
        }
        return result;
    }

    private static double[] computeBasePosition(List<ObsEpoch> baseEpochs, Nav nav, PrcOpt rtkOpt) {
        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_SINGLE;
        opt.nf = rtkOpt.nf;
        opt.navsys = rtkOpt.navsys;
        opt.elmin = rtkOpt.elmin;
        opt.ionoopt = rtkOpt.ionoopt;
        opt.tropopt = rtkOpt.tropopt;
        opt.sateph = rtkOpt.sateph;
        opt.err = rtkOpt.err.clone();
        opt.std = rtkOpt.std.clone();
        opt.prn = rtkOpt.prn.clone();
        opt.exsats = rtkOpt.exsats.clone();

        double[] sumPos = new double[3];
        int count = 0;
        Ssat[] ssat = new Ssat[Constants.MAXSAT];
        for (int i = 0; i < Constants.MAXSAT; i++) ssat[i] = new Ssat();

        GTime lastTime = null;
        for (int ep = 0; ep < baseEpochs.size(); ep++) {
            ObsEpoch epoch = baseEpochs.get(ep);
            if (lastTime != null) {
                double dt = Math.abs(TimeSystem.timediff(epoch.time, lastTime));
                if (dt < 0.99) continue;
            }
            lastTime = new GTime(epoch.time);

            Obsd[] tmpObs = new Obsd[epoch.n];
            int j = 0;
            for (int i = 0; i < epoch.n; i++) {
                int sys = SatUtils.satsys(epoch.obsd[i].sat, null);
                if ((sys & opt.navsys) == 0) continue;
                if (epoch.obsd[i].sat > 0 && epoch.obsd[i].sat <= opt.exsats.length
                        && opt.exsats[epoch.obsd[i].sat - 1] == 1) continue;
                tmpObs[j] = new Obsd(epoch.obsd[i]);
                tmpObs[j].rcv = 1;
                j++;
            }
            if (j <= 0) continue;
            Obsd[] filteredObs = new Obsd[j];
            System.arraycopy(tmpObs, 0, filteredObs, 0, j);

            double[] rs = new double[j * 6];
            double[] dts = new double[j * 2];
            double[] vare = new double[j];
            int[] svh = new int[j];
            EphModel.satposs(epoch.time, filteredObs, j, nav, rs, dts, vare, svh);

            Sol sol = new Sol();
            double[] azel = new double[j * 2];
            int[] vsat = new int[j];
            double[] resp = new double[j];
            String[] msg = new String[1];

            if (SppCore.estpos(filteredObs, j, rs, dts, vare, svh, nav, opt,
                    ssat, sol, azel, vsat, resp, msg) == 1) {
                sumPos[0] += sol.rr[0];
                sumPos[1] += sol.rr[1];
                sumPos[2] += sol.rr[2];
                count++;
            }
        }
        if (count > 0) {
            for (int i = 0; i < 3; i++) sumPos[i] /= count;
            return sumPos;
        }
        return null;
    }

    @BeforeAll
    static void loadData() throws IOException {
        new java.io.File(RESULT_DIR).mkdirs();

        log.info("加载 {} 小时数据: {} ~ {} ...", END_HOUR - START_HOUR, START_HOUR, END_HOUR);

        roverData = loadMultiHourData(ROVER_ID, START_HOUR, END_HOUR);
        baseData = loadMultiHourData(BASE_ID, START_HOUR, END_HOUR);
        log.info("Rover 数据: {} bytes, Base 数据: {} bytes", roverData.length, baseData.length);

        log.info("解析 Rover 观测数据 (station={})...", ROVER_ID);
        roverEpochs = parseObsEpochs(roverData, 1);
        log.info("Rover 历元数: {}", roverEpochs.size());

        log.info("解析 Base 观测数据 (station={})...", BASE_ID);
        baseEpochs = parseObsEpochs(baseData, 2);
        log.info("Base 历元数: {}", baseEpochs.size());

        log.info("解析导航数据 (rover)...");
        nav = parseNavData(roverData);
        int ephCount = 0;
        for (int i = 0; i < nav.eph.length; i++) {
            if (nav.eph[i] != null && nav.eph[i].sat > 0) ephCount++;
        }
        int gephCount = 0;
        for (int i = 0; i < nav.geph.length; i++) {
            if (nav.geph[i] != null && nav.geph[i].sat > 0) gephCount++;
        }
        log.info("Rover导航星历: eph={}, geph={}", ephCount, gephCount);

        log.info("合并导航数据 (base)...");
        mergeNavData(nav, baseData);
        ephCount = 0;
        for (int i = 0; i < nav.eph.length; i++) {
            if (nav.eph[i] != null && nav.eph[i].sat > 0) ephCount++;
        }
        gephCount = 0;
        for (int i = 0; i < nav.geph.length; i++) {
            if (nav.geph[i] != null && nav.geph[i].sat > 0) gephCount++;
        }
        log.info("合并后导航星历: eph={}, geph={}", ephCount, gephCount);

        if (!roverEpochs.isEmpty()) {
            for (int i = 0; i < Math.min(3, roverEpochs.size()); i++) {
                double[] ymdhms = TimeSystem.time2ymdhms(roverEpochs.get(i).time);
                log.info(String.format("Rover 历元 #%d: %04d-%02d-%02d %02d:%02d:%06.3f, nsat=%d",
                        i + 1, (int) ymdhms[0], (int) ymdhms[1], (int) ymdhms[2],
                        (int) ymdhms[3], (int) ymdhms[4], ymdhms[5], roverEpochs.get(i).n));
            }
        }
        if (!baseEpochs.isEmpty()) {
            for (int i = 0; i < Math.min(3, baseEpochs.size()); i++) {
                double[] ymdhms = TimeSystem.time2ymdhms(baseEpochs.get(i).time);
                log.info(String.format("Base  历元 #%d: %04d-%02d-%02d %02d:%02d:%06.3f, nsat=%d",
                        i + 1, (int) ymdhms[0], (int) ymdhms[1], (int) ymdhms[2],
                        (int) ymdhms[3], (int) ymdhms[4], ymdhms[5], baseEpochs.get(i).n));
            }
        }
    }

    private int runRtk(int ionoopt, String label, String resultFile) throws IOException {
        Rtk rtk = new Rtk();
        rtk.opt.mode = Constants.PMODE_STATIC;
        rtk.opt.nf = 2;
        rtk.opt.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL | Constants.SYS_CMP;
        rtk.opt.elmin = 15.0 * Constants.D2R;
        rtk.opt.ionoopt = ionoopt;
        rtk.opt.tropopt = Constants.TROPOPT_SAAS;
        rtk.opt.modear = Constants.ARMODE_FIXHOLD;

        String ionoName = ionoopt == Constants.IONOOPT_IFLC ? "IFLC" : "BRDC";
        log.info(String.format("RTK 配置: mode=STATIC, nf=2, ionoopt=%s(%d), tropopt=SAAS, modear=FIXHOLD, thresar[0]=%.1f",
                ionoName, ionoopt, rtk.opt.thresar[0]));

        log.info("计算基准站近似坐标...");
        double[] basePos = computeBasePosition(baseEpochs, nav, rtk.opt);
        if (basePos == null) {
            log.warn("无法计算基准站坐标，尝试用SPP(BRDC)...");
            PrcOpt sppOpt = new PrcOpt();
            sppOpt.mode = Constants.PMODE_SINGLE;
            sppOpt.nf = 1;
            sppOpt.navsys = rtk.opt.navsys;
            sppOpt.elmin = rtk.opt.elmin;
            sppOpt.ionoopt = Constants.IONOOPT_BRDC;
            sppOpt.tropopt = Constants.TROPOPT_SAAS;
            basePos = computeBasePosition(baseEpochs, nav, sppOpt);
        }
        assertNotNull(basePos, "应能计算基准站近似坐标");
        double[] baseLlh = new double[3];
        CoordTransform.ecef2pos(basePos, baseLlh);
        log.info(String.format("基准站坐标 ECEF: X=%.3f Y=%.3f Z=%.3f", basePos[0], basePos[1], basePos[2]));
        log.info(String.format("基准站坐标 LLH:  lat=%.9f lon=%.9f hgt=%.4f",
                baseLlh[0] / Constants.D2R, baseLlh[1] / Constants.D2R, baseLlh[2]));

        System.arraycopy(basePos, 0, rtk.opt.rb, 0, 3);
        System.arraycopy(basePos, 0, rtk.rb, 0, 3);

        BufferedWriter writer = ResultWriter.create(resultFile, label);
        ResultWriter.writePosHeader(writer, "RTK Static (" + ionoName + ")");

        int rtkCount = 0;
        int failCount = 0;
        int fixCount = 0;
        int floatCount = 0;
        int singleCount = 0;
        int matchedEpochs = 0;
        double maxRatio = 0;
        double sumRatio = 0;
        int ratioGt2 = 0, ratioGt25 = 0, ratioGt3 = 0;
        double[] refPos = null;
        double sumFixErr = 0;
        int fixErrCount = 0;
        double maxFixErr = 0;

        int baseIdx = 0;
        GTime lastMatchedBaseTime = null;

        for (ObsEpoch roverEpoch : roverEpochs) {
            GTime roverTime = roverEpoch.time;

            while (baseIdx >= 0 && baseIdx < baseEpochs.size()) {
                double dt = TimeSystem.timediff(baseEpochs.get(baseIdx).time, roverTime);
                if (Math.abs(dt) < 1.0) break;
                if (dt > 0) { baseIdx = -1; break; }
                baseIdx++;
            }

            if (baseIdx < 0 || baseIdx >= baseEpochs.size()) continue;

            ObsEpoch baseEpoch = baseEpochs.get(baseIdx);
            if (lastMatchedBaseTime != null
                    && TimeSystem.timediff(baseEpoch.time, lastMatchedBaseTime) == 0.0) {
                continue;
            }
            lastMatchedBaseTime = new GTime(baseEpoch.time);

            matchedEpochs++;
            int totalObs = roverEpoch.n + baseEpoch.n;
            Obsd[] combinedObs = new Obsd[totalObs];
            System.arraycopy(roverEpoch.obsd, 0, combinedObs, 0, roverEpoch.n);
            System.arraycopy(baseEpoch.obsd, 0, combinedObs, roverEpoch.n, baseEpoch.n);

            int result = RtkCore.rtkpos(rtk, combinedObs, totalObs, nav);

            if (result == 1) {
                rtkCount++;
                ResultWriter.writePosLine(writer, rtk.sol);
                if (rtk.sol.stat == Constants.SOLQ_FIX) fixCount++;
                else if (rtk.sol.stat == Constants.SOLQ_FLOAT) floatCount++;
                else if (rtk.sol.stat == Constants.SOLQ_SINGLE) singleCount++;
                double ratio = rtk.sol.ratio;
                if (ratio > maxRatio) maxRatio = ratio;
                sumRatio += ratio;
                if (ratio >= 2.0) ratioGt2++;
                if (ratio >= 2.5) ratioGt25++;
                if (ratio >= 3.0) ratioGt3++;
                if (rtk.sol.stat == Constants.SOLQ_FIX) {
                    if (refPos == null) refPos = rtk.sol.rr.clone();
                    double dE = rtk.sol.rr[0] - refPos[0];
                    double dN = rtk.sol.rr[1] - refPos[1];
                    double dU = rtk.sol.rr[2] - refPos[2];
                    double err3D = Math.sqrt(dE * dE + dN * dN + dU * dU);
                    sumFixErr += err3D;
                    fixErrCount++;
                    if (err3D > maxFixErr) maxFixErr = err3D;
                }
            } else {
                failCount++;
            }

            if (matchedEpochs <= 5) {
                double[] ymdhms = TimeSystem.time2ymdhms(roverEpoch.time);
                log.info(String.format("[%s] Epoch #%d: %02d:%02d:%06.3f result=%d stat=%d ns=%d age=%.1f ratio=%.1f",
                        ionoName, matchedEpochs, (int)ymdhms[3], (int)ymdhms[4], ymdhms[5],
                        result, rtk.sol.stat, rtk.sol.ns, rtk.sol.age, rtk.sol.ratio));
            }

            if (matchedEpochs % 200 == 0) {
                log.info(String.format("[%s] 进度: %d epochs, Fix=%d Float=%d Single=%d Fail=%d",
                        ionoName, matchedEpochs, fixCount, floatCount, singleCount, failCount));
            }
        }

        ResultWriter.writeSummary(writer, matchedEpochs, rtkCount, failCount, fixCount, floatCount, singleCount);
        writer.close();

        log.info(String.format("========================================"));
        log.info(String.format("[%s] RTK 结果:", ionoName));
        log.info(String.format("  匹配历元: %d", matchedEpochs));
        log.info(String.format("  成功: %d, 失败: %d", rtkCount, failCount));
        log.info(String.format("  Fix: %d, Float: %d, Single: %d", fixCount, floatCount, singleCount));
        if (matchedEpochs > 0) {
            log.info(String.format("  Fix率: %.1f%%", 100.0 * fixCount / matchedEpochs));
            log.info(String.format("  成功率: %.1f%%", 100.0 * rtkCount / matchedEpochs));
        }
        if (rtkCount > 0) {
            log.info(String.format("  Ratio统计: max=%.2f avg=%.2f", maxRatio, sumRatio / rtkCount));
            log.info(String.format("  Ratio分布: >=2.0: %d(%.1f%%), >=2.5: %d(%.1f%%), >=3.0: %d(%.1f%%)",
                    ratioGt2, 100.0 * ratioGt2 / rtkCount,
                    ratioGt25, 100.0 * ratioGt25 / rtkCount,
                    ratioGt3, 100.0 * ratioGt3 / rtkCount));
        }
        if (fixErrCount > 0) {
            log.info(String.format("  Fix位置精度(3D): avg=%.4fmm max=%.4fmm",
                    sumFixErr / fixErrCount * 1000, maxFixErr * 1000));
        }
        log.info(String.format("结果文件: %s", resultFile));
        log.info(String.format("========================================"));

        return fixCount;
    }

    @Test
    @DisplayName("SPP诊断 - 检查观测数据与星历")
    void testSppDiag() {
        assertTrue(roverEpochs.size() > 0, "应有 rover 观测历元");

        ObsEpoch epoch = roverEpochs.get(0);
        log.info(String.format("=== SPP诊断 ==="));

        int validObs = 0;
        for (int i = 0; i < epoch.n; i++) {
            Obsd obs = epoch.obsd[i];
            int sys = SatUtils.satsys(obs.sat, null);
            String sysName = sys == Constants.SYS_GPS ? "G" : sys == Constants.SYS_GLO ? "R" :
                    sys == Constants.SYS_GAL ? "E" : sys == Constants.SYS_CMP ? "C" : "?";
            int[] prnArr = new int[1];
            SatUtils.satsys(obs.sat, prnArr);
            int prn = prnArr[0];

            boolean hasCode = false;
            boolean hasPhase = false;
            for (int f = 0; f < Constants.NFREQ; f++) {
                if (obs.P[f] != 0) hasCode = true;
                if (obs.L[f] != 0) hasPhase = true;
            }

            boolean hasEph = false;
            if (sys == Constants.SYS_GLO) {
                if (obs.sat > 0 && obs.sat <= nav.geph.length && nav.geph[obs.sat - 1] != null)
                    hasEph = true;
            } else {
                if (obs.sat > 0 && obs.sat <= nav.eph.length && nav.eph[obs.sat - 1] != null)
                    hasEph = true;
            }

            log.info(String.format("  %s%02d sat=%d eph=%s  P[0]=%.1f P[1]=%.1f code[0]=%d code[1]=%d",
                    sysName, prn, obs.sat, hasEph,
                    obs.P[0], obs.P[1], obs.code[0], obs.code[1]));
            if (hasCode && hasEph) validObs++;
        }
        log.info(String.format("有效观测(有伪距+星历): %d / %d", validObs, epoch.n));

        for (int nf : new int[]{1, 2}) {
            for (int iono : new int[]{Constants.IONOOPT_BRDC, Constants.IONOOPT_IFLC}) {
                PrcOpt opt = new PrcOpt();
                opt.mode = Constants.PMODE_SINGLE;
                opt.nf = nf;
                opt.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL | Constants.SYS_CMP;
                opt.elmin = 15.0 * Constants.D2R;
                opt.ionoopt = iono;
                opt.tropopt = Constants.TROPOPT_SAAS;

                Sol sol = new Sol();
                Ssat[] ssat = new Ssat[Constants.MAXSAT];
                for (int i = 0; i < Constants.MAXSAT; i++) ssat[i] = new Ssat();
                int result = PntPos.pntpos(epoch.obsd, epoch.n, nav, opt, sol, null, ssat);
                String ionoName = iono == Constants.IONOOPT_IFLC ? "IFLC" : "BRDC";
                log.info(String.format("Rover SPP(nf=%d,iono=%s) result=%d stat=%d", nf, ionoName, result, sol.stat));
                if (result == 1) {
                    double[] llh = new double[3];
                    CoordTransform.ecef2pos(sol.rr, llh);
                    log.info(String.format("Rover SPP(nf=%d,iono=%s) lat=%.9f lon=%.9f hgt=%.4f",
                            nf, ionoName, llh[0] / Constants.D2R, llh[1] / Constants.D2R, llh[2]));
                }
            }
        }

        ObsEpoch baseEpoch0 = baseEpochs.get(0);
        Obsd[] baseObs = new Obsd[baseEpoch0.n];
        for (int i = 0; i < baseEpoch0.n; i++) {
            baseObs[i] = new Obsd(baseEpoch0.obsd[i]);
            baseObs[i].rcv = 1;
        }

        log.info("=== 直接调用rtkpos诊断 ===");
        Rtk rtk = new Rtk();
        rtk.opt.mode = Constants.PMODE_STATIC;
        rtk.opt.nf = 2;
        rtk.opt.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL | Constants.SYS_CMP;
        rtk.opt.elmin = 15.0 * Constants.D2R;
        rtk.opt.ionoopt = Constants.IONOOPT_BRDC;
        rtk.opt.tropopt = Constants.TROPOPT_SAAS;
        rtk.opt.modear = Constants.ARMODE_FIXHOLD;

        PrcOpt sppOpt = new PrcOpt();
        sppOpt.mode = Constants.PMODE_SINGLE;
        sppOpt.nf = 1;
        sppOpt.navsys = rtk.opt.navsys;
        sppOpt.elmin = rtk.opt.elmin;
        sppOpt.ionoopt = Constants.IONOOPT_BRDC;
        sppOpt.tropopt = Constants.TROPOPT_SAAS;
        double[] basePos = computeBasePosition(baseEpochs, nav, sppOpt);
        if (basePos != null) {
            System.arraycopy(basePos, 0, rtk.opt.rb, 0, 3);
            System.arraycopy(basePos, 0, rtk.rb, 0, 3);
            double[] baseLlh = new double[3];
            CoordTransform.ecef2pos(basePos, baseLlh);
            log.info(String.format("基准站: lat=%.9f lon=%.9f hgt=%.4f",
                    baseLlh[0] / Constants.D2R, baseLlh[1] / Constants.D2R, baseLlh[2]));
        } else {
            log.warn("无法计算基准站坐标");
        }

        ObsEpoch roverEp = roverEpochs.get(0);
        ObsEpoch baseEp = baseEpochs.get(0);
        int totalObs = roverEp.n + baseEp.n;
        Obsd[] combinedObs = new Obsd[totalObs];
        System.arraycopy(roverEp.obsd, 0, combinedObs, 0, roverEp.n);
        System.arraycopy(baseEp.obsd, 0, combinedObs, roverEp.n, baseEp.n);

        log.info(String.format("combinedObs: total=%d, rover(rcv=1)=%d, base(rcv=2)=%d",
                totalObs, roverEp.n, baseEp.n));
        for (int i = 0; i < Math.min(3, totalObs); i++) {
            log.info(String.format("  obs[%d]: sat=%d rcv=%d P[0]=%.1f code[0]=%d",
                    i, combinedObs[i].sat, combinedObs[i].rcv, combinedObs[i].P[0], combinedObs[i].code[0]));
        }
        log.info(String.format("  obs[%d]: sat=%d rcv=%d P[0]=%.1f code[0]=%d",
                roverEp.n, combinedObs[roverEp.n].sat, combinedObs[roverEp.n].rcv,
                combinedObs[roverEp.n].P[0], combinedObs[roverEp.n].code[0]));

        int result = RtkCore.rtkpos(rtk, combinedObs, totalObs, nav);
        log.info(String.format("rtkpos result=%d, sol.stat=%d, sol.ns=%d, sol.ratio=%.1f",
                result, rtk.sol.stat, rtk.sol.ns, rtk.sol.ratio));
    }

    @Test
    @DisplayName("RTK BRDC 电离层改正 (对比基线)")
    void testRtkBrdc() throws IOException {
        assertTrue(roverEpochs.size() > 0, "应有 rover 观测历元");
        assertTrue(baseEpochs.size() > 0, "应有 base 观测历元");
        int fixCount = runRtk(Constants.IONOOPT_BRDC, "RTK BRDC", RESULT_DIR + "\\rtk_brdc.pos");
        log.info(String.format("BRDC Fix解数: %d", fixCount));
    }

    @Test
    @DisplayName("RTK Iono-Free LC (ionoopt=IFLC) 解算")
    void testRtkIonoFreeLc() throws IOException {
        assertTrue(roverEpochs.size() > 0, "应有 rover 观测历元");
        assertTrue(baseEpochs.size() > 0, "应有 base 观测历元");
        int fixCount = runRtk(Constants.IONOOPT_IFLC, "RTK Iono-Free LC", RESULT_DIR + "\\rtk_ionofree_lc.pos");
        log.info(String.format("IFLC Fix解数: %d", fixCount));
    }

    @Test
    @DisplayName("C版RTKLIB IFLC对比测试")
    void testCRtklibIflc() throws IOException, InterruptedException {
        String rtklibDir = "D:\\code\\rtklib_java\\RTKLIB_EX_2.5.0";
        String workDir = "D:\\code\\rtklib_java\\rtk_compare\\c_rtklib_test";
        new java.io.File(workDir).mkdirs();

        String roverObs = workDir + "\\rover.obs";
        String baseObs = workDir + "\\base.obs";
        String navFile = workDir + "\\mixed.nav";
        String confFile = workDir + "\\c_rtk_iflc.conf";
        String posFile = workDir + "\\c_rtk_iflc.pos";
        String brdcPosFile = workDir + "\\c_rtk_brdc.pos";

        log.info("使用RinexObsWriter写RINEX观测文件...");
        PrcOpt sppOpt = new PrcOpt();
        sppOpt.mode = Constants.PMODE_SINGLE;
        sppOpt.nf = 1;
        sppOpt.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL | Constants.SYS_CMP;
        sppOpt.elmin = 15.0 * Constants.D2R;
        sppOpt.ionoopt = Constants.IONOOPT_BRDC;
        sppOpt.tropopt = Constants.TROPOPT_SAAS;
        writeRinexObsV3(roverEpochs, roverObs, "ROVER", sppOpt);
        writeRinexObsV3(baseEpochs, baseObs, "BASE", sppOpt);

        log.info("使用RinexNavWriter写RINEX导航文件...");
        RinexNavWriter navWriter = new RinexNavWriter(3.04, navFile);
        navWriter.setNavData(nav);
        navWriter.write();

        log.info("RINEX文件: rover.obs=" + new java.io.File(roverObs).length()
                + " base.obs=" + new java.io.File(baseObs).length()
                + " mixed.nav=" + new java.io.File(navFile).length());

        writeRnx2rtkpConf(confFile, "dual-freq", "l1+l2", 3.0);
        runCRtklib(rtklibDir, confFile, roverObs, baseObs, navFile, posFile);
        analyzePosFile(posFile, "C版 IFLC(dual-freq)");

        writeRnx2rtkpConf(confFile, "brdc", "l1", 3.0);
        runCRtklib(rtklibDir, confFile, roverObs, baseObs, navFile, brdcPosFile);
        analyzePosFile(brdcPosFile, "C版 单频+BRDC");

        String dualBrdcPosFile = workDir + "\\c_rtk_dual_brdc.pos";
        writeRnx2rtkpConf(confFile, "brdc", "l1+l2", 3.0);
        runCRtklib(rtklibDir, confFile, roverObs, baseObs, navFile, dualBrdcPosFile);
        analyzePosFile(dualBrdcPosFile, "C版 双频+BRDC");
    }

    private void writeRinexObsV3(List<ObsEpoch> epochs, String filePath, String marker, PrcOpt opt) throws IOException {
        Sta sta = new Sta();
        sta.name = marker;

        double[] approxPos = computeBasePosition(epochs, nav, opt);
        if (approxPos != null) {
            sta.pos = approxPos.clone();
        }

        int totalCount = 0;
        for (ObsEpoch ep : epochs) totalCount += ep.n;

        Obs obs = new Obs();
        obs.data = new Obsd[totalCount];
        obs.n = totalCount;
        int idx = 0;
        for (ObsEpoch ep : epochs) {
            for (int j = 0; j < ep.n; j++) {
                obs.data[idx] = new Obsd(ep.obsd[j]);
                obs.data[idx].rcv = 1;
                idx++;
            }
        }

        RinexObsWriter writer = new RinexObsWriter(3.04, filePath, sta);
        writer.setObstype(RinexObsWriter.OBSTYPE_PR | RinexObsWriter.OBSTYPE_CP);
        writer.setObsData(obs);
        writer.write();
    }

    private void writeRnx2rtkpConf(String confFile, String ionoopt, String frequency, double arthres) throws IOException {
        try (BufferedWriter w = new BufferedWriter(new FileWriter(confFile))) {
            w.write("pos1-posmode       =static\n");
            w.write("pos1-frequency     =" + frequency + "\n");
            w.write("pos1-soltype       =forward\n");
            w.write("pos1-elmask        =15\n");
            w.write("pos1-snrmask_r     =off\n");
            w.write("pos1-snrmask_b     =off\n");
            w.write("pos1-dynamics      =off\n");
            w.write("pos1-tidecorr      =off\n");
            w.write("pos1-ionoopt       =" + ionoopt + "\n");
            w.write("pos1-tropopt       =saas\n");
            w.write("pos1-sateph        =brdc\n");
            w.write("pos1-navsys        =45\n");
            w.write("pos2-armode        =fix-and-hold\n");
            w.write("pos2-gloarmode     =off\n");
            w.write("pos2-bdsarmode     =on\n");
            w.write("pos2-arfilter      =on\n");
            w.write("pos2-arthres       =" + arthres + "\n");
            w.write("pos2-arthresmin    =3\n");
            w.write("pos2-arthresmax    =3\n");
            w.write("pos2-arelmask      =15\n");
            w.write("pos2-arminfix      =20\n");
            w.write("pos2-elmaskhold    =15\n");
            w.write("pos2-aroutcnt      =20\n");
            w.write("pos2-maxage        =30\n");
            w.write("pos2-slipthres     =0.05\n");
            w.write("pos2-rejionno      =2\n");
            w.write("pos2-rejcode       =30\n");
            w.write("pos2-niter         =1\n");
            w.write("out-solformat      =llh\n");
            w.write("out-outhead        =on\n");
            w.write("out-outopt         =on\n");
            w.write("out-timesys        =gpst\n");
            w.write("out-timeform       =tow\n");
            w.write("out-timedec        =3\n");
            w.write("out-solstatic      =all\n");
            w.write("ant1-postype       =single\n");
            w.write("ant2-postype       =rinex\n");
        }
    }

    private void runCRtklib(String rtklibDir, String confFile, String roverObs, String baseObs, String navFile, String posFile) throws IOException, InterruptedException {
        String rnx2rtkp = rtklibDir + "\\rnx2rtkp.exe";
        ProcessBuilder pb = new ProcessBuilder(rnx2rtkp,
                "-k", confFile,
                "-o", posFile,
                roverObs, baseObs, navFile);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(proc.getInputStream()));
        String line;
        StringBuilder output = new StringBuilder();
        while ((line = reader.readLine()) != null) {
            output.append(line).append("\n");
        }
        int exitCode = proc.waitFor();
        log.info("C版rnx2rtkp退出码: " + exitCode);
        if (output.length() > 0) {
            String[] lines = output.toString().split("\n");
            for (String l : lines) {
                if (l.contains("error") || l.contains("Error") || l.contains("Fix") || l.contains("fix") || l.contains("ratio")) {
                    log.info("C版输出: " + l);
                }
            }
        }
    }

    private void analyzePosFile(String posFile, String label) throws IOException {
        java.io.File f = new java.io.File(posFile);
        if (!f.exists() || f.length() == 0) {
            log.info(label + ": 结果文件不存在或为空");
            return;
        }
        int total = 0, fix = 0, float_ = 0, single = 0;
        double sumRatio = 0, maxRatio = 0;
        try (java.io.BufferedReader r = new java.io.BufferedReader(new FileReader(posFile))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("%")) continue;
                String[] parts = line.trim().split("\\s+");
                if (parts.length < 8) continue;
                try {
                    int q = Integer.parseInt(parts[5]);
                    if (q == 1) fix++;
                    else if (q == 2) float_++;
                    else if (q == 5) single++;
                    total++;
                } catch (NumberFormatException e) {
                    continue;
                }
                if (parts.length >= 15) {
                    try {
                        double ratio = Double.parseDouble(parts[parts.length - 1]);
                        if (ratio > maxRatio) maxRatio = ratio;
                        sumRatio += ratio;
                    } catch (NumberFormatException ignored) {}
                }
            }
        }
        log.info(String.format(label + ": 总历元=%d, Fix=%d(%.1f%%), Float=%d, Single=%d, ratio_max=%.2f",
                total, fix, total > 0 ? 100.0 * fix / total : 0, float_, single, maxRatio));
    }
}