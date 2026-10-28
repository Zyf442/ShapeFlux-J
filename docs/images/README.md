# 文档图片与统计来源

- `hero`：项目定位与已核验规模的静态视觉封面。
- `architecture`、`compiler-flow`、`optimization`、`shape-guard`：实现结构与计算含义示意，不冒充真实编译输出。
- `quickstart`：与实际文件入口一致的操作路径。
- `dataset-scale`：随包preparation和目录清单核验的完整记录数。
- `streaming`：读取和执行路径，输入数组大小由形状计算，未冒充进程RSS实测。
- `workbench-*.png`：真实Swing组件无显示器渲染。
- `workbench-demo.gif`：上述五个真实标签截图组成的切换演示，不是实时录屏。
- `benchmark-engine`、`benchmark-compiles`：从 `results/v23-*-full/summary.csv` 生成，单次独立进程/策略，无伪造误差线。
- `badge-*`：静态事实徽章，本地检查数不代表远端CI状态。

SVG中图表文字以字形轮廓保存，以避免不同系统中文字体缺失。运行时字体与字体许可位于 `assets/fonts`。PNG备用图便于本地预览。

## 2.4图片

- workbench-v24：当前版本真实Swing组件、7个结果标签与可筛选实验浏览；不是网页模型图。
- composable-reduction：1,2,3的行方差手算与实际DSL语义。
- shape-guard-2d：N/D跨度32的精确区间与边界示意。
- matrix-multiply：96×128×80，与当前五fork矩阵实验尺寸一致。
- confidence-interval：直接读取results/v24-matmul-5fork/confidence.csv，展示计算与冷请求的Student-t区间。

旧benchmark-engine/compiles和五标签GIF保持2.3历史语境，不作2.4的性能结论。
