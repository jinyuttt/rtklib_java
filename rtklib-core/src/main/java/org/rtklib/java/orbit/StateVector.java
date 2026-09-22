package org.rtklib.java.orbit;

public class StateVector {

    public final double x, y, z;
    public final double vx, vy, vz;
    public final Frame frame;
    public final double epochJd;

    public StateVector(double x, double y, double z,
                       double vx, double vy, double vz,
                       Frame frame, double epochJd) {
        this.x = x; this.y = y; this.z = z;
        this.vx = vx; this.vy = vy; this.vz = vz;
        this.frame = frame;
        this.epochJd = epochJd;
    }

    public StateVector(double[] rv, Frame frame, double epochJd) {
        this(rv[0], rv[1], rv[2], rv[3], rv[4], rv[5], frame, epochJd);
    }

    public double[] toArray() {
        return new double[]{x, y, z, vx, vy, vz};
    }

    public double positionNorm() {
        return Math.sqrt(x * x + y * y + z * z);
    }

    public double velocityNorm() {
        return Math.sqrt(vx * vx + vy * vy + vz * vz);
    }

    @Override
    public String toString() {
        return String.format(
                "StateVector{r=(%.6f, %.6f, %.6f) km, v=(%.6f, %.6f, %.6f) km/s, frame=%s, jd=%.6f}",
                x, y, z, vx, vy, vz, frame, epochJd);
    }
}