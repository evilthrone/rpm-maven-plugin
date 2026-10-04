# Container RPM builds

## Normal build

```powershell
mvn clean package
```

On ALT Linux the plugin uses the local `rpmbuild` and `rpm` tools. On Windows,
macOS and other Linux distributions it uses Docker with the official `alt:p11`
image. Docker must be installed and its Linux engine running. Maven still needs
JDK 21+ on the host.

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
plugin requires JDK 21+; the generated RPM's Java dependency is configured
separately by `rpm.jreRequirement`.

Diagnostics are under `target/rpm-work/`: `container-setup.log`,
`container-build.log`, `container-rpm-*.log`, or `rpmbuild.log` / `rpm-*.log`
for local tools. Missing Docker, a stopped engine, failed commands and timeouts
fail Maven with the command and log location. A cleanup failure reports the
temporary container name for manual removal.

Before preparing a container image, and before querying a container-built RPM,
the plugin checks engine availability with `docker info`. If the CLI is missing,
the engine is stopped or the check times out, Maven reports:
`Docker engine is not available now. Please run Docker Desktop.`
The original diagnostic is preserved in `container-engine.log` when available.
Local ALT builds do not perform this Docker check.

Container building verifies package contents. Testing systemd service startup,
upgrade and removal still requires a running ALT system with systemd.

## GitHub Actions

The `Maven checks` workflow has three independent jobs on Ubuntu 24.04:

- `build`: Maven compiles Java and builds JARs on the Ubuntu runner, with test
  execution skipped. The plugin runs `rpmbuild` in ALT p11 and verifies the
  resulting RPM using `rpm` in the same image. The job has a 30-minute timeout
  to allow the first container image to download and install build tools.
- `test`: Maven runs JUnit tests directly on Ubuntu. These tests do not require
  Docker. Test reports are uploaded even if tests fail.
- `lint`: Checkstyle checks Java source and test code on Ubuntu.

The workflow runs for pull requests targeting `main`, pushes to `main`, and
manual `workflow_dispatch` runs. Pushing a feature branch without an open PR
does not automatically start this workflow.

A successful build uploads `demo-rpm`. Missing RPM output fails the job. The
`rpm-build-logs` artifact contains available RPM logs, the generated spec and
the recorded image ID. The test job uploads `junit-reports`. Artifacts are
retained for 14 days.

To download the RPM inside an ALT VM:

1. Sign in to GitHub in the VM's browser and open this repository.
2. Open **Actions**, select **Maven checks**, then select the intended run.
3. In **Artifacts**, download **demo-rpm** and extract the ZIP archive.
4. Install the extracted RPM as root and test the service on the VM.

See [GitHub's artifact download instructions](https://docs.github.com/en/actions/how-tos/manage-workflow-runs/download-workflow-artifacts?tool=webui).
