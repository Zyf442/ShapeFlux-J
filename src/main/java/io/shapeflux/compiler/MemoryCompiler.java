package io.shapeflux.compiler;

import io.shapeflux.ir.Program;
import java.util.concurrent.atomic.AtomicLong;

/** Compiler-owned source only; both scalar and matrix backends share the same Java compiler service. */
public final class MemoryCompiler implements KernelCompiler {
    private static final AtomicLong SERIAL=new AtomicLong();
    @Override public CompiledKernel compile(Program p,ShapeGuard guard,boolean unroll) {
        long start=System.nanoTime();String simple="K_"+SERIAL.incrementAndGet();String source=CodeGenerator.generate(p,simple,guard,unroll);
        var result=JavaSourceCompiler.load(simple,source,Kernel.class);
        return new CompiledKernel(result.instance(),source,System.nanoTime()-start,result.bytes(),guard);
    }
}
