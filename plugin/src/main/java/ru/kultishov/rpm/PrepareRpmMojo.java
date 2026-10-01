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
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Mojo(name = "prepare")
public final class PrepareRpmMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    private File buildDirectory;

    @Parameter(defaultValue = "${project.basedir}", readonly = true, required = true)
    private File baseDirectory;

    @Parameter
    private List<RpmMapping> mappings;

    @Parameter
    private RpmService service;

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

    @Parameter(property = "rpm.jreRequirement", defaultValue = "java-21-openjdk-headless")
    private String jreRequirement;

    @Parameter(property = "rpm.javaExecutable", defaultValue = "java")
    private String javaExecutable;

    @Parameter(property = "rpm.group", defaultValue = "Development/Other")
    private String group;

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
            Files.deleteIfExists(workspace.contentManifest());
            if (Files.isSymbolicLink(workspace.source(rpmPackage)) || Files.isSymbolicLink(workspace.spec(rpmPackage))) {
                throw new MojoExecutionException("RPM source or spec path must not be a symbolic link");
            }
            Path launcher = workspace.root().resolve("launcher.sh");
            RpmContent.rejectSymlink(launcher);
            Files.writeString(launcher, RpmLauncher.generate(rpmPackage.jarPath(), javaExecutable),
                    StandardCharsets.UTF_8);
            RpmMapping launcherMapping = new RpmMapping();
            launcherMapping.setSource(launcher.toFile());
            launcherMapping.setDestination("/usr/bin/" + rpmPackage.name());
            launcherMapping.setMode("0755");
            List<RpmMapping> packageMappings = new ArrayList<>();
            packageMappings.add(launcherMapping);
            RpmSystemd.Service preparedService = service == null ? null
                    : RpmSystemd.prepare(service, rpmPackage, baseDirectory.toPath());
            if (preparedService != null) {
                Path unit = workspace.root().resolve("service.unit");
                RpmContent.rejectSymlink(unit);
                Files.writeString(unit, preparedService.unit(), StandardCharsets.UTF_8);
                RpmMapping unitMapping = new RpmMapping();
                unitMapping.setSource(unit.toFile());
                unitMapping.setDestination(preparedService.destination());
                unitMapping.setMode("0644");
                packageMappings.add(unitMapping);
            }
            if (mappings != null) {
                packageMappings.addAll(mappings);
            }
            RpmContent content = RpmContent.prepare(rpmPackage, jar, baseDirectory.toPath(), packageMappings);
            String spec = RpmSpec.generate(rpmPackage, summary, license, group, buildTime, content, preparedService);
            content.stage(workspace.sourcesDirectory());
            Files.writeString(workspace.spec(rpmPackage),
                    spec, StandardCharsets.UTF_8);
            content.save(workspace.contentManifest());
            rpmPackage.save(workspace.metadata());
            getLog().info("Prepared RPM spec: " + workspace.spec(rpmPackage));
        } catch (IOException e) {
            throw new MojoExecutionException("Failed to prepare RPM files under " + workspace.root(), e);
        }
    }
}
