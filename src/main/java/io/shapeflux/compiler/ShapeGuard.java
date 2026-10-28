package io.shapeflux.compiler;

/** Bounds for both matrix axes. A zero fixedCols means a dynamic column loop. */
public record ShapeGuard(int minRows, int maxRows, int fixedCols, int minCols, int maxCols) {
    public ShapeGuard {
        if (minRows < 1 || maxRows < minRows || minCols < 1 || maxCols < minCols
                || fixedCols != (minCols == maxCols ? minCols : 0)) throw new IllegalArgumentException("Invalid guard");
    }
    public ShapeGuard(int minRows, int maxRows, int fixedCols) {
        this(minRows,maxRows,fixedCols,fixedCols==0?1:fixedCols,fixedCols==0?Integer.MAX_VALUE:fixedCols);
    }
    public static ShapeGuard generic() { return new ShapeGuard(1,Integer.MAX_VALUE,0); }
    public static ShapeGuard exact(int rows,int cols) { return new ShapeGuard(rows,rows,cols); }
    public static ShapeGuard family(int rows,int cols) { return family(rows,cols,0,0); }
    /** rowSpan=0 uses powers of two; colSpan=0 retains an exact feature width. */
    public static ShapeGuard family(int rows,int cols,int rowSpan,int colSpan) {
        if(rows<1 || cols<1 || rowSpan<0 || colSpan<0)throw new IllegalArgumentException("Invalid shape or granularity");
        int[] r=bounds(rows,rowSpan),c=colSpan==0?new int[]{cols,cols}:bounds(cols,colSpan);
        return new ShapeGuard(r[0],r[1],c[0]==c[1]?c[0]:0,c[0],c[1]);
    }
    public static int[] bounds(int value,int span) {
        if(value<1 || span<0)throw new IllegalArgumentException("Invalid axis");
        if(span>0){long low=((long)value-1)/span*span+1;return new int[]{(int)low,(int)Math.min(Integer.MAX_VALUE,low+span-1)};}
        int high=1;while(high<value && high<(1<<30))high<<=1;
        return high<value?new int[]{(1<<30)+1,Integer.MAX_VALUE}:new int[]{high==1?1:(high>>>1)+1,high};
    }
    public boolean accepts(int rows,int cols) { return rows>=minRows && rows<=maxRows && cols>=minCols && cols<=maxCols; }
    public boolean isGeneric() { return minRows==1 && maxRows==Integer.MAX_VALUE && minCols==1 && maxCols==Integer.MAX_VALUE; }
    public boolean isExact() { return fixedCols>0 && minRows==maxRows; }
    public String key() { return isGeneric()?"generic":minRows+".."+maxRows+"x"+(fixedCols>0?fixedCols:minCols+".."+maxCols); }
    public String condition() { return "rows >= "+minRows+" && rows <= "+maxRows+" && cols >= "+minCols+" && cols <= "+maxCols; }
}
