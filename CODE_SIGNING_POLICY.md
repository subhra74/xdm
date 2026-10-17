# Code signing policy

Free code signing provided by [SignPath.io](https://about.signpath.io), certificate by [SignPath Foundation](https://signpath.org)

## What is signed

Only the Windows installers (`.msi`) of Xtreme Download Manager are signed. The macOS, Linux and source releases are not covered by this
certificate.

Every signed file is built by [GitHub Actions](.github/workflows/binaries.yml) from the source code in this
repository ([subhra74/xdm](https://github.com/subhra74/xdm)), at the tagged release commit. Nothing built on a
developer's machine is signed. Third-party components bundled in the installer (the Java runtime and
libraries) are not signed with this certificate.

## Team roles

| Role | Members |
|---|---|
| Committers and reviewers | [subhra74](https://github.com/subhra74) |
| Approvers | [subhra74](https://github.com/subhra74) |

- **Committers** may change the source code in this repository without further review.
- **Reviewers** review every change proposed by anyone else (pull requests) before it is merged, paying
  special attention to build scripts and CI configuration (`.github/`, `packaging/`, `pom.xml`).
- **Approvers** approve each signing request by hand before a release is signed.

All team members use multi-factor authentication on GitHub and SignPath.

## Privacy policy

This program will not transfer any information to other networked systems unless specifically requested by
the user or the person installing or operating it. The only connection it makes on its own is a check for
new versions, which sends no user data. See the full [privacy policy](PRIVACY.md).

## Reporting a problem

If you believe a signed XDM file is malicious or was not built from this repository, open an issue at
<https://github.com/subhra74/xdm/issues>, or report it to SignPath at support@signpath.io.
