# FocusFlow repository instructions

## Read first

- `README.md`
- `docs/6.2-structure-plan.md` when working on 6.2
- `CHANGELOG.md`
- `app/src/main/java/com/sakata/focusflow/RoadmapData.kt`

## Product boundaries

- FocusFlow is a local-first Android schedule and execution assistant.
- Preserve the daily loop: capture → schedule → remind → complete/postpone → retain history.
- Do not surface low-frequency tools in the daily path unless relevant data or an explicit setting enables them.
- Do not add a switch for presentation-only behavior; switches are for background work, permissions, notifications, or meaningful user choice.
- Treat AI, maps, tutorials, commute learning, and app detection as optional tools. Never require them for the core workflow.

## Compatibility and safety

- Preserve package name `com.sakata.focusflow`.
- Existing SharedPreferences keys and JSON fields are user data contracts. Add defaults and migrations before changing them.
- Do not delete, reset, or silently replace user data.
- Notification behavior must remain conservative: ignored or stale prompts must not repeatedly return.
- Never place signing keys, passwords, API keys, or Base64 keystores in tracked files or logs.
- GitHub Actions must fail rather than fall back to an incompatible debug signing key.

## Development workflow

- Use one feature branch per version or bounded phase; do not work directly on `main`.
- Keep structure-only changes separate from behavior changes.
- Update `CHANGELOG.md`, `README.md`, and `RoadmapData.kt` only when a release scope is actually implemented.
- FocusFlow uses 0.1 product-version increments, with patch versions for release fixes. The current release candidate is `7.1.4` / versionCode 479. Device acceptance is required before labeling it stable.
- Do not merge a pull request without explicit user confirmation.

## Verification

- Add pure Kotlin unit tests for time, planning, migration, and reminder-policy logic.
- Run `:app:testDebugUnitTest` and `:app:assembleDebug` in CI before offering an APK.
- Use only the stable-signed GitHub Actions artifact for installation testing.
- Target-device verification is OPPO / ColorOS 16 / Android 15, especially notification permission, channels, background restrictions, reboot recovery, and meal-dismiss behavior.

## Agent handoff

- Start by reporting the checked-out branch, HEAD, working-tree state, and the approved phase.
- Read the live repository as the source of truth; do not infer completion only from roadmap labels.
- Stop at the end of the assigned phase and report changed files, observed test evidence, risks, and next-step recommendation.
