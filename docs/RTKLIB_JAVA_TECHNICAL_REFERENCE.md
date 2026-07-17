# RTKLIB Java版技术参考文档

> **用途**：记录Java版RTKLIB的关键数据结构、矩阵约定、状态索引、常量定义及已知问题，便于升级维护时快速定位问题。

---

## 1. 状态向量布局

### 1.1 状态索引宏定义（Java vs C）

| 宏/函数 | Java实现 | C版定义 | 说明 |
|---------|----------|---------|------|
| `NP(opt)` | `opt.dynamics==0 ? 3 : 9` | `(opt)->dynamics==0?3:9` | 位置状态数：Static=3, Kinematic=9(含速度+加速度) |
| `NI(opt)` | `ionoopt!=IONOOPT_EST ? 0 : (opt.ionoGradient ? MAXSAT*3 : MAXSAT)` | `ionoopt!=IONOOPT_EST ? 0 : MAXSAT` | 电离层参数数（梯度模式每星3个） |
| `NT(opt)` | 三级判断：0/2/6 | 同左 | 对流层参数数 |
| `NL(opt)` | `glomodear!=GLO_ARMODE_AUTOCAL ? 0 : NFREQGLO` | 同左 | GLONASS IC bias数 |
| **`NR(opt)`** | `NP+NI+NT+NL` | 同左 | **非模糊度状态总数 = na** |
| **`IB(sat,f,opt)`** | `NR(opt) + MAXSAT*f + (sat-1)` | 同左 | **模糊度状态索引** |
| `II(sat,opt)` | `opt.ionoGradient ? NP+(sat-1)*3 : NP+(sat-1)` | `NP(opt) + (sat-1)` | 电离层参数索引（梯度模式+1=Gn,+2=Ge） |
| `IL(f,opt)` | `NP+NI+NT+f` | 同左 | GLONASS IC bias索引 |

### 1.2 当前测试配置的状态布局

```
na = NR(opt) = NP + NI + NT + NL = 3 + 0 + 0 + 2 = 5

状态向量 x[461] 布局（使用 IB(sat,f,opt) 按卫星号索引）：
┌─────────────────────────────────────────────────────────────┐
│ 索引范围      │ 状态类型          │ 数量 │ 说明             │
├───────────────┼──────────────────┼──────┼───────────────────┤
│ [0..2]        │ 位置 (X,Y,Z)      │ 3    │ Static模式无速度 │
│ [3..4]        │ GLO IC bias L1/L2 │ 2    │ NL=NFREQGLO=2   │
│ [5..232]      │ 模糊度 L1         │ 228  │ na + 0*MAXSAT    │
│ [233..460]    │ 模糊度 L2         │ 228  │ na + 1*MAXSAT    │
└───────────────┴──────────────────┴──────┴───────────────────┘

nx = NR(opt) + NB(opt) = 5 + 228 * 2 = 461

⚠️ 模糊度使用 IB(sat,f,opt) = NR + MAXSAT*f + (sat-1) 按卫星号索引，
同一卫星在不同历元的模糊度索引固定不变。
```

### 1.3 模糊度索引示例

```java
// BDS C02 (sat=107) 的L1模糊度索引:
IB(107, 0, opt) = 5 + 228 * 0 + (107 - 1) = 111

// BDS C08 (sat=113) 的L1模糊度索引:
IB(113, 0, opt) = 5 + 228 * 0 + (113 - 1) = 117

// BDS C10 (sat=115) 的L2模糊度索引:
IB(115, 1, opt) = 5 + 228 * 1 + (115 - 1) = 348
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

### 2.4 状态向量分配大小

| 常量 | 值 | 说明 |
|------|-----|------|
| `NX_RTK` | 1385 | 状态向量数组最大分配大小 |

```
NX_RTK = NP_max + NI_max + NT_max + NL_max + NB_max
       = 9       + 684      + 6      + 2       + 684
       = 1385

其中:
- NP_max = 9 (dynamics模式: 3位置 + 3速度 + 3加速度)
- NI_max = MAXSAT * 3 = 228 * 3 = 684 (ionoGradient模式: 每星3个电离层参数)
- NT_max = 6 (TROPOPT_ESTG模式: 流动站3 + 基准站3)
- NL_max = NFREQGLO = 2 (GLONASS IC bias)
- NB_max = MAXSAT * 3 = 684 (3频点模糊度)

⚠️ NX_RTK 定义必须在 MAXSAT 之后（Java static final 前向引用限制）
实际使用的 nx = NR(rtk) + NB(rtk)，通常远小于 NX_RTK
```

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
private static void holdamb(Rtk rtk, double[] xa) {
    // Fix-and-Hold模式: 通过Kalman滤波将固定模糊度约束写回

    int nx = rtk.nx;
    int nb = nx - rtk.na;  // 模糊度状态数
    int nv = 0;
    double[] v = new double[nb];       // 伪观测残差（最多nb个）
    double[] H = new double[nb * nx];  // 设计矩阵（最多nb行，每行nx列）

    // 按系统组(m)和频率(f)遍历，构建双差伪观测
    for (int m = 0; m < 6; m++) {
        for (int f = 0; f < nf; f++) {
            // 收集该组中 fix[f]==2 的卫星索引
            int n = 0;
            int[] index = new int[MAXSAT];
            for (int i = 0; i < MAXSAT; i++) {
                if (testSys(sys, m) && fix[f] == 2 && azel > elmask) {
                    index[n++] = IB(sat, f, nf, opt);
                    fix[f] = 3;  // 标记为hold
                }
            }
            // 双差伪观测: v[nv] = (xa[ref] - xa[i]) - (x[ref] - x[i])
            for (int i = 1; i < n; i++) {
                v[nv] = (xa[index[0]] - xa[index[i]]) - (rtk.x[index[0]] - rtk.x[index[i]]);
                H[nv * nx + index[0]] =  1.0;  // 参考星
                H[nv * nx + index[i]] = -1.0;  // 流动星
                nv++;
            }
        }
    }

    // Kalman滤波更新（nv ≤ nb，H矩阵分配nb*nx但只用nv*nx）
    double[] R = new double[nv * nv];
    for (int i = 0; i < nv; i++) R[i * nv + i] = varholdamb;
    filter(rtk.x, rtk.P, H, v, R, nx, nv);

    rtk.holdambFlag = 1;
}
```

---

## 5. 当前状态

### 5.1 RTK 定位性能（多系统短基线）

| 指标 | 当前值 | 目标值 | 状态 |
|------|--------|--------|------|
| Fix解比例 | **88.7%** (86/97) | >80% | ✅ |
| AR ratio | **42~384** | ≥3.0 | ✅ |
| 位置方差 posvar | 0.00015~0.00017 | <0.25 | ✅ |
| 载波相位残差 | <0.03m | <0.05m | ✅ |

### 5.2 关键修复：Joseph 形式协方差更新

早期版本使用标准形式 `P = (I-KH)*P` 更新协方差，在 H 矩阵病态条件下
（载波相位与伪距方差相差 4~5 个数量级，S 条件数 >39 万）导致 P 矩阵
失去正定性，AR ratio 极低（1.04~1.65），Fix 解比例为 0%。

改用 Joseph 形式 `P = (I-KH)*P*(I-KH)' + K*R*K'` 后：
- 保证 P 矩阵对称正定
- AR ratio 从 1.04~1.65 提升至 42~384
- Fix 解比例从 0% 提升至 88.7%

详见 `RTKLIB_Differences.md` 第6节。

### 5.3 已知限制

- **BDS-only 短基线数据质量差时无法 Fix**：C 版 RTKLIB 同样无法 Fix，属于数据质量问题
- **holdamb 偶发 filter error 警告**：与 C 版行为一致，C 版同样使用 `nb*nx` 分配 H 矩阵、实际使用 `nv*nx`（`nv ≤ nb`），LAPACK/EJML 求逆失败时均返回错误码

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
| `RtkCore.java` | RTK核心算法 | 全文（2142行） |
| ├─ | 状态索引宏(NP/NI/NT/NL/NR/IB/II/IL) | 74~110 |
| ├─ | rtkpos(外层入口) | 125~230 |
| ├─ | relpos(相对定位主函数) | 232~505 |
| ├─ | zdres(零差残差) | 660~810 |
| ├─ | ddres(双差残差/H矩阵) | 812~1125 |
| ├─ | udstate/udpos/udion/udtrop/udrcvbias/udbias | 1128~1460 |
| ├─ | detslpLl/detslpGf/detslpCode/detslpDop(周跳检测) | 1460~1560 |
| ├─ | ddidx(双差索引选择) | 1664~1780 |
| ├─ | resamb_LAMBDA(LAMBDA固定) | 1781~1913 |
| ├─ | manage_amb_LAMBDA(AR管理) | 1930~2040 |
| └─ | holdamb(Fix-and-Hold) | 2049~2140 |
| `KalmanFilter.java` | EKF测量更新(Joseph形式) | 全文（227行） |
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
| 13 | SPP dtr数组动态索引→固定索引 | 2026-07-12 | ✅ 完成 |

---

## 10. 待完善项

### 10.1 中优先级
- [ ] **基准站位置自动获取**：部分已实现（RINEX头 `APPROX POSITION XYZ` 自动读取、MOVEB模式SPP平均），缺失 `POSOPT_SINGLE`（非MOVEB模式SPP取平均）和 `POSOPT_FILE`（位置文件读取）

### 10.2 低优先级

- [ ] **Static Start长延迟恢复**：边界场景，`tt>300`时重置状态
- [ ] **多系统PPP验证**：GPS+BDS联合PPP，需多系统精密星历

---

## 11. 附录：快速排查清单

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

## 12. RTK引擎优化模块技术说明

> 五项核心优化均通过 `RtkConfig` 开关控制，默认全部关闭，不影响现有功能。

### 12.1 优化开关与执行时序

| 开关 | 优化项 | 默认值 |
|------|--------|--------|
| `enableParRefReselect` | 部分模糊度固定（PAR）与基准星动态重选 | `false` |
| `enableAdaptiveQ` | 自适应过程噪声与零速检测门控 | `false` |
| `enableIggiii` | 抗差M估计（IGG-III）等价权修正 | `false` |
| `enableSnrMedian` | SNR随机模型（动态中位数基准） | `false` |
| `enableIonoTropGradient` | 电离层/对流层梯度增强 | `false` |

执行时序（`relpos()` 内部）：
```
① computeSnrMedian()           → SNR中位数计算
② udstate() → udpos()          → Q缩放应用于时间更新
③ ddres() → varerr()           → SNR中位数权重修正
④ applyIggiii()                → 抗差修正R
⑤ filter()                     → Kalman滤波
⑥ computeQScale()              → 为下一历元Q做准备
⑦ resamb_LAMBDA() → ddidxPar() → PAR基准星重选+固定
```

### 12.2 HPHt对角线计算：EJML版本与Native版本

IGG-III需要计算新息协方差 `S = R + HPHᵀ` 的对角线元素，用于标准化新息。

**当前使用EJML版本** `computeHPHtDiag()`：
```java
// EJML实现：完整矩阵乘法后提取对角线
SimpleMatrix Hmat = MatrixUtil.createMatrix(H, nv, nx);
SimpleMatrix Pmat = MatrixUtil.createMatrix(P, nx, nx);
SimpleMatrix HPHt = Hmat.mult(Pmat).mult(Hmat.transpose());
for (int i = 0; i < nv; i++) diag[i] = HPHt.get(i, i);
```

**保留的Native版本** `computeHPHtDiagNative()`：
```java
// 手写数组运算：只算对角线，跳过非对角线元素
// Step1: PH[i,j] = Σ_k P[i,k] * H[j,k]
// Step2: diag[i] = Σ_k H[i,k] * PH[k,i]
```

| 版本 | 复杂度 | 优点 | 缺点 |
|------|--------|------|------|
| EJML（当前） | O(nx²×nv + nx×nv²) | 代码简洁，与项目风格一致 | 计算了nv²-nv个无用元素 |
| Native | O(nx²×nv + nx×nv) | 无冗余计算 | 手写循环，与项目EJML风格不一致 |

**性能差异**：典型RTK场景（nx=60, nv=60），FLOP浪费约49%，但绝对耗时在微秒级，1Hz RTK可忽略。
若需10~20Hz或嵌入式场景，可切换到Native版本。

### 12.3 PAR基准星动态重选与连续重选保护 ✅ 已实现 (2026-07-17) → 详见 12.11

**参考星跟踪**：`rtk.parPrevRefSat[f]` 记录每个频率上一历元的参考星卫星ID（1-based）。

**重选检测**：`ddidxPar()` 中比较 `parPrevRefSat[f]` 与当前排除列表，若上一历元参考星被排除则标记 `anyRefReselect=true`。

**连续重选保护**：
```
if (anyRefReselect) {
    parConsecutiveReselectCount++;
    if (parConsecutiveReselectCount > parMaxConsecutiveReselect) {
        // 清空排除列表，退回全模糊度固定
        parExcludedSatCount = 0;
        parConsecutiveReselectCount = 0;
        return ddidxFallback(rtk, ix, gps, glo, sbs);
    }
} else {
    parConsecutiveReselectCount = 0;
}
```

**退回策略**：`ddidxFallback()` 与原始 `ddidx()` 逻辑一致（不排除任何卫星），确保连续重选时PAR退化为全模糊度固定。

### 12.4 对流层梯度估计（TROPOPT_ESTG） ✅ 已实现 (2026-07-17) → 详见 12.11

Java版已补全C版RTKLIB的 `TROPOPT_ESTG` 支持，包括：

| 组件 | 修改 | 说明 |
|------|------|------|
| `udtrop()` | 补全梯度初始化和过程噪声 | ZWD初始化 `INIT_ZWD=0.15`，梯度初始化 `1E-6`/`VAR_GRA` |
| `prectrop()` | 新增方法 | 精确对流层延迟计算，含梯度项 |
| `ddres()` | 添加对流层残差修正和H矩阵梯度项 | `TROPOPT_EST`: 1项(ZWD), `TROPOPT_ESTG`: 3项(ZWD+Gn+Ge) |
| `zdres()` | 无需修改 | 仅加干分量，湿分量由状态估计 |

**状态布局变化**（`TROPOPT_ESTG` 时）：
```
NT = 6 (每站3个: ZWD + Gn + Ge, 共2站)
IT(0,opt) = NP + NI          // 流动站: ZWD, Gn, Ge
IT(1,opt) = NP + NI + 3      // 基准站: ZWD, Gn, Ge
```

**注意**：当前测试配置 `tropopt=TROPOPT_SAAS`（不估计对流层），上述代码路径未被测试。
需设置 `tropopt=TROPOPT_ESTG` 并使用长基线数据（>30km）验证。

### 12.5 电离层延迟估计（IONOOPT_EST） ✅ 已实现 (2026-07-17) → 详见 12.11

Java版已补全C版RTKLIB的 `IONOOPT_EST` 支持，包括：

| 组件 | 修改 | 说明 |
|------|------|------|
| `IonosphereModel.ionmapf()` | 新增方法 | 电离层映射函数，`1/cos(asin(...))`，对应C版 `rtkcmn.c:ionmapf()` |
| `ddres()` | 添加电离层映射因子计算 | `im[i] = (ionmapf(posu,azel)+ionmapf(posr,azel))/2.0` |
| `ddres()` | 添加电离层残差修正和H矩阵项 | 位置偏导数之后、对流层之前插入 |

**ddres中的IONOOPT_EST处理**（对应C版 rtkpos.c:1289-1298）：
```java
if (opt.ionoopt == IONOOPT_EST) {
    double didxi = (code ? -1.0 : 1.0) * im[refIdx] * SQR(FREQL1/freqi);
    double didxj = (code ? -1.0 : 1.0) * im[j] * SQR(FREQL1/freqj);
    int iiRef = II(sat[refIdx], opt);
    int iiJ = II(sat[j], opt);
    v[nv] -= didxi * x[iiRef] - didxj * x[iiJ];
    // 梯度模式：额外残差修正和H矩阵偏导数
    if (opt.ionoGradient) {
        double cotzRef = 1.0 / tan(elRef);
        double gradNRef = didxi * cotzRef * cos(azRef);
        double gradERef = didxi * cotzRef * sin(azRef);
        double cotzJ = 1.0 / tan(elJ);
        double gradNJ = didxj * cotzJ * cos(azJ);
        double gradEJ = didxj * cotzJ * sin(azJ);
        v[nv] -= gradNRef*x[iiRef+1] + gradERef*x[iiRef+2]
               - gradNJ*x[iiJ+1] - gradEJ*x[iiJ+2];
        H[nv*nx + iiRef+1] = gradNRef;
        H[nv*nx + iiRef+2] = gradERef;
        H[nv*nx + iiJ+1] = -gradNJ;
        H[nv*nx + iiJ+2] = -gradEJ;
    }
}
```

**梯度偏导数公式**（与对流层梯度类似，使用方向因子）：
```
∂I/∂VTEC = im * SQR(FREQL1/freq) * sign
∂I/∂Gn = ∂I/∂VTEC * cot(el) * cos(az)
∂I/∂Ge = ∂I/∂VTEC * cot(el) * sin(az)
```

**关键差异**：C版 `didxi/didxj` 使用 `sat[i]/sat[j]` 索引，Java版使用 `refIdx/j` 索引（`refIdx` 是参考星在 `sat[]` 数组中的下标）。

### 12.6 电离层梯度增强（enableIonoTropGradient） ✅ 已实现 (2026-07-17) → 详见 12.11

当 `RtkConfig.enableIonoTropGradient=true` 且 `ionoopt=IONOOPT_EST` 时，每颗卫星的电离层状态从1个扩展为3个（VTEC + Gn + Ge），通过 `PrcOpt.ionoGradient` 标志控制。

| 组件 | 修改 | 说明 |
|------|------|------|
| `PrcOpt.ionoGradient` | 新增字段 | 由 `RtkConfig.enableIonoTropGradient` 同步，`relpos()` 每历元设置 |
| `NI(opt)` | 修改 | `ionoGradient ? MAXSAT*3 : MAXSAT` |
| `II(sat,opt)` | 修改 | `ionoGradient ? NP+(sat-1)*3 : NP+(sat-1)` |
| `udion()` | 添加梯度初始化和过程噪声 | VTEC初始化同原版，Gn/Ge初始化 `1E-6`/`gradientIonoInitVar`，过程噪声 `gradientIonoPrn` |
| `ddres()` | 添加梯度残差修正和H矩阵项 | VTEC项同原版，Gn/Ge项含方向因子 `cot(el)*cos(az)`/`cot(el)*sin(az)` |

**状态布局变化**（`ionoGradient=true` 时）：
```
NI = MAXSAT * 3 (每星3个: VTEC + Gn + Ge)
II(sat,opt) = NP + (sat-1)*3    // VTEC
II(sat,opt)+1 = NP + (sat-1)*3+1  // Gn (南北梯度)
II(sat,opt)+2 = NP + (sat-1)*3+2  // Ge (东西梯度)
```

**风险控制**：默认 `ionoGradient=false`，不影响现有状态布局和功能。需 `enableIonoTropGradient=true` + `ionoopt=IONOOPT_EST` + 长基线数据才激活。

### 12.7 关键文件索引

| 文件 | 职责 |
|------|------|
| `config/RtkConfig.java` | 优化开关与参数配置 |
| `rtkpos/RtkOptimizations.java` | 所有优化算法实现 |
| `rtkpos/RtkCore.java` | RTK核心，集成优化调用点 |
| `data/Rtk.java` | RTK状态结构体，新增优化状态字段 |

### 12.8 滑动窗自适应Q矩阵（enableAdaptiveQ） ✅ 已实现 (2026-07-16)

#### 设计目标
传统Q矩阵使用固定过程噪声，无法区分静态/蠕变/滑动状态。静态时Q应极小以压制观测噪声，动态时需增大Q以避免滞后。

#### 新增字段
| 文件 | 字段 | 类型 | 说明 |
|------|------|------|------|
| Rtk.java | `xOld[3]` | double[] | 上一历元绝对ECEF位置 |
| Rtk.java | `posWin[100]` | double[] | 位置增量环形滑动窗缓冲区 |
| Rtk.java | `winIdx` | int | 滑动窗当前写入位置 |
| Rtk.java | `winCnt` | int | 滑动窗有效元素计数 |

#### 新增配置
| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `adaptiveQWinSize` | 50 | 滑动窗大小（历元） |
| `adaptiveQStaticThresh` | 0.001 m | 静态阈值 |
| `adaptiveQDynamicThresh` | 0.05 m | 动态阈值 |
| `adaptiveQScaleMinStatic` | 0.01 | 静态最小缩放因子 |
| `adaptiveQScaleMaxDynamic` | 5.0 | 动态最大缩放因子 |

#### 核心算法
```
1. 计算当前历元绝对位置 curPos = x[0:2] + rb[0:2]
2. 若 xOld 非零，计算位置增量 posInc = ||curPos - xOld||
3. 存入环形滑动窗 posWin[winIdx]，winIdx = (winIdx+1) % winSize
4. 更新 xOld = curPos
5. 计算滑动窗内位置增量的 RMS：σ_pos = sqrt(∑(x-μ)²/validCount)
6. Sigmoid 映射：
   - σ_pos ≤ 0.001m → α = 0.01（静态）
   - σ_pos ≥ 0.05m  → α = 5.0（动态）
   - 中间值 → S型过渡：sigmoid(t) = 1/(1+exp(-10*(t-0.5)))
7. 最终缩放 = α × nsFactor × pdopFactor × clamp(min, max)
8. udpos() 中 qh/qv *= qScale²
```

#### 实现位置
- `RtkOptimizations.computeQScale()`：完整滑动窗实现
- `RtkCore.udpos()`：已有 qScale 乘法逻辑

### 12.9 模糊度子集锚固（enableAmbAnchor） ✅ 已实现 (2026-07-16)

#### 设计目标
标准Fix-and-Hold在LAMBDA失败时重置所有模糊度。锚固机制将长期固定（≥100历元）的模糊度协方差压制到1e-9，数学上等价于"已知常数"。

#### 新增字段
| 文件 | 字段 | 类型 | 说明 |
|------|------|------|------|
| Rtk.java | `ambAnchored[MAXSAT*NF]` | boolean[] | 模糊度锚固标记 |
| Rtk.java | `ambAnchorCount[MAXSAT*NF]` | int[] | 连续固定历元计数 |

#### 新增配置
| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `enableAmbAnchor` | false | 锚固开关 |
| `ambAnchorMinFixCount` | 100 | 锚固所需连续固定历元数 |
| `ambAnchorVar` | 1e-9 | 锚固后的协方差值 |

#### 核心算法

**holdamb() 修改**：
```
1. SOLQ_FIX 时，对所有 fix[f]>0 的模糊度 ambAnchorCount[globalIdx]++
2. 连续固定 ≥100 历元 → ambAnchored[globalIdx] = true
3. 已锚固的模糊度，holdamb中 Rh 使用 ambAnchorVar(1e-9) 而非 varholdamb
4. 非 SOLQ_FIX 时，仅重置未固定卫星的 ambAnchorCount，不清空锚固标记
```

**resamb_LAMBDA() 修改**：
```
1. 分离已锚固和未锚固的模糊度（freeMap[] / anchoredMap[]）
2. 若全部锚固 → 直接返回 SOLQ_FIX（ratio=999.9）
3. 仅对未锚固子集提取 a/Qa 子矩阵
4. 对子集执行 LAMBDA 搜索
5. 固定成功后，已锚固值保持原值，未锚固值用 LAMBDA 结果
```

#### 实现位置
- `RtkCore.holdamb()`：锚固计数、协方差压制、失败不清空
- `RtkCore.resamb_LAMBDA()`：子集分离、子矩阵提取、子集LAMBDA

### 12.10 大气参数自适应冻结（atmFrozenNsThresh） ✅ 已实现 (2026-07-16)

#### 设计目标
卫星数少（<7颗）时，强行估计大气参数会导致法方程病态，滤波器将大气误差"吸收"进坐标分量，造成虚假位移。

#### 新增配置
| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `atmFrozenNsThresh` | 7 | 卫星数阈值，0=禁用 |

#### 核心算法
```
udion(): if (ns < atmFrozenNsThresh) return;  // 冻结电离层过程噪声更新
udtrop(): if (ns < atmFrozenNsThresh) return;  // 冻结对流层过程噪声更新
```

#### 实现位置
- `RtkCore.udion()`：第277行，RtkCore.java
- `RtkCore.udtrop()`：第297行，RtkCore.java

### 12.11 PAR重选、TROPOPT_ESTG、IONOOPT_EST、电离层梯度 ✅ 已实现 (2026-07-17)

#### 设计目标
四项优化通过配置控制，默认关闭，不影响已调试功能。扩展RTK观测模型，支持电离层/对流层参数估计及梯度项，提升复杂环境下的定位精度。

#### 新增配置
| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| `enableParRefReselect` | false | PAR参考星动态重选 |
| `ionoGradient` | false | 电离层梯度项启用 |
| `ionoopt` | IONOOPT_OFF | 电离层估计模式 |
| `tropopt` | TROPOPT_SAAS | 对流层估计模式 |

#### 核心算法

**PAR重选**：
```
ddidx(): if (enableParRefReselect) → buildParIndex() else → ddidxFallback()
```
- 动态参考星：基于高度角、信号质量、锁相状态
- 回退保护：`parMaxConsecutiveReselect` 超限时清空排除列表

**电离层估计（ddres）**：
```
if (ionoopt == IONOOPT_EST):
  imI = ionmapf(posI, azelI); imJ = ionmapf(posJ, azelJ)
  H[ionoI] += imI; H[ionoJ] -= imJ
  if (ionoGradient): H[+] = cotEl * cosAz/sinAz
```

**对流层梯度估计（prectrop + ddres）**：
```
prectrop():
  TROPOPT_EST:  dtdx[IT] = mw
  TROPOPT_ESTG: dtdx[IT/GN/GE] = mw; mw*cotEl*cosAz; mw*cotEl*sinAz

ddres(): H[k] += dtdxI[k] - dtdxJ[k]
```

#### 实现位置
- `RtkCore.ddidx()`、`II()`、`prectrop()`、`zdres()`、`ddres()`
- `RtkOptimizations.buildParIndex()`、`ddidxFallback()`

### 12.12 SingularMatrixException 安全修复 ✅ 已实现 (2026-07-17)

#### 问题分析

**错误链路**：
```
relpos() → computeQScale() → dops() → invert() → SingularMatrixException
```

**根因**：`dops()` 中 4×4 Q 矩阵可能奇异（卫星扎堆、共面、几何退化），C版 `matinv()` 返回 0，Java版 EJML 抛异常。此外 `computeQScale()` 中 `pdop=0` 时 `pdopFactor=2.0`，逻辑反转。

#### 修复方案：三层防护

| 层 | 文件 | 修改 | 作用 |
|----|------|------|------|
| 底层工具 | `MatrixUtil.java` | 新增 `invertSafe()` | 通用安全求逆，返回 `Optional<SimpleMatrix>` |
| 源头修复 | `RtklibCommon.dops()` | `invert()` → `invertSafe()` | 矩阵奇异时静默返回，DOP=0，对齐C版 |
| 调用方兜底 | `RtkOptimizations.computeQScale()` | `dop[1]==0` 检查 | 几何退化时 `pdopFactor=1.0`，保守策略 |

**invertSafe()**：
```java
public static Optional<SimpleMatrix> invertSafe(SimpleMatrix A) {
    try { return Optional.of(A.invert()); }
    catch (SingularMatrixException e) { return Optional.empty(); }
}
```

**dops()**：`invert()` → `invertSafe()`，失败时 DOP=0 静默返回

**computeQScale()**：`if (dop[1] > 0) pdopFactor = min(ref/dop[1], 2.0)` else `pdopFactor=1.0`

#### 实现位置
- `MatrixUtil.invertSafe()`、`RtklibCommon.dops()`、`RtkOptimizations.computeQScale()`

---

*文档版本：v1.6*
*最后更新：2026-07-17*
*维护者：RTKLIB Java移植团队*