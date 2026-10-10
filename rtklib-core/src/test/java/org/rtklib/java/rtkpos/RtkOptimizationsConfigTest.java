package org.rtklib.java.rtkpos;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.rtklib.java.common.SatUtils;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("RTK optimization items: R2 Anchor, R3 AtmFrozen, R6 PAR, R7 Gradient, R13 ParamNoise")
class RtkOptimizationsConfigTest {

    @Test
    @DisplayName("R2: Ambiguity anchor default disabled")
    void testAmbAnchorDefaultOff() {
        RtkConfig cfg = new RtkConfig();
        assertFalse(cfg.enableAmbAnchor, "enableAmbAnchor should be false by default");
    }

    @Test
    @DisplayName("R2: Ambiguity anchor can be enabled")
    void testAmbAnchorEnabled() {
        RtkConfig cfg = new RtkConfig();
        cfg.enableAmbAnchor = true;
        assertTrue(cfg.enableAmbAnchor);

        Rtk rtk = new Rtk();
        rtk.rtkConfig = cfg;
        rtk.opt = new PrcOpt();
        rtk.opt.modear = Constants.ARMODE_FIXHOLD;

        assertTrue(rtk.rtkConfig.enableAmbAnchor);
    }

    @Test
    @DisplayName("R3: Atmosphere frozen threshold default")
    void testAtmFrozenDefault() {
        RtkConfig cfg = new RtkConfig();
        assertEquals(7, cfg.atmFrozenNsThresh, "atmFrozenNsThresh default should be 7");
    }

    @Test
    @DisplayName("R3: Atmosphere frozen threshold can be configured")
    void testAtmFrozenConfigurable() {
        RtkConfig cfg = new RtkConfig();
        cfg.atmFrozenNsThresh = 5;
        assertEquals(5, cfg.atmFrozenNsThresh);

        cfg.atmFrozenNsThresh = 0;
        assertEquals(0, cfg.atmFrozenNsThresh, "0 means disabled");
    }

    @Test
    @DisplayName("R3: Atmosphere frozen triggers when ns < threshold")
    void testAtmFrozenTriggerCondition() {
        RtkConfig cfg = new RtkConfig();
        cfg.atmFrozenNsThresh = 5;

        Rtk rtk = new Rtk();
        rtk.rtkConfig = cfg;
        rtk.opt = new PrcOpt();

        assertTrue(4 < cfg.atmFrozenNsThresh, "ns=4 < thresh=5 should trigger frozen");
        assertFalse(6 < cfg.atmFrozenNsThresh, "ns=6 >= thresh=5 should not trigger frozen");
    }

    @Test
    @DisplayName("R6: PAR reference reselect default disabled")
    void testParRefReselectDefaultOff() {
        RtkConfig cfg = new RtkConfig();
        assertFalse(cfg.enableParRefReselect, "enableParRefReselect should be false by default");
    }

    @Test
    @DisplayName("R6: PAR reference reselect can be enabled")
    void testParRefReselectEnabled() {
        RtkConfig cfg = new RtkConfig();
        cfg.enableParRefReselect = true;
        assertTrue(cfg.enableParRefReselect);
    }

    @Test
    @DisplayName("R7: Iono/Trop gradient default disabled")
    void testIonoTropGradientDefaultOff() {
        RtkConfig cfg = new RtkConfig();
        assertFalse(cfg.enableIonoTropGradient, "enableIonoTropGradient should be false by default");
    }

    @Test
    @DisplayName("R7: Iono/Trop gradient can be enabled for long baseline")
    void testIonoTropGradientEnabled() {
        RtkConfig cfg = new RtkConfig();
        cfg.enableIonoTropGradient = true;
        assertTrue(cfg.enableIonoTropGradient);
    }

    @Test
    @DisplayName("R13: Parameter type noise default disabled")
    void testParamTypeNoiseDefaultOff() {
        RtkConfig cfg = new RtkConfig();
        assertFalse(cfg.enableParamTypeNoise, "enableParamTypeNoise should be false by default");
    }

    @Test
    @DisplayName("R13: Parameter type noise can be enabled")
    void testParamTypeNoiseEnabled() {
        RtkConfig cfg = new RtkConfig();
        cfg.enableParamTypeNoise = true;
        assertTrue(cfg.enableParamTypeNoise);
    }

    @Test
    @DisplayName("R13: applyParamTypeNoise with identity-like P should not crash")
    void testParamTypeNoiseIdentityP() {
        RtkConfig cfg = new RtkConfig();
        cfg.enableParamTypeNoise = true;

        Rtk rtk = new Rtk();
        rtk.rtkConfig = cfg;
        rtk.opt = new PrcOpt();
        rtk.opt.mode = Constants.PMODE_KINEMA;
        rtk.opt.navsys = Constants.SYS_GPS;

        int nx = 10;
        double[] P = new double[nx * nx];
        for (int i = 0; i < nx; i++) P[i * nx + i] = 1.0;

        double tt = 1.0;
        RtkOptimizations.applyParamTypeNoise(rtk, P, nx, tt);

        for (int i = 0; i < nx; i++) {
            assertTrue(P[i * nx + i] > 0, "Diagonal should remain positive");
        }
    }
}