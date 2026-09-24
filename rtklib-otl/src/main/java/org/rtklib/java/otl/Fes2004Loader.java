package org.rtklib.java.otl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class Fes2004Loader {

    private static final Logger LOG = LoggerFactory.getLogger(Fes2004Loader.class);
    private static final String RESOURCE = "/otl/FES2004S1.dat";
    private static final int HEADER_LINES = 3;

    private Fes2004Loader() {}

    public static List<Fes2004Record> load() throws IOException {
        List<Fes2004Record> records = new ArrayList<>();
        try (InputStream is = Fes2004Loader.class.getResourceAsStream(RESOURCE)) {
            if (is == null) throw new IOException("Resource not found: " + RESOURCE);
            BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            for (int i = 0; i < HEADER_LINES; i++) br.readLine();

            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                String[] t = line.split("\\s+");
                if (t.length < 12) continue;
                try {
                    double doodson = Double.parseDouble(t[0]);
                    String darwin = t[1];
                    int n = Integer.parseInt(t[2]);
                    int m = Integer.parseInt(t[3]);
                    double cPlus = Double.parseDouble(t[8]);
                    double epsPlus = Double.parseDouble(t[9]);
                    double cMinus = Double.parseDouble(t[10]);
                    double epsMinus = Double.parseDouble(t[11]);
                    if (Fes2004Record.isBlqDoodson(doodson)) {
                        records.add(new Fes2004Record(doodson, darwin, n, m,
                                cPlus, epsPlus, cMinus, epsMinus));
                    }
                } catch (NumberFormatException e) {
                    // skip malformed
                }
            }
        }
        LOG.info("Loaded {} FES2004 records for 11 BLQ constituents", records.size());
        return records;
    }
}
