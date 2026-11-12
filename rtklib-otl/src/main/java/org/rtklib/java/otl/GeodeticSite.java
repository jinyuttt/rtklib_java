package org.rtklib.java.otl;

/**
 * [JAVA-ONLY 原创] 测站位置及其派生地球物理量。
 *
 * <p><b>来源说明</b>：大地坐标到地心坐标的转换采用公开的 WGS84 椭球定义
 * （a = 6378137 m，1/f = 298.25641153）；法向重力采用公开的 Somigliana 公式
 * 形式，赤道重力 9.7803278 m/s²。两者均为公开发表的定义，本类按定义独立实现。</p>
 *
 * <p>本类是不可变值对象，构造一次即固化测站的全部几何与重力参数。</p>
 */
public final class GeodeticSite {

    /** WGS84 长半轴，单位米。 */
    private static final double SEMI_MAJOR_AXIS = 6378137.0;

    /** WGS84 扁率。 */
    private static final double FLATTENING = 1.0 / 298.25641153;

    /** 赤道重力加速度，单位 m/s²。 */
    private static final double EQUATORIAL_GRAVITY = 9.7803278;

    /** Somigliana 公式中的重力扁率相关常数。 */
    private static final double GRAVITY_FLATTENING = 1.931851353e-3;

    /** 椭球第一偏心率平方（重力公式用）。 */
    private static final double GRAVITY_ECCENTRICITY_SQUARED = 6.69437999014e-3;

    private final double latitudeDegrees;
    private final double longitudeDegrees;
    private final double geocentricRadius;
    private final double geocentricLatitudeDegrees;
    private final double sinGeocentricLatitude;
    private final double cosGeocentricLatitude;
    private final double surfaceGravity;
    private final double radiusRatio;

    private GeodeticSite(double latitudeDegrees, double longitudeDegrees,
                         double geocentricRadius, double geocentricLatitudeDegrees,
                         double sinGeocentricLatitude, double cosGeocentricLatitude,
                         double surfaceGravity) {
        this.latitudeDegrees = latitudeDegrees;
        this.longitudeDegrees = longitudeDegrees;
        this.geocentricRadius = geocentricRadius;
        this.geocentricLatitudeDegrees = geocentricLatitudeDegrees;
        this.sinGeocentricLatitude = sinGeocentricLatitude;
        this.cosGeocentricLatitude = cosGeocentricLatitude;
        this.surfaceGravity = surfaceGravity;
        this.radiusRatio = BlqConsts.AE / geocentricRadius;
    }

    /**
     * 由大地纬度、经度与椭球高构造测站。
     *
     * @param latitudeDegrees  大地纬度，单位度
     * @param longitudeDegrees 经度，单位度
     * @param ellipsoidalHeight 椭球高，单位米
     */
    public static GeodeticSite of(double latitudeDegrees, double longitudeDegrees,
                                  double ellipsoidalHeight) {
        double eccentricitySquared = (2.0 - FLATTENING) * FLATTENING;
        double latitudeRad = Math.toRadians(latitudeDegrees);
        double longitudeRad = Math.toRadians(longitudeDegrees);
        double sinLatitude = Math.sin(latitudeRad);
        double cosLatitude = Math.cos(latitudeRad);

        double primeVerticalRadius = SEMI_MAJOR_AXIS
                / Math.sqrt(1.0 - eccentricitySquared * sinLatitude * sinLatitude);
        double x = (primeVerticalRadius + ellipsoidalHeight) * cosLatitude * Math.cos(longitudeRad);
        double y = (primeVerticalRadius + ellipsoidalHeight) * cosLatitude * Math.sin(longitudeRad);
        double z = (primeVerticalRadius * (1.0 - eccentricitySquared) + ellipsoidalHeight) * sinLatitude;

        double radius = Math.sqrt(x * x + y * y + z * z);
        double geocentricLatitude = Math.toDegrees(Math.atan2(z, Math.sqrt(x * x + y * y)));

        double sinGeocentric = Math.sin(Math.toRadians(geocentricLatitude));
        double cosGeocentric = Math.cos(Math.toRadians(geocentricLatitude));

        return new GeodeticSite(latitudeDegrees, longitudeDegrees, radius, geocentricLatitude,
                sinGeocentric, cosGeocentric, somiglianaGravity(latitudeDegrees));
    }

    /**
     * Somigliana 公式给出的椭球面法向重力。
     */
    private static double somiglianaGravity(double latitudeDegrees) {
        double sinLatitude = Math.sin(Math.toRadians(latitudeDegrees));
        double sinSquared = sinLatitude * sinLatitude;
        return EQUATORIAL_GRAVITY * (1.0 + GRAVITY_FLATTENING * sinSquared)
                / Math.sqrt(1.0 - GRAVITY_ECCENTRICITY_SQUARED * sinSquared);
    }

    public double latitudeDegrees() {
        return latitudeDegrees;
    }

    public double longitudeDegrees() {
        return longitudeDegrees;
    }

    /** 地心矢径长度，单位米。 */
    public double geocentricRadius() {
        return geocentricRadius;
    }

    public double geocentricLatitudeDegrees() {
        return geocentricLatitudeDegrees;
    }

    /** sin(地心纬度)，即 cos(地心余纬)。 */
    public double sinGeocentricLatitude() {
        return sinGeocentricLatitude;
    }

    public double cosGeocentricLatitude() {
        return cosGeocentricLatitude;
    }

    /** 椭球面法向重力，单位 m/s²。 */
    public double surfaceGravity() {
        return surfaceGravity;
    }

    /** 地球长半轴与地心矢径之比 AE/r。 */
    public double radiusRatio() {
        return radiusRatio;
    }
}