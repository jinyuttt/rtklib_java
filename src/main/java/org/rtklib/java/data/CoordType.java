package org.rtklib.java.data;

/**
 * 坐标系类型枚举，用于 {@link Position}、{@link Velocity}、{@link Accuracy} 的 type 字段。
 *
 * <ul>
 *   <li>{@code ECEF}：地心地固坐标系 (Earth-Centered Earth-Fixed)</li>
 *   <li>{@code LLH}：大地坐标系 (Latitude, Longitude, Height)</li>
 *   <li>{@code ENU}：站心坐标系 (East, North, Up)，相对基站的基线向量</li>
 * </ul>
 */
public enum CoordType {
    ECEF,
    LLH,
    ENU
}