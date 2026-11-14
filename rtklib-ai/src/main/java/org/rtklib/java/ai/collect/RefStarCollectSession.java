package org.rtklib.java.ai.collect;

import org.rtklib.java.ai.spi.AiHooks;
import org.rtklib.java.ai.spi.AiProvider;
import org.rtklib.java.ai.spi.FixValidator;
import org.rtklib.java.ai.spi.ObsCorrector;
import org.rtklib.java.ai.spi.RefStarValidator;
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
 * 接口四数据采集会话——唯一稳定入口。
 *
 * 与接口三的 {@link ObsCorrectorCollectSession} 结构逐行一致（注入 provider → 运行解算 → 还原注入），
 * 只是落盘内容换成接口四的 15 维特征，运行清单落到 {@code _refstar_runs.csv}。
 * 三个采集会话<b>互斥使用</b>：同一个 {@link AiProvider} 只能返回其中一个采集器，
 * 否则多个采集器争同一份 {@code ThreadLocal} 槽位，只有一个生效。
 *
 * 标准用法：
 * <pre>{@code
 * CollectConfig cfg = CollectConfig.of(Paths.get("out/collect-refstar"), "HKWS", "2024-060");
 * try (RefStarCollectSession s = RefStarCollectSession.open(cfg)) {
 *     s.runWith(() -> solveOneFile(cfg));
 * }
 * }</pre>
 *
 * <b>会话不负责开启 trace，也不负责重跑。</b>接口四的标签来自 {@code AI4/IS_REF}
 * （当历元真实参考星，与接口四是否注入无关），故采集分两步走：
 * <ol>
 *   <li>步骤 0：先在<b>未注入</b>状态下跑一遍，只开 {@code topics={"AI4"}} 产出
 *       {@code IS_REF} 行（= 原生参考星轨迹）；</li>
 *   <li>步骤 1：用本会话在<b>同一配置</b>下再跑一遍，产出特征 CSV。</li>
 * </ol>
 * 没有步骤 0 的 {@code IS_REF} trace，标签不存在，训练产物无效。
 */
public final class RefStarCollectSession implements AutoCloseable {

    /** 运行清单表头。与接口一/三分开，避免几套采集混在一张表里。 */
    public static final String MANIFEST_HEADER =
            "run_id,station,day,refstar_csv_file,trace_file,started_at,finished_at,rows,status";

    /** 运行清单文件名（与 {@link CollectConfig#manifestPath()} 区分）。 */
    public static final String MANIFEST_NAME = "_refstar_runs.csv";

    private static final Logger LOG = LoggerFactory.getLogger(RefStarCollectSession.class);
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final CollectConfig cfg;
    private final String startedAt;
    private final RecordingRefStarValidator recorder;
    private boolean closed;

    private RefStarCollectSession(CollectConfig cfg, String startedAt,
                                  RecordingRefStarValidator recorder) {
        this.cfg = cfg;
        this.startedAt = startedAt;
        this.recorder = recorder;
    }

    /**
     * 打开一次接口四采集会话。
     *
     * @param cfg 采集配置，非 {@code null}
     * @return 会话，必须关闭（try-with-resources）
     * @throws IOException 目录无法创建，或目标 CSV 已存在且 {@code overwrite=false}
     */
    public static RefStarCollectSession open(CollectConfig cfg) throws IOException {
        Objects.requireNonNull(cfg, "cfg 不得为 null");
        cfg.prepare();
        BufferedWriter w = Files.newBufferedWriter(cfg.refStarCsvPath(), StandardCharsets.UTF_8);
        LOG.info("接口四采集开始: station={} day={} run={} -> {}",
                cfg.station(), cfg.day(), cfg.runId(), cfg.refStarCsvPath());
        return new RefStarCollectSession(cfg, TS.format(LocalDateTime.now()),
                new RecordingRefStarValidator(cfg, w));
    }

    /** 采集器，可直接用于实例字段注入（{@code rtk.aiRefStarValidator = ...}）。 */
    public RecordingRefStarValidator recorder() {
        return recorder;
    }

    public String runId() {
        return cfg.runId();
    }

    public CollectConfig config() {
        return cfg;
    }

    /**
     * 注入 → 执行 → <b>还原</b>，保证注入点一定恢复原状（即使 {@code body} 抛异常）。
     */
    public void runWith(Runnable body) {
        AiProvider previous = install();
        try {
            body.run();
        } finally {
            AiHooks.install(previous);
        }
    }

    /** 同 {@link #runWith(Runnable)}，但保留返回值。 */
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
            recorder.close();
        } finally {
            appendManifest();
        }
        LOG.info("接口四采集结束: run={} rows={}", cfg.runId(), recorder.rows());
    }

    private AiProvider install() {
        AiProvider previous = AiHooks.provider();
        AiHooks.install(new AiProvider() {
            @Override
            public FixValidator fixValidator() {
                return null;
            }

            @Override
            public ObsCorrector obsCorrector() {
                return null;
            }

            @Override
            public RefStarValidator refStarValidator() {
                return recorder;
            }
        });
        return previous;
    }

    private void appendManifest() throws IOException {
        Path p = cfg.outputDir().resolve(MANIFEST_NAME);
        StringBuilder sb = new StringBuilder(192);
        if (!Files.exists(p)) {
            sb.append(MANIFEST_HEADER).append('\n');
        }
        sb.append(cfg.runId()).append(',')
                .append(cfg.station()).append(',')
                .append(cfg.day()).append(',')
                .append(cfg.refStarCsvPath().getFileName()).append(',')
                .append(cfg.tracePath().getFileName()).append(',')
                .append(startedAt).append(',')
                .append(TS.format(LocalDateTime.now())).append(',')
                .append(recorder.rows()).append(',')
                .append("ok\n");
        Files.writeString(p, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}