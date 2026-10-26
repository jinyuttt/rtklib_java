package org.rtklib.java.joint;

import org.rtklib.java.data.GTime;
import org.rtklib.java.data.Nav;
import org.rtklib.java.data.Obsd;
import org.rtklib.java.data.PrcOpt;

import java.util.HashMap;
import java.util.Map;

public class JointProcessor {
    private JointRtk jrtk;
    private EpochAligner aligner;
    private Nav nav;

    private Obsd[] roverObsBuf;
    private int roverObsCount;
    private GTime roverTime;

    private final Map<String, Obsd[]> baseObsMap = new HashMap<>();
    private final Map<String, GTime> baseTimeMap = new HashMap<>();

    public JointProcessor() {
        aligner = new EpochAligner();
    }

    public void init(JointConfig config, PrcOpt opt, double[][] baseCoords) {
        jrtk = new JointRtk();
        jrtk.opt = opt;
        jrtk.jointConfig = config;
        jrtk.nBaseline = baseCoords.length;
        jrtk.bases = new BaseStation[config.maxBaseline];
        jrtk.baselineOffset = new int[config.maxBaseline + 1];
        jrtk.baselineAmbCount = new int[config.maxBaseline];

        for (int i = 0; i < baseCoords.length; i++) {
            jrtk.bases[i] = new BaseStation("B" + i, baseCoords[i]);
        }

        aligner = new EpochAligner(config.maxTimeDiff);
    }

    public void feedRover(Obsd[] obs, int n, GTime time) {
        this.roverObsBuf = obs;
        this.roverObsCount = n;
        this.roverTime = time;
    }

    public void feedBase(String baseId, Obsd[] obs, int n, GTime time) {
        BaseStation bs = findBase(baseId);
        if (bs != null) {
            bs.obsBuf = obs;
            bs.obsCount = n;
            bs.obsTime = time;
        }
        baseObsMap.put(baseId, obs);
        baseTimeMap.put(baseId, time);
    }

    public void feedNav(Nav nav) {
        this.nav = nav;
    }

    public JointRtk process() {
        if (jrtk == null || roverObsBuf == null) return null;
        if (!aligner.isAligned(baseTimeMap, roverTime)) return null;

        BaselineGroup group = aligner.align(baseObsMap, roverTime,
                roverObsBuf, roverObsCount, jrtk.bases, jrtk.nBaseline);

        JointCore.rtkpos(jrtk, roverObsBuf, roverObsCount, jrtk.bases, jrtk.nBaseline, nav);

        return jrtk;
    }

    private BaseStation findBase(String id) {
        if (jrtk == null || jrtk.bases == null) return null;
        for (BaseStation bs : jrtk.bases) {
            if (bs != null && id.equals(bs.id)) return bs;
        }
        return null;
    }
}