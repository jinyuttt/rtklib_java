package org.rtklib.java.orbit;


/**
 * 单条 TLE (Two-Line Element) 数据容器。
 *
 * <p>持有解析前的原始 TLE 行字符串和卫星编号，以及解析并执行 sgp4init 后
 * 得到的 SGP4 卫星记录（{@link Satellite}）。{@link TleParser#tleRead} 在
 * 读取文件后会自动调用 twoline2rv + sgp4init 填充 {@link #satrec}。</p>
 *
 * <p>对应 python-sgp4 中 io.twoline2rv 返回的 Satellite 对象及其附属字段
 * (line1/line2/satnum_str/classification/intldesg/epochyr/epochdays/...)。</p>
 */
public class TleData {

    /** 5 字符的卫星编号字符串（TLE 第 1 行第 3-7 列）。 */
    public String satno;

    /** 第 1 行原始字符串（去除首尾空白后保留）。 */
    public String line1;

    /** 第 2 行原始字符串（去除首尾空白后保留）。 */
    public String line2;

    /** 可选的名称行（如 data/tle.txt 第一行 "GPS BIIR-5 (PRN 22)"），无则为 null。 */
    public String name;

    /**
     * SGP4 卫星记录（含 sgp4init 后的全部派生系数）。
     * 字段定义对应 python-sgp4 的 Satellite (sgp4/model.py) 与 sgp4io.cpp 的 satrec。
     */
    public Satellite satrec;

    public TleData() {
        this.satno = "";
        this.line1 = "";
        this.line2 = "";
        this.name = null;
        this.satrec = null;
    }
}
