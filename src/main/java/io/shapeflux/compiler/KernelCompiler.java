package io.shapeflux.compiler;

import io.shapeflux.ir.Program;

@FunctionalInterface
public interface KernelCompiler {
    CompiledKernel compile(Program program, ShapeGuard guard, boolean unrollEnabled);
}
