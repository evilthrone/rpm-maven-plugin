# Configurable package contents

The project JAR and `/usr/share/<rpm-name>` are included automatically. Add
other content in the RPM plugin's `<configuration>`:

```xml
<mappings>
    <mapping>
        <source>${project.basedir}/src/main/rpm/app.properties</source>
        <destination>/etc/my-app/app.properties</destination>
        <mode>0640</mode>
        <owner>root</owner>
        <group>root</group>
        <config>true</config>
    </mapping>
    <mapping>
        <source>${project.basedir}/assets</source>
        <destination>/usr/share/my-app/assets</destination>
        <mode>0755</mode>
        <fileMode>0644</fileMode>
    </mapping>
    <mapping>
        <destination>/etc/my-app</destination>
    </mapping>
    <mapping>
        <destination>/var/log/my-app</destination>
        <mode>0750</mode>
    </mapping>
</mappings>
```

## Mapping rules

- A file source maps to the exact destination filename.
- A directory source maps its contents recursively, including nested empty
  directories. The source directory's own name is not appended to the destination.
- Omitting `source` declares a directory. It need not remain empty when other
  mappings place files inside it.
- Relative sources are resolved against the consuming project's base directory.
  Absolute sources are also accepted. Sources must exist and cannot contain
  symbolic links or special files.
- `mode` defaults to `0644` for files and `0755` for directories. For a directory
  tree, `mode` applies to every directory, and `fileMode` applies to every file
  (default `0644`). Use four octal digits; special permission bits are supported
  (for example `2750` for a directory with setgid).
- `owner` and `group` default to `root`. They are Linux account names, not the
  package category `rpm.group`. This stage does not create accounts; configured
  owners and groups must exist on the target system.
- `config=true` emits `%config(noreplace)` for a file, or for every file in a
  directory tree. It is invalid for a directory without a source.
- Destinations must be absolute Linux paths. Supported path components contain
  letters (including Unicode), digits, spaces, `.`, `_`, `+`, and `-`. Paths
  containing spaces are quoted in the spec. Root, trailing or repeated
  slashes, `.` and `..` components, backslashes, RPM macros, and shell
  metacharacters are rejected.
- Duplicate destinations, attempts to replace the default JAR or its directory,
  and a file used as a parent of another destination are rejected. A directory
  and separately mapped children are allowed.
- Parent directories are created as needed during installation into the build
  root. To make an application-specific parent belong to the RPM, declare it
  explicitly, as `/etc/my-app` above. Do not declare shared system directories
  such as `/etc` or `/usr/share`.

Files are copied into `target/rpm-work/SOURCES`; extra files use numbered source
names. The validated list is saved in `content.properties`, so later goals
use the prepared configuration. `build` checks staged sources. `verify` checks
each expected path, file type, mode, owner, group, and configuration flags using
RPM queries.

## Demo checks on ALT p11

Run the following from a fresh checkout of this branch, with no existing demo
installation or leftover `/etc/demo/demo.properties` configuration.

Build as the ordinary user, using a fixed time so both builds have the same
RPM Version and differ only by Release:

```bash
mvn clean package -Drpm.buildTime=2026-09-30T12:00:00Z -Drpm.release=1
cp demo/target/rpm-work/RPMS/noarch/*.rpm /tmp/demo-package-content-alt1.rpm
rpm -qplv /tmp/demo-package-content-alt1.rpm
rpm -qp --queryformat '[%{FILENAMES}|%{FILEMODES}|%{FILEUSERNAME}|%{FILEGROUPNAME}|%{FILEFLAGS}\n]' /tmp/demo-package-content-alt1.rpm
```

Expect `/etc/demo`, `/var/log/demo`, and `/usr/share/demo` to be directories
with mode `0755`, owned by `root:root`. Expect the JAR and configuration file
to have mode `0644`. `/etc/demo/demo.properties` must have both CONFIG and
NOREPLACE flags (numeric value includes bits `1` and `16`).

Install and edit the installed configuration as root:

```bash
su -
rpm -ivh /tmp/demo-package-content-alt1.rpm
printf 'example.message=user-value\n' > /etc/demo/demo.properties
exit
```

Change the packaged default as the ordinary user, then build Release alt2:

```bash
cp demo/src/main/rpm/demo.properties /tmp/demo-package-content-original.properties
printf 'example.message=updated-package-value\n' > demo/src/main/rpm/demo.properties
mvn clean package -Drpm.buildTime=2026-09-30T12:00:00Z -Drpm.release=2
cp demo/target/rpm-work/RPMS/noarch/*.rpm /tmp/demo-package-content-alt2.rpm
cp /tmp/demo-package-content-original.properties demo/src/main/rpm/demo.properties
```

After a successful second build, update as root:

```bash
su -
rpm -Uvh /tmp/demo-package-content-alt2.rpm
cat /etc/demo/demo.properties
cat /etc/demo/demo.properties.rpmnew
rpm -q demo
```

Expect the original path to keep `example.message=user-value`, the `.rpmnew`
file to contain `example.message=updated-package-value`, and the installed
release to be `alt2`. The demo HTTP server does not read this example file yet;
this check exercises package configuration handling.

Remove the package, inspect any retained configuration, and leave the root shell:

```bash
rpm -e demo
rpm -q demo
ls -ld /usr/share/demo /var/log/demo
ls -la /etc/demo
exit
```

The package, JAR directory, and empty log directory should be gone. RPM may
preserve the modified configuration as `.rpmsave` and retain `.rpmnew`, so
`/etc/demo` can remain nonempty. Inspect and remove those test files manually
only when no longer needed. The plugin deliberately does not erase user data.

Actual ALT build, installation, and upgrade results must be recorded after
running these steps; generation of a spec alone does not verify an RPM upgrade.
