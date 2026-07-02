package org.rtklib.java.data;

/**
 * Solution data class.
 * Aligned with RTKLIB sol_t structure.
 */
public class Sol {
    /** Time (GPST) */
    public GTime time;
    
    /** Time of event (GPST) */
    public GTime eventime;
    
    /** 
     * Position/velocity (m|m/s).
     * {x,y,z,vx,vy,vz} or {e,n,u,ve,vn,vu}
     */
    public double[] rr;
    
    /** 
     * Position variance/covariance (m^2).
     * {c_xx,c_yy,c_zz,c_xy,c_yz,c_zx} or
     * {c_ee,c_nn,c_uu,c_en,c_nu,c_ue}
     */
    public float[] qr;
    
    /** Velocity variance/covariance (m^2/s^2) */
    public float[] qv;
    
    /** Receiver clock bias to time systems (s) */
    public double[] dtr;
    
    /** Type (0:XYZ-ECEF, 1:ENU-baseline) */
    public byte type;
    
    /** Solution status (SOLQ_???) */
    public byte stat;
    
    /** Number of valid satellites */
    public byte ns;
    
    /** Age of differential (s) */
    public float age;
    
    /** AR ratio factor for validation */
    public float ratio;
    
    /** Previous initial AR ratio factor for validation */
    public float prev_ratio1;
    
    /** Previous final AR ratio factor for validation */
    public float prev_ratio2;
    
    /** AR ratio threshold for validation */
    public float thres;
    
    /** Reference station ID */
    public int refstationid;

    /**
     * Default constructor.
     */
    public Sol() {
        this.time = new GTime();
        this.eventime = new GTime();
        this.rr = new double[6];
        this.qr = new float[6];
        this.qv = new float[6];
        this.dtr = new double[7];
        this.type = 0;
        this.stat = 0;
        this.ns = 0;
        this.age = 0.0f;
        this.ratio = 0.0f;
        this.prev_ratio1 = 0.0f;
        this.prev_ratio2 = 0.0f;
        this.thres = 0.0f;
        this.refstationid = 0;
    }

    /**
     * Copy constructor.
     * @param other Source Sol object to copy from
     */
    public Sol(Sol other) {
        this.time = new GTime(other.time);
        this.eventime = new GTime(other.eventime);
        this.rr = other.rr.clone();
        this.qr = other.qr.clone();
        this.qv = other.qv.clone();
        this.dtr = other.dtr.clone();
        this.type = other.type;
        this.stat = other.stat;
        this.ns = other.ns;
        this.age = other.age;
        this.ratio = other.ratio;
        this.prev_ratio1 = other.prev_ratio1;
        this.prev_ratio2 = other.prev_ratio2;
        this.thres = other.thres;
        this.refstationid = other.refstationid;
    }
}