package org.rtklib.java;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.condition.EnabledIf;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.data.*;
import org.rtklib.java.rinex.RtcmToRinexConverter;
import org.rtklib.java.rtcm.*;
import org.rtklib.java.time.TimeSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Public test-data: RTCM3 sample from RTKLIB")
public class RtcmSampleDataTest {

    private static final Logger log = LoggerFactory.getLogger(RtcmSampleDataTest.class);

    private static final String RTCM3_PATH = "rtcm/open-sky_base_20090515.rtcm3";
    private static final String TLE_GPS_PATH = "tle/tle_gps.txt";
    private static final String TLE_BDS_PATH = "tle/tle_bds.txt";

    private static byte[] rtcm3Data;

    static boolean hasRtcm3Sample() {
        return TestDataConfig.hasTestDataFile(RTCM3_PATH);
    }

    static boolean hasTleGps() {
        return TestDataConfig.hasTestDataFile(TLE_GPS_PATH);
    }

    static boolean hasTleBds() {
        return TestDataConfig.hasTestDataFile(TLE_BDS_PATH);
    }

    @BeforeAll
    static void loadData() throws IOException {
        if (!hasRtcm3Sample()) {
            log.warn("Public test-data not found: {}, skipping", RTCM3_PATH);
            return;
        }
        String path = TestDataConfig.getTestDataFile(RTCM3_PATH);
        try (InputStream is = new FileInputStream(path)) {
            rtcm3Data = is.readAllBytes();
        }
        log.info("Loaded RTCM3 sample: {} bytes from {}", rtcm3Data.length, path);
    }

    @Test
    @DisplayName("1. RTCM3 raw frame scan")
    @EnabledIf("hasRtcm3Sample")
    void testRawFrameScan() {
        TreeMap<Integer, Integer> frameTypes = new TreeMap<>();
        int frameCount = 0;
        byte[] data = rtcm3Data;

        for (int fi = 0; fi < data.length - 2; fi++) {
            if ((data[fi] & 0xFF) == 0xD3 && (data[fi + 1] & 0x01) == 0) {
                int len = ((data[fi + 1] & 0x03) << 8) | (data[fi + 2] & 0xFF);
                if (fi + 3 + len <= data.length && len >= 0 && len <= 1023) {
                    int msgType = ((data[fi + 3] & 0x1F) << 8) | (data[fi + 4] & 0xFF);
                    frameTypes.merge(msgType, 1, Integer::sum);
                    frameCount++;
                    fi += 2 + len + 3;
                }
            }
        }

        log.info("Total RTCM3 frames: {}", frameCount);
        for (var e : frameTypes.entrySet()) {
            log.info("  Type {}: {} frames", e.getKey(), e.getValue());
        }

        assertTrue(frameCount > 0, "Should find RTCM3 frames");
        assertTrue(frameTypes.containsKey(1006) || frameTypes.containsKey(1005),
                "Should contain station info (1005/1006)");
    }

    @Test
    @DisplayName("2. RTCM3 decode via RtcmCallbackDecoder")
    @EnabledIf("hasRtcm3Sample")
    void testCallbackDecoder() {
        List<ObservationEpoch> epochs = new ArrayList<>();
        List<Eph> ephList = new ArrayList<>();
        List<Geph> gephList = new ArrayList<>();
        List<Sta> staList = new ArrayList<>();

        RtcmCallbackDecoder decoder = new RtcmCallbackDecoder(new RtcmDataHandler() {
            @Override public void onStation(Sta sta) { staList.add(new Sta(sta)); }
            @Override public void onSsr(Ssr ssr) {}
            @Override public void onEph(Eph eph) { ephList.add(eph); }
            @Override public void onGeph(Geph geph) { gephList.add(geph); }
            @Override public void onObservationEpoch(ObservationEpoch epoch) { epochs.add(epoch); }
            @Override public void onAuxData(AuxData aux) {}
            @Override public void onFinish() {}
        });

        decoder.feed(rtcm3Data, 0, rtcm3Data.length);
        decoder.finish();

        log.info("Decoded: epochs={}, eph={}, geph={}, sta={}",
                epochs.size(), ephList.size(), gephList.size(), staList.size());

        assertTrue(epochs.size() > 0, "Should decode observation epochs");
        assertTrue(ephList.size() > 0, "Should decode ephemeris");

        if (!staList.isEmpty()) {
            Sta sta = staList.get(0);
            log.info("Station: name={}, pos=({:.4f},{:.4f},{:.1f})",
                    sta.name, sta.pos[0], sta.pos[1], sta.pos[2]);
        }

        ObservationEpoch first = epochs.get(0);
        double[] ymd = TimeSystem.time2ymdhms(first.time);
        log.info("First epoch: {}-{}-{} {}:{}:{:.3f}, sats={}",
                (int) ymd[0], (int) ymd[1], (int) ymd[2],
                (int) ymd[3], (int) ymd[4], ymd[5], first.obsList.size());

        assertEquals(2009, (int) ymd[0], "Year should be 2009");
        assertEquals(5, (int) ymd[1], "Month should be 5");
        assertEquals(15, (int) ymd[2], "Day should be 15");
    }

    @Test
    @DisplayName("3. RTCM3 to RINEX conversion")
    @EnabledIf("hasRtcm3Sample")
    void testRtcmToRinex() throws IOException {
        Path tempDir = Files.createTempDirectory("rtcm2rinex_");

        RtcmToRinexConverter converter = new RtcmToRinexConverter(3.05, tempDir.toString(), "0263");
        boolean result = converter.convert(rtcm3Data, rtcm3Data.length);
        assertTrue(result, "RTCM to RINEX conversion should succeed");

        Path obsFile = tempDir.resolve("0263.obs");
        Path navFile = tempDir.resolve("0263.nav");

        assertTrue(Files.exists(obsFile), "RINEX obs file should exist");
        assertTrue(Files.size(obsFile) > 0, "RINEX obs file should not be empty");
        assertTrue(Files.exists(navFile), "RINEX nav file should exist");
        assertTrue(Files.size(navFile) > 0, "RINEX nav file should not be empty");

        String obsContent = Files.readString(obsFile);
        assertTrue(obsContent.contains("RINEX VERSION / TYPE"), "Should have RINEX header");
        assertTrue(obsContent.contains("END OF HEADER"), "Should have end of header");

        long obsSize = Files.size(obsFile);
        long navSize = Files.size(navFile);
        log.info("RINEX obs: {} bytes, nav: {} bytes", obsSize, navSize);

        try {
            Files.deleteIfExists(obsFile);
            Files.deleteIfExists(navFile);
            Files.deleteIfExists(tempDir);
        } catch (Exception ignored) {}
    }

    @Test
    @DisplayName("4. RTCM3 message type statistics")
    @EnabledIf("hasRtcm3Sample")
    void testMessageTypeStats() {
        TreeMap<Integer, Integer> msgCounts = new TreeMap<>();
        Rtcm rawRtcm = new Rtcm();
        int pos = 0;

        while (pos < rtcm3Data.length) {
            int consumed = rawRtcm.input(rtcm3Data, pos, rtcm3Data.length - pos);
            if (consumed > 0) {
                msgCounts.merge(rawRtcm.type, 1, Integer::sum);
                pos += consumed;
            } else if (consumed == 0) {
                break;
            } else {
                pos++;
            }
        }

        log.info("Message type statistics:");
        for (var e : msgCounts.entrySet()) {
            log.info("  Type {}: {} messages", e.getKey(), e.getValue());
        }

        assertTrue(msgCounts.size() > 0, "Should decode message types");
        int totalMsgs = msgCounts.values().stream().mapToInt(Integer::intValue).sum();
        log.info("Total decoded messages: {}", totalMsgs);
        assertTrue(totalMsgs > 10, "Should decode at least 10 messages");
    }

    @Test
    @DisplayName("5. TLE GPS data available")
    @EnabledIf("hasTleGps")
    void testTleGpsAvailable() throws IOException {
        String path = TestDataConfig.getTestDataFile(TLE_GPS_PATH);
        String content = Files.readString(Paths.get(path));
        assertTrue(content.contains("1 N"), "Should contain TLE line 1");
        assertTrue(content.contains("2 N"), "Should contain TLE line 2");
        log.info("GPS TLE loaded: {} chars from {}", content.length(), path);
    }

    @Test
    @DisplayName("6. TLE BDS data available")
    @EnabledIf("hasTleBds")
    void testTleBdsAvailable() throws IOException {
        String path = TestDataConfig.getTestDataFile(TLE_BDS_PATH);
        String content = Files.readString(Paths.get(path));
        assertTrue(content.contains("1 N"), "Should contain TLE line 1");
        assertTrue(content.contains("2 N"), "Should contain TLE line 2");
        log.info("BDS TLE loaded: {} chars from {}", content.length(), path);
    }
}