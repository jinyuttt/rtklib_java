package org.rtklib.java.test;

import org.rtklib.java.data.Nav;
import org.rtklib.java.data.PepH;
import org.rtklib.java.data.Pclk;
import org.rtklib.java.ephemeris.Sp3Reader;
import org.rtklib.java.ephemeris.ClkReader;
import org.rtklib.java.time.TimeSystem;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.constants.Constants;

public class Sp3Diag {

    public static void main(String[] args) {
        String sp3File1 = "D:\\rtcm3\\product\\sp3\\WUM0MGXRAP_20261440000_01D_05M_ORB.SP3";
        String sp3File2 = "D:\\rtcm3\\product\\sp3\\WUM0MGXRAP_20261450000_01D_05M_ORB.SP3";
        String clkFile1 = "D:\\rtcm3\\product\\clk\\WUM0MGXRAP_20261440000_01D_30S_CLK.CLK";
        String clkFile2 = "D:\\rtcm3\\product\\clk\\WUM0MGXRAP_20261450000_01D_30S_CLK.CLK";

        Nav nav = new Nav();

        System.out.println("Loading SP3 files...");
        Sp3Reader.readsp3(sp3File1, nav, 0);
        System.out.println("After SP3 day 144: nav.ne = " + nav.ne);
        Sp3Reader.readsp3(sp3File2, nav, 0);
        System.out.println("After SP3 day 145: nav.ne = " + nav.ne);

        if (nav.ne > 0) {
            System.out.println("SP3 time range:");
            System.out.println("  First epoch: " + formatTime(nav.peph[0].time));
            System.out.println("  Last epoch:  " + formatTime(nav.peph[nav.ne - 1].time));

            // Check BDS satellites at epoch corresponding to observation time (2026-05-24 16:00:30)
            // Day 144 has 289 epochs (5-min interval), so 16:00:30 is at epoch ~192
            // RINEX has: C01,C02,C03,C06,C07,C09,C10,C12,C19,C20,C36,C37
            int[] bdsSats = {106, 107, 108, 111, 112, 114, 115, 117, 124, 125, 141, 142};
            int[] checkEpochs = {192};
            for (int epochIdx : checkEpochs) {
                if (epochIdx < nav.ne) {
                    PepH peph = nav.peph[epochIdx];
                    System.out.println("\nEpoch " + epochIdx + " time: " + formatTime(peph.time));
                    for (int sat : bdsSats) {
                        double[] pos = peph.pos[sat - 1];
                        double norm = Math.sqrt(pos[0]*pos[0] + pos[1]*pos[1] + pos[2]*pos[2]);
                        String prn = "C" + String.format("%02d", sat - 105);
                        String status = (norm < 1000) ? "MISSING" : "OK";
                        System.out.println("  " + prn + " (sat=" + sat + "): norm=" +
                                String.format("%.3f", norm/1000) + " km [" + status + "]");
                    }
                }
            }
        }

        System.out.println("\nLoading CLK files...");
        ClkReader.readclk(clkFile1, nav);
        System.out.println("After CLK day 144: nav.nc = " + nav.nc);
        ClkReader.readclk(clkFile2, nav);
        System.out.println("After CLK day 145: nav.nc = " + nav.nc);

        if (nav.nc > 0) {
            System.out.println("CLK time range:");
            System.out.println("  First epoch: " + formatTime(nav.pclk[0].time));
            System.out.println("  Last epoch:  " + formatTime(nav.pclk[nav.nc - 1].time));
        }

        // Test observation time from RINEX
        double[] ep = {2026, 5, 24, 16, 0, 30.0};
        org.rtklib.java.data.GTime obsTime = TimeSystem.epoch2time(ep);
        System.out.println("\nTest observation time: " + formatTime(obsTime));

        // Check if observation time is within SP3 range
        if (nav.ne > 0) {
            double dt1 = TimeSystem.timediff(obsTime, nav.peph[0].time);
            double dt2 = TimeSystem.timediff(obsTime, nav.peph[nav.ne - 1].time);
            System.out.println("Time diff to SP3 start: " + String.format("%.1f", dt1) + "s");
            System.out.println("Time diff to SP3 end:   " + String.format("%.1f", dt2) + "s");
            if (dt1 < -900) {
                System.out.println("WARNING: Observation is BEFORE SP3 range by " + String.format("%.0f", -dt1) + "s!");
            } else if (dt2 > 900) {
                System.out.println("WARNING: Observation is AFTER SP3 range by " + String.format("%.0f", dt2) + "s!");
            } else {
                System.out.println("Observation is WITHIN SP3 range (±900s)");
            }
        }
    }

    private static String formatTime(org.rtklib.java.data.GTime time) {
        double[] ep = TimeSystem.time2ymdhms(time);
        return String.format("%04d-%02d-%02d %02d:%02d:%06.3f",
                (int)ep[0], (int)ep[1], (int)ep[2], (int)ep[3], (int)ep[4], ep[5]);
    }
}
