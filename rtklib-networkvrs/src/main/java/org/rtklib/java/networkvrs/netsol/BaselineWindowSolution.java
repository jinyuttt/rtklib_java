package org.rtklib.java.networkvrs.netsol;

import org.rtklib.java.data.GTime;

import java.util.Map;
import java.util.Set;

/**
 * 单基线单窗口网解结果。
 *
 * @param stationA        站 A
 * @param stationB        站 B
 * @param start           窗口起始历元
 * @param end             窗口结束历元
 * @param refSats         参考星集合（每个「系统+频点对」分组各一颗）
 * @param fixed           窄巷模糊度是否固定
 * @param ambiguityCount  参与固定的双差模糊度个数
 * @param ratio           LAMBDA ratio（s[1]/s[0]）；未固定时为 0
 * @param phaseRms        相位双差残差 RMS（米）
 * @param estimatedDelta  站 B 坐标增量估计 [3]（米）
 * @param fixedN1         卫星 -> 固定的 N1 整周数
 * @param fixedN2         卫星 -> 固定的 N2 整周数
 * @param floatNifMeters  卫星 -> 浮点 IF 模糊度（米）
 * @param floatN1Cycles   卫星 -> 浮点 N1（周）
 * @param diagnostics     归因诊断量（弧段切分/几何/宽巷层）
 */
public record BaselineWindowSolution(
        String stationA,
        String stationB,
        GTime start,
        GTime end,
        Set<Integer> refSats,
        boolean fixed,
        int ambiguityCount,
        double ratio,
        double phaseRms,
        double[] estimatedDelta,
        Map<Integer, Integer> fixedN1,
        Map<Integer, Integer> fixedN2,
        Map<Integer, Double> floatNifMeters,
        Map<Integer, Double> floatN1Cycles,
        ArDiagnostics diagnostics) {

    /**
     * 窗口归因诊断量，用于把窄巷固定失败区分成三类根因。
     *
     * <p>三类根因各有一组可判读的量：</p>
     * <ul>
     *   <li>弧段误切：{@code jumpCuts}/{@code gapCuts} 与 {@code dominantArcEpochs}。
     *       若切弧频繁且主导弧段短于窗口，则失败源于弧段被切碎而非窗口长度；</li>
     *   <li>几何不足：{@code satellites}/{@code fixedPdop}/{@code ambiguityCondition}。
     *       几何判读只看 {@code fixedPdop}（模糊度已知时的位置精度），
     *       {@code floatPdop} 含模糊度不确定性，会随弧段数增加而虚高，不能当几何判据；</li>
     *   <li>宽巷未稳：{@code wlFixed}/{@code satellites}。若宽巷固定率本身就低，
     *       窄巷分级固定无从谈起；若宽巷已高而 {@code firstRatio} 偏低，才是窄巷瓶颈。</li>
     * </ul>
     *
     * @param epochs             参与解算的历元数（参考星集合一致且通过筛选的历元）
     * @param satellites         公共卫星数（双差对）
     * @param arcs               弧段总数
     * @param gapCuts            由历元间隔超限触发的切弧次数
     * @param jumpCuts           由相位阶跃触发的切弧次数
     * @param dominantArcEpochs  各卫星主导弧段的平均历元数
     * @param wlFixed            宽巷固定成功的卫星数
     * @param floatPdop          浮点解位置精度因子（含模糊度不确定性）
     * @param fixedPdop          几何 PDOP（模糊度不建参数）；固定窗口取固定解回代值（只用已固定弧段），
     *                           未固定窗口退化为全部观测的几何 PDOP——观测更多，该值略偏乐观，
     *                           用作「失败窗口几何并不更差」的保守判据
     * @param ambiguityCondition 首次尝试的 N1 模糊度协方差条件数（最大/最小特征值）
     * @param conditionConverged 条件数 Jacobi 迭代是否收敛；{@code false} 表示满扫描仍未达标，
     *                           该窗口的条件数只能作参考、不宜跨窗口比较
     * @param firstAmbiguities   首次尝试参与 LAMBDA 的模糊度个数
     * @param firstRatio         首次尝试的 LAMBDA ratio（未进入 LAMBDA 时为 0）
     * @param attempts           LAMBDA 调用次数（含部分固定剔除重试）
     */
    public record ArDiagnostics(
            int epochs,
            int satellites,
            int arcs,
            int gapCuts,
            int jumpCuts,
            double dominantArcEpochs,
            int wlFixed,
            double floatPdop,
            double fixedPdop,
            double ambiguityCondition,
            boolean conditionConverged,
            int firstAmbiguities,
            double firstRatio,
            int attempts) {

        /**
         * @return 切弧触发次数（间隔 + 阶跃）
         */
        public int cuts() {
            return gapCuts + jumpCuts;
        }

        /**
         * @return 宽巷固定率（0~1）
         */
        public double wlRate() {
            return satellites == 0 ? 0.0 : (double) wlFixed / satellites;
        }
    }

    public BaselineWindowSolution {
        refSats = refSats == null ? Set.of() : Set.copyOf(refSats);
        estimatedDelta = estimatedDelta == null ? new double[3] : estimatedDelta.clone();
        fixedN1 = fixedN1 == null ? Map.of() : Map.copyOf(fixedN1);
        fixedN2 = fixedN2 == null ? Map.of() : Map.copyOf(fixedN2);
        floatNifMeters = floatNifMeters == null ? Map.of() : Map.copyOf(floatNifMeters);
        floatN1Cycles = floatN1Cycles == null ? Map.of() : Map.copyOf(floatN1Cycles);
    }

    /**
     * @return 该历元（绝对秒）是否落在本窗口内
     */
    public boolean covers(double absoluteSeconds) {
        return absoluteSeconds >= start.time + start.sec && absoluteSeconds <= end.time + end.sec;
    }

    /**
     * @return IF 模糊度（米，按 N1/N2 与宽巷关系换算）
     */
    public static double ifAmbiguityMeters(double f1, double f2, int n1, int n2) {
        return org.rtklib.java.constants.Constants.CLIGHT / (f1 + f2)
                * (f1 * n1 - f2 * n2) / (f1 - f2);
    }
}