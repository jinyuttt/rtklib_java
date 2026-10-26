package org.rtklib.java.data;

import org.rtklib.java.config.RtkConfig;

public record RtkState(PrcOpt opt, Ssat[] ssat, RtkConfig rtkConfig, double[] snrMedian, Sol sol, double[] x, double tt) {}