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

## 6. 文件路径索引

### 6.1 核心源码文件

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

### 6.2 测试文件

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

## 7. 配置模板

### 7.1 当前测试配置（RtkTest.java）

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

### 7.2 数据文件路径

```java
ROVER_PATH = "<rover_rtcm3_file_path>";
BASE_PATH  = "<base_rtcm3_file_path>";
```

---

## 8. 已完成的修复清单

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

## 9. 待完善项

### 9.1 中优先级
- [ ] **基准站位置自动获取**：部分已实现（RINEX头 `APPROX POSITION XYZ` 自动读取、MOVEB模式SPP平均），缺失 `POSOPT_SINGLE`（RtkProcessor实时流中空实现，PostPosProcessor批处理已有avepos()）和 `POSOPT_FILE`（位置文件读取，PostPosProcessor中fallback到RINEX header）
- [ ] **SSR相位偏差改正**：SSR解码已实现（RTCM 1057-1068），但 `corr_phase_bias_ssr()` 未实现，SSR改正仅含轨道/钟差，不含相位偏差

### 9.2 低优先级

- [ ] **Static Start长延迟恢复**：边界场景，`tt>300`时重置状态
- [ ] **多系统PPP验证**：GPS+BDS联合PPP，需多系统精密星历
- [ ] **CombinedFilter速度smoother**：C版 `combres()` 对速度也做RTS平滑，Java版仅对位置做smoother，速度直接取正向值
- [ ] **udtrop()冻结**：`atmFrozenNsThresh` 仅在 `udion()` 中实现冻结逻辑，`udtrop()` 尚未实现冻结

---

## 10. RTK引擎扩展与C版对齐实现

> 本节记录与C版RTKLIB对齐的功能实现（TROPOPT_ESTG、IONOOPT_EST、电离层梯度等）及Java版特有的安全修复。
> Java版独有的额外优化项（自适应Q、模糊度锚固、大气冻结、IGGIII、SNR中值、PAR重选等）详见 [优化文档](RTK_Extra_Optimizations.md)。

### 10.1 扩展功能总览

| 功能 | 分类 | 对应C版 | 说明 |
|------|------|---------|------|
| TROPOPT_ESTG | C版对齐 | ✅ rtkpos.c | 对流层梯度估计 |
| IONOOPT_EST | C版对齐 | ✅ rtkpos.c | 电离层延迟估计 |
| 电离层梯度增强 | C版对齐 | ✅ ionoGradient | 梯度参数纳入RtkConfig管理 |
| SingularMatrix修复 | Java特有 | — | EJML异常安全防护 |
| 自适应Q矩阵 | 额外优化 | — | 详见 [优化文档§1](RTK_Extra_Optimizations.md#1-滑动窗自适应q矩阵enableadaptiveq) |
| 模糊度子集锚固 | 额外优化 | — | 详见 [优化文档§2](RTK_Extra_Optimizations.md#2-模糊度子集锚固enableambanchor) |
| 大气参数冻结 | 额外优化 | — | 详见 [优化文档§3](RTK_Extra_Optimizations.md#3-大气参数自适应冻结atmfrozennsthresh) |
| IGGIII抗差估计 | 额外优化 | — | 详见 [优化文档§4](RTK_Extra_Optimizations.md#4-iggiii抗差估计enableiggiii) |
| SNR中值参考星 | 额外优化 | — | 详见 [优化文档§5](RTK_Extra_Optimizations.md#5-snr中值参考星选择enablesnrmedian) |
| PAR参考星重选 | 额外优化 | — | 详见 [优化文档§6](RTK_Extra_Optimizations.md#6-par参考星重选enableparrefreselect) |

### 10.2 对流层梯度估计（TROPOPT_ESTG） ✅ 已实现 (2026-07-17)

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

### 10.3 电离层延迟估计（IONOOPT_EST） ✅ 已实现 (2026-07-17)

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

### 10.4 电离层梯度增强（enableIonoTropGradient） ✅ 已实现 (2026-07-17)

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

### 10.5 关键文件索引

| 文件 | 职责 |
|------|------|
| `config/RtkConfig.java` | 优化开关与参数配置 |
| `rtkpos/RtkOptimizations.java` | 所有优化算法实现 |
| `rtkpos/RtkCore.java` | RTK核心，集成优化调用点 |
| `data/Rtk.java` | RTK状态结构体，新增优化状态字段 |
### 10.6 SingularMatrixException 安全修复 ✅ 已实现 (2026-07-17)

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

## 11. RTCM MSM多信号管理与promoteExtSig机制

### 11.1 设计背景与问题

#### 11.1.1 RTKLIB观测值存储架构

RTKLIB使用**固定大小的频率槽位**存储卫星观测值：

```
Obsd（单个卫星的观测数据）:
┌─────────────────────────────────────────────────────────────┐
│ 字段          │ 索引范围         │ 说明                     │
├───────────────┼──────────────────┼──────────────────────────┤
│ L[] (载波相位) │ [0..NFREQ-1]     │ 主频率槽位 (L1,L2,L5...)  │
│ P[] (伪距)    │ [0..NFREQ-1]     │ 主频率槽位               │
│ D[] (多普勒)   │ [0..NFREQ-1]     │ 主频率槽位               │
│ LLI[] (周跳)  │ [0..NFREQ-1]     │ 主频率槽位               │
│ SNR[] (信噪比) │ [0..NFREQ-1]     │ 主频率槽位               │
│ code[] (信号码)│ [0..NFREQ-1]     │ 主频率槽位               │
├───────────────┼──────────────────┼──────────────────────────┤
│ L[]           │ [NFREQ..NFREQ+NEXOBS-1] │ 扩展槽位 (备用)    │
│ P[]           │ [NFREQ..NFREQ+NEXOBS-1] │ 扩展槽位          │
│ ...           │ ...              │ 扩展槽位                 │
└───────────────┴──────────────────┴──────────────────────────┘

Java版: NFREQ=6, NEXOBS=26 → 总共32个信号槽位/卫星
C版:   NFREQ=3, NEXOBS=...  → 通常8-16个信号槽位/卫星
```

**关键限制**：
- **主频率槽位有限**: RTK处理通常只使用前2-3个主槽位（L1, L2）
- **MSM消息可能包含更多信号**: 如BDS的B1I/B1C/B2I/B2a/B2b/B3I等6+个信号
- **需要智能分配**: 将最重要的信号放入主槽位，其余放入扩展槽位

#### 11.1.2 RTCM MSM消息的特点

**MSM (Multiple Signal Messages)** 是新一代RTCM观测值格式：

| 特性 | 传统格式 (1001-1004) | MSM格式 (1074-1077) |
|------|---------------------|---------------------|
| 支持信号数 | 每频段1个 | 每频段多个（最多32个） |
| 信号标识 | 隐式（由消息类型决定） | 显式（signal-mask） |
| 数据内容 | PR+CP+CNR | PR+CP+CNR+PRR+LLI |
| 适用场景 | 基础GNSS | 多系统多信号 |

**问题场景示例**（BDS卫星125）：
```
MSM消息包含6个信号:
[0] B1I (code=40) → freq_idx=0
[1] B3I (code=42) → freq_idx=1  
[2] B2I (code=27) → freq_idx=?
[3] B2P (code=58) → freq_idx=?
[4] B2a/D (code=61) → freq_idx=2
[5] B1P (code=2)  → freq_idx=0

但RTK只需要: L1(任意), L2(任意), L3(可选)
```

### 11.2 核心方法：sigindex - 信号优先级分配

#### 11.2.1 方法签名

```java
// ObsCode.java:354
public static void sigindex(int sys, int[] code, int n, int[] idx)
```

**参数说明**：
- `sys`: 卫星系统 (SYS_GPS/SYS_GLO/SYS_GAL/SYS_CMP...)
- `code[n]`: 输入的信号码数组 (如 {40,42,27,58,61,2})
- `n`: 信号数量
- `idx[n]`: 输入输出 - 频率索引数组（输入来自code2idx，输出为分配结果）

#### 11.2.2 分配算法

```
算法流程:
1. 初始化: pri_h[8]={0}, index[8]={0}, ex[32]={0}
2. 遍历所有信号 i=0..n-1:
   a. 获取该信号的频率索引 idx[i]
   b. 如果 idx[i] >= NFREQ → 标记 ex[i]=1 (扩展槽位)
   c. 计算信号优先级 pri = getcodepri(sys, code[i])
   d. 如果 pri > pri_h[idx[i]] (当前最高):
      - 将原占用者挤到扩展区: ex[index[idx[i]]-1]=1
      - 更新 pri_h[idx[i]]=pri, index[idx[i]]=i+1
   e. 否则 → 标记 ex[i]=1 (被挤出)
3. 分配扩展槽位:
   对 ex[i]=1 的信号, 分配 idx[i] = NFREQ + nex++
4. 超出NEXOBS容量的信号: idx[i] = -1 (丢弃)
```

#### 11.2.3 信号优先级定义

```java
// ObsCode.java 内部逻辑 (简化)
private static int getcodepri(int sys, int code) {
    // C码 > P码 > 其他码
    // GPS: L1C/L2C > L1P/L2P > L1W/L2W > ...
    // BDS: B1I/B3I > B1C/B2a > B2P/B3Q > ...
    
    switch (sys) {
        case Constants.SYS_GPS:
            if (code == Constants.CODE_L1C || code == Constants.CODE_L2C) return 7; // 最高
            if (code == Constants.CODE_L1P || code == Constants.CODE_L2P) return 6;
            return 4;
        case Constants.SYS_CMP:  // BeiDou
            if (code == Constants.CODE_B1I || code == Constants.CODE_B3I) return 7;
            if (code == Constants.CODE_B1C || code == Constants.CODE_B2A) return 6;
            return 4;
        // ... 其他系统类似
    }
}
```

**优先级规则**：
1. **跟踪精度**: C码（民用）> P码（精密）> 其他
2. **兼容性**: 传统信号 > 新增信号
3. **稳定性**: 开放服务 > 授权服务

### 13.3 核心方法：promoteExtSig - 扩展信号提升

#### 13.3.1 方法签名与位置

```java
// Rtcm.java:1747-1773
private static void promoteExtSig(Obsd obs, int sys)
```

**调用时机**: 在 `saveMsmObs()` 方法中，每个卫星的观测值存储完成后立即调用

```java
// Rtcm.java:1737
if (sat != 0 && index >= 0) {
    promoteExtSig(this.obs.data[index], sys);  // ← 在此调用
}
```

#### 13.3.2 算法详解

```java
private static void promoteExtSig(Obsd obs, int sys) {
    // 遍历所有主频率槽位 f=0,1,2,...,NFREQ-1
    for (int f = 0; f < Constants.NFREQ; f++) {
        
        // 条件1: 该槽位已有有效数据 → 跳过
        if (obs.code[f] != 0 && (obs.L[f] != 0.0 || obs.P[f] != 0.0)) continue;
        
        int freqIdx = f;  // 目标频率索引
        
        // 搜索扩展槽位，寻找同频率的备用信号
        for (int ex = Constants.NFREQ; ex < Constants.NFREQ + Constants.NEXOBS; ex++) {
            
            // 条件2: 扩展槽位为空 → 跳过
            if (obs.code[ex] == 0) continue;
            
            // 条件3: 检查频率是否匹配
            int sigFreqIdx = ObsCode.code2idx(sys, obs.code[ex]);
            if (sigFreqIdx != freqIdx) continue;
            
            // 条件4: 扩展槽位有实际观测数据
            if (obs.L[ex] == 0.0 && obs.P[ex] == 0.0) continue;
            
            // ✅ 找到匹配！执行提升操作:
            
            // Step 1: 复制所有观测值到主槽位
            obs.L[f]   = obs.L[ex];       // 载波相位
            obs.P[f]   = obs.P[ex];        // 伪距
            obs.D[f]   = obs.D[ex];        // 多普勒
            obs.LLI[f] = obs.LLI[ex];      // 周跳指示
            obs.SNR[f] = obs.SNR[ex];      // 信噪比
            obs.code[f] = obs.code[ex];    // 信号码
            
            // Step 2: 清空扩展槽位（避免重复使用）
            obs.L[ex]   = 0.0;
            obs.P[ex]   = 0.0;
            obs.D[ex]   = 0.0f;
            obs.LLI[ex] = 0;
            obs.SNR[ex] = 0.0f;
            obs.code[ex] = 0;
            
            // 调试日志
            System.err.printf("[PROMOTE-SIG] sat=%d freq=%d: promoted code=%d from ext idx=%d to idx=%d%n",
                obs.sat, f, obs.code[f], ex, f);
            
            break;  // 只提升第一个匹配的信号
        }
    }
}
```

#### 13.3.3 提升条件矩阵

| 条件 | 代码 | 含义 | 示例 |
|------|------|------|------|
| **主槽位空闲** | `obs.code[f]==0 \|\| (L==0 && P==0)` | 无有效数据 | L2无观测 |
| **扩展槽位非空** | `obs.code[ex]!=0` | 有备选信号 | 有B2a在ext |
| **频率匹配** | `code2idx(sys,code[ex])==f` | 同一频率 | 都是L2频段 |
| **数据有效** | `L!=0 \|\| P!=0` | 有观测值 | 非空洞 |

**只有4个条件全部满足时才执行提升**

### 13.4 完整工作流程示例

#### 13.4.1 场景：BDS卫星125接收MSM消息

**Step 1: 解析MSM Header获取信号列表**
```
type=1124 (BDS MSM4) nsig=6
signals: [2I, 6I, 7I, 5P, 7D, 1P]

转换为code数组:
code = [40, 42, 27, 58, 61, 2]
```

**Step 2: code2idx计算初始频率索引**
```
code[0]=40 (B1I) → freq_idx=0  (B1频段)
code[1]=42 (B3I) → freq_idx=1  (B3频段)
code[2]=27 (B2I) → freq_idx=?  (B2频段，可能=2或=-1)
code[3]=58 (B2P) → freq_idx=?  (B2频段)
code[4]=61 (B2a)→ freq_idx=2  (B2频段) ✓
code[5]=2  (B1P) → freq_idx=0  (B1频段)

idx = [0, 1, ?, ?, 2, 0]
```

**Step 3: sigindex优先级分配**
```
优先级排序 (BDS): B1I(7)=B3I(7) > B2a(6) > B2P(4) > B2I(4) > B1P(6)

分配过程:
i=0: B1I pri=7, idx[0]=0 → 占用主槽位0 ✓
i=1: B3I pri=7, idx[1]=1 → 占用主槽位1 ✓
i=2: B2I pri=4, idx[2]=2 → 占用主槽位2 (暂定)
i=3: B2P pri=4, idx[3]=2 → pri相等，不替换 → ex[3]=1
i=4: B2a pri=6, idx[4]=2 → pri>4! 挤走B2I → 
     - ex[2]=1 (B2I被挤到扩展)
     - 主槽位2=B2a ✓
i=5: B1P pri=6, idx[5]=0 → pri==7? 不大于 → ex[5]=1

最终分配:
idx = [0, 1, 3, 4, 2, 5]  // [主,主,扩,扩,主,扩]
```

**Step 4: saveMsmObs存储观测值**
```
按idx顺序存储到obs.data[index]:

主槽位0 (idx=0): code=40(B1I), L=198201898.04, P=38062587.71
主槽位1 (idx=1): code=42(B3I), L=153262284.23, P=38062569.32
主槽位2 (idx=4): code=61(B2a), L=97050968.09,  P=38062535.72

扩展槽位3 (idx=2): code=27(B2I), L=94583570.63,  P=24102538.53
扩展槽位4 (idx=3): code=58(B2P), L=0.0,           P=24102538.53  (仅伪距)
扩展槽位5 (idx=5): code=2(B1P),  L=0.0,           P=0.0          (无数据)
```

**Step 5: promoteExtSig提升检查**
```
检查主槽位 f=0: code=40≠0 且 L≠0 → 已填充 ✓ 跳过
检查主槽位 f=1: code=42≠0 且 L≠0 → 已填充 ✓ 跳过
检查主槽位 f=2: code=61≠0 且 L≠0 → 已填充 ✓ 路过

结果: 无需提升（理想情况）
```

**另一种情况（如果B2a没有数据）**:
```
假设主槽位2: code=0, L=0, P=0 (空)

promoteExtSig执行:
  f=2: 发现主槽位为空!
  搜索扩展槽位:
    ex=3: code=27(B2I), code2idx→2=freqIdx ✓ 匹配!
         L=94583570.63 ≠0 ✓ 有效!
         
  执行提升:
    obs.L[2]   = 94583570.63  (从ex=3复制)
    obs.P[2]   = 24102538.53
    obs.code[2] = 27
    
    清空ex=3:
    obs.L[3]   = 0.0
    obs.code[3] = 0
  
  日志输出:
  [PROMOTE-SIG] sat=125 freq=2: promoted code=27 from ext idx=3 to idx=2
```

### 13.5 与其他方法的协作关系

#### 13.5.1 三阶段信号管理流水线

```
┌─────────────────────────────────────────────────────────────────┐
│                    RTCM MSM解码流程                              │
├─────────────────────────────────────────────────────────────────┤
│                                                                 │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────────┐  │
│  │ Phase 1      │    │ Phase 2      │    │ Phase 3          │  │
│  │ code2idx()   │ →  │ sigindex()   │ →  │ promoteExtSig()  │  │
│  │              │    │              │    │                  │  │
│  │ 信号码→频率  │    │ 优先级分配   │    │ 填补空缺槽位     │  │
│  │ 索引映射     │    │ 到主/扩展区  │    │ 从扩展区提升     │  │
│  └──────────────┘    └──────────────┘    └──────────────────┘  │
│         ↓                   ↓                    ↓             │
│  idx[0]=0            idx[0]=0              主槽位全满          │
│  idx[1]=1            idx[1]=1              (或尽量满)          │
│  idx[2]=?            idx[2]=3(扩展)                            │
│  idx[3]=?            idx[3]=4(扩展)                            │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

#### 13.5.2 各方法的职责边界

| 方法 | 输入 | 输出 | 决策依据 | 作用域 |
|------|------|------|----------|--------|
| **code2idx** | (sys, code) | freq_idx (-1~5) | 物理频率 | 单个信号 |
| **sigindex** | (sys, code[], n) | idx[] (修改后) | 信号优先级 | 所有信号全局 |
| **promoteExtSig** | (obs, sys) | obs (修改后) | 频率匹配+数据有效性 | 单个卫星 |

### 13.6 关键常量配置

#### 13.6.1 Java版 vs C版的差异

| 参数 | Java版 | C版 (rtklib 2.5.0) | 影响 |
|------|--------|-------------------|------|
| `NFREQ` | **6** | **3** | Java可追踪更多主频率 |
| `NEXOBS` | **26** | **NEXOBS=12** (典型) | Java有更大扩展空间 |
| **总槽位数** | **32** | **15** (典型) | Java支持更多信号 |

#### 13.6.2 Obsd数组大小影响

```java
// Java版定义 (Constants.java)
public static final int NFREQ  = 6;   // 主频率数
public static final int NEXOBS = 26;  // 扩展观测数

// Obsd类字段声明
public class Obsd {
    public double L[]   = new double[NFREQ + NEXOBS];  // 32个元素
    public double P[]   = new double[NFREQ + NEXOBS];
    public float  D[]   = new float[NFREQ + NEXOBS];
    public short  LLI[] = new short[NFREQ + NEXOBS];
    public float  SNR[] = new float[NFREQ + NEXOBS];
    public byte   code[]= new byte[NFREQ + NEXOBS];
}
```

**内存开销**:
- 每个Obsd对象: ~32 * (8+8+4+2+4+1) = **864 bytes**
- MAXSAT=228个卫星: **197 KB** (单历元)
- 可接受的开销换取灵活性

### 13.7 典型应用场景

#### 13.7.1 场景1：BDS三频信号完整接收

**输入信号**: B1I, B1C, B2I, B2a, B2b, B3I, B3Q (7个)

**期望输出** (opt.nf=3时):
```
主槽位0 (L1): B1I (优先级最高)
主槽位1 (L2): B2a (B2频段最佳)
主槽位2 (L3): B3I (优先级最高)

扩展区: B1C, B2I, B2b, B3Q (备用)
```

**执行过程**:
1. `code2idx`: B1I→0, B1C→0, B2I→?, B2a→2, B2b→?, B3I→1, B3Q→1
2. `sigindex`: 
   - L1: B1I胜出(pri=7), B1C被挤到扩展
   - L2: B2a胜出(pri=6), B2I/B2b被挤到扩展
   - L3: B3I胜出(pri=7), B3Q被挤到扩展
3. `promoteExtSig`: 主槽位已满，无需提升

#### 13.7.2 场景2：GPS双频部分缺失

**输入信号**: L1C, L1W, L2P (3个)

**异常情况**: L2C丢失，只有L2P

**期望输出**:
```
主槽位0 (L1): L1C
主槽位1 (L2): L2P (从扩展提升!)
主槽位2 (L5): 空
```

**执行过程**:
1. `code2idx`: L1C→0, L1W→0, L2P→1
2. `sigindex`:
   - L1: L1C(pri=7) > L1W(pri=5) → L1C占主槽位, L1W去扩展
   - L2: 只有L2P → 直接占主槽位1
3. `promoteExtSig`: 无需操作（L2P已在主槽位）

**如果sigindex将L2P错误地放到扩展区**（理论上不会，但如果发生）:
- `promoteExtSig`会将其救回主槽位1 ✅

#### 13.7.3 场景3：GLONASS多信号冲突

**输入信号**: L1C/A, L1P, L2C/A, L2P (4个, 含FDMA特性)

**特殊考虑**: GLONASS需要FCN（频率通道号）

**期望输出**:
```
主槽位0 (L1): L1C/A (标准C码)
主槽位1 (L2): L2C/A (标准C码)
扩展区: L1P, L2P (精密码，优先级略低但可用)
```

### 13.8 性能分析

#### 13.8.1 时间复杂度

```java
// promoteExtSig 时间复杂度
for (int f = 0; f < NFREQ; f++) {           // 外层循环: NFREQ次 (≤6)
    for (int ex = NFREQ; ex < NFREQ+NEXOBS; ex++) {  // 内层循环: NEXOBS次 (≤26)
        // O(1) 操作
    }
}
// 总计: O(NFREQ × NEXOBS) = O(6 × 26) = O(156) ≈ 常数时间
```

**每次调用耗时**: < 1微秒（现代CPU）

#### 13.8.2 调用频率

```
每秒调用次数 ≈ 观测历元率 × 平均可见卫星数
典型值: 1 Hz × 14颗星 = 14次/秒
极端值: 50 Hz × 30颗星 = 1500次/秒

CPU占用: 1500 × 1μs = 1.5ms/s = 0.15% (可忽略)
```

#### 13.8.3 内存访问模式

```
优化点:
✅ 连续内存访问: Obsd的所有数组都是连续分配
✅ 缓存友好: 外层循环按f递增，内层按ex递增
✅ 无动态分配: 不涉及new/malloc

潜在瓶颈:
⚠️ code2idx内部有switch-case (分支预测)
⚠️ System.err.printf (I/O操作，仅调试时启用)
```

### 13.9 已知限制与改进方向

#### 13.9.1 当前限制

| 限制 | 描述 | 影响程度 |
|------|------|----------|
| **仅提升第一个匹配** | 找到第一个可用信号就停止 | 低 (通常够用) |
| **不区分信号质量** | 不比较SNR、LLI等指标 | 中 (可能不是最优选择) |
| **静态优先级** | sigindex使用固定优先级表 | 中 (无法适应环境变化) |
| **无回退机制** | 提升后不可撤销 | 低 (极少需要) |

#### 13.9.2 可能的改进方向

**方向1: 基于SNR的智能选择**
```java
// 当前: 选择第一个匹配
// 改进: 选择SNR最高的匹配
int bestEx = -1;
float bestSnr = -999;
for (int ex = NFREQ; ex < NFREQ+NEXOBS; ex++) {
    if (...匹配条件...) {
        if (obs.SNR[ex] > bestSnr) {
            bestSnr = obs.SNR[ex];
            bestEx = ex;
        }
    }
}
if (bestEx >= 0) {
    // 使用bestEx而非第一个匹配
}
```

**方向2: 多级提升策略**
```java
// 当前: 仅填补空缺
// 改进: 也尝试替换低质量信号
if (主槽位为空 || obs.SNR[f] < SNR_THRESHOLD) {
    // 寻找更优的扩展信号来替换
}
```

**方向3: 自适应优先级调整**
```java
// 当前: 固定优先级 (getcodepri)
// 改进: 根据历史表现动态调整
float reliability = getSignalReliability(sys, code);
pri = basePri * reliability;  // 可靠性加权
```

### 13.10 实现位置索引

| 文件 | 行号 | 方法名 | 类型 |
|------|------|--------|------|
| `src/main/java/org/rtklib/java/rtcm/Rtcm.java` | 1747-1773 | `promoteExtSig()` | 核心方法 |
| `src/main/java/org/rtklib/java/common/ObsCode.java` | 76-95 | `code2idx()` | 辅助方法 |
| `src/main/java/org/rtklib/java/common/ObsCode.java` | 354-388 | `sigindex()` | 辅助方法 |
| `src/main/java/org/rtklib/java/rtcm/Rtcm.java` | 1631-1745 | `saveMsmObs()` | 调用方 |
| `src/test/java/org/rtklib/java/RtcmParserTest.java` | 全文 | 测试用例 | 测试 |

### 13.11 参考资源

#### 13.11.1 RTKLIB C源码对照

```c
/* rtkcmn.c - C版本的等效逻辑 */
static void sigindex(int sys, const int *code, int n, int *idx, const prcopt_t *opt)
{
    /* 类似逻辑，但多了opt参数用于自定义优先级 */
}

/* 注意: C版没有显式的promoteExtSig函数 */
/* C版通过在decodeMsM中直接检查并赋值实现相同效果 */
```

#### 13.11.2 RTCM标准文档

- **RTCM 10403.3**: Multiple Signal Messages (MSM) 定义
- **RTKLIB Manual**: Section 12.3 MSM Decoding
- **BeiDou ICD**: B1I/B2I/B3I信号特征
- **GPS IS-GPS-200**: L1/L2/L5信号定义

---

## 11. 附录

### 11.1 快速参考卡：promoteExtSig决策树

```
输入: obs (Obsd对象), sys (卫星系统)

对于每个主频率槽位 f = 0, 1, ..., NFREQ-1:
│
├─ 槽位f是否已有数据?
│  └─ 是 → 跳过 (continue)
│  └─ 否 → 继续检查
│
└─ 遍历扩展槽位 ex = NFREQ, ..., NFREQ+NEXOBS-1:
   │
   ├─ 扩展槽位ex是否为空?
   │  └─ 是 → 检查下一个ex
   │  └─ 否 → 继续
   │
   ├─ code2idx(sys, obs.code[ex]) == f ? (频率匹配?)
   │  └─ 否 → 检查下一个ex
   │  └─ 是 → 继续
   │
   ├─ obs.L[ex]!=0 || obs.P[ex]!=0 ? (数据有效?)
   │  └─ 否 → 检查下一个ex
   │  └─ 是 → ✅ 执行提升!
   │
   │   1. 复制 L,P,D,LLI,SNR,code 从 ex → f
   │   2. 清零 ex 的所有字段
   │   3. 打印 [PROMOTE-SIG] 日志
   │   4. break (停止搜索)
   │
   └─ (如果循环结束未找到) → 槽位f保持空
```

### 11.2 常见信号码速查表 (BDS)

| 信号名称 | Code值 | Freq Index | 优先级 | 说明 |
|----------|--------|------------|--------|------|
| B1I | 40 | 0 | 7 | B1频段开放服务 |
| B1C | 44 | 0 | 6 | B1新民用信号 |
| B1P | 2 | 0 | 6 | B1授权信号 |
| B2I | 27 | 2 | 4 | B2开放服务 |
| B2a (D) | 61 | 2 | 6 | B2新民用信号 |
| B2b | 58 | 2 | 4 | B2授权信号 |
| B3I | 42 | 1 | 7 | B3开放服务 |
| B3Q | 59 | 1 | 4 | B3授权信号 |

> 调试日志格式、排查清单、测试验证详见 [调试指南](archive/RTKLIB_JAVA_DEBUG_GUIDE.md)

---

## 12. 定位结果输出体系（SolData）

> Java版定位结果的面向对象封装，替代C版直接写 `.pos` 文件的方式。
> 内部 `Sol` 结构保持不变（始终ECEF），仅在输出时按配置转换为 `SolData`。

### 12.1 设计背景

C版RTKLIB在 `outsol()` 中直接格式化输出到 `.pos` 文件，坐标转换（ECEF→LLH/ENU）和协方差旋转在输出时完成。
Java版作为库使用，不应直接写文件，而是提供结构化的结果对象供调用方使用。

**核心原则**：
- 内部 `Sol` 始终ECEF，不做任何修改
- 输出时通过 `SolData` 封装，按 `PrcOpt.posMask` 配置转换坐标
- 支持回调（`PosHandler.onResult(SolData)`）实时获取结果

### 12.2 类结构总览

```
SolData（一个历元的定位结果）
├── time: GTime              // GPS时间
├── timeUtc: LocalDateTime   // UTC时间
├── timeStr: String          // 时间字符串（.pos格式）
├── status: SolutionStatus   // 解状态枚举
├── numSat: int              // 有效卫星数
├── positions: List<Position>  // 位置列表（可含ECEF/LLH/ENU多种格式）
├── velocities: List<Velocity> // 速度列表（可含ECEF/ENU）
├── accuracies: List<Accuracy> // 精度列表（可含ECEF/ENU）
├── age: double              // 差分龄期（s）
├── ratio: double            // AR ratio
├── getPosition(CoordType)   // 便捷访问：按类型直接取
├── getVelocity(CoordType)   // 便捷访问：按类型直接取
└── getAccuracy(CoordType)   // 便捷访问：按类型直接取

Position（位置数据）
├── type: CoordType          // 坐标系类型
├── v1: double               // x / lat(deg) / e
├── v2: double               // y / lon(deg) / n
└── v3: double               // z / height(m) / u

Velocity（速度数据）
├── type: CoordType          // 坐标系类型
├── v1: double               // vx / ve
├── v2: double               // vy / vn
└── v3: double               // vz / vu

Accuracy（精度数据）
├── type: CoordType          // 坐标系类型
├── s1: double               // σx / σe
├── s2: double               // σy / σn
├── s3: double               // σz / σu
├── c12: double              // σxy / σen
├── c23: double              // σyz / σeu
└── c31: double              // σzx / σnu

CoordType（坐标系枚举）
├── ECEF                     // 地心地固坐标系
├── LLH                      // 大地坐标系（经纬度高）
└── ENU                       // 站心坐标系（东北天基线）

SolutionStatus（解状态枚举）
├── NONE                     // 无解
├── SINGLE                   // 单点定位
├── DGPS                     // DGPS
├── FLOAT                    // 浮点解
├── FIX                      // 固定解
├── PPP                      // PPP浮点解
├── PPP_FIX                  // PPP固定解
└── SBAS                     // SBAS
```

### 12.3 CoordType 枚举

| 枚举值 | 含义 | Position 分量 | Velocity 分量 | Accuracy 分量 |
|--------|------|---------------|---------------|---------------|
| `ECEF` | 地心地固 | v1=x, v2=y, v3=z (m) | v1=vx, v2=vy, v3=vz (m/s) | s1=σx, s2=σy, s3=σz (m) |
| `LLH` | 经纬度高 | v1=lat, v2=lon, v3=h (deg,deg,m) | — | — |
| `ENU` | 东北天基线 | v1=e, v2=n, v3=u (m) | v1=ve, v2=vn, v3=vu (m/s) | s1=σe, s2=σn, s3=σu (m) |

**注意**：
- 速度不存在LLH表示（经纬度对时间的导数无意义），LLH和ENU方向的速度统一用 `CoordType.ENU`
- 精度同理，LLH和ENU方向的精度统一用 `CoordType.ENU`

### 12.4 输出格式配置（posMask）

通过 `PrcOpt.posMask` 位掩码控制输出哪些坐标格式：

| 常量 | 值 | 说明 |
|------|-----|------|
| `PrcOpt.POS_ECEF` | 1 | 输出ECEF格式 |
| `PrcOpt.POS_LLH` | 2 | 输出LLH格式 |
| `PrcOpt.POS_ENU` | 4 | 输出ENU格式（需基站位置） |

**配置示例**：

```java
PrcOpt opt = new PrcOpt();

// 默认：ECEF + LLH
opt.posMask = PrcOpt.POS_ECEF | PrcOpt.POS_LLH;

// 全部输出：ECEF + LLH + ENU
opt.posMask = PrcOpt.POS_ECEF | PrcOpt.POS_LLH | PrcOpt.POS_ENU;

// 仅LLH
opt.posMask = PrcOpt.POS_LLH;
```

**posMask 对输出列表的影响**：

| posMask | positions | velocities | accuracies |
|---------|-----------|------------|------------|
| `ECEF` | [ECEF] | [ECEF] | [ECEF] |
| `LLH` | [LLH] | [ENU] | [ENU] |
| `ECEF\|LLH` | [ECEF, LLH] | [ECEF, ENU] | [ECEF, ENU] |
| `ECEF\|LLH\|ENU` | [ECEF, LLH, ENU] | [ECEF, ENU] | [ECEF, ENU] |

### 12.5 内部转换流程

```
Sol（内部，始终ECEF）                    SolData（输出封装）
┌─────────────────────┐                ┌─────────────────────────────────┐
│ rr[0..2]: ECEF位置   │ ──posMask──→  │ positions:                      │
│ rr[3..5]: ECEF速度   │                │   [ECEF] if POS_ECEF           │
│ qr[0..8]: ECEF协方差 │                │   [LLH]  if POS_LLH            │
│ stat: 解状态(int)    │                │   [ENU]  if POS_ENU            │
│ ns: 卫星数           │                │ velocities:                     │
│ time: GTime          │                │   [ECEF] if POS_ECEF           │
│ age, ratio           │                │   [ENU]  if POS_LLH|POS_ENU    │
└─────────────────────┘                │ accuracies:                     │
                                        │   [ECEF] if POS_ECEF           │
  转换方法：                            │   [ENU]  if POS_LLH            │
  ECEF→LLH: CoordTransform.ecef2pos()  │   [ENU]  if POS_ENU            │
  ECEF→ENU: CoordTransform.ecef2enu()  │ status: SolutionStatus(枚举)   │
  协方差旋转: CoordTransform.covenu()   │ numSat: int                    │
                                        │ age, ratio                     │
                                        └─────────────────────────────────┘
```

**构造时机**：

1. **实时回调**：每个历元定位成功后，立即构造 `SolData` 并通过 `PosHandler.onResult(SolData)` 回调
2. **批量结果**：各Processor的 `buildResult()` 方法中，`List<Sol>` → `List<SolData>`

```java
// RtkProcessor.java 实时回调（每个历元）
if (handler != null) {
    handler.onSolution(new Sol(rtk.sol), copySsatArray(rtk.ssat));
    double[] rb = (opt.rb[0] != 0 || opt.rb[1] != 0 || opt.rb[2] != 0) ? opt.rb : null;
    handler.onResult(new SolData(solCopy, opt.posMask, rb));
}

// RtkProcessor.java 批量结果（处理完成后）
private RtkResult buildResult() {
    double[] rb = (opt.rb[0] != 0 || opt.rb[1] != 0 || opt.rb[2] != 0) ? opt.rb : null;
    List<SolData> solDataList = solutions.stream()
            .map(sol -> new SolData(sol, opt.posMask, rb))
            .toList();
    return new RtkResult(totalEpochs, successCount, failCount, solDataList);
}
```

**回调调用顺序**：`onSolution(Sol, Ssat[])` → `onResult(SolData)`，每个历元依次调用。

### 12.6 使用示例

#### 12.6.1 基本使用

```java
// 配置输出格式
opt.posMask = PrcOpt.POS_ECEF | PrcOpt.POS_LLH | PrcOpt.POS_ENU;

// 运行RTK
RtkProcessor.RtkResult result = RtkProcessor.process(opt, nav, roverObs, baseObs);

// 遍历结果
for (SolData sd : result.solutions) {
    // 直接按类型获取，无需遍历过滤
    Position ecef = sd.getPosition(CoordType.ECEF);
    Position llh  = sd.getPosition(CoordType.LLH);
    Position enu  = sd.getPosition(CoordType.ENU);
    Accuracy acc  = sd.getAccuracy(CoordType.ENU);
    Velocity vel  = sd.getVelocity(CoordType.ECEF);

    System.out.printf("%s %s lat=%.9f lon=%.9f h=%.4f ns=%d%n",
            sd.timeStr, sd.status,
            llh.v1, llh.v2, llh.v3, sd.numSat);
}
```

#### 12.6.2 回调方式

每个历元定位成功后，`onResult(SolData)` 会自动被调用（在 `onSolution` 之后），
应用层只需实现 `onResult` 即可实时接收结构化定位结果：

```java
PosHandler handler = new PosHandler() {
    @Override
    public void onSolution(Sol sol, Ssat[] ssat) {
        // 内部回调，一般不需要实现
    }
    @Override
    public void onResult(SolData solData) {
        // 输出回调，每个历元自动调用
        Position llh = solData.getPosition(CoordType.LLH);
        if (llh != null && solData.status == SolutionStatus.FIX) {
            System.out.printf("FIX: %.9f %.9f %.4f%n", llh.v1, llh.v2, llh.v3);
        }
    }
};
```

#### 12.6.3 .pos 文件格式输出

```java
// 与RTKLIB C版 .pos文件格式兼容
static String formatSolDataLine(SolData solData) {
    Position llh = solData.getPosition(CoordType.LLH);
    Accuracy acc = solData.getAccuracy(CoordType.ENU);
    if (llh == null || acc == null) return "";

    return String.format("%s %s %14.9f %14.9f %10.4f %8.4f %8.4f %8.4f %8.4f %8.4f %8.4f %d %d",
            solData.timeStr, solData.status,
            llh.v1, llh.v2, llh.v3,
            acc.s1, acc.s2, acc.s3, acc.c12, acc.c23, acc.c31,
            solData.numSat, 0);
}
```

### 12.7 SolutionStatus 枚举映射

| 枚举值 | C版常量 | 值 | 说明 |
|--------|---------|-----|------|
| `NONE` | SOLQ_NONE | 0 | 无解 |
| `SINGLE` | SOLQ_SINGLE | 1 | 单点定位 |
| `DGPS` | SOLQ_DGPS | 2 | DGPS差分 |
| `FLOAT` | SOLQ_FLOAT | 3 | RTK浮点解 |
| `FIX` | SOLQ_FIX | 4 | RTK固定解 |
| `PPP` | SOLQ_PPP | 5 | PPP浮点解 |
| `PPP_FIX` | SOLQ_PPP_FIX | 6 | PPP固定解 |
| `SBAS` | — | — | SBAS |

### 12.8 文件索引

| 文件 | 说明 |
|------|------|
| `data/SolData.java` | 输出数据类，Sol→SolData转换逻辑 |
| `data/Position.java` | 位置封装（CoordType + 3分量） |
| `data/Velocity.java` | 速度封装（CoordType + 3分量） |
| `data/Accuracy.java` | 精度封装（CoordType + 6分量：3标准差+3互协方差） |
| `data/CoordType.java` | 坐标系枚举（ECEF/LLH/ENU） |
| `data/SolutionStatus.java` | 解状态枚举 |
| `data/PrcOpt.java` | 新增 posMask 配置字段 |
| `pntpos/PosHandler.java` | 新增 onResult(SolData) 回调方法 |
| `rtkpos/RtkProcessor.java` | RtkResult.solutions: List\<SolData\>，buildResult() |
| `pntpos/SppProcessor.java` | SppResult.solutions: List\<SolData\>，buildResult() |
| `ppp/PppProcessor.java` | PppResult.solutions: List\<SolData\>，buildResult() |

---

## 12. 与 C 版 RTKLIB 的对齐状态

### 12.1 已对齐的核心流程

| 模块 | C版函数 | Java版方法 | 状态 |
|------|---------|-----------|------|
| SPP | `pntpos()` | `PntPos.pntpos()` | ✅ 完整对齐 |
| SPP核心 | `estpos()` | `SppCore.estpos()` | ✅ |
| RAIM FDE | `raim_fde()` | `PntPos.raimFde()` | ✅ |
| 速度估计 | `estvel()` | `PntPos.estvel()` | ✅ |
| 多普勒残差 | `resdop()` | `PntPos.resdop()` | ✅ |
| RTK入口 | `rtkpos()` | `RtkCore.rtkpos()` | ✅ 完整对齐 |
| 相对定位 | `relpos()` | `RtkCore.relpos()` | ✅ |
| 卫星位置 | `satposs()` | `EphModel.satposs()` | ✅ |
| 坐标变换 | `ecef2pos()`/`xyz2enu()` | `CoordTransform` | ✅ |
| LAMBDA | `lambda()` | `Lambda` | ✅ |
| 周跳检测 | `detslp_*()` | `RtkCore.detslpLl/Gf/Code/Dop()` | ✅ |
| RINEX读写 | `readrnx()`/`outrnx()` | `RinexParser`/`RinexObsWriter` | ✅ |
| RTCM解码 | `decode_*()` | `Rtcm` | ✅ |
| PPP入口 | `pppos()` | `PppCore.pppos()` | ✅ 基本对齐 |
| PPP状态更新 | `udstate_ppp()` | `PppCore.udstate()` | ✅ |
| PPP观测修正 | `corrMeas()` | `PppCore.corrMeas()` | ✅ |
| PPP RINEX处理 | - | `PppProcessor`/`RinexPppProcessor` | ✅ |
| 追踪日志 | `trace*()` | `RtkTrace`/`PppTrace` | ✅ |

### 12.2 已对齐的 rtkpos() 流程细节

| 逻辑 | 说明 | 状态 |
|------|------|------|
| 流动站SPP | `P[0]==0||P[0]>STD_PREC_VAR_THRESH` → `pntpos()` | ✅ |
| SPP失败dynamics容错 | dynamics模式下不直接返回 | ✅ |
| outsingle抑制 | 非SINGLE模式抑制单点解输出 | ✅ |
| 基站坐标设置 | `refposmode!=REFPOS_RTCM` | ✅ |
| MOVEB基站SPP | 基站观测数据独立SPP | ✅ |
| MOVEB age检查 | 时间同步验证 | ✅ |
| ssat后处理 | vs/azel/resp/resc更新 | ✅ |
| eventime传递 | `sol.eventime=obs[0].eventime` | ✅ |

### 12.3 未移植项

| 功能 | 优先级 | 原因 |
|------|--------|------|
| Static Start长延迟恢复 | 低 | 边界场景，`tt>300`时重置状态 |
| 多系统PPP验证 | 中 | GPS+BDS联合PPP，需多系统精密星历 |
| SSR相位偏差改正 | 中 | `corr_phase_bias_ssr()` 未实现，SSR改正仅含轨道/钟差 |
| CombinedFilter速度smoother | 低 | C版 `combres()` 对速度做RTS平滑，Java版仅位置 |
| POSOPT_FILE | 低 | 位置文件读取，PostPosProcessor中fallback到RINEX header |
| udtrop()冻结 | 低 | `atmFrozenNsThresh` 仅在 `udion()` 中实现 |

---

## 13. 方法命名规则

Java版方法名遵循以下规则，在保持Java驼峰命名的同时保留C版函数名核心：

| C版 | Java版 | 规则 |
|-----|--------|------|
| `pntpos()` | `pntpos()` | 无下划线，直接保留 |
| `estpos()` | `estpos()` | 同上 |
| `raim_fde()` | `raimFde()` | 下划线后首字母大写 |
| `detslp_ll()` | `detslpLl()` | 下划线后每段首字母大写 |
| `satposs()` | `satposs()` | 直接保留 |
| `ecef2pos()` | `ecef2pos()` | 直接保留 |

---

## 14. 测试验证状态

当前测试以**北斗（BDS）单系统**和**多系统（GPS+BDS）**短基线数据为主。SPP 已通过 BDS 数据与 C 版 RTKLIB 亚毫米级对比验证；RTK 已通过多系统短基线数据验证，Fix 解比例达 88.7%。PPP 已实现基本功能，IFLC 模式下 BDS 浮点解可正常输出。

多系统（GPS+GLONASS 等）联合定位尚未充分验证。如有**多系统真实观测数据**条件，欢迎测试验证并反馈结果。

### 14.1 已验证场景

| 场景 | 数据源 | 状态 |
|------|--------|------|
| SPP（BDS-only） | RTCM MSM4 | ✅ 240历元与C版亚毫米级匹配 |
| RTK（GPS+BDS 短基线） | RTCM MSM4 | ✅ Fix解比例88.7%，ratio 42~384 |
| RTK（BDS-only 短基线） | RTCM MSM4 | ✅ 浮点解收敛稳定（数据质量限制，C版同样无法Fix） |
| PPP（BDS-only IFLC） | RINEX + SP3/CLK | ✅ 240历元浮点解输出 |
| SPP（GPS+BDS） | - | ⏳ 待验证 |
| PPP（GPS+BDS） | - | ⏳ 待验证 |

---

## 15. PPP 关键改正项

PPP 定位精度依赖多种外部改正数据，以下为 Java 版支持的改正项及其读取器：

| 改正项 | 读取器 | 文件格式 | 影响量级 |
|--------|--------|----------|----------|
| 天线相位中心偏差 (PCV) | `PcvReader` | ANTEX (.atx), NGS (.pcv) | 10-15 cm |
| 差分码偏差 (DCB) | `DcbReader` | BIA, BSX, DCB | 几十 cm（伪距） |
| 海潮负荷 (OTL) | `OtlReader` | BLQ | 1-5 cm（沿海站） |
| 固体潮 | `Tides.tidedisp()` | 内置模型 | ~30 cm |
| 极潮 | `Tides.tidePole()` | ERP文件 | ~1-2 cm |

---

*文档版本：v2.4*
*最后更新：2026-08-20*
*变更：文档勘误（NEXOBS=2非26），补充待完善项（SSR相位偏差、速度smoother、udtrop冻结），archive过时标记*
*维护者：RTKLIB Java移植团队*