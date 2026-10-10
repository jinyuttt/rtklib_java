package org.rtklib.java;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.rtklib.java.rtcm.Rtcm;
import org.rtklib.java.data.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;

@DisplayName("RTCM Data Check")
public class RtcmDataCheckTest {

    private static final Logger log = LoggerFactory.getLogger(RtcmDataCheckTest.class);

    @Test
    @DisplayName("Check rtcmdata directory for base+rover")
    void testCheckRtcmData() throws Exception {
        String dir = "D:\\rtcmdata";
        java.io.File f = new java.io.File(dir);
        if (!f.exists()) {
            log.warn("D:\\rtcmdata not found, skipping");
            return;
        }

        String baseFile = dir + "\\540424433891\\540424433891\\2026-03-03\\17.rtcm3";
        String roverFile = dir + "\\540424955567\\540424955567\\2026-03-03\\0.rtcm3";

        log.info("=== Checking 540424433891 (potential base) ===");
        analyzeRtcmFile(baseFile, "540424433891");

        log.info("=== Checking 540424955567 (potential rover) ===");
        analyzeRtcmFile(roverFile, "540424955567");

        String rootRtcm1 = dir + "\\10.rtcm3";
        String rootRtcm2 = dir + "\\5567.rtcm3";
        log.info("=== Checking root 10.rtcm3 ===");
        analyzeRtcmFile(rootRtcm1, "10.rtcm3");
        log.info("=== Checking root 5567.rtcm3 ===");
        analyzeRtcmFile(rootRtcm2, "5567.rtcm3");
    }

    private void analyzeRtcmFile(String filePath, String label) throws IOException {
        java.io.File f = new java.io.File(filePath);
        if (!f.exists()) {
            log.warn("{} not found", filePath);
            return;
        }

        Map<Integer, Integer> typeCounts = new TreeMap<>();
        Set<Integer> stationIds = new TreeSet<>();
        int totalObs = 0;
        int totalMsgs = 0;

        Rtcm rtcm = new Rtcm();
        byte[] buf = new byte[4096];
        try (FileInputStream fis = new FileInputStream(filePath)) {
            byte[] pending = new byte[65536];
            int pendingLen = 0;
            int read;
            while ((read = fis.read(buf)) != -1) {
                System.arraycopy(buf, 0, pending, pendingLen, read);
                pendingLen += read;
                int pos = 0;
                while (pos < pendingLen) {
                    int consumed = rtcm.input(pending, pos, pendingLen - pos);
                    if (consumed > 0) {
                        totalMsgs++;
                        int type = rtcm.type;
                        typeCounts.merge(type, 1, Integer::sum);
                        if (type == 1005 || type == 1006) {
                            stationIds.add(rtcm.staid);
                        }
                        if ((type >= 1001 && type <= 1004) || (type >= 1071 && type <= 1127)) {
                            totalObs++;
                        }
                        pos += consumed;
                    } else if (consumed == 0) {
                        break;
                    } else {
                        pos++;
                    }
                }
                if (pos > 0) {
                    System.arraycopy(pending, pos, pending, 0, pendingLen - pos);
                    pendingLen -= pos;
                }
            }
        }

        log.info("{}: totalMsgs={}, totalObs={}, stationIds={}", label, totalMsgs, totalObs, stationIds);
        for (Map.Entry<Integer, Integer> e : typeCounts.entrySet()) {
            String desc = getMsgDesc(e.getKey());
            log.info("  type={}: {} msgs ({})", e.getKey(), e.getValue(), desc);
        }
    }

    private String getMsgDesc(int type) {
        if (type == 1005) return "Station ARP";
        if (type == 1006) return "Station ARP+height";
        if (type >= 1001 && type <= 1004) return "Legacy obs GPS";
        if (type >= 1009 && type <= 1012) return "Legacy obs GLO";
        if (type >= 1071 && type <= 1077) return "MSM GPS";
        if (type >= 1081 && type <= 1087) return "MSM GLO";
        if (type >= 1091 && type <= 1097) return "MSM GAL";
        if (type >= 1101 && type <= 1107) return "MSM SBAS";
        if (type >= 1111 && type <= 1117) return "MSM QZSS";
        if (type >= 1121 && type <= 1127) return "MSM BDS";
        if (type == 1019) return "BDS eph";
        if (type == 1020) return "GLO eph";
        if (type == 1045) return "GAL eph";
        if (type == 1042) return "BDS eph";
        if (type == 1010) return "GLO eph";
        return "other";
    }
}