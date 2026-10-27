package org.rtklib.java.adjust;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.adjust.engine.NetworkAdjustment;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 网平差测试：约束平差、自由网平差、坐标转换与七参数拟合。
 */
public class NetworkAdjustmentTest {

    private static final double[] ROVER = {-2267749.0, 5009154.0, 3221290.0};

    private static final String[] IDS = {"A", "B", "C"};

    private static final double[][] BASE = {
            {-2267759.0, 5009144.0, 3221300.0},
            {-2267739.0, 5009164.0, 3221280.0},
            {-2267769.0, 5009134.0, 3221310.0}
    };

    private static double[][] diagCov(double var) {
        double[][] cov = new double[3][3];
        for (int i = 0; i < 3; i++) cov[i][i] = var;
        return cov;
    }

    @Test
    @DisplayName("约束平差：基站已知，Rover 三维坐标可恢复")
    void testConstrained() {
        Random rnd = new Random(7);
        double[][] cov = diagCov(1e-4);
        List<NetworkAdjustment.BaselineObs> obs = new ArrayList<>();

        for (int ep = 0; ep < 20; ep++) {
            for (int i = 0; i < 3; i++) {
                double[] d = new double[3];
                for (int c = 0; c < 3; c++) {
                    d[c] = ROVER[c] - BASE[i][c] + 0.005 * rnd.nextGaussian();
                }
                obs.add(new NetworkAdjustment.BaselineObs(IDS[i], "ep" + ep, ep, BASE[i], d, cov));
            }
        }

        NetworkAdjustment.Result r = NetworkAdjustment.constrained(obs);
        assertTrue(r.success(), r.message());
        assertEquals(60, r.obsCount());
        assertEquals(3, r.unknownCount());
        assertEquals(3 * 60 - 3, r.dof());

        for (int c = 0; c < 3; c++) {
            assertEquals(ROVER[c], r.roverXyz()[c], 0.01, "Rover 分量 " + c);
        }
        assertNotNull(r.roverLlh());
        assertEquals(3, r.roverLlh().length);
        assertTrue(r.roverCov()[0][0] > 0);
    }

    @Test
    @DisplayName("自由网平差：单基站位移可由 baseDelta 反算定位")
    void testFreeNetworkDetectsBaseDisplacement() {
        Map<String, double[]> offset = new LinkedHashMap<>();
        offset.put("B", new double[]{0.03, 0.0, 0.0});

        double[][] cov = diagCov(1e-4);
        List<NetworkAdjustment.BaselineObs> obs = new ArrayList<>();

        for (int ep = 0; ep < 10; ep++) {
            for (int i = 0; i < 3; i++) {
                double[] off = offset.get(IDS[i]);
                double[] d = new double[3];
                for (int c = 0; c < 3; c++) {
                    d[c] = ROVER[c] - (BASE[i][c] + (off == null ? 0.0 : off[c]));
                }
                obs.add(new NetworkAdjustment.BaselineObs(IDS[i], "ep" + ep, ep, BASE[i], d, cov));
            }
        }

        NetworkAdjustment.Result r = NetworkAdjustment.freeNetwork(obs);
        assertTrue(r.success(), r.message());
        assertTrue(r.dof() > 0);

        // 重心基准下真位移 d 被摊成 (k−1)/k · d，k = 3 时为 2/3
        assertEquals(2.0 / 3.0 * 0.03, r.baseDelta().get("B")[0], 1e-5);
        assertEquals(-1.0 / 3.0 * 0.03, r.baseDelta().get("A")[0], 1e-5);
        assertEquals(-1.0 / 3.0 * 0.03, r.baseDelta().get("C")[0], 1e-5);
    }

    @Test
    @DisplayName("自由网平差：非对角协方差下 Rover 仍可恢复（回归：R⁻¹l 下标错位）")
    void testFreeNetworkWithCorrelatedCovariance() {
        Random rnd = new Random(11);
        // 强相关的非对角协方差（ECEF 三轴相关，类似真实协方差链路输出）
        double[][] cov = {
                {1.0e-4, 6.0e-5, -3.0e-5},
                {6.0e-5, 1.2e-4, 4.0e-5},
                {-3.0e-5, 4.0e-5, 0.9e-4}
        };
        List<NetworkAdjustment.BaselineObs> obs = new ArrayList<>();

        for (int ep = 0; ep < 20; ep++) {
            for (int i = 0; i < 3; i++) {
                double[] d = new double[3];
                for (int c = 0; c < 3; c++) {
                    d[c] = ROVER[c] - BASE[i][c] + 0.005 * rnd.nextGaussian();
                }
                obs.add(new NetworkAdjustment.BaselineObs(IDS[i], "ep" + ep, ep, BASE[i], d, cov));
            }
        }

        NetworkAdjustment.Result r = NetworkAdjustment.freeNetwork(obs);
        assertTrue(r.success(), r.message());

        // 观测自洽（无基站偏移），重心基准下全零位移即满足约束，Rover 应回到真值
        for (int c = 0; c < 3; c++) {
            assertEquals(ROVER[c], r.roverXyz()[c], 0.05, "Rover 分量 " + c);
        }
        // 观测噪声 0.005 m，协方差 σ≈0.01 m ⇒ sigma0 期望 ≈ 0.5；
        // 若 l 向量或残差用错（绝对坐标混入位移量），sigma0 会到 1e8 量级
        assertTrue(r.sigma0() < 2.0, "sigma0 应与观测噪声/协方差尺度一致，实际=" + r.sigma0());
    }

    @Test
    @DisplayName("ECEF ↔ 大地坐标 ↔ ENU 往返一致")
    void testCoordinateRoundTrips() {
        double[] llh = NetworkAdjustment.ecefToLlh(ROVER);
        double[] back = NetworkAdjustment.llhToEcef(llh);
        for (int c = 0; c < 3; c++) {
            assertEquals(ROVER[c], back[c], 1e-6, "ECEF 往返分量 " + c);
        }

        double[] xyz = {ROVER[0] + 100.0, ROVER[1] - 200.0, ROVER[2] + 50.0};
        double[] enu = NetworkAdjustment.ecefToEnu(xyz, ROVER);
        assertEquals(3, enu.length);
        double[] xyz2 = NetworkAdjustment.enuToEcef(enu, ROVER);
        for (int c = 0; c < 3; c++) {
            assertEquals(xyz[c], xyz2[c], 1e-6, "ENU 往返分量 " + c);
        }
        // ENU 三轴模长应与 ECEF 弦长一致
        double chord = Math.sqrt(Math.pow(xyz[0] - ROVER[0], 2)
                + Math.pow(xyz[1] - ROVER[1], 2)
                + Math.pow(xyz[2] - ROVER[2], 2));
        double enuNorm = Math.sqrt(enu[0] * enu[0] + enu[1] * enu[1] + enu[2] * enu[2]);
        assertEquals(chord, enuNorm, 1e-6);
    }

    @Test
    @DisplayName("七参数拟合：ECEF 量级坐标下可恢复平移/旋转/尺度")
    void testHelmert7() {
        double[] truth = {1.5, -2.0, 0.5, 1.0e-7, -2.0e-7, 1.5e-7, 2.0e-6};

        double[][] offsets = {
                {500.0, 200.0, -300.0},
                {-400.0, 600.0, 100.0},
                {250.0, -500.0, 400.0},
                {-600.0, -150.0, -350.0},
                {0.0, 0.0, 0.0}
        };

        List<double[]> src = new ArrayList<>();
        List<double[]> dst = new ArrayList<>();
        for (double[] o : offsets) {
            double[] s = {ROVER[0] + o[0], ROVER[1] + o[1], ROVER[2] + o[2]};
            src.add(s);
            dst.add(NetworkAdjustment.applyHelmert7(s, truth));
        }

        double[] fit = NetworkAdjustment.fitHelmert7(src, dst);
        assertNotNull(fit);

        for (int c = 0; c < 3; c++) {
            assertEquals(truth[c], fit[c], 1e-4, "平移分量 " + c);
        }
        assertEquals(truth[3], fit[3], 1e-12);
        assertEquals(truth[4], fit[4], 1e-12);
        assertEquals(truth[5], fit[5], 1e-12);
        assertEquals(truth[6], fit[6], 1e-12);

        // 用拟合参数回代，应还原目标坐标
        for (int i = 0; i < src.size(); i++) {
            double[] p = NetworkAdjustment.applyHelmert7(src.get(i), fit);
            double[] expect = dst.get(i);
            for (int c = 0; c < 3; c++) {
                assertEquals(expect[c], p[c], 1e-5, "点 " + i + " 分量 " + c);
            }
        }
    }

    @Test
    @DisplayName("空输入与非法输入返回失败而非抛异常")
    void testInvalidInputs() {
        assertFalse(NetworkAdjustment.constrained(null).success());
        assertFalse(NetworkAdjustment.constrained(List.of()).success());
        assertFalse(NetworkAdjustment.freeNetwork(List.of()).success());
        assertNull(NetworkAdjustment.fitHelmert7(List.of(), List.of()));
    }
}
