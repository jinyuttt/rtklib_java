package org.rtklib.java;

import org.junit.jupiter.api.Test;
import org.rtklib.java.rtcm.Rtcm;
import org.rtklib.java.time.TimeSystem;
import org.rtklib.java.data.GTime;
import java.io.*;

public class TimeDebugTest {
    @Test
    void testMessageOrder() throws Exception {
        String file = "C:\\Users\\admin\\Desktop\\<DEVICE_ID>\\2026-06-08\\1.rtcm3";
        byte[] data = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(file));
        
        Rtcm rtcm = new Rtcm();
        int offset = 0;
        int msgIdx = 0;
        while (offset < data.length && msgIdx < 20) {
            int consumed = rtcm.input(data, offset, data.length - offset);
            if (consumed <= 0) { offset++; continue; }
            offset += consumed;
            msgIdx++;
            int type = rtcm.type;
            if (type == 1042 || type == 1124 || type == 1005 || type == 1033) {
                int[] wk = new int[1];
                double tow = TimeSystem.time2gpst(rtcm.time, wk);
                double[] ymd = TimeSystem.time2ymdhms(rtcm.time);
                System.out.printf("Msg #%d: type=%d, time=%04d-%02d-%02d %02d:%02d:%06.3f (week=%d tow=%.1f)%n",
                    msgIdx, type, (int)ymd[0], (int)ymd[1], (int)ymd[2], (int)ymd[3], (int)ymd[4], ymd[5], wk[0], tow);
            }
        }
    }
}