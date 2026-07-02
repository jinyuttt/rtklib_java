# RTKLIB Java版技术参考文档

> **用途**：记录Java版RTKLIB的关键数据结构、矩阵约定、状态索引、常量定义及已知问题，便于升级维护时快速定位问题。

---

## 1. 状态向量布局

### 1.1 状态索引宏定义（Java vs C）

| 宏/函数 | Java实现 | C版定义 | 说明 |
|---------|----------|---------|------|
| `NP(opt)` | `opt.dynamics==0 ? 3 : 9` | `(opt)->dynamics==0?3:9` | 位置状态数：Static=3, Kinematic=9(含速度+加速度) |
| `NI(opt)` | `ionoopt!=IONOOPT_EST ? 0 : MAXSAT` | 同左 | 电离层参数数 |
| `NT(opt)` | 三级判断：0/2/6 | 同左 | 对流层参数数 |
| `NL(opt)` | `glomodear!=GLO_ARMODE_AUTOCAL ? 0 : NFREQGLO` | 同左 | GLONASS IC bias数 |
| **`NR(opt)`** | `NP+NI+NT+NL` | 同左 | **非模糊度状态总数 = na** |
| **`IB(sat,f,opt)`** | `NR(opt) + MAXSAT*f + (sat-1)` | 同左 | **模糊度状态索引** |
| `II(sat,opt)` | `NP(opt) + (sat-1)` | 同左 | 电离层参数索引 |
| `IL(f,opt)` | `NP+NI+NT+f` | 同左 | GLONASS IC bias索引 |

### 1.2 当前测试配置的状态布局

```
na = NR(opt) = NP + NI + NT + NL = 3 + 0 + 0 + 2 = 5

状态向量 x[433] 布局：
┌─────────────────────────────────────────────────────────────┐
│ 索引范围    │ 状态类型          │ 数量 │ 说明               │
├─────────────┼──────────────────┼──────┼─────────────────────┤
│ [0..2]      │ 位置 (X,Y,Z)      │ 3    │ Static模式无速度   │
│ [3..4]      │ GLO IC bias L1/L2 │ 2    │ NL=NFREQGLO=2     │
│ [5..214]    │ 模糊度 L1         │ 210  │ na + 0*MAXSAT      │
│ [215..424]  │ 模糊度 L2         │ 210  │ na + 1*MAXSAT      │
│ [425..432]  │ (未使用)           │ 8    │ nf=2, 仅2个频率    │
└─────────────┴──────────────────┴──────┴─────────────────────┘

nx = NR(opt) + MAXSAT * NF(opt) = 5 + 210 * 2 = 425 (实际分配433)
```

### 1.3 模糊度索引示例

```java
// BDS C02 (sat=107) 的L1模糊度索引:
IB(107, 0, 2, opt) = 5 + 210 * 0 + (107 - 1) = 111

// BDS C08 (sat=113) 的L1模糊度索引:
IB(113, 0, 2, opt) = 5 + 210 * 0 + (113 - 1) = 117

// BDS C10 (sat=115) 的L2模糊度索引:
IB(115, 1, 2, opt) = 5 + 210 * 1 + (115 - 1) = 333
```

---

## 2. 关键常量对比

### 2.1 频率和卫星常量

| 常量 | Java值 | C值 | 差异说明 |
|------|--------|-----|---------|
| `NFREQ` | **6** | **3** | ⚠️ Java版Obsd数组大小=6, 但opt.nf=2 |
| `NFREQGLO` | 2 | 2 | ✅ 一致 |
| `MAXSAT` | 228 | 228 | ✅ 一致 |

### 2.2 MAXSAT 计算明细

```
MAXSAT = NSATGPS + NSATGLO + NSATGAL + NSATQZS + NSATCMP + NSATIRN + NSATSBS + NSATLEO
       = 32 + 27 + 36 + 10 + 46 + 14 + 39 + 10 = 228
```

| 系统 | PRN范围 | 卫星数 |
|------|---------|--------|
| GPS | 1~32 | 32 |
| GLONASS | 1~27 | 27 |
| Galileo | 1~36 | 36 |
| QZSS | 193~202 | 10 |
| BeiDou (CMP) | 1~46 | 46 |
| IRNSS | 1~14 | 14 |
| SBAS | 120~158 | 39 |
| LEO | 1~10 | 10 |

### 2.3 方差常量

| 常量 | 值 | 说明 |
|------|-----|------|
| `VAR_POS` | 900 (30² m²) | 初始位置方差 |
| `VAR_POS_FIX` | 1E-8 | 固定解位置方差 |
| `VAR_VEL` | 100 (10² m²/s²) | 初始速度方差 |
| `VAR_ACC` | 100 (10² m²/s⁴) | 初始加速度方差 |
| `VAR_AMB` | 900 (30² 周²) | 初始模糊度方差 |

---

## 3. 矩阵存储约定

### 3.1 核心差异：行优先 vs 列优先

| 版本 | 存储方式 | H矩阵访问 | P矩阵访问 |
|------|----------|-----------|-----------|
| **C版** | **列优先** (Fortran风格) | `H[state*n+obs]` | `P[i+j*n]` |
| **Java版** | **行优先** (C风格) | `H[obs*nx+state]` | `P[i*nx+j]` |

### 3.2 ddres 中 H 矩阵构建（Java版行优先）

```java
// 第nv个观测对第state个状态的偏导数
H[nv * rtk.nx + state] = value;

// 示例：第0个观测的位置X偏导数
H[0 * nx + 0] = -e_x_ref + e_x_j;  // 行优先：第0行第0列

// 示例：第0个观测的参考星模糊度偏导数
H[0 * nx + ii] = lami;              // ii = IB(sat_ref, frq, nf, opt)
```

### 3.3 KalmanFilter.update 中的压缩逻辑

```java
// 输入: x[n], P[n*n], H[m*n], v[m], R[m*m]
// 步骤1: 找出活跃状态 (x[i]!=0 且 P[i*n+i]>0)
int[] ix;  // 活跃状态索引数组, 长度 k

// 步骤2: 压缩到子空间
// xc[k]: x[ix[i]]
// Pc[k*k]: P[ix[i]*n+ix[j]]
// Hc[m*k]: H[j*n+ix[i]]  ← 注意: 行优先, j是观测号

for (int i = 0; i < k; i++) {
    for (int j = 0; j < m; j++) {
        Hc[j * k + i] = H[j * n + ix[i]];  // Hc[obs][state]
    }
}

// 步骤3: EJML运算
// K = Pc * Hc^T * (Hc * Pc * Hc^T + R)^-1
// xc_new = xc + K * v
// Pc_new = (I - K * Hc) * Pc

// 步骤4: 写回原数组
for (int i = 0; i < k; i++) {
    x[ix[i]] = XcNew.get(i, 0);
    for (int j = 0; j < k; j++) {
        P[ix[i] * n + ix[j]] = P_new.get(i, j);
    }
}
```

### 3.4 C版 filter_ 函数对应代码

```c
// C版列优先存储
// F[n*m]: F = P * H
matmul("NN",n,m,n,P,H,F);

// Q[m*m]: Q = H' * F + R = H' * P * H + R
matmulp("TN",m,m,n,H,F,Q);  // 注意: matmulp是乘加操作

// K[n*m]: K = F * Q^-1 = P * H * (H' * P * H + R)^-1
matmul("NM",n,m,m,F,Q,K);

// x_new = x + K * v
matmul("NN",n,1,n,K,v,xp);
matadd(x,xp,n,1,x);

// P_new = (I - K*H') * P  ← 注意: C版用H'不是H
matmul("MN",n,n,m,K,P,FP);  // FP = K * P
matmul("NN",n,n,n,FP,H,F);  // F = K * P * H'
for (i=0;i<n;i++) F[i+n*i]-=1.0;  // F = K*P*H' - I
matmul("NN",n,n,n,F,P,FP);        // FP = (K*P*H'-I)*P
matcpy(P,FP,n,n);                  // P_new = -(I-K*P*H')*P
```

**⚠️ 重要**: C版中 `P_new = (I-K*H')*P` 使用的是 `H'`（转置），而Java版使用 `Hc`（未转置）。两者等价是因为Java版的 `Hc` 已经是从 `H` 提取的子矩阵（行优先），相当于C版的 `H'` 的子集。

---

## 4. 关键函数签名与调用链

### 4.1 主流程

```
relpos()
  ├─ udstate()           // 时间更新
  │   ├─ udpos()         // 位置时间更新
  │   ├─ udion()         // 电离层时间更新
  │   ├─ udtrop()        // 对流层时间更新
  │   ├─ udrcvbias()     // GLONASS偏差时间更新
  │   └─ udbias()        // 模糊度时间更新
  ├─ zdres()             // 零差残差计算 → y[], e[]
  ├─ ddres()             // 双差残差/H/R计算 → v[], H[], R[]
  ├─ filter()            // Kalman滤波更新 → x[], P[]
  ├─ manage_amb_LAMBDA() // AR管理（位置检查→卫星排除→AR过滤）
  │   └─ resamb_LAMBDA() // LAMBDA固定 + Pa更新
  │       ├─ ddidx()     // 双差索引选择
  │       ├─ Lambda()    // 整数最小二乘搜索
  │       └─ restamb()   // 重置未固定模糊度
  └─ holdamb()           // Fix-and-Hold约束（可选）
```

### 4.2 ddres 函数关键变量

```java
private static int ddres(Rtk rtk, Obsd[] obs, int[] sat, int[] iu, int[] ir,
                         int ns, double[] y, double[] e,
                         Nav nav, double[] v, double[] H, double[] Ri, double[] Rj,
                         int nv, int flg) {

    // 输入:
    //   y[n_sat * nf * 2]: 零差残差 (载波相位+伪距)
    //   e[n_sat * 3]:     视线向量
    //   nv:               当前已用观测数起始值
    //
    // 输出:
    //   v[]:              双差残差 (追加)
    //   H[nv_total * nx]: 设计矩阵 (追加, 行优先)
    //   Ri[], Rj[]:       流动站/基准站观测噪声
    //   返回值:           新增的观测数

    int refIdx;  // 参考卫星在sat[]中的索引
    int idx_i = iu[refIdx];   // 参考星流动站观测索引
    int idx_ir = ir[refIdx];  // 参考星基准站观测索引
    int idx_j = iu[j];        // 非参考星流动站观测索引
    int idx_jr = ir[j];       // 非参考星基准站观测索引

    // 双差残差计算:
    v[nv] = (y[f + idx_i*nf*2] - y[f + idx_ir*nf*2])
          - (y[f + idx_j*nf*2] - y[f + idx_jr*nf*2]);

    // 模糊度修正 (仅载波相位):
    if (!code && opt.mode > PMODE_DGPS) {
        int ii = IB(sat[refIdx], frq, nf, opt);  // 参考星模糊度索引
        int jj = IB(sat[j], frq, nf, opt);        // 非参考星模糊度索引
        v[nv] -= lami * x[ii] - lamj * x[jj];

        // H矩阵设置 (行优先):
        H[nv * nx + ii] =  lami;   // 参考星偏导数 = +λ
        H[nv * nx + jj] = -lamj;   // 非参考星偏导数 = -λ
    }

    return nv_added;
}
```

### 4.3 resamb_LAMBDA 函数关键逻辑

```java
private static int resamb_LAMBDA(Rtk rtk, double[] v, double[] H, double[] R,
                                  int n, int m, Obsd[] obs, int[] sat,
                                  int[] iu, int[] ir, int ns, Nav nav) {

    // 1. 获取双差索引
    int nb = ddidx(rtk, ix, -1, -1, 0);

    // 2. 构建浮点模糊度和协方差
    for (int i = 0; i < nb; i++) {
        y[i] = rtk.x[ix[i*2]] - rtk.x[ix[i*2+1]];  // 双差浮点模糊度
        for (int j = 0; j < nb; j++) {
            // Qb[i*nb+j] = Var(b_i, b_j): 双差协方差
            Qb[i*nb+j] = P[ix[i*2]*nx+ix[j*2]] - P[ix[i*2]*nx+ix[j*2+1]]
                       - P[ix[i*2+1]*nx+ix[j*2]] + P[ix[i*2+1]*nx+ix[j*2+1]];
        }
    }

    // 3. LAMBDA整数最小二乘搜索
    Lambda.lambda(nb, 2, Qb, b, s);

    // 4. ratio检验: s[0]/s[1] > thresar[2](默认3.0)?
    if (s[1] <= 0.0 || s[0]/s[1] < thresar[2]) return 0;  // 失败

    // 5. 更新固定解状态向量 xa (仅前na个非模糊度状态)
    for (int i = 0; i < na; i++) rtk.xa[i] = rtk.x[i];

    // 6. 更新固定解协方差 Pa
    // Pa = P_aa - P_ab * Qbb^-1 * P_ab^T (条件协方差公式)
    // 其中 P_ab = P[na:nx, na:nx], Qbb = Qb
}
```

### 4.4 holdamb 函数关键逻辑

```java
private static void holdamb(Rtk rtk, Obsd[] obs, int[] sat, int[] iu, int[] ir,
                             int ns, Nav nav) {

    // Fix-and-Hold模式: 通过Kalman滤波将固定模糊度约束写回

    int nv = 0;
    double[] v = new double[MAXOBS * rtk.nf * 2];
    double[] H = new double[MAXOBS * rtk.nf * 2 * rtk.nx];
    double[] R = new double[MAXOBS * rtk.nf * 2];

    // 为每个固定的双差模糊度构建伪观测
    for (int i = 0; i < ns; i++) {
        if (rtk.ssat[sat[i]-1].fix[frq] != 2 && ...) continue;

        int ii = IB(sat[i], frq, nf, opt);
        v[nv] = rtk.xa[ii] - rtk.x[ii];  // 残差 = 固定值 - 浮点值
        H[nv * rtk.nx + ii] = 1.0;        // 设计矩阵
        R[nv] = varholdamb;                // 观测噪声 (默认0.001)
        nv++;
    }

    // Kalman滤波更新
    filter(rtk.x, rtk.P, H, v, R, rtk.nx, nv);

    rtk.holdambFlag = 1;
}
```

---

## 5. 当前问题诊断

### 5.1 核心症状

| 指标 | 当前值 | 目标值 | 状态 |
|------|--------|--------|------|
| Fix解比例 | **0%** (0/41) | >80% | ❌ |
| AR ratio | **1.04~1.65** | ≥3.0 | ❌ |
| 位置方差 posvar | 0.03~0.04 | <0.25 | ✅ |
| 载波相位残差 | <0.03m | <0.05m | ✅ |
| 模糊度方差 (30历元后) | **48~53** | <0.1 | ❌ |

### 5.2 问题根因分析

**矛盾现象**：
- 载波相位残差已经很小（<0.03m），说明模糊度估计值接近真值
- 但模糊度方差仍然很大（48~53周²），说明Kalman滤波没有有效约束模糊度
- 导致LAMBDA无法区分最优解和次优解（ratio低）

**可能原因排序**（按可能性）：

1. **Kalman增益分配不合理**
   - 症状：`I_KH_diag` 出现负值（数值不稳定）
   - 排查：添加K矩阵调试输出，验证模糊度状态的增益是否合理

2. **H矩阵压缩过程丢失信息**
   - 症状：`ddres` 构建的H矩阵正确，但 `KalmanFilter.update` 压缩后可能出错
   - 排查：对比压缩前后H矩阵的非零元素

3. **观测数量不足**
   - 当前：5颗BDS卫星 × 2频率 = 10个载波观测（含伪距共20个）
   - 状态数：12个活跃状态（3位置+2GLO bias+7模糊度）
   - 信息量勉强够用，但不如C版收敛快

4. **模糊度初始值过大**
   - C30初始值89.95周，双差达-107.58周
   - 大模糊度本身不是问题，但会降低ratio

### 5.3 已排除的原因

| 可能原因 | 排除依据 |
|----------|----------|
| `IB`函数索引错误 | ✅ 已验证 `IB(s,f,opt)=NR+MAXSAT*f+(s-1)` 与C版一致 |
| `ddidx`星座分组错误 | ✅ 已按GPS/GLO/GAL/CMP分组 |
| `udpos`Static模式处理 | ✅ 直接返回不添加过程噪声，与C版一致 |
| `udbias`初始化错误 | ✅ 使用sdobs计算单差，与C版一致 |
| `GLONASS IC bias未处理 | ✅ 已添加IL函数和补偿逻辑 |
| 半周期标志LLI_HALFC | ✅ 已添加Ri/Rj方差补偿0.01 |

---

## 6. 调试日志格式说明

### 6.1 KalmanFilter 日志

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

### 6.2 ddres 日志

```
ddres: bl=320.5 m rb=(xxx,yyy,zzz) rr_f=(xxx,yyy,zzz)
ddres ref: f=0 ref=C08 sat=113 el=45.2°
ddres v: ref=113-107 L1 y_r=xxxx y_b=xxxx y_r2=xxxx y_b2=xxxx dd=0.0063
ddres e: e_ref=(a,b,c) e_j=(d,e,f) H_pos=(d-a,e-b,f-c)

pre-filter: nv=10 v=[113:10700=0.0063 113:11500=-0.0076 ...]
             Rdiag=[0.0001 0.0001 ... 7.7000 7.0170 ...]
             xp0=(14.87,254.55,-174.49) P0=18.5 P1=46.9 P2=27.1
```

### 6.3 resamb_LAMBDA 日志

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

### 6.4 udbias 日志

```
udbias init: sat=107 f=0 idx=111 bias=42.1714 var=900.0
udbias init: sat=113 f=0 idx=117 bias=-17.6240 var=900.0
udbias init: sat=115 f=0 idx=119 bias=-86.3637 var=900.0  # ⚠️ 异常大
...
```

---

## 7. 文件路径索引

### 7.1 核心源码文件

| 文件 | 关键内容 | 行号范围 |
|------|----------|----------|
| `RtkCore.java` | RTK核心算法 | 全文 |
| ├─ | 状态索引宏(NP/NI/NT/NL/NR/IB/II/IL) | 68~110 |
| ├─ | ddres(双差残差/H矩阵) | 870~1100 |
| ├─ | ddidx(双差索引选择) | 1746~1805 |
| ├─ | resamb_LAMBDA(LAMBDA固定) | 2000~2120 |
| ├─ | manage_amb_LAMBDA(AR管理) | 2120~2220 |
| ├─ | holdamb(Fix-and-Hold) | 2230~2270 |
| ├─ | udbias(模糊度时间更新) | 1430~1520 |
| ├─ | udstate/udpos/udion/udtrop/udrcvbias | 1195~1320 |
| └─ | relpos(主定位函数) | 1900~2000 |
| `KalmanFilter.java` | EKF测量更新 | 1~200 |
| `Constants.java` | 常量定义 | 63~240 |
| `PrcOpt.java` | 处理选项 | 全文 |
| `Rtk.java` | RTK状态变量 | 全文 |
| `Lambda.java` | LAMBDA算法 | 全文 |
| `MatrixUtil.java` | EJML工具封装 | 全文 |

### 7.2 测试文件

| 文件 | 用途 |
|------|------|
| `RtkTest.java` | 功能测试用例 |
| `ResultWriter.java` | 结果输出(ECEF格式) |
| `test_output.txt` | 最新测试日志 |

### 7.3 参考文件

| 文件 | 用途 |
|------|------|
| `RTKLIB-2.5.0/src/rtkpos.c` | C版核心算法参考 |
| `RTKLIB-2.5.0/src/rtklib.h` | C版常量和宏定义 |

---

## 8. 配置模板

### 8.1 当前测试配置（RtkTest.java）

```java
rtk.opt.mode = PMODE_STATIC;                    // Static定位模式
rtk.opt.nf = 2;                                 // 双频
rtk.opt.navsys = SYS_GPS|SYS_GLO|SYS_GAL|SYS_CMP; // 多系统
rtk.opt.modear = ARMODE_FIXHOLD;                // Fix-and-Hold
rtk.opt.glomodear = GLO_ARMODE_AUTOCAL;         // GLO自动校准
rtk.opt.gpsmodear = 1;                          // GPS AR开启
rtk.opt.bdsmodear = 1;                          // BDS AR开启
rtk.opt.elmin = 15.0*D2R;                       // 截止高度角15°
rtk.opt.thresar[0] = 30.0;                      // 最大卫星几何精度因子
rtk.opt.thresar[1] = 0.25;                      // 最大位置方差阈值(m²)
rtk.opt.thresar[2] = 3.0;                       // AR ratio阈值
rtk.opt.varholdamb = 0.001;                     // Hold约束方差
```

### 8.2 数据文件路径

```java
ROVER_PATH = "<rover_rtcm3_file_path>";
BASE_PATH  = "<base_rtcm3_file_path>";
```

---

## 9. 已完成的修复清单

| 序号 | 问题 | 修复日期 | 状态 |
|------|------|----------|------|
| 1 | 缺少manage_amb_LAMBDA函数 | 2026-07-02 | ✅ 完成 |
| 2 | 缺少Pa协方差更新 | 2026-07-02 | ✅ 完成 |
| 3 | ddidx星座分组不同 | 2026-07-02 | ✅ 完成 |
| 4 | NFREQ默认值6→2 | 2026-07-02 | ✅ 完成 |
| 5 | Fix解输出方式不同 | 2026-07-02 | ✅ 完成 |
| 6 | xa初始化复制全部nx | 2026-07-02 | ✅ 完成 |
| 7 | holdamb缺少xa更新 | 2026-07-02 | ✅ 完成 |
| 8 | holdamb H矩阵索引错误 | 2026-07-02 | ✅ 完成 |
| 9 | GLONASS IC bias未处理 | 2026-07-02 | ✅ 完成 |
| 10 | 半周期标志LLI_HALFC | 2026-07-02 | ✅ 完成 |
| 11 | udstate缺少udion/udtrop/udrcvbias | 2026-07-02 | ✅ 完成 |
| 12 | udpos Kinematic模式重置逻辑 | 2026-07-02 | ✅ 完成 |

---

## 10. 待解决问题

### 10.1 高优先级

- [ ] **模糊度方差收敛慢**：30历元后仍为48~53，目标<0.1
- [ ] **AR ratio低**：当前1.04~1.65，目标≥3.0
- [ ] **Fix解比例为0%**：需要先解决上述两个问题

### 10.2 中优先级

- [ ] **I_KH_diag负值**：数值稳定性问题，可能导致长期运行发散
- [ ] **NFREQ=6影响评估**：Obsd数组大小为6但实际只用2，内存浪费
- [ ] **C版编译运行**：用于结果对比验证

### 10.3 低优先级

- [ ] **调试日志优化**：减少生产环境输出
- [ ] **性能优化**：EJML矩阵运算可考虑并行化

---

## 11. 附录：快速排查清单

当遇到"Fix解比例低"问题时，按以下顺序排查：

1. **检查配置**：`modear`, `bdsmodear`, `gpsmodear`, `glomodear` 是否开启？
2. **检查数据**：是否有足够的公共卫星（≥5颗）？截止高度角是否合理？
3. **检查posvar**：是否满足 `< thresar[1]=0.25`？如果不满足，检查`udpos`。
4. **检查模糊度方差**：`P_diag` 中模糊度项是否在收敛？如果不变，检查`ddres`中H矩阵。
5. **检查ratio**：如果ratio始终<2，检查`Qb`矩阵（双差协方差）是否合理。
6. **检查I_KH_diag**：如果有负值，说明数值不稳定，需改用Joseph形式更新P。
7. **检查IB函数**：确认返回的索引与预期一致（特别是多频情况下的`f`参数）。

---

*文档版本：v1.0*
*最后更新：2026-07-02*
*维护者：RTKLIB Java移植团队*