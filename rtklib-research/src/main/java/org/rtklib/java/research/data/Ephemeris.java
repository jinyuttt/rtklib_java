package org.rtklib.java.research.data;

import java.io.Serializable;
import org.rtklib.java.research.common.GTime;

/**
 * 广播星历（research模块自有）。
 *
 * <p>支持GPS/GAL/QZS/CMP的Kepler星历和GLONASS星历。
 * GLO 星历存储 PZ-90 ECEF 参考位置、速度、加速度，通过四阶 Runge-Kutta 数值积分传播。</p>
 */
public class Ephemeris implements Serializable {
    private static final long serialVersionUID = 1L;

    public int sat;
    public int sys;
    public GTime toe;
    public GTime toc;

    // Kepler 星历参数（GPS/GAL/QZS/CMP）
    public double sqrtA;
    public double e;
    public double i0;
    public double omega;
    public double OMEGA0;
    public double M0;
    public double deln;
    public double idot;
    public double OMEGAdot;
    public double crc;
    public double crs;
    public double cic;
    public double cis;
    public double cuc;
    public double cus;
    public double[] af = new double[3];
    public int week;
    public int code;
    public int flag;
    public double tgd;
    public double tgd2;
    public int iode;
    public int iodc;
    public int svh;
    public double fitInterval;

    // GLONASS 星历参数
    public int gloFreqNum;
    public double[] gloPos;    // PZ-90 ECEF 位置 (m)
    public double[] gloVel;    // PZ-90 ECEF 速度 (m/s)
    public double[] gloAcc;    // 日月摄动加速度 (m/s²)
    public double gloGamma;    // 相对频偏
    public double gloTau;      // 钟差 (s)

    public Ephemeris() {
        this.toe = new GTime();
        this.toc = new GTime();
    }

    public double semiMajorAxis() {
        return sqrtA * sqrtA;
    }

    @Override
    public String toString() {
        return String.format("Eph[sat=%d, sys=0x%02X, toe=%s]", sat, sys, toe);
    }
}