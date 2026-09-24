package org.rtklib.java.otl;

import java.io.IOException;

public class ShowFullBlqResults {
    public static void main(String[] args) throws IOException {
        BlqExtractor extractor = new BlqExtractor();

        String[] names = {"LHASA", "LINZHI", "CHENGDU", "MENGDIGOU"};
        double[] lats = {29.65, 29.65, 30.57, 29.50};
        double[] lons = {91.13, 94.33, 104.07, 101.50};

        for (int i = 0; i < names.length; i++) {
            double[][][] blq = extractor.extract(lats[i], lons[i]);

            System.out.println("====== Station: " + names[i] + " ======");
            System.out.printf("Position: lat=%.4f, lon=%.4f%n", lats[i], lons[i]);
            System.out.println();

            // Header
            System.out.printf("%-16s", "");
            for (String cname : BlqConsts.CONSTITUENT_NAMES) {
                System.out.printf(" %10s", cname);
            }
            System.out.println();

            // Row 0: radial amplitude
            System.out.printf("%-16s", "Rad_Amp(mm)");
            for (int c = 0; c < BlqConsts.NUM_CONSTITUENTS; c++) {
                System.out.printf(" %10.4f", blq[0][c][0]);
            }
            System.out.println();

            // Row 1: west amplitude
            System.out.printf("%-16s", "West_Amp(mm)");
            for (int c = 0; c < BlqConsts.NUM_CONSTITUENTS; c++) {
                System.out.printf(" %10.4f", blq[1][c][0]);
            }
            System.out.println();

            // Row 2: south amplitude
            System.out.printf("%-16s", "South_Amp(mm)");
            for (int c = 0; c < BlqConsts.NUM_CONSTITUENTS; c++) {
                System.out.printf(" %10.4f", blq[2][c][0]);
            }
            System.out.println();

            // Row 3: radial phase
            System.out.printf("%-16s", "Rad_Phs(deg)");
            for (int c = 0; c < BlqConsts.NUM_CONSTITUENTS; c++) {
                System.out.printf(" %10.4f", blq[0][c][1]);
            }
            System.out.println();

            // Row 4: west phase
            System.out.printf("%-16s", "West_Phs(deg)");
            for (int c = 0; c < BlqConsts.NUM_CONSTITUENTS; c++) {
                System.out.printf(" %10.4f", blq[1][c][1]);
            }
            System.out.println();

            // Row 5: south phase
            System.out.printf("%-16s", "South_Phs(deg)");
            for (int c = 0; c < BlqConsts.NUM_CONSTITUENTS; c++) {
                System.out.printf(" %10.4f", blq[2][c][1]);
            }
            System.out.println();

            System.out.println();
        }
    }
}
