package org.rtklib.java.research.pipeline;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.rtklib.java.research.data.Solution;
import org.rtklib.java.research.data.SolutionStatus;

/**
 * 分场景评估切片器。
 *
 * <p>按高度角/卫星数将历元分为三类场景，独立统计精度，回答
 * "方法在哪种环境下有效"这一关键问题。</p>
 *
 * <pre>
 *   OPEN_SKY:        avgEl > 30° 且 nSat > 8
 *   PARTIAL_BLOCKED: 15° ≤ avgEl ≤ 30° 或 5 ≤ nSat ≤ 8
 *   URBAN_CANYON:    avgEl < 15° 或 nSat < 5
 * </pre>
 *
 * <p>输出示例：
 * Scene            Epochs   FixRate   RMS_E(m)  RMS_N(m)  RMS_U(m)
 * ─────────────────────────────────────────────────────────────────
 * OPEN_SKY          1200     98.2%     0.021     0.018     0.035
 * PARTIAL_BLOCKED    680     87.5%     0.145     0.132     0.210
 * URBAN_CANYON       320     62.1%     0.890     0.760     1.450
 * ─────────────────────────────────────────────────────────────────
 * OVERALL           2200     89.0%     0.231     0.205     0.380
 * </pre>
 */
public class SceneEvaluator {

    public enum Scene {
        OPEN_SKY("Open Sky"),
        PARTIAL_BLOCKED("Partial"),
        URBAN_CANYON("Canyon"),
        UNCLASSIFIED("Unclass");

        public final String label;

        Scene(String label) {
            this.label = label;
        }
    }

    public static class SceneStats {
        public Scene scene;
        public int count;
        public int fixCount;
        public double fixRate;
        public double rmsE;
        public double rmsN;
        public double rmsU;
        public double stdE;
        public double stdN;
        public double stdU;

        @Override
        public String toString() {
            return String.format("%-16s %6d %7.1f%% %8.4f %8.4f %8.4f",
                    scene.label, count, fixRate * 100, rmsE, rmsN, rmsU);
        }
    }

    private static final double OPEN_SKY_EL = 30.0;
    private static final double CANYON_EL = 15.0;
    private static final int OPEN_SKY_NSAT = 8;
    private static final int CANYON_NSAT = 5;

    /**
     * 按高度角+卫星数分类单个历元。
     *
     * @param avgElevationDeg 平均高度角（度）
     * @param nSat            有效卫星数
     * @return 场景类型
     */
    public static Scene classify(double avgElevationDeg, int nSat) {
        if (avgElevationDeg > OPEN_SKY_EL && nSat > OPEN_SKY_NSAT) {
            return Scene.OPEN_SKY;
        }
        if (avgElevationDeg < CANYON_EL || nSat < CANYON_NSAT) {
            return Scene.URBAN_CANYON;
        }
        return Scene.PARTIAL_BLOCKED;
    }

    /**
     * 从观测历元实时分类场景（在线自适应检测）。
     *
     * <p>仅使用卫星数做粗分类，适合快速在线判断。精确分类应使用
     * {@link #classify(double, int)} 并传入平均高度角。</p>
     *
     * @param nSat 有效卫星数
     * @return 场景类型
     */
    public static Scene classify(int nSat) {
        if (nSat > OPEN_SKY_NSAT) {
            return Scene.OPEN_SKY;
        }
        if (nSat < CANYON_NSAT) {
            return Scene.URBAN_CANYON;
        }
        return Scene.PARTIAL_BLOCKED;
    }

    /**
     * 生成场景自适应求解器配置。
     *
     * <p>根据当前场景类型自动选择最优策略：
     * <ul>
     *   <li>OPEN_SKY: 不使用鲁棒损失和开关变量（保持最高精度）</li>
     *   <li>PARTIAL_BLOCKED: 仅使用 Huber 鲁棒损失（标准方案）</li>
     *   <li>URBAN_CANYON: 使用 SwitchVariable 开关变量（最强方案）</li>
     * </ul>
     *
     * @param baseConfig 基础配置（会被复制，不修改原配置）
     * @param scene      当前场景
     * @return 场景自适应的新配置
     */
    public static SolverConfig adaptiveStrategy(SolverConfig baseConfig, Scene scene) {
        SolverConfig cfg = baseConfig.copy();

        switch (scene) {
            case OPEN_SKY:
                cfg.useRobustLoss = false;
                cfg.useSwitchVariable = false;
                break;
            case PARTIAL_BLOCKED:
                cfg.useRobustLoss = true;
                cfg.robustLossType = "HUBER";
                cfg.robustLossThreshold = 1.345;
                cfg.useSwitchVariable = false;
                break;
            case URBAN_CANYON:
                cfg.useRobustLoss = false;
                cfg.useSwitchVariable = true;
                cfg.switchPriorSigma = 0.1;
                break;
            default:
                break;
        }
        return cfg;
    }

    /**
     * 对解序列按场景分组统计。
     *
     * @param solutions 解序列
     * @param elevations 每个历元的平均高度角（度），可为 null（仅按 nSat 分类）
     * @param refPos     参考位置 ECEF (m)，用于计算 ENU 误差，可为 null（不计算 RMS）
     * @return 按场景分组的统计结果，额外包含 key=null 的 OVERALL 合计
     */
    public Map<Scene, SceneStats> evaluate(List<Solution> solutions, double[] elevations, double[] refPos) {
        Map<Scene, List<Solution>> grouped = new LinkedHashMap<>();
        for (Scene s : Scene.values()) {
            grouped.put(s, new ArrayList<>());
        }

        for (int i = 0; i < solutions.size(); i++) {
            Solution sol = solutions.get(i);
            double el = (elevations != null && i < elevations.length) ? elevations[i] : 45.0;
            Scene scene = classify(el, sol.numSatellites);
            grouped.get(scene).add(sol);
        }

        Map<Scene, SceneStats> result = new LinkedHashMap<>();
        for (Map.Entry<Scene, List<Solution>> entry : grouped.entrySet()) {
            if (entry.getValue().isEmpty()) continue;
            result.put(entry.getKey(), computeStats(entry.getKey(), entry.getValue(), refPos));
        }

        List<Solution> all = new ArrayList<>(solutions);
        result.put(null, computeStats(null, all, refPos));

        return result;
    }

    private SceneStats computeStats(Scene scene, List<Solution> solutions, double[] refPos) {
        SceneStats s = new SceneStats();
        s.scene = scene;
        s.count = solutions.size();

        double sumE = 0, sumN = 0, sumU = 0;
        double sumE2 = 0, sumN2 = 0, sumU2 = 0;

        for (Solution sol : solutions) {
            if (sol.status == SolutionStatus.FIX) s.fixCount++;

            if (refPos != null && sol.position != null && sol.position.length >= 3) {
                double de = sol.position[0] - refPos[0];
                double dn = sol.position[1] - refPos[1];
                double du = sol.position[2] - refPos[2];
                sumE += de;
                sumN += dn;
                sumU += du;
                sumE2 += de * de;
                sumN2 += dn * dn;
                sumU2 += du * du;
            }
        }

        s.fixRate = s.count > 0 ? (double) s.fixCount / s.count : 0;

        if (s.count > 0) {
            double meanE = sumE / s.count;
            double meanN = sumN / s.count;
            double meanU = sumU / s.count;
            s.rmsE = Math.sqrt(sumE2 / s.count);
            s.rmsN = Math.sqrt(sumN2 / s.count);
            s.rmsU = Math.sqrt(sumU2 / s.count);
            s.stdE = Math.sqrt(Math.max(sumE2 / s.count - meanE * meanE, 0));
            s.stdN = Math.sqrt(Math.max(sumN2 / s.count - meanN * meanN, 0));
            s.stdU = Math.sqrt(Math.max(sumU2 / s.count - meanU * meanU, 0));
        }

        return s;
    }

    /**
     * 格式化场景对比表。
     *
     * @param statsMap evaluate() 的返回值
     * @return 可打印的表格字符串
     */
    public static String formatSceneTable(Map<Scene, SceneStats> statsMap) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("%-16s %6s %7s %8s %8s %8s %8s %8s %8s%n",
                "Scene", "Epochs", "FixRate", "RMS_E", "RMS_N", "RMS_U", "STD_E", "STD_N", "STD_U"));
        sb.append("-".repeat(88)).append("\n");

        SceneStats overall = null;
        for (Map.Entry<Scene, SceneStats> entry : statsMap.entrySet()) {
            if (entry.getKey() == null) {
                overall = entry.getValue();
                continue;
            }
            SceneStats s = entry.getValue();
            sb.append(String.format("%-16s %6d %6.1f%% %8.4f %8.4f %8.4f %8.4f %8.4f %8.4f%n",
                    s.scene.label, s.count, s.fixRate * 100,
                    s.rmsE, s.rmsN, s.rmsU, s.stdE, s.stdN, s.stdU));
        }
        sb.append("-".repeat(88)).append("\n");
        if (overall != null) {
            sb.append(String.format("%-16s %6d %6.1f%% %8.4f %8.4f %8.4f %8.4f %8.4f %8.4f%n",
                    "OVERALL", overall.count, overall.fixRate * 100,
                    overall.rmsE, overall.rmsN, overall.rmsU, overall.stdE, overall.stdN, overall.stdU));
        }
        return sb.toString();
    }
}