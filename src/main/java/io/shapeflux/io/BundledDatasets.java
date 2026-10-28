package io.shapeflux.io;

import java.nio.file.Path;
import java.util.List;

/** Stable dataset IDs shared by CLI and the Chinese workbench. */
public final class BundledDatasets {
    private BundledDatasets() {}
    public record Profile(String id,String title,Path input,DatasetCsv.Label label,long rows,int features) {
        @Override public String toString(){return title;}
    }
    private static final List<Profile> ALL=List.of(
        new Profile("covertype","Covertype · 森林覆盖（581,012 行 / 54 特征）",Path.of("datasets/covertype/covtype.data.gz"),DatasetCsv.Label.LAST,581012,54),
        new Profile("kdd99","KDD99 · 网络连接（4,898,431 行 / 122 特征）",Path.of("datasets/kdd99/prepared/data.csv.gz"),DatasetCsv.Label.NONE,4898431,122),
        new Profile("poker","Poker Hand · 手牌（1,025,010 行 / 10 特征）",Path.of("datasets/poker/prepared/data.csv.gz"),DatasetCsv.Label.NONE,1025010,10)
    );
    public static List<Profile> all(){return ALL;}
    public static Profile get(String id){return ALL.stream().filter(p->p.id().equalsIgnoreCase(id)).findFirst().orElseThrow(()->new IllegalArgumentException("未知随包数据集："+id+"；可选 covertype、kdd99、poker"));}
}
