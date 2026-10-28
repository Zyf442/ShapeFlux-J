package io.shapeflux.ir;

import java.util.List;

public record Node(int id, Op op, double value, List<Node> args) {
    public Node { args = List.copyOf(args); }
    public String name() { return "v" + id; }
    // DAG identity avoids recursively hashing repeated subgraphs such as a+a.
    @Override public boolean equals(Object other) { return this == other; }
    @Override public int hashCode() { return System.identityHashCode(this); }
}
