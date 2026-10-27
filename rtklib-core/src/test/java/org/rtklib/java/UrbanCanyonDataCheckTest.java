package org.rtklib.java;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.rinex.RinexParser;
import org.rtklib.java.rinex.RinexSppProcessor;
import org.rtklib.java.rinex.RinexRtkProcessor;
import org.rtklib.java.rtkpos.RtkProcessor;
import org.rtklib.java.coord.CoordTransform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Urban Canyon Data Check - RINEX数据检查 + SPP分析")
@Tag("manual")
public class UrbanCanyonDataCheckTest {

    private static final Logger log = LoggerFactory.getLogger(UrbanCanyonDataCheckTest.class);

    private static final String BASE = "D:/code/rtklib_java/test-data";

    static class RinexInfo {
        String name;
        String filePath;
        double version;
        char fileType;
        String markerName;
        String recType;
        double[] approxXyz;
        double interval;
        String timeFirst;
        String timeLast;
        int numSats;
        Map<Character, Integer> obsCountBySys;
        Map<Character, List<String>> obsTypesBySys;
        boolean parsed;
        String error;
    }

    @Test
    @DisplayName("1. 检查所有RINEX文件头信息")
    void testCheckAllRinexHeaders() throws Exception {
        List<String[]> fileSets = List.of(
                new String[]{"Net_Diff Urban", BASE + "/rinex/urban_rover_20000719.17o", BASE + "/rinex/urban_base_20000719.17o", BASE + "/nav/urban_20000719.17p"},
                new String[]{"PPC Tokyo run1", BASE + "/ppc/PPC-Dataset/tokyo/run1/rover.obs", BASE + "/ppc/PPC-Dataset/tokyo/run1/base.obs", BASE + "/ppc/PPC-Dataset/tokyo/run1/base.nav"},
                new String[]{"PPC Tokyo run2", BASE + "/ppc/PPC-Dataset/tokyo/run2/rover.obs", BASE + "/ppc/PPC-Dataset/tokyo/run2/base.obs", BASE + "/ppc/PPC-Dataset/tokyo/run2/base.nav"},
                new String[]{"PPC Tokyo run3", BASE + "/ppc/PPC-Dataset/tokyo/run3/rover.obs", BASE + "/ppc/PPC-Dataset/tokyo/run3/base.obs", BASE + "/ppc/PPC-Dataset/tokyo/run3/base.nav"},
                new String[]{"PPC Nagoya run1", BASE + "/ppc/PPC-Dataset/nagoya/run1/rover.obs", BASE + "/ppc/PPC-Dataset/nagoya/run1/base.obs", BASE + "/ppc/PPC-Dataset/nagoya/run1/base.nav"},
                new String[]{"PPC Nagoya run2", BASE + "/ppc/PPC-Dataset/nagoya/run2/rover.obs", BASE + "/ppc/PPC-Dataset/nagoya/run2/base.obs", BASE + "/ppc/PPC-Dataset/nagoya/run2/base.nav"},
                new String[]{"PPC Nagoya run3", BASE + "/ppc/PPC-Dataset/nagoya/run3/rover.obs", BASE + "/ppc/PPC-Dataset/nagoya/run3/base.obs", BASE + "/ppc/PPC-Dataset/nagoya/run3/base.nav"},
                new String[]{"UrbanNav Tokyo", BASE + "/urbannav/tokyo/data_tokyo/tokyo3530.18o", BASE + "/urbannav/tokyo/data_tokyo/tokyo3530_base.18o", BASE + "/urbannav/tokyo/data_tokyo/brdm3530.18p"}
        );

        log.info("");
        log.info("╔════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════╗");
        log.info("║                            RINEX 数据文件检查报告                                                                              ║");
        log.info("╚════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════╝");

        for (String[] set : fileSets) {
            String name = set[0];
            String roverObs = set[1];
            String baseObs = set[2];
            String nav = set[3];

            log.info("");
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");
            log.info("  数据集: {}", name);
            log.info("━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━");

            boolean roverExists = Files.exists(Paths.get(roverObs));
            boolean baseExists = Files.exists(Paths.get(baseObs));
            boolean navExists = Files.exists(Paths.get(nav));

            log.info("  Rover存在: {}, Base存在: {}, Nav存在: {}", roverExists, baseExists, navExists);

            if (!roverExists || !baseExists || !navExists) {
                log.warn("  ⚠ 数据不完整，跳过");
                continue;
            }

            log.info("  Rover文件大小: {} MB", String.format("%.1f", Files.size(Paths.get(roverObs)) / 1e6));
            log.info("  Base文件大小: {} MB", String.format("%.1f", Files.size(Paths.get(baseObs)) / 1e6));
            log.info("  Nav文件大小:  {} MB", String.format("%.1f", Files.size(Paths.get(nav)) / 1e6));

            RinexInfo roverInfo = parseRinexHeader("Rover", roverObs);
            RinexInfo baseInfo = parseRinexHeader("Base", baseObs);
            RinexInfo navInfo = parseRinexHeader("Nav", nav);

            printRinexInfo(roverInfo);
            printRinexInfo(baseInfo);
            printRinexInfo(navInfo);

            checkTimeOverlap(roverInfo, baseInfo);
            checkBaseline(roverInfo, baseInfo);
            checkObsTypeCompatibility(roverInfo, baseInfo);
        }
    }

    @Test
    @DisplayName("2. SPP分析所有rover数据")
    void testSppAllRovers() {
        List<String[]> fileSets = List.of(
                new String[]{"Net_Diff Urban", BASE + "/rinex/urban_rover_20000719.17o", BASE + "/nav/urban_20000719.17p"},
                new String[]{"PPC Tokyo run1", BASE + "/ppc/PPC-Dataset/tokyo/run1/rover.obs", BASE + "/ppc/PPC-Dataset/tokyo/run1/base.nav"},
                new String[]{"PPC Tokyo run2", BASE + "/ppc/PPC-Dataset/tokyo/run2/rover.obs", BASE + "/ppc/PPC-Dataset/tokyo/run2/base.nav"},
                new String[]{"PPC Tokyo run3", BASE + "/ppc/PPC-Dataset/tokyo/run3/rover.obs", BASE + "/ppc/PPC-Dataset/tokyo/run3/base.nav"},
                new String[]{"PPC Nagoya run1", BASE + "/ppc/PPC-Dataset/nagoya/run1/rover.obs", BASE + "/ppc/PPC-Dataset/nagoya/run1/base.nav"},
                new String[]{"PPC Nagoya run2", BASE + "/ppc/PPC-Dataset/nagoya/run2/rover.obs", BASE + "/ppc/PPC-Dataset/nagoya/run2/base.nav"},
                new String[]{"PPC Nagoya run3", BASE + "/ppc/PPC-Dataset/nagoya/run3/rover.obs", BASE + "/ppc/PPC-Dataset/nagoya/run3/base.nav"},
                new String[]{"UrbanNav Tokyo", BASE + "/urbannav/tokyo/data_tokyo/tokyo3530.18o", BASE + "/urbannav/tokyo/data_tokyo/brdm3530.18p"}
        );

        log.info("");
        log.info("╔════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════╗");
        log.info("║                            SPP 分析报告 (Rover单点定位)                                                                        ║");
        log.info("╚════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════════╝");

        for (String[] set : fileSets) {
            String name = set[0];
            String roverObs = set[1];
            String nav = set[2];

            if (!Files.exists(Paths.get(roverObs)) || !Files.exists(Paths.get(nav))) {
                log.warn("[{}] 数据不存在，跳过SPP", name);
                continue;
            }

            runSppAndReport(name, roverObs, nav);
        }
    }

    @Test
    @DisplayName("3. RTK Float解详细分析 - Net_Diff Urban")
    void testRtkFloatDetailNetDiff() {
        String roverObs = BASE + "/rinex/urban_rover_20000719.17o";
        String baseObs = BASE + "/rinex/urban_base_20000719.17o";
        String nav = BASE + "/nav/urban_20000719.17p";

        Assumptions.assumeTrue(Files.exists(Paths.get(roverObs)), "rover数据不存在");

        runRtkFloatDiag("Net_Diff Urban", roverObs, baseObs, nav);
    }

    @Test
    @DisplayName("4. RTK Float解详细分析 - PPC Tokyo run1")
    void testRtkFloatDetailTokyoRun1() {
        String roverObs = BASE + "/ppc/PPC-Dataset/tokyo/run1/rover.obs";
        String baseObs = BASE + "/ppc/PPC-Dataset/tokyo/run1/base.obs";
        String nav = BASE + "/ppc/PPC-Dataset/tokyo/run1/base.nav";

        Assumptions.assumeTrue(Files.exists(Paths.get(roverObs)), "rover数据不存在");

        runRtkFloatDiag("PPC Tokyo run1", roverObs, baseObs, nav);
    }

    @Test
    @DisplayName("5. RTK Float解详细分析 - PPC Nagoya run1")
    void testRtkFloatDetailNagoyaRun1() {
        String roverObs = BASE + "/ppc/PPC-Dataset/nagoya/run1/rover.obs";
        String baseObs = BASE + "/ppc/PPC-Dataset/nagoya/run1/base.obs";
        String nav = BASE + "/ppc/PPC-Dataset/nagoya/run1/base.nav";

        Assumptions.assumeTrue(Files.exists(Paths.get(roverObs)), "rover数据不存在");

        runRtkFloatDiag("PPC Nagoya run1", roverObs, baseObs, nav);
    }

    @Test
    @DisplayName("6. RTK Float解详细分析 - UrbanNav Tokyo")
    void testRtkFloatDetailUrbanNav() {
        String roverObs = BASE + "/urbannav/tokyo/data_tokyo/tokyo3530.18o";
        String baseObs = BASE + "/urbannav/tokyo/data_tokyo/tokyo3530_base.18o";
        String nav = BASE + "/urbannav/tokyo/data_tokyo/brdm3530.18p";

        Assumptions.assumeTrue(Files.exists(Paths.get(roverObs)), "rover数据不存在");

        runRtkFloatDiag("UrbanNav Tokyo", roverObs, baseObs, nav);
    }

    private void runRtkFloatDiag(String name, String roverObs, String baseObs, String nav) {
        PrcOpt opt = RinexRtkProcessor.createDefaultOpt();
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_GLO | Constants.SYS_CMP | Constants.SYS_QZS;
        opt.nf = 2;
        opt.modear = Constants.ARMODE_FIXHOLD;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_SAAS;
        opt.refpos = Constants.POSOPT_POS_XYZ;

        RtkProcessor.RtkResult result;
        try {
            result = RinexRtkProcessor.processRinex(roverObs, baseObs, nav, opt);
        } catch (Exception e) {
            log.error("[{}] RTK处理异常: {}", name, e.getMessage());
            return;
        }

        List<SolData> sols = result.solutions;
        int total = result.totalEpochs;

        int fixCount = 0, floatCount = 0, singleCount = 0, noneCount = 0;
        double sumFloatLat = 0, sumFloatLon = 0, sumFloatH = 0;
        double minFloatLat = 999, maxFloatLat = -999;
        double minFloatLon = 999, maxFloatLon = -999;
        double sumFloatSigma = 0;
        int floatSigmaCount = 0;
        int floatWithGoodPos = 0;

        double sumFixLat = 0, sumFixLon = 0;

        for (SolData sol : sols) {
            if (sol.status == SolutionStatus.FIX) {
                fixCount++;
                Position llh = sol.getPosition(CoordType.LLH);
                if (llh != null) {
                    sumFixLat += llh.v1;
                    sumFixLon += llh.v2;
                }
            } else if (sol.status == SolutionStatus.FLOAT) {
                floatCount++;
                Position llh = sol.getPosition(CoordType.LLH);
                if (llh == null) continue;
                double lat = llh.v1;
                double lon = llh.v2;
                double h = llh.v3;
                sumFloatLat += lat;
                sumFloatLon += lon;
                sumFloatH += h;
                minFloatLat = Math.min(minFloatLat, lat);
                maxFloatLat = Math.max(maxFloatLat, lat);
                minFloatLon = Math.min(minFloatLon, lon);
                maxFloatLon = Math.max(maxFloatLon, lon);

                Accuracy acc = sol.getAccuracy(CoordType.LLH);
                if (acc != null) {
                    sumFloatSigma += Math.sqrt(acc.s1 * acc.s1 + acc.s2 * acc.s2);
                    floatSigmaCount++;
                }

                if (lat > 20 && lat < 50 && lon > 120 && lon < 160) {
                    floatWithGoodPos++;
                }
            } else if (sol.status == SolutionStatus.SINGLE) {
                singleCount++;
            } else {
                noneCount++;
            }
        }

        log.info("");
        log.info("┌─────────────────────────────────────────────────────────┐");
        log.info("│  RTK Float解详细分析: {}", name);
        log.info("├─────────────────────────────────────────────────────────┤");
        log.info("│  总历元: {}, Fix: {} ({})  Float: {} ({})  Single: {} ({})  None: {} ({})",
                total, fixCount, pct(fixCount, total), floatCount, pct(floatCount, total),
                singleCount, pct(singleCount, total), noneCount, pct(noneCount, total));

        if (floatCount > 0) {
            double avgLat = sumFloatLat / floatCount;
            double avgLon = sumFloatLon / floatCount;
            double avgH = sumFloatH / floatCount;

            log.info("├─────── Float解坐标统计 ────────────────────────────────┤");
            log.info("│  平均纬度:   {}°", String.format("%.6f", avgLat));
            log.info("│  平均经度:   {}°", String.format("%.6f", avgLon));
            log.info("│  平均高度:   {} m", String.format("%.2f", avgH));
            log.info("│  纬度范围:   {}° ~ {}° (跨度{})",
                    String.format("%.6f", minFloatLat), String.format("%.6f", maxFloatLat),
                    String.format("%.6f", maxFloatLat - minFloatLat));
            log.info("│  经度范围:   {}° ~ {}° (跨度{})",
                    String.format("%.6f", minFloatLon), String.format("%.6f", maxFloatLon),
                    String.format("%.6f", maxFloatLon - minFloatLon));
            if (floatSigmaCount > 0) {
                log.info("│  平均2D sigma: {} m", String.format("%.3f", sumFloatSigma / floatSigmaCount));
            }
            log.info("│  坐标合理的Float历元: {} / {} ({})",
                    floatWithGoodPos, floatCount, pct(floatWithGoodPos, floatCount));

            boolean latOk = avgLat > 20 && avgLat < 50;
            boolean lonOk = avgLon > 120 && avgLon < 160;
            if (latOk && lonOk) {
                log.info("│  ✓ Float解坐标在日本范围内，定位基本正常");
            } else {
                log.warn("│  ✗ Float解坐标异常！不在日本范围内");
                if (avgLon < 0 || avgLon > 360) {
                    log.warn("│    经度 {}° 疑似坐标系问题(可能需要+360或取模)", String.format("%.2f", avgLon));
                }
            }
        }

        if (fixCount > 0) {
            log.info("├─────── Fix解坐标 ──────────────────────────────────────┤");
            log.info("│  Fix平均纬度: {}°", String.format("%.6f", sumFixLat / fixCount));
            log.info("│  Fix平均经度: {}°", String.format("%.6f", sumFixLon / fixCount));
        }

        log.info("└─────────────────────────────────────────────────────────┘");
    }

    private void runSppAndReport(String name, String obsFile, String navFile) {
        PrcOpt opt = RinexSppProcessor.createDefaultOpt();
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_GLO | Constants.SYS_CMP | Constants.SYS_QZS;
        opt.nf = 2;
        opt.elmin = 15.0 * Constants.D2R;

        RinexSppProcessor.SppResult result;
        try {
            result = RinexSppProcessor.processRinex(obsFile, navFile, opt);
        } catch (Exception e) {
            log.error("[{}] SPP处理异常: {}", name, e.getMessage());
            return;
        }

        List<SolData> sols = result.solutions;
        int total = result.totalEpochs;
        int success = result.successCount;

        double sumLat = 0, sumLon = 0, sumH = 0;
        double minLat = 999, maxLat = -999, minLon = 999, maxLon = -999;
        int validPos = 0;
        double sumNs = 0;
        int nsCount = 0;

        for (SolData sol : sols) {
            if (sol.status == SolutionStatus.SINGLE) {
                Position llh = sol.getPosition(CoordType.LLH);
                if (llh == null) continue;
                double lat = llh.v1;
                double lon = llh.v2;
                double h = llh.v3;
                sumLat += lat;
                sumLon += lon;
                sumH += h;
                minLat = Math.min(minLat, lat);
                maxLat = Math.max(maxLat, lat);
                minLon = Math.min(minLon, lon);
                maxLon = Math.max(maxLon, lon);
                if (lat > 20 && lat < 50 && lon > 120 && lon < 160) validPos++;
                if (sol.numSat > 0) { sumNs += sol.numSat; nsCount++; }
            }
        }

        log.info("");
        log.info("┌─────────────────────────────────────────────────────────┐");
        log.info("│  SPP报告: {}", name);
        log.info("├─────────────────────────────────────────────────────────┤");
        log.info("│  总历元: {}, 成功: {} ({}), 失败: {} ({})",
                total, success, pct(success, total), total - success, pct(total - success, total));

        if (success > 0) {
            log.info("│  平均纬度:   {}°", String.format("%.6f", sumLat / success));
            log.info("│  平均经度:   {}°", String.format("%.6f", sumLon / success));
            log.info("│  平均高度:   {} m", String.format("%.2f", sumH / success));
            log.info("│  纬度范围:   {}° ~ {}°", String.format("%.4f", minLat), String.format("%.4f", maxLat));
            log.info("│  经度范围:   {}° ~ {}°", String.format("%.4f", minLon), String.format("%.4f", maxLon));
            log.info("│  坐标合理历元: {} / {} ({})", validPos, success, pct(validPos, success));
            if (nsCount > 0) {
                log.info("│  平均使用卫星数: {}", String.format("%.1f", sumNs / nsCount));
            }
            boolean latOk = (sumLat / success) > 20 && (sumLat / success) < 50;
            boolean lonOk = (sumLon / success) > 120 && (sumLon / success) < 160;
            if (latOk && lonOk) {
                log.info("│  ✓ SPP坐标在日本范围内，基本数据质量OK");
            } else {
                log.warn("│  ✗ SPP坐标异常！不在日本范围内");
            }
        } else {
            log.warn("│  ✗ SPP完全失败，0个成功历元！数据或星历可能有问题");
        }
        log.info("└─────────────────────────────────────────────────────────┘");
    }

    private RinexInfo parseRinexHeader(String label, String filePath) {
        RinexInfo info = new RinexInfo();
        info.name = label;
        info.filePath = filePath;
        info.obsCountBySys = new LinkedHashMap<>();
        info.obsTypesBySys = new LinkedHashMap<>();

        try (BufferedReader br = new BufferedReader(new FileReader(filePath))) {
            String line;
            while ((line = br.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.contains("END OF HEADER")) break;

                if (line.contains("RINEX VERSION / TYPE")) {
                    info.version = Double.parseDouble(line.substring(0, 20).trim());
                    info.fileType = line.substring(20, 40).trim().charAt(0);
                } else if (line.contains("MARKER NAME")) {
                    info.markerName = line.substring(0, 60).trim();
                } else if (line.contains("REC # / TYPE / VERS")) {
                    info.recType = line.substring(20, 40).trim();
                } else if (line.contains("APPROX POSITION XYZ")) {
                    try {
                        String[] parts = line.substring(0, 60).trim().split("\\s+");
                        if (parts.length >= 3) {
                            info.approxXyz = new double[]{
                                    Double.parseDouble(parts[0]),
                                    Double.parseDouble(parts[1]),
                                    Double.parseDouble(parts[2])
                            };
                        }
                    } catch (NumberFormatException ignored) {}
                } else if (line.contains("INTERVAL")) {
                    info.interval = Double.parseDouble(line.substring(0, 20).trim());
                } else if (line.contains("TIME OF FIRST OBS")) {
                    info.timeFirst = line.substring(0, 60).trim();
                } else if (line.contains("TIME OF LAST OBS")) {
                    info.timeLast = line.substring(0, 60).trim();
                } else if (line.contains("# OF SATELLITES")) {
                    info.numSats = Integer.parseInt(line.substring(0, 10).trim());
                } else if (line.contains("SYS / # / OBS TYPES")) {
                    char sys = line.charAt(0);
                    String numStr = line.substring(3, 6).trim();
                    if (numStr.isEmpty()) continue;
                    int numObs = Integer.parseInt(numStr);
                    info.obsCountBySys.merge(sys, numObs, Integer::sum);
                    List<String> types = info.obsTypesBySys.computeIfAbsent(sys, k -> new ArrayList<>());
                    for (int i = 0; i < Math.min(numObs, 13); i++) {
                        int start = 7 + i * 4;
                        if (start + 3 <= line.length()) {
                            String type = line.substring(start, start + 3).trim();
                            if (!type.isEmpty()) types.add(type);
                        }
                    }
                }
            }
            info.parsed = true;
        } catch (Exception e) {
            info.parsed = false;
            info.error = e.getMessage();
        }
        return info;
    }

    private void printRinexInfo(RinexInfo info) {
        log.info("  ┌── {} ── {} ──", info.name, new File(info.filePath).getName());
        if (!info.parsed) {
            log.error("  │  解析失败: {}", info.error);
            log.info("  └──");
            return;
        }
        log.info("  │  RINEX版本: {}, 类型: {}", info.version, info.fileType);
        log.info("  │  站名: {}, 接收机: {}", info.markerName, info.recType);
        if (info.approxXyz != null) {
            double[] pos = new double[3];
            CoordTransform.ecef2pos(info.approxXyz, pos);
            double lat = pos[0] * Constants.R2D;
            double lon = pos[1] * Constants.R2D;
            double h = pos[2];
            log.info("  │  近似位置: {}°N, {}°E, {}m", String.format("%.6f", lat), String.format("%.6f", lon), String.format("%.2f", h));
        }
        log.info("  │  采样间隔: {}s", info.interval);
        log.info("  │  起始时间: {}", info.timeFirst);
        log.info("  │  结束时间: {}", info.timeLast);
        log.info("  │  卫星数: {}", info.numSats);
        if (!info.obsCountBySys.isEmpty()) {
            String sysInfo = info.obsCountBySys.entrySet().stream()
                    .map(e -> e.getKey() + "(" + e.getValue() + ")")
                    .collect(Collectors.joining(", "));
            log.info("  │  观测类型数: {}", sysInfo);

            for (Map.Entry<Character, List<String>> entry : info.obsTypesBySys.entrySet()) {
                char sys = entry.getKey();
                List<String> types = entry.getValue();
                String codeTypes = types.stream().filter(t -> t.startsWith("C")).collect(Collectors.joining(","));
                String phaseTypes = types.stream().filter(t -> t.startsWith("L")).collect(Collectors.joining(","));
                log.info("  │    {} 伪距: {}", sys, codeTypes);
                log.info("  │    {} 载波: {}", sys, phaseTypes);
            }
        }
        log.info("  └──");
    }

    private void checkTimeOverlap(RinexInfo rover, RinexInfo base) {
        if (rover.timeFirst == null || base.timeFirst == null) return;
        log.info("  ⏱ Rover时间: {} ~ {}", rover.timeFirst, rover.timeLast);
        log.info("  ⏱ Base时间:  {} ~ {}", base.timeFirst, base.timeLast);

        if (rover.interval > 0 && base.interval > 0) {
            if (Math.abs(rover.interval - base.interval) > 0.01 && Math.min(rover.interval, base.interval) > 0) {
                log.warn("  ⚠ 采样率不同！Rover={}s, Base={}s (RTK可处理但需内插)", rover.interval, base.interval);
            } else {
                log.info("  ✓ 采样率一致: {}s", rover.interval);
            }
        }
    }

    private void checkBaseline(RinexInfo rover, RinexInfo base) {
        if (rover.approxXyz == null || base.approxXyz == null) return;
        double dx = rover.approxXyz[0] - base.approxXyz[0];
        double dy = rover.approxXyz[1] - base.approxXyz[1];
        double dz = rover.approxXyz[2] - base.approxXyz[2];
        double baseline = Math.sqrt(dx * dx + dy * dy + dz * dz);
        log.info("  📏 基线长度: {} m", String.format("%.1f", baseline));
        if (baseline > 20000) {
            log.warn("  ⚠ 基线>20km！中长基线RTK需要电离层建模");
        } else if (baseline > 10000) {
            log.warn("  ⚠ 基线>10km，短基线RTK精度可能下降");
        } else {
            log.info("  ✓ 基线<10km，适合短基线RTK");
        }
    }

    private void checkObsTypeCompatibility(RinexInfo rover, RinexInfo base) {
        log.info("  🔍 观测类型兼容性检查:");
        Set<Character> commonSys = new LinkedHashSet<>(rover.obsCountBySys.keySet());
        commonSys.retainAll(base.obsCountBySys.keySet());

        for (char sys : commonSys) {
            List<String> roverCode = rover.obsTypesBySys.getOrDefault(sys, List.of()).stream()
                    .filter(t -> t.startsWith("C")).toList();
            List<String> baseCode = base.obsTypesBySys.getOrDefault(sys, List.of()).stream()
                    .filter(t -> t.startsWith("C")).toList();
            List<String> roverPhase = rover.obsTypesBySys.getOrDefault(sys, List.of()).stream()
                    .filter(t -> t.startsWith("L")).toList();
            List<String> basePhase = base.obsTypesBySys.getOrDefault(sys, List.of()).stream()
                    .filter(t -> t.startsWith("L")).toList();

            Set<String> commonCode = new LinkedHashSet<>(roverCode);
            commonCode.retainAll(baseCode);
            Set<String> commonPhase = new LinkedHashSet<>(roverPhase);
            commonPhase.retainAll(basePhase);

            if (commonCode.size() >= 2 && commonPhase.size() >= 2) {
                log.info("    {} ✓ 双频兼容 (公共伪距: {}, 公共载波: {})", sys, commonCode, commonPhase);
            } else if (commonCode.size() >= 1 && commonPhase.size() >= 1) {
                log.warn("    {} ⚠ 仅单频兼容 (公共伪距: {}, 公共载波: {})", sys, commonCode, commonPhase);
            } else {
                log.error("    {} ✗ 无兼容观测类型！Rover伪距:{} 载波:{} | Base伪距:{} 载波:{}",
                        sys, roverCode, roverPhase, baseCode, basePhase);
            }
        }

        Set<Character> roverOnly = new LinkedHashSet<>(rover.obsCountBySys.keySet());
        roverOnly.removeAll(base.obsCountBySys.keySet());
        if (!roverOnly.isEmpty()) {
            log.warn("    ⚠ Rover有但Base无的系统: {} (这些系统无法用于差分)", roverOnly);
        }
        Set<Character> baseOnly = new LinkedHashSet<>(base.obsCountBySys.keySet());
        baseOnly.removeAll(rover.obsCountBySys.keySet());
        if (!baseOnly.isEmpty()) {
            log.warn("    ⚠ Base有但Rover无的系统: {} (这些系统无法用于差分)", baseOnly);
        }
    }

    private static String pct(int part, int total) {
        if (total == 0) return "0.0%";
        return String.format("%.1f%%", part * 100.0 / total);
    }
}