package org.rtklib.java.data;

import org.rtklib.java.constants.Constants;

/**
 * 卫星状态结构体。
 *
 * <p>对应RTKLIB ssat_t。跟踪每颗卫星在RTK解算过程中的状态，
 * 包括观测值有效性、残差、模糊度固定状态、周跳标志等。</p>
 *
 * <h3>关键字段说明</h3>
 * <ul>
 *   <li>vsat[f]：卫星在频率f上是否有效（0=无效, 1=有效）</li>
 *   <li>fix[f]：模糊度固定状态（0=未固定, 1=浮点, 2=固定）</li>
 *   <li>slip[f]：周跳标志（0=无周跳, 非0=有周跳）</li>
 *   <li>resp[f]：伪距残差（米）</li>
 *   <li>resc[f]：载波相位残差（米）</li>
 * </ul>
 */
public class Ssat {
    /** 卫星系统 (SYS_???) */
    public int sys;

    /** 卫星有效性 (0=无效, 1=有效) */
    public int vs;

    /** 方位角/高度角 [2] (rad) */
    public double[] azel;

    /** 伪距残差 [NFREQ]（米） */
    public double[] resp;

    /** 载波相位残差 [NFREQ]（米） */
    public double[] resc;

    /** 码间偏差 [NFREQ]（米） */
    public double[] icbias;

    /** 卫星在频率f上是否有效 [NFREQ]（0=无效, 1=有效） */
    public int[] vsat;

    /** 流动站SNR [NFREQ] (dBHz) */
    public float[] snrRover;

    /** 基准站SNR [NFREQ] (dBHz) */
    public float[] snrBase;

    /** 模糊度固定状态 [NFREQ]（0=未固定, 1=浮点, 2=固定） */
    public int[] fix;

    /** 码类型 [NFREQ][2] */
    public int[][] code;

    /** 周跳标志 [NFREQ]（0=无周跳, 非0=有周跳） */
    public int[] slip;

    /** 半周模糊度标志 [NFREQ] */
    public int[] half;

    /** 锁定计数 [NFREQ] */
    public int[] lock;

    /** 观测中断计数 [NFREQ] */
    public long[] outc;

    /** 周跳计数 [NFREQ] */
    public long[] slipc;

    /** 拒绝计数 [NFREQ] */
    public long[] rejc;

    /** Geometry-free组合值 [NFREQ-1] */
    public double[] gf;

    /** Melbourne-Wübbena组合值 [NFREQ-1] */
    public double[] mw;

    /** 相位缠绕 (rad) */
    public double phw;

    /** 前一历元时间 [2][NFREQ]：[0]=GF/MW, [1]=rate */
    public GTime[][] pt;

    /** 前一历元载波相位 [2][NFREQ] */
    public double[][] ph;

    /** 模糊度值 [NFREQ]（cycle） */
    public double[] amb;

    /** 模糊度标准差 [NFREQ] */
    public float[] stdA;

    /**
     * 默认构造函数，初始化所有数组。
     */
    public Ssat() {
        this.sys = 0;
        this.vs = 0;
        this.azel = new double[2];
        this.resp = new double[Constants.NFREQ];
        this.resc = new double[Constants.NFREQ];
        this.icbias = new double[Constants.NFREQ];
        this.vsat = new int[Constants.NFREQ];
        this.snrRover = new float[Constants.NFREQ];
        this.snrBase = new float[Constants.NFREQ];
        this.fix = new int[Constants.NFREQ];
        this.code = new int[Constants.NFREQ][2];
        this.slip = new int[Constants.NFREQ];
        this.half = new int[Constants.NFREQ];
        this.lock = new int[Constants.NFREQ];
        this.outc = new long[Constants.NFREQ];
        this.slipc = new long[Constants.NFREQ];
        this.rejc = new long[Constants.NFREQ];
        this.gf = new double[Math.max(1, Constants.NFREQ - 1)];
        this.mw = new double[Math.max(1, Constants.NFREQ - 1)];
        this.phw = 0.0;
        this.pt = new GTime[2][Constants.NFREQ];
        this.ph = new double[2][Constants.NFREQ];
        this.amb = new double[Constants.NFREQ];
        this.stdA = new float[Constants.NFREQ];
        for (int i = 0; i < 2; i++) {
            for (int j = 0; j < Constants.NFREQ; j++) {
                this.pt[i][j] = new GTime();
                this.ph[i][j] = 0.0;
            }
        }
    }
}