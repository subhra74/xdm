<!--
Thanks for contributing to XDM! Please read CONTRIBUTING.md first.
Security issues: don't open a PR or issue; report them privately:
https://github.com/subhra74/xdm/security/advisories/new
-->

## What and why

<!-- What does this change, and why is it needed? Link the issue it fixes, e.g. "Fixes #123". -->

## Type of change

- [ ] Bug fix
- [ ] New feature
- [ ] Performance improvement
- [ ] Refactoring (no behaviour change)
- [ ] Documentation
- [ ] Browser extension

## Area

- [ ] `xdm-core` (download engine, Apache 2.0)
- [ ] `xdm-app` (desktop app, GPL v3)
- [ ] `browser-extension` (GPL v3)
- [ ] Packaging / build / CI

## How it was tested

<!--
Which tests did you add or run? What did you try by hand, and on which OS (Windows / macOS / Linux)
and browser? For UI changes, add before/after screenshots.
-->

## Checklist

- [ ] The project builds (`mvn -q -pl xdm-app -am package`) and the relevant tests pass.
- [ ] New behaviour has tests, or this PR explains why it can't be tested.
- [ ] `xdm-core` stays free of Swing/app dependencies.
- [ ] Saved formats (config, download lists, `.info`) only gain new tagged fields; existing ones are unchanged.
- [ ] Every commit is signed off (`git commit -s`), per the [DCO](https://developercertificate.org/).
- [ ] Any code copied from elsewhere is named below with its source and license (permissive only for `xdm-core`).

## Notes for the reviewer

<!-- Anything else: trade-offs, follow-up work, third-party code and its license. -->
