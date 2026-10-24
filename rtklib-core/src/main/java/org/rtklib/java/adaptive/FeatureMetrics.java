package org.rtklib.java.adaptive;

public class FeatureMetrics {
    private static final double SMOOTH_ALPHA = 0.1;

    private int currentNSat;
    private double currentAvgSnr;
    private double slidingNSat;
    private double slidingSnr;
    private boolean initialized = false;

    public void update(int nSat, double avgSnr) {
        this.currentNSat = nSat;
        this.currentAvgSnr = avgSnr;
        if (!initialized) {
            this.slidingNSat = nSat;
            this.slidingSnr = avgSnr;
            this.initialized = true;
        } else {
            this.slidingNSat = (1.0 - SMOOTH_ALPHA) * slidingNSat + SMOOTH_ALPHA * nSat;
            this.slidingSnr = (1.0 - SMOOTH_ALPHA) * slidingSnr + SMOOTH_ALPHA * avgSnr;
        }
    }

    public int getCurrentNSat() { return currentNSat; }
    public double getCurrentAvgSnr() { return currentAvgSnr; }
    public double getSlidingNSat() { return slidingNSat; }
    public double getSlidingSnr() { return slidingSnr; }

    public GnssScene detect(GnssScene current, int nSatOpenToCanyon, double snrOpenToCanyon,
                            int nSatCanyonToOpen, double snrCanyonToOpen) {
        if (current == GnssScene.OPEN_SKY) {
            if (slidingNSat < nSatOpenToCanyon || slidingSnr < snrOpenToCanyon) {
                return GnssScene.URBAN_CANYON;
            }
        } else {
            if (slidingNSat > nSatCanyonToOpen && slidingSnr > snrCanyonToOpen) {
                return GnssScene.OPEN_SKY;
            }
        }
        return current;
    }

    public String triggerInfo(GnssScene from, GnssScene to,
                              int nSatOpenToCanyon, double snrOpenToCanyon,
                              int nSatCanyonToOpen, double snrCanyonToOpen) {
        if (to == GnssScene.URBAN_CANYON) {
            if (slidingNSat < nSatOpenToCanyon && slidingSnr < snrOpenToCanyon) {
                return String.format("nSat:%.0f<%d,snr:%.1f<%.0f", slidingNSat, nSatOpenToCanyon, slidingSnr, snrOpenToCanyon);
            } else if (slidingNSat < nSatOpenToCanyon) {
                return String.format("nSat:%.0f<%d", slidingNSat, nSatOpenToCanyon);
            } else {
                return String.format("snr:%.1f<%.0f", slidingSnr, snrOpenToCanyon);
            }
        } else {
            return String.format("nSat:%.0f>%d,snr:%.1f>%.0f", slidingNSat, nSatCanyonToOpen, slidingSnr, snrCanyonToOpen);
        }
    }
}