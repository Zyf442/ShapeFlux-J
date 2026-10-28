package io.shapeflux.runtime;

import java.util.*;

public record MatrixInput(int n,int k,int m,double[] a,double[] b) {
    public MatrixInput {
        Objects.requireNonNull(a);Objects.requireNonNull(b);
        if(n<1 || k<1 || m<1 || (long)n*k>8_000_000 || (long)k*m>8_000_000 || (long)n*m>8_000_000
                || (long)n*k*m>500_000_000 || a.length!=(long)n*k || b.length!=(long)k*m)
            throw new IllegalArgumentException("Invalid matrix multiplication shape or work budget");
    }
    public static MatrixInput random(int n,int k,int m,long seed) {
        if(n<1 || k<1 || m<1 || (long)n*k>8_000_000 || (long)k*m>8_000_000 || (long)n*m>8_000_000 || (long)n*k*m>500_000_000)throw new IllegalArgumentException("Invalid matrix multiplication shape or work budget");
        var rng=new SplittableRandom(seed);double[] a=new double[n*k],b=new double[k*m];
        for(int i=0;i<a.length;i++)a[i]=rng.nextDouble(-1,1);for(int i=0;i<b.length;i++)b[i]=rng.nextDouble(-1,1);
        return new MatrixInput(n,k,m,a,b);
    }
    /** Independent i,j,k reference; no reassociation or parallel reduction. */
    public static void reference(double[] a,double[] b,int n,int k,int m,double[] out) {
        new MatrixInput(n,k,m,a,b);if(out.length!=(long)n*m || out==a || out==b)throw new IllegalArgumentException("Invalid matmul output");
        for(int i=0;i<n;i++){KernelProbe.interrupted();for(int j=0;j<m;j++){double sum=0;for(int q=0;q<k;q++)sum+=a[i*k+q]*b[q*m+j];out[i*m+j]=sum;}}
    }
    public double[] reference(){double[] out=new double[n*m];reference(a,b,n,k,m,out);return out;}
}
