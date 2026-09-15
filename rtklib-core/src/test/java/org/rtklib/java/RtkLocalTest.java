package org.rtklib.java;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.rtkpos.RtkProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RTK Local Data Test")
public class RtkLocalTest {

    private static final Logger log = LoggerFactory.getLogger(RtkLocalTest.class);

    private static final String BASE_DIR = System.getProperty("user.dir");
    private static final String ROVER_PATH = BASE_DIR + "\\testdat\\rover.rtcm";
    private static final String BASE_PATH = BASE_DIR + "\\testdat\\base.rtcm";

    private static byte[] roverData;
    private static byte[] baseData;
    private static boolean dataAvailable;

    static boolean dataAvailable() {
        return dataAvailable;
    }

    @BeforeAll
    static void loadData() {
        dataAvailable = new File(ROVER_PATH).exists() && new File(BASE_PATH).exists();
        if (!dataAvailable) {
            log.warn("Test data not found: rover={}, base={}", ROVER_PATH, BASE_PATH);
            return;
        }
        try (FileInputStream fis = new FileInputStream(ROVER_PATH)) {
            roverData = fis.readAllBytes();
        } catch (IOException e) {
            dataAvailable = false;
            log.warn("Failed to read rover data: {}", e.getMessage());
            return;
        }
        try (FileInputStream fis = new FileInputStream(BASE_PATH)) {
            baseData = fis.readAllBytes();
        } catch (IOException e) {
            dataAvailable = false;
            log.warn("Failed to read base data: {}", e.getMessage());
        }
        if (dataAvailable) {
            log.info("Loaded rover={} bytes, base={} bytes", roverData.length, baseData.length);
        }
    }

    @Test
    @DisplayName("1. RTK with local RTCM data (GPS+BDS dual-freq)")
    void testRtkWithLocalData() {
        org.junit.jupiter.api.Assumptions.assumeTrue(dataAvailable, "Local RTCM data not available");

        PrcOpt opt = RtkProcessor.createDefaultOpt();
        opt.navsys = Constants.SYS_GPS | Constants.SYS_CMP;
        opt.nf = 2;
        opt.elmin = 15.0 * Constants.D2R;
        opt.mode = Constants.PMODE_KINEMA;

        RtkProcessor rtk = new RtkProcessor(opt);
        RtkProcessor.RtkResult result = rtk.process(roverData, baseData);

        log.info("RTK result: total={}, success={}, fail={}", result.totalEpochs, result.successCount, result.failCount);
        assertTrue(result.totalEpochs > 0, "Should process epochs");
    }

    @Test
    @DisplayName("2. RTK with tide correction enabled (solid+ocean+pole)")
    void testRtkWithTideCorr() {
        org.junit.jupiter.api.Assumptions.assumeTrue(dataAvailable, "Local RTCM data not available");

        PrcOpt opt = RtkProcessor.createDefaultOpt();
        opt.navsys = Constants.SYS_GPS | Constants.SYS_CMP;
        opt.nf = 2;
        opt.elmin = 15.0 * Constants.D2R;
        opt.mode = Constants.PMODE_KINEMA;
        opt.tidecorr = 7;

        RtkProcessor rtk = new RtkProcessor(opt);
        RtkProcessor.RtkResult result = rtk.process(roverData, baseData);

        log.info("RTK with tidecorr=7: total={}, success={}, fail={}", result.totalEpochs, result.successCount, result.failCount);
        assertTrue(result.totalEpochs > 0, "Should process epochs");
    }
}