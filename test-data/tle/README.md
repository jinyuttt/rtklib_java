# TLE 轨道根数

上传卫星两行根数用于 SGP4 轨道传播验证。

## 命名约定

```
tle_{system}.txt
```

| 示例 | 说明 |
|------|------|
| `tle_gps.txt` | GPS卫星TLE |
| `tle_bds.txt` | 北斗卫星TLE |
| `tle_glo.txt` | GLONASS卫星TLE |
| `tle_gal.txt` | Galileo卫星TLE |

## .meta 必填字段

```properties
scenario=orbit-validation
role=reference
systems=G
date=2026-09-21
source=Celestrak
```

## 上传要求

- 单文件 ≤ 100KB
- 来源标注（Celestrak / Space-Track）
- 附带 `.meta` 文件