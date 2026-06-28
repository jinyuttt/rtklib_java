
package org.rtklib.java.data;

import org.rtklib.java.constants.Constants;

public class Pclk {
    public GTime time;
    public int index;
    public double[][] clk;
    public float[][] std;

    public Pclk() {
        this.time = new GTime();
        this.index = 0;
        this.clk = new double[Constants.MAXSAT][1];
        this.std = new float[Constants.MAXSAT][1];
    }
}