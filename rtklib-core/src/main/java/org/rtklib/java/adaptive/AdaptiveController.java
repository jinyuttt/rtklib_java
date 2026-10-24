package org.rtklib.java.adaptive;

import org.rtklib.java.config.RtkConfig;
import org.rtklib.java.constants.Constants;
import org.rtklib.java.data.Obsd;
import org.rtklib.java.data.PrcOpt;
import org.rtklib.java.data.Rtk;
import org.rtklib.java.time.TimeSystem;
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

    private double prevPosX = Double.NaN;
    private double prevPosY = Double.NaN;
    private double prevPosZ = Double.NaN;
    private boolean auditOpened = false;

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
        PresetManager.applyPreset(mode, currentScene, override, managedParams, lockedParams, opt.dynamics);
        override.applyTo(cfg, opt);

        state = new AdaptiveState(mode);
        state.setScene(currentScene);

        auditLogger = new AdaptiveAuditLogger(
                cfg.adaptiveAuditEnabled, cfg.adaptiveAuditDir,
                cfg.adaptiveAuditMaxSizeKB, cfg.adaptiveAuditMaxFiles);
        openAudit();
        auditOpened = true;

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
        if (cooldownRemaining > 0) cooldownRemaining--;

        intervalDetector.feed(gpst);
        if (intervalDetector.isDetectionComplete()) {
            intervalDetector.warnIfAbnormal(windowEpochs, cooldownEpochs);
            if (!auditOpened) {
                openAudit();
                auditOpened = true;
            }
        }

        metrics.update(nSat, avgSnr);

        if (windowCounter >= windowEpochs) {
            windowCounter = 0;
            evaluateScene(epoch);
        }

        if (currentScene == GnssScene.URBAN_CANYON) {
            state.incrementCanyonEpoch();
        }

        // 优化项效果监控: 过度降权 + 解发散 → 自动关闭对应优化项
        optStats.update(nSat, Math.max(nSat, 1));
        optMonitor.checkAndAct(optStats, cfg, override, auditLogger, listener);
        if (rtk != null && rtk.P != null && rtk.nx > 2 && rtk.P.length > 2 * rtk.nx + 2) {
            double posVarTrace = rtk.P[0] + rtk.P[rtk.nx + 1] + rtk.P[2 * rtk.nx + 2];
            if (optMonitor.checkDivergence(posVarTrace)
                    && optStats.getSlidingIggiiiRate() > OptimizationMonitor.IGGIII_RATE_MAX
                    && (lockedParams == null || !lockedParams.contains("enableIggiii"))) {
                override.set("enableIggiii", false);
                override.applyTo(cfg, opt);
                LOG.warn("[ADAPTIVE] IGGIII 过度降权且解发散, 自动关闭 enableIggiii");
                auditLogger.logEvent(epoch, "", "OPT_DISABLE", "enableIggiii:true", "enableIggiii:false",
                        "", "iggiiiRate=" + String.format("%.2f", optStats.getSlidingIggiiiRate()), "NONE");
            }
        }

        if (isStatic && rtk != null && !Double.isNaN(prevPosX)) {
            checkPositionJump(epoch, rtk);
        }
        if (rtk != null) {
            prevPosX = rtk.sol.rr[0];
            prevPosY = rtk.sol.rr[1];
            prevPosZ = rtk.sol.rr[2];
        }
    }

    private void openAudit() {
        double interval = intervalDetector.getDetectedInterval();
        if (Double.isNaN(interval) || interval <= 0) interval = 1.0;
        String dist = intervalDetector.isDetectionComplete()
                ? intervalDetector.getDistributionSummary().replace("\n", " ") : "(detecting)";
        String managed = managedParams == null ? "(all)" : String.join(",", managedParams);
        String locked = lockedParams == null ? "(none)" : String.join(",", lockedParams);
        auditLogger.open("GPST", mode, windowEpochs, cooldownEpochs, interval,
                GnssScene.URBAN_CANYON, managed, locked, dist);
    }

    private void evaluateScene(long epoch) {
        if (cooldownRemaining > 0) {
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

        PresetManager.applyPreset(mode, newScene, override, managedParams, lockedParams, opt.dynamics);
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
        double dx = rtk.sol.rr[0] - prevPosX;
        double dy = rtk.sol.rr[1] - prevPosY;
        double dz = rtk.sol.rr[2] - prevPosZ;
        double jump = Math.sqrt(dx * dx + dy * dy + dz * dz);

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

        PresetManager.applyPreset(mode, rollbackTarget, override, managedParams, lockedParams, opt.dynamics);
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
        if (auditLogger != null) {
            if (!auditOpened) {
                openAudit();
                auditOpened = true;
            }
            auditLogger.close();
        }
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
                || pmode == Constants.PMODE_STATIC_START || pmode == Constants.PMODE_MOVEB
                || pmode == Constants.PMODE_FIXED) return AdaptiveMode.RTK;
        if (pmode == Constants.PMODE_PPPRTK_KINEMA || pmode == Constants.PMODE_PPPRTK_STATIC) return AdaptiveMode.PPP_RTK;
        // PMODE_PPP_KINEMA / PMODE_PPP_STATIC / PMODE_PPP_FIXED 及未知模式按 AR 开关归 PPP_AR / PPP
        boolean hasAr = cfg.enablePppAR || cfg.enablePppArFixHold
                || cfg.enablePppPartialAR || cfg.enableBds3PppAR || cfg.enableMultiFreqAR;
        return hasAr ? AdaptiveMode.PPP_AR : AdaptiveMode.PPP;
    }

    /**
     * 处理器接线辅助：仅当 {@code cfg.enableAdaptiveScene=true} 时构造并激活控制器，
     * 否则返回 null（默认关闭时零干预、零对象开销，调用方按 null 跳过即可）。
     * <p>控制器直接绑定传入的 opt/cfg 引用，场景切换通过覆盖-恢复机制就地修改，
     * 解算链路下一历元即读到新参数；{@link #deactivate()} 恢复全部原始值。</p>
     *
     * @param opt 解算实际使用的 PrcOpt（通常挂在 Rtk 上的那份）
     * @param rtk 解算状态对象，其 rtkConfig 为开关与配置来源；可为 null（返回 null）
     * @return 已激活的控制器，或 null（开关关闭/配置缺失）
     */
    public static AdaptiveController startIfEnabled(PrcOpt opt, Rtk rtk) {
        if (opt == null || rtk == null) return null;
        return startIfEnabled(opt, rtk.rtkConfig);
    }

    /**
     * {@link #startIfEnabled(PrcOpt, Rtk)} 的显式配置版本，供无 Rtk 对象的链路（如 SPP）使用。
     */
    public static AdaptiveController startIfEnabled(PrcOpt opt, RtkConfig cfg) {
        if (opt == null || cfg == null || !cfg.enableAdaptiveScene) return null;
        AdaptiveController ctl = new AdaptiveController(opt.mode, cfg, opt);
        ctl.activate();
        return ctl;
    }

    /**
     * 处理器接线辅助：从观测历元数组提取特征（nSat/avgSnr）并驱动 {@link #onEpoch}。
     * <p>特征口径与验证 harness 一致：nSat=非基准站(rcv!=2)观测条数，
     * avgSnr=这些观测首频点 SNR&gt;0 的均值（无有效 SNR 时为 0）。</p>
     *
     * @param epoch 历元序号（从 1 起，调用方维护）
     * @param obs   当前历元观测数组（可含基准站 rcv==2，内部自动剔除）
     * @param n     有效条数
     * @param rtk   解算状态对象（用于解发散/位置跳变检测），无 Rtk 的链路传 null
     */
    public void onObsEpoch(long epoch, Obsd[] obs, int n, Rtk rtk) {
        if (!active || obs == null || n <= 0) return;
        int ns = 0, snrCnt = 0;
        double snrSum = 0.0;
        int m = Math.min(n, obs.length);
        for (int i = 0; i < m; i++) {
            if (obs[i] == null || obs[i].rcv == 2) continue;
            ns++;
            if (obs[i].SNR != null && obs[i].SNR.length > 0 && obs[i].SNR[0] > 0) {
                snrSum += obs[i].SNR[0];
                snrCnt++;
            }
        }
        double gpst = TimeSystem.time2gpst(obs[0].time, null);
        onEpoch(epoch, gpst, ns, snrCnt > 0 ? snrSum / snrCnt : 0.0, rtk);
    }
}