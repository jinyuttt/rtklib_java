package org.rtklib.java.pppar;

import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.Nav;
import org.rtklib.java.data.Rtk;
import org.rtklib.java.ppp.PppAmbFix;
import org.rtklib.java.ppp.PppAmbFixBds3;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PppArEngine {
    private PppArEngine() {}

    private static final Logger LOG = LoggerFactory.getLogger(PppArEngine.class);

    public static void applyAmbiguityResolution(Rtk rtk, Nav nav, PppArConfig arCfg) {
        if (arCfg.isEnableMultiFreqAR()) {
            LOG.info("PppArEngine: calling pppAmbFixEwlWlNl, epoch={}", rtk.epoch);
            int nb = PppAmbFix.pppAmbFixEwlWlNl(rtk, null, rtk.xa, 1, 0, 0, nav);
            if (nb > 1) {
                rtk.sol.stat = Constants.SOLQ_FIX;
                LOG.debug("PppArEngine: PPP-AR 3F fixed {} ambiguities", nb);
            }
        }

        if (arCfg.isEnablePppAR() && rtk.sol.stat != Constants.SOLQ_FIX) {
            LOG.info("PppArEngine: calling pppAmbFixWlNl, epoch={}", rtk.epoch);
            int nb = PppAmbFix.pppAmbFixWlNl(rtk, null, rtk.xa, 1, 0, 0, nav);
            if (nb > 1) {
                rtk.sol.stat = Constants.SOLQ_FIX;
                LOG.debug("PppArEngine: PPP-AR fixed {} ambiguities", nb);
            }
        }

        if (arCfg.isEnablePppPartialAR() && rtk.sol.stat != Constants.SOLQ_FIX) {
            int nb = PppAmbFix.pppPartialAR(rtk, null, rtk.xa, 1, 0, 0, nav);
            if (nb > 1) {
                rtk.sol.stat = Constants.SOLQ_FIX;
                LOG.debug("PppArEngine: PPP Partial AR fixed {} ambiguities", nb);
            }
        }

        if (arCfg.isEnableBds3PppAR()) {
            int nb = PppAmbFixBds3.pppAmbFixBds3(rtk, null, rtk.xa, nav);
            if (nb > 1) {
                rtk.sol.stat = Constants.SOLQ_FIX;
                LOG.debug("PppArEngine: BDS-3 PPP-AR fixed {} ambiguities", nb);
            }
        }

        if (rtk.sol.stat == Constants.SOLQ_FIX) {
            rtk.nfix++;
        } else {
            rtk.nfix = 0;
        }

        if (arCfg.isEnablePppArFixHold() && rtk.sol.stat == Constants.SOLQ_FIX) {
            PppAmbFix.pppArFixHold(rtk, nav);
        }
    }
}