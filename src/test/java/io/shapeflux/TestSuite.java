package io.shapeflux;

import io.shapeflux.compiler.*;
import io.shapeflux.experiment.Workloads;
import io.shapeflux.ir.*;
import io.shapeflux.runtime.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Dependency-free integration tests; checks compiler behavior rather than duplicating implementation. */
public final class TestSuite {
    private static int checks;
    private static final MemoryCompiler COMPILER=new MemoryCompiler();
    public static void main(String[] args) throws Exception {
        long start=System.nanoTime();
        syntax();optimization();goldenValues();generatedKernels();guards();runtime();adaptiveDecisions();fallbacks();concurrency();workload();
        checks+=CompletionSuite.run();checks+=ExtendedSuite.run();checks+=ChineseSuite.run();checks+=DatasetSuite.run();checks+=PreparationSuite.run();
        System.out.printf(Locale.ROOT,"通过：%d 项检查，耗时 %.2f 秒%n",checks,(System.nanoTime()-start)/1e9);
    }
    private static void check(boolean condition,String message){checks++;if(!condition)throw new AssertionError(message);}
    private static void rejects(Runnable action,String message){try{action.run();throw new AssertionError("Did not reject: "+message);}catch(IllegalArgumentException expected){checks++;}}
    private static void same(double[] expected,double[] actual,String message){check(Numerics.close(expected,actual),message+" / max error "+Numerics.maxError(expected,actual));}
    private static void syntax(){
        for(String text:Examples.all().values())check(Parser.parse(text).output()!=null,"Parse example");
        for(String bad:List.of("", "out = unknown;","out = x", "out = System.exit(0);","let x = 1; out = x;","let a = x; let a = x; out = a;","out = x; out = bias;","out = tanh(x, bias);","out = max(x);","out = 1e999;","out = x[0];","out = import(x);"))rejects(()->Parser.parse(bad),bad);
        rejects(()->Parser.parse("out = "+"(".repeat(60)+"x"+")".repeat(60)+";"),"Nesting limit");
        check(Parser.parse("// comment\nout = -.5e+1 * x + bias; # tail").reachable().size()>0,"Scientific number and comments");
        StringBuilder repeated=new StringBuilder("let a0 = x;\n");for(int i=1;i<45;i++)repeated.append("let a").append(i).append(" = a").append(i-1).append(" + a").append(i-1).append(";\n");repeated.append("out = a44;");
        check(Optimizer.optimize(Parser.parse(repeated.toString())).after()==45,"Repeated DAG does not hash recursively");
    }
    private static void optimization(){
        Optimizer.Result r=Optimizer.optimize(Parser.parse(Examples.CSE));
        check(r.folds()>=2 && r.merges()>=2 && r.after()<r.before(),"Folding, CSE, dead code");
        check(r.program().nodes().stream().noneMatch(n->n.op()==Op.EXP),"Dead exp eliminated");
        Program p=Parser.parse("out = x * 0.0 + 0.0;");check(Optimizer.optimize(p).program().nodes().stream().anyMatch(n->n.op()==Op.MUL),"No unsafe x*0 folding");
        Program a=Optimizer.optimize(Parser.parse("out = x + 1;")).program(),b=Optimizer.optimize(Parser.parse("out = x + 2;")).program();
        check(!a.fingerprint().equals(b.fingerprint()),"Program fingerprint includes constants");
        check(!Numerics.close(new double[]{1},new double[]{Double.NaN}),"NaN must not pass finite comparison");
        check(!Numerics.close(new double[]{0.0},new double[]{-0.0}),"Signed zero preserved");
        check(Numerics.close(new double[]{Double.NaN,Double.POSITIVE_INFINITY},new double[]{Double.NaN,Double.POSITIVE_INFINITY}),"Matching nonfinite outputs");
    }
    private static void generatedKernels(){
        List<String> programs=new ArrayList<>(Examples.all().values());
        programs.add("out = min(max(abs(-x), bias), 3) / 2 - log(abs(x) + 1) + exp(x * 0.1);");
        programs.add("out = 0.0 * x;");programs.add("out = 1.0 / x;");programs.add("out = -0.0;");
        for(String text:programs){
            Program original=Parser.parse(text),p=Optimizer.optimize(original).program();CompiledKernel generic=COMPILER.compile(p,ShapeGuard.generic(),true);
            check(generic.bytecodeBytes()>0 && generic.source().contains("implements io.shapeflux.compiler.Kernel"),"Real bytecode generated");
            for(int[] shape:new int[][]{{1,1},{3,7},{5,16},{33,19}}){
                TensorInput input=TensorInput.random(shape[0],shape[1],37);double[] out=new double[p.outputSize(shape[0],shape[1])];generic.kernel().run(input.x(),input.bias(),input.rows(),input.cols(),out);same(Interpreter.run(original,input),out,"Generic compiled " + shape[0]+"x"+shape[1]);
                if(p.terminal()==Program.Terminal.SOFTMAX)for(int row=0;row<input.rows();row++){double sum=0;for(int col=0;col<input.cols();col++)sum+=out[row*input.cols()+col];check(Math.abs(sum-1)<1e-12,"Softmax row sums");}
            }
            TensorInput edge=new TensorInput(1,7,new double[]{-0.0,0.0,Double.NaN,Double.NEGATIVE_INFINITY,Double.POSITIVE_INFINITY,-1000,1000},new double[7]);
            double[] output=new double[p.outputSize(1,7)];generic.kernel().run(edge.x(),edge.bias(),1,7,output);same(Interpreter.run(original,edge),output,"IEEE edge semantics");
        }
    }
    private static void goldenValues(){
        TensorInput in=new TensorInput(2,3,new double[]{-2,1,3,0,-1,2},new double[]{1,-2,.5});
        Program p=Parser.parse("out = relu(x + bias) * 2;");
        double[] expected={0,0,7,2,0,5};
        same(expected,Interpreter.run(p,in),"Hand-calculated arithmetic / broadcasting");
        CompiledKernel k=COMPILER.compile(Optimizer.optimize(p).program(),ShapeGuard.exact(2,3),true);double[] out=new double[6];k.kernel().run(in.x(),in.bias(),2,3,out);same(expected,out,"Compiled hand-calculated arithmetic");
        Program sum=Parser.parse("out = rowsum(x + bias);");same(new double[]{1.5,0.5},Interpreter.run(sum,in),"Hand-calculated row sums");
        TensorInput zeros=new TensorInput(1,3,new double[3],new double[3]);same(new double[]{1.0/3,1.0/3,1.0/3},Interpreter.run(Parser.parse("out = softmax(x);"),zeros),"Known uniform softmax");
        check(Ops.sigmoid(-1000)==0 && Ops.sigmoid(1000)==1,"Stable sigmoid endpoints");
    }
    /** Artificial delays are test doubles for policy branches only; never used in benchmark data. */
    private static void adaptiveDecisions(){
        Program p=Parser.parse("out = relu(x + bias);");
        for(boolean makeCandidateFaster:new boolean[]{true,false}){
            KernelCompiler controlled=(program,guard,unroll)->{
                CompiledKernel real=COMPILER.compile(program,guard,unroll);
                boolean delayed=makeCandidateFaster?guard.isGeneric():!guard.isGeneric();
                Kernel kernel=(x,bias,rows,cols,out)->{if(delayed)java.util.concurrent.locks.LockSupport.parkNanos(100_000);real.kernel().run(x,bias,rows,cols,out);};
                return new CompiledKernel(kernel,real.source(),real.compileNanos(),real.bytecodeBytes(),guard);
            };
            Engine e=new Engine(p,Strategy.ADAPTIVE,new EngineConfig(2,2,1_000_000_000L,true,true,Integer.MAX_VALUE),controlled);
            for(int rows:new int[]{33,34,35}){TensorInput in=TensorInput.random(rows,16,rows);same(Interpreter.run(p,in),e.execute(in).output(),"Adaptive policy correctness");}
            check(e.events().stream().anyMatch(x->x.type().equals("PROBE")),"Adaptive candidate was measured");
            if(makeCandidateFaster)check(e.stats().promotions()==1 && e.stats().cachedSpecializations()==1,"Measured faster candidate admitted");
            else check(e.stats().rejections()==1 && e.stats().cachedSpecializations()==0,"Measured slower candidate rejected");
        }
    }
    private static void guards(){
        Program p=Optimizer.optimize(Parser.parse(Examples.REDUCTION)).program();
        for(int rows:new int[]{1,2,3,32,33,64,65,127}){
            ShapeGuard g=ShapeGuard.family(rows,19);check(g.accepts(rows,19),"Family covers original shape");check(!g.accepts(rows,20),"Width guard");check(!g.accepts(g.maxRows()+1,19),"Upper row guard");
        }
        check(ShapeGuard.family(Integer.MAX_VALUE,1).accepts(Integer.MAX_VALUE,1),"Guard power-of-two overflow boundary");
        ShapeGuard guard=ShapeGuard.family(40,19);CompiledKernel k=COMPILER.compile(p,guard,true);
        for(int rows:new int[]{33,40,64}){TensorInput input=TensorInput.random(rows,19,rows);double[] out=new double[rows];k.kernel().run(input.x(),input.bias(),rows,19,out);same(Interpreter.run(p,input),out,"Unrolled reduction with remainder");}
        rejects(()->k.kernel().run(new double[65*19],new double[19],65,19,new double[65]),"Compiled guard enforces upper bound");
        rejects(()->k.kernel().run(new double[40*20],new double[20],40,20,new double[40]),"Compiled guard enforces cols");
        rejects(()->k.kernel().run(new double[40*19],new double[19],40,19,new double[39]),"Compiled output length validation");
        CompiledKernel exact=COMPILER.compile(p,ShapeGuard.exact(3,7),true);rejects(()->exact.kernel().run(new double[28],new double[7],4,7,new double[4]),"Exact shape guard");
        rejects(()->new TensorInput(2,3,new double[6],new double[2]),"Bias length validation");
    }
    private static void runtime(){
        Program p=Parser.parse(Examples.ACTIVATION);
        for(Strategy strategy:Strategy.values()){
            Engine e=new Engine(p,strategy,new EngineConfig(2,2,0,true,true,256));
            for(int rows:new int[]{17,18,33,34,17,18}){TensorInput input=TensorInput.random(rows,9,rows);same(Interpreter.run(p,input),e.execute(input).output(),strategy+" runtime");}
            Engine.Stats s=e.stats();check(s.requests()==6 && s.hits()+s.misses()==6,"Counter conservation");
            if(strategy==Strategy.EXACT)check(s.evictions()>0 && s.cachedSpecializations()==2,"LRU bound and eviction");
            if(strategy==Strategy.BUCKET)check(s.compilations()==3,"Two row families + pinned generic");
            if(strategy==Strategy.ADAPTIVE)check(s.compilations()==1 && s.cachedSpecializations()==0,"Zero specialization budget retains generic");
            if(strategy!=Strategy.INTERPRETED)check(s.compileFailures()==0,"No hidden fallback");
        }
        Engine e=new Engine(p,Strategy.EXACT,new EngineConfig(1,2,0,true,true,256));
        for(int rows:new int[]{3,4,3})e.execute(TensorInput.random(rows,8,1));
        check(e.stats().compilations()==4 && e.stats().evictions()==2,"Evicted exact kernel may recompile");
    }
    private static void fallbacks(){
        Program p=Parser.parse(Examples.CSE);TensorInput input=TensorInput.random(4,9,1);
        KernelCompiler unavailable=(program,guard,unroll)->{throw new IllegalStateException("simulated unavailable compiler");};
        Engine e=new Engine(p,Strategy.ADAPTIVE,EngineConfig.defaults(),unavailable);
        same(Interpreter.run(p,input),e.execute(input).output(),"Compiler failure falls back");e.execute(input);
        check(e.stats().fallbacks()==2 && e.stats().compileFailures()==1,"Failed generic compiler is not retried each request");
        KernelCompiler bad=(program,guard,unroll)->new CompiledKernel((x,bias,rows,cols,out)->Arrays.fill(out,Double.NaN),"",1,1,guard);
        Engine wrong=new Engine(p,Strategy.GENERIC,EngineConfig.defaults(),bad);same(Interpreter.run(p,input),wrong.execute(input).output(),"Bad generated kernel validation fallback");check(wrong.stats().compileFailures()==1,"Bad kernel rejected");
        KernelCompiler brokenSpecial=(program,guard,unroll)->{if(!guard.isGeneric())throw new IllegalStateException("simulated specialization failure");return COMPILER.compile(program,guard,unroll);};
        Engine special=new Engine(p,Strategy.EXACT,EngineConfig.defaults(),brokenSpecial);
        same(Interpreter.run(p,input),special.execute(input).output(),"Specialization failure retains generic");special.execute(input);
        check(special.stats().compileFailures()==1 && special.stats().fallbacks()==0,"Generic remains after specialization failure");
    }
    private static void concurrency() throws Exception {
        Program p=Parser.parse(Examples.CSE);Engine e=new Engine(p,Strategy.GENERIC,EngineConfig.defaults());ExecutorService pool=Executors.newFixedThreadPool(4);
        try{List<Future<Boolean>> futures=new ArrayList<>();for(int i=0;i<12;i++){final int n=i+1;futures.add(pool.submit(()->{TensorInput in=TensorInput.random(n,9,n);return Numerics.close(Interpreter.run(p,in),e.execute(in).output());}));}for(Future<Boolean> f:futures)check(f.get(),"Concurrent request correctness");}
        finally{pool.shutdown();}
        check(e.stats().requests()==12 && e.stats().compilations()==1,"Concurrent generic compile deduplicated");
    }
    private static void workload() throws Exception {
        Path path=Files.createTempFile("shapeflux-workload-",".csv");
        try{Files.writeString(path,"rows,cols,repeat\n3,7,2\n4,8,1\n");List<Workloads.Shape> shapes=Workloads.fromCsv(path);check(shapes.size()==3,"Trace repeat expansion");same(Workloads.inputs(shapes,9).get(0).x(),Workloads.inputs(shapes,9).get(0).x(),"Seed reproducibility");}
        finally{Files.deleteIfExists(path);}
    }
}
