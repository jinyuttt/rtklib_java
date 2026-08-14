# RTKLIB Java 调试指南

> 从技术参考文档迁移的调试日志格式、排查清单、测试验证等内容。
> 与 `RTK_Debug_Record.md`（Bug记录）互补，本文档侧重日志解读和排查方法。

---

## 1. 调试日志格式说明

### 1.1 KalmanFilter 日志

```
KF update: n=433 m=14 k=12
ix=[0 1 2 3 4 111 117 119 120 138 139 333]  # 活跃状态索引
xc=[9.894398 262.721532 -174.706700 ...]      # 压缩后的状态向量
v=[2.5090 0.8966 1.4909 -2.1148 2.1011 ...]   # 双差残差向量
Hc_full=[                                        # 压缩后的设计矩阵 (m×k)
  obs0: [0.470723 0.107021 -0.561206 0 0 -0.192039 0.192039 0 0 0 0 0]
  obs1: [-0.276383 -0.175810 0.056411 0 0 0 0.192039 -0.192039 0 0 0 0]
  ...
]
Pc_diag=[18.5038 46.9267 27.0886 0.0000 0.0000 228.8884 202.6907 ...] # 压缩后协方差对角线
R_diag=[0.0001 0.0001 0.0001 0.0001 0.0001 7.7003 7.0178 ...]          # 观测噪声对角线
K(row0)[0:9]=[0.12345678 0.23456789 ...]  # Kalman增益第0行
KV=[4.982145 -8.173201 ...]                # K*v (状态修正量)
dx=[4.982145 -8.173201 ...]                # dx = x_new - x_old
P_new_diag=[7.2384 18.3233 8.8409 ...]      # 更新后协方差对角线
I_KH_diag=[0.876543 0.765432 ...]           # (I-KH)对角线 (可能出现负值!)
```

### 1.2 ddres 日志

```
ddres: bl=320.5 m rb=(xxx,yyy,zzz) rr_f=(xxx,yyy,zzz)
ddres ref: f=0 ref=C08 sat=113 el=45.2°
ddres v: ref=113-107 L1 y_r=xxxx y_b=xxxx y_r2=xxxx y_b2=xxxx dd=0.0063
ddres e: e_ref=(a,b,c) e_j=(d,e,f) H_pos=(d-a,e-b,f-c)

pre-filter: nv=10 v=[113:10700=0.0063 113:11500=-0.0076 ...]
             Rdiag=[0.0001 0.0001 ... 7.7000 7.0170 ...]
             xp0=(14.87,254.55,-174.49) P0=18.5 P1=46.9 P2=27.1
```

### 1.3 resamb_LAMBDA 日志

```
resamb_LAMBDA: na=5 nx=433 nb=6
  dd[0]: x[117]=xx.xxxx - x[111]=xx.xxxx = xx.xxxx  P_diag=xxx.xxx/xxx.xxx
  ...
Qb matrix (6x6):
  [x.xxxxxx x.xxxxxx ... ]
  [...]
LAMBDA: nb=6 na=5 nx=433 s=[86.6010, 142.6950] ratio=1.6477
resamb_LAMBDA: validation failed (nb=6 ratio=1.65 thresh=3.00)
```

### 1.4 udbias 日志

```
udbias init: sat=107 f=0 idx=111 bias=42.1714 var=900.0
udbias init: sat=113 f=0 idx=117 bias=-17.6240 var=900.0
udbias init: sat=115 f=0 idx=119 bias=-86.3637 var=900.0  # ⚠️ 异常大
...
```

---

## 2. RTK 排查清单

### 2.1 Fix解比例低

当遇到"Fix解比例低"问题时，按以下顺序排查：

1. **检查配置**：`modear`, `bdsmodear`, `gpsmodear`, `glomodear` 是否开启？
2. **检查数据**：是否有足够的公共卫星（≥5颗）？截止高度角是否合理？
3. **检查posvar**：是否满足 `< thresar[1]=0.25`？如果不满足，检查`udpos`。
4. **检查模糊度方差**：`P_diag` 中模糊度项是否在收敛？如果不变，检查`ddres`中H矩阵。
5. **检查ratio**：如果ratio始终<2，检查`Qb`矩阵（双差协方差）是否合理。
6. **检查Kalman滤波**：确认使用Joseph形式更新P（`KalmanFilter.java`），标准形式在病态条件下不稳定。
7. **检查IB函数**：确认返回的索引与预期一致（特别是多频情况下的`f`参数）。
8. **检查数据质量**：C版RTKLIB是否同样无法Fix？如果C版也无法Fix，可能是数据质量问题。

---

## 3. RTCM MSM 调试

### 3.1 promoteExtSig 日志格式

```
[PROMOTE-SIG] sat={卫星号} freq={目标频率}: promoted code={信号码} from ext idx={源索引} to idx={目标索引}

示例:
[PROMOTE-SIG] sat=106 freq=1: promoted code=61 from ext idx=6 to idx=1
含义: 卫星106(G08)的第1频率(L2)槽位为空，从扩展槽位6提升信号码61(B2a)
```

### 3.2 相关调试日志链

```
[MSM-BDS-SIG] type=1124 nsig=6 [...]           ← sigindex之前，显示原始信号
[MSM-BDS-STORE] sat=125 k=0 sig=2I [...]       ← 存储到主槽位
[MSM-BDS-CELL] sat=125 k=2 sig=7I SKIPPED      ← 某些cell被跳过
[MSM-BDS-OBS-BEFORE] sat=125 code=[...]        ← promoteExtSig之前的状态
[PROMOTE-SIG] sat=125 freq=2: promoted ...     ← promoteExtSig执行
[MSM-BDS-OBS-AFTER] sat=125 code=[...]         ← promoteExtSig之后的状态
```

### 3.3 如何启用详细日志

```java
// Rtcm.java 中已有的调试代码 (BDS专用)
if (sys == Constants.SYS_CMP && i == 0 && sat != 0) {
    // promoteExtSig前后打印obs状态
    System.err.printf("[MSM-BDS-OBS-BEFORE] sat=%d ...\n", sat);
    // ... promoteExtSig ...
    System.err.printf("[MSM-BDS-OBS-AFTER] sat=%d ...\n", sat);
}
```

**要为其他系统启用类似日志**:
```java
// 修改 saveMsmObs() 方法
if (sat != 0 && index >= 0)0) {
    // 添加通用调试（不仅限于BDS）
    if (true) {  // 改为始终打印
        Obsd o = this.obs.data[index];
        System.err.printf("[MSM-OBS-BEFORE] sys=%d sat=%d code=[%d,%d,%d]%n",
            sys, sat, o.code[0], o.code[1], o.code[2]);
    }
    
    promoteExtSig(this.obs.data[index], sys);
    
    if (true) {
        Obsd o = this.obs.data[index];
        System.err.printf("[MSM-OBS-AFTER] sys=%d sat=%d code=[%d,%d,%d]%n",
            sys, sat, o.code[0], o.code[1], o.code[2]);
    }
}
```

### 3.4 promoteExtSig 故障排查

**问题1: 某些卫星的主槽位始终为空**
- [ ] 检查MSM消息是否包含该频率的任何信号
- [ ] 检查code2idx返回值是否正确
- [ ] 检查sigindex是否将该信号错误地分到扩展区
- [ ] 启用[MSM-BDS-OBS-BEFORE/AFTER]日志对比

**问题2: promoteExtSig频繁触发**
- [ ] 正常现象: 说明原始信号分布不均
- [ ] 检查是否某个频率的主信号经常缺失
- [ ] 考虑调整getcodepri优先级表

**问题3: 定位精度下降**
- [ ] 检查提升后的信号SNR是否过低
- [ ] 检查是否有错误的频率匹配(code2idx bug)
- [ ] 对比提升前后的obs数据一致性

---

## 4. RTCM MSM 测试验证

### 4.1 单元测试用例

```java
@Test
void testPromoteExtSig_Basic() {
    Obsd obs = new Obsd();
    obs.sat = 125;
    
    // 设置主槽位0,1有数据，槽位2为空
    obs.code[0] = 40; obs.L[0] = 100.0; obs.P[0] = 20000000.0;
    obs.code[1] = 42; obs.L[1] = 150.0; obs.P[1] = 21000000.0;
    obs.code[2] = 0;  obs.L[2] = 0.0;    obs.P[2] = 0.0;
    
    // 扩展槽位3有B2a信号 (freq_idx=2)
    obs.code[3] = 61; obs.L[3] = 200.0; obs.P[3] = 22000000.0;
    
    // 执行提升
    Rtcm.promoteExtSig(obs, Constants.SYS_CMP);
    
    // 验证结果
    assertEquals(61, obs.code[2]);  // B2a被提升到主槽位2
    assertEquals(200.0, obs.L[2], 1e-6);
    assertEquals(0, obs.code[3]);   // 扩展槽位3被清空
}

@Test
void testPromoteExtSig_NoMatch() {
    Obsd obs = new Obsd();
    obs.sat = 106;
    
    // 主槽位2为空
    obs.code[2] = 0;
    
    // 扩展槽位有L"1信号 (freq_idx=0, 不匹配!)
    obs.code[3] = 2;  // B1P, freq_idx=0
    
    Rtcm.promoteExtSig(obs, Constants.SYS_CMP);
    
    // 验证: 不应该提升 (频率不匹配)
    assertEquals(0, obs.code[2]);  // 主槽位2仍为空
    assertEquals(2, obs.code[3]);  // 扩展槽位保持不变
}
```

### 4.2 集成测试验证

使用真实RTCM数据进行端到端测试:

```bash
# 运行RTCM解析测试
mvn test -Dtest=RtcmParserTest#testRoverRtcmParsing

# 检*查日志中的[PROMOTE-SIG]条目
grep "\[PROMOTE-SIG\]" target/surefire-reports/*.txt
- 对于高质量数据: 极少或无promoteExtSig触发（sigindex已正确分配）
- 对于缺失某些信号的数据: 适当数量的提升操作
- 无错误或异常
```

---

*最后更新：2026-08-13*