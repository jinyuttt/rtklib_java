package org.rtklib.java.test.gnssvalidation;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.rtklib.java.TestDataConfig;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.PrcOpt;
import org.rtklib.java.data.SolData;
import org.rtklib.java.data.SolOpt;
import org.rtklib.java.data.SolutionStatus;
import org.rtklib.java.rinex.RinexParser;
import org.rtklib.java.rinex.RinexRtkProcessor;
import org.rtklib.java.rtkpos.RtkProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

public class ShortBaselineOptSweepTest {

    private static final Logger log = LoggerFactory.getLogger(ShortBaselineOptSweepTest.class);

    private static final String ROOT = TestDataConfig.getTestDataDir()
            + File.separator + "gnss-validation" + File.separator + "rtk";
    private static final String SHORT_DIR = ROOT + File.separator + "short-baseline" + File.separator + "hkws-hkss";
    private static final String NAV_FILE = ROOT + File.separator + "brdc" + File.separator
            + "BRDC00IGS_R_20240600000_01D_MN.rnx";
    private static final String ATX_FILE = TestDataConfig.getTestDataDir()
            + File.separator + "gnss-validation" + File.separator + "table" + File.separator + "igs14.atx";

    private static String roverObs;
    private static String baseObs;
    private static double[] basePos;
    private static boolean available;

    @BeforeAll
    static void setup() {
        roverObs = SHORT_DIR + File.separator + "HKWS00HKG_R_20240600000_01D_30S_MO.rnx";
        baseObs = SHORT_DIR + File.separator + "HKSS00HKG_R_20240600000_01D_30S_MO.rnx";
        available = new File(roverObs).exists() && new File(baseObs).exists() && new File(NAV_FILE).exists();
        if (!available) {
            log.info("短基线数据不可用，跳过扫描");
            return;
        }
        try {
            RinexParser parser = new RinexParser();
            if (parser.parseObs(baseObs)) {
                basePos = parser.sta.pos.clone();
            }
        } catch (Exception e) {
            log.warn("读取基准站坐标失败: {}", e.getMessage());
        }
    }

    private static PrcOpt createOpt() {
        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_STATIC;
        opt.soltype = Constants.SOLTYPE_FORWARD;
        opt.nf = 2;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_EST;
        opt.modear = Constants.ARMODE_FIXHOLD;
        opt.refpos = Constants.POSOPT_SINGLE;
        opt.anttype[0] = "*";
        opt.anttype[1] = "*";
        return opt;
    }

    private static SolOpt createSolOpt() {
        SolOpt solOpt = new SolOpt();
        solOpt.solstatic = 0;
        return solOpt;
    }

    private static void run(String label, RtkConfig cfg, PrcOpt opt) {
        RinexRtkProcessor rtk = new RinexRtkProcessor(opt, createSolOpt(), cfg, null, null);
        if (new File(ATX_FILE).exists()) {
            rtk.setAtxPath(ATX_FILE);
        }
        long t0 = System.currentTimeMillis();
        RtkProcessor.RtkResult result = rtk.process(roverObs, baseObs, NAV_FILE);
        long dt = System.currentTimeMillis() - t0;

        List<SolData> sols = result.solutions;
        int total = sols.size();
        int fix = 0, flt = 0, sgl = 0, none = 0;
        List<double[]> fixEcef = new ArrayList<>();
        for (SolData s : sols) {
            if (s.status == SolutionStatus.FIX) {
                fix++;
                org.rtklib.java.data.Position p = s.getPosition(org.rtklib.java.data.CoordType.ECEF);
                if (p != null) fixEcef.add(new double[]{p.v1, p.v2, p.v3});
            } else if (s.status == SolutionStatus.FLOAT) {
                flt++;
            } else if (s.status == SolutionStatus.SINGLE) {
                sgl++;
            } else {
                none++;
            }
        }

        double fixRate = total > 0 ? (double) fix / total * 100.0 : 0.0;
        double rmsE = 0, rmsN = 0, rmsU = 0;
        double baseline = 0;
        if (!fixEcef.isEmpty()) {
            double mx = 0, my = 0, mz = 0;
            for (double[] p : fixEcef) { mx += p[0]; my += p[1]; mz += p[2]; }
            mx /= fixEcef.size(); my /= fixEcef.size(); mz /= fixEcef.size();
            double[] meanPos = new double[3];
            CoordTransform.ecef2pos(new double[]{mx, my, mz}, meanPos);
            double se = 0, sn = 0, su = 0;
            for (double[] p : fixEcef) {
                double[] dr = {p[0] - mx, p[1] - my, p[2] - mz};
                double[] enu = new double[3];
                CoordTransform.ecef2enu(meanPos, dr, enu);
                se += enu[0] * enu[0];
                sn += enu[1] * enu[1];
                su += enu[2] * enu[2];
            }
            rmsE = Math.sqrt(se / fixEcef.size());
            rmsN = Math.sqrt(sn / fixEcef.size());
            rmsU = Math.sqrt(su / fixEcef.size());
            if (basePos != null) {
                double dx = mx - basePos[0], dy = my - basePos[1], dz = mz - basePos[2];
                baseline = Math.sqrt(dx * dx + dy * dy + dz * dz);
            }
        }

        log.info(String.format("SWEEP %-28s fix=%6.2f%% (%4d/%4d) float=%4d single=%4d none=%4d  fixRMS=%.4f/%.4f/%.4f m  base=%.1f m  %d ms",
                label, fixRate, fix, total, flt, sgl, none, rmsE, rmsN, rmsU, baseline, dt));
    }

    @Test
    void sweep() {
        if (!available) {
            return;
        }

        run("0-pureC", new RtkConfig(), createOpt());

        run("0-baseline", base(), createOpt());

        RtkConfig c;
        c = base(); c.rtk.enablePartialAR = true;
        run("1-partialAR", c, createOpt());

        c = base(); c.rtk.enableCascadeAR = true;
        run("2-cascadeAR", c, createOpt());

        c = base(); c.rtk.enableBootstrapping = true;
        run("3-bootstrapping", c, createOpt());

        c = base(); c.rtk.enableIggiii = true;
        run("4-iggiii", c, createOpt());

        c = base(); c.rtk.enableResidualEdit = true;
        run("5-residualEdit", c, createOpt());

        c = base(); c.rtk.enableAmbAnchor = true;
        run("6-ambAnchor", c, createOpt());

        c = base();
        c.rtk.enablePartialAR = true;
        c.rtk.enableCascadeAR = true;
        c.rtk.enableBootstrapping = true;
        c.rtk.enableIggiii = true;
        c.rtk.enableAmbAnchor = true;
        run("7-combo", c, createOpt());

        PrcOpt optLow = createOpt();
        optLow.thresar[0] = 2.0;
        run("8-thresar=2.0", base(), optLow);

        PrcOpt optNiter = createOpt();
        optNiter.niter = 2;
        run("9-niter=2", base(), optNiter);

        c = base(); c.rtk.enablePartialAR = true;
        PrcOpt optP = createOpt();
        optP.thresar[0] = 2.0;
        run("10-partialAR+thresar2", c, optP);
    }

    private static RtkConfig base() {
        RtkConfig cfg = new RtkConfig();
        cfg.rtk.enableSnrMedian = true;
        return cfg;
    }
}