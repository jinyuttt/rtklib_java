package org.rtklib.java.data;

import java.io.Serializable;

/**
 * 精度数据，3个对角标准差 + 3个互协方差 + 坐标系类型。
 *
 * <h3>CoordType 定义</h3>
 * <ul>
 *   <li>{@link CoordType#ECEF}：s1=σx, s2=σy, s3=σz, c12=σxy, c23=σyz, c31=σzx</li>
 *   <li>{@link CoordType#ENU}：s1=σe, s2=σn, s3=σu, c12=σen, c23=σeu, c31=σnu</li>
 * </ul>
 *
 * <p>内部 {@link Sol#qr} 存储 ECEF 协方差（m²），本类存储的是标准差（m），
 * 即对协方差矩阵对角元素开方、非对角元素取绝对值开方。
 * 与 RTKLIB .pos 文件格式一致（sde/sdn/sdu/sdne/sdeu/sdun）。</p>
 *
 * <p>注意：LLH 和 ENU 方向的精度表示完全相同（都是 ENU 方向），
 * 故统一用 {@link CoordType#ENU}，不再区分。</p>
 */
public class Accuracy implements Serializable {
    private static final long serialVersionUID = 1L;
    public final CoordType type;
    public final double s1;
    public final double s2;
    public final double s3;
    public final double c12;
    public final double c23;
    public final double c31;

    public Accuracy(CoordType type, double s1, double s2, double s3,
                    double c12, double c23, double c31) {
        this.type = type;
        this.s1 = s1;
        this.s2 = s2;
        this.s3 = s3;
        this.c12 = c12;
        this.c23 = c23;
        this.c31 = c31;
    }

    @Override
    public String toString() {
        String prefix = type == CoordType.ECEF ? "ECEF" : "ENU";
        return String.format("%s: %8.4f %8.4f %8.4f %8.4f %8.4f %8.4f",
                prefix, s1, s2, s3, c12, c23, c31);
    }
}