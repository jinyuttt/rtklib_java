package org.rtklib.java.rtkpos;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.Obsd;
import org.rtklib.java.data.Rtk;
import org.rtklib.java.data.RtkState;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * IFLC 频率对协商单元测试 [JAVA-ONLY v2.3.0]。
 *
 * <p>验证 RtkCore.negotiateIflcPairs / selectIflcPair / iflcSlots 的核心语义：</p>
 * <ul>
 *   <li>公共频段槽位对协商（槽位=code2idx固定频段映射，首槽位恒为0）；</li>
 *   <li>sticky 策略：已有配对结构可用则保持，不因更高优先级对出现而抖动；</li>
 *   <li>配对失效重选并置 iflcPairSwitch（udbias 按周跳处理重置模糊度）；</li>
 *   <li>无公共对清零回退固定槽位 {0, seliflc()}；</li>
 *   <li>开关关闭 / 非RTK双站模式完全 no-op。</li>
 * </ul>
 *
 * <p>典型场景取自 PPC-Dataset Tokyo run1：流动站 Septentrio BDS-3 仅跟踪
 * B1I(slot0)+B2a(slot2)，基准站 Trimble 有 B1I+B2(slot1)+B2a，固定槽位配对
 * {0,1} 因流动站 slot1 缺失导致整星剔除，协商后可用 {0,2}。</p>
 */
@DisplayName("IFLC 频率对协商（逐卫星 sticky 策略）")
public class IflcPairNegotiationTest {

    /** 构造仅在指定槽位有码跟踪的观测（结构可用判定 = code 非 0） */
    private static Obsd obs(int sat, int rcv, int... codeSlots) {
        Obsd o = new Obsd();
        o.sat = sat;
        o.rcv = rcv;
        for (int s : codeSlots) {
            o.code[s] = 1;
        }
        return o;
    }

    /** 构造已开启协商的 RTK 上下文（IONOOPT_IFLC + RTK 动态模式） */
    private static Rtk rtk(int nf) {
        Rtk rtk = new Rtk();
        rtk.opt.ionoopt = Constants.IONOOPT_IFLC;
        rtk.opt.mode = Constants.PMODE_KINEMA;
        rtk.opt.nf = nf;
        rtk.rtkConfig = new RtkConfig();
        rtk.rtkConfig.enableIflcPairNegotiation = true;
        return rtk;
    }

    private static int bdsSat(int prn) {
        return SatUtils.satno(Constants.SYS_CMP, prn);
    }

    @Test
    @DisplayName("PPC Tokyo场景：rover B1I+B2a / base B1I+B2+B2a → 协商到 {0,2}")
    void negotiationSelectsCommonPair() {
        Rtk rtk = rtk(2);
        int sat = bdsSat(6); // BDS-3
        // 流动站 Septentrio：仅 slot0(B1I/C2I) + slot2(B2a/C5P)
        Obsd oR = obs(sat, 1, 0, 2);
        // 基准站 Trimble：slot0 + slot1(B2/C7D) + slot2
        Obsd oB = obs(sat, 2, 0, 1, 2);
        Obsd[] obsArr = {oR, oB};

        RtkCore.negotiateIflcPairs(rtk.toState(), obsArr, 1, 1);

        assertArrayEquals(new int[]{0, 2}, rtk.ssat[sat - 1].iflcSlot,
                "应协商到两站公共频段对 B1I+B2a={0,2}（{0,1}因流动站slot1缺失不可用）");
        assertFalse(rtk.ssat[sat - 1].iflcPairSwitch, "首次协商不构成切换");
    }

    @Test
    @DisplayName("sticky：已有配对仍可用则保持，即使更高优先级对可用")
    void stickyKeepsExistingPair() {
        Rtk rtk = rtk(2);
        int sat = bdsSat(6);
        rtk.ssat[sat - 1].iflcSlot[0] = 0;
        rtk.ssat[sat - 1].iflcSlot[1] = 2;
        // 两站 slot0/1/2 全可用：{0,1}优先级高于{0,2}，但 sticky 不切换
        Obsd[] obsArr = {obs(sat, 1, 0, 1, 2), obs(sat, 2, 0, 1, 2)};

        RtkCore.negotiateIflcPairs(rtk.toState(), obsArr, 1, 1);

        assertArrayEquals(new int[]{0, 2}, rtk.ssat[sat - 1].iflcSlot, "sticky应保持{0,2}");
        assertFalse(rtk.ssat[sat - 1].iflcPairSwitch);
    }

    @Test
    @DisplayName("配对失效重选：流动站slot2消失 → 切到{0,1}并置切换标志")
    void reselectSetsSwitchFlag() {
        Rtk rtk = rtk(2);
        int sat = bdsSat(6);
        rtk.ssat[sat - 1].iflcSlot[0] = 0;
        rtk.ssat[sat - 1].iflcSlot[1] = 2;
        // 流动站 slot2 消失、slot1 出现
        Obsd[] obsArr = {obs(sat, 1, 0, 1), obs(sat, 2, 0, 1, 2)};

        RtkCore.negotiateIflcPairs(rtk.toState(), obsArr, 1, 1);

        assertArrayEquals(new int[]{0, 1}, rtk.ssat[sat - 1].iflcSlot, "应重选到公共对{0,1}");
        assertTrue(rtk.ssat[sat - 1].iflcPairSwitch, "配对切换必须按周跳处理（重置模糊度/清lock）");
    }

    @Test
    @DisplayName("无公共频率对：槽位清零 + 切换标志置位 + 读取点回退固定槽位")
    void noCommonPairClearsAndSignalsSwitch() {
        Rtk rtk = rtk(2);
        int sat = bdsSat(6);
        rtk.ssat[sat - 1].iflcSlot[0] = 0;
        rtk.ssat[sat - 1].iflcSlot[1] = 2;
        // 两站 slot2 都消失，无任何公共频段对
        Obsd[] obsArr = {obs(sat, 1, 0), obs(sat, 2, 0, 1)};

        RtkCore.negotiateIflcPairs(rtk.toState(), obsArr, 1, 1);

        assertArrayEquals(new int[]{0, 0}, rtk.ssat[sat - 1].iflcSlot, "无公共对应清零");
        assertTrue(rtk.ssat[sat - 1].iflcPairSwitch);
        assertArrayEquals(new int[]{0, 1}, RtkCore.iflcSlots(rtk.toState(), sat),
                "清零后 iflcSlots 应回退固定槽位 {0, seliflc(nf=2,CMP)=1}");
    }

    @Test
    @DisplayName("开关关闭：协商no-op，槽位保持{0,0}，读取点回退固定槽位（C版行为）")
    void disabledConfigIsNoOp() {
        Rtk rtk = rtk(2);
        rtk.rtkConfig.enableIflcPairNegotiation = false;
        int sat = bdsSat(6);
        Obsd[] obsArr = {obs(sat, 1, 0, 2), obs(sat, 2, 0, 1, 2)};

        RtkCore.negotiateIflcPairs(rtk.toState(), obsArr, 1, 1);

        assertArrayEquals(new int[]{0, 0}, rtk.ssat[sat - 1].iflcSlot);
        assertFalse(rtk.ssat[sat - 1].iflcPairSwitch);
        assertArrayEquals(new int[]{0, 1}, RtkCore.iflcSlots(rtk.toState(), sat),
                "未协商时回退固定槽位 {0, seliflc(nf=2,CMP)=1}，与C版seliflc一致");
    }

    @Test
    @DisplayName("非RTK双站模式（DGPS / 无基准站观测）：协商no-op")
    void nonRtkModeIsNoOp() {
        int sat = bdsSat(6);

        // mode=PMODE_DGPS（mode <= PMODE_DGPS 不协商）
        Rtk rtkDgps = rtk(2);
        rtkDgps.opt.mode = Constants.PMODE_DGPS;
        RtkCore.negotiateIflcPairs(rtkDgps.toState(), new Obsd[]{obs(sat, 1, 0, 2), obs(sat, 2, 0, 1, 2)}, 1, 1);
        assertArrayEquals(new int[]{0, 0}, rtkDgps.ssat[sat - 1].iflcSlot);

        // nr=0（无基准站观测）
        Rtk rtkNoBase = rtk(2);
        RtkCore.negotiateIflcPairs(rtkNoBase.toState(), new Obsd[]{obs(sat, 1, 0, 2)}, 1, 0);
        assertArrayEquals(new int[]{0, 0}, rtkNoBase.ssat[sat - 1].iflcSlot);
    }

    @Test
    @DisplayName("归并遍历：仅对两站公共卫星协商")
    void onlyCommonSatsNegotiated() {
        Rtk rtk = rtk(2);
        int gps = SatUtils.satno(Constants.SYS_GPS, 3); // 仅流动站
        int bds = bdsSat(6);                             // 两站公共
        int gal = SatUtils.satno(Constants.SYS_GAL, 3); // 仅基准站
        // obs 须按(接收端,卫星号)排序：obs[0..nu)流动站、obs[nu..nu+nr)基准站
        Obsd[] obsArr = {
                obs(gps, 1, 0, 1),
                obs(bds, 1, 0, 2),
                obs(bds, 2, 0, 1, 2),
                obs(gal, 2, 0, 1)
        };

        RtkCore.negotiateIflcPairs(rtk.toState(), obsArr, 2, 2);

        assertArrayEquals(new int[]{0, 2}, rtk.ssat[bds - 1].iflcSlot);
        assertArrayEquals(new int[]{0, 0}, rtk.ssat[gps - 1].iflcSlot, "仅单站可见不协商");
        assertArrayEquals(new int[]{0, 0}, rtk.ssat[gal - 1].iflcSlot, "仅单站可见不协商");
    }

    @Test
    @DisplayName("GAL优先级随nf变化：nf=2首选{0,1}，nf=3首选{0,2}（对齐seliflc语义）")
    void galPriorityDependsOnNf() {
        int gal = SatUtils.satno(Constants.SYS_GAL, 3);
        Obsd[] obsArr = {obs(gal, 1, 0, 1, 2), obs(gal, 2, 0, 1, 2)};

        Rtk rtk2 = rtk(2);
        RtkCore.negotiateIflcPairs(rtk2.toState(), obsArr, 1, 1);
        assertArrayEquals(new int[]{0, 1}, rtk2.ssat[gal - 1].iflcSlot, "nf=2: E1+E5b优先");

        Rtk rtk3 = rtk(3);
        RtkCore.negotiateIflcPairs(rtk3.toState(), obsArr, 1, 1);
        assertArrayEquals(new int[]{0, 2}, rtk3.ssat[gal - 1].iflcSlot, "nf>=3: E1+E5a优先（对齐seliflc(nf>=3,GAL)=2）");
    }
}