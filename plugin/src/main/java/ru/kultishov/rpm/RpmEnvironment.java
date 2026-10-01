package ru.kultishov.rpm;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Executes tools locally or in containers created from the same immutable image. */
final class RpmEnvironment implements AutoCloseable {
    private final AbstractRpmToolMojo settings;
    private final RpmWorkspace workspace;
    private final Log log;
    private final String image;
    private String container;

    private RpmEnvironment(AbstractRpmToolMojo settings, RpmWorkspace workspace, String image) {
        this.settings = settings;
        this.workspace = workspace;
        this.log = settings.getLog();
        this.image = image;
    }

    static boolean useContainer(String mode, String osRelease) throws MojoExecutionException {
        return switch (mode) {
            case "container" -> true;
            case "local" -> false;
            case "auto" -> osRelease.lines().noneMatch(line -> line.matches("ID=(\"altlinux\"|altlinux)"));
            default -> throw new MojoExecutionException("rpm.buildMode must be auto, local, or container");
        };
    }

    static RpmEnvironment forBuild(AbstractRpmToolMojo settings, RpmWorkspace workspace)
            throws IOException, MojoExecutionException {
        Path release = Path.of("/etc/os-release");
        String osRelease = System.getProperty("os.name").equals("Linux") && Files.isRegularFile(release)
                ? Files.readString(release) : "";
        if (!useContainer(settings.buildMode, osRelease)) {
            settings.getLog().info("Using local RPM tools");
            return new RpmEnvironment(settings, workspace, "local");
        }
        if (!settings.containerImage.matches("[A-Za-z0-9][A-Za-z0-9./:_@-]*")) {
            throw new MojoExecutionException("Invalid container image reference");
        }
        Path context = workspace.root().resolve("container");
        for (Path path : List.of(context, context.resolve("setup.sh"), context.resolve("Dockerfile"),
                context.resolve("image.id"))) {
            if (Files.isSymbolicLink(path)) {
                throw new MojoExecutionException("Container setup path must not be a symbolic link: " + path);
            }
        }
        Files.createDirectories(context);
        Files.writeString(context.resolve("setup.sh"), RpmContainerSetup.script(
                RpmContainerSetup.repositories(settings.repositoryMirrors, settings.repositories),
                settings.repositoryTimeoutSeconds));
        Files.writeString(context.resolve("Dockerfile"), "FROM " + settings.containerImage + "\n"
                + "COPY setup.sh /setup.sh\nRUN sh /setup.sh\n"
                + "RUN mkdir -p /work/SPECS /work/SOURCES /work/BUILD /work/BUILDROOT /work/RPMS\n"
                + "RUN groupadd -r rpm-builder && useradd -r -g rpm-builder -m -d /home/rpm-builder -s /bin/sh rpm-builder"
                + " && chown -R rpm-builder:rpm-builder /work\n"
                + "USER rpm-builder\n"
                + "WORKDIR /work\n");
        RpmEnvironment environment = new RpmEnvironment(settings, workspace, "pending");
        settings.getLog().info("Preparing ALT p11 container tools; log: " + workspace.root().resolve("container-setup.log"));
        Files.deleteIfExists(context.resolve("image.id"));
        environment.docker("setup", settings.containerTimeoutSeconds, "build", "--platform", "linux/amd64",
                "--progress=plain", "--iidfile", "container/image.id", "container");
        String image = Files.readString(context.resolve("image.id")).trim();
        validateImage(image);
        settings.getLog().info("Using ALT container image: " + image);
        return new RpmEnvironment(settings, workspace, image);
    }

    static RpmEnvironment forVerify(AbstractRpmToolMojo settings, RpmWorkspace workspace)
            throws IOException, MojoExecutionException {
        Path receipt = workspace.root().resolve("environment.txt");
        if (!Files.isRegularFile(receipt) || Files.isSymbolicLink(receipt)) {
            throw new MojoExecutionException("RPM build environment is missing; run rpm:build first");
        }
        String image = Files.readString(receipt).trim();
        if (!image.equals("local")) {
            validateImage(image);
        }
        return new RpmEnvironment(settings, workspace, image);
    }

    private static void validateImage(String image) throws MojoExecutionException {
        if (!image.matches("sha256:[0-9a-f]{64}")) {
            throw new MojoExecutionException("Invalid container image ID: " + image);
        }
    }

    String build(RpmPackage rpm, String executable) throws MojoExecutionException, IOException {
        String output;
        if (image.equals("local")) {
            output = RpmCommand.run(List.of(executable, "-bb", "--define", "_topdir " + workspace.root(),
                    workspace.spec(rpm).toString()), workspace.root(), workspace.root().resolve("rpmbuild.log"),
                    settings.timeoutSeconds);
        } else {
            create(List.of("rpmbuild", "-bb", "--define", "_topdir /work", "/work/SPECS/" + rpm.name() + ".spec"));
            docker("copy-sources", settings.timeoutSeconds, "cp", "SOURCES/.", container + ":/work/SOURCES");
            docker("copy-specs", settings.timeoutSeconds, "cp", "SPECS/.", container + ":/work/SPECS");
            output = docker("build", settings.timeoutSeconds, "start", "--attach", container);
            String code = docker("exit", settings.timeoutSeconds, "inspect", "--format", "{{.State.ExitCode}}", container).trim();
            if (!code.equals("0")) {
                throw new MojoExecutionException("Container rpmbuild exited with code " + code + "; log: "
                        + workspace.root().resolve("container-build.log") + "\n" + tail(output));
            }
            Files.createDirectories(rpm.rpmFile(workspace.root()).getParent());
            docker("copy-rpm", settings.timeoutSeconds, "cp", container + ":/work/RPMS/noarch/"
                    + rpm.rpmFile(workspace.root()).getFileName(), "RPMS/noarch/");
        }
        Files.writeString(workspace.root().resolve("environment.txt"), image + "\n");
        return output;
    }

    String query(String executable, Path rpmFile, String label, String... arguments) throws MojoExecutionException {
        List<String> command = new ArrayList<>(List.of(arguments));
        if (image.equals("local")) {
            command.addFirst(executable);
            command.add(rpmFile.toString());
            return RpmCommand.run(command, workspace.root(), workspace.root().resolve("rpm-" + label + ".log"),
                    settings.timeoutSeconds);
        }
        if (container == null) {
            create(List.of("sleep", "infinity"));
            docker("copy-query-rpm", settings.timeoutSeconds, "cp", workspace.root().relativize(rpmFile).toString(),
                    container + ":/work/package.rpm");
            docker("start-query", settings.timeoutSeconds, "start", container);
        }
        command.addFirst("rpm");
        command.addFirst(container);
        command.addFirst("exec");
        command.add("/work/package.rpm");
        return docker("rpm-" + label, settings.timeoutSeconds, command.toArray(String[]::new));
    }

    private void create(List<String> command) throws MojoExecutionException {
        container = "rpm-maven-" + UUID.randomUUID();
        List<String> arguments = new ArrayList<>(List.of("create", "--platform", "linux/amd64", "--name", container, image));
        arguments.addAll(command);
        docker("create", settings.timeoutSeconds, arguments.toArray(String[]::new));
    }

    private String docker(String label, long timeout, String... arguments) throws MojoExecutionException {
        List<String> command = new ArrayList<>(List.of(settings.dockerExecutable));
        command.addAll(List.of(arguments));
        return RpmCommand.run(command, workspace.root(), workspace.root().resolve("container-" + label + ".log"), timeout);
    }

    private static String tail(String output) {
        return output.substring(Math.max(0, output.length() - 4000));
    }

    @Override
    public void close() {
        if (container != null) {
            boolean interrupted = Thread.interrupted();
            try {
                docker("cleanup", 15, "rm", "--force", container);
            } catch (MojoExecutionException e) {
                log.warn("Could not remove temporary container " + container + ": " + e.getMessage());
            } finally {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }
}
