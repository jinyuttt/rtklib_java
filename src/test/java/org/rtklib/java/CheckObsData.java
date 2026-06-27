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
        String roverPath = "C:\\Users\\admin\\Desktop\\540423494727\\2026-06-08\\1.rtcm3";
        byte[] data = Files.readAllBytes(Paths.get(roverPath));
        
        Rtcm rtcm = new Rtcm();
        int i = 0;
        boolean firstEpoch = true;
        while (i < data.length - 3) {
            int consumed = rtcm.input(data, i, data.length - i);
            if (consumed <= 0) { i++; continue; }
            i += consumed;
            
            if (rtcm.obs.n > 0 && firstEpoch) {
                System.out.println("First epoch from RTCM: n=" + rtcm.obs.n);
                for (int j = 0; j < Math.min(3, rtcm.obs.n); j++) {
                    Obsd o = rtcm.obs.data[j];
                    int sys = SatUtils.satsys(o.sat, null);
                    String sysChar = ObsCode.satToSysChar(o.sat);
                    int prn = ObsCode.satToPrn(o.sat);
                    System.out.println("  Sat " + sysChar + prn + " (sat=" + o.sat + " sys=" + sys + "):");
                    for (int f = 0; f < Constants.NFREQ; f++) {
                        if (o.P[f] != 0.0 || o.L[f] != 0.0) {
                            String obsType = ObsCode.code2obs(o.code[f]);
                            int freqIdx = ObsCode.code2idx(sys, o.code[f]);
                            System.out.println("    freq[" + f + "]: code=" + o.code[f] + " type=" + obsType + " freqIdx=" + freqIdx + 
                                " P=" + o.P[f] + " L=" + o.L[f] + " SNR=" + o.SNR[f]);
                        }
                    }
                }
                firstEpoch = false;
            }
        }
    }
}