package org.rtklib.java.vrs;

import org.rtklib.java.common.SatUtils;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.Eph;
import org.rtklib.java.data.Geph;
import org.rtklib.java.data.Nav;
import org.rtklib.java.data.Ssr;
import org.rtklib.java.data.Sta;
import org.rtklib.java.rtcm.*;
import org.rtklib.java.vrs.config.VrsOptionalConfig;
import org.rtklib.java.vrs.config.VrsRequiredConfig;
import org.rtklib.java.vrs.rtcm.Rtcm3Encoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;

public class VrsProcessor {
    private static final Logger log = LoggerFactory.getLogger(VrsProcessor.class);

    public void process(VrsRequiredConfig required, VrsOptionalConfig optional) throws IOException {
        required.validate();

        double[] vrsPos = new double[]{
            required.vrsLat * Constants.D2R,
            required.vrsLon * Constants.D2R,
            required.vrsHeight
        };

        double[] vrsEcef = new double[3];
        CoordTransform.pos2ecef(vrsPos, vrsEcef);
        log.info(String.format("VRS position: lat=%.6f deg, lon=%.6f deg, h=%.3f m",
                 required.vrsLat, required.vrsLon, required.vrsHeight));
        log.info(String.format("VRS ECEF: X=%.3f, Y=%.3f, Z=%.3f", vrsEcef[0], vrsEcef[1], vrsEcef[2]));
        log.info("Input: {}, Output: {}", required.inputRtcmPath, required.outputRtcmPath);

        byte[] fileBytes = Files.readAllBytes(Paths.get(required.inputRtcmPath));
        log.info("Read {} bytes from input RTCM file", fileBytes.length);

        Nav nav = preloadEphemeris(fileBytes);
        log.info("Ephemeris preloaded: {} satellites with ephemeris", countEphemeris(nav));

        Rtcm3Encoder encoder = new Rtcm3Encoder();
        encoder.stationId = optional.rtcmStationId;
        encoder.seqNo = optional.seqNo;

        VrsGenerator generator = new VrsGenerator(
            vrsPos, required.stationId, optional, encoder, required.outputRtcmPath, nav
        );

        RtcmCallbackDecoder decoder = new RtcmCallbackDecoder(generator);

        decoder.feed(fileBytes, 0, fileBytes.length);
        decoder.finish();

        generator.close();
        log.info("VRS processing complete");
    }

    private Nav preloadEphemeris(byte[] data) {
        Nav nav = new Nav();
        RtcmDataHandler ephCollector = new RtcmDataHandler() {
            @Override public void onRtcmMessageType(int type, int length) {}
            @Override public void onStation(Sta sta) {}
            @Override public void onEph(Eph eph) {
                if (eph.sat > 0 && eph.sat <= nav.nmax) {
                    nav.eph[eph.sat - 1] = eph;
                    if (eph.sat > nav.n) nav.n = eph.sat;
                }
            }
            @Override public void onGeph(Geph geph) {
                int[] prn = new int[1];
                int sys = SatUtils.satsys(geph.sat, prn);
                if (sys == Constants.SYS_GLO && prn[0] > 0 && prn[0] <= nav.ngmax) {
                    nav.geph[prn[0] - 1] = geph;
                    if (prn[0] > nav.ng) nav.ng = prn[0];
                }
            }
            @Override public void onObservationEpoch(ObservationEpoch epoch) {}
            @Override public void onSsr(Ssr ssr) {}
            @Override public void onAuxData(AuxData aux) {}
            @Override public void onFinish() {}
        };
        RtcmCallbackDecoder decoder = new RtcmCallbackDecoder(ephCollector);
        decoder.feed(data, 0, data.length);
        decoder.finish();
        return nav;
    }

    private int countEphemeris(Nav nav) {
        int cnt = 0;
        for (int i = 0; i < nav.nmax; i++) {
            if (nav.eph[i] != null && nav.eph[i].sat > 0) cnt++;
        }
        return cnt;
    }
}