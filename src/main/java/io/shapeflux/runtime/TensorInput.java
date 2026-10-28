package io.shapeflux.runtime;

import java.util.Objects;
import java.util.SplittableRandom;

public record TensorInput(int rows, int cols, double[] x, double[] bias) {
    public TensorInput {
        Objects.requireNonNull(x, "x"); Objects.requireNonNull(bias, "bias");
        if (rows < 1 || cols < 1 || (long) rows * cols > 8_000_000) throw new IllegalArgumentException("Shape must be positive and <= 8 million elements");
        if (x.length != rows * cols || bias.length != cols) throw new IllegalArgumentException("x/bias lengths do not match shape");
    }
    public static TensorInput random(int rows, int cols, long seed) {
        if (rows < 1 || cols < 1 || (long) rows * cols > 8_000_000) throw new IllegalArgumentException("Invalid shape");
        SplittableRandom rng = new SplittableRandom(seed);
        double[] x = new double[rows * cols], bias = new double[cols];
        for (int i = 0; i < x.length; i++) x[i] = rng.nextDouble(-3.0, 3.0);
        for (int i = 0; i < bias.length; i++) bias[i] = rng.nextDouble(-0.25, 0.25);
        return new TensorInput(rows, cols, x, bias);
    }
    public String shape() { return rows + "x" + cols; }
}
