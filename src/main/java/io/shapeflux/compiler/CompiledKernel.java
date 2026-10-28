package io.shapeflux.compiler;

public record CompiledKernel(Kernel kernel, String source, long compileNanos, int bytecodeBytes, ShapeGuard guard) {}
