package org.rtklib.java.ai.collect;

import org.rtklib.java.ai.spi.AiHooks;
import org.rtklib.java.ai.spi.AiProvider;
import org.rtklib.java.ai.spi.FixValidator;
import org.rtklib.java.ai.spi.ObsCorrector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 接口一数据采集会话——<b>唯一稳定入口</b>。
 * <p>
 * 采集需要三件事同时正确：注入 provider、运行解算、清理 {@code ThreadLocal}。
 * 三者分散写容易漏（尤其 {@code AiHooks.clear()}），故收敛为一个会话对象，
 * 用 try-with-resources 保证收尾。标准用法：
 * </p>
 * <pre>{@code
 * CollectConfig cfg = CollectConfig.of(Paths.get("out/collect"), "GEJ3034", "2021-304");
 * try (CollectSession s = CollectSession.open(cfg)) {
 *     s.runWith(() -> solveOneFile(cfg));       // 注入 → 解算 → 清理，一步完成
 * }
 * // 退出时：CSV 已 flush/close，_runs.csv 已追加本次运行清单
 * }</pre>
 * <p>
 * 一次会话只对应一次运行（一个站 × 一个日 × 一个 runId）。多次运行就多次
 * {@code open()}，结果累积在同一目录下，靠 {@code run_id} 区分。
 * </p>
 * <p>
 * 会话<b>不</b>负责开启 trace：标签 join 需要 {@code AR/FIX} 里的绝对时间，
 * 而 {@code FixQualityContext} 只带进程内 {@code epoch}。宿主须把 trace 输出到
 * {@link CollectConfig#tracePath()}，两者靠 {@code (runId, epoch)} 对齐。
 * </p>
 * <p>
 * 另有一个必须遵守的前置条件：{@code ionoopt == IONOOPT_IFLC} 时 core 直接跳过
 * 接口一，采集器根本不会被调用，跑完会得到空数据集。采集须用非 IFLC 配置。
 * </p>
 */
public final class CollectSession implements AutoCloseable {

    /** 运行清单表头。 */
    public static final String MANIFEST_HEADER =
            "run_id,station,day,csv_file,trace_file,started_at,finished_at,rows,status";

    private static final Logger LOG = LoggerFactory.getLogger(CollectSession.class);
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final CollectConfig cfg;
    private final String startedAt;
    private final RecordingFixValidator validator;
    private boolean closed;

    private CollectSession(CollectConfig cfg, String startedAt,
                           RecordingFixValidator validator) {
        this.cfg = cfg;
        this.startedAt = startedAt;
        this.validator = validator;
    }

    /**
     * 打开一次采集会话。
     *
     * @param cfg 采集配置，非 {@code null}
     * @return 会话，必须关闭（try-with-resources）
     * @throws IOException 目录无法创建，或目标 CSV 已存在且 {@code overwrite=false}
     */
    public static CollectSession open(CollectConfig cfg) throws IOException {
        Objects.requireNonNull(cfg, "cfg 不得为 null");
        cfg.prepare();
        BufferedWriter w = Files.newBufferedWriter(cfg.csvPath(), StandardCharsets.UTF_8);
        LOG.info("接口一采集开始: station={} day={} run={} -> {}",
                cfg.station(), cfg.day(), cfg.runId(), cfg.csvPath());
        return new CollectSession(cfg, TS.format(LocalDateTime.now()),
                new RecordingFixValidator(cfg, w));
    }

    /** 采集器，可直接用于实例字段注入（{@code rtk.aiFixValidator = ...}）。 */
    public RecordingFixValidator validator() {
        return validator;
    }

    public String runId() {
        return cfg.runId();
    }

    public CollectConfig config() {
        return cfg;
    }

    /**
     * 注入 → 执行 → <b>还原</b>，保证注入点一定恢复原状（即使 {@code body} 抛异常）。
     * <p>
     * 还原而非清空：宿主可能已有自己的 provider，直接 {@code AiHooks.clear()} 会把它一并抹掉。
     * </p>
     *
     * @param body 解算动作
     */
    public void runWith(Runnable body) {
        AiProvider previous = install();
        try {
            body.run();
        } finally {
            AiHooks.install(previous);
        }
    }

    /**
     * 同 {@link #runWith(Runnable)}，但保留返回值。
     * <p>
     * 与 {@code runWith} 分开命名而非重载：{@code () -> solve(cfg)} 同时兼容
     * {@code Runnable} 与 {@code Supplier}，重载会产生歧义。
     * </p>
     *
     * @param body 解算动作
     * @param <T>  返回值类型
     * @return 解算结果
     */
    public <T> T callWith(Supplier<T> body) {
        AiProvider previous = install();
        try {
            return body.get();
        } finally {
            AiHooks.install(previous);
        }
    }

    @Override
    public void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        try {
            validator.close();
        } finally {
            appendManifest();
        }
        LOG.info("接口一采集结束: run={} rows={}", cfg.runId(), validator.rows());
    }

    private AiProvider install() {
        AiProvider previous = AiHooks.provider();
        AiHooks.install(new AiProvider() {
            @Override
            public FixValidator fixValidator() {
                return validator;
            }

            @Override
            public ObsCorrector obsCorrector() {
                return null;
            }
        });
        return previous;
    }

    private void appendManifest() throws IOException {
        Path p = cfg.manifestPath();
        StringBuilder sb = new StringBuilder(192);
        if (!Files.exists(p)) {
            sb.append(MANIFEST_HEADER).append('\n');
        }
        sb.append(cfg.runId()).append(',')
                .append(cfg.station()).append(',')
                .append(cfg.day()).append(',')
                .append(cfg.csvPath().getFileName()).append(',')
                .append(cfg.tracePath().getFileName()).append(',')
                .append(startedAt).append(',')
                .append(TS.format(LocalDateTime.now())).append(',')
                .append(validator.rows()).append(',')
                .append("ok\n");
        Files.writeString(p, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}