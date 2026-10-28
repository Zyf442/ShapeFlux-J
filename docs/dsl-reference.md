# DSL与数值约定

[返回首页](../README.md) · [示例文件](../examples)

## 语法和输入

```text
let name = expression;
out = expression;
```

必须有且只有一个out，作为最后一条语句；每句以分号结束。不可重复定义变量、覆盖x/bias或保留名。支持算术、一元正负、括号和函数组合；`#`或`//`开始行尾注释。只接受有限数值常量，无字符串、成员/数组访问、反射或任意Java片段。

`x:[N,D]`是行优先double矩阵，`bias:[D]`沿行广播；常量全矩阵广播。N、D为正，输入不超过800万元素。语法上限16000字符、200节点、48层一元嵌套。

## 逐元素函数

| 函数 | 数值定义 |
|---|---|
| relu、tanh、sigmoid | 整流、双曲正切、稳定正负分支逻辑函数 |
| gelu、silu | 0.044715系数tanh近似GELU；a×sigmoid(a) |
| sqrt、exp、log、abs | Java Math对应定义 |
| min、max | Math.min/Math.max，包括NaN和有符号零行为 |
| sin、cos | Math三角函数 |
| expm1、log1p | exp(a)-1与log(1+a)的专用Math计算 |
| pow(a,b) | Math.pow(a,b) |
| floor、ceil | 向下/向上取整，输出仍为double |
| square(a) | a×a，不引入乘加融合 |

## 行算子可以组合

| 函数 | 定义 | 最终根输出 |
|---|---|---|
| rowsum | 按列顺序从+0累加 | N×1 |
| rowmean | 行和除以D | N×1 |
| rowmax、rowmin | 按列使用Math.max/Math.min | N×1 |
| rowvar | Welford总体方差，除以D | N×1 |
| softmax | 减行最大值、求指数、除以行和 | N×D |
| layernorm | Welford均值/总体方差，epsilon=1e-5 | N×D |
| rmsnorm | 缩放平方和与hypot，epsilon=1e-5 | N×D |

中间归约得到的行标量沿D列广播，所以 `x-rowmean(x)` 合法。若out根仍为行归约节点，即使经变量引用，也使用N×1输出；参与算术后则按广播后的N×D表达式输出。

```text
let m = rowmean(x);
let centered = x - m;
out = rowmean(square(centered));
```

```text
let e = exp(x - rowmax(x));
out = e / rowsum(e);
```

最后一个例子的输出为N×D。`let s=rowsum(2); out=s;`紧凑输出每行2D；`out=x+s;`则把2D加到该行每个元素。常量归约依赖D，不作为普通常量提前求值。

编译器按行阶段降低组合图，临时数组按D分配，跨行复用。临时预算不超过3200万个double；参考解释器另有3200万个中间元素的活跃预算。超限会明确拒绝，避免把内存不足隐藏为正常结果。

## 浮点和校验

遵循Java17的double及Math语义，不任意重排、结合或调用fma。除零、负数对数/平方根遵循IEEE产生无穷或NaN，不自动裁剪；非有限归一化可能产生NaN。

有限值使用 `abs(expected-actual) <= 1e-11 + 1e-10*abs(expected)`。同时比较NaN/无穷类别和有符号零；长度、类别或符号不匹配时最大误差记为无穷。数值一致性不表示模型准确率。

矩阵乘法使用独立 `matmul`入口，语义见[功能说明](completed-features.md)，不扩展为任意Java表达式。
