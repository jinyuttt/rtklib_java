# 精密产品测试数据

上传 IGS/MGEX 精密产品样本用于 PPP 测试。

## 命名约定

```
{analysis_center}_{date}.{ext}
```

| 示例 | 说明 |
|------|------|
| `wum0mgx_2026179.sp3` | WUM精密星历 |
| `wum0mgx_2026179.clk` | WUM精密钟差 |
| `wum0mgx_2026179.erp` | 地球自转参数 |
| `cas0mgx_2026180.bsx` | CAS差分码偏差 |

## .meta 必填字段

```properties
scenario=open-sky
role=reference
systems=G+R+C+E
date=2026-06-28
source=MGEX
```

## 上传要求

- 单文件 ≤ 2MB
- 来源标注（IGS/MGEX分析中心）
- 附带 `.meta` 文件