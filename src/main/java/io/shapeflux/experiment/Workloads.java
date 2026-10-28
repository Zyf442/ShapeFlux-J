package io.shapeflux.experiment;

import io.shapeflux.runtime.TensorInput;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class Workloads {
    public record Shape(int rows, int cols) {
        public Shape { if (rows < 1 || cols < 1 || (long) rows * cols > 8_000_000) throw new IllegalArgumentException("Invalid workload shape"); }
    }
    private Workloads() {}
    public static List<Shape> create(String name, boolean quick) {
        int count = quick ? 18 : 48;
        List<Shape> result = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Shape shape = switch (name) {
                case "hotset" -> new Shape(new int[]{24, 32, 40, 48, 56, 64}[i % 6], 128);
                case "jitter" -> new Shape(65 + i % (quick ? 6 : 12), 64);
                case "shift" -> i < count / 2 ? new Shape(25 + i % 3, 64) : new Shape(49 + i % 3, 128);
                case "longtail" -> i % 4 == 0 ? new Shape(17 + i, 32 + i * 3) : new Shape(i % 2 == 0 ? 32 : 48, 128);
                default -> throw new IllegalArgumentException("Unknown workload: " + name);
            };
            result.add(shape);
        }
        return List.copyOf(result);
    }
    public static List<Shape> fromCsv(Path path) throws IOException {
        if(Files.size(path)>16_000_000)throw new IllegalArgumentException("Trace CSV exceeds 16 MB");
        List<Shape> shapes=new ArrayList<>();int rowIndex=0,colIndex=1,repeatIndex=2;boolean first=true;int lineNo=0;
        for(String line:Files.readAllLines(path,StandardCharsets.UTF_8)) {
            lineNo++;line=line.replace("\ufeff","").strip();if(line.isEmpty() || line.startsWith("#"))continue;
            List<String> fields=ReportWriter.csvLine(line);
            if(first) {
                first=false;
                if(fields.stream().anyMatch(x->x.strip().equals("rows"))) {
                    var header=fields.stream().map(String::strip).toList();rowIndex=header.indexOf("rows");colIndex=header.indexOf("cols");repeatIndex=header.indexOf("repeat");
                    if(colIndex<0)throw new IllegalArgumentException("Trace requires rows and cols columns");continue;
                }
            }
            if(fields.size()<=Math.max(rowIndex,colIndex) || repeatIndex>=fields.size())throw new IllegalArgumentException("Invalid shape CSV at line "+lineNo);
            int rows=Integer.parseInt(fields.get(rowIndex).strip()),cols=Integer.parseInt(fields.get(colIndex).strip()),repeat=repeatIndex<0?1:Integer.parseInt(fields.get(repeatIndex).strip());
            if(repeat<1 || repeat>10000 || shapes.size()+repeat>10000)throw new IllegalArgumentException("Workload supports 1..10000 requests");
            Shape shape=new Shape(rows,cols);for(int i=0;i<repeat;i++)shapes.add(shape);
        }
        if (shapes.isEmpty()) throw new IllegalArgumentException("Empty workload");
        return List.copyOf(shapes);
    }
    public static List<TensorInput> inputs(List<Shape> shapes, long seed) {
        long elements = shapes.stream().mapToLong(s -> (long) s.rows * s.cols + s.cols).sum();
        if (elements > 32_000_000) throw new IllegalArgumentException("Workload data exceeds 32 million doubles; split it into smaller traces");
        List<TensorInput> result = new ArrayList<>();
        for (int i = 0; i < shapes.size(); i++) { Shape s = shapes.get(i); result.add(TensorInput.random(s.rows, s.cols, seed + i)); }
        return List.copyOf(result);
    }
    public static void writeCsv(Path path, List<Shape> shapes) throws IOException {
        StringBuilder b = new StringBuilder("rows,cols,repeat\n");
        for (Shape s : shapes) b.append(s.rows).append(',').append(s.cols).append(",1\n");
        Files.writeString(path, b, StandardCharsets.UTF_8);
    }
}
