package io.shapeflux.runtime;

/** A bounded observation window and explicit two-axis granularity accompany the soft compile budget. */
public record EngineConfig(int maxSpecializations,int hotThreshold,long compileBudgetNanos,
                           boolean optimizeIr,boolean unroll,int horizonMultiplier,
                           int recheckEvery,boolean traceEvents,int probeRounds,
                           int reuseWindow,int rowBucket,int colBucket) {
    public EngineConfig {
        if(maxSpecializations<1 || maxSpecializations>128 || hotThreshold<1 || compileBudgetNanos<0 || horizonMultiplier<1
                || recheckEvery<0 || probeRounds<3 || probeRounds>31 || (probeRounds&1)==0
                || reuseWindow<1 || reuseWindow>10000 || rowBucket<0 || colBucket<0)
            throw new IllegalArgumentException("Invalid engine configuration");
    }
    public EngineConfig(int cache,int hot,long budget,boolean ir,boolean unroll,int horizon,int recheck,boolean trace,int rounds) {
        this(cache,hot,budget,ir,unroll,horizon,recheck,trace,rounds,128,0,0);
    }
    public EngineConfig(int cache,int hot,long budget,boolean ir,boolean unroll,int horizon) {this(cache,hot,budget,ir,unroll,horizon,32,true,7);}
    public EngineConfig withMonitoring(int interval) {return new EngineConfig(maxSpecializations,hotThreshold,compileBudgetNanos,optimizeIr,unroll,horizonMultiplier,interval,traceEvents,probeRounds,reuseWindow,rowBucket,colBucket);}
    public EngineConfig withGranularity(int rows,int cols) {return new EngineConfig(maxSpecializations,hotThreshold,compileBudgetNanos,optimizeIr,unroll,horizonMultiplier,recheckEvery,traceEvents,probeRounds,reuseWindow,rows,cols);}
    public static EngineConfig defaults() {return new EngineConfig(8,4,500_000_000L,true,true,256);}
    public static EngineConfig demo() {return new EngineConfig(4,3,1_000_000_000L,true,true,16384);}
}
