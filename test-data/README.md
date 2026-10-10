# 测试数据规范

> 本目录存放 rtklib_java 项目的公共测试数据（≤2MB），大文件存放在独立数据仓库 [rtklib-java-data](https://github.com/jinyurrr/rtklib-java-data)。

## 目录结构

```
test-data/
├── rinex/          RINEX 观测数据
├── nav/            RINEX 导航星历
├── rtcm/           RTCM3 数据流
├── product/        精密产品 (SP3/CLK/IONEX/ATX/BIA)
├── reference/      参考解 (.pos)
├── nmea/           NMEA 数据（大文件，数据仓库）
├── config/         配置文件
└── tle/            TLE 两行根数
```

## 元数据规范（.meta）

每个数据文件须附带同名 `.meta` 文件。

### 必填字段

| 字段 | 说明 | 示例 |
|------|------|------|
| `scenario` | 数据场景 | `open-sky`, `urban`, `downtown`, `street`, `elevated` |
| `role` | 数据角色 | `rover`, `base`, `reference`, `nav` |
| `systems` | 卫星系统 | `G`, `G+R`, `G+E+J+C`, `G+R+E+C+J+S` |
| `date` | 观测日期 | `2025-04-08` |
| `source` | 数据来源项目 | `MobileGNSS-SPP`, `Net_Diff`, `rtklib`, `IGS`, `GFZ` |

### 选填字段

| 字段 | 说明 | 示例 |
|------|------|------|
| `sample_rate` | 采样间隔 | `1s`, `30s` |
| `duration` | 数据时长 | `30min`, `4h` |
| `receiver` | 接收机型号 | `UNISOC uis7865`, `NovAtel OEMV` |
| `antenna` | 天线型号 | `unknown`, `LEIAR25.R4` |
| `base.id` | 基站标识 | `3040`, `VRS-001` |
| `base.distance` | 基线长度 | `1.2km`, `15km` |
| `mode` | 定位模式（参考解） | `spp`, `rtk`, `ppp`, `ppp-ar` |
| `software` | 解算软件（参考解） | `RTKLIB demo5 b34k` |
| `license` | 数据许可 | `MIT`, `IGS-data-policy`, `public` |
| `description` | 自由描述 | `城市峡谷手机GNSS数据` |
| `location` | 采集地点 | `Beijing downtown`, `Japan` |
| `type` | 产品类型 | `sp3`, `clk`, `ionex`, `atx`, `bia` |
| `test_ids` | 引用该数据的测试类 | `SppOptimizationTest,RinexFormatTest` |

## 数据License

| 数据类型 | License | 说明 |
|----------|---------|------|
| rtklib样例 | BSD-2-Clause | RTKLIB原始测试数据 |
| MobileGNSS-SPP | MIT | 可自由使用、修改、分发 |
| cssrlib | MIT | 可自由使用、修改、分发 |
| Net_Diff样例 | public | 公开样例数据 |
| IGS精密产品 | IGS Data Policy | 免费用于研究，需引用IGS |
| GFZ/CODE产品 | IGS Data Policy | 同IGS政策 |

> 所有测试数据仅供研究和测试使用。精密产品使用时请按IGS数据政策引用相应分析中心。

## 大文件清单（数据仓库 rtklib-java-data 中存放）

| 文件 | 大小 | 用途 | License |
|------|------|------|---------|
| `rinex/urban_base_20000719.17o` | 24.9MB | 城市RTK基站(G+E+J+C)，准静态 | public |
| `rinex/urban_rover_20000719.17o` | 14.5MB | 城市RTK流动站(G+E+J+C)，准静态 | public |
| `nav/urban_20000719.17p` | 5.7MB | 城区多系统NAV | public |
| `reference/urban_rtk_20000719.pos` | 1.3MB | 城市RTK参考固定解 | public |
| `rinex/downtown_rover_20250408.25o` | 3.1MB | 城市峡谷(动态~140m) | MIT |
| `nav/downtown_rover_20250408.25n` | 4.4MB | 城市峡谷NAV | MIT |
| `nmea/downtown_base_20250408.nmea` | 2.9MB | 城市峡谷基站NMEA | MIT |
| `nmea/downtown_rtk_20250408.nmea` | 2.7MB | 城市峡谷RTK结果 | MIT |
| `rinex/street_rover_20250312.25o` | 1.9MB | 街道(动态~250m) | MIT |
| `nav/street_rover_20250312.25n` | 4.4MB | 街道NAV | MIT |
| `nmea/street_base_20250312.nmea` | 1.8MB | 街道基站NMEA | MIT |
| `nmea/street_rtk_20250312.nmea` | 2.3MB | 街道RTK结果 | MIT |
| `rinex/opensky_rover_20250408.25o` | 2.3MB | 开阔地(动态~6km) | MIT |
| `nav/opensky_rover_20250408.25n` | 4.4MB | 开阔地NAV | MIT |
| `nmea/opensky_base_20250408.nmea` | 2.5MB | 开阔地基站NMEA | MIT |
| `nmea/opensky_rtk_20250408.nmea` | 2.5MB | 开阔地RTK结果 | MIT |
| `rinex/ppp_hksl_20180606.18o` | 19MB | 香港站PPP(G+R+E+C+J+S) | public |
| `rinex/ppp_hkws_20180606.18o` | 18.2MB | 香港站PPP | public |
| `rinex/ppp_wtza_20180606.18o` | 4.5MB | PPP动态站 | public |
| `rinex/ppp_wtzr_20180606.18o` | 10.7MB | PPP动态站 | public |
| `nav/ppp_20180606.18p` | 5.3MB | PPP多系统NAV | public |
| `product/gbm20043.sp3` | 1.9MB | GFZ精密轨道 | IGS-data-policy |
| `product/gbm20043.clk` | 17.7MB | GFZ精密钟差 | IGS-data-policy |
| `product/igsg1570.18i` | 0.8MB | IGS电离层格网 | IGS-data-policy |
| `reference/ppp_wtza_20180606.pos` | 0.4MB | PPP参考解 | public |
| `reference/ppp_wtzr_20180606.pos` | 0.4MB | PPP参考解 | public |

## 小文件清单（已直接提交至 test-data/）

| 文件 | 大小 | 用途 |
|------|------|------|
| `rinex/rinex210_gps_0759.05o` | 68KB | RINEX 2.10 GPS双频(流动站) |
| `rinex/rinex210_gps_3040.05o` | 74KB | RINEX 2.10 GPS双频(基站) |
| `rinex/rinex304_gej_3034.21o` | 294KB | RINEX 3.04 G+E+J(1min) |
| `rinex/rinex304_gej_sept.21o` | 255KB | RINEX 3.04 Septentrio(1min) |
| `rinex/rinex304_gejc_elevated.25o` | 1.6MB | RINEX 3.04 手机高架 |
| `rtcm/open-sky_base_20090515.rtcm3` | 80KB | RTCM3 VRS基站(私有消息) |
| `rtcm/rtcm3_gmsd_20121014.rtcm3` | 256KB | RTCM3 标准消息(G+R+C) |
| `rtcm/rtcm3_glo_test.rtcm3` | 57KB | RTCM3 GLONASS消息 |
| `nav/rinex210_gps_0759.05n` | 94KB | RINEX 2.10 GPS NAV |
| `nav/rinex210_gps_3040.05n` | 96KB | RINEX 2.10 GPS NAV(基站) |
| `nav/rinex304_mixed_sept.21p` | 147KB | G+E+J混合NAV |
| `nav/rinex304_gejc_elevated.25n` | 4.5MB | 手机四系统NAV(高架) |
| `nav/rinex302_qzss.21q` | 58KB | QZSS专用NAV |
| `product/igs15904.sp3` | 248KB | IGS精密轨道 |
| `product/igs15904.clk` | 193KB | IGS精密钟差 |
| `product/igsg1570.18i` | 863KB | IGS电离层格网 |
| `product/test.atx` | 256KB | ANTEX天线改正 |
| `product/cod_osb_2021265.bia` | 710KB | Bias-SINEX码偏差 |
| `reference/rtk_urban_fix.17.pos` | 1.3MB | RTK固定解参考 |
| `reference/ppp_wtza_kinematic.18.pos` | 0.4MB | PPP动态解参考 |
| `reference/ppp_wtzr_kinematic.18.pos` | 0.4MB | PPP动态解参考 |
| `tle/tle_gps.txt` | 1.6KB | GPS两行根数 |
| `tle/tle_bds.txt` | 1.4KB | BDS两行根数 |
| `config/open-sky_rtk_kinematic.conf` | 0.3KB | RTK配置文件 |

## 获取大文件数据

```powershell
# 从独立数据仓库下载
./test-data/download-test-data.ps1
```

## 文件命名规范

```
{scenario}_{role}_{date}.{ext}       # 观测/导航数据
{source}_{date}.{ext}                # 精密产品
{type}_{date}.pos                    # 参考解
```

### 历史命名（兼容）

早期文件使用 `rinex{version}_{systems}_{station}.{ext}` 格式，如 `rinex210_gps_0759.05o`。