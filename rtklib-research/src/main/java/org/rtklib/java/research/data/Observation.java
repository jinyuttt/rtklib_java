package org.rtklib.java.research.data;

import java.io.Serializable;
import org.rtklib.java.research.common.GTime;

/**
 * 单颗卫星单个历元的观测数据（research模块自有）。
 *
 * <h3>观测值单位</h3>
 * <ul>
 *   <li>载波相位 carrierPhase：周（cycle），需乘波长λ转为米</li>
 *   <li>伪距 pseudorange：米</li>
 *   <li>多普勒 doppler：Hz</li>
 *   <li>SNR：dBHz</li>
 * </ul>
 */
public class Observation implements Serializable {
    private static final long serialVersionUID = 1L;

    public GTime time;
    public int sat;
    public int rcv;
    public int freqIndex;
    public int codeType;

    public double carrierPhase;
    public double pseudorange;
    public float doppler;
    public float snr;

    public float carrierPhaseStd;
    public float pseudorangeStd;
    public int lli;

    public Observation() {
        this.time = new GTime();
        this.sat = 0;
        this.rcv = 0;
        this.freqIndex = 0;
        this.codeType = 0;
        this.carrierPhase = 0.0;
        this.pseudorange = 0.0;
        this.doppler = 0.0f;
        this.snr = 0.0f;
        this.carrierPhaseStd = 0.0f;
        this.pseudorangeStd = 0.0f;
        this.lli = 0;
    }

    public Observation(Observation other) {
        this.time = new GTime(other.time);
        this.sat = other.sat;
        this.rcv = other.rcv;
        this.freqIndex = other.freqIndex;
        this.codeType = other.codeType;
        this.carrierPhase = other.carrierPhase;
        this.pseudorange = other.pseudorange;
        this.doppler = other.doppler;
        this.snr = other.snr;
        this.carrierPhaseStd = other.carrierPhaseStd;
        this.pseudorangeStd = other.pseudorangeStd;
        this.lli = other.lli;
    }

    public boolean isValid() {
        return this.pseudorange != 0.0 && this.snr > 0.0f;
    }

    public double wavelength() {
        if (freqIndex >= 0 && freqIndex < org.rtklib.java.research.common.GnssConst.WAVELENGTHS.length) {
            return org.rtklib.java.research.common.GnssConst.WAVELENGTHS[freqIndex];
        }
        return org.rtklib.java.research.common.GnssConst.CLIGHT / org.rtklib.java.research.common.GnssConst.FREQL1;
    }

    public double carrierPhaseInMeters() {
        return this.carrierPhase * wavelength();
    }

    @Override
    public String toString() {
        return String.format("Obs[sat=%d, f=%d, L=%.3f cyc, P=%.3f m, SNR=%.1f]",
                sat, freqIndex, carrierPhase, pseudorange, snr);
    }
}