package ru.kultishov.rpm.packaging;

import org.apache.maven.plugin.MojoExecutionException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public final class RpmWorkspace {
    private final Path root;

    public RpmWorkspace(Path buildDirectory) throws MojoExecutionException {
        if (Files.isSymbolicLink(buildDirectory)) {
            throw new MojoExecutionException("Maven build directory must not be a symbolic link: " + buildDirectory);
        }
        root = buildDirectory.resolve("rpm-work").toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root)) {
            throw new MojoExecutionException("RPM work directory must not be a symbolic link: " + root);
        }
    }

    public Path root() {
        return root;
    }

    public Path metadata() {
        return root.resolve("package.properties");
    }

    public Path contentManifest() {
        return root.resolve("content.properties");
    }

    public Path sourcesDirectory() {
        return root.resolve("SOURCES");
    }

    public Path spec(RpmPackage rpmPackage) {
        return root.resolve("SPECS").resolve(rpmPackage.name() + ".spec");
    }

    public Path source(RpmPackage rpmPackage) {
        return root.resolve("SOURCES").resolve(rpmPackage.name() + ".jar");
    }

    public void create() throws IOException, MojoExecutionException {
        for (String name : new String[]{"SPECS", "SOURCES", "BUILD", "BUILDROOT", "RPMS"}) {
            Path directory = root.resolve(name);
            if (Files.isSymbolicLink(directory)) {
                throw new MojoExecutionException("RPM work path must not be a symbolic link: " + directory);
            }
            Files.createDirectories(directory);
        }
    }
}
