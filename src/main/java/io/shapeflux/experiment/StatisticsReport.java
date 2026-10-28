package io.shapeflux.experiment;

import io.shapeflux.io.Json;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** A second, reproducible cross-fork view. Individual requests are never treated as independent forks. */
public final class StatisticsReport {
    private StatisticsReport() {}
    public static String generate(Path output,List<Map<String,String>> rows,String group,String... metrics) throws IOException {
        Map<String,List<Map<String,String>>> groups=new LinkedHashMap<>();
        for(var row:rows){String key=row.getOrDefault("workload","")+" / "+row.get(group);groups.computeIfAbsent(key,k->new ArrayList<>()).add(row);}
        StringBuilder csv=new StringBuilder("group,metric,forks,mean,sample_sd,ci95_low,ci95_high,coefficient_of_variation,noise_flag\n"),html=new StringBuilder("<h2>跨独立进程的95%置信区间</h2><p>Student-t区间估计各进程统计量的均值，独立样本数为fork数。假定进程统计量近似独立且均值可用t方法估计；小样本或偏斜可产生较宽区间。下界保留原计算值，不裁剪为0。CV≥10%标记为波动较大，不删除任何结果。</p><div class=\"scroll\"><table><tr><th>分组</th><th>指标</th><th>fork数</th><th>均值</th><th>95%区间</th><th>样本标准差</th><th>CV</th><th>波动提示</th></tr>");
        List<Map<String,Object>> records=new ArrayList<>();
        for(var e:groups.entrySet())for(String metric:metrics) {
            // Duplicate fork IDs would invalidate the stated independent sample count.
            if(e.getValue().stream().map(x->x.get("fork")).distinct().count()!=e.getValue().size())throw new IllegalArgumentException("Duplicate independent fork in statistics group");
            var s=Statistics.summarize(e.getValue().stream().map(x->Double.valueOf(x.get(metric))).toList());
            String flag=s.n()==1?"单次，无法估计":s.coefficientOfVariation()!=null && s.coefficientOfVariation()>=.1?"波动较大":s.n()<3?"样本偏少":"已记录";
            csv.append(quote(e.getKey())).append(',').append(metric).append(',').append(s.n()).append(',').append(s.mean()).append(',').append(value(s.sampleSd())).append(',').append(value(s.ci95Low())).append(',').append(value(s.ci95High())).append(',').append(value(s.coefficientOfVariation())).append(',').append(flag).append('\n');
            html.append("<tr><td>").append(ReportWriter.html(e.getKey())).append("</td><td>").append(metric).append("</td><td>").append(s.n()).append("</td><td>").append(fmt(s.mean())).append("</td><td>").append(s.ci95Low()==null?"无法估计":"["+fmt(s.ci95Low())+", "+fmt(s.ci95High())+"]").append("</td><td>").append(fmt(s.sampleSd())).append("</td><td>").append(s.coefficientOfVariation()==null?"—":fmt(s.coefficientOfVariation()*100)+"%").append("</td><td>").append(flag).append("</td></tr>");
            records.add(Map.of("group",e.getKey(),"metric",metric,"statistics",s,"noise_flag",flag));
        }
        html.append("</table></div><h2>系统噪声诊断</h2><p>以下为整个工作进程测量范围内的GC/JIT/CPU增量，包含预热与外部参考校验。它们辅助解释波动，不能单独证明耗时变化的原因。末尾堆使用量不是峰值或进程RSS；unsupported代表当前JVM不提供该指标。</p><div class=\"scroll\"><table><tr><th>工作进程目录</th><th>GC次数</th><th>GC毫秒</th><th>JIT毫秒</th><th>进程CPU毫秒</th><th>测量范围毫秒</th><th>末尾堆字节</th></tr>");
        StringBuilder noise=new StringBuilder("worker,gc_count,gc_ms,jit_ms,process_cpu_ms,scope_ms,heap_used_at_end_bytes\n");
        try(var paths=Files.walk(output,3)) {for(Path p:paths.filter(p->p.getFileName().toString().equals("system-metrics.properties")).sorted().toList()) {
            Properties props=new Properties();try(Reader reader=Files.newBufferedReader(p,StandardCharsets.UTF_8)){props.load(reader);}
            String worker=output.relativize(p.getParent()).toString();html.append("<tr><td>").append(ReportWriter.html(worker)).append("</td>");noise.append(quote(worker));
            for(String key:List.of("gc_count","gc_ms","jit_ms","process_cpu_ms","scope_ms","heap_used_at_end_bytes")){String v=props.getProperty(key,"unsupported");html.append("<td>").append(ReportWriter.html(v)).append("</td>");noise.append(',').append(v);}html.append("</tr>");noise.append('\n');
        }}
        html.append("</table></div><p>原始数据：confidence.csv、confidence.json、noise.csv；公式依据：<a href=\"https://itl.nist.gov/div898/handbook/eda/section3/eda352.htm\">NIST均值置信区间</a>。</p>");
        Files.writeString(output.resolve("confidence.csv"),csv,StandardCharsets.UTF_8);Files.writeString(output.resolve("confidence.json"),Json.write(Map.of("method","mean +/- t(.975,n-1)*sample_sd/sqrt(n)","independent_unit","worker fork","records",records))+"\n",StandardCharsets.UTF_8);Files.writeString(output.resolve("noise.csv"),noise,StandardCharsets.UTF_8);
        return html.toString();
    }
    public static void append(Path report,String fragment) throws IOException {String text=Files.readString(report,StandardCharsets.UTF_8);Files.writeString(report,text.replace("</body>","").replace("</html>","")+fragment+"</html>",StandardCharsets.UTF_8);}
    private static String quote(String s){return "\""+s.replace("\"","\"\"")+"\"";}
    private static String value(Double n){return n==null?"":n.toString();}
    private static String fmt(Double n){return n==null?"—":String.format(Locale.ROOT,"%.3f",n);}
}
