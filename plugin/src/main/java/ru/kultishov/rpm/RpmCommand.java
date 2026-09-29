package ru.kultishov.rpm;

import org.apache.maven.plugin.MojoExecutionException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

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
                    + "; check that the RPM tool is installed and available on PATH", e);
        }
        try {
            if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                process.waitFor();
                throw new MojoExecutionException("RPM command timed out after " + timeoutSeconds
                        + " seconds; log: " + logFile);
            }
        } catch (InterruptedException e) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
            throw new MojoExecutionException("RPM command was interrupted; log: " + logFile, e);
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
}
