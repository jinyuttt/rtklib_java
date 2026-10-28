# RTKLIB Java 项目规则

> AI 协作约定：每次任务开始前先读本文件；改代码前先确认构建/测试命令，提交前执行「敏感信息自检」。

## 敏感信息与脱敏（强制，优先级最高）
本仓库为公开仓库，任何入库文件（代码、测试、文档、配置、脚本、提交信息）一律不得出现真实敏感信息。

### 禁止入库的内容
- 账号类：用户名、密码、Token、AppKey/AppSecret、Cookie、私有证书与密钥
- 服务类：内网/私有服务地址、带鉴权的 URL、私有端口、数据库连接串（含口令）
- 设备类：真实设备序列号/站号/设备 ID（如 `GS2025xxxxxx` 一类）
- 位置类：真实测站坐标、基站坐标、项目所在地、私有 RTCM 存储绝对路径（如 `D:\tdengine-jetlinks\...`）
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
- 父POM：pom.xml（packaging=pom，聚合rtklib-core + rtklib-adjust + rtklib-product + rtklib-test + rtklib-otl）
- rtklib-core：src/main/java/org/rtklib/java/（核心定位算法：SPP/RTK/PPP/PPP-RTK）
- rtklib-adjust：src/main/java/org/rtklib/java/adjust/（多基线间接平差）
- rtklib-product：src/main/java/org/rtklib/java/product/（精密星历下载模块）
- 测试：各模块 src/test/java/
- 文档：docs/
- 数据：data/

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

## 依赖关系
- rtklib-adjust → rtklib-core（单向依赖，core不依赖adjust）
- rtklib-core → ejml-simple:0.41, slf4j, logback
- rtklib-adjust → ejml-all:0.41, junit

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