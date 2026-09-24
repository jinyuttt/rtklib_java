package org.rtklib.java.ephemeris;

import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.Nav;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * FCB（Fractional Cycle Bias）产品读取器，用于PPP-AR模糊度固定。
 *
 * <p>FCB产品提供宽巷(WL)和窄巷(NL)的小数偏差改正值，使浮点模糊度
 * 能够正确取整为整数模糊度，从而实现PPP-AR（Ambiguity Resolution）。
 *
 * <p>文件格式（WHU/CNES）：
 * <pre>
 *   WL 或 WIDELANE 标记行（宽巷段开始）
 *   time  sat  fcb_wl  [fcb_nl]
 *   NL 或 NARROWLANE 标记行（窄巷段开始）
 *   time  sat  fcb_nl
 * </pre>
 * 其中time=GPS秒，sat=卫星编号（G01/R01/E01/C01格式）
 *
 * <p>文件来源：WHU(WUM) / CNES / IGS-MGEX
 * 存储目录：product/fcb/（由ProductDownloader.downloadFcb()下载）
 *
 * <p>对应C版：RTKLIB未内置FCB，本模块为v2.2.2新增
 */
public final class FcbReader {
    private FcbReader() {}

    private static final Logger LOG = LoggerFactory.getLogger(FcbReader.class);

    /**
     * 读取FCB文件到Nav数据结构。
     *
     * @param file 本地文件路径
     * @param nav  导航数据，宽巷存入nav.fcbWl，窄巷存入nav.fcbNl
     * @return 读取成功返回true
     */
    public static boolean readFcb(String file, Nav nav) {
        if (file == null || file.isEmpty()) return false;
        List<double[]> wlList = new ArrayList<>();
        List<double[]> nlList = new ArrayList<>();
        int type = 0; // 0=未确定, 1=宽巷(WL), 2=窄巷(NL)

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                // 段标记：WL/WIDELANE=宽巷段，NL/NARROWLANE=窄巷段
                if (t.startsWith("WL") || t.startsWith("WIDELANE")) { type = 1; continue; }
                if (t.startsWith("NL") || t.startsWith("NARROWLANE")) { type = 2; continue; }
                if (t.startsWith("%") || t.startsWith("#") || t.isEmpty()) continue;

                String[] tokens = t.split("\\s+");
                if (tokens.length < 3) continue;

                try {
                    double time = Double.parseDouble(tokens[0]);
                    int sat = parseSat(tokens[1]);
                    if (sat <= 0) continue;
                    double wl = Double.parseDouble(tokens[2]);
                    double nl = (tokens.length >= 4) ? Double.parseDouble(tokens[3]) : 0.0;
                    double[] rec = {time, sat, wl, nl};
                    if (type == 1) wlList.add(rec);
                    else if (type == 2) nlList.add(rec);
                    else { wlList.add(rec); } // 无段标记时默认为宽巷
                } catch (NumberFormatException e) {
                    continue;
                }
            }
        } catch (IOException e) {
            LOG.warn("FCB file open error: {}", file);
            return false;
        }

        if (wlList.isEmpty() && nlList.isEmpty()) {
            LOG.warn("No FCB records loaded from {}", file);
            return false;
        }

        // 存储到Nav：每行 [time, sat, fcb]
        nav.fcbWl = new double[wlList.size()][3];
        for (int i = 0; i < wlList.size(); i++) {
            nav.fcbWl[i][0] = wlList.get(i)[0]; // time
            nav.fcbWl[i][1] = wlList.get(i)[1]; // sat
            nav.fcbWl[i][2] = wlList.get(i)[2]; // fcb_wl
        }

        nav.fcbNl = new double[nlList.size()][3];
        for (int i = 0; i < nlList.size(); i++) {
            nav.fcbNl[i][0] = nlList.get(i)[0]; // time
            nav.fcbNl[i][1] = nlList.get(i)[1]; // sat
            nav.fcbNl[i][2] = nlList.get(i)[2]; // fcb_nl（优先取第4列，若WL段则取第3列）
            if (nlList.get(i).length >= 4 && nlList.get(i)[3] != 0.0) {
                nav.fcbNl[i][2] = nlList.get(i)[3]; // 窄巷段第3列为NL值
            }
        }

        LOG.info("FCB loaded: {} WL, {} NL records from {}", wlList.size(), nlList.size(), file);
        return true;
    }

    /** 解析卫星标识：G01→SYS_GPS+1, R01→SYS_GLO+1, E01→SYS_GAL+1, C01→SYS_CMP+1, J01→SYS_QZS+1 */
    private static int parseSat(String s) {
        if (s.length() < 3) return 0;
        try {
            int prn = Integer.parseInt(s.substring(1));
            char sys = s.charAt(0);
            switch (sys) {
                case 'G': return Constants.SYS_GPS + prn;
                case 'R': return Constants.SYS_GLO + prn;
                case 'E': return Constants.SYS_GAL + prn;
                case 'C': return Constants.SYS_CMP + prn;
                case 'J': return Constants.SYS_QZS + prn;
                default: return 0;
            }
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 查找指定卫星在指定时刻的宽巷FCB值（取最近的历史记录） */
    public static double getFcbWl(Nav nav, int sat, double time) {
        return getFcb(nav.fcbWl, sat, time);
    }

    /** 查找指定卫星在指定时刻的窄巷FCB值（取最近的历史记录） */
    public static double getFcbNl(Nav nav, int sat, double time) {
        return getFcb(nav.fcbNl, sat, time);
    }

    /** 在FCB数组中查找sat在time时刻的值（线性查找，取time之前最近的记录） */
    private static double getFcb(double[][] fcb, int sat, double time) {
        if (fcb == null || fcb.length == 0) return 0.0;
        int idx = -1;
        for (int i = 0; i < fcb.length; i++) {
            if ((int) fcb[i][1] == sat && fcb[i][0] <= time) idx = i;
        }
        if (idx < 0) return 0.0;
        return fcb[idx][2];
    }
}