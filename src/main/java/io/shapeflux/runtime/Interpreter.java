package io.shapeflux.runtime;

import io.shapeflux.ir.*;
import java.util.*;

/** Unfused, node-by-node tensor execution used as the reference baseline. */
public final class Interpreter {
    private Interpreter() {}
    public static double[] run(Program p, TensorInput input) {
        double[] out = new double[p.outputSize(input.rows(), input.cols())];
        runInto(p, input, out);
        return out;
    }
    /** Caller-owned output permits the same output-allocation boundary as compiled kernels. */
    public static void runInto(Program p, TensorInput input, double[] out) {
        if (out.length != p.outputSize(input.rows(), input.cols())) throw new IllegalArgumentException("Wrong output length");
        int size = input.x().length;
        Map<Node, double[]> values = new IdentityHashMap<>();
        List<Node> order=p.reachable();Map<Node,Integer> users=new IdentityHashMap<>();
        for(Node n:order)for(Node a:n.args())users.merge(a,1,Integer::sum);
        long liveElements=0;
        for (Node n : order) {
            KernelProbe.interrupted();
            double[] v;
            if (n.op() == Op.X) v = input.x();
            else {
                if(liveElements+size>32_000_000L)throw new IllegalArgumentException("Reference intermediate-memory budget exceeded; reduce input shape or graph width");
                v = new double[size];
                liveElements+=size;
                if (n.op() == Op.BIAS) { for (int i = 0; i < size; i++) v[i] = input.bias()[i % input.cols()]; }
                else if (n.op() == Op.CONSTANT) Arrays.fill(v, n.value());
                else if(n.op().rowwise())Ops.rowwise(values.get(n.args().get(0)),v,input.rows(),input.cols(),n.op());
                else {
                    double[] a = values.get(n.args().get(0));
                    double[] b = n.args().size() == 2 ? values.get(n.args().get(1)) : null;
                    for (int i = 0; i < size; i++) v[i] = Ops.apply(n.op(), a[i], b == null ? 0 : b[i]);
                }
            }
            values.put(n, v);
            for(Node a:n.args())if(users.merge(a,-1,Integer::sum)==0) { values.remove(a);if(a.op()!=Op.X)liveElements-=size; }
        }
        Ops.finish(values.get(p.output()), out, input.rows(), input.cols(), p.terminal());
    }
}
