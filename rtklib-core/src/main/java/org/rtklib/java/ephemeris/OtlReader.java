package org.rtklib.java.ephemeris;

import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.PrcOpt;
import org.rtklib.java.data.Sta;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;

public final class OtlReader {
    private OtlReader() {}

    private static final Logger LOG = LoggerFactory.getLogger(OtlReader.class);

    public static boolean readblq(String file, String sta, double[][][] odisp) {
        if (file == null || file.isEmpty() || sta == null || sta.isEmpty()) return false;
        String staname = sta.trim().toUpperCase();
        if (staname.length() > 16) staname = staname.substring(0, 16);
        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.startsWith("$$") || line.length() < 2) continue;
                String name = "";
                if (line.length() > 2) {
                    String sub = line.substring(2).trim();
                    int sp = sub.indexOf(' ');
                    name = (sp > 0 ? sub.substring(0, sp) : sub).toUpperCase();
                    if (name.length() > 16) name = name.substring(0, 16);
                }
                if (!name.equals(staname)) continue;
                if (readblqrecord(br, odisp)) {
                    LOG.info("readblq: ocean tide loading for {} from {}", sta, file);
                    return true;
                }
            }
        } catch (IOException e) {
            LOG.warn("BLQ file open error: {}", file);
            return false;
        }
        LOG.warn("no otl parameters: sta={} file={}", sta, file);
        return false;
    }

    public static void readotl(PrcOpt popt, String file, Sta[] stas) {
        if (file == null || file.isEmpty()) return;
        int mode = (Constants.PMODE_DGPS <= popt.mode && popt.mode <= Constants.PMODE_FIXED) ? 2 : 1;
        for (int i = 0; i < mode; i++) {
            String staName = (stas != null && i < stas.length) ? stas[i].name : "";
            readblq(file, staName, popt.odisp[i]);
        }
    }

    private static boolean readblqrecord(BufferedReader br, double[][][] odisp) throws IOException {
        int n = 0;
        String line;
        while ((line = br.readLine()) != null) {
            if (line.startsWith("$$")) continue;
            String[] tokens = line.trim().split("\\s+");
            if (tokens.length < 11) continue;
            double[] v = new double[11];
            try {
                for (int i = 0; i < 11; i++) v[i] = Double.parseDouble(tokens[i]);
            } catch (NumberFormatException e) {
                continue;
            }
            if (n < 3) {
                for (int i = 0; i < 11; i++) odisp[0][i][n] = v[i];
            } else {
                for (int i = 0; i < 11; i++) odisp[1][i][n - 3] = -v[i];
            }
            if (++n == 6) return true;
        }
        return false;
    }
}