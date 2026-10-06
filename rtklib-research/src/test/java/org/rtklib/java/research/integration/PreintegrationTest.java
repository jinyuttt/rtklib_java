package org.rtklib.java.research.integration;

import java.util.ArrayList;
import java.util.List;
import org.rtklib.java.research.data.ImuBias;
import org.rtklib.java.research.data.ImuData;
import org.rtklib.java.research.common.GTime;
import org.rtklib.java.research.common.RotationUtils;
import org.ejml.simple.SimpleMatrix;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * IMU 预积分单元测试。
 *
 * <p>验证：SO(3) exp/log 一致性、静态IMU零位移、常值旋转积分。</p>
 */
class PreintegrationTest {

    @Test
    void testExpLogConsistency() {
        double[][] testCases = {
            {0.0, 0.0, 0.0},
            {0.1, 0.0, 0.0},
            {0.0, 0.1, 0.0},
            {0.0, 0.0, 0.1},
            {0.1, 0.2, 0.3},
            {-0.1, 0.05, -0.03},
            {1.0, -0.5, 0.3},
            {3.1, 0.0, 0.0}
        };

        for (double[] omega : testCases) {
            SimpleMatrix R = RotationUtils.exp(omega);
            double[] omegaRecovered = RotationUtils.log(R);

            for (int i = 0; i < 3; i++) {
                assertEquals(omega[i], omegaRecovered[i], 1e-10,
                    "exp/log mismatch at idx=" + i + " omega=" + java.util.Arrays.toString(omega));
            }
        }
    }

    @Test
    void testExpToRotationMatrix() {
        double[] omega = {0.0, 0.0, 1.57079632679};
        SimpleMatrix R = RotationUtils.exp(omega);

        assertEquals(0.0, R.get(0, 0), 1e-6);
        assertEquals(-1.0, R.get(0, 1), 1e-6);
        assertTrue(R.get(1, 0) > 0);
        assertEquals(1.0, R.get(2, 2), 1e-10);
        assertEquals(0.0, R.get(2, 0), 1e-10);
        assertEquals(0.0, R.get(2, 1), 1e-10);
        assertEquals(0.0, R.get(0, 2), 1e-10);
        assertEquals(0.0, R.get(1, 2), 1e-10);
    }

    @Test
    void testStaticImuNoDisplacement() {
        List<ImuData> buffer = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            ImuData imu = new ImuData();
            imu.time = new GTime(0, i * 0.01);
            imu.accel = new double[]{0, 0, 9.81};
            imu.gyro = new double[]{0, 0, 0};
            imu.dt = 0.01;
            buffer.add(imu);
        }

        ImuBias bias = new ImuBias();

        PreintegrationResult result = PreintegrationResult.integrate(
            buffer, bias, 0.01, 0.001, 0.0001, 0.00001,
            new double[]{0, 0, 0}
        );

        assertEquals(0.99, result.totalDt, 0.01);

        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                assertEquals(i == j ? 1.0 : 0.0, result.deltaR.get(i, j), 1e-8,
                    "Static IMU: deltaR should be identity");
            }
        }

        assertEquals(0.0, result.deltaV.get(0, 0), 0.02);
        assertEquals(0.0, result.deltaV.get(1, 0), 0.02);
        double expectedDvZ = 9.81 * 0.99;
        assertEquals(expectedDvZ, result.deltaV.get(2, 0), 0.2,
            "Static IMU: deltaV_z should equal g*dt");

        assertEquals(0.0, result.deltaP.get(0, 0), 0.02);
        assertEquals(0.0, result.deltaP.get(1, 0), 0.02);
        double expectedDpZ = 0.5 * 9.81 * 0.99 * 0.99;
        assertEquals(expectedDpZ, result.deltaP.get(2, 0), 0.1);

        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                assertFalse(Double.isNaN(result.jacobianDRbw.get(i, j)));
                assertFalse(Double.isNaN(result.jacobianDVba.get(i, j)));
            }
        }
    }

    @Test
    void testConstantVelocityDisplacement() {
        List<ImuData> buffer = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            ImuData imu = new ImuData();
            imu.time = new GTime(0, i * 0.01);
            imu.accel = new double[]{1.0, 0, 9.81};
            imu.gyro = new double[]{0, 0, 0};
            imu.dt = 0.01;
            buffer.add(imu);
        }

        ImuBias bias = new ImuBias();

        PreintegrationResult result = PreintegrationResult.integrate(
            buffer, bias, 0.01, 0.001, 0.0001, 0.00001,
            new double[]{0, 0, 0}
        );

        assertEquals(0.99, result.totalDt, 0.01);

        double expectedDvX = 1.0 * 0.99;
        assertEquals(expectedDvX, result.deltaV.get(0, 0), 0.1);
        assertEquals(0.0, result.deltaV.get(1, 0), 0.02);

        double expectedDpX = 0.5 * 1.0 * 0.99 * 0.99;
        assertEquals(expectedDpX, result.deltaP.get(0, 0), 0.05);
        assertEquals(0.0, result.deltaP.get(1, 0), 0.02);
    }

    @Test
    void testRotationOnly() {
        List<ImuData> buffer = new ArrayList<>();
        int N = 157;
        double dt = 0.01;
        for (int i = 0; i < N; i++) {
            ImuData imu = new ImuData();
            imu.time = new GTime(0, i * dt);
            imu.accel = new double[]{0, 0, 0};
            imu.gyro = new double[]{1.0, 0, 0};
            imu.dt = dt;
            buffer.add(imu);
        }

        ImuBias bias = new ImuBias();

        PreintegrationResult result = PreintegrationResult.integrate(
            buffer, bias,
            0.0, 0.0, 0.0, 0.0,
            new double[]{0, 0, 0}
        );

        double totalAngle = 1.0 * (N - 1) * dt;
        SimpleMatrix RExpected = RotationUtils.exp(new double[]{totalAngle, 0, 0});

        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                assertEquals(RExpected.get(i, j), result.deltaR.get(i, j), 1e-4,
                    "Rotation mismatch at (" + i + "," + j + ")");
            }
        }
    }
}