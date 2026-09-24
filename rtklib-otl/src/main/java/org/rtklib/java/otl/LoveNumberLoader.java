package org.rtklib.java.otl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public final class LoveNumberLoader {

    private static final Logger LOG = LoggerFactory.getLogger(LoveNumberLoader.class);
    private static final String RESOURCE = "/otl/Love_load_cm.dat";
    private static final int HEADER_LINES = 6;

    private LoveNumberLoader() {}

    public static LoveNumbers load(int maxDeg) throws IOException {
        double[] hn = new double[maxDeg + 1];
        double[] ln = new double[maxDeg + 1];
        double[] kn = new double[maxDeg + 1];

        try (InputStream is = LoveNumberLoader.class.getResourceAsStream(RESOURCE)) {
            if (is == null) throw new IOException("Resource not found: " + RESOURCE);
            BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            for (int i = 0; i < HEADER_LINES; i++) br.readLine();

            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] t = line.split("\\s+");
                if (t.length < 4) continue;
                try {
                    int degree = Integer.parseInt(t[0]);
                    if (degree > maxDeg) break;
                    hn[degree] = Double.parseDouble(t[1].replace('D', 'E').replace('d', 'e'));
                    ln[degree] = Double.parseDouble(t[2].replace('D', 'E').replace('d', 'e'));
                    kn[degree] = Double.parseDouble(t[3].replace('D', 'E').replace('d', 'e'));
                } catch (NumberFormatException e) {
                    // skip malformed
                }
            }
        }
        LOG.info("Loaded Love numbers up to degree {}", maxDeg);
        return new LoveNumbers(hn, ln, kn);
    }

    public static final class LoveNumbers {
        public final double[] h;
        public final double[] l;
        public final double[] k;

        LoveNumbers(double[] h, double[] l, double[] k) {
            this.h = h;
            this.l = l;
            this.k = k;
        }
    }
}
