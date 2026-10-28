import javax.tools.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.io.*;
import java.security.MessageDigest;

/** Dependency-free build: java tools/Build.java [test]. Also works when javac/jar launchers are absent. */
class Build {
    public static void main(String[] args) throws Exception {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) throw new IllegalStateException("Install a full JDK 17 or newer and put its bin directory on PATH");
        Path root = Path.of("").toAbsolutePath();
        if (!Files.isDirectory(root.resolve("src/main/java"))) throw new IllegalArgumentException("Run this command from the shapeflux-j project directory");
        Path classes = root.resolve("build/classes"), tests = root.resolve("build/test-classes"), dist = root.resolve("dist");
        clean(classes); clean(tests); Files.createDirectories(dist);
        compile(compiler, root.resolve("src/main/java"), classes, null);
        Files.createDirectories(classes.resolve("META-INF"));
        Files.writeString(classes.resolve("META-INF/shapeflux-build.properties"),"version=2.4.0\nsource_sha256="+sourceHash(root)+"\n",StandardCharsets.UTF_8);
        Manifest manifest = new Manifest(); manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Main-Class", "io.shapeflux.Main");
        Path jar = dist.resolve("shapeflux-j.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar)); var files = Files.walk(classes)) {
            JarEntry manifestEntry=new JarEntry("META-INF/MANIFEST.MF");manifestEntry.setTime(0);out.putNextEntry(manifestEntry);manifest.write(out);out.closeEntry();
            for (Path p : files.filter(Files::isRegularFile).sorted().toList()) {
                JarEntry entry = new JarEntry(classes.relativize(p).toString().replace(File.separatorChar, '/'));
                entry.setTime(0); out.putNextEntry(entry); Files.copy(p, out); out.closeEntry();
            }
        }
        System.out.println("Built " + jar + " (" + Files.size(jar) + " bytes)");
        if (Arrays.asList(args).contains("test")) {
            compile(compiler, root.resolve("src/test/java"), tests, classes.toString());
            String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
            int exit = new ProcessBuilder(java, "-Djava.awt.headless=true", "-cp", classes + File.pathSeparator + tests, "io.shapeflux.TestSuite").inheritIO().start().waitFor();
            if (exit != 0) System.exit(exit);
        }
    }
    private static String sourceHash(Path root) throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");List<Path> source;
        try(var paths=Files.walk(root.resolve("src/main/java"))){source=new ArrayList<>(paths.filter(p->p.toString().endsWith(".java")).toList());}
        source.add(root.resolve("tools/Build.java"));source.sort(Comparator.comparing(p->root.relativize(p).toString().replace(File.separatorChar,'/')));
        for(Path path:source){digest.update(root.relativize(path).toString().replace(File.separatorChar,'/').getBytes(StandardCharsets.UTF_8));digest.update((byte)0);digest.update(Files.readAllBytes(path));digest.update((byte)0);}
        return HexFormat.of().formatHex(digest.digest());
    }
    private static void compile(JavaCompiler compiler, Path source, Path destination, String classpath) throws Exception {
        List<Path> paths;
        try (var stream = Files.walk(source)) { paths = stream.filter(p -> p.toString().endsWith(".java")).sorted().toList(); }
        try (StandardJavaFileManager files = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8)) {
            List<String> options = new ArrayList<>(List.of("--release", "17", "-encoding", "UTF-8", "-Xlint:all", "-d", destination.toString()));
            if (classpath != null) options.addAll(List.of("-classpath", classpath));
            if (!Boolean.TRUE.equals(compiler.getTask(null, files, null, options, null, files.getJavaFileObjectsFromPaths(paths)).call())) throw new IllegalStateException("Build failed");
        }
    }
    private static void clean(Path dir) throws IOException {
        if (Files.exists(dir)) try (var files = Files.walk(dir)) { for (Path p : files.sorted(Comparator.reverseOrder()).toList()) Files.delete(p); }
        Files.createDirectories(dir);
    }
}
