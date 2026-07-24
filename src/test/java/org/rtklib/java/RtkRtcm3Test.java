package org.rtklib.java;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.PrcOpt;
import org.rtklib.java.rtkpos.RtkProcessor;
import org.rtklib.java.rtkpos.RtkProcessor.RtkResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("5. RTK RTCM3 测试")
public class RtkRtcm3Test {

    private static final Logger log = LoggerFactory.getLogger(RtkRtcm3Test.class);

    private static final String BASE_PATH =
            "D:\\tdengine-jetlinks\\jetlinks-data\\device_rtcmbin_storage\\GS2025090017\\2026-07-20\\12.rtcm3";
    private static final String ROVER_PATH =
            "D:\\tdengine-jetlinks\\jetlinks-data\\device_rtcmbin_storage\\GS2025090010\\2026-07-20\\12.rtcm3";

    private static final String RESULT_DIR = "D:\\code\\rtklib_java\\data\\rtcm3_test";

    @Test
    @DisplayName("RTCM3 RTK 定位测试")
    void testRtcm3Rtk() throws IOException {
        new java.io.File(RESULT_DIR).mkdirs();

        byte[] roverData, baseData;
        try (FileInputStream fis = new FileInputStream(ROVER_PATH)) {
            roverData = fis.readAllBytes();
        }
        try (FileInputStream fis = new FileInputStream(BASE_PATH)) {
            baseData = fis.readAllBytes();
        }
        log.info("========== 加载 RTCM3 数据 ==========");
        log.info("Rover: {} bytes, Base: {} bytes", roverData.length, baseData.length);

        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_KINEMA;
        opt.nf = 2;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL
                | Constants.SYS_CMP | Constants.SYS_QZS;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;
        opt.modear = Constants.ARMODE_OFF;
        opt.dynamics = 0;
        opt.refposmode = Constants.REFPOS_RTCM;
        opt.procmode = Constants.PROCMODE_POST;

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        RtkProcessor processor = new RtkProcessor(opt, null, baos);

        log.info("========== 开始处理 ==========");
        RtkResult result = processor.process(roverData, baseData);

        String posOutput = baos.toString();
        log.info("========== 处理完成 ==========");
        log.info("totalEpochs={}, success={}, fail={}",
                result.totalEpochs, result.successCount, result.failCount);

        String javaResultPath = RESULT_DIR + "\\rtk_java_result.pos";
        try (FileWriter fw = new FileWriter(javaResultPath)) {
            fw.write(posOutput);
        }
        log.info("结果已写入: {}", javaResultPath);

        int posLineCount = 0;
        int fixCount = 0, floatCount = 0, singleCount = 0;
        for (String line : posOutput.split("\n")) {
            if (line.startsWith("%") || line.startsWith("#") || line.trim().isEmpty()) continue;
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 7) {
                posLineCount++;
                try {
                    int q = Integer.parseInt(parts[5]);
                    if (q == Constants.SOLQ_FIX) fixCount++;
                    else if (q == Constants.SOLQ_FLOAT) floatCount++;
                    else if (q == Constants.SOLQ_SINGLE) singleCount++;
                } catch (Exception ignored) {}
            }
        }

        log.info("===== 结果统计 =====");
        log.info("定位历元: {}, Fix: {}, Float: {}, Single: {}",
                posLineCount, fixCount, floatCount, singleCount);
        if (posLineCount > 0) {
            log.info(String.format("Fix率: %.1f%%", 100.0 * fixCount / posLineCount));
        }

        assertTrue(posLineCount > 0, "应至少有一个定位结果");
        assertTrue(result.totalEpochs > 0, "应至少有一个处理历元");
    }
}