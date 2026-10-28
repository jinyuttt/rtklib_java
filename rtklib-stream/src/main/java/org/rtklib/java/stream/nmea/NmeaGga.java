package org.rtklib.java.stream.nmea;

/**
 * NMEA GGA 定位语句的解析结果。
 *
 * <p>字段语义遵循 NMEA 0183 GGA 定义：高度为相对平均海平面的正高，
 * 大地水准面差距为平均海平面相对 WGS84 椭球的高度。两者相加即为椭球高，
 * 可直接用于 GNSS 坐标运算。</p>
 *
 * <p>角度以度为单位，{@link #latitudeRad()} / {@link #longitudeRad()} 提供弧度形式，
 * {@link #toVrsPosition()} 输出 GNSS 计算惯用的 {@code [lat(rad), lon(rad), h(m)]}。</p>
 *
 * @see NmeaGgaParser
 */
public record NmeaGga(
        double utcSecondsOfDay,
        double latitudeDeg,
        double longitudeDeg,
        int fixQuality,
        int satellites,
        double hdop,
        double mslHeight,
        double geoidSeparation,
        double differentialAge,
        int referenceStationId) {

    /** 定位质量：无有效定位。 */
    public static final int FIX_INVALID = 0;
    /** 定位质量：单点定位。 */
    public static final int FIX_SINGLE = 1;
    /** 定位质量：RTK 固定解。 */
    public static final int FIX_RTK_FIXED = 4;
    /** 定位质量：RTK 浮动解。 */
    public static final int FIX_RTK_FLOAT = 5;

    /** @return 纬度（弧度） */
    public double latitudeRad() {
        return Math.toRadians(latitudeDeg);
    }

    /** @return 经度（弧度） */
    public double longitudeRad() {
        return Math.toRadians(longitudeDeg);
    }

    /**
     * 椭球高（米）。
     *
     * <p>GGA 播发的是正高与大地水准面差距，GNSS 坐标运算需要的是椭球高，
     * 两者关系为 {@code h = H + N}。</p>
     *
     * @return 相对 WGS84 椭球面的高度
     */
    public double ellipsoidHeight() {
        return mslHeight + geoidSeparation;
    }

    /**
     * 是否具备可用定位。定位质量为 0 时坐标无意义，不应据此生成虚拟站。
     */
    public boolean hasFix() {
        return fixQuality != FIX_INVALID;
    }

    /**
     * 转换为 GNSS 计算惯用的大地坐标。
     *
     * @return {@code [lat(rad), lon(rad), 椭球高(m)]}
     */
    public double[] toVrsPosition() {
        return new double[]{latitudeRad(), longitudeRad(), ellipsoidHeight()};
    }
}