package org.rtklib.java.ephemeris;

import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.Nav;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/**
 * GPT3 5°×5°网格数据读取与插值。
 *
 * <p>GPT3（Global Pressure and Temperature 3）提供全球气压、温度、水汽压
 * 和VMF3映射函数系数（ah, aw）的先验值，用于VMF3对流层延迟计算。
 *
 * <p>网格规格：纬度37点（-90°~90°，5°间隔）× 经度73点（-180°~180°，5°间隔）= 2701点
 * 每个网格点11列：p_mean, p_cos, p_sin, T_mean, T_cos, T_sin, Qs_mean, Qs_cos, Qs_sin, ah, aw
 * 其中p=气压(mbar), T=温度(K), Qs=比湿, ah/aw=VMF3干/湿映射函数系数
 *
 * <p>文件来源：TU Wien (https://vmf.geo.tuwien.ac.at/codes/gpt3_5.grd)
 * 文件大小：~5MB，静态表，只需下载一次
 *
 * <p>对应C版：RTKLIB未内置GPT3，本模块为v2.2.2新增优化
 */
public final class Gpt3GridReader {
    private Gpt3GridReader() {}

    private static final Logger LOG = LoggerFactory.getLogger(Gpt3GridReader.class);

    /** 纬度方向网格点数：37（-90°, -85°, ..., 85°, 90°） */
    public static final int GPT3_GRID_5DEG_LAT = 37;

    /** 经度方向网格点数：73（-180°, -175°, ..., 175°, 180°） */
    public static final int GPT3_GRID_5DEG_LON = 73;

    /** 每个网格点的数据列数：p(A0,A1,A2), T(A0,A1,A2), Qs(A0,A1,A2), ah, aw */
    public static final int GPT3_GRID_COLS = 11;

    /**
     * 读取GPT3网格文件到Nav数据结构。
     *
     * @param file 本地文件路径（由ProductDownloader.downloadGpt3Grid()下载）
     * @param nav  导航数据，网格数据存入nav.gpt3Grid
     * @return 读取成功返回true
     */
    public static boolean readGpt3Grid(String file, Nav nav) {
        if (file == null || file.isEmpty()) return false;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            double[][] grid = new double[GPT3_GRID_5DEG_LAT * GPT3_GRID_5DEG_LON][GPT3_GRID_COLS];
            int count = 0;

            while ((line = br.readLine()) != null) {
                // 跳过注释行和空行
                if (line.startsWith("%") || line.startsWith("#") || line.trim().isEmpty()) continue;
                String[] tokens = line.trim().split("\\s+");
                if (tokens.length < GPT3_GRID_COLS) continue;
                if (count >= GPT3_GRID_5DEG_LAT * GPT3_GRID_5DEG_LON) break;

                for (int j = 0; j < GPT3_GRID_COLS; j++) {
                    grid[count][j] = Double.parseDouble(tokens[j]);
                }
                count++;
            }

            if (count > 0) {
                nav.gpt3Grid = new double[count][GPT3_GRID_COLS];
                System.arraycopy(grid, 0, nav.gpt3Grid, 0, count);
                nav.gpt3GridLoaded = true;
                LOG.info("GPT3 grid loaded: {} points from {}", count, file);
                return true;
            }
        } catch (IOException e) {
            LOG.warn("GPT3 grid file open error: {}", file);
        } catch (NumberFormatException e) {
            LOG.warn("GPT3 grid file parse error: {}", e.getMessage());
        }
        return false;
    }

    /**
     * 双线性插值GPT3网格，计算测站位置的气压、温度、水汽压和VMF3系数。
     *
     * <p>插值步骤：
     * 1. 找到(lat, lon)所在的5°×5°网格单元
     * 2. 对11列数据分别双线性插值
     * 3. 对p/T/Qs应用年际正弦/余弦周期项（参考历元：DOY=28）
     * 4. 高度改正：p *= (1-0.0000226*h)^5.225, e = Qs/100 * exp(-0.000639*h)
     *
     * @param grid GPT3网格数据（nav.gpt3Grid）
     * @param lat  测站纬度（度，-90~90）
     * @param lon  测站经度（度，-180~180）
     * @param hell 测站椭高（m）
     * @param doy  年积日（1~366）
     * @return [气压(mbar), 温度(K), 水汽压(mbar), ah(干映射系数), aw(湿映射系数)]，失败返回null
     */
    public static double[] interpolateGpt3(double[][] grid, double lat, double lon, double hell, double doy) {
        if (grid == null || grid.length == 0) return null;

        // 网格参数
        double latMin = -90.0, latMax = 90.0;
        double lonMin = -180.0, lonMax = 180.0;
        double dLat = (latMax - latMin) / (GPT3_GRID_5DEG_LAT - 1);
        double dLon = (lonMax - lonMin) / (GPT3_GRID_5DEG_LON - 1);

        // 确定网格索引和插值因子
        int iLat = (int) ((lat - latMin) / dLat);
        int iLon = (int) ((lon - lonMin) / dLon);
        iLat = Math.max(0, Math.min(GPT3_GRID_5DEG_LAT - 2, iLat));
        iLon = Math.max(0, Math.min(GPT3_GRID_5DEG_LON - 2, iLon));

        double fLat = (lat - (latMin + iLat * dLat)) / dLat;
        double fLon = (lon - (lonMin + iLon * dLon)) / dLon;
        fLat = Math.max(0.0, Math.min(1.0, fLat));
        fLon = Math.max(0.0, Math.min(1.0, fLon));

        // 四角网格点索引（按行优先：iLat * NLON + iLon）
        int[] idx = {
            iLat * GPT3_GRID_5DEG_LON + iLon,           // 左下
            iLat * GPT3_GRID_5DEG_LON + iLon + 1,       // 右下
            (iLat + 1) * GPT3_GRID_5DEG_LON + iLon,     // 左上
            (iLat + 1) * GPT3_GRID_5DEG_LON + iLon + 1  // 右上
        };

        // 双线性插值：对每列数据分别插值
        double[] result = new double[GPT3_GRID_COLS];
        for (int j = 0; j < GPT3_GRID_COLS; j++) {
            double v00 = idx[0] < grid.length ? grid[idx[0]][j] : 0.0;
            double v01 = idx[1] < grid.length ? grid[idx[1]][j] : 0.0;
            double v10 = idx[2] < grid.length ? grid[idx[2]][j] : 0.0;
            double v11 = idx[3] < grid.length ? grid[idx[3]][j] : 0.0;
            result[j] = (1 - fLat) * (1 - fLon) * v00
                      + (1 - fLat) * fLon * v01
                      + fLat * (1 - fLon) * v10
                      + fLat * fLon * v11;
        }

        // 年际周期项：A0 + A1*cos(2π(doy-28)/365.25) + A2*sin(2π(doy-28)/365.25)
        // 参考历元DOY=28对应1月28日（北半球冬季极值）
        double cosDoy = Math.cos(2.0 * Math.PI * (doy - 28.0) / 365.25);
        double sinDoy = Math.sin(2.0 * Math.PI * (doy - 28.0) / 365.25);

        // 气压(mbar)、温度(K)、比湿
        double p  = result[0] + result[1] * cosDoy + result[2] * sinDoy;
        double T  = result[3] + result[4] * cosDoy + result[5] * sinDoy;
        double Qs = result[6] + result[7] * cosDoy + result[8] * sinDoy;

        // VMF3映射函数系数（无年际变化）
        double ah = result[9];
        double aw = result[10];

        // 高度改正：气压（标准大气压高公式）、水汽压（指数衰减）
        double pres = p * Math.pow(1.0 - 0.0000226 * hell, 5.225);
        double e = Qs / 100.0 * Math.exp(-0.000639 * hell);

        return new double[]{pres, T, e, ah, aw};
    }
}