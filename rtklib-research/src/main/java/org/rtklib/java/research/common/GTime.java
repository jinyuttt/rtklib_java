package org.rtklib.java.research.common;

import java.io.Serializable;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.LocalDateTime;

/**
 * GPS时间结构体（research模块自有）。
 *
 * <p>内部统一以GPST存储，由整秒部分time和小数秒部分sec组成。</p>
 *
 * <h3>时间系统</h3>
 * <ul>
 *   <li>GPST：GPS时间，起算历元 1980-01-06 00:00:00</li>
 *   <li>BDT：北斗时间，BDT = GPST - 14s</li>
 *   <li>UTC：协调世界时，UTC = GPST - 闰秒数（当前18秒）</li>
 * </ul>
 */
public class GTime implements Serializable, Comparable<GTime> {
    private static final long serialVersionUID = 1L;

    public long time;
    public double sec;

    public GTime() {
        this.time = 0;
        this.sec = 0.0;
    }

    public GTime(long time, double sec) {
        this.time = time;
        this.sec = sec;
    }

    public GTime(GTime other) {
        this.time = other.time;
        this.sec = other.sec;
    }

    public static GTime zero() {
        return new GTime(0, 0.0);
    }

    public double toSeconds() {
        return (double) this.time + this.sec;
    }

    public GTime addSeconds(double dt) {
        double total = this.toSeconds() + dt;
        long t = (long) Math.floor(total);
        double s = total - t;
        return new GTime(t, s);
    }

    public double diffSeconds(GTime other) {
        return this.toSeconds() - other.toSeconds();
    }

    @Override
    public int compareTo(GTime other) {
        if (this.time < other.time) return -1;
        if (this.time > other.time) return 1;
        if (this.sec < other.sec) return -1;
        if (this.sec > other.sec) return 1;
        return 0;
    }

    public boolean equals(GTime other) {
        return this.time == other.time && Math.abs(this.sec - other.sec) < 1e-12;
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (!(obj instanceof GTime)) return false;
        return equals((GTime) obj);
    }

    @Override
    public int hashCode() {
        return Long.hashCode(time) * 31 + Double.hashCode(sec);
    }

    @Override
    public String toString() {
        Instant instant = Instant.ofEpochSecond(this.time, (long) (this.sec * 1e9));
        LocalDateTime ldt = LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
        return String.format("%04d-%02d-%02d %02d:%02d:%09.6f GPST",
                ldt.getYear(), ldt.getMonthValue(), ldt.getDayOfMonth(),
                ldt.getHour(), ldt.getMinute(), ldt.getSecond() + ldt.getNano() / 1e9);
    }
}