package io.shapeflux.io;

import io.shapeflux.runtime.KernelProbe;
import io.shapeflux.runtime.TensorInput;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.zip.GZIPInputStream;

/** Streams a numeric CSV or gzip CSV. At most one input batch is retained. */
public final class DatasetCsv implements AutoCloseable {
    public enum Label { NONE, FIRST, LAST }
    private final BufferedReader reader;
    private final Label label;
    private final int[] sizes;
    private final long maxRows;
    private long rowsRead,line;
    private int columns=-1,batch;
    public DatasetCsv(Path input,Label label,int[] sizes,long maxRows) throws IOException {
        Objects.requireNonNull(label);Objects.requireNonNull(sizes);
        if(sizes.length==0 || sizes.length>256 || Arrays.stream(sizes).anyMatch(n->n<1 || n>8_000_000) || maxRows<0)
            throw new IllegalArgumentException("数据集批次大小或行数上限无效");
        this.label=label;this.sizes=sizes.clone();this.maxRows=maxRows;
        InputStream stream=Files.newInputStream(input);
        try {
            if(input.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gz"))stream=new GZIPInputStream(stream,65536);
            reader=new BufferedReader(new InputStreamReader(stream,StandardCharsets.UTF_8),65536);
        }catch(IOException | RuntimeException e){stream.close();throw e;}
    }
    public static int[] sizes(String text) {
        try{return Arrays.stream(text.split(",",-1)).map(String::strip).mapToInt(Integer::parseInt).toArray();}
        catch(NumberFormatException e){throw new IllegalArgumentException("批次行数必须是逗号分隔的正整数",e);}
    }
    public long rowsRead(){return rowsRead;}
    public int columns(){return columns;}
    public TensorInput next() throws IOException {
        KernelProbe.interrupted();if(maxRows>0 && rowsRead>=maxRows)return null;
        int target=sizes[batch%sizes.length];if(maxRows>0)target=(int)Math.min(target,maxRows-rowsRead);
        double[] values=null;int count=0;
        while(count<target) {
            KernelProbe.interrupted();String text=reader.readLine();if(text==null)break;
            line++;if(line==1)text=text.replace("\ufeff","");text=text.strip();
            if(text.isEmpty() || text.startsWith("#"))continue;
            if(text.length()>64_000_000)throw new IllegalArgumentException("数据集单行超过长度上限，行："+line);
            String[] fields=text.split(",",-1);int width=fields.length-(label==Label.NONE?0:1);
            if(width<1)throw new IllegalArgumentException("删除标签后没有数值特征，行："+line);
            if(columns<0)columns=width;
            if(width!=columns)throw new IllegalArgumentException("数据集各行列数不一致，行："+line);
            if((long)target*columns>8_000_000)throw new IllegalArgumentException("单批超过 800 万元素，请减少批次行数");
            if(values==null)values=new double[target*columns];
            int destination=count*columns;
            for(int c=0;c<fields.length;c++) {
                double value;
                try{value=Double.parseDouble(fields[c].strip());}
                catch(NumberFormatException e){throw new IllegalArgumentException("数据集数值无效，行："+line+"，列："+(c+1),e);}
                if(!Double.isFinite(value))throw new IllegalArgumentException("数据集包含非有限数值，行："+line+"，列："+(c+1));
                if((label==Label.FIRST && c==0) || (label==Label.LAST && c==fields.length-1))continue;
                values[destination++]=value;
            }
            rowsRead++;count++;
        }
        if(count==0)return null;batch++;
        if(count<target)values=Arrays.copyOf(values,count*columns);
        return new TensorInput(count,columns,values,new double[columns]);
    }
    @Override public void close() throws IOException {reader.close();}
}
