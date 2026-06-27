package org.rtklib.java.rinex;

package org.rtklib.java.rinex;

import org.rtklib.java.data.*;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.time.TimeSystem;
import org.rtklib.java.common.ObsCode;
import org.rtklib.java.common.SatUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * RINEX file parser supporting versions 2.11, 3.05, 3.06.
 * Aligned with RTKLIB rnx2rtcm.c and rnxdec.c.
 */
public class Old1 {
    private static final Logger log = LoggerFactory.getLogger(RinexParser.class);

    /** RINEX file version */
    private double version;

    /** RINEX file type ('O': observation, 'N': navigation, 'M': meteorological) */
    private char fileType;

    /** GNSS system identifier */
    private char sys;

    /** Observation data */
    public Obs obs;

    /** Navigation data */
    public Nav nav;

    /** Station information */
    public Sta sta;

    /** Current file path */
    private String filePath;

    private static final int MAXOBSTYPE = 64;
    private static final int RNX_SYS_GPS = 0;
    private static final int RNX_SYS_GLO = 1;
    private static final int RNX_SYS_GAL = 2;
    private static final int RNX_SYS_QZS = 3;
    private static final int RNX_SYS_SBS = 4;
    private static final int RNX_SYS_CMP = 5;
    private static final int RNX_SYS_IRN = 6;
    private static final String SYSCODES = "GREJSCIE";

    private String[][] tobs = new String[7][MAXOBSTYPE];
    private int[] nobs = new int[7];

    /**
     * Default constructor.
     */
    public RinexParser() {
        this.obs = new Obs();
        this.nav = new Nav();
        this.sta = new Sta();
        this.version = 0.0;
        this.fileType = ' ';
        this.sys = ' ';
        for (int i = 0; i < 7; i++) {
            for (int j = 0; j < MAXOBSTYPE; j++) {
                tobs[i][j] = "";
            }
            nobs[i] = 0;
        }
    }

    /**
     * Parse RINEX observation file.
     * @param file RINEX observation file path
     * @return true on success, false on error
     */
    public boolean parseObs(String file) {
        this.filePath = file;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            if (!readObsHeader(reader)) {
                log.error("Failed to read RINEX observation header: {}", file);
                return false;
            }

            List<Obsd> obsList = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                if (line.startsWith("COMMENT") || line.startsWith("#")) continue;

                Obsd[] epochObs = readObsEpoch(reader, line);
                if (epochObs != null) {
                    for (Obsd o : epochObs) {
                        if (o != null) obsList.add(o);
                    }
                }
            }

            this.obs.data = obsList.toArray(new Obsd[0]);
            this.obs.n = obsList.size();
            log.info("RINEX observation parsed: {} epochs, {} observations",
                    this.obs.n, obsList.size());
            return true;
        } catch (IOException e) {
            log.error("Error reading RINEX observation file: {}", file, e);
            return false;
        }
    }

    /**
     * Parse RINEX navigation file.
     * @param file RINEX navigation file path
     * @return true on success, false on error
     */
    public boolean parseNav(String file) {
        this.filePath = file;
        try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
            if (!readNavHeader(reader)) {
                log.error("Failed to read RINEX navigation header: {}", file);
                return false;
            }

            String line;
            int lineNum = 0;
            while ((line = reader.readLine()) != null) {
                lineNum++;
                if (line.trim().isEmpty()) continue;
                if (line.startsWith("COMMENT") || line.startsWith("#")) continue;

                if (this.version >= 3.0) {
                    if (line.length() > 0 && Character.isLetter(line.charAt(0))) {
                        log.debug("parseNav: lineNum={}, calling readNavEphV3, line=[{}]",
                                lineNum, line.substring(0, Math.min(20, line.length())));
                        readNavEphV3(reader, line);
                    } else {
                        log.debug("parseNav: lineNum={}, skipping non-header line, firstChar=[{}]",
                                lineNum, line.length() > 0 ? line.charAt(0) : "empty");
                    }
                } else {
                    if (line.length() >= 2 && !line.substring(0, 2).trim().isEmpty()
                            && Character.isDigit(line.substring(0, 2).trim().charAt(0))) {
                        readNavEphV2(reader, line);
                    }
                }
            }

            log.info("RINEX navigation parsed: GPS={}, GLO={}, GAL={}, BDS={}",
                    this.nav.n, this.nav.ng, this.nav.ne, this.nav.na);
            return true;
        } catch (IOException e) {
            log.error("Error reading RINEX navigation file: {}", file, e);
            return false;
        }
    }

    /**
     * Read RINEX observation file header.
     * @param reader BufferedReader
     * @return true on success, false on error
     */
    private boolean readObsHeader(BufferedReader reader) throws IOException {
        String line;
        boolean endOfHeader = false;

        while ((line = reader.readLine()) != null) {
            if (line.length() < 60) line = String.format("%-80s", line);
            if (line.length() < 80) line = String.format("%-80s", line);

            String label = line.substring(60, 80).trim();

            if (label.equals("RINEX VERSION / TYPE")) {
                this.version = Double.parseDouble(line.substring(0, 9).trim());
                this.fileType = line.charAt(20);
                if (line.length() > 40) {
                    this.sys = line.charAt(40);
                }
                log.debug("RINEX version: {}, type: {}, system: {}",
                        this.version, this.fileType, this.sys);
            } else if (label.equals("MARKER NAME")) {
                this.sta.name = line.substring(0, 60).trim();
            } else if (label.equals("MARKER NUMBER")) {
                this.sta.marker = line.substring(0, 20).trim();
            } else if (label.equals("ANTENNA - DELTA H/E/N")) {
                this.sta.del[2] = parseDouble(line, 0, 14);  // H
                this.sta.del[0] = parseDouble(line, 14, 14); // E
                this.sta.del[1] = parseDouble(line, 28, 14); // N
            } else if (label.equals("ANTENNA - DELTA X/Y/Z")) {
                this.sta.del[3] = parseDouble(line, 0, 14);  // X
                this.sta.del[4] = parseDouble(line, 14, 14); // Y
                this.sta.del[5] = parseDouble(line, 28, 14); // Z
            } else if (label.equals("ANTENNA TYPE")) {
                this.sta.antdes = line.substring(0, 20).trim();
            } else if (label.equals("SYS / # / OBS TYPES")) {
                int p = SYSCODES.indexOf(line.charAt(0));
                int n = parseInteger(line, 3, 3);
                if (p >= 0) {
                    for (int j = 0, k = 7; j < n; j++, k += 4) {
                        if (k > 58) {
                            line = reader.readLine();
                            if (line == null) break;
                            if (line.length() < 60) line = String.format("%-80s", line);
                            if (line.length() < 80) line = String.format("%-80s", line);
                            k = 7;
                        }
                        if (k + 3 > line.length()) break;
                        tobs[p][nobs[p]++] = line.substring(k, k + 3).trim();
                    }
                }
            } else if (label.equals("# / TYPES OF OBSERV")) {
                int n = parseInteger(line, 0, 6);
                for (int j = 0, k = 6; j < n; j++, k += 6) {
                    if (k > 54) {
                        line = reader.readLine();
                        if (line == null) break;
                        if (line.length() < 60) line = String.format("%-80s", line);
                        if (line.length() < 80) line = String.format("%-80s", line);
                        k = 6;
                    }
                    if (k + 6 > line.length()) break;
                    String obsType = line.substring(k, k + 6).trim();
                    if (nobs[0] < MAXOBSTYPE) {
                        tobs[0][nobs[0]++] = obsType;
                    }
                }
            } else if (label.equals("END OF HEADER")) {
                endOfHeader = true;
                break;
            }
        }

        if (!endOfHeader) {
            log.error("RINEX header end not found");
            return false;
        }

        if (this.version < 2.11 || this.version > 3.06) {
            log.warn("Unsupported RINEX version: {}", this.version);
        }

        return true;
    }

    /**
     * Read RINEX navigation file header.
     * @param reader BufferedReader
     * @return true on success, false on error
     */
    private boolean readNavHeader(BufferedReader reader) throws IOException {
        String line;
        boolean endOfHeader = false;

        while ((line = reader.readLine()) != null) {
            if (line.length() < 60) line = String.format("%-80s", line);

            String label = line.substring(60, 80).trim();

            if (label.equals("RINEX VERSION / TYPE")) {
                this.version = Double.parseDouble(line.substring(0, 9).trim());
                this.fileType = line.charAt(20);
                this.sys = line.charAt(40);
                log.debug("RINEX version: {}, type: {}, system: {}",
                        this.version, this.fileType, this.sys);
            } else if (label.equals("END OF HEADER")) {
                endOfHeader = true;
                break;
            }
        }

        if (!endOfHeader) {
            log.error("RINEX header end not found");
            return false;
        }

        return true;
    }

    /**
     * Read observation epoch data.
     * @param reader BufferedReader
     * @param firstLine First line of epoch
     * @return Array of observations for this epoch
     */
    private Obsd[] readObsEpoch(BufferedReader reader, String firstLine) throws IOException {
        int year, month, day, hour, min, flag, nSat;
        double sec;
        int[] satNos = null;

        if (this.version >= 3.0) {
            year = Integer.parseInt(firstLine.substring(2, 6).trim());
            month = Integer.parseInt(firstLine.substring(7, 9).trim());
            day = Integer.parseInt(firstLine.substring(10, 12).trim());
            hour = Integer.parseInt(firstLine.substring(13, 15).trim());
            min = Integer.parseInt(firstLine.substring(16, 18).trim());
            sec = Double.parseDouble(firstLine.substring(19, 28).trim());
            flag = Integer.parseInt(firstLine.substring(31, 32).trim());
            nSat = Integer.parseInt(firstLine.substring(32, 35).trim());
        } else {
            if (firstLine.length() < 32) firstLine = String.format("%-80s", firstLine);

            String trimmed = firstLine.trim();
            if (trimmed.isEmpty() || !Character.isDigit(trimmed.charAt(0))) {
                log.warn("RINEX2 skipping non-epoch line: [{}]", firstLine);
                return new Obsd[0];
            }

            try {
                year = Integer.parseInt(firstLine.substring(1, 3).trim());
                month = Integer.parseInt(firstLine.substring(4, 6).trim());
                day = Integer.parseInt(firstLine.substring(7, 9).trim());
                hour = Integer.parseInt(firstLine.substring(10, 12).trim());
                min = Integer.parseInt(firstLine.substring(13, 15).trim());
                sec = Double.parseDouble(firstLine.substring(15, 25).trim());
                flag = Integer.parseInt(firstLine.substring(27, 29).trim());
                nSat = Integer.parseInt(firstLine.substring(29, 32).trim());
            } catch (NumberFormatException e) {
                log.error("RINEX2 epoch parse error on line: [{}]", firstLine, e);
                return new Obsd[0];
            }
            if (year < 80) year += 2000;
            else year += 1900;

            satNos = new int[nSat];
            int satIdx = 0;
            String satLine = firstLine;
            int lineStart = 32;

            while (satIdx < nSat) {
                for (int pos = lineStart; pos + 3 <= satLine.length() && satIdx < nSat; pos += 3) {
                    String satId = satLine.substring(pos, pos + 3).trim();
                    if (!satId.isEmpty()) {
                        satNos[satIdx] = parseSatId(satId);
                        satIdx++;
                    }
                }
                if (satIdx < nSat) {
                    satLine = reader.readLine();
                    if (satLine == null) break;
                    lineStart = 0;
                }
            }
        }

        int[] weekArr = new int[1];
        double tow = TimeSystem.time2gpst(
                TimeSystem.epoch2time(new double[]{year, month, day, hour, min, sec}), weekArr);
        GTime time = TimeSystem.gpst2time(weekArr[0], tow);

        List<Obsd> obsList = new ArrayList<>();

        for (int i = 0; i < nSat; i++) {
            Obsd obsd = new Obsd();
            obsd.time = time;
            obsd.rcv = 0;
            int si;
            int nt;

            if (this.version >= 3.0) {
                String line = reader.readLine();
                if (line == null) break;

                String satId = line.substring(0, Math.min(3, line.length())).trim();
                char sysChar = satId.charAt(0);
                int prn = Integer.parseInt(satId.substring(1));
                int sys = charToSys(sysChar);
                obsd.sat = SatUtils.satno(sys, prn);
                if (obsd.sat == 0) continue;

                si = SYSCODES.indexOf(sysChar);
                nt = (si >= 0) ? nobs[si] : 0;

                int pos = 3;
                for (int j = 0; j < nt; j++) {
                    if (pos + 14 > line.length()) break;

                    double value = parseDouble(line, pos, 14);
                    int lli = (pos + 14 < line.length()) ? parseInteger(line, pos + 14, 1) : 0;
                    int snr = (pos + 15 < line.length()) ? parseInteger(line, pos + 15, 1) : 0;
                    pos += 16;

                    if (si < 0 || value == 0.0) continue;

                    String tobj = tobs[si][j];
                    if (tobj == null || tobj.length() < 2) continue;

                    String obsCode = tobj.substring(1);
                    int codeVal = ObsCode.obs2code(obsCode);
                    int freqIdx = ObsCode.code2idx(sys, codeVal);
                    if (freqIdx < 0 || freqIdx >= Constants.NFREQ + Constants.NEXOBS) continue;

                    char typeChar = tobj.charAt(0);
                    switch (typeChar) {
                        case 'C':
                        case 'P':
                            obsd.P[freqIdx] = value;
                            obsd.code[freqIdx] = codeVal;
                            obsd.Pstd[freqIdx] = (float) snr;
                            break;
                        case 'L':
                            obsd.L[freqIdx] = value;
                            obsd.LLI[freqIdx] = lli;
                            obsd.Lstd[freqIdx] = (float) snr;
                            if (obsd.code[freqIdx] == 0) {
                                obsd.code[freqIdx] = codeVal;
                            }
                            break;
                        case 'D':
                            obsd.D[freqIdx] = (float) value;
                            break;
                        case 'S':
                            obsd.SNR[freqIdx] = (float) value;
                            break;
                    }
                }
            } else {
                obsd.sat = (satNos != null && i < satNos.length) ? satNos[i] : 0;

                String sysCharStr = obsd.sat != 0 ? ObsCode.satToSysChar(obsd.sat) : "G";
                char sysChar = sysCharStr.isEmpty() ? 'G' : sysCharStr.charAt(0);
                si = SYSCODES.indexOf(sysChar);
                nt = nobs[0];

                String dataLine = null;
                int pos = 0;
                for (int j = 0; j < nt; j++) {
                    if (j % 5 == 0) {
                        dataLine = reader.readLine();
                        if (dataLine == null) break;
                        pos = 0;
                    }
                    if (pos + 14 > dataLine.length()) {
                        pos += 16;
                        continue;
                    }

                    double value = parseDouble(dataLine, pos, 14);
                    int lli = (pos + 14 < dataLine.length()) ? parseInteger(dataLine, pos + 14, 1) : 0;
                    int snr = (pos + 15 < dataLine.length()) ? parseInteger(dataLine, pos + 15, 1) : 0;
                    pos += 16;

                    if (obsd.sat == 0 || value == 0.0) continue;

                    String tobj = tobs[0][j];
                    if (tobj == null || tobj.length() < 2) continue;

                    char typeChar = tobj.charAt(0);
                    int freqNo;
                    try {
                        freqNo = Integer.parseInt(tobj.substring(1));
                    } catch (NumberFormatException e) {
                        continue;
                    }

                    int freqIdx = v2FreqNoToIdx(sysChar, freqNo);
                    if (freqIdx < 0 || freqIdx >= Constants.NFREQ + Constants.NEXOBS) continue;

                    int codeVal = v2FreqNoToCode(sysChar, freqNo, typeChar);

                    switch (typeChar) {
                        case 'C':
                        case 'P':
                            obsd.P[freqIdx] = value;
                            obsd.code[freqIdx] = codeVal;
                            obsd.Pstd[freqIdx] = (float) snr;
                            break;
                        case 'L':
                            obsd.L[freqIdx] = value;
                            obsd.LLI[freqIdx] = lli;
                            obsd.Lstd[freqIdx] = (float) snr;
                            if (obsd.code[freqIdx] == 0) {
                                obsd.code[freqIdx] = codeVal;
                            }
                            break;
                        case 'D':
                            obsd.D[freqIdx] = (float) value;
                            break;
                        case 'S':
                            obsd.SNR[freqIdx] = (float) value;
                            break;
                    }
                }
            }

            if (obsd.sat != 0) {
                obsList.add(obsd);
            }
        }

        return obsList.toArray(new Obsd[0]);
    }

    private static int v2FreqNoToIdx(char sysChar, int freqNo) {
        switch (sysChar) {
            case 'G':
                switch (freqNo) { case 1: return 0; case 2: return 1; case 5: return 2; default: return -1; }
            case 'R':
                switch (freqNo) { case 1: return 0; case 2: return 1; case 3: return 2; default: return -1; }
            case 'E':
                switch (freqNo) { case 1: return 0; case 5: return 1; case 6: return 2; case 7: return 3; case 8: return 4; default: return -1; }
            case 'C':
                switch (freqNo) { case 1: return 0; case 2: return 1; case 5: return 2; case 6: return 3; case 7: return 4; case 8: return 5; default: return -1; }
            case 'J':
                switch (freqNo) { case 1: return 0; case 2: return 1; case 5: return 2; case 6: return 3; default: return -1; }
            case 'S':
                switch (freqNo) { case 1: return 0; case 5: return 1; default: return -1; }
            default:
                return -1;
        }
    }

    private static int v2FreqNoToCode(char sysChar, int freqNo, char typeChar) {
        switch (sysChar) {
            case 'G':
                switch (freqNo) {
                    case 1: return typeChar == 'P' ? 3 : 1;
                    case 2: return typeChar == 'P' ? 19 : 14;
                    case 5: return 5;
                    default: return 0;
                }
            case 'C':
                switch (freqNo) {
                    case 1: return 40;
                    case 2: return 27;
                    case 5: return 58;
                    case 6: return 42;
                    case 7: return 2;
                    case 8: return 37;
                    default: return 0;
                }
            case 'R':
                switch (freqNo) {
                    case 1: return typeChar == 'P' ? 3 : 1;
                    case 2: return typeChar == 'P' ? 19 : 14;
                    default: return 0;
                }
            case 'E':
                switch (freqNo) {
                    case 1: return 1;
                    case 5: return 5;
                    case 6: return 32;
                    case 7: return 27;
                    case 8: return 37;
                    default: return 0;
                }
            default:
                return 0;
        }
    }

    /**
     * Read navigation ephemeris (RINEX 3.x).
     * @param reader BufferedReader
     * @param firstLine First line of ephemeris
     */
    private void readNavEphV3(BufferedReader reader, String firstLine) throws IOException {
        String satId = firstLine.substring(0, Math.min(3, firstLine.length())).trim();
        char sysChar = satId.charAt(0);
        int prn = Integer.parseInt(satId.substring(1));
        int sys = charToSys(sysChar);
        int sat = SatUtils.satno(sys, prn);
        if (sat == 0) {
            log.warn("readNavEphV3: invalid satellite satId={}, prn={}", satId, prn);
            return;
        }

        if (sys == Constants.SYS_GPS || sys == Constants.SYS_GAL ||
                sys == Constants.SYS_QZS || sys == Constants.SYS_CMP || sys == Constants.SYS_IRN) {
            readEphV3(reader, sat, sys, firstLine);
        } else if (sys == Constants.SYS_GLO) {
            readGephV3(reader, sat, firstLine);
        } else if (sys == Constants.SYS_SBS) {
            readSephV3(reader, sat, firstLine);
        }
    }

    /**
     * Read navigation ephemeris (RINEX 2.x).
     * @param reader BufferedReader
     * @param firstLine First line of ephemeris
     */
    private void readNavEphV2(BufferedReader reader, String firstLine) throws IOException {
        int prn = Integer.parseInt(firstLine.substring(0, 2).trim());
        int sat;
        int sys = charToSys((char) this.sys);

        if (sys == 0) {
            if (prn <= 32) sys = Constants.SYS_GPS;
            else if (prn <= 64) { sys = Constants.SYS_GLO; prn -= 32; }
            else if (prn <= 100) { sys = Constants.SYS_CMP; prn -= 64; }
            else if (prn <= 136) { sys = Constants.SYS_GAL; prn -= 100; }
            else if (prn <= 192 + 10) { sys = Constants.SYS_QZS; prn -= 192; }
            else { sys = Constants.SYS_GPS; }
        }

        if (sys == Constants.SYS_SBS) {
            sat = SatUtils.satno(Constants.SYS_SBS, prn + 100);
        } else {
            sat = SatUtils.satno(sys, prn);
        }
        if (sat == 0) return;

        if (sys == Constants.SYS_GLO) {
            readGephV2(reader, sat, firstLine);
        } else if (sys == Constants.SYS_SBS) {
            readSephV2(reader, sat, firstLine);
        } else {
            readEphV2(reader, sat, sys, firstLine);
        }
    }

    /**
     * URA values for uraindex lookup (ref [3] 20.3.3.3.1.1).
     */
    private static final double[] URA_EPH = {
            2.4, 3.4, 4.85, 6.85, 9.65, 13.65, 24.0, 48.0,
            96.0, 192.0, 384.0, 768.0, 1536.0, 3072.0, 6144.0, 0.0
    };

    /**
     * URA value (m) to URA index.
     * @param value URA value in meters
     * @return URA index (0..15)
     */
    private static int uraindex(double value) {
        for (int i = 0; i < 15; i++) {
            if (URA_EPH[i] >= value) return i;
        }
        return 15;
    }

    /**
     * Galileo SISA value (m) to SISA index.
     * @param value SISA value in meters
     * @return SISA index
     */
    private static int sisaIndex(double value) {
        if (value < 0.0 || value > 6.0) return 255;
        else if (value <= 0.49) return (int) Math.round(value / 0.01);
        else if (value <= 0.98) return (int) Math.round((value - 0.5) / 0.02) + 50;
        else if (value <= 1.96) return (int) Math.round((value - 1.0) / 0.04) + 75;
        return (int) Math.round((value - 2.0) / 0.16) + 100;
    }

    /**
     * Adjust time for week handover.
     * @param t  Time to adjust
     * @param t0 Reference time
     * @return Adjusted time
     */
    private GTime adjweek(GTime t, GTime t0) {
        double tt = TimeSystem.timediff(t, t0);
        if (tt < -302400.0) return TimeSystem.timeadd(t, 604800.0);
        if (tt > 302400.0) return TimeSystem.timeadd(t, -604800.0);
        return t;
    }

    /**
     * Read GPS/GAL/QZS/BDS/IRN ephemeris (RINEX 3.x).
     * Builds data[] array and calls decodeEph.
     * @param reader BufferedReader
     * @param sat Satellite number
     * @param sys System code
     * @param firstLine First line
     */
    private void readEphV3(BufferedReader reader, int sat, int sys, String firstLine) throws IOException {
        double[] data = new double[64];
        int i = 0;

        String[] lines = new String[8];
        lines[0] = firstLine;
        for (int k = 1; k < 8; k++) {
            lines[k] = reader.readLine();
            if (lines[k] == null) {
                log.warn("RINEX nav ephemeris truncated at line {} for sat={}", k, sat);
                return;
            }
        }

        GTime toc = parseTime(lines[0], 4, 19);
        if (toc == null) {
            log.warn("RINEX nav toc parse error for sat={}, line=[{}]", sat, lines[0]);
            return;
        }

        for (int j = 0; j < 3; j++) data[i++] = parseDouble(lines[0], 4 + 19 + j * 19, 19);
        for (int k = 1; k < 8; k++) {
            for (int j = 0; j < 4; j++) data[i++] = parseDouble(lines[k], 4 + j * 19, 19);
        }

        log.debug("readEphV3: sat={}, sys={}, i={}, data[21]={}, data[3]={}, data[11]={}",
                sat, sys, i, data[21], data[3], data[11]);

        decodeEph(this.version, sat, toc, data);
    }

    /**
     * Read GPS/GAL/QZS/BDS/IRN ephemeris (RINEX 2.x).
     * @param reader BufferedReader
     * @param sat Satellite number
     * @param sys System code
     * @param firstLine First line
     */
    private void readEphV2(BufferedReader reader, int sat, int sys, String firstLine) throws IOException {
        double[] data = new double[64];
        int i = 0;

        String[] lines = new String[8];
        lines[0] = firstLine;
        for (int k = 1; k < 8; k++) {
            lines[k] = reader.readLine();
            if (lines[k] == null) {
                log.warn("RINEX nav ephemeris truncated at line {} for sat={}", k, sat);
                return;
            }
        }

        GTime toc = parseTime(lines[0], 3, 19);
        if (toc == null) {
            log.warn("RINEX nav toc parse error for sat={}", sat);
            return;
        }

        for (int j = 0; j < 3; j++) data[i++] = parseDouble(lines[0], 3 + 19 + j * 19, 19);
        for (int k = 1; k < 8; k++) {
            for (int j = 0; j < 4; j++) data[i++] = parseDouble(lines[k], 3 + j * 19, 19);
        }

        decodeEph(this.version, sat, toc, data);
    }

    /**
     * Read GLONASS ephemeris (RINEX 3.x).
     * @param reader BufferedReader
     * @param sat Satellite number
     * @param firstLine First line
     */
    private void readGephV3(BufferedReader reader, int sat, String firstLine) throws IOException {
        double[] data = new double[64];
        int i = 0;

        int nglo = this.version >= 3.05 ? 19 : 15;
        int nlines = (nglo - 3 + 3) / 4 + 1; // ceil((nglo-3)/4) + 1

        String[] lines = new String[nlines];
        lines[0] = firstLine;
        for (int k = 1; k < nlines; k++) {
            lines[k] = reader.readLine();
            if (lines[k] == null) {
                log.warn("RINEX GLO nav ephemeris truncated at line {} for sat={}", k, sat);
                return;
            }
        }

        GTime toc = parseTime(lines[0], 4, 19);
        if (toc == null) {
            log.warn("RINEX GLO nav toc parse error for sat={}", sat);
            return;
        }

        for (int j = 0; j < 3; j++) data[i++] = parseDouble(lines[0], 4 + 19 + j * 19, 19);
        for (int k = 1; k < nlines && i < nglo; k++) {
            for (int j = 0; j < 4 && i < nglo; j++) data[i++] = parseDouble(lines[k], 4 + j * 19, 19);
        }

        decodeGeph(this.version, sat, toc, data);
    }

    /**
     * Read GLONASS ephemeris (RINEX 2.x).
     * @param reader BufferedReader
     * @param sat Satellite number
     * @param firstLine First line
     */
    private void readGephV2(BufferedReader reader, int sat, String firstLine) throws IOException {
        double[] data = new double[64];
        int i = 0;

        String[] lines = new String[4];
        lines[0] = firstLine;
        for (int k = 1; k < 4; k++) {
            lines[k] = reader.readLine();
            if (lines[k] == null) {
                log.warn("RINEX GLO nav ephemeris truncated at line {} for sat={}", k, sat);
                return;
            }
        }

        GTime toc = parseTime(lines[0], 3, 19);
        if (toc == null) {
            log.warn("RINEX GLO nav toc parse error for sat={}", sat);
            return;
        }

        for (int j = 0; j < 3; j++) data[i++] = parseDouble(lines[0], 3 + 19 + j * 19, 19);
        for (int k = 1; k < 4; k++) {
            for (int j = 0; j < 4; j++) data[i++] = parseDouble(lines[k], 3 + j * 19, 19);
        }

        decodeGeph(this.version, sat, toc, data);
    }

    /**
     * Read SBAS ephemeris (RINEX 3.x).
     * @param reader BufferedReader
     * @param sat Satellite number
     * @param firstLine First line
     */
    private void readSephV3(BufferedReader reader, int sat, String firstLine) throws IOException {
        double[] data = new double[64];
        int i = 0;

        String[] lines = new String[4];
        lines[0] = firstLine;
        for (int k = 1; k < 4; k++) {
            lines[k] = reader.readLine();
            if (lines[k] == null) {
                log.warn("RINEX SBAS nav ephemeris truncated at line {} for sat={}", k, sat);
                return;
            }
        }

        GTime toc = parseTime(lines[0], 4, 19);
        if (toc == null) {
            log.warn("RINEX SBAS nav toc parse error for sat={}", sat);
            return;
        }

        for (int j = 0; j < 3; j++) data[i++] = parseDouble(lines[0], 4 + 19 + j * 19, 19);
        for (int k = 1; k < 4; k++) {
            for (int j = 0; j < 4; j++) data[i++] = parseDouble(lines[k], 4 + j * 19, 19);
        }

        decodeSeph(this.version, sat, toc, data);
    }

    /**
     * Read SBAS ephemeris (RINEX 2.x).
     * @param reader BufferedReader
     * @param sat Satellite number
     * @param firstLine First line
     */
    private void readSephV2(BufferedReader reader, int sat, String firstLine) throws IOException {
        double[] data = new double[64];
        int i = 0;

        String[] lines = new String[4];
        lines[0] = firstLine;
        for (int k = 1; k < 4; k++) {
            lines[k] = reader.readLine();
            if (lines[k] == null) {
                log.warn("RINEX SBAS nav ephemeris truncated at line {} for sat={}", k, sat);
                return;
            }
        }

        GTime toc = parseTime(lines[0], 3, 19);
        if (toc == null) {
            log.warn("RINEX SBAS nav toc parse error for sat={}", sat);
            return;
        }

        for (int j = 0; j < 3; j++) data[i++] = parseDouble(lines[0], 3 + 19 + j * 19, 19);
        for (int k = 1; k < 4; k++) {
            for (int j = 0; j < 4; j++) data[i++] = parseDouble(lines[k], 3 + j * 19, 19);
        }

        decodeSeph(this.version, sat, toc, data);
    }

    /**
     * Convert system character to system code.
     * @param c System character ('G', 'R', 'E', 'C', 'J', 'I', 'S')
     * @return System code
     */
    private int charToSys(char c) {
        switch (c) {
            case 'G': return Constants.SYS_GPS;
            case 'R': return Constants.SYS_GLO;
            case 'E': return Constants.SYS_GAL;
            case 'C': return Constants.SYS_CMP;
            case 'J': return Constants.SYS_QZS;
            case 'I': return Constants.SYS_IRN;
            case 'S': return Constants.SYS_SBS;
            default: return 0;
        }
    }

    /**
     * Parse time from substring.
     * @param line Input line
     * @param start Start position
     * @param length Length
     * @return GTime or null on parse error
     */
    private GTime parseTime(String line, int start, int length) {
        try {
            int end = Math.min(start + length, line.length());
            String substr = line.substring(start, end).trim();
            String[] parts = substr.split("\\s+");
            if (parts.length < 6) return null;
            int year = Integer.parseInt(parts[0]);
            int month = Integer.parseInt(parts[1]);
            int day = Integer.parseInt(parts[2]);
            int hour = Integer.parseInt(parts[3]);
            int min = Integer.parseInt(parts[4]);
            double sec = Double.parseDouble(parts[5]);
            if (year < 100) {
                if (year < 80) year += 2000;
                else year += 1900;
            }
            return TimeSystem.epoch2time(new double[]{year, month, day, hour, min, sec});
        } catch (Exception e) {
            log.warn("RINEX time parse error: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Adjust time by day boundary.
     * @param t  Time to adjust
     * @param t0 Reference time
     * @return Adjusted time
     */
    private GTime adjday(GTime t, GTime t0) {
        double tt = TimeSystem.timediff(t, t0);
        if (tt < -43200.0) return TimeSystem.timeadd(t, 86400.0);
        if (tt > 43200.0) return TimeSystem.timeadd(t, -86400.0);
        return t;
    }

    /**
     * Decode GPS/GAL/QZS/BDS/IRN broadcast ephemeris from data array.
     * Aligned with RTKLIB rinex.c decode_eph().
     * @param ver  RINEX version
     * @param sat  Satellite number
     * @param toc  Time of clock
     * @param data Data array
     */
    private void decodeEph(double ver, int sat, GTime toc, double[] data) {
        int sys = SatUtils.satsys(sat, null);
        if ((sys & (Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_QZS |
                Constants.SYS_CMP | Constants.SYS_IRN)) == 0) {
            log.warn("decodeEph: invalid satellite sat={}", sat);
            return;
        }

        Eph eph = new Eph();
        eph.sat = sat;
        eph.toc = new GTime(toc);

        eph.f0 = data[0];
        eph.f1 = data[1];
        eph.f2 = data[2];

        eph.A = data[10] * data[10];
        eph.e = data[8];
        eph.i0 = data[15];
        eph.OMG0 = data[13];
        eph.omg = data[17];
        eph.M0 = data[6];
        eph.deln = data[5];
        eph.OMGd = data[18];
        eph.idot = data[19];
        eph.crc = data[16];
        eph.crs = data[4];
        eph.cuc = data[7];
        eph.cus = data[9];
        eph.cic = data[12];
        eph.cis = data[14];

        if (sys == Constants.SYS_GPS || sys == Constants.SYS_QZS) {
            eph.iode = (int) data[3];
            eph.iodc = (int) data[26];
            eph.toes = data[11];
            eph.week = (int) data[21];
            eph.toe = adjweek(TimeSystem.gpst2time(eph.week, data[11]), toc);
            eph.ttr = adjweek(TimeSystem.gpst2time(eph.week, data[27]), toc);

            eph.code = (int) data[20];
            eph.svh = (int) data[24];
            eph.sva = uraindex(data[23]);
            eph.flag = (int) data[22];

            eph.tgd[0] = data[25];
            if (sys == Constants.SYS_GPS) {
                eph.fit = data[28];
            } else {
                eph.fit = data[28] == 0.0 ? 2.0 : 4.0;
            }
        } else if (sys == Constants.SYS_GAL) {
            eph.iode = (int) data[3];
            eph.toes = data[11];
            eph.week = (int) data[21];
            eph.toe = adjweek(TimeSystem.gpst2time(eph.week, data[11]), toc);
            eph.ttr = adjweek(TimeSystem.gpst2time(eph.week, data[27]), toc);

            eph.code = (int) data[20];
            eph.svh = (int) data[24];
            eph.sva = sisaIndex(data[23]);

            eph.tgd[0] = data[25];
            eph.tgd[1] = data[26];
        } else if (sys == Constants.SYS_CMP) {
            eph.toc = TimeSystem.bdt2gpst(eph.toc);
            eph.iode = (int) data[3];
            eph.iodc = (int) data[28];
            eph.toes = data[11];
            eph.week = (int) data[21];
            eph.toe = TimeSystem.bdt2gpst(TimeSystem.bdt2time(eph.week, data[11]));
            eph.ttr = TimeSystem.bdt2gpst(TimeSystem.bdt2time(eph.week, data[27]));
            eph.toe = adjweek(eph.toe, toc);
            eph.ttr = adjweek(eph.ttr, toc);

            eph.svh = (int) data[24];
            eph.sva = uraindex(data[23]);

            eph.tgd[0] = data[25];
            eph.tgd[1] = data[26];
        } else if (sys == Constants.SYS_IRN) {
            eph.iode = (int) data[3];
            eph.toes = data[11];
            eph.week = (int) data[21];
            eph.toe = adjweek(TimeSystem.gpst2time(eph.week, data[11]), toc);
            eph.ttr = adjweek(TimeSystem.gpst2time(eph.week, data[27]), toc);
            eph.svh = (int) data[24];
            eph.sva = uraindex(data[23]);
            eph.tgd[0] = data[25];
        }

        if (nav.n < nav.nmax) {
            nav.eph[nav.n] = eph;
            nav.n++;
            log.debug("decodeEph: stored eph sat={}, week={}, toes={}, nav.n={}",
                    sat, eph.week, eph.toes, nav.n);
        } else {
            log.warn("decodeEph: nav buffer full, nav.n={}, nav.nmax={}", nav.n, nav.nmax);
        }
    }

    /**
     * Decode GLONASS broadcast ephemeris from data array.
     * Aligned with RTKLIB rinex.c decode_geph().
     * @param ver  RINEX version
     * @param sat  Satellite number
     * @param toc  Time of clock
     * @param data Data array
     */
    private void decodeGeph(double ver, int sat, GTime toc, double[] data) {
        if (SatUtils.satsys(sat, null) != Constants.SYS_GLO) {
            log.warn("decodeGeph: invalid satellite sat={}", sat);
            return;
        }

        Geph geph = new Geph();
        geph.sat = sat;

        int[] week = new int[1];
        double tow = TimeSystem.time2gpst(toc, week);
        toc = TimeSystem.gpst2time(week[0], Math.floor((tow + 450.0) / 900.0) * 900.0);
        int dow = (int) Math.floor(tow / 86400.0);

        double tod = ver <= 2.99 ? data[2] : data[2] % 86400.0;
        GTime tof = TimeSystem.gpst2time(week[0], tod + dow * 86400.0);
        tof = adjday(tof, toc);

        geph.toe = TimeSystem.utc2gpst(toc);
        geph.tof = TimeSystem.utc2gpst(tof);

        geph.iode = (int) (Math.floor((tow + 10800.0) % 86400.0 / 900.0 + 0.5));

        geph.taun = -data[0];
        geph.gamn = data[1];

        geph.pos[0] = data[3] * 1E3;
        geph.pos[1] = data[7] * 1E3;
        geph.pos[2] = data[11] * 1E3;
        geph.vel[0] = data[4] * 1E3;
        geph.vel[1] = data[8] * 1E3;
        geph.vel[2] = data[12] * 1E3;
        geph.acc[0] = data[5] * 1E3;
        geph.acc[1] = data[9] * 1E3;
        geph.acc[2] = data[13] * 1E3;

        geph.svh = (int) data[6];
        geph.frq = (int) data[10];
        geph.age = (int) data[14];

        if (ver >= 3.05) {
            geph.flags = (int) data[15];
            geph.dtaun = data[16];
            geph.sva = (int) data[17];
            geph.svh |= ((int) data[18]) << 1;
        }

        if (geph.frq > 128) geph.frq -= 256;

        if (nav.ng < nav.ngmax) {
            nav.geph[nav.ng] = geph;
            nav.ng++;
        }
    }

    /**
     * Decode SBAS broadcast ephemeris from data array.
     * Aligned with RTKLIB rinex.c decode_seph().
     * @param ver  RINEX version
     * @param sat  Satellite number
     * @param toc  Time of clock
     * @param data Data array
     */
    private void decodeSeph(double ver, int sat, GTime toc, double[] data) {
        if (SatUtils.satsys(sat, null) != Constants.SYS_SBS) {
            log.warn("decodeSeph: invalid satellite sat={}", sat);
            return;
        }

        Seph seph = new Seph();
        seph.sat = sat;
        seph.t0 = new GTime(toc);

        int[] week = new int[1];
        TimeSystem.time2gpst(toc, week);
        seph.tof = adjweek(TimeSystem.gpst2time(week[0], data[2]), toc);

        seph.af0 = data[0];
        seph.af1 = data[1];

        seph.pos[0] = data[3] * 1E3;
        seph.pos[1] = data[7] * 1E3;
        seph.pos[2] = data[11] * 1E3;
        seph.vel[0] = data[4] * 1E3;
        seph.vel[1] = data[8] * 1E3;
        seph.vel[2] = data[12] * 1E3;
        seph.acc[0] = data[5] * 1E3;
        seph.acc[1] = data[9] * 1E3;
        seph.acc[2] = data[13] * 1E3;

        seph.svh = (int) data[6];
        seph.sva = uraindex(data[10]);

        if (nav.ns < nav.nsmax) {
            nav.seph[nav.ns] = seph;
            nav.ns++;
        }
    }

    /**
     * Parse double from substring.
     * @param line Input line
     * @param start Start position
     * @param length Length
     * @return Parsed value
     */
    private int parseSatId(String satId) {
        try {
            if (satId == null || satId.isEmpty()) return 0;
            char sc;
            int prn;
            if (satId.length() >= 3 && Character.isLetter(satId.charAt(0))) {
                sc = satId.charAt(0);
                prn = Integer.parseInt(satId.substring(1));
            } else if (satId.length() >= 2 && Character.isDigit(satId.charAt(0))) {
                prn = Integer.parseInt(satId);
                sc = 'G';
            } else if (satId.length() == 1 && Character.isDigit(satId.charAt(0))) {
                prn = Integer.parseInt(satId);
                sc = 'G';
            } else {
                return 0;
            }
            return SatUtils.satno(charToSys(sc), prn);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private double parseDouble(String line, int start, int length) {
        try {
            String substr = line.substring(start, Math.min(start + length, line.length())).trim();
            if (substr.isEmpty() || substr.equals("D") || substr.equals("E")) return 0.0;
            return Double.parseDouble(substr.replace('D', 'E'));
        } catch (Exception e) {
            return 0.0;
        }
    }

    /**
     * Parse integer from substring.
     * @param line Input line
     * @param start Start position
     * @param length Length
     * @return Parsed value
     */
    private int parseInteger(String line, int start, int length) {
        try {
            String substr = line.substring(start, Math.min(start + length, line.length())).trim();
            if (substr.isEmpty()) return 0;
            return Integer.parseInt(substr);
        } catch (Exception e) {
            return 0;
        }
    }
}
