package org.rtklib.java;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.rtcm.AuxData;
import org.rtklib.java.rtcm.ObservationEpoch;
import org.rtklib.java.rtcm.RtcmCallbackDecoder;
import org.rtklib.java.rtcm.RtcmDataHandler;
import org.rtklib.java.time.TimeSystem;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.*;

@DisplayName("RTCM Output Check Test")
public class RtcmOutputCheckTest {

    @Test
    @DisplayName("Check all 5 output types from RTCM")
    public void testRtcmOutput() throws IOException {
        String filePath = "D:\\tdengine-jetlinks\\jetlinks-data\\device_rtcmbin_storage\\<DEVICE_ID>\\2026-07-31\\1.rtcm3";

        System.out.println("=== Reading: " + filePath + " ===");
        byte[] data;
        try (FileInputStream fis = new FileInputStream(filePath)) {
            data = fis.readAllBytes();
        }
        System.out.println("File size: " + data.length + " bytes");

        List<ObservationEpoch> epochs = new ArrayList<>();
        List<Eph> ephList = new ArrayList<>();
        List<Geph> gephList = new ArrayList<>();
        List<Sta> stations = new ArrayList<>();

        RtcmCallbackDecoder decoder = new RtcmCallbackDecoder(new RtcmDataHandler() {
            @Override
            public void onStation(Sta sta) { stations.add(sta); }
            @Override
            public void onSsr(Ssr ssr) {}
            @Override
            public void onEph(Eph eph) { ephList.add(eph); }
            @Override
            public void onGeph(Geph geph) { gephList.add(geph); }
            @Override
            public void onObservationEpoch(ObservationEpoch epoch) { epochs.add(epoch); }
            @Override
            public void onAuxData(AuxData aux) {}
            @Override
            public void onFinish() {}
        });

        decoder.feed(data, 0, data.length);
        decoder.finish();

        System.out.println("Total epochs: " + epochs.size());
        System.out.println("Total eph: " + ephList.size());
        System.out.println("Total geph: " + gephList.size());
        System.out.println("Total stations: " + stations.size());

        int maxEpochs = Math.min(3, epochs.size());

        for (int ei = 0; ei < maxEpochs; ei++) {
            ObservationEpoch epoch = epochs.get(ei);
            double[] ymd = TimeSystem.time2ymdhms(epoch.time);
            System.out.println();
            System.out.println("=== Epoch #" + (ei + 1) + " === Time: " +
                    String.format("%04d-%02d-%02d %02d:%02d:%06.3f",
                            (int) ymd[0], (int) ymd[1], (int) ymd[2],
                            (int) ymd[3], (int) ymd[4], ymd[5]) +
                    " | Satellites: " + epoch.obsList.size());

            int maxSats = Math.min(3, epoch.obsList.size());
            for (int si = 0; si < maxSats; si++) {
                Obsd obs = epoch.obsList.get(si);
                System.out.println("  --- Sat=" + obs.sat + " ---");

                for (int j = 0; j < Constants.NFREQ + Constants.NEXOBS; j++) {
                    boolean hasP = obs.P[j] != 0.0;
                    boolean hasL = obs.L[j] != 0.0;
                    boolean hasD = obs.D[j] != 0.0f;
                    boolean hasSNR = obs.SNR[j] != 0.0f;
                    boolean hasLLI = obs.LLI[j] != 0;
                    boolean hasCode = obs.code[j] != 0;

                    if (hasP || hasL || hasD || hasSNR || hasLLI || hasCode) {
                        System.out.println("    freq[" + j + "]: "
                                + "P=" + (hasP ? String.format("%.3f", obs.P[j]) : "0")
                                + " | L=" + (hasL ? String.format("%.4f", obs.L[j]) : "0")
                                + " | D=" + (hasD ? String.format("%.4f", obs.D[j]) : "0")
                                + " | SNR=" + (hasSNR ? String.format("%.1f", obs.SNR[j]) : "0")
                                + " | LLI=" + obs.LLI[j]
                                + " | code=" + obs.code[j]
                                + " | Pstd=" + String.format("%.6f", obs.Pstd[j])
                                + " | Lstd=" + String.format("%.6f", obs.Lstd[j]));
                    }
                }
            }
        }

        System.out.println();
        System.out.println("=== Summary ===");
        int totalObs = 0;
        int hasP = 0, hasL = 0, hasD = 0, hasSNR = 0, hasLLI = 0;
        for (ObservationEpoch epoch : epochs) {
            for (Obsd obs : epoch.obsList) {
                totalObs++;
                for (int j = 0; j < Constants.NFREQ + Constants.NEXOBS; j++) {
                    if (obs.P[j] != 0.0) hasP++;
                    if (obs.L[j] != 0.0) hasL++;
                    if (obs.D[j] != 0.0f) hasD++;
                    if (obs.SNR[j] != 0.0f) hasSNR++;
                    if (obs.LLI[j] != 0) hasLLI++;
                }
            }
        }
        System.out.println("Total observations: " + totalObs);
        System.out.println("  Pseudorange (P):   " + hasP + " entries");
        System.out.println("  CarrierPhase (L):  " + hasL + " entries");
        System.out.println("  Doppler (D):       " + hasD + " entries");
        System.out.println("  SNR:               " + hasSNR + " entries");
        System.out.println("  LLI (non-zero):    " + hasLLI + " entries");
        System.out.println("  LLI (including 0): " + totalObs * (Constants.NFREQ + Constants.NEXOBS) + " total slots");
    }
}