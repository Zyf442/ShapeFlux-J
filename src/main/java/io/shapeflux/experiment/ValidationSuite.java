package io.shapeflux.experiment;

import io.shapeflux.Examples;
import io.shapeflux.io.Json;
import io.shapeflux.ir.*;
import io.shapeflux.runtime.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Real compiler self-check shipped in the runnable JAR; contains no artificial timing doubles. */
public final class ValidationSuite {
    private ValidationSuite() {}
    public static int run(Path output) throws IOException {
        Files.createDirectories(output);StringBuilder csv=new StringBuilder("example,strategy,rows,cols,correct,max_abs_error\n");int checks=0;
        for(var example:Examples.all().entrySet())for(Strategy strategy:Strategy.values()) {
            Program p=Parser.parse(example.getValue());Engine engine=new Engine(p,strategy,new EngineConfig(2,3,0,true,true,256));
            for(int[] shape:new int[][]{{1,1},{3,7},{33,19},{40,19}}) {
                KernelProbe.interrupted();TensorInput input=TensorInput.random(shape[0],shape[1],2049);Engine.Execution execution=engine.execute(input);
                double[] expected=Interpreter.run(p,input);boolean correct=Numerics.close(expected,execution.output());
                csv.append(example.getKey()).append(',').append(strategy).append(',').append(shape[0]).append(',').append(shape[1]).append(',').append(correct).append(',').append(Numerics.maxError(expected,execution.output())).append('\n');
                if(!correct)throw new IllegalStateException("Self-check failed: "+example.getKey()+" / "+strategy);checks++;
            }
            if(engine.stats().compileFailures()>0 || engine.stats().runtimeFailures()>0 || engine.stats().fallbacks()>0)throw new IllegalStateException("Self-check used a failed compiler/kernel: "+example.getKey());
        }
        Files.writeString(output.resolve("verification.csv"),csv,StandardCharsets.UTF_8);
        Files.writeString(output.resolve("verification.json"),Json.write(Map.of("passed",true,"request_checks",checks,"java",System.getProperty("java.version"),"seed",2049,"note","Correctness checks, not a performance benchmark"))+"\n",StandardCharsets.UTF_8);
        System.out.println("通过："+checks+" 项随包编译器与输入形状自检");return checks;
    }
}
