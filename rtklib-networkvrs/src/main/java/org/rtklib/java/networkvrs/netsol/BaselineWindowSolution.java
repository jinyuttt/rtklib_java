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
        Map<Integer, Double> floatN1Cycles) {

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
