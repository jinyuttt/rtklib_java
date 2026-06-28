package org.rtklib.java.data;

import java.util.Arrays;
import org.rtklib.java.constants.Constants;

/**
 * Navigation data container class.
 * Aligned with RTKLIB nav_t structure.
 */
public class Nav {
    /** Number of broadcast ephemeris */
    public int n, nmax;
    
    /** Number of GLONASS ephemeris */
    public int ng, ngmax;
    
    /** Number of SBAS ephemeris */
    public int ns, nsmax;
    
    /** Number of precise ephemeris */
    public int ne, nemax;
    
    /** Number of precise clock */
    public int nc, ncmax;
    
    /** Number of almanac data */
    public int na, namax;
    
    /** Number of TEC grid data */
    public int nt, ntmax;
    
    /** GPS/QZS/GAL/BDS/IRN ephemeris */
    public Eph[] eph;
    
    /** GLONASS ephemeris */
    public Geph[] geph;
    
    /** SBAS ephemeris */
    public Seph[] seph;
    
    /** Almanac data */
    public Alm[] alm;
    
    /** GPS delta-UTC parameters {A0,A1,Tot,WNt,dt_LS,WN_LSF,DN,dt_LSF} */
    public double[] utc_gps;
    
    /** GLONASS UTC time parameters {tau_C,tau_GPS} */
    public double[] utc_glo;
    
    /** Galileo UTC parameters */
    public double[] utc_gal;
    
    /** QZS UTC parameters */
    public double[] utc_qzs;
    
    /** BeiDou UTC parameters */
    public double[] utc_cmp;
    
    /** IRNSS UTC parameters {A0,A1,Tot,...,dt_LSF,A2} */
    public double[] utc_irn;
    
    /** SBAS UTC parameters */
    public double[] utc_sbs;
    
    /** GPS iono model parameters {a0,a1,a2,a3,b0,b1,b2,b3} */
    public double[] ion_gps;
    
    /** Galileo iono model parameters {ai0,ai1,ai2,0} */
    public double[] ion_gal;
    
    /** QZSS iono model parameters {a0,a1,a2,a3,b0,b1,b2,b3} */
    public double[] ion_qzs;
    
    /** BeiDou iono model parameters {a0,a1,a2,a3,b0,b1,b2,b3} */
    public double[] ion_cmp;
    
    /** IRNSS iono model parameters {a0,a1,a2,a3,b0,b1,b2,b3} */
    public double[] ion_irn;
    
    /** GLONASS FCN + 8 */
    public int[] glo_fcn;
    
    /** SBAS satellite corrections */
    public SbsSat sbssat;
    
    /** SBAS ionosphere corrections */
    public SbsIon[] sbsion;
    
    /** DGPS corrections */
    public Dgps[] dgps;
    
    /** SSR corrections */
    public Ssr[] ssr;

    public Erp erp;

    public double[][][] cbias;
    public double[][][] rbias;

    public PepH[] peph;
    public Pclk[] pclk;
    public Pcv[] pcvs;

    /**
     * Default constructor.
     */
    public Nav() {
        this.n = 0;
        this.nmax = Constants.MAXSAT * 2;
        this.ng = 0;
        this.ngmax = Constants.MAXSAT;
        this.ns = 0;
        this.nsmax = Constants.MAXSAT;
        this.ne = 0;
        this.nemax = 0;
        this.nc = 0;
        this.ncmax = 0;
        this.na = 0;
        this.namax = 0;
        this.nt = 0;
        this.ntmax = 0;
        
        this.eph = new Eph[this.nmax];
        for (int i = 0; i < this.nmax; i++) {
            this.eph[i] = new Eph();
        }
        
        this.geph = new Geph[this.ngmax];
        for (int i = 0; i < this.ngmax; i++) {
            this.geph[i] = new Geph();
        }
        
        this.seph = new Seph[this.nsmax];
        for (int i = 0; i < this.nsmax; i++) {
            this.seph[i] = new Seph();
        }
        
        this.alm = new Alm[this.namax];
        for (int i = 0; i < this.namax; i++) {
            this.alm[i] = new Alm();
        }
        
        this.utc_gps = new double[8];
        this.utc_glo = new double[8];
        this.utc_gal = new double[8];
        this.utc_qzs = new double[8];
        this.utc_cmp = new double[8];
        this.utc_irn = new double[9];
        this.utc_sbs = new double[4];
        
        this.ion_gps = new double[8];
        this.ion_gal = new double[4];
        this.ion_qzs = new double[8];
        this.ion_cmp = new double[8];
        this.ion_irn = new double[8];
        
        this.glo_fcn = new int[32];
        
        this.sbssat = new SbsSat();
        this.sbsion = new SbsIon[Constants.MAXBAND + 1];
        for (int i = 0; i <= Constants.MAXBAND; i++) {
            this.sbsion[i] = new SbsIon();
        }
        
        this.dgps = new Dgps[Constants.MAXSAT];
        for (int i = 0; i < Constants.MAXSAT; i++) {
            this.dgps[i] = new Dgps();
        }
        
        this.ssr = new Ssr[Constants.MAXSAT];
        for (int i = 0; i < Constants.MAXSAT; i++) {
            this.ssr[i] = new Ssr();
        }

        this.erp = new Erp();

        this.peph = new PepH[0];
        this.pclk = new Pclk[0];
        this.pcvs = new Pcv[Constants.MAXSAT];
        for (int i = 0; i < Constants.MAXSAT; i++) {
            this.pcvs[i] = new Pcv();
        }
    }

    /**
     * Clear all navigation data.
     */
    public void clear() {
        this.n = 0;
        this.ng = 0;
        this.ns = 0;
        this.ne = 0;
        this.nc = 0;
        this.na = 0;
        this.nt = 0;
        
        for (int i = 0; i < this.nmax; i++) {
            this.eph[i] = new Eph();
        }
        for (int i = 0; i < this.ngmax; i++) {
            this.geph[i] = new Geph();
        }
        for (int i = 0; i < this.nsmax; i++) {
            this.seph[i] = new Seph();
        }
        
        Arrays.fill(this.utc_gps, 0.0);
        Arrays.fill(this.utc_glo, 0.0);
        Arrays.fill(this.utc_gal, 0.0);
        Arrays.fill(this.utc_qzs, 0.0);
        Arrays.fill(this.utc_cmp, 0.0);
        Arrays.fill(this.utc_irn, 0.0);
        Arrays.fill(this.utc_sbs, 0.0);
        
        Arrays.fill(this.ion_gps, 0.0);
        Arrays.fill(this.ion_gal, 0.0);
        Arrays.fill(this.ion_qzs, 0.0);
        Arrays.fill(this.ion_cmp, 0.0);
        Arrays.fill(this.ion_irn, 0.0);
        
        Arrays.fill(this.glo_fcn, 0);
        this.sbssat = new SbsSat();
        
        for (int i = 0; i <= Constants.MAXBAND; i++) {
            this.sbsion[i] = new SbsIon();
        }
        
        for (int i = 0; i < Constants.MAXSAT; i++) {
            this.dgps[i] = new Dgps();
            this.ssr[i] = new Ssr();
        }

        this.erp = new Erp();
    }
}