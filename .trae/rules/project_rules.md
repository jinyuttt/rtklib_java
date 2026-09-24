# RTKLIB Java 项目规则

## 项目结构（多模块）
- 父POM：pom.xml（packaging=pom，聚合rtklib-core + rtklib-adjust + rtklib-product）
- rtklib-core：src/main/java/org/rtklib/java/（核心定位算法）
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

## RTK高级模糊度固定优化（v2.2.1）
- 所有优化通过RtkConfig独立开关控制，默认全部关闭
- 实现类：RtkOptimizationsCascadeAR/ResEdit/PartialAR/Bootstrap/BdsBias
- 侵入方式：RtkCore中8个if(cfg.enableXxx)分支，默认关闭时与原版RTKLIB行为完全一致
- 27个单元测试全部通过
- 详见：docs/RTK_Extra_Optimizations.md 第10~15节

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