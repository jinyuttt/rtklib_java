package org.rtklib.java;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.PrcOpt;
import org.rtklib.java.data.Sol;
import org.rtklib.java.data.Ssat;
import org.rtklib.java.data.GTime;
import org.rtklib.java.pntpos.PosHandler;
import org.rtklib.java.rinex.RinexSppProcessor;
import org.rtklib.java.rinex.RtcmFileToRinexConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RINEX SPP 封装测试")
public class RinexSppProcessorTest {

    private static final Logger log = LoggerFactory.getLogger(RinexSppProcessorTest.class);

    private static final String ROVER_PATH =
            "C:\\Users\\admin\\Desktop\\<DEVICE_ID>\\2026-06-08\\1.rtcm3";

    private static byte[] roverData;

    @BeforeAll
    static void loadData() throws IOException {
        try (FileInputStream fis = new FileInputStream(ROVER_PATH)) {
            roverData = fis.readAllBytes();
        }
        log.info("已加载 rover: {} bytes", roverData.length);
    }

    @Test
    @DisplayName("RTCM文件转RINEX + RINEX SPP定位 完整流程")
    void testRtcmFileToRinexThenSpp(@TempDir Path tempDir) throws IOException {
        String outputDir = tempDir.toString();

        log.info("步骤1: RTCM文件转RINEX...");
        RtcmFileToRinexConverter converter = new RtcmFileToRinexConverter(3.05, outputDir, "ROVER");
        boolean ok = converter.convert(ROVER_PATH);
        assertTrue(ok, "RTCM文件转RINEX应成功");

        Path obsFile = tempDir.resolve("ROVER.obs");
        Path navFile = tempDir.resolve("ROVER.nav");
        assertTrue(java.nio.file.Files.exists(obsFile), "RINEX观测文件应存在");
        assertTrue(java.nio.file.Files.exists(navFile), "RINEX导航文件应存在");

        log.info("步骤2: RINEX SPP定位...");
        PrcOpt opt = RinexSppProcessor.createDefaultOpt();
        RinexSppProcessor.SppResult result = RinexSppProcessor.processRinex(
                obsFile.toString(), navFile.toString(), opt);

        log.info("定位结果: total={}, success={}, fail={}", result.totalEpochs, result.successCount, result.failCount);
        assertTrue(result.totalEpochs > 0, "应有历元数据");
        assertTrue(result.successCount > 0, "应有成功定位");

        if (!result.solutions.isEmpty()) {
            Sol firstSol = result.solutions.get(0);
            log.info(String.format("首个定位解: ECEF=(%.4f, %.4f, %.4f), ns=%d",
                    firstSol.rr[0], firstSol.rr[1], firstSol.rr[2], firstSol.ns));
            assertTrue(firstSol.ns >= 4, "有效卫星数应>=4");
        }
    }

    @Test
    @DisplayName("RinexSppProcessor实例模式 + 回调")
    void testRinexSppWithCallback(@TempDir Path tempDir) throws IOException {
        String outputDir = tempDir.toString();

        RtcmFileToRinexConverter converter = new RtcmFileToRinexConverter(3.05, outputDir, "ROVER");
        boolean ok = converter.convert(ROVER_PATH);
        assertTrue(ok);

        StringBuilder callbackLog = new StringBuilder();
        PosHandler handler = new PosHandler() {
            @Override public void onSolution(Sol sol, Ssat[] ssat) {
                callbackLog.append(String.format("SPP OK: ns=%d\n", sol.ns));
            }
            @Override public void onPosFail(GTime time, String msg) {
                callbackLog.append(String.format("SPP FAIL: %s\n", msg));
            }
            @Override public void onFinish(int totalEpochs, int successCount, int failCount) {
                callbackLog.append(String.format("SPP DONE: total=%d, ok=%d, fail=%d\n", totalEpochs, successCount, failCount));
            }
        };

        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_SINGLE;
        opt.nf = 2;
        opt.navsys = Constants.SYS_CMP;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;

        RinexSppProcessor spp = new RinexSppProcessor(opt, handler);
        RinexSppProcessor.SppResult result = spp.process(
                converter.getObsFilePath(), converter.getNavFilePath());

        log.info("回调日志:\n{}", callbackLog);
        assertTrue(result.successCount > 0, "应有成功定位");
        assertTrue(callbackLog.toString().contains("SPP DONE"), "应收到完成回调");
    }

    @Test
    @DisplayName("RtcmFileToRinexConverter便捷方法")
    void testRtcmFileConvertConvenience(@TempDir Path tempDir) throws IOException {
        String outputDir = tempDir.toString();
        boolean ok = RtcmFileToRinexConverter.convertFile(ROVER_PATH, 3.05, outputDir, "ROVER2");
        assertTrue(ok, "便捷方法转换应成功");

        Path obsFile = tempDir.resolve("ROVER2.obs");
        Path navFile = tempDir.resolve("ROVER2.nav");
        assertTrue(java.nio.file.Files.exists(obsFile), "ROVER2.obs应存在");
        assertTrue(java.nio.file.Files.exists(navFile), "ROVER2.nav应存在");
    }
}