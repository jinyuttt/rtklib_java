package org.rtklib.java.joint;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link BaseStabilityMonitor} 的合成残差测试：Phase B（降权）与 Phase D（剔除）的
 * 「可归因性」与「存在健康参照」两条前置约束。
 *
 * <p>现场数据只有 2 台基站（k=2），按设计 Phase B/D 在该配置下不执行，故 k>=3 的
 * 归因、降权、剔除路径在真实数据上无法覆盖。这里用几何一致的合成残差
 * （{@code v_j = (e_j - e_ref)^T · (δrr - δb)}，即 DD 残差对 rover 先验位置误差与
 * 基站位移的几何系数完全相同这一事实）验证状态机与权重计算。</p>
 */
public class BaseStabilityMonitorSyntheticTest {

    /** 双差参考星号（同时是 {@code bo.sat[0]}）。 */
    private static final int REF_SAT = 1;
    private static final int NSAT = 6;
    /** 每基线双差观测数 = 非参考星数。 */
    private static final int NV = NSAT - 1;
    /** 残差方差（1 mm 级），保证形式 σ 远小于降权绝对下限 3 cm。 */
    private static final double VAR = 1e-6;

    private static final int[] AZ_DEG = {0, 90, 200, 300, 45, 160};
    private static final int[] EL_DEG = {70, 40, 20, 55, 25, 15};

    private static final double[] NO_RATE = {0.0, 0.0, 0.0};

    /**
     * 构造一条基线的观测容器，只填闸2 需要的 {@code ns/sat/iu/e}。
     * {@code e} 取单位视线向量（ECEF），方位/高度角选得使各差向量张满三维。
     */
    private static BaselineObs obs() {
        BaselineObs bo = new BaselineObs();
        bo.ns = NSAT;
        bo.nu = NSAT;
        bo.nr = NSAT;
        for (int j = 0; j < NSAT; j++) {
            bo.sat[j] = j + 1;
            bo.iu[j] = j;
            double az = Math.toRadians(AZ_DEG[j]);
            double el = Math.toRadians(EL_DEG[j]);
            bo.e[j * 3] = Math.cos(el) * Math.sin(az);
            bo.e[j * 3 + 1] = Math.cos(el) * Math.cos(az);
            bo.e[j * 3 + 2] = Math.sin(el);
        }
        return bo;
    }

    /** 合成残差：{@code v_j = (e_j - e_ref)^T · w}，{@code w = δrr - δb}。 */
    private static double[] residual(BaselineObs bo, double[] w) {
        double[] v = new double[NV];
        for (int i = 0; i < NV; i++) {
            int j = i + 1;
            v[i] = (bo.e[j * 3] - bo.e[0]) * w[0]
                    + (bo.e[j * 3 + 1] - bo.e[1]) * w[1]
                    + (bo.e[j * 3 + 2] - bo.e[2]) * w[2];
        }
        return v;
    }

    /**
     * 跑一段模拟：从第 {@code startEpoch} 个历元起，rover 先验位置误差每历元增加
     * {@code roverRate}，各基站每历元增加 {@code baseRate[b]}（持续爬升，模拟标墩沉降）。
     *
     * @return 跑完后的监测器
     */
    private static BaseStabilityMonitor run(int nb, int epochs, int startEpoch,
                                            double[] roverRate, double[][] baseRate) {
        BaselineObs[] bo = new BaselineObs[nb];
        int[][] vflg = new int[nb][NV];
        for (int b = 0; b < nb; b++) {
            bo[b] = obs();
            for (int i = 0; i < NV; i++) {
                // (satRef<<16)|(satJ<<8)|(code<<4)|frq，code 位为 0 表示载波
                vflg[b][i] = (REF_SAT << 16) | ((i + 2) << 8);
            }
        }

        BaseStabilityMonitor mon = new BaseStabilityMonitor();
        double[] rr = new double[3];
        double[][] db = new double[nb][3];
        for (int ep = 0; ep < epochs; ep++) {
            if (ep >= startEpoch) {
                for (int k = 0; k < 3; k++) rr[k] += roverRate[k];
                for (int b = 0; b < nb; b++) {
                    for (int k = 0; k < 3; k++) db[b][k] += baseRate[b][k];
                }
            }
            double[][] v = new double[nb][];
            double[][] var = new double[nb][];
            int[] nv = new int[nb];
            for (int b = 0; b < nb; b++) {
                double[] w = {rr[0] - db[b][0], rr[1] - db[b][1], rr[2] - db[b][2]};
                v[b] = residual(bo[b], w);
                var[b] = new double[NV];
                java.util.Arrays.fill(var[b], VAR);
                nv[b] = NV;
            }
            mon.update(bo, nb, v, nv, vflg, var);
        }
        return mon;
    }

    private static double[][] zeroRates(int nb) {
        return new double[nb][3];
    }

    @Test
    @DisplayName("k=2：单台基站位移被公共模式分离均分，不可归因 -> 不降权、不剔除")
    void twoBasesAreNotAttributable() {
        double[][] rate = zeroRates(2);
        rate[0][0] = 0.40;
        BaseStabilityMonitor mon = run(2, 100, 20, NO_RATE, rate);

        // 闸2 确实检出了位移（否则本用例只是「无信号」而非「不归因」）。
        // 注意 k=2 时公共分量取均值，位移被均分成 ±d/2，故这里只有 0.2 m 而非 0.4 m；
        // 物理位移由 physicalStep 乘 2 还原，但它只在 updateWeights 内部使用。
        assertEquals(0.20, mon.getStepMax(0), 0.02,
                "闸2 应检出被均分一半的 0.2 m 位移，实际 stepMax=" + mon.getStepMax(0));

        // 但 k=2 无法区分是哪台基站动了，对健康基站降权会破坏其 AR，故必须放弃
        assertEquals(1.0, mon.getWeight(0), 1e-9);
        assertEquals(1.0, mon.getWeight(1), 1e-9);
        assertFalse(mon.isDisplaced(0));
        assertFalse(mon.isDisplaced(1));
    }

    @Test
    @DisplayName("k=3：单台基站位移可归因 -> 只降权该基线，持续超限后剔除")
    void threeBasesAttributeToDisplacedBase() {
        double[][] rate = zeroRates(3);
        rate[0][0] = 0.40;
        BaseStabilityMonitor mon = run(3, 100, 20, NO_RATE, rate);

        assertTrue(mon.getWeight(0) > 1.0,
                "位移基线应被降权，实际 weight=" + mon.getWeight(0));
        assertEquals(1.0, mon.getWeight(1), 1e-9);
        assertEquals(1.0, mon.getWeight(2), 1e-9);
        assertEquals(BaseStabilityMonitor.DISPLACED, mon.getStatus(0));
        assertEquals(BaseStabilityMonitor.STABLE, mon.getStatus(1));
        assertEquals(BaseStabilityMonitor.STABLE, mon.getStatus(2));
    }

    @Test
    @DisplayName("k=3：rover 侧公共跳变被中位数吸收，不误判为任何基站位移")
    void roverJumpIsAbsorbedByCommonMode() {
        double[][] rate = zeroRates(3);
        BaseStabilityMonitor mon = run(3, 100, 20, new double[]{0.40, 0.0, 0.0}, rate);

        for (int b = 0; b < 3; b++) {
            assertEquals(1.0, mon.getWeight(b), 1e-9,
                    "rover 侧跳变不应降权 baseline[" + b + "]");
            assertFalse(mon.isDisplaced(b));
        }
    }

    @Test
    @DisplayName("k=4：全部基线同时位移（无健康参照）-> 整体放弃降权")
    void noHealthyReferenceGivesUpDownWeight() {
        double[][] rate = zeroRates(4);
        rate[0][0] = 0.40;
        rate[1][0] = 0.80;
        rate[2][0] = 1.20;
        rate[3][0] = 1.60;
        BaseStabilityMonitor mon = run(4, 100, 20, NO_RATE, rate);

        // 四条基线都「看起来」位移了：同等降权只放大 Qb 的绝对尺度，反而破坏 AR
        for (int b = 0; b < 4; b++) {
            assertEquals(1.0, mon.getWeight(b), 1e-9,
                    "无健康参照时应放弃降权，baseline[" + b + "] weight=" + mon.getWeight(b));
            assertFalse(mon.isDisplaced(b));
        }
    }
}