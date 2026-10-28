package io.shapeflux.i18n;

import io.shapeflux.ir.*;
import io.shapeflux.runtime.*;
import java.util.*;

/** Chinese presentation layer. Engine identifiers, fingerprints and numerical semantics stay canonical. */
public final class ZhCn {
    private ZhCn() {}
    private static final Map<String,String> NAMES=Map.ofEntries(
        Map.entry("REFERENCE","参考矩阵乘法"),Map.entry("BLOCKED","分块矩阵编译"),Map.entry("INTERPRETED","逐算子解释执行"),Map.entry("GENERIC","通用融合编译"),
        Map.entry("EXACT","精确形状特化"),Map.entry("BUCKET","形状分桶编译"),Map.entry("ADAPTIVE","自适应编译优化"),
        Map.entry("BUCKET_NO_IR","分桶编译·关闭图优化"),Map.entry("BUCKET_NO_UNROLL","分桶编译·关闭循环展开"),
        Map.entry("ADAPTIVE_NO_IR","自适应·关闭图优化"),Map.entry("ADAPTIVE_NO_UNROLL","自适应·关闭循环展开"),
        Map.entry("ADAPTIVE_NO_BUDGET","自适应·取消预算限制"),Map.entry("ADAPTIVE_NO_MONITOR","自适应·关闭持续复核"),
        Map.entry("hotset","热点形状集合"),Map.entry("jitter","形状轻微波动"),Map.entry("shift","输入分布切换"),
        Map.entry("longtail","长尾混合负载"),Map.entry("custom","自定义形状轨迹"),Map.entry("Custom CSV","自定义形状轨迹"),
        Map.entry("Conservative","稳健研究配置"),Map.entry("Demo horizon (16384)","演示配置（长复用窗口）"),Map.entry("Custom settings","自定义参数"),
        Map.entry("Activation chain","激活函数融合链"),Map.entry("Stable row softmax","稳定行归一化（Softmax）"),
        Map.entry("Row reduction","逐行求和"),Map.entry("CSE + constant folding","公共表达式消除与常量折叠"),
        Map.entry("Layer normalization","层归一化（LayerNorm）"),Map.entry("RMS normalization","均方根归一化（RMSNorm）"),
        Map.entry("Composable variance","可组合归约 · 行方差"),Map.entry("Composed stable softmax","可组合归约 · 稳定Softmax"),Map.entry("Trigonometric features","三角与对数特征"),
        Map.entry("Mean magnitude","平均幅值计算"),Map.entry("Gated residual","门控残差计算")
    );
    public static String name(Object value){String key=String.valueOf(value);return NAMES.getOrDefault(key,key);}
    public static String guard(String value) {
        return switch(value) {
            case "generic" -> "通用内核";case "generic_after_failure" -> "通用内核（异常回退）";
            case "INTERPRETER_FALLBACK" -> "解释执行（异常回退）";case "INTERPRETED" -> "逐算子解释执行";
            default -> value.replace("..","～").replace("x"," 行 × ")+" 列";
        };
    }
    public static String op(Op op) {
        return switch(op) {
            case X->"输入矩阵 x";case BIAS->"行广播偏置 bias";case CONSTANT->"数值常量";
            case ADD->"逐元素相加";case SUB->"逐元素相减";case MUL->"逐元素相乘";case DIV->"逐元素相除";case NEG->"取负";
            case RELU->"线性整流激活 relu";case TANH->"双曲正切激活 tanh";case SIGMOID->"逻辑激活 sigmoid";
            case GELU->"高斯误差线性激活 gelu";case SILU->"平滑线性激活 silu";case SQRT->"平方根 sqrt";
            case EXP->"自然指数 exp";case LOG->"自然对数 log";case ABS->"绝对值 abs";case MAX->"逐元素最大值";case MIN->"逐元素最小值";
            case POW->"逐元素幂 pow";case SIN->"正弦 sin";case COS->"余弦 cos";case EXPM1->"指数减一 expm1";case LOG1P->"一加对数 log1p";case FLOOR->"向下取整";case CEIL->"向上取整";case SQUARE->"平方";
            default->terminal(Program.Terminal.function(op.name().toLowerCase(Locale.ROOT)))+"（可组合行阶段）";
        };
    }
    public static String terminal(Program.Terminal terminal) {
        return switch(terminal) {
            case MAP->"逐元素输出";case SOFTMAX->"稳定行归一化";case ROW_SUM->"逐行求和";case ROW_MEAN->"逐行求均值";case ROW_MAX->"逐行最大值";case ROW_MIN->"逐行最小值";case ROW_VAR->"逐行总体方差";
            case LAYER_NORM->"层归一化";case RMS_NORM->"均方根归一化";
        };
    }
    public static String ir(Program program) {
        StringBuilder text=new StringBuilder("输入矩阵 x：[N 行，D 列]，双精度浮点\n偏置 bias：[D 列]，沿行广播\n\n");
        for(Node node:program.reachable()) {
            text.append(node.name()).append(" = ").append(op(node.op()));
            if(node.op()==Op.CONSTANT)text.append('(').append(node.value()).append(')');
            else if(!node.args().isEmpty())text.append(node.args().stream().map(Node::name).toList());
            text.append('\n');
        }
        return text.append("\n最终输出：").append(terminal(program.terminal())).append('(').append(program.output().name())
            .append(")，形状 [N，").append(program.terminal().reduces()?"1":"D").append("]\n").toString();
    }
    public static String stats(Engine.Stats s) {
        return String.format(Locale.ROOT,"""
            请求次数：%d　目标缓存命中：%d　未命中：%d
            专用形状查找未命中：%d　缓存淘汰：%d
            编译产物：%d　编译或验证失败：%d　运行异常：%d
            通用内核回退：%d　解释执行回退：%d
            候选接纳：%d　候选拒绝：%d　特化撤销：%d　周期复核：%d
            累计编译：%.3f 毫秒（其中专用编译 %.3f 毫秒）
            累计验证：%.3f 毫秒　收益探测与复核：%.3f 毫秒
            当前专用缓存：%d　保留字节码：%d 字节
            """,s.requests(),s.hits(),s.misses(),s.guardMisses(),s.evictions(),s.compilations(),s.compileFailures(),s.runtimeFailures(),
            s.genericFallbacks(),s.fallbacks(),s.promotions(),s.rejections(),s.demotions(),s.monitorChecks(),s.compileNanos()/1e6,
            s.specialCompileNanos()/1e6,s.validationNanos()/1e6,s.probeNanos()/1e6,s.cachedSpecializations(),s.retainedBytecodeBytes());
    }
    public static String event(Engine.Event event) {
        String type=switch(event.type()) {
            case "COMPILE"->"编译完成";case "DISPATCH"->"执行派发";case "FALLBACK"->"启用回退";case "RUNTIME_FAIL"->"内核运行异常";
            case "COMPILE_FAIL"->"编译失败";case "VALIDATION_REJECT"->"数值验证未通过";case "EVICT"->"缓存淘汰";case "DEFER"->"暂缓特化";
            case "PROBE"->"候选收益探测";case "PROMOTE"->"接纳专用内核";case "REJECT"->"拒绝专用内核";
            case "RECHECK"->"持续收益复核";case "DEMOTE"->"撤销专用内核";case "PROBE_FAIL"->"收益探测异常";default->event.type();
        };
        String detail=event.detail().replace("Interpreter after generic compilation failure","通用编译不可用，改用解释执行")
            .replace("generic=","通用耗时=").replace("candidate=","候选耗时=")
            .replace("generic","通用内核").replace("[hit]","[缓存命中]").replace("[miss]","[缓存未命中]")
            .replace("predicted=","预测复用次数=").replace("observed=","窗口频次=").replace("gap=","平均再次到达间隔=").replace("horizon=","预测复用次数=").replace("batch=","每轮批量=").replace("ratio=","候选/通用耗时比=")
            .replace(" ms"," 毫秒").replace(" us"," 微秒").replace("estimated payback too long","预计收益不足以回收编译成本")
            .replace("compilation budget exhausted","专用编译预算已耗尽").replace("insufficient measured benefit","实测收益不足")
            .replace("measured regression","实测性能退化").replace("recheck failure:","复核异常：")
            .replace("Interpreter after generic compilation failure","通用编译不可用，改用解释执行");
        return "第 "+event.request()+" 次请求｜"+type+"｜"+message(detail);
    }
    public static String dsl(String source) {
        return source.replace("# Fused activation chain / x:[N,D], bias:[D]","# 激活函数融合链；x 为矩阵，bias 为行广播偏置")
            .replace("# Layer normalization without trainable affine parameters; epsilon=1e-5","# 层归一化；不含可训练仿射参数；稳定常数为 1e-5");
    }
    public static String message(String original) {
        if(original==null || original.isBlank())return "发生未提供详细信息的运行错误，请检查输入和日志。";
        return original.replace("Undefined variable:","未定义的变量：").replace("Invalid or duplicate variable:","变量名无效或重复：")
            .replace("Unknown function:","未知函数：").replace("Unsupported function:","不支持的函数：")
            .replace("Missing out = expression; statement","缺少输出语句：out = 表达式;").replace("out must be the last statement","out 必须是最后一条语句")
            .replace("Expected '","需要 '").replace("', got '","'，实际为 '").replace("(near character ","（靠近第 ")
            .replace("Expression nesting limit exceeded","表达式嵌套层数超过上限").replace("Constants must be finite","常量必须是有限数值")
            .replace("DSL must contain at most 16000 characters","算子程序不能超过 16000 个字符").replace("Illegal character:","非法字符：")
            .replace("Invalid number at line ","数值格式错误，行：").replace("Invalid number","数值格式错误").replace("Invalid shape","输入形状无效").replace("Invalid engine configuration","引擎参数无效")
            .replace("Kernel shape guard failed","内核形状守卫校验失败").replace("Numerical check failed","数值结果未通过参考校验")
            .replace("Non-rectangular CSV at line ","矩阵各行列数不一致，出错行：").replace("Invalid number at line ","数值格式错误，行：")
            .replace(", column ","，列：").replace("Empty numeric CSV:","数值文件为空：")
            .replace("Bias CSV must contain exactly one row with ","偏置文件必须只有一行，列数应为 ").replace(" values"," 个数值")
            .replace("Matrix exceeds 8 million elements","矩阵超过 800 万元素上限").replace("CSV exceeds element limit","数值文件超过元素数上限")
            .replace("Unsupported project format","不支持的工程文件格式").replace("Project exceeds 128 KB","工程文件超过 128 KB 上限")
            .replace("Invalid project shape","工程中的输入形状无效").replace("Invalid project workload","工程中的负载名称无效")
            .replace("Missing project field:","工程缺少字段：")
            .replace("Invalid workload shape","形状轨迹包含无效的输入形状").replace("Unknown workload:","未知负载：")
            .replace("Expected rows,cols,repeat:","每行需要 rows,cols,repeat 三个字段：").replace("Workload supports 1..10000 requests","形状轨迹只支持 1～10000 次请求")
            .replace("Empty workload","形状轨迹为空").replace("Workload data exceeds 32 million doubles; split it into smaller traces","轨迹输入超过 3200 万个数值，请拆成较小的轨迹")
            .replace("CSV line is too large","CSV 单行数据过大").replace("Output shape mismatch","输出形状不匹配")
            .replace("Invalid boolean:","布尔参数无效：").replace("Invalid encoded engine configuration","工程中的引擎配置编码无效").replace("Invalid encoded boolean","工程中的布尔值编码无效")
            .replace("Program exceeds ","程序超过 ").replace(" IR nodes"," 个计算图节点")
            .replace("For input string:","无法解析数值：").replace("Task cancelled","任务已取消")
            .replace("Worker timeout; see ","工作进程超时，请查看 ").replace("Worker failed; see ","工作进程执行失败，请查看 ")
            .replace("Benchmark output already contains a run; choose a new directory","输出目录已有实验记录，请选择新的目录")
            .replace("A full JDK 17+ is required; no system Java compiler is available","未找到系统编译器，请安装完整 JDK 17 或更新版本");
    }
    public static String benchmarkSummary(List<Map<String,String>> rows) {
        StringBuilder result=new StringBuilder("各策略均在独立 Java 进程中运行。完整图表请点击“查看报告”。\n\n");
        for(Map<String,String> row:rows)result.append(name(row.get("workload"))).append("｜").append(name(row.get("variant")))
            .append("\n  冷序列总耗时：").append(row.get("cold_total_ms")).append(" 毫秒；请求总耗时中位数：")
            .append(row.get("steady_wall_median_us")).append(" 微秒\n  编译产物：").append(row.get("compilations"))
            .append("；数值校验：").append("true".equals(row.get("correct"))?"通过":"未通过").append("\n\n");
        return result.toString();
    }
}
