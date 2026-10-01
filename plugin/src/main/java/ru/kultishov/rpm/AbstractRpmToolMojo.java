package ru.kultishov.rpm;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugins.annotations.Parameter;

import java.util.List;

// Settings shared by RPM build and verification
public abstract class AbstractRpmToolMojo extends AbstractMojo {
    @Parameter(property = "rpm.buildMode", defaultValue = "auto")
    protected String buildMode = "auto";

    @Parameter(property = "rpm.dockerExecutable", defaultValue = "docker")
    protected String dockerExecutable = "docker";

    @Parameter(property = "rpm.containerImage", defaultValue = "alt:p11")
    protected String containerImage = "alt:p11";

    @Parameter(property = "rpm.containerTimeoutSeconds", defaultValue = "900")
    protected long containerTimeoutSeconds = 900;

    @Parameter(property = "rpm.repositoryTimeoutSeconds", defaultValue = "20")
    protected long repositoryTimeoutSeconds = 20;

    @Parameter
    protected List<String> repositoryMirrors;

    @Parameter(property = "rpm.repositories")
    protected String repositories;

    @Parameter(property = "rpm.commandTimeoutSeconds", defaultValue = "120")
    protected long timeoutSeconds = 120;
}
