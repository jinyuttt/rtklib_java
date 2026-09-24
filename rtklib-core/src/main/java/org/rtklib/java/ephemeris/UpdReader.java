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
 * UPD（Uncalibrated Phase Delay）产品读取器，用于PPP-AR窄巷模糊度固定。
 *
 * <p>UPD与FCB功能类似，提供宽巷和窄巷的相位偏差改正。
 * UPD是WHU/PRIDE的命名方式，FCB是CNES的命名方式，两者数学上等价。
 *
 * <p>文件格式：
 * <pre>
 *   WIDELANE 或 WL 标记行（宽巷段开始）
 *   time  sat  upd_wl
 *   NARROWLANE 或 NL 标记行（窄巷段开始）
 *   time  sat  upd_nl
 * </pre>
 *
 * <p>文件来源：WHU(WUM) / PRIDE
 * 存储目录：product/upd/（由ProductDownloader.downloadUpd()下载）
 *
 * <p>对应C版：RTKLIB未内置UPD，本模块为v2.2.2新增
 */
public final class UpdReader {
    private UpdReader() {}

    private static final Logger LOG = LoggerFactory.getLogger(UpdReader.class);

    /**
     * 读取UPD文件到Nav数据结构。
     *
     * @param file 本地文件路径
     * @param nav  导航数据，宽巷存入nav.updWl，窄巷存入nav.updNl
     * @return 读取成功返回true
     */
    public static boolean readUpd(String file, Nav nav) {
        if (file == null || file.isEmpty()) return false;
        List<double[]> wlList = new ArrayList<>();
        List<double[]> nlList = new ArrayList<>();
        int type = 0; // 0=未确定, 1=宽巷(WL), 2=窄巷(NL)

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                // 段标记
                if (t.startsWith("WIDELANE") || t.startsWith("WL")) { type = 1; continue; }
                if (t.startsWith("NARROWLANE") || t.startsWith("NL")) { type = 2; continue; }
                if (t.startsWith("%") || t.startsWith("#") || t.isEmpty()) continue;

                String[] tokens = t.split("\\s+");
                if (tokens.length < 3) continue;

                try {
                    double time = Double.parseDouble(tokens[0]);
                    int sat = parseSat(tokens[1]);
                    if (sat <= 0) continue;
                    double upd = Double.parseDouble(tokens[2]);
                    double[] rec = {time, sat, upd};
                    if (type == 1) wlList.add(rec);
                    else if (type == 2) nlList.add(rec);
                    else wlList.add(rec); // 无段标记时默认为宽巷
                } catch (NumberFormatException e) {
                    continue;
                }
            }
        } catch (IOException e) {
            LOG.warn("UPD file open error: {}", file);
            return false;
        }

        if (wlList.isEmpty() && nlList.isEmpty()) {
            LOG.warn("No UPD records loaded from {}", file);
            return false;
        }

        // 存储到Nav：每行 [time, sat, upd]
        nav.updWl = new double[wlList.size()][3];
        for (int i = 0; i < wlList.size(); i++) {
            System.arraycopy(wlList.get(i), 0, nav.updWl[i], 0, 3);
        }

        nav.updNl = new double[nlList.size()][3];
        for (int i = 0; i < nlList.size(); i++) {
            System.arraycopy(nlList.get(i), 0, nav.updNl[i], 0, 3);
        }

        LOG.info("UPD loaded: {} WL, {} NL records from {}", wlList.size(), nlList.size(), file);
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

    /** 查找指定卫星在指定时刻的宽巷UPD值 */
    public static double getUpdWl(Nav nav, int sat, double time) {
        return getUpd(nav.updWl, sat, time);
    }

    /** 查找指定卫星在指定时刻的窄巷UPD值 */
    public static double getUpdNl(Nav nav, int sat, double time) {
        return getUpd(nav.updNl, sat, time);
    }

    /** 在UPD数组中查找sat在time时刻的值（取time之前最近的记录） */
    private static double getUpd(double[][] upd, int sat, double time) {
        if (upd == null || upd.length == 0) return 0.0;
        int idx = -1;
        for (int i = 0; i < upd.length; i++) {
            if ((int) upd[i][1] == sat && upd[i][0] <= time) idx = i;
        }
        return (idx < 0) ? 0.0 : upd[idx][2];
    }
}