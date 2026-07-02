# RTKLIB 矩阵维度与存储方式参考文档

> **用途**：系统化记录C版与Java版中所有关键矩阵的维度、存储约定及运算差异，避免反复混淆。
> **适用场景**：排查Kalman滤波发散、AR ratio低、Fix解比例0%等问题时快速查阅。

---

## 1. 核心结论速查表

| 矩阵 | C版维度 | Java版维度 | 物理含义 | 存储一致性 |
|------|---------|------------|----------|-----------|
| **H (ddres)** | **n×m** (状态×观测) | **m×n** (观测×状态) | 设计矩阵 | ✅ `H[state+obs*n]` = `H[obs*n+state]` |
| **P** | n×n | n×n | 协方差矩阵 | ⚠️ 列优先 vs 行优先 |
| **R** | m×m | m×m | 观测噪声协方差 | ⚠️ 列优先 vs 行优先 |
| **x** | n×1 | n×1 | 状态向量 | ✅ 一维数组 |
| **v** | m×1 | m×1 | 残差向量 | ✅ 一维数组 |

**关键发现**：C版和Java版的H矩阵**物理存储完全相同**，但解释方式不同：
- C版：列优先，解释为 **n×m**（状态×观测）
- Java版：行优先，解释为 **m×n**（观测×状态）

---

## 2. ddres 函数中的 H 矩阵构建

### 2.1 C版实现（rtkpos.c）

```c
// 函数签名: static int ddres(..., double *H, ...)
// 输入: rtk->nx = n (状态维数)
//       nv = 当前已用观测数
// 输出: H[n * m_total] (n行m列, 列优先)

Hi = H + nv * rtk->nx;  // Hi 指向第nv个观测对应的H行起始位置

for (k = 0; k < rtk->nx; k++) {
    Hi[k] = 0.0;  // 清零整行
}

// 设置位置偏导数 (前3个状态)
for (k = 0; k < 3; k++) {
    Hi[k] = -e[k + iu[i]*3] + e[k + iu[j]*3];  // Hi[k] = H[nv*nx + k]
}

// 设置模糊度偏导数
int ii = IB(sat[i], frq, opt);
int jj = IB(sat[j], frq, opt);
Hi[ii] =  CLIGHT / freqi;   // 参考星模糊度偏导数
Hi[jj] = -CLIGHT / freqj;   // 非参考星模糊度偏导数
```

**C版H矩阵结构**：
```
H (n × m, 列优先):
┌─────────────────────────────────────────────┐
│         │ obs_0  obs_1  obs_2  ...  obs_m-1 │
├─────────┼────────────────────────────────────┤
│ state_0 │ H[0+0*n] H[0+1*n] ... H[0+(m-1)*n]│
│ state_1 │ H[1+0*n] H[1+1*n] ... H[1+(m-1)*n]│
│ ...     │ ...                                │
│ state_n-1│ H[n-1+0*n]...H[n-1+(m-1)*n]      │
└─────────┴────────────────────────────────────┘

访问方式: H[state_index + obs_index * n]
```

### 2.2 Java版实现（RtkCore.java）

```java
// 函数签名: private static int ddres(..., double[] H, ...)
// 输入: rtk.nx = n (状态维数)
//       nv = 当前已用观测数
// 输出: H[m_total * n] (m行n列, 行优先)

if (H != null) {
    for (k = 0; k < rtk.nx; k++) {
        H[nv * rtk.nx + k] = 0.0;  // 清零整行
    }
}

// 设置位置偏导数 (前3个状态)
for (k = 0; k < 3; k++) {
    H[nv * rtk.nx + k] = -e[k + idx_i*3] + e[k + idx_j*3];
}

// 设置模糊度偏导数
int ii = IB(sat[refIdx], frq, nf, opt);
int jj = IB(sat[j], frq, nf, opt);
H[nv * rtk.nx + ii] =  lami;
H[nv * rtk.nx + jj] = -lamj;
```

**Java版H矩阵结构**：
```
H (m × n, 行优先):
┌───────────────────────────────────────────────────┐
│          │ state_0  state_1  ...  state_n-1        │
├──────────┼─────────────────────────────────────────┤
│ obs_0    │ H[0*n+0] H[0*n+1] ... H[0*n+n-1]       │
│ obs_1    │ H[1*n+0] H[1*n+1] ... H[1*n+n-1]       │
│ ...      │ ...                                      │
│ obs_m-1  │ H[(m-1)*n+0] ... H[(m-1)*n+n-1]        │
└──────────┴─────────────────────────────────────────┘

访问方式: H[obs_index * n + state_index]
```

### 2.3 存储等价性证明

**命题**: `H_C[state + obs * n] == H_Java[obs * n + state]`

**证明**:
- 左边：C版列优先 n×m 矩阵的第state行第obs列元素
- 右边：Java版行优先 m×n 矩阵的第obs行第state列元素
- 由于两者都是"第state个状态对第obs个观测的偏导数"，所以值相等
- 数学上：`state + obs * n == obs * n + state` （加法交换律）

**结论**: ✅ **ddres输出的H数组在内存中完全相同**

---

## 3. filter 函数中的矩阵压缩

### 3.1 C版 filter() 压缩逻辑（rtkcmn.c）

```c
extern int filter(double *x, double *P, const double *H, const double *v,
                  const double *R, int n, int m)
{
    // 输入:
    //   x[n]:      状态向量 (n×1)
    //   P[n*n]:    协方差矩阵 (n×n, 列优先)
    //   H[n*m]:    设计矩阵 (n×m, 列优先) ← 注意！这里是n×m
    //   v[m]:      残差向量 (m×1)
    //   R[m*m]:    观测噪声 (m×m, 列优先)

    // 步骤1: 找活跃状态
    ix = imat(n, 1);
    for (i = k = 0; i < n; i++) {
        if (x[i] != 0.0 && P[i + i*n] > 0.0) {  // P对角线元素
            ix[k++] = i;
        }
    }

    // 步骤2: 分配压缩数组
    x_ = mat(k, 1);   // 压缩后的状态 (k×1)
    P_ = mat(k, k);   // 压缩后的协方差 (k×k, 列优先)
    H_ = mat(k, m);   // 压缩后的设计矩阵 (k×m, 列优先)

    // 步骤3: 填充压缩数组
    for (i = 0; i < k; i++) {
        x_[i] = x[ix[i]];                          // 状态

        for (j = 0; j < k; j++) {
            P_[i + j*k] = P[ix[i] + ix[j]*n];      // 协方差 (列优先)
        }

        for (j = 0; j < m; j++) {
            H_[i + j*k] = H[ix[i] + j*n];          // 设计矩阵 (列优先)
        }
    }
    // 此时:
    //   H_: k×m (状态×观测), 列优先, H_[i+j*k]=第i行第j列
    //   P_: k×k, 列优先, P_[i+j*k]=第i行第j列

    // 步骤4: 调用 filter_
    info = filter_(x_, P_, H_, v, R, k, m, xp_, Pp_);

    // 步骤5: 写回原数组
    for (i = 0; i < k; i++) {
        x[ix[i]] = xp_[i];
        for (j = 0; j < k; j++) {
            P[ix[i] + ix[j]*n] = Pp_[i + j*k];  // 列优先写回
        }
    }
}
```

### 3.2 Java版 KalmanFilter.update() 压缩逻辑

```java
public static int update(double[] x, double[] P, double[] H, double[] v,
                         double[] R, int n, int m) {
    // 输入:
    //   x[n]:      状态向量 (n×1)
    //   P[n*n]:    协方差矩阵 (n×n, 行优先)
    //   H[m*n]:    设计矩阵 (m×n, 行优先) ← 注意！这里是m×n
    //   v[m]:      残差向量 (m×1)
    //   R[m*m]:    观测噪声 (m×m, 行优先)

    // 步骤1: 找活跃状态
    int[] ix = new int[n];
    int k = 0;
    for (int i = 0; i < n; i++) {
        if (x[i] != 0.0 && P[i * n + i] > 0.0) {
            ix[k++] = i;
        }
    }

    // 步骤2: 分配压缩数组
    double[] xc = new double[k];      // 压缩后的状态 (k×1)
    double[] Pc = new double[k * k];  // 压缩后的协方差 (k×k, 行优先)
    double[] Hc = new double[m * k];  // 压缩后的设计矩阵 (m×k, 行优先)

    // 步骤3: 填充压缩数组
    for (int i = 0; i < k; i++) {
        xc[i] = x[ix[i]];

        for (int j = 0; j < k; j++) {
            Pc[i * k + j] = P[ix[i] * n + ix[j]];  // 行优先
        }

        for (int j = 0; j < m; j++) {
            Hc[j * k + i] = H[j * n + ix[i]];      // 行优先
        }
    }
    // 此时:
    //   Hc: m×k (观测×状态), 行优先, Hc[j*k+i]=第j行第i列
    //   Pc: k×k, 行优先, Pc[i*k+j]=第i行第j列

    // 步骤4: EJML运算
    SimpleMatrix Xc = MatrixUtil.createMatrix(xc, k, 1);
    SimpleMatrix PcMat = MatrixUtil.createMatrix(Pc, k, k);
    SimpleMatrix HcMat = MatrixUtil.createMatrix(Hc, m, k);  // m×k
    // ... Kalman滤波运算 ...

    // 步骤5: 写回原数组
    for (int i = 0; i < k; i++) {
        x[ix[i]] = XcNew.get(i, 0);
        for (int j = 0; j < k; j++) {
            P[ix[i] * n + ix[j]] = P_new.get(i, j);  // 行优先写回
        }
    }
}
```

### 3.3 压缩后H矩阵的转置关系

**C版压缩后的H_**：
- 维度：**k × m**（状态×观测）
- 存储：列优先 `H_[i + j*k]` = 第i行第j列
- 含义：`H_[i][j]` = 第i个活跃状态对第j个观测的偏导数

**Java版压缩后的Hc**：
- 维度：**m × k**（观测×状态）
- 存储：行优先 `Hc[j*k+i]` = 第j行第i列
- 含义：`Hc[j][i]` = 第j个观测对第i个活跃状态的偏导数

**数学关系**：`Hc = H_^T`（互为转置）

**验证**：
```
C版:  H_[i + j*k] = H[ix[i] + j*n]  (从原始H提取)
Java: Hc[j*k+i]   = H[j*n + ix[i]]  (从原始H提取)

由于 H[ix[i]+j*n] = H[j*n+ix[i]] (加法交换律),
所以 H_[i][j] = Hc[j][i],
即 Hc = H_^T。
```

---

## 4. filter_ 核心算法对比

### 4.1 C版实现（使用LAPACK）

```c
static int filter_(const double *x, const double *P, const double *H,
                   const double *v, const double *R, int n, int m,
                   double *xp, double *Pp)
{
    // 输入维度:
    //   x: n×1 (状态向量)
    //   P: n×n (协方差, 列优先)
    //   H: n×m (设计矩阵, 列优先) ← 注意是n×m!
    //   v: m×1 (残差)
    //   R: m×m (观测噪声, 列优先)

    double *F = mat(n, m);   // F = P * H^T (中间变量)
    double *Q = mat(m, m);   // Q = S = H * P * H^T + R (新息协方差)
    double *K = mat(n, m);   // K = P * H^T * S^-1 (Kalman增益)
    double *I = eye(n);      // 单位矩阵

    // Step 1: Q = R (初始化)
    matcpy(Q, R, m, m);

    // Step 2: F = P * H  (n×n * n×m = n×m)
    matmul("NN", n, m, n, P, H, F);

    // Step 3: Q += H^T * F = H^T * P * H + R  (m×n * n×m + m×m = m×m)
    // "TN": 第一个矩阵T(转置), 第二个N(正常)
    matmulp("TN", m, m, n, H, F, Q);

    // Step 4: Q^-1 (LU分解求逆, 使用LAPACK dgetrf_ + dgetri_)
    if (!(info = matinv(Q, m))) {

        // Step 5: K = F * Q^-1 = P * H * S^-1  (n×m * m×m = n×m)
        matmul("NN", n, m, m, F, Q, K);

        // Step 6: xp = x + K * v  (n×1 + n×m * m×1 = n×1)
        matmul("NN", n, 1, m, K, v, xp);
        matadd(x, xp, n, 1, xp);

        // Step 7: I -= K * H^T  (n×n - n×m * m×n = n×n)
        // "NT": 第一个N(正常), 第二个T(转置)
        matmulm("NT", n, n, m, K, H, I);

        // Step 8: Pp = I * P = (I - K*H^T) * P  (n×n * n×n = n×n)
        matmul("NN", n, n, n, I, P, Pp);
    }

    free(F); free(Q); free(K); free(I);
    return info;
}
```

**C版矩阵运算维度追踪**：
```
P (n×n) * H (n×m) → F (n×m)
H^T (m×n) * F (n×m) → Q (m×m)
Q (m×m) → Q^-1 (m×m)
F (n×m) * Q^-1 (m×m) → K (n×m)
K (n×m) * v (m×1) → dx (n×1)
K (n×m) * H^T (m×n) → KH (n×n)
I (n×n) - KH (n×n) → I_KH (n×n)
I_KH (n×n) * P (n×n) → P_new (n×n)
```

### 4.2 Java版实现（使用EJML）

```java
public static int update(...) {
    // 压缩后:
    //   Xc: k×1 (EJML SimpleMatrix)
    //   PcMat: k×k (EJML SimpleMatrix)
    //   HcMat: m×k (EJML SimpleMatrix) ← 注意是m×k!
    //   V: m×1 (EJML SimpleMatrix)
    //   RMat: m×m (EJML SimpleMatrix)

    // Step 1: Hc^T (k×m)
    SimpleMatrix Hct = MatrixUtil.transpose(HcMat);

    // Step 2: PHt = Pc * Hc^T  (k×k * k×m = k×m)
    SimpleMatrix PHt = MatrixUtil.multiply(PcMat, Hct);

    // Step 3: HPHt = Hc * PHt  (m×k * k×m = m×m)
    SimpleMatrix HPHt = MatrixUtil.multiply(HcMat, PHt);

    // Step 4: S = HPHt + R  (m×m)
    SimpleMatrix S = MatrixUtil.add(HPHt, RMat);

    // Step 5: S^-1 (EJML invert, 使用LU分解)
    SimpleMatrix Sinv = MatrixUtil.invert(S);

    // Step 6: K = PHt * S^-1  (k×m * m×m = k×m)
    SimpleMatrix K = MatrixUtil.multiply(PHt, Sinv);

    // Step 7: XcNew = Xc + K * V  (k×1 + k×m * m×1 = k×1)
    SimpleMatrix KV = MatrixUtil.multiply(K, V);
    SimpleMatrix XcNew = MatrixUtil.add(Xc, KV);

    // Step 8: I - K * Hc  (k×k - k×m * m×k = k×k)
    SimpleMatrix Ic = MatrixUtil.identity(k);
    SimpleMatrix KHc = MatrixUtil.multiply(K, HcMat);
    SimpleMatrix I_KH = MatrixUtil.subtract(Ic, KHc);

    // Step 9: P_new = (I-KH) * Pc  (k×k * k×k = k×k)
    SimpleMatrix P_new = MatrixUtil.multiply(I_KH, PcMat);
}
```

**Java版矩阵运算维度追踪**：
```
Pc (k×k) * Hc^T (k×m) → PHt (k×m)
Hc (m×k) * PHt (k×m) → HPHt (m×m)
HPHt (m×m) + R (m×m) → S (m×m)
S (m×m) → S^-1 (m×m)
PHt (k×m) * S^-1 (m×m) → K (k×m)
K (k×m) * V (m×1) → KV (k×1)
K (k×m) * Hc (m×k) → KH (k×k)
I (k×k) - KH (k×k) → I_KH (k×k)
I_KH (k×k) * Pc (k×k) → P_new (k×k)
```

### 4.3 数学等价性验证

| 运算步骤 | C版表达式 | Java版表达式 | 维度匹配 |
|----------|-----------|--------------|----------|
| 新息协方差 | `H^T * P * H + R` | `Hc * Pc * Hc^T + R` | ⚠️ 见下方说明 |
| Kalman增益 | `P * H * S^-1` | `Pc * Hc^T * S^-1` | ✅ |
| 状态更新 | `x + K * v` | `Xc + K * V` | ✅ |
| 协方差更新 | `(I - K*H^T) * P` | `(I - K*Hc) * Pc` | ⚠️ 见下方说明 |

**新息协方差等价性**：
```
C版:  S = H^T * P * H + R
      其中 H 是 n×m (状态×观测)

Java: S = Hc * Pc * Hc^T + R
      其中 Hc 是 m×k (观测×状态)

由于 Hc = H_^T (压缩后的转置关系),
且 Pc 是 P 的子集,

所以 Hc * Pc * Hc^T = (H_^T) * P_sub * (H_^T)^T
                  = H_^T * P_sub * H_
                  ≈ H^T * P * H (子集近似)
```

**协方差更新等价性**：
```
C版:  P_new = (I - K*H^T) * P
      其中 K 是 n×m, H^T 是 m×n

Java: P_new = (I - K*Hc) * Pc
      其中 K 是 k×m, Hc 是 m×k

由于 Hc = H_^T (压缩后的转置关系),
所以 K*Hc = K*H_^T,
即 (I-K*Hc) = (I-K*H_^T).

注意: C版用的是 H^T (原始H的转置),
      Java版用的是 Hc (压缩后的H, 已等于H_^T).
```

---

## 5. ddcov 函数：双差协方差矩阵构建

### 5.1 实现原理

双差协方差矩阵R不是对角矩阵！同一组内（同系统同频率）的双差观测之间存在相关性。

```java
private static void ddcov(int[] nb, int b, double[] Ri, double[] Rj,
                           int nv, double[] R) {
    // 输入:
    //   nb[b]: 第b组的观测数量
    //   b:     总组数
    //   Ri[]:  流动站单差方差 (长度nv)
    //   Rj[]:  基准站单差方差 (长度nv)
    //   nv:    总观测数
    //
    // 输出:
    //   R[nv*nv]: 双差协方差矩阵 (行优先)

    int i, j, k = 0;

    // 初始化为零矩阵
    for (i = 0; i < nv * nv; i++) R[i] = 0.0;

    // 按组填充
    for (int bi = 0; bi < b; k += nb[bi++]) {
        for (i = 0; i < nb[bi]; i++) {
            for (j = 0; j < nb[bi]; j++) {
                // 同一组内的协方差非零
                R[(k + i) * nv + (k + j)] = Ri[k + i]
                                          + (i == j ? Rj[k + i] : 0.0);
            }
        }
    }
}
```

### 5.2 R矩阵结构示例

假设有10个观测，分为2组（BDS L1=5个, BDS L2=5个）：

```
R (10×10, 行优先):
┌────────────────────────────────────────────────────────────┐
│           │ BDSL1_0  BDSL1_1  ...  BDSL1_4  BDSL2_0  ...  │
├───────────┼────────────────────────────────────────────────┤
│ BDSL1_0   │ Ri0+Rj0   Ri0      ...  0        0       ...  │
│ BDSL1_1   │ Ri0      Ri1+Rj1  ...  0        0       ...  │
│ ...       │ ...                                  (块对角)  │
│ BDSL1_4   │ 0        0       ...  Ri4+Rj4   0       ...  │
│ BDSL2_0   │ 0        0       ...  0        Ri5+Rj5  ...  │
│ ...       │ ...                                         │
│ BDSL2_4   │ 0        0       ...  0        0       ...  │
└───────────┴────────────────────────────────────────────────┘

特点:
- 块对角结构（每组一块）
- 组内非对角线元素 = Ri[i] (流动站方差)
- 对角线元素 = Ri[i] + Rj[i] (双边方差和)
- 不同组之间 = 0 (独立)
```

---

## 6. varerr 函数：观测噪声方差计算

### 6.1 公式详解

```java
private static double varerr(int sat, int sys, double el,
                             double snr_rover, double snr_base,
                             double bl, double dt, int f,
                             PrcOpt opt, Obsd obs) {
    // 输入参数:
    //   el:        高度角 (弧度)
    //   snr_rover: 流动站信噪比 (dB-Hz)
    //   snr_base:  基准站信噪比 (dB-Hz)
    //   bl:        基线长度 (米)
    //   dt:        时间间隔 (秒)
    //   f:         频率/码索引 (0..nf-1为相位, nf..2nf-1为伪距)

    int nf = (opt.ionoopt == IONOOPT_IFLC) ? 1 : opt.nf;
    int frq = f % nf;
    boolean code = f >= nf;  // true=伪距, false=载波相位

    double s_el = Math.sin(el);
    if (s_el <= 0.0) s_el = 0.001;  // 防止除零

    // Step 1: 基础误差因子 fact
    double fact;
    if (code) {
        fact = opt.eratio[frq];              // 伪距放大因子 (通常100~300)
    } else {
        fact = opt.eratio[frq] / opt.eratio[0];  // 相位相对因子 (通常1.0)
    }

    // Step 2: 系统类型修正
    switch (sys) {
        case SYS_GPS: fact *= EFACT_GPS; break;  // 通常1.0
        case SYS_GLO: fact *= EFACT_GLO; break;  // 通常1.5~2.0
        case SYS_GAL: fact *= EFACT_GAL; break;  // 通常1.0
        case SYS_CMP: fact *= EFACT_CMP; break;  // 通常1.0~2.0
        // ...
    }

    // Step 3: 各项误差计算
    double a = fact * opt.err[1];             // 基础项 (通常0.003m)
    double b = fact * opt.err[2];             // 高度角项 (通常0.003m)
    double c = opt.err[3] * bl / 1E4;        // 基线项 (bl/10000 * err[3])
    double d = CLIGHT * opt.sclkstab * dt;   // 钟漂项 (通常可忽略)

    // Step 4: 合成方差
    double var = 2.0 * (SQR(a) + SQR(b/s_el) + SQR(c)) + SQR(d);

    // Step 5: SNR修正 (可选)
    if (opt.err[6] > 0.0) {
        double e = fact * opt.err[6];
        var += SQR(e) * (
            Math.pow(10, 0.1 * Math.max(opt.err[5] - snr_rover, 0.0)) +
            Math.pow(10, 0.1 * Math.max(opt.err[5] - snr_base, 0.0))
        );
    }

    // Step 6: 接收机标准差修正 (可选)
    if (opt.err[7] > 0.0) {
        if (code) var += SQR(opt.err[7] * obs.Pstd[frq]);
        else var += SQR(opt.err[7] * obs.Lstd[frq] * 0.2);
    }

    // Step 7: 无电离层组合修正
    if (opt.ionoopt == IONOOPT_IFLC) {
        var *= SQR(3.0);  // 放大9倍
    }

    return var;  // 单位: 平方米 (m²)
}
```

### 6.2 典型输出值

| 观测类型 | 高度角 | 方差值 (m²) | 标准差 (m) |
|----------|--------|-------------|------------|
| 载波相位 L1 | 45° | ~0.0001 | ~0.01 |
| 载波相位 L1 | 15° | ~0.0003 | ~0.017 |
| 伪距 P1 | 45° | ~8.0 | ~2.83 |
| 伪距 P1 | 15° | ~12.0 | ~3.46 |
| 伪距 P2 | 45° | ~15.0 | ~3.87 |

**⚠️ 关键问题**: 载波相位和伪距的方差相差约 **80000倍**（0.0001 vs 8.0），导致S矩阵条件数极差。

---

## 7. 数值稳定性问题分析

### 7.1 当前问题现象

第二个历元的Kalman滤波出现数值不稳定：

```
S_diag=[0.000201 0.000241 0.000173 0.000206 0.000236 13.009 8.253 10.812 12.485 15.916]
S_cond=394143  ← 条件数39万!

K[state0 ix=0]: [0]=-39.882476 sum=-39.882476  ← 增益异常大
K[state1 ix=1]: [1]=-99.551992 sum=-99.551992

I_KH_diag=[32.43 -85.01 43.68 1.00 1.00 0.77 3.99 -7.34 -8.07 -9.97 34.62 1.00]
              ↑负值! 应该接近0~1
```

### 7.2 问题根因

**S矩阵条件数极差**导致矩阵求逆精度损失：

```
S = H * P * H^T + R

其中:
- 前5个观测是载波相位: S_diag ≈ 0.0002 (很小)
- 后5个观测是伪距:     S_diag ≈ 8~16 (很大)

条件数 = max(S_diag) / min(S_diag) ≈ 16 / 0.0002 = 80,000
实际条件数更高 (考虑非对角线元素): 394,143
```

**为什么C版不发散？**

C版使用 **LAPACK (dgetrf_ + dgetri_)** 进行LU分解和矩阵求逆：
- 使用部分选主元（Partial Pivoting）提高数值稳定性
- Fortran实现经过几十年优化，数值精度极高

Java版使用 **EJML (SimpleMatrix.invert)**：
- 也使用LU分解和选主元
- 但Java浮点运算精度可能略低于Fortran
- 在极端条件下可能出现精度损失

### 7.3 可能的解决方案（按推荐顺序）

#### 方案1: 使用Joseph形式更新P（推荐）

**原理**: Joseph形式在数学上更稳定，即使K有误差也能保证P正定。

```java
// 当前形式 (简单但不稳定):
SimpleMatrix P_new = MatrixUtil.multiply(I_KH, PcMat);  // (I-KH)*P

// Joseph形式 (更稳定):
SimpleMatrix KHK = MatrixUtil.multiply(KHc, PcMat);      // K*H*P
SimpleMatrix P_joseph = MatrixUtil.subtract(PcMat, KHK); // P - K*H*P
```

**优点**: 保证P正定性，数值稳定性好
**缺点**: 计算量略大（多一次矩阵乘法）

#### 方案2: 分开处理载波相位和伪距（中等推荐）

**原理**: 将载波相位和伪距分开进行两次Kalman更新，避免S矩阵条件数差。

```java
// 第一次更新: 仅载波相位 (m1个观测)
update_phase_only(x, P, H_phase, v_phase, R_phase, n, m1);

// 第二次更新: 仅伪距 (m2个观测)
update_code_only(x, P, H_code, v_code, R_code, n, m2);
```

**优点**: 彻底避免条件数问题
**缺点**: 需要修改ddres和filter接口

#### 方案3: 提高载波相位噪声下限（临时方案）

**原理**: 人为增大载波相位的R值，降低条件数。

```java
if (!code && var < 0.001) {
    var = 0.001;  // 强制最小方差
}
```

**优点**: 简单易实现
**缺点**: 降低载波相位的权重，可能影响收敛速度

#### 方案4: 使用高精度数值库（长期方案）

**原理**: 替换EJML为ND4J或Apache Commons Math。

**优点**: 更好的数值稳定性
**缺点**: 需要重构代码，引入新依赖

---

## 8. 快速诊断清单

当遇到以下问题时，按此表检查：

### 8.1 Kalman增益K异常大

| 检查项 | 正常值 | 异常值 | 排查方向 |
|--------|--------|--------|----------|
| S_diag范围 | 0.001~100 | 出现<0.0001或>1000 | 检查R矩阵是否合理 |
| S_cond | <1000 | >10000 | 条件数差，需改用Joseph形式 |
| Pc_diag范围 | 0.001~1000 | >10000 | P未收敛，检查时间更新 |
| Hc非零元素 | -1~1 | >10或<-10 | H矩阵构建错误 |

### 8.2 I_KH_diag出现负值

| 检查项 | 正常值 | 异常值 | 排查方向 |
|--------|--------|--------|----------|
| I_KH_diag范围 | 0~1 | <-1或>2 | K过大或Hc错误 |
| K的行和 | 0~1 | >10或<-10 | 增益分配不合理 |
| P_new_diag | >0 | <0 | 协方差矩阵不正定 |

### 8.3 AR ratio始终很低

| 检查项 | 正常值 | 异常值 | 排查方向 |
|--------|--------|--------|----------|
| 模糊度方差 | 随历元减小 | 不变或增大 | H矩阵未正确约束模糊度 |
| Qb矩阵条件数 | <100 | >1000 | 双差协方差异常 |
| 浮点解残差 | <0.05m | >0.1m | 模糊度初始值偏差大 |

---

## 9. 附录：常用矩阵操作对照表

| 操作 | C版 (RTKLIB) | Java版 (EJML) | 说明 |
|------|---------------|---------------|------|
| 矩阵乘法 A*B | `matmul("NN",n,k,m,A,B,C)` | `A.mult(B)` | N=正常, T=转置 |
| 矩阵乘加 C+=A*B | `matmulp("TN",n,k,m,A,B,C)` | `C.plus(A.mult(B))` | p=plus |
| 矩阵求逆 | `matinv(A,n)` | `A.invert()` | LAPACK vs EJML |
| 矩阵转置 | `matmul("T",...)` 或手动 | `A.transpose()` | - |
| 矩阵复制 | `matcpy(dst,src,n,m)` | `dst.set(src)` | - |
| 单位矩阵 | `eye(n)` | `SimpleMatrix.identity(n)` | - |
| 矩阵减法 C=A*B | `matmulm("NT",n,k,m,A,B,C)` | `A.mult(B)` (alpha=-1) | m=minus |

---

## 10. 文件路径索引

| 文件 | 内容 | 关键函数/类 |
|------|------|-------------|
| `docs/MATRIX_DIMENSION_REFERENCE.md` | 本文档 | - |
| `src/main/java/org/rtklib/java/kalman/KalmanFilter.java` | Java版Kalman滤波 | `update()`, `predict()` |
| `src/main/java/org/rtklib/java/common/MatrixUtil.java` | EJML工具封装 | `createMatrix()`, `invert()` |
| `src/main/java/org/rtklib/java/rtkpos/RtkCore.java` | RTK核心算法 | `ddres()`, `ddcov()`, `varerr()` |
| `RTKLIB-2.5.0/src/rtkcmn.c` | C版矩阵工具 | `filter()`, `filter_()`, `matinv()` |
| `RTKLIB-2.5.0/src/rtkpos.c` | C版RTK核心 | `ddres()`, `ddcov()`, `varerr()` |

---

*文档版本：v1.0*
*创建日期：2026-07-02*
*最后更新：2026-07-02*
*维护者：RTKLIB Java移植团队*

---

## 11. 修订历史

| 版本 | 日期 | 修改内容 | 作者 |
|------|------|----------|------|
| v1.0 | 2026-07-02 | 初版创建，整理H/P/R矩阵维度对比 | AI Assistant |