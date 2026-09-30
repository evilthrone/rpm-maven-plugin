package ru.kultishov.rpm;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.artifact.handler.DefaultArtifactHandler;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.artifact.AttachedArtifact;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

@Mojo(name = "build")
public final class BuildRpmMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    private File buildDirectory;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "rpm.rpmbuildExecutable", defaultValue = "rpmbuild")
    private String rpmbuildExecutable;

    @Parameter(property = "rpm.commandTimeoutSeconds", defaultValue = "120")
    private long timeoutSeconds;

    @Parameter(property = "rpm.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException {
        if (skip) {
            getLog().info("Skipping RPM build");
            return;
        }
        RpmWorkspace workspace = new RpmWorkspace(buildDirectory.toPath().toAbsolutePath().normalize());
        try {
            RpmPackage rpmPackage = RpmPackage.load(workspace.metadata());
            RpmContent.load(workspace.contentManifest()).validateSources(workspace.sourcesDirectory());
            Path spec = workspace.spec(rpmPackage);
            Path source = workspace.source(rpmPackage);
            if (!Files.isRegularFile(spec) || Files.isSymbolicLink(spec)
                    || !Files.isRegularFile(source) || Files.isSymbolicLink(source)) {
                throw new MojoExecutionException("RPM spec or source is missing; run rpm:prepare first");
            }
            Path rpmFile = rpmPackage.rpmFile(workspace.root());
            if (Files.isSymbolicLink(rpmFile.getParent())) {
                throw new MojoExecutionException("RPM output directory must not be a symbolic link");
            }
            Files.deleteIfExists(rpmFile);
            Path log = workspace.root().resolve("rpmbuild.log");
            String output = RpmCommand.run(List.of(rpmbuildExecutable, "-bb", "--define",
                    "_topdir " + workspace.root(), spec.toString()), workspace.root(), log, timeoutSeconds);
            if (!Files.isRegularFile(rpmFile) || Files.isSymbolicLink(rpmFile)) {
                throw new MojoExecutionException("rpmbuild succeeded but expected RPM is missing: " + rpmFile
                        + "\n" + output);
            }
            DefaultArtifactHandler handler = new DefaultArtifactHandler("rpm");
            handler.setExtension("rpm");
            AttachedArtifact artifact = new AttachedArtifact(project.getArtifact(), "rpm", "rpm", handler);
            artifact.setFile(rpmFile.toFile());
            artifact.setResolved(true);
            project.addAttachedArtifact(artifact);
            getLog().info("Built and attached RPM: " + rpmFile);
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to read RPM preparation files", e);
        }
    }
}
