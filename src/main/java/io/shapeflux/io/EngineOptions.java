package io.shapeflux.io;

import io.shapeflux.runtime.EngineConfig;
import java.util.*;

public final class EngineOptions {
    public static final Set<String> KEYS=Set.of("cache","hot","budget-ms","horizon","recheck","probe-rounds","trace-events","reuse-window","row-bucket","col-bucket");
    private EngineOptions() {}
    public static EngineConfig parse(Map<String,String> options,EngineConfig base) {
        long budget=options.containsKey("budget-ms")?Math.multiplyExact(Long.parseLong(options.get("budget-ms")),1_000_000L):base.compileBudgetNanos();
        boolean tracing=base.traceEvents();
        if(options.containsKey("trace-events")) {String text=options.get("trace-events");if(!Set.of("on","off").contains(text))throw new IllegalArgumentException("trace-events must be on or off");tracing=text.equals("on");}
        return new EngineConfig(integer(options,"cache",base.maxSpecializations()),integer(options,"hot",base.hotThreshold()),budget,base.optimizeIr(),base.unroll(),integer(options,"horizon",base.horizonMultiplier()),integer(options,"recheck",base.recheckEvery()),tracing,integer(options,"probe-rounds",base.probeRounds()),integer(options,"reuse-window",base.reuseWindow()),integer(options,"row-bucket",base.rowBucket()),integer(options,"col-bucket",base.colBucket()));
    }
    private static int integer(Map<String,String> options,String name,int fallback){return options.containsKey(name)?Integer.parseInt(options.get(name)):fallback;}
    public static String encode(EngineConfig c) {
        return c.maxSpecializations()+","+c.hotThreshold()+","+c.compileBudgetNanos()+","+c.optimizeIr()+","+c.unroll()+","+c.horizonMultiplier()+","+c.recheckEvery()+","+c.traceEvents()+","+c.probeRounds()+","+c.reuseWindow()+","+c.rowBucket()+","+c.colBucket();
    }
    public static EngineConfig decode(String value) {
        String[] f=value.split(",",-1);if(f.length!=9 && f.length!=12)throw new IllegalArgumentException("Invalid encoded engine configuration");
        for(int index:new int[]{3,4,7})if(!Set.of("true","false").contains(f[index]))throw new IllegalArgumentException("Invalid encoded boolean");
        return new EngineConfig(Integer.parseInt(f[0]),Integer.parseInt(f[1]),Long.parseLong(f[2]),Boolean.parseBoolean(f[3]),Boolean.parseBoolean(f[4]),Integer.parseInt(f[5]),Integer.parseInt(f[6]),Boolean.parseBoolean(f[7]),Integer.parseInt(f[8]),f.length==12?Integer.parseInt(f[9]):128,f.length==12?Integer.parseInt(f[10]):0,f.length==12?Integer.parseInt(f[11]):0);
    }
}
