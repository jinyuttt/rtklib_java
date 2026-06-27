package org.rtklib.java;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.io.TempDir;
import org.rtklib.java.data.Nav;
import org.rtklib.java.data.Obs;
import org.rtklib.java.data.Obsd;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.GTime;
import org.rtklib.java.rinex.RtcmToRinexConverter;
import org.rtklib.java.rtcm.Rtcm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;


/**
 * Test RTCM to RINEX conversion.
 * Converts RTCM3 binary data to RINEX observation and navigation files,
 * then verifies the generated files.
 */
@DisplayName("RINEX Conversion Tests")
public class RinexConversionTest {

    private static final Logger log = LoggerFactory.getLogger(RinexConversionTest.class);

    private static final String ROVER_PATH =
            "C:\\Users\\admin\\Desktop\\540423494727\\2026-06-08\\1.rtcm3";

    private static final String BASE_PATH =
            "C:\\Users\\admin\\Desktop\\540423496360\\2026-06-08\\1.rtcm3";

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
        log.info("Loaded rover: {} bytes, base: {} bytes", roverData.length, baseData.length);
    }

    static boolean isObsType(int type) {
        return (type >= 1001 && type <= 1004)
                || (type >= 1074 && type <= 1077)
                || (type >= 1084 && type <= 1087)
                || (type >= 1094 && type <= 1097)
                || (type >= 1104 && type <= 1107)
                || (type >= 1114 && type <= 1117)
                || (type >= 1124 && type <= 1127)
                || (type >= 1134 && type <= 1137);
    }

    /**
     * Test RTCM to RINEX conversion for rover station.
     * Generates RINEX obs and nav files from RTCM3 data.
     */
    @Test
    @DisplayName("RTCM to RINEX rover conversion")
    void testRoverRtcmToRinex(@TempDir Path tempDir) throws IOException {
        String outputDir = tempDir.toString();
        String stationName = "ROVER";
        RtcmToRinexConverter converter = new RtcmToRinexConverter(3.05, outputDir, stationName);

        boolean result = converter.convert(roverData, roverData.length);
        assertTrue(result, "RTCM to RINEX conversion should succeed");

        Path obsFile = Path.of(outputDir).resolve("ROVER.obs");
        Path navFile = Path.of(outputDir).resolve("ROVER.nav");

        assertTrue(Files.exists(obsFile), "RINEX observation file should exist");
        long obsSize = Files.size(obsFile);
        assertTrue(obsSize > 0, "RINEX observation file should not be empty");
        log.info("Rover observation file: {} bytes", obsSize);

        assertTrue(Files.exists(navFile), "RINEX navigation file should exist");
        long navSize = Files.size(navFile);
        assertTrue(navSize > 0, "RINEX navigation file should not be empty");
        log.info("Rover navigation file: {} bytes", navSize);

        String obsContent = Files.readString(obsFile);
        assertTrue(obsContent.contains("RINEX VERSION / TYPE"),
                "Observation file should contain RINEX header");
        assertTrue(obsContent.contains("END OF HEADER"),
                "Observation file should contain end of header");
        assertTrue(obsContent.contains("MARKER NAME"),
                "Observation file should contain marker name");

        String navContent = Files.readString(navFile);
        assertTrue(navContent.contains("RINEX VERSION / TYPE"),
                "Navigation file should contain RINEX header");
        assertTrue(navContent.contains("END OF HEADER"),
                "Navigation file should contain end of header");

        log.info("RTCM to RINEX rover conversion test PASSED");
    }

    /**
     * Test RTCM to RINEX conversion for base station.
     */
    @Test
    @DisplayName("RTCM to RINEX base conversion")
    void testBaseRtcmToRinex(@TempDir Path tempDir) throws IOException {
        String outputDir = tempDir.toString();
        String stationName = "BASE";
        RtcmToRinexConverter converter = new RtcmToRinexConverter(3.05, outputDir, stationName);

        boolean result = converter.convert(baseData, baseData.length);
        assertTrue(result, "RTCM to RINEX conversion should succeed");

        Path obsFile = Path.of(outputDir).resolve("BASE.obs");
        Path navFile = Path.of(outputDir).resolve("BASE.nav");

        assertTrue(Files.exists(obsFile), "RINEX observation file should exist");
        assertTrue(Files.size(obsFile) > 0, "RINEX observation file should not be empty");
        assertTrue(Files.exists(navFile), "RINEX navigation file should exist");
        assertTrue(Files.size(navFile) > 0, "RINEX navigation file should not be empty");

        log.info("RTCM to RINEX base conversion test PASSED");
    }

    /**
     * Test RTCM to RINEX conversion with RINEX 2.11 format.
     */
//    @Test
//    @DisplayName("RTCM to RINEX 2.11 conversion")
//    void testRtcmToRinex211(@TempDir Path tempDir) throws IOException {
//        String outputDir = " D:\\code\\rtklib_java\\temp_compare\\java";
//        RtcmToRinexConverter converter = new RtcmToRinexConverter(2.11, outputDir, "ROVER");
//
//        boolean result = converter.convert(roverData, roverData.length);
//        assertTrue(result, "RTCM to RINEX 2.11 conversion should succeed");
//
//        Path obsFile = Path.of(outputDir).resolve("ROVER.obs");
//        assertTrue(Files.exists(obsFile), "RINEX 2.11 observation file should exist");
//        assertTrue(Files.size(obsFile) > 0, "RINEX 2.11 observation file should not be empty");
//
//        log.info("RTCM to RINEX 2.11 conversion test PASSED");
//    }

    /**
     * Test that RTCM parser correctly extracts observation data used for RINEX.
     * Verifies that the converter's RTCM decoder can parse all messages.
     */
    @Test
    @DisplayName("RTCM data extraction for RINEX")
    void testRtcmDataExtraction() {
        Rtcm rtcm = new Rtcm();
        int offset = 0;
        int msgCount = 0;
        int obsEpochs = 0;
        int navMsgs = 0;

        while (offset < roverData.length) {
            int consumed = rtcm.input(roverData, offset, roverData.length - offset);
            if (consumed <= 0) {
                offset++;
                continue;
            }
            offset += consumed;
            msgCount++;

            if (isObsType(rtcm.type) && rtcm.obs.n > 0) {
                obsEpochs++;
            }
            if (rtcm.type >= 1019 && rtcm.type <= 1046) {
                navMsgs++;
            }
        }

        log.info("Extracted: {} messages, {} obs epochs, {} nav messages",
                msgCount, obsEpochs, navMsgs);

        assertTrue(msgCount > 0, "Should decode RTCM messages");
        assertTrue(obsEpochs > 0, "Should extract observation epochs");
        assertTrue(navMsgs > 0, "Should extract navigation messages");

        log.info("RTCM data extraction for RINEX test PASSED");
    }

    @Test
    @DisplayName("Compare Java RINEX obs with rtklib C result")
    void testCompareObsWithRtklibC(@TempDir Path tempDir) throws IOException {
        String outputDir = tempDir.toString();
        RtcmToRinexConverter converter = new RtcmToRinexConverter(3.05, outputDir, "ROVER");
        boolean result = converter.convert(roverData, roverData.length);
        assertTrue(result, "Java conversion should succeed");

        Path javaObsFile = Path.of(outputDir).resolve("ROVER.obs");
        assertTrue(Files.exists(javaObsFile), "Java obs file should exist");

        RinexObsData javaData = parseRinex3ObsWithTypes(javaObsFile.toString());
        RinexObsData cData = parseRinex3ObsWithTypes("D:\\code\\rtklib_java\\540423494727\\2026-06-08\\full.obs");

        log.info("Java obs types: {}", javaData.obsTypesBySys);
        log.info("C obs types: {}", cData.obsTypesBySys);

        int matchCount = 0;
        int satMatchCount = 0;
        double sumPDiff = 0, maxPDiff = 0;
        double sumLDiff = 0, maxLDiff = 0;
        int pDiffCount = 0, lDiffCount = 0;
        int cOnlyEpochs = 0, javaOnlyEpochs = 0;
        int pExactMatch = 0;

        for (String epochKey : cData.epochs.keySet()) {
            if (!javaData.epochs.containsKey(epochKey)) {
                cOnlyEpochs++;
                continue;
            }
            Map<String, Map<String, Double>> cSatData = cData.epochs.get(epochKey);
            Map<String, Map<String, Double>> javaSatData = javaData.epochs.get(epochKey);
            matchCount++;

            for (String sat : cSatData.keySet()) {
                if (!javaSatData.containsKey(sat)) continue;
                Map<String, Double> cVals = cSatData.get(sat);
                Map<String, Double> jVals = javaSatData.get(sat);
                satMatchCount++;

                for (String obsType : cVals.keySet()) {
                    if (!obsType.startsWith("C") && !obsType.startsWith("L")) continue;
                    if (!jVals.containsKey(obsType)) continue;
                    double cv = cVals.get(obsType);
                    double jv = jVals.get(obsType);
                    if (Double.isNaN(cv) || Double.isNaN(jv) || cv == 0 || jv == 0) continue;

                    double diff = Math.abs(jv - cv);
                    if (obsType.startsWith("C")) {
                        sumPDiff += diff;
                        maxPDiff = Math.max(maxPDiff, diff);
                        pDiffCount++;
                        if (diff < 0.001) pExactMatch++;
                    } else {
                        sumLDiff += diff;
                        maxLDiff = Math.max(maxLDiff, diff);
                        lDiffCount++;
                    }
                }
            }
        }
        for (String epochKey : javaData.epochs.keySet()) {
            if (!cData.epochs.containsKey(epochKey)) javaOnlyEpochs++;
        }

        log.info("=== RINEX OBS Comparison (Java vs C, by obs type name) ===");
        log.info("C epochs: {}, Java epochs: {}, Matched: {}, C-only: {}, Java-only: {}",
                cData.epochs.size(), javaData.epochs.size(), matchCount, cOnlyEpochs, javaOnlyEpochs);
        log.info("Satellite-epoch matches: {}", satMatchCount);
        if (pDiffCount > 0) {
            log.info(String.format("Pseudorange avg diff: %.6f m, max: %.6f m (%d values, %d exact < 1mm)",
                    sumPDiff / pDiffCount, maxPDiff, pDiffCount, pExactMatch));
        }
        if (lDiffCount > 0) {
            log.info(String.format("Phase avg diff: %.6f cycles, max: %.6f cycles (%d values)",
                    sumLDiff / lDiffCount, maxLDiff, lDiffCount));
        }

        assertTrue(matchCount > 0, "Should have matched epochs");
        if (pDiffCount > 0) {
            assertTrue(sumPDiff / pDiffCount < 0.01,
                    String.format("Avg pseudorange diff should be < 0.01 m, got %.6f", sumPDiff / pDiffCount));
        }
    }

    private static class RinexObsData {
        Map<String, java.util.List<String>> obsTypesBySys = new LinkedHashMap<>();
        Map<String, Map<String, Map<String, Double>>> epochs = new LinkedHashMap<>();
    }

    private RinexObsData parseRinex3ObsWithTypes(String filePath) throws IOException {
        RinexObsData result = new RinexObsData();
        List<String> lines = Files.readAllLines(Path.of(filePath));
        boolean inHeader = true;
        String currentSys = null;
        java.util.List<String> currentObsTypes = new java.util.ArrayList<>();
        String currentEpoch = null;

        for (String line : lines) {
            if (inHeader) {
                if (line.contains("SYS / # / OBS TYPES")) {
                    String trimmed = line.trim();
                    String[] parts = trimmed.split("\\s+");
                    if (parts.length >= 2 && parts[0].length() == 1 && Character.isLetter(parts[0].charAt(0))) {
                        currentSys = parts[0];
                        currentObsTypes = new java.util.ArrayList<>();
                        int n = Integer.parseInt(parts[1]);
                        for (int i = 2; i < parts.length && currentObsTypes.size() < n; i++) {
                            if (parts[i].equals("SYS") || parts[i].equals("/") || parts[i].equals("#")
                                    || parts[i].equals("OBS") || parts[i].equals("TYPES")) continue;
                            currentObsTypes.add(parts[i]);
                        }
                        if (currentObsTypes.size() < n) {
                            result.obsTypesBySys.put(currentSys, null);
                        } else {
                            result.obsTypesBySys.put(currentSys, currentObsTypes);
                        }
                    } else if (currentSys != null && result.obsTypesBySys.get(currentSys) == null) {
                        for (int i = 0; i < parts.length; i++) {
                            if (parts[i].equals("SYS") || parts[i].equals("/") || parts[i].equals("#")
                                    || parts[i].equals("OBS") || parts[i].equals("TYPES")) continue;
                            currentObsTypes.add(parts[i]);
                        }
                        java.util.List<String> prev = new java.util.ArrayList<>(currentObsTypes);
                        result.obsTypesBySys.put(currentSys, prev);
                    }
                }
                if (line.contains("END OF HEADER")) inHeader = false;
                continue;
            }

            if (line.startsWith(">")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 7) {
                    currentEpoch = parts[1] + " " + parts[2] + " " + parts[3] + " " + parts[4] + " " + parts[5] + " " + parts[6];
                    result.epochs.put(currentEpoch, new LinkedHashMap<>());
                }
                continue;
            }

            if (currentEpoch != null && line.length() >= 3) {
                String sat = line.substring(0, 3).trim();
                String sysChar = sat.substring(0, 1);
                java.util.List<String> obsTypes = result.obsTypesBySys.get(sysChar);
                if (obsTypes == null || obsTypes.isEmpty()) continue;

                String data = line.substring(3);
                Map<String, Double> satVals = new LinkedHashMap<>();
                int pos = 0;
                for (int i = 0; i < obsTypes.size(); i++) {
                    double val = Double.NaN;
                    if (pos + 14 <= data.length()) {
                        String valStr = data.substring(pos, pos + 14).trim();
                        if (!valStr.isEmpty()) {
                            try { val = Double.parseDouble(valStr); } catch (NumberFormatException e) { val = Double.NaN; }
                        }
                    }
                    satVals.put(obsTypes.get(i), val);
                    pos += 16;
                }
                result.epochs.get(currentEpoch).put(sat, satVals);
            }
        }
        return result;
    }

    @Test
    @DisplayName("Compare Java RINEX nav with rtklib C result")
    void testCompareNavWithRtklibC(@TempDir Path tempDir) throws IOException {
        String outputDir = tempDir.toString();
        RtcmToRinexConverter converter = new RtcmToRinexConverter(3.05, outputDir, "ROVER");
        boolean result = converter.convert(roverData, roverData.length);
        assertTrue(result, "Java conversion should succeed");

        Path javaNavFile = tempDir.resolve("ROVER.nav");
        assertTrue(Files.exists(javaNavFile), "Java nav file should exist");

        Map<String, double[]> javaNav = parseRinexNav(javaNavFile.toString());
        Map<String, double[]> cNav = parseRinexNav("D:\\code\\rtklib_java\\540423494727\\2026-06-08\\full.nav");

        log.info("Java nav entries: {}, C nav entries: {}", javaNav.size(), cNav.size());

        int matchCount = 0;
        double sumDiff = 0, maxDiff = 0;
        int diffCount = 0;

        for (String sat : cNav.keySet()) {
            if (!javaNav.containsKey(sat)) continue;
            matchCount++;
            double[] cVals = cNav.get(sat);
            double[] jVals = javaNav.get(sat);
            int minLen = Math.min(cVals.length, jVals.length);
            for (int i = 0; i < minLen; i++) {
                if (cVals[i] == 0 && jVals[i] == 0) continue;
                double diff = Math.abs(jVals[i] - cVals[i]);
                sumDiff += diff;
                maxDiff = Math.max(maxDiff, diff);
                diffCount++;
            }
        }

        log.info("=== RINEX NAV Comparison ===");
        log.info("Matched satellites: {}/{}/{}", matchCount, javaNav.size(), cNav.size());
        if (diffCount > 0) {
            log.info("Avg param diff: {}, max: {}", String.format("%.6e", sumDiff / diffCount), String.format("%.6e", maxDiff));
        }

        assertTrue(matchCount > 0, "Should have matched nav entries");
    }

    private Map<String, Map<String, List<Double>>> parseRinex3ObsValues(String filePath) throws IOException {
        Map<String, Map<String, List<Double>>> result = new LinkedHashMap<>();
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(filePath))) {
            String line;
            boolean inHeader = true;
            String currentEpoch = null;
            int numObsTypes = 0;

            while ((line = br.readLine()) != null) {
                if (inHeader) {
                    if (line.contains("SYS / # / OBS TYPES")) {
                        String trimmed = line.trim();
                        String[] parts = trimmed.split("\\s+");
                        if (parts.length >= 2 && parts[0].equals("C")) {
                            numObsTypes = Integer.parseInt(parts[1]);
                        }
                    }
                    if (line.contains("END OF HEADER")) inHeader = false;
                    continue;
                }

                if (line.startsWith(">")) {
                    String[] parts = line.trim().split("\\s+");
                    if (parts.length >= 7) {
                        currentEpoch = parts[1] + " " + parts[2] + " " + parts[3] + " " + parts[4] + " " + parts[5] + " " + parts[6];
                        result.put(currentEpoch, new LinkedHashMap<>());
                    }
                    continue;
                }

                if (currentEpoch != null && line.length() >= 3) {
                    String sat = line.substring(0, 3).trim();
                    String data = line.substring(3);
                    List<Double> vals = new java.util.ArrayList<>();
                    int pos = 0;
                    for (int i = 0; i < numObsTypes; i++) {
                        if (pos + 14 <= data.length()) {
                            String valStr = data.substring(pos, pos + 14).trim();
                            double val = valStr.isEmpty() ? Double.NaN : Double.parseDouble(valStr);
                            vals.add(val);
                        } else {
                            vals.add(Double.NaN);
                        }
                        pos += 16;
                    }
                    result.get(currentEpoch).put(sat, vals);
                }
            }
        }
        return result;
    }

    private Map<String, List<String>> extractObsDataByEpoch(List<String> lines) {
        Map<String, List<String>> result = new LinkedHashMap<>();
        boolean inHeader = true;
        String currentEpoch = null;
        List<String> satLines = null;

        for (String line : lines) {
            if (inHeader) {
                if (line.contains("END OF HEADER")) inHeader = false;
                continue;
            }
            if (line.startsWith(">")) {
                String[] parts = line.trim().split("\\s+");
                if (parts.length >= 7) {
                    currentEpoch = parts[1] + " " + parts[2] + " " + parts[3] + " " + parts[4] + " " + parts[5] + " " + parts[6];
                    satLines = new java.util.ArrayList<>();
                    result.put(currentEpoch, satLines);
                }
            } else if (currentEpoch != null && line.length() >= 3) {
                satLines.add(line);
            }
        }
        return result;
    }

    private Map<String, List<Double>> parseSatObsValues(List<String> satLines) {
        Map<String, List<Double>> result = new LinkedHashMap<>();
        for (String line : satLines) {
            String sat = line.substring(0, 3).trim();
            String data = line.substring(3);
            List<Double> vals = new java.util.ArrayList<>();
            String[] tokens = data.trim().split("\\s+");
            for (String tok : tokens) {
                try {
                    double v = Double.parseDouble(tok);
                    if (v < 1e10) {
                        vals.add(v);
                    }
                } catch (NumberFormatException e) {
                    // skip
                }
            }
            result.put(sat, vals);
        }
        return result;
    }

    private Map<String, double[]> parseRinexNav(String filePath) throws IOException {
        Map<String, double[]> result = new LinkedHashMap<>();
        try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.FileReader(filePath))) {
            String line;
            boolean inHeader = true;
            String currentSat = null;
            java.util.List<Double> params = new java.util.ArrayList<>();

            while ((line = br.readLine()) != null) {
                if (inHeader) {
                    if (line.contains("END OF HEADER")) inHeader = false;
                    continue;
                }

                if (line.isEmpty()) continue;

                if (Character.isLetter(line.charAt(0))) {
                    if (currentSat != null && !params.isEmpty()) {
                        result.put(currentSat, params.stream().mapToDouble(Double::doubleValue).toArray());
                    }
                    String sat = line.substring(0, 3).trim();
                    currentSat = sat;
                    params = new java.util.ArrayList<>();
                    String rest = line.substring(3).trim();
                    for (String v : rest.split("\\s+")) {
                        try { params.add(Double.parseDouble(v.replace('D', 'E').replace('d', 'e'))); } catch (NumberFormatException e) { break; }
                    }
                } else if (currentSat != null) {
                    for (String v : line.trim().split("\\s+")) {
                        try { params.add(Double.parseDouble(v.replace('D', 'E').replace('d', 'e'))); } catch (NumberFormatException e) { break; }
                    }
                }
            }
            if (currentSat != null && !params.isEmpty()) {
                result.put(currentSat, params.stream().mapToDouble(Double::doubleValue).toArray());
            }
        }
        return result;
    }
}
