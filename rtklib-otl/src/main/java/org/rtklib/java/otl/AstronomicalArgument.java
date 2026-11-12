package org.rtklib.java.otl;

/**
 * [JAVA-ONLY 原创] 一组历元对应的基本天文幅角，供 Doodson 展开与交点潮调制使用。
 *
 * <p><b>来源说明</b>：平均天文幅角的多项式级数取自公开文献
 * IERS Conventions (2010) 第 5 章表 5.3（与 Simon et al., 1994 一致），
 * 月球升交点黄经取自 R. D. Ray 公开发表的潮汐分析算例（NASA/GSFC, 2003）。
 * 上述内容均为公开发表的标准与公式，本类为按其物理定义独立编写的实现。</p>
 *
 * <p>本类是不可变值对象：一次构造即固定某一历元的全部幅角，
 * 后续计算只读取、不修改。</p>
 */
public final class AstronomicalArgument {

    /** 六个 Doodson 基本幅角，顺序与 Doodson 编码的六位数字一一对应。 */
    public enum Slot {
        /** 平太阴时 τ，由平地方时与月球、太阳平黄经组合得到。 */
        LUNAR_TIME,
        /** 月球平黄经 s。 */
        MOON_LONGITUDE,
        /** 太阳平黄经 h。 */
        SUN_LONGITUDE,
        /** 月球近地点平黄经 p。 */
        LUNAR_PERIGEE,
        /** 月球升交点平黄经 N。 */
        LUNAR_NODE,
        /** 太阳近地点平黄经 p'。 */
        SOLAR_PERIGEE;

        private static final Slot[] ALL = values();

        public static Slot at(int position) {
            return ALL[position];
        }
    }

    private static final int SLOT_COUNT = 6;
    private static final double RAD = Math.PI / 180.0;
    private static final double FULL_CIRCLE = 2.0 * Math.PI;

    /** 儒略世纪起点 2000-01-01T12:00 UT 的简化儒略日。 */
    private static final double CENTURY_EPOCH_MJD = 51544.5;

    /** 同一历元的当日零点简化儒略日。 */
    private static final double DAY_START_MJD = 51544.0;

    /** J2000.0 历元的儒略日。 */
    private static final double J2000_JULIAN_DAY = 2451545.0;

    /** 简化儒略日与儒略日的固定偏移。 */
    private static final double MJD_TO_JD = 2400000.5;

    private final double[] anglesRad;
    private final double ascendingNodeDeg;

    private AstronomicalArgument(double[] anglesRad, double ascendingNodeDeg) {
        this.anglesRad = anglesRad;
        this.ascendingNodeDeg = ascendingNodeDeg;
    }

    /**
     * 计算给定历元的全部幅角。
     *
     * @param mjd 简化儒略日（UTC）
     */
    public static AstronomicalArgument at(double mjd) {
        double centuries = (mjd - CENTURY_EPOCH_MJD) / 36525.0;
        double dayFraction = (mjd - DAY_START_MJD) - Math.floor(mjd - DAY_START_MJD);

        double moonLongitude = 218.31664563 + 481267.88119575 * centuries
                - 0.001466388889 * centuries * centuries
                - 0.000000074112 * centuries * centuries * centuries
                - 0.000000153389 * centuries * centuries * centuries * centuries;

        double sunLongitude = 280.4664501606 + 36000.769748805556 * centuries
                + 0.000303222222 * centuries * centuries
                - 0.000001905501 * centuries * centuries * centuries
                - 0.000000065361 * centuries * centuries * centuries * centuries;

        double lunarPerigee = 83.35324312 + 4069.01363525 * centuries
                - 0.010321722222 * centuries * centuries
                - 0.000014417168 * centuries * centuries * centuries
                + 0.0000000526333 * centuries * centuries * centuries * centuries;

        double lunarNode = 234.95544499 + 1934.136261972222 * centuries
                - 0.002075611111 * centuries * centuries
                - 0.000000213944 * centuries * centuries * centuries
                + 0.000000164972 * centuries * centuries * centuries * centuries;

        double solarPerigee = 282.9373409806 + 1.719457666668 * centuries * centuries
                + 0.000456888889 * centuries * centuries
                - 0.000001943279 * centuries * centuries * centuries
                - 0.0000000033444 * centuries * centuries * centuries * centuries;

        double lunarTime = 360.0 * dayFraction - moonLongitude + sunLongitude;

        double[] degrees = {lunarTime, moonLongitude, sunLongitude,
                lunarPerigee, lunarNode, solarPerigee};
        double[] radians = new double[SLOT_COUNT];
        for (int i = 0; i < SLOT_COUNT; i++) {
            radians[i] = (degrees[i] * RAD) % FULL_CIRCLE;
        }

        return new AstronomicalArgument(radians, eclipticAscendingNode(mjd));
    }

    /**
     * 月球升交点黄经，用于交点潮的 f、u 调制。
     * 采用公开发表的低阶多项式（Ray, 2003）。
     */
    private static double eclipticAscendingNode(double mjd) {
        double julianDay = mjd + MJD_TO_JD;
        double centuries = (julianDay - J2000_JULIAN_DAY) / 36525.0;
        double nodeDeg = ((2.22222e-6 * centuries + 2.0708e-3) * centuries
                - 1934.136261) * centuries + 125.04452;
        nodeDeg = nodeDeg % 360.0;
        if (nodeDeg < 0.0) nodeDeg += 360.0;
        return nodeDeg;
    }

    /** 指定幅角，单位弧度。 */
    public double angleRad(Slot slot) {
        return anglesRad[slot.ordinal()];
    }

    /** 月球升交点黄经，单位度。 */
    public double ascendingNodeDegrees() {
        return ascendingNodeDeg;
    }
}