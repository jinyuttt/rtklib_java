package org.rtklib.java.networkvrs.netsol;

import org.rtklib.java.data.GTime;

import java.util.List;

/**
 * 单历元双差观测集合。
 */
public record DdEpoch(GTime time, List<DdObservation> observations) {
}
