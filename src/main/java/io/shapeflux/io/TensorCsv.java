package io.shapeflux.io;

import io.shapeflux.runtime.TensorInput;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Numeric CSV, optional comments, rectangular rows; no header is assumed. */
public final class TensorCsv {
    private TensorCsv() {}
    public static TensorInput read(Path matrix, Path biasFile) throws IOException {
        List<double[]> rows=readRows(matrix);int cols=rows.get(0).length;
        long size=(long)rows.size()*cols;
        if(size>8_000_000)throw new IllegalArgumentException("Matrix exceeds 8 million elements");
        double[] x=new double[(int)size],bias=new double[cols];
        for(int r=0;r<rows.size();r++)System.arraycopy(rows.get(r),0,x,r*cols,cols);
        if(biasFile!=null) {
            List<double[]> data=readRows(biasFile);
            if(data.size()!=1 || data.get(0).length!=cols)throw new IllegalArgumentException("Bias CSV must contain exactly one row with "+cols+" values");
            bias=data.get(0);
        }
        return new TensorInput(rows.size(),cols,x,bias);
    }
    private static List<double[]> readRows(Path file) throws IOException {
        List<double[]> rows=new ArrayList<>();long total=0;int width=-1,lineNo=0;
        try(BufferedReader reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)) {
            for(String line;(line=reader.readLine())!=null;) {
                lineNo++;if(lineNo==1)line=line.replace("\ufeff","");line=line.strip();
                if(line.isEmpty() || line.startsWith("#"))continue;
                if(line.length()>64_000_000)throw new IllegalArgumentException("CSV line is too large");
                String[] fields=line.split(",",-1);
                if(width<0)width=fields.length;
                if(fields.length!=width)throw new IllegalArgumentException("Non-rectangular CSV at line "+lineNo);
                total+=width;if(total>8_000_000)throw new IllegalArgumentException("CSV exceeds element limit");
                double[] values=new double[width];
                for(int c=0;c<width;c++)try { values[c]=Double.parseDouble(fields[c].strip()); }
                catch(NumberFormatException e) {throw new IllegalArgumentException("Invalid number at line "+lineNo+", column "+(c+1),e);}
                rows.add(values);
            }
        }
        if(rows.isEmpty())throw new IllegalArgumentException("Empty numeric CSV: "+file.getFileName());return rows;
    }
    public static void write(Path path,double[] output,int rows,int cols) throws IOException {
        if((long)rows*cols!=output.length)throw new IllegalArgumentException("Output shape mismatch");
        try(BufferedWriter writer=Files.newBufferedWriter(path,StandardCharsets.UTF_8)) {
            for(int r=0;r<rows;r++) { for(int c=0;c<cols;c++) {if(c>0)writer.write(',');writer.write(Double.toString(output[r*cols+c]));}writer.newLine(); }
        }
    }
}
