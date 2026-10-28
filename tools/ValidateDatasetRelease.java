import io.shapeflux.experiment.*;
import io.shapeflux.io.*;
import io.shapeflux.runtime.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/** java -cp dist/shapeflux-j.jar tools/ValidateDatasetRelease.java [fresh-output-dir] */
class ValidateDatasetRelease {
    private static final List<Map<String,Object>> checks=new ArrayList<>();
    private static Path root,output,jar;
    public static void main(String[] args) throws Exception {
        root=Path.of("").toAbsolutePath();jar=root.resolve("dist/shapeflux-j.jar");output=Path.of(args.length==0?"results/dataset-release-"+System.currentTimeMillis():args[0]).toAbsolutePath();
        if(Files.exists(output))throw new IllegalArgumentException("请使用新的数据集检查目录");Files.createDirectories(output);
        try {
            Path valid=output.resolve("valid");command("real-stream",0,"dataset","--max-rows","256","--batches","33,40,48","--modes","GENERIC,BUCKET","--output",valid.toString());
            List<String> lines=Files.readAllLines(valid.resolve("summary.csv"));List<String> keys=ReportWriter.csvLine(lines.get(0));
            check(lines.size()==3,"Two isolated real-data workers completed");
            List<String> first=ReportWriter.csvLine(lines.get(1)),second=ReportWriter.csvLine(lines.get(2));
            check(first.get(keys.indexOf("rows")).equals("256") && first.get(keys.indexOf("features")).equals("54"),"Actual source row/feature counts");
            check(first.get(keys.indexOf("input_values_sha256")).equals(second.get(keys.indexOf("input_values_sha256"))),"Workers read identical real values");
            check(Files.readString(valid.resolve("report.html")).contains("真实数据集测试报告") && Files.readString(valid.resolve("概览.txt")).contains("数值校验：通过"),"Chinese reports generated");
            command("preserve-results",1,"dataset","--output",valid.toString());
            command("invalid-label",1,"dataset","--label","unknown");
            Path bad=output.resolve("bad.csv");Files.writeString(bad,"1,2,0\nbad,3,1\n");Path failed=output.resolve("failed");
            command("invalid-data",1,"dataset","--input",bad.toString(),"--modes","GENERIC","--output",failed.toString());
            check(Files.readString(failed.resolve("metadata.json")).contains("\"state\":\"FAILED\"") && !Files.exists(failed.resolve("report.html")),"Malformed file never produces a completed report");
            Path timed=output.resolve("expected-timeout");command("worker-timeout",1,"dataset","--modes","EXACT","--batches","17,18,19,20,21,22,23,24,25,26,27,28","--timeout-seconds","1","--output",timed.toString());
            check(Files.readString(timed.resolve("metadata.json")).contains("\"state\":\"FAILED\""),"Dataset timeout recorded");
            Path cancelled=output.resolve("expected-cancelled");AtomicReference<Throwable> error=new AtomicReference<>();
            var settings=new DatasetBenchmark.Settings(root.resolve("datasets/covertype/covtype.data.gz"),DatasetCsv.Label.LAST,"17,18,19,20,21,22,23,24,25,26,27,28",0,1,60,"EXACT",EngineConfig.defaults());
            Thread worker=new Thread(()->{try{DatasetBenchmark.launch(cancelled,settings,"out = rmsnorm(x);",message->{});}catch(Throwable t){error.set(t);}},"dataset-cancel-check");
            worker.start();long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
            while(!Files.exists(cancelled.resolve("EXACT-f0/worker.log")) && worker.isAlive() && System.nanoTime()<deadline)Thread.sleep(20);
            worker.interrupt();worker.join(15000);
            check(!worker.isAlive() && (error.get() instanceof InterruptedException || error.get() instanceof CancellationException),"Cancellation ends dataset launcher and owned worker");
            check(Files.readString(cancelled.resolve("metadata.json")).contains("\"state\":\"CANCELLED\""),"Dataset cancellation state retained");
            Files.writeString(output.resolve("checks.json"),Json.write(Map.of("passed",true,"check_count",checks.size(),"checks",checks,"note","Timeout/cancelled/invalid-file cases are intentional negative checks."))+"\n",StandardCharsets.UTF_8);
            System.out.println("通过："+checks.size()+" 项真实数据集发布检查。");
        }catch(Exception | AssertionError e){Files.writeString(output.resolve("checks.json"),Json.write(Map.of("passed",false,"checks",checks,"error",e.toString())));throw e;}
    }
    private static void command(String name,int expected,String...args) throws Exception {
        String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
        List<String> cmd=new ArrayList<>(List.of(java,"-Djava.awt.headless=true","-jar",jar.toString()));cmd.addAll(Arrays.asList(args));
        Process process=new ProcessBuilder(cmd).directory(root.toFile()).redirectErrorStream(true).redirectOutput(output.resolve(name+".log").toFile()).start();
        try{if(!process.waitFor(120,TimeUnit.SECONDS))throw new IllegalStateException("数据集发布检查超时："+name);check(process.exitValue()==expected,name+" exit "+process.exitValue()+" expected "+expected);}
        finally{if(process.isAlive())process.destroyForcibly();}
    }
    private static void check(boolean passed,String detail){checks.add(Map.of("passed",passed,"detail",detail));if(!passed)throw new AssertionError(detail);}
}
