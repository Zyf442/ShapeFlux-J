package io.shapeflux;

import io.shapeflux.experiment.*;
import io.shapeflux.io.*;
import io.shapeflux.ir.*;
import io.shapeflux.runtime.*;
import io.shapeflux.ui.Dashboard;
import io.shapeflux.i18n.ZhCn;
import javax.tools.ToolProvider;
import java.awt.GraphicsEnvironment;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class Main {
    private Main() {}
    public static void main(String[] args) {
        try { command(args); }
        catch(Exception error) {
            System.err.println("错误："+ZhCn.message(error.getMessage()));
            if(Boolean.getBoolean("shapeflux.debug"))error.printStackTrace();
            System.exit(1);
        }
    }
    private static void command(String[] args) throws Exception {
        String command=args.length==0?"gui":args[0];
        if(command.equals("__worker")){Benchmark.worker(args);return;}
        if(command.equals("__matmul_worker")){MatmulBenchmark.worker(args);return;}
        if(command.equals("__dataset_worker")){DatasetBenchmark.worker(args);return;}
        if(command.equals("help") || command.equals("--help")){help();return;}
        if(command.equals("gui")) {
            if(args.length>1)throw new IllegalArgumentException("gui 命令不接受额外参数");
            if(GraphicsEnvironment.isHeadless())throw new IllegalStateException("当前没有图形桌面，请使用 demo、run、bench 或 verify 命令。");
            Dashboard.open();return;
        }
        if(command.equals("render-ui")) {
            if(args.length<2 || args.length>3)throw new IllegalArgumentException("render-ui 需要 PNG 路径，可选标签序号 0～6");
            Dashboard.snapshot(Path.of(args[1]),args.length==3?Integer.parseInt(args[2]):0);return;
        }
        Map<String,String> options=options(args,Set.of("quick","ablate"));
        switch(command) {
            case "trace" -> {
                allowedEngine(options,Set.of("input","output","profile"));
                if(!options.containsKey("input"))throw new IllegalArgumentException("trace需要--input形状、请求或批次CSV");
                System.out.println(TraceAnalyzer.analyze(Path.of(options.get("input")),output(options,"trace"),config(options,EngineConfig.defaults())).toAbsolutePath());
            }
            case "browse" -> {
                allowed(options,Set.of("input","output"));Path out=Path.of(options.getOrDefault("output","results/experiment-index.html"));
                System.out.println(ExperimentIndex.writeHtml(Path.of(options.getOrDefault("input","results")),out).toAbsolutePath());
            }
            case "matmul" -> {
                allowed(options,Set.of("rows","inner","cols","forks","warmup","measure","seed","guard","input-a","input-b","output"));
                String guard=options.getOrDefault("guard","family");if(!Set.of("family","exact").contains(guard))throw new IllegalArgumentException("guard必须为family或exact");
                if(options.containsKey("input-a"))for(String key:Set.of("rows","inner","cols"))if(options.containsKey(key))throw new IllegalArgumentException("导入矩阵时形状由CSV确定，不同时设置--"+key);
                var settings=new MatmulBenchmark.Settings(integer(options,"rows",64),integer(options,"inner",96),integer(options,"cols",48),integer(options,"forks",3),integer(options,"warmup",3),integer(options,"measure",7),Long.parseLong(options.getOrDefault("seed","20261027")),guard.equals("exact"));
                Path report=MatmulBenchmark.launch(output(options,"matmul"),settings,options.containsKey("input-a")?Path.of(options.get("input-a")):null,options.containsKey("input-b")?Path.of(options.get("input-b")):null,u->System.out.println(u.text()));
                System.out.println("矩阵乘法实验完成："+report.toAbsolutePath());
            }
            case "doctor" -> {
                allowed(options,Set.of("output"));
                Map<String,Object> report=new LinkedHashMap<>();
                report.put("application","ShapeFlux-J 2.4");report.put("java_version",System.getProperty("java.version"));report.put("java_home",System.getProperty("java.home"));
                report.put("os",System.getProperty("os.name"));report.put("arch",System.getProperty("os.arch"));report.put("processors",Runtime.getRuntime().availableProcessors());
                report.put("system_compiler",ToolProvider.getSystemJavaCompiler()!=null);report.put("desktop_display",!GraphicsEnvironment.isHeadless());
                report.put("backend","Java CPU / float64");String json=Json.write(report)+"\n";System.out.print(json);
                if(options.containsKey("output"))Files.writeString(Path.of(options.get("output")),json,StandardCharsets.UTF_8);
                if(ToolProvider.getSystemJavaCompiler()==null)throw new IllegalStateException("请安装完整 JDK 17 或更新版本，以启用编译功能");
            }
            case "verify" -> {allowed(options,Set.of("output"));ValidationSuite.run(output(options,"verification"));}
            case "dataset" -> {
                allowedEngine(options,Set.of("dataset","input","label","batches","max-rows","forks","modes","timeout-seconds","file","output","profile"));
                if(options.containsKey("dataset") && options.containsKey("input"))throw new IllegalArgumentException("--dataset 与 --input 请择一使用");
                BundledDatasets.Profile bundled=BundledDatasets.get(options.getOrDefault("dataset","covertype"));
                DatasetCsv.Label label;
                try{label=DatasetCsv.Label.valueOf(options.getOrDefault("label",options.containsKey("input")?"last":bundled.label().name()).toUpperCase(Locale.ROOT));}
                catch(IllegalArgumentException e){throw new IllegalArgumentException("label 必须为 none（无标签）、first（首列标签）或 last（末列标签）",e);}
                DatasetBenchmark.Settings settings=new DatasetBenchmark.Settings(Path.of(options.getOrDefault("input",bundled.input().toString())),label,
                    options.getOrDefault("batches","4097,4352,4608,4864,5120,5376,5632,5888,6144,6400,6656,8192"),Long.parseLong(options.getOrDefault("max-rows","0")),integer(options,"forks",1),integer(options,"timeout-seconds",1800),options.getOrDefault("modes","all"),config(options,EngineConfig.defaults()));
                Path report=DatasetBenchmark.launch(output(options,"dataset"),settings,readDsl(options,Examples.RMS_NORM),System.out::println);
                System.out.println("真实数据集测试已完成，报告："+report.toAbsolutePath());
            }
            case "report" -> {
                allowed(options,Set.of("input","output"));
                if(!options.containsKey("input"))throw new IllegalArgumentException("report 命令需要 --input <summary.csv>");
                Path input=Path.of(options.get("input")),out=options.containsKey("output")?Path.of(options.get("output")):input.toAbsolutePath().getParent();
                System.out.println("已保存："+ReportWriter.generate(input,out).toAbsolutePath());
            }
            case "project" -> {
                allowedEngine(options,Set.of("file","mode","rows","cols","workload","output","profile"));
                String dsl=readDsl(options,Examples.ACTIVATION);Strategy mode=Strategy.valueOf(options.getOrDefault("mode","ADAPTIVE").toUpperCase(Locale.ROOT));
                ProjectFile.Project project=new ProjectFile.Project(dsl,mode,config(options,EngineConfig.defaults()),integer(options,"rows",48),integer(options,"cols",128),options.getOrDefault("workload","hotset"));
                Path out=Path.of(options.getOrDefault("output","shapeflux.sfxproj"));ProjectFile.save(out,project);System.out.println("已保存："+out.toAbsolutePath());
            }
            case "demo" -> {
                allowed(options,Set.of("output"));
                run(Examples.CSE,Strategy.ADAPTIVE,EngineConfig.demo(),Workloads.inputs(Workloads.create("hotset",true),20261027),output(options,"demo"),true);
                System.out.println("演示配置的预测请求预算为 16384；默认值为 256。候选被拒绝也是有效结果。");
            }
            case "run","inspect" -> {
                allowedEngine(options,Set.of("project","file","mode","rows","cols","trace","input","bias","output","profile","seed"));
                ProjectFile.Project project=options.containsKey("project")?ProjectFile.load(Path.of(options.get("project"))):null;
                String dsl=readDsl(options,project==null?Examples.ACTIVATION:project.dsl());
                Strategy mode=Strategy.valueOf(options.getOrDefault("mode",project==null?"ADAPTIVE":project.strategy().name()).toUpperCase(Locale.ROOT));
                EngineConfig base=project==null?EngineConfig.defaults():project.config();List<TensorInput> inputs;
                if(options.containsKey("input")) {
                    for(String incompatible:Set.of("trace","rows","cols"))if(options.containsKey(incompatible))throw new IllegalArgumentException("--input 不能与以下参数同时使用：--"+incompatible);
                    inputs=List.of(TensorCsv.read(Path.of(options.get("input")),options.containsKey("bias")?Path.of(options.get("bias")):null));
                } else {
                    if(options.containsKey("bias"))throw new IllegalArgumentException("--bias 需要同时指定 --input");
                    List<Workloads.Shape> shapes=options.containsKey("trace")?Workloads.fromCsv(Path.of(options.get("trace"))):List.of(new Workloads.Shape(integer(options,"rows",project==null?48:project.rows()),integer(options,"cols",project==null?128:project.cols())));
                    inputs=Workloads.inputs(shapes,Long.parseLong(options.getOrDefault("seed","20261027")));
                }
                run(dsl,mode,config(options,base),inputs,output(options,"run"),command.equals("inspect"));
            }
            case "bench" -> {
                allowedEngine(options,Set.of("quick","ablate","forks","workload","output","file","trace","warmup","measure","seed","modes","timeout-seconds","profile"));
                boolean quick=options.containsKey("quick");BenchmarkSettings defaults=BenchmarkSettings.defaults(quick,1);
                BenchmarkSettings settings=new BenchmarkSettings(quick,integer(options,"forks",1),integer(options,"warmup",defaults.warmup()),integer(options,"measure",defaults.measure()),Long.parseLong(options.getOrDefault("seed","20261027")),integer(options,"timeout-seconds",1800),options.getOrDefault("modes","all"),config(options,EngineConfig.defaults()));
                Benchmark.launch(output(options,"benchmark"),settings,options.containsKey("ablate"),options.getOrDefault("workload","hotset"),options.containsKey("file")?Path.of(options.get("file")):null,options.containsKey("trace")?Path.of(options.get("trace")):null);
            }
            default -> throw new IllegalArgumentException("未知命令："+command+"。请使用 help 查看说明。");
        }
    }
    private static Path output(Map<String,String> options,String prefix){return Path.of(options.getOrDefault("output","results/"+prefix+"-"+System.currentTimeMillis()));}
    private static int integer(Map<String,String> options,String key,int fallback){return options.containsKey(key)?Integer.parseInt(options.get(key)):fallback;}
    private static String readDsl(Map<String,String> options,String fallback) throws Exception {return options.containsKey("file")?Files.readString(Path.of(options.get("file")),StandardCharsets.UTF_8):fallback;}
    private static EngineConfig config(Map<String,String> options,EngineConfig base) {
        if(options.containsKey("profile")) {
            String profile=options.get("profile");
            if(!Set.of("conservative","demo").contains(profile))throw new IllegalArgumentException("profile 必须为 conservative（稳健研究配置）或 demo（演示配置）");
            base=profile.equals("demo")?EngineConfig.demo():EngineConfig.defaults();
        }
        return EngineOptions.parse(options,base);
    }
    private static Map<String,String> options(String[] args,Set<String> flags) {
        Map<String,String> result=new LinkedHashMap<>();
        for(int i=1;i<args.length;i++) {
            if(!args[i].startsWith("--"))throw new IllegalArgumentException("需要 --参数，实际输入："+args[i]);
            String key=args[i].substring(2);if(result.containsKey(key))throw new IllegalArgumentException("重复参数："+key);
            if(flags.contains(key))result.put(key,"true");
            else {if(++i>=args.length || args[i].startsWith("--"))throw new IllegalArgumentException("参数缺少值：--"+key);result.put(key,args[i]);}
        }
        return result;
    }
    private static void allowed(Map<String,String> options,Set<String> keys){for(String key:options.keySet())if(!keys.contains(key))throw new IllegalArgumentException("未知参数：--"+key);}
    private static void allowedEngine(Map<String,String> options,Set<String> keys){Set<String> all=new HashSet<>(keys);all.addAll(EngineOptions.KEYS);allowed(options,all);}
    private static void run(String dsl,Strategy strategy,EngineConfig config,List<TensorInput> inputs,Path output,boolean inspect) throws Exception {
        if(Files.exists(output.resolve("requests.csv")))throw new IllegalArgumentException("运行结果已存在，请选择新的输出目录");
        Program program=Parser.parse(dsl);Engine engine=new Engine(program,strategy,config);List<Engine.Execution> runs=new ArrayList<>();
        for(TensorInput input:inputs) {
            Engine.Execution execution=engine.execute(input);runs.add(execution);
            if(!Numerics.close(Interpreter.run(program,input),execution.output()))throw new IllegalStateException("数值结果未通过参考校验，输入形状："+input.shape());
        }
        RunExporter.save(output,dsl,program,engine,inputs,runs);System.out.println("参考数值校验：通过\n"+ZhCn.stats(engine.stats()));
        if(inspect){System.out.println(ZhCn.ir(engine.program()));for(Engine.Event event:engine.events())System.out.println(ZhCn.event(event));}
        System.out.println("已保存："+output.toAbsolutePath());
        if(engine.stats().compileFailures()>0 || engine.stats().runtimeFailures()>0)System.err.println("编译器或内核出现异常，输出已使用经过验证的回退路径。详情请查看运行事件日志。");
    }
    private static void help() {
        System.out.println("""
            ShapeFlux-J 2.4 | JDK 17+ | 本地 Java CPU 动态算子编译系统（中文版）
            可用命令：
              gui                    打开中文桌面实验台（默认）
              doctor                 检查 Java、系统编译器及运行环境
              trace --input CSV      分析实测请求轨迹和复用窗口
              browse --input results --output results/experiment-index.html
              matmul --rows 64 --inner 96 --cols 48 --forks 5
                --guard family|exact --warmup 3 --measure 7
                --input-a A.csv --input-b B.csv  使用实际矩阵替代生成输入
              verify --output DIR    运行随包编译器正确性自检
              demo --output DIR      演示自适应编译与运行轨迹
              run --file FILE        执行算子表达式程序
              inspect --project FILE 运行已保存的工程并显示中文编译事件
              project --file FILE --output project.sfxproj
              bench --quick --workload all --output DIR
              report --input DIR/summary.csv
              dataset --output DIR   流式读取随包真实数据，比较五种策略

            dataset：默认完整遍历真实数据；--max-rows 65536 可快速测试
              --dataset covertype|kdd99|poker  选择随包数据集，默认 covertype
              --input DATA.csv.gz --label none|first|last
              --batches 4097,4352,4608,8192 --modes all --forks 1
              --file program.sfx --timeout-seconds 1800

            run/inspect: --mode GENERIC|EXACT|BUCKET|ADAPTIVE|INTERPRETED
              --rows N --cols D --trace shapes.csv --seed 20261027
              --input matrix.csv [--bias bias.csv]   导入真实数值数据
            bench: --forks 3 --warmup 8 --measure 5 --ablate
              --modes GENERIC,BUCKET,ADAPTIVE --timeout-seconds 1800
              --workload hotset|jitter|shift|longtail|all --file program.sfx
            引擎参数（用于 run、inspect、project、bench）：
              --cache 8 --hot 4 --budget-ms 500 --horizon 256
              --recheck 32 --probe-rounds 7 --trace-events on|off
              --reuse-window 128 --row-bucket 0 --col-bucket 0
              row-bucket=0使用二次幂行区间，col-bucket=0固定列宽；正数指定区间跨度
              --profile conservative|demo
            每组基准使用独立工作进程和固定输入，完成后生成中文报告。
            每次运行请使用新的输出目录，程序会保留已有实验结果。
            """);
    }
}
