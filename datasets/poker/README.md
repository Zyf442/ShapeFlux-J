# Poker Hand：完整百万条特征输入

[数据集地图](../../docs/datasets.md) · [使用教程](../../先看这里_数据集测试说明.md)

本包包含完整原始训练文件25,010行、测试文件1,000,000行，合计 **1,025,010 行、10 个特征**。数据用于数值算子的性能遍历，末列分类标签不参与计算。

| 文件 | 内容 |
|---|---|
| `poker-hand-training-true.data` | 原始训练文件，10个特征和1列标签 |
| `poker-hand-testing.data` | 原始百万行测试文件 |
| `poker-hand.names` | 原始字段说明 |
| `prepared/data.csv.gz` | 训练文件在前、测试文件在后，去掉末列标签的完整纯特征流 |
| `prepared/quick-1024x10.csv` | 1024行快速导入样本 |
| `prepared/quick-8192x10.csv` | 8192行快速导入样本 |
| `prepared/preparation.json` / `SOURCE.json` | 处理记录、文件摘要、实际来源与许可 |

每条记录保留五张牌的花色与点数十个原始特征；不缩放、不打乱、不抽样。合并训练和测试文件是为了完整遍历算子性能，不用于分类模型训练或准确率评估。数据处理代码位于 `DatasetPreparation.poker`。

来源：https://archive.ics.uci.edu/dataset/158/poker+hand

引用：Cattral, R. & Oppacher, F. (2002). Poker Hand [Dataset]. UCI Machine Learning Repository. https://doi.org/10.24432/C5KW38.

UCI标注许可：CC BY 4.0，https://creativecommons.org/licenses/by/4.0/ 。请保留来源、引用和处理说明，数据不适用本项目Java代码的MIT许可。

运行：`java -jar dist/shapeflux-j.jar dataset --dataset poker --output results/my-poker`。
