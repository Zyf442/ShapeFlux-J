package io.shapeflux.compiler;

public record MatmulGuard(int minN,int maxN,int minK,int maxK,int minM,int maxM) {
    public MatmulGuard {if(minN<1 || maxN<minN || minK<1 || maxK<minK || minM<1 || maxM<minM)throw new IllegalArgumentException("Invalid matrix guard");}
    public static MatmulGuard generic(){return new MatmulGuard(1,Integer.MAX_VALUE,1,Integer.MAX_VALUE,1,Integer.MAX_VALUE);}
    public static MatmulGuard exact(int n,int k,int m){return new MatmulGuard(n,n,k,k,m,m);}
    public static MatmulGuard family(int n,int k,int m){int[] a=ShapeGuard.bounds(n,0),b=ShapeGuard.bounds(k,0),c=ShapeGuard.bounds(m,0);return new MatmulGuard(a[0],a[1],b[0],b[1],c[0],c[1]);}
    public boolean accepts(int n,int k,int m){return n>=minN && n<=maxN && k>=minK && k<=maxK && m>=minM && m<=maxM;}
    public String condition(){return "n>="+minN+" && n<="+maxN+" && k>="+minK+" && k<="+maxK+" && m>="+minM+" && m<="+maxM;}
    public String key(){return minN+".."+maxN+" / "+minK+".."+maxK+" / "+minM+".."+maxM;}
}
