package io.shapeflux.experiment;

import io.shapeflux.io.*;
import io.shapeflux.ir.*;
import io.shapeflux.runtime.*;
import io.shapeflux.i18n.ZhCn;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** True numeric dataset benchmark: one streaming pass in a fresh JVM per strategy/fork. */
public final class DatasetBenchmark {
    private DatasetBenchmark() {}
    public record Settings(Path input,DatasetCsv.Label label,String batches,long maxRows,int forks,int timeoutSeconds,String modes,EngineConfig engine) {
        public Settings {
            Objects.requireNonNull(input);Objects.requireNonNull(label);Objects.requireNonNull(batches);Objects.requireNonNull(engine);
            int[] sizes=DatasetCsv.sizes(batches);
            if(sizes.length<1 || sizes.length>256 || Arrays.stream(sizes).anyMatch(n->n<1 || n>8_000_000) || maxRows<0 || forks<1 || forks>10 || timeoutSeconds<1 || timeoutSeconds>86400)
                throw new IllegalArgumentException("真实数据集测试参数无效");
            strategies(modes);
        }
        public void save(Path file) throws IOException {
            Properties p=new Properties();p.setProperty("input",input.toAbsolutePath().toString());p.setProperty("label",label.name());p.setProperty("batches",batches);p.setProperty("maxRows",Long.toString(maxRows));
            p.setProperty("forks",Integer.toString(forks));p.setProperty("timeoutSeconds",Integer.toString(timeoutSeconds));p.setProperty("modes",modes);p.setProperty("engine",EngineOptions.encode(engine));
            try(Writer writer=Files.newBufferedWriter(file,StandardCharsets.UTF_8)){p.store(writer,"ShapeFlux-J real dataset settings");}
        }
        public static Settings load(Path file) throws IOException {
            Properties p=new Properties();try(Reader reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)){p.load(reader);}
            return new Settings(Path.of(p.getProperty("input")),DatasetCsv.Label.valueOf(p.getProperty("label")),p.getProperty("batches"),Long.parseLong(p.getProperty("maxRows")),Integer.parseInt(p.getProperty("forks")),Integer.parseInt(p.getProperty("timeoutSeconds")),p.getProperty("modes"),EngineOptions.decode(p.getProperty("engine")));
        }
    }
    public record Outcome(String strategy,int fork,long rows,int features,int batches,double engineMs,double computeMs,double readMs,double referenceMs,double elapsedMs,
                          double p50Us,double p95Us,long compilations,long hits,long misses,long compileFailures,long runtimeFailures,long fallbacks,double compileMs,boolean correct,double maxError,String inputValuesSha256) {
        public static String header(){return "strategy,fork,rows,features,batches,engine_ms,compute_ms,read_ms,reference_ms,elapsed_ms,request_p50_us,request_p95_us,compilations,hits,misses,compile_failures,runtime_failures,fallbacks,compile_ms,correct,max_abs_error,input_values_sha256";}
        public String csv(){return String.join(",",List.of(strategy,fork,rows,features,batches,engineMs,computeMs,readMs,referenceMs,elapsedMs,p50Us,p95Us,compilations,hits,misses,compileFailures,runtimeFailures,fallbacks,compileMs,correct,maxError,inputValuesSha256).stream().map(Object::toString).toList());}
        public boolean healthy(){return correct && compileFailures==0 && runtimeFailures==0 && fallbacks==0;}
    }
    public static List<Strategy> strategies(String modes) {
        if("all".equalsIgnoreCase(modes))return List.of(Strategy.values());
        if(modes==null)throw new IllegalArgumentException("必须指定数据集测试策略");
        List<Strategy> selected=new ArrayList<>();
        for(String token:modes.split(",",-1)) {
            Strategy strategy;
            try{strategy=Strategy.valueOf(token.strip().toUpperCase(Locale.ROOT));}catch(IllegalArgumentException e){throw new IllegalArgumentException("未知数据集测试策略："+token,e);}
            if(selected.contains(strategy))throw new IllegalArgumentException("重复的数据集测试策略："+token);selected.add(strategy);
        }
        return List.copyOf(selected);
    }
    public static Path launch(Path output,Settings settings,String dsl,Consumer<String> progress) throws Exception {return launch(output,settings,dsl,progress,u->{});}
    public static Path launch(Path output,Settings settings,String dsl,Consumer<String> progress,Consumer<ProgressIO.Update> steps) throws Exception {
        Parser.parse(dsl);List<Strategy> selected=strategies(settings.modes());
        if(!Files.isRegularFile(settings.input()))throw new IllegalArgumentException("找不到数据集文件："+settings.input());
        if(Files.exists(output))throw new IllegalArgumentException("数据集测试目录已存在，请选择新的输出目录");
        if(output.toAbsolutePath().getParent()!=null)Files.createDirectories(output.toAbsolutePath().getParent());Files.createDirectory(output);
        settings.save(output.resolve("settings.properties"));Files.writeString(output.resolve("program.sfx"),dsl,StandardCharsets.UTF_8);
        String digest=sha(settings.input());
        Map<String,Object> metadata=new LinkedHashMap<>();metadata.put("format","shapeflux-real-dataset-1");metadata.put("state","RUNNING");metadata.put("created_at_utc",Instant.now().toString());
        metadata.put("input",settings.input().toString());metadata.put("dataset_title",datasetName(settings.input()));metadata.put("source_sha256",digest);metadata.put("program_sha256",sha(output.resolve("program.sfx")));metadata.put("application_sha256",applicationHash());
        metadata.put("settings",Map.of("batches",settings.batches(),"max_rows",settings.maxRows(),"label",settings.label(),"forks",settings.forks(),"modes",selected));
        metadata.put("java_version",System.getProperty("java.version"));metadata.put("os",System.getProperty("os.name"));metadata.put("worker_vm_options",List.of("-Xmx768m","-Djava.awt.headless=true"));
        metadata.put("source_note","Each worker reopens the same source and verifies its file hash before and after the run. Inputs are streamed, not retained as a full snapshot.");
        metadata.put("completed_workers",0);save(output,metadata);
        StringBuilder csv=new StringBuilder(Outcome.header()+"\n");int complete=0;List<Map<String,String>> outcomes=new ArrayList<>();
        String valuesDigest=null;
        try {
            for(int fork=0;fork<settings.forks();fork++) {
                List<Strategy> order=new ArrayList<>(selected);Collections.rotate(order,fork);
                for(Strategy strategy:order) {
                    KernelProbe.interrupted();String title="正在测试真实数据："+ZhCn.name(strategy)+"（第 "+(fork+1)+" 次独立进程）";progress.accept(title);
                    Path directory=output.resolve(strategy+"-f"+fork);Files.createDirectory(directory);
                    String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
                    Process process=new ProcessBuilder(java,"-Xmx768m","-Djava.awt.headless=true","-cp",System.getProperty("java.class.path"),"io.shapeflux.Main","__dataset_worker",output.toAbsolutePath().toString(),strategy.name(),Integer.toString(fork),digest)
                        .redirectErrorStream(true).redirectOutput(directory.resolve("worker.log").toFile()).start();
                    try {
                        ProgressIO.await(process,directory,settings.timeoutSeconds(),ZhCn.name(strategy),complete+1,settings.forks()*selected.size(),steps);
                        if(process.exitValue()!=0)throw new IllegalStateException("数据集工作进程执行失败，请查看 "+directory.resolve("worker.log"));
                    } finally {if(process.isAlive()){process.destroyForcibly();try{process.waitFor(5,TimeUnit.SECONDS);}catch(InterruptedException e){Thread.currentThread().interrupt();}}}
                    List<String> lines=Files.readAllLines(directory.resolve("summary.csv"),StandardCharsets.UTF_8);
                    List<String> keys=ReportWriter.csvLine(lines.get(0)),values=ReportWriter.csvLine(lines.get(1));Map<String,String> row=new LinkedHashMap<>();for(int i=0;i<keys.size();i++)row.put(keys.get(i),values.get(i));
                    if(valuesDigest==null)valuesDigest=row.get("input_values_sha256");
                    else if(!valuesDigest.equals(row.get("input_values_sha256")))throw new IllegalStateException("各策略读取的真实特征不一致，停止汇总");
                    outcomes.add(row);csv.append(lines.get(1)).append('\n');Files.writeString(output.resolve("summary.csv"),csv,StandardCharsets.UTF_8);
                    metadata.put("completed_workers",++complete);save(output,metadata);
                }
            }
            writeReport(output,settings,outcomes);metadata.put("input_values_sha256",valuesDigest);metadata.put("state","COMPLETE");
        }catch(Exception e){metadata.put("state",e instanceof InterruptedException || e instanceof CancellationException?"CANCELLED":"FAILED");metadata.put("error",e.toString());throw e;}
        finally {boolean interrupted=Thread.interrupted();try{metadata.put("finished_at_utc",Instant.now().toString());save(output,metadata);}finally{if(interrupted)Thread.currentThread().interrupt();}}
        return output.resolve("report.html");
    }
    public static Outcome run(Path directory,Settings settings,Strategy strategy,int fork,String dsl) throws Exception {
        Files.createDirectories(directory);SystemMetrics noise=SystemMetrics.begin();ProgressIO.Tracker progress=new ProgressIO.Tracker(directory);
        long knownTotal=knownRows(settings);progress.step(0,knownTotal,0,0,"准备读取与编译");Program original=Parser.parse(dsl);Engine engine=new Engine(original,strategy,settings.engine());
        MessageDigest inputDigest=MessageDigest.getInstance("SHA-256");long engineNs=0,computeNs=0,readNs=0,referenceNs=0;int batches=0,columns=0;long rows=0;double maxError=0;boolean correct=true;
        List<Long> times=new ArrayList<>();double[] preview=new double[0];long start=System.nanoTime();
        try(DatasetCsv reader=new DatasetCsv(settings.input(),settings.label(),DatasetCsv.sizes(settings.batches()),settings.maxRows());
            BufferedWriter raw=Files.newBufferedWriter(directory.resolve("batches.csv"),StandardCharsets.UTF_8)) {
            raw.write("batch,start_row,end_row,rows,cols,selected,cache_hit,compute_us,total_us,correct,max_abs_error\n");
            for(;;) {
                KernelProbe.interrupted();long before=System.nanoTime();TensorInput input=reader.next();readNs+=System.nanoTime()-before;if(input==null)break;
                if(batches>=1_000_000)throw new IllegalArgumentException("数据集测试超过 100 万批次，请增大批次行数或限制总行数");
                updateValues(inputDigest,input.x());
                Engine.Execution execution=engine.execute(input);before=System.nanoTime();double[] expected=Interpreter.run(original,input);
                boolean ok=Numerics.close(expected,execution.output());double error=Numerics.maxError(expected,execution.output());referenceNs+=System.nanoTime()-before;
                correct &= ok;maxError=Math.max(maxError,error);engineNs+=execution.totalNanos();computeNs+=execution.computeNanos();times.add(execution.totalNanos());columns=input.cols();
                raw.write(String.format(Locale.ROOT,"%d,%d,%d,%d,%d,%s,%s,%.6f,%.6f,%s,%.12g%n",++batches,rows+1,rows+input.rows(),input.rows(),input.cols(),execution.selected(),execution.cacheHit(),execution.computeNanos()/1000.0,execution.totalNanos()/1000.0,ok,error));rows+=input.rows();
                preview=Arrays.copyOf(execution.output(),Math.min(16,execution.output().length));
                progress.step(rows,knownTotal,rows,batches,"读取、执行与校验");
                if(!ok)throw new IllegalStateException("真实数据第 "+batches+" 批数值校验未通过");
            }
        }
        long elapsed=System.nanoTime()-start;if(batches==0)throw new IllegalArgumentException("数据集没有有效数值行");
        Collections.sort(times);Engine.Stats s=engine.stats();
        Outcome result=new Outcome(strategy.name(),fork,rows,columns,batches,engineNs/1e6,computeNs/1e6,readNs/1e6,referenceNs/1e6,elapsed/1e6,
            times.get((int)Math.ceil(times.size()*.5)-1)/1000.0,times.get((int)Math.ceil(times.size()*.95)-1)/1000.0,s.compilations(),s.hits(),s.misses(),s.compileFailures(),s.runtimeFailures(),s.fallbacks(),s.compileNanos()/1e6,correct,maxError,HexFormat.of().formatHex(inputDigest.digest()));
        Files.writeString(directory.resolve("summary.csv"),Outcome.header()+"\n"+result.csv()+"\n",StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("run.json"),Json.write(Map.of("outcome",result,"stats",s,"last_output_preview",Arrays.stream(preview).boxed().toList()))+"\n",StandardCharsets.UTF_8);
        StringBuilder events=new StringBuilder("ShapeFlux-J 真实数据运行事件\n\n"+ZhCn.stats(s)+"\n");for(Engine.Event event:engine.events())events.append(ZhCn.event(event)).append('\n');Files.writeString(directory.resolve("运行事件.txt"),events,StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("optimized-ir.txt"),ZhCn.ir(engine.program()),StandardCharsets.UTF_8);
        Files.createDirectories(directory.resolve("generated"));int artifact=0;
        for(var kernel:engine.artifacts())Files.writeString(directory.resolve("generated/kernel-"+artifact+++".java.txt"),kernel.source(),StandardCharsets.UTF_8);
        noise.save(directory);progress.finish(rows,rows,rows,batches);return result;
    }
    public static void worker(String[] args) throws Exception {
        if(args.length!=5)throw new IllegalArgumentException("数据集工作进程参数无效");Path root=Path.of(args[1]);
        // The fifth user argument is the immutable compressed/plain file digest.
        Settings settings=Settings.load(root.resolve("settings.properties"));String expected=args[4];
        if(!sha(settings.input()).equals(expected))throw new IllegalStateException("数据集文件在测试前发生变化");
        Strategy strategy=Strategy.valueOf(args[2]);int fork=Integer.parseInt(args[3]);
        Outcome outcome=run(root.resolve(strategy+"-f"+fork),settings,strategy,fork,Files.readString(root.resolve("program.sfx"),StandardCharsets.UTF_8));
        if(!sha(settings.input()).equals(expected))throw new IllegalStateException("数据集文件在测试期间发生变化");
        System.out.println("实际处理 "+outcome.rows()+" 行、"+outcome.batches()+" 批；数值校验："+(outcome.correct()?"通过":"未通过"));
        if(!outcome.healthy())throw new IllegalStateException("数据集测试出现编译失败或回退，请检查统计");
    }
    private static void writeReport(Path output,Settings settings,List<Map<String,String>> outcomes) throws IOException {
        String note="每种策略的每次重复使用独立 Java 进程，从文件开头顺序读取同一批真实数值。批次顺序循环为 "+settings.batches()+" 行，偏置为零。每个工作进程只保留当前批次，逐批与参考解释器校验。";
        String timing="单次文件遍历包含首次编译请求，没有额外预热；中位数和第95百分位覆盖本次全部批次。引擎耗时包含编译、内核验证、收益探测和复核，排除文件读取及外部参考校验。遍历总耗时包含读取、特征摘要计算、引擎执行、参考校验与逐批记录，不含 Java 进程启动和最终报告生成。";
        StringBuilder table=new StringBuilder("<table><thead><tr><th>执行策略</th><th>独立进程</th><th>实际行数</th><th>特征数</th><th>批次数</th><th>引擎总计/毫秒</th><th>计算总计/毫秒</th><th>遍历总计/毫秒</th><th>请求中位数/微秒</th><th>请求第95百分位/微秒</th><th>编译次数</th><th>缓存命中率</th><th>校验</th></tr></thead><tbody>");
        StringBuilder md=new StringBuilder("# ShapeFlux-J 真实数据集测试报告\n\n"+note+"\n\n"+timing+"\n\n| 策略 | 独立进程 | 行数 | 特征 | 批次 | 引擎/毫秒 | 计算/毫秒 | 遍历/毫秒 | 请求中位数/微秒 | 请求第95百分位/微秒 | 编译次数 | 缓存命中率 | 校验 |\n|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---|\n");
        for(Map<String,String> r:outcomes) {
            long hits=Long.parseLong(r.get("hits")),misses=Long.parseLong(r.get("misses"));String hit=String.format(Locale.ROOT,"%.1f%%",100.0*hits/Math.max(1,hits+misses));
            List<String> cells=List.of(ZhCn.name(r.get("strategy")),Integer.toString(Integer.parseInt(r.get("fork"))+1),r.get("rows"),r.get("features"),r.get("batches"),number(r,"engine_ms"),number(r,"compute_ms"),number(r,"elapsed_ms"),number(r,"request_p50_us"),number(r,"request_p95_us"),r.get("compilations"),hit,"true".equals(r.get("correct"))?"通过":"未通过");
            table.append("<tr>");for(String cell:cells)table.append("<td>").append(ReportWriter.html(cell)).append("</td>");table.append("</tr>");md.append("| ").append(String.join(" | ",cells)).append(" |\n");
        }
        table.append("</tbody></table>");String file=ReportWriter.html(datasetName(settings.input()));
        String html="<!doctype html><html lang=\"zh-CN\"><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>真实数据集测试报告</title><style>body{font:15px/1.8 system-ui,'Microsoft YaHei',sans-serif;background:#0b1120;color:#eaf1ff;max-width:1400px;margin:40px auto;padding:0 24px}h1{color:#42dfc0}table{border-collapse:collapse;font-size:13px;width:100%}td,th{padding:12px;border-bottom:1px solid #31425f;text-align:left}th{color:#42dfc0}.scroll{overflow:auto}p{color:#bdcbe0}</style><h1>ShapeFlux-J 真实数据集测试报告</h1><p>数据文件："+file+"；完成 "+outcomes.size()+" 组独立进程测试。</p><p>"+ReportWriter.html(note)+"</p><div class=\"scroll\">"+table+"</div><p>"+ReportWriter.html(timing)+"</p><p>原始统计：summary.csv；逐批数据：各策略目录的 batches.csv；环境与文件摘要：metadata.json；中文事件：各策略目录的运行事件.txt。单次独立进程结果用于探索，不提供跨进程方差估计或稳定加速保证。</p></html>";
        Files.writeString(output.resolve("report.html"),html,StandardCharsets.UTF_8);Files.writeString(output.resolve("report.md"),md,StandardCharsets.UTF_8);
        StatisticsReport.append(output.resolve("report.html"),StatisticsReport.generate(output,outcomes,"strategy","engine_ms","compute_ms","elapsed_ms","request_p95_us"));
        StringBuilder readable=new StringBuilder("真实数据集测试已完成\n数据集："+datasetName(settings.input())+"\n\n"+note+"\n\n");
        for(Map<String,String> r:outcomes)readable.append(ZhCn.name(r.get("strategy"))).append("｜第 ").append(Integer.parseInt(r.get("fork"))+1).append(" 次独立进程\n实际处理 ").append(r.get("rows")).append(" 行 × ").append(r.get("features")).append(" 个特征，").append(r.get("batches")).append(" 批\n引擎总计：").append(number(r,"engine_ms")).append(" 毫秒；计算总计：").append(number(r,"compute_ms")).append(" 毫秒\n编译次数：").append(r.get("compilations")).append("；数值校验：").append("true".equals(r.get("correct"))?"通过":"未通过").append("；最大绝对误差：").append(r.get("max_abs_error")).append("\n\n");
        readable.append(timing).append("\n\n完整表格请点击“查看报告”，或打开同目录 report.html。\n");Files.writeString(output.resolve("概览.txt"),readable,StandardCharsets.UTF_8);
    }
    private static long knownRows(Settings s){long actual=BundledDatasets.all().stream().filter(p->p.input().toAbsolutePath().normalize().equals(s.input().toAbsolutePath().normalize())).mapToLong(BundledDatasets.Profile::rows).findFirst().orElse(0);return s.maxRows()>0?(actual>0?Math.min(actual,s.maxRows()):s.maxRows()):actual;}
    private static void updateValues(MessageDigest digest,double[] values) {
        byte[] buffer=new byte[8192];int offset=0;
        for(double value:values){long bits=Double.doubleToRawLongBits(value);for(int b=7;b>=0;b--)buffer[offset++]=(byte)(bits>>>(b*8));if(offset==buffer.length){digest.update(buffer);offset=0;}}
        if(offset>0)digest.update(buffer,0,offset);
    }
    private static String number(Map<String,String> row,String field){return String.format(Locale.ROOT,"%.3f",Double.parseDouble(row.get(field)));}
    private static String datasetName(Path file){return BundledDatasets.all().stream().filter(p->p.input().toAbsolutePath().normalize().equals(file.toAbsolutePath().normalize())).map(BundledDatasets.Profile::title).findFirst().orElse(file.getFileName().toString());}
    private static void save(Path output,Map<String,Object> metadata) throws IOException {Files.writeString(output.resolve("metadata.json"),Json.write(metadata)+"\n",StandardCharsets.UTF_8);}
    private static String sha(Path path) throws Exception {MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(path)){byte[] buffer=new byte[65536];for(int n;(n=in.read(buffer))!=-1;){KernelProbe.interrupted();md.update(buffer,0,n);}}return HexFormat.of().formatHex(md.digest());}
    private static String applicationHash(){try{Path path=Path.of(DatasetBenchmark.class.getProtectionDomain().getCodeSource().getLocation().toURI());return Files.isRegularFile(path)?sha(path):"development-class-directory";}catch(Exception e){return "unavailable";}}
}
