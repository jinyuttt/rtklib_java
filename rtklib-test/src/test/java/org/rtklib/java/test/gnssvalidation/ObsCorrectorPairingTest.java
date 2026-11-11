package org.rtklib.java.test.gnssvalidation;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.TestDataConfig;
import org.rtklib.java.ai.OnnxAiProvider;
import org.rtklib.java.ai.ObsCorrectorImpl;
import org.rtklib.java.ai.spi.AiHooks;
import org.rtklib.java.ai.spi.AiProvider;
import org.rtklib.java.ai.spi.CodeCorrection;
import org.rtklib.java.ai.spi.FixValidator;
import org.rtklib.java.ai.spi.ObsCorrectionContext;
import org.rtklib.java.ai.spi.ObsCorrector;
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
 * 接口三 A2/A3 三跑配对：<b>A 组（原生，不注入） vs C 组（bias+scale 全开） vs
 * D 组（强制 {@code biasMeters≡0}，仅保留 {@code varianceScale}）</b>。
 *
 * <p>
 * 这是 §7.5.2 的落地：A2 的每一条都要求「同历元配对」，不是跨数据集比两个比例。
 * 因此本类对<b>同一批输入</b>跑三遍，唯一差异是 {@link AiHooks#install} 注入的
 * {@link ObsCorrector} 实现：C 组注入原样输出，D 组注入「偏差置零」装饰器；
 * 站/日/小时/基站/星历/AR 配置/天线改正全部复用
 * {@link FixValidatorHourlyCollectTest#createOpt()}，不在此处复制解算参数。
 * </p>
 *
 * <p>
 * <b>为什么要有 D 组</b>：C 组把两个头的效果混在一起，指标一旦变化无法归因。
 * D 组把 {@code biasMeters} 强制置 0，只剩 {@code varianceScale}（单侧方差膨胀）——
 * 于是「C 组相对 A 组的变化」可分解为「D 组（尺度头）」+「C 组相对 D 组（偏差头）」。
 * D 组同时也是注入正确性的反证：它的 trace 里 {@code after} 必须恒等于
 * {@code before}，否则说明 bias 并未真正置零。
 * </p>
 *
 * <p>
 * 产物（{@code data/fix-validator/pairing} 下，已被 .gitignore 忽略）：
 * </p>
 * <ul>
 *   <li>{@code pairing.A.sol.csv} / {@code pairing.C.sol.csv} / {@code pairing.D.sol.csv}：
 *       逐历元解（stat/ns/ECEF/ratio）；</li>
 *   <li>{@code pairing.A.ai3.csv} / {@code pairing.C.ai3.csv} / {@code pairing.D.ai3.csv}：
 *       逐 (历元, 星, 频点) 的 {@code before} / {@code after} / {@code Ri} / {@code Rj}
 *       （§7.5.3 的六元组，{@code Ri}/{@code Rj} 取方差膨胀前的原生值）；</li>
 *   <li>{@code _pairing_runs.csv}：每次运行的 {@code total_epochs} 与计数，供固定率分母
 *       与断点续跑使用（三组原子完成，{@code status=ok} 即三组都已落盘）。</li>
 * </ul>
 * <p>
 * 指标计算（A2-1~A2-4、A3 真实复核）由 {@code training/obs_corrector/a2_pairing.py} 读取上述
 * CSV 完成，本类只负责「跑」与「落原始配对数据」，不在 Java 侧重复统计口径。
 * </p>
 * <p>
 * <b>保密约束</b>：本类不含任何站号、坐标、站址常量，站/日/基准站与切分（含哪两个站是
 * 未见测试站）全部由系统属性或训练产物 {@code obs_corrector.metrics.json} 传入。
 * </p>
 * <pre>
 * mvn test -pl rtklib-test -Dtest=ObsCorrectorPairingTest \
 *   -Dpairing.enable=true \
 *   -Dhourlyrtk.rinex-root=<rinex-root> -Dhourlyrtk.base=XXXX \
 *   -Dhourlyrtk.rovers=X1,X2 -Dhourlyrtk.days=2026-06-06 \
 *   -Dhourlyrtk.nf=2 -Dhourlyrtk.navsys=cmp -Dhourlyrtk.armode=fixhold
 * </pre>
 */
@DisplayName("接口三 A2/A3 三跑配对：A（原生）/ C（bias+scale）/ D（仅 scale）")
class ObsCorrectorPairingTest {

    private static final Logger log = LoggerFactory.getLogger(ObsCorrectorPairingTest.class);

    private static final String PROP_ENABLE = "pairing.enable";
    private static final String PROP_OUT = "pairing.out";
    private static final String PROP_MODEL = "pairing.model";
    private static final String PROP_NORM = "pairing.norm";

    private static final String P_RINEX_ROOT = "hourlyrtk.rinex-root";
    private static final String P_BASE = "hourlyrtk.base";
    private static final String P_ROVERS = "hourlyrtk.rovers";
    private static final String P_DAYS = "hourlyrtk.days";
    private static final String P_HOURS = "hourlyrtk.hours";

    private static final String GROUP_A = "A";
    private static final String GROUP_C = "C";
    /** D 组：强制 {@code biasMeters≡0}，仅保留 {@code varianceScale}。 */
    private static final String GROUP_D = "D";

    private static final String SOL_HEADER =
            "station,day,hour,group,gpst,stat,ns,ratio,x,y,z";
    private static final String AI3_HEADER =
            "station,day,hour,group,epoch,gpst,sat,frq,before,after,ri,rj,rej";
    private static final String RUN_HEADER =
            "station,day,hour,total_epochs,sol_rows,fix_rows,ai3_rows,rej_rows,status";

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

        Path model = resolveRepoPath(PROP_MODEL, "rtklib-ai", "training", "out", "obs_corrector.onnx");
        Path norm = resolveRepoPath(PROP_NORM, "rtklib-ai", "training", "out", "obs_corrector.norm.json");
        Assumptions.assumeTrue(Files.isRegularFile(model), "接口三模型不存在: " + model);
        Assumptions.assumeTrue(Files.isRegularFile(norm), "接口三归一化参数不存在: " + norm);

        OnnxAiProvider provider = new OnnxAiProvider(null, null, model, norm);
        Assumptions.assumeTrue(provider.isObsCorrectorAvailable(),
                "接口三模型加载失败（provider 等价于未注入）: " + model);

        log.info("配对输出目录: {}  模型: {}  基准站: {}  流动站: {}  观测日: {}",
                outDir, model, base, rovers, days);

        Set<String> done = loadDoneRuns(outDir);
        Csv solA = new Csv(outDir.resolve("pairing.A.sol.csv"), SOL_HEADER);
        Csv solC = new Csv(outDir.resolve("pairing.C.sol.csv"), SOL_HEADER);
        Csv solD = new Csv(outDir.resolve("pairing.D.sol.csv"), SOL_HEADER);
        Csv ai3A = new Csv(outDir.resolve("pairing.A.ai3.csv"), AI3_HEADER);
        Csv ai3C = new Csv(outDir.resolve("pairing.C.ai3.csv"), AI3_HEADER);
        Csv ai3D = new Csv(outDir.resolve("pairing.D.ai3.csv"), AI3_HEADER);
        Csv runs = new Csv(outDir.resolve("_pairing_runs.csv"), RUN_HEADER);

        /* C 组注入原样输出；D 组注入「偏差置零」装饰器（同一个 ObsCorrectorImpl 实例，
         * 共享短窗状态维护逻辑，只是输出被改写，故两组窗口演化逐位一致）。 */
        ObsCorrector ocC = provider.obsCorrector();
        ObsCorrector ocD = new ScaleOnlyCorrector(ocC);

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
                                atxPath, null, solA, ai3A);
                        int[] c = runOne(GROUP_C, rover, day, hour, roverObs, baseObs, nav,
                                atxPath, ocC, solC, ai3C);
                        int[] d = runOne(GROUP_D, rover, day, hour, roverObs, baseObs, nav,
                                atxPath, ocD, solD, ai3D);

                        runs.write(join(rover, day, String.valueOf(hour),
                                String.valueOf(a[0]), String.valueOf(a[1]),
                                String.valueOf(a[2]), String.valueOf(a[3]),
                                String.valueOf(a[4]), "ok"));
                        ran++;
                        if (ran % 20 == 0) {
                            log.info("配对进度: 已完成 {} 次 ×3 组（已存在跳过 {}，缺文件 {}）"
                                    + " 最近 C 组 ns 行 {} / D 组 ns 行 {}",
                                    ran, skippedDone, skippedMissing, c[3], d[3]);
                            solA.flush();
                            solC.flush();
                            solD.flush();
                            ai3A.flush();
                            ai3C.flush();
                            ai3D.flush();
                            runs.flush();
                        }
                    }
                }
            }
        } finally {
            AiHooks.clear();
            solA.close();
            solC.close();
            solD.close();
            ai3A.close();
            ai3C.close();
            ai3D.close();
            runs.close();
            provider.close();
        }
        log.info("配对完成: 实跑 {} 次，已存在跳过 {}，缺文件 {}",
                ran, skippedDone, skippedMissing);
    }

    /**
     * 单次运行（一个 站 × 日 × 小时 × 组）。
     *
     * @param injector 该组注入的接口三实现；{@code null} = 不注入（A 组基线）
     * @return {@code [totalEpochs, solRows, fixRows, ai3Rows, rejRows]}
     */
    private int[] runOne(String group, String rover, String day, int hour,
                         String roverObs, String baseObs, String nav, String atxPath,
                         ObsCorrector injector,
                         Csv solSink, Csv ai3Sink) throws IOException {
        PrcOpt opt = FixValidatorHourlyCollectTest.createOpt();
        SolOpt solOpt = new SolOpt();
        solOpt.solstatic = 0;

        TraceConfig traceCfg = new TraceConfig();
        traceCfg.enabled = true;
        traceCfg.topics.add("AI3");
        traceCfg.actions = null;

        int[] counters = new int[5];
        Ai3Collector sink = new Ai3Collector(rover, day, hour, group, counters);

        if (injector != null) {
            resetCorrector(injector);
            AiHooks.install(new SingleCorrectorProvider(injector));
        } else {
            AiHooks.clear();
        }
        RtkProcessor.RtkResult result;
        try {
            RinexRtkProcessor proc = new RinexRtkProcessor(opt, solOpt,
                    new RtkConfig(), null, null);
            if (atxPath != null) {
                proc.setAtxPath(atxPath);
            }
            proc.setTraceConfig(traceCfg);
            proc.setTraceCallback(sink::onLine);
            result = proc.process(roverObs, baseObs, nav);
        } catch (RuntimeException e) {
            log.error("配对运行失败 {} {} h{} {}: {}", rover, day, hour, group, e.toString());
            AiHooks.clear();
            throw e;
        } finally {
            AiHooks.clear();
        }

        counters[0] = result.totalEpochs;
        sink.flushTo(ai3Sink);
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

    /**
     * D 组装饰器：把接口三输出的 {@code biasMeters} 全部置 0，只透传 {@code varianceScale}。
     * <p>
     * 与「换一个模型」不同，本类<b>不改变</b>被包装实现的推理与短窗演化：同一个
     * {@link ObsCorrectorImpl} 实例先跑 C 组、再跑 D 组，两组看到的窗口历史逐位一致，
     * 唯一差别是 core 拿到的偏差量——这样「C 相对 D 的差异」才能干净地归因到偏差头。
     * </p>
     * <p>
     * {@code varianceScale} 为 {@code null} 时返回 {@link CodeCorrection#NONE}（降级为
     * 不干预），与 core 侧对降级的判定一致。
     * </p>
     */
    private static final class ScaleOnlyCorrector implements ObsCorrector {
        private final ObsCorrector delegate;

        ScaleOnlyCorrector(ObsCorrector delegate) {
            if (delegate == null) {
                throw new IllegalArgumentException("被包装的 ObsCorrector 不得为 null");
            }
            this.delegate = delegate;
        }

        @Override
        public CodeCorrection correct(ObsCorrectionContext ctx) {
            CodeCorrection cc = delegate.correct(ctx);
            if (cc == null || cc.varianceScale() == null) {
                return CodeCorrection.NONE;
            }
            double[] scale = cc.varianceScale();
            return new CodeCorrection(new double[scale.length], scale);
        }

        void reset() {
            resetCorrector(delegate);
        }
    }

    /** 清空接口三的短窗状态（实现类各自提供 {@code reset()}）。 */
    private static void resetCorrector(ObsCorrector oc) {
        if (oc instanceof ObsCorrectorImpl impl) {
            impl.reset();
        } else if (oc instanceof ScaleOnlyCorrector scaleOnly) {
            scaleOnly.reset();
        }
    }

    /**
     * 只提供接口三的 {@link AiProvider}：接口一返回 {@code null}（本类不评估接口一）。
     * <p>
     * 与直接 {@code AiHooks.install(provider)} 的区别仅在于接口三可以是任意实现
     * （C 组原样、D 组置零），从而三组共用同一套注入路径。
     * </p>
     */
    private static final class SingleCorrectorProvider implements AiProvider {
        private final ObsCorrector obs;

        SingleCorrectorProvider(ObsCorrector obs) {
            this.obs = obs;
        }

        @Override
        public FixValidator fixValidator() {
            return null;
        }

        @Override
        public ObsCorrector obsCorrector() {
            return obs;
        }
    }

    /**
     * {@code AI3/DDRES} 与 {@code AI3/DDRES_REJ} 的行级收集。
     * <p>
     * <b>先缓存后落盘</b>：{@code Trace.emit} 对回调异常是 {@code catch (Exception ignored)}
     * （trace 不得影响解算），若在回调里直接写文件，磁盘写失败会被静默吞掉，长时间跑的
     * 配对数据会缺行而无任何报错。故回调只做解析入表，{@link #flushTo} 在 {@code process()}
     * 返回后才写，IO 失败可正常抛出。
     * </p>
     * <p>
     * {@code DDRES_REJ} 只用于 A2-4 归因（判定 {@code ns} 减少是否由 {@code maxinno}
     * 剔除行为改变引起），不参与 A3；因此 {@code ri}/{@code rj} 留空，并以 {@code rej=1} 标记。
     * </p>
     */
    private static final class Ai3Collector {
        private final String station;
        private final String day;
        private final int hour;
        private final String group;
        private final int[] counters;
        private final List<String> rows = new ArrayList<>();

        Ai3Collector(String station, String day, int hour, String group, int[] counters) {
            this.station = station;
            this.day = day;
            this.hour = hour;
            this.group = group;
            this.counters = counters;
        }

        void onLine(String line) {
            String action = value(line, "action");
            if (!"DDRES".equals(action) && !"DDRES_REJ".equals(action)) {
                return;
            }
            String sat = value(line, "sat");
            String frq = value(line, "frq");
            String before = value(line, "before");
            String after = value(line, "after");
            if (sat == null || frq == null || before == null || after == null) {
                return;
            }
            boolean rej = "DDRES_REJ".equals(action);
            String ri = rej ? "" : value(line, "Ri");
            String rj = rej ? "" : value(line, "Rj");
            rows.add(join(station, day, String.valueOf(hour), group,
                    value(line, "epoch"), value(line, "gpst"), sat, frq,
                    before, after, ri == null ? "" : ri, rj == null ? "" : rj,
                    rej ? "1" : "0"));
            if (rej) {
                counters[4]++;
            } else {
                counters[3]++;
            }
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
        return repoRoot.resolve("data").resolve("fix-validator").resolve("pairing");
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