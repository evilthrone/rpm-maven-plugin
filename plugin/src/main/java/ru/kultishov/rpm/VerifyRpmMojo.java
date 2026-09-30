package ru.kultishov.rpm;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

@Mojo(name = "verify")
public final class VerifyRpmMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    private File buildDirectory;

    @Parameter(property = "rpm.rpmExecutable", defaultValue = "rpm")
    private String rpmExecutable;

    @Parameter(property = "rpm.commandTimeoutSeconds", defaultValue = "120")
    private long timeoutSeconds;

    @Parameter(property = "rpm.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Skipping RPM verification");
            return;
        }
        RpmWorkspace workspace = new RpmWorkspace(buildDirectory.toPath().toAbsolutePath().normalize());
        try {
            RpmPackage rpmPackage = RpmPackage.load(workspace.metadata());
            Path rpmFile = rpmPackage.rpmFile(workspace.root());
            if (!Files.isRegularFile(rpmFile) || Files.isSymbolicLink(rpmFile)) {
                throw new MojoExecutionException("RPM is missing; run rpm:build first: " + rpmFile);
            }
            String metadata = query(workspace, "metadata", "-qp", "--queryformat",
                    "%{NAME}|%{VERSION}|%{RELEASE}|%{ARCH}", rpmFile.toString()).trim();
            String expected = String.join("|", rpmPackage.name(), rpmPackage.version(), rpmPackage.release(), "noarch");
            if (!expected.equals(metadata)) {
                throw new MojoExecutionException("Unexpected RPM metadata: " + metadata + "; expected: " + expected);
            }
            String requirements = query(workspace, "requirements", "-qp", "--requires", rpmFile.toString());
            if (requirements.lines().map(line -> line.trim().replaceAll("[ \\t]+", " "))
                    .noneMatch(rpmPackage.jreRequirement()::equals)) {
                throw new MojoExecutionException("RPM lacks JRE requirement: " + rpmPackage.jreRequirement());
            }
            String files = query(workspace, "files", "-qpl", rpmFile.toString());
            if (files.lines().noneMatch(rpmPackage.jarPath()::equals)) {
                throw new MojoExecutionException("RPM lacks expected JAR path: " + rpmPackage.jarPath());
            }
            if (files.lines().noneMatch(rpmPackage.installDirectory()::equals)) {
                throw new MojoExecutionException("RPM lacks expected application directory: " + rpmPackage.installDirectory());
            }
            String changelog = query(workspace, "changelog", "-qp", "--changelog", rpmFile.toString());
            if (changelog.isBlank()) {
                throw new MojoExecutionException("RPM changelog is empty");
            }
            getLog().info("Verified RPM metadata, JRE requirement, application directory, JAR path, and changelog: " + rpmFile);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to read RPM preparation files", e);
        }
    }

    private String query(RpmWorkspace workspace, String label, String... arguments) throws MojoExecutionException {
        List<String> command = new ArrayList<>();
        command.add(rpmExecutable);
        command.addAll(List.of(arguments));
        return RpmCommand.run(command, workspace.root(), workspace.root().resolve("rpm-" + label + ".log"), timeoutSeconds);
    }
}
