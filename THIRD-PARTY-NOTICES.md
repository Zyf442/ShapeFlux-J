# 第三方资源说明

本项目的 Java 自编代码采用根目录的 MIT 许可。以下字体使用其自身许可：

- 名称：Noto Sans CJK SC Regular（未修改的官方 OpenType 字体）。
- 用途：系统缺少中文字体时的本地显示后备；正常运行无需网络下载。
- 原始项目：https://github.com/notofonts/noto-cjk
- 版权与许可：字体版权归其原始权利人所有，使用 SIL Open Font License 1.1；完整版权声明及许可见 `assets/fonts/LICENSE.txt`。
- 原始下载地址和 SHA-256：见 `assets/fonts/SOURCE.json`。
- 文件：`assets/fonts/NotoSansCJKsc-Regular.otf`。字体文件未作子集化、修改或更名。

随包字体必须连同其版权及许可文件一起保留。本项目不捆绑 JDK。

## Covertype 数据

本包包含 UCI Covertype 原始公开数据及删除标签列后的 CSV 批次。数据权利人、原始说明、CC BY 4.0 许可链接、引用、下载来源和处理改动见 `datasets/covertype/README.md`、`covtype.info` 和 `SOURCE.json`。数据不适用本项目 Java 代码的 MIT 许可。

## 新增完整公开数据

KDD Cup 1999 Data与Poker Hand均保留原始说明、引用、UCI页面许可和实际来源记录，使用CC BY 4.0。具体转换与版权见 `datasets/kdd99/README.md`、`datasets/poker/README.md` 和各自SOURCE文件。KDD99原始文件由scikit-learn公开镜像取得并核对其发布的完整SHA-256，UCI任务元数据另行保留；这不引入scikit-learn运行依赖。

文档静态图表与工作台动图由本项目代码、真实组件和原始统计生成。图表中文字使用随包Noto字体，字体遵守其SIL OFL 1.1许可；图示不包含第三方品牌背书或虚构Star/CI状态。
