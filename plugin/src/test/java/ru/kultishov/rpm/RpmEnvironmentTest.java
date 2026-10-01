package ru.kultishov.rpm;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RpmEnvironmentTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void detectsAltByIdRatherThanDistributionNameOrRelatedIds() throws Exception {
        assertFalse(RpmEnvironment.useContainer("auto", "NAME=ALT Container\nID=altlinux\n"));
        assertFalse(RpmEnvironment.useContainer("auto", "ID=\"altlinux\"\n"));
        assertTrue(RpmEnvironment.useContainer("auto", "NAME=ALT\nID=ubuntu\nID_LIKE=altlinux"));
        assertTrue(RpmEnvironment.useContainer("auto", ""));
    }

    @Test
    void supportsExplicitModesAndRejectsUnknownMode() throws Exception {
        assertFalse(RpmEnvironment.useContainer("local", ""));
        assertTrue(RpmEnvironment.useContainer("container", "ID=altlinux"));
        assertThrows(MojoExecutionException.class, () -> RpmEnvironment.useContainer("docker", ""));
    }

    @Test
    void usesOnlyOfficialRepositoryByDefault() throws Exception {
        assertEquals(List.of(RpmContainerSetup.OFFICIAL_REPOSITORY), RpmContainerSetup.repositories(null, null));
    }

    @Test
    void preservesFallbackOrderAndSupportsCommandLineOverride() throws Exception {
        assertEquals(List.of("http://127.0.0.1:9", RpmContainerSetup.OFFICIAL_REPOSITORY),
                RpmContainerSetup.repositories(List.of("http://unused.example"),
                        "http://127.0.0.1:9/," + RpmContainerSetup.OFFICIAL_REPOSITORY));
    }

    @Test
    void rejectsEmptyAndUnsafeRepositoryUrls() {
        assertThrows(MojoExecutionException.class, () -> RpmContainerSetup.repositories(List.of(), null));
        for (String url : List.of("", "ftp://example.org", "https://user:password@example.org",
                "http://example.org/';exit 0;#", "https://example.org\nRUN echo unsafe", "http://example.org?q=1")) {
            assertThrows(MojoExecutionException.class, () -> RpmContainerSetup.repositories(null, url), url);
        }
    }

    @Test
    void rejectsInvalidRepositoryTimeout() {
        assertThrows(MojoExecutionException.class, () -> RpmContainerSetup.script(List.of("http://example.org"), 0));
        assertThrows(MojoExecutionException.class, () -> RpmContainerSetup.script(List.of("http://example.org"), 3601));
    }

    @Test
    void verificationRequiresRecordedBuildEnvironment() throws Exception {
        RpmWorkspace workspace = new RpmWorkspace(temporaryDirectory);
        workspace.create();
        assertThrows(MojoExecutionException.class, () -> RpmEnvironment.forVerify(new VerifyRpmMojo(), workspace));
        Files.writeString(workspace.root().resolve("environment.txt"), "alt:latest");
        assertThrows(MojoExecutionException.class, () -> RpmEnvironment.forVerify(new VerifyRpmMojo(), workspace));
        Files.writeString(workspace.root().resolve("environment.txt"), "sha256:" + "a".repeat(64));
        try (RpmEnvironment environment = RpmEnvironment.forVerify(new VerifyRpmMojo(), workspace)) {
            assertNotNull(environment);
        }
    }
}
