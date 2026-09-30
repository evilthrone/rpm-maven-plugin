package ru.kultishov.rpm;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RpmPackageTest {
    private static final Instant BUILD_TIME = Instant.parse("2026-09-28T12:34:56Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void convertsReleaseCandidate() throws Exception {
        RpmPackage rpmPackage = RpmPackage.create("demo", "1.0.0-RC1", 2,
                "jre-openjdk-headless", BUILD_TIME);

        assertEquals("1.0.0~rc1", rpmPackage.version());
        assertEquals("alt2", rpmPackage.release());
        assertEquals("/usr/share/demo/demo.jar", rpmPackage.jarPath());
    }

    @Test
    void convertsSnapshotWithFixedUtcTime() throws Exception {
        RpmPackage rpmPackage = RpmPackage.create("demo", "1.0.0-SNAPSHOT", 1,
                "jre-openjdk-headless", BUILD_TIME);

        assertEquals("1.0.0~snapshot.20260928123456", rpmPackage.version());
        assertEquals("alt1", rpmPackage.release());
    }

    @Test
    void rejectsUnsupportedVersionAndUnsafeName() {
        assertThrows(MojoExecutionException.class,
                () -> RpmPackage.create("demo", "1.0.0-beta1", 1, "jre-openjdk-headless", BUILD_TIME));
        assertThrows(MojoExecutionException.class,
                () -> RpmPackage.create("../demo", "1.0.0", 1, "jre-openjdk-headless", BUILD_TIME));
    }

    @Test
    void keepsMetadataConsistentAcrossGoals() throws Exception {
        RpmPackage original = RpmPackage.create("demo", "1.0.0-SNAPSHOT", 1,
                "jre-openjdk-headless", BUILD_TIME);
        Path metadata = temporaryDirectory.resolve("package.properties");

        original.save(metadata);

        assertEquals(original, RpmPackage.load(metadata));
    }

    @Test
    void specContainsPackageAndVerificationFields() throws Exception {
        RpmPackage rpmPackage = RpmPackage.create("demo", "1.0.0", 1,
                "jre-openjdk-headless", BUILD_TIME);

        String spec = RpmSpec.generate(rpmPackage, "Demo service", "Proprietary", BUILD_TIME);

        assertTrue(spec.contains("Version: 1.0.0\nRelease: alt1\n"));
        assertTrue(spec.contains("Requires: jre-openjdk-headless\n"));
        assertTrue(spec.contains("%attr(0644,root,root) /usr/share/demo/demo.jar\n"));
        assertTrue(spec.contains("%changelog\n* Mon Sep 28 2026"));
    }
}
