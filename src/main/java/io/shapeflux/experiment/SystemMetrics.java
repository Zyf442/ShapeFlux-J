package io.shapeflux.experiment;

import io.shapeflux.io.Json;
import java.lang.management.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;

/** Diagnostic deltas span the worker's execution scope, including external oracle work. */
public final class SystemMetrics {
    private final long start=System.nanoTime(),gcCount=gc(false),gcMs=gc(true),jitMs=jit(),cpuNs=cpu();
    private SystemMetrics() {}
    public static SystemMetrics begin(){return new SystemMetrics();}
    private static long gc(boolean time){long v=0;for(GarbageCollectorMXBean bean:ManagementFactory.getGarbageCollectorMXBeans()){long x=time?bean.getCollectionTime():bean.getCollectionCount();if(x<0)return -1;v+=x;}return v;}
    private static long jit(){CompilationMXBean b=ManagementFactory.getCompilationMXBean();return b!=null && b.isCompilationTimeMonitoringSupported()?b.getTotalCompilationTime():-1;}
    private static long cpu(){return ProcessHandle.current().info().totalCpuDuration().map(java.time.Duration::toNanos).orElse(-1L);}
    private static String delta(long first,long last,double divisor){return first<0 || last<0?"unsupported":Double.toString(Math.max(0,last-first)/divisor);}
    public void save(Path directory) throws IOException {
        Properties p=new Properties();p.setProperty("scope_ms",Double.toString((System.nanoTime()-start)/1e6));
        p.setProperty("gc_count",delta(gcCount,gc(false),1));p.setProperty("gc_ms",delta(gcMs,gc(true),1));p.setProperty("jit_ms",delta(jitMs,jit(),1));p.setProperty("process_cpu_ms",delta(cpuNs,cpu(),1e6));
        p.setProperty("heap_used_at_end_bytes",Long.toString(ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed()));
        p.setProperty("system_load_average",Double.toString(ManagementFactory.getOperatingSystemMXBean().getSystemLoadAverage()));
        p.setProperty("scope","cold,warmup,measurement,external_reference_and_worker_exports; excludes JVM startup; heap end is not peak or RSS");
        try(Writer w=Files.newBufferedWriter(directory.resolve("system-metrics.properties"),StandardCharsets.UTF_8)){p.store(w,"Worker scope diagnostics");}
        Map<String,Object> map=new LinkedHashMap<>();for(String key:p.stringPropertyNames())map.put(key,p.getProperty(key));
        Files.writeString(directory.resolve("system-metrics.json"),Json.write(map)+"\n",StandardCharsets.UTF_8);
    }
}
