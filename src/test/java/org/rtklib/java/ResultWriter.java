package org.rtklib.java;

import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.Sol;
import org.rtklib.java.time.TimeSystem;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DecimalFormat;

/**
 * 定位结果输出工具类。
 * 将定位结果输出到文件，格式与 RTKLIB .pos 文件兼容，方便对比。
 */
public final class ResultWriter {
    private ResultWriter() {}

    private static final DecimalFormat DF8 = new DecimalFormat("0.00000000");
    private static final DecimalFormat DF4 = new DecimalFormat("0.0000");
    private static final DecimalFormat DF3 = new DecimalFormat("0.000");
    private static final DecimalFormat DF2 = new DecimalFormat("0.00");

    /**
     * 创建结果文件并写入头部。
     * @param filePath 输出文件路径
     * @param title 标题
     * @return BufferedWriter
     */
    public static BufferedWriter create(String filePath, String title) throws IOException {
        BufferedWriter writer = new BufferedWriter(new FileWriter(filePath));
        writer.write("# " + title + "\n");
        writer.write("# rtklib_java result file\n");
        writer.write("#\n");
        return writer;
    }

    /**
     * 写入 .pos 格式头部（兼容 RTKLIB）。
     * @param writer BufferedWriter
     * @param mode 定位模式描述
     */
    public static void writePosHeader(BufferedWriter writer, String mode) throws IOException {
        writer.write(String.format("# %-20s: %s\n", "Mode", mode));
        writer.write(String.format("# %-20s: %s\n", "Coordinate System", "ECEF / LLH"));
        writer.write("#\n");
        writer.write("#  Date       Time       lat(deg)      lon(deg)     height(m)  Q  ns   sdn(m)   sde(m)   sdu(m)  sdne(m)  sdeu(m)  sdun(m) age(s)  ratio\n");
    }

    /**
     * 写入一条定位结果（.pos 格式）。
     * @param writer BufferedWriter
     * @param sol 定位解
     */
    public static void writePosLine(BufferedWriter writer, Sol sol) throws IOException {
        double[] llh = new double[3];
        CoordTransform.ecef2pos(sol.rr, llh);

        double latDeg = llh[0] * Constants.R2D;
        double lonDeg = llh[1] * Constants.R2D;
        double height = llh[2];

        double[] ymdhms = TimeSystem.time2ymdhms(sol.time);
        int year = (int) ymdhms[0];
        int month = (int) ymdhms[1];
        int day = (int) ymdhms[2];
        int hour = (int) ymdhms[3];
        int min = (int) ymdhms[4];
        double sec = ymdhms[5];

        String dateStr = String.format("%04d/%02d/%02d", year, month, day);
        String timeStr = String.format("%02d:%02d:%09.6f", hour, min, sec);

        String qStr;
        switch (sol.stat) {
            case Constants.SOLQ_FIX:    qStr = "1"; break;
            case Constants.SOLQ_FLOAT:  qStr = "2"; break;
            case Constants.SOLQ_SBAS:   qStr = "3"; break;
            case Constants.SOLQ_DGPS:   qStr = "4"; break;
            case Constants.SOLQ_SINGLE: qStr = "5"; break;
            case Constants.SOLQ_PPP:    qStr = "6"; break;
            default:                    qStr = "0"; break;
        }

        double sdn = sol.qr[0] > 0 ? Math.sqrt(sol.qr[0]) : 0;
        double sde = sol.qr[1] > 0 ? Math.sqrt(sol.qr[1]) : 0;
        double sdu = sol.qr[2] > 0 ? Math.sqrt(sol.qr[2]) : 0;
        double sdne = sol.qr[3] > 0 ? Math.sqrt(Math.abs(sol.qr[3])) : 0;
        double sdeu = sol.qr[4] > 0 ? Math.sqrt(Math.abs(sol.qr[4])) : 0;
        double sdun = sol.qr[5] > 0 ? Math.sqrt(Math.abs(sol.qr[5])) : 0;

        writer.write(String.format("  %s %s %14.9f %14.9f %10.4f  %s %3d %8.4f %8.4f %8.4f %8.4f %8.4f %8.4f %5.1f %6.1f\n",
                dateStr, timeStr, latDeg, lonDeg, height,
                qStr, sol.ns,
                sdn, sde, sdu, sdne, sdeu, sdun,
                sol.age, sol.ratio));
    }

    /**
     * 写入 ECEF 格式的一条定位结果。
     * @param writer BufferedWriter
     * @param sol 定位解
     */
    public static void writeEcefLine(BufferedWriter writer, Sol sol) throws IOException {
        double[] ymdhms = TimeSystem.time2ymdhms(sol.time);
        String dateStr = String.format("%04d/%02d/%02d", (int)ymdhms[0], (int)ymdhms[1], (int)ymdhms[2]);
        String timeStr = String.format("%02d:%02d:%09.6f", (int)ymdhms[3], (int)ymdhms[4], ymdhms[5]);

        String qStr = solStatStr(sol.stat);

        writer.write(String.format("  %s %s %14.4f %14.4f %14.4f  %s %3d\n",
                dateStr, timeStr, sol.rr[0], sol.rr[1], sol.rr[2], qStr, sol.ns));
    }

    /**
     * 写入统计摘要。
     * @param writer BufferedWriter
     * @param totalCount 总历元数
     * @param successCount 成功数
     * @param failCount 失败数
     * @param fixCount Fix 解数
     * @param floatCount Float 解数
     * @param singleCount Single 解数
     */
    public static void writeSummary(BufferedWriter writer, int totalCount, int successCount,
                                     int failCount, int fixCount, int floatCount, int singleCount) throws IOException {
        writer.write("#\n");
        writer.write("# ========== 统计摘要 ==========\n");
        writer.write(String.format("# 总历元数: %d\n", totalCount));
        writer.write(String.format("# 成功: %d, 失败: %d\n", successCount, failCount));
        writer.write(String.format("# Fix: %d, Float: %d, Single: %d\n", fixCount, floatCount, singleCount));
        if (successCount > 0) {
            writer.write(String.format("# 成功率: %.1f%%\n", 100.0 * successCount / totalCount));
        }
    }

    static String solStatStr(int stat) {
        switch (stat) {
            case Constants.SOLQ_FIX:    return "Fix";
            case Constants.SOLQ_FLOAT:  return "Float";
            case Constants.SOLQ_SBAS:   return "SBAS";
            case Constants.SOLQ_DGPS:   return "DGPS";
            case Constants.SOLQ_SINGLE: return "Single";
            case Constants.SOLQ_PPP:    return "PPP";
            default:                    return "None";
        }
    }
}