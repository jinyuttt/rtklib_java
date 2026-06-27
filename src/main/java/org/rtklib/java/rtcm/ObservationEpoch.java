package org.rtklib.java.rtcm;

import org.rtklib.java.data.GTime;
import org.rtklib.java.data.Obsd;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ObservationEpoch {

    public GTime time;
    public final List<Obsd> obsList;

    public ObservationEpoch(GTime time) {
        this.time = new GTime(time);
        this.obsList = new ArrayList<>();
    }

    public List<Obsd> getObservations() {
        return Collections.unmodifiableList(obsList);
    }
}