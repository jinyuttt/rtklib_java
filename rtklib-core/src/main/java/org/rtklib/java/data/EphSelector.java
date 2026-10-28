package org.rtklib.java.data;

import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.time.TimeSystem;

import java.util.List;

/**
 * 广播星历选择工具：按「参考时刻 toe 距目标历元最近」为每颗卫星挑一条星历。
 *
 * <p>RTKLIB 的 {@code nav.eph[sat-1]} / {@code nav.geph[prn-1]} 是单槽位存储，
 * 连续接收多份星历会被后到的覆盖。MAC 改正差与 VRS 生成都需要在指定历元使用
 * 当时有效的星历（其 IODE / IOD 必须与流动站持有的星历一致），因此需要显式选择。</p>
 */
public final class EphSelector {

    private EphSelector() {
    }

    /**
     * 为指定卫星挑选星历：优先从星历池中取 toe 距 {@code time} 最近的一条，
     * 池中无该卫星时回退 {@code nav.eph[sat-1]}。
     *
     * @param pool 星历池（可为 null 或空）
     * @param nav  导航数据（可为 null，仅用于回退）
     * @param sat  卫星号（1-based，含系统偏移）
     * @param time 目标历元
     * @return 选中的星历；选不到返回 null
     */
    public static Eph selectEph(List<Eph> pool, Nav nav, int sat, GTime time) {
        Eph best = null;
        if (pool != null) {
            for (Eph e : pool) {
                if (e == null || e.sat != sat) continue;
                if (best == null || closer(e.toe, best.toe, time)) best = e;
            }
        }
        if (best == null && nav != null && sat > 0 && sat <= nav.nmax) {
            Eph slot = nav.eph[sat - 1];
            // Nav 的空槽位是占位对象（sat=0），不能当作有效星历返回
            if (slot != null && slot.sat == sat) best = slot;
        }
        return best;
    }

    /**
     * 为指定 GLONASS 卫星挑选星历：星历池按 PRN 匹配，池中无该卫星时回退
     * {@code nav.geph[prn-1]}。
     *
     * @param pool 星历池（可为 null 或空）
     * @param nav  导航数据（可为 null，仅用于回退）
     * @param sat  卫星号（1-based，含系统偏移）
     * @param time 目标历元
     * @return 选中的星历；选不到返回 null
     */
    public static Geph selectGeph(List<Geph> pool, Nav nav, int sat, GTime time) {
        int[] prn = new int[1];
        if (SatUtils.satsys(sat, prn) != Constants.SYS_GLO || prn[0] <= 0) return null;

        Geph best = null;
        if (pool != null) {
            for (Geph g : pool) {
                if (g == null || g.sat != sat) continue;
                if (best == null || closer(g.toe, best.toe, time)) best = g;
            }
        }
        if (best == null && nav != null && prn[0] <= nav.ngmax) {
            Geph slot = nav.geph[prn[0] - 1];
            if (slot != null && slot.sat == sat) best = slot;
        }
        return best;
    }

    /**
     * 把星历池中每颗卫星「toe 距 {@code time} 最近」的星历写入 {@code nav}，
     * 供逐历元生成的编码器（VRS / MAC）读取。
     *
     * <p>池中已存在比 {@code nav} 当前槽位更合适的星历时才覆盖，因此可以反复调用。</p>
     */
    public static void applyEphPool(List<Eph> pool, List<Geph> gephPool, Nav nav, GTime time) {
        if (nav == null) return;
        if (pool != null) {
            for (Eph e : pool) {
                if (e == null || e.sat <= 0 || e.sat > nav.nmax) continue;
                Eph cur = nav.eph[e.sat - 1];
                if (cur == null || closer(e.toe, cur.toe, time)) {
                    nav.eph[e.sat - 1] = e;
                }
            }
        }
        if (gephPool != null) {
            for (Geph g : gephPool) {
                if (g == null) continue;
                int[] prn = new int[1];
                if (SatUtils.satsys(g.sat, prn) != Constants.SYS_GLO
                        || prn[0] <= 0 || prn[0] > nav.ngmax) continue;
                Geph cur = nav.geph[prn[0] - 1];
                if (cur == null || closer(g.toe, cur.toe, time)) {
                    nav.geph[prn[0] - 1] = g;
                }
            }
        }
    }

    private static boolean closer(GTime candidate, GTime incumbent, GTime time) {
        return Math.abs(TimeSystem.timediff(time, candidate))
                < Math.abs(TimeSystem.timediff(time, incumbent));
    }
}