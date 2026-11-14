package org.rtklib.java.ai.collect;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern;

/**
 * 接口一数据采集配置（一次运行 = 一个站 × 一个观测日 × 一个 runId）。
 * <p>
 * 设计前提：单次运行产出的样本量远不足以训练，训练集由<b>多次运行累积</b>而成。
 * 因此：
 * </p>
 * <ul>
 *   <li>{@code runId} 是必填项（缺省自动生成），它同时写入 CSV 每一行与 trace 文件名；
 *       {@code epoch} 只是进程内计数器，跨运行会从 0 重来，故主键是 {@code (runId, epoch)}；</li>
 *   <li>{@code station}/{@code day} 是两层切分的依据，构造期固定，不提供 setter；</li>
 *   <li>文件名由 {@code station + day + runId} 派生，天然不碰撞，无需人工命名。</li>
 * </ul>
 * <p>
 * 所有标识符只允许 {@code [A-Za-z0-9._-]}：拒绝空白、逗号、路径分隔符，
 * 从源头消除 CSV 破损与路径穿越。校验失败<b>抛异常</b>而非钳位——
 * 钳位在推理侧无害，在采集侧会静默产出标签错位的数据，污染不可逆。
 * </p>
 */
public record CollectConfig(
        Path outputDir,
        String station,
        String day,
        String runId,
        boolean overwrite,
        int flushEvery) {

    /** 默认落盘间隔（行）。 */
    public static final int DEFAULT_FLUSH_EVERY = 1000;

    private static final Pattern SAFE_TOKEN = Pattern.compile("[A-Za-z0-9._-]{1,32}");
    private static final DateTimeFormatter RUN_ID_TIME =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");

    public CollectConfig {
        Objects.requireNonNull(outputDir, "outputDir 不得为 null");
        station = safeToken(station, "station");
        day = safeToken(day, "day");
        runId = (runId == null || runId.isBlank()) ? generateRunId() : safeToken(runId, "runId");
        if (flushEvery <= 0) {
            flushEvery = DEFAULT_FLUSH_EVERY;
        }
    }

    /** 最常用形态：自动生成 runId、不覆盖、默认落盘间隔。 */
    public static CollectConfig of(Path outputDir, String station, String day) {
        return new CollectConfig(outputDir, station, day, null, false, DEFAULT_FLUSH_EVERY);
    }

    /** 单次运行的 CSV 落盘路径。 */
    public Path csvPath() {
        return outputDir.resolve(station + "_" + day + "_" + runId + ".csv");
    }

    /**
     * 单次运行应配套使用的 trace 路径。
     * <p>
     * 本类只提供文件名约定，不负责开启 trace：{@code epoch} 无绝对时间，
     * 标签 join 依赖 {@code AR/FIX} trace 的 {@code gpst}，故宿主须把 trace 输出到这里。
     * </p>
     */
    public Path tracePath() {
        return outputDir.resolve(station + "_" + day + "_" + runId + ".trace");
    }

    /**
     * 接口三采集的 17 维特征落盘路径。
     * <p>
     * 与接口一的 {@link #csvPath()} <b>必须分开</b>：接口一是「一历元一行」（6 维），
     * 接口三是「一历元 × 一卫星 × 一频点一行」（17 维），行数与主键都不同，
     * 混在一个文件里无法 join，也无法按行回溯。
     * </p>
     */
    public Path obsCsvPath() {
        return outputDir.resolve(station + "_" + day + "_" + runId + ".obs.csv");
    }

    /**
     * 接口四采集的 15 维特征落盘路径。
     * <p>
     * 与接口三同样必须分开：接口四主键是 {@code (epoch, sat, frq)}，但标签来自
     * {@code AI4/IS_REF}（当历元真实参考星），与接口三的码残差标签是两套东西。
     * </p>
     */
    public Path refStarCsvPath() {
        return outputDir.resolve(station + "_" + day + "_" + runId + ".refstar.csv");
    }

    /** 跨运行累积的运行清单，记录每次运行的站/日/行数，供组装阶段过滤残缺运行。 */
    public Path manifestPath() {
        return outputDir.resolve("_runs.csv");
    }

    /**
     * 创建输出目录并做冲突检查。
     *
     * @throws IOException 目录无法创建，或目标 CSV 已存在且 {@code overwrite=false}
     */
    public void prepare() throws IOException {
        Files.createDirectories(outputDir);
        if (!overwrite && Files.exists(csvPath())) {
            throw new IOException("采集文件已存在，拒绝覆盖（runId 必须唯一）: " + csvPath());
        }
        if (!overwrite && Files.exists(obsCsvPath())) {
            throw new IOException("接口三采集文件已存在，拒绝覆盖（runId 必须唯一）: " + obsCsvPath());
        }
        if (!overwrite && Files.exists(refStarCsvPath())) {
            throw new IOException("接口四采集文件已存在，拒绝覆盖（runId 必须唯一）: " + refStarCsvPath());
        }
    }

    private static String safeToken(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " 不得为空");
        }
        if (!SAFE_TOKEN.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    name + " 仅允许字母/数字/._- 且长度 1..32: " + value);
        }
        return value;
    }

    private static String generateRunId() {
        return RUN_ID_TIME.format(LocalDateTime.now()) + "-"
                + String.format(Locale.ROOT, "%04x", ThreadLocalRandom.current().nextInt(0x10000));
    }
}