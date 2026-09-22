package org.rtklib.java.orbit;

public class TleConverter {

    private TleConverter() {}

    public static OrbitalElements tleToOrbitalElements(TleData tleData) {
        if (tleData == null || tleData.satrec == null) {
            throw new IllegalArgumentException("TleData或其satrec未初始化");
        }
        Sgp4Propagator prop = new Sgp4Propagator(tleData);
        double[] rs = new double[6];
        prop.propagate(0.0, rs);

        StateVector sv = new StateVector(rs, Frame.TEME, tleEpochJd(tleData));
        return OrbitalMechanics.rv2coe(sv);
    }

    public static OrbitalElements tleToOrbitalElements(TleData tleData, double tsince) {
        if (tleData == null || tleData.satrec == null) {
            throw new IllegalArgumentException("TleData或其satrec未初始化");
        }
        Sgp4Propagator prop = new Sgp4Propagator(tleData);
        double[] rs = new double[6];
        prop.propagate(tsince, rs);

        StateVector sv = new StateVector(rs, Frame.TEME, tleEpochJd(tleData));
        return OrbitalMechanics.rv2coe(sv);
    }

    public static StateVector tleToStateVector(TleData tleData) {
        return tleToStateVector(tleData, 0.0);
    }

    public static StateVector tleToStateVector(TleData tleData, double tsince) {
        if (tleData == null || tleData.satrec == null) {
            throw new IllegalArgumentException("TleData或其satrec未初始化");
        }
        Sgp4Propagator prop = new Sgp4Propagator(tleData);
        double[] rs = new double[6];
        prop.propagate(tsince, rs);
        return new StateVector(rs, Frame.TEME, tleEpochJd(tleData));
    }

    public static StateVector orbitalElementsToStateVector(OrbitalElements oe) {
        return OrbitalMechanics.coe2rv(oe);
    }

    public static OrbitalElements stateVectorToOrbitalElements(StateVector sv) {
        return OrbitalMechanics.rv2coe(sv);
    }

    private static double tleEpochJd(TleData tleData) {
        if (tleData.satrec == null) return 0.0;
        return 0.0;
    }
}