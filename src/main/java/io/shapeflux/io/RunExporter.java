package io.shapeflux.io;

import io.shapeflux.ir.Program;
import io.shapeflux.i18n.ZhCn;
import io.shapeflux.runtime.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public final class RunExporter {
    private RunExporter() {}
    public static void save(Path directory,String dsl,Program program,Engine engine,List<TensorInput> inputs,List<Engine.Execution> runs) throws IOException {
        if(inputs.size()!=runs.size() || runs.isEmpty())throw new IllegalArgumentException("Incomplete run records");
        Files.createDirectories(directory);StringBuilder csv=new StringBuilder("index,rows,cols,selected,cache_hit,compute_us,total_us,correct,max_abs_error\n");
        StringBuilder readable=new StringBuilder("ShapeFlux-J 中文运行报告\n\n执行策略："+ZhCn.name(engine.strategy())+"\n\n"+ZhCn.stats(engine.stats())+"\n逐请求校验与计时：\n");
        for(int i=0;i<runs.size();i++) {
            TensorInput input=inputs.get(i);Engine.Execution execution=runs.get(i);double[] expected=Interpreter.run(program,input);
            csv.append(String.format(Locale.ROOT,"%d,%d,%d,%s,%s,%.6f,%.6f,%s,%.12g%n",i,input.rows(),input.cols(),execution.selected(),execution.cacheHit(),execution.computeNanos()/1000.0,execution.totalNanos()/1000.0,Numerics.close(expected,execution.output()),Numerics.maxError(expected,execution.output())));
            readable.append("第 ").append(i+1).append(" 次｜").append(input.rows()).append(" 行 × ").append(input.cols()).append(" 列｜")
                .append(ZhCn.guard(execution.selected())).append("｜缓存").append(execution.cacheHit()?"命中":"未命中")
                .append("｜数值校验").append(Numerics.close(expected,execution.output())?"通过":"未通过")
                .append(String.format(Locale.ROOT,"\n计算：%.3f 微秒；请求总计：%.3f 毫秒；最大绝对误差：%.12g\n",execution.computeNanos()/1000.0,execution.totalNanos()/1e6,Numerics.maxError(expected,execution.output())));
        }
        Files.writeString(directory.resolve("requests.csv"),csv,StandardCharsets.UTF_8);Files.writeString(directory.resolve("program.sfx"),dsl,StandardCharsets.UTF_8);
        Files.writeString(directory.resolve("optimized-ir.txt"),engine.program().describe(),StandardCharsets.UTF_8);Files.writeString(directory.resolve("stats.json"),Json.write(engine.stats())+"\n",StandardCharsets.UTF_8);
        StringBuilder events=new StringBuilder();for(Engine.Event event:engine.events())events.append(event.request()).append(' ').append(event.type()).append(' ').append(event.detail()).append('\n');
        Files.writeString(directory.resolve("events.log"),events,StandardCharsets.UTF_8);
        readable.append("\n中文计算图：\n").append(ZhCn.ir(engine.program())).append("\n运行事件：\n");
        for(Engine.Event event:engine.events())readable.append(ZhCn.event(event)).append('\n');
        readable.append("\n计时说明：请求总耗时包含该次触发的编译、验证与收益探测；计算耗时仅覆盖所选内核的执行。\n完整输出矩阵请查看 last-output.csv，逐请求原始数据请查看 requests.csv。\n");
        Files.writeString(directory.resolve("运行报告.txt"),readable,StandardCharsets.UTF_8);
        int index=0;Files.createDirectories(directory.resolve("generated"));for(var kernel:engine.artifacts())Files.writeString(directory.resolve("generated/kernel-"+index+++".java.txt"),kernel.source(),StandardCharsets.UTF_8);
        TensorInput last=inputs.get(inputs.size()-1);double[] out=runs.get(runs.size()-1).output();TensorCsv.write(directory.resolve("last-output.csv"),out,last.rows(),program.terminal().reduces()?1:last.cols());
    }
}
