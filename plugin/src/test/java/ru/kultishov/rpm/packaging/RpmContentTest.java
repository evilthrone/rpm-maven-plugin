package ru.kultishov.rpm.packaging;

import ru.kultishov.rpm.config.RpmMapping;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RpmContentTest {
    private static final Instant BUILD_TIME = Instant.parse("2026-09-28T12:34:56Z");

    @TempDir
    Path temporaryDirectory;

    private RpmPackage rpmPackage() throws Exception {
        return RpmPackage.create("demo", "1.0.0", 1, "java-21-openjdk-headless", BUILD_TIME);
    }

    private RpmMapping mapping(String source, String destination) {
        RpmMapping mapping = new RpmMapping();
        mapping.setSource(source == null ? null : Path.of(source).toFile());
        mapping.setDestination(destination);
        return mapping;
    }

    private RpmContent prepare(RpmMapping... mappings) throws Exception {
        Path jar = temporaryDirectory.resolve("demo.jar");
        Files.writeString(jar, "JAR payload");
        return RpmContent.prepare(rpmPackage(), jar, temporaryDirectory, List.of(mappings));
    }

    @Test
    void stagesRelativeSourceAndKeepsAttributesAcrossGoals() throws Exception {
        Files.writeString(temporaryDirectory.resolve("app.properties"), "message=original\n");
        RpmMapping config = mapping("app.properties", "/etc/demo/app.properties");
        config.setMode("0640");
        config.setOwner("daemon");
        config.setGroup("daemon");
        config.setConfig(true);
        RpmContent content = prepare(mapping(null, "/etc/demo"), config, mapping(null, "/var/log/demo"));
        Path sources = Files.createDirectory(temporaryDirectory.resolve("SOURCES"));
        Path manifest = temporaryDirectory.resolve("content.properties");

        content.stage(sources);
        content.save(manifest);
        RpmContent loaded = RpmContent.load(manifest);
        loaded.validateSources(sources);

        assertEquals("JAR payload", Files.readString(sources.resolve("demo.jar")));
        assertEquals("message=original\n", Files.readString(sources.resolve("rpm-source-1")));
        assertEquals(content.entries().stream().map(e -> e.destination() + ":" + e.mode() + ":"
                        + e.owner() + ":" + e.group() + ":" + e.config()).toList(),
                loaded.entries().stream().map(e -> e.destination() + ":" + e.mode() + ":"
                        + e.owner() + ":" + e.group() + ":" + e.config()).toList());
        String spec = RpmSpec.generate(rpmPackage(), "Demo", "Proprietary", "Development/Other", BUILD_TIME, loaded);
        assertTrue(spec.contains("%config(noreplace) %attr(0640,daemon,daemon) /etc/demo/app.properties\n"));
        assertTrue(spec.contains("%dir %attr(0755,root,root) /var/log/demo\n"));
    }

    @Test
    void expandsDirectoryTreeIncludingNestedEmptyDirectories() throws Exception {
        Path tree = Files.createDirectories(temporaryDirectory.resolve("assets/nested/empty"));
        Files.writeString(tree.getParent().resolve("data.txt"), "data");
        RpmMapping mapping = mapping("assets", "/usr/share/demo/assets");
        mapping.setMode("0750");
        mapping.setFileMode("0640");
        mapping.setOwner("daemon");
        mapping.setConfig(true);
        RpmContent content = prepare(mapping);

        assertEquals(List.of("/usr/share/demo", "/usr/share/demo/demo.jar", "/usr/share/demo/assets",
                "/usr/share/demo/assets/nested", "/usr/share/demo/assets/nested/data.txt",
                "/usr/share/demo/assets/nested/empty"), content.entries().stream().map(RpmContent.Entry::destination).toList());
        assertTrue(content.entries().stream().filter(e -> e.destination().startsWith("/usr/share/demo/assets"))
                .allMatch(e -> e.owner().equals("daemon") && e.mode().equals(e.directory() ? "0750" : "0640")));
        assertTrue(content.entries().stream().filter(e -> e.destination().startsWith("/usr/share/demo/assets"))
                .allMatch(e -> e.config() == !e.directory()));
    }

    @Test
    void supportsSourceAndDestinationNamesWithSpacesAndUnicode() throws Exception {
        Files.writeString(temporaryDirectory.resolve("my config.properties"), "config");
        RpmContent content = prepare(mapping("my config.properties", "/etc/демо сервис/my config.properties"));
        String spec = RpmSpec.generate(rpmPackage(), "Demo", "Proprietary", "Development/Other", BUILD_TIME, content);
        assertTrue(spec.contains("\"%{buildroot}/etc/демо сервис/my config.properties\""));
        assertTrue(spec.contains("%attr(0644,root,root) \"/etc/демо сервис/my config.properties\"\n"));
    }

    @Test
    void rejectsInvalidDestinations() {
        for (String destination : new String[]{"relative/file", "/", "/etc/../file", "/etc/./file",
                "/etc//file", "/etc/file/", "C:\\etc\\file", "/etc/%{name}", "/etc/file;touch", "/etc/file\n"}) {
            assertThrows(MojoExecutionException.class, () -> prepare(mapping(null, destination)), destination);
        }
    }

    @Test
    void rejectsDuplicateAndFileAncestorDestinationsInEitherOrder() throws Exception {
        Files.writeString(temporaryDirectory.resolve("data"), "data");
        assertThrows(MojoExecutionException.class, () -> prepare(mapping(null, "/usr/share/demo")));
        assertThrows(MojoExecutionException.class, () -> prepare(mapping("data", "/usr/share/demo/demo.jar")));
        assertThrows(MojoExecutionException.class, () -> prepare(mapping("data", "/etc/demo"), mapping(null, "/etc/demo/child")));
        assertThrows(MojoExecutionException.class, () -> prepare(mapping(null, "/etc/demo/child"), mapping("data", "/etc/demo")));
    }

    @Test
    void rejectsMissingSourcesAndInvalidAttributes() {
        assertThrows(MojoExecutionException.class, () -> prepare(mapping("missing", "/etc/demo/file")));
        RpmMapping invalidMode = mapping(null, "/var/log/demo");
        invalidMode.setMode("0899");
        assertThrows(MojoExecutionException.class, () -> prepare(invalidMode));
        RpmMapping invalidOwner = mapping(null, "/var/log/demo");
        invalidOwner.setOwner("root\n%post");
        assertThrows(MojoExecutionException.class, () -> prepare(invalidOwner));
        RpmMapping invalidGroup = mapping(null, "/var/log/demo");
        invalidGroup.setGroup("root,other");
        assertThrows(MojoExecutionException.class, () -> prepare(invalidGroup));
        RpmMapping invalidConfig = mapping(null, "/var/log/demo");
        invalidConfig.setConfig(true);
        assertThrows(MojoExecutionException.class, () -> prepare(invalidConfig));
    }

    @Test
    void rejectsSymlinksInsideDirectoryTrees() throws Exception {
        Path directory = Files.createDirectory(temporaryDirectory.resolve("tree"));
        Path target = Files.writeString(temporaryDirectory.resolve("outside"), "outside");
        try {
            Files.createSymbolicLink(directory.resolve("link"), target);
        } catch (UnsupportedOperationException | java.io.IOException | SecurityException e) {
            assumeTrue(false, "Creating symbolic links is unavailable: " + e.getMessage());
        }
        assertThrows(MojoExecutionException.class, () -> prepare(mapping("tree", "/usr/share/demo/tree")));
    }

    @Test
    void rejectsManifestSourceTraversal() throws Exception {
        Path manifest = temporaryDirectory.resolve("content.properties");
        prepare().save(manifest);
        Properties properties = new Properties();
        try (var reader = Files.newBufferedReader(manifest)) {
            properties.load(reader);
        }
        properties.setProperty("1.sourceName", "../outside.jar");
        try (var writer = Files.newBufferedWriter(manifest)) {
            properties.store(writer, "Tampered manifest");
        }
        assertThrows(MojoExecutionException.class, () -> RpmContent.load(manifest));
    }

    @Test
    void detectsMissingStagedSources() throws Exception {
        RpmContent content = prepare();
        Path sources = Files.createDirectory(temporaryDirectory.resolve("SOURCES"));
        content.stage(sources);
        Files.delete(sources.resolve("demo.jar"));
        assertThrows(MojoExecutionException.class, () -> content.validateSources(sources));
    }

    @Test
    void preservesSpecialDirectoryPermissionBits() throws Exception {
        RpmMapping directory = mapping(null, "/var/log/demo");
        directory.setMode("2750");
        RpmContent content = prepare(directory);
        assertDoesNotThrow(() -> content.verify("/usr/share/demo|16877|root|root|0\n"
                + "/usr/share/demo/demo.jar|33188|root|root|0\n"
                + "/var/log/demo|17896|root|root|0\n"));
    }

    @Test
    void verifiesModesOwnersTypesAndConfigNoreplaceFlags() throws Exception {
        Files.writeString(temporaryDirectory.resolve("config"), "config");
        RpmMapping config = mapping("config", "/etc/demo.properties");
        config.setConfig(true);
        RpmContent content = prepare(config);
        String correct = "/usr/share/demo|16877|root|root|0\n"
                + "/usr/share/demo/demo.jar|33188|root|root|0\n"
                + "/etc/demo.properties|33188|root|root|17\n";

        assertDoesNotThrow(() -> content.verify(correct));
        assertThrows(MojoExecutionException.class, () -> content.verify(correct.replace("|17", "|1")));
        assertThrows(MojoExecutionException.class, () -> content.verify(correct.replace("33188", "33261")));
        assertThrows(MojoExecutionException.class, () -> content.verify(correct.replace("|root|root|", "|daemon|root|")));
        assertThrows(MojoExecutionException.class, () -> content.verify(correct.replace("16877", "33261")));
        assertThrows(MojoExecutionException.class, () -> content.verify(correct.replace("/etc/demo.properties|33188|root|root|17\n", "")));
    }
}
