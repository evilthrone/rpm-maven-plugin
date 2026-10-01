package ru.kultishov.rpm;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class RpmCommandTest {
    @TempDir
    Path temporaryDirectory;

    private List<String> command(String argument) {
        String java = Path.of(System.getProperty("java.home"), "bin",
                System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        return List.of(java, "-cp", System.getProperty("java.class.path"), Probe.class.getName(), argument);
    }

    @Test
    void preservesArgumentsAndWorkingPathsWithSpaces() throws Exception {
        Path directory = temporaryDirectory.resolve("directory with spaces");
        Files.createDirectories(directory);
        String output = RpmCommand.run(command("two words"), directory, directory.resolve("command.log"), 10);
        assertEquals("two words", output.trim());
    }

    @Test
    void reportsNonzeroExitAndCommandOutput() {
        MojoExecutionException failure = assertThrows(MojoExecutionException.class,
                () -> RpmCommand.run(command("fail"), temporaryDirectory, temporaryDirectory.resolve("fail.log"), 10));
        assertTrue(failure.getMessage().contains("code 7"));
        assertTrue(failure.getMessage().contains("diagnostic from child process"));
    }

    @Test
    void terminatesCommandOnTimeout() throws Exception {
        MojoExecutionException failure = assertThrows(MojoExecutionException.class,
                () -> RpmCommand.run(command("sleep"), temporaryDirectory, temporaryDirectory.resolve("timeout.log"), 1));
        assertTrue(failure.getMessage().contains("timed out after 1"));
        long pid = Long.parseLong(Files.readString(temporaryDirectory.resolve("timeout.log")).trim());
        var process = ProcessHandle.of(pid);
        if (process.isPresent()) {
            process.get().onExit().get(5, TimeUnit.SECONDS);
            assertFalse(process.get().isAlive());
        }
    }

    @Test
    void reportsMissingExecutable() {
        MojoExecutionException failure = assertThrows(MojoExecutionException.class,
                () -> RpmCommand.run(List.of(temporaryDirectory.resolve("missing-docker").toString()), temporaryDirectory,
                        temporaryDirectory.resolve("missing.log"), 10));
        assertTrue(failure.getMessage().contains("Cannot start"));
    }

    @Test
    void rejectsNonpositiveTimeout() {
        assertThrows(MojoExecutionException.class,
                () -> RpmCommand.run(command("echo"), temporaryDirectory, temporaryDirectory.resolve("log"), 0));
    }

    public static class Probe {
        public static void main(String[] arguments) throws Exception {
            if (arguments[0].equals("sleep")) {
                System.out.println(ProcessHandle.current().pid());
                System.out.flush();
                Thread.sleep(60_000);
            } else if (arguments[0].equals("fail")) {
                System.err.println("diagnostic from child process");
                System.exit(7);
            } else {
                System.out.println(arguments[0]);
            }
        }
    }
}
