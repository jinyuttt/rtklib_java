# RTK 定位处理流程文档

## 目录
1. [整体架构](#1-整体架构)
2. [rtkpos() 主流程](#2-rtkpos-主流程)
3. [relpos() 相对定位流程](#3-relpos-相对定位流程)
4. [核心子函数详解](#4-核心子函数详解)
5. [数据结构](#5-数据结构)
6. [C/Java 已知差异清单](#6-cjava-已知差异清单)

---

## 1. 整体架构

```
rtkpos(rtk, obs, n, nav)
│
├── ① 分离观测：流动站(rcv=1) / 基准站(rcv=2)
├── ② 流动站 SPP 作为初始位置
├── ③ 设置基站位置 (RTCM 1005 或 SPP 估算)
│
└── relpos(rtk, obs, nu, nr, nav)
    │
    ├── ④ 卫星位置 + 钟差计算
    ├── ⑤ 基准站零差残差 zdres(base=1)
    ├── ⑥ 时间对齐 (intpres)
    ├── ⑦ 选星 selsat()
    ├── ⑧ Kalman 状态初始化
    ├── ⑨ 状态更新 udstate()
    │
    ├── 迭代 niter 次 ──┐
    │   ├── ⑩ 流动站零差残差 zdres(base=0)
    │   ├── ⑪ 双差残差 ddres()
    │   └── ⑫ Kalman 滤波 filter()
    │                  └──────────────────┘
    │
    ├── ⑬ LAMBDA 模糊度固定
    └── ⑭ 输出结果
```

---

## 2. rtkpos() 主流程

### 2.1 观测分离

```
obs[n] 数组按 rcv 字段拆分：
  obs[0..nu-1]  → 流动站观测 (rcv==1)
  obs[nu..n-1]  → 基准站观测 (rcv==2)
```

### 2.2 流动站 SPP 初始定位

| 步骤 | 说明 |
|------|------|
| 输入 | 流动站观测 `obs[0..nu-1]`，广播星历 `nav` |
| 处理 | 最小二乘伪距单点定位 |
| 输出 | `rtk.sol.rr[3]`（流动站 ECEF 坐标），`rtk.sol.dtr[2]`（接收机钟差） |

**C/Java 差异**：C 版每历元都执行 SPP，Java 版仅首历元执行。

### 2.3 基站位置设置

| 模式 | 基站位置来源 | 更新频率 |
|------|-------------|---------|
| DGPS / Kinematic / Static / Static-Start / Fixed | `rtk.rb = opt.rb`（RTCM 1005） | 一次设置，全程不变 |
| Moving-Base | `rtk.rb = SPP(基站观测)`，低通滤波 | 每历元更新 |
| Single / PPP-* | 不使用基站 | — |

---

## 3. relpos() 相对定位流程

### 3.1 卫星位置计算 `satposs()`

```
输入：obs[n] 所有观测，nav 广播星历
输出：rs[n*6]  卫星位置+速度 (ECEF, m)，每颗卫星 6 个值
      dts[n*2] 卫星钟差+钟速 (s, s/s)
      vare[n]  卫星位置方差
      svh[n*2] 卫星健康状态

关键步骤：
  a) 信号传播时间迭代：tr = t_obs - P/C - dts
  b) 根据星历类型计算卫星位置（广播星历/精密星历）
  c) 地球自转修正 (Sagnac)：rs += ωe × rs * tr
  d) 卫星钟差：相对论修正 + TGD 修正
```

### 3.2 基准站零差残差 `zdres(base=1)`

```
zdres = 观测值 - 几何距离 - 模型修正

几何距离 = |卫星位置(ECEF) - 基站位置(ECEF)|

模型修正项：
  - 对流层延迟 (Saastamoinen 模型)
  - 卫星钟差
  - 相对论效应
  - 固体潮 + 海潮 + 极潮 (tidedisp)
  - 天线相位中心偏移 (PCO)
  - IFLC 线性组合 (IONOOPT_IFLC 模式下)

输出：y[n*2] 零差残差 (L1, L2)，e[n*3] 视线单位向量，azel[n*2] 方位角/高度角
```

### 3.3 时间对齐 `intpres()`

```
目的：将基准站观测插值到流动站时间

if intpref == 0:
    dt = timediff(rover_time, base_time)  // 直接计算时间差

if intpref != 0:
    yb = zdres(上一历元基准站观测)
    y  = zdres(当前历元基准站观测)
    y = (ttb*y - tt*yb) / (ttb - tt)  // 线性插值
```

### 3.4 选星 `selsat()`

```
筛选条件：
  1. 基准站和流动站共视
  2. 高度角 > 截止高度角
  3. 排除异常卫星 (svh 标记)
  4. 双频观测值完整

输出：
  sat[ns]  共视卫星 PRN 列表
  iu[ns]   流动站中对应索引
  ir[ns]   基准站中对应索引
```

### 3.5 Kalman 状态初始化

```
状态向量 x[nx] 结构 (Kinematic 模式)：

  x[0..2]   = 基线向量 (流动站 - 基站) ECEF
  x[3..5]   = 速度
  x[6..nx-1] = 单差模糊度 (每颗卫星 × 每频率)

初始值：
  x[0..2] = rtk.sol.rr - rtk.rb  ← 初始基线
  x[3..5] = 0
  x[6..]  = 从 zdres 残差反推，或继承上一历元

协方差矩阵 P[nx*nx] (对角阵)：
  P[0..2] = VAR_POS     = SQR(30.0)  = 900
  P[3..5] = VAR_VEL     = SQR(10.0)  = 100
  P[6..]  = VAR_AMB     = SQR(30.0)  = 900
```

### 3.6 状态更新 `udstate()`

```
处理内容：
  1. 模糊度继承：前后历元共视卫星的模糊度保持不变
  2. 周跳检测：LLI 标记 / 几何距离跳变
  3. 电离层状态传递 (IONOOPT_EST 模式)
  4. 对流层状态传递 (TROPOPT_EST 模式)
```

### 3.7 迭代 Kalman 滤波

```
for iter = 1 to niter (通常 niter=1):

  3.7.1 流动站零差残差 zdres(base=0)
        几何距离 = |卫星位置 - (rtk.rb + xp[0..2])|
        xp 为当前 Kalman 基线向量估计

  3.7.2 双差残差 ddres()
        参考星选择：高度角最高的共视卫星

        DD_L1 = (流动站_ref - 基准站_ref)_L1 - (流动站_j - 基准站_j)_L1
        DD_L2 = (流动站_ref - 基准站_ref)_L2 - (流动站_j - 基准站_j)_L2

        构建：
          v[ny]    = 双差残差向量 (观测 - 计算)
          H[ny*nx] = 设计矩阵 (偏导数)
          R[ny*ny] = 观测噪声协方差

        设计矩阵 H 元素 (非参考星 j, 非参考频率):
          H[j, 0..2] = -e_ref + e_j          (位置偏导数)
          H[j, ref]  = -λ                    (参考星模糊度)
          H[j, j]    = +λ                    (j 星模糊度)

  3.7.3 Kalman 滤波 filter()
          K = P * H' * inv(H * P * H' + R)   (卡尔曼增益)
          x = x + K * v                      (状态更新)
          P = (I - K * H) * P                (协方差更新)
```

### 3.8 模糊度固定 `resamb_LAMBDA()`

```
条件：ratio > 阈值 (默认 3.0)

  1. 提取浮点模糊度部分 xa[na], Pa[na*na]
  2. LAMBDA 搜索整数最小二乘解
  3. 固定解验证 (ratio test)
  4. 固定成功 → 更新全状态 x, P
```

### 3.9 输出结果

```
rtk.sol.rr = rtk.rb + x[0..2]   // 流动站绝对位置 (ECEF)
rtk.sol.stat = {
  SOLQ_NONE  (0)  // 无解
  SOLQ_FIX   (1)  // 固定解
  SOLQ_FLOAT (2)  // 浮点解
  SOLQ_DGPS  (4)  // 差分解 (DGPS 模式)
}
```

---

## 4. 核心子函数详解

### 4.1 zdres() — 零差残差

```
函数签名：
  zdres(base, obs, n, rs, dts, vare, svh, nav, rr, opt, y, e, azel, freq)

  base=1: 基准站 → rr = rtk.rb
  base=0: 流动站 → rr = rtk.rb + xp (基线)

计算流程：
  for each sat i:
      r = |rs[i] - rr|                        // 几何距离
      dtrp = saastamoinen(time, rr, azel[i])  // 对流层延迟
      tidedisp(time, rr, opt, &dr)            // 固体潮等
      r += dr 沿视线方向

      for each freq j:
          y[i*2+j] = P[i*2+j] - (r + dts[i*2] - dtrp)  // 伪距残差
          y[i*2+j] = L[i*2+j] - (r + dts[i*2] - dtrp)  // 载波残差

      e[i] = (rs[i] - rr) / r                  // 视线单位向量
```

### 4.2 ddres() — 双差残差

```
参考星选择：高度角最大的共视卫星

for each non-ref sat j:
    v[j] = (zdres_rover[j] - zdres_base[j]) - (zdres_rover[ref] - zdres_base[ref])

H 矩阵构建：
    H[j, 0..2] = -e_ref + e_j     // 位置偏导数

    for each freq k:
        H[j, ref_idx+k] = -λ_k    // 参考星模糊度
        H[j, j_idx+k]   = +λ_k    // 卫星 j 模糊度

        if IONOOPT_EST:
            H[j, iono_ref] = -f1²/fk²
            H[j, iono_j]   = +f1²/fk²
```

### 4.3 filter() — Kalman 滤波

```
标准 Kalman 滤波公式：

  xp = F * x      // 状态预测 (Static 模式 F=I)
  Pp = F * P * F' + Q  // 协方差预测

  K = Pp * H' * inv(H * Pp * H' + R)  // 卡尔曼增益
  x = xp + K * v                      // 状态更新
  P = (I - K * H) * Pp                // 协方差更新
```

### 4.4 varerr() — 观测噪声

```
伪距噪声：
  var = FACT_P(初值) * (code_factor) * (elevation_factor)

载波噪声：
  var = FACT_L(初值) * (code_factor) * (elevation_factor)

IFLC 模式放大：
  var *= SQR(3.0)  // 无电离层组合噪声放大 9 倍
```

---

## 5. 数据结构

### 5.1 Rtk — RTK 控制结构

```
rtk.sol       → 解算结果 (位置、钟差、状态)
rtk.rb[3]     → 基准站 ECEF 坐标
rtk.nx        → 状态向量维度
rtk.x[nx]     → 状态向量 (Kalman)
rtk.P[nx*nx]  → 协方差矩阵
rtk.tt        → 历元时间差
rtk.ssat[]    → 卫星状态
rtk.opt       → 处理选项
rtk.intpres_nb → 内插基准站观测数量
rtk.intpres_obsb → 内插基准站观测数据
rtk.epoch     → 历元计数
```

### 5.2 Sol — 解算结果

```
sol.time      → 解算时刻
sol.rr[3]     → 位置 (ECEF, m)
sol.qr[3]     → 位置方差 (m²)
sol.dtr[2]    → 接收机钟差 (s)
sol.stat      → 解状态: NONE/FIX/FLOAT/DGPS
sol.ns        → 有效卫星数
sol.ratio     → 模糊度固定 ratio
```

### 5.3 Obsd — 观测数据

```
obs.time      → 观测时刻
obs.sat       → 卫星 PRN
obs.rcv       → 接收机编号 (1=流动站, 2=基准站)
obs.P[2]      → 伪距观测值 (L1, L2)
obs.L[2]      → 载波相位观测值 (L1, L2)
obs.D[2]      → 多普勒观测值
obs.lli[2]    → 周跳标记
obs.code[2]   → 观测值类型 (C1C, L1C, etc.)
```

---

## 6. C/Java 已知差异清单

| 序号 | 位置 | 差异描述 | 当前测试影响 | 状态 |
|------|------|---------|-------------|------|
| 1 | `zdres()` | 缺少 IFLC 线性组合 | 不影响 (IONOOPT_BRDC) | 待修复 |
| 2 | `zdres()` | 缺少天线 PCO 修正 | 不影响 (无天线文件) | 待修复 |
| 3 | `zdres()` | 固体潮占位实现 | **已修复** | ✅ |
| 4 | `prange()` | 缺少 DCB 修正 | 不影响 (无 DCB 数据) | 待修复 |
| 5 | `varerr()` | IFLC 方差放大公式错误 | 不影响 (IONOOPT_BRDC) | **已修复** |
| 6 | `intpres()` | 基准站观测值内插缺失 | 不影响 (intpref=0) | **已修复** |
| 7 | `rtkpos()` | SPP 仅首历元执行 | **可能有影响** | 待修复 |
| 8 | `rtkpos()` | MOVEB 模式 SPP 数据错误 | 不影响 (非 MOVEB) | 待修复 |
| 9 | `relpos()` | 待进一步排查 | 剩余偏差根因 | 排查中 |

---

## 附录：关键常量

| 常量 | 值 | 说明 |
|------|-----|------|
| VAR_POS | SQR(30.0) = 900 | 位置初始方差 |
| VAR_VEL | SQR(10.0) = 100 | 速度初始方差 |
| VAR_AMB | SQR(30.0) = 900 | 模糊度初始方差 |
| STD_PREC_VAR_THRESH | 0 | SPP 跳过阈值 (0=永不跳过) |
| RE_WGS84 | 6378137.0 | 地球长半轴 (m) |
| FREQL1 | 1.57542e9 | L1 频率 (Hz) |
| FREQL2 | 1.22760e9 | L2 频率 (Hz) |
| CLIGHT | 299792458.0 | 光速 (m/s) |