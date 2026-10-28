# 历史性能测量版本

`benchmark-reference.jar` 是 2026-10-27 五套 `verified-*` 性能实验实际使用的 2.0 测量版本。历史 metadata 中的程序哈希对应此文件。

2.1 中文版位于 `dist/shapeflux-j.jar`，所有正常启动入口使用该中文版。编译器、运行时和 IR 核心源码与历史版本逐文件哈希一致，记录见 `core-source-hashes.json`。中文版新增的功能检查在 `results/chinese-release-checks`；正式历史性能报告仅重新生成中文展示，原始计时数据保持不变。
