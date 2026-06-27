package org.rtklib.java.rinex;

public class Old3 {

import org.rtklib.java.data.*;
        import org.rtklib.java.constants.Constants;
import org.rtklib.java.time.TimeSystem;
import org.rtklib.java.common.ObsCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.text.DecimalFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * RINEX observation file writer.
 * Supports RINEX 2.11 and 3.05/3.06 formats.
 * Aligned with RTKLIB rnxout.c.
 */
public class RinexObsWriter {
    private static final Logger log = LoggerFactory.getLogger(RinexObsWriter.class);

    /** RINEX version */
    private double version;

    /** Output file path */
    private String filePath;

    /** Station information */
    private Sta sta;

    /** Observation data */
    private Obs obs;

    /** RINEX format version */
    private String formatVersion;

    /** GNSS system mask */
    private int navsys;

    /** Observation codes */
    private Set<String> obsCodes;

    /**
     * Constructor.
     * @param version RINEX version (2.11, 3.05, or 3.06)
     * @param filePath Output file path
     * @param sta Station information
     */
    public RinexObsWriter(double version, String filePath, Sta sta) {
        this.version = version;
        this.filePath = filePath;
        this.sta = sta;
        this.obs = null;
        this.formatVersion = String.format("%4.2f", version);
        this.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL | Constants.SYS_CMP;
        this.obsCodes = new LinkedHashSet<>();
    }

    /**
     * Set observation data.
     * @param obs Observation data
     */
    public void setObsData(Obs obs) {
        this.obs = obs;
    }

    /**
     * Write RINEX observation file.
     * @return true on success, false on error
     */
    public boolean write() {
        if (this.obs == null || this.obs.data == null || this.obs.data.length == 0) {
            log.error("No observation data to write");
            return false;
        }

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
            // Write header
            writeHeader(writer);

            // Write observation data
            writeObservations(writer);

            log.info("RINEX observation file written: {}", filePath);
            return true;
        } catch (IOException e) {
            log.error("Error writing RINEX observation file: {}", filePath, e);
            return false;
        }
    }

    /**
     * Write RINEX header.
     * @param writer BufferedWriter
     * @throws IOException if write fails
     */
    private void writeHeader(BufferedWriter writer) throws IOException {
        // RINEX VERSION / TYPE
        String sysChar = getSysChar(navsys);
        writer.write(String.format("%9.2f%-11s%-20s%-20s%-20s\n",
                version, "", "OBSERVATION DATA", sysChar, "RINEX VERSION / TYPE"));

        // PGM / RUN BY / DATE
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd HHmmss");
        String dateStr = sdf.format(new Date());
        writer.write(String.format("%-20s%-20s%-20s%-20s\n",
                "rtklib_java", "USER", dateStr, "PGM / RUN BY / DATE"));

        // MARKER NAME
        String markerName = sta.name != null && !sta.name.isEmpty() ? sta.name : "UNKNOWN";
        writer.write(String.format("%-60s%-20s\n", markerName, "MARKER NAME"));

        // MARKER NUMBER
        if (sta.marker != null && !sta.marker.isEmpty()) {
            writer.write(String.format("%-20s%-60s%-20s\n", sta.marker, "", "MARKER NUMBER"));
        }

        // OBSERVER / AGENCY
        writer.write(String.format("%-20s%-40s%-20s\n", "USER", "AGENCY", "OBSERVER / AGENCY"));

        // REC # / TYPE / VERS
        String recType = sta.rectype != null && !sta.rectype.isEmpty() ? sta.rectype : "UNKNOWN";
        writer.write(String.format("%-20s%-20s%-20s%-20s\n", "", recType, "", "REC # / TYPE / VERS"));

        // ANT # / TYPE
        String antType = sta.antdes != null && !sta.antdes.isEmpty() ? sta.antdes : "UNKNOWN";
        writer.write(String.format("%-20s%-20s%-40s%-20s\n", "", antType, "", "ANT # / TYPE"));

        // APPROX POSITION XYZ
        if (sta.pos != null && sta.pos.length == 3) {
            writer.write(String.format("%14.4f%14.4f%14.4f%-18s%-20s\n",
                    sta.pos[0], sta.pos[1], sta.pos[2], "", "APPROX POSITION XYZ"));
        }

        // ANTENNA: DELTA H/E/N
        if (sta.del != null && sta.del.length >= 3) {
            writer.write(String.format("%14.4f%14.4f%14.4f%-18s%-20s\n",
                    sta.del[2], sta.del[0], sta.del[1], "", "ANTENNA: DELTA H/E/N"));
        }

        // SYS / # / OBS TYPES
        writeObsTypes(writer);

        // TIME OF FIRST OBS
        if (obs.data.length > 0) {
            writeFirstObsTime(writer, obs.data[0].time);
        }

        // END OF HEADER
        writer.write(String.format("%-60s%-20s\n", "", "END OF HEADER"));
    }

    /**
     * Write observation types.
     * @param writer BufferedWriter
     * @throws IOException if write fails
     */
    private void writeObsTypes(BufferedWriter writer) throws IOException {
        // Collect observation codes from data
        collectObsCodes();

        if (version >= 3.0) {
            // RINEX 3.x format
            writeObsTypesV3(writer);
        } else {
            // RINEX 2.x format
            writeObsTypesV2(writer);
        }
    }

    /**
     * Write observation types (RINEX 3.x).
     * @param writer BufferedWriter
     * @throws IOException if write fails
     */
    private void writeObsTypesV3(BufferedWriter writer) throws IOException {
        String[] systems = {"G", "R", "E", "C", "J", "I", "S"};
        int[] sysCodes = {Constants.SYS_GPS, Constants.SYS_GLO, Constants.SYS_GAL,
                Constants.SYS_CMP, Constants.SYS_QZS, Constants.SYS_IRN, Constants.SYS_SBS};

        for (int i = 0; i < systems.length; i++) {
            if ((navsys & sysCodes[i]) == 0) continue;

            java.util.List<String> sysObs = new java.util.ArrayList<>();
            for (String code : obsCodes) {
                if (code.startsWith(systems[i])) {
                    sysObs.add(code.substring(1));
                }
            }

            if (sysObs.isEmpty()) continue;

            StringBuilder line = new StringBuilder();
            line.append(String.format("%s  %3d", systems[i], sysObs.size()));
            for (int j = 0; j < sysObs.size(); j++) {
                if (j > 0 && j % 13 == 0) {
                    line.append("      ");
                }
                line.append(String.format(" %3s", sysObs.get(j)));
                if (j % 13 == 12 && j < sysObs.size() - 1) {
                    int padLen = 60 - line.length();
                    if (padLen < 0) padLen = 0;
                    line.append(String.format("%-" + padLen + "s%-20s\n", "", "SYS / # / OBS TYPES"));
                    writer.write(line.toString());
                    line = new StringBuilder();
                }
            }
            if (sysObs.size() % 13 > 0 || sysObs.isEmpty()) {
                int padLen = 60 - line.length();
                if (padLen < 0) padLen = 0;
                line.append(String.format("%-" + padLen + "s%-20s\n", "", "SYS / # / OBS TYPES"));
                writer.write(line.toString());
            }
        }
    }

    /**
     * Write observation types (RINEX 2.x).
     * @param writer BufferedWriter
     * @throws IOException if write fails
     */
    private void writeObsTypesV2(BufferedWriter writer) throws IOException {
        // RINEX 2.x uses fixed observation types
        String line = "     9    L1    L2    C1    P1    P2    D1    D2    S1    S2";
        writer.write(line + String.format("%-20s\n", "# / TYPES OF OBSERV"));

        // Wave length factor
        writer.write(String.format("%-60s%-20s\n", "    1    1", "WAVELENGTH FACT L1/2"));
    }

    /**
     * Write time of first observation.
     * @param writer BufferedWriter
     * @param time Time of first observation
     * @throws IOException if write fails
     */
    private void writeFirstObsTime(BufferedWriter writer, GTime time) throws IOException {
        int[] week = new int[1];
        double sec = TimeSystem.time2gpst(time, week);
        double[] ymdhms = TimeSystem.time2ymdhms(time);

        writer.write(String.format("  %04d    %02d    %02d    %02d    %02d%11.7f%5d%-20s%-20s\n",
                (int)ymdhms[0], (int)ymdhms[1], (int)ymdhms[2],
                (int)ymdhms[3], (int)ymdhms[4], ymdhms[5],
                week[0], "", "TIME OF FIRST OBS"));
    }

    /**
     * Write observation data.
     * @param writer BufferedWriter
     * @throws IOException if write fails
     */
    private void writeObservations(BufferedWriter writer) throws IOException {
        if (version >= 3.0) {
            writeObservationsV3(writer);
        } else {
            writeObservationsV2(writer);
        }
    }

    /**
     * Write observation data (RINEX 3.x).
     * @param writer BufferedWriter
     * @throws IOException if write fails
     */
    private void writeObservationsV3(BufferedWriter writer) throws IOException {
        String[] systems = {"G", "R", "E", "C", "J", "I", "S"};
        int[] sysCodes = {Constants.SYS_GPS, Constants.SYS_GLO, Constants.SYS_GAL,
                Constants.SYS_CMP, Constants.SYS_QZS, Constants.SYS_IRN, Constants.SYS_SBS};

        java.util.LinkedHashMap<String, java.util.List<String>> sysObsMap = new java.util.LinkedHashMap<>();
        for (int i = 0; i < systems.length; i++) {
            java.util.List<String> sysObs = new java.util.ArrayList<>();
            for (String code : obsCodes) {
                if (code.startsWith(systems[i])) {
                    sysObs.add(code.substring(1));
                }
            }
            sysObsMap.put(systems[i], sysObs);
        }

        int i = 0;
        int totalObs = (obs.n > 0) ? obs.n : obs.data.length;
        while (i < totalObs) {
            GTime epochTime = obs.data[i].time;
            int nSat = 0;
            int startIdx = i;

            while (i < obs.data.length && TimeSystem.timediff(obs.data[i].time, epochTime) < 0.001) {
                nSat++;
                i++;
            }

            double[] ymdhms = TimeSystem.time2ymdhms(epochTime);
            int flag = 0;
            writer.write(String.format("> %04d %02d %02d %02d %02d %010.7f  %d%3d\n",
                    (int)ymdhms[0], (int)ymdhms[1], (int)ymdhms[2],
                    (int)ymdhms[3], (int)ymdhms[4], ymdhms[5],
                    flag, nSat));

            for (int j = startIdx; j < i; j++) {
                Obsd o = obs.data[j];
                String sysChar = ObsCode.satToSysChar(o.sat);
                int prn = ObsCode.satToPrn(o.sat);
                writer.write(String.format("%s%02d", sysChar, prn));

                java.util.List<String> sysObs = sysObsMap.get(sysChar);
                if (sysObs == null || sysObs.isEmpty()) {
                    writer.write("\n");
                    continue;
                }

                StringBuilder valLine = new StringBuilder();
                for (String obsType : sysObs) {
                    char typeChar = obsType.charAt(0);
                    String freqCode = obsType.substring(1);
                    int codeVal = ObsCode.obs2code(freqCode);
                    int freqIdx = ObsCode.code2idx(ObsCode.charToSys(sysChar), codeVal);

                    double value = 0.0;
                    int lli = 0;
                    int snr = 0;

                    if (freqIdx >= 0 && freqIdx < Constants.NFREQ) {
                        switch (typeChar) {
                            case 'C':
                            case 'P':
                                value = o.P[freqIdx];
                                snr = (int) o.Pstd[freqIdx];
                                break;
                            case 'L':
                                value = o.L[freqIdx];
                                lli = o.LLI[freqIdx];
                                snr = (int) o.Lstd[freqIdx];
                                break;
                            case 'D':
                                value = o.D[freqIdx];
                                break;
                            case 'S':
                                value = o.SNR[freqIdx];
                                break;
                        }
                    }

                    if (value != 0.0) {
                        if (typeChar == 'C' || typeChar == 'P' || typeChar == 'L') {
                            valLine.append(String.format("%14.3f%1d%1d", value, lli, snr));
                        } else {
                            valLine.append(String.format("%14.3f  ", value));
                        }
                    } else {
                        valLine.append("                ");
                    }
                }
                writer.write(valLine.toString());
                writer.write("\n");
            }
        }
    }

    /**
     * Write observation data (RINEX 2.x).
     * @param writer BufferedWriter
     * @throws IOException if write fails
     */
    private void writeObservationsV2(BufferedWriter writer) throws IOException {
        int i = 0;
        int totalObs = (obs.n > 0) ? obs.n : obs.data.length;
        while (i < totalObs) {
            GTime epochTime = obs.data[i].time;
            java.util.List<Integer> epochIdx = new java.util.ArrayList<>();
            java.util.List<String> satIds = new java.util.ArrayList<>();

            while (i < totalObs && TimeSystem.timediff(obs.data[i].time, epochTime) < 0.001) {
                String sysChar = ObsCode.satToSysChar(obs.data[i].sat);
                int prn = ObsCode.satToPrn(obs.data[i].sat);
                epochIdx.add(i);
                satIds.add(String.format("%s%02d", sysChar, prn));
                i++;
            }

            int ns = epochIdx.size();
            double[] ymdhms = TimeSystem.time2ymdhms(epochTime);
            int year = (int)ymdhms[0] % 100;

            writer.write(String.format(" %02d %02d %02d %02d %02d %010.7f  %d%3d",
                    year, (int)ymdhms[1], (int)ymdhms[2],
                    (int)ymdhms[3], (int)ymdhms[4], ymdhms[5],
                    0, ns));

            for (int j = 0; j < ns; j++) {
                if (j > 0 && j % 12 == 0) {
                    writer.write(String.format("\n%32s", ""));
                }
                writer.write(String.format("%-3s", satIds.get(j)));
            }
            writer.write("\n");

            for (int j = 0; j < ns; j++) {
                Obsd o = obs.data[epochIdx.get(j)];
                int sys = ObsCode.charToSys(satIds.get(j).charAt(0));
                int m = 0;
                String[] typePrefixes = {"C", "L", "S"};
                int obsCount = 0;

                for (int freq = 0; freq < Constants.NFREQ; freq++) {
                    if (o.code[freq] == 0) continue;
                    String obsCode = ObsCode.code2obs(o.code[freq]);
                    if (obsCode.isEmpty()) continue;

                    for (String prefix : typePrefixes) {
                        if (obsCount % 5 == 0) writer.write("\n");
                        obsCount++;

                        double value = 0.0;
                        int lli = -1, snr = -1;
                        switch (prefix) {
                            case "C":
                            case "P":
                                value = o.P[freq];
                                snr = (int) o.Pstd[freq];
                                break;
                            case "L":
                                value = o.L[freq];
                                lli = o.LLI[freq];
                                snr = (int) o.Lstd[freq];
                                break;
                            case "S":
                                value = o.SNR[freq];
                                break;
                        }

                        if (value != 0.0) {
                            if (prefix.equals("C") || prefix.equals("P")) {
                                writer.write(String.format("%14.3f%1d%1d", value, 0, snr));
                            } else if (prefix.equals("L")) {
                                writer.write(String.format("%14.3f%1d%1d", value, lli, snr));
                            } else {
                                writer.write(String.format("%14.3f  ", value));
                            }
                        } else {
                            writer.write("                ");
                        }
                    }
                }
            }
        }
    }

    /**
     * Collect observation codes from data.
     */
    private void collectObsCodes() {
        obsCodes.clear();
        java.util.Map<String, java.util.Set<String>> sysObsMap = new java.util.LinkedHashMap<>();
        for (Obsd o : obs.data) {
            String sysChar = ObsCode.satToSysChar(o.sat);
            if (!sysObsMap.containsKey(sysChar)) {
                sysObsMap.put(sysChar, new LinkedHashSet<>());
            }
            java.util.Set<String> sysObs = sysObsMap.get(sysChar);
            int sys = ObsCode.charToSys(sysChar);
            for (int j = 0; j < Constants.NFREQ; j++) {
                if (o.P[j] != 0.0 || o.L[j] != 0.0) {
                    String obsType = ObsCode.code2obs(o.code[j]);
                    if (!obsType.isEmpty()) {
                        int freqIdx = ObsCode.code2idx(sys, o.code[j]);
                        String key = String.format("%d%s", freqIdx, obsType);
                        sysObs.add(key);
                    }
                }
            }
        }

        String[] typePrefixes = {"C", "L", "S"};
        for (String sysChar : sysObsMap.keySet()) {
            java.util.Set<String> sysObs = sysObsMap.get(sysChar);
            java.util.List<String> sortedKeys = new java.util.ArrayList<>(sysObs);
            java.util.Collections.sort(sortedKeys);
            for (String key : sortedKeys) {
                String obsType = key.substring(1);
                for (String prefix : typePrefixes) {
                    obsCodes.add(sysChar + prefix + obsType);
                }
            }
        }
        log.debug("collectObsCodes: {}", obsCodes);
    }

    /**
     * Get system character from system mask.
     * @param navsys System mask
     * @return System character
     */
    private String getSysChar(int navsys) {
        if ((navsys & Constants.SYS_GPS) != 0) return "G";
        if ((navsys & Constants.SYS_GLO) != 0) return "R";
        if ((navsys & Constants.SYS_GAL) != 0) return "E";
        if ((navsys & Constants.SYS_CMP) != 0) return "C";
        if ((navsys & Constants.SYS_QZS) != 0) return "J";
        if ((navsys & Constants.SYS_IRN) != 0) return "I";
        if ((navsys & Constants.SYS_SBS) != 0) return "S";
        return "G";
    }
}{
}
