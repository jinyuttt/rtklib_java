package org.rtklib.java.pppar;

import org.rtklib.java.config.RtkConfig;

public final class PppArConfig {
    private final boolean enablePppAR;
    private final boolean enablePppArFixHold;
    private final boolean enablePppPartialAR;
    private final boolean enableBds3PppAR;
    private final boolean enableMultiFreqAR;

    private PppArConfig(boolean enablePppAR, boolean enablePppArFixHold,
                        boolean enablePppPartialAR, boolean enableBds3PppAR,
                        boolean enableMultiFreqAR) {
        this.enablePppAR = enablePppAR;
        this.enablePppArFixHold = enablePppArFixHold;
        this.enablePppPartialAR = enablePppPartialAR;
        this.enableBds3PppAR = enableBds3PppAR;
        this.enableMultiFreqAR = enableMultiFreqAR;
    }

    public static PppArConfig from(RtkConfig cfg) {
        return new PppArConfig(
            cfg.enablePppAR,
            cfg.enablePppArFixHold,
            cfg.enablePppPartialAR,
            cfg.enableBds3PppAR,
            cfg.enableMultiFreqAR
        );
    }

    public boolean hasAnyAr() {
        return enablePppAR || enablePppArFixHold || enablePppPartialAR
               || enableBds3PppAR || enableMultiFreqAR;
    }

    public boolean isEnablePppAR() { return enablePppAR; }
    public boolean isEnablePppArFixHold() { return enablePppArFixHold; }
    public boolean isEnablePppPartialAR() { return enablePppPartialAR; }
    public boolean isEnableBds3PppAR() { return enableBds3PppAR; }
    public boolean isEnableMultiFreqAR() { return enableMultiFreqAR; }
}