package io.shapeflux.compiler;

import javax.tools.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Java source -> bytecode -> isolated class loader, entirely in memory. */
final class JavaSourceCompiler {
    record Loaded<T>(T instance,int bytes) {}
    private JavaSourceCompiler() {}
    static <T> Loaded<T> load(String simple,String source,Class<T> api) {
        JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();
        if(compiler==null)throw new IllegalStateException("A full JDK 17+ is required; no system Java compiler is available");
        String qualified="io.shapeflux.generated."+simple;
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager standard = compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8);
             OutputManager manager = new OutputManager(standard)) {
            List<String> options = List.of("--release", "17", "-proc:none", "-g:none", "-encoding", "UTF-8", "-classpath", System.getProperty("java.class.path"));
            StringWriter extra = new StringWriter();
            boolean success = Boolean.TRUE.equals(compiler.getTask(extra, manager, diagnostics, options, null, List.of(new Source(qualified, source))).call());
            if (!success) throw new IllegalStateException("Generated kernel did not compile: " + diagnostics.getDiagnostics() + extra);
            Map<String, byte[]> bytes = new HashMap<>();
            manager.classes.forEach((name, obj) -> bytes.put(name, obj.bytes.toByteArray()));
            ClassLoader loader = new ClassLoader(api.getClassLoader()) {
                @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                    byte[] code = bytes.get(name);
                    if (code == null) throw new ClassNotFoundException(name);
                    return defineClass(name, code, 0, code.length);
                }
            };
            T kernel = api.cast(loader.loadClass(qualified).getDeclaredConstructor().newInstance());
            int size = bytes.values().stream().mapToInt(x -> x.length).sum();
            return new Loaded<>(kernel,size);
        } catch (ReflectiveOperationException | IOException e) { throw new IllegalStateException("Cannot load generated kernel", e); }
    }
    private static final class Source extends SimpleJavaFileObject {
        private final String code;
        Source(String name, String code) { super(URI.create("string:///" + name.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE); this.code = code; }
        @Override public CharSequence getCharContent(boolean ignoreEncodingErrors) { return code; }
    }
    private static final class Bytecode extends SimpleJavaFileObject {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Bytecode(String name, Kind kind) { super(URI.create("memory:///" + name.replace('.', '/') + kind.extension), kind); }
        @Override public OutputStream openOutputStream() { return bytes; }
    }
    private static final class OutputManager extends ForwardingJavaFileManager<StandardJavaFileManager> {
        final Map<String, Bytecode> classes = new HashMap<>();
        OutputManager(StandardJavaFileManager manager) { super(manager); }
        @Override public JavaFileObject getJavaFileForOutput(Location location, String name, JavaFileObject.Kind kind, FileObject sibling) {
            Bytecode code = new Bytecode(name, kind); classes.put(name, code); return code;
        }
    }
}
