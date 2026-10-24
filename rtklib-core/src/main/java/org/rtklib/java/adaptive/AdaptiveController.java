package org.rtklib.java.adaptive;

import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.PrcOpt;
import org.rtklib.java.data.Rtk;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

public class AdaptiveController {
    private static final Logger LOG = LoggerFactory.getLogger(AdaptiveController.class);

    private static final int NSAT_OPEN_TO_CANYON = 8;
    private static final int NSAT_CANYON_TO_OPEN = 14;
    private static final double SNR_OPEN_TO_CANYON = 30.0;
    private static final double SNR_CANYON_TO_OPEN = 38.0;
    private static final int NSAT_STATIC_OPEN_TO_CANYON = 5;
    private static final int STATIC_WINDOW_MIN = 100;
    private static final int STATIC_COOLDOWN_MIN = 900;
    private static final int STATIC_CONFIRM_WINDOWS = 3;
    private static final double JUMP_SIGMA_THRESHOLD = 3.0;

    private final AdaptiveMode mode;
    private final boolean isStatic;
    private GnssScene currentScene;
    private GnssScene pendingScene;
    private int confirmCount;
    private int cooldownRemaining;
    private int windowEpochs;
    private int cooldownEpochs;
    private int windowCounter;
    private boolean active = false;

    private final FeatureMetrics metrics = new FeatureMetrics();
    private final AdaptiveOverride override = new AdaptiveOverride();
    private final OptimizationStats optStats = new OptimizationStats();
    private final OptimizationMonitor optMonitor = new OptimizationMonitor();
    private final SampleIntervalDetector intervalDetector = new SampleIntervalDetector();
    private AdaptiveAuditLogger auditLogger;
    private AdaptiveState state;

    private final RtkConfig cfg;
    private final PrcOpt opt;
    private final Set<String> managedParams;
    private final Set<String> lockedParams;
    private final AdaptiveListener listener;

    private double prevPosN = Double.NaN;
    private double prevPosE = Double.NaN;
    private double prevPosU = Double.NaN;

    public AdaptiveController(int pmode, RtkConfig cfg, PrcOpt opt) {
        this.cfg = cfg;
        this.opt = opt;
        this.mode = resolveMode(pmode, cfg);
        this.isStatic = opt.dynamics == 0 || opt.mode == Constants.PMODE_STATIC
                || opt.mode == Constants.PMODE_STATIC_START;
        this.managedParams = cfg.adaptiveManagedParams;
        this.lockedParams = cfg.adaptiveLockedParams;
        this.listener = cfg.adaptiveListener;
    }

    public void activate() {
        if (!cfg.enableAdaptiveScene) return;
        active = true;

        windowEpochs = cfg.adaptiveWindowEpochs > 0 ? cfg.adaptiveWindowEpochs : PresetManager.defaultWindow(mode);
        cooldownEpochs = cfg.adaptiveCooldownEpochs > 0 ? cfg.adaptiveCooldownEpochs : PresetManager.defaultCooldown(mode);

        if (isStatic) {
            if (windowEpochs < STATIC_WINDOW_MIN) {
                LOG.info("[ADAPTIVE] 静态模式窗口自动提升: {} → {} 历元", windowEpochs, STATIC_WINDOW_MIN);
                windowEpochs = STATIC_WINDOW_MIN;
            }
            if (cooldownEpochs < STATIC_COOLDOWN_MIN) {
                LOG.info("[ADAPTIVE] 静态模式冷却自动提升: {} → {} 历元", cooldownEpochs, STATIC_COOLDOWN_MIN);
                cooldownEpochs = STATIC_COOLDOWN_MIN;
            }
        }

        currentScene = GnssScene.URBAN_CANYON;
        pendingScene = null;
        confirmCount = 0;
        cooldownRemaining = 0;
        windowCounter = 0;

        override.snapshot(cfg, opt);
        override.activate();
        PresetManager.applyPreset(mode, currentScene, override, managedParams, lockedParams);
        override.applyTo(cfg, opt);

        state = new AdaptiveState(mode);
        state.setScene(currentScene);

        auditLogger = new AdaptiveAuditLogger(
                cfg.adaptiveAuditEnabled, cfg.adaptiveAuditDir,
                cfg.adaptiveAuditMaxSizeKB, cfg.adaptiveAuditMaxFiles);

        if (cfg.adaptiveVerbose) {
            LOG.info("[ADAPTIVE] 自适应引擎启动: mode={}, scene={}, window={}历元, cooldown={}历元, static={}",
                    mode, currentScene, windowEpochs, cooldownEpochs, isStatic);
        }
    }

    public void onEpoch(long epoch, double gpst, int nSat, double avgSnr, Rtk rtk) {
        if (!active) return;

        state.incrementTotalEpochs();
        state.setEpoch(epoch);
        windowCounter++;

        intervalDetector.feed(gpst);
        if (intervalDetector.isDetectionComplete() && epoch <= 35) {
            intervalDetector.warnIfAbnormal(windowEpochs, cooldownEpochs);
        }

        metrics.update(nSat, avgSnr);

        if (windowCounter >= windowEpochs) {
            windowCounter = 0;
            evaluateScene(epoch);
        }

        if (currentScene == GnssScene.URBAN_CANYON) {
            state.incrementCanyonEpoch();
        }

        if (isStatic && rtk != null && !Double.isNaN(prevPosN)) {
            checkPositionJump(epoch, rtk);
        }
        if (rtk != null) {
            prevPosN = rtk.sol.rr[0];
            prevPosE = rtk.sol.rr[1];
            prevPosU = rtk.sol.rr[2];
        }
    }

    private void evaluateScene(long epoch) {
        if (cooldownRemaining > 0) {
            cooldownRemaining--;
            return;
        }

        int nSatThresh = isStatic && currentScene == GnssScene.OPEN_SKY ? NSAT_STATIC_OPEN_TO_CANYON : NSAT_OPEN_TO_CANYON;
        GnssScene detected = metrics.detect(currentScene, nSatThresh, SNR_OPEN_TO_CANYON,
                NSAT_CANYON_TO_OPEN, SNR_CANYON_TO_OPEN);

        if (detected != currentScene) {
            if (pendingScene == detected) {
                confirmCount++;
            } else {
                pendingScene = detected;
                confirmCount = 1;
            }

            int requiredConfirms = (isStatic && detected == GnssScene.URBAN_CANYON) ? STATIC_CONFIRM_WINDOWS : 1;
            if (confirmCount >= requiredConfirms) {
                switchScene(epoch, detected);
                pendingScene = null;
                confirmCount = 0;
            }
        } else {
            pendingScene = null;
            confirmCount = 0;
        }
    }

    private void switchScene(long epoch, GnssScene newScene) {
        GnssScene oldScene = currentScene;
        currentScene = newScene;
        state.setScene(newScene);
        state.incrementSwitchCount();
        cooldownRemaining = cooldownEpochs;

        PresetManager.applyPreset(mode, newScene, override, managedParams, lockedParams);
        override.applyTo(cfg, opt);

        String trigger = metrics.triggerInfo(oldScene, newScene, NSAT_OPEN_TO_CANYON, SNR_OPEN_TO_CANYON,
                NSAT_CANYON_TO_OPEN, SNR_CANYON_TO_OPEN);
        String changes = override.diffString();

        if (cfg.adaptiveVerbose) {
            LOG.info("[ADAPTIVE] SCENE_SWITCH epoch={} {} → {}, trigger={}, changes={}",
                    epoch, oldScene, newScene, trigger, changes);
        }

        auditLogger.logEvent(epoch, "", "SCENE_SWITCH", oldScene.toString(), newScene.toString(),
                trigger, changes, "NONE");

        if (listener != null) {
            AdaptiveEvent event = new AdaptiveEvent(epoch, AdaptiveEvent.Type.SCENE_SWITCH,
                    oldScene.toString(), newScene.toString(), trigger, changes, "");
            listener.onSceneSwitch(event);
        }
    }

    private void checkPositionJump(long epoch, Rtk rtk) {
        double dn = rtk.sol.rr[0] - prevPosN;
        double de = rtk.sol.rr[1] - prevPosE;
        double du = rtk.sol.rr[2] - prevPosU;
        double jump = Math.sqrt(dn * dn + de * de + du * du);

        double posStd = Math.sqrt(rtk.sol.qr[0] + rtk.sol.qr[1] + rtk.sol.qr[2]);
        if (posStd <= 0) return;

        if (jump > JUMP_SIGMA_THRESHOLD * posStd) {
            rollbackScene(epoch, "位置跳变: " + String.format("%.3fm", jump));
        }
    }

    private void rollbackScene(long epoch, String reason) {
        GnssScene rollbackTarget = currentScene == GnssScene.URBAN_CANYON ? GnssScene.OPEN_SKY : GnssScene.URBAN_CANYON;
        currentScene = rollbackTarget;
        state.setScene(rollbackTarget);
        state.incrementRollback();
        cooldownRemaining = cooldownEpochs;

        PresetManager.applyPreset(mode, rollbackTarget, override, managedParams, lockedParams);
        override.applyTo(cfg, opt);

        if (cfg.adaptiveVerbose) {
            LOG.warn("[ADAPTIVE] ROLLBACK epoch={}, reason={}", epoch, reason);
        }
        auditLogger.logRollback(epoch, reason);

        if (listener != null) {
            AdaptiveEvent event = new AdaptiveEvent(epoch, AdaptiveEvent.Type.ROLLBACK,
                    "", rollbackTarget.toString(), "", "", reason);
            listener.onSceneSwitch(event);
        }
    }

    public void onDegradation(long epoch, String from, String to, String reason) {
        if (!active) return;
        state.incrementDegradation();

        if (cfg.adaptiveVerbose) {
            LOG.info("[ADAPTIVE] DEGRADATION epoch={} {} → {}, reason={}", epoch, from, to, reason);
        }
        auditLogger.logEvent(epoch, "", "DEGRADATION", from, to, "", "reason=" + reason, "");

        if (listener != null) {
            AdaptiveEvent event = new AdaptiveEvent(epoch, AdaptiveEvent.Type.DEGRADATION, from, to, "", "", reason);
            listener.onDegradation(event);
        }
    }

    public void deactivate() {
        if (!active) return;
        active = false;
        override.restoreAll(cfg, opt);
        override.deactivate();
        if (auditLogger != null) auditLogger.close();
        if (cfg.adaptiveVerbose && state != null) {
            LOG.info(state.summary());
        }
    }

    public AdaptiveState getState() { return state; }
    public GnssScene getCurrentScene() { return currentScene; }
    public AdaptiveOverride getOverride() { return override; }
    public OptimizationStats getOptStats() { return optStats; }
    public FeatureMetrics getMetrics() { return metrics; }
    public boolean isActive() { return active; }

    public static AdaptiveMode resolveMode(int pmode, RtkConfig cfg) {
        if (pmode == Constants.PMODE_SINGLE || pmode == Constants.PMODE_DGPS) return AdaptiveMode.SPP;
        if (pmode == Constants.PMODE_KINEMA || pmode == Constants.PMODE_STATIC
                || pmode == Constants.PMODE_STATIC_START) return AdaptiveMode.RTK;
        if (pmode == Constants.PMODE_PPPRTK_KINEMA || pmode == Constants.PMODE_PPPRTK_STATIC) return AdaptiveMode.PPP_RTK;
        boolean hasAr = cfg.enablePppAR || cfg.enablePppArFixHold
                || cfg.enablePppPartialAR || cfg.enableBds3PppAR || cfg.enableMultiFreqAR;
        return hasAr ? AdaptiveMode.PPP_AR : AdaptiveMode.PPP;
    }
}