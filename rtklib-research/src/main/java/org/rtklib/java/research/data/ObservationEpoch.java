package org.rtklib.java.research.data;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import org.rtklib.java.research.common.GTime;

/**
 * 一个历元所有卫星的观测数据（research模块自有）。
 */
public class ObservationEpoch implements Serializable {
    private static final long serialVersionUID = 1L;

    public GTime time;
    public List<Observation> observations;
    public int receiverId;

    public ObservationEpoch() {
        this.time = new GTime();
        this.observations = new ArrayList<>();
        this.receiverId = 1;
    }

    public ObservationEpoch(GTime time, int receiverId) {
        this.time = new GTime(time);
        this.observations = new ArrayList<>();
        this.receiverId = receiverId;
    }

    public void addObservation(Observation obs) {
        this.observations.add(obs);
    }

    public int size() {
        return observations.size();
    }

    public List<Observation> getBySatellite(int sat) {
        List<Observation> result = new ArrayList<>();
        for (Observation obs : observations) {
            if (obs.sat == sat) result.add(obs);
        }
        return result;
    }

    public List<Observation> getByFrequency(int freqIndex) {
        List<Observation> result = new ArrayList<>();
        for (Observation obs : observations) {
            if (obs.freqIndex == freqIndex) result.add(obs);
        }
        return result;
    }

    public List<Observation> getValidObservations() {
        List<Observation> result = new ArrayList<>();
        for (Observation obs : observations) {
            if (obs.isValid()) result.add(obs);
        }
        return result;
    }

    @Override
    public String toString() {
        return String.format("Epoch[%s, rcv=%d, nObs=%d]", time, receiverId, observations.size());
    }
}