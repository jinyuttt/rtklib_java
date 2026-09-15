package org.rtklib.java.postpos;

import org.ejml.simple.SimpleMatrix;
import org.rtklib.java.rtkpos.Smoother;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * 基于RTKLIB .pos文件的RTS平滑后处理工具（测试功能）。
 *
 * <p><b>⚠ 此功能为测试/实验性质，仅供验证和评估使用，不建议直接用于生产环境。</b></p>
 *
 * <h3>功能概述</h3>
 * <p>解析RTKLIB标准输出格式的.pos文件（ENU baseline模式），对基线分量(e,n,u)进行
 * Rauch-Tung-Striebel (RTS) 固定区间平滑，输出平滑后的结果。</p>
 *
 * <h3>两种平滑模式</h3>
 * <ul>
 *   <li><b>简单反转配对模式</b> ({@link #smooth})：将历元序列反转作为后向数据，
 *       与前向数据逐历元配对，调用{@link Smoother#smooth}做协方差加权融合。
 *       适用于全固定解数据；对含浮点解的数据可能恶化结果。</li>
 *   <li><b>卡尔曼滤波模式</b> ({@link #smoothKalman})：对.pos中的位置估计作为伪观测，
 *       分别执行前向和后向卡尔曼滤波，再用{@link Smoother#smooth}融合。
 *       浮点解历元通过协方差放大自动降权，实测对含浮点解数据有效。</li>
 * </ul>
 *
 * <h3>重要限制（测试功能说明）</h3>
 * <ul>
 *   <li>.pos文件中的(e,n,u)已经是RTK前向卡尔曼滤波的输出结果，并非原始GPS观测数据。</li>
 *   <li>简单反转法的前向和后向数据完全相关（同一份数据），不是真正的独立双向滤波。</li>
 *   <li>卡尔曼模式将已滤波结果当作"伪观测"再滤波（"滤波之滤波"），数学上不严格：
 *       前后向估计存在隐含相关性，存在过度平滑风险。</li>
 *   <li>严格的RTS平滑需要原始观测数据（.obs/.nav），通过
 *       {@code PostPosProcessor.processCombined()}实现。</li>
 *   <li>本工具适用于静态基线（位置基本不变）的场景评估，对动态基线需谨慎。</li>
 * </ul>
 *
 * <h3>输入格式</h3>
 * <p>RTKLIB标准.pos文件（ENU baseline模式），每行格式：</p>
 * <pre>
 * yyyy/MM/dd HH:mm:ss.SSSS  e(m)  n(m)  u(m)  Q  ns  sde  sdn  sdu  sden  sdnu  sdue  age  ratio
 * </pre>
 * <p>Q=1为固定解，Q!=1为非固定解（浮点解等）。表头行以%开头，自动跳过。</p>
 *
 * <h3>使用示例</h3>
 * <pre>
 * // 最简调用：文件路径 + 默认配置（卡尔曼模式）
 * SmoothResult result = PosFileRtsSmoother.process("0.pos", new SmoothConfig());
 *
 * // 自定义参数
 * SmoothConfig config = new SmoothConfig()
 *     .useKalman(true)
 *     .processNoiseStd(0.002)
 *     .nonFixVarianceScale(10000);
 * SmoothResult result = PosFileRtsSmoother.process("0.pos", config);
 *
 * // 多文件合并
 * SmoothResult result = PosFileRtsSmoother.processFiles(
 *     List.of("0.pos", "1.pos", "2.pos"), config);
 *
 * // 实体列表输入
 * List&lt;PosEpoch&gt; epochs = PosFileRtsSmoother.parse("0.pos");
 * List&lt;SmoothedEpoch&gt; smoothed = PosFileRtsSmoother.smoothKalman(epochs);
 *
 * // Reader输入（HTTP/流式）
 * List&lt;PosEpoch&gt; epochs = PosFileRtsSmoother.parseFromReader(reader);
 * </pre>
 *
 * <h3>命令行入口</h3>
 * <pre>
 * java PosFileRtsSmoother [--kalman|--simple] [--q=STD] [--scale=S] &lt;pos_file&gt; [pos_file2 ...]
 *   --kalman    卡尔曼RTS模式（默认）
 *   --simple    简单反转配对模式
 *   --q=STD     过程噪声标准差(m)，默认0.001
 *   --scale=S   非固定解方差放大倍数，默认10000
 * </pre>
 *
 * @see Smoother#smooth(double[], double[], double[], double[], int, double[], double[])
 */
public class PosFileRtsSmoother {

    /**
     * .pos文件中的单个历元数据。
     * 包含时间、ENU基线分量、解质量、卫星数及完整的3x3协方差信息。
     */
    public static class PosEpoch {
        public String time;
        public double e, n, u;
        public int q, ns;
        public double sde, sdn, sdu;
        public double sden, sdnu, sdue;

        public PosEpoch() {}

        public PosEpoch(String time, double e, double n, double u,
                        int q, int ns,
                        double sde, double sdn, double sdu,
                        double sden, double sdnu, double sdue) {
            this.time = time;
            this.e = e;
            this.n = n;
            this.u = u;
            this.q = q;
            this.ns = ns;
            this.sde = sde;
            this.sdn = sdn;
            this.sdu = sdu;
            this.sden = sden;
            this.sdnu = sdnu;
            this.sdue = sdue;
        }
    }

    /**
     * 平滑后的单个历元结果。
     * 包含时间、平滑后的ENU基线分量、原始解质量和卫星数、以及平滑是否成功。
     */
    public static class SmoothedEpoch {
        public String time;
        public double e, n, u;
        public int q, ns;
        /** true=RTS融合成功, false=矩阵奇异降级为前后向平均值 */
        public boolean smoothed;

        public SmoothedEpoch() {}
    }

    /**
     * 平滑配置参数。支持链式调用设置。
     */
    public static class SmoothConfig {
        /** true=卡尔曼RTS模式, false=简单反转配对模式 */
        public boolean useKalman = true;
        /** 过程噪声标准差(m)，控制卡尔曼滤波器的记忆长度，默认0.001m */
        public double processNoiseStd = 0.001;
        /** 非固定解(Q!=1)的方差放大倍数，默认10000(即标准差放大100倍) */
        public double nonFixVarianceScale = 100.0 * 100.0;

        public SmoothConfig() {}

        public SmoothConfig useKalman(boolean v) { this.useKalman = v; return this; }
        public SmoothConfig processNoiseStd(double v) { this.processNoiseStd = v; return this; }
        public SmoothConfig nonFixVarianceScale(double v) { this.nonFixVarianceScale = v; return this; }
    }

    /**
     * 平滑结果，包含逐历元平滑数据及整体统计摘要。
     */
    public static class SmoothResult {
        /** 平滑后的历元列表 */
        public List<SmoothedEpoch> epochs;
        /** 总历元数 */
        public int totalEpochs;
        /** 固定解(Q=1)历元数 */
        public int fixedCount;
        /** 非固定解(Q!=1)历元数 */
        public int floatCount;
        /** RTS融合成功历元数 */
        public int smoothedCount;
        /** 降级为前后向平均的历元数 */
        public int fallbackCount;
        /** 平滑后E方向波动范围(m) */
        public double eRange;
        /** 平滑后N方向波动范围(m) */
        public double nRange;
        /** 平滑后U方向波动范围(m) */
        public double uRange;
        /** 平滑后E方向标准差(m) */
        public double eStd;
        /** 平滑后N方向标准差(m) */
        public double nStd;
        /** 平滑后U方向标准差(m) */
        public double uStd;

        public SmoothResult(List<SmoothedEpoch> epochs) {
            this.epochs = epochs;
            this.totalEpochs = epochs.size();
            this.fixedCount = 0;
            this.floatCount = 0;
            this.smoothedCount = 0;
            this.fallbackCount = 0;

            if (epochs.isEmpty()) {
                eRange = nRange = uRange = eStd = nStd = uStd = 0;
                return;
            }

            double eMin = Double.MAX_VALUE, eMax = -Double.MAX_VALUE;
            double nMin = Double.MAX_VALUE, nMax = -Double.MAX_VALUE;
            double uMin = Double.MAX_VALUE, uMax = -Double.MAX_VALUE;
            double eSum = 0, nSum = 0, uSum = 0;

            for (SmoothedEpoch se : epochs) {
                if (se.q == 1) fixedCount++; else floatCount++;
                if (se.smoothed) smoothedCount++; else fallbackCount++;
                eMin = Math.min(eMin, se.e); eMax = Math.max(eMax, se.e);
                nMin = Math.min(nMin, se.n); nMax = Math.max(nMax, se.n);
                uMin = Math.min(uMin, se.u); uMax = Math.max(uMax, se.u);
                eSum += se.e; nSum += se.n; uSum += se.u;
            }

            eRange = eMax - eMin; nRange = nMax - nMin; uRange = uMax - uMin;
            double eMean = eSum / totalEpochs, nMean = nSum / totalEpochs, uMean = uSum / totalEpochs;
            double eVar = 0, nVar = 0, uVar = 0;
            for (SmoothedEpoch se : epochs) {
                eVar += (se.e - eMean) * (se.e - eMean);
                nVar += (se.n - nMean) * (se.n - nMean);
                uVar += (se.u - uMean) * (se.u - uMean);
            }
            eStd = Math.sqrt(eVar / totalEpochs);
            nStd = Math.sqrt(nVar / totalEpochs);
            uStd = Math.sqrt(uVar / totalEpochs);
        }
    }

    /** 从单个.pos文件解析历元列表 */
    public static List<PosEpoch> parse(String path) throws IOException {
        try (BufferedReader br = new BufferedReader(new FileReader(path))) {
            return parseFromReader(br);
        }
    }

    /** 从多个.pos文件解析并合并历元列表（按文件顺序拼接） */
    public static List<PosEpoch> parse(List<String> paths) throws IOException {
        List<PosEpoch> all = new ArrayList<>();
        for (String path : paths) {
            all.addAll(parse(path));
        }
        return all;
    }

    /** 从Reader解析历元列表（适用于HTTP响应、InputStream等任意来源） */
    public static List<PosEpoch> parseFromReader(Reader reader) throws IOException {
        List<PosEpoch> epochs = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(reader)) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("%") || line.startsWith("#")) {
                    continue;
                }
                PosEpoch ep = parseLine(line);
                if (ep != null) {
                    epochs.add(ep);
                }
            }
        }
        return epochs;
    }

    /** 从字符串行列表解析历元列表（适用于消息队列、数据库CLOB等已加载内容） */
    public static List<PosEpoch> parseFromLines(List<String> lines) {
        List<PosEpoch> epochs = new ArrayList<>();
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("%") || line.startsWith("#")) {
                continue;
            }
            PosEpoch ep = parseLine(line);
            if (ep != null) {
                epochs.add(ep);
            }
        }
        return epochs;
    }

    private static PosEpoch parseLine(String line) {
        String[] parts = line.split("\\s+");
        if (parts.length < 7) {
            return null;
        }
        try {
            String time = parts[0] + " " + parts[1];
            double e = Double.parseDouble(parts[2]);
            double n = Double.parseDouble(parts[3]);
            double u = Double.parseDouble(parts[4]);
            int q = Integer.parseInt(parts[5]);
            int ns = Integer.parseInt(parts[6]);

            double sde = 0, sdn = 0, sdu = 0;
            double sden = 0, sdnu = 0, sdue = 0;

            if (parts.length >= 10) {
                sde = Double.parseDouble(parts[7]);
                sdn = Double.parseDouble(parts[8]);
                sdu = Double.parseDouble(parts[9]);
            }
            if (parts.length >= 13) {
                sden = Double.parseDouble(parts[10]);
                sdnu = Double.parseDouble(parts[11]);
                sdue = Double.parseDouble(parts[12]);
            }

            return new PosEpoch(time, e, n, u, q, ns, sde, sdn, sdu, sden, sdnu, sdue);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    /**
     * 从PosEpoch构建3x3协方差矩阵（行优先一维数组，长度9）。
     * 对角元素为方差(sde², sdn², sdu²)，非对角元素为协方差(sden, sdnu, sdue)。
     * 非固定解(Q!=1)的协方差放大nonFixScale倍以降低权重。
     */
    public static double[] buildCovMatrix(PosEpoch ep, double nonFixScale) {
        double scale = (ep.q != 1) ? nonFixScale : 1.0;
        double[] Q = new double[9];
        Q[0] = ep.sde * ep.sde * scale;
        Q[4] = ep.sdn * ep.sdn * scale;
        Q[8] = ep.sdu * ep.sdu * scale;
        Q[1] = Q[3] = ep.sden * scale;
        Q[5] = Q[7] = ep.sdnu * scale;
        Q[2] = Q[6] = ep.sdue * scale;
        return Q;
    }

    /** 构建协方差矩阵，非固定解方差放大10000倍(标准差放大100倍) */
    public static double[] buildCovMatrix(PosEpoch ep) {
        return buildCovMatrix(ep, 100.0 * 100.0);
    }

    /**
     * 简单反转配对RTS平滑。
     * 将历元序列反转作为后向数据，epoch[i]与epoch[N-1-i]配对，
     * 调用{@link Smoother#smooth}做协方差加权融合。
     * <p>适用于全固定解数据；对含浮点解的数据可能恶化结果（前后向完全相关）。</p>
     */
    public static List<SmoothedEpoch> smooth(List<PosEpoch> epochs) {
        return smooth(epochs, 100.0 * 100.0);
    }

    /** 简单反转配对RTS平滑，自定义非固定解方差放大倍数 */
    public static List<SmoothedEpoch> smooth(List<PosEpoch> epochs, double nonFixScale) {
        List<SmoothedEpoch> results = new ArrayList<>();
        int n = epochs.size();
        if (n == 0) {
            return results;
        }

        for (int i = 0; i < n; i++) {
            PosEpoch fwd = epochs.get(i);
            PosEpoch bwd = epochs.get(n - 1 - i);

            double[] xf = {fwd.e, fwd.n, fwd.u};
            double[] xb = {bwd.e, bwd.n, bwd.u};
            double[] Qf = buildCovMatrix(fwd, nonFixScale);
            double[] Qb = buildCovMatrix(bwd, nonFixScale);
            double[] xs = new double[3];
            double[] Qs = new double[9];

            SmoothedEpoch se = new SmoothedEpoch();
            se.time = fwd.time;
            se.q = fwd.q;
            se.ns = fwd.ns;

            int ret = Smoother.smooth(xf, Qf, xb, Qb, 3, xs, Qs);
            if (ret == 1) {
                se.e = xs[0];
                se.n = xs[1];
                se.u = xs[2];
                se.smoothed = true;
            } else {
                se.e = (xf[0] + xb[0]) / 2.0;
                se.n = (xf[1] + xb[1]) / 2.0;
                se.u = (xf[2] + xb[2]) / 2.0;
                se.smoothed = false;
            }

            results.add(se);
        }

        return results;
    }

    /** 从.pos文件解析并执行简单反转配对RTS平滑 */
    public static List<SmoothedEpoch> smooth(String posFilePath) throws IOException {
        return smooth(parse(posFilePath));
    }

    /** 从多个.pos文件合并解析并执行简单反转配对RTS平滑 */
    public static List<SmoothedEpoch> smoothFiles(List<String> posFilePaths) throws IOException {
        return smooth(parse(posFilePaths));
    }

    /** 卡尔曼RTS平滑，默认参数(processNoiseStd=0.001, nonFixScale=10000) */
    public static List<SmoothedEpoch> smoothKalman(List<PosEpoch> epochs) {
        return smoothKalman(epochs, 0.001, 100.0 * 100.0);
    }

    /** 卡尔曼RTS平滑，自定义过程噪声标准差 */
    public static List<SmoothedEpoch> smoothKalman(List<PosEpoch> epochs, double processNoiseStd) {
        return smoothKalman(epochs, processNoiseStd, 100.0 * 100.0);
    }

    /**
     * 卡尔曼滤波RTS平滑（推荐）。
     * <p>将.pos中的位置估计作为伪观测，分别执行前向和后向卡尔曼滤波，
     * 再用{@link Smoother#smooth}融合。浮点解历元通过协方差放大自动降权。</p>
     * <p><b>注意</b>：.pos中的值已是RTK前向滤波结果，此方法为"滤波之滤波"，
     * 数学上不严格，存在过度平滑风险，适用于静态基线场景评估。</p>
     *
     * @param epochs 历元列表
     * @param processNoiseStd 过程噪声标准差(m)，控制滤波器记忆长度，越小平滑越强
     * @param nonFixScale 非固定解方差放大倍数
     */
    public static List<SmoothedEpoch> smoothKalman(List<PosEpoch> epochs, double processNoiseStd, double nonFixScale) {
        int n = epochs.size();
        List<SmoothedEpoch> results = new ArrayList<>();
        if (n == 0) {
            return results;
        }

        double qVal = processNoiseStd * processNoiseStd;
        SimpleMatrix Qproc = new SimpleMatrix(3, 3);
        Qproc.set(0, 0, qVal);
        Qproc.set(1, 1, qVal);
        Qproc.set(2, 2, qVal);

        SimpleMatrix[] xFwd = new SimpleMatrix[n];
        SimpleMatrix[] PFwd = new SimpleMatrix[n];

        SimpleMatrix z0 = vec3(epochs.get(0).e, epochs.get(0).n, epochs.get(0).u);
        SimpleMatrix R0 = mat3(buildCovMatrix(epochs.get(0), nonFixScale));
        xFwd[0] = z0.copy();
        PFwd[0] = R0.copy();

        for (int i = 1; i < n; i++) {
            SimpleMatrix xPred = xFwd[i - 1].copy();
            SimpleMatrix PPred = PFwd[i - 1].plus(Qproc);

            PosEpoch ep = epochs.get(i);
            SimpleMatrix z = vec3(ep.e, ep.n, ep.u);
            SimpleMatrix R = mat3(buildCovMatrix(ep, nonFixScale));

            SimpleMatrix S = PPred.plus(R);
            SimpleMatrix K;
            try {
                K = PPred.mult(S.invert());
            } catch (Exception e) {
                xFwd[i] = xPred.copy();
                PFwd[i] = PPred.copy();
                continue;
            }

            SimpleMatrix innov = z.minus(xPred);
            xFwd[i] = xPred.plus(K.mult(innov));
            PFwd[i] = SimpleMatrix.identity(3).minus(K).mult(PPred);
        }

        SimpleMatrix[] xBwd = new SimpleMatrix[n];
        SimpleMatrix[] PBwd = new SimpleMatrix[n];

        SimpleMatrix zN = vec3(epochs.get(n - 1).e, epochs.get(n - 1).n, epochs.get(n - 1).u);
        SimpleMatrix RN = mat3(buildCovMatrix(epochs.get(n - 1), nonFixScale));
        xBwd[n - 1] = zN.copy();
        PBwd[n - 1] = RN.copy();

        for (int i = n - 2; i >= 0; i--) {
            SimpleMatrix xPred = xBwd[i + 1].copy();
            SimpleMatrix PPred = PBwd[i + 1].plus(Qproc);

            PosEpoch ep = epochs.get(i);
            SimpleMatrix z = vec3(ep.e, ep.n, ep.u);
            SimpleMatrix R = mat3(buildCovMatrix(ep, nonFixScale));

            SimpleMatrix S = PPred.plus(R);
            SimpleMatrix K;
            try {
                K = PPred.mult(S.invert());
            } catch (Exception e) {
                xBwd[i] = xPred.copy();
                PBwd[i] = PPred.copy();
                continue;
            }

            SimpleMatrix innov = z.minus(xPred);
            xBwd[i] = xPred.plus(K.mult(innov));
            PBwd[i] = SimpleMatrix.identity(3).minus(K).mult(PPred);
        }

        for (int i = 0; i < n; i++) {
            PosEpoch ep = epochs.get(i);

            double[] xf = {xFwd[i].get(0, 0), xFwd[i].get(1, 0), xFwd[i].get(2, 0)};
            double[] Qf = arr9(PFwd[i]);
            double[] xb = {xBwd[i].get(0, 0), xBwd[i].get(1, 0), xBwd[i].get(2, 0)};
            double[] Qb = arr9(PBwd[i]);
            double[] xs = new double[3];
            double[] Qs = new double[9];

            SmoothedEpoch se = new SmoothedEpoch();
            se.time = ep.time;
            se.q = ep.q;
            se.ns = ep.ns;

            int ret = Smoother.smooth(xf, Qf, xb, Qb, 3, xs, Qs);
            if (ret == 1) {
                se.e = xs[0];
                se.n = xs[1];
                se.u = xs[2];
                se.smoothed = true;
            } else {
                se.e = (xf[0] + xb[0]) / 2.0;
                se.n = (xf[1] + xb[1]) / 2.0;
                se.u = (xf[2] + xb[2]) / 2.0;
                se.smoothed = false;
            }

            results.add(se);
        }

        return results;
    }

    /** 从.pos文件解析并执行卡尔曼RTS平滑 */
    public static List<SmoothedEpoch> smoothKalman(String posFilePath) throws IOException {
        return smoothKalman(parse(posFilePath));
    }

    /** 从.pos文件解析并执行卡尔曼RTS平滑，自定义过程噪声标准差 */
    public static List<SmoothedEpoch> smoothKalman(String posFilePath, double processNoiseStd) throws IOException {
        return smoothKalman(parse(posFilePath), processNoiseStd);
    }

    /** 从多个.pos文件合并解析并执行卡尔曼RTS平滑 */
    public static List<SmoothedEpoch> smoothKalmanFiles(List<String> posFilePaths) throws IOException {
        return smoothKalman(parse(posFilePaths));
    }

    /** 从多个.pos文件合并解析并执行卡尔曼RTS平滑，自定义过程噪声标准差 */
    public static List<SmoothedEpoch> smoothKalmanFiles(List<String> posFilePaths, double processNoiseStd) throws IOException {
        return smoothKalman(parse(posFilePaths), processNoiseStd);
    }

    /**
     * 统一处理入口：根据配置选择平滑模式，返回平滑结果及统计摘要。
     *
     * @param epochs 历元列表
     * @param config 平滑配置
     * @return 包含平滑后历元列表和统计摘要的结果对象
     */
    public static SmoothResult process(List<PosEpoch> epochs, SmoothConfig config) {
        List<SmoothedEpoch> smoothed;
        if (config.useKalman) {
            smoothed = smoothKalman(epochs, config.processNoiseStd, config.nonFixVarianceScale);
        } else {
            smoothed = smooth(epochs, config.nonFixVarianceScale);
        }
        return new SmoothResult(smoothed);
    }

    /** 从.pos文件解析并统一处理 */
    public static SmoothResult process(String posFilePath, SmoothConfig config) throws IOException {
        return process(parse(posFilePath), config);
    }

    /** 从多个.pos文件合并解析并统一处理 */
    public static SmoothResult processFiles(List<String> posFilePaths, SmoothConfig config) throws IOException {
        return process(parse(posFilePaths), config);
    }

    private static SimpleMatrix vec3(double a, double b, double c) {
        SimpleMatrix v = new SimpleMatrix(3, 1);
        v.set(0, 0, a);
        v.set(1, 0, b);
        v.set(2, 0, c);
        return v;
    }

    private static SimpleMatrix mat3(double[] arr) {
        SimpleMatrix m = new SimpleMatrix(3, 3);
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                m.set(i, j, arr[i * 3 + j]);
            }
        }
        return m;
    }

    private static double[] arr9(SimpleMatrix m) {
        double[] a = new double[9];
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 3; j++) {
                a[i * 3 + j] = m.get(i, j);
            }
        }
        return a;
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1) {
            System.out.println("Usage: PosFileRtsSmoother [--kalman] [--simple] [--q=STD] [--scale=S] <pos_file> [pos_file2 ...]");
            System.out.println("  --kalman      : use Kalman-based RTS smoother (default)");
            System.out.println("  --simple      : use simple reverse-pair RTS smoother");
            System.out.println("  --q=STD       : process noise std dev in m (default: 0.001)");
            System.out.println("  --scale=S     : non-fix variance scale (default: 10000)");
            return;
        }

        SmoothConfig config = new SmoothConfig();
        List<String> filePaths = new ArrayList<>();

        for (String arg : args) {
            if (arg.equals("--kalman")) {
                config.useKalman = true;
            } else if (arg.equals("--simple")) {
                config.useKalman = false;
            } else if (arg.startsWith("--q=")) {
                config.processNoiseStd = Double.parseDouble(arg.substring(4));
            } else if (arg.startsWith("--scale=")) {
                config.nonFixVarianceScale = Double.parseDouble(arg.substring(8));
            } else {
                filePaths.add(arg);
            }
        }

        if (filePaths.isEmpty()) {
            System.out.println("Error: no pos file specified");
            return;
        }

        List<PosEpoch> epochs = parse(filePaths);
        System.out.printf("Parsed %d epochs from %d file(s)%n", epochs.size(), filePaths.size());

        SmoothResult result = process(epochs, config);

        System.out.printf("Mode: %s (processNoiseStd=%.4f, nonFixScale=%.0f)%n",
                config.useKalman ? "Kalman RTS" : "Simple reverse-pair RTS",
                config.processNoiseStd, config.nonFixVarianceScale);
        System.out.printf("Summary: total=%d, fixed=%d, float=%d, smoothed=%d, fallback=%d%n",
                result.totalEpochs, result.fixedCount, result.floatCount,
                result.smoothedCount, result.fallbackCount);
        System.out.printf("Smoothed std: E=%.4fmm  N=%.4fmm  U=%.4fmm  range: E=%.4fmm  N=%.4fmm  U=%.4fmm%n",
                result.eStd * 1000, result.nStd * 1000, result.uStd * 1000,
                result.eRange * 1000, result.nRange * 1000, result.uRange * 1000);

        System.out.println("%  GPST                   e-smoothed(m)  n-smoothed(m)  u-smoothed(m)   Q  ns  smoothed");
        for (SmoothedEpoch se : result.epochs) {
            System.out.printf("%s  %12.4f  %12.4f  %12.4f  %2d  %2d  %s%n",
                    se.time, se.e, se.n, se.u, se.q, se.ns,
                    se.smoothed ? "Y" : "N");
        }
    }
}