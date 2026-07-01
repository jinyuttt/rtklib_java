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
import org.rtklib.java.common.ObsCode;
import org.rtklib.java.common.RtklibCommon;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.ionosphere.IonosphereModel;
import org.rtklib.java.time.TimeSystem;
import org.rtklib.java.troposphere.TroposphereModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("2. SPP Single Point Positioning")
public class SppTest {

    private static final Logger log = LoggerFactory.getLogger(SppTest.class);

    private static final String ROVER_PATH =
            "C:\\Users\\jinyu\\Desktop\\540423494727\\2026-06-08\\1.rtcm3";

    private static final String RESULT_DIR = "C:\\Users\\jinyu\\Desktop\\rtklib_java_results";

    private static byte[] roverData;

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

    @BeforeAll
    static void loadData() throws IOException {
        try (FileInputStream fis = new FileInputStream(ROVER_PATH)) {
            roverData = fis.readAllBytes();
        }
        new java.io.File(RESULT_DIR).mkdirs();
        log.info("Loaded Rover RTCM data: {} bytes", roverData.length);
    }

    @Test
    @DisplayName("SPP positioning output .pos file")
    void testSppPositioning() throws IOException {
        String resultFile = RESULT_DIR + "\\2_spp_result.pos";
        BufferedWriter writer = ResultWriter.create(resultFile, "SPP positioning result");
        ResultWriter.writePosHeader(writer, "SPP (Single Point Positioning)");

        PrintWriter debugWriter = new PrintWriter(new FileWriter(RESULT_DIR + "\\2_spp_debug.log"));

        Rtcm rtcm = new Rtcm();
        Sol sol = new Sol();
        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_SINGLE;
        opt.nf = 2;
        /* [DIFF-C] C version rnx2rtkp defaults to navsys=GPS+GLO, must use -sys C for BDS.
           Java version sets navsys=SYS_CMP directly since data is BDS-only. The navsys
           parameter affects: (1) satexclude() - rejects non-selected system satellites,
           (2) NX estimation - only enabled systems get inter-system clock bias parameters,
           (3) dtr[] output - only enabled systems get clock bias output. */
        opt.navsys = Constants.SYS_CMP;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;

        Ssat[] ssat = new Ssat[Constants.MAXSAT];
        for (int i = 0; i < Constants.MAXSAT; i++) ssat[i] = new Ssat();

        boolean hasBasePos = false;
        double[] basePos = new double[3];
        java.util.Map<Integer, Integer> ephTypeCount = new java.util.TreeMap<>();
        java.util.Set<Integer> obsSats = new java.util.TreeSet<>();
        java.util.Set<String> obsSys = new java.util.TreeSet<>();

        java.util.List<Obsd[]> obsList = new java.util.ArrayList<>();
        java.util.List<Integer> obsCountList = new java.util.ArrayList<>();
        java.util.List<GTime> obsTimeList = new java.util.ArrayList<>();

        /* [DIFF-C] C version (rnx2rtkp) reads all RINEX data (including all ephemeris)
           before doing SPP. In RTCM stream processing, ephemeris and observation messages
           arrive interleaved. We use two-phase processing: first parse all RTCM to collect
           ephemeris, then do SPP for each observation epoch. This avoids the issue where
           satellites without ephemeris have rs=0 and el=-89.8 degrees. */
        int msgCount = 0;
        int offset = 0;
        while (offset < roverData.length) {
            int consumed = rtcm.input(roverData, offset, roverData.length - offset);
            if (consumed <= 0) {
                offset++;
                continue;
            }
            offset += consumed;
            msgCount++;
            if (msgCount <= 30) {
                debugWriter.println(String.format("Phase1 msg#%d type=%d timeInit=%s rtcm.time=%d/%.3f",
                        msgCount, rtcm.type, rtcm.isTimeInitialized(),
                        rtcm.time.time, rtcm.time.sec));
            }

            if (rtcm.type == 1005 || rtcm.type == 1006) {
                if (rtcm.sta != null) {
                    basePos[0] = rtcm.sta.pos[0];
                    basePos[1] = rtcm.sta.pos[1];
                    basePos[2] = rtcm.sta.pos[2];
                    hasBasePos = true;
                    log.info("RTCM {} base station position: ({}, {}, {})",
                            rtcm.type, basePos[0], basePos[1], basePos[2]);
                }
            }
            if (rtcm.type == 1019 || rtcm.type == 1020 || rtcm.type == 1042
                    || rtcm.type == 1045 || rtcm.type == 1046) {
                ephTypeCount.merge(rtcm.type, 1, Integer::sum);
            }

            if (isObsType(rtcm.type) && rtcm.obs.n > 0 && rtcm.obsflag == 1) {
                int n = rtcm.obs.n;
                Obsd[] obsCopy = new Obsd[n];
                for (int i = 0; i < n; i++) {
                    obsCopy[i] = new Obsd();
                    obsCopy[i].time = rtcm.obs.data[i].time;
                    obsCopy[i].sat = rtcm.obs.data[i].sat;
                    obsCopy[i].code = rtcm.obs.data[i].code.clone();
                    obsCopy[i].P = rtcm.obs.data[i].P.clone();
                    obsCopy[i].L = rtcm.obs.data[i].L.clone();
                    obsCopy[i].SNR = rtcm.obs.data[i].SNR.clone();
                    obsCopy[i].LLI = rtcm.obs.data[i].LLI.clone();

                    obsSats.add(rtcm.obs.data[i].sat);
                    int sys = SatUtils.satsys(rtcm.obs.data[i].sat, null);
                    String name;
                    if (sys == Constants.SYS_GPS) name = "GPS";
                    else if (sys == Constants.SYS_GLO) name = "GLO";
                    else if (sys == Constants.SYS_GAL) name = "GAL";
                    else if (sys == Constants.SYS_CMP) name = "BDS";
                    else if (sys == Constants.SYS_QZS) name = "QZS";
                    else if (sys == Constants.SYS_IRN) name = "IRN";
                    else if (sys == Constants.SYS_SBS) name = "SBS";
                    else name = "SYS" + sys;
                    obsSys.add(name);
                }
                obsList.add(obsCopy);
                obsCountList.add(n);
                obsTimeList.add(rtcm.obs.data[0].time);
            }
        }

        /* [SPP-PASSED] Fix observation times: when MSM messages arrive before ephemeris,
           adjweek uses week=0 as temporary reference. After ephemeris provides the correct
           week, we fix all observation times to use the ephemeris week number.
           This is specific to offline RTCM processing; real-time convbin does not need this. */
        if (!obsTimeList.isEmpty()) {
            GTime refTime = null;
            for (int i = 0; i < rtcm.nav.eph.length; i++) {
                if (rtcm.nav.eph[i] != null && rtcm.nav.eph[i].A > 0 && rtcm.nav.eph[i].toe.time > 0) {
                    refTime = rtcm.nav.eph[i].toe;
                    break;
                }
            }
            if (refTime != null) {
                int[] refWeek = new int[1];
                double refTow = TimeSystem.time2gpst(refTime, refWeek);
                for (int i = 0; i < obsTimeList.size(); i++) {
                    GTime obsTime = obsTimeList.get(i);
                    int[] obsWeek = new int[1];
                    double obsTow = TimeSystem.time2gpst(obsTime, obsWeek);
                    if (obsWeek[0] != refWeek[0]) {
                        obsTimeList.set(i, TimeSystem.gpst2time(refWeek[0], obsTow));
                        for (int j = 0; j < obsList.get(i).length; j++) {
                            int[] oWeek = new int[1];
                            double oTow = TimeSystem.time2gpst(obsList.get(i)[j].time, oWeek);
                            if (oWeek[0] != refWeek[0]) {
                                obsList.get(i)[j].time = TimeSystem.gpst2time(refWeek[0], oTow);
                            }
                        }
                    }
                }
            }
        }

        int ephValid = 0;
        for (int i = 0; i < rtcm.nav.eph.length; i++) {
            if (rtcm.nav.eph[i] != null && rtcm.nav.eph[i].A > 0) {
                ephValid++;
                if (ephValid <= 25) {
                    log.info("  eph[{}]: sat={}, A={}, week={}, iode={}",
                            i, rtcm.nav.eph[i].sat, String.format("%.1f", rtcm.nav.eph[i].A),
                            rtcm.nav.eph[i].week, rtcm.nav.eph[i].iode);
                }
            }
        }
        log.info("Phase 1 complete: ephTypes={}, obsEpochs={}, validEph={}",
                ephTypeCount, obsList.size(), ephValid);

        log.info("Phase 2: SPP positioning for each observation epoch...");
        if (hasBasePos && sol.rr[0] == 0.0 && sol.rr[1] == 0.0 && sol.rr[2] == 0.0) {
            sol.rr[0] = basePos[0];
            sol.rr[1] = basePos[1];
            sol.rr[2] = basePos[2];
            log.info("Initialized sol.rr from base station: ({}, {}, {})",
                    sol.rr[0], sol.rr[1], sol.rr[2]);
        }

        int sppCount = 0;
        int failCount = 0;

        for (int epoch = 0; epoch < obsList.size(); epoch++) {
            Obsd[] obsData = obsList.get(epoch);
            int n = obsCountList.get(epoch);

            double[] rs = new double[n * 6];
            double[] dts = new double[n * 2];
            double[] vare = new double[n];
            int[] svh = new int[n];

            /* SPP通过 - Use satposs() aligned with RTKLIB C version, which handles
               signal transit time, clock bias iteration, and best ephemeris selection */
            EphModel.satposs(obsTimeList.get(epoch), obsData, n, rtcm.nav,
                    rs, dts, vare, svh);

            int[] vsat = new int[n];
            double[] azel = new double[n * 2];
            double[] resp = new double[n];
            String[] msg = new String[1];

            if (epoch <= 1) {
                debugWriter.println("=== EPOCH " + epoch + " DEBUG ===");
                debugWriter.println(String.format("obsTime: time=%d sec=%.6f", obsTimeList.get(epoch).time, obsTimeList.get(epoch).sec));
                debugWriter.println(String.format("sol.rr init: %.3f %.3f %.3f",
                        sol.rr[0], sol.rr[1], sol.rr[2]));
                for (int di = 0; di < n; di++) {
                    int dsat = obsData[di].sat;
                    int[] dprn = new int[1];
                    int dsys = SatUtils.satsys(dsat, dprn);
                    String sysStr = dsys == Constants.SYS_CMP ? "BDS" : "OTH";
                    double[] drsi = {rs[di * 6], rs[di * 6 + 1], rs[di * 6 + 2]};
                    double ddts0 = dts[di * 2];
                    int dsvh = svh[di];
                    StringBuilder sigInfo = new StringBuilder();
                    for (int fi = 0; fi < Constants.NFREQ + Constants.NEXOBS; fi++) {
                        if (obsData[di].P[fi] != 0.0 || obsData[di].code[fi] != 0) {
                            int dcode = obsData[di].code[fi];
                            String codeStr = ObsCode.code2obs(dcode);
                            double dfreq = SatUtils.sat2freq(dsat, dcode, rtcm.nav);
                            sigInfo.append(String.format(" [f%d:%s P=%.3f freq=%.1f]", fi, codeStr, obsData[di].P[fi], dfreq));
                        }
                    }
                    Eph dEph = (dsat > 0 && dsat <= rtcm.nav.eph.length) ? rtcm.nav.eph[dsat - 1] : null;
                    String ephInfo = (dEph != null && dEph.A > 0) ?
                            String.format("A=%.1f toe.time=%d toe.sec=%.1f iode=%d", dEph.A, dEph.toe.time, dEph.toe.sec, dEph.iode) : "null";
                    debugWriter.println(String.format(
                            "  sat=%d(%s%d) rs=[%.3f,%.3f,%.3f] dts=%.12f svh=%d eph=%s sig=%s",
                            dsat, sysStr, dprn[0], drsi[0], drsi[1], drsi[2], ddts0, dsvh, ephInfo, sigInfo.toString()));
                }
            }

            int result = SppCore.estpos(obsData, n, rs, dts, vare, svh,
                    rtcm.nav, opt, ssat, sol, azel, vsat, resp, msg);

            if (epoch == 1 && result == 1) {
                debugWriter.println(String.format("SPP result: rr=[%.3f,%.3f,%.3f] ns=%d",
                        sol.rr[0], sol.rr[1], sol.rr[2], sol.ns));
                double[] drr = {sol.rr[0], sol.rr[1], sol.rr[2]};
                double[] dpos = new double[3];
                CoordTransform.ecef2pos(drr, dpos);
                for (int di = 0; di < n; di++) {
                    if (vsat[di] == 0) continue;
                    int dsat = obsData[di].sat;
                    double[] drsi = {rs[di * 6], rs[di * 6 + 1], rs[di * 6 + 2]};
                    double[] de = new double[3];
                    double dr = RtklibCommon.geodist(drsi, drr, de);
                    double[] dae = new double[2];
                    RtklibCommon.satazel(dpos, de, dae);
                    double ddts = dts[di * 2];
                    double dP0 = obsData[di].P[0];
                    int dcode0 = obsData[di].code[0];
                    double[] dvarMeas = new double[1];
                    double dP = SppCore.prange(obsData[di], rtcm.nav, opt, dvarMeas);
                    double dfreq = SatUtils.sat2freq(dsat, dcode0, rtcm.nav);
                    double[] dionOut = new double[2];
                    IonosphereModel.ionocorr(obsData[di].time, rtcm.nav, dsat, dpos, dae, opt.ionoopt, dionOut);
                    double ddion = dionOut[0] * RtklibCommon.sqr(Constants.FREQL1 / dfreq);
                    double[] dtrpOut = new double[2];
                    TroposphereModel.tropcorr(obsData[di].time, rtcm.nav, dpos, dae, opt.tropopt, dtrpOut);
                    double ddtrp = dtrpOut[0];
                    double dtgd = SppCore.gettgd(dsat, rtcm.nav, 0);
                    debugWriter.println(String.format(
                            "  sat=%d P=%.3f Prange=%.3f r=%.3f CLIGHT*dts=%.3f dion=%.3f dtrp=%.3f tgd=%.3f code0=%d freq=%.1f res=%.3f",
                            dsat, dP0, dP, dr, Constants.CLIGHT * ddts, ddion, ddtrp, dtgd, dcode0, dfreq, resp[di]));
                }
            }

            if (result == 1) {
                sppCount++;
                ResultWriter.writePosLine(writer, sol);
                if (sppCount <= 5 || sppCount % 50 == 0) {
                    double[] llh = new double[3]; CoordTransform.ecef2pos(sol.rr, llh);
                    GTime t = obsTimeList.get(epoch);
                    int[] wk = new int[1];
                    double sow = TimeSystem.time2gpst(t, wk);
                    int sod = (int) sow;
                    String ts = String.format("%02d:%02d:%02d", sod / 3600, (sod % 3600) / 60, sod % 60);
                    log.info("SPP success #{}: epoch={}, time={}, n={}, lat={} lon={} h={} ns={}",
                            sppCount, epoch, ts, n,
                            String.format("%.9f", Math.toDegrees(llh[0])),
                            String.format("%.9f", Math.toDegrees(llh[1])),
                            String.format("%.3f", llh[2]), sol.ns);
                }
                debugWriter.println(String.format("SPP success #%d: epoch=%d n=%d ns=%d",
                        sppCount, epoch, n, sol.ns));
            } else {
                failCount++;
                if (failCount <= 5) {
                    GTime tf = obsTimeList.get(epoch);
                    int[] wkf = new int[1];
                    double sowf = TimeSystem.time2gpst(tf, wkf);
                    int sodf = (int) sowf;
                    String tsf = String.format("%02d:%02d:%02d", sodf / 3600, (sodf % 3600) / 60, sodf % 60);
                    log.warn("SPP fail #{}: epoch={}, time={}, msg={}, n={}", failCount, epoch, tsf, msg[0], n);
                    int validEph = 0, validObs = 0;
                    StringBuilder ephDetail = new StringBuilder();
                    for (int j = 0; j < n; j++) {
                        if (obsData[j].P[0] != 0.0) validObs++;
                        int s = obsData[j].sat;
                        if (s > 0 && s <= Constants.MAXSAT) {
                            Eph e = rtcm.nav.eph[s - 1];
                            if (e == null || e.A <= 0) {
                                int idx2 = s - 1 + Constants.MAXSAT;
                                if (idx2 < rtcm.nav.eph.length) e = rtcm.nav.eph[idx2];
                            }
                            if (e != null && e.A > 0) validEph++;
                            else {
                                if (ephDetail.length() < 200)
                                    ephDetail.append(" sat").append(s).append("(noEph)");
                            }
                        }
                    }
                    log.warn("  validObs={}, validEph={}, noEph: {}", validObs, validEph, ephDetail);
                }
                debugWriter.println(String.format("SPP fail #%d: epoch=%d msg=%s n=%d",
                        failCount, epoch, msg[0], n));
            }
        }

        ResultWriter.writeSummary(writer, sppCount + failCount, sppCount, failCount, 0, 0, sppCount);
        writer.close();
        debugWriter.close();
        log.info("SPP result written to: {}", resultFile);
        log.info("SPP result: success={}, fail={}", sppCount, failCount);
        log.info("Ephemeris message stats: {}", ephTypeCount);
        log.info("Observation satellite systems: {}", obsSys);
        log.info("Observation satellite IDs: {}", obsSats);

        assertTrue(sppCount + failCount > 0, "should have at least one epoch");
        assertTrue(sppCount > 0, "should have at least one successful SPP solution");
        log.info("SPP single point positioning test PASSED");
    }
}