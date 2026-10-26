package org.rtklib.java.joint;

import java.io.Serializable;

public class JointConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    public int maxBaseline = 3;
    public double maxTimeDiff = 0.05;

    public boolean sharedIono = true;
    public boolean sharedTrop = false;
    public double sharedIonoMaxDist = 10000.0;

    public boolean independentAmbFix = true;
    public boolean jointAmbFix = false;

    public boolean blockDiagonalR = true;
    public boolean modelCrossCorrelation = false;

    public boolean jointCycleSlip = true;
}