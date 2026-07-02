package org.rtklib.java;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.rtklib.java.common.ObsCode;
import org.rtklib.java.common.RtklibCommon;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.ionosphere.IonosphereModel;
import org.rtklib.java.troposphere.TroposphereModel;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.pntpos.SppCore;
import org.rtklib.java.ephemeris.EphModel;
import org.rtklib.java.rinex.RinexParser;
import org.rtklib.java.rinex.RtcmToRinexConverter;
import org.rtklib.java.time.TimeSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 测试使用 RINEX 数据进行 SPP 单点定位。
 * 先将 RTCM3 数据转换为 RINEX 格式，再解析 RINEX 文件进行定位。
 */
@DisplayName("RINEX SPP 定位测试")
public class RinexSppTest {

    private static final Logger log = LoggerFactory.getLogger(RinexSppTest.class);

    private static final String ROVER_PATH =
            "C:\\Users\\admin\\Desktop\\<ROVER_DEVICE_ID>\\2026-06-08\\1.rtcm3";

    private static final String BASE_PATH =
            "C:\\Users\\admin\\Desktop\\<BASE_DEVICE_ID>\\2026-06-08\\1.rtcm3";

    private static byte[] roverData;
    private static byte[] baseData;

    @BeforeAll
    static void loadData() throws IOException {
        try (FileInputStream fis = new FileInputStream(ROVER_PATH)) {
            roverData = fis.readAllBytes();
        }
        try (FileInputStream fis = new FileInputStream(BASE_PATH)) {
            baseData = fis.readAllBytes();
        }
        log.info("已加载 rover: {} bytes, base: {} bytes", roverData.length, baseData.length);
    }

    /**
     * 测试：RTCM → RINEX → SPP 定位的完整流程。
     * 1. 将 RTCM 数据转换为 RINEX 文件
     * 2. 解析 RINEX 观测文件和导航文件
     * 3. 使用解析后的数据进行 SPP 定位
     */
    @Test
    @DisplayName("RINEX 转换后 SPP 定位")
    void testRinexSppPositioning(@TempDir Path tempDir) throws IOException {
        String outputDir = tempDir.toString();

        // 步骤1: RTCM → RINEX 转换
        log.info("步骤1: 将 RTCM 转换为 RINEX...");
        RtcmToRinexConverter converter = new RtcmToRinexConverter(3.02, outputDir, "ROVER");
        boolean ok = converter.convert(roverData, roverData.length);
        assertTrue(ok, "RTCM → RINEX 转换应成功");

        Path obsFile = tempDir.resolve("ROVER.obs");
        Path navFile = tempDir.resolve("ROVER.nav");
        assertTrue(java.nio.file.Files.exists(obsFile), "RINEX 观测文件应存在");
        assertTrue(java.nio.file.Files.exists(navFile), "RINEX 导航文件应存在");

        log.info("=== RINEX OBS 文件前30行 ===");
        List<String> obsLines = java.nio.file.Files.readAllLines(obsFile);
        for (int li = 0; li < Math.min(30, obsLines.size()); li++) {
            log.info("  " + obsLines.get(li));
        }

        // 调试: 直接检查 RTCM 解码后的 Obsd 数据
        log.info("=== 直接检查 RTCM 解码后的 Obsd 数据 ===");
        org.rtklib.java.rtcm.RtcmDataHandler dbgHandler = new org.rtklib.java.rtcm.RtcmDataHandler() {
            int cnt = 0;
            @Override public void onObservationEpoch(org.rtklib.java.rtcm.ObservationEpoch epoch) {
                if (cnt++ < 2) {
                    for (Obsd o : epoch.obsList) {
                        String sc = org.rtklib.java.common.ObsCode.satToSysChar(o.sat);
                        int prn = org.rtklib.java.common.ObsCode.satToPrn(o.sat);
                        log.info(String.format("  RTCM OBS: %s%02d code[0]=%d code[1]=%d P[0]=%.3f P[1]=%.3f L[0]=%.3f L[1]=%.3f",
                                sc, prn, o.code[0], o.code[1], o.P[0], o.P[1], o.L[0], o.L[1]));
                    }
                }
            }
            @Override public void onEph(Eph eph) {}
            @Override public void onGeph(Geph geph) {}
            @Override public void onStation(Sta sta) {}
            @Override public void onSsr(Ssr ssr) {}
            @Override public void onAuxData(org.rtklib.java.rtcm.AuxData aux) {}
            @Override public void onFinish() {}
        };
        org.rtklib.java.rtcm.RtcmCallbackDecoder dbgDecoder = new org.rtklib.java.rtcm.RtcmCallbackDecoder(dbgHandler);
        dbgDecoder.feed(roverData, 0, roverData.length);
        dbgDecoder.finish();

        // 步骤2: 解析 RINEX 文件
        log.info("步骤2: 解析 RINEX 文件...");
        RinexParser parser = new RinexParser();
        boolean obsParsed = parser.parseObs(obsFile.toString());
        assertTrue(obsParsed, "RINEX 观测文件解析应成功");
        assertNotNull(parser.obs, "观测数据不应为空");
        assertTrue(parser.obs.n > 0, "应包含观测数据，实际: " + parser.obs.n);

        boolean navParsed = parser.parseNav(navFile.toString());
        assertTrue(navParsed, "RINEX 导航文件解析应成功");
        assertNotNull(parser.nav, "导航数据不应为空");

        log.info("解析结果: obs records={}, nav.eph count={}", parser.obs.n, parser.nav.n);

        // 步骤3: SPP 定位
        log.info("步骤3: 执行 SPP 定位...");
        Sol sol = new Sol();
        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_SINGLE;
        opt.nf = 2;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL
                | Constants.SYS_CMP | Constants.SYS_QZS;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;

        Ssat[] ssat = new Ssat[Constants.MAXSAT];
        for (int i = 0; i < Constants.MAXSAT; i++) {
            ssat[i] = new Ssat();
        }

        int n = parser.obs.n;
        int epochStart = n;
        for (int i = 1; i < n; i++) {
            if (!parser.obs.data[i].time.equals(parser.obs.data[0].time)) {
                epochStart = i;
                break;
            }
        }
        int nEpoch = epochStart;
        log.info("第一个历元: {} 颗卫星 (总观测数: {})", nEpoch, n);

        int[] vsat = new int[nEpoch];
        double[] azel = new double[nEpoch * 2];
        double[] resp = new double[nEpoch];
        double[] vare = new double[nEpoch];
        int[] svh = new int[nEpoch];
        String[] msg = new String[1];

        double[] rs = new double[nEpoch * 6];
        double[] dts = new double[nEpoch * 2];

        GTime obsTime = parser.obs.data[0].time;
        double[] ymd = TimeSystem.time2ymdhms(obsTime);
        log.info(String.format("观测时间: %04d-%02d-%02d %02d:%02d:%06.3f",
                (int)ymd[0], (int)ymd[1], (int)ymd[2], (int)ymd[3], (int)ymd[4], ymd[5]));
        for (int i = 0; i < nEpoch; i++) {
            double[] rs_i = new double[6];
            double[] dts_i = new double[2];
            double[] vare_i = new double[1];
            EphModel.satpos(obsTime, parser.nav, parser.obs.data[i].sat, rs_i, dts_i, vare_i);
            for (int j = 0; j < 6; j++) rs[i * 6 + j] = rs_i[j];
            for (int j = 0; j < 2; j++) dts[i * 2 + j] = dts_i[j];
            vare[i] = vare_i[0];
            double r = Math.sqrt(rs_i[0]*rs_i[0] + rs_i[1]*rs_i[1] + rs_i[2]*rs_i[2]);
            if (i < 5 || r > 0) {
                log.info(String.format("  sat[%d]=%d code[0]=%d P[0]=%.3f rs=(%.0f,%.0f,%.0f) r=%.0f dts=%.12f",
                        i, parser.obs.data[i].sat, parser.obs.data[i].code[0],
                        parser.obs.data[i].P[0], rs_i[0], rs_i[1], rs_i[2], r, dts_i[0]));
            }
        }

        int result = SppCore.estpos(parser.obs.data, nEpoch, rs, dts, vare, svh,
                parser.nav, opt, ssat, sol, azel, vsat, resp, msg);

        assertTrue(result == 1, "SPP 定位应成功: " + (msg[0] != null ? msg[0] : ""));
        assertTrue(sol.ns >= 4, "SPP 解算应有至少 4 颗卫星，实际: " + sol.ns);
        assertEquals(Constants.SOLQ_SINGLE, sol.stat, "SPP 解算状态应为 SINGLE");
        assertFalse(Double.isNaN(sol.rr[0]), "X 坐标不应为 NaN");
        assertFalse(Double.isNaN(sol.rr[1]), "Y 坐标不应为 NaN");
        assertFalse(Double.isNaN(sol.rr[2]), "Z 坐标不应为 NaN");

        double[] llh = new double[3];
        CoordTransform.ecef2pos(sol.rr, llh);
        log.info("SPP 定位结果: ECEF=({}, {}, {}), LLH=({}, {}, {}), ns={}",
                sol.rr[0], sol.rr[1], sol.rr[2],
                llh[0] * Constants.R2D, llh[1] * Constants.R2D, llh[2], sol.ns);

        log.info("RINEX SPP 定位测试通过");
    }

    /**
     * 测试：RINEX 2.11 格式的转换和定位。
     */
    @Test
    @DisplayName("RINEX 2.11 格式 SPP 定位")
    @org.junit.jupiter.api.Disabled("RINEX 2.x 暂不支持，后续版本补充")
    void testRinex211Spp(@TempDir Path tempDir) throws IOException {
        String outputDir = tempDir.toString();

        // RTCM → RINEX 2.11
        RtcmToRinexConverter converter = new RtcmToRinexConverter(2.11, outputDir, "ROVER");
        boolean ok = converter.convert(roverData, roverData.length);
        assertTrue(ok, "RTCM → RINEX 2.11 转换应成功");

        Path obsFile = tempDir.resolve("ROVER.obs");
        Path navFile = tempDir.resolve("ROVER.nav");

        // 解析 RINEX 2.11
        RinexParser parser = new RinexParser();
        assertTrue(parser.parseObs(obsFile.toString()), "RINEX 2.11 观测文件解析应成功");
        assertTrue(parser.parseNav(navFile.toString()), "RINEX 2.11 导航文件解析应成功");

        assertTrue(parser.obs.n > 0, "RINEX 2.11 应包含观测数据");

        // SPP 定位
        Sol sol = new Sol();
        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_SINGLE;
        opt.nf = 2;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL
                | Constants.SYS_CMP | Constants.SYS_QZS;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;

        Ssat[] ssat = new Ssat[Constants.MAXSAT];
        for (int i = 0; i < Constants.MAXSAT; i++) {
            ssat[i] = new Ssat();
        }

        int n = parser.obs.n;
        int epochStart = n;
        for (int i = 1; i < n; i++) {
            if (!parser.obs.data[i].time.equals(parser.obs.data[0].time)) {
                epochStart = i;
                break;
            }
        }
        int nEpoch = epochStart;

        int[] vsat = new int[nEpoch];
        double[] azel = new double[nEpoch * 2];
        double[] resp = new double[nEpoch];
        double[] vare = new double[nEpoch];
        int[] svh = new int[nEpoch];
        String[] msg = new String[1];

        double[] rs = new double[nEpoch * 6];
        double[] dts = new double[nEpoch * 2];

        GTime obsTime = parser.obs.data[0].time;
        for (int i = 0; i < nEpoch; i++) {
            double[] rs_i = new double[6];
            double[] dts_i = new double[2];
            double[] vare_i = new double[1];
            EphModel.satpos(obsTime, parser.nav, parser.obs.data[i].sat, rs_i, dts_i, vare_i);
            for (int j = 0; j < 6; j++) rs[i * 6 + j] = rs_i[j];
            for (int j = 0; j < 2; j++) dts[i * 2 + j] = dts_i[j];
            vare[i] = vare_i[0];
        }

        int result = SppCore.estpos(parser.obs.data, nEpoch, rs, dts, vare, svh,
                parser.nav, opt, ssat, sol, azel, vsat, resp, msg);

        assertTrue(result == 1, "RINEX 2.11 SPP 定位应成功: " + (msg[0] != null ? msg[0] : ""));
        assertTrue(sol.ns >= 4, "应有至少 4 颗卫星");

        log.info("RINEX 2.11 SPP 定位测试通过");
    }

    /**
     * 测试：使用 rover 和 base 两份 RTCM 数据分别转换 RINEX 并验证。
     */
    @Test
    @DisplayName("rover 和 base 双站 RINEX 转换")
    void testDualStationRinex(@TempDir Path tempDir) throws IOException {
        String outputDir = tempDir.toString();

        // 转换 rover
        RtcmToRinexConverter roverConverter = new RtcmToRinexConverter(3.02, outputDir, "ROVER");
        assertTrue(roverConverter.convert(roverData, roverData.length), "rover 转换应成功");

        // 转换 base
        RtcmToRinexConverter baseConverter = new RtcmToRinexConverter(3.02, outputDir, "BASE");
        assertTrue(baseConverter.convert(baseData, baseData.length), "base 转换应成功");

        Path roverObs = tempDir.resolve("ROVER.obs");
        Path roverNav = tempDir.resolve("ROVER.nav");
        Path baseObs = tempDir.resolve("BASE.obs");
        Path baseNav = tempDir.resolve("BASE.nav");

        assertTrue(java.nio.file.Files.exists(roverObs), "rover obs 文件应存在");
        assertTrue(java.nio.file.Files.exists(roverNav), "rover nav 文件应存在");
        assertTrue(java.nio.file.Files.exists(baseObs), "base obs 文件应存在");
        assertTrue(java.nio.file.Files.exists(baseNav), "base nav 文件应存在");

        long roverObsSize = java.nio.file.Files.size(roverObs);
        long baseObsSize = java.nio.file.Files.size(baseObs);
        log.info("rover obs: {} bytes, base obs: {} bytes", roverObsSize, baseObsSize);

        assertTrue(roverObsSize > 0, "rover 观测文件不应为空");
        assertTrue(baseObsSize > 0, "base 观测文件不应为空");

        // 解析 rover RINEX 进行 SPP 定位
        RinexParser parser = new RinexParser();
        assertTrue(parser.parseObs(roverObs.toString()), "rover RINEX 解析应成功");
        assertTrue(parser.parseNav(roverNav.toString()), "rover 导航文件解析应成功");

        Sol sol = new Sol();
        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_SINGLE;
        opt.nf = 2;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL
                | Constants.SYS_CMP | Constants.SYS_QZS;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;

        Ssat[] ssat = new Ssat[Constants.MAXSAT];
        for (int i = 0; i < Constants.MAXSAT; i++) {
            ssat[i] = new Ssat();
        }

        int n = parser.obs.n;
        int epochStart = n;
        for (int i = 1; i < n; i++) {
            if (!parser.obs.data[i].time.equals(parser.obs.data[0].time)) {
                epochStart = i;
                break;
            }
        }
        int nEpoch = epochStart;

        double[] rs = new double[nEpoch * 6];
        double[] dts = new double[nEpoch * 2];
        double[] vare = new double[nEpoch];
        int[] svh = new int[nEpoch];
        String[] msg = new String[1];

        GTime obsTime = parser.obs.data[0].time;
        for (int i = 0; i < nEpoch; i++) {
            double[] rs_i = new double[6];
            double[] dts_i = new double[2];
            double[] vare_i = new double[1];
            EphModel.satpos(obsTime, parser.nav, parser.obs.data[i].sat, rs_i, dts_i, vare_i);
            for (int j = 0; j < 6; j++) rs[i * 6 + j] = rs_i[j];
            for (int j = 0; j < 2; j++) dts[i * 2 + j] = dts_i[j];
            vare[i] = vare_i[0];
        }

        int result = SppCore.estpos(parser.obs.data, nEpoch, rs, dts, vare, svh,
                parser.nav, opt, ssat, sol, new double[nEpoch * 2], new int[nEpoch], new double[nEpoch], msg);

        assertTrue(result == 1, "RINEX 解析后 SPP 定位应成功: " + (msg[0] != null ? msg[0] : ""));
        assertTrue(sol.ns >= 4, "应有至少 4 颗卫星");

        double[] llh = new double[3];
        CoordTransform.ecef2pos(sol.rr, llh);
        log.info(String.format("rover SPP: LLH=(%.6f, %.6f, %.1f), ns=%d",
                llh[0] * Constants.R2D, llh[1] * Constants.R2D, llh[2], sol.ns));

        // 解析 base RINEX 进行 SPP 定位
        RinexParser baseParser = new RinexParser();
        assertTrue(baseParser.parseObs(baseObs.toString()), "base RINEX 解析应成功");
        assertTrue(baseParser.parseNav(baseNav.toString()), "base 导航文件解析应成功");

        Sol baseSol = new Sol();
        Ssat[] baseSsat = new Ssat[Constants.MAXSAT];
        for (int i = 0; i < Constants.MAXSAT; i++) {
            baseSsat[i] = new Ssat();
        }

        int bn = baseParser.obs.n;
        int baseEpochStart = bn;
        for (int i = 1; i < bn; i++) {
            if (!baseParser.obs.data[i].time.equals(baseParser.obs.data[0].time)) {
                baseEpochStart = i;
                break;
            }
        }
        int bnEpoch = baseEpochStart;

        double[] brs = new double[bnEpoch * 6];
        double[] bdts = new double[bnEpoch * 2];
        double[] bvare = new double[bnEpoch];
        int[] bsvh = new int[bnEpoch];

        GTime baseObsTime = baseParser.obs.data[0].time;
        for (int i = 0; i < bnEpoch; i++) {
            double[] rs_i = new double[6];
            double[] dts_i = new double[2];
            double[] vare_i = new double[1];
            EphModel.satpos(baseObsTime, baseParser.nav, baseParser.obs.data[i].sat, rs_i, dts_i, vare_i);
            for (int j = 0; j < 6; j++) brs[i * 6 + j] = rs_i[j];
            for (int j = 0; j < 2; j++) bdts[i * 2 + j] = dts_i[j];
            bvare[i] = vare_i[0];
        }
        int baseResult = SppCore.estpos(baseParser.obs.data, bnEpoch, brs, bdts, bvare, bsvh,
                baseParser.nav, opt, baseSsat, baseSol, new double[bnEpoch * 2], new int[bnEpoch], new double[bnEpoch], msg);

        assertTrue(baseResult == 1, "base SPP 定位应成功: " + (msg[0] != null ? msg[0] : ""));

        double[] baseLlh = new double[3];
        CoordTransform.ecef2pos(baseSol.rr, baseLlh);
        log.info(String.format("base SPP: LLH=(%.6f, %.6f, %.1f), ns=%d",
                baseLlh[0] * Constants.R2D, baseLlh[1] * Constants.R2D, baseLlh[2], baseSol.ns));

        log.info("双站 RINEX 转换测试通过");
    }

    @Test
    @DisplayName("Java SPP 与 rtklib C rnx2rtkp SPP 结果对比")
    void testCompareSppWithRtklibC(@TempDir Path tempDir) throws IOException {
        String outputDir = tempDir.toString();

        RtcmToRinexConverter converter = new RtcmToRinexConverter(3.02, outputDir, "ROVER");
        assertTrue(converter.convert(roverData, roverData.length), "Java 转换应成功");

        Path obsFile = tempDir.resolve("ROVER.obs");
        Path navFile = tempDir.resolve("ROVER.nav");
        assertTrue(java.nio.file.Files.exists(obsFile), "obs 文件应存在");
        assertTrue(java.nio.file.Files.exists(navFile), "nav 文件应存在");

        // rtklib C rnx2rtkp SPP
        String rnx2rtkp = "D:\\code\\rtklib_java\\RTKLIB_EX_2.5.0\\rnx2rtkp.exe";
        Path posFile = tempDir.resolve("rover_spp.pos");

        String[] cmdArray = {
                rnx2rtkp,
                "-e",
                "-p", "0",
                "-f", "2",
                "-m", "15",
                "-sys", "G,R,E,J,C",
                "-o", posFile.toString(),
                obsFile.toString(),
                navFile.toString()
        };
        log.info("rnx2rtkp command: {}", String.join(" ", cmdArray));

        ProcessBuilder pb = new ProcessBuilder(cmdArray);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String cOutput = new String(proc.getInputStream().readAllBytes());
        try { proc.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        log.info("rnx2rtkp exit: {}, stderr: {}", proc.exitValue(), cOutput.trim());
        assertTrue(java.nio.file.Files.exists(posFile), "C SPP pos 文件应存在");

        // 解析 C SPP 所有有效历元
        List<String> posLines = java.nio.file.Files.readAllLines(posFile);
        java.util.List<double[]> cEcefList = new java.util.ArrayList<>();
        for (String line : posLines) {
            if (line.startsWith("%") || line.trim().isEmpty()) continue;
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 7) {
                try {
                    int q = Integer.parseInt(parts[5]);
                    if (q == 0) continue;
                    double x = Double.parseDouble(parts[2]);
                    double y = Double.parseDouble(parts[3]);
                    double z = Double.parseDouble(parts[4]);
                    cEcefList.add(new double[]{x, y, z});
                } catch (NumberFormatException e) { continue; }
            }
        }
        assertFalse(cEcefList.isEmpty(), "C SPP 应有定位结果");
        log.info("C SPP 有效历元数: {}", cEcefList.size());

        // C 版平均 ECEF
        double cAvgX = 0, cAvgY = 0, cAvgZ = 0;
        for (double[] e : cEcefList) { cAvgX += e[0]; cAvgY += e[1]; cAvgZ += e[2]; }
        cAvgX /= cEcefList.size(); cAvgY /= cEcefList.size(); cAvgZ /= cEcefList.size();
        log.info(String.format("C SPP 平均: ECEF=(%.4f, %.4f, %.4f), %d 历元", cAvgX, cAvgY, cAvgZ, cEcefList.size()));

        // Java SPP: 逐历元解算
        RinexParser parser = new RinexParser();
        assertTrue(parser.parseObs(obsFile.toString()), "RINEX 解析应成功");
        assertTrue(parser.parseNav(navFile.toString()), "导航文件解析应成功");

        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_SINGLE;
        opt.nf = 2;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL | Constants.SYS_CMP | Constants.SYS_QZS;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;

        int n = parser.obs.n;
        java.util.List<Integer> epochStarts = new java.util.ArrayList<>();
        epochStarts.add(0);
        for (int i = 1; i < n; i++) {
            if (!parser.obs.data[i].time.equals(parser.obs.data[i - 1].time)) {
                epochStarts.add(i);
            }
        }
        epochStarts.add(n);
        log.info("Java 共 {} 个历元", epochStarts.size() - 1);

        java.util.List<double[]> javaEcefList = new java.util.ArrayList<>();
        for (int ei = 0; ei < epochStarts.size() - 1; ei++) {
            int start = epochStarts.get(ei);
            int nEpoch = epochStarts.get(ei + 1) - start;
            if (nEpoch < 4) continue;

            Sol sol = new Sol();
            Ssat[] ssat = new Ssat[Constants.MAXSAT];
            for (int i = 0; i < Constants.MAXSAT; i++) ssat[i] = new Ssat();

            double[] rs = new double[nEpoch * 6];
            double[] dts = new double[nEpoch * 2];
            double[] vare = new double[nEpoch];
            int[] svh = new int[nEpoch];
            GTime obsTime = parser.obs.data[start].time;
            for (int i = 0; i < nEpoch; i++) {
                double[] rs_i = new double[6], dts_i = new double[2], vare_i = new double[1];
                EphModel.satpos(obsTime, parser.nav, parser.obs.data[start + i].sat, rs_i, dts_i, vare_i);
                for (int j = 0; j < 6; j++) rs[i * 6 + j] = rs_i[j];
                for (int j = 0; j < 2; j++) dts[i * 2 + j] = dts_i[j];
                vare[i] = vare_i[0];
            }

            String[] msg = new String[1];
            Obsd[] epochObs = java.util.Arrays.copyOfRange(parser.obs.data, start, start + nEpoch);
            int ret = SppCore.estpos(epochObs, nEpoch, rs, dts, vare, svh,
                    parser.nav, opt, ssat, sol, new double[nEpoch * 2], new int[nEpoch], new double[nEpoch], msg);
            if (ret == 1) {
                javaEcefList.add(new double[]{sol.rr[0], sol.rr[1], sol.rr[2]});
            }
        }
        assertFalse(javaEcefList.isEmpty(), "Java SPP 应有定位结果");
        log.info("Java SPP 有效历元数: {}", javaEcefList.size());

        double jAvgX = 0, jAvgY = 0, jAvgZ = 0;
        for (double[] e : javaEcefList) { jAvgX += e[0]; jAvgY += e[1]; jAvgZ += e[2]; }
        jAvgX /= javaEcefList.size(); jAvgY /= javaEcefList.size(); jAvgZ /= javaEcefList.size();
        log.info(String.format("Java SPP 平均: ECEF=(%.4f, %.4f, %.4f), %d 历元", jAvgX, jAvgY, jAvgZ, javaEcefList.size()));

        // 对比平均位置
        double dx = jAvgX - cAvgX;
        double dy = jAvgY - cAvgY;
        double dz = jAvgZ - cAvgZ;
        double dist3d = Math.sqrt(dx * dx + dy * dy + dz * dz);

        log.info(String.format("SPP 平均差异: dX=%.4f m, dY=%.4f m, dZ=%.4f m, 3D=%.4f m", dx, dy, dz, dist3d));

        // 逐历元对比第一个历元
        if (!cEcefList.isEmpty() && !javaEcefList.isEmpty()) {
            double[] c0 = cEcefList.get(0);
            double[] j0 = javaEcefList.get(0);
            double d0 = Math.sqrt(Math.pow(j0[0] - c0[0], 2) + Math.pow(j0[1] - c0[1], 2) + Math.pow(j0[2] - c0[2], 2));
            log.info(String.format("首历元差异: Java(%.4f,%.4f,%.4f) vs C(%.4f,%.4f,%.4f), 3D=%.4f m",
                    j0[0], j0[1], j0[2], c0[0], c0[1], c0[2], d0));
        }

        assertTrue(dist3d < 50.0, String.format("Java 与 C SPP 平均定位差异应 < 50m, 实际 %.4f m", dist3d));
    }

    @Test
    @DisplayName("逐卫星 SPP 残差对比（Java vs C）")
    void testSppResidualCompare(@TempDir Path tempDir) throws IOException {
        String outputDir = tempDir.toString();

        RtcmToRinexConverter converter = new RtcmToRinexConverter(3.02, outputDir, "ROVER");
        assertTrue(converter.convert(roverData, roverData.length), "Java 转换应成功");

        Path obsFile = tempDir.resolve("ROVER.obs");
        Path navFile = tempDir.resolve("ROVER.nav");

        RinexParser parser = new RinexParser();
        assertTrue(parser.parseObs(obsFile.toString()));
        assertTrue(parser.parseNav(navFile.toString()));

        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_SINGLE;
        opt.nf = 2;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL | Constants.SYS_CMP | Constants.SYS_QZS;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;

        int n = parser.obs.n;
        java.util.List<Integer> epochStarts = new java.util.ArrayList<>();
        epochStarts.add(0);
        for (int i = 1; i < n; i++) {
            if (!parser.obs.data[i].time.equals(parser.obs.data[i - 1].time)) {
                epochStarts.add(i);
            }
        }
        epochStarts.add(n);

        int start = epochStarts.get(0);
        int nEpoch = epochStarts.get(1) - start;

        double[] rs = new double[nEpoch * 6];
        double[] dts = new double[nEpoch * 2];
        double[] vare = new double[nEpoch];
        int[] svh = new int[nEpoch];
        GTime obsTime = parser.obs.data[start].time;
        for (int i = 0; i < nEpoch; i++) {
            double[] rs_i = new double[6], dts_i = new double[2], vare_i = new double[1];
            EphModel.satpos(obsTime, parser.nav, parser.obs.data[start + i].sat, rs_i, dts_i, vare_i);
            for (int j = 0; j < 6; j++) rs[i * 6 + j] = rs_i[j];
            for (int j = 0; j < 2; j++) dts[i * 2 + j] = dts_i[j];
            vare[i] = vare_i[0];
        }

        Sol sol = new Sol();
        Ssat[] ssat = new Ssat[Constants.MAXSAT];
        for (int i = 0; i < Constants.MAXSAT; i++) ssat[i] = new Ssat();

        double[] azel = new double[nEpoch * 2];
        int[] vsat = new int[nEpoch];
        double[] resp = new double[nEpoch];
        int[] nsArr = new int[1];

        int NX = SppCore.nx(opt);
        double[] x = new double[NX];
        double[] dx = new double[NX];
        double[] Q = new double[NX * NX];
        int m = nEpoch + NX - 3;
        double[] v = new double[m];
        double[] H = new double[NX * m];
        double[] var = new double[m];

        for (int it = 0; it < 10; it++) {
            int nv = SppCore.rescode(it, java.util.Arrays.copyOfRange(parser.obs.data, start, start + nEpoch),
                    nEpoch, rs, dts, vare, svh, parser.nav, x, opt, ssat,
                    v, H, var, azel, vsat, resp, nsArr);
            if (nv < NX) break;

            for (int j = 0; j < nv; j++) {
                double sig = Math.sqrt(var[j]);
                v[j] /= sig;
                for (int k = 0; k < NX; k++) H[j * NX + k] /= sig;
            }
            if (RtklibCommon.lsq(H, v, NX, nv, dx, Q) != 0) break;
            for (int j = 0; j < NX; j++) x[j] += dx[j];
            if (RtklibCommon.norm(dx, NX) < 1E-4) break;
        }

        log.info(String.format("Java SPP 收敛位置: ECEF=(%.4f, %.4f, %.4f)", x[0], x[1], x[2]));
        log.info(String.format("Java SPP 钟差: x[3]=%.6f(%.3fm), x[4]=%.6f, x[5]=%.6f, x[6]=%.6f(%.3fm), x[7]=%.6f",
                x[3], x[3] * Constants.CLIGHT, x.length > 4 ? x[4] : 0, x.length > 5 ? x[5] : 0,
                x.length > 6 ? x[6] : 0, x.length > 6 ? x[6] * Constants.CLIGHT : 0, x.length > 7 ? x[7] : 0));

        double[] pos = new double[3];
        CoordTransform.ecef2pos(new double[]{x[0], x[1], x[2]}, pos);
        double dtr = x[3];

        int vi = 0;
        for (int i = 0; i < nEpoch; i++) {
            Obsd obs_i = parser.obs.data[start + i];
            int sat = obs_i.sat;
            int sys = SatUtils.satsys(sat, null);
            if (vsat[i] == 0) continue;

            double[] rsi = new double[]{rs[i * 6], rs[i * 6 + 1], rs[i * 6 + 2]};
            double[] e = new double[3];
            double r = RtklibCommon.geodist(rsi, new double[]{x[0], x[1], x[2]}, e);
            double[] ae = new double[2];
            double el = RtklibCommon.satazel(pos, e, ae);

            double[] vmeas = new double[1];
            double P = SppCore.prange(obs_i, parser.nav, opt, vmeas);
            double dion = 0.0;
            if (opt.ionoopt == Constants.IONOOPT_BRDC) {
                double[] ionOut = new double[2];
                IonosphereModel.ionocorr(obs_i.time, parser.nav, sat, pos, ae, opt.ionoopt, ionOut);
                dion = ionOut[0];
                double freq = SatUtils.sat2freq(sat, obs_i.code[0], parser.nav);
                if (freq != 0.0) dion *= RtklibCommon.sqr(Constants.FREQL1 / freq);
            }
            double dtrp = 0.0;
            double[] trpOut = new double[2];
            TroposphereModel.tropcorr(obs_i.time, parser.nav, pos, ae, opt.tropopt, trpOut);
            dtrp = trpOut[0];

            double residual = P - (r + dtr - Constants.CLIGHT * dts[i * 2] + dion + dtrp);
            int si = SppCore.sysIdx(sys, opt);
            if (si > 0) residual -= x[si];

            String sysStr = sys == Constants.SYS_GPS ? "G" : sys == Constants.SYS_CMP ? "C" : sys == Constants.SYS_GAL ? "E" : sys == Constants.SYS_GLO ? "R" : "?";
            log.info(String.format("  sat=%s%02d P=%.3f r=%.3f dtr=%.6f dts=%.9f dion=%.3f dtrp=%.3f res=%.3f el=%.1f",
                    sysStr, ObsCode.satToPrn(sat), P, r, dtr, dts[i * 2], dion, dtrp, residual, el * Constants.R2D));
            vi++;
        }

        log.info("有效卫星数: {}", nsArr[0]);
    }
}