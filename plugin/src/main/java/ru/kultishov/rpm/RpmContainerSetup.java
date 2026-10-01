package ru.kultishov.rpm;

import org.apache.maven.plugin.MojoExecutionException;

import java.net.URI;
import java.util.List;

final class RpmContainerSetup {
    static final String OFFICIAL_REPOSITORY = "http://ftp.altlinux.org/pub/distributions/ALTLinux";

    private RpmContainerSetup() {
    }

    static List<String> repositories(List<String> configured, String override) throws MojoExecutionException {
        List<String> values = override != null ? List.of(override.split(",", -1))
                : configured == null ? List.of(OFFICIAL_REPOSITORY) : configured;
        if (values.isEmpty()) {
            throw new MojoExecutionException("At least one ALT repository is required");
        }
        for (String value : values) {
            try {
                URI uri = URI.create(value);
                if (!value.matches("https?://[A-Za-z0-9./:_-]+") || uri.getHost() == null
                        || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
                    throw new IllegalArgumentException();
                }
            } catch (IllegalArgumentException e) {
                throw new MojoExecutionException("Invalid ALT repository URL: " + value, e);
            }
        }
        return values.stream().map(value -> value.replaceAll("/+$", "")).toList();
    }

    static String script(List<String> repositories, long timeout) throws MojoExecutionException {
        if (timeout < 1 || timeout > 3600) {
            throw new MojoExecutionException("ALT repository timeout must be between 1 and 3600 seconds");
        }
        String urls = String.join(" ", repositories.stream().map(url -> "'" + url + "'").toList());
        return """
                #!/bin/sh
                set -eu
                rm -f /etc/apt/sources.list.d/*.list
                arch=$(uname -m)
                for repository in %s; do
                    echo "Trying ALT repository: $repository"
                    printf 'rpm [p11] %%s p11/branch/%%s classic\\nrpm [p11] %%s p11/branch/noarch classic\\n' \
                        "$repository" "$arch" "$repository" > /etc/apt/sources.list
                    find /var/lib/apt/lists -type f -delete
                    if apt-get -o Acquire::http::Timeout=%d -o Acquire::ftp::Timeout=%d update > /tmp/apt-update.log 2>&1; then
                        cat /tmp/apt-update.log
                        if ! grep -Eq '^(Err|E:|W:.*(Failed|Some index))' /tmp/apt-update.log && \
                            apt-get -o Acquire::http::Timeout=%d -y install rpm-build; then
                            command -v rpmbuild
                            rpm --eval '%%post_service demo' | grep /usr/sbin/post_service
                            exit 0
                        fi
                    else
                        cat /tmp/apt-update.log
                    fi
                    echo "ALT repository failed: $repository" >&2
                done
                echo 'All configured ALT repositories failed' >&2
                exit 1
                """.formatted(urls, timeout, timeout, timeout);
    }
}
