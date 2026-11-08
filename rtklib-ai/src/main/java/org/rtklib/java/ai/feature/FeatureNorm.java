package org.rtklib.java.ai.feature;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 特征归一化参数（设计文档 §5.1「归一化要求」）。
 * <p>
 * 归一化参数在<b>训练集</b>上拟合后固化到模型旁的 {@code norm.json}，推理侧原样加载：
 * {@code y = (x - mean) / std}，{@code std} 为 0 或缺失的列按 1.0 处理（等价于只减均值）。
 * </p>
 * <p>
 * 禁止推理侧按运行数据动态归一化——同一模型在不同数据上行为漂移会让阈值失去意义，
 * 也让「训练/推理特征分布一致性」无法验证。
 * </p>
 * <p>
 * 文件缺失时退化为<b>恒等归一化</b>（{@link #identity()}）：阶段 2 的 dummy 模型没有
 * {@code norm.json}，恒等映射保证最小验证链路可独立跑通。
 * </p>
 * <p>
 * 解析器刻意只支持 {@code norm.json} 的固定形态（两个数值数组），不引入 JSON 依赖：
 * {@code {"mean":[...],"std":[...]}}，允许额外字段与空白。
 * </p>
 */
public final class FeatureNorm {

    private final double[] mean;
    private final double[] std;
    private final int dim;

    private FeatureNorm(double[] mean, double[] std, int dim) {
        this.mean = mean;
        this.std = std;
        this.dim = dim;
    }

    /** 恒等归一化：不改变特征，供无 {@code norm.json} 的 dummy 模型使用。 */
    public static FeatureNorm identity() {
        return identity(FixQualityFeatures.DIM);
    }

    /** 指定维度的恒等归一化。 */
    public static FeatureNorm identity(int dim) {
        return new FeatureNorm(null, null, dim);
    }

    /**
     * 从 {@code norm.json} 加载（接口一维度）；文件不存在返回恒等归一化。
     *
     * @param path {@code norm.json} 路径，可为 {@code null}
     * @throws IllegalArgumentException 文件存在但内容/维度非法（配置错误应尽早暴露）
     */
    public static FeatureNorm load(Path path) {
        return load(path, FixQualityFeatures.DIM);
    }

    /**
     * 从 {@code norm.json} 加载并校验特征维度；文件不存在返回该维度的恒等归一化。
     *
     * @param path {@code norm.json} 路径，可为 {@code null}
     * @param dim  期望的特征维数（接口一 6、接口三 17）
     */
    public static FeatureNorm load(Path path, int dim) {
        if (path == null || !Files.isRegularFile(path)) {
            return identity(dim);
        }
        String text;
        try {
            text = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("读取归一化参数失败: " + path, e);
        }
        double[] mean = parseArray(text, "mean", path);
        double[] std = parseArray(text, "std", path);
        if (mean != null && mean.length != dim) {
            throw new IllegalArgumentException("norm.json 的 mean 长度 " + mean.length
                    + " 与特征维 " + dim + " 不一致: " + path);
        }
        if (std != null && std.length != dim) {
            throw new IllegalArgumentException("norm.json 的 std 长度 " + std.length
                    + " 与特征维 " + dim + " 不一致: " + path);
        }
        return new FeatureNorm(mean, std, dim);
    }

    /** 是否为恒等归一化（无参数）。 */
    public boolean isIdentity() {
        return mean == null && std == null;
    }

    /** 归一化参数覆盖的特征维数。 */
    public int dim() {
        return dim;
    }

    /**
     * 对原始特征做归一化（接口一维度）。
     *
     * @param raw 长度必须等于 {@link FixQualityFeatures#DIM}
     * @return 可直接送入 ONNX 的 {@code float} 数组（与 {@code raw} 等长、新分配）
     */
    public float[] normalize(double[] raw) {
        return normalize(raw, dim);
    }

    /**
     * 对原始特征做归一化。
     *
     * @param raw       原始特征
     * @param expected  期望长度
     * @return 可直接送入 ONNX 的 {@code float} 数组（与 {@code raw} 等长、新分配）
     */
    public float[] normalize(double[] raw, int expected) {
        if (raw == null || raw.length != expected) {
            throw new IllegalArgumentException("原始特征长度必须为 " + expected
                    + "，实际 " + (raw == null ? "null" : raw.length));
        }
        float[] out = new float[raw.length];
        for (int i = 0; i < raw.length; i++) {
            double v = raw[i];
            if (mean != null) v -= mean[i];
            if (std != null && std[i] != 0.0 && Double.isFinite(std[i])) v /= std[i];
            out[i] = (float) FixQualityFeatures.sanitize(v);
        }
        return out;
    }

    /**
     * 极简数组解析：定位 {@code "key":} 后的第一个 {@code [ ... ]} 并取其中数值。
     * 缺失该键返回 {@code null}；出现无法解析的片段返回 {@code null}（由调用方按恒等处理）。
     */
    private static double[] parseArray(String text, String key, Path path) {
        int k = text.indexOf("\"" + key + "\"");
        if (k < 0) return null;
        int lb = text.indexOf('[', k);
        if (lb < 0) return null;
        int rb = text.indexOf(']', lb);
        if (rb < 0) return null;

        String body = text.substring(lb + 1, rb).trim();
        if (body.isEmpty()) {
            throw new IllegalArgumentException("norm.json 的 " + key + " 为空数组: " + path);
        }
        List<Double> values = new ArrayList<>();
        for (String part : body.split(",")) {
            String s = part.trim();
            if (s.isEmpty()) continue;
            try {
                values.add(Double.parseDouble(s));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("norm.json 的 " + key
                        + " 含非数值项 \"" + s + "\": " + path, e);
            }
        }
        double[] arr = new double[values.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = values.get(i);
        return arr;
    }
}