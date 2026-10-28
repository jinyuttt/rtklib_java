# GNSS 引擎自适应方案设计文档 (v3.0)

> 版本: 3.0 | 日期: 2026-10-23 | 状态: 设计定稿
> 修订: 2026-10-28 — `RtkConfig` 拆分为 7 个子配置后，本文所有配置字段名改为带命名空间前缀（`cfg.adaptive.*` / `cfg.pppAr.*` / `cfg.pppRtk.*` 等）
> 替代: docs/archive/gnss-adaptive-design.md (v2.0)

---

## 一、设计目标

**一句话**：根据信号质量自动切换精密/鲁棒策略，不区分退化原因。

| 信号质量 | 策略 | 参数方向 |
|---------|------|---------|
| 好 | 精密 | 紧门限 + 不抗差 + 积极固定 |
| 差 | 鲁棒 | 松门限 + 抗差 + 降权 + 保守固定 |

---

## 二、能力边界

### 引擎能做的

| 能力 | 说明 |
|------|------|
| 检测信号质量退化 | nSat↓ / avgSnr↓ |
| 切换精密/鲁棒策略 | 抗差 + 降权 + 放宽门限 |
| 检测信号质量恢复 | 指标回到正常，切回精密 |
| 检测基线模式不匹配 | 固定率异常低 + 新息系统偏大 |
| 切换基线模式 | Hard Reset + 重新收敛 |
| 检测产品退化 | OSB 缺失 / SSR 超时 |
| 记录审计 | 所有切换的完整证据链 |

### 引擎不能做的

| 不能做 | 原因 | 用户应该 |
|--------|------|---------|
| 区分退化原因 | 峡谷/电离层暴/降雨症状相同 | 不需要区分，响应方向一致 |
| 判断静态/动态 | 位置变化可能是噪声 | 启动前设定 `dynamics` |
| 判断基线长度 | 需要基站坐标先验 | 启动前设定 `ionoopt`（或信任引擎自动切换） |
| 判断对流层模型 | 需要气象先验 | 启动前设定 `tropopt` |
| 判断地理/气象 | 需要外部传感器 | 启动前设定相关参数 |
| 判断日夜 | 需要时间+位置先验 | 不需要，日夜变化缓慢 |

---

## 三、场景与模式

### 3.1 场景（2 个）

```java
enum GnssScene {
    OPEN_SKY,       // 信号好 → 精密策略
    URBAN_CANYON    // 信号差 → 鲁棒策略
}
```

不区分退化原因（峡谷/电离层暴/降雨/闪烁），因为正确响应方向一致。

### 3.2 模式（5 个）

```java
enum AdaptiveMode {
    SPP,        // PMODE_SINGLE / DGPS
    RTK,        // PMODE_KINEMA / STATIC
    PPP,        // PMODE_PPP + 无AR
    PPP_AR,     // PMODE_PPP + cfg.pppAr.enableAR/enablePartialAR/enableBds3AR 任一开启
    PPP_RTK     // PMODE_PPPRTK
}
```

PPP-AR 定义为独立定位模式，不是 PPP 的优化。用户开启 AR 开关即选择此模式。
PPP-AR 可能因 OSB 产品缺失退化到 PPP，引擎检测并记录。

### 3.3 模式判定（启动时一次）

```java
static AdaptiveMode resolve(int pmode, RtkConfig cfg) {
    if (pmode == PMODE_SINGLE || pmode == PMODE_DGPS) return SPP;
    if (pmode == PMODE_KINEMA || pmode == PMODE_STATIC
        || pmode == PMODE_STATIC_START || pmode == PMODE_MOVEB
        || pmode == PMODE_FIXED) return RTK;
    if (pmode == PMODE_PPPRTK_KINEMA || pmode == PMODE_PPPRTK_STATIC) return PPP_RTK;
    // PMODE_PPP_KINEMA / PMODE_PPP_STATIC / PMODE_PPP_FIXED 按 AR 开关归类
    boolean hasAr = cfg.pppAr.enableAR || cfg.pppAr.enableFixHold
                    || cfg.pppAr.enablePartialAR || cfg.pppAr.enableBds3AR || cfg.pppAr.enableMultiFreqAR;
    return hasAr ? PPP_AR : PPP;
}
```

---

## 四、特征提取与场景判定

### 4.1 特征（2 个）

| 特征 | 来源 | 计算成本 |
|------|------|---------|
| nSat | 观测数据 | 零 |
| avgSnr | 观测数据 | 零 |

多系统 SNR 统一处理，不区分系统。用户如需细分可通过黑名单锁 avgSnr。

### 4.2 滑动平均

```java
slidingNSat  = 0.9 * slidingNSat  + 0.1 * currentNSat;
slidingSnr   = 0.9 * slidingSnr   + 0.1 * currentSnr;
```

判定用平滑后特征，抗瞬态抖动。

### 4.3 滞后区间（Hysteresis）

OPEN→CANYON 和 CANYON→OPEN 用不同阈值，防卫星升降抖动：

| 阈值 | 含义 | 建议值 |
|------|------|--------|
| NSAT_OPEN_TO_CANYON | 开阔→峡谷：nSat 低于此值才切 | 8 |
| NSAT_CANYON_TO_OPEN | 峡谷→开阔：nSat 高于此值才切回 | 14 |
| SNR_OPEN_TO_CANYON | 开阔→峡谷：avgSnr 低于此值才切 | 30 dB-Hz |
| SNR_CANYON_TO_OPEN | 峡谷→开阔：avgSnr 高于此值才切回 | 34 dB-Hz |

四个阈值均可通过 `RtkConfig.adaptive` 的 `nsatOpenToCanyon` / `snrOpenToCanyon` /
`nsatCanyonToOpen` / `snrCanyonToOpen` 现场调整（即 `cfg.adaptive.*`，默认值即上表）。

> 标定依据（2026-10-24 真实数据实测，EMA α=0.1）：opensky 数据的平滑 avgSnr 峰值仅
> **37.90 dB-Hz**，原阈值 38 在数学上不可达，导致开阔场景永远无法切回 OPEN_SKY。
> 取 34 时 opensky 有 233 个历元满足且 downtown（峰值 32.39）零误报；取 32 则 downtown
> 开始出现 14 个历元误报。因此默认值重标定为 34。

中间地带（8 < nSat < 14 且 30 < avgSnr < 34）保持当前场景不切换。

### 4.4 判定规则

进入敏感（任一指标触发即切 CANYON）、退出保守（双指标同时满足才切回 OPEN），有意为之：卫星数和信噪比可能不同步恶化，任一变差即应切换鲁棒策略；切回精密则要求信号全面恢复。

```java
GnssScene detect(FeatureMetrics m, GnssScene current) {
    if (current == OPEN_SKY) {
        if (m.nSat < NSAT_OPEN_TO_CANYON || m.avgSnr < SNR_OPEN_TO_CANYON) {
            return URBAN_CANYON;  // 进入敏感：||
        }
    } else {
        if (m.nSat > NSAT_CANYON_TO_OPEN && m.avgSnr > SNR_CANYON_TO_OPEN) {
            return OPEN_SKY;      // 退出保守：&&
        }
    }
    return current;
}
```

追踪日志记录触发指标（如 `trigger=nSat:7<8` 或 `trigger=nSat:26>14,snr:37.7>34`）。

### 4.5 初始场景

启动时无特征数据，初始场景 = **URBAN_CANYON**（保守），先用鲁棒参数保安全，确认信号好再切精密。

> 已知代价：PPP/PPP-AR 在初始 CANYON 下使用松门限+抗差参数，收敛期可能略长（因抗差降权减少有效观测）。这是保守优先的安全选择。引擎在首个确认窗口内若检测到 OPEN_SKY 条件满足，会尽快切回精密参数加速收敛。

---

## 五、窗口与冷却

### 5.1 API（历元数）

```java
public int windowEpochs = 0;    // 0 = 按模式默认（AdaptiveOpts）
public int cooldownEpochs = 0;  // 0 = 按模式默认（AdaptiveOpts）
```

用户设历元数，引擎自动检测采样间隔，启动时输出配置摘要：

```
[ADAPTIVE] 配置摘要:
  模式: RTK
  历元间隔: 1.000s (1.0Hz)
  判定窗口: 30历元 × 1.000s = 30.0s
  冷却窗口: 90历元 × 1.000s = 90.0s
```

### 5.2 模式默认值

| 模式 | 窗口(历元) | 冷却(历元) | @1Hz 物理时间 |
|------|-----------|-----------|-------------|
| SPP | 10 | 10 | 10s / 10s |
| RTK | 30 | 90 | 30s / 90s |
| PPP | 60 | 300 | 60s / 300s |
| PPP_AR | 60 | 300 | 60s / 300s |
| PPP_RTK | 30 | 90 | 30s / 90s |

### 5.3 采样间隔检测

引擎前 3~5 个历元自动检测采样间隔，输出间隔分布，异常时 WARN：

```
[ADAPTIVE] 历元间隔分布 (前30历元):
  1.000s: 29次 (100.0%)
  主间隔: 1.000s (1.0Hz)
```

设备配置错误时：

```
[ADAPTIVE] 历元间隔分布 (前30历元):
  15.000s: 29次 (100.0%)
  主间隔: 15.000s (0.067Hz)
  ⚠ 实际采样间隔15.000s，当前配置: 30历元 × 15.000s = 450.0s (7分30秒)
```

采样间隔异常时仅 WARN，不自动钳制物理窗口。理由：钳制会静默改变用户意图，且反算历元数过少（如 120s/15s=8 历元）导致判定不可靠。根本问题是用户配置错误，应通过 WARN 提示用户自行调整 `cfg.adaptive.windowEpochs`。

---

## 六、参数分类与自适应范围

### 6.1 三层参数

| 层 | 含义 | 例子 | 自适应可调？ |
|---|------|------|-------------|
| **A 层** | 模式固有，改变状态向量维度 | ionoopt, nf, sateph, tropopt, dynamics | ✅ 可调，但需更严格确认（5分钟+3/4指标+30分钟冷却+Hard Reset） |
| **B 层** | 模式专属可调 | thresar, modear, prn, elmaskhold, pppArNlMaxdev | ✅ 可调，受黑名单约束 |
| **C 层** | 跨模式通用 | thresslip, maxinno, elmin, enableIggiii, enableSnrMedian, enableSegmentSigmaFilter, enableAdaptiveQ | ✅ 可调，受黑名单约束 |

### 6.2 优化项归属

| 类别 | 判断依据 | 优化项 | 引擎管？ |
|------|---------|--------|---------|
| **场景驱动** | 场景 | enableIggiii, enableSnrMedian, enableSegmentSigmaFilter, enableAdaptiveQ, enableSppEkf, enableSppRobust | ✅ 管开关 |
| **用户驱动** | 用户配置 | GPT3, OSB, ISB/IFCB, FixHold, PartialAR, 3F-AR, 大气潮, 零速 | ❌ 不碰 |

**引擎只管场景驱动的 6 个优化项开关。**

---

## 七、各模式×场景参数预设

### 7.1 SPP

| 参数 | OPEN_SKY | URBAN_CANYON |
|------|---------|-------------|
| thresslip | 0.05 | 0.10 |
| maxinno[0] | 30.0 | 50.0 |
| maxinno[1] | 30.0 | 50.0 |
| elmin | 15° | 20° |
| enableSppEkf | false | true |
| enableSppRobust | false | true |
| enableSppInnovChisq | true | true |
| sppInnovOutlierThresh | 5.0 | 7.0 |

### 7.2 RTK

| 参数 | OPEN_SKY | URBAN_CANYON |
|------|---------|-------------|
| thresslip | 0.05 | 0.10 |
| maxinno[0] | 30.0 | 50.0 |
| maxinno[1] | 30.0 | 50.0 |
| elmin | 15° | 20° |
| enableIggiii | false | true |
| enableSnrMedian | false | true |
| enableSegmentSigmaFilter | false | true |
| enableAdaptiveQ | dynamics==1 | true |

**RTK B 层参数（thresar, modear, prn, elmaskhold, dynamics）引擎不碰，用户先验。**
引擎通过黑名单推荐回调建议调整。

**RTK 用户必须提供的先验**：
- 基线长度（短/长）→ ionoopt, nf（或信任引擎自动切换）
- 运动状态（静态/动态）→ dynamics, prn[], enableAdaptiveQ
- AR 策略 → thresar[0], modear, elmaskhold
- 对流层模型 → tropopt
- 地理/气象 → atmFrozenNsThresh

### 7.3 PPP

| 参数 | OPEN_SKY | URBAN_CANYON |
|------|---------|-------------|
| thresslip | 0.05 | 0.10 |
| maxinno[0] | 5.0 | 10.0 |
| maxinno[1] | 30.0 | 50.0 |
| elmin | 15° | 20° |
| enableIggiii | false | true |
| enableSnrMedian | false | true |

### 7.4 PPP-AR

| 参数 | OPEN_SKY | URBAN_CANYON |
|------|---------|-------------|
| thresslip | 0.05 | **0.05**（不放宽） |
| maxinno[0] | 5.0 | **7.0**（适度） |
| maxinno[1] | 30.0 | 50.0 |
| elmin | 15° | 20° |
| enableIggiii | false | true |
| enableSnrMedian | false | true |
| pppArNlMaxdev | 0.15 | **0.15**（不放宽，安全红线） |
| pppArNlMaxsig | 0.15 | **0.15**（不放宽） |
| pppArWlMaxdev | 0.20 | **0.20**（不放宽） |

**PPP-AR 安全红线**：AR 门限不放宽。宁可固定率下降，也不引入假 Fix。
峡谷策略：开抗差/降权对抗多路径，AR 门限不变，AR 自然固定率下降或归零，自动回退浮点。

### 7.5 PPP-RTK

| 参数 | OPEN_SKY | URBAN_CANYON |
|------|---------|-------------|
| thresslip | 0.05 | 0.10 |
| maxinno[0] | 5.0 | 7.0 |
| maxinno[1] | 30.0 | 50.0 |
| elmin | 15° | 20° |
| enableIggiii | false | true |
| enableSnrMedian | false | true |

---

## 八、切换安全

### 8.1 C 层切换（Soft Change）

场景切换只改 C 层参数 → 不重置滤波器 → 无收敛中断。

### 8.2 A 层切换（Hard Reset）

基线模式切换改 A 层参数 → 重置滤波器 → 需重新收敛。

| 安全措施 | 值 |
|---------|---|
| 确认窗口 | 300s（5 分钟） |
| 触发条件 | 3/4 指标交叉验证 |
| 确认次数 | 连续 3 个窗口 |
| 冷却期 | 1800s（30 分钟） |

基线不匹配检测指标（短基线模式下，仅检测 SHORT→LONG 单向）：

| 指标 | 短基线预期 | 长基线表现 | 阈值 |
|------|-----------|-----------|------|
| 相位新息均值 | < 0.05 m | > 0.2 m | 0.15 m |
| 固定率 | > 50% | < 20% | 25% |
| 浮点模糊度方差均值 | < 0.01 cyc² | > 0.1 cyc² | 0.05 cyc² |
| 收敛后位置RMS | < 0.1 m | > 0.5 m | 0.3 m |

仅检测 SHORT→LONG 单向：长基线误用短基线参数会导致固定解错误（危险），短基线误用长基线参数只是精度浪费（安全），故反向不触发。

### 8.3 静态模式保护

`enableAdaptiveScene=true` 即生效，引擎自动按 `dynamics` 识别静态模式并采用更严格的窗口与回滚保护。用户如不信任可整体关闭自适应，或用黑名单锁定具体参数。

> 此处为场景切换（C 层）窗口，A 层基线模式切换仍按 §8.2 的 300s/1800s。
> `isStatic(dynamics)` 依赖用户启动前设定的 `dynamics`（§二 已明确"引擎不能判断静态/动态，用户启动前设定"）。若用户未正确设定 dynamics，静态保护不生效，属用户先验缺失，引擎不推断。

静态模式自动参数（无需用户配置）：

| 条件 | 动态 | 静态 |
|------|------|------|
| 判定窗口 | 30 历元 | **100 历元**（自动提升） |
| 冷却窗口 | 90 历元 | **900 历元**（自动提升） |
| 进入 CANYON 阈值 | nSat < 8 | **nSat < 5**（更保守） |
| 切回 OPEN 阈值 | 同 §4.4（nSat>14 且 SNR>34） | **同 §4.4，不额外加严**（避免长期困在 CANYON） |
| 确认次数 | 1 个窗口 | **连续 3 个窗口** |
| 切换后 | 正常 | **位置跳变检测** |

窗口自动提升规则：

```java
int win = cfg.adaptive.windowEpochs;         // 0 = 按模式默认
if (win == 0) win = PresetManager.defaultWindow(mode);
if (isStatic(dynamics) && win < 100) {
    win = 100;   // 静态模式窗口过小自动提升
    logger.info("[ADAPTIVE] 静态模式窗口自动提升: {} → 100 历元", cfg.adaptive.windowEpochs);
}
```

冷却窗口同理（静态 ≥900 历元）。用户显式设置大于该值时以用户值为准（提升只针对"过小"）。

位置跳变 > 3σ 自动回滚：

```java
if (jump > 3.0 * posStd) {
    rollbackSwitch();
    auditLogger.logRollback(epoch, "位置跳变: " + jump + "m");
}
```

建议回调（只推荐、不执行）：静态模式下若检测到窗口被自动提升或场景频繁切换，通过 `onParamRecommendation` 输出建议：

```
[ADAPTIVE-RECOMMEND] epoch=1230 STATIC 模式
  建议（未执行）:
  cfg.adaptive.windowEpochs: 0 → 300   (静态解对误切敏感，建议加长窗口)
  cfg.adaptive.cooldownEpochs: 0 → 1800
```

---

## 九、退化检测

### 9.1 PPP-AR → PPP

OSB 产品缺失时 PPP-AR 退化到 PPP：

```
[ADAPTIVE] DEGRADATION epoch=5000
  PPP_AR → PPP
  原因: OSB产品不可用
```

OSB 产品恢复后自动回切 PPP_AR，AR 状态重置：

```java
rtk.nfix = 0;
for (int i = 0; i < Constants.MAXSAT; i++) {
    for (int j = 0; j < opt.nf; j++) {
        rtk.ssat[i].fix[j] = 0;
    }
}
```

回切逻辑：PPP 浮点解在退化期间持续运行未中断，OSB 恢复后 AR 从当前浮点模糊度重新尝试固定，自然在几个窗口内收敛到固定解。不 Hard Reset（未改状态向量维度），不丢弃 PPP 浮点解。

### 9.2 PPP-RTK SSR 超时

SSR 年龄超过 ssrMaxAge 时记录（不改变模式，SSR 可能恢复）：

```
[ADAPTIVE] DEGRADATION epoch=3000
  PPP_RTK SSR超时, age=120s > maxAge=60s
```

SSR 恢复后自动清除超时标记，无需额外操作（PPP-RTK 滤波器持续运行，SSR 恢复后自然使用新 SSR 纠正）。

---

## 十、白名单/黑名单

### 10.1 双 API

```java
public Set<String> managedParams = null;   // 白名单（优先级高，AdaptiveOpts）
public Set<String> lockedParams = null;    // 黑名单（AdaptiveOpts）
```

解析优先级：

1. 用户显式白名单 → 用白名单
2. 用户黑名单 → 从默认可调参数中移除黑名单
3. 都没设 → 默认全部可调

### 10.2 使用示例

```java
// 全自动（默认）
cfg.adaptive.enableScene = true;

// 只让自适应管 3 个参数（白名单）
cfg.adaptive.managedParams = Set.of("thresslip", "maxinno", "enableIggiii");

// 锁住 1 个参数（黑名单）
cfg.adaptive.lockedParams = Set.of("enableIggiii");
```

### 10.3 黑名单参数推荐回调

引擎不碰黑名单参数，但场景切换时通过回调推荐调整建议：

```java
public interface AdaptiveListener {
    void onSceneSwitch(AdaptiveEvent event);
    void onDegradation(AdaptiveEvent event);
    void onParamRecommendation(AdaptiveRecommendation rec);
    void onBaselineSwitch(AdaptiveEvent event);
}
```

推荐示例（仅供参考，用户需结合实际评估）：

```
[ADAPTIVE-RECOMMEND] epoch=1230 OPEN_SKY → URBAN_CANYON
  黑名单参数建议（未执行，请用户评估）：
  thresar[0]: 3.0 → 2.5       (开阔天空下ratio门限可适度放宽)
  modear: FIXHOLD → CONTINUOUS (峡谷动态下建议不锁定固定解)
  elmaskhold: 0° → 15°        (排除低仰角多路径)
```

---

## 十一、开关机制：覆盖层

引擎不直接改 RtkConfig 字段，通过覆盖层管理：

```java
public class AdaptiveOverride {
    private final Map<String, Object> overrides = new LinkedHashMap<>();
    private final Map<String, Object> originals = new LinkedHashMap<>();

    void snapshot(RtkConfig cfg, PrcOpt opt);  // 启动时快照用户原始值
    void set(String key, Object value);         // 引擎设覆盖
    boolean getBoolean(String key, boolean userValue);  // 获取生效值
    void restoreAll(RtkConfig cfg, PrcOpt opt); // 关闭时恢复
}
```

生命周期：

```
启动: snapshot() → activate()
运行: set() 写覆盖，解算代码通过 getBoolean/getDouble 读生效值
关闭: restoreAll() → deactivate()
```

### 11.1 处理器接线点（v3.2.0 实现）

| 处理器 | 接线位置 | 绑定对象 | 说明 |
|--------|---------|---------|------|
| PostPosProcessor | processForward / processBackward / processCombined 三通道历元循环（rtkpos 调用后） | rtk.opt / rtk.rtkConfig（createRtk 内部副本） | RINEX 批处理漏斗，RinexRtk/Ppp/SppProcessor 三个薄壳均经此 |
| PppProcessor | processEpoch 解算分支后（首个历元惰性启动） | this.opt / rtk.rtkConfig（用户引用，deactivate 恢复） | RTCM 流 / 批处理 / RINEX 三路径共用漏斗 |
| RtkProcessor | processEpoch 中 rtkpos 后（首个历元惰性启动） | this.opt / rtk.rtkConfig（用户引用，deactivate 恢复） | 流式 RTK 漏斗；adaptiveCtl 字段 transient 不参与序列化 |
| SppProcessor | processEpoch 中 pntpos 后（首个历元惰性启动） | this.opt / this.rtkConfig（用户引用，deactivate 恢复） | 无 Rtk 对象，onObsEpoch 传 rtk=null，静态保护与解发散监控自动跳过 |

- **启动方式**：`AdaptiveController.startIfEnabled(opt, cfg/rtk)` 静态工厂，`enableAdaptiveScene=false` 或 cfg=null 时返回 null；全部调用点 `if (adaptive != null)` 守卫 → 关闭时零干预、零开销。
- **特征提取**：`onObsEpoch(epoch, obs, n, rtk)`，nSat = 非基准站（rcv≠2）观测条数，avgSnr = 首频点 SNR[0]>0 均值，与测试 harness 口径一致。
- **关闭时机**：finishInternal / reset / resetForNextBatch 统一 deactivate() → restoreAll() 恢复用户参数快照；RINEX 批处理在三通道循环结束后 deactivate。
- **已知边界**：PppProcessor.processBatchCombined 与 RtkProcessor.processBatchCombined / reprocess 的反向循环直接调用 rtkpos、未经 processEpoch，未接线（次级再处理路径，前向通道已覆盖）。

`enableAdaptiveScene=false` 时覆盖层 active=false，直接返回用户值，零开销。

---

## 十二、优化项效果跟踪

### 12.1 统计收集器

```java
public class OptimizationStats {
    public int iggiiiDownweighted;
    public int iggiiiRejected;
    public int snrDownweighted;
    public int segArcsRejected;
    public int arAttempted;
    public int arFixed;
    public int arDeltaGateRejected;
}
```

各优化模块每历元填写，自适应引擎定期读取滑动平均。

### 12.2 安全监控

| 条件 | 行为 |
|------|------|
| 优化项开启但无效 | 不关（无害） |
| 优化项过度降权 | WARN + 审计 + 回调 |
| 优化项过度降权 **且** 解发散 | **自动关闭** |

降权率分母 = 参与该历元 KF update 的观测数（nv）：

```java
double iggiiiDownweightRate = (double) iggiiiDownweighted / nv;
double segRejectRate = (double) segArcsRejected / totalArcs;
```

解发散判据 = 位置方差 trace 连续增长 3 历元，且当前值 > 基线 3 倍：

```java
double posVarTrace = rtk.P[0] + rtk.P[nx+1] + rtk.P[2*nx+2];

if (posVarTrace > prevPosVarTrace) {
    divergingCount++;
} else {
    divergingCount = 0;
}
prevPosVarTrace = posVarTrace;

boolean isDiverging = divergingCount >= 3 && posVarTrace > baselinePosVarTrace * 3.0;
// baselinePosVarTrace = 最近100历元位置方差trace的最小值（代表稳态精度）
```

安全阈值：

| 指标 | 上限 |
|------|------|
| IGGIII 降权率 | 50% |
| 段σ 剔除率 | 30% |
| SNR 最低权 | 0.01 |

---

## 十三、日志体系

### 13.1 三层日志

| 层 | 输出位置 | 内容 | 始终开启？ |
|---|---------|------|-----------|
| 控制台 | SLF4J INFO | 切换摘要 + 推荐 + 退化 + 回滚 + 异常 | ✅ cfg.adaptive.verbose=true（低频事件，不扰） |
| 审计文件 | CSV 文件 | 完整事件记录 + 参数前后对比 | ✅ cfg.adaptive.auditEnabled=true |
| 追踪文件 | CSV 文件 | 逐窗口特征值 + 优化效果 | ❌ cfg.adaptive.traceAll=true |

### 13.2 审计日志格式

```
# RTKLIB-Java Adaptive Audit Log
# version: 3.2.0  ← 软件版本号，非本文档版本
# mode: RTK
# sampleInterval: 1.000s (1.0Hz)
# adaptiveWindowEpochs: 30
# adaptiveWindowTime: 30.0s
# adaptiveCooldownEpochs: 90
# adaptiveCooldownTime: 90.0s
# initialScene: URBAN_CANYON
# managedParams: thresslip,maxinno[0],maxinno[1],elmin,enableIggiii,enableSnrMedian,enableSegmentSigmaFilter
# lockedParams: (none)
# sampleIntervalDistribution: 1.000s:29(100.0%)
#
# epoch | gpst | event | from | to | metrics | changes | reset
0001230,2024-01-01T00:20:30GPST,SCENE_SWITCH,OPEN_SKY,URBAN_CANYON,nSat=8|avgSnr=28.3,thresslip:0.05→0.10;maxinno[0]:30.0→50.0;enableIggiii:false→true,NONE
0001260,2024-01-01T00:21:00GPST,RECOMMEND,OPEN_SKY,URBAN_CANYON,,thresar[0]:3.0→2.5;modear:FIXHOLD→CONTINUOUS,
0005000,2024-01-01T01:23:20GPST,DEGRADATION,PPP_AR,PPP,,reason=OSB产品不可用,
0008000,2024-01-01T02:13:20GPST,BASELINE_SWITCH,SHORT,LONG,phaseInnovMean=0.32|fixRate=0.12|ambVar=0.45|posRms=0.62,HARD
```

> 注：文件头中的 `adaptiveWindowEpochs` / `adaptiveCooldownEpochs` 是**审计文件字段**（沿用历史命名，保证已归档日志可解析），与配置字段 `cfg.adaptive.windowEpochs` / `cooldownEpochs` 属不同命名空间。

### 13.3 审计日志轮转

| 配置 | 默认值 | 说明 |
|------|--------|------|
| cfg.adaptive.auditMaxSizeKB | 5120 (5MB) | 超过大小后轮转 |
| cfg.adaptive.auditMaxFiles | 3 | 保留 3 个历史文件 |

### 13.4 运行结束统计

```
[ADAPTIVE] 运行统计:
  总历元: 86400
  场景切换: 12次 (OPEN→CANYON: 5次, CANYON→OPEN: 5次, 基线: 2次)
  退化: 1次 (PPP_AR→PPP)
  回滚: 0次
  CANYON停留: 23400历元 (27.1%)
  优化效果: IGGIII降权8.3% SNR降权5.1% 段σ剔除4.2% AR固定率62.3%
```

---

## 十四、AdaptiveOpts 完整配置

`RtkConfig` 拆分后，自适应参数集中在 `RtkConfig.adaptive`（即 `AdaptiveOpts`），字段名已去掉原 `adaptive` 前缀：

```java
public class AdaptiveOpts {
    // 开关（原 enableAdaptiveScene）
    public boolean enableScene = false;

    // 窗口/冷却（历元数，0=按模式默认；原 adaptiveWindowEpochs / adaptiveCooldownEpochs）
    public int windowEpochs = 0;
    public int cooldownEpochs = 0;

    // 场景判定阈值（EMA 平滑特征；原 adaptiveNsat*/adaptiveSnr*）
    public int nsatOpenToCanyon = 8;
    public double snrOpenToCanyon = 30.0;
    public int nsatCanyonToOpen = 14;
    public double snrCanyonToOpen = 34.0;

    // 白名单/黑名单（原 adaptiveManagedParams / adaptiveLockedParams）
    public Set<String> managedParams = null;  // 白名单优先
    public Set<String> lockedParams = null;   // 黑名单次之

    // 审计日志（原 adaptiveAudit*）
    public boolean auditEnabled = true;
    public String auditDir = "";
    public int auditMaxSizeKB = 5120;
    public int auditMaxFiles = 3;

    // 诊断（原 adaptiveVerbose / adaptiveTraceAll）
    public boolean verbose = true;
    public boolean traceAll = false;

    // 回调（原 adaptiveListener）
    public transient AdaptiveListener listener = null;
}
```

共 16 个字段，访问方式为 `cfg.adaptive.xxx`。

---

## 十五、包结构

```
org.rtklib.java.adaptive/
├── AdaptiveController.java      // 主控：判定 + 切换 + 生命周期
├── AdaptiveOverride.java        // 覆盖层：原始值快照 + 引擎覆盖
├── AdaptiveMode.java            // 枚举：5 模式
├── GnssScene.java               // 枚举：2 场景
├── FeatureMetrics.java          // 特征：nSat + avgSnr + 滑动平均
├── PresetManager.java           // 参数预设：5模式×2场景参数表
├── OptimizationStats.java       // 优化项效果统计收集器
├── OptimizationMonitor.java     // 优化项安全监控
├── SampleIntervalDetector.java  // 采样间隔检测
├── AdaptiveAuditLogger.java     // 审计日志
├── AdaptiveEvent.java           // 事件：切换/退化/推荐
├── AdaptiveRecommendation.java  // 参数调整建议（黑名单参数回调载体）
├── AdaptiveListener.java        // 回调接口
└── AdaptiveState.java           // 状态查询
```

14 个类。

---

## 十六、性能开销

| 操作 | 频率 | 开销 |
|------|------|------|
| 特征提取 | 每历元 | 零（数据已有） |
| 滑动平均更新 | 每历元 | 2 次乘加 |
| 场景判定 | 每窗口 | 2 次比较 |
| 参数切换 | 极少 | Map 写入 |
| 覆盖层读取 | 每历元×每参数 | 1 次 boolean 判断 + 1 次 Map 查找 |
| 审计日志写入 | 极少 | 文件 I/O |

`enableAdaptiveScene=false` 时覆盖层 active=false，直接返回用户值，**零开销**。

---

## 十七、测试策略

| 阶段 | 内容 | 通过标准 |
|------|------|---------|
| 单元测试 | 场景判定、滞后区间、覆盖层、采样检测 | 全绿 |
| 回归测试 | 开自适应跑现有验证数据（OPEN_SKY 不改参数） | 精度不退化 |
| 切换测试 | 构造 nSat 变化序列 | 切换发生在预期窗口 |
| 静态保护测试 | 静态模式下窗口自动提升到 100；位置跳变 > 3σ 回滚；回调输出建议 | 提升生效、回滚触发、建议日志存在 |
| 审计完整性测试 | 验证审计日志包含所有事件 | 条目数 = 事件数 |

---

## 十八、PPP-AR 代码重构

### 方案 B（本轮实施）：轻量重构

```
org.rtklib.java.pppar/    ← 与 ppp、ppprtk 同级
├── PppArEngine.java      ← AR 总调度（从 PppCoreEx 抽出）
└── PppArConfig.java      ← AR 相关配置只读视图

org.rtklib.java.ppp/      ← 原包，不动
├── PppAmbFix.java        ← 保持原位，PppArEngine 通过 import 引用
└── PppAmbFixBds3.java    ← 保持原位，PppArEngine 通过 import 引用
```

PppCoreEx 瘦身：只管扩展状态建模，AR 调度委托给 PppArEngine。
不改算法逻辑，FIX-ALG-022~028 修复原封不动。

### 方案 C（待激活，写入路线图）：深度重构

PppArCore 完全独立包，PppCoreEx 完全瘦身，退化显式化。
触发条件：方案 B 运行稳定 + PPP-AR 测试覆盖充分（详见 ROADMAP §9.2）。

---

## 十九、策略文档（独立输出）

策略文档记录设计决策和推荐使用方式，不放代码：

- 场景判定规则和阈值
- 5 模式 × 2 场景参数预设表
- 白名单/黑名单使用示例
- 审计日志格式说明
- 回调接口使用示例
- 引擎能力边界说明
- 用户必须提供的先验清单
- 推荐使用路径

---

## 附录：v2.0 → v3.0 变更摘要

| # | v2.0 | v3.0 | 原因 |
|---|------|------|------|
| 1 | 3 场景（OPEN/CANYON/LANDSLIDE） | 2 场景（OPEN/CANYON） | LANDSLIDE 改 A 层参数=Hard Reset，且大气冻结是用户先验 |
| 2 | 30s 固定窗口 | 按模式默认+用户可调历元数 | 不同模式对误切/迟切容忍度不同 |
| 3 | mpResidual 代理指标 | 去掉，只用 nSat+avgSnr | 代理指标不可靠（受收敛影响） |
| 4 | 无滞后区间 | 双向不同阈值 | 防卫星升降抖动 |
| 5 | 无白名单/黑名单 | 双 API | 用户控制权 |
| 6 | 无回调 | AdaptiveListener | 通知+推荐 |
| 7 | 无审计文件 | 独立 CSV 文件+轮转 | 事后追查 |
| 8 | 无覆盖层 | AdaptiveOverride | 保护用户原始值 |
| 9 | 无静态保护 | 自动长窗口+3σ回滚+建议回调（无二次确认） | 静态解稳定性 |
| 10 | 无优化项跟踪 | OptimizationStats+安全监控 | 知道优化项是否有效 |
| 11 | 无退化检测 | PPP-AR→PPP / SSR超时 | 运行时产品缺失 |
| 12 | 无基线模式切换 | 自动切换（可黑名单禁止） | 用户信任程序 |
| 13 | PPP-AR 不是独立模式 | 5 模式，PPP-AR 独立 | 参数空间不同+产品依赖不同 |
| 14 | 初始场景 OPEN_SKY | URBAN_CANYON | 保守优先 |
| 15 | 静态模式二次确认 | 去掉，改自动长窗口+建议回调 | 信任程序，避免静默不生效 |
| 16 | 退化只降不回 | OSB恢复自动回切(AR重置)，SSR恢复自动清除 | 产品临时中断常见，不应要求重启 |