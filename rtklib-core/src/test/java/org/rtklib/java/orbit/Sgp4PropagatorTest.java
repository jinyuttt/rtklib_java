package org.rtklib.java.orbit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * SGP4/SDP4传播器Vallado标准验证测试。
 *
 * <p>使用Vallado 2006 (AIAA 2006-6753) SGP4-VER.TLE标准测试数据，
 * 验证WGS72 TEME坐标系下的位置速度精度。
 * 参考值由python-sgp4 (WGS72) 生成。</p>
 */
class Sgp4PropagatorTest {

    private static Tle tleData;

    @BeforeAll
    static void loadTle() {
        tleData = new Tle();
        String[] paths = {
            "D:/code/rtklib_java/rtklib-core/src/test/resources/sgp4-ver.tle",
            "rtklib-core/src/test/resources/sgp4-ver.tle",
            "src/test/resources/sgp4-ver.tle"
        };
        boolean ok = false;
        for (String path : paths) {
            ok = TleParser.tleRead(path, tleData);
            if (ok && tleData.n > 0) break;
        }
        assertTrue(ok, "TLE文件读取失败");
        assertTrue(tleData.n > 0, "TLE数据为空");
    }

    @Test
    @DisplayName("Vallado验证 - 00005 近地高偏心率 (i=34.3°, e=0.186)")
    void testVallado00005() {
        double[] ref = {7022.465293, -1400.082968, 0.039952, 1.89384101, 6.40589376, 4.53480725};
        assertVallado("00005", 0, ref, 0.01, 1e-6);
    }

    @Test
    @DisplayName("Vallado验证 - 06251 近地中等阻力 (i=58.1°, e=0.003)")
    void testVallado06251() {
        double[] ref = {3988.310227, 5498.966572, 0.900559, -3.29003274, 2.35765282, 6.49662347};
        assertVallado("06251", 0, ref, 0.01, 1e-6);
    }

    @Test
    @DisplayName("Vallado验证 - 08195 深空12h共振+临界倾角 (i=64.2°, e=0.688)")
    void testVallado08195() {
        double[] ref = {2349.894834, -14785.938116, 0.021194, 2.72148810, -3.25681165, 4.49841667};
        TleData data = findTleBySatno("08195");
        assertNotNull(data);
        Sgp4Propagator prop = new Sgp4Propagator(data);
        double[] rs = new double[6];
        prop.propagate(0, rs);
        System.out.printf("08195 actual: x=%.6f y=%.6f z=%.6f vx=%.8f vy=%.8f vz=%.8f%n", rs[0], rs[1], rs[2], rs[3], rs[4], rs[5]);
        System.out.printf("08195 ref:    x=%.6f y=%.6f z=%.6f vx=%.8f vy=%.8f vz=%.8f%n", ref[0], ref[1], ref[2], ref[3], ref[4], ref[5]);
        assertVallado("08195", 0, ref, 100, 0.01);
    }

    @Test
    @DisplayName("Vallado验证 - 09880 深空12h共振+临界倾角 (i=64.6°, e=0.707)")
    void testVallado09880() {
        double[] ref = {13020.067508, -2449.071935, 1.158960, 4.24736393, 1.59717850, 4.95670861};
        assertVallado("09880", 0, ref, 0.01, 1e-6);
    }

    @Test
    @DisplayName("Vallado验证 - 11801 深空SDP4原始STR#3测试 (i=46.8°, e=0.732)")
    void testVallado11801() {
        double[] ref = {7473.371025, 428.947483, 5828.748468, 5.10715539, 6.44468030, -0.18613330};
        assertVallado("11801", 0, ref, 0.01, 1e-6);
    }

    @Test
    @DisplayName("Vallado验证 - 16925 深空临界倾角 (i=62.1°, e=0.560)")
    void testVallado16925() {
        double[] ref = {5559.116868, -11941.040908, -19.412352, 3.39211676, -1.94698512, 4.25075585};
        assertVallado("16925", 0, ref, 0.01, 1e-6);
    }

    @Test
    @DisplayName("Vallado验证 - 21897 深空12h共振+负BSTAR (i=62.2°, e=0.742)")
    void testVallado21897() {
        double[] ref = {-14464.721352, -4699.195176, 0.066817, -3.24931201, -3.28103271, 4.00704694};
        assertVallado("21897", 0, ref, 0.01, 1e-6);
    }

    @Test
    @DisplayName("Vallado验证 - 22674 深空12h共振+临界倾角 (i=63.5°, e=0.754)")
    void testVallado22674() {
        double[] ref = {14712.220233, -1443.810619, 0.834979, 4.41896547, 1.62959210, 4.11553180};
        assertVallado("22674", 0, ref, 0.01, 1e-6);
    }

    @Test
    @DisplayName("Vallado验证 - 24208 深空24h共振GEO (i=3.9°, e=0.003)")
    void testVallado24208() {
        double[] ref = {7534.109872, 41266.392668, -0.108010, -3.02716801, 0.55884900, 0.20798276};
        assertVallado("24208", 0, ref, 0.01, 1e-6);
    }

    @Test
    @DisplayName("Vallado验证 - 28350 近地临界倾角 (i=65.0°, e=0.002)")
    void testVallado28350() {
        double[] ref = {6333.081231, -1580.828523, 90.693557, 0.71463442, 3.22424655, 7.08312813};
        assertVallado("28350", 0, ref, 0.01, 1e-6);
    }

    @Test
    @DisplayName("Vallado验证 - 88888 近地SGP4原始STR#3测试 (i=72.8°, e=0.009)")
    void testVallado88888() {
        double[] ref = {2328.969753, -5995.220513, 1719.972972, 2.91207328, -0.98341796, -7.09081621};
        assertVallado("88888", 0, ref, 0.01, 1e-6);
    }

    @Test
    @DisplayName("传播连续性 - 多时间点位置应连续变化")
    void testPropagationContinuity() {
        TleData data = findTleBySatno("00005");
        assertNotNull(data);

        Sgp4Propagator prop = new Sgp4Propagator(data);
        double[] rs1 = new double[6];
        double[] rs2 = new double[6];

        prop.propagate(0.0, rs1);
        prop.propagate(1.0, rs2);

        double dx = rs2[0] - rs1[0];
        double dy = rs2[1] - rs1[1];
        double dz = rs2[2] - rs1[2];
        double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);

        assertTrue(dist > 0.01 && dist < 600,
                "1分钟内位置变化应在0.01-600km范围内，实际: " + dist);
    }

    @Test
    @DisplayName("传播器缓存 - 同一TLE多次传播应一致")
    void testPropagatorConsistency() {
        TleData data = findTleBySatno("00005");
        assertNotNull(data);

        Sgp4Propagator prop = new Sgp4Propagator(data);
        double[] rs1 = new double[6];
        double[] rs2 = new double[6];

        prop.propagate(100.0, rs1);
        prop.propagate(100.0, rs2);

        for (int i = 0; i < 6; i++) {
            assertEquals(rs1[i], rs2[i], 1e-12, "同一时间点传播结果应一致");
        }
    }

    private void assertVallado(String satno, double tsince, double[] ref, double posTolKm, double velTolKmS) {
        TleData data = findTleBySatno(satno);
        assertNotNull(data, "未找到测试卫星" + satno);

        Sgp4Propagator prop = new Sgp4Propagator(data);
        double[] rs = new double[6];
        prop.propagate(tsince, rs);

        assertFalse(Double.isNaN(rs[0]), "卫星" + satno + "轨道衰减不应发生");

        double posErr = Math.sqrt(
                Math.pow(rs[0] - ref[0], 2) +
                Math.pow(rs[1] - ref[1], 2) +
                Math.pow(rs[2] - ref[2], 2));
        double velErr = Math.sqrt(
                Math.pow(rs[3] - ref[3], 2) +
                Math.pow(rs[4] - ref[4], 2) +
                Math.pow(rs[5] - ref[5], 2));

        if (posErr > posTolKm) {
            System.out.printf("[VALLADO] %s t=%.0f: Java=(%.6f,%.6f,%.6f) Ref=(%.6f,%.6f,%.6f) posErr=%.3f km%n",
                satno, tsince, rs[0], rs[1], rs[2], ref[0], ref[1], ref[2], posErr);
        }

        assertTrue(posErr < posTolKm,
                satno + " 位置误差 " + posErr + " km 超过容差 " + posTolKm + " km");
        assertTrue(velErr < velTolKmS,
                satno + " 速度误差 " + velErr + " km/s 超过容差 " + velTolKmS + " km/s");
    }

    @Test
    @DisplayName("真实TLE交叉验证 - GPS/BDS卫星 vs python-sgp4")
    void testRealTleCrossValidation() {
        Tle gpsTle = new Tle();
        Tle bdsTle = new Tle();
        boolean gpsOk = TleParser.tleRead("D:/code/rtklib_java/data/tle.txt", gpsTle);
        boolean bdsOk = TleParser.tleRead("D:/code/rtklib_java/data/tle-bds.txt", bdsTle);
        assertTrue(gpsOk, "GPS TLE读取失败");
        assertTrue(bdsOk, "BDS TLE读取失败");

        String refContent;
        try {
            refContent = java.nio.file.Files.readString(
                    java.nio.file.Paths.get("D:/code/rtklib_java/rtklib-core/src/test/resources/real-tle-ref.csv"));
        } catch (java.io.IOException e) {
            fail("参考文件读取失败: " + e.getMessage());
            return;
        }
        String[] refLines = refContent.split("\n");

        int passCount = 0;
        int failCount = 0;
        double maxPosErr = 0;
        String maxPosErrSat = "";
        StringBuilder failures = new StringBuilder();

        for (int i = 1; i < refLines.length; i++) {
            String line = refLines[i].trim();
            if (line.isEmpty()) continue;
            String[] parts = line.split(",");
            String satno = parts[0];
            String method = parts[3];
            double refX = Double.parseDouble(parts[4]);
            double refY = Double.parseDouble(parts[5]);
            double refZ = Double.parseDouble(parts[6]);
            double refVx = Double.parseDouble(parts[7]);
            double refVy = Double.parseDouble(parts[8]);
            double refVz = Double.parseDouble(parts[9]);

            TleData data = findTleInCollection(satno, gpsTle, bdsTle);
            if (data == null) continue;

            Sgp4Propagator prop = new Sgp4Propagator(data);
            double[] rs = new double[6];
            prop.propagate(0, rs);

            if (Double.isNaN(rs[0])) {
                failCount++;
                failures.append(String.format("  %s [%s]: 轨道衰减(NaN)%n", satno, method));
                continue;
            }

            double posErr = Math.sqrt(Math.pow(rs[0] - refX, 2) + Math.pow(rs[1] - refY, 2) + Math.pow(rs[2] - refZ, 2));
            double velErr = Math.sqrt(Math.pow(rs[3] - refVx, 2) + Math.pow(rs[4] - refVy, 2) + Math.pow(rs[5] - refVz, 2));

            if (posErr > maxPosErr) {
                maxPosErr = posErr;
                maxPosErrSat = satno + " [" + method + "]";
            }

            double posTol = "d".equals(method) ? 10.0 : 0.01;
            double velTol = "d".equals(method) ? 0.01 : 1e-6;

            if (posErr < posTol && velErr < velTol) {
                passCount++;
            } else {
                failCount++;
                failures.append(String.format("  %s [%s]: posErr=%.3f km (tol=%.3f), velErr=%.6f km/s%n",
                        satno, method, posErr, posTol, velErr));
            }
        }

        System.out.printf("真实TLE交叉验证: %d 通过, %d 失败 (最大位置误差: %.3f km @ %s)%n",
                passCount, failCount, maxPosErr, maxPosErrSat);
        if (failures.length() > 0) {
            System.out.println("失败详情:\n" + failures);
        }

        assertTrue(passCount > 0, "应至少有一颗卫星通过验证");
        assertTrue(failCount < passCount, "失败数不应超过通过数，失败详情:\n" + failures);
    }

    private TleData findTleInCollection(String satno, Tle tle1, Tle tle2) {
        for (Tle t : new Tle[]{tle1, tle2}) {
            for (int i = 0; i < t.n; i++) {
                if (satno.equals(t.data[i].satno)) {
                    return t.data[i];
                }
            }
        }
        return null;
    }

    private TleData findTleBySatno(String satno) {
        if (tleData == null) return null;
        for (int i = 0; i < tleData.n; i++) {
            if (satno.equals(tleData.data[i].satno)) {
                return tleData.data[i];
            }
        }
        return null;
    }
}