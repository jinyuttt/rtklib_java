package org.rtklib.java.test.gnssvalidation;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.TestDataConfig;
import org.rtklib.java.ai.OnnxAiProvider;
import org.rtklib.java.ai.ObsCorrectorImpl;
import org.rtklib.java.ai.spi.AiHooks;
import org.rtklib.java.ai.spi.AiProvider;
import org.rtklib.java.ai.spi.FixValidator;
import org.rtklib.java.ai.spi.ObsCorrector;
import org.rtklib.java.config.AiOpts;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.data.CoordType;
import org.rtklib.java.data.GTime;
import org.rtklib.java.data.Position;
import org.rtklib.java.data.PrcOpt;
import org.rtklib.java.data.SolData;
import org.rtklib.java.data.SolOpt;
import org.rtklib.java.data.SolutionStatus;
import org.rtklib.java.rinex.RinexRtkProcessor;
import org.rtklib.java.rtkpos.RtkProcessor;
import org.rtklib.java.time.TimeSystem;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 双接口协同四跑配对：<b>A（不注入）/ B（仅接口一）/ C（仅接口三）/ D（接口一+接口三）</b>。
 *
 * <p>
 * 四组对<b>同一批输入</b>各跑一遍，唯一差异是 {@link AiHooks#install} 注入的
 * {@link AiProvider} 内容：
 * </p>
 * <ul>
 *   <li><b>A</b>：不注入，原生基线；同时是参考坐标的唯一来源；</li>
 *   <li><b>B</b>：只提供 {@link FixValidator}（{@code fix_validator.onnx}），
 *       接口三返回 {@code null}；</li>
 *   <li><b>C</b>：只提供 {@link ObsCorrector}（{@code obs_corrector_e2e.onnx}），
 *       接口一返回 {@code null}；</li>
 *   <li><b>D</b>：两个接口同时提供。</li>
 * </ul>
 *
 * <p>
 * 站/日/小时/基站/星历/AR 配置/天线改正全部复用
 * {@link FixValidatorHourlyCollectTest#createOpt()}，不在此处复制解算参数。
 * 接口一置信度阈值由 {@code pairing.fix-threshold} 传入（默认取运行时
 * {@link AiOpts#DEFAULT_FIX_VALIDATOR_MIN_CONFIDENCE}），跑批时应显式给出模型标定值。
 * </p>
 *
 * <p>
 * 产物（{@code pairing.out} 下，默认 {@code data/fix-validator/pairing-dual}，已被 .gitignore 忽略）：
 * </p>
 * <ul>
 *   <li>{@code pairing.{A,B,C,D}.sol.csv}：逐历元解（stat/ns/ECEF/ratio）；</li>
 *   <li>{@code pairing.rejects.csv}：B/D 组的 {@code AI_REJECT} 逐历元记录
 *       （nb/ratio/confidence/threshold）；</li>
 *   <li>{@code _pairing_runs.csv}：每次运行四组的行数与拦截数，供断点续跑与覆盖率核对
 *       （四组原子完成，{@code status=ok} 即四组都已落盘）。</li>
 * </ul>
 *
 * <p>
 * 指标与归因由 {@code training/obs_corrector/dual_interface_pairing.py} 读取上述 CSV 完成，
 * 本类只负责「跑」与「落原始配对数据」，不在 Java 侧重复统计口径。
 * </p>
 *
 * <pre>
 * mvn test -pl rtklib-test -Dtest=DualInterfacePairingTest \
 *   -Dpairing.enable=true \
 *   -Dpairing.out=data/fix-validator/pairing-dual \
 *   -Dpairing.fix-threshold=0.08 \
 *   -Dhourlyrtk.rinex-root=&lt;rinex-root&gt; -Dhourlyrtk.base=XXXX \
 *   -Dhourlyrtk.rovers=X1,X2 -Dhourlyrtk.days=2026-06-06
 * </pre>
 */
@DisplayName("双接口协同四跑配对：A（原生）/ B（仅接口一）/ C（仅接口三）/ D（双接口）")
class DualInterfacePairingTest {

    private static final Logger log = LoggerFactory.getLogger(DualInterfacePairingTest.class);

    private static final String PROP_ENABLE = "pairing.enable";
    private static final String PROP_OUT = "pairing.out";
    private static final String PROP_FIX_MODEL = "pairing.fix-model";
    private static final String PROP_FIX_NORM = "pairing.fix-norm";
    private static final String PROP_OBS_MODEL = "pairing.obs-model";
    private static final String PROP_OBS_NORM = "pairing.obs-norm";
    private static final String PROP_FIX_THRESHOLD = "pairing.fix-threshold";

    private static final String P_RINEX_ROOT = "hourlyrtk.rinex-root";
    private static final String P_BASE = "hourlyrtk.base";
    private static final String P_ROVERS = "hourlyrtk.rovers";
    private static final String P_DAYS = "hourlyrtk.days";
    private static final String P_HOURS = "hourlyrtk.hours";

    private static final String GROUP_A = "A";
    private static final String GROUP_B = "B";
    private static final String GROUP_C = "C";
    private static final String GROUP_D = "D";

    private static final String SOL_HEADER =
            "station,day,hour,group,gpst,stat,ns,ratio,x,y,z";
    private static final String REJ_HEADER =
            "station,day,hour,group,epoch,gpst,nb,ratio,confidence,threshold";
    private static final String RUN_HEADER =
            "station,day,hour,total_epochs,"
                    + "sol_a,fix_a,sol_b,fix_b,sol_c,fix_c,sol_d,fix_d,"
                    + "rej_b,rej_d,status";

    @Test
    void pair() throws Exception {
        Assumptions.assumeTrue(Boolean.getBoolean(PROP_ENABLE),
                "配对默认关闭，设置 -D" + PROP_ENABLE + "=true 启用");

        Path rinexRoot = FixValidatorHourlyCollectTest.requiredPath(P_RINEX_ROOT);
        String base = FixValidatorHourlyCollectTest.required(P_BASE);
        List<String> rovers = FixValidatorHourlyCollectTest.splitList(
                FixValidatorHourlyCollectTest.required(P_ROVERS));
        List<String> days = FixValidatorHourlyCollectTest.splitList(
                FixValidatorHourlyCollectTest.required(P_DAYS));
        String hoursProp = System.getProperty(P_HOURS, "").trim();
        String atxPath = FixValidatorHourlyCollectTest.resolveAtx();

        Path outDir = resolveOutDir();
        Files.createDirectories(outDir);

        Path fixModel = resolveRepoPath(PROP_FIX_MODEL,
                "data", "fix-validator", "out", "fix_validator.onnx");
        Path fixNorm = resolveRepoPath(PROP_FIX_NORM,
                "data", "fix-validator", "out", "fix_validator.norm.json");
        Path obsModel = resolveRepoPath(PROP_OBS_MODEL,
                "rtklib-ai", "training", "out", "obs_corrector_e2e.onnx");
        Path obsNorm = resolveRepoPath(PROP_OBS_NORM,
                "rtklib-ai", "training", "out", "obs_corrector_e2e.norm.json");
        Assumptions.assumeTrue(Files.isRegularFile(fixModel), "接口一模型不存在: " + fixModel);
        Assumptions.assumeTrue(Files.isRegularFile(fixNorm), "接口一归一化参数不存在: " + fixNorm);
        Assumptions.assumeTrue(Files.isRegularFile(obsModel), "接口三模型不存在: " + obsModel);
        Assumptions.assumeTrue(Files.isRegularFile(obsNorm), "接口三归一化参数不存在: " + obsNorm);

        double fixThreshold = Double.parseDouble(System.getProperty(
                PROP_FIX_THRESHOLD, String.valueOf(AiOpts.DEFAULT_FIX_VALIDATOR_MIN_CONFIDENCE)));

        OnnxAiProvider fixOnly = new OnnxAiProvider(fixModel, fixNorm);
        OnnxAiProvider obsOnly = new OnnxAiProvider(null, null, obsModel, obsNorm);
        OnnxAiProvider both = new OnnxAiProvider(fixModel, fixNorm, obsModel, obsNorm);
        Assumptions.assumeTrue(fixOnly.isFixValidatorAvailable(),
                "接口一模型加载失败: " + fixModel);
        Assumptions.assumeTrue(obsOnly.isObsCorrectorAvailable(),
                "接口三模型加载失败: " + obsModel);
        Assumptions.assumeTrue(both.isFixValidatorAvailable() && both.isObsCorrectorAvailable(),
                "双接口模型加载失败: " + fixModel + " / " + obsModel);

        log.info("配对输出目录: {}  接口一: {} (阈值 {})  接口三: {}  基准站: {}  流动站: {}  观测日: {}",
                outDir, fixModel, fixThreshold, obsModel, base, rovers, days);

        Set<String> done = loadDoneRuns(outDir);
        Csv solA = new Csv(outDir.resolve("pairing.A.sol.csv"), SOL_HEADER);
        Csv solB = new Csv(outDir.resolve("pairing.B.sol.csv"), SOL_HEADER);
        Csv solC = new Csv(outDir.resolve("pairing.C.sol.csv"), SOL_HEADER);
        Csv solD = new Csv(outDir.resolve("pairing.D.sol.csv"), SOL_HEADER);
        Csv rejects = new Csv(outDir.resolve("pairing.rejects.csv"), REJ_HEADER);
        Csv runs = new Csv(outDir.resolve("_pairing_runs.csv"), RUN_HEADER);

        int ran = 0, skippedMissing = 0, skippedDone = 0;
        try {
            for (String day : days) {
                List<Integer> hours = hoursProp.isEmpty()
                        ? FixValidatorHourlyCollectTest.listHours(rinexRoot, base, day)
                        : FixValidatorHourlyCollectTest.parseInts(
                                FixValidatorHourlyCollectTest.splitList(hoursProp));
                if (hours.isEmpty()) {
                    log.warn("{} 基准站 {} 无小时文件，跳过", day, base);
                    continue;
                }
                for (String rover : rovers) {
                    for (int hour : hours) {
                        String key = rover + "|" + day + "|" + hour;
                        if (done.contains(key)) {
                            skippedDone++;
                            continue;
                        }
                        String roverObs = FixValidatorHourlyCollectTest.hourlyFile(
                                rinexRoot, rover, day, hour, ".obs");
                        String baseObs = FixValidatorHourlyCollectTest.hourlyFile(
                                rinexRoot, base, day, hour, ".obs");
                        if (roverObs == null || baseObs == null) {
                            skippedMissing++;
                            continue;
                        }
                        String nav = FixValidatorHourlyCollectTest.hourlyFile(
                                rinexRoot, rover, day, hour, ".nav");
                        if (nav == null) {
                            nav = FixValidatorHourlyCollectTest.hourlyFile(
                                    rinexRoot, base, day, hour, ".nav");
                        }
                        if (nav == null) {
                            log.warn("{} {} h{} 无星历，跳过", rover, day, hour);
                            skippedMissing++;
                            continue;
                        }

                        int[] a = runOne(GROUP_A, rover, day, hour, roverObs, baseObs, nav,
                                atxPath, null, null, fixThreshold, solA, rejects);
                        int[] b = runOne(GROUP_B, rover, day, hour, roverObs, baseObs, nav,
                                atxPath, fixOnly.fixValidator(), null, fixThreshold, solB, rejects);
                        int[] c = runOne(GROUP_C, rover, day, hour, roverObs, baseObs, nav,
                                atxPath, null, obsOnly.obsCorrector(), fixThreshold, solC, rejects);
                        int[] d = runOne(GROUP_D, rover, day, hour, roverObs, baseObs, nav,
                                atxPath, both.fixValidator(), both.obsCorrector(),
                                fixThreshold, solD, rejects);

                        runs.write(join(rover, day, String.valueOf(hour),
                                String.valueOf(a[0]),
                                String.valueOf(a[1]), String.valueOf(a[2]),
                                String.valueOf(b[1]), String.valueOf(b[2]),
                                String.valueOf(c[1]), String.valueOf(c[2]),
                                String.valueOf(d[1]), String.valueOf(d[2]),
                                String.valueOf(b[3]), String.valueOf(d[3]),
                                "ok"));
                        ran++;
                        if (ran % 20 == 0) {
                            log.info("四跑进度: 已完成 {} 次 ×4 组（已存在跳过 {}，缺文件 {}）"
                                            + " 本 run 固定解 A/B/C/D={}/{}/{}/{} 拦截 B/D={}/{}",
                                    ran, skippedDone, skippedMissing,
                                    a[2], b[2], c[2], d[2], b[3], d[3]);
                            solA.flush();
                            solB.flush();
                            solC.flush();
                            solD.flush();
                            rejects.flush();
                            runs.flush();
                        }
                    }
                }
            }
        } finally {
            AiHooks.clear();
            solA.close();
            solB.close();
            solC.close();
            solD.close();
            rejects.close();
            runs.close();
            fixOnly.close();
            obsOnly.close();
            both.close();
        }
        log.info("四跑配对完成: 实跑 {} 次，已存在跳过 {}，缺文件 {}",
                ran, skippedDone, skippedMissing);
    }

    /**
     * 单次运行（一个 站 × 日 × 小时 × 组）。
     *
     * @param fv        该组注入的接口一实现；{@code null} = 不提供接口一
     * @param oc        该组注入的接口三实现；{@code null} = 不提供接口三
     * @param threshold 接口一置信度阈值（写入 {@link RtkConfig#ai}）
     * @return {@code [totalEpochs, solRows, fixRows, rejectRows]}
     */
    private int[] runOne(String group, String rover, String day, int hour,
                         String roverObs, String baseObs, String nav, String atxPath,
                         FixValidator fv, ObsCorrector oc, double threshold,
                         Csv solSink, Csv rejectSink) throws IOException {
        PrcOpt opt = FixValidatorHourlyCollectTest.createOpt();
        SolOpt solOpt = new SolOpt();
        solOpt.solstatic = 0;

        RtkConfig rtkConfig = new RtkConfig();
        rtkConfig.ai.fixValidatorMinConfidence = threshold;

        TraceConfig traceCfg = new TraceConfig();
        traceCfg.enabled = true;
        traceCfg.topics.add("AR");
        traceCfg.actions = null;

        int[] counters = new int[4];
        RejectCollector sink = new RejectCollector(rover, day, hour, group, counters);

        if (fv == null && oc == null) {
            AiHooks.clear();
        } else {
            if (oc != null) {
                resetCorrector(oc);
            }
            AiHooks.install(new GroupProvider(fv, oc));
        }
        RtkProcessor.RtkResult result;
        try {
            RinexRtkProcessor proc = new RinexRtkProcessor(opt, solOpt,
                    rtkConfig, null, null);
            if (atxPath != null) {
                proc.setAtxPath(atxPath);
            }
            proc.setTraceConfig(traceCfg);
            proc.setTraceCallback(sink::onLine);
            result = proc.process(roverObs, baseObs, nav);
        } catch (RuntimeException e) {
            log.error("四跑运行失败 {} {} h{} {}: {}", rover, day, hour, group, e.toString());
            AiHooks.clear();
            throw e;
        } finally {
            AiHooks.clear();
        }

        counters[0] = result.totalEpochs;
        sink.flushTo(rejectSink);
        for (SolData s : result.solutions) {
            double[] ecef = ecefOf(s);
            if (ecef == null) {
                continue;
            }
            solSink.write(join(rover, day, String.valueOf(hour), group,
                    gpstKey(s.time), String.valueOf(s.status.code),
                    String.valueOf(s.numSat), fmt(s.ratio),
                    fmt(ecef[0]), fmt(ecef[1]), fmt(ecef[2])));
            counters[1]++;
            if (s.status == SolutionStatus.FIX) {
                counters[2]++;
            }
        }
        return counters;
    }

    private static double[] ecefOf(SolData s) {
        for (Position p : s.positions) {
            if (p.type == CoordType.ECEF) {
                return new double[]{p.v1, p.v2, p.v3};
            }
        }
        return null;
    }

    private static String gpstKey(GTime t) {
        if (t == null || t.time == 0) {
            return "0.000";
        }
        return String.format(Locale.ROOT, "%.3f", TimeSystem.time2gpst(t, null));
    }

    private static String fmt(double v) {
        return String.format(Locale.ROOT, "%.6f", v);
    }

    private static String join(String... parts) {
        StringBuilder sb = new StringBuilder(parts.length * 12);
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(parts[i]);
        }
        return sb.toString();
    }

    /** 清空接口三的短窗状态（实现类各自提供 {@code reset()}）。 */
    private static void resetCorrector(ObsCorrector oc) {
        if (oc instanceof ObsCorrectorImpl impl) {
            impl.reset();
        }
    }

    /** 按组提供接口一/接口三；任一为 {@code null} 即该接口不启用。 */
    private static final class GroupProvider implements AiProvider {
        private final FixValidator fv;
        private final ObsCorrector oc;

        GroupProvider(FixValidator fv, ObsCorrector oc) {
            this.fv = fv;
            this.oc = oc;
        }

        @Override
        public FixValidator fixValidator() {
            return fv;
        }

        @Override
        public ObsCorrector obsCorrector() {
            return oc;
        }
    }

    /**
     * {@code AR/AI_REJECT} 的行级收集（仅接口一拒收时 emit）。
     * <p>
     * 与 {@code Ai3Collector} 同样「先缓存后落盘」：trace 回调异常会被
     * {@code Trace.emit} 静默吞掉，直接在回调里写文件会丢行且无报错。
     * </p>
     */
    private static final class RejectCollector {
        private final String station;
        private final String day;
        private final int hour;
        private final String group;
        private final int[] counters;
        private final List<String> rows = new ArrayList<>();

        RejectCollector(String station, String day, int hour, String group, int[] counters) {
            this.station = station;
            this.day = day;
            this.hour = hour;
            this.group = group;
            this.counters = counters;
        }

        void onLine(String line) {
            if (!"AI_REJECT".equals(value(line, "action"))) {
                return;
            }
            String epoch = value(line, "epoch");
            String gpst = value(line, "gpst");
            String nb = value(line, "nb");
            String ratio = value(line, "ratio");
            String confidence = value(line, "confidence");
            String threshold = value(line, "threshold");
            if (epoch == null || gpst == null || confidence == null) {
                return;
            }
            rows.add(join(station, day, String.valueOf(hour), group,
                    epoch, gpst,
                    nb == null ? "" : nb,
                    ratio == null ? "" : ratio,
                    confidence,
                    threshold == null ? "" : threshold));
            counters[3]++;
        }

        void flushTo(Csv sink) throws IOException {
            for (String row : rows) {
                sink.write(row);
            }
            rows.clear();
        }

        private static String value(String line, String key) {
            String needle = "|" + key + "=";
            int at = line.indexOf(needle);
            if (at < 0) {
                return null;
            }
            int from = at + needle.length();
            int to = line.indexOf('|', from);
            return to < 0 ? line.substring(from) : line.substring(from, to);
        }
    }

    private static Set<String> loadDoneRuns(Path outDir) throws IOException {
        Set<String> done = new HashSet<>();
        Path manifest = outDir.resolve("_pairing_runs.csv");
        if (!Files.isRegularFile(manifest)) {
            return done;
        }
        for (String line : Files.readAllLines(manifest, StandardCharsets.UTF_8)) {
            if (line.startsWith("station,") || line.isBlank()) {
                continue;
            }
            String[] f = line.split(",");
            if (f.length >= 3 && "ok".equals(f[f.length - 1])) {
                done.add(f[0] + "|" + f[1] + "|" + f[2]);
            }
        }
        return done;
    }

    private static Path resolveOutDir() {
        String explicit = System.getProperty(PROP_OUT);
        if (explicit != null && !explicit.isBlank()) {
            return Paths.get(explicit);
        }
        Path repoRoot = Paths.get(TestDataConfig.getTestDataDir()).getParent();
        return repoRoot.resolve("data").resolve("fix-validator").resolve("pairing-dual");
    }

    private static Path resolveRepoPath(String key, String... defaults) {
        String explicit = System.getProperty(key);
        if (explicit != null && !explicit.isBlank()) {
            return Paths.get(explicit);
        }
        Path repoRoot = Paths.get(TestDataConfig.getTestDataDir()).getParent();
        Path p = repoRoot;
        for (String d : defaults) {
            p = p.resolve(d);
        }
        return p;
    }

    /** 追加式 CSV 落盘：断点续跑时不重复写表头。 */
    private static final class Csv {
        private final Writer writer;
        private final String header;
        private boolean headerWritten;

        Csv(Path path, String header) throws IOException {
            this.header = header;
            this.writer = Files.newBufferedWriter(path, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            this.headerWritten = Files.size(path) > 0;
        }

        void write(String line) throws IOException {
            if (!headerWritten) {
                writer.write(header);
                writer.write('\n');
                headerWritten = true;
            }
            writer.write(line);
            writer.write('\n');
        }

        void flush() throws IOException {
            writer.flush();
        }

        void close() throws IOException {
            writer.close();
        }
    }
}