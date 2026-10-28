package io.shapeflux.experiment;

import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.*;
import java.util.*;
import java.util.regex.*;

/** Reads small metadata files, never large numeric CSV data. */
public final class ExperimentIndex {
    public record Entry(Path directory,String name,String category,String state,String created,String javaVersion,String completed,boolean reportAvailable) {}
    private ExperimentIndex() {}
    public static List<Entry> scan(Path root) throws IOException {
        if(!Files.isDirectory(root))return List.of();List<Entry> entries=new ArrayList<>();
        try(var paths=Files.walk(root,3)) {
            for(Path p:paths.filter(p->p.getFileName().toString().equals("metadata.json") && Files.isRegularFile(p)).limit(1000).toList()) {
                if(Files.size(p)>256_000)continue;
                String json=Files.readString(p,StandardCharsets.UTF_8);Path directory=p.getParent();String format=text(json,"format","");
                String category=format.contains("dataset")?"真实数据":format.contains("matmul")?"矩阵乘法":format.contains("trace")?"轨迹分析":"算子实验";
                Matcher count=Pattern.compile("\"completed_workers\"\\s*:\\s*([0-9]+)").matcher(json);
                entries.add(new Entry(directory,root.relativize(directory).toString(),category,text(json,"state","记录中"),text(json,"created_at_utc","未记录"),text(json,"java_version","未记录"),count.find()?count.group(1):"—",Files.isRegularFile(directory.resolve("report.html"))));
            }
        }
        entries.sort(Comparator.comparing(Entry::created).reversed().thenComparing(Entry::name));return List.copyOf(entries);
    }
    // These metadata fields are canonical identifiers and timestamps, without escaped strings.
    private static String text(String json,String key,String fallback) {
        Matcher m=Pattern.compile("\""+Pattern.quote(key)+"\"\\s*:\\s*\"([^\"]*)\"").matcher(json);
        return m.find()?m.group(1):fallback;
    }
    public static Path writeHtml(Path root,Path output) throws IOException {
        List<Entry> entries=scan(root);
        StringBuilder table=new StringBuilder("<table><thead><tr><th>实验</th><th>类型</th><th>状态</th><th>开始UTC</th><th>JDK</th><th>已完成进程</th><th>报告</th></tr></thead><tbody>");
        for(Entry e:entries){
            table.append("<tr>");
            for(String v:List.of(e.name(),e.category(),e.state(),e.created(),e.javaVersion(),e.completed()))table.append("<td>").append(ReportWriter.html(v)).append("</td>");
            table.append("<td>");
            if(e.reportAvailable()) {
                String url;
                try {url=output.toAbsolutePath().getParent().relativize(e.directory().resolve("report.html").toAbsolutePath()).toString().replace(File.separatorChar,'/');url=new java.net.URI(null,null,url,null).toASCIIString();}
                catch(IllegalArgumentException | java.net.URISyntaxException error){url=e.directory().resolve("report.html").toAbsolutePath().toUri().toASCIIString();}
                table.append("<a href='").append(ReportWriter.html(url)).append("'>打开报告</a>");
            } else table.append("执行中或无报告");
            table.append("</td></tr>");
        }
        table.append("</tbody></table>");
        String html="""
            <!doctype html><html lang='zh-CN'><meta charset='utf-8'><title>ShapeFlux-J 实验浏览</title>
            <style>body{font:15px/1.8 system-ui,'Microsoft YaHei',sans-serif;background:#0b1120;color:#eaf1ff;max-width:1400px;margin:40px auto;padding:24px}h1,a{color:#42dfc0}input{width:95%;padding:12px;background:#14243b;color:#fff;border:1px solid #31425f}td,th{padding:10px;border-bottom:1px solid #31425f}table{width:100%;border-collapse:collapse}.scroll{overflow:auto}</style>
            <h1>ShapeFlux-J 实验浏览</h1><p>页面是生成时的快照；桌面工作台可以刷新实时状态。</p>
            <input id='search' placeholder='按名称、类型、状态或JDK筛选'><div class='scroll'>
            """+table+"""
            </div><script>document.getElementById('search').addEventListener('input',function(){const q=this.value.toLowerCase();document.querySelectorAll('tbody tr').forEach(r=>r.hidden=!r.textContent.toLowerCase().includes(q));});</script></html>
            """;
        Path parent=output.toAbsolutePath().getParent();if(parent!=null)Files.createDirectories(parent);Files.writeString(output,html,StandardCharsets.UTF_8);return output;
    }
}
