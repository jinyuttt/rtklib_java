package org.rtklib.java;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.rtklib.java.common.ObsCode;
import org.rtklib.java.common.RtklibCommon;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.ephemeris.EphModel;
import org.rtklib.java.pntpos.PntPos;
import org.rtklib.java.rinex.RinexParser;
import org.rtklib.java.rinex.RinexRtkProcessor;
import org.rtklib.java.rtkpos.RtkProcessor;
import org.rtklib.java.time.TimeSystem;

import java.util.ArrayList;
import java.util.List;

/**
 * 临时诊断测试：排查 cssrlib RINEX RTK 测试 nv=0（无双差观测）的根因。
 * 不修改任何现有代码，仅做诊断输出。
 */
@DisplayName("Cssr nv=0 Diagnostic")
public class CssrDiagTest {

    private static final String TEST_DATA_DIR = TestDataConfig.getTestDataDir();
    private static final String ROVER = TEST_DATA_DIR + "/rinex/rinex304_gej_3034.21o";
    private static final String BASE  = TEST_DATA_DIR + "/rinex/rinex304_gej_sept.21o";
    private static final String NAV   = TEST_DATA_DIR + "/nav/rinex304_mixed_sept.21p";

    private String satId(int sat) {
        return SatUtils.satno2id(sat);
    }

    private String sysName(int sys) {
        if (sys == Constants.SYS_GPS) return "GPS";
        if (sys == Constants.SYS_GAL) return "GAL";
        if (sys == Constants.SYS_QZS) return "QZS";
        if (sys == Constants.SYS_CMP) return "CMP";
        if (sys == Constants.SYS_GLO) return "GLO";
        return "SYS(" + sys + ")";
    }

    @Test
    @DisplayName("Diagnose nv=0 root cause")
    void diagnoseNv0() {
        System.out.println("====== CSSR nv=0 DIAGNOSTIC START ======");
        System.out.println("Rover: " + ROVER);
        System.out.println("Base:  " + BASE);
        System.out.println("Nav:   " + NAV);

        // ---- Step 0: Run actual RTK to confirm nv=0 ----
        runActualRtk();

        // ---- Step 1: Parse RINEX observation files ----
        System.out.println("\n====== STEP 1: RINEX Observation Parsing ======");
        RinexParser roverParser = new RinexParser();
        boolean roverOk = roverParser.parseObs(ROVER);
        System.out.println("Rover parse: " + roverOk + ", obs.n=" + roverParser.obs.n);
        if (roverParser.sta != null && roverParser.sta.pos != null) {
            System.out.printf("Rover sta.pos: (%.4f, %.4f, %.4f)%n",
                    roverParser.sta.pos[0], roverParser.sta.pos[1], roverParser.sta.pos[2]);
        }

        RinexParser baseParser = new RinexParser();
        boolean baseOk = baseParser.parseObs(BASE);
        System.out.println("Base parse: " + baseOk + ", obs.n=" + baseParser.obs.n);
        if (baseParser.sta != null && baseParser.sta.pos != null) {
            System.out.printf("Base sta.pos: (%.4f, %.4f, %.4f)%n",
                    baseParser.sta.pos[0], baseParser.sta.pos[1], baseParser.sta.pos[2]);
        }

        // Group by epoch
        List<List<Obsd>> roverEpochs = groupByEpoch(roverParser.obs.data, roverParser.obs.n);
        List<List<Obsd>> baseEpochs  = groupByEpoch(baseParser.obs.data, baseParser.obs.n);
        System.out.println("Rover epochs: " + roverEpochs.size());
        System.out.println("Base epochs:  " + baseEpochs.size());

        // Print first few epochs satellite counts
        int maxPrint = Math.min(5, Math.min(roverEpochs.size(), baseEpochs.size()));
        for (int e = 0; e < maxPrint; e++) {
            List<Obsd> re = roverEpochs.get(e);
            List<Obsd> be = baseEpochs.get(e);
            StringBuilder roverSats = new StringBuilder();
            for (Obsd o : re) roverSats.append(satId(o.sat)).append(" ");
            StringBuilder baseSats = new StringBuilder();
            for (Obsd o : be) baseSats.append(satId(o.sat)).append(" ");
            System.out.println("Epoch " + e + " time=" + re.get(0).time +
                    " rover_ns=" + re.size() + " base_ns=" + be.size());
            System.out.println("  Rover sats: " + roverSats);
            System.out.println("  Base  sats: " + baseSats);
        }

        // ---- Step 2: Parse Navigation file ----
        System.out.println("\n====== STEP 2: Navigation Ephemeris ======");
        RinexParser navParser = new RinexParser();
        boolean navOk = navParser.parseNav(NAV);
        System.out.println("Nav parse: " + navOk);
        Nav nav = navParser.nav;
        System.out.println("nav.n=" + nav.n + " (broadcast eph count)");
        int ephGps = 0, ephGal = 0, ephQzs = 0, ephOther = 0;
        if (nav.eph != null) {
            for (Eph eph : nav.eph) {
                if (eph == null) continue;
                int sys = SatUtils.satsys(eph.sat, null);
                if (sys == Constants.SYS_GPS) ephGps++;
                else if (sys == Constants.SYS_GAL) ephGal++;
                else if (sys == Constants.SYS_QZS) ephQzs++;
                else ephOther++;
            }
        }
        System.out.println("Eph by sys: GPS=" + ephGps + " GAL=" + ephGal + " QZS=" + ephQzs + " other=" + ephOther);

        // Print ephemeris details
        if (nav.eph != null) {
            System.out.println("Ephemeris list (sat, toe):");
            for (Eph eph : nav.eph) {
                if (eph == null) continue;
                int sys = SatUtils.satsys(eph.sat, null);
                if ((sys & (Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_QZS)) == 0) continue;
                System.out.println("  " + satId(eph.sat) + " toe=" + eph.toe + " svh=" + eph.svh);
            }
        }

        // ---- Step 3: Satellite positions for first epoch ----
        System.out.println("\n====== STEP 3: Satellite Positions (first epoch) ======");
        if (roverEpochs.isEmpty()) {
            System.out.println("No rover epochs!");
            return;
        }
        List<Obsd> firstRover = roverEpochs.get(0);
        GTime epochTime = firstRover.get(0).time;
        System.out.println("Epoch time: " + epochTime);

        for (Obsd o : firstRover) {
            double[] rs = new double[6];
            double[] dts = new double[2];
            double[] vare = new double[1];
            EphModel.satpos(epochTime, nav, o.sat, rs, dts, vare);
            double rNorm = Math.sqrt(rs[0]*rs[0] + rs[1]*rs[1] + rs[2]*rs[2]);
            boolean ok = rNorm > Constants.RE_WGS84 / 2.0;
            System.out.printf("  %s: satpos %s, |rs|=%.1f km, vare=%.1f, dts=%.6e%n",
                    satId(o.sat), ok ? "OK" : "FAIL", rNorm / 1000.0, vare[0], dts[0]);
        }

        // ---- Step 4: Common satellites (selsat logic) ----
        System.out.println("\n====== STEP 4: Common Satellites ======");
        // Find matching base epoch
        List<Obsd> matchBase = null;
        double bestDt = Double.MAX_VALUE;
        for (List<Obsd> be : baseEpochs) {
            if (be.isEmpty()) continue;
            double dt = Math.abs(TimeSystem.timediff(epochTime, be.get(0).time));
            if (dt < bestDt) {
                bestDt = dt;
                matchBase = be;
            }
        }
        System.out.println("Best base epoch dt=" + bestDt + "s, ns=" + (matchBase != null ? matchBase.size() : 0));

        if (matchBase != null) {
            // Simulate selsat: rover obs sorted by sat, base obs sorted by sat
            List<Obsd> roverSorted = new ArrayList<>(firstRover);
            roverSorted.sort((a, b) -> Integer.compare(a.sat, b.sat));
            List<Obsd> baseSorted = new ArrayList<>(matchBase);
            baseSorted.sort((a, b) -> Integer.compare(a.sat, b.sat));

            int i = 0, j = 0;
            int common = 0;
            System.out.println("Common satellites (before elmin check):");
            while (i < roverSorted.size() && j < baseSorted.size()) {
                if (roverSorted.get(i).sat < baseSorted.get(j).sat) {
                    i++;
                } else if (roverSorted.get(i).sat > baseSorted.get(j).sat) {
                    j++;
                } else {
                    int sys = SatUtils.satsys(roverSorted.get(i).sat, null);
                    boolean navsysOk = (sys & (Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_QZS)) != 0;
                    System.out.printf("  %s sys=%s navsys_ok=%s%n",
                            satId(roverSorted.get(i).sat), sysName(sys), navsysOk);
                    common++;
                    i++; j++;
                }
            }
            System.out.println("Common satellites: " + common);
        }

        // ---- Step 5: Obs type codes and frequency mapping ----
        System.out.println("\n====== STEP 5: Obs Type Codes & Frequency Mapping ======");
        // Before compactObsFreq
        System.out.println("--- Before compactObsFreq ---");
        printObsCodes("Rover", firstRover, nav);
        if (matchBase != null) printObsCodes("Base", matchBase, nav);

        // Apply compactObsFreq (like RtkCore.rtkpos does)
        // Build combined obs array
        List<Obsd> allObs = new ArrayList<>();
        for (Obsd o : firstRover) {
            o.rcv = 1;
            int sys = SatUtils.satsys(o.sat, null);
            if ((sys & (Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_QZS)) != 0) {
                allObs.add(o);
            }
        }
        if (matchBase != null) {
            for (Obsd o : matchBase) {
                o.rcv = 2;
                int sys = SatUtils.satsys(o.sat, null);
                if ((sys & (Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_QZS)) != 0) {
                    allObs.add(o);
                }
            }
        }
        Obsd[] epochObs = allObs.toArray(new Obsd[0]);
        int nu = 0, nr = 0;
        for (nu = 0; nu < epochObs.length && epochObs[nu].rcv == 1; nu++) ;
        for (nr = 0; nu + nr < epochObs.length && epochObs[nu + nr].rcv == 2; nr++) ;
        System.out.println("\nAfter navsys filter: nu=" + nu + " nr=" + nr + " total=" + epochObs.length);

        int nfOpt = 2; // nf=2, ionoopt=BRDC (not IFLC)
        RtklibCommon.compactObsFreq(epochObs, epochObs.length, nfOpt, nav);
        System.out.println("--- After compactObsFreq ---");
        printObsCodes("Rover (after compact)", epochObs, nu, nav);
        printObsCodes("Base  (after compact)", epochObs, nu, nr, nav);

        // ---- Step 6: zdres simulation ----
        System.out.println("\n====== STEP 6: zdres Simulation ======");
        simulateZdres(epochObs, nu, nr, nav, epochTime);

        // ---- Step 7: ddres simulation ----
        System.out.println("\n====== STEP 7: ddres Simulation ======");
        simulateDdres(epochObs, nu, nr, nav, epochTime);

        System.out.println("\n====== CSSR nv=0 DIAGNOSTIC END ======");
    }

    private void runActualRtk() {
        System.out.println("\n--- Running actual RTK (processRinex) ---");
        PrcOpt opt = RinexRtkProcessor.createDefaultOpt();
        opt.mode = Constants.PMODE_STATIC;
        opt.nf = 2;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_QZS;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;
        opt.modear = Constants.ARMODE_FIXHOLD;
        opt.refpos = Constants.POSOPT_POS_XYZ;
        try {
            RtkProcessor.RtkResult result = RinexRtkProcessor.processRinex(ROVER, BASE, NAV, opt);
            System.out.println("RTK result: total=" + result.totalEpochs +
                    " success=" + result.successCount + " fail=" + result.failCount);
        } catch (Exception e) {
            System.out.println("RTK exception: " + e.getMessage());
            e.printStackTrace(System.out);
        }
    }

    private List<List<Obsd>> groupByEpoch(Obsd[] data, int n) {
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

    private void printObsCodes(String label, List<Obsd> obsList, Nav nav) {
        for (Obsd o : obsList) {
            int sys = SatUtils.satsys(o.sat, null);
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("  [%s] %s sys=%s: ", label, satId(o.sat), sysName(sys)));
            for (int f = 0; f < Constants.NFREQ + Constants.NEXOBS; f++) {
                if (o.code[f] == 0 && o.P[f] == 0.0 && o.L[f] == 0.0) continue;
                String codeStr = ObsCode.code2obs(o.code[f]);
                double freq = SatUtils.sat2freq(o.sat, o.code[f], nav);
                int idx = ObsCode.code2idx(sys, o.code[f]);
                sb.append(String.format("[f%d code=%s val=%s freq=%.3e idx=%d] ",
                        f, codeStr, o.P[f] != 0.0 ? "P" : (o.L[f] != 0.0 ? "L" : "0"), freq, idx));
            }
            System.out.println(sb);
        }
    }

    private void printObsCodes(String label, Obsd[] obs, int start, int count, Nav nav) {
        for (int i = start; i < start + count && i < obs.length; i++) {
            Obsd o = obs[i];
            int sys = SatUtils.satsys(o.sat, null);
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("  [%s] %s sys=%s: ", label, satId(o.sat), sysName(sys)));
            for (int f = 0; f < Constants.NFREQ + Constants.NEXOBS; f++) {
                if (o.code[f] == 0 && o.P[f] == 0.0 && o.L[f] == 0.0) continue;
                String codeStr = ObsCode.code2obs(o.code[f]);
                double freq = SatUtils.sat2freq(o.sat, o.code[f], nav);
                int idx = ObsCode.code2idx(sys, o.code[f]);
                double lam = freq > 0 ? Constants.CLIGHT / freq : 0;
                sb.append(String.format("[f%d code=%s P=%.1f L=%.1f freq=%.4e idx=%d lam=%.4f] ",
                        f, codeStr, o.P[f], o.L[f], freq, idx, lam));
            }
            System.out.println(sb);
        }
    }

    private void printObsCodes(String label, Obsd[] obs, int nu, Nav nav) {
        printObsCodes(label, obs, 0, nu, nav);
    }

    /**
     * Simulate zdres to check which satellites pass elevation cutoff and have valid phase obs.
     */
    private void simulateZdres(Obsd[] obs, int nu, int nr, Nav nav, GTime epochTime) {
        int n = nu + nr;
        int nf = 2;
        double[] rs = new double[n * 6];
        double[] dts = new double[n * 2];
        double[] vare = new double[n];
        int[] svh = new int[n];

        // Call satposs
        EphModel.satposs(epochTime, obs, n, nav, rs, dts, vare, svh);

        // Check base position
        double[] basePos = null;
        // Try RINEX header position
        RinexParser baseParser = new RinexParser();
        baseParser.parseObs(BASE);
        if (baseParser.sta != null && baseParser.sta.pos != null &&
            (baseParser.sta.pos[0] != 0.0 || baseParser.sta.pos[1] != 0.0 || baseParser.sta.pos[2] != 0.0)) {
            basePos = baseParser.sta.pos.clone();
            System.out.println("Base position from RINEX header: (" +
                    basePos[0] + ", " + basePos[1] + ", " + basePos[2] + ")");
        } else {
            System.out.println("WARNING: Base position is ZERO! zdres would return false.");
            // Try SPP
            basePos = computeSppPos(obs, nu, nr, nav, epochTime);
            if (basePos != null) {
                System.out.println("Base position from SPP: (" +
                        basePos[0] + ", " + basePos[1] + ", " + basePos[2] + ")");
            } else {
                System.out.println("SPP also failed for base position!");
                return;
            }
        }

        // Check rover approximate position
        double[] roverPos = null;
        RinexParser roverParser = new RinexParser();
        roverParser.parseObs(ROVER);
        if (roverParser.sta != null && roverParser.sta.pos != null &&
            (roverParser.sta.pos[0] != 0.0 || roverParser.sta.pos[1] != 0.0 || roverParser.sta.pos[2] != 0.0)) {
            roverPos = roverParser.sta.pos.clone();
            System.out.println("Rover approx position from RINEX header: (" +
                    roverPos[0] + ", " + roverPos[1] + ", " + roverPos[2] + ")");
        }

        double elmin = 15.0 * Constants.D2R;

        // Simulate zdres for base (rcv=2) and rover (rcv=1)
        for (int rcv = 0; rcv <= 1; rcv++) {
            String rcvName = rcv == 0 ? "Rover" : "Base";
            int start = rcv == 0 ? 0 : nu;
            int cnt = rcv == 0 ? nu : nr;
            double[] rr = rcv == 0 ? roverPos : basePos;
            if (rr == null) {
                System.out.println(rcvName + ": no position, skipping zdres sim");
                continue;
            }
            double rrNorm = Math.sqrt(rr[0]*rr[0] + rr[1]*rr[1] + rr[2]*rr[2]);
            if (rrNorm <= Constants.RE_WGS84 / 2.0) {
                System.out.println(rcvName + ": position too small (|rr|=" + rrNorm + "), zdres would fail!");
                continue;
            }
            double[] pos = new double[3];
            CoordTransform.ecef2pos(rr, pos);

            System.out.println("\n--- zdres sim: " + rcvName + " (|rr|=" + rrNorm + ") ---");
            int passEl = 0, hasPhase = 0, hasFreq0 = 0, hasFreq1 = 0;

            for (int i = 0; i < cnt; i++) {
                int idx = start + i;
                double[] rsi = {rs[idx*6], rs[idx*6+1], rs[idx*6+2]};
                double[] ei = new double[3];
                double r = RtklibCommon.geodist(rsi, rr, ei);
                if (r <= 0.0) {
                    System.out.printf("  %s: geodist<=0 (r=%.1f) rs=(%.1f,%.1f,%.1f)%n",
                            satId(obs[idx].sat), r, rsi[0], rsi[1], rsi[2]);
                    continue;
                }
                double[] ae = new double[2];
                double el = RtklibCommon.satazel(pos, ei, ae);
                double elDeg = el * Constants.R2D;
                boolean aboveEl = el >= elmin;

                // Check freq and obs values
                double freq0 = SatUtils.sat2freq(obs[idx].sat, obs[idx].code[0], nav);
                double freq1 = SatUtils.sat2freq(obs[idx].sat, obs[idx].code[1], nav);
                boolean hasL0 = obs[idx].L[0] != 0.0;
                boolean hasL1 = obs[idx].L[1] != 0.0;
                boolean hasP0 = obs[idx].P[0] != 0.0;
                boolean hasP1 = obs[idx].P[1] != 0.0;
                boolean hasPr = false;
                for (int f2 = 0; f2 < Constants.NFREQ; f2++) {
                    if (obs[idx].P[f2] != 0.0) { hasPr = true; break; }
                }

                if (aboveEl) passEl++;
                if (hasL0 || hasL1) hasPhase++;
                if (freq0 > 0 && hasL0) hasFreq0++;
                if (freq1 > 0 && hasL1) hasFreq1++;

                if (!aboveEl || !hasPr) {
                    System.out.printf("  %s: el=%.1f° aboveEl=%s hasPr=%s freq0=%.4e freq1=%.4e L0=%s L1=%s P0=%s P1=%s code0=%s code1=%s%n",
                            satId(obs[idx].sat), elDeg, aboveEl, hasPr,
                            freq0, freq1, hasL0, hasL1, hasP0, hasP1,
                            ObsCode.code2obs(obs[idx].code[0]), ObsCode.code2obs(obs[idx].code[1]));
                }
            }
            System.out.printf("  Summary: passEl=%d hasPhase=%d hasFreq0=%d hasFreq1=%d%n",
                    passEl, hasPhase, hasFreq0, hasFreq1);
        }
    }

    /**
     * Simulate selsat + ddres to count nv.
     */
    private void simulateDdres(Obsd[] obs, int nu, int nr, Nav nav, GTime epochTime) {
        int n = nu + nr;
        int nf = 2;
        double[] rs = new double[n * 6];
        double[] dts = new double[n * 2];
        double[] vare = new double[n];
        int[] svh = new int[n];
        EphModel.satposs(epochTime, obs, n, nav, rs, dts, vare, svh);

        // Get base position
        RinexParser baseParser = new RinexParser();
        baseParser.parseObs(BASE);
        double[] basePos = baseParser.sta.pos.clone();

        // Get rover position (from RINEX header for simulation)
        RinexParser roverParser = new RinexParser();
        roverParser.parseObs(ROVER);
        double[] roverPos = roverParser.sta.pos.clone();

        // Compute azel for all satellites
        double[] azel = new double[Constants.MAXSAT * 2];
        for (int rcv = 0; rcv <= 1; rcv++) {
            int start = rcv == 0 ? 0 : nu;
            int cnt = rcv == 0 ? nu : nr;
            double[] rr = rcv == 0 ? roverPos : basePos;
            double[] pos = new double[3];
            CoordTransform.ecef2pos(rr, pos);
            for (int i = 0; i < cnt; i++) {
                int idx = start + i;
                double[] rsi = {rs[idx*6], rs[idx*6+1], rs[idx*6+2]};
                double[] ei = new double[3];
                double r = RtklibCommon.geodist(rsi, rr, ei);
                if (r <= 0.0) continue;
                double[] ae = new double[2];
                RtklibCommon.satazel(pos, ei, ae);
                azel[idx * 2] = ae[0];
                azel[idx * 2 + 1] = ae[1];
            }
        }

        // Simulate selsat: merge rover and base by sat number
        double elmin = 15.0 * Constants.D2R;
        int[] sat = new int[Constants.MAXSAT];
        int[] iu = new int[Constants.MAXSAT];
        int[] ir = new int[Constants.MAXSAT];
        int ns = 0;
        int i = 0, j = nu;
        while (i < nu && j < nu + nr) {
            if (obs[i].sat < obs[j].sat) {
                i++;
            } else if (obs[i].sat > obs[j].sat) {
                j++;
            } else {
                if (azel[1 + j * 2] >= elmin) {
                    sat[ns] = obs[i].sat;
                    iu[ns] = i;
                    ir[ns] = j;
                    ns++;
                } else {
                    System.out.printf("  %s: below elmin (%.1f°) in selsat%n",
                            satId(obs[i].sat), azel[1 + j * 2] * Constants.R2D);
                }
                i++;
                j++;
            }
        }
        System.out.println("selsat: ns=" + ns);
        if (ns <= 0) {
            System.out.println("selsat returned 0! This would cause relpos to return 0.");
            return;
        }

        // Simulate y (zdres output) - check if obs values are non-zero
        System.out.println("\n--- Checking y (zdres output) for each common sat ---");
        int nv = 0;
        int[] sysMap = {Constants.SYS_GPS | Constants.SYS_SBS, Constants.SYS_GLO, Constants.SYS_GAL,
                Constants.SYS_CMP, Constants.SYS_QZS, Constants.SYS_IRN};

        for (int m = 0; m < 6; m++) {
            for (int f = (Constants.PMODE_STATIC > Constants.PMODE_DGPS ? 0 : nf); f < nf * 2; f++) {
                int frq = f % nf;
                boolean code = f >= nf;

                // Find reference satellite
                int refIdx = -1;
                for (int jj = 0; jj < ns; jj++) {
                    int sysj = SatUtils.satsys(sat[jj], null);
                    if ((sysj & sysMap[m]) == 0) continue;
                    if (sysj == Constants.SYS_SBS) continue;
                    // validobs check: y[f + iu*4] != 0 && y[f + ir*4] != 0
                    // y is computed in zdres. Simulate: check if L/P obs non-zero AND freq > 0
                    double freqRover = SatUtils.sat2freq(sat[jj], obs[iu[jj]].code[frq], nav);
                    double freqBase  = SatUtils.sat2freq(sat[jj], obs[ir[jj]].code[frq], nav);
                    boolean roverHasObs, baseHasObs;
                    if (code) {
                        roverHasObs = obs[iu[jj]].P[frq] != 0.0;
                        baseHasObs  = obs[ir[jj]].P[frq] != 0.0;
                    } else {
                        roverHasObs = obs[iu[jj]].L[frq] != 0.0;
                        baseHasObs  = obs[ir[jj]].L[frq] != 0.0;
                    }
                    boolean yValid = roverHasObs && baseHasObs && freqRover > 0 && freqBase > 0;
                    if (yValid) {
                        if (refIdx < 0) refIdx = jj;
                    }
                }
                if (refIdx < 0) continue;

                // Count DD pairs
                for (int jj = 0; jj < ns; jj++) {
                    if (jj == refIdx) continue;
                    int sysj = SatUtils.satsys(sat[jj], null);
                    if ((sysj & sysMap[m]) == 0) continue;
                    double freqRover = SatUtils.sat2freq(sat[jj], obs[iu[jj]].code[frq], nav);
                    double freqBase  = SatUtils.sat2freq(sat[jj], obs[ir[jj]].code[frq], nav);
                    boolean roverHasObs, baseHasObs;
                    if (code) {
                        roverHasObs = obs[iu[jj]].P[frq] != 0.0;
                        baseHasObs  = obs[ir[jj]].P[frq] != 0.0;
                    } else {
                        roverHasObs = obs[iu[jj]].L[frq] != 0.0;
                        baseHasObs  = obs[ir[jj]].L[frq] != 0.0;
                    }
                    boolean yValid = roverHasObs && baseHasObs && freqRover > 0 && freqBase > 0;
                    if (!yValid) {
                        System.out.printf("  DD skip: ref=%s sat=%s f=%d code=%s roverL=%s baseL=%s roverP=%s baseP=%s freqR=%.4e freqB=%.4e%n",
                                satId(sat[refIdx]), satId(sat[jj]), frq, code,
                                obs[iu[jj]].L[frq] != 0, obs[ir[jj]].L[frq] != 0,
                                obs[iu[jj]].P[frq] != 0, obs[ir[jj]].P[frq] != 0,
                                freqRover, freqBase);
                        continue;
                    }
                    nv++;
                }
            }
        }
        System.out.println("\nddres simulated nv=" + nv + (nv < 4 ? " < 4 => FAIL" : " => OK"));

        // ===== SORTING VERIFICATION EXPERIMENT =====
        System.out.println("\n--- SORTING VERIFICATION (sorted obs => selsat => ddres) ---");

        // Print unsorted satellite order
        StringBuilder roverOrder = new StringBuilder();
        for (int k = 0; k < nu; k++) roverOrder.append(satId(obs[k].sat)).append("(").append(obs[k].sat).append(") ");
        System.out.println("Rover unsorted: " + roverOrder);
        StringBuilder baseOrder = new StringBuilder();
        for (int k = 0; k < nr; k++) {
            int idx = nu + k;
            baseOrder.append(satId(obs[idx].sat)).append("(").append(obs[idx].sat).append(") ");
        }
        System.out.println("Base  unsorted: " + baseOrder);

        // Create sorted copy: rover section and base section each sorted by sat
        Obsd[] sortedObs = new Obsd[n];
        List<Obsd> roverList = new ArrayList<>();
        for (int k = 0; k < nu; k++) roverList.add(obs[k]);
        roverList.sort((a, b) -> Integer.compare(a.sat, b.sat));
        List<Obsd> baseList = new ArrayList<>();
        for (int k = 0; k < nr; k++) baseList.add(obs[nu + k]);
        baseList.sort((a, b) -> Integer.compare(a.sat, b.sat));
        for (int k = 0; k < nu; k++) sortedObs[k] = roverList.get(k);
        for (int k = 0; k < nr; k++) sortedObs[nu + k] = baseList.get(k);

        StringBuilder roverSortedOrder = new StringBuilder();
        for (int k = 0; k < nu; k++) roverSortedOrder.append(satId(sortedObs[k].sat)).append("(").append(sortedObs[k].sat).append(") ");
        System.out.println("Rover sorted:   " + roverSortedOrder);
        StringBuilder baseSortedOrder = new StringBuilder();
        for (int k = 0; k < nr; k++) {
            int idx = nu + k;
            baseSortedOrder.append(satId(sortedObs[idx].sat)).append("(").append(sortedObs[idx].sat).append(") ");
        }
        System.out.println("Base  sorted:   " + baseSortedOrder);

        // Recompute azel for sorted obs
        double[] azelSorted = new double[Constants.MAXSAT * 2];
        for (int rcv = 0; rcv <= 1; rcv++) {
            int start = rcv == 0 ? 0 : nu;
            int cnt = rcv == 0 ? nu : nr;
            double[] rr = rcv == 0 ? roverPos : basePos;
            double[] pos = new double[3];
            CoordTransform.ecef2pos(rr, pos);
            for (int i2 = 0; i2 < cnt; i2++) {
                int idx = start + i2;
                double[] rsi = {rs[idx*6], rs[idx*6+1], rs[idx*6+2]};
                double[] ei = new double[3];
                double r = RtklibCommon.geodist(rsi, rr, ei);
                if (r <= 0.0) continue;
                double[] ae = new double[2];
                RtklibCommon.satazel(pos, ei, ae);
                azelSorted[idx * 2] = ae[0];
                azelSorted[idx * 2 + 1] = ae[1];
            }
        }

        // Re-run selsat on sorted obs
        int[] satSorted = new int[Constants.MAXSAT];
        int[] iuSorted = new int[Constants.MAXSAT];
        int[] irSorted = new int[Constants.MAXSAT];
        int nsSorted = 0;
        int is = 0, js = nu;
        while (is < nu && js < nu + nr) {
            if (sortedObs[is].sat < sortedObs[js].sat) {
                is++;
            } else if (sortedObs[is].sat > sortedObs[js].sat) {
                js++;
            } else {
                if (azelSorted[1 + js * 2] >= elmin) {
                    satSorted[nsSorted] = sortedObs[is].sat;
                    iuSorted[nsSorted] = is;
                    irSorted[nsSorted] = js;
                    nsSorted++;
                }
                is++;
                js++;
            }
        }
        System.out.println("selsat (sorted): ns=" + nsSorted + "  [vs unsorted ns=" + ns + "]");
        if (nsSorted > 0) {
            StringBuilder matchedSats = new StringBuilder();
            for (int k = 0; k < nsSorted; k++) {
                matchedSats.append(satId(satSorted[k])).append(" ");
            }
            System.out.println("  Matched sats: " + matchedSats);
        }

        // Re-run ddres nv count on sorted obs
        int nvSorted = 0;
        for (int m = 0; m < 6; m++) {
            for (int f = (Constants.PMODE_STATIC > Constants.PMODE_DGPS ? 0 : nf); f < nf * 2; f++) {
                int frq = f % nf;
                boolean code = f >= nf;

                int refIdx = -1;
                for (int jj = 0; jj < nsSorted; jj++) {
                    int sysj = SatUtils.satsys(satSorted[jj], null);
                    if ((sysj & sysMap[m]) == 0) continue;
                    if (sysj == Constants.SYS_SBS) continue;
                    double freqRover = SatUtils.sat2freq(satSorted[jj], sortedObs[iuSorted[jj]].code[frq], nav);
                    double freqBase  = SatUtils.sat2freq(satSorted[jj], sortedObs[irSorted[jj]].code[frq], nav);
                    boolean roverHasObs, baseHasObs;
                    if (code) {
                        roverHasObs = sortedObs[iuSorted[jj]].P[frq] != 0.0;
                        baseHasObs  = sortedObs[irSorted[jj]].P[frq] != 0.0;
                    } else {
                        roverHasObs = sortedObs[iuSorted[jj]].L[frq] != 0.0;
                        baseHasObs  = sortedObs[irSorted[jj]].L[frq] != 0.0;
                    }
                    boolean yValid = roverHasObs && baseHasObs && freqRover > 0 && freqBase > 0;
                    if (yValid) {
                        if (refIdx < 0) refIdx = jj;
                    }
                }
                if (refIdx < 0) continue;

                for (int jj = 0; jj < nsSorted; jj++) {
                    if (jj == refIdx) continue;
                    int sysj = SatUtils.satsys(satSorted[jj], null);
                    if ((sysj & sysMap[m]) == 0) continue;
                    double freqRover = SatUtils.sat2freq(satSorted[jj], sortedObs[iuSorted[jj]].code[frq], nav);
                    double freqBase  = SatUtils.sat2freq(satSorted[jj], sortedObs[irSorted[jj]].code[frq], nav);
                    boolean roverHasObs, baseHasObs;
                    if (code) {
                        roverHasObs = sortedObs[iuSorted[jj]].P[frq] != 0.0;
                        baseHasObs  = sortedObs[irSorted[jj]].P[frq] != 0.0;
                    } else {
                        roverHasObs = sortedObs[iuSorted[jj]].L[frq] != 0.0;
                        baseHasObs  = sortedObs[irSorted[jj]].L[frq] != 0.0;
                    }
                    boolean yValid = roverHasObs && baseHasObs && freqRover > 0 && freqBase > 0;
                    if (yValid) {
                        nvSorted++;
                    }
                }
            }
        }
        System.out.println("ddres simulated nv (sorted)=" + nvSorted +
                (nvSorted < 4 ? " < 4 => FAIL" : " => OK") +
                "  [vs unsorted nv=" + nv + "]");
        System.out.println("\n>>> CONCLUSION: sorting obs array before selsat changes " +
                "ns=" + ns + " -> " + nsSorted + ", nv=" + nv + " -> " + nvSorted + " <<<");
    }

    private double[] computeSppPos(Obsd[] obs, int nu, int nr, Nav nav, GTime epochTime) {
        PrcOpt sppOpt = new PrcOpt();
        sppOpt.mode = Constants.PMODE_SINGLE;
        sppOpt.navsys = Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_QZS;
        sppOpt.ionoopt = Constants.IONOOPT_BRDC;
        sppOpt.tropopt = Constants.TROPOPT_SAAS;
        sppOpt.nf = 2;
        sppOpt.elmin = 15.0 * Constants.D2R;

        Sol sol = new Sol();
        Obsd[] baseObs = new Obsd[nr];
        System.arraycopy(obs, nu, baseObs, 0, nr);
        int result = PntPos.pntpos(baseObs, nr, nav, sppOpt, sol, null, null);
        if (result == 1 && sol.stat != Constants.SOLQ_NONE) {
            return sol.rr.clone();
        }
        return null;
    }
}
