package org.rtklib.java.otl;

import java.io.IOException;

public class GenerateBlqAllStations {
    public static void main(String[] args) throws IOException {
        BlqExtractor extractor = new BlqExtractor();

        String[] names = {"LHASA", "LINZHI", "CHENGDU", "MENGDIGOU"};
        double[] lats = {29.65, 29.65, 30.57, 29.50};
        double[] lons = {91.13, 94.33, 104.07, 101.50};

        for (int i = 0; i < names.length; i++) {
            double[][][] blq = extractor.extract(lats[i], lons[i]);
            String filename = "validate/" + names[i].toLowerCase() + "_java.blq";
            BlqWriter.write(filename, names[i], lats[i], lons[i], blq);
            System.out.println("Generated: " + filename);
        }
    }
}
