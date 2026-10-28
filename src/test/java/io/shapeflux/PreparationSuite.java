package io.shapeflux;

import io.shapeflux.io.*;
import io.shapeflux.runtime.TensorInput;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPOutputStream;

final class PreparationSuite {
    private static int checks;
    private PreparationSuite() {}
    static int run() throws Exception {
        Path root=Files.createTempDirectory("shapeflux-preparation-");
        try {
            String[] a=new String[42];Arrays.fill(a,"0");a[0]="5";a[1]="tcp";a[2]="http";a[3]="SF";a[41]="normal.";
            String[] b=a.clone();b[0]="7";b[1]="udp";b[2]="smtp";b[3]="REJ";b[41]="attack.";
            Path raw=root.resolve("kdd.gz");try(var out=new GZIPOutputStream(Files.newOutputStream(raw))){out.write((String.join(",",a)+"\n"+String.join(",",b)+"\n").getBytes(StandardCharsets.UTF_8));}
            Path prepared=root.resolve("kdd");var result=DatasetPreparation.kdd(raw,prepared);
            check(result.rows()==2 && result.features()==44,"Categorical cardinality determines encoded dimension");
            try(DatasetCsv reader=new DatasetCsv(prepared.resolve("data.csv.gz"),DatasetCsv.Label.NONE,new int[]{1},0)) {
                TensorInput first=reader.next(),second=reader.next();
                check(first.x()[0]==5 && second.x()[0]==7 && reader.next()==null,"Numeric values and source row order preserved");
                check(Arrays.equals(Arrays.copyOfRange(first.x(),38,44),new double[]{1,0,1,0,0,1}),"Lexicographically stable one-hot category positions");
                check(Arrays.equals(Arrays.copyOfRange(second.x(),38,44),new double[]{0,1,0,1,1,0}),"Different categories have distinct one-hot vectors");
            }
            check(Files.readString(prepared.resolve("schema.json")).contains("\"source_column_1based\":2"),"Category mapping exported for reproducibility");
            Path train=root.resolve("train.data"),test=root.resolve("test.data");Files.writeString(train,"1,2,3,4,1,6,2,8,3,10,0\n");Files.writeString(test,"4,13,4,12,4,11,4,10,4,9,9\n");
            Path poker=root.resolve("poker");var hands=DatasetPreparation.poker(List.of(train,test),poker);
            try(DatasetCsv reader=new DatasetCsv(poker.resolve("data.csv.gz"),DatasetCsv.Label.NONE,new int[]{2},0)) {
                TensorInput input=reader.next();check(hands.rows()==2 && hands.features()==10 && input.rows()==2 && input.x().length==20,"Training and testing files combined without class labels");
                check(input.x()[0]==1 && input.x()[9]==10 && input.x()[10]==4 && input.x()[19]==9,"Actual card feature values preserved");
            }
            check(BundledDatasets.all().size()==3 && BundledDatasets.all().stream().mapToLong(BundledDatasets.Profile::rows).sum()==6504453,"All three full datasets have stable catalog metadata");
            check(BundledDatasets.get("KDD99").label()==DatasetCsv.Label.NONE && BundledDatasets.get("covertype").label()==DatasetCsv.Label.LAST,"Profile label handling matches prepared/original files");
            try{BundledDatasets.get("unknown");throw new AssertionError("Unknown dataset accepted");}catch(IllegalArgumentException expected){checks++;}
        }finally{try(var files=Files.walk(root)){for(Path path:files.sorted(Comparator.reverseOrder()).toList())Files.delete(path);}}
        return checks;
    }
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
}
