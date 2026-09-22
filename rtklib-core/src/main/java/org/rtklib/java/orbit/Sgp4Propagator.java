package org.rtklib.java.orbit;


/**
 * SGP4/SDP4 传播器 API 封装。
 *
 * <p>本类是对 {@link Sgp4Propagation#sgp4} 的轻量封装，提供测试代码
 * {@code Sgp4PropagatorTest} 期望的 API：</p>
 * <pre>
 *   Sgp4Propagator prop = new Sgp4Propagator(tleData);
 *   double[] rs = new double[6];
 *   prop.propagate(tsince, rs);   // rs[0..2]=x,y,z (km)；rs[3..5]=vx,vy,vz (km/s)
 * </pre>
 *
 * <p>坐标系 WGS72 TEME，与 python-sgp4 默认配置一致。</p>
 */
public class Sgp4Propagator {

    /** 已初始化的 SGP4 卫星记录。 */
    private final Satellite satrec;

    /** 重力常数集合，默认 WGS72（与 python-sgp4 默认一致）。 */
    private final Sgp4Constants.Gravity whichconst;

    /**
     * 使用 WGS72 常数构造传播器。
     *
     * @param data 已由 {@link TleParser#tleRead} 解析并完成 sgp4init 的 TleData
     */
    public Sgp4Propagator(TleData data) {
        this(data, Sgp4Constants.WGS72);
    }

    /**
     * 指定重力常数构造传播器。
     *
     * @param data 已初始化的 TleData
     * @param whichconst 重力常数集合（如 {@link Sgp4Constants#WGS72}）
     */
    public Sgp4Propagator(TleData data, Sgp4Constants.Gravity whichconst) {
        if (data == null || data.satrec == null) {
            throw new IllegalArgumentException("TleData.satrec 未初始化，请先调用 TleParser.tleRead");
        }
        this.satrec = data.satrec;
        this.whichconst = whichconst;
    }

    /**
     * 传播至指定时刻。
     *
     * @param tsince 自历元起的分钟数
     * @param rs 长度 6 的输出数组：[x, y, z, vx, vy, vz]（单位 km / km/s，WGS72 TEME）
     */
    public void propagate(double tsince, double[] rs) {
        double[] rv = Sgp4Propagation.sgp4(satrec, tsince, whichconst);
        if (rs == null || rs.length < 6) {
            throw new IllegalArgumentException("rs 数组长度必须 >= 6");
        }
        System.arraycopy(rv, 0, rs, 0, 6);
    }

    /**
     * 返回底层 Satellite 记录（用于调试或访问 sgp4 内部字段）。
     */
    public Satellite getSatrec() {
        return satrec;
    }
}
