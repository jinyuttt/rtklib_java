package org.rtklib.java;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.rtcm.*;
import org.rtklib.java.time.TimeSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RTCM3 file parse test")
public class RtcmFileParserTest {

    private static final Logger log = LoggerFactory.getLogger(RtcmFileParserTest.class);

    private static final String FILE_PATH = "D:\\tdengine-jetlinks\\jetlinks-data\\device_rtcmbin_storage\\GS2025090019\\2026-07-09\\1.rtcm3";

    @Test
    @DisplayName("Parse RTCM3 binary file")
    void testParseRtcm3File() throws IOException {
        byte[] data = readFile(FILE_PATH);
        log.info("File size: {} bytes", data.length);

        log.info("--- Step 0: Raw frame scan (independent of decoder) ---");
        int frameCount = 0;
        TreeMap<Integer, Integer> rawFrameTypes = new TreeMap<>();
        for (int fi = 0; fi < data.length - 2; fi++) {
            if ((data[fi] & 0xFF) == 0xD3 && (data[fi + 1] & 0x01) == 0) {
                int len = ((data[fi + 1] & 0x03) << 8) | (data[fi + 2] & 0xFF);
                if (fi + 3 + len <= data.length && len >= 0 && len <= 1023) {
                    int msgType = ((data[fi + 3] & 0x1F) << 8) | (data[fi + 4] & 0xFF);
                    rawFrameTypes.merge(msgType, 1, Integer::sum);
                    frameCount++;
                    fi += 2 + len + 3;
                }
            }
        }
        log.info("  Total RTCM3 frames found: {}", frameCount);
        for (var entry : rawFrameTypes.entrySet()) {
            log.info("  Raw frame type {}: {} frames", entry.getKey(), entry.getValue());
        }

        log.info("--- Step 1: Decoder message type statistics ---");
        TreeMap<Integer, Integer> msgTypeCounts = new TreeMap<>();
        TreeMap<Integer, List<String>> msgTypeTimeline = new TreeMap<>();
        Rtcm rawRtcm = new Rtcm();
        int rawPos = 0;
        int msgIndex = 0;
        while (rawPos < data.length) {
            int consumed = rawRtcm.input(data, rawPos, data.length - rawPos);
            if (consumed > 0) {
                int type = rawRtcm.type;
                msgTypeCounts.merge(type, 1, Integer::sum);
                if (type == 1042 || type == 1006 || type == 1124) {
                    String info;
                    if (type == 1042 && rawRtcm.ephsat != 0) {
                        int[] prnArr = new int[1];
                        int sys = SatUtils.satsys(rawRtcm.ephsat, prnArr);
                        info = String.format("#%d type=%d sat=%s%d", msgIndex, type, sysName(sys), prnArr[0]);
                    } else if (type == 1124) {
                        int[] weekArr = new int[1];
                        double tow = TimeSystem.time2gpst(rawRtcm.time, weekArr);
                        info = String.format("#%d type=%d TOW=%.0f", msgIndex, type, tow);
                    } else {
                        info = String.format("#%d type=%d", msgIndex, type);
                    }
                    msgTypeTimeline.computeIfAbsent(type, k -> new ArrayList<>()).add(info);
                }
                rawPos += consumed;
                msgIndex++;
            } else if (consumed == 0) {
                break;
            } else {
                rawPos++;
            }
        }
        for (var entry : msgTypeCounts.entrySet()) {
            log.info("  RTCM {}: {} messages", entry.getKey(), entry.getValue());
        }
        log.info("  --- 1042 ephemeris timeline ---");
        List<String> ephTimeline = msgTypeTimeline.getOrDefault(1042, List.of());
        for (String s : ephTimeline) {
            log.info("  {}", s);
        }
        log.info("  --- 1124 observation timeline (first 10 + last 5) ---");
        List<String> obsTimeline = msgTypeTimeline.getOrDefault(1124, List.of());
        for (int i = 0; i < Math.min(10, obsTimeline.size()); i++) {
            log.info("  {}", obsTimeline.get(i));
        }
        if (obsTimeline.size() > 15) log.info("  ... {} omitted ...", obsTimeline.size() - 15);
        for (int i = Math.max(10, obsTimeline.size() - 5); i < obsTimeline.size(); i++) {
            log.info("  {}", obsTimeline.get(i));
        }

        log.info("--- Step 2: Callback decoder results ---");

        List<ObservationEpoch> epochs = new ArrayList<>();
        List<Eph> ephList = new ArrayList<>();
        List<Geph> gephList = new ArrayList<>();
        List<Sta> staList = new ArrayList<>();
        List<Ssr> ssrList = new ArrayList<>();
        List<AuxData> auxList = new ArrayList<>();

        RtcmDataHandler handler = new RtcmDataHandler() {
            @Override public void onStation(Sta sta) { staList.add(sta); }
            @Override public void onSsr(Ssr ssr) { ssrList.add(ssr); }
            @Override public void onEph(Eph eph) { ephList.add(eph); }
            @Override public void onGeph(Geph geph) { gephList.add(geph); }
            @Override public void onObservationEpoch(ObservationEpoch epoch) { epochs.add(epoch); }
            @Override public void onAuxData(AuxData aux) { auxList.add(aux); }
            @Override public void onFinish() {}
        };

        RtcmCallbackDecoder decoder = new RtcmCallbackDecoder(handler);
        decoder.feed(data, 0, data.length);
        decoder.finish();

        log.info("===== Parse result summary =====");
        log.info("  Observation epochs: {}", epochs.size());
        log.info("  Ephemeris records: {}", ephList.size());
        log.info("  GLONASS ephemeris: {}", gephList.size());
        log.info("  Station records: {}", staList.size());
        log.info("  SSR records: {}", ssrList.size());
        log.info("  Aux data records: {}", auxList.size());

        assertTrue(epochs.size() > 0 || ephList.size() > 0, "Should have at least observation or ephemeris data");

        for (int ei = 0; ei < epochs.size(); ei++) {
            ObservationEpoch epoch = epochs.get(ei);
            String timeStr = formatTime(epoch.time);
            int satCount = epoch.obsList.size();
            log.info("  Epoch {} : {} satellites", timeStr, satCount);
            for (Obsd o : epoch.obsList) {
                int[] prnArr = new int[1];
                int sys = SatUtils.satsys(o.sat, prnArr);
                String sysStr = sysName(sys);
                StringBuilder codes = new StringBuilder();
                for (int j = 0; j < Constants.NFREQ; j++) {
                    if (o.P[j] != 0.0 || o.L[j] != 0.0) {
                        if (codes.length() > 0) codes.append(",");
                        codes.append("F").append(j).append("(P=")
                             .append(String.format("%.3f", o.P[j]))
                             .append(",L=").append(String.format("%.3f", o.L[j]))
                             .append(",code=").append(o.code[j]).append(")");
                    }
                }
                log.info("    {}{}: {}", sysStr, prnArr[0], codes);
            }
            if (ei >= 2) {
                log.info("  ... {} more epochs omitted", epochs.size() - ei - 1);
                break;
            }
        }

        if (!staList.isEmpty()) {
            Sta s = staList.get(0);
            log.info("  Station (1st of {}): name={} pos=({},{},{})",
                    staList.size(), s.name,
                    String.format("%.4f", s.pos[0]),
                    String.format("%.4f", s.pos[1]),
                    String.format("%.4f", s.pos[2]));
        }

        if (!ephList.isEmpty()) {
            java.util.LinkedHashMap<Integer, Eph> latestEph = new java.util.LinkedHashMap<>();
            for (Eph e : ephList) {
                latestEph.put(e.sat, e);
            }
            log.info("  Ephemeris: {} total callbacks, {} unique satellites", ephList.size(), latestEph.size());
            for (Eph e : latestEph.values()) {
                int[] prnArr = new int[1];
                int sys = SatUtils.satsys(e.sat, prnArr);
                log.info("    {}{} A={} e={} i0={}",
                        sysName(sys), prnArr[0],
                        String.format("%.4f", e.A),
                        String.format("%.6e", e.e),
                        String.format("%.6f", e.i0));
            }
        }

        for (Geph g : gephList) {
            log.info("  GLONASS Ephemeris: sat={} frq={}", g.sat, g.frq);
        }

        log.info("===== File parse test completed =====");
    }

    private byte[] readFile(String path) throws IOException {
        try (FileInputStream fis = new FileInputStream(path)) {
            return fis.readAllBytes();
        }
    }

    private String formatTime(GTime time) {
        int[] weekArr = new int[1];
        double tow = TimeSystem.time2gpst(time, weekArr);
        return String.format("GPSwk%d TOW=%.1f", weekArr[0], tow);
    }

    private String sysName(int sys) {
        if (sys == Constants.SYS_GPS) return "G";
        if (sys == Constants.SYS_GLO) return "R";
        if (sys == Constants.SYS_GAL) return "E";
        if (sys == Constants.SYS_QZS) return "J";
        if (sys == Constants.SYS_CMP) return "C";
        if (sys == Constants.SYS_IRN) return "I";
        if (sys == Constants.SYS_SBS) return "S";
        return "?";
    }
}