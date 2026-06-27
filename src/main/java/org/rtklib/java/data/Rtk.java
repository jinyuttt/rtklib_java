package org.rtklib.java.data;

import org.rtklib.java.constants.Constants;

/**
 * RTK解算控制与状态结构体。
 *
 * <p>对应RTKLIB rtk_t。存储RTK/DGPS相对定位的解算状态，
 * 包括状态向量、协方差矩阵、基准站坐标、卫星状态等。</p>
 *
 * <h3>状态向量定义（与C版本不同）</h3>
 * <p>Java版本 rtk.x[0..2] 存储<b>基线向量</b>（流动站ECEF - 基准站ECEF），
 * 而C版本 rtk->x[0..2] 存储流动站绝对ECEF坐标。
 * 因此计算流动站绝对坐标时需要 rtk.rb + rtk.x。</p>
 *
 * <h3>矩阵存储约定（行优先）</h3>
 * <ul>
 *   <li>P[nx*nx]：协方差矩阵，行优先存储 P[i*nx+j] = 第i行第j列</li>
 *   <li>Pa[nx*nx]：固定解协方差矩阵，行优先存储</li>
 * </ul>
 *
 * <h3>状态向量布局</h3>
 * <ul>
 *   <li>x[0..2]：基线向量（米），x[0]=ΔX, x[1]=ΔY, x[2]=ΔZ</li>
 *   <li>x[3..5]：速度分量（预留，当前置零）</li>
 *   <li>x[6..6+ns*nf-1]：载波相位模糊度（周/cycle）</li>
 * </ul>
 */
public class Rtk {
    /** 定位解结果 */
    public Sol sol;

    /** 基准站ECEF坐标 [6]：{x,y,z,vx,vy,vz}，前3个为位置，后3个预留速度 */
    public double[] rb;

    /** 状态向量维数（6 + ns*nf） */
    public int nx;

    /** 模糊度参数个数（ns*nf） */
    public int na;

    /** 状态向量 [nx]，行优先存储，布局见类注释 */
    public double[] x;

    /** 协方差矩阵 [nx*nx]，行优先存储，P[i*nx+j] = 第i行第j列 */
    public double[] P;

    /** 固定解状态向量 [nx]（LAMBDA固定后的状态） */
    public double[] xa;

    /** 固定解协方差矩阵 [nx*nx]，行优先存储 */
    public double[] Pa;

    /** 连续固定解历元计数（用于Fix-and-Hold策略） */
    public int nfix;

    /** 时间间隔（当前历元与上一历元的时间差，秒） */
    public double tt;

    /** 频带数 */
    public int nband;

    /** 历元计数 [2]：{流动站, 基准站} */
    public int[] nepoch;

    /** 卫星状态数组 [MAXSAT] */
    public Ssat[] ssat;

    /** 处理选项 */
    public PrcOpt opt;

    /** 模糊度控制信息 */
    public Ambc ambc;

    /**
     * 默认构造函数，初始化所有字段。
     */
    public Rtk() {
        this.sol = new Sol();
        this.rb = new double[6];
        this.nx = 0;
        this.na = 0;
        this.x = new double[Constants.NX_RTK];
        this.P = new double[Constants.NX_RTK * Constants.NX_RTK];
        this.xa = new double[Constants.NX_RTK];
        this.Pa = new double[Constants.NX_RTK * Constants.NX_RTK];
        this.nfix = 0;
        this.nband = 0;
        this.nepoch = new int[2];
        this.ssat = new Ssat[Constants.MAXSAT];
        for (int i = 0; i < Constants.MAXSAT; i++) {
            this.ssat[i] = new Ssat();
        }
        this.opt = new PrcOpt();
        this.ambc = new Ambc();
    }
}