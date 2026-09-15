# RTKLIB Java 项目规则

## 项目结构（多模块）
- 父POM：pom.xml（packaging=pom，聚合rtklib-core + rtklib-adjust）
- rtklib-core：src/main/java/org/rtklib/java/（核心定位算法）
- rtklib-adjust：src/main/java/org/rtklib/java/adjust/（多基线间接平差）
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
- 打包：mvn package
- 安装父POM：mvn install -N
- 安装core到本地仓库：mvn install -pl rtklib-core -DskipTests

## 依赖关系
- rtklib-adjust → rtklib-core（单向依赖，core不依赖adjust）
- rtklib-core → ejml-simple:0.41, slf4j, logback
- rtklib-adjust → ejml-all:0.41, junit

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