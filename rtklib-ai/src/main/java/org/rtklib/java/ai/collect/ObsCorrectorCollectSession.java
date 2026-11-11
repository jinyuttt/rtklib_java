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
 * 接口三数据采集会话——<b>唯一稳定入口</b>。
 * <p>
 * 与接口一的 {@link CollectSession} 结构一致（注入 provider → 运行解算 → 还原注入），
 * 但落盘内容不同：接口一是「一历元一行」的 6 维特征，接口三是
 * 「一历元 × 一卫星 × 一频点一行」的 17 维特征。两者<b>互斥使用</b>：
 * 同一个 {@code AiProvider} 只能返回其中一个采集器，否则两套采集器争同一份
 * {@code ThreadLocal} 槽位，只会有一个生效。
 * </p>
 * <p>
 * 标准用法：
 * </p>
 * <pre>{@code
 * CollectConfig cfg = CollectConfig.of(Paths.get("out/collect-obs"), "HKWS", "2024-060");
 * try (ObsCorrectorCollectSession s = ObsCorrectorCollectSession.open(cfg)) {
 *     s.runWith(() -> solveOneFile(cfg));   // 注入 → 解算 → 还原，一步完成
 * }
 * // 退出时：.obs.csv 已 flush/close，_obs_runs.csv 已追加本次运行清单
 * }</pre>
 * <p>
 * 会话<b>不</b>负责开启 trace。接口三的标签来自 core 的 {@code AI3/DDRES} 行
 * （{@code before} 字段即未扣偏差的双差码残差），宿主须开启
 * {@code topics={"AI3"}} 并把 trace 输出到 {@link CollectConfig#tracePath()}，
 * 与特征 CSV 靠 {@code (runId, epoch, sat, frq)} 对齐。
 * </p>
 */
public final class ObsCorrectorCollectSession implements AutoCloseable {

    /** 运行清单表头。与接口一的 {@code _runs.csv} 分开，避免两套采集混在一张表里。 */
    public static final String MANIFEST_HEADER =
            "run_id,station,day,obs_csv_file,trace_file,started_at,finished_at,rows,status";

    /** 运行清单文件名（与 {@link CollectConfig#manifestPath()} 区分）。 */
    public static final String MANIFEST_NAME = "_obs_runs.csv";

    private static final Logger LOG = LoggerFactory.getLogger(ObsCorrectorCollectSession.class);
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final CollectConfig cfg;
    private final String startedAt;
    private final RecordingObsCorrector recorder;
    private boolean closed;

    private ObsCorrectorCollectSession(CollectConfig cfg, String startedAt,
                                       RecordingObsCorrector recorder) {
        this.cfg = cfg;
        this.startedAt = startedAt;
        this.recorder = recorder;
    }

    /**
     * 打开一次接口三采集会话。
     *
     * @param cfg 采集配置，非 {@code null}
     * @return 会话，必须关闭（try-with-resources）
     * @throws IOException 目录无法创建，或目标 CSV 已存在且 {@code overwrite=false}
     */
    public static ObsCorrectorCollectSession open(CollectConfig cfg) throws IOException {
        Objects.requireNonNull(cfg, "cfg 不得为 null");
        cfg.prepare();
        BufferedWriter w = Files.newBufferedWriter(cfg.obsCsvPath(), StandardCharsets.UTF_8);
        LOG.info("接口三采集开始: station={} day={} run={} -> {}",
                cfg.station(), cfg.day(), cfg.runId(), cfg.obsCsvPath());
        return new ObsCorrectorCollectSession(cfg, TS.format(LocalDateTime.now()),
                new RecordingObsCorrector(cfg, w));
    }

    /** 采集器，可直接用于实例字段注入（{@code rtk.aiObsCorrector = ...}）。 */
    public RecordingObsCorrector recorder() {
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
        LOG.info("接口三采集结束: run={} rows={}", cfg.runId(), recorder.rows());
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
                .append(cfg.obsCsvPath().getFileName()).append(',')
                .append(cfg.tracePath().getFileName()).append(',')
                .append(startedAt).append(',')
                .append(TS.format(LocalDateTime.now())).append(',')
                .append(recorder.rows()).append(',')
                .append("ok\n");
        Files.writeString(p, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}