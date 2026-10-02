package ru.kultishov.rpm.mojo;

import ru.kultishov.rpm.packaging.RpmContent;
import ru.kultishov.rpm.config.RpmMapping;
import ru.kultishov.rpm.packaging.RpmPackage;
import ru.kultishov.rpm.config.RpmService;
import ru.kultishov.rpm.packaging.RpmWorkspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PrepareRpmMojoTest {
    @TempDir
    Path temporaryDirectory;

    private RpmPackage rpm() throws Exception {
        return RpmPackage.create("demo", "1.0.0", 1, "java-21-openjdk-headless", Instant.EPOCH);
    }

    @Test
    void prepareGoalIncludesUnitLauncherAndOwnedDirectoryInManifest() throws Exception {
        Path build = Files.createDirectory(temporaryDirectory.resolve("target"));
        Files.writeString(build.resolve("demo-1.0.0.jar"), "JAR");
        RpmService configuration = new RpmService();
        configuration.setArguments(List.of("8081"));
        RpmMapping logs = new RpmMapping();
        logs.setDestination("/var/log/demo");
        logs.setOwner("demo");
        logs.setGroup("demo");
        logs.setMode("0750");
        PrepareRpmMojo mojo = new PrepareRpmMojo();
        Map<String, Object> parameters = Map.ofEntries(
                Map.entry("buildDirectory", build.toFile()), Map.entry("baseDirectory", temporaryDirectory.toFile()),
                Map.entry("finalName", "demo-1.0.0"), Map.entry("projectVersion", "1.0.0"),
                Map.entry("packaging", "jar"), Map.entry("rpmName", "demo"), Map.entry("releaseNumber", 1),
                Map.entry("jreRequirement", "java-21-openjdk-headless"), Map.entry("javaExecutable", "auto"),
                Map.entry("summary", "Demo"), Map.entry("license", "Proprietary"),
                Map.entry("group", "Development/Other"), Map.entry("service", configuration),
                Map.entry("mappings", List.of(logs)), Map.entry("fixedBuildTime", "2026-10-01T12:00:00Z"));
        for (var parameter : parameters.entrySet()) {
            var field = PrepareRpmMojo.class.getDeclaredField(parameter.getKey());
            field.setAccessible(true);
            field.set(mojo, parameter.getValue());
        }
        mojo.execute();
        RpmWorkspace workspace = new RpmWorkspace(build);
        RpmContent content = RpmContent.load(workspace.contentManifest());
        content.validateSources(workspace.sourcesDirectory());
        assertTrue(content.entries().stream().anyMatch(entry -> entry.destination().equals("/lib/systemd/system/demo.service")
                && entry.mode().equals("0644") && entry.owner().equals("root")));
        assertTrue(content.entries().stream().anyMatch(entry -> entry.destination().equals("/usr/bin/demo")));
        assertTrue(Files.readString(workspace.sourcesDirectory().resolve("rpm-source-1"))
                .contains("rpm -ql 'java-21-openjdk-headless'"));
        assertTrue(Files.readString(workspace.spec(rpm())).contains("%post_service demo"));
        assertTrue(Files.readString(workspace.sourcesDirectory().resolve("rpm-source-2"))
                .contains("ExecStart=/usr/bin/demo \"8081\""));
    }
}
