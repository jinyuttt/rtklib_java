package org.rtklib.java.ephemeris;

import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.coord.CoordTransform;
import org.rtklib.java.data.*;
import org.rtklib.java.rtkpos.Tides;
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

public final class Sp3Reader {
    private static final Logger LOG = LoggerFactory.getLogger(Sp3Reader.class);
    private Sp3Reader() {
    }

    private static final int NMAX = 10;
    private static final double MAXDTE = 900.0;

    public static void readsp3(String file, Nav nav, int opt) {
        List<PepH> pephList = new ArrayList<>();
        int[] sats = new int[Constants.MAXSAT];
        double[] bfact = new double[2];
        String[] tsys = new String[]{""};
        int fileIndex = 0;

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            int lineNum = 0;
            int ns = 0;
            char type = ' ';
            GTime time0 = new GTime();
            int nl = 5;

            while ((line = br.readLine()) != null) {
                lineNum++;

                if (line.startsWith("#c") || line.startsWith("#d")) {
                    type = line.charAt(2);
                    time0 = parseTime(line.substring(3, 31));
                } else if (line.startsWith("+ ") && lineNum > 2) {
                    if (lineNum == 3 + (lineNum - 3) / 1) {
                        try {
                            ns = Integer.parseInt(line.substring(3, 6).trim());
                            if (ns > 85) nl = ns / 17 + (ns % 17 != 0 ? 1 : 0);
                        } catch (NumberFormatException e) {
                            ns = 0;
                        }
                    }
                    int k = 0;
                    for (int j = 0; j < 17 && k < ns; j++) {
                        int pos = 9 + 3 * j;
                        if (pos + 2 >= line.length()) break;
                        char code = line.charAt(pos);
                        int prn;
                        try {
                            prn = Integer.parseInt(line.substring(pos + 1, pos + 3).trim());
                        } catch (NumberFormatException e) {
                            continue;
                        }
                        int sys = code2sys(code);
                        if (sys != 0 && k < Constants.MAXSAT) {
                            sats[k++] = SatUtils.satno(sys, prn);
                        }
                    }
                } else if (line.startsWith("%c") && line.length() >= 12) {
                    tsys[0] = line.substring(9, Math.min(12, line.length()));
                } else if (line.startsWith("%f") && line.length() >= 26) {
                    try {
                        bfact[0] = Double.parseDouble(line.substring(3, 13).trim());
                        bfact[1] = Double.parseDouble(line.substring(14, 26).trim());
                    } catch (NumberFormatException e) {
                        bfact[0] = bfact[1] = 0.0;
                    }
                } else if (line.startsWith("/*") && lineNum > 22) {
                    break;
                }
            }
        } catch (IOException e) {
            return;
        }

        try (BufferedReader br = new BufferedReader(new FileReader(file))) {
            String line;
            PepH currentPeph = null;
            int nRead = 0;
            int epochCount = 0;
            int lineNum2 = 0;

            while ((line = br.readLine()) != null) {
                lineNum2++;
                boolean isEpoch = line.length() > 0 && line.charAt(0) == '*' && line.length() >= 3 && line.charAt(1) == ' ' && line.charAt(2) == ' ';
                if (isEpoch) {
                    if (currentPeph != null && nRead > 0) {
                        pephList.add(currentPeph);
                    }
                    GTime epochTime = parseTime(line.substring(3, 28));
                    if (epochTime.time == 0 && epochTime.sec == 0.0) {
                        currentPeph = null;
                        nRead = 0;
                        continue;
                    }

                    if (!tsys[0].isEmpty() && tsys[0].startsWith("UTC")) {
                        epochTime = TimeSystem.utc2gpst(epochTime);
                    }

                    currentPeph = new PepH();
                    currentPeph.time = epochTime;
                    currentPeph.index = fileIndex;
                    nRead = 0;
                    epochCount++;
                } else if (currentPeph != null && line.length() >= 4 && line.startsWith("P")) {
                    parsePositionLine(line, currentPeph, bfact);
                    nRead++;
                } else if (line.startsWith("EOF")) {
                    if (currentPeph != null && nRead > 0) {
                        pephList.add(currentPeph);
                    }
                    break;
                }
            }

            if (currentPeph != null && nRead > 0 && !pephList.contains(currentPeph)) {
                pephList.add(currentPeph);
            }
        } catch (IOException e) {
            // ignore
        }

        pephList.sort(Comparator.comparingDouble(a -> TimeSystem.timediff(a.time, new GTime())));

        PepH[] existing = nav.peph;
        List<PepH> combined = new ArrayList<>();
        if (existing != null) {
            combined.addAll(Arrays.asList(existing));
        }
        combined.addAll(pephList);
        combined.sort(Comparator.comparingDouble(a -> TimeSystem.timediff(a.time, new GTime())));

        nav.peph = combined.toArray(new PepH[0]);
        nav.ne = nav.peph.length;
        nav.nemax = nav.ne;
    }

    private static GTime parseTime(String s) {
        try {
            while (s.length() < 28) s += " ";
            int year = Integer.parseInt(s.substring(0, 4).trim());
            int month = Integer.parseInt(s.substring(5, 7).trim());
            int day = Integer.parseInt(s.substring(8, 10).trim());
            int hour = Integer.parseInt(s.substring(11, 13).trim());
            int min = Integer.parseInt(s.substring(14, 16).trim());
            double sec = Double.parseDouble(s.substring(17, 28).trim());
            double[] ep = {year, month, day, hour, min, sec};
            return TimeSystem.epoch2time(ep);
        } catch (Exception e) {
            LOG.warn("SP3 time parse error: {} — {}", e.getMessage(), s);
            return new GTime();
        }
    }

    private static void parsePositionLine(String line, PepH peph, double[] bfact) {
        if (line.length() < 4) return;
        char recType = line.charAt(0);
        if (recType != 'P') return;

        char sysCode = line.charAt(1);
        int sys = code2sys(sysCode == ' ' ? 'G' : sysCode);
        int prn;
        try {
            prn = Integer.parseInt(line.substring(2, 4).trim());
        } catch (NumberFormatException e) {
            return;
        }

        if (sys == Constants.SYS_SBS) prn += 100;
        else if (sys == Constants.SYS_QZS) prn += 192;

        int sat = SatUtils.satno(sys, prn);
        if (sat <= 0 || sat > Constants.MAXSAT) return;

        for (int j = 0; j < 4; j++) {
            int pos = 4 + j * 14;
            if (pos + 14 > line.length()) break;
            double val;
            try {
                val = Double.parseDouble(line.substring(pos, pos + 14).trim());
            } catch (NumberFormatException e) {
                continue;
            }

            if (Math.abs(val - 999999.999999) < 1E-6 || val == 0.0) continue;

            if (j < 3) {
                peph.pos[sat - 1][j] = val * 1000.0;
            } else {
                peph.pos[sat - 1][3] = val * 1E-6;
            }
        }

        if (line.length() >= 70) {
            for (int j = 0; j < 4; j++) {
                int pos = 61 + j * 3;
                if (pos + (j < 3 ? 2 : 3) > line.length()) break;
                try {
                    double std = Double.parseDouble(line.substring(pos, pos + (j < 3 ? 2 : 3)).trim());
                    if (std > 0.0 && bfact[j < 3 ? 0 : 1] > 0.0) {
                        double base = bfact[j < 3 ? 0 : 1];
                        peph.std[sat - 1][j] = (float) (Math.pow(base, std) * (j < 3 ? 1E-3 : 1E-12));
                    }
                } catch (NumberFormatException e) {
                    // ignore
                }
            }
        }
    }

    private static int code2sys(char code) {
        switch (code) {
            case 'G':
            case ' ':
                return Constants.SYS_GPS;
            case 'R':
                return Constants.SYS_GLO;
            case 'E':
                return Constants.SYS_GAL;
            case 'J':
                return Constants.SYS_QZS;
            case 'C':
                return Constants.SYS_CMP;
            case 'I':
                return Constants.SYS_IRN;
            case 'L':
                return Constants.SYS_LEO;
            default:
                return 0;
        }
    }

    public static int pephpos(GTime time, int sat, Nav nav, double[] rs, double[] dts, double[] vare, double[] varc) {
        rs[0] = rs[1] = rs[2] = 0.0;
        dts[0] = 0.0;
        if (vare != null) vare[0] = 0.0;
        if (varc != null) varc[0] = 0.0;

        if (nav.ne < NMAX + 1) {
            LOG.debug("pephpos: no prec ephem, sat={} ne={} < NMAX+1={}", sat, nav.ne, NMAX + 1);
            return 0;
        }
        if (TimeSystem.timediff(time, nav.peph[0].time) < -MAXDTE) {
            LOG.debug(String.format("pephpos: no prec ephem sat=%d time before SP3 range by %.0fs", sat, -TimeSystem.timediff(time, nav.peph[0].time)));
            return 0;
        }
        if (TimeSystem.timediff(time, nav.peph[nav.ne - 1].time) > MAXDTE) {
            LOG.debug(String.format("pephpos: no prec ephem sat=%d time after SP3 range by %.0fs", sat, TimeSystem.timediff(time, nav.peph[nav.ne - 1].time)));
            return 0;
        }

        int i = 0, j = nav.ne - 1;
        while (i < j) {
            int k = (i + j) / 2;
            if (TimeSystem.timediff(nav.peph[k].time, time) < 0.0) i = k + 1;
            else j = k;
        }
        int index = i <= 0 ? 0 : i - 1;

        int start = index - (NMAX + 1) / 2;
        if (start < 0) start = 0;
        else if (start + NMAX >= nav.ne) start = nav.ne - NMAX - 1;

        double[] t = new double[NMAX + 1];
        double[][] p = new double[3][NMAX + 1];
        for (int ii = 0; ii <= NMAX; ii++) {
            t[ii] = TimeSystem.timediff(nav.peph[start + ii].time, time);
            double norm = 0.0;
            for (int jj = 0; jj < 3; jj++) {
                norm += nav.peph[start + ii].pos[sat - 1][jj] * nav.peph[start + ii].pos[sat - 1][jj];
            }
            if (Math.sqrt(norm) <= 0.0) {
                LOG.debug(String.format("pephpos: prec ephem outage sat=%d pos=0 at peph[%d] (start=%d ii=%d)", sat, start + ii, start, ii));
                return 0;
            }
        }

        for (int ii = 0; ii <= NMAX; ii++) {
            double[] pos = nav.peph[start + ii].pos[sat - 1];
            double sinl = Math.sin(Constants.OMGE * t[ii]);
            double cosl = Math.cos(Constants.OMGE * t[ii]);
            p[0][ii] = cosl * pos[0] - sinl * pos[1];
            p[1][ii] = sinl * pos[0] + cosl * pos[1];
            p[2][ii] = pos[2];
        }

        for (int ii = 0; ii < 3; ii++) {
            rs[ii] = interppol(t, p[ii].clone(), NMAX + 1);
        }

        double std = 0.0;
        if (vare != null) {
            double s0 = nav.peph[index].std[sat - 1][0];
            double s1 = nav.peph[index].std[sat - 1][1];
            double s2 = nav.peph[index].std[sat - 1][2];
            std = Math.sqrt(s0 * s0 + s1 * s1 + s2 * s2);
            if (t[0] > 0.0) std += 5E-7 * t[0] * t[0] / 2.0;
            else if (t[NMAX] < 0.0) std += 5E-7 * t[NMAX] * t[NMAX] / 2.0;
            vare[0] = std * std;
        }

        int idx1 = index + 1 < nav.ne ? index + 1 : index;
        double t0 = TimeSystem.timediff(time, nav.peph[index].time);
        double t1 = TimeSystem.timediff(time, nav.peph[idx1].time);
        double c0 = nav.peph[index].pos[sat - 1][3];
        double c1 = nav.peph[idx1].pos[sat - 1][3];

        if (t0 <= 0.0) {
            if ((dts[0] = c0) != 0.0) {
                std = nav.peph[index].std[sat - 1][3] * Constants.CLIGHT - 1E-3 * t0;
            }
        } else if (t1 >= 0.0) {
            if ((dts[0] = c1) != 0.0) {
                std = nav.peph[idx1].std[sat - 1][3] * Constants.CLIGHT + 1E-3 * t1;
            }
        } else if (c0 != 0.0 && c1 != 0.0) {
            dts[0] = (c1 * t0 - c0 * t1) / (t0 - t1);
            int ii = t0 < -t1 ? 0 : 1;
            /* NOTE: no *CLIGHT here, faithful to RTKLIB preceph.c pephpos() */
            std = nav.peph[ii == 0 ? index : idx1].std[sat - 1][3] + 1E-3 * Math.abs(ii == 0 ? t0 : t1);
        } else {
            dts[0] = 0.0;
        }
        if (varc != null) varc[0] = std * std;

        return 1;
    }

    /**
     * Satellite clock by precise clock.
     * Aligned with RTKLIB preceph.c pephclk().
     * - No clock product / time out of range: return 1 keeping dts set by
     *   pephpos() (SP3 clock used instead, C L651-656).
     * - Clock outage (c=0): return 0 (satellite dropped, C L671/675/685).
     */
    public static int pephclk(GTime time, int sat, Nav nav, double[] dts, double[] varc) {
        if (nav.nc < 2 ||
            TimeSystem.timediff(time, nav.pclk[0].time) < -MAXDTE ||
            TimeSystem.timediff(time, nav.pclk[nav.nc - 1].time) > MAXDTE) {
            return 1;
        }

        int i = 0, j = nav.nc - 1;
        while (i < j) {
            int k = (i + j) / 2;
            if (TimeSystem.timediff(nav.pclk[k].time, time) < 0.0) i = k + 1;
            else j = k;
        }
        int index = i <= 0 ? 0 : i - 1;

        int idx1 = index + 1 < nav.nc ? index + 1 : index;
        double t0 = TimeSystem.timediff(time, nav.pclk[index].time);
        double t1 = TimeSystem.timediff(time, nav.pclk[idx1].time);
        double c0 = nav.pclk[index].clk[sat - 1][0];
        double c1 = nav.pclk[idx1].clk[sat - 1][0];

        double std;
        if (t0 <= 0.0) {
            if ((dts[0] = c0) == 0.0) return 0;
            std = nav.pclk[index].std[sat - 1][0] * Constants.CLIGHT - 1E-3 * t0;
        } else if (t1 >= 0.0) {
            if ((dts[0] = c1) == 0.0) return 0;
            std = nav.pclk[idx1].std[sat - 1][0] * Constants.CLIGHT + 1E-3 * t1;
        } else if (c0 != 0.0 && c1 != 0.0) {
            dts[0] = (c1 * t0 - c0 * t1) / (t0 - t1);
            int ii = t0 < -t1 ? 0 : 1;
            int idx = ii == 0 ? index : idx1;
            std = nav.pclk[idx].std[sat - 1][0] * Constants.CLIGHT + 1E-3 * Math.abs(ii == 0 ? t0 : t1);
        } else {
            return 0; /* prec clock outage */
        }
        if (varc != null) varc[0] = std * std;
        return 1;
    }

    /**
     * Satellite antenna offset correction (satellite PCO: center-of-mass to
     * antenna phase center) in ECEF.
     * Aligned with RTKLIB preceph.c satantoff() L707-765.
     * Uses iono-free LC coefficients of the two frequencies per system.
     * With no satellite ANTEX loaded (nav.pcvs zero-filled) dant stays 0.
     * @param time  time (gpst)
     * @param rs    satellite position (ecef, center of mass)
     * @param sat   satellite number
     * @param nav   navigation data (nav.pcvs satellite antenna parameters)
     * @param dant  output: satellite antenna offset {dx,dy,dz} (ecef, m)
     */
    public static void satantoff(GTime time, double[] rs, int sat, Nav nav, double[] dant) {
        Pcv pcv = nav.pcvs[sat - 1];
        double[] ex = new double[3], ey = new double[3], ez = new double[3];
        double[] es = new double[3], r = new double[3], rsun = new double[3];
        double[] erpv = new double[5];
        double[] freq = new double[2];

        dant[0] = dant[1] = dant[2] = 0.0;

        /* sun position in ecef */
        Tides.sunmoonpos(TimeSystem.gpst2utc(time), erpv, rsun, null, null);

        /* unit vectors of satellite fixed coordinates */
        for (int i = 0; i < 3; i++) r[i] = -rs[i];
        if (!CoordTransform.normv3(r, ez)) return;
        for (int i = 0; i < 3; i++) r[i] = rsun[i] - rs[i];
        if (!CoordTransform.normv3(r, es)) return;
        CoordTransform.cross3(ez, es, r);
        if (!CoordTransform.normv3(r, ey)) return;
        CoordTransform.cross3(ey, ez, ex);

        /* iono-free LC coefficients */
        int sys = SatUtils.satsys(sat, null);
        if (sys == Constants.SYS_GPS || sys == Constants.SYS_QZS) { /* L1-L2 */
            freq[0] = Constants.FREQL1;
            freq[1] = Constants.FREQL2;
        } else if (sys == Constants.SYS_GLO) { /* G1-G2 */
            freq[0] = SatUtils.sat2freq(sat, Constants.CODE_L1C, nav);
            freq[1] = SatUtils.sat2freq(sat, Constants.CODE_L2C, nav);
        } else if (sys == Constants.SYS_GAL) { /* E1-E5b */
            freq[0] = Constants.FREQL1;
            freq[1] = Constants.FREQE5b;
        } else if (sys == Constants.SYS_CMP) { /* B1I-B2I */
            freq[0] = Constants.FREQ1_CMP;
            freq[1] = Constants.FREQ2_CMP;
        } else if (sys == Constants.SYS_IRN) { /* L5-S */
            freq[0] = Constants.FREQL5;
            freq[1] = Constants.FREQs;
        } else return;

        double C1 = freq[0] * freq[0] / (freq[0] * freq[0] - freq[1] * freq[1]);
        double C2 = -freq[1] * freq[1] / (freq[0] * freq[0] - freq[1] * freq[1]);

        /* iono-free LC */
        for (int i = 0; i < 3; i++) {
            double dant1 = pcv.off[0][0] * ex[i] + pcv.off[0][1] * ey[i] + pcv.off[0][2] * ez[i];
            double dant2 = pcv.off[1][0] * ex[i] + pcv.off[1][1] * ey[i] + pcv.off[1][2] * ez[i];
            dant[i] = C1 * dant1 + C2 * dant2;
        }
    }

    private static double interppol(double[] x, double[] y, int n) {
        for (int j = 1; j < n; j++) {
            for (int i = 0; i < n - j; i++) {
                if (x[i + j] == x[i]) return y[0];
                y[i] = (x[i + j] * y[i] - x[i] * y[i + 1]) / (x[i + j] - x[i]);
            }
        }
        return y[0];
    }
}