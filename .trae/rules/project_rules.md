# RTKLIB Java 项目规则

> AI 协作约定：每次任务开始前先读本文件；改代码前先确认构建/测试命令，提交前执行「敏感信息自检」。

## 敏感信息与脱敏（强制，优先级最高）
本仓库为公开仓库，任何入库文件（代码、测试、文档、配置、脚本、提交信息）一律不得出现真实敏感信息。

### 禁止入库的内容
- 账号类：用户名、密码、Token、AppKey/AppSecret、Cookie、私有证书与密钥
- 服务类：内网/私有服务地址、带鉴权的 URL、私有端口、数据库连接串（含口令）
- 设备类：真实设备序列号/站号/设备 ID（如 `GS2025xxxxxx` 一类）
- 位置类：真实测站坐标、基站坐标、项目所在地、私有 RTCM 存储绝对路径（如 `D:\<PATH_TO_DEVICE_RTCM_STORAGE>`）
- 数据类：真实观测/星历原始数据、内部产品文件、客户交付结果

### 落地写法
- 测试代码：走 `System.getProperty(...)` 或环境变量读取，默认空串；数据缺失时自动 `Assume.assumeTrue` 跳过，不硬编码
- 配置：只提交 `*.template` / `*.example`，值用占位符（`<BASE_DEVICE_ID>`、`<ROVER_DEVICE_ID>`、`<BASE_LAT>`、`<PATH_TO_DEVICE_RTCM_STORAGE>`）
- 文档：结论、精度、统计量照写；站号/坐标/路径一律占位符，需要复现时说明"私有数据，不入库"
- 示例：用 `rover`/`base`、`<token>`、`https://example.com` 等中性值，不用真实值

### 提交前自检
```powershell
git grep -n -E "password|passwd|secret|token|apikey|appkey" -- .
git grep -n -E "GS20[0-9]{8}" -- .
git grep -n -E "tdengine-jetlinks|[0-9]{2}\.[0-9]{6,}" -- . ":(exclude)test-data"
```
- 命中即停止提交，改为占位符/环境变量后再提交
- 已误提交历史：先报告，由用户决定是否 `filter-repo` 重写；不得擅自 force push

## 项目结构（多模块）
父POM：pom.xml（packaging=pom，version=3.5.0），`<modules>` 共 12 个，顺序即构建顺序：

| 模块 | 包根 | 职责 |
|---|---|---|
| rtklib-core | org.rtklib.java.* | 核心定位算法：SPP / RTK / PPP / PPP-AR / PPP-RTK |
| rtklib-ai | org.rtklib.java.ai | AI 接口层：core 侧零依赖 SPI + 注入点，provider 实现与 ONNX 推理（接口一/三/四） |
| rtklib-stream | org.rtklib.java.stream | NTRIP 客户端 + 串口通讯（对齐 stream.c） |
| rtklib-adjust | org.rtklib.java.adjust | 多基线间接平差（σ₀ 检验 + Baarda 探测） |
| rtklib-joint | org.rtklib.java.joint | 联合 RTK：多基站/多基线联合滤波解算 |
| rtklib-product | org.rtklib.java.product | IGS/MGEX 精密产品下载（SP3/CLK/ERP/BIA/OSB/FCB/VMF3/GPT3 等） |
| rtklib-test | org.rtklib.java.test | 早期测试入口与 Demo（NtripClient 已被 stream 取代，保留兼容） |
| rtklib-otl | org.rtklib.java.otl | 海潮负荷 BLQ 系数生成（FES2004） |
| rtklib-research | org.rtklib.java.research | 研究沙盒：FGO / IMU 预积分 / 随机模型，与 core 无代码级耦合 |
| rtklib-vrs | org.rtklib.java.vrs | 虚拟参考站生成（单基站差分改正 + RTCM3 编码） |
| rtklib-networkvrs | org.rtklib.java.networkvrs | 网络 VRS：多站历元同步 + 误差插值（档 1 IDW；档 2/3 网解与 FKP/MAC） |
| rtklib-ext-igs | org.rtklib.java.ext.igs | IGS SSR 4076 扩展模块隔离（非标准 RTCM 消息） |

### rtklib-core 子包（org.rtklib.java.）
- 定位解算：`pntpos/`（SPP）、`rtkpos/`（RTK + 优化器）、`ppp/`（PPP/PPP-AR）、`pppar/`、`ppprtk/`（PPP-RTK）、`joint` 相关在 rtklib-joint、`kalman/`（EKF）、`ambiguity/`（LAMBDA）、`adaptive/`（自适应随机模型）、`aggregate/`
- 数据载体：`data/`（Nav/Obs/Eph/Ssr/Rtk/PrcOpt…）、`config/`（RtkConfig + CommonOpts/RtkOpts/PppOpts/PppArOpts/PppRtkOpts/SppOpts/AdaptiveOpts）
- 解析与模型：`rtcm/`、`rinex/`、`ephemeris/`、`ionosphere/`、`troposphere/`、`orbit/`、`coord/`、`time/`、`tide/`、`cssr/`
- 其他：`common/`、`constants/`、`monitor/`、`postpos/`、`trace/`

### 目录约定
- 测试：各模块 `src/test/java/`；测试资源 `src/test/resources/`（敏感配置只提交 `*.template`）
- 文档：`docs/`（现行）、`docs/archive/`（历史归档）
- 公开测试数据：`test-data/`（大文件按 `.gitignore` 排除，用 `download-test-data.ps1` 拉取）
- 本地私有数据：`data/`（不入库）

## 构建与测试
- 编译全部：mvn compile
- 编译core：mvn compile -pl rtklib-core
- 编译adjust：mvn compile -pl rtklib-adjust
- 运行全部测试：mvn test
- 运行core测试：mvn test -pl rtklib-core
- 运行adjust测试：mvn test -pl rtklib-adjust -Dtest=GnssBaselineAdjustTest
- 运行RTK优化测试：mvn test -pl rtklib-core -Dtest=RtkOptimizationsBootstrapTest,RtkOptimizationsBdsBiasTest,RtkOptimizationsResEditTest,RtkOptimizationsCascadeARTest,RtkOptimizationsPartialARTest
- 打包：mvn package
- 安装父POM：mvn install -N
- 安装core到本地仓库：mvn install -pl rtklib-core -DskipTests

### Maven 硬规则（不写就会踩坑）

**规则 1：`-pl` 必须配 `-am`（跨模块测试时强制）**

```powershell
# 错误：只用 -pl，兄弟模块不会一起构建
mvn -pl rtklib-test -Dtest=RefStarContractTest test

# 正确：带 -am，先把依赖模块构建进来
mvn -pl rtklib-test -am "-Dtest=RefStarContractTest" "-Dsurefire.failIfNoSpecifiedTests=false" test
```

不配 `-am` 的后果不是报错，是**静默用旧 jar**：`rtklib-test` 依赖的 `rtklib-core` / `rtklib-ai` 不会一起构建，Maven 会去本地仓库取上一次 `install` 留下的 jar。于是「测试通过但被测代码没更新」——改完 core 跑 test 模块，测的还是改动前的字节码。这类静默错误比编译失败更难发现，必须靠 `-am` 消除。

例外：`-pl` 指向的模块自身没有兄弟模块依赖时（如 `-pl rtklib-core`）可省略 `-am`。

**规则 2：PowerShell 下 `-D` 参数必须加引号**

```powershell
# 错误：PowerShell 按 "." 切断，报 Unknown lifecycle phase ".failIfNoSpecifiedTests=false"
mvn test -Dsurefire.failIfNoSpecifiedTests=false

# 正确：整体加引号
mvn test "-Dsurefire.failIfNoSpecifiedTests=false"
```

`-D` 值里含 `.`（属性名普遍如此）时，PowerShell 会把它当成多个参数拆开。凡 `-Dxxx.yyy=zzz` 形式一律写成 `"-Dxxx.yyy=zzz"`。值里含空格或逗号的同理（如 `-Dtest=A,B` 建议 `"-Dtest=A,B"`）。

**规则 3：离线模式（`-o`）下禁止省略 `-am`**

本地仓库缺少当前版本（`3.5.0`）的兄弟模块 jar 时，`-o` 会直接报 `Cannot access central ... in offline mode` 而不是回退联网。此时 `-am` 是唯一出路——它让 Maven 从源码构建依赖，绕过仓库查找。

**规则 4：`-Dtest` 指定的类在某个模块不存在时会失败**

`-pl a -am` 会让 reactor 里的**每个**模块都执行 surefire，`-Dtest=X` 在其中没有 `X` 的模块上会报 `No tests matching pattern "X" were executed!`。必须同时加 `"-Dsurefire.failIfNoSpecifiedTests=false"`。

## 依赖关系
模块间（单向，core 不反向依赖任何业务模块）：
- rtklib-adjust → rtklib-core
- rtklib-joint → rtklib-core
- rtklib-vrs → rtklib-core
- rtklib-networkvrs → rtklib-core + rtklib-vrs
- rtklib-ext-igs → rtklib-core
- rtklib-ai → rtklib-core（core 不反向依赖 ai；SPI 定义在 core，实现与 ONNX 推理在 ai）
- rtklib-test → rtklib-core + rtklib-product + rtklib-ai（+ rtklib-stream，test scope）
- rtklib-stream → rtklib-core（仅 test scope）
- rtklib-product → rtklib-core（仅 test scope）
- rtklib-research、rtklib-otl → 不依赖 core（独立演进）

第三方（版本统一在父 POM `dependencyManagement`）：
- ejml-simple / ejml-all 0.41、slf4j-api 2.0.9、logback-classic 1.4.14、junit-jupiter 5.10.1、jmh 1.37
- rtklib-stream 额外：com.fazecast:jSerialComm
- rtklib-product 额外：commons-net（FTPS/FTP）
- rtklib-ai 额外：onnxruntime（`${onnxruntime.version}`，父 POM `dependencyManagement` 统一）

## 高级优化（RtkConfig开关控制，默认关闭）
- RTK模糊度固定优化（v2.2.1）：CascadeAR/ResEdit/PartialAR/Bootstrap/BdsBias/ParamTypeNoise
- PPP/PPP-RTK优化（v2.2.2）：PppAR/PppArFixHold/PppPartialAR/PppRtk/PppRtkAR/PppRtkFixHold/Gpt3Vmf3/Iers2010

## 测试数据
- RINEX 观测文件：data/*.obs
- RINEX 导航文件：data/*.nav
- RTKLIB C 版参考结果：data/*.pos

## 代码规范
- Java 17
- 使用 EJML 进行矩阵运算（SimpleMatrix，行优先存储）
- 状态向量和协方差矩阵使用一维数组存储（行优先）
- 包命名：org.rtklib.java.{module}
- 对应 C 源码：RTKLIB 2.5.0