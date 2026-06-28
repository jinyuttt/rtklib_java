package org.rtklib.java.ephemeris;

import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.time.TimeSystem;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

public final class ClkReader {
    private ClkReader() {
    }

    public static void readclk(String file, Nav nav) {
        List<Pclk> pclkList = new ArrayList<>();

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            Pclk currentPclk = null;
            boolean inHeader = true;

            while ((line = br.readLine()) != null) {
                if (inHeader) {
                    if (line.contains("END OF HEADER")) {
                        inHeader = false;
                    }
                    continue;
                }

                if (line.startsWith("AS")) {
                    String satField = line.substring(3, Math.min(7, line.length())).trim();
                    int sat = parseSat(satField);
                    if (sat <= 0 || sat > Constants.MAXSAT) continue;

                    GTime time = parseClkTime(line, 8, 26);
                    if (time.time == 0 && time.sec == 0.0) continue;

                    if (currentPclk == null || Math.abs(TimeSystem.timediff(time, currentPclk.time)) > 1E-9) {
                        currentPclk = new Pclk();
                        currentPclk.time = time;
                        pclkList.add(currentPclk);
                    }

                    double clkVal = str2num(line, 40, 19);
                    if (clkVal == 0.0) continue;

                    currentPclk.clk[sat - 1][0] = clkVal * Constants.CLIGHT;

                    double clkStd = str2num(line, 59, 19);
                    if (clkStd != 0.0) {
                        currentPclk.std[sat - 1][0] = (float) (clkStd * Constants.CLIGHT);
                    }
                }
            }
        } catch (IOException e) {
            return;
        }

        pclkList.sort(Comparator.comparingDouble(a -> TimeSystem.timediff(a.time, new GTime())));

        Pclk[] existing = nav.pclk;
        List<Pclk> combined = new ArrayList<>();
        if (existing != null) {
            combined.addAll(Arrays.asList(existing));
        }
        combined.addAll(pclkList);
        combined.sort(Comparator.comparingDouble(a -> TimeSystem.timediff(a.time, new GTime())));

        nav.pclk = combined.toArray(new Pclk[0]);
        nav.nc = nav.pclk.length;
        nav.ncmax = nav.nc;
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
            return Double.parseDouble(s.substring(pos, end).trim());
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