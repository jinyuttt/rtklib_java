package org.rtklib.java.vrs.config;

import java.io.Serializable;

public class VrsRequiredConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    public double vrsLat;
    public double vrsLon;
    public double vrsHeight;
    public String stationId;
    public String inputRtcmPath;
    public String outputRtcmPath;

    public VrsRequiredConfig() {
        this.stationId = "";
        this.inputRtcmPath = "";
        this.outputRtcmPath = "";
    }

    public void validate() {
        if (vrsLat < -90.0 || vrsLat > 90.0)
            throw new IllegalArgumentException("vrsLat out of range: " + vrsLat);
        if (vrsLon < -180.0 || vrsLon > 180.0)
            throw new IllegalArgumentException("vrsLon out of range: " + vrsLon);
        if (stationId == null || stationId.isEmpty())
            throw new IllegalArgumentException("stationId is required");
        if (inputRtcmPath == null || inputRtcmPath.isEmpty())
            throw new IllegalArgumentException("inputRtcmPath is required");
        if (outputRtcmPath == null || outputRtcmPath.isEmpty())
            throw new IllegalArgumentException("outputRtcmPath is required");
    }
}