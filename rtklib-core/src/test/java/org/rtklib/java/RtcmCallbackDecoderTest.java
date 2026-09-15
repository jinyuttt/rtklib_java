package org.rtklib.java;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.rtcm.AuxData;
import org.rtklib.java.rtcm.ObservationEpoch;
import org.rtklib.java.rtcm.RtcmCallbackDecoder;
import org.rtklib.java.rtcm.RtcmDataHandler;
import org.rtklib.java.time.TimeSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RtcmCallbackDecoder Test")
public class RtcmCallbackDecoderTest {

    private static final Logger log = LoggerFactory.getLogger(RtcmCallbackDecoderTest.class);
    private static final String ROVER_PATH =
            "C:\\Users\\admin\\Desktop\\<DEVICE_ID>\\2026-06-08\\1.rtcm3";
    private static byte[] roverData;

    @BeforeAll
    static void loadData() throws IOException {
        try (FileInputStream fis = new FileInputStream(ROVER_PATH)) {
            roverData = fis.readAllBytes();
        }
        log.info("Loaded RTCM data: {} bytes", roverData.length);
    }

    @Test
    @DisplayName("1. Observation epoch decoding and time correction")
    void testObservationEpochDecoding() {
        List<ObservationEpoch> epochs = new ArrayList<>();
        List<Eph> ephList = new ArrayList<>();
        List<Geph> gephList = new ArrayList<>();

        RtcmCallbackDecoder decoder = new RtcmCallbackDecoder(new RtcmDataHandler() {
            @Override public void onStation(Sta sta) {}
            @Override public void onSsr(Ssr ssr) {}
            @Override public void onEph(Eph eph) { ephList.add(eph); }
            @Override public void onGeph(Geph geph) { gephList.add(geph); }
            @Override public void onObservationEpoch(ObservationEpoch epoch) {
                epochs.add(epoch);
            }
            @Override public void onAuxData(AuxData aux) {}
            @Override public void onFinish() {}
        });

        decoder.feed(roverData, 0, roverData.length);
        decoder.finish();

        assertFalse(epochs.isEmpty(), "Should have at least one observation epoch");
        log.info("Total epochs: {}, eph: {}, geph: {}", epochs.size(), ephList.size(), gephList.size());

        for (ObservationEpoch epoch : epochs) {
            double[] ymd = TimeSystem.time2ymdhms(epoch.time);
            int year = (int) ymd[0];
            int month = (int) ymd[1];
            int day = (int) ymd[2];
            assertTrue(year >= 2020 && year <= 2030,
                    "Year should be reasonable (2020-2030), got " + year);
            assertTrue(month >= 1 && month <= 12,
                    "Month should be 1-12, got " + month);
            assertTrue(day >= 1 && day <= 31,
                    "Day should be 1-31, got " + day);
        }

        ObservationEpoch first = epochs.get(0);
        double[] ymd0 = TimeSystem.time2ymdhms(first.time);
        log.info("First epoch: {}, sats={}",
                String.format("%04d-%02d-%02d %02d:%02d:%06.3f",
                        (int) ymd0[0], (int) ymd0[1], (int) ymd0[2],
                        (int) ymd0[3], (int) ymd0[4], ymd0[5]),
                first.obsList.size());

        ObservationEpoch last = epochs.get(epochs.size() - 1);
        double[] ymdL = TimeSystem.time2ymdhms(last.time);
        log.info("Last epoch: {}, sats={}",
                String.format("%04d-%02d-%02d %02d:%02d:%06.3f",
                        (int) ymdL[0], (int) ymdL[1], (int) ymdL[2],
                        (int) ymdL[3], (int) ymdL[4], ymdL[5]),
                last.obsList.size());
    }

    @Test
    @DisplayName("2. Multi-frequency observation merging")
    void testMultiFrequencyMerging() {
        List<ObservationEpoch> epochs = new ArrayList<>();

        RtcmCallbackDecoder decoder = new RtcmCallbackDecoder(new RtcmDataHandler() {
            @Override public void onStation(Sta sta) {}
            @Override public void onSsr(Ssr ssr) {}
            @Override public void onEph(Eph eph) {}
            @Override public void onGeph(Geph geph) {}
            @Override public void onObservationEpoch(ObservationEpoch epoch) {
                epochs.add(epoch);
            }
            @Override public void onAuxData(AuxData aux) {}
            @Override public void onFinish() {}
        });

        decoder.feed(roverData, 0, roverData.length);
        decoder.finish();

        assertFalse(epochs.isEmpty(), "Should have observation epochs");

        int multiFreqCount = 0;
        int totalObs = 0;
        for (ObservationEpoch epoch : epochs) {
            for (Obsd o : epoch.obsList) {
                totalObs++;
                int freqCount = 0;
                for (int j = 0; j < Constants.NFREQ + Constants.NEXOBS; j++) {
                    if (o.P[j] != 0.0 || o.L[j] != 0.0) freqCount++;
                }
                if (freqCount > 1) multiFreqCount++;
            }
        }

        log.info("Total obs: {}, multi-freq obs: {}", totalObs, multiFreqCount);
        assertTrue(multiFreqCount > 0, "Should have multi-frequency observations after merging");

        ObservationEpoch first = epochs.get(0);
        for (Obsd o : first.obsList) {
            int[] prnArr = new int[1];
            SatUtils.satsys(o.sat, prnArr);
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("  sat=%d (prn=%d): ", o.sat, prnArr[0]));
            for (int j = 0; j < Constants.NFREQ + Constants.NEXOBS; j++) {
                if (o.P[j] != 0.0 || o.L[j] != 0.0) {
                    sb.append(String.format("[%d]P=%.3f L=%.3f c=%d  ", j, o.P[j], o.L[j], o.code[j]));
                }
            }
            log.info(sb.toString());
        }
    }

    @Test
    @DisplayName("3. Ephemeris callback verification")
    void testEphemerisCallback() {
        List<Eph> ephList = new ArrayList<>();
        List<Geph> gephList = new ArrayList<>();
        Set<Integer> ephSats = new TreeSet<>();

        RtcmCallbackDecoder decoder = new RtcmCallbackDecoder(new RtcmDataHandler() {
            @Override public void onStation(Sta sta) {}
            @Override public void onSsr(Ssr ssr) {}
            @Override public void onEph(Eph eph) {
                ephList.add(eph);
                ephSats.add(eph.sat);
            }
            @Override public void onGeph(Geph geph) {
                gephList.add(geph);
                ephSats.add(geph.sat);
            }
            @Override public void onObservationEpoch(ObservationEpoch epoch) {}
            @Override public void onAuxData(AuxData aux) {}
            @Override public void onFinish() {}
        });

        decoder.feed(roverData, 0, roverData.length);
        decoder.finish();

        log.info("Ephemeris count: eph={}, geph={}", ephList.size(), gephList.size());
        assertFalse(ephList.isEmpty() && gephList.isEmpty(), "Should have at least some ephemeris");

        for (Eph eph : ephList) {
            assertTrue(eph.sat > 0, "Ephemeris satellite should be valid");
            assertTrue(eph.A > 0, "Ephemeris semi-major axis should be positive");
        }

        Map<String, Integer> sysCount = new LinkedHashMap<>();
        for (int sat : ephSats) {
            int[] prnArr = new int[1];
            int sys = SatUtils.satsys(sat, prnArr);
            String name;
            if (sys == Constants.SYS_GPS) name = "GPS";
            else if (sys == Constants.SYS_CMP) name = "BDS";
            else if (sys == Constants.SYS_GAL) name = "GAL";
            else if (sys == Constants.SYS_QZS) name = "QZS";
            else name = "SYS" + sys;
            sysCount.merge(name, 1, Integer::sum);
        }
        log.info("Ephemeris by system: {}", sysCount);
    }

    @Test
    @DisplayName("4. Chunked feed test")
    void testChunkedFeed() {
        List<ObservationEpoch> wholeEpochs = new ArrayList<>();
        List<ObservationEpoch> chunkedEpochs = new ArrayList<>();

        RtcmCallbackDecoder decoder1 = new RtcmCallbackDecoder(new RtcmDataHandler() {
            @Override public void onStation(Sta sta) {}
            @Override public void onSsr(Ssr ssr) {}
            @Override public void onEph(Eph eph) {}
            @Override public void onGeph(Geph geph) {}
            @Override public void onObservationEpoch(ObservationEpoch epoch) {
                wholeEpochs.add(epoch);
            }
            @Override public void onAuxData(AuxData aux) {}
            @Override public void onFinish() {}
        });
        decoder1.feed(roverData, 0, roverData.length);
        decoder1.finish();

        int chunkSize = 256;
        int offset = 0;
        RtcmCallbackDecoder decoder2 = new RtcmCallbackDecoder(new RtcmDataHandler() {
            @Override public void onStation(Sta sta) {}
            @Override public void onSsr(Ssr ssr) {}
            @Override public void onEph(Eph eph) {}
            @Override public void onGeph(Geph geph) {}
            @Override public void onObservationEpoch(ObservationEpoch epoch) {
                chunkedEpochs.add(epoch);
            }
            @Override public void onAuxData(AuxData aux) {}
            @Override public void onFinish() {}
        });
        while (offset < roverData.length) {
            int len = Math.min(chunkSize, roverData.length - offset);
            decoder2.feed(roverData, offset, len);
            offset += len;
        }
        decoder2.finish();

        log.info("Whole feed epochs: {}, chunked feed epochs: {}", wholeEpochs.size(), chunkedEpochs.size());
        assertEquals(wholeEpochs.size(), chunkedEpochs.size(),
                "Chunked feed should produce same number of epochs as whole feed");

        for (int i = 0; i < Math.min(wholeEpochs.size(), chunkedEpochs.size()); i++) {
            ObservationEpoch we = wholeEpochs.get(i);
            ObservationEpoch ce = chunkedEpochs.get(i);
            assertEquals(we.obsList.size(), ce.obsList.size(),
                    "Epoch " + i + " should have same number of observations");
        }
    }

    @Test
    @DisplayName("5. Satellite system distribution")
    void testSatelliteSystemDistribution() {
        List<ObservationEpoch> epochs = new ArrayList<>();

        RtcmCallbackDecoder decoder = new RtcmCallbackDecoder(new RtcmDataHandler() {
            @Override public void onStation(Sta sta) {}
            @Override public void onSsr(Ssr ssr) {}
            @Override public void onEph(Eph eph) {}
            @Override public void onGeph(Geph geph) {}
            @Override public void onObservationEpoch(ObservationEpoch epoch) {
                epochs.add(epoch);
            }
            @Override public void onAuxData(AuxData aux) {}
            @Override public void onFinish() {}
        });

        decoder.feed(roverData, 0, roverData.length);
        decoder.finish();

        Map<String, Integer> sysCount = new LinkedHashMap<>();
        Set<Integer> allSats = new TreeSet<>();

        for (ObservationEpoch epoch : epochs) {
            for (Obsd o : epoch.obsList) {
                allSats.add(o.sat);
                int[] prnArr = new int[1];
                int sys = SatUtils.satsys(o.sat, prnArr);
                String name;
                if (sys == Constants.SYS_GPS) name = "GPS";
                else if (sys == Constants.SYS_CMP) name = "BDS";
                else if (sys == Constants.SYS_GLO) name = "GLO";
                else if (sys == Constants.SYS_GAL) name = "GAL";
                else if (sys == Constants.SYS_QZS) name = "QZS";
                else name = "SYS" + sys;
                sysCount.merge(name, 1, Integer::sum);
            }
        }

        log.info("Satellite system distribution: {}", sysCount);
        log.info("Total unique satellites: {}", allSats.size());
        assertFalse(sysCount.isEmpty(), "Should have at least one satellite system");
    }
}