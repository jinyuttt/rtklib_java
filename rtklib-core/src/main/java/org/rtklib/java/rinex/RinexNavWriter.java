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
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * RINEX navigation file writer.
 * Supports RINEX 3.05/3.06 format only.
 * Aligned with RTKLIB rnxout.c.
 */
public class RinexNavWriter {
    private static final Logger log = LoggerFactory.getLogger(RinexNavWriter.class);

    private double version;

    private String filePath;

    private Nav nav;

    private String formatVersion;

    private int navsys;

    /**
     * Constructor.
     * @param version RINEX version (3.05 or 3.06)
     * @param filePath Output file path
     */
    public RinexNavWriter(double version, String filePath) {
        if (version < 3.0) {
            throw new IllegalArgumentException("RINEX 2.x is not supported. Use version 3.05 or 3.06.");
        }
        this.version = version;
        this.filePath = filePath;
        this.nav = null;
        this.formatVersion = String.format("%4.2f", version);
        this.navsys = Constants.SYS_GPS | Constants.SYS_GLO | Constants.SYS_GAL | Constants.SYS_CMP;
    }

    public void setNavData(Nav nav) {
        this.nav = nav;
    }

    public boolean write() {
        if (this.nav == null) {
            log.error("No navigation data to write");
            return false;
        }

        try (BufferedWriter writer = new BufferedWriter(new FileWriter(filePath))) {
            writeHeader(writer);
            writeEphemerisV3(writer);
            log.info("RINEX navigation file written: {}", filePath);
            return true;
        } catch (IOException e) {
            log.error("Error writing RINEX navigation file: {}", filePath, e);
            return false;
        }
    }

    private void writeHeader(BufferedWriter writer) throws IOException {
        String sysChar = getSysChar(navsys);
        writer.write(String.format("%9.2f           %-20s%-20s%-20s\n",
                version, "N: GNSS NAV DATA", "M: Mixed", "RINEX VERSION / TYPE"));

        SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMdd HHmmss");
        String dateStr = sdf.format(new Date());
        writer.write(String.format("%-20s%-20s%-20s%-20s\n",
                "rtklib_java", "USER", dateStr, "PGM / RUN BY / DATE"));

        writer.write(String.format("%-60s%-20s\n", "", "END OF HEADER"));
    }

    private void writeEphemerisV3(BufferedWriter writer) throws IOException {
        if ((navsys & Constants.SYS_GPS) != 0) {
            for (int i = 0; i < Constants.MAXSAT; i++) {
                if (nav.eph[i] != null && SatUtils.satsys(nav.eph[i].sat, null) == Constants.SYS_GPS) {
                    writeGpsEphV3(writer, nav.eph[i]);
                }
            }
        }

        if ((navsys & Constants.SYS_GLO) != 0) {
            for (int i = 0; i < Constants.MAXSAT; i++) {
                if (nav.geph[i] != null && SatUtils.satsys(nav.geph[i].sat, null) == Constants.SYS_GLO) {
                    writeGloEphV3(writer, nav.geph[i]);
                }
            }
        }

        if ((navsys & Constants.SYS_GAL) != 0) {
            for (int i = 0; i < Constants.MAXSAT; i++) {
                if (nav.eph[i] != null && SatUtils.satsys(nav.eph[i].sat, null) == Constants.SYS_GAL) {
                    writeGalEphV3(writer, nav.eph[i]);
                }
            }
        }

        if ((navsys & Constants.SYS_CMP) != 0) {
            for (int i = 0; i < Constants.MAXSAT; i++) {
                if (nav.eph[i] != null && SatUtils.satsys(nav.eph[i].sat, null) == Constants.SYS_CMP) {
                    writeBdsEphV3(writer, nav.eph[i]);
                }
            }
        }
    }

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
                eph.idot, (double)eph.code, (double)eph.week, 0.0));

        writer.write(String.format("    %19.12E%19.12E%19.12E%19.12E\n",
                uravalue(eph.sva), (double)eph.svh, eph.tgd[0], eph.tgd[1]));

        writer.write(String.format("    %19.12E%19.12E%19.12E%19.12E\n",
                ttr + (weekArr[0] - eph.week) * 604800.0, (double)eph.iodc, eph.fit, 0.0));
    }

    private void writeGloEphV3(BufferedWriter writer, Geph geph) throws IOException {
        int prn = ObsCode.satToPrn(geph.sat);
        double[] ymdhms = TimeSystem.time2ymdhms(geph.toe);

        writer.write(String.format("R%02d %04d %02d %02d %02d %02d %02.0f%19.12E%19.12E%19.12E\n",
                prn, (int)ymdhms[0], (int)ymdhms[1], (int)ymdhms[2],
                (int)ymdhms[3], (int)ymdhms[4], ymdhms[5],
                -geph.taun, geph.gamn, 0.0));

        writer.write(String.format("    %19.12E%19.12E%19.12E%19.12E\n",
                geph.pos[0] / 1E3, geph.pos[1] / 1E3, geph.pos[2] / 1E3,
                geph.vel[0] / 1E3));

        writer.write(String.format("    %19.12E%19.12E%19.12E%19.12E\n",
                geph.vel[1] / 1E3, geph.vel[2] / 1E3,
                geph.acc[0] / 1E3, geph.acc[1] / 1E3));

        writer.write(String.format("    %19.12E%19.12E%19.12E%19.12E\n",
                geph.acc[2] / 1E3, (double)(geph.frq + 8), (double)geph.age, (double)geph.svh));
    }

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
}