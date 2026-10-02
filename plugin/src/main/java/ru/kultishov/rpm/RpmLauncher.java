package ru.kultishov.rpm;

import org.apache.maven.plugin.MojoExecutionException;

final class RpmLauncher {
    private RpmLauncher() {
    }

    static String generate(String jarPath, String javaExecutable, String jreRequirement) throws MojoExecutionException {
        validate("JAR path", jarPath);
        validate("Java executable", javaExecutable);
        String jrePackage = RpmPackage.normalizeRequirement(jreRequirement).split(" ")[0];
        return "#!/bin/sh\n"
                + "set -eu\n"
                + "# JAVA_OPTS is split on whitespace; disable pathname expansion.\n"
                + "set -f\n"
                + "if [ -n \"${JAVA_HOME:-}\" ]; then\n"
                + "    java=\"$JAVA_HOME/bin/java\"\n"
                + "elif [ " + quote(javaExecutable) + " = 'auto' ]; then\n"
                + "    java=\n"
                + "    java_files=$(rpm -ql " + quote(jrePackage) + ") || {\n"
                + "        printf '%s\\n' " + quote("Cannot query JRE package " + jrePackage + "; set JAVA_HOME or rpm.javaExecutable.") + " >&2\n"
                + "        exit 1\n"
                + "    }\n"
                + "    while IFS= read -r candidate; do\n"
                + "        case \"$candidate\" in\n"
                + "            /usr/bin/java|/bin/java) ;;\n"
                + "            */bin/java)\n"
                + "                if [ -x \"$candidate\" ]; then java=$candidate; break; fi ;;\n"
                + "        esac\n"
                + "    done <<RPM_JAVA_FILES\n"
                + "$java_files\n"
                + "RPM_JAVA_FILES\n"
                + "    if [ -z \"$java\" ]; then\n"
                + "        printf '%s\\n' " + quote("No JVM executable found in JRE package " + jrePackage + "; set JAVA_HOME or rpm.javaExecutable.") + " >&2\n"
                + "        exit 1\n"
                + "    fi\n"
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
