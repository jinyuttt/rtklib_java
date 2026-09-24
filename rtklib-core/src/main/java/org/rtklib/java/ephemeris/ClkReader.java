package org.rtklib.java.ephemeris;

import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.time.TimeSystem;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public final class ClkReader {
    private static final Logger LOG = LoggerFactory.getLogger(ClkReader.class);
    private static final double EXTERR_CLK = 1E-3;

    private ClkReader() {
    }

    public static void readclk(String file, Nav nav) {
        List<Pclk> pclkList = new ArrayList<>();
        double ver = 0.0;

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            boolean inHeader = true;

            while ((line = br.readLine()) != null) {
                if (inHeader) {
                    if (line.length() >= 20 && line.substring(20).startsWith("RINEX VERSION / TYPE")) {
                        try {
                            ver = Double.parseDouble(line.substring(0, 20).trim());
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    if (line.contains("END OF HEADER")) {
                        inHeader = false;
                    }
                    continue;
                }

                if (!line.startsWith("AS")) continue;

                int off = ver >= 3.04 ? 5 : 0;

                String satField = line.substring(3 + off, Math.min(7 + off, line.length())).trim();
                int sat = parseSat(satField);
                if (sat <= 0 || sat > Constants.MAXSAT) continue;

                GTime time = parseClkTime(line, 8 + off, 26);
                if (time.time == 0 && time.sec == 0.0) continue;

                Pclk last = pclkList.isEmpty() ? null : pclkList.get(pclkList.size() - 1);
                if (last == null || Math.abs(TimeSystem.timediff(time, last.time)) > 1E-9) {
                    Pclk pclk = new Pclk();
                    pclk.time = time;
                    pclkList.add(pclk);
                    last = pclk;
                }

                double clkVal = str2num(line, 40 + off, 19);
                double clkStd = str2num(line, 59 + off, 19);

                last.clk[sat - 1][0] = clkVal;
                last.std[sat - 1][0] = (float) clkStd;
            }
        } catch (IOException e) {
            LOG.error("ClkReader: failed to read file: {}", file, e);
            return;
        }

        if (pclkList.isEmpty()) return;

        interpolateStd(pclkList);

        pclkList.sort(Comparator.comparingDouble(a -> a.time.time + a.time.sec));

        Pclk[] existing = nav.pclk;
        List<Pclk> combined = new ArrayList<>();
        if (existing != null && existing.length > 0) {
            combined.addAll(Arrays.asList(existing));
        }
        combined.addAll(pclkList);
        combined.sort(Comparator.comparingDouble(a -> a.time.time + a.time.sec));

        nav.pclk = combined.toArray(new Pclk[0]);
        nav.nc = nav.pclk.length;
        nav.ncmax = nav.nc;
    }

    private static void interpolateStd(List<Pclk> pclkList) {
        int nc = pclkList.size();
        for (int k = 0; k < Constants.MAXSAT; k++) {
            int lastStdIdx = -1;
            for (int i = 0; i < nc; i++) {
                double std = pclkList.get(i).std[k][0];
                if (std > 0) {
                    if (lastStdIdx < 0) {
                        for (int j = 0; j < i; j++) {
                            if (pclkList.get(j).clk[k][0] != 0) {
                                pclkList.get(j).std[k][0] = (float) std;
                            }
                        }
                    } else {
                        for (int j = lastStdIdx + 1; j < i; j++) {
                            if (pclkList.get(j).clk[k][0] != 0) {
                                double lastStd = pclkList.get(lastStdIdx).std[k][0];
                                double t0 = TimeSystem.timediff(pclkList.get(j).time, pclkList.get(lastStdIdx).time);
                                double t1 = TimeSystem.timediff(pclkList.get(j).time, pclkList.get(i).time);
                                if (t0 - t1 != 0.0) {
                                    double var = (std * std * t0 - lastStd * lastStd * t1) / (t0 - t1);
                                    if (var > 0) {
                                        pclkList.get(j).std[k][0] = (float) Math.sqrt(var);
                                    }
                                }
                            }
                        }
                    }
                    lastStdIdx = i;
                }
            }
            if (lastStdIdx >= 0) {
                float lastStd = pclkList.get(lastStdIdx).std[k][0];
                for (int j = lastStdIdx + 1; j < nc; j++) {
                    if (pclkList.get(j).clk[k][0] != 0) {
                        pclkList.get(j).std[k][0] = lastStd;
                    }
                }
            }
        }
    }

    private static GTime parseClkTime(String line, int pos, int len) {
        try {
            String s = line.substring(pos, Math.min(pos + len, line.length()));
            int year = Integer.parseInt(s.substring(0, 4).trim());
            int month = Integer.parseInt(s.substring(5, 7).trim());
            int day = Integer.parseInt(s.substring(8, 10).trim());
            int hour = Integer.parseInt(s.substring(11, 13).trim());
            int min = Integer.parseInt(s.substring(14, 16).trim());
            double sec = Double.parseDouble(s.substring(17).trim());
            double[] ep = {year, month, day, hour, min, sec};
            return TimeSystem.epoch2time(ep);
        } catch (Exception e) {
            return new GTime();
        }
    }

    private static double str2num(String s, int pos, int len) {
        try {
            int end = Math.min(pos + len, s.length());
            if (pos >= end) return 0.0;
            String sub = s.substring(pos, end).trim();
            if (sub.isEmpty()) return 0.0;
            return Double.parseDouble(sub);
        } catch (NumberFormatException e) {
            return 0.0;
        }
    }

    private static int parseSat(String s) {
        if (s.length() < 2) return 0;
        char sysCode = s.charAt(0);
        int prn;
        try {
            prn = Integer.parseInt(s.substring(1).trim());
        } catch (NumberFormatException e) {
            return 0;
        }

        int sys;
        switch (sysCode) {
            case 'G':
                sys = Constants.SYS_GPS;
                break;
            case 'R':
                sys = Constants.SYS_GLO;
                break;
            case 'E':
                sys = Constants.SYS_GAL;
                break;
            case 'J':
                sys = Constants.SYS_QZS;
                prn += 192;
                break;
            case 'C':
                sys = Constants.SYS_CMP;
                break;
            case 'I':
                sys = Constants.SYS_IRN;
                break;
            default:
                return 0;
        }
        return SatUtils.satno(sys, prn);
    }
}