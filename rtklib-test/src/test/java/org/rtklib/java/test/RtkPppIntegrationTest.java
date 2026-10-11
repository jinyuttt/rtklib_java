package org.rtklib.java.test;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;
import org.rtklib.java.ppp.PppProcessor;
import org.rtklib.java.rtkpos.RtkProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RTK and PPP Integration Test with RTCM data")
public class RtkPppIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(RtkPppIntegrationTest.class);

    private String getBaseFile() {
        return null;
    }

    private String getRoverFile() {
        return null;
    }

    @Test
    @DisplayName("RTK with RTCM data - base/rover pair")
    void testRtkWithRtcmData() throws Exception {
        String baseFile = getBaseFile();
        String roverFile = getRoverFile();
        if (baseFile == null || roverFile == null) {
            log.info("Skipping - no data configured, implement getBaseFile()/getRoverFile()");
            return;
        }

        PrcOpt opt = RtkProcessor.createDefaultOpt();
        opt.mode = Constants.PMODE_KINEMA;
        opt.nf = 2;
        opt.elmin = 15.0 * Constants.D2R;
        opt.tropopt = Constants.TROPOPT_EST;
        opt.ionoopt = Constants.IONOOPT_BRDC;
        opt.posopt[0] = 0;

        RtkConfig cfg = new RtkConfig();
        RtkProcessor rtk = new RtkProcessor(opt);
        rtk.setRtkConfig(cfg);

        log.info("RTK test: base={}, rover={}", baseFile, roverFile);

        RtkProcessor.RtkResult result = rtk.process(roverFile, baseFile);
        log.info("RTK result: {} solutions, {} success, {} fail",
                result.solutions != null ? result.solutions.size() : 0,
                result.successCount, result.failCount);
        if (result.solutions != null && !result.solutions.isEmpty()) {
            SolData lastSol = result.solutions.get(result.solutions.size() - 1);
            log.info("Last solution: time={}, status={}, numSat={}",
                    lastSol.timeStr, lastSol.status, lastSol.numSat);
            assertTrue(lastSol.status != SolutionStatus.NONE,
                    "Solution should have valid status");
        }
    }

    @Test
    @DisplayName("PPP with RTCM data - single rover")
    void testPppWithRtcmData() throws Exception {
        String roverFile = getRoverFile();
        if (roverFile == null) {
            log.info("Skipping - no data configured, implement getRoverFile()");
            return;
        }

        PrcOpt opt = PppProcessor.createDefaultOpt();
        opt.mode = Constants.PMODE_PPP_KINEMA;
        opt.nf = 2;
        opt.elmin = 10.0 * Constants.D2R;
        opt.tropopt = Constants.TROPOPT_EST;
        opt.ionoopt = Constants.IONOOPT_IFLC;
        opt.sateph = Constants.EPHOPT_BRDC;

        RtkConfig cfg = new RtkConfig();
        PppProcessor ppp = new PppProcessor(opt);
        ppp.setRtkConfig(cfg);

        log.info("PPP test: rover={}", roverFile);

        PppProcessor.PppResult result = ppp.process(roverFile);
        log.info("PPP result: {} solutions, {} success, {} fail",
                result.solutions != null ? result.solutions.size() : 0,
                result.successCount, result.failCount);
    }
}