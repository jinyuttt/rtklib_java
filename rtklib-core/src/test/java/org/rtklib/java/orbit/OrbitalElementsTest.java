package org.rtklib.java.orbit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

class OrbitalElementsTest {

    private static Tle tleData;

    @BeforeAll
    static void loadTle() {
        tleData = new Tle();
        String[] paths = {
            "D:/code/rtklib_java/rtklib-core/src/test/resources/sgp4-ver.tle",
            "rtklib-core/src/test/resources/sgp4-ver.tle",
            "src/test/resources/sgp4-ver.tle"
        };
        for (String path : paths) {
            boolean ok = TleParser.tleRead(path, tleData);
            if (ok && tleData.n > 0) break;
        }
    }

    @Test
    @DisplayName("rv2coe → coe2rv 往返转换 - 近地轨道 00005")
    void testRoundTrip00005() {
        testRoundTrip("00005", 0.1, 1e-4);
    }

    @Test
    @DisplayName("rv2coe → coe2rv 往返转换 - 深空轨道 11801")
    void testRoundTrip11801() {
        testRoundTrip("11801", 0.1, 1e-4);
    }

    @Test
    @DisplayName("rv2coe → coe2rv 往返转换 - GEO 24208")
    void testRoundTrip24208() {
        testRoundTrip("24208", 0.1, 1e-4);
    }

    @Test
    @DisplayName("rv2coe → coe2rv 往返转换 - 临界倾角 08195")
    void testRoundTrip08195() {
        testRoundTrip("08195", 0.1, 1e-4);
    }

    @Test
    @DisplayName("TLE → 六根数 → 状态向量 一致性 - 00005")
    void testTleToElementsConsistency00005() {
        TleData data = findTle("00005");
        assertNotNull(data);

        Sgp4Propagator prop = new Sgp4Propagator(data);
        double[] rs = new double[6];
        prop.propagate(0, rs);

        double[] coe = OrbitalMechanics.rv2coe(rs);
        double[] rs2 = OrbitalMechanics.coe2rv(coe[0], coe[1], coe[2], coe[3], coe[4], coe[5]);

        double posErr = Math.sqrt(Math.pow(rs[0]-rs2[0],2) + Math.pow(rs[1]-rs2[1],2) + Math.pow(rs[2]-rs2[2],2));
        double velErr = Math.sqrt(Math.pow(rs[3]-rs2[3],2) + Math.pow(rs[4]-rs2[4],2) + Math.pow(rs[5]-rs2[5],2));

        System.out.printf("00005 round-trip: posErr=%.6f km, velErr=%.9f km/s%n", posErr, velErr);
        System.out.printf("  a=%.6f km, e=%.8f, i=%.6f°, Ω=%.6f°, ω=%.6f°, M=%.6f°%n",
                coe[0], coe[1], Math.toDegrees(coe[2]),
                Math.toDegrees(coe[3]), Math.toDegrees(coe[4]), Math.toDegrees(coe[5]));

        assertTrue(posErr < 0.1, "位置往返误差 " + posErr + " km");
        assertTrue(velErr < 1e-4, "速度往返误差 " + velErr + " km/s");
    }

    @Test
    @DisplayName("TLE → OrbitalElements - 所有Vallado卫星")
    void testTleToElementsAllVallado() {
        String[] satnos = {"00005", "06251", "08195", "09880", "11801", "16925", "21897", "22674", "24208", "28350", "88888"};
        int pass = 0;
        for (String satno : satnos) {
            TleData data = findTle(satno);
            if (data == null) continue;

            Sgp4Propagator prop = new Sgp4Propagator(data);
            double[] rs = new double[6];
            prop.propagate(0, rs);
            if (Double.isNaN(rs[0])) continue;

            double[] coe = OrbitalMechanics.rv2coe(rs);
            double[] rs2 = OrbitalMechanics.coe2rv(coe[0], coe[1], coe[2], coe[3], coe[4], coe[5]);

            double posErr = Math.sqrt(Math.pow(rs[0]-rs2[0],2) + Math.pow(rs[1]-rs2[1],2) + Math.pow(rs[2]-rs2[2],2));
            double velErr = Math.sqrt(Math.pow(rs[3]-rs2[3],2) + Math.pow(rs[4]-rs2[4],2) + Math.pow(rs[5]-rs2[5],2));

            System.out.printf("  %s: a=%.3f km, e=%.6f, i=%.4f°, posErr=%.6f km, velErr=%.9f km/s%n",
                    satno, coe[0], coe[1], Math.toDegrees(coe[2]), posErr, velErr);

            if (posErr < 0.1 && velErr < 1e-4) pass++;
        }
        assertTrue(pass >= satnos.length - 1, "至少" + (satnos.length-1) + "颗卫星往返转换应通过");
    }

    @Test
    @DisplayName("KeplerPropagator - 二体传播与SGP4初始位置一致")
    void testKeplerPropagatorInitial() {
        TleData data = findTle("00005");
        assertNotNull(data);

        OrbitalElements oe = TleConverter.tleToOrbitalElements(data);
        KeplerPropagator kepler = new KeplerPropagator(oe);
        StateVector sv = kepler.propagate(0.0);

        Sgp4Propagator prop = new Sgp4Propagator(data);
        double[] rs = new double[6];
        prop.propagate(0, rs);

        double posErr = Math.sqrt(Math.pow(sv.x-rs[0],2) + Math.pow(sv.y-rs[1],2) + Math.pow(sv.z-rs[2],2));
        System.out.printf("Kepler vs SGP4 at t=0: posErr=%.6f km%n", posErr);
        assertTrue(posErr < 0.1, "Kepler与SGP4初始位置误差 " + posErr + " km");
    }

    @Test
    @DisplayName("OrbitalElements 便利方法 - 周期/近地点/远地点")
    void testOrbitalElementsUtilities() {
        TleData data = findTle("24208");
        assertNotNull(data);

        OrbitalElements oe = TleConverter.tleToOrbitalElements(data);
        System.out.printf("24208 (GEO): a=%.3f km, period=%.2f min, perigee=%.3f km, apogee=%.3f km%n",
                oe.a, oe.period() / 60.0, oe.perigee(), oe.apogee());

        assertTrue(Math.abs(oe.period() / 60.0 - 1436.0) < 20.0,
                "GEO周期应约1436分钟，实际: " + oe.period() / 60.0);
        assertTrue(oe.apogee() > 41000 && oe.apogee() < 44000,
                "GEO远地点应约42164km，实际: " + oe.apogee());
    }

    private void testRoundTrip(String satno, double posTol, double velTol) {
        TleData data = findTle(satno);
        assertNotNull(data, "未找到卫星 " + satno);

        Sgp4Propagator prop = new Sgp4Propagator(data);
        double[] rs = new double[6];
        prop.propagate(0, rs);
        assertFalse(Double.isNaN(rs[0]), satno + " 传播失败");

        double[] coe = OrbitalMechanics.rv2coe(rs);
        double[] rs2 = OrbitalMechanics.coe2rv(coe[0], coe[1], coe[2], coe[3], coe[4], coe[5]);

        double posErr = Math.sqrt(Math.pow(rs[0]-rs2[0],2) + Math.pow(rs[1]-rs2[1],2) + Math.pow(rs[2]-rs2[2],2));
        double velErr = Math.sqrt(Math.pow(rs[3]-rs2[3],2) + Math.pow(rs[4]-rs2[4],2) + Math.pow(rs[5]-rs2[5],2));

        assertTrue(posErr < posTol, satno + " 位置往返误差 " + posErr + " km");
        assertTrue(velErr < velTol, satno + " 速度往返误差 " + velErr + " km/s");
    }

    private TleData findTle(String satno) {
        if (tleData == null) return null;
        for (int i = 0; i < tleData.n; i++) {
            if (satno.equals(tleData.data[i].satno)) {
                return tleData.data[i];
            }
        }
        return null;
    }
}