package org.rtklib.java.joint;

import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.GTime;
import org.rtklib.java.data.Obsd;
import org.rtklib.java.data.Ssat;
import org.rtklib.java.time.TimeSystem;

import java.util.Map;

public class EpochAligner {
    private final double maxTimeDiff;

    public EpochAligner(double maxTimeDiff) {
        this.maxTimeDiff = maxTimeDiff;
    }

    public EpochAligner() {
        this(0.05);
    }

    public boolean isAligned(Map<String, GTime> baseTimes, GTime roverTime) {
        if (roverTime == null) return false;
        for (GTime t : baseTimes.values()) {
            if (t == null) return false;
            if (Math.abs(TimeSystem.timediff(t, roverTime)) > maxTimeDiff) return false;
        }
        return true;
    }

    public BaselineGroup align(Map<String, Obsd[]> baseObs, GTime roverTime,
                               Obsd[] roverObs, int roverObsCount,
                               BaseStation[] baseStations, int nBaseline) {
        BaselineGroup group = new BaselineGroup(nBaseline);
        group.time = roverTime;
        group.roverObs = roverObs;
        group.roverObsCount = roverObsCount;
        group.nBaseline = nBaseline;

        for (int i = 0; i < nBaseline; i++) {
            group.bases[i] = baseStations[i];
        }

        selectCommonSats(group);
        selectGlobalRefSat(group);

        return group;
    }

    public void fillCommonSats(BaselineGroup group, BaselineObs[] blObs) {
        int[] count = new int[Constants.MAXSAT + 1];
        int nBaselineWithObs = 0;

        for (int b = 0; b < group.nBaseline; b++) {
            BaselineObs bo = blObs[b];
            if (bo == null || bo.ns <= 0) continue;
            nBaselineWithObs++;
            for (int i = 0; i < bo.ns; i++) {
                if (bo.sat[i] > 0 && bo.sat[i] <= Constants.MAXSAT) {
                    count[bo.sat[i]]++;
                }
            }
        }

        int[] tmp = new int[Constants.MAXSAT];
        int ns = 0;
        for (int sat = 1; sat <= Constants.MAXSAT; sat++) {
            if (count[sat] == nBaselineWithObs && nBaselineWithObs > 0) {
                tmp[ns++] = sat;
            }
        }
        group.commonSats = new int[ns];
        System.arraycopy(tmp, 0, group.commonSats, 0, ns);
        group.nCommonSat = ns;
    }

    public void selectGlobalRefSatBySys(BaselineGroup group, Ssat[] ssat, BaselineObs[] blObs) {
        if (group.nCommonSat == 0) {
            group.globalRefSat = 0;
            return;
        }

        double bestEl = -1.0;
        int bestSat = 0;

        for (int i = 0; i < group.nCommonSat; i++) {
            int sat = group.commonSats[i];
            double maxEl = -1.0;
            for (int b = 0; b < group.nBaseline; b++) {
                BaselineObs bo = blObs[b];
                if (bo == null) continue;
                for (int j = 0; j < bo.ns; j++) {
                    if (bo.sat[j] == sat) {
                        double el = bo.azel[1 + bo.iu[j] * 2];
                        if (el > maxEl) maxEl = el;
                    }
                }
            }
            if (maxEl > bestEl) {
                bestEl = maxEl;
                bestSat = sat;
            }
        }
        group.globalRefSat = bestSat;
    }

    private void selectCommonSats(BaselineGroup group) {
        int[] count = new int[64];
        for (int i = 0; i < group.nBaseline; i++) {
            BaseStation bs = group.bases[i];
            if (bs == null || bs.obsBuf == null) continue;
            for (int j = 0; j < bs.obsCount; j++) {
                if (bs.obsBuf[j].rcv == 2) {
                    int sat = bs.obsBuf[j].sat;
                    if (sat > 0 && sat < count.length) count[sat]++;
                }
            }
        }

        int[] tmp = new int[64];
        int ns = 0;
        for (int sat = 1; sat < count.length; sat++) {
            if (count[sat] == group.nBaseline) {
                tmp[ns++] = sat;
            }
        }
        group.commonSats = new int[ns];
        System.arraycopy(tmp, 0, group.commonSats, 0, ns);
        group.nCommonSat = ns;
    }

    private void selectGlobalRefSat(BaselineGroup group) {
        if (group.nCommonSat == 0) {
            group.globalRefSat = 0;
            return;
        }
        group.globalRefSat = group.commonSats[0];
    }
}