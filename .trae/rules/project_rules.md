# RTKLIB Java 项目规则

## 项目结构
- 源码：src/main/java/org/rtklib/java/
- 测试：src/test/java/org/rtklib/java/
- 文档：docs/
- 数据：data/

## 构建与测试
- 编译：mvn compile
- 运行测试：mvn test
- 运行单个测试：mvn test -Dtest=RtkRinexCompareTest
- 打包：mvn package

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