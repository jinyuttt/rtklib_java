package org.rtklib.java.vrs;

import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.rtcm.*;
import org.rtklib.java.constants.Constants;

import java.nio.file.*;

public class VrsOutputDiag {
    public static void main(String[] args) throws Exception {
        String file = args.length > 0 ? args[0] : "D:\\code\\rtklib_java\\rtklib-vrs\\target\\vrs-reverse-validation.rtcm3";
        byte[] data = Files.readAllBytes(Paths.get(file));
        System.out.printf("VRS输出文件: %d bytes%n", data.length);

        int[] obsEpochCount = new int[1];
        int[] ephCount = new int[1];

        RtcmDataHandler handler = new RtcmDataHandler() {
            @Override public void onStation(Sta s) {
                double[] pos = new double[3];
                CoordTransform.ecef2pos(s.pos, pos);
                System.out.printf("站坐标: lat=%.9f lon=%.9f h=%.4f%n",
                        pos[0]*180/Math.PI, pos[1]*180/Math.PI, pos[2]);
                System.out.printf("ECEF: X=%.4f Y=%.4f Z=%.4f%n", s.pos[0], s.pos[1], s.pos[2]);
            }
            @Override public void onEph(Eph eph) { ephCount[0]++; }
            @Override public void onGeph(Geph geph) { ephCount[0]++; }
            @Override public void onObservationEpoch(ObservationEpoch epoch) {
                obsEpochCount[0]++;
                if (obsEpochCount[0] <= 3) {
                    System.out.printf("历元 #%d: time=%s nsat=%d%n", obsEpochCount[0], epoch.time, epoch.getObservations().size());
                    for (Obsd o : epoch.getObservations()) {
                        System.out.printf("  sat=%d P[0]=%.4f L[0]=%.4f code[0]=%d rcv=%d%n",
                                o.sat, o.P[0], o.L[0], o.code[0], o.rcv);
                    }
                }
            }
            @Override public void onSsr(Ssr ssr) {}
            @Override public void onAuxData(AuxData aux) {}
            @Override public void onFinish() {}
        };

        RtcmCallbackDecoder decoder = new RtcmCallbackDecoder(handler);
        decoder.feed(data, 0, data.length);
        decoder.finish();

        System.out.printf("%n总计: obsEpochs=%d eph=%d%n", obsEpochCount[0], ephCount[0]);
    }
}