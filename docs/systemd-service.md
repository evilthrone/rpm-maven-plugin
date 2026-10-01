# systemd service on ALT p11

Service integration is optional. Add `<service>` to the consuming plugin's
`<configuration>` to generate a unit and account creation scriptlets:

```xml
<service>
    <name>my-app</name>
    <user>my-app</user>
    <group>my-app</group>
    <description>My Java service</description>
    <restart>on-failure</restart>
    <arguments>
        <argument>8081</argument>
    </arguments>
    <environment>
        <JAVA_OPTS>-Xmx128m</JAVA_OPTS>
        <!-- Optional: a stable JDK directory on the target machine -->
        <JAVA_HOME>/path/to/jdk</JAVA_HOME>
    </environment>
</service>
```

Omit the example `JAVA_HOME` entry unless you have a real target path.
Without it, the launcher uses `javaExecutable`, defaulting to `java` from PATH.
The demo uses the repository Java selected by the system and does not pin a
patch-version directory. The JRE dependency remains independently configurable.

`name` defaults to the RPM name, `user` to the RPM name, and `group` to the user.
Names accept lowercase letters, digits, underscores and hyphens, beginning with
a letter or underscore. `root` is rejected for user and group.
`description` defaults to `Java application`, `restart` to `on-failure`.
Arguments preserve their boundaries; systemd `%` specifiers and `$` expansion
in arguments are escaped. Environment values are literal single lines.

The unit is installed at `/lib/systemd/system/<name>.service` with `0644`,
owned by `root:root`. It runs `/usr/bin/<rpm-name>` as the configured user/group,
uses `Type=simple`, and declares `WantedBy=multi-user.target`.

## Ready-made unit

```xml
<service>
    <user>my-app</user>
    <group>my-app</group>
    <unitFile>${project.basedir}/src/main/rpm/my-app.service</unitFile>
</service>
```

Relative paths resolve against the consuming project. The source must be a
regular file without symbolic links. CRLF is normalized to LF.
The file must contain exactly one matching `User=`, `Group=` and nonempty
`ExecStart=` in `[Service]`. `DynamicUser` and line continuations are rejected.
Other directives are retained as supplied. Generated description, arguments,
environment and restart settings do not modify a supplied unit.
The author is responsible for its systemd semantics; the plugin's checks are
not a full systemd unit parser. Validate it on the target with
`systemd-analyze verify /lib/systemd/system/my-app.service`.

## Accounts and directory ownership

`%pre` checks accounts with `getent` before creating them with `groupadd -r`
and `useradd -r`. The account has no created home directory and uses `/dev/null`
as its login shell. Existing accounts are reused without modification.
Failures creating a missing account fail `%pre` instead of being ignored.
Accounts remain after uninstall to keep UID/GID associations with retained
user data. They are not deleted or recreated during upgrades.

Use ordinary mappings for writable directories:

```xml
<mapping>
    <destination>/var/log/my-app</destination>
    <mode>0750</mode>
    <owner>my-app</owner>
    <group>my-app</group>
</mapping>
```

The current demo applies this to `/var/log/demo`. The JAR, launcher, configuration
and unit remain owned by root. DemoServer writes logs to stdout/journald;
the log directory demonstrates access permissions and is not currently used.

## ALT scriptlets

The spec declares dependencies on account and service helper executables.
`%post_service <name>` registers a new service using the system's preset policy
and runs `try-restart` on upgrades. It does not explicitly start a stopped
service. `%preun_service <name>` does nothing during upgrades and disables and
stops the service on final removal. These behaviors were confirmed from the
helper scripts supplied by the ALT p11 test VM.

## Manual acceptance check

Run builds as the ordinary user. Fixed time and release numbers below are only
for comparing two packages with the same RPM Version:

```bash
mvn clean package -Drpm.buildTime=2026-10-01T12:00:00Z -Drpm.release=1
cp demo/target/rpm-work/RPMS/noarch/*.rpm /tmp/demo-service-alt1.rpm
rpm -qplv /tmp/demo-service-alt1.rpm
rpm -qp --scripts /tmp/demo-service-alt1.rpm
su -
rpm -Uvh /tmp/demo-service-alt1.rpm
getent passwd demo
getent group demo
ls -ld /var/log/demo
systemd-analyze verify /lib/systemd/system/demo.service
systemctl start demo
systemctl status demo --no-pager
systemctl show demo -p User -p Group -p MainPID -p Environment
curl -i http://localhost:8081/health
systemctl stop demo
curl --max-time 3 -i http://localhost:8081/health
systemctl start demo
systemctl restart demo
curl -i http://localhost:8081/health
systemctl show demo -p MainPID
exit
```

Ensure port 8081 is free before starting. Expect `demo:demo`, mode `0750` on
the log directory, an active service, and HTTP 200/OK after start/restart.
The request after stop must fail to connect. Record MainPID before upgrading.
For diagnostics, use `journalctl -u demo -n 50 --no-pager` as root.

Build alt2 as the ordinary user while the service is running:

```bash
mvn clean package -Drpm.buildTime=2026-10-01T12:00:00Z -Drpm.release=2
cp demo/target/rpm-work/RPMS/noarch/*.rpm /tmp/demo-service-alt2.rpm
su -
rpm -Uvh /tmp/demo-service-alt2.rpm
rpm -q demo
systemctl is-active demo
systemctl show demo -p MainPID
curl --retry 10 --retry-connrefused --retry-delay 1 -i http://localhost:8081/health
rpm -e demo
systemctl is-active demo
curl --max-time 3 -i http://localhost:8081/health
test ! -e /lib/systemd/system/demo.service && echo 'Unit removed'
test ! -e /usr/bin/demo && echo 'Launcher removed'
test ! -e /var/log/demo && echo 'Empty log directory removed'
exit
```

Expect alt2 installed, a new nonzero MainPID after the upgrade, and HTTP 200/OK.
After removal the service must not be active and the endpoint must not respond.
Preserved configuration backups and nonempty application data may remain;
RPM does not erase user changes. The service account remains intentionally.
The commands above are a verification procedure, not a claim that VM acceptance
has already passed for this stage.
