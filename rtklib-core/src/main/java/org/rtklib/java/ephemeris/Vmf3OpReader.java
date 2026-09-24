package org.rtklib.java.ephemeris;

import org.rtklib.java.data.GTime;
import org.rtklib.java.data.Nav;
import org.rtklib.java.time.TimeSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * VMF3 OP（Output Parameter）文件读取与插值。
 *
 * <p>VMF3（Vienna Mapping Function 3）是当前最精确的经验对流层映射函数，
 * 优于GMF/GPT2等简化模型。OP文件提供测站位置的VMF3映射函数系数（ah, aw, bH, bW），
 * 通常按6小时间隔提供（00:00, 06:00, 12:00, 18:00 UTC）。
 *
 * <p>VMF3映射函数计算：
 *   mf(e) = (1 + a/(1 + b/(1 + c))) / (sin(e) + a/(sin(e) + b/(sin(e) + c)))
 *   其中 a = ah或aw（干/湿系数），b = bH或bW，c为常数
 *
 * <p>文件来源：TU Wien VMF服务器 (http://vmf.geo.tuwien.ac.at/trop_products/VMF3/)
 * 文件格式：每行 MJD ah aw bH bW [可选：ah2 aw2 bH2 bW2 ah3 aw3]
 *
 * <p>对应C版：RTKLIB使用简化GMF模型，本模块为v2.2.2新增优化
 */
public final class Vmf3OpReader {
    private Vmf3OpReader() {}

    private static final Logger LOG = LoggerFactory.getLogger(Vmf3OpReader.class);

    /**
     * VMF3 OP单条记录。
     * mjd: 修正儒略日
     * ah/aw: VMF3干/湿映射函数a系数
     * bH/bW: VMF3干/湿映射函数b系数
     */
    public static class Vmf3OpRecord {
        public double mjd;
        public double lat;
        public double lon;
        public double zhd;
        public double zwd;
        public double[] ah;
        public double[] aw;
        public double[] bH;
        public double[] bW;

        public Vmf3OpRecord() {
            ah = new double[4];
            aw = new double[4];
            bH = new double[4];
            bW = new double[4];
        }
    }

    /**
     * 读取VMF3 OP文件到Nav数据结构。
     *
     * @param file 本地文件路径（由ProductDownloader.downloadVmf3()下载）
     * @param nav  导航数据，系数存入nav.vmf3Coeff
     * @return 读取成功返回true
     */
    public static boolean readVmf3Op(String file, Nav nav) {
        if (file == null || file.isEmpty()) return false;
        List<Vmf3OpRecord> records = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("%") || line.startsWith("#") || line.startsWith("!") || line.trim().isEmpty()) continue;
                String[] tokens = line.trim().split("\\s+");
                if (tokens.length < 6) continue;

                Vmf3OpRecord rec = new Vmf3OpRecord();
                if (tokens.length >= 13) {
                    rec.mjd = Double.parseDouble(tokens[0]);
                    rec.ah[0] = Double.parseDouble(tokens[1]);
                    rec.aw[0] = Double.parseDouble(tokens[2]);
                    rec.bH[0] = Double.parseDouble(tokens[3]);
                    rec.bW[0] = Double.parseDouble(tokens[4]);
                    rec.ah[1] = Double.parseDouble(tokens[5]);
                    rec.aw[1] = Double.parseDouble(tokens[6]);
                    rec.bH[1] = Double.parseDouble(tokens[7]);
                    rec.bW[1] = Double.parseDouble(tokens[8]);
                    rec.ah[2] = Double.parseDouble(tokens[9]);
                    rec.aw[2] = Double.parseDouble(tokens[10]);
                    rec.bH[2] = Double.parseDouble(tokens[11]);
                    rec.bW[2] = Double.parseDouble(tokens[12]);
                } else {
                    rec.lat = Double.parseDouble(tokens[0]);
                    rec.lon = Double.parseDouble(tokens[1]);
                    rec.ah[0] = Double.parseDouble(tokens[2]);
                    rec.aw[0] = Double.parseDouble(tokens[3]);
                    rec.zhd = Double.parseDouble(tokens[4]);
                    rec.zwd = Double.parseDouble(tokens[5]);
                }
                records.add(rec);
            }
        } catch (IOException e) {
            LOG.warn("VMF3 OP file open error: {}", file);
            return false;
        } catch (NumberFormatException e) {
            LOG.warn("VMF3 OP file parse error: {}", e.getMessage());
            return false;
        }

        if (records.isEmpty()) {
            LOG.warn("No VMF3 OP records loaded from {}", file);
            return false;
        }

        // 存储为紧凑数组：[mjd, ah0, aw0, bH0, bW0, ah1, aw1, ah2, aw2]
        nav.vmf3Coeff = new double[records.size()][9];
        for (int i = 0; i < records.size(); i++) {
            Vmf3OpRecord r = records.get(i);
            nav.vmf3Coeff[i][0] = r.mjd;
            nav.vmf3Coeff[i][1] = r.ah[0];
            nav.vmf3Coeff[i][2] = r.aw[0];
            nav.vmf3Coeff[i][3] = r.bH[0];
            nav.vmf3Coeff[i][4] = r.bW[0];
            nav.vmf3Coeff[i][5] = r.ah[1];
            nav.vmf3Coeff[i][6] = r.aw[1];
            nav.vmf3Coeff[i][7] = r.ah[2];
            nav.vmf3Coeff[i][8] = r.aw[2];
        }
        nav.vmf3OpLoaded = true;
        LOG.info("VMF3 OP loaded: {} records from {}", records.size(), file);
        return true;
    }

    /**
     * 线性插值VMF3系数（按MJD时间插值）。
     *
     * @param vmf3Coeff VMF3系数数组（nav.vmf3Coeff）
     * @param mjd       当前时刻的修正儒略日
     * @return [ah0, aw0, bH0, bW0, ah1, aw1, ah2, aw2]，失败返回null
     */
    public static double[] interpolateVmf3(double[][] vmf3Coeff, double mjd) {
        if (vmf3Coeff == null || vmf3Coeff.length == 0) return null;

        // 找到mjd所在的区间
        int idx = 0;
        for (int i = 0; i < vmf3Coeff.length; i++) {
            if (vmf3Coeff[i][0] <= mjd) idx = i;
            else break;
        }

        // 边界处理
        if (idx >= vmf3Coeff.length - 1) idx = vmf3Coeff.length - 2;
        if (idx < 0) idx = 0;

        // 线性插值因子
        double mjd0 = vmf3Coeff[idx][0];
        double mjd1 = vmf3Coeff[idx + 1][0];
        double f = (mjd1 > mjd0) ? (mjd - mjd0) / (mjd1 - mjd0) : 0.0;
        f = Math.max(0.0, Math.min(1.0, f));

        // 对8个系数列分别线性插值
        double[] result = new double[8];
        for (int j = 1; j <= 8; j++) {
            double v0 = vmf3Coeff[idx][j];
            double v1 = vmf3Coeff[idx + 1][j];
            result[j - 1] = v0 + f * (v1 - v0);
        }
        return result;
    }

    /**
     * GTime转修正儒略日（MJD）。
     * MJD = JD - 2400000.5，JD为儒略日。
     */
    public static double mjdFromGTime(GTime time) {
        double[] ep = new double[6];
        TimeSystem.time2epoch(time, ep);
        int year = (int) ep[0];
        int mon = (int) ep[1];
        int day = (int) ep[2];
        double hour = ep[3] + ep[4] / 60.0 + ep[5] / 3600.0;

        // 儒略日公式（适用于1582年10月15日之后）
        if (mon <= 2) { year -= 1; mon += 12; }
        return (int)(365.25 * (year + 4716)) + (int)(30.6001 * (mon + 1)) + day + hour / 24.0 - 2401520.5;
    }
}