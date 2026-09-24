package org.rtklib.java.rtkpos;

import org.junit.jupiter.api.Test;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.*;

import static org.junit.jupiter.api.Assertions.*;

class RtkOptimizationsResEditTest {

    @Test
    void testScreenArcIntegrityDefaultOff() {
        Rtk rtk = new Rtk();
        rtk.rtkConfig.enableResidualEdit = false;
        int origLock = rtk.ssat[0].lock[0];
        RtkOptimizationsResEdit.screenArcIntegrity(rtk);
        assertEquals(origLock, rtk.ssat[0].lock[0], "When disabled, should not modify any state");
    }

    @Test
    void testScreenArcIntegrityShortArcReset() {
        Rtk rtk = new Rtk();
        rtk.rtkConfig.enableResidualEdit = true;
        rtk.rtkConfig.residEditMinArcLen = 10;
        rtk.opt.minlock = 20;
        rtk.ssat[0].lock[0] = 5;
        rtk.ssat[0].vsat[0] = 1;
        rtk.ssat[0].outc[0] = 8;

        int nx = rtk.nx;
        if (nx > 0) {
            int na = rtk.na;
            if (na < nx) {
                rtk.x[na] = 1.0;
                rtk.P[na * nx + na] = 0.01;
            }
        }

        RtkOptimizationsResEdit.screenArcIntegrity(rtk);
        assertEquals(-rtk.opt.minlock, rtk.ssat[0].lock[0], "Short arc with high outc should be reset");
        assertEquals(0, rtk.ssat[0].outc[0], "outc should be reset to 0");
    }

    @Test
    void testScreenArcIntegrityLongArcPreserved() {
        Rtk rtk = new Rtk();
        rtk.rtkConfig.enableResidualEdit = true;
        rtk.rtkConfig.residEditMinArcLen = 10;
        rtk.ssat[0].lock[0] = 50;
        rtk.ssat[0].vsat[0] = 1;
        rtk.ssat[0].outc[0] = 2;

        int origLock = rtk.ssat[0].lock[0];
        RtkOptimizationsResEdit.screenArcIntegrity(rtk);
        assertEquals(origLock, rtk.ssat[0].lock[0], "Long arc should not be reset");
    }

    @Test
    void testCheckCycleSlipDefaultOff() {
        Rtk rtk = new Rtk();
        rtk.rtkConfig.enableResidualEdit = false;
        double[] v = {10.0};
        double[] R = {1.0};
        int[] vflg = {0};
        int origSlip = rtk.ssat[0].slip[0];
        RtkOptimizationsResEdit.checkCycleSlip(rtk, v, R, vflg, 1);
        assertEquals(origSlip, rtk.ssat[0].slip[0], "When disabled, should not modify slip flags");
    }

    @Test
    void testCheckPcConsistencyDefaultOff() {
        Rtk rtk = new Rtk();
        rtk.rtkConfig.enableResidualEdit = false;
        Obsd[] obs = new Obsd[1];
        obs[0] = new Obsd();
        int[] sat = {1};
        RtkOptimizationsResEdit.checkPcConsistency(rtk, obs, sat, 1, 1, null);
        assertEquals(0, rtk.diagResEditPcRejectCount, "When disabled, should not count rejections");
    }
}