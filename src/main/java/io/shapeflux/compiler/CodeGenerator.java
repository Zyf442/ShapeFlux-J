package io.shapeflux.compiler;

import io.shapeflux.ir.*;

/** Emits only compiler-owned Java syntax and validated numerical constants. */
public final class CodeGenerator {
    private CodeGenerator() {}
    public static String generate(Program p, String simpleClassName, ShapeGuard guard, boolean unrollEnabled) {
        if (!simpleClassName.matches("K_[0-9]+")) throw new IllegalArgumentException("Invalid internal class name");
        if(p.terminal()==Program.Terminal.ROW_MAX || p.terminal()==Program.Terminal.ROW_MIN || p.terminal()==Program.Terminal.ROW_VAR || p.reachable().stream().anyMatch(n->n.op().rowwise()))return RowCodeGenerator.generate(p,simpleClassName,guard);
        int width = guard.fixedCols();
        int unroll = unrollEnabled && width >= 8 ? 4 : 1;
        StringBuilder b = new StringBuilder("package io.shapeflux.generated;\n\n");
        b.append("// Guard: ").append(guard.key()).append("; unroll=").append(unroll).append("\n")
         .append("public final class ").append(simpleClassName).append(" implements io.shapeflux.compiler.Kernel {\n")
         .append("  public void run(double[] x, double[] bias, int rows, int cols, double[] out) {\n")
         .append("    if (!(" + guard.condition() + ") || rows < 1 || cols < 1 || (long) rows * cols > 8000000L")
         .append(" || x.length != (long) rows * cols || bias.length != cols || out.length != ")
         .append(p.terminal().reduces() ? "rows" : "(long) rows * cols")
         .append(") throw new IllegalArgumentException(\"Kernel shape guard failed\");\n")
         .append("    final int width = ").append(width == 0 ? "cols" : width).append(";\n")
         .append("    final int height = ").append(guard.isExact() ? Integer.toString(guard.minRows()) : "rows").append(";\n")
         .append("    for (int r = 0; r < height; r++) {\n      int offset = r * width;\n");
        if (p.terminal().reduces()) b.append("      double sum = 0.0;\n");
        b.append("      int c = 0;\n");
        if (unroll > 1) {
            b.append("      for (; c + 3 < width; c += 4) {\n");
            for (int lane = 0; lane < 4; lane++) assignment(b, p.terminal(), "c + " + lane, "        ");
            b.append("      }\n");
        }
        b.append("      for (; c < width; c++) {\n"); assignment(b, p.terminal(), "c", "        "); b.append("      }\n");
        if (p.terminal().reduces()) b.append(p.terminal() == Program.Terminal.ROW_MEAN ? "      out[r] = sum / width;\n" : "      out[r] = sum;\n");
        if (p.terminal() == Program.Terminal.SOFTMAX) b.append("""
                  double max = Double.NEGATIVE_INFINITY;
                  for (int j = 0; j < width; j++) max = Math.max(max, out[offset + j]);
                  double sum = 0.0;
                  for (int j = 0; j < width; j++) { double e = Math.exp(out[offset + j] - max); out[offset + j] = e; sum += e; }
                  for (int j = 0; j < width; j++) out[offset + j] /= sum;
                """);
        if (p.terminal() == Program.Terminal.LAYER_NORM) b.append("""
                  double mean = 0.0, m2 = 0.0;
                  for (int j = 0; j < width; j++) {
                    double v = out[offset + j], delta = v - mean;
                    mean += delta / (j + 1);
                    m2 += delta * (v - mean);
                  }
                  double denom = Math.sqrt(m2 / width + 1e-5);
                  for (int j = 0; j < width; j++) out[offset + j] = (out[offset + j] - mean) / denom;
                """);
        if (p.terminal() == Program.Terminal.RMS_NORM) b.append("""
                  double scale = 0.0, squares = 0.0;
                  for (int j = 0; j < width; j++) scale = Math.max(scale, Math.abs(out[offset + j]));
                  if (scale > 0) for (int j = 0; j < width; j++) { double q = out[offset + j] / scale; squares += q * q; }
                  double denom = Math.hypot(scale * Math.sqrt(squares / width), Math.sqrt(1e-5));
                  for (int j = 0; j < width; j++) out[offset + j] /= denom;
                """);
        b.append("    }\n  }\n\n  private static double eval(double[] x, double[] bias, int i, int c) {\n");
        for (Node n : p.reachable()) b.append("    double ").append(n.name()).append(" = ").append(expression(n)).append(";\n");
        return b.append("    return ").append(p.output().name()).append(";\n  }\n}\n").toString();
    }
    private static void assignment(StringBuilder b, Program.Terminal terminal, String col, String indent) {
        b.append(indent);
        if (terminal.reduces()) b.append("sum += ");
        else b.append("out[offset + ").append(col).append("] = ");
        b.append("eval(x, bias, offset + ").append(col).append(", ").append(col).append(");\n");
    }
    static String expression(Node n) {
        String a = n.args().isEmpty() ? "" : n.args().get(0).name();
        String b = n.args().size() < 2 ? "" : n.args().get(1).name();
        return switch (n.op()) {
            case X -> "x[i]"; case BIAS -> "bias[c]"; case CONSTANT -> Double.toString(n.value());
            case ADD -> a + " + " + b; case SUB -> a + " - " + b; case MUL -> a + " * " + b; case DIV -> a + " / " + b;
            case NEG -> "-" + a; case RELU -> "Math.max(0.0, " + a + ")";
            case TANH -> "Math.tanh(" + a + ")"; case EXP -> "Math.exp(" + a + ")";
            case LOG -> "Math.log(" + a + ")"; case ABS -> "Math.abs(" + a + ")";
            case SIGMOID -> "io.shapeflux.ir.Ops.sigmoid(" + a + ")";
            case GELU -> "io.shapeflux.ir.Ops.gelu(" + a + ")";
            case SILU -> a + " * io.shapeflux.ir.Ops.sigmoid(" + a + ")";
            case SQRT -> "Math.sqrt(" + a + ")";
            case MAX -> "Math.max(" + a + ", " + b + ")";
            case MIN -> "Math.min(" + a + ", " + b + ")";
            case POW -> "Math.pow("+a+", "+b+")"; case SIN -> "Math.sin("+a+")"; case COS -> "Math.cos("+a+")";
            case EXPM1 -> "Math.expm1("+a+")"; case LOG1P -> "Math.log1p("+a+")"; case FLOOR -> "Math.floor("+a+")"; case CEIL -> "Math.ceil("+a+")"; case SQUARE -> a+" * "+a;
            default -> throw new IllegalArgumentException("Row operation requires staged lowering");
        };
    }
}
