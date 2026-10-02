package ru.kultishov.rpm.mojo;

import ru.kultishov.rpm.packaging.RpmContent;
import ru.kultishov.rpm.build.RpmEnvironment;
import ru.kultishov.rpm.packaging.RpmPackage;
import ru.kultishov.rpm.packaging.RpmWorkspace;

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

@Mojo(name = "build")
public final class BuildRpmMojo extends AbstractRpmToolMojo {
    @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    private File buildDirectory;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "rpm.rpmbuildExecutable", defaultValue = "rpmbuild")
    private String rpmbuildExecutable;

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
            Files.deleteIfExists(workspace.root().resolve("environment.txt"));
            String output;
            try (RpmEnvironment environment = RpmEnvironment.forBuild(buildSettings(), workspace, getLog())) {
                output = environment.build(rpmPackage, rpmbuildExecutable);
            }
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
            throw new MojoExecutionException("Failed to prepare or read RPM build files", e);
        }
    }
}
