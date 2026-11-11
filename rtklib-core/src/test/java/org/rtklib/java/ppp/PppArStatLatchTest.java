package org.rtklib.java.ppp;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.Nav;
import org.rtklib.java.data.Obsd;
import org.rtklib.java.data.PrcOpt;
import org.rtklib.java.data.Rtk;
import org.rtklib.java.data.Ssat;
import org.rtklib.java.pppar.PppArConfig;
import org.rtklib.java.pppar.PppArEngine;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * FIX-ALG-057 回归测试：PPP-AR 链路的状态锁存与 MIN_NSAT_SOL 降级。
 *
 * <p>对应 PPP-RTK 侧的 FIX-ALG-054：逐历元入口必须清除上一历元 AR 写入的
 * SOLQ_FIX 锁存，且固定解标签同样受 MIN_NSAT_SOL=4 约束。</p>
 */
public class PppArStatLatchTest {

    private Rtk rtk;
    private PrcOpt opt;
    private RtkConfig cfg;
    private int nx;

    @BeforeEach
    void setUp() {
        rtk = new Rtk();
        opt = new PrcOpt();
        cfg = new RtkConfig();

        opt.mode = Constants.PMODE_PPP_KINEMA;
        opt.nf = 1;
        opt.navsys = Constants.SYS_GPS | Constants.SYS_GAL | Constants.SYS_CMP;

        rtk.opt = opt;
        rtk.rtkConfig = cfg;
        rtk.sol.rr = new double[6];
        rtk.sol.qr = new float[6];
        rtk.sol.dtr = new double[4];

        nx = PppCore.pppnx(opt);
        rtk.nx = nx;
        rtk.x = new double[nx];
        rtk.P = new double[nx * nx];
        rtk.xa = new double[nx];
        rtk.Pa = new double[nx * nx];

        rtk.ssat = new Ssat[Constants.MAXSAT];
        for (int i = 0; i < Constants.MAXSAT; i++) {
            Ssat s = new Ssat();
            s.azel = new double[2];
            s.vsat = new int[Constants.NFREQ];
            s.fix = new int[Constants.NFREQ];
            s.code = new int[Constants.NFREQ][2];
            s.slip = new int[Constants.NFREQ];
            s.half = new int[Constants.NFREQ];
            s.lock = new int[Constants.NFREQ];
            s.outc = new long[Constants.NFREQ];
            rtk.ssat[i] = s;
        }
    }

    /** 构造前 n 颗有效卫星（vsat[0]=1）及其观测值。 */
    private Obsd[] markValidSats(int n) {
        Obsd[] obs = new Obsd[n];
        for (int i = 0; i < n; i++) {
            int sat = i + 1;
            rtk.ssat[sat - 1].vsat[0] = 1;
            rtk.ssat[sat - 1].vs = 1;
            rtk.ssat[sat - 1].azel[0] = 0.0;
            rtk.ssat[sat - 1].azel[1] = 0.5;
            obs[i] = new Obsd();
            obs[i].sat = sat;
            obs[i].rcv = 1;
        }
        return obs;
    }

    @Test
    @DisplayName("FIX-ALG-057: 入口清除上一历元的 SOLQ_FIX 锁存")
    void testClearFixLatch() {
        rtk.sol.stat = Constants.SOLQ_FIX;
        PppCore.clearFixLatch(rtk);
        assertEquals(Constants.SOLQ_NONE, rtk.sol.stat);
    }

    @Test
    @DisplayName("FIX-ALG-057: 非 FIX 状态不被入口清理破坏（保留 outsingle 的 SINGLE 语义）")
    void testClearFixLatchKeepsNonFixStat() {
        rtk.sol.stat = Constants.SOLQ_PPP;
        PppCore.clearFixLatch(rtk);
        assertEquals(Constants.SOLQ_PPP, rtk.sol.stat);

        rtk.sol.stat = Constants.SOLQ_SINGLE;
        PppCore.clearFixLatch(rtk);
        assertEquals(Constants.SOLQ_SINGLE, rtk.sol.stat);
    }

    @Test
    @DisplayName("FIX-ALG-057: ns<4 时 FIX 降级为 NONE")
    void testUpdateStatDowngradesFixBelowMinNsat() {
        Obsd[] obs = markValidSats(3);
        rtk.sol.stat = Constants.SOLQ_FIX;

        PppCore.updateStat(rtk, obs, obs.length, Constants.SOLQ_PPP, nx);

        assertEquals(3, rtk.sol.ns);
        assertEquals(Constants.SOLQ_NONE, rtk.sol.stat);
    }

    @Test
    @DisplayName("FIX-ALG-057: ns>=4 时 FIX 保持不变")
    void testUpdateStatKeepsFixAtSufficientNsat() {
        Obsd[] obs = markValidSats(6);
        rtk.sol.stat = Constants.SOLQ_FIX;

        PppCore.updateStat(rtk, obs, obs.length, Constants.SOLQ_PPP, nx);

        assertEquals(6, rtk.sol.ns);
        assertEquals(Constants.SOLQ_FIX, rtk.sol.stat);
    }

    @Test
    @DisplayName("FIX-ALG-057: 浮点解路径不受影响（ns 足够时仍标 PPP，不足时标 NONE）")
    void testUpdateStatFloatPathUnchanged() {
        Obsd[] obs6 = markValidSats(6);
        rtk.sol.stat = Constants.SOLQ_NONE;
        PppCore.updateStat(rtk, obs6, obs6.length, Constants.SOLQ_PPP, nx);
        assertEquals(Constants.SOLQ_PPP, rtk.sol.stat);
    }

    @Test
    @DisplayName("FIX-ALG-057: AR 入口在 ns<4 时拒绝抬升标签并清零 nfix")
    void testArEngineRefusesFixBelowMinNsat() {
        cfg.pppAr.enableAR = true;
        rtk.sol.ns = 3;
        rtk.sol.stat = Constants.SOLQ_FIX;
        rtk.nfix = 7;

        PppArEngine.applyAmbiguityResolution(rtk, new Nav(), PppArConfig.from(cfg));

        assertEquals(Constants.SOLQ_NONE, rtk.sol.stat);
        assertEquals(0, rtk.nfix);
    }
}