# GNSS RTK 追踪日志系统 — 设计与使用文档

## 1. 概述

RTK 追踪日志系统（Trace Log System）为 RTK 相对定位解算过程提供分阶段、可配置的实时日志输出能力。系统将 RTK 解算流程划分为 7 个关键阶段，每个阶段可独立开关，并支持采样率控制、目标卫星过滤和内容详细程度调节。

### 设计目标

- **非侵入性**：默认关闭（`enabled=false`），不开启时零性能开销
- **分阶段输出**：7 个阶段独立控制，按需开启
- **灵活过滤**：支持采样率、最大历元数、目标卫星筛选
- **回调驱动**：通过 `TraceCallback` 接口输出，不绑定具体 I/O 实现
- **异常安全**：回调异常被静默捕获，不影响解算流程

---

## 2. 架构

```
┌─────────────────────────────────────────────────────────────┐
│                       Rtk (数据对象)                         │
│  ┌──────────────┐  ┌──────────────┐                         │
│  │ traceControl │  │traceCallback │                         │
│  └──────┬───────┘  └──────┬───────┘                         │
└─────────┼─────────────────┼─────────────────────────────────┘
          │                 │
          ▼                 ▼
┌─────────────────────────────────────────────────────────────┐
│                     RtkTrace (核心类)                        │
│                                                             │
│  shouldTrace()  ─── 判断是否输出（开关/阶段/采样率/历元）     │
│  isTargetSat()  ─── 判断卫星是否在目标列表中                 │
│  safeCallback() ─── 安全调用回调（异常捕获）                 │
│                                                             │
│  traceStage0()  ─── 输入数据                                 │
│  traceStage1()  ─── 卫星位置与误差改正                       │
│  traceStage2()  ─── 状态时间更新                             │
│  traceStage3()  ─── 双差残差与设计矩阵                       │
│  traceStage4()  ─── 卡尔曼滤波更新                           │
│  traceStage5()  ─── 模糊度固定（LAMBDA）                     │
│  traceStage6()  ─── 结果输出                                 │
└─────────────────────────────────────────────────────────────┘
          │
          ▼
┌─────────────────────────────────────────────────────────────┐
│                  TraceCallback (回调接口)                    │
│                                                             │
│  void onTrace(String content)                               │
│                                                             │
│  实现示例：                                                  │
│  · System.out.println                                       │
│  · 写入文件                                                  │
│  · 写入 BlockingQueue（异步处理）                            │
│  · 推送到 WebSocket                                          │
└─────────────────────────────────────────────────────────────┘
```

---

## 3. 核心类说明

### 3.1 TraceControl — 追踪控制参数

| 字段 | 类型 | 默认值 | 说明 |
|------|------|--------|------|
| `enabled` | boolean | false | 全局开关，false 时所有阶段均不输出 |
| `stages` | int | 0 | 阶段位掩码，控制哪些阶段输出 |
| `contentFlags` | int | 0 | 内容标志，控制输出详细程度 |
| `maxEpochs` | int | 0 | 最大输出历元数，0=不限制 |
| `samplerate` | int | 1 | 采样率，1=每个历元输出，N=每N个历元输出一次 |
| `targetSats` | int[] | [] | 目标卫星编号列表，空=输出所有卫星 |

**阶段位掩码常量：**

| 常量 | 值 | 阶段 |
|------|----|------|
| `STAGE_INPUT` | 1 << 0 | 输入数据 |
| `STAGE_SATPOS` | 1 << 1 | 卫星位置与误差改正 |
| `STAGE_UDSTATE` | 1 << 2 | 状态时间更新 |
| `STAGE_DDRES` | 1 << 3 | 双差残差与设计矩阵 |
| `STAGE_FILTER` | 1 << 4 | 卡尔曼滤波更新 |
| `STAGE_LAMBDA` | 1 << 5 | 模糊度固定 |
| `STAGE_RESULT` | 1 << 6 | 结果输出 |

**内容标志常量：**

| 常量 | 值 | 说明 |
|------|----|------|
| `CONTENT_RESIDUAL_ONLY` | 1 << 0 | Stage 3 仅输出残差，不输出 R 矩阵对角线 |
| `CONTENT_H_MATRIX` | 1 << 1 | Stage 3 输出设计矩阵 H 的位置列和模糊度列 |
| `CONTENT_SUMMARY_ONLY` | 1 << 2 | Stage 2/5 仅输出摘要，不输出逐卫星详情 |

### 3.2 TraceCallback — 回调接口

```java
public interface TraceCallback {
    void onTrace(String content);
}
```

回调方法在 RTK 解算线程中同步调用，实现方需注意：
- **非阻塞**：如果 I/O 操作耗时，应使用 `BlockingQueue` 异步处理
- **异常安全**：回调抛出异常会被 `RtkTrace.safeCallback()` 静默捕获

### 3.3 RtkTrace — 核心实现类

工具类（`final`，私有构造），所有方法为 `public static`。核心判断流程：

```
shouldTrace(ctrl, stageBit, epoch)
  ├─ ctrl == null 或 !ctrl.enabled  →  false（不输出）
  ├─ maxEpochs > 0 且 epoch >= maxEpochs  →  false
  ├─ samplerate > 1 且 epoch % samplerate != 0  →  false
  └─ (stages & stageBit) == 0  →  false
  └─ 以上均通过  →  true（输出）
```

---

## 4. 7 个阶段详细说明

### 4.1 Stage 0 — 输入数据

**触发位置**：`RtkCore.relpos()` 中 `selsat()` 之后

**输出行格式：**

| 行标识 | 格式 | 说明 |
|--------|------|------|
| `STAGE0` | `TRACE\|STAGE0\|gpst=<>\|epoch=<>\|rover_ns=<>\|base_ns=<>\|common_ns=<>\|rover_valid=<>\|base_valid=<>\|eph_valid=<>` | 历元概览 |
| `STAGE0_SAT` | `TRACE\|STAGE0_SAT\|gpst=<>\|epoch=<>\|sat=<G01>\|rover_L1=<>\|rover_P1=<>\|rover_SNR=<>\|base_L1=<>\|base_P1=<>\|base_SNR=<>\|el=<>\|az=<>` | 逐共视卫星详情 |

**字段说明：**

| 字段 | 单位 | 说明 |
|------|------|------|
| gpst | 秒 | GPS 周内秒 |
| epoch | - | 历元序号（从1开始） |
| rover_ns / base_ns | - | 流动站/基准站观测数 |
| common_ns | - | 共视卫星数 |
| rover_valid / base_valid | 0/1 | 流动站/基准站是否有有效观测 |
| eph_valid | 0/1 | 星历是否可用 |
| sat | - | 卫星ID（如 G01, E12） |
| rover_L1 / base_L1 | 米 | 载波相位距离（周×波长） |
| rover_P1 / base_P1 | 米 | 伪距 |
| rover_SNR / base_SNR | dB-Hz | 信噪比 |
| el / az | 度 | 高度角/方位角 |

---

### 4.2 Stage 1 — 卫星位置与误差改正

**触发位置**：`RtkCore.relpos()` 中 `selsat()` 之后

**输出行格式：**

| 行标识 | 格式 |
|--------|------|
| `STAGE1` | `TRACE\|STAGE1\|gpst=<>\|epoch=<>\|sat=<G01>\|pos_x=<>\|pos_y=<>\|pos_z=<>\|clock=<>\|trop=<>\|iono=<>\|geom_dist=<>\|pcv=<>` |

**字段说明：**

| 字段 | 单位 | 说明 |
|------|------|------|
| pos_x/y/z | 米 | 卫星 ECEF 坐标 |
| clock | 米 | 卫星钟差（已乘光速，取负） |
| trop | 米 | 对流层延迟近似值 |
| iono | 米 | 电离层延迟（当前为0，预留） |
| geom_dist | 米 | 几何距离 |
| pcv | 米 | 天线相位中心改正（当前为0，预留） |

---

### 4.3 Stage 2 — 状态时间更新

**触发位置**：`RtkCore.relpos()` 中 `udstate()` 之后

**输出行格式：**

| 行标识 | 格式 | 条件 |
|--------|------|------|
| `STAGE2` | `TRACE\|STAGE2\|gpst=<>\|epoch=<>\|pos_x=<>\|pos_y=<>\|pos_z=<>` | 始终输出 |
| `STAGE2_BIAS` | `TRACE\|STAGE2_BIAS\|gpst=<>\|epoch=<>\|sat=<>\|freq=<>\|bias=<>\|var=<>\|slip=<>\|rejc=<>` | 非 SUMMARY_ONLY 时 |
| `STAGE2_RESET` | `TRACE\|STAGE2_RESET\|gpst=<>\|epoch=<>\|global=<>\|partial=<G01,E12>` | 始终输出 |

**字段说明：**

| 字段 | 单位 | 说明 |
|------|------|------|
| pos_x/y/z | 米 | 状态向量中的位置分量（绝对坐标 = x + rb） |
| bias | 周 | 模糊度浮点值 |
| var | - | 模糊度方差 |
| slip | 0/1 | 是否有周跳标志 |
| rejc | - | 粗差拒绝计数 |
| global | 0/1 | 是否发生全局状态重置 |
| partial | - | 发生部分重置的卫星列表 |

---

### 4.4 Stage 3 — 双差残差与设计矩阵

**触发位置**：`RtkCore.relpos()` 中 `ddres()` 之后

**输出行格式：**

| 行标识 | 格式 | 条件 |
|--------|------|------|
| `STAGE3` | `TRACE\|STAGE3\|gpst=<>\|epoch=<>\|ref=<G01>\|pairs=<G01-G02,G01-G05>\|nv=<>` | 始终输出 |
| `STAGE3_V` | `TRACE\|STAGE3_V\|gpst=<>\|epoch=<>\|v0=<>\|v1=<>...` | nv > 0 时 |
| `STAGE3_R` | `TRACE\|STAGE3_R\|gpst=<>\|epoch=<>\|r0=<>\|r1=<>...` | nv > 0 时 |
| `STAGE3_H_POS` | `TRACE\|STAGE3_H_POS\|gpst=<>\|epoch=<>\|row0=<>,<>,<>...` | 设置 CONTENT_H_MATRIX 时 |
| `STAGE3_H_BIAS` | `TRACE\|STAGE3_H_BIAS\|gpst=<>\|epoch=<>\|G01=<>\|E12=<>...` | 设置 CONTENT_H_MATRIX 时 |

**字段说明：**

| 字段 | 单位 | 说明 |
|------|------|------|
| ref | - | 参考卫星ID |
| pairs | - | 双差配对列表 |
| nv | - | 残差向量维数 |
| v0, v1, ... | 米 | 双差残差值 |
| r0, r1, ... | - | R 矩阵对角线元素 |
| row0, row1, ... | - | H 矩阵每行的位置列（前3列） |
| G01=, E12=, ... | - | H 矩阵中各卫星模糊度列的非零系数 |

---

### 4.5 Stage 4 — 卡尔曼滤波更新

**触发位置**：`RtkCore.relpos()` 中 `filter()` 之后

**输出行格式：**

| 行标识 | 格式 |
|--------|------|
| `STAGE4` | `TRACE\|STAGE4\|gpst=<>\|epoch=<>\|info=<>` |
| `STAGE4_DXP` | `TRACE\|STAGE4_DXP\|gpst=<>\|epoch=<>\|dx0=<>\|dx1=<>\|dx2=<>...` |
| `STAGE4_PDIAG` | `TRACE\|STAGE4_PDIAG\|gpst=<>\|epoch=<>\|p0=<>\|p1=<>\|p2=<>...` |

**字段说明：**

| 字段 | 单位 | 说明 |
|------|------|------|
| info | - | 滤波返回码，0=成功，非0=奇异 |
| dx0, dx1, dx2, ... | 米/周 | 状态向量各分量更新量（xp - xpPrev） |
| p0, p1, p2, ... | - | 协方差矩阵对角线元素（标准差平方） |

---

### 4.6 Stage 5 — 模糊度固定（LAMBDA）

**触发位置**：`RtkCore.relpos()` 中 LAMBDA 块之后

**输出行格式：**

| 行标识 | 格式 | 条件 |
|--------|------|------|
| `STAGE5` | `TRACE\|STAGE5\|gpst=<>\|epoch=<>\|fixed=<>\|ratio=<>` | 始终输出 |
| `STAGE5_FLOAT` | `TRACE\|STAGE5_FLOAT\|gpst=<>\|epoch=<>\|G01_L0=<>\|E12_L1=<>...` | 非 SUMMARY_ONLY 时 |
| `STAGE5_FIXED` | `TRACE\|STAGE5_FIXED\|gpst=<>\|epoch=<>\|G01_L0=<>\|E12_L1=<>...` | 非 SUMMARY_ONLY 时 |
| `STAGE5_SHIFT` | `TRACE\|STAGE5_SHIFT\|gpst=<>\|epoch=<>\|dx=<>\|dy=<>\|dz=<>` | 始终输出 |

**字段说明：**

| 字段 | 单位 | 说明 |
|------|------|------|
| fixed | 0/1 | 是否固定成功（0=浮点，1=固定） |
| ratio | - | Ratio 值 |
| G01_L0, E12_L1, ... | 周 | 各卫星各频点的模糊度值 |
| dx/dy/dz | 米 | 固定解相对于浮点解的位置偏移 |

---

### 4.7 Stage 6 — 结果输出

**触发位置**：`RtkCore.relpos()` 末尾，`rtk.sol.stat` 赋值之后

**输出行格式：**

| 行标识 | 格式 |
|--------|------|
| `STAGE6` | `TRACE\|STAGE6\|gpst=<>\|epoch=<>\|Q=<>\|lat=<>\|lon=<>\|h=<>\|sdn=<>\|sde=<>\|sdu=<>\|ns=<>\|iter=<>` |

**字段说明：**

| 字段 | 单位 | 说明 |
|------|------|------|
| Q | - | 解算质量（0=None, 1=Fix, 2=Float, 5=Single, 6=DGPS） |
| lat / lon | 度 | 纬度/经度（WGS84） |
| h | 米 | 椭球高 |
| sdn / sde / sdu | 米 | 北/东/天方向标准差 |
| ns | - | 使用卫星数 |
| iter | - | 迭代次数 |

---

## 5. 使用方法

### 5.1 基本用法

```java
import org.rtklib.java.data.Rtk;
import org.rtklib.java.trace.TraceControl;
import org.rtklib.java.trace.TraceCallback;

Rtk rtk = new Rtk();

// 1. 创建控制参数
rtk.traceControl = new TraceControl();
rtk.traceControl.enabled = true;

// 2. 选择要输出的阶段（位掩码组合）
rtk.traceControl.stages = TraceControl.STAGE_INPUT
                        | TraceControl.STAGE_RESULT;

// 3. 设置回调
rtk.traceCallback = content -> System.out.println(content);
```

### 5.2 输出所有阶段

```java
rtk.traceControl.stages = TraceControl.STAGE_INPUT
                        | TraceControl.STAGE_SATPOS
                        | TraceControl.STAGE_UDSTATE
                        | TraceControl.STAGE_DDRES
                        | TraceControl.STAGE_FILTER
                        | TraceControl.STAGE_LAMBDA
                        | TraceControl.STAGE_RESULT;
// 或简写：
rtk.traceControl.stages = 0x7F;  // 二进制 1111111
```

### 5.3 采样率控制

```java
// 每 10 个历元输出一次
rtk.traceControl.samplerate = 10;
```

### 5.4 限制输出历元数

```java
// 仅输出前 100 个历元
rtk.traceControl.maxEpochs = 100;
```

### 5.5 目标卫星过滤

```java
import org.rtklib.java.common.SatUtils;

// 仅输出 G01 和 E12 卫星的详情
// 注意：satno 是内部编号，需用 SatUtils 转换
int g01 = SatUtils.satid2no("G01");  // 返回卫星内部编号
int e12 = SatUtils.satid2no("E12");
rtk.traceControl.targetSats = new int[]{g01, e12};
```

### 5.6 内容详细程度

```java
// Stage 3 输出设计矩阵 H
rtk.traceControl.contentFlags |= TraceControl.CONTENT_H_MATRIX;

// Stage 2/5 仅输出摘要
rtk.traceControl.contentFlags |= TraceControl.CONTENT_SUMMARY_ONLY;
```

### 5.7 异步回调（推荐用于文件写入）

```java
import java.util.concurrent.*;
import java.io.*;

// 使用 BlockingQueue 实现异步写入
BlockingQueue<String> queue = new LinkedBlockingQueue<>(10000);
ExecutorService executor = Executors.newSingleThreadExecutor();

rtk.traceCallback = queue::offer;  // 非阻塞写入队列

// 后台消费线程
executor.submit(() -> {
    try (PrintWriter pw = new PrintWriter(new FileWriter("rtk_trace.log"))) {
        while (!Thread.currentThread().isInterrupted()) {
            String line = queue.poll(100, TimeUnit.MILLISECONDS);
            if (line != null) pw.println(line);
        }
    } catch (Exception e) {
        e.printStackTrace();
    }
});

// 解算结束后关闭
executor.shutdownNow();
```

---

## 6. 日志格式规范

### 6.1 通用格式

```
TRACE|<行标识>|gpst=<值>|epoch=<值>|<字段1>=<值1>|<字段2>=<值2>|...
```

- 字段分隔符：`|`
- 键值分隔符：`=`
- 数值格式：统一使用 `Locale.US`，浮点数使用 `.` 作为小数点
- 精度：坐标/距离 3 位小数（`%.3f`），经纬度 6 位小数（`%.6f`），残差 3 位，R 对角线 4 位

### 6.2 行标识命名规则

| 模式 | 说明 | 示例 |
|------|------|------|
| `STAGEN` | 阶段主行 | `STAGE0`, `STAGE3` |
| `STAGEN_XXX` | 阶段子行 | `STAGE0_SAT`, `STAGE3_V` |

### 6.3 解析示例

```python
# Python 解析示例
def parse_trace_line(line):
    if not line.startswith("TRACE|"):
        return None
    parts = line.split("|")
    tag = parts[1]
    fields = {}
    for p in parts[2:]:
        if "=" in p:
            k, v = p.split("=", 1)
            fields[k] = v
    return {"tag": tag, "fields": fields}
```

---

## 7. 判断流程图

```
RtkCore.relpos() 调用
│
├─ rtk.epoch++
│
├─ EphModel.satposs()  ────────────────  (计算卫星位置)
│
├─ zdres()  ───────────────────────────  (基准站非差残差)
│
├─ selsat()  ──────────────────────────  (选择共视卫星)
│   │
│   ├── ★ traceStage0() ─── 输入数据
│   └── ★ traceStage1() ─── 卫星位置与误差改正
│
├─ udstate()  ─────────────────────────  (状态时间更新)
│   │
│   └── ★ traceStage2() ─── 状态时间更新
│
├─ [迭代循环]
│   ├── zdres()  ──────────  (流动站非差残差)
│   ├── ddres()  ──────────  (双差残差)
│   │   │
│   │   └── ★ traceStage3() ─── 双差残差与设计矩阵
│   │
│   ├── filter()  ─────────  (卡尔曼滤波)
│   │   │
│   │   └── ★ traceStage4() ─── 卡尔曼滤波更新
│   │
│   └── (收敛判断)
│
├─ [LAMBDA 模糊度固定]
│   │
│   └── ★ traceStage5() ─── 模糊度固定
│
├─ rtk.sol.stat = stat
│   │
│   └── ★ traceStage6() ─── 结果输出
│
└─ return
```

---

## 8. 文件清单

| 文件路径 | 类型 | 说明 |
|----------|------|------|
| `src/main/java/org/rtklib/java/trace/TraceControl.java` | 新增 | 追踪控制参数类 |
| `src/main/java/org/rtklib/java/trace/TraceCallback.java` | 新增 | 日志回调接口 |
| `src/main/java/org/rtklib/java/trace/RtkTrace.java` | 新增 | 核心实现类（7个阶段输出） |
| `src/main/java/org/rtklib/java/data/Rtk.java` | 修改 | 添加 traceControl/traceCallback 字段 |
| `src/main/java/org/rtklib/java/rtkpos/RtkCore.java` | 修改 | 添加 rtk.epoch++ 和 7 处追踪调用 |

---

## 9. 注意事项

1. **默认关闭**：`TraceControl.enabled` 默认为 `false`，不设置时无任何性能开销
2. **回调线程安全**：`onTrace()` 在 RTK 解算线程中同步调用，耗时操作应异步处理
3. **异常安全**：回调异常被 `safeCallback()` 捕获并忽略，不影响解算
4. **null 安全**：`traceControl` 或 `traceCallback` 为 `null` 时，所有 trace 方法安全跳过
5. **数值格式**：所有浮点数使用 `String.format(Locale.US, ...)` 确保小数点为 `.`
6. **卫星编号**：日志中使用卫星ID（如 `G01`、`E12`），内部通过 `SatUtils.satno2id()` 转换
7. **Stage 3 数据量**：开启 `CONTENT_H_MATRIX` 后输出量较大，建议配合 `samplerate` 使用
8. **Stage 5 依赖**：`STAGE5_FLOAT`/`STAGE5_FIXED` 子行依赖 LAMBDA 是否进入，未进入时仅输出 `STAGE5` 主行