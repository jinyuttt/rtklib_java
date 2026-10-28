package org.rtklib.java.stream.caster;

import java.util.Locale;

/**
 * NTRIP 挂载点描述。
 *
 * <p>一个挂载点代表 Caster 对外提供的一路数据流，其字段与 Sourcetable 的
 * {@code STR} 记录一一对应，可直接序列化为对外播发的挂载点列表。</p>
 *
 * <p>除 {@link #getName() 名称} 外所有字段均可配置，默认值对应一个
 * 播发 RTCM 3.2 网络解、支持 GPS/GLONASS/Galileo/BDS 的挂载点。</p>
 *
 * @see NtripCasterConfig
 */
public class MountPoint {

    private final String name;
    private String identifier;
    private String format = "RTCM 3.2";
    private String formatDetails = "";
    private int carrier = 2;
    private String navSystem = "GPS+GLO+GAL+BDS";
    private String network = "";
    private String country = "";
    private double latitude;
    private double longitude;
    private boolean nmea = true;
    private int solution = 1;
    private String generator = "rtklib-java";
    private String compression = "none";
    private String authentication = "B";
    private boolean fee = false;
    private int bitrate = 0;
    private String misc = "";

    /**
     * 创建挂载点。
     *
     * @param name 挂载点名称，即客户端请求 URL 中的路径，如 {@code "VRS01"}
     */
    public MountPoint(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("挂载点名称不能为空");
        }
        if (name.indexOf('/') >= 0 || name.indexOf(';') >= 0) {
            throw new IllegalArgumentException("挂载点名称不能包含 '/' 或 ';': " + name);
        }
        this.name = name;
        this.identifier = name;
    }

    /** 获取挂载点名称。 */
    public String getName() { return name; }

    /** 获取挂载点标识符，默认为名称。 */
    public String getIdentifier() { return identifier; }
    /** 设置挂载点标识符，用于 Sourcetable 中区分同名数据源。 */
    public void setIdentifier(String identifier) { this.identifier = identifier; }

    /** 获取数据格式，如 "RTCM 3.2"。 */
    public String getFormat() { return format; }
    /** 设置数据格式。 */
    public void setFormat(String format) { this.format = format; }

    /** 获取格式明细，通常为消息类型列表，如 "1005(1),1077(1)"。 */
    public String getFormatDetails() { return formatDetails; }
    /** 设置格式明细。 */
    public void setFormatDetails(String formatDetails) { this.formatDetails = formatDetails; }

    /** 获取载波相位支持等级：0 无、1 L1、2 L1+L2。 */
    public int getCarrier() { return carrier; }
    /** 设置载波相位支持等级。 */
    public void setCarrier(int carrier) { this.carrier = carrier; }

    /** 获取支持的导航系统组合，如 "GPS+GLO+GAL+BDS"。 */
    public String getNavSystem() { return navSystem; }
    /** 设置支持的导航系统组合。 */
    public void setNavSystem(String navSystem) { this.navSystem = navSystem; }

    /** 获取所属网络名称。 */
    public String getNetwork() { return network; }
    /** 设置所属网络名称。 */
    public void setNetwork(String network) { this.network = network; }

    /** 获取国家/地区三字符代码。 */
    public String getCountry() { return country; }
    /** 设置国家/地区三字符代码。 */
    public void setCountry(String country) { this.country = country; }

    /** 获取挂载点参考纬度（度）。 */
    public double getLatitude() { return latitude; }
    /** 设置挂载点参考纬度（度）。 */
    public void setLatitude(double latitude) { this.latitude = latitude; }

    /** 获取挂载点参考经度（度）。 */
    public double getLongitude() { return longitude; }
    /** 设置挂载点参考经度（度）。 */
    public void setLongitude(double longitude) { this.longitude = longitude; }

    /** 是否需要客户端发送 NMEA GGA。 */
    public boolean isNmea() { return nmea; }
    /** 设置是否需要客户端发送 NMEA GGA。 */
    public void setNmea(boolean nmea) { this.nmea = nmea; }

    /** 获取解算类型：0 单基站、1 网络解。 */
    public int getSolution() { return solution; }
    /** 设置解算类型：0 单基站、1 网络解。 */
    public void setSolution(int solution) { this.solution = solution; }

    /** 获取生成器标识。 */
    public String getGenerator() { return generator; }
    /** 设置生成器标识。 */
    public void setGenerator(String generator) { this.generator = generator; }

    /** 获取压缩方式，通常为 "none"。 */
    public String getCompression() { return compression; }
    /** 设置压缩方式。 */
    public void setCompression(String compression) { this.compression = compression; }

    /** 获取鉴权方式：N 无、B Basic、D Digest。 */
    public String getAuthentication() { return authentication; }
    /** 设置鉴权方式：N 无、B Basic、D Digest。 */
    public void setAuthentication(String authentication) { this.authentication = authentication; }

    /** 是否收费。 */
    public boolean isFee() { return fee; }
    /** 设置是否收费。 */
    public void setFee(boolean fee) { this.fee = fee; }

    /** 获取标称比特率（bps）。 */
    public int getBitrate() { return bitrate; }
    /** 设置标称比特率（bps）。 */
    public void setBitrate(int bitrate) { this.bitrate = bitrate; }

    /** 获取附加说明字段。 */
    public String getMisc() { return misc; }
    /** 设置附加说明字段。 */
    public void setMisc(String misc) { this.misc = misc; }

    /**
     * 序列化为 Sourcetable 的 {@code STR} 记录（不含行尾换行符）。
     *
     * @return 形如 {@code STR;VRS01;VRS01;RTCM 3.2;...} 的单行文本
     */
    public String toSourcetableEntry() {
        return String.join(";",
                "STR",
                name,
                nullToEmpty(identifier),
                nullToEmpty(format),
                nullToEmpty(formatDetails),
                String.valueOf(carrier),
                nullToEmpty(navSystem),
                nullToEmpty(network),
                nullToEmpty(country),
                String.format(Locale.ROOT, "%.2f", latitude),
                String.format(Locale.ROOT, "%.2f", longitude),
                nmea ? "1" : "0",
                String.valueOf(solution),
                nullToEmpty(generator),
                nullToEmpty(compression),
                nullToEmpty(authentication),
                fee ? "Y" : "N",
                String.valueOf(bitrate),
                nullToEmpty(misc));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    @Override
    public String toString() {
        return "MountPoint{" + name + "}";
    }
}