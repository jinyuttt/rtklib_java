package org.rtklib.java.config;

import java.io.Serializable;

/**
 * PPP-RTK 参数。
 * <p>
 * 从 {@link RtkConfig} 拆分出的独立子配置，字段名去掉原 {@code pppRtk} 前缀
 * （模式总开关 {@code enablePppRtk} → {@code enable}）。
 * </p>
 */
public class PppRtkOpts implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 是否启用 PPP-RTK 模式。原 enablePppRtk。 */
    public boolean enable = false;
    public boolean enableAR = false;
    public double arRatio = 3.0;
    public boolean enableFixHold = false;
    public int fixHoldMinEpoch = 10;
    public double fixHoldVar = 1e-4;

    public PppRtkOpts() {
    }

    public PppRtkOpts(PppRtkOpts other) {
        copyFrom(other);
    }

    /**
     * 从另一个实例复制全部字段。
     *
     * @param other 源实例，非 null
     */
    public void copyFrom(PppRtkOpts other) {
        this.enable = other.enable;
        this.enableAR = other.enableAR;
        this.arRatio = other.arRatio;
        this.enableFixHold = other.enableFixHold;
        this.fixHoldMinEpoch = other.fixHoldMinEpoch;
        this.fixHoldVar = other.fixHoldVar;
    }
}
