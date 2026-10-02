package ru.kultishov.rpm.packaging;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RpmSpecTest {
    private static final Instant BUILD_TIME = Instant.parse("2026-09-28T12:34:56Z");

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
