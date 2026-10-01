package ru.kultishov.rpm;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RpmDockerTest {
    private static final String MESSAGE = "Docker engine is not available now. Please run Docker Desktop.";

    @TempDir
    Path temporaryDirectory;

    private List<String> fakeDocker(String mode) {
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        return List.of(java, "-cp", System.getProperty("java.class.path"), DockerProbe.class.getName(), mode);
    }

    @Test
    void acceptsRunningEngineAndRecordsServerVersion() throws Exception {
        RpmEnvironment.checkDockerAvailable(fakeDocker("available"), temporaryDirectory, 10);
        assertEquals("29.test", Files.readString(temporaryDirectory.resolve("container-engine.log")).trim());
    }

    @Test
    void explainsUnavailableEngineAndKeepsOriginalDiagnostic() throws Exception {
        MojoExecutionException failure = assertThrows(MojoExecutionException.class,
                () -> RpmEnvironment.checkDockerAvailable(fakeDocker("unavailable"), temporaryDirectory, 10));
        assertTrue(failure.getMessage().startsWith(MESSAGE));
        assertTrue(failure.getMessage().contains("container-engine.log"));
        assertTrue(failure.getCause().getMessage().contains("Cannot connect to the Docker daemon"));
        assertTrue(Files.readString(temporaryDirectory.resolve("container-engine.log"))
                .contains("Cannot connect to the Docker daemon"));
    }

    @Test
    void buildReportsMissingDockerBeforePreparingImage() throws Exception {
        RpmWorkspace workspace = new RpmWorkspace(temporaryDirectory);
        workspace.create();
        BuildRpmMojo settings = new BuildRpmMojo();
        settings.buildMode = "container";
        settings.dockerExecutable = temporaryDirectory.resolve("missing-docker").toString();
        MojoExecutionException failure = assertThrows(MojoExecutionException.class,
                () -> RpmEnvironment.forBuild(settings, workspace));
        assertTrue(failure.getMessage().startsWith(MESSAGE));
        assertTrue(failure.getCause().getMessage().contains("Cannot start"));
        assertFalse(Files.exists(workspace.root().resolve("container-setup.log")));
    }

    @Test
    void verificationChecksEngineBeforeCreatingContainer() throws Exception {
        RpmWorkspace workspace = new RpmWorkspace(temporaryDirectory);
        workspace.create();
        Files.writeString(workspace.root().resolve("environment.txt"), "sha256:" + "a".repeat(64));
        VerifyRpmMojo settings = new VerifyRpmMojo();
        settings.dockerExecutable = temporaryDirectory.resolve("missing-docker").toString();
        try (RpmEnvironment environment = RpmEnvironment.forVerify(settings, workspace)) {
            MojoExecutionException failure = assertThrows(MojoExecutionException.class,
                    () -> environment.query("rpm", workspace.root().resolve("package.rpm"), "metadata", "-qp"));
            assertTrue(failure.getMessage().startsWith(MESSAGE));
            assertFalse(Files.exists(workspace.root().resolve("container-create.log")));
        }
    }

    @Test
    void localEnvironmentDoesNotRequireDocker() throws Exception {
        RpmWorkspace workspace = new RpmWorkspace(temporaryDirectory);
        workspace.create();
        BuildRpmMojo settings = new BuildRpmMojo();
        settings.buildMode = "local";
        settings.dockerExecutable = temporaryDirectory.resolve("missing-docker").toString();
        try (RpmEnvironment environment = RpmEnvironment.forBuild(settings, workspace)) {
            assertNotNull(environment);
            assertFalse(Files.exists(workspace.root().resolve("container-engine.log")));
        }
    }

    @Test
    void reportsUnresponsiveEngineAndTerminatesCheck() throws Exception {
        MojoExecutionException failure = assertThrows(MojoExecutionException.class,
                () -> RpmEnvironment.checkDockerAvailable(fakeDocker("unresponsive"), temporaryDirectory, 1));
        assertTrue(failure.getMessage().startsWith(MESSAGE));
        assertTrue(failure.getCause().getMessage().contains("timed out after 1"));
        long pid = Long.parseLong(Files.readString(temporaryDirectory.resolve("container-engine.log")).trim());
        var process = ProcessHandle.of(pid);
        if (process.isPresent()) {
            process.get().onExit().get(5, TimeUnit.SECONDS);
            assertFalse(process.get().isAlive());
        }
    }

    @Test
    void invalidTimeoutIsNotReportedAsUnavailableDocker() {
        MojoExecutionException failure = assertThrows(MojoExecutionException.class,
                () -> RpmEnvironment.checkDockerAvailable(fakeDocker("available"), temporaryDirectory, 0));
        assertEquals("rpm.commandTimeoutSeconds must be positive", failure.getMessage());
    }

    public static class DockerProbe {
        public static void main(String[] arguments) throws Exception {
            if (arguments.length != 4 || !arguments[1].equals("info") || !arguments[2].equals("--format")
                    || !arguments[3].equals("{{.ServerVersion}}")) {
                throw new IllegalArgumentException("Unexpected Docker availability command");
            }
            switch (arguments[0]) {
                case "available" -> System.out.println("29.test");
                case "unavailable" -> {
                    System.err.println("Cannot connect to the Docker daemon");
                    System.exit(1);
                }
                case "unresponsive" -> {
                    System.out.println(ProcessHandle.current().pid());
                    System.out.flush();
                    Thread.sleep(60_000);
                }
                default -> throw new IllegalArgumentException("Unknown probe mode");
            }
        }
    }
}
