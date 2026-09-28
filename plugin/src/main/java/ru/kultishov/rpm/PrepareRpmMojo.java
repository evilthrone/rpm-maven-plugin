package ru.kultishov.rpm;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;

@Mojo(name = "prepare")
public final class PrepareRpmMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    private File buildDirectory;

    @Parameter(defaultValue = "${project.build.finalName}", readonly = true, required = true)
    private String finalName;

    @Parameter(defaultValue = "${project.version}", readonly = true, required = true)
    private String projectVersion;

    @Parameter(defaultValue = "${project.packaging}", readonly = true, required = true)
    private String packaging;

    @Parameter(property = "rpm.name", defaultValue = "${project.artifactId}")
    private String rpmName;

    @Parameter(property = "rpm.release", defaultValue = "1")
    private int releaseNumber;

    @Parameter(property = "rpm.jreRequirement", defaultValue = "jre-openjdk-headless")
    private String jreRequirement;

    @Parameter(property = "rpm.summary", defaultValue = "Java application packaged by Maven")
    private String summary;

    @Parameter(property = "rpm.license", defaultValue = "Proprietary")
    private String license;

    @Parameter(property = "rpm.buildTime")
    private String fixedBuildTime;

    @Override
    public void execute() throws MojoExecutionException {
        if (!"jar".equals(packaging)) {
            throw new MojoExecutionException("rpm:prepare currently supports only jar projects");
        }
        Instant buildTime;
        try {
            buildTime = fixedBuildTime == null || fixedBuildTime.isBlank()
                    ? Instant.now() : Instant.parse(fixedBuildTime);
        } catch (RuntimeException e) {
            throw new MojoExecutionException("rpm.buildTime must be an ISO-8601 instant, for example 2026-09-28T12:00:00Z", e);
        }
        RpmPackage rpmPackage = RpmPackage.create(rpmName, projectVersion, releaseNumber, jreRequirement, buildTime);
        Path buildPath = buildDirectory.toPath().toAbsolutePath().normalize();
        Path jar = buildPath.resolve(finalName + ".jar").normalize();
        if (!jar.startsWith(buildPath) || !Files.isRegularFile(jar) || Files.isSymbolicLink(jar)) {
            throw new MojoExecutionException("Project JAR is missing or unsafe; run Maven package first: " + jar);
        }
        RpmWorkspace workspace = new RpmWorkspace(buildPath);
        try {
            workspace.create();
            Files.deleteIfExists(workspace.metadata());
            if (Files.isSymbolicLink(workspace.source(rpmPackage)) || Files.isSymbolicLink(workspace.spec(rpmPackage))) {
                throw new MojoExecutionException("RPM source or spec path must not be a symbolic link");
            }
            Files.copy(jar, workspace.source(rpmPackage), StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(workspace.spec(rpmPackage),
                    RpmSpec.generate(rpmPackage, summary, license, buildTime), StandardCharsets.UTF_8);
            rpmPackage.save(workspace.metadata());
            getLog().info("Prepared RPM spec: " + workspace.spec(rpmPackage));
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to prepare RPM files under " + workspace.root(), e);
        }
    }
}
