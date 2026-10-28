import io.shapeflux.experiment.*;
import io.shapeflux.io.*;
import io.shapeflux.runtime.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** java -cp dist/shapeflux-j.jar tools/ValidateCompletionRelease.java RESULTS */
class ValidateCompletionRelease {
    private static Path out,jar;
    private static final List<Map<String,Object>> checks=new ArrayList<>();
    public static void main(String[] args) throws Exception {
        out=Path.of(args.length==0?"results/completion-checks-"+System.currentTimeMillis():args[0]).toAbsolutePath();
        jar=Path.of("dist/shapeflux-j.jar").toAbsolutePath();if(Files.exists(out))throw new IllegalArgumentException("Use a fresh output directory");Files.createDirectories(out);
        try {
            command("two-axis",0,"run","--file","examples/composable-softmax.sfx","--trace","examples/traces/multiaxis.csv","--mode","BUCKET","--row-bucket","32","--col-bucket","32","--output",out.resolve("two-axis").toString());
            String stats=Files.readString(out.resolve("two-axis/stats.json"));expect(stats.contains("\"compileFailures\":0") && stats.contains("\"fallbacks\":0"),"composable softmax ran a healthy two-axis compiled kernel");
            command("actual-trace",0,"trace","--input","examples/traces/recorded-combined.csv","--row-bucket","1024","--col-bucket","32","--output",out.resolve("actual-trace").toString());
            String trace=Files.readString(out.resolve("actual-trace/metadata.json"));expect(trace.contains("\"request_count\":1163") && trace.contains("\"exact_shape_count\":39"),"all recorded batch shapes were analyzed");
            Path golden=out.resolve("matmul-golden");
            command("matrix-csv",0,"matmul","--input-a","examples/matmul-a.csv","--input-b","examples/matmul-b.csv","--forks","2","--warmup","1","--measure","3","--output",golden.toString());
            var rows=read(golden.resolve("summary.csv"));expect(rows.size()==6 && rows.stream().allMatch(r->r.get("correct").equals("true") && r.get("max_abs_error").equals("0.0")),"six independent matrix workers have exact hand-calculated output");
            for(String name:List.of("REFERENCE","GENERIC","BLOCKED"))expect(Arrays.equals(TensorCsv.read(golden.resolve(name+"-f0/output.csv"),null).x(),new double[]{58,64,139,154}),name+" hand-calculated output matrix");
            var confidence=read(golden.resolve("confidence.csv"));expect(confidence.size()==9 && confidence.stream().allMatch(x->x.get("forks").equals("2") && !x.get("ci95_low").isBlank()),"matrix confidence intervals use independent worker counts");
            expect(read(golden.resolve("noise.csv")).size()==6,"all matrix worker noise scopes were recorded");
            expect(Files.readString(golden.resolve("metadata.json")).contains("\"state\":\"COMPLETE\""),"matrix final state is complete");
            command("invalid-matrix-guard",1,"matmul","--guard","unknown","--output",out.resolve("bad-guard").toString());
            command("incomplete-matrix-pair",1,"matmul","--input-a","examples/matmul-a.csv","--output",out.resolve("bad-pair").toString());
            command("invalid-reuse-window",1,"run","--reuse-window","0","--output",out.resolve("bad-window").toString());
            command("invalid-granularity",1,"run","--col-bucket","-1","--output",out.resolve("bad-span").toString());
            command("preserve-matrix-output",1,"matmul","--output",golden.toString());
            command("browse",0,"browse","--input",out.toString(),"--output",out.resolve("index.html").toString());
            expect(Files.readString(out.resolve("index.html")).contains("matmul-golden/report.html"),"offline browser uses a portable local report link");
            AtomicInteger updates=new AtomicInteger();Path dataset=out.resolve("dataset-progress");
            DatasetBenchmark.launch(dataset,new DatasetBenchmark.Settings(Path.of("datasets/kdd99/prepared/quick-8192x122.csv"),DatasetCsv.Label.NONE,"1024,2048",8192,2,120,"GENERIC",EngineConfig.defaults()),"out=rowmean(square(x-rowmean(x)));",s->{},u->{updates.incrementAndGet();});
            expect(updates.get()>1 && Files.exists(dataset.resolve("GENERIC-f0/progress.properties")),"live dataset progress is emitted from real worker snapshots");
            expect(read(dataset.resolve("confidence.csv")).stream().allMatch(x->x.get("forks").equals("2")),"dataset confidence report has correct independent repeats");
            Path cancel=out.resolve("matrix-cancel");AtomicReference<Throwable> failure=new AtomicReference<>();
            Thread thread=new Thread(()->{try{MatmulBenchmark.launch(cancel,new MatmulBenchmark.Settings(128,256,128,3,0,1000,42,false),null,null,u->{});}catch(Throwable e){failure.set(e);}},"matrix-cancel-check");
            thread.start();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            while(!Files.exists(cancel.resolve("REFERENCE-f0/worker.log")) && thread.isAlive() && System.nanoTime()<deadline)Thread.sleep(20);
            thread.interrupt();thread.join(15000);
            expect(!thread.isAlive() && (failure.get() instanceof InterruptedException || failure.get() instanceof CancellationException),"matrix cancellation terminates the owned worker");
            expect(Files.readString(cancel.resolve("metadata.json")).contains("\"state\":\"CANCELLED\"") && !Files.exists(cancel.resolve("report.html")),"cancelled matrix run is never reported as complete");
            Files.writeString(out.resolve("checks.json"),Json.write(Map.of("passed",true,"check_count",checks.size(),"checks",checks,"note","Invalid arguments and cancellation deliberately exercise failure states."))+"\n");
            System.out.println("PASS: "+checks.size()+" feature release checks.");
        } catch(Exception | AssertionError e){Files.writeString(out.resolve("checks.json"),Json.write(Map.of("passed",false,"checks",checks,"error",e.toString())));throw e;}
    }
    private static List<Map<String,String>> read(Path p)throws Exception {
        List<String> lines=Files.readAllLines(p);List<String> h=ReportWriter.csvLine(lines.get(0));List<Map<String,String>> rows=new ArrayList<>();
        for(String line:lines.subList(1,lines.size())){if(line.isBlank())continue;var v=ReportWriter.csvLine(line);Map<String,String> row=new LinkedHashMap<>();for(int i=0;i<h.size();i++)row.put(h.get(i),v.get(i));rows.add(row);}return rows;
    }
    private static void command(String name,int expected,String...args)throws Exception {
        List<String> cmd=new ArrayList<>(List.of(java(),"-Djava.awt.headless=true","-jar",jar.toString()));cmd.addAll(List.of(args));
        Process p=new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(out.resolve(name+".log").toFile()).start();
        try{if(!p.waitFor(120,TimeUnit.SECONDS))throw new IllegalStateException("Timed out: "+name);expect(p.exitValue()==expected,name+" exit matches "+expected);}finally{if(p.isAlive())p.destroyForcibly();}
    }
    private static String java(){return Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();}
    private static void expect(boolean ok,String detail){checks.add(Map.of("passed",ok,"detail",detail));if(!ok)throw new AssertionError(detail);}
}
