package org.rtklib.java.networkvrs.sync;

import org.rtklib.java.data.Nav;
import org.rtklib.java.ephemeris.ClkReader;
import org.rtklib.java.ephemeris.Sp3Reader;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 事后精密产品装载：把 SP3 精密轨道与 CLK 精密钟差装入 {@link Nav}，
 * 使网解可以 {@code EPHOPT_PREC} 计算卫星位置/钟差。
 *
 * <p>装载后 {@code nav.eph}（广播星历）保持不变，两种星历共存；
 * 是否使用精密星历由 {@link org.rtklib.java.networkvrs.config.NetworkConfig#ephemerisOption} 决定。</p>
 */
public final class PreciseEphemerisLoader {

    private PreciseEphemerisLoader() {
    }

    /**
     * @param nav 目标导航数据（通常已装载广播星历）
     * @param sp3 SP3 精密轨道文件（.sp3 / .SP3，不支持 .gz 压缩包）
     * @param clk CLK 精密钟差文件；为 {@code null} 时只装载轨道
     * @return 入参 {@code nav}（便于链式调用）
     */
    public static Nav load(Nav nav, Path sp3, Path clk) {
        if (nav == null) {
            throw new IllegalArgumentException("nav 不能为 null");
        }
        if (sp3 == null || !Files.exists(sp3)) {
            throw new IllegalArgumentException("缺少 SP3 精密轨道文件: " + sp3);
        }
        Sp3Reader.readsp3(sp3.toString(), nav, 0);
        if (nav.ne < 1) {
            throw new IllegalArgumentException("SP3 文件无可用的精密轨道: " + sp3);
        }
        if (clk != null) {
            if (!Files.exists(clk)) {
                throw new IllegalArgumentException("缺少 CLK 精密钟差文件: " + clk);
            }
            ClkReader.readclk(clk.toString(), nav);
        }
        return nav;
    }
}