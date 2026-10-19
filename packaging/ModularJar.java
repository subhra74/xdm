import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import javax.tools.ToolProvider;

/**
 * Turns the fat jar into the bundles' app module (packaging/module/module-info.java, APPCDS.md):
 *
 *   java packaging/ModularJar.java <fat.jar> <out.jar> <module-info.java> [--exclude <prefix>]...
 *
 * - drops every library's own module-info.class (one module holds them all) and signature files;
 * - flattens META-INF/versions/N (N up to this JDK's release) over the base entries, as a multi-release
 *   jar resolves them on this JDK, and drops the Multi-Release attribute: jlink takes no versioned entries;
 * - leaves out entries under each --exclude prefix (the recording session, xdm/app/recording/);
 * - compiles the module-info against the result (javac --patch-module) and adds it.
 *
 * Run by build-bundle.sh / build-bundle.ps1 with the build's JDK (a full JDK: it needs javac). Standard
 * JDK only, so it runs as a single source file anywhere.
 */
public class ModularJar {

    private static final Pattern VERSIONED = Pattern.compile("META-INF/versions/(\\d+)/(.+)");
    private static final Pattern SIGNATURE = Pattern.compile("META-INF/[^/]+\\.(SF|RSA|DSA|EC)");
    private static final Pattern MODULE_NAME = Pattern.compile("\\bmodule\\s+([\\w.]+)\\s*\\{");

    public static void main(String[] args) throws Exception {
        if (args.length < 3) {
            System.err.println("usage: ModularJar <fat.jar> <out.jar> <module-info.java> [--exclude <prefix>]...");
            System.exit(2);
        }
        Path in = Path.of(args[0]);
        Path out = Path.of(args[1]);
        Path moduleInfo = Path.of(args[2]);
        List<String> excludes = new ArrayList<>();
        for (int i = 3; i < args.length; i++) {
            if (!args[i].equals("--exclude") || i + 1 == args.length) throw new IllegalArgumentException(args[i]);
            excludes.add(args[++i]);
        }
        int release = Runtime.version().feature();

        Manifest manifest;
        Map<String, Entry> entries = new LinkedHashMap<>();
        TreeMap<Integer, Map<String, Entry>> versioned = new TreeMap<>();
        try (JarFile jar = new JarFile(in.toFile())) {
            manifest = new Manifest(jar.getManifest());
            for (JarEntry e : jar.stream().toList()) {
                String name = e.getName();
                if (e.isDirectory() || name.equals(JarFile.MANIFEST_NAME)) continue;
                Matcher v = VERSIONED.matcher(name);
                if (v.matches()) {
                    int version = Integer.parseInt(v.group(1));
                    if (version <= release) versioned.computeIfAbsent(version, k -> new LinkedHashMap<>())
                            .put(v.group(2), new Entry(e, read(jar, e)));
                    continue;
                }
                entries.put(name, new Entry(e, read(jar, e)));
            }
        }
        versioned.values().forEach(entries::putAll);   // ascending: the highest version wins
        entries.keySet().removeIf(name -> name.equals("module-info.class") || name.endsWith("/module-info.class")
                || SIGNATURE.matcher(name).matches() || excludes.stream().anyMatch(name::startsWith));
        manifest.getMainAttributes().remove(new Attributes.Name("Multi-Release"));

        Path tmp = Files.createTempDirectory("modular-jar");
        try {
            Path classes = tmp.resolve("classes.jar");
            write(classes, manifest, entries, null);

            String source = Files.readString(moduleInfo);
            Matcher m = MODULE_NAME.matcher(source);
            if (!m.find()) throw new IllegalStateException("no module declaration in " + moduleInfo);
            Path compiled = tmp.resolve("out");
            int rc = ToolProvider.getSystemJavaCompiler().run(null, null, null,
                    "--release", String.valueOf(release), "--patch-module", m.group(1) + "=" + classes,
                    "-d", compiled.toString(), moduleInfo.toString());
            if (rc != 0) throw new IllegalStateException("javac failed on " + moduleInfo);

            write(out, manifest, entries, Files.readAllBytes(compiled.resolve("module-info.class")));
            System.out.printf(">> module %s: %d entries -> %s%n", m.group(1), entries.size() + 1, out);
        } finally {
            try (var walk = Files.walk(tmp)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    private record Entry(JarEntry source, byte[] data) { }

    private static byte[] read(JarFile jar, JarEntry e) throws IOException {
        try (InputStream s = jar.getInputStream(e)) {
            return s.readAllBytes();
        }
    }

    private static void write(Path path, Manifest manifest, Map<String, Entry> entries, byte[] moduleInfo)
            throws IOException {
        try (OutputStream os = Files.newOutputStream(path); JarOutputStream jar = new JarOutputStream(os, manifest)) {
            if (moduleInfo != null) put(jar, "module-info.class", moduleInfo, 0);
            for (Map.Entry<String, Entry> e : entries.entrySet()) {
                put(jar, e.getKey(), e.getValue().data(), e.getValue().source().getTime());
            }
        }
    }

    private static void put(JarOutputStream jar, String name, byte[] data, long time) throws IOException {
        ZipEntry ze = new ZipEntry(name);
        if (time > 0) ze.setTime(time);
        jar.putNextEntry(ze);
        jar.write(data);
        jar.closeEntry();
    }
}
