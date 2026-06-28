# SPP调试记录

## 测试环境
- 数据源：RTCM3 MSM4流（BDS-only，13颗卫星）
- 对比基准：RTKLIB C版（2.5.0）convbin转RINEX + rnx2rtkp SPP定位
- 历元数：240
- 验证标准：Java版与C版定位结果差异在小数第9位以内

## 最终结果
- 240/240 历元全部匹配
- 纬度/经度差异：仅小数第9位（亚毫米级）
- 高程差异：亚毫米级
- 卫星数差异：0/240
- 标准差完全一致（纬度3.57m，高程14.1m）

---

## Bug #1：MSM伪距cell索引错误（影响最大，200m级偏差）

### 文件
`src/main/java/org/rtklib/java/rtcm/Rtcm.java` — `saveMsmObs()` 方法

### 问题
遍历cell时，无效cell（cellmask=0）也递增了j索引：
```java
// 错误写法
if (h.cellmask[k + i * h.nsig] == 0) { j++; continue; }
```

C版正确逻辑是跳过无效cell时不递增j：
```c
if (!h->cellmask[k+i*h->nsig]) continue;
```

### 原因
`pr[]`、`cp[]`、`cnr[]`、`lock[]`、`half[]` 数组只存储有效cell的数据（共ncell个）。
j索引应只对有效cell递增。在无效cell上递增j导致后续cell的伪距残差`pr[j]`错位，
映射到错误的卫星/信号组合。

### 影响
- 部分卫星伪距偏差高达200m
- 平均定位偏差53m
- C01和C39等卫星的伪距偶然正确（因为它们前面的无效cell恰好没有造成错位）

### 修复
```java
if (h.cellmask[k + i * h.nsig] == 0) continue;  // 不递增j
```

---

## Bug #2：Sagnac效应缺失（影响：~50m东向偏差）

### 文件
`src/main/java/org/rtklib/java/common/RtklibCommon.java` — `geodist()` 方法

### 问题
几何距离计算缺少地球自转修正项（Sagnac效应）。

### 修复
```java
r += Constants.OMGE * (rs[0] * rr[1] - rs[1] * rr[0]) / Constants.CLIGHT;
```

### 影响
- 补上后平面偏差从55m级收敛到8m级

---

## Bug #3：ephclk返回值逻辑错误（影响：有效卫星被排除）

### 文件
`src/main/java/org/rtklib/java/ephemeris/EphModel.java` — `ephclk()` 方法

### 问题
原实现直接返回时钟偏差值，用`dt == 0.0`判断失败。但某些卫星的时钟偏差
恰好很小（接近0），会被误判为失败而排除。

### 修复
改为返回boolean状态 + 输出数组参数，与C版`ephclk()`签名一致：
```java
private static boolean ephclk(GTime time, GTime teph, int sat, Nav nav, double[] dtOut)
```

---

## Bug #4：RTCM时间初始化依赖CPU时间（影响：epoch 0定位失败）

### 文件
`src/main/java/org/rtklib/java/rtcm/Rtcm.java` — `adjweek()`、`adjustGpsWeek()`、
`adjustBdtWeek()`、`adjdayGlot()`

### 问题
C版RTKLIB是实时转换（convbin），CPU时间和数据时间基本一致，所以用`timeget()`
（系统时间）作为时间未初始化时的回退是合理的。但我们是离线处理RTCM直接定位，
CPU时间可能与数据时间相差很远（超过半周），导致周数调整错误。

### 修复
- `adjweek()`：时间未初始化时用week=0创建临时时间，不设timeInitialized，
  让第一个星历消息建立正确的时间基准
- `adjustGpsWeek()`/`adjustBdtWeek()`：时间未初始化时返回原始周数
- `adjdayGlot()`：时间未初始化时直接返回
- SppTest中添加观测时间修正：用星历toe的周数修正早期观测时间

### 影响
- 修复前epoch 0因时间错误导致卫星位置计算失败（rs全为0）
- 修复后所有历元时间正确

---

## 调试过程

### 阶段1：系统性偏差分析
初始Java版与C版偏差达40-50m，通过逐步排查定位到4个误差源：
1. 卫星位置计算架构（ephclk返回值）→ 有效卫星被排除
2. Sagnac效应缺失 → 50m东向偏差
3. 对流层模型 → 高程方向偏差
4. 相对论钟差 → 小幅偏差

### 阶段2：伪距差异排查
在卫星选择和信号映射确认一致后，发现伪距值存在差异：
- C01伪距完全一致，但C07差95m、C08差200m
- 同一卫星不同信号的伪距在Java版相同（只有r值），C版不同（r+pr）
- 定位到saveMsmObs中cell索引j的递增逻辑错误

### 阶段3：时间初始化排查
修复伪距后epoch 0仍失败：
- MSM消息在星历之前到达，adjweek用week=0创建临时时间
- 之前用CPU时间初始化导致周数错误
- 改为依赖星历消息建立时间基准，SppTest中修正早期观测时间

### 阶段4：最终验证
修复cell索引后，240个历元全部匹配，差异仅在小数第9位。