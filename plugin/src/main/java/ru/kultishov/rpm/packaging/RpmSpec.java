package ru.kultishov.rpm.packaging;

import ru.kultishov.rpm.runtime.RpmSystemd;

import org.apache.maven.plugin.MojoExecutionException;

import java.time.Instant;

public final class RpmSpec {
    private RpmSpec() {
    }

    public static String generate(RpmPackage rpmPackage, String summary, String license, String group, Instant buildTime)
            throws MojoExecutionException {
        return generate(rpmPackage, summary, license, group, buildTime, RpmContent.defaults(rpmPackage));
    }

    public static String generate(RpmPackage rpmPackage, String summary, String license, String group, Instant buildTime,
                           RpmContent content) throws MojoExecutionException {
        return generate(rpmPackage, summary, license, group, buildTime, content, null);
    }

    public static String generate(RpmPackage rpmPackage, String summary, String license, String group, Instant buildTime,
                           RpmContent content, RpmSystemd.Service service) throws MojoExecutionException {
        validateLine("Summary", summary);
        validateLine("License", license);
        validateLine("Group", group);
        StringBuilder spec = new StringBuilder("Name: " + rpmPackage.name() + "\n"
                + "Version: " + rpmPackage.version() + "\n"
                + "Release: " + rpmPackage.release() + "\n"
                + "Summary: " + summary + "\n"
                + "License: " + license + "\n"
                + "Group: " + group + "\n"
                + "BuildArch: noarch\n"
                + "Requires: " + rpmPackage.jreRequirement() + "\n");
        if (service != null) {
            spec.append(service.requirements());
        }
        for (RpmContent.Entry entry : content.entries()) {
            if (!entry.directory()) {
                spec.append("Source").append(entry.sourceIndex()).append(": ")
                        .append(entry.sourceName()).append('\n');
            }
        }
        spec.append("\n"
                + "%description\n"
                + summary + "\n"
                + "\n"
                + "%install\n");
        for (RpmContent.Entry entry : content.entries()) {
            if (entry.directory()) {
                // Keep staging directories writable; %attr applies the requested permissions to the RPM.
                spec.append("install -dm 0755 \"%{buildroot}")
                        .append(entry.destination()).append("\"\n");
            } else {
                spec.append("install -Dpm ").append(entry.mode()).append(" \"%{SOURCE")
                        .append(entry.sourceIndex()).append("}\" \"%{buildroot}")
                        .append(entry.destination()).append("\"\n");
            }
        }
        if (service != null) {
            spec.append(service.scriptlets());
        }
        spec.append("\n%files\n");
        for (RpmContent.Entry entry : content.entries()) {
            if (entry.directory()) {
                spec.append("%dir ");
            } else if (entry.config()) {
                spec.append("%config(noreplace) ");
            }
            spec.append("%attr(").append(entry.mode()).append(',').append(entry.owner()).append(',')
                    .append(entry.group()).append(") ");
            if (entry.destination().indexOf(' ') >= 0) {
                spec.append('"').append(entry.destination()).append('"');
            } else {
                spec.append(entry.destination());
            }
            spec.append('\n');
        }
        spec.append("\n%changelog\n"
                + "* " + RpmPackage.changelogDate(buildTime) + " Maven RPM Plugin "
                + rpmPackage.version() + "-" + rpmPackage.release() + "\n"
                + "- Initial package.\n");
        return spec.toString();
    }

    private static void validateLine(String field, String value) throws MojoExecutionException {
        if (value == null || value.isBlank() || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0
                || value.indexOf('%') >= 0 || value.indexOf('\\') >= 0) {
            throw new MojoExecutionException(field + " must be a non-empty single line without RPM macros");
        }
    }
}
