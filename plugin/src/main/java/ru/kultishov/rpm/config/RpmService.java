package ru.kultishov.rpm.config;

import java.io.File;
import java.util.List;
import java.util.Map;

// Optional systemd service configuration in the consuming POM
public final class RpmService {
    private File unitFile;
    private String name;
    private String user;
    private String group;
    private String description = "Java application";
    private String restart = "on-failure";
    private List<String> arguments = List.of();
    private Map<String, String> environment = Map.of();

    public File getUnitFile() { return unitFile; }
    public void setUnitFile(File unitFile) { this.unitFile = unitFile; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getUser() { return user; }
    public void setUser(String user) { this.user = user; }
    public String getGroup() { return group; }
    public void setGroup(String group) { this.group = group; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getRestart() { return restart; }
    public void setRestart(String restart) { this.restart = restart; }
    public List<String> getArguments() { return arguments; }
    public void setArguments(List<String> arguments) { this.arguments = arguments; }
    public Map<String, String> getEnvironment() { return environment; }
    public void setEnvironment(Map<String, String> environment) { this.environment = environment; }
}
