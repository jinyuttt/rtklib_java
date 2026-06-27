package org.rtklib.java.data;

import org.rtklib.java.constants.Constants;

/**
 * Observation data container class.
 * Aligned with RTKLIB obs_t structure.
 */
public class Obs {
    /** Number of observation data records */
    public int n;
    
    /** Maximum allocated records */
    public int nmax;
    
    /** Epoch flag (0:ok, 1:power failure, >1:event flag) */
    public int flag;
    
    /** Count of receiver events */
    public int rcvcount;
    
    /** Time mark count */
    public int tmcount;
    
    /** Observation data records array */
    public Obsd[] data;

    /**
     * Default constructor.
     */
    public Obs() {
        this.n = 0;
        this.nmax = Constants.MAXOBS;
        this.flag = 0;
        this.rcvcount = 0;
        this.tmcount = 0;
        this.data = new Obsd[this.nmax];
        for (int i = 0; i < this.nmax; i++) {
            this.data[i] = new Obsd();
        }
    }

    /**
     * Copy constructor.
     * @param other Source Obs object to copy from
     */
    public Obs(Obs other) {
        this.n = other.n;
        this.nmax = other.nmax;
        this.flag = other.flag;
        this.rcvcount = other.rcvcount;
        this.tmcount = other.tmcount;
        this.data = new Obsd[this.nmax];
        for (int i = 0; i < this.n; i++) {
            this.data[i] = new Obsd(other.data[i]);
        }
        for (int i = this.n; i < this.nmax; i++) {
            this.data[i] = new Obsd();
        }
    }

    /**
     * Clear all observation data.
     */
    public void clear() {
        this.n = 0;
        this.flag = 0;
        this.rcvcount = 0;
        this.tmcount = 0;
        for (int i = 0; i < this.nmax; i++) {
            this.data[i] = new Obsd();
        }
    }
}