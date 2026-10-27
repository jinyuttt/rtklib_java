package org.rtklib.java.vrs;

import org.rtklib.java.common.SatUtils;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.rtcm.AuxData;
import org.rtklib.java.data.Eph;
import org.rtklib.java.data.GTime;
import org.rtklib.java.data.Geph;
import org.rtklib.java.data.Nav;
import org.rtklib.java.data.Obs;
import org.rtklib.java.data.Ssr;
import org.rtklib.java.data.Sta;
import org.rtklib.java.time.TimeSystem;
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
import java.util.List;

public class VrsGenerator implements RtcmDataHandler {
    private static final Logger log = LoggerFactory.getLogger(VrsGenerator.class);

    private final double[] vrsPos;
    private final String stationId;
    private final VrsOptionalConfig opt;
    private final Rtcm3Encoder encoder;
    private final OutputStream outputStream;

    private final Nav nav;
    private final List<Eph> ephPool;
    private final List<Geph> gephPool;
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
        this(vrsPos, stationId, opt, encoder, outputRtcmPath, nav, null, null);
    }

    public VrsGenerator(double[] vrsPos, String stationId,
                        VrsOptionalConfig opt, Rtcm3Encoder encoder,
                        String outputRtcmPath, Nav nav,
                        List<Eph> ephPool, List<Geph> gephPool) throws IOException {
        this.vrsPos = vrsPos;
        this.stationId = stationId;
        this.opt = opt;
        this.encoder = encoder;
        this.nav = nav;
        this.ephPool = ephPool;
        this.gephPool = gephPool;
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

        selectEphemerisForEpoch(epoch.time);

        Obs vrsObs = VrsCorrection.correct(epoch, nav, baseSta, vrsPos, opt);
        if (vrsObs.n == 0) return;

        encoder.obs = vrsObs;
        encoder.sta = makeVrsSta();
        encoder.nav = nav;
        encoder.msmEpochSeq++;

        int[] systems = {Constants.SYS_GPS, Constants.SYS_GLO, Constants.SYS_GAL, Constants.SYS_CMP};
        for (int sys : systems) {
            if ((opt.navsys & sys) == 0) continue;
            int msgBase = msmMsgBase(sys, opt.msmType);
            if (msgBase <= 0) continue;
            encodeSystemBatched(sys, msgBase);
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

    private void selectEphemerisForEpoch(GTime time) {
        if (ephPool != null && !ephPool.isEmpty()) {
            for (Eph e : ephPool) {
                if (e == null || e.sat <= 0 || e.sat > nav.nmax) continue;
                Eph cur = nav.eph[e.sat - 1];
                if (cur == null || Math.abs(TimeSystem.timediff(time, e.toe))
                        < Math.abs(TimeSystem.timediff(time, cur.toe))) {
                    nav.eph[e.sat - 1] = e;
                }
            }
        }
        if (gephPool != null && !gephPool.isEmpty()) {
            for (Geph g : gephPool) {
                if (g == null) continue;
                int[] prn = new int[1];
                int sys = SatUtils.satsys(g.sat, prn);
                if (sys != Constants.SYS_GLO || prn[0] <= 0 || prn[0] > nav.ngmax) continue;
                Geph cur = nav.geph[prn[0] - 1];
                if (cur == null || Math.abs(TimeSystem.timediff(g.toe, time))
                        < Math.abs(TimeSystem.timediff(cur.toe, time))) {
                    nav.geph[prn[0] - 1] = g;
                }
            }
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

    /**
     * 编码一个系统的 MSM 消息；当 nsat*nsig > 64 时按卫星分批，
     * 各批 sigmask 保持一致，除末批外 sync=1 让解码端累积。
     */
    private void encodeSystemBatched(int sys, int msgBase) {
        int[] cnt = org.rtklib.java.vrs.rtcm.Rtcm3MsmEncoder.countSatSig(encoder, sys);
        int nsat = cnt[0];
        int nsig = cnt[1];
        if (nsat == 0 || nsig == 0) return;

        if (nsat * nsig <= 64) {
            byte[] msg = encoder.encode(msgBase, 0, 0);
            if (msg != null) writeMsg(msg);
            return;
        }

        // 分批：每批最多 floor(64/nsig) 颗卫星，sigmask 跨批保持一致
        int batchSize = Math.max(1, 64 / nsig);
        int nBatches = (nsat + batchSize - 1) / batchSize;
        log.debug("sys={} nsat={} nsig={} -> {} batches of <={} sats", sys, nsat, nsig, nBatches, batchSize);
        for (int b = 0; b < nBatches; b++) {
            int batchStart = b * batchSize + 1;             // 1-based 全局序号
            int sync = (b < nBatches - 1) ? 1 : 0;
            byte[] msg = encoder.encodeMsmBatch(msgBase, sync, batchStart, batchSize, nsig);
            if (msg != null) writeMsg(msg);
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