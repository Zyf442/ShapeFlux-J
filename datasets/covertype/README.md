# 随包 Covertype 真实数据

本目录包含完整公开数据和已处理的可导入特征，无需再次下载。

| 文件 | 内容 |
|---|---|
| `covtype.data.gz` | 官方原始文件，581,012 行，每行 54 个特征加末列分类标签 |
| `covtype.info` | 原始数据说明与版权声明，完整保留 |
| `SOURCE.json` | 官方来源、下载文件摘要、数据许可与引用 |
| `prepared/quick-1024x54.csv` | 前 1024 行纯特征，可直接“导入矩阵” |
| `prepared/quick-8192x54.csv` | 前 8192 行纯特征，可直接“导入矩阵” |
| `prepared/batches/` | 完整数据拆分为 71 个纯特征 CSV，保持原始行顺序 |
| `prepared/batches.csv` | 批次文件、行范围、形状与文件校验值清单 |
| `prepared/preparation.json` | 全量准备记录 |

原始数据保留全部标签。处理后的 CSV 只删除末列分类标签，将数字序列化为十进制浮点文本；没有缩放、抽样、打乱或补零。71 个批次合计仍为 581,012 行、54 个特征，最后一批为实际剩余行数。两个 quick 文件是重复提供的入门样本，统计全量时不要再次加算。

程序测试的是数值算子的计算结果、编译和缓存成本，不使用标签训练分类器。归一化算子是在每行内部进行，不是预先对整个数据集按列标准化。

## 来源、版权与许可

- 数据集：Covertype，UCI Machine Learning Repository。
- 原始权利人包括 Jock A. Blackard 和 Colorado State University；原始版权与使用说明见 `covtype.info`，请一并保留。
- 官方页面：https://archive.ics.uci.edu/dataset/31/covertype
- 官方下载：https://archive.ics.uci.edu/static/public/31/covertype.zip
- UCI 当前标注许可：Creative Commons Attribution 4.0 International（CC BY 4.0）。许可：https://creativecommons.org/licenses/by/4.0/
- 引用：Blackard, J. (1998). Covertype [Dataset]. UCI Machine Learning Repository. https://doi.org/10.24432/C50K5N.
- 本项目的数据处理改动已在上文说明。数据使用自身许可，不适用本项目 Java 代码的 MIT 许可。

重新准备：在项目根目录运行 `java -cp dist/shapeflux-j.jar tools/PrepareCovertype.java datasets/covertype/covtype.data.gz datasets/covertype/prepared-new`，输出目录须为新目录。

使用步骤见项目根目录 `先看这里_数据集测试说明.md`。
