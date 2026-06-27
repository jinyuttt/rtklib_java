package org.rtklib.java.data;

import org.rtklib.java.constants.Constants;

/**
 * SSR (State Space Representation) correction class.
 * Aligned with RTKLIB ssr_t structure.
 */
public class Ssr {
    /** Epoch time (GPST) {eph, clk, hrclk, ura, bias, pbias} */
    public GTime[] t0;
    
    /** SSR update interval (s) */
    public double[] udi;
    
    /** IOD SSR {eph, clk, hrclk, ura, bias, pbias} */
    public int[] iod;
    
    /** Issue of data */
    public int iode;
    
    /** Issue of data CRC for BeiDou/SBAS */
    public int iodcrc;
    
    /** URA indicator */
    public int ura;
    
    /** Satellite reference datum (0:ITRF, 1:regional) */
    public int refd;
    
    /** Delta orbit {radial, along, cross} (m) */
    public double[] deph;
    
    /** Dot delta orbit {radial, along, cross} (m/s) */
    public double[] ddeph;
    
    /** Delta clock {c0, c1, c2} (m, m/s, m/s^2) */
    public double[] dclk;
    
    /** High-rate clock correction (m) */
    public double hrclk;
    
    /** Code biases (m) */
    public float[] cbias;
    
    /** Phase biases (m) */
    public double[] pbias;
    
    /** Std-dev of phase biases (m) */
    public float[] stdpb;
    
    /** Yaw angle and yaw rate (deg, deg/s) */
    public double yaw_ang, yaw_rate;
    
    /** Update flag (0:no update, 1:update) */
    public int update;

    /**
     * Default constructor.
     */
    public Ssr() {
        this.t0 = new GTime[6];
        for (int i = 0; i < 6; i++) {
            this.t0[i] = new GTime();
        }
        this.udi = new double[6];
        this.iod = new int[6];
        this.iode = 0;
        this.iodcrc = 0;
        this.ura = 0;
        this.refd = 0;
        this.deph = new double[3];
        this.ddeph = new double[3];
        this.dclk = new double[3];
        this.hrclk = 0.0;
        this.cbias = new float[Constants.MAXCODE];
        this.pbias = new double[Constants.MAXCODE];
        this.stdpb = new float[Constants.MAXCODE];
        this.yaw_ang = 0.0;
        this.yaw_rate = 0.0;
        this.update = 0;
    }

    /**
     * Copy constructor.
     * @param other Source Ssr object to copy from
     */
    public Ssr(Ssr other) {
        this.t0 = new GTime[6];
        for (int i = 0; i < 6; i++) {
            this.t0[i] = new GTime(other.t0[i]);
        }
        this.udi = other.udi.clone();
        this.iod = other.iod.clone();
        this.iode = other.iode;
        this.iodcrc = other.iodcrc;
        this.ura = other.ura;
        this.refd = other.refd;
        this.deph = other.deph.clone();
        this.ddeph = other.ddeph.clone();
        this.dclk = other.dclk.clone();
        this.hrclk = other.hrclk;
        this.cbias = other.cbias.clone();
        this.pbias = other.pbias.clone();
        this.stdpb = other.stdpb.clone();
        this.yaw_ang = other.yaw_ang;
        this.yaw_rate = other.yaw_rate;
        this.update = other.update;
    }
}