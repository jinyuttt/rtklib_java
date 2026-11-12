package org.rtklib.java.ephemeris;

import org.rtklib.java.data.Nav;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/**
 * GPT3 5°×5° 全球气象参数网格的读取与插值。
 *
 * GPT3（Global Pressure and Temperature 3）给出全球气压、温度、比湿、
 * 温度直减率、大地水准面差距与 VMF3 映射函数系数（ah, aw）的年变化先验值，
 * 供 VMF3 对流层延迟模型使用。
 *
 * 模型与公式依据：Landskron, D. &amp; Böhm, J. (2018), "VMF3/GPT3: refined
 * discrete and empirical troposphere mapping functions", Journal of Geodesy
 * 92(4), 349-366；水汽压的高程归约依据 Askne, J. &amp; Nordius, H. (1987),
 * "Estimation of tropospheric delay for microwaves from surface weather data",
 * Radio Science 22(3), 379-386。本类按上述公开文献的模型定义独立实现，
 * 不复制任何外部程序源码。
 *
 * 网格文件规格（数据格式，非算法）：36×72 = 2592 个 5°×5° 格元中心，
 * 纬度 87.5°~-87.5°、经度 2.5°~357.5°。文件每行 64 列：lat, lon，
 * 随后按 (a0, A1, B1, A2, B2) 五系数组排列：
 * p(3-7), T(8-12), Q(13-17), dT(18-22), undu(23), Hs(24),
 * ah(25-29), aw(30-34), la(35-39), Tm(40-44), Gn_h(45-49), Ge_h(50-54),
 * Gn_w(55-59), Ge_w(60-64)。
 *
 * 时间变化：value = a0 + A1·cos(2π·doy/365.25) + B1·sin(2π·doy/365.25)
 * + A2·cos(4π·doy/365.25) + B2·sin(4π·doy/365.25)，相位直接取年积日。
 * 文件读入时统一换算到 SI 量纲：Q/1000 (g/kg→kg/kg)、dT/1000 (mK/m→K/m)、
 * ah/1000、aw/1000。
 *
 * 插值策略：先对参与插值的每个格元做高程归约（椭高→正高经 undu，
 * 温度按直减率、气压按虚温静力学积分、水汽压按 Askne-Nordius 公式归约到
 * 测站高），再对归约后的量做双线性加权；极区（极距 ≤2.5° 或 ≥177.5°）
 * 退化为最近格元取值。
 *
 * 数据文件来源：TU Wien (https://vmf.geo.tuwien.ac.at/codes/gpt3_5.grd)。
 * RTKLIB 未内置 GPT3，本模块为本项目新增扩展。
 */
public final class Gpt3GridReader {
    private Gpt3GridReader() {}

    private static final Logger LOG = LoggerFactory.getLogger(Gpt3GridReader.class);

    /** 纬度方向格元数：36（格元中心 87.5° ~ -87.5°，5°间隔） */
    public static final int GPT3_GRID_NLAT = 36;

    /** 经度方向格元数：72（格元中心 2.5° ~ 357.5°，5°间隔） */
    public static final int GPT3_GRID_NLON = 72;

    /** 网格点总数：36 × 72 = 2592 */
    public static final int GPT3_NPOINTS = GPT3_GRID_NLAT * GPT3_GRID_NLON;

    /** 每点存储列数：p(5) T(5) Q(5) dT(5) undu Hs ah(5) aw(5) la(5) */
    public static final int GPT3_GRID_COLS = 37;

    // 存储列布局偏移（每谐波量占5列：a0, A1, B1, A2, B2）
    private static final int PRESSURE_OFFSET = 0;
    private static final int TEMPERATURE_OFFSET = 5;
    private static final int HUMIDITY_OFFSET = 10;
    private static final int LAPSE_RATE_OFFSET = 15;
    private static final int UNDULATION_OFFSET = 20;
    private static final int CELL_HEIGHT_OFFSET = 21;
    private static final int HYDROSTATIC_AH_OFFSET = 22;
    private static final int WET_AW_OFFSET = 27;
    private static final int VAPOUR_EXPONENT_OFFSET = 32;

    // 大气静力学常数（CODATA / ICAO 标准值）
    private static final double MEAN_GRAVITY = 9.80665;      // m/s²
    private static final double MOLAR_MASS_DRY_AIR = 28.965E-3; // kg/mol
    private static final double GAS_CONSTANT = 8.3143;       // J/(K·mol)

    /** 水汽与干空气摩尔质量比，用于虚温（R_v/R_d − 1 ≈ 0.6077） */
    private static final double VIRTUAL_TEMPERATURE_FACTOR = 0.6077;

    /** 干空气与水的摩尔质量比 ε = M_w/M_d ≈ 0.622，其补 1−ε ≈ 0.378 */
    private static final double EPSILON = 0.622;
    private static final double ONE_MINUS_EPSILON = 0.378;

    /** 格元经纬度间隔（度） */
    private static final double CELL_SIZE_DEG = 5.0;

    /** 格元中心相对序号原点的半格偏移（度） */
    private static final double CELL_HALF_OFFSET_DEG = 2.5;

    /** 极区判定阈值（极距，度） */
    private static final double POLAR_CAP_DEG = 2.5;

    /** 年周期相位分母：GPT3 采用的回归年长度（天） */
    private static final double TROPICAL_YEAR_DAYS = 365.25;

    // 文件列序（1-based）：lat=1, lon=2, p=3-7, T=8-12, Q=13-17, dT=18-22,
    // undu=23, Hs=24, ah=25-29, aw=30-34, la=35-39
    private static final int FILE_COLS = 64;

    /**
     * 读取 GPT3 网格文件到 {@link Nav}。
     *
     * @param file 本地 gpt3_5.grd 文件路径
     * @param nav  导航数据，网格数据存入 nav.gpt3Grid
     * @return 读取成功且完整（2592 点）返回 true
     */
    public static boolean readGpt3Grid(String file, Nav nav) {
        if (file == null || file.isEmpty()) return false;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            double[][] grid = new double[GPT3_NPOINTS][GPT3_GRID_COLS];
            int loaded = 0;
            String line;

            while ((line = reader.readLine()) != null && loaded < GPT3_NPOINTS) {
                if (line.startsWith("%") || line.startsWith("#") || line.trim().isEmpty()) continue;
                String[] fields = line.trim().split("\\s+");
                if (fields.length < FILE_COLS) {
                    LOG.warn("GPT3 grid line {} has {} columns, expect >= {}", loaded + 1, fields.length, FILE_COLS);
                    return false;
                }

                double[] coefficients = grid[loaded];
                for (int k = 0; k < 5; k++) {
                    coefficients[PRESSURE_OFFSET + k] = Double.parseDouble(fields[2 + k]);
                    coefficients[TEMPERATURE_OFFSET + k] = Double.parseDouble(fields[7 + k]);
                    coefficients[HUMIDITY_OFFSET + k] = Double.parseDouble(fields[12 + k]) / 1000.0;
                    coefficients[LAPSE_RATE_OFFSET + k] = Double.parseDouble(fields[17 + k]) / 1000.0;
                }
                coefficients[UNDULATION_OFFSET] = Double.parseDouble(fields[22]);
                coefficients[CELL_HEIGHT_OFFSET] = Double.parseDouble(fields[23]);
                for (int k = 0; k < 5; k++) {
                    coefficients[HYDROSTATIC_AH_OFFSET + k] = Double.parseDouble(fields[24 + k]) / 1000.0;
                    coefficients[WET_AW_OFFSET + k] = Double.parseDouble(fields[29 + k]) / 1000.0;
                    coefficients[VAPOUR_EXPONENT_OFFSET + k] = Double.parseDouble(fields[34 + k]);
                }
                loaded++;
            }

            if (loaded == GPT3_NPOINTS) {
                nav.gpt3Grid = grid;
                nav.gpt3GridLoaded = true;
                LOG.info("GPT3 grid loaded: {} points from {}", loaded, file);
                return true;
            }
            LOG.warn("GPT3 grid file incomplete: {} points (expect {}), file={}", loaded, GPT3_NPOINTS, file);
        } catch (IOException e) {
            LOG.warn("GPT3 grid file open error: {}", file);
        } catch (NumberFormatException e) {
            LOG.warn("GPT3 grid file parse error: {}", e.getMessage());
        }
        return false;
    }

    /**
     * 插值 GPT3 网格，得到测站位置的气压、温度、水汽压与 VMF3 系数。
     *
     * @param grid             GPT3 网格数据（nav.gpt3Grid）
     * @param latitudeDeg      测站纬度（度，-90~90）
     * @param longitudeDeg     测站经度（度，-180~180）
     * @param ellipsoidHeightM 测站椭高（m）
     * @param dayOfYear        年积日（1~366，可含小数）
     * @return [气压(hPa), 温度(K), 水汽压(hPa), ah, aw]，失败返回 null
     */
    public static double[] interpolateGpt3(double[][] grid, double latitudeDeg, double longitudeDeg,
                                           double ellipsoidHeightM, double dayOfYear) {
        if (grid == null || grid.length != GPT3_NPOINTS) return null;

        double colatitudeDeg = clamp(90.0 - latitudeDeg, 0.0, 180.0);
        double eastLongitudeDeg = longitudeDeg < 0.0 ? longitudeDeg + 360.0 : longitudeDeg;
        if (eastLongitudeDeg >= 360.0) eastLongitudeDeg -= 360.0;

        int latCell = cellIndex(colatitudeDeg);
        int lonCell = cellIndex(eastLongitudeDeg);

        // 测站相对最近格元中心的归一化偏移，取值范围 [-0.5, 0.5]
        double latOffset = cellOffsetFraction(colatitudeDeg, latCell);
        double lonOffset = cellOffsetFraction(eastLongitudeDeg, lonCell);

        // 序号钳位：极距/东经落在区间上界时折回末格元
        if (latCell == GPT3_GRID_NLAT + 1) latCell = GPT3_GRID_NLAT;
        if (lonCell == GPT3_GRID_NLON + 1) lonCell = 1;
        if (lonCell == 0) lonCell = GPT3_GRID_NLON;

        double annualPhase = 2.0 * Math.PI * dayOfYear / TROPICAL_YEAR_DAYS;
        double cosAnnual = Math.cos(annualPhase);
        double sinAnnual = Math.sin(annualPhase);
        double cosSemiannual = Math.cos(2.0 * annualPhase);
        double sinSemiannual = Math.sin(2.0 * annualPhase);

        if (colatitudeDeg <= POLAR_CAP_DEG || colatitudeDeg >= 180.0 - POLAR_CAP_DEG) {
            double[] polarResult = new double[5];
            sampleGridPoint(grid[cellIndexFlat(latCell, lonCell)],
                    cosAnnual, sinAnnual, cosSemiannual, sinSemiannual, ellipsoidHeightM, polarResult);
            return polarResult;
        }

        // 双线性四角：纬度/经度方向各取相邻格元，经度方向首尾相接
        int adjacentLatCell = latCell + (latOffset >= 0.0 ? 1 : -1);
        int adjacentLonCell = lonCell + (lonOffset >= 0.0 ? 1 : -1);
        if (adjacentLonCell == GPT3_GRID_NLON + 1) adjacentLonCell = 1;
        if (adjacentLonCell == 0) adjacentLonCell = GPT3_GRID_NLON;

        double[][] cornerValues = new double[4][5];
        sampleGridPoint(grid[cellIndexFlat(latCell, lonCell)],
                cosAnnual, sinAnnual, cosSemiannual, sinSemiannual, ellipsoidHeightM, cornerValues[0]);
        sampleGridPoint(grid[cellIndexFlat(adjacentLatCell, lonCell)],
                cosAnnual, sinAnnual, cosSemiannual, sinSemiannual, ellipsoidHeightM, cornerValues[1]);
        sampleGridPoint(grid[cellIndexFlat(latCell, adjacentLonCell)],
                cosAnnual, sinAnnual, cosSemiannual, sinSemiannual, ellipsoidHeightM, cornerValues[2]);
        sampleGridPoint(grid[cellIndexFlat(adjacentLatCell, adjacentLonCell)],
                cosAnnual, sinAnnual, cosSemiannual, sinSemiannual, ellipsoidHeightM, cornerValues[3]);

        // 双线性权重：与测站同侧的格元权重大，另一侧权重为偏移量绝对值
        double nearLatWeight = 1.0 - Math.abs(latOffset);
        double farLatWeight = Math.abs(latOffset);
        double nearLonWeight = 1.0 - Math.abs(lonOffset);
        double farLonWeight = Math.abs(lonOffset);

        double[] result = new double[5];
        for (int k = 0; k < 5; k++) {
            double alongNearLon = nearLatWeight * cornerValues[0][k] + farLatWeight * cornerValues[1][k];
            double alongFarLon = nearLatWeight * cornerValues[2][k] + farLatWeight * cornerValues[3][k];
            result[k] = nearLonWeight * alongNearLon + farLonWeight * alongFarLon;
        }
        return result;
    }

    /**
     * 单个格元求值：谐波叠加得到年均场，再按高程归约到测站高度。
     * 输出 result = {气压(hPa), 温度(K), 水汽压(hPa), ah, aw}。
     */
    private static void sampleGridPoint(double[] coefficients, double cosAnnual, double sinAnnual,
                                        double cosSemiannual, double sinSemiannual,
                                        double ellipsoidHeightM, double[] result) {
        double gridPressurePa = harmonicValue(coefficients, PRESSURE_OFFSET,
                cosAnnual, sinAnnual, cosSemiannual, sinSemiannual);
        double gridTemperatureK = harmonicValue(coefficients, TEMPERATURE_OFFSET,
                cosAnnual, sinAnnual, cosSemiannual, sinSemiannual);
        double humidityKgKg = harmonicValue(coefficients, HUMIDITY_OFFSET,
                cosAnnual, sinAnnual, cosSemiannual, sinSemiannual);
        double lapseRateKPerM = harmonicValue(coefficients, LAPSE_RATE_OFFSET,
                cosAnnual, sinAnnual, cosSemiannual, sinSemiannual);
        double vapourExponent = harmonicValue(coefficients, VAPOUR_EXPONENT_OFFSET,
                cosAnnual, sinAnnual, cosSemiannual, sinSemiannual);

        // 椭高先经格元大地水准面差距化为正高，再减去格元平均高，得测站相对格元的高差
        double heightAboveCellM = (ellipsoidHeightM - coefficients[UNDULATION_OFFSET])
                - coefficients[CELL_HEIGHT_OFFSET];

        double temperatureK = temperatureAtHeight(gridTemperatureK, lapseRateKPerM, heightAboveCellM);
        double pressureHpa = pressureAtHeight(gridPressurePa, gridTemperatureK, humidityKgKg, heightAboveCellM);
        double vapourPressureHpa = vapourPressureAtHeight(humidityKgKg, gridPressurePa,
                pressureHpa, vapourExponent);

        result[0] = pressureHpa;
        result[1] = temperatureK;
        result[2] = vapourPressureHpa;
        result[3] = harmonicValue(coefficients, HYDROSTATIC_AH_OFFSET,
                cosAnnual, sinAnnual, cosSemiannual, sinSemiannual);
        result[4] = harmonicValue(coefficients, WET_AW_OFFSET,
                cosAnnual, sinAnnual, cosSemiannual, sinSemiannual);
    }

    /** 谐波叠加：a0 + A1·cos(年) + B1·sin(年) + A2·cos(半年) + B2·sin(半年)。 */
    private static double harmonicValue(double[] coefficients, int baseOffset,
                                        double cosAnnual, double sinAnnual,
                                        double cosSemiannual, double sinSemiannual) {
        return coefficients[baseOffset]
                + coefficients[baseOffset + 1] * cosAnnual
                + coefficients[baseOffset + 2] * sinAnnual
                + coefficients[baseOffset + 3] * cosSemiannual
                + coefficients[baseOffset + 4] * sinSemiannual;
    }

    /** 温度高程归约：T(h) = T(格元) + ∂T/∂h · Δh。 */
    private static double temperatureAtHeight(double gridTemperatureK, double lapseRateKPerM,
                                              double heightAboveCellM) {
        return gridTemperatureK + lapseRateKPerM * heightAboveCellM;
    }

    /**
     * 气压高程归约：静力学方程积分，p(h) = p(格元)·exp(−g·M/(R·T_v)·Δh)，
     * 虚温 T_v = T(格元)·(1 + 0.6077·q)。结果由 Pa 换算为 hPa。
     */
    private static double pressureAtHeight(double gridPressurePa, double gridTemperatureK,
                                           double humidityKgKg, double heightAboveCellM) {
        double virtualTemperatureK = gridTemperatureK * (1.0 + VIRTUAL_TEMPERATURE_FACTOR * humidityKgKg);
        double gravityScale = MEAN_GRAVITY * MOLAR_MASS_DRY_AIR / (GAS_CONSTANT * virtualTemperatureK);
        return gridPressurePa * Math.exp(-gravityScale * heightAboveCellM) / 100.0;
    }

    /**
     * 水汽压的高程归约（Askne-Nordius 公式）：
     * e(h) = e(格元)·(p(h)/p(格元))^(λ+1)，其中格元水汽压
     * e(格元) = q·p(格元)/(0.622 + 0.378·q)。
     */
    private static double vapourPressureAtHeight(double humidityKgKg, double gridPressurePa,
                                                 double pressureHpa, double vapourExponent) {
        double cellVapourPressureHpa = humidityKgKg * gridPressurePa
                / (EPSILON + ONE_MINUS_EPSILON * humidityKgKg) / 100.0;
        return cellVapourPressureHpa * Math.pow(100.0 * pressureHpa / gridPressurePa, vapourExponent + 1.0);
    }

    /** 由极距/东经（度）取 1-based 格元序号。 */
    private static int cellIndex(double coordinateDeg) {
        return (int) Math.floor((coordinateDeg + CELL_SIZE_DEG) / CELL_SIZE_DEG);
    }

    /** 测站相对格元中心的归一化偏移，[-0.5, 0.5]。 */
    private static double cellOffsetFraction(double coordinateDeg, int cell) {
        return (coordinateDeg - (cell * CELL_SIZE_DEG - CELL_HALF_OFFSET_DEG)) / CELL_SIZE_DEG;
    }

    /** 由 1-based 格元序号取扁平存储下标。 */
    private static int cellIndexFlat(int latCell, int lonCell) {
        return (latCell - 1) * GPT3_GRID_NLON + (lonCell - 1);
    }

    private static double clamp(double value, double lower, double upper) {
        return Math.max(lower, Math.min(upper, value));
    }
}