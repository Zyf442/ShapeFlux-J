package io.shapeflux.compiler;

import java.util.concurrent.atomic.AtomicLong;

/** Tiled CPU lowering preserves ascending K accumulation for every output cell. */
public final class MatmulCompiler {
    public record Artifact(MatmulKernel kernel,String source,long compileNanos,int bytecodeBytes,MatmulGuard guard) {}
    private static final AtomicLong SERIAL=new AtomicLong();
    private MatmulCompiler() {}
    public static Artifact compile(MatmulGuard guard,boolean blocked) {
        long start=System.nanoTime();String simple="M_"+SERIAL.incrementAndGet();
        StringBuilder b=new StringBuilder("package io.shapeflux.generated;\npublic final class "+simple+" implements io.shapeflux.compiler.MatmulKernel {\npublic void run(double[] a,double[] b,int n,int k,int m,double[] out){\n");
        b.append("if(!(").append(guard.condition()).append(") || (long)n*k>8000000L || (long)k*m>8000000L || (long)n*m>8000000L || (long)n*k*m>500000000L || a.length!=(long)n*k || b.length!=(long)k*m || out.length!=(long)n*m || out==a || out==b)throw new IllegalArgumentException(\"Matmul shape guard failed\");\n");
        b.append("final int N=").append(guard.minN()==guard.maxN()?guard.minN():"n").append(",K=").append(guard.minK()==guard.maxK()?guard.minK():"k").append(",M=").append(guard.minM()==guard.maxM()?guard.minM():"m").append(";\n");
        if(blocked)b.append("""
            java.util.Arrays.fill(out,0.0);
            for(int ii=0;ii<N;ii+=16){
              io.shapeflux.runtime.KernelProbe.interrupted();
              for(int kk=0;kk<K;kk+=32)for(int jj=0;jj<M;jj+=32)
                for(int i=ii;i<Math.min(N,ii+16);i++)for(int q=kk;q<Math.min(K,kk+32);q++){
                  double value=a[i*K+q];
                  for(int j=jj;j<Math.min(M,jj+32);j++)out[i*M+j]+=value*b[q*M+j];
                }
            }
            """);
        else b.append("for(int i=0;i<N;i++){io.shapeflux.runtime.KernelProbe.interrupted();for(int j=0;j<M;j++){double sum=0.0;for(int q=0;q<K;q++)sum+=a[i*K+q]*b[q*M+j];out[i*M+j]=sum;}}\n");
        String source=b.append("}}\n").toString();var loaded=JavaSourceCompiler.load(simple,source,MatmulKernel.class);
        return new Artifact(loaded.instance(),source,System.nanoTime()-start,loaded.bytes(),guard);
    }
}
