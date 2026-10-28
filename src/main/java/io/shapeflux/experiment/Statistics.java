package io.shapeflux.experiment;

import java.util.*;

/** Student-t interval for a mean of independent worker-level statistics. */
public final class Statistics {
    private static final double[] T95={0,12.7062047364,4.30265272975,3.18244630528,2.7764451052,2.57058183564,2.44691184879,2.36462425159,2.3060041352,2.26215716285};
    public record Summary(int n,double mean,Double sampleSd,Double ci95Low,Double ci95High,Double coefficientOfVariation) {}
    private Statistics() {}
    public static Summary summarize(List<Double> values) {
        if(values.isEmpty() || values.size()>10)throw new IllegalArgumentException("Statistics supports 1..10 independent forks");
        double mean=0,m2=0;int n=0;
        for(double x:values){if(!Double.isFinite(x))throw new IllegalArgumentException("Nonfinite measurement");double delta=x-mean;mean+=delta/++n;m2+=delta*(x-mean);}
        if(n==1)return new Summary(1,mean,null,null,null,null);
        double sd=Math.sqrt(Math.max(0,m2/(n-1))),half=T95[n-1]*sd/Math.sqrt(n);
        return new Summary(n,mean,sd,mean-half,mean+half,mean==0?(sd==0?0.0:null):sd/Math.abs(mean));
    }
}
