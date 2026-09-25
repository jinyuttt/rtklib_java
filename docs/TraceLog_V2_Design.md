# GNSS 解算追踪日志系统 V2 — 设计与实现文档

## 1. 与 V1 的对比

### 1.1 V1 回顾

V1（`RTK_TraceLog_Design.md`）为 RTK 相对定位量身定制，核心特征：

| 维度 | V1 |
|------|-----|
| 覆盖范围 | 仅 RTK（`RtkCore.relpos()`） |
| 阶段模型 | 7 个 Stage，int 位掩码选择 |
| 过滤机制 | `stages & stageBit` 位运算 |
| 扩展方式 | 加 Stage 需改位掩码常量 + 加方法 |
| 诊断能力 | 无 |
| 统计能力 | 无 |
| 文件数 | 3（TraceControl, TraceCallback, RtkTrace） |

### 1.2 V2 改进

| 维度 | V2 | 改进点 |
|------|-----|--------|
| 覆盖范围 | SPP / RTK / PPP / PPP-AR / PPP-RTK / Adjust 全模式 | 一套机制覆盖所有解算模式 |
| 阶段模型 | topic + action 字符串，自由组合 | 加领域 = 加字符串，不改任何枚举/常量文件 |
| 过滤机制 | topic 白名单 + action 白名单（可选） | 语义更清晰：「我要看哪些领域」+「只看哪些事件」 |
| 扩展方式 | `Trace.emit("NEW_TOPIC", ...)` 一行即可 | 零侵入，不改已有结构 |
| 诊断能力 | 内联 3 条诊断规则（位置偏差、AR 偏移告警、失败累积） | 自动计算 dN/dE/dU/d3D/conv，无需后处理 |
| 统计能力 | TraceStats 累积 + SUMMARY 输出 | 收敛历元、固定率、失败原因分布一目了然 |
| 文件数 | 4（Trace, TraceConfig, TraceStats, TraceCallback） | ~325 行 |

### 1.3 关键设计决策

**为什么用字符串而非枚举？**

枚举的价值是编译期穷举和 switch。日志系统两者都不需要：
- 没人会 `switch` 所有 topic
- 没人需要编译器保证 topic 完整性
- 枚举的唯一效果是加一个 topic 要改一个文件，违背「增量扩展」原则

**为什么保留 Object... kv 而非 Builder？**

调试日志里 `"lat", lat` 写错的概率，低于 Builder 忘记 `.flush()` 的概率。类型错误在第一次运行时就会暴露，且日志不输出不影响解算。

**为什么不做 NOP 对象？**

`if (cfg == null || !cfg.enabled) return;` 一行快速返回，比维护一个空实现类更简单。

### 1.4 明确不做的事

| 不做 | 原因 |
|------|------|
| 类型安全 Builder | 调试日志不需要，增加复杂度 |
| 诊断规则插件接口 | 3 条规则 if 判断足够，加第 4 条加一个 if 块 |
| 精度映射表 | 会和文档不同步，精度是调用者的责任 |
| NOP 对象 | 一行 if 比空实现类更简单 |

---

## 2. 核心概念

三层，用字符串而非枚举：

```
topic   （关注领域）   → "POSITION" / "AR" / "SSR" ...
action  （事件类型）   → "START" / "UPDATE" / "FAIL" ...
kv      （自由字段）   → "sat=C08" / "ratio=3.2" ...
```

- `topic` 和 `action` 都是 `String`
- 加一个新领域不需要改任何枚举文件，加一个 `"NEW_TOPIC"` 字符串即可
- 解析器忽略不认识的 key

---

## 3. 日志格式

```
TRACE|topic=POSITION|action=UPDATE|gpst=123456.0|epoch=91|lat=29.212409|lon=95.098123|h=699.123|ns=7|stat=PPP
```

**规则：**

- 分隔符 `|`，键值分隔符 `=`
- 浮点 `Locale.US`，小数点 `.`
- 解析器忽略不认识的 key
- 默认精度：double 用 `%.6f`，int 用 `%d`，String 原样
- 极小值（方差、协方差、科学计数法场景）由调用者预格式化，见 4.4
- 诊断字段追加在末尾，以 `d_` 前缀区分（如 `d_dN`、`d_d3D`）

---

## 4. API

### 4.1 入口

```java
public final class Trace {
    private Trace() {}

    // 唯一入口
    public static void emit(String topic, String action,
                            TraceConfig cfg, TraceCallback cb,
                            int epoch, GTime time, Object... kv);

    // 运行结束调用一次，输出 SUMMARY
    public static void summary(TraceConfig cfg, TraceCallback cb);
}
```

### 4.2 emit 内部流程

```java
public static void emit(String topic, String action,
                        TraceConfig cfg, TraceCallback cb,
                        int epoch, GTime time, Object... kv) {
    // 1. 快速返回
    if (cfg == null || !cfg.enabled) return;
    if (!cfg.topics.contains(topic)) return;
    if (cfg.actions != null && !cfg.actions.contains(action)) return;
    if (cfg.maxEpoch > 0 && epoch >= cfg.maxEpoch) return;
    if (cfg.samplerate > 1 && epoch % cfg.samplerate != 0) return;
    if (cfg.maxOutputLines > 0 && cfg.stats.outputLineCount >= cfg.maxOutputLines) return;
    if (!passSatFilter(kv, cfg)) return;

    // 2. 构建
    StringBuilder sb = new StringBuilder(256);
    sb.append("TRACE|topic=").append(topic)
      .append("|action=").append(action)
      .append("|gpst=").append(gpstStr(time))
      .append("|epoch=").append(epoch);

    for (int i = 0; i + 1 < kv.length; i += 2) {
        sb.append('|').append(kv[i]).append('=').append(fmt(kv[i + 1]));
    }

    // 3. 内联诊断（见第 6 节）
    appendDiagnosis(sb, topic, action, kv, cfg);

    // 4. 输出
    try { cb.onLine(sb.toString()); } catch (Exception ignored) {}
    cfg.stats.outputLineCount++;

    // 5. 累积统计（见第 7 节）
    accumulate(cfg, topic, action, kv);
}
```

### 4.3 格式化

```java
private static String fmt(Object v) {
    if (v == null) return "";
    if (v instanceof Double || v instanceof Float) {
        return String.format(Locale.US, "%.6f", ((Number) v).doubleValue());
    }
    return String.valueOf(v);
}
```

统一 6 位小数。

### 4.4 极小值的处理（明确约定）

`%.6f` 对 `var=1.2345e-08` 会输出 `0.000000`，信息丢失。方差、协方差、ZTD 方差、STEC 方差等极小值，**由调用者预格式化后作为 String 传入**：

```java
Trace.emit("AMBIGUITY", "UPDATE", cfg, cb, epoch, time,
    "sat", "C08", "bias", bias,
    "var", String.format(Locale.US, "%.4e", var));   // 调用者控制精度
```

同理适用于：`ztd_var`、`stec_var`、`pvar`、任何量级小于 `1e-3` 的浮点。

系统不维护 key→格式映射表——那张表会和文档不同步，且没有一处代码会检查它。精度是调用者的责任，文档把这条约定写清楚即可。

---

## 5. 配置

### 5.1 TraceConfig

```java
public class TraceConfig {
    boolean enabled = false;

    // opt-in：空 = 全不输出，必须显式订阅
    Set<String> topics = new HashSet<>();

    // opt-out：null = 全部 action，非 null = 只输出集合中的 action
    Set<String> actions = null;

    int samplerate = 1;
    int maxEpoch = 0;
    int maxOutputLines = 0;
    int[] targetSats = {};

    double[] refEcef = null;           // 参考坐标（ECEF），用于诊断位置偏差
    double convergenceThresh = 0.1;    // 收敛阈值（m）
    int convergenceEpochs = 10;        // 连续收敛历元数
    double arShiftWarnThresh = 5.0;    // AR 固定后偏移告警阈值（m）

    final TraceStats stats = new TraceStats();
}
```

**topics 与 actions 的语义非对称，是刻意的：**

| 参数 | 空值语义 | 设计意图 |
|------|----------|----------|
| `topics` | 空 = 全不输出 | 日志默认关闭，必须显式说「我要看什么领域」 |
| `actions` | null = 全部 | 选了 topic 之后，该领域的所有 action 默认都要，除非显式收窄 |

常见用法：

```java
cfg.topics = Set.of("POSITION", "AR");   // 只看这两个领域
cfg.actions = null;                       // 这两个领域的所有 action（默认）

cfg.topics = Set.of("POSITION", "AR");
cfg.actions = Set.of("FAIL", "WARN");    // 只看失败和告警
```

emit 中的判断：

```java
if (!cfg.topics.contains(topic)) return;
if (cfg.actions != null && !cfg.actions.contains(action)) return;
```

### 5.2 TraceStats

```java
public class TraceStats {
    int totalEpochs;
    int successEpochs;
    int failEpochs;
    Map<String, Integer> failReasons = new LinkedHashMap<>();
    int convEpoch;                  // 首次收敛历元序号
    int consecutiveConvEpochs;      // 连续收敛历元计数
    int fixCount, floatCount;
    double maxArShift;
    int outputLineCount;
}
```

### 5.3 TraceCallback

```java
public interface TraceCallback {
    void onLine(String line);
}
```

---

## 6. 诊断

只有三条，直接写在 `appendDiagnosis` 里：

```java
private static void appendDiagnosis(StringBuilder sb, String topic,
                                    String action, Object[] kv, TraceConfig cfg) {
    // 6.1 位置偏差
    if ("POSITION".equals(topic) && "UPDATE".equals(action) && cfg.refEcef != null) {
        double[] ecef = extractEcef(kv);   // 从 x/y/z 或 lat/lon/h 取
        if (ecef != null) {
            double[] neu = ecefToNeu(ecef, cfg.refEcef);
            double d3 = Math.sqrt(neu[0]*neu[0] + neu[1]*neu[1] + neu[2]*neu[2]);
            sb.append("|d_dN=").append(fmt(neu[0]))
              .append("|d_dE=").append(fmt(neu[1]))
              .append("|d_dU=").append(fmt(neu[2]))
              .append("|d_d3D=").append(fmt(d3))
              .append("|d_conv=").append(convState(cfg, d3));
        }
    }

    // 6.2 AR 固定后偏移告警
    if ("AR".equals(topic) && "FIX".equals(action)) {
        double shift3 = getDouble(kv, "shift_3D");
        if (shift3 > cfg.arShiftWarnThresh) {
            sb.append("|d_WARN=LARGE_SHIFT|d_thresh=").append(fmt(cfg.arShiftWarnThresh));
            cfg.stats.maxArShift = Math.max(cfg.stats.maxArShift, shift3);
        }
    }

    // 6.3 失败原因累积在 accumulate 里，不在这里
}
```

不做 `DiagnosisRule` 接口。三条规则，if 判断足够。加第四条规则时加一个 if 块，仍然是一处修改。

---

## 7. 统计

```java
private static void accumulate(TraceConfig cfg, String topic,
                               String action, Object[] kv) {
    TraceStats s = cfg.stats;

    if ("RESULT".equals(topic)) {
        if ("UPDATE".equals(action)) s.successEpochs++;
        if ("FAIL".equals(action)) {
            s.failEpochs++;
            String reason = getString(kv, "reason");
            if (reason != null) s.failReasons.merge(reason, 1, Integer::sum);
        }
    }

    if ("AR".equals(topic)) {
        if ("FIX".equals(action)) s.fixCount++;
        if ("WARN".equals(action)) s.floatCount++;
    }

    if ("SATELLITE".equals(topic) && "START".equals(action)) {
        s.totalEpochs++;
    }

    if ("POSITION".equals(topic) && "UPDATE".equals(action)) {
        String conv = getString(kv, "d_conv");
        if ("YES".equals(conv)) {
            s.consecutiveConvEpochs++;
            if (s.convEpoch == 0 && s.consecutiveConvEpochs >= cfg.convergenceEpochs) {
                s.convEpoch = epochOf(kv);
            }
        } else {
            s.consecutiveConvEpochs = 0;
        }
    }
}
```

---

## 8. SUMMARY

`summary(cfg, cb)` 输出单行，和普通事件同构，解析器统一处理：

```java
public static void summary(TraceConfig cfg, TraceCallback cb) {
    if (cfg == null || !cfg.enabled) return;
    TraceStats s = cfg.stats;

    StringBuilder sb = new StringBuilder(256);
    sb.append("TRACE|topic=RESULT|action=SUMMARY")
      .append("|total=").append(s.totalEpochs)
      .append("|success=").append(s.successEpochs)
      .append("|fail=").append(s.failEpochs)
      .append("|fail_reasons=").append(joinReasons(s.failReasons))
      .append("|fix=").append(s.fixCount)
      .append("|float=").append(s.floatCount)
      .append("|conv_epoch=").append(s.convEpoch)
      .append("|max_ar_shift=").append(fmt(s.maxArShift))
      .append("|output_lines=").append(s.outputLineCount);

    try { cb.onLine(sb.toString()); } catch (Exception ignored) {}
}
```

`fail_reasons=FILTER_DIVERGENCE:80,NO_SP3:45`，reason 用下划线命名，不含 `:` 和 `,`。

---

## 9. 各模式 emit 规划

topic 用字符串，不再枚举。下面列 RTK 和 PPP-RTK 作为示例，SPP/PPP/Adjust 类似。

### 9.1 RTK

| 位置 | topic | action | kv |
|------|-------|--------|----|
| 历元开始 | SATELLITE | START | rover_ns, base_ns, common_ns |
| 卫星排除 | SATELLITE | FAIL | sat, reason, el |
| 双差形成 | BASELINE | COMPUTE | ref, pairs, nv |
| 残差异常 | BASELINE | WARN | sat, type, v, thresh |
| 滤波更新 | FILTER | UPDATE | info, dx_norm |
| 滤波发散 | FILTER | FAIL | reason, dX_norm |
| 模糊度更新 | AMBIGUITY | UPDATE | sat, freq, bias, var（预格式化）, dist_int |
| 模糊度重置 | AMBIGUITY | RESET | sat, reason |
| LAMBDA | AR | FIX | mode, ratio, nb, fixed, shift_N, shift_E, shift_U |
| 位置输出 | POSITION | UPDATE | lat, lon, h, ns, stat |
| 历元结果 | RESULT | UPDATE | stat, ns, fix |
| 历元失败 | RESULT | FAIL | reason |

### 9.2 PPP-RTK

| 位置 | topic | action | kv |
|------|-------|--------|----|
| 历元开始 | SATELLITE | START | obs, ssr_ok, ssr_fail |
| SSR 轨道 | SSR | COMPUTE | sat, type=orbit, d_radial, d_along, d_cross |
| SSR 钟差 | SSR | COMPUTE | sat, type=clock, value |
| SSR 码偏差 | SSR | COMPUTE | sat, type=code_bias, freq, value |
| SSR 相位偏差 | SSR | COMPUTE | sat, type=phase_bias, freq, value |
| SSR 过期 | SSR | WARN | sat, code=SSR_EXPIRED, age, max_age |
| SSR 无数据 | SSR | FAIL | sat, reason=NO_SSR_DATA |
| STEC 估计 | STEC | UPDATE | sat, stec, stec_var（预格式化） |
| STEC 重置 | STEC | RESET | sat, reason |
| ZTD 估计 | ZTD | UPDATE | ztd, ztd_var（预格式化）, grad_N, grad_E |
| 滤波更新 | FILTER | UPDATE | info, dx_norm |
| 模糊度更新 | AMBIGUITY | UPDATE | sat, freq, bias, var（预格式化）, dist_int |
| LAMBDA | AR | FIX | mode, ratio, nb, fixed, shift_N, shift_E, shift_U |
| 位置输出 | POSITION | UPDATE | lat, lon, h, ns, stat |
| 历元结果 | RESULT | UPDATE | stat, ns |

### 9.3 其余模式

| 模式 | topic 集合 |
|------|------------|
| SPP | SATELLITE / EPHEMERIS / CORRECTION / POSITION / RESULT |
| PPP | SATELLITE / EPHEMERIS / CORRECTION / ZTD / FILTER / AMBIGUITY / POSITION / RESULT |
| PPP-AR | PPP + AR（WL/NL/Partial） |
| Adjust | ADJUSTMENT / RESULT |

模式与 topic 的对应关系写在 README 里，不在代码里强制。

---

## 10. 使用示例

```java
TraceConfig cfg = new TraceConfig();
cfg.enabled = true;
cfg.topics = Set.of("POSITION", "AR", "AMBIGUITY", "SSR");
cfg.actions = null;                        // 这些领域的所有 action
cfg.refEcef = new double[]{-495107.57, 5549961.52, 3094815.33};
cfg.arShiftWarnThresh = 5.0;

TraceCallback cb = line -> System.out.println(line);

for (int epoch = 0; epoch < n; epoch++) {
    // ... 解算 ...

    Trace.emit("POSITION", "UPDATE", cfg, cb, epoch, time,
        "lat", lat, "lon", lon, "h", h, "ns", ns, "stat", "PPP");

    Trace.emit("AR", "FIX", cfg, cb, epoch, time,
        "mode", "PARTIAL", "ratio", ratio, "nb_wl", nbWl, "nb_nl", nbNl,
        "shift_N", shiftN, "shift_E", shiftE, "shift_U", shiftU);

    Trace.emit("AMBIGUITY", "UPDATE", cfg, cb, epoch, time,
        "sat", "C08", "bias", bias,
        "var", String.format(Locale.US, "%.4e", var));   // 极小值预格式化
}

Trace.summary(cfg, cb);
```

**加一个观测点 = 加一行 `Trace.emit(...)`。**
**加一个字段 = 加一个 kv。**
**加一个 topic = 加一个字符串。**
**加一个诊断 = 加一个 if 块。**

---

## 11. 文件清单

| 文件 | 行数（估计） | 说明 |
|------|-------------|------|
| `Trace.java` | ~250 | emit + 诊断 + 统计 + summary |
| `TraceConfig.java` | ~40 | 配置 |
| `TraceStats.java` | ~30 | 累积状态 |
| `TraceCallback.java` | ~5 | 回调接口 |

**4 个文件，约 325 行。**

---

## 12. 与 V1 的共存策略

V2 不替换 V1，两者共存：

- V1（`RtkTrace`）继续服务 RTK 的 7 阶段详细输出（H 矩阵、R 对角线等）
- V2（`Trace`）服务全模式的高层事件追踪和诊断

如果未来 V1 的 Stage 信息可以映射为 V2 的 topic/action，可以考虑在 V1 内部调用 V2 的 `emit()`，统一输出格式。但这不是 V2 的前提条件——V2 可以独立实施。

---

## 13. 输出示例

### 13.1 PPP 位置 + 诊断

```
TRACE|topic=POSITION|action=UPDATE|gpst=123456.0|epoch=91|lat=29.212409|lon=95.098123|h=699.123|ns=7|stat=PPP|d_dN=0.012|d_dE=-0.008|d_dU=0.045|d_d3D=0.048|d_conv=YES
```

### 13.2 AR 固定 + 告警

```
TRACE|topic=AR|action=FIX|gpst=123460.0|epoch=95|mode=PARTIAL|ratio=2.8|nb_wl=3|nb_nl=5|shift_N=1.2|shift_E=-0.8|shift_U=0.5|d_WARN=LARGE_SHIFT|d_thresh=5.0
```

### 13.3 SSR 失败

```
TRACE|topic=SSR|action=FAIL|gpst=123465.0|epoch=100|sat=C08|reason=NO_SSR_DATA
```

### 13.4 SUMMARY

```
TRACE|topic=RESULT|action=SUMMARY|total=3600|success=3120|fail=480|fail_reasons=FILTER_DIVERGENCE:380,NO_SP3:100|fix=2800|float=320|conv_epoch=45|max_ar_shift=6.2|output_lines=15230
```

---

## 14. 解析器兼容

V2 输出与 V1 同构（`TRACE|...|key=value|...`），V1 的 Python 解析器可直接使用：

```python
def parse_trace_line(line):
    if not line.startswith("TRACE|"):
        return None
    parts = line.split("|")
    fields = {}
    for p in parts[1:]:  # V2 没有 STAGE 标识，从 parts[1] 开始就是 topic=...
        if "=" in p:
            k, v = p.split("=", 1)
            fields[k] = v
    return fields
```

V2 的 topic/action 可以直接作为 DataFrame 的列：

```python
import pandas as pd

rows = [parse_trace_line(line) for line in open("trace.log") if line.startswith("TRACE|")]
df = pd.DataFrame(rows)
# 按 topic 过滤
positions = df[df["topic"] == "POSITION"]
# 按 action 过滤
failures = df[df["action"] == "FAIL"]
```
