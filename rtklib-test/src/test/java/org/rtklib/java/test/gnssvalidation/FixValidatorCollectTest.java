package org.rtklib.java.test.gnssvalidation;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.TestDataConfig;
import org.rtklib.java.ai.collect.CollectConfig;
import org.rtklib.java.ai.collect.CollectSession;
import org.rtklib.java.ai.collect.ObsCorrectorCollectSession;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.PrcOpt;
import org.rtklib.java.data.SolOpt;
import org.rtklib.java.data.Sta;
import org.rtklib.java.rinex.RinexParser;
import org.rtklib.java.rinex.RinexRtkProcessor;
import org.rtklib.java.trace.TraceConfig;
import org.rtklib.java.trace.TraceCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 接口一真实数据采集：多站 × 多日，逐站选最近邻基站，产出采集 CSV + AR/FIX trace + 真值表。
 * <p>
 * 默认关闭（避免常规 {@code mvn test} 触发下载与长时间解算）。启用方式：
 * </p>
 * <pre>
 * mvn test -pl rtklib-test -Dtest=FixValidatorCollectTest -Dfixvalidator.collect=true
 * </pre>
 * <p>
 * 可覆盖属性：
 * </p>
 * <ul>
 *   <li>{@code fixvalidator.year}（默认 2024）</li>
 *   <li>{@code fixvalidator.doys}（默认 {@code 060,061}）</li>
 *   <li>{@code fixvalidator.stations}（默认 6 站，逗号分隔）</li>
 *   <li>{@code fixvalidator.max-base-km}（默认 60，超过则跳过该站当日）</li>
 *   <li>{@code fixvalidator.glo-mode}（{@code off/on/fixhold/autocal}，默认 {@code autocal}）</li>
 *   <li>{@code fixvalidator.igg}（默认 {@code false}）</li>
 *   <li>{@code fixvalidator.niter}（默认 1）</li>
 *   <li>{@code fixvalidator.run-suffix}（默认空，便于同一 (站, 日) 跑多套配置）</li>
 * </ul>
 * <p>
 * 真值口径：RINEX 头 {@code APPROX POSITION XYZ} 与香港地政总署 SatRef 官方坐标表
 * （ITRF96 @ 1998.121）逐位一致，即测站参考点；叠加 {@code ANTENNA: DELTA H/E/N}
 * 后转为天线相位中心，与 {@code PostPosProcessor#posFromSta} 完全同口径。
 * 头坐标仅用于选最近邻基站与距离上限判断。真值表落在 {@code data/} 下，不入库。
 * </p>
 * <p>
 * 幂等：某 (站, 日) 的 CSV 与 trace 均已存在时直接跳过，可断点续采。
 * </p>
 */
@DisplayName("接口一真实数据采集（多站多日）")
class FixValidatorCollectTest {

    private static final Logger log = LoggerFactory.getLogger(FixValidatorCollectTest.class);

    private static final String PROP_ENABLE = "fixvalidator.collect";
    private static final String PROP_ENABLE_OBS = "obs-corrector.collect";
    private static final String PROP_YEAR = "fixvalidator.year";
    private static final String PROP_DOYS = "fixvalidator.doys";
    private static final String PROP_STATIONS = "fixvalidator.stations";
    private static final String PROP_MAX_BASE_KM = "fixvalidator.max-base-km";
    private static final String PROP_NAV = "fixvalidator.nav";
    private static final String PROP_RUN_SUFFIX = "fixvalidator.run-suffix";
    private static final String PROP_GLO_MODE = "fixvalidator.glo-mode";
    private static final String PROP_IGG = "fixvalidator.igg";
    private static final String PROP_NITER = "fixvalidator.niter";

    private static final String DEFAULT_STATIONS = "HKWS,HKSS,HKSL,HKST,HKPC,HKLM";
    private static final String DEFAULT_DOYS = "060,061";

    @Test
    void collect() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean(PROP_ENABLE),
                "采集默认关闭，设置 -D" + PROP_ENABLE + "=true 启用");

        int year = Integer.parseInt(System.getProperty(PROP_YEAR, "2024"));
        List<String> doys = splitList(System.getProperty(PROP_DOYS, DEFAULT_DOYS));
        List<String> stations = splitList(System.getProperty(PROP_STATIONS, DEFAULT_STATIONS));
        double maxBaseKm = Double.parseDouble(System.getProperty(PROP_MAX_BASE_KM, "60"));
        String runSuffix = System.getProperty(PROP_RUN_SUFFIX, "").trim();

        Path repoRoot = Paths.get(TestDataConfig.getTestDataDir()).getParent();
        Path outDir = repoRoot.resolve("data").resolve("fix-validator");
        Path collectDir = outDir.resolve("collect");
        Path rinexRoot = outDir.resolve("rinex");
        Path truthPath = outDir.resolve("truth.csv");
        Files.createDirectories(collectDir);

        String atxPath = findAtx();
        GnssValidationDataHelper helper =
                new GnssValidationDataHelper(TestDataConfig.getTestDataDir());

        Map<String, double[]> truth = new LinkedHashMap<>();
        List<String> planned = new ArrayList<>();
        int skippedNoData = 0, skippedNoBase = 0, skippedExists = 0, ran = 0;

        for (String doy : doys) {
            Path rinexDir = rinexRoot.resolve(year + "-" + doy);
            Files.createDirectories(rinexDir);

            String navFile = resolveNav(helper, year, Integer.parseInt(doy), rinexDir);
            if (navFile == null) {
                log.warn("{} 广播星历不可用，跳过该日全部站点", doy);
                continue;
            }
            log.info("{} 使用广播星历: {}", doy, navFile);

            Map<String, String> obsByStation = new LinkedHashMap<>();
            Map<String, double[]> posByStation = new LinkedHashMap<>();
            for (String st : stations) {
                helper.ensureSatRefRinex(rinexDir.toString(), st, year, Integer.parseInt(doy), "30s");
                String obs = findObs(rinexDir, st);
                if (obs == null) {
                    log.warn("{} {} 观测文件不可用，跳过", st, doy);
                    skippedNoData++;
                    continue;
                }
                double[] pos = readApproxPos(obs);
                if (pos == null) {
                    log.warn("{} {} 头文件无可信近似坐标，跳过", st, doy);
                    skippedNoData++;
                    continue;
                }
                obsByStation.put(st, obs);
                posByStation.put(st, pos);
            }
            if (obsByStation.size() < 2) {
                log.warn("{} 可用站仅 {} 个，无法构造基线", doy, obsByStation.size());
                continue;
            }

            for (Map.Entry<String, String> e : obsByStation.entrySet()) {
                String rover = e.getKey();
                double[] roverPos = posByStation.get(rover);
                String base = nearestOther(rover, roverPos, posByStation);
                if (base == null) {
                    skippedNoBase++;
                    continue;
                }
                double baseKm = distanceKm(roverPos, posByStation.get(base));
                if (baseKm > maxBaseKm) {
                    log.info("{} {} 最近基站 {} 距离 {} km 超过上限 {} km，跳过",
                            rover, doy, base, String.format(Locale.ROOT, "%.1f", baseKm), maxBaseKm);
                    skippedNoBase++;
                    continue;
                }

                String day = year + "-" + doy;
                String runId = rover + "-" + day + "-" + base + runSuffix;
                CollectConfig cfg = new CollectConfig(collectDir, rover, day, runId, false,
                        CollectConfig.DEFAULT_FLUSH_EVERY);

                if (Files.exists(cfg.csvPath())) {
                    if (!Files.exists(cfg.tracePath())) {
                        log.warn("CSV 已存在但 trace 缺失，跳过且不覆盖: {}", cfg.csvPath().getFileName());
                    } else {
                        log.info("已采集，跳过: {}", cfg.csvPath().getFileName());
                    }
                    skippedExists++;
                    truth.put(rover, roverPos);
                    continue;
                }

                planned.add(String.format("%s/%s base=%s %.1fkm", rover, day, base, baseKm));
                collectOne(cfg, e.getValue(), obsByStation.get(base), navFile, atxPath, roverPos);
                truth.put(rover, roverPos);
                ran++;
            }
        }

        if (!truth.isEmpty()) {
            writeTruth(truthPath, truth);
            log.info("真值表已写出: {}（{} 站）", truthPath, truth.size());
        }
        log.info("采集完成: 计划 {}，实跑 {}，已存在跳过 {}，无数据 {}，无可用基站 {}",
                planned.size(), ran, skippedExists, skippedNoData, skippedNoBase);
        for (String p : planned) {
            log.info("  计划: {}", p);
        }
    }

    /**
     * 接口三采集：与接口一<b>同一站/同一日/同一基站配对</b>，只换采集器与 trace 主题。
     * <p>
     * 复用同一套 {@code createOpt()} / 选基站 / 读星历逻辑是刻意的：接口三的特征必须与
     * 接口一的基线逐位可比（A2 配对验收的前提就是两组解算除注入外完全一致），
     * 任何配置漂移都会让配对归因失去意义。
     * </p>
     * <p>
     * trace 主题为 {@code AI3}（{@code DDRES} / {@code DDRES_REJ} / {@code CORRECT}）加
     * {@code AR}（{@code FIX} / {@code FAIL}，仅用于给数据集填 {@code solution_stat}）。
     * {@code actions} 必须置 {@code null}：它是<b>跨主题的全局过滤器</b>，若沿用接口一的
     * {@code {FIX, FAIL}}，{@code DDRES} 会被一并滤掉，trace 里一行标签都不会剩下。
     * </p>
     * <p>
     * 启用方式：
     * </p>
     * <pre>
     * mvn test -pl rtklib-test -Dtest=FixValidatorCollectTest -Dobs-corrector.collect=true
     * </pre>
     */
    @Test
    void collectObsCorrector() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean(PROP_ENABLE_OBS),
                "接口三采集默认关闭，设置 -D" + PROP_ENABLE_OBS + "=true 启用");

        int year = Integer.parseInt(System.getProperty(PROP_YEAR, "2024"));
        List<String> doys = splitList(System.getProperty(PROP_DOYS, DEFAULT_DOYS));
        List<String> stations = splitList(System.getProperty(PROP_STATIONS, DEFAULT_STATIONS));
        double maxBaseKm = Double.parseDouble(System.getProperty(PROP_MAX_BASE_KM, "60"));
        String runSuffix = System.getProperty(PROP_RUN_SUFFIX, "-ai3").trim();

        Path repoRoot = Paths.get(TestDataConfig.getTestDataDir()).getParent();
        Path outDir = repoRoot.resolve("data").resolve("fix-validator");
        Path collectDir = outDir.resolve("collect-obs");
        Path rinexRoot = outDir.resolve("rinex");
        Files.createDirectories(collectDir);

        String atxPath = findAtx();
        GnssValidationDataHelper helper =
                new GnssValidationDataHelper(TestDataConfig.getTestDataDir());

        int skippedExists = 0, ran = 0, failed = 0;

        for (String doy : doys) {
            Path rinexDir = rinexRoot.resolve(year + "-" + doy);
            String navFile = resolveNav(helper, year, Integer.parseInt(doy), rinexDir);
            if (navFile == null) {
                log.warn("{} 广播星历不可用，跳过该日全部站点", doy);
                continue;
            }

            Map<String, String> obsByStation = new LinkedHashMap<>();
            Map<String, double[]> posByStation = new LinkedHashMap<>();
            for (String st : stations) {
                String obs = findObs(rinexDir, st);
                if (obs == null) {
                    log.warn("{} {} 观测文件不可用，跳过", st, doy);
                    continue;
                }
                double[] pos = readApproxPos(obs);
                if (pos == null) {
                    log.warn("{} {} 头文件无可信近似坐标，跳过", st, doy);
                    continue;
                }
                obsByStation.put(st, obs);
                posByStation.put(st, pos);
            }
            if (obsByStation.size() < 2) {
                log.warn("{} 可用站仅 {} 个，无法构造基线", doy, obsByStation.size());
                continue;
            }

            String day = year + "-" + doy;
            for (Map.Entry<String, String> e : obsByStation.entrySet()) {
                String rover = e.getKey();
                double[] roverPos = posByStation.get(rover);
                String base = nearestOther(rover, roverPos, posByStation);
                if (base == null) {
                    continue;
                }
                if (distanceKm(roverPos, posByStation.get(base)) > maxBaseKm) {
                    continue;
                }

                String runId = rover + "-" + day + "-" + base + runSuffix;
                CollectConfig cfg = new CollectConfig(collectDir, rover, day, runId, false,
                        CollectConfig.DEFAULT_FLUSH_EVERY);

                if (Files.exists(cfg.obsCsvPath()) && Files.exists(cfg.tracePath())) {
                    log.info("已采集，跳过: {}", cfg.obsCsvPath().getFileName());
                    skippedExists++;
                    continue;
                }

                try {
                    collectObsOne(cfg, e.getValue(), obsByStation.get(base), navFile, atxPath);
                    ran++;
                } catch (IOException ex) {
                    failed++;
                    log.error("接口三采集失败 {}: {}", cfg.runId(), ex.getMessage());
                }
            }
        }

        log.info("接口三采集完成: 实跑 {}，已存在跳过 {}，失败 {}", ran, skippedExists, failed);
    }

    private void collectObsOne(CollectConfig cfg, String roverObs, String baseObs,
                               String navFile, String atxPath) throws IOException {
        PrcOpt opt = createOpt();
        RtkConfig rtkConfig = createRtkConfig();

        SolOpt solOpt = new SolOpt();
        solOpt.solstatic = 0;

        TraceConfig traceCfg = new TraceConfig();
        traceCfg.enabled = true;
        traceCfg.topics.add("AI3");
        traceCfg.topics.add("AR");
        traceCfg.actions = null;

        try (ObsCorrectorCollectSession session = ObsCorrectorCollectSession.open(cfg);
             Writer traceOut = Files.newBufferedWriter(cfg.tracePath(), StandardCharsets.UTF_8,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            TraceCallback cb = line -> {
                try {
                    traceOut.write(line);
                    traceOut.write('\n');
                } catch (IOException ignored) {
                }
            };
            session.runWith(() -> {
                RinexRtkProcessor proc = new RinexRtkProcessor(opt, solOpt, rtkConfig, null, null);
                proc.setAtxPath(atxPath);
                proc.setTraceConfig(traceCfg);
                proc.setTraceCallback(cb);
                proc.process(roverObs, baseObs, navFile);
            });
        }
    }

    private void collectOne(CollectConfig cfg, String roverObs, String baseObs,
                            String navFile, String atxPath, double[] roverPos) throws IOException {
        PrcOpt opt = createOpt();
        RtkConfig rtkConfig = createRtkConfig();

        SolOpt solOpt = new SolOpt();
        solOpt.solstatic = 0;

        TraceConfig traceCfg = new TraceConfig();
        traceCfg.enabled = true;
        traceCfg.topics.add("AR");
        traceCfg.actions = new LinkedHashSet<>(List.of("FIX", "FAIL"));

        try (CollectSession session = CollectSession.open(cfg);
             Writer traceOut = Files.newBufferedWriter(cfg.tracePath(), StandardCharsets.UTF_8,
                     StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
            TraceCallback cb = line -> {
                try {
                    traceOut.write(line);
                    traceOut.write('\n');
                } catch (IOException ignored) {
                }
            };
            session.runWith(() -> {
                RinexRtkProcessor proc = new RinexRtkProcessor(opt, solOpt, rtkConfig, null, null);
                proc.setAtxPath(atxPath);
                proc.setTraceConfig(traceCfg);
                proc.setTraceCallback(cb);
                proc.process(roverObs, baseObs, navFile);
            });
        } catch (IOException e) {
            log.error("采集失败 {}: {}", cfg.runId(), e.getMessage());
        }
    }

    private static String resolveNav(GnssValidationDataHelper helper, int year, int doy,
                                     Path downloadDir) {
        String explicit = System.getProperty(PROP_NAV);
        if (explicit != null && !explicit.isBlank()) {
            return Files.exists(Paths.get(explicit)) ? explicit : null;
        }

        String suffix = String.format(Locale.ROOT, "_%d%03d0000_01D_MN.RNX", year, doy);
        List<Path> candidates = List.of(
                Paths.get(TestDataConfig.getTestDataDir()).resolve("gnss-validation")
                        .resolve("rtk").resolve("brdc"),
                Paths.get(TestDataConfig.getTestDataDir()).resolve("gnss-validation")
                        .resolve("ppp").resolve("algo"),
                Paths.get(TestDataConfig.getTestDataDir()).resolve("gnss-validation").resolve("brdc"));
        for (Path dir : candidates) {
            String found = scanNav(dir, suffix);
            if (found != null) {
                return found;
            }
        }

        try {
            Files.createDirectories(downloadDir);
            helper.ensureIgsRinex(downloadDir.toString(), "BRDC", year, doy);
        } catch (Exception e) {
            log.warn("BRDC 下载失败 {}: {}", doy, e.getMessage());
        }
        return scanNav(downloadDir, ".RNX");
    }

    private static String scanNav(Path dir, String suffix) {
        File d = dir.toFile();
        if (!d.isDirectory()) {
            return null;
        }
        File[] files = d.listFiles();
        if (files == null) {
            return null;
        }
        for (File f : files) {
            String n = f.getName().toUpperCase(Locale.ROOT);
            if (f.length() > 0 && n.contains("BRD") && n.endsWith(suffix)) {
                return f.getAbsolutePath();
            }
        }
        return null;
    }

    private static String findObs(Path dir, String station) {
        File d = dir.toFile();
        File[] files = d.listFiles();
        if (files == null) {
            return null;
        }
        String st = station.toUpperCase(Locale.ROOT);
        for (File f : files) {
            String n = f.getName().toUpperCase(Locale.ROOT);
            if (n.startsWith(st) && n.endsWith(".RNX") && f.length() > 0) {
                return f.getAbsolutePath();
            }
        }
        return null;
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
        opt.glomodear = gloMode();
        opt.niter = Integer.getInteger(PROP_NITER, 1);
        opt.refpos = Constants.POSOPT_RINEX;
        opt.anttype[0] = "*";
        opt.anttype[1] = "*";
        return opt;
    }

    private static RtkConfig createRtkConfig() {
        RtkConfig rtkConfig = new RtkConfig();
        rtkConfig.rtk.enableSnrMedian = true;
        rtkConfig.rtk.enableCascadeAR = true;
        rtkConfig.rtk.enableIggiii = Boolean.getBoolean(PROP_IGG);
        return rtkConfig;
    }

    private static int gloMode() {
        String v = System.getProperty(PROP_GLO_MODE, "autocal").trim().toLowerCase(Locale.ROOT);
        switch (v) {
            case "off": return Constants.GLO_ARMODE_OFF;
            case "on": return Constants.GLO_ARMODE_ON;
            case "fixhold": return Constants.GLO_ARMODE_FIXHOLD;
            case "autocal": return Constants.GLO_ARMODE_AUTOCAL;
            default: throw new IllegalArgumentException(
                    PROP_GLO_MODE + " 仅支持 off/on/fixhold/autocal: " + v);
        }
    }

    private static double[] readApproxPos(String obsFile) {
        try {
            RinexParser parser = new RinexParser();
            if (parser.parseObs(obsFile)) {
                Sta sta = parser.sta;
                if (sta == null || sta.pos == null
                        || (sta.pos[0] == 0.0 && sta.pos[1] == 0.0 && sta.pos[2] == 0.0)) {
                    return null;
                }
                return antennaPos(sta);
            }
        } catch (Exception e) {
            log.warn("读取 RINEX 近似坐标失败 {}: {}", obsFile, e.getMessage());
        }
        return null;
    }

    /**
     * 与 {@code PostPosProcessor#posFromSta} 保持同一口径：真值必须是天线相位中心坐标，
     * 否则 ANTENNA: DELTA H/E/N 会作为常量偏差进入「错误固定」判定。
     */
    private static double[] antennaPos(Sta sta) {
        double[] rr = sta.pos.clone();
        if (sta.del != null && (sta.del[0] != 0.0 || sta.del[1] != 0.0 || sta.del[2] != 0.0)) {
            double[] pos = new double[3];
            CoordTransform.ecef2pos(rr, pos);
            double[] delEnu = new double[]{sta.del[1], sta.del[0], sta.del[2] + sta.hgt};
            double[] dr = new double[3];
            CoordTransform.enu2ecef(pos, delEnu, dr);
            for (int i = 0; i < 3; i++) {
                rr[i] += dr[i];
            }
        }
        return rr;
    }

    private static String nearestOther(String rover, double[] roverPos,
                                       Map<String, double[]> posByStation) {
        String best = null;
        double bestKm = Double.MAX_VALUE;
        for (Map.Entry<String, double[]> e : posByStation.entrySet()) {
            if (e.getKey().equals(rover)) {
                continue;
            }
            double km = distanceKm(roverPos, e.getValue());
            if (km < bestKm) {
                bestKm = km;
                best = e.getKey();
            }
        }
        return best;
    }

    private static double distanceKm(double[] a, double[] b) {
        double dx = a[0] - b[0], dy = a[1] - b[1], dz = a[2] - b[2];
        return Math.sqrt(dx * dx + dy * dy + dz * dz) / 1000.0;
    }

    private static void writeTruth(Path path, Map<String, double[]> truth) throws IOException {
        Files.createDirectories(path.getParent());
        StringBuilder sb = new StringBuilder("station,ref_x,ref_y,ref_z\n");
        for (Map.Entry<String, double[]> e : truth.entrySet()) {
            double[] p = e.getValue();
            sb.append(e.getKey()).append(',')
              .append(String.format(Locale.ROOT, "%.4f", p[0])).append(',')
              .append(String.format(Locale.ROOT, "%.4f", p[1])).append(',')
              .append(String.format(Locale.ROOT, "%.4f", p[2])).append('\n');
        }
        Files.writeString(path, sb.toString(), StandardCharsets.UTF_8);
    }

    private static String findAtx() {
        Path p = Paths.get(TestDataConfig.getTestDataDir())
                .resolve("gnss-validation").resolve("table").resolve("igs14.atx");
        return Files.exists(p) ? p.toString() : null;
    }

    private static List<String> splitList(String s) {
        List<String> out = new ArrayList<>();
        for (String t : s.split(",")) {
            String v = t.trim();
            if (!v.isEmpty()) {
                out.add(v);
            }
        }
        return out;
    }
}