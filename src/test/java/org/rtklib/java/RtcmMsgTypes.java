package org.rtklib.java;

import org.rtklib.java.rtcm.Rtcm;
import java.io.*;
import java.nio.file.*;
import java.util.*;

public class RtcmMsgTypes {
    public static void main(String[] args) throws Exception {
        String roverPath = "C:\\Users\\admin\\Desktop\\<DEVICE_ID>\\2026-06-08\\1.rtcm3";
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


    }
}