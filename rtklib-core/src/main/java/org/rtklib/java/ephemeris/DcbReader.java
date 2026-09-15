package org.rtklib.java.ephemeris;

import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.Nav;
import org.rtklib.java.data.Sta;
import org.rtklib.java.pntpos.SppCore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.util.ArrayList;
import java.util.List;

public final class DcbReader {
    private DcbReader() {}

    private static final Logger LOG = LoggerFactory.getLogger(DcbReader.class);

    private static final int MAX_CODE_BIASES = 3;
    private static final int MAX_CODE_BIAS_FREQS = 2;

    public static boolean readdcb(String file, Nav nav, Sta[] stas) {
        if (file == null || file.isEmpty()) return false;
        initCbias(nav);
        String upper = file.toUpperCase();
        boolean dcbOk = false;
        if (upper.contains(".BIA") || upper.contains(".BSX")) {
            dcbOk = readbiaf(file, nav);
        } else if (upper.contains(".DCB")) {
            dcbOk = readdcbf(file, nav, stas);
        }
        if (dcbOk) {
            LOG.info("readdcb: DCB parameters loaded from {}", file);
        } else {
            LOG.warn("readdcb: failed to read DCB from {}", file);
        }
        return dcbOk;
    }

    public static boolean readdcbMulti(String[] files, Nav nav, Sta[] stas) {
        initCbias(nav);
        boolean dcbOk = false;
        for (String file : files) {
            if (file == null || file.isEmpty()) continue;
            String upper = file.toUpperCase();
            if (upper.contains(".BIA") || upper.contains(".BSX")) {
                dcbOk = readbiaf(file, nav);
            } else if (upper.contains(".DCB")) {
                dcbOk = readdcbf(file, nav, stas);
            }
            if (dcbOk) break;
        }
        if (dcbOk) LOG.info("readdcb: DCB parameters loaded");
        return dcbOk;
    }

    private static void initCbias(Nav nav) {
        nav.cbias = new double[Constants.MAXSAT][MAX_CODE_BIAS_FREQS][MAX_CODE_BIASES];
        nav.rbias = new double[1][2][MAX_CODE_BIASES];
    }

    static boolean readdcbf(String file, Nav nav, Sta[] stas) {
        boolean ok = false;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            int type = 0;
            while ((line = br.readLine()) != null) {
                if (line.contains("DIFFERENTIAL (P1-C1) CODE BIASES")) type = 1;
                else if (line.contains("DIFFERENTIAL (P2-C2) CODE BIASES")) type = 2;
                if (type == 0) continue;
                String trimmed = line.trim();
                if (trimmed.isEmpty()) continue;
                String[] tokens = trimmed.split("\\s+");
                if (tokens.length < 1) continue;
                String str1 = tokens[0];
                String str2 = tokens.length >= 2 ? tokens[1] : "";
                double cbias = str2num(line, 26, 9);
                if (cbias == 0.0) continue;
                if (stas != null && (str1.equals("G") || str1.equals("R"))) {
                    int idx = -1;
                    for (int i = 0; i < stas.length; i++) {
                        if (stas[i].name.equals(str2)) { idx = i; break; }
                    }
                    if (idx >= 0 && idx < 1) {
                        int j = str1.equals("G") ? 0 : 1;
                        if (type - 1 < MAX_CODE_BIASES) {
                            nav.rbias[idx][j][type - 1] = cbias * 1E-9 * Constants.CLIGHT;
                        }
                    }
                } else {
                    int sat = SatUtils.satid2no(str1);
                    if (sat > 0 && sat <= Constants.MAXSAT && type - 1 < MAX_CODE_BIAS_FREQS) {
                        nav.cbias[sat - 1][type - 1][0] = cbias * 1E-9 * Constants.CLIGHT;
                        ok = true;
                    }
                }
            }
        } catch (IOException e) {
            LOG.warn("DCB file open error: {}", file);
            return false;
        }
        return ok;
    }

    static boolean readbiaf(String file, Nav nav) {
        boolean ok = false;
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.length() < 75) continue;
                String[] tokens = line.trim().split("\\s+");
                if (tokens.length < 5) continue;
                String bias = tokens[0];
                String prn = tokens[2];
                String obs1 = tokens[3];
                if (obs1.length() < 2 || obs1.charAt(0) != 'C') continue;
                double cbias = str2num(line, 70, 21);
                if (cbias == 0.0) continue;
                int sat = SatUtils.satid2no(prn);
                if (sat == 0) continue;
                int sys = SatUtils.satsys(sat, null);
                int freq;
                if (obs1.charAt(1) == '1') {
                    freq = 0;
                } else if ((sys != Constants.SYS_GAL && obs1.charAt(1) == '2') ||
                           (sys == Constants.SYS_GAL && obs1.charAt(1) == '5')) {
                    freq = 1;
                } else {
                    continue;
                }
                if (freq >= MAX_CODE_BIAS_FREQS) continue;
                if (bias.equals("OSB")) {
                    int code1 = obs2code(obs1.substring(1));
                    int biasIx1 = SppCore.code2biasIx(sys, code1);
                    if (biasIx1 == 0) {
                        for (int i = 0; i < MAX_CODE_BIASES; i++) {
                            nav.cbias[sat - 1][freq][i] += cbias * 1E-9 * Constants.CLIGHT;
                        }
                    } else if (biasIx1 - 1 < MAX_CODE_BIASES) {
                        nav.cbias[sat - 1][freq][biasIx1 - 1] -= cbias * 1E-9 * Constants.CLIGHT;
                    }
                    ok = true;
                } else if (bias.equals("DSB")) {
                    String obs2 = tokens[4];
                    if (obs1.length() >= 2 && obs2.length() >= 2 && obs1.charAt(1) != obs2.charAt(1)) continue;
                    int code1 = obs2code(obs1.substring(1));
                    int code2 = obs2code(obs2.substring(1));
                    int biasIx1 = SppCore.code2biasIx(sys, code1);
                    int biasIx2 = SppCore.code2biasIx(sys, code2);
                    if (biasIx1 == 0 && biasIx2 - 1 < MAX_CODE_BIASES) {
                        nav.cbias[sat - 1][freq][biasIx2 - 1] = cbias * 1E-9 * Constants.CLIGHT;
                    } else if (biasIx2 == 0 && biasIx1 - 1 < MAX_CODE_BIASES) {
                        nav.cbias[sat - 1][freq][biasIx1 - 1] = -cbias * 1E-9 * Constants.CLIGHT;
                    }
                    ok = true;
                }
            }
        } catch (IOException e) {
            LOG.warn("BIA/BSX file open error: {}", file);
            return false;
        }
        return ok;
    }

    private static int obs2code(String obs) {
        if (obs == null || obs.isEmpty()) return 0;
        char c = obs.charAt(0);
        switch (c) {
            case '1': return Constants.CODE_L1C;
            case '2': return Constants.CODE_L2C;
            case '5': return Constants.CODE_L5C;
            case '7': return Constants.CODE_L7X;
            case 'W': return Constants.CODE_L1W;
            case 'P': return Constants.CODE_L1P;
            default: return 0;
        }
    }

    private static double str2num(String s, int pos, int len) {
        if (s == null || pos < 0 || pos + len > s.length()) return 0.0;
        try {
            return Double.parseDouble(s.substring(pos, pos + len).trim());
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }
}