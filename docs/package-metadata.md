# Package metadata and ALT p11 checks

The `prepare` goal accepts these settings in the plugin's `<configuration>`:

```xml
<group>Development/Other</group>
<jreRequirement>java-21-openjdk-headless</jreRequirement>
```

These are the defaults. The corresponding command-line properties are
`rpm.group` and `rpm.jreRequirement`. A version constraint is also supported,
for example `java-21-openjdk-headless >= 21.0.1` (escape `<` as `&lt;` in XML).
Only one package requirement is accepted; RPM macros and multiple lines are
rejected. The requirement is saved during preparation and checked against the
built RPM by `verify`.

The default dependency supplies Java 21 for the demo, which targets Java 21.
It does not select the executable behind `/usr/bin/java`. When several JDKs
are installed, use the executable from the required package explicitly.
Applications targeting a newer Java release must configure a suitable JRE
requirement. A generated launcher will be added in a later stage.

The package owns `/usr/share/<rpm-name>` with mode `0755`, owner `root`, and
group `root`. The JAR inside it has mode `0644`. Shared parent directories
such as `/usr/share` are not added to the package's file list.

## Local checks on Windows

```powershell
mvn clean package '-Drpm.skip=true'
```

This runs the Java tests, builds both JARs, and generates the spec. RPM build
and verification are skipped and must be checked on ALT.

## Checks on ALT p11

Build as an ordinary user:

```bash
mvn clean package
rpm -q java-21-openjdk-headless
rpm -qp --requires demo/target/rpm-work/RPMS/noarch/*.rpm
rpm -qplv demo/target/rpm-work/RPMS/noarch/*.rpm
```

Expect the configured JRE requirement and both `/usr/share/demo` (directory,
`0755`, `root:root`) and `/usr/share/demo/demo.jar` (`0644`, `root:root`).
Use a clean test VM with no previously installed `demo` package. Enter a root
shell with `su -` and install the built RPM using its absolute path:

```bash
rpm -ivh /home/vkult/rpm-maven-plugin/demo/target/rpm-work/RPMS/noarch/*.rpm
exit
```

If the Java dependency is missing, install `java-21-openjdk-headless` through
ALT's package manager as root, then retry installation. Do not use `--nodeps`.
Find and run the Java executable belonging to the required package as the
ordinary user:

```bash
java_binary=$(rpm -ql java-21-openjdk-headless | grep -m1 '^/usr/lib/jvm/.*/bin/java$')
"$java_binary" -version
"$java_binary" -jar /usr/share/demo/demo.jar 8081
```

In another terminal:

```bash
curl -i http://localhost:8081/health
```

Expect HTTP 200 and body `OK`. Stop the server with Ctrl+C, enter a root shell,
and remove the package:

```bash
su -
rpm -e demo
exit
rpm -q demo
test ! -e /usr/share/demo && echo 'Application directory removed'
```

Expect the package to be absent and the application directory to be removed.
If another program has placed files in that directory, RPM may retain it;
do not delete unrelated files to force this check to pass.

These ALT checks are manual acceptance steps, not a claim that they have
already passed for this revision.
