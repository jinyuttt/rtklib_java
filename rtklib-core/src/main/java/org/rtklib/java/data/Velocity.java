package org.rtklib.java.data;

import java.io.Serializable;

/**
 * 速度数据，3个分量 + 坐标系类型。
 *
 * <h3>CoordType 定义</h3>
 * <ul>
 *   <li>{@link CoordType#ECEF}：v1=vx, v2=vy, v3=vz (m/s)</li>
 *   <li>{@link CoordType#ENU}：v1=ve, v2=vn, v3=vu (m/s)</li>
 * </ul>
 *
 * <p>注意：LLH 和 ENU 对速度的表示相同（都是 ENU 方向 {ve,vn,vu}），
 * 因为速度不存在"经纬度"表示。故 LLH 和 ENU 统一用 {@link CoordType#ENU}。</p>
 */
public class Velocity implements Serializable {
    private static final long serialVersionUID = 1L;
    public final CoordType type;
    public final double v1;
    public final double v2;
    public final double v3;

    public Velocity(CoordType type, double v1, double v2, double v3) {
        this.type = type;
        this.v1 = v1;
        this.v2 = v2;
        this.v3 = v3;
    }

    @Override
    public String toString() {
        if (type == CoordType.ECEF) {
            return String.format("ECEF: %10.4f %10.4f %10.4f", v1, v2, v3);
        }
        return String.format("ENU: %10.4f %10.4f %10.4f", v1, v2, v3);
    }
}