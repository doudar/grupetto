# Grupetto contributor instructions

## CI and release workflows

- External fork pull requests must run their builds and tests in the fork. Do not approve or trigger upstream build jobs for a fork PR.
- When reviewing a fork PR, check for changes to `.github/workflows/`, local actions, and scripts or Gradle configuration that affect how upstream CI builds, signs, or publishes the app. Ask the contributor to remove changes to upstream CI behavior. A maintainer can propose the intended CI change separately from a branch in `doudar/grupetto`.
- Keep production signing credentials and release permissions out of jobs that execute fork code. Publish official APKs only from a trusted upstream build. Never promote a fork-provided artifact to an official release.
