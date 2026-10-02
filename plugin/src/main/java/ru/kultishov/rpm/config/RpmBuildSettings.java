package ru.kultishov.rpm.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

// Snapshot of tool settings passed from Maven goals to the build environment
public record RpmBuildSettings(String buildMode, String dockerExecutable, String containerImage,
                               long containerTimeoutSeconds, long repositoryTimeoutSeconds,
                               List<String> repositoryMirrors, String repositories, long timeoutSeconds) {
    public RpmBuildSettings {
        if (repositoryMirrors != null) {
            repositoryMirrors = Collections.unmodifiableList(new ArrayList<>(repositoryMirrors));
        }
    }
}
