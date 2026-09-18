package org.rtklib.java.data;

import java.io.Serializable;

/**
 * Solution data class.
 * Aligned with RTKLIB sol_t structure.
 */
public class Sol implements Serializable {
    private static final long serialVersionUID = 1L;
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

    /** Geometric DOP */
    public float gdop;
    /** Position DOP */
    public float pdop;
    /** Horizontal DOP */
    public float hdop;
    /** Vertical DOP */
    public float vdop;

    /**
     * [Java扩展] H矩阵位置分量等效设计矩阵(3x3，行优先9个元素)。
     * 由relpos()中H[:,0:3]和R计算得到，diagMask & DIAG_HPOS时有效。
     * null表示未计算或计算失败。
     */
    public double[] hPos = null;

    /**
     * [Java扩展] 新息向量RMS = sqrt(v^T R^{-1} v / nv)。
     * diagMask & DIAG_INNOVATION时有效，NaN表示未计算。
     */
    public double innovRms = Double.NaN;

    /**
     * [Java扩展] 最大标准化新息 = max(|v_i| / sqrt(R_ii))。
     * diagMask & DIAG_INNOVATION时有效，NaN表示未计算。
     */
    public double innovMax = Double.NaN;

    /**
     * [Java扩展] 双差观测数nv。
     * diagMask & DIAG_INNOVATION时有效，0表示未计算。
     */
    public int ddObsCount = 0;

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
        this.gdop = 0.0f;
        this.pdop = 0.0f;
        this.hdop = 0.0f;
        this.vdop = 0.0f;
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
        this.gdop = other.gdop;
        this.pdop = other.pdop;
        this.hdop = other.hdop;
        this.vdop = other.vdop;
        this.hPos = other.hPos != null ? other.hPos.clone() : null;
        this.innovRms = other.innovRms;
        this.innovMax = other.innovMax;
        this.ddObsCount = other.ddObsCount;
    }
}