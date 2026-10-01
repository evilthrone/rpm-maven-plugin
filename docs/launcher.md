# Application launcher

The `prepare` goal generates a POSIX shell script installed as
`/usr/bin/<rpm-name>`, owned by `root:root`, with mode `0755`.
For the demo, the command is `demo` and the installed JAR is
`/usr/share/demo/demo.jar`.

The launcher uses `exec` to replace itself with Java. JVM options precede
`-jar`; application arguments follow the JAR path and keep their original
argument boundaries, including spaces and empty arguments.

## Java selection

If `JAVA_HOME` is nonempty, the launcher uses `$JAVA_HOME/bin/java`.
Otherwise it uses the executable configured at package build time:

```xml
<configuration>
    <javaExecutable>java</javaExecutable>
</configuration>
```

`java` is the default and is resolved through the runtime `PATH`. An absolute
Linux path is also supported. The build-time property is `rpm.javaExecutable`.
An invalid `JAVA_HOME` or unavailable executable causes launch to fail; the
launcher does not silently switch to another Java installation.

The RPM's JRE dependency and Java selection are separate settings. Installing
`java-21-openjdk-headless` does not guarantee that `java` in `PATH` selects Java
21. Set `JAVA_HOME` or configure the executable when multiple JDKs are installed.

## JVM options and application arguments

```bash
JAVA_OPTS='-Xmx128m -Ddemo.marker=launcher-test' demo 8081
```

`JAVA_OPTS` is a whitespace-separated list of JVM options. It is not evaluated
as shell code. Shell substitutions, wildcard expansion, and shell quoting
inside its value are not performed. An option whose value contains spaces
cannot be represented through this variable in this version.

Application arguments use ordinary shell quoting:

```bash
demo 8081
```

This stage does not add systemd integration or change how DemoServer reads
configuration. `/etc/demo/demo.properties` remains a packaging example.
Mappings cannot overwrite `/usr/bin/<rpm-name>`; conflicting destinations
fail during preparation.

## Verification on ALT p11

As the ordinary user, build and inspect the RPM:

```bash
mvn clean package
cp demo/target/rpm-work/RPMS/noarch/*.rpm /tmp/demo-launcher.rpm
rpm -qplv /tmp/demo-launcher.rpm
```

Expect `/usr/bin/demo` with executable mode `0755`, owned by `root:root`.
Enter a root shell to install or update the package:

```bash
su -
rpm -Uvh /tmp/demo-launcher.rpm
exit
```

Start as the ordinary user using Java 21 from the ALT package:

```bash
java_binary=$(rpm -ql java-21-openjdk-headless | grep -m1 '^/usr/lib/jvm/.*/bin/java$')
JAVA_HOME=$(dirname "$(dirname "$java_binary")") JAVA_OPTS='-Xmx128m -XshowSettings:vm' demo 8081
```

Expect VM settings showing a maximum heap of approximately 128 MB and
`Demo server listening on port 8081`. In a second terminal:

```bash
curl -i http://localhost:8081/health
```

Expect HTTP 200 and body `OK`. Stop the process with Ctrl+C and confirm the
port is no longer served by that process. Remove the package as root:

```bash
su -
rpm -e demo
exit
test ! -e /usr/bin/demo && echo 'Launcher removed'
```

Modified configuration left from the previous stage may still be preserved
by RPM; the launcher does not remove user configuration.
