package org.rtklib.java.vrs.config;

import java.io.Serializable;
import org.rtklib.java.constants.Constants;

public class VrsOptionalConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    public int ionoModel = Constants.IONOOPT_BRDC;
    public int tropModel = Constants.TROPOPT_SAAS;
    public double elMaskDeg = 10.0;
    public int navsys = Constants.SYS_GPS | Constants.SYS_GLO
                      | Constants.SYS_GAL | Constants.SYS_CMP;
    public int msmType = 4;
    public int stationMsgType = 1005;
    public String antType = "";
    public String recType = "";
    public boolean passThroughEph = true;
    public int rtcmStationId = 1;
    public int seqNo = 0;
}