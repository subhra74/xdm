# Contributing to XDM

Thanks for your interest in improving Xtreme Download Manager! Bug reports, fixes, features,
translations and documentation are all welcome.

## Before you start

- **Bugs and feature ideas:** open an issue first for anything bigger than a small fix, so we can
  agree on the approach before you spend time on it.
- **Security issues:** please do **not** open a public issue. Report it privately through
  [GitHub's private vulnerability reporting](https://github.com/subhra74/xdm/security/advisories/new).
- **Translations:** see [TRANSLATING.md](TRANSLATING.md). Translations go through Weblate and do not
  need a pull request.

## Building and testing

See [Building from source](README.md#-building-from-source) in the README. In short:

```bash
mvn -q -pl xdm-app -am package
```

```bash
mvn -q -pl xdm-core test -DskipTests=false -Dtest=SomeTest
```

JDK 25 is selected through a Maven toolchain (`~/.m2/toolchains.xml`; a sample is in
`packaging/toolchains.sample.xml`). Please run the tests for the area you changed before opening a
pull request, and add tests for new behaviour where practical.

## Project layout

| Module | What it is |
|---|---|
| `xdm-core` | Download engine (HTTP, HLS, DASH, muxing). Pure Kotlin/JVM, JDK 8, **no UI dependencies**. |
| `xdm-app` | The desktop app: Swing UI and browser integration. |
| `browser-extension` | The Chrome and Firefox extensions. |

## Code guidelines

- Kotlin sources live in `src/main/java`. Match the style of the code around your change.
- `xdm-core` must not depend on Swing or app types; it talks to the app through `DownloadHost`.
- All Swing UI changes happen on the Event Dispatch Thread.
- On-disk formats (config, download lists, `.info`) use numbered tagged fields: add a new number for a
  new field, never reuse or change the type of an existing one.
- Tests use JUnit 5; put the assertion message **last**.
- Keep pull requests focused: one fix or feature per PR, with a clear description of what changed
  and why.

## Pull request checklist

1. The project builds and the relevant tests pass.
2. New behaviour has tests, or the PR explains why it can't be tested.
3. The PR description says what changed, why, and how you tested it.
4. Every commit is signed off (see below).

## Licensing of contributions

The repository uses two licenses:

| Part | License |
|---|---|
| `xdm-core` | [Apache License 2.0](xdm-core/LICENSE) |
| Everything else (`xdm-app`, browser extensions, ...) | [GNU GPL v3](LICENSE) |

A contribution is licensed under the license of the part it changes: changes to `xdm-core` under
the Apache License 2.0 (as its section 5 provides), everything else under the GPL v3. You keep the
copyright to your contribution. New `xdm-core` source files start with the same header as the
existing ones:

```kotlin
// Copyright 2026 Subhra Das Gupta
// SPDX-License-Identifier: Apache-2.0
```

If you add a file that is substantially your own work, you may add your own copyright line above
the SPDX line.

## Signing off your commits (DCO)

Instead of a contributor license agreement, XDM uses the
[Developer Certificate of Origin](https://developercertificate.org/) (DCO). By adding a
`Signed-off-by` line to a commit, you certify that you wrote the change, or otherwise have the right
to submit it under the project's licenses as described above.

Git adds the line for you with `-s`:

```bash
git commit -s -m "Fix resume after a server restart"
```

The line uses the name and email from your Git config, for example:

```
Signed-off-by: Jane Doe <jane@example.com>
```

Use your real name. If you forgot to sign off, `git commit --amend -s` fixes the last commit and
`git rebase --signoff master` fixes all commits on your branch.

Code copied from another project needs to be under a license compatible with the part it goes into
(for `xdm-core`, a permissive license such as Apache 2.0, MIT or BSD; GPL code cannot go into
`xdm-core`). Say where it came from in your pull request.

## Code of conduct

Everyone taking part in this project is expected to follow the
[Code of Conduct](CODE_OF_CONDUCT.md).
