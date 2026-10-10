# RTCM3 测试数据

上传短时段 RTCM3 采样文件用于解码和转换测试。

## 命名约定

```
{scenario}_{role}_{date}_{hour}.rtcm3
```

| 示例 | 说明 |
|------|------|
| `urban_rover_20260629_12.rtcm3` | 城市流动站12时 |
| `canyon_base_20260629_8.rtcm3` | 峡谷基站8时 |
| `vehicular_rover_20260629_14.rtcm3` | 车载流动站14时 |

## .meta 必填字段

```properties
scenario=urban
role=rover
systems=G+R+C+E
date=2026-06-29
source=self-collected
# RTK数据还需标注:
base.id=urban-base-01
base.distance=8.5
```

## 上传要求

- 单文件 ≤ 500KB（短时段采样）
- 附带 `.meta` 文件（必填字段完整）
- 无设备序列号等敏感信息