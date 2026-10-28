package io.shapeflux;

import java.util.LinkedHashMap;
import java.util.Map;

public final class Examples {
    private Examples() {}
    public static final String ACTIVATION = """
            # Fused activation chain / x:[N,D], bias:[D]
            let a = relu(x * 1.25 + bias);
            let h = tanh(a * 0.5) + a * 0.1;
            out = sigmoid(h);
            """;
    public static final String SOFTMAX = """
            let scores = gelu(x + bias) * 0.75;
            out = softmax(scores);
            """;
    public static final String REDUCTION = """
            let a = relu(x * 0.8 + bias);
            out = rowsum(a * a);
            """;
    public static final String CSE = """
            let unused = exp(x);
            let factor = (1.0 + 2.0) / 4.0;
            let a = relu(x + bias);
            let b = relu(x + bias);
            out = (a + b) * factor;
            """;
    public static final String LAYER_NORM = """
            # Layer normalization without trainable affine parameters; epsilon=1e-5
            let features = silu(x + bias) + x * 0.1;
            out = layernorm(features);
            """;
    public static final String RMS_NORM = """
            let features = x + bias;
            out = rmsnorm(features);
            """;
    public static Map<String, String> all() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("Activation chain", ACTIVATION); result.put("Stable row softmax", SOFTMAX);
        result.put("Row reduction", REDUCTION); result.put("CSE + constant folding", CSE);
        result.put("Layer normalization", LAYER_NORM); result.put("RMS normalization", RMS_NORM);
        result.put("Mean magnitude", "out = rowmean(sqrt(x * x) + abs(bias));\n");
        result.put("Gated residual", "let gate = sigmoid(x * 0.3 + bias);\nlet value = gelu(x + bias);\nout = value * gate + x * 0.1;\n");
        result.put("Composable variance", "let centered = x - rowmean(x);\nout = rowmean(square(centered));\n");
        result.put("Composed stable softmax", "let centered = x - rowmax(x);\nlet e = exp(centered);\nout = e / rowsum(e);\n");
        result.put("Trigonometric features", "out = sin(x) + cos(x) * log1p(abs(bias));\n");
        return result;
    }
}
