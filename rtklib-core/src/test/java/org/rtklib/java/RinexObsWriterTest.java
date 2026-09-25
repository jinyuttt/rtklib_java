package org.rtklib.java;

import org.junit.jupiter.api.Test;
import org.rtklib.java.data.*;
import org.rtklib.java.rinex.RtcmFileToRinexConverter;
import org.rtklib.java.rinex.RinexParser;
import org.rtklib.java.rinex.RinexObsWriter;

import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

public class RinexObsWriterTest {

    private static final String BASE_RTCM =
            TestDataConfig.getBaseFile().isEmpty() ? TestDataConfig.getRtcmBaseDir() + "\\base.rtcm3" : TestDataConfig.getBaseFile();

    @Test
    void testObstypeAll() throws Exception {
        Path outDir = Files.createTempDirectory("rinex_all");
        String outPath = outDir.toString();

        RtcmFileToRinexConverter converter = new RtcmFileToRinexConverter(3.05, outPath, "BASE");
        boolean ok = converter.convert(BASE_RTCM);
        assertTrue(ok);

        String obsFile = converter.getObsFilePath();

        List<String> sysLines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(obsFile))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("SYS / # / OBS TYPES")) {
                    sysLines.add(line.trim());
                }
            }
        }

        System.out.println("=== Default (OBSTYPE_ALL) ===");
        for (String l : sysLines) System.out.println(l);

        String joined = String.join(" ", sysLines);
        assertTrue(joined.contains("C2I"), "should have C (pseudorange)");
        assertTrue(joined.contains("L2I"), "should have L (carrier phase)");
        assertTrue(joined.contains("D2I"), "should have D (doppler)");
        assertTrue(joined.contains("S2I"), "should have S (SNR)");

        Files.walk(outDir).sorted().forEach(p -> p.toFile().delete());
    }

    @Test
    void testObstypeCL() throws Exception {
        Path outDir1 = Files.createTempDirectory("rinex_cl_src");
        RtcmFileToRinexConverter converter = new RtcmFileToRinexConverter(3.05, outDir1.toString(), "BASE");
        converter.convert(BASE_RTCM);

        RinexParser parser = new RinexParser();
        assertTrue(parser.parseObs(converter.getObsFilePath()));

        Path outDir2 = Files.createTempDirectory("rinex_cl_out");
        String obsFile2 = outDir2.toString() + "\\BASE_CL.obs";

        RinexObsWriter writer = new RinexObsWriter(3.05, obsFile2, parser.sta);
        writer.setObstype(RinexObsWriter.OBSTYPE_PR | RinexObsWriter.OBSTYPE_CP);
        writer.setObsData(parser.obs);
        assertTrue(writer.write());

        List<String> sysLines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new FileReader(obsFile2))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.contains("SYS / # / OBS TYPES")) {
                    sysLines.add(line.trim());
                }
            }
        }

        System.out.println("=== CL only ===");
        for (String l : sysLines) System.out.println(l);

        String joined = String.join(" ", sysLines);
        assertTrue(joined.contains("C2I"), "should have C");
        assertTrue(joined.contains("L2I"), "should have L");
        assertFalse(joined.contains("D2I"), "should NOT have D");
        assertFalse(joined.contains("S2I"), "should NOT have S");

        Files.walk(outDir1).sorted().forEach(p -> p.toFile().delete());
        Files.walk(outDir2).sorted().forEach(p -> p.toFile().delete());
    }
}