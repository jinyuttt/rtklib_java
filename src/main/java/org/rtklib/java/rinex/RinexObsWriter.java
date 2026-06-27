package org.rtklib.java.rinex;

import org.rtklib.java.data.*;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.time.TimeSystem;
import org.rtklib.java.common.ObsCode;
import org.rtklib.java.common.SatUtils;
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
 * Supports RINEX 3.05/3.06 format only.
 * Aligned with RTKLIB rnxout.c.
 */
public class RinexObsWriter {
    private static final Logger log = LoggerFactory.getLogger(RinexObsWriter.class);

    private double version;

    private String filePath;

    private Sta sta;

    private Obs obs;

    private String formatVersion;

    private int navsys;

    private Set<String> obsCodes;

    /**
     * Constructor.
     * @param version RINEX version (3.05 or 3.06)
     * @param filePath Output file path
     * @param sta Station information
     */
    public RinexObsWriter(double version, String filePath, Sta sta) {
        if (version < 3.0) {
            throw new IllegalArgumentException("RINEX 2.x is not supported. Use version 3.05 or 3.06.");
        }
        this.version = version;
        this.filePath = filePath;
        this.sta = sta != null ? sta : new Sta();
        this.obs = null;
        this.formatVersion = String.format("%4.2f", version);
        this.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL | Constants.SYS_CMP;
        this.obsCodes = new LinkedHashSet<>();
    }

    public void setObsData(Obs obs) {
        this.obs = obs;
    }

    public boolean write() {
        if (this.obs == null || this.obs.data == null || this.obs.data.length == 0) {
            log.error("No observation data to write");
            return false;
        }

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
            writeHeader(writer);
            writeObservationsV3(writer);
            log.info("RINEX observation file written: {}", filePath);
            return true;
        } catch (IOException e) {
            log.error("Error writing RINEX observation file: {}", filePath, e);
            return false;
        }
    }

    private void writeHeader(BufferedWriter writer) throws IOException {
        String sysChar = getSysChar(navsys);
        writer.write(String.format("%9.2f%-11s%-20s%-20s%-20s\n",
                version, "", "OBSERVATION DATA", sysChar, "RINEX VERSION / TYPE"));

        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd HHmmss");
        String dateStr = sdf.format(new Date());
        writer.write(String.format("%-20s%-20s%-20s%-20s\n",
                "rtklib_java", "USER", dateStr, "PGM / RUN BY / DATE"));

        String markerName = sta.name != null && !sta.name.isEmpty() ? sta.name : "UNKNOWN";
        writer.write(String.format("%-60s%-20s\n", markerName, "MARKER NAME"));

        if (sta.marker != null && !sta.marker.isEmpty()) {
            writer.write(String.format("%-20s%-60s%-20s\n", sta.marker, "", "MARKER NUMBER"));
        }

        writer.write(String.format("%-20s%-40s%-20s\n", "USER", "AGENCY", "OBSERVER / AGENCY"));

        String recType = sta.rectype != null && !sta.rectype.isEmpty() ? sta.rectype : "UNKNOWN";
        writer.write(String.format("%-20s%-20s%-20s%-20s\n", "", recType, "", "REC # / TYPE / VERS"));

        String antType = sta.antdes != null && !sta.antdes.isEmpty() ? sta.antdes : "UNKNOWN";
        writer.write(String.format("%-20s%-20s%-40s%-20s\n", "", antType, "", "ANT # / TYPE"));

        if (sta.pos != null && sta.pos.length == 3) {
            writer.write(String.format("%14.4f%14.4f%14.4f%-18s%-20s\n",
                    sta.pos[0], sta.pos[1], sta.pos[2], "", "APPROX POSITION XYZ"));
        }

        if (sta.del != null && sta.del.length >= 3) {
            writer.write(String.format("%14.4f%14.4f%14.4f%-18s%-20s\n",
                    sta.del[2], sta.del[0], sta.del[1], "", "ANTENNA: DELTA H/E/N"));
        }

        writeObsTypesV3(writer);

        if (obs.data.length > 0) {
            writeFirstObsTime(writer, obs.data[0].time);
        }

        writer.write(String.format("%-60s%-20s\n", "", "END OF HEADER"));
    }

    private void writeObsTypesV3(BufferedWriter writer) throws IOException {
        collectObsCodes();

        java.util.Map<String, java.util.List<String>> sysObsMap = new java.util.LinkedHashMap<>();
        for (String code : obsCodes) {
            String sysChar = code.substring(0, 1);
            sysObsMap.computeIfAbsent(sysChar, k -> new java.util.ArrayList<>()).add(code);
        }

        for (String sysChar : sysObsMap.keySet()) {
            java.util.List<String> sysObs = sysObsMap.get(sysChar);
            int n = sysObs.size();
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("%s  %3d", sysChar, n));
            for (int i = 0; i < n; i++) {
                sb.append(String.format(" %3s", sysObs.get(i).substring(1)));
                if ((i + 1) % 13 == 0 && i < n - 1) {
                    writer.write(String.format("%-60s%-20s\n", sb.toString(), "SYS / # / OBS TYPES"));
                    sb = new StringBuilder(String.format("      "));
                }
            }
            writer.write(String.format("%-60s%-20s\n", sb.toString(), "SYS / # / OBS TYPES"));
        }
    }

    private void writeFirstObsTime(BufferedWriter writer, GTime time) throws IOException {
        double[] ymdhms = TimeSystem.time2ymdhms(time);
        writer.write(String.format("  %04d    %02d    %02d    %02d    %02d   %010.7f     %s%-20s\n",
                (int) ymdhms[0], (int) ymdhms[1], (int) ymdhms[2],
                (int) ymdhms[3], (int) ymdhms[4], ymdhms[5],
                "GPS", "                  TIME OF FIRST OBS"));
    }

    private void writeObservationsV3(BufferedWriter writer) throws IOException {
        int i = 0;
        int totalObs = (obs.n > 0) ? obs.n : obs.data.length;
        while (i < totalObs) {
            GTime epochTime = obs.data[i].time;
            java.util.List<Integer> epochIdx = new java.util.ArrayList<>();

            while (i < totalObs && TimeSystem.timediff(obs.data[i].time, epochTime) < 0.001) {
                epochIdx.add(i);
                i++;
            }

            int ns = epochIdx.size();
            double[] ymdhms = TimeSystem.time2ymdhms(epochTime);

            writer.write(String.format("> %04d %02d %02d %02d %02d %10.7f  %d%3d\n",
                    (int) ymdhms[0], (int) ymdhms[1], (int) ymdhms[2],
                    (int) ymdhms[3], (int) ymdhms[4], ymdhms[5],
                    0, ns));

            for (int j = 0; j < ns; j++) {
                Obsd o = obs.data[epochIdx.get(j)];
                String sysChar = ObsCode.satToSysChar(o.sat);
                int prn = ObsCode.satToPrn(o.sat);

                if (sysChar.equals("S")) {
                    prn += 100;
                }

                StringBuilder line = new StringBuilder();
                line.append(String.format("%s%02d", sysChar, prn));

                int sys = ObsCode.charToSys(sysChar);
                java.util.List<String> sysObsTypes = new java.util.ArrayList<>();
                for (String code : obsCodes) {
                    if (code.startsWith(sysChar)) {
                        sysObsTypes.add(code);
                    }
                }

                for (String code : sysObsTypes) {
                    char typePrefix = code.charAt(1);
                    String obsCodeStr = code.substring(2);
                    int codeVal = ObsCode.obs2code(obsCodeStr);
                    int freqIdx = ObsCode.code2idx(sys, codeVal);
                    double value = 0.0;
                    int lli = 0;
                    int snr = 0;

                    if (freqIdx >= 0 && freqIdx < Constants.NFREQ + Constants.NEXOBS) {
                        if (typePrefix == 'C' || typePrefix == 'P') {
                            value = o.P[freqIdx];
                            lli = o.LLI[freqIdx];
                            snr = (int) o.SNR[freqIdx];
                        } else if (typePrefix == 'L') {
                            value = o.L[freqIdx];
                            lli = o.LLI[freqIdx];
                            snr = (int) o.SNR[freqIdx];
                        } else if (typePrefix == 'D') {
                            value = o.D[freqIdx];
                        } else if (typePrefix == 'S') {
                            value = o.SNR[freqIdx];
                        }
                    }

                    if (value != 0.0) {
                        line.append(String.format("%14.3f", value));
                    } else {
                        line.append("              ");
                    }
                    line.append(String.format("%1d%1d", lli, Math.min(snr, 9)));
                }
                line.append("\n");
                writer.write(line.toString());
            }
        }
    }

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
            for (int j = 0; j < Constants.NFREQ + Constants.NEXOBS; j++) {
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

        String[] typePrefixes = new String[]{"C", "L"};
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

    private String getSysChar(int navsys) {
        int count = 0;
        if ((navsys & Constants.SYS_GPS) != 0) count++;
        if ((navsys & Constants.SYS_GLO) != 0) count++;
        if ((navsys & Constants.SYS_GAL) != 0) count++;
        if ((navsys & Constants.SYS_CMP) != 0) count++;
        if ((navsys & Constants.SYS_QZS) != 0) count++;
        if ((navsys & Constants.SYS_IRN) != 0) count++;
        if ((navsys & Constants.SYS_SBS) != 0) count++;
        if (count > 1) return "M";
        if ((navsys & Constants.SYS_GPS) != 0) return "G";
        if ((navsys & Constants.SYS_GLO) != 0) return "R";
        if ((navsys & Constants.SYS_GAL) != 0) return "E";
        if ((navsys & Constants.SYS_CMP) != 0) return "C";
        if ((navsys & Constants.SYS_QZS) != 0) return "J";
        if ((navsys & Constants.SYS_IRN) != 0) return "I";
        if ((navsys & Constants.SYS_SBS) != 0) return "S";
        return "M";
    }
}
