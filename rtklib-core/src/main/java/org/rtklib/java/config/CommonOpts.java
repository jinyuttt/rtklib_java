package org.rtklib.java.config;

import java.io.Serializable;

/**
 * 所有定位模式共用的参数。
 * <p>
 * 从 {@link RtkConfig} 拆分出的独立子配置，归属 RTK 主滤波器（RtkCore）等各模式共用逻辑。
 * </p>
 */
public class CommonOpts implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 参考星重选开关。 */
    public boolean enableParRefReselect = false;
    /** 参考星重选仰角门限（度）。 */
    public double parElMask = 15.0;
    /** 参考星最大连续重选次数。 */
    public int parMaxConsecutiveReselect = 3;

    /** 天顶对流层参数冻结所需卫星数门限。 */
    public int atmFrozenNsThresh = 7;

    /**
     * [JAVA-ONLY v2.3.0] IFLC频率对逐卫星协商。
     * IONOOPT_IFLC模式下，RTKLIB C固定用槽位0+seliflc()槽位做无电离层组合；
     * 当流动站与基准站接收机频段不一致（如流动站BDS-3只有B1I+B2a、基准站有
     * B1I+B2b+B2a）时，固定槽位对会导致整星被剔除。开启后每历元按两站公共
     * 频段协商槽位对（sticky策略，配对切换按周跳处理）。
     * 默认false，与RTKLIB C行为逐位一致。
     */
    public boolean enableIflcPairNegotiation = false;

    /** 是否按参数类型使用过程噪声。 */
    public boolean enableParamTypeNoise = false;
    /** ZTD 过程噪声。 */
    public double noiseZtdRw = 1e-4;
    /** 钟差白噪声。 */
    public double noiseClkWhite = 1e2;
    /** 电离层过程噪声。 */
    public double noiseIonoRw = 1e-4;

    public CommonOpts() {
    }

    public CommonOpts(CommonOpts other) {
        copyFrom(other);
    }

    /**
     * 从另一个实例复制全部字段。
     *
     * @param other 源实例，非 null
     */
    public void copyFrom(CommonOpts other) {
        this.enableParRefReselect = other.enableParRefReselect;
        this.parElMask = other.parElMask;
        this.parMaxConsecutiveReselect = other.parMaxConsecutiveReselect;
        this.atmFrozenNsThresh = other.atmFrozenNsThresh;
        this.enableIflcPairNegotiation = other.enableIflcPairNegotiation;
        this.enableParamTypeNoise = other.enableParamTypeNoise;
        this.noiseZtdRw = other.noiseZtdRw;
        this.noiseClkWhite = other.noiseClkWhite;
        this.noiseIonoRw = other.noiseIonoRw;
    }
}
