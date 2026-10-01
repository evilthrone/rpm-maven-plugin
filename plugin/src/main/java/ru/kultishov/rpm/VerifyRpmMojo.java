package ru.kultishov.rpm;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

@Mojo(name = "verify")
public final class VerifyRpmMojo extends AbstractRpmToolMojo {
    @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    private File buildDirectory;

    @Parameter(property = "rpm.rpmExecutable", defaultValue = "rpm")
    private String rpmExecutable;

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
            try (RpmEnvironment environment = RpmEnvironment.forVerify(this, workspace)) {
                String metadata = environment.query(rpmExecutable, rpmFile, "metadata", "-qp", "--queryformat",
                        "%{NAME}|%{VERSION}|%{RELEASE}|%{ARCH}").trim();
                String expected = String.join("|", rpmPackage.name(), rpmPackage.version(), rpmPackage.release(), "noarch");
                if (!expected.equals(metadata)) {
                    throw new MojoExecutionException("Unexpected RPM metadata: " + metadata + "; expected: " + expected);
                }
                String requirements = environment.query(rpmExecutable, rpmFile, "requirements", "-qp", "--requires");
                if (requirements.lines().map(line -> line.trim().replaceAll("[ \\t]+", " "))
                        .noneMatch(rpmPackage.jreRequirement()::equals)) {
                    throw new MojoExecutionException("RPM lacks JRE requirement: " + rpmPackage.jreRequirement());
                }
                String files = environment.query(rpmExecutable, rpmFile, "files", "-qp", "--queryformat",
                        "[%{FILENAMES}|%{FILEMODES}|%{FILEUSERNAME}|%{FILEGROUPNAME}|%{FILEFLAGS}\\n]");
                RpmContent.load(workspace.contentManifest()).verify(files);
                String changelog = environment.query(rpmExecutable, rpmFile, "changelog", "-qp", "--changelog");
                if (changelog.isBlank()) {
                    throw new MojoExecutionException("RPM changelog is empty");
                }
                getLog().info("Verified RPM metadata, JRE requirement, file attributes, configuration flags, and changelog: " + rpmFile);
            }
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to read RPM verification files", e);
        }
    }
}
