package org.rtklib.java.joint;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.PrcOpt;

import static org.junit.jupiter.api.Assertions.*;

/**
 * [§2.1.1] 共享大气状态布局的维度与索引一致性测试。
 *
 * <p>不依赖实测数据，只验证 nx 计算、baselineOffset 起点与 II/ITrover/ITbase 索引
 * 在同一布局下自洽：默认 BRDC/SAAS 下与旧「每基线复制」结构逐维一致（回归安全），
 * EST 共享模式下大气不再随基线数线性膨胀。</p>
 */
class JointAtmLayoutTest {

    private static final int K = 3;

    private static JointRtk make(int k, int ionoopt, int tropopt, boolean ionoGradient,
                                 JointConfig cfg, double baselineLen) {
        JointRtk rtk = new JointRtk();
        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_KINEMA;
        opt.nf = 2;
        opt.ionoopt = ionoopt;
        opt.tropopt = tropopt;
        opt.ionoGradient = ionoGradient;
        rtk.opt = opt;
        rtk.jointConfig = cfg;
        rtk.nBaseline = k;
        rtk.rb = new double[]{0.0, 0.0, 0.0};
        rtk.x = new double[]{1.0, 2.0, 3.0};
        rtk.bases = new BaseStation[k];
        for (int b = 0; b < k; b++) {
            rtk.bases[b] = new BaseStation("B" + b, new double[]{baselineLen * (b + 1), 0.0, 0.0});
        }
        rtk.refreshAtmSharing();
        rtk.baselineOffset = new int[k];
        for (int b = 0; b < k; b++) {
            rtk.baselineOffset[b] = rtk.baselineOffsetOf(b);
        }
        return rtk;
    }

    private static JointConfig cfg(boolean sharedIono, boolean sharedTrop, double maxDistKm) {
        JointConfig c = new JointConfig();
        c.maxBaseline = K;
        c.sharedIono = sharedIono;
        c.sharedTrop = sharedTrop;
        c.sharedIonoMaxDist = maxDistKm;
        return c;
    }

    @Test
    @DisplayName("默认 BRDC/SAAS：维度与旧「每基线复制」结构逐维一致")
    void testDefaultModelLayoutUnchanged() {
        int nf = 2;
        for (int k = 1; k <= K; k++) {
            JointRtk rtk = make(k, Constants.IONOOPT_BRDC, Constants.TROPOPT_SAAS, false,
                    cfg(true, true, 0.0), 1000.0);

            assertEquals(3, rtk.NP(), "BRDC/SAAS 下非模糊度状态只有位置");
            assertEquals(0, rtk.NIBlock(), "BRDC 不应产生电离层状态");
            assertEquals(0, rtk.NTPerR(), "SAAS 不应产生对流层状态");
            assertEquals(0, rtk.NAtm(), "BRDC/SAAS 下大气维数应为 0");
            assertEquals(3 + k * Constants.MAXSAT * nf, rtk.NXTotal(),
                    "k=" + k + " 时 nx 应与旧布局一致");

            for (int b = 0; b < k; b++) {
                assertEquals(3 + b * rtk.NB(), rtk.baselineOffsetOf(b),
                        "k=" + k + " baseline[" + b + "] 起点应与旧布局一致");
            }
        }
    }

    @Test
    @DisplayName("EST 电离层共享：大气不随基线数线性膨胀")
    void testSharedIonoDimension() {
        JointRtk rtk = make(K, Constants.IONOOPT_EST, Constants.TROPOPT_SAAS, false,
                cfg(true, true, 0.0), 1000.0);

        assertEquals(1, rtk.NIonoBlocks(), "共享电离层应只有 1 个块");
        assertEquals(Constants.MAXSAT, rtk.NIBlock(), "无梯度时每卫星 1 维");
        assertEquals(Constants.MAXSAT, rtk.NAtm(), "共享电离层总维数应为 MAXSAT，而非 k*MAXSAT");
        assertEquals(3 + Constants.MAXSAT + K * rtk.NB(), rtk.NXTotal());

        for (int b = 0; b < K; b++) {
            assertEquals(0, rtk.ionoBlockOf(b), "共享模式下所有基线取同一电离层块");
        }
        for (int sat = 1; sat <= Constants.MAXSAT; sat++) {
            assertEquals(3 + (sat - 1), rtk.II(0, sat), "sat=" + sat + " 的电离层索引");
        }
    }

    @Test
    @DisplayName("EST 对流层：rover 共享 1 份 + 每基站各 1 份")
    void testSharedTropDimension() {
        JointRtk rtk = make(K, Constants.IONOOPT_BRDC, Constants.TROPOPT_EST, false,
                cfg(true, true, 0.0), 1000.0);

        assertEquals(1, rtk.NTPerR(), "EST 无梯度时每站 1 维 ZWD");
        assertEquals(1, rtk.NTropRoverBlocks(), "rover 侧对流层应只有 1 个块");
        assertEquals(1 + K, rtk.NAtm(), "大气维数 = rover 1 + 基站 k");
        assertEquals(3 + 1 + K + K * rtk.NB(), rtk.NXTotal());

        assertEquals(3, rtk.ITrover(0, 0), "rover ZWD 紧随位置");
        for (int b = 0; b < K; b++) {
            assertEquals(3 + 1 + b, rtk.ITbase(b, 0), "基站[" + b + "] ZWD 索引");
        }
    }

    @Test
    @DisplayName("EST+梯度：每站 3 维，rover 共享 + 基站各一份")
    void testTropGradientDimension() {
        JointRtk rtk = make(K, Constants.IONOOPT_BRDC, Constants.TROPOPT_ESTG, true,
                cfg(true, true, 0.0), 1000.0);

        assertEquals(3, rtk.NTPerR(), "ESTG 含 ZWD+北+东梯度");
        assertEquals((1 + K) * 3, rtk.NAtm());
        assertEquals(3 + (1 + K) * 3 + K * rtk.NB(), rtk.NXTotal());

        assertEquals(3, rtk.ITrover(0, 0));
        assertEquals(4, rtk.ITrover(0, 1));
        assertEquals(5, rtk.ITrover(0, 2));
        assertEquals(6, rtk.ITbase(0, 0));
        assertEquals(6 + 3 * (K - 1), rtk.ITbase(K - 1, 0));
    }

    @Test
    @DisplayName("关闭共享：退回每基线一份（回归对照）")
    void testNonSharedLayout() {
        JointRtk rtk = make(K, Constants.IONOOPT_EST, Constants.TROPOPT_ESTG, true,
                cfg(false, false, 0.0), 1000.0);

        assertEquals(K, rtk.NIonoBlocks());
        assertEquals(K, rtk.NTropRoverBlocks());
        assertEquals(K * 3 * Constants.MAXSAT + (K + K) * 3, rtk.NAtm());
        assertEquals(3 + K * 3 * Constants.MAXSAT + (K + K) * 3 + K * rtk.NB(), rtk.NXTotal());

        for (int b = 0; b < K; b++) {
            assertEquals(b, rtk.ionoBlockOf(b));
            assertEquals(b, rtk.tropRoverBlockOf(b));
        }
        assertEquals(3 + rtk.NIBlock(), rtk.II(1, 1), "块 1 的电离层起点应为 pos + 单个块维数");
    }

    @Test
    @DisplayName("长基线超限：共享电离层自动退回每基线独立")
    void testIonoSharingFallbackByDistance() {
        JointRtk rtk = make(K, Constants.IONOOPT_EST, Constants.TROPOPT_SAAS, false,
                cfg(true, true, 100.0), 500_000.0);

        assertFalse(rtk.sharedIonoOn, "最长基线 1500km 超过 100km 上限，应停用共享");
        assertEquals(K, rtk.NIonoBlocks());
        assertEquals(K * Constants.MAXSAT, rtk.NAtm());

        for (int b = 0; b < K; b++) {
            rtk.bases[b].rb = new double[]{5_000.0 * (b + 1), 0.0, 0.0};
        }
        rtk.refreshAtmSharing();
        assertFalse(rtk.sharedIonoOn, "超距停用后应锁定，避免基线抖动导致 nx 反复重建");
        assertEquals(K, rtk.NIonoBlocks());

        JointRtk near = make(K, Constants.IONOOPT_EST, Constants.TROPOPT_SAAS, false,
                cfg(true, true, 100.0), 5_000.0);
        assertTrue(near.sharedIonoOn, "5km 基线在 100km 上限内，共享应保持开启");
        assertEquals(Constants.MAXSAT, near.NAtm());
    }

    @Test
    @DisplayName("索引连续性：大气段与模糊度段无重叠且紧邻")
    void testIndexContiguity() {
        JointRtk rtk = make(K, Constants.IONOOPT_EST, Constants.TROPOPT_ESTG, true,
                cfg(true, true, 0.0), 1000.0);

        int nx = rtk.NXTotal();
        boolean[] used = new boolean[nx];

        for (int i = 0; i < rtk.NP(); i++) used[i] = true;
        for (int sat = 1; sat <= Constants.MAXSAT; sat++) {
            for (int r = 0; r < rtk.NIPerSat(); r++) {
                int idx = rtk.II(0, sat) + r;
                assertTrue(idx >= 0 && idx < nx, "电离层索引越界: " + idx);
                assertFalse(used[idx], "电离层索引重复: " + idx);
                used[idx] = true;
            }
        }
        for (int r = 0; r < rtk.NTPerR(); r++) {
            int idx = rtk.ITrover(0, r);
            assertFalse(used[idx], "rover 对流层索引重复: " + idx);
            used[idx] = true;
            for (int b = 0; b < K; b++) {
                int ib = rtk.ITbase(b, r);
                assertFalse(used[ib], "基站对流层索引重复: " + ib);
                used[ib] = true;
            }
        }
        for (int i = 0; i < rtk.NAtm(); i++) {
            assertTrue(used[rtk.NP() + i], "大气段应连续覆盖 [NP, NP+NAtm)");
        }

        int ambStart = rtk.baselineOffsetOf(0);
        assertEquals(rtk.NRTotal(), ambStart, "模糊度段应紧邻大气段之后");
        for (int b = 0; b < K; b++) {
            for (int f = 0; f < 2; f++) {
                for (int sat = 1; sat <= Constants.MAXSAT; sat++) {
                    int idx = rtk.IB(b, sat, f);
                    assertTrue(idx >= 0 && idx < nx, "模糊度索引越界: " + idx);
                    assertFalse(used[idx], "模糊度索引重复: " + idx);
                    used[idx] = true;
                }
            }
        }
    }
}