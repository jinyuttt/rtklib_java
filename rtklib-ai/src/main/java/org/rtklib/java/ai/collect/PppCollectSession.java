package org.rtklib.java.ai.collect;

import org.rtklib.java.ai.spi.AiHooks;
import org.rtklib.java.ai.spi.AiProvider;
import org.rtklib.java.ai.spi.FixValidator;
import org.rtklib.java.ai.spi.ObsCorrector;
import org.rtklib.java.ai.spi.PppSolutionValidator;
import org.rtklib.java.ai.spi.PppVarianceScaler;
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
 * PPP 体系（接口 A / 接口 B）数据采集会话——<b>唯一稳定入口</b>。
 * <p>
 * 与 {@link CollectSession} 同构：注入 provider → 运行解算 → 还原注入 → 落清单，
 * 用 try-with-resources 保证收尾。标准用法：
 * </p>
 * <pre>{@code
 * CollectConfig cfg = CollectConfig.of(Paths.get("out/ppp"), "GEJ3034", "2021-304");
 * try (PppCollectSession s = PppCollectSession.open(cfg, epoch -> rtk.sol.rr)) {
 *     s.runWith(() -> solveOneFile(cfg));
 * }
 * }</pre>
 * <p>
 * <b>一次会话 = 一个站 × 一个日 × 一个 runId</b>，同时采集两个接口：
 * </p>
 * <ul>
 *   <li>接口 A（历元级，10 维）→ {@link CollectConfig#pppCsvPath()}，清单 {@code _runs.csv}；</li>
 *   <li>接口 B（行级，12 维）→ {@link CollectConfig#pppScaleCsvPath()}，清单
 *       {@value #SCALE_MANIFEST_NAME}。</li>
 * </ul>
 * <p>
 * 两份清单而非一份：两份 CSV 的行数口径不同（历元数 vs 观测行数），而运行清单的
 * {@code rows} 列是组装阶段判断「运行是否残缺」的依据。混在一份清单里，B 的组装
 * 会拿 A 的行数当自己的，残缺判定随之失效。两份清单共用 {@link CollectSession#MANIFEST_HEADER}，
 * 列序与既有清单完全一致，故接口一/三的组装代码无需改动即可读 {@code _runs.csv}。
 * </p>
 * <p>
 * <b>位置回调</b>：接口 A 的标签是「解算位置 vs 参考坐标」的 3D 误差，而
 * {@code PppSolutionContext} 刻意不含坐标（S2：impl 只读快照）。二者由宿主弥合：
 * 宿主持有 {@code Rtk}，逐历元把 {@code sol.rr} 交给 {@link PppPositionSource}。
 * 不提供回调时 A 的 CSV 位置列留空，组装阶段计为 {@code missing_position} 并丢弃该历元
 * （不伪造坐标）。接口 B 不需要位置回调——它的标签 {@code resp / sigma} 全在快照里。
 * </p>
 * <p>
 * 前置条件：{@code ionoopt == IONOOPT_IFLC} 时 core 跳过接口 B（R 为标量），采集会得到
 * 空数据集；动态 PPP 还需 {@code opt.dynamics == 1} 才有足够的状态自由度。采集须用
 * 非 IFLC、且与目标场景一致的配置。
 * </p>
 */
public final class PppCollectSession implements AutoCloseable {

    /** 接口 B 的运行清单文件名（与接口一/三的 {@code _runs.csv} 分开）。 */
    public static final String SCALE_MANIFEST_NAME = "_runs_ppp_scale.csv";

    private static final Logger LOG = LoggerFactory.getLogger(PppCollectSession.class);
    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private final CollectConfig cfg;
    private final String startedAt;
    private final RecordingPppSolutionValidator solutionValidator;
    private final RecordingPppVarianceScaler varianceScaler;
    private boolean closed;

    private PppCollectSession(CollectConfig cfg, String startedAt,
                              RecordingPppSolutionValidator solutionValidator,
                              RecordingPppVarianceScaler varianceScaler) {
        this.cfg = cfg;
        this.startedAt = startedAt;
        this.solutionValidator = solutionValidator;
        this.varianceScaler = varianceScaler;
    }

    /**
     * 打开一次 PPP 采集会话（不采集位置，接口 A 的 CSV 位置列留空）。
     *
     * @param cfg 采集配置，非 {@code null}
     * @return 会话，必须关闭（try-with-resources）
     * @throws IOException 目录无法创建，或目标 CSV 已存在且 {@code overwrite=false}
     */
    public static PppCollectSession open(CollectConfig cfg) throws IOException {
        return open(cfg, null, null);
    }

    /**
     * 打开一次 PPP 采集会话。
     *
     * @param cfg       采集配置，非 {@code null}
     * @param positions 逐历元位置回调，{@code null} 表示不采集位置
     * @return 会话，必须关闭（try-with-resources）
     * @throws IOException 目录无法创建，或目标 CSV 已存在且 {@code overwrite=false}
     */
    public static PppCollectSession open(CollectConfig cfg, PppPositionSource positions)
            throws IOException {
        return open(cfg, positions, null);
    }

    /**
     * 打开一次 PPP 采集会话。
     *
     * @param cfg       采集配置，非 {@code null}
     * @param positions 逐历元位置回调，{@code null} 表示不采集位置
     * @param reference 站点参考坐标 ECEF {@code {x,y,z}}（米），{@code null} 表示不预算误差；
     *                  给了也仍需在组装阶段用 {@code --truth} 复算，这里只是便于现场自查
     * @return 会话，必须关闭（try-with-resources）
     * @throws IOException 目录无法创建，或目标 CSV 已存在且 {@code overwrite=false}
     */
    public static PppCollectSession open(CollectConfig cfg, PppPositionSource positions,
                                         double[] reference) throws IOException {
        Objects.requireNonNull(cfg, "cfg 不得为 null");
        cfg.prepare();
        BufferedWriter solWriter = Files.newBufferedWriter(cfg.pppCsvPath(), StandardCharsets.UTF_8);
        BufferedWriter scaleWriter = null;
        try {
            scaleWriter = Files.newBufferedWriter(cfg.pppScaleCsvPath(), StandardCharsets.UTF_8);
            LOG.info("PPP 采集开始: station={} day={} run={} -> {} / {}",
                    cfg.station(), cfg.day(), cfg.runId(),
                    cfg.pppCsvPath(), cfg.pppScaleCsvPath());
            return new PppCollectSession(cfg, TS.format(LocalDateTime.now()),
                    new RecordingPppSolutionValidator(cfg, solWriter, positions, reference),
                    new RecordingPppVarianceScaler(cfg, scaleWriter));
        } catch (IOException | RuntimeException e) {
            closeQuietly(scaleWriter);
            closeQuietly(solWriter);
            throw e;
        }
    }

    /** 接口 A 采集器，可直接用于实例字段注入。 */
    public RecordingPppSolutionValidator solutionValidator() {
        return solutionValidator;
    }

    /** 接口 B 采集器，可直接用于实例字段注入。 */
    public RecordingPppVarianceScaler varianceScaler() {
        return varianceScaler;
    }

    public String runId() {
        return cfg.runId();
    }

    public CollectConfig config() {
        return cfg;
    }

    /**
     * 注入 → 执行 → <b>还原</b>，保证注入点一定恢复原状（即使 {@code body} 抛异常）。
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
        IOException failure = null;
        try {
            solutionValidator.close();
        } catch (IOException e) {
            failure = e;
        }
        try {
            varianceScaler.close();
        } catch (IOException e) {
            if (failure == null) failure = e;
        }
        appendManifest(cfg.manifestPath(), cfg.pppCsvPath(), solutionValidator.rows());
        appendManifest(cfg.outputDir().resolve(SCALE_MANIFEST_NAME), cfg.pppScaleCsvPath(),
                varianceScaler.rows());
        LOG.info("PPP 采集结束: run={} A行数={} B行数={}（B 跳过 {} 行：σ 缺失或残差非有限）",
                cfg.runId(), solutionValidator.rows(), varianceScaler.rows(),
                varianceScaler.skippedRows());
        if (failure != null) {
            throw failure;
        }
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
            public PppSolutionValidator pppSolutionValidator() {
                return solutionValidator;
            }

            @Override
            public PppVarianceScaler pppVarianceScaler() {
                return varianceScaler;
            }
        });
        return previous;
    }

    /**
     * 追加一行运行清单。
     * <p>
     * {@code status} 恒为 {@code ok}：本方法只在正常 {@code close()} 时执行，
     * 崩溃的运行不会走到这里，正是「残缺运行没有 ok 行」的识别依据。
     * </p>
     */
    private void appendManifest(Path manifest, Path csv, long rows) throws IOException {
        StringBuilder sb = new StringBuilder(192);
        if (!Files.exists(manifest)) {
            sb.append(CollectSession.MANIFEST_HEADER).append('\n');
        }
        sb.append(cfg.runId()).append(',')
                .append(cfg.station()).append(',')
                .append(cfg.day()).append(',')
                .append(csv.getFileName()).append(',')
                .append(cfg.tracePath().getFileName()).append(',')
                .append(startedAt).append(',')
                .append(TS.format(LocalDateTime.now())).append(',')
                .append(rows).append(',')
                .append("ok\n");
        Files.writeString(manifest, sb.toString(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    private static void closeQuietly(java.io.Closeable c) {
        if (c == null) return;
        try {
            c.close();
        } catch (IOException ignored) {
            // 构造失败路径上的二次异常不应掩盖原始原因
        }
    }
}