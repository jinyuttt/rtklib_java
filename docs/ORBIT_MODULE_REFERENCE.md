# 轨道模块技术参考

> **包路径**：`org.rtklib.java.orbit`
> **功能**：TLE解析、SGP4/SDP4轨道传播、轨道六根数转换、二体轨道传播
> **算法来源**：SGP4/SDP4逐行移植自python-sgp4（Vallado标准）；rv2coe/coe2rv移植自Orekit 12的KeplerianParametersConverter

---

## 1. 模块总览

```
TLE ──解析──> TleData ──SGP4──> (r,v)ₜₑₘₑ ──rv2coe──> (a,e,i,Ω,ω,M)
                                                    ↑
                                              coe2rv ↓
                                              (r,v)ₜₑₘₑ
```

| 类 | 职责 |
|----|------|
| `TleParser` | TLE文件/字符串解析 → `TleData` |
| `TleData` | TLE数据容器（原始行 + `Satellite`记录） |
| `Sgp4Propagator` | SGP4/SDP4轨道传播（近地/深空/同步） |
| `OrbitalMechanics` | rv2coe / coe2rv / 开普勒方程求解 |
| `OrbitalElements` | 六根数不可变容器 (a, e, i, Ω, ω, M) |
| `KeplerPropagator` | 二体轨道传播（仅平近点角线性推进） |
| `TleConverter` | TLE↔六根数 / TLE↔状态向量 转换入口 |
| `StateVector` | 位置速度不可变容器 (x,y,z,vx,vy,vz) + 坐标系 |
| `Frame` | 坐标系枚举 (TEME / EME2000 / ITRF) |
| `OrbitConstants` | 物理常数 (μ, Re, J2, J3, J4) |

---

## 2. TLE解析

### 2.1 从文件读取

```java
Tle tle = new Tle();
TleParser.tleRead("data/tle.txt", tle);
// tle.data[] 中每颗卫星一个 TleData
```

### 2.2 从两行字符串解析

```java
TleData tleData = TleParser.twoline2rv(line1, line2, "00");
// tleData.satrec 已完成 sgp4init，可直接传播
```

### 2.3 TleData字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `satno` | String | 5字符卫星编号 |
| `line1` | String | TLE第1行原始字符串 |
| `line2` | String | TLE第2行原始字符串 |
| `name` | String | 卫星名称（可选） |
| `satrec` | Satellite | SGP4卫星记录（含全部派生系数） |

---

## 3. SGP4/SDP4轨道传播

### 3.1 基本用法

```java
Sgp4Propagator prop = new Sgp4Propagator(tleData);
double[] rs = new double[6];  // [x, y, z, vx, vy, vz]
prop.propagate(tsince, rs);   // tsince: 历元后分钟数
```

### 3.2 输出单位

| 分量 | 单位 |
|------|------|
| x, y, z | km（TEME坐标系，地心惯性系） |
| vx, vy, vz | km/s |

### 3.3 轨道类型自动判断

| 条件 | 模型 | 适用轨道 |
|------|------|----------|
| 周期 < 225 min | SGP4 | LEO（近地轨道） |
| 偏心率 < 0.0 且 225 ≤ 周期 ≤ 720 min | SGP4 | 深空近圆 |
| 其他 | SDP4 | 深空（Molniya/GEO/共振） |

### 3.4 验证状态

- Vallado标准测试：14个用例全部通过
- 真实TLE交叉验证：86颗卫星，最大位置误差 < 0.001 km

---

## 4. 轨道六根数

### 4.1 六根数定义

| 根数 | 符号 | 单位 | 范围 | 物理含义 |
|------|------|------|------|----------|
| 半长轴 | a | km | > 0 | 轨道大小 |
| 偏心率 | e | 无量纲 | [0, 1) | 轨道形状（0=圆，→1=极椭圆） |
| 轨道倾角 | i | rad | [0, π] | 轨道面与赤道面夹角 |
| 升交点赤经 | Ω (raan) | rad | [0, 2π) | 升交点在赤道上的角位置 |
| 近地点幅角 | ω (argp) | rad | [0, 2π) | 升交点到近地点的角距 |
| 平近点角 | M (meanAnomaly) | rad | [0, 2π) | 卫星在轨道上的平均角位置 |

### 4.2 OrbitalElements便利方法

```java
OrbitalElements oe = TleConverter.tleToOrbitalElements(tleData);

oe.period();        // 轨道周期 (秒)
oe.meanMotion();    // 平均角速度 n (rad/s)
oe.apogee();        // 远地点半径 a(1+e) (km)
oe.perigee();       // 近地点半径 a(1-e) (km)
```

---

## 5. 状态向量 ↔ 六根数转换

### 5.1 rv2coe：状态向量 → 六根数

```java
StateVector sv = new StateVector(x, y, z, vx, vy, vz, Frame.TEME, epochJd);
OrbitalElements oe = OrbitalMechanics.rv2coe(sv);
```

**算法流程**（移植自Orekit KeplerianParametersConverter）：

1. 计算比动量矩 **h** = **r** × **v**，得倾角 i = arccos(hz/|h|)
2. 计算升交线方向 **n** = (−hy, hx, 0)，得升交点赤经 Ω = atan2(ny, nx)
3. 由vis-viva方程得半长轴 a = r / (2 − rv²/μ)
4. 计算偏心率矢量：eSE = **r**·**v** / √(μa)，eCE = rv²/μ − 1，e = √(eSE² + eCE²)
5. 偏近点角 E = atan2(eSE, eCE)，真近点角 ν 由E转换
6. 近地点幅角 ω = atan2(py, px) − ν，其中 px = **r**·**n̂**，py = **r**·(**h**×**n̂**) / |h|
7. 平近点角 M = E − e·sin(E)

### 5.2 coe2rv：六根数 → 状态向量

```java
StateVector sv = OrbitalMechanics.coe2rv(oe);
// 或直接传参数：
double[] rv = OrbitalMechanics.coe2rv(a, e, i, raan, argp, meanAnomaly);
```

**算法流程**（移植自Orekit）：

1. 求解开普勒方程 M = E − e·sin(E) 得偏近点角 E
2. 计算PQW坐标系下的位置和速度：
   - xp = a(cosE − e)，yp = a·sinE·√(1−e²)
   - vxp = −sinE·√(μ/a)/(1−e·cosE)，vyp = cosE·√(1−e²)·同因子
3. 通过referenceAxes旋转矩阵将PQW→IJK：
   - **P** = (cosΩ·cosω − cosi·sinΩ·sinω, sinΩ·cosω + cosi·cosΩ·sinω, sini·sinω)
   - **Q** = (−cosΩ·sinω − cosi·sinΩ·cosω, −sinΩ·sinω + cosi·cosΩ·cosω, sini·cosω)
4. **r** = xp·**P** + yp·**Q**，**v** = vxp·**P** + vyp·**Q**

### 5.3 往返转换精度

rv2coe → coe2rv 往返转换位置误差 < 0.001 km（Vallado全部11颗测试卫星验证通过）。

---

## 6. 开普勒方程求解

### 6.1 椭圆轨道：M = E − e·sin(E)

采用Orekit的**Halley修正牛顿迭代法**，2次迭代即达机器精度：

1. 初始估计：基于Markley风格的分段近似
2. 迭代：使用三阶Halley修正步长 `dee = f·f' / (0.5·f·f'' − f'²)`
3. 精度：|ΔE| < 10⁻¹⁵ rad（2次迭代后）

### 6.2 双曲线轨道：M = e·sinh(H) − H

采用Lagrange展开初始估计 + Halley修正，2次迭代收敛。

---

## 7. 二体轨道传播

```java
OrbitalElements oe = TleConverter.tleToOrbitalElements(tleData);
KeplerPropagator kepler = new KeplerPropagator(oe);

// 传播至历元后60分钟
StateVector sv = kepler.propagate(60.0);

// 也可获取传播后的六根数
OrbitalElements oe2 = kepler.propagateElements(60.0);
```

**注意**：KeplerPropagator是纯二体模型，仅推进平近点角 M(t) = M₀ + n·Δt，不考虑J2等摄动。与SGP4的差异随传播时间增大：

| 传播时间 | LEO位置差 | MEO位置差 | GEO位置差 |
|----------|-----------|-----------|-----------|
| 0 min | 0 km | 0 km | 0 km |
| 30 min | ~1 km | ~0.5 km | ~0.3 km |
| 60 min | ~5 km | ~2 km | ~1 km |

---

## 8. TleConverter转换入口

| 方法 | 输入 | 输出 | 说明 |
|------|------|------|------|
| `tleToOrbitalElements(TleData)` | TLE | OrbitalElements | TLE→SGP4(t=0)→rv2coe |
| `tleToOrbitalElements(TleData, tsince)` | TLE + 分钟 | OrbitalElements | TLE→SGP4(t=tsince)→rv2coe |
| `tleToStateVector(TleData)` | TLE | StateVector | TLE→SGP4(t=0) |
| `tleToStateVector(TleData, tsince)` | TLE + 分钟 | StateVector | TLE→SGP4(t=tsince) |
| `orbitalElementsToStateVector(OrbitalElements)` | 六根数 | StateVector | coe2rv |
| `stateVectorToOrbitalElements(StateVector)` | 状态向量 | OrbitalElements | rv2coe |

---

## 9. 坐标系

| 枚举值 | 含义 | 说明 |
|--------|------|------|
| `TEME` | True Equator Mean Equinox | SGP4输出坐标系，真赤道平春分点 |
| `EME2000` | Earth Mean Equator 2000 | J2000平赤道平春分点（常用惯性系） |
| `ITRF` | International Terrestrial Reference Frame | 国际地球参考框架（地固系） |

**当前状态**：SGP4输出为TEME坐标系。TEME↔EME2000↔ITRF转换尚未实现。

TEME与EME2000的差异约0.5~2.8 km（取决于轨道高度），如需与IGS精密星历或RTKLIB定位结果比对，需实现帧转换。

---

## 10. 物理常数

| 常量 | 值 | 说明 |
|------|-----|------|
| `MU` | 398600.8 km³/s² | 地球引力常数 (WGS72) |
| `EARTH_RADIUS_KM` | 6378.135 km | 地球赤道半径 (WGS72) |
| `J2` | 1.082616×10⁻³ | 二阶带谐系数 |
| `J3` | −2.53881×10⁻⁶ | 三阶带谐系数 |
| `J4` | −1.62097×10⁻⁶ | 四阶带谐系数 |
| `XKE` | √(μ/Re³) | 开普勒方程用归一化常数 |
| `TUMIN` | 1/XKE | 归一化时间单位 (min/rad) |

> **注意**：SGP4使用WGS72常数（与TLE数据匹配），与WGS84的μ=398600.4418有微小差异。六根数转换中rv2coe/coe2rv也使用同一μ值以保持一致性。

---

## 11. 算法来源与移植说明

| 算法 | 来源 | 移植方式 |
|------|------|----------|
| SGP4/SDP4 | python-sgp4 (Brandon Rhodes) | 逐行翻译，保留变量命名和注释风格 |
| sgp4init | python-sgp4 model.py | 逐行翻译 |
| rv2coe | Orekit 12 KeplerianParametersConverter | 提取核心逻辑，去除Orekit框架依赖 |
| coe2rv | Orekit 12 KeplerianParametersConverter | 同上 |
| solveKepler | Orekit 12 KeplerianAnomalyUtility | Halley修正牛顿法，2次迭代收敛 |
| hyperbolicMeanToEccentric | Orekit 12 KeplerianAnomalyUtility | Lagrange展开 + Halley修正 |

**移植原则**：
- 不引入Orekit依赖，仅移植纯数学算法
- 保留Orekit的数值稳定性处理（如eMeSinE级数展开避免大数相消）
- 使用与Orekit相同的μ值（398600.8），与SGP4保持一致