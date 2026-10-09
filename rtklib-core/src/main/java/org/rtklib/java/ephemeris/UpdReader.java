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
 * <p>支持两种文件格式：
 * <pre>
 *   格式1（WHU/PRIDE标准）：段标记 + time sat upd
 *     WIDELANE / WL        ← 宽巷段开始
 *     time  sat  upd_wl
 *     NARROWLANE / NL      ← 窄巷段开始
 *     time  sat  upd_nl
 *
 *   格式2（GREAT-PVT）：sat在首列，无/有EPOCH-TIME时间行
 *     % UPD generated using upd_WL
 *     G02  0.461  0.008  162        ← WL: sat upd sigma nsat
 *     EPOCH-TIME  60249  0.0       ← NL时间行
 *     G02  0.532  0.016  31        ← NL: sat upd sigma nsat
 * </pre>
 *
 * <p>文件来源：WHU(WUM) / PRIDE / GREAT-PVT
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
        int type = inferTypeFromFileName(file);
        double currentTime = 0.0;

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                String t = line.trim();
                if (t.startsWith("WIDELANE") || t.startsWith("WL")) { type = 1; continue; }
                if (t.startsWith("NARROWLANE") || t.startsWith("NL")) { type = 2; continue; }
                if (t.startsWith("%") || t.startsWith("#") || t.isEmpty()) continue;

                if (t.startsWith("EPOCH-TIME")) {
                    String[] et = t.split("\\s+");
                    if (et.length >= 2) {
                        try { currentTime = Double.parseDouble(et[1]); } catch (NumberFormatException ignored) {}
                    }
                    continue;
                }

                String[] tokens = t.split("\\s+");
                if (tokens.length < 2) continue;

                double[] rec = parseUpdLine(tokens, currentTime);
                if (rec == null) continue;

                if (type == 1) wlList.add(rec);
                else if (type == 2) nlList.add(rec);
                else wlList.add(rec);
            }
        } catch (IOException e) {
            LOG.warn("UPD file open error: {}", file);
            return false;
        }

        if (wlList.isEmpty() && nlList.isEmpty()) {
            LOG.warn("No UPD records loaded from {}", file);
            return false;
        }

        nav.updWl = mergeUpd(nav.updWl, wlList);
        nav.updNl = mergeUpd(nav.updNl, nlList);

        LOG.info("UPD loaded: {} WL, {} NL records from {}", wlList.size(), nlList.size(), file);
        return true;
    }

    private static double[][] mergeUpd(double[][] existing, List<double[]> newRecords) {
        if (newRecords.isEmpty()) return existing;
        int existLen = (existing != null) ? existing.length : 0;
        double[][] merged = new double[existLen + newRecords.size()][3];
        if (existLen > 0) {
            System.arraycopy(existing, 0, merged, 0, existLen);
        }
        for (int i = 0; i < newRecords.size(); i++) {
            System.arraycopy(newRecords.get(i), 0, merged[existLen + i], 0, 3);
        }
        return merged;
    }

    private static double[] parseUpdLine(String[] tokens, double currentTime) {
        int sat = parseSat(tokens[0].replace("x", ""));
        if (sat > 0) {
            try {
                double upd = Double.parseDouble(tokens[1]);
                return new double[]{currentTime, sat, upd};
            } catch (NumberFormatException ignored) {}
        }

        if (tokens.length < 3) return null;
        try {
            double time = Double.parseDouble(tokens[0]);
            sat = parseSat(tokens[1]);
            if (sat <= 0) return null;
            double upd = Double.parseDouble(tokens[2]);
            return new double[]{time, sat, upd};
        } catch (NumberFormatException ignored) {}

        return null;
    }

    private static int inferTypeFromFileName(String file) {
        String name = file.substring(file.lastIndexOf(java.io.File.separatorChar) + 1).toLowerCase();
        if (name.contains("_nl_") || name.contains("_nl")) return 2;
        if (name.contains("_wl_") || name.contains("_wl")) return 1;
        if (name.contains("_ewl_") || name.contains("_ewl")) return 1;
        return 0;
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