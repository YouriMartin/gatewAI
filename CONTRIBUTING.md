# Contributing to gatewAI

Thanks for considering it. The full guide — prerequisites, build and test commands,
the hexagonal rules ArchUnit enforces, and how to make common changes — is
[`docs/developpment/contributing.md`](docs/developpment/contributing.md).

In short:

- `./mvnw -DskipFrontend verify` must pass (Checkstyle, tests, SpotBugs).
- English only, in code, comments, docs and commit messages; commit messages short
  and in the imperative mood.
- A change in behaviour updates the doc that describes it in the same pull request.
- A bug report goes through the issue template, which asks for what is needed to
  reproduce it. A vulnerability does not: see [`SECURITY.md`](SECURITY.md).
