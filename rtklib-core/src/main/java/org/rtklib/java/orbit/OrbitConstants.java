package org.rtklib.java.orbit;

public final class OrbitConstants {
    private OrbitConstants() {}

    public static final double PI = Math.PI;
    public static final double TWOPI = 2.0 * Math.PI;
    public static final double DEG2RAD = Math.PI / 180.0;
    public static final double XPDOTP = 1440.0 / (2.0 * Math.PI);

    public static final double EARTH_RADIUS_KM = 6378.135;
    public static final double MU = 398600.8;
    public static final double XKE = Math.sqrt(MU / (EARTH_RADIUS_KM * EARTH_RADIUS_KM * EARTH_RADIUS_KM));
    public static final double TUMIN = 1.0 / XKE;
    public static final double J2 = 0.001082616;
    public static final double J3 = -0.00000253881;
    public static final double J4 = -0.00000162097;
    public static final double J3OJ2 = J3 / J2;
}