# KDD99：完整网络连接特征流

[数据集地图](../../docs/datasets.md) · [使用教程](../../先看这里_数据集测试说明.md)

本包包含完整原始训练数据 **4,898,431 行、41 个原始特征**，以及可直接使用的 **122 列独热编码数值输入**。数据用于编译与算子性能测试，不训练入侵检测模型。

| 文件 | 内容 |
|---|---|
| `kddcup.data.gz` | 完整原始训练文件，41个特征加类别标签 |
| `kddcup.names` | 原始字段与标签说明 |
| `task.html` / `kddcup99.html` | UCI原始任务和数据说明 |
| `prepared/data.csv.gz` | 全部4,898,431行的纯数值特征，无标签 |
| `prepared/quick-1024x122.csv` | 可直接导入矩阵的1024行样本 |
| `prepared/quick-8192x122.csv` | 可直接导入矩阵的8192行样本 |
| `prepared/schema.json` | 数值源列顺序与三个独热编码字典 |
| `prepared/preparation.json` | 全量行列数、处理方式、摘要 |
| `SOURCE.json` | 实际下载来源、引用和原始文件摘要 |

## 转换规则

保留原始行顺序与38个数值特征原值。对原始第2、3、4列 protocol_type、service、flag 依次做独热编码，类别按完整原文件中的字典序固定。词表大小为3、70、11，故最终38+3+70+11=122列。删除原始第42列类别标签，不缩放、不打乱、不抽样；quick文件是便利副本，不另算总样本数。

完整训练文件含有大量重复记录和偏斜分布，属于历史数据。这里的结果只描述特征流上的算子数值与性能，不代表现代网络安全检测能力。

## 来源和许可

- 原始数据：UCI KDD Cup 1999 Data，https://archive.ics.uci.edu/dataset/130/kdd+cup+1999+data
- 引用：Stolfo, S., Fan, W., Lee, W., Prodromidis, A., & Chan, P. (1999). KDD Cup 1999 Data [Dataset]. UCI Machine Learning Repository. https://doi.org/10.24432/C51C7N.
- UCI标注许可：CC BY 4.0，https://creativecommons.org/licenses/by/4.0/
- 原始旧站下载返回403/404后，本包使用scikit-learn公开的全量镜像，并核对该项目公开的SHA-256：`3b6c942aa0356c0ca35b7b595a26c89d343652c9db428893e7494f837b274292`。核验来源：https://github.com/scikit-learn/scikit-learn/blob/main/sklearn/datasets/_kddcup99.py
- 实际文件来源记录于 `SOURCE.json`，原始任务元数据来自UCI。预处理改动已在上文标注。数据不适用本项目Java代码的MIT许可。

运行：`java -jar dist/shapeflux-j.jar dataset --dataset kdd99 --max-rows 65536 --output results/my-kdd`。去掉行数上限即可全量测试。
