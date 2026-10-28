package io.shapeflux.ir;

/** Shared, explicit numerical semantics, also used by generated code. */
public final class Ops {
    public static final double NORM_EPSILON = 1e-5;
    private Ops() {}
    public static double sigmoid(double x) {
        if (x >= 0) return 1.0 / (1.0 + Math.exp(-x));
        double e = Math.exp(x);
        return e / (1.0 + e);
    }
    /** Widely used tanh approximation, not the exact erf definition. */
    public static double gelu(double x) {
        return 0.5 * x * (1.0 + Math.tanh(0.7978845608028654 * (x + 0.044715 * x * x * x)));
    }
    public static double apply(Op op, double a, double b) {
        return switch (op) {
            case ADD -> a + b; case SUB -> a - b; case MUL -> a * b; case DIV -> a / b;
            case NEG -> -a; case RELU -> Math.max(0.0, a); case TANH -> Math.tanh(a);
            case SIGMOID -> sigmoid(a); case GELU -> gelu(a); case EXP -> Math.exp(a);
            case SILU -> a * sigmoid(a); case SQRT -> Math.sqrt(a);
            case LOG -> Math.log(a); case ABS -> Math.abs(a); case MAX -> Math.max(a, b);
            case MIN -> Math.min(a, b); case POW -> Math.pow(a,b); case SIN -> Math.sin(a); case COS -> Math.cos(a);
            case EXPM1 -> Math.expm1(a); case LOG1P -> Math.log1p(a); case FLOOR -> Math.floor(a); case CEIL -> Math.ceil(a); case SQUARE -> a*a;
            default -> throw new IllegalArgumentException("Not a scalar operation: " + op);
        };
    }
    public static void finish(double[] values, double[] out, int rows, int cols, Program.Terminal terminal) {
        if (terminal == Program.Terminal.MAP) { System.arraycopy(values, 0, out, 0, values.length); return; }
        for (int r = 0; r < rows; r++) {
            int offset = r * cols;
            if (terminal.reduces()) {
                out[r] = reduce(values,offset,cols,terminal);
            } else if (terminal == Program.Terminal.SOFTMAX) {
                double max = Double.NEGATIVE_INFINITY;
                for (int c = 0; c < cols; c++) max = Math.max(max, values[offset + c]);
                double sum = 0;
                for (int c = 0; c < cols; c++) { double e = Math.exp(values[offset + c] - max); out[offset + c] = e; sum += e; }
                for (int c = 0; c < cols; c++) out[offset + c] /= sum;
            } else if (terminal == Program.Terminal.LAYER_NORM) {
                double mean = 0, m2 = 0;
                for (int c = 0; c < cols; c++) {
                    double v = values[offset + c], delta = v - mean;
                    mean += delta / (c + 1);
                    m2 += delta * (v - mean);
                }
                double denom = Math.sqrt(m2 / cols + NORM_EPSILON);
                for (int c = 0; c < cols; c++) out[offset + c] = (values[offset + c] - mean) / denom;
            } else {
                double scale = 0, squares = 0;
                for (int c = 0; c < cols; c++) scale = Math.max(scale, Math.abs(values[offset + c]));
                if (scale > 0) for (int c = 0; c < cols; c++) { double q = values[offset + c] / scale; squares += q * q; }
                double denom = Math.hypot(scale * Math.sqrt(squares / cols), Math.sqrt(NORM_EPSILON));
                for (int c = 0; c < cols; c++) out[offset + c] = values[offset + c] / denom;
            }
        }
    }

    public static double reduce(double[] values,int offset,int width,Program.Terminal terminal) {
        if(terminal==Program.Terminal.ROW_MAX || terminal==Program.Terminal.ROW_MIN) {
            double v=terminal==Program.Terminal.ROW_MAX?Double.NEGATIVE_INFINITY:Double.POSITIVE_INFINITY;
            for(int c=0;c<width;c++)v=terminal==Program.Terminal.ROW_MAX?Math.max(v,values[offset+c]):Math.min(v,values[offset+c]);
            return v;
        }
        if(terminal==Program.Terminal.ROW_VAR) {
            double mean=0,m2=0;
            for(int c=0;c<width;c++){double v=values[offset+c],delta=v-mean;mean+=delta/(c+1);m2+=delta*(v-mean);}
            return m2/width;
        }
        double sum=0;for(int c=0;c<width;c++)sum+=values[offset+c];
        return terminal==Program.Terminal.ROW_MEAN?sum/width:sum;
    }
    /** In-place row stage; reduction scalars broadcast over the current row. */
    public static void transformRow(double[] row,Op op) {
        Program.Terminal terminal=Program.Terminal.function(op.name().toLowerCase(java.util.Locale.ROOT));
        if(terminal==null)throw new IllegalArgumentException("Not a row operation: "+op);
        if(terminal.reduces())java.util.Arrays.fill(row,reduce(row,0,row.length,terminal));
        else finish(row,row,1,row.length,terminal);
    }
    public static void rowwise(double[] input,double[] out,int rows,int cols,Op op) {
        if(!op.rowwise())throw new IllegalArgumentException("Not a row operation");
        Program.Terminal terminal=Program.Terminal.function(op.name().toLowerCase(java.util.Locale.ROOT));
        if(!terminal.reduces()){finish(input,out,rows,cols,terminal);return;}
        for(int r=0;r<rows;r++){int offset=r*cols;java.util.Arrays.fill(out,offset,offset+cols,reduce(input,offset,cols,terminal));}
    }
}
