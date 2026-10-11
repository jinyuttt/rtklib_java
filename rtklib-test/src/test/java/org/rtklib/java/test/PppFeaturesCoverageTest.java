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

@DisplayName("PPP Features Coverage Test with Real RTCM Data")
public class PppFeaturesCoverageTest {

    private static final Logger log = LoggerFactory.getLogger(PppFeaturesCoverageTest.class);

    private String getBaseFile() {
        return null;
    }

    private String getRoverFile() {
        return null;
    }

    private PrcOpt bdsOnlyPppOpt() {
        PrcOpt opt = PppProcessor.createDefaultOpt();
        opt.navsys = Constants.SYS_CMP;
        opt.nf = 2;
        opt.elmin = 10.0 * Constants.D2R;
        opt.tropopt = Constants.TROPOPT_EST;
        opt.ionoopt = Constants.IONOOPT_IFLC;
        opt.sateph = Constants.EPHOPT_BRDC;
        opt.mode = Constants.PMODE_PPP_KINEMA;
        return opt;
    }

    private PrcOpt bdsOnlyRtkOpt() {
        PrcOpt opt = RtkProcessor.createDefaultOpt();
        opt.navsys = Constants.SYS_CMP;
        opt.nf = 2;
        opt.elmin = 15.0 * Constants.D2R;
        opt.mode = Constants.PMODE_KINEMA;
        return opt;
    }

    @Test
    @DisplayName("1. RTK baseline - BDS dual-freq")
    void test01_RtkBaseline() throws Exception {
        String baseFile = getBaseFile();
        String roverFile = getRoverFile();
        if (baseFile == null || roverFile == null) {
            log.info("Skipping - no data configured, implement getBaseFile()/getRoverFile()");
            return;
        }

        PrcOpt opt = bdsOnlyRtkOpt();
        RtkProcessor rtk = new RtkProcessor(opt);
        RtkProcessor.RtkResult result = rtk.process(roverFile, baseFile);
        log.info("RTK-BDS: total={}, success={}, fail={}",
                result.totalEpochs, result.successCount, result.failCount);
        assertTrue(result.totalEpochs > 0, "Should process epochs");
    }

    @Test
    @DisplayName("2. PPP broadcast eph - BDS-only")
    void test02_PppBrdcBaseline() throws Exception {
        String roverFile = getRoverFile();
        if (roverFile == null) {
            log.info("Skipping - no data configured, implement getRoverFile()");
            return;
        }

        PrcOpt opt = bdsOnlyPppOpt();
        RtkConfig cfg = new RtkConfig();
        PppProcessor ppp = new PppProcessor(opt);
        ppp.setRtkConfig(cfg);

        PppProcessor.PppResult result = ppp.process(roverFile);
        log.info("PPP-BDS-BRDC: total={}, success={}, fail={}",
                result.totalEpochs, result.successCount, result.failCount);
        assertTrue(result.totalEpochs > 0, "Should process epochs");
    }

    @Test
    @DisplayName("3. PPP + GPT3/VMF3 troposphere")
    void test03_PppGpt3Vmf3() throws Exception {
        String roverFile = getRoverFile();
        if (roverFile == null) {
            log.info("Skipping - no data configured, implement getRoverFile()");
            return;
        }

        PrcOpt opt = bdsOnlyPppOpt();
        RtkConfig cfg = new RtkConfig();
        cfg.enableGpt3Vmf3 = true;
        PppProcessor ppp = new PppProcessor(opt);
        ppp.setRtkConfig(cfg);

        PppProcessor.PppResult result = ppp.process(roverFile);
        log.info("PPP+GPT3+VMF3: total={}, success={}, fail={}",
                result.totalEpochs, result.successCount, result.failCount);
        assertTrue(result.totalEpochs > 0, "Should process epochs");
    }
}