package org.rtklib.java.data;

/**
 * 位置数据，3个分量 + 坐标系类型。
 *
 * <h3>CoordType 定义</h3>
 * <ul>
 *   <li>{@link CoordType#ECEF}：v1=x, v2=y, v3=z (m)</li>
 *   <li>{@link CoordType#LLH}：v1=lat, v2=lon, v3=height (deg, deg, m)</li>
 *   <li>{@link CoordType#ENU}：v1=e, v2=n, v3=u (m)，相对基站的基线向量</li>
 * </ul>
 *
 * <p>与 {@link Sol} 内部始终 ECEF 不同，
 * 本类是输出封装，根据 {@link PrcOpt#posMask} 配置在构造时已完成坐标转换。</p>
 */
public class Position {
    public final CoordType type;
    public final double v1;
    public final double v2;
    public final double v3;

    public Position(CoordType type, double v1, double v2, double v3) {
        this.type = type;
        this.v1 = v1;
        this.v2 = v2;
        this.v3 = v3;
    }

    @Override
    public String toString() {
        return switch (type) {
            case LLH -> String.format("LLH: %14.9f %14.9f %10.4f", v1, v2, v3);
            case ENU -> String.format("ENU: %10.4f %10.4f %10.4f", v1, v2, v3);
            default  -> String.format("ECEF: %14.4f %14.4f %14.4f", v1, v2, v3);
        };
    }
}