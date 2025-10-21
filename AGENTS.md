# Repository Guidelines

## Project Structure & Module Organization
- `src/main/kotlin/actions` holds IntelliJ action classes such as `StartRecordingAction` and `StopRecordingAction`; group new actions by feature in this directory.
- `src/main/resources/META-INF` contains `plugin.xml` and bundled assets; register menu entries and update metadata here when adding behaviour.
- Gradle output lives in `build/`, with the IntelliJ sandbox under `build/idea-sandbox/`; both directories are disposable and should not be edited manually.

## Build, Test, and Development Commands
- `./gradlew build` compiles Kotlin sources, packages the plugin, and runs the configured JVM test suite.
- `./gradlew runIde` launches the plugin inside an IntelliJ IDEA sandbox for manual verification; the sandbox configuration persists under `build/idea-sandbox/`.
- `./gradlew verifyPlugin` inspects the plugin archive for structural issues before sharing artifacts; run it ahead of releases or hand-offs.

## Coding Style & Naming Conventions
- Kotlin code targets JVM 21 with Kotlin 2.1; use four-space indentation and keep package declarations aligned with the directory structure (e.g., `actions`).
- Name classes in `UpperCamelCase`, functions and properties in `lowerCamelCase`, and action IDs in `plugin.xml` using the existing `RecordEditor.*` prefix.
- Apply JetBrains auto-formatting (Code → Reformat) and add KDoc or line comments where logic is non-trivial or involves platform APIs.

## Testing Guidelines
- The Gradle script enables the IntelliJ Platform test framework; place new coverage in `src/test/kotlin`, mirroring production packages and suffixing classes with `Test`.
- Run `./gradlew test` for headless verification and extend with UI fixture tests when exercising action flows or menu wiring.
- Add regression tests whenever modifying existing action behaviour or plugin.xml registrations to avoid menu regressions.

## Commit & Pull Request Guidelines
- This snapshot lacks VCS history; craft imperative, present-tense subjects under ~65 characters (e.g., `Add stop-recording notifier`) and use the body for context.
- Reference issues in the footer (`Refs #123`) and isolate commits to single concerns to ease review and cherry-picking.
- Pull requests should summarize behaviour changes, list manual verification steps (`./gradlew runIde`, screenshots), and link to tracking tickets or specs.
