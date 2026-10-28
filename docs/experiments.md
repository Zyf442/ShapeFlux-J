# 实验命令与参数

[首页](../README.md) · [计时与统计](benchmark-methodology.md)

每次使用新的输出目录。所有命令均在项目根目录执行；Windows、Linux与macOS终端使用同样的Java参数。

## 真实数据的重复测量

```bash
java -jar dist/shapeflux-j.jar dataset --dataset kdd99 --max-rows 65536 --forks 5 --output results/my-kdd-5fork
java -jar dist/shapeflux-j.jar dataset --dataset covertype --forks 3 --output results/my-cov-full
```

`--max-rows 0`完整遍历。可用 `--input 文件.csv.gz --label none|first|last`测试自己的数值文件；--dataset与--input择一。`--batches`指定循环行数序列，`--modes`选择策略，`--timeout-seconds`指定单工作进程超时。

## 可组合归约与二维守卫

```bash
java -jar dist/shapeflux-j.jar bench --quick --trace examples/traces/multiaxis.csv --file examples/composable-softmax.sfx --forks 5 --warmup 3 --measure 5 --row-bucket 32 --col-bucket 32 --output results/my-composed
```

bench的cold序列执行一次，随后warmup遍，再执行measure遍。输入按形状与固定种子逐批重建；所有策略和同fork使用相同数值，外部生成与参考校验在引擎计时外。`--seed`指定基础种子，fork使用确定偏移，详见settings与metadata。

五种策略均可通过--modes指定。`--ablate`可加入关闭IR优化、循环展开、预算或持续复核的变体。配置存在不表示每个消融都在每个负载上产生可辨识差异。

## 实测形状轨迹

```bash
java -jar dist/shapeflux-j.jar trace --input examples/traces/recorded-combined.csv --reuse-window 128 --horizon 256 --row-bucket 1024 --col-bucket 32 --output results/my-trace
java -jar dist/shapeflux-j.jar bench --quick --trace examples/traces/recorded-poker.csv --file examples/rmsnorm.sfx --modes GENERIC,BUCKET,ADAPTIVE --forks 5 --warmup 1 --measure 2 --output results/my-recorded
```

第一条只分析实际批次形状；第二条重建数值测算子与编译成本。两者均不代表采集到线上服务的请求内容。标准化轨迹支持最多10000个请求；bench最多记录100万个请求，超限明确拒绝。

## 矩阵乘法

```bash
java -jar dist/shapeflux-j.jar matmul --rows 96 --inner 128 --cols 80 --guard family --forks 5 --warmup 4 --measure 9 --output results/my-matmul
java -jar dist/shapeflux-j.jar matmul --input-a examples/matmul-a.csv --input-b examples/matmul-b.csv --guard exact --forks 2 --output results/my-matmul-csv
```

输入A/B必须同时提供，形状从CSV确定，不同时使用rows/inner/cols。每种模式使用独立进程；A/B快照相同且前后检查摘要。REFERENCE、GENERIC、BLOCKED三种模式均与参考核验，任何失败都会使完整实验失败。

## 引擎配置

| 参数 | 默认值 | 含义 |
|---|---:|---|
| --cache | 8 | 专用内核上限 |
| --hot | 4 | 当前观测窗口内热点阈值 |
| --budget-ms | 500 | 自适应专用编译软预算 |
| --reuse-window | 128 | 热度滑动观测窗口 |
| --horizon | 256 | 预测请求预算，2.4使用窗口频率估计复用次数 |
| --row-bucket | 0 | 0为N二次幂，正数为固定跨度 |
| --col-bucket | 0 | 0固定D，正数为固定跨度 |
| --recheck | 32 | 专用命中复核间隔；0关闭 |
| --probe-rounds | 7 | A/B探测轮数，3～31的奇数 |
| --trace-events | on | 是否保存每请求派发日志 |

`--profile conservative|demo`选择配置。Demo的预测预算16384便于短演示，不等于默认研究结果。候选编译可能被拒绝，所有成本与失败记录保留。

## 报告与浏览

```bash
java -jar dist/shapeflux-j.jar report --input results/my-composed/summary.csv --output results/my-composed-report
java -jar dist/shapeflux-j.jar browse --input results --output results/experiment-index.html
```

report用于bench汇总格式，生成已有数据的报告；噪声原始记录在对应工作进程目录中。dataset与matmul在结束时自动生成各自报告。browse只读取小型元数据并生成可检索离线快照。

读报告先检查实际记录数、correct、失败/回退计数和应用摘要；再解释请求总计、裸计算、编译与区间。不存在所有输入、所有冷请求、所有设备都保证加速的结论。
