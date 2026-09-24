package org.rtklib.java.ephemeris;

import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.Erp;
import org.rtklib.java.data.Erpd;
import org.rtklib.java.data.GTime;
import org.rtklib.java.data.Nav;
import org.rtklib.java.time.TimeSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

/**
 * IGS ERP（Earth Rotation Parameters）文件读取器。
 *
 * <p>读取IGS格式的ERP文件，包含地球自转参数：
 * <ul>
 *   <li>xp, yp — 极移坐标（微角秒 → 弧度）</li>
 *   <li>ut1_utc — UT1-UTC时间差（微秒 → 秒）</li>
 *   <li>lod — 日长变化（微秒/天 → 秒/天）</li>
 *   <li>xpr, ypr — 极移速率（微角秒/天 → 弧度/天）</li>
 * </ul>
 *
 * <p>IGS ERP文件格式（version 2）：
 * <pre>
 *   version 2
 *   MJD  Xpole  Ypole  UT1-UTC  LOD  ...  Xrt  Yrt
 *   60484.00  0.05839  0.34527  -0.1234567  0.0001234  ...  0.000123  -0.000456
 * </pre>
 * 数据单位：极移为微角秒（microarcsec），UT1-UTC和LOD为微秒（microsecond）。
 *
 * <p>对应C版：RTKLIB rtkcmn.c readerp()
 */
public final class ErpReader {
    private ErpReader() {}

    private static final Logger LOG = LoggerFactory.getLogger(ErpReader.class);

    private static final double AS2R = Constants.AS2R;

    /**
     * 读取IGS ERP文件到Nav数据结构。
     *
     * <p>解析流程：
     * <ol>
     *   <li>查找"version 2"行，确认文件格式</li>
     *   <li>查找包含MJD/Xpole/Ypole/UT1/LOD的表头行，判断UTC/TAI标志</li>
     *   <li>逐行读取纯数值数据行，解析14列浮点数</li>
     *   <li>单位转换：xp/yp ×1E-6×AS2R, ut1_utc ×1E-7, lod ×1E-7, xpr/ypr ×1E-6×AS2R</li>
     *   <li>若TAI标志，将UT1-TAI转换为UT1-UTC</li>
     * </ol>
     *
     * @param file ERP文件路径
     * @param nav  导航数据，ERP记录存入nav.erp
     * @return 读取的ERP记录数
     */
    public static int readErp(String file, Nav nav) {
        if (file == null || file.isEmpty()) return 0;

        Erp erp = nav.erp;
        if (erp == null) {
            erp = new Erp();
            nav.erp = erp;
        }

        int nerp = 0;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            int state = 0;
            boolean taip = false;

            while ((line = br.readLine()) != null) {
                if (line.contains("version 2") || line.contains("VERSION 2")) {
                    state = 1;
                    continue;
                }

                if (state == 0) continue;

                if (state == 1) {
                    String lower = line.toLowerCase();
                    if (lower.contains("mjd") || lower.contains("xpole") || lower.contains("ypole")
                            || lower.contains("ut1") || lower.contains("lod")) {
                        taip = lower.contains("tai");
                        state = 2;
                    }
                    continue;
                }

                if (state == 2) {
                    if (!isDataLine(line)) continue;

                    double[] v = parseDataLine(line);
                    if (v == null || v.length < 5) continue;

                    if (erp.n >= erp.nmax) {
                        erp.nmax = erp.nmax <= 0 ? 128 : erp.nmax * 2;
                        Erpd[] newData = new Erpd[erp.nmax];
                        if (erp.data != null) {
                            System.arraycopy(erp.data, 0, newData, 0, erp.n);
                        }
                        for (int i = erp.n; i < erp.nmax; i++) {
                            newData[i] = new Erpd();
                        }
                        erp.data = newData;
                    }

                    erp.data[erp.n].mjd = v[0];
                    erp.data[erp.n].xp = v[1] * 1E-6 * AS2R;
                    erp.data[erp.n].yp = v[2] * 1E-6 * AS2R;
                    erp.data[erp.n].ut1_utc = v[3] * 1E-7;

                    if (taip) {
                        double[] ep = {2000, 1, 1, 12, 0, 0};
                        GTime tutcTime = TimeSystem.timeadd(TimeSystem.epoch2time(ep), (v[0] - 51544.5) * 86400.0);
                        erp.data[erp.n].ut1_utc += TimeSystem.timediff(TimeSystem.utc2gpst(tutcTime), tutcTime) + 19;
                    }

                    erp.data[erp.n].lod = v[4] * 1E-7;
                    if (v.length > 12) erp.data[erp.n].xpr = v[12] * 1E-6 * AS2R;
                    if (v.length > 13) erp.data[erp.n].ypr = v[13] * 1E-6 * AS2R;

                    erp.n++;
                    nerp++;
                }
            }
        } catch (IOException e) {
            LOG.warn("ERP file open error: {}", file);
            return 0;
        }

        if (nerp > 0) {
            LOG.info("ERP loaded: {} records from {}", nerp, file);
        } else {
            LOG.warn("No ERP data read from {}", file);
        }
        return nerp;
    }

    /**
     * 判断行是否为纯数值数据行。
     * 与C版逻辑一致：逐字符检查，只允许数字、小数点、正负号、空格、制表符。
     */
    private static boolean isDataLine(String line) {
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '\0' || ch == '\r' || ch == '\n') break;
            if (ch == '.' || ch == '-' || ch == '+' || ch == ' ' || ch == '\t') continue;
            if (ch < '0' || ch > '9') return false;
        }
        return true;
    }

    /**
     * 解析数值数据行，最多解析14个浮点数。
     */
    private static double[] parseDataLine(String line) {
        String[] tokens = line.trim().split("\\s+");
        int len = Math.min(tokens.length, 14);
        double[] v = new double[len];
        try {
            for (int i = 0; i < len; i++) {
                v[i] = Double.parseDouble(tokens[i]);
            }
        } catch (NumberFormatException e) {
            return null;
        }
        return v;
    }
}