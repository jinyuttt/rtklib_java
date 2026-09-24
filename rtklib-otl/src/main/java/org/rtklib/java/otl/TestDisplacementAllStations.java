package org.rtklib.java.otl;

import java.io.IOException;

public class TestDisplacementAllStations {
    public static void main(String[] args) throws IOException {
        BlqExtractor extractor = new BlqExtractor();

        // 4 stations
        String[] names = {"LHASA", "LINZHI", "CHENGDU", "MENGDIGOU"};
        double[] lats = {29.65, 29.65, 30.57, 29.50};
        double[] lons = {91.13, 94.33, 104.07, 101.50};

        // Today: 2026-09-24, one hour (00:00 to 01:00 UTC), 10-second interval
        // MJD for 2026-09-24 00:00:00 UTC
        // JD = 2461307.5 for 2026-09-24 00:00:00
        // MJD = JD - 2400000.5 = 61307.0
        double startMjd = 61307.0;
        double endMjd = startMjd + 1.0 / 24.0; // 1 hour
        double intervalSec = 10.0;

        System.out.println("Testing extractDisplacement for 4 stations");
        System.out.println("Time: 2026-09-24 00:00-01:00 UTC, interval=10s");
        System.out.println("=========================================\n");

        for (int i = 0; i < names.length; i++) {
            System.out.println("Station: " + names[i]);
            System.out.println("Position: lat=" + lats[i] + ", lon=" + lons[i]);

            double[][] disp = extractor.extractDisplacement(lats[i], lons[i],
                    startMjd, endMjd, intervalSec);

            // disp[epoch][0] = time (MJD), disp[epoch][1] = east (m),
            // disp[epoch][2] = north (m), disp[epoch][3] = up/radial (m)
            int nEpochs = disp.length;
            System.out.println("Epochs: " + nEpochs);

            // Show first 5 and last 5 epochs
            System.out.println("\nFirst 5 epochs (time, east_mm, north_mm, up_mm):");
            for (int j = 0; j < Math.min(5, nEpochs); j++) {
                System.out.printf("  %.6f  %8.4f  %8.4f  %8.4f%n",
                        disp[j][0],
                        disp[j][1] * 1000.0,
                        disp[j][2] * 1000.0,
                        disp[j][3] * 1000.0);
            }

            if (nEpochs > 10) {
                System.out.println("  ...");
                System.out.println("Last 5 epochs:");
                for (int j = nEpochs - 5; j < nEpochs; j++) {
                    System.out.printf("  %.6f  %8.4f  %8.4f  %8.4f%n",
                            disp[j][0],
                            disp[j][1] * 1000.0,
                            disp[j][2] * 1000.0,
                            disp[j][3] * 1000.0);
                }
            }

            // Statistics
            double minE = Double.MAX_VALUE, maxE = -Double.MAX_VALUE;
            double minN = Double.MAX_VALUE, maxN = -Double.MAX_VALUE;
            double minU = Double.MAX_VALUE, maxU = -Double.MAX_VALUE;
            for (int j = 0; j < nEpochs; j++) {
                minE = Math.min(minE, disp[j][1] * 1000.0);
                maxE = Math.max(maxE, disp[j][1] * 1000.0);
                minN = Math.min(minN, disp[j][2] * 1000.0);
                maxN = Math.max(maxN, disp[j][2] * 1000.0);
                minU = Math.min(minU, disp[j][3] * 1000.0);
                maxU = Math.max(maxU, disp[j][3] * 1000.0);
            }
            System.out.printf("%nStatistics (mm):%n");
            System.out.printf("  East:   min=%.4f, max=%.4f, peak-to-peak=%.4f%n",
                    minE, maxE, maxE - minE);
            System.out.printf("  North:  min=%.4f, max=%.4f, peak-to-peak=%.4f%n",
                    minN, maxN, maxN - minN);
            System.out.printf("  Up:     min=%.4f, max=%.4f, peak-to-peak=%.4f%n",
                    minU, maxU, maxU - minU);
            System.out.println("\n-----------------------------------------\n");
        }
    }
}
