package org.rtklib.java.ephemeris;

import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.time.TimeSystem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

public final class PcvReader {
    private PcvReader() {}

    private static final Logger LOG = LoggerFactory.getLogger(PcvReader.class);

    public static List<Pcv> readpcv(String file) {
        if (file == null || file.isEmpty()) return new ArrayList<>();
        String ext = "";
        int dot = file.lastIndexOf('.');
        if (dot >= 0) ext = file.substring(dot).toUpperCase();
        if (ext.equals(".ATX")) {
            return readantex(file);
        } else {
            return readngspcv(file);
        }
    }

    static List<Pcv> readantex(String file) {
        List<Pcv> pcvs = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            Pcv pcv = null;
            int state = 0;
            int freq = 0;
            int[] freqs = {1, 2, 5, 0};
            String line;
            while ((line = br.readLine()) != null) {
                if (line.length() < 60) continue;
                String label = line.substring(60).trim();
                if (label.contains("START OF ANTENNA")) {
                    pcv = new Pcv();
                    state = 1;
                    continue;
                }
                if (label.contains("END OF ANTENNA")) {
                    if (pcv != null) pcvs.add(pcv);
                    state = 0;
                    continue;
                }
                if (state == 0 || pcv == null) continue;
                if (label.contains("COMMENT")) continue;

                if (label.contains("TYPE / SERIAL NO")) {
                    pcv.type = line.length() >= 20 ? line.substring(0, 20).trim() : "";
                    pcv.code = line.length() >= 40 ? line.substring(20, 40).trim() : "";
                    if (pcv.code.length() == 3) {
                        pcv.sat = SatUtils.satid2no(pcv.code);
                    }
                } else if (label.contains("VALID FROM")) {
                    GTime t = str2time(line, 0, 43);
                    if (t != null) pcv.ts = t;
                } else if (label.contains("VALID UNTIL")) {
                    GTime t = str2time(line, 0, 43);
                    if (t != null) pcv.te = t;
                } else if (label.contains("START OF FREQUENCY")) {
                    if (pcv.sat == 0 && line.length() >= 4 && line.charAt(3) != 'G') continue;
                    int f = 0;
                    try { f = Integer.parseInt(line.substring(4, 8).trim()); } catch (NumberFormatException e) { continue; }
                    int fi = 0;
                    for (int i = 0; freqs[i] != 0; i++) {
                        if (freqs[i] == f) { fi = i + 1; break; }
                    }
                    if (fi > 0) freq = fi;
                    int sys = SatUtils.satsys(pcv.sat, null);
                    if (sys == Constants.SYS_GAL && f == 7) freq = 2;
                } else if (label.contains("END OF FREQUENCY")) {
                    freq = 0;
                } else if (label.contains("NORTH / EAST / UP")) {
                    if (freq < 1 || freq > Constants.NFREQ) continue;
                    double[] neu = decodef(line, 3);
                    if (neu == null || neu.length < 3) continue;
                    int fi = freq - 1;
                    pcv.off[fi][0] = neu[pcv.sat != 0 ? 0 : 1];
                    pcv.off[fi][1] = neu[pcv.sat != 0 ? 1 : 0];
                    pcv.off[fi][2] = neu[2];
                } else if (line.length() >= 8 && line.substring(0, 8).contains("NOAZI")) {
                    if (freq < 1 || freq > Constants.NFREQ) continue;
                    double[] vals = decodef(line.substring(8), 19);
                    if (vals == null) continue;
                    int fi = freq - 1;
                    for (int i = 0; i < vals.length && i < 19; i++) {
                        pcv.var[fi][i] = vals[i];
                    }
                    for (int i = vals.length; i < 19; i++) {
                        pcv.var[fi][i] = pcv.var[fi][i - 1];
                    }
                }
            }
        } catch (IOException e) {
            LOG.warn("antex file open error: {}", file);
            return pcvs;
        }
        LOG.info("readpcv: {} entries from {}", pcvs.size(), file);
        return pcvs;
    }

    static List<Pcv> readngspcv(String file) {
        List<Pcv> pcvs = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            Pcv pcv = null;
            int n = 0;
            String line;
            while ((line = br.readLine()) != null) {
                if (line.length() >= 62 && line.charAt(61) == '|') continue;
                if (line.isEmpty() || line.charAt(0) != ' ') n = 0;
                n++;
                if (n == 1) {
                    pcv = new Pcv();
                    pcv.type = line.length() >= 61 ? line.substring(0, 61).trim() : line.trim();
                } else if (n == 2 && pcv != null) {
                    double[] neu = decodef(line, 3);
                    if (neu == null || neu.length < 3) continue;
                    pcv.off[0][0] = neu[1];
                    pcv.off[0][1] = neu[0];
                    pcv.off[0][2] = neu[2];
                } else if (n == 3 && pcv != null) {
                    double[] vals = decodef(line, 10);
                    if (vals != null) for (int i = 0; i < vals.length && i < 10; i++) pcv.var[0][i] = vals[i];
                } else if (n == 4 && pcv != null) {
                    double[] vals = decodef(line, 9);
                    if (vals != null) for (int i = 0; i < vals.length && i < 9; i++) pcv.var[0][10 + i] = vals[i];
                } else if (n == 5 && pcv != null) {
                    double[] neu = decodef(line, 3);
                    if (neu == null || neu.length < 3) continue;
                    pcv.off[1][0] = neu[1];
                    pcv.off[1][1] = neu[0];
                    pcv.off[1][2] = neu[2];
                } else if (n == 6 && pcv != null) {
                    double[] vals = decodef(line, 10);
                    if (vals != null) for (int i = 0; i < vals.length && i < 10; i++) pcv.var[1][i] = vals[i];
                } else if (n == 7 && pcv != null) {
                    double[] vals = decodef(line, 9);
                    if (vals != null) {
                        for (int i = 0; i < vals.length && i < 9; i++) pcv.var[1][10 + i] = vals[i];
                        pcvs.add(pcv);
                    }
                }
            }
        } catch (IOException e) {
            LOG.warn("ngs pcv file open error: {}", file);
            return pcvs;
        }
        LOG.info("readngspcv: {} entries from {}", pcvs.size(), file);
        return pcvs;
    }

    public static Pcv searchpcv(int sat, String type, GTime time, List<Pcv> pcvs) {
        if (pcvs == null || pcvs.isEmpty()) return null;
        if (sat != 0) {
            for (Pcv pcv : pcvs) {
                if (pcv.sat != sat) continue;
                if (pcv.ts.time != 0 && TimeSystem.timediff(pcv.ts, time) > 0.0) continue;
                if (pcv.te.time != 0 && TimeSystem.timediff(pcv.te, time) < 0.0) continue;
                return pcv;
            }
            return null;
        }
        String[] types = type != null ? type.trim().split("\\s+") : new String[0];
        if (types.length == 0) return null;
        for (Pcv pcv : pcvs) {
            boolean match = true;
            for (String t : types) {
                if (!pcv.type.contains(t)) { match = false; break; }
            }
            if (match) return pcv;
        }
        for (Pcv pcv : pcvs) {
            if (pcv.type.startsWith(types[0])) {
                LOG.info("pcv without radome is used type={}", type);
                return pcv;
            }
        }
        return null;
    }

    public static void setpcv(GTime time, PrcOpt popt, Nav nav, List<Pcv> pcvSatList, List<Pcv> pcvRecList, Sta[] stas) {
        Pcv pcv0 = new Pcv();
        for (int i = 0; i < Constants.MAXSAT; i++) {
            nav.pcvs[i] = new Pcv(pcv0);
            if ((SatUtils.satsys(i + 1, null) & popt.navsys) == 0) continue;
            Pcv pcv = searchpcv(i + 1, "", time, pcvSatList);
            if (pcv == null) {
                LOG.debug("no satellite antenna pcv: {}", SatUtils.satno2id(i + 1));
                continue;
            }
            nav.pcvs[i] = new Pcv(pcv);
        }
        int mode = (Constants.PMODE_DGPS <= popt.mode && popt.mode <= Constants.PMODE_FIXED) ? 2 : 1;
        for (int i = 0; i < mode; i++) {
            popt.pcvr[i] = new Pcv(pcv0);
            if (popt.anttype[i].equals("*") && stas != null && i < stas.length) {
                popt.anttype[i] = stas[i].antdes;
                if (stas[i].del.length > 3 && stas[i].del[3] == 1.0) {
                    if (CoordTransform.norm3(stas[i].pos) > 0.0) {
                        double[] pos = new double[3];
                        CoordTransform.ecef2pos(stas[i].pos, pos);
                        double[] del = new double[3];
                        double[] E = new double[9];
                        CoordTransform.xyz2enu(pos, E);
                        del[0] = -E[0] * stas[i].del[0] + E[3] * stas[i].del[1] + E[6] * stas[i].del[2];
                        del[1] = -E[1] * stas[i].del[0] + E[4] * stas[i].del[1] + E[7] * stas[i].del[2];
                        del[2] = -E[2] * stas[i].del[0] + E[5] * stas[i].del[1] + E[8] * stas[i].del[2];
                        for (int j = 0; j < 3; j++) popt.antdel[i][j] = del[j];
                    }
                } else {
                    for (int j = 0; j < 3 && j < stas[i].del.length; j++) popt.antdel[i][j] = stas[i].del[j];
                }
            }
            Pcv pcv = searchpcv(0, popt.anttype[i], time, pcvRecList);
            if (pcv == null) {
                LOG.warn("no receiver antenna pcv: {}", popt.anttype[i]);
                popt.anttype[i] = "";
                continue;
            }
            popt.anttype[i] = pcv.type;
            popt.pcvr[i] = new Pcv(pcv);
        }
        LOG.info("setpcv: satellite and receiver antenna parameters set");
    }

    public static int readsap(String file, GTime time, Nav nav) {
        List<Pcv> pcvs = readpcv(file);
        if (pcvs.isEmpty()) return 0;
        for (int i = 0; i < Constants.MAXSAT; i++) {
            Pcv pcv = searchpcv(i + 1, "", time, pcvs);
            if (pcv != null) {
                nav.pcvs[i] = new Pcv(pcv);
            } else {
                nav.pcvs[i] = new Pcv();
            }
        }
        LOG.info("readsap: {} antenna parameters from {}", pcvs.size(), file);
        return 1;
    }

    private static GTime str2time(String s, int pos, int len) {
        if (s == null || s.length() < pos + len) return null;
        String str = s.substring(pos, Math.min(pos + len, s.length())).trim();
        try {
            int year = Integer.parseInt(str.substring(0, 4).trim());
            int month = Integer.parseInt(str.substring(5, 7).trim());
            int day = Integer.parseInt(str.substring(8, 10).trim());
            int hour = Integer.parseInt(str.substring(11, 13).trim());
            int min = Integer.parseInt(str.substring(14, 16).trim());
            double sec = Double.parseDouble(str.substring(17).trim());
            double[] ep = {year, month, day, hour, min, sec};
            return TimeSystem.epoch2time(ep);
        } catch (Exception e) {
            return null;
        }
    }

    private static double[] decodef(String s, int n) {
        if (s == null || s.isEmpty()) return null;
        List<Double> vals = new ArrayList<>();
        String[] tokens = s.trim().split("\\s+");
        for (int i = 0; i < tokens.length && vals.size() < n; i++) {
            try { vals.add(Double.parseDouble(tokens[i])); }
            catch (NumberFormatException e) { /* skip */ }
        }
        if (vals.isEmpty()) return null;
        double[] result = new double[vals.size()];
        for (int i = 0; i < vals.size(); i++) result[i] = vals.get(i);
        return result;
    }
}