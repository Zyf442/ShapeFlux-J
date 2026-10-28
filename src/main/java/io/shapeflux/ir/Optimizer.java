package io.shapeflux.ir;

import java.util.*;

/** Constant folding + ordered structural CSE + dead-code elimination. No unsafe reassociation. */
public final class Optimizer {
    public record Result(Program program, int before, int after, int folds, int merges) {}
    private final Map<Node, Node> rewritten = new IdentityHashMap<>();
    private final Map<String, Node> unique = new HashMap<>();
    private final List<Node> nodes = new ArrayList<>();
    private int folds, merges;
    public static Result optimize(Program input) {
        Optimizer pass = new Optimizer();
        Node root = pass.rewrite(input.output());
        Program p = new Program(pass.nodes, root, input.terminal());
        p = new Program(p.reachable(), root, p.terminal());
        return new Result(p, input.nodes().size(), p.nodes().size(), pass.folds, pass.merges);
    }
    private Node rewrite(Node n) {
        if (rewritten.containsKey(n)) return rewritten.get(n);
        List<Node> args = n.args().stream().map(this::rewrite).toList();
        Op op = n.op(); double value = n.value();
        if (!op.rowwise() && !args.isEmpty() && args.stream().allMatch(a -> a.op() == Op.CONSTANT)) {
            double v = Ops.apply(op, args.get(0).value(), args.size() == 2 ? args.get(1).value() : 0);
            if (Double.isFinite(v)) { op = Op.CONSTANT; value = v; args = List.of(); folds++; }
        }
        String key = op + ":" + (op == Op.CONSTANT ? Double.doubleToRawLongBits(value) : "") + ":" + args.stream().map(Node::id).toList();
        Node result = unique.get(key);
        if (result == null) { result = new Node(nodes.size(), op, value, args); nodes.add(result); unique.put(key, result); }
        else merges++;
        rewritten.put(n, result); return result;
    }
}
