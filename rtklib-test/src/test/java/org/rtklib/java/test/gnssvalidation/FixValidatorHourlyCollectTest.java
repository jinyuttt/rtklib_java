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
import org.rtklib.java.data.PrcOpt;
import org.rtklib.java.data.SolOpt;
import org.rtklib.java.rinex.RinexRtkProcessor;
import org.rtklib.java.trace.TraceCallback;
import org.rtklib.java.trace.TraceConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

/**
 * 接口一采集：<b>按小时切片</b>的「基准站 + 多流动站」双站数据。
 * <p>
 * 与 {@link FixValidatorCollectTest} 的区别只有三点，其余口径完全一致：
 * </p>
 * <ol>
 *   <li>输入不是单日整文件，而是 {@code <rinex-root>/<station>/<yyyy-MM-dd>/<hour>.obs|.nav}。
 *       每个小时文件独立成一次运行（滤波器逐小时重启），因此 runId 用 {@code h07} 这类小时号，
 *       与既有 {@code _h7_*.pos} 的切片口径一一对应。</li>
 *   <li>不写 {@code truth.csv}。本数据集为现场设备，RINEX 头 {@code APPROX POSITION XYZ}
 *       与实测固定解相差数十米（未扣天线高/粗解），不能当真值。真值改由
 *       {@code assemble.py --truth auto} 从多日固定解紧致聚类估计，误差在厘米级。</li>
 *   <li>输出目录独立（{@code collect-hourly}），避免与香港 SatRef 数据集的 CSV 混在一起。</li>
 * </ol>
 * <p>
 * <b>保密约束</b>：本类不含任何站号、坐标、站址常量，全部由系统属性传入；
 * 采集产物落在 {@code data/} 下（已被 .gitignore 忽略），不入库。
 * </p>
 * <pre>
 * mvn test -pl rtklib-test -Dtest=FixValidatorHourlyCollectTest \
 *   -Dhourlyrtk.enable=true \
 *   -Dhourlyrtk.rinex-root=<rinex-root> \
 *   -Dhourlyrtk.base=XXXX -Dhourlyrtk.rovers=X1,X2 -Dhourlyrtk.days=2026-06-18 \
 *   -Dhourlyrtk.hours=7
 * </pre>
 * 可用属性见类内 {@code PROP_*} 常量；缺省值均为「不跑任何东西」的安全值。
 * <p>
 * 幂等：某 (站, 日, 小时) 的 CSV 与 trace 均已存在时跳过，可断点续采。
 * </p>
 */
@DisplayName("接口一采集：按小时切片的双站数据")
class FixValidatorHourlyCollectTest {

    private static final Logger log = LoggerFactory.getLogger(FixValidatorHourlyCollectTest.class);

    private static final String PROP_ENABLE = "hourlyrtk.enable";
    private static final String PROP_RINEX_ROOT = "hourlyrtk.rinex-root";
    private static final String PROP_BASE = "hourlyrtk.base";
    private static final String PROP_ROVERS = "hourlyrtk.rovers";
    private static final String PROP_DAYS = "hourlyrtk.days";
    private static final String PROP_HOURS = "hourlyrtk.hours";
    private static final String PROP_OUT = "hourlyrtk.out";
    private static final String PROP_ATX = "hourlyrtk.atx";

    private static final String PROP_OBS_ENABLE = "hourlyrtk.obs.enable";
    private static final String PROP_OBS_OUT = "hourlyrtk.obs.out";
    private static final String PROP_COMMIT = "hourlyrtk.commit";
    private static final String PROP_WORKTREE = "hourlyrtk.worktree";

    private static final String PROP_NAVSYS = "hourlyrtk.navsys";
    private static final String PROP_NF = "hourlyrtk.nf";
    private static final String PROP_ELMIN = "hourlyrtk.elmin";
    private static final String PROP_IONO = "hourlyrtk.ionoopt";
    private static final String PROP_TROP = "hourlyrtk.tropopt";
    private static final String PROP_ARMODE = "hourlyrtk.armode";
    private static final String PROP_ARTHRES = "hourlyrtk.arthres";
    private static final String PROP_ARLOCKCNT = "hourlyrtk.arlockcnt";
    private static final String PROP_ARMINFIX = "hourlyrtk.arminfix";
    private static final String PROP_AROUTCNT = "hourlyrtk.aroutcnt";
    private static final String PROP_SLIPTHRES = "hourlyrtk.slipthres";
    private static final String PROP_ERRPHASE = "hourlyrtk.errphase";

    @Test
    void collect() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean(PROP_ENABLE),
                "采集默认关闭，设置 -D" + PROP_ENABLE + "=true 启用");

        Path rinexRoot = requiredPath(PROP_RINEX_ROOT);
        String base = required(PROP_BASE);
        List<String> rovers = splitList(required(PROP_ROVERS));
        List<String> days = splitList(required(PROP_DAYS));
        String hoursProp = System.getProperty(PROP_HOURS, "").trim();

        Path outDir = resolveOutDir();
        Path collectDir = outDir.resolve("collect-hourly");
        Files.createDirectories(collectDir);

        String atxPath = resolveAtx();
        log.info("采集目录: {}  基准站: {}  流动站: {}  观测日: {}  星历表: {}",
                collectDir, base, rovers, days, atxPath);

        collectAll(rinexRoot, base, rovers, days, hoursProp, collectDir, atxPath, false);
    }

    @Test
    @DisplayName("接口三采集：按小时切片的 17 维特征 + AI3 标签")
    void collectObs() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean(PROP_OBS_ENABLE),
                "接口三采集默认关闭，设置 -D" + PROP_OBS_ENABLE + "=true 启用");

        Path rinexRoot = requiredPath(PROP_RINEX_ROOT);
        String base = required(PROP_BASE);
        List<String> rovers = splitList(required(PROP_ROVERS));
        List<String> days = splitList(required(PROP_DAYS));
        String hoursProp = System.getProperty(PROP_HOURS, "").trim();

        Path outDir = resolveOutDir();
        String explicit = System.getProperty(PROP_OBS_OUT, "").trim();
        Path collectDir = explicit.isEmpty()
                ? outDir.resolve("collect-obs-hourly")
                : Paths.get(explicit);
        Files.createDirectories(collectDir);

        String atxPath = resolveAtx();
        log.info("接口三采集目录: {}  基准站: {}  流动站: {}  观测日: {}  星历表: {}",
                collectDir, base, rovers, days, atxPath);

        collectAll(rinexRoot, base, rovers, days, hoursProp, collectDir, atxPath, true);
    }

    /**
     * 两种采集共用的遍历骨架。
     * <p>
     * 站/日/小时/基站的配对规则、缺文件跳过、幂等跳过必须逐位一致：接口三的 A2 判据是
     * 「C 组 vs A 组<b>同历元配对</b>」，两组若连输入集合都不同，配对统计就失去意义。
     * 故此处只用 {@code obsMode} 一处分叉，差异仅限采集器与 trace 主题，不复制遍历逻辑。
     * </p>
     */
    private void collectAll(Path rinexRoot, String base, List<String> rovers, List<String> days,
                            String hoursProp, Path collectDir, String atxPath, boolean obsMode)
            throws IOException {
        int planned = 0, ran = 0, skippedExists = 0, skippedMissing = 0;
        for (String day : days) {
            List<Integer> hours = hoursProp.isEmpty()
                    ? listHours(rinexRoot, base, day)
                    : parseInts(splitList(hoursProp));
            if (hours.isEmpty()) {
                log.warn("{} 基准站 {} 无小时文件，跳过", day, base);
                continue;
            }
            for (String rover : rovers) {
                for (int hour : hours) {
                    String roverObs = hourlyFile(rinexRoot, rover, day, hour, ".obs");
                    String baseObs = hourlyFile(rinexRoot, base, day, hour, ".obs");
                    if (roverObs == null || baseObs == null) {
                        skippedMissing++;
                        continue;
                    }
                    String nav = hourlyFile(rinexRoot, rover, day, hour, ".nav");
                    if (nav == null) {
                        nav = hourlyFile(rinexRoot, base, day, hour, ".nav");
                    }
                    if (nav == null) {
                        log.warn("{} {} h{} 无星历，跳过", rover, day, hour);
                        skippedMissing++;
                        continue;
                    }

                    String runId = String.format(Locale.ROOT, "h%02d", hour);
                    CollectConfig cfg = new CollectConfig(collectDir, rover, day, runId, false,
                            CollectConfig.DEFAULT_FLUSH_EVERY);

                    if (obsMode
                            ? Files.exists(cfg.obsCsvPath()) && Files.exists(cfg.tracePath())
                            : Files.exists(cfg.csvPath()) && Files.exists(cfg.tracePath())) {
                        skippedExists++;
                        continue;
                    }
                    planned++;
                    if (obsMode) {
                        collectObsOne(cfg, roverObs, baseObs, nav, atxPath, base);
                    } else {
                        collectOne(cfg, roverObs, baseObs, nav, atxPath);
                    }
                    ran++;
                    if (ran % 20 == 0) {
                        log.info("进度: 已采集 {} 次（跳过已存在 {}，缺文件 {}）",
                                ran, skippedExists, skippedMissing);
                    }
                }
            }
        }
        log.info("采集完成: 计划 {}，实跑 {}，已存在跳过 {}，缺文件 {}",
                planned, ran, skippedExists, skippedMissing);
    }

    private void collectOne(CollectConfig cfg, String roverObs, String baseObs,
                            String navFile, String atxPath) throws IOException {
        PrcOpt opt = createOpt();
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
                RinexRtkProcessor proc = new RinexRtkProcessor(opt, solOpt,
                        new RtkConfig(), null, null);
                if (atxPath != null) {
                    proc.setAtxPath(atxPath);
                }
                proc.setTraceConfig(traceCfg);
                proc.setTraceCallback(cb);
                proc.process(roverObs, baseObs, navFile);
            });
        } catch (IOException e) {
            log.error("采集失败 {}: {}", cfg.runId(), e.getMessage());
        }
    }

    /**
     * 接口三单次运行采集：17 维特征落 {@code .obs.csv}，标签落 trace 的 {@code AI3/DDRES}。
     * <p>
     * 与 {@link #collectOne} 的差异只有两处，其余（{@link #createOpt()}、站/日/基站配对、
     * RINEX 输入、{@link RtkConfig}）逐位相同——这是 A2 配对验收的前提：
     * 两组解算若有一处口径分叉，配对出的指标就不再是同源对照。
     * </p>
     * <ul>
     *   <li>采集器换成 {@link ObsCorrectorCollectSession}：一历元 × 一卫星 × 一频点一行；</li>
     *   <li>trace 主题加 {@code AI3}：标签是未扣偏差的双差码残差（{@code before}），
     *       {@code AR} 仍保留，供组装阶段写入 {@code solution_stat}。</li>
     * </ul>
     */
    private void collectObsOne(CollectConfig cfg, String roverObs, String baseObs,
                               String navFile, String atxPath, String baseStation)
            throws IOException {
        PrcOpt opt = createOpt();
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
                RinexRtkProcessor proc = new RinexRtkProcessor(opt, solOpt,
                        new RtkConfig(), null, null);
                if (atxPath != null) {
                    proc.setAtxPath(atxPath);
                }
                proc.setTraceConfig(traceCfg);
                proc.setTraceCallback(cb);
                proc.process(roverObs, baseObs, navFile);
            });
        } catch (IOException e) {
            log.error("接口三采集失败 {}: {}", cfg.runId(), e.getMessage());
        }
        writeConfigSnapshot(cfg, opt, baseStation);
    }

    private static void writeConfigSnapshot(CollectConfig cfg, PrcOpt opt, String baseStation) {
        Path p = cfg.outputDir().resolve(
                cfg.station() + "_" + cfg.day() + "_" + cfg.runId() + ".config.json");
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\n");
        jsonKv(sb, "station", cfg.station(), true);
        jsonKv(sb, "day", cfg.day(), true);
        jsonKv(sb, "run_id", cfg.runId(), true);
        jsonKv(sb, "base_station", baseStation, true);
        jsonNum(sb, "nf", opt.nf, true);
        jsonNum(sb, "navsys", opt.navsys, true);
        jsonKv(sb, "navsys_names", navsysNames(opt.navsys), true);
        jsonNum(sb, "ionoopt", opt.ionoopt, true);
        jsonNum(sb, "armode", opt.modear, true);
        jsonNum(sb, "elmin_deg", Math.toDegrees(opt.elmin), true);
        jsonKv(sb, "thresar", thresarJson(opt.thresar), true);
        jsonKv(sb, "commit", System.getProperty(PROP_COMMIT, "unknown"), true);
        jsonKv(sb, "worktree", System.getProperty(PROP_WORKTREE, "unknown"), true);
        jsonKv(sb, "collected_at", Instant.now().toString(), false);
        sb.append("}\n");
        try {
            Files.writeString(p, sb.toString(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log.error("配置快照写盘失败 {}: {}", p, e.getMessage());
        }
    }

    private static void jsonKv(StringBuilder sb, String key, String value, boolean more) {
        sb.append("  \"").append(key).append("\": \"").append(escapeJson(value)).append('"');
        sb.append(more ? ",\n" : "\n");
    }

    private static void jsonNum(StringBuilder sb, String key, double value, boolean more) {
        sb.append("  \"").append(key).append("\": ")
                .append(Double.isFinite(value) ? Double.toString(value) : "null");
        sb.append(more ? ",\n" : "\n");
    }

    private static String navsysNames(int sys) {
        List<String> names = new ArrayList<>();
        if ((sys & Constants.SYS_GPS) != 0) names.add("GPS");
        if ((sys & Constants.SYS_GLO) != 0) names.add("GLO");
        if ((sys & Constants.SYS_GAL) != 0) names.add("GAL");
        if ((sys & Constants.SYS_QZS) != 0) names.add("QZS");
        if ((sys & Constants.SYS_CMP) != 0) names.add("BDS");
        if ((sys & Constants.SYS_IRN) != 0) names.add("IRN");
        if ((sys & Constants.SYS_SBS) != 0) names.add("SBS");
        return String.join("|", names);
    }

    private static String thresarJson(double[] thresar) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < thresar.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(Double.isFinite(thresar[i]) ? Double.toString(thresar[i]) : "null");
        }
        return sb.append(']').toString();
    }

    private static String escapeJson(String v) {
        return v == null ? "" : v.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * 逐小时解算配置（包级可见，供 {@link ObsCorrectorPairingTest} 复用）。
     * <p>
     * <b>不得复制</b>：A2 配对要求 A/C 两组解算口径逐位一致，只允许「是否注入 AI」一处不同。
     * 一旦这里分叉（例如某组换了 {@code armode} 或 {@code thresar}），配对出的固定率差
     * 就不再是同源对照，而是两组不同解算器的差。
     * </p>
     */
    static PrcOpt createOpt() {
        PrcOpt opt = new PrcOpt();
        opt.mode = Constants.PMODE_STATIC;
        opt.soltype = Constants.SOLTYPE_FORWARD;
        opt.nf = Integer.getInteger(PROP_NF, 2);
        opt.navsys = parseNavsys(System.getProperty(PROP_NAVSYS, "cmp"));
        opt.elmin = Double.parseDouble(System.getProperty(PROP_ELMIN, "15")) * Constants.D2R;
        opt.ionoopt = parseIono(System.getProperty(PROP_IONO, "brdc"));
        opt.tropopt = parseTrop(System.getProperty(PROP_TROP, "saas"));
        opt.modear = parseArmode(System.getProperty(PROP_ARMODE, "fixhold"));
        opt.glomodear = Constants.GLO_ARMODE_OFF;
        opt.thresar[0] = Double.parseDouble(System.getProperty(PROP_ARTHRES, "4.0"));
        opt.minlock = Integer.getInteger(PROP_ARLOCKCNT, 20);
        opt.minfix = Integer.getInteger(PROP_ARMINFIX, 20);
        opt.maxout = Integer.getInteger(PROP_AROUTCNT, 10);
        opt.thresslip = Double.parseDouble(System.getProperty(PROP_SLIPTHRES, "0.05"));
        double errphase = Double.parseDouble(System.getProperty(PROP_ERRPHASE, "0.003"));
        opt.err[1] = errphase;
        opt.err[2] = errphase;
        opt.err[3] = 0.0;
        opt.refpos = Constants.POSOPT_RINEX;
        opt.rovpos = Constants.POSOPT_RINEX;
        opt.anttype[0] = "*";
        opt.anttype[1] = "*";
        return opt;
    }

    private static int parseNavsys(String spec) {
        int sys = 0;
        for (String t : splitList(spec)) {
            switch (t.toLowerCase(Locale.ROOT)) {
                case "gps": sys |= Constants.SYS_GPS; break;
                case "glo": sys |= Constants.SYS_GLO; break;
                case "gal": sys |= Constants.SYS_GAL; break;
                case "cmp": case "bds": sys |= Constants.SYS_CMP; break;
                case "qzs": sys |= Constants.SYS_QZS; break;
                case "irn": sys |= Constants.SYS_IRN; break;
                case "sbs": sys |= Constants.SYS_SBS; break;
                default: throw new IllegalArgumentException(PROP_NAVSYS + " 不支持: " + t);
            }
        }
        if (sys == 0) {
            throw new IllegalArgumentException(PROP_NAVSYS + " 为空");
        }
        return sys;
    }

    private static int parseIono(String v) {
        switch (v.toLowerCase(Locale.ROOT)) {
            case "off": return Constants.IONOOPT_OFF;
            case "brdc": return Constants.IONOOPT_BRDC;
            case "iflc": return Constants.IONOOPT_IFLC;
            case "est": return Constants.IONOOPT_EST;
            default: throw new IllegalArgumentException(PROP_IONO + " 不支持: " + v);
        }
    }

    private static int parseTrop(String v) {
        switch (v.toLowerCase(Locale.ROOT)) {
            case "off": return Constants.TROPOPT_OFF;
            case "saas": return Constants.TROPOPT_SAAS;
            case "sbas": return Constants.TROPOPT_SBAS;
            case "est": return Constants.TROPOPT_EST;
            case "estg": return Constants.TROPOPT_ESTG;
            default: throw new IllegalArgumentException(PROP_TROP + " 不支持: " + v);
        }
    }

    private static int parseArmode(String v) {
        switch (v.toLowerCase(Locale.ROOT)) {
            case "off": return Constants.ARMODE_OFF;
            case "continuous": case "cont": return Constants.ARMODE_CONT;
            case "instant": case "inst": return Constants.ARMODE_INST;
            case "fixhold": case "fix-and-hold": return Constants.ARMODE_FIXHOLD;
            default: throw new IllegalArgumentException(PROP_ARMODE + " 不支持: " + v);
        }
    }

    private static Path resolveOutDir() {
        String explicit = System.getProperty(PROP_OUT);
        if (explicit != null && !explicit.isBlank()) {
            return Paths.get(explicit);
        }
        Path repoRoot = Paths.get(TestDataConfig.getTestDataDir()).getParent();
        return repoRoot.resolve("data").resolve("fix-validator");
    }

    static String resolveAtx() {
        String explicit = System.getProperty(PROP_ATX);
        if (explicit != null && !explicit.isBlank()) {
            return Files.exists(Paths.get(explicit)) ? explicit : null;
        }
        Path p = Paths.get(TestDataConfig.getTestDataDir())
                .resolve("gnss-validation").resolve("table").resolve("igs14.atx");
        return Files.exists(p) ? p.toString() : null;
    }

    static Path requiredPath(String key) {
        Path p = Paths.get(required(key));
        Assumptions.assumeTrue(Files.isDirectory(p), key + " 不是目录: " + p);
        return p;
    }

    static String required(String key) {
        String v = System.getProperty(key, "").trim();
        Assumptions.assumeTrue(!v.isEmpty(), "必须设置 -D" + key);
        return v;
    }

    static String hourlyFile(Path root, String station, String day, int hour,
                             String ext) {
        Path p = root.resolve(station).resolve(day).resolve(hour + ext);
        return Files.exists(p) && p.toFile().length() > 0 ? p.toString() : null;
    }

    static List<Integer> listHours(Path root, String station, String day) {
        Map<Integer, Integer> found = new TreeMap<>();
        Path dir = root.resolve(station).resolve(day);
        if (!Files.isDirectory(dir)) {
            return new ArrayList<>();
        }
        try (var stream = Files.newDirectoryStream(dir, "*.obs")) {
            for (Path p : stream) {
                String name = p.getFileName().toString();
                String stem = name.substring(0, name.length() - 4);
                try {
                    int h = Integer.parseInt(stem);
                    if (h >= 0 && h <= 23) {
                        found.put(h, h);
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        } catch (IOException e) {
            log.warn("列出小时文件失败 {}: {}", dir, e.getMessage());
        }
        return new ArrayList<>(found.keySet());
    }

    static List<Integer> parseInts(List<String> tokens) {
        List<Integer> out = new ArrayList<>();
        for (String t : tokens) {
            int h = Integer.parseInt(t);
            if (h < 0 || h > 23) {
                throw new IllegalArgumentException(PROP_HOURS + " 取值须在 0..23: " + t);
            }
            out.add(h);
        }
        return out;
    }

    static List<String> splitList(String s) {
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