# P2/P3修复数值验证说明

## 修复概述

### P2: Fix-and-Hold最小历元守卫

**问题**：原代码在模糊度固定后立即收紧方差，没有检查是否达到足够的连续固定历元数。

**修复**：在`pppArFixHold()`方法开头添加检查：
```java
if (rtk.nfix < cfg.pppArFixHoldMinEp) return;
```

**验证逻辑**：
- 当`nfix < pppArFixHoldMinEp`（默认50）时，方法立即返回，不修改任何方差
- 当`nfix >= pppArFixHoldMinEp`时，遍历所有已固定模糊度，将方差>pppArFixHoldVar的收紧到pppArFixHoldVar（默认1e-6）

**单元测试用例**（PppArFixVerificationTest.testFixAndHoldMinEpochGuard）：
1. 设置3颗GPS卫星，初始方差=0.5 m²
2. `nfix=49`时调用pppArFixHold() → 验证方差保持0.5不变
3. `nfix=50`时调用pppArFixHold() → 验证方差收紧到1e-6
4. `nfix=100`时重新设置方差=0.1 → 验证再次收紧到1e-6

### P3: Partial AR周空间转换

**问题**：原代码直接在米空间调用LAMBDA方法，但LAMBDA要求输入是整周模糊度（cycle space），导致固定失败。

**修复**：
1. **候选过滤**：将方差从m²转换为cycles²，拒绝varCyc > 16.0（4σ）的模糊度
   ```java
   double varCyc = var / (lam1 * lam1);
   if (var <= 0 || varCyc > 16.0) continue;
   ```

2. **LAMBDA输入转换**：将浮点模糊度和协方差从米转换为周
   ```java
   y[i] = ambFloat[i] / lam_i;  // 米 → 周
   Q[i*nb+j] = rtk.P[idx*nx+idxJ] / (lam_i * lam1List[j]);  // 协方差双线性变换
   ```

3. **固定结果转换**：将LAMBDA返回的整周值转换回米
   ```java
   double fixedAmb = Math.round(b[i]) * lam1List[i];  // 周 → 米
   ```

**验证逻辑**：
- GPS L1波长：λ = c/f = 299792458/1575.44e6 ≈ 0.19029 m
- BDS B1波长：λ = c/f = 299792458/1561.098e6 ≈ 0.19204 m
- 若浮点模糊度=5.1λ，方差=0.01 m² → varCyc=0.28 cycles² < 16，通过过滤
- LAMBDA应固定到5 cycles = 5×0.19029 = 0.95145 m

**单元测试用例**：

1. **testPartialARCycleSpaceConversion**：
   - 设置6颗GPS卫星，浮点模糊度=[5.1, 8.2, 12.3, 15.4, 20.5, 25.6] cycles
   - 方差=0.01 m²（≈0.28 cycles²）
   - 调用pppPartialAR() → 验证返回nb≥4
   - 验证每颗固定卫星的模糊度=round(cycles)×lambda

2. **testPartialARVarianceFilter**：
   - 设置8颗卫星：4颗低方差（0.01 m²），4颗高方差（1.0 m²）
   - 调用pppPartialAR() → 验证只固定低方差的4颗
   - 高方差卫星的fix[0]应保持0

3. **testPartialARMultiSystemWavelength**：
   - 设置3颗GPS（λ=0.19029m）+ 3颗BDS（λ=0.19204m）
   - 调用pppPartialAR() → 验证至少固定4颗
   - GPS固定值/λGPS应为整数
   - BDS固定值/λBDS应为整数

## 数值示例

### 示例1：单颗GPS卫星固定过程

**输入**：
- sat=1, float_amb=0.9705 m（≈5.1 cycles）
- var=0.01 m²
- lambda_GPS=0.19029 m

**处理**：
1. 过滤：varCyc = 0.01 / 0.19029² = 0.276 < 16 ✓
2. 转换：y[0] = 0.9705 / 0.19029 = 5.1 cycles
3. LAMBDA：返回b[0]=5.08 → round=5 cycles
4. 反转换：fixed_amb = 5 × 0.19029 = 0.95145 m

**输出**：
- rtk.x[idx] = 0.95145 m
- rtk.ssat[0].fix[0] = 1

### 示例2：高方差被拒绝

**输入**：
- sat=2, float_amb=1.5 m
- var=1.0 m²
- lambda_GPS=0.19029 m

**处理**：
1. 过滤：varCyc = 1.0 / 0.19029² = 27.6 > 16 ✗
2. 跳过该卫星

**输出**：
- rtk.ssat[1].fix[0] = 0（未固定）

## 运行测试

```bash
cd rtklib-java/rtklib-core
mvn test -Dtest=PppArFixVerificationTest
```

预期输出：
```
[INFO] Tests run: 4, Failures: 0, Errors: 0, Skipped: 0
```

## 与真实数据的区别

真实PPP-AR处理中，模糊度方差通常较大（>0.5 m²），因为：
1. 电离层/对流层残差影响
2. 多路径效应
3. 精确星历/钟差残差
4. SP3产品缺失BDS GEO卫星（导致可用卫星数<4）

因此真实数据中Partial AR成功率较低，但单元测试通过构造理想输入验证了修复的数值正确性。
