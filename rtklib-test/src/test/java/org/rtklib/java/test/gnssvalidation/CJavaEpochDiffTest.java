package org.rtklib.java.test.gnssvalidation;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.TestDataConfig;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.CoordType;
import org.rtklib.java.data.Position;
import org.rtklib.java.data.PrcOpt;
import org.rtklib.java.data.SolData;
import org.rtklib.java.data.SolOpt;
import org.rtklib.java.data.SolutionStatus;
import org.rtklib.java.rinex.RinexRtkProcessor;
import org.rtklib.java.rtkpos.RtkProcessor;
import org.rtklib.java.trace.TraceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Java RTK 与 C 原版 rnx2rtkp 同口径逐历元对照（只做诊断，不产出结论性断言）。
 *
 * <p>默认关闭。C 侧结果由 {@code rnx2rtkp -k confB.txt} 生成（与 {@link #createOpt()} 参数一一对应）：
 * posmode=static, freq=l1+l2, elmask=15, iono=brdc, trop=est, navsys=GPS+GLO+GAL,
 * armode=fix-and-hold, gloarmode=autocal, arthres=3.0/0.9999, slipthres=0.15, postype=single。</p>
 *
 * <pre>
 * mvn test -pl rtklib-test -Dtest=CJavaEpochDiffTest -Dcjdiff=true
 * 可选 -Dcjdiff.cpos=... 指定 C 的 .pos 路径
 * </pre>
 */
@DisplayName("Java vs C 逐历元对照诊断")
class CJavaEpochDiffTest {

    private static final Logger log = LoggerFactory.getLogger(CJavaEpochDiffTest.class);

    private static final String PROP_ENABLE = "cjdiff";
    private static final String PROP_CPOS = "cjdiff.cpos";

    private static final String ROOT = TestDataConfig.getTestDataDir()
            + File.separator + "gnss-validation" + File.separator + "rtk";
    private static final String SHORT_DIR = ROOT + File.separator + "short-baseline" + File.separator + "hkws-hkss";
    private static final String ROVER = SHORT_DIR + File.separator + "HKWS00HKG_R_20240600000_01D_30S_MO.rnx";
    private static final String BASE = SHORT_DIR + File.separator + "HKSS00HKG_R_20240600000_01D_30S_MO.rnx";
    private static final String NAV = ROOT + File.separator + "brdc" + File.separator
            + "BRDC00IGS_R_20240600000_01D_MN.rnx";
    private static final String ATX = TestDataConfig.getTestDataDir()
            + File.separator + "gnss-validation" + File.separator + "table" + File.separator + "igs14.atx";

    @Test
    void diff() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean(PROP_ENABLE), "默认关闭，设置 -D" + PROP_ENABLE + "=true 启用");
        Assumptions.assumeTrue(new File(ROVER).exists() && new File(BASE).exists() && new File(NAV).exists(),
                "短基线数据不可用");

        Path cPos = Paths.get(System.getProperty(PROP_CPOS, ".tmp" + File.separator + "cbin" + File.separator + "B.pos"));
        Assumptions.assumeTrue(Files.exists(cPos), "C 侧 .pos 不存在: " + cPos);

        List<String[]> cList = readCPos(cPos);
        log.info("C 侧历元数: {}", cList.size());

        RtkConfig cfg = new RtkConfig();
        cfg.rtk.enableSnrMedian = false;

        RinexRtkProcessor rtk = new RinexRtkProcessor(createOpt(), createSolOpt(), cfg, null, null);
        if (new File(ATX).exists()) {
            rtk.setAtxPath(ATX);
        }
        if (Boolean.getBoolean("cjdiff.trace")) {
            TraceConfig tc = new TraceConfig();
            tc.enabled = true;
            tc.topics.add("AR");
            tc.actions = new java.util.LinkedHashSet<>(List.of("FIX", "FAIL"));
            Path tp = Paths.get(".tmp", "cbin", "java_ar.trace");
            Files.createDirectories(tp.getParent());
            BufferedWriter tw = Files.newBufferedWriter(tp, StandardCharsets.UTF_8);
            rtk.setTraceConfig(tc);
            rtk.setTraceCallback(line -> {
                try {
                    tw.write(line);
                    tw.write('\n');
                    tw.flush();
                } catch (IOException ignored) {
                }
            });
        }
        RtkProcessor.RtkResult result = rtk.process(ROVER, BASE, NAV);
        List<SolData> sols = result.solutions;

        Path out = Paths.get(".tmp", "cbin", "java_epochs.csv");
        Files.createDirectories(out.getParent());

        int matched = 0, missC = 0;
        int[] jq = new int[8];
        int[] cq = new int[8];
        double sumDe = 0, sumDn = 0, sumDu = 0;
        double maxAbs = 0;
        String maxEpoch = "";
        int bothFix = 0, jFixCNot = 0, cFixJNot = 0;
        double sumFixDe = 0, sumFixDn = 0, sumFixDu = 0;
        int bigDiff = 0;

        StringBuilder sb = new StringBuilder(
                "idx,javaTime,javaQ,javaNs,javaRatio,cTime,cQ,cNs,cRatio,dE,dN,dU,absHoriz\n");
        try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
            if (!sols.isEmpty()) {
                log.info("Java 首历元 timeStr={} timeUtc={}", sols.get(0).timeStr, sols.get(0).timeUtc);
            }
            if (!cList.isEmpty()) {
                log.info("C    首历元 timeStr={}", cList.get(0)[0]);
            }
            for (int idx = 0; idx < sols.size(); idx++) {
                SolData s = sols.get(idx);
                String[] c = idx < cList.size() ? cList.get(idx) : new String[0];
                boolean hasC = c.length >= 7;
                Position p = s.getPosition(CoordType.ECEF);
                int jstatus = s.status == SolutionStatus.FIX ? 1 : (s.status == SolutionStatus.FLOAT ? 2 : 5);
                jq[Math.min(jstatus, 7)]++;
                if (!hasC) {
                    missC++;
                    sb.append(String.format(Locale.ROOT, "%d,%s,%d,%d,%.3f,NA,NA,NA,NA,NA,NA,NA,NA%n",
                            idx, s.timeStr, jstatus, s.numSat, s.ratio));
                    continue;
                }
                matched++;
                int cstat = (int) Double.parseDouble(c[1]);
                double cx = Double.parseDouble(c[2]);
                double cy = Double.parseDouble(c[3]);
                double cz = Double.parseDouble(c[4]);
                int cns = (int) Double.parseDouble(c[5]);
                double cratio = Double.parseDouble(c[6]);
                cq[Math.min(cstat, 7)]++;
                double de = 0, dn = 0, du = 0, horiz = 0;
                if (p != null) {
                    double[] dr = {p.v1 - cx, p.v2 - cy, p.v3 - cz};
                    double[] pos = new double[3];
                    CoordTransform.ecef2pos(cx == 0 ? new double[]{p.v1, p.v2, p.v3} : new double[]{cx, cy, cz}, pos);
                    double[] enu = new double[3];
                    CoordTransform.ecef2enu(pos, dr, enu);
                    de = enu[0];
                    dn = enu[1];
                    du = enu[2];
                    horiz = Math.sqrt(de * de + dn * dn);
                    sumDe += de;
                    sumDn += dn;
                    sumDu += du;
                    if (Math.abs(de) > maxAbs) {
                        maxAbs = Math.abs(de);
                        maxEpoch = String.valueOf(idx);
                    }
                    if (horiz > 0.1) {
                        bigDiff++;
                    }
                    if (jstatus == 1 && cstat == 1) {
                        bothFix++;
                        sumFixDe += de;
                        sumFixDn += dn;
                        sumFixDu += du;
                    } else if (jstatus == 1) {
                        jFixCNot++;
                    } else if (cstat == 1) {
                        cFixJNot++;
                    }
                }
                if (idx < 20) {
                    log.info("ep{} java(Q={} ns={} ratio={}) C(Q={} ns={} ratio={}) dE/dN/dU={}/{}/{}",
                            idx, jstatus, s.numSat, String.format(Locale.ROOT, "%.2f", s.ratio),
                            cstat, cns, String.format(Locale.ROOT, "%.1f", cratio),
                            String.format(Locale.ROOT, "%.3f", de),
                            String.format(Locale.ROOT, "%.3f", dn),
                            String.format(Locale.ROOT, "%.3f", du));
                }
                sb.append(String.format(Locale.ROOT,
                        "%d,%s,%d,%d,%.3f,%s,%d,%d,%.1f,%.4f,%.4f,%.4f,%.4f%n",
                        idx, s.timeStr, jstatus, s.numSat, s.ratio, c[0], cstat, cns, cratio, de, dn, du, horiz));
            }
            w.write(sb.toString());
        }

        log.info("Java 历元总数 {}，与 C 对齐 {}，C 缺 {}，逐历元明细已写: {}", sols.size(), matched, missC, out);
        log.info("Java Q 分布 fix/float/other = {}/{}/{}", jq[1], jq[2], sols.size() - jq[1] - jq[2]);
        log.info("C    Q 分布 fix/float/other = {}/{}/{}", cq[1], cq[2], matched - cq[1] - cq[2]);
        log.info("同为 FIX {}，Java-FIX 而 C 非 FIX {}，C-FIX 而 Java 非 FIX {}", bothFix, jFixCNot, cFixJNot);
        log.info("全部对齐历元 平均差 dE/dN/dU = {}/{}/{} m，水平差>0.1m 的历元数 = {}",
                String.format(Locale.ROOT, "%.4f", sumDe / Math.max(1, matched)),
                String.format(Locale.ROOT, "%.4f", sumDn / Math.max(1, matched)),
                String.format(Locale.ROOT, "%.4f", sumDu / Math.max(1, matched)), bigDiff);
        if (bothFix > 0) {
            log.info("同为 FIX 历元 平均差 dE/dN/dU = {}/{}/{} m",
                    String.format(Locale.ROOT, "%.4f", sumFixDe / bothFix),
                    String.format(Locale.ROOT, "%.4f", sumFixDn / bothFix),
                    String.format(Locale.ROOT, "%.4f", sumFixDu / bothFix));
        }
        log.info("最大单历元 |dE| = {} m @ {}", String.format(Locale.ROOT, "%.3f", maxAbs), maxEpoch);
    }

    private static List<String[]> readCPos(Path path) throws Exception {
        List<String[]> list = new ArrayList<>();
        for (String line : Files.readAllLines(path, StandardCharsets.UTF_8)) {
            if (line.startsWith("%") || line.isBlank()) {
                continue;
            }
            String[] t = line.trim().split("\\s+");
            if (t.length < 15) {
                continue;
            }
            list.add(new String[]{t[0] + " " + t[1], t[5], t[2], t[3], t[4], t[6], t[14]});
        }
        return list;
    }

    private static PrcOpt createOpt() {
        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_STATIC;
        opt.soltype = Constants.SOLTYPE_FORWARD;
        opt.nf = 2;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL;
        opt.elmin = 15.0 * Constants.D2R;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.tropopt = Constants.TROPOPT_EST;
        opt.modear = Constants.ARMODE_FIXHOLD;
        opt.refpos = Constants.POSOPT_SINGLE;
        opt.anttype[0] = "*";
        opt.anttype[1] = "*";
        return opt;
    }

    private static SolOpt createSolOpt() {
        SolOpt solOpt = new SolOpt();
        solOpt.solstatic = 0;
        return solOpt;
    }
}