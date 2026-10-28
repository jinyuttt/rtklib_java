package org.rtklib.java.adaptive;

import org.junit.jupiter.api.Test;
import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.data.PrcOpt;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** PresetManager 单元测试: 5模式×2场景预设 + 白名单/黑名单 + 默认窗口/冷却 + RTK enableAdaptiveQ */
class PresetManagerTest {

    private AdaptiveOverride newOverride() {
        RtkConfig cfg = new RtkConfig();
        PrcOpt opt = new PrcOpt();
        AdaptiveOverride ov = new AdaptiveOverride();
        ov.snapshot(cfg, opt);
        ov.activate();
        return ov;
    }

    @Test
    void presetsMatchDesignTable() {
        // RTK OPEN: thresslip=0.05, maxinno=30/30, elmin=15°
        Map<String, Object> open = PresetManager.getPreset(AdaptiveMode.RTK, GnssScene.OPEN_SKY);
        assertEquals(0.05, (double) open.get("thresslip"), 1e-12);
        assertEquals(30.0, (double) open.get("maxinno0"), 1e-12);
        assertEquals(Math.toRadians(15), (double) open.get("elmin"), 1e-12);
        assertEquals(false, open.get("enableIggiii"));
        // RTK CANYON: thresslip=0.10, maxinno=50/50, elmin=20°
        Map<String, Object> canyon = PresetManager.getPreset(AdaptiveMode.RTK, GnssScene.URBAN_CANYON);
        assertEquals(0.10, (double) canyon.get("thresslip"), 1e-12);
        assertEquals(50.0, (double) canyon.get("maxinno0"), 1e-12);
        assertEquals(Math.toRadians(20), (double) canyon.get("elmin"), 1e-12);
        assertEquals(true, canyon.get("enableIggiii"));
        // PPP-AR CANYON 安全红线: thresslip 不放宽
        Map<String, Object> pppar = PresetManager.getPreset(AdaptiveMode.PPP_AR, GnssScene.URBAN_CANYON);
        assertEquals(0.05, (double) pppar.get("thresslip"), 1e-12);
        assertEquals(7.0, (double) pppar.get("maxinno0"), 1e-12);
        // SPP 含 enableSppInnovChisq
        assertEquals(true, PresetManager.getPreset(AdaptiveMode.SPP, GnssScene.OPEN_SKY).get("spp.enableInnovChisq"));
        assertEquals(true, PresetManager.getPreset(AdaptiveMode.SPP, GnssScene.URBAN_CANYON).get("spp.enableInnovChisq"));
    }

    @Test
    void defaultWindowCooldownMatchDoc() {
        assertEquals(10, PresetManager.defaultWindow(AdaptiveMode.SPP));
        assertEquals(30, PresetManager.defaultWindow(AdaptiveMode.RTK));
        assertEquals(60, PresetManager.defaultWindow(AdaptiveMode.PPP));
        assertEquals(60, PresetManager.defaultWindow(AdaptiveMode.PPP_AR));
        assertEquals(30, PresetManager.defaultWindow(AdaptiveMode.PPP_RTK));
        assertEquals(10, PresetManager.defaultCooldown(AdaptiveMode.SPP));
        assertEquals(90, PresetManager.defaultCooldown(AdaptiveMode.RTK));
        assertEquals(300, PresetManager.defaultCooldown(AdaptiveMode.PPP));
        assertEquals(300, PresetManager.defaultCooldown(AdaptiveMode.PPP_AR));
        assertEquals(90, PresetManager.defaultCooldown(AdaptiveMode.PPP_RTK));
    }

    @Test
    void whitelistRestrictsManagedParams() {
        AdaptiveOverride ov = newOverride();
        PresetManager.applyPreset(AdaptiveMode.RTK, GnssScene.URBAN_CANYON, ov,
                Set.of("thresslip"), null, 1);
        assertEquals(0.10, ov.getDouble("thresslip", 0), 1e-12);
        assertEquals(Math.toRadians(15), ov.getDouble("elmin", Math.toRadians(15)), 1e-12,
                "白名单外 elmin 不应被覆盖");
    }

    @Test
    void blacklistLocksParam() {
        AdaptiveOverride ov = newOverride();
        PresetManager.applyPreset(AdaptiveMode.RTK, GnssScene.URBAN_CANYON, ov,
                null, Set.of("elmin"), 1);
        assertEquals(Math.toRadians(15), ov.getDouble("elmin", Math.toRadians(15)), 1e-12,
                "黑名单 elmin 不应被覆盖");
        assertEquals(0.10, ov.getDouble("thresslip", 0), 1e-12);
    }

    @Test
    void rtkAdaptiveQFollowsDynamicsAndScene() {
        // RTK OPEN + 静态(dynamics=0) → enableAdaptiveQ=false
        AdaptiveOverride ov1 = newOverride();
        PresetManager.applyPreset(AdaptiveMode.RTK, GnssScene.OPEN_SKY, ov1, null, null, 0);
        assertFalse(ov1.getBoolean("enableAdaptiveQ", true));
        // RTK OPEN + 动态(dynamics=1) → true
        AdaptiveOverride ov2 = newOverride();
        PresetManager.applyPreset(AdaptiveMode.RTK, GnssScene.OPEN_SKY, ov2, null, null, 1);
        assertTrue(ov2.getBoolean("enableAdaptiveQ", false));
        // RTK CANYON + 静态 → true (CANYON 恒开)
        AdaptiveOverride ov3 = newOverride();
        PresetManager.applyPreset(AdaptiveMode.RTK, GnssScene.URBAN_CANYON, ov3, null, null, 0);
        assertTrue(ov3.getBoolean("enableAdaptiveQ", false));
        // 非 RTK 模式不应注入 enableAdaptiveQ
        AdaptiveOverride ov4 = newOverride();
        PresetManager.applyPreset(AdaptiveMode.PPP, GnssScene.URBAN_CANYON, ov4, null, null, 0);
        assertFalse(ov4.getBoolean("enableAdaptiveQ", false), "PPP 模式不应覆盖 enableAdaptiveQ");
    }
}
