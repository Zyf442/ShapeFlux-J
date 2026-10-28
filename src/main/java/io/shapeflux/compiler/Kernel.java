package io.shapeflux.compiler;

/** Generated implementations must also check their shape guards when called directly. */
public interface Kernel {
    void run(double[] x, double[] bias, int rows, int cols, double[] out);
}
