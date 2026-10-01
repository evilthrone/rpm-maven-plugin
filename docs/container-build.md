# Container RPM builds

## Normal build

```powershell
mvn clean package
```

On ALT Linux the plugin uses the local `rpmbuild` and `rpm` tools. On Windows,
macOS and other Linux distributions it uses Docker with the official `alt:p11`
image. Docker must be installed and its Linux engine running. Maven still needs
Java 21 on the host.

The first build installs `rpm-build` in a derived image. Subsequent builds reuse
Docker's cached layers, including after `mvn clean`. Maven does not install RPM
tools on Windows. The resulting RPM is copied to
`demo/target/rpm-work/RPMS/noarch/` and attached to the demo Maven artifact.

ALT disallows RPM builds as root. The derived image contains an `rpm-builder`
user who owns its work directory. This account exists only inside the image.

The plugin copies files using `docker cp`, without mounting Windows directories.
Host paths are passed as separate process arguments; spaces in project paths
are supported. Temporary containers are removed after success or failure.

Verification uses the exact image ID recorded during the build in
`target/rpm-work/environment.txt`. It checks the actual RPM using `rpm` inside
that image. A new `prepare` invalidates the previous record.

## Repositories

By default only the official ALT repository is used:
`http://ftp.altlinux.org/pub/distributions/ALTLinux`, branch `p11`, components
`x86_64/classic` and `noarch/classic`. Other repositories enabled in the base
image are removed from the builder configuration.

An optional ordered list can be configured in the plugin's `<configuration>`:

```xml
<repositoryMirrors>
    <repositoryMirror>http://first.example/ALTLinux</repositoryMirror>
    <repositoryMirror>http://ftp.altlinux.org/pub/distributions/ALTLinux</repositoryMirror>
</repositoryMirrors>
```

The first repository must successfully provide both indexes and the RPM build
tools. If updating indexes or installing tools fails, the next repository is
tried. If all fail, Maven fails and reports the setup log. Repositories are used
only when preparing the container image; local ALT builds use installed tools.

To check fallback on Windows, deliberately reject the first connection:

```powershell
mvn clean package '-Drpm.repositories=http://127.0.0.1:9,http://ftp.altlinux.org/pub/distributions/ALTLinux'
```

The setup log should show failure of the first URL followed by success of the
official repository. The same image setup can be cached by Docker on later runs.

## Settings and failures

| Property | Default | Purpose |
| --- | --- | --- |
| `rpm.buildMode` | `auto` | `auto`, `local`, or `container` |
| `rpm.dockerExecutable` | `docker` | Docker CLI executable or its full path |
| `rpm.containerImage` | `alt:p11` | Base ALT p11 image |
| `rpm.repositories` | official repository | Comma-separated list overriding the XML list |
| `rpm.repositoryTimeoutSeconds` | `20` | Network timeout per APT operation |
| `rpm.containerTimeoutSeconds` | `900` | Maximum time to prepare the builder image |
| `rpm.commandTimeoutSeconds` | `120` | Maximum time for an individual tool command |
| `rpm.rpmbuildExecutable` | `rpmbuild` | Local build executable |
| `rpm.rpmExecutable` | `rpm` | Local query executable |

Containers run as `linux/amd64`. Generated packages remain `noarch`. The Maven
plugin requires Java 21; the generated RPM's Java dependency is configured
separately by `rpm.jreRequirement`.

Diagnostics are under `target/rpm-work/`: `container-setup.log`,
`container-build.log`, `container-rpm-*.log`, or `rpmbuild.log` / `rpm-*.log`
for local tools. Missing Docker, a stopped engine, failed commands and timeouts
fail Maven with the command and log location. A cleanup failure reports the
temporary container name for manual removal.

Container building verifies package contents. Testing systemd service startup,
upgrade and removal still requires a running ALT system with systemd.

## Verified results (2026-10-02)

- Windows: real RPM build and verification using the official `alt:p11` base
  image and the official ALT repository. ALT's restriction against building as
  root was addressed by the separate container build account.
- Fallback: a fresh derived image was prepared with `http://127.0.0.1:9` first
  and the official repository second. The first connection was refused, the
  second repository provided indexes and build tools, and RPM build and
  verification succeeded.
- The fallback build ran from `target/container path check/`, exercising an
  actual Windows project path containing spaces.
- Tests: 50 total, 49 passed, one existing symbolic-link test skipped on Windows;
  no failures or errors. Twelve tests were added for environment selection,
  repository configuration, recorded image IDs, process failures, argument
  handling and timeout termination. Checkstyle reported zero violations.
- A deliberately missing Docker executable produced an immediate Maven error.

Local ALT execution and systemd installation were not rerun on an ALT VM during
this stage. Container verification checks package metadata and contents; it
does not start the packaged service.
