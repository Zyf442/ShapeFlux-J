package io.shapeflux.experiment;

import io.shapeflux.io.EngineOptions;
import io.shapeflux.runtime.EngineConfig;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public record BenchmarkSettings(boolean quick,int forks,int warmup,int measure,long seed,
                                int timeoutSeconds,String modes,EngineConfig engine) {
    public BenchmarkSettings {
        if(forks<1 || forks>10 || warmup<0 || warmup>1000 || measure<1 || measure>1000 || timeoutSeconds<1 || timeoutSeconds>86400)
            throw new IllegalArgumentException("Invalid benchmark settings");
        Objects.requireNonNull(modes);Objects.requireNonNull(engine);
    }
    public static BenchmarkSettings defaults(boolean quick,int forks) {return new BenchmarkSettings(quick,forks,quick?2:8,quick?2:5,20261027,1800,"all",EngineConfig.defaults());}
    public void save(Path path) throws IOException {
        Properties p=new Properties();p.setProperty("quick",Boolean.toString(quick));p.setProperty("forks",Integer.toString(forks));p.setProperty("warmup",Integer.toString(warmup));p.setProperty("measure",Integer.toString(measure));p.setProperty("seed",Long.toString(seed));
        p.setProperty("timeoutSeconds",Integer.toString(timeoutSeconds));p.setProperty("modes",modes);p.setProperty("engine",EngineOptions.encode(engine));
        try(Writer writer=Files.newBufferedWriter(path,StandardCharsets.UTF_8)){p.store(writer,"ShapeFlux-J benchmark settings");}
    }
    public static BenchmarkSettings load(Path path) throws IOException {
        Properties p=new Properties();try(Reader reader=Files.newBufferedReader(path,StandardCharsets.UTF_8)){p.load(reader);}
        return new BenchmarkSettings(Boolean.parseBoolean(p.getProperty("quick")),Integer.parseInt(p.getProperty("forks")),Integer.parseInt(p.getProperty("warmup")),Integer.parseInt(p.getProperty("measure")),Long.parseLong(p.getProperty("seed")),Integer.parseInt(p.getProperty("timeoutSeconds")),p.getProperty("modes"),EngineOptions.decode(p.getProperty("engine")));
    }
}
