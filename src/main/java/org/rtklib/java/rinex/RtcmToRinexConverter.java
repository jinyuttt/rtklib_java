package org.rtklib.java.rinex;

import org.rtklib.java.rtcm.*;
import org.rtklib.java.data.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class RtcmToRinexConverter {
    private static final Logger log = LoggerFactory.getLogger(RtcmToRinexConverter.class);

    private RinexObsWriter obsWriter;
    private RinexNavWriter navWriter;
    private double version;
    private String outputDir;
    private String stationName;
    private final List<ObservationEpoch> epochList = new ArrayList<>();
    private final List<Eph> ephList = new ArrayList<>();
    private final List<Geph> gephList = new ArrayList<>();
    private Sta stationInfo = null;

    public RtcmToRinexConverter(double version, String outputDir, String stationName) {
        this.version = version;
        this.outputDir = outputDir;
        this.stationName = stationName;
    }

    public boolean convert(byte[] rtcmData, int len) {
        try {
            RtcmDataHandler handler = new RtcmDataHandler() {
                @Override public void onObservationEpoch(ObservationEpoch epoch) {
                    epochList.add(epoch);
                }
                @Override public void onEph(Eph eph) {
                    ephList.add(eph);
                }
                @Override public void onGeph(Geph geph) {
                    gephList.add(geph);
                }
                @Override public void onStation(Sta sta) {
                    stationInfo = new Sta(sta);
                }
                @Override public void onSsr(Ssr ssr) {}
                @Override public void onAuxData(AuxData aux) {}
                @Override public void onFinish() {}
            };

            RtcmCallbackDecoder decoder = new RtcmCallbackDecoder(handler);
            decoder.feed(rtcmData, 0, len);
            decoder.finish();

            if (epochList.isEmpty()) {
                log.warn("No observation data accumulated");
                return false;
            }

            initializeWriters();

            List<Obsd> allObs = new ArrayList<>();
            for (ObservationEpoch epoch : epochList) {
                allObs.addAll(epoch.obsList);
            }

            Obs obs = new Obs();
            obs.data = allObs.toArray(new Obsd[0]);
            obs.n = allObs.size();
            obsWriter.setObsData(obs);
            obsWriter.write();

            if (!ephList.isEmpty() || !gephList.isEmpty()) {
                Nav nav = new Nav();
                int ephIdx = 0;
                for (Eph e : ephList) {
                    if (e.sat > 0 && e.sat <= nav.eph.length) {
                        nav.eph[e.sat - 1] = e;
                    }
                }
                for (Geph g : gephList) {
                    int[] prnArr = new int[1];
                    int sys = org.rtklib.java.common.SatUtils.satsys(g.sat, prnArr);
                    if (sys == org.rtklib.java.constants.Constants.SYS_GLO && prnArr[0] > 0
                            && prnArr[0] <= nav.geph.length) {
                        nav.geph[prnArr[0] - 1] = g;
                    }
                }
                navWriter.setNavData(nav);
                navWriter.write();
            }

            return true;
        } catch (Exception e) {
            log.error("Error converting RTCM to RINEX", e);
            return false;
        }
    }

    private void initializeWriters() {
        Sta sta = stationInfo;
        if (sta == null) {
            sta = new Sta();
        }
        if (sta.name == null || sta.name.isEmpty()) {
            sta.name = stationName != null && !stationName.isEmpty() ? stationName : "UNKNOWN";
        }
        if (sta.marker == null || sta.marker.isEmpty()) {
            sta.marker = sta.name;
        }

        String obsFile = java.nio.file.Paths.get(outputDir, stationName + ".obs").toString();
        obsWriter = new RinexObsWriter(version, obsFile, sta);

        String navFile = java.nio.file.Paths.get(outputDir, stationName + ".nav").toString();
        navWriter = new RinexNavWriter(version, navFile);
    }

    public boolean convertStream(byte[] rtcmStream) {
        return convert(rtcmStream, rtcmStream.length);
    }

    public Rtcm getRtcm() {
        return new Rtcm();
    }

    public RinexObsWriter getObsWriter() {
        return obsWriter;
    }

    public RinexNavWriter getNavWriter() {
        return navWriter;
    }
}
