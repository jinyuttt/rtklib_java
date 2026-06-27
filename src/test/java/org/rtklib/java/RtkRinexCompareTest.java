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
import org.rtklib.java.rtkpos.RtkCore;
import org.rtklib.java.time.TimeSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("4. RTK RINEX 对比测试（Java vs RTKLIB C）")
public class RtkRinexCompareTest {

    private static final Logger log = LoggerFactory.getLogger(RtkRinexCompareTest.class);

    private static final String DATA_DIR = "D:\\code\\rtklib_java\\RTKLIB_EX_2.5.0";
    private static final String ROVER_OBS = DATA_DIR + "\\rover_0608.obs";
    private static final String BASE_OBS = DATA_DIR + "\\base_0608.obs";
    private static final String ROVER_NAV = DATA_DIR + "\\rover_0608.nav";
    private static final String BASE_NAV = DATA_DIR + "\\base_0608.nav";
    private static final String RTKLIB_C_RESULT = DATA_DIR + "\\rtk_c_ref.pos";
    private static final String JAVA_RESULT = DATA_DIR + "\\rtk_java_result.pos";

    private static List<ObsEpoch> roverEpochs;
    private static List<ObsEpoch> baseEpochs;
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

        RinexParser roverParser = new RinexParser();
        assertTrue(roverParser.parseObs(ROVER_OBS), "应成功解析 rover OBS");
        assertTrue(roverParser.parseNav(ROVER_NAV), "应成功解析 rover NAV");

        RinexParser baseParser = new RinexParser();
        assertTrue(baseParser.parseObs(BASE_OBS), "应成功解析 base OBS");
        assertTrue(baseParser.parseNav(BASE_NAV), "应成功解析 base NAV");

        nav = roverParser.nav;
        mergeNav(nav, baseParser.nav);

        roverEpochs = groupObsByEpoch(roverParser.obs.data, roverParser.obs.n, 1);
        baseEpochs = groupObsByEpoch(baseParser.obs.data, baseParser.obs.n, 2);

        log.info("Rover 历元数: {}, Base 历元数: {}", roverEpochs.size(), baseEpochs.size());

        for (int i = 0; i < Math.min(3, roverEpochs.size()); i++) {
            double[] ymdhms = TimeSystem.time2ymdhms(roverEpochs.get(i).time);
            log.info(String.format("Rover #%d: %04d-%02d-%02d %02d:%02d:%06.3f nsat=%d",
                    i + 1, (int)ymdhms[0], (int)ymdhms[1], (int)ymdhms[2],
                    (int)ymdhms[3], (int)ymdhms[4], ymdhms[5], roverEpochs.get(i).n));
        }
        for (int i = 0; i < Math.min(3, baseEpochs.size()); i++) {
            double[] ymdhms = TimeSystem.time2ymdhms(baseEpochs.get(i).time);
            log.info(String.format("Base  #%d: %04d-%02d-%02d %02d:%02d:%06.3f nsat=%d",
                    i + 1, (int)ymdhms[0], (int)ymdhms[1], (int)ymdhms[2],
                    (int)ymdhms[3], (int)ymdhms[4], ymdhms[5], baseEpochs.get(i).n));
        }
    }

    private static void mergeNav(Nav dst, Nav src) {
        for (int i = 0; i < src.n; i++) {
            if (dst.n < dst.nmax) {
                dst.eph[dst.n++] = src.eph[i];
            }
        }
        for (int i = 0; i < src.ng; i++) {
            if (dst.ng < dst.ngmax) {
                dst.geph[dst.ng++] = src.geph[i];
            }
        }
        for (int i = 0; i < src.ns; i++) {
            if (dst.ns < dst.nsmax) {
                dst.seph[dst.ns++] = src.seph[i];
            }
        }
    }

    private static List<ObsEpoch> groupObsByEpoch(Obsd[] data, int n, int rcv) {
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
            data[i].rcv = rcv;
            current.add(data[i]);
        }
        if (!current.isEmpty()) {
            Obsd[] arr = current.toArray(new Obsd[0]);
            groups.add(new ObsEpoch(currentTime, arr, arr.length));
        }
        return groups;
    }

    private static double[] computeBasePosition() {
        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_SINGLE;
        opt.nf = 2;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL | Constants.SYS_CMP;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;

        double[] sumPos = new double[3];
        int count = 0;
        Ssat[] ssat = new Ssat[Constants.MAXSAT];
        for (int i = 0; i < Constants.MAXSAT; i++) ssat[i] = new Ssat();

        for (int ep = 0; ep < Math.min(10, baseEpochs.size()); ep++) {
            ObsEpoch epoch = baseEpochs.get(ep);
            Obsd[] tmpObs = new Obsd[epoch.n];
            for (int i = 0; i < epoch.n; i++) {
                tmpObs[i] = new Obsd(epoch.obsd[i]);
                tmpObs[i].rcv = 1;
            }

            double[] rs = new double[epoch.n * 6];
            double[] dts = new double[epoch.n * 2];
            double[] vare = new double[epoch.n];
            int[] svh = new int[epoch.n];
            EphModel.satposs(epoch.time, tmpObs, epoch.n, nav, rs, dts, vare, svh);

            Sol sol = new Sol();
            double[] azel = new double[epoch.n * 2];
            int[] vsat = new int[epoch.n];
            double[] resp = new double[epoch.n];
            String[] msg = new String[1];

            if (SppCore.estpos(tmpObs, epoch.n, rs, dts, vare, svh, nav, opt,
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
    @DisplayName("Java RTK vs RTKLIB C 结果对比")
    void testRtkCompareWithRtklib() throws IOException {
        log.info("===== 计算基准站近似坐标 =====");
        double[] basePos = computeBasePosition();
        assertNotNull(basePos, "应能计算基准站近似坐标");
        log.info(String.format("基准站近似坐标: X=%.4f Y=%.4f Z=%.4f", basePos[0], basePos[1], basePos[2]));

        Rtk rtk = new Rtk();
        rtk.opt.mode = Constants.PMODE_KINEMA;
        rtk.opt.nf = 2;
        rtk.opt.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL
                | Constants.SYS_CMP | Constants.SYS_QZS;
        rtk.opt.elmin = 15.0 * Constants.D2R;
        rtk.opt.ionoopt = Constants.IONOOPT_BRDC;
        rtk.opt.tropopt = Constants.TROPOPT_SAAS;
        rtk.opt.modear = Constants.ARMODE_OFF;
        rtk.opt.dynamics = 0;

        double[] cRefBasePos = new double[]{-493099.3671, 5551412.6391, 3092558.0152};
        System.arraycopy(cRefBasePos, 0, rtk.rb, 0, 3);
        System.arraycopy(cRefBasePos, 0, rtk.opt.rb, 0, 3);
        log.info(String.format("Java SPP平均基准站坐标: X=%.4f Y=%.4f Z=%.4f", basePos[0], basePos[1], basePos[2]));
        log.info(String.format("使用 RTKLIB C SPP平均基准站坐标: X=%.4f Y=%.4f Z=%.4f", cRefBasePos[0], cRefBasePos[1], cRefBasePos[2]));
        log.info(String.format("Java SPP vs C SPP差值: dX=%.4f dY=%.4f dZ=%.4f",
                basePos[0] - cRefBasePos[0], basePos[1] - cRefBasePos[1], basePos[2] - cRefBasePos[2]));

        BufferedWriter writer = new BufferedWriter(new FileWriter(JAVA_RESULT));
        writer.write("% program   : rtklib_java\n");
        writer.write("% inp file  : rover_0608.obs\n");
        writer.write("% inp file  : base_0608.obs\n");
        writer.write("% inp file  : rover_0608.nav\n");
        writer.write("%\n");
        writer.write("% (x/y/z-ecef=WGS84,Q=1:fix,2:float,3:sbas,4:dgps,5:single,6:ppp,ns=# of satellites)\n");
        writer.write("%  GPST                      x-ecef(m)      y-ecef(m)      z-ecef(m)   Q  ns   sdx(m)   sdy(m)   sdz(m)  sdxy(m)  sdyz(m)  sdzx(m) age(s)  ratio\n");

        int rtkCount = 0, failCount = 0, fixCount = 0, floatCount = 0, singleCount = 0;
        int matchedEpochs = 0;
        int baseIdx = 0;
        GTime lastMatchedBaseTime = null;

        List<double[]> javaResults = new ArrayList<>();

        for (ObsEpoch roverEpoch : roverEpochs) {
            GTime roverTime = roverEpoch.time;

            while (baseIdx < baseEpochs.size()) {
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

            if (result == 1 && matchedEpochs <= 5) {
                double[] ymdhms = TimeSystem.time2ymdhms(rtk.sol.time);
                log.info(String.format("Epoch %d: sol.rr=(%.4f,%.4f,%.4f) x+rb=(%.4f,%.4f,%.4f) Q=%d ns=%d x[0..2]=(%.4f,%.4f,%.4f) P[0]=%.4f P[1]=%.4f qr=(%.4f,%.4f,%.4f)",
                        matchedEpochs,
                        rtk.sol.rr[0], rtk.sol.rr[1], rtk.sol.rr[2],
                        rtk.x[0] + rtk.rb[0], rtk.x[1] + rtk.rb[1], rtk.x[2] + rtk.rb[2],
                        rtk.sol.stat, rtk.sol.ns,
                        rtk.x[0], rtk.x[1], rtk.x[2],
                        rtk.P[0], rtk.P[1],
                        rtk.sol.qr[0], rtk.sol.qr[1], rtk.sol.qr[2]));
            }

            if (result == 1) {
                rtkCount++;
                double[] ymdhms = TimeSystem.time2ymdhms(rtk.sol.time);
                String dateStr = String.format("%04d/%02d/%02d", (int)ymdhms[0], (int)ymdhms[1], (int)ymdhms[2]);
                String timeStr = String.format("%02d:%02d:%06.3f", (int)ymdhms[3], (int)ymdhms[4], ymdhms[5]);

                double[] roverPos = new double[3];
                System.arraycopy(rtk.sol.rr, 0, roverPos, 0, 3);

                int q = rtk.sol.stat;
                int ns = rtk.sol.ns;

                writer.write(String.format("%s %s %14.4f %14.4f %14.4f  %d %2d\n",
                        dateStr, timeStr, roverPos[0], roverPos[1], roverPos[2], q, ns));

                javaResults.add(new double[]{roverPos[0], roverPos[1], roverPos[2], q, ns,
                        ymdhms[0], ymdhms[1], ymdhms[2], ymdhms[3], ymdhms[4], ymdhms[5]});

                if (q == Constants.SOLQ_FIX) fixCount++;
                else if (q == Constants.SOLQ_FLOAT) floatCount++;
                else if (q == Constants.SOLQ_SINGLE) singleCount++;
            } else {
                failCount++;
            }
        }

        writer.close();
        log.info("Java RTK 结果已写入: {}", JAVA_RESULT);

        log.info("===== 读取 RTKLIB C 参考结果 =====");
        List<double[]> cResults = readRtklibPosFile(RTKLIB_C_RESULT);
        log.info("RTKLIB C 结果历元数: {}", cResults.size());

        log.info("===== 对比结果 =====");
        log.info(String.format("Java: 匹配历元=%d, 成功=%d, 失败=%d, Fix=%d, Float=%d, Single=%d",
                matchedEpochs, rtkCount, failCount, fixCount, floatCount, singleCount));

        compareResults(javaResults, cResults);

        assertTrue(matchedEpochs > 0, "应至少有一个匹配的历元");
    }

    private List<double[]> readRtklibPosFile(String filePath) throws IOException {
        List<double[]> results = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.startsWith("%") || line.trim().isEmpty()) continue;

                String[] parts = line.trim().split("\\s+");
                if (parts.length < 7) continue;

                try {
                    String datePart = parts[0];
                    String timePart = parts[1];
                    double x = Double.parseDouble(parts[2]);
                    double y = Double.parseDouble(parts[3]);
                    double z = Double.parseDouble(parts[4]);
                    int q = Integer.parseInt(parts[5]);
                    int ns = Integer.parseInt(parts[6]);

                    String[] dateParts = datePart.split("/");
                    String[] timeParts = timePart.split(":");
                    double year = Double.parseDouble(dateParts[0]);
                    double month = Double.parseDouble(dateParts[1]);
                    double day = Double.parseDouble(dateParts[2]);
                    double hour = Double.parseDouble(timeParts[0]);
                    double min = Double.parseDouble(timeParts[1]);
                    double sec = Double.parseDouble(timeParts[2]);

                    results.add(new double[]{x, y, z, q, ns, year, month, day, hour, min, sec});
                } catch (NumberFormatException e) {
                    continue;
                }
            }
        }
        return results;
    }

    private void compareResults(List<double[]> javaResults, List<double[]> cResults) {
        int compared = 0;
        double sumDx = 0, sumDy = 0, sumDz = 0;
        double maxDx = 0, maxDy = 0, maxDz = 0;
        double sum3D = 0, max3D = 0;
        int qMatch = 0, qMismatch = 0;

        Map<String, double[]> cMap = new LinkedHashMap<>();
        for (double[] r : cResults) {
            String key = String.format("%04.0f%02.0f%02.0f%02.0f%02.0f%06.3f",
                    r[5], r[6], r[7], r[8], r[9], r[10]);
            cMap.put(key, r);
        }

        log.info(String.format("%-22s  %14s %14s %14s  %4s %4s  |  dX(m)    dY(m)    dZ(m)   3D(m)",
                "Time", "X_java", "Y_java", "Z_java", "Qj", "Qc"));
        log.info("-".repeat(130));

        for (double[] jr : javaResults) {
            String key = String.format("%04.0f%02.0f%02.0f%02.0f%02.0f%06.3f",
                    jr[5], jr[6], jr[7], jr[8], jr[9], jr[10]);

            double[] cr = cMap.get(key);
            if (cr == null) continue;

            compared++;
            double dx = jr[0] - cr[0];
            double dy = jr[1] - cr[1];
            double dz = jr[2] - cr[2];
            double d3 = Math.sqrt(dx * dx + dy * dy + dz * dz);

            sumDx += Math.abs(dx);
            sumDy += Math.abs(dy);
            sumDz += Math.abs(dz);
            sum3D += d3;
            maxDx = Math.max(maxDx, Math.abs(dx));
            maxDy = Math.max(maxDy, Math.abs(dy));
            maxDz = Math.max(maxDz, Math.abs(dz));
            max3D = Math.max(max3D, d3);

            int qj = (int) jr[3];
            int qc = (int) cr[3];
            if (qj == qc) qMatch++;
            else qMismatch++;

            if (compared <= 10 || d3 > 0.5 || qj != qc) {
                String timeStr = String.format("%04.0f/%02.0f/%02.0f %02.0f:%02.0f:%06.3f",
                        jr[5], jr[6], jr[7], jr[8], jr[9], jr[10]);
                log.info(String.format("%-22s  %14.4f %14.4f %14.4f  %4d %4d  | %8.4f %8.4f %8.4f %8.4f%s",
                        timeStr, jr[0], jr[1], jr[2], qj, qc, dx, dy, dz, d3,
                        d3 > 1.0 ? " ***" : ""));
            }
        }

        log.info("-".repeat(130));
        log.info(String.format("对比历元数: %d / Java=%d / C=%d", compared, javaResults.size(), cResults.size()));
        log.info(String.format("Q 匹配: %d, Q 不匹配: %d", qMatch, qMismatch));
        if (compared > 0) {
            log.info(String.format("平均偏差: dX=%.4f dY=%.4f dZ=%.4f 3D=%.4f (m)",
                    sumDx / compared, sumDy / compared, sumDz / compared, sum3D / compared));
            log.info(String.format("最大偏差: dX=%.4f dY=%.4f dZ=%.4f 3D=%.4f (m)",
                    maxDx, maxDy, maxDz, max3D));
        }

        if (compared > 0) {
            log.info(String.format("\n===== 对比总结 ====="));
            log.info(String.format("对比历元: %d", compared));
            log.info(String.format("平均3D偏差: %.4f m", sum3D / compared));
            log.info(String.format("最大3D偏差: %.4f m", max3D));
            log.info(String.format("解类型匹配率: %.1f%%", 100.0 * qMatch / compared));
        }
    }
}