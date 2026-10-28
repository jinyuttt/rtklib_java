package org.rtklib.java.config;

import java.io.Serializable;

import org.rtklib.java.cssr.CompactSsrDecoder;

/**
 * PPP / PPP-RTK 公共参数。
 * <p>
 * 从 {@link RtkConfig} 拆分出的独立子配置，字段名去掉原 {@code ppp} 前缀
 * （{@code enablePppIsbClockInit} → {@code enableIsbClockInit}）。
 * </p>
 */
public class PppOpts implements Serializable {
    private static final long serialVersionUID = 1L;

    public boolean enableGpt3Vmf3 = false;
    public String gpt3GridFile = "";
    public String vmf3OpFile = "";
    public boolean useGpt3Grid = false;

    public boolean enableIers2010 = false;
    public boolean enableAt1S2 = false;

    public String ionexFile = "";

    public boolean enableIsbIfcbIfb = false;
    /**
     * JAVA-ONLY：PPP 精密星历模式下用 SPP 的系统间偏差(ISB)初始化各系统接收机钟差。
     * 默认 false=逐值对齐 RTKLIB 2.5.0 ppp.c udclk_ppp（精密模式 neglect receiver
     * inter-system bias，全部系统用 dtr[0] 初始化，GLO/GAL 初值偏差数百米被
     * maxinno 剔除，实际上仅 GPS 参与解算）。开启后精密模式也用 dtr[0]+dtr[i]
     * （即广播模式语义），多系统 PPP 可用性显著改善。
     */
    public boolean enableIsbClockInit = false;
    public boolean estimateIsb = true;
    public boolean estimateIfcb = true;
    public boolean estimateIfb = true;
    public double isbPrn = 0.01;
    public double ifcbPrn = 0.01;
    public double ifbPrn = 0.001;

    /**
     * [JAVA-ONLY] IONOOPT_EST 健壮初始化开关。
     * 非组合 PPP（ionoopt=EST）首历元（或周跳后重置）时，相位 prefit 残差含未建模的
     * 码偏差/电离层初值，超出 maxinno 门限被全部剔除，仅码行进入 EKF，相位偏差状态
     * 永不更新，形成永久死锁（C 2.5.0 固有缺陷，C 依赖 postpos 自动加载 DCB 产品规避）。
     * 开启后：对本历元刚 initx 的 bias/ion 状态（协方差 P 恰为 VAR_BIAS/VAR_IONO，
     * 即尚未被任何 KF 更新吸收）跳过相位行的 prefit 剔除，让 EKF 一次性吸收常数偏差。
     * 仅放宽相位行（code==0），码行剔除逻辑保持与 C 一致；状态被估计过一历元后
     * 自动恢复正常剔除。
     * 默认 false=与 RTKLIB C 行为一致。正确做法是加载 DCB 产品（DcbReader）。
     */
    public boolean enableIonoEstRobust = false;

    public boolean enableOsb = false;
    public String osbFile = "";
    public String dcbFile = "";

    public boolean enableSsrTropDirect = false;
    public double ssrMaxAge = 60.0;
    public int ssrIonoMode = 1;
    public boolean enableCompactSsr = false;
    public String compactSsrGridFile = "";
    public transient CompactSsrDecoder cssrDecoder = null;

    public PppOpts() {
    }

    public PppOpts(PppOpts other) {
        copyFrom(other);
    }

    /**
     * 从另一个实例复制全部字段（含 transient 字段）。
     *
     * @param other 源实例，非 null
     */
    public void copyFrom(PppOpts other) {
        this.enableGpt3Vmf3 = other.enableGpt3Vmf3;
        this.gpt3GridFile = other.gpt3GridFile;
        this.vmf3OpFile = other.vmf3OpFile;
        this.useGpt3Grid = other.useGpt3Grid;
        this.enableIers2010 = other.enableIers2010;
        this.enableAt1S2 = other.enableAt1S2;
        this.ionexFile = other.ionexFile;
        this.enableIsbIfcbIfb = other.enableIsbIfcbIfb;
        this.enableIsbClockInit = other.enableIsbClockInit;
        this.estimateIsb = other.estimateIsb;
        this.estimateIfcb = other.estimateIfcb;
        this.estimateIfb = other.estimateIfb;
        this.isbPrn = other.isbPrn;
        this.ifcbPrn = other.ifcbPrn;
        this.ifbPrn = other.ifbPrn;
        this.enableIonoEstRobust = other.enableIonoEstRobust;
        this.enableOsb = other.enableOsb;
        this.osbFile = other.osbFile;
        this.dcbFile = other.dcbFile;
        this.enableSsrTropDirect = other.enableSsrTropDirect;
        this.ssrMaxAge = other.ssrMaxAge;
        this.ssrIonoMode = other.ssrIonoMode;
        this.enableCompactSsr = other.enableCompactSsr;
        this.compactSsrGridFile = other.compactSsrGridFile;
        this.cssrDecoder = other.cssrDecoder;
    }
}
