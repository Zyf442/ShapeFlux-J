package io.shapeflux.experiment;

import io.shapeflux.runtime.KernelProbe;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Atomic progress snapshots are written outside engine timers; no reader sees a partial write. */
public final class ProgressIO {
    public record Update(String label,long completed,long total,long rows,int batches,String phase,int worker,int workers) {
        public String text(){String name=String.join(" / ",Arrays.stream(label.split(" / ")).map(io.shapeflux.i18n.ZhCn::name).toList());String stage=switch(phase){case "cold"->"冷请求";case "warmup"->"预热";case "measure","steady"->"正式测量";default->phase;};return name+"｜第 "+worker+"/"+workers+" 组｜"+stage+"｜"+rows+" 行，"+batches+" 批";}
        public double fraction(){return workers<1?0:Math.min(1,Math.max(0,((worker-1)+(total>0?Math.min(1,(double)completed/total):0))/workers));}
    }
    public static final class Tracker {
        private final Path file;private long last;
        public Tracker(Path directory){file=directory.resolve("progress.properties");}
        public void step(long completed,long total,long rows,int batches,String phase) throws IOException {write(completed,total,rows,batches,phase,false);}
        public void finish(long completed,long total,long rows,int batches) throws IOException {write(completed,total,rows,batches,"完成",true);}
        private void write(long completed,long total,long rows,int batches,String phase,boolean force) throws IOException {
            long now=System.nanoTime();if(!force && last!=0 && now-last<150_000_000)return;last=now;
            Properties p=new Properties();p.setProperty("completed",Long.toString(completed));p.setProperty("total",Long.toString(total));p.setProperty("rows",Long.toString(rows));p.setProperty("batches",Integer.toString(batches));p.setProperty("phase",phase);
            Path tmp=file.resolveSibling(".progress.tmp");try(Writer w=Files.newBufferedWriter(tmp,StandardCharsets.UTF_8)){p.store(w,"Live worker progress");}
            try{Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}catch(AtomicMoveNotSupportedException e){Files.move(tmp,file,StandardCopyOption.REPLACE_EXISTING);}
        }
    }
    private ProgressIO() {}
    public static Update read(Path directory,String label,int worker,int workers) throws IOException {
        Path file=directory.resolve("progress.properties");Properties p=new Properties();
        if(Files.exists(file))try(Reader reader=Files.newBufferedReader(file,StandardCharsets.UTF_8)){p.load(reader);}
        return new Update(label,Long.parseLong(p.getProperty("completed","0")),Long.parseLong(p.getProperty("total","0")),Long.parseLong(p.getProperty("rows","0")),Integer.parseInt(p.getProperty("batches","0")),p.getProperty("phase","准备编译与读取"),worker,workers);
    }
    public static void await(Process process,Path directory,int seconds,String label,int worker,int workers,Consumer<Update> listener) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);Update previous=null;
        for(;;){KernelProbe.interrupted();Update u=read(directory,label,worker,workers);if(!u.equals(previous)){listener.accept(u);previous=u;}
            if(process.waitFor(200,TimeUnit.MILLISECONDS))break;
            if(System.nanoTime()>=deadline)throw new IllegalStateException("Worker timeout; 工作进程超时："+directory.resolve("worker.log"));
        }
        listener.accept(read(directory,label,worker,workers));
    }
}
