import io.shapeflux.io.*;
import io.shapeflux.runtime.TensorInput;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** java -cp dist/shapeflux-j.jar tools/PrepareCovertype.java [raw.gz] [fresh-output-folder] */
class PrepareCovertype {
    public static void main(String[] args) throws Exception {
        Path input=Path.of(args.length>0?args[0]:"datasets/covertype/covtype.data.gz"),output=Path.of(args.length>1?args[1]:"datasets/covertype/prepared");
        if(Files.exists(output))throw new IllegalArgumentException("准备目录已存在，请选择新的目录");
        Files.createDirectories(output.resolve("batches"));StringBuilder manifest=new StringBuilder("file,start_row,end_row,rows,cols,sha256\n");int index=0;long rows=0;
        try(DatasetCsv reader=new DatasetCsv(input,DatasetCsv.Label.LAST,new int[]{8192},0)) {
            for(TensorInput batch;(batch=reader.next())!=null;) {
                if(batch.cols()!=54)throw new IllegalArgumentException("Covertype 应包含 54 个特征");
                String name=String.format(Locale.ROOT,"batch-%03d-%dx%d.csv",++index,batch.rows(),batch.cols());Path file=output.resolve("batches").resolve(name);
                TensorCsv.write(file,batch.x(),batch.rows(),batch.cols());
                manifest.append("batches/").append(name).append(',').append(rows+1).append(',').append(rows+batch.rows()).append(',').append(batch.rows()).append(',').append(batch.cols()).append(',').append(sha(file)).append('\n');
                if(index==1) {
                    TensorCsv.write(output.resolve("quick-1024x54.csv"),Arrays.copyOf(batch.x(),1024*54),1024,54);
                    Files.copy(file,output.resolve("quick-8192x54.csv"));
                }
                rows+=batch.rows();
            }
        }
        if(rows!=581012)throw new IllegalArgumentException("Covertype 总行数不符："+rows);
        Files.writeString(output.resolve("batches.csv"),manifest,StandardCharsets.UTF_8);
        Files.writeString(output.resolve("preparation.json"),Json.write(Map.of("source_sha256",sha(input),"rows",rows,"features",54,"batch_count",index,"label_removed","last column","value_transform","none; decimal serialization only","row_order","original; no shuffle"))+"\n",StandardCharsets.UTF_8);
        System.out.println("已准备完整 Covertype："+rows+" 行、54 个特征、"+index+" 个批次，以及两个快速导入文件。");
    }
    private static String sha(Path file) throws Exception {MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream in=Files.newInputStream(file)){byte[] bytes=new byte[65536];for(int n;(n=in.read(bytes))!=-1;)digest.update(bytes,0,n);}return HexFormat.of().formatHex(digest.digest());}
}
