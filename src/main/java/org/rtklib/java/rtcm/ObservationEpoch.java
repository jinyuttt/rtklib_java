package org.rtklib.java.rtcm;

import org.rtklib.java.data.GTime;
import org.rtklib.java.data.Obsd;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ObservationEpoch implements Serializable {
    private static final long serialVersionUID = 1L;

    public GTime time;
    public String sourceId;
    public final List<Obsd> obsList;

    public ObservationEpoch(GTime time) {
        this.time = new GTime(time);
        this.sourceId = null;
        this.obsList = new ArrayList<>();
    }

    public ObservationEpoch(GTime time, String sourceId) {
        this.time = new GTime(time);
        this.sourceId = sourceId;
        this.obsList = new ArrayList<>();
    }

    public List<Obsd> getObservations() {
        return Collections.unmodifiableList(obsList);
    }
}