package org.rtklib.java.vrs;

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
import java.util.ArrayList;
import java.util.List;

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

        List<Eph> ephPool = new ArrayList<>();
        List<Geph> gephPool = new ArrayList<>();
        preloadEphemeris(fileBytes, ephPool, gephPool);
        Nav nav = new Nav();
        log.info("Ephemeris preloaded: {} broadcast, {} GLONASS", ephPool.size(), gephPool.size());

        Rtcm3Encoder encoder = new Rtcm3Encoder();
        encoder.stationId = optional.rtcmStationId;
        encoder.seqNo = optional.seqNo;

        VrsGenerator generator = new VrsGenerator(
            vrsPos, required.stationId, optional, encoder, required.outputRtcmPath,
            nav, ephPool, gephPool
        );

        RtcmCallbackDecoder decoder = new RtcmCallbackDecoder(generator);

        decoder.feed(fileBytes, 0, fileBytes.length);
        decoder.finish();

        generator.close();
        log.info("VRS processing complete");
    }

    private void preloadEphemeris(byte[] data, List<Eph> ephPool, List<Geph> gephPool) {
        RtcmDataHandler ephCollector = new RtcmDataHandler() {
            @Override public void onRtcmMessageType(int type, int length) {}
            @Override public void onStation(Sta sta) {}
            @Override public void onEph(Eph eph) {
                if (eph != null && eph.sat > 0) ephPool.add(eph);
            }
            @Override public void onGeph(Geph geph) {
                if (geph != null) gephPool.add(geph);
            }
            @Override public void onObservationEpoch(ObservationEpoch epoch) {}
            @Override public void onSsr(Ssr ssr) {}
            @Override public void onAuxData(AuxData aux) {}
            @Override public void onFinish() {}
        };
        RtcmCallbackDecoder decoder = new RtcmCallbackDecoder(ephCollector);
        decoder.feed(data, 0, data.length);
        decoder.finish();
    }
}