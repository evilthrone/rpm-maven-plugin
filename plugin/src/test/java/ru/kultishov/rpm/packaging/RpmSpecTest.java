package ru.kultishov.rpm.packaging;

import ru.kultishov.rpm.config.RpmMapping;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RpmSpecTest {
    private static final Instant BUILD_TIME = Instant.parse("2026-09-28T12:34:56Z");

    @TempDir
    Path temporaryDirectory;

    @Test
    void keepsDirectoryTreesWritableDuringInstallationAndPreservesPackagedModes() throws Exception {
        Path assets = Files.createDirectories(temporaryDirectory.resolve("assets/nested/empty"));
        Files.writeString(assets.getParent().resolve("data.txt"), "data");
        Path jar = Files.writeString(temporaryDirectory.resolve("demo.jar"), "JAR payload");
        RpmPackage rpmPackage = RpmPackage.create("demo", "1.0.0", 1,
                "java-21-openjdk-headless", BUILD_TIME);

        for (String mode : List.of("0555", "0500", "0000", "2750")) {
            RpmMapping mapping = new RpmMapping();
            mapping.setSource(temporaryDirectory.resolve("assets").toFile());
            mapping.setDestination("/usr/share/demo/assets");
            mapping.setMode(mode);
            mapping.setFileMode("0640");
            mapping.setOwner("daemon");
            mapping.setGroup("daemon");
            RpmContent content = RpmContent.prepare(rpmPackage, jar, temporaryDirectory, List.of(mapping));
            String spec = RpmSpec.generate(rpmPackage, "Demo", "Proprietary", "Development/Other",
                    BUILD_TIME, content);

            for (String directory : List.of("/usr/share/demo/assets", "/usr/share/demo/assets/nested",
                    "/usr/share/demo/assets/nested/empty")) {
                assertTrue(spec.contains("install -dm 0755 \"%{buildroot}" + directory + "\"\n"), mode);
                assertTrue(spec.contains("%dir %attr(" + mode + ",daemon,daemon) " + directory + "\n"), mode);
            }
            assertTrue(spec.contains("%attr(0640,daemon,daemon) /usr/share/demo/assets/nested/data.txt\n"));
        }
    }

    @Test
    void usesConfiguredGroupAndOwnsOnlyTheApplicationDirectory() throws Exception {
        RpmPackage rpmPackage = RpmPackage.create("billing-service", "2.3.0", 1,
                "java-21-openjdk-headless >= 21.0.1", BUILD_TIME);

        String spec = RpmSpec.generate(rpmPackage, "Billing service", "Proprietary",
                "Networking/Other", BUILD_TIME);

        assertTrue(spec.contains("Group: Networking/Other\n"));
        assertTrue(spec.contains("Requires: java-21-openjdk-headless >= 21.0.1\n"));
        assertTrue(spec.contains("%files\n"
                + "%dir %attr(0755,root,root) /usr/share/billing-service\n"
                + "%attr(0644,root,root) /usr/share/billing-service/billing-service.jar\n"));
    }

    @Test
    void rejectsBlankAndInjectedGroup() throws Exception {
        RpmPackage rpmPackage = RpmPackage.create("demo", "1.0.0", 1,
                "java-21-openjdk-headless", BUILD_TIME);

        for (String group : new String[]{"", " ", "Development/Other\nRequires: injected",
                "%{injected}", "Development\\Other"}) {
            assertThrows(MojoExecutionException.class,
                    () -> RpmSpec.generate(rpmPackage, "Demo", "Proprietary", group, BUILD_TIME));
        }
    }
}
