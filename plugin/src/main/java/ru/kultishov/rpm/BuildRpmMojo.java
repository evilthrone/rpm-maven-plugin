package ru.kultishov.rpm;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import java.io.File;

@Mojo(name = "build")
public final class BuildRpmMojo extends AbstractMojo {
    @Parameter(defaultValue = "${project.build.directory}", readonly = true, required = true)
    private File buildDirectory;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Parameter(property = "rpm.rpmbuildExecutable", defaultValue = "rpmbuild")
    private String rpmbuildExecutable;

    @Parameter(property = "rpm.commandTimeoutSeconds", defaultValue = "120")
    private long timeoutSeconds;

    @Parameter(property = "rpm.skip", defaultValue = "false")
    private boolean skip;

    @Override
    public void execute() throws MojoExecutionException {
        // TO DO
    }
}
