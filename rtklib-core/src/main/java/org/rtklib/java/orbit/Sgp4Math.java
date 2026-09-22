package org.rtklib.java.orbit;

/**
 * Python 行为兼容的数学工具方法。
 *
 * <p>Python 与 Java 在取模、整除和取整上行为不同，移植 python-sgp4 时
 * 必须使用本类工具方法以保证逐行移植结果与 Python 一致：</p>
 * <ul>
 *   <li>Python % 对负数返回非负余数：-1.5 % 6.283 = 4.783；Java % 返回 -1.5</li>
 *   <li>Python // 是向下取整除法；Java 整数除法向零截断</li>
 *   <li>Python math.floor 对正数等价 (long) Math.floor</li>
 * </ul>
 */
public final class Sgp4Math {

    private Sgp4Math() {}

    /**
     * Python 风格的浮点取模，结果总是与除数同号且 |result| < |divisor|。
     * 例如 mod(-1.5, 6.283) = 4.783。
     */
    public static double mod(double x, double y) {
        return x - y * Math.floor(x / y);
    }

    /**
     * Python 风格的浮点向下取整除法：x // y → 等价 floor(x / y)。
     */
    public static double floorDivDouble(double x, double y) {
        return Math.floor(x / y);
    }

    /**
     * Python 风格的整型向下取整：int(x // 1.0)。
     */
    public static long floorToLong(double x) {
        return (long) Math.floor(x);
    }
}
