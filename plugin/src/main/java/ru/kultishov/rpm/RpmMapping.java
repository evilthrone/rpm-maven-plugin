package ru.kultishov.rpm;

import java.io.File;

// A file, directory tree, or empty directory configured in the consuming POM
public final class RpmMapping {
    private File source;
    private String destination;
    private String mode;
    private String fileMode = "0644";
    private String owner = "root";
    private String group = "root";
    private boolean config;

    public File getSource() { return source; }
    public void setSource(File source) { this.source = source; }
    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public String getFileMode() { return fileMode; }
    public void setFileMode(String fileMode) { this.fileMode = fileMode; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public String getGroup() { return group; }
    public void setGroup(String group) { this.group = group; }
    public boolean isConfig() { return config; }
    public void setConfig(boolean config) { this.config = config; }
}
