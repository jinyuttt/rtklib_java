package org.rtklib.java.vrs;

import org.rtklib.java.common.SatUtils;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.rtcm.AuxData;
import org.rtklib.java.data.Eph;
import org.rtklib.java.data.Geph;
import org.rtklib.java.data.Nav;
import org.rtklib.java.data.Obs;
import org.rtklib.java.data.Ssr;
import org.rtklib.java.data.Sta;
import org.rtklib.java.rtcm.ObservationEpoch;
import org.rtklib.java.rtcm.RtcmDataHandler;
import org.rtklib.java.vrs.config.VrsOptionalConfig;
import org.rtklib.java.vrs.rtcm.Rtcm3Encoder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedOutputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

public class VrsGenerator implements RtcmDataHandler {
    private static final Logger log = LoggerFactory.getLogger(VrsGenerator.class);

    private final double[] vrsPos;
    private final String stationId;
    private final VrsOptionalConfig opt;
    private final Rtcm3Encoder encoder;
    private final OutputStream outputStream;

    private final Nav nav;
    private Sta baseSta;
    private boolean staWritten = false;
    private int epochCount = 0;

    public VrsGenerator(double[] vrsPos, String stationId,
                        VrsOptionalConfig opt, Rtcm3Encoder encoder,
                        String outputRtcmPath) throws IOException {
        this(vrsPos, stationId, opt, encoder, outputRtcmPath, new Nav());
    }

    public VrsGenerator(double[] vrsPos, String stationId,
                        VrsOptionalConfig opt, Rtcm3Encoder encoder,
                        String outputRtcmPath, Nav nav) throws IOException {
        this.vrsPos = vrsPos;
        this.stationId = stationId;
        this.opt = opt;
        this.encoder = encoder;
        this.nav = nav;
        this.outputStream = new BufferedOutputStream(new FileOutputStream(outputRtcmPath));
    }

    @Override
    public void onStation(Sta sta) {
        this.baseSta = sta;
        log.debug("Base station received: name={}, pos=[{},{},{}]",
                  sta.name, sta.pos[0], sta.pos[1], sta.pos[2]);
    }

    @Override
    public void onEph(Eph eph) {
        if (eph.sat > 0 && eph.sat <= nav.nmax) {
            nav.eph[eph.sat - 1] = eph;
            if (eph.sat > nav.n) nav.n = eph.sat;
        }
        if (opt.passThroughEph) {
            encoder.nav = nav;
            encoder.ephsat = eph.sat;
            int msgType = ephMsgType(eph.sat);
            if (msgType > 0) {
                byte[] msg = encoder.encode(msgType, 0, 0);
                if (msg != null) writeMsg(msg);
            }
        }
    }

    @Override
    public void onGeph(Geph geph) {
        int[] prn = new int[1];
        int sys = SatUtils.satsys(geph.sat, prn);
        if (sys == Constants.SYS_GLO && prn[0] > 0 && prn[0] <= nav.ngmax) {
            nav.geph[prn[0] - 1] = geph;
            if (prn[0] > nav.ng) nav.ng = prn[0];
        }
        if (opt.passThroughEph) {
            encoder.nav = nav;
            encoder.ephsat = geph.sat;
            byte[] msg = encoder.encode(1020, 0, 0);
            if (msg != null) writeMsg(msg);
        }
    }

    @Override
    public void onObservationEpoch(ObservationEpoch epoch) {
        if (baseSta == null) {
            log.warn("No base station info yet, skipping epoch");
            return;
        }

        if (!staWritten) {
            writeVrsStation();
            staWritten = true;
        }

        Obs vrsObs = VrsCorrection.correct(epoch, nav, baseSta, vrsPos, opt);
        if (vrsObs.n == 0) return;

        encoder.obs = vrsObs;
        encoder.sta = makeVrsSta();
        encoder.nav = nav;

        int[] systems = {Constants.SYS_GPS, Constants.SYS_GLO, Constants.SYS_GAL, Constants.SYS_CMP};
        for (int sys : systems) {
            if ((opt.navsys & sys) == 0) continue;
            int msgBase = msmMsgBase(sys, opt.msmType);
            if (msgBase > 0) {
                byte[] msg = encoder.encode(msgBase, 0, 0);
                if (msg != null) writeMsg(msg);
            }
        }

        epochCount++;
        if (epochCount % 100 == 0) {
            log.info("Processed {} epochs", epochCount);
        }
    }

    @Override
    public void onSsr(Ssr ssr) {
    }

    @Override
    public void onAuxData(AuxData aux) {
    }

    @Override
    public void onFinish() {
        log.info("VRS generation complete: {} epochs processed", epochCount);
    }

    public void close() {
        try {
            outputStream.close();
        } catch (IOException e) {
            log.error("Error closing output stream", e);
        }
    }

    private void writeVrsStation() {
        Sta vrsSta = makeVrsSta();
        encoder.sta = vrsSta;
        byte[] msg = encoder.encode(opt.stationMsgType, 0, 0);
        if (msg != null) writeMsg(msg);
        log.info("VRS station written: id={}, pos=[{},{},{}]",
                 stationId, vrsSta.pos[0], vrsSta.pos[1], vrsSta.pos[2]);
    }

    private Sta makeVrsSta() {
        Sta sta = new Sta();
        sta.name = stationId;
        sta.marker = stationId;
        CoordTransform.pos2ecef(vrsPos, sta.pos);
        sta.antdes = opt.antType;
        sta.rectype = opt.recType;
        return sta;
    }

    private void writeMsg(byte[] msg) {
        try {
            outputStream.write(msg);
        } catch (IOException e) {
            log.error("Error writing RTCM message", e);
        }
    }

    private int ephMsgType(int sat) {
        int sys = SatUtils.satsys(sat, null);
        if (sys == Constants.SYS_GPS) return 1019;
        if (sys == Constants.SYS_GLO) return 1020;
        if (sys == Constants.SYS_GAL) return 1045;
        if (sys == Constants.SYS_CMP) return 1042;
        return 0;
    }

    private int msmMsgBase(int sys, int msmType) {
        if (msmType < 1 || msmType > 7) return 0;
        int base;
        if (sys == Constants.SYS_GPS) base = 1070;
        else if (sys == Constants.SYS_GLO) base = 1080;
        else if (sys == Constants.SYS_GAL) base = 1090;
        else if (sys == Constants.SYS_SBS) base = 1100;
        else if (sys == Constants.SYS_QZS) base = 1110;
        else if (sys == Constants.SYS_CMP) base = 1120;
        else return 0;
        return base + msmType;
    }
}