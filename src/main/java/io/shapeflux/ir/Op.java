package io.shapeflux.ir;

/** Scalar operations lowered by the compiler; tensors are contiguous row-major doubles. */
public enum Op {
    X(0), BIAS(0), CONSTANT(0), ADD(2), SUB(2), MUL(2), DIV(2), NEG(1),
    RELU(1), TANH(1), SIGMOID(1), GELU(1), SILU(1), SQRT(1), EXP(1), LOG(1), ABS(1), MAX(2), MIN(2), POW(2), SIN(1), COS(1), EXPM1(1), LOG1P(1), FLOOR(1), CEIL(1), SQUARE(1),
    ROWSUM(1), ROWMEAN(1), ROWMAX(1), ROWMIN(1), ROWVAR(1), SOFTMAX(1), LAYERNORM(1), RMSNORM(1);
    private final int arity;
    Op(int arity) { this.arity = arity; }
    public int arity() { return arity; }
    public boolean rowwise() { return ordinal() >= ROWSUM.ordinal(); }
}
