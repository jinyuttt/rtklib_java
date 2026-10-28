package org.rtklib.java.networkvrs.error;

import org.ejml.simple.SimpleMatrix;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.networkvrs.NetworkVrsProcessor;
import org.rtklib.java.networkvrs.config.NetworkConfig;
import org.rtklib.java.networkvrs.netsol.BaselineWindowSolution;
import org.rtklib.java.networkvrs.netsol.DdObservation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 误差分解：把双差观测量还原为各站相对主站的色散（电离层）与非色散（对流层+轨道残差）改正。
 *
 * <p>每历元做一次小型网络最小二乘：未知量为「除主站外各站 × 各卫星」的改正量，
 * 观测方程来自已固定的基线双差残差，先验来自单站码几何无关组合（电离层）与 0（非色散）。
 * 主站改正量恒为 0（输出相对主站的增量）。</p>
 */
public final class NetworkErrorDecomposer {

    /**
     * @param masterId        主站
     * @param stationIds      参与本历元的站
     * @param ddByBaseline    基线键（A|B）-> 双差观测
     * @param priors          站 -> 卫星 -> 单站模型改正（电离层/对流层，绝对值）
     * @param solutions       覆盖本历元的网解窗口
     * @param config          配置
     * @return 站 -> 卫星 -> 相对主站改正量（主站各星为 0）
     */
    public Map<String, Map<Integer, NetworkVrsProcessor.CorrectionValue>> decomposeEpoch(
            String masterId,
            List<String> stationIds,
            Map<String, List<DdObservation>> ddByBaseline,
            Map<String, Map<Integer, NetworkVrsProcessor.CorrectionValue>> priors,
            List<BaselineWindowSolution> solutions,
            NetworkConfig config) {

        Set<Integer> sats = new LinkedHashSet<>();
        for (Map<Integer, NetworkVrsProcessor.CorrectionValue> bySat : priors.values()) {
            sats.addAll(bySat.keySet());
        }
        for (List<DdObservation> list : ddByBaseline.values()) {
            for (DdObservation d : list) {
                sats.add(d.sat());
                sats.add(d.refSat());
            }
        }
        List<Integer> satList = new ArrayList<>(sats);

        List<String> otherStations = new ArrayList<>();
        for (String id : stationIds) {
            if (!id.equals(masterId)) {
                otherStations.add(id);
            }
        }

        if (satList.isEmpty()) {
            return emptyResult(stationIds);
        }

        double[] iono = solveComponent(masterId, otherStations, satList, ddByBaseline, priors,
                solutions, config, true);
        double[] nonDisp = solveComponent(masterId, otherStations, satList, ddByBaseline, priors,
                solutions, config, false);

        Map<String, Map<Integer, NetworkVrsProcessor.CorrectionValue>> result = new LinkedHashMap<>();
        for (String id : stationIds) {
            result.put(id, new LinkedHashMap<>());
        }
        for (String id : stationIds) {
            Map<Integer, NetworkVrsProcessor.CorrectionValue> bySat = result.get(id);
            for (int i = 0; i < satList.size(); i++) {
                int sat = satList.get(i);
                int si = otherStations.indexOf(id);
                double di = 0.0;
                double dn = 0.0;
                if (si >= 0) {
                    int k = si * satList.size() + i;
                    di = iono[k];
                    dn = nonDisp[k];
                }
                bySat.put(sat, new NetworkVrsProcessor.CorrectionValue(di, dn, 0.0));
            }
        }
        return result;
    }

    /**
     * 求解单个分量的网络最小二乘。
     *
     * @return 按「站索引 × 卫星索引」展开的改正量（相对主站，米）
     */
    private double[] solveComponent(String masterId,
                                    List<String> otherStations,
                                    List<Integer> satList,
                                    Map<String, List<DdObservation>> ddByBaseline,
                                    Map<String, Map<Integer, NetworkVrsProcessor.CorrectionValue>> priors,
                                    List<BaselineWindowSolution> solutions,
                                    NetworkConfig config,
                                    boolean iono) {
        int nSat = satList.size();
        int nx = otherStations.size() * nSat;
        double[] fallback = new double[nx];
        if (nx == 0) {
            return fallback;
        }

        Map<Integer, Integer> satIndex = new LinkedHashMap<>();
        for (int i = 0; i < nSat; i++) {
            satIndex.put(satList.get(i), i);
        }

        double[][] normal = new double[nx][nx];
        double[] rhs = new double[nx];

        double priorSigma = iono ? config.ionoPriorSigmaM : config.nonDispPriorSigmaM;
        Map<Integer, NetworkVrsProcessor.CorrectionValue> masterPriors = priors.get(masterId);
        for (int si = 0; si < otherStations.size(); si++) {
            String id = otherStations.get(si);
            Map<Integer, NetworkVrsProcessor.CorrectionValue> bySat = priors.get(id);
            if (bySat == null) {
                continue;
            }
            for (int i = 0; i < nSat; i++) {
                int sat = satList.get(i);
                NetworkVrsProcessor.CorrectionValue value = bySat.get(sat);
                if (value == null) {
                    continue;
                }
                double prior;
                if (iono) {
                    NetworkVrsProcessor.CorrectionValue m = masterPriors == null ? null : masterPriors.get(sat);
                    if (m == null) {
                        continue;
                    }
                    prior = value.iono() - m.iono();
                } else {
                    prior = 0.0;
                }
                int k = si * nSat + i;
                fallback[k] = prior;
                double[] h = new double[nx];
                h[k] = 1.0;
                accumulate(normal, rhs, h, prior, 1.0 / (priorSigma * priorSigma));
            }
        }

        Map<String, Integer> stationIndex = new LinkedHashMap<>();
        for (int si = 0; si < otherStations.size(); si++) {
            stationIndex.put(otherStations.get(si), si);
        }
        double ddSigma = iono ? config.ddIonoPhaseSigmaM : config.ddNonDispSigmaM;

        for (BaselineWindowSolution solution : solutions) {
            List<DdObservation> ddList = ddByBaseline.get(solution.stationA() + "|" + solution.stationB());
            if (ddList == null) {
                continue;
            }
            double[] delta = solution.estimatedDelta();
            for (DdObservation d : ddList) {
                // 窄巷固定优先；未固定时退回「宽巷整数 + IF 浮点」的降级路径（设计文档 §4）
                double n1;
                double n2;
                double weight = 1.0 / (ddSigma * ddSigma);
                Integer fixed1 = solution.fixedN1().get(d.sat());
                Integer fixed2 = solution.fixedN2().get(d.sat());
                if (fixed1 != null && fixed2 != null) {
                    n1 = fixed1;
                    n2 = fixed2;
                } else {
                    Double nif = solution.floatNifMeters().get(d.sat());
                    Double n1Float = solution.floatN1Cycles().get(d.sat());
                    if (nif == null || n1Float == null) {
                        // 宽巷未固定时整数基准不可知，该星本窗口不参与分解
                        continue;
                    }
                    n1 = n1Float;
                    n2 = n2FromIf(nif, n1, d.f1(), d.f2());
                    weight *= 0.25;
                }
                Integer ia = stationIndex.get(solution.stationA());
                Integer ib = stationIndex.get(solution.stationB());
                int is = satIndex.get(d.sat());
                int ir = satIndex.get(d.refSat());
                double value = iono
                        ? ddIono(d, n1, n2)
                        : ddNonDisp(d, nifMeters(d, n1, n2), delta);
                List<Integer> cols = new ArrayList<>();
                List<Double> signs = new ArrayList<>();
                if (ia != null) {
                    cols.add(ia * nSat + is);
                    signs.add(1.0);
                    cols.add(ia * nSat + ir);
                    signs.add(-1.0);
                }
                if (ib != null) {
                    cols.add(ib * nSat + is);
                    signs.add(-1.0);
                    cols.add(ib * nSat + ir);
                    signs.add(1.0);
                }
                double[] h = new double[nx];
                for (int c = 0; c < cols.size(); c++) {
                    h[cols.get(c)] = signs.get(c);
                }
                accumulate(normal, rhs, h, value, weight);
            }
        }

        if (nx == 0 || !hasWeight(normal)) {
            return fallback;
        }

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
            double[] x = new double[nx];
            for (int i = 0; i < nx; i++) {
                x[i] = xMat.get(i, 0);
            }
            return x;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    /**
     * 双差相位几何无关组合还原的电离层（L1 等效，米）。参考星差分为 0。
     */
    private static double ddIono(DdObservation d, double n1, double n2) {
        double lam1 = Constants.CLIGHT / d.f1();
        double lam2 = Constants.CLIGHT / d.f2();
        double gfResidual = d.ddGfPhase() - (n1 * lam1 - n2 * lam2);
        return gfResidual * d.f2() * d.f2() / (d.f1() * d.f1() - d.f2() * d.f2());
    }

    /**
     * 双差非色散残差：扣掉模型对流层与已估坐标增量投影，剩下对流层模型误差 + 轨道残差。
     */
    private static double ddNonDisp(DdObservation d, double nifDd, double[] delta) {
        double geometry = delta[0] * d.losX() + delta[1] * d.losY() + delta[2] * d.losZ();
        return (d.ddPhaseIf() - d.ddRange() - d.ddModelTropo()) - nifDd - geometry;
    }

    /** IF 模糊度（米，双差，参考星为 0）。 */
    private static double nifMeters(DdObservation d, double n1, double n2) {
        double f1 = d.f1();
        double f2 = d.f2();
        return Constants.CLIGHT / (f1 + f2) * (f1 * n1 - f2 * n2) / (f1 - f2);
    }

    /**
     * 由 IF 浮点模糊度与浮点 N1 反算 N2：{@code f1·N1 − f2·N2 = N_if·(f1²−f2²)/c}。
     */
    private static double n2FromIf(double nif, double n1, double f1, double f2) {
        return (f1 * n1 - nif * (f1 * f1 - f2 * f2) / Constants.CLIGHT) / f2;
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

    private static boolean hasWeight(double[][] normal) {
        for (double[] row : normal) {
            for (double v : row) {
                if (v != 0.0) {
                    return true;
                }
            }
        }
        return false;
    }

    private static Map<String, Map<Integer, NetworkVrsProcessor.CorrectionValue>> emptyResult(
            List<String> stationIds) {
        Map<String, Map<Integer, NetworkVrsProcessor.CorrectionValue>> result = new LinkedHashMap<>();
        for (String id : stationIds) {
            result.put(id, Map.of());
        }
        return result;
    }
}
