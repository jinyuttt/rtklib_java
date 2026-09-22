package org.rtklib.java.orbit;


import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * TLE 文件读取与两行根解析器，逐行移植自 python-sgp4 的 io.py。
 *
 * <p>移植自 io.py 的 twoline2rv() 函数。支持两种文件格式：</p>
 * <ul>
 *   <li>Vallado 标准 2 行格式（如 sgp4-ver.tle）：仅含 line1 / line2</li>
 *   <li>带名称行的 3 行格式（如 data/tle.txt, data/tle-bds.txt）：
 *       第一行是名称，随后是 line1 / line2</li>
 * </ul>
 *
 * <p>解析完成后会自动调用 {@link Sgp4Propagation#sgp4init} 完成初始化，
 * 将结果存入 {@link TleData#satrec}。</p>
 */
public final class TleParser {

    private TleParser() {}

    /**
     * 读取 TLE 文件并解析为 {@link Tle} 集合。
     *
     * @param path TLE 文件路径
     * @param tleData 输出容器（in-place 修改其 {@link Tle#n} 与 {@link Tle#data}）
     * @return true=读取并解析成功（至少 1 条），false=失败
     */
    public static boolean tleRead(String path, Tle tleData) {
        List<String> lines;
        try {
            lines = Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8);
        } catch (IOException | NullPointerException e) {
            return false;
        }

        List<TleData> result = new ArrayList<>();
        String pendingName = null;

        for (int i = 0; i < lines.size(); i++) {
            String raw = lines.get(i);
            if (raw == null) continue;
            String line = raw.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (isLine1(line)) {
                // 找下一行作为 line2
                if (i + 1 < lines.size()) {
                    String next = lines.get(i + 1).trim();
                    if (isLine2(next)) {
                        TleData d = twoline2rv(line, next, Sgp4Constants.WGS72, 'i');
                        if (d != null) {
                            if (pendingName != null) {
                                d.name = pendingName;
                                pendingName = null;
                            }
                            result.add(d);
                        }
                        i++;  // 跳过 line2
                    }
                }
            } else if (isLine2(line)) {
                // 单独的 line2 没有 line1 配对，跳过
                continue;
            } else {
                // 名称行
                pendingName = line;
            }
        }

        if (result.isEmpty()) {
            tleData.n = 0;
            tleData.data = new TleData[0];
            return false;
        }
        tleData.n = result.size();
        tleData.data = result.toArray(new TleData[0]);
        return true;
    }

    /**
     * 解析两行 TLE 字符串，构造 {@link TleData} 并执行 sgp4init。
     * 逐行移植自 io.py 的 twoline2rv()。
     *
     * @param longstr1 第 1 行
     * @param longstr2 第 2 行
     * @param whichconst 重力常数集合
     * @param opsmode 操作模式：'a'=AFSPC, 'i'=improved
     * @return 已初始化的 TleData，失败返回 null
     */
    public static TleData twoline2rv(String longstr1, String longstr2,
                                     Sgp4Constants.Gravity whichconst, char opsmode) {
        // python-sgp4 中先 rstrip()
        String line1 = rstrip(longstr1);
        String line2 = rstrip(longstr2);

        if (!isLine1Valid(line1)) {
            return null;
        }
        if (!isLine2Valid(line2)) {
            return null;
        }

        TleData data = new TleData();
        data.line1 = line1;
        data.line2 = line2;

        // 解析 line1
        // line[2:7]=satnum_str
        String satnum_str = line1.substring(2, 7);
        // line[7]=classification (默认 'U')
        String classification = String.valueOf(line1.charAt(7));
        // line[9:17]=intldesg (rstrip)
        String intldesg = rstrip(line1.substring(9, 17));
        // line[18:20]=two_digit_year
        int two_digit_year = Integer.parseInt(line1.substring(18, 20).trim());
        // line[20:32]=epochdays
        double epochdays = Double.parseDouble(line1.substring(20, 32));
        // line[33:43]=ndot
        double ndot = Double.parseDouble(line1.substring(33, 43));
        // line[44] + '.' + line[45:50]=nddot
        double nddot = Double.parseDouble(line1.charAt(44) + "." + line1.substring(45, 50));
        int nexp = Integer.parseInt(line1.substring(50, 52).trim());
        // line[53] + '.' + line[54:59]=bstar
        double bstar = Double.parseDouble(line1.charAt(53) + "." + line1.substring(54, 59));
        int ibexp = Integer.parseInt(line1.substring(59, 61).trim());
        // line[62]=ephtype
        char ephtype = line1.charAt(62);
        // line[64:68]=elnum
        int elnum = Integer.parseInt(line1.substring(64, 68).trim());

        // 校验 line1 与 line2 的 satnum 一致
        String satnum2 = line2.substring(2, 7);
        if (!satnum_str.equals(satnum2)) {
            return null;
        }

        // 解析 line2
        // line[8:16]=inclo (deg)
        double inclo = Double.parseDouble(line2.substring(8, 16));
        // line[17:25]=nodeo (deg)
        double nodeo = Double.parseDouble(line2.substring(17, 25));
        // line[26:33]=ecco: '0.' + (前导空格替换为 '0')
        String eccoStr = "0." + line2.substring(26, 33).replace(' ', '0');
        double ecco = Double.parseDouble(eccoStr);
        // line[34:42]=argpo (deg)
        double argpo = Double.parseDouble(line2.substring(34, 42));
        // line[43:51]=mo (deg)
        double mo = Double.parseDouble(line2.substring(43, 51));
        // line[52:63]=no_kozai (rev/day)
        double no_kozai = Double.parseDouble(line2.substring(52, 63));
        // line[63:68]=revnum
        String revnum = line2.substring(63, 68);

        // ---- find no, ndot, nddot ----
        double xpdotp = Sgp4Constants.XPDOTP;
        no_kozai = no_kozai / xpdotp;                       // rad/min
        nddot = nddot * Math.pow(10.0, nexp);
        bstar = bstar * Math.pow(10.0, ibexp);

        // ---- convert to sgp4 units ----
        ndot = ndot / (xpdotp * 1440.0);
        nddot = nddot / (xpdotp * 1440.0 * 1440.0);

        // ---- find standard orbital elements ----
        double deg2rad = Sgp4Constants.DEG2RAD;
        inclo = inclo * deg2rad;
        nodeo = nodeo * deg2rad;
        argpo = argpo * deg2rad;
        mo = mo * deg2rad;

        // ---- find sgp4epoch time ----
        int year;
        if (two_digit_year < 57) {
            year = two_digit_year + 2000;
        } else {
            year = two_digit_year + 1900;
        }

        double[] mdhms = Sgp4Time.days2mdhms(year, epochdays);
        int mon = (int) mdhms[0];
        int day = (int) mdhms[1];
        int hr = (int) mdhms[2];
        int minute = (int) mdhms[3];
        double sec = mdhms[4];

        double jdsatepoch = Sgp4Time.jday(year, mon, day, hr, minute, sec);

        // 构造 Satellite 并 sgp4init
        Satellite satrec = new Satellite();
        Sgp4Propagation.sgp4init(whichconst, opsmode, satnum_str,
                jdsatepoch - 2433281.5,
                bstar, ndot, nddot, ecco, argpo, inclo, mo, no_kozai, nodeo, satrec);

        data.satno = satnum_str;
        data.satrec = satrec;
        return data;
    }

    // ===================== 行格式校验 =====================

    /**
     * 简单判断是否为 TLE line 1：长度 >= 64 且以 "1 " 开头。
     */
    private static boolean isLine1(String line) {
        return line != null && line.length() >= 64 && line.startsWith("1 ");
    }

    private static boolean isLine2(String line) {
        return line != null && line.length() >= 68 && line.startsWith("2 ");
    }

    /**
     * 完整校验 line 1 格式，对应 io.py 中 twoline2rv 的格式检查。
     */
    private static boolean isLine1Valid(String line) {
        return line != null
                && line.length() >= 64
                && line.startsWith("1 ")
                && line.charAt(8) == ' '
                && line.charAt(23) == '.'
                && line.charAt(32) == ' '
                && line.charAt(34) == '.'
                && line.charAt(43) == ' '
                && line.charAt(52) == ' '
                && line.charAt(61) == ' '
                && line.charAt(63) == ' ';
    }

    private static boolean isLine2Valid(String line) {
        return line != null
                && line.length() >= 68
                && line.startsWith("2 ")
                && line.charAt(7) == ' '
                && line.charAt(11) == '.'
                && line.charAt(16) == ' '
                && line.charAt(20) == '.'
                && line.charAt(25) == ' '
                && line.charAt(33) == ' '
                && line.charAt(37) == '.'
                && line.charAt(42) == ' '
                && line.charAt(46) == '.'
                && line.charAt(51) == ' ';
    }

    /**
     * 等价于 Python str.rstrip()：去除尾部空白（不去除前导）。
     */
    private static String rstrip(String s) {
        if (s == null) return "";
        int end = s.length();
        while (end > 0 && Character.isWhitespace(s.charAt(end - 1))) {
            end--;
        }
        return s.substring(0, end);
    }
}
