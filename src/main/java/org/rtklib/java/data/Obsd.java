package org.rtklib.java.data;

import org.rtklib.java.constants.Constants;

/**
 * 单颗卫星单个历元的观测数据。
 *
 * <p>对应RTKLIB obsd_t。包含载波相位、伪距、多普勒、SNR等观测值，
 * 以及卫星号、接收机ID、码类型等标识信息。</p>
 *
 * <h3>观测值单位</h3>
 * <ul>
 *   <li>载波相位 L[]：单位为<b>周（cycle）</b>，需乘波长λ转为米：L * λ</li>
 *   <li>伪距 P[]：单位为<b>米</b></li>
 *   <li>多普勒 D[]：单位为<b>Hz</b></li>
 *   <li>SNR[]：单位为<b>dBHz</b></li>
 * </ul>
 *
 * <h3>致命陷阱</h3>
 * <p>载波相位 L 的单位是<b>周</b>，不是米！计算残差时必须乘波长：
 * y = L * λ - r，不可省略波长转换。</p>
 *
 * <h3>接收机ID (rcv)</h3>
 * <ul>
 *   <li>rcv=1：流动站观测</li>
 *   <li>rcv=2：基准站观测</li>
 * </ul>
 */
public class Obsd {
    /** 观测时间（GPST） */
    public GTime time;

    /** 卫星号 (1..MAXSAT) */
    public int sat;

    /** 接收机ID (1=流动站, 2=基准站) */
    public int rcv;

    /** 频率索引 */
    public int freq;

    /** 失锁指示器 [NFREQ+NEXOBS] */
    public int[] LLI;

    /** 码类型 [NFREQ+NEXOBS]，如 CODE_L1C, CODE_L2P 等 */
    public int[] code;

    /** 载波相位观测值 [NFREQ+NEXOBS]，单位：周（cycle） */
    public double[] L;

    /** 伪距观测值 [NFREQ+NEXOBS]，单位：米 */
    public double[] P;

    /** 多普勒观测值 [NFREQ+NEXOBS]，单位：Hz */
    public float[] D;

    /** 信号载噪比 [NFREQ+NEXOBS]，单位：dBHz */
    public float[] SNR;

    /** 载波相位标准差 [NFREQ+NEXOBS] */
    public float[] Lstd;

    /** 伪距标准差 [NFREQ+NEXOBS] */
    public float[] Pstd;

    /** 时间有效性标志 */
    public int timevalid;

    /** 事件时间 */
    public GTime eventime;

    /**
     * 默认构造函数，初始化所有数组。
     */
    public Obsd() {
        this.time = new GTime();
        this.sat = 0;
        this.rcv = 0;
        this.freq = 0;
        this.LLI = new int[Constants.NFREQ + Constants.NEXOBS];
        this.code = new int[Constants.NFREQ + Constants.NEXOBS];
        this.L = new double[Constants.NFREQ + Constants.NEXOBS];
        this.P = new double[Constants.NFREQ + Constants.NEXOBS];
        this.D = new float[Constants.NFREQ + Constants.NEXOBS];
        this.SNR = new float[Constants.NFREQ + Constants.NEXOBS];
        this.Lstd = new float[Constants.NFREQ + Constants.NEXOBS];
        this.Pstd = new float[Constants.NFREQ + Constants.NEXOBS];
        this.timevalid = 0;
        this.eventime = new GTime();
    }

    /**
     * 拷贝构造函数。
     *
     * @param other 源Obsd对象
     */
    public Obsd(Obsd other) {
        this.time = new GTime(other.time);
        this.sat = other.sat;
        this.rcv = other.rcv;
        this.freq = other.freq;
        this.LLI = other.LLI.clone();
        this.code = other.code.clone();
        this.L = other.L.clone();
        this.P = other.P.clone();
        this.D = other.D.clone();
        this.SNR = other.SNR.clone();
        this.Lstd = other.Lstd.clone();
        this.Pstd = other.Pstd.clone();
        this.timevalid = other.timevalid;
        this.eventime = new GTime(other.eventime);
    }
}