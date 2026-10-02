package ru.kultishov.rpm;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RpmSystemdTest {
    @TempDir
    Path temporaryDirectory;

    private RpmPackage rpm() throws Exception {
        return RpmPackage.create("demo", "1.0.0", 1, "java-21-openjdk-headless", Instant.EPOCH);
    }

    @Test
    void generatesServiceWithDefaultAccountAndLauncher() throws Exception {
        var service = RpmSystemd.prepare(new RpmService(), rpm(), temporaryDirectory);
        assertEquals("demo", service.user());
        assertEquals("demo", service.group());
        assertEquals("/lib/systemd/system/demo.service", service.destination());
        assertTrue(service.unit().contains("Type=simple\nUser=demo\nGroup=demo\nExecStart=/usr/bin/demo\n"));
        assertTrue(service.unit().contains("Restart=on-failure\n"));
        assertTrue(service.unit().contains("WantedBy=multi-user.target\n"));
    }

    @Test
    void treatsJavaSigtermExitAsSuccessfulWithoutIgnoringOtherFailures() throws Exception {
        var service = RpmSystemd.prepare(new RpmService(), rpm(), temporaryDirectory);
        assertTrue(service.unit().contains("\nSuccessExitStatus=143\n"));
        assertEquals(1, service.unit().lines().filter(line -> line.startsWith("SuccessExitStatus=")).count());
        assertTrue(service.unit().contains("\nRestart=on-failure\n"));
        assertFalse(service.unit().contains("\nExecStart=-"));
    }

    @Test
    void escapesSystemdSpecifiersAndPreservesArgumentBoundaries() throws Exception {
        RpmService configuration = new RpmService();
        configuration.setName("demo-http");
        configuration.setUser("demo_user");
        configuration.setGroup("demo_group");
        configuration.setArguments(List.of("8081", "two words", "$HOME", "%n", "a\"b", ""));
        configuration.setEnvironment(Map.of("JAVA_OPTS", "-Xmx128m", "MARKER", "%n $HOME \\\""));
        var service = RpmSystemd.prepare(configuration, rpm(), temporaryDirectory);
        assertEquals("/lib/systemd/system/demo-http.service", service.destination());
        assertTrue(service.unit().contains("ExecStart=/usr/bin/demo \"8081\" \"two words\" \"$$HOME\" \"%%n\" \"a\\\"b\" \"\"\n"));
        assertTrue(service.unit().contains("Environment=\"JAVA_OPTS=-Xmx128m\"\n"));
        assertTrue(service.unit().contains("Environment=\"MARKER=%%n $HOME"));
    }

    @Test
    void rejectsUnsafeAccountNamesAndRoot() throws Exception {
        for (String name : List.of("root", "demo;id", "demo\nroot", "-demo", "demo%name")) {
            RpmService configuration = new RpmService();
            configuration.setUser(name);
            assertThrows(MojoExecutionException.class,
                    () -> RpmSystemd.prepare(configuration, rpm(), temporaryDirectory));
            configuration.setUser("demo");
            configuration.setGroup(name);
            assertThrows(MojoExecutionException.class,
                    () -> RpmSystemd.prepare(configuration, rpm(), temporaryDirectory));
        }
    }

    @Test
    void rejectsInvalidUnitNameAndRestartPolicy() throws Exception {
        RpmService configuration = new RpmService();
        configuration.setName("../demo");
        assertThrows(MojoExecutionException.class,
                () -> RpmSystemd.prepare(configuration, rpm(), temporaryDirectory));
        configuration.setName("demo");
        configuration.setRestart("always\nUser=root");
        assertThrows(MojoExecutionException.class,
                () -> RpmSystemd.prepare(configuration, rpm(), temporaryDirectory));
    }

    @Test
    void rejectsDirectiveInjectionThroughArgumentsAndEnvironment() throws Exception {
        RpmService configuration = new RpmService();
        configuration.setArguments(List.of("8081\nUser=root"));
        assertThrows(MojoExecutionException.class,
                () -> RpmSystemd.prepare(configuration, rpm(), temporaryDirectory));
        configuration.setArguments(List.of());
        configuration.setEnvironment(Map.of("BAD=NAME", "value"));
        assertThrows(MojoExecutionException.class,
                () -> RpmSystemd.prepare(configuration, rpm(), temporaryDirectory));
        configuration.setEnvironment(Map.of("JAVA_OPTS", "value\r\nUser=root"));
        assertThrows(MojoExecutionException.class,
                () -> RpmSystemd.prepare(configuration, rpm(), temporaryDirectory));
    }

    @Test
    void acceptsReadyUnitWithMatchingAccountAndNormalizesCrLf() throws Exception {
        String unit = "[Unit]\r\nDescription=Custom\r\n[Service]\r\nUser=demo\r\nGroup=demo\r\n"
                + "ExecStart=/usr/bin/demo 8082\r\n[Install]\r\nWantedBy=multi-user.target\r\n";
        Files.writeString(temporaryDirectory.resolve("custom.service"), unit);
        RpmService configuration = new RpmService();
        configuration.setUnitFile(Path.of("custom.service").toFile());
        assertEquals(unit.replace("\r\n", "\n"),
                RpmSystemd.prepare(configuration, rpm(), temporaryDirectory).unit());
    }

    @Test
    void rejectsCustomUnitWhoseExecStartWasReset() throws Exception {
        Path unit = temporaryDirectory.resolve("reset.service");
        RpmService configuration = new RpmService();
        configuration.setUnitFile(unit.toFile());
        for (String commands : List.of("ExecStart=/usr/bin/demo\nExecStart=\n",
                "ExecStart=/usr/bin/demo\nExecStart=   \n", "ExecStart=\n")) {
            Files.writeString(unit, "[Service]\nUser=demo\nGroup=demo\n" + commands);
            assertThrows(MojoExecutionException.class,
                    () -> RpmSystemd.prepare(configuration, rpm(), temporaryDirectory));
        }
    }

    @Test
    void acceptsCustomUnitWithOneCommandAfterReset() throws Exception {
        Path unit = temporaryDirectory.resolve("replace.service");
        String contents = "[Service]\nUser=demo\nGroup=demo\nExecStart=/usr/bin/old-demo\n"
                + "ExecStart=\nExecStart=/usr/bin/demo 8081\n";
        Files.writeString(unit, contents);
        RpmService configuration = new RpmService();
        configuration.setUnitFile(unit.toFile());
        assertEquals(contents, RpmSystemd.prepare(configuration, rpm(), temporaryDirectory).unit());
    }

    @Test
    void rejectsMultipleEffectiveExecStartCommands() throws Exception {
        Path unit = temporaryDirectory.resolve("multiple.service");
        Files.writeString(unit, "[Service]\nUser=demo\nGroup=demo\nExecStart=\n"
                + "ExecStart=/usr/bin/demo\nExecStart=/usr/bin/another-demo\n");
        RpmService configuration = new RpmService();
        configuration.setUnitFile(unit.toFile());
        assertThrows(MojoExecutionException.class,
                () -> RpmSystemd.prepare(configuration, rpm(), temporaryDirectory));
    }

    @Test
    void rejectsMissingOrMismatchedAccountAndInvalidCustomUnits() throws Exception {
        RpmService configuration = new RpmService();
        Path unit = temporaryDirectory.resolve("custom.service");
        configuration.setUnitFile(unit.toFile());
        assertThrows(MojoExecutionException.class,
                () -> RpmSystemd.prepare(configuration, rpm(), temporaryDirectory));
        for (String directives : List.of("User=root\nGroup=demo\n", "User=demo\n",
                "User=demo\nGroup=demo\nUser=root\n", "User=demo\nGroup=demo\nDynamicUser=yes\n")) {
            Files.writeString(unit, "[Service]\n" + directives + "ExecStart=/usr/bin/demo\n");
            assertThrows(MojoExecutionException.class,
                    () -> RpmSystemd.prepare(configuration, rpm(), temporaryDirectory));
        }
    }

    @Test
    void addsAccountCreationAndAltServiceMacrosOnlyForServicePackages() throws Exception {
        var service = RpmSystemd.prepare(new RpmService(), rpm(), temporaryDirectory);
        String spec = RpmSpec.generate(rpm(), "Demo", "Proprietary", "Development/Other", Instant.EPOCH,
                RpmContent.defaults(rpm()), service);
        assertTrue(spec.contains("Requires(pre): /usr/sbin/useradd, /usr/sbin/groupadd, /usr/bin/getent\n"));
        assertTrue(spec.contains("getent group 'demo' >/dev/null || /usr/sbin/groupadd -r 'demo'"));
        assertTrue(spec.contains("getent passwd 'demo' >/dev/null || /usr/sbin/useradd -r -g 'demo' -d / -s /dev/null -M 'demo'"));
        assertTrue(spec.contains("%post\n%post_service demo\n"));
        assertTrue(spec.contains("%preun\n%preun_service demo\n"));
        assertFalse(spec.contains("userdel"));
        assertFalse(RpmSpec.generate(rpm(), "Demo", "Proprietary", "Development/Other", Instant.EPOCH)
                .contains("%post_service"));
    }

    @Test
    void unitAndServiceOwnedDirectoryAreStagedAndVerified() throws Exception {
        var service = RpmSystemd.prepare(new RpmService(), rpm(), temporaryDirectory);
        Path jar = Files.writeString(temporaryDirectory.resolve("demo.jar"), "JAR");
        Path unit = Files.writeString(temporaryDirectory.resolve("demo.service"), service.unit());
        RpmMapping unitMapping = new RpmMapping();
        unitMapping.setSource(unit.toFile());
        unitMapping.setDestination(service.destination());
        RpmMapping logs = new RpmMapping();
        logs.setDestination("/var/log/demo");
        logs.setMode("0750");
        logs.setOwner("demo");
        logs.setGroup("demo");
        RpmContent content = RpmContent.prepare(rpm(), jar, temporaryDirectory, List.of(unitMapping, logs));
        Path sources = Files.createDirectory(temporaryDirectory.resolve("SOURCES"));
        content.stage(sources);
        assertEquals(service.unit(), Files.readString(sources.resolve("rpm-source-1")));
        String metadata = "/usr/share/demo|16877|root|root|0\n/usr/share/demo/demo.jar|33188|root|root|0\n"
                + "/lib/systemd/system/demo.service|33188|root|root|0\n/var/log/demo|16872|demo|demo|0\n";
        assertDoesNotThrow(() -> content.verify(metadata));
        assertThrows(MojoExecutionException.class, () -> content.verify(metadata.replace("16872|demo", "16872|root")));
        assertThrows(MojoExecutionException.class,
                () -> RpmContent.prepare(rpm(), jar, temporaryDirectory, List.of(unitMapping, unitMapping)));
    }

    @Test
    void prepareGoalIncludesUnitLauncherAndOwnedDirectoryInManifest() throws Exception {
        Path build = Files.createDirectory(temporaryDirectory.resolve("target"));
        Files.writeString(build.resolve("demo-1.0.0.jar"), "JAR");
        RpmService configuration = new RpmService();
        configuration.setArguments(List.of("8081"));
        RpmMapping logs = new RpmMapping();
        logs.setDestination("/var/log/demo");
        logs.setOwner("demo");
        logs.setGroup("demo");
        logs.setMode("0750");
        PrepareRpmMojo mojo = new PrepareRpmMojo();
        Map<String, Object> parameters = Map.ofEntries(
                Map.entry("buildDirectory", build.toFile()), Map.entry("baseDirectory", temporaryDirectory.toFile()),
                Map.entry("finalName", "demo-1.0.0"), Map.entry("projectVersion", "1.0.0"),
                Map.entry("packaging", "jar"), Map.entry("rpmName", "demo"), Map.entry("releaseNumber", 1),
                Map.entry("jreRequirement", "java-21-openjdk-headless"), Map.entry("javaExecutable", "auto"),
                Map.entry("summary", "Demo"), Map.entry("license", "Proprietary"),
                Map.entry("group", "Development/Other"), Map.entry("service", configuration),
                Map.entry("mappings", List.of(logs)), Map.entry("fixedBuildTime", "2026-10-01T12:00:00Z"));
        for (var parameter : parameters.entrySet()) {
            var field = PrepareRpmMojo.class.getDeclaredField(parameter.getKey());
            field.setAccessible(true);
            field.set(mojo, parameter.getValue());
        }
        mojo.execute();
        RpmWorkspace workspace = new RpmWorkspace(build);
        RpmContent content = RpmContent.load(workspace.contentManifest());
        content.validateSources(workspace.sourcesDirectory());
        assertTrue(content.entries().stream().anyMatch(entry -> entry.destination().equals("/lib/systemd/system/demo.service")
                && entry.mode().equals("0644") && entry.owner().equals("root")));
        assertTrue(content.entries().stream().anyMatch(entry -> entry.destination().equals("/usr/bin/demo")));
        assertTrue(Files.readString(workspace.sourcesDirectory().resolve("rpm-source-1"))
                .contains("rpm -ql 'java-21-openjdk-headless'"));
        assertTrue(Files.readString(workspace.spec(rpm())).contains("%post_service demo"));
        assertTrue(Files.readString(workspace.sourcesDirectory().resolve("rpm-source-2"))
                .contains("ExecStart=/usr/bin/demo \"8081\""));
    }
}
