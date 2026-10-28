package io.shapeflux.compiler;

/** Row-major C[N,M] = A[N,K] * B[K,M], no transpose, bias, alpha or beta. */
@FunctionalInterface
public interface MatmulKernel {
    void run(double[] a,double[] b,int n,int k,int m,double[] out);
}
