package org.rtklib.java.config;

import java.io.Serializable;
import java.util.Set;

import org.rtklib.java.adaptive.AdaptiveListener;

/**
 * 自适应场景参数。
 * <p>
 * 从 {@link RtkConfig} 拆分出的独立子配置，归属自适应场景引擎（AdaptiveController /
 * AdaptiveAuditLogger）。字段名去掉原 {@code adaptive} 前缀（已在命名空间内）。
 * </p>
 */
public class AdaptiveOpts implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 是否启用自适应场景切换。原 enableAdaptiveScene。 */
    public boolean enableScene = false;
    /** 场景判定窗口（历元），0 表示按模式取默认值。原 adaptiveWindowEpochs。 */
    public int windowEpochs = 0;
    /** 场景切换冷却期（历元），0 表示按模式取默认值。原 adaptiveCooldownEpochs。 */
    public int cooldownEpochs = 0;

    /* 场景判定阈值（EMA 平滑特征）：OPEN→CANYON 满足 nSat<阈值 或 snr<阈值；CANYON→OPEN 需同时满足 */
    public int nsatOpenToCanyon = 8;
    public double snrOpenToCanyon = 30.0;
    public int nsatCanyonToOpen = 14;
    public double snrCanyonToOpen = 34.0;

    public Set<String> managedParams = null;
    public Set<String> lockedParams = null;
    public boolean auditEnabled = true;
    public String auditDir = "";
    public int auditMaxSizeKB = 5120;
    public int auditMaxFiles = 3;
    public boolean verbose = true;
    public boolean traceAll = false;
    public transient AdaptiveListener listener = null;

    public AdaptiveOpts() {
    }

    public AdaptiveOpts(AdaptiveOpts other) {
        copyFrom(other);
    }

    /**
     * 从另一个实例复制全部字段（含 transient 字段）。
     *
     * @param other 源实例，非 null
     */
    public void copyFrom(AdaptiveOpts other) {
        this.enableScene = other.enableScene;
        this.windowEpochs = other.windowEpochs;
        this.cooldownEpochs = other.cooldownEpochs;
        this.nsatOpenToCanyon = other.nsatOpenToCanyon;
        this.snrOpenToCanyon = other.snrOpenToCanyon;
        this.nsatCanyonToOpen = other.nsatCanyonToOpen;
        this.snrCanyonToOpen = other.snrCanyonToOpen;
        this.managedParams = other.managedParams;
        this.lockedParams = other.lockedParams;
        this.auditEnabled = other.auditEnabled;
        this.auditDir = other.auditDir;
        this.auditMaxSizeKB = other.auditMaxSizeKB;
        this.auditMaxFiles = other.auditMaxFiles;
        this.verbose = other.verbose;
        this.traceAll = other.traceAll;
        this.listener = other.listener;
    }
}
