package org.rtklib.java.stream.nmea;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

/**
 * NMEA GGA 语句解析器。
 *
 * <p>面向流式输入设计：任何格式不合法的语句一律返回 {@link Optional#empty()}，
 * 不抛异常。上游数据流中混有坏行是常态，解析失败不应中断整条链路。</p>
 *
 * <p>支持的语句形态：任意 talker ID 的 GGA，如 {@code $GPGGA}、{@code $GNGGA}、
 * {@code $GLGGA}、{@code $GBGGA}、{@code $BDGGA} 等。校验和默认必须匹配，
 * 无校验和的语句按不合规处理。</p>
 *
 * @see NmeaGga
 */
public final class NmeaGgaParser {

    private static final Logger log = LoggerFactory.getLogger(NmeaGgaParser.class);

    private NmeaGgaParser() {
    }

    /**
     * 判断一行文本是否为 GGA 语句（仅看语句标识，不校验内容与校验和）。
     *
     * @param line 单行文本，允许带行尾换行符
     */
    public static boolean isGga(String line) {
        if (line == null) {
            return false;
        }
        String s = stripLineEnding(line);
        return s.length() >= 7 && s.charAt(0) == '$' && s.regionMatches(true, 3, "GGA", 0, 3);
    }

    /**
     * 解析一行 GGA 语句。
     *
     * @param line 单行文本，允许带行尾换行符
     * @return 解析结果；语句非法、校验和错误或字段缺失时为空
     */
    public static Optional<NmeaGga> parse(String line) {
        if (!isGga(line)) {
            return Optional.empty();
        }
        String s = stripLineEnding(line);

        int star = s.lastIndexOf('*');
        if (star < 0 || star + 3 > s.length()) {
            log.debug("GGA 缺少校验和: {}", s);
            return Optional.empty();
        }
        if (!verifyChecksum(s)) {
            log.debug("GGA 校验和错误: {}", s);
            return Optional.empty();
        }

        String body = s.substring(1, star);
        String[] f = body.split(",", -1);
        if (f.length < 10) {
            log.debug("GGA 字段不足({}): {}", f.length, s);
            return Optional.empty();
        }

        Optional<Double> time = parseUtcSeconds(f[1]);
        Optional<Double> lat = parseAngle(f[2], f[3], 'N', 'S');
        Optional<Double> lon = parseAngle(f[4], f[5], 'E', 'W');
        if (time.isEmpty() || lat.isEmpty() || lon.isEmpty()) {
            return Optional.empty();
        }

        int fixQuality = parseInt(f[6]).orElse(0);
        int satellites = parseInt(f[7]).orElse(0);
        double hdop = parseDouble(f[8]).orElse(Double.NaN);
        double mslHeight = parseDouble(f[9]).orElse(0.0);
        double geoidSeparation = f.length > 11 ? parseDouble(f[11]).orElse(0.0) : 0.0;
        double differentialAge = f.length > 13 ? parseDouble(f[13]).orElse(0.0) : 0.0;
        int referenceStationId = f.length > 14 ? parseInt(f[14]).orElse(0) : 0;

        return Optional.of(new NmeaGga(time.get(), lat.get(), lon.get(), fixQuality, satellites,
                hdop, mslHeight, geoidSeparation, differentialAge, referenceStationId));
    }

    /**
     * 校验 NMEA 语句的 XOR 校验和。
     *
     * @param sentence 完整语句，形如 {@code $GPGGA,...,*5B}
     */
    public static boolean verifyChecksum(String sentence) {
        String s = stripLineEnding(sentence);
        int star = s.lastIndexOf('*');
        if (s.isEmpty() || s.charAt(0) != '$' || star < 0 || star + 3 > s.length()) {
            return false;
        }
        int expected;
        try {
            expected = Integer.parseInt(s.substring(star + 1, star + 3), 16);
        } catch (NumberFormatException e) {
            return false;
        }
        int actual = 0;
        for (int i = 1; i < star; i++) {
            actual ^= s.charAt(i);
        }
        return actual == expected;
    }

    /**
     * 解析 {@code hhmmss.ss} 为当日秒数。
     */
    private static Optional<Double> parseUtcSeconds(String field) {
        Optional<Double> value = parseDouble(field);
        if (value.isEmpty()) {
            return Optional.empty();
        }
        double raw = value.get();
        double hour = Math.floor(raw / 10000.0);
        double minute = Math.floor((raw - hour * 10000.0) / 100.0);
        double second = raw - hour * 10000.0 - minute * 100.0;
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59 || second < 0 || second >= 61) {
            return Optional.empty();
        }
        return Optional.of(hour * 3600.0 + minute * 60.0 + second);
    }

    /**
     * 解析 {@code ddmm.mmmm} 形式的度分字段并施加半球符号。
     */
    private static Optional<Double> parseAngle(String value, String hemisphere,
                                               char positive, char negative) {
        Optional<Double> raw = parseDouble(value);
        if (raw.isEmpty() || hemisphere == null || hemisphere.isEmpty()) {
            return Optional.empty();
        }
        char hemi = Character.toUpperCase(hemisphere.charAt(0));
        if (hemi != positive && hemi != negative) {
            return Optional.empty();
        }
        double packed = raw.get();
        if (packed < 0) {
            return Optional.empty();
        }
        double degrees = Math.floor(packed / 100.0);
        double minutes = packed - degrees * 100.0;
        if (minutes >= 60.0) {
            return Optional.empty();
        }
        double result = degrees + minutes / 60.0;
        return Optional.of(hemi == negative ? -result : result);
    }

    private static Optional<Double> parseDouble(String field) {
        if (field == null || field.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Double.parseDouble(field));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }

    private static Optional<Integer> parseInt(String field) {
        Optional<Double> value = parseDouble(field);
        return value.map(v -> (int) Math.round(v));
    }

    private static String stripLineEnding(String line) {
        String s = line;
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == '\r' || s.charAt(end - 1) == '\n')) {
            end--;
        }
        return s.substring(0, end).trim();
    }
}