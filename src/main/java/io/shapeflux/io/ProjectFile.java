package io.shapeflux.io;

import io.shapeflux.ir.Parser;
import io.shapeflux.runtime.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Portable text project; arrays and executable bytecode are intentionally not serialized. */
public final class ProjectFile {
    public record Project(String dsl, Strategy strategy, EngineConfig config, int rows, int cols, String workload) {
        public Project {
            Parser.parse(dsl);Objects.requireNonNull(strategy);Objects.requireNonNull(config);
            if(rows<1 || cols<1 || (long)rows*cols>8_000_000)throw new IllegalArgumentException("Invalid project shape");
            if(!Set.of("hotset","jitter","shift","longtail").contains(workload))throw new IllegalArgumentException("Invalid project workload");
        }
    }
    private ProjectFile() {}
    public static void save(Path target,Project project) throws IOException {
        Properties p=new Properties();EngineConfig c=project.config();
        p.setProperty("format","shapeflux-project-2");p.setProperty("dsl",project.dsl());p.setProperty("strategy",project.strategy().name());
        p.setProperty("rows",Integer.toString(project.rows()));p.setProperty("cols",Integer.toString(project.cols()));p.setProperty("workload",project.workload());
        p.setProperty("cache",Integer.toString(c.maxSpecializations()));p.setProperty("hot",Integer.toString(c.hotThreshold()));p.setProperty("budget_ns",Long.toString(c.compileBudgetNanos()));
        p.setProperty("optimize_ir",Boolean.toString(c.optimizeIr()));p.setProperty("unroll",Boolean.toString(c.unroll()));p.setProperty("horizon",Integer.toString(c.horizonMultiplier()));
        p.setProperty("reuse_window",Integer.toString(c.reuseWindow()));p.setProperty("row_bucket",Integer.toString(c.rowBucket()));p.setProperty("col_bucket",Integer.toString(c.colBucket()));
        p.setProperty("recheck",Integer.toString(c.recheckEvery()));p.setProperty("trace_events",Boolean.toString(c.traceEvents()));p.setProperty("probe_rounds",Integer.toString(c.probeRounds()));
        Path path=target.toAbsolutePath();Files.createDirectories(path.getParent());Path temp=Files.createTempFile(path.getParent(),".sfx-project-",".tmp");
        try {try(Writer writer=Files.newBufferedWriter(temp,StandardCharsets.UTF_8)){p.store(writer,"ShapeFlux-J project, UTF-8");}
            try {Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}
            catch(AtomicMoveNotSupportedException e){Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);}
        } finally {Files.deleteIfExists(temp);}
    }
    public static Project load(Path path) throws IOException {
        if(Files.size(path)>128_000)throw new IllegalArgumentException("Project exceeds 128 KB");
        Properties p=new Properties();try(Reader reader=Files.newBufferedReader(path,StandardCharsets.UTF_8)){p.load(reader);}
        if(!"shapeflux-project-2".equals(p.getProperty("format")))throw new IllegalArgumentException("Unsupported project format");
        EngineConfig c=new EngineConfig(integer(p,"cache"),integer(p,"hot"),Long.parseLong(required(p,"budget_ns")),bool(p,"optimize_ir"),bool(p,"unroll"),integer(p,"horizon"),integer(p,"recheck"),bool(p,"trace_events"),integer(p,"probe_rounds"),Integer.parseInt(p.getProperty("reuse_window","128")),Integer.parseInt(p.getProperty("row_bucket","0")),Integer.parseInt(p.getProperty("col_bucket","0")));
        return new Project(required(p,"dsl"),Strategy.valueOf(required(p,"strategy")),c,integer(p,"rows"),integer(p,"cols"),required(p,"workload"));
    }
    private static String required(Properties p,String key){String v=p.getProperty(key);if(v==null)throw new IllegalArgumentException("Missing project field: "+key);return v;}
    private static int integer(Properties p,String key){return Integer.parseInt(required(p,key));}
    private static boolean bool(Properties p,String key){String v=required(p,key);if(!Set.of("true","false").contains(v))throw new IllegalArgumentException("Invalid boolean: "+key);return Boolean.parseBoolean(v);}
}
