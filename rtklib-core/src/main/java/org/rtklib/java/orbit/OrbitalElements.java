package org.rtklib.java.orbit;

public class OrbitalElements {

    public final double a;
    public final double e;
    public final double i;
    public final double raan;
    public final double argp;
    public final double meanAnomaly;
    public final Frame frame;
    public final double epochJd;

    public OrbitalElements(double a, double e, double i,
                           double raan, double argp, double meanAnomaly,
                           Frame frame, double epochJd) {
        this.a = a;
        this.e = e;
        this.i = i;
        this.raan = raan;
        this.argp = argp;
        this.meanAnomaly = meanAnomaly;
        this.frame = frame;
        this.epochJd = epochJd;
    }

    public double period() {
        double n = Math.sqrt(OrbitConstants.MU / (a * a * a));
        return 2.0 * Math.PI / n;
    }

    public double meanMotion() {
        return Math.sqrt(OrbitConstants.MU / (a * a * a));
    }

    public double apogee() {
        return a * (1.0 + e);
    }

    public double perigee() {
        return a * (1.0 - e);
    }

    @Override
    public String toString() {
        return String.format(
                "OrbitalElements{a=%.6f km, e=%.8f, i=%.6f°, Ω=%.6f°, ω=%.6f°, M=%.6f°, frame=%s, jd=%.6f}",
                a, e, Math.toDegrees(i), Math.toDegrees(raan),
                Math.toDegrees(argp), Math.toDegrees(meanAnomaly), frame, epochJd);
    }
}