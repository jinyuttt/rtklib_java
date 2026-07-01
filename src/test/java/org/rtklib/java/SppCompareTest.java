package org.rtklib.java;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.ephemeris.EphModel;
import org.rtklib.java.pntpos.SppCore;
import org.rtklib.java.rinex.RinexParser;
import org.rtklib.java.time.TimeSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("SPP 对比测试（Java vs RTKLIB C）")
public class SppCompareTest {

    private static final Logger log = LoggerFactory.getLogger(SppCompareTest.class);

    private static final String DATA_DIR = "D:\\rtklib\\rtklib_java\\RTKLIB_EX_2.5.0";
    private static final String OBS_FILE = DATA_DIR + "\\1.obs";
    private static final String NAV_FILE = DATA_DIR + "\\1.nav";
    private static final String C_REF_FILE = DATA_DIR + "\\spp_bds.pos";
    private static final String JAVA_RESULT_FILE = DATA_DIR + "\\spp_java_result.pos";

    private static List<ObsEpoch> obsEpochs;
    private static Nav nav;

    static class ObsEpoch {
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
    static void loadRinexData() {
        log.info("===== 加载 RINEX 数据 =====");

        RinexParser parser = new RinexParser();
        assertTrue(parser.parseObs(OBS_FILE), "应成功解析 OBS 文件");
        assertTrue(parser.parseNav(NAV_FILE), "应成功解析 NAV 文件");

        nav = parser.nav;
        obsEpochs = groupObsByEpoch(parser.obs.data, parser.obs.n);

        log.info("历元数: {}, 导航星历数: eph={}, geph={}, seph={}",
                obsEpochs.size(), nav.n, nav.ng, nav.ns);

        for (int i = 0; i < Math.min(3, obsEpochs.size()); i++) {
            double[] ymdhms = TimeSystem.time2ymdhms(obsEpochs.get(i).time);
            log.info(String.format("历元 #%d: %04d-%02d-%02d %02d:%02d:%06.3f nsat=%d",
                    i + 1, (int) ymdhms[0], (int) ymdhms[1], (int) ymdhms[2],
                    (int) ymdhms[3], (int) ymdhms[4], ymdhms[5], obsEpochs.get(i).n));
        }
    }

    private static List<ObsEpoch> groupObsByEpoch(Obsd[] data, int n) {
        List<ObsEpoch> groups = new ArrayList<>();
        if (n == 0) return groups;

        List<Obsd> current = new ArrayList<>();
        GTime currentTime = data[0].time;

        for (int i = 0; i < n; i++) {
            if (!data[i].time.equals(currentTime)) {
                Obsd[] arr = current.toArray(new Obsd[0]);
                groups.add(new ObsEpoch(currentTime, arr, arr.length));
                current = new ArrayList<>();
                currentTime = data[i].time;
            }
            current.add(data[i]);
        }
        if (!current.isEmpty()) {
            Obsd[] arr = current.toArray(new Obsd[0]);
            groups.add(new ObsEpoch(currentTime, arr, arr.length));
        }
        return groups;
    }

    @Test
    @DisplayName("SPP 全历元定位 + 写入 .pos 文件")
    void testSppAllEpochs() throws IOException {
        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_SINGLE;
        opt.nf = 2;
        opt.navsys = Constants.SYS_CMP;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;

        Ssat[] ssat = new Ssat[Constants.MAXSAT];
        for (int i = 0; i < Constants.MAXSAT; i++) ssat[i] = new Ssat();

        Sol sol = new Sol();

        BufferedWriter writer = new BufferedWriter(new FileWriter(JAVA_RESULT_FILE));
        writer.write("% program   : rtklib_java SPP\n");
        writer.write("% inp file  : 1.obs\n");
        writer.write("% inp file  : 1.nav\n");
        writer.write("%\n");
        writer.write("% (lat/lon/height=WGS84/ellipsoidal,Q=1:fix,2:float,3:sbas,4:dgps,5:single,6:ppp,ns=# of satellites)\n");
        writer.write("%  GPST          latitude(deg) longitude(deg)  height(m)   Q  ns   sdx(m)   sde(m)   sdu(m)  sdne(m)  sdeu(m)  sdun(m) age(s)  ratio\n");

        int successCount = 0;
        int failCount = 0;

        for (int ep = 0; ep < obsEpochs.size(); ep++) {
            ObsEpoch epoch = obsEpochs.get(ep);
            int n = epoch.n;

            double[] rs = new double[n * 6];
            double[] dts = new double[n * 2];
            double[] vare = new double[n];
            int[] svh = new int[n];

            EphModel.satposs(epoch.time, epoch.obsd, n, nav, rs, dts, vare, svh, opt.sateph);

            int[] vsat = new int[n];
            double[] azel = new double[n * 2];
            double[] resp = new double[n];
            String[] msg = new String[1];

            int result = SppCore.estpos(epoch.obsd, n, rs, dts, vare, svh, nav, opt,
                    ssat, sol, azel, vsat, resp, msg);

            if (result == 1) {
                successCount++;
                double[] llh = new double[3];
                CoordTransform.ecef2pos(sol.rr, llh);

                int[] week = new int[0];
                double tow = TimeSystem.time2gpst(epoch.time, week);

                writer.write(String.format("%4d %9.3f  %12.9f %13.9f %10.4f  %2d %3d\n",
                        week.length > 0 ? week[0] : 0, tow,
                        llh[0] * Constants.R2D, llh[1] * Constants.R2D, llh[2],
                        sol.stat, sol.ns));
            } else {
                failCount++;
            }
        }

        writer.close();

        log.info("===== SPP 定位结果 =====");
        log.info("总历元: {}, 成功: {}, 失败: {}", obsEpochs.size(), successCount, failCount);
        log.info("Java 结果已写入: {}", JAVA_RESULT_FILE);
        assertTrue(successCount > 0, "至少应有一个历元定位成功");
    }

    @Test
    @DisplayName("SPP Java vs RTKLIB C 逐历元对比")
    void testSppCompareWithRtklibC() throws IOException {
        // 先运行 SPP 获取 Java 结果
        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_SINGLE;
        opt.nf = 2;
        opt.navsys = Constants.SYS_CMP;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;

        Ssat[] ssat = new Ssat[Constants.MAXSAT];
        for (int i = 0; i < Constants.MAXSAT; i++) ssat[i] = new Ssat();

        Sol sol = new Sol();
        List<JavaResult> javaResults = new ArrayList<>();

        for (int ep = 0; ep < obsEpochs.size(); ep++) {
            ObsEpoch epoch = obsEpochs.get(ep);
            int n = epoch.n;

            double[] rs = new double[n * 6];
            double[] dts = new double[n * 2];
            double[] vare = new double[n];
            int[] svh = new int[n];

            EphModel.satposs(epoch.time, epoch.obsd, n, nav, rs, dts, vare, svh, opt.sateph);

            int[] vsat = new int[n];
            double[] azel = new double[n * 2];
            double[] resp = new double[n];
            String[] msg = new String[1];

            int result = SppCore.estpos(epoch.obsd, n, rs, dts, vare, svh, nav, opt,
                    ssat, sol, azel, vsat, resp, msg);

            if (result == 1) {
                double[] llh = new double[3];
                CoordTransform.ecef2pos(sol.rr, llh);
                javaResults.add(new JavaResult(epoch.time, llh, sol.ns, sol.stat));
            }
        }

        log.info("Java SPP: {} 个解", javaResults.size());

        // 读取 C 参考结果
        List<CResult> cResults = readSppBdsPos(C_REF_FILE);
        log.info("RTKLIB C SPP: {} 个解", cResults.size());

        // 对比
        Map<String, CResult> cMap = new LinkedHashMap<>();
        for (CResult cr : cResults) {
            cMap.put(cr.timeKey, cr);
        }

        int compared = 0;
        int unmatched = 0;
        double sumLatDiff = 0, sumLonDiff = 0, sumHDiff = 0;
        double maxLatDiff = 0, maxLonDiff = 0, maxHDiff = 0;
        double sum3D = 0, max3D = 0;
        int nsDiffCount = 0;

        log.info("");
        log.info(String.format("%-22s  %14s %14s %10s  %3s %3s  |  dLat(deg)  dLon(deg)  dH(m)   3D(m)",
                "Time", "Lat_java", "Lon_java", "H_java", "nsJ", "nsC"));
        log.info("-".repeat(130));

        for (JavaResult jr : javaResults) {
            String timeKey = formatTimeKey(jr.time);
            CResult cr = cMap.get(timeKey);

            if (cr == null) {
                unmatched++;
                if (unmatched <= 5) {
                    log.info("  Java 解未在 C 结果中找到: {}", timeKey);
                }
                continue;
            }

            compared++;
            double latDiff = Math.abs(jr.lat - cr.lat);
            double lonDiff = Math.abs(jr.lon - cr.lon);
            double hDiff = Math.abs(jr.h - cr.h);
            double d3 = Math.sqrt(
                    Math.pow(latDiff * 111000, 2) +
                    Math.pow(lonDiff * 111000 * Math.cos(Math.toRadians(cr.lat)), 2) +
                    Math.pow(hDiff, 2));

            sumLatDiff += latDiff;
            sumLonDiff += lonDiff;
            sumHDiff += hDiff;
            sum3D += d3;
            maxLatDiff = Math.max(maxLatDiff, latDiff);
            maxLonDiff = Math.max(maxLonDiff, lonDiff);
            maxHDiff = Math.max(maxHDiff, hDiff);
            max3D = Math.max(max3D, d3);

            if (jr.ns != cr.ns) nsDiffCount++;

            if (compared <= 10 || d3 > 0.5 || jr.ns != cr.ns) {
                log.info(String.format("%-22s  %14.9f %14.9f %10.4f  %3d %3d  |  %9.6f  %9.6f  %8.3f %8.3f%s",
                        timeKey, jr.lat, jr.lon, jr.h, jr.ns, cr.ns,
                        latDiff, lonDiff, hDiff, d3,
                        d3 > 1.0 ? " ***" : ""));
            }
        }

        log.info("-".repeat(130));
        log.info("===== 对比汇总 =====");
        log.info("C 历元数: {}, Java 解数: {}, 匹配: {}, 未匹配: {}",
                cResults.size(), javaResults.size(), compared, unmatched);
        if (compared > 0) {
            log.info(String.format("平均纬度偏差: %.9f deg (%.3f m), 最大: %.9f deg (%.3f m)",
                    sumLatDiff / compared, sumLatDiff / compared * 111000,
                    maxLatDiff, maxLatDiff * 111000));
            log.info(String.format("平均经度偏差: %.9f deg (%.3f m), 最大: %.9f deg (%.3f m)",
                    sumLonDiff / compared, sumLonDiff / compared * 111000 * Math.cos(Math.toRadians(29.19)),
                    maxLonDiff, maxLonDiff * 111000 * Math.cos(Math.toRadians(29.19))));
            log.info(String.format("平均高程偏差: %.3f m, 最大: %.3f m",
                    sumHDiff / compared, maxHDiff));
            log.info(String.format("平均 3D 偏差: %.3f m, 最大: %.3f m",
                    sum3D / compared, max3D));
            log.info(String.format("ns 不一致历元: %d/%d", nsDiffCount, compared));
        }

        assertTrue(compared > 0, "至少应有一个匹配的历元");
        // SPP 精度在米级，3D 偏差应 < 5m
        assertTrue(sum3D / compared < 5.0,
                String.format("平均 3D 偏差 %.3f m 应 < 5 m", sum3D / compared));
    }

    private List<CResult> readSppBdsPos(String filePath) throws IOException {
        List<CResult> results = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("%") || line.trim().isEmpty()) continue;

                String[] parts = line.trim().split("\\s+");
                if (parts.length < 8) continue;

                try {
                    int week = Integer.parseInt(parts[0]);
                    double tow = Double.parseDouble(parts[1]);
                    double lat = Double.parseDouble(parts[2]);
                    double lon = Double.parseDouble(parts[3]);
                    double h = Double.parseDouble(parts[4]);
                    int q = Integer.parseInt(parts[5]);
                    int ns = Integer.parseInt(parts[6]);

                    GTime time = TimeSystem.gpst2time(week, tow);
                    String timeKey = formatTimeKey(time);
                    results.add(new CResult(timeKey, lat, lon, h, ns, q));
                } catch (NumberFormatException e) {
                    continue;
                }
            }
        }
        return results;
    }

    private String formatTimeKey(GTime time) {
        int[] week = new int[1];
        double tow = TimeSystem.time2gpst(time, week);
        return String.format("%04d %09.3f", week[0], tow);
    }

    static class JavaResult {
        final String timeKey;
        final double lat, lon, h;
        final int ns, stat;

        JavaResult(GTime time, double[] llh, int ns, int stat) {
            this.timeKey = formatTimeKey(time);
            this.lat = llh[0] * Constants.R2D;
            this.lon = llh[1] * Constants.R2D;
            this.h = llh[2];
            this.ns = ns;
            this.stat = stat;
        }
    }

    static class CResult {
        final String timeKey;
        final double lat, lon, h;
        final int ns, q;

        CResult(String timeKey, double lat, double lon, double h, int ns, int q) {
            this.timeKey = timeKey;
            this.lat = lat;
            this.lon = lon;
            this.h = h;
            this.ns = ns;
            this.q = q;
        }
    }
}