package io.shapeflux.experiment;

import io.shapeflux.compiler.ShapeGuard;
import io.shapeflux.runtime.*;
import io.shapeflux.io.Json;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Audit a shape/request/batch trace; replay values are deliberately independent of this shape analysis. */
public final class TraceAnalyzer {
    private TraceAnalyzer() {}
    public static Path analyze(Path input,Path output,EngineConfig config) throws Exception {
        List<Workloads.Shape> shapes=Workloads.fromCsv(input);if(Files.exists(output))throw new IllegalArgumentException("Trace analysis output already exists");Files.createDirectories(output);
        ReuseWindow window=new ReuseWindow(config.reuseWindow());Set<Workloads.Shape> exact=new HashSet<>();Map<String,Integer> families=new LinkedHashMap<>();Map<String,Long> last=new HashMap<>();List<Long> gaps=new ArrayList<>();
        StringBuilder raw=new StringBuilder("request,rows,cols,family,reuse_gap_requests,window_observations,window_span,frequency,predicted_uses\n");int index=0;
        for(var shape:shapes) {
            String family=ShapeGuard.family(shape.rows(),shape.cols(),config.rowBucket(),config.colBucket()).key();exact.add(shape);families.merge(family,1,Integer::sum);index++;
            Long previous=last.put(family,(long)index);Long gap=previous==null?null:index-previous;if(gap!=null)gaps.add(gap);
            var e=window.observe(family,config.horizonMultiplier());raw.append(index).append(',').append(shape.rows()).append(',').append(shape.cols()).append(',').append(family).append(',').append(gap==null?"":gap).append(',').append(e.observed()).append(',').append(e.span()).append(',').append(e.frequency()).append(',').append(e.predictedUses()).append('\n');
        }
        Collections.sort(gaps);String meanGap=gaps.isEmpty()?"无重复":String.format(Locale.ROOT,"%.3f",gaps.stream().mapToLong(Long::longValue).average().orElseThrow()),p95=gaps.isEmpty()?"无重复":Long.toString(gaps.get((int)Math.ceil(gaps.size()*.95)-1));
        String overview="请求数："+shapes.size()+"\n精确形状数："+exact.size()+"\n守卫形状族："+families.size()+"\n观测窗口："+config.reuseWindow()+" 次请求\n预测请求预算："+config.horizonMultiplier()+"\n再次到达间隔均值："+meanGap+" 次请求\n再次到达间隔P95："+p95+" 次请求\n\n预测复用次数 = 当前观测窗口内该形状族频率 × 预测请求预算。形状族长时间未出现后退出观测窗口，不累计过期热度。预测是启发式估计，不是未来请求事实。重放只保留输入形状，数值用固定种子重新生成。\n";
        Files.writeString(output.resolve("概览.txt"),overview,StandardCharsets.UTF_8);Files.writeString(output.resolve("reuse.csv"),raw,StandardCharsets.UTF_8);Files.copy(input,output.resolve("source-trace.csv"));Workloads.writeCsv(output.resolve("workload.csv"),shapes);
        Map<String,Object> meta=new LinkedHashMap<>();meta.put("format","shapeflux-trace-1");meta.put("state","COMPLETE");meta.put("created_at_utc",Instant.now().toString());meta.put("java_version",System.getProperty("java.version"));meta.put("source_sha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(input))));meta.put("request_count",shapes.size());meta.put("exact_shape_count",exact.size());meta.put("family_count",families.size());meta.put("engine_config",config);meta.put("completed_workers",0);Files.writeString(output.resolve("metadata.json"),Json.write(meta)+"\n",StandardCharsets.UTF_8);
        var ranked=families.entrySet().stream().sorted(Map.Entry.<String,Integer>comparingByValue().reversed()).toList();StringBuilder table=new StringBuilder("<table><tr><th>形状族守卫</th><th>观测次数</th><th>占比</th></tr>");
        StringBuilder bars=new StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"980\" height=\""+(50+Math.min(20,ranked.size())*32)+"\" viewBox=\"0 0 980 "+(50+Math.min(20,ranked.size())*32)+"\"><rect width=\"100%\" height=\"100%\" fill=\"#121e32\"/>");int i=0;
        for(var entry:ranked){table.append("<tr><td>").append(entry.getKey()).append("</td><td>").append(entry.getValue()).append("</td><td>").append(String.format(Locale.ROOT,"%.2f%%",100.0*entry.getValue()/shapes.size())).append("</td></tr>");if(i<20){int y=28+i*32;double width=entry.getValue()*600.0/ranked.get(0).getValue();bars.append("<text x=\"20\" y=\"").append(y+14).append("\" fill=\"#eaf1ff\" font-size=\"13\">").append(entry.getKey()).append("</text><rect x=\"260\" y=\"").append(y).append("\" width=\"").append(width).append("\" height=\"20\" fill=\"#42dfc0\"/><text x=\"").append(270+width).append("\" y=\"").append(y+14).append("\" fill=\"#eaf1ff\">").append(entry.getValue()).append("</text>");}i++;}table.append("</table>");bars.append("</svg>");
        Files.writeString(output.resolve("families.svg"),bars,StandardCharsets.UTF_8);String html="<!doctype html><html lang=\"zh-CN\"><meta charset=\"utf-8\"><title>形状轨迹与复用窗口</title><style>body{font:16px/1.8 system-ui,'Microsoft YaHei',sans-serif;background:#0b1120;color:#eaf1ff;max-width:1100px;margin:40px auto;padding:24px}h1{color:#42dfc0}pre{white-space:pre-wrap}td,th{padding:10px;border-bottom:1px solid #31425f}table{width:100%;border-collapse:collapse}svg{max-width:100%;height:auto}</style><h1>形状轨迹与复用窗口</h1><pre>"+ReportWriter.html(overview)+"</pre>"+bars+table+"<p>原始序列 source-trace.csv；标准化形状 workload.csv；逐请求估计 reuse.csv。图显示频次最高的20个形状族；完整表保留全部。</p></html>";
        Files.writeString(output.resolve("report.html"),html,StandardCharsets.UTF_8);return output.resolve("report.html");
    }
}
