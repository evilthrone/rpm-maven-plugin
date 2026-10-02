package ru.kultishov.rpm.packaging;

import ru.kultishov.rpm.config.RpmMapping;

import org.apache.maven.plugin.MojoExecutionException;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

// Validated package contents shared by prepare, build, and verify
public final class RpmContent {
    public record Entry(String destination, boolean directory, String mode, String owner, String group,
                 boolean config, int sourceIndex, String sourceName, Path source) {
    }

    private final List<Entry> entries;

    private RpmContent(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    public List<Entry> entries() {
        return entries;
    }

    public static RpmContent defaults(RpmPackage rpmPackage) throws MojoExecutionException {
        return prepare(rpmPackage, null, Path.of("."), List.of());
    }

    public static RpmContent prepare(RpmPackage rpmPackage, Path jar, Path baseDirectory, List<RpmMapping> mappings)
            throws MojoExecutionException {
        Map<String, Entry> result = new LinkedHashMap<>();
        add(result, new Entry(rpmPackage.installDirectory(), true, "0755", "root", "root", false,
                -1, "", null));
        add(result, new Entry(rpmPackage.jarPath(), false, "0644", "root", "root", false,
                0, rpmPackage.name() + ".jar", jar));
        int sourceIndex = 1;
        for (RpmMapping mapping : mappings) {
            if (mapping == null) {
                throw new MojoExecutionException("RPM mapping must not be null");
            }
            String destination = mapping.getDestination();
            validateDestination(destination);
            Path source = mapping.getSource() == null ? null
                    : baseDirectory.resolve(mapping.getSource().toPath()).toAbsolutePath().normalize();
            if (source != null) {
                rejectSymlink(source);
                if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)
                        && !Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                    throw new MojoExecutionException("RPM source must be an existing file or directory: " + source);
                }
            }
            boolean directory = source == null || Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS);
            String mode = mapping.getMode() == null ? (directory ? "0755" : "0644") : mapping.getMode();
            if (source == null && mapping.isConfig()) {
                throw new MojoExecutionException("An empty directory cannot be a configuration file: " + destination);
            }
            if (!directory) {
                add(result, entry(mapping, destination, false, mode, sourceIndex++, source));
                continue;
            }
            add(result, entry(mapping, destination, true, mode, -1, null));
            if (source == null) {
                continue;
            }
            try (var paths = Files.walk(source)) {
                for (Path child : paths.sorted().toList()) {
                    if (child.equals(source)) {
                        continue;
                    }
                    rejectSymlink(child);
                    boolean childDirectory = Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS);
                    if (!childDirectory && !Files.isRegularFile(child, LinkOption.NOFOLLOW_LINKS)) {
                        throw new MojoExecutionException("Unsupported RPM source: " + child);
                    }
                    String relative = source.relativize(child).toString().replace('\\', '/');
                    add(result, entry(mapping, destination + "/" + relative, childDirectory,
                            childDirectory ? mode : mapping.getFileMode(),
                            childDirectory ? -1 : sourceIndex++, childDirectory ? null : child));
                }
            } catch (IOException | UncheckedIOException e) {
                throw new MojoExecutionException("Failed to read RPM source directory: " + source, e);
            }
        }
        return new RpmContent(new ArrayList<>(result.values()));
    }

    private static Entry entry(RpmMapping mapping, String destination, boolean directory,
                               String mode, int index, Path source) {
        return new Entry(destination, directory, mode, mapping.getOwner(), mapping.getGroup(),
                !directory && mapping.isConfig(), index, directory ? "" : "rpm-source-" + index, source);
    }

    private static void add(Map<String, Entry> entries, Entry entry) throws MojoExecutionException {
        validateDestination(entry.destination());
        if (entry.mode() == null || !entry.mode().matches("[0-7]{4}")) {
            throw new MojoExecutionException("RPM mode must contain four octal digits, for example 0644: " + entry.destination());
        }
        if (!validIdentity(entry.owner()) || !validIdentity(entry.group())) {
            throw new MojoExecutionException("Invalid RPM owner or group: " + entry.destination());
        }
        for (Entry existing : entries.values()) {
            if (existing.destination().equals(entry.destination())
                    || (!existing.directory() && entry.destination().startsWith(existing.destination() + "/"))
                    || (!entry.directory() && existing.destination().startsWith(entry.destination() + "/"))) {
                throw new MojoExecutionException("Conflicting RPM destinations: " + existing.destination()
                        + " and " + entry.destination());
            }
        }
        entries.put(entry.destination(), entry);
    }

    private static boolean validIdentity(String value) {
        return value != null && value.matches("[a-zA-Z_][a-zA-Z0-9_-]*");
    }

    private static void validateDestination(String destination) throws MojoExecutionException {
        if (destination == null || !destination.matches("/(?:[\\p{L}\\p{N}._+ -]+/)*[\\p{L}\\p{N}._+ -]+")) {
            throw new MojoExecutionException("RPM destination must be an absolute Linux path without macros or special characters: "
                    + destination);
        }
        for (String component : destination.substring(1).split("/")) {
            if (component.equals(".") || component.equals("..")) {
                throw new MojoExecutionException("RPM destination must not contain '.' or '..': " + destination);
            }
        }
    }

    public static void rejectSymlink(Path path) throws MojoExecutionException {
        Path absolute = path.toAbsolutePath().normalize();
        for (Path current = absolute; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) {
                throw new MojoExecutionException("RPM path must not contain symbolic links: " + path);
            }
        }
    }

    public void stage(Path sourcesDirectory) throws IOException, MojoExecutionException {
        for (Entry entry : entries) {
            if (entry.directory()) {
                continue;
            }
            Path target = sourcesDirectory.resolve(entry.sourceName());
            rejectSymlink(entry.source());
            rejectSymlink(target);
            if (!Files.isRegularFile(entry.source(), LinkOption.NOFOLLOW_LINKS)) {
                throw new MojoExecutionException("RPM source is no longer a regular file: " + entry.source());
            }
            Files.copy(entry.source(), target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    public void validateSources(Path sourcesDirectory) throws MojoExecutionException {
        for (Entry entry : entries) {
            if (!entry.directory()) {
                Path source = sourcesDirectory.resolve(entry.sourceName());
                rejectSymlink(source);
                if (!Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS)) {
                    throw new MojoExecutionException("Prepared RPM source is missing: " + source);
                }
            }
        }
    }

    public void verify(String rpmFiles) throws MojoExecutionException {
        Map<String, String[]> actual = new LinkedHashMap<>();
        for (String line : rpmFiles.lines().toList()) {
            String[] fields = line.split("\\|", -1);
            if (fields.length != 5 || actual.putIfAbsent(fields[0], fields) != null) {
                throw new MojoExecutionException("Invalid RPM file metadata: " + line);
            }
        }
        for (Entry entry : entries) {
            String[] fields = actual.get(entry.destination());
            if (fields == null) {
                throw new MojoExecutionException("RPM lacks expected path: " + entry.destination());
            }
            try {
                int mode = Integer.parseInt(fields[1]);
                int flags = Integer.parseInt(fields[4]);
                int expectedType = entry.directory() ? 0040000 : 0100000;
                // RPMFILE_CONFIG = 1, RPMFILE_NOREPLACE = 16.
                int expectedConfigFlags = entry.config() ? 17 : 0;
                if ((mode & 0170000) != expectedType || (mode & 07777) != Integer.parseInt(entry.mode(), 8)
                        || !entry.owner().equals(fields[2]) || !entry.group().equals(fields[3])
                        || (flags & 17) != expectedConfigFlags) {
                    throw new MojoExecutionException("Unexpected RPM attributes for " + entry.destination()
                            + ": " + String.join("|", fields));
                }
            } catch (NumberFormatException e) {
                throw new MojoExecutionException("Invalid RPM mode or flags for " + entry.destination(), e);
            }
        }
    }

    public void save(Path file) throws IOException, MojoExecutionException {
        rejectSymlink(file);
        Properties properties = new Properties();
        properties.setProperty("count", Integer.toString(entries.size()));
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            String prefix = i + ".";
            properties.setProperty(prefix + "destination", entry.destination());
            properties.setProperty(prefix + "directory", Boolean.toString(entry.directory()));
            properties.setProperty(prefix + "mode", entry.mode());
            properties.setProperty(prefix + "owner", entry.owner());
            properties.setProperty(prefix + "group", entry.group());
            properties.setProperty(prefix + "config", Boolean.toString(entry.config()));
            properties.setProperty(prefix + "sourceIndex", Integer.toString(entry.sourceIndex()));
            properties.setProperty(prefix + "sourceName", entry.sourceName());
        }
        try (Writer writer = Files.newBufferedWriter(file)) {
            properties.store(writer, "Prepared RPM contents");
        }
    }

    public static RpmContent load(Path file) throws IOException, MojoExecutionException {
        rejectSymlink(file);
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) {
            properties.load(reader);
        }
        Map<String, Entry> result = new LinkedHashMap<>();
        try {
            int count = Integer.parseInt(properties.getProperty("count"));
            if (count < 2 || count > 100000) {
                throw new IllegalArgumentException("Invalid entry count");
            }
            int nextSourceIndex = 0;
            for (int i = 0; i < count; i++) {
                String prefix = i + ".";
                String directoryValue = properties.getProperty(prefix + "directory");
                String configValue = properties.getProperty(prefix + "config");
                if (!("true".equals(directoryValue) || "false".equals(directoryValue))
                        || !("true".equals(configValue) || "false".equals(configValue))) {
                    throw new IllegalArgumentException("Invalid boolean");
                }
                boolean directory = Boolean.parseBoolean(directoryValue);
                boolean config = Boolean.parseBoolean(configValue);
                int index = Integer.parseInt(properties.getProperty(prefix + "sourceIndex"));
                String sourceName = properties.getProperty(prefix + "sourceName");
                if (directory) {
                    if (index != -1 || config || !"".equals(sourceName)) {
                        throw new IllegalArgumentException("Invalid directory source reference");
                    }
                } else {
                    boolean validName = sourceName != null && (index == 0
                            ? sourceName.matches("[a-z0-9][a-z0-9+._-]*\\.jar")
                            : sourceName.equals("rpm-source-" + index));
                    if (index != nextSourceIndex++ || !validName) {
                        throw new IllegalArgumentException("Invalid file source reference");
                    }
                }
                add(result, new Entry(properties.getProperty(prefix + "destination"), directory,
                        properties.getProperty(prefix + "mode"), properties.getProperty(prefix + "owner"),
                        properties.getProperty(prefix + "group"), config, index, sourceName, null));
            }
        } catch (IllegalArgumentException e) {
            throw new MojoExecutionException("Invalid RPM content manifest: " + file, e);
        }
        return new RpmContent(new ArrayList<>(result.values()));
    }
}
