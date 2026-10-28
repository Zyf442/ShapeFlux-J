package io.shapeflux;

import io.shapeflux.compiler.*;
import io.shapeflux.ir.*;
import io.shapeflux.runtime.*;
import io.shapeflux.experiment.*;
import io.shapeflux.io.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Independent examples and boundary checks for the 2.4 features. */
final class CompletionSuite {
    private static int checks;
    private static final MemoryCompiler COMPILER=new MemoryCompiler();
    static int run() throws Exception {
        composedRows();twoAxisGuards();reuse();matrix();statistics();files();return checks;
    }
    private static void check(boolean ok,String why){checks++;if(!ok)throw new AssertionError(why);}
    private static void same(double[] expected,double[] actual,String why){check(Numerics.close(expected,actual),why+" / "+Numerics.maxError(expected,actual));}
    private static void rejects(Runnable task,String why){try{task.run();throw new AssertionError(why);}catch(IllegalArgumentException expected){checks++;}}
    private static void composedRows() {
        TensorInput in=new TensorInput(2,3,new double[]{1,2,3,4,5,6},new double[3]);
        Map<String,double[]> golden=new LinkedHashMap<>();
        golden.put("let centered=x-rowmean(x);out=rowmean(square(centered));",new double[]{2.0/3,2.0/3});
        golden.put("out=rowvar(x);",new double[]{2.0/3,2.0/3});
        golden.put("out=rowmax(x);",new double[]{3,6});
        golden.put("out=rowmin(x);",new double[]{1,4});
        golden.put("let s=rowsum(2);out=s;",new double[]{6,6});
        golden.put("let s=rowsum(2);out=x+s;",new double[]{7,8,9,10,11,12});
        golden.put("let p=softmax(x);out=rowsum(p);",new double[]{1,1});
        golden.put("out=rowsum(softmax(x));",new double[]{1,1});
        double den=1+Math.exp(1)+Math.exp(2);
        double[] plus=new double[]{1+1/den,1+Math.exp(1)/den,1+Math.exp(2)/den,1+1/den,1+Math.exp(1)/den,1+Math.exp(2)/den};
        golden.put("out=softmax(x)+1;",plus);
        for(var entry:golden.entrySet()) {
            Program p=Parser.parse(entry.getKey());same(entry.getValue(),Interpreter.run(p,in),"Hand calculated composed reference: "+entry.getKey());
            CompiledKernel k=COMPILER.compile(Optimizer.optimize(p).program(),ShapeGuard.generic(),true);
            double[] out=new double[p.outputSize(2,3)];k.kernel().run(in.x(),in.bias(),2,3,out);same(entry.getValue(),out,"Hand calculated composed bytecode");
        }
        for(String text:List.of(Examples.all().get("Composed stable softmax"),"let a=rowmean(x);let b=rowsum(x-a);out=b+x;","let a=layernorm(x);out=rmsnorm(a+x);","out=sin(x)+cos(x)+expm1(x*.01)+log1p(abs(x))+pow(x,2)+floor(x)+ceil(x);")) {
            Program p=Parser.parse(text);var optimized=Optimizer.optimize(p).program();
            for(int[] shape:new int[][]{{1,1},{3,7},{33,65},{40,70},{64,96}}) {
                TensorInput data=TensorInput.random(shape[0],shape[1],38);
                var kernel=COMPILER.compile(optimized,ShapeGuard.family(data.rows(),data.cols(),32,32),true);
                double[] out=new double[p.outputSize(data.rows(),data.cols())];kernel.kernel().run(data.x(),data.bias(),data.rows(),data.cols(),out);
                same(Interpreter.run(p,data),out,"Composed stages across dynamic N and D");
            }
        }
        Program stable=Parser.parse(Examples.all().get("Composed stable softmax"));
        TensorInput extreme=new TensorInput(1,3,new double[]{-1000,-1000,-1000},new double[3]);
        same(new double[]{1.0/3,1.0/3,1.0/3},Interpreter.run(stable,extreme),"Stable composed softmax avoids underflow denominator");
        var k=COMPILER.compile(Optimizer.optimize(stable).program(),ShapeGuard.generic(),true);double[] out=new double[3];k.kernel().run(extreme.x(),extreme.bias(),1,3,out);same(new double[]{1.0/3,1.0/3,1.0/3},out,"Stable generated softmax known values");
        Program constantSum=Optimizer.optimize(Parser.parse("let a=rowsum(2);out=x+a;")).program();
        check(constantSum.reachable().stream().anyMatch(n->n.op()==Op.ROWSUM),"Dimension-dependent row sum is never folded as a scalar");
    }
    private static void twoAxisGuards() {
        var guard=ShapeGuard.family(40,70,32,32);
        check(guard.minRows()==33 && guard.maxRows()==64 && guard.minCols()==65 && guard.maxCols()==96,"Two-axis aligned region");
        for(int n:new int[]{33,40,64})for(int d:new int[]{65,70,96})check(guard.accepts(n,d),"Region covers all boundary corners");
        for(int[] s:new int[][]{{32,70},{65,70},{40,64},{40,97}})check(!guard.accepts(s[0],s[1]),"Region excludes outside boundary");
        Program p=Parser.parse("out=rowsum(relu(x+bias));");
        Engine engine=new Engine(p,Strategy.BUCKET,EngineConfig.defaults().withGranularity(32,32));
        for(int[] s:new int[][]{{33,65},{40,70},{64,96}}){var in=TensorInput.random(s[0],s[1],9);same(Interpreter.run(p,in),engine.execute(in).output(),"Two-axis cached kernel");}
        check(engine.stats().compilations()==2,"One generic plus one N/D-family kernel");
        var kernel=COMPILER.compile(Optimizer.optimize(p).program(),guard,true);
        rejects(()->kernel.kernel().run(new double[40*97],new double[97],40,97,new double[40]),"Generated code checks column upper bound");
        check(ShapeGuard.family(Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE,Integer.MAX_VALUE).accepts(Integer.MAX_VALUE,Integer.MAX_VALUE),"Granularity arithmetic avoids integer overflow");
        rejects(()->new ShapeGuard(1,4,0,5,5),"Inconsistent fixed width rejected");
    }
    private static void reuse() {
        ReuseWindow w=new ReuseWindow(4);
        check(w.observe("A",10).predictedUses()==10,"Initial observed frequency");
        check(w.observe("A",10).observed()==2,"Repeated hot family");
        check(Math.abs(w.observe("B",10).predictedUses()-10.0/3)<1e-12,"Estimate responds to mixed arrivals");
        check(w.observe("B",10).observed()==2,"Frequency in bounded window");
        w.observe("B",10);w.observe("B",10);
        var returned=w.observe("A",10);
        check(returned.observed()==1 && returned.gapRequests()==0,"Expired family loses old frequency and interval");
        check(returned.predictedUses()==2.5 && w.retainedRequests()==4,"Predictions use current window rather than lifetime counts");
        for(int i=0;i<1000;i++)w.observe("shape-"+i,100);
        check(w.retainedRequests()==4,"Window storage is bounded under high cardinality");
    }
    private static void matrix() {
        MatrixInput in=new MatrixInput(2,3,2,new double[]{1,2,3,4,5,6},new double[]{7,8,9,10,11,12});
        double[] golden={58,64,139,154};same(golden,in.reference(),"Independent hand-calculated matrix product");
        for(var mode:MatmulEngine.Mode.values())for(boolean exact:new boolean[]{false,true}) {
            MatmulEngine e=new MatmulEngine(mode,exact,2);same(golden,e.execute(in).output(),"Known generated matrix product");same(golden,e.execute(in).output(),"Known cached matrix product");
            check(e.compilations()==(mode==MatmulEngine.Mode.REFERENCE?0:1),"Matrix cache compiles once");
        }
        MatmulEngine e=new MatmulEngine(MatmulEngine.Mode.BLOCKED,false,2);
        for(int[] shape:new int[][]{{1,1,1},{3,7,5},{17,33,35},{32,64,64},{23,47,51},{9,9,9}}) {
            MatrixInput data=MatrixInput.random(shape[0],shape[1],shape[2],193);
            same(data.reference(),e.execute(data).output(),"Blocked remainder and K ordering");check(e.artifacts().size()<=2,"Matrix LRU remains bounded");
        }
        check(e.evictions()>0,"Matrix cache eviction exercised");
        var artifact=MatmulCompiler.compile(MatmulGuard.exact(2,3,2),true);
        rejects(()->artifact.kernel().run(new double[9],new double[6],3,3,2,new double[6]),"Three-axis compiled guard rejects N mismatch");
        rejects(()->artifact.kernel().run(in.a(),in.b(),2,3,2,in.a()),"In-place matrix output is rejected");
        rejects(()->new MatrixInput(2,3,2,new double[5],new double[6]),"Matrix dimensions checked before use");
        MatrixInput edge=new MatrixInput(1,3,2,new double[]{-0.0,Double.POSITIVE_INFINITY,Double.NaN},new double[]{-1,1,0,2,3,4});
        same(edge.reference(),new MatmulEngine(MatmulEngine.Mode.BLOCKED,true,1).execute(edge).output(),"Matrix NaN and infinity semantics");
    }
    private static void statistics() {
        var s=Statistics.summarize(List.of(1.0,2.0,3.0,4.0,5.0));
        check(s.n()==5 && s.mean()==3 && Math.abs(s.sampleSd()-Math.sqrt(2.5))<1e-12,"Known sample mean and unbiased standard deviation");
        check(Math.abs(s.ci95Low()-1.0367568385)<1e-8 && Math.abs(s.ci95High()-4.9632431615)<1e-8,"Known df=4 Student-t interval");
        var one=Statistics.summarize(List.of(100.0));check(one.ci95Low()==null && one.sampleSd()==null,"Single fork cannot estimate variance");
        var two=Statistics.summarize(List.of(10.0,30.0));check(two.ci95Low()<0 && two.ci95High()>140,"Negative t interval bound is retained");
        rejects(()->Statistics.summarize(List.of(Double.NaN)),"Nonfinite metrics rejected");
        rejects(()->Statistics.summarize(List.of()),"Empty statistics rejected");
    }
    private static void files() throws Exception {
        Path root=Files.createTempDirectory("shapeflux-completion-");
        try {
            EngineConfig c=new EngineConfig(3,2,7_000_000,true,false,23,9,false,5,32,64,16);
            check(EngineOptions.decode(EngineOptions.encode(c)).equals(c),"Extended configuration encoding");
            check(EngineOptions.decode("3,2,7000000,true,false,23,9,false,5").reuseWindow()==128,"Legacy configuration decoding");
            ProjectFile.Project p=new ProjectFile.Project("out=x-rowmean(x);",Strategy.ADAPTIVE,c,33,65,"jitter");
            ProjectFile.save(root.resolve("test.sfxproj"),p);check(ProjectFile.load(root.resolve("test.sfxproj")).equals(p),"Extended project round trip");
            Path request=root.resolve("batches.csv");Files.writeString(request,"batch,start_row,end_row,rows,cols,selected\n1,1,3,3,7,generic\n2,4,7,4,9,generic\n");
            check(Workloads.fromCsv(request).equals(List.of(new Workloads.Shape(3,7),new Workloads.Shape(4,9))),"Direct import of actual batch CSV");
            Path report=TraceAnalyzer.analyze(request,root.resolve("trace"),c);
            check(Files.isRegularFile(report) && Files.readString(report.resolveSibling("reuse.csv")).split("\n").length==3,"Trace analysis saves both real observations");
            var tracker=new ProgressIO.Tracker(root);tracker.step(0,100,0,0,"准备");tracker.finish(100,100,100,4);
            var u=ProgressIO.read(root,"测试",2,5);check(u.completed()==100 && u.fraction()==.4 && u.batches()==4,"Atomic progress snapshot and group fraction");
            check(new ProgressIO.Update("REFERENCE",1,2,2,1,"warmup",1,1).text().contains("参考矩阵乘法") && new ProgressIO.Update("REFERENCE",1,2,2,1,"warmup",1,1).text().contains("预热"),"Progress identifiers render in Chinese");
            check(!Files.exists(root.resolve(".progress.tmp")),"Atomic snapshot leaves no partial file");
            var entries=ExperimentIndex.scan(root);
            check(entries.size()==1 && entries.get(0).category().equals("轨迹分析") && entries.get(0).state().equals("COMPLETE"),"Experiment browser reads canonical state");
            Path index=ExperimentIndex.writeHtml(root,root.resolve("index.html"));String html=Files.readString(index);
            check(html.contains("href='trace/report.html'") && !html.contains("href=\\\""),"Portable report link and valid HTML attributes");
            var noise=SystemMetrics.begin();noise.save(root);
            check(Files.readString(root.resolve("system-metrics.properties")).contains("heap_used_at_end_bytes"),"Diagnostic scope and end-heap measurement saved");
        } finally {try(var paths=Files.walk(root)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
}
