package io.shapeflux.compiler;

import io.shapeflux.ir.*;
import java.util.*;

/** Fuse scalar subgraphs between row stages; reuse row-local scratch across all N rows. */
final class RowCodeGenerator {
    private RowCodeGenerator() {}
    static String generate(Program p,String simple,ShapeGuard guard) {
        List<Node> stages=p.reachable().stream().filter(n->n.op().rowwise()).toList();
        Map<Node,Integer> slots=new IdentityHashMap<>();for(int i=0;i<stages.size();i++)slots.put(stages.get(i),i);
        StringBuilder b=new StringBuilder("package io.shapeflux.generated;\npublic final class "+simple+" implements io.shapeflux.compiler.Kernel {\n");
        b.append("public void run(double[] x,double[] bias,int rows,int cols,double[] out) {\n")
         .append("if(!(").append(guard.condition()).append(") || (long)rows*cols>8000000L || x.length!=(long)rows*cols || bias.length!=cols || out.length!=")
         .append(p.terminal().reduces()?"rows":"(long)rows*cols").append(")throw new IllegalArgumentException(\"Kernel shape guard failed\");\n")
         .append("if((long)cols*").append(stages.size()+2).append(">32000000L)throw new IllegalArgumentException(\"Row scratch budget exceeded\");\n")
         .append("double[][] t=new double[").append(stages.size()).append("][cols];double[] tail=new double[cols],finished=new double[")
         .append(p.terminal().reduces()?"1":"cols").append("];\nfor(int r=0;r<rows;r++){int offset=r*cols;\n");
        for(int s=0;s<stages.size();s++) {
            Node n=stages.get(s);b.append("for(int c=0;c<cols;c++)t[").append(s).append("][c]=e").append(s).append("(x,bias,offset+c,c,t);\n")
             .append("io.shapeflux.ir.Ops.transformRow(t[").append(s).append("],io.shapeflux.ir.Op.").append(n.op()).append(");\n");
        }
        b.append("for(int c=0;c<cols;c++)tail[c]=e").append(stages.size()).append("(x,bias,offset+c,c,t);\n")
         .append("io.shapeflux.ir.Ops.finish(tail,finished,1,cols,io.shapeflux.ir.Program.Terminal.").append(p.terminal()).append(");\n")
         .append("System.arraycopy(finished,0,out,r*finished.length,finished.length);}}\n");
        for(int i=0;i<=stages.size();i++)emit(b,i,i==stages.size()?p.output():stages.get(i).args().get(0),slots);
        return b.append("}\n").toString();
    }
    private static void emit(StringBuilder b,int index,Node target,Map<Node,Integer> slots) {
        LinkedHashSet<Node> order=new LinkedHashSet<>();visit(target,order);
        b.append("private static double e").append(index).append("(double[] x,double[] bias,int i,int c,double[][] t){\n");
        for(Node n:order)b.append("double ").append(n.name()).append("=").append(n.op().rowwise()?"t["+slots.get(n)+"][c]":CodeGenerator.expression(n)).append(";\n");
        b.append("return ").append(target.name()).append(";}\n");
    }
    private static void visit(Node n,Set<Node> order) {if(order.contains(n))return;if(!n.op().rowwise())for(Node a:n.args())visit(a,order);order.add(n);}
}
