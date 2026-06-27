package org.rtklib.java.data;

/**
 * GPS时间结构体。
 *
 * <p>对应RTKLIB gtime_t。存储GPS时间（GPST），
 * 由整秒部分 time 和小数秒部分 sec 组成。</p>
 *
 * <h3>时间系统说明</h3>
 * <ul>
 *   <li>GPST：GPS时间，起算历元 1980-01-06 00:00:00</li>
 *   <li>BDT：北斗时间，BDT = GPST - 14s（14秒偏移）</li>
 *   <li>UTC：协调世界时，UTC = GPST - 闰秒数（当前18秒）</li>
 * </ul>
 *
 * <p><b>注意</b>：GTime 内部统一以 GPST 存储，
 * 不同时间系统的转换由 {@link org.rtklib.java.time.TimeSystem} 处理。</p>
 */
public class GTime {
    /** 整秒部分（自1970-01-01 00:00:00 UTC以来的秒数） */
    public long time;

    /** 小数秒部分（0.0 <= sec < 1.0） */
    public double sec;

    /**
     * 默认构造函数，初始化为零时刻。
     */
    public GTime() {
        this.time = 0;
        this.sec = 0.0;
    }

    /**
     * 指定整秒和小数秒的构造函数。
     *
     * @param time 整秒部分
     * @param sec  小数秒部分
     */
    public GTime(long time, double sec) {
        this.time = time;
        this.sec = sec;
    }

    /**
     * 拷贝构造函数。
     *
     * @param other 源GTime对象
     */
    public GTime(GTime other) {
        this.time = other.time;
        this.sec = other.sec;
    }

    /**
     * 判断两个GTime是否相等（小数秒容差1e-12）。
     *
     * @param other 另一个GTime
     * @return true=相等
     */
    public boolean equals(GTime other) {
        return this.time == other.time && Math.abs(this.sec - other.sec) < 1e-12;
    }

    /**
     * 比较两个GTime的大小。
     *
     * @param other 另一个GTime
     * @return -1: this&lt;other, 0:相等, 1: this&gt;other
     */
    public int compareTo(GTime other) {
        if (this.time < other.time) return -1;
        if (this.time > other.time) return 1;
        if (this.sec < other.sec) return -1;
        if (this.sec > other.sec) return 1;
        return 0;
    }

    /**
     * 创建零时刻GTime。
     *
     * @return 零时刻 (time=0, sec=0.0)
     */
    public static GTime zero() {
        return new GTime(0, 0.0);
    }
}