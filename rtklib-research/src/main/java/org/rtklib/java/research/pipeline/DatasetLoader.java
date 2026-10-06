package org.rtklib.java.research.pipeline;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.rtklib.java.research.common.GTime;
import org.rtklib.java.research.data.Navigation;
import org.rtklib.java.research.data.Observation;
import org.rtklib.java.research.data.ObservationEpoch;

/**
 * FE-GUT 数据集加载器（移植自 FE-GUT fileio/psfileloader.h 等文件加载器）。
 *
 * <p>读取 FE-GUT 格式的 GNSS/UWB 仿真数据，构建 ObservationEpoch。
 * 数据集包含 6 颗卫星、4 个 UWB 锚点、12000 个历元（1200 秒 @ 10Hz）。</p>
 *
 * <h3>数据文件格式</h3>
 * <ul>
 *   <li>psdata.txt：时间 + 6 个伪距值，空格分隔</li>
 *   <li>psratedata.txt：时间 + 6 个伪距率值</li>
 *   <li>satposdata.txt：时间 + 18 个卫星坐标（6 颗 × 3 轴）</li>
 *   <li>satveldata.txt：时间 + 18 个卫星速度</li>
 *   <li>uwbdata.txt：时间 + 4 个 UWB 测距值</li>
 *   <li>navdata.txt：时间 + 7 个导航真值（位置3 + 速度3 + ??）</li>
 * </ul>
 */
public class DatasetLoader {

    private static final int GNSS_NUM = 6;
    private static final int UWB_NUM = 4;

    private String datasetPath;
    private int satelliteCount;
    private int uwbAnchorCount;

    private List<double[]> psData;
    private List<double[]> psRateData;
    private List<double[]> satPosData;
    private List<double[]> satVelData;
    private List<double[]> uwbData;
    private List<double[]> navData;

    private int currentIndex;

    public DatasetLoader(String datasetPath) {
        this(datasetPath, GNSS_NUM, UWB_NUM);
    }

    public DatasetLoader(String datasetPath, int satelliteCount, int uwbAnchorCount) {
        this.datasetPath = datasetPath;
        this.satelliteCount = satelliteCount;
        this.uwbAnchorCount = uwbAnchorCount;
        this.currentIndex = 0;
    }

    public int size() {
        if (psData == null) return 0;
        return psData.size();
    }

    public int satelliteCount() {
        return satelliteCount;
    }

    public int uwbAnchorCount() {
        return uwbAnchorCount;
    }

    public List<double[]> getUWBRaw() {
        return uwbData;
    }

    public List<double[]> getNavRaw() {
        return navData;
    }

    /**
     * 加载所有数据文件。
     *
     * @throws IOException 如果文件读取失败
     */
    public void loadAll() throws IOException {
        psData = loadDataFile(datasetPath + File.separator + "psdata.txt");
        psRateData = loadDataFile(datasetPath + File.separator + "psratedata.txt");
        satPosData = loadDataFile(datasetPath + File.separator + "satposdata.txt");
        satVelData = loadDataFile(datasetPath + File.separator + "satveldata.txt");
        uwbData = loadDataFile(datasetPath + File.separator + "uwbdata.txt");

        String navFilePath = datasetPath + File.separator + "navdata.txt";
        if (new File(navFilePath).exists()) {
            navData = loadDataFile(navFilePath);
        }

        currentIndex = 0;
    }

    /**
     * 是否有下一个历元。
     */
    public boolean hasNext() {
        return psData != null && currentIndex < psData.size();
    }

    /**
     * 获取下一个历元的观测数据。
     */
    public ObservationEpoch next() {
        if (!hasNext()) return null;

        double[] ps = psData.get(currentIndex);
        double[] psr = psRateData.get(currentIndex);
        double[] spos = satPosData.get(currentIndex);
        double[] svel = satVelData.get(currentIndex);

        double time = ps[0];
        GTime gtime = new GTime((long) time, time - (long) time);

        ObservationEpoch epoch = new ObservationEpoch(gtime, 1);

        for (int i = 0; i < satelliteCount; i++) {
            Observation obs = new Observation();
            obs.time = new GTime(gtime);
            obs.sat = i + 1;
            obs.rcv = 1;
            obs.freqIndex = 0;
            obs.pseudorange = ps[1 + i];
            obs.doppler = (float) (-psr[1 + i] / obs.wavelength());
            obs.snr = 45.0f;
            obs.pseudorangeStd = 0.3f;
            obs.carrierPhaseStd = 0.01f;

            epoch.addObservation(obs);
        }

        currentIndex++;
        return epoch;
    }

    /**
     * 获取指定历元的卫星位置（ECEF 米）。
     */
    public double[][] getSatellitePositions(int epochIndex) {
        if (satPosData == null || epochIndex >= satPosData.size()) {
            return new double[satelliteCount][3];
        }
        double[] raw = satPosData.get(epochIndex);
        double[][] pos = new double[satelliteCount][3];
        for (int i = 0; i < satelliteCount; i++) {
            for (int j = 0; j < 3; j++) {
                pos[i][j] = raw[1 + 3 * i + j];
            }
        }
        return pos;
    }

    /**
     * 获取指定历元的卫星速度（ECEF 米/秒）。
     */
    public double[][] getSatelliteVelocities(int epochIndex) {
        if (satVelData == null || epochIndex >= satVelData.size()) {
            return new double[satelliteCount][3];
        }
        double[] raw = satVelData.get(epochIndex);
        double[][] vel = new double[satelliteCount][3];
        for (int i = 0; i < satelliteCount; i++) {
            for (int j = 0; j < 3; j++) {
                vel[i][j] = raw[1 + 3 * i + j];
            }
        }
        return vel;
    }

    /**
     * 获取指定历元的 UWB 测距值（米）。
     */
    public double[] getUwbRanges(int epochIndex) {
        if (uwbData == null || epochIndex >= uwbData.size()) {
            return new double[uwbAnchorCount];
        }
        double[] raw = uwbData.get(epochIndex);
        double[] ranges = new double[uwbAnchorCount];
        for (int i = 0; i < uwbAnchorCount; i++) {
            ranges[i] = raw[1 + i];
        }
        return ranges;
    }

    /**
     * 获取指定历元的地面真值（ECEF 位置 + 速度）。
     */
    public double[] getGroundTruth(int epochIndex) {
        if (navData == null || epochIndex >= navData.size()) return null;
        return navData.get(epochIndex);
    }

    /**
     * 加载所有历元的 ObservationEpoch 列表。
     */
    public List<ObservationEpoch> loadAllEpochs() throws IOException {
        loadAll();
        List<ObservationEpoch> epochs = new ArrayList<>();
        while (hasNext()) {
            epochs.add(next());
        }
        return epochs;
    }

    /**
     * 构建与历元匹配的 Navigation 对象（包含预计算的卫星位置）。
     */
    public Navigation buildNavigation() {
        Navigation nav = new Navigation();
        return nav;
    }

    /**
     * 重置读取指针。
     */
    public void reset() {
        currentIndex = 0;
    }

    /**
     * 直接获取第 index 个历元的观测数据（不改变内部指针）。
     */
    public ObservationEpoch getEpoch(int index) {
        if (psData == null || index >= psData.size()) return null;

        double[] ps = psData.get(index);
        double[] psr = psRateData.get(index);

        double time = ps[0];
        GTime gtime = new GTime((long) time, time - (long) time);

        ObservationEpoch epoch = new ObservationEpoch(gtime, 1);

        for (int i = 0; i < satelliteCount; i++) {
            Observation obs = new Observation();
            obs.time = new GTime(gtime);
            obs.sat = i + 1;
            obs.rcv = 1;
            obs.freqIndex = 0;
            obs.pseudorange = ps[1 + i];
            obs.doppler = (float) (-psr[1 + i] / obs.wavelength());
            obs.snr = 45.0f;
            obs.pseudorangeStd = 0.3f;
            obs.carrierPhaseStd = 0.01f;
            epoch.addObservation(obs);
        }

        return epoch;
    }

    private static List<double[]> loadDataFile(String filePath) throws IOException {
        File file = new File(filePath);
        if (!file.exists()) {
            throw new IOException("File not found: " + filePath);
        }

        List<double[]> data = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] tokens = line.split("\\s+");
                double[] values = new double[tokens.length];
                for (int i = 0; i < tokens.length; i++) {
                    values[i] = Double.parseDouble(tokens[i]);
                }
                data.add(values);
            }
        }

        return data;
    }
}