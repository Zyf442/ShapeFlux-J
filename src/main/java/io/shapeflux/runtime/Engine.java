package io.shapeflux.runtime;

import io.shapeflux.compiler.*;
import io.shapeflux.ir.*;
import java.util.*;
import java.util.concurrent.CancellationException;

/** One engine owns one immutable program. Public calls are serialized for consistent counters. */
public final class Engine {
    public record Event(long request, String type, String detail) {}
    public record Execution(double[] output, String selected, boolean cacheHit, long computeNanos, long totalNanos) {}
    public record Stats(long requests, long hits, long misses, long guardMisses, long compilations,
                        long compileFailures, long fallbacks, long evictions, long promotions, long rejections,
                        long compileNanos, long specialCompileNanos, long probeNanos, long validationNanos,
                        int cachedSpecializations, long retainedBytecodeBytes,
                        long runtimeFailures, long genericFallbacks, long demotions, long monitorChecks) {}
    private static final int PROFILE_LIMIT = 256, EVENT_LIMIT = 400;
    private final Program original, program;
    private final Optimizer.Result optimization;
    private final Strategy strategy;
    private final EngineConfig config;
    private final KernelCompiler compiler;
    private final String fingerprint;
    private final ReuseWindow reuseWindow;
    private final LinkedHashMap<String, CompiledKernel> cache = new LinkedHashMap<>(16, .75f, true);
    private final LinkedHashMap<String, Heat> profiles = new LinkedHashMap<>(16, .75f, true);
    private final ArrayDeque<Event> events = new ArrayDeque<>();
    private CompiledKernel generic;
    private boolean genericAttempted;
    private long requests, hits, misses, guardMisses, compilations, failures, fallbacks, evictions, promotions, rejections;
    private long compileNanos, specialCompileNanos, probeNanos, validationNanos;
    private long runtimeFailures, genericFallbacks, demotions, monitorChecks;
    private long estimatedCompileNanos = 100_000_000;
    private static final class Heat { long seen, cacheHits; double genericPerElement; boolean attempted, admissionLogged; ReuseWindow.Estimate reuse; }
    public Engine(Program original, Strategy strategy, EngineConfig config) { this(original, strategy, config, new MemoryCompiler()); }
    public Engine(Program original, Strategy strategy, EngineConfig config, KernelCompiler compiler) {
        this.original = Objects.requireNonNull(original); this.strategy = Objects.requireNonNull(strategy);
        this.config = Objects.requireNonNull(config); this.compiler = Objects.requireNonNull(compiler);
        optimization = config.optimizeIr() ? Optimizer.optimize(original) : new Optimizer.Result(original, original.nodes().size(), original.reachable().size(), 0, 0);
        program = optimization.program();
        fingerprint = program.fingerprint();
        reuseWindow = new ReuseWindow(config.reuseWindow());
    }
    public Program program() { return program; }
    public Optimizer.Result optimization() { return optimization; }
    public Strategy strategy() { return strategy; }
    public synchronized List<Event> events() { return List.copyOf(events); }
    public synchronized List<CompiledKernel> artifacts() {
        List<CompiledKernel> result = new ArrayList<>(); if (generic != null) result.add(generic); result.addAll(cache.values()); return List.copyOf(result);
    }
    public synchronized Stats stats() {
        long bytes = generic == null ? 0 : generic.bytecodeBytes();
        for (CompiledKernel k : cache.values()) bytes += k.bytecodeBytes();
        return new Stats(requests, hits, misses, guardMisses, compilations, failures, fallbacks, evictions, promotions, rejections,
                compileNanos, specialCompileNanos, probeNanos, validationNanos, cache.size(), bytes,
                runtimeFailures,genericFallbacks,demotions,monitorChecks);
    }
    public synchronized Execution execute(TensorInput input) {
        Objects.requireNonNull(input); KernelProbe.interrupted(); long start = System.nanoTime(); requests++;
        if (strategy == Strategy.INTERPRETED) { misses++; return interpreted(input, start, false); }
        boolean hadGeneric = generic != null;
        if (!genericAttempted) { genericAttempted = true; generic = build(ShapeGuard.generic(), input); }
        if (generic == null) { misses++; return interpreted(input, start, true); }
        CompiledKernel selected = generic;
        boolean hit = hadGeneric;
        Heat heat = null; ShapeGuard family = null;
        if (strategy != Strategy.GENERIC) {
            family = strategy == Strategy.EXACT ? ShapeGuard.exact(input.rows(), input.cols()) : ShapeGuard.family(input.rows(), input.cols(),config.rowBucket(),config.colBucket());
            String key = key(family);
            heat = heat(key); heat.seen++;
            heat.reuse=reuseWindow.observe(key,config.horizonMultiplier());
            if(heat.reuse.observed()==1){heat.genericPerElement=0;heat.admissionLogged=false;if(strategy==Strategy.ADAPTIVE)heat.attempted=false;}
            CompiledKernel existing = cache.get(key);
            if (existing != null && existing.guard().accepts(input.rows(), input.cols())) {
                selected = existing; heat.cacheHits++;
                if(strategy==Strategy.ADAPTIVE && config.recheckEvery()>0 && heat.cacheHits%config.recheckEvery()==0
                        && !stillWorthwhile(existing,input)) { cache.remove(key); selected=generic; hit=false; }
            }
            else {
                hit = false; guardMisses++;
                if (strategy != Strategy.ADAPTIVE && !heat.attempted) {
                    // Eager baselines deliberately ignore the adaptive compilation budget.
                    heat.attempted = true;
                    CompiledKernel candidate = build(family, input);
                    if (candidate != null) { put(key, candidate); selected = candidate; }
                }
            }
        }
        if (hit) hits++; else misses++;
        double[] out = new double[program.outputSize(input.rows(), input.cols())];
        long computeStart = System.nanoTime();
        String selection=selected.guard().key();
        try { selected.kernel().run(input.x(), input.bias(), input.rows(), input.cols(), out); }
        catch (CancellationException e) { throw e; }
        catch (RuntimeException e) {
            runtimeFailures++;event("RUNTIME_FAIL",selection+" / "+e.getMessage());
            if(selected!=generic) {
                cache.remove(key(selected.guard()));
                try {generic.kernel().run(input.x(),input.bias(),input.rows(),input.cols(),out);genericFallbacks++;selection="generic_after_failure";}
                catch(CancellationException cancel){throw cancel;}
                catch(RuntimeException genericError){runtimeFailures++;generic=null;Interpreter.runInto(original,input,out);fallbacks++;selection="INTERPRETER_FALLBACK";}
            } else {generic=null;Interpreter.runInto(original,input,out);fallbacks++;selection="INTERPRETER_FALLBACK";}
        }
        long computeTime = System.nanoTime() - computeStart;
        if (heat != null && strategy == Strategy.ADAPTIVE && selected==generic && generic!=null && selection.equals("generic")) {
            double density=(double)computeTime/input.x().length;
            heat.genericPerElement=heat.genericPerElement==0?density:.8*heat.genericPerElement+.2*density;
            if (!heat.attempted && heat.reuse.observed() >= config.hotThreshold()) maybePromote(family, input, heat);
        }
        event("DISPATCH", input.shape() + " -> " + selection + (hit ? " [hit]" : " [miss]"));
        return new Execution(out, selection, hit, computeTime, System.nanoTime() - start);
    }
    private Execution interpreted(TensorInput input, long start, boolean fallback) {
        if (fallback) { fallbacks++; event("FALLBACK", "Interpreter after generic compilation failure"); }
        double[] out = new double[original.outputSize(input.rows(), input.cols())];
        long computeStart = System.nanoTime(); Interpreter.runInto(original, input, out);
        return new Execution(out, fallback ? "INTERPRETER_FALLBACK" : "INTERPRETED", false, System.nanoTime() - computeStart, System.nanoTime() - start);
    }
    private String key(ShapeGuard guard) { return fingerprint + ":" + guard.key(); }
    private Heat heat(String key) {
        Heat result = profiles.get(key);
        if (result == null) {
            if (profiles.size() >= PROFILE_LIMIT) profiles.remove(profiles.keySet().iterator().next());
            result = new Heat(); profiles.put(key, result);
        }
        return result;
    }
    private CompiledKernel build(ShapeGuard guard, TensorInput input) {
        long start = System.nanoTime();
        CompiledKernel result;
        boolean counted = false;
        try {
            result = compiler.compile(program, guard, config.unroll());
            long spent = System.nanoTime() - start;
            compileNanos += spent;
            if (!guard.isGeneric()) specialCompileNanos += spent;
            estimatedCompileNanos = Math.max(1, spent); compilations++;
            counted = true;
            long checkStart = System.nanoTime();
            boolean correct;
            try {
                correct = validate(result,input);
            } finally { validationNanos += System.nanoTime() - checkStart; }
            if (!correct) { failures++; event("VALIDATION_REJECT", guard.key()); return null; }
            event("COMPILE", guard.key() + " / " + String.format(Locale.ROOT, "%.2f ms", spent / 1e6));
            return result;
        } catch (CancellationException e) { throw e;
        } catch (RuntimeException e) {
            // Compilation errors never change the numerical reference path.
            if (!counted) { long spent = System.nanoTime() - start; compileNanos += spent; if (!guard.isGeneric()) specialCompileNanos += spent; }
            failures++; event("COMPILE_FAIL", guard.key() + " / " + e.getClass().getSimpleName() + ": " + e.getMessage()); return null;
        }
    }
    private boolean validate(CompiledKernel kernel,TensorInput current) {
        List<TensorInput> cases=new ArrayList<>();cases.add(current);
        if(current.x().length<=32768)cases.add(TensorInput.random(current.rows(),current.cols(),7043));
        ShapeGuard g=kernel.guard();
        if(!g.isGeneric())for(int rows:new int[]{g.minRows(),g.maxRows()})for(int cols:new int[]{g.minCols(),g.maxCols()})
            if((rows!=current.rows() || cols!=current.cols()) && (long)rows*cols<=32768)cases.add(TensorInput.random(rows,cols,rows+cols+9967L));
        for(TensorInput in:cases) {
            KernelProbe.interrupted();double[] expected=Interpreter.run(original,in),out=new double[expected.length];
            kernel.kernel().run(in.x(),in.bias(),in.rows(),in.cols(),out);
            if(!Numerics.close(expected,out))return false;
        }
        return true;
    }
    private boolean stillWorthwhile(CompiledKernel candidate,TensorInput input) {
        monitorChecks++;long start=System.nanoTime();
        try {
            KernelProbe.Result timing=KernelProbe.compare(generic.kernel(),candidate.kernel(),input,program.outputSize(input.rows(),input.cols()),config.probeRounds());
            event("RECHECK",candidate.guard().key()+" / ratio="+String.format(Locale.ROOT,"%.3f",timing.candidateNanos()/Math.max(1,timing.genericNanos())));
            if(timing.candidateNanos()<=timing.genericNanos()*1.05)return true;
            demotions++;event("DEMOTE",candidate.guard().key()+" / measured regression");return false;
        } catch(CancellationException e){throw e;
        } catch(RuntimeException e){runtimeFailures++;demotions++;event("DEMOTE",candidate.guard().key()+" / recheck failure: "+e.getMessage());return false;
        } finally {probeNanos+=System.nanoTime()-start;}
    }
    private void put(String key, CompiledKernel value) {
        if (!cache.containsKey(key) && cache.size() >= config.maxSpecializations()) {
            String oldest = cache.keySet().iterator().next(); CompiledKernel removed = cache.remove(oldest);
            Heat history = profiles.get(oldest);
            if (history != null && strategy != Strategy.ADAPTIVE) history.attempted = false;
            evictions++; event("EVICT", removed.guard().key());
        }
        cache.put(key, value);
    }
    private void maybePromote(ShapeGuard guard, TensorInput input, Heat heat) {
        double horizon = heat.reuse.predictedUses();
        double predictedSavings = heat.genericPerElement * input.x().length * .10 * horizon;
        boolean budget = specialCompileNanos < config.compileBudgetNanos();
        if (!budget || predictedSavings < estimatedCompileNanos) {
            if (!heat.admissionLogged) { event("DEFER", guard.key() + (budget ? " / estimated payback too long" : " / compilation budget exhausted")+" / observed="+heat.reuse.observed()+"/"+heat.reuse.span()+" predicted="+String.format(Locale.ROOT,"%.1f",horizon)); heat.admissionLogged = true; }
            return;
        }
        heat.attempted = true;
        CompiledKernel candidate = build(guard, input);
        if (candidate == null) return;
        long probeStart = System.nanoTime();
        KernelProbe.Result times;
        try { times = KernelProbe.compare(generic.kernel(),candidate.kernel(),input,program.outputSize(input.rows(),input.cols()),config.probeRounds()); }
        catch(CancellationException e){throw e;}
        catch(RuntimeException e){runtimeFailures++;rejections++;event("PROBE_FAIL",guard.key()+" / "+e.getMessage());return;}
        finally { probeNanos += System.nanoTime() - probeStart; }
        double saving = times.genericNanos() - times.candidateNanos();
        boolean worthwhile = times.candidateNanos() < times.genericNanos() * .98 && saving * horizon >= candidate.compileNanos();
        event("PROBE", guard.key() + String.format(Locale.ROOT, " / generic=%.1f us candidate=%.1f us horizon=%.1f observed=%d/%d gap=%.1f batch=%d", times.genericNanos()/1000,times.candidateNanos()/1000,horizon,heat.reuse.observed(),heat.reuse.span(),heat.reuse.meanGap(),times.batch()));
        if (worthwhile) { put(key(guard), candidate); promotions++; event("PROMOTE", guard.key()); }
        else { rejections++; event("REJECT", guard.key() + " / insufficient measured benefit"); }
    }
    private void event(String type, String detail) {
        if(type.equals("DISPATCH") && !config.traceEvents())return;
        if (events.size() == EVENT_LIMIT) events.removeFirst();
        events.addLast(new Event(requests, type, detail));
    }
}
