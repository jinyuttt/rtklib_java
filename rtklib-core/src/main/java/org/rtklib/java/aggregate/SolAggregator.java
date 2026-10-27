package org.rtklib.java.aggregate;

import org.rtklib.java.data.GTime;
import org.rtklib.java.data.SolData;
import org.rtklib.java.data.SolutionStatus;
import org.rtklib.java.time.TimeSystem;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 历元聚合工具主入口。
 *
 * <p>输入窗口内多个 {@link SolData}，输出一个融合结果。支持两种模式：</p>
 * <ul>
 *   <li>{@link AggregationMode#ROBUST}：鲁棒加权（IGG3 降权），适合静态压噪声、抗假固定</li>
 *   <li>{@link AggregationMode#KALMAN_SMOOTH}：卡尔曼前向滤波 + RTS 平滑，适合跟踪位移</li>
 * </ul>
 *
 * <p>纯函数式接口，不修改 solstatic / RtkProcessor / PppProcessor。</p>
 */
public final class SolAggregator {

    private static final Comparator<SolData> TIME_COMPARATOR =
            Comparator.comparingDouble(s -> TimeSystem.timediff(s.time, new GTime(0, 0.0)));

    private SolAggregator() {
    }

    /**
     * 聚合窗口内历元。
     *
     * @param window 窗口内历元（建议按时间升序，内部会再次排序去重）
     * @param mode   聚合模式
     * @param params 模式相关参数（类型必须与 mode 匹配）
     * @return 聚合结果，永不为 null
     */
    public static AggregatedSol aggregate(List<SolData> window, AggregationMode mode, AggregationParams params) {
        if (window == null || window.isEmpty()) {
            return AggregatedSol.failure(AggregationStatus.EMPTY_WINDOW, null, mode);
        }

        // 参数类型校验
        if (mode == AggregationMode.ROBUST && !(params instanceof RobustParams)) {
            return AggregatedSol.failure(AggregationStatus.PARAM_MISMATCH, lastTime(window), mode);
        }
        if (mode == AggregationMode.KALMAN_SMOOTH && !(params instanceof KalmanParams)) {
            return AggregatedSol.failure(AggregationStatus.PARAM_MISMATCH, lastTime(window), mode);
        }

        // 预处理：排序 + 去重
        List<SolData> sorted = sortAndDedup(window);

        switch (mode) {
            case ROBUST:
                return RobustAggregator.aggregate(sorted, (RobustParams) params);
            case KALMAN_SMOOTH:
                return KalmanSmoothAggregator.aggregate(sorted, (KalmanParams) params);
            default:
                return AggregatedSol.failure(AggregationStatus.PARAM_MISMATCH, lastTime(sorted), mode);
        }
    }

    /**
     * 按时间升序排序，重复时间戳保留 status 优先级最高的一个。
     */
    private static List<SolData> sortAndDedup(List<SolData> window) {
        List<SolData> copy = new ArrayList<>(window);
        copy.sort(TIME_COMPARATOR);

        // 去重：同一时间戳保留优先级最高（FIX > FLOAT > SINGLE > ...）
        Map<Long, SolData> dedup = new LinkedHashMap<>();
        for (SolData sol : copy) {
            long key = sol.time.time * 1000 + (long) (sol.time.sec * 1000);
            SolData existing = dedup.get(key);
            if (existing == null || statusPriority(sol.status) < statusPriority(existing.status)) {
                dedup.put(key, sol);
            }
        }
        return new ArrayList<>(dedup.values());
    }

    private static int statusPriority(SolutionStatus s) {
        // 数值越小优先级越高，对齐 RtkProcessor.SOL_PRIO
        return switch (s) {
            case FIX -> 1;
            case FLOAT -> 2;
            case SBAS -> 3;
            case DGPS -> 4;
            case SINGLE -> 5;
            case PPP -> 6;
            case DR -> 7;
            default -> 8;
        };
    }

    private static GTime lastTime(List<SolData> window) {
        if (window.isEmpty()) return null;
        return new GTime(window.get(window.size() - 1).time);
    }
}
