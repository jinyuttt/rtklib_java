package org.rtklib.java;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.rtklib.java.common.RtklibCommon;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.GTime;
import org.rtklib.java.data.PrcOpt;
import org.rtklib.java.time.TimeSystem;
import org.rtklib.java.troposphere.TroposphereModel;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Core Algorithm Unit Tests")
class CoreAlgorithmTest {

    @Nested
    @DisplayName("SatUtils.satsys() - GNSS system identification")
    class SatSysTest {

        @Test
        @DisplayName("GPS PRN 1-32 maps to SYS_GPS")
        void testGps() {
            assertEquals(Constants.SYS_GPS, SatUtils.satsys(1, null));
            assertEquals(Constants.SYS_GPS, SatUtils.satsys(16, null));
            assertEquals(Constants.SYS_GPS, SatUtils.satsys(32, null));
        }

        @Test
        @DisplayName("GLONASS maps to SYS_GLO")
        void testGlonass() {
            int sat = 32 + 1;
            assertEquals(Constants.SYS_GLO, SatUtils.satsys(sat, null));
            sat = 32 + 24;
            assertEquals(Constants.SYS_GLO, SatUtils.satsys(sat, null));
        }

        @Test
        @DisplayName("Galileo maps to SYS_GAL")
        void testGalileo() {
            int sat = 32 + 27 + 1;
            assertEquals(Constants.SYS_GAL, SatUtils.satsys(sat, null));
        }

        @Test
        @DisplayName("BeiDou maps to SYS_CMP")
        void testBeiDou() {
            int sat = 32 + 27 + 36 + 10 + 1;
            assertEquals(Constants.SYS_CMP, SatUtils.satsys(sat, null));
        }

        @Test
        @DisplayName("QZSS maps to SYS_QZS")
        void testQzss() {
            int sat = 32 + 27 + 36 + 1;
            assertEquals(Constants.SYS_QZS, SatUtils.satsys(sat, null));
        }

        @Test
        @DisplayName("IRNSS maps to SYS_IRN")
        void testIrnss() {
            int sat = 32 + 27 + 36 + 10 + 46 + 1;
            assertEquals(Constants.SYS_IRN, SatUtils.satsys(sat, null));
        }

        @Test
        @DisplayName("SBAS maps to SYS_SBS")
        void testSbas() {
            int sat = 32 + 27 + 36 + 10 + 46 + 14 + 10 + 1;
            assertEquals(Constants.SYS_SBS, SatUtils.satsys(sat, null));
        }

        @Test
        @DisplayName("out-of-range returns SYS_NONE")
        void testOutOfRange() {
            assertEquals(Constants.SYS_NONE, SatUtils.satsys(0, null));
            assertEquals(Constants.SYS_NONE, SatUtils.satsys(-1, null));
            assertEquals(Constants.SYS_NONE, SatUtils.satsys(Constants.MAXSAT + 1, null));
        }

        @Test
        @DisplayName("PRN output array populated correctly")
        void testPrnOutput() {
            int[] prn = new int[1];
            int sys = SatUtils.satsys(1, prn);
            assertEquals(Constants.SYS_GPS, sys);
            assertEquals(1, prn[0]);

            sys = SatUtils.satsys(33, prn);
            assertEquals(Constants.SYS_GLO, sys);
            assertEquals(1, prn[0]);

            sys = SatUtils.satsys(60, prn);
            assertEquals(Constants.SYS_GAL, sys);
            assertEquals(1, prn[0]);
        }
    }

    @Nested
    @DisplayName("RtklibCommon.geodist() - geometric distance + Sagnac")
    class GeodistTest {

        @Test
        @DisplayName("zero-length vector gives zero distance")
        void testZeroDistance() {
            double[] rs = {6378137 + 20000000, 0, 0};
            double[] rr = {6378137 + 20000000, 0, 0};
            double[] e = new double[3];
            double dist = RtklibCommon.geodist(rs, rr, e);
            assertEquals(0.0, dist, 1e-3);
        }

        @Test
        @DisplayName("known distance: receiver at Shanghai vs GPS satellite (~20,000 km)")
        void testGpsSatDistance() {
            double[] rr = {-2831733.0, 4675703.0, 3275369.0};
            double[] rs = {-5234172.0, -19765160.0, 19551782.0};
            double[] e = new double[3];
            double dist = RtklibCommon.geodist(rs, rr, e);
            assertTrue(dist > 20000000.0, "distance should be > 20000 km, got " + dist);
            assertTrue(dist < 30000000.0, "distance should be < 30000 km, got " + dist);

            double normE = Math.sqrt(e[0] * e[0] + e[1] * e[1] + e[2] * e[2]);
            assertEquals(1.0, normE, 1e-10);
        }

        @Test
        @DisplayName("Sagnac correction is non-zero for equatorial satellite")
        void testSagnacCorrection() {
            double[] rr = {6378137.0, 0, 0};
            double[] rs = {6378137 + 20000000, 100000, 0};
            double[] e = new double[3];
            double distRaw = Math.sqrt((rs[0]-rr[0])*(rs[0]-rr[0]) + (rs[1]-rr[1])*(rs[1]-rr[1]) + (rs[2]-rr[2])*(rs[2]-rr[2]));
            double dist = RtklibCommon.geodist(rs, rr, e);
            double sagnac = dist - distRaw;
            assertTrue(Math.abs(sagnac) > 1e-6, "Sagnac correction should be non-zero");
        }

        @Test
        @DisplayName("unit vector e points from receiver to satellite")
        void testUnitVectorDirection() {
            double[] rr = {0, 0, 0};
            double[] rs = {1000, 2000, 3000};
            double[] e = new double[3];
            RtklibCommon.geodist(rs, rr, e);
            assertEquals(1000.0 / Math.sqrt(14e6), e[0], 1e-10);
            assertEquals(2000.0 / Math.sqrt(14e6), e[1], 1e-10);
            assertEquals(3000.0 / Math.sqrt(14e6), e[2], 1e-10);
        }
    }

    @Nested
    @DisplayName("RtklibCommon.satazel() - elevation/azimuth from ECEF line-of-sight")
    class SatazelTest {

        @Test
        @DisplayName("zenith direction gives elevation PI/2")
        void testZenith() {
            double[] pos = {0.0, 0.0, 0.0};
            double[] e = {1, 0, 0};
            double[] azel = new double[2];
            double el = RtklibCommon.satazel(pos, e, azel);
            assertEquals(Constants.PI / 2.0, el, 1e-10);
        }

        @Test
        @DisplayName("horizon direction gives elevation ~0")
        void testHorizon() {
            double[] pos = {0.0, 0.0, 0.0};
            double[] e = {0, 0, 1};
            double[] azel = new double[2];
            double el = RtklibCommon.satazel(pos, e, azel);
            assertTrue(el < 0.1, "horizon el should be near 0, got " + el);
        }

        @Test
        @DisplayName("known azimuth for east direction at equator")
        void testEquatorEast() {
            double[] pos = {0.0, 0.0, 0.0};
            double[] e = {0, 1, 0};
            double[] azel = new double[2];
            double el = RtklibCommon.satazel(pos, e, azel);
            assertEquals(Math.PI / 2.0, azel[0], 0.01);
        }

        @Test
        @DisplayName("known azimuth for north direction at equator")
        void testEquatorNorth() {
            double[] pos = {0.0, 0.0, 0.0};
            double[] e = {0, 0, 1};
            double[] azel = new double[2];
            double el = RtklibCommon.satazel(pos, e, azel);
            assertEquals(0.0, azel[0], 0.01);
        }

        @Test
        @DisplayName("below surface position returns PI/2")
        void testSubSurface() {
            double[] pos = {0.0, 0.0, -Constants.RE_WGS84 - 100};
            double[] e = {1, 0, 0};
            double[] azel = new double[2];
            double el = RtklibCommon.satazel(pos, e, azel);
            assertEquals(Constants.PI / 2.0, el, 1e-10);
        }
    }

    @Nested
    @DisplayName("TroposphereModel.saastamoinen() - zenith delay")
    class SaastamoinenTest {

        @Test
        @DisplayName("zenith delay at sea level is ~2.3 m")
        void testSeaLevelZenith() {
            double[] pos = {0.0, 0.0, 0.0};
            double[] azel = {0.0, Constants.PI / 2.0};
            double ztd = TroposphereModel.saastamoinen(pos, azel, 0.7, 293.15);
            assertTrue(ztd > 2.2, "ZTD should be > 2.2 m, got " + ztd);
            assertTrue(ztd < 2.5, "ZTD should be < 2.5 m, got " + ztd);
        }

        @Test
        @DisplayName("zero humidity gives hydrostatic-only delay")
        void testZeroHumidity() {
            double[] pos = {0.0, 0.0, 0.0};
            double[] azel = {0.0, Constants.PI / 2.0};
            double zhd = TroposphereModel.saastamoinen(pos, azel, 0.0, 293.15);
            assertTrue(zhd > 2.2, "ZHD should be > 2.2 m, got " + zhd);
            assertTrue(zhd < 2.35, "ZHD should be < 2.35 m, got " + zhd);
        }

        @Test
        @DisplayName("low elevation has larger delay than zenith")
        void testLowElevation() {
            double[] pos = {0.0, 0.0, 0.0};
            double[] azelZenith = {0.0, Constants.PI / 2.0};
            double[] azelLow = {0.0, 10.0 * Constants.D2R};
            double ztdZenith = TroposphereModel.saastamoinen(pos, azelZenith, 0.7, 293.15);
            double ztdLow = TroposphereModel.saastamoinen(pos, azelLow, 0.7, 293.15);
            assertTrue(ztdLow > ztdZenith, "low elevation delay should exceed zenith");
            assertTrue(ztdLow > 10.0, "low elevation delay should be > 10 m, got " + ztdLow);
        }

        @Test
        @DisplayName("high altitude has smaller delay")
        void testHighAltitude() {
            double[] posSea = {0.0, 0.0, 0.0};
            double[] posHigh = {0.0, 0.0, 5000.0};
            double[] azel = {0.0, Constants.PI / 2.0};
            double ztdSea = TroposphereModel.saastamoinen(posSea, azel, 0.7, 293.15);
            double ztdHigh = TroposphereModel.saastamoinen(posHigh, azel, 0.7, 293.15);
            assertTrue(ztdHigh < ztdSea, "high altitude delay should be smaller");
        }
    }

    @Nested
    @DisplayName("TimeSystem epoch2time/time2epoch roundtrip")
    class TimeSystemTest {

        @Test
        @DisplayName("roundtrip epoch->time->epoch preserves values")
        void testRoundtrip() {
            double[] epoch = {2026, 1, 15, 12, 30, 45.678};
            GTime time = TimeSystem.epoch2time(epoch);
            assertNotNull(time);
            double[] epochOut = new double[6];
            TimeSystem.time2epoch(time, epochOut);
            assertEquals(epoch[0], epochOut[0], 1e-9);
            assertEquals(epoch[1], epochOut[1], 1e-9);
            assertEquals(epoch[2], epochOut[2], 1e-9);
            assertEquals(epoch[3], epochOut[3], 1e-9);
            assertEquals(epoch[4], epochOut[4], 1e-9);
            assertEquals(epoch[5], epochOut[5], 1e-6);
        }

        @Test
        @DisplayName("epoch->time->epoch roundtrip for 2024-06-15")
        void testGpsEpochStart() {
            double[] epoch = {2024, 6, 15, 12, 0, 0};
            GTime time = TimeSystem.epoch2time(epoch);
            assertNotNull(time);
            double[] epochOut = new double[6];
            TimeSystem.time2epoch(time, epochOut);
            assertEquals(2024, epochOut[0], 1e-9);
            assertEquals(6, epochOut[1], 1e-9);
            assertEquals(15, epochOut[2], 1e-9);
            assertEquals(12, epochOut[3], 1e-9);
            assertEquals(0, epochOut[4], 1e-9);
            assertEquals(0, epochOut[5], 1e-6);
        }

        @Test
        @DisplayName("leap second boundary (2017-01-01)")
        void testLeapSecondBoundary() {
            double[] epoch = {2017, 1, 1, 0, 0, 0};
            GTime time = TimeSystem.epoch2time(epoch);
            assertNotNull(time);
            double[] epochOut = new double[6];
            TimeSystem.time2epoch(time, epochOut);
            assertEquals(2017, epochOut[0], 1e-9);
            assertEquals(1, epochOut[1], 1e-9);
            assertEquals(1, epochOut[2], 1e-9);
        }
    }

    @Nested
    @DisplayName("CoordTransform ecef2pos/pos2ecef roundtrip")
    class CoordTransformTest {

        @Test
        @DisplayName("roundtrip ECEF->LLH->ECEF preserves coordinates")
        void testEcefPosRoundtrip() {
            double[] ecefIn = {-2831733.0, 4675703.0, 3275369.0};
            double[] pos = new double[3];
            CoordTransform.ecef2pos(ecefIn, pos);
            double[] ecefOut = new double[3];
            CoordTransform.pos2ecef(pos, ecefOut);
            for (int i = 0; i < 3; i++) {
                assertEquals(ecefIn[i], ecefOut[i], 0.01);
            }
        }

        @Test
        @DisplayName("roundtrip LLH->ECEF->LLH preserves coordinates")
        void testPosEcefRoundtrip() {
            double[] posIn = {31.0 * Constants.D2R, 121.0 * Constants.D2R, 100.0};
            double[] ecef = new double[3];
            CoordTransform.pos2ecef(posIn, ecef);
            double[] posOut = new double[3];
            CoordTransform.ecef2pos(ecef, posOut);
            assertEquals(posIn[0], posOut[0], 1e-10);
            assertEquals(posIn[1], posOut[1], 1e-10);
            assertEquals(posIn[2], posOut[2], 0.01);
        }

        @Test
        @DisplayName("North pole conversion")
        void testNorthPole() {
            double[] pos = {Constants.PI / 2.0, 0.0, 0.0};
            double[] ecef = new double[3];
            CoordTransform.pos2ecef(pos, ecef);
            assertTrue(Math.abs(ecef[0]) < 1e-6);
            assertTrue(Math.abs(ecef[1]) < 1e-6);
            double semiMinor = 6356752.314;
            assertEquals(semiMinor, ecef[2], 0.5);
        }

        @Test
        @DisplayName("Equator at prime meridian")
        void testEquatorPrimeMeridian() {
            double[] pos = {0.0, 0.0, 0.0};
            double[] ecef = new double[3];
            CoordTransform.pos2ecef(pos, ecef);
            assertEquals(Constants.RE_WGS84, ecef[0], 1.0);
            assertTrue(Math.abs(ecef[1]) < 1e-6);
            assertTrue(Math.abs(ecef[2]) < 1e-6);
        }

        @Test
        @DisplayName("Shanghai approximate coordinates")
        void testShanghai() {
            double[] pos = {31.2304 * Constants.D2R, 121.4737 * Constants.D2R, 5.0};
            double[] ecef = new double[3];
            CoordTransform.pos2ecef(pos, ecef);
            double[] posOut = new double[3];
            CoordTransform.ecef2pos(ecef, posOut);
            assertEquals(31.2304, posOut[0] * Constants.R2D, 0.01);
            assertEquals(121.4737, posOut[1] * Constants.R2D, 0.01);
            assertEquals(5.0, posOut[2], 0.1);
        }
    }

    @Nested
    @DisplayName("RtklibCommon.satexclude() - satellite exclusion")
    class SatexcludeTest {

        @Test
        @DisplayName("healthy satellite is not excluded")
        void testHealthySat() {
            PrcOpt opt = new PrcOpt();
            opt.exsats = new int[Constants.MAXSAT];
            opt.navsys = Constants.SYS_GPS;
            assertEquals(0, RtklibCommon.satexclude(1, 0.01, 0, opt));
        }

        @Test
        @DisplayName("unhealthy satellite (svh != 0) is excluded")
        void testUnhealthySat() {
            PrcOpt opt = new PrcOpt();
            opt.exsats = new int[Constants.MAXSAT];
            opt.navsys = Constants.SYS_GPS;
            assertNotEquals(0, RtklibCommon.satexclude(1, 0.01, 1, opt));
        }

        @Test
        @DisplayName("high variance satellite is excluded via MAX_VAR_EPH")
        void testHighVariance() {
            PrcOpt opt = new PrcOpt();
            opt.exsats = new int[Constants.MAXSAT];
            opt.navsys = Constants.SYS_GPS;
            assertNotEquals(0, RtklibCommon.satexclude(1, Constants.MAX_VAR_EPH + 1, 0, opt));
        }

        @Test
        @DisplayName("normal variance satellite is not excluded")
        void testNormalVariance() {
            PrcOpt opt = new PrcOpt();
            opt.exsats = new int[Constants.MAXSAT];
            opt.navsys = Constants.SYS_GPS;
            assertEquals(0, RtklibCommon.satexclude(1, 1.0, 0, opt));
        }
    }
}