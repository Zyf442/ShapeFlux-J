package io.shapeflux.io;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Reproducible, bounded-memory transformations of the two bundled public datasets. */
public final class DatasetPreparation {
    private DatasetPreparation() {}
    public record Prepared(long rows,int features,String sourceSha256,String outputSha256) {}
    private static BufferedReader open(Path input) throws IOException {
        InputStream in=Files.newInputStream(input);try{if(input.toString().endsWith(".gz"))in=new GZIPInputStream(in,65536);return new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8),65536);}
        catch(IOException | RuntimeException e){in.close();throw e;}
    }
    public static Prepared kdd(Path source,Path output) throws Exception {
        List<SortedSet<String>> vocabulary=List.of(new TreeSet<>(),new TreeSet<>(),new TreeSet<>());long rows=0;
        try(BufferedReader in=open(source)) {
            for(String line;(line=in.readLine())!=null;){String[] fields=line.strip().split(",",-1);if(fields.length!=42)throw new IllegalArgumentException("KDD99 必须有 41 个特征和标签");for(int c=0;c<3;c++){if(fields[c+1].isBlank())throw new IllegalArgumentException("KDD99 分类字段为空");vocabulary.get(c).add(fields[c+1]);}rows++;}
        }
        if(rows==0)throw new IllegalArgumentException("KDD99 数据为空");
        List<List<String>> categories=vocabulary.stream().map(s->List.copyOf(s)).toList();int features=38+categories.stream().mapToInt(List::size).sum();
        List<Map<String,Integer>> indexes=new ArrayList<>();for(List<String> category:categories){Map<String,Integer> map=new HashMap<>();for(int i=0;i<category.size();i++)map.put(category.get(i),i);indexes.add(map);}
        List<Map<String,Object>> blocks=new ArrayList<>();String[] names={"protocol_type","service","flag"};for(int i=0;i<3;i++)blocks.add(Map.of("source_column_1based",i+2,"name",names[i],"values",categories.get(i)));
        Map<String,Object> schema=new LinkedHashMap<>();schema.put("raw_features",41);schema.put("features",features);schema.put("numeric_source_columns_1based",java.util.stream.IntStream.range(0,41).filter(c->c==0 || c>3).map(c->c+1).boxed().toList());schema.put("one_hot_blocks",blocks);schema.put("label_removed","column 42; categorical class label");schema.put("row_order","unchanged");
        Map<String,String> suffixes=new HashMap<>();long written=0;
        try(Outputs out=new Outputs(output,features);BufferedReader in=open(source)) {
            for(String line;(line=in.readLine())!=null;) {
                String[] fields=line.strip().split(",",-1);if(fields.length!=42)throw new IllegalArgumentException("KDD99 列数不一致");StringBuilder numeric=new StringBuilder();
                for(int c=0;c<41;c++)if(c==0 || c>3){finite(fields[c]);if(numeric.length()>0)numeric.append(',');numeric.append(fields[c]);}
                String key=fields[1]+"/"+fields[2]+"/"+fields[3];String suffix=suffixes.get(key);
                if(suffix==null){StringBuilder hot=new StringBuilder();for(int c=0;c<3;c++){Integer selected=indexes.get(c).get(fields[c+1]);if(selected==null)throw new IllegalArgumentException("KDD99 分类字典不一致");for(int i=0;i<categories.get(c).size();i++)hot.append(',').append(i==selected?'1':'0');}suffix=hot.toString();suffixes.put(key,suffix);}
                out.row(numeric.append(suffix).toString());written++;
            }
        }
        if(written!=rows)throw new IllegalStateException("KDD99 两次读取行数不一致");Files.writeString(output.resolve("schema.json"),Json.write(schema)+"\n",StandardCharsets.UTF_8);
        Prepared result=new Prepared(rows,features,sha(source),sha(output.resolve("data.csv.gz")));writeMetadata(output,result,"38 numeric features unchanged; 3 categorical features one-hot encoded with lexicographically sorted full-source vocabularies; label removed");return result;
    }
    public static Prepared poker(List<Path> sources,Path output) throws Exception {
        long rows=0;MessageDigest sourceDigest=MessageDigest.getInstance("SHA-256");
        try(Outputs out=new Outputs(output,10)) {
            for(Path source:sources) {
                sourceDigest.update(sha(source).getBytes(StandardCharsets.UTF_8));sourceDigest.update((byte)'\n');
                try(BufferedReader in=open(source)){for(String line;(line=in.readLine())!=null;){String[] fields=line.strip().split(",",-1);if(fields.length!=11)throw new IllegalArgumentException("Poker Hand 必须有 10 个特征和标签");for(String field:fields)finite(field);out.row(String.join(",",Arrays.copyOf(fields,10)));rows++;}}
            }
        }
        if(rows==0)throw new IllegalArgumentException("Poker Hand 数据为空");
        Prepared result=new Prepared(rows,10,HexFormat.of().formatHex(sourceDigest.digest()),sha(output.resolve("data.csv.gz")));writeMetadata(output,result,"training file followed by testing file; first 10 features unchanged; label removed; used only for operator performance, not training or accuracy evaluation");return result;
    }
    private static void finite(String value){try{if(!Double.isFinite(Double.parseDouble(value)))throw new IllegalArgumentException("数据必须为有限数值");}catch(NumberFormatException e){throw new IllegalArgumentException("数据存在无效数值："+value,e);}}
    private static final class Outputs implements AutoCloseable {
        private final BufferedWriter data,small,large;private long rows;
        Outputs(Path directory,int features) throws IOException {
            if(Files.exists(directory))throw new IllegalArgumentException("处理输出目录已存在，请使用新目录");Files.createDirectories(directory);
            data=new BufferedWriter(new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(directory.resolve("data.csv.gz")),65536),StandardCharsets.UTF_8),65536);
            small=Files.newBufferedWriter(directory.resolve("quick-1024x"+features+".csv"),StandardCharsets.UTF_8);large=Files.newBufferedWriter(directory.resolve("quick-8192x"+features+".csv"),StandardCharsets.UTF_8);
        }
        void row(String line) throws IOException {data.write(line);data.newLine();if(rows<1024){small.write(line);small.newLine();}if(rows<8192){large.write(line);large.newLine();}rows++;}
        @Override public void close() throws IOException {IOException failure=null;for(Writer writer:List.of(data,small,large))try{writer.close();}catch(IOException e){if(failure==null)failure=e;else failure.addSuppressed(e);}if(failure!=null)throw failure;}
    }
    private static void writeMetadata(Path output,Prepared data,String transform) throws IOException {Files.writeString(output.resolve("preparation.json"),Json.write(Map.of("rows",data.rows(),"features",data.features(),"source_sha256",data.sourceSha256(),"output_sha256",data.outputSha256(),"transform",transform))+"\n",StandardCharsets.UTF_8);}
    private static String sha(Path file) throws Exception {MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(file)){byte[] buffer=new byte[65536];for(int n;(n=in.read(buffer))!=-1;)md.update(buffer,0,n);}return HexFormat.of().formatHex(md.digest());}
}
