package org.rtklib.java;

import org.rtklib.java.data.*;
import org.rtklib.java.rtcm.AuxData;
import org.rtklib.java.rtcm.ObservationEpoch;
import org.rtklib.java.rtcm.RtcmCallbackDecoder;
import org.rtklib.java.rtcm.RtcmDataHandler;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class RtcmRawCheck {
    public static void main(String[] args) throws Exception {
        String roverPath = "C:\\Users\\admin\\Desktop\\540423494727\\2026-06-08\\1.rtcm3";
        byte[] data = Files.readAllBytes(Paths.get(roverPath));

        Set<Integer> satSet = new TreeSet<>();
        List<Eph> ephList = new ArrayList<>();
        List<Geph> gephList = new ArrayList<>();

        RtcmCallbackDecoder decoder = new RtcmCallbackDecoder(new RtcmDataHandler() {
            int epochCount = 0;
            @Override
            public void onStation(Sta sta) {}
            @Override
            public void onSsr(Ssr ssr) {}
            @Override
            public void onEph(Eph eph) {
                ephList.add(eph);
            }
            @Override
            public void onGeph(Geph geph) {
                gephList.add(geph);
            }
            @Override
            public void onObservationEpoch(ObservationEpoch epoch) {
                epochCount++;
                if (epochCount == 1) {
                    System.out.println("=== First Epoch ===");
                    System.out.println("Num obs: " + epoch.obsList.size());
                    for (Obsd o : epoch.obsList) {
                        int[] prnArr = new int[1];
                        int sys = SatUtils.satsys(o.sat, prnArr);
                        System.out.print("  C" + String.format("%02d", prnArr[0]) + ":");
                        for (int j = 0; j < Constants.NFREQ + Constants.NEXOBS; j++) {
                            if (o.P[j] != 0.0 || o.L[j] != 0.0) {
                                System.out.print(" [" + j + "]P=" + String.format("%.3f", o.P[j]) + " L=" + String.format("%.3f", o.L[j]) + " c=" + o.code[j]);
                            }
                        }
                        System.out.println();
                    }
                }
                for (Obsd o : epoch.obsList) {
                    satSet.add(o.sat);
                }
            }
            @Override
            public void onAuxData(AuxData aux) {}
            @Override
            public void onFinish() {}
        });

        decoder.feed(data, 0, data.length);
        decoder.finish();

        System.out.println("=== Observation Satellites ===");
        for (int sat : satSet) {
            int[] prnArr = new int[1];
            int sys = SatUtils.satsys(sat, prnArr);
            String sysName;
            switch (sys) {
                case Constants.SYS_GPS: sysName = "GPS"; break;
                case Constants.SYS_CMP: sysName = "BDS"; break;
                case Constants.SYS_GLO: sysName = "GLO"; break;
                case Constants.SYS_GAL: sysName = "GAL"; break;
                case Constants.SYS_QZS: sysName = "QZS"; break;
                default: sysName = "SYS" + sys; break;
            }
            System.out.println("  sat=" + sat + " sys=" + sysName + " prn=" + prnArr[0]);
        }

        System.out.println("\n=== Ephemeris (GPS/BDS/GAL/QZS) ===");
        for (Eph eph : ephList) {
            int[] prnArr = new int[1];
            int sys = SatUtils.satsys(eph.sat, prnArr);
            String sysName;
            switch (sys) {
                case Constants.SYS_GPS: sysName = "GPS"; break;
                case Constants.SYS_CMP: sysName = "BDS"; break;
                case Constants.SYS_GAL: sysName = "GAL"; break;
                case Constants.SYS_QZS: sysName = "QZS"; break;
                default: sysName = "SYS" + sys; break;
            }
            System.out.println("  sat=" + eph.sat + " sys=" + sysName + " prn=" + prnArr[0]);
        }

        System.out.println("\n=== GLONASS Ephemeris ===");
        for (Geph geph : gephList) {
            int[] prnArr = new int[1];
            SatUtils.satsys(geph.sat, prnArr);
            System.out.println("  sat=" + geph.sat + " prn=" + prnArr[0]);
        }

        System.out.println("\n=== Summary ===");
        System.out.println("Total obs sats: " + satSet.size());
        System.out.println("Total eph: " + ephList.size());
        System.out.println("Total geph: " + gephList.size());
    }
}