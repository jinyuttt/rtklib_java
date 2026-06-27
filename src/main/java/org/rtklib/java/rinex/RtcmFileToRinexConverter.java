package org.rtklib.java.rinex;

import org.rtklib.java.data.Sta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * RTCM文件转RINEX文件转换器。
 * <p>
 * 输入RTCM文件路径，输出RINEX 3.x格式的观测文件(.obs)和导航文件(.nav)。
 * </p>
 *
 * <pre>
 * // 示例：RTCM文件转RINEX
 * RtcmFileToRinexConverter converter = new RtcmFileToRinexConverter(3.05, "D:/output", "ROVER");
 * boolean ok = converter.convert("D:/data/1.rtcm3");
 *
 * // 或使用便捷方法
 * boolean ok = RtcmFileToRinexConverter.convertFile("D:/data/1.rtcm3", 3.05, "D:/output", "ROVER");
 * </pre>
 */
public class RtcmFileToRinexConverter {

    private static final Logger log = LoggerFactory.getLogger(RtcmFileToRinexConverter.class);

    private final double version;
    private final String outputDir;
    private final String stationName;

    /**
     * 构造RTCM文件转RINEX转换器。
     *
     * @param version     RINEX版本号（3.05或3.06）
     * @param outputDir   输出目录
     * @param stationName 站名（用于生成文件名，如"ROVER"生成ROVER.obs和ROVER.nav）
     */
    public RtcmFileToRinexConverter(double version, String outputDir, String stationName) {
        this.version = version;
        this.outputDir = outputDir;
        this.stationName = stationName;
    }

    /**
     * 转换RTCM文件为RINEX文件。
     *
     * @param rtcmFilePath RTCM文件路径
     * @return 转换是否成功
     * @throws IOException 文件读取失败
     */
    public boolean convert(String rtcmFilePath) throws IOException {
        byte[] rtcmData;
        try (FileInputStream fis = new FileInputStream(rtcmFilePath)) {
            rtcmData = fis.readAllBytes();
        }
        log.info("Read RTCM file: {} ({} bytes)", rtcmFilePath, rtcmData.length);

        Files.createDirectories(Path.of(outputDir));

        RtcmToRinexConverter converter = new RtcmToRinexConverter(version, outputDir, stationName);
        boolean ok = converter.convert(rtcmData, rtcmData.length);

        if (ok) {
            log.info("RINEX files generated: {}/{}.obs, {}/{}.nav", outputDir, stationName, outputDir, stationName);
        } else {
            log.warn("RTCM to RINEX conversion failed for: {}", rtcmFilePath);
        }
        return ok;
    }

    /**
     * 便捷方法：转换RTCM文件为RINEX文件。
     *
     * @param rtcmFilePath RTCM文件路径
     * @param version      RINEX版本号
     * @param outputDir    输出目录
     * @param stationName  站名
     * @return 转换是否成功
     * @throws IOException 文件读取失败
     */
    public static boolean convertFile(String rtcmFilePath, double version, String outputDir, String stationName) throws IOException {
        RtcmFileToRinexConverter converter = new RtcmFileToRinexConverter(version, outputDir, stationName);
        return converter.convert(rtcmFilePath);
    }

    /**
     * 获取生成的RINEX观测文件路径。
     */
    public String getObsFilePath() {
        return Paths.get(outputDir, stationName + ".obs").toString();
    }

    /**
     * 获取生成的RINEX导航文件路径。
     */
    public String getNavFilePath() {
        return Paths.get(outputDir, stationName + ".nav").toString();
    }
}