package io.shapeflux;

import io.shapeflux.experiment.DatasetBenchmark;
import io.shapeflux.io.*;
import io.shapeflux.runtime.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPOutputStream;

/** Real streaming, changing-batch, label handling and canonical input identity checks. */
final class DatasetSuite {
    private static int checks;
    private DatasetSuite() {}
    static int run() throws Exception {
        Path root=Files.createTempDirectory("shapeflux-dataset-");
        try {
            Path raw=root.resolve("last.csv"),gzip=root.resolve("last.csv.gz");
            String text="\ufeff# numeric fixture\n1,2,0\n3,4,1\n5,6,0\n7,8,1\n9,10,0\n";Files.writeString(raw,text,StandardCharsets.UTF_8);
            try(var out=new GZIPOutputStream(Files.newOutputStream(gzip))){out.write(text.getBytes(StandardCharsets.UTF_8));}
            for(Path input:List.of(raw,gzip))try(DatasetCsv reader=new DatasetCsv(input,DatasetCsv.Label.LAST,new int[]{2,2},0)) {
                TensorInput a=reader.next(),b=reader.next(),c=reader.next();
                check(a.rows()==2 && b.rows()==2 && c.rows()==1 && reader.next()==null,"All rows streamed once, including short last batch");
                check(Arrays.equals(a.x(),new double[]{1,2,3,4}) && Arrays.equals(c.x(),new double[]{9,10}),"Last label is removed without changing feature values");
                check(reader.rowsRead()==5 && reader.columns()==2 && Arrays.equals(a.bias(),new double[2]),"Reader accounting and zero bias");
            }
            try(DatasetCsv reader=new DatasetCsv(raw,DatasetCsv.Label.FIRST,new int[]{1,2,3},4)) {
                check(Arrays.equals(reader.next().x(),new double[]{2,0}),"First label removal");
                check(reader.next().rows()==2 && reader.next().rows()==1 && reader.next()==null && reader.rowsRead()==4,"Pattern cycles and max rows truncates exactly");
            }
            try(DatasetCsv reader=new DatasetCsv(raw,DatasetCsv.Label.NONE,new int[]{10},2)) {check(Arrays.equals(reader.next().x(),new double[]{1,2,0,3,4,1}),"No-label features preserve all columns");}
            for(String bad:List.of("1,2\n3,4,5\n","1,NaN\n","1,Infinity\n","one,two\n")) {
                Path file=root.resolve("bad.csv");Files.writeString(file,bad);rejects(()->{try(DatasetCsv reader=new DatasetCsv(file,DatasetCsv.Label.NONE,new int[]{2},0)){reader.next();}},"Invalid row/number rejected");
            }
            rejects(()->new DatasetCsv(raw,DatasetCsv.Label.NONE,new int[]{0},0),"Zero batch rejected");
            rejects(()->new DatasetCsv(raw,DatasetCsv.Label.NONE,new int[]{2},-1),"Negative row cap rejected");
            rejects(()->{try(DatasetCsv reader=new DatasetCsv(raw,DatasetCsv.Label.NONE,new int[]{8_000_000},0)){reader.next();}},"Element limit checked before batch allocation");
            rejects(()->DatasetBenchmark.strategies("GENERIC,GENERIC"),"Repeated modes rejected");
            rejects(()->DatasetBenchmark.strategies("UNKNOWN"),"Unknown mode rejected");
            DatasetBenchmark.Settings settings=new DatasetBenchmark.Settings(gzip,DatasetCsv.Label.LAST,"2,3",0,1,20,"GENERIC,BUCKET",EngineConfig.defaults());
            settings.save(root.resolve("settings.properties"));check(settings.equals(DatasetBenchmark.Settings.load(root.resolve("settings.properties"))),"Streaming settings round-trip");
            DatasetBenchmark.Outcome generic=DatasetBenchmark.run(root.resolve("generic"),settings,Strategy.GENERIC,0,"out = x + 1;");
            DatasetBenchmark.Settings single=new DatasetBenchmark.Settings(raw,DatasetCsv.Label.LAST,"1",0,1,20,"BUCKET",EngineConfig.defaults());
            DatasetBenchmark.Outcome bucket=DatasetBenchmark.run(root.resolve("bucket"),single,Strategy.BUCKET,0,"out = x + 1;");
            check(generic.rows()==5 && generic.features()==2 && generic.batches()==2 && generic.healthy(),"Real compiled stream and oracle pass");
            check(bucket.batches()==5 && bucket.healthy() && bucket.hits()+bucket.misses()==5,"One engine survives across all real batches");
            check(generic.inputValuesSha256().equals(bucket.inputValuesSha256()),"Feature digest invariant to gzip and batch partition");
            check(Files.readString(root.resolve("generic/run.json")).contains("\"last_output_preview\":[6.0,7.0,8.0,9.0,10.0,11.0]"),"Output uses actual file features, not generated random input");
            check(Files.readAllLines(root.resolve("generic/batches.csv")).size()==3,"Per-batch records complete");
        } finally {try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
        return checks;
    }
    @FunctionalInterface private interface Action {void run() throws Exception;}
    private static void rejects(Action action,String message) throws Exception {try{action.run();throw new AssertionError(message);}catch(IllegalArgumentException expected){checks++;}}
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
}
