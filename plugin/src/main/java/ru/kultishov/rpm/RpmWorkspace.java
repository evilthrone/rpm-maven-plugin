package ru.kultishov.rpm;

import org.apache.maven.plugin.MojoExecutionException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class RpmWorkspace {
    private final Path root;

    RpmWorkspace(Path buildDirectory) throws MojoExecutionException {
        if (Files.isSymbolicLink(buildDirectory)) {
            throw new MojoExecutionException("Maven build directory must not be a symbolic link: " + buildDirectory);
        }
        root = buildDirectory.resolve("rpm-work").toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root)) {
            throw new MojoExecutionException("RPM work directory must not be a symbolic link: " + root);
        }
    }

    Path root() {
        return root;
    }

    Path metadata() {
        return root.resolve("package.properties");
    }

    Path contentManifest() {
        return root.resolve("content.properties");
    }

    Path sourcesDirectory() {
        return root.resolve("SOURCES");
    }

    Path spec(RpmPackage rpmPackage) {
        return root.resolve("SPECS").resolve(rpmPackage.name() + ".spec");
    }

    Path source(RpmPackage rpmPackage) {
        return root.resolve("SOURCES").resolve(rpmPackage.name() + ".jar");
    }

    void create() throws IOException, MojoExecutionException {
        for (String name : new String[]{"SPECS", "SOURCES", "BUILD", "BUILDROOT", "RPMS"}) {
            Path directory = root.resolve(name);
            if (Files.isSymbolicLink(directory)) {
                throw new MojoExecutionException("RPM work path must not be a symbolic link: " + directory);
            }
            Files.createDirectories(directory);
        }
    }
}
