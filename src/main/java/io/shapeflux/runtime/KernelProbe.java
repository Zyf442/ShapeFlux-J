package io.shapeflux.runtime;

import io.shapeflux.compiler.Kernel;
import java.util.Arrays;
import java.util.concurrent.CancellationException;

/** Alternating, calibrated batches; elapsed probe time is charged to the triggering request. */
public final class KernelProbe {
    public record Result(double genericNanos, double candidateNanos, int rounds, int batch) {}
    private static volatile double sink;
    private KernelProbe() {}
    public static Result compare(Kernel generic, Kernel candidate, TensorInput input, int outputSize, int rounds) {
        double[] a = new double[outputSize], b = new double[outputSize];
        int warmup = input.x().length > 131072 ? 2 : 12;
        for (int i=0;i<warmup;i++) {
            interrupted(); generic.run(input.x(),input.bias(),input.rows(),input.cols(),a);
            candidate.run(input.x(),input.bias(),input.rows(),input.cols(),b);
        }
        if (!Numerics.close(a,b)) throw new IllegalStateException("Probe output mismatch");
        double pilot = Math.max(time(generic,input,a,1),time(candidate,input,b,1));
        int batch = Math.max(1,Math.min(64,(int)Math.ceil(200_000.0/Math.max(1,pilot))));
        double[] first = new double[rounds], second = new double[rounds];
        for (int r=0;r<rounds;r++) {
            interrupted();
            if ((r&1)==0) { first[r]=time(generic,input,a,batch);second[r]=time(candidate,input,b,batch); }
            else { second[r]=time(candidate,input,b,batch);first[r]=time(generic,input,a,batch); }
        }
        if (!Numerics.close(a,b)) throw new IllegalStateException("Probe output mismatch after timing");
        Arrays.sort(first);Arrays.sort(second);
        return new Result(first[rounds/2],second[rounds/2],rounds,batch);
    }
    private static double time(Kernel kernel,TensorInput input,double[] out,int count) {
        long start=System.nanoTime();
        for(int i=0;i<count;i++) { interrupted();kernel.run(input.x(),input.bias(),input.rows(),input.cols(),out); }
        long elapsed=System.nanoTime()-start;sink=out[out.length/2];return (double)elapsed/count;
    }
    public static void interrupted() { if(Thread.currentThread().isInterrupted())throw new CancellationException("Task cancelled"); }
}
