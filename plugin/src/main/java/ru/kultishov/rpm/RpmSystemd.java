package ru.kultishov.rpm;

import org.apache.maven.plugin.MojoExecutionException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

final class RpmSystemd {
    private RpmSystemd() {
    }

    record Service(String name, String user, String group, String unit) {
        String destination() { return "/lib/systemd/system/" + name + ".service"; }

        String requirements() {
            return "Requires(pre): /usr/sbin/useradd, /usr/sbin/groupadd, /usr/bin/getent\n"
                    + "Requires(post): /usr/sbin/post_service\n"
                    + "Requires(preun): /usr/sbin/preun_service\n";
        }

        String scriptlets() {
            return "\n%pre\nset -e\n"
                    + "getent group '" + group + "' >/dev/null || /usr/sbin/groupadd -r '" + group + "'\n"
                    + "getent passwd '" + user + "' >/dev/null || /usr/sbin/useradd -r -g '" + group
                    + "' -d / -s /dev/null -M '" + user + "'\n"
                    + "\n%post\n%post_service " + name + "\n"
                    + "\n%preun\n%preun_service " + name + "\n";
        }
    }

    static Service prepare(RpmService configuration, RpmPackage rpm, Path baseDirectory)
            throws MojoExecutionException, IOException {
        String name = configuration.getName() == null ? rpm.name() : configuration.getName();
        String user = configuration.getUser() == null ? rpm.name() : configuration.getUser();
        String group = configuration.getGroup() == null ? user : configuration.getGroup();
        identity("service name", name);
        identity("service user", user);
        identity("service group", group);
        if (user.equals("root") || group.equals("root")) {
            throw new MojoExecutionException("Service user and group must not be root");
        }
        String unit;
        if (configuration.getUnitFile() != null) {
            Path source = baseDirectory.resolve(configuration.getUnitFile().toPath()).toAbsolutePath().normalize();
            RpmContent.rejectSymlink(source);
            if (!Files.isRegularFile(source)) {
                throw new MojoExecutionException("Service unit must be an existing regular file: " + source);
            }
            unit = Files.readString(source, StandardCharsets.UTF_8).replace("\r\n", "\n");
            validateCustomUnit(unit, user, group);
        } else {
            unit = generate(configuration, rpm, user, group);
        }
        return new Service(name, user, group, unit);
    }

    private static String generate(RpmService configuration, RpmPackage rpm, String user, String group)
            throws MojoExecutionException {
        if (configuration.getArguments() == null || configuration.getEnvironment() == null) {
            throw new MojoExecutionException("Service arguments and environment must not be null");
        }
        if (!List.of("no", "on-success", "on-failure", "on-abnormal", "on-watchdog", "on-abort", "always")
                .contains(configuration.getRestart())) {
            throw new MojoExecutionException("Invalid systemd restart policy");
        }
        StringBuilder unit = new StringBuilder("[Unit]\nDescription=" + escaped(configuration.getDescription())
                + "\nAfter=network.target\n\n[Service]\nType=simple\nUser=" + user + "\nGroup=" + group
                + "\nExecStart=/usr/bin/" + rpm.name());
        for (String argument : configuration.getArguments()) {
            unit.append(" \"").append(escaped(argument).replace("$", "$$")).append('"');
        }
        unit.append("\nRestart=").append(configuration.getRestart()).append("\nRestartSec=3\n");
        for (var entry : configuration.getEnvironment().entrySet().stream().sorted(java.util.Map.Entry.comparingByKey()).toList()) {
            if (!entry.getKey().matches("[a-zA-Z_][a-zA-Z0-9_]*")) {
                throw new MojoExecutionException("Invalid service environment variable: " + entry.getKey());
            }
            unit.append("Environment=\"").append(entry.getKey()).append('=')
                    .append(escaped(entry.getValue())).append("\"\n");
        }
        return unit.append("\n[Install]\nWantedBy=multi-user.target\n").toString();
    }

    private static String escaped(String value) throws MojoExecutionException {
        if (value == null || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\0') >= 0) {
            throw new MojoExecutionException("Service values must be single lines without NUL");
        }
        return value.replace("\\", "\\\\").replace("\"", "\\\"").replace("%", "%%");
    }

    private static void identity(String label, String value) throws MojoExecutionException {
        if (value == null || !value.matches("[a-z_][a-z0-9_-]*")) {
            throw new MojoExecutionException("Invalid " + label + ": " + value);
        }
    }

    private static void validateCustomUnit(String unit, String user, String group) throws MojoExecutionException {
        if (unit.indexOf('\0') >= 0 || unit.indexOf('\r') >= 0 || unit.contains("\\\n")) {
            throw new MojoExecutionException("Custom unit contains unsupported control characters or continuations");
        }
        String section = "";
        int users = 0;
        int groups = 0;
        int commands = 0;
        for (String line : unit.lines().toList()) {
            String trimmed = line.trim();
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                section = trimmed;
            } else if (section.equals("[Service]") && trimmed.contains("=") && !trimmed.startsWith("#")
                    && !trimmed.startsWith(";")) {
                String[] pair = trimmed.split("=", 2);
                switch (pair[0].trim()) {
                    case "User" -> { users++; if (!pair[1].trim().equals(user)) throw new MojoExecutionException("Unit User must match service user"); }
                    case "Group" -> { groups++; if (!pair[1].trim().equals(group)) throw new MojoExecutionException("Unit Group must match service group"); }
                    case "ExecStart" -> { if (!pair[1].isBlank()) commands++; }
                    case "DynamicUser" -> throw new MojoExecutionException("DynamicUser conflicts with the RPM managed service account");
                    default -> { }
                }
            }
        }
        if (users != 1 || groups != 1 || commands != 1) {
            throw new MojoExecutionException("Custom unit requires one User, Group and non-empty ExecStart in [Service]");
        }
    }
}
