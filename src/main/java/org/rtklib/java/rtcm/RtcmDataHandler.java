package org.rtklib.java.rtcm;

import org.rtklib.java.data.Eph;
import org.rtklib.java.data.Geph;
import org.rtklib.java.data.Ssr;
import org.rtklib.java.data.Sta;

public interface RtcmDataHandler {

    void onStation(Sta sta);

    void onSsr(Ssr ssr);

    void onEph(Eph eph);

    void onGeph(Geph geph);

    void onObservationEpoch(ObservationEpoch epoch);

    void onAuxData(AuxData aux);

    void onFinish();
}