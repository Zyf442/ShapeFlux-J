# 参考资料

核验日期：2026-10-27。以下用于背景与技术设计定位，没有将外部项目代码复制到本包。

1. PyTorch 官方文档，**Dynamic Shapes**，2.14 文档。说明动态尺寸、形状符号化和减少重编译的动机。  
   https://docs.pytorch.org/docs/2.14/user_guide/torch_compiler/torch.compiler_dynamic_shapes.html

2. PyTorch 官方文档，**Dealing with Recompilations**。说明守卫失效与重复编译成本。  
   https://docs.pytorch.org/docs/stable/user_guide/torch_compiler/compile/programming_model.recompilation.html

3. Ruihang Lai 等，**Relax: Composable Abstractions for End-to-End Dynamic Machine Learning**，arXiv:2311.02103，2023 提交、2025 修订，ASPLOS 2025。研究跨层抽象与符号形状。  
   https://arxiv.org/abs/2311.02103

4. UofT-EcoSystem，**DietCode Code Release**，作者代码仓库。作为动态张量程序优化的相关系统入口；本包不复现其自动调优器。  
   https://github.com/UofT-EcoSystem/DietCode

5. Oracle Java SE 17，**JavaCompiler**。标准 Java 编译接口、诊断与文件管理 SPI。  
   https://docs.oracle.com/en/java/javase/17/docs/api/java.compiler/javax/tools/JavaCompiler.html

6. Oracle Java SE 17，**ToolProvider**。系统 Java 编译器的获取方式。  
   https://docs.oracle.com/en/java/javase/17/docs/api/java.compiler/javax/tools/ToolProvider.html

文献支持问题背景，并不证明 ShapeFlux-J 相对已有成熟编译系统具备新颖性或性能优势。当前没有进行 PyTorch/TVM 的性能复现，也没有作跨语言性能排名。


## 随包新增真实数据与复现来源

- KDD Cup 1999 Data，UCI：https://archive.ics.uci.edu/dataset/130/kdd+cup+1999+data ，DOI：https://doi.org/10.24432/C51C7N 。
- KDD99全量镜像与公开摘要，scikit-learn数据加载源码：https://github.com/scikit-learn/scikit-learn/blob/main/sklearn/datasets/_kddcup99.py 。
- Poker Hand，UCI：https://archive.ics.uci.edu/dataset/158/poker+hand ，DOI：https://doi.org/10.24432/C5KW38 。
- 数据处理、实际下载来源和原始文件摘要见 `datasets/*/SOURCE.json`。数据许可分别保留CC BY 4.0链接与处理声明。

## CI配置来源

- GitHub checkout官方动作：https://github.com/actions/checkout 。
- GitHub setup-java官方动作：https://github.com/actions/setup-java 。

CI模板依据官方动作当前用法，提供配置不等于已在用户远端仓库执行。运行时Java项目不依赖这些动作或Python库。

## 2.4统计与矩阵语义核验

- NIST，均值的Student-t置信区间：https://itl.nist.gov/div898/handbook/eda/section3/eda352.htm 。
- Netlib，DGEMM矩阵乘法接口定义：https://www.netlib.org/lapack/explore-html/dd/d09/group__gemm_ga1e899f8453bcbfde78e91a86a2dab984.html 。本项目只实现无转置C=A×B的明确子集，未复制BLAS实现。
- Oracle，ToolProvider：https://docs.oracle.com/en/java/javase/17/docs/api/java.compiler/javax/tools/ToolProvider.html 。

以上2.4资料核验于2026-10-28。
