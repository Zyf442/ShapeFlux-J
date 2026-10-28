import io.shapeflux.experiment.*;
import io.shapeflux.io.*;
import io.shapeflux.runtime.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** java -cp dist/shapeflux-j.jar tools/ValidateRelease.java [fresh-output-dir] */
class ValidateRelease {
    private static final List<Map<String,Object>> checks=new ArrayList<>();
    private static Path root,output,jar;
    public static void main(String[] args)throws Exception {
        root=Path.of("").toAbsolutePath();jar=root.resolve("dist/shapeflux-j.jar");
        output=args.length==0?root.resolve("results/release-checks-"+System.currentTimeMillis()):Path.of(args[0]).toAbsolutePath();
        if(Files.exists(output))throw new IllegalArgumentException("Use a fresh release-check directory");Files.createDirectories(output);
        try {
            command("doctor",0,"doctor","--output",output.resolve("doctor.json").toString());
            command("packaged-verification",0,"verify","--output",output.resolve("verify").toString());
            Path data=output.resolve("real-data");command("real-matrix",0,"run","--file","examples/golden.sfx","--input","examples/matrix.csv","--bias","examples/bias.csv","--mode","GENERIC","--output",data.toString());
            TensorInput matrix=TensorCsv.read(data.resolve("last-output.csv"),null);expect(Arrays.equals(matrix.x(),new double[]{0,0,7,2,0,5}),"hand-calculated CSV output");
            String stats=Files.readString(data.resolve("stats.json"));expect(stats.contains("\"runtimeFailures\":0") && stats.contains("\"compileFailures\":0") && stats.contains("\"fallbacks\":0"),"matrix ran a healthy compiled kernel");
            Path project=output.resolve("roundtrip.sfxproj");command("project-save",0,"project","--file","examples/rmsnorm.sfx","--cache","3","--hot","2","--budget-ms","0","--recheck","8","--output",project.toString());
            command("project-run",0,"inspect","--project",project.toString(),"--rows","3","--cols","7","--output",output.resolve("project-run").toString());
            Path benchmark=output.resolve("forked-benchmark");command("forked-benchmark",0,"bench","--quick","--modes","GENERIC,BUCKET","--forks","2","--warmup","0","--measure","1","--seed","42","--cache","1","--trace-events","off","--output",benchmark.toString());
            List<Map<String,String>> rows=ReportWriter.read(benchmark.resolve("summary.csv"));expect(rows.size()==4,"all selected modes and forks ran");
            expect(rows.stream().allMatch(r->r.get("correct").equals("true") && r.get("compile_failures").equals("0") && r.get("runtime_failures").equals("0")),"forked benchmark is healthy");
            command("report-regenerate",0,"report","--input",benchmark.resolve("summary.csv").toString(),"--output",output.resolve("regenerated-report").toString());
            command("invalid-argument",1,"run","--unknown","x");
            command("invalid-config",1,"run","--cache","0");
            command("preserve-results",1,"bench","--output",benchmark.toString());
            Path timeout=output.resolve("expected-timeout");command("worker-timeout",1,"bench","--quick","--warmup","0","--measure","1000","--modes","EXACT","--timeout-seconds","1","--output",timeout.toString());
            String timeoutState=Files.readString(timeout.resolve("metadata.json"));expect(timeoutState.contains("\"state\":\"FAILED\"") && timeoutState.contains("Worker timeout"),"timeout state and reason recorded");
            Path cancelled=output.resolve("expected-cancellation");AtomicReference<Throwable> failure=new AtomicReference<>();
            Thread worker=new Thread(()->{try{Benchmark.launch(cancelled,new BenchmarkSettings(true,1,0,1000,42,120,"EXACT",EngineConfig.defaults()),false,"jitter",root.resolve("examples/activation.sfx"),null);}catch(Throwable e){failure.set(e);}},"release-cancellation-check");
            worker.start();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while(!Files.exists(cancelled.resolve("jitter-EXACT-f0/worker.log")) && worker.isAlive() && System.nanoTime()<deadline)Thread.sleep(20);
            worker.interrupt();worker.join(15000);
            expect(!worker.isAlive() && (failure.get() instanceof InterruptedException || failure.get() instanceof CancellationException),"cancellation ends owned worker");
            expect(Files.readString(cancelled.resolve("metadata.json")).contains("\"state\":\"CANCELLED\""),"cancelled metadata retained");
            command("headless-ui",0,"render-ui",output.resolve("ui-preview.png").toString());
            Files.writeString(output.resolve("checks.json"),Json.write(Map.of("passed",true,"check_count",checks.size(),"checks",checks,"note","Negative CLI, timeout and cancellation cases intentionally expect failure states."))+"\n",StandardCharsets.UTF_8);
            System.out.println("PASS: "+checks.size()+" release integration checks. Saved "+output);
        }catch(Exception | AssertionError e){Files.writeString(output.resolve("checks.json"),Json.write(Map.of("passed",false,"checks",checks,"error",e.toString())));throw e;}
    }
    private static void command(String name,int expected,String...arguments)throws Exception {
        List<String> command=new ArrayList<>(List.of(java(),"-Djava.awt.headless=true","-jar",jar.toString()));command.addAll(Arrays.asList(arguments));
        Process process=new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).redirectOutput(output.resolve(name+".log").toFile()).start();
        int code;
        try{if(!process.waitFor(120,TimeUnit.SECONDS))throw new IllegalStateException("Release check timed out: "+name);code=process.exitValue();}
        finally{if(process.isAlive())process.destroyForcibly();}
        expect(code==expected,name+" exit "+code+" expected "+expected);
    }
    private static String java(){return Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();}
    private static void expect(boolean passed,String detail){checks.add(Map.of("passed",passed,"detail",detail));if(!passed)throw new AssertionError(detail);}
}
