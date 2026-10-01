package ru.kultishov.rpm;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class RpmLauncherTest {
    @TempDir
    Path temporaryDirectory;

    private Path fakeJava(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        Files.writeString(path, "#!/bin/sh\nprintf '<%s>\\n' \"$@\"\nexit \"${MOCK_EXIT_CODE:-0}\"\n");
        path.toFile().setExecutable(true);
        return path;
    }

    private Path shell() {
        if (Files.isExecutable(Path.of("/bin/sh"))) {
            return Path.of("/bin/sh");
        }
        String programFiles = System.getenv("ProgramFiles");
        Path gitBash = Path.of(programFiles == null ? "C:/Program Files" : programFiles, "Git/bin/bash.exe");
        assumeTrue(Files.isRegularFile(gitBash), "A POSIX shell or Git Bash is required to test the launcher");
        return gitBash;
    }

    private record Result(int exitCode, String output) {
    }

    private Result run(String jar, String javaExecutable, Map<String, String> environment, String... arguments)
            throws Exception {
        Path launcher = temporaryDirectory.resolve("launcher.sh");
        Files.writeString(launcher, RpmLauncher.generate(jar, javaExecutable));
        var command = new java.util.ArrayList<>(List.of(shell().toString(), launcher.toString()));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).directory(temporaryDirectory.toFile())
                .redirectErrorStream(true);
        builder.environment().remove("JAVA_HOME");
        builder.environment().remove("JAVA_OPTS");
        builder.environment().putAll(environment);
        Process process = builder.start();
        boolean finished = process.waitFor(10, TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
        }
        assertTrue(finished, "Launcher did not finish");
        return new Result(process.exitValue(), new String(process.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    @Test
    void passesJvmOptionsBeforeJarAndPreservesApplicationArguments() throws Exception {
        Path java = fakeJava(temporaryDirectory.resolve("java mock"));
        Result result = run("/usr/share/demo/demo.jar", java.toString(),
                Map.of("JAVA_OPTS", "-Xmx128m -Ddemo.marker=launcher-test"), "8081", "two words", "");
        assertEquals(0, result.exitCode(), result.output());
        assertEquals("<-Xmx128m>\n<-Ddemo.marker=launcher-test>\n<-jar>\n"
                + "</usr/share/demo/demo.jar>\n<8081>\n<two words>\n<>\n", result.output());
    }

    @Test
    void runsWithoutOptionalEnvironmentAndPropagatesExitCode() throws Exception {
        Path java = fakeJava(temporaryDirectory.resolve("java"));
        Result result = run("/usr/share/demo/demo.jar", java.toString(), Map.of("MOCK_EXIT_CODE", "7"));
        assertEquals(7, result.exitCode());
        assertEquals("<-jar>\n</usr/share/demo/demo.jar>\n", result.output());
    }

    @Test
    void javaHomeOverridesConfiguredExecutableAndSupportsSpaces() throws Exception {
        Path javaHome = temporaryDirectory.resolve("jdk with spaces");
        fakeJava(javaHome.resolve("bin/java"));
        Result result = run("/usr/share/demo/demo.jar", "missing-java-executable",
                Map.of("JAVA_HOME", javaHome.toString()), "8082");
        assertEquals(0, result.exitCode(), result.output());
        assertEquals("<-jar>\n</usr/share/demo/demo.jar>\n<8082>\n", result.output());
    }

    @Test
    void doesNotExecuteOrExpandShellSyntaxFromJvmOptions() throws Exception {
        Path java = fakeJava(temporaryDirectory.resolve("java"));
        Files.writeString(temporaryDirectory.resolve("-Dpattern=expanded"), "would match a glob");
        Result result = run("/usr/share/demo/demo.jar", java.toString(),
                Map.of("JAVA_OPTS", "-Dpattern=* $(touch injected)"));
        assertEquals(0, result.exitCode(), result.output());
        assertTrue(result.output().contains("<-Dpattern=*>\n<$(touch>\n<injected)>\n"));
        assertFalse(Files.exists(temporaryDirectory.resolve("injected")));
    }

    @Test
    void quotesConfiguredPathsIncludingApostrophes() throws Exception {
        Path java = fakeJava(temporaryDirectory.resolve("java's mock"));
        Result result = run("/usr/share/demo's app/demo.jar", java.toString(), Map.of());
        assertEquals(0, result.exitCode(), result.output());
        assertEquals("<-jar>\n</usr/share/demo's app/demo.jar>\n", result.output());
    }

    @Test
    void rejectsInvalidExecutableSettings() {
        for (String executable : new String[]{"", " ", "java\nexit 0", "java\r", "java\0"}) {
            assertThrows(MojoExecutionException.class,
                    () -> RpmLauncher.generate("/usr/share/demo/demo.jar", executable));
        }
        assertThrows(MojoExecutionException.class,
                () -> RpmLauncher.generate("/usr/share/demo/demo.jar", null));
    }

    @Test
    void launcherIsStagedPackagedAndVerifiedAsExecutable() throws Exception {
        RpmPackage rpmPackage = RpmPackage.create("demo", "1.0.0", 1, "java-21-openjdk-headless", Instant.EPOCH);
        Path jar = Files.writeString(temporaryDirectory.resolve("demo.jar"), "JAR");
        Path launcher = Files.writeString(temporaryDirectory.resolve("launcher.sh"),
                RpmLauncher.generate(rpmPackage.jarPath(), "java"));
        RpmMapping mapping = new RpmMapping();
        mapping.setSource(launcher.toFile());
        mapping.setDestination("/usr/bin/demo");
        mapping.setMode("0755");
        RpmContent content = RpmContent.prepare(rpmPackage, jar, temporaryDirectory, List.of(mapping));
        Path sources = Files.createDirectory(temporaryDirectory.resolve("SOURCES"));
        content.stage(sources);
        Path manifest = temporaryDirectory.resolve("content.properties");
        content.save(manifest);
        RpmContent loaded = RpmContent.load(manifest);
        assertEquals(Files.readString(launcher), Files.readString(sources.resolve("rpm-source-1")));
        String spec = RpmSpec.generate(rpmPackage, "Demo", "Proprietary", "Development/Other", Instant.EPOCH, loaded);
        assertTrue(spec.contains("%attr(0755,root,root) /usr/bin/demo\n"));
        String metadata = "/usr/share/demo|16877|root|root|0\n/usr/share/demo/demo.jar|33188|root|root|0\n"
                + "/usr/bin/demo|33261|root|root|0\n";
        assertDoesNotThrow(() -> loaded.verify(metadata));
        assertThrows(MojoExecutionException.class, () -> loaded.verify(metadata.replace("33261", "33188")));
        assertThrows(MojoExecutionException.class,
                () -> RpmContent.prepare(rpmPackage, jar, temporaryDirectory, List.of(mapping, mapping)));
    }
}
