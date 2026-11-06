package org.rtklib.java.networkvrs.netsol;

import org.ejml.simple.SimpleMatrix;
import org.rtklib.java.ambiguity.Lambda;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.networkvrs.config.NetworkConfig;

import java.util.ArrayList;
import java.util.Arrays;
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

    /**
     * 未知数排列：{@code [dx, dy, dz, ztdA, ztdB, ztdRateA, ztdRateB, N1..Nn]}。
     *
     * <p>ztd 为窗口中心时刻的天顶延迟残差（米），ztdRate 为线性变化率（米/小时）。
     * 1 小时窗口内对流层可变化 1~3 cm，仅用常数项时相位残差会出现线性漂移，
     * 直接把 LAMBDA 的整数候选拉开、ratio 掉到 3 以下。</p>
     */
    private static final int ZTD_A_OFFSET = 3;
    private static final int ZTD_B_OFFSET = 4;
    private static final int ZTD_RATE_A_OFFSET = 5;
    private static final int ZTD_RATE_B_OFFSET = 6;
    private static final int ZTD_AMB_OFFSET = 7;

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
        boolean debugAr = Boolean.getBoolean("network.debug.ar");
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

        int nEpochs = usable.size();

        // 逐卫星观测序列（历元 × 卫星），供周跳检测与弧段划分使用
        Map<Integer, DdObservation[]> seriesBySat = new LinkedHashMap<>();
        for (int k = 0; k < nSats; k++) {
            seriesBySat.put(satList.get(k), new DdObservation[nEpochs]);
        }
        for (int e = 0; e < nEpochs; e++) {
            for (DdObservation d : usable.get(e).observations()) {
                DdObservation[] series = seriesBySat.get(d.sat());
                if (series != null) {
                    series[e] = d;
                }
            }
        }

        // 周跳检测：相邻历元「相位 − 几何 − 模型」出现阶跃即认为失锁，在该处切分弧段，
        // 各弧段独立估计一个 IF 模糊度。若窗口内不分弧，一次 1 周失锁会让整窗模糊度
        // 偏移约半周（残差呈 ±Δ/2），并把残余 ZTD 拉离先验（实测可达 −7 m），
        // 使 LAMBDA 的整数候选整体错位、ratio 塌到 1.0 左右。
        int[][] arcIdOfSat = new int[nSats][nEpochs];
        int[][] ambIndexOfSat = new int[nSats][];
        int nAmb = 0;
        double sampling = samplingIntervalSeconds(usable);
        for (int k = 0; k < nSats; k++) {
            Arrays.fill(arcIdOfSat[k], -1);
            DdObservation[] series = seriesBySat.get(satList.get(k));
            int arcs = 0;
            int prevEpoch = -1;
            double prevDelta = 0.0;
            for (int e = 0; e < nEpochs; e++) {
                DdObservation d = series[e];
                if (d == null) {
                    continue;
                }
                double delta = d.ddPhaseIf() - d.ddRange() - d.ddModelTropo();
                boolean slip = prevEpoch < 0;
                if (prevEpoch >= 0) {
                    double dt = epochSeconds(usable.get(e)) - epochSeconds(usable.get(prevEpoch));
                    double jump = Math.abs(delta - prevDelta);
                    boolean gapSlip = dt > config.maxArcGapFactor * sampling;
                    boolean jumpSlip = jump > config.cycleSlipThresholdMeters;
                    if (gapSlip || jumpSlip) {
                        slip = true;
                        if (debugAr) {
                            System.out.printf("[slip] %s-%s sat=%d t=%s dt=%.1fs jump=%.3f m %s%n",
                                    stationA, stationB, satList.get(k), usable.get(e).time(),
                                    dt, jump, gapSlip ? "gap" : "jump");
                        }
                    }
                }
                if (slip) {
                    arcs++;
                }
                arcIdOfSat[k][e] = arcs - 1;
                prevEpoch = e;
                prevDelta = delta;
            }
            ambIndexOfSat[k] = new int[arcs];
            for (int a = 0; a < arcs; a++) {
                ambIndexOfSat[k][a] = nAmb++;
            }
        }
        // 每颗卫星取历元数最多的弧段代表该卫星（用于浮点解输出与 LAMBDA 固定）
        int[] domArcOfSat = new int[nSats];
        for (int k = 0; k < nSats; k++) {
            int[] counts = new int[ambIndexOfSat[k].length];
            for (int e = 0; e < nEpochs; e++) {
                int a = arcIdOfSat[k][e];
                if (a >= 0) {
                    counts[a]++;
                }
            }
            int bestArc = 0;
            for (int a = 1; a < counts.length; a++) {
                if (counts[a] > counts[bestArc]) {
                    bestArc = a;
                }
            }
            domArcOfSat[k] = bestArc;
        }

        int nx = ZTD_AMB_OFFSET + nAmb;
        double[][] normal = new double[nx][nx];
        double[] rhs = new double[nx];

        double sigmaPhaseIf = config.phaseSigmaM * 3.0;
        double sigmaCodeIf = config.codeSigmaM * 3.0;
        Map<Integer, Integer> satIndex = new LinkedHashMap<>();
        for (int k = 0; k < nSats; k++) {
            satIndex.put(satList.get(k), k);
        }
        // 站 A/B 各估计一个残余天顶对流层延迟及其线性变化率，消除 Saastamoinen 模型误差
        // 与窗口内对流层缓慢变化。双差对流层残差量级约 1~2 ppm 基线长，因此先验随基线长
        // 缩放（下限 2 cm）：短基线下若沿用固定的 0.3 m 先验，天顶延迟与高程分量强相关，
        // 会白白吃掉自由度并把位置估计拉偏（实测 20 历元窗口固定率 29.2%→34.7%）。
        int ambOffset = ZTD_AMB_OFFSET;
        double dx = ecefB[0] - ecefA[0];
        double dy = ecefB[1] - ecefA[1];
        double dz = ecefB[2] - ecefA[2];
        double baselineMeters = Math.sqrt(dx * dx + dy * dy + dz * dz);
        double sigmaZtdPrior = Math.max(0.02, 2e-6 * baselineMeters);
        double sigmaZtdRatePrior = 0.1;
        normal[ZTD_A_OFFSET][ZTD_A_OFFSET] += 1.0 / (sigmaZtdPrior * sigmaZtdPrior);
        normal[ZTD_B_OFFSET][ZTD_B_OFFSET] += 1.0 / (sigmaZtdPrior * sigmaZtdPrior);
        normal[ZTD_RATE_A_OFFSET][ZTD_RATE_A_OFFSET] += 1.0 / (sigmaZtdRatePrior * sigmaZtdRatePrior);
        normal[ZTD_RATE_B_OFFSET][ZTD_RATE_B_OFFSET] += 1.0 / (sigmaZtdRatePrior * sigmaZtdRatePrior);

        double tMid = (epochSeconds(usable.get(0)) + epochSeconds(usable.get(nEpochs - 1))) / 2.0;

        Map<Integer, Double> f1BySat = new LinkedHashMap<>();
        Map<Integer, Double> f2BySat = new LinkedHashMap<>();
        Map<Integer, List<Double>> mwSeries = new LinkedHashMap<>();
        for (int k = 0; k < nSats; k++) {
            mwSeries.put(satList.get(k), new ArrayList<>());
        }

        for (int e = 0; e < nEpochs; e++) {
            double tau = (epochSeconds(usable.get(e)) - tMid) / 3600.0;
            for (DdObservation d : usable.get(e).observations()) {
                Integer k = satIndex.get(d.sat());
                if (k == null) {
                    continue;
                }
                f1BySat.putIfAbsent(d.sat(), d.f1());
                f2BySat.putIfAbsent(d.sat(), d.f2());
                mwSeries.get(d.sat()).add(d.ddMwCycles());

                int ambParam = ambOffset + ambIndexOfSat[k][arcIdOfSat[k][e]];
                double[] h = new double[nx];
                h[0] = d.losX();
                h[1] = d.losY();
                h[2] = d.losZ();
                h[ZTD_A_OFFSET] = d.tropoMapDiffA();
                h[ZTD_B_OFFSET] = -d.tropoMapDiffB();
                h[ZTD_RATE_A_OFFSET] = d.tropoMapDiffA() * tau;
                h[ZTD_RATE_B_OFFSET] = -d.tropoMapDiffB() * tau;
                h[ambParam] = 1.0;
                double yPhase = d.ddPhaseIf() - d.ddRange() - d.ddModelTropo();
                accumulate(normal, rhs, h, yPhase, 1.0 / (sigmaPhaseIf * sigmaPhaseIf));

                double[] hc = new double[nx];
                System.arraycopy(h, 0, hc, 0, ambOffset);
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
        int[] ambParamOfSat = new int[nSats];
        for (int k = 0; k < nSats; k++) {
            ambParamOfSat[k] = ambOffset + ambIndexOfSat[k][domArcOfSat[k]];
            floatNif.put(satList.get(k), x[ambParamOfSat[k]]);
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
                floatN1.put(sat, n1FromIf(x[ambParamOfSat[k]], f1BySat.get(sat), f2BySat.get(sat), wl));
            }
        }

        double phaseRms = phaseRms(usable, x, satIndex, arcIdOfSat, ambIndexOfSat, ambOffset, tMid);
        boolean fixed = false;
        double ratio = 0.0;
        int ambiguityCount = 0;
        Map<Integer, Integer> fixedN1 = new LinkedHashMap<>();
        Map<Integer, Integer> fixedN2 = new LinkedHashMap<>();
        Map<Integer, Double> fixedNifByParam = new LinkedHashMap<>();

        // 部分固定（partial AR）：ratio 不达标时逐步剔除模糊度标准差最大的卫星再重试，
        // 与 RTKLIB 的处理一致；低高度角/多路径卫星的模糊度方差大，往往是拉低 ratio 的源头
        List<Integer> active = new ArrayList<>(wlFixed.keySet());
        boolean firstAttempt = true;
        while (active.size() >= config.minFixedAmbiguities) {
            int nFix = active.size();
            // 消元：每颗可固定卫星的整周 N1 = x_if/λ_if − f2·N_WL/(f1−f2)
            double[] a = new double[nFix];
            double[] qa = new double[nFix * nFix];
            double[] scale = new double[nFix];
            for (int i = 0; i < nFix; i++) {
                int sat = active.get(i);
                double f1 = f1BySat.get(sat);
                double f2 = f2BySat.get(sat);
                scale[i] = (f1 + f2) / Constants.CLIGHT;
                a[i] = n1FromIf(x[ambParamOfSat[satIndex.get(sat)]], f1, f2, wlFixed.get(sat));
                for (int j = 0; j < nFix; j++) {
                    int p = ambParamOfSat[satIndex.get(sat)];
                    int r = ambParamOfSat[satIndex.get(active.get(j))];
                    qa[i * nFix + j] = q[p][r] * scale[i] * scale[j];
                }
            }
            if (firstAttempt && debugAr) {
                System.out.printf("[ar] %s-%s dx=(%.3f %.3f %.3f) m ztd=(%.3f %.3f) m "
                                + "ztdRate=(%.3f %.3f) m/h sigma=(%.3f %.3f %.3f)%n",
                        stationA, stationB, x[0], x[1], x[2],
                        x[ZTD_A_OFFSET], x[ZTD_B_OFFSET],
                        x[ZTD_RATE_A_OFFSET], x[ZTD_RATE_B_OFFSET],
                        Math.sqrt(q[0][0]), Math.sqrt(q[1][1]), Math.sqrt(q[2][2]));
                for (int i = 0; i < nFix; i++) {
                    int sat = active.get(i);
                    System.out.printf("[ar] %s-%s sat=%d sys=%d f1=%.3f f2=%.3f wlFloat=%+.3f "
                                    + "wlFix=%d n1=%.3f sigma=%.3f cyc%n",
                            stationA, stationB, sat, SatUtils.satsys(sat, null),
                            f1BySat.get(sat) / 1e6, f2BySat.get(sat) / 1e6,
                            wlFloat.get(sat), wlFixed.get(sat), a[i],
                            Math.sqrt(qa[i * nFix + i]));
                }
                for (int k = 0; k < nSats; k++) {
                    int sat = satList.get(k);
                    double sum = 0.0;
                    int cnt = 0;
                    double firstRes = Double.NaN;
                    double lastRes = Double.NaN;
                    for (int e = 0; e < nEpochs; e++) {
                        double tau = (epochSeconds(usable.get(e)) - tMid) / 3600.0;
                        for (DdObservation d : usable.get(e).observations()) {
                            if (d.sat() != sat) {
                                continue;
                            }
                            double predicted = x[0] * d.losX() + x[1] * d.losY() + x[2] * d.losZ()
                                    + x[ZTD_A_OFFSET] * d.tropoMapDiffA()
                                    - x[ZTD_B_OFFSET] * d.tropoMapDiffB()
                                    + x[ZTD_RATE_A_OFFSET] * d.tropoMapDiffA() * tau
                                    - x[ZTD_RATE_B_OFFSET] * d.tropoMapDiffB() * tau
                                    + x[ambOffset + ambIndexOfSat[k][arcIdOfSat[k][e]]];
                            double r = (d.ddPhaseIf() - d.ddRange() - d.ddModelTropo()) - predicted;
                            if (Double.isNaN(firstRes)) {
                                firstRes = r;
                            }
                            lastRes = r;
                            sum += r * r;
                            cnt++;
                        }
                    }
                    System.out.printf("[satres] %s-%s sat=%d n=%d rms=%.2f cm arcs=%d "
                                    + "first=%+.2f last=%+.2f cm%n",
                            stationA, stationB, sat, cnt,
                            cnt == 0 ? Double.NaN : Math.sqrt(sum / cnt) * 100.0,
                            ambIndexOfSat[k].length, firstRes * 100.0, lastRes * 100.0);
                }
                int dbgSat = active.get(0);
                int dbgK = satIndex.get(dbgSat);
                for (int e = 0; e < nEpochs; e++) {
                    double tau = (epochSeconds(usable.get(e)) - tMid) / 3600.0;
                    for (DdObservation d : usable.get(e).observations()) {
                        if (d.sat() != dbgSat) {
                            continue;
                        }
                        double res = d.ddPhaseIf() - d.ddRange() - d.ddModelTropo()
                                - (d.losX() * x[0] + d.losY() * x[1] + d.losZ() * x[2]
                                + d.tropoMapDiffA() * x[ZTD_A_OFFSET]
                                - d.tropoMapDiffB() * x[ZTD_B_OFFSET]
                                + d.tropoMapDiffA() * tau * x[ZTD_RATE_A_OFFSET]
                                - d.tropoMapDiffB() * tau * x[ZTD_RATE_B_OFFSET])
                                - x[ambOffset + ambIndexOfSat[dbgK][arcIdOfSat[dbgK][e]]];
                        System.out.printf("[res] %s-%s sat=%d t=%s el=%.1f res=%.2f cm%n",
                                stationA, stationB, dbgSat, usable.get(e).time(),
                                d.elevationSatDeg(), res * 100.0);
                    }
                }
                firstAttempt = false;
            }
            double[] f = new double[nFix * 2];
            double[] s = new double[2];
            if (Lambda.lambda(nFix, 2, a, qa, f, s) != 0 || !(s[0] > 0.0)) {
                break;
            }
            ratio = s[1] / s[0];
            if (debugAr) {
                System.out.printf("[lam] %s-%s nFix=%d s0=%.3f s1=%.3f ratio=%.2f%n",
                        stationA, stationB, nFix, s[0], s[1], ratio);
            }
            if (ratio >= config.arRatioThreshold) {
                fixed = true;
                ambiguityCount = nFix;
                for (int i = 0; i < nFix; i++) {
                    int sat = active.get(i);
                    int n1 = (int) Math.rint(f[i * 2]);
                    fixedN1.put(sat, n1);
                    fixedN2.put(sat, n1 - wlFixed.get(sat));
                    fixedNifByParam.put(ambParamOfSat[satIndex.get(sat)],
                            BaselineWindowSolution.ifAmbiguityMeters(
                                    f1BySat.get(sat), f2BySat.get(sat), n1, n1 - wlFixed.get(sat)));
                }
                break;
            }
            int worst = 0;
            for (int i = 1; i < nFix; i++) {
                if (qa[i * nFix + i] > qa[worst * nFix + worst]) {
                    worst = i;
                }
            }
            active.remove(worst);
        }

        if (fixed) {
            // 固定解回代：把已固定的 IF 模糊度当作已知量，只重估位置与对流层参数。
            // 若不回代，输出的 δx 仍是浮点解，固定窗口之间的基线长度会漂移 0.1~1 m。
            double[] xFixed = fixedSolution(usable, fixedNifByParam, satIndex,
                    arcIdOfSat, ambIndexOfSat, ambOffset, sigmaPhaseIf, sigmaCodeIf, tMid);
            if (xFixed != null) {
                System.arraycopy(xFixed, 0, x, 0, ambOffset);
            }
        }

        double rms = fixed
                ? fixedPhaseRms(usable, x, fixedNifByParam, satIndex, arcIdOfSat, ambIndexOfSat,
                        ambOffset, tMid)
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

    /**
     * 固定解回代：以已固定的 IF 模糊度作为已知量，重估位置增量与对流层参数。
     *
     * <p>只使用参与固定的弧段（参数索引命中 {@code fixedNifByParam}）；同一卫星被周跳
     * 切出的其他弧段整周值未固定，不能进入本解。</p>
     *
     * @return 位置与对流层参数估计 {@code [dx, dy, dz, ztdA, ztdB, ztdRateA, ztdRateB]}；
     *         无法求解返回 {@code null}
     */
    private static double[] fixedSolution(List<DdEpoch> epochs,
                                          Map<Integer, Double> fixedNifByParam,
                                          Map<Integer, Integer> satIndex,
                                          int[][] arcIdOfSat, int[][] ambIndexOfSat,
                                          int ambOffset, double sigmaPhaseIf, double sigmaCodeIf,
                                          double tMid) {
        double[][] normal = new double[ambOffset][ambOffset];
        double[] rhs = new double[ambOffset];
        double sigmaZtdPrior = 0.3;
        double sigmaZtdRatePrior = 0.1;
        normal[ZTD_A_OFFSET][ZTD_A_OFFSET] += 1.0 / (sigmaZtdPrior * sigmaZtdPrior);
        normal[ZTD_B_OFFSET][ZTD_B_OFFSET] += 1.0 / (sigmaZtdPrior * sigmaZtdPrior);
        normal[ZTD_RATE_A_OFFSET][ZTD_RATE_A_OFFSET] += 1.0 / (sigmaZtdRatePrior * sigmaZtdRatePrior);
        normal[ZTD_RATE_B_OFFSET][ZTD_RATE_B_OFFSET] += 1.0 / (sigmaZtdRatePrior * sigmaZtdRatePrior);

        int count = 0;
        for (int e = 0; e < epochs.size(); e++) {
            double tau = (epochSeconds(epochs.get(e)) - tMid) / 3600.0;
            for (DdObservation d : epochs.get(e).observations()) {
                Integer k = satIndex.get(d.sat());
                if (k == null) {
                    continue;
                }
                Double nif = fixedNifByParam.get(ambOffset + ambIndexOfSat[k][arcIdOfSat[k][e]]);
                if (nif == null) {
                    continue;
                }
                double[] h = new double[ambOffset];
                h[0] = d.losX();
                h[1] = d.losY();
                h[2] = d.losZ();
                h[ZTD_A_OFFSET] = d.tropoMapDiffA();
                h[ZTD_B_OFFSET] = -d.tropoMapDiffB();
                h[ZTD_RATE_A_OFFSET] = d.tropoMapDiffA() * tau;
                h[ZTD_RATE_B_OFFSET] = -d.tropoMapDiffB() * tau;
                double yPhase = d.ddPhaseIf() - d.ddRange() - d.ddModelTropo() - nif;
                accumulate(normal, rhs, h, yPhase, 1.0 / (sigmaPhaseIf * sigmaPhaseIf));
                double yCode = d.ddCodeIf() - d.ddRange() - d.ddModelTropo();
                accumulate(normal, rhs, h, yCode, 1.0 / (sigmaCodeIf * sigmaCodeIf));
                count++;
            }
        }
        if (count == 0) {
            return null;
        }
        try {
            SimpleMatrix nMat = new SimpleMatrix(ambOffset, ambOffset);
            for (int i = 0; i < ambOffset; i++) {
                for (int j = 0; j < ambOffset; j++) {
                    nMat.set(i, j, normal[i][j]);
                }
            }
            SimpleMatrix rhsMat = new SimpleMatrix(ambOffset, 1);
            for (int i = 0; i < ambOffset; i++) {
                rhsMat.set(i, 0, rhs[i]);
            }
            SimpleMatrix xMat = nMat.solve(rhsMat);
            double[] out = new double[ambOffset];
            for (int i = 0; i < ambOffset; i++) {
                out[i] = xMat.get(i, 0);
            }
            return out;
        } catch (RuntimeException e) {
            return null;
        }
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

    /** 历元绝对时刻（GPST 秒），用于周跳检测中的采样间隔判断。 */
    private static double epochSeconds(DdEpoch epoch) {
        return epoch.time().time + epoch.time().sec;
    }

    /** 窗口平均采样间隔（秒），缺省按 30 s 处理。 */
    private static double samplingIntervalSeconds(List<DdEpoch> epochs) {
        double sum = 0.0;
        int count = 0;
        for (int i = 1; i < epochs.size() && count < 20; i++) {
            double dt = epochSeconds(epochs.get(i)) - epochSeconds(epochs.get(i - 1));
            if (dt > 0.0) {
                sum += dt;
                count++;
            }
        }
        return count == 0 ? 30.0 : sum / count;
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
                                   Map<Integer, Integer> satIndex,
                                   int[][] arcIdOfSat, int[][] ambIndexOfSat, int ambOffset,
                                   double tMid) {
        double sum = 0.0;
        int count = 0;
        for (int e = 0; e < epochs.size(); e++) {
            double tau = (epochSeconds(epochs.get(e)) - tMid) / 3600.0;
            for (DdObservation d : epochs.get(e).observations()) {
                Integer k = satIndex.get(d.sat());
                if (k == null) {
                    continue;
                }
                double predicted = x[0] * d.losX() + x[1] * d.losY() + x[2] * d.losZ()
                        + x[ZTD_A_OFFSET] * d.tropoMapDiffA() - x[ZTD_B_OFFSET] * d.tropoMapDiffB()
                        + x[ZTD_RATE_A_OFFSET] * d.tropoMapDiffA() * tau
                        - x[ZTD_RATE_B_OFFSET] * d.tropoMapDiffB() * tau
                        + x[ambOffset + ambIndexOfSat[k][arcIdOfSat[k][e]]];
                double r = (d.ddPhaseIf() - d.ddRange() - d.ddModelTropo()) - predicted;
                sum += r * r;
                count++;
            }
        }
        return count == 0 ? Double.NaN : Math.sqrt(sum / count);
    }

    /**
     * 固定解的相位残差 RMS。只统计参与固定弧段（参数索引命中 {@code fixedNifByParam}）的观测：
     * 同一卫星在窗口内可能被周跳切成多段，未参与固定的弧段其整周值未知，不能计入。
     */
    private static double fixedPhaseRms(List<DdEpoch> epochs, double[] x,
                                        Map<Integer, Double> fixedNifByParam,
                                        Map<Integer, Integer> satIndex,
                                        int[][] arcIdOfSat, int[][] ambIndexOfSat, int ambOffset,
                                        double tMid) {
        double sum = 0.0;
        int count = 0;
        for (int e = 0; e < epochs.size(); e++) {
            double tau = (epochSeconds(epochs.get(e)) - tMid) / 3600.0;
            for (DdObservation d : epochs.get(e).observations()) {
                Integer k = satIndex.get(d.sat());
                if (k == null) {
                    continue;
                }
                Double nif = fixedNifByParam.get(ambOffset + ambIndexOfSat[k][arcIdOfSat[k][e]]);
                if (nif == null) {
                    continue;
                }
                double predicted = x[0] * d.losX() + x[1] * d.losY() + x[2] * d.losZ()
                        + x[ZTD_A_OFFSET] * d.tropoMapDiffA() - x[ZTD_B_OFFSET] * d.tropoMapDiffB()
                        + x[ZTD_RATE_A_OFFSET] * d.tropoMapDiffA() * tau
                        - x[ZTD_RATE_B_OFFSET] * d.tropoMapDiffB() * tau
                        + nif;
                double r = (d.ddPhaseIf() - d.ddRange() - d.ddModelTropo()) - predicted;
                sum += r * r;
                count++;
            }
        }
        return count == 0 ? Double.NaN : Math.sqrt(sum / count);
    }
}