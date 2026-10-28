package io.shapeflux.experiment;

import io.shapeflux.io.*;
import io.shapeflux.runtime.*;
import io.shapeflux.compiler.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;

/** Isolated, repeatable experiment for the precisely defined row-major CPU matrix product. */
public final class MatmulBenchmark {
    private static final String HEADER="mode,fork,rows,inner,cols,compile_ms,cold_request_ms,median_compute_us,p95_compute_us,correct,max_abs_error,compilations";
    private MatmulBenchmark() {}
    public record Settings(int n,int k,int m,int forks,int warmup,int measure,long seed,boolean exact) {
        public Settings {if(n<1 || k<1 || m<1 || (long)n*k>8_000_000 || (long)k*m>8_000_000 || (long)n*m>8_000_000 || (long)n*k*m>500_000_000 || forks<1 || forks>10 || warmup<0 || warmup>1000 || measure<1 || measure>1000)throw new IllegalArgumentException("Invalid matmul benchmark settings");}
    }
    public static Path launch(Path out,Settings s,Path aFile,Path bFile,Consumer<ProgressIO.Update> progress) throws Exception {
        if((aFile==null)!=(bFile==null))throw new IllegalArgumentException("--input-a and --input-b are required together");
        MatrixInput input;
        if(aFile==null)input=MatrixInput.random(s.n(),s.k(),s.m(),s.seed());
        else {TensorInput a=TensorCsv.read(aFile,null),b=TensorCsv.read(bFile,null);if(a.cols()!=b.rows())throw new IllegalArgumentException("A columns must equal B rows");input=new MatrixInput(a.rows(),a.cols(),b.cols(),a.x(),b.x());}
        if(Files.exists(out))throw new IllegalArgumentException("Matmul output already exists");Files.createDirectories(out.resolve("inputs"));
        TensorCsv.write(out.resolve("inputs/a.csv"),input.a(),input.n(),input.k());TensorCsv.write(out.resolve("inputs/b.csv"),input.b(),input.k(),input.m());
        Properties p=new Properties();p.setProperty("forks",Integer.toString(s.forks()));p.setProperty("warmup",Integer.toString(s.warmup()));p.setProperty("measure",Integer.toString(s.measure()));p.setProperty("exact",Boolean.toString(s.exact()));
        p.setProperty("a_sha256",sha(out.resolve("inputs/a.csv")));p.setProperty("b_sha256",sha(out.resolve("inputs/b.csv")));
        try(Writer w=Files.newBufferedWriter(out.resolve("settings.properties"),StandardCharsets.UTF_8)){p.store(w,"Matrix benchmark");}
        Map<String,Object> meta=new LinkedHashMap<>();meta.put("format","shapeflux-matmul-1");meta.put("state","RUNNING");meta.put("created_at_utc",Instant.now().toString());meta.put("java_version",System.getProperty("java.version"));meta.put("os",System.getProperty("os.name"));meta.put("settings",s);meta.put("actual_shape",List.of(input.n(),input.k(),input.m()));meta.put("application_sha256",applicationHash());meta.put("input_a_sha256",p.getProperty("a_sha256"));meta.put("input_b_sha256",p.getProperty("b_sha256"));meta.put("completed_workers",0);save(out,meta);
        StringBuilder summary=new StringBuilder(HEADER+"\n");List<Map<String,String>> rows=new ArrayList<>();int completed=0;
        try {
            for(int fork=0;fork<s.forks();fork++) {
                List<MatmulEngine.Mode> order=new ArrayList<>(List.of(MatmulEngine.Mode.values()));Collections.rotate(order,fork);
                for(var mode:order) {
                    KernelProbe.interrupted();Path directory=out.resolve(mode+"-f"+fork);Files.createDirectory(directory);
                    String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
                    Process process=new ProcessBuilder(java,"-Xmx768m","-Djava.awt.headless=true","-cp",System.getProperty("java.class.path"),"io.shapeflux.Main","__matmul_worker",out.toAbsolutePath().toString(),mode.name(),Integer.toString(fork)).redirectErrorStream(true).redirectOutput(directory.resolve("worker.log").toFile()).start();
                    try{ProgressIO.await(process,directory,1800,mode.name(),completed+1,3*s.forks(),progress);if(process.exitValue()!=0)throw new IllegalStateException("Matrix worker failed: "+directory.resolve("worker.log"));}finally{if(process.isAlive())process.destroyForcibly();}
                    List<String> lines=Files.readAllLines(directory.resolve("summary.csv"),StandardCharsets.UTF_8);summary.append(lines.get(1)).append('\n');Files.writeString(out.resolve("summary.csv"),summary,StandardCharsets.UTF_8);
                    List<String> h=ReportWriter.csvLine(lines.get(0)),v=ReportWriter.csvLine(lines.get(1));Map<String,String> row=new LinkedHashMap<>();for(int i=0;i<h.size();i++)row.put(h.get(i),v.get(i));rows.add(row);meta.put("completed_workers",++completed);save(out,meta);
                }
            }
            StringBuilder table=new StringBuilder("<table><tr><th>策略</th><th>fork</th><th>形状 N×K×M</th><th>冷请求/毫秒</th><th>计算中位数/微秒</th><th>P95/微秒</th><th>编译/毫秒</th><th>校验</th></tr>");
            for(var row:rows){table.append("<tr>");for(String v:List.of(row.get("mode"),row.get("fork"),row.get("rows")+"×"+row.get("inner")+"×"+row.get("cols"),row.get("cold_request_ms"),row.get("median_compute_us"),row.get("p95_compute_us"),row.get("compile_ms"),row.get("correct")))table.append("<td>").append(ReportWriter.html(v)).append("</td>");table.append("</tr>");}table.append("</table>");
            String html="<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><title>ShapeFlux-J 矩阵乘法实验</title><style>body{background:#0b1120;color:#eaf1ff;font:15px/1.8 system-ui,'Microsoft YaHei',sans-serif;margin:40px auto;max-width:1400px;padding:20px}h1,h2,a{color:#42dfc0}table{border-collapse:collapse;font-size:13px;width:100%}td,th{padding:9px;border-bottom:1px solid #31425f}.scroll{overflow:auto}</style><h1>CPU 矩阵乘法编译实验</h1><p>语义：行优先 A[N,K] × B[K,M] → C[N,M]，从+0按K递增累加，不转置、不含偏置或alpha/beta。REFERENCE为独立i,j,k参考；GENERIC为通用Java编译；BLOCKED为16×32×32分块CPU代码，按实际N/K/M执行并检查三轴守卫。</p><p>每种策略每次fork使用独立JVM和相同输入快照。冷请求包含编译及内核验证；计算时间排除编译、输出分配和外部参考校验。分位数覆盖单进程测量轮次，跨进程置信区间单独列出。</p><div class=\"scroll\">"+table+"</div></html>";
            Files.writeString(out.resolve("report.html"),html,StandardCharsets.UTF_8);StatisticsReport.append(out.resolve("report.html"),StatisticsReport.generate(out,rows,"mode","cold_request_ms","median_compute_us","p95_compute_us"));
            meta.put("state","COMPLETE");
        }catch(Exception e){meta.put("state",e instanceof java.util.concurrent.CancellationException || e instanceof InterruptedException?"CANCELLED":"FAILED");meta.put("error",e.toString());throw e;}
        finally{boolean interrupted=Thread.interrupted();try{meta.put("finished_at_utc",Instant.now().toString());save(out,meta);}finally{if(interrupted)Thread.currentThread().interrupt();}}
        return out.resolve("report.html");
    }
    public static void worker(String[] args) throws Exception {
        if(args.length!=4)throw new IllegalArgumentException("Invalid matrix worker arguments");Path out=Path.of(args[1]);var mode=MatmulEngine.Mode.valueOf(args[2]);int fork=Integer.parseInt(args[3]);Path directory=out.resolve(mode+"-f"+fork);
        Properties p=new Properties();try(Reader reader=Files.newBufferedReader(out.resolve("settings.properties"),StandardCharsets.UTF_8)){p.load(reader);}
        checkInputs(out,p);TensorInput a=TensorCsv.read(out.resolve("inputs/a.csv"),null),b=TensorCsv.read(out.resolve("inputs/b.csv"),null);MatrixInput input=new MatrixInput(a.rows(),a.cols(),b.cols(),a.x(),b.x());
        var engine=new MatmulEngine(mode,Boolean.parseBoolean(p.getProperty("exact")),8);int warmup=Integer.parseInt(p.getProperty("warmup")),measure=Integer.parseInt(p.getProperty("measure"));
        var noise=SystemMetrics.begin();var progress=new ProgressIO.Tracker(directory);int total=1+warmup+measure;progress.step(0,total,0,0,"准备编译");
        StringBuilder raw=new StringBuilder("phase,iteration,compute_us,total_us,correct,max_abs_error\n");List<Long> samples=new ArrayList<>();double cold=0,maxError=0;boolean correct=true;double[] last=new double[0];
        for(int i=0;i<total;i++) {
            KernelProbe.interrupted();String phase=i==0?"cold":i<=warmup?"warmup":"measure";progress.step(i,total,(long)i*input.n(),i,phase);
            var run=engine.execute(input);double[] expected=input.reference();boolean ok=Numerics.close(expected,run.output());double error=Numerics.maxError(expected,run.output());correct &=ok;maxError=Math.max(maxError,error);last=run.output();
            raw.append(phase).append(',').append(i).append(',').append(run.computeNanos()/1000.0).append(',').append(run.totalNanos()/1000.0).append(',').append(ok).append(',').append(error).append('\n');
            if(i==0)cold=run.totalNanos()/1e6;if(i>warmup)samples.add(run.computeNanos());if(!ok)throw new IllegalStateException("Matrix numerical validation failed");
        }
        checkInputs(out,p);Collections.sort(samples);double median=samples.get((int)Math.ceil(samples.size()*.5)-1)/1000.0,p95=samples.get((int)Math.ceil(samples.size()*.95)-1)/1000.0;
        String line=String.join(",",List.of(mode,fork,input.n(),input.k(),input.m(),engine.compileNanos()/1e6,cold,median,p95,correct,maxError,engine.compilations()).stream().map(Object::toString).toList());
        Files.writeString(directory.resolve("summary.csv"),HEADER+"\n"+line+"\n",StandardCharsets.UTF_8);Files.writeString(directory.resolve("raw_requests.csv"),raw,StandardCharsets.UTF_8);TensorCsv.write(directory.resolve("output.csv"),last,input.n(),input.m());
        Files.createDirectories(directory.resolve("generated"));int index=0;for(var artifact:engine.artifacts())Files.writeString(directory.resolve("generated/kernel-"+index+++".java.txt"),artifact.source(),StandardCharsets.UTF_8);
        noise.save(directory);progress.finish(total,total,(long)total*input.n(),total);
    }
    private static void checkInputs(Path root,Properties p)throws Exception{for(String name:List.of("a","b"))if(!sha(root.resolve("inputs/"+name+".csv")).equals(p.getProperty(name+"_sha256")))throw new IllegalStateException("Matrix input snapshot changed");}
    private static void save(Path out,Map<String,Object> meta)throws IOException{Files.writeString(out.resolve("metadata.json"),Json.write(meta)+"\n",StandardCharsets.UTF_8);}
    private static String sha(Path file)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));}
    private static String applicationHash(){try{Path p=Path.of(MatmulBenchmark.class.getProtectionDomain().getCodeSource().getLocation().toURI());return Files.isRegularFile(p)?sha(p):"development-class-directory";}catch(Exception e){return "unavailable";}}
}
