package io.shapeflux.ir;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

public record Program(List<Node> nodes, Node output, Terminal terminal) {
    public enum Terminal {
        MAP, SOFTMAX, ROW_SUM, ROW_MEAN, ROW_MAX, ROW_MIN, ROW_VAR, LAYER_NORM, RMS_NORM;
        public boolean reduces() { return this == ROW_SUM || this == ROW_MEAN || this == ROW_MAX || this == ROW_MIN || this == ROW_VAR; }
        public static Terminal function(String name) {
            return switch (name) {
                case "softmax" -> SOFTMAX; case "rowsum" -> ROW_SUM; case "rowmean" -> ROW_MEAN;
                case "rowmax" -> ROW_MAX; case "rowmin" -> ROW_MIN; case "rowvar" -> ROW_VAR;
                case "layernorm" -> LAYER_NORM; case "rmsnorm" -> RMS_NORM; default -> null;
            };
        }
    }
    public Program { nodes = List.copyOf(nodes); }
    public List<Node> reachable() {
        LinkedHashSet<Node> ordered = new LinkedHashSet<>();
        visit(output, ordered);
        return List.copyOf(ordered);
    }
    private static void visit(Node node, Set<Node> ordered) {
        if (ordered.contains(node)) return;
        for (Node arg : node.args()) visit(arg, ordered);
        ordered.add(node);
    }
    public int outputSize(int rows, int cols) { return terminal.reduces() ? rows : Math.multiplyExact(rows, cols); }
    public String describe() {
        StringBuilder b = new StringBuilder("x : Tensor<double>[N,D]\nbias : Tensor<double>[D] (row broadcast)\n\n");
        for (Node n : reachable()) {
            b.append(n.name()).append(" = ").append(n.op());
            if (n.op() == Op.CONSTANT) b.append('(').append(n.value()).append(')');
            else if (!n.args().isEmpty()) b.append(n.args().stream().map(Node::name).toList());
            b.append('\n');
        }
        return b.append("\nout = ").append(terminal).append('(').append(output.name()).append(") : [N,")
                .append(terminal.reduces() ? "1" : "D").append("]\n").toString();
    }
    /** Canonical full digest: never key generated code by source labels or shape alone. */
    public String fingerprint() {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(describe().getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) { throw new AssertionError(e); }
    }
}
