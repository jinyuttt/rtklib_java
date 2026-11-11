package org.rtklib.java.pppar;

import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.Nav;
import org.rtklib.java.data.Rtk;
import org.rtklib.java.ppp.PppAmbFix;
import org.rtklib.java.ppp.PppAmbFixBds3;
import org.rtklib.java.ppp.PppCore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PppArEngine {
    private PppArEngine() {}

    private static final Logger LOG = LoggerFactory.getLogger(PppArEngine.class);

    public static void applyAmbiguityResolution(Rtk rtk, Nav nav, PppArConfig arCfg) {
        /* [FIX-ALG-057] 固定解同样受 MIN_NSAT_SOL 约束（对齐 PPP-RTK FIX-054）：
         * 卫星数不足时不允许 AR 把标签抬到 SOLQ_FIX，否则会出现以 3 颗星
         * 支撑的“固定解”。PppCoreEx 扩展状态分支在 updateStat 之后无条件调用
         * 本方法，因此门控必须放在 AR 入口而不是只依赖 updateStat。 */
        if (rtk.sol.ns < PppCore.MIN_NSAT_SOL) {
            if (rtk.sol.stat == Constants.SOLQ_FIX) {
                rtk.sol.stat = Constants.SOLQ_NONE;
            }
            rtk.nfix = 0;
            return;
        }

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