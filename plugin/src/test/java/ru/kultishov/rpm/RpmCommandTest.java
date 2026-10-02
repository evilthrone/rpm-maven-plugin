package ru.kultishov.rpm;

import org.apache.maven.plugin.MojoExecutionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class RpmCommandTest {
    @TempDir
    Path temporaryDirectory;

    private static List<String> command(String argument) {
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
            assertFalse(process.get().isAlive());
        }
    }

    private List<Long> processIds(Path log) throws Exception {
        return Files.exists(log) ? Files.readAllLines(log).stream().filter(line -> line.matches("[0-9]+"))
                .map(Long::parseLong).toList() : List.of();
    }

    private void cleanup(Path log) throws Exception {
        for (long pid : processIds(log).reversed()) {
            var process = ProcessHandle.of(pid);
            if (process.isPresent() && process.get().isAlive()) {
                process.get().destroyForcibly();
                process.get().onExit().get(5, TimeUnit.SECONDS);
            }
        }
    }

    private void assertTreeStopped(Path log) throws Exception {
        List<Long> pids = processIds(log);
        assertEquals(3, pids.size(), "Parent, child and grandchild must all have started");
        for (long pid : pids) {
            assertFalse(ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false),
                    "Process still running when command returned: " + pid);
        }
    }

    @Test
    void terminatesEntireProcessTreeBeforeReportingTimeout() throws Exception {
        Path log = temporaryDirectory.resolve("tree-timeout.log");
        try {
            MojoExecutionException failure = assertThrows(MojoExecutionException.class,
                    () -> RpmCommand.run(command("tree-parent"), temporaryDirectory, log, 3));
            assertTrue(failure.getMessage().contains("timed out after 3"));
            assertTreeStopped(log);
        } finally {
            cleanup(log);
        }
    }

    @Test
    void interruptionTerminatesProcessTreeAndPreservesInterruptFlag() throws Exception {
        Path log = temporaryDirectory.resolve("tree-interrupt.log");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interruptFlag = new AtomicBoolean();
        Thread worker = new Thread(() -> {
            try {
                RpmCommand.run(command("tree-parent"), temporaryDirectory, log, 30);
            } catch (Throwable e) {
                failure.set(e);
                interruptFlag.set(Thread.currentThread().isInterrupted());
            }
        });
        worker.start();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (processIds(log).size() < 3 && worker.isAlive() && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
            assertEquals(3, processIds(log).size(), "Probe process tree did not start");
            worker.interrupt();
            worker.join(10_000);
            assertFalse(worker.isAlive(), "Interrupted command did not return");
            assertInstanceOf(MojoExecutionException.class, failure.get());
            assertTrue(failure.get().getMessage().contains("interrupted"));
            assertTrue(interruptFlag.get(), "Caller interrupt flag was lost");
            assertTreeStopped(log);
        } finally {
            worker.interrupt();
            worker.join(10_000);
            cleanup(log);
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
            if (arguments[0].equals("sleep") || arguments[0].startsWith("tree-")) {
                System.out.println(ProcessHandle.current().pid());
                System.out.flush();
                if (!arguments[0].equals("sleep") && !arguments[0].equals("tree-leaf")) {
                    String childMode = arguments[0].equals("tree-parent") ? "tree-child" : "tree-leaf";
                    new ProcessBuilder(command(childMode)).inheritIO().start().waitFor();
                }
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
