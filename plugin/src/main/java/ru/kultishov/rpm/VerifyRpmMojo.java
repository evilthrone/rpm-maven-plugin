package ru.kultishov.rpm;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Mojo;

@Mojo(name = "verify")
public final class VerifyRpmMojo extends AbstractMojo {
    @Override
    public void execute() throws MojoExecutionException {
        // TO DO
    }
}
