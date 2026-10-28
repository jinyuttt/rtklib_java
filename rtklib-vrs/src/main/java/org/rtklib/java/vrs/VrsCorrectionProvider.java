package org.rtklib.java.vrs;

import org.rtklib.java.data.GTime;

/**
 * 外部改正量提供者：用网络插值等外部方法给出的改正量替代模型计算。
 *
 * <p>返回值语义与 {@link VrsCorrection} 一致，均为相对基准站的增量（米）：
 * {@code [0]} 色散项（电离层，加到伪距、从载波中减去），
 * {@code [1]} 非色散项（对流层），{@code [2]} 轨道残差。
 * 返回 {@code null} 表示该卫星不使用外部改正，回退到 {@link VrsCorrection} 的模型路径。</p>
 */
@FunctionalInterface
public interface VrsCorrectionProvider {

    /**
     * @param time 观测历元时间
     * @param sat  卫星号
     * @return 相对基准站的改正量 {@code [dIon, dTrop, dOrbit]}（米）；null 表示回退模型
     */
    double[] correction(GTime time, int sat);
}
