package org.rtklib.java.rtkpos;

import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SegmentSigmaFilter {
    private static final Logger log = LoggerFactory.getLogger(SegmentSigmaFilter.class);
    private final double threshold;
    private final int minLen;

    private int segLen = 0;
    private int segStartIdx = -1;
    private boolean segInvalidated = false;
    private double refLat = 0, refLon = 0;
    private double sumE = 0, sumN = 0;
    private double sumE2 = 0, sumN2 = 0;
    private final double[] posBuf = new double[3];

    public SegmentSigmaFilter(RtkConfig cfg) {
        this.threshold = cfg.rtk.segmentSigmaThreshold;
        this.minLen = cfg.rtk.segmentSigmaMinLen;
    }

    public void reset() {
        segLen = 0;
        segStartIdx = -1;
        segInvalidated = false;
        sumE = sumN = 0;
        sumE2 = sumN2 = 0;
    }

    public CheckResult checkAndAdd(double[] rr, int solType, int stat, int solutionIndex) {
        boolean isFix = stat == Constants.SOLQ_FIX;

        if (!isFix) {
            reset();
            return CheckResult.OK;
        }

        double e, n;
        if (solType == 1) {
            e = rr[0];
            n = rr[1];
        } else {
            CoordTransform.ecef2pos(rr, posBuf);
            double latRad = posBuf[0];
            double lonRad = posBuf[1];
            if (segLen == 0) {
                refLat = latRad;
                refLon = lonRad;
                segStartIdx = solutionIndex;
            }
            double dLat = (latRad - refLat) * Constants.RE_WGS84;
            double dLon = (lonRad - refLon) * Math.cos(refLat) * Constants.RE_WGS84;
            e = dLon;
            n = dLat;
        }

        segLen++;
        sumE += e;
        sumN += n;
        sumE2 += e * e;
        sumN2 += n * n;

        if (segLen < minLen) {
            return CheckResult.OK;
        }

        double n_ = segLen;
        double varE = sumE2 / n_ - (sumE / n_) * (sumE / n_);
        double varN = sumN2 / n_ - (sumN / n_) * (sumN / n_);
        double sigma = Math.sqrt(Math.max(0, varE) + Math.max(0, varN));

        if (sigma >= threshold && !segInvalidated) {
            segInvalidated = true;
            int invalidateTo = solutionIndex - 1;
            log.debug("SegmentSigmaFilter: sigma={}m >= threshold={}m, invalidating [{},{}], segLen={}",
                    String.format("%.3f", sigma), threshold, segStartIdx, invalidateTo, segLen);
            return new CheckResult(false, segStartIdx, invalidateTo);
        }

        if (segInvalidated) {
            return new CheckResult(false, -1, -1);
        }

        return CheckResult.OK;
    }

    public int getSegLen() {
        return segLen;
    }

    public double getCurrentSigma() {
        if (segLen < 2) return 0;
        double n_ = segLen;
        double varE = sumE2 / n_ - (sumE / n_) * (sumE / n_);
        double varN = sumN2 / n_ - (sumN / n_) * (sumN / n_);
        return Math.sqrt(Math.max(0, varE) + Math.max(0, varN));
    }

    public static class CheckResult {
        public static final CheckResult OK = new CheckResult(true, -1, -1);
        public final boolean accepted;
        public final int invalidateFrom;
        public final int invalidateTo;

        private CheckResult(boolean accepted, int invalidateFrom, int invalidateTo) {
            this.accepted = accepted;
            this.invalidateFrom = invalidateFrom;
            this.invalidateTo = invalidateTo;
        }
    }
}