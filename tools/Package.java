import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.jar.JarFile;
import java.util.zip.*;

/** java tools/Package.java [output.zip]; packages the verified source tree without external tools. */
class Package {
    public static void main(String[] args) throws Exception {
        Path root=Path.of("").toAbsolutePath();Path jar=root.resolve("dist/shapeflux-j.jar");
        Path output=args.length==0?root.resolve("release/ShapeFlux-J_Java_Project.zip"):Path.of(args[0]).toAbsolutePath();
        if(!Files.isRegularFile(jar))throw new IllegalStateException("Build the project first: java tools/Build.java test");
        Properties build=new Properties();try(JarFile file=new JarFile(jar.toFile())) {
            var entry=file.getJarEntry("META-INF/shapeflux-build.properties");if(entry==null)throw new IllegalStateException("Build metadata missing; rebuild with tools/Build.java");
            try(InputStream stream=file.getInputStream(entry)){build.load(stream);}
        }
        if(!sourceHash(root).equals(build.getProperty("source_sha256")))throw new IllegalStateException("JAR does not match current source; run java tools/Build.java test");
        List<Path> files;
        try(var stream=Files.walk(root)) {
            files=stream.filter(Files::isRegularFile).filter(p->!Files.isSymbolicLink(p)).filter(p->!p.equals(output))
                    .filter(p->{for(Path part:root.relativize(p))if(Set.of("build","target","release",".git").contains(part.toString()))return false;return !p.getFileName().toString().equals("MANIFEST.sha256");})
                    .sorted(Comparator.comparing(p->root.relativize(p).toString())).toList();
        }
        StringBuilder manifest=new StringBuilder();for(Path path:files)manifest.append(hash(Files.readAllBytes(path))).append("  ").append(root.relativize(path).toString().replace(File.separatorChar,'/')).append('\n');
        Files.writeString(root.resolve("MANIFEST.sha256"),manifest,StandardCharsets.UTF_8);List<Path> entries=new ArrayList<>(files);entries.add(root.resolve("MANIFEST.sha256"));
        Files.createDirectories(output.getParent());Path temp=Files.createTempFile(output.getParent(),"shapeflux-package-",".tmp");
        try {
            try(ZipOutputStream zip=new ZipOutputStream(Files.newOutputStream(temp),StandardCharsets.UTF_8)) {
                zip.setLevel(9);for(Path path:entries) {
                    ZipEntry entry=new ZipEntry("shapeflux-j/"+root.relativize(path).toString().replace(File.separatorChar,'/'));entry.setTime(0);zip.putNextEntry(entry);Files.copy(path,zip);zip.closeEntry();
                }
            }
            Files.move(temp,output,StandardCopyOption.REPLACE_EXISTING);
        }finally{Files.deleteIfExists(temp);}
        try(ZipFile zip=new ZipFile(output.toFile(),StandardCharsets.UTF_8)) {for(var iterator=zip.entries();iterator.hasMoreElements();)try(InputStream stream=zip.getInputStream(iterator.nextElement())){stream.transferTo(OutputStream.nullOutputStream());}}
        System.out.println("Packaged "+entries.size()+" files: "+output+" ("+Files.size(output)+" bytes)");System.out.println("SHA-256: "+hash(Files.readAllBytes(output)));
    }
    private static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    private static String sourceHash(Path root)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");List<Path> source;
        try(var paths=Files.walk(root.resolve("src/main/java"))){source=new ArrayList<>(paths.filter(p->p.toString().endsWith(".java")).toList());}
        source.add(root.resolve("tools/Build.java"));source.sort(Comparator.comparing(p->root.relativize(p).toString().replace(File.separatorChar,'/')));
        for(Path path:source){digest.update(root.relativize(path).toString().replace(File.separatorChar,'/').getBytes(StandardCharsets.UTF_8));digest.update((byte)0);digest.update(Files.readAllBytes(path));digest.update((byte)0);}
        return HexFormat.of().formatHex(digest.digest());
    }
}
