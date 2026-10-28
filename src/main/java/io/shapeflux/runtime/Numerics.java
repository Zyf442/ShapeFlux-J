package io.shapeflux.runtime;

public final class Numerics {
    private Numerics() {}
    /** Handles NaN/infinity explicitly; never accepts NaN as a small error. */
    public static double maxError(double[] expected, double[] actual) {
        if (expected.length != actual.length) return Double.POSITIVE_INFINITY;
        double max = 0;
        for (int i = 0; i < expected.length; i++) {
            double a = expected[i], b = actual[i];
            if (Double.doubleToLongBits(a) == Double.doubleToLongBits(b)) continue;
            if (!Double.isFinite(a) || !Double.isFinite(b)) return Double.POSITIVE_INFINITY;
            // Signed zero is part of strict expression semantics.
            if (a == 0.0 && b == 0.0) return Double.POSITIVE_INFINITY;
            max = Math.max(max, Math.abs(a - b));
        }
        return max;
    }
    public static boolean close(double[] expected, double[] actual) {
        if (expected.length != actual.length) return false;
        for (int i = 0; i < expected.length; i++) {
            double a = expected[i], b = actual[i];
            if (Double.doubleToLongBits(a) == Double.doubleToLongBits(b)) continue;
            if (!Double.isFinite(a) || !Double.isFinite(b) || (a == 0 && b == 0)) return false;
            if (Math.abs(a - b) > 1e-11 + 1e-10 * Math.abs(a)) return false;
        }
        return true;
    }
}
