package org.rtklib.java.config;

import java.io.Serializable;

public class RtkConfig implements Serializable {
    private static final long serialVersionUID = 2L;

    /** 所有模式共用参数（CommonOpts）。 */
    public final CommonOpts common = new CommonOpts();

    /** RTK 专用参数（RtkOpts）。 */
    public final RtkOpts rtk = new RtkOpts();

    /** PPP 参数（PppOpts）。 */
    public final PppOpts ppp = new PppOpts();

    /** PPP-AR 参数（PppArOpts）。 */
    public final PppArOpts pppAr = new PppArOpts();

    /** SPP 参数（SppOpts）。 */
    public final SppOpts spp = new SppOpts();

    /** PPP-RTK 参数（PppRtkOpts）。 */
    public final PppRtkOpts pppRtk = new PppRtkOpts();

    /** 自适应场景参数（AdaptiveOpts）。 */
    public final AdaptiveOpts adaptive = new AdaptiveOpts();

    public RtkConfig() {
    }

    public RtkConfig(RtkConfig other) {
        this.common.copyFrom(other.common);
        this.rtk.copyFrom(other.rtk);

        this.ppp.copyFrom(other.ppp);
        this.spp.copyFrom(other.spp);
        this.pppRtk.copyFrom(other.pppRtk);
        this.pppAr.copyFrom(other.pppAr);
        this.adaptive.copyFrom(other.adaptive);
    }
}
