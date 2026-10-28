package io.shapeflux.runtime;

import io.shapeflux.compiler.*;
import java.util.*;

/** Bounded cache of validated CPU matmul kernels with explicit three-axis guards. */
public final class MatmulEngine {
    public enum Mode {REFERENCE,GENERIC,BLOCKED}
    public record Execution(double[] output,long computeNanos,long totalNanos,boolean cacheHit,String guard) {}
    private final Mode mode;private final boolean exact;private final int capacity;
    private final LinkedHashMap<MatmulGuard,MatmulCompiler.Artifact> cache=new LinkedHashMap<>(16,.75f,true);
    private long compilations,evictions,compileNanos;
    public MatmulEngine(Mode mode,boolean exact,int capacity){this.mode=Objects.requireNonNull(mode);this.exact=exact;if(capacity<1 || capacity>128)throw new IllegalArgumentException("Invalid matrix cache");this.capacity=capacity;}
    public synchronized Execution execute(MatrixInput in) {
        KernelProbe.interrupted();long start=System.nanoTime();double[] out=new double[in.n()*in.m()];boolean hit=false;String key="reference";
        MatmulCompiler.Artifact artifact=null;
        if(mode!=Mode.REFERENCE) {
            MatmulGuard guard=mode==Mode.GENERIC?MatmulGuard.generic():exact?MatmulGuard.exact(in.n(),in.k(),in.m()):MatmulGuard.family(in.n(),in.k(),in.m());
            key=guard.key();artifact=cache.get(guard);hit=artifact!=null;
            if(artifact==null) {
                artifact=MatmulCompiler.compile(guard,mode==Mode.BLOCKED);compileNanos+=artifact.compileNanos();compilations++;
                double[] check=new double[out.length];artifact.kernel().run(in.a(),in.b(),in.n(),in.k(),in.m(),check);
                if(!Numerics.close(in.reference(),check))throw new IllegalStateException("Compiled matrix kernel failed validation");
                if(cache.size()>=capacity){cache.remove(cache.keySet().iterator().next());evictions++;}cache.put(guard,artifact);
            }
        }
        long compute=System.nanoTime();
        if(artifact==null)MatrixInput.reference(in.a(),in.b(),in.n(),in.k(),in.m(),out);else artifact.kernel().run(in.a(),in.b(),in.n(),in.k(),in.m(),out);
        return new Execution(out,System.nanoTime()-compute,System.nanoTime()-start,hit,key);
    }
    public synchronized long compilations(){return compilations;}
    public synchronized long evictions(){return evictions;}
    public synchronized long compileNanos(){return compileNanos;}
    public synchronized List<MatmulCompiler.Artifact> artifacts(){return List.copyOf(cache.values());}
}
