# RPM Maven Plugin

Packages a Java JAR as a `noarch` RPM for ALT Linux p11 during Maven's `package` phase.

## Requirements

- JDK 21+ and Maven 3.8.8+.
- On ALT: local `rpmbuild` and `rpm` (`su -c 'apt-get install rpm-build'`). Build as a regular user.
- On other systems: Docker with a running Linux engine. On Windows, start Docker Desktop.
- Internet access for missing Maven dependencies and the initial container setup. The plugin downloads `alt:p11` automatically.

Maven builds the JAR on the host; RPM build and verification use ALT.

## Build the demo

```sh
git clone https://github.com/evilthrone/rpm-maven-plugin.git
cd rpm-maven-plugin
mvn clean package
```

RPM: `demo/target/rpm-work/RPMS/noarch/*.rpm`. Build logs and the generated spec: `demo/target/rpm-work/`.

## Use in your project

Install the plugin and its parent POM from this repository first:

```sh
mvn -pl plugin -am install
```

In your application's `pom.xml`, add:

```xml
<build>
    <plugins>
        <plugin>
            <groupId>ru.kultishov</groupId>
            <artifactId>rpm-maven-plugin</artifactId>
            <version>1.0.0-SNAPSHOT</version>
            <executions>
                <execution>
                    <phase>package</phase>
                    <goals>
                        <goal>prepare</goal>
                        <goal>build</goal>
                        <goal>verify</goal>
                    </goals>
                </execution>
            </executions>
        </plugin>
    </plugins>
</build>
```

Use `jar` packaging. To run the installed application, its JAR must have a `Main-Class` manifest entry and include any required runtime dependencies.

Run `mvn clean package` in your application. Output: `target/rpm-work/RPMS/noarch/`. The RPM is also attached to the Maven project with classifier `rpm`.

Goal order: `prepare` (spec and files), `build` (RPM), `verify` (metadata and contents).

## Configuration

No `<configuration>` fields are required for basic packaging. Add optional settings inside the plugin declaration above:

| XML parameter | Default | Purpose |
| --- | --- | --- |
| `rpmName` | Project `artifactId` | Package and launcher name |
| `releaseNumber` | `1` | RPM release `alt1`, `alt2`, etc. |
| `summary` | `Java application packaged by Maven` | Package summary |
| `license` | `Proprietary` | Package license |
| `group` | `Development/Other` | RPM category, unrelated to Linux user groups |
| `jreRequirement` | `java-21-openjdk-headless` | Required ALT Java package, written as RPM `Requires` |
| `javaExecutable` | `auto` | `auto`, a command such as `java`, or an executable path |
| `buildMode` | `auto` | `auto`, `local` or `container` |
| `containerImage` | `alt:p11` | Docker base image |
| `service` | None | Optional systemd service |
| `mappings` | None | Additional files and directories |

The JAR is installed as `/usr/share/<rpmName>/<rpmName>.jar`; its launcher is `/usr/bin/<rpmName>`.

Supported Maven versions: numeric versions such as `1.0.0`, optionally ending in `-RC1` or `-SNAPSHOT`. These become `1.0.0`, `1.0.0~rc1` and `1.0.0~snapshot.<UTC timestamp>`.

Increase the release with `mvn clean package '-Drpm.release=2'`. Optional `'-Drpm.buildTime=2026-10-01T12:00:00Z'` fixes the snapshot timestamp for testing.

### Java runtime

- `jreRequirement` specifies the Java package needed on the target system. `rpm -Uvh` rejects installation if the dependency is missing; install it first.
- `javaExecutable=auto` finds Java inside that installed package. Use a concrete package that owns a Java executable.
- `javaExecutable=java` searches `PATH`; an absolute path such as `/opt/jdk-21/bin/java` selects that executable directly.
- If `JAVA_HOME` is set when launching the application, `$JAVA_HOME/bin/java` overrides `javaExecutable`.

Example for an application compatible with Java 17:

```xml
<configuration>
    <jreRequirement>java-17-openjdk-headless</jreRequirement>
    <javaExecutable>auto</javaExecutable>
</configuration>
```

These settings do not change compilation or remove the RPM dependency. The Maven plugin itself needs JDK 21+; the demo is compiled for Java 21.

### Service and additional files

Example for an application named `myapp`. Create `src/main/rpm/myapp.properties` before building:

```xml
<configuration>
    <service>
        <user>myapp</user>
        <group>myapp</group>
        <arguments>
            <argument>8081</argument>
        </arguments>
        <environment>
            <JAVA_OPTS>-Xmx128m</JAVA_OPTS>
        </environment>
    </service>
    <mappings>
        <mapping>
            <destination>/etc/myapp</destination>
        </mapping>
        <mapping>
            <source>${project.basedir}/src/main/rpm/myapp.properties</source>
            <destination>/etc/myapp/myapp.properties</destination>
            <config>true</config>
        </mapping>
        <mapping>
            <destination>/var/log/myapp</destination>
            <mode>0750</mode>
            <owner>myapp</owner>
            <group>myapp</group>
        </mapping>
    </mappings>
</configuration>
```

- **Service:** all fields are optional. `name` and `user` default to the RPM name; `group` to the user; `description` to `Java application`; `restart` to `on-failure`. Arguments and environment default to empty, with no JVM options.
- **Existing unit:** set `<unitFile>${project.basedir}/src/main/rpm/myapp.service</unitFile>` inside `service`. Its `User` and `Group` must match the service configuration, and it must contain an `ExecStart`.
- **Mapping:** `destination` is required and must be an absolute Linux path. Optional `source` accepts a file or directory tree; omitting it creates an empty directory.
- **Attributes:** `mode` defaults to `0644` for files and `0755` for directories. `fileMode` controls files inside a directory tree (default `0644`). `owner` and `group` default to `root`.
- **Configuration files:** `config=true` adds `%config(noreplace)`: upgrades preserve user edits and save changed packaged content as `.rpmnew`. Default: `false`. The application must read the file itself.

Installation creates the service account. Other mapping owners/groups must already exist. Start the service explicitly after installation.

The default repository is `http://ftp.altlinux.org/pub/distributions/ALTLinux`. To configure ordered fallback repositories and timeouts, see [container settings](docs/container-build.md#repositories).

## Install and run on ALT

From the repository directory on ALT (for a downloaded RPM, substitute its path):

```sh
rpm -qplv demo/target/rpm-work/RPMS/noarch/*.rpm
su -c 'apt-get install java-21-openjdk-headless'
su -c 'rpm -Uvh demo/target/rpm-work/RPMS/noarch/*.rpm'
su -c 'systemctl start demo'
curl --retry 10 --retry-connrefused --retry-delay 1 http://localhost:8081/health
```

Expected response: `OK`. Check status with `systemctl status demo --no-pager`.

Manual run as a regular user: `demo` (port 8080). To choose another free port: `demo 8082`. Stop with `Ctrl+C`.

Optional JVM settings: `JAVA_OPTS='-Xmx128m' demo 8082`.

Remove: `su -c 'rpm -e demo'`. The service account remains; edited configuration files may be saved as `.rpmsave`.

## Tests

```sh
mvn clean test
mvn checkstyle:check
```

JUnit runs on the host. GitHub Actions runs separate Build, Test and Lint jobs; a successful Build uploads the `demo-rpm` artifact. Service startup and package upgrades are checked on ALT.
