package org.rtklib.java.ephemeris;

import org.rtklib.java.data.Nav;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/**
 * GPT3 5°×5°网格数据读取与插值。
 *
 * <p>GPT3（Global Pressure and Temperature 3）提供全球气压、温度、比湿、
 * 温度直减率、大地水准面差距和VMF3映射函数系数（ah, aw）的先验值，
 * 用于VMF3对流层延迟计算。
 *
 * <p>网格规格（对齐 TU Wien 官方 gpt3_5.f90 / gpt3_5.grd）：
 * 36×72 = 2592 个 5°×5° 格元中心，纬度 87.5°~-87.5°、经度 2.5°~357.5°。
 * 文件每行 64 列：lat, lon，随后按 (a0, A1, B1, A2, B2) 五系数组排列：
 * p(3-7), T(8-12), Q(13-17), dT(18-22), undu(23), Hs(24),
 * ah(25-29), aw(30-34), la(35-39), Tm(40-44), Gn_h(45-49), Ge_h(50-54),
 * Gn_w(55-59), Ge_w(60-64)。
 *
 * <p>时间变化：value = a0 + A1·cos(2π·doy/365.25) + B1·sin(2π·doy/365.25)
 * + A2·cos(4π·doy/365.25) + B2·sin(4π·doy/365.25)，相位直接取年积日（无偏移）。
 * 文件读入时按 f90 语义除常数缩放：Q/1000 (g/kg→kg/kg), dT/1000 (mK/m→K/m),
 * ah/1000, aw/1000。
 *
 * <p>插值（f90 逐点一致）：先对每个格点做高程归约（椭高→正高经 undu，
 * 温度按直减率、气压按虚温指数归约到测站高，水汽压经 Askne-Nordius 公式），
 * 再对归约后的量做双线性插值；极区（极距≤2.5°或≥177.5°）用最近邻。
 *
 * <p>文件来源：TU Wien (https://vmf.geo.tuwien.ac.at/codes/gpt3_5.grd)
 *
 * <p>对应C版：RTKLIB未内置GPT3，本模块为v2.2.2新增优化；
 * 解析与插值逻辑对齐官方参考实现 gpt3_5.f90。
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
    private static final int COL_P = 0;
    private static final int COL_T = 5;
    private static final int COL_Q = 10;
    private static final int COL_DT = 15;
    private static final int COL_UNDU = 20;
    private static final int COL_HS = 21;
    private static final int COL_AH = 22;
    private static final int COL_AW = 27;
    private static final int COL_LA = 32;

    // gpt3_5.f90 常数
    private static final double GM = 9.80665;      // mean gravity m/s^2
    private static final double DMTR = 28.965E-3;  // molar mass of dry air kg/mol
    private static final double RG = 8.3143;       // universal gas constant J/K/mol

    // 文件列序（1-based）：lat=1, lon=2, p=3-7, T=8-12, Q=13-17, dT=18-22,
    // undu=23, Hs=24, ah=25-29, aw=30-34, la=35-39
    private static final int FILE_COLS = 64;

    /**
     * 读取GPT3网格文件到Nav数据结构。
     *
     * @param file 本地 gpt3_5.grd 文件路径
     * @param nav  导航数据，网格数据存入nav.gpt3Grid
     * @return 读取成功且完整（2592点）返回true
     */
    public static boolean readGpt3Grid(String file, Nav nav) {
        if (file == null || file.isEmpty()) return false;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            double[][] grid = new double[GPT3_NPOINTS][GPT3_GRID_COLS];
            int count = 0;
            String line;

            while ((line = br.readLine()) != null && count < GPT3_NPOINTS) {
                // 跳过注释行和空行
                if (line.startsWith("%") || line.startsWith("#") || line.trim().isEmpty()) continue;
                String[] tokens = line.trim().split("\\s+");
                if (tokens.length < FILE_COLS) {
                    LOG.warn("GPT3 grid line {} has {} columns, expect >= {}", count + 1, tokens.length, FILE_COLS);
                    return false;
                }

                // p: 3-7 -> 0-4 (Pa), T: 8-12 -> 5-9 (K)
                for (int j = 0; j < 5; j++) {
                    grid[count][COL_P + j] = Double.parseDouble(tokens[2 + j]);
                    grid[count][COL_T + j] = Double.parseDouble(tokens[7 + j]);
                }
                // Q: 13-17 -> 10-14 (g/kg -> kg/kg), dT: 18-22 -> 15-19 (mK/m -> K/m)
                for (int j = 0; j < 5; j++) {
                    grid[count][COL_Q + j] = Double.parseDouble(tokens[12 + j]) / 1000.0;
                    grid[count][COL_DT + j] = Double.parseDouble(tokens[17 + j]) / 1000.0;
                }
                // undu: 23 -> 20 (m), Hs: 24 -> 21 (m)
                grid[count][COL_UNDU] = Double.parseDouble(tokens[22]);
                grid[count][COL_HS] = Double.parseDouble(tokens[23]);
                // ah: 25-29 -> 22-26 (/1000), aw: 30-34 -> 27-31 (/1000)
                for (int j = 0; j < 5; j++) {
                    grid[count][COL_AH + j] = Double.parseDouble(tokens[24 + j]) / 1000.0;
                    grid[count][COL_AW + j] = Double.parseDouble(tokens[29 + j]) / 1000.0;
                }
                // la: 35-39 -> 32-36
                for (int j = 0; j < 5; j++) {
                    grid[count][COL_LA + j] = Double.parseDouble(tokens[34 + j]);
                }
                count++;
            }

            if (count == GPT3_NPOINTS) {
                nav.gpt3Grid = grid;
                nav.gpt3GridLoaded = true;
                LOG.info("GPT3 grid loaded: {} points from {}", count, file);
                return true;
            }
            LOG.warn("GPT3 grid file incomplete: {} points (expect {}), file={}", count, GPT3_NPOINTS, file);
        } catch (IOException e) {
            LOG.warn("GPT3 grid file open error: {}", file);
        } catch (NumberFormatException e) {
            LOG.warn("GPT3 grid file parse error: {}", e.getMessage());
        }
        return false;
    }

    /**
     * 插值GPT3网格，计算测站位置的气压、温度、水汽压和VMF3系数。
     *
     * <p>对齐 gpt3_5.f90：极距/东经定位格元 → 极区最近邻、其余双线性；
     * 双线性在"逐格点高程归约后"的量上进行；谐波相位直接取年积日。
     *
     * @param grid GPT3网格数据（nav.gpt3Grid）
     * @param lat  测站纬度（度，-90~90）
     * @param lon  测站经度（度，-180~180）
     * @param hell 测站椭高（m）
     * @param doy  年积日（1~366，可含小数）
     * @return [气压(hPa), 温度(K), 水汽压(hPa), ah, aw]，失败返回null
     */
    public static double[] interpolateGpt3(double[][] grid, double lat, double lon, double hell, double doy) {
        if (grid == null || grid.length != GPT3_NPOINTS) return null;

        // f90: 极距（度）与 0~360 东经
        double ppod = 90.0 - lat;
        double plon = lon < 0 ? lon + 360.0 : lon;
        ppod = Math.max(0.0, Math.min(180.0, ppod));
        if (plon >= 360.0) plon -= 360.0;

        int ipod = (int) Math.floor((ppod + 5.0) / 5.0);
        int ilon = (int) Math.floor((plon + 5.0) / 5.0);

        // 归一化偏差（相对最近格元中心，[-0.5, 0.5]），f90 在索引钳位前计算
        double diffpod = (ppod - (ipod * 5.0 - 2.5)) / 5.0;
        double difflon = (plon - (ilon * 5.0 - 2.5)) / 5.0;
        if (ipod == 37) ipod = 36;
        if (ilon == 73) ilon = 1;
        if (ilon == 0) ilon = 72;

        // 谐波系数（f90: cosfy/sinfy 年周期, coshy/sinhy 半年周期）
        double w = 2.0 * Math.PI * doy / 365.25;
        double cosfy = Math.cos(w);
        double sinfy = Math.sin(w);
        double coshy = Math.cos(2.0 * w);
        double sinhy = Math.sin(2.0 * w);

        // 极区最近邻
        if (ppod <= 2.5 || ppod >= 177.5) {
            double[] out = new double[5];
            evalPoint(grid[(ipod - 1) * GPT3_GRID_NLON + (ilon - 1)],
                    cosfy, sinfy, coshy, sinhy, hell, out);
            return out;
        }

        // 双线性四角（f90 indx(1..4)，经度环绕 72<->1）
        int ipod1 = ipod + (diffpod >= 0 ? 1 : -1);
        int ilon1 = ilon + (difflon >= 0 ? 1 : -1);
        if (ilon1 == 73) ilon1 = 1;
        if (ilon1 == 0) ilon1 = 72;

        int[] idx = {
                (ipod - 1) * GPT3_GRID_NLON + (ilon - 1),   // (ipod, ilon)
                (ipod1 - 1) * GPT3_GRID_NLON + (ilon - 1),  // (ipod1, ilon)
                (ipod - 1) * GPT3_GRID_NLON + (ilon1 - 1),  // (ipod, ilon1)
                (ipod1 - 1) * GPT3_GRID_NLON + (ilon1 - 1)  // 对角
        };

        double[][] v = new double[4][5];
        for (int i = 0; i < 4; i++) {
            evalPoint(grid[idx[i]], cosfy, sinfy, coshy, sinhy, hell, v[i]);
        }

        // f90 权重：近点权 1-|diff|，远点权 |diff|
        double dnpod1 = Math.abs(diffpod), dnpod2 = 1.0 - dnpod1;
        double dnlon1 = Math.abs(difflon), dnlon2 = 1.0 - dnlon1;

        double[] r = new double[5];
        for (int k = 0; k < 5; k++) {
            double r1 = dnpod2 * v[0][k] + dnpod1 * v[1][k];
            double r2 = dnpod2 * v[2][k] + dnpod1 * v[3][k];
            r[k] = dnlon2 * r1 + dnlon1 * r2;
        }
        return r;
    }

    /**
     * 单格点求值：谐波叠加 + 高程归约（对齐 gpt3_5.f90 L336-380）。
     * 输出 out = {p(hPa), T(K), e(hPa), ah, aw}。
     */
    private static void evalPoint(double[] g, double cosfy, double sinfy, double coshy, double sinhy,
                                  double hell, double[] out) {
        double p0 = harm(g, COL_P, cosfy, sinfy, coshy, sinhy);   // Pa
        double T0 = harm(g, COL_T, cosfy, sinfy, coshy, sinhy);   // K
        double q = harm(g, COL_Q, cosfy, sinfy, coshy, sinhy);    // kg/kg
        double dt = harm(g, COL_DT, cosfy, sinfy, coshy, sinhy);  // K/m
        double la = harm(g, COL_LA, cosfy, sinfy, coshy, sinhy);

        // 椭高→正高，站高-格高
        double redh = (hell - g[COL_UNDU]) - g[COL_HS];

        // 温度（K）
        double t = T0 + dt * redh;

        // 气压：虚温指数归约（Pa -> hPa）
        double tv = T0 * (1.0 + 0.6077 * q);
        double c = GM * DMTR / (RG * tv);
        double p = p0 * Math.exp(-c * redh) / 100.0;

        // 水汽压：Askne and Nordius (1987) 式(14)
        double e0 = q * p0 / (0.622 + 0.378 * q) / 100.0;
        double e = e0 * Math.pow(100.0 * p / p0, la + 1.0);

        double ah = harm(g, COL_AH, cosfy, sinfy, coshy, sinhy);
        double aw = harm(g, COL_AW, cosfy, sinfy, coshy, sinhy);

        out[0] = p;
        out[1] = t;
        out[2] = e;
        out[3] = ah;
        out[4] = aw;
    }

    /** 谐波叠加：a0 + A1·cos(2π·doy/365.25) + B1·sin + A2·cos(4π·doy/365.25) + B2·sin */
    private static double harm(double[] g, int base, double cosfy, double sinfy, double coshy, double sinhy) {
        return g[base] + g[base + 1] * cosfy + g[base + 2] * sinfy
                + g[base + 3] * coshy + g[base + 4] * sinhy;
    }
}
