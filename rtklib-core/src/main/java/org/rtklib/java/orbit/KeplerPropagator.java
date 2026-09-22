package org.rtklib.java.orbit;

public class KeplerPropagator {

    private final OrbitalElements elements;
    private final double n;

    public KeplerPropagator(OrbitalElements elements) {
        this.elements = elements;
        this.n = Math.sqrt(OrbitConstants.MU / (elements.a * elements.a * elements.a));
    }

    public StateVector propagate(double dtMinutes) {
        double dtSec = dtMinutes * 60.0;
        double newM = elements.meanAnomaly + n * dtSec;
        newM = newM % OrbitConstants.TWOPI;
        if (newM < 0.0) newM += OrbitConstants.TWOPI;

        OrbitalElements newElements = new OrbitalElements(
                elements.a, elements.e, elements.i,
                elements.raan, elements.argp, newM,
                elements.frame, elements.epochJd);

        return OrbitalMechanics.coe2rv(newElements);
    }

    public OrbitalElements propagateElements(double dtMinutes) {
        double dtSec = dtMinutes * 60.0;
        double newM = elements.meanAnomaly + n * dtSec;
        newM = newM % OrbitConstants.TWOPI;
        if (newM < 0.0) newM += OrbitConstants.TWOPI;

        return new OrbitalElements(
                elements.a, elements.e, elements.i,
                elements.raan, elements.argp, newM,
                elements.frame, elements.epochJd);
    }

    public OrbitalElements getElements() {
        return elements;
    }
}