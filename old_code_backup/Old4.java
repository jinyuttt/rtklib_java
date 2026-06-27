package org.rtklib.java.rinex;

public class Old4 {

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
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * RINEX navigation file writer.
 * Supports RINEX 2.11 and 3.05/3.06 formats.
 * Aligned with RTKLIB rnxout.c.
 */
public class RinexNavWriter {
    private static final Logger log = LoggerFactory.getLogger(RinexNavWriter.class);

    /** RINEX version */
    private double version;

    /** Output file path */
    private String filePath;

    /** Navigation data */
    private Nav nav;

    /** RINEX format version */
    private String formatVersion;

    /** GNSS system mask */
    private int navsys;

    /**
     * Constructor.
     * @param version RINEX version (2.11, 3.05, or 3.06)
     * @param filePath Output file path
     */
    public RinexNavWriter(double version, String filePath) {
        this.version = version;
        this.filePath = filePath;
        this.nav = null;
        this.formatVersion = String.format("%4.2f", version);
        this.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL | Constants.SYS_CMP;
    }

    /**
     * Set navigation data.
     * @param nav Navigation data
     */
    public void setNavData(Nav nav) {
        this.nav = nav;
    }

    /**
     * Write RINEX navigation file.
     * @return true on success, false on error
     */
    public boolean write() {
        if (this.nav == null) {
            log.error("No navigation data to write");
            return false;
        }

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
            // Write header
            writeHeader(writer);

            // Write navigation data
            writeNavigation(writer);

            log.info("RINEX navigation file written: {}", filePath);
            return true;
        } catch (IOException e) {
            log.error("Error writing RINEX navigation file: {}", filePath, e);
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
        writer.write(String.format("%9.2f           %-20s%-20s%-20s\n",
                version, "N: GNSS NAV DATA", "M: Mixed", "RINEX VERSION / TYPE"));

        // PGM / RUN BY / DATE
        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd HHmmss");
        String dateStr = sdf.format(new Date());
        writer.write(String.format("%-20s%-20s%-20s%-20s\n",
                "rtklib_java", "USER", dateStr, "PGM / RUN BY / DATE"));

        // END OF HEADER
        writer.write(String.format("%-60s%-20s\n", "", "END OF HEADER"));
    }

    /**
     * Write navigation data.
     * @param writer BufferedWriter
     * @throws IOException if write fails
     */
    private void writeNavigation(BufferedWriter writer) throws IOException {
        if (version >= 3.0) {
            // RINEX 3.x format - write ephemeris by system
            writeEphemerisV3(writer);
        } else {
            // RINEX 2.x format - write GPS ephemeris only
            writeEphemerisV2(writer);
        }
    }

    /**
     * Write ephemeris data (RINEX 3.x).
     * @param writer BufferedWriter
     * @throws IOException if write fails
     */
    private void writeEphemerisV3(BufferedWriter writer) throws IOException {
        // Write GPS ephemeris
        if ((navsys & Constants.SYS_GPS) != 0) {
            for (int i = 0; i < Constants.MAXSAT; i++) {
                if (nav.eph[i] != null && SatUtils.satsys(nav.eph[i].sat, null) == Constants.SYS_GPS) {
                    writeGpsEphV3(writer, nav.eph[i]);
                }
            }
        }

        // Write GLONASS ephemeris
        if ((navsys & Constants.SYS_GLO) != 0) {
            for (int i = 0; i < Constants.MAXSAT; i++) {
                if (nav.geph[i] != null && SatUtils.satsys(nav.geph[i].sat, null) == Constants.SYS_GLO) {
                    writeGloEphV3(writer, nav.geph[i]);
                }
            }
        }

        // Write Galileo ephemeris
        if ((navsys & Constants.SYS_GAL) != 0) {
            for (int i = 0; i < Constants.MAXSAT; i++) {
                if (nav.eph[i] != null && SatUtils.satsys(nav.eph[i].sat, null) == Constants.SYS_GAL) {
                    writeGalEphV3(writer, nav.eph[i]);
                }
            }
        }

        // Write BDS ephemeris
        if ((navsys & Constants.SYS_CMP) != 0) {
            for (int i = 0; i < Constants.MAXSAT; i++) {
                if (nav.eph[i] != null && SatUtils.satsys(nav.eph[i].sat, null) == Constants.SYS_CMP) {
                    writeBdsEphV3(writer, nav.eph[i]);
                }
            }
        }
    }

    /**
     * Write ephemeris data (RINEX 2.x).
     * @param writer BufferedWriter
     * @throws IOException if write fails
     */
    private void writeEphemerisV2(BufferedWriter writer) throws IOException {
        // Write GPS ephemeris only
        for (int i = 0; i < Constants.MAXSAT; i++) {
            if (nav.eph[i] != null && SatUtils.satsys(nav.eph[i].sat, null) == Constants.SYS_GPS) {
                writeGpsEphV2(writer, nav.eph[i]);
            }
        }
    }

    /**
     * Write GPS ephemeris (RINEX 3.x).
     * @param writer BufferedWriter
     * @param eph Ephemeris data
     * @throws IOException if write fails
     */
    private void writeGpsEphV3(BufferedWriter writer, Eph eph) throws IOException {
        int prn = ObsCode.satToPrn(eph.sat);
        double[] ymdhms = TimeSystem.time2ymdhms(eph.toe);
        int[] weekArr = new int[1];
        double ttr = TimeSystem.time2gpst(eph.ttr, weekArr);

        writer.write(String.format("G%02d %04d %02d %02d %02d %02d %02.0f%19.12E%19.12E%19.12E\n",
                prn, (int)ymdhms[0], (int)ymdhms[1], (int)ymdhms[2],
                (int)ymdhms[3], (int)ymdhms[4], ymdhms[5],
                eph.f0, eph.f1, eph.f2));

        writer.write(String.format("    %19.12E%19.12E%19.12E%19.12E\n",
                (double)eph.iode, eph.crs, eph.deln, eph.M0));

        writer.write(String.format("    %19.12E%19.12E%19.12E%19.12E\n",
                eph.cuc, eph.e, eph.cus, Math.sqrt(eph.A)));

        writer.write(String.format("    %19.12E%19.12E%19.12E%19.12E\n",
                eph.toes, eph.cic, eph.OMG0, eph.cis));

        writer.write(String.format("    %19.12E%19.12E%19.12E%19.12E\n",
                eph.i0, eph.crc, eph.omg, eph.OMGd));

        writer.write(String.format("    %19.12E%19.12E%19.12E%19.12E\n",
                eph.idot, (double)eph.code, (double)eph.week, (double)eph.flag));

        double svaVal = uravalue(eph.sva);
        writer.write(String.format("    %19.12E%19.12E%19.12E%19.12E\n",
                svaVal, (double)eph.svh, eph.tgd[0], (double)eph.iodc));

        writer.write(String.format("    %19.12E%19.12E%19.12E%19.12E\n",
                ttr + (weekArr[0] - eph.week) * 604800.0, eph.fit, 0.0, 0.0));
    }

    /**
     * Write GPS ephemeris (RINEX 2.x).
     * @param writer BufferedWriter
     * @param eph Ephemeris data
     * @throws IOException if write fails
     */
    private void writeGpsEphV2(BufferedWriter writer, Eph eph) throws IOException {
        int prn = ObsCode.satToPrn(eph.sat);
        double[] ymdhms = TimeSystem.time2ymdhms(eph.toe);
        int year = (int)ymdhms[0] % 100;

        // Line 1: PRN, epoch, SV clock
        writer.write(String.format("%2d %2d %2d %2d %2d %2d %4.1f%19.12E%19.12E%19.12E\n",
                prn, year, (int)ymdhms[1], (int)ymdhms[2],
                (int)ymdhms[3], (int)ymdhms[4], ymdhms[5],
                eph.f0, eph.f1, eph.f2));

        // Line 2: broadcast orbit 1
        writer.write(String.format("    %19.12E%19.12E%19.12E%19.12E\n",
                eph.A, eph.e, eph.M0, eph.omg));

        // Line 3: broadcast orbit 2
        writer.write(String.format("    %19.12E%19.12E%19.12E%19.12E\n",
                eph.i0, eph.OMG0, eph.OMGd, eph.idot));

        // Line 4: broadcast orbit 3
        writer.write(String.format("    %19.12E%19.12E%19.12E%19.12E\n",
                eph.cuc, eph.cus, eph.crc, eph.crs));

        // Line 5: broadcast orbit 4
        writer.write(String.format("    %19.12E%19.12E%19.12E%19.12E\n",
                eph.cic, eph.cis, eph.toes, (double)eph.iode));

        // Line 6: broadcast orbit 5
        writer.write(String.format("    %19.12E%19.12E%19.12E%19.12E\n",
                eph.deln, (double)eph.iodc, (double)eph.week, (double)eph.sva));

        // Line 7: broadcast orbit 6
        writer.write(String.format("    %19.12E%19.12E%19.12E%19.12E\n",
                eph.tgd[0], (double)eph.svh, 0.0, eph.fit));
    }

    /**
     * Write GLONASS ephemeris (RINEX 3.x).
     * @param writer BufferedWriter
     * @param geph GLONASS ephemeris data
     * @throws IOException if write fails
     */
    private void writeGloEphV3(BufferedWriter writer, Geph geph) throws IOException {
        int prn = ObsCode.satToPrn(geph.sat);
        double[] ymdhms = TimeSystem.time2ymdhms(geph.toe);

        // Line 1: PRN, epoch, SV clock
        writer.write(String.format("R%02d %04d %02d %02d %02d %02d %02.0f%19.12E%19.12E%19.12E\n",
                prn, (int)ymdhms[0], (int)ymdhms[1], (int)ymdhms[2],
                (int)ymdhms[3], (int)ymdhms[4], ymdhms[5],
                -geph.taun, geph.gamn, 0.0));

        // Line 2: position and velocity
        writer.write(String.format("    %19.12E%19.12E%19.12E%19.12E\n",
                geph.pos[0] / 1E3, geph.pos[1] / 1E3, geph.pos[2] / 1E3,
                geph.vel[0] / 1E3));

        // Line 3: velocity and acceleration
        writer.write(String.format("    %19.12E%19.12E%19.12E%19.12E\n",
                geph.vel[1] / 1E3, geph.vel[2] / 1E3,
                geph.acc[0] / 1E3, geph.acc[1] / 1E3));

        // Line 4: acceleration and health
        writer.write(String.format("    %19.12E%19.12E%19.12E%19.12E\n",
                geph.acc[2] / 1E3, (double)(geph.frq + 8), (double)geph.age, (double)geph.svh));
    }

    /**
     * Write Galileo ephemeris (RINEX 3.x).
     * @param writer BufferedWriter
     * @param eph Ephemeris data
     * @throws IOException if write fails
     */
    private void writeGalEphV3(BufferedWriter writer, Eph eph) throws IOException {
        int prn = ObsCode.satToPrn(eph.sat);
        double[] ymdhms = TimeSystem.time2ymdhms(eph.toe);
        int[] weekArr = new int[1];
        double ttr = TimeSystem.time2gpst(eph.ttr, weekArr);

        writer.write(String.format("E%02d %04d %02d %02d %02d %02d %02.0f%19.12E%19.12E%19.12E\n",
                prn, (int)ymdhms[0], (int)ymdhms[1], (int)ymdhms[2],
                (int)ymdhms[3], (int)ymdhms[4], ymdhms[5],
                eph.f0, eph.f1, eph.f2));

        writer.write(String.format("    %19.12E%19.12E%19.12E%19.12E\n",
                (double)eph.iode, eph.crs, eph.deln, eph.M0));

        writer.write(String.format("    %19.12E%19.12E%19.12E%19.12E\n",
                eph.cuc, eph.e, eph.cus, Math.sqrt(eph.A)));

        writer.write(String.format("    %19.12E%19.12E%19.12E%19.12E\n",
                eph.toes, eph.cic, eph.OMG0, eph.cis));

        writer.write(String.format("    %19.12E%19.12E%19.12E%19.12E\n",
                eph.i0, eph.crc, eph.omg, eph.OMGd));

        writer.write(String.format("    %19.12E%19.12E%19.12E%19.12E\n",
                eph.idot, (double)eph.code, (double)eph.week, 0.0));

        double sisaVal = sisaValue(eph.sva);
        writer.write(String.format("    %19.12E%19.12E%19.12E%19.12E\n",
                sisaVal, (double)eph.svh, eph.tgd[0], eph.tgd[1]));

        writer.write(String.format("    %19.12E%19.12E%19.12E%19.12E\n",
                ttr + (weekArr[0] - eph.week) * 604800.0, 0.0, 0.0, 0.0));
    }

    /**
     * Write BDS ephemeris (RINEX 3.x).
     * @param writer BufferedWriter
     * @param eph Ephemeris data
     * @throws IOException if write fails
     */
    private void writeBdsEphV3(BufferedWriter writer, Eph eph) throws IOException {
        int prn = ObsCode.satToPrn(eph.sat);
        double[] ymdhms = TimeSystem.time2ymdhms(TimeSystem.gpst2bdt(eph.toe));
        int[] weekArr = new int[1];
        double ttr = TimeSystem.time2bdt(eph.ttr, weekArr);

        writer.write(String.format("C%02d %04d %02d %02d %02d %02d %02.0f%19.12E%19.12E%19.12E\n",
                prn, (int)ymdhms[0], (int)ymdhms[1], (int)ymdhms[2],
                (int)ymdhms[3], (int)ymdhms[4], ymdhms[5],
                eph.f0, eph.f1, eph.f2));

        writer.write(String.format("    %19.12E%19.12E%19.12E%19.12E\n",
                (double)eph.iode, eph.crs, eph.deln, eph.M0));

        writer.write(String.format("    %19.12E%19.12E%19.12E%19.12E\n",
                eph.cuc, eph.e, eph.cus, Math.sqrt(eph.A)));

        writer.write(String.format("    %19.12E%19.12E%19.12E%19.12E\n",
                eph.toes, eph.cic, eph.OMG0, eph.cis));

        writer.write(String.format("    %19.12E%19.12E%19.12E%19.12E\n",
                eph.i0, eph.crc, eph.omg, eph.OMGd));

        writer.write(String.format("    %19.12E%19.12E%19.12E%19.12E\n",
                eph.idot, 0.0, (double)eph.week, 0.0));

        double svaVal = uravalue(eph.sva);
        writer.write(String.format("    %19.12E%19.12E%19.12E%19.12E\n",
                svaVal, (double)eph.svh, eph.tgd[0], eph.tgd[1]));

        writer.write(String.format("    %19.12E%19.12E%19.12E%19.12E\n",
                ttr + (weekArr[0] - eph.week) * 604800.0, (double)eph.iodc, 0.0, 0.0));
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

    private static final double[] URA_NOMINAL = {
            2.0, 2.8, 4.0, 5.7, 8.0, 11.3, 16.0, 32.0,
            64.0, 128.0, 256.0, 512.0, 1024.0, 2048.0, 4096.0, 8192.0
    };

    private static double uravalue(int sva) {
        return (sva >= 0 && sva < 15) ? URA_NOMINAL[sva] : 8192.0;
    }

    private static double sisaValue(int sisa) {
        if (sisa < 0) return -1.0;
        if (sisa <= 49) return sisa * 0.01;
        if (sisa <= 74) return 0.5 + (sisa - 50) * 0.02;
        if (sisa <= 99) return 1.0 + (sisa - 75) * 0.04;
        if (sisa <= 125) return 2.0 + (sisa - 100) * 0.16;
        return -1.0;
    }
}{
}
