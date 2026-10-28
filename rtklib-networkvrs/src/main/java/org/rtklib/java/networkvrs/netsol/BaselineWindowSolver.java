package org.rtklib.java.networkvrs.netsol;

import org.ejml.simple.SimpleMatrix;
import org.rtklib.java.ambiguity.Lambda;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.networkvrs.config.NetworkConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单基线窗口网解：批处理最小二乘 + 宽巷/窄巷级联固定。
 *
 * <p>窗口内未知量：站 B 坐标增量 δx(3) 与每颗卫星的双差 IF 模糊度 N_if(米)。
 * 观测模型已扣除 Saastamoinen 模型对流层双差（由 {@link DdObservationBuilder} 预先算好），
 * 因此残差中的非色散部分即为「对流层模型误差 + 轨道残差」，交由误差分解处理。</p>
 *
 * <p>求解后先用 MW 组合固定宽巷，再把 IF 模糊度换算为 N1 并用 LAMBDA 固定；
 * ratio 达标才输出固定解，否则保留浮点解。</p>
 */
public final class BaselineWindowSolver {

    private final NetworkConfig config;

    public BaselineWindowSolver(NetworkConfig config) {
        this.config = config;
    }

    /**
     * @param stationA 站 A
     * @param stationB 站 B
     * @param ecefA    站 A 坐标
     * @param ecefB    站 B 坐标（先验）
     * @param epochs   窗口内逐历元双差观测
     * @return 网解结果；有效数据不足或法方程奇异时返回 null
     */
    public BaselineWindowSolution solve(String stationA, String stationB,
                                        double[] ecefA, double[] ecefB,
                                        List<DdEpoch> epochs) {
        if (epochs == null || epochs.isEmpty()) {
            return null;
        }
        // 参考星集合在窗口内可能因卫星起落而变化；取出现次数最多的组合，其余历元跳过
        Map<Set<Integer>, Integer> refCounts = new LinkedHashMap<>();
        for (DdEpoch epoch : epochs) {
            Set<Integer> refs = new LinkedHashSet<>();
            for (DdObservation d : epoch.observations()) {
                refs.add(d.refSat());
            }
            if (!refs.isEmpty()) {
                refCounts.merge(refs, 1, Integer::sum);
            }
        }
        Set<Integer> refSats = null;
        int best = 0;
        for (Map.Entry<Set<Integer>, Integer> e : refCounts.entrySet()) {
            if (e.getValue() > best) {
                best = e.getValue();
                refSats = e.getKey();
            }
        }
        if (refSats == null) {
            return null;
        }

        List<DdEpoch> usable = new ArrayList<>();
        Set<Integer> stable = null;
        for (DdEpoch epoch : epochs) {
            Set<Integer> sats = new LinkedHashSet<>();
            Set<Integer> epochRefs = new LinkedHashSet<>();
            for (DdObservation d : epoch.observations()) {
                epochRefs.add(d.refSat());
                sats.add(d.sat());
            }
            // 参考星集合变化说明双差模糊度基准变了，该历元不能与窗口其他历元合并
            if (sats.isEmpty() || !epochRefs.equals(refSats)) {
                continue;
            }
            stable = stable == null ? sats : intersect(stable, sats);
            usable.add(epoch);
        }
        if (stable == null || stable.size() < config.minCommonSatellites) {
            return null;
        }

        List<Integer> satList = new ArrayList<>(stable);
        int nSats = satList.size();

        int nx = 3 + nSats;
        double[][] normal = new double[nx][nx];
        double[] rhs = new double[nx];

        double sigmaPhaseIf = config.phaseSigmaM * 3.0;
        double sigmaCodeIf = config.codeSigmaM * 3.0;
        Map<Integer, Integer> satIndex = new LinkedHashMap<>();
        for (int k = 0; k < nSats; k++) {
            satIndex.put(satList.get(k), k);
        }
        int ambOffset = 3;

        Map<Integer, Double> f1BySat = new LinkedHashMap<>();
        Map<Integer, Double> f2BySat = new LinkedHashMap<>();
        Map<Integer, List<Double>> mwSeries = new LinkedHashMap<>();
        for (int k = 0; k < nSats; k++) {
            mwSeries.put(satList.get(k), new ArrayList<>());
        }

        for (DdEpoch epoch : usable) {
            for (DdObservation d : epoch.observations()) {
                Integer k = satIndex.get(d.sat());
                if (k == null) {
                    continue;
                }
                f1BySat.putIfAbsent(d.sat(), d.f1());
                f2BySat.putIfAbsent(d.sat(), d.f2());
                mwSeries.get(d.sat()).add(d.ddMwCycles());

                double[] h = new double[nx];
                h[0] = d.losX();
                h[1] = d.losY();
                h[2] = d.losZ();
                h[ambOffset + k] = 1.0;
                double yPhase = d.ddPhaseIf() - d.ddRange() - d.ddModelTropo();
                accumulate(normal, rhs, h, yPhase, 1.0 / (sigmaPhaseIf * sigmaPhaseIf));

                double[] hc = new double[nx];
                System.arraycopy(h, 0, hc, 0, 3);
                double yCode = d.ddCodeIf() - d.ddRange() - d.ddModelTropo();
                accumulate(normal, rhs, hc, yCode, 1.0 / (sigmaCodeIf * sigmaCodeIf));
            }
        }

        double[] x;
        double[][] q;
        try {
            SimpleMatrix nMat = new SimpleMatrix(nx, nx);
            for (int i = 0; i < nx; i++) {
                for (int j = 0; j < nx; j++) {
                    nMat.set(i, j, normal[i][j]);
                }
            }
            SimpleMatrix rhsMat = new SimpleMatrix(nx, 1);
            for (int i = 0; i < nx; i++) {
                rhsMat.set(i, 0, rhs[i]);
            }
            SimpleMatrix xMat = nMat.solve(rhsMat);
            SimpleMatrix qMat = nMat.invert();
            x = new double[nx];
            q = new double[nx][];
            for (int i = 0; i < nx; i++) {
                x[i] = xMat.get(i, 0);
                q[i] = new double[nx];
                for (int j = 0; j < nx; j++) {
                    q[i][j] = qMat.get(i, j);
                }
            }
        } catch (RuntimeException e) {
            return null;
        }

        Map<Integer, Double> floatNif = new LinkedHashMap<>();
        Map<Integer, Double> floatN1 = new LinkedHashMap<>();
        for (int k = 0; k < nSats; k++) {
            floatNif.put(satList.get(k), x[ambOffset + k]);
        }

        // 宽巷固定
        Map<Integer, Integer> wlFixed = new LinkedHashMap<>();
        Map<Integer, Double> wlFloat = new LinkedHashMap<>();
        for (int k = 0; k < nSats; k++) {
            int sat = satList.get(k);
            double mean = mean(mwSeries.get(sat));
            // 用均值的标准误差判断稳定性：单历元 MW 噪声大，但窗口平均后应收敛
            double scatter = std(mwSeries.get(sat), mean) / Math.sqrt(mwSeries.get(sat).size());
            wlFloat.put(sat, mean);
            if (Math.abs(mean - Math.rint(mean)) <= config.wlFixThresholdCycles
                    && scatter <= config.wlScatterThresholdCycles) {
                wlFixed.put(sat, (int) Math.rint(mean));
            }
        }

        // IF 模糊度（米）→ N1（周）；各卫星系统频点不同，需逐星换算
        for (int k = 0; k < nSats; k++) {
            int sat = satList.get(k);
            Integer wl = wlFixed.get(sat);
            if (wl != null) {
                floatN1.put(sat, n1FromIf(x[ambOffset + k], f1BySat.get(sat), f2BySat.get(sat), wl));
            }
        }

        double phaseRms = phaseRms(usable, x, satIndex, ambOffset);
        boolean fixed = false;
        double ratio = 0.0;
        int ambiguityCount = 0;
        Map<Integer, Integer> fixedN1 = new LinkedHashMap<>();
        Map<Integer, Integer> fixedN2 = new LinkedHashMap<>();

        List<Integer> fixSats = new ArrayList<>(wlFixed.keySet());
        // 消元：每颗可固定卫星的整周 N1 = x_if/λ_if − f2·N_WL/(f1−f2)
        int nFix = fixSats.size();
        double[] a = new double[nFix];
        double[] qa = new double[nFix * nFix];
        double[] scale = new double[nFix];
        for (int i = 0; i < nFix; i++) {
            int sat = fixSats.get(i);
            double f1 = f1BySat.get(sat);
            double f2 = f2BySat.get(sat);
            scale[i] = (f1 + f2) / Constants.CLIGHT;
            a[i] = n1FromIf(x[ambOffset + satIndex.get(sat)], f1, f2, wlFixed.get(sat));
            for (int j = 0; j < nFix; j++) {
                qa[i * nFix + j] = q[ambOffset + satIndex.get(sat)][ambOffset + satIndex.get(fixSats.get(j))]
                        * scale[i] * scale[j];
            }
        }
        if (nFix >= config.minFixedAmbiguities) {
            double[] f = new double[nFix * 2];
            double[] s = new double[2];
            if (Lambda.lambda(nFix, 2, a, qa, f, s) == 0 && s[0] > 0.0) {
                ratio = s[1] / s[0];
                if (ratio >= config.arRatioThreshold) {
                    fixed = true;
                    ambiguityCount = nFix;
                    for (int i = 0; i < nFix; i++) {
                        int sat = fixSats.get(i);
                        int n1 = (int) Math.rint(f[i * 2]);
                        fixedN1.put(sat, n1);
                        fixedN2.put(sat, n1 - wlFixed.get(sat));
                    }
                }
            }
        }

        double rms = fixed
                ? fixedPhaseRms(usable, x, fixedN1, fixedN2)
                : phaseRms;
        return new BaselineWindowSolution(stationA, stationB,
                usable.get(0).time(), usable.get(usable.size() - 1).time(),
                refSatsOf(usable), fixed, ambiguityCount, ratio, rms,
                new double[]{x[0], x[1], x[2]},
                fixedN1, fixedN2, floatNif, floatN1);
    }

    /** IF 模糊度（米）→ 整周 N1：{@code N1 = N_if·(f1+f2)/c − f2·N_WL/(f1−f2)}。 */
    private static double n1FromIf(double ifMeters, double f1, double f2, int wlCycles) {
        double lambdaIf = Constants.CLIGHT / (f1 + f2);
        return ifMeters / lambdaIf - f2 * wlCycles / (f1 - f2);
    }

    private static Set<Integer> refSatsOf(List<DdEpoch> epochs) {
        Set<Integer> out = new LinkedHashSet<>();
        for (DdEpoch epoch : epochs) {
            for (DdObservation d : epoch.observations()) {
                out.add(d.refSat());
            }
        }
        return out;
    }

    private static Set<Integer> intersect(Set<Integer> a, Set<Integer> b) {
        Set<Integer> out = new LinkedHashSet<>();
        for (Integer s : a) {
            if (b.contains(s)) {
                out.add(s);
            }
        }
        return out;
    }

    private static void accumulate(double[][] normal, double[] rhs,
                                   double[] h, double y, double w) {
        for (int i = 0; i < h.length; i++) {
            if (h[i] == 0.0) {
                continue;
            }
            rhs[i] += w * h[i] * y;
            for (int j = 0; j < h.length; j++) {
                if (h[j] != 0.0) {
                    normal[i][j] += w * h[i] * h[j];
                }
            }
        }
    }

    private static double mean(List<Double> values) {
        if (values.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (double v : values) {
            sum += v;
        }
        return sum / values.size();
    }

    private static double std(List<Double> values, double mean) {
        if (values.size() < 2) {
            return Double.MAX_VALUE;
        }
        double sum = 0.0;
        for (double v : values) {
            double d = v - mean;
            sum += d * d;
        }
        return Math.sqrt(sum / (values.size() - 1));
    }

    private static double phaseRms(List<DdEpoch> epochs, double[] x,
                                   Map<Integer, Integer> satIndex, int ambOffset) {
        double sum = 0.0;
        int count = 0;
        for (DdEpoch epoch : epochs) {
            for (DdObservation d : epoch.observations()) {
                Integer k = satIndex.get(d.sat());
                if (k == null) {
                    continue;
                }
                double predicted = x[0] * d.losX() + x[1] * d.losY() + x[2] * d.losZ()
                        + x[ambOffset + k];
                double r = (d.ddPhaseIf() - d.ddRange() - d.ddModelTropo()) - predicted;
                sum += r * r;
                count++;
            }
        }
        return count == 0 ? Double.NaN : Math.sqrt(sum / count);
    }

    private static double fixedPhaseRms(List<DdEpoch> epochs, double[] x,
                                        Map<Integer, Integer> fixedN1,
                                        Map<Integer, Integer> fixedN2) {
        double sum = 0.0;
        int count = 0;
        for (DdEpoch epoch : epochs) {
            for (DdObservation d : epoch.observations()) {
                Integer n1 = fixedN1.get(d.sat());
                if (n1 == null) {
                    continue;
                }
                int n2 = fixedN2.get(d.sat());
                double nif = BaselineWindowSolution.ifAmbiguityMeters(d.f1(), d.f2(), n1, n2);
                double predicted = x[0] * d.losX() + x[1] * d.losY() + x[2] * d.losZ() + nif;
                double r = (d.ddPhaseIf() - d.ddRange() - d.ddModelTropo()) - predicted;
                sum += r * r;
                count++;
            }
        }
        return count == 0 ? Double.NaN : Math.sqrt(sum / count);
    }
}
