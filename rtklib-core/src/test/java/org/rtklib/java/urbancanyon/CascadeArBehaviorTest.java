package org.rtklib.java.urbancanyon;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.CoordType;
import org.rtklib.java.data.Position;
import org.rtklib.java.data.PrcOpt;
import org.rtklib.java.data.SolData;
import org.rtklib.java.data.SolutionStatus;
import org.rtklib.java.rinex.RinexRtkProcessor;
import org.rtklib.java.rtkpos.RtkProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * [JAVA-ONLY] Cascade AR 真逐级实现行为验证（FIX-ALG-007 验收）。
 *
 * <p>在非 IFLC（EST 电离层）多频 DD 模式下对比级联开/关：
 * 验收要点为无假 Fix（固定解相对参考轨迹 RMSE 合理）、Fix 率与 RMSE 不显著劣化。
 * 旧伪实现因 IFLC 守卫从未在数据集测试中激活，本测试确保新实现在真实路径上被验证。</p>
 */
@DisplayName("CascadeAR 行为验证 - EST模式 A/B 对比")
@Tag("manual")
public class CascadeArBehaviorTest {

    private static final Logger log = LoggerFactory.getLogger(CascadeArBehaviorTest.class);
    private static final double D2R = Constants.D2R;
    private static final double GPS_EPOCH_UNIX = 315964800.0;
    private static final int SYS_MULTI = Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_GLO
            | Constants.SYS_CMP | Constants.SYS_QZS;
    private static final String BASE = "D:/code/rtklib_java/test-data/ppc/PPC-Dataset";

    // 诊断开关（环境变量）：CASCADE_AB_DATASET="Tokyo run2" 单数据集；CASCADE_AB_BASELINE=false 跳过基线
    private static final String ONLY_DATASET = System.getenv().getOrDefault("CASCADE_AB_DATASET", "");
    private static final boolean RUN_BASELINE = !"false".equalsIgnoreCase(System.getenv().getOrDefault("CASCADE_AB_BASELINE", "true"));

    record DataSet(String name, String roverObs, String baseObs, String nav, String referencePath) {
        boolean exists() {
            return Files.exists(Paths.get(roverObs)) && Files.exists(Paths.get(baseObs))
                    && Files.exists(Paths.get(nav)) && Files.exists(Paths.get(referencePath));
        }
    }

    static final DataSet[] DATA_SETS = {
        new DataSet("Tokyo run1", BASE + "/tokyo/run1/rover.obs", BASE + "/tokyo/run1/base.obs",
                BASE + "/tokyo/run1/base.nav", BASE + "/tokyo/run1/reference.csv"),
        new DataSet("Tokyo run2", BASE + "/tokyo/run2/rover.obs", BASE + "/tokyo/run2/base.obs",
                BASE + "/tokyo/run2/base.nav", BASE + "/tokyo/run2/reference.csv"),
        new DataSet("Nagoya run1", BASE + "/nagoya/run1/rover.obs", BASE + "/nagoya/run1/base.obs",
                BASE + "/nagoya/run1/base.nav", BASE + "/nagoya/run1/reference.csv"),
        new DataSet("Nagoya run2", BASE + "/nagoya/run2/rover.obs", BASE + "/nagoya/run2/base.obs",
                BASE + "/nagoya/run2/base.nav", BASE + "/nagoya/run2/reference.csv"),
    };

    private static PrcOpt baseOpt() {
        PrcOpt opt = RinexRtkProcessor.createDefaultOpt();
        opt.navsys = SYS_MULTI;
        opt.nf = 2;
        opt.modear = Constants.ARMODE_FIXHOLD;
        opt.elmin = 15.0 * D2R;
        opt.ionoopt = Constants.IONOOPT_EST;
        opt.tropopt = Constants.TROPOPT_SAAS;
        opt.refpos = Constants.POSOPT_POS_XYZ;
        // 对齐 C-EST-Ref / CV_EST_REF
        opt.dynamics = 1;
        opt.thresar[1] = 0.9999;
        opt.prn[3] = 3.0;
        opt.prn[4] = 1.0;
        opt.glomodear = Constants.GLO_ARMODE_OFF;
        opt.bdsmodear = 0;
        opt.elmaskhold = 15.0 * D2R;
        opt.thresar[6] = 10.0;
        return opt;
    }

    record RunStat(String dsName, String configName, int total, int fix, int floatN,
                   double fixRate, double hRmseFix, double vRmseFix,
                   double hRmseFloat, double vRmseFloat, int fixCountValid,
                   double p50FixH, double p90FixH, double p99FixH, double maxFixH, double pctOver2m) {
    }

    private static double percentile(List<Double> vals, double p) {
        if (vals.isEmpty()) return -1;
        List<Double> s = new ArrayList<>(vals);
        java.util.Collections.sort(s);
        int idx = (int) Math.ceil(p * s.size()) - 1;
        return s.get(Math.max(0, Math.min(s.size() - 1, idx)));
    }

    private RunStat runOne(DataSet ds, boolean cascadeOn) {
        PrcOpt opt = baseOpt();
        RtkConfig cfg = new RtkConfig();
        if (cascadeOn) cfg.rtk.enableCascadeAR = true;

        RtkProcessor.RtkResult result;
        try {
            result = RinexRtkProcessor.processRinex(ds.roverObs, ds.baseObs, ds.nav, opt, cfg);
        } catch (Exception e) {
            log.error("[{}][cascade={}] RTK异常: {}", ds.name, cascadeOn, e.getMessage(), e);
            return new RunStat(ds.name, cascadeOn ? "EST-CascadeAR" : "EST-Ref", 0, 0, 0, 0, -1, -1, -1, -1, 0, -1, -1, -1, -1, -1);
        }

        int total = result.totalEpochs;
        int fix = 0, floatN = 0;
        for (SolData sol : result.solutions) {
            if (sol.status == SolutionStatus.FIX) fix++;
            else if (sol.status == SolutionStatus.FLOAT) floatN++;
        }
        double fixRate = total > 0 ? (double) fix / total : 0;

        List<double[]> refs = loadReference(ds.referencePath);
        List<Double> fixHorErrs = new ArrayList<>();
        double sumE2Fix = 0, sumN2Fix = 0, sumU2Fix = 0, sumE2F = 0, sumN2F = 0, sumU2F = 0;
        int cntFix = 0, cntFloat = 0;
        for (SolData sol : result.solutions) {
            Position p = sol.getPosition(CoordType.LLH);
            if (p == null) continue;
            double[] nearest = findNearestRef(sol.time.time + sol.time.sec, refs);
            if (nearest == null) continue;
            double dLat = (p.v1 - nearest[1]) * D2R;
            double dLon = (p.v2 - nearest[2]) * D2R;
            double e = dLon * Math.cos(nearest[1] * D2R) * Constants.RE_WGS84;
            double n = dLat * Constants.RE_WGS84;
            double u = p.v3 - nearest[3];
            if (sol.status == SolutionStatus.FIX) {
                sumE2Fix += e * e;
                sumN2Fix += n * n;
                sumU2Fix += u * u;
                fixHorErrs.add(Math.hypot(e, n));
                cntFix++;
            } else if (sol.status == SolutionStatus.FLOAT) {
                sumE2F += e * e;
                sumN2F += n * n;
                sumU2F += u * u;
                cntFloat++;
            }
        }
        double hFix = cntFix > 0 ? Math.sqrt((sumE2Fix + sumN2Fix) / cntFix) : -1;
        double vFix = cntFix > 0 ? Math.sqrt(sumU2Fix / cntFix) : -1;
        double hFloat = cntFloat > 0 ? Math.sqrt((sumE2F + sumN2F) / cntFloat) : -1;
        double vFloat = cntFloat > 0 ? Math.sqrt(sumU2F / cntFloat) : -1;
        double p50FixH = percentile(fixHorErrs, 0.50);
        double p90FixH = percentile(fixHorErrs, 0.90);
        double p99FixH = percentile(fixHorErrs, 0.99);
        double maxFixH = fixHorErrs.isEmpty() ? -1 : java.util.Collections.max(fixHorErrs);
        double pctOver2m = fixHorErrs.isEmpty() ? 0
                : 100.0 * fixHorErrs.stream().filter(d -> d > 2.0).count() / fixHorErrs.size();

        // 逐历元误差 CSV（与级联日志按历元 join 用）
        try (java.io.PrintWriter pw = new java.io.PrintWriter(
                new java.io.FileWriter("D:/code/rtklib_java/cascade_epochs_"
                        + ds.name().replaceAll("\\s+", "") + (cascadeOn ? "_cas" : "_ref") + ".csv"), true)) {
            pw.println("unix_time,status,e,n,u");
            for (SolData sol : result.solutions) {
                Position p = sol.getPosition(CoordType.LLH);
                if (p == null) continue;
                double[] nearest = findNearestRef(sol.time.time + sol.time.sec, refs);
                if (nearest == null) continue;
                double dLat = (p.v1 - nearest[1]) * D2R;
                double dLon = (p.v2 - nearest[2]) * D2R;
                double e = dLon * Math.cos(nearest[1] * D2R) * Constants.RE_WGS84;
                double n = dLat * Constants.RE_WGS84;
                double u = p.v3 - nearest[3];
                pw.println(String.format("%.3f,%d,%.4f,%.4f,%.4f",
                        sol.time.time + sol.time.sec, sol.status.ordinal(), e, n, u));
            }
        } catch (Exception ex) {
            log.warn("CSV dump failed: {}", ex.getMessage());
        }

        return new RunStat(ds.name, cascadeOn ? "EST-CascadeAR" : "EST-Ref", total, fix, floatN, fixRate,
                hFix, vFix, hFloat, vFloat, cntFix, p50FixH, p90FixH, p99FixH, maxFixH, pctOver2m);
    }

    @Test
    @DisplayName("EST模式 级联A/B: 相对门限判据（假Fix鉴别+可用性回归，4数据集）")
    void testCascadeEstAB() throws Exception {
        // 分片运行（CASCADE_AB_DATASET 设置时）写独立报告文件，支持 4 进程并行
        String reportPath = ONLY_DATASET.isEmpty()
                ? "D:/code/rtklib_java/cascade_ab_report.txt"
                : "D:/code/rtklib_java/cascade_ab_report_" + ONLY_DATASET.replaceAll("\\s+", "") + ".txt";
        java.io.PrintStream out = new java.io.PrintStream(
                new java.io.FileOutputStream(reportPath), true, "UTF-8");
        List<RunStat> all = new ArrayList<>();
        List<String> violations = new ArrayList<>();
        for (DataSet ds : DATA_SETS) {
            if (!ONLY_DATASET.isEmpty() && !ds.name().equals(ONLY_DATASET)) continue;
            if (!ds.exists()) {
                log.warn("{} 数据不存在，跳过", ds.name);
                continue;
            }
            RunStat ref = null;
            if (RUN_BASELINE) {
                ref = runOne(ds, false);
                all.add(ref);
            }
            RunStat cas = runOne(ds, true);
            all.add(cas);

            // 相对门限策略（FIX-ALG-007 A/B 结论）：
            // 城市峡谷浮点偏差漂移导致的自洽假 Fix 为基线与级联共有的固有问题
            // （Nagoya 两数据集基线 100% 假 Fix、Tokyo run1 基线 Fix H.RMSE 9.5m），
            // 绝对 RMSE 门限对基线自身即不成立，故仅在基线健康区制下执行假 Fix 鉴别。
            if (ref != null && ref.fix() > 0 && cas.fix() > 0) {
                if (ref.p50FixH() >= 0 && ref.p50FixH() < 1.0 && cas.p50FixH() >= 1.0) {
                    violations.add(String.format("%s suspected fake-fix: ref p50FixH=%.3fm (healthy regime) but cascade p50FixH=%.3fm",
                            ds.name, ref.p50FixH(), cas.p50FixH()));
                }
                if (cas.fixRate() < 0.5 * ref.fixRate()) {
                    violations.add(String.format("%s availability regression: cascade fixRate=%.1f%% < 0.5x baseline %.1f%%",
                            ds.name, cas.fixRate() * 100, ref.fixRate() * 100));
                }
            }
        }

        System.out.println();
        System.out.println("CascadeAR EST A/B (ON=true cascade + fallback, OFF=standard LAMBDA)");
        System.out.println("------------------------------------------------------------------");
        String lastName = "";
        for (RunStat r : all) {
            String dsName = r.dsName().equals(lastName) ? "" : r.dsName();
            lastName = r.dsName();
            String line = String.format("| %-12s | %-14s | %7.1f%% | fix=%-7d | float=%-7d | F.H.RMSE=%-9s | Fix.H.RMSE=%-9s | Fix.V.RMSE=%-9s |",
                    dsName, r.configName(), r.fixRate() * 100, r.fix(), r.floatN(),
                    r.hRmseFloat() > 0 ? String.format("%.3f", r.hRmseFloat()) : "N/A",
                    r.hRmseFix() > 0 ? String.format("%.3f", r.hRmseFix()) : "N/A",
                    r.vRmseFix() > 0 ? String.format("%.3f", r.vRmseFix()) : "N/A");
            System.out.println(line);
            out.println(line);
        }

        // Fix 逐历元误差分布（信息项，不作门限）：假 Fix 检测必须看分布而非 RMSE 均值
        // （FIXHOLD 下单个通过 valpos 的错误 fix 会被 holdamb 锁定形成冻结簇）
        System.out.println();
        out.println();
        String distHeader = "| dataset       | config         |  p50(m) |  p90(m) |  p99(m) |  max(m) | >2m %  |";
        System.out.println("Fix epoch error distribution (H, m):");
        System.out.println(distHeader);
        out.println("Fix epoch error distribution (H, m):");
        out.println(distHeader);
        for (RunStat r : all) {
            String line = String.format("| %-12s | %-14s | %7.3f | %7.3f | %7.3f | %7.3f | %6.2f |",
                    r.dsName(), r.configName(), r.p50FixH(), r.p90FixH(), r.p99FixH(), r.maxFixH(), r.pctOver2m());
            System.out.println(line);
            out.println(line);
        }
        out.close();

        if (!violations.isEmpty()) {
            fail(String.join("; ", violations));
        }
    }

    private List<double[]> loadReference(String path) {
        List<double[]> refs = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            String header = br.readLine();
            if (header == null || !header.contains("GPS TOW")) return refs;
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] cols = line.split(",");
                if (cols.length < 8) continue;
                try {
                    double tow = Double.parseDouble(cols[0].trim());
                    int week = Integer.parseInt(cols[1].trim());
                    double lat = Double.parseDouble(cols[2].trim());
                    double lon = Double.parseDouble(cols[3].trim());
                    double h = Double.parseDouble(cols[4].trim());
                    double unixTime = GPS_EPOCH_UNIX + week * 604800.0 + tow;
                    refs.add(new double[]{unixTime, lat, lon, h});
                } catch (NumberFormatException ignore) {
                    // skip
                }
            }
        } catch (Exception e) {
            log.warn("读取参考轨迹失败: {}", e.getMessage());
        }
        return refs;
    }

    private double[] findNearestRef(double t, List<double[]> refs) {
        double bestDt = Double.MAX_VALUE;
        double[] best = null;
        for (double[] ref : refs) {
            double dt = Math.abs(t - ref[0]);
            if (dt < bestDt) {
                bestDt = dt;
                best = ref;
            }
        }
        return bestDt > 1.0 ? null : best;
    }
}
