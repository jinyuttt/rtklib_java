# 参考配置文件

上传 RTKLIB/项目兼容的定位配置文件作为参考。

## 命名约定

```
{scenario}_{mode}_{soltype}.conf
```

| 示例 | 说明 |
|------|------|
| `open-sky_rtk_kinematic.conf` | 开阔地RTK动态配置 |
| `urban_rtk_static.conf` | 城市RTK静态配置 |
| `open-sky_ppp_static.conf` | 开阔地PPP静态配置 |
| `canyon_spp_single.conf` | 峡谷SPP单点配置 |

## .meta 必填字段

```properties
scenario=open-sky
role=reference
systems=G
date=2009-05-15
source=RTKLIB-sample
mode=rtk
software=RTKLIB-C 2.4.1
software.config=kinematic, L1+L2, AR=fix-and-hold
```

## 上传要求

- 单文件 ≤ 10KB
- 附带 `.meta` 文件
- 标注 `mode` 和 `software`