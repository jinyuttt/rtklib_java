package org.rtklib.java.orbit;

/**
 * SGP4 重力常数与基本数学常数。
 *
 * <p>逐行移植自 python-sgp4 的 propagation.py 中的 getgravconst() 函数。
 * 注意：项目中 org.rtklib.java.orbit.OrbitConstants.J4 取值 (-0.00000162097)
 * 与 python-sgp4 WGS72 的 J4 (-0.00000165597) 不一致，因此本类独立定义
 * WGS72/WGS72OLD/WGS84 三套常数，不复用 OrbitConstants，以避免移植误差。</p>
 */
public final class Sgp4Constants {

    private Sgp4Constants() {}

    /** 圆周率。 */
    public static final double PI = Math.PI;
    /** 2*PI。 */
    public static final double TWOPI = 2.0 * Math.PI;
    /** 度转弧度。 */
    public static final double DEG2RAD = Math.PI / 180.0;
    /** 每天分钟数 / 2PI：rad/min 与 rev/day 之间的转换系数。 */
    public static final double XPDOTP = 1440.0 / (2.0 * Math.PI);

    /** WGS72OLD 重力常数集合（与 python-sgp4 'wgs72old' 一致）。 */
    public static final Gravity WGS72OLD = new Gravity(
            398600.79964,   // mu (km^3/s^2)
            6378.135,        // radiusearthkm
            0.0743669161,    // xke
            1.0 / 0.0743669161, // tumin
            0.001082616,     // j2
            -0.00000253881,  // j3
            -0.00000165597,  // j4
            -0.00000253881 / 0.001082616 // j3oj2
    );

    /** WGS72 重力常数集合（与 python-sgp4 'wgs72' 一致，Vallado 标准使用）。 */
    public static final Gravity WGS72 = new Gravity(
            398600.8,                            // mu
            6378.135,                            // radiusearthkm
            60.0 / Math.sqrt(6378.135 * 6378.135 * 6378.135 / 398600.8), // xke
            1.0 / (60.0 / Math.sqrt(6378.135 * 6378.135 * 6378.135 / 398600.8)), // tumin
            0.001082616,                         // j2
            -0.00000253881,                       // j3
            -0.00000165597,                       // j4
            -0.00000253881 / 0.001082616          // j3oj2
    );

    /** WGS84 重力常数集合（与 python-sgp4 'wgs84' 一致）。 */
    public static final Gravity WGS84 = new Gravity(
            398600.5,                            // mu
            6378.137,                            // radiusearthkm
            60.0 / Math.sqrt(6378.137 * 6378.137 * 6378.137 / 398600.5), // xke
            1.0 / (60.0 / Math.sqrt(6378.137 * 6378.137 * 6378.137 / 398600.5)), // tumin
            0.00108262998905,                    // j2
            -0.00000253215306,                   // j3
            -0.00000161098761,                   // j4
            -0.00000253215306 / 0.00108262998905 // j3oj2
    );

    /**
     * 重力常数集合，对应 python-sgp4 的 EarthGravity namedtuple。
     * 字段顺序与 getgravconst() 返回值顺序一致：
     * tumin, mu, radiusearthkm, xke, j2, j3, j4, j3oj2
     */
    public static final class Gravity {
        /** 分钟/时间单位。 */
        public final double tumin;
        /** 地球引力常数 (km^3/s^2)。 */
        public final double mu;
        /** 地球赤道半径 (km)。 */
        public final double radiusearthkm;
        /** tumin 的倒数。 */
        public final double xke;
        /** J2 带谐系数。 */
        public final double j2;
        /** J3 带谐系数。 */
        public final double j3;
        /** J4 带谐系数。 */
        public final double j4;
        /** J3 / J2。 */
        public final double j3oj2;

        public Gravity(double mu, double radiusearthkm, double xke, double tumin,
                       double j2, double j3, double j4, double j3oj2) {
            this.mu = mu;
            this.radiusearthkm = radiusearthkm;
            this.xke = xke;
            this.tumin = tumin;
            this.j2 = j2;
            this.j3 = j3;
            this.j4 = j4;
            this.j3oj2 = j3oj2;
        }
    }
}
