package ru.kultishov.rpm.build;

import org.apache.maven.plugin.MojoExecutionException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

final class RpmCommand {
    private RpmCommand() {
    }

    static String run(List<String> command, Path directory, Path logFile, long timeoutSeconds)
            throws MojoExecutionException {
        if (timeoutSeconds < 1) {
            throw new MojoExecutionException("RPM command timeout must be positive");
        }
        if (Files.isSymbolicLink(logFile)) {
            throw new MojoExecutionException("RPM log path must not be a symbolic link: " + logFile);
        }
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .redirectOutput(logFile.toFile())
                    .start();
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot start " + command.getFirst()
                    + "; check that the executable is installed and available on PATH"
                    + "; for container builds, start the Docker engine", e);
        }
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                String cleanup = terminate(process);
                throw new MojoExecutionException("RPM command timed out after " + timeoutSeconds
                        + " seconds; log: " + logFile + cleanup);
            }
        } catch (InterruptedException e) {
            String cleanup = terminate(process);
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("RPM command was interrupted; log: " + logFile + cleanup, e);
        }
        String output;
        try {
            output = Files.readString(logFile, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new MojoExecutionException("Cannot read RPM command log: " + logFile, e);
        }
        if (process.exitValue() != 0) {
            String tail = output.length() > 4000 ? output.substring(output.length() - 4000) : output;
            throw new MojoExecutionException(command.getFirst() + " exited with code " + process.exitValue()
                    + "; log: " + logFile + "\n" + tail);
        }
        return output;
    }

    private static String terminate(Process process) {
        // Keep handles before terminating parents, which can orphan their children.
        List<ProcessHandle> handles = new ArrayList<>(process.descendants().toList().reversed());
        handles.add(process.toHandle());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        boolean interrupted = Thread.interrupted();
        try {
            for (ProcessHandle handle : handles) {
                handle.destroyForcibly();
                while (handle.isAlive()) {
                    long remaining = deadline - System.nanoTime();
                    if (remaining <= 0) {
                        break;
                    }
                    try {
                        if (handle.pid() == process.pid()) {
                            process.waitFor(remaining, TimeUnit.NANOSECONDS);
                        } else {
                            handle.onExit().get(remaining, TimeUnit.NANOSECONDS);
                        }
                    } catch (InterruptedException e) {
                        interrupted = true;
                    } catch (ExecutionException | TimeoutException e) {
                        break;
                    }
                }
            }
        } finally {
            try {
                process.getOutputStream().close();
                process.getInputStream().close();
                process.getErrorStream().close();
            } catch (IOException e) {
                // The command has already failed; keep reporting its timeout or interruption.
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
        String remaining = handles.stream().filter(ProcessHandle::isAlive)
                .map(handle -> Long.toString(handle.pid())).collect(Collectors.joining(", "));
        return remaining.isEmpty() ? "" : "; processes still running after cleanup: " + remaining;
    }
}
