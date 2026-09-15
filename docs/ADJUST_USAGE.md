# rtklib-adjust 模块使用说明

## 概述

单历元GNSS多基线间接平差模块。基于高斯-马尔可夫最小二乘模型，对同一历元多条独立RTK基线进行融合平差，输出流动站P01最优坐标及精度评定。

## 依赖

- rtklib-core（RTK解算核心）
- EJML 0.41（Apache 2.0，矩阵运算）

## 快速使用

```java
// 1. 从rtklib-core获取各基线SolData（RTK解算结果）
SolData solA = ...; // 基线A的解算结果
SolData solB = ...; // 基线B的解算结果
SolData solC = ...; // 基线C的解算结果

// 2. 构建BaselineEntry
BaselineEpoch.BaselineEntry entryA = new BaselineEpoch.BaselineEntry(
    "A", solA,
    CovAssembler.extractBaselineDxyz(solA),       // ECEF基线增量
    CovAssembler.extractCovariance3x3(solA),       // 基线协方差
    CovAssembler.extractCovariance3x3(solA)        // Rover位置协方差
);

// 3. 构建BaselineEpoch（自动剔除非FIX基线）
BaselineEpoch epoch = new BaselineEpoch(
    "2026-01-01 10:00:00",
    new BaselineEpoch.BaselineEntry[]{entryA, entryB, entryC}
);

// 4. 执行平差
AdjustResult result = GnssBaselineAdjust.adjust(epoch);

// 5. 获取结果
if (result.success) {
    double p01X = result.p01Xyz[0];  // 平差后P01 ECEF X (m)
    double p01Y = result.p01Xyz[1];  // 平差后P01 ECEF Y (m)
    double p01Z = result.p01Xyz[2];  // 平差后P01 ECEF Z (m)
    double sigma0 = result.sigma0;    // 单位权中误差
    double[] baardaT = result.baardaT; // Baarda数据探测T统计量
}
```

## 输出说明

| 字段 | 含义 | 单位 |
|------|------|------|
| `p01Xyz` | 平差后P01 ECEF坐标 | m |
| `dx` | 参数解（即P01坐标估计） | m |
| `Dx` | 参数协方差矩阵 3×3 | m² |
| `v` | 残差向量 | m |
| `sigma0` | 单位权中误差 | 无量纲 |
| `dof` | 自由度 df=3k-3 | - |
| `baardaT` | Baarda数据探测T统计量 | - |
| `Qv` | 残差协因数矩阵 | m² |

## 特殊情况

- **k=1**（单基线）：df=0，无法精度评定，sigma0=NaN，不计算Baarda
- **k=0**（无有效基线）：返回失败结果
- **非FIX基线**：构建BaselineEpoch时自动剔除