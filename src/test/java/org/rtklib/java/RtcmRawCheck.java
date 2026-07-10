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
        String roverPath = "C:\\Users\\admin\\Desktop\\<DEVICE_ID>\\2026-06-08\\1.rtcm3";
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
    }
}