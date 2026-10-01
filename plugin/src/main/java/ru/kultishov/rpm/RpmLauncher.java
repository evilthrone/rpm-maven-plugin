package ru.kultishov.rpm;

import org.apache.maven.plugin.MojoExecutionException;

final class RpmLauncher {
    private RpmLauncher() {
    }

    static String generate(String jarPath, String javaExecutable) throws MojoExecutionException {
        validate("JAR path", jarPath);
        validate("Java executable", javaExecutable);
        return "#!/bin/sh\n"
                + "set -eu\n"
                + "# JAVA_OPTS is split on whitespace; disable pathname expansion.\n"
                + "set -f\n"
                + "if [ -n \"${JAVA_HOME:-}\" ]; then\n"
                + "    java=\"$JAVA_HOME/bin/java\"\n"
                + "else\n"
                + "    java=" + quote(javaExecutable) + "\n"
                + "fi\n"
                + "# Do not eval JAVA_OPTS: its contents must not execute shell commands.\n"
                + "exec \"$java\" ${JAVA_OPTS:-} -jar " + quote(jarPath) + " \"$@\"\n";
    }

    private static void validate(String label, String value) throws MojoExecutionException {
        if (value == null || value.isBlank() || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0 || value.indexOf('\0') >= 0) {
            throw new MojoExecutionException(label + " must be a non-empty single line");
        }
    }

    private static String quote(String value) {
        return "'" + value.replace("'", "'\"'\"'") + "'";
    }
}
