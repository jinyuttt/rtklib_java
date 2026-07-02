package org.rtklib.java;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.ephemeris.EphModel;
import org.rtklib.java.pntpos.SppCore;
import org.rtklib.java.rtcm.Rtcm;
import org.rtklib.java.rtkpos.RtkCore;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.time.TimeSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("3. RTK 定位测试")
public class RtkTest {

    private static final Logger log = LoggerFactory.getLogger(RtkTest.class);

    private static final String DATA_BASE =
            "D:\\tdengine-jetlinks\\jetlinks-data\\device_rtcmbin_storage";

    private static final String ROVER_ID = "GS2025090017";
    private static final String BASE_ID = "GS2025090006";
    private static final String DATE = "2026-07-01";
    private static final int HOUR = 8;

    private static final String ROVER_PATH =
            DATA_BASE + "\\" + ROVER_ID + "\\" + DATE + "\\" + HOUR + ".rtcm3";

    private static final String BASE_PATH =
            DATA_BASE + "\\" + BASE_ID + "\\" + DATE + "\\" + HOUR + ".rtcm3";

    private static final String RESULT_DIR = "D:\\code\\rtklib_java\\rtk_compare\\java_results";

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

    @BeforeAll
    static void loadData() throws IOException {
        try (FileInputStream fis = new FileInputStream(ROVER_PATH)) {
            roverData = fis.readAllBytes();
        }
        try (FileInputStream fis = new FileInputStream(BASE_PATH)) {
            baseData = fis.readAllBytes();
        }
        new java.io.File(RESULT_DIR).mkdirs();
        log.info("加载 Rover: {} bytes, Base: {} bytes", roverData.length, baseData.length);
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

    @Test
    @DisplayName("RTK 定位（输出到 .pos 文件）")
    void testRtkPositioning() throws IOException {
        String resultFile = RESULT_DIR + "\\3_rtk_result.pos";

        log.info("解析 Rover 观测数据...");
        List<ObsEpoch> roverEpochs = parseObsEpochs(roverData, 1);
        log.info("Rover 历元数: {}", roverEpochs.size());

        log.info("解析 Base 观测数据...");
        List<ObsEpoch> baseEpochs = parseObsEpochs(baseData, 2);
        log.info("Base 历元数: {}", baseEpochs.size());
        if (!baseEpochs.isEmpty()) {
            int rcv2count = 0;
            for (int i = 0; i < Math.min(baseEpochs.get(0).n, 5); i++) {
                log.info(String.format("Base obsd[%d].rcv=%d sat=%d", i, baseEpochs.get(0).obsd[i].rcv, baseEpochs.get(0).obsd[i].sat));
                if (baseEpochs.get(0).obsd[i].rcv == 2) rcv2count++;
            }
            log.info("Base first epoch: rcv=2 count={}/{}", rcv2count, baseEpochs.get(0).n);
        }

        log.info("解析导航数据...");
        Nav nav = parseNavData(roverData);

        assertTrue(roverEpochs.size() > 0, "应有 rover 观测历元");
        assertTrue(baseEpochs.size() > 0, "应有 base 观测历元");

        for (int i = 0; i < Math.min(3, roverEpochs.size()); i++) {
            double[] ymdhms = TimeSystem.time2ymdhms(roverEpochs.get(i).time);
            log.info(String.format("Rover 历元 #%d: %04d-%02d-%02d %02d:%02d:%06.3f, nsat=%d",
                    i + 1, (int)ymdhms[0], (int)ymdhms[1], (int)ymdhms[2],
                    (int)ymdhms[3], (int)ymdhms[4], ymdhms[5], roverEpochs.get(i).n));
        }
        for (int i = 0; i < Math.min(3, baseEpochs.size()); i++) {
            double[] ymdhms = TimeSystem.time2ymdhms(baseEpochs.get(i).time);
            log.info(String.format("Base  历元 #%d: %04d-%02d-%02d %02d:%02d:%06.3f, nsat=%d",
                    i + 1, (int)ymdhms[0], (int)ymdhms[1], (int)ymdhms[2],
                    (int)ymdhms[3], (int)ymdhms[4], ymdhms[5], baseEpochs.get(i).n));
        }

        Rtk rtk = new Rtk();
        rtk.opt.mode = Constants.PMODE_STATIC;
        rtk.opt.nf = 2;
        rtk.opt.navsys = Constants.SYS_GPS | Constants.SYS_SBS | Constants.SYS_GLO | Constants.SYS_GAL | Constants.SYS_CMP;

        rtk.opt.elmin = 15.0 * Constants.D2R;
        rtk.opt.ionoopt = Constants.IONOOPT_BRDC;
        rtk.opt.tropopt = Constants.TROPOPT_SAAS;
        rtk.opt.modear = Constants.ARMODE_FIXHOLD;

        log.info("计算基准站近似坐标...");
        double[] basePos = computeBasePosition(baseEpochs, nav, rtk.opt);
        assertNotNull(basePos, "应能计算基准站近似坐标");
        log.info(String.format("基准站近似坐标: X=%.3f Y=%.3f Z=%.3f", basePos[0], basePos[1], basePos[2]));
        double[] baseLlh = new double[3];
        CoordTransform.ecef2pos(basePos, baseLlh);
        log.info(String.format("基准站近似坐标(LLH): lat=%.9f lon=%.9f hgt=%.4f", baseLlh[0] / Constants.D2R, baseLlh[1] / Constants.D2R, baseLlh[2]));
        double[] cRefLlh = {29.189057880 * Constants.D2R, 95.075927980 * Constants.D2R, 709.4898};
        double[] cRefEcef = new double[3];
        CoordTransform.pos2ecef(cRefLlh, cRefEcef);
        log.info(String.format("C版ref pos ECEF: X=%.3f Y=%.3f Z=%.3f", cRefEcef[0], cRefEcef[1], cRefEcef[2]));
        log.info(String.format("基站坐标差: dX=%.3f dY=%.3f dZ=%.3f", basePos[0] - cRefEcef[0], basePos[1] - cRefEcef[1], basePos[2] - cRefEcef[2]));

        BufferedWriter writer = ResultWriter.create(resultFile, "RTK 定位结果");
        ResultWriter.writePosHeader(writer, "RTK (Kinematic)");
        String ecefFile = RESULT_DIR + "\\3_rtk_result_ecef.pos";
        BufferedWriter ecefWriter = ResultWriter.create(ecefFile, "RTK 定位结果 (ECEF)");

        System.arraycopy(basePos, 0, rtk.opt.rb, 0, 3);

        int rtkCount = 0;
        int failCount = 0;
        int fixCount = 0;
        int floatCount = 0;
        int singleCount = 0;
        int matchedEpochs = 0;

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

            if (matchedEpochs <= 3) {
                int rcv1 = 0, rcv2 = 0;
                for (int ci = 0; ci < totalObs; ci++) {
                    if (combinedObs[ci].rcv == 1) rcv1++;
                    else if (combinedObs[ci].rcv == 2) rcv2++;
                }
                log.info(String.format("Epoch #%d: totalObs=%d rcv1=%d rcv2=%d result=%d stat=%d",
                        matchedEpochs, totalObs, rcv1, rcv2, result, rtk.sol.stat));
            }

            if (result == 1) {
                rtkCount++;
                ResultWriter.writePosLine(writer, rtk.sol);
                ResultWriter.writeEcefLine(ecefWriter, rtk.sol);
                if (rtk.sol.stat == Constants.SOLQ_FIX) fixCount++;
                else if (rtk.sol.stat == Constants.SOLQ_FLOAT) floatCount++;
                else if (rtk.sol.stat == Constants.SOLQ_SINGLE) singleCount++;
            } else {
                failCount++;
            }
        }

        ResultWriter.writeSummary(writer, matchedEpochs, rtkCount, failCount, fixCount, floatCount, singleCount);
        writer.close();
        ecefWriter.close();

        log.info("RTK 结果已写入: {}", resultFile);
        log.info("RTK 结果: 匹配历元={}, 成功={}, 失败={}", matchedEpochs, rtkCount, failCount);
        log.info("RTK 解类型: Fix={}, Float={}, Single={}", fixCount, floatCount, singleCount);

        assertTrue(matchedEpochs > 0, "应至少有一个匹配的历元");
        log.info("RTK 定位测试 通过");
    }

    @Test
    @DisplayName("RTK SPP 回退模式（输出到 .pos 文件）")
    void testRtkFallbackToSpp() throws IOException {
        String resultFile = RESULT_DIR + "\\3_rtk_spp_fallback.pos";

        Rtk rtk = new Rtk();
        rtk.opt.mode = Constants.PMODE_SINGLE;
        rtk.opt.nf = 2;
        rtk.opt.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL
                | Constants.SYS_CMP | Constants.SYS_QZS;
        rtk.opt.elmin = 15.0 * Constants.D2R;
        rtk.opt.ionoopt = Constants.IONOOPT_BRDC;
        rtk.opt.tropopt = Constants.TROPOPT_SAAS;

        Nav nav = parseNavData(roverData);
        List<ObsEpoch> roverEpochs = parseObsEpochs(roverData, 1);

        assertTrue(roverEpochs.size() > 0, "应有 rover 观测历元");

        BufferedWriter writer = ResultWriter.create(resultFile, "RTK SPP 回退模式结果");
        ResultWriter.writePosHeader(writer, "SPP (via RtkCore)");

        int sppCount = 0;
        int failCount = 0;

        for (ObsEpoch epoch : roverEpochs) {
            int result = RtkCore.rtkpos(rtk, epoch.obsd, epoch.n, nav);
            if (result == 1) {
                sppCount++;
                ResultWriter.writePosLine(writer, rtk.sol);
            } else {
                failCount++;
            }
        }

        ResultWriter.writeSummary(writer, roverEpochs.size(), sppCount, failCount, 0, 0, sppCount);
        writer.close();

        log.info("RTK SPP 回退结果已写入: {}", resultFile);
        log.info("SPP 回退结果: 成功={}, 失败={}", sppCount, failCount);

        assertTrue(sppCount > 0, "应至少有一个成功的 SPP 解");
        log.info("RTK SPP 回退测试 通过");
    }
}