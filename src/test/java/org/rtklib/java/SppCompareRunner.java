package org.rtklib.java;

import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.ephemeris.EphModel;
import org.rtklib.java.pntpos.SppCore;
import org.rtklib.java.rinex.RinexParser;
import org.rtklib.java.time.TimeSystem;

import java.io.*;
import java.util.*;

public class SppCompareRunner {

    private static final String DATA_DIR = "D:\\rtklib\\rtklib_java\\RTKLIB_EX_2.5.0";
    private static final String OBS_FILE = DATA_DIR + "\\1.obs";
    private static final String NAV_FILE = DATA_DIR + "\\1.nav";
    private static final String C_REF_FILE = DATA_DIR + "\\spp_bds.pos";
    private static final String JAVA_RESULT_FILE = DATA_DIR + "\\spp_java_result.pos";

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

    public static void main(String[] args) throws Exception {
        System.out.println("===== 加载 RINEX 数据 =====");

        RinexParser parser = new RinexParser();
        if (!parser.parseObs(OBS_FILE)) {
            System.err.println("解析 OBS 失败: " + OBS_FILE);
            System.exit(1);
        }
        if (!parser.parseNav(NAV_FILE)) {
            System.err.println("解析 NAV 失败: " + NAV_FILE);
            System.exit(1);
        }

        Nav nav = parser.nav;
        List<ObsEpoch> obsEpochs = groupObsByEpoch(parser.obs.data, parser.obs.n);

        System.out.println("历元数: " + obsEpochs.size() + ", 导航星历: eph=" + nav.n);

        for (int i = 0; i < Math.min(3, obsEpochs.size()); i++) {
            double[] ymdhms = TimeSystem.time2ymdhms(obsEpochs.get(i).time);
            System.out.printf("历元 #%d: %04d-%02d-%02d %02d:%02d:%06.3f nsat=%d%n",
                    i + 1, (int) ymdhms[0], (int) ymdhms[1], (int) ymdhms[2],
                    (int) ymdhms[3], (int) ymdhms[4], ymdhms[5], obsEpochs.get(i).n);
        }

        // 配置: BDS-only, 与 C spp_bds.pos 一致
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
        int failCount = 0;

        System.out.println("\n===== 运行 SPP 定位 =====");

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
            } else {
                failCount++;
                if (failCount <= 5) {
                    System.out.println("  历元 " + ep + " 失败: " + (msg[0] != null ? msg[0] : "unknown"));
                }
            }
        }

        System.out.println("Java SPP: 成功=" + javaResults.size() + ", 失败=" + failCount);

        // 写入 Java 结果
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(JAVA_RESULT_FILE))) {
            writer.write("% program   : rtklib_java SPP\n");
            writer.write("% inp file  : 1.obs\n");
            writer.write("% inp file  : 1.nav\n");
            writer.write("%\n");
            writer.write("% (lat/lon/height=WGS84/ellipsoidal,Q=1:fix,2:float,3:sbas,4:dgps,5:single,6:ppp,ns=# of satellites)\n");
            writer.write("%  GPST          latitude(deg) longitude(deg)  height(m)   Q  ns\n");
            for (JavaResult jr : javaResults) {
                writer.write(String.format("%s  %12.9f %13.9f %10.4f  %2d %3d%n",
                        jr.timeKey, jr.lat, jr.lon, jr.h, jr.stat, jr.ns));
            }
        }
        System.out.println("Java 结果已写入: " + JAVA_RESULT_FILE);

        // 读取 C 参考结果
        List<CResult> cResults = readSppBdsPos(C_REF_FILE);
        System.out.println("RTKLIB C SPP: " + cResults.size() + " 个解");

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
        List<String> largeErrLines = new ArrayList<>();

        System.out.println();
        System.out.printf("%-22s  %14s %14s %10s  %3s %3s  |  dLat(deg)  dLon(deg)  dH(m)   3D(m)%n",
                "Time", "Lat_java", "Lon_java", "H_java", "nsJ", "nsC");
        System.out.println("-".repeat(130));

        for (JavaResult jr : javaResults) {
            String timeKey = jr.timeKey;
            CResult cr = cMap.get(timeKey);

            if (cr == null) {
                unmatched++;
                if (unmatched <= 5) {
                    System.out.println("  Java 解未在 C 结果中找到: " + timeKey);
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
                String line = String.format("%-22s  %14.9f %14.9f %10.4f  %3d %3d  |  %9.6f  %9.6f  %8.3f %8.3f%s",
                        timeKey, jr.lat, jr.lon, jr.h, jr.ns, cr.ns,
                        latDiff, lonDiff, hDiff, d3,
                        d3 > 1.0 ? " ***" : "");
                System.out.println(line);
                if (d3 > 1.0) largeErrLines.add(line);
            }
        }

        System.out.println("-".repeat(130));
        System.out.println("\n===== 对比汇总 =====");
        System.out.println("C 历元数: " + cResults.size() + ", Java 解数: " + javaResults.size() + ", 匹配: " + compared + ", 未匹配: " + unmatched);
        if (compared > 0) {
            System.out.printf("平均纬度偏差: %.9f deg (%.3f m), 最大: %.9f deg (%.3f m)%n",
                    sumLatDiff / compared, sumLatDiff / compared * 111000,
                    maxLatDiff, maxLatDiff * 111000);
            System.out.printf("平均经度偏差: %.9f deg (%.3f m), 最大: %.9f deg (%.3f m)%n",
                    sumLonDiff / compared, sumLonDiff / compared * 111000 * Math.cos(Math.toRadians(29.19)),
                    maxLonDiff, maxLonDiff * 111000 * Math.cos(Math.toRadians(29.19)));
            System.out.printf("平均高程偏差: %.3f m, 最大: %.3f m%n",
                    sumHDiff / compared, maxHDiff);
            System.out.printf("平均 3D 偏差: %.3f m, 最大: %.3f m%n",
                    sum3D / compared, max3D);
            System.out.println("ns 不一致历元: " + nsDiffCount + "/" + compared);
        }

        if (!largeErrLines.isEmpty()) {
            System.out.println("\n===== 大偏差历元 (3D > 1m) =====");
            for (String line : largeErrLines) {
                System.out.println(line);
            }
        }

        // 判断
        if (compared == 0) {
            System.err.println("ERROR: 没有匹配的历元!");
            System.exit(1);
        }
        double avg3D = sum3D / compared;
        if (avg3D > 5.0) {
            System.err.printf("ERROR: 平均 3D 偏差 %.3f m > 5 m, SPP 结果异常!%n", avg3D);
            System.exit(1);
        }
        System.out.printf("%nOK: 平均 3D 偏差 %.3f m < 5 m, SPP 结果正常%n", avg3D);
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

    private static List<CResult> readSppBdsPos(String filePath) throws IOException {
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

    private static String formatTimeKey(GTime time) {
        int[] week = new int[1];
        double tow = TimeSystem.time2gpst(time, week);
        return String.format("%4d %9.3f", week[0], tow);
    }
}