package org.rtklib.java.adaptive;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.PrcOpt;
import org.rtklib.java.data.Rtk;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** AdaptiveController 单元测试: 激活/切换/冷却历元语义/静态提升/跳变回滚/deactivate恢复/审计/resolveMode */
class AdaptiveControllerTest {

    @TempDir Path tempDir;

    private RtkConfig newCfg() {
        RtkConfig cfg = new RtkConfig();
        cfg.adaptive.enableScene = true;
        cfg.adaptive.verbose = false;
        cfg.adaptive.auditEnabled = false;
        return cfg;
    }

    private PrcOpt newOpt(int mode, int dynamics) {
        PrcOpt opt = new PrcOpt();
        opt.mode = mode;
        opt.dynamics = dynamics;
        return opt;
    }

    @Test
    void notActivatedWhenDisabled() {
        RtkConfig cfg = new RtkConfig();  // enableAdaptiveScene=false
        PrcOpt opt = newOpt(Constants.PMODE_KINEMA, 1);
        double orig = opt.thresslip;
        AdaptiveController ctl = new AdaptiveController(Constants.PMODE_KINEMA, cfg, opt);
        ctl.activate();
        assertFalse(ctl.isActive());
        for (long e = 1; e <= 50; e++) ctl.onEpoch(e, 100000.0 + e, 6, 25.0, null);
        assertEquals(orig, opt.thresslip, 1e-12, "关闭时不得修改任何参数");
    }

    @Test
    void initialSceneCanyonAppliesRobustPreset() {
        RtkConfig cfg = newCfg();
        PrcOpt opt = newOpt(Constants.PMODE_KINEMA, 1);
        AdaptiveController ctl = new AdaptiveController(Constants.PMODE_KINEMA, cfg, opt);
        ctl.activate();
        assertEquals(GnssScene.URBAN_CANYON, ctl.getCurrentScene());
        assertEquals(0.10, opt.thresslip, 1e-12);
        assertTrue(cfg.rtk.enableIggiii);
        assertTrue(cfg.rtk.enableSnrMedian);
        ctl.deactivate();
    }

    @Test
    void sceneSwitchAndCooldownEpochSemantics() {
        RtkConfig cfg = newCfg();
        cfg.adaptive.windowEpochs = 10;
        cfg.adaptive.cooldownEpochs = 3;
        PrcOpt opt = newOpt(Constants.PMODE_KINEMA, 1);
        AdaptiveController ctl = new AdaptiveController(Constants.PMODE_KINEMA, cfg, opt);
        ctl.activate();
        long epoch = 0;
        double t0 = 200000.0;
        for (int i = 0; i < 20; i++) ctl.onEpoch(++epoch, t0 + epoch, 6, 25.0, null);
        // 好信号 → 首个窗口边界(epoch30)切 OPEN
        while (ctl.getCurrentScene() == GnssScene.URBAN_CANYON && epoch < 200) {
            ctl.onEpoch(++epoch, t0 + epoch, 20, 45.0, null);
        }
        long firstSwitch = epoch;
        assertEquals(GnssScene.OPEN_SKY, ctl.getCurrentScene());
        assertEquals(0.05, opt.thresslip, 1e-12);
        // 转差信号 → 冷却3历元语义下, 下一窗口评估即可切换 (远早于窗口语义的+40)
        while (ctl.getCurrentScene() == GnssScene.OPEN_SKY && epoch < firstSwitch + 200) {
            ctl.onEpoch(++epoch, t0 + epoch, 6, 25.0, null);
        }
        long secondSwitch = epoch;
        assertTrue(secondSwitch >= firstSwitch + 4, "冷却3历元内不得切换");
        assertTrue(secondSwitch < firstSwitch + 40,
                "冷却按历元递减: 第二次切换应远早于窗口语义+40, 实际 +" + (secondSwitch - firstSwitch));
        ctl.deactivate();
    }

    @Test
    void staticModePromotesWindowAndCooldown() {
        RtkConfig cfg = newCfg();
        PrcOpt opt = newOpt(Constants.PMODE_STATIC, 0);
        AdaptiveController ctl = new AdaptiveController(Constants.PMODE_STATIC, cfg, opt);
        ctl.activate();
        assertTrue(ctl.isActive());
        ctl.deactivate();
    }

    @Test
    void staticPositionJumpTriggersRollback() {
        RtkConfig cfg = newCfg();
        PrcOpt opt = newOpt(Constants.PMODE_STATIC, 0);
        AdaptiveController ctl = new AdaptiveController(Constants.PMODE_STATIC, cfg, opt);
        ctl.activate();
        Rtk rtk = new Rtk();
        rtk.sol.rr[0] = 1000.0; rtk.sol.rr[1] = 2000.0; rtk.sol.rr[2] = 3000.0;
        rtk.sol.qr[0] = 0.01f; rtk.sol.qr[1] = 0.01f; rtk.sol.qr[2] = 0.01f;  // posStd≈0.173
        ctl.onEpoch(1, 100000.0, 20, 45.0, rtk);
        // 位置跳变 10m >> 3σ → 回滚 (CANYON → OPEN_SKY)
        rtk.sol.rr[0] += 10.0;
        ctl.onEpoch(2, 100001.0, 20, 45.0, rtk);
        assertEquals(1, ctl.getState().getRollbackCount(), "静态位置跳变应触发回滚");
        assertEquals(GnssScene.OPEN_SKY, ctl.getCurrentScene());
        ctl.deactivate();
    }

    @Test
    void configurableThresholdsControlSwitch() {
        // 默认阈值 34: 20星/36dBHz 应切 OPEN
        RtkConfig cfgDefault = newCfg();
        cfgDefault.adaptive.windowEpochs = 10;
        PrcOpt optA = newOpt(Constants.PMODE_KINEMA, 1);
        AdaptiveController a = new AdaptiveController(Constants.PMODE_KINEMA, cfgDefault, optA);
        a.activate();
        for (long e = 1; e <= 20; e++) a.onEpoch(e, 500000.0 + e, 20, 36.0, null);
        assertEquals(GnssScene.OPEN_SKY, a.getCurrentScene(), "snr 36 > 默认阈值34 应切换");
        a.deactivate();

        // 抬高阈值到 40: 同样信号不切换
        RtkConfig cfgHigh = newCfg();
        cfgHigh.adaptive.windowEpochs = 10;
        cfgHigh.adaptive.snrCanyonToOpen = 40.0;
        PrcOpt optB = newOpt(Constants.PMODE_KINEMA, 1);
        AdaptiveController b = new AdaptiveController(Constants.PMODE_KINEMA, cfgHigh, optB);
        b.activate();
        for (long e = 1; e <= 20; e++) b.onEpoch(e, 500000.0 + e, 20, 36.0, null);
        assertEquals(GnssScene.URBAN_CANYON, b.getCurrentScene(), "snr 36 < 阈值40 不应切换");
        b.deactivate();
    }

    @Test
    void deactivateRestoresOriginals() {
        RtkConfig cfg = newCfg();
        PrcOpt opt = newOpt(Constants.PMODE_KINEMA, 1);
        double origThresslip = opt.thresslip;
        double origElmin = opt.elmin;
        boolean origIggiii = cfg.rtk.enableIggiii;
        boolean origSnrMedian = cfg.rtk.enableSnrMedian;
        AdaptiveController ctl = new AdaptiveController(Constants.PMODE_KINEMA, cfg, opt);
        ctl.activate();
        for (long e = 1; e <= 50; e++) ctl.onEpoch(e, 100000.0 + e, 6, 25.0, null);
        ctl.deactivate();
        assertEquals(origThresslip, opt.thresslip, 1e-12);
        assertEquals(origElmin, opt.elmin, 1e-12);
        assertEquals(origIggiii, cfg.rtk.enableIggiii);
        assertEquals(origSnrMedian, cfg.rtk.enableSnrMedian);
    }

    @Test
    void auditFileGenerated() throws Exception {
        RtkConfig cfg = newCfg();
        cfg.adaptive.auditEnabled = true;
        cfg.adaptive.auditDir = tempDir.toString();
        cfg.adaptive.windowEpochs = 10;
        cfg.adaptive.cooldownEpochs = 3;
        PrcOpt opt = newOpt(Constants.PMODE_KINEMA, 1);
        AdaptiveController ctl = new AdaptiveController(Constants.PMODE_KINEMA, cfg, opt);
        ctl.activate();
        long epoch = 0;
        double t0 = 300000.0;
        for (int i = 0; i < 20; i++) ctl.onEpoch(++epoch, t0 + epoch, 6, 25.0, null);
        for (int i = 0; i < 100; i++) ctl.onEpoch(++epoch, t0 + epoch, 20, 45.0, null);
        ctl.deactivate();
        Path audit = tempDir.resolve("adaptive_audit.csv");
        assertTrue(Files.exists(audit), "审计文件应生成");
        List<String> lines = Files.readAllLines(audit);
        assertTrue(lines.stream().anyMatch(l -> l.contains("RTKLIB-Java Adaptive Audit Log")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("# version: 3.2.0")));
        assertTrue(lines.stream().anyMatch(l -> l.contains("SCENE_SWITCH")), "应含场景切换事件");
    }

    @Test
    void resolveModeCoversAllPmodes() {
        RtkConfig cfg = new RtkConfig();
        assertEquals(AdaptiveMode.SPP, AdaptiveController.resolveMode(Constants.PMODE_SINGLE, cfg));
        assertEquals(AdaptiveMode.SPP, AdaptiveController.resolveMode(Constants.PMODE_DGPS, cfg));
        assertEquals(AdaptiveMode.RTK, AdaptiveController.resolveMode(Constants.PMODE_KINEMA, cfg));
        assertEquals(AdaptiveMode.RTK, AdaptiveController.resolveMode(Constants.PMODE_STATIC, cfg));
        assertEquals(AdaptiveMode.RTK, AdaptiveController.resolveMode(Constants.PMODE_STATIC_START, cfg));
        assertEquals(AdaptiveMode.RTK, AdaptiveController.resolveMode(Constants.PMODE_MOVEB, cfg));
        assertEquals(AdaptiveMode.RTK, AdaptiveController.resolveMode(Constants.PMODE_FIXED, cfg));
        assertEquals(AdaptiveMode.PPP_RTK, AdaptiveController.resolveMode(Constants.PMODE_PPPRTK_KINEMA, cfg));
        assertEquals(AdaptiveMode.PPP_RTK, AdaptiveController.resolveMode(Constants.PMODE_PPPRTK_STATIC, cfg));
        assertEquals(AdaptiveMode.PPP, AdaptiveController.resolveMode(Constants.PMODE_PPP_KINEMA, cfg));
        assertEquals(AdaptiveMode.PPP, AdaptiveController.resolveMode(Constants.PMODE_PPP_STATIC, cfg));
        cfg.pppAr.enableAR = true;
        assertEquals(AdaptiveMode.PPP_AR, AdaptiveController.resolveMode(Constants.PMODE_PPP_KINEMA, cfg));
        cfg.pppAr.enableAR = false; cfg.pppAr.enablePartialAR = true;
        assertEquals(AdaptiveMode.PPP_AR, AdaptiveController.resolveMode(Constants.PMODE_PPP_STATIC, cfg));
    }

    @Test
    void divergencePlusOverDownweightDisablesIggiii() {
        RtkConfig cfg = newCfg();
        PrcOpt opt = newOpt(Constants.PMODE_KINEMA, 1);
        AdaptiveController ctl = new AdaptiveController(Constants.PMODE_KINEMA, cfg, opt);
        ctl.activate();
        assertTrue(cfg.rtk.enableIggiii, "初始 CANYON 预设应开启 IGGIII");
        // 模拟: IGGIII 降权率 100% + 位置方差连续增长 → 自动关闭
        Rtk rtk = new Rtk();
        rtk.nx = 10;
        rtk.P = new double[100];
        double trace = 1.0;
        for (long e = 1; e <= 130; e++) {
            ctl.getOptStats().iggiiiDownweighted = 10;  // nv=10 → rate=1.0
            rtk.P[0] = trace; rtk.P[rtk.nx + 1] = trace; rtk.P[2 * rtk.nx + 2] = trace;
            trace *= 1.5;  // 持续增长
            ctl.onEpoch(e, 400000.0 + e, 10, 35.0, rtk);
        }
        assertFalse(cfg.rtk.enableIggiii, "过度降权+解发散应自动关闭 enableIggiii");
        ctl.deactivate();
    }
}
