# RINEX 测试数据

上传 RINEX 3.x 观测/导航文件用于算法验证。

## 命名约定

```
{scenario}_{role}_{date}[_{type}].{ext}
```

| 示例 | 说明 |
|------|------|
| `urban_rover_20260629.obs` | 城市流动站观测 |
| `urban_base_20260629.nav` | 城市基站导航 |
| `open-sky_cors_2026179.obs` | 开阔地CORS站观测 |
| `canyon_rover_20260629.obs` | 峡谷流动站观测 |

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

- 单文件 ≤ 2MB
- 附带 `.meta` 文件（必填字段完整）
- 无设备序列号等敏感信息