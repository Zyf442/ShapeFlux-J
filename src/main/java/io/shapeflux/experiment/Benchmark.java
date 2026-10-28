package io.shapeflux.experiment;

import io.shapeflux.Examples;
import io.shapeflux.ir.*;
import io.shapeflux.runtime.*;
import io.shapeflux.io.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.security.MessageDigest;

/** Exploratory, fork-isolated benchmark. No third-party benchmarking framework is required. */
public final class Benchmark {
    public record Variant(String name, Strategy strategy, EngineConfig config) {}
    public record Outcome(String workload, String variant, long seed, int fork, int traceRequests,
                          double coldMs, double coldComputeMs, double steadyMs, double steadyComputeMedianUs,
                          double steadyComputeP95Us, double steadyWallMedianUs, double steadyWallP95Us, double compileMs,
                          double validationMs, double probeMs, long compilations, long hits, long misses,
                          long evictions, long promotions, long rejections, long compileFailures, long fallbacks,
                          boolean correct, double maxError, long retainedBytes, long runtimeFailures, long demotions, long monitorChecks) {
        public static String header() { return "workload,variant,seed,fork,trace_requests,cold_total_ms,cold_compute_ms,steady_total_ms,steady_compute_median_us,steady_compute_p95_us,steady_wall_median_us,steady_wall_p95_us,compile_ms,validation_ms,probe_ms,compilations,hits,misses,evictions,promotions,rejections,compile_failures,fallbacks,correct,max_abs_error,retained_bytecode_bytes,runtime_failures,demotions,monitor_checks"; }
        public String csv() {
            List<Object> values=List.of(workload,variant,seed,fork,traceRequests,coldMs,coldComputeMs,steadyMs,steadyComputeMedianUs,steadyComputeP95Us,steadyWallMedianUs,steadyWallP95Us,compileMs,validationMs,probeMs,compilations,hits,misses,evictions,promotions,rejections,compileFailures,fallbacks,correct,maxError,retainedBytes,runtimeFailures,demotions,monitorChecks);
            return String.join(",",values.stream().map(Object::toString).toList());
        }
        public boolean healthy(){return correct && compileFailures==0 && fallbacks==0 && runtimeFailures==0;}
    }
    private static volatile double sink;
    private Benchmark() {}
    public static List<Variant> variants(boolean ablate) {
        return variants(ablate,EngineConfig.defaults());
    }
    public static List<Variant> variants(boolean ablate,EngineConfig defaults) {
        List<Variant> v = new ArrayList<>();
        for (Strategy strategy : Strategy.values()) v.add(new Variant(strategy.name(), strategy, defaults));
        if (ablate) {
            v.add(new Variant("BUCKET_NO_IR", Strategy.BUCKET, change(defaults,false,defaults.unroll(),defaults.compileBudgetNanos())));
            v.add(new Variant("BUCKET_NO_UNROLL", Strategy.BUCKET, change(defaults,defaults.optimizeIr(),false,defaults.compileBudgetNanos())));
            v.add(new Variant("ADAPTIVE_NO_IR", Strategy.ADAPTIVE, change(defaults,false,defaults.unroll(),defaults.compileBudgetNanos())));
            v.add(new Variant("ADAPTIVE_NO_UNROLL", Strategy.ADAPTIVE, change(defaults,defaults.optimizeIr(),false,defaults.compileBudgetNanos())));
            v.add(new Variant("ADAPTIVE_NO_BUDGET", Strategy.ADAPTIVE, change(defaults,defaults.optimizeIr(),defaults.unroll(),Long.MAX_VALUE)));
            v.add(new Variant("ADAPTIVE_NO_MONITOR", Strategy.ADAPTIVE, defaults.withMonitoring(0)));
        }
        return List.copyOf(v);
    }
    private static EngineConfig change(EngineConfig b,boolean ir,boolean unroll,long budget) {
        return new EngineConfig(b.maxSpecializations(),b.hotThreshold(),budget,ir,unroll,b.horizonMultiplier(),b.recheckEvery(),b.traceEvents(),b.probeRounds(),b.reuseWindow(),b.rowBucket(),b.colBucket());
    }
    public static List<Variant> select(boolean ablate,BenchmarkSettings settings) {
        List<Variant> available=variants(ablate,settings.engine());
        if(settings.modes().equalsIgnoreCase("all"))return available;
        List<Variant> selected=new ArrayList<>();Set<String> seen=new HashSet<>();
        for(String token:settings.modes().split(",",-1)) {
            String name=token.strip().toUpperCase(Locale.ROOT);if(!seen.add(name))throw new IllegalArgumentException("Duplicate benchmark mode: "+name);
            selected.add(available.stream().filter(v->v.name.equals(name)).findFirst().orElseThrow(()->new IllegalArgumentException("Unknown mode (add --ablate for ablations): "+name)));
        }
        return List.copyOf(selected);
    }
    public static Outcome run(String workload, Variant variant, boolean quick, long seed, int fork, Path output, String dsl, List<Workloads.Shape> shapes) throws IOException {
        return run(workload,variant,BenchmarkSettings.defaults(quick,1),seed,fork,output,dsl,shapes);
    }
    public static Outcome run(String workload, Variant variant, BenchmarkSettings settings, long seed, int fork, Path output, String dsl, List<Workloads.Shape> shapes) throws IOException {
        Files.createDirectories(output);SystemMetrics noise=SystemMetrics.begin();ProgressIO.Tracker progress=new ProgressIO.Tracker(output);
        Program program = Parser.parse(dsl);
        if((long)shapes.size()*(1+settings.warmup()+settings.measure())>1_000_000)throw new IllegalArgumentException("Run exceeds one million recorded requests; split the experiment");
        Engine engine = new Engine(program, variant.strategy(), variant.config());
        // Recreate the same seeded values outside engine timing, holding only the current input.
        List<Workloads.Shape> inputs = shapes;
        StringBuilder raw = new StringBuilder("phase,pass,index,rows,cols,selected,hit,compute_ns,total_ns,correct,max_abs_error\n");
        double coldMs = 0, coldComputeMs = 0, steadyMs = 0, maxError = 0;
        boolean correct = true;
        List<Long> compute = new ArrayList<>(), wall = new ArrayList<>();
        int warmup = settings.warmup(), measure = settings.measure();
        long completed=0,processedRows=0,total=(long)inputs.size()*(1+warmup+measure);
        progress.step(0,total,0,0,"准备编译");
        for (int pass = -1; pass < warmup + measure; pass++) {
            String phase = pass == -1 ? "cold" : pass < warmup ? "warmup" : "steady";
            for (int i = 0; i < inputs.size(); i++) {
                KernelProbe.interrupted();
                Workloads.Shape shape=inputs.get(i);TensorInput input=TensorInput.random(shape.rows(),shape.cols(),seed+i);
                progress.step(completed,total,processedRows,(int)completed,phase.equals("warmup")?"预热":phase.equals("cold")?"冷请求":"测量");
                Engine.Execution e = engine.execute(input);
                // Consume an output per invocation; oracle time is outside reported engine timings.
                sink = e.output()[e.output().length / 2];
                double[] expected = Interpreter.run(program, input);
                boolean ok = Numerics.close(expected, e.output()); double error = Numerics.maxError(expected, e.output());
                correct &= ok; maxError = Math.max(maxError, error);completed++;processedRows+=input.rows();
                raw.append(phase).append(',').append(pass).append(',').append(i).append(',').append(input.rows()).append(',').append(input.cols()).append(',')
                        .append(e.selected()).append(',').append(e.cacheHit()).append(',').append(e.computeNanos()).append(',').append(e.totalNanos()).append(',').append(ok).append(',').append(error).append('\n');
                if (pass == -1) { coldMs += e.totalNanos()/1e6; coldComputeMs += e.computeNanos()/1e6; }
                else if (pass >= warmup) { steadyMs += e.totalNanos()/1e6; compute.add(e.computeNanos()); wall.add(e.totalNanos()); }
            }
        }
        Engine.Stats s = engine.stats();
        Outcome o = new Outcome(workload,variant.name,seed,fork,inputs.size(),coldMs,coldComputeMs,steadyMs,percentile(compute,.5)/1000,percentile(compute,.95)/1000,percentile(wall,.5)/1000,percentile(wall,.95)/1000,
                s.compileNanos()/1e6,s.validationNanos()/1e6,s.probeNanos()/1e6,s.compilations(),s.hits(),s.misses(),s.evictions(),s.promotions(),s.rejections(),s.compileFailures(),s.fallbacks(),correct,maxError,s.retainedBytecodeBytes(),s.runtimeFailures(),s.demotions(),s.monitorChecks());
        Files.writeString(output.resolve("raw_requests.csv"), raw, StandardCharsets.UTF_8);
        Files.writeString(output.resolve("summary.csv"), Outcome.header()+"\n"+o.csv()+"\n", StandardCharsets.UTF_8);
        Files.writeString(output.resolve("program.sfx"), dsl, StandardCharsets.UTF_8);
        Workloads.writeCsv(output.resolve("workload.csv"), shapes);
        StringBuilder trace = new StringBuilder("request,type,detail\n");
        for (Engine.Event event : engine.events()) trace.append(event.request()).append(',').append(event.type()).append(',').append(quote(event.detail())).append('\n');
        Files.writeString(output.resolve("events.csv"), trace, StandardCharsets.UTF_8);
        Files.writeString(output.resolve("optimized-ir.txt"), engine.program().describe(), StandardCharsets.UTF_8);
        Files.writeString(output.resolve("run.json"),Json.write(Map.of("stats",s,"outcome",o,"engine_config",variant.config,"settings",settings,"program_fingerprint",engine.program().fingerprint()))+"\n",StandardCharsets.UTF_8);
        Files.createDirectories(output.resolve("generated"));
        int index = 0;
        for (var k : engine.artifacts()) Files.writeString(output.resolve("generated/kernel-" + index++ + ".java.txt"), k.source(), StandardCharsets.UTF_8);
        noise.save(output);progress.finish(completed,total,processedRows,(int)completed);
        return o;
    }
    private static double percentile(List<Long> values, double quantile) {
        List<Long> copy = new ArrayList<>(values); copy.sort(null);
        return copy.get(Math.max(0, (int) Math.ceil(copy.size() * quantile) - 1));
    }
    private static String quote(String text) { return "\"" + text.replace("\"", "\"\"").replace("\n", " ") + "\""; }
    /** Each workload / strategy / seed-fork pair runs in a fresh JVM. */
    public static void launch(Path output,boolean quick,int forks,boolean ablate,String workload,Path dslFile,Path customTrace) throws Exception {
        launch(output,BenchmarkSettings.defaults(quick,forks),ablate,workload,dslFile,customTrace);
    }
    public static void launch(Path output,BenchmarkSettings settings,boolean ablate,String workload,Path dslFile,Path customTrace) throws Exception {
        launch(output,settings,ablate,workload,dslFile,customTrace,u->{});
    }
    public static void launch(Path output,BenchmarkSettings settings,boolean ablate,String workload,Path dslFile,Path customTrace,java.util.function.Consumer<ProgressIO.Update> progress) throws Exception {
        List<Variant> variants=select(ablate,settings);
        List<String> scenarios=customTrace!=null?List.of("custom"):workload.equals("all")?List.of("hotset","jitter","shift","longtail"):List.of(workload);
        Map<String,List<Workloads.Shape>> traces=new LinkedHashMap<>();
        for(String scenario:scenarios)traces.put(scenario,customTrace==null?Workloads.create(scenario,settings.quick()):Workloads.fromCsv(customTrace));
        String dsl=dslFile==null?Examples.ACTIVATION:Files.readString(dslFile,StandardCharsets.UTF_8);Parser.parse(dsl);
        if(Files.exists(output.resolve("metadata.json")) || Files.exists(output.resolve("summary.csv")))throw new IllegalArgumentException("Benchmark output already contains a run; choose a new directory");
        Files.createDirectories(output);Files.createDirectories(output.resolve("inputs"));settings.save(output.resolve("settings.properties"));
        Files.writeString(output.resolve("inputs/program.sfx"),dsl,StandardCharsets.UTF_8);
        for(var trace:traces.entrySet())Workloads.writeCsv(output.resolve("inputs/"+trace.getKey()+".csv"),trace.getValue());
        Map<String,Object> metadata=new LinkedHashMap<>();metadata.put("format","shapeflux-benchmark-2");metadata.put("created_at_utc",Instant.now().toString());
        metadata.put("java_version",System.getProperty("java.version"));metadata.put("vm",System.getProperty("java.vm.name"));metadata.put("os",System.getProperty("os.name"));metadata.put("os_version",System.getProperty("os.version"));metadata.put("arch",System.getProperty("os.arch"));
        metadata.put("available_processors",Runtime.getRuntime().availableProcessors());metadata.put("settings",settings);metadata.put("selected_variants",variants.stream().map(Variant::name).toList());
        metadata.put("program_sha256",sha(output.resolve("inputs/program.sfx")));metadata.put("application_sha256",applicationHash());metadata.put("worker_vm_options",List.of("-Xmx768m","-Djava.awt.headless=true"));
        metadata.put("note","Engine wall timings include compilation, validation, tuning and monitoring. JVM startup, parsing, inputs and external oracle are excluded. Input values are recreated per request outside engine timing, holding only the current batch. Exploratory benchmark; fixed warmup does not guarantee JVM steady state.");
        StringBuilder summary=new StringBuilder(Outcome.header()+"\n");int completed=0;metadata.put("state","RUNNING");metadata.put("completed_workers",completed);saveMetadata(output,metadata);
        try {
            for(int fork=0;fork<settings.forks();fork++) {
                List<Variant> order=new ArrayList<>(variants);Collections.rotate(order,fork);
                for(String scenario:scenarios)for(Variant variant:order) {
                    KernelProbe.interrupted();Path directory=output.resolve(scenario+"-"+variant.name+"-f"+fork);Files.createDirectories(directory);
                    System.out.println("Running "+scenario+" / "+variant.name+" / fork "+fork);
                    String java=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").startsWith("Windows")?"java.exe":"java").toString();
                    List<String> args=List.of(java,"-Xmx768m","-Djava.awt.headless=true","-cp",System.getProperty("java.class.path"),"io.shapeflux.Main","__worker",output.toAbsolutePath().toString(),scenario,variant.name,Integer.toString(fork));
                    Process process=new ProcessBuilder(args).redirectErrorStream(true).redirectOutput(directory.resolve("worker.log").toFile()).start();
                    try {
                        ProgressIO.await(process,directory,settings.timeoutSeconds(),scenario+" / "+variant.name,completed+1,settings.forks()*scenarios.size()*variants.size(),progress);
                        if(process.exitValue()!=0)throw new IllegalStateException("Worker failed; see "+directory.resolve("worker.log"));
                    } finally {if(process.isAlive())process.destroyForcibly();}
                    List<String> lines=Files.readAllLines(directory.resolve("summary.csv"),StandardCharsets.UTF_8);summary.append(lines.get(1)).append('\n');
                    Files.writeString(output.resolve("summary.csv"),summary,StandardCharsets.UTF_8);
                    metadata.put("completed_workers",++completed);saveMetadata(output,metadata);
                }
            }
            metadata.put("state","COMPLETE");ReportWriter.generate(output.resolve("summary.csv"),output);
        } catch(Exception error) {
            metadata.put("state",error instanceof InterruptedException || error instanceof CancellationException?"CANCELLED":"FAILED");metadata.put("error",error.toString());throw error;
        } finally {
            boolean interrupted=Thread.interrupted();
            try{metadata.put("finished_at_utc",Instant.now().toString());saveMetadata(output,metadata);}
            finally{if(interrupted)Thread.currentThread().interrupt();}
        }
        System.out.println("Saved "+output.resolve("report.html").toAbsolutePath());
    }
    private static void saveMetadata(Path output,Map<String,Object> metadata) throws IOException {Files.writeString(output.resolve("metadata.json"),Json.write(metadata)+"\n",StandardCharsets.UTF_8);}
    private static String sha(Path file) throws Exception {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)));}
    private static String applicationHash(){try{Path location=Path.of(Benchmark.class.getProtectionDomain().getCodeSource().getLocation().toURI());return Files.isRegularFile(location)?sha(location):"development-class-directory";}catch(Exception e){return "unavailable";}}
    public static void worker(String[] args) throws IOException {
        if(args.length!=5)throw new IllegalArgumentException("Invalid internal worker arguments");
        Path root=Path.of(args[1]);String scenario=args[2],label=args[3];int fork=Integer.parseInt(args[4]);BenchmarkSettings settings=BenchmarkSettings.load(root.resolve("settings.properties"));
        Variant variant=variants(true,settings.engine()).stream().filter(v->v.name.equals(label)).findFirst().orElseThrow();
        if(!Set.of("hotset","jitter","shift","longtail","custom").contains(scenario))throw new IllegalArgumentException("Invalid scenario");
        Path directory=root.resolve(scenario+"-"+label+"-f"+fork);
        Outcome outcome=run(scenario,variant,settings,Math.addExact(settings.seed(),fork*1000L),fork,directory,Files.readString(root.resolve("inputs/program.sfx"),StandardCharsets.UTF_8),Workloads.fromCsv(root.resolve("inputs/"+scenario+".csv")));
        System.out.println(outcome.csv());if(!outcome.healthy())throw new IllegalStateException("Correctness, compilation or execution check failed");
    }
}
