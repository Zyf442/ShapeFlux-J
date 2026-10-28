package io.shapeflux.experiment;

import io.shapeflux.i18n.ZhCn;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Generates an offline HTML report, Markdown, aggregate CSV and exact SVG from measured summaries. */
public final class ReportWriter {
    private ReportWriter() {}
    public static List<Map<String,String>> read(Path file) throws IOException {
        List<String> lines=Files.readAllLines(file,StandardCharsets.UTF_8);
        if(lines.size()<2)throw new IllegalArgumentException("汇总文件没有数据行");
        List<String> header=csvLine(lines.get(0));List<Map<String,String>> rows=new ArrayList<>();
        if(!header.containsAll(List.of("workload","variant","fork","cold_total_ms","steady_wall_median_us","steady_wall_p95_us","compilations","correct")))throw new IllegalArgumentException("不支持的汇总格式；请使用 ShapeFlux-J 2.0 或更新版本的汇总文件");
        for(int i=1;i<lines.size();i++)if(!lines.get(i).isBlank()) {
            List<String> fields=csvLine(lines.get(i));if(fields.size()!=header.size())throw new IllegalArgumentException("汇总文件列数错误，行："+(i+1));
            Map<String,String> row=new LinkedHashMap<>();for(int c=0;c<header.size();c++)row.put(header.get(c),fields.get(c));rows.add(row);
        }
        return rows;
    }
    public static List<String> csvLine(String line) {
        List<String> values=new ArrayList<>();StringBuilder value=new StringBuilder();boolean quoted=false;
        for(int i=0;i<line.length();i++) {char c=line.charAt(i);
            if(c=='"') {if(quoted && i+1<line.length() && line.charAt(i+1)=='"'){value.append('"');i++;}else quoted=!quoted;}
            else if(c==',' && !quoted){values.add(value.toString());value.setLength(0);}else value.append(c);
        }
        if(quoted)throw new IllegalArgumentException("CSV 引号未闭合");values.add(value.toString());return values;
    }
    public static Path generate(Path summary,Path output) throws IOException {
        List<Map<String,String>> rows=read(summary);Map<String,List<Map<String,String>>> groups=new LinkedHashMap<>();
        for(Map<String,String> row:rows)groups.computeIfAbsent(row.get("workload")+" / "+row.get("variant"),k->new ArrayList<>()).add(row);
        StringBuilder table=new StringBuilder("<table><thead><tr><th>负载 / 执行策略</th><th>独立进程重复数</th><th>冷序列均值 ± 标准差（毫秒）</th><th>完整请求中位数的均值（微秒）</th><th>完整请求第95百分位的均值（微秒）</th><th>平均编译次数</th><th>校验</th></tr></thead><tbody>");
        StringBuilder md=new StringBuilder("# ShapeFlux-J 实验报告\n\n计时来自原始 summary.csv。跨独立进程汇总的是各进程的统计量；表中标准差为样本标准差，不是置信区间。\n\n| 负载 / 策略 | 独立进程重复数 | 冷序列均值 ± 标准差 / 毫秒 | 完整请求中位数的均值 / 微秒 | 完整请求第95百分位的均值 / 微秒 | 编译次数均值 | 校验 |\n|---|---:|---:|---:|---:|---:|---|\n");
        StringBuilder aggregate=new StringBuilder("workload_variant,forks,cold_mean_ms,cold_sample_sd_ms,wall_median_mean_us,wall_p95_mean_us,compilations_mean,healthy\n");
        StringBuilder svg=new StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"1100\" height=\""+(75+groups.size()*31)+"\" viewBox=\"0 0 1100 "+(75+groups.size()*31)+"\"><rect width=\"100%\" height=\"100%\" fill=\"#101b2f\"/><g font-family=\"Microsoft YaHei,Noto Sans CJK SC,sans-serif\" fill=\"#eaf1ff\"><text x=\"24\" y=\"30\" font-size=\"18\">实测冷序列总耗时：独立进程均值（毫秒）</text>");
        double max=groups.values().stream().mapToDouble(g->mean(g,"cold_total_ms")).max().orElse(1);int index=0;
        for(var entry:groups.entrySet()) {
            List<Map<String,String>> g=entry.getValue();double cold=mean(g,"cold_total_ms"),sd=sd(g,"cold_total_ms"),wall=mean(g,"steady_wall_median_us"),p95=mean(g,"steady_wall_p95_us"),compiles=mean(g,"compilations");
            boolean healthy=g.stream().allMatch(r->"true".equals(r.get("correct")) && number(r,"compile_failures")==0 && number(r,"fallbacks")==0 && number(r,"runtime_failures")==0);
            String key=entry.getKey(),display=ZhCn.name(g.get(0).get("workload"))+" / "+ZhCn.name(g.get(0).get("variant")),label=healthy?"通过":"需检查";
            table.append("<tr><td>").append(html(display)).append("</td><td>").append(g.size()).append("</td><td>").append(format(cold)).append(" ± ").append(g.size()==1?"无法估计":format(sd)).append("</td><td>").append(format(wall)).append("</td><td>").append(format(p95)).append("</td><td>").append(format(compiles)).append("</td><td>").append(label).append("</td></tr>");
            md.append("| ").append(display.replace("|","\\|")).append(" | ").append(g.size()).append(" | ").append(format(cold)).append(" ± ").append(g.size()==1?"无法估计":format(sd)).append(" | ").append(format(wall)).append(" | ").append(format(p95)).append(" | ").append(format(compiles)).append(" | ").append(label).append(" |\n");
            aggregate.append('"').append(key.replace("\"","\"\"")).append("\",").append(g.size()).append(',').append(cold).append(',').append(g.size()==1?"":sd).append(',').append(wall).append(',').append(p95).append(',').append(compiles).append(',').append(healthy).append('\n');
            int y=58+index++*31;double width=max==0?0:cold/max*590;
            svg.append("<text x=\"24\" y=\"").append(y+13).append("\" font-size=\"12\">").append(html(display)).append("</text><rect x=\"350\" y=\"").append(y).append("\" width=\"").append(String.format(Locale.ROOT,"%.2f",width)).append("\" height=\"20\" rx=\"3\" fill=\"#36c8ac\"/><text x=\"").append(360+width).append("\" y=\"").append(y+14).append("\" font-size=\"12\">").append(format(cold)).append("</text>");
        }
        table.append("</tbody></table>");svg.append("</g></svg>");
        String note="冷总时间为 Engine.execute 的序列累计时间，包含编译、验证、探测与监测；排除 JVM 启动、解析、输入生成和外部参考校验。计算时间不代表完整请求延迟。仅一次独立进程重复时不提供方差估计；不同形状混合后的分位数代表该请求分布。";
        String html="<!doctype html><html lang=\"zh-CN\"><meta charset=\"UTF-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><title>ShapeFlux-J 实验报告</title><style>body{font:15px/1.7 system-ui,Microsoft YaHei,Noto Sans CJK SC,sans-serif;color:#eaf1ff;background:#0b1120;max-width:1280px;margin:40px auto;padding:0 24px}h1{color:#42dfc0}table{border-collapse:collapse;width:100%;font-size:13px}td,th{border-bottom:1px solid #31425f;padding:10px;text-align:left}th{color:#42dfc0}.scroll{overflow:auto}svg{max-width:100%;height:auto}.note{color:#a8bbd5}code{color:#b9a0ff}</style><h1>ShapeFlux-J 实验报告</h1><p>实际记录 "+rows.size()+" 组，聚合 "+groups.size()+" 个负载/策略组合。标准差为样本标准差；表中完整请求耗时是各独立进程统计量的均值。</p><p class=\"note\">"+note+"</p>"+svg+"<div class=\"scroll\">"+table+"</div><p class=\"note\">完整数据：同目录 summary.csv、aggregate.csv，及各工作目录 raw_requests.csv。耗时指标不能替代准确率、功耗或真实内存测量。</p></html>";
        Files.createDirectories(output);Files.writeString(output.resolve("report.html"),html,StandardCharsets.UTF_8);Files.writeString(output.resolve("report.md"),md+"\n"+note+"\n",StandardCharsets.UTF_8);
        Files.writeString(output.resolve("aggregate.csv"),aggregate,StandardCharsets.UTF_8);Files.writeString(output.resolve("summary.svg"),svg,StandardCharsets.UTF_8);StatisticsReport.append(output.resolve("report.html"),StatisticsReport.generate(output,rows,"variant","cold_total_ms","steady_wall_median_us","steady_wall_p95_us"));return output.resolve("report.html");
    }
    private static double number(Map<String,String> row,String name){String value=row.get(name);if(value==null || value.isBlank())return 0;double n=Double.parseDouble(value);if(!Double.isFinite(n))throw new IllegalArgumentException("报告指标不是有限数值："+name);return n;}
    private static double mean(List<Map<String,String>> rows,String field){return rows.stream().mapToDouble(r->number(r,field)).average().orElseThrow();}
    private static double sd(List<Map<String,String>> rows,String field){if(rows.size()<2)return 0;double mean=mean(rows,field);return Math.sqrt(rows.stream().mapToDouble(r->{double d=number(r,field)-mean;return d*d;}).sum()/(rows.size()-1));}
    private static String format(double value){return String.format(Locale.ROOT,"%.3f",value);}
    public static String html(String value){return value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&#39;");}
}
