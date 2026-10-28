package io.shapeflux;

import io.shapeflux.compiler.*;
import io.shapeflux.experiment.*;
import io.shapeflux.io.*;
import io.shapeflux.ir.*;
import io.shapeflux.runtime.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Integration and seeded property tests. Timing delays below are policy test doubles only. */
final class ExtendedSuite {
    private static int checks;
    private static final MemoryCompiler COMPILER=new MemoryCompiler();
    private ExtendedSuite() {}
    static int run() throws Exception {
        normalization();randomPrograms();runtimeRecovery();monitoring();cancellation();filesAndReports();return checks;
    }
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static void same(double[] expected,double[] actual,String message){check(Numerics.close(expected,actual),message+" / error="+Numerics.maxError(expected,actual));}
    @FunctionalInterface private interface Action {void run() throws Exception;}
    private static void rejects(Action action,String message)throws Exception {try{action.run();throw new AssertionError("Did not reject: "+message);}catch(IllegalArgumentException expected){checks++;}}
    private static void normalization() {
        TensorInput values=new TensorInput(1,3,new double[]{1,2,3},new double[3]);
        double denom=Math.sqrt(2.0/3+1e-5);
        same(new double[]{-1/denom,0,1/denom},Interpreter.run(Parser.parse("out = layernorm(x);"),values),"Independent layernorm formula");
        same(new double[]{2},Interpreter.run(Parser.parse("out = rowmean(x);"),values),"Row mean");
        TensorInput rms=new TensorInput(1,2,new double[]{3,4},new double[2]);double root=Math.sqrt(12.5+1e-5);
        same(new double[]{3/root,4/root},Interpreter.run(Parser.parse("out = rmsnorm(x);"),rms),"Independent RMS formula");
        same(new double[]{1,1},Interpreter.run(Parser.parse("out = rmsnorm(x);"),new TensorInput(1,2,new double[]{1e308,1e308},new double[2])),"Scaled RMS prevents square overflow");
        same(new double[]{0,0,0},Interpreter.run(Parser.parse("out = layernorm(x);"),new TensorInput(1,3,new double[]{7,7,7},new double[3])),"Constant row normalization");
        for(String operation:List.of("layernorm","rmsnorm","rowmean")) {
            Program original=Parser.parse("out = "+operation+"(silu(x + bias));"),optimized=Optimizer.optimize(original).program();
            for(boolean unroll:new boolean[]{true,false}) {
                CompiledKernel kernel=COMPILER.compile(optimized,ShapeGuard.family(40,19),unroll);
                for(int n:new int[]{33,40,64}) {
                    TensorInput input=TensorInput.random(n,19,n);double[] output=new double[original.outputSize(n,19)];kernel.kernel().run(input.x(),input.bias(),n,19,output);
                    same(Interpreter.run(original,input),output,"Normalization specialized tail: "+operation);
                }
            }
        }
    }
    private static void randomPrograms() {
        SplittableRandom random=new SplittableRandom(769031);
        for(int trial=0;trial<12;trial++) {
            StringBuilder dsl=new StringBuilder("let h0 = x + bias;\n");
            for(int i=1;i<=8;i++) {
                String previous="h"+(i-1);
                String expression=switch(random.nextInt(5)) {
                    case 0->"tanh("+previous+") + bias * 0.1";
                    case 1->"relu("+previous+") * 0.7 + x * 0.1";
                    case 2->"sqrt(abs("+previous+") + 0.5)";
                    case 3->"silu("+previous+") / (abs(x) + 1)";
                    default->"min(max("+previous+", -2), 2)";
                };
                dsl.append("let h").append(i).append(" = ").append(expression).append(";\n");
            }
            String terminal=new String[]{"h8","softmax(h8)","layernorm(h8)","rmsnorm(h8)"}[trial%4];dsl.append("out = ").append(terminal).append(';');
            Program original=Parser.parse(dsl.toString());CompiledKernel kernel=COMPILER.compile(Optimizer.optimize(original).program(),ShapeGuard.generic(),true);
            for(int n:new int[]{1,3,17})for(int d:new int[]{1,5,19}) {
                TensorInput input=TensorInput.random(n,d,trial*100L+n+d);double[] output=new double[original.outputSize(n,d)];kernel.kernel().run(input.x(),input.bias(),n,d,output);
                same(Interpreter.run(original,input),output,"Seeded compiler property trial "+trial+" / "+n+"x"+d);
            }
        }
    }
    private static void runtimeRecovery() {
        Program p=Parser.parse("out = x + bias;");TensorInput input=TensorInput.random(40,9,1);AtomicInteger genericCalls=new AtomicInteger();
        KernelCompiler lateFailure=(program,guard,unroll)-> {
            CompiledKernel real=COMPILER.compile(program,guard,unroll);
            Kernel kernel=(x,bias,rows,cols,out)->{if(genericCalls.incrementAndGet()>2)throw new IllegalStateException("test-only runtime failure");real.kernel().run(x,bias,rows,cols,out);};
            return new CompiledKernel(kernel,real.source(),real.compileNanos(),real.bytecodeBytes(),guard);
        };
        Engine generic=new Engine(p,Strategy.GENERIC,EngineConfig.defaults(),lateFailure);
        same(Interpreter.run(p,input),generic.execute(input).output(),"Late generic failure recovers");
        check(generic.stats().runtimeFailures()==1 && generic.stats().fallbacks()==1,"Runtime failure is visible");
        KernelCompiler specialFailure=(program,guard,unroll)-> {
            CompiledKernel real=COMPILER.compile(program,guard,unroll);AtomicInteger calls=new AtomicInteger();
            Kernel kernel=(x,bias,rows,cols,out)->{if(!guard.isGeneric() && calls.incrementAndGet()>2)throw new IllegalStateException("test-only specialized failure");real.kernel().run(x,bias,rows,cols,out);};
            return new CompiledKernel(kernel,real.source(),real.compileNanos(),real.bytecodeBytes(),guard);
        };
        Engine exact=new Engine(p,Strategy.EXACT,EngineConfig.defaults(),specialFailure);same(Interpreter.run(p,input),exact.execute(input).output(),"Late specialized failure recovers");
        check(exact.stats().runtimeFailures()==1 && exact.stats().genericFallbacks()==1 && exact.stats().cachedSpecializations()==0,"Broken specialization evicted");
        KernelCompiler badBoundary=(program,guard,unroll)-> {
            CompiledKernel real=COMPILER.compile(program,guard,unroll);
            Kernel kernel=(x,bias,rows,cols,out)->{real.kernel().run(x,bias,rows,cols,out);if(!guard.isGeneric() && rows==33)Arrays.fill(out,0);};
            return new CompiledKernel(kernel,real.source(),real.compileNanos(),real.bytecodeBytes(),guard);
        };
        Engine boundary=new Engine(p,Strategy.BUCKET,EngineConfig.defaults(),badBoundary);same(Interpreter.run(p,input),boundary.execute(input).output(),"Boundary validation rejects unsafe family");
        check(boundary.stats().compileFailures()==1 && boundary.stats().cachedSpecializations()==0,"Cross-shape validation performed");
    }
    private static void monitoring() {
        Program p=Parser.parse("out = relu(x + bias);");AtomicBoolean slow=new AtomicBoolean();
        KernelCompiler controlled=(program,guard,unroll)-> {
            CompiledKernel real=COMPILER.compile(program,guard,unroll);
            Kernel kernel=(x,bias,rows,cols,out)->{if(guard.isGeneric())java.util.concurrent.locks.LockSupport.parkNanos(1_000_000);else if(slow.get())java.util.concurrent.locks.LockSupport.parkNanos(5_000_000);real.kernel().run(x,bias,rows,cols,out);};
            return new CompiledKernel(kernel,real.source(),real.compileNanos(),real.bytecodeBytes(),guard);
        };
        Engine engine=new Engine(p,Strategy.ADAPTIVE,new EngineConfig(2,2,1_000_000_000L,true,true,Integer.MAX_VALUE,1,false,7),controlled);
        for(int n:new int[]{33,34})engine.execute(TensorInput.random(n,16,2));check(engine.stats().promotions()==1,"Fast test candidate admitted");slow.set(true);
        TensorInput input=TensorInput.random(35,16,3);same(Interpreter.run(p,input),engine.execute(input).output(),"Regression recheck preserves output");
        check(engine.stats().demotions()==1 && engine.stats().monitorChecks()==1 && engine.stats().cachedSpecializations()==0,"Regressed candidate demoted");
        check(engine.events().stream().noneMatch(e->e.type().equals("DISPATCH")),"Dispatch tracing can be disabled");
    }
    private static void cancellation() {
        Engine engine=new Engine(Parser.parse("out = x;"),Strategy.GENERIC,EngineConfig.defaults());Thread.currentThread().interrupt();
        try{engine.execute(TensorInput.random(1,1,1));throw new AssertionError("Cancellation not observed");}catch(CancellationException expected){checks++;}finally{Thread.interrupted();}
        check(engine.stats().requests()==0,"Cancelled request not counted as executed");
    }
    private static void filesAndReports() throws Exception {
        Path root=Files.createTempDirectory("shapeflux-integration-");
        try {
            Path matrix=root.resolve("matrix.csv"),bias=root.resolve("bias.csv");Files.writeString(matrix,"\ufeff# numeric matrix\n1,2,3\n4,5,6\n");Files.writeString(bias,".5,0,-.5\n");
            TensorInput input=TensorCsv.read(matrix,bias);same(new double[]{1,2,3,4,5,6},input.x(),"CSV matrix values");same(new double[]{.5,0,-.5},input.bias(),"CSV bias");
            TensorCsv.write(root.resolve("roundtrip.csv"),input.x(),2,3);same(input.x(),TensorCsv.read(root.resolve("roundtrip.csv"),null).x(),"CSV output round trip");
            Files.writeString(matrix,"1,2\n3\n");rejects(()->TensorCsv.read(matrix,null),"Jagged matrix");Files.writeString(matrix,"1,,3\n");rejects(()->TensorCsv.read(matrix,null),"Missing CSV value");
            EngineConfig custom=new EngineConfig(3,2,7_000_000,true,false,23,9,false,5);ProjectFile.Project project=new ProjectFile.Project("# 中文注释\nout = layernorm(x + bias);",Strategy.ADAPTIVE,custom,33,19,"jitter");
            Path file=root.resolve("portable.sfxproj");ProjectFile.save(file,project);check(project.equals(ProjectFile.load(file)),"Portable project round trip");
            Files.writeString(file,"format=unknown\n");rejects(()->ProjectFile.load(file),"Project schema version");check(custom.equals(EngineOptions.decode(EngineOptions.encode(custom))),"Engine configuration round trip");
            EngineConfig modified=EngineOptions.parse(Map.of("cache","1","budget-ms","0","recheck","0","trace-events","on"),custom);check(modified.maxSpecializations()==1 && modified.compileBudgetNanos()==0 && modified.recheckEvery()==0 && modified.traceEvents(),"CLI configuration overrides");
            rejects(()->EngineOptions.parse(Map.of("probe-rounds","4"),custom),"Even probe round count");
            BenchmarkSettings settings=new BenchmarkSettings(true,2,1,2,93,30,"GENERIC,ADAPTIVE_NO_MONITOR",custom);Path settingsFile=root.resolve("settings.properties");settings.save(settingsFile);check(settings.equals(BenchmarkSettings.load(settingsFile)),"Benchmark settings round trip");
            List<Benchmark.Variant> selection=Benchmark.select(true,settings);check(selection.size()==2 && selection.get(1).config().recheckEvery()==0 && selection.get(1).config().horizonMultiplier()==23,"Ablation inherits user configuration");
            rejects(()->Benchmark.select(false,settings),"Ablation requires switch");
            String encoded=Json.write(Map.of("quote","\"\\\n\u0001","config",custom));check(encoded.contains("\\u0001") && encoded.contains("\\n") && encoded.contains("\"maxSpecializations\":3"),"JSON escapes and records");
            check(ReportWriter.csvLine("\"a,b\",\"c\"\"d\",3").equals(List.of("a,b","c\"d","3")),"Quoted summary CSV");
            Path summary=root.resolve("summary.csv");String header="workload,variant,fork,cold_total_ms,steady_wall_median_us,steady_wall_p95_us,compilations,correct,compile_failures,fallbacks,runtime_failures\n";
            // Artificial metrics are isolated to this temporary report-format test, never released as results.
            Files.writeString(summary,header+"<script>alert(1)</script>,GENERIC,0,10,4,7,1,true,0,0,0\n<script>alert(1)</script>,GENERIC,1,30,6,9,1,true,0,0,0\n",StandardCharsets.UTF_8);
            Path report=ReportWriter.generate(summary,root.resolve("report"));String html=Files.readString(report);check(!html.contains("<script>") && html.contains("&lt;script&gt;"),"HTML/SVG labels escaped");
            List<Map<String,String>> aggregated=ReportWriter.read(summary);check(aggregated.size()==2,"Report reads both forks");String csv=Files.readString(root.resolve("report/aggregate.csv"));check(csv.contains(",20.0,") && csv.contains(",5.0,8.0,1.0,true"),"Known cross-fork aggregates");
        } finally {try(var files=Files.walk(root)){for(Path path:files.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
    }
}
