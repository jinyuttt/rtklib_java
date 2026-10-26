package org.rtklib.java.joint;

import org.rtklib.java.data.GTime;
import org.rtklib.java.data.Obsd;

public class BaselineGroup {
    public GTime time;
    public Obsd[] roverObs;
    public int roverObsCount;
    public BaseStation[] bases;
    public int nBaseline;
    public int[] commonSats;
    public int nCommonSat;
    public int globalRefSat;

    public BaselineGroup(int maxBaseline) {
        bases = new BaseStation[maxBaseline];
        commonSats = new int[0];
    }
}