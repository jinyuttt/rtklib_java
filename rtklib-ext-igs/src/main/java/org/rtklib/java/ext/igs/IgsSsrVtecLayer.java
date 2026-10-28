package org.rtklib.java.ext.igs;

/**
 * IGS SSR IM201 电离层 VTEC 球谐模型中的单个薄层。
 *
 * <p>球谐展开为 {@code VTEC = Σ Σ (C_nm cos(mλ_S) + S_nm sin(mλ_S)) P_nm(sin φ_PP)}，
 * 系数已换算为 TECU（原始 int16 × 0.005）。</p>
 *
 * <p>系数数组按度 n、次 m 索引，未参与展开的项保持 0；C 覆盖 m=0..M，S 覆盖 m=1..M。</p>
 */
public class IgsSsrVtecLayer {

    /** 层高（km，球面地球模型之上） */
    public final double heightKm;
    /** 球谐阶 N（1~16） */
    public final int degree;
    /** 球谐次 M（1~16，且 M ≤ N） */
    public final int order;
    /** 余弦系数 [n][m]，TECU */
    public final double[][] cnm;
    /** 正弦系数 [n][m]，TECU；m=0 无定义，恒为 0 */
    public final double[][] snm;

    /**
     * @param heightKm 层高（km）
     * @param degree 阶 N
     * @param order 次 M
     * @param cnm 余弦系数，尺寸至少 (N+1)×(N+1)
     * @param snm 正弦系数，尺寸至少 (N+1)×(N+1)
     */
    public IgsSsrVtecLayer(double heightKm, int degree, int order, double[][] cnm, double[][] snm) {
        this.heightKm = heightKm;
        this.degree = degree;
        this.order = order;
        this.cnm = cnm;
        this.snm = snm;
    }

    /** 余弦系数 C_nm（TECU），超界返回 0 */
    public double c(int n, int m) {
        return n >= 0 && n < cnm.length && m >= 0 && m < cnm[n].length ? cnm[n][m] : 0.0;
    }

    /** 正弦系数 S_nm（TECU），超界返回 0 */
    public double s(int n, int m) {
        return n >= 0 && n < snm.length && m >= 0 && m < snm[n].length ? snm[n][m] : 0.0;
    }

    @Override
    public String toString() {
        return "IM201 layer [height=" + heightKm + " km, N=" + degree + ", M=" + order
                + ", C00=" + c(0, 0) + "]";
    }
}
