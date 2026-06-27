# RTK调试记录

## 测试环境
- 数据源：RTCM3 MSM4流（BDS-only，13颗卫星）
- 对比基准：RTKLIB C版（2.5.0）rnx2rtkp RTK定位
- 历元数：212
- 基准站坐标：硬编码 C 版 SPP 平均值（待实现 avepos）
- 验证标准：Java版与C版定位结果差异

---

## 当前状态

### 定位精度

| 条件 | 历元数 | 3D RMS |
|------|--------|--------|
| 全部历元 | 212 | 23.6m |
| 排除异常值(>20m) | 177 | 6.9m |
| 充分收敛(>30min, <10m) | 78 | 5.4m |

正常历元精度 1-5m，但 16.5% 的历元偏差>20m，71% 的异常历元与低卫星数相关。

### Outlier 与模糊度重置统计

| 指标 | Java 版 | C 版 | 比率 |
|------|---------|------|------|
| Outlier 检测次数 | 479 | 224 | 2.1x |
| 模糊度重置次数 | 222 | 158 | 1.4x |

最频繁被重置的卫星：C14(33次)、C27(31次)、C17(28次)

---

## Bug #1：周跳检测函数未被调用（最关键）

### 文件
`src/main/java/org/rtklib/java/rtkpos/RtkCore.java` — `udbias()` 方法

### 问题
C 版 `udbias()` 中调用了 4 个周跳检测函数，Java 版一个都没调用：

| C 版函数 | 功能 | Java 版状态 |
|----------|------|-------------|
| `detslp_ll()` | LLI 标志检测周跳 | ❌ 未调用 |
| `detslp_gf()` | 几何无关组合检测周跳 | ❌ 未调用 |
| `detslp_code()` | 码类型变化检测周跳 | ❌ 未调用 |
| `detslp_dop()` | 多普勒-相位差检测周跳 | ❌ 未调用 |

Java 版有 `CycleDetect` 类（`src/main/java/org/rtklib/java/cycle/CycleDetect.java`）实现了部分功能，
但 `RtkCore.udbias()` 中没有调用它。

### C 版参考代码（rtkpos.c）
```c
static void udbias(rtk_t *rtk, double tt, const obsd_t *obs, const int *sat,
                   const int *iu, const int *ir, int ns, const nav_t *nav) {
    // 清除 slip 标志
    for (i=0;i<ns;i++) for (k=0;k<nf;k++) rtk->ssat[sat[i]-1].slip[k]&=0xFC;

    // 周跳检测
    detslp_dop(rtk,obs,iu,ns,1,nav);   // rover: 多普勒-相位差
    detslp_dop(rtk,obs,ir,ns,2,nav);   // base:  多普勒-相位差
    for (i=0;i<ns;i++) {
        detslp_code(rtk, obs, iu[i], 1);  // rover: 码类型变化
        detslp_code(rtk, obs, ir[i], 2);  // base:  码类型变化
        detslp_ll(rtk,obs,iu[i],1);       // rover: LLI标志
        detslp_ll(rtk,obs,ir[i],2);       // base:  LLI标志
        detslp_gf(rtk,obs,iu[i],ir[i],nav); // GF组合
    }
    // ... 后续处理 ...
}
```

### Java 版当前代码（RtkCore.java）
```java
private static void udbias(Rtk rtk, double tt, Obsd[] obs, int[] sat, int[] iu, int[] ir,
                           int ns, Nav nav, int nf) {
    PrcOpt opt = rtk.opt;
    for (int i = 0; i < ns; i++) {
        for (int k = 0; k < nf; k++) {
            rtk.ssat[sat[i] - 1].slip[k] &= 0xFC;  // 只清除，不检测
        }
    }
    // 缺失：detslp_dop, detslp_code, detslp_ll, detslp_gf

    for (int k = 0; k < nf; k++) {
        // ... outlier/slip 重置逻辑（依赖 slip 标志，但 slip 标志从未被更新）...
    }
}
```

### 影响链
```
周跳漏检 → 模糊度状态偏差累积 → 残差偏大
→ 超过 outlier 阈值 → 被标记为 outlier
→ 触发模糊度重置 → 重新收敛 → 精度下降
→ 再次触发 outlier → 恶性循环
```

### 修复方案
在 `udbias()` 中插入 5 个新方法（`detslp_dop`, `detslp_code`, `detslp_ll`, `detslp_gf`, `gfobs`），
调用位置在 `slip[k] &= 0xFC` 之后、`for (int k = 0; k < nf; k++)` 之前。

### 预期效果
- Outlier 检测次数：479 → 接近 224（~50%减少）
- 模糊度重置次数：222 → 接近 158（~30%减少）
- 异常历元(>20m)：16.5% → 预期 5-8%
- 整体 3D RMS：23.6m → 预期 8-12m

---

## Bug #2：rtk.sol.time 未更新导致 Kalman 滤波器冻结（已修复）

### 文件
`src/main/java/org/rtklib/java/rtkpos/RtkCore.java` — `rtkpos()` 方法

### 问题
C 版 rtkpos.c 中 P[0]!=0 时更新 `rtk->sol.time = obs[0].time`，Java 版缺失此 else 分支。

### 影响链
1. `rtk.sol.time` 不更新 → `rtk.tt = timediff(rtk.sol.time, prevTime)` 永远为 0
2. `tt=0` → `udpos()` 中状态转移矩阵 F 的速度项 `F[i*(i+3)] = tt = 0`，位置不传播
3. `tt=0` → 过程噪声 `Q = prn^2 * |tt| = 0`，协方差矩阵 P 不增长
4. `tt=0` → `udbias()` 中模糊度过程噪声也为 0
5. 结果：Kalman 滤波器完全冻结

### 修复
```java
} else {
    rtk.sol.time = obs[0].time;
}
```

---

## Bug #3：varerr() 观测噪声模型不完整（已修复）

### 文件
`src/main/java/org/rtklib/java/rtkpos/RtkCore.java` — `varerr()` 方法

### 问题 3a：缺少 SNR 调整和接收机噪声项
C 版 varerr() 包含 SNR 调整项 `10^(0.1*(thresh - snr))` 和接收机标准差项 `err[6]/err[7]`，Java 版缺失。

### 问题 3b：星座因子硬编码且 IRNSS 错误
Java 版使用硬编码值，IRNSS 用 1.0 而非 C 版的 1.5，缺少 GPS 和 SBS 分支。

### 修复
- 添加 SNR 调整项和接收机标准差项
- 使用 `Constants.EFACT_*` 常量替代硬编码
- 添加所有星座分支

### 注意
默认配置下 `err[6]=0.0` 和 `err[7]=0.0`，SNR/接收机噪声项不影响结果。
但星座因子差异会影响所有情况。

---

## Bug #4：对流层映射函数简化（已修复）

### 文件
`src/main/java/org/rtklib/java/rtkpos/RtkCore.java` — `zdres()` 方法

### 问题
Java 版使用简化映射函数 `1/sin(el)` 替代 C 版的 NMF（Niell Mapping Function）。

### 数值差异

| 高度角 | 1/sin(el) | NMF | 差异 |
|--------|-----------|-----|------|
| 10° | 5.76 | ~5.5 | ~0.26 |
| 15° | 3.86 | ~3.7 | ~0.16 |
| 20° | 2.92 | ~2.8 | ~0.12 |
| 30° | 2.00 | ~1.95 | ~0.05 |

### 修复
使用 `TroposphereModel.tropmapf()` 替代 `1/sin(el)`。

### 实测影响
映射函数差异（0.01~0.06）不足以单独解释 14m 偏差，但会与其他因素累积。

---

## 待排查问题

### 🟡 P1：Kalman 滤波增益计算

**文件**: `src/main/java/org/rtklib/java/kalman/KalmanFilter.java`

**关注点**:
- 状态压缩（ix 数组）是否正确映射
- P 矩阵更新（Joseph 形式）的索引计算
- 数值精度差异（float vs double）
- 需逐历元对比 C 版 trace 输出与 Java 版的 H/v/R 矩阵

### 🟢 P2：充分收敛历元的 5.4m RMS

即使排除异常值、充分收敛(>30min, <10m)的 78 个历元，3D RMS 仍有 5.4m。
正常 RTK 收敛后应为 cm 级（0.01-0.05m），说明还有更深层的问题。

可能原因：
- Kalman 滤波矩阵运算差异（行优先 vs 列优先）
- 观测噪声 R 矩阵差异导致 Kalman 增益偏小
- 对流层/电离层修正的累积差异

### 🟢 P3：基准站位置自动获取

Java 版缺少 `antpos()` / `avepos()` 的等价实现，目前测试中硬编码 C 版 SPP 平均坐标。
需要实现自动从 RINEX 头或 SPP 平均获取基准站位置。

---

## 修复优先级汇总

| 优先级 | 问题 | 文件 | 状态 | 影响 |
|--------|------|------|------|------|
| 🔴 | 周跳检测未调用 | RtkCore.udbias() | ❌ 待修复 | 恶性循环根源 |
| ✅ | rtk.sol.time 未更新 | RtkCore.rtkpos() | ✅ 已修复 | Kalman 冻结 |
| ✅ | varerr() 不完整 | RtkCore.varerr() | ✅ 已修复 | 噪声模型 |
| ✅ | 对流层映射函数 | RtkCore.zdres() | ✅ 已修复 | 对流层延迟 |
| 🟡 | Kalman 增益计算 | KalmanFilter.java | ⚠️ 待验证 | 滤波精度 |
| 🟢 | 基准站位置自动获取 | 待实现 | ⚠️ 待实现 | 自动化 |

---

## 调试过程

### 阶段1：14m 偏差初步排查
初始 Java 版与 C 版 RTK 定位偏差达 14m，逐步排查：
1. 状态向量定义差异 → 验证等价（不是原因）
2. 基准站坐标差异（5m）→ 硬编码 C 版坐标
3. 对流层映射函数差异 → 修复但效果有限（0.01~0.06 差异）

### 阶段2：时间同步问题发现
发现 Java 输出文件中 240 行全是同一时间戳，而 C 版每历元时间不同。
定位到 `rtk.sol.time` 未更新 → `rtk.tt=0` → Kalman 滤波器冻结。

### 阶段3：观测噪声模型修复
对比 C 版 varerr() 发现：
- 缺少 SNR 调整项
- 星座因子硬编码且 IRNSS 错误
- 修复后观测噪声模型与 C 版一致

### 阶段4：异常定位行为分析
修复时间同步和噪声模型后，偏差仍较大。统计发现：
- Outlier 检测 479 次（C 版 224 次，2.1x）
- 模糊度重置 222 次（C 版 158 次，1.4x）
- C14/C27/C17 频繁被重置（33/31/28 次）

### 阶段5：周跳检测缺失定位
对比 C 版 udbias() 发现 4 个周跳检测函数全部缺失。
这是 Outlier 频发和模糊度反复重置的根本原因。