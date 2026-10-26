package org.rtklib.java.joint;

import org.rtklib.java.data.GTime;
import org.rtklib.java.data.Obsd;

import java.io.Serializable;

public class BaseStation implements Serializable {
    private static final long serialVersionUID = 1L;

    public String id;
    public double[] rb;
    public Obsd[] obsBuf;
    public int obsCount;
    public GTime obsTime;
    public int nSat;
    public int[] satIndex;
    public int refSat;

    public BaseStation() {
        rb = new double[3];
    }

    public BaseStation(String id, double[] rb) {
        this.id = id;
        this.rb = rb.clone();
    }
}