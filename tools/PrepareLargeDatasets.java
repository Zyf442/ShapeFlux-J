import io.shapeflux.io.DatasetPreparation;
import java.nio.file.*;
import java.util.List;

/** java -cp dist/shapeflux-j.jar tools/PrepareLargeDatasets.java */
class PrepareLargeDatasets {
    public static void main(String[] args) throws Exception {
        Path root=Path.of("datasets");
        var kdd=DatasetPreparation.kdd(root.resolve("kdd99/kddcup.data.gz"),root.resolve("kdd99/prepared"));
        if(kdd.rows()!=4898431 || kdd.features()!=122)throw new IllegalStateException("KDD99 全量规模不符："+kdd);
        System.out.println("KDD99 全量处理完成："+kdd);
        var poker=DatasetPreparation.poker(List.of(root.resolve("poker/poker-hand-training-true.data"),root.resolve("poker/poker-hand-testing.data")),root.resolve("poker/prepared"));
        if(poker.rows()!=1025010 || poker.features()!=10)throw new IllegalStateException("Poker Hand 全量规模不符："+poker);
        System.out.println("Poker Hand 全量处理完成："+poker);
    }
}
