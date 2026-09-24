package org.rtklib.java.otl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class Main {

    private static final Logger LOG = LoggerFactory.getLogger(Main.class);

    public static void main(String[] args) {
        double lat = Double.NaN;
        double lon = Double.NaN;
        String output = "otl.blq";
        String station = "Station";

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-lat": lat = Double.parseDouble(args[++i]); break;
                case "-lon": lon = Double.parseDouble(args[++i]); break;
                case "-o": output = args[++i]; break;
                case "-sta": station = args[++i]; break;
                case "-help":
                case "--help":
                    printUsage();
                    return;
            }
        }

        if (Double.isNaN(lat) || Double.isNaN(lon)) {
            printUsage();
            System.exit(1);
        }

        try {
            BlqExtractor extractor = new BlqExtractor();
            double[][][] blq = extractor.extract(lat, lon);
            BlqWriter.write(output, station, lat, lon, blq);
            LOG.info("BLQ file written to {}", output);
        } catch (Exception e) {
            LOG.error("Failed to generate BLQ file: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    private static void printUsage() {
        System.out.println("Usage: rtklib-otl -lat <latitude> -lon <longitude> [-o <output.blq>] [-sta <station_name>]");
        System.out.println("  Generates standard BLQ ocean tide loading coefficient file from FES2004 model.");
        System.out.println("  -lat   Station latitude in degrees (positive north)");
        System.out.println("  -lon   Station longitude in degrees (positive east)");
        System.out.println("  -o     Output BLQ file path (default: otl.blq)");
        System.out.println("  -sta   Station name for BLQ header (default: Station)");
    }
}
