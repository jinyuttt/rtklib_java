package org.rtklib.java.networkvrs.interp;

import java.util.List;

/**
 * [JAVA-ONLY 原创] 改正量空间插值接口。
 *
 * <p>输入为各基准站在同一卫星上的改正量（标量），输出流动站位置的插值值。
 * 档 1 提供 {@link IdwInterpolator}；档 2 起可替换为 FKP/MAC 实现。</p>
 *
 * <p><b>归属声明</b>：本项目原创实现，未移植任何外部开源项目代码。</p>
 */
@FunctionalInterface
public interface CorrectionInterpolator {

    /**
     * @param targetPos   流动站大地坐标 [lat(rad), lon(rad), height(m)]
     * @param corrections 各站改正量（至少一个）
     * @return 插值后的改正量
     */
    double interpolate(double[] targetPos, List<StationCorrection> corrections);
}