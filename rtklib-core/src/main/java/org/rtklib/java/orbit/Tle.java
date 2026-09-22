package org.rtklib.java.orbit;

/**
 * TLE 集合容器，对应测试代码 Sgp4PropagatorTest 中使用的 {@code Tle} 类型。
 *
 * <p>持有 {@link #n} 条 {@link TleData}，由 {@link TleParser#tleRead} 填充。</p>
 */
public class Tle {

    /** 已解析的 TLE 条数。 */
    public int n;

    /** 已解析的 TLE 数组，有效长度为 {@link #n}。 */
    public TleData[] data;

    public Tle() {
        this.n = 0;
        this.data = new TleData[0];
    }
}
