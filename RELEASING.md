# Releasing CursorJ

This guide documents the steps for shipping a new CursorJ version.

## 1) Pre-release checklist

- Ensure your branch is up to date and CI is green.
- Confirm local JDK is 21 (`java -version`).
- Verify any release-critical changes are documented in `CHANGELOG.md`.
- Verify JetBrains Marketplace metadata is up to date in `build.gradle.kts`:
  - `pluginConfiguration.description`
  - `pluginConfiguration.changeNotes`
- Verify minimum IDE compatibility in `gradle.properties` (`pluginSinceBuild`). There is no `until-build` cap; `build.gradle.kts` sets `untilBuild = provider { null }` for open-ended upper compatibility.

## 2) Bump version and release notes

1. Update `pluginVersion` in `gradle.properties`.
2. Add a new version section in `CHANGELOG.md`.
3. Update `pluginConfiguration.changeNotes` in `build.gradle.kts` to match release highlights.
4. Update `pluginConfiguration.description` in `build.gradle.kts` if feature bullets changed.
5. Sanity-check consistency across:
   - `CHANGELOG.md`
   - `README.md` feature/config text
   - Marketplace metadata in `build.gradle.kts`

## 3) Local verification

Run the full local gate before publishing:

```bash
./gradlew --no-daemon clean build
./gradlew --no-daemon verifyPlugin
./gradlew --no-daemon buildPlugin
```

Expected output artifact:

- `build/distributions/cursorj-<version>.zip`

Plugin verifier reports are written to:

- `build/reports/pluginVerifier/`

## 4) Optional manual smoke test

Install the generated ZIP into a sandbox IDE via:

- **Settings > Plugins > Install Plugin from Disk**

Then validate at least:

- CursorJ tool window opens.
- Chat can start a session.
- File and terminal tool calls work.
- Authentication works (run `agent login` beforehand).

## 5) Publish to JetBrains Marketplace

CI publishing is automated by `.github/workflows/release.yml`.
It runs on `v*` tags and expects repository secret:

- `JETBRAINS_MARKETPLACE_TOKEN`

To publish via CI:

1. Push your release commit.
2. Create and push a version tag (for example `v0.1.0`).
3. Confirm the `Release` workflow succeeds.
4. Open the Marketplace listing and verify the published description and release notes reflect the current release.

For local publishing:
For local publishing, run:

```bash
./gradlew --no-daemon publishPlugin -PintellijPlatformPublishingToken=<YOUR_MARKETPLACE_TOKEN>
```

Alternatively, set environment variable:

- `ORG_GRADLE_PROJECT_intellijPlatformPublishingToken`

Then run:

```bash
./gradlew --no-daemon publishPlugin
```

## 6) Post-release

- Create a Git tag for the released version.
- Verify Marketplace page metadata, description, and release notes.
- Monitor first-install and runtime feedback for regressions.
