package org.rtklib.java;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.PrcOpt;
import org.rtklib.java.rtkpos.RtkProcessor;
import org.rtklib.java.rtkpos.RtkProcessor.RtkResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RTK 优化选项逐项测试")
public class RtkOptimizationTest {

    private static final Logger log = LoggerFactory.getLogger(RtkOptimizationTest.class);

    private static final String BASE_PATH =
            "D:\\tdengine-jetlinks\\jetlinks-data\\device_rtcmbin_storage\\GS2025090017\\2026-07-20\\12.rtcm3";
    private static final String ROVER_PATH =
            "D:\\tdengine-jetlinks\\jetlinks-data\\device_rtcmbin_storage\\GS2025090010\\2026-07-20\\12.rtcm3";

    private static final String RESULT_DIR = "D:\\code\\rtklib_java\\data\\rtcm3_test";

    private static class OptResult {
        String name;
        int totalEpochs;
        int successCount;
        int fixCount;
        int floatCount;
        int singleCount;
        double fixRate;
        double avgLat;
        double avgLon;
        double avgHeight;
        double stdLat;
        double stdLon;
        double stdHeight;

        OptResult(String name) {
            this.name = name;
        }
    }

    @Test
    @DisplayName("逐项开启优化测试")
    void testOptimizationsOneByOne() throws IOException {
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

        // 定义优化配置
        List<Map.Entry<String, java.util.function.Consumer<RtkConfig>>> optimizations = new ArrayList<>();

        // 0. 基准（无优化，AR开启）
        optimizations.add(new AbstractMap.SimpleEntry<>("0_Base_AR", cfg -> {
            cfg.enableParRefReselect = false;
            cfg.enableAdaptiveQ = false;
            cfg.enableIggiii = false;
            cfg.enableSnrMedian = false;
            cfg.enableIonoTropGradient = false;
            cfg.enableAmbAnchor = false;
        }));

        // 1. PAR重选
        optimizations.add(new AbstractMap.SimpleEntry<>("1_PAR_RefReselect", cfg -> {
            cfg.enableParRefReselect = true;
            cfg.enableAdaptiveQ = false;
            cfg.enableIggiii = false;
            cfg.enableSnrMedian = false;
            cfg.enableIonoTropGradient = false;
            cfg.enableAmbAnchor = false;
        }));

        // 2. 自适应Q矩阵
        optimizations.add(new AbstractMap.SimpleEntry<>("2_AdaptiveQ", cfg -> {
            cfg.enableParRefReselect = false;
            cfg.enableAdaptiveQ = true;
            cfg.enableIggiii = false;
            cfg.enableSnrMedian = false;
            cfg.enableIonoTropGradient = false;
            cfg.enableAmbAnchor = false;
        }));

        // 3. IGGIII抗差估计
        optimizations.add(new AbstractMap.SimpleEntry<>("3_IGGIII", cfg -> {
            cfg.enableParRefReselect = false;
            cfg.enableAdaptiveQ = false;
            cfg.enableIggiii = true;
            cfg.enableSnrMedian = false;
            cfg.enableIonoTropGradient = false;
            cfg.enableAmbAnchor = false;
        }));

        // 4. SNR中值滤波
        optimizations.add(new AbstractMap.SimpleEntry<>("4_SNR_Median", cfg -> {
            cfg.enableParRefReselect = false;
            cfg.enableAdaptiveQ = false;
            cfg.enableIggiii = false;
            cfg.enableSnrMedian = true;
            cfg.enableIonoTropGradient = false;
            cfg.enableAmbAnchor = false;
        }));

        // 5. 电离层/对流层梯度
        optimizations.add(new AbstractMap.SimpleEntry<>("5_IonoTropGradient", cfg -> {
            cfg.enableParRefReselect = false;
            cfg.enableAdaptiveQ = false;
            cfg.enableIggiii = false;
            cfg.enableSnrMedian = false;
            cfg.enableIonoTropGradient = true;
            cfg.enableAmbAnchor = false;
        }));

        // 6. 模糊度锚定
        optimizations.add(new AbstractMap.SimpleEntry<>("6_AmbAnchor", cfg -> {
            cfg.enableParRefReselect = false;
            cfg.enableAdaptiveQ = false;
            cfg.enableIggiii = false;
            cfg.enableSnrMedian = false;
            cfg.enableIonoTropGradient = false;
            cfg.enableAmbAnchor = true;
        }));

        // 7. 全部优化
        optimizations.add(new AbstractMap.SimpleEntry<>("7_AllOptimizations", cfg -> {
            cfg.enableParRefReselect = true;
            cfg.enableAdaptiveQ = true;
            cfg.enableIggiii = true;
            cfg.enableSnrMedian = true;
            cfg.enableIonoTropGradient = true;
            cfg.enableAmbAnchor = true;
        }));

        List<OptResult> results = new ArrayList<>();

        for (var entry : optimizations) {
            String name = entry.getKey();
            java.util.function.Consumer<RtkConfig> configurer = entry.getValue();

            log.info("\n========== 测试: {} ==========", name);

            PrcOpt opt = new PrcOpt();
            opt.mode = Constants.PMODE_KINEMA;
            opt.nf = 2;
            opt.navsys = Constants.SYS_CMP;
            opt.elmin = 15.0 * Constants.D2R;
            opt.ionoopt = Constants.IONOOPT_BRDC;
            opt.tropopt = Constants.TROPOPT_SAAS;
            opt.modear = Constants.ARMODE_FIXHOLD;
            opt.dynamics = 0;
            opt.refposmode = Constants.REFPOS_RTCM;
            opt.procmode = Constants.PROCMODE_POST;

            RtkConfig config = new RtkConfig();
            configurer.accept(config);

            String resultFile = RESULT_DIR + "\\rtk_" + name + ".pos";
            try (FileOutputStream fos = new FileOutputStream(resultFile)) {
                RtkProcessor processor = new RtkProcessor(opt, null, fos);
                processor.getRtk().rtkConfig = config;
                RtkResult result = processor.process(roverData, baseData);
            }
            
            String posOutput = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(resultFile)));
            OptResult optResult = analyzeResult(name, posOutput);
            results.add(optResult);

            log.info("结果已写入: {}", resultFile);
        }

        // 打印对比表格
        log.info("\n========== 优化对比结果 ==========");
        log.info(String.format("%-25s %8s %8s %8s %8s %8s %10s %12s %12s %12s",
                "配置", "总历元", "成功", "Fix", "Float", "Single", "Fix率(%)", "平均Lat", "平均Lon", "平均H(m)"));
        log.info(String.join("", Collections.nCopies(120, "-")));

        for (OptResult r : results) {
            log.info(String.format("%-25s %8d %8d %8d %8d %8d %10.1f %12.8f %12.8f %12.3f",
                    r.name, r.totalEpochs, r.successCount, r.fixCount, r.floatCount, r.singleCount,
                    r.fixRate, r.avgLat, r.avgLon, r.avgHeight));
        }

        assertTrue(results.size() > 0, "应至少有一个测试结果");
    }

    private OptResult analyzeResult(String name, String posOutput) {
        OptResult optResult = new OptResult(name);

        List<Double> lats = new ArrayList<>();
        List<Double> lons = new ArrayList<>();
        List<Double> heights = new ArrayList<>();

        for (String line : posOutput.split("\n")) {
            if (line.startsWith("%") || line.startsWith("#") || line.trim().isEmpty()) continue;
            String[] parts = line.trim().split("\\s+");
            if (parts.length >= 7) {
                try {
                    int q = Integer.parseInt(parts[5]);
                    if (q == Constants.SOLQ_FIX) optResult.fixCount++;
                    else if (q == Constants.SOLQ_FLOAT) optResult.floatCount++;
                    else if (q == Constants.SOLQ_SINGLE) optResult.singleCount++;

                    double lat = Double.parseDouble(parts[2]);
                    double lon = Double.parseDouble(parts[3]);
                    double h = Double.parseDouble(parts[4]);
                    lats.add(lat);
                    lons.add(lon);
                    heights.add(h);
                } catch (Exception ignored) {}
            }
        }

        int total = optResult.fixCount + optResult.floatCount + optResult.singleCount;
        optResult.successCount = total;
        optResult.totalEpochs = total;
        optResult.fixRate = total > 0 ? 100.0 * optResult.fixCount / total : 0.0;

        if (!lats.isEmpty()) {
            optResult.avgLat = lats.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            optResult.avgLon = lons.stream().mapToDouble(Double::doubleValue).average().orElse(0);
            optResult.avgHeight = heights.stream().mapToDouble(Double::doubleValue).average().orElse(0);

            optResult.stdLat = std(lats, optResult.avgLat);
            optResult.stdLon = std(lons, optResult.avgLon);
            optResult.stdHeight = std(heights, optResult.avgHeight);
        }

        log.info("===== {} 结果统计 =====", name);
        log.info("定位历元: {}, Fix: {}, Float: {}, Single: {}",
                total, optResult.fixCount, optResult.floatCount, optResult.singleCount);
        log.info(String.format("Fix率: %.1f%%", optResult.fixRate));
        log.info(String.format("平均位置: Lat=%.8f, Lon=%.8f, H=%.3f",
                optResult.avgLat, optResult.avgLon, optResult.avgHeight));
        log.info(String.format("标准差: Lat=%.8f, Lon=%.8f, H=%.3f",
                optResult.stdLat, optResult.stdLon, optResult.stdHeight));

        return optResult;
    }

    private double std(List<Double> values, double mean) {
        if (values.size() < 2) return 0;
        double sumSq = values.stream().mapToDouble(v -> Math.pow(v - mean, 2)).sum();
        return Math.sqrt(sumSq / (values.size() - 1));
    }
}