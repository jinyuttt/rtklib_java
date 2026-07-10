package org.rtklib.java;

import org.rtklib.java.data.*;
import org.rtklib.java.rtcm.Rtcm;
import org.rtklib.java.common.ObsCode;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import java.io.*;
import java.nio.file.*;

public class CheckObsData {
    public static void main(String[] args) throws Exception {
        String roverPath = "C:\\Users\\admin\\Desktop\\<DEVICE_ID>\\2026-06-08\\1.rtcm3";
        byte[] data = Files.readAllBytes(Paths.get(roverPath));
        
        Rtcm rtcm = new Rtcm();
        int i = 0;
        boolean firstEpoch = true;
        while (i < data.length - 3) {
            int consumed = rtcm.input(data, i, data.length - i);
            if (consumed <= 0) { i++; continue; }
            i += consumed;
            
            if (rtcm.obs.n > 0 && firstEpoch) {
                firstEpoch = false;
            }
        }
    }
}