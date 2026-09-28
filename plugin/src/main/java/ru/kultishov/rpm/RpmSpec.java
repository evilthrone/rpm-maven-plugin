package ru.kultishov.rpm;

import org.apache.maven.plugin.MojoExecutionException;

import java.time.Instant;

final class RpmSpec {
    private RpmSpec() {
    }

    static String generate(RpmPackage rpmPackage, String summary, String license, Instant buildTime)
            throws MojoExecutionException {
        validateLine("Summary", summary);
        validateLine("License", license);
        return "Name: " + rpmPackage.name() + "\n"
                + "Version: " + rpmPackage.version() + "\n"
                + "Release: " + rpmPackage.release() + "\n"
                + "Summary: " + summary + "\n"
                + "License: " + license + "\n"
                + "BuildArch: noarch\n"
                + "Requires: " + rpmPackage.jreRequirement() + "\n"
                + "Source0: " + rpmPackage.name() + ".jar\n"
                + "\n"
                + "%description\n"
                + summary + "\n"
                + "\n"
                + "%install\n"
                + "install -Dpm 0644 \"%{SOURCE0}\" \"%{buildroot}" + rpmPackage.jarPath() + "\"\n"
                + "\n"
                + "%files\n"
                + "%attr(0644,root,root) " + rpmPackage.jarPath() + "\n"
                + "\n"
                + "%changelog\n"
                + "* " + RpmPackage.changelogDate(buildTime) + " Maven RPM Plugin "
                + rpmPackage.version() + "-" + rpmPackage.release() + "\n"
                + "- Initial package.\n";
    }

    private static void validateLine(String field, String value) throws MojoExecutionException {
        if (value == null || value.isBlank() || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0
                || value.indexOf('%') >= 0 || value.indexOf('\\') >= 0) {
            throw new MojoExecutionException(field + " must be a non-empty single line without RPM macros");
        }
    }
}
