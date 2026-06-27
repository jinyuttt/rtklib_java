package org.rtklib.java;

import org.rtklib.java.rtcm.Rtcm;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class RtcmMsgTypes {
    public static void main(String[] args) throws Exception {
        String roverPath = "C:\\Users\\admin\\Desktop\\540423494727\\2026-06-08\\1.rtcm3";
        byte[] data = Files.readAllBytes(Paths.get(roverPath));

        Rtcm rtcm = new Rtcm();
        Map<Integer, Integer> typeCounts = new TreeMap<>();
        int pos = 0;
        int totalMsgs = 0;

        while (pos < data.length) {
            int consumed = rtcm.input(data, pos, data.length - pos);
            if (consumed > 0) {
                int type = rtcm.type;
                typeCounts.merge(type, 1, Integer::sum);
                totalMsgs++;
                pos += consumed;
            } else if (consumed == 0) {
                pos++;
            } else {
                pos++;
            }
        }

        System.out.println("Total messages: " + totalMsgs);
        System.out.println("\nMessage type distribution:");
        for (Map.Entry<Integer, Integer> e : typeCounts.entrySet()) {
            String desc;
            int t = e.getKey();
            if (t >= 1071 && t <= 1077) desc = "GPS MSM" + (t - 1071);
            else if (t >= 1081 && t <= 1087) desc = "GLO MSM" + (t - 1081);
            else if (t >= 1091 && t <= 1097) desc = "GAL MSM" + (t - 1091);
            else if (t >= 1101 && t <= 1107) desc = "SBS MSM" + (t - 1101);
            else if (t >= 1111 && t <= 1117) desc = "QZS MSM" + (t - 1111);
            else if (t >= 1121 && t <= 1127) desc = "BDS MSM" + (t - 1121);
            else if (t >= 1131 && t <= 1137) desc = "IRN MSM" + (t - 1131);
            else if (t == 1019) desc = "GPS Eph";
            else if (t == 1020) desc = "GLO Eph";
            else if (t == 1042) desc = "BDS Eph";
            else if (t == 1045 || t == 1046) desc = "GAL Eph";
            else if (t == 1044) desc = "QZS Eph";
            else if (t == 1005 || t == 1006) desc = "Station";
            else desc = "Other";
            System.out.println(String.format("  Type %4d (%s): %d", e.getKey(), desc, e.getValue()));
        }
    }
}