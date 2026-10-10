# 参考解算结果

上传参考定位结果用于精度验证（与C版RTKLIB、PRIDE-PPPAR等对比）。

## 命名约定

```
{scenario}_{role}_{date}_{mode}.pos
```

| 示例 | 说明 |
|------|------|
| `open-sky_rover_2026179_spp.pos` | 开阔地SPP参考解 |
| `urban_rover_20260629_rtk.pos` | 城市RTK参考解 |
| `open-sky_cors_2026179_ppp.pos` | 开阔地PPP参考解 |
| `vehicular_rover_20260629_rtk.pos` | 车载RTK参考解 |

## .meta 必填字段

```properties
scenario=open-sky
role=reference
systems=G+R+C+E
date=2026-06-28
source=IGS
mode=ppp
software=PRIDE-PPPAR 3.2
software.config=IFLC, AR=fix-and-hold, wum0mgx rapid
```

## 上传要求

- 单文件 ≤ 2MB
- 标注解算软件和配置
- 附带 `.meta` 文件（`mode` 和 `software` 必填）