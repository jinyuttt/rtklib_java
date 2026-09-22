package org.rtklib.java.orbit;

/**
 * 时间与日期转换工具，逐行移植自 python-sgp4 的 ext.py / functions.py / propagation.py。
 *
 * <p>包含：</p>
 * <ul>
 *   <li>{@link #jday} - 公历日期 → 儒略日（与 sgp4.ext.jday 一致，返回单个 double）</li>
 *   <li>{@link #days2mdhms} - 年内天数 → 月、日、时、分、秒（与 sgp4.functions.days2mdhms 一致）</li>
 *   <li>{@link #invjday} - 儒略日 → 公历日期（与 sgp4.ext.invjday 一致）</li>
 *   <li>{@link #gstime} - 儒略日 → 格林尼治恒星时（与 sgp4.propagation.gstime 一致）</li>
 * </ul>
 */
public final class Sgp4Time {

    private Sgp4Time() {}

    /**
     * 公历日期 → 儒略日。
     * 逐行移植自 sgp4/ext.py 的 jday()，返回单个 double。
     */
    public static double jday(int year, int mon, int day, int hr, int minute, double sec) {
        // Python ext.py jday:
        //   367.0 * year
        //   - 7.0 * (year + ((mon + 9.0) // 12.0)) * 0.25 // 1.0
        //   + 275.0 * mon // 9.0
        //   + day + 1721013.5
        //   + ((sec / 60.0 + minute) / 60.0 + hr) / 24.0
        // 注意 Python 的 // 是向下取整（floor division），对正数等价于 Math.floor。
        // 关键：(mon + 9.0) // 12.0 先 floor，再加到 year 上；最后 7.0 * (...) * 0.25 再 // 1.0。
        double innerFloor = Sgp4Math.floorToLong((mon + 9.0) / 12.0);   // (mon+9)//12
        double midTerm = 7.0 * (year + innerFloor) * 0.25;               // 7*(year+inner)*0.25
        double outerFloor = Sgp4Math.floorToLong(midTerm);               // ... // 1.0
        double monTerm = Sgp4Math.floorToLong(275.0 * mon / 9.0);        // 275*mon//9
        return (367.0 * year
                - outerFloor
                + monTerm
                + day
                + 1721013.5
                + ((sec / 60.0 + minute) / 60.0 + hr) / 24.0);
    }

    /**
     * 年内天数 → 月、日、时、分、秒。
     * 逐行移植自 sgp4/functions.py 的 days2mdhms(year, days)。
     * 默认对秒做 6 位小数四舍五入（与 Python round 行为一致）。
     *
     * @return 长度 5 的数组：[month(1-12), day(1-31), hour(0-23), minute(0-59), second(0-59.999)]
     */
    public static double[] days2mdhms(int year, double days) {
        // 第二个参数 round_to_microsecond 在 python-sgp4 默认为 6，对应 round 到 6 位小数。
        // 此处直接使用 6 位小数四舍五入以与 Python round() 等价。
        double second = days * 86400.0;
        second = roundToMicros(second);

        // Python: minute, second = divmod(second, 60.0)
        // divmod(a, b) 返回 (floor(a/b), a - b*floor(a/b))
        double minuteD = Math.floor(second / 60.0);
        second = second - 60.0 * minuteD;
        second = roundToMicros(second);

        int minute = (int) minuteD;
        // Python: hour, minute = divmod(minute, 60)
        int hourRaw = (int) Math.floor(minute / 60.0); // minute 是 int，正数
        minute = minute - 60 * hourRaw;
        // Python: day_of_year, hour = divmod(hour, 24)
        // 注意 Python 这里 hour 已经是 int。
        int hourTemp = hourRaw;
        int dayOfYear = (int) Math.floor((double) hourTemp / 24.0);
        hourTemp = hourTemp - 24 * dayOfYear;
        int hour = hourTemp;

        boolean isLeap = (year % 400 == 0) || (year % 4 == 0 && year % 100 != 0);
        int[] md = dayOfYearToMonthDay(dayOfYear, isLeap);
        int month = md[0];
        int day = md[1];
        if (month == 13) {  // behave like the original in case of overflow
            month = 12;
            day += 31;
        }
        return new double[]{month, day, hour, minute, second};
    }

    /**
     * 儒略日 → 公历日期。
     * 逐行移植自 sgp4/ext.py 的 invjday()。
     *
     * @return 长度 6 的数组：[year, month, day, hour, minute, second]
     */
    public static double[] invjday(double jd) {
        double temp = jd - 2415019.5;
        double tu = temp / 365.25;
        int year = 1900 + (int) Math.floor(tu);
        long leapyrs = (long) Math.floor((year - 1901) * 0.25);

        // optional nudge by 8.64x10-7 sec to get even outputs
        double days = temp - ((year - 1900) * 365.0 + leapyrs) + 0.00000000001;

        if (days < 1.0) {
            year = year - 1;
            leapyrs = (long) Math.floor((year - 1901) * 0.25);
            days = temp - ((year - 1900) * 365.0 + leapyrs);
        }

        double[] mdhms = days2mdhms(year, days);
        int mon = (int) mdhms[0];
        int day = (int) mdhms[1];
        int hr = (int) mdhms[2];
        int minute = (int) mdhms[3];
        double sec = mdhms[4];
        sec = sec - 0.00000086400;
        return new double[]{year, mon, day, hr, minute, sec};
    }

    /**
     * 格林尼治恒星时。
     * 逐行移植自 sgp4/propagation.py 的 gstime(jdut1)。
     *
     * @param jdut1 儒略日 (UT1)
     * @return GST，0 ~ 2PI rad
     */
    public static double gstime(double jdut1) {
        double tut1 = (jdut1 - 2451545.0) / 36525.0;
        double temp = -6.2e-6 * tut1 * tut1 * tut1
                + 0.093104 * tut1 * tut1
                + (876600.0 * 3600 + 8640184.812866) * tut1
                + 67310.54841;   // sec
        // 360/86400 = 1/240, to deg, to rad
        temp = Sgp4Math.mod(temp * Sgp4Constants.DEG2RAD / 240.0, Sgp4Constants.TWOPI);

        if (temp < 0.0) {
            temp += Sgp4Constants.TWOPI;
        }
        return temp;
    }

    // ---------- functions.py 内部辅助 ----------

    /**
     * 等价于 Python round(x, 6) 的"四舍六入五成双"行为。
     * 这里取最简单的等价实现：Math.rint(x * 1e6) / 1e6。
     * round() 在 Python 3 中是 banker's rounding，与 Math.rint 一致。
     */
    private static double roundToMicros(double x) {
        return Math.rint(x * 1.0e6) / 1.0e6;
    }

    /**
     * 实现 functions.py 中 _day_of_year_to_month_day 的核心逻辑。
     *
     * <pre>
     * february_bump = (2 - is_leap) * (day_of_year >= 60 + is_leap)
     * august = day_of_year >= 215
     * month, day = divmod(2 * (day_of_year - 1 + 30 * august + february_bump), 61)
     * month += 1 - august
     * day //= 2
     * day += 1
     * </pre>
     */
    private static int[] dayOfYearToMonthDay(int dayOfYear, boolean isLeap) {
        int isLeapInt = isLeap ? 1 : 0;
        int februaryBump = (2 - isLeapInt) * (dayOfYear >= 60 + isLeapInt ? 1 : 0);
        int august = dayOfYear >= 215 ? 1 : 0;

        int dividend = 2 * (dayOfYear - 1 + 30 * august + februaryBump);
        int month = dividend / 61;            // Python divmod → 商 (向零截断对正数等价 floor)
        int day = dividend - 61 * month;      // 余数
        month += 1 - august;
        day = day / 2;                         // Python 整数 //
        day += 1;
        return new int[]{month, day};
    }
}
